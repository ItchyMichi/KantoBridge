#!/usr/bin/env python3
"""The warp kinds table of the 3D heights plan (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md D2).

    python warp_kinds_table.py <kinds.json> <designs.json> <out dir>

<kinds.json> is the agents' review of every warp-cell drawing of the three games ({"id", "kind", "facing", "note"} per
design; docs/specs/WARP_MODELS.md), <designs.json> maps each design id to its game and cell key (kb-hub work/warps).
Writes <out dir>/<game>.json: {"game", "source", "kinds": {cell key: kind}} for each game, the key as the fitted-shape
tables name a cell (FittedShapes.key: <atlas>/<t0>-<t1>-<t2>-<t3>). Pixel-free: tile ids and a kind, nothing drawn.

The planner reads it where the game data merges kinds: Gen 1's warp tiles (stairs, ladders and mats alike) and Gen 2's
"stairs" (a ladder and a staircase), and for which way a ladder or a staircase leads (up or down).
"""
import json
import sys
from collections import Counter
from pathlib import Path

KINDS = {"building_door", "cave_mouth", "floor", "hole", "ladder_down", "ladder_up", "mat", "other", "pad", "stairs_down",
         "stairs_up", "wall_exit"}


def main():
    kinds_f, designs_f, out = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    kinds = {k["id"]: k["kind"] for k in json.loads(kinds_f.read_text(encoding="utf-8"))}
    tables = {}
    for d in json.loads(designs_f.read_text(encoding="utf-8")):
        kind = kinds.get(d["id"])
        if kind not in KINDS:
            raise SystemExit("unknown kind %r for %s" % (kind, d["id"]))
        table = tables.setdefault(d["game"], {})
        if table.get(d["key"], kind) != kind:
            raise SystemExit("two kinds for %s %s" % (d["game"], d["key"]))
        table[d["key"]] = kind
    out.mkdir(parents=True, exist_ok=True)
    for game, table in sorted(tables.items()):
        doc = {"game": game, "source": "the agents' review of every warp-cell drawing (docs/specs/WARP_MODELS.md, 2026-10-20)",
               "kinds": dict(sorted(table.items()))}
        (out / ("%s.json" % game)).write_text(json.dumps(doc, indent=1) + "\n", encoding="utf-8")
        print(game, len(table), dict(sorted(Counter(table.values()).items())))


if __name__ == "__main__":
    main()
