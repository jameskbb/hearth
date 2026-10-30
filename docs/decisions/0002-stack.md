# 0002: Kotlin, Compose for TV, pinned to API 28-compatible releases

**Status:** Accepted, 2026-10-21. Minimum API requirement superseded by
[0010: Android 7.1 compatibility](0010-android-7-compatibility.md).

## Decision
Kotlin 2.0.21, AGP 8.7.3, Compose BOM 2024.12.01, androidx.tv:tv-material
1.0.0, DataStore Preferences 1.1.1. minSdk 28, targetSdk 34, compileSdk 35.
One app module. Dependencies wired by hand in `AppContainer`.

## Why
The primary target is Fire OS 7 (Android 9). These releases are stable and
support API 28; newer releases were not adopted without checking their minSdk
and on-device behavior. Compose for TV provides focus-aware TV components.
The target hardware has about 2 GB of RAM, so the app avoids heavy frameworks
and keeps artwork at tile size.

## Consequences
- `<queries>` limits package visibility to launchable apps; no
  QUERY_ALL_PACKAGES.
- Release builds use R8 with resource shrinking.
