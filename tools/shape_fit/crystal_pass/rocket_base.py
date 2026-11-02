"""Crystal checkpoint 16: TILESET_FACILITY_2 (the Team Rocket base B2F and B3F).

The Rocket base's furniture is the facility tileset's (checkpoint 15) redrawn in its own palette (a warmer tan
(197,148,58)/(165,123,25), the pale green floor), so it takes the same designs, here with that palette (the facility
module hard-codes the Power Plant's colours and is not changed):

- Tables: the shared `interiors.table_piece` (legs at drawn outer corners, the apron on the front row with the solid
  front panel under the band); the one-row desks with legs at both ends and their console, gold set or document.
- Generators (stacks), bookcases (stacks; alsoOn adds the drawer base where the top stood in place), the transformer
  bank (full machine blocks: vents drawn on the top rows, dials on the face rows), potted plants in urns (stacks),
  chairs; the stairway warps are kept (their WARP_MODELS.md models).
"""
from __future__ import annotations

from tools.shape_fit.crystal_pass import furniture as F
from tools.shape_fit.crystal_pass import interiors as I
from tools.shape_fit.crystal_pass.common import K, Pass
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, front_rect, top_rect

S = (16, 16, 16)
DARK = I.DARK
WOOD, WOOD_D, PALE = (197, 148, 58), (165, 123, 25), (222, 255, 222)
GREY, GREY_D = (173, 173, 173), (107, 107, 107)
LEG = [WOOD_D, DARK, WOOD]
GREENS = [(99, 206, 8), (41, 115, 0), (181, 255, 82)]
TOPDRAW = top_rect((0, 0, 16, 16), (0, 16), (0, 16))
FULL = front_rect((0, 0, 16, 16), (0, 16), (0, 16))
APRON = (14, (8, 11))
FAMILY = "TILESET_FACILITY_2"

TABLES = {  # key: (edges, legs, apron, label)
    K("4932-4976-4977-3114"): ("nw", "nw", None, "table north-west corner"),
    K("4976-4976-3114-3114"): ("n", "", None, "table north edge"),
    K("4976-4933-3114-4978"): ("ne", "ne", None, "table north-east corner"),
    K("4977-3114-4981-4982"): ("w", "sw", APRON, "table south-west corner (solid apron)"),
    K("3114-3114-4982-4982"): ("", "", APRON, "table front edge (solid apron)"),
    K("3114-4978-4982-4983"): ("e", "se", APRON, "table south-east corner (solid apron)"),
}
DESK_CONSOLE, DESK_GOLD = K("4952-4953-4956-4957"), K("4954-4955-4958-4959")
DESK_DOC_W, DESK_DOC_E = K("4932-4976-4977-4979"), K("4976-4933-4980-4978")
GEN, GEN_TOP = K("4934-4935-4936-4937"), K("4932-4933-4934-4935")
CASE_TOP, CASE_SHELVES, CASE_DRAWERS = K("4932-4933-4948-4949"), K("4948-4949-4948-4949"), K("4948-4949-4950-4951")
VENT_ROW = [K(k) for k in ("4988-4988-4990-4990", "4988-4989-4990-4991", "4988-4994-4990-4994", "4989-4988-4991-4990")]
FACE_ROW = [K(k) for k in ("4935-4992-4937-4993", "4992-4934-4993-4936", "4995-4992-4996-4993", "4995-4994-4996-4994")]
CROWN, URN = K("4964-4965-4966-4967"), K("4968-4969-4970-4971")
CHAIR = K("4984-4985-4986-4987")
STAIRS = [K("4940-4941-4942-4943"), K("4944-4945-4946-4947")]


def table(key, edges, legs, apron):
    occ, paint = I.table_piece(key, edges=edges, legs=legs, apron=apron, side=LEG)
    if not apron:
        return occ, paint
    c = Cell(key)
    b = Build(c)
    b.add(occ, top=TOPDRAW, front=LEG, side=LEG)
    x = (1 if "w" in edges else 0, 15 if "e" in edges else 16)
    b.add(box(S, x, (0, 4), (13, 14)), top=LEG, front=front_rect((x[0], 11, x[1], 15), x, (0, 4)), side=LEG)
    occ2, paint2 = b.done()
    paint2[occ] = paint[occ]
    return occ2, paint2


