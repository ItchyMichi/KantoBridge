"""Shared interior builders for the Crystal pass (furniture repeated across tilesets).

Every builder returns (occ, paint) for one 16x16 cell, painted only from that cell's own drawing.
Sizes are in voxels (8 a block); y up, z south. Heights shared across families:

    table      top surface at y 8 (a 2-voxel slab y 6..8), legs 2x2 at drawn outer corners
    cabinet    the low cabinet under a TV/stereo: y 0..7
    seat       stools keep the seat height of their existing entries (y 4)

Gen 2's tables are drawn with their top across whole cells and, on a solid south row, the top's
front edge and apron mid-cell (rows 8-11) with floor below: `table_piece(apron=...)` ends the top at
the drawn front and paints that face; walkable apron rows are never drawn (census) and stay floor.
"""
from __future__ import annotations

import numpy as np

from tools.shape_fit.crystal_pass import furniture as F
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, front_rect, top_rect

S = (16, 16, 16)
DARK = (58, 58, 58)
WOOD, WOOD_D, WOOD_L = (173, 140, 58), (132, 107, 25), (214, 197, 140)
GREY, GREY_D, CREAM = (156, 156, 156), (107, 107, 107), (247, 230, 214)
TOP = (6, 8)
LEG = [WOOD_D, DARK, WOOD]  # the last for single-colour table tiles


def solid(colour):
    return [colour, DARK]


def table_piece(key, *, edges="", legs="", apron=None, side=LEG, top_rows=(0, 16), slab=TOP, cut=None):
    """One cell of a table: the slab over the cell (inset a voxel at the drawn outer `edges`, a string of
    n/s/w/e), 2x2 legs at the listed `legs` corners (nw, ne, sw, se), and for a solid apron cell
    `apron=(z_front, (row0, row1))`: the top ends at z_front and its south face shows rows row0..row1.
    `slab` is the top's height range (TOP for tables, lower for Japanese low tables); `cut` an optional
    (x0, x1, z0, z1) rectangle left open (the outside of an L-shaped table's inner corner)."""
    c = Cell(key)
    b = Build(c)
    x = (1 if "w" in edges else 0, 15 if "e" in edges else 16)
    z0 = 1 if "n" in edges else 0
    z1 = apron[0] if apron else (15 if "s" in edges else 16)
    top = top_rect((0, 0, 16, 16), (0, 16), (0, 16))
    board = (max(0, slab[0] - 2), slab[0])
    front = front_rect((0, apron[1][0], 16, apron[1][1]), (0, 16), (board[0], slab[1])) if apron else side
    b.add(box(S, x, slab, (z0, z1)), top=top, front=front, side=side)
    if apron:
        b.add(box(S, x, board, (z1 - 1, z1)), top=side, front=front, side=side)  # the apron board under the front edge
    for corner in legs.split():
        lx = x[0] + 1 if "w" in corner else x[1] - 3
        lz = z0 + 1 if "n" in corner else z1 - 3
        b.add(box(S, (lx, lx + 2), (0, slab[0]), (lz, lz + 2)), top=side, front=side, side=side)
    if cut:
        b.cut(box(S, (cut[0], cut[1]), (0, 16), (cut[2], cut[3])))
    return b.done()


