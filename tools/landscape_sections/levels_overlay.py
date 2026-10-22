#!/usr/bin/env python3
"""The computer's levels drawn over a map's art (docs/specs/LEVELS_PIPELINE.md, step 2: the review), as the marking
page's 🖥 overlay draws them: each walkable or water cell's level in half cells (a tile row of cliff, 1.5 blocks) over the
window's lowest, a colour per level, the ramps' arrows, the cells the solver left at an unwalkable edge (breaks) boxed
in red.

    python levels_overlay.py <levels.json> <art.png> <out dir> [--window X0,Y0,X1,Y1] [--chunk N] [--plain] [--blocks]

<levels.json>: OutdoorLevelsDumpTest's dump of the map; <art.png>: its art at 48 px a cell. Writes <MAP>_levels_r<row>_c<col>.png,
chunks of at most N x N cells (default 24) with the cell numbers every 4 cells, and <MAP>_levels.json (each chunk's
cells). --plain: the same chunks with the cell numbers only (<MAP>_plain_r<row>_c<col>.png), the drawing to read the
levels against. --blocks: the numbers in blocks over the floor (a ledge's 1-block drop shows; the review agents' unit),
not half cells. Private output: the art is the user's game. Needs Pillow (kb-pyimg:1).
"""
import json
import os
import sys

from PIL import Image, ImageDraw

CELL = 48
COLOURS = [(96, 165, 250), (74, 222, 128), (250, 204, 21), (251, 146, 60), (248, 113, 113), (192, 132, 252), (244, 114, 182),
           (45, 212, 191), (163, 230, 53), (253, 186, 116)]
ARROWS = {"up": "^", "down": "v", "left": "<", "right": ">"}


def main(argv):
    dump = json.load(open(argv[1]))
    art = Image.open(argv[2]).convert("RGB")
    out_dir = argv[3]
    os.makedirs(out_dir, exist_ok=True)
    w, h, kinds, levels = dump["w"], dump["h"], dump["kinds"], dump["levels"]
    x0, y0, x1, y1 = (int(v) for v in argv[argv.index("--window") + 1].split(",")) if "--window" in argv else (0, 0, w, h)
    chunk = int(argv[argv.index("--chunk") + 1]) if "--chunk" in argv else 24
    plain = "--plain" in argv
    blocks = "--blocks" in argv
    ramps = {(r[0], r[1]): r for r in dump.get("ramps", [])}
    breaks = {tuple(b) for b in dump.get("breaks", [])}
    floor = min((levels[y * w + x] for y in range(y0, y1) for x in range(x0, x1) if kinds[y * w + x] != "#"), default=0)
    mid = dump.get("map", os.path.basename(argv[1])[:-5])
    index = []
    for r0 in range(y0, y1, chunk):
        for c0 in range(x0, x1, chunk):
            r1, c1 = min(y1, r0 + chunk), min(x1, c0 + chunk)
            img = art.crop((c0 * CELL, r0 * CELL, c1 * CELL, r1 * CELL)).convert("RGBA")
            over = Image.new("RGBA", img.size, (0, 0, 0, 0))
            d = ImageDraw.Draw(over)
            for y in range(r0, r1):
                for x in range(c0, c1):
                    X, Y = (x - c0) * CELL, (y - r0) * CELL
                    k = kinds[y * w + x]
                    if k == "#" or plain:
                        continue
                    n = levels[y * w + x] - floor if blocks else (levels[y * w + x] - floor) * 2 // 3
                    col = COLOURS[n % len(COLOURS)]
                    d.rectangle([X + 2, Y + 2, X + CELL - 3, Y + CELL - 3], outline=col + (230,), width=2)
                    text = ARROWS.get(ramps[(x, y)][2], "?") + str(n) if (x, y) in ramps else str(n)
                    d.rectangle([X + 12, Y + 14, X + 14 + 8 * len(text), Y + 32], fill=(0, 0, 0, 170 if k != "~" else 110))
                    d.text((X + 14, Y + 17), text, fill=(255, 255, 0, 255) if (x, y) in ramps else col + (255,))
                    if (x, y) in breaks:
                        d.rectangle([X, Y, X + CELL - 1, Y + CELL - 1], outline=(255, 0, 0, 255), width=4)
            for x in range(c0, c1):
                if x % 4 == 0:
                    d.rectangle([(x - c0) * CELL + 2, 2, (x - c0) * CELL + 30, 15], fill=(255, 255, 255, 220))
                    d.text(((x - c0) * CELL + 4, 3), str(x), fill=(0, 0, 0, 255))
            for y in range(r0, r1):
                if y % 4 == 0:
                    d.rectangle([2, (y - r0) * CELL + 2, 30, (y - r0) * CELL + 15], fill=(255, 255, 255, 220))
                    d.text((4, (y - r0) * CELL + 3), str(y), fill=(0, 0, 0, 255))
            name = f"{mid}_{'plain' if plain else 'levels'}_r{(r0 - y0) // chunk}_c{(c0 - x0) // chunk}.png"
            Image.alpha_composite(img, over).convert("RGB").save(os.path.join(out_dir, name))
            index.append({"file": name, "cells": [c0, r0, c1 - 1, r1 - 1]})
    with open(os.path.join(out_dir, f"{mid}_levels.json"), "w") as fh:
        json.dump({"map": mid, "floor": floor, "unit": "blocks over the floor" if blocks else "half cells over the floor (1.5 blocks)",
                   "chunks": index}, fh, indent=1)
    print(mid, "floor", floor, "chunks", len(index))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