def desk(key, *, west, device=None, sheet=None):
    occ, paint = I.table_piece(key, edges="nws" if west else "nes", legs="nw sw" if west else "ne se", side=LEG)
    c = Cell(key)
    b = Build(c)
    b.add(occ, top=TOPDRAW, front=LEG, side=LEG)
    if device:
        x, z, h = device
        b.add(box(S, x, (8, 8 + h), z), top=top_rect((x[0], z[0], x[1], z[1]), x, z), front=front_rect((x[0], z[1] - 4, x[1], z[1]), x, (8, 8 + h)),
              side=[WOOD_D, DARK, WOOD, PALE])
    if sheet:
        x, z = sheet
        b.add(box(S, x, (8, 9), z), top=top_rect((x[0], z[0], x[1], z[1]), x, z), front=[WOOD_D, DARK], side=[WOOD_D, DARK])
    occ2, paint2 = b.done()
    paint2[occ] = paint[occ]
    return occ2, paint2


def machine(key, *, top_drawn):
    c = Cell(key)
    b = Build(c)
    grey = [GREY, GREY_D, DARK, PALE, WOOD]
    b.add(box(S, (0, 16), (0, 16), (0, 16)), top=TOPDRAW if top_drawn else grey, front=FULL, side=grey)
    return b.done()


def gen_top():
    c = Cell(GEN_TOP)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 12), (1, 16)), top=top_rect((0, 0, 16, 8), (0, 16), (1, 16)), front=front_rect((0, 8, 16, 16), (0, 16), (0, 12)),
          side=[GREY, GREY_D, DARK, WOOD])
    return b.done()


def case_two_shelves():
    c = Cell(CASE_SHELVES)
    b = Build(c)
    side = [WOOD, WOOD_D, DARK]
    b.add(box(S, (1, 15), (0, 16), (6, 15)), top=[WOOD], front=front_rect((1, 0, 15, 16), (1, 15), (0, 16)), side=side)
    for sy, sr in (((9, 15), (1, 7)), ((1, 7), (9, 15))):
        b.cut(box(S, (2, 14), sy, (12, 15)))
        b.add(box(S, (2, 14), sy, (12, 13)), top=[WOOD_D], front=front_rect((2, sr[0], 14, sr[1]), (2, 14), sy), side=side)
    return b.done()


def crown():
    c = Cell(CROWN)
    b = Build(c)
    b.add(F.ellipsoid(S, (8, 7.5, 8), (7, 7.5, 6.5)), top=top_rect((0, 0, 16, 9), (1, 15), (1.5, 14.5)),
          front=front_rect((0, 0, 16, 16), (1, 15), (0, 15)), side=GREENS)
    return b.done()


def urn():
    c = Cell(URN)
    b = Build(c)
    # the drawing's outer columns are floor: project only the urn's own columns (2-13) round its face
    b.add(F.cylinder(S, (8, 8), 7.2, (0, 16)), top=[WOOD, WOOD_D], front=front_rect((2, 0, 14, 16), (0.8, 15.2), (0, 16)), side=[WOOD, WOOD_D, DARK])
    return b.done()


def chair():
    c = Cell(CHAIR)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 5), (2, 14)), top=top_rect((1, 1, 15, 9), (1, 15), (2, 14)), front=front_rect((1, 9, 15, 15), (1, 15), (0, 5)),
          side=[WOOD, WOOD_D, DARK])
    return b.done()


def plate(key):
    c = Cell(key)
    b = Build(c)
    own = [DARK, PALE]
    b.add(box(S, (0, 16), (0, 1), (0, 16)), top=TOPDRAW, front=own, side=own)
    return b.done()


