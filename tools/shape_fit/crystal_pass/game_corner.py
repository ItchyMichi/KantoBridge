"""Crystal checkpoint 10: TILESET_GAME_CORNER_1 (the Goldenrod and Celadon Game Corners, the prize room, the Celadon and
Olivine cafes, the Safari Zone office, Vermilion Gym).

Source reading (study/groups/TILESET_GAME_CORNER_1; composites _gc_*):

- The slot machine columns are counters (Tier A: one block, its art on top) except each column's last machine (row 10)
  and the end cabinet south of it, which are solid: both one block high, so the column reads as one run; the end
  cabinets show their drawn fronts. The grey square chairs (96) beside them and around the tables.
- The cafe table: a pink octagonal table (dark outline, a thin red-and-dark rim) on a red rug with a cream border, a gold
  centrepiece on Celadon's 2x3 table; Olivine's 2x2 table shares the bottom row's keys, so each cell's octagon is local.
- Stacks: the pink "G" prize machines (a tall cabinet), the blue telephone on its dark stand, the leafy crown over its
  gold urn, Vermilion Gym's statue figure over this family's pedestal (the labs module's `interiors.statue_pedestal`).
- Wall-row cells: the prize exchange machines fill theirs (full blocks, the wall closed); where the prize counter's or the
  blue table's north part is drawn under the wall, the wall closes the cell's north half and the counter (one block) or
  the table top stands in front of it.
- Vermilion Gym's trash cans (a grey can with its round lid).
- Never drawn (retained): the counter cells and the two walkable cells under the prize machines.
"""
from __future__ import annotations

import numpy as np

from tools.shape_fit.crystal_pass import furniture as F
from tools.shape_fit.crystal_pass import interiors as I
from tools.shape_fit.crystal_pass.common import K, Pass
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, front_rect, top_rect

S = (16, 16, 16)
DARK, CREAM, GREY, GREY_D = I.DARK, I.CREAM, I.GREY, I.GREY_D
PINK, RED, ORANGE, YELLOW = (255, 156, 197), (247, 82, 49), (255, 132, 8), (255, 255, 58)
GOLD_L, GOLD, GOLD_D = (214, 197, 140), (173, 140, 58), (132, 107, 25)
BLUE_L, BLUE, BLUE_P, BLUE_M = (123, 132, 255), (74, 74, 255), (140, 156, 255), (115, 132, 255)
GREEN, GREEN_L, GREEN_D = (123, 165, 8), (148, 197, 74), (74, 107, 0)
GREY_S = (156, 156, 156)
TOPDRAW = top_rect((0, 0, 16, 16), (0, 16), (0, 16))
FULL = front_rect((0, 0, 16, 16), (0, 16), (0, 16))
BRICK = front_rect((0, 0, 16, 7), (0, 16), (0, 16))  # the wall's upper half drawn as its face

TABLE = {K("1072-1073-1144-1145"): (0, 8, None), K("1072-1073-1145-1146"): (1, 8, None),
         K("129-1196-129-1198"): (0, None, None), K("1197-130-1199-130"): (1, None, None),
         K("1148-1149-1151-1152"): (0, None, 5), K("1149-1150-1153-1154"): (1, None, 5),
         K("1144-1145-129-1147"): (0, 0, None), K("1145-1146-1147-130"): (1, 0, None)}
CENTRE = (K("129-1196-129-1198"), K("1197-130-1199-130"))
SLOT_PINK, SLOT_YELLOW = K("1090-1091-1094-1095"), K("1092-1093-1096-1097")
END_RED, END_ORANGE = K("1107-1108-1111-1112"), K("1109-1110-1113-1114")
CHAIR = K("1086-1087-1088-1089")
PRIZE_L, PRIZE_R = K("1077-1078-1080-1081"), K("1078-1079-1081-1082")
COUNTER_END = (K("1076-1076-1138-1139"), K("1076-1076-1156-1157"))
PHONE_TOP, PHONE = K("1076-1076-1190-1191"), K("1192-1193-1194-1195")
BT_WALL_L, BT_WALL_R, BT_L, BT_R = (K("1076-1076-1200-1201"), K("1076-1076-1201-1206"), K("1202-1203-1204-1205"),
                                    K("1203-1207-1208-1209"))
