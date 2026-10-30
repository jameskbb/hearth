#!/usr/bin/env bash
# Render Hearth's identity assets and the demo app banners to pixels.
#
#   scripts/render-brand.sh
#
# Sources:
#   design/brand/banner.html      -> app/src/main/res/drawable-xhdpi/banner.png (320x180)
#   design/brand/icon.svg         -> app/src/main/res/mipmap-*dpi/ic_launcher.png (legacy icon)
#   design/demo-apps/<app>.html   -> demoapps/src/<app>/res/drawable-xhdpi/banner.png
# The adaptive icon layers (mipmap-anydpi-v26, drawable/ic_launcher_*.xml) are
# hand-written vectors that mirror icon.svg.
#
# Text is rendered to pixels here, so nothing depends on fonts at runtime.
# Pages are captured at 4x in a large window (browsers clamp tiny windows) and
# cropped and downsampled by brand-post.py.
# Env: CHROME (see scripts/lib/chrome.sh), PYTHON (with Pillow and numpy).
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=scripts/lib/chrome.sh
source scripts/lib/chrome.sh

PYTHON="${PYTHON:-python3}"
OUT=build/brand
mkdir -p "$OUT"

# Every page lays its artwork out at the top-left corner of an 800x600 window.
shot() { chrome_shot "$1" "$2" 800 600 4; }

shot design/brand/banner.html "$OUT/banner.png"
"$PYTHON" scripts/brand-post.py crop "$OUT/banner.png" 4 320 180 app/src/main/res/drawable-xhdpi/banner.png

shot design/brand/icon.html "$OUT/icon.png"
for spec in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  "$PYTHON" scripts/brand-post.py crop "$OUT/icon.png" 4 192 192 \
    "app/src/main/res/mipmap-${spec%%:*}/ic_launcher.png" "${spec##*:}"
done

for page in design/demo-apps/*.html; do
  app=$(basename "$page" .html)
  shot "$page" "$OUT/demo-$app.png"
  "$PYTHON" scripts/brand-post.py crop "$OUT/demo-$app.png" 4 320 180 \
    "demoapps/src/$app/res/drawable-xhdpi/banner.png"
done
echo "rendered brand assets"
