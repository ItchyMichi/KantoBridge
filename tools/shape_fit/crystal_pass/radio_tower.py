"""Crystal checkpoint 9: TILESET_RADIO_TOWER_1 (Goldenrod Radio Tower 1F-5F, the Lavender Radio Tower 1F).

Source reading (study/groups/TILESET_RADIO_TOWER_1; composites _rt_*):

- The studio's window (LAV 1F and 5F): a pale rail and glass drawn over two rows. It is modelled as a glass wall one
  cell tall straddling the two rows: the rail row's south half carries the rail and the glass seen from the lobby; the
  glass row's north half carries the studio side of the glass. In that row's south half stand the north end of the DJ
  desk (counter height, with the microphone) and, in the window's line, the equipment rack and the two speakers.
- The DJ desk, its script and the reception counter's corner: counter height (Tier A draws the counters one block, plain).
- Stacks: the reception PC (monitor on its desk), the record racks (wall row top on its base), and the three-puff hedges
  in grey planters (the crown over the planter; in place on the floor where its base is not the planter).
- The round white tables: in the wall row the table half stands against the wall (the wall stays closed behind it), its
  stand's foot in the walkable cell below; the 2F one-cell table between two stools.
- The 2F album display (2x2, a back tier with the upper album row, a lower front tier with the lower row and its base),
  the 5F console (its top in the back cell, its front with the drawers), the low partition, the stools, wall posters and
  the 3F wall device.
- Never drawn (retained): the counter cells (Crystal never draws fitted counters) and two walkable cells. The links of
  the equipment rack and the 3F wall device onto walkable cells never stack at runtime (a base must be solid); they are
  left unchanged.
"""
from __future__ import annotations

import numpy as np

from tools.shape_fit.crystal_pass import furniture as F
from tools.shape_fit.crystal_pass import interiors as I
from tools.shape_fit.crystal_pass.common import K, Pass
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, front_rect, top_rect

S = (16, 16, 16)
DARK = I.DARK
PALE, PINK, RED, WHITE = (222, 255, 222), (255, 156, 197), (247, 82, 49), (255, 255, 255)
GREY_L, GREY_D = (173, 173, 173), (107, 107, 107)
CYAN, GLASS_B = (123, 255, 255), (41, 140, 255)
BLUE_W, BLUE_D = (66, 99, 255), (8, 33, 255)
GREEN_D, GREEN_L = (41, 115, 0), (99, 206, 8)
BRICK, BRICK_D = (197, 148, 58), (165, 123, 25)
FULL = front_rect((0, 0, 16, 16), (0, 16), (0, 16))
TOPDRAW = top_rect((0, 0, 16, 16), (0, 16), (0, 16))
GREYS = [GREY_L, GREY_D, PALE, DARK]
GLASS = [CYAN, GLASS_B, PALE, DARK]

DESK, PAPER, CORNER = K("2984-3617-2984-3617"), K("3684-3685-3686-3687"), K("2991-3616-2995-3617")
PC_BASE, PC_TOP = K("3092-3657-3658-3659"), K("3606-3606-3655-3656")
CONSOLE_BACK, CONSOLE_FRONT = K("3605-3605-3663-3664"), K("3666-3667-3648-3649")
PARTITION, POSTER, WALL_DEVICE = K("3605-3682-3605-3683"), K("3607-3608-3610-3611"), K("3606-3606-3609-3688")
WALL_TABLE, WALL_TABLE_PHONE, TABLE_FOOT = K("3606-3606-3650-3651"), K("3606-3606-3650-3652"), K("3653-3654-3605-3605")
ROUND_TABLE = K("3703-3704-3653-3654")
RED_STOOL, BROWN_STOOL = K("3626-3627-3628-3629"), K("3696-3697-3698-3699")
RACK_TOP, RACK_BASE = K("3630-3631-3632-3633"), K("3632-3633-3634-3635")
HEDGE_WALL, HEDGE_ROOM, HEDGE_EDGE, HEDGE_BASE = (K("3636-3637-3638-3639"), K("3690-3691-3640-3641"), K("3680-3681-3640-3641"),
                                                  K("3640-3641-3642-3643"))
