#!/usr/bin/env python3
"""Fitted shapes, steps 2 and 3 (docs/specs/FITTED_SHAPES.md): each dumped cell's drawing -> TRELLIS.2 -> a solid
16 x 16 x 16 voxel target in the unit's own frame (x east, y up, z south; the drawing is the unit's south view, as the
game's hulls read it: drawing row r is voxel row 15 - r).

Runs on a GPU pod inside TRELLIS.2's checkout and venv (it imports trellis2). Input: FitTool's dump (ROM-derived: it
holds the drawings' shades; keep it on the pod or under local/). Output: targets.json, {cell: base64 of one bit per
voxel, index (y * 16 + z) * 16 + x}, written as it goes so a stopped run resumes, and targets.meta.jsonl (per cell its
runs' depth, kept runs and the voxels).

Only TRELLIS.2's first stage runs: its sparse structure, a 32^3 occupancy of the object's surface, is already twice the
unit's resolution. TRELLIS is unstable on 16 x 16 pixel art (one prompt gives a flat slab, a near one a full cube), so
every cell is prompted in STYLES (grey or tinted, plain or Scale2x-smoothed), a run that comes back as the bare 32^3
shell is dropped, and the target keeps the voxels at least half the kept runs agree on. TRELLIS's frame is z up, its
camera looking along y; which side of y faces the viewer is chosen per run by the south view's match with the drawing.

With --structs it does the dump's buildings instead (§5): the drawing is the template's tile grid, and the target is
the model scaled into the template's box (w x 8 wide, its tallest column high, d x 8 deep), index
(y * depth + z) * width + x.

usage: trellis_targets.py <dump.json> <targets.json> [--structs] [--limit N] [--only KEY ...] [--seed 42]
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time

import numpy as np

W = 16
SS = 32  # TRELLIS.2's sparse structure resolution
GREY = (255, 170, 85, 0)  # Textures.GREY_PALETTE, shade 0 lightest .. 3 darkest
# Tints by shade: foliage (the unit's tree flag) green, anything else a warm stone.
FOLIAGE = ((236, 244, 214), (140, 190, 96), (64, 112, 56), (24, 32, 24))
STONE = ((240, 232, 216), (184, 160, 128), (112, 88, 64), (40, 32, 24))
STYLES = ("grey", "grey-x2", "tint", "tint-x2")
# A run whose occupancy lies mostly on the domain's faces and touches all six is TRELLIS's degenerate shell.
SHELL = 6 * SS * SS - 12 * SS + 8


def bits_decode(text: str, n: int) -> np.ndarray:
    raw = np.frombuffer(base64.b64decode(text), dtype=np.uint8)
    return np.unpackbits(raw, bitorder="little")[:n].astype(bool)


def bits_encode(values: np.ndarray) -> str:
    return base64.b64encode(np.packbits(values.astype(np.uint8).ravel(), bitorder="little").tobytes()).decode("ascii")


def shades_of(cell: dict) -> np.ndarray:
    return np.frombuffer(base64.b64decode(cell["shades"]), dtype=np.int8).reshape(W, W)


def is_tree(cell: dict) -> bool:
    """The planner's foliage hint: the variant's flags (before the first underscore) hold t."""
    return any("t" in v["variant"].split("_")[0][2:] for v in cell["variants"])


def foreground(cell: dict) -> np.ndarray:
    """The thing drawn, W x W: the union of the cell's hull or object foregrounds, else its hull mask, else all drawn."""
    fg = np.zeros(W * W, dtype=bool)
    for v in cell["variants"]:
        if v["kind"] in ("hull", "object"):
            fg |= bits_decode(v["foreground"], W * W)
    if not fg.any() and cell.get("hullMask"):
        fg = bits_decode(cell["hullMask"], W * W)
    if not fg.any():
        fg = shades_of(cell).ravel() >= 0
    return fg.reshape(W, W)


def scale2x(a: np.ndarray) -> np.ndarray:
    """EPX / Scale2x on an H x W x C array (alpha included): edges stay hard, staircases turn into slopes."""
    h, w = a.shape[:2]
    p = np.pad(a, ((1, 1), (1, 1), (0, 0)), mode="edge")
    b, d, f, hh = p[:-2, 1:-1], p[1:-1, :-2], p[1:-1, 2:], p[2:, 1:-1]

    def eq(x, y):
        return np.all(x == y, axis=-1, keepdims=True)

    out = np.zeros((h * 2, w * 2, a.shape[2]), a.dtype)
    out[0::2, 0::2] = np.where(eq(d, b) & ~eq(b, f) & ~eq(d, hh), d, a)
    out[0::2, 1::2] = np.where(eq(b, f) & ~eq(b, d) & ~eq(f, hh), f, a)
    out[1::2, 0::2] = np.where(eq(d, hh) & ~eq(d, b) & ~eq(hh, f), d, a)
    out[1::2, 1::2] = np.where(eq(hh, f) & ~eq(d, hh) & ~eq(b, f), f, a)
    return out


