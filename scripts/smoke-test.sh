#!/usr/bin/env bash
# Scripted end-to-end smoke test on an emulator or device.
#
#   scripts/smoke-test.sh [--no-build] [serial]
#
# Installs Hearth and the fixture apps, launches Hearth, drives it with the
# remote, and after each step asserts what a script can: Hearth is still the
# foreground activity, its process is alive, and the crash log has no Hearth
# crash. It cannot judge visuals. UI dumps and screenshots of each step go to
# build/smoke/ for a human to look at.
#
# The remote paths into All apps, Settings and the options dialog depend on
# the UI layout; override them if the layout changes:
#   NAV_ALL_APPS   (default "down down select")    from a fresh Home
#   NAV_SETTINGS   (default "down down right right select")
#   NAV_FAVORITE   (default "menu down select")    on the focused All apps tile
# STEP_WAIT seconds to settle after each step (default 2).
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=scripts/lib/adb.sh
source scripts/lib/adb.sh

build_flag=()
for a in "$@"; do
  case "$a" in
    --no-build) build_flag=(--no-build) ;;
    *) export ANDROID_SERIAL="$a" ;;
  esac
done
require_device

NAV_ALL_APPS="${NAV_ALL_APPS:-down down select}"
NAV_SETTINGS="${NAV_SETTINGS:-down down right right select}"
NAV_FAVORITE="${NAV_FAVORITE:-menu down select}"
STEP_WAIT="${STEP_WAIT:-2}"
REMOVED_FIXTURE="io.github.jameskbb.hearth.fixture.banner"
OUT=build/smoke
mkdir -p "$OUT"

pass=0 fail=0
result() { # status step detail
  printf '%-4s %-38s %s\n' "$1" "$2" "${3:-}"
  if [ "$1" = PASS ]; then pass=$((pass + 1)); elif [ "$1" = FAIL ]; then fail=$((fail + 1)); fi
}

hearth_crashes() {
  "$ADB" logcat -b crash -d 2>/dev/null | grep -cE "Process: ${HEARTH_PKG//./\\.}, PID" || true
}

# Snapshot the screen and UI tree, then assert Hearth is alive and in front.
check() { # step-name
  local step="$1" fg pid crashes
  sleep "$STEP_WAIT"
  "$ADB" exec-out screencap -p >"$OUT/$step.png" 2>/dev/null || true
  if "$ADB" shell uiautomator dump /sdcard/hearth-ui.xml >/dev/null 2>&1; then
    "$ADB" exec-out cat /sdcard/hearth-ui.xml >"$OUT/$step.xml" 2>/dev/null || true
  fi
  fg=$(foreground_package)
  pid=$(pid_of "$HEARTH_PKG")
  crashes=$(hearth_crashes)
  if [ "$fg" = "$HEARTH_PKG" ] && [ -n "$pid" ] && [ "$crashes" = 0 ]; then
    result PASS "$step" "pid $pid"
  else
    result FAIL "$step" "foreground=${fg:-none} pid=${pid:-none} crashes=$crashes"
    # Bring Hearth back so the remaining steps still exercise it.
    launch_hearth || true
  fi
}

# Does a package resolve a MAIN activity in the given launcher category?
resolves() { # package category
  "$ADB" shell cmd package resolve-activity --brief -a android.intent.action.MAIN \
    -c "android.intent.category.$2" "$1" 2>/dev/null | tail -n1 | grep -q /
}

# 1. Install.
if scripts/install.sh --fixtures "${build_flag[@]}" >"$OUT/install.log" 2>&1; then
  result PASS install "app + fixtures"
else
  result FAIL install "see $OUT/install.log"
  exit 1
fi

fx="io.github.jameskbb.hearth.fixture"
if resolves "$fx.banner" LEANBACK_LAUNCHER && resolves "$fx.icononly" LEANBACK_LAUNCHER &&
  resolves "$fx.noart" LEANBACK_LAUNCHER && resolves "$fx.phone" LAUNCHER &&
  ! resolves "$fx.phone" LEANBACK_LAUNCHER; then
  result PASS fixtures-resolve "3 TV fixtures, 1 phone-only"
else
  result FAIL fixtures-resolve "fixture launcher categories are not as expected"
fi

# 2. Launch from cold.
"$ADB" logcat -b crash -c >/dev/null 2>&1 || true
"$ADB" shell am force-stop "$HEARTH_PKG"
"$ADB" shell input keyevent KEYCODE_WAKEUP
launch_hearth
check launch

# 3. Move around Home.
scripts/remote.sh right right left down up
check navigate-home

# 4. All apps, then Back.
launch_hearth
# shellcheck disable=SC2086 # key lists are intentionally word-split
scripts/remote.sh $NAV_ALL_APPS
check open-all-apps
if grep -q 'text="All apps"' "$OUT/open-all-apps.xml" 2>/dev/null; then
  result INFO all-apps-visible "found \"All apps\" in the UI tree"
fi

# 5. Favorite the focused All apps tile (expected: the first fixture), then Back.
# shellcheck disable=SC2086
scripts/remote.sh $NAV_FAVORITE
check favorite-from-options
scripts/remote.sh back
check back-to-home

# 6. Settings, then Back.
# shellcheck disable=SC2086
scripts/remote.sh $NAV_SETTINGS
check open-settings
scripts/remote.sh back
check back-from-settings

# 7. Back at the root must not leave Hearth.
scripts/remote.sh back back
check back-at-root

# 8. Uninstall a fixture (possibly a favorite) while Hearth is running.
if "$ADB" uninstall "$REMOVED_FIXTURE" >/dev/null 2>&1; then
  result PASS uninstall-fixture "$REMOVED_FIXTURE"
else
  result FAIL uninstall-fixture "$REMOVED_FIXTURE"
fi
check after-uninstall

# 9. Relaunch from cold with the favorite gone.
"$ADB" shell am force-stop "$HEARTH_PKG"
launch_hearth
check relaunch
scripts/remote.sh right select
sleep "$STEP_WAIT"
launch_hearth
check select-after-removal

# 10. Final crash log.
crashes=$(hearth_crashes)
"$ADB" logcat -b crash -d >"$OUT/crash.log" 2>/dev/null || true
if [ "$crashes" = 0 ]; then result PASS crash-log "no Hearth crashes"; else result FAIL crash-log "$crashes crash(es), see $OUT/crash.log"; fi

# Leave the device as we found it: put the removed fixture back.
"$ADB" install -r fixtures/build/outputs/apk/banner/debug/fixtures-banner-debug.apk >/dev/null 2>&1 || true

echo "smoke test: $pass passed, $fail failed (artifacts in $OUT/)"
[ $fail -eq 0 ]
