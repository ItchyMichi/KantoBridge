#!/usr/bin/env python3
"""Landscape sections as parts (docs/specs/LANDSCAPE_MODELS.md): each model built into voxels, painted from the window's
drawing, kept to its own cells, and the window's scene.

    python terrain_model.py <package dir> [--model NAME ...] [--scene]

<package dir> holds package.json and drawing.png (tools/landscape_sections/package.py) and specs/<name>.json, one per
model: the parts spec of docs/specs/BUILDING_PARTS.md in the window's frame (x 0..W west to east and z 0..D north to
south in drawing pixels, 16 a cell; y up in voxels, 8 a block), plus:

    "sections": [id, ...]     the marked sections the model stands on: every voxel over another cell is cleared, over a
                              door's cell only below 24 voxels (a cave mouth keeps 3 blocks of headroom)
    {"shape": "trees", "cells": [[cx, cy], ...] or "sections": [id, ...],
     "trunk": {"radius": 2, "height": 8, "paint": [px0, py0, px1, py1]},
     "crown": {"shape": "dome" | "cone", "radius": 9, "height": 20, "rows": [0, 1]}}
                              a trunk and a crown on each cell (window cells), the crown painted with the drawing of the
                              cell's rows "rows" (cells relative to the tree's: [0, 1] its own, [-1, 1] the one above too)

Writes <package>/out/<name>.json (kb-hub's viewer, cropped to the model), <name>_view.png (3D from the south-east, above),
<name>_front.png (straight from the south) and <name>_game.png (the game's own three-quarter view: set it beside the
drawing, they should read alike); with --scene, scene.json (every model on the window's ground, 4 voxels a block) and
scene_view.png, scene_game.png. Prints per model its size, voxels, highest voxel and the voxels the footprint cleared.

With levels in the package (package.py --levels: the levels pilot), the scene's ground stands at each cell's level (a
ramp climbing across its cell) and each model on its base, the lowest of its sections' (its voxels count from there, as
the game places it); a model's own pictures stay on the ground.
"""
from __future__ import annotations

import argparse
import base64
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

# Emerald's copy: the one the game's PartsBuilding matches (sides and backs repeat their art at its own scale)
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "shape_fit" / "emerald"))
from parts_model import FACES, arc, bounds, inside, pick, profile_of, radii  # noqa: E402

# A door's cell keeps this many voxels of headroom (3 blocks): a cave mouth's arch may reach over it above that.
DOOR_CLEAR = 24
MAX_HEIGHT = 160
# The game's view: a face's drawn row is 1.5 voxels tall (the buildings' scale, BUILDING_PARTS.md), so a voxel y high is
# drawn y / 1.5 pixels up the screen.
ROW_VOXELS = 1.5


def cells_of(package: dict, ids) -> list[list[int]]:
    out = []
    for s in package["sections"]:
        if s["id"] in ids:
            out.extend(s["cells"])
    return out


def expand(spec: dict, package: dict) -> list[dict]:
    """The spec's parts, each "trees" part made into a trunk and a crown on each of its cells."""
    px = package["px"]
    out = []
    for part in spec["parts"]:
        if part["shape"] != "trees":
            out.append(part)
            continue
        cells = part.get("cells") or cells_of(package, part.get("sections", []))
        trunk = part.get("trunk", {})
        crown = part.get("crown", {})
        tr, th = float(trunk.get("radius", 2)), float(trunk.get("height", 8))
        cr, ch = float(crown.get("radius", px * 0.6)), float(crown.get("height", 20))
        shape = crown.get("shape", "dome")
        r0, r1 = crown.get("rows", [0, 1])
        for cx, cy in cells:
            mx, mz = cx * px + px / 2, cy * px + px / 2
            own = [cx * px, cy * px, cx * px + px, cy * px + px]
            out.append({"shape": "cylinder", "center": [mx, mz + px * 0.15], "radius": tr, "y": [0, th + 2],
                        "paint": {"front": trunk.get("paint", own), "top": own, "wrap": False}})
            rect = [cx * px, (cy + r0) * px, cx * px + px, (cy + r1) * px]
            out.append({"shape": shape, "center": [mx, mz], "radius": cr, "y": [th, th + ch], "paint": {"front": rect, "top": rect}})
    return out


