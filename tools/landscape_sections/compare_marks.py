#!/usr/bin/env python3
"""The user's height marks beside the computer's levels (docs/specs/LEVELS_PIPELINE.md, step 3).

    python compare_marks.py <marks.json> <levels.json> [--offset N]

<marks.json>: the height-marking page's marks for a map (kb-hub's data/levels/marks/<MAP>.json): "view" [x0, y0, x1, y1]
(map cells, last excluded), "levels" [[x, y, n], ...] in half cells (a tile row of cliff, 1.5 blocks) over whatever the
user counted from, "slopes" [[x, y, way up], ...]. <levels.json>: OutdoorLevelsDumpTest's dump of the same map: "w",
"kinds" ('#' rock), "levels" (blocks over the map's floor, 3 a cell of cliff), "ramps" [[x, y, way up, rise], ...].

The two count from different grounds, so the marks are moved by the offset that makes the most marked cells agree (or
--offset: the computer's level, in half cells, of the user's 0). Prints both grids over the view in half cells (an arrow
for a slope or a ramp, '#' rock), then the cells that differ. Plain Python.
"""
import json
import sys
from collections import Counter

ARROWS = {"up": "^", "down": "v", "left": "<", "right": ">"}


def half_cells(blocks):
    """A level in blocks as the marks count it: half cells of cliff, 1.5 blocks each."""
    return blocks * 2 // 3


def compare(marks, dump, offset=None):
    """{offset, agree, total, differ: [(x, y, mark, computer, kind)], rows: [(y, marks row, computer row)]}: every marked cell
    that isn't rock and isn't a slope, the marks moved by offset half cells."""
    w, kinds, levels = dump["w"], dump["kinds"], dump["levels"]
    x0, y0, x1, y1 = marks["view"]
    mine = {(x, y): n for x, y, n in marks["levels"]}
    slopes = {(x, y): d for x, y, d in marks["slopes"]}
    ramps = {(r[0], r[1]): r[2] for r in dump["ramps"]}

    def computer(x, y):
        return half_cells(levels[y * w + x])

    compared = [(x, y) for (x, y) in mine if kinds[y * w + x] != "#" and (x, y) not in slopes]
    if offset is None:
        counts = Counter(computer(x, y) - mine[(x, y)] for x, y in compared)
        offset = counts.most_common(1)[0][0] if counts else 0
    differ = [(x, y, mine[(x, y)] + offset, computer(x, y), kinds[y * w + x]) for x, y in sorted(compared, key=lambda c: (c[1], c[0]))
              if mine[(x, y)] + offset != computer(x, y)]
    rows = []
    for y in range(y0, y1):
        a = b = ""
        for x in range(x0, x1):
            k = kinds[y * w + x]
            if (x, y) in slopes:
                a += ARROWS[slopes[(x, y)]]
            elif (x, y) in mine:
                a += _digit(mine[(x, y)] + offset)
            else:
                a += "#" if k == "#" else "."
            b += ARROWS.get(ramps.get((x, y)), "") or ("#" if k == "#" else _digit(computer(x, y)))
        rows.append((y, a, b))
    return {"offset": offset, "agree": len(compared) - len(differ), "total": len(compared), "differ": differ, "rows": rows}


def _digit(n):
    return str(n) if 0 <= n <= 9 else chr(ord("a") + n - 10) if 10 <= n <= 35 else ("-" if n < 0 else "+")


def main(argv):
    marks = json.load(open(argv[1]))
    dump = json.load(open(argv[2]))
    offset = int(argv[argv.index("--offset") + 1]) if "--offset" in argv else None
    r = compare(marks, dump, offset)
    x0, x1 = marks["view"][0], marks["view"][2]
    print(f"{marks.get('map', '?')}: the marks moved by {r['offset']} half cells (the computer's level of the user's 0); "
          f"{r['agree']} of {r['total']} marked cells agree (10 and over as a, b, ...)")
    print("    " + "marks".ljust(x1 - x0 + 3) + "computer")
    for y, a, b in r["rows"]:
        print(f"{y:3d} {a}   {b}")
    for x, y, mine, computer, kind in r["differ"]:
        print(f"  ({x}, {y}) {kind!r}: marks {mine}, computer {computer}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
