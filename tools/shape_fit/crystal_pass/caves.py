"""Crystal checkpoint 26: the caves (TILESET_CAVE_1/2/3, TILESET_DARK_CAVE_1), the Ice Path (TILESET_ICE_PATH_2) and the
Hall of Fame (its keys fall in TILESET_ICE_PATH_1).

Source reading (study/groups/TILESET_CAVE_*, DARK_CAVE_1, ICE_PATH_*; Diglett's Cave, Dark Cave, the Hall of Fame maps):

- Boulders (the cave's 767, the dark cave's 940: one drawing in two palettes; the Ice Path's ice rocks): rounded rocks.
  The 2x2 rock formations (Burned Tower B1F, the Dark Cave, the Ice Path) one mound over their four cells; the Ice Path's
  two-wide rocks (repeated north-south) one elongated rock over each pair.
- Ledges and their north-south edges: a low step, the rock on the face and ends (Johto's, Kanto's and Ilex's rule). Two
  dark-cave edge keys (77 and 23 placements) had empty models: invisible solid cells until now.
- The daylight openings in the rock (Mount Moon, Diglett's Cave, the Dark Cave, the Seafoam Gym, Silver Cave): a framed
  opening glowing with the light at the back of the cell and a little light on the floor (one design for the four keys;
  only one of them drew it, the others were a scrap of floor, a sand slab and a floating canopy).
- Ladders (warp cells with walk models): kept (WARP_MODELS.md).
- The Hall of Fame: the recording machine designed as one object over its 4x3 cells (the back wall full height, so the
  wall row stays closed; fluted blue pillars to the front with steps; lamp posts; the console with its lit panel on top and
  its framed screen on the front), the PC a cabinet with its keypad recessed. The step corner (3346-3347-3309-3310) is
  linked `on` the PC but is the dais step and floor, not the PC's top: the false stack is dropped (it is then a walkable
  cell, never drawn).
"""
from __future__ import annotations

import copy
import json
from collections import Counter

import numpy as np

from tools.shape_fit.crystal_pass import furniture as F
from tools.shape_fit.crystal_pass import interiors as I
from tools.shape_fit.crystal_pass.common import K, STUDY, Pass
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, front_rect, side_rect, top_rect

S = (16, 16, 16)
TOPDRAW = top_rect((0, 0, 16, 16), (0, 16), (0, 16))
FULL = front_rect((0, 0, 16, 16), (0, 16), (0, 16))
SIDEDRAW = side_rect((0, 0, 16, 16), (0, 16), (0, 16))
FAMILIES = ["TILESET_CAVE_1", "TILESET_CAVE_2", "TILESET_CAVE_3", "TILESET_DARK_CAVE_1", "TILESET_ICE_PATH_1", "TILESET_ICE_PATH_2"]
LIGHT = [(247, 247, 90), (255, 255, 58)]
EDGE = [(0, 0, 0), (58, 58, 58), (41, 0, 74)]

BOULDERS = [K(k) for k in ("1044-1045-969-970", "2102-2103-2104-2105", "3438-3439-3442-3443", "3466-3467-3468-3469", "3470-3471-3472-3473")]
RUBBLE = K("3387-3388-3389-3390")
ROCKS_2X2 = {"crystal_cv_rock": [[K("1011-1012-1013-1014"), K("1012-1015-1014-1016")], [K("1013-1014-1025-1026"), K("1014-1016-1026-1027")]],
             "crystal_dc_rock": [[K("2071-2072-2073-2074"), K("2072-2075-2074-2076")], [K("2073-2074-2085-2086"), K("2074-2076-2086-2087")]],
             "crystal_ip_rock": [[K("3450-3451-3454-3455"), K("3452-3453-3456-3457")], [K("3458-3459-3462-3463"), K("3460-3461-3464-3465")]]}
