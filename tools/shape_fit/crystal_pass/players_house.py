"""Crystal reference family: TILESET_PLAYERS_HOUSE_1 (the player's, Elm's, Copycat's and Red's houses).

Source reading (study/groups/TILESET_PLAYERS_HOUSE_1: sprites, maps, collision). Gen 2 draws this
furniture in a 3/4 view: upper sprite rows are the top surface, lower rows the south face.

- Kitchen (PLAYERS_HOUSE_1F row 1): a stove-counter (burner top, control band, oven door), a sink
  counter (basin and tap over two cabinet doors) and a two-door fridge drawn full height.
- TV and cabinet pairs: the TV body over a cabinet (Copycat 2F, the doors; Players 1F, the open shelf
  with the video deck); Red's room has a CRT TV on the floor with a game console in front of it.
- Bookcases (Copycat 1F/2F, Elm's, Red's 1F): two-cell stacks (top `on` base, the links match their
  south neighbours in every map, so the runtime draws one tall case on the floor cell): cabinet doors
  at the bottom, two book shelves, the top surface.
- The low chest of drawers (Players 1F (5,1)): top surface rows 0-7, two drawer rows below.
- Tables: top cells 1926/659/662/663/665 quarters with their dark border on the outer edges; the
  solid apron cells 6/7 (Copycat 2F row 4) show the front edge and legs. The walkable apron cells
  1938/1939-1906-1906 sit on WALK cells everywhere and carry no walk flag: never drawn, retained.
- Round stools (seat flag, WALK): a round seat on four legs.
- Desk with PC (Players 2F, Elm's, Red's 2F): desk with the keyboard on top and the monitor's lower
  half (base, 700-1949-1950-1951), the monitor's screen in the stacked top (1905-1905-1947-1948; its
  link matches everywhere, so it stands on the desk and its wall-art rows are not drawn).
- Red's bed (REDS_HOUSE_2F (0,6)/(0,7)): head and foot of one bed lying north-south. Its head cell's
  `on` link stacks the pillow end four blocks high on the foot: a false vertical stack (the Blue
  pass corrected the same mistake for its beds). Amended: the link is removed (source_amendments.json
  "stacks"), and the bed is one 1x2 object on the floor.
- Retained: the TV top in the wall row (1905-1905-1907-1908: stacked on the TV in Players 2F but drawn
  in place as wall art in five other maps, so one model cannot suit both), the warp stairs (122,
  drawn by the planner's stair handling) and the two walkable apron cells.
"""
from __future__ import annotations

import numpy as np

from tools.shape_fit.crystal_pass import furniture as F
from tools.shape_fit.crystal_pass.common import K, Pass, slice_object  # noqa: F401
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, cylinder, front_rect, top_rect

DARK = (58, 58, 58)
GREY, GREY_D = (156, 156, 156), (107, 107, 107)
CREAM = (247, 230, 214)
WOOD, WOOD_D, WOOD_L = (173, 140, 58), (132, 107, 25), (214, 197, 140)
BLUE, BLUE_D = (123, 132, 255), (74, 74, 255)
S = (16, 16, 16)
LEG = [WOOD_D, DARK]  # table edges and legs: dark wood, else the outline colour

STOVE, SINK, FRIDGE = K("1918-1919-1922-1923"), K("1920-1921-1911-1912"), K("1943-1944-1945-1946")
TV_DOORS, TV_SHELF, TV_FLOOR, CONSOLE = (K("1909-1910-1911-1912"), K("1909-1910-1954-1955"),
                                         K("1907-1908-1909-1910"), K("1958-1959-1960-1961"))
TV_TOP = K("1905-1905-1907-1908")
CHEST = K("1952-1953-1956-1957")
CASE_TOP_A, CASE_BASE_A = K("1952-1953-1966-1967"), K("1968-1969-1911-1912")
CASE_TOP_B, CASE_BASE_B = K("1952-1953-1968-1969"), K("1954-1955-1956-1957")
STOOL = K("1924-1925-1927-1928")
TABLE = {  # key: (north edge, south edge, west edge, east edge)
    K("1926-659-662-663"): (1, 0, 1, 0), K("659-1929-663-665"): (1, 0, 0, 1),
    K("662-663-1915-1916"): (0, 1, 1, 0), K("663-665-1916-1917"): (0, 1, 0, 1),
    K("1926-659-1915-1916"): (1, 1, 1, 0), K("659-1929-1916-1917"): (1, 1, 0, 1),
}
APRON = {K("1915-1916-1938-1939"): "west", K("1916-1917-1939-1940"): "east"}
APRON_WALK = (K("1938-1939-1906-1906"), K("1939-1940-1906-1906"))
STAIRS = K("1930-1931-1932-1933")
DESK, MONITOR = K("700-1949-1950-1951"), K("1905-1905-1947-1948")
BED_HEAD, BED_FOOT = K("1970-1971-1973-1974"), K("135-1975-1976-1977")
OWNED = [STOVE, SINK, FRIDGE, TV_DOORS, TV_SHELF, TV_FLOOR, CONSOLE, TV_TOP, CHEST, CASE_TOP_A, CASE_BASE_A,
         CASE_TOP_B, CASE_BASE_B, STOOL, *TABLE, *APRON, *APRON_WALK, STAIRS, DESK, MONITOR, BED_HEAD, BED_FOOT]


