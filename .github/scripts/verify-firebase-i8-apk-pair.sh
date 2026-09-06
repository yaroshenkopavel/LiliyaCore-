#!/usr/bin/env bash
set -euo pipefail

bundle="${RUNNER_TEMP:?RUNNER_TEMP is required}/firebase-arm64-i8-bundle"
app="$bundle/android-semantic-test-host-debug.apk"
test_apk="$bundle/android-semantic-test-host-debug-androidTest.apk"

test -f "$app"
test -f "$test_apk"

app_id="$(apkanalyzer manifest application-id "$app")"
test_id="$(apkanalyzer manifest application-id "$test_apk")"
manifest="$(apkanalyzer manifest print "$test_apk")"
target_pkg="$(printf '%s\n' "$manifest" | sed -n 's/.*targetPackage="\([^"]*\)".*/\1/p' | head -1)"
runner="$(printf '%s\n' "$manifest" | sed -n 's/.*android:name="\([^"]*AndroidJUnitRunner\)".*/\1/p' | head -1)"

echo "appId=$app_id"
echo "testId=$test_id"
echo "targetPackage=$target_pkg"
echo "runner=$runner"

test "$app_id" = "pro.liliya.android.semanticprovider"
test "$target_pkg" = "$app_id"
test -n "$test_id"
test -n "$runner"

app_files="$(apkanalyzer files list "$app")"
test_files="$(apkanalyzer files list "$test_apk")"

app_arm64_count="$(
  printf '%s\n' "$app_files" |
    awk '/^\/lib\/arm64-v8a\/[^/]+$/ { count++ } END { print count + 0 }'
)"
test_arm64_count="$(
  printf '%s\n' "$test_files" |
    awk '/^\/lib\/arm64-v8a\/[^/]+$/ { count++ } END { print count + 0 }'
)"

app_other_abi_count="$(
  printf '%s\n' "$app_files" |
    awk '/^\/lib\/[^/]+\/[^/]+$/ && $0 !~ /^\/lib\/arm64-v8a\/[^/]+$/ { count++ } END { print count + 0 }'
)"
test_other_abi_count="$(
  printf '%s\n' "$test_files" |
    awk '/^\/lib\/[^/]+\/[^/]+$/ && $0 !~ /^\/lib\/arm64-v8a\/[^/]+$/ { count++ } END { print count + 0 }'
)"

echo "appArm64NativeEntries=$app_arm64_count"
echo "testArm64NativeEntries=$test_arm64_count"
echo "appOtherAbiNativeEntries=$app_other_abi_count"
echo "testOtherAbiNativeEntries=$test_other_abi_count"

echo "appNativeEntries:"
printf '%s\n' "$app_files" |
  awk '/^\/lib\/[^/]+\/[^/]+$/ { print }'

echo "testNativeEntries:"
printf '%s\n' "$test_files" |
  awk '/^\/lib\/[^/]+\/[^/]+$/ { print }'

echo "appUnexpectedNativeEntries:"
printf '%s\n' "$app_files" |
  awk '/^\/lib\/[^/]+\/[^/]+$/ && $0 !~ /^\/lib\/arm64-v8a\/[^/]+$/ { print }'

echo "testUnexpectedNativeEntries:"
printf '%s\n' "$test_files" |
  awk '/^\/lib\/[^/]+\/[^/]+$/ && $0 !~ /^\/lib\/arm64-v8a\/[^/]+$/ { print }'

test "$app_arm64_count" -gt 0
test "$test_arm64_count" -gt 0
test "$app_other_abi_count" -eq 0
test "$test_other_abi_count" -eq 0

echo "testDexRequiredClasses:"
test_dex_packages="$(apkanalyzer dex packages "$test_apk")"
for required_class in \
  "pro.liliya.android.semanticprovider.OfflineSemanticProviderResourceInstrumentedTest" \
  "pro.liliya.android.semanticprovider.ProductionGenerationCandidateCombinedResourceInstrumentedTest"
do
  printf '%s\n' "$test_dex_packages" | grep -F "$required_class"
done

echo "Firebase APK pair metadata/ABI/class preflight passed"
echo "Physical installation remains intentionally deferred to Firebase ARM64 device execution."
