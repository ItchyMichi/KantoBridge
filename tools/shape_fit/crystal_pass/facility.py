"""Crystal checkpoint 15: TILESET_FACILITY_1 (the Power Plant, Mr. Pokemon's house, Silph Co. 1F, the Ruins of Alph
research center, the Trainer House basement, the Goldenrod flower shop).

Source reading (study/groups/TILESET_FACILITY_1; composites _fac_*):

- Tables of the house tables' tiles: the shared `interiors.table_piece` (a 1-block top showing the cell's drawing, legs at
  drawn outer corners, the apron on solid south rows). The one-row desks (their front row is floor) carry legs at both
  ends, as the flower shop's table (house module); so does the north-west corner 667-659-662-663 that the flower shop
  shares. The desks' devices (a console, a gold set, a document) stand on their tops.
- The Power Plant's counter (counter height, its slatted front), the long north-south counter strip with its wall end
  and its machine end, the generators (grey dial cabinets: the top stacked on the base), the transformer bank (full
  machine blocks: vented tops on the top rows, dial faces on the face rows), the blue sofas, chairs.
- Bookcases (two shelves, or a shelf over drawers; the top stacked on the base; alsoOn adds the drawer base where the top
  stood in place), the research center's terminal (stack), potted plants in gold urns (stack), a stairway warp (kept: its WARP_MODELS.md model).
"""
from __future__ import annotations

import numpy as np

from tools.shape_fit.crystal_pass import furniture as F
from tools.shape_fit.crystal_pass import interiors as I
from tools.shape_fit.crystal_pass.common import K, Pass
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, front_rect, top_rect

S = (16, 16, 16)
DARK, CREAM = I.DARK, I.CREAM
GOLD, GOLD_D, GOLD_L = (173, 140, 58), (132, 107, 25), (214, 197, 140)
GREY, GREY_D, GREY_P = (156, 156, 156), (107, 107, 107), (173, 173, 173)
BLUE, BLUE_L, BLUE_P, BLUE_M = (74, 74, 255), (123, 132, 255), (140, 156, 255), (115, 132, 255)
G, G_D, G_L = (123, 165, 8), (74, 107, 0), (148, 197, 74)
TOPDRAW = top_rect((0, 0, 16, 16), (0, 16), (0, 16))
FULL = front_rect((0, 0, 16, 16), (0, 16), (0, 16))
APRON = (14, (8, 11))  # the top ends at its drawn front band (rows 8-10); the panel below is added
FAMILY = "TILESET_FACILITY_1"

TABLES = {  # key: (edges, legs, apron, label)
    K("667-659-662-663"): ("nw", "nw sw", None, "table north-west corner (legs at both ends: the flower shop's front row is floor)"),
    K("659-3748-663-665"): ("ne", "ne", None, "table north-east corner"),
    K("662-663-3788-3789"): ("w", "sw", APRON, "table south-west corner (solid apron)"),
    K("663-663-3789-3789"): ("", "", APRON, "table front edge (solid apron)"),
    K("663-665-3789-3790"): ("e", "se", APRON, "table south-east corner (solid apron)"),
    K("662-3786-3788-3789"): ("w", "sw", APRON, "table south-west corner with the document (solid apron)"),
    K("3787-665-3789-3790"): ("e", "se", APRON, "table south-east corner with the document (solid apron)"),
}
DESK_CONSOLE, DESK_GOLD = K("3767-1421-1424-1425"), K("1422-3768-1426-3769")
DESK_DOC_W, DESK_DOC_E = K("667-659-662-3786"), K("659-3748-3787-665")
COUNTER = {K("659-659-3804-3804"): "", K("659-3748-3804-3805"): "e", K("667-659-3817-3804"): "w"}
STRIP, STRIP_WALL, STRIP_END = K("662-665-662-665"), K("3746-3746-667-3748"), K("662-665-3751-3752")
GEN, GEN_TOP = K("3749-3750-3751-3752"), K("667-3748-3749-3750")
VENT_ROW = [K(k) for k in ("3795-3795-3797-3797", "3795-3796-3797-3798", "3795-3801-3797-3801", "3796-3795-3798-3797")]
FACE_ROW = [K(k) for k in ("3799-3749-3800-3751", "3802-3799-3803-3800", "3802-3801-3803-3801")]
SOFA_L, SOFA_R = K("3806-3807-3809-3810"), K("3807-3808-3810-3811")
CHAIR = K("3791-3792-3793-3794")
CASE_TOP, CASE_SHELVES, CASE_DRAWERS = K("667-3748-3763-3764"), K("3763-3764-3763-3764"), K("3763-3764-3765-3766")
TERM_TOP, TERM = K("3746-3746-3818-3819"), K("3820-3821-3822-3823")
CROWN, URN = K("3774-3775-3776-3777"), K("3778-3779-3780-3781")
STAIRS = K("3755-3756-3757-3758")
WOOD = (GOLD, GOLD_D)


