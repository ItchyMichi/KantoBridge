"""Shared furniture builders for the Claude continuation (Battle Frontier and similar rooms).

Each builder returns (occupancy, paint) for one 16-wide cell column (y, z, x), front facing
south (+z). Paint is always an address in the given cell's own drawing (CellPaint): projection
of the drawing where it shows the part, exact source colours otherwise. Callers slice or
offset these into their cells; nothing here decides which keys use them.
"""
from __future__ import annotations

import numpy as np


def faces(occ):
    pad = np.pad(occ, 1)
    up = ~pad[2:, 1:-1, 1:-1]
    ox = ~pad[1:-1, 1:-1, :-2] | ~pad[1:-1, 1:-1, 2:]
    oz = ~pad[1:-1, :-2, 1:-1] | ~pad[1:-1, 2:, 1:-1]
    return up, ox, oz


def table(cell, height=16, h=16, legs=True, front_rows=(0, 15)):
    """A wooden table (or counter) filling the cell: top slab, apron and four legs.
    Front and sides by projection of the drawing rows front_rows (top..bottom); top by the
    drawing seen from above."""
    yy, zz, xx = np.indices((h, 16, 16))
    top = (yy >= height - 3) & (yy < height)
    apron = (yy >= height - 6) & (yy < height) & ((xx < 2) | (xx >= 14) | (zz < 2) | (zz >= 14))
    leg = ((xx < 3) | (xx >= 13)) & ((zz < 3) | (zz >= 13)) & (yy < height) if legs else (yy < height)
    occ = top | apron | leg
    up, ox, oz = faces(occ)
    r0, r1 = front_rows
    v = (r1 - (yy * (r1 - r0 + 1)) // height).clip(r0, r1)
    side_u = np.where(ox & ~oz, zz, xx)
    paint = np.where(up & (yy == height - 1), zz * 16 + xx, v * 16 + side_u)
    return occ, paint.astype(np.uint8)


def bonsai(cell, base=0, h=16, colours=None):
    """A bonsai in a stone tray standing on y=base: tray, soil, short trunk and three leaf pads."""
    c = colours
    yy, zz, xx = np.indices((h, 16, 16))
    px, py, pz = xx + .5, yy + .5, zz + .5
    tray = (xx >= 2) & (xx < 14) & (zz >= 4) & (zz < 13) & (yy >= base) & (yy < base + 3)
    inner = (xx >= 3) & (xx < 13) & (zz >= 5) & (zz < 12) & (yy == base + 2)
    trunk = (np.hypot(px - 7.5 - (yy - base - 3) * .15, pz - 8.5) < 1.1) & (yy >= base + 2) & (yy < base + 9)
    pads = np.zeros_like(tray)
    for cx, cy, cz, rx, ry, rz in ((6.0, base + 9.5, 8.5, 3.6, 1.8, 3.0), (10.0, base + 8.0, 8.0, 3.0, 1.6, 2.6),
                                   (8.5, base + 11.5, 9.0, 2.8, 1.6, 2.4)):
        pads |= ((px - cx) / rx) ** 2 + ((py - cy) / ry) ** 2 + ((pz - cz) / rz) ** 2 <= 1
    occ = tray | trunk | pads
    paint = np.zeros(occ.shape, np.uint8)
    up, ox, oz = faces(occ)
    for y, z, x in np.argwhere(occ):
        if pads[y, z, x]:
            role = c["leaf_light"] if up[y, z, x] and (x + z + y) % 3 else c["leaf"]
        elif trunk[y, z, x]:
            role = c["trunk"]
        elif inner[y, z, x] and up[y, z, x]:
            role = c["soil"]
        else:
            role = c["stone_light"] if up[y, z, x] else c["stone"]
        paint[y, z, x] = cell.pick(role, x + .5, z + .5)
    return occ, paint


def framed_screen(cell, base=0, h=16, rows=(0, 13), x0=1, x1=15, frame=None):
    """A screen in a frame, 5 deep, standing on y=base; its face by projection of drawing rows."""
    yy, zz, xx = np.indices((h, 16, 16))
    top_y = min(h, base + 16)
    body = (xx >= x0) & (xx < x1) & (zz >= 6) & (zz < 11) & (yy >= base) & (yy < top_y)
    occ = body & ~((xx >= x0 + 2) & (xx < x1 - 2) & (zz >= 10) & (yy >= base + 2) & (yy < top_y - 2))
    up, ox, oz = faces(occ)
    r0, r1 = rows
    v = (r1 - ((yy - base) * (r1 - r0 + 1)) // max(1, top_y - base)).clip(r0, r1)
    paint = (v * 16 + xx).astype(np.uint8)
    if frame:
        paint[occ & ~oz] = cell.pick(frame, 8, 1)
    return occ, paint


def cushion(cell, height=4):
    """A floor cushion/mat: its top is the drawing seen from above."""
    yy, zz, xx = np.indices((16, 16, 16))
    occ = (xx >= 1) & (xx < 15) & (zz >= 1) & (zz < 15) & (yy < height)
    paint = (zz * 16 + xx).astype(np.uint8)
    return occ, paint


def not_floor(cell, floor):
    """Per drawing pixel (row, col): not one of the room's floor colours."""
    px = cell.pixels
    return ~np.array([[tuple(px[v, u]) in floor for u in range(16)] for v in range(16)])


def mask_counter(cell, floor, height=12, h=16):
    """A counter/desk standing on exactly the drawing's non-floor footprint: flat top from above,
    fronts and sides by that column's drawn colours down its height (no depth noise)."""
    yy, zz, xx = np.indices((h, 16, 16))
    foot = not_floor(cell, floor)
    occ = foot[zz, xx] & (yy < height)
    up, ox, oz = faces(occ)
    lowest = np.array([max([v for v in range(16) if foot[v, u]] or [15]) for u in range(16)])
    v_side = np.clip(lowest[xx] - (height - 1 - yy) // 3, 0, 15)
    paint = np.where(up, zz * 16 + xx, v_side * 16 + xx)
    return occ, paint.astype(np.uint8)


def stanchions(cell, floor, post, rope, h=16):
    """Rope barrier: a round post wherever the drawing shows a post shaft (a vertical run of post colour), a red rope
    sagging between posts at about two-thirds height where the drawing shows rope."""
    px = cell.pixels
    yy, zz, xx = np.indices((h, 16, 16))
    def run(u):                                    # the longest vertical run of post colour: a shaft, not a clasp or flange
        best = cur = 0
        for v in range(16):
            cur = cur + 1 if tuple(px[v, u]) in post else 0
            best = max(best, cur)
        return best
    cols = [u for u in range(16) if run(u) >= 6]
    occ = np.zeros((h, 16, 16), bool)
    paint = np.zeros((h, 16, 16), np.uint8)
    groups = []
    for u in cols:
        if groups and u - groups[-1][-1] <= 3:
            groups[-1].append(u)
        else:
            groups.append([u])
    centres = [sum(g) / len(g) + .5 for g in groups]
    for c in centres:
        p = (np.hypot(xx + .5 - c, zz + .5 - 8) < 1.3) & (yy < 13)
        base = (np.hypot(xx + .5 - c, zz + .5 - 8) < 2.4) & (yy < 1)
        top = (np.hypot(xx + .5 - c, zz + .5 - 8) < 1.8) & (yy == 13)
        occ |= p | base | top
        paint[p | base | top] = cell.pick(post, c, 8)
    rope_cols = [u for u in range(16) if any(tuple(px[v, u]) in rope for v in range(16))]
    for u in rope_cols:
        if any(abs(u + .5 - c) < 1.5 for c in centres):
            continue
        near = min((abs(u + .5 - c) for c in centres), default=8)
        y = int(round(11 - min(near, 6) * .35))
        occ[y, 8, u] = True
        paint[y, 8, u] = cell.pick(rope, u + .5, 8)
    return occ, paint


def palm(cell, leaf, leaf_dark, h=16):
    """Palm fronds radiating from a short crown, the top of a potted palm (a stack top)."""
    yy, zz, xx = np.indices((h, 16, 16))
    occ = (np.hypot(xx + .5 - 8, zz + .5 - 8) < 1.6) & (yy < 4)
    paint = np.zeros((h, 16, 16), np.uint8)
    import math
    for i in range(7):
        a = i * 2 * math.pi / 7 + .3
        for s in range(14):
            r = s * .55
            x, z, y = 8 + math.cos(a) * r, 8 + math.sin(a) * r, 3 + s * .9 - (s / 6) ** 2 * 1.6
            if 0 <= x < 16 and 0 <= z < 16 and 0 <= y < h:
                occ[int(y), int(z), int(x)] = True
    up, ox, oz = faces(occ)
    for y, z, x in np.argwhere(occ):
        paint[y, z, x] = cell.pick(leaf if up[y, z, x] else leaf_dark, x + .5, z + .5)
    return occ, paint


def pot(cell, body, rim, soil, h=16, height=9):
    """A round pot with a rim and soil, the base of a potted plant."""
    yy, zz, xx = np.indices((h, 16, 16))
    r = np.hypot(xx + .5 - 8, zz + .5 - 8)
    body_m = (r < 4.2 + yy * .12) & (yy < height)
    rim_m = (r < 5.6) & (yy >= height - 2) & (yy < height)
    occ = body_m | rim_m
    paint = np.zeros((h, 16, 16), np.uint8)
    up, ox, oz = faces(occ)
    for y, z, x in np.argwhere(occ):
        role = soil if (up[y, z, x] and r[y, z, x] < 4.2) else (rim if rim_m[y, z, x] else body)
        paint[y, z, x] = cell.pick(role, x + .5, 8)
    return occ, paint


def sofa(cell, colours, back_side, h=16, arms_at=("start", "end")):
    """A padded seat (sofa or armchair) facing away from back_side ('n', 'e' or 'w'): seat 6 high,
    back 11 and arms 9 high, painted from the drawing's cushion colours. arms_at limits the arms to
    the run's start/end so neighbouring seat cells join into one sofa or bench."""
    yy, zz, xx = np.indices((h, 16, 16))
    run, cross = (xx, zz) if back_side == "n" else (zz, xx)
    lo = 0 if "start" not in arms_at else (1 if back_side == "n" else 2)       # an end without an arm runs to the edge
    hi = 16 if "end" not in arms_at else 15
    across = (cross >= (2 if back_side == "n" else 1)) & (cross < 15)
    body = (run >= lo) & (run < hi) & across
    seat = body & (yy < 6)
    back = body & {"n": zz < 5, "e": xx >= 11, "w": xx < 5}[back_side] & (yy < 11)
    arms = body & (yy < 9) & ((("start" in arms_at) & (run < lo + 2)) | (("end" in arms_at) & (run >= hi - 2)))
    occ = seat | back | arms
    paint = np.zeros((h, 16, 16), np.uint8)
    up, ox, oz = faces(occ)
    for y, z, x in np.argwhere(occ):
        paint[y, z, x] = cell.pick(colours[0] if up[y, z, x] else colours[1], x + .5, z + .5)
    return occ, paint


def low_table(cell, top, edge, height=7, h=16):
    """A low table filling the cell: slab top from above, edge colour on its sides, four short legs."""
    yy, zz, xx = np.indices((h, 16, 16))
    slab = (yy >= height - 2) & (yy < height)
    legs = ((xx < 2) | (xx >= 14)) & ((zz < 2) | (zz >= 14)) & (yy < height)
    occ = slab | legs
    up, ox, oz = faces(occ)
    paint = np.where(up, zz * 16 + xx, cell.pick(edge, 8, 15)).astype(np.uint8)
    return occ, paint


def box(cell, height, inset=0, h=16):
    """A plain box (cabinet, planter box, block): top from above, sides by the drawing rows."""
    yy, zz, xx = np.indices((h, 16, 16))
    occ = (xx >= inset) & (xx < 16 - inset) & (zz >= inset) & (zz < 16 - inset) & (yy < height)
    up, ox, oz = faces(occ)
    v = (15 - yy * 16 // max(1, height)).clip(0, 15)
    paint = np.where(up, zz * 16 + xx, v * 16 + np.where(ox & ~oz, zz, xx))
    return occ, paint.astype(np.uint8)
