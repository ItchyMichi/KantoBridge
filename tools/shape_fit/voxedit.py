#!/usr/bin/env python3
"""Fitted shapes: recorded edits on voxel shapes (docs/specs/FITTED_SHAPES.md section 11). Plain Python.

    python voxedit.py <shapes.json> <edits.json> <out shapes.json>

<edits.json>: {cell: {"note": "...", "ops": [[op, args...], ...]}}; cells without edits pass through unchanged, a cell
whose ops end with ["drop"] is left out (its table entry then falls back to the builders). Coordinates are the
drawing's: column c (0 west .. 15 east), row r (0 top .. 15 bottom); depth z (0 north .. 15 south, the drawn side).

    ["keep_largest"]                    keep only the largest 4-connected shape seen from the front
    ["remove", c0, r0, c1, r1]          remove every voxel in front-view columns c0..c1, rows r0..r1 (inclusive)
    ["add", c0, r0, c1, r1, z0, z1]     fill those columns from depth z0 to z1 (inclusive)
    ["thickness", c0, r0, c1, r1, t]    those columns (where solid) t voxels deep, centred on the cell
    ["round", r0, r1]                   rows r0..r1 as a lathe: each column as deep as the row is wide at it
    ["shift", dz]                       move the whole shape along the depth (south positive)
    ["fill_holes"]                      fill voxels enclosed on both sides along the depth
    ["drop"]                            no shape for this cell
"""
from __future__ import annotations

import base64
import json
import math
import sys

W = 16


def unbits(text: str) -> list[bool]:
    raw = base64.b64decode(text)
    return [(raw[i >> 3] >> (i & 7)) & 1 == 1 for i in range(W ** 3)]


def bits(values: list[bool]) -> str:
    out = bytearray(W ** 3 // 8)
    for i, v in enumerate(values):
        if v:
            out[i >> 3] |= 1 << (i & 7)
    return base64.b64encode(bytes(out)).decode("ascii")


def idx(c: int, r: int, z: int) -> int:
    """The voxel behind drawing column c, row r, at depth z."""
    return ((W - 1 - r) * W + z) * W + c


def column(v, c, r):
    return [z for z in range(W) if v[idx(c, r, z)]]


def keep_largest(v):
    solid = {(c, r) for c in range(W) for r in range(W) if column(v, c, r)}
    best, seen = set(), set()
    for start in solid:
        if start in seen:
            continue
        comp, todo = set(), [start]
        while todo:
            p = todo.pop()
            if p in comp or p not in solid:
                continue
            comp.add(p)
            c, r = p
            todo += [(c + 1, r), (c - 1, r), (c, r + 1), (c, r - 1)]
        seen |= comp
        if len(comp) > len(best):
            best = comp
    for c in range(W):
        for r in range(W):
            if (c, r) not in best:
                for z in range(W):
                    v[idx(c, r, z)] = False


def rect(c0, r0, c1, r1):
    return [(c, r) for c in range(max(0, c0), min(W - 1, c1) + 1) for r in range(max(0, r0), min(W - 1, r1) + 1)]


def apply(v: list[bool], ops: list) -> list[bool] | None:
    v = list(v)
    for op in ops:
        name, args = op[0], op[1:]
        if name == "drop":
            return None
        if name == "keep_largest":
            keep_largest(v)
        elif name == "remove":
            for c, r in rect(*args):
                for z in range(W):
                    v[idx(c, r, z)] = False
        elif name == "add":
            c0, r0, c1, r1, z0, z1 = args
            for c, r in rect(c0, r0, c1, r1):
                for z in range(max(0, z0), min(W - 1, z1) + 1):
                    v[idx(c, r, z)] = True
        elif name == "thickness":
            c0, r0, c1, r1, t = args
            for c, r in rect(c0, r0, c1, r1):
                if column(v, c, r):
                    z0 = (W - t) // 2
                    for z in range(W):
                        v[idx(c, r, z)] = z0 <= z < z0 + t
        elif name == "round":
            r0, r1 = args
            for r in range(r0, r1 + 1):
                cols = [c for c in range(W) if column(v, c, r)]
                if not cols:
                    continue
                left, right = min(cols), max(cols)
                mid, half = (left + right) / 2.0, (right - left + 1) / 2.0
                for c in cols:
                    t = max(1, int(round(2 * math.sqrt(max(0.0, half * half - (c - mid) ** 2)))))
                    z0 = (W - t) // 2
                    for z in range(W):
                        v[idx(c, r, z)] = z0 <= z < z0 + t
        elif name == "shift":
            dz = args[0]
            nv = [False] * len(v)
            for c in range(W):
                for r in range(W):
                    for z in range(W):
                        if v[idx(c, r, z)] and 0 <= z + dz < W:
                            nv[idx(c, r, z + dz)] = True
            v = nv
        elif name == "fill_holes":
            for c in range(W):
                for r in range(W):
                    zs = column(v, c, r)
                    if zs:
                        for z in range(min(zs), max(zs) + 1):
                            v[idx(c, r, z)] = True
        else:
            raise ValueError(f"unknown op {name}")
    return v


def main() -> int:
    shapes = json.load(open(sys.argv[1], encoding="utf-8"))
    edits = json.load(open(sys.argv[2], encoding="utf-8"))
    out, changed, dropped = {}, 0, 0
    for cell, b64 in shapes.items():
        e = edits.get(cell)
        if not e:
            out[cell] = b64
            continue
        v = apply(unbits(b64), e.get("ops", []))
        if v is None or not any(v):
            dropped += 1
            continue
        out[cell] = bits(v)
        changed += 1
    json.dump(out, open(sys.argv[3], "w", encoding="utf-8"))
    print(f"{changed} edited, {dropped} dropped, {len(out)} shapes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
