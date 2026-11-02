"""Crystal checkpoint 13: TILESET_UNDERGROUND_1 (the Goldenrod Dept Store B1F and Underground Warehouse, the Team
Rocket base B1F), TILESET_UNDERGROUND_2 (the Olivine and Vermilion port passages, Saffron Gym's statue) and
TILESET_UNDERGROUND_3 (the Underground Path).

Source reading (study/groups/TILESET_UNDERGROUND_*; composites _ug_*):

- The warehouse's cardboard crates (84): lidded boxes with the drawn tape on their faces.
- The Rocket base: Persian statues (the gold figure stacked on its grey pedestal, the shared statue builders), the grey
  desk with its monitor drawn across the wall row and the row below (the wall closes the wall row's north half, the desk
  top straddles the seam, legs at its ends) and its chair, the bookshelves (the shared case builders, stacks).
- The port passages' potted palms (the crown stacked on the pot; the pot carries the trunk up to the crown), Saffron
  Gym's red statue on its pedestal.
- Walkable warps (the red shutter doors, the stairways): kept, their WARP_MODELS.md models.
- Walls: the Underground Path's wall-row blocks and the basement's event-placed wall piece (2968-2969-2968-2969: the
  basement's sliding walls; the survey's default layout never places it) are full blocks with their drawing.
"""
from __future__ import annotations

from collections import Counter

import numpy as np

from tools.shape_fit.crystal_pass import furniture as F
from tools.shape_fit.crystal_pass import interiors as I
from tools.shape_fit.crystal_pass.common import K, Pass
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, front_rect, top_rect

S = (16, 16, 16)
DARK, CREAM, GREY, GREY_D = I.DARK, I.CREAM, I.GREY, I.GREY_D
GREY_P, PALE = (173, 173, 173), (222, 255, 222)
TAN, TAN_D = (197, 148, 58), (165, 123, 25)
TOPDRAW = top_rect((0, 0, 16, 16), (0, 16), (0, 16))
FULL = front_rect((0, 0, 16, 16), (0, 16), (0, 16))
FAMILIES = ["TILESET_UNDERGROUND_1", "TILESET_UNDERGROUND_2", "TILESET_UNDERGROUND_3"]

STAIRS = [K(k) for k in ("2947-2948-2949-2950", "2951-2952-2953-2954", "4105-4106-4107-4108", "4109-4110-4111-4112",
                         "5018-5019-5020-5021")]
SHUTTERS = [K("2982-2982-2983-2983"), K("4138-4138-4139-4139")]
CRATE = K("2972-2973-2974-2975")
PERSIAN, PERSIAN_PED = K("2955-2956-2958-2959"), K("2961-2962-2963-2964")
SAFFRON, SAFFRON_PED = K("4155-4156-4157-4158"), K("4119-4120-4121-4122")
DESK_WL, DESK_WR, DESK_L, DESK_R = (K("2957-2957-2991-2992"), K("2957-2957-2993-2994"), K("2995-2996-2999-3000"),
                                    K("2997-2998-3001-3002"))
CHAIR = K("3015-3016-3017-3018")
SHELF_TOP, SHELF = K("2991-3009-3011-3012"), K("3011-3012-3013-3014")
PALM, POT = K("4140-4141-4142-4143"), K("4144-4145-4104-4104")
WALLS = [K("5028-5040-5031-5042"), K("5039-5028-5041-5031"), K("2968-2969-2968-2969")]


def dominant(key, n=3, skip=()):
    """The cell's n most frequent colours (a statue's stone), skipping the floor's."""
    c = Cell(key)
    counts = Counter(tuple(int(a) for a in px) for px in c.pixels.reshape(-1, 3))
    return tuple(col for col, _ in counts.most_common() if col not in skip)[:n]


def plate(key):
    """A walkable stairway warp: a floor-level plate showing the drawn stairs."""
    c = Cell(key)
    b = Build(c)
    own = list(dominant(key, 4))
    b.add(box(S, (0, 16), (0, 1), (0, 16)), top=TOPDRAW, front=own, side=own)
    return b.done()


def shutter(key):
    """A walkable shutter door in the wall row: a thin panel against the back showing the drawn shutter (no collision:
    the player walks into it and warps), so the wall line stays closed."""
    c = Cell(key)
    b = Build(c)
    own = list(dominant(key, 4))
    b.add(box(S, (0, 16), (0, 16), (0, 2)), top=own, front=FULL, side=own)
    return b.done()


