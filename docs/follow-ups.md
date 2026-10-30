# Follow-ups

Known work after v0.1, from the readiness review (2026-10-21) and planned
enhancements. Settled design choices live in `docs/decisions/`; this is only
the list of what is not done yet.

## Next

1. **Extend the README tour to weather and schedule editing.** The current
   tour shows the latest interface with local service-name demos, launch and
   Back, All apps, Settings, and Appearance. A future extended tour can show
   Home with weather beside the clock and choosing a city
   ([decision 0005](decisions/0005-opt-in-weather.md)), all thirteen theme packs,
   and editing the three Appearance modes
   ([decisions 0006](decisions/0006-theme-packs.md) and
   [0008](decisions/0008-follow-the-day-and-theme-rotation.md)).
2. **Complete the physical Fire TV checklist for this evolution.** The current
   launcher is in daily device use, but the new Follow the Day boundaries,
   stable rotation across restart, timezone changes, live pack replacement,
   three bright scenes, Open Bay, the seasonal scenes, the Home label with weather,
   frame pacing, memory, banding, and focus paths still need the explicit checks
   in [`testing.md`](testing.md).

## Non-blocking issues

- When the row scrolls to its last tiles, the trailing tile can sit up to
  about 24 px past the 48 dp margin, and the first tile is cut at the left
  edge. Cosmetic.
- Eight lint warnings expected of a TV launcher remain unsuppressed (fixed
  orientation, `resizeableActivity`, `allowBackup` without data extraction
  rules, no monochrome icon layer, an unneeded `mipmap-anydpi-v26` folder,
  targetSdk 34).
- The launch-failure copy paths cannot be exercised with the current fixtures;
  add a fixture that refuses to start.
