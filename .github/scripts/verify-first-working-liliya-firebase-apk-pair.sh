#!/usr/bin/env bash
set -euo pipefail

bundle="${RUNNER_TEMP:?RUNNER_TEMP is required}/first-working-liliya-firebase-arm64-bundle"
app="$bundle/android-app-debug.apk"
test_apk="$bundle/android-app-debug-androidTest.apk"

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

test "$app_id" = "pro.liliya.app"
test "$target_pkg" = "$app_id"
test -n "$test_id"
test -n "$runner"

app_files="$(apkanalyzer files list "$app")"
test_files="$(apkanalyzer files list "$test_apk")"

app_arm64_count="$(printf '%s\n' "$app_files" | awk '/^\/lib\/arm64-v8a\/[^/]+$/ { count++ } END { print count + 0 }')"
app_other_abi_count="$(printf '%s\n' "$app_files" | awk '/^\/lib\/[^/]+\/[^/]+$/ && $0 !~ /^\/lib\/arm64-v8a\/[^/]+$/ { count++ } END { print count + 0 }')"
test_other_abi_count="$(printf '%s\n' "$test_files" | awk '/^\/lib\/[^/]+\/[^/]+$/ && $0 !~ /^\/lib\/arm64-v8a\/[^/]+$/ { count++ } END { print count + 0 }')"

echo "appArm64NativeEntries=$app_arm64_count"
echo "appOtherAbiNativeEntries=$app_other_abi_count"
echo "testOtherAbiNativeEntries=$test_other_abi_count"

test "$app_arm64_count" -gt 0
test "$app_other_abi_count" -eq 0
test "$test_other_abi_count" -eq 0

test_dex_packages="$(apkanalyzer dex packages "$test_apk")"
printf '%s\n' "$test_dex_packages" | grep -F "pro.liliya.app.DevelopmentFirstWorkingLiliyaColdStartInstrumentedTest"

echo "First Working Liliya Firebase APK pair metadata/ABI/class preflight passed"
echo "Physical execution remains intentionally outside this credential-free bundle gate."
