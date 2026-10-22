#!/usr/bin/env python3
"""The second look (docs/specs/LEVELS_PIPELINE.md, step 4: after a change to the rules, the maps rules_check.py calls "look"):
what a review agent needs beside the new pictures, and a way to try its pins before writing them.

    python second_look.py inputs <old dump> <old table> <raw dump> <pinned dump> <new table> <maps.json> <out dir> <reviews>
    python second_look.py try <MAP>_raw.json <review.json> [X0,Y0,X1,Y1]

inputs: per map of <maps.json> (["MAP", ...]) into <out dir>/<MAP>/ (or <out dir> with "{map}" in it, e.g.
review/{map}/r2: the round's pictures' folder): <MAP>_raw.json (the new rules' levels with no pins: the
pins apply to these) and <MAP>_second.json: "floor" (the pinned dump's lowest level, the pictures' 0), "pins_now" (the
new table's pins for the map, in blocks over that floor, as the pipeline applies them: sorted), "intent" (the levels the
first review meant: the old dump with its pins, lined up with the pinned dump at the shift most cells agree on, in
blocks over the floor, null for rock; none for a map with no first review in <reviews>) and "differ" ([x, y, now, meant] for every
ground or water cell where the pinned dump and that intent differ).

try: the levels a review's pins give (its "pins" in blocks over its "floor", applied in the pipeline's order, sorted, to
the raw levels: each floods the ground and water level with its cell, no ramp, then each stair goes with the ground at its
ends, as OutdoorLevels.pinned does), printed
as a grid in blocks over the floor: a number for ground, "~" after it for water, "L" for a ledge, "^ v < >" before it
for a ramp (its way up, at its foot's level), "#" for rock, and "*" after a cell where it differs from what the first
review meant (the <MAP>_second.json beside <MAP>_raw.json, if it has an intent); then the cells the pins changed. A pin
stops at the game's walls where the dump has them ("walls": Crystal's one-way walls, a door cut into a face). Slopes are
not simulated (the solver takes them on the next dump). Plain Python.
"""
import json
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rules_check import load, pinned, pinned_ramps  # noqa: E402

ARROWS = {"up": "^", "down": "v", "left": "<", "right": ">"}


def lowest(dump):
    return min((v for v, k in zip(dump["levels"], dump["kinds"]) if k != "#"), default=0)


def second(old, old_pins, raw, pin, new_pins):
    """A map's second-look facts (see the module's doc)."""
    kinds, now = pin["kinds"], pin["levels"]
    floor = lowest(pin)
    out = {"map": pin.get("map"), "floor": floor, "pins_now": [[x, y, n - floor] for x, y, n in sorted(new_pins)]}
    if old is None or old_pins is None:
        return out
    want = pinned(old, old_pins)
    cells = [i for i, k in enumerate(kinds) if k != "#"]
    d = Counter(now[i] - want[i] for i in cells).most_common(1)[0][0] if cells else 0
    w = pin["w"]
    out["intent"] = [None if kinds[i] == "#" else want[i] + d - floor for i in range(len(kinds))]
    out["differ"] = [[i % w, i // w, now[i] - floor, want[i] + d - floor] for i in cells if now[i] != want[i] + d]
    return out


def inputs(argv):
    old_dir, old_table, raw_dir, pin_dir, new_table, maps_path, out_dir, reviews = argv
    old_pins = json.load(open(old_table)).get("pins", {})
    new_pins = json.load(open(new_table)).get("pins", {})
    for mid in json.load(open(maps_path)):
        old, raw, pin = load(old_dir, mid), load(raw_dir, mid), load(pin_dir, mid)
        if raw is None or pin is None:
            print("no dump", mid)
            continue
        out = out_dir.format(map=mid) if "{map}" in out_dir else os.path.join(out_dir, mid)
        os.makedirs(out, exist_ok=True)
        with open(os.path.join(out, mid + "_raw.json"), "w") as fh:
            json.dump(raw, fh)
        # a map the first round never reviewed has no intent
        reviewed = os.path.exists(os.path.join(reviews, mid + ".json"))
        facts = second(old, old_pins.get(mid, []) if reviewed else None, raw, pin, new_pins.get(mid, []))
        with open(os.path.join(out, mid + "_second.json"), "w") as fh:
            json.dump(facts, fh)
        print(mid, "floor", facts["floor"], "pins", len(facts["pins_now"]), "differ", len(facts.get("differ", [])))
    return 0


def grid(dump, levels, floor, window, meant=None, ramps=None):
    w, kinds = dump["w"], dump["kinds"]
    ramps = {(r[0], r[1]): r[2] for r in (dump.get("ramps", []) if ramps is None else ramps)}
    x0, y0, x1, y1 = window
    cw = 5 if meant else 4
    lines = ["     " + "".join(f"{x:>{cw}}" if x % 4 == 0 else " " * cw for x in range(x0, x1))]
    for y in range(y0, y1):
        row = []
        for x in range(x0, x1):
            i = y * w + x
            k = kinds[i]
            n = levels[i] - floor
            text = "#" if k == "#" else ARROWS.get(ramps.get((x, y)), "") + str(n) + ("~" if k == "~" else "L" if k == "L" else "")
            if meant and k != "#" and meant[i] is not None and meant[i] != n:
                text += "*"
            row.append(f"{text:>{cw}}")
        lines.append(f"{y:>4} " + "".join(row))
    return "\n".join(lines)


def try_pins(argv):
    raw = json.load(open(argv[0]))
    review = json.load(open(argv[1]))
    floor = review.get("floor", 0)
    pins = sorted([x, y, floor + n] for x, y, n in review.get("pins", []))
    levels, ramps = pinned_ramps(raw, pins)
    w, h = raw["w"], raw["h"]
    window = tuple(int(v) for v in argv[2].split(",")) if len(argv) > 2 else (0, 0, w, h)
    beside = argv[0][:-len("_raw.json")] + "_second.json" if argv[0].endswith("_raw.json") else None
    meant = json.load(open(beside)).get("intent") if beside and os.path.exists(beside) else None
    # the intent counts from the pictures' floor: line it up with the review's (the same unless the review changed it)
    if meant:
        base = json.load(open(beside))["floor"]
        meant = [None if v is None else v + base - floor for v in meant]
    print(grid(raw, levels, floor, window, meant, ramps))
    if meant:
        off = sum(1 for i in range(w * h) if raw["kinds"][i] != "#" and meant[i] is not None and meant[i] != levels[i] - floor)
        print(f"{off} cells (*) differ from what the first review meant")
    changed = [(i % w, i // w, raw["levels"][i] - floor, levels[i] - floor) for i in range(w * h) if levels[i] != raw["levels"][i]]
    print(f"{len(pins)} pins changed {len(changed)} cells (x, y, before, after):", changed[:60], "..." if len(changed) > 60 else "")
    return 0


def main(argv):
    if len(argv) > 1 and argv[1] == "inputs" and len(argv) == 10:
        return inputs(argv[2:])
    if len(argv) > 1 and argv[1] == "try" and len(argv) in (4, 5):
        return try_pins(argv[2:])
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
