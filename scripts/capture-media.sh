#!/usr/bin/env bash
# Capture the README screenshots and the short tour video from an emulator.
#
#   scripts/capture-media.sh [--no-install] [--shots-only | --video-only] [serial]
#
# 1. Installs Hearth and the local demo stand-ins (scripts/install.sh --demo).
# 2. Screenshots (adb screencap) to docs/images/:
#      home-dusk, home-focus, options, arrange, all-apps, settings, appearance,
#      theme-schedule, home-aurora
# 3. Video: a scripted remote tour recorded by the emulator on the host (RECORDER=host,
#    the default; RECORDER=device uses on-device screenrecord, which runs at a few fps on
#    a software-rendered emulator), trimmed and faded, converted to docs/media/tour.mp4
#    (H.264 1080p) and docs/media/tour.webp (animated, 960 px, 15 fps) for inline display.
# See docs/recording-media.md for the full procedure.
#
# Navigation is written against Home's layout: a row of favorites above an
# action row (All apps, Arrange, Settings; Arrange only with two or more
# favorites). Down from any tile lands on All apps, Down on the action row does
# nothing, and Left stops at All apps, so NAV_ACTIONS reaches All apps whether
# focus starts on a tile or on the action row. Each path is an env var so a
# layout change does not need a script change (keys as in scripts/remote.sh):
#   NAV_ACTIONS   Home, any focus: go to All apps        (default "down left left")
#   NAV_FOCUS     Home: move focus along favorites       (default "up right right")
#   NAV_OPTIONS   open the options dialog                (default "menu")
#   NAV_ARRANGE   after NAV_ACTIONS, enter arrange mode and lift the second tile one
#                 place right (default "right select right select right"); the shot is
#                 followed by Back twice, which cancels the move and leaves arrange mode
#   NAV_ALL_APPS  after NAV_ACTIONS, open All apps       (default "select")
#   NAV_SETTINGS  Home, any focus: open Settings         (default: NAV_ACTIONS,
#                 then "right right select" if Arrange is shown, else "right select")
#   TOUR          the video's key sequence (see default below); stay inside Hearth
#                 and never open an app tile or the options dialog's Open action
# Themes are chosen by name, not by key count: Settings opens with the Theme
# row focused (checked); Select opens Appearance, the script chooses Single
# Theme, moves Left to the first card, then moves Right until the hint reads
# "Select to use <name>". Adding themes does not break it. THEME_MAIN
# (default "Dusk") is used for every shot but
# home-aurora, which uses THEME_ALT (default "Aurora"); THEME_MAX (default 12)
# bounds the presses. The mode and single theme set before the run are restored.
# Favorites must already be set up (via the UI) for a meaningful Home shot, and
# at least two for the arrange shot.
# Env: FFMPEG (default: ffmpeg on PATH), VIDEO_SECONDS (an upper bound, default 180;
# recording stops when the tour ends), RECORDER
# (host|device), TRIM_START/TRIM_END seconds, WEBP_QUALITY (default 50), ADB, ANDROID_SERIAL.
# HOME_SETTLE (default 4) and SHOT_DELAY (default 1) allow slower software emulators to settle.
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=scripts/lib/adb.sh
source scripts/lib/adb.sh

install=1 shots=1 video=1
for a in "$@"; do
  case "$a" in
    --no-install) install=0 ;;
    --shots-only) video=0 ;;
    --video-only) shots=0 ;;
    -*) echo "unknown option $a" >&2; exit 2 ;;
    *) export ANDROID_SERIAL="$a" ;;
  esac
done
require_device

