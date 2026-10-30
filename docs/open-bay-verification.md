# Open Bay and Home label verification

## Deliverables

- [Original artwork sources and prompt](../design/art/open-bay/).
- [Bundled pack](../app/src/main/assets/themes/open-bay/), including the
  [480x270 gallery preview](../app/src/main/assets/themes/open-bay/preview.webp).
- [1920x1080 rest state](images/theme-open-bay.png).
- [Eight motion frames over 0 to 240 seconds](images/open-bay-motion.png).
- Native API 28 TV emulator captures: [label enabled](images/home-open-bay-label.png),
  [label disabled](images/home-open-bay.png), [label with weather](images/home-open-bay-label-weather.png),
  [gallery](images/open-bay-gallery.png), and [label editor](images/home-label-settings.png).
- Cross-theme captures: [Sunlit Valley](images/home-label-sunlit-valley.png),
  [Aurora](images/home-label-aurora.png), and [all seven existing themes](images/home-label-themes.png).

The service tiles are the repository's offline demo apps, visibly marked Demo.
The weather capture uses a fresh local cache fixture with generic coordinates
and example conditions to exercise the actual Home layout. It is not a live
weather observation. Other captures have weather off. No accounts or household
data were used.

## Artwork and review

The original environment was generated with the built-in image tool, without
third-party reference images. Two local refinements removed the small lamp
behind the clock and dimmed the side window behind weather. The complete
generation and edit prompts are preserved with the sources. No personalized
text is painted into the scene.

The base SVG embeds the selected painting losslessly. This preserves brushwork
instead of reconstructing it as vector primitives. Haze and dust are separate,
editable SVG layers rendered by the same script as every other pack. Rebuilding
uses only local sources, Chrome, Pillow and numpy, with no image service.
Structural depth is painted into the base; the bench, car and architecture do
not move. This deliberately avoids sliding edges or cutout seams. The shared
renderer animates only faint light and sparse dust. Reduce motion uses the
attractive midpoint still, without losing any environmental detail.

Reviewed the full-size rest render, preview, initial contact sheet and a second
six-frame sheet from 600 to 840 seconds. Checked warm/cool balance, material
texture, workbench and car recognition, fine grain, static edge alignment,
overscan, and the low amplitude of the atmospheric changes. Nothing moves
through the lettering. Checked the native clock, weather, app name, hint,
navigation and Home label. The label stays restrained and right aligned,
without a panel or new Home focus stop. The seven existing scenes retain their
palettes, focus treatment and layout.

## Automated results

| Check | Result |
|---|---|
| `scripts/render-theme.sh open-bay` | Passed, three layers and 480x270 preview |
| Decoded artwork budget | 12,441,600 bytes (11.9 MiB), below 32 MB |
| Base and overlay files | 1,951,346 bytes, plus the 32,862-byte preview |
| Rebuild reproducibility | Manifest, preview, notes and all three WebPs matched SHA-256 after a second render |
| `scripts/theme-compose.py`, initial and sustained frames | Passed |
| `scripts/banding-check.py`, upper region | p95 5 px, max 8 px, passed |
| `scripts/banding-check.py`, foreground | p95 4 px, max 8 px, passed |
| `./gradlew testDebugUnitTest` | 388 app JVM tests, zero failures or skips |
| `./gradlew assembleDebug` | Passed |
| `scripts/check.sh` | Passed, including fixture/demo builds and repository checks |
| Impeccable detector on changed Compose screens | No findings |

Tests cover manifest defaults and strict boolean validation, catalog metadata,
eight gallery entries, manual selection, unchanged recommended schedule,
implicit Surprise Me exclusion, explicit opt-in, automatic fallbacks, unchanged
existing rotations, and retaining a full explicit pool across new installs.

Label tests cover defaults, blank/disabled suppression, Unicode-safe length
limiting, control sanitization, preference round trips, real DataStore restarts,
retained disabled text and clearing, editor actions and Back focus, and width
allocation with ordinary, wide and overfull weather headers. Native remote
entry saved the generic label through the existing field and Save action.

## Reproduce

With the documented Android SDK and JDK 17, plus a headless Chrome binary and
a Python interpreter with Pillow and numpy:

```bash
REVIEW_DIR=build/open-bay-review scripts/render-theme.sh open-bay
python3 scripts/theme-compose.py app/src/main/assets/themes/open-bay \
  --frames 6 --start-seconds 600 --seconds 240 \
  --contact-sheet build/open-bay-review/sustained-motion.png
python3 scripts/banding-check.py app/src/main/assets/themes/open-bay/workshop.webp
./gradlew testDebugUnitTest
./gradlew assembleDebug
scripts/check.sh
```

Use `CHROME` and `PYTHON` overrides when the tools are not on PATH. This review
used Chrome headless shell and the TV emulator in software mode. Native PNGs
were captured with `CAPTURE_SOURCE=host scripts/screenshots.sh`.

## Remaining physical-device validation

No physical-TV testing was performed. Verify sofa-distance lettering and focus,
panel banding and grain in a dark room, sustained frame pacing and memory,
Reduce motion, lifecycle pauses, the TV's IME and remote focus, and persistence
after an actual reboot. Check long localized clock/weather content and API 25's
static-font path on hardware. The checklist is in [testing.md](testing.md).
