#!/usr/bin/env python3
"""The landscape models' table for the game (docs/specs/LANDSCAPE_MODELS.md): every spot's specs as the planner places
them (LandscapeModels.java, -Pkb.landscapeModels).

    python models_table.py <models dir> <table.json> [--slopes <dir>] [SPOT ...]

Per model: its grid (the tiles its cells, the door cells its arch reaches over, its parts and its paint cover, inside
the spot's window), its claim (those cells' tiles), and its spec in the grid's pixels: the parts (the trees helper
expanded into trunks and crowns, as terrain_model.py builds them), the footprint ("keep": its cells' tiles; "doors";
"doorClear"), "height", "quadCap" and "roofRows", and "trees" (a model of marked trees). Part sizes, paint rectangles and tiles
only: no pixel. With --slopes, the table's
"slopes" are the marked slopes there (slopes_table.py). Runs where terrain_model.py does (numpy, Pillow).
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from slopes_table import add_slopes  # noqa: E402
from terrain_model import DOOR_CLEAR, MAX_HEIGHT, build, cells_of, expand  # noqa: E402

TILE = 8
# The client's quad cap per block for these models (BoxBuilding's "quadCap"): high, so a busy rock face stays a model;
# what it costs is the frame-rate test's to say.
QUAD_CAP = 1024


def shifted(part, dx, dz):
    """A part moved dx, dz pixels west and north, its paint rectangles with it."""
    out = dict(part)
    if "x" in part:
        out["x"] = [part["x"][0] - dx, part["x"][1] - dx]
        out["z"] = [part["z"][0] - dz, part["z"][1] - dz]
    if "center" in part:
        out["center"] = [part["center"][0] - dx, part["center"][1] - dz]
    paint = dict(part.get("paint", {}))
    for key in ("front", "top", "side", "back"):
        if key in paint:
            r = paint[key]
            paint[key] = [r[0] - dx, r[1] - dz, r[2] - dx, r[3] - dz]
    out["paint"] = paint
    return out


def extent(part):
    """A part's x and z extent in pixels, with its paint rectangles."""
    if "x" in part:
        xs, zs = list(part["x"]), list(part["z"])
    else:
        r = part["radius"]
        rx, rz = (r[0], r[1]) if isinstance(r, list) else (r, r)
        xs, zs = [part["center"][0] - rx, part["center"][0] + rx], [part["center"][1] - rz, part["center"][1] + rz]
    for rect in part.get("paint", {}).values():
        if isinstance(rect, list) and len(rect) == 4:
            xs += [rect[0], rect[2]]
            zs += [rect[1], rect[3]]
    return min(xs), max(xs), min(zs), max(zs)


def trees_only(package, spec):
    """True for a model of trees: every section it stands on marked trees, or it uses the trees helper."""
    kinds = {sec["id"]: sec["kind"] for sec in package["sections"]}
    sections = spec.get("sections", [])
    return (bool(sections) and all(kinds.get(i) == "trees" for i in sections)) or any(
        p.get("shape") == "trees" for p in spec.get("parts", []))


def model(package, spec, draw, name):
    px = package["px"]
    wx0, wy0, ww, wh = package["window"]
    W, D = ww * px, wh * px
    H = min(MAX_HEIGHT, int(spec.get("height", 96)))
    parts = expand(spec, package)
    cells = cells_of(package, spec.get("sections", []))
    mine = {(cx, cy) for cx, cy in cells}
    # the doors its arch reaches over: next to its cells, with a voxel over them from DOOR_CLEAR up
    allowed = np.zeros((D, W), bool)
    for cx, cy in cells:
        allowed[cy * px:(cy + 1) * px, cx * px:(cx + 1) * px] = True
    near = [d for d in package.get("doors", []) if any((d[0] + a, d[1] + b) in mine for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1)))]
    doors_px = np.zeros((D, W), bool)
    for cx, cy in near:
        doors_px[cy * px:(cy + 1) * px, cx * px:(cx + 1) * px] = True
    occ, _, _, _ = build(parts, draw, H, allowed, doors_px)
    doors = [d for d in near if occ[:, d[1] * px:(d[1] + 1) * px, d[0] * px:(d[0] + 1) * px].any()]
    # the grid: its cells, its doors, its parts and paint, inside the window, in whole tiles
    xs, zs = [], []
    for cx, cy in cells + doors:
        xs += [cx * px, (cx + 1) * px]
        zs += [cy * px, (cy + 1) * px]
    for part in parts:
        a, b, c, d = extent(part)
        xs += [a, b]
        zs += [c, d]
    gx0 = max(0, math.floor(min(xs) / TILE))
    gz0 = max(0, math.floor(min(zs) / TILE))
    gx1 = min(W // TILE, math.ceil(max(xs) / TILE))
    gz1 = min(D // TILE, math.ceil(max(zs) / TILE))
    dx, dz = gx0 * TILE, gz0 * TILE
    per = px // TILE
    keep = sorted({(cx * per + i - gx0, cy * per + j - gz0) for cx, cy in cells for i in range(per) for j in range(per)})
    door_tiles = sorted({(cx * per + i - gx0, cy * per + j - gz0) for cx, cy in doors for i in range(per) for j in range(per)})
    out_spec = {"height": H, "parts": [shifted(p, dx, dz) for p in parts], "keep": [list(t) for t in keep],
                "doors": [list(t) for t in door_tiles], "doorClear": DOOR_CLEAR, "quadCap": QUAD_CAP, "roofRows": 0}
    return {"name": name, "tx": wx0 * per + gx0, "ty": wy0 * per + gz0, "w": gx1 - gx0, "h": gz1 - gz0,
            "claim": [list(t) for t in keep + door_tiles], "spec": out_spec,
            # a model of marked trees stands each tree on its own ground when the ground has levels; rock is one shape
            "trees": trees_only(package, spec)}


def main(argv):
    models_dir, out = argv[1], argv[2]
    rest = argv[3:]
    slopes_dir = None
    if rest[:1] == ["--slopes"]:
        slopes_dir, rest = rest[1], rest[2:]
    spots = rest or sorted(d for d in os.listdir(models_dir) if os.path.isdir(os.path.join(models_dir, d, "specs")))
    maps = {}
    for spot in spots:
        d = os.path.join(models_dir, spot)
        package = json.load(open(os.path.join(d, "package.json")))
        draw = np.asarray(Image.open(os.path.join(d, "drawing.png")).convert("RGB"))
        for f in sorted(os.listdir(os.path.join(d, "specs"))):
            if not f.endswith(".json"):
                continue
            spec = json.load(open(os.path.join(d, "specs", f)))
            m = model(package, spec, draw, f[:-5])
            maps.setdefault(package["map"], []).append(m)
            print(package["map"], m["name"], f"grid {m['w']}x{m['h']} tiles at {m['tx']},{m['ty']}", f"claim {len(m['claim'])}",
                  f"parts {len(m['spec']['parts'])}", f"doors {len(m['spec']['doors']) // 4}")
    table = {"maps": maps}
    if slopes_dir is not None:
        add_slopes(table, slopes_dir)
    with open(out, "w") as fh:
        json.dump(table, fh, separators=(",", ":"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