FFMPEG="${FFMPEG:-ffmpeg}"
VIDEO_SECONDS="${VIDEO_SECONDS:-180}"
NAV_ACTIONS="${NAV_ACTIONS:-down left left}"
NAV_FOCUS="${NAV_FOCUS:-up right right}"
NAV_OPTIONS="${NAV_OPTIONS:-menu}"
NAV_ARRANGE="${NAV_ARRANGE:-right select right select right}"
NAV_ALL_APPS="${NAV_ALL_APPS:-select}"
NAV_SETTINGS="${NAV_SETTINGS:-}"
THEME_MAIN="${THEME_MAIN:-Dusk}"
THEME_ALT="${THEME_ALT:-Aurora}"
THEME_MAX="${THEME_MAX:-12}"
TOUR="${TOUR:-wait:3 right wait:1 right wait:1 menu wait:2 back wait:1 down left left right select right select right wait:2 back back wait:1 down left left select wait:3 right wait:1 right wait:1 down wait:1 back wait:2 down left left right right select wait:3 select wait:4 back back up back wait:4}"
RECORDER="${RECORDER:-host}"
TRIM_START="${TRIM_START:-1.2}"
TRIM_END="${TRIM_END:-1.0}"
WEBP_QUALITY="${WEBP_QUALITY:-50}"
SHOTS=docs/images MEDIA=docs/media RAW=build/media
mkdir -p "$SHOTS" "$MEDIA" "$RAW"

if [ $video -eq 1 ] && ! command -v "$FFMPEG" >/dev/null 2>&1; then
  echo "ffmpeg not found: set FFMPEG (docs/testing.md shows a pip-installable static build)" >&2
  exit 2
fi
[ $install -eq 1 ] && scripts/install.sh --demo

# A clean Home: cold start, wake, settle.
fresh_home() {
  "$ADB" shell input keyevent KEYCODE_WAKEUP
  "$ADB" shell am force-stop "$HEARTH_PKG"
  launch_hearth
  sleep "${HOME_SETTLE:-4}"
}
remote() { # shellcheck disable=SC2086 # key lists are intentionally word-split
  scripts/remote.sh $1
}
shot() { scripts/screenshots.sh -o "$SHOTS" "$1" >/dev/null; echo "$SHOTS/$1.png"; }
die() { echo "capture-media: $*" >&2; exit 1; }

# The current UI tree as one line of XML (uiautomator retries when the UI is busy).
ui_dump() {
  local i
  for i in 1 2 3; do
    if "$ADB" shell uiautomator dump /sdcard/hearth-ui.xml >/dev/null 2>&1; then
      "$ADB" exec-out cat /sdcard/hearth-ui.xml
      return 0
    fi
    sleep 1
  done
  return 1
}
has_arrange() { ui_dump | grep -q 'text="Arrange"'; }

# From Home with any focus, open Settings and check the Theme row has focus.
open_settings() {
  local i
  if [ -n "$NAV_SETTINGS" ]; then
    remote "$NAV_SETTINGS"
  else
    remote "$NAV_ACTIONS"
    if has_arrange; then remote "right right select"; else remote "right select"; fi
  fi
  for i in 1 2 3 4; do
    if ui_dump | grep -qE 'focused="true"[^>]*>(</node>|[[:space:]])*<node[^>]*text="Theme"'; then
      return 0
    fi
    sleep 2
  done
  die "Settings did not open with the Theme row focused (check NAV_ACTIONS / NAV_SETTINGS)"
}
# The Theme row's value, read from the node after its label.
theme_value() {
  ui_dump | sed -nE 's/.*text="Theme"[^>]*>(<\/node>|[[:space:]])*<node[^>]*text="([^"]*)".*/\2/p' | head -n1
}
theme_mode() {
  case "$1" in
    "Follow the Day,"*) echo follow ;;
    "Surprise Me,"*) echo surprise ;;
    *) echo single ;;
  esac
}
# In Settings with the Theme row focused, choose the theme named $1 in the Appearance panel.
choose_theme() {
  local i
  [ "$(theme_value)" = "$1" ] && return 0
  remote select
  sleep 1
  # Single Theme is the first mode row. Selecting it exposes and focuses the gallery.
  remote "up up up left left select"
  sleep 1
  for ((i = 0; i < THEME_MAX; i++)); do remote left; done
  for ((i = 0; i <= THEME_MAX; i++)); do
    if ui_dump | grep -qF "text=\"Select to use $1\""; then
      remote select
      sleep 1
      [ "$(theme_value)" = "$1" ] && return 0
      die "chose \"$1\" but the Theme row reads \"$(theme_value)\""
    fi
    remote right
    sleep 0.6
  done
  remote back
  die "theme \"$1\" not found in $THEME_MAX presses"
}

# Start from the main theme; remember the user's to restore it at the end.
fresh_home; open_settings
original=$(theme_value)
[ -n "$original" ] || die "could not read the Theme row's value"
original_mode=$(theme_mode "$original")
choose_theme "$THEME_MAIN"

