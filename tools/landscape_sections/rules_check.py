#!/usr/bin/env python3
"""After a change to the solver's rules (docs/specs/LEVELS_PIPELINE.md, step 4): which maps the rules moved, and whether each
map's review still holds, so only those it doesn't go back to a review agent (the second look).

    python rules_check.py translate <old dump> <table> <new dump, no pins> <table out>
    python rules_check.py <old dump> <table> <new dump, no pins> <new dump, pins> [--json <out.json>] [--final <table out>]

<old dump>: the dump the reviews read (OutdoorLevelsDumpTest, every map); <table>: the models table with the reviews' pins
(apply_reviews.py); the new dumps: the new rules with that table less its pins, and with the translated table. A pin is a
level in its map's frame (blocks over its floor): where the new rules move a map's floor, "translate" moves each pin of
it by the shift most of its cells moved by (the new levels less the old), so it still means what its review meant. Per
map, over its ground and water cells, each pair of grids lined up at the shift most cells agree on:
  old      the old levels with the pins applied as OutdoorLevels.pinned does (the review's intent)
  raw      the new rules alone      pinned   the new rules and the pins
and a verdict: "same" (the rules didn't move it: its pins stand), "fixed" (the rules alone give what the review wanted),
"pins" (with its pins it is what the review wanted), "look" (neither: a second look). --final writes the translated table
less the pins of the maps the rules fixed. Plain Python.
"""
import json
import os
import sys
from collections import Counter

STEPS = ((1, 0), (-1, 0), (0, 1), (0, -1))
MAX_LEVEL = 64  # MapLayout.MAX_LEVEL: a pin over it stops the planner


def load(dump_dir, mid):
    path = os.path.join(dump_dir, mid + ".json")
    return json.load(open(path)) if os.path.exists(path) else None


def pinned(dump, pins):
    """The dump's levels with the pins applied in order: each floods the ground and water level with its cell (no ramp)."""
    w, h, kinds = dump["w"], dump["h"], dump["kinds"]
    levels = list(dump["levels"])
    ramps = {(r[0], r[1]) for r in dump.get("ramps", [])}
    for x, y, n in pins:
        if not (0 <= x < w and 0 <= y < h) or kinds[y * w + x] == "#":
            continue
        start = y * w + x
        was = levels[start]
        seen, todo = {start}, [start]
        while todo:
            c = todo.pop()
            levels[c] = n
            for dx, dy in STEPS:
                nx, ny = c % w + dx, c // w + dy
                j = ny * w + nx
                if 0 <= nx < w and 0 <= ny < h and j not in seen and kinds[j] != "#" and levels[j] == was and (nx, ny) not in ramps:
                    seen.add(j)
                    todo.append(j)
    return levels


def shift(a, b, kinds):
    """The difference b - a most of the ground and water cells have (0 for none)."""
    cells = [i for i, k in enumerate(kinds) if k != "#"]
    return Counter(b[i] - a[i] for i in cells).most_common(1)[0][0] if cells else 0


def translate(old_dir, table, raw_dir):
    """The table with each map's pins moved by the shift of its floor between the old dump and the new."""
    out = dict(table)
    out["pins"] = {}
    for name, pins in table.get("pins", {}).items():
        old, raw = load(old_dir, name), load(raw_dir, name)
        d = shift(old["levels"], raw["levels"], raw["kinds"]) if old and raw and "kinds" in raw else 0
        out["pins"][name] = [[x, y, min(MAX_LEVEL, max(0, n + d))] for x, y, n in pins]
    return out


def apart(a, b, kinds):
    """The cells (ground, ledge, water) where grids a and b differ once lined up at the shift most cells agree on."""
    cells = [i for i, k in enumerate(kinds) if k != "#"]
    if not cells:
        return 0
    shift = Counter(b[i] - a[i] for i in cells).most_common(1)[0][0]
    return sum(1 for i in cells if b[i] - a[i] != shift)


def check(old_dir, table, raw_dir, pin_dir):
    rows = []
    pins = table.get("pins", {})
    for name in sorted(f[:-5] for f in os.listdir(raw_dir) if f.endswith(".json") and not f.startswith(("placements_", "notes_"))
                       and not f.endswith(("_keys.json", "_mids.json"))):
        old, raw, pin = load(old_dir, name), load(raw_dir, name), load(pin_dir, name)
        if old is None or pin is None or "kinds" not in raw:
            continue
        kinds = raw["kinds"]
        want = pinned(old, pins.get(name, []))
        moved = apart(old["levels"], raw["levels"], kinds)
        to_raw = apart(want, raw["levels"], kinds)
        to_pin = apart(want, pin["levels"], kinds)
        verdict = "same" if moved == 0 and to_pin == 0 else "fixed" if to_raw == 0 else "pins" if to_pin == 0 else "look"
        rows.append({"map": name, "cells": sum(1 for k in kinds if k != "#"), "pins": len(pins.get(name, [])), "moved": moved,
                     "raw_off": to_raw, "pinned_off": to_pin, "verdict": verdict,
                     "outcome": [old.get("outcome"), raw.get("outcome"), pin.get("outcome")],
                     "breaks": [len(old.get("breaks", [])), len(raw.get("breaks", [])), len(pin.get("breaks", []))]})
    return rows


def main(argv):
    if argv[1] == "translate":
        old_dir, table_path, raw_dir, out_path = argv[2:6]
        with open(out_path, "w") as fh:
            json.dump(translate(old_dir, json.load(open(table_path)), raw_dir), fh, separators=(",", ":"))
        return 0
    old_dir, table_path, raw_dir, pin_dir = argv[1:5]
    table = json.load(open(table_path))
    rows = check(old_dir, table, raw_dir, pin_dir)
    print(Counter(r["verdict"] for r in rows))
    for r in sorted(rows, key=lambda r: (r["verdict"] != "look", -r["pinned_off"])):
        if r["verdict"] in ("look", "pins", "fixed"):
            print(f"{r['verdict']:5s} {r['map']:34s} moved {r['moved']:4d} raw_off {r['raw_off']:4d} pinned_off {r['pinned_off']:4d}"
                  f" of {r['cells']:5d}  pins {r['pins']:3d}  {r['outcome']}  breaks {r['breaks']}")
    if "--json" in argv:
        with open(argv[argv.index("--json") + 1], "w") as fh:
            json.dump(rows, fh, indent=1)
    if "--final" in argv:
        final = translate(old_dir, table, raw_dir)
        for r in rows:
            if r["verdict"] == "fixed":
                final["pins"].pop(r["map"], None)
        with open(argv[argv.index("--final") + 1], "w") as fh:
            json.dump(final, fh, separators=(",", ":"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
