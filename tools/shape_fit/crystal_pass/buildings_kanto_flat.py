"""Crystal checkpoint 31: the Kanto flat-roofed buildings (Celadon, Cerulean's Mart, Fuchsia, Lavender, Saffron, Pewter's
museum, the Indigo Plateau).

Source reading (building_kit.flat_eave and read; the pixel maps of b045, b064, b077; the survey's templates):

- The archetype (b041-b047, b051, b064, b071, b080, b083): a flat roof seen from above (a lattice field inside the
  parapet's outline), a triple cornice line, then the facade (an upper row of windows, brick bands, the door; the Pokémon
  Center's and Mart's signs are grey bars painted on the wall) between grey stone pillars. building_kit.kanto_flat: walls
  1:1 for tall facades (Silph Co. stands 22 blocks), a flat roof slab with the drawn field on top and the cornice on its
  edges, a parapet three voxels high, the pillars, the door recessed.
- b069 (the Kanto Radio Tower's facade, at Lavender's top edge) has no roof drawn (the windows start at row 0): walls only,
  capped in the facade's top row.
- b084 is all roof: the runtime claims it over a building body Tier A raises (Saffron, Route 10), with the default box 30
  high, so the roof slab and parapet stand at 30.
- b077 (Pewter's museum): a two-tier hip roof (an upper tier rows 0-31, a lower tier rows 32-55, each a lattice field and a
  triple eave), an eave soffit band (rows 56-62), the facade below (rows 64-95) between stone pillars: walls, a low hip in
  the lower field, a raised upper storey under a second hip in the upper field.
- b102 (the Indigo Plateau's facade at Route 23's top edge) keeps its earlier parts spec (a tall facade block, 184 high,
  which reads well); only the door recess is added, so the double door stands open over its walkable door tiles.
"""
from __future__ import annotations

import copy

import numpy as np

from tools.shape_fit.crystal_pass import building_kit as BK
from tools.shape_fit.crystal_pass.building_parts import box, drawing, paint
from tools.shape_fit.crystal_pass.buildings_johto_a import readings_sheet
from tools.shape_fit.crystal_pass.common import Pass

REGULAR = {
    "crystal_b041_G2_CELADON_CITY": "Celadon Department Store",
    "crystal_b042_G2_CELADON_CITY": "Celadon apartments (no door this side)",
    "crystal_b043_G2_CELADON_CITY": "Celadon Mansion",
    "crystal_b044_G2_CELADON_CITY": "Kanto flat-roofed block (no door this side; 33 claims)",
    "crystal_b045_G2_CELADON_CITY": "Kanto Pokémon Center (flat roof)",
    "crystal_b046_G2_CELADON_CITY": "Celadon Game Corner block",
    "crystal_b047_G2_CELADON_CITY": "Kanto flat-roofed house (12 claims)",
    "crystal_b051_G2_CERULEAN_CITY": "Kanto Mart (flat roof)",
    "crystal_b064_G2_FUCHSIA_CITY": "Fuchsia flat-roofed block (no door this side)",
    "crystal_b071_G2_LAVENDER_TOWN": "Lavender Volunteer house",
    "crystal_b080_G2_SAFFRON_CITY": "Saffron Magnet Train station",
    "crystal_b083_G2_SAFFRON_CITY": "Silph Co.",
}
WALLS_ONLY = {"crystal_b069_G2_LAVENDER_TOWN": "Kanto Radio Tower facade (no roof drawn)"}
ROOF_ONLY = {"crystal_b084_G2_SAFFRON_CITY": "flat roof over a Tier A body"}
MUSEUM = "crystal_b077_G2_PEWTER_CITY"
INDIGO = "crystal_b102_G2_ROUTE_23"


def museum(a):
    """Pewter's museum (128x96): walls (rows 64-95) between pillars, an eave soffit, a low lower hip in the lower field,
    a raised upper storey under a second hip in the upper field."""
    D, W = a.shape[:2]
    y0, zf, zb, yt = 2, D - 3, 3, 34
    upper, lower = [16, 5, W - 16, 28], [16, 33, W - 16, 52]
    parts = [box((1, W - 1), (zb - 1, D), (0, y0), paint([1, D - 1, W - 1, D], top=[2, D - 1, W - 2, D], side=[1, D - 1, 3, D])),
             box((4, W - 4), (zb, zf), (y0, yt), paint([4, 64, W - 4, D - 1], side=[8, 72, 16, 90], back=[8, 72, 16, 90]))]
    for px in ((1, 4), (W - 4, W - 1)):
        for pz in ((zf - 4, zf), (zb, zb + 4)):
            parts.append(box(px, pz, (y0, yt), paint([2, 64, 4, D - 1], side=[2, 64, 4, D - 1])))
    parts.append(box((0, W), (0, D), (yt, yt + 4), paint([0, 56, W, 63], top=lower, side=[0, 56, 16, 63], back=[0, 56, 16, 63])))
    parts.append({"shape": "hip", "x": [0, W], "z": [0, D], "y": [yt + 4, yt + 18], "paint": paint([0, 53, W, 56], top=lower, side=[0, 32, 14, 52])})
    parts.append(box((20, W - 20), (18, D - 18), (yt + 10, yt + 22), paint([0, 29, W, 32], top=upper, side=[0, 29, 16, 32], back=[0, 29, 16, 32])))
    parts.append({"shape": "hip", "x": [14, W - 14], "z": [12, D - 12], "y": [yt + 22, yt + 40], "paint": paint([0, 29, W, 32], top=upper, side=[0, 8, 14, 28])})
    return {"height": yt + 41, "parts": parts}


