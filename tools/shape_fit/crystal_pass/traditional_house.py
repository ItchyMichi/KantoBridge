"""Crystal checkpoint 3: TILESET_TRADITIONAL_HOUSE_1 (Ecruteak and Violet houses, Kurt's house, the Dance Theater,
the Wise Trio's room, Mahogany's shop and Mt Moon's gift shop, the Route 39 barn).

Source reading (study/groups/TILESET_TRADITIONAL_HOUSE_1, the census, the maps):

- Never drawn (census, walkable): the stage edge and floor strips of the Dance Theater and the walkable apron
  rows in front of the low tables.
- Low tables (chabudai, Kurt's workbench): the shared table system at Japanese height (top y 4-6, legs to the
  floor) with the drawn wood grain on top; legs at drawn outer corners and at the corners whose south row is
  walkable floor. Kurt's L-shaped bench carries his apricorns (two balls), a grey vice and his tools.
- Wall-row tansu: the chest-of-drawers and altar-cabinet tops stack onto their bases in every placement and
  continue them. The chest top `1984-1984-2008-2010` stacks onto its chest in 5 maps but stands in place over a
  bookshelf in 7: its lower half is the top surface of whatever furniture is below. Its link is removed
  (amendment) and it becomes a wall cell (the lattice wall at its native scale), so the chest is one full chest.
- Zabuton (seat, 26 placements): a blue floor cushion with the white crest, kept at the seat height (y 4).
- Mahogany / Mt Moon shop shelves: a double-sided gondola two cells deep, goods on the south face of the front
  row and on the north face of the back row.
- The wooden railing (Route 39 barn, Wise Trio's room): two posts and two rails across the cell.
- The Wise Trio's stairwell down (a warp cell): kept, its WARP_MODELS.md model.
- The Dance Theater's stand, the radio, the bookshelf.
"""
from __future__ import annotations

import numpy as np

from tools.shape_fit.crystal_pass import furniture as F
from tools.shape_fit.crystal_pass import interiors as I
from tools.shape_fit.crystal_pass.common import K, Pass
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, front_rect, top_rect

S = (16, 16, 16)
DARK, CREAM = I.DARK, I.CREAM
WOOD, WOOD_D, WOOD_L = I.WOOD, I.WOOD_D, I.WOOD_L
GREY, GREY_D = I.GREY, I.GREY_D
BLUE, BLUE_D = (123, 132, 255), (74, 74, 255)
LOW = (4, 6)  # a low table's top
LEG = [WOOD_D, DARK]

NEVER = {K(k): label for k, label in [
    ("1987-1987-2063-2063", "Dance Theater stage edge"), ("2004-2004-2005-2005", "Dance Theater stage front"),
    ("2004-2004-2005-2058", "Dance Theater stage front with lanterns"), ("2004-2004-2058-2005", "Dance Theater stage front with lanterns"),
    ("2004-2004-2058-2058", "Dance Theater stage front with lanterns"), ("2018-2019-2002-2003", "low-table front edge on the tatami"),
    ("2020-2018-2001-2002", "low-table front edge on the tatami"), ("2020-2019-2002-2003", "low-table front edge on the tatami")]}

