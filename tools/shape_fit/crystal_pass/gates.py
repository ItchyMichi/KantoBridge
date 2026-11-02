"""Crystal checkpoint 12: TILESET_GATE_1 (the route gate houses, the underground path entrances, the Colosseum, the
Trade Center and Time Capsule) and TILESET_GATE_2 (the Goldenrod Underground).

Source reading (study/groups/TILESET_GATE_1 and _2; composite _gate_colosseum):

- The gate houses: potted shrubs in wooden planters (40), the National Park gates' PC (the monitor stacked on its blue
  keyboard desk, as the Radio Tower's PC), grey square chairs, and the stairs down to the underground paths (warp
  cells: kept, their WARP_MODELS.md models).
- The Colosseum: the link machine, designed over its 2x2 cells (two low grid consoles flanking a tall green A-frame
  whose tip is drawn in the row above) and painted from its composite drawing; the round ring-cushion stools beside it.
- The Goldenrod Underground: the wall row's blue panels with their light bars (wall reliefs: the wall stays closed), its
  two corner cells over the green counters' ends (the wall closes the north half, the counter one block in front), the
  blue shutter door recessed in its wall block, the bush planters (a two-puff crown stacked on the planter, and a
  single-cell planter), grey chairs and the stairs.
- Never drawn (retained): the counter cells of both families (Crystal never draws fitted counters).
"""
from __future__ import annotations

import numpy as np

from tools.shape_fit.crystal_pass import furniture as F
from tools.shape_fit.crystal_pass import interiors as I
from tools.shape_fit.crystal_pass.common import K, Pass
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, front_rect, top_rect

S = (16, 16, 16)
DARK, CREAM, GREY, GREY_D = I.DARK, I.CREAM, I.GREY, I.GREY_D
BLUE, BLUE_L, BLUE_D, BLUE_W = (74, 74, 255), (123, 132, 255), (8, 33, 255), (66, 99, 255)
WOOD, WOOD_D, WOOD_L = (173, 140, 58), (132, 107, 25), (214, 197, 140)
G_D, G, G_L, G_DD, G_B, G_P = (74, 107, 0), (123, 165, 8), (148, 197, 74), (41, 115, 0), (99, 206, 8), (181, 255, 82)
GREY_P = (173, 173, 173)
TOPDRAW = top_rect((0, 0, 16, 16), (0, 16), (0, 16))
FULL = front_rect((0, 0, 16, 16), (0, 16), (0, 16))
FAMILIES = ["TILESET_GATE_1", "TILESET_GATE_2"]

PC_TOP, PC_BASE = K("1828-1829-1881-1882"), K("700-701-1883-1884")
MACHINE = [[K("1854-1854-96-1855"), K("1854-1854-1856-96")], [K("1864-1865-1868-1869"), K("1866-1867-1870-1871")]]
RING_STOOL = K("1860-1861-1862-1863")
CHAIRS = [K("1891-1892-1893-1894"), K("3102-3103-3104-3105")]
SHRUB = K("1895-1896-1897-1898")
STAIRS = [K("1899-1900-1901-1902"), K("3098-3099-3100-3101"), K("3110-3111-3112-3113")]
UG_WALL, UG_CORNER, UG_DOOR = K("3028-3029-3030-3030"), K("3028-3029-3037-3038"), K("3033-3034-3035-3036")
BUSH_BASE, BUSH_TOP, BUSH_ONE = K("3058-3059-3108-3109"), K("3106-3107-3058-3059"), K("3106-3107-3108-3109")
COUNTERS = [K(k) for k in ("1837-1838-1840-1841", "1837-1838-1844-1845", "1837-1839-1842-1843", "1839-1839-1842-1843",
                           "1840-1841-1840-1841", "1840-1841-1842-1843", "1844-1845-1846-1847", "1846-1847-1840-1841",
                           "1873-1874-1842-1843", "1875-1876-1842-1843", "3037-3038-3040-3041", "3039-3038-3042-3043",
                           "3039-3039-3042-3043", "3040-3041-3040-3041", "3040-3041-3042-3043")]


def pc_base():
    """The PC's blue keyboard desk (its front: drawn rows 6-15) and the monitor's lower casing on it (rows 0-6)."""
    c = Cell(PC_BASE)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 8), (2, 15)), top=[CREAM, GREY], front=front_rect((0, 6, 16, 16), (1, 15), (0, 8)), side=[BLUE, DARK, BLUE_L])
    b.add(box(S, (3, 13), (8, 16), (4, 12)), top=[GREY, GREY_D], front=front_rect((2, 0, 14, 6), (3, 13), (8, 16)), side=[GREY, GREY_D, DARK])
    return b.done()