G_TOP, G_BASE = K("1115-1116-1117-1118"), K("1119-1120-1121-1122")
CROWN, URN = K("1130-1131-1132-1133"), K("1134-1135-1136-1137")
TRASH, PEDESTAL, FIGURE = K("1166-1167-1168-1169"), K("1162-1163-1164-1165"), K("1158-1159-1160-1161")
COUNTERS = [K(k) for k in ("1098-1099-1094-1095", "1100-1101-1096-1097", "1102-1102-1103-1103", "1104-1105-1103-1106",
                           "1140-1141-1140-1141", "1140-1142-1143-1103", "1155-1105-1155-1105", "1184-1185-1103-1103")]
WALKS = [K("1083-1084-1074-1075"), K("1084-1085-1074-1075")]
FAMILY = "TILESET_GAME_CORNER_1"


def rug_paint(c):
    """The rug's top: the drawn pixel where the drawing shows the rug (red or its cream border), else the nearest red."""
    px = c.pixels.astype(int)
    reds = np.argwhere((px == RED).all(axis=2))

    def at(y, z, x):
        if tuple(px[z, x]) in (RED, CREAM) or not len(reds):
            return x + .5, z + .5
        v, u = reds[np.argmin((reds[:, 0] - z) ** 2 + (reds[:, 1] - x) ** 2)]
        return u + .5, v + .5
    return at


def cafe_table(key):
    """One cell of the octagonal cafe table: the red rug (one voxel) under a pink top (rim y 5-8, the drawn top), the
    octagon cut locally (north chamfer in the top cells, south end and chamfer in the bottom cells), legs near both ends,
    the gold centrepiece on Celadon's middle cells."""
    col, zn, zs = TABLE[key]
    c = Cell(key)
    b = Build(c)
    zz, xx = np.indices((16, 16)) + .5
    u = xx if col == 0 else 16 - xx
    inside = np.ones((16, 16), bool)
    rug = np.ones((16, 16), bool)
    if zn is not None:
        inside &= (zz >= zn) & (u + (zz - zn) >= 7)
        rug &= zz >= zn
    if zs is not None:
        inside &= (zz <= zs) & (u + (zs - zz) >= 5)
    yy = np.indices(S)[0]
    b.add((yy < 1) & rug[None], top=rug_paint(c), front=[RED, CREAM], side=[RED, CREAM])
    b.add((yy >= 5) & (yy < 8) & inside[None], top=TOPDRAW,
          front=front_rect((0, 5, 16, 8), (0, 16), (5, 8)) if zs is not None else [RED, DARK], side=[RED, DARK, PINK])
    lx = (9, 13) if col == 0 else (3, 7)
    if zn is not None:
        b.add(box(S, lx, (1, 5), (zn + 3, zn + 7)), top=[CREAM, PINK], front=[CREAM, PINK, DARK], side=[CREAM, PINK, DARK])
    if zs is not None:
        b.add(box(S, lx, (1, 5), (0, 3)), top=[CREAM, PINK], front=[CREAM, PINK, DARK], side=[CREAM, PINK, DARK])
    if key in CENTRE:
        dome = F.ellipsoid(S, (16 if col == 0 else 0, 8, 8), (7.5, 4.5, 7.5)) & (yy >= 8)
        b.add(dome, top=TOPDRAW, front=[GOLD, GOLD_D, GOLD_L], side=[GOLD_L, GOLD, GOLD_D])
    return b.done()


def slot_machine(key):
    """A column's last slot machine: one block like Tier A's counters beside it, its drawn top."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 8), (0, 16)), top=TOPDRAW, front=[DARK, ORANGE], side=[ORANGE, DARK])
    return b.done()


def end_cabinet(key, red):
    """A slot column's end cabinet, one block: its top (drawn rows 0-9) and its front with the coin slots (rows 10-15)."""
    c = Cell(key)
    b = Build(c)
    cols = (1, 16) if red else (0, 15)
    mat = [RED, PINK, DARK] if red else [ORANGE, YELLOW, DARK]
    b.add(box(S, (0, 16), (0, 8), (0, 14)), top=top_rect((0, 0, 16, 10), (0, 16), (0, 14)),
          front=front_rect((cols[0], 10, cols[1], 16), (0, 16), (0, 8)), side=mat)
    return b.done()