LOW_TABLE = {  # key: (edges, legs, apron, label)
    K("2008-2009-2011-2012"): ("nw", "nw", None, "low table north-west corner"),
    K("2009-2010-2012-2013"): ("ne", "ne se", None, "low table north-east corner"),
    K("2011-2012-2011-2012"): ("w", "sw", None, "low table south-west (its front row is tatami)"),
    K("2012-2013-2012-2013"): ("e", "se", None, "low table south-east (its front row is tatami)"),
    K("2011-2013-2011-2013"): ("we", "sw se", None, "Kurt's bench, one cell wide"),
    K("2008-2010-2011-2013"): ("we", "sw se", None, "low table, one cell wide (Kurt's)"),
    K("2012-2012-2018-2018"): ("", "", (9, (8, 11)), "Kurt's bench front edge"),
}
KURT_ITEMS = {
    K("2009-2009-2012-2064"): "Kurt's bench with an apricorn",
    K("2009-2009-2065-2012"): "Kurt's bench with an apricorn",
    K("2009-2010-1990-2013"): "Kurt's bench corner with the vice",
    K("2012-2066-2018-2018"): "Kurt's bench front with tools",
    K("2067-2013-2018-2019"): "Kurt's bench south-east corner with a tool box",
    K("2011-2012-2008-2010"): "Kurt's bench inner corner",
}
CHEST_TOP, CHEST = K("1984-1984-2008-2010"), K("1954-1955-2031-2032")
ALTAR_TOP, ALTAR = K("2008-2010-2025-2026"), K("2027-2028-2031-2032")
TANSU_TOP, TANSU = K("2008-2010-2054-2055"), K("2056-2057-2033-2034")
BOOKSHELF, RADIO = K("2029-2030-2033-2034"), K("1985-1986-1988-1989")
ZABUTON = K("2014-2015-2016-2017")
SHELF_BACK = (K("333-1990-355-1991"), K("1990-354-1991-356"))
SHELF_FRONT = (K("1992-1993-1995-1996"), K("1993-1994-1996-1997"))
STAND = (K("2043-2044-2045-2046"), K("2021-2022-2023-2024"))
STAIRS, RAILING = K("2047-2048-2049-2050"), K("2052-2052-2053-2053")
OWNED = [*NEVER, *LOW_TABLE, *KURT_ITEMS, CHEST_TOP, CHEST, ALTAR_TOP, ALTAR, TANSU_TOP, TANSU, BOOKSHELF, RADIO, ZABUTON,
         *SHELF_BACK, *SHELF_FRONT, *STAND, STAIRS, RAILING]


def kurt(key):
    """Kurt's bench cells with what stands on them (positions from each drawing)."""
    if key == K("2011-2012-2008-2010"):
        # the L's inner corner: the bench continues south along its west side (rows 8-15 draw that part's east edge)
        return I.table_piece(key, edges="w", slab=LOW, cut=(15, 16, 9, 16))
    apron = key in (K("2012-2066-2018-2018"), K("2067-2013-2018-2019"))
    edges = {"2009-2009-2012-2064": "n", "2009-2009-2065-2012": "n", "2009-2010-1990-2013": "ne",
             "2012-2066-2018-2018": "", "2067-2013-2018-2019": "e"}[key[4:]]
    legs = {"2009-2010-1990-2013": "ne", "2067-2013-2018-2019": "se"}.get(key[4:], "")
    occ, paint = I.table_piece(key, edges=edges, legs=legs, slab=LOW, apron=(9, (8, 11)) if apron else None)
    c = Cell(key)
    b = Build(c)
    b.occ |= occ
    b.parts.append((occ.copy(), None))
    if key == K("2009-2009-2012-2064"):
        b.add(F.ellipsoid(S, (13, 8.5, 10), (2.5, 2.5, 2.5)) & (np.indices(S)[0] >= 6),
              top=top_rect((11, 8, 16, 13), (10.5, 15.5), (7.5, 12.5)), front=[DARK, WOOD_D], side=[DARK])
    elif key == K("2009-2009-2065-2012"):
        b.add(F.ellipsoid(S, (4.5, 8.5, 12), (2.5, 2.5, 2.5)) & (np.indices(S)[0] >= 6),
              top=top_rect((2, 10, 7, 15), (2, 7), (9.5, 14.5)), front=[DARK, WOOD_D], side=[DARK])
    elif key == K("2009-2010-1990-2013"):
        b.add(box(S, (1, 8), (6, 12), (9, 15)), top=top_rect((0, 8, 8, 11), (1, 8), (9, 15)),
              front=front_rect((0, 9, 8, 16), (1, 8), (6, 12)), side=[GREY_D, GREY])
    elif key == K("2012-2066-2018-2018"):
        b.add(box(S, (10, 15), (6, 8), (1, 6)), top=top_rect((10, 0, 16, 7), (10, 15), (1, 6)), front=[DARK], side=[DARK])
    else:
        b.add(box(S, (1, 7), (6, 10), (1, 7)), top=top_rect((0, 0, 8, 3), (1, 7), (1, 7)),
              front=front_rect((0, 3, 8, 8), (1, 7), (6, 10)), side=[WOOD_D, DARK])
    return _merge(b, occ, paint)


