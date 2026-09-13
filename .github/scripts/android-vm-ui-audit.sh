#!/usr/bin/env bash
set -euo pipefail

apk="android-app/build/outputs/apk/debug/android-app-debug.apk"
out="${RUNNER_TEMP:?RUNNER_TEMP is required}/liliya-vm-ui-audit"
mkdir -p "$out"

density="$({ adb shell wm density || true; } | tr -d '\r' | awk '
  /Physical density:/ { physical=$3 }
  /Override density:/ { override=$3 }
  END { if (override != "") print override; else print physical }
')"
test -n "$density"

verify_layout() {
  local xml="$1"
  local orientation="$2"
  python3 - "$xml" "$orientation" "$density" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

path, orientation, density_text = sys.argv[1:]
density = int(density_text)
root = ET.parse(path).getroot()
nodes = list(root.iter("node"))

if any(node.attrib.get("resource-id") in {"android:id/action_bar", "android:id/action_bar_container"} for node in nodes):
    raise SystemExit(f"{orientation}: provisioning content must not be covered by a system ActionBar")

def exact_text(value):
    matches = [node for node in nodes if node.attrib.get("text") == value]
    if len(matches) != 1:
        raise SystemExit(f"{orientation}: expected exactly one visible node for {value!r}, got {len(matches)}")
    return matches[0]

def bounds(node):
    raw = node.attrib.get("bounds", "")
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", raw)
    if not match:
        raise SystemExit(f"{orientation}: invalid bounds {raw!r}")
    return tuple(map(int, match.groups()))

title = bounds(exact_text("Liliya — подготовка доступа"))
status = bounds(exact_text("Требуется доступ продукта"))
button = bounds(exact_text("Импортировать доступ продукта"))

minimum_top = (density * 40 + 159) // 160
if title[1] < minimum_top:
    raise SystemExit(
        f"{orientation}: title top {title[1]}px is inside unsafe top region; "
        f"minimum is {minimum_top}px at density {density}"
    )
if not (title[1] < title[3] <= status[1] < status[3] <= button[1] < button[3]):
    raise SystemExit(
        f"{orientation}: provisioning controls overlap or are out of order: "
        f"title={title}, status={status}, button={button}"
    )
PY
}

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
verify_layout "$out/provisioning-portrait.xml" portrait

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
verify_layout "$out/provisioning-landscape.xml" landscape

adb shell settings put system user_rotation 0
