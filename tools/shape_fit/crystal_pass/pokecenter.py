"""Crystal checkpoint 6: TILESET_POKECENTER_1 (every Johto and Kanto Pokemon Center 1F, the 2F "beta" Cable Club rooms,
the Indigo Plateau center).

Source reading (study/groups/TILESET_POKECENTER_1, the census, the stack placements; composites _pc1f/_pc2f/_indigo):

- 1F, behind the counter: the 2x2 healing machine (its green console and body in the wall row, drawn in place, so full
  wall blocks; in front its base with the red-cross ball tray and two cream columns), the blue terminal (top stacked on
  its base in every center), the wall post, the counter-end monitor (its upper body `291-295-296-297` stacks on the 2F
  counter variant but stood in place over the 1F one in all 22 Johto centers: alsoOn adds it, amendment), the PC (top
  stacked on its desk), Nurse Joy's counter section with the two Poke Balls on it.
- The counters (collision 144) are never drawn (Crystal keeps the native counter fold); the plain counter top is drawn
  only where it is solid (the Indigo / 2F placements): a counter one block high.
- The waiting-room seats (150 placements): yellow cushions on an orange frame, one block high.
- 2F Cable Club (11 placements each): blue pilasters and free-standing pillars, the booth's blue walls, glass columns,
  corner beams and its counter with the screen, the booth's machine top in the wall row, and the booth door (a warp cell:
  kept, its WARP_MODELS.md doorway frame).
- Indigo Plateau: a tall grey rack (top stacked on its base).
- Never drawn (census): the counter end, the two stairs warps.
"""
from __future__ import annotations

import numpy as np

from tools.shape_fit.crystal_pass import furniture as F
from tools.shape_fit.crystal_pass import interiors as I
from tools.shape_fit.crystal_pass.common import K, Pass
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, front_rect, top_rect

S = (16, 16, 16)
DARK, CREAM = I.DARK, I.CREAM
GREY, GREY_D = I.GREY, I.GREY_D
BLUE, BLUE_L, BLUE_M = (74, 74, 255), (123, 132, 255), (115, 132, 255)
RED, PINK, YELLOW, ORANGE = (247, 82, 49), (255, 156, 197), (255, 255, 58), (255, 132, 8)
FULL = front_rect((0, 0, 16, 16), (0, 16), (0, 16))

NEVER = {K("163-299-298-298"): ("counter end", "counter"), K("324-325-326-327"): ("2F stairs warp", "walk"),
         K("328-329-330-331"): ("1F stairs warp", "walk")}
WHY = {"walk": "Every placement is a walkable cell and the entry has no walk or seat flag: never drawn (census: walk).",
       "counter": "Collision 144 is a counter: Crystal keeps the native counter fold and never draws this entry (census)."}
HEAL_BACK = (K("270-271-274-275"), K("272-273-276-277"))
HEAL_FRONT = (K("278-0-280-281"), K("0-279-282-283"))
TERM_TOP, TERM = K("284-285-287-288"), K("289-290-292-293")
POST = K("286-286-286-295")
MON_TOP, MON_2F, MON_1F = K("291-295-296-297"), K("300-301-298-298"), K("300-301-302-303")
PC_TOP, PC_TOP_2F, PC = K("286-286-304-305"), K("291-291-304-305"), K("306-307-308-309")
COUNTER, BALLS = K("163-163-298-298"), K("299-299-298-298")
SEAT = K("346-347-348-349")
PILASTER, PILLAR = K("310-311-310-311"), K("310-311-312-313")
BOOTH_WALL, BOOTH_BASE = K("322-323-322-323"), K("322-323-302-303")
GLASS = (K("323-338-323-338"), K("332-322-332-322"))
BOOTH_CORNER = (K("334-335-322-323"), K("335-339-322-323"))
BOOTH_DESK, BOOTH_SCREEN, BOOTH_MACHINE = K("335-335-336-337"), K("344-345-302-303"), K("273-333-275-276")
DOOR = K("316-317-320-321")
RACK_TOP, RACK = K("333-354-355-356"), K("355-356-357-358")
OWNED = [*NEVER, *HEAL_BACK, *HEAL_FRONT, TERM_TOP, TERM, POST, MON_TOP, MON_2F, MON_1F, PC_TOP, PC_TOP_2F, PC, COUNTER, BALLS,
         SEAT, PILASTER, PILLAR, BOOTH_WALL, BOOTH_BASE, *GLASS, *BOOTH_CORNER, BOOTH_DESK, BOOTH_SCREEN, BOOTH_MACHINE, DOOR,
         RACK_TOP, RACK]


def block(key, *, x=(0, 16), y=(0, 16), z=(0, 16), front=FULL, top=None, side=None):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, x, y, z), top=top or [GREY, GREY_D, DARK], front=front, side=side or [GREY_D, GREY, DARK])
    return b.done()


