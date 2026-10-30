#!/usr/bin/env bash
# Capture the device screen as PNG.
#
#   scripts/screenshots.sh [-s serial] [-o dir] name [name ...]
#
# Each name is captured in order, with SHOT_DELAY seconds between captures.
# A name may carry remote keys to press first, as name=keys:
#   scripts/screenshots.sh home "allapps=down select" "settings=back down right right select"
# Files land in dir/<name>.png (default docs/images, used by the README).
# Env: SHOT_DELAY (default 1.0), REMOTE_DELAY, ADB, ANDROID_SERIAL.
# CAPTURE_SOURCE=host uses the emulator's native host capture, avoiding slow
# guest PNG encoding on software-only emulators. The default is device.
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=scripts/lib/adb.sh
source scripts/lib/adb.sh

out=docs/images
while [ $# -gt 0 ]; do
  case "$1" in
    -s) export ANDROID_SERIAL="$2"; shift 2 ;;
    -o) out="$2"; shift 2 ;;
    *) break ;;
  esac
done
[ $# -gt 0 ] || { sed -n '2,10p' "$0" >&2; exit 2; }
require_device
mkdir -p "$out"

host_capture() (
  case "$("$ADB" get-serialno)" in
    emulator-*) ;;
    *) echo "Host capture requires an emulator" >&2; exit 2 ;;
  esac
  temp=$(mktemp -d)
  trap 'rm -rf "$temp"' EXIT
  "$ADB" emu screenrecord screenshot "$temp" >/dev/null
  captures=("$temp"/*.png)
  [ ${#captures[@]} -eq 1 ] && [ -s "${captures[0]}" ] || exit 1
  mv "${captures[0]}" "$1"
)

for spec in "$@"; do
  name="${spec%%=*}"
  if [ "$spec" != "$name" ]; then
    # shellcheck disable=SC2086 # keys are intentionally word-split
    scripts/remote.sh ${spec#*=}
  fi
  sleep "${SHOT_DELAY:-1.0}"
  case "${CAPTURE_SOURCE:-device}" in
    device) "$ADB" exec-out screencap -p >"$out/$name.png" ;;
    host) host_capture "$out/$name.png" ;;
    *) echo "CAPTURE_SOURCE must be device or host" >&2; exit 2 ;;
  esac
  [ -s "$out/$name.png" ] || { echo "empty capture for $name" >&2; exit 1; }
  echo "$out/$name.png"
done
