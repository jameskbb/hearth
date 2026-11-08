#!/usr/bin/env python3
"""Composite a theme pack the way Hearth draws it, to check it on a computer.

  scripts/theme-compose.py PACK_DIR [options]

PACK_DIR holds theme.json and the images it names (docs/theme-packs.md). By
default this validates the manifest, prints the decoded and encoded size of
each layer, and writes PACK_DIR/preview.webp: the rest state at 480x270.

Options:
  --preview PATH        where to write the preview (default PACK_DIR/preview.webp)
  --no-preview          do not write a preview
  --rest PATH           also write the rest state at 1920x1080 (PNG)
  --compare IMAGE       report the mean absolute difference, in 8-bit levels,
                        between the 1920x1080 rest state and IMAGE
  --frames N            render N frames of the motion at the 960x540 canvas size
  --start-seconds S     begin motion samples at this elapsed time (default 0)
  --seconds S           spread the frames over S seconds (default 240)
  --out DIR             where the frames go (frame-000.png, ...)
  --contact-sheet PATH  one PNG grid of the frames, each labelled with its time
  --columns C           columns in the contact sheet (default 2)
  --weather ID          compose weather variant ID instead of the plain scene
                        (--rest, --compare and --frames then show the variant;
                        no preview is written unless --preview is given)
  --all-weather         check every weather variant and write each one's rest
                        state to REVIEW/weather-<id>.webp (1920x1080, for
                        review only, never shipped)
  --review-dir REVIEW   where --all-weather writes (default
                        build/weather-review/<pack id>)

This file is the reference implementation of the placement and motion rules
in docs/theme-packs.md:

- Placement: a layer is scaled so its width spans the 960 dp canvas and its
  top edge sits `y` dp from the top. A layer that moves and does not wrap is
  overscanned: drawn 2 x travel dp wider (left edge at -travel), height scaled
  with it, top edge still at `y`, where travel = sway amplitude x parallax +
  drift x. The overscan applies at rest too, so the geometry never jumps.
  A wrapping layer is drawn 960 dp wide and repeated left to right.
- Sway: amplitude x sin(2 pi t / period), times the layer's parallax.
- Drift without wrap: x x sin(2 pi t / period). With wrap: x x t / period,
  modulo the layer's drawn width. Positive x moves right.
- Opacity: midpoint + (max - min) / 2 x sin(2 pi (t / period + phase)), so it
  starts at the midpoint (the rest state) with phase 0 and at max with 0.25.
- Fade: from + (to - from) x smoothstep((t - delay) / duration), clamped.
  The layer's alpha is opacity x fade.
- Fall: a layer with `fall` repeats top to bottom; its tiles start at
  (y + fall.y x t / fall.period) dp, reduced to one drawn image height, so
  it moves down continuously and always covers the canvas height.
- Rest state: no sway, no drift, no fall, opacity at its midpoint, fade at
  `to`.
- Weather variants (`weather`): the variant's layers are the base layers with
  `replace` swapping an image in place (keeping placement and motion, unless
  an `add` entry names the new image, whose fields are then used there),
  `hide` leaving layers out, and each other `add` entry inserted just after
  the base layer its `after` names (on top without one). Its palette changes
  only the colors it lists.
- Blending is source-over (normal) or screen, in sRGB values, as Android's
  Canvas does it.

Needs Pillow and numpy.
"""
import argparse
import json
import math
import os
import re
import sys

import numpy as np
from PIL import Image, ImageDraw

