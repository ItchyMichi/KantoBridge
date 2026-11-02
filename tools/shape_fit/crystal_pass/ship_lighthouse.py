"""Crystal checkpoint 18: TILESET_LIGHTHOUSE_1 (the S.S. Aqua: decks, cabins, the dining room, the captain's cabin) and
TILESET_LIGHTHOUSE_2 (the Olivine Lighthouse).

Source reading (study/groups/TILESET_LIGHTHOUSE_*; the ship and lighthouse maps):

- Tables: the shared `interiors.table_piece` (legs at drawn outer corners) with the front rows' band and the woven front
  panel under it (as the facility tables), the dishes (a plate across the seam), cups and the captain's book on top.
  The cabins' small tables are drawn half a row lower: their north cells carry the top over the cell's south half (one
  under the wall, the porthole wall closing its north half).
- Beds: one bed lying north-south across its head and foot cells (the players' house design: headboard, footboard,
  rails, mattress, pillow, legs). Their links stacked the foot on the head two cells tall, so they are removed
  (amendments), as the players' house and mansion beds.
- The portholes in the wall row (wall reliefs), the cabin machine against the wall (its top in the wall row's south
  half), red stools, the captain's chair (a blue back over a red stool), the cabin cabinet (stack), the red ball, the
  lighthouse's window walls; stairway warps kept (WARP_MODELS.md).
"""
from __future__ import annotations

from collections import Counter

import numpy as np

from tools.shape_fit.crystal_pass import furniture as F
from tools.shape_fit.crystal_pass import interiors as I
from tools.shape_fit.crystal_pass.common import K, Pass
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, front_rect, top_rect

S = (16, 16, 16)
DARK, CREAM = I.DARK, I.CREAM
GOLD, GOLD_D, GOLD_L = (173, 140, 58), (132, 107, 25), (214, 197, 140)
GREY, GREY_D = (156, 156, 156), (107, 107, 107)
TOPDRAW = top_rect((0, 0, 16, 16), (0, 16), (0, 16))
FULL = front_rect((0, 0, 16, 16), (0, 16), (0, 16))
APRON = (14, (8, 11))
FAMILIES = ["TILESET_LIGHTHOUSE_1", "TILESET_LIGHTHOUSE_2"]

# table pieces: key -> (edges, legs, apron, items)  items: list of ("cup", (cx, cz, r)) / ("dish", west) / ("book", (x, z))
TABLES = {
    # S.S. Aqua dining table and captain's table
    K("2658-2659-2661-2662"): ("nw", "nw", False, [("cup", (10.5, 11, 3))]),
    K("2659-2660-1123-2663"): ("ne", "ne", False, []),
    K("2661-2702-2661-2704"): ("w", "", False, [("dish", True)]),
    K("2703-2663-2705-2663"): ("e", "", False, [("dish", False)]),
    K("2661-1123-2661-1123"): ("w", "", False, []),
    K("2662-2663-1123-2663"): ("e", "", False, [("cup", (3.5, 3, 2.6))]),
    K("2708-2709-2711-2712"): ("w", "sw", True, []),
    K("2709-2710-2713-2711"): ("e", "se", True, []),
    K("2658-2659-2661-1123"): ("nw", "nw", False, []),
    K("2659-2659-2728-2729"): ("n", "", False, [("book", ((3, 13), (9, 15)))]),
    K("2708-2709-2722-2723"): ("w", "sw", True, []),
    K("2709-2709-2711-2711"): ("", "", True, []),
    K("2708-2662-2722-2723"): ("w", "sw", True, []),
    K("2709-2710-2722-2723"): ("e", "se", True, []),
    # Olivine Lighthouse 6F table
    K("3935-3936-3938-3939"): ("nw", "nw", False, [("cup", (10.5, 11, 3))]),
    K("3936-3937-3940-3941"): ("ne", "ne", False, []),
    K("3938-3979-3938-3981"): ("w", "", False, [("dish", True)]),
    K("3980-3941-3982-3941"): ("e", "", False, [("dish", False)]),
    K("3985-3986-3988-3989"): ("w", "sw", True, []),
    K("3986-3987-3990-3988"): ("e", "se", True, []),
}
HALF_TABLE = {K("2655-2656-2658-2659"): ("w", True), K("96-96-2659-2660"): ("e", False)}  # key: (side, under the wall)
PORTHOLE = K("2655-2656-2657-2657")
MACHINE_TOP, MACHINE = K("2655-2656-2730-2731"), K("2732-2733-2734-2735")
STOOLS = [K("2664-2665-2666-2667"), K("3942-3943-3944-3945")]
CHAIR = K("2726-2727-2666-2667")
BEDS = [(K("2683-2684-2685-2686"), K("2687-2688-2689-2690")), (K("3961-3962-3963-3964"), K("3965-3966-3967-3968"))]
CABINET_TOP, CABINET = K("2714-2715-2716-2717"), K("2716-2717-2722-2723")
BALL = K("2718-2719-2720-2721")
WINDOW_WALL = K("3976-3976-3977-3978")
STAIRS = [K("2691-2692-2693-2694"), K("2736-2737-2738-2739"), K("3969-3970-2693-3971"), K("4013-4014-4015-4016")]
DOOR = K("2668-2669-2670-2671")


