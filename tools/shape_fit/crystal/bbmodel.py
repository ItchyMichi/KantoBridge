#!/usr/bin/env python3
"""Fitted shapes: voxel shapes to and from Blockbench (docs/specs/FITTED_SHAPES.md section 11). Plain Python.

    python bbmodel.py export <shapes.json> <sprites dir> <out dir>
    python bbmodel.py import <bbmodel dir> <out shapes.json>

export: each cell of <shapes.json> ({cell: voxel bits}) becomes <out>/<cell as file name>.bbmodel, a Blockbench
"generic model" (format 4.10): the voxels merged into boxes (greedy, x then z then y), 2 Blockbench units per voxel, the
cell 32 units on each side (x -16..16 east, y 0..32 up, z -16..16 south: the south side is the side the game draws).
The texture is <sprites dir>/<cell as file name>/sprite_full.png (the whole drawing, unmasked) mapped by front
projection: a box's south and north faces show the drawing's pixels in front of it, its other faces a strip of them.
import: every cube of every .bbmodel (rotations honoured) back to voxels, a voxel solid when its centre lies inside a
cube; the cell is read from the model's name. Writes {cell: bits}, ready for merge_voxels.py.

Voxel bits: base64 of one bit per voxel, index (y * 16 + z) * 16 + x, least significant bit first.
"""
from __future__ import annotations

import base64
import json
import math
import sys
import uuid
from pathlib import Path

W = 16
U = 2  # Blockbench units per voxel
HALF = W * U // 2


def unbits(text: str) -> list[bool]:
    raw = base64.b64decode(text)
    return [(raw[i >> 3] >> (i & 7)) & 1 == 1 for i in range(W ** 3)]


