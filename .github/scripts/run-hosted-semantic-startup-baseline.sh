#!/usr/bin/env bash
set -euo pipefail

echo "[hosted-baseline] starting exact production resource instrumentation"
echo "[hosted-baseline] workload: hosted x86_64 pipeline probe = 1,000 real ONNX embeddings"
echo "[hosted-baseline] physical ARM64 remains the full 1k/5k/10k/20k acceptance path"
echo "[hosted-baseline] this is non-acceptance x86_64 evidence; production defaults are unchanged"
adb shell getprop ro.product.cpu.abi | sed 's/^/[hosted-baseline] device abi: /'
adb shell getprop sys.boot_completed | sed 's/^/[hosted-baseline] boot_completed: /'

progress_name="post-onnx-production-resource-progress.txt"
evidence_name="post-onnx-production-resource-evidence.json"
evidence_out="$RUNNER_TEMP/hosted-semantic-startup-baseline.json"
last_progress=""
evidence_captured=0

rm -f "$evidence_out"

gradle :android-offline-semantic-provider:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.semanticFixtureSha256="$SELF_REPRODUCED_SHA256" \
  -Pandroid.testInstrumentationRunnerArguments.semanticFixtureSizeBytes="$SELF_REPRODUCED_SIZE" \
  '-Pandroid.testInstrumentationRunnerArguments.class=pro.liliya.android.semanticprovider.OfflineSemanticProviderProductionResourceInstrumentedTest' \
  --console=plain &
gradle_pid=$!

# AGP may uninstall the instrumentation package immediately when connectedDebugAndroidTest returns.
# Therefore progress/evidence must be observed and copied while the test package is still alive.
while kill -0 "$gradle_pid" 2>/dev/null; do
  current_progress=""
  mapfile -t semantic_packages < <(
    adb shell pm list packages 2>/dev/null |
      tr -d '\r' |
      sed 's/^package://' |
      grep '^pro\.liliya\.android\.semanticprovider' || true
  )

  for package_name in "${semantic_packages[@]}"; do
    if [[ "$evidence_captured" -eq 0 ]] &&
       adb shell run-as "$package_name" test -f "files/$evidence_name" 2>/dev/null; then
      if adb shell run-as "$package_name" cat "files/$evidence_name" 2>/dev/null > "$evidence_out" &&
         [[ -s "$evidence_out" ]]; then
        evidence_captured=1
        echo "[hosted-baseline] evidence captured before instrumentation package cleanup"
      fi
    fi

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
  elif [[ -n "$last_progress" ]]; then
    echo "[hosted-baseline] $(date -u +%Y-%m-%dT%H:%M:%SZ) heartbeat: $last_progress"
  fi

  sleep 1
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

# Fast-path fallback for runners where the package remains installed briefly after Gradle returns.
if [[ "$evidence_captured" -eq 0 ]]; then
  mapfile -t semantic_packages < <(
    adb shell pm list packages 2>/dev/null |
      tr -d '\r' |
      sed 's/^package://' |
      grep '^pro\.liliya\.android\.semanticprovider' || true
  )
  for package_name in "${semantic_packages[@]}"; do
    if adb shell run-as "$package_name" test -f "files/$evidence_name" 2>/dev/null; then
      if adb shell run-as "$package_name" cat "files/$evidence_name" 2>/dev/null > "$evidence_out" &&
         [[ -s "$evidence_out" ]]; then
        evidence_captured=1
        echo "[hosted-baseline] evidence captured during post-Gradle fallback"
        break
      fi
    fi
  done
fi

if [[ "$evidence_captured" -ne 1 || ! -s "$evidence_out" ]]; then
  echo "[hosted-baseline] evidence extraction failed"
  echo "[hosted-baseline] last observed progress: ${last_progress:-unavailable}"
  exit 1
fi

echo "[hosted-baseline] evidence extracted successfully"
