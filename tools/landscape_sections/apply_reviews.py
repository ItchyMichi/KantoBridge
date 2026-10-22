#!/usr/bin/env python3
"""The levels reviews applied (docs/specs/LEVELS_PIPELINE.md, steps 2 and 4): every review's pins and marked slopes into the
landscape models' table, and for the games without a stair table (Crystal, Blue) the drawings the reviews mark as stairs.

    python apply_reviews.py <reviews dir> <dumps dir> <table in> <table out> [--stairs <out.json>] [--skip MAP,...] [--replace]

<reviews dir>/<MAP>.json: a review ({"unit": "blocks", "floor", "pins", "slopes", "notes"}); <dumps dir>: the levels dump the
reviews read (its <MAP>_keys.json: each Gen 1/2 cell's drawing key). The table out: the table in with "pins" (pins_table.py)
and "slopes" for every map whose review marks some (a map's empty list would switch an Emerald map to its marked mode,
so maps with none are left out). With --stairs: per drawing marked as stairs on Crystal's and Blue's maps, its game, its
way up, how many of its cells on the reviewed maps were marked and how many weren't; "stairs" lists those marked nine
times in ten or more and at least twice, the same way up: the candidates for floor_kinds/<game>.json. --replace (a second
look's reviews, whose lists are complete): each reviewed map keeps only its review's pins and slopes, none if it has none.
Plain Python.
"""
import json
import os
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pins_table import read_pins  # noqa: E402
from slopes_table import read_slopes  # noqa: E402

GAMES = {"G2_": "crystal", "EM_": "emerald"}


def game_of(mid):
    return next((g for p, g in GAMES.items() if mid.startswith(p)), "blue")


def apply(reviews_dir, table, skip=(), replace=False):
    """table with every review's pins and (where it marks some) slopes; {map: (pins, slopes)} applied. replace: a reviewed
    map's pins and slopes are its review's alone (the table's earlier ones go)."""
    table.setdefault("pins", {})
    table.setdefault("slopes", {})
    done = {}
    for name in sorted(os.listdir(reviews_dir)):
        if not name.endswith(".json") or name[:-5] in skip:
            continue
        mid = name[:-5]
        path = os.path.join(reviews_dir, name)
        try:
            pins = read_pins(path)
            slopes = read_slopes(path)
        except (ValueError, KeyError, json.JSONDecodeError) as e:
            # a review that doesn't read is left out, said
            print("skipped", mid, e)
            continue
        if replace:
            table["pins"].pop(mid, None)
            table["slopes"].pop(mid, None)
        if pins:
            table["pins"][mid] = pins
        if slopes:
            table["slopes"][mid] = slopes
        done[mid] = (len(pins), len(slopes))
    return table, done


def stairs(reviews_dir, dumps_dir, skip=()):
    """Per (game, drawing) marked as stairs: {way: marks}, cells on the reviewed walkable ground, marked; and the candidates."""
    marked = defaultdict(Counter)
    seen = Counter()
    for name in sorted(os.listdir(reviews_dir)):
        mid = name[:-5]
        game = game_of(mid)
        keys_path = os.path.join(dumps_dir, mid + "_keys.json")
        if not name.endswith(".json") or mid in skip or game == "emerald" or not os.path.exists(keys_path):
            continue
        keys = json.load(open(keys_path))["keys"]
        dump = json.load(open(os.path.join(dumps_dir, mid + ".json")))
        w, kinds = dump["w"], dump["kinds"]
        for i, k in enumerate(keys):
            if kinds[i] in ".L":
                seen[(game, k)] += 1
        try:
            marks = read_slopes(os.path.join(reviews_dir, name))
        except (ValueError, KeyError, json.JSONDecodeError):
            continue
        for x, y, way in marks:
            if 0 <= x < w and 0 <= y < dump["h"]:
                marked[(game, keys[y * w + x])][way] += 1
    rows = []
    for (game, key), ways in marked.items():
        total = sum(ways.values())
        way, n = ways.most_common(1)[0]
        cells = max(seen[(game, key)], total)
        rows.append({"game": game, "key": key, "way": way, "marked": total, "same_way": n, "cells": cells,
                     "candidate": n >= 2 and n == total and n >= 0.9 * cells})
    rows.sort(key=lambda r: (r["game"], -r["marked"], r["key"]))
    return rows


def main(argv):
    reviews_dir, dumps_dir, table_in, table_out = argv[1:5]
    skip = set(argv[argv.index("--skip") + 1].split(",")) if "--skip" in argv else set()
    table, done = apply(reviews_dir, json.load(open(table_in)), skip, "--replace" in argv)
    with open(table_out, "w") as fh:
        json.dump(table, fh, separators=(",", ":"))
    print(len(done), "reviews:", sum(1 for p, s in done.values() if p), "with pins,", sum(1 for p, s in done.values() if s), "with slopes;",
          sum(p for p, s in done.values()), "pins,", sum(s for p, s in done.values()), "slope cells")
    if "--stairs" in argv:
        rows = stairs(reviews_dir, dumps_dir, skip)
        out = {"drawings": rows, "stairs": [r for r in rows if r["candidate"]]}
        with open(argv[argv.index("--stairs") + 1], "w") as fh:
            json.dump(out, fh, indent=1)
        print(len(rows), "drawings marked as stairs,", len(out["stairs"]), "candidates")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