def device_on_cabinet(key, *, cabinet_rows, device_rows, device_x=(3, 13), cabinet=(WOOD, WOOD_D),
                      device=(GREY, GREY_D), cab_h=7, device_z=(5, 13), cab_z=(3, 15)):
    """A TV or stereo standing on its low cabinet against the wall: the cabinet's doors from
    `cabinet_rows`, the device's lower body (its face from `device_rows`) up to the top of the cell.
    Its upper body is the stacked wall-row cell above (`device_top`)."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (1, 15), (0, cab_h), cab_z), top=solid(cabinet[0]),
          front=front_rect((1, cabinet_rows[0], 15, cabinet_rows[1]), (1, 15), (0, cab_h)), side=list(cabinet) + [DARK])
    b.add(box(S, device_x, (cab_h, 16), device_z), top=solid(device[0]),
          front=front_rect((device_x[0], device_rows[0], device_x[1], device_rows[1]), device_x, (cab_h, 16)),
          side=list(device) + [DARK])
    return b.done()


def device_top(key, *, rows, device_x=(3, 13), height=5, device=(GREY, GREY_D), device_z=(5, 13), top_rows=None):
    """The upper body of a stacked TV/stereo/monitor (its own cell moves on top of the base): only
    the device, from the bottom of the cell up; the wall-art rows above it are not modelled."""
    c = Cell(key)
    b = Build(c)
    top = top_rect((device_x[0], top_rows[0], device_x[1], top_rows[1]), device_x, device_z) if top_rows else solid(device[0])
    b.add(box(S, device_x, (0, height), device_z), top=top,
          front=front_rect((device_x[0], rows[0], device_x[1], rows[1]), device_x, (0, height)), side=list(device) + [DARK])
    return b.done()


def case_base(key, *, rows, shelf=None, wood=(WOOD, WOOD_D), z=(6, 15), x=(1, 15)):
    """Lower half of a tall cabinet/bookcase standing on the floor: its whole front from `rows`
    over 16 voxels; `shelf` = ((x0, x1), (y0, y1), (row0, row1)) carves an open shelf recess
    whose back shows those rows (the books)."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, x, (0, 16), z), top=solid(wood[0]), front=front_rect((x[0], rows[0], x[1], rows[1]), x, (0, 16)),
          side=list(wood) + [DARK])
    if shelf:
        (sx, sy, sr) = shelf
        b.cut(box(S, sx, sy, (z[1] - 3, z[1])))
        b.add(box(S, sx, sy, (z[1] - 3, z[1] - 2)), top=solid(wood[1]),
              front=front_rect((sx[0], sr[0], sx[1], sr[1]), sx, sy), side=list(wood[::-1]) + [DARK])
    return b.done()


