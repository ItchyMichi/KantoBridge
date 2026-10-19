#!/usr/bin/env python3
"""Fitted shapes: put voxel shapes straight into the tables (docs/specs/FITTED_SHAPES.md section 10).

    python merge_voxels.py <game> <shapes.json> [--source depth]

<shapes.json> maps cell keys to voxel bits (or, from hand_import.py, {"v": bits, "t": paint}) (base64, index (y * 16 + z) * 16 + x, least significant bit first): the depth
run's models (depth_all.py's targets_depth.json) or Blockbench edits (bbmodel.py import). Each becomes the cell's table
entry {"class": "voxels", "v": bits, "from": <source>}, replacing a builder fit the depth run had added for that cell
("from": "depth"), and an earlier voxel entry; entries from the first run (TRELLIS, no "from") are kept unless the cell
is in <shapes.json> and --replace-first is given. --stacks marks the user's tall objects' top cells to stand on the cell
below (the planner moves them there); --seats makes stools on walkable cells seats (HAND_MODELS.md section 6).
"""
from __future__ import annotations

import argparse
import base64
import json
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
VOXEL_BYTES = 16 * 16 * 16 // 8


SEAT_H = 4  # a seat's height in voxels: half a block, the seat collider's top
SEAT_WORDS = re.compile(r"\b(stool|chair|seat|bench)", re.I)  # a walkable entity is a seat when its note says so
NOT_SEAT = re.compile(r"knocked|overturned|tilted|sign", re.I)


def squash(bits: str) -> tuple[str, str]:
    """A seat's voxels (HAND_MODELS.md section 6): the cell's 16 rows squashed into its lowest SEAT_H (a voxel solid
    where any of its rows is), painted with the drawing's row the middle of its rows shows."""
    W = 16
    raw = base64.b64decode(bits)
    solid = lambda x, y, z: raw[((y * W + z) * W + x) >> 3] >> (((y * W + z) * W + x) & 7) & 1  # noqa: E731
    out = bytearray(len(raw))
    paint = bytearray(W * W * W)
    k = W // SEAT_H
    for y in range(SEAT_H):
        row = W - 1 - (y * k + k // 2)  # front projection's row for the middle of the squashed rows
        for z in range(W):
            for x in range(W):
                if any(solid(x, y * k + j, z) for j in range(k)):
                    i = (y * W + z) * W + x
                    out[i >> 3] |= 1 << (i & 7)
                    paint[i] = row * W + x
    return base64.b64encode(bytes(out)).decode(), base64.b64encode(bytes(paint)).decode()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("game")
    ap.add_argument("shapes")
    ap.add_argument("--source", default="depth")
    ap.add_argument("--replace-first", action="store_true")
    ap.add_argument("--stacks", default="", help="kb-hub's flags_<game>.json: each upright 1x2 \"stack (tall object)\" entity's top "
                    "cell entry gets \"on\": the bottom cell's key (section 15), when both are voxel entries")
    ap.add_argument("--seats", default="", help="kb-hub's images/maps/<game> dir: with --stacks' flags, each modelled entity whose "
                    "cells are all walkable (a stool) becomes a seat: its cells' voxels squashed to SEAT_H, painted, \"seat\": true")
    ap.add_argument("--edits", default="", help="voxedit's edits: cells whose ops end with drop lose their voxel entry")
    args = ap.parse_args()
    table_file = REPO / "fabric-bridge/src/main/resources/kantobridge/fitted_shapes" / f"{args.game}.json"
    table = json.loads(table_file.read_text(encoding="utf-8"))
    shapes = json.loads(Path(args.shapes).read_text(encoding="utf-8"))
    put = kept_first = 0
    for key, bits in sorted(shapes.items()):
        paint = None
        if isinstance(bits, dict):  # a hand model's cell (hand_import.py): its voxels and its paint
            bits, paint = bits["v"], bits.get("t")
        if len(base64.b64decode(bits)) != VOXEL_BYTES:
            continue
        old = table["cells"].get(key)
        if old is not None and "from" not in old and not args.replace_first:
            kept_first += 1
            continue
        table["cells"][key] = {"class": "voxels", "v": bits, "from": args.source}
        if paint and len(base64.b64decode(paint)) == VOXEL_BYTES * 8:
            table["cells"][key]["t"] = paint
        put += 1
    removed = 0
    if args.edits:
        for key, e in json.loads(Path(args.edits).read_text(encoding="utf-8")).items():
            ops = e.get("ops", [])
            old = table["cells"].get(key)
            if ops and ops[-1] == ["drop"] and old is not None and old.get("class") == "voxels":
                del table["cells"][key]
                removed += 1
    stacked = 0
    if args.stacks:
        for ents in json.loads(Path(args.stacks).read_text(encoding="utf-8")).get("_entities", {}).values():
            for e in ents:
                if e.get("kind") != "stack (tall object)" or e.get("box", [0, 0, 0, 0])[2:] != [1, 2] or len(e["keys"]) != 2:
                    continue
                top, bottom = e["keys"]  # cells are sorted by row: the top first
                t, b = table["cells"].get(top), table["cells"].get(bottom)
                if top != bottom and t and b and t.get("class") == "voxels" and b.get("class") == "voxels":
                    t["on"] = bottom
                    stacked += 1
    seats = 0
    if args.seats and args.stacks:
        for map_id, ents in json.loads(Path(args.stacks).read_text(encoding="utf-8")).get("_entities", {}).items():
            info_f = Path(args.seats) / f"{map_id}.json"
            if not info_f.is_file():
                continue
            info = json.loads(info_f.read_text(encoding="utf-8"))
            for e in ents:
                if e.get("kind") in ("keep as blocks", "make by hand", "floor decoration"):
                    continue
                if not all(info["kind"][y * info["w"] + x] == "W" for x, y in e["cells"]):
                    continue
                if not SEAT_WORDS.search(e.get("note", "")) or NOT_SEAT.search(e.get("note", "")):
                    continue  # a walkable door, ladder, stairs, grass or clutter is not a seat
                for key in e["keys"]:
                    entry = table["cells"].get(key)
                    if entry and entry.get("class") == "voxels" and not entry.get("seat"):
                        entry["v"], entry["t"] = squash(entry["v"])
                        entry["seat"] = True
                        seats += 1
    table["cells"] = dict(sorted(table["cells"].items()))
    summary = table.setdefault("summary", {})
    summary["voxels"] = sum(1 for e in table["cells"].values() if e.get("class") == "voxels")
    table_file.write_text(json.dumps(table, indent=2) + "\n", encoding="utf-8")
    print(f"{args.game}: {put} voxel shapes put ({args.source}), {removed} dropped, {kept_first} first-run entries kept; "
          f"{summary['voxels']} voxel entries in the table; {stacked} stack tops, {seats} seats")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