WIN_TOP, WIN = K("3630-3631-3660-3661"), K("3660-3661-3668-3669")
WIN_DESK, WIN_MIC, SPEAKER = K("3660-3661-2992-3616"), K("3660-3661-3674-3675"), K("3701-3661-3702-3669")
SPEAKER_FOOT, EQUIPMENT = K("3702-3679-3678-3679"), K("3670-3671-3672-3673")
DISPLAY = {"nw": K("3705-3706-3709-3710"), "ne": K("3707-3708-3711-3712"), "sw": K("3713-3714-3717-3718"),
           "se": K("3715-3716-3719-3720")}
COUNTERS = [K(k) for k in ("2991-2992-3624-3618", "2991-2992-3624-3619", "2992-2992-3618-3618", "2992-3623-3618-3618",
                           "2992-3623-3618-3619", "2995-3617-2995-3617", "2995-3617-3624-3619", "3622-2992-3618-3618",
                           "3622-3623-3618-3625", "3623-3616-3618-3619", "3623-3616-3648-3649", "3663-3664-3666-3667",
                           "3676-3677-2995-2984")]
WALKS = [K("3605-3689-3605-3605"), K("3672-3673-3678-3679")]
FAMILY = "TILESET_RADIO_TOWER_1"


def slab(key, *, h=8, x=(0, 16), z=(0, 16), top=TOPDRAW, front=None, side=None):
    """A counter-height slab (Tier A draws the counters one block, plain): its drawn top."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, x, (0, h), z), top=top, front=front or GREYS, side=side or GREYS)
    return b, c


def desk_with_paper():
    b, c = slab(PAPER)
    b.add(box(S, (5, 13), (8, 9), (1, 14)), top=TOPDRAW, front=[PALE, GREY_L], side=[PALE, GREY_L])
    return b.done()


def pc_base():
    """The reception PC's desk (its front with the keyboard drawer: drawn rows 6-15) and the monitor's lower casing on
    it (rows 0-5); the monitor's screen is the stacked top."""
    c = Cell(PC_BASE)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 8), (2, 15)), top=[PALE, GREY_L], front=front_rect((0, 6, 16, 16), (1, 15), (0, 8)), side=GREYS)
    b.add(box(S, (3, 13), (8, 16), (4, 12)), top=[GREY_L, GREY_D], front=front_rect((2, 0, 14, 6), (3, 13), (8, 16)), side=GREYS)
    return b.done()


def pc_top():
    """Stacked on the desk: the monitor's upper casing with the screen (drawn rows 8-15; the wall above is not modelled)."""
    c = Cell(PC_TOP)
    b = Build(c)
    b.add(box(S, (3, 13), (0, 7), (4, 12)), top=[GREY_L, GREY_D], front=front_rect((2, 8, 14, 16), (3, 13), (0, 7)), side=GREYS)
    return b.done()


def console_back():
    """The 5F console's back half: its top with the screen and keys (drawn rows 8-15) over the cell's south half."""
    b, c = slab(CONSOLE_BACK, h=14, z=(8, 16), top=top_rect((0, 8, 16, 16), (0, 16), (8, 16)))
    return b.done()


def console_front():
    """The console's front: the key panel and the drawers (drawn rows 0-15) on its south face."""
    b, c = slab(CONSOLE_FRONT, h=14, x=(1, 15), z=(0, 14), top=[GREY_L, GREY_D], front=front_rect((1, 0, 15, 15), (1, 15), (0, 14)))
    return b.done()


def partition():
    """The low pale partition along the east half of the cell (drawn columns 8-15), one block high."""
    b, c = slab(PARTITION, x=(8, 16), top=top_rect((8, 0, 16, 16), (8, 16), (0, 16)), front=[PALE, GREY_L, DARK],
                side=[PALE, GREY_L, DARK])
    return b.done()