def pc_top():
    """Stacked on the desk: the monitor's upper casing with the cream screen (drawn rows 8-15; the wall above is not
    modelled)."""
    c = Cell(PC_TOP)
    b = Build(c)
    b.add(box(S, (3, 13), (0, 7), (4, 12)), top=[GREY, GREY_D], front=front_rect((2, 8, 14, 16), (3, 13), (0, 7)), side=[GREY, GREY_D, DARK])
    return b.done()


def link_machine():
    """The Colosseum's link machine over its 2x2 cells (global y, z 0-31, x 0-31): two grid consoles (x 1-9, 23-31, six
    high) and the green A-frame between them (ridge at x 16, fourteen high), its tip reaching into the row above."""
    yy, zz, xx = np.indices((16, 32, 32))
    zc, xc = zz + .5, xx + .5
    consoles = ((xc > 1) & (xc < 9.5) | (xc > 22.5) & (xc < 31)) & (zc > 17) & (zc < 29.5) & (yy < 6)
    dx = np.abs(xc - 16)
    ridge = 14 * (1 - dx / 8.5)
    frame = (dx < 8.5) & (zc > 10) & (zc < 30) & (yy < ridge)
    occ = consoles | frame

    def colour_at(y, gz, gx, face):
        if face == "up":
            return gx + .5, gz + .5 - (y * .5 if abs(gx + .5 - 16) < 8.5 else 0)
        if face == "down":
            return gx + .5, 29
        return gx + .5, 30.5 - (y + .5) * (14 / 14)
    return I.paint_global(occ, MACHINE, colour_at, [G_D, G, G_L, DARK])


def chair(key):
    """A grey square chair: its seat (drawn rows 1-8) and front (rows 9-14), five voxels high (walkable)."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 5), (2, 14)), top=top_rect((1, 1, 15, 9), (1, 15), (2, 14)), front=front_rect((1, 9, 15, 15), (1, 15), (0, 5)),
          side=[GREY_D, DARK, GREY])
    return b.done()


def puff(b, yc, rows, ry=3.6, greens=(G, G_D, G_L)):
    r0, r1 = rows
    occ = F.ellipsoid(S, (8, yc, 8), (6.8, ry, 6))
    b.add(occ, top=top_rect((1, r0, 15, r1), (1.2, 14.8), (2, 14)), front=front_rect((1, r0, 15, r1), (1.2, 14.8), (yc - ry, yc + ry)),
          side=list(greens))


def planter(key, *, box_rows, crown_rows, greens):
    """A wooden planter box (its front from `box_rows`) with a bush puff over it (`crown_rows`)."""
    c = Cell(key)
    b = Build(c)
    wood = [WOOD, WOOD_D, (197, 148, 58), (165, 123, 25), DARK]  # the gates' browns and the Underground's
    b.add(box(S, (1, 15), (0, 7), (2, 14)), top=[WOOD_L] + wood, front=front_rect((1, box_rows[0], 15, box_rows[1]), (1, 15), (0, 7)),
          side=wood)
    puff(b, 10.4, crown_rows, greens=greens)
    return b.done()


def bush_top():
    """Stacked on the planter: the bush's upper two puffs (drawn rows 0-8 and 9-15)."""
    c = Cell(BUSH_TOP)
    b = Build(c)
    greens = (G_B, G_DD, G_P)
    puff(b, 3.6, (8, 16), greens=greens)
    puff(b, 10.4, (0, 8), greens=greens)
    return b.done()


def stairs(key):
    """A walkable stairway warp: a floor-level plate showing the drawn stairwell."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 1), (0, 16)), top=TOPDRAW, front=[GREY_D, DARK], side=[GREY_D, DARK])
    return b.done()


def ug_corner():
    """The Underground wall row over a green counter's end: the wall (its light bar, drawn rows 0-7) closes the north
    half; the counter's end (one block, its drawn top: rows 8-15) stands in front."""
    c = Cell(UG_CORNER)
    b = Build(c)
    blue = [BLUE_W, BLUE_D]
    b.add(box(S, (0, 16), (0, 16), (0, 8)), top=blue, front=front_rect((0, 0, 16, 8), (0, 16), (0, 16)), side=blue)
    b.add(box(S, (0, 16), (0, 8), (8, 16)), top=top_rect((0, 8, 16, 16), (0, 16), (8, 16)), front=[G_B, DARK], side=[G_B, G_DD, DARK])
    return b.done()


