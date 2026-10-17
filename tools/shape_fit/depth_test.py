#!/usr/bin/env python3
"""Fitted shapes: depth from a monocular depth model instead of a whole 3D object (docs/specs/FITTED_SHAPES.md
section 8).

For each cell of an upscale_test.py result folder: Depth Anything V2 Small (Apache-2.0) on the sprite (nearest x16)
and on its four upscaled prompts; each depth map is averaged over every drawing pixel, normalised over the
foreground (0 the farthest drawn pixel, 1 the nearest) and the maps averaged. The target then stands behind the drawing
exactly, by depth_all.relief's rules (thin for a tiled piece, none for a pattern). It is a relief, so it is aligned
with the art pixel for pixel.

    python depth_test.py <dump.json> <results dir>/<game>   (writes depth.json and depth.png into each cell's folder)
"""
from __future__ import annotations

import base64
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import carve  # noqa: E402
import trellis_targets as tt  # noqa: E402
from depth_all import depth_maps, relief  # noqa: E402

MODEL = "depth-anything/Depth-Anything-V2-Small-hf"


def main() -> int:
    from PIL import Image
    from transformers import pipeline

    dump = {c["cell"]: c for c in json.load(open(sys.argv[1], encoding="utf-8"))["cells"]}
    root = sys.argv[2]
    depth = pipeline("depth-estimation", model=MODEL, device=0 if __import__("torch").cuda.is_available() else -1)
    for name in sorted(os.listdir(root)):
        folder = os.path.join(root, name)
        res_file = os.path.join(folder, "result.json")
        if not os.path.isfile(res_file):
            continue
        key = json.load(open(res_file, encoding="utf-8"))["cell"]
        cell = dump[key]
        fg = tt.foreground(cell)
        sources = []
        sprite = Image.open(os.path.join(folder, "sprite.png")).convert("RGBA")
        sources.append(("sprite", sprite.resize((512, 512), Image.NEAREST)))
        for f in sorted(os.listdir(folder)):
            if f.startswith("prompt_") and f.endswith(".png"):
                sources.append((f[7:-4], Image.open(os.path.join(folder, f)).convert("RGBA").resize((512, 512), Image.LANCZOS)))
        maps = depth_maps(depth, [img for _, img in sources], fg)
        out = {"cell": key, "sources": [s[0] for s in sources], "maps": len(maps)}
        if maps:
            dn = np.clip(np.mean(maps, axis=0), 0, 1)
            grid = relief(dn, fg, *carve.tiled(cell))
            if grid is None:
                json.dump(out, open(os.path.join(folder, "depth.json"), "w", encoding="utf-8"))
                continue
            out["target"] = tt.bits_encode(np.transpose(grid, (1, 2, 0)))
            out["depth16"] = [[round(float(v), 3) for v in row] for row in dn]
            preview = (np.where(fg, dn, 0) * 255).astype(np.uint8)
            Image.fromarray(preview).resize((128, 128), Image.NEAREST).save(os.path.join(folder, "depth.png"))
        json.dump(out, open(os.path.join(folder, "depth.json"), "w", encoding="utf-8"))
        print(key, "maps", len(maps), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
