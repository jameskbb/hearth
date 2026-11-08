# Theme packs

A theme is a folder: one manifest, `theme.json`, and the images it names.
Hearth ships fourteen packs inside the APK (`app/src/main/assets/themes/`) and
loads extra packs from a folder on the device. Nothing in the launcher's UI
code knows a theme by name; the gallery in Settings, Appearance lists whatever
packs load.

Decision records: [0006](decisions/0006-theme-packs.md),
[0008](decisions/0008-follow-the-day-and-theme-rotation.md) and
[0013](decisions/0013-weather-scenes.md).

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
| `weather` | no | Up to 24 weather variants, in match order (see Weather). Older Hearth builds ignore it, so a pack with weather stays `format: 1`. |

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
| `fall` | none | `y` in dp (more than 0, at most 540) and `period` in seconds. The layer repeats top to bottom and moves down `y` dp every period, continuously: falling snow or rain. Combine with `wrap` for an image that also tiles left to right. With Reduce motion or Hearth in the background it holds still, so author it soft enough to read as a texture when still. |

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
| Fall offset | `y` x t / `period` downward; the tiles start at (layer `y` + fall offset), modulo the layer's drawn height, so they always cover the canvas from top to bottom |
| Opacity | midpoint + (`max` - `min`) / 2 x sin(2 pi (t / `period` + `phase`)): the midpoint (the rest state) at t = 0 with phase 0, `max` with phase 0.25 |
| Fade | `from` + (`to` - `from`) x smoothstep((t - `delay`) / `duration`), clamped to 0..1 |
| Layer alpha | opacity x fade |

Layers composite first to last with source-over (`normal`) or `screen`.
`scripts/theme-compose.py` implements exactly this and is the reference.

### Rest state

With Reduce motion on, and in the gallery's preview images, a theme is drawn
at rest: no sway, no drift, no fall, `opacity` at the midpoint of `min` and
`max`, and `fade` at its `to` value. `preview.webp` should show the rest
state.

## Weather

A pack can change with the weather
([decision 0013](decisions/0013-weather-scenes.md)). This needs the user to
have weather on in Settings (it is off by default) and Weather in scenes on
(the default once weather is on). Without either, and in any Hearth build
older than this feature, the pack draws its plain layers exactly as before.

### What Hearth matches on

Hearth reduces the weather report to a mood of three parts:

| Part | Values | From |
|---|---|---|
| `sky` | `clear`, `cloudy`, `fog`, `rain`, `snow`, `storm` | The current condition: clear and mostly clear are `clear`; partly cloudy and cloudy are `cloudy`; drizzle, freezing drizzle, rain, freezing rain and showers are `rain`; snow and snow showers are `snow`; thunderstorms are `storm`. An unknown code is `clear`. |
| `temp` | `freezing`, `cold`, `mild`, `warm`, `hot` | The current temperature: `freezing` at or below 0 C, `cold` below 7 C (about 45 F), `mild` below 18 C, `warm` below 27 C, `hot` from 27 C. A band holds for 1 degree past each edge, so a reading on an edge does not flip back and forth. |
| `forecast` | `snow`, `rain`, or none | Today's overall forecast code, when it has snow (or rain or storms) that the current sky does not show yet. |

The mood changes only when a new report arrives (at most every 30 minutes)
or a setting changes, and a new mood replaces the one on screen only after
that one has been up for 45 minutes. Turning weather off, or the report
going stale, returns to the plain scene at once.

### The `weather` array

Each entry is a variant:

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | 1 to 32 characters of `a-z`, `0-9` and `-`, unique in the pack. Named in errors. |
| `when` | no | Any of `sky`, `temp` and `forecast`, each a non-empty list of the values above (`forecast` also takes `none`: nothing on the way). A missing part matches anything; a variant without `when` matches every mood. |
| `palette` | no | Any of `window`, `ink`, `inkMuted`, `shade` and `shadeMid`. Only the colors listed change; the others stay as the base resolves them, so give `inkMuted` or `shadeMid` too if they should follow a new `ink` or `window`. `accent` and `glow` are each pack's identity and are rejected here. |
| `replace` | no | Base layer image to a new image: `{ "sky.webp": "sky-snow.webp" }`. The layer keeps its placement and motion, unless an `add` entry names the new image; then that entry's fields are used in the base layer's place (and its `after` is ignored). At most one replacement may be a full-size (1920 px wide) image. |
| `hide` | no | Base layer images left out, as a list. An image cannot be both hidden and replaced. |
| `add` | no | Extra layers, with every field of a layer plus `after`: the base layer image to insert just after (in the order listed, even when that base layer is hidden). Without `after`, on top. |

