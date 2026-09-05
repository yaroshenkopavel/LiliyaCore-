#!/usr/bin/env bash
set -euo pipefail

bundle="${RUNNER_TEMP:?RUNNER_TEMP is required}/firebase-arm64-i8-bundle"
app="$bundle/android-semantic-test-host-debug.apk"
test_apk="$bundle/android-offline-semantic-provider-debug-androidTest.apk"

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

adb install -r "$app"
adb install -r "$test_apk"

instrumentation_list="$(adb shell pm list instrumentation | tr -d '\r')"
echo "$instrumentation_list"
echo "$instrumentation_list" | grep -F "target=$app_id"

adb shell pm path "$app_id"
adb shell pm path "$test_id"

echo "Firebase APK pair preflight passed"
