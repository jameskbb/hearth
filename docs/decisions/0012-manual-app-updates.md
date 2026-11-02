# 0012: Manual app updates through the TV's installer

**Status:** Accepted, 2026-11-01. Requested by the maintainer. Amends the
weather-only network promise in decision 0005 and the product brief.

## Decision

Settings has Check for updates. Selecting it opens an update page and checks
the latest stable public GitHub release for this repository. Opening Settings,
starting Hearth, resuming it, and observing update state never check for an
update. No worker, timer, service, notification, or boot receiver checks in
the background. Weather remains a separate opt-in feature.

A stable release carries two assets with exact names: `hearth-update.json`
and `app-release.apk`. The manifest identifies the version code and name,
minimum Android SDK, APK size and SHA-256. The version code must be greater
than the installed version. Drafts and prereleases are excluded. No stable
release produces an honest result instead of claiming the installed build is
current. Missing or malformed assets produce a retryable error.

The user separately chooses Download update, then Install update. HTTPS
requests use GitHub's API and release storage with bounded redirects, timeouts,
response sizes and APK size. Redirects are restricted to GitHub's release
hosts. No account, token, device identifier, app inventory, or location is
sent. Downloads live in private cache storage, and partial files are deleted
on cancellation or failure. Leaving the page or backgrounding Hearth cancels
an active check/download. A ready APK stays available during the installer
handoff and permission-setting round trip.

Before opening the installer, Hearth verifies the exact download checksum
and size, then the archive's package, expected higher version code, compatible
minimum SDK, and current signer certificates against its installed package.
A different key is rejected. Signing-key rotation is deliberately unsupported
by this updater so the same policy works on older Android. The platform also
verifies installation itself.

A private FileProvider exposes only the update cache directory with a temporary
read grant. Hearth requests installation permission but never grants it itself.
On Android 8 and later, the user enables installation from Hearth in the
platform settings. Older Fire OS uses its own unknown-source controls. Routes
are checked at runtime with settings fallbacks; unavailable routes are reported.
The system installer requires user confirmation. No silent install, root, ADB,
or modification of another app is involved.

## Release operation

The maintainer builds with the same persistent signing key as the installed
app, increases `versionCode` for each release, and runs
`scripts/prepare-update.py` to prepare assets locally. Publication remains a
manual maintainer action under the public repository rules. CI artifacts have
an ephemeral signing identity and must not be used as public update assets.
No release-publishing automation is added.

An existing TV needs one final installation of an APK containing this feature.
Subsequent compatible releases can be installed with the remote while ADB is
off. This does not change the requirements of a separate Home-button helper
or Home Assistant integration.

## Sources

- [GitHub's release API](https://docs.github.com/en/rest/releases/releases)
- [Android app publication and unknown-source installation](https://developer.android.com/studio/publish)
- [FileProvider read grants](https://developer.android.com/reference/androidx/core/content/FileProvider)
- [Android package install permission](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls())