if [ $shots -eq 1 ]; then
  fresh_home;                       shot home-dusk
  remote "$NAV_FOCUS";              shot home-focus
  remote "$NAV_OPTIONS";            shot options
  remote back
  fresh_home
  if has_arrange; then
    remote "$NAV_ACTIONS $NAV_ARRANGE"; shot arrange
    remote "back back"
  else
    echo "skipping arrange: it needs at least two favorites" >&2
  fi
  fresh_home; remote "$NAV_ACTIONS $NAV_ALL_APPS"; shot all-apps
  fresh_home; open_settings;        shot settings
  remote select;                    shot appearance
  remote "right select wait:1 select"; shot theme-schedule
  remote "back back"
  choose_theme "$THEME_ALT"
  fresh_home;                       shot home-aurora
  open_settings; choose_theme "$THEME_MAIN"
fi

if [ $video -eq 1 ]; then
  fresh_home
  # Back on Home restores the first favorite, a consistent start for the tour.
  remote back
  raw="$PWD/$RAW/tour-raw"
  if [ "$RECORDER" = host ]; then
    # The emulator records its own display on the host: smooth 30 fps, and no encoding
    # load inside the emulated device (on-device screenrecord drops to a few fps there).
    rm -f "$raw.webm"
    "$ADB" emu screenrecord start --time-limit "$VIDEO_SECONDS" --fps 30 "$raw.webm" >/dev/null
    sleep 1
    remote "$TOUR"
    "$ADB" emu screenrecord stop >/dev/null || true
    sleep 2
    src="$raw.webm"
  else
    dev=/sdcard/hearth-tour.mp4
    "$ADB" shell rm -f "$dev"
    "$ADB" shell screenrecord --size 1920x1080 --bit-rate 8000000 --time-limit "$VIDEO_SECONDS" "$dev" &
    rec=$!
    sleep 1
    remote "$TOUR"
    wait "$rec" || true
    sleep 1
    "$ADB" pull "$dev" "$raw.mp4" >/dev/null
    "$ADB" shell rm -f "$dev"
    src="$raw.mp4"
  fi

  # Trim the lead-in and tail, then fade in and out.
  # ffmpeg -i with no output exits non-zero by design; do not let pipefail end the run.
  dur=$({ "$FFMPEG" -hide_banner -i "$src" 2>&1 || true; } | sed -n 's/.*Duration: \([0-9:.]*\).*/\1/p' | awk -F: '{print $1*3600+$2*60+$3}')
  end=$(awk -v d="$dur" -v t="$TRIM_END" 'BEGIN{print d-t}')
  len=$(awk -v e="$end" -v s="$TRIM_START" 'BEGIN{print e-s}')
  fade_out=$(awk -v l="$len" 'BEGIN{print l-0.5}')

  # MP4 for the README link: H.264 1080p, no audio, web-optimized.
  "$FFMPEG" -hide_banner -loglevel error -y -ss "$TRIM_START" -to "$end" -i "$src" -an \
    -vf "fade=t=in:st=0:d=0.4,fade=t=out:st=$fade_out:d=0.5" -c:v libx264 -preset slow \
    -crf 20 -pix_fmt yuv420p -r 30 -movflags +faststart "$MEDIA/tour.mp4"

  # Animated WebP for inline display. GIF's 256 colors cannot hold the theme art's
  # grain (a 27 s GIF came out over 40 MB); WebP keeps a 40 s tour near 8 MB at 960 px.
  "$FFMPEG" -hide_banner -loglevel error -y -ss "$TRIM_START" -to "$end" -i "$src" -an \
    -vf "fps=15,scale=960:-1:flags=lanczos" -c:v libwebp -lossless 0 -quality "$WEBP_QUALITY" \
    -compression_level 6 -loop 0 "$MEDIA/tour.webp"
  ls -l "$MEDIA/tour.mp4" "$MEDIA/tour.webp"
fi

# Put back the mode and, for Single Theme, the selected pack from before the run.
fresh_home; open_settings
case "$original_mode" in
  single) choose_theme "$original" ;;
  follow)
    remote "select up up up left left right select"
    sleep 1
    remote "back back"
    ;;
  surprise)
    remote "select up up up left left right right select"
    sleep 1
    remote back
    ;;
esac
