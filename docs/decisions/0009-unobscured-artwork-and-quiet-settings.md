# 0009: Unobscured artwork and quiet Settings

**Status:** Accepted, 2026-10-23

## Context

Gradient pools added behind text visibly covered the landscapes. Shrinking
them still left gray patches around labels, which conflicted with the
maintainer's direction: artwork should supply the color and atmosphere while
the interface recedes. Appearance also presented too much explanation and
maintenance information at once.

## Decision

- Remove all text backing gradients from Home, All apps, and Settings.
  Readability uses restrained glyph shadows and clear secondary text.
  Existing fonts, layout proportions, and focus indicators remain.
- Present the three theme modes together, with one explanation for the
  selected mode and only its relevant controls below. Put About on its own
  Settings destination. Keep scheduling and installed-pack tools available
  without making them part of the home screen.
- Keep one shared layered animation renderer. Increase the three daylight
  packs' cloud drift to the restrained range already used by the established
  packs. Landscape motion remains subtle. Reduce Motion and leaving Hearth
  must stop both ambient motion and an in-progress theme crossfade.
- Retain legacy shade palette fields in the pack codec for compatibility;
  they no longer create text backing gradients.

## Consequences

Every bundled theme requires a native screenshot review and a motion-phase
review. Bright clouds, small captions, and Settings focus must be checked
together. Removing a gradient is not evidence that text contrast is adequate.
Physical-panel color, sofa-distance readability, and frame pacing still need
device observation; emulator captures cannot establish those qualities.
