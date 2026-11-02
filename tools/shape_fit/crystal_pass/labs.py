"""Crystal checkpoint 5: TILESET_LAB_1 (the Dragon Shrine) and TILESET_LAB_2 (Elm's and Oak's labs, Earl's Pokemon
Academy, the statues of the Fuchsia and Vermilion gyms).

Source reading (study/groups/TILESET_LAB_1, TILESET_LAB_2, the census and the stack placements):

- Never drawn (census, walkable): the Dragon Shrine's floor and dais strips, the labs' table front edges.
- Dragon Shrine: carved dragon heads in the wall row (in place): wall blocks with the carving in relief.
- Bookcases: the top `2479-2480-1407-2481` stacks onto its base in Oak's lab and the Academy (14) but stood in place over
  Elm's base variant (12): `alsoOn` adds that base (amendment), so every one is a tall bookcase. Bases with a recessed
  shelf of books over their doors or drawers.
- The gym statue figure `1158-1159-1160-1161` stacks onto its pedestal in Vermilion but stood in place over Fuchsia's
  pedestal variant: `alsoOn` adds it (amendment). Pedestal and figure come from interiors (the Game Corner family has
  the other pedestal).
- Lab table (Elm's, Oak's): the shared table system, one row deep (its front row is floor), and the Poke Ball display
  at its east end; Elm's research machine (2x2), PC and printer, the window and wall monitor (wall row, in place:
  wall blocks with the window recessed and the monitor proud), Elm's corner machine and desk.
- Earl's Academy: two-cell school desks with a front panel, chairs (seat) facing north with their backrests on the
  south edge, the teacher's desk.
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
BLUE, BLUE_L = (115, 132, 255), (140, 156, 255)
NAVY, NAVY_D, BLACK = (41, 41, 140), (25, 25, 82), (0, 0, 0)

NEVER = {K(k): label for k, label in [
    ("2402-2445-2409-2409", "Dragon Shrine floor before the dais"), ("2446-2405-2409-2409", "Dragon Shrine floor before the dais"),
    ("2473-2473-2474-2474", "Dragon Shrine dais step"), ("2510-2511-2476-2476", "lab table front edge on the floor"),
    ("2511-2511-2476-2476", "lab table front edge on the floor"), ("2511-2512-2476-2476", "lab table front edge on the floor")]}
DRAGONS = (K("2395-2435-2398-2437"), K("2436-2397-2438-2401"))
CASE_TOP, CASE_ELM, CASE_OAK = K("2479-2480-1407-2481"), K("1407-2481-2482-2483"), K("1407-2481-2521-2522")
FIGURE, PEDESTAL_F = K("1158-1159-1160-1161"), K("1162-1163-2541-2542")
TABLE = {K("2479-659-662-663"): ("nw", "nw sw", "lab table west end"), K("659-2480-663-2490"): ("ne", "ne se", "lab table east end")}
BALL = K("2488-2489-2491-2492")
MACHINE = {K("270-2533-274-275"): ("back", "Elm's research machine, back west"), K("2534-273-276-277"): ("back", "Elm's research machine, back east"),
           K("278-0-280-2535"): ("front", "Elm's research machine, front west"), K("0-279-2536-283"): ("front", "Elm's research machine, front east")}
PC, PRINTER = K("2494-2495-2498-2499"), K("2496-2497-2500-2501")
WINDOW, MONITOR = K("2502-2503-2506-2507"), K("2504-2505-2508-2509")
CORNER_A, CORNER_B = K("2550-2551-2554-2555"), K("2552-2553-2556-2557")
TEACHER = K("2479-2480-2482-2483")
DESKS = {K("2523-2524-2526-2527"): "west", K("2524-2525-2527-2528"): "east"}
CHAIR = K("2529-2530-2531-2532")
OWNED = [*NEVER, *DRAGONS, CASE_TOP, CASE_ELM, CASE_OAK, FIGURE, PEDESTAL_F, *TABLE, BALL, *MACHINE, PC, PRINTER, WINDOW,
         MONITOR, CORNER_A, CORNER_B, TEACHER, *DESKS, CHAIR]


def ball_display():
    """The Poke Ball display at the lab table's end: a grey stand and the ball (its drawn ring and halves) on it."""
    c = Cell(BALL)
    b = Build(c)
    b.add(box(S, (2, 14), (0, 6), (3, 13)), top=[GREY_D, GREY], front=[GREY_D, GREY], side=[GREY_D, GREY])
    b.add(F.ellipsoid(S, (8, 10.5, 8), (5.5, 5.5, 5)), top=top_rect((2, 1, 14, 9), (2.5, 13.5), (3, 13)),
          front=front_rect((2, 1, 14, 15), (2.5, 13.5), (5, 16)), side=[GREY, GREY_D, DARK])
    return b.done()


