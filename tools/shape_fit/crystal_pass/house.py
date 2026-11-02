"""Crystal checkpoint 2: TILESET_HOUSE_1 (Bill's, Mr Fuji's, the Fan Club, Trainer House, Soul House, the flower
shop and dozens of speech houses) and TILESET_PLAYERS_ROOM_1 (the player's bedroom).

Source reading (study/groups/TILESET_HOUSE_1, TILESET_PLAYERS_ROOM_1; the census of where each entry is drawn):

- Never drawn (census): the Soul House floor candle (walkable), the two Trainer House counter cells (collision 144:
  Crystal keeps the native counter fold), the flower shop's two walkable apron cells, the bedroom stairs warp.
- Tables: one system shared with the Lab, Mansion and Facility families (interiors.table_piece): a 1-block top
  showing each cell's drawing, legs at the drawn outer corners only, and on a solid south row the top ending
  mid-cell at its drawn front (rows 8-11) with the apron. The 4x4 table (Fan Club, Trainer House, Safari Zone
  Warden) carries a potted plant on its centre, drawn across four cells: a pot and a leafy mound as one object.
- Wall-row tops stacked on their bases (the census and map check: every placement stacks): the TV's upper body
  over the TV on its cabinet, the stereo's top and handle over the stereo on its cabinet, the PC monitor's
  screen over the desk, the bookcases' upper shelves and tops over their bases (Mr Fuji's two in-place tops
  stand before the wall as low cases). The tall potted plant: pot and stem in the base, the leaf crown rising
  through the stacked top. The flower shop's tall planter: box and flowers through its stacked top.
- Round cushion stools (seat, 222 placements) at the existing seat height; Soul House gravestones (an
  upright tablet on its plinth); single flower planters; the Trainer House stairwell down (a warp cell: kept, its WARP_MODELS.md model; was: a floor-level
  well with its low side wall); the bedroom's radio-cassette player, grey bookcase and TV with its console.
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
BLUE, BLUE_D = (123, 132, 255), (74, 74, 255)
PINK = (255, 156, 197)
GREEN, GREEN_D = (123, 165, 8), (74, 107, 0)
ORANGE, YELLOW = (255, 132, 8), (255, 255, 58)

NEVER = {  # key: (label, reason) for the entries the census never finds drawn
    K("655-740-655-655"): ("Soul House floor candle", "walk"),
    K("659-659-692-692"): ("Trainer House counter top", "counter"),
    K("659-668-662-665"): ("Trainer House counter end", "counter"),
    K("673-692-655-655"): ("flower-shop table front edge on the floor", "walk"),
    K("692-674-655-655"): ("flower-shop table front edge on the floor", "walk"),
    K("102-1848-4215-4216"): ("bedroom stairs warp", "walk"),
}
WHY = {"walk": "Every placement is a walkable cell and the entry has no walk or seat flag: the planner never draws it "
               "(census: walk).",
       "counter": "Collision 144 is a counter: Crystal keeps the native counter fold and never draws this entry "
                  "(census: counter)."}

APRON = (9, (8, 12))
TABLE = {  # key: (edges, legs, apron, label)
    K("658-659-662-663"): ("nw", "nw", None, "table north-west corner"),
    K("659-659-663-663"): ("n", "", None, "table north edge"),
    K("659-664-663-665"): ("ne", "ne", None, "table north-east corner"),
    K("662-663-662-663"): ("w", "", None, "table west edge"),
    K("663-665-663-665"): ("e", "", None, "table east edge"),
    K("663-663-692-692"): ("", "", APRON, "table front edge (solid apron)"),
    K("691-663-673-692"): ("w", "sw", APRON, "table south-west corner (solid apron)"),
    K("663-693-692-674"): ("e", "se", APRON, "table south-east corner (solid apron)"),
    K("659-668-663-665"): ("ne", "ne se", None, "flower-shop table north-east corner (its front row is floor)"),
}
CENTRE = [[K("663-725-663-727"), K("726-663-728-663")], [K("663-729-663-663"), K("730-663-663-663")]]
TRAINER_NW, TRAINER_SW = K("658-659-711-712"), K("715-716-673-692")
STOOL = K("656-657-660-661")
TV_TOP, TV = K("666-666-721-722"), K("723-724-675-676")
STEREO_TOP, STEREO = K("666-666-717-718"), K("719-720-675-676")
PC_TOP, PC = K("666-666-698-699"), K("700-701-702-703")
CASE = [(K("667-668-669-670"), K("671-672-673-674")), (K("667-668-671-672"), K("671-672-675-676"))]
FUJI_CASE = K("669-670-675-676")
PLANT_TOP, PLANT = K("681-682-683-684"), K("685-686-687-688")
STAIRS = K("731-732-733-734")
GRAVES = (K("735-736-737-738"), K("735-736-737-739"))
PLANTER = K("743-744-747-748")
TALL_TOP, TALL = K("743-744-745-746"), K("745-746-747-748")
ROOM_TV, ROOM_RADIO = K("1909-1910-4223-4224"), K("4211-4212-4213-4214")
ROOM_CASE_TOP, ROOM_CASE = K("4217-4218-4219-4220"), K("4221-4222-4225-4226")
PLAYERS_TV_TOP = K("1905-1905-1907-1908")  # players-house key: only its stack link changes (see main)

OWNED = [*NEVER, *TABLE, *[k for row in CENTRE for k in row], TRAINER_NW, TRAINER_SW, STOOL, TV_TOP, TV, STEREO_TOP,
         STEREO, PC_TOP, PC, *[k for pair in CASE for k in pair], FUJI_CASE, PLANT_TOP, PLANT, STAIRS, *GRAVES, PLANTER,
         TALL_TOP, TALL, ROOM_TV, ROOM_RADIO, ROOM_CASE_TOP, ROOM_CASE]


def centrepiece():
    """The 4x4 table's middle: table top over all four cells, a square pot (drawn rows 13-23 of the 32x32
    composite) and a rounded leaf mound (rows 0-13) standing on it, centred where the cells meet."""
    occ = np.zeros((16, 32, 32), bool)
    sh = occ.shape
    table = box(sh, (0, 32), I.TOP, (0, 32))
    pot = box(sh, (9, 23), (8, 11), (10, 22))
    rim = box(sh, (8, 24), (10, 11), (9, 23)) & ~box(sh, (10, 22), (10, 11), (11, 21))
    mound = F.ellipsoid(sh, (16, 11.5, 15.5), (6.2, 5, 5.4)) & (np.indices(sh)[0] >= 10)
    occ |= table | pot | rim | mound
    role = np.zeros(sh, np.uint8)
    role[table] = 1
    role[pot | rim] = 2
    role[mound] = 3

    def at(y, z, x, face):
        r = role[y, z, x]
        if r == 1:
            return x, z if face == "up" else 20               # the table's own drawing; sides in plain wood
        if r == 2:
            return x, (14 + (10 - y) * 9 / 3) if face != "up" else 13
        if face == "up":
            return x, max(0.0, min(12.0, (z - 10) * 12 / 11))     # the mound's top: drawn rows 0-12
        return x, max(0.0, min(13.0, 13 - (y - 9) * 1.6))        # its sides: the lower drawn rows
    return I.paint_global(occ, CENTRE, at, [WOOD, WOOD_L, WOOD_D, DARK])


def trainer_table():
    """Trainer House's small table: the north-west corner carries a two-compartment box (its top rows 8-11, its
    front rows 12-15); the south-west apron's top shows only table wood (the box's drawn front there is perspective)."""
    nw = Cell(TRAINER_NW)
    b = Build(nw)
    b.add(box(S, (1, 16), I.TOP, (1, 16)), top=top_rect((0, 0, 16, 16), (0, 16), (0, 16)), front=I.LEG, side=I.LEG)
    b.add(box(S, (2, 4), (0, 6), (2, 4)), top=I.LEG, front=I.LEG, side=I.LEG)
    b.add(box(S, (3, 14), (8, 14), (8, 14)), top=top_rect((3, 8, 14, 12), (3, 14), (8, 14)),
          front=front_rect((2, 12, 15, 16), (3, 14), (8, 14)), side=[DARK, WOOD_D])
    sw = Cell(TRAINER_SW)
    c = Build(sw)
    z1 = APRON[0]
    c.add(box(S, (1, 16), I.TOP, (0, z1)), top=[WOOD], front=front_rect((0, 8, 16, 12), (0, 16), (4, 8)), side=I.LEG)
    c.add(box(S, (1, 16), (4, 6), (z1 - 1, z1)), top=I.LEG, front=front_rect((0, 8, 16, 12), (0, 16), (4, 8)), side=I.LEG)
    c.add(box(S, (2, 4), (0, 6), (z1 - 3, z1 - 1)), top=I.LEG, front=I.LEG, side=I.LEG)
    return {TRAINER_NW: b.done(), TRAINER_SW: c.done()}


def desk_and_monitor(desk_key, top_key):
    """The PC desk (keyboard on its top, the monitor's lower body standing on it) and the stacked screen."""
    d = Build(Cell(desk_key))
    d.add(box(S, (0, 16), (0, 9), (2, 15)), top=top_rect((0, 6, 16, 13), (0, 16), (2, 15)),
          front=front_rect((0, 12, 16, 15), (0, 16), (0, 9)), side=[GREY_D, DARK])
    d.add(box(S, (3, 13), (9, 16), (3, 9)), top=[GREY], front=front_rect((3, 0, 13, 6), (3, 13), (9, 16)), side=[GREY, DARK])
    m = Build(Cell(top_key))
    m.add(box(S, (3, 13), (0, 6), (3, 9)), top=[BLUE, DARK], front=front_rect((2, 9, 14, 15), (3, 13), (0, 6)), side=[BLUE, DARK])
    return {desk_key: d.done(), top_key: m.done()}


def stereo_top():
    """The stereo's upper body (its cream top band, rows 12-15) and the upright carrying handle (rows 8-11)."""
    b = Build(Cell(STEREO_TOP))
    b.add(box(S, (1, 15), (0, 4), (5, 13)), top=[CREAM], front=front_rect((1, 12, 15, 16), (1, 15), (0, 4)), side=[CREAM, DARK])
    b.add(box(S, (12, 15), (4, 8), (8, 9)), top=[CREAM], front=front_rect((12, 8, 15, 12), (12, 15), (4, 8)), side=[DARK])
    return b.done()


def tall_plant():
    """Pot, stem and a leaf crown as one object across the base (y 0-15) and its stacked top (y 16-31). The base
    cell draws its lower foliage dark and brown (the crown's shade) and the top cell green: each cell's leaves
    take only its own leaf colours, by front projection of the two-cell drawing."""
    occ = np.zeros((32, 16, 16), bool)
    sh = occ.shape
    pot = F.cylinder(sh, (8, 9), 4.2, (0, 6)) | F.cylinder(sh, (8, 9), 4.8, (5, 7))
    stem = box(sh, (7, 9), (6, 13), (8, 10))
    crown = (F.ellipsoid(sh, (8, 21, 8.5), (6.8, 9, 6.2)) | F.ellipsoid(sh, (4.5, 16, 8), (3.5, 4.5, 4))
             | F.ellipsoid(sh, (11.5, 17, 9), (3.5, 4.5, 4)) | F.ellipsoid(sh, (8, 27.5, 8), (4.5, 3.5, 4.5)))
    occ |= pot | stem | crown
    leaf = crown & ~pot
    out = {}
    for key, y0 in ((PLANT, 0), (PLANT_TOP, 16)):
        c = Cell(key)
        have = {tuple(int(a) for a in px) for px in c.pixels.reshape(-1, 3)}
        greens = [m for m in (GREEN, GREEN_D) if m in have]
        leaves = (greens + ([CREAM] if CREAM in have else [])) if greens else [DARK, WOOD_D]
        part = occ[y0:y0 + 16]
        paint = np.zeros(part.shape, np.uint8)
        for y, z, x in np.argwhere(part):
            gy = y0 + y
            if leaf[gy, z, x]:
                v = min(15, max(0, 15 - y))            # front projection within this cell's own rows
                want = tuple(int(a) for a in c.pixels[v, x])
                paint[y, z, x] = c.pick(([want] if want in leaves else []) + leaves, x + .5, v + .5)
            elif pot[gy, z, x]:
                paint[y, z, x] = c.pick([WOOD_D, DARK], x + .5, 12)
            else:
                paint[y, z, x] = c.pick([WOOD_D, DARK], x + .5, 9)
        out[key] = (part.copy(), paint)
    return out


def planter(key, *, flowers_to=12, rows=(1, 8), box_rows=(8, 15)):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 7), (3, 15)), top=[WOOD_D, DARK], front=front_rect((1, box_rows[0], 15, box_rows[1]), (1, 15), (0, 7)),
          side=[WOOD_D, WOOD, DARK])
    yy, zz, xx = np.indices(S)
    mass = np.zeros(S, bool)
    for zi in range(3, 15):
        for xi in range(1, 15):
            v = int(rows[0] + (zi - 3) * (rows[1] - rows[0]) / 12)
            colour = tuple(int(a) for a in c.pixels[v, xi])
            h = flowers_to if colour in (ORANGE, YELLOW) else (flowers_to - 1 if colour in (GREEN, GREEN_D) else 9)
            edge = min(zi - 3, 14 - zi, xi - 1, 14 - xi)
            h = min(h, 8 + 2 * edge) if edge < 2 else h
            mass[7:h, zi, xi] = True
    b.add(mass, top=top_rect((1, rows[0], 15, rows[1]), (1, 15), (3, 15)),
          front=front_rect((1, rows[0], 15, rows[1]), (1, 15), (7, flowers_to)), side=[GREEN, GREEN_D])
    return b.done()


