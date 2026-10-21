"""The landscape sections pilot's table (docs/specs/LANDSCAPE_SECTIONS_PLAN.md section 2.3) from the agents' marks.

    python tools/landscape_sections/table.py <regions dir> <marks dir> <table.json> MAP [MAP ...]

<regions dir>/<MAP>.json is the first pass (LandscapeRegionsTest: w, h, grid); <marks dir>/<MAP>_sections.json a
marking agent's sections (docs/specs/LANDSCAPE_MARKING_RULES.md). A section's cells are the cells of its region in its
boxes, a cell in two sections' boxes the first's; its top cells those of its `top` boxes. Each box of objects is a
section of its own (the agents box each boulder, each sign: the planner stands one at most two cells deep as one thing). The table holds, per map, w, h,
each section's kind (and rows, landmark), each cell's section (-1 none) and top flag: what the section models are made
over (LANDSCAPE_MODELS.md). Prints the counts the plan's check asks for: per map and kind the cells marked, and the solid
cells left to the default. Cells, kinds and counts only.
"""
import json
import os
import sys

KINDS = ("face", "mass", "objects", "trees", "mouth", "building")


class MarkError(ValueError):
    pass


def table_of(regions, marks):
    """One map's table entry from its first pass and its marks; raises MarkError on a malformed mark."""
    w, h, grid = regions["w"], regions["h"], regions["grid"]
    ids = {r["id"] for r in regions["regions"]}
    cells = [-1] * (w * h)
    top = [0] * (w * h)
    sections = []
    for s, sec in enumerate(marks["sections"]):
        kind = sec.get("kind")
        if kind not in KINDS:
            raise MarkError(f"section {s}: kind {kind!r}")
        region = sec.get("region")
        if region not in ids:
            raise MarkError(f"section {s}: region {region!r}")
        entry = {"kind": kind}
        if sec.get("rows"):
            entry["rows"] = int(sec["rows"])
        if sec.get("landmark"):
            entry["landmark"] = True
        groups = [[box] for box in sec.get("boxes", [])] if kind == "objects" else [sec.get("boxes", [])]
        for boxes in groups:
            t = len(sections)
            sections.append(dict(entry))
            for box in boxes:
                for x, y in _cells(box, w, h, s):
                    if grid[y * w + x] == region and cells[y * w + x] < 0:
                        cells[y * w + x] = t
            for box in sec.get("top", []) or []:
                for x, y in _cells(box, w, h, s):
                    if cells[y * w + x] == t:
                        top[y * w + x] = 1
    return {"w": w, "h": h, "sections": sections, "cells": cells, "top": top}


def _cells(box, w, h, s):
    if not isinstance(box, list) or len(box) != 4:
        raise MarkError(f"section {s}: box {box!r}")
    x0, y0, x1, y1 = (int(v) for v in box)
    if not (0 <= x0 <= x1 < w and 0 <= y0 <= y1 < h):
        raise MarkError(f"section {s}: box {box!r} outside {w} x {h}")
    return [(x, y) for y in range(y0, y1 + 1) for x in range(x0, x1 + 1)]


def counts(regions, entry):
    """Per kind the cells marked, plus 'default': the solid cells left out, and 'top': the top cells."""
    out = {k: 0 for k in KINDS}
    out["default"] = 0
    out["top"] = sum(entry["top"])
    for i, r in enumerate(regions["grid"]):
        if r < 0:
            continue
        s = entry["cells"][i]
        if s < 0:
            out["default"] += 1
        else:
            out[entry["sections"][s]["kind"]] += 1
    return out


def main(argv):
    regions_dir, marks_dir, out = argv[1:4]
    maps = {}
    for mid in argv[4:]:
        regions = json.load(open(os.path.join(regions_dir, mid + ".json")))
        marks = json.load(open(os.path.join(marks_dir, mid + "_sections.json")))
        entry = table_of(regions, marks)
        maps[mid] = entry
        c = counts(regions, entry)
        print(mid, " ".join(f"{k}={v}" for k, v in c.items() if v), f"sections={len(entry['sections'])}")
    with open(out, "w") as fh:
        json.dump({"maps": maps}, fh, separators=(",", ":"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
