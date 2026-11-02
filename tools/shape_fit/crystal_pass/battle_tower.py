"""Crystal checkpoint 14: TILESET_BATTLE_TOWER_INSIDE_1 (the Battle Tower's 1F lobby, hallway and battle room).

Source reading (study/groups/TILESET_BATTLE_TOWER_INSIDE_1):

- The wall row's tan machine cabinets with orange lights (full blocks, the wall closed), the glass doors (warp cells:
  kept, their WARP_MODELS.md doorway frames).
- The hallway's elevator, where the wall is drawn one row lower: its cabinet's top is a full block; the door's top
  (537-537-488-489, a walkable cell) had a stack link onto the walkable door below, which never stacks (a base must be
  solid), so the lone top was dropped and the back wall had a one-cell gap. A metadata amendment marks it walk, so its
  voxels draw in place as the wall's face over the door; the door below (a warp cell) keeps its WARP_MODELS.md frame.
- The reception counters: cream tops and blue fronts at counter height, the monitor and the keyboard standing on the
  counter's end; the blue terminal (stack); the gold plinths with their emblem.
- The battle room's grey corner machines (stacks).
- Never drawn (retained): the cabinet bottom in the walkable row below the wall.
"""
from __future__ import annotations

from tools.shape_fit.crystal_pass import interiors as I
from tools.shape_fit.crystal_pass.common import K, Pass
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, front_rect, top_rect

S = (16, 16, 16)
DARK, CREAM, GREY, GREY_D = I.DARK, I.CREAM, I.GREY, I.GREY_D
BLUE, BLUE_L = (74, 74, 255), (123, 132, 255)
TAN, TAN_D, TAN_L = (173, 140, 58), (132, 107, 25), (214, 197, 140)
TOPDRAW = top_rect((0, 0, 16, 16), (0, 16), (0, 16))
FULL = front_rect((0, 0, 16, 16), (0, 16), (0, 16))
FAMILY = "TILESET_BATTLE_TOWER_INSIDE_1"

CABINETS = [K("484-485-486-487"), K("538-537-484-485"), K("536-537-484-485")]
DOOR, HALL_DOOR, HALL_DOOR_TOP = K("488-489-490-491"), K("490-491-483-483"), K("537-537-488-489")
COUNTER_FRONT = [K("479-480-481-482"), K("480-480-482-482"), K("518-562-481-482")]
COUNTER_ARM = K("518-519-518-519")
MONITOR, KEYBOARD = K("530-531-532-533"), K("559-560-482-561")
TERM, TERM_TOP = K("492-493-494-495"), K("496-497-498-499")
PLINTH = K("500-501-502-503")
CORNER, CORNER_TOP = K("509-510-511-511"), K("547-548-507-508")
CABINET_FOOT = K("486-487-483-483")


def own(key, n=4):
    from collections import Counter
    c = Cell(key)
    return [col for col, _ in Counter(tuple(int(a) for a in px) for px in c.pixels.reshape(-1, 3)).most_common(n)]


def full_block(key, top=None):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 16), (0, 16)), top=top or own(key), front=FULL, side=own(key))
    return b.done()


def panel(key, rows=(0, 16)):
    """A walkable door: a thin panel against the back showing the drawn door (no collision); its edges in the door
    frame's cream and dark (not the floor drawn below it)."""
    c = Cell(key)
    b = Build(c)
    frame = [CREAM, DARK, BLUE_L]
    b.add(box(S, (0, 16), (0, 16), (0, 2)), top=frame, front=front_rect((0, rows[0], 16, rows[1]), (0, 16), (0, 16)), side=frame)
    return b.done()


def counter(key, *, front_rows=None):
    """A reception counter piece at counter height: its cream top (the drawn top rows) and its blue front."""
    c = Cell(key)
    b = Build(c)
    if front_rows:
        b.add(box(S, (0, 16), (0, 8), (0, 16)), top=top_rect((0, 0, 16, front_rows[0]), (0, 16), (0, 16)),
              front=front_rect((0, front_rows[0], 16, front_rows[1]), (0, 16), (0, 8)), side=[BLUE, CREAM, DARK])
    else:
        b.add(box(S, (0, 16), (0, 8), (0, 16)), top=TOPDRAW, front=[BLUE, DARK], side=[BLUE, CREAM, DARK])
    return b.done()


def monitor():
    """The counter's end with a monitor standing on it: cream top (drawn rows 0-4), the grey frame and screen (rows 5-14)."""
    c = Cell(MONITOR)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 8), (0, 16)), top=[CREAM], front=[BLUE, DARK], side=[BLUE, CREAM, DARK])
    b.add(box(S, (2, 14), (8, 16), (4, 13)), top=top_rect((1, 0, 15, 5), (2, 14), (4, 13)), front=front_rect((1, 5, 15, 15), (2, 14), (8, 16)),
          side=[GREY, GREY_D, DARK])
    return b.done()


def keyboard():
    """The counter's end with the keyboard on it (drawn rows 0-7) and the blue front (rows 8-15)."""
    c = Cell(KEYBOARD)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 8), (0, 16)), top=top_rect((0, 0, 16, 8), (0, 16), (0, 16)), front=front_rect((0, 8, 16, 16), (0, 16), (0, 8)),
          side=own(KEYBOARD))
    b.add(box(S, (2, 14), (8, 9), (2, 8)), top=top_rect((1, 0, 15, 7), (2, 14), (2, 8)), front=own(KEYBOARD), side=own(KEYBOARD))
    return b.done()