def table(key, edges, legs, apron):
    """The shared table piece; on a front row, the solid tan front panel under the band reaching the floor."""
    occ, paint = I.table_piece(key, edges=edges, legs=legs, apron=apron)
    if not apron:
        return occ, paint
    c = Cell(key)
    b = Build(c)
    b.add(occ, top=TOPDRAW, front=I.LEG, side=I.LEG)
    x = (1 if "w" in edges else 0, 15 if "e" in edges else 16)
    b.add(box(S, x, (0, 4), (13, 14)), top=I.LEG, front=front_rect((x[0], 11, x[1], 15), x, (0, 4)), side=I.LEG)
    occ2, paint2 = b.done()
    paint2[occ] = paint[occ]
    return occ2, paint2


def desk(key, *, west, device=None, sheet=None):
    """A one-row desk (its front row is floor): the shared table top with legs at both ends, and its device or document."""
    occ, paint = I.table_piece(key, edges="nws" if west else "nes", legs="nw sw" if west else "ne se")
    c = Cell(key)
    b = Build(c)
    b.add(occ, top=TOPDRAW, front=I.LEG, side=I.LEG)
    if device:
        x, z, h = device
        b.add(box(S, x, (8, 8 + h), z), top=top_rect((x[0], z[0], x[1], z[1]), x, z), front=front_rect((x[0], z[1] - 4, x[1], z[1]), x, (8, 8 + h)),
              side=[GOLD_D, DARK, GOLD, GOLD_L])
    if sheet:
        x, z = sheet
        b.add(box(S, x, (8, 9), z), top=top_rect((x[0], z[0], x[1], z[1]), x, z), front=[GOLD_D, DARK], side=[GOLD_D, DARK])
    occ2, _ = b.done()
    # keep the table_piece paint for its own voxels (legs and top), the new parts painted by the Build
    _, paint2 = b.done()
    paint2[occ] = paint[occ]
    return occ2, paint2


def counter(key, end):
    """The Power Plant's counter at counter height: its tan top (drawn rows 0-7) and the slatted front (rows 8-15)."""
    c = Cell(key)
    b = Build(c)
    x = (1 if end == "w" else 0, 15 if end == "e" else 16)
    b.add(box(S, x, (0, 8), (0, 14)), top=top_rect((0, 0, 16, 8), (0, 16), (0, 14)), front=front_rect((0, 8, 16, 16), (0, 16), (0, 8)),
          side=[GOLD, GOLD_D, DARK])
    return b.done()


