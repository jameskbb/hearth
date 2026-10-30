# Testing

An emulator run is evidence about Hearth's logic, not proof of compatibility
with a real Fire TV. Anything not checked on a device is labeled untested.

## Unit tests and repository checks

```bash
./gradlew testDebugUnitTest      # JVM unit tests
scripts/check.sh                 # unit tests, fixture/demo builds, shellcheck,
                                 # CLAUDE.md/AGENTS.md parity, public-safety scan
```

## Android 7.1 compatibility

The original API 25 expansion is recorded below. The current minimum is API 21;
see the CI coverage section. Run `./gradlew lintDebug` at the current minimum;
NewApi findings must be fixed, not suppressed to force installation. Java time
calls use pinned core-library desugaring. The static font generator validates
character coverage and tabular figure support while producing font files with
no variable axes. Rebuild them with fontTools 4.61.1 and
`python scripts/static-fonts.py` after changing the original bundled fonts.

On an API 25 device, verify cold launch without linkage errors; clock/date and
Follow the Day scheduling; static-font rendering; All apps and favorites;
remote focus and Back; Settings display facts; HDMI inputs if offered; theme
changes and saved preferences across an app restart. Check memory, animation,
and crash logs. Weather/network behavior and default-home redirection need
separate checks. An APK build is not evidence that Home on Fire supports the OS.

Build verification on 2026-10-24: release APK metadata reports minimum API 25;
`lintDebug` passed with zero errors; all 376 JVM tests and repository checks
passed. Static-font regeneration produced identical bytes.

