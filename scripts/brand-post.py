#!/usr/bin/env python3
"""Crop and downsample a browser capture into an app resource.

  brand-post.py crop SRC SCALE W H DST [OUT_SIZE]

Takes the top-left W x H CSS-pixel box of SRC (captured at SCALE device pixels
per CSS pixel), resizes it with Lanczos to OUT_SIZE (square) or W x H, and
saves an optimized PNG. Fully opaque images are saved as RGB.
"""
import os
import sys

from PIL import Image


def main() -> int:
    if len(sys.argv) not in (7, 8) or sys.argv[1] != "crop":
        print(__doc__, file=sys.stderr)
        return 2
    src, scale, w, h, dst = sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5]), sys.argv[6]
    size = (int(sys.argv[7]),) * 2 if len(sys.argv) == 8 else (w, h)
    img = Image.open(src).convert("RGBA").crop((0, 0, w * scale, h * scale))
    img = img.resize(size, Image.LANCZOS)
    if img.getextrema()[3][0] == 255:
        img = img.convert("RGB")
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    img.save(dst, "PNG", optimize=True)
    print(f"{dst}: {size[0]}x{size[1]}, {os.path.getsize(dst) // 1024} KiB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
