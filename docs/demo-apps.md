# Demo apps

The optional `demoapps` module supplies six local stand-ins for README
screenshots and remote-navigation demos: Netflix, Disney+, Hulu, ESPN,
Prime Video, and YouTube. They are not the official apps and cannot stream,
sign in, or make network requests. Each opens an explicit demo screen;
Select on Return to Hearth or the remote's Back key closes it.

The tiles are original text layouts in Instrument Sans with a visible Demo
label, not official logos. Service names identify examples only. Hearth is
not affiliated with or endorsed by these services; their names and trademarks
remain their owners' property. These stand-ins are not included in the Hearth
APK. Install them only on a development emulator, not your everyday TV.

## Build and try

With the Android SDK, JDK 17, and a running TV emulator:

```bash
scripts/install.sh --demo emulator-5554
scripts/demo-smoke.sh emulator-5554
```

The smoke check opens each demo, verifies its disclaimer and focused return
button, and exercises Select and Back returning to Hearth. Use All apps to
pin and arrange the six examples for screenshots. Hide unwanted test or
system entries using Hearth's own Hide action; do not disable system apps.

Package IDs deliberately remain under `io.github.jameskbb.hearth.demo` and
keep their original fixture suffixes for compatible upgrades:

| Service shown | Flavor and package suffix |
|---|---|
| Netflix | `kestrel` |
| Disney+ | `lowtide` |
| Hulu | `marquee` |
| ESPN | `northwind` |
| Prime Video | `pinhole` |
| YouTube | `puddlejump` |

No official service package is replaced or modified. Existing demo favorites
survive `adb install -r` because those IDs stay stable.

## Rebuild artwork and media

Edit `design/demo-apps/<flavor>.html` and `demo.css`, then run
`scripts/render-brand.sh` with headless Chrome and Python with Pillow/numpy.
The committed 320x180 banners make normal APK builds independent of these
design tools. Capture native screens with `scripts/screenshots.sh` or the
full procedure in [`recording-media.md`](recording-media.md), then regenerate
the title card with `scripts/render-title-card.sh`.