def own(key, n=4):
    c = Cell(key)
    return [col for col, _ in Counter(tuple(int(a) for a in px) for px in c.pixels.reshape(-1, 3)).most_common(n)]


def items(b, key, spec):
    mats = own(key, 6)
    for kind, arg in spec:
        if kind == "cup":
            cx, cz, r = arg
            b.add(F.cylinder(S, (cx, cz), r, (8, 11)), top=TOPDRAW, front=mats, side=mats)
        elif kind == "dish":
            cx = 16 if arg else 0
            b.add(F.cylinder(S, (cx, 8), 7.2, (8, 9)), top=TOPDRAW, front=mats, side=mats)
        else:
            x, z = arg
            b.add(box(S, x, (8, 10), z), top=TOPDRAW, front=mats, side=mats)


def table(key, edges, legs, apron, spec):
    """The shared table piece; on a front row the band and the woven front panel under it (drawn rows 11-14); its items."""
    occ, paint = I.table_piece(key, edges=edges, legs=legs, apron=APRON if apron else None)
    c = Cell(key)
    b = Build(c)
    b.add(occ, top=TOPDRAW, front=I.LEG, side=I.LEG)
    if apron:
        x = (1 if "w" in edges else 0, 15 if "e" in edges else 16)
        b.add(box(S, x, (0, 4), (13, 14)), top=I.LEG, front=front_rect((x[0], 11, x[1], 15), x, (0, 4)), side=I.LEG)
    items(b, key, spec)
    occ2, paint2 = b.done()
    paint2[occ] = paint[occ]
    return occ2, paint2


def half_table(key, side, wall):
    """A cabin table's north cell drawn half a row lower: the top over the cell's south half (legs at its outer north
    corner); under the wall, the porthole wall closes the north half."""
    c = Cell(key)
    b = Build(c)
    mats = own(key, 5)
    if wall:
        b.add(box(S, (0, 16), (0, 16), (0, 8)), top=mats, front=front_rect((0, 0, 16, 8), (0, 16), (0, 16)), side=mats)
    x = (1, 16) if side == "w" else (0, 15)
    b.add(box(S, x, (6, 8), (9, 16)), top=top_rect((0, 8, 16, 16), (0, 16), (8, 16)), front=I.LEG, side=I.LEG)
    lx = (2, 4) if side == "w" else (12, 14)
    b.add(box(S, lx, (0, 6), (10, 12)), top=I.LEG, front=I.LEG, side=I.LEG)
    return b.done()


