#!/usr/bin/env python3
"""Fitted shapes: an AI upscaler before TRELLIS.2, tried on cells the first run gave no model for
(docs/specs/FITTED_SHAPES.md section 7).

Each cell's drawing (grey, or tinted as trellis_targets.py tints it) goes through Real-ESRGAN twice at x4
(16 -> 64 -> 256 px), its alpha too, then into TRELLIS.2's first stage like trellis_targets.py's prompts. Four runs per
cell: the anime model and the general model, each grey and tinted. Shells are dropped and the runs vote as before.

Runs on the GPU pod next to trellis_targets.py (imported), with spandrel and the two weights files:
    python upscale_test.py <dump.json> <cells.json> <out dir> [--weights /workspace/esrgan]
<cells.json> is a list of cell keys. Writes <out>/<cell>/: sprite.png, prompt_<style>.png and result.json (per run its
outcome and placed voxels, and the voted target). The pictures are ROM-derived: they stay on the pod and the user's
own tailnet viewer.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trellis_targets as tt  # noqa: E402

MODELS = {"anime": "RealESRGAN_x4plus_anime_6B.pth", "general": "RealESRGAN_x4plus.pth"}
STYLES = [(m, c) for m in MODELS for c in ("grey", "tint")]


def base_rgba(cell: dict, fg: np.ndarray, colour: str) -> np.ndarray:
    shades = tt.shades_of(cell)
    palette = (tt.FOLIAGE if tt.is_tree(cell) else tt.STONE) if colour == "tint" else [(g, g, g) for g in tt.GREY]
    rgba = np.zeros((tt.W, tt.W, 4), dtype=np.uint8)
    for s in range(4):
        rgba[shades == s, :3] = palette[s]
    rgba[..., 3] = np.where(fg & (shades >= 0), 255, 0)
    return rgba


def upscale(model, rgba: np.ndarray, passes: int = 2) -> np.ndarray:
    """RGB and alpha each through the model `passes` times (x4 each); alpha thresholded at half."""
    import torch

    a = rgba[..., 3:4].astype(np.float32) / 255.0
    rgb = (rgba[..., :3].astype(np.float32) / 255.0 * a).astype(np.float32)  # on black, as TRELLIS sees it
    alpha = np.repeat(a, 3, axis=2)
    out = []
    for img in (rgb, alpha):
        t = torch.from_numpy(np.ascontiguousarray(img, dtype=np.float32)).permute(2, 0, 1)[None].cuda()
        with torch.no_grad():
            for _ in range(passes):
                t = model(t).clamp(0, 1)
        out.append(t[0].permute(1, 2, 0).cpu().numpy())
    a = (out[1].mean(axis=2) >= 0.5).astype(np.uint8) * 255
    return np.dstack([(out[0] * 255).round().astype(np.uint8), a])


def prompt_from(big: np.ndarray):
    from PIL import Image

    img = Image.fromarray(big, "RGBA").resize((512, 512), Image.LANCZOS)
    a = np.array(img)
    a[..., 3] = np.where(a[..., 3] >= 128, 255, 0)
    img = Image.fromarray(a, "RGBA")
    canvas = Image.new("RGBA", (768, 768), (0, 0, 0, 0))
    canvas.paste(img, (128, 128))
    return canvas


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("dump")
    ap.add_argument("cells")
    ap.add_argument("out")
    ap.add_argument("--weights", default="/workspace/esrgan")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    import torch
    from PIL import Image
    from spandrel import ModelLoader
    from trellis2.pipelines import Trellis2ImageTo3DPipeline, rembg

    class NoBackgroundRemoval:
        def __init__(self, *a, **k):
            pass

        def to(self, *a, **k):
            return self

        def cpu(self):
            return self

        def __call__(self, image):
            raise RuntimeError("no background removal: every prompt has an alpha channel")

    rembg.BiRefNet = NoBackgroundRemoval
    wanted = set(json.load(open(args.cells, encoding="utf-8")))
    cells = [c for c in json.load(open(args.dump, encoding="utf-8"))["cells"] if c["cell"] in wanted]
    print(f"{len(cells)} cells", flush=True)
    upscalers = {k: ModelLoader().load_from_file(os.path.join(args.weights, f)).cuda().eval() for k, f in MODELS.items()}
    pipe = Trellis2ImageTo3DPipeline.from_pretrained("microsoft/TRELLIS.2-4B")
    pipe.cuda()
    t0 = time.time()
    for n, cell in enumerate(cells, 1):
        key = cell["cell"]
        folder = os.path.join(args.out, key.replace("/", "_"))
        os.makedirs(folder, exist_ok=True)
        fg = tt.foreground(cell)
        sprite = base_rgba(cell, fg, "grey")
        Image.fromarray(sprite, "RGBA").resize((128, 128), Image.NEAREST).save(os.path.join(folder, "sprite.png"))
        result = {"cell": key, "runs": []}
        placed = []
        for model_name, colour in STYLES:
            style = f"{model_name}-{colour}"
            big = upscale(upscalers[model_name], base_rgba(cell, fg, colour))
            prompt = prompt_from(big)
            prompt.crop((128, 128, 640, 640)).resize((256, 256), Image.LANCZOS).save(os.path.join(folder, f"prompt_{style}.png"))
            image = pipe.preprocess_image(prompt)
            torch.manual_seed(args.seed)
            with torch.no_grad():
                cond = pipe.get_cond([image], 512)
                coords = pipe.sample_sparse_structure(cond, tt.SS, 1, {})[:, 1:].cpu().numpy()
            surface = np.zeros((tt.SS,) * 3, dtype=bool)
            surface[coords[:, 0], coords[:, 1], coords[:, 2]] = True
            run = {"style": style, "voxels": int(surface.sum())}
            if tt.degenerate(surface):
                run["dropped"] = "shell"
            else:
                occ = tt.solid(coords)
                best = max(((tt.view_iou(g, fg), side, g) for side, g in tt.frames(occ)), key=lambda t: t[0])
                lo, hi = tt.bbox(best[2])
                grid = tt.place(best[2], fg)
                run.update(side=best[1], view_iou=round(best[0], 3), depth=round(float((hi[2] - lo[2]) / max(1, hi[0] - lo[0])), 2),
                           placed=tt.bits_encode(np.transpose(grid, (1, 2, 0))))
                placed.append(grid)
            result["runs"].append(run)
        result["kept"] = len(placed)
        if placed:
            vote = np.mean(np.stack(placed).astype(np.float32), axis=0) >= 0.5
            result["target"] = tt.bits_encode(np.transpose(vote, (1, 2, 0)))
        json.dump(result, open(os.path.join(folder, "result.json"), "w", encoding="utf-8"))
        print(f"{n}/{len(cells)} {key}: kept {len(placed)} of {len(STYLES)} ({time.time() - t0:.0f} s)", flush=True)
        torch.cuda.empty_cache()
    return 0


if __name__ == "__main__":
    sys.exit(main())