CANVAS_W, CANVAS_H = 960.0, 540.0
BASE_SIZE = (1920, 1080)
MAX_WIDTH = 1920
# The spec says "under 32 MB"; this checks the stricter decimal reading so a
# pack passes whichever way a renderer counts.
DECODED_LIMIT = 32_000_000
ID_RE = re.compile(r"^[a-z0-9-]{2,40}$")
COLOR_RE = re.compile(r"^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")
DAYPARTS = {"morning", "day", "evening", "night"}
VARIANT_ID_RE = re.compile(r"^[a-z0-9-]{1,32}$")
SKIES = {"clear", "cloudy", "fog", "rain", "snow", "storm"}
TEMPS = {"freezing", "cold", "mild", "warm", "hot"}
FORECASTS = {"snow", "rain", "none"}
VARIANT_COLORS = {"window", "ink", "inkMuted", "shade", "shadeMid"}
MAX_VARIANTS = 24
MAX_VARIANT_LAYERS = 10


class PackError(Exception):
    pass


# ---------------------------------------------------------------- manifest

def _num(obj, key, default=None, lo=None, hi=None, positive=False, where=""):
    v = obj.get(key, default)
    if v is None:
        return None
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        raise PackError(f"{where}{key} must be a number")
    v = float(v)
    if lo is not None and v < lo or hi is not None and v > hi:
        raise PackError(f"{where}{key} {v:g} is outside {lo:g} to {hi:g}")
    if positive and v <= 0:
        raise PackError(f"{where}{key} must be more than 0")
    return v


def load_manifest(pack):
    path = os.path.join(pack, "theme.json")
    if not os.path.isfile(path):
        raise PackError("missing theme.json")
    try:
        with open(path, encoding="utf-8") as f:
            m = json.load(f)
    except ValueError as e:
        raise PackError(f"theme.json is not valid JSON: {e}")
    if m.get("format") != 1:
        raise PackError("needs a newer Hearth" if isinstance(m.get("format"), int) and m["format"] > 1
                        else "format must be 1")
    pid = m.get("id")
    if not isinstance(pid, str) or not ID_RE.match(pid) or pid == "auto":
        raise PackError(f"id {pid!r} must be 2 to 40 of a-z, 0-9 and -, and not auto")
    name = m.get("name")
    if not isinstance(name, str) or not 0 < len(name) <= 40:
        raise PackError("name is required, up to 40 characters")
    for d in m.get("dayparts", []):
        if d not in DAYPARTS:
            raise PackError(f"unknown daypart {d!r}")
    pal = m.get("palette")
    if not isinstance(pal, dict):
        raise PackError("palette is required")
    for k in ("window", "accent"):
        if k not in pal:
            raise PackError(f"palette.{k} is required")
    for k, v in pal.items():
        if not isinstance(v, str) or not COLOR_RE.match(v):
            raise PackError(f"palette.{k} {v!r} is not #AARRGGBB or #RRGGBB")
    sway = (m.get("motion") or {}).get("sway") or {}
    m["_sway"] = (_num(sway, "amplitude", 0.0, lo=0, where="sway."),
                  _num(sway, "period", 90.0, positive=True, where="sway."))
    layers = m.get("layers")
    if not isinstance(layers, list) or not 1 <= len(layers) <= 8:
        raise PackError("layers must hold 1 to 8 layers")
    for i, L in enumerate(layers):
        check_layer(L, f"layers[{i}].")
    m["_weather"] = check_weather(m)
    return m


