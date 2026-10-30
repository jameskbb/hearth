# Build, install, update and roll back

Hearth is installed by sideloading over ADB. Placeholders in angle brackets,
such as `<tv-ip>`, stand for your own values.

## Compatibility

Hearth's APK supports installation on Android TV 5.0 (API 21) and later,
including Fire OS 5 (API 22), Fire OS 6 (API 25), Fire OS 7 (API 28), and
later Android-based Fire OS versions. Its target SDK remains 34.
Older Android receives bundled static versions of the same fonts and a
compatibility implementation of the Java time APIs used by theme schedules.
The newer-device font and theme paths are unchanged.

Running Hearth and intercepting the Home button are separate capabilities.
Home on Fire upstream lists Fire OS 6 as unsupported. On those devices,
launch Hearth from the installed-app list or an ADB activity launch; do not
assume Home or restart will return to it. Never disable Amazon's launcher
without a working, verified replacement route. A separate UsageStats helper
has passed a limited Fire OS 6 device check;
see [Home-button notes](home-button.md#fire-os-6) for limitations.

CI runs Android framework unit tests across APIs 21 through 36, checks both
APKs' minimum SDKs and font resources, and runs Android lint. These checks
do not certify a physical TV or a Home redirect. Older Fire OS firmware and
modern Android TV hardware still need the
[compatibility checklist](testing.md#android-version-compatibility-in-ci).

## Build

Requires JDK 17 and the Android SDK (`sdk.dir=...` in `local.properties`,
which is not committed).

```bash
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

The release build is minified. How it is signed depends on one file:

- **With `keystore.properties`** in the repository root, the release APK is
  signed with the key it names (below).
- **Without it**, the release APK is signed with this machine's debug key and
  Gradle prints a warning saying so. That is fine for trying Hearth on your
  own TV, but the debug key differs per machine (and changes whenever the
  SDK's debug keystore is recreated), and Android only lets an update replace
  an installed app signed with the same key.

### Signing with your own key

Keep the keystore and its passwords out of the repository: `*.jks`,
`*.keystore` and `keystore.properties` are already in `.gitignore`. Never
commit a keystore.

1. Create a key once with `keytool` (it comes with the JDK). It asks for a
   store password, a key password and a name. Back up the keystore and the
   passwords: losing the key means every TV must uninstall Hearth, erasing
   its preferences, before it can take a build signed with a new key.
   ```bash
   keytool -genkeypair -v -keystore <path-outside-repo>/hearth-release.jks \
     -alias hearth -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Create `keystore.properties` in the repository root (ignored by git). All
   four keys are required. `storeFile` is an absolute path or a path relative
   to the repository root; keep the keystore itself outside the repository.
   ```properties
   storeFile=<path-outside-repo>/hearth-release.jks
   storePassword=<store-password>
   keyAlias=hearth
   keyPassword=<key-password>
   ```
3. Build with `./gradlew assembleRelease`. If the file exists but a key is
   missing or empty, or `storeFile` does not exist, the build stops with an
   error; it never falls back to the debug key once the file is present.

To check which key signed an APK:

```bash
<sdk>/build-tools/<version>/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Use the same key for every build you install on a TV. `adb install -r` keeps
Hearth's preferences only when the new APK is signed with the same key as the
installed one (see Update below). A TV that first got a debug-signed build
must uninstall it once, losing its preferences, before it can move to your
release key.

## Install on a Fire TV over ADB

1. On the TV: if Settings, My Fire TV has no Developer options, open About and
   select the device name seven times to unlock them. Then, in Developer
   options, turn on **ADB debugging** (and **Apps from Unknown Sources** if
   offered).
2. Find the TV's address: Settings, My Fire TV, About, Network.
3. From your computer, on the same network:
   ```bash
   adb connect <tv-ip>:5555        # accept the prompt on the TV the first time
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```
   Or with the helper: `scripts/install.sh --release <tv-ip>:5555`.

## Make Home open Hearth

Install Home on Fire and follow its own setup (it needs its accessibility
service enabled and, for some features, `WRITE_SECURE_SETTINGS` granted over
ADB). Then, in Home on Fire, set the **target launcher to Hearth**. See
`docs/home-button.md` for the recommended loading-screen settings and a mapped
button for instant launches.

## Update

```bash
adb install -r app-release.apk
```

`-r` replaces the installed app and **keeps Hearth's preferences**
(favorites, order, hidden apps, wallpaper) as long as the new APK is signed
with the same key. If the keys differ, the install fails with a signature
error; installing then requires uninstalling first, which erases preferences.

## Roll back

Amazon's launcher is never replaced or modified, so it always remains.

- **Switch launchers:** in Home on Fire, set the target back to the previous
  launcher (for example FLauncher), or turn its Home redirect off to return to
  Amazon home.
- **Remove Hearth entirely:**
  ```bash
  adb uninstall io.github.jameskbb.hearth
  ```
  Then point Home on Fire at another launcher or disable its redirect.
- **Go back to an older Hearth build:** Android refuses to downgrade in place.
  Either uninstall and install the older APK (preferences are lost), or use
  `adb install -r -d <older.apk>` (debuggable builds only).

## Status

A release APK was installed and launched on a physical Fire OS 6 / API 25 TV
on 2026-10-24. The first screen and remote navigation passed the narrow smoke
check in [testing](testing.md#android-71-compatibility). A separate Home-button
helper was subsequently verified; see the
[Home-button notes](home-button.md#fire-os-6).
