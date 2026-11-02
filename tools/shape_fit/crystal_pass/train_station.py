"""Crystal checkpoint 11: TILESET_TRAIN_STATION_1 (the Goldenrod and Saffron Magnet Train stations, the Celadon,
Viridian, Cerulean, Pewter, Cianwood and Ecruteak gyms' shared pieces, the Fighting Dojo, the Tin Tower entrance).

Source reading (study/groups/TILESET_TRAIN_STATION_1; composites _ts_*):

- The maglev train (10x2 cells, both stations): one designed body painted from its composite drawing: a cream roof,
  the drawn side with its windows, panels and skirt on the south face, both noses tapered; two blocks tall, straddling
  the roof row and the side row (the track row). Its two door cells are walkable warps (never drawn), so the body is
  open there. The track continues beyond the train: a low bed with two rails.
- The station hall: the platform railing (12 voxels, posts and rails), the ticket gates (two-cell booths: stacks), the
  yellow seats, the blue info desk at counter height, flower planters.
- Flower planters: a single-cell planter (box and flowers), and a tall one whose flower crown stacks on the planter; the
  crown's link names the station's walkable variant, so `alsoOn` adds the planter base where it stood in place (16).
- Celadon Gym's hedges and Viridian Gym's maze of boxed hedges, one uniform height (bush tops drawn on top, the boxes'
  outlined faces on the south faces). Viridian's links stacked 12 of the maze's segments two cells tall (the maze draws
  walls several cells deep: a top over a face, a top over a top), so those links are removed (amendments).
- The gym statue figure (24) stands on five pedestal variants; its link names one, so `alsoOn` adds the other four
  (each a pedestal: Fighting Dojo, Tin Tower entrance / Cianwood / Ecruteak, Cerulean, Pewter). This family's pedestals
  use the shared `interiors.statue_pedestal`.
- The Fighting Dojo's wall scroll (a wall relief).
"""
from __future__ import annotations

import numpy as np

from tools.shape_fit.crystal_pass import furniture as F
from tools.shape_fit.crystal_pass import interiors as I
from tools.shape_fit.crystal_pass.common import K, Pass
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, front_rect, top_rect

S = (16, 16, 16)
DARK, CREAM, GREY, GREY_D = I.DARK, I.CREAM, I.GREY, I.GREY_D
YELLOW, ORANGE = (255, 255, 58), (255, 132, 8)
GOLD_L, GOLD, GOLD_D = (214, 197, 140), (173, 140, 58), (132, 107, 25)
BLUE, BLUE_L = (74, 74, 255), (123, 132, 255)
G_D, G, G_L = (74, 107, 0), (123, 165, 8), (148, 197, 74)
TOPDRAW = top_rect((0, 0, 16, 16), (0, 16), (0, 16))
FULL = front_rect((0, 0, 16, 16), (0, 16), (0, 16))
FAMILY = "TILESET_TRAIN_STATION_1"

TRAIN_TOP = ["1320-1321-1324-1325", "1322-1323-1326-1327", "1323-1323-1327-1327", "1334-1323-1335-1327", "1323-1323-1327-1327",
             "1323-1323-1327-1327", "1323-1334-1327-1335", "1323-1323-1327-1327", "1323-1342-1327-1344", "1343-1320-1345-1346"]
TRAIN_LOW = ["1328-1329-1332-1320", "1330-1331-1333-1320", "1336-1337-1339-1340", "1338-1331-1333-1320", "1352-1352-1320-1320",
             "1352-1352-1320-1320", "1341-1338-1320-1333", "1336-1337-1339-1340", "1341-1347-1320-1333", "1348-1349-1320-1350"]
DOOR = K("1336-1337-1339-1340")
RAIL = K("1351-1351-1351-1351")
RAILING = K("1297-1297-1298-1298")
GATE_TOP, GATE = K("1299-1300-1301-1302"), K("1303-1304-1305-1306")
SEAT = K("1290-1291-1292-1293")
DESK, DESK_END = K("1371-1372-1373-61"), K("1374-1375-1376-1377")
CROWN, PLANTER, PLANTER_ONE, PLANTER_WALK = (K("1310-1311-1312-1313"), K("1312-1313-1314-1315"), K("1310-1311-1314-1315"),
                                            K("1314-1315-1294-1294"))
