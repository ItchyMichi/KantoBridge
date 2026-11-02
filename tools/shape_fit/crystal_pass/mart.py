"""Crystal checkpoint 7: TILESET_MART_1 (every Mart, the Celadon and Goldenrod department stores and the Goldenrod roof).

Source reading (study/groups/TILESET_MART_1, the census, the stack placements; composites _CELADON_DEPT_STORE_1F,
_celadon5f, _roof_pool):

- Never drawn (census): the counters (collision 144: Crystal's native counter fold) and two walkable roof cells.
- Shelves, two cells tall and stacked in every placement: the yellow-goods and red-goods shelves (92 each), the plain
  shelf (51), the blue refrigerated display (64), the blue glass cabinet on the east wall (48), the Celadon 6F vending
  machines (8): a full-height base with recessed shelves or glass and a shorter top with the upper shelf and the cream
  sign.
- Glass panes `104-105-108-109` (112): windows in the wall row and the glass display cases of the 5F counter: one model
  serves both, a full block with the glass recessed and a sill; the cases' sign band behind them.
- Celadon 1F: the tall plant in its pink planter (stack), the directory board (wall row), the counter's end, and the
  lobby benches: each is one bench lying north-south across two cells (two cushions on a frame, legs at the south end);
  its top's link stacked the north half on the south half, a false vertical stack (amendment, as the beds).
- Goldenrod roof: the south railing along the cell's north edge (the blue beyond the edge is not modelled), the east
  railing and the coin binoculars on it, the hedge planters at the north edge, the bush planters, the ticket machine,
  the raised flower bed with its water channel, the stairwell (a warp cell: kept, its WARP_MODELS.md model).
"""
from __future__ import annotations

import numpy as np

from tools.shape_fit.crystal_pass import furniture as F
from tools.shape_fit.crystal_pass import interiors as I
from tools.shape_fit.crystal_pass.common import K, Pass
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, front_rect, side_rect, top_rect

S = (16, 16, 16)
DARK, CREAM = I.DARK, I.CREAM
GREY, GREY_D = I.GREY, I.GREY_D
WOOD, WOOD_D = I.WOOD, I.WOOD_D
BLUE, BLUE_L, BLUE_M, BLUE_P = (74, 74, 255), (123, 132, 255), (115, 132, 255), (140, 156, 255)
PINK, RED, YELLOW, ORANGE = (255, 156, 197), (247, 82, 49), (255, 255, 58), (255, 132, 8)
GREEN, GREEN_D, GREEN_L = (123, 165, 8), (74, 107, 0), (148, 197, 74)
FULL = front_rect((0, 0, 16, 16), (0, 16), (0, 16))

NEVER = {K(k): (label, why) for k, label, why in [
    ("133-163-135-136", "counter corner", "counter"), ("135-136-135-136", "counter side", "counter"),
    ("137-138-139-140", "counter with the register", "counter"), ("149-150-151-152", "counter end", "counter"),
    ("150-150-152-152", "counter front", "counter"), ("163-134-135-136", "counter corner", "counter"),
    ("163-163-152-152", "counter front", "counter"), ("205-206-209-156", "roof floor by the ticket machine", "walk"),
    ("207-208-156-156", "roof floor by the planters", "walk")]}
WHY = {"walk": "Every placement is a walkable cell and the entry has no walk or seat flag: never drawn (census: walk).",
       "counter": "Collision 144 is a counter: Crystal keeps the native counter fold and never draws this entry (census)."}
# (top, base, label, base shelf recess or glass, top sign rows)
SHELVES = [
    (K("172-173-175-176"), K("179-180-183-184"), "shelf of yellow goods", "shelf"),
    (K("174-134-177-178"), K("181-182-185-186"), "shelf of red goods", "shelf"),
    (K("187-188-175-176"), K("175-176-195-196"), "plain shelf", "shelf"),
    (K("187-188-189-190"), K("191-192-193-194"), "refrigerated display", "glass"),
    (K("164-165-166-167"), K("168-169-170-171"), "blue glass cabinet", "glass"),
    (K("187-188-212-213"), K("212-213-214-215"), "vending machine", "machine"),
]
PANE, DIRECTORY, COUNTER_END = K("104-105-108-109"), K("111-112-115-116"), K("150-153-152-154")
CASE_SIGN = (K("135-136-187-188"), K("31-31-187-188"))
PLANT_TOP, PLANT = K("141-142-143-144"), K("145-146-147-148")
BENCH_N, BENCH_S = K("125-126-127-128"), K("129-130-131-132")
RAIL_S = {K("231-232-217-217"): "west end", K("232-232-217-217"): "middle", K("232-237-217-217"): "east end"}
RAIL_E, BINOCULARS = K("156-238-156-238"), K("233-234-235-236")
HEDGE = (K("197-198-201-202"), K("199-200-203-204"))
BUSH_PLANTER = (K("246-247-250-251"), K("248-249-251-252"))
TICKET, STAIRS = K("225-226-227-228"), K("242-243-244-245")
BED = {K("257-220-258-259"): "nw", K("220-220-254-254"): "n", K("220-253-254-255"): "ne",
       K("258-256-258-256"): "w", K("256-256-110-110"): "s", K("256-255-110-255"): "se"}