def with_door(entry, a):
    """An existing spec with the drawn door recessed (a carve and the door at its back), at the facade's base."""
    spec = {k: copy.deepcopy(entry[k]) for k in ("height", "parts")}
    f = BK.read(a, top=0)
    if not f.door:
        raise SystemExit(f"{entry['id']}: no door to recess")
    x0, z0, x1, z1 = f.door
    D = a.shape[0]
    front = max(p["z"][1] for p in spec["parts"] if "z" in p and not p.get("carve"))
    walls = [p for p in spec["parts"] if p.get("shape") == "box" and not p.get("carve") and p["z"][1] == front]
    y_lo = min(p["y"][0] for p in walls)
    y_hi = max(p["y"][1] for p in walls)
    rows = D - z0
    dh = round(y_lo + (y_hi - y_lo) * rows / D)
    spec["parts"].append(box((x0 + 1, x1 - 1), (front - 4, min(front + 1, D)), (y_lo, dh), carve=True))
    spec["parts"].append(box((x0 + 1, x1 - 1), (front - 4, front - 3), (y_lo, dh), paint([x0 + 1, z0 + 1, x1 - 1, z1], side=[x0 + 1, z0 + 1, x0 + 2, z1])))
    return spec, f.door


def main():
    out = Pass("buildings-kanto-flat")
    table = {b["id"]: b for b in out.table["buildings"]}
    items = []
    for bid, label in REGULAR.items():
        img = drawing(table[bid])
        a = np.asarray(img)
        e = BK.flat_eave(a)
        f = BK.read(a, top=e + 1)
        items.append((bid, BK.overlay(img, f, 2)))
        out.building(bid, BK.kanto_flat(a), label=label, family="bk-flat",
                     reason=f"The Kanto flat-roof archetype from its drawing (cornice row {e}): walls between the stone pillars, a "
                            f"flat roof slab with the drawn field on top and a parapet, "
                            f"{'the door recessed' if f.door else 'no door on this side'}.")
    for bid, label in WALLS_ONLY.items():
        img = drawing(table[bid])
        items.append((bid, BK.overlay(img, BK.read(np.asarray(img), top=0), 2)))
        out.building(bid, BK.kanto_flat(np.asarray(img), walls_only=True), label=label, family="bk-flat",
                     reason="A facade with no roof drawn (the building's roof lies beyond the map's edge): walls only, capped in "
                            "the facade's top row, the door recessed.")
    for bid, label in ROOF_ONLY.items():
        img = drawing(table[bid])
        items.append((bid, img.resize((img.width * 2, img.height * 2))))
        out.building(bid, BK.kanto_flat(np.asarray(img), eave=img.height - 1, roof_only=True), label=label, family="bk-flat",
                     reason="All roof: claimed over a building body Tier A raises, with the default box 30 high; the roof slab "
                            "(the drawn field) and its parapet stand at 30 on a base painted with the cornice.")
    img = drawing(table[MUSEUM])
    items.append((MUSEUM, img.resize((img.width * 2, img.height * 2))))
    out.building(MUSEUM, museum(np.asarray(img)), label="Pewter Museum", family="bk-special",
                 reason="A two-tier hip roof over the walls: an eave soffit, a low lower hip in the lower field, a raised upper "
                        "storey under a second hip in the upper field; walls between stone pillars (no door on this side).")
    img = drawing(table[INDIGO])
    spec, door = with_door(table[INDIGO], np.asarray(img))
    f = BK.Facade(img.width, img.height, 0, door)
    items.append((INDIGO, BK.overlay(img, f, 2)))
    out.building(INDIGO, spec, label="Indigo Plateau facade", family="bk-special",
                 reason="The earlier parts spec (a tall facade block that reads well) kept; the drawn double door recessed over "
                        "its walkable door tiles.")
    out.write(owned=[*REGULAR, *WALLS_ONLY, *ROOF_ONLY, MUSEUM, INDIGO])
    readings_sheet(out.out, items)


if __name__ == "__main__":
    main()