def strip(key, kind):
    """The long north-south counter strip at counter height (its drawn top); its north end under the wall (the wall
    closes the north half) and its south end with a small grey control box."""
    c = Cell(key)
    b = Build(c)
    side = [GOLD, GOLD_D, DARK, GOLD_L]
    if kind == "strip":
        b.add(box(S, (0, 16), (0, 8), (0, 16)), top=TOPDRAW, front=side, side=side)
    elif kind == "wall":
        wall = [G, G_D, DARK]
        b.add(box(S, (0, 16), (0, 16), (0, 8)), top=wall, front=front_rect((0, 0, 16, 8), (0, 16), (0, 16)), side=wall)
        b.add(box(S, (0, 16), (0, 8), (8, 16)), top=top_rect((0, 8, 16, 16), (0, 16), (8, 16)), front=side, side=side)
    else:
        b.add(box(S, (0, 16), (0, 8), (0, 8)), top=top_rect((0, 0, 16, 8), (0, 16), (0, 8)), front=side, side=side)
        b.add(box(S, (1, 15), (0, 10), (8, 16)), top=[GREY, GREY_D], front=front_rect((0, 8, 16, 16), (1, 15), (0, 10)), side=[GREY, GREY_D, DARK])
    return b.done()


def machine(key, *, top_drawn):
    """A full machine block: the transformer bank's vented top rows (drawn top, face below it) or its dial faces and the
    generators' bodies (the drawn face, a grey top)."""
    c = Cell(key)
    b = Build(c)
    grey = [GREY, GREY_D, DARK, GREY_P, CREAM]
    b.add(box(S, (0, 16), (0, 16), (0, 16)), top=TOPDRAW if top_drawn else grey, front=FULL, side=grey)
    return b.done()


def gen_top():
    """Stacked on the generator body: its upper part with the dials (drawn rows 8-15) and the tan top (rows 0-7)."""
    c = Cell(GEN_TOP)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 12), (1, 16)), top=top_rect((0, 0, 16, 8), (0, 16), (1, 16)), front=front_rect((0, 8, 16, 16), (0, 16), (0, 12)),
          side=[GREY, GREY_D, DARK, GOLD])
    return b.done()


def sofa(key, left):
    """A blue sofa: the seat (its front drawn rows 7-14), the backrest along the north (its top rows 0-2, face 2-6) and
    the armrest at the outer end."""
    c = Cell(key)
    b = Build(c)
    blue = [BLUE_P, BLUE_M, BLUE, DARK, CREAM]
    b.add(box(S, (0, 16), (0, 7), (3, 15)), top=top_rect((0, 4, 16, 7), (0, 16), (3, 15)), front=front_rect((0, 7, 16, 15), (0, 16), (0, 7)), side=blue)
    b.add(box(S, (0, 16), (7, 13), (2, 5)), top=top_rect((0, 0, 16, 2), (0, 16), (2, 5)), front=front_rect((0, 2, 16, 6), (0, 16), (7, 13)), side=blue)
    ax = (0, 3) if left else (13, 16)
    b.add(box(S, ax, (7, 10), (5, 15)), top=blue, front=blue, side=blue)
    return b.done()


def chair():
    c = Cell(CHAIR)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 5), (2, 14)), top=top_rect((1, 1, 15, 9), (1, 15), (2, 14)), front=front_rect((1, 9, 15, 15), (1, 15), (0, 5)),
          side=[GOLD, GOLD_D, DARK])
    return b.done()


def case_two_shelves():
    """The bookcase's base with two recessed shelves of books (drawn rows 1-7 and 9-15)."""
    c = Cell(CASE_SHELVES)
    b = Build(c)
    side = [GOLD, GOLD_D, DARK]
    b.add(box(S, (1, 15), (0, 16), (6, 15)), top=[GOLD], front=front_rect((1, 0, 15, 16), (1, 15), (0, 16)), side=side)
    for sy, sr in (((9, 15), (1, 7)), ((1, 7), (9, 15))):
        b.cut(box(S, (2, 14), sy, (12, 15)))
        b.add(box(S, (2, 14), sy, (12, 13)), top=[GOLD_D], front=front_rect((2, sr[0], 14, sr[1]), (2, 14), sy), side=side)
    return b.done()