ICE_PAIR = [[K("3403-3404-3407-3408"), K("3405-3406-3409-3410")]]
# ledges: the drawing's rock quarter, for the ends
LEDGES = {K("1009-1009-1026-1026"): (0, 8, 8, 16), K("2069-2069-2086-2086"): (0, 8, 8, 16), K("2073-2069-2073-2069"): (0, 0, 8, 16),
          K("2069-2076-2069-2076"): (8, 0, 16, 16), K("2069-2076-2086-2087"): (0, 8, 8, 16), K("2073-2069-2085-2086"): (0, 8, 8, 16),
          K("3391-3394-3391-3394"): (8, 0, 16, 16)}
OPENINGS = [K(k) for k in ("1042-1043-1014-1014", "1042-1043-2074-2074", "3305-3306-2848-2848", "4765-4766-3906-3906")]
LADDERS = [K(k) for k in ("1017-1018-1019-1020", "1021-1022-1023-1024", "1046-1047-1048-1049", "1050-1051-1052-1053",
                          "2077-2078-2079-2080", "2106-2107-2108-2109", "2110-2111-2112-2113", "3379-3380-3381-3382", "3399-3400-3401-3402")]
HOF = [[K("3311-3311-3313-3312"), K("3311-3311-3317-3318"), K("3311-3311-3318-3319"), K("3311-3311-3312-3328")],
       [K("3314-3315-3313-3316"), K("3320-3321-3324-3325"), K("3322-3323-3326-3327"), K("3329-3330-3331-3328")],
       [K("3314-3316-3332-3333"), None, None, K("3343-3330-3344-3345")]]
HOF_PC, HOF_STEP = K("3348-3349-3350-3351"), K("3346-3347-3309-3310")


def own(key, n=4):
    c = Cell(key)
    return [col for col, _ in Counter(tuple(int(a) for a in px) for px in c.pixels.reshape(-1, 3)).most_common(n)]


def union(keys):
    out = []
    for k in keys:
        if not k:
            continue
        for col in own(k, 8):
            if col not in out:
                out.append(col)
    return out


def boulder(key):
    """A rounded rock filling its cell, its drawing on top and faces."""
    c = Cell(key)
    b = Build(c)
    occ = F.ellipsoid(S, (8, 5, 8), (7.8, 9.5, 7.8)) & (np.indices(S)[0] < 14)
    b.add(occ, top=TOPDRAW, front=FULL, side=SIDEDRAW)
    return b.done()


def rubble():
    """A low rough mound of ice and rock, its drawing on top."""
    c = Cell(RUBBLE)
    b = Build(c)
    occ = F.ellipsoid(S, (8, 0, 8), (8, 6, 8))
    b.add(occ, top=TOPDRAW, front=FULL, side=SIDEDRAW)
    return b.done()


def mound(layout):
    rows, cols = len(layout), len(layout[0])
    zz_n, xx_n = rows * 16, cols * 16
    yy, zz, xx = np.indices((16, zz_n, xx_n))
    occ = (((xx + .5 - xx_n / 2) / (xx_n / 2 - .5)) ** 2 + ((yy + .5 - 1) / 15) ** 2 + ((zz + .5 - zz_n / 2) / (zz_n / 2 - 1.5)) ** 2) <= 1

    def colour_at(y, gz, gx, face):
        return (gx + .5, gz + .5) if face == "up" else (gx + .5, min(zz_n - .5, gz + .5 + (14 - y) * .4))
    return I.paint_global(occ, layout, colour_at, union([k for row in layout for k in row]))


def ledge(key, rock):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 5), (0, 16)), top=TOPDRAW, front=front_rect((0, 8, 16, 16), (0, 16), (0, 5)),
          side=side_rect(rock, (0, 16), (0, 5)))
    return b.done()


