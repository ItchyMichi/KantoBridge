#!/usr/bin/env python3
"""Fitted shapes: a hand-made model (an entity_package.py package with its model.bbmodel) sliced into its cells' voxel
shapes (docs/specs/HAND_MODELS.md).

    python hand_import.py <package dir> [<package dir> ...] --out shapes.json [--viewer <dir>]

A package's model stands on its footprint (spec.json size_cells W x H): x from -16W to 16W, z from -16H to 16H, ground
y = 0, the front facing +z, 32 Blockbench units a cell. Each of the entity's cells (spec.json `part`) is the 32 x 32 x
32 units over its square of the footprint, cut to 16 x 16 x 16 voxels (bbmodel.py rasterize: a voxel is solid when its
centre is inside a cube) and keyed by the cell's key. Anything above y = 32 is reported and left out (one cell high for
now). Paint (HAND_MODELS.md section 3): each voxel shows the drawing's pixel its cube's face maps it to (the model's UVs
on its texture, the reference at 16 px a cell): its top face when that is open, else its front, sides, back, bottom; a
pixel from another cell's drawing becomes the nearest pixel of the same colour in its own cell (the same place when the
pixel is missing art, pure black). Writes {key: {"v": bits,
"t": paint}} for merge_voxels.py. --viewer writes <dir>/<package id>.json: the whole model for kb-hub's viewer,
coloured as the game will colour it.
"""
from __future__ import annotations

import argparse
import base64
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bbmodel import rasterize  # noqa: E402

W = 16
UNITS = 32  # Blockbench units a cell


def shifted(model: dict, dx: float, dz: float) -> dict:
    """The model moved by (dx, 0, dz): its cubes and their rotation origins."""
    els = []
    for e in model.get("elements", []):
        if e.get("type", "cube") != "cube":
            continue
        e = dict(e)
        e["from"] = [e["from"][0] + dx, e["from"][1], e["from"][2] + dz]
        e["to"] = [e["to"][0] + dx, e["to"][1], e["to"][2] + dz]
        o = e.get("origin") or [0, 0, 0]
        e["origin"] = [o[0] + dx, o[1], o[2] + dz]
        els.append(e)
    return {"elements": els}


FACES = ("up", "south", "east", "west", "north", "down")
OPEN = {"up": (1, 0, 0), "down": (-1, 0, 0), "south": (0, 1, 0), "north": (0, -1, 0), "east": (0, 0, 1), "west": (0, 0, -1)}


def face_pixel(e: dict, face: str, X: float, Y: float, Z: float, res: tuple[int, int]) -> tuple[int, int] | None:
    """The texture pixel (u, v) a point on a cube's face shows, by the face's UV rectangle (no rotation)."""
    uv = (e.get("faces", {}).get(face) or {}).get("uv")
    if not uv:
        return None
    f, t = e["from"], e["to"]
    lo = [min(f[i], t[i]) for i in range(3)]
    hi = [max(f[i], t[i]) for i in range(3)]
    span = lambda a, i: (a - lo[i]) / ((hi[i] - lo[i]) or 1)  # noqa: E731
    if face in ("up", "down"):
        a, b = span(X, 0), span(Z, 2)
    elif face in ("south", "north"):
        a, b = span(X, 0), 1 - span(Y, 1)
    else:
        a, b = span(Z, 2), 1 - span(Y, 1)
    u = uv[0] + a * (uv[2] - uv[0])
    v = uv[1] + b * (uv[3] - uv[1])
    return (min(res[0] - 1, max(0, int(u))), min(res[1] - 1, max(0, int(v))))


