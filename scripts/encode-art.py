#!/usr/bin/env python3
"""Encode a rendered theme layer PNG to the app's WebP.

  scripts/encode-art.py IN.png OUT.webp [--dither LEVELS] [--quality Q | --lossless]
  scripts/encode-art.py IN.png OUT.webp --overlay [--dither LEVELS]

The art carries fine grain so 8-bit gradients do not band. Lossy WebP at the
usual quality settings smooths that grain away (its 4:2:0 chroma and block
quantization flatten low-amplitude noise), which brings the bands back, so the
default is lossless. See docs/display.md for the measurements. Output is sRGB
RGB (no alpha), 1920x1080.

With --overlay the image keeps its size and alpha (a translucent theme layer
such as fog, clouds or stars). An overlay that turns out fully opaque is
written as RGB, like a base layer, at its own size. The grain is added to the
color channels and, at the same amplitude, to partial alpha, so soft edges do
not band either.
"""
import argparse
import os
import sys

import numpy as np
from PIL import Image


def dither(img: Image.Image, amplitude: float, seed: int) -> Image.Image:
    """Add fixed-seed, zero-mean triangular noise of +-amplitude levels to every
    channel (the same noise for R, G and B, so it reads as luminance grain and
    does not shift hue or saturation). The SVG grain is an overlay blend, which
    nearly vanishes in deep shadow; this keeps the darkest gradients broken up."""
    if amplitude <= 0:
        return img
    rng = np.random.default_rng(seed)
    h, w = img.height, img.width
    n = (rng.random((h, w)) - rng.random((h, w))) * amplitude
    px = np.asarray(img).astype(np.float32) + n[..., None]
    return Image.fromarray(np.clip(np.rint(px), 0, 255).astype(np.uint8), "RGB")


def dither_rgba(img: Image.Image, amplitude: float, seed: int) -> Image.Image:
    """The same grain for a straight-alpha RGBA layer: color grain as above,
    and grain on alpha only where it is partial (0 stays 0, 255 stays 255)."""
    px = np.asarray(img).astype(np.float32)
    if amplitude > 0:
        rng = np.random.default_rng(seed)
        h, w = img.height, img.width
        n = (rng.random((h, w)) - rng.random((h, w))) * amplitude
        px[..., :3] += n[..., None]
        a = px[..., 3]
        partial = (a > 0) & (a < 255)
        n2 = (rng.random((h, w)) - rng.random((h, w))) * amplitude
        a[partial] = np.clip(a[partial] + n2[partial], 1, 254)
    out = np.clip(np.rint(px), 0, 255).astype(np.uint8)
    out[out[..., 3] == 0, :3] = 0
    return Image.fromarray(out, "RGBA")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--lossless", action="store_true", default=True)
    mode.add_argument("--quality", type=int, help="lossy WebP quality 0-100 instead of lossless")
    ap.add_argument("--dither", type=float, default=1.5, help="grain amplitude in 8-bit levels (0 = off)")
    ap.add_argument("--seed", type=int, default=1, help="noise seed, fixed so renders are reproducible")
    ap.add_argument("--overlay", action="store_true", help="keep size and alpha (a translucent layer)")
    args = ap.parse_args()

    if args.overlay:
        img = Image.open(args.src).convert("RGBA")
        if np.asarray(img)[..., 3].min() == 255:
            img = dither(img.convert("RGB"), args.dither, args.seed)
        else:
            img = dither_rgba(img, args.dither, args.seed)
        img.save(args.dst, "WEBP", lossless=True, quality=100, method=6, exact=img.mode == "RGB")
        print(f"{args.dst}: {img.width}x{img.height} {img.mode} {os.path.getsize(args.dst) // 1024} KiB")
        return 0

    img = Image.open(args.src).convert("RGB")
    if img.size != (1920, 1080):
        img = img.resize((1920, 1080), Image.LANCZOS)
    img = dither(img, args.dither, args.seed)
    if args.quality is not None:
        img.save(args.dst, "WEBP", quality=args.quality, method=6, exact=True)
    else:
        img.save(args.dst, "WEBP", lossless=True, quality=100, method=6, exact=True)
    print(f"{args.dst}: {os.path.getsize(args.dst) // 1024} KiB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