def tall_planter():
    """The flower shop's tall planter: the box at the bottom of the base and a rounded bush of leaves on long
    stems up through the stacked top, with a blossom bump wherever the two-cell drawing has an orange flower."""
    occ = np.zeros((32, 16, 16), bool)
    sh = occ.shape
    boxm = box(sh, (1, 15), (0, 7), (3, 15))
    bush = F.ellipsoid(sh, (8, 18, 9), (6.6, 11.5, 5.6)) & (np.indices(sh)[0] >= 7)
    blossoms = np.zeros(sh, bool)
    # Blossom centres read from the two-cell drawing (u, v over 32 rows): top cell (4.5, 4.5) and (11.5, 11.5),
    # base (11.5, 19.5). Each stands out of the bush's front, at the drawn height.
    for cu, cv in ((4.5, 4.5), (11.5, 11.5), (11.5, 19.5)):
        blossoms |= F.ellipsoid(sh, (cu, 31 - cv, 12.2), (2.6, 2.6, 2.0))
    occ |= boxm | bush | blossoms
    occ[:7] &= boxm[:7]
    out = {}
    for key, y0 in ((TALL, 0), (TALL_TOP, 16)):
        c = Cell(key)
        part = occ[y0:y0 + 16]
        paint = np.zeros(part.shape, np.uint8)
        e = F.exposed(part)
        for y, z, x in np.argwhere(part):
            gy = y0 + y
            if boxm[gy, z, x]:
                paint[y, z, x] = (int(min(14, 8 + (6 - gy))) * 16 + x) if e["south"][y, z, x] and not e["up"][y, z, x]                     else c.pick([WOOD_D, WOOD], x + .5, 10)
            elif blossoms[gy, z, x] and (not bush[gy, z, x] or z >= 11):
                v = min(15, max(0, 15 - y))
                paint[y, z, x] = c.pick([ORANGE, YELLOW], x + .5, v + .5)
            else:
                v = min(15, max(0, 15 - y))
                want = tuple(int(a) for a in c.pixels[v, x])
                paint[y, z, x] = c.pick(([want] if want in (GREEN, GREEN_D) else []) + [GREEN, GREEN_D], x + .5, v + .5)
        out[key] = (part.copy(), paint)
    return out