def check_layer(L, w):
    if not isinstance(L, dict) or not isinstance(L.get("image"), str):
        raise PackError(f"{w}image is required")
    _num(L, "y", 0.0, lo=-540, hi=540, where=w)
    _num(L, "parallax", 0.0, lo=0, hi=1, where=w)
    if L.get("blend", "normal") not in ("normal", "screen"):
        raise PackError(f"{w}blend must be normal or screen")
    if "drift" in L:
        _num(L["drift"], "x", None, where=w + "drift.")
        _num(L["drift"], "period", None, positive=True, where=w + "drift.")
    if "opacity" in L:
        o = L["opacity"]
        lo_ = _num(o, "min", None, lo=0, hi=1, where=w + "opacity.")
        hi_ = _num(o, "max", None, lo=0, hi=1, where=w + "opacity.")
        _num(o, "period", None, positive=True, where=w + "opacity.")
        _num(o, "phase", 0.0, lo=0, hi=1, where=w + "opacity.")
        if lo_ is None or hi_ is None or lo_ > hi_:
            raise PackError(f"{w}opacity needs min <= max")
    if "fade" in L:
        fd = L["fade"]
        _num(fd, "from", None, lo=0, hi=1, where=w + "fade.")
        _num(fd, "to", None, lo=0, hi=1, where=w + "fade.")
        _num(fd, "delay", None, lo=0, where=w + "fade.")
        _num(fd, "duration", None, positive=True, where=w + "fade.")
    if "fall" in L:
        f = L["fall"]
        if not isinstance(f, dict):
            raise PackError(f"{w}fall must be an object")
        y = _num(f, "y", None, lo=0, hi=540, where=w + "fall.")
        if y is None or y <= 0:
            raise PackError(f"{w}fall.y must be more than 0")
        if _num(f, "period", None, positive=True, where=w + "fall.") is None:
            raise PackError(f"{w}fall.period is required")


def check_weather(m):
    """The optional weather array, checked as ThemeManifestCodec checks it. Returns it."""
    weather = m.get("weather", [])
    if not isinstance(weather, list) or len(weather) > MAX_VARIANTS:
        raise PackError(f"weather must be a list of at most {MAX_VARIANTS} variants")
    base = {L["image"] for L in m["layers"]}
    ids = set()
    for i, v in enumerate(weather):
        if not isinstance(v, dict):
            raise PackError(f"weather[{i}] must be an object")
        vid = v.get("id")
        if not isinstance(vid, str) or not VARIANT_ID_RE.match(vid) or vid in ids:
            raise PackError(f"weather[{i}].id {vid!r} must be 1 to 32 of a-z, 0-9 and -, unique in the pack")
        ids.add(vid)
        w = f"weather.{vid}."
        when = v.get("when", {})
        if not isinstance(when, dict):
            raise PackError(f"{w}when must be an object")
        for key, allowed in (("sky", SKIES), ("temp", TEMPS), ("forecast", FORECASTS)):
            if key in when:
                vals = when[key]
                if not isinstance(vals, list) or not vals or any(x not in allowed for x in vals):
                    raise PackError(f"{w}when.{key} must be a non-empty list of {', '.join(sorted(allowed))}")
        pal = v.get("palette", {})
        if not isinstance(pal, dict):
            raise PackError(f"{w}palette must be an object")
        for k, c in pal.items():
            if k not in VARIANT_COLORS:
                raise PackError(f"{w}palette.{k} cannot change in a variant (only {', '.join(sorted(VARIANT_COLORS))})")
            if not isinstance(c, str) or not COLOR_RE.match(c):
                raise PackError(f"{w}palette.{k} {c!r} is not #AARRGGBB or #RRGGBB")
        rep = v.get("replace", {})
        if not isinstance(rep, dict):
            raise PackError(f"{w}replace must be an object")
        for k, img in rep.items():
            if k not in base or not isinstance(img, str):
                raise PackError(f"{w}replace {k!r} must name a base layer image and give an image")
        hide = v.get("hide", [])
        if not isinstance(hide, list) or any(h not in base or h in rep for h in hide):
            raise PackError(f"{w}hide must list base layer images that are not replaced")
        add = v.get("add", [])
        if not isinstance(add, list):
            raise PackError(f"{w}add must be a list")
        for j, L in enumerate(add):
            check_layer(L, f"{w}add[{j}].")
            if "after" in L and L["after"] not in base:
                raise PackError(f"{w}add[{j}].after must name a base layer image")
    return weather


def find_variant(m, vid):
    for v in m["_weather"]:
        if v["id"] == vid:
            return v
    raise PackError(f"no weather variant {vid!r} (has: {', '.join(v['id'] for v in m['_weather']) or 'none'})")