def machine(key, part):
    c = Cell(key)
    b = Build(c)
    full = front_rect((0, 0, 16, 16), (0, 16), (0, 16))
    mats = [GREY, GREY_D, DARK]
    if part == "back":
        b.add(box(S, (0, 16), (0, 16), (0, 14)), top=top_rect((0, 0, 16, 3), (0, 16), (0, 14)), front=full, side=mats)
    else:
        b.add(box(S, (0, 16), (0, 12), (0, 12)), top=[GREY, GREY_D], front=front_rect((0, 4, 16, 16), (0, 16), (0, 12)), side=mats)
        b.add(box(S, (0, 16), (12, 16), (0, 4)), top=[GREY], front=front_rect((0, 0, 16, 4), (0, 16), (12, 16)), side=mats)
    return b.done()


def pc_desk(key, kind):
    """Elm's PC (monitor rows 3-9, keyboard rows 11-14) and the printer beside it, each on a grey desk."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 8), (2, 15)), top=[GREY, GREY_D], front=front_rect((0, 12, 16, 15), (0, 16), (0, 8)), side=[GREY_D, DARK])
    if kind == "pc":
        b.add(box(S, (2, 14), (8, 16), (3, 9)), top=[CREAM, GREY], front=front_rect((2, 2, 14, 10), (2, 14), (8, 16)), side=[CREAM, GREY])
        b.add(box(S, (3, 13), (8, 9), (10, 14)), top=top_rect((3, 11, 13, 14), (3, 13), (10, 14)), front=[GREY_D], side=[GREY_D])
    else:
        b.add(box(S, (1, 15), (8, 14), (3, 13)), top=top_rect((0, 0, 16, 5), (1, 15), (3, 13)),
              front=front_rect((0, 5, 16, 14), (1, 15), (8, 14)), side=[CREAM, GREY])
    return b.done()


def window():
    """Wall row, in place: the wall block with the glass (drawn rows 1-9) recessed two voxels and the sill proud."""
    c = Cell(WINDOW)
    b = Build(c)
    full = front_rect((0, 0, 16, 16), (0, 16), (0, 16))
    b.add(box(S, (0, 16), (0, 16), (0, 15)), top=[CREAM], front=full, side=[CREAM, DARK])
    b.cut(box(S, (2, 14), (7, 15), (13, 15)))
    b.add(box(S, (2, 14), (7, 15), (13, 14)), top=[BLUE], front=full, side=[BLUE])
    b.add(box(S, (1, 15), (5, 7), (15, 16)), top=[DARK, CREAM], front=full, side=[DARK])
    return b.done()


def corner(key, kind):
    """Elm's corner equipment: a grey machine with its dial and monitor, and the desk with its computer (checker top)."""
    c = Cell(key)
    b = Build(c)
    if kind == "machine":
        b.add(box(S, (0, 16), (0, 12), (2, 15)), top=top_rect((0, 0, 16, 11), (0, 16), (2, 15)),
              front=front_rect((0, 11, 16, 16), (0, 16), (0, 12)), side=[GREY, GREY_D])
    else:
        b.add(box(S, (0, 15), (0, 9), (6, 15)), top=[GREY, GREY_D], front=front_rect((0, 10, 16, 15), (0, 15), (0, 9)), side=[GREY_D, DARK])
        b.add(box(S, (1, 12), (9, 15), (6, 11)), top=top_rect((1, 0, 12, 4), (1, 12), (6, 11)),
              front=front_rect((1, 4, 12, 10), (1, 12), (9, 15)), side=[GREY, GREY_D])
    return b.done()


