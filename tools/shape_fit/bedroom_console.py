#!/usr/bin/env python3
"""Hand-model the Blue bedroom console, controller and cable on the native grid.

Eight voxels equal one Minecraft block. The console is horizontal on the floor;
the source drawing's vertical axis describes depth, not the height of a slab.
Only geometry and source-texel addresses are written to the distributable table.

    python tools/shape_fit/bedroom_console.py --write-table
    python tools/shape_fit/bedroom_console.py --check
"""
from __future__ import annotations

import argparse
import base64
import json
import math
from pathlib import Path

KEY = "tilesets.reds_house/14-15-30-31"
ROOT = Path(__file__).resolve().parents[2]
TABLE = ROOT / "fabric-bridge/src/main/resources/kantobridge/fitted_shapes/blue.json"
W = 16
# Addresses in this cell's own drawing, not baked colours. Its palette remains
# the player's: the same model also appears in Copycat's room in another palette.
PLASTIC = 5 * W + 4
ACCENT = 6 * W + 4
SHADE = 8 * W + 2
DARK = 4 * W + 4
CABLE = ((3, 9), (3, 11), (4, 11), (4, 12), (6, 12),
         (6, 11), (9, 11), (9, 12), (10, 12))


def index(x: int, y: int, z: int) -> int:
    return (y * W + z) * W + x


def controller_distance(x: float, z: float) -> float:
    """Distance to the diagonal pad's centreline: two grips, joined by a waist."""
    ax, az, bx, bz = 11.0, 13.0, 14.0, 10.0
    dx, dz = bx - ax, bz - az
    t = max(0.0, min(1.0, ((x - ax) * dx + (z - az) * dz) / (dx * dx + dz * dz)))
    return math.hypot(x - ax - t * dx, z - az - t * dz)


def generate() -> dict:
    occupied = [False] * W ** 3
    paint = bytearray(W ** 3)

    def put(x, y, z, texel):
        if not (0 <= x < W and 0 <= y < W and 0 <= z < W):
            raise ValueError("console part exceeds its source cell")
        i = index(x, y, z)
        occupied[i], paint[i] = True, texel

    # Four feet support a dark lower chassis. The empty space beneath it is real.
    for x in (2, 8):
        for z in (2, 8):
            put(x, 0, z, DARK)
    for x in range(1, 10):
        for z in range(1, 10):
            if x in (1, 9) and z in (1, 9):
                continue  # chamfered corners
            put(x, 1, z, SHADE)
            put(x, 2, z, PLASTIC)
    # Inset upper shell makes the case bevel readable from the side.
    for x in range(2, 9):
        for z in range(2, 9):
            put(x, 3, z, PLASTIC)

    # Raised cartridge-slot surround, with a one-voxel-deep dark opening.
    # The cavity is empty above its floor, not a painted solid rectangle.
    for x in range(2, 9):
        for z in range(2, 6):
            if 3 <= x <= 7 and z in (3, 4):
                put(x, 3, z, DARK)
            else:
                put(x, 4, z, SHADE if x in (2, 8) or z == 5 else PLASTIC)
    # Two separate raised controls on the front of the top panel.
    for x, texel in ((3, SHADE), (4, SHADE), (6, ACCENT), (7, ACCENT)):
        put(x, 4, 6, texel)
    # Side ventilation and a cable socket use the source's dark detail texel.
    for z in (3, 5, 7):
        put(9, 2, z, DARK)
    put(3, 1, 9, DARK)

    # A separate diagonal controller rests on the floor at the drawing's
    # lower-right position. The lower rim projects beyond its light shell.
    for x in range(W):
        for z in range(W):
            distance = controller_distance(x + .5, z + .5)
            if distance <= 1.95:
                put(x, 0, z, DARK)
            if distance <= 1.65:
                put(x, 1, z, PLASTIC)
    # Raised directional cross, two face buttons and a small centre button.
    for x, z in ((10, 12), (10, 13), (10, 14), (9, 13), (11, 13)):
        if occupied[index(x, 1, z)]:
            put(x, 2, z, DARK)
    for x, z, texel in ((13, 9, SHADE), (14, 10, ACCENT), (12, 11, SHADE)):
        if occupied[index(x, 1, z)]:
            put(x, 2, z, texel)

    # One-voxel cable with an open loop. It joins the case socket to the pad;
    # the sprite's offset floor shadow is deliberately absent.
    for (x0, z0), (x1, z1) in zip(CABLE, CABLE[1:]):
        steps = max(abs(x1 - x0), abs(z1 - z0))
        for step in range(steps + 1):
            x = round(x0 + (x1 - x0) * step / max(1, steps))
            z = round(z0 + (z1 - z0) * step / max(1, steps))
            put(x, 0, z, DARK)

    bits = bytearray(W ** 3 // 8)
    for i, solid in enumerate(occupied):
        if solid:
            bits[i >> 3] |= 1 << (i & 7)
    return {"class": "voxels", "v": base64.b64encode(bits).decode("ascii"),
            "from": "hand-bedroom-console", "t": base64.b64encode(paint).decode("ascii")}


def preview(entry: dict, sprite: Path, out: Path) -> None:
    import numpy as np
    from PIL import Image
    from hand_check import picture, front

    art = np.asarray(Image.open(sprite).convert("RGB"))
    if art.shape != (W, W, 3):
        raise ValueError("preview expects the assembled 16x16 console sprite")
    occ = np.unpackbits(np.frombuffer(base64.b64decode(entry["v"]), np.uint8), bitorder="little").reshape(W, W, W).astype(bool)
    address = np.frombuffer(base64.b64decode(entry["t"]), np.uint8).reshape(W, W, W)
    colour = art[address // W, address % W]
    out.mkdir(parents=True, exist_ok=True)
    picture(occ, colour, out / "console-hand-isometric.png", 20)
    front(occ, colour, out / "console-hand-front.png", 32)
    front(occ.transpose(0, 2, 1), colour.transpose(0, 2, 1, 3), out / "console-hand-side.png", 32)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write-table", action="store_true")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--preview", type=Path)
    parser.add_argument("--out", type=Path, default=ROOT / "local/model-gallery/bedroom-console-study")
    args = parser.parse_args()
    entry = generate()
    table = json.loads(TABLE.read_text(encoding="utf-8"))
    if args.write_table:
        table["cells"][KEY] = entry
        TABLE.write_text(json.dumps(table, indent=2) + "\n", encoding="utf-8")
    if args.check and table["cells"].get(KEY) != entry:
        print("console table differs from authoring rules")
        return 1
    if args.preview:
        preview(entry, args.preview, args.out)
    print(f"{KEY}: low case, recessed cartridge slot, controls, diagonal pad and floor cable")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
