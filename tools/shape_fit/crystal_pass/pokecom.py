"""Crystal checkpoint 8: TILESET_POKECOM_CENTER_1 (the Pokecom Center admin office, one map; every key drawn).

Source reading (study/groups/TILESET_POKECOM_CENTER_1; composites _pokecom_atrium/_machines/_office/_corner):

- The atrium's central machine (6x11 cells): a big static display at the back (its top in the wall row: full blocks),
  a raised blue platform with the machinery window recessed into its middle, slanted glass guards down both sides,
  and in front four terminals (a monitor on its stand over a keypad desk) between glass corner pieces; blue frame
  pieces around it.
- The machines booth: two trade machines (top stacked on its base) inside an L-shaped blue partition (the same
  platform pieces at partition height).
- The Center-style corner: a terminal monitor and a PC top (stacked on the Center's bases), the counter with its Poke
  Balls and its ends, the signboard on its stand.
- Waiting seats (12), potted plants (one-cell, two-cell: the tall plant's top also stands on the urn variant, alsoOn),
  the office's ledge under the windows, computer desks and plants.
"""
from __future__ import annotations

import numpy as np

from tools.shape_fit.crystal_pass import furniture as F
from tools.shape_fit.crystal_pass import interiors as I
from tools.shape_fit.crystal_pass import pokecenter as PC
from tools.shape_fit.crystal_pass.common import K, Pass
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, front_rect, side_rect, top_rect

S = (16, 16, 16)
DARK, CREAM = I.DARK, I.CREAM
GREY, GREY_D = I.GREY, I.GREY_D
WOOD, WOOD_D = I.WOOD, I.WOOD_D
BLUE, BLUE_L, BLUE_P = (115, 132, 255), (140, 156, 255), (140, 156, 255)  # this map's blues
RED, ORANGE, YELLOW = (247, 82, 49), (255, 165, 8), (255, 214, 173)  # this map's seat orange and peach
GREEN, GREEN_D, GREEN_L = (123, 165, 8), (74, 107, 0), (148, 197, 74)
FULL = PC.FULL
TOPDRAW = top_rect((0, 0, 16, 16), (0, 16), (0, 16))

SCREEN = (K("4313-4313-4362-4362"), K("4362-4362-4362-4362"), K("4362-4362-4312-4312"))
PLATFORM = [K(k) for k in ("4302-4313-4310-1123", "4313-4313-4404-4312", "4313-4313-4312-4319", "4313-4303-1123-4311",
                           "4310-1123-4310-1123", "1123-4311-1123-4311", "4310-1123-4304-4312", "4366-4313-4312-4312",
                           "4313-4367-4312-4312", "1123-4311-4312-4305", "4313-4313-4312-4312",
                           "4313-4313-4319-1123")]
