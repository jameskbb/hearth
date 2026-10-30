# 0011: Broader Android compatibility with CI framework tests

**Status:** Accepted, 2026-10-26. Extends [0010](0010-android-7-compatibility.md)
at the maintainer's request for more Android versions and CI-only validation.

## Decision

Lower minSdk from 25 to 21, the first Android TV API level. Keep targetSdk 34,
compileSdk 35 and the pinned production dependencies. The same application id
and signing rules continue, with version 0.1.2 / code 3. This broadens APK
installation eligibility to Android 5.0, 5.1, 6.0 and 7.0, including Fire OS 5.

Use string-based display and TV-input service lookup before API 23. Read
display modes only from API 23 and HDR capabilities only from API 24. Older
platforms report no unsupported capability list; do not invent panel facts.
Read custom platform input labels only from API 24. Keep the existing
pre-26 static fonts and core-library Java time desugaring. Clock and weather
read the legacy configuration locale before API 24, with LocaleList after it.
Adaptive icon vectors are qualified to API 26; older launchers retain the
existing PNG icons.

Run framework unit tests in CI for every API from 21 through 36. Robolectric
4.14.1 supports the API 21/22 lane; 4.16 supports the remaining lanes. Both
are test-only, pinned dependencies. Test jobs use JDK 21 for API 36; production
builds retain JDK 17. The ordinary JVM suite remains separate. CI rejects
missing runtime evidence, empty test suites, skipped tests and failed tests.

Also inspect debug and minified release APK metadata for minSdk 21 and
targetSdk 34, retain the legacy font resources, and check that the real debug
DEX includes desugared Java time. Framework tests on a host JDK cannot prove
that Android's ART executes desugared code, so this artifact check complements
the runtime matrix and Android lint.

## Limits

Robolectric is an Android framework sandbox, not an Android TV image. These
tests exercise our API branches, package wrappers, settings/input intents,
receiver lifecycle, font-resource selection, persisted preferences and the
platform JSON parser. Activity metadata uses the stable integer-flags API;
other package wrappers retain the typed API 33+ paths. These tests
do not establish GPU rendering, D-pad timing, vendor permissions, firmware
behavior, reboot behavior, TLS provider support, or Home-button redirection.
No broader physical-device certification is claimed. An installation minimum
and a target API are different things; this does not opt into a newer target's
behavior changes or guarantee an untested future API.

The maintainer requested CI execution, so no local builds, tests or emulator
runs are part of this change. CI runs in the existing private repository;
this work creates no public release or public repository.

## Sources

- [Robolectric SDK and Java compatibility](https://robolectric.org/compatibility_table/)
- [Robolectric SDK configuration](https://robolectric.org/configuring/)
- [Display API availability](https://developer.android.com/reference/android/view/Display)
- [TV input labels](https://developer.android.com/reference/android/media/tv/TvInputInfo#loadCustomLabel(android.content.Context))
