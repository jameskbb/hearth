#!/usr/bin/env bash
# Manage a headless Android TV (API 28) emulator for tests and screenshots.
#
#   scripts/emulator.sh create    # create the AVD (once)
#   scripts/emulator.sh start     # boot headless and wait for boot_completed
#   scripts/emulator.sh stop      # shut it down
#   scripts/emulator.sh status    # print the serial if running
#
# Env: ANDROID_HOME (or ANDROID_SDK_ROOT) for the SDK, AVD_NAME (default
# hearth-tv-28), AVD_IMAGE (default the API 28 Android TV x86 image),
# EMU_PORT (default 5554), BOOT_TIMEOUT seconds (default 300), EMU_LOG
# (default build/emulator.log), EMULATOR_ARGS (extra emulator flags, e.g.
# "-cores 6 -memory 3072" for smoother recording). Needs hardware acceleration
# (/dev/kvm on Linux).
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || { echo "set ANDROID_HOME to the Android SDK" >&2; exit 2; }
AVD_NAME="${AVD_NAME:-hearth-tv-28}"
AVD_IMAGE="${AVD_IMAGE:-system-images;android-28;android-tv;x86}"
EMU_PORT="${EMU_PORT:-5554}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-300}"
EMU_LOG="${EMU_LOG:-build/emulator.log}"
EMULATOR_ARGS="${EMULATOR_ARGS:-}"
SERIAL="emulator-$EMU_PORT"
ADB="$SDK/platform-tools/adb"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"
EMULATOR="$SDK/emulator/emulator"

cmd="${1:-}"
case "$cmd" in
  create)
    if "$EMULATOR" -list-avds | grep -qx "$AVD_NAME"; then
      echo "AVD $AVD_NAME already exists"
      exit 0
    fi
    echo no | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "$AVD_IMAGE" -d tv_1080p
    echo "created AVD $AVD_NAME"
    ;;
  start)
    if "$ADB" devices | grep -q "^$SERIAL\b"; then
      echo "$SERIAL already running"
    else
      if [ "$(uname -s)" = Linux ] && [ ! -w /dev/kvm ]; then
        echo "/dev/kvm is not writable: add yourself to the kvm group and log in again" >&2
        exit 2
      fi
      mkdir -p "$(dirname "$EMU_LOG")"
      # shellcheck disable=SC2086 # EMULATOR_ARGS is a list of flags, split on purpose
      nohup "$EMULATOR" -avd "$AVD_NAME" -port "$EMU_PORT" -no-window -no-audio \
        -no-boot-anim -no-snapshot -gpu swiftshader_indirect $EMULATOR_ARGS >"$EMU_LOG" 2>&1 &
      emu_pid=$!
      echo "starting $SERIAL (log: $EMU_LOG)"
    fi
    deadline=$((SECONDS + BOOT_TIMEOUT))
    until [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
      if [ -n "${emu_pid:-}" ] && ! kill -0 "$emu_pid" 2>/dev/null; then
        echo "emulator exited during boot; last log lines:" >&2
        tail -n 5 "$EMU_LOG" >&2
        exit 1
      fi
      if [ $SECONDS -ge $deadline ]; then echo "boot timed out after ${BOOT_TIMEOUT}s" >&2; exit 1; fi
      sleep 3
    done
    # Keep the screen awake for screenshots and key events.
    "$ADB" -s "$SERIAL" shell svc power stayon true >/dev/null 2>&1 || true
    "$ADB" -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    echo "$SERIAL booted"
    ;;
  stop)
    if "$ADB" devices | grep -q "^$SERIAL\b"; then
      "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
      echo "stopped $SERIAL"
    else
      echo "$SERIAL not running"
    fi
    ;;
  status)
    if "$ADB" devices | grep -q "^$SERIAL\b"; then echo "$SERIAL"; else echo "not running"; exit 1; fi
    ;;
  *)
    echo "usage: $0 create|start|stop|status" >&2
    exit 2
    ;;
esac
