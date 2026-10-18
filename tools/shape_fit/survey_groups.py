#!/usr/bin/env python3
"""Fitted shapes survey (docs/specs/FITTED_SHAPES.md section 14): group every map's solid cells into objects from the
survey alone (FitTool survey; pixel-free).

    python survey_groups.py <survey.json> <out groups.json>

A group is one structure region (the 4-connected solid art tiles, Structures.regions) in cells. Its cell box and the keys
in it give its signature; groups with the same signature on any map are one entry, with every placement. Kinds:

    item       a box of at most 2 x 2 cells: a one-cell object, a 2x1 stack (one cell over another: a statue, a plant pot
               on its stand, a sign on a post), a 1x2 pair, a 2x2 object (a big sign, a large statue)
    strip      one or two cells tall and wider, repeating along x with a period of at most 2 cells: a row of items
               (fences, hedges, a row of pots or statues); entered as its repeating unit, an item
    structure  everything larger: buildings (a template, a door folds into it), canopies, fountains, walls, cliffs,
               tree lines; "repeat" when every cell of it repeats along x or y (tree lines, plain walls, cliff bodies)

Per cell of an entry: its key, kind, why the planner made it a unit or not, and the fitted table's class.
"""
from __future__ import annotations

import collections
import json
import sys


def period(grid: list[list[int]]) -> int:
    """The smallest x period (1 or 2) the key grid repeats with, else 0."""
    w = len(grid[0])
    for p in (1, 2):
        if w >= 2 * p and all(row[x] == row[x - p] for row in grid for x in range(p, w)):
            return p
    return 0


def main() -> int:
    survey = json.load(open(sys.argv[1], encoding="utf-8"))
    keys = survey["keys"]
    entries: dict[str, dict] = {}
    for m in survey["maps"]:
        w, h = m["w"], m["h"]
        cells = collections.defaultdict(list)
        for i, r in enumerate(m["region"]):
            if r >= 0:
                cells[r].append(i)
        tmpl_cells = {}
        for t in m["templates"]:
            e = t["entry"]
            per = m["perCell"]
            tw, td = e.get("w", 0), e.get("d", 0)
            for ty in range(td):
                for tx in range(tw):
                    tmpl_cells[((t["y0"] + ty) // per) * w + (t["x0"] + tx) // per] = t["sig"]
        for r, idx in cells.items():
            xs = [i % w for i in idx]
            ys = [i // w for i in idx]
            x0, y0, x1, y1 = min(xs), min(ys), max(xs), max(ys)
            bw, bh = x1 - x0 + 1, y1 - y0 + 1
            inside = set(idx)
            grid = [[m["cell"][y * w + x] if y * w + x in inside else -1 for x in range(x0, x1 + 1)] for y in range(y0, y1 + 1)]
            kind = "item" if bw <= 2 and bh <= 2 else "structure"
            unit_w = bw
            if kind == "structure" and bh <= 2 and -1 not in sum(grid, []):
                p = period(grid)
                if p:
                    kind, unit_w = "strip", p
            if kind == "strip":
                grid = [row[:unit_w] for row in grid]
            sig = "x".join(map(str, (len(grid[0]), len(grid)))) + ":" + ",".join(str(k) for k in sum(grid, []))
            ent = entries.get(sig)
            if ent is None:
                ent = entries[sig] = {"sig": sig, "kind": kind, "w": len(grid[0]), "h": len(grid),
                                      "cells": [[keys[k] if k >= 0 else None for k in row] for row in grid],
                                      "placements": [], "templates": set(), "strip_lengths": []}
                info = []
                for y in range(len(grid)):
                    for x in range(len(grid[0])):
                        i = (y0 + y) * w + x0 + x
                        if grid[y][x] >= 0:
                            info.append({"x": x, "y": y, "kind": m["kind"][i], "why": m["why"][i], "fit": m["fit"][i]})
                ent["info"] = info
                all_idx = [i for i in idx]
                ent["repeat"] = kind == "structure" and all(m["why"][i] in ("not_low", "building") for i in all_idx) and False
            ent["placements"].append([m["map"], x0, y0])
            if kind == "strip":
                ent["strip_lengths"].append(bw)
            for i in idx:
                if i in tmpl_cells:
                    ent["templates"].add(tmpl_cells[i])
    out = []
    for ent in entries.values():
        ent["templates"] = sorted(ent["templates"])
        ent["uses"] = len(ent["placements"])
        out.append(ent)
    out.sort(key=lambda e: ({"item": 0, "strip": 1, "structure": 2}[e["kind"]], -e["uses"]))
    json.dump({"game": survey["game"], "groups": out}, open(sys.argv[2], "w", encoding="utf-8"))
    c = collections.Counter(e["kind"] for e in out)
    shapes = collections.Counter(f"{e['w']}x{e['h']}" for e in out if e["kind"] == "item")
    print(survey["game"], dict(c), "items by size:", dict(shapes), "with a template:", sum(1 for e in out if e["templates"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
