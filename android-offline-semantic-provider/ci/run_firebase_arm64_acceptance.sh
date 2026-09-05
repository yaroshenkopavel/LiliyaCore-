#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
usage:
  run_firebase_arm64_acceptance.sh <app-apk> <androidTest-apk> [gcp-project]

Runs the exact Offline Semantic Provider v0.1 physical ARM64 resource acceptance in Firebase Test Lab.
The supplied APKs must contain the current resource-test harness and exact staged ONNX test assets.
EOF
}

if [[ $# -lt 2 || $# -gt 3 ]]; then
  usage
  exit 64
fi

APP_APK="$1"
TEST_APK="$2"
GCP_PROJECT="\${3:-liliyacore-test-lab}"

readonly FIXTURE_SHA256="11f460a6600163508a6eca0f2ccd8df9272fafbbee10579b3dafa74217b084dc"
readonly FIXTURE_SIZE_BYTES="118258170"
readonly TEST_CLASS="pro.liliya.android.semanticprovider.OfflineSemanticProviderResourceInstrumentedTest"
readonly DEVICE_MODEL="z3q"
readonly DEVICE_VERSION="33"
readonly DEVICE_LOCALE="en"
readonly DEVICE_ORIENTATION="portrait"

command -v gcloud >/dev/null 2>&1 || {
  echo "gcloud is required" >&2
  exit 69
}

[[ -f "$APP_APK" ]] || {
  echo "app APK not found: $APP_APK" >&2
  exit 66
}

[[ -f "$TEST_APK" ]] || {
  echo "androidTest APK not found: $TEST_APK" >&2
  exit 66
}

gcloud config set project "$GCP_PROJECT" >/dev/null
configured_project="$(gcloud config get-value project 2>/dev/null)"
if [[ "$configured_project" != "$GCP_PROJECT" ]]; then
  echo "gcloud project mismatch: expected $GCP_PROJECT, got $configured_project" >&2
  exit 78
fi

head_short="unknown"
if command -v git >/dev/null 2>&1 && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  head_short="$(git rev-parse --short=12 HEAD)"
fi
matrix_label="LiliyaCore-ONNX-ARM64-\${head_short}"

echo "=== Firebase physical ARM64 acceptance ==="
echo "project=$GCP_PROJECT"
echo "matrixLabel=$matrix_label"
echo "device=\${DEVICE_MODEL}-api\${DEVICE_VERSION}"
echo "fixtureSha256=$FIXTURE_SHA256"
echo "fixtureSizeBytes=$FIXTURE_SIZE_BYTES"
echo "testClass=$TEST_CLASS"

gcloud firebase test android run \
  --type instrumentation \
  --app "$APP_APK" \
  --test "$TEST_APK" \
  --device "model=\${DEVICE_MODEL},version=\${DEVICE_VERSION},locale=\${DEVICE_LOCALE},orientation=\${DEVICE_ORIENTATION}" \
  --test-targets "class \${TEST_CLASS}" \
  --environment-variables "semanticFixtureSha256=\${FIXTURE_SHA256},semanticFixtureSizeBytes=\${FIXTURE_SIZE_BYTES}" \
  --timeout 15m \
  --client-details "matrixLabel=\${matrix_label}"