Physical smoke check on 2026-10-24, one Fire OS 6 / API 25 TV: release install
and cold activity launch succeeded; the main activity stayed resumed with a
live process; the clock, date, static fonts, artwork, and default favorites
rendered in a 1080p screenshot; the operator confirmed remote navigation.
The recent AndroidRuntime error query was empty. This does not establish
long-run stability, schedule-boundary behavior, weather, reboot persistence,
or a working Home-button redirect by itself. A subsequent separate helper
check verified Home return and automatic watcher startup after reboot; see
[Home-button notes](home-button.md#fire-os-6). Other checks remain open.

API 26+ must still use the original variable fonts. Existing README screenshots
represent that unchanged rendering path. Device-specific measurements belong
in private device records, not this public repository.

## CI

`.github/workflows/ci.yml` runs on every push to `main`, on pull requests and
on demand. Inside a container image with the Android SDK, pinned by digest so
every run uses the same toolchain, it runs `scripts/check.sh` (with shellcheck
installed), `./gradlew assembleDebug assembleRelease` and
`./gradlew lintDebug`, and uploads the APKs as a short-lived build artifact.
CI has no `keystore.properties`, so its release APK is debug-signed and only
proves the release build works; it is not a build to install and update from.

All jobs use GitHub-hosted `ubuntu-latest`, including while the repository is
private. The log identifies the machine only by the runner's name and OS.

## Android version compatibility in CI

The APK minimum is API 21; the target remains API 34. CI adds four independent
framework-test jobs covering every API from 21 through 36:

| Android APIs | Robolectric | Test JDK |
|---|---|---|
| 21, 22 | 4.14.1 | 21 |
| 23, 24, 25, 26 | 4.16 | 21 |
| 27, 28, 29, 30, 31 | 4.16 | 21 |
| 32, 33, 34, 35, 36 | 4.16 | 21 |

Robolectric supplies Android framework implementations in a JVM sandbox.
No Android TV image, connected device, KVM, or local emulator is required.
The CI command is `./gradlew :app:testDebugUnitTest -PcompatSdks=<comma-separated-APIs>`.
API 21/22 must stay in a separate group because the modern test runtime no
longer supports them. All production builds still use the existing JDK 17
toolchain. Versions remain pinned in the catalog.

The compatibility class exercises actual platform lookup and resolution calls,
legacy versus typed package flags, absent services, settings and HDMI intents,
clock receiver registration/delivery/unregistration, static versus variable
font-resource selection, Android-context DataStore persistence, and theme/weather
parsing with Android's own `org.json`. It verifies both configured and absent
locale handling for clock/weather formatting. Activity metadata uses the stable zero-flag
lookup; the other package wrappers exercise both legacy and typed overloads. Each SDK
records execution evidence. CI rejects an absent SDK, zero tests, any skipped
test, or any failure. The ordinary JVM suite remains separate and is run by
`scripts/check.sh`; the matrix does not masquerade as extra runs of pure tests.

The build job also checks the real debug and minified release APKs' minSdk 21,
targetSdk 34 and six static fonts. It checks desugared Java time in debug DEX,
because JVM tests use the host's Java time classes rather than Android's ART.
JUnit XML, HTML reports and runtime evidence are retained as CI artifacts.

These checks establish automated framework coverage. They do not certify
vendor firmware, GPU rendering, actual font rasterization, D-pad responsiveness,
network TLS providers, sleep/reboot behavior, or Home interception. Retain the
existing physical-device checklist for every new OS/device family. See
[decision 0011](decisions/0011-android-version-compatibility-ci.md).

## Test apps

Two extra Gradle modules build tiny installable apps (plain Android views, no
dependencies):

| Module, flavor | Package | What it exercises |
|---|---|---|
| `fixtures` banner | `io.github.jameskbb.hearth.fixture.banner` | TV app (LEANBACK_LAUNCHER) with a 16:9 banner |
| `fixtures` icononly | `...fixture.icononly` | TV app with an icon and no banner |
| `fixtures` phone | `...fixture.phone` | LAUNCHER only: belongs in "Other apps" |
| `fixtures` noart | `...fixture.noart` | TV app, no banner, fully transparent icon: monogram fallback |
| `demoapps` (6 flavors) | `io.github.jameskbb.hearth.demo.*` | Fictional TV apps with polished banners, for README captures only |

The `noart` icon is `fixtures/src/noart/res/drawable/fixture_icon.xml`, a
`<shape>` rectangle with solid `#00000000` and a 96 dp size: every pixel is
transparent. A launcher that detects empty artwork should treat it as
"no art".

```bash
./gradlew :fixtures:assembleDebug :demoapps:assembleDebug
```

## Emulator

Needs the Android SDK with the emulator and the
`system-images;android-28;android-tv;x86` image, and hardware acceleration
(`/dev/kvm` on Linux). If you were just added to the `kvm` group, log in again
(or run the commands under `sg kvm -c '...'`) before the group takes effect.

```bash
export ANDROID_HOME=<path-to-sdk>
scripts/emulator.sh create        # AVD hearth-tv-28, tv_1080p
scripts/emulator.sh start         # headless; waits for boot
scripts/emulator.sh stop
```

`AVD_NAME` and `EMU_PORT` select a different AVD and port, for example to run a
second emulator beside one that another session is using.

Other helpers (all take a serial argument or `ANDROID_SERIAL`):

```bash
scripts/install.sh --fixtures             # build and install Hearth + fixtures
scripts/remote.sh down down select        # press remote keys in order
scripts/remote.sh longselect              # long-press Select
scripts/screenshots.sh home               # docs/images/home.png
```

`HEARTH_APK=<apk>` makes `install.sh` install a given APK instead of building.

### Smoke test

```bash
scripts/smoke-test.sh [serial]
```

Installs Hearth and the fixtures, then: checks the fixtures resolve in the
right launcher categories, launches Hearth cold, moves around Home, opens All
apps, favorites the focused tile through the options dialog, goes Back, opens
Settings, goes Back, presses Back at the root, uninstalls a fixture while
Hearth runs, relaunches, and selects a tile. After each step it prints PASS or
FAIL based on: Hearth is the foreground activity (`dumpsys activity`), its
process is alive (`pidof`), and `logcat -b crash` has no Hearth crash.
Screenshots and `uiautomator` dumps of every step go to `build/smoke/`. It
cannot judge visuals. The remote paths are env vars (`NAV_ALL_APPS`,
`NAV_SETTINGS`, `NAV_FAVORITE`) in case the layout changes.

On stock Android TV, an implicit HOME intent (`am start -a
android.intent.action.MAIN -c android.intent.category.HOME`) opens the system
launcher rather than Hearth, because Hearth is not the system home there. The
scripts launch Hearth by its explicit component instead, which is also the
path a Home-button redirect uses on Fire OS.

### README screenshots and video

```bash
FFMPEG=<path-to-ffmpeg> scripts/capture-media.sh [serial]
```

Installs Hearth and the demo apps, captures `docs/images/` (home-dusk,
home-focus, options, arrange, all-apps, settings, home-aurora), records a
scripted tour (recorded by the emulator on the host by default), and writes
`docs/media/tour.mp4` (H.264 1080p) and `docs/media/tour.webp` (animated, 960
px, for inline display). The full procedure, including emulator preparation
and review, is in [`recording-media.md`](recording-media.md).
Without a system ffmpeg, a static build is available from the `imageio-ffmpeg`
pip package: `python -c "import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())"`.
Navigation paths are env vars (`NAV_*`, `TOUR`), listed at the top of the
script. Home paths start with `NAV_ACTIONS`, which reaches All apps whether
focus is on a tile or on the action row. Themes are chosen by name
(`THEME_MAIN`, `THEME_ALT`): the script opens the Appearance panel from the
Theme row and moves along the cards until a `uiautomator` dump shows the hint
"Select to use <name>", so it does not depend on how many themes exist,
and it restores the theme that was set before the run.

The unobscured-artwork revision was reviewed on a software-rendered API 28
TV emulator: all seven themes on Home and in Appearance, all three modes,
the schedule, the period editor, and rename Back navigation. Separate motion
contact sheets cover early animation and sustained motion after ten minutes.
These checks do not establish physical-panel color, sofa-distance contrast,
or frame pacing. The real-TV checklist below remains the hardware gate.

## Manual checklist on a real TV

Mirrors the Validation section of `docs/BRIEF.md`. Record device results in
your own notes; only the status belongs here.

| Check | How | Status |
|---|---|---|
| Home redirection | Press Home from another app: Home on Fire's overlay, then Hearth. Back at Hearth's root stays in Hearth. | untested |
| Mapped button | The mapped spare button opens Hearth instantly, no overlay. | untested |
| Wake | Sleep the TV, wake it: Hearth (or the last app) returns without a crash or a white flash. | untested |
| Restart | Reboot the TV: Home leads to Hearth; favorites, order, hidden apps and theme persisted. | untested |
| Settings access | Settings, Device settings opens the platform settings; Back returns to Hearth. | untested |
| Voice assistant | The remote's voice button still works from Hearth. | untested |
| Playback keys | Play/pause, rewind, fast-forward are not swallowed by Hearth and work in apps launched from it. | untested |
| Launching | Every favorite opens; returning restores the focused tile and scroll position. | untested |
| Removed app | Uninstall a favorite: Hearth shows the inline message and focus lands on a neighbor. | untested |
| Artwork | Banners keep their proportions; icon-only and no-art apps use the fallbacks. | untested |
| Banding and halo | In a dark room, look at every theme's skies and dark foregrounds for bands, and at the focus glow for haloing. | untested |
| Unobscured artwork | View every bundled theme on Home and in Settings. There must be no gray pools or backing gradients around the clock, names, hints, or settings rows. Check small lettering while clouds pass behind it. | untested |
| Seasonal themes | Preview Harvest Evening, Christmas, New Year, Easter and July Fourth on Home and in Appearance. Check steady motion after ten minutes, readable text through every motion phase, still artwork under Reduce motion, and no motion behind another app. Confirm they enter rotations only after explicit selection. | untested |
| Quiet Settings | Use only D-pad, Select, and Back to change all three modes, edit a period, rename it, and open About. Each Back restores the previous control; entering a period does not open the keyboard. | untested |
| Theme modes and preview | Settings, Theme: Single Theme, Follow the Day, and Surprise Me are clear from the sofa. Moving across cards previews each scene within about half a second, with no black or white flash. Back restores focus at every level. An upgrade keeps a chosen pack and migrates Auto to Follow the Day. | untested |
| Follow the Day boundaries | Restore the recommended schedule. Verify 05:00, 09:00, 17:00, and 20:30 switch once at the boundary without a key press, including Night across midnight. | untested |
| Custom schedule | Rename every period, move each boundary, assign one theme and a shuffled pool, then leave and reopen Settings. Hours, names, assignments, and focus are preserved; every minute belongs to exactly one period. | untested |
| Stable period shuffle | Give Evening two themes. Enter Evening, note the choice, launch an app, return, force-stop Hearth, and reboot. The choice stays for that occurrence. At the next Evening it changes when an alternative is usable. | untested |
| Surprise Me | Enable at least three themes. Reopening Hearth and rebooting keep today's theme; advancing to the next local day chooses once and avoids yesterday's theme when possible. | untested |
| Clock and timezone changes | While Hearth is in front, change the clock, timezone, and date across period boundaries and daylight saving transitions. The current period and next boundary update without polling or a restart. | untested |
| Theme motion | On Home, each theme's motion is smooth (no stutter or tearing) for a few minutes; `adb shell dumpsys gfxinfo io.github.jameskbb.hearth` shows few janky frames, and focus moves on the favorites row stay as quick as with Reduce motion on. | untested |
| Theme memory | `adb shell dumpsys meminfo io.github.jameskbb.hearth` on Home with each theme, and after browsing every card in Appearance: graphics memory settles near one theme's decoded size, never growing card after card. Launch an app and wait: Hearth's footprint drops when the system trims it, and the theme comes back on return. | untested |
| Reduce motion stills | With Reduce motion on, every theme is a still image that matches its gallery preview. Manual, scheduled, and Surprise Me changes snap without a crossfade. | untested |
| Interrupted theme transition | Browse previews quickly, enable Reduce motion during a transition, and leave Hearth during a transition. No half-transparent scene persists; returning is stable and moving scenes resume. | untested |
| Installed pack | Push a valid pack folder and a zipped pack to `Android/data/io.github.jameskbb.hearth/files/themes/`, select Reload themes, preview both, assign them to any period, and restart. The pack and assignment survive. | untested |
| Live pack replacement | Select an installed pack, replace only one image while leaving `theme.json` unchanged, then select Reload themes. The backdrop and card show the new bytes, unchanged pack caches remain warm, and no reinstall is needed. | untested |
| Incomplete or broken pack | Leave a temporary `.part` upload, push a malformed zip, a missing image, and a pack over 32 MB. Temporary files are ignored; bad packs never appear as cards, are listed with reasons, and cannot crash Hearth. Delete an assigned pack and verify the schedule falls back cleanly. | untested |
| Background transition | Leave Hearth for another app across a schedule boundary, then return. The correct theme appears once, with the old decoded scene held until the new one is ready and no visible image edge. | untested |
| Display facts | Settings, About shows the TV's real resolution, refresh rate, wide gamut and HDR values. | untested |
| Offline | With the network disconnected, Hearth starts and works. | untested |
| Weather off makes no requests | Fresh install, weather left off: no traffic from Hearth (router log or `adb shell dumpsys netstats detail`). | untested |
| Weather city search | Settings, Weather, Show on Home: the search opens with the system keyboard up; typing finds cities; the keyboard's Search key focuses the first match; Back closes the keyboard, then the search; choosing a city returns to the City row. | untested |
| Weather on Home | After choosing a city, the temperature, glyph and range appear beside the clock within seconds, readable on every theme from the sofa; Home's layout and focus are otherwise unchanged. | untested |
| Weather units | The default unit follows the TV's region; Units switches it and Home follows at once. | untested |
| Weather offline | With weather on, disconnect the network: Settings says Open-Meteo can't be reached and when Hearth will try again; after three hours Home shows no weather; reconnecting brings it back by the next retry. | untested |
| Inputs listed | On a TV with HDMI inputs, All apps shows "System inputs" first, one tile per port in port order, named as the TV names them (its custom label, else its label); an unnamed input reads "HDMI n" by its position, so the first port is "HDMI 1". | untested |
| Input switch from All apps | Select an input tile: the TV shows that HDMI input through its own player; Home returns to Hearth. | untested |
| Input switch from a favorite | Add an input to favorites from the options dialog; Select on Home switches to it; arrange and hide work on it. | untested |
| Input rename | Settings, Inputs, Rename: the keyboard opens with the current name; Done saves it and the tile and name line follow; an empty name restores the TV's name. | untested |
| Input switch failure | If a switch fails (for example the Live TV player is disabled), the inline "HDMI n didn't open" message appears and Hearth stays responsive. | untested |
| No inputs on a stick | On a Fire TV stick or cube: no "System inputs" section, no Inputs in Settings, nothing else changed. | untested |
| Weather in the background | Launch an app and leave it for an hour: Hearth makes no requests until it is in front again, then refreshes at once if the result is 30 minutes old. | untested |

Emulator status (API 28 Android TV, not a device): the smoke test passed 15 of
15 steps against a merged development build; fixtures resolve as intended.

## Open Bay and Home label device follow-up

- Select Open Bay in Single Theme. Inspect the bench, plane, vise, car and
  outside depth, warm/cool balance, grain, banding, haze and sparse dust from
  the sofa. Watch several minutes; motion should not compete with controls.
- Enable Reduce motion: all atmospheric motion stops with an attractive still.
  Leave Hearth and return: no background ticking or misaligned layer edges.
- With a fresh Surprise Me pool, Open Bay has no Included marker and never
  rotates in by itself. Select its card to opt in, then restart and confirm
  that the explicit pool is retained. Restore the recommended day schedule:
  its original seven themes and times remain unchanged.
- Settings, Theme (Appearance), Home label: enable, edit through the TV IME,
  Save/Done, Back to cancel a draft, disable, re-enable, and clear. Verify
  retained text after restarting Hearth and rebooting the TV. Check Back focus
  on each page and Home while the keyboard is open.
- Inspect the label against Open Bay, Aurora, Misty Forest and the three bright
  scenes, with and without weather, long conditions/dates and 40-character
  text. Confirm safe margins, ellipsis, no overlap or extra Home focus stop,
  and absence in All apps, Settings and the gallery. Confirm readability at
  sofa distance and on API 25's static font path.

These are physical-TV checks, not claims established by emulator captures.
