"""Crystal checkpoint 32: Ecruteak's traditional buildings (and the Ruins of Alph's research centre, retained).

Source reading (the pixel maps of b008, b009, b010, b007, b016; Codex's accepted b010 and b088 and their QC recreation in
claude_qc_buildings.py):

- b008 and b009 share Codex's b010 drawing (rows 0-31 the hip-and-gable roof: tile courses, gable ends, stepped eave edges,
  the white beam and brown plate; rows 32-45 the timbered plaster wall with barred windows and the lattice wainscot): b008
  has three windows and no door, b009 is six cells wide with its lattice door in the middle. `machiya` is b010's design
  (claude_qc_buildings.house_spec) with its width, door and windows as parameters.
- b015 is a Johto facade (yellow-paned windows, no door this side) under a speckled traditional roof: building_kit.johto.
- b007, the Burned Tower: a wooden hall (its facade rows 48-63: the lattice door, a barred window) under a low ruined roof
  whose drawing (rows 8-40) carries the charred hole, rubble at both sides (columns 0-8 and 56-63).
- b016, the Bell Tower seen from Ecruteak: seven identical storey bands (rows 16k..16k+11 a wall with paired windows
  between lattice posts, rows 16k+12..16k+15 a balcony eave) over a ground floor with a lattice door (rows 112-127). Eight
  square storeys on the south of the footprint (Codex's b088 stands its storeys the same way), each with a proud balcony
  eave and its band's art, a low hip cap.
- Retained: Codex's accepted b010 and b088 (protected), and b105 (the research centre: its earlier parts spec, brick walls
  under a speckled hip, reads well and its drawn side has no door).
"""
from __future__ import annotations

import numpy as np

from tools.shape_fit.crystal_pass import building_kit as BK
from tools.shape_fit.crystal_pass.building_parts import box, drawing, hip_and_gable, paint, profile, swept_eave
from tools.shape_fit.crystal_pass.buildings_johto_a import readings_sheet
from tools.shape_fit.crystal_pass.common import Pass


def machiya(W, door=None, windows=()):
    """Codex's b010 machiya (claude_qc_buildings.house_spec) for a drawing W wide and 48 deep: an ochre deck, timbered
    plaster walls with the lattice wainscot, the white beam and brown plate at the wall head, veranda posts, a hip-and-gable
    roof with upturned eaves and corner flicks, the lattice door recessed, the barred windows proud."""
    ROOF_TOP, EAVE_FRONT, GABLE = [8, 1, W - 8, 24], [0, 24, W, 29], [1, 4, 7, 24]
    BEAM, BEAM_SIDE, RIDGE = [1, 29, W - 1, 32], [8, 29, 16, 32], [8, 26, W - 8, 28]
    POST, DARKP = [1, 32, 3, 46], [3, 32, 4, 46]
    side = [6, 32, 16, 46]
    parts = [box((1, W - 1), (4, 48), (0, 2), paint([1, 46, W - 1, 48], top=[2, 47, W - 2, 48], side=[2, 46, 8, 48])),
             box((4, W - 4), (8, 44), (2, 22), paint([4, 32, W - 4, 46], top=[8, 33, 16, 40], side=side, back=side)),
             box((3, W - 3), (7, 45), (22, 25), paint(BEAM, top=BEAM_SIDE, side=BEAM_SIDE))]
    for px in (1, W - 3):
        for pz in (5, 45):
            parts.append(box((px, px + 2), (pz, pz + 2), (2, 25), paint(POST, side=POST)))
    x, z, y0, rise, hip_rise, hip_run = (0, W), (0, 48), 24, 21, 11, 10
    across = swept_eave(48, rise)
    along = hip_and_gable(W, rise, hip_rise, hip_run)
    parts.append(profile(x, z, (y0, y0 + rise + 1), "x", across, paint(EAVE_FRONT, top=ROOF_TOP, side=GABLE, back=EAVE_FRONT), cross=along))
    cz, top = 24, y0 + rise
    parts.append(box((hip_run - 2, W - hip_run + 2), (cz - 2, cz + 2), (top - 1, top + 2), paint(RIDGE, top=[8, 26, W - 8, 27], side=[8, 26, 12, 28])))
    for ex in (hip_run - 3, W - hip_run):
        parts.append(box((ex, ex + 3), (cz - 3, cz + 3), (top - 1, top + 4), paint(DARKP, top=DARKP, side=DARKP)))
    tip = paint(EAVE_FRONT, top=[8, 25, 12, 26], side=[8, 25, 12, 28])
    for cx in (0, W - 3):
        for cz2 in (0, 45):
            parts.append(box((cx, cx + 3), (cz2, cz2 + 3), (26, 29), tip))
    if door:
        x0, x1 = door
        parts.append(box((x0 + 2, x1 - 2), (40, 44), (2, 18), carve=True))
        parts.append(box((x0 + 2, x1 - 2), (40, 41), (2, 18), paint([x0 + 2, 33, x1 - 2, 46], side=[x0 + 2, 33, x0 + 3, 46])))
        parts.append(box((x0, x1), (44, 47), (0, 3), paint([x0, 45, x1, 47], top=[x0 + 2, 45, x1 - 4, 46], side=[x0, 45, x0 + 2, 47])))
    for wx0, wx1 in windows:
        win = [wx0, 32, wx1, 37]
        parts.append(box((wx0, wx1), (44, 45), (14, 21), paint(win, side=[wx0, 32, wx0 + 1, 37])))
    parts.append(box((W - 4, W - 3), (22, 28), (12, 19), paint([windows[0][0], 32, windows[0][1], 37] if windows else [48, 32, 53, 37])))
    parts.append(box((3, 4), (22, 28), (12, 19), paint([windows[0][0], 32, windows[0][1], 37] if windows else [48, 32, 53, 37])))
    return {"height": 50, "parts": parts}


