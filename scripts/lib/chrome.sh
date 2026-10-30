# shellcheck shell=bash
# Headless Chrome screenshot helper, sourced by the render scripts.
#
#   chrome_shot <input.svg|input.html> <output.png> <css-width> <css-height> [scale]
#
# CHROME selects the browser binary. On Linux or macOS it defaults to the first
# of google-chrome, chromium, chromium-browser found on PATH. Under WSL, a
# Windows chrome.exe also works: paths are translated with wslpath and the
# screenshot is written to the Windows temp folder (WINTMP, a WSL path; detected
# through cmd.exe when unset, found on PATH or in the default Windows system
# folder) and moved back.

chrome_find() {
  if [ -n "${CHROME:-}" ]; then echo "$CHROME"; return; fi
  local c
  for c in google-chrome chromium chromium-browser; do
    if command -v "$c" >/dev/null 2>&1; then command -v "$c"; return; fi
  done
  c="/mnt/c/Program Files/Google/Chrome/Application/chrome.exe"
  if [ -x "$c" ]; then echo "$c"; return; fi
  echo "chrome_find: no Chrome found; set CHROME" >&2
  return 1
}

chrome_shot() {
  local in="$1" out="$2" w="$3" h="$4" scale="${5:-1}"
  local chrome
  chrome=$(chrome_find)
  local common=(--headless=new --disable-gpu --hide-scrollbars --no-first-run
    --no-default-browser-check --force-color-profile=srgb --default-background-color=00000000
    --run-all-compositor-stages-before-draw --virtual-time-budget=3000
    --window-size="$w,$h" --force-device-scale-factor="$scale")
  if [[ "$chrome" == *.exe ]]; then
    local wintmp="${WINTMP:-}"
    if [ -z "$wintmp" ]; then
      local cmd=cmd.exe
      command -v "$cmd" >/dev/null 2>&1 || cmd=/mnt/c/Windows/System32/cmd.exe
      wintmp=$(wslpath "$(cd /mnt/c 2>/dev/null || true; "$cmd" /c 'echo %TEMP%' 2>/dev/null | tr -d '\r')")
    fi
    local tag="hearth-shot-$$-$RANDOM"
    local shot="$wintmp/$tag.png" profile="$wintmp/$tag-profile"
    mkdir -p "$profile"
    local url
    url=$(wslpath -w "$(realpath "$in")")
    url="file:///${url//\\//}"
    "$chrome" "${common[@]}" --user-data-dir="$(wslpath -w "$profile")" \
      --screenshot="$(wslpath -w "$shot")" "$url" >/dev/null 2>&1 || true
    rm -rf "$profile"
    if [ ! -s "$shot" ]; then echo "chrome_shot: no screenshot for $in" >&2; return 1; fi
    mv "$shot" "$out"
  else
    local profile
    profile=$(mktemp -d)
    "$chrome" "${common[@]}" --user-data-dir="$profile" --screenshot="$out" \
      "file://$(realpath "$in")" >/dev/null 2>&1 || true
    rm -rf "$profile"
    [ -s "$out" ] || { echo "chrome_shot: no screenshot for $in" >&2; return 1; }
  fi
}
