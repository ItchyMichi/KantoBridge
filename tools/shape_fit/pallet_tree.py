#!/usr/bin/env python3
"""Hand-authored Blue boundary tree; geometry and source-pixel addresses only.

The old depth reconstruction kept the sprite's cast shadow as solid ground and
projected its black contour onto the whole side and crown. This lathed crown is
round in both horizontal directions, tapers onto a small stem, and wraps the
interior of the player's own drawing around its sides. No image is embedded.

    python tools/shape_fit/pallet_tree.py --write-table

Optional --preview <16x16 sprite.png> --out <local directory> produces local
modelling previews. The gallery's Minecraft captures remain the visual test.
"""
from __future__ import annotations

import argparse
import base64
import json
from pathlib import Path

KEY = "tilesets.overworld/42-43-58-59"
ROOT = Path(__file__).resolve().parents[2]
TABLE = ROOT / "fabric-bridge/src/main/resources/kantobridge/fitted_shapes/blue.json"
W = 16
# Bottom to top, measured in voxels (eight voxels per Minecraft block).
# A short stem, rounded lower skirt, full crown, then a stepped dome.
RADII = (1.5, 2.0, 3.5, 5.0, 6.0, 6.5, 6.5, 6.5,
         6.5, 6.5, 6.5, 6.25, 5.75, 4.75, 3.5, 2.5)


def index(x: int, y: int, z: int) -> int:
    return (y * W + z) * W + x


def generate() -> dict:
    """Return the runtime's 16-cubed occupancy and per-voxel texel addresses."""
    occupied = [False] * W ** 3
    paint = bytearray(W ** 3)
    for y, radius in enumerate(RADII):
        for z in range(W):
            for x in range(W):
                dx, dz = x + .5 - W / 2, z + .5 - W / 2
                if dx * dx + dz * dz > radius * radius:
                    continue
                i = index(x, y, z)
                occupied[i] = True
                if y < 2:
                    # The dark lower-edge shade supplies the stem, without
                    # turning the sprite's offset ground shadow into geometry.
                    u, v = 7 + (x % 2), 15
                else:
                    # Repeat the source's interior face around four quarters.
                    # Its outer black line is a 2D contour, not a side material.
                    transverse = dz if abs(dx) > abs(dz) else dx
                    v = max(2, min(13, 15 - y))
                    half_width = 3 if v == 2 else 4
                    u = max(4, min(12, round(8 + transverse / radius * half_width)))
                    # Exposed steps near the top use crown interior, not the
                    # black perimeter of the sprite's first row.
                    if y >= 12:
                        v = 3 + ((x + z) % 2)
                        u = max(5, min(11, u))
                paint[i] = v * W + u
    bits = bytearray(W ** 3 // 8)
    for i, solid in enumerate(occupied):
        if solid:
            bits[i >> 3] |= 1 << (i & 7)
    return {"class": "voxels", "v": base64.b64encode(bits).decode("ascii"),
            "from": "hand-pallet-tree", "t": base64.b64encode(paint).decode("ascii")}


def preview(entry: dict, sprite: Path, out: Path) -> None:
    """Optional working images, never substitutes for Minecraft screenshots."""
    import numpy as np
    from PIL import Image
    from hand_check import picture, front

    art = np.asarray(Image.open(sprite).convert("RGB"))
    if art.shape != (W, W, 3):
        raise ValueError("preview expects the assembled 16x16 cell sprite")
    occ = np.unpackbits(np.frombuffer(base64.b64decode(entry["v"]), np.uint8), bitorder="little").reshape(W, W, W).astype(bool)
    address = np.frombuffer(base64.b64decode(entry["t"]), np.uint8).reshape(W, W, W)
    col = art[address // W, address % W]
    out.mkdir(parents=True, exist_ok=True)
    picture(occ, col, out / "hand-tree-isometric.png", 16)
    front(occ, col, out / "hand-tree-front.png", 32)
    front(occ.transpose(0, 2, 1), col.transpose(0, 2, 1, 3), out / "hand-tree-side.png", 32)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--write-table", action="store_true")
    ap.add_argument("--check", action="store_true", help="check the committed entry matches the authoring rules")
    ap.add_argument("--preview", type=Path)
    ap.add_argument("--out", type=Path, default=ROOT / "local/model-gallery/tree-study")
    args = ap.parse_args()
    entry = generate()
    table = json.loads(TABLE.read_text(encoding="utf-8"))
    if args.write_table:
        table["cells"][KEY] = entry
        TABLE.write_text(json.dumps(table, indent=2) + "\n", encoding="utf-8")
    if args.check and table["cells"].get(KEY) != entry:
        print("tree table differs from authoring rules")
        return 1
    if args.preview:
        preview(entry, args.preview, args.out)
    print(f"{KEY}: hand-authored round crown, stem, and wrapped source texels")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
