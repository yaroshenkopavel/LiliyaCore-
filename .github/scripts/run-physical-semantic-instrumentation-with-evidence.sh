#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <instrumentation-class> <evidence-file-name> <output-json>" >&2
  exit 64
fi

test_class="$1"
evidence_name="$2"
evidence_out="$3"

: "${ANDROID_DEVICE_SERIAL:?ANDROID_DEVICE_SERIAL is required}"
: "${SELF_REPRODUCED_SHA256:?SELF_REPRODUCED_SHA256 is required}"
: "${SELF_REPRODUCED_SIZE:?SELF_REPRODUCED_SIZE is required}"

rm -f "$evidence_out"

echo "[arm64-evidence] class=$test_class"
echo "[arm64-evidence] evidence=$evidence_name"
echo "[arm64-evidence] serial=$ANDROID_DEVICE_SERIAL"

export ANDROID_SERIAL="$ANDROID_DEVICE_SERIAL"

gradle :android-offline-semantic-provider:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.semanticFixtureSha256="$SELF_REPRODUCED_SHA256" \
  -Pandroid.testInstrumentationRunnerArguments.semanticFixtureSizeBytes="$SELF_REPRODUCED_SIZE" \
  "-Pandroid.testInstrumentationRunnerArguments.class=$test_class" \
  --console=plain &
gradle_pid=$!

evidence_captured=0
last_package=""

capture_evidence() {
  mapfile -t semantic_packages < <(
    adb -s "$ANDROID_DEVICE_SERIAL" shell pm list packages 2>/dev/null |
      tr -d '\r' |
      sed 's/^package://' |
      grep '^pro\.liliya\.android\.semanticprovider' || true
  )

  for package_name in "${semantic_packages[@]}"; do
    last_package="$package_name"
    if adb -s "$ANDROID_DEVICE_SERIAL" shell run-as "$package_name" \
      test -f "files/$evidence_name" 2>/dev/null; then
      if adb -s "$ANDROID_DEVICE_SERIAL" shell run-as "$package_name" \
        cat "files/$evidence_name" 2>/dev/null > "$evidence_out" &&
        [[ -s "$evidence_out" ]]; then
        evidence_captured=1
        echo "[arm64-evidence] captured from $package_name before package cleanup"
        return 0
      fi
    fi
  done

  return 1
}

while kill -0 "$gradle_pid" 2>/dev/null; do
  if [[ "$evidence_captured" -eq 0 ]]; then
    capture_evidence || true
  fi
  sleep 1
done

set +e
wait "$gradle_pid"
gradle_status=$?
set -e

if [[ "$gradle_status" -ne 0 ]]; then
  echo "[arm64-evidence] instrumentation failed with exit code $gradle_status" >&2
  exit "$gradle_status"
fi

if [[ "$evidence_captured" -eq 0 ]]; then
  capture_evidence || true
fi

if [[ "$evidence_captured" -ne 1 || ! -s "$evidence_out" ]]; then
  echo "[arm64-evidence] instrumentation succeeded but evidence extraction failed" >&2
  echo "[arm64-evidence] last observed package: ${last_package:-none}" >&2
  exit 1
fi

echo "[arm64-evidence] instrumentation and evidence capture completed"
