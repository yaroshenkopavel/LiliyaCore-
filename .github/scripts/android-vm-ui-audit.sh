#!/usr/bin/env bash
set -euo pipefail

apk="android-app/build/outputs/apk/debug/android-app-debug.apk"
out="${RUNNER_TEMP:?RUNNER_TEMP is required}/liliya-vm-ui-audit"
mkdir -p "$out"

adb install -r "$apk"
adb shell pm clear pro.liliya.app >/dev/null
adb shell am start -W -n pro.liliya.app/.LiliyaProvisioningActivity > "$out/start-portrait.txt"
cat "$out/start-portrait.txt"
sleep 2

adb shell uiautomator dump /sdcard/liliya-window.xml >/dev/null
adb pull /sdcard/liliya-window.xml "$out/provisioning-portrait.xml" >/dev/null
adb exec-out screencap -p > "$out/provisioning-portrait.png"
adb shell dumpsys activity activities > "$out/activities-portrait.txt"

grep -Fq "Liliya — подготовка доступа" "$out/provisioning-portrait.xml"
grep -Fq "Требуется доступ продукта" "$out/provisioning-portrait.xml"
grep -Fq "Импортировать доступ продукта" "$out/provisioning-portrait.xml"
grep -Fq "pro.liliya.app/.LiliyaProvisioningActivity" "$out/activities-portrait.txt"

adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 2
adb shell uiautomator dump /sdcard/liliya-window-landscape.xml >/dev/null
adb pull /sdcard/liliya-window-landscape.xml "$out/provisioning-landscape.xml" >/dev/null
adb exec-out screencap -p > "$out/provisioning-landscape.png"
adb shell dumpsys activity activities > "$out/activities-landscape.txt"

grep -Fq "Liliya — подготовка доступа" "$out/provisioning-landscape.xml"
grep -Fq "Требуется доступ продукта" "$out/provisioning-landscape.xml"
grep -Fq "Импортировать доступ продукта" "$out/provisioning-landscape.xml"
grep -Fq "pro.liliya.app/.LiliyaProvisioningActivity" "$out/activities-landscape.txt"

adb shell settings put system user_rotation 0