def bed(head, foot):
    """One bed lying north-south across its head and foot cells (the players' house design), its sheets and pillow
    showing each cell's drawing from above."""
    occ = np.zeros((16, 32, 16), bool)
    mat = np.zeros(occ.shape, np.uint8)
    shape = occ.shape

    def put(mask, m):
        occ[mask] = True
        mat[mask] = m
    put(box(shape, (1, 15), (0, 12), (1, 3)), 1)
    put(box(shape, (1, 15), (0, 9), (29, 31)), 1)
    put(box(shape, (1, 15), (2, 4), (3, 29)), 1)
    put(box(shape, (2, 14), (4, 7), (3, 29)), 2)
    put(box(shape, (4, 12), (7, 9), (4, 9)), 3)
    for x in (1, 13):
        for z in (3, 27):
            put(box(shape, (x, x + 2), (0, 2), (z, z + 2)), 1)
    up = F.exposed(occ)["up"]
    out = {}
    for key, z0 in ((head, 0), (foot, 16)):
        c = Cell(key)
        frame = [col for col in own(key, 6) if col not in (CREAM, (247, 230, 214))] or [DARK]
        part, mats, upm = occ[:, z0:z0 + 16], mat[:, z0:z0 + 16], up[:, z0:z0 + 16]
        p = np.zeros(part.shape, np.uint8)
        for y, z, x in np.argwhere(part):
            m = int(mats[y, z, x])
            if upm[y, z, x] and m in (2, 3):
                p[y, z, x] = z * 16 + x
            else:
                p[y, z, x] = c.pick(frame, x + .5, z + .5)
        out[key] = (part.copy(), p)
    return out


def chair():
    """The captain's chair: a red stool (its legs drawn rows 8-15) with the blue back along the north (rows 0-7)."""
    c = Cell(CHAIR)
    b = Build(c)
    occ, paint = I.round_stool(CHAIR, background=[CREAM], center=(8, 9), radius=5, top_rows=(8, 15))
    b.add(occ, top=own(CHAIR), front=own(CHAIR), side=own(CHAIR))
    blue = [(140, 156, 255), (115, 132, 255), DARK]
    b.add(box(S, (3, 13), (4, 13), (3, 5)), top=blue, front=front_rect((3, 0, 13, 8), (3, 13), (4, 13)), side=blue)
    occ2, paint2 = b.done()
    paint2[occ] = paint[occ]
    return occ2, paint2


def machine_top():
    """The wall row over the cabin machine: the porthole wall closes the north half, the machine's upper part (its top
    drawn rows 8-15) stands in the south half, continuing the machine below."""
    c = Cell(MACHINE_TOP)
    b = Build(c)
    mats = own(MACHINE_TOP, 5)
    b.add(box(S, (0, 16), (0, 16), (0, 8)), top=mats, front=front_rect((0, 0, 16, 8), (0, 16), (0, 16)), side=mats)
    blue = [(140, 156, 255), (115, 132, 255), (123, 132, 255), (74, 74, 255), DARK]  # the machine's own blues
    b.add(box(S, (2, 14), (0, 16), (8, 16)), top=top_rect((2, 8, 14, 16), (2, 14), (8, 16)), front=blue, side=blue)
    return b.done()


def full(key, top=None):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 16), (0, 16)), top=top or own(key), front=FULL, side=own(key))
    return b.done()


def ball():
    c = Cell(BALL)
    b = Build(c)
    b.add(F.ellipsoid(S, (8, 6.5, 8), (6.5, 6.5, 6.5)), top=top_rect((1, 0, 15, 8), (1.5, 14.5), (1.5, 14.5)),
          front=front_rect((1, 1, 15, 15), (1.5, 14.5), (0, 13)), side=own(BALL))
    return b.done()


def plate(key):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 1), (0, 16)), top=TOPDRAW, front=own(key), side=own(key))
    return b.done()


