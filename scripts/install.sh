#!/usr/bin/env bash
# Build (incrementally) and install Hearth on a device or emulator.
#
#   scripts/install.sh [--fixtures] [--demo] [--release] [--no-build] [serial]
#
# --fixtures  also install the test fixture apps (fixtures module)
# --demo      also install the local demo stand-ins (demoapps module)
# --release   install the release build instead of debug
# --no-build  install whatever APKs are already built
# Upgrades keep Hearth's preferences when the APK is signed with the same key.
# Env: HEARTH_APK (install this APK instead of the built one), ADB,
# ANDROID_SERIAL, JAVA_HOME (for Gradle).
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=scripts/lib/adb.sh
source scripts/lib/adb.sh

fixtures=0 demo=0 build=1 variant=debug
for a in "$@"; do
  case "$a" in
    --fixtures) fixtures=1 ;;
    --demo) demo=1 ;;
    --release) variant=release ;;
    --no-build) build=0 ;;
    -h | --help) sed -n '2,11p' "$0"; exit 0 ;;
    -*) echo "unknown option $a" >&2; exit 2 ;;
    *) export ANDROID_SERIAL="$a" ;;
  esac
done
require_device

Variant="$(tr '[:lower:]' '[:upper:]' <<<"${variant:0:1}")${variant:1}"
if [ $build -eq 1 ]; then
  tasks=(":app:assemble$Variant")
  [ $fixtures -eq 1 ] && tasks+=(":fixtures:assembleDebug")
  [ $demo -eq 1 ] && tasks+=(":demoapps:assembleDebug")
  ./gradlew -q "${tasks[@]}"
  # -q hides Gradle's own warning about this, so repeat it here.
  if [ "$variant" = release ] && [ ! -f keystore.properties ]; then
    echo "WARNING: keystore.properties not found; the release APK is signed with this machine's debug key (see docs/install.md)" >&2
  fi
fi

install_apk() {
  local apk="$1"
  [ -f "$apk" ] || { echo "missing $apk (build first or drop --no-build)" >&2; exit 1; }
  printf 'installing %s ... ' "$(basename "$apk")"
  "$ADB" install -r "$apk" | tail -n1
}

shopt -s nullglob
if [ -n "${HEARTH_APK:-}" ]; then
  app_apks=("$HEARTH_APK")
else
  app_apks=(app/build/outputs/apk/"$variant"/*.apk)
fi
[ ${#app_apks[@]} -gt 0 ] || { echo "no app APK in app/build/outputs/apk/$variant" >&2; exit 1; }
install_apk "${app_apks[0]}"
if [ $fixtures -eq 1 ]; then
  for apk in fixtures/build/outputs/apk/*/debug/*.apk; do install_apk "$apk"; done
fi
if [ $demo -eq 1 ]; then
  for apk in demoapps/build/outputs/apk/*/debug/*.apk; do install_apk "$apk"; done
fi
