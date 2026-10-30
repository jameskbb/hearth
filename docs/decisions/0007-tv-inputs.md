# 0007: HDMI inputs as entries in All apps, through the TV Input Framework

**Status:** Accepted, 2026-10-22

## Context
The maintainer wants a TV's HDMI inputs reachable from Hearth without a new
permanent control on Home: inputs should behave like apps, listed in their
own section of All apps, pinnable to the favorites row, with recognizable
tiles and optional custom names. Fire OS is not stock Android TV, so what is
possible had to be checked on the device first.

## What the device reports (a Fire OS 7.7 television, Android 9)
Read-only probes over ADB, nothing switched:

- The platform's TV Input Framework is present: `dumpsys tv_input` lists one
  hardware input per HDMI port (`.../HDMIInputService/HW<n>` with
  `hdmi_port` 1 to 4), a composite input and a tuner, and reports each
  input's state (connected, standby, disconnected).
- The documented way to show a passthrough input, `ACTION_VIEW` on
  `TvContract.buildChannelUriForPassthroughInput(inputId)`, resolves to the
  platform's own Live TV player activity, which is exported and declares no
  permission on that filter.
- Amazon's own routes (`com.amazon.tv.action.NAVIGATE_TO_INPUTS`, the input
  preference service's `LAUNCH_INPUTS` and `SWITCH_AMAZON_INPUT` actions) are
  either disabled or guarded by Amazon-signature permissions. They are not
  available to a third-party launcher and are not used.
- A Fire TV stick or cube has no HDMI inputs and no Live TV player for them,
  so the same code finds nothing there.

Whether the resolved intent actually switches the picture is confirmed only
on the TV; the probe proves it is the supported route, not that it works.

## Decision
- `system.TvInputs` lists HDMI inputs from `TvInputManager` (type HDMI only,
  ordered by port, labelled from the framework's label, else "HDMI n") and
  switches by starting the passthrough `ACTION_VIEW` intent. It reports
  nothing unless the service exists, at least one HDMI input is listed and
  the intent resolves, so the feature is invisible where it cannot work.
- Inputs are catalog entries (`AppEntry` with `kind = Input`) keyed
  `input:<frameworkInputId>`, so favorites, hiding, arrange and the options
  dialog work unchanged. All apps shows a "System inputs" section first when
  any exist. Tiles use Hearth's own HDMI glyph on a tinted surface with the
  label, like an icon-only app.
- Custom names are Hearth's own preference, edited in Settings, Inputs;
  the framework's names are the default. Hearth never renames inputs in the
  system.
- A switch that fails shows the same inline message as an app that did not
  open. No control is shown that could do nothing.

## Consequences
- The manifest gains a `<queries>` entry for the channel `ACTION_VIEW`, so
  resolution works on Android 11+ as well.
- The device checklist gains: inputs listed with the right names, a switch
  from All apps and from a favorite, a failed switch's message, and that
  nothing input-related appears on a stick.
