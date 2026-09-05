#!/usr/bin/env bash
set -euo pipefail

gradle :android-offline-semantic-provider:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.semanticFixtureSha256="$SELF_REPRODUCED_SHA256" \
  -Pandroid.testInstrumentationRunnerArguments.semanticFixtureSizeBytes="$SELF_REPRODUCED_SIZE" \
  '-Pandroid.testInstrumentationRunnerArguments.class=pro.liliya.android.semanticprovider.OfflineSemanticProviderProductionResourceInstrumentedTest' \
  --console=plain

evidence_name="post-onnx-production-resource-evidence.json"
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