def resolve_layers(m, v):
    """The layer list drawn for variant v (None: the base), as ThemeVariants.layers resolves it."""
    if v is None:
        return list(m["layers"])
    rep = v.get("replace", {})
    hide = set(v.get("hide", []))
    add = v.get("add", [])
    full = {L["image"]: L for L in add if L["image"] in rep.values()}
    out = []
    for L in m["layers"]:
        if L["image"] not in hide:
            new = rep.get(L["image"])
            if new is None:
                out.append(L)
            elif new in full:
                out.append({k: x for k, x in full[new].items() if k != "after"})
            else:
                out.append(dict(L, image=new))
        out.extend(a for a in add if a.get("after") == L["image"] and a["image"] not in full)
    out.extend(a for a in add if "after" not in a and a["image"] not in full)
    return out


def variant_view(m, v):
    """A manifest-shaped dict for variant v: its resolved layers and palette."""
    if v is None:
        return m
    view = dict(m)
    view["layers"] = resolve_layers(m, v)
    view["palette"] = dict(m["palette"], **v.get("palette", {}))
    view["_variant"] = v["id"]
    return view


def warnings_for(m):
    out = []
    amp, per = m["_sway"]
    if amp and per < 20:
        out.append(f"sway period {per:g} s is under 20 s")
    for L in m["layers"]:
        d = L.get("drift")
        if d and (d["period"] < 20 or abs(d["x"]) > 120):
            out.append(f"{L['image']}: drift {d['x']:g} dp per {d['period']:g} s is faster than the spec advises")
        o = L.get("opacity")
        if o and o["period"] < 20:
            out.append(f"{L['image']}: opacity period {o['period']:g} s is under 20 s")
    return out


def load_images(pack, m, cache=None):
    """The images of m's layers (a variant view works too), checked like ThemeValidation."""
    cache = {} if cache is None else cache
    where = f"weather variant {m['_variant']}: " if "_variant" in m else ""
    n = len(m["layers"])
    if "_variant" in m and not 1 <= n <= MAX_VARIANT_LAYERS:
        raise PackError(f"{where}{n} layers, 1 to {MAX_VARIANT_LAYERS} allowed")
    imgs = []
    for i, L in enumerate(m["layers"]):
        name = L["image"]
        if name not in cache:
            p = os.path.join(pack, name)
            if not os.path.isfile(p):
                raise PackError(f"{where}layer {name} not found")
            im = Image.open(p)
            if im.width > MAX_WIDTH:
                raise PackError(f"{where}layer {name} is wider than {MAX_WIDTH} px")
            cache[name] = im.convert("RGBA")
        im = cache[name]
        if i == 0:
            if im.size != BASE_SIZE:
                raise PackError(f"{where}base layer {name} must be 1920x1080, not {im.width}x{im.height}")
            if np.asarray(im)[..., 3].min() < 255:
                raise PackError(f"{where}base layer {name} must be opaque")
        imgs.append(im)
    # An image used by two layers is decoded once.
    unique = {L["image"]: im for L, im in zip(m["layers"], imgs)}
    decoded = sum(im.width * im.height * 4 for im in unique.values())
    if decoded >= DECODED_LIMIT:
        raise PackError(f"{where}decoded layers exceed 32 MB ({decoded} bytes)")
    return imgs


def check_variant_images(pack, m, v, cache):
    """Every image v names exists and fits, and at most one replacement is full size."""
    names = list(v.get("replace", {}).values()) + [L["image"] for L in v.get("add", [])]
    full = 0
    for name in dict.fromkeys(names):
        if name not in cache:
            p = os.path.join(pack, name)
            if not os.path.isfile(p):
                raise PackError(f"weather variant {v['id']}: image {name} not found")
            im = Image.open(p)
            if im.width > MAX_WIDTH:
                raise PackError(f"weather variant {v['id']}: image {name} is wider than {MAX_WIDTH} px")
            cache[name] = im.convert("RGBA")
    for name in set(v.get("replace", {}).values()):
        if cache[name].width == MAX_WIDTH:
            full += 1
    if full > 1:
        raise PackError(f"weather variant {v['id']}: {full} full-size replacements, at most 1 allowed")
    return load_images(pack, variant_view(m, v), cache)


