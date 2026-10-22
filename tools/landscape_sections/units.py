#!/usr/bin/env python3
"""The landscape models rollout's units (docs/specs/LANDSCAPE_ROLLOUT_PLAN.md): per map, the rock sections to model, grouped
into units (sections whose cells touch: a mountain and the mouth in its front, a cliff and the bands beside it), each with
a window round it, written as package.py's spots.

    python units.py <regions dir> <marks dir> <models dir> <spots.json> MAP [MAP ...]

Rock sections: masses, mouths and faces of 3 cells or more (the pilot's rule, without trees: the user's "rock only",
2026-10-22). A section a pilot spot of the same map already models (<models dir>/<spot>/specs/*.json "sections") is left
out. A unit's window is its cells' bounding box and 2 cells round it, inside the map; a unit of several sections wider or
taller than 48 cells is split into one window a section. Writes (adds to) <spots.json>: {"<MAP>__u<k>": {"map", "x",
"y" (the window's centre), "window": [x0, y0, w, h], "model": [section ids]}}. Prints per map its units. Plain Python.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from table import _cells  # noqa: E402

MARGIN = 2
MAX_SIDE = 48


def section_cells(regions, marks):
    """Per marked section, its cells: its region's cells in its boxes, a cell in two sections' boxes the first's."""
    w, h, grid = regions["w"], regions["h"], regions["grid"]
    owner = [-1] * (w * h)
    out = []
    for s, sec in enumerate(marks["sections"]):
        cells = []
        for box in sec.get("boxes", []):
            for x, y in _cells(box, w, h, s):
                if grid[y * w + x] == sec["region"] and owner[y * w + x] < 0:
                    owner[y * w + x] = s
                    cells.append((x, y))
        out.append(cells)
    return out


def rock(sec, cells):
    return sec["kind"] in ("mass", "mouth") or (sec["kind"] == "face" and len(cells) >= 3)


def piloted(models_dir, mid):
    """The section ids the pilot's spots of map mid already model."""
    done = set()
    if not os.path.isdir(models_dir):
        return done
    for spot in os.listdir(models_dir):
        pkg = os.path.join(models_dir, spot, "package.json")
        specs = os.path.join(models_dir, spot, "specs")
        if "__u" in spot or not os.path.exists(pkg) or not os.path.isdir(specs):
            continue
        if json.load(open(pkg)).get("map") != mid:
            continue
        for f in os.listdir(specs):
            if f.endswith(".json"):
                done.update(json.load(open(os.path.join(specs, f))).get("sections", []))
    return done


def window(cells, w, h):
    xs = [x for x, _ in cells]
    ys = [y for _, y in cells]
    x0, y0 = max(0, min(xs) - MARGIN), max(0, min(ys) - MARGIN)
    x1, y1 = min(w, max(xs) + MARGIN + 1), min(h, max(ys) + MARGIN + 1)
    return [x0, y0, x1 - x0, y1 - y0]


def units(regions, marks, skip=()):
    """[(window, [section ids])]: the map's rock sections (less skip) grouped where their cells touch."""
    w, h = regions["w"], regions["h"]
    cells = section_cells(regions, marks)
    ids = [s for s, sec in enumerate(marks["sections"]) if s not in skip and cells[s] and rock(sec, cells[s])]
    parent = {s: s for s in ids}

    def root(s):
        while parent[s] != s:
            parent[s] = parent[parent[s]]
            s = parent[s]
        return s

    owner = {}
    for s in ids:
        for c in cells[s]:
            owner[c] = s
    for s in ids:
        for x, y in cells[s]:
            for nx, ny in ((x + 1, y), (x, y + 1)):
                t = owner.get((nx, ny))
                if t is not None and root(t) != root(s):
                    parent[root(t)] = root(s)
    groups = {}
    for s in ids:
        groups.setdefault(root(s), []).append(s)
    out = []
    for members in sorted(groups.values(), key=min):
        win = window([c for s in members for c in cells[s]], w, h)
        if len(members) > 1 and (win[2] > MAX_SIDE or win[3] > MAX_SIDE):
            out.extend((window(cells[s], w, h), [s]) for s in members)
        else:
            out.append((win, sorted(members)))
    return out


def main(argv):
    regions_dir, marks_dir, models_dir, spots_path = argv[1:5]
    spots = json.load(open(spots_path)) if os.path.exists(spots_path) else {}
    for mid in argv[5:]:
        regions = json.load(open(os.path.join(regions_dir, mid + ".json")))
        marks = json.load(open(os.path.join(marks_dir, mid + "_sections.json")))
        for name in [n for n in spots if spots[n]["map"] == mid]:
            del spots[name]
        found = units(regions, marks, piloted(models_dir, mid))
        for k, (win, members) in enumerate(found):
            spots[f"{mid}__u{k}"] = {"map": mid, "x": win[0] + win[2] // 2, "y": win[1] + win[3] - 1, "window": win, "model": members}
        print(mid, len(found), "units", [f"{w[2]}x{w[3]}:{m}" for w, m in found])
    with open(spots_path, "w") as fh:
        json.dump(spots, fh, indent=1)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