def main():
    out = Pass("rocket-base")
    for key, (edges, legs, apron, label) in TABLES.items():
        out.cell(key, *table(key, edges, legs, apron), label=label, family="rb-tables",
                 reason="The shared table system in the Rocket base's palette: a 1-block top, legs at drawn outer corners"
                        + (", the top ending at its front band with the solid front panel below" if apron else "") + ".")
    out.entity("crystal_rb_table", [list(TABLES)[0:3], list(TABLES)[3:6]], "Rocket base table")
    out.cell(DESK_CONSOLE, *desk(DESK_CONSOLE, west=True, device=((2, 16), (1, 14), 5)), label="desk with a console", family="rb-tables",
             reason="A one-row desk (legs at both ends) with the drawn console standing on it.")
    out.cell(DESK_GOLD, *desk(DESK_GOLD, west=False, device=((0, 14), (1, 14), 6)), label="desk with a gold set", family="rb-tables",
             reason="A one-row desk (legs at both ends) with the drawn set standing on it.")
    out.cell(DESK_DOC_W, *desk(DESK_DOC_W, west=True, sheet=((8, 16), (8, 16))), label="desk with a document (west)", family="rb-tables",
             reason="A one-row desk (legs at both ends) with the document lying on it.")
    out.cell(DESK_DOC_E, *desk(DESK_DOC_E, west=False), label="desk with a document (east)", family="rb-tables",
             reason="A one-row desk (legs at both ends), the document's edge drawn on its top.")
    out.entity("crystal_rb_desks", [[DESK_CONSOLE, DESK_GOLD, DESK_DOC_W, DESK_DOC_E]], "Rocket base desks")
    out.cell(GEN, *machine(GEN, top_drawn=False), label="generator body / dial unit", family="rb-machines",
             reason="A full machine block, its dials on the face (the generators' body and a unit of the transformer bank).")
    out.cell(GEN_TOP, *gen_top(), label="generator top (stacked)", family="rb-machines",
             reason="Stacked on the body: the generator's upper dials and its tan top.")
    out.entity("crystal_rb_generator", [[GEN_TOP], [GEN]], "Rocket base generator (stack)", kind="stack (model as one)")
    for key in VENT_ROW:
        out.cell(key, *machine(key, top_drawn=True), label="transformer bank (vented top row)", family="rb-machines",
                 reason="A full machine block of the transformer bank, its vents drawn on top.")
    for key in FACE_ROW:
        out.cell(key, *machine(key, top_drawn=False), label="transformer bank (face row)", family="rb-machines",
                 reason="A full machine block of the transformer bank, its drawn face on the south.")
    out.entity("crystal_rb_transformer", [[VENT_ROW[0], VENT_ROW[1], VENT_ROW[3], VENT_ROW[2]], [GEN, FACE_ROW[1], FACE_ROW[0], FACE_ROW[3]]],
               "Rocket base transformer bank (part)")
    out.cell(CASE_SHELVES, *case_two_shelves(), label="bookcase base (two shelves)", family="rb-furniture",
             reason="The bookcase's lower half with two recessed shelves of books.")
    out.cell(CASE_DRAWERS, *I.case_base(CASE_DRAWERS, rows=(0, 15), shelf=((2, 14), (9, 15), (1, 7)), wood=(WOOD, WOOD_D)),
             label="bookcase base (drawers)", family="rb-furniture", reason="The shelf recessed with its books, the drawers below.")
    out.cell(CASE_TOP, *I.case_top(CASE_TOP, front_rows=(8, 16), top_rows=(1, 8), height=12, shelf=((2, 14), (3, 10), (9, 15)),
                                   wood=(WOOD, WOOD_D)),
             label="bookcase top (stacked)", family="rb-furniture",
             reason="Stacked on its base: the upper shelf, recessed, and the tan top. alsoOn adds the drawer base (amendment).")
    out.also_on(CASE_TOP, [CASE_DRAWERS], "TEAM_ROCKET_BASE_B3F: the bookcase top over the drawer base 4948-4949-4950-4951 stood "
                                          "in place (4 of its 8 placements), as the facility tileset's (checkpoint 15).")
    out.entity("crystal_rb_bookcase", [[CASE_TOP], [CASE_DRAWERS]], "Bookcase over drawers (alsoOn stack)", kind="stack (model as one)")
    out.cell(URN, *urn(), label="urn", family="rb-furniture", reason="The urn a full cell tall, its drawn decoration.")
    out.cell(CROWN, *crown(), label="leafy crown (stacked)", family="rb-furniture", reason="Stacked on the urn: the leafy crown.")
    out.entity("crystal_rb_plant", [[CROWN], [URN]], "Potted plant (stack)", kind="stack (model as one)")
    out.cell(CHAIR, *chair(), label="chair", family="rb-furniture", reason="A tan square chair five voxels high (walkable).")
    for key in STAIRS:
        out.keep_warp(key, label="stairway (warp)", family="rb-furniture")
    out.write(families=[FAMILY])


if __name__ == "__main__":
    main()
