"""Crystal checkpoint 19: TILESET_TOWER_1 (the Burned Tower), TILESET_TOWER_2 (the statue pedestals the gym figure
stands on, Cianwood's boulders, the Tin Tower entrance), TILESET_TOWER_3 (the Sprout Tower and the Tin Tower floors) and
TILESET_TOWER_4 (the Tin Tower roof).

Source reading (study/groups/TILESET_TOWER_*; the tower maps):

- The Sprout and Tin Towers' wooden lattice walls: east-west segments face-on (full blocks, their lattice on the face),
  north-south segments seen from above (a one-block wall over the drawn middle columns), the Tin Tower's posts and the
  floor ledge; the central swaying pillar (2x3 full blocks: its dark top, its face below).
- Statues: the Tin Tower figures and the Sprout Tower's Bellsprout heads on their pedestals (stacks; the shared statue
  builders); the two pedestal variants the gym figure also stands on (checkpoint 11's alsoOn) with the shared pedestal.
- The Burned Tower's carved head in the wall row over its barrel pillar, the burnt rubble (low mounds); Cianwood's
  boulders (the rounded rock of Olivine's); the Tin Tower roof's tiles as a low raised surface beside the walkway (one
  model per tile key cannot follow a slope across the columns); stairway warps kept (WARP_MODELS.md).
"""
from __future__ import annotations

from collections import Counter

import numpy as np

from tools.shape_fit.crystal_pass import furniture as F
from tools.shape_fit.crystal_pass import interiors as I
from tools.shape_fit.crystal_pass.common import K, Pass
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, front_rect, top_rect

S = (16, 16, 16)
DARK = I.DARK
GREY, GREY_D = (156, 156, 156), (107, 107, 107)
TOPDRAW = top_rect((0, 0, 16, 16), (0, 16), (0, 16))
FULL = front_rect((0, 0, 16, 16), (0, 16), (0, 16))
FAMILIES = ["TILESET_TOWER_1", "TILESET_TOWER_2", "TILESET_TOWER_3", "TILESET_TOWER_4"]

EW_WALLS = [K("4806-4806-4809-4809"), K("4806-4806-4807-4808")]
NS_WALL = K("4810-4811-4810-4811")
POSTS = [K("4815-4894-4815-4895"), K("4896-4814-4897-4814")]
LEDGE = K("4916-4916-4813-4813")
PILLAR_TOP = [K("4821-4822-4828-0"), K("4822-4846-0-4852")]
PILLAR_MID = [K("4833-0-4839-4840"), K("0-4857-4840-4863")]
PILLAR_FOOT = [K("4839-4840-4869-4870"), K("4840-4863-4870-4876")]
TIN_FIGURE, TIN_PEDESTAL = K("4086-4087-4088-4089"), K("4888-4889-4879-4880")
SPROUT_HEADS, SPROUT_PEDESTAL = [K("4910-4911-4903-4904"), K("4900-4901-4903-4904")], K("4906-4907-4879-4880")
GYM_PEDESTALS = [K("1162-1163-1758-1759"), K("1369-1370-1758-1759")]
BOULDER = K("1769-1770-1771-1772")
BURNED_HEAD, BURNED_PILLAR, RUBBLE = K("944-945-946-947"), K("963-964-950-951"), K("967-968-969-970")
ROOF = [K("4883-4884-4883-4884"), K("4883-4885-4883-4885")]
STAIRS = [K("4798-4799-4800-4801"), K("4802-4803-4804-4805"), K("1679-1680-1681-1682"), K("1683-1684-1685-1686")]


def own(key, n=4):
    c = Cell(key)
    return [col for col, _ in Counter(tuple(int(a) for a in px) for px in c.pixels.reshape(-1, 3)).most_common(n)]


def block(key, *, top=None, front=FULL, x=(0, 16), z=(0, 16), h=16):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, x, (0, h), z), top=top or own(key), front=front, side=own(key))
    return b.done()


def pillar_mid(key):
    """The central pillar's middle row: its dark top over the drawn rows 0-7, its face from rows 8-15."""
    return block(key, top=top_rect((0, 0, 16, 8), (0, 16), (0, 16)), front=front_rect((0, 8, 16, 16), (0, 16), (0, 16)))


def barrel_pillar():
    c = Cell(BURNED_PILLAR)
    b = Build(c)
    b.add(F.cylinder(S, (8, 8), 6.5, (0, 16)), top=own(BURNED_PILLAR), front=front_rect((1, 0, 15, 16), (1.5, 14.5), (0, 16)), side=own(BURNED_PILLAR))
    return b.done()


def rubble():
    """A low mound of burnt rubble, its drawing on top."""
    c = Cell(RUBBLE)
    b = Build(c)
    occ = F.ellipsoid(S, (8, 0, 8), (8, 7, 8)) & (np.indices(S)[0] >= 0)
    b.add(occ, top=TOPDRAW, front=own(RUBBLE), side=own(RUBBLE))
    return b.done()


def boulder():
    c = Cell(BOULDER)
    b = Build(c)
    occ = F.ellipsoid(S, (8, 6, 8), (7.6, 9, 7.6)) & (np.indices(S)[0] < 14)
    b.add(occ, top=TOPDRAW, front=FULL, side=own(BOULDER))
    return b.done()


