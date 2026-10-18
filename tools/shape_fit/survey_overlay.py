#!/usr/bin/env python3
"""Fitted shapes survey (docs/specs/FITTED_SHAPES.md section 14): what every cell gets today, over the hidden game's
picture of each map, and the lists of what still needs a model.

    python survey_overlay.py <game> <survey.json> <groups.json> <mosaic maps dir> <out dir>

<out>/<game>/overlays/<map>.png: the map 3x, each cell outlined by what it gets: green a voxel shape (the fitted table),
cyan another table shape (a builder fit), red a solid block of an object (a cell that should get a model), orange a solid
block of a structure (a building, a canopy, terrain: the structure packages), nothing for walkable floor; yellow dots
mark walkable cells drawn differently from the map's plain floor (decoration: a ledge, a step, a rug, flowers).
<out>/<game>/coverage.json: per key not yet modelled and not floor, its placements and what it is part of.
<out>/<game>/floor_decor.json: per walkable key that isn't its map's plain floor, its placements.
<out>/<game>/info/<map>.json and index.json: per map its cells (key, kind, why, table class) and red/orange counts, for
kb-hub's maps page.
"""
from __future__ import annotations

import collections
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

W = 16
Z = 3


def main() -> int:
    game, survey_f, groups_f, maps_dir, out_dir = sys.argv[1:6]
    survey = json.load(open(survey_f))
    keys = survey["keys"]
    groups = json.load(open(groups_f))["groups"]
    # what each placement of a group is: item (multi or single) or structure
    part = {}
    for g in groups:
        kind = g["kind"] if not (g["kind"] == "item" and g["w"] * g["h"] == 1) else "single"
        for mp, x0, y0 in g["placements"]:
            part[(mp, x0, y0)] = (kind, g["w"], g["h"])
    out = Path(out_dir) / game
    (out / "overlays").mkdir(parents=True, exist_ok=True)
    coverage = collections.defaultdict(lambda: {"uses": 0, "parts": collections.Counter(), "example": None})
    decor = collections.defaultdict(lambda: {"uses": 0, "example": None})
    totals = collections.Counter()
    index = []
    (out / "info").mkdir(exist_ok=True)
    for m in survey["maps"]:
        counts = collections.Counter()
        w, h = m["w"], m["h"]
        # each region's kind, from its top-left placement
        region_kind = {}
        cells_of = collections.defaultdict(list)
        for i, r in enumerate(m["region"]):
            if r >= 0:
                cells_of[r].append(i)
        for r, idx in cells_of.items():
            x0 = min(i % w for i in idx)
            y0 = min(i // w for i in idx)
            region_kind[r] = part.get((m["map"], x0, y0), ("structure", 0, 0))[0]
        walk = [m["cell"][i] for i in range(w * h) if m["kind"][i] == "W"]
        plain = collections.Counter(walk).most_common(1)[0][0] if walk else None
        path = Path(maps_dir) / f"{m['map']}.png"
        img = Image.open(path).convert("RGBA") if path.is_file() else Image.new("RGBA", (w * W, h * W), (40, 40, 40, 255))
        img = img.resize((w * W * Z, h * W * Z), Image.NEAREST)
        d = ImageDraw.Draw(img, "RGBA")
        for i in range(w * h):
            x, y = i % w, i // w
            k, kind, fit = m["cell"][i], m["kind"][i], m["fit"][i]
            box = [x * W * Z, y * W * Z, (x + 1) * W * Z - 1, (y + 1) * W * Z - 1]
            if kind == "W":
                totals["floor"] += 1
                if k != plain:
                    d.ellipse([box[0] + 20, box[1] + 20, box[0] + 27, box[1] + 27], fill=(255, 230, 0, 230))
                    e = decor[keys[k]]
                    e["uses"] += 1
                    e["example"] = e["example"] or [m["map"], x, y]
                continue
            if fit == "voxels":
                d.rectangle(box, outline=(40, 220, 60, 255), width=3)
                totals["voxels"] += 1
                continue
            if fit:
                d.rectangle(box, outline=(0, 200, 230, 255), width=3)
                totals["builder"] += 1
                continue
            rk = region_kind.get(m["region"][i], "single")
            colour = (255, 140, 0, 90) if rk == "structure" else (230, 30, 30, 110)
            d.rectangle(box, fill=colour)
            totals["blocks_" + ("structure" if rk == "structure" else "object")] += 1
            counts["orange" if rk == "structure" else "red"] += 1
            c = coverage[keys[k]]
            c["uses"] += 1
            c["parts"][rk] += 1
            c["example"] = c["example"] or [m["map"], x, y]
        img.convert("RGB").save(out / "overlays" / f"{m['map']}.png", optimize=True)
        local = sorted(set(m["cell"]))
        where = {k: i for i, k in enumerate(local)}
        (out / "info" / f"{m['map']}.json").write_text(json.dumps(
            {"map": m["map"], "w": w, "h": h, "keys": [keys[k] for k in local], "cell": [where[k] for k in m["cell"]],
             "kind": m["kind"], "why": m["why"], "fit": m["fit"]}), encoding="utf-8")
        index.append({"map": m["map"], "w": w, "h": h, "red": counts["red"], "orange": counts["orange"]})
    index.sort(key=lambda e: e["map"])
    (out / "index.json").write_text(json.dumps(index), encoding="utf-8")
    cov = sorted(({"key": k, "uses": v["uses"], "parts": dict(v["parts"]), "example": v["example"]} for k, v in coverage.items()),
                 key=lambda e: -e["uses"])
    (out / "coverage.json").write_text(json.dumps(cov, indent=1), encoding="utf-8")
    dec = sorted(({"key": k, "uses": v["uses"], "example": v["example"]} for k, v in decor.items()), key=lambda e: -e["uses"])
    (out / "floor_decor.json").write_text(json.dumps(dec, indent=1), encoding="utf-8")
    print(game, dict(totals), "keys without a model:", len(cov), "floor decoration keys:", len(dec))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
