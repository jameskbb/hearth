# 0003: One landscape through the day, with an Automatic wallpaper option

**Status:** Superseded by decisions 0006 and 0008.

## Context
The maintainer wants the wallpaper to follow the time of day: one landscape
seen in the morning, the day, the evening, and at night, keeping its
composition while the sky, light, and land colors change. The existing Dusk
scene becomes the evening version.

## How it fits what exists
The wallpaper system is a registry (`ui.theme.Wallpapers`): each entry is an
id, a label, one image, and its own palette (ink, accent, glow, shades,
window). The preference stores only the id. That makes this an addition, not
a redesign:

- **Art:** three more variants of `design/art/dusk.svg` (morning, day, night)
  sharing its ridgelines and horizon, rendered by the existing
  `scripts/render-art.sh` and `scripts/encode-art.py`. Night gets the
  quietest palette: a darker sky, a moon, sparse stars, lower-contrast land.
  Quieter means darker art and softer accents only; never the device's
  brightness or picture settings.
- **Registry:** four scene entries (Morning, Day, Evening = Dusk, Night) plus
  Aurora, and one more choice, Automatic, stored as its own id.
- **Schedule:** a pure function from local time to scene, unit-tested
  (initially fixed hours, e.g. morning 05:00, day 09:00, evening 17:00, night
  20:30). No location permission, weather, or network.
- **Clock source:** the scene is computed at launch and on every resume
  (covers waking), and re-evaluated at the next boundary with a single delay,
  plus `ACTION_TIME_CHANGED` and `ACTION_TIMEZONE_CHANGED`. No ticking loop.
- **Transition:** when Home is visible at a boundary, a single short
  crossfade between two decoded images; with Reduce motion on, an instant
  switch. Nothing animates continuously. The current one-slot image cache
  holds both images only during the fade.
- **Unchanged:** layout, tile positions, focus treatment, navigation. Each
  scene's palette must keep text readable through localized shading alone.

## Decision (proposed)
Build it as the first enhancement after the v0.1 device test. It touches
art, the registry, one settings row, and a small scheduler; it does not
touch app discovery, launching, focus, or navigation.

## Consequences
- At the shipped encoding (lossless WebP plus grain, about 1.3 MB per
  1920x1080 wallpaper; see `docs/display.md`), four scenes take about 5.2 MB,
  and the three new ones add about 4 MB to the APK, taking a release build
  from about 5 MB today to about 9 MB. Lossy encoding would be far smaller but brings the
  banding back, so the size is the accepted cost of clean gradients; if it
  matters, the first lever is dropping or merging scenes, not lossy WebP.
- Decoded, each scene is about 8 MB as an ARGB bitmap whatever its encoding;
  only one is held at a time, two during a crossfade.
- Each scene's palette gets a readability check on the real panel as part of
  the device checklist.
