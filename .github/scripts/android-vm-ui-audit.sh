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
status = bounds(exact_text("Требуется профиль продукта"))
activation_code = bounds(exact_text("Код активации"))
activate = bounds(exact_text("Активировать"))
fallback = bounds(exact_text("Старый способ: импортировать доступ"))

minimum_top = (density * 40 + 159) // 160
if title[1] < minimum_top:
    raise SystemExit(
        f"{orientation}: title top {title[1]}px is inside unsafe top region; "
        f"minimum is {minimum_top}px at density {density}"
    )
if not (
    title[1] < title[3] <= status[1] < status[3] <=
    activation_code[1] < activation_code[3] <=
    activate[1] < activate[3] <=
    fallback[1] < fallback[3]
):
    raise SystemExit(
        f"{orientation}: provisioning controls overlap or are out of order: "
        f"title={title}, status={status}, activation_code={activation_code}, "
        f"activate={activate}, fallback={fallback}"
    )
PY
}

verify_snapshot() {
  local xml="$1"
  local activities="$2"
  local orientation="$3"
  local expected
  for expected in \
    "Liliya — подготовка доступа" \
    "Требуется профиль продукта" \
    "Код активации" \
    "Активировать" \
    "Старый способ: импортировать доступ"; do
    if ! grep -Fq "$expected" "$xml"; then
      echo "$orientation: missing visible label: $expected" >&2
      return 1
    fi
  done
  if ! grep -Fq "pro.liliya.app/.LiliyaProvisioningActivity" "$activities"; then
    echo "$orientation: provisioning Activity is not foreground" >&2
    return 1
  fi
  verify_layout "$xml" "$orientation"
}

capture_and_verify() {
  local orientation="$1"
  local remote="/sdcard/liliya-window-$orientation.xml"
  local xml="$out/provisioning-$orientation.xml"
  local activities="$out/activities-$orientation.txt"
  local diagnostic="$out/check-$orientation.txt"
  local attempt
  for attempt in 1 2 3 4 5; do
    if adb shell uiautomator dump "$remote" >/dev/null &&
       adb pull "$remote" "$xml" >/dev/null; then
      adb exec-out screencap -p > "$out/provisioning-$orientation.png"
      adb shell dumpsys activity activities > "$activities"
      if verify_snapshot "$xml" "$activities" "$orientation" > "$diagnostic" 2>&1; then
        echo "$orientation: expected UI verified on attempt $attempt"
        return 0
      fi
    else
      echo "$orientation: UI dump not yet available" > "$diagnostic"
    fi
    sleep 2
  done
  cat "$diagnostic" >&2
  echo "$orientation: provisioning UI did not settle after five captures" >&2
  return 1
}

adb install -r "$apk"
adb shell pm clear pro.liliya.app >/dev/null
adb shell am start -W -n pro.liliya.app/.LiliyaProvisioningActivity > "$out/start-portrait.txt"
cat "$out/start-portrait.txt"
capture_and_verify portrait

adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
capture_and_verify landscape

adb shell settings put system user_rotation 0
