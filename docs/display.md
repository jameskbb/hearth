# Display: what the panel can do, and what Hearth can draw

A TV can be capable of far more than a launcher is allowed to put on screen.
This page separates three things that are easy to conflate, states Hearth's
plan, and records the wallpaper banding measurements.

Each claim is labeled:
- **Documented**: from platform documentation or the TV class in general; not
  measured by this project.
- **Verified (emulator)**: observed on the API 28 Android TV emulator.
- **Verified (device)**: observed on a real TV. Nothing is in this state yet.

## (a) Panel capabilities

**Documented.** TVs in the class Hearth targets typically have 4K (3840x2160)
60 Hz panels, many with quantum-dot (QLED) color filters that cover a wider
gamut than sRGB, and accept HDR10, HLG and often Dolby Vision signals. This is
what the panel can show when it is fed a suitable signal. It says nothing
about what an app's user interface can send it.

## (b) Video playback capabilities

**Documented.** Streaming apps decode HDR and wide-gamut video through their
own media pipelines (hardware decoders and video surfaces composited by the
platform, often switching the output mode to HDR for the duration of the
video). That path bypasses the ordinary UI surface entirely, so a launcher's
rendering has no bearing on how well other apps play HDR video, and a launcher
cannot borrow that path for its own interface.

## (c) What a launcher can render

- **UI resolution. Documented.** On Fire OS the UI surface is commonly 1080p
  (960x540 dp at 320 dpi) even when the panel is 4K; the platform scales it up.
  Hearth designs for that canvas (`docs/design.md`) and ships 1920x1080
  wallpapers. It does not assume a 4K UI.
- **Wide color. Documented.** Hearth reports no wide-gamut UI support below
  API 26 without calling the unavailable platform API. From Android 8 (API 26), an activity can render
  in a wide color gamut only when `Display.isWideColorGamut()` returns true and
  the window requests `ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT`. Many TV
  platforms report false here even on wide-gamut panels, in which case the UI
  is sRGB and the panel's own processing decides how sRGB is mapped.
- **HDR for UI. Documented.** API 28 gives apps no way to render their UI in
  HDR. Window-level HDR modes for app UI arrived in much later Android
  releases, which Fire OS 7 does not have.
- **Verified (emulator).** The API 28 TV emulator reports 1920x1080 at 320 dpi,
  60 Hz, a single 1920x1080 @ 60 Hz mode, wide color gamut "No" and no HDR
  types, as shown in Hearth's Settings, About.

## Plan

1. **SDR sRGB by default.** All assets are sRGB, 1920x1080, and the default
   rendering path is tuned to look excellent in SDR. Saturation is never raised
   to imitate wide gamut.
2. **Wide gamut only when real.** A wide-gamut path would be enabled only if
   the device reports `isWideColorGamut()` true and a test on that device shows
   the requested color mode is actually honored end to end (for example a
   test pattern with out-of-sRGB colors that visibly differs). Until then there
   is no such path.
3. **No HDR UI** on API 28.
4. **Verifiable claims.** Settings, About shows the facts the device reports
   (UI resolution and density, refresh rate, supported modes, wide color gamut,
   HDR types), so anyone can check on their own TV what Hearth is working with.

## Wallpaper banding

Smooth 8-bit gradients that change by a few levels over hundreds of pixels
quantize into visible bands, which a bright, high-contrast panel in a dark room
makes obvious. The wallpapers carry grain to break the bands up.

`scripts/banding-check.py` measures the risk. Along every vertical scanline of a
region it finds runs of one exact RGB value that are bounded on both ends by a
small step (1 to 3 levels), which is the signature of a quantized gradient;
runs bounded by hard edges (ridge and treeline silhouettes) are ignored. It
reports the 95th percentile and maximum of the longest such run per column. A
p95 above 16 px is flagged as a risk. Regions: upper sky (top 30%) and the dark
foreground behind the controls (bottom 20%).

Measured at 1920x1080 (p95 / max, in px):

| Wallpaper | Encoding | Sky | Foreground | Size |
|---|---|---|---|---|
| Dusk | previous: lossy WebP | 20 / 24 RISK | 66 / 72 RISK | 38 KiB |
| Dusk | lossless, SVG grain only | 6 / 8 | 43 / 59 RISK | 991 KiB |
| Dusk | lossy q95 + 1.5-level grain | 24 / 24 RISK | 65 / 72 RISK | 67 KiB |
| **Dusk** | **lossless + 1.5-level grain (shipped)** | **5 / 8** | **10 / 21** | **1306 KiB** |
| Aurora | previous: lossy WebP | 64 / 64 RISK | 0 / 8 | 35 KiB |
| Aurora | lossless, SVG grain only | 10 / 12 | 125 / 192 RISK | 970 KiB |
| **Aurora** | **lossless + 1.5-level grain (shipped)** | **6 / 11** | **12 / 21** | **1347 KiB** |

Findings:
- Lossy WebP removes the low-amplitude grain (block quantization and 4:2:0
  chroma), so the bands come back, even at quality 95.
- The SVG grain is an overlay blend, which nearly vanishes in deep shadow, so
  the dark foregrounds still banded with lossless encoding.
- Shipped: lossless WebP plus a fixed-seed, zero-mean luminance dither of about
  1.5 levels added in `scripts/encode-art.py` (the same offset on R, G and B, so
  no hue or saturation shift). Every measured region is now under the limit.
- Cost: about 1.3 MB per wallpaper in the APK. Decoded, a 1920x1080 ARGB bitmap
  is about 8 MB regardless of encoding; Hearth shows one wallpaper at a time,
  which is acceptable on a 2 GB device.

The wallpapers are now theme packs. Regenerate one with
`PYTHON=<python with Pillow and numpy> scripts/render-theme.sh <id>`, which
renders `design/art/<id>/*.svg` with headless Chrome, encodes, and runs the
banding check on the base (see `docs/theme-packs.md`, Making the art).

**Not yet verified:** how the grain and gradients look on a real QLED panel in a
dark room (banding, and haloing around the focus glow). That check is in the
manual checklist in `docs/testing.md`.

Open Bay uses the same lossless, grain-dithered SDR pipeline. Its 1920x1080
workshop base measures 5 / 8 px in the upper region and 4 / 8 px in the
foreground (p95 / max), both under the risk threshold. The three decoded layers
use 12,441,600 bytes (11.9 MiB). Physical-panel verification remains pending.