def bits(values: list[bool]) -> str:
    out = bytearray(W ** 3 // 8)
    for i, v in enumerate(values):
        if v:
            out[i >> 3] |= 1 << (i & 7)
    return base64.b64encode(bytes(out)).decode("ascii")


def idx(x: int, y: int, z: int) -> int:
    return (y * W + z) * W + x


def boxes(vox: list[bool]) -> list[tuple[int, int, int, int, int, int]]:
    """Greedy boxes (x0, y0, z0, x1, y1, z1), ends exclusive, covering exactly the solid voxels."""
    used = [False] * len(vox)
    out = []
    free = lambda x, y, z: vox[idx(x, y, z)] and not used[idx(x, y, z)]  # noqa: E731
    for y in range(W):
        for z in range(W):
            for x in range(W):
                if not free(x, y, z):
                    continue
                x1 = x + 1
                while x1 < W and free(x1, y, z):
                    x1 += 1
                z1 = z + 1
                while z1 < W and all(free(i, y, z1) for i in range(x, x1)):
                    z1 += 1
                y1 = y + 1
                while y1 < W and all(free(i, y1, k) for i in range(x, x1) for k in range(z, z1)):
                    y1 += 1
                for yy in range(y, y1):
                    for zz in range(z, z1):
                        for xx in range(x, x1):
                            used[idx(xx, yy, zz)] = True
                out.append((x, y, z, x1, y1, z1))
    return out


def safe(cell: str) -> str:
    return cell.replace("/", "_")


def element(b) -> dict:
    x0, y0, z0, x1, y1, z1 = b
    front = [x0, W - y1, x1, W - y0]  # the drawing's columns x0..x1, rows 16 - y1 .. 16 - y0
    faces = {
        "south": {"uv": front, "texture": 0},
        "north": {"uv": [x1, W - y1, x0, W - y0], "texture": 0},
        "east": {"uv": [x1 - 1, W - y1, x1, W - y0], "texture": 0},
        "west": {"uv": [x0, W - y1, x0 + 1, W - y0], "texture": 0},
        "up": {"uv": [x0, W - y1, x1, W - y1 + 1], "texture": 0},
        "down": {"uv": [x0, W - y0 - 1, x1, W - y0], "texture": 0},
    }
    return {
        "name": "voxels", "box_uv": False, "rescale": False, "locked": False, "render_order": "default",
        "from": [x0 * U - HALF, y0 * U, z0 * U - HALF], "to": [x1 * U - HALF, y1 * U, z1 * U - HALF],
        "autouv": 0, "color": 0, "origin": [0, 0, 0], "faces": faces, "type": "cube", "uuid": str(uuid.uuid4()),
    }


def export(shapes_file: str, sprites: str, out_dir: str) -> int:
    shapes = json.loads(Path(shapes_file).read_text(encoding="utf-8"))
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    for cell, b64 in sorted(shapes.items()):
        els = [element(b) for b in boxes(unbits(b64))]
        sprite = Path(sprites) / safe(cell) / "sprite_full.png"
        textures = []
        if sprite.is_file():
            textures.append({
                "path": "", "name": "sprite_full.png", "folder": "", "namespace": "", "id": "0", "width": 128, "height": 128,
                "uv_width": W, "uv_height": W, "particle": False, "render_mode": "default", "render_sides": "auto",
                "frame_time": 1, "visible": True, "internal": True, "saved": True, "uuid": str(uuid.uuid4()),
                "source": "data:image/png;base64," + base64.b64encode(sprite.read_bytes()).decode("ascii"),
            })
        model = {
            "meta": {"format_version": "4.10", "model_format": "free", "box_uv": False},
            "name": cell, "model_identifier": "", "visible_box": [2, 2, 0], "variable_placeholders": "",
            "variable_placeholder_buttons": [], "timeline_setups": [], "unhandled_root_fields": {},
            "resolution": {"width": W, "height": W}, "elements": els, "outliner": [e["uuid"] for e in els],
            "textures": textures,
        }
        (out / f"{safe(cell)}.bbmodel").write_text(json.dumps(model), encoding="utf-8")
    print(f"exported {len(shapes)} models to {out}")
    return 0


def rot(v, r, inverse=False):
    """v rotated by Blockbench's Euler angles r (degrees, applied X then Y then Z), or by the inverse."""
    ax = [("x", r[0]), ("y", r[1]), ("z", r[2])]
    if inverse:
        ax = [(a, -d) for a, d in reversed(ax)]
    x, y, z = v
    for a, d in ax:
        c, s = math.cos(math.radians(d)), math.sin(math.radians(d))
        if a == "x":
            y, z = y * c - z * s, y * s + z * c
        elif a == "y":
            x, z = x * c + z * s, -x * s + z * c
        else:
            x, y = x * c - y * s, x * s + y * c
    return x, y, z


def rasterize(model: dict) -> list[bool]:
    vox = [False] * (W ** 3)
    cubes = [e for e in model.get("elements", []) if e.get("type", "cube") == "cube"]
    for e in cubes:
        f, t = e["from"], e["to"]
        lo = [min(f[i], t[i]) for i in range(3)]
        hi = [max(f[i], t[i]) for i in range(3)]
        r = e.get("rotation") or [0, 0, 0]
        o = e.get("origin") or [0, 0, 0]
        for y in range(W):
            for z in range(W):
                for x in range(W):
                    p = (x * U - HALF + U / 2, y * U + U / 2, z * U - HALF + U / 2)
                    if any(r):
                        q = rot((p[0] - o[0], p[1] - o[1], p[2] - o[2]), r, inverse=True)
                        p = (q[0] + o[0], q[1] + o[1], q[2] + o[2])
                    if all(lo[i] <= p[i] <= hi[i] for i in range(3)):
                        vox[idx(x, y, z)] = True
    return vox


def import_(model_dir: str, out_file: str) -> int:
    shapes = {}
    for f in sorted(Path(model_dir).glob("*.bbmodel")):
        model = json.loads(f.read_text(encoding="utf-8"))
        cell = model.get("name") or f.stem
        vox = rasterize(model)
        if any(vox):
            shapes[cell] = bits(vox)
    Path(out_file).write_text(json.dumps(shapes), encoding="utf-8")
    print(f"imported {len(shapes)} models to {out_file}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) == 5 and sys.argv[1] == "export":
        sys.exit(export(*sys.argv[2:]))
    if len(sys.argv) == 4 and sys.argv[1] == "import":
        sys.exit(import_(*sys.argv[2:]))
    print(__doc__)
    sys.exit(2)
