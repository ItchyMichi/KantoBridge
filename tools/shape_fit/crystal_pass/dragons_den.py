"""Crystal checkpoint 21: TILESET_JOHTO_4 (the Dragon's Den B1F).

Source reading (study/groups/TILESET_JOHTO_4; composite _dd_shrine):

- The shrine on the island: a small building drawn as cells (two rows of dark roof slats over its front wall; the door
  cell is not a fitted key). Designed as one object over its 4x3 cells (walls eight voxels high, a gabled roof with its
  ridge running east-west, rising to the cell's full height) and painted from its composite drawing.
- The white fence round the enclosure: east-west runs (posts and two rails) and north-south runs (a post column at the
  drawn side), their ends.
- The 2x2 rock formations in the water (15 each): one rounded mound over the four cells, painted from its composite.
- The rough rock edge in the water (35): a low rough slab showing its drawing. The sign: its board on two legs.
"""
from __future__ import annotations

from collections import Counter

import numpy as np

from tools.shape_fit.crystal_pass import furniture as F
from tools.shape_fit.crystal_pass import interiors as I
from tools.shape_fit.crystal_pass.common import K, Pass
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, front_rect, top_rect

S = (16, 16, 16)
DARK = I.DARK
TOPDRAW = top_rect((0, 0, 16, 16), (0, 16), (0, 16))
FULL = front_rect((0, 0, 16, 16), (0, 16), (0, 16))
FAMILY = "TILESET_JOHTO_4"

SHRINE = [[K("2170-2238-2172-2238"), K("2238-2238-2238-2238"), K("2238-2238-2238-2238"), K("2238-2176-2238-2177")],
          [K("2172-2238-2174-2175"), K("2238-2238-2175-2175"), K("2238-2238-2175-2175"), K("2238-2177-2175-2178")],
          [K("2189-971-2245-2246"), K("2247-2248-2249-2250"), K("971-971-2246-2246"), K("2242-2187-2246-2251")]]
SHRINE_DOOR = K("2247-2248-2249-2250")  # not a fitted key (the door cell)
ROCK = [[K("2179-2180-2182-1009"), K("2180-2181-1009-2183")], [K("2182-1009-1025-1026"), K("1009-2183-1026-1027")]]
FENCE_EW = {K("2185-2185-2186-2186"): None, K("2185-2185-2186-2262"): "e", K("2185-2185-2262-2186"): "w"}
FENCE_NS = {K("2262-2132-2262-2132"): "w", K("2132-2262-2132-2262"): "e"}
ROCK_EDGE = K("2155-2155-2155-2155")
SIGN = K("2264-2265-2266-2267")


def own(key, n=4):
    c = Cell(key)
    return [col for col, _ in Counter(tuple(int(a) for a in px) for px in c.pixels.reshape(-1, 3)).most_common(n)]


def union(keys):
    """The colours of an object's cells (the fallback paint of a multi-cell object: each cell picks the ones it has)."""
    out = []
    for k in keys:
        for col in own(k, 8):
            if col not in out:
                out.append(col)
    return out


def shrine():
    """The shrine over its 4x3 cells (global y, z 0-47, x 0-63): walls eight high round z 8-47, a gabled roof over
    z 2-47 with its ridge along z 20 rising to 16; tops painted from the drawn roof, the south face from the front wall."""
    yy, zz, xx = np.indices((16, 48, 64))
    zc, xc = zz + .5, xx + .5
    walls = (yy < 8) & (zc > 8) & (zc < 47) & (xc > 1) & (xc < 63)
    ridge = 20.0
    roof_h = np.where(zc < ridge, 8 + 8 * (zc - 2) / (ridge - 2), 16 - 8 * (zc - ridge) / (45 - ridge))
    roof = (yy >= 7) & (yy < roof_h) & (zc > 2) & (zc < 45) & (xc > 0) & (xc < 64)  # the south eave over the front wall
    occ = walls | roof
    occ[:, 32:, 16:32] &= np.indices((16, 16, 16))[0] >= 8  # the door cell (not fitted) stays out of the wall's carriers

    def colour_at(y, gz, gx, face):
        if face == "up":
            # the roof's tops show the drawn roof (composite rows 0-31) spread over its depth (z 2-45)
            return gx + .5, (gz + .5 - 2) / 43 * 31 if y >= 7 else gz + .5
        return gx + .5, 32 + (16 - y - .5)
    parts = I.paint_global(occ, SHRINE, colour_at, union([k for row in SHRINE for k in row if k != SHRINE_DOOR]))
    parts.pop(SHRINE_DOOR, None)
    return parts