def terminal():
    c = Cell(TERM)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 16), (3, 14)), top=[BLUE, BLUE_L], front=FULL, side=[BLUE, BLUE_L, DARK])
    return b.done()


def terminal_top():
    """Stacked on the body: the terminal's monitor (front drawn rows 8-15, its top rows 1-7)."""
    c = Cell(TERM_TOP)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 8), (3, 14)), top=top_rect((1, 1, 15, 7), (1, 15), (3, 14)), front=front_rect((1, 8, 15, 16), (1, 15), (0, 8)),
          side=[BLUE, BLUE_L, DARK])
    return b.done()


def plinth():
    """A gold plinth: its emblem top (drawn rows 0-9) and front (rows 10-15), one block high."""
    c = Cell(PLINTH)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 8), (0, 16)), top=top_rect((0, 0, 16, 10), (0, 16), (0, 16)), front=front_rect((0, 10, 16, 16), (0, 16), (0, 8)),
          side=[TAN, TAN_D, DARK])
    return b.done()


def corner(top):
    """The battle room's grey corner machine in the cell's north half: the body (its face: the base's drawn rows 0-6)
    and, stacked on it, the cap (the top's rows 8-15)."""
    key = CORNER_TOP if top else CORNER
    c = Cell(key)
    b = Build(c)
    grey = [GREY, GREY_D, DARK]
    if top:
        b.add(box(S, (1, 15), (0, 6), (0, 8)), top=top_rect((1, 8, 15, 11), (1, 15), (0, 8)), front=front_rect((1, 10, 15, 16), (1, 15), (0, 6)), side=grey)
    else:
        b.add(box(S, (1, 15), (0, 16), (0, 8)), top=grey, front=front_rect((1, 0, 15, 7), (1, 15), (0, 16)), side=grey)
    return b.done()


def main():
    out = Pass("battle-tower")
    for key in CABINETS:
        out.cell(key, *full_block(key), label="wall cabinet", family="bt-wall",
                 reason="A machine cabinet filling its wall-row cell: a full block, its drawn front (the wall stays closed).")
    out.keep_warp(DOOR, label="glass door (warp)", family="bt-wall")
    out.keep_warp(HALL_DOOR, label="hallway elevator door (warp)", family="bt-wall")
    out.cell(HALL_DOOR_TOP, *full_block(HALL_DOOR_TOP), label="hallway wall over the elevator door", family="bt-wall",
             reason="The wall's face over the elevator door: walk voxels drawn in place (amendment), a full block of decor "
                    "that closes the back wall.")
    out.set_metadata(HALL_DOOR_TOP, {"walk": True},
                     "BATTLE_TOWER_HALLWAY (11,0): a walkable wall-row cell over the elevator door 490-491-483-483 (a walkable "
                     "warp). Its stack link names that walkable door, which never stacks (Units.stack needs a solid base), so "
                     "the lone top was dropped and the back wall showed a one-cell gap; as walk voxels it draws in place.")
    for key in COUNTER_FRONT:
        out.cell(key, *counter(key, front_rows=(8, 16)), label="reception counter (front)", family="bt-lobby",
                 reason="The counter at counter height: its cream top and the blue front with its dots.")
    out.cell(COUNTER_ARM, *counter(COUNTER_ARM), label="reception counter (arm)", family="bt-lobby",
             reason="The counter's arm at counter height, its drawn cream top with the blue edges.")
    out.cell(MONITOR, *monitor(), label="counter end with a monitor", family="bt-lobby",
             reason="The counter's end with a monitor standing on it.")
    out.cell(KEYBOARD, *keyboard(), label="counter end with a keyboard", family="bt-lobby",
             reason="The counter's end with the keyboard on it and its blue front.")
    out.entity("crystal_bt_counter", [[COUNTER_ARM, None, None, None, None, None, None, MONITOR],
                                      [K("518-562-481-482"), COUNTER_FRONT[1], COUNTER_FRONT[1], None, None, COUNTER_FRONT[0],
                                       COUNTER_FRONT[1], KEYBOARD]], "Battle Tower reception counter (part)")
    out.cell(TERM, *terminal(), label="blue terminal", family="bt-lobby", reason="The terminal's body, its drawn front.")
    out.cell(TERM_TOP, *terminal_top(), label="blue terminal monitor (stacked)", family="bt-lobby",
             reason="Stacked on the body: the terminal's monitor.")
    out.entity("crystal_bt_terminal", [[TERM_TOP], [TERM]], "Battle Tower terminal (stack)", kind="stack (model as one)")
    out.cell(PLINTH, *plinth(), label="gold plinth", family="bt-lobby", reason="A gold plinth one block high with its emblem.")
    out.cell(CORNER, *corner(False), label="battle room corner machine", family="bt-battle-room",
             reason="The grey machine's body in the cell's north half (the floor drawn in front).")
    out.cell(CORNER_TOP, *corner(True), label="battle room corner machine cap (stacked)", family="bt-battle-room",
             reason="Stacked on the body: the machine's cap.")
    out.entity("crystal_bt_corner", [[CORNER_TOP], [CORNER]], "Battle room corner machine (stack)", kind="stack (model as one)")
    out.entity("crystal_bt_elevator", [[CABINETS[2], HALL_DOOR_TOP], [CABINET_FOOT, HALL_DOOR]], "Hallway elevator and cabinet")
    out.keep(CABINET_FOOT, label="cabinet bottom (walkable, never drawn)", family="bt-retained",
             reason="A walkable cell below the wall with no walk or seat model: never drawn, its entry is kept.")
    out.write(families=[FAMILY])


if __name__ == "__main__":
    main()
