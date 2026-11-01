#!/usr/bin/env python3
"""Reproducible hand-built Ecruteak house and Sprout Tower parts specifications.

Only dimensions and source-drawing UV rectangles are distributed. Optional local
previews reconstruct each building directly from the user's exported quarters.
"""
from __future__ import annotations

import argparse
import base64
import copy
import io
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TABLE = ROOT / "fabric-bridge/src/main/resources/kantobridge/fitted_shapes/crystal.json"
BUNDLE = ROOT / "local/minecraft/kantobridge/export/crystal"
HOUSE = "crystal_b010_G2_ECRUTEAK_CITY"
TOWER = "crystal_b088_G2_VIOLET_CITY"
IDS = (HOUSE, TOWER)


def box(x, z, y, front, *, top=None, side=None, back=None, carve=False):
    p = {"shape": "box", "x": list(x), "z": list(z), "y": list(y)}
    if carve:
        p["carve"] = True
    else:
        p["paint"] = {"front": front, "top": top or front,
                      "side": side or front, "back": back or side or front}
    return p


def roof(x, z, y, profile, *, front, top, cross=None):
    p = {"shape": "profile", "along": "x", "x": list(x), "z": list(z),
         "y": list(y), "profile": profile,
         "paint": {"top": top, "front": front,
                   "side": [8, 4, 12, 24], "back": front}}
    if cross:
        p["cross"] = cross
    return p


def timber_frame(parts, x, z, y, wood):
    """Four posts and horizontal side beams; facade details remain source art."""
    x0, x1 = x
    z0, z1 = z
    y0, y1 = y
    for px in (x0, x1 - 2):
        for pz in (z0, z1 - 2):
            parts.append(box((px, px + 2), (pz, pz + 2), y, wood))
        for py in (y0 + 2, y1 - 3):
            parts.append(box((px, px + 2), (z0, z1), (py, py + 2), wood))


def house_spec():
    wood = [6, 35, 7, 36]
    plaster = [34, 34, 38, 40]
    parts = [box((4, 60), (8, 44), (0, 24), [4, 32, 60, 48],
                 top=plaster, side=plaster)]
    timber_frame(parts, (4, 60), (8, 44), (0, 24), wood)
    # A curved, overhanging gable. The outer eaves turn upward after a shallow
    # dip, and the central ridge is raised instead of being a flat sprite slab.
    parts.append(roof((0, 64), (4, 48), (24, 44),
                      [6, 3, 6, 13, 20, 13, 6, 3, 6],
                      front=[0, 27, 64, 32], top=[4, 0, 60, 28]))
    parts.append(box((4, 60), (24, 28), (43, 46), [8, 1, 56, 4], top=[8, 1, 56, 3]))
    # A real recessed entry with two blocks of clear headroom, retaining the
    # left-of-centre doorway and timber frame present in the source drawing.
    parts.append(box((18, 30), (39, 48), (0, 20), [], carve=True))
    parts.append(box((18, 30), (38, 39), (0, 20), [18, 35, 30, 47], side=wood))
    return {"height": 46, "parts": parts, "terrain": []}