`replace`, `hide` and `after` name base layer images; anything else is
rejected.

### Matching and fallback

The first entry, in order, whose `when` matches the mood wins, so list the
most specific entries first. If none matches, Hearth tries again with the
sky widened, keeping temperature and forecast: `storm` then `rain` then
`cloudy`; `fog` then `cloudy`; `snow` then `cloudy`. If still nothing
matches, the plain scene shows. Falling back to the plain scene is always
correct: a pack may leave out any pairing that would not be true to its
place.

When a shuffled Follow the Day period or Surprise Me makes its new pick, it
prefers packs in the user's own pool that have a variant matching the mood
exactly (without the widening). Holiday overrides, Single Theme and fixed
periods are unchanged. A pick is never rerolled because the weather changed;
the chosen pack's variants follow the weather for the rest of the
occurrence.

### Rules for variant art

- Every image a variant names must exist and pass the same checks as other
  layers (at most 1920 px wide; a replaced first layer must still be the
  opaque 1920x1080 base).
- The resolved layer list (base layers after `replace`, `hide` and `add`)
  must have 1 to 10 layers.
- The 32 MB decoded budget applies to the base and separately to each
  variant's resolved set, since only one set is decoded at a time. Images a
  variant hides or replaces do not count toward its set.
- Each variant's palette must keep text as readable as the base: ink and
  muted ink at least 4.5:1 against the window color, and the same text
  checks over the art as the base (`docs/design.md`, Text over artwork).
- A bad variant rejects the whole pack, with a reason that names it
  ("weather variant snow: image sky-snow.webp not found").
- Lift, never lower: overcast is pearl and silver with light still in it,
  rain is fresh with glints and warm windows, snow is bright, soft and
  quiet, fog is luminous, freezing is crisp blue, hot is golden. Keep the
  warm accent lights.
- Keep variants small: soft overlays at half resolution, partial-height
  strips and palette shifts. Bundled packs budget about 2.5 MB of encoded
  variant art per everyday pack and 1 MB per occasion pack.

On a variant change while Home is visible, Hearth crossfades over about
4 seconds (at once under Reduce motion), decoding only the images the new
set does not share with the old one.

### A worked example

A valley pack whose base is `sky.webp`, `haze.webp` and `valley.webp`:

```json
"weather": [
  {
    "id": "snow",
    "when": { "sky": ["snow"], "temp": ["freezing", "cold"] },
    "palette": { "window": "#FF0C2236" },
    "replace": { "sky.webp": "sky-snow.webp" },
    "hide": ["haze.webp"],
    "add": [
      { "image": "snow-ground.webp", "y": 300, "after": "valley.webp" },
      { "image": "snowfall.webp", "wrap": true, "fall": { "y": 40, "period": 60 } }
    ]
  },
  {
    "id": "snow-coming",
    "when": { "sky": ["clear", "cloudy"], "forecast": ["snow"] },
    "add": [{ "image": "snow-clouds.webp", "after": "sky.webp", "wrap": true,
              "drift": { "x": 30, "period": 120 } }]
  },
  {
    "id": "rain",
    "when": { "sky": ["rain"] },
    "replace": { "sky.webp": "sky-overcast.webp" },
    "add": [{ "image": "rain.webp", "fall": { "y": 120, "period": 30 } }]
  },
  {
    "id": "overcast",
    "when": { "sky": ["cloudy"], "forecast": ["none"] },
    "replace": { "sky.webp": "sky-overcast.webp" }
  }
]
```

What shows:

