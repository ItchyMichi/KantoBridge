#!/usr/bin/env python3
"""The landscape models' packages (docs/specs/LANDSCAPE_MODELS.md): per picture spot, the window of the map the camera
looks over, for the modelling agent and terrain_model.py.

    python package.py <regions dir> <marks dir> <art dir> <out dir> <spots.json> [--levels <levels dir>] [SPOT ...]

<spots.json>: {"<spot>": {"map": "<MAP>", "x": cx, "y": cy}, ...} (the cell the camera stands on, looking north);
<regions dir>/<MAP>.json the first pass, <marks dir>/<MAP>_sections.json the agents' marks, <art dir>/<MAP>_art.png the
map's art at 48 px a cell. Per spot, <out dir>/<spot>/:

- drawing.png: the window's art at 16 px a cell (the game's own pixels);
- reference.png: the same at 3x, a line round every cell, the window's pixel numbers every 2 cells along the top and
  left, each marked section outlined in its kind's colour with its id;
- package.json: the map, the spot, the window ([x0, y0, w, h] in map cells), px (16), the doors (window cells), and
  each marked section with a cell in the window: id, kind, note, rows, landmark, its cells and top cells (window
  cells), and "model": the sections to model (masses, mouths, faces of 3 cells or more; with trees, the trees). With
  --levels (OutdoorLevelsDumpTest's per-map levels: the levels pilot), "levels": each window cell's level in blocks
  over the map's floor, "floor": the window's lowest walkable level, "ramps": [x, y, climb, rise] in window cells, and
  each section's "base": the level its rock stands on (the lowest of its cells': a model counts its voxels from
  there); the reference shows the levels over the window's floor.

The window: 12 cells either side of the spot and 24 north of it, inside the map. Private output: the art is the user's
game.
"""
import json
import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from table import _cells  # noqa: E402

COLOURS = {"face": (230, 60, 60), "mass": (150, 70, 220), "objects": (255, 190, 0), "trees": (40, 190, 70),
           "mouth": (0, 200, 230), "building": (120, 120, 120)}
PX = 16
ART = 48


def section_cells(regions, marks):
    """Per marked section (its index in the marks), its cells and top cells: its region's cells in its boxes, a cell in
    two sections' boxes the first's."""
    w, h, grid = regions["w"], regions["h"], regions["grid"]
    owner = [-1] * (w * h)
    out = []
    for s, sec in enumerate(marks["sections"]):
        cells = []
        for box in sec.get("boxes", []):
            for x, y in _cells(box, w, h, s):
                if grid[y * w + x] == sec["region"] and owner[y * w + x] < 0:
                    owner[y * w + x] = s
                    cells.append((x, y))
        mine = set(cells)
        top = []
        for box in sec.get("top", []) or []:
            top.extend((x, y) for x, y in _cells(box, w, h, s) if (x, y) in mine)
        out.append((cells, sorted(set(top))))
    return out


def window_of(regions, spot):
    w, h = regions["w"], regions["h"]
    x0, x1 = max(0, spot["x"] - 12), min(w, spot["x"] + 13)
    y0, y1 = max(0, spot["y"] - 24), min(h, spot["y"] + 2)
    return x0, y0, x1 - x0, y1 - y0


def package(regions, marks, art, spot, trees, levels=None):
    x0, y0, ww, wh = window_of(regions, spot)
    inside = lambda x, y: x0 <= x < x0 + ww and y0 <= y < y0 + wh  # noqa: E731
    sections = []
    for s, (cells, top) in enumerate(section_cells(regions, marks)):
        mine = [[x - x0, y - y0] for x, y in cells if inside(x, y)]
        if not mine:
            continue
        sec = marks["sections"][s]
        sections.append({"id": s, "kind": sec["kind"], "note": sec.get("note", ""), "rows": sec.get("rows"),
                         "landmark": bool(sec.get("landmark")), "cells": mine,
                         "top": [[x - x0, y - y0] for x, y in top if inside(x, y)], "all_cells": len(cells)})
    model = [s["id"] for s in sections if s["kind"] in ("mass", "mouth") or (s["kind"] == "face" and len(s["cells"]) >= 3)
             or (trees and s["kind"] == "trees")]
    doors = sorted({(d[0] - x0, d[1] - y0) for r in regions["regions"] for d in r.get("doors_below", []) if inside(d[0], d[1])})
    crop = art.crop((x0 * ART, y0 * ART, (x0 + ww) * ART, (y0 + wh) * ART)).resize((ww * PX, wh * PX), Image.NEAREST)
    pkg = {"map": regions["map"], "spot": [spot["x"] - x0, spot["y"] - y0], "window": [x0, y0, ww, wh], "px": PX,
           "doors": [list(d) for d in doors], "sections": sections, "model": model}
    if levels is not None:
        mw = levels["w"]
        lv = levels["levels"]
        pkg["levels"] = [lv[(y0 + y) * mw + x0 + x] for y in range(wh) for x in range(ww)]
        pkg["ramps"] = [[r[0] - x0, r[1] - y0, r[2], r[3]] for r in levels["ramps"] if inside(r[0], r[1])]
        kinds = levels.get("kinds")
        walk = [pkg["levels"][y * ww + x] for y in range(wh) for x in range(ww)
                if kinds is None or kinds[(y0 + y) * mw + x0 + x] != "#"]
        pkg["floor"] = min(walk, default=0)
        for sec in sections:
            sec["base"] = min((pkg["levels"][y * ww + x] for x, y in sec["cells"]), default=0)
    return pkg, crop