def wall_table(key, phone):
    """A round white table standing against the wall: the wall's lower part (its drawn dots) closes the back, the table's
    half top (drawn rows 8-15) at seat-table height in front of it on a dark stand."""
    c = Cell(key)
    b = Build(c)
    blue = [BLUE_W, BLUE_D]
    b.add(box(S, (0, 16), (0, 16), (0, 10)), top=blue, front=front_rect((0, 0, 16, 8), (0, 16), (0, 16)), side=blue)
    top = F.cylinder(S, (8, 10), 7.5, (7, 9)) & (np.indices(S)[1] >= 10)
    b.add(top, top=top_rect((0, 8, 16, 16), (0.5, 15.5), (10, 16)), front=[DARK, WHITE], side=[DARK, WHITE])
    b.add(box(S, (7, 9), (1, 7), (11, 13)), top=[DARK], front=[DARK], side=[DARK])
    b.add(box(S, (5, 11), (0, 1), (10, 15)), top=[DARK], front=[DARK], side=[DARK])
    if phone:
        b.add(box(S, (8, 13), (9, 11), (11, 14)), top=top_rect((8, 10, 13, 14), (8, 13), (11, 14)), front=[DARK], side=[DARK])
    return b.done()


def table_foot():
    """The walkable cell below a wall table: only the foot of its stand reaching out (the table stands in the wall row)."""
    c = Cell(TABLE_FOOT)
    b = Build(c)
    b.add(box(S, (6, 10), (0, 1), (0, 3)), top=[DARK], front=[DARK], side=[DARK])
    return b.done()


def round_table():
    """The 2F round white table: the white top (drawn rows 0-7) on a dark pedestal and foot (rows 8-15)."""
    c = Cell(ROUND_TABLE)
    b = Build(c)
    b.add(F.cylinder(S, (8, 8), 7.5, (9, 11)), top=top_rect((0, 0, 16, 8), (0.5, 15.5), (0.5, 15.5)), front=[DARK, PALE],
          side=[DARK, PALE])
    b.add(box(S, (7, 9), (1, 9), (7, 9)), top=[DARK], front=[DARK], side=[DARK])
    b.add(F.cylinder(S, (8, 8), 3.6, (0, 1)), top=[DARK], front=[DARK], side=[DARK])
    return b.done()


def rack_base():
    """The record rack's lower half: grey case, its two shelves recessed with the records (drawn rows 1-5 and 9-13)."""
    c = Cell(RACK_BASE)
    b = Build(c)
    case = [GREY_L, PALE, GREY_D, DARK]
    b.add(box(S, (1, 15), (0, 16), (6, 15)), top=[GREY_L], front=front_rect((1, 0, 15, 15), (1, 15), (0, 16)), side=case)
    for sy, sr in (((10, 15), (1, 6)), ((2, 7), (9, 14))):
        b.cut(box(S, (2, 14), sy, (12, 15)))
        b.add(box(S, (2, 14), sy, (12, 13)), top=[GREY_D], front=front_rect((2, sr[0], 14, sr[1]), (2, 14), sy), side=case)
    return b.done()


def rack_top():
    """Stacked on its base: the rack's upper shelf (front rows 7-15, the records recessed) and its pale top (rows 1-6)."""
    return I.case_top(RACK_TOP, front_rows=(7, 16), top_rows=(1, 6), height=12, shelf=((2, 14), (3, 9), (9, 14)),
                      wood=(PALE, GREY_L))


def puff(b, yc, rows, ry=3.6):
    r0, r1 = rows
    occ = F.ellipsoid(S, (8, yc, 8), (6.8, ry, 6))
    b.add(occ, top=top_rect((1, r0, 15, r1), (1.2, 14.8), (2, 14)), front=front_rect((1, r0, 15, r1), (1.2, 14.8), (yc - ry, yc + ry)),
          side=[GREEN_L, GREEN_D])


def hedge_top(key):
    """A hedge's upper two puffs (drawn rows 1-7 and 8-15): stacked on the planter, or on the floor where it stands in place."""
    c = Cell(key)
    b = Build(c)
    puff(b, 3.6, (8, 15))
    puff(b, 10.4, (1, 8))
    return b.done()


def hedge_base():
    """The grey planter (its front drawn rows 9-15, its pale rim) and the hedge's lowest puff (rows 0-7) in it."""
    c = Cell(HEDGE_BASE)
    b = Build(c)
    b.add(box(S, (2, 14), (0, 6), (3, 13)), top=[PALE, GREY_L], front=front_rect((2, 9, 14, 16), (2, 14), (0, 6)), side=GREYS)
    puff(b, 10.2, (0, 8))
    return b.done()