def opening(key):
    """A daylight opening in the rock: the light standing at the back of the cell in a dark frame, a little of it on the
    floor in front."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 14), (0, 2)), top=LIGHT, front=LIGHT, side=LIGHT)
    for x in ((0, 1), (15, 16)):
        b.add(box(S, x, (0, 16), (0, 2)), top=EDGE, front=EDGE, side=EDGE)
    b.add(box(S, (1, 15), (14, 16), (0, 2)), top=EDGE, front=EDGE, side=EDGE)
    b.add(box(S, (3, 13), (0, 1), (2, 5)), top=LIGHT, front=LIGHT, side=LIGHT)
    return b.done()


def hall_of_fame():
    """The recording machine over its 4x3 cells (global x 0-63, z 0-47, y 0-15; composite rows as z): the back wall
    (z 0-7) full height; fluted pillars (x 0-7, 56-63; z 8-39) full height ending in a step (z 40-47); lamp posts and arms
    (x 8-15, 48-55; z 8-31) twelve high, a low step in front (z 32-47); the console (x 16-47, z 8-23) fourteen high, its lit
    panel (rows 8-17) on top and its framed screen (rows 18-31) on its front. The two middle front cells are floor."""
    yy, zz, xx = np.indices((16, 48, 64))
    wall = zz < 8
    pillar = ((xx < 8) | (xx >= 56)) & (zz >= 8) & (zz < 40)
    pstep = ((xx < 15) | (xx >= 49)) & (zz >= 40) & (yy < 6) & ~pillar
    inner = (((xx >= 8) & (xx < 16)) | ((xx >= 48) & (xx < 56))) & (zz >= 8) & (zz < 32) & (yy < 12)
    istep = (((xx >= 8) & (xx < 16)) | ((xx >= 48) & (xx < 56))) & (zz >= 32) & (zz < 40) & (yy < 4)
    console = (xx >= 16) & (xx < 48) & (zz >= 8) & (zz < 24) & (yy < 14)
    occ = wall | pillar | pstep | inner | istep | console

    def colour_at(y, gz, gx, face):
        if wall[y, gz, gx]:
            return gx + .5, (15.5 - y) / 16 * 8 if face != "up" else 6.5
        if console[y, gz, gx]:
            if face == "up":
                return gx + .5, 8 + (gz + .5 - 8) / 16 * 10
            if face == "south":
                return gx + .5, 18 + (13.5 - y) / 14 * 14
            return (16.5 if gx < 32 else 46.5), 8 + (13.5 - y) / 14 * 22
        if pillar[y, gz, gx] and face == "south":
            return gx + .5, 40 + (15.5 - y) / 16 * 7
        if face in ("east", "west"):
            return (gx + .5), min(47.5, 8.5 + (15.5 - y) / 16 * 30)
        return gx + .5, gz + .5
    parts = I.paint_global(occ, HOF, colour_at, union([k for row in HOF for k in row]))
    return parts


def hof_pc():
    """The Hall of Fame PC: a grey cabinet, its top (drawn rows 1-7) sloping to the back, its keypad recessed in the front
    (rows 7-15)."""
    c = Cell(HOF_PC)
    b = Build(c)
    m = own(HOF_PC)
    b.add(box(S, (1, 15), (0, 12), (4, 13)), top=top_rect((1, 1, 15, 7), (1, 15), (4, 13)), front=front_rect((1, 7, 15, 16), (1, 15), (0, 12)),
          side=[(156, 156, 156), (107, 107, 107)])
    b.add(box(S, (1, 15), (12, 14), (4, 9)), top=top_rect((1, 1, 15, 4), (1, 15), (4, 9)), front=front_rect((1, 1, 15, 3), (1, 15), (12, 14)), side=m)
    b.cut(box(S, (3, 12), (2, 10), (11, 13)))
    return b.done()


def main():
    out = Pass("caves")
    census = json.loads((STUDY / "census" / "census.json").read_text(encoding="utf-8"))["keys"]
    for key in BOULDERS:
        out.cell(key, *boulder(key), label="boulder", family="cv-rocks", reason=f"A rounded rock filling its cell ({census[key]['drawn']}).")
    out.cell(RUBBLE, *rubble(), label="ice rubble", family="cv-rocks", reason="A low rough mound, its drawing on top.")
    for eid, layout in ROCKS_2X2.items():
        for key, (occ, paint) in mound(layout).items():
            out.cell(key, occ, paint, label="rock formation (2x2)", family="cv-rocks",
                     reason="One rounded mound designed over its 2x2 cells, painted from its composite drawing.")
        out.entity(eid, layout, "Cave rock formation")
    for key, (occ, paint) in mound(ICE_PAIR).items():
        out.cell(key, occ, paint, label="ice rock (two wide)", family="cv-rocks",
                 reason="One elongated rock over the pair of cells (the pair repeats north-south: a row of such rocks).")
    out.entity("crystal_ip_rock_pair", ICE_PAIR, "Ice Path two-wide rock")
    for key, rock in LEDGES.items():
        empty = census[key]["drawn"] and key in (K("2069-2069-2086-2086"), K("2073-2069-2073-2069"))
        out.cell(key, *ledge(key, rock), label="ledge", family="cv-ledges",
                 reason="The floor on top of a low step, the rock on its face and ends" +
                        (f" (the old model was empty: {census[key]['drawn']} invisible solid cells)." if empty else "."))
    for key in OPENINGS:
        out.cell(key, *opening(key), label="daylight opening", family="cv-openings",
                 reason="A framed opening glowing with daylight at the back of the cell, a little light on the floor (one design "
                        "for the four keys).")
    for key in LADDERS:
        out.keep_warp(key, label="ladder (warp)", family="cv-warps")
    for key, (occ, paint) in hall_of_fame().items():
        out.cell(key, occ, paint, label="Hall of Fame machine", family="cv-hof",
                 reason="Part of the recording machine designed over its 4x3 cells (back wall full height, pillars and steps, "
                        "lamp posts, the console with its lit panel and screen), painted from its composite drawing.")
    out.entity("crystal_hof_machine", HOF, "Hall of Fame recording machine (the two front middle cells are floor)")
    out.cell(HOF_PC, *hof_pc(), label="Hall of Fame PC", family="cv-hof", reason="A grey cabinet with its keypad recessed.")
    out.cell_entry(HOF_STEP, copy.deepcopy(out.table["cells"][HOF_STEP]), label="dais step corner (false stack dropped)", family="cv-hof",
                   reason="Its `on` link put the dais step and floor on top of the PC; the link is dropped, leaving a walkable "
                          "cell that is never drawn (its geometry unchanged).")
    out.drop_stack(HOF_STEP, "Its tiles are the dais's front step (3346/3347) and the floor (3309/3310), not the PC's top: the "
                             "PC (3348-3349-3350-3351, south of it at G2_HALL_OF_FAME 6,4) is drawn whole in its own cell, and "
                             "the stack put a blue step box on top of it.")
    modelled = {*BOULDERS, RUBBLE, *ICE_PAIR[0], *LEDGES, *OPENINGS, *LADDERS, HOF_PC, HOF_STEP,
                *(k for lay in ROCKS_2X2.values() for row in lay for k in row), *(k for row in HOF for k in row if k)}
    fam = json.loads((STUDY / "families.json").read_text(encoding="utf-8"))
    for F_ in FAMILIES:
        for key in fam[F_]:
            if key in modelled:
                continue
            info = census.get(key, {})
            if info.get("drawn", 0):
                raise SystemExit(f"{key} is drawn ({info['drawn']}) but has no decision")
            out.keep(key, label="never drawn", family="cv-retained",
                     reason=f"Never drawn by its entry (census: {', '.join(sorted(info.get('reasons') or {})) or 'unplaced'}); kept.")
    out.write(families=FAMILIES)


if __name__ == "__main__":
    main()
