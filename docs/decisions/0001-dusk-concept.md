# 0001: Dusk is the visual identity; Aurora is the second wallpaper

**Status:** Accepted, 2026-10-21

## Context
Three concepts were rendered for the same home screen at 1920x1080
(`design/concepts/`): cinematic sunset, aurora, and gallery photography.

## Decision
Ship the sunset concept, named Dusk, as the identity and default wallpaper.
Ship Aurora as a selectable wallpaper. Drop Gallery.

- Dusk has the clearest point of view: warm amber light against deep blue
  shadow, which suits a product named Hearth and a QLED panel's color volume.
  Its horizon sits at about 55% so the light stays above the controls.
- Aurora shows off saturated emerald and stays dark overall, which is
  comfortable in a dark room, so it earns a place as the alternative.
- Gallery needs real photography to work; drawn landscapes read as clip art,
  and bundling photographs means licensing and a much larger APK.

## Consequences
- Wallpaper art is original SVG in `design/art/`, rendered to 1920x1080 WebP
  by `scripts/render-art.sh` and `scripts/encode-art.py`, so the native app
  draws the same image the concept showed.
- Each wallpaper carries its own palette (`ui.theme.Wallpapers`).