def part_box(part: dict, H: int, D: int, W: int) -> tuple[slice, slice, slice]:
    """The voxel box a part can fill (y, z, x slices), so each part is computed over its own box only."""
    x0, x1, z0, z1 = bounds(part)
    y0 = part["y"][0]
    if part["shape"] in ("profile", "barrel"):
        prof, cross, _ = profile_of(part)
        y1 = y0 + max(prof) + 1
    else:
        y1 = part["y"][1]
    clip = lambda a, b, n: slice(max(0, int(math.floor(a)) - 1), min(n, int(math.ceil(b)) + 1))  # noqa: E731
    return clip(y0, y1, H), clip(z0, z1, D), clip(x0, x1, W)


def build(parts: list[dict], draw: np.ndarray, H: int, allowed: np.ndarray, doors: np.ndarray):
    """Occupancy, owner part and colour per voxel (H, D, W); allowed/doors per drawing pixel (D, W). Returns also the
    voxels the footprint cleared."""
    D, W = draw.shape[0], draw.shape[1]
    occ = np.zeros((H, D, W), bool)
    owner = np.full((H, D, W), -1, np.int32)
    for i, part in enumerate(parts):
        ys, zs, xs = part_box(part, H, D, W)
        if ys.start >= ys.stop or zs.start >= zs.stop or xs.start >= xs.stop:
            continue
        y = np.arange(ys.start, ys.stop).reshape(-1, 1, 1)
        z = np.arange(zs.start, zs.stop).reshape(1, -1, 1)
        x = np.arange(xs.start, xs.stop).reshape(1, 1, -1)
        m = np.broadcast_to(inside(part, x, y, z), (ys.stop - ys.start, zs.stop - zs.start, xs.stop - xs.start))
        if part.get("carve"):
            occ[ys, zs, xs] &= ~m
            owner[ys, zs, xs][m] = -1
        else:
            occ[ys, zs, xs] |= m
            owner[ys, zs, xs][m] = i
    keep = allowed[None, :, :] | (doors[None, :, :] & (np.arange(H) >= DOOR_CLEAR)[:, None, None])
    cleared = int((occ & ~keep).sum())
    occ &= keep
    exposed = exposed_of(occ)
    col = np.zeros((H, D, W, 3), np.uint8)
    for yy, zz, xx in np.argwhere(exposed):
        part = parts[owner[yy, zz, xx]]
        paint = part.get("paint", {})
        bx0, bx1, bz0, bz1 = bounds(part)
        py0 = part["y"][0]
        py1 = part["y"][1] if len(part["y"]) > 1 else py0 + 1
        face = "south"
        for name, (dy, dz, dx) in FACES:
            ny, nz, nx = yy + dy, zz + dz, xx + dx
            if ny < 0:
                continue
            if not (ny < H and 0 <= nz < D and 0 <= nx < W) or not occ[ny, nz, nx]:
                face = name
                break
        front = paint.get("front") or paint.get("top") or [0, 0, W, D]
        across = (xx + 0.5 - bx0) / max(1e-6, bx1 - bx0)
        if part["shape"] in ("cylinder", "cone", "dome") and face in ("south", "east", "west") and paint.get("wrap", True):
            cx, cz = part["center"]
            rx, rz = radii(part)
            ang = math.atan2((xx + 0.5 - cx) / rx, (zz + 0.5 - cz) / rz)
            across = 0.5 + max(-math.pi / 2, min(math.pi / 2, ang)) / math.pi
            face = "south"
        down = 1 - (yy + 0.5 - py0) / max(1e-6, py1 - py0)
        if face == "up" and paint.get("top") and part["shape"] in ("profile", "barrel"):
            prof, _, along = profile_of(part)
            if along == "x":
                t, span = (zz + 0.5 - bz0) / max(1e-6, bz1 - bz0), bz1 - bz0
            else:
                t, span = (xx + 0.5 - bx0) / max(1e-6, bx1 - bx0), bx1 - bx0
            a = arc(prof, span)
            f = float(np.interp(t, np.linspace(0, 1, len(a)), a))
            r, c = pick(paint["top"], across, f, draw) if along == "x" else pick(paint["top"], f, (zz + 0.5 - bz0) / max(1e-6, bz1 - bz0), draw)
        elif face == "up" and paint.get("top"):
            r, c = pick(paint["top"], across, (zz + 0.5 - bz0) / max(1e-6, bz1 - bz0), draw)
        elif face in ("east", "west"):
            # as the game's PartsBuilding: the side's rectangle (else the front's) repeated along it at its own scale
            side = paint.get("side") or front
            sw = max(1, int(side[2] - side[0]))
            r, c = pick(side, ((zz - int(bz0)) % sw + 0.5) / sw, down, draw)
        elif face == "north":
            back = paint.get("back") or paint.get("side") or front
            bw = max(1, int(back[2] - back[0]))
            r, c = pick(back, ((int(bx1) - 1 - xx) % bw + 0.5) / bw, down, draw)
        else:
            r, c = pick(front, across, down, draw)
        col[yy, zz, xx] = draw[r, c]
    return occ, exposed, col, cleared


