#!/usr/bin/env python3
"""Fitted shapes: a hand-made model (an entity_package.py package with its model.bbmodel) sliced into its cells' voxel
shapes (docs/specs/HAND_MODELS.md).

    python hand_import.py <package dir> [<package dir> ...] --out shapes.json [--viewer <dir>]

A package's model stands on its footprint (spec.json size_cells W x H): x from -16W to 16W, z from -16H to 16H, ground
y = 0, the front facing +z, 32 Blockbench units a cell. Each of the entity's cells (spec.json `part`) is the 32 x 32 x
32 units over its square of the footprint, cut to 16 x 16 x 16 voxels (bbmodel.py rasterize: a voxel is solid when its
centre is inside a cube) and keyed by the cell's key. Anything above y = 32 is reported and left out (one cell high for
now). Writes {key: bits} for merge_voxels.py. --viewer writes <dir>/<package id>.json: the whole model for kb-hub's
viewer, coloured as the game colours it (front projection: a voxel shows its own cell's drawing at its column and
height, the whole depth).
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
        colour = np.zeros((W, h * W, w * W, 3), np.uint8)
        for cy in range(h):
            for cx in range(w):
                cell = spec["cells"][cy][cx]
                if not cell["part"]:
                    continue
                # the cell's square moved to the origin: its centre is at (cx + 0.5) * 32 - 16W, (cy + 0.5) * 32 - 16H
                dx = -((cx + 0.5) * UNITS - w * UNITS / 2)
                dz = -((cy + 0.5) * UNITS - h * UNITS / 2)
                vox = rasterize(shifted(model, dx, dz))
                arr = np.array(vox, bool).reshape(W, W, W)  # [y][z][x]: bbmodel's index (y * 16 + z) * 16 + x
                if arr.any():
                    shapes[cell["key"]] = base64.b64encode(np.packbits(arr.astype(np.uint8).ravel(), bitorder="little")
                                                           .tobytes()).decode()
                whole[:, cy * W:(cy + 1) * W, cx * W:(cx + 1) * W] = arr
                for y in range(W):
                    row = draw[cy * W + W - 1 - y, cx * W:(cx + 1) * W]  # front projection: height y shows row 15 - y
                    colour[y, cy * W:(cy + 1) * W, cx * W:(cx + 1) * W] = row[None, :, :]
        print(f"{spec['id']}: {sum(1 for r in spec['cells'] for c in r if c['part'])} cells, "
              f"{int(whole.sum())} voxels" + (f"; above one cell (y {top} > {UNITS}) left out" if top > UNITS else ""))
        if args.viewer:
            q = Image.fromarray(draw).quantize(colors=64, method=Image.Quantize.MEDIANCUT)
            pal = ((q.getpalette() or []) + [0] * 192)[:192]
            pidx = np.asarray(q)
            shade = np.full(whole.shape, 255, np.uint8)
            for cy in range(h):
                for y in range(W):
                    row = cy * W + W - 1 - y
                    shade[y, cy * W:(cy + 1) * W] = np.where(whole[y, cy * W:(cy + 1) * W], pidx[row][None, :], 255)
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
