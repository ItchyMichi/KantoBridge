"""Crystal checkpoint 17: TILESET_ELITE_FOUR_ROOM_1, TILESET_ELITE_FOUR_ROOM_2 and TILESET_CHAMPIONS_ROOM_1 (the Johto
gyms' shared pieces, the Elite Four and Lance's rooms, the Goldenrod Bike Shop, Olivine Gym, the Underground switch
room entrances).

Source reading (study/groups/TILESET_*; the gym maps):

- The gym statue: a red-and-white Poke Ball standing on its pedestal (stacks; the shared `interiors.statue_pedestal`).
- Brick planters with a bush or red flowers (180 placements), Koga's and Azalea's two-cell bush, Olivine's gold boulders
  (62), Violet's stone posts, Blackthorn's low blue ledges over the red floor, its dragon heads, the pits (Blackthorn 2F)
  and walkway drops (Violet), drawn on floor-level plates.
- Lance's room: the gold dragon pillars flanking the door (the head a full block in the wall row, the pillar below), the
  grey dragon-head statues on their pedestals (stacks), the red-ball braziers (upright cutouts), the platform's steps.
- The Bike Shop: the bicycles as upright cutouts (their side or head-on view a thin card painted from the drawing),
  the parts shelf (wall-row blocks).
- The switch room entrances: brick planters, the switch boxes, the blue wall machine.
- Stairway warps are kept (their WARP_MODELS.md models); never-drawn cells (counters, walkable floor) are retained.
"""
from __future__ import annotations

import numpy as np

from tools.shape_fit.crystal_pass import furniture as F
from tools.shape_fit.crystal_pass import interiors as I
from tools.shape_fit.crystal_pass.common import K, Pass
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, front_rect, top_rect

S = (16, 16, 16)
DARK, CREAM = I.DARK, I.CREAM
GREY, GREY_D = (156, 156, 156), (107, 107, 107)
GOLD, GOLD_D, GOLD_L = (173, 140, 58), (132, 107, 25), (214, 197, 140)
G, G_D, G_L = (123, 165, 8), (74, 107, 0), (148, 197, 74)
BLUE, BLUE_L = (74, 74, 255), (123, 132, 255)
TOPDRAW = top_rect((0, 0, 16, 16), (0, 16), (0, 16))
FULL = front_rect((0, 0, 16, 16), (0, 16), (0, 16))
FAMILIES = ["TILESET_CHAMPIONS_ROOM_1", "TILESET_ELITE_FOUR_ROOM_1", "TILESET_ELITE_FOUR_ROOM_2"]

BALL_TOPS, PEDESTAL = [K("38-39-40-41"), K("38-39-2843-2844")], K("42-43-44-45")
PLANTERS = {K("9-10-11-12"): "bush", K("7-8-11-12"): "flowers", K("3119-3120-3123-3124"): "flowers", K("3121-3122-3123-3124"): "bush"}
BUSH_L, BUSH_R = K("48-49-51-52"), K("50-20-53-20")
BOULDER = K("2818-2819-1771-1772")
POST = K("83-84-85-86")
LEDGES = [K("30-30-31-31"), K("30-30-31-36")]
DRAGON_HEAD = K("79-80-81-82")
PIT = K("14-14-15-15")
LANCE_HEAD, LANCE_PILLAR = K("2873-2874-2875-2876"), K("2877-2878-2877-2878")
LANCE_STATUE, LANCE_PEDESTAL = K("2899-2900-2901-2902"), K("2903-2904-2905-2906")
BRAZIERS = [K("2916-96-2917-2842"), K("96-2916-96-2917")]
STEPS = [K(k) for k in ("2889-2890-2889-2890", "2892-2893-2892-2893", "2889-2909-2910-2911", "2912-2893-2911-2913", "2918-2918-2911-2911")]
BIKES = [K("2759-2760-2763-2764"), K("2761-2762-2765-2766"), K("2769-2770-2771-2772")]
WALL_BIKE = K("2780-2781-2783-2784")
SHELVES = [K("2801-2802-2804-2805"), K("2802-2803-2805-2806")]
SWITCH_BOX, WALL_MACHINE = K("3128-3129-3130-3131"), K("3195-3196-3138-3138")
STAIRS = [K("66-67-68-69"), K("70-71-72-73"), K("3172-3173-3174-3175"), K("3176-3177-3178-3179")]
RETAINED = {K("2767-2768-2767-2767"): "walk", K("2840-2841-96-96"): "walk", K("2854-2855-2854-2855"): "walk",
            K("2914-2914-2915-2915"): "walk", K("1-2-1-2"): "walk", K("3-1-3-1"): "walk", K("54-55-20-20"): "walk",
            K("56-20-20-20"): "walk", K("3163-3164-3165-3166"): "walk", K("2788-2789-2790-2791"): "counter",
            K("2792-2793-2792-2793"): "counter", K("2792-2793-2795-2796"): "counter", K("2794-2794-2797-2797"): "counter"}


