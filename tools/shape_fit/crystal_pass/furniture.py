"""Face-aware source paint and small builders for Crystal's interior objects.

Gen 2 draws furniture in a 3/4 view: a sprite's upper rows are usually the object's top surface
and its lower rows its south face. A voxel shows one texel on all its faces; the runtime shows the
texel of its most open face (up, then south, east/west, north). `paint_faces` mirrors that order:

    top    rows of the drawing laid over the object's x/z extent (seen from above)
    front  rows of the drawing stretched over the south face's x/height
    side   a material (exact source colours) for east/west faces: never the outline or the floor
    back   a material for north faces

Coordinates: arrays (y, z, x), x east, y up, z south, 16 voxels a cell, 8 a block.
"""
from __future__ import annotations

import numpy as np

from tools.shape_fit.crystal_pass.common import CellPaint, K, art


class Cell(CellPaint):
    """One key's own drawing: exact-colour picks and rectangle projections."""

    def __init__(self, key):
        super().__init__(art(), K(key))

    def col(self, u, v):
        return tuple(int(c) for c in self.pixels[int(v), int(u)])


def silhouette(cell, background):
    """(16, 16) mask [v, u] of the drawn object: every pixel not reachable from the cell's border
    through `background` colours (floor, grass, wall paint). Interior pixels of those colours that
    the outline encloses stay part of the object."""
    px = cell.pixels
    bg = np.zeros((16, 16), bool)
    for c in background:
        bg |= (px == np.asarray(c)).all(axis=2)
    outside = np.zeros((16, 16), bool)
    stack = [(v, u) for v in range(16) for u in range(16) if (v in (0, 15) or u in (0, 15)) and bg[v, u]]
    for p in stack:
        outside[p] = True
    while stack:
        v, u = stack.pop()
        for dv, du in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            q = (v + dv, u + du)
            if 0 <= q[0] < 16 and 0 <= q[1] < 16 and bg[q] and not outside[q]:
                outside[q] = True
                stack.append(q)
    return ~outside


def footprint(shape, sil):
    """A drawn silhouette as columns: voxel (y, z, x) is kept where pixel (u=x, v=z) is the object."""
    return np.broadcast_to(sil[None, :, :], shape)


def box(shape, x, y, z):
    """Mask of voxels whose centres lie in [x0,x1) x [y0,y1) x [z0,z1)."""
    yy, zz, xx = np.indices(shape)
    return ((xx + .5 >= x[0]) & (xx + .5 < x[1]) & (yy + .5 >= y[0]) & (yy + .5 < y[1])
            & (zz + .5 >= z[0]) & (zz + .5 < z[1]))


def cylinder(shape, center, radius, y):
    yy, zz, xx = np.indices(shape)
    rx, rz = (radius, radius) if np.isscalar(radius) else radius
    return ((((xx + .5 - center[0]) / rx) ** 2 + ((zz + .5 - center[1]) / rz) ** 2 <= 1)
            & (yy + .5 >= y[0]) & (yy + .5 < y[1]))


def ellipsoid(shape, center, radii):
    yy, zz, xx = np.indices(shape)
    cx, cy, cz = center
    rx, ry, rz = radii
    return ((xx + .5 - cx) / rx) ** 2 + ((yy + .5 - cy) / ry) ** 2 + ((zz + .5 - cz) / rz) ** 2 <= 1


def exposed(occ):
    """Per-face exposure masks: up, south, east, west, north, down."""
    p = np.pad(occ, 1)
    c = (slice(1, -1),) * 3
    return {"up": occ & ~p[2:, 1:-1, 1:-1], "down": occ & ~p[:-2, 1:-1, 1:-1],
            "south": occ & ~p[1:-1, 2:, 1:-1], "north": occ & ~p[1:-1, :-2, 1:-1],
            "east": occ & ~p[1:-1, 1:-1, 2:], "west": occ & ~p[1:-1, 1:-1, :-2]}


