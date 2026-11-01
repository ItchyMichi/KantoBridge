#!/usr/bin/env python3
"""Reproducible hand-authored Emerald building trials, using source-art UVs.

Only three buildings' parts and height are replaced. IDs, match grids, terrain,
all cell models and the other building templates remain unchanged.
"""
from __future__ import annotations

import argparse
import base64
import io
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TABLE = ROOT / "fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json"
IDS = ("emerald_b000_EM_LITTLEROOT_TOWN", "emerald_b002_EM_LITTLEROOT_TOWN", "emerald_b003_EM_FORTREE_CITY")


def paint(front, top=None, side=None, back=None):
    return {k: v for k, v in dict(front=front, top=top, side=side, back=back).items() if v is not None}


def box(x, z, y, p=None, carve=False):
    result = dict(shape="box", x=x, z=z, y=y)
    if p is not None:
        result["paint"] = p
    if carve:
        result["carve"] = True
    return result


def round_part(shape, center, radius, y, p=None, carve=False):
    result = dict(shape=shape, center=center, radius=radius, y=y)
    if p is not None:
        result["paint"] = p
    if carve:
        result["carve"] = True
    return result


def house():
    wall = paint([3, 56, 77, 80], [7, 60, 13, 72], [7, 60, 13, 78])
    fascia = paint([8, 36, 72, 44], [8, 36, 72, 38], [16, 36, 20, 44])
    parts = [
        box([4, 76], [16, 76], [0, 32], wall),
        # The low outer roof is an awning/skirt around the main roof, not a
        # second exposed storey. The tall source roof has one coherent gable.
        dict(shape="hip", x=[0, 80], z=[12, 80], y=[30, 40],
             paint=paint([2, 46, 78, 55], [2, 18, 78, 52], [4, 46, 8, 55])),
        box([8, 72], [9, 65], [38, 42], fascia),
        dict(shape="gable", ridge="x", x=[8, 72], z=[8, 66], y=[42, 65],
             paint=paint([10, 30, 70, 36], [10, 2, 70, 35], [7, 60, 13, 78], [10, 30, 70, 36])),
        # Ridge tile and the front eave give the layered roof a readable edge.
        box([8, 72], [35, 39], [64, 66], paint([10, 1, 70, 5], [10, 1, 70, 5], [9, 2, 12, 5])),
        box([8, 72], [64, 67], [39, 42], fascia),
        # Actual inset door, with a painted slab at the rear of the recess.
        box([48, 64], [72, 78], [0, 27], carve=True),
        box([48, 64], [72, 73], [0, 27], paint([48, 59, 64, 79], [48, 59, 64, 60], [48, 60, 49, 79])),
        box([46, 66], [73, 78], [0, 2], paint([46, 77, 66, 80], [46, 77, 66, 80], [46, 77, 49, 80])),
        box([16, 32], [76, 77], [12, 27], paint([16, 59, 32, 70], [16, 59, 32, 60], [16, 60, 17, 70])),
        # One side window, rather than repeating the front door frame as siding.
        box([76, 77], [34, 50], [12, 27], paint([16, 60, 17, 70], [16, 59, 32, 60], [16, 59, 32, 70])),
    ]
    return dict(height=68, parts=parts)


def lab():
    wall = paint([2, 54, 110, 80], [8, 56, 14, 72], [8, 56, 14, 80])
    roof = paint([2, 48, 110, 54], [56, 17, 108, 51], [56, 48, 64, 54])
    metal = paint([18, 24, 46, 30], [18, 23, 46, 28], [18, 24, 22, 30])
    silver = paint([22, 6, 23, 7], [22, 6, 23, 7], [22, 6, 23, 7])
    dark = paint([30, 9, 31, 10], [30, 9, 31, 10], [30, 9, 31, 10])
    parts = [
        box([3, 109], [10, 76], [0, 36], wall),
        dict(shape="profile", along="x", x=[1, 111], z=[6, 79], y=[36, 49],
             profile=[12, 12, 10, 8, 6, 4, 2], paint=roof),
        box([1, 111], [4, 9], [47, 50], paint([1, 7, 111, 15], [1, 1, 111, 7], [52, 2, 56, 14])),
        # Roof equipment plinth and a shallow, genuinely hollow dish.
        box([18, 48], [17, 39], [41, 50], metal),
        round_part("cylinder", [33, 27], 5, [49, 54], metal),
        round_part("cylinder", [33, 27], 13, [53, 59], silver),
        round_part("cylinder", [33, 27], 11, [55, 62], carve=True),
        # Dark inner basin floor plus the receiver, contained inside the rim.
        round_part("cylinder", [33, 27], 10.5, [54, 55], paint([25, 15, 26, 16], [25, 15, 26, 16])),
        box([32, 34], [26, 28], [55, 61], dark),
        box([30, 36], [24, 30], [60, 62], metal),
        box([70, 82], [72, 79], [0, 28], carve=True),
        box([70, 82], [72, 73], [0, 28], paint([70, 60, 82, 80], [70, 60, 82, 61], [70, 60, 71, 80])),
        box([68, 84], [73, 79], [0, 2], paint([69, 77, 83, 80], [69, 77, 83, 80], [70, 77, 73, 80])),
        box([16, 48], [76, 77], [12, 31], paint([16, 56, 48, 70], [16, 56, 48, 57], [16, 56, 17, 70])),
        box([109, 110], [33, 49], [12, 29], paint([16, 58, 17, 70], [16, 58, 32, 59], [16, 58, 32, 70])),
    ]
    return dict(height=64, parts=parts)