def own(key, n=4):
    from collections import Counter
    c = Cell(key)
    return [col for col, _ in Counter(tuple(int(a) for a in px) for px in c.pixels.reshape(-1, 3)).most_common(n)]


def same_pixel(y, z, x):
    """An upright cutout's paint: the drawing's pixel at the voxel's column and height (row 15 at the floor)."""
    return x + .5, 15 - y + .5


def cutout(key, background, *, thick=(6, 10), close=True):
    """An upright cutout: the drawing's non-background pixels as a thin card standing in the cell (its side view: row r
    at height 15 - r), every face painted with the drawing's pixel there; background pixels mostly enclosed by the
    figure are filled so it has no pinholes."""
    c = Cell(key)
    px = c.pixels.astype(int)
    bg = np.array([[tuple(px[r, x]) in background for x in range(16)] for r in range(16)])
    fig = ~bg
    if close:
        filled = fig.copy()
        for r in range(16):
            for x in range(16):
                if bg[r, x]:
                    n = sum(fig[rr, xx] for rr, xx in ((r - 1, x), (r + 1, x), (r, x - 1), (r, x + 1)) if 0 <= rr < 16 and 0 <= xx < 16)
                    filled[r, x] = n >= 3
        fig = filled
    occ = np.zeros(S, bool)
    for r in range(16):
        for x in range(16):
            if fig[r, x]:
                occ[15 - r, thick[0]:thick[1], x] = True
    b = Build(c)
    b.add(occ, top=same_pixel, front=same_pixel, side=same_pixel)
    return b.done()


def ball():
    """The gym statue's Poke Ball standing on the pedestal (stacked): a sphere showing the drawn ball."""
    def make(key):
        c = Cell(key)
        b = Build(c)
        b.add(F.ellipsoid(S, (8, 6.5, 8), (6.5, 6.5, 6.5)), top=top_rect((1, 0, 15, 8), (1.5, 14.5), (1.5, 14.5)),
              front=front_rect((1, 0, 15, 15), (1.5, 14.5), (0, 13)), side=own(key))
        return b.done()
    return make


def planter(key, kind):
    """A brick planter (its front drawn rows 8-15) with a bush or red flowers over it (rows 0-7)."""
    c = Cell(key)
    b = Build(c)
    brick = [GOLD, GOLD_D, GOLD_L, DARK]
    b.add(box(S, (1, 15), (0, 7), (2, 14)), top=brick, front=front_rect((1, 8, 15, 16), (1, 15), (0, 7)), side=brick)
    occ = F.ellipsoid(S, (8, 10, 8), (6.8, 3.6 if kind == "bush" else 3, 6))
    b.add(occ, top=top_rect((1, 0, 15, 8), (1.2, 14.8), (2, 14)), front=front_rect((1, 0, 15, 8), (1.2, 14.8), (7, 13.6)), side=own(key))
    return b.done()


def bush(key, left):
    """Half of the two-cell bush: one rounded mound across the seam (its middle on the left cell's east edge)."""
    c = Cell(key)
    b = Build(c)
    cx = 14 if left else -2
    occ = F.ellipsoid(S, (cx, 6.5, 8), (12, 6.5, 7))
    b.add(occ, top=TOPDRAW, front=front_rect((0, 4, 16, 16), (0, 16), (0, 13)), side=[G, G_D, G_L])
    return b.done()


