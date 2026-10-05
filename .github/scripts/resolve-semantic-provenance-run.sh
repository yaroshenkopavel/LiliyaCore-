#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${GITHUB_TOKEN:?GITHUB_TOKEN is required}"
: "${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}"

override="${PROVENANCE_RUN_ID_OVERRIDE:-}"
artifact_name="semantic-onnx-provenance"

if [[ -n "$override" ]]; then
  if [[ ! "$override" =~ ^[0-9]+$ ]]; then
    echo "invalid provenance run override: $override" >&2
    exit 2
  fi
  run_id="$override"
else
  api="https://api.github.com/repos/${GITHUB_REPOSITORY}/actions/artifacts?name=${artifact_name}&per_page=100"
  payload="$(
    curl --fail --silent --show-error --location       -H "Accept: application/vnd.github+json"       -H "Authorization: Bearer ${GITHUB_TOKEN}"       -H "X-GitHub-Api-Version: 2022-11-28"       "$api"
  )"

  run_id="$(
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

if not candidates:
    raise SystemExit("no non-expired semantic provenance artifact found")

candidates.sort(reverse=True)
print(candidates[0][1])
' "$artifact_name"
  )"
fi

if [[ ! "$run_id" =~ ^[0-9]+$ ]]; then
  echo "resolved provenance run id is invalid: $run_id" >&2
  exit 3
fi

echo "semantic provenance run id: $run_id"
echo "run_id=$run_id" >> "$GITHUB_OUTPUT"