def chair():
    """The grey square chair: its seat (drawn rows 1-8) and front (rows 9-14), five voxels high."""
    c = Cell(CHAIR)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 5), (2, 14)), top=top_rect((1, 1, 15, 9), (1, 15), (2, 14)), front=front_rect((1, 9, 15, 15), (1, 15), (0, 5)),
          side=[GREY_D, DARK, GREY_S])
    return b.done()


def full_machine(key):
    """A prize exchange machine filling its wall-row cell: a full block, its drawn front (the wall stays closed)."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 16), (0, 16)), top=[CREAM, GREY_S], front=FULL, side=[CREAM, GREY_S, DARK])
    return b.done()


def wall_half(b, c):
    tan = [GOLD, GOLD_D, GOLD_L]
    b.add(box(S, (0, 16), (0, 16), (0, 8)), top=tan, front=BRICK, side=tan)


def counter_end(key):
    """The wall row over the prize counter's north end: the wall closes the north half, the counter (one block, its
    drawn top: rows 8-15) stands in front of it, continuing Tier A's counter below."""
    c = Cell(key)
    b = Build(c)
    wall_half(b, c)
    b.add(box(S, (0, 16), (0, 8), (8, 16)), top=top_rect((0, 8, 16, 16), (0, 16), (8, 16)), front=[GREEN, DARK],
          side=[GOLD_L, DARK, GREEN])
    return b.done()


def phone_base():
    """The telephone's dark stand (its front: drawn rows 8-15) and the phone's body on it (rows 0-8)."""
    c = Cell(PHONE)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 8), (3, 14)), top=[GREY_D, DARK], front=front_rect((1, 8, 15, 15), (1, 15), (0, 8)), side=[GREY_D, DARK, GREY_S])
    b.add(box(S, (2, 13), (8, 16), (4, 12)), top=[BLUE, BLUE_L], front=front_rect((1, 0, 15, 8), (2, 13), (8, 16)), side=[BLUE, DARK])
    return b.done()


def phone_top():
    """Stacked on the stand: the phone's upper part with the handset (drawn rows 8-15)."""
    c = Cell(PHONE_TOP)
    b = Build(c)
    b.add(box(S, (1, 13), (0, 5), (4, 12)), top=top_rect((0, 8, 14, 12), (1, 13), (4, 12)), front=front_rect((0, 8, 14, 16), (1, 13), (0, 5)),
          side=[BLUE, BLUE_L, DARK])
    return b.done()


def blue_table(key):
    """The blue table between two chairs, drawn across the wall row and the row below: in the wall row the wall closes
    the north half and the table's back half stands in front; below, its front half with the drawn edge and legs. A
    bottle stands on each half across the seam."""
    c = Cell(key)
    b = Build(c)
    wall = key in (BT_WALL_L, BT_WALL_R)
    left = key in (BT_WALL_L, BT_L)
    legs = (0, 3) if left else (13, 16)
    bottle = (2, 7) if left else (8, 13)
    leg_mat = [GREY_S, GREY_D, DARK]
    if wall:
        wall_half(b, c)
        b.add(box(S, (0, 16), (5, 8), (8, 16)), top=top_rect((0, 8, 16, 16), (0, 16), (8, 16)), front=[BLUE_P, DARK], side=[DARK, CREAM])
        b.add(box(S, legs, (0, 5), (10, 14)), top=leg_mat, front=leg_mat, side=leg_mat)
        b.add(box(S, bottle, (8, 14), (12, 16)), top=top_rect((bottle[0], 11, bottle[1], 16), bottle, (12, 16)), front=[BLUE_M, DARK],
              side=[BLUE_M, DARK, CREAM])
    else:
        b.add(box(S, (0, 16), (5, 8), (0, 7)), top=top_rect((0, 0, 16, 6), (0, 16), (0, 7)), front=front_rect((0, 6, 16, 10), (0, 16), (5, 8)),
              side=[DARK, CREAM])
        b.add(box(S, legs, (0, 5), (2, 6)), top=leg_mat, front=front_rect((legs[0], 10, legs[1], 15), legs, (0, 5)), side=leg_mat)
        b.add(box(S, bottle, (8, 14), (0, 2)), top=top_rect((bottle[0], 0, bottle[1], 3), bottle, (0, 2)),
              front=front_rect((bottle[0], 0, bottle[1], 5), bottle, (8, 14)), side=[BLUE_M, DARK, CREAM])
    return b.done()