SLOT_WEST = [K(k) for k in ("4311-4363-4311-4365", "4311-4368-4311-4370", "4311-4405-4311-4365")]
SLOT_EAST = [K(k) for k in ("4364-4310-4316-4310", "4369-4310-4371-4310", "4406-4310-4407-4310")]
GUARD_WEST = [K(k) for k in ("4324-4325-4326-4327", "4324-4325-4326-4328", "4415-4325-4326-4327")]
GUARD_EAST = [K(k) for k in ("4329-4330-4331-4332", "4329-4330-4333-4332", "4329-4416-4331-4332")]
FRAME_WALL = [K(k) for k in ("4274-4302-4274-4310", "4286-4310-4287-4310", "4303-4274-4311-4274", "4311-4286-4311-4287")]
FRAME_EAST, FRAME_WEST = K("4311-4275-4305-4273"), K("4380-4310-4381-4304")
MONITOR, KEYPAD = K("4340-4341-4344-4345"), K("4348-4349-4352-4353")
GLASS_CORNER = [K(k) for k in ("4338-4339-4342-4343", "4346-4347-4350-4351", "4354-4355-4356-4357", "4358-4359-4360-4361")]
WALL_MACHINE = K("4334-4335-4336-4337")
TRADE_TOP, TRADE = K("274-277-280-283"), K("4294-4295-4297-4298")
TERM_TOP, PC_TOP = K("4279-4280-4282-4283"), K("4274-4274-304-305")
BALLS = K("4292-4292-4287-4287")
COUNTER = [K(k) for k in ("4286-4292-4287-4276", "4303-4276-4311-4276", "4311-4276-4305-4276")]
LEDGE = K("4322-4323-4286-4286")
SIGN = K("4288-4289-4290-4291")
SEAT = K("4376-4377-4378-4379")
PLANT_ONE = K("4383-4384-4385-4386")
PLANT_TOP, PLANT_POT, PLANT_URN = K("4383-4384-4387-4388"), K("4387-4388-4385-4386"), K("4408-4409-4385-4386")
OFFICE_PLANT_TOP, OFFICE_PLANT = K("4275-4275-4383-4384"), K("4387-4388-4389-4390")
DESK_PC, DESK_KEYS = K("4393-4394-4395-4396"), K("4287-4287-4399-4400")
OWNED = [*SCREEN, *PLATFORM, *SLOT_WEST, *SLOT_EAST, *GUARD_WEST, *GUARD_EAST, *FRAME_WALL, FRAME_EAST, FRAME_WEST, MONITOR, KEYPAD, *GLASS_CORNER,
         WALL_MACHINE, TRADE_TOP, TRADE, TERM_TOP, PC_TOP, BALLS, *COUNTER, LEDGE, SIGN, SEAT, PLANT_ONE, PLANT_TOP, PLANT_POT,
         PLANT_URN, OFFICE_PLANT_TOP, OFFICE_PLANT, DESK_PC, DESK_KEYS]
MAT = [BLUE_L, BLUE_P, (115, 132, 255), BLUE, DARK, CREAM, GREY]
ATRIUM = [[K(k) for k in row.split()] for row in """
4274-4302-4274-4310 4313-4313-4362-4362 4313-4313-4362-4362 4313-4313-4362-4362 4313-4313-4362-4362 4303-4274-4311-4274
4286-4310-4287-4310 4362-4362-4362-4362 4362-4362-4362-4362 4362-4362-4362-4362 4362-4362-4362-4362 4311-4286-4311-4287
4380-4310-4381-4304 4362-4362-4312-4312 4362-4362-4312-4312 4362-4362-4312-4312 4362-4362-4312-4312 4311-4275-4305-4273
4415-4325-4326-4327 4302-4313-4310-1123 4313-4313-4404-4312 4313-4313-4312-4319 4313-4303-1123-4311 4329-4416-4331-4332
4324-4325-4326-4327 4310-1123-4310-1123 4311-4405-4311-4365 4406-4310-4407-4310 1123-4311-1123-4311 4329-4330-4331-4332
4324-4325-4326-4327 4310-1123-4310-1123 4311-4363-4311-4365 4364-4310-4316-4310 1123-4311-1123-4311 4329-4330-4331-4332
4324-4325-4326-4327 4310-1123-4310-1123 4311-4368-4311-4370 4369-4310-4371-4310 1123-4311-1123-4311 4329-4330-4331-4332
4324-4325-4326-4327 4310-1123-4310-1123 4311-4363-4311-4365 4364-4310-4316-4310 1123-4311-1123-4311 4329-4330-4331-4332
4324-4325-4326-4328 4310-1123-4304-4312 4366-4313-4312-4312 4313-4367-4312-4312 1123-4311-4312-4305 4329-4330-4333-4332
4338-4339-4342-4343 4340-4341-4344-4345 4340-4341-4344-4345 4340-4341-4344-4345 4340-4341-4344-4345 4354-4355-4356-4357
4346-4347-4350-4351 4348-4349-4352-4353 4348-4349-4352-4353 4348-4349-4352-4353 4348-4349-4352-4353 4358-4359-4360-4361
""".strip().splitlines()]  # G2_POKECOM_CENTER_ADMIN_OFFICE_MOBILE x 24-29, y 1-11