def stool():
    return I.round_stool(STOOL, background=[PINK, CREAM], center=(8, 8.5), radius=5.5, seat=(2, 4), top_rows=(5, 12))


def grave(key):
    """An upright stone tablet (its face with the plaque, drawn rows 0-11) on a plinth (rows 12-15)."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 3), (5, 13)), top=[WOOD_D, WOOD], front=front_rect((1, 12, 15, 16), (1, 15), (0, 3)), side=[WOOD_D, DARK])
    stone = box(S, (2, 14), (3, 15), (7, 11))
    yy, zz, xx = np.indices(S)
    stone &= ~((yy >= 13) & ((xx < 4) | (xx >= 12)))   # rounded shoulders
    stone &= ~((yy >= 14) & ((xx < 5) | (xx >= 11)))
    b.add(stone, top=[WOOD_D, WOOD], front=front_rect((2, 0, 14, 12), (2, 14), (3, 15)), side=[WOOD_D, WOOD])
    return b.done()


def stairs():
    """Trainer House stairwell down (walk): a floor-level plate showing the steps and the well, and the well's low
    side wall along its east edge (drawn rows 6-15, columns 12-14). Walk-through decoration, no collision."""
    c = Cell(STAIRS)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 1), (0, 16)), top=top_rect((0, 0, 16, 16), (0, 16), (0, 16)), front=[WOOD_D, DARK], side=[WOOD_D, DARK])
    # the well's east side wall (the drawn ochre strip, columns 12-14 of rows 6-15), low as a balustrade
    b.add(box(S, (12, 15), (1, 7), (5, 16)), top=[WOOD_L, WOOD], front=[WOOD, WOOD_L], side=[WOOD, WOOD_D])
    return b.done()


def room_radio():
    """The bedroom's radio-cassette player on the floor: its top (rows 0-6) and front with the speaker (rows 8-13)."""
    c = Cell(ROOM_RADIO)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 8), (5, 14)), top=top_rect((0, 0, 16, 7), (1, 15), (5, 14)),
          front=front_rect((1, 8, 15, 14), (1, 15), (0, 8)), side=[GREY, GREY_D])
    b.add(box(S, (3, 13), (8, 9), (8, 10)), top=[GREY_D], front=[GREY_D], side=[GREY_D])  # carrying handle bar
    for x in (3, 12):
        b.add(box(S, (x, x + 1), (8, 10), (8, 10)), top=[GREY_D], front=[GREY_D], side=[GREY_D])
    return b.done()