OWNED = [*NEVER, *[k for t, b, _, _ in SHELVES for k in (t, b)], PANE, DIRECTORY, COUNTER_END, *CASE_SIGN, PLANT_TOP, PLANT,
         BENCH_N, BENCH_S, *RAIL_S, RAIL_E, BINOCULARS, *HEDGE, *BUSH_PLANTER, TICKET, STAIRS, *BED]


def shelf_base(key, kind):
    """A shelf unit's base, the full cell height: its drawn front; the upper shelf (rows 1-7) a real recess with the
    goods on its back, or a recessed glass door, or the vending machine's panel proud."""
    c = Cell(key)
    b = Build(c)
    mats = [GREY_D, GREY, DARK] if kind != "glass" else [BLUE, BLUE_L, DARK]
    b.add(box(S, (1, 15), (0, 16), (3, 15)), top=[GREY, CREAM, BLUE_L], front=front_rect((1, 0, 15, 16), (1, 15), (0, 16)), side=mats)
    if kind == "shelf":
        b.cut(box(S, (2, 14), (9, 15), (12, 15)))
        b.add(box(S, (2, 14), (9, 15), (12, 13)), top=[GREY_D, DARK], front=front_rect((2, 1, 14, 7), (2, 14), (9, 15)), side=mats)
    elif kind == "glass":
        b.cut(box(S, (3, 13), (2, 15), (13, 15)))
        b.add(box(S, (3, 13), (2, 15), (13, 14)), top=mats, front=front_rect((3, 0, 13, 13), (3, 13), (2, 15)), side=mats)
    else:
        b.add(box(S, (3, 13), (8, 15), (15, 16)), top=[GREY], front=front_rect((3, 1, 13, 8), (3, 13), (8, 15)), side=[GREY_D, DARK])
    return b.done()


def shelf_top(key, kind):
    """The unit's upper part, stacked on its base: the upper shelf / glass (drawn rows 8-15) and the cream sign or top
    surface (rows 0-7) on its top, 10 voxels tall."""
    c = Cell(key)
    b = Build(c)
    mats = [GREY_D, GREY, DARK] if kind != "glass" else [BLUE, BLUE_L, DARK]
    b.add(box(S, (1, 15), (0, 10), (3, 15)), top=top_rect((1, 0, 15, 7), (1, 15), (3, 15)),
          front=front_rect((1, 8, 15, 16), (1, 15), (0, 10)), side=mats)
    if kind == "shelf":
        b.cut(box(S, (2, 14), (1, 8), (12, 15)))
        b.add(box(S, (2, 14), (1, 8), (12, 13)), top=[GREY_D, DARK], front=front_rect((2, 9, 14, 15), (2, 14), (1, 8)), side=mats)
    return b.done()


def pane(key):
    """A glass pane: a full block (a window in the wall row, a display case on the 5F) with the glass recessed two
    voxels in its dark frame and the sill (drawn rows 12-14) proud."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 16), (0, 15)), top=[DARK, BLUE_M], front=FULL, side=[DARK, BLUE_M])
    b.cut(box(S, (1, 14), (4, 16), (13, 15)))
    b.add(box(S, (1, 14), (4, 16), (13, 14)), top=[BLUE_P], front=FULL, side=[BLUE_P])
    b.add(box(S, (0, 15), (2, 4), (15, 16)), top=[DARK], front=front_rect((0, 12, 16, 15), (0, 16), (2, 4)), side=[DARK])
    return b.done()


def case_sign(key, counter_side):
    """The 5F display cases' upper back: the sign band (drawn rows 8-15) on top of a block in the cell's south half;
    in its north half the U counter's side (one block, its cream top) or the carpet (floor)."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 16), (8, 16)), top=top_rect((0, 8, 16, 16), (0, 16), (8, 16)), front=[CREAM, GREY], side=[CREAM, GREY])
    if counter_side:
        b.add(box(S, (0, 16), (0, 8), (0, 8)), top=top_rect((0, 0, 16, 8), (0, 16), (0, 8)), front=[CREAM], side=[CREAM, DARK])
    return b.done()