def heal_front(key, west):
    """The healing machine's front: its body against the back cell (drawn rows 0-8: the cream column on the outer side),
    and the ball tray with the red cross (rows 9-14) one block high in front of it."""
    c = Cell(key)
    b = Build(c)
    # the body stays low (10) so the green console on the back cells' face shows above it
    b.add(box(S, (0, 16), (0, 10), (0, 5)), top=[GREY_D, DARK], front=front_rect((0, 0, 16, 9), (0, 16), (0, 10)), side=[GREY_D, DARK])
    cx = 3.5 if west else 12.5
    b.add(F.cylinder(S, (cx, 6), 2.4, (0, 14)), top=[CREAM, GREY], front=[CREAM, GREY], side=[GREY, CREAM])
    b.add(box(S, (0, 16), (0, 8), (5, 15)), top=top_rect((0, 9, 16, 15), (0, 16), (5, 15)),
          front=front_rect((0, 13, 16, 16), (0, 16), (0, 8)), side=[GREY_D, DARK])
    return b.done()


def counter(key, balls=False):
    """A counter one block high: its cream top (drawn rows 1-8), the blue front (rows 8-15); two Poke Balls on Nurse
    Joy's section."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 8), (0, 14)), top=top_rect((0, 1, 16, 8), (0, 16), (0, 14)), front=front_rect((0, 8, 16, 16), (0, 16), (0, 8)),
          side=[BLUE, DARK])
    if balls:
        for u in (3.5, 11.5):
            b.add(F.ellipsoid(S, (u, 10, 6), (2.6, 2.4, 2.6)) & (np.indices(S)[0] >= 8),
                  top=top_rect((u - 3, 2, u + 3, 8), (u - 3, u + 3), (3, 9)), front=[DARK, GREY], side=[CREAM, GREY])
    return b.done()


def counter_monitor(key):
    """The counter-end monitor: the counter (rows 8-15) with the tilted blue monitor (rows 0-7) standing on it."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 8), (0, 14)), top=[CREAM, BLUE], front=front_rect((0, 8, 16, 16), (0, 16), (0, 8)), side=[BLUE, DARK])
    yy, zz, xx = np.indices(S)
    tilt = (xx >= 2) & (xx < 14) & (zz >= 4) & (zz < 12) & (yy >= 8) & (yy < 8 + 6 + (zz - 4) * 0.25)
    b.add(tilt, top=top_rect((1, 0, 15, 8), (2, 14), (4, 12)), front=front_rect((1, 0, 15, 8), (2, 14), (8, 16)), side=[BLUE_L, BLUE])
    return b.done()


def monitor_top():
    """The monitor's upper body, stacked on the counter end (drawn rows 8-15); the floor and post beside it are not modelled."""
    c = Cell(MON_TOP)
    b = Build(c)
    b.add(box(S, (2, 14), (0, 4), (4, 12)), top=top_rect((1, 8, 15, 12), (2, 14), (4, 12)), front=front_rect((1, 8, 15, 16), (2, 14), (0, 4)),
          side=[BLUE_L, BLUE])
    return b.done()


def pc_desk():
    c = Cell(PC)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 8), (2, 15)), top=top_rect((0, 6, 16, 10), (0, 16), (2, 15)), front=front_rect((0, 10, 16, 16), (0, 16), (0, 8)),
          side=[GREY_D, DARK])
    b.add(box(S, (2, 14), (8, 16), (3, 9)), top=[GREY], front=front_rect((1, 0, 15, 6), (2, 14), (8, 16)), side=[GREY, GREY_D])
    return b.done()


def pc_top(key):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (2, 14), (0, 6), (3, 9)), top=[GREY, CREAM], front=front_rect((2, 8, 14, 16), (2, 14), (0, 6)), side=[GREY, GREY_D])
    return b.done()


def seat():
    """The waiting-room seat: an orange frame (drawn rows 10-15) with the yellow cushion (rows 1-9) on top, one block high."""
    c = Cell(SEAT)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 5), (2, 15)), top=[ORANGE], front=front_rect((0, 10, 16, 16), (1, 15), (0, 5)), side=[ORANGE, DARK])
    b.add(box(S, (1, 15), (5, 8), (2, 15)), top=top_rect((1, 1, 15, 10), (1, 15), (2, 15)), front=[ORANGE, YELLOW], side=[ORANGE, YELLOW])
    return b.done()


def pillar(key, wall):
    """Blue Cable Club column: a pilaster filling the wall-row cell, or a free-standing pillar on its base."""
    if wall:
        return block(key, front=FULL, top=[BLUE, BLUE_L], side=[DARK, BLUE])
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (2, 14), (0, 16), (3, 13)), top=[CREAM, BLUE_L], front=front_rect((2, 0, 14, 16), (2, 14), (0, 16)),
          side=front_rect((2, 0, 14, 16), (3, 13), (0, 16)))
    return b.done()


