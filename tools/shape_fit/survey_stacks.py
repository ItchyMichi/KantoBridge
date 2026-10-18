#!/usr/bin/env python3
"""Fitted shapes survey (docs/specs/FITTED_SHAPES.md section 14): viewer models for multi-cell items, each cell's current
shape put together into the object the drawing shows.

    python survey_stacks.py <game> <groups.json> <keys.npz> <survey.json> <mosaic maps dir> <viewer data dir>

For each item of more than one cell (a 2x1 stack, a 1x2 pair, a 2x2 object) and each strip's repeating unit:
  stacked   the cells' shapes put together as the drawing reads: a cell row above another stands on top of it (a 2x1
            stack becomes one object two cells tall), cells side by side stay side by side
  today     the same cells as the planner places them now: each row one cell further north, all on the ground
A cell with a voxel shape in the fitted table shows it; any other cell shows a full block (what the builders make of
it is not in the table): "block" in the model's notes. Colours: each voxel takes the colour of the drawing in front of it
(the first placement's map picture from the hidden-game shots, its palette), quantised to a palette. Images: the item's drawing and the
hidden game around one placement. Group "stacks / <game> / <size> <keys>".
"""
from __future__ import annotations

import base64
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

W = 16
MARGIN = 3


def unbits(text: str, n: int) -> np.ndarray:
    raw = np.frombuffer(base64.b64decode(text), np.uint8)
    return np.unpackbits(raw, bitorder="little")[:n].astype(bool)


def bits(arr: np.ndarray) -> str:
    return base64.b64encode(np.packbits(arr.astype(np.uint8).ravel(), bitorder="little").tobytes()).decode()


def cell_voxels(entry: dict | None) -> tuple[np.ndarray, str]:
    """[y][z][x] occupancy of one cell, and its source."""
    if entry and entry.get("class") == "voxels":
        return unbits(entry["v"], W ** 3).reshape(W, W, W), "voxels"
    return np.ones((W, W, W), bool), "block" if not entry else f"builder fit ({entry.get('class')})"


def main() -> int:
    game, groups_f, keys_f, survey_f, maps_dir, data = sys.argv[1:7]
    data = Path(data)
    groups = json.load(open(groups_f))["groups"]
    keyimg = np.load(keys_f)
    survey = json.load(open(survey_f))
    key_index = {k: i for i, k in enumerate(survey["keys"])}
    repo = Path(__file__).resolve().parents[2]
    table = json.load(open(repo / f"fabric-bridge/src/main/resources/kantobridge/fitted_shapes/{game}.json"))["cells"]
    for old in (data / "models").glob(f"st_{game}_*.json"):
        old.unlink()
    made = 0
    for g in groups:
        if not ((g["kind"] == "item" and g["w"] * g["h"] > 1) or g["kind"] == "strip"):
            continue
        gw, gh = g["w"], g["h"]
        sid = f"{game}_{gw}x{gh}_{made:04d}"
        pics = {}
        for y in range(gh):
            for x in range(gw):
                k = g["cells"][y][x]
                if k is None:
                    continue
                i = key_index[k]
                pics[(x, y)] = keyimg[str(i)] if str(i) in keyimg else np.full((W, W, 3), 128, np.uint8)
        # one palette for the item: its drawing quantised
        drawing = np.zeros((gh * W, gw * W, 3), np.uint8)
        for (x, y), p in pics.items():
            drawing[y * W:(y + 1) * W, x * W:(x + 1) * W] = p
        mp0, mx0, my0 = g["placements"][0]
        mp0path = Path(maps_dir) / f"{mp0}.png"
        if mp0path.is_file():
            drawing = np.asarray(Image.open(mp0path).convert("RGB").crop((mx0 * W, my0 * W, (mx0 + gw) * W, (my0 + gh) * W))).copy()
        q = Image.fromarray(drawing).quantize(colors=64, method=Image.Quantize.MEDIANCUT)
        pal = q.getpalette() or []
        pal = (pal + [0] * (64 * 3))[:64 * 3]
        idx = np.asarray(q)
        palette = ["#%02x%02x%02x" % tuple(pal[i * 3:i * 3 + 3]) for i in range(64)]
        notes = {}
        layouts = {"stacked": (gw * W, gh * W, W), "today": (gw * W, W, gh * W)}
        img_dir = data / "images" / "stacks" / sid
        img_dir.mkdir(parents=True, exist_ok=True)
        Image.fromarray(drawing).resize((gw * W * 8, gh * W * 8), Image.NEAREST).save(img_dir / "drawing.png")
        images = [{"url": f"/images/stacks/{sid}/drawing.png", "label": "drawing (hidden game colours)"}]
        mp, mx, my = g["placements"][0]
        mpath = Path(maps_dir) / f"{mp}.png"
        if mpath.is_file():
            full = Image.open(mpath)
            box = (max(0, (mx - MARGIN) * W), max(0, (my - MARGIN) * W), min(full.width, (mx + gw + MARGIN) * W),
                   min(full.height, (my + gh + MARGIN) * W))
            crop = full.crop(box)
            crop.resize((crop.width * 6, crop.height * 6), Image.NEAREST).save(img_dir / "context.png")
            images.append({"url": f"/images/stacks/{sid}/context.png", "label": f"in the hidden game ({mp})"})
        for name, (dx, dy, dz) in layouts.items():
            occ = np.zeros((dy, dz, dx), bool)
            shade = np.full((dy, dz, dx), 255, np.uint8)
            for (x, y), _p in pics.items():
                k = g["cells"][y][x]
                v, src = cell_voxels(table.get(k))
                notes[f"cell {x},{y}"] = src
                ox = x * W
                oy = (gh - 1 - y) * W if name == "stacked" else 0
                oz = 0 if name == "stacked" else y * W  # today: row 0 (north) furthest back
                for vy in range(W):
                    row = W - 1 - vy
                    cols = idx[y * W + row, x * W:(x + 1) * W]
                    for vz in range(W):
                        on = v[vy, vz]
                        occ[oy + vy, oz + vz, ox:ox + W] |= on
                        shade[oy + vy, oz + vz, ox:ox + W] = np.where(on, cols, shade[oy + vy, oz + vz, ox:ox + W])
            model = {"title": f"{name}: {gw}x{gh}" + (" (strip unit)" if g["kind"] == "strip" else ""),
                     "group": f"stacks / {game} / {gw}x{gh} {sid.split('_')[-1]}", "order": 0 if name == "stacked" else 1,
                     "tag": f"{g['uses']} placements; " + ", ".join(sorted(set(notes.values()))),
                     "dims": [dx, dy, dz], "voxels": bits(occ), "shades": base64.b64encode(shade.tobytes()).decode(),
                     "palette": palette, "voxelsPerBlock": 8, "images": images,
                     "meta": dict(notes, keys=" | ".join(str(c) for row in g["cells"] for c in row), placements=g["uses"],
                                  example=f"{mp} ({mx},{my})")}
            (data / "models" / f"st_{sid}_{name}.json").write_text(json.dumps(model), encoding="utf-8")
        made += 1
    print(f"{game}: {made} multi-cell items")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
