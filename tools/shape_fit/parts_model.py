#!/usr/bin/env python3
"""A building described as parts (boxes, cylinders, roofs, domes), built into voxels and painted from its drawing
(docs/specs/BUILDING_PARTS.md).

    python parts_model.py <spec.json> <drawing.png> <out dir> [--name NAME]

<drawing.png> is the building's drawing at 16 px a cell (W x D px: the grid's width and depth). The spec:

    {"height": 64,                        voxels tall at most (8 a block)
     "parts": [                           in order: later parts add to (or carve from) earlier ones
       {"shape": "box", "x": [x0, x1], "z": [z0, z1], "y": [y0, y1], "paint": {...}},
       {"shape": "cylinder", "center": [cx, cz], "radius": r, "y": [y0, y1], "paint": {...}},
       {"shape": "gable", "x": [..], "z": [..], "y": [y0, y1], "ridge": "x" | "z", "paint": {...}},
       {"shape": "hip", "x": [..], "z": [..], "y": [y0, y1], "paint": {...}},
       {"shape": "dome", "center": [cx, cz], "radius": r, "y": [y0, y1], "paint": {...}},
       {"shape": "cone", "center": [cx, cz], "radius": r, "y": [y0, y1], "paint": {...}},
       {"shape": "box", ..., "carve": true}            cuts (a door's recess, an alcove)
     ]}

Coordinates: x 0..W west to east and z 0..D north to south in drawing pixels (one voxel each), y up from the ground
in voxels (8 a block); ranges include their first value and exclude their last. A gable's ridge runs along the axis
it names (its slopes face the other way); a hip slopes on all four sides; a dome is a half sphere (y[1] - y[0] its
height); a cone narrows to a point.

Paint: "front": [px0, py0, px1, py1], a rectangle of the drawing (pixels, last excluded) stretched over the part's
south face (its width over the face's width, its height over the face's height, top row at the top); "top": a
rectangle over the part's upward faces seen from above (its width over x, its height over z); "side": over the east
and west faces (default: the front rectangle's outer columns); "back": over the north face (default: "side"). A voxel
shows the rectangle of its most open face: up, then south, east/west, north, down.

Writes <out>/<name>.json (kb-hub's viewer), <name>_view.png (3D from the south-east, above) and <name>_front.png (straight
from the south, to set beside the drawing).
"""
from __future__ import annotations

import argparse
import base64
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

FACES = (("up", (1, 0, 0)), ("south", (0, 1, 0)), ("east", (0, 0, 1)), ("west", (0, 0, -1)), ("north", (0, -1, 0)),
         ("down", (-1, 0, 0)))


def inside(part: dict, x: np.ndarray, y: np.ndarray, z: np.ndarray) -> np.ndarray:
    """Which voxel centres (x + 0.5 ...) lie in a part."""
    X, Y, Z = x + 0.5, y + 0.5, z + 0.5
    s = part["shape"]
    if s in ("box", "gable", "hip"):
        x0, x1 = part["x"]
        z0, z1 = part["z"]
        y0, y1 = part["y"]
        m = (X >= x0) & (X <= x1) & (Z >= z0) & (Z <= z1) & (Y >= y0) & (Y <= y1)
        if s == "box":
            return m
        t = (Y - y0) / max(1e-6, y1 - y0)  # 0 at the eaves, 1 at the top
        if s == "gable":
            if part.get("ridge", "x") == "x":
                half = (z1 - z0) / 2
                return m & (np.abs(Z - (z0 + z1) / 2) <= half * (1 - t) + 1e-6)
            half = (x1 - x0) / 2
            return m & (np.abs(X - (x0 + x1) / 2) <= half * (1 - t) + 1e-6)
        hx, hz = (x1 - x0) / 2, (z1 - z0) / 2
        inset = min(hx, hz) * t
        return m & (np.abs(X - (x0 + x1) / 2) <= hx - inset + 1e-6) & (np.abs(Z - (z0 + z1) / 2) <= hz - inset + 1e-6)
    cx, cz = part["center"]
    r = part["radius"]
    y0, y1 = part["y"]
    d = np.sqrt((X - cx) ** 2 + (Z - cz) ** 2)
    m = (Y >= y0) & (Y <= y1)
    if s == "cylinder":
        return m & (d <= r)
    if s == "dome":
        h = max(1e-6, y1 - y0)
        return m & ((d / r) ** 2 + ((Y - y0) / h) ** 2 <= 1)
    if s == "cone":
        t = (Y - y0) / max(1e-6, y1 - y0)
        return m & (d <= r * (1 - t))
    raise ValueError(f"unknown shape {s}")


def bounds(part: dict) -> tuple[float, float, float, float]:
    if "x" in part:
        return part["x"][0], part["x"][1], part["z"][0], part["z"][1]
    cx, cz = part["center"]
    r = part["radius"]
    return cx - r, cx + r, cz - r, cz + r


def pick(rect, a: float, b: float, draw: np.ndarray) -> tuple[int, int]:
    """The drawing pixel at fractions (a across, b down) of a rectangle."""
    x0, y0, x1, y1 = rect
    u = int(x0 + min(0.999, max(0.0, a)) * (x1 - x0))
    v = int(y0 + min(0.999, max(0.0, b)) * (y1 - y0))
    return min(draw.shape[0] - 1, max(0, v)), min(draw.shape[1] - 1, max(0, u))


