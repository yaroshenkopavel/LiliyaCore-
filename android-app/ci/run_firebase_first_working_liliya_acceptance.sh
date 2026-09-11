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
EOF
}

if [[ $# -lt 1 || $# -gt 2 ]]; then
  usage
  exit 64
fi

BUNDLE_DIR="$1"
GCP_PROJECT="${2:-liliyacore-test-lab}"

readonly DEVICE_MODEL="z3q"
readonly DEVICE_VERSION="33"
readonly DEVICE_LOCALE="en"
readonly DEVICE_ORIENTATION="portrait"
readonly TEST_CLASS="pro.liliya.app.DevelopmentFirstWorkingLiliyaColdStartInstrumentedTest"

command -v gcloud >/dev/null 2>&1 || {
  echo "gcloud is required" >&2
  exit 69
}

[[ -d "$BUNDLE_DIR" ]] || {
  echo "bundle directory not found: $BUNDLE_DIR" >&2
  exit 66
}

active_account="$(gcloud auth list --filter=status:ACTIVE --format='value(account)' 2>/dev/null | head -n 1)"
if [[ -z "$active_account" ]]; then
  echo "no active gcloud account; authenticate outside this script first" >&2
  exit 77
fi

mapfile -t test_apks < <(find "$BUNDLE_DIR" -type f -name '*androidTest*.apk' -print | sort)
mapfile -t app_apks < <(find "$BUNDLE_DIR" -type f -name '*.apk' ! -name '*androidTest*.apk' -print | sort)

if [[ "${#test_apks[@]}" -ne 1 || "${#app_apks[@]}" -ne 1 ]]; then
  echo "expected exactly one target APK and one androidTest APK" >&2
  exit 65
fi

APP_APK="${app_apks[0]}"
TEST_APK="${test_apks[0]}"

gcloud config set project "$GCP_PROJECT" >/dev/null
configured_project="$(gcloud config get-value project 2>/dev/null)"
if [[ "$configured_project" != "$GCP_PROJECT" ]]; then
  echo "gcloud project mismatch: expected $GCP_PROJECT, got $configured_project" >&2
  exit 78
fi

echo "=== Firebase physical ARM64: First Working Liliya ==="
echo "account=$active_account"
echo "project=$GCP_PROJECT"
echo "device=${DEVICE_MODEL}-api${DEVICE_VERSION}"
echo "testClass=$TEST_CLASS"
echo "appApk=$APP_APK"
echo "testApk=$TEST_APK"

gcloud firebase test android run \
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