| Weather | Mood | Variant | Layers drawn |
|---|---|---|---|
| Snowing, -3 C | snow, freezing | `snow` | `sky-snow`, `valley`, `snow-ground`, `snowfall` |
| Cloudy, 2 C, snow forecast | cloudy, cold, snow | `snow-coming` | `sky`, `snow-clouds`, `haze`, `valley` |
| Thunderstorm, 15 C | storm, mild | `rain` (storm widened to rain) | `sky-overcast`, `haze`, `valley`, `rain` |
| Fog, 9 C | fog, mild | `overcast` (fog widened to cloudy) | `sky-overcast`, `haze`, `valley` |
| Snow showers, 9 C | snow, mild | `overcast` (`snow` wants freezing or cold; widened to cloudy) | `sky-overcast`, `haze`, `valley` |
| Clear, 20 C | clear, warm | none | the plain scene |

`scripts/theme-compose.py <pack> --all-weather` writes each variant's rest
state for review; `--weather snow` composes one variant with any of the
other options. The same pack, with these images given only as sizes, is the
test fixture in `app/src/test/resources/themes/weather-fixture/`.

## Validation and fallback

A pack that fails any check is not shown in the gallery, and Settings lists
it with the reason ("missing `theme.json`", "layer `sky.webp` not found",
"decoded layers exceed 32 MB", "id `dusk` already used by a bundled theme",
"weather variant `snow`: image `sky-snow.webp` not found", and so on). If the chosen theme fails to load at launch, Hearth shows the
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
the existing manifest and gallery for manual choices and rotation pools.

These packs are bundled with `autoRotationEligible: false` and no daypart
recommendations. They appear in the normal gallery and work in Single Theme.
They are absent from the recommended Follow the Day schedule and Surprise Me's
implicit pool. Selecting a card in Surprise Me, or assigning it to a schedule
period, intentionally opts it in. Eligibility is generic pack metadata; the
rotation logic has no special case for these ids. Old manifests without the field retain
their existing rotation behavior. The field must be a JSON boolean.

The separate Holiday themes setting is on by default, including on upgrades.
It maps New Year's Day, Western Easter Sunday, July Fourth, Halloween and
Christmas Day to the matching bundled ids, independently of rotation
eligibility. Overrides last one local calendar day and preserve the saved
mode and pools. Missing or broken holiday packs leave the chosen mode in
charge. No new manifest fields or network access are involved.

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
With weather off it uses no location, weather, or network. With weather on,
only a new shuffled pick reads the weather, preferring packs in the pool with
a matching variant (see Weather).

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
# with REVIEW_DIR set, also writes REVIEW_DIR/<id>/rest.png, contact-sheet.png
# and weather-<variant>.webp for each weather variant
```

Weather variant art is drawn in the same folder, by the same hand, on the
same horizon and composition, with source numbers from 10 up:
`10-sky-snow.svg` gives `sky-snow.webp`. Numbers below 10 are the base
layers, and the first source is always the base. A full-size opaque variant
image (a replacement sky) also goes through `scripts/banding-check.py`.
Variant notes go in the pack's `notes.txt`, one line or more per variant id.

`scripts/theme-compose.py <pack-dir>` checks any pack against this page,
prints each layer's decoded and file size, and writes its preview;
`--frames N --seconds S --out DIR` renders N frames of the motion and
`--contact-sheet PATH` puts them on one sheet, to check the motion on a
computer before trying it on a TV. Add `--start-seconds 600 --seconds 30`
to check sustained motion after the introductory fades have finished.
`--weather ID` composes a weather variant instead of the plain scene (for
`--rest`, `--compare` and `--frames`; no preview is written unless
`--preview` is given), and `--all-weather` checks every variant and writes
each one's rest state to `weather-<id>.webp` in `--review-dir` (default
`build/weather-review/<pack id>`), never into the pack.

Open Bay's base SVG embeds its original raster painting losslessly, preserving
brushwork through the same Chrome, SVG and WebP pipeline. The two atmospheric
SVG overlays remain separately editable. Its source notes and generation prompt
are in `design/art/open-bay/`; regeneration is entirely offline and does not
require image generation. Structural depth is painted into the base, with no
structural parallax; only haze and sparse dust change. Three layers decode to
11.9 MiB, well below the 32 MB limit.

A pack made any other way is fine as
long as the manifest and images follow this page.
