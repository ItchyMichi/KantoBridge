"""Crystal checkpoint 4: TILESET_MANSION_1 (Celadon Mansion 1F-3F and its roof, the Mobile Battle and Trade Rooms).

Source reading (study/groups/TILESET_MANSION_1, the census: every entry is drawn somewhere; the maps):

- Celadon Mansion 1F: display cabinets along the back (stacked tops over their bases in every placement; the
  middle one holds a golden ornament behind its glass), a potted bush, and a 2x2 table whose solid front row
  carries a drawer apron. 2F (Game Freak's office): a computer desk, a drawing desk, a chair seen from behind
  (seat), the same cabinets and bush, and a bed whose head stacks on its foot: a false vertical stack (as Red's),
  amended to lie flat. A blue nameplate on the corridor wall.
- The roof: the lattice railing along its edges, the stairwell down (a warp cell: kept, its WARP_MODELS.md model) and its grey
  parapet, and the little hut whose front wall is blue siding. The hut's roof is drawn with the house tables'
  own tiles (shared keys, already the table system in house.py): on the roof it reads as a low flat top behind
  the hut's front wall. The table-centre key used only there continues it. Recorded as a shared-key compromise.
- The Mobile rooms' terminal (4x3 cells): grey side towers running its full depth, a screen hood at the back, a
  counter carrying the Poke Ball and arrow panels, a front pillar; the wall emblems; the lattice partitions.
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
GREENS = [(123, 165, 8), (74, 107, 0), (148, 197, 74)]
MACHINE = [(165, 156, 156), (206, 197, 189), GREY, DARK]  # the terminal's own warm greys

CAB_TOP, CAB = K("480-480-1403-1404"), K("1407-1408-1411-1412")
ORN_TOP, ORN = K("1403-1404-1405-1406"), K("1409-1410-1411-1412")
BUSH = K("1416-1417-1418-1419")
TABLE = {K("1403-659-662-663"): ("nw", "nw", None, "table north-west corner (also the roof hut's roof)"),
         K("659-1404-663-665"): ("ne", "ne", None, "table north-east corner (also the roof hut's roof)"),
         K("662-663-1413-1414"): ("w", "sw", (9, (8, 15)), "table south-west with its drawer apron"),
         K("663-665-1414-1415"): ("e", "se", (9, (8, 15)), "table south-east with its drawer apron"),
         K("663-663-663-663"): ("", "", None, "roof hut roof centre (table tile)")}
DESK_PC, DESK_DRAW, CHAIR = K("1420-1421-1424-1425"), K("1422-1423-1426-1427"), K("1428-1429-1432-1433")
BED_HEAD, BED_FOOT = K("1434-1435-135-136"), K("135-136-1436-1437")
PLAQUE = K("480-1390-1391-1392")
RAIL = {K("1447-1448-1449-1450"): "west end", K("1448-1448-1450-1450"): "middle", K("1448-1478-1450-1479"): "east end"}
STAIRS, PARAPET = K("0-1466-1469-1470"), K("1459-1460-1461-100")
HUT_WALL = (K("1475-1384-1475-1384"), K("1384-1476-1384-1476"))
EMBLEM = K("1502-1503-1504-1505")
TOWER = {K("1506-1507-1508-1509"): "terminal west tower (back)", K("1506-1524-1525-1526"): "terminal west tower (middle)",
         K("1527-1528-1529-1530"): "terminal west tower (front)", K("1518-1506-1519-1508"): "terminal east tower (back)",
         K("1524-1506-1526-1525"): "terminal east tower (middle)", K("1528-1527-1530-1529"): "terminal east tower (front)"}
HOOD = {K("1512-1513-1514-1515"): "terminal screen hood", K("1513-1512-1516-1517"): "terminal arrow display (trade)",
        K("1513-1512-1522-1523"): "terminal display (battle)"}
COUNTER = {K("1531-1532-1535-1535"): "terminal keyboard counter", K("1533-1534-1536-1537"): "terminal Poke Ball panel (trade)",
           K("1541-1542-1543-1544"): "terminal panel (battle)"}
PILLAR = {K("1539-1525-1540-1529"): "terminal front pillar (trade)", K("1508-1525-1545-1529"): "terminal front pillar (battle)"}
OWNED = [CAB_TOP, CAB, ORN_TOP, ORN, BUSH, *TABLE, DESK_PC, DESK_DRAW, CHAIR, BED_HEAD, BED_FOOT, PLAQUE, *RAIL, STAIRS,
         PARAPET, *HUT_WALL, EMBLEM, *TOWER, *HOOD, *COUNTER, *PILLAR]


def bush():
    """A leafy bush (rows 0-7) in a woven pot (rows 8-15), one cell."""
    c = Cell(BUSH)
    b = Build(c)
    b.add(F.cylinder(S, (8, 9), 5.2, (0, 7)), top=[WOOD_D, WOOD], front=front_rect((2, 8, 14, 16), (2.8, 13.2), (0, 7)), side=[WOOD_D, WOOD])
    leaves = (F.ellipsoid(S, (8, 10.5, 8.5), (6.4, 5, 5.6)) | F.ellipsoid(S, (5, 9, 9), (3, 3.5, 3)) | F.ellipsoid(S, (11, 9.5, 8), (3, 3.5, 3)))
    leaves &= np.indices(S)[0] >= 6
    b.add(leaves, top=top_rect((1, 0, 15, 8), (1.6, 14.4), (2.9, 14.1)), front=front_rect((1, 0, 15, 8), (1.6, 14.4), (6, 16)), side=GREENS)
    return b.done()


def desk(key, kind):
    """2F desks: a wooden desk; the computer's screen frame (rows 2-8) stands on the first, a slanted drawing board
    (the drawn sheet, rows 1-13) on the second."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 8), (2, 15)), top=[WOOD, WOOD_L], front=[WOOD_D, WOOD], side=[WOOD_D, DARK])
    if kind == "pc":
        b.add(box(S, (3, 15), (8, 16), (3, 8)), top=[WOOD_L], front=front_rect((2, 2, 16, 9), (3, 15), (8, 16)), side=[WOOD_D, DARK])
        b.add(box(S, (5, 15), (8, 9), (9, 13)), top=top_rect((4, 9, 16, 13), (5, 15), (9, 13)), front=[DARK], side=[DARK])
    else:
        yy, zz, xx = np.indices(S)
        board = (xx >= 1) & (xx < 14) & (zz >= 3) & (zz < 13) & (yy >= 8) & (yy < 8 + (13 - zz) * 0.7 + 1)
        b.add(board, top=top_rect((0, 1, 14, 14), (1, 14), (3, 13)), front=[WOOD_D, DARK], side=[WOOD_D, DARK])
    return b.done()