def tall_plant():
    """The 1F's ornamental plant: a pink planter (drawn rows 9-14 of the base) with a tall leafy column rising through
    the stacked top; leaves take each cell's own greens."""
    occ = np.zeros((32, 16, 16), bool)
    sh = occ.shape
    planter = box(sh, (2, 14), (0, 7), (3, 13))
    leaves = (F.ellipsoid(sh, (8, 17, 8), (6, 11, 5)) | F.ellipsoid(sh, (5, 12, 8), (3, 4.5, 3.5)) | F.ellipsoid(sh, (11, 22, 8), (3, 5, 3.5)))
    leaves &= np.indices(sh)[0] >= 7
    occ |= planter | leaves
    out = {}
    for key, y0 in ((PLANT, 0), (PLANT_TOP, 16)):
        c = Cell(key)
        have = {tuple(int(a) for a in px) for px in c.pixels.reshape(-1, 3)}
        greens = [g for g in (GREEN_L, GREEN, GREEN_D) if g in have]
        part = occ[y0:y0 + 16]
        paint = np.zeros(part.shape, np.uint8)
        for y, z, x in np.argwhere(part):
            gy = y0 + y
            if planter[gy, z, x]:
                paint[y, z, x] = c.pick([RED, PINK], x + .5, 12)
            else:
                v = min(15, max(0, 15 - y))
                want = tuple(int(a) for a in c.pixels[v, x])
                paint[y, z, x] = c.pick(([want] if want in greens else []) + greens, x + .5, v + .5)
        out[key] = (part.copy(), paint)
    return out


def bench():
    """The lobby bench lying north-south: a frame on legs at both ends, two pink cushions (each cell's own drawing on
    top), one block high; sliced into its north and south cells."""
    occ = np.zeros((16, 32, 16), bool)
    role = np.zeros(occ.shape, np.uint8)
    sh = occ.shape

    def put(m, r):
        occ[m] = True
        role[m] = r
    put(box(sh, (1, 15), (3, 5), (1, 31)), 1)
    for z in ((1, 3), (29, 31)):
        for x in ((1, 3), (13, 15)):
            put(box(sh, x, (0, 3), z), 1)
    put(box(sh, (1, 15), (5, 8), (1, 15)) | box(sh, (1, 15), (5, 8), (17, 30)), 2)
    up = F.exposed(occ)["up"]
    out = {}
    for key, z0 in ((BENCH_N, 0), (BENCH_S, 16)):
        c = Cell(key)
        part, r, u = occ[:, z0:z0 + 16], role[:, z0:z0 + 16], up[:, z0:z0 + 16]
        p = np.zeros(part.shape, np.uint8)
        for y, z, x in np.argwhere(part):
            if r[y, z, x] == 2 and u[y, z, x]:
                p[y, z, x] = z * 16 + x
            elif r[y, z, x] == 2:
                p[y, z, x] = c.pick([RED, PINK], x + .5, z + .5)
            else:
                p[y, z, x] = c.pick([DARK, RED], x + .5, z + .5)
        out[key] = (part.copy(), p)
    return out


def rail_south(key, end):
    """The roof's south railing along the cell's north edge (drawn rows 0-6); the blue beyond the edge is not modelled."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 10), (1, 4)), top=[GREY, DARK], front=front_rect((0, 0, 16, 7), (0, 16), (0, 10)), side=[GREY_D, DARK])
    if end != "middle":
        x = (0, 3) if end == "west end" else (13, 16)
        b.add(box(S, x, (0, 12), (0, 5)), top=[GREY], front=[GREY, DARK], side=[GREY_D, DARK])
    return b.done()


def rail_east(key, binoculars=False):
    """The roof's east railing running north-south (drawn columns 10-14), with a coin binocular stand west of it."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (11, 14), (0, 10), (0, 16)), top=[GREY, DARK], front=[GREY_D, DARK], side=side_rect((10, 0, 15, 16), (0, 16), (0, 10)))
    for pz in (3, 11):
        b.add(box(S, (10, 15), (0, 12), (pz, pz + 2)), top=[GREY], front=[DARK, GREY_D], side=[DARK, GREY_D])
    if binoculars:
        b.add(F.cylinder(S, (5, 11), 1.5, (0, 8)), top=[GREY], front=[GREY_D, DARK], side=[GREY_D, DARK])
        b.add(box(S, (2, 9), (8, 12), (8, 13)), top=top_rect((0, 0, 8, 7), (2, 9), (8, 13)), front=front_rect((0, 0, 8, 7), (2, 9), (8, 12)),
              side=[GREY, GREY_D])
    return b.done()