def burned_tower():
    """The Burned Tower (64x64): a wooden hall (walls 8..56, the facade rows 48-63 with the lattice door recessed) under a
    low ruined roof painted from the charred roof (rows 8-40), the eave lines (rows 41-47) on its edges; rubble mounds at
    both sides (columns 0-8 and 56-63)."""
    W, D = 64, 64
    parts = [box((8, 56), (14, 61), (0, 2), paint([8, 62, 56, 64], top=[8, 62, 56, 63], side=[8, 62, 12, 64])),
             box((9, 55), (16, 60), (2, 22), paint([9, 48, 55, 62], side=[34, 48, 40, 56], back=[34, 48, 40, 56]))]
    for px in (8, 54):
        for pz in (16, 58):
            parts.append(box((px, px + 2), (pz, pz + 2), (2, 22), paint([8, 48, 10, 62], side=[8, 48, 10, 62])))
    parts.append(box((6, 58), (12, 62), (22, 25), paint([6, 43, 58, 47], top=[10, 41, 50, 44], side=[6, 43, 12, 47])))
    parts.append(profile((6, 58), (8, 62), (25, 34), "x", swept_eave(54, 8, lip=2, dip=1),
                         paint([6, 41, 58, 44], top=[8, 8, 56, 40], side=[8, 20, 14, 30], back=[8, 8, 56, 12])))
    # the charred hole: the roof opened down to the wall head over the drawn hole
    parts.append(box((22, 44), (24, 40), (26, 36), carve=True))
    parts.append(box((17, 31), (55, 60), (2, 18), carve=True))
    parts.append(box((17, 31), (55, 56), (2, 18), paint([17, 49, 31, 62], side=[17, 49, 18, 62])))
    parts.append(box((40, 47), (60, 61), (12, 20), paint([40, 48, 47, 53])))
    for x in ((0, 8), (56, 64)):
        parts.append(box(x, (10, 62), (0, 6), paint([x[0], 48, x[1], 63], top=[x[0], 10, x[1], 40], side=[x[0], 48, x[1], 63])))
    return {"height": 37, "parts": parts}