# ---------------------------------------------------------------- motion

def travel(L, sway):
    """Overscan in dp on each side for a layer that moves and does not wrap."""
    if L.get("wrap"):
        return 0.0
    t = sway[0] * L.get("parallax", 0.0)
    if L.get("drift"):
        t += abs(L["drift"]["x"])
    return t


def fall_offset(L, t, rest):
    """Downward travel in dp of a falling layer at t (0 at rest or without fall)."""
    f = L.get("fall")
    if not f or rest:
        return 0.0
    return f["y"] * t / f["period"]


def _shift_down(arr, dy_px):
    """Roll an image down by a fractional number of pixels, wrapping (linear filter)."""
    return _shift(arr.swapaxes(0, 1), dy_px, True).swapaxes(0, 1)


def x_offset(L, sway, t, rest):
    if rest:
        return 0.0
    amp, per = sway
    x = amp * math.sin(2 * math.pi * t / per) * L.get("parallax", 0.0)
    d = L.get("drift")
    if d:
        if L.get("wrap"):
            x += d["x"] * t / d["period"]
        else:
            x += d["x"] * math.sin(2 * math.pi * t / d["period"])
    return x


def smoothstep(e):
    e = min(1.0, max(0.0, e))
    return e * e * (3 - 2 * e)


def alpha(L, t, rest):
    a = 1.0
    o = L.get("opacity")
    if o:
        if rest:
            a = (o["min"] + o["max"]) / 2
        else:
            s_ = math.sin(2 * math.pi * (t / o["period"] + o.get("phase", 0.0)))
            a = (o["min"] + o["max"]) / 2 + (o["max"] - o["min"]) / 2 * s_
    f = L.get("fade")
    if f:
        if rest:
            a *= f["to"]
        else:
            a *= f["from"] + (f["to"] - f["from"]) * smoothstep((t - f["delay"]) / f["duration"])
    return a


# ---------------------------------------------------------------- drawing

class Layer:
    def __init__(self, spec, img, sway, scale):
        """scale: output pixels per dp."""
        self.spec = spec
        self.T = travel(spec, sway)
        self.wrap = bool(spec.get("wrap"))
        wdp = CANVAS_W + 2 * self.T
        self.wdp = wdp
        self.hdp = wdp * img.height / img.width
        pw = max(1, round(wdp * scale))
        ph = max(1, round(self.hdp * scale))
        r = img.resize((pw, ph), Image.LANCZOS)
        a = np.asarray(r).astype(np.float32) / 255.0
        # Pillow resamples RGBA premultiplied; premultiply here for compositing.
        self.rgb = a[..., :3] * a[..., 3:4]
        self.a = a[..., 3]
        self.scale = scale


def _shift(arr, dx_px, wrap):
    """Shift an image right by a fractional number of pixels (linear filter)."""
    i = math.floor(dx_px)
    f = dx_px - i
    if wrap:
        a = np.roll(arr, i, axis=1)
        b = np.roll(arr, i + 1, axis=1)
    else:
        a = _int_shift(arr, i)
        b = _int_shift(arr, i + 1)
    return a * (1 - f) + b * f


def _int_shift(arr, i):
    out = np.zeros_like(arr)
    w = arr.shape[1]
    if i >= 0:
        if i < w:
            out[:, i:] = arr[:, :w - i]
    else:
        if -i < w:
            out[:, :w + i] = arr[:, -i:]
    return out


