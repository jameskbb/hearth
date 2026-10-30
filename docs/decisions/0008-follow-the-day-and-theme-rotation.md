# 0008: Follow the Day and stable theme rotation

**Status:** Accepted, 2026-10-23

## Context

The original Auto choice mapped four fixed dayparts to the first matching
theme manifest. That made a pleasant default, but it could not express a
household's preferred hours, names, theme pools, or rotation style. It also
treated a pack author's recommended dayparts as restrictions instead of
suggestions.

Theme artwork may now be replaced on a TV during pack development. Comparing
only parsed manifest metadata cannot notice a changed image when
`theme.json` stays the same, so decoded art and gallery previews can remain
stale.

## Decision

- Appearance has three modes: Single Theme, Follow the Day, and Surprise Me.
  A stored `auto` choice migrates to Follow the Day; any stored pack choice
  remains the Single Theme choice.
- Follow the Day stores an ordered list of periods. Each period has a stable
  id, a user-facing name, a start minute, assigned theme ids, and its own
  shuffle setting. One period ends when the next starts, with the last ending
  at the first start on the following day. Unique ordered boundaries make
  gaps and overlaps impossible, including across midnight.
- The recommended schedule is Morning at 05:00, Daytime at 09:00, Evening at
  17:00, and Night at 20:30. Its pools are Morning Glow and Misty Forest;
  Sunlit Valley and Golden Prairie; Dusk and Desert Twilight; and Aurora.
  Restoring it replaces the user's period configuration.
- A shuffled period chooses once per local period occurrence and persists
  that choice. It avoids the preceding selection when another eligible pack
  exists. A fixed period uses its first usable assigned pack. Missing or bad
  packs are skipped, and an empty usable pool falls back to Dusk.
- Surprise Me uses the same persisted rotation rule on a local calendar-day
  occurrence. Its enabled pool is independent of Follow the Day. An empty
  pool originally meant every usable theme. The Open Bay feature request
  refines this to usable packs whose optional `autoRotationEligible` is true
  (the backward-compatible default). Explicit pools and period assignments
  remain intentional user choices, including packs that opt out by default.
- Scheduling reads the device's local clock and zone through `java.time`.
  Hearth evaluates on launch, resume, clock or timezone broadcasts, and one
  delay to the next boundary while it is in front. It does not poll and uses
  no location, weather, or network data.
- Manifest `dayparts` remain recommendations for defaults and discovery.
  They never prevent a user from assigning any theme to any period.
- Installed pack discovery computes a content revision from the manifest,
  preview, and referenced layers during refresh. Unchanged revisions retain
  decoded and preview caches. A changed revision invalidates only that pack
  and reloads a selected pack even when its id and manifest are unchanged.

## Consequences

- Periods can be renamed, added, or removed without changing the renderer.
  The renderer receives only the resolved theme id and remains unaware of
  schedules.
- Daylight saving transitions and timezone changes are resolved from local
  boundaries by the platform zone rules. Persisted occurrence keys prevent
  reopening Hearth or restarting the device from rolling a new theme during
  the same occurrence.
- A content-aware installed-pack refresh reads pack bytes but does not decode
  them. This work happens only during catalog refresh, off the main thread,
  and keeps the one-theme decoded cache unchanged when files match.
- Sunrise and sunset boundaries can be added later as another boundary
  provider without changing theme packs or the renderer. This release keeps
  fixed local clock boundaries so scheduling stays offline and predictable.