def term_top():
    c = Cell(TERM_TOP)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 8), (3, 14)), top=[GREY, GREY_D], front=front_rect((1, 8, 15, 16), (1, 15), (0, 8)), side=[GREY, GREY_D, DARK])
    return b.done()


def crown():
    c = Cell(CROWN)
    b = Build(c)
    b.add(F.ellipsoid(S, (8, 7.5, 8), (7, 7.5, 6.5)), top=top_rect((0, 0, 16, 9), (1, 15), (1.5, 14.5)),
          front=front_rect((0, 0, 16, 16), (1, 15), (0, 15)), side=[G, G_D, G_L])
    return b.done()


def urn():
    c = Cell(URN)
    b = Build(c)
    b.add(F.cylinder(S, (8, 8), 7.2, (0, 16)), top=[GOLD, GOLD_D], front=front_rect((0, 0, 16, 16), (0.8, 15.2), (0, 16)), side=[GOLD_L, GOLD, GOLD_D])
    return b.done()


def plate(key):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 1), (0, 16)), top=TOPDRAW, front=[DARK, CREAM], side=[DARK, CREAM])
    return b.done()


def main():
    out = Pass("facility")
    for key, (edges, legs, apron, label) in TABLES.items():
        out.cell(key, *table(key, edges, legs, apron), label=label, family="fac-tables",
                 reason="The shared table system: a 1-block top showing the cell's drawing, legs only at drawn outer corners"
                        + (", the top ending at its drawn front band with the solid front panel below it (drawn rows 11-14)"
                           if apron else "") + ".")
    out.cell(DESK_CONSOLE, *desk(DESK_CONSOLE, west=True, device=((2, 16), (1, 14), 5)), label="desk with a console", family="fac-tables",
             reason="A one-row desk (legs at both ends) with the drawn console standing on it.")
    out.cell(DESK_GOLD, *desk(DESK_GOLD, west=False, device=((0, 14), (1, 14), 6)), label="desk with a gold set", family="fac-tables",
             reason="A one-row desk (legs at both ends) with the drawn gold set standing on it.")
    out.cell(DESK_DOC_W, *desk(DESK_DOC_W, west=True, sheet=((8, 16), (8, 16))), label="desk with a document (west)", family="fac-tables",
             reason="A one-row desk (legs at both ends) with the document lying on it.")
    out.cell(DESK_DOC_E, *desk(DESK_DOC_E, west=False), label="desk with a document (east)", family="fac-tables",
             reason="A one-row desk (legs at both ends), the document's edge drawn on its top.")
    out.entity("crystal_fac_table", [[K("667-659-662-663"), K("659-659-663-663"), K("659-3748-663-665")],
                                     [K("662-663-3788-3789"), K("663-663-3789-3789"), K("663-665-3789-3790")]], "Power Plant table")
    out.entity("crystal_fac_desks", [[DESK_CONSOLE, DESK_GOLD, DESK_DOC_W, DESK_DOC_E]], "Power Plant desks")
    for key, end in COUNTER.items():
        out.cell(key, *counter(key, end), label="Power Plant counter", family="fac-plant",
                 reason="The counter at counter height (Tier A draws the counter cells beside it), its slatted front.")
    out.cell(STRIP, *strip(STRIP, "strip"), label="counter strip (north-south)", family="fac-plant",
             reason="The long north-south counter strip at counter height, its drawn top (38 placements).")
    out.cell(STRIP_WALL, *strip(STRIP_WALL, "wall"), label="counter strip under the wall", family="fac-plant",
             reason="The wall closes the north half; the strip's north end one block in front of it.")
    out.cell(STRIP_END, *strip(STRIP_END, "end"), label="counter strip end with a control box", family="fac-plant",
             reason="The strip's south end with the small grey control box in front of it.")
    out.cell(GEN, *machine(GEN, top_drawn=False), label="generator body / dial unit", family="fac-plant",
             reason="A full machine block, its dials on the face (the generators' body and a unit of the transformer bank).")
    out.cell(GEN_TOP, *gen_top(), label="generator top (stacked)", family="fac-plant",
             reason="Stacked on the body: the generator's upper dials and its tan top.")
    out.entity("crystal_fac_generator", [[GEN_TOP], [GEN]], "Power Plant generator (stack)", kind="stack (model as one)")
    for key in VENT_ROW:
        out.cell(key, *machine(key, top_drawn=True), label="transformer bank (vented top row)", family="fac-plant",
                 reason="A full machine block of the transformer bank, its vents drawn on top.")
    for key in FACE_ROW:
        out.cell(key, *machine(key, top_drawn=False), label="transformer bank (face row)", family="fac-plant",
                 reason="A full machine block of the transformer bank, its drawn face on the south.")
    out.entity("crystal_fac_transformer", [[VENT_ROW[0], VENT_ROW[3], VENT_ROW[1], VENT_ROW[2]], [GEN, FACE_ROW[0], FACE_ROW[1], FACE_ROW[2]]],
               "Power Plant transformer bank (part)")
    out.cell(SOFA_L, *sofa(SOFA_L, True), label="blue sofa (left)", family="fac-furniture",
             reason="The sofa's seat, backrest along the north and the armrest at its outer end.")
    out.cell(SOFA_R, *sofa(SOFA_R, False), label="blue sofa (right)", family="fac-furniture", reason="As the left half.")
    out.entity("crystal_fac_sofa", [[SOFA_L, SOFA_R]], "Blue sofa")
    out.cell(CHAIR, *chair(), label="chair", family="fac-furniture", reason="A tan square chair five voxels high (walkable).")
    out.cell(CASE_SHELVES, *case_two_shelves(), label="bookcase base (two shelves)", family="fac-furniture",
             reason="The bookcase's lower half with two recessed shelves of books.")
    out.cell(CASE_DRAWERS, *I.case_base(CASE_DRAWERS, rows=(0, 15), shelf=((2, 14), (9, 15), (1, 7)), wood=WOOD), label="bookcase base (drawers)",
             family="fac-furniture", reason="The bookcase's lower half: the shelf recessed with its books, the drawers below.")
    out.cell(CASE_TOP, *I.case_top(CASE_TOP, front_rows=(8, 16), top_rows=(1, 8), height=12, shelf=((2, 14), (3, 10), (9, 15)), wood=WOOD),
             label="bookcase top (stacked)", family="fac-furniture",
             reason="Stacked on its base: the upper shelf, recessed, and the tan top. alsoOn adds the drawer base (amendment).")
    out.also_on(CASE_TOP, [CASE_DRAWERS], "MR_POKEMONS_HOUSE and POWER_PLANT (0,0): the bookcase top over the drawer base "
                                          "3763-3764-3765-3766 stood in place (3 of its 6 placements).")
    out.entity("crystal_fac_bookcase", [[CASE_TOP], [CASE_DRAWERS]], "Bookcase over drawers (alsoOn stack)", kind="stack (model as one)")
    out.cell(TERM, *machine(TERM, top_drawn=False), label="research terminal", family="fac-furniture",
             reason="The terminal's body, a full block with its drawn screen and keys.")
    out.cell(TERM_TOP, *term_top(), label="research terminal top (stacked)", family="fac-furniture",
             reason="Stacked on the body: the terminal's upper part.")
    out.cell(URN, *urn(), label="gold urn", family="fac-furniture", reason="The gold urn a full cell tall, its drawn decoration.")
    out.cell(CROWN, *crown(), label="leafy crown (stacked)", family="fac-furniture", reason="Stacked on the urn: the leafy crown.")
    out.entity("crystal_fac_plant", [[CROWN], [URN]], "Potted plant in a gold urn (stack)", kind="stack (model as one)")
    out.keep_warp(STAIRS, label="stairway (warp)", family="fac-furniture")
    out.write(families=[FAMILY])


if __name__ == "__main__":
    main()
