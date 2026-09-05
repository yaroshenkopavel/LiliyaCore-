#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
usage:
  run_firebase_full_i8_acceptance.sh <bundle-dir> [gcp-project]

Runs the current post-ONNX physical ARM64 I8 acceptance on Firebase Test Lab using
prebuilt exact APKs from the GitHub-produced ARM64 Firebase bundle.

The bundle must contain exactly one target app APK and one androidTest APK.
EOF
}

if [[ $# -lt 1 || $# -gt 2 ]]; then
  usage
  exit 64
fi

BUNDLE_DIR="$1"
GCP_PROJECT="${2:-liliyacore-test-lab}"

readonly FIXTURE_SHA256="11f460a6600163508a6eca0f2ccd8df9272fafbbee10579b3dafa74217b084dc"
readonly FIXTURE_SIZE_BYTES="118258170"
readonly DEVICE_MODEL="z3q"
readonly DEVICE_VERSION="33"
readonly DEVICE_LOCALE="en"
readonly DEVICE_ORIENTATION="portrait"

readonly CANONICAL_CLASS="pro.liliya.android.semanticprovider.OfflineSemanticProviderResourceInstrumentedTest"
readonly PRODUCTION_CLASS="pro.liliya.android.semanticprovider.OfflineSemanticProviderProductionResourceInstrumentedTest"
readonly COMBINED_CLASS="pro.liliya.android.semanticprovider.OfflineSemanticProviderCombinedEngineResourceInstrumentedTest"

command -v gcloud >/dev/null 2>&1 || {
  echo "gcloud is required" >&2
  exit 69
}

[[ -d "$BUNDLE_DIR" ]] || {
  echo "bundle directory not found: $BUNDLE_DIR" >&2
  exit 66
}

mapfile -t test_apks < <(find "$BUNDLE_DIR" -type f -name '*androidTest*.apk' -print | sort)
mapfile -t app_apks < <(
  find "$BUNDLE_DIR" -type f -name '*.apk' ! -name '*androidTest*.apk' -print | sort
)

if [[ "${#test_apks[@]}" -ne 1 || "${#app_apks[@]}" -ne 1 ]]; then
  echo "expected exactly one target APK and one androidTest APK" >&2
  echo "target APK candidates:" >&2
  printf '  %s\n' "${app_apks[@]:-}" >&2
  echo "androidTest APK candidates:" >&2
  printf '  %s\n' "${test_apks[@]:-}" >&2
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

run_matrix() {
  local label="$1"
  local test_class="$2"

  echo
  echo "=== Firebase physical ARM64 I8: $label ==="
  echo "project=$GCP_PROJECT"
  echo "device=${DEVICE_MODEL}-api${DEVICE_VERSION}"
  echo "testClass=$test_class"
  echo "fixtureSha256=$FIXTURE_SHA256"
  echo "fixtureSizeBytes=$FIXTURE_SIZE_BYTES"

  gcloud firebase test android run \
    --type instrumentation \
    --app "$APP_APK" \
    --test "$TEST_APK" \
    --device "model=${DEVICE_MODEL},version=${DEVICE_VERSION},locale=${DEVICE_LOCALE},orientation=${DEVICE_ORIENTATION}" \
    --test-targets "class ${test_class}" \
    --environment-variables "semanticFixtureSha256=${FIXTURE_SHA256},semanticFixtureSizeBytes=${FIXTURE_SIZE_BYTES}" \
    --timeout 45m \
    --client-details "matrixLabel=LiliyaCore-I8-${label}"
}

run_matrix "canonical-resource" "$CANONICAL_CLASS"
run_matrix "production-rebuild-1k-5k-10k-20k" "$PRODUCTION_CLASS"
run_matrix "combined-onnx-20k-llama-residency" "$COMBINED_CLASS"

echo
echo "All three Firebase physical ARM64 I8 matrices completed successfully."
echo "Review matrix IDs and raw instrumentation evidence before freezing I8."
