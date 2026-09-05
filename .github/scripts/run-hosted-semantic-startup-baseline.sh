#!/usr/bin/env bash
set -euo pipefail

echo "[hosted-baseline] starting exact production resource instrumentation"
echo "[hosted-baseline] workload: four full rebuilds = 1k + 5k + 10k + 20k = 36,000 real ONNX embeddings"
echo "[hosted-baseline] this is non-acceptance x86_64 evidence; production defaults are unchanged"
adb shell getprop ro.product.cpu.abi | sed 's/^/[hosted-baseline] device abi: /'
adb shell getprop sys.boot_completed | sed 's/^/[hosted-baseline] boot_completed: /'

progress_name="post-onnx-production-resource-progress.txt"
evidence_name="post-onnx-production-resource-evidence.json"
last_progress=""

gradle :android-offline-semantic-provider:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.semanticFixtureSha256="$SELF_REPRODUCED_SHA256" \
  -Pandroid.testInstrumentationRunnerArguments.semanticFixtureSizeBytes="$SELF_REPRODUCED_SIZE" \
  '-Pandroid.testInstrumentationRunnerArguments.class=pro.liliya.android.semanticprovider.OfflineSemanticProviderProductionResourceInstrumentedTest' \
  --console=plain &
gradle_pid=$!

while kill -0 "$gradle_pid" 2>/dev/null; do
  current_progress=""
  mapfile -t semantic_packages < <(
    adb shell pm list packages 2>/dev/null |
      tr -d '\r' |
      sed 's/^package://' |
      grep '^pro\.liliya\.android\.semanticprovider' || true
  )

  for package_name in "${semantic_packages[@]}"; do
    if adb shell run-as "$package_name" test -f "files/$progress_name" 2>/dev/null; then
      current_progress="$(
        adb shell run-as "$package_name" cat "files/$progress_name" 2>/dev/null |
          tr -d '\r\n'
      )"
      break
    fi
  done

  if [[ -n "$current_progress" && "$current_progress" != "$last_progress" ]]; then
    echo "[hosted-baseline] $(date -u +%Y-%m-%dT%H:%M:%SZ) progress: $current_progress"
    last_progress="$current_progress"
  else
    echo "[hosted-baseline] $(date -u +%Y-%m-%dT%H:%M:%SZ) heartbeat: ${last_progress:-instrumentation-starting}"
  fi

  sleep 30
done

set +e
wait "$gradle_pid"
gradle_status=$?
set -e

if [[ "$gradle_status" -ne 0 ]]; then
  echo "[hosted-baseline] instrumentation failed with exit code $gradle_status"
  echo "[hosted-baseline] last observed progress: ${last_progress:-unavailable}"
  exit "$gradle_status"
fi

echo "[hosted-baseline] instrumentation completed"
mapfile -t semantic_packages < <(
  adb shell pm list packages |
    tr -d '\r' |
    sed 's/^package://' |
    grep '^pro\.liliya\.android\.semanticprovider'
)
test "${#semantic_packages[@]}" -gt 0

evidence_found=0
for package_name in "${semantic_packages[@]}"; do
  if adb shell run-as "$package_name" test -f "files/$evidence_name" 2>/dev/null; then
    adb shell run-as "$package_name" cat "files/$evidence_name" \
      > "$RUNNER_TEMP/hosted-semantic-startup-baseline.json"
    evidence_found=1
    break
  fi
done

test "$evidence_found" -eq 1
test -s "$RUNNER_TEMP/hosted-semantic-startup-baseline.json"
echo "[hosted-baseline] evidence extracted successfully"