def top_rect(rect, x, z):
    """(u, v) of the drawing rectangle [u0,v0,u1,v1] laid over x/z, seen from above."""
    u0, v0, u1, v1 = rect

    def at(y, zz, xx):
        u = u0 + (xx + .5 - x[0]) / (x[1] - x[0]) * (u1 - u0)
        v = v0 + (zz + .5 - z[0]) / (z[1] - z[0]) * (v1 - v0)
        return min(u1 - .5, max(u0 + .5, u)), min(v1 - .5, max(v0 + .5, v))
    return at


def front_rect(rect, x, y):
    """(u, v) of the drawing rectangle stretched over a south face spanning x and height y."""
    u0, v0, u1, v1 = rect

    def at(yy, z, xx):
        u = u0 + (xx + .5 - x[0]) / (x[1] - x[0]) * (u1 - u0)
        v = v0 + (y[1] - (yy + .5)) / (y[1] - y[0]) * (v1 - v0)
        return min(u1 - .5, max(u0 + .5, u)), min(v1 - .5, max(v0 + .5, v))
    return at


def side_rect(rect, z, y):
    """(u, v) of a drawing rectangle laid along an east/west face (z across, height down)."""
    u0, v0, u1, v1 = rect

    def at(yy, zz, x):
        u = u0 + (zz + .5 - z[0]) / (z[1] - z[0]) * (u1 - u0)
        v = v0 + (y[1] - (yy + .5)) / (y[1] - y[0]) * (v1 - v0)
        return min(u1 - .5, max(u0 + .5, u)), min(v1 - .5, max(v0 + .5, v))
    return at


def texel(cell, how, y, z, x):
    """One voxel's source address from a projection (callable -> (u, v)), a material (colour
    list, nearest such pixel to the voxel's x/z), or an explicit (u, v) pair."""
    if callable(how):
        u, v = how(y, z, x)
        return int(min(15, max(0, v))) * 16 + int(min(15, max(0, u)))
    if isinstance(how, tuple) and len(how) == 2 and all(isinstance(a, (int, float)) for a in how):
        return int(how[1]) * 16 + int(how[0])
    return cell.pick(how, x + .5, z + .5)


def paint_faces(occ, cell, *, top, front, side, back=None, down=None, region=None, paint=None):
    """Paint `occ` (or only `region` of it) by the runtime's face order."""
    paint = np.zeros(occ.shape, np.uint8) if paint is None else paint
    e = exposed(occ)
    region = occ if region is None else (occ & region)
    order = [("up", top), ("south", front), ("east", side), ("west", side), ("north", back or side),
             ("down", down or side)]
    done = np.zeros(occ.shape, bool)
    for face, how in order:
        for y, z, x in np.argwhere(e[face] & region & ~done):
            paint[y, z, x] = texel(cell, how, y, z, x)
        done |= e[face]
    for y, z, x in np.argwhere(region & ~done):  # hidden voxels: any interior texel of the side material
        paint[y, z, x] = texel(cell, side, y, z, x)
    return paint


class Build:
    """Accumulates parts: each add() paints its own voxels by faces of the final shape."""

    def __init__(self, cell, height=16, depth=16, width=16):
        self.cell = cell
        self.occ = np.zeros((height, depth, width), bool)
        self.parts = []

    def add(self, mask, *, top, front, side, back=None, down=None):
        self.occ |= mask
        self.parts.append((mask, dict(top=top, front=front, side=side, back=back, down=down)))
        return self

    def cut(self, mask):
        self.occ &= ~mask
        self.parts = [(m & ~mask, how) for m, how in self.parts]
        return self

    def done(self):
        paint = np.zeros(self.occ.shape, np.uint8)
        owner = np.full(self.occ.shape, -1)
        for i, (m, _) in enumerate(self.parts):
            owner[m & self.occ] = i
        for i, (m, how) in enumerate(self.parts):
            paint_faces(self.occ, self.cell, region=(owner == i), paint=paint, **how)
        return self.occ.copy(), paint