def _merge(b, base_occ, base_paint):
    """Paint the added items by faces of the whole shape; keep the table's own paint elsewhere."""
    occ = b.occ.copy()
    paint = base_paint.copy()
    items = occ & ~base_occ
    full = np.zeros(occ.shape, np.uint8)
    for m, how in b.parts:
        if how is None:
            continue
        F.paint_faces(occ, b.cell, region=m & items, paint=full, **how)
    paint[items] = full[items]
    return occ, paint


def wall_cell(key):
    """A wall-row cell that is only wall: the full cell, its south face the drawn lattice wall (rows 0-7) tiled at
    its native scale (one pixel a voxel), as the neighbouring native wall columns show it."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 16), (0, 16)), top=[WOOD_D, WOOD], front=lambda y, z, x: (x + .5, (15 - y) % 8 + .5),
          side=[WOOD_D, WOOD])
    return b.done()


def chest(key, *, rows, h=16, shelf=None):
    return I.case_base(key, rows=rows, shelf=shelf, z=(4, 15))


def stack_top(key, *, front_rows, top_rows, height):
    """The upper drawers / doors of a tansu stacked on its base, with its drawn top surface."""
    return I.case_top(key, front_rows=front_rows, top_rows=top_rows, height=height, z=(4, 15))


def zabuton():
    c = Cell(ZABUTON)
    b = Build(c)
    body = box(S, (1, 15), (0, 4), (1, 15))
    yy, zz, xx = np.indices(S)
    corner = ((xx < 2) | (xx >= 14)) & ((zz < 2) | (zz >= 14)) & (yy >= 2)   # soft rounded corners
    b.add(body & ~corner, top=top_rect((0, 0, 16, 16), (0, 16), (0, 16)), front=[BLUE_D, BLUE], side=[BLUE_D, BLUE])
    return b.done()


def gondola(key, row):
    """Mahogany's double-sided shelf, two cells deep in all. The back row shows its goods to the north, the front
    row to the south: two shelf levels (drawn rows 1-6 and 8-13) are recesses 3 voxels deep with the goods drawn on
    their back walls, the boards and frame between them solid."""
    c = Cell(key)
    b = Build(c)
    z = (1, 16) if row == "back" else (0, 15)
    shelf = lambda y, zz, x: (x + .5, 15.5 - y)                 # drawing row = height, south view
    mirrored = lambda y, zz, x: (15.5 - x, 15.5 - y)            # the same art seen from the north
    face = shelf if row == "front" else mirrored
    plain = [GREY_D, GREY]
    b.add(box(S, (0, 16), (0, 16), z), top=[GREY, GREY_D], front=face if row == "front" else plain, side=plain,
          back=face if row == "back" else plain)
    for y in ((2, 8), (10, 15)):                                # shelf levels: rows 8-13 and 1-6 from the bottom up
        if row == "front":
            b.cut(box(S, (1, 15), y, (z[1] - 3, z[1])))
            b.add(box(S, (1, 15), y, (z[1] - 3, z[1] - 2)), top=plain, front=face, side=plain)
        else:
            b.cut(box(S, (1, 15), y, (z[0], z[0] + 3)))
            b.add(box(S, (1, 15), y, (z[0] + 2, z[0] + 3)), top=plain, front=plain, side=plain, back=face)
    return b.done()


def stand(key, west):
    """The Dance Theater's wooden stand: a low box with its top strip (rows 0-3) and decorated front (rows 4-12);
    the tatami row in front is floor."""
    c = Cell(key)
    b = Build(c)
    x = (1, 16) if west else (0, 15)
    b.add(box(S, x, (0, 8), (3, 14)), top=top_rect((0, 0, 16, 4), (0, 16), (3, 14)),
          front=front_rect((0, 4, 16, 12), (0, 16), (0, 8)), side=[WOOD_D, WOOD])
    return b.done()


def stairs():
    c = Cell(STAIRS)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 1), (0, 16)), top=top_rect((0, 0, 16, 16), (0, 16), (0, 16)), front=[WOOD_D, DARK], side=[WOOD_D, DARK])
    b.add(box(S, (12, 15), (1, 7), (5, 16)), top=[WOOD_L, WOOD], front=[WOOD, WOOD_D], side=[WOOD, WOOD_D])
    return b.done()


def railing():
    """Two posts (drawn columns 2-6 and 10-14) and two rails across the cell (rows 4 and 7-8), at the cell's middle."""
    c = Cell(RAILING)
    b = Build(c)
    for px in (2, 10):
        b.add(box(S, (px + 1, px + 4), (0, 12), (6, 9)), top=top_rect((px, 0, px + 5, 3), (px + 1, px + 4), (6, 9)),
              front=front_rect((px, 9, px + 5, 16), (px + 1, px + 4), (0, 12)), side=[WOOD_D, WOOD])
    for y in ((9, 11), (4, 6)):
        b.add(box(S, (0, 16), y, (7, 8)), top=[WOOD, WOOD_L], front=[WOOD, WOOD_D], side=[WOOD_D, DARK])
    return b.done()