def prompt_image(cell: dict, fg: np.ndarray, style: str, shades: np.ndarray | None = None):
    from PIL import Image

    shades = shades_of(cell) if shades is None else shades
    palette = (FOLIAGE if "variants" in cell and is_tree(cell) else STONE) if style.startswith("tint") else [(g, g, g) for g in GREY]
    rgba = np.zeros(shades.shape + (4,), dtype=np.uint8)
    for s in range(4):
        rgba[shades == s, :3] = palette[s]
    rgba[..., 3] = np.where(fg & (shades >= 0), 255, 0)
    if style.endswith("x2"):
        for _ in range(3):
            rgba = scale2x(rgba)
    h, w = rgba.shape[:2]
    k = 512 / max(h, w)
    img = Image.fromarray(rgba, "RGBA").resize((max(1, round(w * k)), max(1, round(h * k))), Image.NEAREST)
    canvas = Image.new("RGBA", (768, 768), (0, 0, 0, 0))
    canvas.paste(img, ((768 - img.width) // 2, (768 - img.height) // 2))
    return canvas


def struct_drawing(struct: dict) -> np.ndarray:
    """A building's drawing, (d x 8) rows of (w x 8) shades, -1 outside the structure."""
    return np.frombuffer(base64.b64decode(struct["shades"]), dtype=np.int8).reshape(struct["d"] * 8, struct["w"] * 8)


def fill_box(g: np.ndarray, dims) -> np.ndarray:
    """g [x, y, z] cropped to its box and stretched to fill dims (the template's volume)."""
    lo, hi = bbox(g)
    g = g[lo[0]:hi[0], lo[1]:hi[1], lo[2]:hi[2]]
    return resample(g, dims)


def degenerate(occ: np.ndarray) -> bool:
    """TRELLIS's bare shell: (nearly) every voxel on the domain's faces, touching all six."""
    inner = occ[1:-1, 1:-1, 1:-1].sum()
    faces = occ.sum() - inner
    touches = all(occ.take(i, axis=a).any() for a in range(3) for i in (0, -1))
    return touches and faces >= 0.8 * SHELL and inner < 0.05 * occ.sum() + 1


def frames(occ: np.ndarray):
    """occ [x, y, z] in TRELLIS's frame (z up, the camera along y) as [x east, y up, z south], both ways round."""
    up = np.transpose(occ, (0, 2, 1))  # [x, z(up), y]
    yield "front", up[:, :, ::-1]  # camera at -y: image x is +x, -y toward the viewer
    yield "back", up[::-1, :, :]  # camera at +y: image x is -x, +y toward the viewer


def bbox(mask: np.ndarray):
    idx = np.argwhere(mask)
    return idx.min(0), idx.max(0) + 1


def view_iou(g: np.ndarray, fg: np.ndarray) -> float:
    """The south view of g [x, y, z] against the drawing's foreground, both cropped to their boxes."""
    from scipy import ndimage

    view = g.any(axis=2)
    if not view.any():
        return 0.0
    lo, hi = bbox(view)
    rows = np.flipud(view[lo[0]:hi[0], lo[1]:hi[1]].T)
    flo, fhi = bbox(fg)
    want = fg[flo[0]:fhi[0], flo[1]:fhi[1]]
    got = ndimage.zoom(rows.astype(np.float32), [t / s for t, s in zip(want.shape, rows.shape)], order=1) >= 0.5
    got = got[: want.shape[0], : want.shape[1]]
    pad = np.zeros_like(want)
    pad[: got.shape[0], : got.shape[1]] = got
    return float((pad & want).sum() / max(1, (pad | want).sum()))


def place(g: np.ndarray, fg: np.ndarray) -> np.ndarray:
    """g [x, y, z] in the unit: its south view stretched onto the drawing's box, its depth scaled as its width and centred
    in the cell. Returns [x, y, z] booleans W^3."""
    lo, hi = bbox(g)
    g = g[lo[0]:hi[0], lo[1]:hi[1], lo[2]:hi[2]]
    flo, fhi = bbox(fg)
    nx = fhi[1] - flo[1]
    ny = fhi[0] - flo[0]
    nz = max(1, min(W, int(round(g.shape[2] * nx / g.shape[0]))))
    out = resample(g, (nx, ny, nz))
    grid = np.zeros((W, W, W), dtype=bool)
    y0 = W - fhi[0]
    z0 = (W - nz) // 2
    grid[flo[1]:flo[1] + out.shape[0], y0:y0 + out.shape[1], z0:z0 + out.shape[2]] = out
    return grid


def resample(g: np.ndarray, dims) -> np.ndarray:
    """g resized to dims by sampling each target voxel's centre (no voxel lost at the edges, as linear zooms lose them)."""
    idx = [np.minimum((np.arange(n) + 0.5) * m / n, m - 1).astype(int) for n, m in zip(dims, g.shape)]
    return g[np.ix_(*idx)]


def solid(coords: np.ndarray) -> np.ndarray:
    """The sparse structure's surface voxels as a solid SS^3 [x, y, z] (padded so a shell on the faces still closes)."""
    from scipy import ndimage

    occ = np.zeros((SS, SS, SS), dtype=bool)
    occ[coords[:, 0], coords[:, 1], coords[:, 2]] = True
    padded = np.pad(occ, 1)
    return ndimage.binary_fill_holes(padded)[1:-1, 1:-1, 1:-1]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("dump")
    ap.add_argument("targets")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--only", nargs="*", default=None)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--structs", action="store_true")
    args = ap.parse_args()

    os.environ.setdefault("PYTORCH_CUDA_ALLOC_CONF", "expandable_segments:True")
    import torch
    from trellis2.pipelines import Trellis2ImageTo3DPipeline, rembg

    class NoBackgroundRemoval:
        """The prompts carry their own alpha, so the pipeline's background remover (a gated model) never runs."""

        def __init__(self, *args, **kwargs):
            pass

        def to(self, *args, **kwargs):
            return self

        def cpu(self):
            return self

        def __call__(self, image):
            raise RuntimeError("no background removal: every prompt has an alpha channel")

    rembg.BiRefNet = NoBackgroundRemoval

    dump = json.load(open(args.dump, encoding="utf-8"))
    name = "struct" if args.structs else "cell"
    cells = dump.get("structs", []) if args.structs else dump["cells"]
    if args.only:
        cells = [c for c in cells if c[name] in set(args.only)]
    done = {}
    if os.path.exists(args.targets):
        done = json.load(open(args.targets, encoding="utf-8"))
    meta_path = args.targets.replace(".json", "") + ".meta.jsonl"
    todo = [c for c in cells if c[name] not in done]
    if args.limit:
        todo = todo[: args.limit]
    print(f"{dump['game']}: {len(cells)} cells, {len(done)} done, {len(todo)} to do", flush=True)
    if not todo:
        return 0

    pipe = Trellis2ImageTo3DPipeline.from_pretrained("microsoft/TRELLIS.2-4B")
    pipe.cuda()
    t0 = time.time()
    for n, cell in enumerate(todo, 1):
        key = cell[name]
        if args.structs:
            shades = struct_drawing(cell)
            fg = shades >= 0
            dims = (cell["w"] * 8, cell["height"] * 8, cell["d"] * 8)
        else:
            shades = None
            fg = foreground(cell)
        runs = []
        meta = {name: key, "runs": []}
        try:
            for style in STYLES:
                image = pipe.preprocess_image(prompt_image(cell, fg, style, shades))
                torch.manual_seed(args.seed)
                with torch.no_grad():
                    cond = pipe.get_cond([image], 512)
                    coords = pipe.sample_sparse_structure(cond, SS, 1, {})[:, 1:].cpu().numpy()
                surface = np.zeros((SS, SS, SS), dtype=bool)
                surface[coords[:, 0], coords[:, 1], coords[:, 2]] = True
                run = {"style": style, "voxels": int(surface.sum())}
                # Checked before filling: filled, the bare shell would pass for a solid cube.
                if degenerate(surface):
                    run["dropped"] = "shell"
                else:
                    occ = solid(coords)
                    best = max(((view_iou(g, fg), side, g) for side, g in frames(occ)), key=lambda t: t[0])
                    run.update(side=best[1], view_iou=round(best[0], 3))
                    lo, hi = bbox(best[2])
                    run["depth"] = round(float((hi[2] - lo[2]) / max(1, hi[0] - lo[0])), 2)
                    runs.append(fill_box(best[2], dims) if args.structs else place(best[2], fg))
                meta["runs"].append(run)
            if runs:
                vote = np.mean(np.stack(runs).astype(np.float32), axis=0) >= 0.5
                target = np.transpose(vote, (1, 2, 0))  # [y, z, x]: FitTool's order (BuildingFit's too)
                done[key] = bits_encode(target)
                meta.update(kept=len(runs), voxels=int(target.sum()))
            else:
                meta.update(kept=0)
        except Exception as e:  # one bad cell must not stop the run
            meta["error"] = repr(e)[:300]
        meta["seconds"] = round(time.time() - t0, 1)
        with open(meta_path, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(meta) + "\n")
        if n % 10 == 0 or n == len(todo):
            tmp = args.targets + ".tmp"
            json.dump(done, open(tmp, "w", encoding="utf-8"))
            os.replace(tmp, args.targets)
            rate = (time.time() - t0) / n
            print(f"{n}/{len(todo)} {rate:.1f} s/cell, eta {rate * (len(todo) - n) / 60:.0f} min", flush=True)
        torch.cuda.empty_cache()
    return 0


if __name__ == "__main__":
    sys.exit(main())