def chair():
    """The office chair seen from behind (it faces the desk to the north): the seat at the existing seat height
    (y 4) and its backrest standing on the south edge (drawn rows 4-9)."""
    c = Cell(CHAIR)
    b = Build(c)
    b.add(box(S, (5, 15), (2, 4), (3, 14)), top=[WOOD, WOOD_L], front=[WOOD_D, DARK], side=[WOOD_D, DARK])
    b.add(box(S, (5, 15), (4, 12), (12, 14)), top=[WOOD_L], front=front_rect((4, 4, 16, 10), (5, 15), (4, 12)), side=[WOOD_D, DARK])
    for x, z in ((6, 4), (13, 4), (6, 12), (13, 12)):
        b.add(box(S, (x, x + 1), (0, 2), (z, z + 1)), top=[DARK], front=[DARK], side=[DARK])
    return b.done()


def bed():
    """The 2F bed lying north-south across head (north) and foot: headboard, mattress with the drawn sheets,
    footboard, grey frame; as Red's bed."""
    occ = np.zeros((16, 32, 16), bool)
    mat = np.zeros(occ.shape, np.uint8)
    sh = occ.shape

    def put(m, v):
        occ[m] = True
        mat[m] = v
    put(box(sh, (1, 15), (0, 12), (1, 3)), 1)
    put(box(sh, (1, 15), (0, 8), (29, 31)), 1)
    put(box(sh, (1, 15), (2, 4), (3, 29)), 1)
    put(box(sh, (2, 14), (4, 7), (3, 29)), 2)
    put(box(sh, (3, 13), (7, 9), (4, 8)), 2)
    for x in (1, 13):
        for z in (3, 27):
            put(box(sh, (x, x + 2), (0, 2), (z, z + 2)), 1)
    up = F.exposed(occ)["up"]
    out = {}
    for key, z0 in ((BED_HEAD, 0), (BED_FOOT, 16)):
        c = Cell(key)
        part, m, u = occ[:, z0:z0 + 16], mat[:, z0:z0 + 16], up[:, z0:z0 + 16]
        p = np.zeros(part.shape, np.uint8)
        for y, z, x in np.argwhere(part):
            p[y, z, x] = z * 16 + x if (u[y, z, x] and m[y, z, x] == 2) else c.pick([GREY_D, GREY, DARK] if m[y, z, x] == 1 else [CREAM], x + .5, z + .5)
        out[key] = (part.copy(), p)
    return out


