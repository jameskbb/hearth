# Theme packs

A theme is a folder: one manifest, `theme.json`, and the images it names.
Hearth ships thirteen packs inside the APK (`app/src/main/assets/themes/`) and
loads extra packs from a folder on the device. Nothing in the launcher's UI
code knows a theme by name; the gallery in Settings, Appearance lists whatever
packs load.

Decision records: [0006](decisions/0006-theme-packs.md) and
[0008](decisions/0008-follow-the-day-and-theme-rotation.md).

## Where packs live

| Kind | Location | Discovered |
|---|---|---|
| Bundled | `app/src/main/assets/themes/<id>/` in the source tree | At build time; every folder with a `theme.json` is a theme |
| Installed | `Android/data/io.github.jameskbb.hearth/files/themes/<id>/` on the device's shared storage, or a `.zip` in that folder | At app launch, when Appearance opens, and when Reload themes is selected |

Adding a bundled theme is adding a folder; no Kotlin changes. Installing a
pack on a TV over ADB:

```bash
adb push misty-forest/ /sdcard/Android/data/io.github.jameskbb.hearth/files/themes/misty-forest/
# or
adb push misty-forest.zip /sdcard/Android/data/io.github.jameskbb.hearth/files/themes/
```

A zip holds `theme.json` at its root or inside one top-level folder. The
folder is Hearth's own app-specific storage, so no storage permission is
involved, and uninstalling Hearth removes it. Packs there are read-only to
Hearth: it never writes, moves or deletes them.

When a bundled pack and an installed pack share an id, the bundled one wins
and the installed one is listed as not loaded, with the reason.

### Development loop on a TV

Use a zip for the safest quick iteration. Push it under a temporary extension,
then rename it only after ADB finishes. Hearth ignores the temporary file, so
it cannot scan a half-copied archive.

```bash
zip -r my-theme.zip my-theme/
adb push my-theme.zip /sdcard/Android/data/io.github.jameskbb.hearth/files/themes/my-theme.zip.part
adb shell mv /sdcard/Android/data/io.github.jameskbb.hearth/files/themes/my-theme.zip.part /sdcard/Android/data/io.github.jameskbb.hearth/files/themes/my-theme.zip
```

Open Settings, Theme, then select Reload themes. Hearth fingerprints the
manifest, preview, and referenced layer bytes. A changed pack invalidates its
decoded backdrop and preview even when `theme.json` did not change. Unchanged
packs keep their caches. A malformed pack is listed with its reason and never
crashes the launcher. Fix the files, push again, and reload.

For a folder pack, push all changed files before selecting Reload themes. A
zip is preferable when several files must change together.

## The manifest

```json
{
  "format": 1,
  "id": "misty-forest",
  "name": "Misty Forest",
  "author": "Hearth",
  "description": "Pine ridges in drifting fog",
  "dayparts": ["morning"],
  "palette": {
    "ink": "#FFEEF4F0",
    "inkMuted": "#B8EEF4F0",
    "accent": "#FF8FD9B8",
    "glow": "#5C8FD9B8",
    "shade": "#E0071017",
    "shadeMid": "#73071017",
    "window": "#FF071017"
  },
  "preview": "preview.webp",
  "motion": { "sway": { "amplitude": 10, "period": 90 } },
  "layers": [
    { "image": "sky.webp" },
    { "image": "mountains.webp", "parallax": 0.2 },
    { "image": "fog-far.webp", "parallax": 0.3, "wrap": true,
      "drift": { "x": 80, "period": 150 },
      "opacity": { "min": 0.55, "max": 0.85, "period": 40 } },
    { "image": "pines-near.webp", "parallax": 1.0 }
  ]
}
```

### Top level

| Field | Required | Meaning |
|---|---|---|
| `format` | yes | Manifest version. Hearth 0.2 reads `1`; a higher number is rejected with "needs a newer Hearth". |
| `id` | yes | 2 to 40 characters of `a-z`, `0-9` and `-`. Stored in preferences, so changing it loses the selection. `auto` is reserved. |
| `name` | yes | Shown in the gallery. Sentence case, up to 40 characters. |
| `author`, `description` | no | Shown under the name in the gallery. |
| `autoRotationEligible` | no | Boolean, default `true`. `false` excludes a pack from implicit automatic pools and automatic fallbacks. It does not restrict Single Theme or explicit user assignments to Surprise Me or Follow the Day. |
| `dayparts` | no | Any of `morning`, `day`, `evening`, `night`. These are recommendations for default schedules and discovery, never restrictions. Users can assign a pack to any period. |
| `palette` | yes | Colors as `#AARRGGBB` or `#RRGGBB`. `window` and `accent` are required; the rest default as the table below says. |
| `preview` | no | A 480x270 image for the gallery card. Without it the card shows the first layer, scaled down. |
| `motion` | no | `sway.amplitude` in dp (default 0) and `sway.period` in seconds (default 90): a slow to-and-fro of the whole scene that each layer follows by its `parallax`. |
| `layers` | yes | 1 to 8 layers, drawn first to last. |

