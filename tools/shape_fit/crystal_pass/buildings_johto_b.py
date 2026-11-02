"""Crystal checkpoint 29: the modern Johto town buildings, second half (Goldenrod's regular buildings, Blackthorn, Cianwood,
Mahogany, the Ruins of Alph's small house), and the three fitted buildings the runtime never applies.

Source reading (building_kit.read's overlays, `readings.png` here; the survey's templates):

- The Johto archetype as checkpoint 28 (building_kit.johto), each from its drawing's reading.
- Goldenrod's small blocks (b033, b034: a roof over a plain wall, 14 and 6 claims) and the 4x4 buildings whose drawn side
  has no door (b035, b036) are the same archetype without the door.
- b024 is a facade with no roof (the walls of a house whose roof rows the runtime leaves to Tier A, or to units as at the
  Battle Tower's grounds, checkpoint 27): walls only, flat-topped at 30 voxels (the height the runtime gave these claims,
  which the Battle Tower house's roof units start from), the door recessed, the window proud.
- Never applied (the survey's templates never match their tiles): b026 (Goldenrod), b097 (the Battle Tower house: the
  runtime claims only its walls, b024) and b104 (the Ruins of Alph): retained.
"""
from __future__ import annotations

from tools.shape_fit.crystal_pass import building_kit as BK
from tools.shape_fit.crystal_pass.building_parts import drawing
from tools.shape_fit.crystal_pass.buildings_johto_a import readings_sheet
from tools.shape_fit.crystal_pass.common import Pass

BUILDINGS = {
    "crystal_b025_G2_GOLDENROD_CITY": ("Goldenrod small house", "bj-goldenrod"),
    "crystal_b027_G2_GOLDENROD_CITY": ("Goldenrod Gym", "bj-goldenrod"),
    "crystal_b028_G2_GOLDENROD_CITY": ("Goldenrod house (6x4)", "bj-goldenrod"),
    "crystal_b030_G2_GOLDENROD_CITY": ("Goldenrod house (6x4, two windows)", "bj-goldenrod"),
    "crystal_b031_G2_GOLDENROD_CITY": ("Goldenrod Pokémon Center", "bj-goldenrod"),
    "crystal_b033_G2_GOLDENROD_CITY": ("Goldenrod small block (no door)", "bj-goldenrod"),
    "crystal_b034_G2_GOLDENROD_CITY": ("Goldenrod small block, three wide (no door)", "bj-goldenrod"),
    "crystal_b035_G2_GOLDENROD_CITY": ("Goldenrod house (4x4, brick, no door this side)", "bj-goldenrod"),
    "crystal_b036_G2_GOLDENROD_CITY": ("Goldenrod house (4x4, no door this side)", "bj-goldenrod"),
    "crystal_b037_G2_BLACKTHORN_CITY": ("Blackthorn Gym", "bj-blackthorn"),
    "crystal_b038_G2_BLACKTHORN_CITY": ("Blackthorn Mart", "bj-blackthorn"),
    "crystal_b039_G2_BLACKTHORN_CITY": ("Blackthorn Pokémon Center", "bj-blackthorn"),
    "crystal_b040_G2_BLACKTHORN_CITY": ("Blackthorn small house", "bj-blackthorn"),
    "crystal_b061_G2_CIANWOOD_CITY": ("Cianwood small house", "bj-cianwood"),
    "crystal_b062_G2_CIANWOOD_CITY": ("Cianwood Gym", "bj-cianwood"),
    "crystal_b063_G2_CIANWOOD_CITY": ("Cianwood Pokémon Center", "bj-cianwood"),
    "crystal_b072_G2_MAHOGANY_TOWN": ("Mahogany house (4x4)", "bj-mahogany"),
    "crystal_b073_G2_MAHOGANY_TOWN": ("Mahogany small house", "bj-mahogany"),
    "crystal_b074_G2_MAHOGANY_TOWN": ("Mahogany Gym", "bj-mahogany"),
    "crystal_b075_G2_MAHOGANY_TOWN": ("Mahogany Pokémon Center", "bj-mahogany"),
    "crystal_b106_G2_RUINS_OF_ALPH_OUTSIDE": ("Ruins of Alph small house", "bj-ruins"),
}
WALLS_ONLY = {"crystal_b024_G2_GOLDENROD_CITY": ("Goldenrod house walls (no roof drawn)", "bj-goldenrod")}
NEVER_APPLIED = {"crystal_b026_G2_GOLDENROD_CITY": "Goldenrod house (4x4)",
                 "crystal_b097_G2_BATTLE_TOWER_OUTSIDE": "Battle Tower grounds house",
                 "crystal_b104_G2_RUINS_OF_ALPH_OUTSIDE": "Ruins of Alph house"}


def main():
    out = Pass("buildings-johto-b")
    table = {b["id"]: b for b in out.table["buildings"]}
    items = []
    for bid, (label, family) in {**BUILDINGS, **WALLS_ONLY}.items():
        img = drawing(table[bid])
        walls = bid in WALLS_ONLY
        f = BK.read(img, top=0 if walls else None)
        if f.top is None:
            raise SystemExit(f"{bid}: no facade line")
        items.append((bid, BK.overlay(img, f, 3)))
        if walls:
            out.building(bid, BK.johto(f, roof=False, top_y=30), label=label, family=family,
                         reason="A facade with no roof drawn (the runtime claims only these walls; the roof rows behind are "
                                "Tier A's or units): walls only, flat-topped at 30, the door recessed, the window proud.")
            continue
        what = ", ".join(x for x in (
            "the door recessed" if f.door else "no door on this side",
            f"{len(f.windows)} windows" if f.windows else "",
            {"stand": "the sign on its legs", "wall": "the wall sign"}.get(f.signs[0][1], "") if f.signs else "") if x)
        out.building(bid, BK.johto(f), label=label, family=family,
                     reason=f"The Johto archetype from its drawing (facade from row {f.top}): walls behind the corner posts, "
                            f"a gable over the footprint with the roof art along its slope, {what}.")
    for bid, label in NEVER_APPLIED.items():
        out.keep_building(bid, label=f"{label} (never applied)", family="bj-unapplied",
                          reason="The survey's templates never match its tiles (census/buildings-applied-cp26.json): it has "
                                 "no runtime effect; kept.")
    out.write(owned=[*BUILDINGS, *WALLS_ONLY, *NEVER_APPLIED])
    readings_sheet(out.out, items)


if __name__ == "__main__":
    main()