def tower_spec():
    wood = [7, 30, 8, 31]
    plaster = [28, 34, 32, 40]
    parts = []
    # Three actual storeys, progressively set back. Intermediate roof textures
    # use the same roof's tile courses rather than stretching painted eave bands
    # down the whole tower side.
    levels = (
        ((8, 56), (26, 74), (0, 24), [8, 64, 56, 80],
         (2, 62), (20, 78), (24, 38), [2, 60, 62, 64]),
        ((12, 52), (30, 70), (38, 58), [12, 48, 52, 60],
         (6, 58), (24, 76), (58, 72), [2, 44, 62, 48]),
        ((16, 48), (34, 66), (72, 92), [12, 32, 52, 44],
         (10, 54), (28, 72), (92, 112), [2, 27, 62, 32]),
    )
    for n, (x, z, y, front, rx, rz, ry, edge) in enumerate(levels):
        parts.append(box(x, z, y, front, top=plaster, side=plaster))
        timber_frame(parts, x, z, y, wood)
        high = ry[1] - ry[0]
        prof = [5, 2, 4, high * .65, high, high * .65, 4, 2, 5]
        # Crosswise taper makes the pagoda's hips and raised corner eaves.
        cross = [5, 2, high * .8, high, high, high, high * .8, 2, 5]
        parts.append(roof(rx, rz, ry, prof, cross=cross,
                          front=edge, top=[8, 0, 56, 28]))
        # Ridge caps on intermediate levels also join the wall above them.
        cz = (rz[0] + rz[1]) / 2
        parts.append(box((x[0] + 2, x[1] - 2), (cz - 2, cz + 2),
                         (ry[1] - 1, ry[1] + 1), [8, 1, 56, 4], top=[8, 1, 56, 3]))
    parts.append(box((18, 30), (69, 80), (0, 20), [], carve=True))
    parts.append(box((18, 30), (68, 69), (0, 20), [18, 67, 30, 79], side=wood))
    # Rock/ground drawn at the bottom corners is outside the building footprint.
    # Parts stay within all originally claimed cells; terrain matching is unchanged.
    return {"height": 113, "parts": parts, "terrain": []}


def generate():
    return {HOUSE: house_spec(), TOWER: tower_spec()}


def apply(table):
    result = copy.deepcopy(table)
    specs = generate()
    found = set()
    for entry in result["buildings"]:
        if entry["id"] not in specs:
            continue
        found.add(entry["id"])
        spec = specs[entry["id"]]
        for key in ("roofRows", "roof", "bands", "parts", "height"):
            entry.pop(key, None)
        entry.update({k: v for k, v in spec.items() if k != "terrain"})
        # Preserve any existing terrain exclusions, rather than changing which
        # source cells the normal world's building matcher claims.
        if "terrain" not in entry:
            entry["terrain"] = spec["terrain"]
    if found != set(specs):
        raise ValueError(f"Missing building templates: {set(specs) - found}")
    return result


def drawing(entry, bundle=BUNDLE):
    from PIL import Image
    rows = entry["tiles"]
    out = Image.new("RGB", (len(rows[0]) * 8, len(rows) * 8))
    pages = {}
    for y, row in enumerate(rows):
        for x, q in enumerate(row):
            page, i = divmod(q, 1024)
            if page not in pages:
                raw = json.loads((bundle / "g2image" / f"g2q.{page}.json").read_text())
                pages[page] = Image.open(io.BytesIO(base64.b64decode(raw["png"]))).convert("RGB")
            sx, sy = i % 32 * 8, i // 32 * 8
            out.paste(pages[page].crop((sx, sy, sx + 8, sy + 8)), (x * 8, y * 8))
    return out


def preview(table, out):
    import numpy as np
    import parts_model
    from PIL import Image
    out.mkdir(parents=True, exist_ok=True)
    for entry in table["buildings"]:
        if entry["id"] not in IDS:
            continue
        name = entry["id"]
        art = drawing(entry)
        art.save(out / f"{name}-source.png")
        art.resize((art.width * 6, art.height * 6), Image.Resampling.NEAREST).save(out / f"{name}-reference.png")
        spec = generate()[name]
        (out / f"{name}-spec.json").write_text(json.dumps(spec, indent=2) + "\n")
        occ, col = parts_model.build(spec, np.asarray(art))
        parts_model.picture(occ, col, out / f"{name}-view.png", s=4)
        parts_model.front_view(occ, col, out / f"{name}-front.png", scale=5)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--write-table", action="store_true")
    p.add_argument("--check", action="store_true")
    p.add_argument("--preview", type=Path)
    args = p.parse_args()
    table = json.loads(TABLE.read_text())
    result = apply(table)
    if args.write_table:
        TABLE.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    if args.check and table != result and not args.write_table:
        raise SystemExit("Crystal building table differs from authoring rules")
    if args.preview:
        preview(result, args.preview)
    print("Crystal trial buildings:", ", ".join(IDS))


if __name__ == "__main__":
    main()
