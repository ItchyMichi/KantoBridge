#!/usr/bin/env python3
"""The marking agents' pictures (LANDSCAPE_SECTIONS_PLAN.md 2.1): each solid region outlined and numbered over a map's art.

    python overlay.py <regions dir> <art dir> <out dir> MAP [MAP ...]

<regions dir>/<MAP>.json is LandscapeRegionsTest's output; <art dir>/<MAP>_art.png kb-hub's render of the map. Each
map gives chunks of at most 32 x 32 cells (<MAP>_r<row>_c<col>.png) with a cell grid every cell (faint) and every 8
cells (stronger, with the cell numbers), each region outlined in its own colour and numbered at a cell of its own, and
an index (<MAP>_chunks.json: each chunk's cell window). Private output: the art is the user's game. Needs Pillow
(kb-pyimg:1).
"""
import json
import os
import sys

from PIL import Image, ImageDraw

CHUNK = 32
PALETTE = [(255, 64, 64), (64, 160, 255), (255, 200, 0), (180, 80, 255), (0, 220, 120), (255, 120, 200), (0, 230, 230),
           (255, 140, 0), (160, 255, 60), (120, 120, 255)]


def main(argv):
    regions_dir, art_dir, out_dir = argv[1:4]
    os.makedirs(out_dir, exist_ok=True)
    for mid in argv[4:]:
        data = json.load(open(os.path.join(regions_dir, mid + ".json")))
        w, h, grid = data["w"], data["h"], data["grid"]
        art = Image.open(os.path.join(art_dir, mid + "_art.png")).convert("RGB")
        px = art.width // w
        # a label cell per region: the cell of the region nearest its bounding box's centre
        label = {}
        for reg in data["regions"]:
            x0, y0, x1, y1 = reg["bbox"]
            cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
            best = None
            for y in range(y0, y1 + 1):
                for x in range(x0, x1 + 1):
                    if grid[y * w + x] == reg["id"]:
                        d = (x - cx) ** 2 + (y - cy) ** 2
                        if best is None or d < best[0]:
                            best = (d, x, y)
            label[reg["id"]] = best[1:]
        chunks = []
        for r0 in range(0, h, CHUNK):
            for c0 in range(0, w, CHUNK):
                r1, c1 = min(h, r0 + CHUNK), min(w, c0 + CHUNK)
                img = art.crop((c0 * px, r0 * px, c1 * px, r1 * px)).convert("RGBA")
                over = Image.new("RGBA", img.size, (0, 0, 0, 0))
                d = ImageDraw.Draw(over)
                for x in range(c0, c1 + 1):
                    strong = x % 8 == 0
                    d.line([((x - c0) * px, 0), ((x - c0) * px, img.height)], fill=(0, 0, 0, 110 if strong else 35), width=2 if strong else 1)
                for y in range(r0, r1 + 1):
                    strong = y % 8 == 0
                    d.line([(0, (y - r0) * px), (img.width, (y - r0) * px)], fill=(0, 0, 0, 110 if strong else 35), width=2 if strong else 1)
                # region outlines: a cell's edge where the neighbour is another region (or none)
                for y in range(r0, r1):
                    for x in range(c0, c1):
                        r = grid[y * w + x]
                        if r < 0:
                            continue
                        col = PALETTE[r % len(PALETTE)] + (255,)
                        X, Y = (x - c0) * px, (y - r0) * px
                        if y == 0 or grid[(y - 1) * w + x] != r:
                            d.line([(X, Y + 1), (X + px, Y + 1)], fill=col, width=3)
                        if y == h - 1 or grid[(y + 1) * w + x] != r:
                            d.line([(X, Y + px - 2), (X + px, Y + px - 2)], fill=col, width=3)
                        if x == 0 or grid[y * w + x - 1] != r:
                            d.line([(X + 1, Y), (X + 1, Y + px)], fill=col, width=3)
                        if x == w - 1 or grid[y * w + x + 1] != r:
                            d.line([(X + px - 2, Y), (X + px - 2, Y + px)], fill=col, width=3)
                for rid, (lx, ly) in label.items():
                    if c0 <= lx < c1 and r0 <= ly < r1:
                        X, Y = (lx - c0) * px + 4, (ly - r0) * px + 4
                        text = str(rid)
                        d.rectangle([X - 2, Y - 2, X + 8 * len(text) + 4, Y + 14], fill=(0, 0, 0, 200))
                        d.text((X, Y), text, fill=PALETTE[rid % len(PALETTE)] + (255,))
                # the cell numbers every 8 cells along the top and left edges
                for x in range(c0, c1):
                    if x % 8 == 0:
                        d.rectangle([(x - c0) * px + 2, 2, (x - c0) * px + 30, 16], fill=(255, 255, 255, 200))
                        d.text(((x - c0) * px + 4, 3), str(x), fill=(0, 0, 0, 255))
                for y in range(r0, r1):
                    if y % 8 == 0:
                        d.rectangle([2, (y - r0) * px + 2, 30, (y - r0) * px + 16], fill=(255, 255, 255, 200))
                        d.text((4, (y - r0) * px + 3), str(y), fill=(0, 0, 0, 255))
                out = Image.alpha_composite(img, over).convert("RGB")
                name = "%s_r%d_c%d.png" % (mid, r0 // CHUNK, c0 // CHUNK)
                out.save(os.path.join(out_dir, name))
                chunks.append({"file": name, "cells": [c0, r0, c1 - 1, r1 - 1]})
        json.dump({"map": mid, "w": w, "h": h, "px_per_cell": px, "chunks": chunks}, open(os.path.join(out_dir, mid + "_chunks.json"), "w"),
                  indent=1)
        print(mid, w, h, "regions", len(data["regions"]), "chunks", len(chunks))


if __name__ == "__main__":
    main(sys.argv)
