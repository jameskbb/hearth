# Hearth product brief

What Hearth v1 must be. Decisions made against this brief live in
`docs/decisions/`; the look and behavior are specified in `docs/design.md`.

## Goal

A polished, quiet home screen (launcher) for Fire TV and other Android TV
devices, built as a native Android app. Someone should be able to sit down,
press Home, immediately see what is selected, open an app comfortably with the
remote, and enjoy a screen that feels intentionally designed. Visual quality,
remote navigation, and performance matter equally.

## Target

- Primary: Fire OS 7 TVs (Android 9, API 28), 1080p UI, about 2 GB of RAM,
  remote-only input. A quantum-dot (QLED) panel is the showcase display.
- Also targets Android TV 5.0 / API 21 and later, including Fire OS 5 / API 22
  and Fire OS 6 / API 25. Framework compatibility has CI coverage through API 36;
  physical-device checks remain separate.
  Older Fire OS home-button integration is separate from app compatibility.
- On Fire OS, Amazon's launcher stays the system home. Hearth is opened by a
  Home-button redirect app such as Home on Fire (`docs/home-button.md`).

## Visual direction

- Rich, intentional color against dark surroundings: deep blues, warm amber,
  sunset tones, emerald accents.
- A background with real presence (landscape or aurora, or an elegant abstract
  composition with depth), kept vibrant. Text sits directly on the artwork,
  with restrained glyph shadows for readability. No gradient pools or gray
  backing panels under labels.
- The selected app looks subtly lit: restrained accent, slight enlargement, a
  crisp focus indicator.
- Comfortable in a dark living room: no large bright white areas, no constant
  motion.
- Real app artwork, brand proportions preserved, tasteful fallbacks.
- Avoid: admin-style UI, heavy glass effects, neon outlines, tiny controls,
  giant empty hero areas, fake recommendations, unnecessary widgets.

## v1 scope

1. Favorites on the home screen with persistent ordering.
2. All apps view of the actual launchable apps (TV apps first, others in a
   secondary section, no internal system components).
3. Remote-accessible favorite, reorder, and hide.
4. Restoring hidden apps.
5. Reliable launching, with a graceful response when an app is gone or
   refuses to open.
6. A small settings screen: appearance, reduced motion, verified routes into
   the device's own settings.
7. Preferences that persist across restarts and reboots.
8. Optional weather beside the clock, off by default, for a city the user
   picks in Settings (added by the owner after the original brief; see
   `docs/decisions/0005-opt-in-weather.md`).

Out of scope for v1: accounts, cloud sync, streaming catalogs, cross-app
continue watching, pretend data, controls that do nothing, and any weather
beyond a glance (forecasts, radar, alerts, location detection).

## Remote interaction

Everything works with D-pad, Select, Back and Menu. Focus is unmistakable from
across the room, movement is predictable, there are no traps or lost focus,
the previous tile and scroll position come back after an app closes, focus
lands sensibly after hide, move, or removal, reordering works without touch,
every long-press has a discoverable alternative, and Back never falls through
to the system launcher from the root screen.

## Engineering

- Kotlin, Jetpack Compose for TV, pinned dependencies, minSdk 21, and API 28
  as the original primary target.
- Clear separation: app discovery, launching, preferences, presentation. No DI
  or architecture frameworks beyond that.
- Offline by design: no internet use unless the user turns on weather, which
  then sends only the chosen city's coordinates to Open-Meteo. No Google Play
  Services, no analytics. Artwork cached, heavy work off the UI thread.
- Do not change global system settings (animation scales, picture settings,
  display modes), disable system packages, or require root.

## Home button and the transition

The redirect app draws the screen shown while Fire OS 7 holds a Home launch.
Investigate what it can be configured to show, prefer an existing setting, and
describe any source change (with its license implications) before making one.
The preferred transition is quiet: a plain dark screen or one consistent with
Hearth's background. Never promise to remove platform delays or the brief
appearance of the system launcher.

## Validation

Emulator: discovery, launching, persistence, focus through navigation and
dialogs and returning from apps, missing artwork, removed apps, readable and
unclipped layout, offline operation. Then the physical TV: Home redirection,
wake and restart, settings access, voice assistant, playback keys. Anything
untested is labeled so; an emulator is not proof of device compatibility.