def boulder():
    """Olivine's gold boulder: a rounded rock showing its drawing on top."""
    c = Cell(BOULDER)
    b = Build(c)
    occ = F.ellipsoid(S, (8, 6, 8), (7.6, 9, 7.6)) & (np.indices(S)[0] < 14)
    b.add(occ, top=TOPDRAW, front=FULL, side=own(BOULDER))
    return b.done()


def post():
    c = Cell(POST)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 12), (1, 15)), top=TOPDRAW, front=own(POST), side=own(POST))
    return b.done()


def ledge(key):
    """Blackthorn's low blue ledge along the north (its top: drawn rows 0-2, its brick face rows 3-7) over the red floor
    (rows 8-15) as a plate."""
    c = Cell(key)
    b = Build(c)
    blue = [BLUE, BLUE_L, DARK]
    b.add(box(S, (0, 16), (0, 6), (0, 5)), top=top_rect((0, 0, 16, 3), (0, 16), (0, 5)), front=front_rect((0, 3, 16, 8), (0, 16), (0, 6)), side=blue)
    b.add(box(S, (0, 16), (0, 1), (5, 16)), top=top_rect((0, 8, 16, 16), (0, 16), (5, 16)), front=own(key), side=own(key))
    return b.done()


def plate(key):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 1), (0, 16)), top=TOPDRAW, front=own(key), side=own(key))
    return b.done()


def full(key):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 16), (0, 16)), top=own(key), front=FULL, side=own(key))
    return b.done()


def pillar():
    """Lance's gold dragon pillar below its head: a column with its drawn body on the face."""
    c = Cell(LANCE_PILLAR)
    b = Build(c)
    b.add(box(S, (2, 14), (0, 16), (2, 14)), top=[GOLD, GOLD_D], front=front_rect((1, 0, 15, 16), (2, 14), (0, 16)), side=[GOLD, GOLD_D, DARK, GOLD_L])
    return b.done()


def step(key):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 4), (0, 16)), top=TOPDRAW, front=own(key), side=own(key))
    return b.done()


def switch_box():
    c = Cell(SWITCH_BOX)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 10), (1, 15)), top=TOPDRAW, front=own(SWITCH_BOX), side=own(SWITCH_BOX))
    return b.done()