def plaque():
    """A corridor wall cell: cream above, the blue band below, the nameplate (rows 8-14) a voxel proud."""
    c = Cell(PLAQUE)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 16), (0, 15)), top=[CREAM], front=front_rect((0, 0, 16, 16), (0, 16), (0, 16)), side=[CREAM, BLUE])
    b.add(box(S, (1, 15), (2, 8), (15, 16)), top=[DARK], front=front_rect((1, 8, 15, 15), (1, 15), (2, 8)), side=[DARK])
    return b.done()


def lattice(key, where):
    """The lattice railing: a panel across the cell with its drawn dark ground open (see-through), a top rail,
    and a post at a drawn end."""
    c = Cell(key)
    b = Build(c)
    proj = front_rect((0, 0, 16, 16), (0, 16), (0, 13))
    panel = box(S, (0, 16), (1, 11), (7, 9))
    yy, zz, xx = np.indices(S)
    open_ = np.zeros(S, bool)
    for y in range(1, 11):
        for x in range(16):
            u, v = proj(y, 8, x)
            open_[y, :, x] = tuple(int(a) for a in c.pixels[int(v), int(u)]) == DARK
    b.add(panel & ~open_, top=[GREY, CREAM], front=proj, side=[GREY, CREAM], back=lambda y, z, x: proj(y, z, 15 - x))
    b.add(box(S, (0, 16), (11, 13), (6, 10)), top=[CREAM, GREY], front=[GREY, CREAM], side=[GREY, DARK])
    b.add(box(S, (0, 16), (0, 1), (6, 10)), top=[GREY], front=[DARK, GREY], side=[DARK])
    if where != "middle":
        px = (1, 3) if where == "west end" else (13, 15)
        b.add(box(S, px, (0, 14), (6, 10)), top=[CREAM, GREY], front=[GREY, DARK], side=[GREY, DARK])
    return b.done()


def stairs():
    c = Cell(STAIRS)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 1), (0, 16)), top=top_rect((0, 0, 16, 16), (0, 16), (0, 16)), front=[GREY_D, DARK], side=[GREY_D, DARK])
    return b.done()


def parapet():
    """The grey parapet beside the roof stairwell: its edge trim (drawn columns 0-2) and the stair's drawn stringer on
    its face."""
    c = Cell(PARAPET)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 10), (0, 16)), top=[GREY, GREY_D], front=front_rect((0, 2, 16, 16), (0, 16), (0, 10)), side=[GREY, GREY_D])
    return b.done()


def hut_wall(key):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 16), (2, 16)), top=[BLUE, BLUE_L], front=lambda y, z, x: (x + .5, (15 - y) % 16 + .5), side=[BLUE, BLUE_L])
    return b.done()