def crate():
    """A cardboard crate: its lid (drawn rows 0-7) on top, the taped face (rows 8-15) on the south face."""
    c = Cell(CRATE)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 12), (1, 15)), top=top_rect((0, 0, 16, 8), (0, 16), (1, 15)), front=front_rect((0, 8, 16, 16), (0, 16), (0, 12)),
          side=[TAN, TAN_D, DARK])
    return b.done()


def wall_half(b):
    b.add(box(S, (0, 16), (0, 16), (0, 8)), top=[DARK], front=front_rect((0, 0, 16, 8), (0, 16), (0, 16)), side=[DARK, (66, 99, 255)])


def desk(key):
    """The Rocket base's grey desk drawn across the wall row and the row below: in the wall row the wall closes the north
    half and the desk top's back half stands in front (the monitor on the right); below, the top's front half, its
    drawn edge and the legs at the outer ends (the round object on the left, the monitor's front on the right)."""
    c = Cell(key)
    b = Build(c)
    left = key in (DESK_WL, DESK_L)
    grey = [GREY_P, GREY_D, DARK, PALE]
    if key in (DESK_WL, DESK_WR):
        wall_half(b)
        b.add(box(S, (0, 16), (5, 8), (8, 16)), top=top_rect((0, 8, 16, 16), (0, 16), (8, 16)), front=grey, side=grey)
        if not left:
            b.add(box(S, (4, 12), (8, 14), (9, 16)), top=top_rect((3, 9, 13, 16), (4, 12), (9, 16)), front=grey, side=grey)
    else:
        b.add(box(S, (0, 16), (5, 8), (0, 8)), top=top_rect((0, 0, 16, 8), (0, 16), (0, 8)), front=front_rect((0, 8, 16, 11), (0, 16), (5, 8)),
              side=grey)
        legs = (0, 3) if left else (13, 16)
        b.add(box(S, legs, (0, 5), (1, 7)), top=grey, front=front_rect((legs[0], 10, legs[1], 16), legs, (0, 5)), side=grey)
        if left:
            b.add(F.cylinder(S, (12.5, 3.5), 2.5, (8, 10)), top=top_rect((10, 0, 16, 7), (10, 15), (1, 6)), front=grey, side=grey)
        else:
            b.add(box(S, (4, 12), (8, 14), (0, 4)), top=top_rect((0, 0, 10, 6), (4, 12), (0, 4)), front=front_rect((0, 0, 10, 6), (4, 12), (8, 14)),
                  side=grey)
    return b.done()


def chair():
    """The desk's grey chair: a square seat five voxels high (walkable)."""
    c = Cell(CHAIR)
    b = Build(c)
    b.add(box(S, (2, 14), (0, 5), (2, 14)), top=top_rect((2, 1, 14, 9), (2, 14), (2, 14)), front=front_rect((2, 9, 14, 15), (2, 14), (0, 5)),
          side=[GREY_P, GREY_D, DARK])
    return b.done()


def pot():
    """The palm's gold pot in the cell's north half (drawn rows 0-7) and the trunk rising from it to the stacked crown."""
    c = Cell(POT)
    b = Build(c)
    gold = [(173, 140, 58), (132, 107, 25), (214, 197, 140), DARK]
    b.add(F.cylinder(S, (8, 5), 4.8, (0, 8)), top=top_rect((2, 0, 14, 4), (3.2, 12.8), (0.2, 9.8)), front=front_rect((2, 0, 14, 8), (3.2, 12.8), (0, 8)),
          side=gold)
    b.add(box(S, (7, 9), (8, 16), (4, 6)), top=[(132, 107, 25)], front=[(132, 107, 25), DARK], side=[(132, 107, 25), DARK])
    return b.done()


def palm():
    """Stacked on the pot: the palm's crown of leaves."""
    c = Cell(PALM)
    b = Build(c)
    have = {tuple(int(a) for a in px) for px in c.pixels.reshape(-1, 3)}
    greens = [g for g in ((123, 165, 8), (74, 107, 0), (148, 197, 74), (41, 115, 0), (99, 206, 8)) if g in have] or [DARK]
    b.add(F.ellipsoid(S, (8, 7, 6.5), (7.5, 7, 6.5)), top=top_rect((0, 0, 16, 10), (0.5, 15.5), (0, 13)), front=front_rect((0, 0, 16, 16), (0.5, 15.5), (0, 14)),
          side=greens)
    return b.done()


