# 0013: Weather scenes

**Status:** Accepted, 2026-11-08. Amends decision 0008 (scheduling may now use
weather when the user has turned weather on) and extends decision 0006 (the
pack format gains an optional `weather` block, still `format: 1`).

## Context

The maintainer wants Home to feel like an extension of the room's real
surroundings, not only a picture that follows the clock. A freezing clear
morning, a rainy afternoon, a warm golden evening and a day with snow on the
way should each look like themselves. Hard rules from the maintainer:

- Every weather look is optimistic and uplifting. Cold, gray, wet or snowy
  never means dark, bleak or depressing. A winter morning can be crisp,
  pearl-bright and cozy; rain can be fresh and glistening.
- Hearth keeps its own look: layered original scenery, fine grain, warm light,
  the same type, layout and focus treatment.
- Weather stays optional. With weather off, Hearth behaves exactly as today.
- When the user turns weather on, weather scenes are on by default, with one
  setting to turn them off.

A three-judge review (art direction, engineering, living with it) chose
unanimously to transform the scene the user already has rather than switch to
a separate weather scene. The maintainer then asked that pack selection also
use the weather, not only the look of the chosen pack.

## Decision

### 1. Scene weather: a small, pure mood

A new pure function in `theme` (`SceneWeather`) turns the current
`WeatherGlance` plus today's forecast into a `SceneMood`, or null when
weather is off, has no place, is stale (decision 0005), or weather scenes are
turned off.

`SceneMood` has three parts:

| Part | Values | From |
|---|---|---|
| `sky` | `clear`, `cloudy`, `fog`, `rain`, `snow`, `storm` | current WMO condition |
| `temp` | `freezing`, `cold`, `mild`, `warm`, `hot` | current temperature |
| `forecast` | `snow`, `rain`, or none | today's daily WMO code |

- `sky`: Clear and MostlyClear are `clear`; PartlyCloudy and Cloudy are
  `cloudy`; Fog is `fog`; Drizzle, FreezingDrizzle, Rain, HeavyRain,
  FreezingRain, Showers and HeavyShowers are `rain`; Snow, HeavySnow and
  SnowShowers are `snow`; Thunderstorm and ThunderstormHail are `storm`. An
  unknown code gives `clear` for the sky (the temperature still counts).
- `temp` thresholds in Celsius, with 1 degree of hysteresis around each edge
  against the previous mood: `freezing` at or below 0, `cold` below 7 (about
  45 F, so a 40 F morning is cold), `mild` below 18, `warm` below 27, `hot`
  at 27 and above.
- `forecast`: Open-Meteo's daily `weather_code` for today is added to the
  existing forecast request (same endpoint, same coordinates, nothing new is
  sent). A snow code gives `snow`, a rain or storm code gives `rain`, when it
  differs from the current sky. This is how "snow in the forecast" shows
  before the first flake.
- Stability: the mood is recomputed only when a new report arrives (at most
  every 30 minutes) or when weather, its place or the scenes setting changes.
  A new non-null mood replaces a previous non-null mood only after the
  previous one has been shown for at least 45 minutes, so the scene does not
  flicker between two states. Turning weather off, or the report going stale,
  returns to the plain scene at once.

### 2. Pack format: optional weather variants

A pack may add a top-level `weather` array. Older Hearth builds ignore it
(unknown fields are ignored), so packs stay `format: 1` and old packs need no
change. Each entry:

```json
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
}
```

- `id`: 1 to 32 characters of `a-z`, `0-9`, `-`, unique in the pack.
- `when`: any of `sky`, `temp`, `forecast`, each a non-empty list. A missing
  part matches anything. The first entry, in order, that matches the mood
  wins, so authors list the most specific entries first.
- If no entry matches, Hearth retries with the sky widened along
  `storm -> rain -> cloudy`, `fog -> cloudy`, `snow -> cloudy` (temperature
  and forecast unchanged). If still nothing matches, the plain scene shows.
