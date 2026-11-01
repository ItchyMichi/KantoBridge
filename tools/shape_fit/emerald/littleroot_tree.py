#!/usr/bin/env python3
"""Hand-model Littleroot's broadleaf tree across its four solid map cells.

The two grass cells showing the projected crown tip remain walkable. The tree
stands in the existing 2x2 solid footprint, using height layers for its crown.
Only geometry and addresses in each cell's own artwork enter the fitted table.
"""
from __future__ import annotations

import argparse
import base64
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TABLE = ROOT / "fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json"
W, HEIGHT = 16, 36
KEYS = {
    468: "g3q/12331-12332-12333-12334", 469: "g3q/12335-12336-12337-12338",
    470: "g3q/12339-12332-12340-12334", 471: "g3q/12335-12341-12337-12342",
    476: "g3q/12349-12350-12351-12352", 477: "g3q/12353-12354-12355-12356",
    484: "g3q/12349-12350-12351-12372", 485: "g3q/12353-12354-12373-12356",
    486: "g3q/12357-12350-12358-12374", 487: "g3q/12353-12360-12375-12362",
}
ROLES = {468: (0, 0), 469: (1, 0), 470: (0, 0), 471: (1, 0),
         476: (0, 1), 477: (1, 1), 484: (0, 1), 485: (1, 1),
         486: (0, 1), 487: (1, 1)}
# Four foliage tones, dark to light, sampled inside the original drawing.
# Southern tiles have no brightest crown colour: their light green is retained.
# No grass, turquoise cast shadow, or another tree's tip is sampled.
LEAF_UV = {
    468: ((15, 0), (6, 0), (12, 0), (7, 0)),
    469: ((2, 2), (1, 0), (3, 0), (0, 0)),
    470: ((15, 0), (7, 0), (12, 0), (8, 0)),
    471: ((2, 2), (1, 0), (3, 0), (0, 0)),
    476: ((1, 0), (4, 0), (3, 0), (3, 0)),
    477: ((2, 0), (0, 0), (4, 0), (4, 0)),
    484: ((1, 0), (4, 0), (3, 0), (3, 0)),
    485: ((2, 0), (0, 0), (4, 0), (4, 0)),
    486: ((1, 0), (4, 0), (3, 0), (3, 0)),
    487: ((2, 0), (0, 0), (4, 0), (4, 0)),
}
BARK_UV = {mid: (((12, 9), (13, 10)) if ROLES[mid][0] == 0 else ((3, 9), (0, 10)))
           for mid in (476, 477, 484, 485, 486, 487)}
# Intersecting lobes produce a rounded, irregular crown with a high central tip
# and lower shoulders. Coordinates are global voxels across the four cells.
LOBES = ((16, 23, 16, 12, 11, 11), (8, 18, 18, 7, 8, 9),
         (24, 19, 18, 7, 8, 9), (16, 15, 24, 11, 6, 7),
         (16, 30, 14, 7, 6, 7), (16, 22, 7, 10, 8, 6))


def index(x: int, y: int, z: int) -> int:
    return (y * W + z) * W + x


def noise(x: int, y: int, z: int) -> int:
    return ((x * 73856093) ^ (y * 19349663) ^ (z * 83492791)) % 100


def material(x: int, y: int, z: int) -> str | None:
    """A connected tree, with no extrusion of the sprite's ground or shadow."""
    px, py, pz = x + .5, y + .5, z + .5
    for cx, cy, cz, rx, ry, rz in LOBES:
        if ((px - cx) / rx) ** 2 + ((py - cy) / ry) ** 2 + ((pz - cz) / rz) ** 2 <= 1:
            return "leaf"
    # A slightly flared root collar narrows into a trunk under the crown.
    radius = 3.0 if y < 2 else 2.25
    if y <= 16 and (px - 16) ** 2 + (pz - 21) ** 2 <= radius ** 2:
        return "bark"
    # Two rising branches join the crown shoulders rather than hanging loose.
    if 7 <= y <= 14:
        t = (y - 7) / 7
        for side in (-1, 1):
            if (px - (16 + side * t * 6)) ** 2 + (pz - 21) ** 2 <= 1.5 ** 2:
                return "bark"
    return None