def g_base():
    """The prize machine's lower body: its front with the "G" panel (drawn rows 0-14) over the full cell."""
    c = Cell(G_BASE)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 16), (6, 15)), top=[PINK, RED], front=front_rect((1, 0, 15, 15), (1, 15), (0, 16)), side=[RED, DARK, PINK])
    return b.done()


def g_top():
    """Stacked on the body: the machine's upper front with the slot panel (rows 8-15) and its pink top (rows 1-6)."""
    c = Cell(G_TOP)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 8), (6, 15)), top=top_rect((1, 1, 15, 7), (1, 15), (6, 15)), front=front_rect((1, 8, 15, 16), (1, 15), (0, 8)),
          side=[RED, PINK, DARK])
    return b.done()


def crown():
    """The plant's leafy crown, stacked on its urn."""
    c = Cell(CROWN)
    b = Build(c)
    b.add(F.ellipsoid(S, (8, 7.5, 8), (7, 7.5, 6.5)), top=top_rect((0, 0, 16, 9), (1, 15), (1.5, 14.5)),
          front=front_rect((0, 0, 16, 16), (1, 15), (0, 15)), side=[GREEN, GREEN_L, GREEN_D])
    return b.done()


def urn():
    """The gold urn, a full cell tall: its drawn front with the dark decoration."""
    c = Cell(URN)
    b = Build(c)
    b.add(F.cylinder(S, (8, 8), 7.2, (0, 16)), top=[GOLD, GOLD_D], front=front_rect((0, 0, 16, 16), (0.8, 15.2), (0, 16)),
          side=[GOLD_L, GOLD, GOLD_D])
    return b.done()


def trash_can():
    """Vermilion Gym's trash can: a grey can, its round lid drawn on top and raised a voxel."""
    c = Cell(TRASH)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 11), (1, 15)), top=TOPDRAW, front=[GREY_D, DARK, GREY_S], side=[GREY_D, DARK, GREY_S])
    b.add(F.cylinder(S, (8, 7.5), 5.6, (11, 12)), top=TOPDRAW, front=[DARK, GREY_D], side=[DARK, GREY_D])
    return b.done()