def counter(cell, *, top_rows, front_rows, side, h=11, z0=2, z1=15):
    """A kitchen counter: its top from the upper rows, its south face from the lower rows."""
    b = Build(cell)
    b.add(box(S, (0, 16), (0, h), (z0, z1)), top=top_rect((0, top_rows[0], 16, top_rows[1]), (0, 16), (z0, z1)),
          front=front_rect((0, front_rows[0], 16, front_rows[1]), (0, 16), (0, h)), side=side)
    return b


def stove():
    c = Cell(STOVE)
    b = counter(c, top_rows=(1, 6), front_rows=(6, 15), side=[GREY, GREY_D])
    for x in (3.5, 11.5):  # the two burner rings, raised a voxel
        ring = F.cylinder(S, (x, 6), 2.6, (11, 12)) & ~F.cylinder(S, (x, 6), 1.2, (11, 12))
        b.add(ring, top=[DARK], front=[DARK], side=[DARK])
    for x in (3, 5, 11, 13):  # knobs on the control band
        b.add(box(S, (x, x + 1), (8, 9), (15, 16)), top=[GREY_D], front=[GREY_D], side=[GREY_D])
    b.add(box(S, (5, 11), (5, 6), (15, 16)), top=[GREY], front=[GREY], side=[GREY])  # oven door handle
    return b.done()


def sink():
    c = Cell(SINK)
    b = counter(c, top_rows=(1, 8), front_rows=(8, 15), side=[WOOD, WOOD_D])
    b.cut(box(S, (2, 14), (9, 11), (4, 10)))  # the basin, two voxels deep
    b.add(box(S, (2, 14), (8, 9), (4, 10)), top=[GREY_D], front=[GREY], side=[GREY])
    b.add(box(S, (7, 9), (11, 14), (2, 4)), top=[CREAM], front=[CREAM], side=[GREY])  # the tap
    b.add(box(S, (7, 9), (13, 14), (4, 6)), top=[CREAM], front=[CREAM], side=[GREY])
    return b.done()


def fridge():
    c = Cell(FRIDGE)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 16), (3, 15)), top=[CREAM], front=front_rect((0, 0, 16, 16), (1, 15), (0, 16)),
          side=[CREAM, GREY], back=[CREAM])
    for y in ((12, 15), (4, 8)):  # freezer and fridge door handles
        b.add(box(S, (3, 5), y, (15, 16)), top=[DARK], front=[DARK], side=[DARK])
    return b.done()