def platform(key, h=8):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, h), (0, 16)), top=TOPDRAW, front=front_rect((0, 8, 16, 16), (0, 16), (0, h)), side=MAT)
    return b.done()


def slot(key, west):
    """Platform cells around the machinery window: the window part (drawn columns 6-15 west of the seam, 0-9 east of it)
    recessed three voxels, its machinery on the recess floor."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 8), (0, 16)), top=TOPDRAW, front=MAT, side=MAT)
    x = (7, 16) if west else (0, 9)
    b.cut(box(S, x, (5, 8), (0, 16)))
    b.add(box(S, x, (4, 5), (0, 16)), top=TOPDRAW, front=MAT, side=MAT)
    return b.done()


RING_H = 6   # the machine's light-blue ring and console desks: the keypads' drawn south face is six rows


def _slope(t):
    """Height of a glass slope voxel column: 1 at the outer edge (t=0) rising to the ring at the rim (t=1)."""
    return np.where(t >= 1, RING_H, 1 + (RING_H - 1) * np.clip(t, 0, 1))


def _columns(heights):
    """An occupancy grid from a (z, x) height map."""
    yy = np.indices(S)[0]
    return yy < np.ceil(heights)[None, :, :]


def guard(key, west):
    """A side of the machine: the sloped glass band (drawn columns 0-12 west, 4-15 east, the hatch on its sloped top)
    rising from its outer edge to the ring at the rim (x 13 west, 3 east), and the narrow ring strip beyond it."""
    c = Cell(key)
    b = Build(c)
    zz, xx = np.indices((16, 16)) + .5
    t = xx / 13 if west else (16 - xx) / 13
    b.add(_columns(_slope(t)), top=TOPDRAW, front=MAT, side=MAT)
    return b.done()


def glass_corner(key, west, row):
    """A front corner of the machine: the glass band turning diagonally (its rim on u - z = 4 in the upper cell, u the
    distance from the outer side, its outer line on u - z = -24 counted from the upper cell), the ring inside it."""
    c = Cell(key)
    b = Build(c)
    zz, xx = np.indices((16, 16)) + .5
    u = xx if west else 16 - xx
    d = u - (zz + 16 * row)
    h = np.where(d < -24, 0, _slope((d + 24) / 28))
    b.add(_columns(h), top=TOPDRAW, front=MAT, side=MAT)
    return b.done()


def screen(key):
    """The atrium's big display: a full block (its top row stands in the wall row) showing the static on top and face."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 16), (0, 16)), top=TOPDRAW, front=FULL, side=MAT)
    return b.done()


def wall_block(key):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 16), (0, 16)), top=TOPDRAW, front=FULL, side=MAT)
    return b.done()


def monitor():
    """A front terminal's monitor standing behind its desk: the monitor's top (drawn rows 0-2) and its screen face
    (rows 3-11) above the desk, and the back of the console desk (rows 13-15) in front of it."""
    c = Cell(MONITOR)
    b = Build(c)
    b.add(box(S, (1, 15), (0, RING_H), (2, 12)), top=MAT, front=[DARK], side=[DARK, BLUE_L])
    b.add(box(S, (1, 15), (RING_H, 15), (2, 12)), top=top_rect((1, 0, 15, 3), (1, 15), (2, 12)),
          front=front_rect((1, 3, 15, 12), (1, 15), (RING_H, 15)), side=[BLUE_L, BLUE, DARK])
    b.add(box(S, (0, 16), (0, RING_H), (12, 16)), top=top_rect((0, 13, 16, 16), (0, 16), (12, 16)), front=MAT, side=MAT)
    return b.done()


