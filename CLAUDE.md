# Hearth agent rules

`CLAUDE.md` and `AGENTS.md` are the same document under two names so Claude
Code and Codex follow identical rules. Change both together;
`scripts/check.sh` fails if they differ.

## 1. What this is

Hearth is an open-source home screen (launcher) for Fire TV and Android TV,
written in Kotlin with Jetpack Compose for TV. Status: v0.1 in development.

Read first, in order: `docs/BRIEF.md` (what v1 must be), `docs/design.md`
(how it looks and behaves), `docs/decisions/` (settled choices; do not reopen
them without the maintainer).

## 2. This repository is headed for public release

Write everything as if it were already public.
- No private identifiers anywhere: no IP or MAC addresses, hostnames, serial
  numbers, exact household device models, personal names, or absolute paths
  on anyone's machine. Describe targets generically ("a Fire OS 7 TV").
- Device-specific notes, measurements and install logs belong in the
  maintainer's private records, not here.
- No secrets, keystores or signing passwords in the repo (`.gitignore` covers
  `*.jks`, `*.keystore`, `keystore.properties`).
- Agents never make this repository public, create a public repository,
  publish a release, or post project content publicly. The maintainer does
  that by hand.
- Commits carry no AI attribution trailers.
- Third-party material is recorded in `third_party/` with its license.

## 3. Build and test

Requires JDK 17 and the Android SDK (`sdk.dir` in `local.properties`, which is
not committed).

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # JVM unit tests
scripts/check.sh                 # tests + repo consistency + public-safety scan
```

Emulator and device scripts are in `scripts/` (see `docs/testing.md`).

## 4. Code layout

| Package | Owns |
|---|---|
| `apps` | Discovery, artwork loading and caching, launching |
| `prefs` | DataStore persistence and the pure `FavoritesOps` |
| `system` | Verified routes into the platform's settings, display facts |
| `weather` | Opt-in weather: Open-Meteo client and parsing, WMO codes, refresh policy, cache |
| `theme` | Theme packs: manifest codec, validation, discovery, decoding, Auto schedule |
| `ui.theme` | Tokens, fonts, theme motion math |
| `ui.home`, `ui.allapps`, `ui.settings`, `ui.components` | Presentation |

Keep the layers separate: UI talks to the interfaces in `apps`, `prefs`,
`system`, `weather` and `theme`, wired by hand in `AppContainer`. No DI or architecture frameworks.

## 5. Rules

- minSdk 21 (Android TV 5.0, including Fire OS 5 / API 22). Keep Java API desugaring and pre-26
  static font fallbacks working. Check any new dependency's minSdk and keep versions
  pinned in `gradle/libs.versions.toml`.
- Android compatibility tests run in CI across APIs 21 through 36. Keep the legacy
  Robolectric lane for API 21/22 and JDK 21 for API 36 tests; production builds use JDK 17.
- INTERNET is used only by opt-in weather, off by default, with requests only
  to Open-Meteo. No Google Play Services, no analytics.
- Never change global system settings (animation scales, picture settings,
  display modes), and never disable or modify other apps.
- Every visible control must work; no placeholder data.
- Changing the interface updates the README screenshots in the same change
  (`scripts/screenshots.sh`).
- No em dashes in any file or commit message.
- Branch model: `main` only. Test command: `scripts/check.sh`.
