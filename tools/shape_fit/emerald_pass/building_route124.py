"""Source-proven b112 Treasure Hunter house addition; local candidate only."""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path

from tools.shape_fit.emerald import building_trials as p
from tools.shape_fit.emerald_pass.buildings import BASELINE, BUNDLE, ROOT, digest
from tools.shape_fit.emerald_pass.buildings_west import box, material

OUT = ROOT / "local/model-gallery/emerald-pass-study/building-route124"
ID = "emerald_b112_EM_ROUTE124"
SOURCE_SHA256 = "354a6b3693dceff4fd038ca582b055caf1f2849b868ff4263c27c2a47a9a7585"
SOURCE_RGBA_SHA256 = "be71ae4595a52de1d8717e419c68b47cd35fa42d81e49109bd7126665f26b632"
LAYOUT = [
    ["g3q/12506-10660-12507-10662", "g3q/10660-10660-10662-10662", "g3q/10660-10660-10662-10662", "g3q/10660-10661-10662-10663"],
    ["g3q/10786-10787-10786-10787", "g3q/10787-10787-10787-10787", "g3q/10787-10787-10787-10787", "g3q/10787-10827-10787-10827"],
    ["g3q/22036-10692-22037-10987", "g3q/10692-10692-11145-11146", "g3q/10692-10692-10693-10694", "g3q/10692-22038-10987-22039"],
    ["g3q/22045-10987-22046-11412", "g3q/11453-11454-11455-11456", "g3q/10850-10851-10852-10852", "g3q/10987-22039-11412-22047"],
]


def tiles():
    """Preserve all four quarters of each exact exported source cell."""
    result = []
    for row in LAYOUT:
        quarters = [list(map(int, key.split("/")[1].split("-"))) for key in row]
        result.extend([[value for q in quarters for value in q[:2]],
                       [value for q in quarters for value in q[2:]]])
    return result


def generate():
    # This is the same source architecture as b051, with four different coastal
    # facade corners. The two roof tiers use their own source bands, not a house
    # family substituted for an unrelated drawing. The WALK rear row is solely
    # a source projection: all physical parts stay in z16..64.
    wall_side = [10, 42, 15, 63]
    parts = [
        box((2, 62), (18, 61), (0, 28),
            p.paint([2, 39, 62, 64], [10, 42, 15, 46], wall_side, wall_side)),
        {"shape": "profile", "x": [0, 64], "z": [16, 64], "y": [28, 42], "along": "x",
         "profile": [12, 14, 14, 14, 14, 14, 13, 12, 10, 8, 6, 4, 2, 0],
         "cross": [8, 14, 14, 14, 14, 14, 14, 14, 14, 14, 8],
         "paint": p.paint([2, 35, 62, 40], [2, 16, 62, 39], [1, 18, 4, 38], [4, 18, 60, 26])},
        {"shape": "profile", "x": [4, 60], "z": [16, 34], "y": [40, 54], "along": "x",
         "profile": [3, 7, 10, 12, 13, 14, 14, 14],
         "cross": [6, 14, 14, 14, 14, 14, 14, 14, 14, 6],
         "paint": p.paint([4, 12, 60, 18], [4, 1, 60, 13], [1, 2, 4, 12], [4, 2, 60, 10])},
        box((0, 64), (60, 64), (27, 30), p.paint([0, 35, 64, 39], [0, 35, 64, 37], [1, 35, 4, 39])),
        # The source warp is local (1,3): its red door is recessed in that cell.
        box((16, 32), (55, 64), (0, 25), carve=True),
        box((16, 32), (55, 56), (0, 25), p.paint([16, 44, 32, 63], side=[16, 44, 17, 63])),
        box((14, 34), (56, 64), (0, 2), material((3, 63, 5, 64))),
        box((34, 47), (61, 62), (8, 25), p.paint([34, 41, 47, 55], side=[34, 41, 35, 55])),
    ]
    return {ID: {"id": ID, "atlas": "g3q", "tiles": tiles(), "parts": parts,
                 "height": 58, "terrain": [[x, 0] for x in range(4)]}}