def glass(key):
    """A booth wall with its glass column recessed two voxels."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 16), (0, 16)), top=[BLUE, DARK], front=FULL, side=[BLUE, DARK])
    pix = c.pixels
    glass_cols = [u for u in range(16) if any(tuple(int(a) for a in pix[v, u]) in (CREAM, (140, 156, 255)) for v in range(16))]
    if glass_cols:
        g0, g1 = min(glass_cols), max(glass_cols) + 1
        b.cut(box(S, (g0, g1), (0, 16), (14, 16)))
        b.add(box(S, (g0, g1), (0, 16), (14, 15)), top=[CREAM], front=FULL, side=[CREAM])
    return b.done()


def door():
    """The booth door (walk): the drawn door at the back of a frame; walk-through decoration."""
    c = Cell(DOOR)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 16), (0, 2)), top=[CREAM], front=FULL, side=[CREAM, DARK])
    for x in ((1, 3), (13, 15)):
        b.add(box(S, x, (0, 16), (2, 5)), top=[CREAM], front=[CREAM, DARK], side=[CREAM, DARK])
    b.add(box(S, (1, 15), (14, 16), (2, 5)), top=[CREAM], front=front_rect((0, 0, 16, 2), (1, 15), (14, 16)), side=[CREAM])
    return b.done()


def main():
    out = Pass("pokecenter")
    for key, (label, why) in NEVER.items():
        out.keep(key, label=label, family="pokecenter-never-drawn", reason=WHY[why])
    for key in HEAL_BACK:
        out.cell(key, *block(key, top=top_rect((0, 0, 16, 2), (0, 16), (0, 16)), side=[GREY_D, DARK]),
                 label="healing machine console (wall row)", family="healing-machine",
                 reason="Wall row, drawn in place: a full block (the wall stays closed) showing the green console and the "
                        "machine's body with its columns on its face.")
    for key, west in zip(HEAL_FRONT, (True, False)):
        out.cell(key, *heal_front(key, west), label="healing machine base and tray", family="healing-machine",
                 reason="The machine's body against the back, a cream column rising on its outer side, and the ball tray with "
                        "the red cross one block high in front.")
    out.entity("crystal_healing_machine", [list(HEAL_BACK), list(HEAL_FRONT)], "Pokemon Center healing machine (2x2)")
    out.cell(TERM, *block(TERM, z=(2, 15), top=[GREY, CREAM]), label="blue terminal base", family="center-terminal",
             reason="The terminal's grey base with its display panel and vents on its face.")
    out.cell(TERM_TOP, *block(TERM_TOP, x=(1, 15), y=(0, 13), z=(3, 13), top=top_rect((1, 0, 15, 5), (1, 15), (3, 13)),
                              side=[BLUE_L, BLUE]),
             label="blue terminal monitor (stacked)", family="center-terminal",
             reason="Stacked in all 22 placements: the blue monitor with its glass and hood standing on the base.")
    out.entity("crystal_center_terminal", [[TERM_TOP], [TERM]], "Pokemon Center terminal (stack)", kind="stack (model as one)")
    out.cell(POST, *I.wall_relief(POST, background=[DARK, GREY_D], depth=2, side=[GREY_D, DARK]), label="wall with the cream post (wall row)",
             family="center-terminal", reason="Wall row, drawn in place: the striped wall block with the cream post standing proud.")
    for key in (MON_1F, MON_2F):
        out.cell(key, *counter_monitor(key), label="counter-end monitor (" + ("1F" if key == MON_1F else "2F") + ")",
                 family="center-terminal", reason="The counter end one block high with the tilted blue monitor standing on it.")
    out.cell(MON_TOP, *monitor_top(), label="counter-end monitor upper body (stacked)", family="center-terminal",
             reason="Stacked on the counter end: the monitor's upper body. alsoOn adds the 1F counter variant (amendment), "
                    "where it had stood in place behind the counter.")
    out.also_on(MON_TOP, [MON_1F], "Every Johto/Kanto POKECENTER_1F (5,1) over 300-301-302-303: the same monitor drawn over the 1F "
                                   "counter variant (22 placements); without the link it stood in place behind the counter.")
    out.entity("crystal_center_monitor_1f", [[MON_TOP], [MON_1F]], "Counter-end monitor (1F, alsoOn stack)", kind="stack (model as one)")
    out.cell(PC, *pc_desk(), label="Pokemon Center PC desk", family="center-pc",
             reason="The PC's desk with its keyboard and the monitor's lower body standing on it.")
    for key in (PC_TOP, PC_TOP_2F):
        out.cell(key, *pc_top(key), label="PC monitor upper body (stacked)", family="center-pc",
                 reason="Stacked on the PC desk in every placement: the monitor's upper body; wall or floor rows not modelled.")
    out.entity("crystal_center_pc", [[PC_TOP], [PC]], "Pokemon Center PC (stack)", kind="stack (model as one)")
    out.cell(COUNTER, *counter(COUNTER), label="counter top (where solid)", family="center-counter",
             reason="Where solid (22 of 116 placements; the counters are never drawn): a counter one block high, cream top, blue front.")
    out.cell(BALLS, *counter(BALLS, balls=True), label="Nurse Joy's counter with two Poke Balls", family="center-counter",
             reason="The counter section in front of Nurse Joy with the two Poke Balls standing on it.")
    out.cell(SEAT, *seat(), label="waiting-room seat", family="center-seating",
             reason="A cushioned seat one block high: the yellow cushion on its orange frame (150 placements).")
    out.cell(PILASTER, *pillar(PILASTER, True), label="Cable Club pilaster (wall row)", family="cable-club",
             reason="Wall row, drawn in place: a full block with the blue-and-cream pilaster on its face.")
    out.cell(PILLAR, *pillar(PILLAR, False), label="Cable Club pillar", family="cable-club",
             reason="A free-standing blue pillar with its cream light strip and base, 2 blocks tall.")
    out.cell(BOOTH_WALL, *block(BOOTH_WALL, top=[BLUE, DARK], side=[BLUE, DARK]), label="booth wall", family="cable-club",
             reason="A wall block of the Cable Club booth with its blue panel and dark edges.")
    out.cell(BOOTH_BASE, *block(BOOTH_BASE, top=[BLUE, DARK], side=[BLUE, DARK]), label="booth wall base", family="cable-club",
             reason="The booth wall's lower block with its counter edge on the face.")
    for key in GLASS:
        out.cell(key, *glass(key), label="booth glass column", family="cable-club",
                 reason="A booth wall with its glass column recessed two voxels.")
    for key in BOOTH_CORNER:
        out.cell(key, *block(key, top=[BLUE, DARK], side=[BLUE, DARK]), label="booth corner beam", family="cable-club",
                 reason="The booth's upper corner with its diagonal beam drawn on its face.")
    out.cell(BOOTH_DESK, *block(BOOTH_DESK, y=(0, 10), top=top_rect((0, 2, 16, 8), (0, 16), (0, 16)),
                                front=front_rect((0, 8, 16, 16), (0, 16), (0, 10)), side=[BLUE, DARK]),
             label="booth counter", family="cable-club", reason="The booth's counter: its cream top and its front with the panel.")
    out.cell(BOOTH_SCREEN, *block(BOOTH_SCREEN, top=[BLUE, DARK], side=[BLUE, DARK]), label="booth counter with screen",
             family="cable-club", reason="The booth counter section with the screen on its face.")
    out.cell(BOOTH_MACHINE, *block(BOOTH_MACHINE, side=[GREY_D, DARK]), label="booth machine (wall row)", family="cable-club",
             reason="Wall row, drawn in place: a full block with the booth's machine on its face.")
    out.keep_warp(DOOR, label="booth door (warp)", family="cable-club")
    out.cell(RACK, *I.case_base(RACK, rows=(0, 16), shelf=((3, 13), (8, 14), (2, 8)), wood=(GREY, GREY_D)), label="Indigo rack lower half",
             family="center-rack", reason="A grey rack on the floor: a recessed shelf over its lower compartments.")
    out.cell(RACK_TOP, *I.case_top(RACK_TOP, front_rows=(0, 16), top_rows=(0, 2), height=12, wood=(GREY, GREY_D),
                                   shelf=((3, 13), (5, 11), (1, 7))),
             label="Indigo rack upper half (stacked)", family="center-rack", reason="Stacked on its base: the rack's upper shelves.")
    out.entity("crystal_indigo_rack", [[RACK_TOP], [RACK]], "Indigo Plateau rack (stack)", kind="stack (model as one)")
    out.entity("crystal_center_counter_row", [[COUNTER, BALLS, COUNTER, MON_1F]], "Pokemon Center counter with Nurse Joy's balls")
    out.entity("crystal_cable_club_booth", [[PILASTER, GLASS[1], BOOTH_MACHINE, GLASS[0], PILASTER],
                                            [PILLAR, BOOTH_CORNER[0], BOOTH_DESK, BOOTH_CORNER[1], PILLAR],
                                            [BOOTH_WALL, BOOTH_BASE, DOOR, BOOTH_BASE, BOOTH_WALL]], "Cable Club booth (5x3)")
    out.write(owned=OWNED)


if __name__ == "__main__":
    main()