def main():
    out = Pass("game-corner")
    for key in TABLE:
        out.cell(key, *cafe_table(key), label="cafe table", family="gc-cafe",
                 reason="One cell of the pink octagonal table on its red rug (thin rim, legs near the ends"
                        + (", the gold centrepiece" if key in CENTRE else "") + "); the octagon cut in the cell's own frame "
                        "(Celadon's 2x3 and Olivine's 2x2 tables share the bottom row).")
    out.entity("crystal_gc_cafe_table", [[k for k in list(TABLE)[0:2]], list(TABLE)[2:4], list(TABLE)[4:6]], "Celadon cafe table")
    out.entity("crystal_gc_olivine_table", [list(TABLE)[6:8], list(TABLE)[4:6]], "Olivine cafe table")
    out.cell(CHAIR, *chair(), label="grey square chair", family="gc-cafe",
             reason="A square chair five voxels high, its drawn seat and front (96 placements, walkable).")
    out.cell(SLOT_PINK, *slot_machine(SLOT_PINK), label="slot machine (pink), column end", family="gc-slots",
             reason="The column's last machine: one block like Tier A's counters (the rest of the column), its drawn top.")
    out.cell(SLOT_YELLOW, *slot_machine(SLOT_YELLOW), label="slot machine (yellow), column end", family="gc-slots",
             reason="As the pink one.")
    out.cell(END_RED, *end_cabinet(END_RED, True), label="slot column end cabinet (red)", family="gc-slots",
             reason="One block, its drawn top and the front with the coin slots.")
    out.cell(END_ORANGE, *end_cabinet(END_ORANGE, False), label="slot column end cabinet (orange)", family="gc-slots",
             reason="As the red one.")
    out.entity("crystal_gc_slot_end", [[SLOT_PINK, SLOT_YELLOW], [END_RED, END_ORANGE]], "Slot machine column end")
    for key in (PRIZE_L, PRIZE_R):
        out.cell(key, *full_machine(key), label="prize exchange machine (wall row)", family="gc-prizes",
                 reason="The machine fills its wall-row cell: a full block, its drawn front (the cell below is walkable floor).")
    out.entity("crystal_gc_prize_machines", [[PRIZE_L, PRIZE_R]], "Prize exchange machines")
    for key in COUNTER_END:
        out.cell(key, *counter_end(key), label="prize counter's north end under the wall", family="gc-prizes",
                 reason="The wall closes the north half; the counter's north end (one block, its drawn top) in front of it.")
    out.cell(PHONE, *phone_base(), label="telephone on its stand", family="gc-prizes",
             reason="The dark stand and the blue telephone's body on it.")
    out.cell(PHONE_TOP, *phone_top(), label="telephone handset (stacked)", family="gc-prizes",
             reason="Stacked on the stand: the phone's upper part with the handset.")
    out.entity("crystal_gc_phone", [[PHONE_TOP], [PHONE]], "Game Corner telephone (stack)", kind="stack (model as one)")
    for key in (BT_WALL_L, BT_WALL_R, BT_L, BT_R):
        out.cell(key, *blue_table(key), label="blue table" + (" under the wall" if key in (BT_WALL_L, BT_WALL_R) else ""),
                 family="gc-prizes",
                 reason="The blue table drawn across the wall row and the row below: " + (
                     "the wall closes the north half, the table's back half in front of it." if key in (BT_WALL_L, BT_WALL_R)
                     else "its front half with the drawn edge and legs.") + " A bottle stands across the seam.")
    out.entity("crystal_gc_blue_table", [[None, BT_WALL_L, BT_WALL_R, None], [CHAIR, BT_L, BT_R, CHAIR]],
               "Goldenrod blue table with chairs")
    out.cell(G_BASE, *g_base(), label="G prize machine body", family="gc-prizes",
             reason="The prize machine's body with the drawn G panel, a full cell tall.")
    out.cell(G_TOP, *g_top(), label="G prize machine top (stacked)", family="gc-prizes",
             reason="Stacked on the body: the upper front with the slot panel and the pink top.")
    out.entity("crystal_gc_g_machine", [[G_TOP], [G_BASE]], "G prize machine (stack)", kind="stack (model as one)")
    out.cell(URN, *urn(), label="gold urn", family="gc-plants", reason="The gold urn a full cell tall, its drawn decoration.")
    out.cell(CROWN, *crown(), label="leafy crown (stacked)", family="gc-plants", reason="Stacked on the urn: the leafy crown.")
    out.entity("crystal_gc_plant", [[CROWN], [URN]], "Game Corner plant in its gold urn (stack)", kind="stack (model as one)")
    out.cell(TRASH, *trash_can(), label="trash can", family="gc-gym",
             reason="Vermilion Gym's grey trash can, its round lid drawn on top and raised a voxel.")
    out.cell(PEDESTAL, *I.statue_pedestal(PEDESTAL, stone=(GREY, GREY_D, DARK)), label="gym statue pedestal (Vermilion)",
             family="gc-gym", reason="The gym statue's pedestal, the shared builder (the figure stacks on it: labs module).")
    out.entity("crystal_gc_gym_statue", [[FIGURE], [PEDESTAL]], "Vermilion Gym statue (stack)", kind="stack (model as one)")
    for key in COUNTERS:
        out.keep(key, label="counter (never drawn)", family="gc-retained",
                 reason="A counter cell: Crystal never draws fitted counters (Tier A's plain counter), so its entry is kept.")
    for key in WALKS:
        out.keep(key, label="walkable (never drawn)", family="gc-retained",
                 reason="A walkable cell under a prize machine with no walk or seat model: never drawn, its entry is kept.")
    out.write(families=[FAMILY])


if __name__ == "__main__":
    main()