def reference(drawing, pkg, scale=3):
    ww, wh = pkg["window"][2], pkg["window"][3]
    img = drawing.resize((drawing.width * scale, drawing.height * scale), Image.NEAREST).convert("RGBA")
    over = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(over)
    c = PX * scale
    for x in range(ww + 1):
        d.line([(x * c, 0), (x * c, img.height)], fill=(0, 0, 0, 120 if x % 2 == 0 else 50), width=1)
    for y in range(wh + 1):
        d.line([(0, y * c), (img.width, y * c)], fill=(0, 0, 0, 120 if y % 2 == 0 else 50), width=1)
    owner = {}
    for sec in pkg["sections"]:
        for x, y in sec["cells"]:
            owner[(x, y)] = sec
    for (x, y), sec in owner.items():
        col = COLOURS[sec["kind"]] + (255,)
        X, Y = x * c, y * c
        for nx, ny, line in ((x, y - 1, [(X, Y + 1), (X + c, Y + 1)]), (x, y + 1, [(X, Y + c - 2), (X + c, Y + c - 2)]),
                             (x - 1, y, [(X + 1, Y), (X + 1, Y + c)]), (x + 1, y, [(X + c - 2, Y), (X + c - 2, Y + c)])):
            if owner.get((nx, ny)) is not sec:
                d.line(line, fill=col, width=3)
        if [x, y] in sec["top"]:
            d.ellipse([X + c // 2 - 3, Y + c // 2 - 3, X + c // 2 + 3, Y + c // 2 + 3], fill=(255, 255, 255, 200))
    for sec in pkg["sections"]:
        x, y = min(sec["cells"], key=lambda p: (p[1], p[0]))
        text = str(sec["id"])
        d.rectangle([x * c + 4, y * c + 4, x * c + 10 + 7 * len(text), y * c + 18], fill=(0, 0, 0, 210))
        d.text((x * c + 7, y * c + 5), text, fill=COLOURS[sec["kind"]] + (255,))
    for x in range(0, ww, 2):
        d.rectangle([x * c + 2, 2, x * c + 34, 15], fill=(255, 255, 255, 220))
        d.text((x * c + 4, 3), str(x * PX), fill=(0, 0, 0, 255))
    for y in range(0, wh, 2):
        d.rectangle([2, y * c + 2, 34, y * c + 15], fill=(255, 255, 255, 220))
        d.text((4, y * c + 3), str(y * PX), fill=(0, 0, 0, 255))
    if "levels" in pkg:
        # each cell over the map's floor: its level in blocks, a ramp's arrow
        for y in range(wh):
            for x in range(ww):
                level = pkg["levels"][y * ww + x] - pkg.get("floor", 0)
                if level > 0:
                    X, Y = x * c, y * c
                    d.rectangle([X + c - 22, Y + c - 16, X + c - 2, Y + c - 3], fill=(255, 255, 255, 215))
                    d.text((X + c - 20, Y + c - 15), str(level), fill=(200, 0, 160, 255))
        arrows = {"up": "^", "down": "v", "left": "<", "right": ">"}
        for rx, ry, climb, rise in pkg.get("ramps", []):
            X, Y = rx * c, ry * c
            d.rectangle([X + 2, Y + c - 16, X + 22, Y + c - 3], fill=(255, 230, 0, 230))
            d.text((X + 6, Y + c - 15), arrows.get(climb, "?") + str(rise), fill=(0, 0, 0, 255))
    sx, sy = pkg["spot"]
    d.rectangle([sx * c + 6, sy * c + 6, sx * c + c - 6, sy * c + c - 6], outline=(255, 0, 255, 255), width=3)
    return Image.alpha_composite(img, over).convert("RGB")


def main(argv):
    regions_dir, marks_dir, art_dir, out_dir, spots_path = argv[1:6]
    rest = argv[6:]
    levels_dir = None
    if rest[:1] == ["--levels"]:
        levels_dir, rest = rest[1], rest[2:]
    spots = json.load(open(spots_path))
    for name in rest or sorted(spots):
        spot = spots[name]
        mid = spot["map"]
        regions = json.load(open(os.path.join(regions_dir, mid + ".json")))
        marks = json.load(open(os.path.join(marks_dir, mid + "_sections.json")))
        art = Image.open(os.path.join(art_dir, mid + "_art.png")).convert("RGB")
        levels = None
        if levels_dir is not None and os.path.exists(os.path.join(levels_dir, mid + ".json")):
            levels = json.load(open(os.path.join(levels_dir, mid + ".json")))
        pkg, drawing = package(regions, marks, art, spot, spot.get("trees", False), levels)
        d = os.path.join(out_dir, name)
        os.makedirs(os.path.join(d, "specs"), exist_ok=True)
        drawing.save(os.path.join(d, "drawing.png"))
        reference(drawing, pkg).save(os.path.join(d, "reference.png"))
        if os.path.exists(os.path.join(d, "package.json")) and "levels" in pkg:
            # an existing package keeps its sections and models; it gains the levels
            old = json.load(open(os.path.join(d, "package.json")))
            old.update({k: pkg[k] for k in ("levels", "ramps", "floor")})
            bases = {s["id"]: s["base"] for s in pkg["sections"]}
            for s in old["sections"]:
                s["base"] = bases.get(s["id"], 0)
            pkg = old
        with open(os.path.join(d, "package.json"), "w") as fh:
            json.dump(pkg, fh, indent=1)
        print(name, "window", pkg["window"], "sections", len(pkg["sections"]), "to model", pkg["model"])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