def bell_tower():
    """The Bell Tower as Ecruteak draws it (64x128): eight square storeys on the footprint's south (x 4..60, z 68..124),
    each 18 high with its band's art (rows 96-107: the wall with paired windows between lattice posts; the ground storey
    rows 112-127 with the lattice door recessed), a balcony eave three proud at each storey's head (rows 108-111), a low hip
    cap."""
    parts = [box((2, 62), (66, 126), (0, 2), paint([2, 126, 62, 128], top=[16, 112, 31, 113], side=[2, 126, 6, 128]))]
    band, eave, ground = [2, 96, 62, 108], [0, 108, 64, 112], [2, 112, 62, 127]
    side = [48, 96, 63, 108]
    y = 2
    for k in range(8):
        front = ground if k == 0 else band
        parts.append(box((4, 60), (68, 124), (y, y + 18), paint(front, top=[16, 105, 31, 107], side=side, back=side)))
        parts.append(box((1, 63), (65, 127), (y + 18, y + 21), paint(eave, top=[8, 109, 56, 111], side=[0, 108, 8, 112], back=eave)))
        y += 21
    parts.append({"shape": "hip", "x": [0, 64], "z": [64, 128], "y": [y, y + 18], "paint": paint(eave, top=[8, 105, 56, 107], side=[0, 108, 8, 112])})
    parts.append(box((17, 31), (119, 125), (2, 18), carve=True))
    parts.append(box((17, 31), (119, 120), (2, 18), paint([17, 113, 31, 126], side=[17, 113, 18, 126])))
    return {"height": y + 19, "parts": parts}


BUILT = {
    "crystal_b008_G2_ECRUTEAK_CITY": ("Ecruteak machiya (three windows, no door this side)", lambda a: machiya(64, None, [(11, 16), (24, 29), (48, 53)])),
    "crystal_b009_G2_ECRUTEAK_CITY": ("Ecruteak machiya (six wide, the door in the middle)", lambda a: machiya(96, (32, 48), [(11, 16), (24, 29), (51, 56), (80, 85)])),
    "crystal_b015_G2_ECRUTEAK_CITY": ("Ecruteak house under a speckled roof", lambda a: BK.johto(BK.read(a))),
    "crystal_b007_G2_ECRUTEAK_CITY": ("Burned Tower", lambda a: burned_tower()),
    "crystal_b016_G2_ECRUTEAK_CITY": ("Bell Tower (as Ecruteak draws it)", lambda a: bell_tower()),
}
REASONS = {
    "crystal_b008_G2_ECRUTEAK_CITY": "Codex's accepted b010 machiya with this drawing's three barred windows (no door on this side).",
    "crystal_b009_G2_ECRUTEAK_CITY": "Codex's accepted b010 machiya six cells wide: the lattice door recessed in the middle, four barred windows.",
    "crystal_b015_G2_ECRUTEAK_CITY": "A Johto facade under a speckled traditional roof: the Johto archetype (gable, corner posts, proud windows).",
    "crystal_b007_G2_ECRUTEAK_CITY": "A wooden hall under a low ruined roof painted from the charred roof, its hole opened; rubble at both sides; the lattice door recessed.",
    "crystal_b016_G2_ECRUTEAK_CITY": "Eight square storeys with balcony eaves (the drawing's repeating bands) on the footprint's south, a low hip cap, the door recessed.",
}
RETAINED = {
    "crystal_b010_G2_ECRUTEAK_CITY": ("Ecruteak machiya (Codex, accepted)", "Codex's accepted trial: protected, never changed."),
    "crystal_b088_G2_VIOLET_CITY": ("Sprout Tower (Codex, accepted)", "Codex's accepted trial: protected, never changed."),
    "crystal_b105_G2_RUINS_OF_ALPH_OUTSIDE": ("Ruins of Alph research centre", "Its earlier parts spec (brick walls under a speckled hip "
                                              "roof) reads well, and its drawn side has no door to recess; kept."),
}


def main():
    out = Pass("buildings-ecruteak")
    table = {b["id"]: b for b in out.table["buildings"]}
    items = []
    for bid, (label, build) in BUILT.items():
        img = drawing(table[bid])
        items.append((bid, img.resize((img.width * 3, img.height * 3))))
        out.building(bid, build(np.asarray(img)), label=label, family="be-ecruteak", reason=REASONS[bid])
    for bid, (label, why) in RETAINED.items():
        out.keep_building(bid, label=label, family="be-retained", reason=why)
    out.write(owned=[*BUILT, *RETAINED])
    readings_sheet(out.out, items)


if __name__ == "__main__":
    main()
