#!/usr/bin/env python3
"""Trim a variable font to the axis ranges and characters Hearth uses.

  scripts/trim-font.py IN.ttf OUT.ttf [--wght MIN:MAX] [--opsz MIN:MAX]

Made for the display face (docs/decisions/0004-display-typeface.md):

  scripts/trim-font.py 'Literata[opsz,wght].ttf' app/src/main/res/font/literata.ttf \
      --wght 300:400 --opsz 24:72

Axes stay variable inside the given ranges, so Compose can still pin each role
to its own weight and optical size. Characters are cut to Latin (Basic,
Latin-1, Extended-A and -B, Extended Additional), combining marks, general
punctuation, currency and letterlike symbols; all OpenType features are kept
(tabular figures for the clock among them). Hinting is dropped: Android does
not use TrueType hinting at these sizes. Needs fontTools
(`pip install fonttools`).
"""
import argparse
import os
import tempfile

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

RANGES = [
    (0x0020, 0x007E), (0x00A0, 0x024F), (0x02B9, 0x02DD), (0x0300, 0x036F),
    (0x1E00, 0x1EFF), (0x2000, 0x206F), (0x20A0, 0x20CF), (0x2100, 0x214F),
    (0x2190, 0x2199), (0x2212, 0x2212), (0x2215, 0x2215), (0xFB00, 0xFB06),
]


def axis_range(text: str) -> tuple[float, float]:
    lo, hi = text.split(":")
    return float(lo), float(hi)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("src")
    ap.add_argument("out")
    ap.add_argument("--wght", type=axis_range)
    ap.add_argument("--opsz", type=axis_range)
    args = ap.parse_args()

    font = TTFont(args.src)
    limits = {k: v for k, v in (("wght", args.wght), ("opsz", args.opsz)) if v}
    if limits:
        font = instancer.instantiateVariableFont(font, limits)
        # Reload from disk: the subsetter needs fully decompiled tables.
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "limited.ttf")
            font.save(path)
            font = TTFont(path)
            font.ensureDecompiled()

    opts = subset.Options()
    opts.layout_features = ["*"]
    opts.name_IDs = ["*"]
    opts.name_languages = ["*"]
    opts.notdef_outline = True
    opts.glyph_names = False
    opts.hinting = False
    opts.drop_tables += ["DSIG"]
    subsetter = subset.Subsetter(opts)
    subsetter.populate(unicodes=[c for lo, hi in RANGES for c in range(lo, hi + 1)])
    subsetter.subset(font)
    font.save(args.out)
    axes = ", ".join(f"{a.axisTag} {a.minValue:g}-{a.maxValue:g}" for a in font["fvar"].axes) if "fvar" in font else "static"
    print(f"{args.out}: {os.path.getsize(args.out)} bytes, {axes}")


if __name__ == "__main__":
    main()