def school_desk(key, end):
    """One cell of a two-cell school desk: the top (rows 1-7), the front panel (rows 8-14) and the end panel."""
    c = Cell(key)
    b = Build(c)
    x = (1, 16) if end == "west" else (0, 15)
    b.add(box(S, x, (6, 8), (2, 12)), top=top_rect((0, 1, 16, 8), (0, 16), (2, 12)), front=[WOOD_D, DARK], side=[WOOD_D, DARK])
    b.add(box(S, x, (0, 6), (10, 12)), top=[WOOD_D], front=front_rect((0, 8, 16, 15), (0, 16), (0, 6)), side=[WOOD_D, DARK])
    ex = (1, 3) if end == "west" else (13, 15)
    b.add(box(S, ex, (0, 6), (2, 12)), top=[WOOD_D], front=[DARK, WOOD_D], side=[WOOD_D, DARK])
    return b.done()


def school_chair():
    """The pupil's chair, facing the teacher (north): seat at the existing seat height (y 4), the backrest (drawn rows
    3-9) on its south edge, four legs."""
    c = Cell(CHAIR)
    b = Build(c)
    b.add(box(S, (3, 13), (2, 4), (3, 13)), top=[CREAM, BLUE], front=[DARK], side=[DARK])
    b.add(box(S, (3, 13), (4, 12), (11, 13)), top=[DARK], front=front_rect((2, 3, 14, 10), (3, 13), (4, 12)), side=[DARK])
    for x, z in ((3, 3), (12, 3), (3, 12), (12, 12)):
        b.add(box(S, (x, x + 1), (0, 2), (z, z + 1)), top=[DARK], front=[DARK], side=[DARK])
    return b.done()


def teacher_desk():
    c = Cell(TEACHER)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 10), (2, 15)), top=top_rect((1, 1, 15, 8), (1, 15), (2, 15)),
          front=front_rect((1, 8, 15, 15), (1, 15), (0, 10)), side=[WOOD, WOOD_D])
    return b.done()