def plate(key, h=1):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, h), (0, 16)), top=TOPDRAW, front=own(key), side=own(key))
    return b.done()


def main():
    out = Pass("towers")
    for key in EW_WALLS:
        out.cell(key, *block(key), label="lattice wall (east-west)", family="tw-walls",
                 reason="An east-west wooden lattice wall segment: a full block with its lattice on the face.")
    out.cell(NS_WALL, *block(NS_WALL, top=TOPDRAW, x=(4, 12)), label="lattice wall (north-south)", family="tw-walls",
             reason="A north-south lattice wall seen from above: one block thick over the drawn middle columns (4-11).")
    for key in POSTS:
        out.cell(key, *block(key), label="Tin Tower post", family="tw-walls", reason="The post and lattice end: a full block, its face drawn.")
    out.cell(LEDGE, *plate(LEDGE, h=4), label="floor ledge", family="tw-walls", reason="The floor's ledge as a low step, its drawn top.")
    for key in PILLAR_TOP:
        out.cell(key, *block(key, top=TOPDRAW), label="central pillar (top row)", family="tw-pillar",
                 reason="The swaying central pillar: a full block, its dark top drawn.")
    for key in PILLAR_MID:
        out.cell(key, *pillar_mid(key), label="central pillar (middle row)", family="tw-pillar",
                 reason="The pillar's middle row: the dark top over the drawn north half, its face below.")
    for key in PILLAR_FOOT:
        out.cell(key, *block(key), label="central pillar (face)", family="tw-pillar", reason="The pillar's lower face, a full block.")
    out.entity("crystal_tw_pillar", [PILLAR_TOP, PILLAR_MID, PILLAR_FOOT], "Sprout Tower central pillar")
    out.cell(TIN_PEDESTAL, *I.statue_pedestal(TIN_PEDESTAL, stone=tuple(own(TIN_PEDESTAL, 3))), label="Tin Tower statue pedestal",
             family="tw-statues", reason="The pedestal, the shared builder.")
    out.cell(TIN_FIGURE, *I.statue_figure(TIN_FIGURE, stone=tuple(own(TIN_FIGURE, 3))), label="Tin Tower statue (stacked)",
             family="tw-statues", reason="The gold figure, the shared statue figure, stacked on its pedestal.")
    out.entity("crystal_tw_tin_statue", [[TIN_FIGURE], [TIN_PEDESTAL]], "Tin Tower statue (stack)", kind="stack (model as one)")
    out.cell(SPROUT_PEDESTAL, *I.statue_pedestal(SPROUT_PEDESTAL, stone=tuple(own(SPROUT_PEDESTAL, 3))), label="Bellsprout statue pedestal",
             family="tw-statues", reason="The pedestal, the shared builder.")
    for key in SPROUT_HEADS:
        out.cell(key, *I.statue_figure(key, stone=tuple(own(key, 3))), label="Bellsprout statue head (stacked)", family="tw-statues",
                 reason="The gold Bellsprout head, the shared statue figure, stacked on its pedestal.")
    out.entity("crystal_tw_sprout_statue", [[SPROUT_HEADS[0]], [SPROUT_PEDESTAL]], "Sprout Tower statue (stack)", kind="stack (model as one)")
    for key in GYM_PEDESTALS:
        out.cell(key, *I.statue_pedestal(key, stone=(GREY, GREY_D, DARK)), label="gym statue pedestal", family="tw-statues",
                 reason="A pedestal the gym statue figure stands on (checkpoint 11's alsoOn), the shared builder.")
    out.entity("crystal_tw_gym_statue", [[K("1359-1360-1361-1362")], [GYM_PEDESTALS[1]]], "Gym statue on its Cianwood/Ecruteak pedestal (alsoOn stack)",
               kind="stack (model as one)")
    out.cell(BOULDER, *boulder(), label="boulder", family="tw-rocks", reason="Cianwood's boulder, the rounded rock of Olivine's.")
    out.cell(BURNED_HEAD, *block(BURNED_HEAD), label="Burned Tower carved head (wall row)", family="tw-burned",
             reason="The carved head filling its wall-row cell: a full block with its drawing.")
    out.cell(BURNED_PILLAR, *barrel_pillar(), label="Burned Tower barrel pillar", family="tw-burned", reason="The pillar below the head.")
    out.entity("crystal_tw_burned_pillar", [[BURNED_HEAD], [BURNED_PILLAR]], "Burned Tower carved pillar")
    out.cell(RUBBLE, *rubble(), label="burnt rubble", family="tw-burned", reason="A low mound of rubble, its drawing on top (77).")
    for key in ROOF:
        out.cell(key, *plate(key, h=4), label="Tin Tower roof tiles", family="tw-roof",
                 reason="The roof beside the walkway as a low raised surface showing its tiles (one model per key).")
    for key in STAIRS:
        out.keep_warp(key, label="stairway (warp)", family="tw-warps")
    out.write(families=FAMILIES)


if __name__ == "__main__":
    main()