def exposed_of(occ: np.ndarray) -> np.ndarray:
    """The voxels with a face open to the air (the ground under y = 0 counts as closed)."""
    pad = np.pad(occ, 1, constant_values=False)
    pad[0, 1:-1, 1:-1] = True
    closed = pad[2:, 1:-1, 1:-1] & pad[:-2, 1:-1, 1:-1] & pad[1:-1, 2:, 1:-1] & pad[1:-1, :-2, 1:-1] & pad[1:-1, 1:-1, 2:] & pad[1:-1, 1:-1, :-2]
    return occ & ~closed


def viewer(occ, exposed, col, title, per_block=8) -> dict:
    """kb-hub's viewer model: occupancy bits and a 64-colour shade per voxel (y, z, x order: index (y*d+z)*w+x)."""
    H, D, W = occ.shape
    shade = np.full(occ.shape, 255, np.uint8)
    pal = [0] * 192
    if exposed.any():
        q = Image.fromarray(col[exposed].reshape(-1, 1, 3)).quantize(colors=64, method=Image.Quantize.MEDIANCUT)
        pal = ((q.getpalette() or []) + [0] * 192)[:192]
        shade[occ] = 0
        shade[exposed] = np.asarray(q).reshape(-1)
    return {"title": title, "dims": [W, H, D],
            "voxels": base64.b64encode(np.packbits(occ.astype(np.uint8).ravel(), bitorder="little").tobytes()).decode(),
            "shades": base64.b64encode(shade.tobytes()).decode(),
            "palette": ["#%02x%02x%02x" % tuple(pal[i * 3:i * 3 + 3]) for i in range(64)], "voxelsPerBlock": per_block}


def picture(occ, exposed, col, path, budget=1400):
    """3D from the south-east, above: each exposed voxel's top, south and east faces, back to front."""
    GY, GZ, GX = occ.shape
    s = max(1.0, min(5.0, budget / max(1, GX + GZ)))

    def p(x, y, z):
        return ((x - z) * s, (x + z) * s / 2 - y * s * 1.15)

    xs = [p(a, b, c)[0] for a in (0, GX) for b in (0, GY) for c in (0, GZ)]
    ys = [p(a, b, c)[1] for a in (0, GX) for b in (0, GY) for c in (0, GZ)]
    ox, oy = -min(xs) + 20, -min(ys) + 20
    img = Image.new("RGB", (int(max(xs) - min(xs)) + 40, int(max(ys) - min(ys)) + 40), (40, 44, 52))
    d = ImageDraw.Draw(img)
    q = lambda x, y, z: (p(x, y, z)[0] + ox, p(x, y, z)[1] + oy)  # noqa: E731
    for yv, zv, xv in np.argwhere(exposed):
        c = col[yv, zv, xv].astype(int)
        if yv + 1 >= GY or not occ[yv + 1, zv, xv]:
            d.polygon([q(xv, yv + 1, zv), q(xv + 1, yv + 1, zv), q(xv + 1, yv + 1, zv + 1), q(xv, yv + 1, zv + 1)], fill=tuple(c))
        if zv + 1 >= GZ or not occ[yv, zv + 1, xv]:
            d.polygon([q(xv, yv, zv + 1), q(xv + 1, yv, zv + 1), q(xv + 1, yv + 1, zv + 1), q(xv, yv + 1, zv + 1)],
                      fill=tuple((c * 0.86).astype(int)))
        if xv + 1 >= GX or not occ[yv, zv, xv + 1]:
            d.polygon([q(xv + 1, yv, zv), q(xv + 1, yv, zv + 1), q(xv + 1, yv + 1, zv + 1), q(xv + 1, yv + 1, zv)],
                      fill=tuple((c * 0.7).astype(int)))
    img.save(path)


