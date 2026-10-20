#!/usr/bin/env python3
"""A parts package per building design (docs/specs/PRODUCTION_PIPELINE_EMERALD.md, "Buildings as parts", step 1).

    python parts_package.py <game> <kb-hub data dir> <building_batch out dir> <packages dir> [--only ID,ID]

For every design in `<building_batch out>/<game>/buildings.json` this writes `<packages>/<id>/`: `drawing.png` (the
entity's cells at 16 px a cell), `reference_grid.png` (10x, a line every 4 px, magenta every 16, pixel numbers every 8),
`info.json` (game, map, box, size, the marking's note, placements) and `old_box_model.png` (the box generator's view).
A package that already has a `spec.json` is left alone.
"""
from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

from PIL import Image, ImageDraw

ART = 48
K = 10
PAD = 40


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("game")
    ap.add_argument("data", type=Path)
    ap.add_argument("bld", type=Path)
    ap.add_argument("out", type=Path)
    ap.add_argument("--only", default="")
    a = ap.parse_args()
    only = set(filter(None, a.only.split(",")))
    designs = json.loads((a.bld / a.game / "buildings.json").read_text(encoding="utf-8"))
    n = 0
    for d in designs:
        if only and d["id"] not in only:
            continue
        out = a.out / d["id"]
        if (out / "spec.json").is_file():
            continue
        x0, y0, w, h = d["box"]
        art = Image.open(a.data / "images" / "maps" / a.game / f"{d['map']}_art.png").convert("RGB")
        d16 = art.crop((x0 * ART, y0 * ART, (x0 + w) * ART, (y0 + h) * ART)).resize((w * 16, h * 16), Image.NEAREST)
        out.mkdir(parents=True, exist_ok=True)
        d16.save(out / "drawing.png")
        ref = d16.resize((w * 16 * K, h * 16 * K), Image.NEAREST)
        canvas = Image.new("RGB", (ref.width + PAD, ref.height + PAD), (255, 255, 255))
        canvas.paste(ref, (PAD, PAD))
        g = ImageDraw.Draw(canvas)
        for px in range(0, w * 16 + 1, 4):
            major = px % 16 == 0
            g.line([(PAD + px * K, PAD), (PAD + px * K, PAD + ref.height)], fill=(255, 0, 255) if major else (0, 200, 255),
                   width=2 if major else 1)
            if px % 8 == 0:
                g.text((PAD + px * K + 2, 4), str(px), fill=(0, 0, 0))
        for py in range(0, h * 16 + 1, 4):
            major = py % 16 == 0
            g.line([(PAD, PAD + py * K), (PAD + ref.width, PAD + py * K)], fill=(255, 0, 255) if major else (0, 200, 255),
                   width=2 if major else 1)
            if py % 8 == 0:
                g.text((4, PAD + py * K + 2), str(py), fill=(0, 0, 0))
        canvas.save(out / "reference_grid.png")
        view = a.bld / a.game / f"{d['id']}_view.png"
        if view.is_file():
            shutil.copy(view, out / "old_box_model.png")
        (out / "info.json").write_text(json.dumps({
            "game": a.game, "map": d["map"], "box": d["box"], "width_px": w * 16, "depth_px": h * 16,
            "note": d.get("note", ""), "placements": d.get("placements", [])}, indent=1), encoding="utf-8")
        n += 1
    print(n, "packages written")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