CELADON_HEDGE = K("1354-1355-1356-1357")
V_TOPS = K("1316-1317-1316-1317")            # bush tops over bush tops
V_TOP_FACE = K("1316-1317-1318-1319")        # bush tops over the box face
V_FACE = K("1318-1319-1318-1319")            # two box faces
V_CORNERS = [K("1316-1317-1316-1378"), K("1316-1317-1378-1317")]
V_HALF = K("1294-1294-1316-1317")            # floor over bush tops
FIGURE = K("1359-1360-1361-1362")
PEDESTALS = [K("1162-1163-1363-1364"), K("1369-1370-1363-1364")]
OTHER_PEDESTALS = [K("1369-1370-1363-1364"), K("1369-1370-1758-1759"), K("1646-1647-1648-1649"), K("1162-1163-1758-1759")]
SCROLL = K("1365-1366-1367-1368")
HEDGE_H = 14


def train():
    """The maglev body over the 10x2 layout (global y, z, x: z 0-31 over the roof and side rows, x 0-159), cut per cell,
    painted from the composite drawing: tops from the roof band (drawn rows 1-6), the south side from the side rows
    (roof row 7-15 and side row 0-12 over the body's height)."""
    layout = [[K(k) for k in TRAIN_TOP], [K(k) for k in TRAIN_LOW]]
    yy, zz, xx = np.indices((16, 32, 160))
    zc, xc = zz + .5, xx + .5
    d = np.minimum(xc, 160 - xc)
    L = 22.0
    t = np.clip(d / L, 0, 1)
    h = np.where(d < L, 5 + 11 * np.sqrt(t), 16)
    z0, z1 = 8 + 3 * (1 - t), 28 - 3 * (1 - t)
    occ = (yy < h) & (zc >= z0) & (zc < z1)
    occ &= ~((yy >= h - 1) & ((zc < z0 + 2) | (zc >= z1 - 2)))      # the roof's rounded edges
    occ &= ~((yy < 2) & ((zc < z0 + 1) | (zc >= z1 - 1)))           # the skirt set in
    for col, key in enumerate(layout[1]):
        if key == DOOR:
            occ[:, 16:, col * 16:(col + 1) * 16] = False              # the walkable doors stay open

    def colour_at(y, gz, gx, face):
        side_v = 7 + (16 - y - .5) * 21 / 16
        if face == "up":
            return gx + .5, 1 + (gz - 8) / 20 * 6
        if face == "down":
            return gx + .5, 26
        return gx + .5, side_v
    parts = I.paint_global(occ, layout, colour_at, [GREY, GREY_D, DARK, CREAM])
    parts.pop(DOOR, None)
    return parts, layout


def rail():
    """The track beyond the train: a low grey bed with two rails (cream tops, drawn rows 4-5 and 12-13)."""
    c = Cell(RAIL)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 1), (2, 15)), top=[GREY_D, GREY], front=[DARK], side=[DARK])
    for z in ((4, 6), (12, 14)):
        b.add(box(S, (0, 16), (1, 3), z), top=[CREAM], front=[DARK], side=[DARK])
    return b.done()


def railing():
    """The platform railing: two posts with cream caps (drawn columns 3-6 and 11-14), the rail between them (rows 5-7)
    and the foot rail (rows 12-13), twelve voxels high."""
    c = Cell(RAILING)
    b = Build(c)
    for x in ((3, 7), (11, 15)):
        b.add(box(S, x, (0, 12), (6, 10)), top=[CREAM], front=front_rect((x[0], 3, x[1], 14), x, (0, 12)), side=[GREY_D, DARK])
    b.add(box(S, (0, 16), (8, 11), (7, 9)), top=[CREAM, GREY], front=front_rect((0, 5, 16, 8), (0, 16), (8, 11)), side=[DARK])
    b.add(box(S, (0, 16), (0, 2), (7, 9)), top=[GREY_D], front=front_rect((0, 12, 16, 14), (0, 16), (0, 2)), side=[DARK])
    return b.done()


def gate():
    """A ticket gate booth's body: its drawn front (posts and panel, rows 0-14) over the full cell."""
    c = Cell(GATE)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 16), (4, 13)), top=[GREY, CREAM], front=front_rect((1, 0, 15, 15), (1, 15), (0, 16)), side=[GREY_D, DARK, GREY])
    return b.done()


def gate_top():
    """Stacked on the body: the booth's upper part with its window panel (the whole drawing on its face)."""
    c = Cell(GATE_TOP)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 10), (4, 13)), top=[GREY, CREAM], front=FULL, side=[GREY_D, DARK, GREY])
    return b.done()


def seat():
    """A yellow cushioned seat: its seat (drawn rows 1-9) and front (rows 9-14), five voxels high."""
    c = Cell(SEAT)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 5), (2, 14)), top=top_rect((1, 1, 15, 10), (1, 15), (2, 14)), front=front_rect((1, 9, 15, 15), (1, 15), (0, 5)),
          side=[ORANGE, YELLOW, DARK])
    return b.done()


