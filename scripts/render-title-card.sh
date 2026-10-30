#!/usr/bin/env bash
# Render the README title card (docs/src/title-card.html) to
# docs/images/readme-title-hearth.png at 1200x440, device scale 2.
# Re-run after recapturing docs/images/home-focus.png. CHROME as in scripts/lib/chrome.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=scripts/lib/chrome.sh
source scripts/lib/chrome.sh
chrome_shot docs/src/title-card.html docs/images/readme-title-hearth.png 1200 440 2
echo docs/images/readme-title-hearth.png