def build(spec: dict, draw: np.ndarray):
    D, W = draw.shape[0], draw.shape[1]
    H = int(spec.get("height", 64))
    y, z, x = np.meshgrid(np.arange(H), np.arange(D), np.arange(W), indexing="ij")
    occ = np.zeros((H, D, W), bool)
    owner = np.full((H, D, W), -1, int)
    for i, part in enumerate(spec["parts"]):
        m = inside(part, x, y, z)
        if part.get("carve"):
            occ &= ~m
            owner[m] = -1
        else:
            occ |= m
            owner[m] = i
    col = np.zeros((H, D, W, 3), np.uint8)
    for yy, zz, xx in np.argwhere(occ):
        part = spec["parts"][owner[yy, zz, xx]]
        paint = part.get("paint", {})
        bx0, bx1, bz0, bz1 = bounds(part)
        py0, py1 = part["y"]
        face = "south"
        for name, (dy, dz, dx) in FACES:
            ny, nz, nx = yy + dy, zz + dz, xx + dx
            if not (0 <= ny < H and 0 <= nz < D and 0 <= nx < W) or not occ[ny, nz, nx]:
                face = name
                break
        front = paint.get("front") or paint.get("top") or [0, 0, W, D]
        across = (xx + 0.5 - bx0) / max(1e-6, bx1 - bx0)
        down = 1 - (yy + 0.5 - py0) / max(1e-6, py1 - py0)
        if face == "up" and paint.get("top"):
            r, c = pick(paint["top"], across, (zz + 0.5 - bz0) / max(1e-6, bz1 - bz0), draw)
        elif face in ("east", "west"):
            side = paint.get("side")
            if side:
                r, c = pick(side, (zz + 0.5 - bz0) / max(1e-6, bz1 - bz0), down, draw)
            else:
                r, c = pick(front, 0.02 if face == "west" else 0.98, down, draw)
        elif face == "north":
            back = paint.get("back") or paint.get("side")
            r, c = pick(back, 1 - across, down, draw) if back else pick(front, 0.5, down, draw)
        else:
            r, c = pick(front, across, down, draw)
        col[yy, zz, xx] = draw[r, c]
    return occ, col


def picture(occ, col, path, s=5):
    GY, GZ, GX = occ.shape

    def p(x, y, z):
        return ((x - z) * s, (x + z) * s / 2 - y * s * 1.15)

    xs = [p(a, b, c)[0] for a in (0, GX) for b in (0, GY) for c in (0, GZ)]
    ys = [p(a, b, c)[1] for a in (0, GX) for b in (0, GY) for c in (0, GZ)]
    ox, oy = -min(xs) + 20, -min(ys) + 20
    img = Image.new("RGB", (int(max(xs) - min(xs)) + 40, int(max(ys) - min(ys)) + 40), (40, 44, 52))
    d = ImageDraw.Draw(img)
    q = lambda x, y, z: (p(x, y, z)[0] + ox, p(x, y, z)[1] + oy)  # noqa: E731
    for yv, zv, xv in np.argwhere(occ):
        c = col[yv, zv, xv].astype(int)
        if yv + 1 >= GY or not occ[yv + 1, zv, xv]:
            d.polygon([q(xv, yv + 1, zv), q(xv + 1, yv + 1, zv), q(xv + 1, yv + 1, zv + 1), q(xv, yv + 1, zv + 1)], fill=tuple(c))
        if zv + 1 >= GZ or not occ[yv, zv + 1, xv]:
            d.polygon([q(xv, yv, zv + 1), q(xv + 1, yv, zv + 1), q(xv + 1, yv + 1, zv + 1), q(xv, yv + 1, zv + 1)], fill=tuple((c * 0.86).astype(int)))
        if xv + 1 >= GX or not occ[yv, zv, xv + 1]:
            d.polygon([q(xv + 1, yv, zv), q(xv + 1, yv, zv + 1), q(xv + 1, yv + 1, zv + 1), q(xv + 1, yv + 1, zv)], fill=tuple((c * 0.7).astype(int)))
    img.save(path)


def front_view(occ, col, path, scale=4):
    """Straight from the south (orthographic): each column's nearest voxel."""
    GY, GZ, GX = occ.shape
    img = np.full((GY, GX, 3), 40, np.uint8)
    for yv in range(GY):
        for xv in range(GX):
            zs = np.nonzero(occ[yv, :, xv])[0]
            if len(zs):
                img[GY - 1 - yv, xv] = col[yv, zs.max(), xv]
    Image.fromarray(img).resize((GX * scale, GY * scale), Image.NEAREST).save(path)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("spec")
    ap.add_argument("drawing")
    ap.add_argument("out")
    ap.add_argument("--name", default="")
    args = ap.parse_args()
    spec = json.loads(Path(args.spec).read_text(encoding="utf-8"))
    draw = np.asarray(Image.open(args.drawing).convert("RGB"))
    occ, col = build(spec, draw)
    name = args.name or Path(args.spec).stem
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    q = Image.fromarray(col.reshape(-1, 1, 3)).quantize(colors=64, method=Image.Quantize.MEDIANCUT)
    pal = ((q.getpalette() or []) + [0] * 192)[:192]
    shade = np.where(occ.reshape(-1), np.asarray(q).reshape(-1), 255).astype(np.uint8)
    H, D, W = occ.shape
    view = {"title": f"parts model: {name}", "dims": [W, H, D],
            "voxels": base64.b64encode(np.packbits(occ.astype(np.uint8).ravel(), bitorder="little").tobytes()).decode(),
            "shades": base64.b64encode(shade.tobytes()).decode(),
            "palette": ["#%02x%02x%02x" % tuple(pal[i * 3:i * 3 + 3]) for i in range(64)], "voxelsPerBlock": 8}
    (out / f"{name}.json").write_text(json.dumps(view), encoding="utf-8")
    picture(occ, col, out / f"{name}_view.png")
    front_view(occ, col, out / f"{name}_front.png")
    print(name, f"{W}x{H}x{D} voxels", int(occ.sum()), "solid;", len(spec["parts"]), "parts")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