def main():
    out = Pass("gyms")
    make_ball = ball()
    out.cell(PEDESTAL, *I.statue_pedestal(PEDESTAL, stone=(GREY, GREY_D, DARK)), label="gym statue pedestal", family="gym-statues",
             reason="The gym statue's pedestal, the shared builder (the Poke Ball stacks on it).")
    for key in BALL_TOPS:
        out.cell(key, *make_ball(key), label="gym statue Poke Ball (stacked)", family="gym-statues",
                 reason="The red-and-white Poke Ball standing on the pedestal: a sphere showing the drawn ball.")
    out.entity("crystal_gym_statue", [[BALL_TOPS[0]], [PEDESTAL]], "Gym statue (stack)", kind="stack (model as one)")
    for key, kind in PLANTERS.items():
        out.cell(key, *planter(key, kind), label=f"brick planter ({kind})", family="gym-plants",
                 reason=f"A brick planter with {'a bush' if kind == 'bush' else 'red flowers'} over it.")
    out.cell(BUSH_L, *bush(BUSH_L, True), label="two-cell bush (west)", family="gym-plants", reason="Half of a rounded two-cell bush.")
    out.cell(BUSH_R, *bush(BUSH_R, False), label="two-cell bush (east)", family="gym-plants", reason="The bush's east half.")
    out.entity("crystal_gym_bush", [[BUSH_L, BUSH_R]], "Two-cell bush")
    out.cell(BOULDER, *boulder(), label="gold boulder", family="gym-rocks", reason="Olivine's gold boulder, a rounded rock (62 placements).")
    out.cell(POST, *post(), label="stone post", family="gym-rocks", reason="Violet's carved stone post, its drawing on top.")
    for key in LEDGES:
        out.cell(key, *ledge(key), label="Blackthorn ledge over the red floor", family="gym-blackthorn",
                 reason="The low blue ledge along the north with its brick face, the red floor as a plate.")
    out.cell(DRAGON_HEAD, *I.statue_figure(DRAGON_HEAD, stone=tuple(own(DRAGON_HEAD, 3))), label="Blackthorn dragon head", family="gym-blackthorn",
             reason="The blue dragon head as a rounded statue, its drawn face to the south.")
    out.cell(PIT, *plate(PIT), label="pit / walkway drop", family="gym-blackthorn",
             reason="Blackthorn 2F's pits and Violet's walkway drops: a floor-level plate showing the drawn dark edge.")
    out.cell(LANCE_HEAD, *full(LANCE_HEAD), label="Lance's dragon pillar head (wall row)", family="gym-lance",
             reason="The gold dragon's head filling its wall-row cell: a full block with its drawing.")
    out.cell(LANCE_PILLAR, *pillar(), label="Lance's dragon pillar", family="gym-lance", reason="The gold pillar below the head.")
    out.entity("crystal_gym_lance_pillar", [[LANCE_HEAD], [LANCE_PILLAR]], "Lance's dragon pillar")
    out.cell(LANCE_PEDESTAL, *I.statue_pedestal(LANCE_PEDESTAL, stone=tuple(own(LANCE_PEDESTAL, 3))), label="dragon statue pedestal",
             family="gym-lance", reason="The statue's pedestal, the shared builder.")
    out.cell(LANCE_STATUE, *I.statue_figure(LANCE_STATUE, stone=tuple(own(LANCE_STATUE, 3))), label="dragon head statue (stacked)",
             family="gym-lance", reason="The grey dragon head, the shared statue figure, stacked on its pedestal.")
    out.entity("crystal_gym_lance_statue", [[LANCE_STATUE], [LANCE_PEDESTAL]], "Lance's hall statue (stack)", kind="stack (model as one)")
    for key in BRAZIERS:
        out.cell(key, *cutout(key, {CREAM, GREY}), label="red-ball brazier (cutout)", family="gym-lance",
                 reason="The red ball on its blue stand as an upright cutout of the drawing.")
    for key in STEPS:
        out.cell(key, *step(key), label="platform step", family="gym-lance", reason="The platform's edge as a low step, its drawn top.")
    for key in BIKES:
        out.cell(key, *cutout(key, {CREAM, GREY}), label="bicycle (cutout)", family="gym-bike-shop",
                 reason="The bicycle's side view as an upright cutout card painted from the drawing.")
    out.entity("crystal_gym_bike", [[BIKES[0], BIKES[1]]], "Bicycle")
    out.cell(WALL_BIKE, *cutout(WALL_BIKE, {GREY, CREAM}), label="bicycle seen head-on (cutout)", family="gym-bike-shop",
             reason="A bicycle standing head-on in front of the wall (row 1, not the wall row): an upright cutout of the drawing.")
    for key in SHELVES:
        out.cell(key, *full(key), label="parts shelf (wall row)", family="gym-bike-shop",
                 reason="The parts shelf filling its wall-row cell: a full block with its drawing.")
    out.cell(SWITCH_BOX, *switch_box(), label="switch box", family="gym-switch-room", reason="The grey switch box, its drawing on top.")
    out.cell(WALL_MACHINE, *full(WALL_MACHINE), label="wall machine", family="gym-switch-room",
             reason="The blue machine filling its wall-row cell: a full block with its drawing.")
    for key in STAIRS:
        out.keep_warp(key, label="stairway (warp)", family="gym-warps")
    for key, why in RETAINED.items():
        out.keep(key, label=f"{why} (never drawn)", family="gym-retained",
                 reason="A counter cell: Crystal never draws fitted counters." if why == "counter"
                 else "A walkable cell with no walk or seat model: never drawn, its entry is kept.")
    out.write(families=FAMILIES)


if __name__ == "__main__":
    main()