def ug_door():
    """The blue shutter door in its wall block: the frame flush with the wall, the door panel (drawn columns 2-13, rows
    2-14) set two voxels in."""
    c = Cell(UG_DOOR)
    b = Build(c)
    blue = [BLUE_W, BLUE_D, DARK]
    b.add(box(S, (0, 16), (0, 16), (0, 16)), top=blue, front=FULL, side=blue)
    b.cut(box(S, (2, 14), (1, 14), (14, 16)))
    b.add(box(S, (2, 14), (1, 14), (14, 15)), top=blue, front=front_rect((2, 2, 14, 15), (2, 14), (1, 14)), side=blue)
    return b.done()


def main():
    out = Pass("gates")
    out.cell(PC_BASE, *pc_base(), label="gate PC desk", family="gate-houses",
             reason="The PC's blue keyboard desk and the monitor's lower casing on it.")
    out.cell(PC_TOP, *pc_top(), label="gate PC monitor (stacked)", family="gate-houses",
             reason="Stacked on the desk: the monitor's upper casing with the screen.")
    out.entity("crystal_gate_pc", [[PC_TOP], [PC_BASE]], "National Park gate PC (stack)", kind="stack (model as one)")
    out.cell(SHRUB, *planter(SHRUB, box_rows=(8, 15), crown_rows=(1, 8), greens=(G, G_D, G_L)), label="potted shrub", family="gate-houses",
             reason="A wooden planter box with a shrub over it (40 placements).")
    for key in CHAIRS:
        out.cell(key, *chair(key), label="grey square chair", family="gate-houses",
                 reason="A grey square chair five voxels high, its drawn seat and front (walkable).")
    for key in STAIRS:
        out.keep_warp(key, label="stairway (warp)", family="gate-houses")
    for key, (occ, paint) in link_machine().items():
        out.cell(key, occ, paint, label="Colosseum link machine", family="gate-colosseum",
                 reason="Part of the link machine designed over its 2x2 cells (grid consoles, the green A-frame) and painted "
                        "from its composite drawing.")
    out.entity("crystal_gate_link_machine", MACHINE, "Colosseum link machine")
    out.cell(RING_STOOL, *I.round_stool(RING_STOOL, background=[CREAM], center=(8, 9), radius=5.5, top_rows=(3, 13)),
             label="ring-cushion stool", family="gate-colosseum", reason="A round grey stool on short legs (walkable).")
    out.cell(UG_WALL, *I.wall_relief(UG_WALL, background=[BLUE_W, BLUE_D], depth=1, side=[BLUE_W, BLUE_D]),
             label="Underground wall panel", family="gate-underground",
             reason="A wall-row cell: the wall closed, its light bar and trim standing one voxel proud (22 placements).")
    out.cell(UG_CORNER, *ug_corner(), label="Underground wall over a counter's end", family="gate-underground",
             reason="The wall closes the north half; the green counter's end one block in front of it.")
    out.cell(UG_DOOR, *ug_door(), label="Underground shutter door", family="gate-underground",
             reason="The blue shutter door recessed two voxels in its wall block.")
    out.cell(BUSH_BASE, *planter(BUSH_BASE, box_rows=(9, 16), crown_rows=(0, 9), greens=(G_B, G_DD, G_P)),
             label="bush planter base", family="gate-underground", reason="The wooden planter and the bush's lowest puff.")
    out.cell(BUSH_TOP, *bush_top(), label="bush crown (stacked)", family="gate-underground",
             reason="Stacked on the planter: the bush's upper two puffs.")
    out.cell(BUSH_ONE, *planter(BUSH_ONE, box_rows=(9, 16), crown_rows=(0, 9), greens=(G_B, G_DD, G_P)),
             label="bush planter (one cell)", family="gate-underground", reason="A wooden planter with its bush, one cell.")
    out.entity("crystal_gate_bush", [[BUSH_TOP], [BUSH_BASE]], "Underground bush planter (stack)", kind="stack (model as one)")
    for key in COUNTERS:
        out.keep(key, label="counter (never drawn)", family="gate-retained",
                 reason="A counter cell: Crystal never draws fitted counters (Tier A's plain counter), so its entry is kept.")
    out.write(families=FAMILIES)


if __name__ == "__main__":
    main()
