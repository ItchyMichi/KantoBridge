#!/usr/bin/env python3
"""Fitted shapes: carve the upscaler test's runs to their drawing and re-vote (docs/specs/FITTED_SHAPES.md section 8).

Plain Python (no numpy). For each cell of an upscale_test.py result folder:
  1. The runs whose south view matched the drawing well (view IoU >= MIN_VIEW; at least the best two) are kept.
  2. Each is carved: a voxel stays only where the drawing's foreground has a pixel in front of it (x, row 15 - y), as
     the game's builders keep every shape behind its drawing.
  3. They vote (at least half), and every foreground pixel left with no voxel gets the median depth span of its row
     (else of the whole model), so the front always shows the whole drawing.
Also marks each cell `tiled_x` / `tiled_y` when its drawing repeats across that edge (a fence, a wall, a ledge: a piece
of a pattern, not an object; such cells are better left to rules than to an image-to-3D model).

    python3 carve.py <results dir>/<game> <dump2_<game>.json> <out targets json>
"""
from __future__ import annotations

import base64
import json
import statistics
import sys
from pathlib import Path

W = 16
MIN_VIEW = 0.75


def unbits(text: str, n: int) -> list[bool]:
    raw = base64.b64decode(text)
    return [(raw[i >> 3] >> (i & 7)) & 1 == 1 for i in range(n)]


def bits(values: list[bool]) -> str:
    out = bytearray((len(values) + 7) // 8)
    for i, v in enumerate(values):
        if v:
            out[i >> 3] |= 1 << (i & 7)
    return base64.b64encode(bytes(out)).decode("ascii")


def idx(x: int, y: int, z: int) -> int:
    return (y * W + z) * W + x


def foreground(cell: dict) -> list[list[bool]]:
    """As trellis_targets.foreground: [row][col]."""
    fg = [False] * (W * W)
    for v in cell["variants"]:
        if v["kind"] in ("hull", "object"):
            fg = [a or b for a, b in zip(fg, unbits(v["foreground"], W * W))]
    if not any(fg) and cell.get("hullMask"):
        fg = unbits(cell["hullMask"], W * W)
    if not any(fg):
        shades = base64.b64decode(cell["shades"])
        fg = [(s if s < 128 else s - 256) >= 0 for s in shades]
    return [fg[r * W:(r + 1) * W] for r in range(W)]


def tiled(cell: dict) -> tuple[bool, bool]:
    """Whether the drawing continues across its left/right edges (x) or top/bottom edges (y): the opposite edges'
    shades agree on at least 14 of 16 pixels and the foreground touches both."""
    raw = base64.b64decode(cell["shades"])
    s = [[(raw[r * W + c] if raw[r * W + c] < 128 else raw[r * W + c] - 256) for c in range(W)] for r in range(W)]
    fg = foreground(cell)
    same_x = sum(s[r][0] == s[r][W - 1] for r in range(W))
    same_y = sum(s[0][c] == s[W - 1][c] for c in range(W))
    touch_x = any(fg[r][0] for r in range(W)) and any(fg[r][W - 1] for r in range(W))
    touch_y = any(fg[0][c] for c in range(W)) and any(fg[W - 1][c] for c in range(W))
    return same_x >= 14 and touch_x, same_y >= 14 and touch_y


def carve(res: dict, fg: list[list[bool]]) -> list[bool] | None:
    runs = [r for r in res["runs"] if r.get("placed")]
    if not runs:
        return None
    runs.sort(key=lambda r: -r.get("view_iou", 0))
    good = [r for r in runs if r.get("view_iou", 0) >= MIN_VIEW]
    if len(good) < 2:
        good = runs[:2]
    grids = []
    for r in good:
        g = unbits(r["placed"], W ** 3)
        for y in range(W):
            row = fg[W - 1 - y]
            for z in range(W):
                for x in range(W):
                    if not row[x]:
                        g[idx(x, y, z)] = False
        grids.append(g)
    need = (len(grids) + 1) // 2
    vote = [sum(g[i] for g in grids) >= need for i in range(W ** 3)]
    # Fill: a drawn pixel with no voxel behind it gets its row's median span, else the model's.
    spans = {}
    for y in range(W):
        for x in range(W):
            zs = [z for z in range(W) if vote[idx(x, y, z)]]
            if zs:
                spans.setdefault(y, []).append((min(zs), max(zs)))
    every = [s for v in spans.values() for s in v]
    if not every:
        every = [(W // 2 - 1, W // 2)]
    for y in range(W):
        pool = spans.get(y) or every
        z0 = int(statistics.median(s[0] for s in pool))
        z1 = int(statistics.median(s[1] for s in pool))
        for x in range(W):
            if fg[W - 1 - y][x] and not any(vote[idx(x, y, z)] for z in range(W)):
                for z in range(z0, z1 + 1):
                    vote[idx(x, y, z)] = True
    return vote


def main() -> int:
    results, dump_file, out_file = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
    cells = {c["cell"]: c for c in json.load(open(dump_file, encoding="utf-8"))["cells"]}
    targets, info = {}, {}
    for f in sorted(results.glob("*/result.json")):
        res = json.loads(f.read_text(encoding="utf-8"))
        cell = cells[res["cell"]]
        fg = foreground(cell)
        tx, ty = tiled(cell)
        info[res["cell"]] = {"tiled_x": tx, "tiled_y": ty}
        vote = carve(res, fg)
        if vote is not None:
            targets[res["cell"]] = bits(vote)
            info[res["cell"]]["voxels"] = sum(vote)
    json.dump(targets, open(out_file, "w", encoding="utf-8"))
    json.dump(info, open(out_file.replace(".json", ".info.json"), "w", encoding="utf-8"), indent=1)
    print(f"{len(targets)} carved targets; tiled: {sum(1 for v in info.values() if v['tiled_x'] or v['tiled_y'])} of {len(info)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