def own_pixel(draw: np.ndarray, cx: int, cy: int, u: int, v: int) -> int:
    """Pixel (u, v) of the whole drawing as a pixel of cell (cx, cy)'s own drawing (row * 16 + column): itself when it is
    in the cell, else the nearest pixel of the same colour there (the nearest colour when the cell has none)."""
    if u // W == cx and v // W == cy:
        return (v % W) * W + u % W
    want = draw[v, u].astype(int)
    ru, rv = u % W, v % W
    if not want.any():
        # pure black is missing art in the hidden-game mosaic (the games' own black is never 0, 0, 0): the same place in
        # the cell's own drawing, which the game has
        return rv * W + ru
    cell = draw[cy * W:(cy + 1) * W, cx * W:(cx + 1) * W].astype(int)
    diff = np.abs(cell - want).sum(axis=2)
    best = np.argwhere(diff == diff.min())
    r, c = min(best, key=lambda p: (p[0] - rv) ** 2 + (p[1] - ru) ** 2)
    return int(r) * W + int(c)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("packages", nargs="+")
    ap.add_argument("--out", required=True)
    ap.add_argument("--viewer", default="")
    args = ap.parse_args()
    shapes = {}
    for p in map(Path, args.packages):
        spec = json.loads((p / "spec.json").read_text(encoding="utf-8"))
        model = json.loads((p / "model.bbmodel").read_text(encoding="utf-8"))
        w, h = spec["size_cells"]
        top = max((max(e["from"][1], e["to"][1]) for e in model.get("elements", []) if e.get("type", "cube") == "cube"),
                  default=0)
        draw = np.asarray(Image.open(p / "reference.png").convert("RGB").resize((w * W, h * W), Image.NEAREST))
        whole = np.zeros((W, h * W, w * W), bool)  # [y][z][x]
        cubes = [e for e in model.get("elements", []) if e.get("type", "cube") == "cube"]
        res = (int(model.get("resolution", {}).get("width", w * W)), int(model.get("resolution", {}).get("height", h * W)))
        scale = (w * W / res[0], h * W / res[1])  # texture pixels to drawing pixels
        cells = {}
        for cy in range(h):
            for cx in range(w):
                cell = spec["cells"][cy][cx]
                if not cell["part"]:
                    continue
                # the cell's square moved to the origin: its centre is at (cx + 0.5) * 32 - 16W, (cy + 0.5) * 32 - 16H
                dx = -((cx + 0.5) * UNITS - w * UNITS / 2)
                dz = -((cy + 0.5) * UNITS - h * UNITS / 2)
                arr = np.array(rasterize(shifted(model, dx, dz)), bool).reshape(W, W, W)  # [y][z][x]: index (y * 16 + z) * 16 + x
                whole[:, cy * W:(cy + 1) * W, cx * W:(cx + 1) * W] = arr
                cells[(cx, cy)] = cell["key"]
        paint = np.zeros(whole.shape, np.uint8)
        for y, gz, gx in np.argwhere(whole):
            cx, cy = gx // W, gz // W
            X, Y, Z = gx * 2 - w * W + 1, y * 2 + 1, gz * 2 - h * W + 1  # the voxel's centre in Blockbench units
            pick = (W - 1 - y) * W + gx % W  # front projection, for a voxel no face shows
            inside = [e for e in cubes if all(min(e["from"][i], e["to"][i]) <= (X, Y, Z)[i] <= max(e["from"][i], e["to"][i])
                                              for i in range(3))]
            for face in FACES:
                oy, oz, ox = OPEN[face]
                ny, nz, nx = y + oy, gz + oz, gx + ox
                if 0 <= ny < W and 0 <= nz < h * W and 0 <= nx < w * W and whole[ny, nz, nx]:
                    continue
                px = face_pixel(inside[-1], face, X, Y, Z, res) if inside else None
                if px:
                    pick = own_pixel(draw, cx, cy, min(w * W - 1, int(px[0] * scale[0])), min(h * W - 1, int(px[1] * scale[1])))
                    break
            paint[y, gz, gx] = pick
        for (cx, cy), key in cells.items():
            arr = whole[:, cy * W:(cy + 1) * W, cx * W:(cx + 1) * W]
            if arr.any():
                shapes[key] = {"v": base64.b64encode(np.packbits(arr.astype(np.uint8).ravel(), bitorder="little").tobytes()).decode(),
                               "t": base64.b64encode(np.ascontiguousarray(paint[:, cy * W:(cy + 1) * W, cx * W:(cx + 1) * W])
                                                     .tobytes()).decode()}
        print(f"{spec['id']}: {sum(1 for r in spec['cells'] for c in r if c['part'])} cells, "
              f"{int(whole.sum())} voxels" + (f"; above one cell (y {top} > {UNITS}) left out" if top > UNITS else ""))
        if args.viewer:
            q = Image.fromarray(draw).quantize(colors=64, method=Image.Quantize.MEDIANCUT)
            pal = ((q.getpalette() or []) + [0] * 192)[:192]
            pidx = np.asarray(q)
            shade = np.full(whole.shape, 255, np.uint8)
            for y, gz, gx in np.argwhere(whole):
                cx, cy = gx // W, gz // W
                p = int(paint[y, gz, gx])
                shade[y, gz, gx] = pidx[cy * W + p // W, cx * W + p % W]
            view = {"title": f"hand model: {spec.get('note', '')}"[:120], "dims": [w * W, W, h * W],
                    "voxels": base64.b64encode(np.packbits(whole.astype(np.uint8).ravel(), bitorder="little").tobytes()).decode(),
                    "shades": base64.b64encode(shade.tobytes()).decode(),
                    "palette": ["#%02x%02x%02x" % tuple(pal[i * 3:i * 3 + 3]) for i in range(64)], "voxelsPerBlock": 8}
            Path(args.viewer).mkdir(parents=True, exist_ok=True)
            (Path(args.viewer) / f"{spec['id']}.json").write_text(json.dumps(view), encoding="utf-8")
    Path(args.out).write_text(json.dumps(shapes), encoding="utf-8")
    print(f"{len(shapes)} cell shapes to {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