def front_view(occ, col, path, scale=3):
    """Straight from the south: each column's nearest voxel."""
    GY, GZ, GX = occ.shape
    has = occ.any(axis=1)
    near = GZ - 1 - np.argmax(occ[:, ::-1, :], axis=1)
    img = np.full((GY, GX, 3), 40, np.uint8)
    yy, xx = np.nonzero(has)
    img[yy, xx] = col[yy, near[yy, xx], xx]
    Image.fromarray(img[::-1]).resize((GX * scale, GY * scale), Image.NEAREST).save(path)


def game_view(occ, exposed, col, path, under=None, scale=2):
    """The game's own three-quarter view: a voxel at (x, y, z) drawn at (x, z - y / 1.5); of those on one pixel the
    nearest (the largest z + y / 1.5). Beside the drawing it should read alike. under: the drawing, shown where nothing
    stands."""
    GY, GZ, GX = occ.shape
    lift = int(math.ceil(GY / ROW_VOXELS)) + 1
    img = np.full((GZ + lift, GX, 3), 40, np.uint8)
    if under is not None:
        img[lift:lift + GZ] = under
    yv, zv, xv = np.nonzero(exposed)
    if len(yv):
        sy = np.floor(zv + 0.5 - (yv + 0.5) / ROW_VOXELS).astype(int) + lift
        key = zv + yv / ROW_VOXELS
        order = np.argsort(key)
        img[sy[order], xv[order]] = col[yv[order], zv[order], xv[order]]
    Image.fromarray(img).resize((GX * scale, (GZ + lift) * scale), Image.NEAREST).save(path)


def crop(occ, exposed, col):
    """The model's own box (its x and z extent, y from the ground)."""
    zs, xs = np.nonzero(occ.any(axis=0))
    if not len(zs):
        return occ[:1, :1, :1], exposed[:1, :1, :1], col[:1, :1, :1]
    top = int(np.nonzero(occ.any(axis=(1, 2)))[0].max()) + 1
    box = (slice(0, top), slice(zs.min(), zs.max() + 1), slice(xs.min(), xs.max() + 1))
    return occ[box], exposed[box], col[box]


def ground_tops(package: dict, D: int, W: int) -> np.ndarray:
    """Per drawing pixel the ground's height in voxels over the window's floor (package "floor"): each cell's level (8
    voxels a block), a ramp climbing its rise across its cell the way it climbs. Zero without levels."""
    top = np.zeros((D, W), np.int32)
    levels = package.get("levels")
    if not levels:
        return top
    levels = [max(0, v - package.get("floor", 0)) for v in levels]
    px = package["px"]
    ww = package["window"][2]
    for i, level in enumerate(levels):
        cx, cy = i % ww, i // ww
        top[cy * px:(cy + 1) * px, cx * px:(cx + 1) * px] = level * 8
    for cx, cy, climb, rise in package.get("ramps", []):
        level = levels[cy * ww + cx]
        t = (np.arange(px) + 0.5) / px
        up = np.round((level + rise * t) * 8).astype(np.int32)
        cell = top[cy * px:(cy + 1) * px, cx * px:(cx + 1) * px]
        if climb == "up":
            cell[:] = up[::-1, None]
        elif climb == "down":
            cell[:] = up[:, None]
        elif climb == "left":
            cell[:] = up[None, ::-1]
        else:
            cell[:] = up[None, :]
    return top


