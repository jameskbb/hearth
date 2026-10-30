#!/usr/bin/env bash
# Render a bundled theme pack from its SVG sources and copy it into the app.
#
#   scripts/render-theme.sh <id> [<id> ...]
#
# Sources live in design/art/<id>/: theme.json plus one SVG per layer named
# NN-<name>.svg, drawn first to last in NN order. Each SVG is rendered with
# headless Chrome at its own width and height times its data-scale attribute
# (default 2, so a 960x540 source gives the 1920x1080 base; soft layers set
# data-scale="1" for 960 px wide images). Layer 00 is the base and is encoded
# as opaque RGB with the anti-banding grain; the others keep their alpha (an
# opaque one is written as RGB with grain). The image for NN-<name>.svg is
# <name>.webp. The pack lands in app/src/main/assets/themes/<id>/ with its
# theme.json, any notes.txt, and preview.webp from scripts/theme-compose.py,
# and the base layer goes through scripts/banding-check.py.
#
# If design/art/<id>.svg exists (the single-file Dusk and Aurora art the packs
# were split from), it is rendered too and the pack's rest state is compared
# with it.
#
# Env: CHROME (browser binary, see scripts/lib/chrome.sh), PYTHON (an
# interpreter with Pillow and numpy; default python3), OUT_PNG (intermediate
# PNGs; default build/art, not committed), REVIEW_DIR (when set, the rest
# state and a contact sheet of 8 frames over 240 seconds are written to
# REVIEW_DIR/<id>/ for review).
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=scripts/lib/chrome.sh
source scripts/lib/chrome.sh

PYTHON="${PYTHON:-python3}"
OUT_PNG="${OUT_PNG:-build/art}"
[ $# -gt 0 ] || { echo "usage: scripts/render-theme.sh <id> [<id> ...]" >&2; exit 2; }

# Prints "width height scale" from the root <svg> element of a source file.
svg_size() {
  "$PYTHON" - "$1" <<'PY'
import re, sys
head = open(sys.argv[1], encoding="utf-8").read(4096)
tag = re.search(r"<svg\b[^>]*>", head, re.S).group(0)
def attr(n, d=None):
    m = re.search(r'\s' + n + r'="([^"]+)"', tag)
    return m.group(1) if m else d
print(attr("width"), attr("height"), attr("data-scale", "2"))
PY
}

for id in "$@"; do
  src="design/art/$id"
  dst="app/src/main/assets/themes/$id"
  png="$OUT_PNG/$id"
  [ -f "$src/theme.json" ] || { echo "no such theme source: $src/theme.json" >&2; exit 1; }
  mkdir -p "$dst" "$png"
  rm -f "$dst"/*.webp "$dst"/*.png
  base=""
  for svg in "$src"/[0-9][0-9]-*.svg; do
    file=$(basename "$svg" .svg)
    name="${file#[0-9][0-9]-}"
    read -r w h scale < <(svg_size "$svg")
    chrome_shot "$svg" "$png/$file.png" "$w" "$h" "$scale"
    if [ -z "$base" ]; then
      base="$dst/$name.webp"
      "$PYTHON" scripts/encode-art.py "$png/$file.png" "$base"
    else
      "$PYTHON" scripts/encode-art.py --overlay "$png/$file.png" "$dst/$name.webp"
    fi
  done
  [ -n "$base" ] || { echo "no layer sources in $src" >&2; exit 1; }
  cp "$src/theme.json" "$dst/theme.json"
  if [ -f "$src/notes.txt" ]; then cp "$src/notes.txt" "$dst/notes.txt"; fi

  compose=(scripts/theme-compose.py "$dst")
  if [ -f "design/art/$id.svg" ]; then
    chrome_shot "design/art/$id.svg" "$png/reference.png" 960 540 2
    "$PYTHON" scripts/encode-art.py "$png/reference.png" "$png/reference.webp" >/dev/null
    compose+=(--compare "$png/reference.webp")
  fi
  if [ -n "${REVIEW_DIR:-}" ]; then
    mkdir -p "$REVIEW_DIR/$id"
    compose+=(--rest "$REVIEW_DIR/$id/rest.png" --frames 8 --seconds 240
      --contact-sheet "$REVIEW_DIR/$id/contact-sheet.png")
  fi
  "$PYTHON" "${compose[@]}"
  "$PYTHON" scripts/banding-check.py "$base"
done