def leaf_tone(x: int, y: int, z: int) -> int:
    # Two-voxel leaf patches follow the sculpted lobes, without stripe bands.
    n = noise(x // 2, y // 2, z // 2)
    top = material(x, y + 1, z) != "leaf"
    if top and y >= 21:
        # Fade the original lime highlight before the source-cell boundary.
        # Southern source cells only have the shared light green, so a hard
        # switch at z=16 would draw an artificial seam through the crown.
        return 3 if n < max(0, min(55, (14 - z) * 12)) else (2 if n < 68 else 1)
    if y >= 25:
        return 3 if n < max(0, min(24, (14 - z) * 6)) else (2 if n < 78 else 1)
    if y >= 16:
        return 2 if n < 43 else (1 if n < 93 else 0)
    return 2 if n < 12 else (1 if n < 80 else 0)


def generate() -> dict[str, dict]:
    entries = {}
    for mid, key in KEYS.items():
        cx, cz = ROLES[mid]
        layers = []
        for layer_y in range(math.ceil(HEIGHT / W)):
            bits, paint = bytearray(W ** 3 // 8), bytearray(W ** 3)
            for y in range(W):
                gy = layer_y * W + y
                if gy >= HEIGHT:
                    continue
                for z in range(W):
                    for x in range(W):
                        gx, gz = cx * W + x, cz * W + z
                        kind = material(gx, gy, gz)
                        if kind is None:
                            continue
                        i = index(x, y, z)
                        bits[i >> 3] |= 1 << (i & 7)
                        if kind == "bark" and mid in BARK_UV:
                            u, v = BARK_UV[mid][(gx + 2 * gz) % 5 > 1]
                        else:
                            u, v = LEAF_UV[mid][leaf_tone(gx, gy, gz)]
                        paint[i] = v * W + u
            layers.append({"v": base64.b64encode(bits).decode("ascii"),
                           "t": base64.b64encode(paint).decode("ascii")})
        while len(layers) > 1 and not any(base64.b64decode(layers[-1]["v"])):
            layers.pop()
        entries[key] = {"class": "voxels", **layers[0], "from": "hand-littleroot-tree", "up": layers[1:]}
    return entries


def preview(entries: dict, sprites: Path, out: Path) -> None:
    import sys
    import numpy as np
    from PIL import Image
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from hand_check import picture, front

    occ = np.zeros((HEIGHT, 32, 32), dtype=bool)
    col = np.zeros((HEIGHT, 32, 32, 3), dtype=np.uint8)
    for mid in (468, 469, 484, 485):
        cx, cz = ROLES[mid]
        art = np.asarray(Image.open(sprites / f"mid-{mid}.png").convert("RGB"))
        entry = entries[KEYS[mid]]
        for n, layer in enumerate([entry, *entry.get("up", [])]):
            mask = np.unpackbits(np.frombuffer(base64.b64decode(layer["v"]), np.uint8), bitorder="little").reshape(W, W, W)
            uv = np.frombuffer(base64.b64decode(layer["t"]), np.uint8).reshape(W, W, W)
            height = min(W, HEIGHT - n * W)
            occ[n * W:n * W + height, cz * W:(cz + 1) * W, cx * W:(cx + 1) * W] = mask[:height]
            col[n * W:n * W + height, cz * W:(cz + 1) * W, cx * W:(cx + 1) * W] = art[uv[:height] // W, uv[:height] % W]
    out.mkdir(parents=True, exist_ok=True)
    picture(occ, col, out / "littleroot-hand-isometric.png", 12)
    front(occ, col, out / "littleroot-hand-front.png", 16)
    front(occ.transpose(0, 2, 1), col.transpose(0, 2, 1, 3), out / "littleroot-hand-side.png", 16)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--write-table", action="store_true")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--preview", type=Path, help="local directory of mid-N.png source sprites")
    ap.add_argument("--out", type=Path, default=ROOT / "local/model-gallery/emerald-tree-study/model")
    args = ap.parse_args()
    entries = generate()
    table = json.loads(TABLE.read_text(encoding="utf-8"))
    if args.write_table:
        table["cells"].update(entries)
        TABLE.write_text(json.dumps(table, indent=2) + "\n", encoding="utf-8")
    if args.check and any(table["cells"].get(k) != v for k, v in entries.items()):
        print("Littleroot tree table differs from authoring rules")
        return 1
    if args.preview:
        preview(entries, args.preview, args.out)
    print(f"{len(entries)} cell variants: four-cell tree, lobed crown, trunk, roots and wrapped foliage")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