def case_top(key, *, front_rows, top_rows, height=12, shelf=None, wood=(WOOD, WOOD_D), z=(6, 15), x=(1, 15)):
    """Upper half of the same case (stacked on the base): shelf front from `front_rows` over `height`,
    the top surface from `top_rows`."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, x, (0, height), z), top=top_rect((x[0], top_rows[0], x[1], top_rows[1]), x, z),
          front=front_rect((x[0], front_rows[0], x[1], front_rows[1]), x, (0, height)), side=list(wood) + [DARK])
    if shelf:
        (sx, sy, sr) = shelf
        b.cut(box(S, sx, sy, (z[1] - 3, z[1])))
        b.add(box(S, sx, sy, (z[1] - 3, z[1] - 2)), top=solid(wood[1]),
              front=front_rect((sx[0], sr[0], sx[1], sr[1]), sx, sy), side=list(wood[::-1]) + [DARK])
    return b.done()


def round_stool(key, *, background, center=(8, 9), radius=5.5, seat=(2, 4), top_rows=(5, 12), legs_mat=None):
    """A round stool: a cushion disk at the seat height on four short legs; the top shows the drawn
    seat, the floor around it stays floor."""
    c = Cell(key)
    b = Build(c)
    cx, cz = center
    disk = F.cylinder(S, center, radius, seat)
    b.add(disk, top=top_rect((cx - radius, top_rows[0], cx + radius, top_rows[1]), (cx - radius, cx + radius),
                             (cz - radius, cz + radius)), front=[DARK], side=[DARK])
    mat = legs_mat or [DARK]
    for dx, dz in ((-3, -3), (2, -3), (-3, 2), (2, 2)):
        lx, lz = int(cx + dx), int(cz + dz)
        b.add(box(S, (lx, lx + 1), (0, seat[0]), (lz, lz + 1)), top=mat, front=mat, side=mat)
    return b.done()


def paint_global(occ, layout, colour_at, fallback, keys_art=None):
    """Paint an object designed across several cells (occ in global (y, z, x)) from its composite
    drawing: `colour_at(y, z, x, face)` returns the drawing position (u, v) a voxel shows; the voxel
    takes that colour from its own cell nearest the same position (or the `fallback` material).
    Returns {key: (occ_part, paint_part)} for each layout cell."""
    from tools.shape_fit.crystal_pass.common import object_drawing
    big = np.asarray(object_drawing(layout, scale=1).convert("RGB")).astype(int)
    e = F.exposed(occ)
    order = ("up", "south", "east", "west", "north", "down")
    out = {}
    for r, row in enumerate(layout):
        for col, key in enumerate(row):
            if not key or key in out:
                continue
            c = Cell(key)
            z0, x0 = r * 16, col * 16
            part = occ[:, z0:z0 + 16, x0:x0 + 16]
            paint = np.zeros(part.shape, np.uint8)
            for y, z, x in np.argwhere(part):
                gz, gx = z0 + z, x0 + x
                face = next((f for f in order if e[f][y, gz, gx]), "south")
                u, v = colour_at(y, gz, gx, face)
                u = min(big.shape[1] - 1, max(0, int(u)))
                v = min(big.shape[0] - 1, max(0, int(v)))
                want = tuple(int(a) for a in big[v, u])
                paint[y, z, x] = c.pick([want] + list(fallback), u - x0 + .5, v - z0 + .5)
            out[key] = (part.copy(), paint)
    return out


def wall_relief(key, *, background, depth=2, side=None, top=None):
    """A wall-row cell that is wall with carved or mounted art: a full block whose south face shows the drawing,
    and the art's non-`background` pixels standing `depth` voxels proud (a relief, not a cast). Keeps the back
    wall closed where a wall-row cell is drawn in place."""
    c = Cell(key)
    b = Build(c)
    proj = front_rect((0, 0, 16, 16), (0, 16), (0, 16))
    mats = side or [c.col(0, 0)]
    b.add(box(S, (0, 16), (0, 16), (0, 16 - depth)), top=top or mats, front=proj, side=mats)
    bg = {tuple(col) for col in background}
    relief = np.zeros(S, bool)
    for y in range(16):
        for x in range(16):
            u, v = proj(y, 15, x)
            if tuple(int(a) for a in c.pixels[int(v), int(u)]) not in bg:
                relief[y, 16 - depth:, x] = True
    if relief.any():
        b.add(relief, top=proj_top(proj), front=proj, side=proj_side(proj))
    return b.done()


def proj_top(proj):
    """For a relief's up faces: the pixel just above the voxel's own front pixel (its upper edge)."""
    return lambda y, z, x: proj(min(15, y + 1), z, x)


def proj_side(proj):
    return lambda y, z, x: proj(y, z, x)


def statue_pedestal(key, *, stone, front_rows=(0, 16)):
    """A gym statue's pedestal: a stone block (the drawn frame and plaque on its south face) filling the base cell;
    the statue figure stands on it as the stacked top (statue_figure)."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 16), (2, 14)), top=[stone[0]], front=front_rect((0, front_rows[0], 16, front_rows[1]), (1, 15), (0, 16)),
          side=list(stone))
    return b.done()


def statue_figure(key, *, stone, rows=(0, 16)):
    """The statue figure standing on its pedestal (the stacked top): a rounded stone figure, its drawn face to the
    south, plain stone elsewhere."""
    c = Cell(key)
    b = Build(c)
    body = F.ellipsoid(S, (8, 6.5, 8), (6.2, 7, 4.8)) & (np.indices(S)[0] >= 0)
    head = F.ellipsoid(S, (8, 12.5, 8), (4.5, 3.5, 4))
    b.add(body | head, top=[stone[0]], front=front_rect((0, rows[0], 16, rows[1]), (1.8, 14.2), (0, 16)), side=list(stone))
    return b.done()