def hedge(key):
    """At the roof's north edge: a planter box with a clipped hedge (drawn rows 6-15) and the railing behind it."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 12), (0, 2)), top=[CREAM, GREY], front=front_rect((0, 0, 16, 6), (0, 16), (0, 12)), side=[GREY, DARK])
    b.add(box(S, (0, 16), (0, 5), (3, 15)), top=[WOOD_D, DARK], front=[GREY_D, DARK], side=[GREY_D, DARK])
    b.add(box(S, (1, 15), (5, 11), (4, 14)), top=top_rect((0, 6, 16, 16), (1, 15), (4, 14)), front=front_rect((0, 6, 16, 16), (1, 15), (5, 11)),
          side=[GREEN, GREEN_D])
    return b.done()


def bush_planter(key):
    """A bush (drawn rows 0-7) in an orange planter box with its yellow flowers (rows 8-14)."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 7), (3, 14)), top=[ORANGE], front=front_rect((1, 8, 15, 15), (1, 15), (0, 7)), side=[ORANGE, YELLOW])
    b.add(F.ellipsoid(S, (8, 9.5, 8.5), (6.5, 4, 5)) & (np.indices(S)[0] >= 7), top=top_rect((1, 0, 15, 8), (1.5, 14.5), (3.5, 13.5)),
          front=front_rect((1, 0, 15, 8), (1.5, 14.5), (7, 14)), side=[GREEN_D, GREEN])
    return b.done()


def ticket_machine():
    """The blue ticket machine standing in the cell's east half (drawn columns 6-15); its west half is floor."""
    c = Cell(TICKET)
    b = Build(c)
    b.add(box(S, (7, 15), (0, 14), (3, 13)), top=[BLUE_L, BLUE], front=front_rect((6, 0, 16, 16), (7, 15), (0, 14)), side=[BLUE, DARK])
    return b.done()