def main():
    out = Pass("ship-lighthouse")
    for key, (edges, legs, apron, spec) in TABLES.items():
        out.cell(key, *table(key, edges, legs, apron, spec), label="table" + (" (front)" if apron else ""), family="sl-tables",
                 reason="The shared table system: a 1-block top, legs at drawn outer corners"
                        + (", the front band and the woven panel under it" if apron else "")
                        + ("; " + ", ".join(k for k, _ in spec) + " on top" if spec else "") + ".")
    for key, (side, wall) in HALF_TABLE.items():
        out.cell(key, *half_table(key, side, wall), label="cabin table top (south half)" + (" under the wall" if wall else ""),
                 family="sl-tables", reason="The cabin table drawn half a row lower: its top over the cell's south half"
                                            + (", the porthole wall closing the north half." if wall else "."))
    out.entity("crystal_sl_dining_table", [[K("2658-2659-2661-2662"), K("2659-2660-1123-2663")], [K("2661-2702-2661-2704"), K("2703-2663-2705-2663")],
                                           [K("2661-1123-2661-1123"), K("2662-2663-1123-2663")], [K("2708-2709-2711-2712"), K("2709-2710-2713-2711")]],
               "S.S. Aqua dining table (part)")
    out.entity("crystal_sl_cabin_table", [[K("2655-2656-2658-2659"), K("96-96-2659-2660")], [K("2708-2662-2722-2723"), K("2709-2710-2722-2723")]],
               "Cabin table under the porthole wall")
    for head, foot in BEDS:
        parts = bed(head, foot)
        out.cell(head, *parts[head], label="bed head (pillow end)", family="sl-beds",
                 reason="The head of one bed lying north-south (the players' house design): headboard, pillow, mattress.")
        out.drop_stack(head, "S.S. Aqua cabins and Olivine Lighthouse 6F: one bed drawn top-down across its head and foot "
                             "cells; the link stacked the foot on the head two cells tall (as the players' house and "
                             "mansion beds, amended at checkpoints 1 and 4).")
        out.cell(foot, *parts[foot], label="bed foot", family="sl-beds", reason="The foot of the same bed: sheets and footboard.")
    out.entity("crystal_sl_bed", [[BEDS[0][0]], [BEDS[0][1]]], "Cabin bed")
    out.cell(PORTHOLE, *I.wall_relief(PORTHOLE, background=[CREAM], depth=1), label="porthole wall", family="sl-walls",
             reason="A wall-row cell: the wall closed, the porthole and the skirting standing one voxel proud (61 placements).")
    out.cell(MACHINE_TOP, *machine_top(), label="cabin machine under the porthole wall", family="sl-walls",
             reason="The porthole wall closes the north half; the machine's upper part stands in front.")
    out.cell(MACHINE, *full(MACHINE), label="cabin machine", family="sl-walls", reason="The blue machine's face, a full block.")
    out.cell(WINDOW_WALL, *full(WINDOW_WALL), label="lighthouse window wall", family="sl-walls",
             reason="The lighthouse's outer wall with its windows, a full block with its drawing (52 placements).")
    for key in STOOLS:
        out.cell(key, *I.round_stool(key, background=[CREAM], center=(8, 8), radius=5.5, top_rows=(1, 10)), label="red stool",
                 family="sl-furniture", reason="A round red stool on short legs (walkable).")
    out.cell(CHAIR, *chair(), label="captain's chair", family="sl-furniture", reason="A red stool with the blue back along the north.")
    out.cell(CABINET, *I.case_base(CABINET, rows=(0, 15), wood=(GOLD, GOLD_D)), label="cabin cabinet base", family="sl-furniture",
             reason="The cabinet's lower half, its drawn front.")
    out.cell(CABINET_TOP, *I.case_top(CABINET_TOP, front_rows=(8, 16), top_rows=(1, 8), height=12, wood=(GOLD, GOLD_D)),
             label="cabin cabinet top (stacked)", family="sl-furniture", reason="Stacked on its base: the upper front and top.")
    out.entity("crystal_sl_cabinet", [[CABINET_TOP], [CABINET]], "Cabin cabinet (stack)", kind="stack (model as one)")
    out.cell(BALL, *ball(), label="red ball", family="sl-furniture", reason="The red ball as a sphere showing its drawing.")
    for key in STAIRS:
        out.keep_warp(key, label="stairway (warp)", family="sl-warps")
    out.keep(DOOR, label="cabin door (walkable, never drawn)", family="sl-retained",
             reason="A walkable door cell with no walk or seat model: never drawn, its entry is kept.")
    out.write(families=FAMILIES)


if __name__ == "__main__":
    main()
