#!/usr/bin/env python3
"""Fitted shapes: the depth version over every cell the first run gave no model for (docs/specs/FITTED_SHAPES.md
section 9). Runs on a CPU.

Per cell of tools/shape_fit/failed/<game>.json: the drawing as the sprite (nearest x32) and through Real-ESRGAN twice at
x4 (anime and general model, grey and tinted: upscale_test.py's four prompts); Depth Anything V2 Small on all five;
each map averaged per drawn pixel, normalised over the foreground and the maps averaged (0 the farthest drawn pixel, 1
the nearest), on a GPU when there is one. The target is a relief behind the drawing, each drawn pixel a column centred in the cell, by the rules:

    a free-standing thing     TMIN + depth * (span - TMIN) voxels thick, span = the foreground's widest row (as deep
                              as it is wide, like the game's hulls)
    tiled across one axis     TILED_MIN + depth * (TILED_MAX - TILED_MIN): a fence, a rail, a ledge or a post run is
                              a piece of a line, thin as a standee
    tiled across both axes    no target: a wall or cliff pattern; today's pick stands (rules, not a model)

"Tiled" is carve.tiled: the drawing continues across opposite edges.

    python depth_all.py <dump2_<game>.json> <failed/<game>.json> <out dir> [--weights /root/esrgan]
Writes <out>/<cell>/{sprite.png, sprite_full.png (unmasked), prompt_<style>.png, depth.png, depth.json} and <out>/targets_depth.json.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import carve  # noqa: E402
import trellis_targets as tt  # noqa: E402

MODEL = "depth-anything/Depth-Anything-V2-Small-hf"
TMIN = 2
TILED_MIN = 2
TILED_MAX = 4


def depth_maps(depth, sources, fg: np.ndarray) -> list[np.ndarray]:
    """Each source's depth map, averaged per art pixel [row, col] and normalised over the foreground."""
    from PIL import Image

    maps = []
    for img in sources:
        rgb = Image.new("RGB", img.size, (0, 0, 0))
        rgb.paste(img, mask=img.split()[3])
        d = np.array(depth(rgb)["predicted_depth"], dtype=np.float32).squeeze()
        d = np.array(Image.fromarray(d).resize((512, 512), Image.BILINEAR))
        cells16 = d.reshape(tt.W, 512 // tt.W, tt.W, 512 // tt.W).mean(axis=(1, 3))
        vals = cells16[fg]
        if vals.size == 0 or vals.max() - vals.min() < 1e-6:
            continue
        maps.append((cells16 - vals.min()) / (vals.max() - vals.min()))
    return maps


def relief(dn: np.ndarray, fg: np.ndarray, tiled_x: bool, tiled_y: bool) -> np.ndarray | None:
    """The target [x, y, z] by the rules above, or None for a pattern tiled both ways."""
    if tiled_x and tiled_y:
        return None
    if tiled_x or tiled_y:
        lo, hi = TILED_MIN, TILED_MAX
    else:
        lo, hi = TMIN, max(int(fg[r].sum()) for r in range(tt.W)) or tt.W
    grid = np.zeros((tt.W, tt.W, tt.W), dtype=bool)
    for r in range(tt.W):
        for c in range(tt.W):
            if fg[r, c]:
                t = int(round(lo + dn[r, c] * (hi - lo)))
                z0 = (tt.W - t) // 2
                grid[c, tt.W - 1 - r, z0:z0 + t] = True
    return grid


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("dump")
    ap.add_argument("failed")
    ap.add_argument("out")
    ap.add_argument("--weights", default="/root/esrgan")
    args = ap.parse_args()

    import torch
    from PIL import Image
    from spandrel import ModelLoader
    from transformers import pipeline

    import upscale_test as up

    torch.set_num_threads(os.cpu_count() or 4)
    cuda = torch.cuda.is_available()
    cells = {c["cell"]: c for c in json.load(open(args.dump, encoding="utf-8"))["cells"]}
    failed = [c["cell"] for c in json.load(open(args.failed, encoding="utf-8"))["cells"] if c["cell"] in cells]
    upscalers = {k: ModelLoader().load_from_file(os.path.join(args.weights, f)).eval() for k, f in up.MODELS.items()}
    if cuda:
        upscalers = {k: m.cuda() for k, m in upscalers.items()}
    depth = pipeline("depth-estimation", model=MODEL, device=0 if cuda else -1)
    targets, t0 = {}, time.time()
    os.makedirs(args.out, exist_ok=True)
    for n, key in enumerate(failed, 1):
        cell = cells[key]
        folder = os.path.join(args.out, key.replace("/", "_"))
        os.makedirs(folder, exist_ok=True)
        fg = tt.foreground(cell)
        tiled_x, tiled_y = carve.tiled(cell)
        out = {"cell": key, "tiled_x": tiled_x, "tiled_y": tiled_y}
        sprite = up.base_rgba(cell, fg, "grey")
        Image.fromarray(sprite, "RGBA").resize((128, 128), Image.NEAREST).save(os.path.join(folder, "sprite.png"))
        # The whole drawing, unmasked, beside it: what the foreground mask left out.
        whole = up.base_rgba(cell, np.ones_like(fg), "grey")
        Image.fromarray(whole, "RGBA").resize((128, 128), Image.NEAREST).save(os.path.join(folder, "sprite_full.png"))
        if tiled_x and tiled_y:
            out["rule"] = "pattern: today's pick stands"
        else:
            sources = [Image.fromarray(sprite, "RGBA").resize((512, 512), Image.NEAREST)]
            for model_name, colour in up.STYLES:
                big = up.upscale(upscalers[model_name], up.base_rgba(cell, fg, colour))
                img = up.prompt_from(big).crop((128, 128, 640, 640))
                img.resize((256, 256), Image.LANCZOS).save(os.path.join(folder, f"prompt_{model_name}-{colour}.png"))
                sources.append(img)
            maps = depth_maps(depth, sources, fg)
            out["maps"] = len(maps)
            if maps:
                dn = np.clip(np.mean(maps, axis=0), 0, 1)
                grid = relief(dn, fg, tiled_x, tiled_y)
                out["rule"] = "tiled: thin" if (tiled_x or tiled_y) else "free-standing"
                out["target"] = tt.bits_encode(np.transpose(grid, (1, 2, 0)))
                targets[key] = out["target"]
                preview = (np.where(fg, dn, 0) * 255).astype(np.uint8)
                Image.fromarray(preview).resize((128, 128), Image.NEAREST).save(os.path.join(folder, "depth.png"))
        json.dump(out, open(os.path.join(folder, "depth.json"), "w", encoding="utf-8"))
        if n % 10 == 0 or n == len(failed):
            json.dump(targets, open(os.path.join(args.out, "targets_depth.json"), "w", encoding="utf-8"))
            rate = (time.time() - t0) / n
            print(f"{n}/{len(failed)} {rate:.1f} s/cell, eta {rate * (len(failed) - n) / 60:.0f} min", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
