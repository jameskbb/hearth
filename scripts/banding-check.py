#!/usr/bin/env python3
"""Measure gradient-banding risk in wallpaper images.

A smooth 8-bit gradient that changes by only a few levels over hundreds of
pixels quantizes into flat bands. Fine grain (dither) breaks those bands into
short runs. This script walks vertical scanlines through smooth regions and
measures how long each column stays on exactly the same RGB value.

Per file and region it reports:
  p50 / p95 / max  per column, the longest run of one exact RGB value that is
                   bounded on both ends by a small step (1..--step levels), in px.
                   That is a gradient band; solid shapes bounded by hard edges
                   are not counted (0 means no bands in that column).
  flat%            share of vertically adjacent pixel pairs that are identical
  levels           distinct values of the green channel in the region
  verdict          ok when p95 <= --limit, otherwise RISK

Usage:
  scripts/banding-check.py [--limit N] [--region NAME:Y0:Y1 ...] FILE...
Y0 and Y1 are fractions of the image height. Default regions are the upper sky
(0.00-0.30) and the dark foreground behind the controls (0.80-1.00).
Exit status is 1 if any region is at risk. Needs Pillow and numpy.
"""
import argparse
import sys

import numpy as np
from PIL import Image

DEFAULT_REGIONS = ["sky:0.00:0.30", "foreground:0.80:1.00"]


def band_runs(block: np.ndarray, max_step: int):
    """Per column, the longest run of identical RGB values that is bounded on both
    sides by a small step (1..max_step levels): the signature of a quantized
    gradient. Runs bounded by big edges (silhouettes, solid shapes) are not bands."""
    b = block.astype(np.int16)
    d = np.abs(b[1:] - b[:-1]).max(axis=2)  # (h-1) x w, step between rows
    w = d.shape[1]
    longest = np.zeros(w, dtype=np.int32)
    for x in range(w):
        col = d[:, x]
        edges = np.flatnonzero(col)
        if len(edges) < 2:
            continue
        lengths = np.diff(edges)
        small = (col[edges] <= max_step)
        is_band = small[:-1] & small[1:]
        if is_band.any():
            longest[x] = lengths[is_band].max()
    return longest, d == 0


def analyse(path: str, regions, limit: int, max_step: int):
    img = Image.open(path).convert("RGB")
    px = np.asarray(img)
    h = px.shape[0]
    rows = []
    for spec in regions:
        name, y0, y1 = spec.split(":")
        a, b = int(float(y0) * h), int(float(y1) * h)
        block = px[a:b]
        runs, same = band_runs(block, max_step)
        p50, p95 = np.percentile(runs, [50, 95])
        rows.append(
            dict(
                file=path,
                region=f"{name} {float(y0):.2f}-{float(y1):.2f}",
                p50=int(p50),
                p95=int(p95),
                max=int(runs.max()),
                flat=100.0 * same.mean(),
                levels=len(np.unique(block[..., 1])),
                ok=p95 <= limit,
            )
        )
    return img.size, rows


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="+")
    ap.add_argument("--limit", type=int, default=16, help="max acceptable p95 run length in px (default 16)")
    ap.add_argument("--step", type=int, default=3, help="largest step (levels) that still counts as a band edge")
    ap.add_argument("--region", action="append", help="NAME:Y0:Y1 (fractions); repeatable")
    args = ap.parse_args()
    regions = args.region or DEFAULT_REGIONS

    risk = False
    print(f"{'file':44} {'region':22} {'p50':>4} {'p95':>4} {'max':>5} {'flat%':>6} {'levels':>6}  verdict")
    for f in args.files:
        size, rows = analyse(f, regions, args.limit, args.step)
        for r in rows:
            risk |= not r["ok"]
            print(
                f"{r['file'][-44:]:44} {r['region']:22} {r['p50']:4d} {r['p95']:4d} {r['max']:5d} "
                f"{r['flat']:6.1f} {r['levels']:6d}  {'ok' if r['ok'] else 'RISK'}"
            )
    return 1 if risk else 0


if __name__ == "__main__":
    sys.exit(main())
