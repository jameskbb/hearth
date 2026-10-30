#!/usr/bin/env bash
# Exercise all six offline demo destinations on a development emulator.
# Usage: scripts/demo-smoke.sh [emulator-serial] [flavor ...]
# DEMO_SCREENSHOTS optionally saves each destination for visual review.
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=scripts/lib/adb.sh
source scripts/lib/adb.sh
export ANDROID_SERIAL="${1:-${ANDROID_SERIAL:-emulator-5554}}"
[ $# -eq 0 ] || shift
case "$ANDROID_SERIAL" in emulator-*) ;; *) echo "Use a development emulator for demo checks" >&2; exit 2 ;; esac
require_device
"$ADB" shell input keyevent KEYCODE_WAKEUP

assert_foreground() {
  local expected="$1" _attempt
  for _attempt in 1 2 3 4 5; do
    if [ "$(foreground_package)" = "$expected" ]; then return 0; fi
    sleep 2
  done
  echo "FAIL: foreground is not $expected" >&2
  exit 1
}
assert_demo() {
  local ui _attempt
  for _attempt in 1 2 3 4; do
    if "$ADB" shell uiautomator dump /sdcard/hearth-demo-check.xml >/dev/null; then
      ui=$("$ADB" exec-out cat /sdcard/hearth-demo-check.xml)
      if [[ "$ui" == *'Hearth demo, not the official'* ]] &&
          grep -qE 'text="Return to Hearth"[^>]*focused="true"' <<<"$ui"; then return 0; fi
    fi
    sleep 2
  done
  echo "FAIL: demo disclaimer or focused return button missing" >&2
  exit 1
}
flavors=("$@")
[ ${#flavors[@]} -gt 0 ] || flavors=(kestrel lowtide marquee northwind pinhole puddlejump)
for flavor in "${flavors[@]}"; do
  case "$flavor" in kestrel|lowtide|marquee|northwind|pinhole|puddlejump) ;; *) echo "Unknown demo flavor: $flavor" >&2; exit 2 ;; esac
  launch_hearth
  assert_foreground "$HEARTH_PKG"
  demo="io.github.jameskbb.hearth.demo.$flavor"
  "$ADB" shell am start -W -n "$demo/io.github.jameskbb.hearth.demo.DemoActivity" >/dev/null
  assert_foreground "$demo"
  assert_demo
  if [ -n "${DEMO_SCREENSHOTS:-}" ]; then
    mkdir -p "$DEMO_SCREENSHOTS"
    "$ADB" exec-out screencap -p >"$DEMO_SCREENSHOTS/$flavor.png"
  fi
  "$ADB" shell input keyevent KEYCODE_DPAD_CENTER
  assert_foreground "$HEARTH_PKG"
  "$ADB" shell am start -W -n "$demo/io.github.jameskbb.hearth.demo.DemoActivity" >/dev/null
  assert_foreground "$demo"
  "$ADB" shell input keyevent KEYCODE_BACK
  assert_foreground "$HEARTH_PKG"
  echo "PASS: $flavor opens and returns with Select and Back"
done
