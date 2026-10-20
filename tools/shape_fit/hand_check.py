#!/usr/bin/env python3
"""A hand model's check pictures (docs/specs/HAND_MODELS.md section 4), for the modeller (an agent or a person).

    python hand_check.py <package dir>

Imports the package's `model.bbmodel` as the game will (hand_import.py: sliced per cell, painted from the model's UVs)
and writes `<package>/out/`: `hand.json` (the cells' shapes for merge_voxels.py), the kb-hub viewer file, `view.png`
(3D from the south-east, above) and `front.png` (straight from the south), coloured as the game colours them.
"""
from __future__ import annotations

import base64
import json
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent


def main() -> int:
    pkg = Path(sys.argv[1])
    out = pkg / "out"
    out.mkdir(exist_ok=True)
    r = subprocess.run([sys.executable, str(HERE / "hand_import.py"), str(pkg), "--out", str(out / "hand.json"), "--viewer", str(out)],
                       capture_output=True, text=True)
    print(r.stdout.strip())
    if r.returncode != 0:
        print(r.stderr.strip()[-2000:])
        return 1
    spec = json.loads((pkg / "spec.json").read_text(encoding="utf-8"))
    view = json.loads((out / f"{spec['id']}.json").read_text(encoding="utf-8"))
    W, H, D = view["dims"]
    occ = np.unpackbits(np.frombuffer(base64.b64decode(view["voxels"]), np.uint8), bitorder="little")[: W * H * D].astype(bool)
    shade = np.frombuffer(base64.b64decode(view["shades"]), np.uint8)[: W * H * D]
    pal = np.array([[int(c[i:i + 2], 16) for i in (1, 3, 5)] for c in view["palette"]] + [[0, 0, 0]] * 192, np.uint8)
    occ = occ.reshape(H, D, W)
    col = pal[np.minimum(shade, 255)].reshape(H, D, W, 3)
    picture(occ, col, out / "view.png")
    front(occ, col, out / "front.png")
    print("pictures:", out / "view.png", out / "front.png")
    return 0


def picture(occ: np.ndarray, col: np.ndarray, path: Path, s: int = 8) -> None:
    H, D, W = occ.shape

    def p(x, y, z):
        return ((x - z) * s, (x + z) * s / 2 - y * s * 1.15)

    xs = [p(a, b, c)[0] for a in (0, W) for b in (0, H) for c in (0, D)]
    ys = [p(a, b, c)[1] for a in (0, W) for b in (0, H) for c in (0, D)]
    ox, oy = -min(xs) + 20, -min(ys) + 20
    img = Image.new("RGB", (int(max(xs) - min(xs)) + 40, int(max(ys) - min(ys)) + 40), (40, 44, 52))
    d = ImageDraw.Draw(img)
    q = lambda x, y, z: (p(x, y, z)[0] + ox, p(x, y, z)[1] + oy)  # noqa: E731
    # the ground footprint, for scale: a line every cell (16 voxels)
    for gx in range(0, W + 1, 16):
        d.line([q(gx, 0, 0), q(gx, 0, D)], fill=(70, 74, 84))
    for gz in range(0, D + 1, 16):
        d.line([q(0, 0, gz), q(W, 0, gz)], fill=(70, 74, 84))
    for y, z, x in np.argwhere(occ):
        c = col[y, z, x].astype(int)
        if y + 1 >= H or not occ[y + 1, z, x]:
            d.polygon([q(x, y + 1, z), q(x + 1, y + 1, z), q(x + 1, y + 1, z + 1), q(x, y + 1, z + 1)], fill=tuple(c))
        if z + 1 >= D or not occ[y, z + 1, x]:
            d.polygon([q(x, y, z + 1), q(x + 1, y, z + 1), q(x + 1, y + 1, z + 1), q(x, y + 1, z + 1)], fill=tuple((c * 0.86).astype(int)))
        if x + 1 >= W or not occ[y, z, x + 1]:
            d.polygon([q(x + 1, y, z), q(x + 1, y, z + 1), q(x + 1, y + 1, z + 1), q(x + 1, y + 1, z)], fill=tuple((c * 0.7).astype(int)))
    img.save(path)


def front(occ: np.ndarray, col: np.ndarray, path: Path, scale: int = 8) -> None:
    H, D, W = occ.shape
    img = np.full((H, W, 3), 40, np.uint8)
    for y in range(H):
        for x in range(W):
            zs = np.nonzero(occ[y, :, x])[0]
            if len(zs):
                img[H - 1 - y, x] = col[y, zs.max(), x]
    Image.fromarray(img).resize((W * scale, H * scale), Image.NEAREST).save(path)


if __name__ == "__main__":
    raise SystemExit(main())
