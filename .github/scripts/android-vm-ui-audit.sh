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

capture_liliya_window() {
  local remote_xml="$1"
  local local_xml="$2"
  local orientation="$3"
  local attempt

  for attempt in 1 2 3; do
    if ! adb shell uiautomator dump "$remote_xml" >/dev/null; then
      echo "$orientation: uiautomator dump failed on attempt $attempt" >&2
      sleep 2
      continue
    fi
    if ! adb pull "$remote_xml" "$local_xml" >/dev/null; then
      echo "$orientation: failed to pull UI hierarchy on attempt $attempt" >&2
      sleep 2
      continue
    fi

    cp "$local_xml" "$out/${orientation}-attempt-${attempt}.xml"

    if grep -Fq "Liliya — подготовка доступа" "$local_xml"; then
      return 0
    fi

    # Hosted Android VMs can transiently surface a Pixel Launcher ANR above the
    # already-started Liliya Activity. Treat only that known system overlay as
    # retryable. Any Liliya ANR or other unexpected foreground UI stays fail-closed.
    if grep -Fq "Pixel Launcher" "$local_xml" &&
       grep -Fq "responding" "$local_xml"; then
      echo "$orientation: transient Pixel Launcher ANR overlay; dismissing and retrying ($attempt/3)" >&2
      adb shell input keyevent KEYCODE_BACK || true
      adb shell am start -W -n pro.liliya.app/.LiliyaProvisioningActivity         > "$out/recover-${orientation}-${attempt}.txt"
      sleep 2
      continue
    fi

    echo "$orientation: unexpected foreground UI; refusing to hide a real product failure" >&2
    cat "$local_xml" >&2
    return 1
  done

  echo "$orientation: Liliya UI did not become observable after bounded retries" >&2
  return 1
}

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
status = bounds(exact_text("Введите код активации"))
field = bounds(exact_text("Код активации или замены устройства"))
button = bounds(exact_text("Активировать"))

minimum_top = (density * 40 + 159) // 160
if title[1] < minimum_top:
    raise SystemExit(
        f"{orientation}: title top {title[1]}px is inside unsafe top region; "
        f"minimum is {minimum_top}px at density {density}"
    )
if not (
    title[1] < title[3] <=
    status[1] < status[3] <=
    field[1] < field[3] <=
    button[1] < button[3]
):
    raise SystemExit(
        f"{orientation}: provisioning controls overlap or are out of order: "
        f"title={title}, status={status}, field={field}, button={button}"
    )
PY
}

adb install -r "$apk"
adb shell pm clear pro.liliya.app >/dev/null
adb shell am start -W -n pro.liliya.app/.LiliyaProvisioningActivity > "$out/start-portrait.txt"
cat "$out/start-portrait.txt"
sleep 2

capture_liliya_window /sdcard/liliya-window.xml "$out/provisioning-portrait.xml" portrait
adb exec-out screencap -p > "$out/provisioning-portrait.png"
adb shell dumpsys activity activities > "$out/activities-portrait.txt"

grep -Fq "Введите код активации" "$out/provisioning-portrait.xml"
grep -Fq "Код активации или замены устройства" "$out/provisioning-portrait.xml"
grep -Fq "Активировать" "$out/provisioning-portrait.xml"
grep -Fq "pro.liliya.app/.LiliyaProvisioningActivity" "$out/activities-portrait.txt"
verify_layout "$out/provisioning-portrait.xml" portrait

adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 2

capture_liliya_window /sdcard/liliya-window-landscape.xml "$out/provisioning-landscape.xml" landscape
adb exec-out screencap -p > "$out/provisioning-landscape.png"
adb shell dumpsys activity activities > "$out/activities-landscape.txt"

grep -Fq "Введите код активации" "$out/provisioning-landscape.xml"
grep -Fq "Код активации или замены устройства" "$out/provisioning-landscape.xml"
grep -Fq "Активировать" "$out/provisioning-landscape.xml"
grep -Fq "pro.liliya.app/.LiliyaProvisioningActivity" "$out/activities-landscape.txt"
verify_layout "$out/provisioning-landscape.xml" landscape

adb shell settings put system user_rotation 0