def tv_on_cabinet(key, cabinet_rows):
    """The TV's lower body standing on its cabinet; the TV's top is drawn in the wall-row cell."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 7), (3, 15)), top=[WOOD], front=front_rect((1, cabinet_rows[0], 15, cabinet_rows[1]), (1, 15), (0, 7)),
          side=[WOOD, WOOD_D])
    b.add(box(S, (3, 13), (7, 16), (5, 13)), top=[BLUE], front=front_rect((3, 0, 13, 6), (3, 13), (7, 16)), side=[BLUE_D, BLUE])
    return b.done()


def tv_floor():
    c = Cell(TV_FLOOR)
    b = Build(c)
    b.add(box(S, (2, 14), (0, 12), (3, 14)), top=top_rect((2, 1, 14, 5), (2, 14), (3, 14)),
          front=front_rect((2, 6, 14, 15), (2, 14), (0, 12)), side=[BLUE, BLUE_D])
    return b.done()


def console():
    """Red's game console (sprite rows 0-9, the dark body with its lid and vents), one controller
    (rows 10-14, right of centre) and the cable that runs from it round the left of the body.
    The tan and ochre stripes around them are the room's floor: the parts occupy only the drawn
    silhouette, seen from above (identity top projection)."""
    c = Cell(CONSOLE)
    sil = F.footprint(S, F.silhouette(c, [WOOD_L, WOOD]))
    top = top_rect((0, 0, 16, 16), (0, 16), (0, 16))
    b = Build(c)
    b.add(box(S, (0, 16), (0, 4), (0, 10)) & sil, top=top, front=[DARK], side=[DARK])
    b.add(box(S, (6, 16), (0, 2), (10, 15)) & sil, top=top, front=[DARK], side=[DARK])
    dark = F.footprint(S, (c.pixels == np.asarray(DARK)).all(axis=2))
    b.add(box(S, (0, 7), (0, 1), (9, 14)) & sil & dark, top=[DARK], front=[DARK], side=[DARK])  # the cable only
    return b.done()


def chest():
    c = Cell(CHEST)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 9), (2, 15)), top=top_rect((1, 1, 15, 8), (1, 15), (2, 15)),
          front=front_rect((1, 8, 15, 16), (1, 15), (0, 9)), side=[WOOD, WOOD_D])
    for y in (6, 2):  # drawer pulls
        b.add(box(S, (7, 9), (y, y + 1), (15, 16)), top=[WOOD_D], front=[WOOD_D], side=[WOOD_D])
    return b.done()


def bookcase(base_key, top_key):
    """One tall bookcase: base cell 16 voxels (cabinet doors, lower shelf), top cell 12 (upper shelf, top)."""
    out = {}
    cb, ct = Cell(base_key), Cell(top_key)
    b = Build(cb)
    b.add(box(S, (1, 15), (0, 16), (6, 15)), top=[WOOD], front=front_rect((1, 0, 15, 15), (1, 15), (0, 16)), side=[WOOD, WOOD_D])
    b.cut(box(S, (3, 13), (10, 15), (12, 15)))  # open shelf recess; its back shows the books
    b.add(box(S, (3, 13), (10, 15), (12, 13)), top=[WOOD_D], front=front_rect((3, 2, 13, 7), (3, 13), (10, 15)), side=[WOOD_D])
    out[base_key] = b.done()
    t = Build(ct)
    t.add(box(S, (1, 15), (0, 12), (6, 15)), top=top_rect((1, 1, 15, 6), (1, 15), (6, 15)),
          front=front_rect((1, 6, 15, 16), (1, 15), (0, 12)), side=[WOOD, WOOD_D])
    t.cut(box(S, (3, 13), (1, 7), (12, 15)))
    t.add(box(S, (3, 13), (1, 7), (12, 13)), top=[WOOD_D], front=front_rect((3, 9, 13, 15), (3, 13), (1, 7)), side=[WOOD_D])
    out[top_key] = t.done()
    return out


def stool():
    c = Cell(STOOL)
    b = Build(c)
    b.add(F.cylinder(S, (8, 7), 5.5, (2, 4)), top=top_rect((2, 1, 14, 13), (2.5, 13.5), (1.5, 12.5)), front=[WOOD, WOOD_D], side=[WOOD])
    for x, z in ((4, 3), (11, 3), (4, 10), (11, 10)):
        b.add(box(S, (x, x + 1), (0, 2), (z, z + 1)), top=[WOOD_D], front=[WOOD_D], side=[WOOD_D])
    return b.done()


def table_cell(key, edges):
    """One cell of a table 1 block high: a 2-voxel top over the cell, legs at its outer corners."""
    n, s, w, e = edges
    c = Cell(key)
    b = Build(c)
    x = (1 if w else 0, 15 if e else 16)
    z = (1 if n else 0, 15 if s else 16)
    b.add(box(S, x, (6, 8), z), top=top_rect((0, 0, 16, 16), (0, 16), (0, 16)), front=LEG, side=LEG)
    for lx in ([x[0] + 1] if w else []) + ([x[1] - 3] if e else []):
        for lz in ([z[0] + 1] if n else []) + ([z[1] - 3] if s else []):
            b.add(box(S, (lx, lx + 2), (0, 6), (lz, lz + 2)), top=LEG, front=LEG, side=LEG)
    return b.done()


def apron_cell(key, side):
    """The solid south row of a table: the top's front edge (rows 0-2) and a front leg."""
    c = Cell(key)
    b = Build(c)
    x = (1, 16) if side == "west" else (0, 15)
    b.add(box(S, x, (6, 8), (0, 3)), top=top_rect((0, 0, 16, 3), (0, 16), (0, 3)), front=LEG, side=LEG)
    lx = 2 if side == "west" else 12
    b.add(box(S, (lx, lx + 2), (0, 6), (0, 2)), top=LEG, front=LEG, side=LEG)
    return b.done()