def keypad():
    """The console desk in front of a monitor: its top with the keys (drawn rows 0-9), its south face (rows 10-15)."""
    c = Cell(KEYPAD)
    b = Build(c)
    b.add(box(S, (0, 16), (0, RING_H), (0, 16)), top=top_rect((0, 0, 16, 10), (0, 16), (0, 16)),
          front=front_rect((0, 10, 16, 16), (0, 16), (0, RING_H)), side=MAT)
    return b.done()


def frame_east():
    """The display frame's lower east corner: the frame as a full-height column over drawn columns 0-7; the floor
    beyond it stays open."""
    c = Cell(FRAME_EAST)
    b = Build(c)
    b.add(box(S, (0, 8), (0, 16), (0, 16)), top=[BLUE_L, BLUE], front=front_rect((0, 0, 8, 16), (0, 8), (0, 16)), side=MAT)
    return b.done()


def frame_west():
    """The display frame's lower west corner: the frame column over drawn columns 9-15, and beside it a narrow ticket
    machine (its top drawn rows 0-1, its face with the slot rows 2-14)."""
    c = Cell(FRAME_WEST)
    b = Build(c)
    b.add(box(S, (9, 16), (0, 16), (0, 16)), top=[BLUE_L, BLUE], front=front_rect((9, 0, 16, 16), (9, 16), (0, 16)), side=MAT)
    grey = [(156, 156, 156), (107, 107, 107), DARK]
    b.add(box(S, (0, 9), (0, 13), (3, 15)), top=top_rect((0, 0, 9, 2), (0, 9), (3, 15)), front=front_rect((0, 2, 9, 15), (0, 9), (0, 13)),
          side=grey)
    return b.done()


def trade(key, top):
    c = Cell(key)
    b = Build(c)
    if top:
        b.add(box(S, (1, 15), (0, 12), (3, 14)), top=top_rect((1, 0, 15, 4), (1, 15), (3, 14)), front=front_rect((1, 0, 15, 16), (1, 15), (0, 12)),
              side=[GREY_D, GREY, DARK])
    else:
        b.add(box(S, (1, 15), (0, 16), (3, 14)), top=[GREY, GREY_D], front=FULL, side=[GREY_D, GREY, DARK])
    return b.done()


def counter(key, balls=False):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 8), (0, 14)), top=top_rect((0, 0, 16, 9), (0, 16), (0, 14)), front=front_rect((0, 8, 16, 16), (0, 16), (0, 8)),
          side=[RED, CREAM, BLUE_L, DARK])
    if balls:
        for u in (3.5, 11.5):
            b.add(F.ellipsoid(S, (u, 10, 6), (2.6, 2.4, 2.6)) & (np.indices(S)[0] >= 8),
                  top=top_rect((u - 3, 1, u + 3, 7), (u - 3, u + 3), (3, 9)), front=[DARK, GREY], side=[CREAM, GREY])
    return b.done()


def ledge():
    """The ledge along the window wall: cream top (the window drawn above it is the wall's), its front band."""
    c = Cell(LEDGE)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 8), (0, 14)), top=[CREAM], front=front_rect((0, 8, 16, 16), (0, 16), (0, 8)), side=[CREAM, DARK])
    return b.done()


def sign():
    """The signboard: its blue panel with the text (drawn rows 0-10) on a slim post and foot."""
    c = Cell(SIGN)
    b = Build(c)
    b.add(box(S, (2, 14), (6, 15), (7, 9)), top=MAT, front=front_rect((2, 0, 14, 10), (2, 14), (6, 15)), side=MAT)
    b.add(box(S, (7, 9), (0, 6), (7, 9)), top=[DARK], front=[DARK, GREY], side=[DARK])
    b.add(box(S, (5, 11), (0, 1), (6, 10)), top=[DARK, GREY], front=[DARK], side=[DARK])
    return b.done()


