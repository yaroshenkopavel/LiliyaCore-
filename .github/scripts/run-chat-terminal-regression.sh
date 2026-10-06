#!/usr/bin/env bash
set -euo pipefail

app_apk="android-app/build/outputs/apk/debug/android-app-debug.apk"
test_apk="android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk"
out="${RUNNER_TEMP:?RUNNER_TEMP is required}/chat-terminal-regression.txt"

test -f "$app_apk"
test -f "$test_apk"

adb install -r "$app_apk"
adb install -r "$test_apk"

set +e
adb shell am instrument -w -r \
  -e class pro.liliya.app.LiliyaActivityConsumedTerminalChatInstrumentedTest \
  pro.liliya.app.test/androidx.test.runner.AndroidJUnitRunner \
  | tee "$out"
instrument_status=${PIPESTATUS[0]}
set -e

test "$instrument_status" -eq 0
grep -Fq "OK (1 test)" "$out"
echo "CHAT_TERMINAL_CONSUME_RACE_REGRESSION=PASS"
