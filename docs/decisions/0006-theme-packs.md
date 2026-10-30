# 0006: Wallpapers become theme packs, with gentle motion

**Status:** Accepted, 2026-10-22

## Context
The wallpaper registry (`ui.theme.Wallpapers`) held two entries, Dusk and
Aurora, each one static image and a palette, with the Kotlin code as the
only place to add one. The maintainer wants themes to be a first-class
feature: self-contained packs that anyone can drop into the project or push
to a TV, two new scenes (Misty Forest, Desert Twilight), a gallery in
Settings, an Auto option, and subtle motion in the art, in the manner of
Roku's backgrounds.

The brief's "no constant motion" line was written for the interface, not the
art: tiles, text and focus still move only in response to the user. The art
may breathe. Reduce motion turns every theme into a still image.

## Decision
- A theme is a folder with `theme.json` and images, specified in
  `docs/theme-packs.md`: a palette, a preview, up to eight image layers, and
  per-layer motion (parallax sway, drift, opacity breathing, one-time fades).
  Bundled packs live in `assets/themes/`; installed packs in Hearth's
  app-specific external storage, as folders or zips.
- A small renderer (`ui.theme` and `ui.components`) draws the layers on one
  canvas from a frame clock capped at 30 frames per second, only while Hearth
  is started and only when Reduce motion is off. Decoded layers for the
  current theme are kept; the whole pack is limited to 32 MB decoded.
- Dusk and Aurora become packs with the same ids, so stored preferences
  carry over. Each keeps its current render as the base layer and gains one
  translucent overlay with slow motion (Dusk's lit wisps, Aurora's curtain);
  at rest the composite matches the previous wallpaper.
- Settings gets an Appearance panel: a row of theme cards with previews and
  names. Moving focus across the cards shows that theme live behind the panel
  (the animated full-screen preview is the real renderer), Select keeps it,
  and Back restores the saved one. The original Auto behavior was later
  replaced by the modes and editable schedule in decision 0008.
- Validation never crashes the launcher: a bad pack is listed with its
  reason, and the chosen theme falls back to Dusk, then to a solid color.

## Consequences
- Four bundled packs at the shipped lossless encoding add about 10 MB to
  the APK (Desert Twilight carries two full skies); overlay layers are
  authored at half resolution or as strips to keep both the APK and the
  decoded footprint small on a 2 GB TV.
- Motion costs a few full-screen bitmap draws per frame at 30 fps on the
  GPU. The device checklist gains an item for frame pacing and memory while
  a theme animates.
- Decision 0003's fixed time-of-day scenes were first superseded by manifest
  `dayparts` plus Auto. Decision 0008 then made `dayparts` recommendations and
  moved the editable schedule into user preferences.
- README captures are generated from the Android TV emulator with the media
  scripts described in `docs/recording-media.md`.