def seat():
    c = Cell(SEAT)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 5), (2, 15)), top=[ORANGE], front=front_rect((0, 10, 16, 16), (1, 15), (0, 5)), side=[ORANGE, DARK, BLUE_L])
    b.add(box(S, (1, 15), (5, 8), (2, 15)), top=top_rect((1, 1, 15, 10), (1, 15), (2, 15)), front=[ORANGE, YELLOW], side=[ORANGE, YELLOW])
    return b.done()


def pot_and_leaves(key, *, leaves_to=16, pot=True, pot_rows=(9, 16), pot_mats=(WOOD_D, WOOD, ORANGE), y0=0, crown=None,
                   leaf_rows=(0, 9)):
    """One cell of a potted plant: the pot (its drawn front) and a crown of leaves from the cell's own greens."""
    c = Cell(key)
    have = {tuple(int(a) for a in px) for px in c.pixels.reshape(-1, 3)}
    greens = [g for g in (GREEN_L, GREEN, GREEN_D) if g in have] or [DARK]
    b = Build(c)
    if pot:
        b.add(F.cylinder(S, (8, 9), 4.6, (0, 6)), top=list(pot_mats) + [DARK], front=front_rect((3, pot_rows[0], 13, pot_rows[1]), (3.4, 12.6), (0, 6)),
              side=list(pot_mats) + [DARK])
    leaves = crown if crown is not None else (F.ellipsoid(S, (8, 11, 8.5), (6.5, 5.5, 5.5)) & (np.indices(S)[0] >= (6 if pot else 0)))
    r0, r1 = leaf_rows
    b.add(leaves, top=top_rect((0, r0, 16, r1), (1.5, 14.5), (3, 14)), front=front_rect((0, r0, 16, r1), (1.5, 14.5), (5, 16)), side=greens)
    return b.done()


def tall_plant(top_key, pot_key, pot_mats, crown_rows=(0, 16)):
    """A two-cell potted plant: pot and lower leaves in the base, the crown rising in the stacked top (its leaves
    painted from `crown_rows` of its drawing: the office plant's top cell shows the blue wall above its leaves)."""
    sh = (16, 16, 16)
    base = pot_and_leaves(pot_key, pot_mats=pot_mats)
    crown = F.ellipsoid(sh, (8, 4, 8.5), (6.8, 8, 5.8)) | F.ellipsoid(sh, (8, 10, 8), (4.5, 4, 4.5))
    top = pot_and_leaves(top_key, pot=False, crown=crown, leaf_rows=crown_rows)
    return base, top


def urn():
    """The urn pot variant: a gold vase (narrow foot, round body, flared lip) with no leaves of its own; the tall
    plant's crown stands on it (alsoOn)."""
    c = Cell(PLANT_URN)
    b = Build(c)
    gold = [(173, 140, 58), WOOD_D, DARK]
    body = (F.cylinder(S, (8, 8.5), 2.5, (0, 3)) | F.ellipsoid(S, (8, 7.5, 8.5), (5.2, 5, 5.2)) | F.cylinder(S, (8, 8.5), 3.6, (11, 15)))
    body &= np.indices(S)[0] < 15
    b.add(body, top=gold, front=front_rect((2, 0, 14, 16), (2.8, 13.2), (0, 15)), side=gold)
    return b.done()


def desk(key, kind):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 8), (2, 15)), top=[GREY, GREY_D], front=[GREY_D, DARK], side=[GREY_D, DARK])
    if kind == "pc":
        b.add(box(S, (2, 13), (8, 15), (3, 11)), top=top_rect((1, 0, 13, 5), (2, 13), (3, 11)), front=front_rect((1, 3, 13, 12), (2, 13), (8, 15)),
              side=[CREAM, GREY, (255, 156, 197)])
    else:
        b.add(box(S, (2, 14), (8, 9), (5, 12)), top=top_rect((1, 9, 15, 15), (2, 14), (5, 12)), front=[GREY_D, DARK], side=[GREY_D, DARK])
    return b.done()