def composite(layers, sway, t, out_w, out_h, rest):
    scale = out_w / CANVAS_W
    acc = np.zeros((out_h, out_w, 3), dtype=np.float32)
    for L in layers:
        s = L.spec
        al = alpha(s, t, rest)
        if al <= 0:
            continue
        xo = x_offset(s, sway, t, rest)
        top = s.get("y", 0.0) * scale
        ty = int(round(top))
        if L.wrap:
            dx = (xo % L.wdp) * scale
            rgb = _shift(L.rgb, dx, True)
            a = _shift(L.a[..., None], dx, True)[..., 0]
            # Tile to cover the canvas width.
            reps = math.ceil(out_w / rgb.shape[1])
            rgb = np.tile(rgb, (1, reps, 1))[:, :out_w]
            a = np.tile(a, (1, reps))[:, :out_w]
        else:
            dx = (xo - L.T) * scale
            pad = int(math.ceil(abs(dx))) + 2
            # Place on a strip as wide as the canvas.
            w = L.rgb.shape[1]
            full = np.zeros((L.rgb.shape[0], out_w + 2 * pad + w, 3), np.float32)
            fa = np.zeros((L.rgb.shape[0], out_w + 2 * pad + w), np.float32)
            full[:, pad:pad + w] = L.rgb
            fa[:, pad:pad + w] = L.a
            full = _shift(full, dx, False)
            fa = _shift(fa[..., None], dx, False)[..., 0]
            rgb = full[:, pad:pad + out_w]
            a = fa[:, pad:pad + out_w]
        if s.get("fall"):
            # Repeat top to bottom from (y + fall) reduced to one image height.
            h = rgb.shape[0]
            dy = ((s.get("y", 0.0) + fall_offset(s, t, rest)) * scale) % h
            rgb = _shift_down(rgb, dy)
            a = _shift_down(a[..., None], dy)[..., 0]
            reps = math.ceil(out_h / h)
            rgb = np.tile(rgb, (reps, 1, 1))[:out_h]
            a = np.tile(a, (reps, 1))[:out_h]
            ty = 0
        # Vertical placement and clipping.
        y0, y1 = max(0, ty), min(out_h, ty + rgb.shape[0])
        if y1 <= y0:
            continue
        src_rgb = rgb[y0 - ty:y1 - ty] * al
        src_a = a[y0 - ty:y1 - ty, :, None] * al
        dst = acc[y0:y1]
        if s.get("blend", "normal") == "screen":
            acc[y0:y1] = dst + src_rgb - dst * src_rgb
        else:
            acc[y0:y1] = src_rgb + dst * (1 - src_a)
    return Image.fromarray(np.clip(np.rint(acc * 255), 0, 255).astype(np.uint8), "RGB")


def build_layers(m, imgs, out_w):
    return [Layer(s, im, m["_sway"], out_w / CANVAS_W) for s, im in zip(m["layers"], imgs)]


