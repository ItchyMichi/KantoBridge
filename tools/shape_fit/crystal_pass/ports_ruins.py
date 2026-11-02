"""Crystal checkpoint 20: TILESET_PORT_1 (Cerulean Gym's pool and pedestal), TILESET_PORT_2 (the Olivine and Vermilion
ports), TILESET_RUINS_OF_ALPH_1 and TILESET_AERODACTYL_WORD_ROOM_1 (the Ruins of Alph chambers), and the five unplaced keys.

Source reading (study/groups/TILESET_PORT_*, TILESET_RUINS_OF_ALPH_1; the port and ruins maps):

- The ports' sea rocks lining the piers (268): rounded blue rocks showing their drawing; the docked ship's waterline row
  (the cells the ship's building does not claim): the drawn hull rising from a water plate.
- Cerulean Gym's gold statue pedestal (the gym figure stands on it: checkpoint 11's alsoOn), the shared pedestal.
- The Ruins: the stone statues on their pedestals (stacks; the shared statue builders), the puzzle wall's panels in the
  wall row (full blocks), the item rooms' carved stones.
- Warp cells (the port and ruins stairs, the item and word rooms' exits) are kept (their WARP_MODELS.md models).
- Never drawn (retained): water cells, the cells the ship's building claims, the ruins' walkable floor, and the unplaced
  keys (never placed by the survey, or walkable without a model).
"""
from __future__ import annotations

from collections import Counter

import numpy as np

from tools.shape_fit.crystal_pass import furniture as F
from tools.shape_fit.crystal_pass import interiors as I
from tools.shape_fit.crystal_pass.common import K, Pass
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, front_rect, top_rect

S = (16, 16, 16)
TOPDRAW = top_rect((0, 0, 16, 16), (0, 16), (0, 16))
FULL = front_rect((0, 0, 16, 16), (0, 16), (0, 16))
FAMILIES = ["TILESET_PORT_1", "TILESET_PORT_2", "TILESET_RUINS_OF_ALPH_1", "TILESET_AERODACTYL_WORD_ROOM_1", "unplaced"]

SEA_ROCK = K("4019-4020-4021-4022")
HULL = [K(k) for k in ("4023-4061-4023-4023", "4062-4063-4023-4023", "4063-4070-4023-4023", "4071-4071-4023-4023",
                       "4077-4078-4023-4023", "4078-4078-4023-4023", "4084-4023-4023-4023")]
CERULEAN_PEDESTAL = K("1646-1647-1648-1649")
RUIN_STATUE, RUIN_PEDESTAL = K("4514-4515-4517-4518"), K("4521-4522-4525-4526")
RUIN_WALLS = [K("4516-4516-4519-4520"), K("4516-4516-4529-4530")]
RUIN_STONE = K("4574-4575-4576-4577")
WARPS = {K("4100-4101-4102-4103"): "port stairway (warp)", K("4562-4563-4564-4565"): "inner chamber stairway (warp)",
         K("4578-4579-4580-0"): "item room exit (warp)", K("4581-4582-0-4583"): "item room exit (warp)",
         K("4578-4581-4580-4583"): "word room exit (warp)"}
RETAINED = {K("1582-1582-1580-1580"): "water", K("1288-1288-4023-4023"): "water",
            **{K(k): "building" for k in ("360-4045-4048-4049", "4023-4023-4041-4042", "4023-4023-4043-4044", "4023-4023-4052-4023",
                                          "4025-4026-4027-4028", "4033-4034-4037-4038", "4035-4036-4039-4040", "4046-4047-4050-4051",
                                          "4053-4054-4056-4057", "4055-4023-4058-4023", "4079-4080-4082-4083", "4081-4023-4023-4023",
                                          "629-629-4028-360")},
            **{K(k): "walk" for k in ("4533-4534-4536-4533", "4535-4534-4534-4535", "4535-4538-4538-4537", "4523-4524-4527-4528",
                                      "4524-4531-4528-4532")},
            **{K(k): "no_rule" for k in ("2807-2807-2808-2809", "2807-2807-2810-2811", "2812-0-2814-2815")}}
WHY = {"water": "A water cell: drawn by the water planner, never by its entry; kept.",
       "building": "A cell the ship's building claims: never drawn by its entry; kept.",
       "walk": "A walkable cell with no walk or seat model: never drawn; kept.",
       "no_rule": "Never placed by the survey (no planner rule reaches it); kept."}


