# shellcheck shell=bash
# Shared adb helpers, sourced by the device scripts.
#
# ADB: the adb binary (default $ANDROID_HOME/platform-tools/adb, else adb on PATH).
# ANDROID_SERIAL: the target device; adb honors it directly. Scripts that take a
# [serial] argument export it. With one device attached it may stay unset.

HEARTH_PKG="io.github.jameskbb.hearth"
HEARTH_ACTIVITY="$HEARTH_PKG/.MainActivity"

if [ -z "${ADB:-}" ]; then
  _sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -n "$_sdk" ] && [ -x "$_sdk/platform-tools/adb" ]; then
    ADB="$_sdk/platform-tools/adb"
  else
    ADB=adb
  fi
fi

adb_() { "$ADB" "$@"; }

# Fail early with a readable message when no device is reachable.
require_device() {
  if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "no device: start one (scripts/emulator.sh start), or set ANDROID_SERIAL / pass a serial" >&2
    exit 2
  fi
}

# The package of the resumed (foreground) activity, or empty.
foreground_package() {
  "$ADB" shell dumpsys activity activities 2>/dev/null \
    | grep -E 'mResumedActivity|topResumedActivity' | head -n1 \
    | sed -nE 's/.* ([A-Za-z0-9_.]+)\/[A-Za-z0-9_.$]+.*/\1/p' || true
}

# The PID of a package's main process, or empty.
pid_of() { "$ADB" shell pidof "$1" 2>/dev/null | tr -d '\r' || true; }

# Launch Hearth and wait for the activity to be displayed. `am start -W` can
# block indefinitely when the app crashes during start, so it gets a watchdog
# (LAUNCH_TIMEOUT seconds, default 20).
launch_hearth() {
  "$ADB" shell am start -W -n "$HEARTH_ACTIVITY" >/dev/null 2>&1 &
  local p=$!
  (sleep "${LAUNCH_TIMEOUT:-20}"; kill "$p" 2>/dev/null) &
  local w=$!
  wait "$p" 2>/dev/null || true
  kill "$w" 2>/dev/null || true
  wait "$w" 2>/dev/null || true
}