def desk(key, end):
    """The blue info desk at counter height over the cell's east part (its west edge drawn in column 2)."""
    c = Cell(key)
    b = Build(c)
    if end:
        b.add(box(S, (2, 16), (0, 8), (0, 15)), top=top_rect((2, 0, 16, 10), (2, 16), (0, 15)), front=front_rect((2, 10, 16, 16), (2, 16), (0, 8)),
              side=[BLUE, DARK, BLUE_L])
    else:
        b.add(box(S, (2, 16), (0, 8), (0, 16)), top=TOPDRAW, front=[BLUE, DARK], side=[BLUE, DARK, BLUE_L])
    return b.done()


def flowers(b, yc, rows, ry=3.5):
    r0, r1 = rows
    occ = F.ellipsoid(S, (8, yc, 8), (6.8, ry, 6))
    b.add(occ, top=top_rect((1, r0, 15, r1), (1.2, 14.8), (2, 14)), front=front_rect((1, r0, 15, r1), (1.2, 14.8), (yc - ry, yc + ry)),
          side=[G, G_D, G_L])


def planter(key, crown_rows=(1, 8)):
    """A wooden planter box (its front drawn rows 8-14) with flowers over it (rows 1-7)."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 7), (2, 14)), top=[GOLD_L, GOLD], front=front_rect((1, 8, 15, 15), (1, 15), (0, 7)), side=[GOLD, GOLD_D, DARK])
    flowers(b, 10, crown_rows)
    return b.done()


def crown():
    """The tall planter's flowering crown (stacked on the planter): two layers of flowers (drawn rows 0-7 and 8-15)."""
    c = Cell(CROWN)
    b = Build(c)
    flowers(b, 3.5, (8, 16))
    flowers(b, 9.5, (0, 8), ry=3.2)
    return b.done()


