#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
usage:
  run_firebase_first_working_liliya_acceptance.sh <bundle-dir> [gcp-project]

Runs the exact First Working Liliya cold-start instrumentation class on a
physical Firebase Test Lab ARM64 Android device using prebuilt APKs from the
GitHub-produced bundle.

This script does not authenticate to Google Cloud. An active gcloud account must
already be configured by the caller.

Set LILIYA_FIREBASE_VERIFY_ONLY=1 to verify the bundle contract and file digests
without requiring gcloud or starting a Firebase matrix.
EOF
}

if [[ $# -lt 1 || $# -gt 2 ]]; then
  usage
  exit 64
fi

BUNDLE_DIR="$1"
GCP_PROJECT="${2:-liliyacore-test-lab}"
VERIFY_ONLY="${LILIYA_FIREBASE_VERIFY_ONLY:-0}"

readonly DEVICE_MODEL="z3q"
readonly DEVICE_VERSION="33"
readonly DEVICE_LOCALE="en"
readonly DEVICE_ORIENTATION="portrait"
readonly DEVICE_POLICY="firebase-physical-arm64-z3q-api33"
readonly TEST_CLASS="pro.liliya.app.DevelopmentFirstWorkingLiliyaColdStartInstrumentedTest"
readonly AUTHENTICATION_POLICY="external-preauthenticated-gcloud-only"
readonly EVIDENCE_CLASS="firebase-first-working-liliya-input-bundle"
readonly MANIFEST_NAME="firebase-first-working-liliya-bundle-manifest.json"

[[ "$VERIFY_ONLY" == "0" || "$VERIFY_ONLY" == "1" ]] || {
  echo "LILIYA_FIREBASE_VERIFY_ONLY must be 0 or 1" >&2
  exit 64
}

[[ -d "$BUNDLE_DIR" ]] || {
  echo "bundle directory not found: $BUNDLE_DIR" >&2
  exit 66
}

command -v python3 >/dev/null 2>&1 || {
  echo "python3 is required for bundle verification" >&2
  exit 69
}

MANIFEST="$BUNDLE_DIR/$MANIFEST_NAME"
[[ -f "$MANIFEST" ]] || {
  echo "bundle manifest not found: $MANIFEST" >&2
  exit 65
}

python3 - \
  "$BUNDLE_DIR" \
  "$EVIDENCE_CLASS" \
  "$DEVICE_POLICY" \
  "$TEST_CLASS" \
  "$AUTHENTICATION_POLICY" <<'PY'
import hashlib
import json
from pathlib import Path
import re
import sys

bundle = Path(sys.argv[1]).resolve()
expected_evidence_class = sys.argv[2]
expected_device_policy = sys.argv[3]
expected_test_class = sys.argv[4]
expected_authentication_policy = sys.argv[5]
manifest_path = bundle / "firebase-first-working-liliya-bundle-manifest.json"

try:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
except (OSError, json.JSONDecodeError) as exc:
    raise SystemExit(f"invalid bundle manifest: {exc}")

expected_fields = {
    "evidenceClass": expected_evidence_class,
    "devicePolicy": expected_device_policy,
    "testClass": expected_test_class,
    "authenticationPolicy": expected_authentication_policy,
}
for key, expected in expected_fields.items():
    actual = manifest.get(key)
    if actual != expected:
        raise SystemExit(f"manifest {key} mismatch: expected {expected!r}, got {actual!r}")

if manifest.get("acceptanceEvidence") is not False:
    raise SystemExit("manifest acceptanceEvidence must be false before physical execution")

exact_commit = manifest.get("exactCommitSha")
if not isinstance(exact_commit, str) or re.fullmatch(r"[0-9a-f]{40}", exact_commit) is None:
    raise SystemExit("manifest exactCommitSha must be a full lowercase Git SHA")

provenance_run_id = manifest.get("semanticProvenanceRunId")
if not isinstance(provenance_run_id, str) or not provenance_run_id.isdigit():
    raise SystemExit("manifest semanticProvenanceRunId must be a numeric string")

files = manifest.get("files")
if not isinstance(files, list) or not files:
    raise SystemExit("manifest files must be a non-empty list")

required_names = {
    "android-app-debug.apk",
    "android-app-debug-androidTest.apk",
    "firebase-first-working-liliya-app-apk-files.txt",
    "firebase-first-working-liliya-app-badging.txt",
    "firebase-first-working-liliya-test-apk-files.txt",
    "firebase-first-working-liliya-test-badging.txt",
    "firebase-first-working-liliya-test-manifest.txt",
    "run_firebase_first_working_liliya_acceptance.sh",
}

seen = set()
for entry in files:
    if not isinstance(entry, dict):
        raise SystemExit("manifest file entry must be an object")
    name = entry.get("name")
    expected_bytes = entry.get("bytes")
    expected_sha = entry.get("sha256")
    if not isinstance(name, str) or not name or Path(name).name != name:
        raise SystemExit(f"invalid manifest file name: {name!r}")
    if name in seen:
        raise SystemExit(f"duplicate manifest file entry: {name}")
    seen.add(name)
    if not isinstance(expected_bytes, int) or expected_bytes < 0:
        raise SystemExit(f"invalid manifest byte count for {name}")
    if not isinstance(expected_sha, str) or re.fullmatch(r"[0-9a-f]{64}", expected_sha) is None:
        raise SystemExit(f"invalid manifest sha256 for {name}")

    path = bundle / name
    if not path.is_file():
        raise SystemExit(f"bundle file missing: {name}")
    actual_bytes = path.stat().st_size
    if actual_bytes != expected_bytes:
        raise SystemExit(
            f"bundle byte-count mismatch for {name}: expected {expected_bytes}, got {actual_bytes}"
        )
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    actual_sha = digest.hexdigest()
    if actual_sha != expected_sha:
        raise SystemExit(
            f"bundle sha256 mismatch for {name}: expected {expected_sha}, got {actual_sha}"
        )

missing = required_names - seen
if missing:
    raise SystemExit(f"manifest missing required bundle files: {sorted(missing)}")

apk_names = sorted(name for name in seen if name.endswith(".apk"))
if apk_names != ["android-app-debug-androidTest.apk", "android-app-debug.apk"]:
    raise SystemExit(f"unexpected APK set in bundle manifest: {apk_names}")

print(f"bundle verified: exactCommitSha={exact_commit}")
print(f"bundle verified: semanticProvenanceRunId={provenance_run_id}")
PY

mapfile -t test_apks < <(find "$BUNDLE_DIR" -maxdepth 1 -type f -name '*androidTest*.apk' -print | sort)
mapfile -t app_apks < <(find "$BUNDLE_DIR" -maxdepth 1 -type f -name '*.apk' ! -name '*androidTest*.apk' -print | sort)

if [[ "${#test_apks[@]}" -ne 1 || "${#app_apks[@]}" -ne 1 ]]; then
  echo "expected exactly one target APK and one androidTest APK" >&2
  exit 65
fi

APP_APK="${app_apks[0]}"
TEST_APK="${test_apks[0]}"

if [[ "$VERIFY_ONLY" == "1" ]]; then
  echo "Firebase bundle verification completed successfully; physical matrix not started."
  exit 0
fi

command -v gcloud >/dev/null 2>&1 || {
  echo "gcloud is required" >&2
  exit 69
}

active_account="$(gcloud auth list --filter=status:ACTIVE --format='value(account)' 2>/dev/null | head -n 1)"
if [[ -z "$active_account" ]]; then
  echo "no active gcloud account; authenticate outside this script first" >&2
  exit 77
fi

echo "=== Firebase physical ARM64: First Working Liliya ==="
echo "account=$active_account"
echo "project=$GCP_PROJECT"
echo "device=${DEVICE_MODEL}-api${DEVICE_VERSION}"
echo "testClass=$TEST_CLASS"
echo "appApk=$APP_APK"
echo "testApk=$TEST_APK"

gcloud firebase test android run \
  --project "$GCP_PROJECT" \
  --type instrumentation \
  --app "$APP_APK" \
  --test "$TEST_APK" \
  --device "model=${DEVICE_MODEL},version=${DEVICE_VERSION},locale=${DEVICE_LOCALE},orientation=${DEVICE_ORIENTATION}" \
  --test-targets "class ${TEST_CLASS}" \
  --timeout 45m \
  --client-details "matrixLabel=LiliyaCore-First-Working-Liliya"

echo
echo "Firebase physical First Working Liliya matrix completed successfully."
echo "Preserve the matrix ID and raw instrumentation evidence before declaring physical acceptance."
