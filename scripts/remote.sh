#!/usr/bin/env bash
# Send remote-control keys to a device, in order.
#
#   scripts/remote.sh [-s serial] key [key ...]
#   scripts/remote.sh right right select
#   scripts/remote.sh longselect down select
#
# Keys: up down left right select back menu home longselect
#       playpause play pause next previous rewind fastforward wakeup sleep
# Any other word is passed through as a raw keycode (e.g. KEYCODE_SEARCH or 82).
# A word like wait:1.5 pauses for that many seconds.
# Env: REMOTE_DELAY seconds between keys (default 0.35), ADB, ANDROID_SERIAL.
# INPUT_SOURCE=host injects D-pad, Select and Back through the emulator
# console. Other keys keep using adb input. The default is device.
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=scripts/lib/adb.sh
source scripts/lib/adb.sh

if [ "${1:-}" = "-s" ]; then export ANDROID_SERIAL="$2"; shift 2; fi
[ $# -gt 0 ] || { sed -n '2,12p' "$0" >&2; exit 2; }
DELAY="${REMOTE_DELAY:-0.35}"
require_device
case "${INPUT_SOURCE:-device}" in
  device) ;;
  host)
    case "$("$ADB" get-serialno)" in
      emulator-*) ;;
      *) echo "Host input requires an emulator" >&2; exit 2 ;;
    esac ;;
  *) echo "INPUT_SOURCE must be device or host" >&2; exit 2 ;;
esac

keycode() {
  case "$1" in
    up) echo KEYCODE_DPAD_UP ;;
    down) echo KEYCODE_DPAD_DOWN ;;
    left) echo KEYCODE_DPAD_LEFT ;;
    right) echo KEYCODE_DPAD_RIGHT ;;
    select | center | ok) echo KEYCODE_DPAD_CENTER ;;
    back) echo KEYCODE_BACK ;;
    menu) echo KEYCODE_MENU ;;
    home) echo KEYCODE_HOME ;;
    playpause) echo KEYCODE_MEDIA_PLAY_PAUSE ;;
    play) echo KEYCODE_MEDIA_PLAY ;;
    pause) echo KEYCODE_MEDIA_PAUSE ;;
    next) echo KEYCODE_MEDIA_NEXT ;;
    previous) echo KEYCODE_MEDIA_PREVIOUS ;;
    rewind) echo KEYCODE_MEDIA_REWIND ;;
    fastforward) echo KEYCODE_MEDIA_FAST_FORWARD ;;
    wakeup) echo KEYCODE_WAKEUP ;;
    sleep) echo KEYCODE_SLEEP ;;
    *) echo "$1" ;;
  esac
}

for k in "$@"; do
  host_code=
  if [ "${INPUT_SOURCE:-device}" = host ]; then
    case "$k" in
      up) host_code=KEY_UP ;; down) host_code=KEY_DOWN ;;
      left) host_code=KEY_LEFT ;; right) host_code=KEY_RIGHT ;;
      select|center|ok) host_code=232 ;; # emulator qwerty.kl: DPAD_CENTER
      back) host_code=158 ;;
    esac
  fi
  if [ -n "$host_code" ]; then
    "$ADB" emu event send "EV_KEY:$host_code:1" 0:0:0 >/dev/null
    sleep 0.12
    "$ADB" emu event send "EV_KEY:$host_code:0" 0:0:0 >/dev/null
    sleep "$DELAY"
    continue
  fi
  case "$k" in
    wait:*) sleep "${k#wait:}"; continue ;;
    longselect) "$ADB" shell input keyevent --longpress KEYCODE_DPAD_CENTER ;;
    *) "$ADB" shell input keyevent "$(keycode "$k")" ;;
  esac
  sleep "$DELAY"
done