def desk_and_monitor():
    cd, cm = Cell(DESK), Cell(MONITOR)
    d = Build(cd)
    d.add(box(S, (0, 16), (0, 9), (2, 15)), top=top_rect((0, 6, 16, 13), (0, 16), (2, 15)),
          front=front_rect((0, 12, 16, 15), (0, 16), (0, 9)), side=[GREY_D, DARK])
    d.add(box(S, (3, 13), (9, 16), (3, 9)), top=[GREY], front=front_rect((3, 0, 13, 6), (3, 13), (9, 16)), side=[GREY])
    m = Build(cm)
    m.add(box(S, (3, 13), (0, 6), (3, 9)), top=[GREY], front=front_rect((2, 9, 14, 15), (3, 13), (0, 6)), side=[GREY, GREY_D])
    return {DESK: d.done(), MONITOR: m.done()}


def bed():
    """Red's bed lying north-south across its head (north) and foot cells, sliced at z=16."""
    occ = np.zeros((16, 32, 16), bool)
    mat = np.zeros(occ.shape, np.uint8)  # 1 frame, 2 mattress/sheets, 3 pillow
    shape = occ.shape

    def put(mask, m):
        occ[mask] = True
        mat[mask] = m
    put(box(shape, (1, 15), (0, 12), (1, 3)), 1)        # headboard
    put(box(shape, (1, 15), (0, 9), (29, 31)), 1)       # footboard
    put(box(shape, (1, 15), (2, 4), (3, 29)), 1)        # side rails / base
    put(box(shape, (2, 14), (4, 7), (3, 29)), 2)        # mattress and sheet
    put(box(shape, (4, 12), (7, 9), (4, 9)), 3)         # pillow
    for x in (1, 13):
        for z in (3, 27):
            put(box(shape, (x, x + 2), (0, 2), (z, z + 2)), 1)
    materials = {1: [GREY_D, GREY, DARK], 2: [CREAM], 3: [CREAM]}
    up = F.exposed(occ)["up"]

    def hint(y, z, x):
        return x + .5, (z % 16) + .5
    paint_of = {}
    for key, z0 in ((BED_HEAD, 0), (BED_FOOT, 16)):
        c = Cell(key)
        part, mats, upm = occ[:, z0:z0 + 16], mat[:, z0:z0 + 16], up[:, z0:z0 + 16]
        p = np.zeros(part.shape, np.uint8)
        for y, z, x in np.argwhere(part):
            m = int(mats[y, z, x])
            if upm[y, z, x] and m in (2, 3):
                p[y, z, x] = z * 16 + x  # sheets and pillow: this cell's drawing seen from above
            else:
                p[y, z, x] = c.pick(materials[m], x + .5, z + .5)
        paint_of[key] = (part, p)
    return paint_of