def glass(b, x=(0, 16), rows=(0, 16)):
    """The studio side of the window in the glass row's north half: a pane one cell tall, the drawn glass and sill on it."""
    b.add(box(S, x, (0, 16), (0, 8)), top=[PALE, GREY_L], front=front_rect((x[0], rows[0], x[1], rows[1]), x, (0, 16)), side=GLASS)


def window_top():
    """The rail row: the window's lobby half (south half of the cell) with the pale rail on top (drawn rows 1-4) and the
    glass on its lobby face (rows 8-15)."""
    c = Cell(WIN_TOP)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 16), (8, 16)), top=top_rect((0, 1, 16, 5), (0, 16), (8, 16)),
          front=front_rect((1, 8, 15, 16), (0, 16), (0, 16)), side=[PALE, GREY_L, DARK],
          back=front_rect((1, 8, 15, 16), (0, 16), (0, 16)))
    return b.done()


def window():
    c = Cell(WIN)
    b = Build(c)
    glass(b)
    return b.done()


def window_desk():
    """The window and, in front of it, the DJ desk's north end at counter height (its top: drawn rows 9-15)."""
    c = Cell(WIN_DESK)
    b = Build(c)
    glass(b, rows=(0, 9))
    b.add(box(S, (0, 16), (0, 8), (8, 16)), top=top_rect((0, 9, 16, 16), (0, 16), (8, 16)), front=GREYS, side=GREYS)
    return b.done()


def window_mic():
    """The window and the DJ desk's north end with the microphone's head standing on it (drawn rows 9-15)."""
    c = Cell(WIN_MIC)
    b = Build(c)
    glass(b, rows=(0, 9))
    b.add(box(S, (0, 16), (0, 8), (8, 16)), top=top_rect((0, 9, 16, 16), (0, 16), (8, 16)), front=GREYS, side=GREYS)
    b.add(box(S, (4, 12), (8, 12), (10, 15)), top=top_rect((2, 9, 13, 16), (4, 12), (10, 15)), front=[DARK, GREY_D], side=[DARK, GREY_D])
    return b.done()


def speaker():
    """A speaker cabinet standing in the window's line (drawn columns 0-7: grey top rows 1-6, the grille rows 7-15) and
    the window beside it (columns 8-15)."""
    c = Cell(SPEAKER)
    b = Build(c)
    b.add(box(S, (0, 8), (0, 14), (6, 16)), top=top_rect((1, 1, 7, 7), (0, 8), (6, 16)), front=front_rect((0, 7, 8, 16), (0, 8), (0, 14)),
          side=[GREY_D, DARK])
    glass(b, x=(8, 16))
    return b.done()


def speaker_foot():
    """The walkable cell in front of a speaker: its plinth's lip only."""
    c = Cell(SPEAKER_FOOT)
    b = Build(c)
    b.add(box(S, (1, 7), (0, 1), (0, 3)), top=[DARK], front=[DARK], side=[DARK])
    return b.done()


def equipment():
    """The studio's equipment rack in the window's line: a dark case with the units' fronts (drawn rows 0-12)."""
    c = Cell(EQUIPMENT)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 16), (2, 16)), top=[GREY_D, DARK], front=front_rect((1, 0, 15, 13), (1, 15), (0, 16)), side=[GREY_D, DARK])
    return b.done()


def display(part):
    """The 2F album display, a 2x2 cabinet: the back cells a full tier (pale top: drawn rows 0-6; the upper album row on
    the face above the front tier: rows 7-15), the front cells a lower tier (the lower album row and the plain base:
    rows 0-15 over its face)."""
    key = DISPLAY[part]
    c = Cell(key)
    b = Build(c)
    side = [PALE, GREY_L, DARK]
    if part in ("nw", "ne"):
        b.add(box(S, (0, 16), (0, 16), (6, 16)), top=top_rect((0, 0, 16, 7), (0, 16), (6, 16)),
              front=front_rect((0, 7, 16, 16), (0, 16), (10, 16)), side=side)
    else:
        b.add(box(S, (0, 16), (0, 10), (0, 12)), top=[PALE], front=front_rect((0, 0, 16, 16), (0, 16), (0, 10)), side=side)
    return b.done()


