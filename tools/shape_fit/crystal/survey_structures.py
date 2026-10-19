#!/usr/bin/env python3
"""Fitted shapes survey (docs/specs/FITTED_SHAPES.md section 14): every structure as a package an AI (or a person) can
model as a whole in Blockbench.

    python survey_structures.py <game> <groups.json> <survey.json> <keys.npz> <mosaic maps dir> <out dir>

One folder per distinct structure (survey_groups.py's "structure" entries), named <game>_<type>_<nnnn>:

    reference.png       the structure as the game draws it on its first placement's map (that map's palette), 8x; cells
                        of the box outside the structure dimmed, walkable cells (doorways) kept
    reference_grid.png  the same with the cell grid and each cell's (column,row)
    context.png         the hidden game around one placement (3 cells of margin), 6x
    spec.json           size, cells (key, kind, why, today's height), doors, ground, placements, today's Tier A heights
    starter.bbmodel     a Blockbench blockout: one box per cell at today's height, textured with the reference
    README.md           what to make, for this structure

Types: building (a template: a door folds into it, or the planner claims it as a building), terrain (repeats: few
distinct keys over many cells: tree lines, cliffs, plain walls, water edges), object (everything else: canopies, fountains,
statues larger than 2 x 2, counters). <out dir>/PROMPT.md explains the conventions for every package; index.json lists
them, most placements first.
"""
from __future__ import annotations

import base64
import io
import json
import shutil
import sys
import uuid
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

W = 16          # drawing pixels per cell
UNITS = 32      # Blockbench units per cell (2 blocks of 16)
MARGIN = 3

PROMPT = """# Structures to model in Blockbench (KantoBridge)

Each folder is one structure from a 2D top-down game map that must become a single 3D Minecraft-scale model.

## How to read the reference
- `reference.png` is the structure exactly as the game draws it: a top-down "three-quarter" view. The lower rows of the
  drawing are the **front (south) face**; the upper rows show the **roof or top** seen from above and behind it. Nothing
  shows the back or the sides: make them plausible and consistent with the front.
- The drawing is a grid of cells. One cell = 16 x 16 drawing pixels = **2 x 2 Minecraft blocks** on the ground =
  **32 x 32 Blockbench units**. A structure of W x H cells stands on a footprint of (2W) x (2H) blocks.
- `reference_grid.png` numbers the cells (column,row), row 0 at the top (north). `spec.json` gives every cell's kind:
  `S` solid, `W` walkable floor, `C` counter, `L` ledge; walkable cells inside the footprint (doorways, paths) must stay
  open at ground level.
- `context.png` shows the structure in its surroundings in the game, for scale and for what is around it.

## Coordinates in Blockbench
- Units: 16 Blockbench units = 1 block. Ground is y = 0, up is +y.
- Footprint: x from -16W to +16W (west to east), z from -16H to +16H (north to south). The front of the structure faces
  **+z (south)**.
- `starter.bbmodel` is a blockout already in place: one box per cell at the height the game builds today (`spec.json`
  `today_blocks`), textured with the reference. Replace it with the real shape.

## What to make
- A model whose **front (south) view matches the reference** when seen straight on: the facade rows of the drawing on
  the south faces, the roof rows on the top surfaces and slopes.
- Roofs: the drawn roof is seen from above; give it real depth (a sloped or flat roof over the footprint), not a
  vertical wall of roof texture.
- Keep the footprint; height is free but should look right next to a 2-block-tall player (doors about 2 blocks high).
- Use cubes only (rotations in 22.5 degree steps are fine). Keep the texture `reference` and map faces to the matching
  part of the drawing (front projection is fine for faces that look south).
- Save as `model.bbmodel` in the structure's folder.
"""


def compose(cells: list[list[str | None]], key_index: dict, keyimg) -> np.ndarray:
    h, w = len(cells), len(cells[0])
    out = np.zeros((h * W, w * W, 4), np.uint8)
    for y in range(h):
        for x in range(w):
            k = cells[y][x]
            if k is None:
                continue
            ki = str(key_index[k])
            if ki in keyimg:
                out[y * W:(y + 1) * W, x * W:(x + 1) * W, :3] = keyimg[ki]
                out[y * W:(y + 1) * W, x * W:(x + 1) * W, 3] = 255
    return out


