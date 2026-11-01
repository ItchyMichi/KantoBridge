"""Source-local paint picking and whole-object previews for Claude's trial models.

Paint stays a source address, never baked RGB (MODEL_AUTHORING_WORKFLOW section 3):
each voxel names one pixel of its own cell's 16x16 drawing. A material is a list of
exact source colours in preference order; among that cell's pixels of the first
colour present, the one nearest the voxel's projected drawing position is chosen,
so a texture pack that repaints the drawing repaints the matching part. A material
whose colours are absent from a cell is an authoring error, never a silent fallback.
"""
from __future__ import annotations

from pathlib import Path

import numpy as np

from tools.shape_fit.blue_pass.common import decode, encode


class CellPaint:
    """Exact-colour texel lookup within one cell's own drawing."""

    def __init__(self, art, key: str):
        self.key = key
        self.pixels = np.asarray(art.cell(key, "BAKED").convert("RGB")).astype(int)

    def has(self, colour) -> bool:
        return bool((self.pixels == np.asarray(colour)).all(axis=2).any())

    def pick(self, colours, u: float, v: float) -> int:
        for colour in colours:
            rows, cols = np.nonzero((self.pixels == np.asarray(colour)).all(axis=2))
            if len(rows):
                i = int(np.argmin((cols + .5 - u) ** 2 + (rows + .5 - v) ** 2))
                return int(rows[i]) * 16 + int(cols[i])
        raise ValueError(f"{self.key}: none of {colours} occurs in this cell's drawing")


def slice_object(occ, material, materials, layout, art, originals, source, hint):
    """Cut one globally designed object into its layout cells.

    occ/material are (height, 16*rows, 16*cols) arrays indexed (y, z, x). `materials`
    maps a material code to its colour list; `hint(y, z, x)` gives the global drawing
    position (u, v) whose pixel the voxel represents. Interaction metadata of each
    original entry is preserved by encode().
    """
    entries = {}
    for row, keys in enumerate(layout):
        for col, key in enumerate(keys):
            if key is None or key in entries:
                continue
            z0, x0 = row * 16, col * 16
            part = occ[:, z0:z0 + 16, x0:x0 + 16]
            mats = material[:, z0:z0 + 16, x0:x0 + 16]
            paint = np.zeros(part.shape, np.uint8)
            cell = CellPaint(art, key)
            for y, z, x in np.argwhere(part):
                u, v = hint(y, z0 + z, x0 + x)
                paint[y, z, x] = cell.pick(materials[int(mats[y, z, x])], u - x0, v - z0)
            entries[key] = encode(part, paint, originals[key], source=source, trim=True)
    return entries


def stack_object(occ, material, materials, keys_bottom_up, art, originals, source, hint):
    """Cut one object designed as a single column into vertical 16-voxel stack pieces."""
    entries = {}
    for level, key in enumerate(keys_bottom_up):
        part = occ[level * 16:(level + 1) * 16]
        mats = material[level * 16:(level + 1) * 16]
        paint = np.zeros(part.shape, np.uint8)
        cell = CellPaint(art, key)
        for y, z, x in np.argwhere(part):
            u, v = hint(level * 16 + y, z, x)
            paint[y, z, x] = cell.pick(materials[int(mats[y, z, x])], u, v)
        entries[key] = encode(part, paint, originals[key], source=source, trim=True)
    return entries


def assemble(entries, layout, art, stack=False):
    """Global (occ, rgb) of a complete object from fitted entries, for local previews."""
    blocks = []
    if stack:
        for key in layout:
            occ, paint = decode(entries[key])
            occ, paint = occ[:16], paint[:16]
            art_px = np.asarray(art.cell(key, "BAKED").convert("RGB"))
            blocks.append((occ, art_px[paint // 16, paint % 16]))
        return (np.concatenate([b[0] for b in blocks]), np.concatenate([b[1] for b in blocks]))
    height = max(len(decode(entries[k])[0]) for row in layout for k in row if k)
    rows, cols = len(layout), len(layout[0])
    occ = np.zeros((height, rows * 16, cols * 16), bool)
    rgb = np.zeros((height, rows * 16, cols * 16, 3), np.uint8)
    for r, keys in enumerate(layout):
        for c, key in enumerate(keys):
            if key is None:
                continue
            o, p = decode(entries[key])
            art_px = np.asarray(art.cell(key, "BAKED").convert("RGB"))
            occ[:len(o), r * 16:(r + 1) * 16, c * 16:(c + 1) * 16] = o
            rgb[:len(o), r * 16:(r + 1) * 16, c * 16:(c + 1) * 16] = art_px[p // 16, p % 16]
    return occ, rgb


def previews(occ, rgb, out: Path, prefix: str, scale=8):
    from tools.shape_fit.hand_check import front, picture
    out.mkdir(parents=True, exist_ok=True)
    picture(occ, rgb, out / f"{prefix}-angled.png", scale)
    front(occ, rgb, out / f"{prefix}-front.png", scale)
    front(occ.transpose(0, 2, 1), rgb.transpose(0, 2, 1, 3), out / f"{prefix}-side.png", scale)
