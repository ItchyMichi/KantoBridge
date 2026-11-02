"""Crystal checkpoint 27: TILESET_BATTLE_TOWER_OUTSIDE_1 (the Battle Tower's grounds).

Source reading (study/groups/TILESET_BATTLE_TOWER_OUTSIDE_1; the map; the survey's templates):

- Every key but six is claimed by the Battle Tower's or the house's template (never drawn by its entry): retained.
- The six drawn keys are the house's roof (4x2 cells north of its walls). The runtime's template claims only the walls
  (8x4 art tiles: the survey's hull is the default box, 30 high, flat), so the roof rows are drawn as units behind it; as
  16-high blocks they stood as a lower purple box behind the walls. Designed once over its 4x2 cells: the roof slopes from
  the walls' top (30 voxels) at the walls down to an eave twelve high at the back, its top painted from the roof drawing
  (two layers: the cells are solid parts of a tall object).
"""
from __future__ import annotations

import json

import numpy as np

from tools.shape_fit.crystal_pass import interiors as I
from tools.shape_fit.crystal_pass.common import K, STUDY, Pass
from tools.shape_fit.crystal_pass.forest import union

FAMILIES = ["TILESET_BATTLE_TOWER_OUTSIDE_1"]
ROOF = [[K("649-640-650-641"), K("640-640-641-641"), K("640-640-641-641"), K("640-652-641-653")],
        [K("650-641-651-642"), K("641-641-642-642"), K("641-641-642-642"), K("641-653-642-654")]]


def roof():
    """The roof over its 4x2 cells (global y 0-31, z 0-31, x 0-63): its height falls from 30 at the walls (z 31) to 12 at
    the back (z 0); up faces show the drawn roof (rows 0-31 over z), the back eave and the gable ends the roof's slats."""
    yy, zz, xx = np.indices((32, 32, 64))
    h = 12 + (zz + .5) / 32 * 18
    occ = yy + .5 < h

    def colour_at(y, gz, gx, face):
        if face == "up":
            return gx + .5, gz + .5
        if face == "north":
            return gx + .5, 4.5   # the slats, not the drawn edge line
        if face in ("east", "west"):
            return (3.5 if gx < 32 else 59.5), gz + .5
        return gx + .5, 30.5
    return I.paint_global(occ, ROOF, colour_at, union([k for row in ROOF for k in row]))


def main():
    out = Pass("battle-tower-outside")
    census = json.loads((STUDY / "census" / "census.json").read_text(encoding="utf-8"))["keys"]
    for key, (occ, paint) in roof().items():
        out.cell(key, occ, paint, label="house roof (units behind the template's walls)", family="bto-roof",
                 reason="The house's roof rows, which the runtime template does not claim: one roof over the 4x2 cells sloping "
                        "from the walls' top (30) to a back eave (12), painted from the roof drawing (two layers).")
    out.entity("crystal_bto_house_roof", ROOF, "Battle Tower grounds: the house's roof (its walls are the template)")
    modelled = {k for row in ROOF for k in row}
    fam = json.loads((STUDY / "families.json").read_text(encoding="utf-8"))
    for key in fam["TILESET_BATTLE_TOWER_OUTSIDE_1"]:
        if key in modelled:
            continue
        info = census.get(key, {})
        if info.get("drawn", 0):
            raise SystemExit(f"{key} is drawn ({info['drawn']}) but has no decision")
        out.keep(key, label="never drawn", family="bto-retained",
                 reason=f"Never drawn by its entry (census: {', '.join(sorted(info.get('reasons') or {})) or 'unplaced'}); kept.")
    out.write(families=FAMILIES)


if __name__ == "__main__":
    main()