def main():
    out = Pass("players-house")
    fam = "players-house"
    out.cell(STOVE, *stove(), label="kitchen stove counter", family=fam + "-kitchen",
             reason="Counter with the burner top laid flat, raised burner rings, knobs and oven handle on the control/oven face; "
                    "grey sides instead of the smeared outline.")
    out.cell(SINK, *sink(), label="kitchen sink counter", family=fam + "-kitchen",
             reason="Counter with a real two-voxel basin and a tap; the cabinet doors on the south face; wood sides.")
    out.cell(FRIDGE, *fridge(), label="fridge", family=fam + "-kitchen",
             reason="Upright two-door fridge drawn full height on its south face, door handles in relief, cream body.")
    out.cell(TV_DOORS, *tv_on_cabinet(TV_DOORS, (8, 15)), label="TV on a door cabinet (lower half)", family=fam + "-tv",
             reason="Cabinet with its doors on the front and the TV body standing on it, screen facing south.")
    out.cell(TV_SHELF, *tv_on_cabinet(TV_SHELF, (7, 15)), label="TV on an open shelf with the video deck (lower half)",
             family=fam + "-tv", reason="Open-shelf cabinet with the deck on its front and the TV body standing on it.")
    out.cell(TV_FLOOR, *tv_floor(), label="CRT TV on the floor (Red's room)", family=fam + "-tv",
             reason="A CRT box: its top from the upper rows, the screen and knob on the south face, blue sides.")
    out.cell(CONSOLE, *console(), label="game console with its controller (Red's room)", family=fam + "-tv",
             reason="Low console body, one controller in front of it and its cable; the floor stripes around them stay floor.")
    out.cell_entry(TV_TOP, dict(out.table["cells"][TV_TOP]), label="TV top in the wall row", family=fam + "-tv",
                   reason="Drawn in place as wall art over the TV in five maps; its link stacked it onto the bedroom TV only "
                          "(a two-block wall block standing on the TV). The link is removed (amendment) so the bedroom "
                          "matches the other five; the wall-art model itself is unchanged.")
    out.drop_stack(TV_TOP, "Placed in the wall row over a TV in COPYCATS_HOUSE_1F/2F, ELMS_HOUSE, PLAYERS_HOUSE_1F and "
                           "REDS_HOUSE_1F without stacking (different bases); only PLAYERS_HOUSE_2F (4,0) matched its link.")
    out.cell(CHEST, *chest(), label="low chest of drawers", family=fam + "-storage",
             reason="Chest one block high: its top from rows 0-7, the two drawer rows on the front with pulls in relief.")
    for base, top in ((CASE_BASE_A, CASE_TOP_A), (CASE_BASE_B, CASE_TOP_B)):
        parts = bookcase(base, top)
        out.cell(base, *parts[base], label="bookcase lower half (cabinet and shelf)", family=fam + "-bookcase",
                 reason="Base of a two-cell bookcase standing on the floor: cabinet doors below a recessed book shelf; "
                        "an 8-voxel-deep case instead of a full cube.")
        out.cell(top, *parts[top], label="bookcase upper half (shelf and top)", family=fam + "-bookcase",
                 reason="Top of the same bookcase (its link matches in every map): recessed upper shelf and the top surface; "
                        "12 voxels so the case stands 3.5 blocks.")
        out.entity(f"crystal_bookcase_{base[4:]}", [[top], [base]], "Players-house bookcase (stack)", kind="stack (model as one)")
    out.cell(STOOL, *stool(), label="round stool", family=fam + "-seating",
             reason="Round seat on four legs at the existing seat height instead of a filled block.")
    for key, edges in TABLE.items():
        out.cell(key, *table_cell(key, edges), label="table top section", family=fam + "-table",
                 reason="Table one block high: a two-voxel top showing this cell's drawing, legs at its outer corners.")
    for key, side in APRON.items():
        out.cell(key, *apron_cell(key, side), label=f"table front edge and {side} leg (solid apron)", family=fam + "-table",
                 reason="The table's front edge continues three voxels into this solid cell with its front leg; the rest is floor.")
    for key in APRON_WALK:
        out.keep(key, label="table front edge drawn on the walkable floor", family=fam + "-table",
                 reason="Every placement is a WALK cell (collision 0) and the entry has no walk flag, so the planner never draws it.")
    out.keep(STAIRS, label="staircase warp", family=fam + "-stairs",
             reason="Warp stairs (collision 122): drawn by the planner's stair handling, never by this entry.")
    dm = desk_and_monitor()
    out.cell(DESK, *dm[DESK], label="desk with keyboard and the monitor's lower half", family=fam + "-pc",
             reason="Desk one block high with the keyboard on its top and the CRT's lower body standing on it.")
    out.cell(MONITOR, *dm[MONITOR], label="PC monitor screen (stacked on the desk)", family=fam + "-pc",
             reason="Stacked on the desk in all three maps: only the monitor's upper body with its screen, continuing the "
                    "lower body below; the wall rows are not drawn on top of the desk.")
    out.entity("crystal_players_house_pc", [[MONITOR], [DESK]], "Desk and PC (stack)", kind="stack (model as one)")
    parts = bed()
    out.cell(BED_HEAD, *parts[BED_HEAD], label="bed head (pillow end)", family=fam + "-bed",
             reason="Corrected false stack: the pillow end lies north of the foot (amendment). Headboard, mattress, pillow.")
    out.drop_stack(BED_HEAD, "REDS_HOUSE_2F (0,6) head and (0,7) foot, both collision 7: one bed drawn top-down across two "
                             "rows, not a pillow end standing on the foot.")
    out.cell(BED_FOOT, *parts[BED_FOOT], label="bed foot", family=fam + "-bed",
             reason="Foot of the same bed: sheets on the mattress and the footboard.")
    out.entity("crystal_reds_bed", [[BED_HEAD], [BED_FOOT]], "Red's bed lying north-south (1x2 on the floor)")
    out.entity("crystal_players_house_table_2x2", [[K("1926-659-662-663"), K("659-1929-663-665")],
                                                   [K("662-663-1915-1916"), K("663-665-1916-1917")]],
               "Players 1F table, 2x2 top")
    out.entity("crystal_copycat_table_apron", [[K("1926-659-662-663"), K("659-1929-663-665")],
                                               [K("1915-1916-1938-1939"), K("1916-1917-1939-1940")]],
               "Copycat 2F table with its solid apron row")
    out.entity("crystal_kitchen_row", [[STOVE, SINK, FRIDGE]], "Players 1F kitchen: stove, sink, fridge")
    out.write(owned=OWNED)


if __name__ == "__main__":
    main()