def own(key, n=4):
    c = Cell(key)
    return [col for col, _ in Counter(tuple(int(a) for a in px) for px in c.pixels.reshape(-1, 3)).most_common(n)]


def sea_rock():
    """A rounded blue sea rock lining the pier, its drawing on top."""
    c = Cell(SEA_ROCK)
    b = Build(c)
    occ = F.ellipsoid(S, (8, 4, 8), (7.8, 8, 7.8)) & (np.indices(S)[0] < 10)
    b.add(occ, top=TOPDRAW, front=FULL, side=own(SEA_ROCK))
    return b.done()


SEA = set()


def sea_colours():
    if not SEA:
        c = Cell(K("4062-4063-4023-4023"))
        # the water drawn under the hull (rows 8-15 of this cell), sparkles included
        SEA.update(tuple(int(a) for a in c.pixels[r, x]) for r in range(8, 16) for x in range(16))
    return SEA


def hull(key):
    """The docked ship's waterline row: the drawn hull (its non-water pixels, rows as depth) rising six voxels from a
    one-voxel water plate."""
    c = Cell(key)
    px = c.pixels.astype(int)
    sea = sea_colours()
    occ = np.zeros(S, bool)
    occ[0, :, :] = True
    for r in range(16):
        for x in range(16):
            if tuple(px[r, x]) not in sea:
                occ[:6, r, x] = True
    b = Build(c)
    b.add(occ, top=TOPDRAW, front=TOPDRAW, side=own(key))
    return b.done()


def block(key):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 16), (0, 16)), top=own(key), front=FULL, side=own(key))
    return b.done()


def stone():
    c = Cell(RUIN_STONE)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 16), (2, 14)), top=own(RUIN_STONE), front=front_rect((1, 0, 15, 16), (1, 15), (0, 16)), side=own(RUIN_STONE))
    return b.done()


def main():
    out = Pass("ports-ruins")
    out.cell(SEA_ROCK, *sea_rock(), label="sea rock", family="pr-port", reason="A rounded blue rock lining the pier (268 placements).")
    for key in HULL:
        out.cell(key, *hull(key), label="ship's waterline", family="pr-port",
                 reason="The docked ship's waterline row outside its building: the drawn hull over a water plate.")
    out.entity("crystal_pr_hull", [HULL], "Docked ship's waterline row")
    out.cell(CERULEAN_PEDESTAL, *I.statue_pedestal(CERULEAN_PEDESTAL, stone=tuple(own(CERULEAN_PEDESTAL, 3))), label="Cerulean statue pedestal",
             family="pr-statues", reason="The gold pedestal the gym figure stands on (checkpoint 11's alsoOn), the shared builder.")
    out.entity("crystal_pr_cerulean_statue", [[K("1359-1360-1361-1362")], [CERULEAN_PEDESTAL]], "Cerulean Gym statue (alsoOn stack)",
               kind="stack (model as one)")
    out.cell(RUIN_PEDESTAL, *I.statue_pedestal(RUIN_PEDESTAL, stone=tuple(own(RUIN_PEDESTAL, 3))), label="ruins statue pedestal",
             family="pr-ruins", reason="The stone pedestal, the shared builder.")
    out.cell(RUIN_STATUE, *I.statue_figure(RUIN_STATUE, stone=tuple(own(RUIN_STATUE, 3))), label="ruins statue (stacked)",
             family="pr-ruins", reason="The carved stone figure, the shared statue figure, stacked on its pedestal (42).")
    out.entity("crystal_pr_ruins_statue", [[RUIN_STATUE], [RUIN_PEDESTAL]], "Ruins statue (stack)", kind="stack (model as one)")
    for key in RUIN_WALLS:
        out.cell(key, *block(key), label="puzzle wall panel (wall row)", family="pr-ruins",
                 reason="The puzzle wall's panel filling its wall-row cell: a full block with its drawing.")
    out.cell(RUIN_STONE, *stone(), label="carved stone", family="pr-ruins", reason="The item room's carved stone, its drawn face.")
    for key, label in WARPS.items():
        out.keep_warp(key, label=label, family="pr-warps")
    for key, why in RETAINED.items():
        out.keep(key, label=f"{why} (never drawn)", family="pr-retained", reason=WHY[why])
    out.write(families=FAMILIES)


if __name__ == "__main__":
    main()