def contact_sheet(frames, times, columns):
    fw, fh = frames[0].size
    label = 22
    rows = math.ceil(len(frames) / columns)
    sheet = Image.new("RGB", (columns * fw + (columns - 1) * 4, rows * (fh + label) + (rows - 1) * 4), (18, 18, 18))
    d = ImageDraw.Draw(sheet)
    for i, (f, t) in enumerate(zip(frames, times)):
        x = (i % columns) * (fw + 4)
        y = (i // columns) * (fh + label + 4)
        d.text((x + 6, y + 5), f"t = {t:.0f} s", fill=(220, 220, 220))
        sheet.paste(f, (x, y + label))
    return sheet


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("pack")
    ap.add_argument("--preview")
    ap.add_argument("--no-preview", action="store_true")
    ap.add_argument("--rest")
    ap.add_argument("--compare")
    ap.add_argument("--frames", type=int, default=0)
    ap.add_argument("--start-seconds", type=float, default=0.0)
    ap.add_argument("--seconds", type=float, default=240.0)
    ap.add_argument("--out")
    ap.add_argument("--contact-sheet")
    ap.add_argument("--columns", type=int, default=2)
    ap.add_argument("--weather")
    ap.add_argument("--all-weather", action="store_true")
    ap.add_argument("--review-dir")
    args = ap.parse_args()
    if args.frames < 0 or args.start_seconds < 0 or args.seconds < 0:
        ap.error("frame count and sample times must be nonnegative")

    cache = {}
    try:
        base = load_manifest(args.pack)
        load_images(args.pack, base, cache)
        variants = {}
        for v in base["_weather"]:
            variants[v["id"]] = check_variant_images(args.pack, base, v, cache)
        m = variant_view(base, find_variant(base, args.weather)) if args.weather else base
        imgs = load_images(args.pack, m, cache)
    except PackError as e:
        print(f"{args.pack}: {e}", file=sys.stderr)
        return 1
    if args.weather and not args.preview:
        args.no_preview = True

    if base["_weather"]:
        print(f"{base['id']}: weather variants {', '.join(variants)}")
    if args.all_weather:
        review = args.review_dir or os.path.join("build", "weather-review", base["id"])
        os.makedirs(review, exist_ok=True)
        for v in base["_weather"]:
            view = variant_view(base, v)
            rest = composite(build_layers(view, variants[v["id"]], 1920), view["_sway"], 0.0, 1920, 1080, rest=True)
            dst = os.path.join(review, f"weather-{v['id']}.webp")
            rest.save(dst, "WEBP", quality=90, method=6)
            print(f"  weather {v['id']}: {len(view['layers'])} layers, rest state {dst}")

    decoded = encoded = 0
    label = f"{m['id']} weather {args.weather}" if args.weather else m["id"]
    print(f"{label}: {len(imgs)} layers")
    seen = set()
    for s, im in zip(m["layers"], imgs):
        p = os.path.join(args.pack, s["image"])
        n = im.width * im.height * 4
        if s["image"] not in seen:
            decoded += n
            encoded += os.path.getsize(p)
            seen.add(s["image"])
        print(f"  {s['image']:24} {im.width:5d}x{im.height:<5d} decoded {n:>10,d}  file {os.path.getsize(p):>10,d}")
    print(f"  total decoded {decoded:,d} bytes ({decoded / 2**20:.1f} MiB), files {encoded:,d} bytes")
    for w in warnings_for(m):
        print(f"  warning: {w}")

    full = None
    if args.rest or args.compare or not args.no_preview:
        full = composite(build_layers(m, imgs, 1920), m["_sway"], 0.0, 1920, 1080, rest=True)
    if args.rest:
        full.save(args.rest)
        print(f"  rest state: {args.rest}")
    if not args.no_preview:
        dst = args.preview or os.path.join(args.pack, "preview.webp")
        full.resize((480, 270), Image.LANCZOS).save(dst, "WEBP", quality=90, method=6)
        print(f"  preview: {dst} ({os.path.getsize(dst):,d} bytes)")
    if args.compare:
        ref = Image.open(args.compare).convert("RGB")
        if ref.size != full.size:
            ref = ref.resize(full.size, Image.LANCZOS)
        d = np.abs(np.asarray(full).astype(np.int16) - np.asarray(ref).astype(np.int16))
        print(f"  compare with {os.path.basename(args.compare)}: mean abs diff {d.mean():.3f} levels, "
              f"p99 {np.percentile(d.max(axis=2), 99):.0f}, max {d.max()}")

    if args.frames:
        if not args.out and not args.contact_sheet:
            ap.error("--frames needs --out or --contact-sheet")
        layers = build_layers(m, imgs, 960)
        n = args.frames
        times = [args.start_seconds + (args.seconds * i / (n - 1) if n > 1 else 0.0) for i in range(n)]
        frames = [composite(layers, m["_sway"], t, 960, 540, rest=False) for t in times]
        if args.out:
            os.makedirs(args.out, exist_ok=True)
            for i, f in enumerate(frames):
                f.save(os.path.join(args.out, f"frame-{i:03d}.png"))
            print(f"  {n} frames: {args.out}")
        if args.contact_sheet:
            contact_sheet(frames, times, args.columns).save(args.contact_sheet)
            print(f"  contact sheet: {args.contact_sheet}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