### Palette

| Color | Used for | Default |
|---|---|---|
| `window` | The solid color behind everything, shown before the images decode | required |
| `accent` | Focus ring, selected values, underlines | required |
| `ink` | Primary text | `#FFF4F1EC` |
| `inkMuted` | Secondary text (hints, dates) | `ink` at alpha `B8` |
| `glow` | The light under the focused tile | `accent` at alpha `66` |
| `shade` | Legacy palette field, accepted for pack compatibility | `window` at alpha `E0` |
| `shadeMid` | Legacy palette field, accepted for pack compatibility | `window` at alpha `73` |

Text sits directly on the art with a restrained glyph shadow. Hearth does not
draw gradient pools behind labels or dim the screen. Check the clock, hints,
and Settings over the brightest and darkest motion phases of a custom pack.

### Layers

Each layer is an image file (WebP or PNG, alpha allowed) placed on the
960x540 dp canvas: it is scaled so its width spans the canvas and its top
edge sits `y` dp from the top (default 0). A 16:9 image therefore fills the
canvas; a 1920x400 strip at `y: 300` is a 200 dp band across the lower
half. The first layer is the base: 16:9, opaque, and the full 1920x1080 px.
Images are at most 1920 px wide; a soft layer such as fog, clouds or a glow
can be authored at half size (960 px wide) and is scaled up on the device,
which keeps memory down. The decoded size of all layers together (width x
height x 4 bytes each) must stay under 32 MB, or the pack is rejected: in
practice at most three full-size layers, the rest half size or strips.

| Field | Default | Meaning |
|---|---|---|
| `image` | required | Path relative to the pack folder. |
| `y` | `0` | Where the image's top edge sits, in dp from the top of the canvas. |
| `parallax` | `0` | 0 to 1: how much of the scene's sway this layer follows. Far layers take small values, near layers 1. |
| `drift` | none | `x` in dp and `period` in seconds. Without `wrap`, the layer glides to and fro by up to `x` dp, one full cycle per period, on a sine. With `wrap`, it moves `x` dp every period, continuously, and repeats horizontally. |
| `wrap` | `false` | The image tiles seamlessly left to right (its left and right edges match). Required for continuous drift. |
| `opacity` | constant 1 | `min`, `max` (0 to 1) and `period` in seconds: a sine between the two. Optional `phase` (0 to 1) offsets the cycle so two layers do not breathe together. |
| `fade` | none | A one-time change: `from` and `to` (0 to 1), `delay` and `duration` in seconds, counted from when the theme appears. Smoothstep. Multiplies with `opacity`. This is how a sky turns to twilight and stars come out. |
| `blend` | `normal` | `normal` or `screen`. Prefer normal with pre-multiplied alpha art; `screen` is for glows. |

Hearth overscans each moving layer by the furthest it can travel, so motion
never reveals an edge: a layer that moves and does not wrap travels up to
`sway.amplitude` x `parallax` + `drift.x` dp, and is drawn that much wider on
each side (left edge at minus the travel), its height scaled with it and its
top edge still at `y`. The overscan applies at rest too, so the picture does
not jump when motion starts or stops. A wrapping layer is drawn 960 dp wide
and repeated. Art for a moving layer can be authored with the overscan in
mind, as the bundled packs do, so it lands exactly where it was drawn. Motion is slow by design; think Roku's backgrounds,
not a screensaver. Periods under 20 seconds and drifts over 120 dp will read
as motion graphics and should not be used.

### Motion math

`t` is seconds since the theme appeared. Positive `x` moves right.

| Quantity | Value at time `t` |
|---|---|
| Sway offset | `amplitude` x sin(2 pi t / `period`) x `parallax` |
| Drift offset, no wrap | `x` x sin(2 pi t / `period`) |
| Drift offset, wrap | `x` x t / `period`, modulo the layer's drawn width |
| Opacity | midpoint + (`max` - `min`) / 2 x sin(2 pi (t / `period` + `phase`)): the midpoint (the rest state) at t = 0 with phase 0, `max` with phase 0.25 |
| Fade | `from` + (`to` - `from`) x smoothstep((t - `delay`) / `duration`), clamped to 0..1 |
| Layer alpha | opacity x fade |

Layers composite first to last with source-over (`normal`) or `screen`.
`scripts/theme-compose.py` implements exactly this and is the reference.

### Rest state

With Reduce motion on, and in the gallery's preview images, a theme is drawn
at rest: no sway, no drift, `opacity` at the midpoint of `min` and `max`,
and `fade` at its `to` value. `preview.webp` should show the rest state.

## Validation and fallback

A pack that fails any check is not shown in the gallery, and Settings lists
it with the reason ("missing `theme.json`", "layer `sky.webp` not found",
"decoded layers exceed 32 MB", "id `dusk` already used by a bundled theme",
and so on). If the chosen theme fails to load at launch, Hearth shows the
bundled Dusk; if Dusk itself cannot load, a solid window color with Dusk's
palette. The launcher never shows nothing.

