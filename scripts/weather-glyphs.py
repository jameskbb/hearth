#!/usr/bin/env python3
"""Generate Hearth's weather condition glyphs.

  scripts/weather-glyphs.py [--svg-dir DIR]

Writes one Android vector drawable per condition to
app/src/main/res/drawable/weather_*.xml and, with --svg-dir, a matching SVG
for each (used to proof the glyphs over the wallpapers in a browser).

The glyphs are drawn on a 24-unit grid as single-weight strokes (1.5 units,
round caps and joins), the same quiet line language as the rest of Hearth.
Shapes are built from circles: a cloud is three overlapping circles on a flat
base, the moon is one circle minus another. Where a sun or moon sits behind a
cloud, its outline is cut where it passes under the cloud (with a small gap),
so the glyph reads as layered without fills. The drawables are white; Compose
tints them with the wallpaper palette's ink. Standard library only.
"""
import argparse
import math
import os

STROKE = 1.5
GAP = 1.4  # clearance between a cloud and what it hides
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "app", "src", "main", "res", "drawable")


def f(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def circle_intersections(c1, r1, c2, r2):
    (x1, y1), (x2, y2) = c1, c2
    d = math.hypot(x2 - x1, y2 - y1)
    a = (r1 * r1 - r2 * r2 + d * d) / (2 * d)
    h = math.sqrt(max(r1 * r1 - a * a, 0))
    mx, my = x1 + a * (x2 - x1) / d, y1 + a * (y2 - y1) / d
    p1 = (mx + h * (y2 - y1) / d, my - h * (x2 - x1) / d)
    p2 = (mx - h * (y2 - y1) / d, my + h * (x2 - x1) / d)
    return p1, p2


def ang(c, p):
    return math.atan2(p[1] - c[1], p[0] - c[0])


def sample_arc(c, r, a0, a1, n=48):
    return [(c[0] + r * math.cos(a0 + (a1 - a0) * i / n), c[1] + r * math.sin(a0 + (a1 - a0) * i / n))
            for i in range(n + 1)]


class Cloud:
    """Three circles on a flat base: left, top and right. Outline traced clockwise from the base."""

    def __init__(self, base, left, rl, top, rt, right, rr):
        self.cl, self.rl = (left, base - rl), rl
        self.ct, self.rt = top, rt
        self.cr, self.rr = (right, base - rr), rr
        self.base = base
        # Upper intersections (smaller y) of right/top and top/left.
        self.p_rt = min(circle_intersections(self.cr, self.rr, self.ct, self.rt), key=lambda p: p[1])
        self.p_tl = min(circle_intersections(self.ct, self.rt, self.cl, self.rl), key=lambda p: p[1])

    def arcs(self):
        """(center, r, start angle, end angle), each running counterclockwise on screen."""
        a_r0 = math.pi / 2
        a_r1 = ang(self.cr, self.p_rt)
        while a_r1 > a_r0:
            a_r1 -= 2 * math.pi
        a_t0 = ang(self.ct, self.p_rt)
        a_t1 = ang(self.ct, self.p_tl)
        while a_t1 > a_t0:
            a_t1 -= 2 * math.pi
        a_l0 = ang(self.cl, self.p_tl)
        a_l1 = math.pi / 2
        while a_l1 > a_l0:
            a_l1 -= 2 * math.pi
        return [(self.cr, self.rr, a_r0, a_r1), (self.ct, self.rt, a_t0, a_t1), (self.cl, self.rl, a_l0, a_l1)]

    def path(self):
        sx, sy = self.cl[0], self.base
        d = f"M{f(sx)},{f(sy)}H{f(self.cr[0])}"
        for c, r, a0, a1 in self.arcs():
            end = (c[0] + r * math.cos(a1), c[1] + r * math.sin(a1))
            large = 1 if abs(a1 - a0) > math.pi else 0
            d += f"A{f(r)},{f(r)} 0 {large} 0 {f(end[0])},{f(end[1])}"
        return d + "Z"

    def polygon(self):
        pts = [(self.cl[0], self.base), (self.cr[0], self.base)]
        for c, r, a0, a1 in self.arcs():
            pts += sample_arc(c, r, a0, a1)
        return pts


def inside(poly, p):
    x, y = p
    n, hit = len(poly), False
    for i in range(n):
        (x1, y1), (x2, y2) = poly[i], poly[(i + 1) % n]
        if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
            hit = not hit
    return hit


def seg_dist(p, a, b):
    ax, ay = a
    bx, by = b
    dx, dy = bx - ax, by - ay
    t = 0 if dx == dy == 0 else max(0, min(1, ((p[0] - ax) * dx + (p[1] - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(p[0] - ax - t * dx, p[1] - ay - t * dy)


def hidden(poly, p, gap):
    if inside(poly, p):
        return True
    return any(seg_dist(p, poly[i], poly[(i + 1) % len(poly)]) < gap for i in range(len(poly)))


def visible_runs(items, point, poly, gap=GAP):
    """Splits a sequence into the runs whose points stay clear of the cloud."""
    runs, cur = [], []
    for item in items:
        if hidden(poly, point(item), gap):
            if len(cur) > 1:
                runs.append(cur)
            cur = []
        else:
            cur.append(item)
    if len(cur) > 1:
        runs.append(cur)
    return runs


def clipped_line(points, poly):
    """A straight segment, sampled, cut where it passes under the cloud: one M/L per visible piece."""
    return "".join(f"M{f(r[0][0])},{f(r[0][1])}L{f(r[-1][0])},{f(r[-1][1])}"
                   for r in visible_runs(points, lambda p: p, poly))


def clipped_arc(c, r, a0, a1, poly, n=144):
    """An arc of circle (c, r) from angle a0 to a1, cut under the cloud: one SVG arc per visible piece."""
    angles = [a0 + (a1 - a0) * i / n for i in range(n + 1)]
    at = lambda a: (c[0] + r * math.cos(a), c[1] + r * math.sin(a))
    d = ""
    for run in visible_runs(angles, at, poly):
        start, end = at(run[0]), at(run[-1])
        large = 1 if abs(run[-1] - run[0]) > math.pi else 0
        sweep = 1 if run[-1] > run[0] else 0
        d += f"M{f(start[0])},{f(start[1])}A{f(r)},{f(r)} 0 {large} {sweep} {f(end[0])},{f(end[1])}"
    return d


def circle_path(c, r):
    x, y = c
    return f"M{f(x - r)},{f(y)}A{f(r)},{f(r)} 0 1 1 {f(x + r)},{f(y)}A{f(r)},{f(r)} 0 1 1 {f(x - r)},{f(y)}Z"


def sun(c, r, r0, r1, cloud=None):
    rays = []
    for i in range(8):
        a = -math.pi / 2 + i * math.pi / 4
        rays.append([(c[0] + rr * math.cos(a), c[1] + rr * math.sin(a))
                     for rr in [r0 + (r1 - r0) * k / 8 for k in range(9)]])
    if cloud is None:
        d = circle_path(c, r)
        d += "".join(f"M{f(ray[0][0])},{f(ray[0][1])}L{f(ray[-1][0])},{f(ray[-1][1])}" for ray in rays)
        return d
    poly = cloud.polygon()
    # Start the circle where it is hidden, so no visible arc is split at the seam.
    d = clipped_arc(c, r, math.pi / 4, math.pi / 4 + 2 * math.pi, poly)
    for ray in rays:
        d += clipped_line(ray, poly)
    return d


def moon(c, r, cut_c, cut_r, cloud=None):
    """A crescent: circle (c, r) minus circle (cut_c, cut_r)."""
    p1, p2 = circle_intersections(c, r, cut_c, cut_r)
    # Outer arc: the part of the big circle outside the cut circle.
    a1, a2 = ang(c, p1), ang(c, p2)
    mid_far = ang(c, cut_c) + math.pi
    # Go from p1 to p2 the long way round, through the side away from the cut.
    while a2 < a1:
        a2 += 2 * math.pi
    if not (a1 <= mid_far <= a2 or a1 <= mid_far + 2 * math.pi <= a2):
        a1, a2 = a2, a1 + 2 * math.pi
        p1, p2 = p2, p1
    b1, b2 = ang(cut_c, p2), ang(cut_c, p1)
    near = ang(cut_c, c)
    while b2 > b1:
        b2 -= 2 * math.pi
    if not (b2 <= near <= b1 or b2 <= near - 2 * math.pi <= b1):
        b2 += 2 * math.pi
    if cloud is None:
        large = 1 if (a2 - a1) > math.pi else 0
        sweep_inner = 1 if b2 > b1 else 0
        return (f"M{f(p1[0])},{f(p1[1])}A{f(r)},{f(r)} 0 {large} 1 {f(p2[0])},{f(p2[1])}"
                f"A{f(cut_r)},{f(cut_r)} 0 0 {sweep_inner} {f(p1[0])},{f(p1[1])}Z")
    poly = cloud.polygon()
    return clipped_arc(c, r, a1, a2, poly) + clipped_arc(cut_c, cut_r, b1, b2, poly)


def lines(*segs):
    return "".join(f"M{f(a)},{f(b)}L{f(c)},{f(d)}" for a, b, c, d in segs)


def dots(*centers, r=0.95):
    return "".join(circle_path(c, r) for c in centers)


# Clouds. A full cloud for overcast; a raised one when something falls below it; a smaller
# lower-right one when the sun or moon is behind it.
CLOUD = Cloud(base=18.5, left=6.6, rl=3.4, top=(12.4, 10.6), rt=5.1, right=17.4, rr=4.1)
RAISED = Cloud(base=14.6, left=6.6, rl=3.2, top=(12.2, 7.9), rt=4.8, right=17.4, rr=3.8)
BEHIND = Cloud(base=19.6, left=9.8, rl=2.9, top=(14.6, 12.9), rt=4.3, right=18.9, rr=3.4)

GLYPHS = {
    "clear_day": [("s", sun((12, 12), 4.1, 6.6, 8.9))],
    "clear_night": [("s", moon((11.4, 12.6), 7.6, (16.6, 7.6), 6.0))],
    "partly_cloudy_day": [
        ("s", BEHIND.path()),
        ("s", sun((8.6, 8.6), 3.4, 5.6, 7.3, cloud=BEHIND)),
    ],
    "partly_cloudy_night": [
        ("s", BEHIND.path()),
        ("s", moon((8.6, 9.0), 5.6, (12.4, 5.4), 4.4, cloud=BEHIND)),
    ],
    "cloudy": [("s", CLOUD.path())],
    "fog": [
        ("s", RAISED.path()),
        ("s", lines((4.5, 18.4, 15.5, 18.4), (18.5, 18.4, 19.5, 18.4), (7.5, 21.6, 19.5, 21.6))),
    ],
    "drizzle": [
        ("s", RAISED.path()),
        ("s", lines((8.6, 18.1, 8.1, 19.3), (12.6, 18.1, 12.1, 19.3), (16.6, 18.1, 16.1, 19.3),
                    (10.6, 20.9, 10.1, 22.1), (14.6, 20.9, 14.1, 22.1))),
    ],
    "rain": [
        ("s", RAISED.path()),
        ("s", lines((8.9, 17.6, 7.6, 21.4), (12.9, 17.6, 11.6, 21.4), (16.9, 17.6, 15.6, 21.4))),
    ],
    "sleet": [
        ("s", RAISED.path()),
        ("s", lines((8.9, 17.6, 7.6, 21.4), (16.9, 17.6, 15.6, 21.4))),
        ("f", dots((12.4, 18.6), (11.8, 21.6))),
    ],
    "snow": [
        ("s", RAISED.path()),
        ("f", dots((8.2, 18.4), (12.4, 18.4), (16.6, 18.4), (10.3, 21.7), (14.5, 21.7))),
    ],
    "thunderstorm": [
        ("s", RAISED.path()),
        ("s", "M13.2,16.6L10.6,20.2H13.6L11.2,23.4"),
    ],
}


def to_vector(parts):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           "<!-- Generated by scripts/weather-glyphs.py; edit the script, not this file. -->",
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="24dp"', '    android:height="24dp"',
           '    android:viewportWidth="24"', '    android:viewportHeight="24">']
    for kind, d in parts:
        if kind == "s":
            out.append(f'    <path\n        android:pathData="{d}"\n        android:strokeColor="#FFFFFFFF"\n'
                       f'        android:strokeWidth="{STROKE}"\n        android:strokeLineCap="round"\n'
                       '        android:strokeLineJoin="round" />')
        else:
            out.append(f'    <path\n        android:pathData="{d}"\n        android:fillColor="#FFFFFFFF" />')
    out.append("</vector>")
    return "\n".join(out) + "\n"


def to_svg(parts):
    body = []
    for kind, d in parts:
        if kind == "s":
            body.append(f'<path d="{d}" fill="none" stroke="currentColor" stroke-width="{STROKE}" '
                        'stroke-linecap="round" stroke-linejoin="round"/>')
        else:
            body.append(f'<path d="{d}" fill="currentColor"/>')
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">' + "".join(body) + "</svg>\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--svg-dir", help="also write SVG copies here, for browser proofs")
    args = ap.parse_args()
    for name, parts in GLYPHS.items():
        with open(os.path.join(RES, f"weather_{name}.xml"), "w") as fh:
            fh.write(to_vector(parts))
        if args.svg_dir:
            os.makedirs(args.svg_dir, exist_ok=True)
            with open(os.path.join(args.svg_dir, f"{name}.svg"), "w") as fh:
                fh.write(to_svg(parts))
    print(f"wrote {len(GLYPHS)} glyphs")


if __name__ == "__main__":
    main()