def wall_block(key):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 16), (0, 16)), top=TOPDRAW, front=FULL, side=list(dominant(key, 4)))
    return b.done()


def main():
    out = Pass("underground")
    for key in STAIRS:
        out.keep_warp(key, label="stairway (warp)", family="ug-warps")
    for key in SHUTTERS:
        out.keep_warp(key, label="shutter door (warp)", family="ug-warps")
    out.cell(CRATE, *crate(), label="cardboard crate", family="ug-warehouse",
             reason="A lidded crate twelve voxels high, the tape drawn on its face (84 placements).")
    out.entity("crystal_ug_crates", [[CRATE, CRATE], [CRATE, CRATE]], "Warehouse crates")
    for key in WALLS:
        out.cell(key, *wall_block(key), label="wall block", family="ug-walls",
                 reason="A wall cell as a full block with its drawing (the Underground Path's wall row; the basement's "
                        "event-placed wall piece, never in the survey's default layout).")
    pskip = {PALE, (156, 156, 156)}
    out.cell(PERSIAN_PED, *I.statue_pedestal(PERSIAN_PED, stone=dominant(PERSIAN_PED, skip=pskip)), label="Persian statue pedestal",
             family="ug-statues", reason="The pedestal, the shared builder (the figure stacks on it).")
    out.cell(PERSIAN, *I.statue_figure(PERSIAN, stone=dominant(PERSIAN, skip=pskip)), label="Persian statue (stacked)",
             family="ug-statues", reason="The gold Persian figure, the shared builder, stacked on its pedestal.")
    out.entity("crystal_ug_persian", [[PERSIAN], [PERSIAN_PED]], "Rocket base Persian statue (stack)", kind="stack (model as one)")
    out.cell(SAFFRON_PED, *I.statue_pedestal(SAFFRON_PED, stone=dominant(SAFFRON_PED, skip=pskip | {CREAM})), label="Saffron statue pedestal",
             family="ug-statues", reason="The pedestal, the shared builder.")
    out.cell(SAFFRON, *I.statue_figure(SAFFRON, stone=dominant(SAFFRON, skip=pskip | {CREAM})), label="Saffron statue (stacked)",
             family="ug-statues", reason="The red figure, the shared builder, stacked on its pedestal.")
    out.entity("crystal_ug_saffron_statue", [[SAFFRON], [SAFFRON_PED]], "Saffron Gym statue (stack)", kind="stack (model as one)")
    for key in (DESK_WL, DESK_WR, DESK_L, DESK_R):
        out.cell(key, *desk(key), label="Rocket base desk" + (" under the wall" if key in (DESK_WL, DESK_WR) else ""), family="ug-rocket",
                 reason="The grey desk drawn across the wall row and the row below (the wall closes the north half; the top "
                        "straddles the seam, legs at its ends, the monitor on the right).")
    out.cell(CHAIR, *chair(), label="desk chair", family="ug-rocket", reason="The desk's grey chair, five voxels high (walkable).")
    out.entity("crystal_ug_rocket_desk", [[DESK_WL, DESK_WR], [DESK_L, DESK_R], [CHAIR, None]], "Rocket base desk with its chair")
    wood = (TAN, TAN_D)
    out.cell(SHELF, *I.case_base(SHELF, rows=(0, 15), shelf=((2, 14), (9, 15), (1, 7)), wood=wood), label="bookshelf base",
             family="ug-rocket", reason="The shelf's lower half: the upper shelf recessed with its books, the drawers below.")
    out.cell(SHELF_TOP, *I.case_top(SHELF_TOP, front_rows=(8, 16), top_rows=(1, 8), height=12, shelf=((2, 14), (3, 9), (9, 15)), wood=wood),
             label="bookshelf top (stacked)", family="ug-rocket", reason="Stacked on its base: the upper shelf, recessed, and the grey top.")
    out.entity("crystal_ug_shelf", [[SHELF_TOP], [SHELF]], "Rocket base bookshelf (stack)", kind="stack (model as one)")
    out.cell(POT, *pot(), label="palm pot with the trunk", family="ug-port", reason="The gold pot and the trunk rising to the crown.")
    out.cell(PALM, *palm(), label="palm crown (stacked)", family="ug-port", reason="Stacked on the pot: the palm's crown of leaves.")
    out.entity("crystal_ug_palm", [[PALM], [POT]], "Port passage palm (stack)", kind="stack (model as one)")
    out.write(families=FAMILIES)


if __name__ == "__main__":
    main()
