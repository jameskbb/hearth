# The Home button and the loading transition

On Fire OS, Amazon's launcher stays the system home. Hearth is opened by a
Home-button redirect app; this page describes the one Hearth is tested with,
[Home on Fire](https://github.com/toolicious/home-on-fire) (GPL-3.0-or-later),
as of release v1.5.2.

Labels used below:
- **Implemented**: something Hearth itself does.
- **Documented by upstream**: stated in Home on Fire's README, strings or
  source (file and function names cited, read at tag v1.5.2).
- **Verified on a device**: observed on real hardware, with the OS identified.
  The Fire OS 6 section has a physical check; the Fire OS 7 instructions below
  remain based on upstream documentation.

## Fire OS 6

Hearth includes an Android 7.1 compatibility path, but Home on Fire reports
that its redirect does not work on Fire OS 6.7.1.1.
Source: [Home on Fire compatibility](https://github.com/toolicious/home-on-fire#compatibility).

A third-party [modified LauncherHijack](https://webserver-x.de/20260913/fire-tv-stick-alternative-launcher-home-button-without-root/)
uses usage events instead of accessibility to detect Amazon Home and open the
selected launcher. A locally built copy was checked on one Fire OS 6 TV:
Home returned to Hearth from two apps, and the watcher started automatically
after a full reboot. Redirection took several seconds; boot-event delivery
took about two minutes. This is limited device evidence, not a guarantee for
all Fire OS 6 hardware or firmware. Amazon Home remains enabled.

The helper is separate from Hearth. It needs usage access and boot permission;
it is not bundled with this project. Review its source and device-specific
setup before installing. Uninstalling the helper restores ordinary Home
behavior. Installing Hearth alone does not establish Home or boot redirection.

## What happens when you press Home on Fire OS 7

1. The platform starts Amazon's home and holds any other Home-triggered launch
   for up to about 5 seconds (an app-switch lock). This cannot be removed
   without root. *Documented by upstream* (`mask_cover_tip` and
   `switch_hijack_tip_fos7` in `res/values/strings.xml`).
2. Amazon home appears briefly before Home on Fire's accessibility service can
   react. *Documented by upstream*: its "Start cover delay" setting
   (`mask_grace_ms`, `Prefs.DEFAULT_MASK_GRACE = 0`) already defaults to 0 ms,
   so this flash cannot be tuned away further.
3. Home on Fire covers the rest of the wait with its own overlay window,
   `LoaderView`, added by `HijackService` (the block that calls
   `view.setBlackScreen(prefs.isMaskBlackScreen())`). *Documented by upstream.*
4. When the lock lifts, Home on Fire starts the target launcher, Hearth, and
   its overlay fades out.

Fire OS 8 has no such hold, and Home on Fire shows no overlay there
(*documented by upstream*).

## Whose pixels are on screen

| Moment | Drawn by | Hearth can change it? |
|---|---|---|
| Brief Amazon home flash | Fire OS | No |
| Loading overlay (animation or black screen) | Home on Fire, `LoaderView` | No (only by a Home on Fire change) |
| First frame of Hearth | Hearth: window background `#0A0E24` (Dusk night color, `values/colors.xml` and `Theme.Hearth`) | Yes, *implemented* |
| Home screen | Hearth: wallpaper and UI | Yes, *implemented* |

Home on Fire's overlay has two looks, chosen by "Loading screen" in its
settings (pref `mask_black_screen`):
- **Animation** (default): `LoaderView.onDraw` plays a short scene with the
  caption "Loading custom launcher…" and the Home on Fire brand mark
  (`drawBrandMark`).
- **Black screen**: `LoaderView.drawBlackScreen` fills black, but still draws
  the letter-spaced loading caption and calls `drawBrandMark`, so a "Home on
  Fire" wordmark stays visible at the bottom.

So with today's options the quietest available overlay is black with a caption
and a wordmark. A truly plain screen needs a source change (below).

## Recommended settings today

1. In Home on Fire, set **Loading screen: Black screen**. It is the quietest
   built-in option and its black sits close to Hearth's `#0A0E24` first frame.
2. Map a spare remote button to Hearth under **Map custom buttons**. A button
   that sends its own key code (a color, number or media key) never goes
   through Home, so it opens Hearth instantly with no overlay and no Amazon
   flash. *Documented by upstream* (README "To get an instant launch",
   `launch_key_tip_fos7`).
3. Leave "Start cover delay" at its default 0 ms.

Hearth does not promise to remove the platform delay or the brief Amazon home
flash; neither is under its control.

## Proposed upstream change: a "Plain screen" option

**Proposal only. Not written, not built, not tested.** A minimal,
upstream-friendly addition to Home on Fire:

- **Prefs.java** (~8 lines): replace the boolean with a three-value mode while
  keeping `mask_black_screen` readable for migration, plus a color:
  `mask_cover_mode` = `animation` | `black` | `plain` (defaulting from the old
  boolean), and `mask_plain_color` (int, default `0xFF000000`; a user who runs
  Hearth would pick `0xFF0A0E24`).
- **LoaderView.java** (~8 lines): a `setPlainScreen(int color)` setter and,
  at the top of `onDraw`, `if (plain) { canvas.drawColor(plainColor); return; }`
  so nothing else is drawn: no caption, no `drawBrandMark`, no animation clock.
- **HijackService.java** (1 line): call `view.setPlainScreen(...)` when the mode
  is `plain`, next to the existing `setBlackScreen` call.
- **MainActivity.java** (~4 lines): the existing "Loading screen" row (the
  toggle near `prefs.setMaskBlackScreen`) cycles through three values instead
  of two, with a "Plain screen" string; the color could start as a fixed
  choice (black or near-black navy) and become configurable later.
- `LoaderPreviewActivity` would honor the same mode so the preview stays
  truthful.

About 20 lines in all. It keeps Home on Fire's branding everywhere it matters
(its own app and settings) and only lets a user choose an unbranded cover.

## Licensing

- Home on Fire is **GPL-3.0-or-later**. Any modified build must stay under the
  GPL, and anyone who distributes it must provide the corresponding source.
- Hearth stays **MIT**. It is a separate app that shares no code with Home on
  Fire; the two talk only through Android intents. Nothing from Home on Fire is
  copied into this repository.
- **Best path: a pull request upstream**, which the maintainer would open by
  hand. Users then get the option in the normal, signed release.
- **A self-built fork** is signed with a different key, so it cannot update the
  installed Home on Fire in place. It would need: uninstall the official app,
  install the fork, grant `WRITE_SECURE_SETTINGS` again over ADB, and re-enable
  the accessibility service. Hearth will not ship such a fork.

## Status

| Item | Status |
|---|---|
| Hearth's dark first frame (`#0A0E24` window background) | Implemented |
| Home on Fire behavior above | Documented by upstream (v1.5.2 source and README) |
| Black screen + mapped button recommendation | Documented by upstream; not verified on a device |
| "Plain screen" option | Proposed only |
| Anything on a real Fire OS 7 TV | Not verified yet |