def radio():
    c = Cell(RADIO)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 8), (5, 14)), top=top_rect((0, 1, 16, 8), (1, 15), (5, 14)),
          front=front_rect((1, 8, 15, 14), (1, 15), (0, 8)), side=[GREY_D, GREY])
    return b.done()


def main():
    out = Pass("traditional-house")
    for key, label in NEVER.items():
        out.keep(key, label=label, family="traditional-never-drawn",
                 reason="Every placement is a walkable cell and the entry has no walk or seat flag: never drawn (census: walk).")
    for key, (edges, legs, apron, label) in LOW_TABLE.items():
        out.cell(key, *I.table_piece(key, edges=edges, legs=legs, apron=apron, slab=LOW), label=label, family="low-table",
                 reason="The shared table system at Japanese height (top y 4-6): the drawn wood grain on top, legs at drawn "
                        "outer corners and where the next row is tatami floor.")
    for key, label in KURT_ITEMS.items():
        out.cell(key, *kurt(key), label=label, family="kurt-bench",
                 reason="Kurt's L-shaped low workbench, with the drawn apricorns as balls, the grey vice and his tools standing "
                        "on it instead of being painted flat.")
    occ, paint = wall_cell(CHEST_TOP)
    out.cell(CHEST_TOP, occ, paint, label="lattice wall over a chest or bookshelf (wall row)", family="tansu",
             reason="Stands in place over a bookshelf in 7 of its 12 placements; its lower half is the top surface of the "
                    "furniture below. Its stack link is removed (amendment) and it is the wall it shows, the lattice at its "
                    "native scale; the chest below is one full chest.")
    out.drop_stack(CHEST_TOP, "Placed in the wall row over 2029-2030-2033-2034 (a bookshelf, no top of its own) in ECRUTEAK_ITEMFINDER_HOUSE, "
                              "ECRUTEAK_LUGIA_SPEECH_HOUSE, KURTS_HOUSE, MAHOGANY_MART_1F, MOUNT_MOON_GIFT_SHOP, VIOLET_KYLES_HOUSE and "
                              "VIOLET_NICKNAME_SPEECH_HOUSE: the same drawing is not a chest's upper part there.")
    out.cell(CHEST, *chest(CHEST, rows=(0, 16)), label="tansu chest", family="tansu",
             reason="A full low chest (2 blocks) with its drawers above two doors on the front, its top in wood.")
    out.cell(ALTAR, *chest(ALTAR, rows=(0, 16)), label="altar cabinet lower half", family="tansu",
             reason="Base of the tall altar cabinet: compartments above two doors.")
    out.cell(ALTAR_TOP, *stack_top(ALTAR_TOP, front_rows=(8, 16), top_rows=(0, 8), height=12), label="altar cabinet upper half (stacked)",
             family="tansu", reason="Stacked in all 7 placements: the shrine face with its emblem and the wood-grain top.")
    out.entity("crystal_traditional_altar", [[ALTAR_TOP], [ALTAR]], "Altar cabinet (stack)", kind="stack (model as one)")
    out.cell(TANSU, *chest(TANSU, rows=(0, 15)), label="chest of drawers lower half", family="tansu",
             reason="Base of the tall chest of drawers: three drawer rows on its front.")
    out.cell(TANSU_TOP, *stack_top(TANSU_TOP, front_rows=(8, 16), top_rows=(0, 8), height=10),
             label="chest of drawers upper half (stacked)", family="tansu",
             reason="Stacked in all 5 placements: the top drawers and the wood-grain top.")
    out.entity("crystal_traditional_tansu", [[TANSU_TOP], [TANSU]], "Chest of drawers (stack)", kind="stack (model as one)")
    out.cell(BOOKSHELF, *I.case_base(BOOKSHELF, rows=(0, 15), shelf=((3, 13), (9, 15), (1, 7)), z=(4, 15)),
             label="bookshelf", family="tansu", reason="A full-height bookshelf: a recessed shelf of books over drawers.")
    out.cell(RADIO, *radio(), label="radio on the floor", family="traditional-props",
             reason="A grey radio: its top panel and its front with the dial and speaker.")
    out.cell(ZABUTON, *zabuton(), label="zabuton floor cushion", family="seating",
             reason="A square cushion with soft corners and the white crest on top, at the existing seat height (y 4).")
    for key in SHELF_BACK:
        out.cell(key, *gondola(key, "back"), label="shop gondola, back row (goods to the north)", family="shop-shelf",
                 reason="Double-sided shop shelf two cells deep: this row shows its goods on its north face.")
    for key in SHELF_FRONT:
        out.cell(key, *gondola(key, "front"), label="shop gondola, front row (goods to the south)", family="shop-shelf",
                 reason="Double-sided shop shelf two cells deep: this row shows its goods on its south face.")
    out.entity("crystal_mahogany_gondola", [[SHELF_BACK[0], SHELF_BACK[1]], [SHELF_FRONT[0], SHELF_FRONT[1]]],
               "Mahogany shop gondola shelf (2x2)")
    for key, west in zip(STAND, (True, False)):
        out.cell(key, *stand(key, west), label="Dance Theater wooden stand", family="traditional-props",
                 reason="A low wooden stand: its top strip and decorated front; the tatami in front stays floor.")
    out.entity("crystal_dance_theater_stand", [list(STAND)], "Dance Theater stand (2x1)")
    out.keep_warp(STAIRS, label="Wise Trio's stairwell down (warp)", family="stairs")
    out.cell(RAILING, *railing(), label="wooden railing", family="railing",
             reason="Two posts and two rails across the cell (Route 39 barn pens, the Wise Trio's room) instead of an "
                    "extruded sprite.")
    out.entity("crystal_barn_railing_row", [[RAILING, RAILING, RAILING]], "Route 39 barn railing (three cells)")
    out.entity("crystal_kurt_bench", [[K("2008-2009-2011-2012"), K("2009-2009-2012-2064"), K("2009-2009-2065-2012"), K("2009-2010-1990-2013")],
                                      [K("2011-2012-2008-2010"), K("2012-2012-2018-2018"), K("2012-2066-2018-2018"), K("2067-2013-2018-2019")],
                                      [K("2011-2013-2011-2013"), None, None, None],
                                      [K("2011-2013-2011-2013"), None, None, None]], "Kurt's L-shaped workbench")
    out.entity("crystal_low_table_2x2", [[K("2008-2009-2011-2012"), K("2009-2010-2012-2013")],
                                         [K("2011-2012-2011-2012"), K("2012-2013-2012-2013")]], "Ecruteak low table (2x2)")
    out.write(owned=OWNED)


if __name__ == "__main__":
    main()