def main():
    out = Pass("radio-tower")
    fam = "radio-studio"
    out.cell(DESK, *slab(DESK)[0].done(), label="DJ desk", family=fam,
             reason="The studio's long grey desk at counter height (Tier A's counters beside it are one block), its drawn top.")
    out.cell(PAPER, *desk_with_paper(), label="DJ desk with the script", family=fam,
             reason="The desk at counter height with the script lying on it.")
    out.cell(WIN_TOP, *window_top(), label="studio window, rail row", family=fam,
             reason="The window's lobby half: a pane in the cell's south half with the pale rail on top and the glass on its "
                    "lobby face (the window is drawn over two rows; it straddles them, one cell tall).")
    out.cell(WIN, *window(), label="studio window, glass row", family=fam,
             reason="The window's studio half: the pane in the cell's north half, the drawn glass and sill on its face.")
    out.cell(WIN_DESK, *window_desk(), label="studio window with the desk's north end", family=fam,
             reason="The pane, and in front of it the DJ desk's north end at counter height.")
    out.cell(WIN_MIC, *window_mic(), label="studio window with the microphone", family=fam,
             reason="The pane, the desk's north end and the microphone's head standing on it.")
    out.cell(SPEAKER, *speaker(), label="speaker cabinet beside the window", family=fam,
             reason="A speaker cabinet in the window's line, its grille on the face; the pane beside it.")
    out.cell(SPEAKER_FOOT, *speaker_foot(), label="in front of the speaker (walkable)", family=fam,
             reason="A walkable cell: only the speaker plinth's lip (the speaker's lower face drawn here belongs to the "
                    "cabinet in the cell behind).")
    out.cell(EQUIPMENT, *equipment(), label="studio equipment rack", family=fam,
             reason="A dark equipment rack in the window's line, the units' fronts on its face (drawn in place: its link "
                    "names a walkable cell, which never stacks).")
    out.entity("crystal_radio_studio_window", [[WIN_TOP] * 8, [SPEAKER, WIN, EQUIPMENT, WIN_MIC, WIN_DESK, WIN, SPEAKER, WIN]],
               "Radio Tower studio window (LAV 1F x 12-19)")
    out.entity("crystal_radio_dj_desk", [[WIN_MIC, WIN_DESK], [K("3676-3677-2995-2984"), DESK], [PAPER, DESK]],
               "DJ desk (the microphone cell is a counter: Tier A draws it at runtime)")

    fam = "radio-lobby"
    out.cell(CORNER, *slab(CORNER)[0].done(), label="reception counter corner", family=fam,
             reason="The L counter's corner at counter height (Tier A draws the counters beside it), its drawn top.")
    out.cell(PC_BASE, *pc_base(), label="reception PC desk", family=fam,
             reason="The PC's desk with the keyboard drawer, the monitor's lower casing on it.")
    out.cell(PC_TOP, *pc_top(), label="reception PC monitor (stacked)", family=fam,
             reason="Stacked on the desk: the monitor with its screen.")
    out.entity("crystal_radio_pc", [[PC_TOP], [PC_BASE]], "Radio Tower reception PC (stack)", kind="stack (model as one)")
    out.cell(CONSOLE_BACK, *console_back(), label="5F console, back half", family=fam,
             reason="The console's back half with its drawn top (screen and keys) over the cell's south half.")
    out.cell(CONSOLE_FRONT, *console_front(), label="5F console, front", family=fam,
             reason="The console's front: key panel and drawers on its face.")
    out.entity("crystal_radio_console", [[CONSOLE_BACK], [CONSOLE_FRONT]], "Radio Tower 5F console")
    out.cell(PARTITION, *partition(), label="low partition", family=fam,
             reason="The pale partition on the cell's east half, one block high, its drawn top.")
    out.cell(POSTER, *I.wall_relief(POSTER, background=[BLUE_W, BLUE_D], depth=1, side=[BLUE_W, BLUE_D]),
             label="wall poster", family=fam, reason="A wall-row cell: the wall closed, the poster board standing one voxel proud.")
    out.cell(WALL_DEVICE, *I.wall_relief(WALL_DEVICE, background=[BLUE_W, BLUE_D], depth=5, side=[BLUE_W, BLUE_D]),
             label="3F wall device", family=fam,
             reason="A wall-row cell: the wall closed, the small grey device at its foot standing five voxels proud.")
    out.cell(RACK_BASE, *rack_base(), label="record rack, base", family=fam,
             reason="The rack's lower half: grey case with two recessed shelves of records.")
    out.cell(RACK_TOP, *rack_top(), label="record rack, top (stacked)", family=fam,
             reason="Stacked on its base: the upper shelf, recessed, and the pale top.")
    out.entity("crystal_radio_record_rack", [[RACK_TOP], [RACK_BASE]], "Radio Tower record rack (stack)", kind="stack (model as one)")

    fam = "radio-furniture"
    out.cell(WALL_TABLE, *wall_table(WALL_TABLE, False), label="round table against the wall", family=fam,
             reason="A wall-row cell: the wall's lower part closes the back, the round table's half top in front of it on a "
                    "dark stand (its foot reaches the walkable cell below).")
    out.cell(WALL_TABLE_PHONE, *wall_table(WALL_TABLE_PHONE, True), label="round table against the wall, with a phone",
             family=fam, reason="As the plain one, with the dark set standing on the table.")
    out.cell(TABLE_FOOT, *table_foot(), label="wall table's foot (walkable)", family=fam,
             reason="A walkable seat cell: only the foot of the wall table's stand.")
    out.entity("crystal_radio_wall_tables", [[WALL_TABLE, WALL_TABLE_PHONE], [TABLE_FOOT, TABLE_FOOT]], "Wall tables (LAV 1F)")
    out.cell(ROUND_TABLE, *round_table(), label="round white table", family=fam,
             reason="The round white top on a dark pedestal and foot.")
    out.cell(RED_STOOL, *I.round_stool(RED_STOOL, background=[PALE, PINK], center=(8, 8), radius=5.5, top_rows=(1, 10)),
             label="red stool", family=fam, reason="A round red stool on short legs (17 placements).")
    out.cell(BROWN_STOOL, *I.round_stool(BROWN_STOOL, background=[BRICK, PALE], center=(8, 8), radius=5.5, top_rows=(1, 10)),
             label="brown stool", family=fam, reason="A round brown stool on short legs (the studio, 12 placements).")
    for part in ("nw", "ne", "sw", "se"):
        out.cell(DISPLAY[part], *display(part), label=f"album display ({part})", family=fam,
                 reason="The 2x2 album display: " + ("the back tier with its top and the upper album row."
                                                     if part in ("nw", "ne") else "the lower front tier: lower album row and base."))
    out.entity("crystal_radio_album_display", [[DISPLAY["nw"], DISPLAY["ne"]], [DISPLAY["sw"], DISPLAY["se"]],
                                               [RED_STOOL, ROUND_TABLE]], "2F album display with table and stool")

    fam = "radio-hedges"
    out.cell(HEDGE_BASE, *hedge_base(), label="hedge planter with the lowest puff", family=fam,
             reason="The grey planter and the hedge's lowest puff.")
    for key, where in ((HEDGE_WALL, "the wall row"), (HEDGE_ROOM, "the room"), (HEDGE_EDGE, "2F's west edge")):
        out.cell(key, *hedge_top(key), label=f"hedge's upper puffs ({where})", family=fam,
                 reason="The hedge's upper two puffs, stacked on the planter (in place on the floor where its base is not the "
                        "planter).")
    out.entity("crystal_radio_hedge", [[HEDGE_ROOM], [HEDGE_BASE]], "Radio Tower hedge (stack)", kind="stack (model as one)")

    for key in COUNTERS:
        out.keep(key, label="counter (never drawn)", family="radio-retained",
                 reason="A counter cell: Crystal never draws fitted counters (Tier A's plain counter), so its entry is kept.")
    for key in WALKS:
        out.keep(key, label="walkable (never drawn)", family="radio-retained",
                 reason="A walkable cell with no walk or seat model: never drawn, its entry is kept.")
    out.write(owned=None, families=[FAMILY])


if __name__ == "__main__":
    main()
