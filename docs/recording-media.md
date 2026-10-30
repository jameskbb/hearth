# Recording the README media

How the screenshots, tour video and title card in the README are made. Run it
again whenever the interface changes; the project rules require the README
media to match the app.

Everything runs on a development Android TV emulator with the local service
stand-ins in [`demo-apps.md`](demo-apps.md). Familiar service names appear,
but these are visibly labeled demos, not official clients. No accounts or
household details belong in captures.

The README tour stays inside Hearth: favorites, app options, rearranging tiles,
All apps, Settings, and Appearance. Do not launch an app tile or choose Open
from its options. The separate demo smoke check still exercises app launching;
demo destination screens do not belong in promotional recordings.

## What you get

| File | Made by | Notes |
|---|---|---|
| `docs/images/home-dusk.png`, `home-focus.png`, `options.png`, `arrange.png`, `all-apps.png`, `settings.png`, `appearance.png`, `theme-schedule.png`, `home-aurora.png` | `scripts/capture-media.sh` | 1920x1080 screencaps |
| `docs/media/tour.mp4` | `scripts/capture-media.sh` | H.264 1080p, 30 fps, about one minute |
| `docs/media/tour.webp` | `scripts/capture-media.sh` | Animated, 960 px, 15 fps, plays inline in the README |
| `docs/images/readme-title-hearth.png` | `scripts/render-title-card.sh` | 1200x440 at 2x, from `docs/src/title-card.html` |

## 1. Prepare the emulator

1. Create and start the Android TV emulator (`docs/testing.md`). Give it room
   to render smoothly; recording is CPU-bound because the emulator renders in
   software:

   ```bash
   scripts/emulator.sh create
   EMULATOR_ARGS="-cores 6 -memory 3072" scripts/emulator.sh start
   ```

2. Install Hearth and the demo apps:

   ```bash
   scripts/install.sh --demo emulator-5554
   scripts/demo-smoke.sh emulator-5554
   ```

   Hide unrelated launchable apps with Hearth's own Menu, Hide action.
   Do not disable system apps or modify other apps for a capture.

3. In All apps, use Menu, Add to favorites to include each of the six demos.
   Arrange them as Netflix, Disney+, Hulu, ESPN, Prime Video, and YouTube.
   Existing demo installations retain their package IDs, so an update keeps
   the established favorites order.

4. Select Single Theme and Dusk in Appearance. Use the emulator's actual
   local clock; do not change system time or global settings for a capture.

5. Wait for system notifications (for example "Wi-Fi has no internet access")
   to clear before capturing.

## 2. Capture

```bash
export FFMPEG=/path/to/ffmpeg   # or have ffmpeg on PATH
scripts/capture-media.sh --no-install
```

The script takes the nine screenshots, then records the scripted tour. By
default (`RECORDER=host`) the emulator records its own display on the host
with `adb emu screenrecord`, which gives a smooth 30 fps. On-device
`screenrecord` (`RECORDER=device`) runs at about 6 fps on a software-rendered
emulator and is only a fallback.

No ffmpeg installed? The `imageio-ffmpeg` Python package ships a static binary:

```bash
python3 -m venv .venv && .venv/bin/pip install imageio-ffmpeg
export FFMPEG=$(.venv/bin/python -c "import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())")
```

Useful overrides (all environment variables; see the script header):

| Variable | Default | Use |
|---|---|---|
| `TOUR` | the documented tour | Key sequence, e.g. `right wait:1 menu wait:1.8 back` |
| `VIDEO_SECONDS` | 180 | Upper bound; recording stops when the tour ends |
| `TRIM_START`, `TRIM_END` | 1.2, 1.0 | Seconds trimmed before the fades |
| `WEBP_QUALITY` | 50 | Lower to shrink the inline clip |
| `CAPTURE_SOURCE` | `device` | Use `host` for native emulator screenshots without slow guest PNG encoding |
| `INPUT_SOURCE` | `device` | Use `host` for emulator D-pad, Select and Back input without starting a guest process for every press |
| `HOME_SETTLE`, `SHOT_DELAY` | 4, 1 | Seconds to let a software emulator finish loading before capture |
| `NAV_*`, `THEME_*` | see script | Paths to each screen and the themes to show, if the layout changes |

Then render the title card, which embeds `home-focus.png`:

```bash
scripts/render-title-card.sh
```

## 3. Review before committing

Look at every file; do not commit captures you have not viewed.

- **Screenshots:** no system toasts, the clock reads a sensible time, focus is
  where the README text says it is, and the arrange shot shows a lifted tile.
- **Video:** make a contact sheet and check that the tour reaches every screen
  and ends on a tidy Home. Keep app destination screens out of the recording.
  Trim brief emulator artwork-loading pauses after reviewing the footage, and
  apply the same edits to the MP4 and inline WebP:

  ```bash
  "$FFMPEG" -i docs/media/tour.mp4 -vf "fps=1/2.5,scale=480:-1,tile=4x4" -frames:v 1 build/media/sheet.png
  ```

- **Sizes:** `tour.webp` should stay under about 10 MB. GIF is not used: the
  wallpaper's fine grain, which prevents banding, defeats GIF's 256-color
  palette (a 27 s GIF came out over 40 MB).

## Pitfalls

- `ffmpeg -i <file>` with no output exits non-zero by design. In a script
  running `set -euo pipefail`, guard it (`|| true`) or the run ends silently.
- If the tour is longer than `VIDEO_SECONDS`, the last steps are cut and the
  clip can end mid-scroll. The script stops recording when the tour ends, so
  keep the bound generous.
- Software-only emulation can take much longer to load art. Increase
  `SHOT_DELAY` and inspect the capture before continuing; a blank tile is
  not a finished screenshot. `CAPTURE_SOURCE=host INPUT_SOURCE=host` avoids
  guest capture/input overhead. Use `REMOTE_DELAY=2` if focus changes need
  more time. Host input uses the standard emulator qwerty key layout;
  physical devices keep the default ADB input path.
- GitHub plays a repository MP4 only as a download link. The inline motion in
  the README comes from the animated WebP; the MP4 is the full-quality copy.