def source_evidence(art, entry):
    from tools import model_gallery
    item = model_gallery.building_catalog({"game": "emerald", "cells": {}, "buildings": [entry]}, art)[0]
    occurrences = item["occurrences"]
    assert occurrences, "The added template must exactly match exported source maps"
    evidence = []
    for location in occurrences:
        source = art.maps[location["map"]]
        raw = base64.b64decode(source["cells"])
        cells = []
        for dy, row in enumerate(LAYOUT):
            for dx, key in enumerate(row):
                x, y = location["x"] + dx, location["y"] + dy
                i = (y * source["w"] + x) * 4
                cells.append({"x": x, "y": y, "local": [dx, dy], "key": key,
                              "metatile": int.from_bytes(raw[i:i + 2], "little"),
                              "collision_byte": raw[i + 2], "elevation": raw[i + 3]})
        warps = [warp for warp in source.get("warps", [])
                 if location["x"] <= warp["x"] < location["x"] + 4
                 and location["y"] <= warp["y"] < location["y"] + 4]
        evidence.append({**location, "width": 4, "depth": 4, "cells": cells, "warps": warps})
    return evidence


def main():
    from PIL import Image, ImageDraw
    import numpy as np
    from tools import model_gallery
    from tools.shape_fit.emerald import parts_model
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=OUT)
    parser.add_argument("--previews", action="store_true")
    args = parser.parse_args()
    baseline = json.loads(BASELINE.read_text())
    entry = generate()[ID]
    assert all(old["id"] != ID and old["tiles"] != entry["tiles"] for old in baseline["buildings"])
    art = model_gallery.EmeraldArt(BUNDLE)
    source = p.drawing(entry, BUNDLE)
    source_hash = hashlib.sha256(source.tobytes()).hexdigest()
    assert source_hash == SOURCE_SHA256
    assert hashlib.sha256(art.drawing(LAYOUT, "BAKED").tobytes()).hexdigest() == SOURCE_RGBA_SHA256
    occurrences = source_evidence(art, entry)
    args.out.mkdir(parents=True, exist_ok=True)
    source.save(args.out / "source.png")
    source.resize((640, 640), Image.Resampling.NEAREST).save(args.out / "source-enlarged.png")
    for number, location in enumerate(occurrences):
        map_image = art.map_image(location["map"]).convert("RGB")
        x, y = location["x"] * 16, location["y"] * 16
        crop = map_image.crop((x - 32, y - 32, x + 96, y + 96))
        ImageDraw.Draw(crop).rectangle((32, 32, 95, 95), outline="red")
        crop.resize((512, 512), Image.Resampling.NEAREST).save(args.out / f"context-{number:02}.png")
    if args.previews:
        occ, colors = parts_model.build(entry, np.asarray(source))
        parts_model.picture(occ, colors, args.out / "view.png", 4)
        parts_model.front_view(occ, colors, args.out / "front.png", 6)
        parts_model.front_view(np.transpose(occ, (0, 2, 1))[:, :, ::-1],
                               np.transpose(colors, (0, 2, 1, 3))[:, :, ::-1], args.out / "side.png", 6)
    relative = str(args.out.relative_to(ROOT)).replace("\\", "/")
    row = {"key": ID, "kind": "building", "label": "Route124 Diving Treasure Hunter house",
           "family": "stepped-red-coastal-house", "decision": "added", "baseline_absent": True,
           "baseline_entry_sha256": None, "proposed_entry_sha256": digest(entry),
           "source_pixel_sha256": source_hash, "source_pixel_mode": "RGB",
           "source_rgba_pixel_sha256": SOURCE_RGBA_SHA256, "occurrences": occurrences,
           "reason": "The exact 4x4 Route124 house is absent from all112 existing templates: b051 matches only12/16 cells. Add the four coastal facade corners and exact source layout, with two curved red roof tiers, gold walls, separate six-pane window, source-warp recessed red door and gray threshold. The rear WALK projection row remains terrain; no part crosses it.",
           "evidence": [{"source": f"{relative}/source.png", "context": f"{relative}/context-00.png", "source_pixels_sha256": source_hash},
                        {"gap_record": "tools/shape_fit/emerald_pass/coverage_gaps.json", "gap_id": "emerald-route124-treasure-hunters-house"},
                        {"nearest_existing_template": "emerald_b051_EM_LAVARIDGE_TOWN", "matching_cells": 12, "total_cells": 16,
                         "different_local_cells": [[0, 2], [3, 2], [0, 3], [3, 3]]},
                        {"python_front": f"{relative}/front.png", "python_side": f"{relative}/side.png", "python_angled": f"{relative}/view.png"}],
           "visual_status": "source inspected; candidate Python/Minecraft comparison pending", "user_approved": False}
    for name, value in {"updates.json": {"cells": {}, "buildings": {ID: entry}}, "added-ledger.json": [row],
                        "occurrences.json": occurrences, "spec.json": entry}.items():
        (args.out / name).write_text(json.dumps(value, indent=2) + "\n")
    print(json.dumps({"id": ID, "occurrences": len(occurrences), "proposed_entry_sha256": digest(entry), "out": relative}))


if __name__ == "__main__":
    main()