def center():
    # The drawing's bevelled bottom corners include grass. Keep the wall UV
    # wholly inside its straight facade rather than stretching that background.
    wall = paint([7, 43, 57, 63], [8, 46, 12, 55], [8, 46, 12, 64])
    red = paint([8, 28, 9, 29], [8, 28, 9, 29], [8, 28, 9, 29])
    roof = paint([8, 27, 12, 30], [16, 8, 48, 24], [8, 27, 12, 30])
    parts = [
        box([4, 60], [12, 61], [0, 28], wall),
        # A continuous rounded roof, with the front medallion on a separate
        # vertical sign; the original two overlapping roofs distorted it.
        dict(shape="profile", along="z", x=[0, 64], z=[6, 63], y=[28, 57],
             profile=[9, 14, 14, 20, 27, 29, 27, 20, 14, 14, 9],
             cross=[8, 18, 27, 29, 29, 25, 19, 10], paint=roof),
        box([2, 62], [58, 63], [28, 38], red),
        # White roof-edge trim and central Poké Ball medallion are real parts.
        box([3, 61], [60, 63], [26, 28], paint([3, 40, 61, 43], [3, 40, 61, 42], [3, 40, 6, 43])),
        box([24, 40], [61, 64], [29, 45], paint([24, 24, 40, 40], [24, 24, 40, 25], [24, 24, 25, 40])),
        box([16, 32], [57, 63], [0, 26], carve=True),
        box([16, 32], [57, 58], [0, 26], paint([16, 44, 32, 64], [16, 44, 32, 45], [16, 44, 17, 64])),
        box([14, 34], [58, 63], [0, 2], paint([16, 62, 32, 64], [16, 62, 32, 64], [16, 62, 20, 64])),
        box([40, 56], [61, 62], [10, 26], paint([40, 44, 56, 56], [40, 44, 56, 45], [40, 44, 41, 56])),
        box([60, 61], [30, 46], [10, 24], paint([16, 44, 17, 56], [16, 44, 32, 45], [16, 44, 32, 56])),
    ]
    return dict(height=60, parts=parts)


def generate():
    return dict(zip(IDS, (house(), lab(), center())))


def drawing(entry, bundle):
    from PIL import Image
    pages = {}
    rows = entry["tiles"]
    result = Image.new("RGB", (len(rows[0]) * 8, len(rows) * 8))
    for y, row in enumerate(rows):
        for x, quarter in enumerate(row):
            page, q = divmod(quarter, 1024)
            if page not in pages:
                doc = json.loads((bundle / "g3image" / f"g3q.{page}.json").read_text())
                pages[page] = Image.open(io.BytesIO(base64.b64decode(doc["png"]))).convert("RGB")
            result.paste(pages[page].crop((q % 32 * 8, q // 32 * 8, q % 32 * 8 + 8, q // 32 * 8 + 8)), (x * 8, y * 8))
    return result


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--write-table", action="store_true")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--preview", type=Path)
    ap.add_argument("--bundle", type=Path, default=ROOT / "local/minecraft/kantobridge/export/emerald")
    args = ap.parse_args()
    table = json.loads(TABLE.read_text(encoding="utf-8"))
    updates = generate()
    entries = {b["id"]: b for b in table["buildings"]}
    if args.check and any(any(entries[name].get(k) != v for k, v in update.items()) for name, update in updates.items()):
        raise SystemExit("Emerald building trials differ from their authoring rules")
    if args.preview:
        import numpy as np
        import parts_model
        for name, update in updates.items():
            entry = {**entries[name], **update}
            out = args.preview / name
            out.mkdir(parents=True, exist_ok=True)
            art = drawing(entry, args.bundle)
            art.save(out / "drawing.png")
            from PIL import Image
            art.resize((art.width * 5, art.height * 5), Image.Resampling.NEAREST).save(out / "source.png")
            occ, colors = parts_model.build(entry, np.asarray(art))
            parts_model.picture(occ, colors, out / "view.png", 4)
            parts_model.front_view(occ, colors, out / "front.png", 5)
            (out / "spec.json").write_text(json.dumps(entry, indent=2) + "\n")
            print(name, "previewed", flush=True)
    if args.write_table:
        for name, update in updates.items():
            entries[name].update(update)
        TABLE.write_text(json.dumps(table, indent=2) + "\n", encoding="utf-8")
    print("Three Emerald building trials: layered roof house, dish lab, rounded-roof Center")


if __name__ == "__main__":
    main()
