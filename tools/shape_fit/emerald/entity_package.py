#!/usr/bin/env python3
"""Fitted shapes: a Blockbench package for each entity the user marked "make by hand" on kb-hub's maps page
(docs/specs/FITTED_SHAPES.md section 15), in survey_structures.py's format so its PROMPT.md applies.

    python entity_package.py <game> <map> <flags_<game>.json> <map art.png> <map info.json> <out dir>

Per entity (its largest 4-connected group of cells, as entity_depth.py takes it): reference.png (the cells from the
map's picture, cells of the box outside the entity dimmed), reference_grid.png, context.png (3 cells around),
spec.json (cells, kinds, keys, the user's note and background cells), starter.bbmodel (one 2-block box per cell, the
reference as texture) and README.md. Out: <out>/<game>/<game>_<map>_hand<nn>/.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
from entity_depth import tidy  # noqa: E402
from survey_structures import PROMPT, starter  # noqa: E402

ART = 48
W = 16
MARGIN = 3


def main() -> int:
    game, map_id, flags_f, art_f, info_f, out_dir = sys.argv[1:7]
    ents = json.load(open(flags_f)).get("_entities", {}).get(map_id, [])
    art = Image.open(art_f).convert("RGBA")
    info = json.load(open(info_f))
    taken = {tuple(c) for e in ents for c in e["cells"]}
    out = Path(out_dir) / game
    out.mkdir(parents=True, exist_ok=True)
    (Path(out_dir) / "PROMPT.md").write_text(PROMPT, encoding="utf-8")
    n = 0
    for e in ents:
        if e["kind"] != "make by hand":
            continue
        e = tidy(e, taken)
        x0, y0, w, h = e["box"]
        cells_set = {tuple(c) for c in e["cells"]}
        sid = f"{game}_{map_id}_hand{n:02d}"
        n += 1
        d = out / sid
        d.mkdir(exist_ok=True)
        crop = art.crop((x0 * ART, y0 * ART, (x0 + w) * ART, (y0 + h) * ART))
        px = np.asarray(crop).copy()
        for y in range(h):
            for x in range(w):
                if (x0 + x, y0 + y) not in cells_set:
                    px[y * ART:(y + 1) * ART, x * ART:(x + 1) * ART, :3] //= 3
        draw16 = Image.fromarray(px, "RGBA").resize((w * W, h * W), Image.NEAREST)
        ref = draw16.resize((w * W * 8, h * W * 8), Image.NEAREST)
        ref.save(d / "reference.png")
        grid = ref.copy()
        gd = ImageDraw.Draw(grid)
        for y in range(h):
            for x in range(w):
                gd.rectangle([x * 128, y * 128, x * 128 + 127, y * 128 + 127], outline=(255, 0, 255, 255))
                gd.text((x * 128 + 4, y * 128 + 4), f"{x},{y}", fill=(255, 0, 255, 255))
        grid.save(d / "reference_grid.png")
        ctx = art.crop((max(0, (x0 - MARGIN) * ART), max(0, (y0 - MARGIN) * ART), min(art.width, (x0 + w + MARGIN) * ART),
                        min(art.height, (y0 + h + MARGIN) * ART)))
        ctx.resize((ctx.width * 2, ctx.height * 2), Image.NEAREST).save(d / "context.png")
        layout = [[None] * w for _ in range(h)]
        for (cx, cy), k in zip(e["cells"], e["keys"]):
            layout[cy - y0][cx - x0] = k
        heights = [[2 if layout[y][x] else 0 for x in range(w)] for y in range(h)]
        spec = {"id": sid, "game": game, "map": map_id, "type": "entity (make by hand)", "note": e.get("note", ""),
                "size_cells": [w, h], "footprint_blocks": [w * 2, h * 2], "at": [x0, y0],
                "cells": [[{"key": info["keys"][info["cell"][(y0 + y) * info["w"] + x0 + x]],
                            "kind": info["kind"][(y0 + y) * info["w"] + x0 + x], "part": layout[y][x] is not None}
                           for x in range(w)] for y in range(h)],
                "background": e.get("background", []), "strays_ignored": e.get("strays", []), "today_blocks": heights}
        (d / "spec.json").write_text(json.dumps(spec, indent=1), encoding="utf-8")
        (d / "starter.bbmodel").write_text(json.dumps(starter(sid, draw16, layout, heights)), encoding="utf-8")
        (d / "README.md").write_text(
            f"# {sid}\n\n{e.get('note') or 'An object'} on {map_id}, {w} x {h} cells ({w * 2} x {h * 2} blocks), "
            f"{len(e['cells'])} cells of it (spec.json `part`).\n\nRead ../../PROMPT.md for the conventions. Start from "
            "starter.bbmodel, save model.bbmodel here.\n", encoding="utf-8")
        print(sid, f"{w}x{h}", len(e["cells"]), "cells", "strays dropped:", e.get("strays"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
