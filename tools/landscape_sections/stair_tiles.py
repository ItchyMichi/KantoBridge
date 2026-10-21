#!/usr/bin/env python3
"""The tiles under the user's slope marks (docs/specs/LEVELS_PIPELINE.md, step 4): the drawing's stairs and slopes a
game's table can name, so the computer finds them on every map without marks.

    python stair_tiles.py <marks.json> <levels.json> <tiles.json>

<marks.json>: the height-marking page's marks (kb-hub's data/levels/marks/<MAP>.json); <levels.json>: OutdoorLevelsDumpTest's
dump of the map (its "w"); <tiles.json>: each cell's tile, row by row: the dump's <MAP>_mids.json for Emerald ({"pair",
"mids": [metatile id, or -1 outside the map's grid]}), or {"keys": [...]} for a game keyed otherwise.

Per tile under an arrow: the arrows on it (by their way up), its cells in the marks' view, how many of those the user
marked as slopes and how many as plain ground. A tile every marked cell of which is a slope is a stair the game's table
can take (`floor_kinds/<game>.json`; Emerald keys a metatile under 512 by the pair's first tileset, the rest by its
second: "general/175"). Plain Python.
"""
import json
import sys
from collections import Counter, defaultdict


def tile_keys(tiles):
    """Each cell's tile key: Emerald's metatile ids, or a game's own keys."""
    return tiles["mids"] if "mids" in tiles else tiles["keys"]


def under_arrows(marks, w, keys):
    """[{tile, arrows {way: n}, cells, slopes, ground}], the tiles under the user's arrows, most arrows first: cells counts
    the tile's cells in the view, slopes and ground how many of them the user marked as each."""
    x0, y0, x1, y1 = marks["view"]
    slopes = {(x, y): d for x, y, d in marks["slopes"]}
    ground = {(x, y) for x, y, _ in marks["levels"]} - set(slopes)
    arrows = defaultdict(Counter)
    for (x, y), d in slopes.items():
        arrows[keys[y * w + x]][d] += 1
    out = []
    for tile, ways in arrows.items():
        cells = [(x, y) for y in range(y0, y1) for x in range(x0, x1) if keys[y * w + x] == tile]
        out.append({"tile": tile, "arrows": dict(ways), "cells": len(cells), "slopes": sum(1 for c in cells if c in slopes),
                    "ground": sum(1 for c in cells if c in ground)})
    return sorted(out, key=lambda t: (-sum(t["arrows"].values()), str(t["tile"])))


def emerald_key(pair, mid, primary=512):
    """Emerald's table key for a metatile: its tileset's name and its id."""
    names = pair.split("__", 1)
    return f"{names[0] if mid < primary or len(names) < 2 else names[1]}/{mid}"


def main(argv):
    marks = json.load(open(argv[1]))
    w = json.load(open(argv[2]))["w"]
    tiles = json.load(open(argv[3]))
    for t in under_arrows(marks, w, tile_keys(tiles)):
        key = emerald_key(tiles["pair"], t["tile"]) if "pair" in tiles else t["tile"]
        verdict = "a stair: every marked cell of it is a slope" if t["ground"] == 0 else "also marked as plain ground"
        print(f"{key}: arrows {t['arrows']}, {t['cells']} cells in the view, {t['slopes']} marked as slopes, "
              f"{t['ground']} as ground: {verdict}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