def flower_bed(key, edge):
    """The roof's raised bed: a rim two voxels thick on its drawn outer edges, six high, and the filling (water at the
    back, the flowers in front) showing the drawing on top, five high."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 5), (0, 16)), top=top_rect((0, 0, 16, 16), (0, 16), (0, 16)), front=[GREY_D, DARK, ORANGE], side=[GREY_D, DARK, ORANGE])
    rims = []
    if "n" in edge:
        rims.append(box(S, (0, 16), (0, 7), (2, 4)))
    if "w" in edge:
        rims.append(box(S, (2, 4), (0, 7), (0, 16)))
    if "e" in edge:
        rims.append(box(S, (12, 14), (0, 7), (0, 16)))
    if "s" in edge:
        rims.append(box(S, (0, 16), (0, 7), (12, 14)))
    for r in rims:
        b.add(r, top=[GREY, CREAM, ORANGE], front=[GREY_D, DARK, ORANGE], side=[GREY_D, DARK, ORANGE])
    return b.done()


def stairs():
    c = Cell(STAIRS)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 1), (0, 16)), top=top_rect((0, 0, 16, 16), (0, 16), (0, 16)), front=[GREY_D, DARK], side=[GREY_D, DARK])
    return b.done()


def main():
    out = Pass("mart")
    for key, (label, why) in NEVER.items():
        out.keep(key, label=label, family="mart-never-drawn", reason=WHY[why])
    for top, base, label, kind in SHELVES:
        out.cell(base, *shelf_base(base, kind), label=f"{label}, base", family="mart-shelf",
                 reason="A shelf unit's full-height base: " + {"shelf": "the upper shelf a real recess with the goods on its back",
                                                               "glass": "its glass door recessed in its frame",
                                                               "machine": "its button panel proud"}[kind] + ".")
        out.cell(top, *shelf_top(top, kind), label=f"{label}, top (stacked)", family="mart-shelf",
                 reason="Stacked on its base in every placement: the unit's upper part with its sign on top, 10 voxels.")
        out.entity(f"crystal_mart_{label.replace(' ', '_')}", [[top], [base]], f"Mart {label} (stack)", kind="stack (model as one)")
    out.cell(PANE, *pane(PANE), label="glass pane: window (wall row) or display case", family="mart-glass",
             reason="One model for both uses (112 placements): a full block with the glass recessed in its frame and the sill "
                    "proud; it keeps the wall closed in the wall row and reads as a glass case on the 5F.")
    for key, side in zip(CASE_SIGN, (True, False)):
        out.cell(key, *case_sign(key, side), label="5F display case sign" + (" and counter side" if side else ""), family="mart-glass",
                 reason="The display cases' upper back with its sign band on top; north of it the U counter's side or the carpet.")
    out.entity("crystal_celadon_5f_cases", [list(CASE_SIGN) + [CASE_SIGN[1], CASE_SIGN[0]], [PANE, PANE, PANE, PANE]],
               "Celadon 5F display cases")
    out.cell(DIRECTORY, *I.wall_relief(DIRECTORY, background=[DARK], depth=1, side=[GREY_D, DARK]), label="directory board (wall row)",
             family="mart-wall", reason="Wall row, drawn in place: the wall block with the directory board a voxel proud.")
    out.cell(COUNTER_END, *counter_end(), label="counter end (solid)", family="mart-counter",
             reason="Where the counter's end is solid (13 of 17): a counter one block high, cream top, brown front.")
    for key, parts in tall_plant().items():
        out.cell(key, *parts, label="Celadon tall plant " + ("(planter)" if key == PLANT else "(stacked)"), family="mart-plant",
                 reason="One plant across its stacked cells: a pink planter and a tall leafy column of overlapping lobes.")
    out.entity("crystal_celadon_tall_plant", [[PLANT_TOP], [PLANT]], "Celadon tall plant (stack)", kind="stack (model as one)")
    parts = bench()
    out.cell(BENCH_N, *parts[BENCH_N], label="lobby bench, north half", family="mart-bench",
             reason="Corrected false stack (amendment): the bench lies north-south; frame, legs and the first cushion.")
    out.drop_stack(BENCH_N, "CELADON_DEPT_STORE_1F (2,4)/(2,5), (2,6)/(2,7), (4,4)/(4,5), (4,6)/(4,7): two cushions on one frame "
                            "drawn top-down across two rows, legs at the south end: a bench lying flat, not a tall box.")
    out.cell(BENCH_S, *parts[BENCH_S], label="lobby bench, south half", family="mart-bench",
             reason="The same bench's south half: the second cushion and the legs.")
    out.entity("crystal_celadon_bench", [[BENCH_N], [BENCH_S]], "Celadon lobby bench (1x2)")
    for key, end in RAIL_S.items():
        out.cell(key, *rail_south(key, end), label=f"roof south railing ({end})", family="roof",
                 reason="The railing along the roof's edge; the blue beyond it is not modelled.")
    out.entity("crystal_roof_south_railing", [list(RAIL_S)], "Goldenrod roof south railing")
    out.cell(RAIL_E, *rail_east(RAIL_E), label="roof east railing", family="roof",
             reason="The railing running north-south along the roof's east edge, posts at its drawn joints.")
    out.cell(BINOCULARS, *rail_east(BINOCULARS, binoculars=True), label="coin binoculars at the east railing", family="roof",
             reason="The east railing with a coin binocular stand beside it.")
    for key in HEDGE:
        out.cell(key, *hedge(key), label="hedge planter at the north edge", family="roof",
                 reason="A planter with a clipped hedge and the railing behind it (wall row, drawn in place: the edge stays closed).")
    for key in BUSH_PLANTER:
        out.cell(key, *bush_planter(key), label="bush planter", family="roof",
                 reason="A rounded bush in an orange planter box with its yellow flowers.")
    out.cell(TICKET, *ticket_machine(), label="ticket machine", family="roof",
             reason="The blue ticket machine standing in the cell's east half; the floor beside it stays floor.")
    for key, edge in BED.items():
        out.cell(key, *flower_bed(key, edge), label=f"raised flower bed ({edge})", family="roof",
                 reason="The raised bed: a rim on its drawn outer edges and the filling with the water and flowers on top.")
    out.entity("crystal_roof_flower_bed", [[K("257-220-258-259"), K("220-220-254-254"), K("220-220-254-254"), K("220-253-254-255")],
                                           [K("258-256-258-256"), K("256-256-110-110"), K("256-256-110-110"), K("256-255-110-255")]],
               "Goldenrod roof raised flower bed")
    out.keep_warp(STAIRS, label="roof stairwell (warp)", family="roof")
    out.write(owned=OWNED)


def counter_end():
    c = Cell(COUNTER_END)
    b = Build(c)
    b.add(box(S, (0, 15), (0, 8), (0, 14)), top=top_rect((0, 0, 16, 8), (0, 16), (0, 14)), front=front_rect((0, 8, 16, 16), (0, 16), (0, 8)),
          side=[WOOD_D, CREAM, DARK])
    return b.done()


if __name__ == "__main__":
    main()
