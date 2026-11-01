"""Small geometry/encoding helpers for the individually reviewed Blue model pass.

This module does not classify sprites or decide which models to replace. Each
authoring module explicitly selects its keys and gives them semantic geometry.
Coordinates are voxels, x east, y up, z south. One cell is 16x16 horizontally.
"""
from __future__ import annotations

import base64
import copy
import importlib.util
import json
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[3]
BASELINE = ROOT / "local/model-gallery/blue-rollout-baseline.json"
TABLE = ROOT / "fabric-bridge/src/main/resources/kantobridge/fitted_shapes/blue.json"


def load_baseline():
    return json.loads(BASELINE.read_text(encoding="utf-8"))


def uv(u, v):
    return int(v) * 16 + int(u)


def decode(entry):
    layers = [entry, *entry.get("up", [])]
    occ = np.concatenate([np.unpackbits(np.frombuffer(base64.b64decode(p["v"]), np.uint8),
                                       bitorder="little").reshape(16, 16, 16).astype(bool) for p in layers])
    default = np.broadcast_to((np.arange(15, -1, -1)[:, None, None] * 16
                              + np.arange(16)[None, None, :]), (16, 16, 16)).astype(np.uint8)
    paint = np.concatenate([np.frombuffer(base64.b64decode(p["t"]), np.uint8).reshape(16, 16, 16)
                            if p.get("t") else default.copy() for p in layers])
    return occ, paint


def encode(occ, paint, original, source="hand-blue-review", trim=False):
    """Preserve interaction/stack flags; replace geometry and its source addresses."""
    occ, paint = np.asarray(occ, bool), np.asarray(paint, np.uint8)
    if occ.shape != paint.shape or occ.shape[1:] != (16, 16):
        raise ValueError("one output cell requires matching Hx16x16 arrays")
    if not occ.any():
        raise ValueError("reviewed models must not silently become empty fallbacks")
    height = occ.shape[0]
    if height > 240:
        raise ValueError("model exceeds the runtime layer limit")
    layers = []
    for start in range(0, height, 16):
        o, p = np.zeros((16, 16, 16), bool), np.zeros((16, 16, 16), np.uint8)
        n = min(16, height - start)
        o[:n], p[:n] = occ[start:start+n], paint[start:start+n]
        layers.append({"v": base64.b64encode(np.packbits(o.ravel(), bitorder="little").tobytes()).decode(),
                       "t": base64.b64encode(p.tobytes()).decode()})
    if trim:
        while len(layers) > 1 and not any(base64.b64decode(layers[-1]["v"])):
            layers.pop()
    result = copy.deepcopy(original)
    result.update({"class": "voxels", "from": source, **layers[0]})
    if len(layers) > 1:
        result["up"] = layers[1:]
    else:
        result.pop("up", None)
    return result


class Model:
    """Explicit boxes, rounded parts and cuts, with per-voxel source-pixel paint."""
    def __init__(self, height=16, width=16, depth=16):
        self.occ = np.zeros((height, depth, width), bool)
        self.paint = np.zeros(self.occ.shape, np.uint8)

    def put(self, mask, texel=0, cut=False):
        self.occ[mask] = not cut
        if not cut:
            self.paint[mask] = texel
        return self

    def box(self, x, y, z, texel=0, cut=False):
        yy, zz, xx = np.indices(self.occ.shape)
        mask = ((xx + .5 >= x[0]) & (xx + .5 < x[1]) & (yy + .5 >= y[0])
                & (yy + .5 < y[1]) & (zz + .5 >= z[0]) & (zz + .5 < z[1]))
        return self.put(mask, texel, cut)

    def ellipsoid(self, center, radii, texel=0, cut=False):
        yy, zz, xx = np.indices(self.occ.shape)
        cx, cy, cz = center
        rx, ry, rz = radii
        mask = ((xx + .5 - cx) / rx) ** 2 + ((yy + .5 - cy) / ry) ** 2 + ((zz + .5 - cz) / rz) ** 2 <= 1
        return self.put(mask, texel, cut)

    def cylinder(self, center, radius, y, texel=0, cut=False):
        yy, zz, xx = np.indices(self.occ.shape)
        rx, rz = (radius, radius) if isinstance(radius, (int, float)) else radius
        mask = (((xx + .5 - center[0]) / rx) ** 2 + ((zz + .5 - center[1]) / rz) ** 2 <= 1)
        mask &= (yy + .5 >= y[0]) & (yy + .5 < y[1])
        return self.put(mask, texel, cut)

    def project(self, front=(0, 0, 16, 16), *, side=None, top=None, back=None):
        """Front art at native proportions; optional interior surface addresses.

        Use on one completed part/model when appropriate. Fine details can be
        added afterwards. A texel is shared by all faces of one runtime voxel.
        """
        h, d, w = self.occ.shape
        yy, zz, xx = np.indices(self.occ.shape)
        u0, v0, u1, v1 = front
        u = np.minimum(u1-1, u0 + xx * (u1-u0) // w)
        v = np.minimum(v1-1, v0 + (h-1-yy) * (v1-v0) // h)
        self.paint[self.occ] = (v * 16 + u)[self.occ]
        for axis, shift, texel in ((2, 1, side), (2, -1, side), (1, 1, back), (0, -1, top)):
            if texel is None:
                continue
            neighbour = np.roll(self.occ, shift, axis=axis)
            slices = [slice(None)] * 3
            slices[axis] = 0 if shift == 1 else -1
            neighbour[tuple(slices)] = False
            self.paint[self.occ & ~neighbour] = texel
        return self

    def entry(self, original, source="hand-blue-review", trim=False):
        return encode(self.occ, self.paint, original, source, trim)


def shade_uv(shades, shade, rect=(0, 0, 16, 16), near=(8, 8)):
    """Pick a source address, never a baked colour. Prefer the requested region."""
    x0, y0, x1, y1 = rect
    choices = [(abs(int(shades[y, x])-shade), (x-near[0])**2+(y-near[1])**2, y, x)
               for y in range(y0, y1) for x in range(x0, x1)]
    _, _, y, x = min(choices)
    return uv(x, y)


def source_shades(art, key):
    atlas, ids = key.rsplit("/", 1)
    ids = list(map(int, ids.split("-")))
    sheet = np.asarray(art.sheet(atlas))
    per = art.by_atlas[atlas]["tilesPerRow"]
    cell = np.zeros((16, 16), np.uint8)
    for n, tile in enumerate(ids):
        x, y = tile % per * 8, tile // per * 8
        cell[n//2*8:n//2*8+8, n%2*8:n%2*8+8] = sheet[y:y+8, x:x+8]
    return np.clip((255-cell.astype(int)+42)//85, 0, 3).astype(np.uint8)


def render(entry, sprite, out):
    """Local modelling previews; final evidence always comes from Minecraft."""
    spec = importlib.util.spec_from_file_location("hand_check", ROOT / "tools/shape_fit/hand_check.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    occ, texels = decode(entry)
    art = np.asarray(sprite.convert("RGB"))
    colors = art[texels // 16, texels % 16]
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    module.picture(occ, colors, out / "angled.png", 10)
    module.front(occ, colors, out / "front.png", 20)
    module.front(occ.transpose(0, 2, 1), colors.transpose(0, 2, 1, 3), out / "side.png", 20)
