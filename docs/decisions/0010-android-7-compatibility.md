# 0010: Android 7.1 compatibility

**Status:** Accepted, 2026-10-24. Supersedes the minimum API requirement in
[0002](0002-stack.md); supplements [0004](0004-display-typeface.md).

## Context

The maintainer requested Hearth support on older Fire OS devices. Fire OS 6
uses Android 7.1 / API 25, below the original minimum API 28. Lowering the
manifest alone would expose Java time, variable-font, and display API gaps.

## Decision

Support API 25 in the existing application, with the same package and signing
identity. Keep the current pinned UI dependencies. Add pinned
`desugar_jdk_libs` 2.1.5 for the Java time API used by theme schedules. API 25
uses static instances generated from the bundled OFL fonts at the existing
weights and optical sizes; API 26+ keeps the original variable-font code.
Guard wide-gamut display queries below API 26. Bump the app to 0.1.1 / code 2.

## Consequences

The installable APK gains compatibility code and six static font instances.
No fonts download at runtime. Existing newer-device artwork, typography,
preferences, and navigation remain unchanged. Lint must pass at the lower
minimum API, and native runtime behavior must be checked on older hardware.

Home-button interception is a separate integration. Home on Fire reports
Fire OS 6 unsupported, so this change does not promise Home or boot redirection
and does not modify Amazon's launcher or global device settings.

## References

- [Android Java API desugaring](https://developer.android.com/studio/write/java8-support)
- [Compose font compatibility](https://developer.android.com/develop/ui/compose/text/fonts)
- [Home on Fire compatibility](https://github.com/toolicious/home-on-fire#compatibility)
