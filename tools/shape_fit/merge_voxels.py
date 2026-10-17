#!/usr/bin/env python3
"""Fitted shapes: put voxel shapes straight into the tables (docs/specs/FITTED_SHAPES.md section 10).

    python merge_voxels.py <game> <shapes.json> [--source depth]

<shapes.json> maps cell keys to voxel bits (base64, index (y * 16 + z) * 16 + x, least significant bit first): the depth
run's models (depth_all.py's targets_depth.json) or Blockbench edits (bbmodel.py import). Each becomes the cell's table
entry {"class": "voxels", "v": bits, "from": <source>}, replacing a builder fit the depth run had added for that cell
("from": "depth"), and an earlier voxel entry; entries from the first run (TRELLIS, no "from") are kept unless the cell
is in <shapes.json> and --replace-first is given.
"""
from __future__ import annotations

import argparse
import base64
import json
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
VOXEL_BYTES = 16 * 16 * 16 // 8


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("game")
    ap.add_argument("shapes")
    ap.add_argument("--source", default="depth")
    ap.add_argument("--replace-first", action="store_true")
    args = ap.parse_args()
    table_file = REPO / "fabric-bridge/src/main/resources/kantobridge/fitted_shapes" / f"{args.game}.json"
    table = json.loads(table_file.read_text(encoding="utf-8"))
    shapes = json.loads(Path(args.shapes).read_text(encoding="utf-8"))
    put = kept_first = 0
    for key, bits in sorted(shapes.items()):
        if len(base64.b64decode(bits)) != VOXEL_BYTES:
            continue
        old = table["cells"].get(key)
        if old is not None and "from" not in old and not args.replace_first:
            kept_first += 1
            continue
        table["cells"][key] = {"class": "voxels", "v": bits, "from": args.source}
        put += 1
    table["cells"] = dict(sorted(table["cells"].items()))
    summary = table.setdefault("summary", {})
    summary["voxels"] = sum(1 for e in table["cells"].values() if e.get("class") == "voxels")
    table_file.write_text(json.dumps(table, indent=2) + "\n", encoding="utf-8")
    print(f"{args.game}: {put} voxel shapes put ({args.source}), {kept_first} first-run entries kept; "
          f"{summary['voxels']} voxel entries in the table")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
