#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${GITHUB_TOKEN:?GITHUB_TOKEN is required}"
: "${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}"

override="${PROVENANCE_RUN_ID_OVERRIDE:-}"
artifact_name="semantic-onnx-provenance"
api_headers=(
  -H "Accept: application/vnd.github+json"
  -H "Authorization: Bearer ${GITHUB_TOKEN}"
  -H "X-GitHub-Api-Version: 2022-11-28"
)

require_successful_run() {
  local candidate="$1"
  local run_api="https://api.github.com/repos/${GITHUB_REPOSITORY}/actions/runs/${candidate}"
  local run_payload
  run_payload="$(
    curl --fail --silent --show-error --location "${api_headers[@]}" "$run_api"
  )"

  printf '%s' "$run_payload" | python3 -c '
import json
import sys

payload = json.load(sys.stdin)
status = payload.get("status")
conclusion = payload.get("conclusion")
if status != "completed" or conclusion != "success":
    raise SystemExit(1)
'
}

if [[ -n "$override" ]]; then
  if [[ ! "$override" =~ ^[0-9]+$ ]]; then
    echo "invalid provenance run override: $override" >&2
    exit 2
  fi
  if ! require_successful_run "$override"; then
    echo "provenance run override is not a completed successful workflow: $override" >&2
    exit 4
  fi
  run_id="$override"
else
  artifacts_api="https://api.github.com/repos/${GITHUB_REPOSITORY}/actions/artifacts?name=${artifact_name}&per_page=100"
  payload="$(
    curl --fail --silent --show-error --location "${api_headers[@]}" "$artifacts_api"
  )"

  mapfile -t candidates < <(
    printf '%s' "$payload" | python3 -c '
import json
import sys

artifact_name = sys.argv[1]
payload = json.load(sys.stdin)
candidates = []
for artifact in payload.get("artifacts", []):
    if artifact.get("name") != artifact_name:
        continue
    if artifact.get("expired"):
        continue
    workflow_run = artifact.get("workflow_run") or {}
    run_id = workflow_run.get("id")
    created_at = artifact.get("created_at") or ""
    if isinstance(run_id, int):
        candidates.append((created_at, run_id))

for _, run_id in sorted(set(candidates), reverse=True):
    print(run_id)
' "$artifact_name"
  )

  run_id=""
  for candidate in "${candidates[@]}"; do
    if require_successful_run "$candidate"; then
      run_id="$candidate"
      break
    fi
  done

  if [[ -z "$run_id" ]]; then
    echo "no non-expired semantic provenance artifact from a successful workflow run found" >&2
    exit 5
  fi
fi

if [[ ! "$run_id" =~ ^[0-9]+$ ]]; then
  echo "resolved provenance run id is invalid: $run_id" >&2
  exit 3
fi

echo "semantic provenance run id: $run_id"
echo "run_id=$run_id" >> "$GITHUB_OUTPUT"
