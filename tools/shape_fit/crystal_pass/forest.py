"""Crystal checkpoint 24: TILESET_FOREST_1 (Ilex Forest; its headbutt tree also grows in Dragon's Den B1F).

Source reading (study/groups/TILESET_FOREST_1; the Ilex Forest map):

- The big trees that make the forest (247 each): one tree drawn over 2x2 cells, the crown over the north cells and the
  south cells' upper rows, the trunk at the south cells' join (composite rows 24-27, columns 8-22), shadow below. One
  rounded crown over the four cells on a stout trunk, 32 voxels high (two layers: a genuinely tall object, as Johto's
  trees), designed once and painted from the composite; the shadow is not modelled.
- The headbutt tree (156; the same drawing as Johto's one-cell tree in the forest palette): a compact crown on a stub trunk.
- The two gatehouses at the forest's ends (6x6 cells each: three roof rows, three wall rows, the door a warp cell): no
  template claims them, so every cell is drawn as a unit. Each cell's paint can only come from its own drawing, so the
  windows (row 3) can never reach the front face (row 5), and the door's warp frame is two blocks high: every cell becomes
  a full block showing its own drawing (the Kanto gatehouse slices' rule), uniform where the old ones stood 15 or 16.
- The shrine (its upper cell: the roof over its posts; the base cell south of it is walkable, never drawn) and the small
  roofed notice board: one-cell objects. Ledges: a low step, as Johto's and Kanto's.
- Retained: the unplaced cut tree and the shrine's walkable base (never drawn).
"""
from __future__ import annotations

import json
from collections import Counter

import numpy as np

from tools.shape_fit.crystal_pass import furniture as F
from tools.shape_fit.crystal_pass import interiors as I
from tools.shape_fit.crystal_pass.common import K, STUDY, Pass
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, front_rect, side_rect, top_rect

S = (16, 16, 16)
TOPDRAW = top_rect((0, 0, 16, 16), (0, 16), (0, 16))
FULL = front_rect((0, 0, 16, 16), (0, 16), (0, 16))
SIDEDRAW = side_rect((0, 0, 16, 16), (0, 16), (0, 16))
FAMILIES = ["TILESET_FOREST_1"]

BIG_TREE = [[K("3548-3549-3552-3553"), K("3550-3551-3554-3555")], [K("3556-3557-3560-3561"), K("3558-3559-3562-3563")]]
HEADBUTT = K("2138-2139-2142-2143")
SHRINE, BOARD = K("2170-2176-2174-2178"), K("3566-3567-3568-3569")
GATE = [K(k) for k in ("2170-2171-2172-2173", "2171-2171-2173-2173", "2171-2176-2173-2177", "2172-2173-2172-2173",
                       "2173-2173-2173-2173", "2173-2177-2173-2177", "2172-2173-2174-2175", "2173-2173-2175-2175",
                       "2173-2177-2175-2178", "2189-3570-2189-3571", "3570-3570-3571-3570", "3570-3570-3570-3571",
                       "3570-2187-3571-2187", "2189-3570-2189-3570", "3570-3570-3570-3570", "3570-2187-3570-2187",
                       "2189-3570-2190-2191", "3570-3570-2191-2191", "3570-2187-2191-2192")]
LEDGES = {K("2133-2133-1026-1026"): (0, 8, 8, 16), K("2133-2183-1026-1027"): (0, 8, 8, 16), K("2182-2133-1025-1026"): (0, 8, 8, 16),
          K("2182-2133-2182-2133"): (0, 8, 8, 16), K("2133-2183-2133-2183"): (8, 8, 16, 16)}
RETAINED = {K("2270-2271-2272-2273"): "Never placed by the survey (a cut tree no map uses); kept.",
            K("3568-3569-2133-2133"): "The shrine's base, walkable without a walk model: never drawn; kept (the shrine is "
                                      "modelled whole in its upper cell)."}


def own(key, n=4):
    c = Cell(key)
    return [col for col, _ in Counter(tuple(int(a) for a in px) for px in c.pixels.reshape(-1, 3)).most_common(n)]


def union(keys):
    out = []
    for k in keys:
        for col in own(k, 8):
            if col not in out:
                out.append(col)
    return out


def big_tree():
    """The 2x2 tree (global y 0-31, z and x 0-31): a rounded crown (y 8-31) on a stout trunk (x 12-19, z 16-21, y 0-10).
    Up faces show the crown's drawn rows 0-21 from back to front; side faces by height over the same rows; the trunk the
    drawn trunk (rows 24-27, columns 8-22); the crown's underside the shadow."""
    yy, zz, xx = np.indices((32, 32, 32))
    crown = ((xx + .5 - 16) / 15.6) ** 2 + ((yy + .5 - 20) / 11.8) ** 2 + ((zz + .5 - 15.5) / 14.6) ** 2 <= 1
    trunk = ((xx + .5 - 15.5) / 3.8) ** 2 + ((zz + .5 - 18.5) / 2.8) ** 2 <= 1
    trunk &= yy < 11
    occ = crown | trunk

    def colour_at(y, gz, gx, face):
        if trunk[y, gz, gx] and not crown[y, gz, gx]:
            return 8 + (gx + .5 - 11.7) / 7.6 * 14, 24 + (10.5 - y) / 10.5 * 3.9
        # the crown keeps to the drawn crown (columns 3-28; the composite's corners are ground)
        cu = 3 + (gx + .5) / 32 * 26
        if face == "up":
            return cu, 1 + (gz + .5 - .9) / 29.2 * 20
        if face == "down":
            return gx + .5, 30.5
        u = cu if face in ("south", "north") else 3 + (gz + .5) / 32 * 26
        return u, 1 + (31.5 - y) / 23.5 * 20.5
    return I.paint_global(occ, BIG_TREE, colour_at, union([k for row in BIG_TREE for k in row]))