def machine(key, part):
    """The Mobile terminal's cells as solid machine blocks: each shows its drawing on its open face, grey elsewhere."""
    c = Cell(key)
    b = Build(c)
    full = front_rect((0, 0, 16, 16), (0, 16), (0, 16))
    if part == "tower":
        b.add(box(S, (1, 15), (0, 16), (0, 16)), top=MACHINE, front=full, side=full)
    elif part == "emblem":
        b.add(box(S, (0, 16), (0, 16), (0, 14)), top=MACHINE, front=full, side=MACHINE)
        b.add(F.cylinder(S, (8, 8), 5.5, (0, 16)) & box(S, (0, 16), (3, 14), (14, 15)), top=MACHINE,
              front=front_rect((2, 2, 14, 14), (2.5, 13.5), (2.5, 13.5)), side=MACHINE)
    elif part == "hood":
        b.add(box(S, (0, 16), (0, 16), (0, 9)), top=top_rect((0, 0, 16, 4), (0, 16), (0, 9)),
              front=front_rect((0, 4, 16, 16), (0, 16), (0, 12)), side=MACHINE)
        b.add(box(S, (0, 16), (12, 16), (9, 13)), top=top_rect((0, 0, 16, 4), (0, 16), (9, 13)),
              front=front_rect((0, 0, 16, 4), (0, 16), (12, 16)), side=MACHINE)
    elif part == "counter":
        b.add(box(S, (0, 16), (0, 10), (0, 16)), top=top_rect((0, 0, 16, 10), (0, 16), (0, 16)),
              front=front_rect((0, 9, 16, 16), (0, 16), (0, 10)), side=MACHINE)
    else:
        b.add(box(S, (3, 13), (0, 12), (0, 12)), top=MACHINE, front=front_rect((3, 0, 13, 16), (3, 13), (0, 12)), side=MACHINE)
    return b.done()