## Theme selection and recommendations

Theme packs do not implement scheduling. They only offer optional `dayparts`
recommendations. Appearance provides three modes:

- Single Theme keeps one chosen pack active.
- Follow the Day uses the user's named periods, boundaries, assignments, and
  per-period shuffle choices. One boundary is the next period's start, so the
  schedule covers every local minute without gaps or overlaps.
- Surprise Me chooses from the enabled packs once per local day. Its empty stored
  enabled set means the implicit pool of usable packs with `autoRotationEligible`
  true, not every installed pack. In that state the gallery marks only those
  packs Included. The first toggle materializes that pool and changes the chosen
  card. Every customized pool stays explicit, even if it includes every pack,
  so future installs cannot silently extend it. At least one choice is retained.
  Existing non-empty saved pools are preserved, including unavailable pack ids.

Special themes cover a particular space or occasion. Open Bay, Harvest Evening,
Christmas, New Year, Easter and July Fourth follow the same opt-in convention, using
`autoRotationEligible: false` and no default daypart recommendations. This uses
the existing manifest and gallery without a separate scheduler.

These packs are bundled with `autoRotationEligible: false` and no daypart
recommendations. They appear in the normal gallery and work in Single Theme.
They are absent from the recommended Follow the Day schedule and Surprise Me's
implicit pool. Selecting a card in Surprise Me, or assigning it to a schedule
period, intentionally opts it in. Eligibility is generic pack metadata; the
launcher has no special case for these ids. Old manifests without the field retain
their existing rotation behavior. The field must be a JSON boolean.

The recommended Follow the Day schedule is Morning from 05:00, Daytime from
09:00, Evening from 17:00, and Night from 20:30. Its pools are Morning Glow
and Misty Forest; Sunlit Valley and Golden Prairie; Dusk and Desert Twilight;
and Aurora. These are editable defaults. Users may assign any pack to any
period regardless of its manifest recommendations.

A shuffled choice is persisted for the full period occurrence, so leaving
Hearth, restarting the app, or rebooting the TV does not reroll it. A new
occurrence avoids the previous choice when another assigned pack is usable.
Missing or invalid packs are skipped. An empty usable pool falls back to Dusk.
Scheduling uses the TV's local clock and timezone, reevaluates on resume and
clock changes, and waits only for the next boundary while Hearth is in front.
It uses no location, weather, or network.

The old stored theme id `auto` migrates to Follow the Day.

The optional Home label is a launcher preference, independent of packs. Do not
embed names, slogans or personalized text in artwork. Leave calm space at the
upper-right safe margin for a short title with the pack's ink and glyph shadow.

## Making the art

The bundled packs are built from SVG sources in `design/art/<id>/`: the
pack's `theme.json` plus one SVG per layer named `NN-<name>.svg`, drawn in
`NN` order, whose image is `<name>.webp`. Each SVG is authored in the
960x540 dp canvas's coordinates; its `width` and `height` give the image's
size at 1x and its `viewBox` the part of the canvas it covers (a strip, or
the canvas widened by the layer's overscan). `scripts/render-theme.sh <id>`
renders each one with headless Chrome at 2x (or 1x for soft layers marked
`data-scale="1"`, which become 960 px wide images), encodes them with
`scripts/encode-art.py`, copies the pack into
`app/src/main/assets/themes/<id>/`, writes `preview.webp` (the rest state,
composited by `scripts/theme-compose.py`) and runs `scripts/banding-check.py`
on the base. Base and other opaque layers get the same fine grain as the
original wallpapers so gradients do not band (`docs/display.md`); overlay
layers are lossless with alpha, with the grain on their partial alpha too.
Dusk and Aurora were split from the single-file art in `design/art/dusk.svg`
and `design/art/aurora.svg`, which stay as the reference: when that file
exists, the script also renders it and reports how far the pack's rest
state is from it.

```bash
PYTHON=<python with Pillow and numpy> scripts/render-theme.sh misty-forest
# with REVIEW_DIR set, also writes REVIEW_DIR/<id>/rest.png and contact-sheet.png
```

`scripts/theme-compose.py <pack-dir>` checks any pack against this page,
prints each layer's decoded and file size, and writes its preview;
`--frames N --seconds S --out DIR` renders N frames of the motion and
`--contact-sheet PATH` puts them on one sheet, to check the motion on a
computer before trying it on a TV. Add `--start-seconds 600 --seconds 30`
to check sustained motion after the introductory fades have finished.

Open Bay's base SVG embeds its original raster painting losslessly, preserving
brushwork through the same Chrome, SVG and WebP pipeline. The two atmospheric
SVG overlays remain separately editable. Its source notes and generation prompt
are in `design/art/open-bay/`; regeneration is entirely offline and does not
require image generation. Structural depth is painted into the base, with no
structural parallax; only haze and sparse dust change. Three layers decode to
11.9 MiB, well below the 32 MB limit.

A pack made any other way is fine as
long as the manifest and images follow this page.