def rock():
    yy, zz, xx = np.indices((16, 32, 32))
    occ = F.ellipsoid((16, 32, 32), (16, 1, 16), (15.5, 14, 15.5)) & (yy >= 0)

    def colour_at(y, gz, gx, face):
        return (gx + .5, gz + .5) if face == "up" else (gx + .5, min(31.5, gz + .5 + (14 - y) * .4))
    return I.paint_global(occ, ROCK, colour_at, union([k for row in ROCK for k in row]))


def fence_ew(key, end):
    """An east-west fence run: posts (drawn columns 1-3 and 12-14) and two rails, ten voxels high."""
    c = Cell(key)
    b = Build(c)
    m = own(key)
    for x in ((1, 4), (12, 15)):
        b.add(box(S, x, (0, 10), (7, 10)), top=m, front=front_rect((x[0], 0, x[1], 16), x, (0, 10)), side=m)
    for y in ((7, 9), (3, 5)):
        x0 = 0 if end != "w" else 1
        x1 = 16 if end != "e" else 15
        b.add(box(S, (x0, x1), y, (8, 9)), top=m, front=FULL, side=m)
    return b.done()


def fence_ns(key, side):
    """A north-south fence run: the post column along the drawn side (columns 0-4 west, 11-15 east) with its rails."""
    c = Cell(key)
    b = Build(c)
    m = own(key)
    x = (1, 3) if side == "w" else (13, 15)
    b.add(box(S, x, (0, 10), (0, 16)), top=TOPDRAW, front=m, side=front_rect((x[0], 0, x[1], 16), x, (0, 10)))
    return b.done()


def rock_edge():
    c = Cell(ROCK_EDGE)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 5), (0, 16)), top=TOPDRAW, front=own(ROCK_EDGE), side=own(ROCK_EDGE))
    return b.done()


def sign():
    """The sign: its board (drawn rows 1-10) on two short legs."""
    c = Cell(SIGN)
    b = Build(c)
    m = own(SIGN)
    b.add(box(S, (1, 15), (5, 15), (7, 9)), top=m, front=front_rect((1, 1, 15, 11), (1, 15), (5, 15)), side=m)
    for x in ((3, 5), (11, 13)):
        b.add(box(S, x, (0, 5), (7, 9)), top=m, front=m, side=m)
    return b.done()


def main():
    out = Pass("dragons-den")
    for key, (occ, paint) in shrine().items():
        out.cell(key, occ, paint, label="Dragon's Den shrine", family="dd-shrine",
                 reason="Part of the shrine designed over its 4x3 cells (walls, a gabled roof) and painted from its drawing.")
    out.entity("crystal_dd_shrine", [[k if k != SHRINE_DOOR else None for k in row] for row in SHRINE],
               "Dragon's Den shrine (the door cell is not fitted)")
    for key, (occ, paint) in rock().items():
        out.cell(key, occ, paint, label="rock formation (2x2)", family="dd-rocks",
                 reason="One rounded rock mound designed over its 2x2 cells, painted from its composite drawing.")
    out.entity("crystal_dd_rock", ROCK, "Dragon's Den rock formation")
    for key, end in FENCE_EW.items():
        out.cell(key, *fence_ew(key, end), label="fence (east-west)", family="dd-fence",
                 reason="An east-west fence run: posts and two rails, ten voxels high.")
    for key, side in FENCE_NS.items():
        out.cell(key, *fence_ns(key, side), label="fence (north-south)", family="dd-fence",
                 reason="A north-south fence run: the post column along the drawn side.")
    out.cell(ROCK_EDGE, *rock_edge(), label="rough rock edge", family="dd-rocks", reason="A low rough rock slab showing its drawing.")
    out.cell(SIGN, *sign(), label="sign", family="dd-shrine", reason="The sign's board on two short legs.")
    out.write(families=[FAMILY])


if __name__ == "__main__":
    main()