def main():
    out = Pass("mansion")
    out.cell(CAB, *I.case_base(CAB, rows=(0, 13), shelf=((3, 13), (9, 15), (1, 6))), label="display cabinet lower half",
             family="mansion-cabinet", reason="A cabinet on the floor: a recessed shelf of crockery over its doors.")
    out.cell(CAB_TOP, *I.case_top(CAB_TOP, front_rows=(8, 16), top_rows=(9, 16), height=4), label="display cabinet top (stacked)",
             family="mansion-cabinet", reason="Stacked in all 4 placements: only the cabinet's top (the cream wall rows above it are "
                                              "not drawn on the cabinet).")
    out.entity("crystal_mansion_cabinet", [[CAB_TOP], [CAB]], "Celadon Mansion display cabinet (stack)", kind="stack (model as one)")
    out.cell(ORN, *I.case_base(ORN, rows=(0, 13), shelf=((3, 13), (9, 14), (3, 7))), label="ornament cabinet lower half",
             family="mansion-cabinet", reason="The middle cabinet's base: a small shelf over its doors.")
    out.cell(ORN_TOP, *I.case_top(ORN_TOP, front_rows=(8, 16), top_rows=(1, 8), height=10, shelf=((3, 13), (1, 8), (9, 16))),
             label="ornament cabinet upper half (stacked)", family="mansion-cabinet",
             reason="Stacked: the glass-fronted upper case with the golden ornament inside a real recess, and its top.")
    out.entity("crystal_mansion_ornament_cabinet", [[ORN_TOP], [ORN]], "Celadon Mansion ornament cabinet (stack)", kind="stack (model as one)")
    out.cell(BUSH, *bush(), label="potted bush", family="mansion-plant",
             reason="A leafy bush of overlapping lobes in a woven round pot, from the drawn greens and basketwork.")
    for key, (edges, legs, apron, label) in TABLE.items():
        out.cell(key, *I.table_piece(key, edges=edges, legs=legs, apron=apron), label=label, family="table",
                 reason="The shared table system (the 1F table; on the roof the same tiles draw the hut's roof, where they "
                        "read as a low flat top behind the hut's front wall: a shared-key compromise).")
    out.entity("crystal_mansion_table", [[K("1403-659-662-663"), K("659-1404-663-665")], [K("662-663-1413-1414"), K("663-665-1414-1415")]],
               "Celadon Mansion 1F table (2x2)")
    out.cell(DESK_PC, *desk(DESK_PC, "pc"), label="office desk with computer", family="mansion-office",
             reason="A wooden desk with the computer's screen frame standing on it and its keyboard.")
    out.cell(DESK_DRAW, *desk(DESK_DRAW, "draw"), label="office drawing desk", family="mansion-office",
             reason="A wooden desk with a slanted drawing board carrying the drawn sheet.")
    out.cell(CHAIR, *chair(), label="office chair (seat)", family="mansion-office",
             reason="The chair seen from behind: seat at the existing seat height, backrest on its south edge, four legs.")
    out.entity("crystal_mansion_office_desks", [[DESK_PC, DESK_DRAW], [CHAIR, None]], "Celadon Mansion 2F desks and chair")
    parts = bed()
    out.cell(BED_HEAD, *parts[BED_HEAD], label="bed head", family="mansion-bed",
             reason="Corrected false stack (amendment): the pillow end lies north of the foot. Headboard, mattress, pillow.")
    out.drop_stack(BED_HEAD, "CELADON_MANSION_2F (0,6) head over (0,7) foot, both collision 7: one bed drawn top-down across "
                             "two rows (as Red's bed), not a pillow end standing on the foot.")
    out.cell(BED_FOOT, *parts[BED_FOOT], label="bed foot", family="mansion-bed", reason="Foot of the same bed: sheets and footboard.")
    out.entity("crystal_mansion_bed", [[BED_HEAD], [BED_FOOT]], "Celadon Mansion 2F bed (1x2)")
    out.cell(PLAQUE, *plaque(), label="corridor wall with nameplate", family="mansion-wall",
             reason="The corridor wall cell as wall (cream over the blue band) with the nameplate standing a voxel proud.")
    for key, where in RAIL.items():
        out.cell(key, *lattice(key, where), label=f"lattice railing ({where})", family="lattice-railing",
                 reason="A see-through lattice panel (the drawn dark ground open) under a top rail, with a post at a drawn "
                        "end: the roof's edge railing and the Mobile rooms' partitions.")
    out.entity("crystal_lattice_railing_row", [list(RAIL)], "Lattice railing (west end, middle, east end)")
    out.keep_warp(STAIRS, label="roof stairwell down (warp)", family="mansion-roof")
    out.cell(PARAPET, *parapet(), label="roof stairwell parapet", family="mansion-roof",
             reason="A grey parapet block beside the stairwell with its edge trim and the drawn stair stringer on its face.")
    out.entity("crystal_roof_stairwell", [[PARAPET, STAIRS]], "Celadon Mansion roof stairwell")
    for key in HUT_WALL:
        out.cell(key, *hut_wall(key), label="roof hut front wall (blue siding)", family="mansion-roof",
                 reason="The hut's front wall beside its door: a wall block with the blue siding at its native scale.")
    out.cell(EMBLEM, *machine(EMBLEM, "emblem"), label="Mobile room wall emblem", family="mobile-terminal",
             reason="A wall block with the round emblem standing a voxel proud.")
    for key, label in TOWER.items():
        out.cell(key, *machine(key, "tower"), label=label, family="mobile-terminal",
                 reason="One of the terminal's two grey side towers, running its full depth: drawn panels on its faces.")
    for key, label in HOOD.items():
        out.cell(key, *machine(key, "hood"), label=label, family="mobile-terminal",
                 reason="The terminal's screen hood at the back: the display facing south under an overhanging top.")
    for key, label in COUNTER.items():
        out.cell(key, *machine(key, "counter"), label=label, family="mobile-terminal",
                 reason="The terminal's counter: its drawn keys and panels laid on top, its front below.")
    for key, label in PILLAR.items():
        out.cell(key, *machine(key, "pillar"), label=label, family="mobile-terminal",
                 reason="A narrower front pillar beside the walkable spot where the player stands.")
    out.entity("crystal_mobile_trade_terminal", [[EMBLEM, None, None, EMBLEM],
                                                 [K("1506-1507-1508-1509"), K("1512-1513-1514-1515"), K("1513-1512-1516-1517"), K("1518-1506-1519-1508")],
                                                 [K("1506-1524-1525-1526"), K("1531-1532-1535-1535"), K("1533-1534-1536-1537"), K("1524-1506-1526-1525")],
                                                 [K("1527-1528-1529-1530"), None, K("1539-1525-1540-1529"), K("1528-1527-1530-1529")]],
               "Mobile Trade Room terminal")
    out.write(owned=OWNED)


if __name__ == "__main__":
    main()