def main():
    out = Pass("pokecom")
    for key in SCREEN:
        out.cell(key, *screen(key), label="atrium display block", family="pokecom-atrium",
                 reason="The atrium's big display as solid blocks (its top row is in the wall row: the wall stays closed), the "
                        "static on its top and face.")
    for key in PLATFORM:
        out.cell(key, *platform(key), label="blue platform / partition piece", family="pokecom-atrium",
                 reason="A piece of the atrium's raised blue platform (and of the machines booth's partition): one block high, "
                        "its drawn frame lines on top.")
    for key in SLOT_WEST + SLOT_EAST:
        out.cell(key, *slot(key, key in SLOT_WEST), label="platform with the machinery window", family="pokecom-atrium",
                 reason="The platform around the central window: the window recessed three voxels with the machinery on its floor.")
    for key in GUARD_WEST + GUARD_EAST:
        out.cell(key, *guard(key, key in GUARD_WEST), label="machine side: sloped glass band", family="pokecom-atrium",
                 reason="The machine's side: a glass band sloping from its outer edge up to the six-high ring, the drawn hatch "
                        "on its sloped top, the ring strip beyond the rim.")
    for key in FRAME_WALL + [WALL_MACHINE]:
        out.cell(key, *wall_block(key), label="atrium frame / wall machine", family="pokecom-atrium",
                 reason="A solid block of the display's frame in the wall row, or the framed machine against the wall: its "
                        "drawing on top and face.")
    out.cell(FRAME_EAST, *frame_east(), label="display frame, lower east corner", family="pokecom-atrium",
             reason="The display frame's east side as a full-height column over its drawn half; the floor beside it stays open.")
    out.cell(FRAME_WEST, *frame_west(), label="display frame, lower west corner, with a ticket machine", family="pokecom-atrium",
             reason="The display frame's west side as a full-height column, and the narrow ticket machine standing beside it.")
    out.cell(MONITOR, *monitor(), label="front terminal monitor", family="pokecom-atrium",
             reason="A terminal's monitor standing behind the console desk, its screen face above the desk.")
    out.cell(KEYPAD, *keypad(), label="front terminal console desk", family="pokecom-atrium",
             reason="The console desk at the ring's height, the keys on its top and its drawn south face.")
    for i, key in enumerate(GLASS_CORNER):
        out.cell(key, *glass_corner(key, i < 2, i % 2), label="machine front corner: diagonal glass band", family="pokecom-atrium",
                 reason="The glass band turning diagonally at the machine's front corner, sloping down to the floor; the ring "
                        "inside it.")
    out.entity("crystal_pokecom_atrium_machine", ATRIUM, "Pokecom atrium machine (display, platform, glass, terminals)")
    out.cell(TRADE, *trade(TRADE, False), label="trade machine base", family="pokecom-machines",
             reason="The trade machine's base, full height, its mechanism on the face.")
    out.cell(TRADE_TOP, *trade(TRADE_TOP, True), label="trade machine top (stacked)", family="pokecom-machines",
             reason="Stacked on its base: the machine's upper body with its dial.")
    out.entity("crystal_pokecom_trade_machine", [[TRADE_TOP], [TRADE]], "Pokecom trade machine (stack)", kind="stack (model as one)")
    out.cell(TERM_TOP, *PC.block(TERM_TOP, x=(1, 15), y=(0, 13), z=(3, 13), top=top_rect((1, 0, 15, 5), (1, 15), (3, 13)),
                                 side=MAT),
             label="terminal monitor (stacked on the Center's base)", family="pokecom-corner",
             reason="Stacked on the Center terminal's base: the blue monitor, as the Center's own.")
    out.cell(PC_TOP, *PC.pc_top(PC_TOP), label="PC monitor upper body (stacked)", family="pokecom-corner",
             reason="Stacked on the Center's PC desk: the monitor's upper body, as the Center's own.")
    out.cell(BALLS, *counter(BALLS, balls=True), label="counter with two Poke Balls", family="pokecom-corner",
             reason="The counter one block high with the two Poke Balls standing on it.")
    for key in COUNTER:
        out.cell(key, *counter(key), label="counter piece", family="pokecom-corner",
                 reason="A counter piece one block high: its drawn top and front.")
    out.cell(LEDGE, *ledge(), label="office ledge under the windows", family="pokecom-office",
             reason="The ledge along the window wall, one block high, its cream top and red front.")
    out.cell(SIGN, *sign(), label="signboard on its stand", family="pokecom-corner",
             reason="The blue signboard with its text on a slim post and foot.")
    out.cell(SEAT, *seat(), label="waiting seat", family="pokecom-seating",
             reason="A cushioned seat one block high, as the Centers' (12 placements).")
    out.cell(PLANT_ONE, *pot_and_leaves(PLANT_ONE), label="potted plant (one cell)", family="pokecom-plants",
             reason="A leafy crown in its pot, one cell.")
    base, top = tall_plant(PLANT_TOP, PLANT_POT, (WOOD_D, (173, 140, 58)))
    out.cell(PLANT_POT, *base, label="tall plant pot and lower leaves", family="pokecom-plants",
             reason="The pot and lower leaves of a two-cell plant.")
    out.cell(PLANT_TOP, *top, label="tall plant crown (stacked)", family="pokecom-plants",
             reason="Stacked on its pot: the crown. alsoOn adds the urn pot variant (amendment), where it had stood in place.")
    out.also_on(PLANT_TOP, [PLANT_URN], "POKECOM_CENTER_ADMIN_OFFICE_MOBILE (22,14) and (31,14)... over 4408-4409-4385-4386: the same crown "
                                        "over the urn pot variant; without the link it stood in place on the floor behind it.")
    out.cell(PLANT_URN, *urn(), label="gold urn (the tall plant's base variant)",
             family="pokecom-plants", reason="A gold vase with a narrow foot, round body and flared lip; the crown stands on it.")
    out.entity("crystal_pokecom_tall_plant", [[PLANT_TOP], [PLANT_POT]], "Pokecom tall plant (stack)", kind="stack (model as one)")
    out.entity("crystal_pokecom_tall_plant_urn", [[PLANT_TOP], [PLANT_URN]], "Pokecom tall plant in the urn (alsoOn stack)",
               kind="stack (model as one)")
    obase, otop = tall_plant(OFFICE_PLANT_TOP, OFFICE_PLANT, (ORANGE, YELLOW), crown_rows=(8, 16))
    out.cell(OFFICE_PLANT, *obase, label="office plant pot", family="pokecom-plants", reason="The office plant's orange pot and lower leaves.")
    out.cell(OFFICE_PLANT_TOP, *otop, label="office plant crown (stacked)", family="pokecom-plants",
             reason="Stacked on its pot: the crown (the blue wall drawn behind it is not modelled).")
    out.entity("crystal_pokecom_office_plant", [[OFFICE_PLANT_TOP], [OFFICE_PLANT]], "Pokecom office plant (stack)", kind="stack (model as one)")
    out.cell(DESK_PC, *desk(DESK_PC, "pc"), label="office desk with computer", family="pokecom-office",
             reason="A grey desk with the pink-and-cream computer standing on it.")
    out.cell(DESK_KEYS, *desk(DESK_KEYS, "keys"), label="office desk with keyboard", family="pokecom-office",
             reason="A grey desk with the keyboard on it.")
    out.entity("crystal_pokecom_office_desk", [[DESK_PC, DESK_KEYS]], "Pokecom office computer desk")
    out.write(owned=OWNED, families=["TILESET_POKECOM_CENTER_1"])


if __name__ == "__main__":
    main()