def main():
    out = Pass("labs")
    for key, label in NEVER.items():
        out.keep(key, label=label, family="labs-never-drawn",
                 reason="Every placement is a walkable cell and the entry has no walk or seat flag: never drawn (census: walk).")
    for key in DRAGONS:
        out.cell(key, *I.wall_relief(key, background=[NAVY_D, BLACK], depth=2, side=[NAVY_D, NAVY, BLACK]),
                 label="Dragon Shrine carved dragon (wall row)", family="dragon-shrine",
                 reason="Wall-row cell drawn in place: the wall block with the carved dragon standing two voxels proud of its "
                        "dark ground, instead of a free-standing extrusion that opened the wall.")
    out.cell(CASE_ELM, *I.case_base(CASE_ELM, rows=(0, 13), shelf=((3, 13), (9, 15), (1, 6))), label="lab bookcase lower half (Elm's)",
             family="bookcase", reason="Bookcase base: a recessed shelf of books over two doors.")
    out.cell(CASE_OAK, *I.case_base(CASE_OAK, rows=(0, 15), shelf=((3, 13), (10, 15), (1, 6))), label="lab bookcase lower half (Oak's, Academy)",
             family="bookcase", reason="Bookcase base: a recessed shelf of books over drawers.")
    out.cell(CASE_TOP, *I.case_top(CASE_TOP, front_rows=(8, 16), top_rows=(1, 8), shelf=((3, 13), (1, 7), (9, 15))),
             label="lab bookcase upper half (stacked)", family="bookcase",
             reason="Stacked on its base: the upper shelf of books and the top. alsoOn adds Elm's base variant (amendment), "
                    "where it had stood in place in the wall row.")
    out.also_on(CASE_TOP, [CASE_ELM], "ELMS_LAB (6..9,0) over 1407-2481-2482-2483 (12 placements): the same bookcase top drawn "
                                      "over Elm's base variant; without the link it stood in place in the wall row.")
    out.entity("crystal_lab_bookcase_elm", [[CASE_TOP], [CASE_ELM]], "Elm's lab bookcase (alsoOn stack)", kind="stack (model as one)")
    out.entity("crystal_lab_bookcase_oak", [[CASE_TOP], [CASE_OAK]], "Oak's lab / Academy bookcase (stack)", kind="stack (model as one)")
    out.cell(PEDESTAL_F, *I.statue_pedestal(PEDESTAL_F, stone=(GREY, GREY_D, DARK)), label="gym statue pedestal (Fuchsia)",
             family="gym-statue", reason="The statue's stone pedestal with its drawn frame and plaque, filling the base cell.")
    out.cell(FIGURE, *I.statue_figure(FIGURE, stone=(GREY, GREY_D, DARK)), label="gym statue figure (stacked)", family="gym-statue",
             reason="The rounded stone figure standing on its pedestal. alsoOn adds Fuchsia's pedestal variant (amendment), "
                    "where it had stood in place on the floor north of the pedestal.")
    out.also_on(FIGURE, [PEDESTAL_F], "FUCHSIA_GYM (3,14) and (6,14) over 1162-1163-2541-2542: the same statue over Fuchsia's "
                                      "pedestal variant; without the link the figure stood on the floor behind its pedestal.")
    out.entity("crystal_gym_statue_fuchsia", [[FIGURE], [PEDESTAL_F]], "Fuchsia Gym statue (alsoOn stack)", kind="stack (model as one)")
    for key, (edges, legs, label) in TABLE.items():
        out.cell(key, *I.table_piece(key, edges=edges, legs=legs), label=label, family="table",
                 reason="The shared table system: the lab table is one row deep (its front row is floor), legs at both ends.")
    out.cell(BALL, *ball_display(), label="Poke Ball display at the table's end", family="lab-equipment",
             reason="A grey stand with the drawn Poke Ball as a sphere on it.")
    out.entity("crystal_lab_table", [[K("2479-659-662-663"), K("659-659-663-663"), K("659-2480-663-2490"), BALL]],
               "Elm's / Oak's lab table and the Poke Ball display")
    for key, (part, label) in MACHINE.items():
        out.cell(key, *machine(key, part), label=label, family="lab-equipment",
                 reason="Elm's 2x2 research machine as solid machine blocks: the monitors along the back, the pipes and "
                        "console in front, grey elsewhere.")
    out.entity("crystal_elm_research_machine", [[K("270-2533-274-275"), K("2534-273-276-277")], [K("278-0-280-2535"), K("0-279-2536-283")]],
               "Elm's research machine (2x2)")
    out.cell(PC, *pc_desk(PC, "pc"), label="lab PC", family="lab-equipment",
             reason="A grey desk with the cream monitor standing on it and the keyboard in front.")
    out.cell(PRINTER, *pc_desk(PRINTER, "printer"), label="lab printer", family="lab-equipment",
             reason="A grey desk with the printer box on it.")
    out.cell(WINDOW, *window(), label="lab window (wall row)", family="lab-wall",
             reason="Wall row, drawn in place: the wall block with the glass recessed and the sill proud.")
    out.cell(MONITOR, *I.wall_relief(MONITOR, background=[CREAM], depth=2, side=[CREAM, DARK]), label="lab wall monitor (wall row)",
             family="lab-wall", reason="Wall row, drawn in place: the wall block with the monitor and its stand proud of the cream wall.")
    out.cell(CORNER_A, *corner(CORNER_A, "machine"), label="Elm's corner machine", family="lab-equipment",
             reason="A grey machine with its dial and panel on top and its front below.")
    out.cell(CORNER_B, *corner(CORNER_B, "desk"), label="Elm's corner desk with computer", family="lab-equipment",
             reason="A grey desk with the computer (its checker screen top) standing on it.")
    out.entity("crystal_elm_corner", [[CORNER_A, CORNER_B]], "Elm's corner machine and desk")
    out.cell(TEACHER, *teacher_desk(), label="Academy teacher's desk", family="academy",
             reason="A small wooden desk: its top and its two-door front.")
    for key, end in DESKS.items():
        out.cell(key, *school_desk(key, end), label=f"school desk ({end} half)", family="academy",
                 reason="Half of a two-cell school desk: the top, the front panel with its drawn slats, the end panel.")
    out.cell(CHAIR, *school_chair(), label="school chair (seat)", family="academy",
             reason="The pupil's chair facing north: seat at the existing seat height, the backrest on its south edge.")
    out.entity("crystal_academy_desk_row", [[K("2523-2524-2526-2527"), K("2524-2525-2527-2528")], [CHAIR, CHAIR]],
               "Academy school desk and chairs")
    out.write(owned=OWNED)


if __name__ == "__main__":
    main()
