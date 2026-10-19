#!/usr/bin/env python3
"""Emerald's map pictures drawn from the game's own art (docs/specs/PRODUCTION_PIPELINE_EMERALD.md), not from
hidden-game screenshots: no sprites, popups, offsets or missing tiles.

    python render_maps.py <export bundle dir> <kb-hub maps dir (images/maps/emerald)> <kb-hub sprites dir> [MAP ...]

A cell's key `g3q/a-b-c-d` names its four 8 x 8 quarters (a b over c d), already flattened from both metatile layers
by the export: quarter q is tile q % 1024 of page `g3image/g3q.<q // 1024>` (256 x 256, 32 tiles a row). For every
map with a kb-hub info file (`<MAP>.json`: w, h, keys, cell) this writes `<MAP>_art.png` (48 px a cell) and
`<MAP>_grid.png` (the same with a magenta cell grid and each cell's column,row in yellow), and each key's sprite
`<sprites>/<key with / as _>.png` (16 px). The old pictures are kept as `<MAP>_art.mosaic.png` / `_grid.mosaic.png`.
"""
from __future__ import annotations

import base64
import io
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ART = 48
CELL = 16


def main() -> int:
    bundle, maps_dir, sprites_dir = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    only = set(sys.argv[4:])
    pages: dict[int, Image.Image] = {}

    def page(n: int) -> Image.Image | None:
        if n not in pages:
            f = bundle / "g3image" / f"g3q.{n}.json"
            if not f.is_file():
                pages[n] = None
            else:
                data = json.loads(f.read_text(encoding="utf-8"))
                pages[n] = Image.open(io.BytesIO(base64.b64decode(data["png"]))).convert("RGBA")
        return pages[n]

    def quarter(q: int) -> Image.Image:
        p = page(q // 1024)
        if p is None:
            return Image.new("RGBA", (8, 8), (255, 0, 255, 255))
        i = q % 1024
        return p.crop(((i % 32) * 8, (i // 32) * 8, (i % 32) * 8 + 8, (i // 32) * 8 + 8))

    sprites_dir.mkdir(parents=True, exist_ok=True)
    cells: dict[str, Image.Image] = {}

    def cell_art(key: str) -> Image.Image:
        if key not in cells:
            ids = [int(v) for v in key.rsplit("/", 1)[1].split("-")]
            im = Image.new("RGBA", (CELL, CELL), (0, 0, 0, 255))
            for k, q in enumerate(ids[:4]):
                t = quarter(q)
                im.paste(t, ((k % 2) * 8, (k // 2) * 8), t)
            cells[key] = im.convert("RGB")
            cells[key].save(sprites_dir / (key.replace("/", "_") + ".png"))
        return cells[key]

    n = 0
    for info_f in sorted(maps_dir.glob("*.json")):
        if info_f.name == "index.json" or (only and info_f.stem not in only):
            continue
        info = json.loads(info_f.read_text(encoding="utf-8"))
        if "keys" not in info:
            continue
        w, h = info["w"], info["h"]
        art = Image.new("RGB", (w * CELL, h * CELL))
        for y in range(h):
            for x in range(w):
                art.paste(cell_art(info["keys"][info["cell"][y * w + x]]), (x * CELL, y * CELL))
        art = art.resize((w * ART, h * ART), Image.NEAREST)
        m = info_f.stem
        for suffix in ("_art", "_grid"):
            old = maps_dir / f"{m}{suffix}.png"
            keep = maps_dir / f"{m}{suffix}.mosaic.png"
            if old.is_file() and not keep.is_file():
                old.rename(keep)
        art.save(maps_dir / f"{m}_art.png", optimize=True)
        grid = art.copy()
        g = ImageDraw.Draw(grid)
        for x in range(w + 1):
            g.line([(x * ART, 0), (x * ART, h * ART)], fill=(255, 0, 255), width=1)
        for y in range(h + 1):
            g.line([(0, y * ART), (w * ART, y * ART)], fill=(255, 0, 255), width=1)
        for y in range(h):
            for x in range(w):
                g.text((x * ART + 2, y * ART + 1), f"{x},{y}", fill=(255, 255, 0), stroke_width=1, stroke_fill=(0, 0, 0))
        grid.save(maps_dir / f"{m}_grid.png", optimize=True)
        n += 1
    print(n, "maps drawn,", len(cells), "cell sprites")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