def headbutt_tree():
    """The headbutt tree: a compact crown (drawn rows 0-10) on a stub trunk (rows 11-15), as Johto's one-cell tree."""
    c = Cell(HEADBUTT)
    b = Build(c)
    crown = F.ellipsoid(S, (8, 9.5, 8), (7.2, 6.5, 7))
    b.add(crown, top=top_rect((0, 0, 16, 9), (0.8, 15.2), (1, 15)), front=front_rect((0, 0, 16, 11), (0.8, 15.2), (3, 16)),
          side=side_rect((0, 0, 16, 11), (1, 15), (3, 16)))
    b.add(box(S, (7, 9), (0, 4), (7, 9)), top=own(HEADBUTT), front=front_rect((6, 11, 10, 16), (7, 9), (0, 4)), side=own(HEADBUTT))
    return b.done()


def shrine():
    """The shrine: a dark body between its posts (drawn rows 3-10) on a low plinth (rows 11-14), under a roof with
    upturned corners (rows 0-2)."""
    c = Cell(SHRINE)
    b = Build(c)
    m = own(SHRINE)
    b.add(box(S, (1, 15), (0, 2), (3, 13)), top=m, front=front_rect((1, 13, 15, 15), (1, 15), (0, 2)), side=m)
    b.add(box(S, (2, 14), (2, 11), (4, 12)), top=m, front=front_rect((1, 3, 15, 12), (2, 14), (2, 11)),
          side=side_rect((1, 3, 15, 12), (4, 12), (2, 11)))
    b.add(box(S, (0, 16), (11, 13), (2, 14)), top=top_rect((0, 0, 16, 3), (0, 16), (2, 14)), front=front_rect((0, 0, 16, 3), (0, 16), (11, 13)), side=m)
    b.add(box(S, (3, 13), (13, 15), (5, 11)), top=top_rect((4, 0, 12, 2), (3, 13), (5, 11)), front=front_rect((4, 0, 12, 2), (3, 13), (13, 15)), side=m)
    return b.done()


def board():
    """The small roofed notice board: its roof (drawn rows 0-2), the framed board (rows 3-10), two legs and the box
    between them (rows 11-15)."""
    c = Cell(BOARD)
    b = Build(c)
    m = own(BOARD)
    b.add(box(S, (0, 16), (13, 16), (4, 12)), top=top_rect((0, 0, 16, 3), (0, 16), (4, 12)), front=front_rect((0, 0, 16, 3), (0, 16), (13, 16)), side=m)
    b.add(box(S, (1, 15), (6, 13), (5, 11)), top=m, front=front_rect((1, 3, 15, 11), (1, 15), (6, 13)), side=m)
    for x in ((2, 4), (12, 14)):
        b.add(box(S, x, (0, 6), (6, 10)), top=m, front=front_rect((x[0], 11, x[1], 16), x, (0, 6)), side=m)
    b.add(box(S, (5, 11), (0, 4), (6, 10)), top=m, front=front_rect((5, 12, 11, 16), (5, 11), (0, 4)), side=m)
    return b.done()


def block(key):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 16), (0, 16)), top=TOPDRAW, front=FULL, side=SIDEDRAW)
    return b.done()


def ledge(key, rock):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 5), (0, 16)), top=TOPDRAW, front=front_rect((0, 8, 16, 16), (0, 16), (0, 5)),
          side=side_rect(rock, (0, 16), (0, 5)))
    return b.done()


def main():
    out = Pass("forest")
    census = json.loads((STUDY / "census" / "census.json").read_text(encoding="utf-8"))["keys"]
    for key, (occ, paint) in big_tree().items():
        out.cell(key, occ, paint, label="Ilex big tree (2x2)", family="fo-trees",
                 reason="One rounded crown over its 2x2 cells on a stout trunk, 32 voxels (two layers), painted from its "
                        "composite drawing (247 each).")
    out.entity("crystal_fo_big_tree", BIG_TREE, "Ilex Forest big tree")
    out.cell(HEADBUTT, *headbutt_tree(), label="headbutt tree", family="fo-trees",
             reason="A compact crown on a stub trunk (156), as Johto's one-cell tree.")
    out.cell(SHRINE, *shrine(), label="Ilex shrine", family="fo-shrine",
             reason="The shrine in its upper cell: the body between its posts on a plinth, under a roof (its base cell is walkable).")
    out.cell(BOARD, *board(), label="roofed notice board", family="fo-shrine", reason="The roofed board on two legs with the box between.")
    for key in GATE:
        out.cell(key, *block(key), label="gatehouse cell (no template)", family="fo-gates",
                 reason=f"A cell of the gatehouses no template claims (drawn as units, {census[key]['drawn']}): a full block "
                        f"showing its own drawing (its paint cannot come from other cells, and the door's frame is two blocks).")
    for key, rock in LEDGES.items():
        out.cell(key, *ledge(key, rock), label="ledge", family="fo-ledges", reason="The ground on top of a low step, the rock face south and on its ends.")
    for key, why in RETAINED.items():
        out.keep(key, label="never drawn", family="fo-retained", reason=why)
    out.write(families=FAMILIES)


if __name__ == "__main__":
    main()