def hedge(key, *, top=TOPDRAW, front=None, z=(0, 16)):
    """A hedge segment of uniform height (bush tops drawn on top, the box's outlined face on the south face)."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, HEDGE_H), z), top=top, front=front or [G_D, DARK], side=[G_D, G, DARK])
    return b.done()


def scroll():
    c = Cell(SCROLL)
    bg = [tuple(int(a) for a in c.pixels[0, 0])]
    return I.wall_relief(SCROLL, background=bg, depth=1)


def main():
    out = Pass("train-station")
    parts, layout = train()
    for key, (occ, paint) in parts.items():
        top = key in [K(k) for k in TRAIN_TOP]
        out.cell(key, occ, paint, label="maglev train (" + ("roof row" if top else "side row") + ")", family="ts-train",
                 reason="Part of the maglev body designed over the train's 10x2 cells and painted from its composite drawing "
                        "(roof band on top, the drawn side on the south face, tapered noses).")
    out.entity("crystal_ts_train", [[k for k in row] for row in layout], "Magnet Train (the door cells are walkable warps)")
    out.cell(RAIL, *rail(), label="track", family="ts-train", reason="The track beyond the train: low bed, two rails.")
    out.cell(RAILING, *railing(), label="platform railing", family="ts-hall",
             reason="Posts with cream caps, the rail and the foot rail, twelve voxels high (34 placements).")
    out.cell(GATE, *gate(), label="ticket gate booth", family="ts-hall", reason="The booth's body, its drawn front.")
    out.cell(GATE_TOP, *gate_top(), label="ticket gate booth top (stacked)", family="ts-hall",
             reason="Stacked on the body: the booth's upper part with its window panel.")
    out.entity("crystal_ts_gate", [[GATE_TOP], [GATE]], "Ticket gate booth (stack)", kind="stack (model as one)")
    out.cell(SEAT, *seat(), label="yellow seat", family="ts-hall", reason="A yellow cushioned seat, five voxels high (walkable).")
    out.cell(DESK, *desk(DESK, False), label="info desk", family="ts-hall", reason="The blue info desk at counter height.")
    out.cell(DESK_END, *desk(DESK_END, True), label="info desk end", family="ts-hall",
             reason="The desk's south end at counter height, its drawn front.")
    out.cell(PLANTER_ONE, *planter(PLANTER_ONE), label="flower planter", family="ts-planters",
             reason="A wooden planter box with flowers over it.")
    out.cell(PLANTER, *planter(PLANTER, crown_rows=(0, 8)), label="tall flower planter base", family="ts-planters",
             reason="The planter box and the crown's lower flowers.")
    out.cell(CROWN, *crown(), label="tall flower planter crown (stacked)", family="ts-planters",
             reason="Stacked on the planter: two layers of flowers. alsoOn adds the planter base (amendment).")
    out.also_on(CROWN, [PLANTER], "CELADON_GYM (10), SAFFRON (4) and GOLDENROD (2) MAGNET_TRAIN_STATION: the crown over the "
                                  "planter base 1312-1313-1314-1315 stood in place on the floor beside it; its link names "
                                  "the station's walkable variant 1314-1315-1294-1294, which never stacks.")
    out.entity("crystal_ts_planter", [[CROWN], [PLANTER]], "Tall flower planter (alsoOn stack)", kind="stack (model as one)")
    out.cell(CELADON_HEDGE, *hedge(CELADON_HEDGE), label="Celadon Gym hedge", family="ts-hedges",
             reason="A hedge block of the gyms' uniform hedge height, its drawn foliage on top (42 placements).")
    out.cell(V_TOPS, *hedge(V_TOPS), label="Viridian hedge (bush tops)", family="ts-hedges",
             reason="A maze segment: bush tops drawn on top.")
    out.cell(V_TOP_FACE, *hedge(V_TOP_FACE, top=top_rect((0, 0, 16, 8), (0, 16), (0, 16)), front=front_rect((0, 8, 16, 16), (0, 16), (0, HEDGE_H))),
             label="Viridian hedge (bush tops, box face)", family="ts-hedges",
             reason="A maze segment's south end: bush tops on top (drawn rows 0-7), the box's outlined face on the south face.")
    out.cell(V_FACE, *hedge(V_FACE, top=[G_D], front=FULL), label="Viridian hedge (box faces)", family="ts-hedges",
             reason="A maze segment drawn only as the box's faces: plain top, the faces on the south face.")
    for key in V_CORNERS:
        out.cell(key, *hedge(key), label="Viridian hedge (corner)", family="ts-hedges",
                 reason="A maze segment with a plain corner: bush tops drawn on top.")
    out.cell(V_HALF, *hedge(V_HALF, top=top_rect((0, 8, 16, 16), (0, 16), (8, 16)), z=(8, 16)), label="Viridian hedge (north edge)",
             family="ts-hedges", reason="A maze segment's north edge: the hedge over the cell's south half (the floor drawn north).")
    viridian = ("VIRIDIAN_GYM: a maze of boxed hedge walls drawn several cells deep (bush tops over tops, tops over the "
                "boxes' faces); the link stacked these segments two cells tall over 1316-1317-1318-1319 while the rest of "
                "the maze stands one cell, so it is removed (each segment drawn in place).")
    for key in (V_TOPS, *V_CORNERS, V_HALF):
        out.drop_stack(key, viridian)
    out.entity("crystal_ts_viridian_maze", [[V_HALF, V_TOPS], [V_TOPS, V_TOPS], [V_TOP_FACE, V_FACE]], "Viridian Gym hedge segments")
    out.cell(FIGURE, *I.statue_figure(FIGURE, stone=(GOLD, GOLD_D, DARK)), label="gym statue figure (stacked)", family="ts-statues",
             reason="The gym statue figure, the shared builder; it stacks on its pedestal. alsoOn adds the other four pedestals.")
    out.also_on(FIGURE, OTHER_PEDESTALS,
                "The same figure stood in place over four other pedestal variants: FIGHTING_DOJO (8) over 1369-1370-1363-1364; "
                "ECRUTEAK_TIN_TOWER_ENTRANCE (4), CIANWOOD_GYM (2), ECRUTEAK_GYM (2) over 1369-1370-1758-1759; CERULEAN_GYM "
                "(2) over 1646-1647-1648-1649; PEWTER_GYM (2) over 1162-1163-1758-1759 (composite _ts_statues: each a pedestal).")
    for key in PEDESTALS:
        out.cell(key, *I.statue_pedestal(key, stone=(GREY, GREY_D, DARK)), label="gym statue pedestal", family="ts-statues",
                 reason="The statue's pedestal, the shared builder (the figure stacks on it).")
    out.entity("crystal_ts_statue", [[FIGURE], [PEDESTALS[0]]], "Gym statue (stack)", kind="stack (model as one)")
    out.entity("crystal_ts_statue_dojo", [[FIGURE], [PEDESTALS[1]]], "Fighting Dojo statue (alsoOn stack)", kind="stack (model as one)")
    out.cell(SCROLL, *scroll(), label="dojo wall scroll", family="ts-dojo",
             reason="A wall-row cell: the wall closed, the scroll standing one voxel proud.")
    for key in (PLANTER_WALK, DOOR):
        out.keep(key, label="walkable (never drawn)", family="ts-retained",
                 reason="A walkable cell (the station's planter edge, the train's door warp) with no walk or seat model.")
    out.write(families=[FAMILY])


if __name__ == "__main__":
    main()
