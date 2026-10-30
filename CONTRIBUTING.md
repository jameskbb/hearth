# Contributing to Hearth

Thanks for helping make Hearth better. Documentation fixes, bug reports,
compatibility reports, and theme contributions are welcome. For a bug, include
your Hearth version or commit, Android or Fire OS version, steps to reproduce,
and what you expected. Keep device identifiers and household details private.
For sensitive reports, follow [the security policy](SECURITY.md).
Please follow [the community guidelines](CODE_OF_CONDUCT.md).

Keep the home screen calm, quick, and remote-first. Read the
[brief](docs/BRIEF.md), [design spec](docs/design.md), and
[architecture decisions](docs/decisions/) before changing behavior.

## Build and check

Install JDK 17, Python 3, Android SDK platform 35 and build-tools 35.0.0, plus
platform-tools. Set `ANDROID_HOME`, or put `sdk.dir` in an untracked
`local.properties`. Dependencies download from public Maven repositories.

```bash
./gradlew assembleDebug assembleRelease
scripts/check.sh
./gradlew lintDebug
```

Normal builds use committed fonts, artwork, and banners. Theme-art editing
needs the separate tools documented in [theme packs](docs/theme-packs.md).
Unsigned development setup uses the local debug key even for release builds;
use your own signing key for durable installs, as described in
[installing](docs/install.md). Never commit a key or signing password.

## Sending a change

Fork the repository and open a pull request against `main`. Keep changes
focused so they are easy to review. Describe what changed, why, and what you
checked. CI runs on pull requests, including the Android API 21 through 36
framework matrix. Those jobs use JDK 21; ordinary builds use JDK 17.
See [testing](docs/testing.md) for the difference between framework, emulator,
and physical-device evidence.

Dependency versions are pinned. Updates must preserve minSdk 21, static font
fallbacks, and Java API desugaring; a newer version alone is not a reason to
drop older TVs. Review dependency updates explicitly and run the compatibility
matrix before adopting them.

For theme contributions, start with the [gallery](docs/themes.md) and
[pack format](docs/theme-packs.md). Include editable source and only artwork
you have permission to contribute. Record third-party licenses in
`third_party/`.

## Review checklist

- Reuse the current renderer, focus model, and small service interfaces.
- Add unit tests for behavior; use the [device checklist](docs/testing.md)
  for paths that need native rendering or a real TV. State what you did not test.
- Update affected screenshots and their captions in the same change.
  [Media recording](docs/recording-media.md) and [demo apps](docs/demo-apps.md)
  explain the repeatable setup. Regenerate the title card when its embedded
  screenshot changes.
- Keep documentation public-safe: no private device identifiers, machine
  paths, secrets, or household details. Do not modify system settings or
  other apps. No em dashes in source or documentation.
- Keep `CLAUDE.md` and `AGENTS.md` identical when updating project rules.

The project is still v0.1. Discuss substantial new home-screen controls or
network features before implementation; simplicity is a product constraint.