def scene(models, draw, path_json, path_view, path_game, title, ground=None):
    """Every model on the window's ground (2 voxels of the drawing seen from above), at half the resolution: 4 voxels a
    block. ground: per drawing pixel the ground's height in voxels (ground_tops); each model then (occ, col, base voxels)."""
    D, W = draw.shape[0], draw.shape[1]
    ground = np.zeros((D, W), np.int32) if ground is None else ground
    H = max([m[0].shape[0] + (m[2] if len(m) > 2 else 0) for m in models] + [8, int(ground.max())])
    H += H % 2  # an even height, so the half resolution pairs every layer
    occ = np.zeros((H + 2, D, W), bool)
    col = np.zeros((H + 2, D, W, 3), np.uint8)
    ys = np.arange(H + 2)[:, None, None]
    solid = ys < 2 + ground[None]
    occ |= solid
    # the ground's top in the drawing's colours, its raised sides darker
    col[solid] = (np.broadcast_to(draw[None], (H + 2, D, W, 3))[solid] * 0.62).astype(np.uint8)
    top = ys == 1 + ground[None]
    col[top] = np.broadcast_to(draw[None], (H + 2, D, W, 3))[top]
    for m in models:
        m_occ, m_col = m[0], m[1]
        base = m[2] if len(m) > 2 else 0
        h = m_occ.shape[0]
        occ[2 + base:2 + base + h] |= m_occ
        col[2 + base:2 + base + h][m_occ] = m_col[m_occ]
    # half resolution: a voxel where any of its 8 is; its colour the highest, southmost one's
    H2, D2, W2 = (occ.shape[0] + 1) // 2, D // 2, W // 2
    o = occ[:H2 * 2, :D2 * 2, :W2 * 2].reshape(H2, 2, D2, 2, W2, 2)
    c = col[:H2 * 2, :D2 * 2, :W2 * 2].reshape(H2, 2, D2, 2, W2, 2, 3)
    small = o.any(axis=(1, 3, 5))
    pick_ = np.zeros((H2, D2, W2, 3), np.uint8)
    for dy in (0, 1):
        for dz in (0, 1):
            for dx in (0, 1):
                m = o[:, dy, :, dz, :, dx]
                pick_[m] = c[:, dy, :, dz, :, dx][m]
    exp = exposed_of(small)
    Path(path_json).write_text(json.dumps(viewer(small, exp, pick_, title, per_block=4)), encoding="utf-8")
    picture(small, exp, pick_, path_view)
    game_view(occ, exposed_of(occ), col, path_game)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("package")
    ap.add_argument("--model", action="append", default=[])
    ap.add_argument("--scene", action="store_true")
    args = ap.parse_args()
    pkg_dir = Path(args.package)
    package = json.loads((pkg_dir / "package.json").read_text(encoding="utf-8"))
    draw = np.asarray(Image.open(pkg_dir / "drawing.png").convert("RGB"))
    px = package["px"]
    D, W = draw.shape[0], draw.shape[1]
    doors = np.zeros((D, W), bool)
    for cx, cy in package.get("doors", []):
        doors[cy * px:(cy + 1) * px, cx * px:(cx + 1) * px] = True
    out = pkg_dir / "out"
    out.mkdir(exist_ok=True)
    every = sorted(p.stem for p in (pkg_dir / "specs").glob("*.json"))
    names = args.model or every

    def made(name):
        spec = json.loads((pkg_dir / "specs" / f"{name}.json").read_text(encoding="utf-8"))
        H = min(MAX_HEIGHT, int(spec.get("height", 96)))
        allowed = np.zeros((D, W), bool)
        for cx, cy in cells_of(package, spec.get("sections", [])):
            allowed[cy * px:(cy + 1) * px, cx * px:(cx + 1) * px] = True
        parts = expand(spec, package)
        return parts, build(parts, draw, H, allowed, doors)

    built = {}
    for name in names:
        parts, (occ, exposed, col, cleared) = made(name)
        built[name] = (occ, col)
        c_occ, c_exp, c_col = crop(occ, exposed, col)
        (out / f"{name}.json").write_text(json.dumps(viewer(c_occ, c_exp, c_col, f"{package['map']}: {name}")), encoding="utf-8")
        picture(c_occ, c_exp, c_col, out / f"{name}_view.png")
        front_view(c_occ, c_col, out / f"{name}_front.png")
        game_view(occ, exposed, col, out / f"{name}_game.png", under=draw)
        top = int(np.nonzero(occ.any(axis=(1, 2)))[0].max()) + 1 if occ.any() else 0
        print(name, f"{len(parts)} parts", f"{int(occ.sum())} voxels", f"highest {top} ({top / 8:.1f} blocks)",
              f"cleared {cleared}" + (" (parts reach over cells that aren't the model's)" if cleared else ""))
    if args.scene:
        for name in every:
            if name not in built:
                _, (occ, _, col, _) = made(name)
                built[name] = (occ, col)
        bases = {s["id"]: max(0, s.get("base", 0) - package.get("floor", 0)) for s in package["sections"]}

        def base_of(name):
            spec = json.loads((pkg_dir / "specs" / f"{name}.json").read_text(encoding="utf-8"))
            return min([bases.get(i, 0) for i in spec.get("sections", [])] or [0]) * 8

        scene([built[n] + (base_of(n),) for n in every], draw, out / "scene.json", out / "scene_view.png", out / "scene_game.png",
              f"{package['map']}: scene" + (" (on the levels)" if package.get("levels") else ""), ground_tops(package, D, W))
        print("scene", len(every), "models", "on the levels" if package.get("levels") else "")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