- `palette`: a partial palette. `ink`, `inkMuted`, `shade`, `shadeMid` and
  `window` may change; `accent` and `glow` may not (the warm accent is part
  of every pack's identity), and the codec rejects them here.
- `replace`: base layer image to a new image, keeping that layer's placement
  and motion unless the variant also gives a full layer under `add`. At most
  one replaced image may be full size (1920 px wide).
- `hide`: base layer images left out in this variant.
- `add`: extra layers with the normal layer fields plus `after` (insert after
  that base image; default on top).
- New motion field for any layer, `fall`: `{ "y": dp, "period": s }`. A
  falling layer wraps vertically: it is drawn tiled top to bottom and moves
  down `y` dp every `period` seconds. Its rest state is no offset. With
  Reduce motion or Hearth in the background it does not move, so falling snow
  and rain hold still as a gentle texture; authors keep them soft enough to
  read that way.
- Validation (`ThemeValidation`): every variant image must exist and pass the
  same size checks as other layers; the resolved layer list of any variant
  must have 1 to 10 layers; the decoded budget (32 MB) applies to the base
  and separately to each variant's resolved layer set, since only one set is
  decoded at a time. A bad variant is a `ThemeProblem` that names it, and
  rejects the pack like any other bad field.
- `theme-compose.py` gains `--weather ID` (compose that variant) and
  `--all-weather` (a preview per variant, written to `weather-<id>.webp` in a
  review directory, not shipped). `render-theme.sh` renders variant SVGs the
  same way as base layers: they use numbers from 10 up (`10-sky-snow.svg`
  gives `sky-snow.webp`).

### 3. Selection uses the weather

- Holiday overrides and Single Theme are unchanged: weather only changes the
  look of the chosen pack.
- When a shuffled Follow the Day period or Surprise Me makes its
  once-per-occurrence pick and a mood is known, it prefers packs in the
  user's own eligible pool that have a variant matching the mood exactly
  (without the sky widening), still avoiding the previous pick. If none
  match, it picks as today. It never picks outside the pool and never rerolls
  during an occurrence because the weather changed; the chosen pack's own
  variants follow the weather for the rest of the occurrence.
- Fixed (unshuffled) periods keep their first usable pack.
- `ThemeInfo` exposes the set of moods a pack has variants for, computed from
  the manifest, so selection does not need decoded art.

### 4. Rendering

- The renderer receives a resolved pack id and variant id (or none).
  `ThemeCatalog.load` takes the variant and returns the resolved layers and
  palette; decoded bitmaps shared between the old and new layer set are
  reused rather than decoded twice.
- A variant change while Home is visible uses the existing theme crossfade,
  slowed to about 4 seconds; with Reduce motion, an instant switch. Nothing
  else animates because of weather.
- Text readability is unchanged: each variant's palette must pass the same
  4.5:1 contrast checks as the base (`docs/design.md`).

### 5. Settings

- Weather settings gain one row, "Weather in scenes", shown only while
  weather is on. Default on. It is a preference (`weatherScenes`), stored like
  the other appearance settings.
- Appearance's theme gallery is unchanged. A pack's detail text may say that
  it changes with the weather.

### 6. Art

- Every bundled pack gets weather variants drawn from its own SVG sources in
  `design/art/<id>/`, by the same hand, on the same horizon and composition.
  Variant notes go in the pack's `notes.txt`.
- Everyday packs (Morning Glow, Misty Forest, Sunlit Valley, Golden Prairie,
  Dusk, Desert Twilight, Aurora, Open Bay) aim for: soft bright overcast,
  rain-washed, falling snow with snow on the ground, snow on the way, fog,
  freezing clear (frost, crisp air), cold clear, and hot clear (golden haze).
  A pack may leave out pairings that would not be true to its place; falling
  back to the plain scene is always correct.
- Occasion packs (Harvest Evening, Halloween, Christmas, New Year, Easter,
  July Fourth) get the variants that suit their season, at least overcast and
  one precipitation look.
- Art direction for every variant: lift, never lower. Overcast is pearl and
  silver with light still in it; rain is fresh with glints and warm windows;
  snow is bright, soft and quiet; fog is luminous; freezing is crisp blue and
  sparkling; hot is golden, not washed out. Warm accent lights stay.
- Size: variants are mostly soft overlays at half resolution
  (`data-scale="1"`), partial-height strips and palette shifts. Budget: about
  2.5 MB of encoded variant art per everyday pack and 1 MB per occasion pack,
  so the APK grows by about 26 MB at most.

## Consequences

- Decision 0008's sentence that scheduling "uses no location, weather, or
  network data" now holds only while weather is off. With weather on, only
  the moment of a new shuffled pick reads the mood; persistence and
  occurrence keys are unchanged.
- Each new variant is reviewed for banding (`scripts/banding-check.py`),
  contrast and the uplifting direction before it ships, and appears in the
  screenshot workflow.
- Third-party pack authors can add weather to their packs without changing
  their format version; packs without a `weather` block render as before.
