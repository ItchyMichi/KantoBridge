#!/usr/bin/env python3
"""A levels review's pins in the landscape models' table (docs/specs/LEVELS_PIPELINE.md, step 2): the planner sets the
ground round each pinned cell, as far as it stands level with it, to the pin's level (OutdoorLevels.pinned).

    python pins_table.py <table.json> <review dir> [MAP ...]

<review dir>/<MAP>.json: {"floor": blocks, "pins": [[x, y, n], ...], ...}, map cells and n over "floor", the computer's
lowest level in the reviewed view (levels_overlay.py's <MAP>_levels.json "floor"): with "unit": "blocks" n is in blocks
(the review agents', levels_overlay.py --blocks), else in half cells (a tile row of cliff, 1.5 blocks; the user's marks'
unit) and even, so a pin stands on whole blocks. The table's "pins" gets each map's list in blocks over the map's floor
(every map with a file, or the maps named). Plain Python.
"""
import json
import os
import sys


def read_pins(path):
    """A review's pins in blocks over the map's floor: [[x, y, blocks], ...] sorted."""
    review = json.load(open(path))
    floor = review.get("floor", 0)
    in_blocks = review.get("unit") == "blocks"
    out = []
    for p in review.get("pins", []):
        if not (isinstance(p, list) and len(p) == 3 and all(isinstance(v, int) and not isinstance(v, bool) for v in p)):
            raise ValueError(f"{path}: bad pin {p!r}")
        if p[2] < 0 or not in_blocks and p[2] % 2:
            raise ValueError(f"{path}: pin {p!r}: the level must be 0 or more (in half cells, an even number)")
        out.append([p[0], p[1], floor + (p[2] if in_blocks else p[2] * 3 // 2)])
    return sorted(out)


def add_pins(table, review_dir, maps=None):
    """{map: [[x, y, blocks], ...]} for every <MAP>.json in review_dir (or the maps named), put in table["pins"]."""
    names = maps or sorted(f[:-5] for f in os.listdir(review_dir) if f.endswith(".json"))
    table.setdefault("pins", {})
    for name in names:
        table["pins"][name] = read_pins(os.path.join(review_dir, name + ".json"))
    return table


def main(argv):
    path, review_dir = argv[1], argv[2]
    table = json.load(open(path))
    add_pins(table, review_dir, argv[3:])
    with open(path, "w") as fh:
        json.dump(table, fh, separators=(",", ":"))
    for name, pins in sorted(table["pins"].items()):
        print(name, len(pins), "pins")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