def main():
    out = Pass("house")
    for key, (label, why) in NEVER.items():
        out.keep(key, label=label, family="house-never-drawn", reason=WHY[why])
    for key, (edges, legs, apron, label) in TABLE.items():
        out.cell(key, *I.table_piece(key, edges=edges, legs=legs, apron=apron), label=label, family="table",
                 reason="Shared table system: a 1-block top showing this cell's drawing, legs only at drawn outer corners"
                        + (", the top ending at its drawn front with the apron" if apron else "") + ".")
    for key, (occ, paint) in centrepiece().items():
        out.cell(key, occ, paint, label="table centre with the potted plant (quarter)", family="table-centrepiece",
                 reason="The 4x4 table's middle: table top, a square pot and a rounded leaf mound designed as one object "
                        "across four cells (drawing rows 0-23 of the composite), sliced at the seams.")
    for key, parts in trainer_table().items():
        out.cell(key, *parts, label="Trainer House small table " + ("with its box" if key == TRAINER_NW else "front edge"),
                 family="table", reason="Small table with a two-compartment box standing on it; the south cell's top is "
                                        "plain wood (the box's lower front drawn there is perspective).")
    out.cell(STOOL, *stool(), label="round cushion stool", family="seating",
             reason="Pink cushion with its red ring on four short legs at the existing seat height (y 4); the checkered "
                    "floor around it stays floor.")
    tv = I.device_on_cabinet(TV, cabinet_rows=(7, 16), device_rows=(0, 7), device_x=(2, 14), device=(BLUE, BLUE_D))
    out.cell(TV, *tv, label="TV on its cabinet (lower half)", family="tv-stack",
             reason="Cabinet with its door below, the TV body standing on it with the screen facing south.")
    out.cell(TV_TOP, *I.device_top(TV_TOP, rows=(11, 16), top_rows=(8, 11), device_x=(2, 14), height=6, device=(BLUE, BLUE_D)),
             label="TV upper body (stacked)", family="tv-stack",
             reason="Stacked on the TV in all 50 placements: only the TV's upper body; the blue wall rows are not drawn on it.")
    out.cell(STEREO, *I.device_on_cabinet(STEREO, cabinet_rows=(8, 16), device_rows=(0, 7), device_x=(1, 15)),
             label="stereo on its cabinet (lower half)", family="stereo-stack",
             reason="Cabinet below, the grey stereo with its knobs and speaker grille standing on it.")
    out.cell(STEREO_TOP, *stereo_top(), label="stereo top and handle (stacked)", family="stereo-stack",
             reason="Stacked in all 51 placements: the stereo's cream top band and its upright handle, no wall rows.")
    for key, parts in desk_and_monitor(PC, PC_TOP).items():
        out.cell(key, *parts, label="PC desk with keyboard" if key == PC else "PC monitor screen (stacked)", family="pc-stack",
                 reason="The players-house PC design: desk with keyboard, the monitor standing on it, its screen in the "
                        "stacked top.")
    for top, base in CASE:
        right_column = base == K("671-672-673-674")
        out.cell(base, *I.case_base(base, rows=(0, 14) if right_column else (0, 16), shelf=((2, 10), (9, 15), (1, 7))),
                 label="bookcase lower half (shelf and cabinet)", family="bookcase",
                 reason="Base of a tall bookcase on the floor: a recessed shelf of books over the cabinet; 9 voxels deep.")
        out.cell(top, *I.case_top(top, front_rows=(8, 16), top_rows=(1, 8),
                                  shelf=((2, 10) if not right_column else (2, 14), (1, 7), (9, 15))),
                 label="bookcase upper half (stacked)", family="bookcase",
                 reason="Stacked on its base (54 placements; Mr Fuji's two unmatched placements stand in the wall row as "
                        "low cases): the upper shelf and the top surface.")
        out.entity(f"crystal_house_bookcase_{base[4:]}", [[top], [base]], "House bookcase (stack)", kind="stack (model as one)")
    # Mr Fuji's two bookcases stand on base variants their top's link does not name: it stood in place in the wall row,
    # a low case with the back wall open. alsoOn lets it stack on them too (amendment).
    out.also_on(K("667-668-669-670"), [K("669-670-675-676"), K("671-672-675-676")],
                "MR_FUJIS_HOUSE (8,0) over 669-670-675-676 and (9,0) over 671-672-675-676: the same bookcase top drawn "
                "over two base variants; without the link it stood in place in the wall row.")
    out.entity("crystal_fuji_bookcase_a", [[K("667-668-669-670")], [FUJI_CASE]], "Mr Fuji's bookcase (alsoOn stack)",
               kind="stack (model as one)")
    out.entity("crystal_fuji_bookcase_b", [[K("667-668-669-670")], [K("671-672-675-676")]], "Mr Fuji's bookcase on the other base "
               "(alsoOn stack)", kind="stack (model as one)")
    out.cell(FUJI_CASE, *I.case_base(FUJI_CASE, rows=(0, 16), shelf=((2, 14), (9, 15), (1, 7))),
             label="Mr Fuji's bookcase lower half", family="bookcase",
             reason="The same bookcase base with a full-width shelf; its wall-row top is a separate in-place cell.")
    for key, parts in tall_plant().items():
        out.cell(key, *parts, label="tall potted plant " + ("(pot and lower leaves)" if key == PLANT else "(crown, stacked)"),
                 family="plant", reason="One plant across its two stacked cells (109 placements): pot, stem and a leaf "
                                        "crown of several lobes; leaf texels from each cell's own greens, the checkered "
                                        "floor beside the pot excluded.")
    out.entity("crystal_house_tall_plant", [[PLANT_TOP], [PLANT]], "Tall potted plant (stack)", kind="stack (model as one)")
    out.keep_warp(STAIRS, label="Trainer House stairwell down (warp)", family="stairs")
    for key in GRAVES:
        out.cell(key, *grave(key), label="Soul House gravestone", family="gravestone",
                 reason="An upright tablet with rounded shoulders and its plaque on the face, standing on a plinth.")
    out.cell(PLANTER, *planter(PLANTER), label="flower planter", family="planter",
             reason="Wooden planter box with the blossoms and leaves as a low mound that follows the drawn flowers.")
    for key, parts in tall_planter().items():
        out.cell(key, *parts, label="tall flower planter " + ("(box)" if key == TALL else "(flowers, stacked)"), family="planter",
                 reason="One planter across its stacked cells: the box at the bottom and tall flowers whose outline "
                        "follows the drawing up through the top.")
    out.entity("crystal_house_tall_planter", [[TALL_TOP], [TALL]], "Flower-shop tall planter (stack)", kind="stack (model as one)")
    out.cell(ROOM_TV, *I.device_on_cabinet(ROOM_TV, cabinet_rows=(8, 15), device_rows=(0, 7), device_x=(3, 13),
                                           cabinet=(GREY, GREY_D), device=(BLUE, BLUE_D)),
             label="bedroom TV on its cabinet", family="tv-stack",
             reason="Grey cabinet with doors, the TV standing on it. The wall-row TV top above is drawn in place (its "
                    "false stack onto this cell is removed: it stands in place over the same TV in five other maps).")
    out.cell(ROOM_RADIO, *room_radio(), label="bedroom radio-cassette player", family="bedroom",
             reason="A grey radio-cassette on the floor with its top panel, speaker front and carrying handle.")
    out.cell(ROOM_CASE, *I.case_base(ROOM_CASE, rows=(0, 13), shelf=((3, 13), (9, 15), (1, 6)), wood=(GREY, GREY_D)),
             label="bedroom bookcase lower half", family="bookcase",
             reason="Grey bookcase base: recessed shelf over the drawer cabinet.")
    out.cell(ROOM_CASE_TOP, *I.case_top(ROOM_CASE_TOP, front_rows=(6, 16), top_rows=(1, 6), wood=(GREY, GREY_D),
                                        shelf=((3, 13), (1, 7), (9, 15))),
             label="bedroom bookcase upper half (stacked)", family="bookcase",
             reason="Stacked on its base: the upper shelf and the cream top.")
    out.entity("crystal_bedroom_bookcase", [[ROOM_CASE_TOP], [ROOM_CASE]], "Bedroom bookcase (stack)", kind="stack (model as one)")
    out.entity("crystal_house_table_4x4", [[K("658-659-662-663"), K("659-659-663-663"), K("659-659-663-663"), K("659-664-663-665")],
                                           [K("662-663-662-663"), *CENTRE[0], K("663-665-663-665")],
                                           [K("662-663-662-663"), *CENTRE[1], K("663-665-663-665")],
                                           [K("691-663-673-692"), K("663-663-692-692"), K("663-663-692-692"), K("663-693-692-674")]],
               "Fan Club / Trainer House 4x4 table with the potted plant")
    out.entity("crystal_house_table_2x2", [[K("658-659-662-663"), K("659-664-663-665")], [K("691-663-673-692"), K("663-693-692-674")]],
               "Bill's-house 2x2 table")
    out.entity("crystal_trainer_small_table", [[TRAINER_NW, K("659-664-663-665")], [TRAINER_SW, K("663-693-692-674")]],
               "Trainer House small table with its box")
    out.write(owned=OWNED)


if __name__ == "__main__":
    main()
