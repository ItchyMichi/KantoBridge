#!/usr/bin/env python3
"""The marked slopes in the landscape models' table (docs/specs/LANDSCAPE_MODELS.md; the levels pilot): the planner reads
them as the map's drawing checked for slopes and stairs (LandscapeModels.slopes, OutdoorLevels).

    python slopes_table.py <table.json> <slopes dir> [MAP ...]

<slopes dir>/<MAP>.json: {"slopes": [[x, y, "up"|"down"|"left"|"right"], ...], ...} in map cells, the way up (the
height-marking page's marks have this shape). The table's "slopes" gets each map's list (every map with a file, or the
maps named); a map listed with no slopes had its drawing checked and has none. Plain Python.
"""
import json
import os
import sys

DIRS = ("up", "down", "left", "right")


def read_slopes(path):
    """A marks file's slopes, checked: [[x, y, dir], ...] sorted."""
    marks = json.load(open(path))
    out = []
    for s in marks.get("slopes", []):
        if not (isinstance(s, list) and len(s) == 3 and all(isinstance(v, int) and not isinstance(v, bool) for v in s[:2])
                and s[2] in DIRS):
            raise ValueError(f"{path}: bad slope {s!r}")
        out.append([s[0], s[1], s[2]])
    return sorted(out)


def add_slopes(table, slopes_dir, maps=None):
    """{map: [[x, y, dir], ...]} for every <MAP>.json in slopes_dir (or the maps named), put in table["slopes"]."""
    names = maps or sorted(f[:-5] for f in os.listdir(slopes_dir) if f.endswith(".json"))
    table.setdefault("slopes", {})
    for name in names:
        table["slopes"][name] = read_slopes(os.path.join(slopes_dir, name + ".json"))
    return table


def main(argv):
    path, slopes_dir = argv[1], argv[2]
    table = json.load(open(path))
    add_slopes(table, slopes_dir, argv[3:])
    with open(path, "w") as fh:
        json.dump(table, fh, separators=(",", ":"))
    for name, cells in sorted(table["slopes"].items()):
        print(name, len(cells), "slope cells")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