def box(x0, y0, z0, x1, y1, z1, uv_up, uv_south) -> dict:
    faces = {f: {"uv": uv_south, "texture": 0} for f in ("north", "east", "west", "down")}
    faces["south"] = {"uv": uv_south, "texture": 0}
    faces["up"] = {"uv": uv_up, "texture": 0}
    return {"name": "cell", "box_uv": False, "rescale": False, "locked": False, "render_order": "default",
            "from": [x0, y0, z0], "to": [x1, y1, z1], "autouv": 0, "color": 0, "origin": [0, 0, 0], "faces": faces,
            "type": "cube", "uuid": str(uuid.uuid4())}


def starter(name: str, ref: Image.Image, cells, heights) -> dict:
    h, w = len(cells), len(cells[0])
    els = []
    for y in range(h):
        for x in range(w):
            if cells[y][x] is None:
                continue
            blocks = max(1, heights[y][x])
            x0, z0 = x * UNITS - w * UNITS // 2, y * UNITS - h * UNITS // 2
            uv = [x * W, y * W, (x + 1) * W, (y + 1) * W]
            els.append(box(x0, 0, z0, x0 + UNITS, blocks * 16, z0 + UNITS, uv, uv))
    buf = io.BytesIO()
    ref.save(buf, "PNG")
    tex = {"path": "", "name": "reference", "folder": "", "namespace": "", "id": "0", "width": ref.width, "height": ref.height,
           "uv_width": ref.width, "uv_height": ref.height, "particle": False, "render_mode": "default", "render_sides": "auto",
           "frame_time": 1, "visible": True, "internal": True, "saved": True, "uuid": str(uuid.uuid4()),
           "source": "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode("ascii")}
    return {"meta": {"format_version": "4.10", "model_format": "free", "box_uv": False}, "name": name, "model_identifier": "",
            "visible_box": [w * 2, max(2, max(max(r) for r in heights)), h * 2], "variable_placeholders": "",
            "variable_placeholder_buttons": [], "timeline_setups": [], "unhandled_root_fields": {},
            "resolution": {"width": ref.width, "height": ref.height}, "elements": els, "outliner": [e["uuid"] for e in els],
            "textures": [tex]}


def main() -> int:
    game, groups_f, survey_f, keys_f, maps_dir, out_dir = sys.argv[1:7]
    groups = json.load(open(groups_f))["groups"]
    survey = json.load(open(survey_f))
    maps = {m["map"]: m for m in survey["maps"]}
    key_index = {k: i for i, k in enumerate(survey["keys"])}
    keyimg = np.load(keys_f)
    out = Path(out_dir) / game
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    index = []
    counters = {"building": 0, "terrain": 0, "object": 0}
    for g in groups:
        if g["kind"] != "structure":
            continue
        cells = g["cells"]
        h, w = len(cells), len(cells[0])
        n = sum(1 for row in cells for c in row if c is not None)
        distinct = len({c for row in cells for c in row if c is not None})
        mp, mx, my = g["placements"][0]
        m = maps[mp]
        building = bool(g["templates"]) or any(i["why"] == "building" for i in g["info"])
        terrain = not building and n >= 12 and distinct / n < 0.35
        kind = "building" if building else "terrain" if terrain else "object"
        sid = f"{game}_{kind}_{counters[kind]:04d}"
        counters[kind] += 1
        d = out / sid
        d.mkdir()
        # the reference: the placement's own map picture (its palette), every cell of the box; cells outside the structure
        # dimmed, the structure's walkable cells (doorways) kept as drawn
        draw = Image.fromarray(compose(cells, key_index, keyimg), "RGBA")
        mpath0 = Path(maps_dir) / f"{mp}.png"
        if mpath0.is_file():
            crop = Image.open(mpath0).convert("RGBA").crop((mx * W, my * W, (mx + w) * W, (my + h) * W))
            px = np.asarray(crop).copy()
            for y in range(h):
                for x in range(w):
                    if cells[y][x] is None:
                        mi = (my + y) * m["w"] + mx + x
                        if m["kind"][mi] != "W":
                            px[y * W:(y + 1) * W, x * W:(x + 1) * W, :3] //= 3
            draw = Image.fromarray(px, "RGBA")
        ref = draw.resize((w * W * 8, h * W * 8), Image.NEAREST)
        ref.save(d / "reference.png")
        grid = ref.copy()
        gd = ImageDraw.Draw(grid)
        for y in range(h):
            for x in range(w):
                gd.rectangle([x * 128, y * 128, x * 128 + 127, y * 128 + 127], outline=(255, 0, 255, 255))
                gd.text((x * 128 + 4, y * 128 + 4), f"{x},{y}", fill=(255, 0, 255, 255))
        grid.save(d / "reference_grid.png")
        mpath = Path(maps_dir) / f"{mp}.png"
        if mpath.is_file():
            full = Image.open(mpath)
            crop = full.crop((max(0, (mx - MARGIN) * W), max(0, (my - MARGIN) * W), min(full.width, (mx + w + MARGIN) * W),
                              min(full.height, (my + h + MARGIN) * W)))
            crop.resize((crop.width * 6, crop.height * 6), Image.NEAREST).save(d / "context.png")
        per = m["perCell"]
        heights = [[0] * w for _ in range(h)]
        if "blocks" in m:
            ac = m["w"] * per
            for y in range(h):
                for x in range(w):
                    if cells[y][x] is not None:
                        heights[y][x] = max(m["blocks"][((my + y) * per + dy) * ac + (mx + x) * per + dx]
                                            for dy in range(per) for dx in range(per))
        info = {(i["x"], i["y"]): i for i in g["info"]}
        tmpl = None
        for t in m["templates"]:
            if t["sig"] in g["templates"]:
                tmpl = t
                break
        spec = {"id": sid, "game": game, "type": kind, "size_cells": [w, h], "footprint_blocks": [w * 2, h * 2],
                "cells": [[{"key": survey["keys"][m["cell"][(my + y) * m["w"] + mx + x]], "kind": m["kind"][(my + y) * m["w"] + mx + x],
                            "part": False} if cells[y][x] is None else {"key": cells[y][x], "kind": info[(x, y)]["kind"], "part": True,
                                                             "why": info[(x, y)]["why"], "fit": info[(x, y)]["fit"],
                                                             "today_blocks": heights[y][x]}
                           for x in range(w)] for y in range(h)],
                "today_blocks": heights, "placements": g["placements"], "uses": g["uses"],
                "template": None if tmpl is None else {"sig": tmpl["sig"], "doors": tmpl["entry"].get("doors"),
                                                       "ground": tmpl["entry"].get("ground"),
                                                       "column_heights": [len(c) for c in tmpl["entry"].get("columns", [])]}}
        (d / "spec.json").write_text(json.dumps(spec, indent=1), encoding="utf-8")
        (d / "starter.bbmodel").write_text(json.dumps(starter(sid, draw, cells, heights)), encoding="utf-8")
        walk = sum(1 for row in spec["cells"] for c in row if c["kind"] == "W")
        (d / "README.md").write_text(
            f"# {sid}\n\n{kind.capitalize()} of {w} x {h} cells ({w * 2} x {h * 2} blocks), {n} cells drawn, "
            f"{walk} walkable (keep open at ground level), used {g['uses']} times; first at {mp} ({mx},{my}).\n"
            f"Today the game builds it {max(max(r) for r in heights)} blocks tall at most (spec.json today_blocks).\n\n"
            "Read ../PROMPT.md for the conventions. Start from starter.bbmodel, save model.bbmodel here.\n", encoding="utf-8")
        index.append({"id": sid, "type": kind, "size": [w, h], "cells": n, "uses": g["uses"], "example": [mp, mx, my]})
    index.sort(key=lambda e: ({"building": 0, "object": 1, "terrain": 2}[e["type"]], -e["uses"], -e["cells"]))
    (out / "index.json").write_text(json.dumps(index, indent=1), encoding="utf-8")
    (Path(out_dir) / "PROMPT.md").write_text(PROMPT, encoding="utf-8")
    print(game, counters)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
