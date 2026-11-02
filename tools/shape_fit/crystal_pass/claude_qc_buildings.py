"""Claude's independent QC recreation of Codex's two Crystal building trials.

NOT an integration candidate: Codex's accepted Ecruteak house (b010) and Sprout Tower
(b088) stay live. This authors the same two templates from their source drawings with the
same process (parts specs, source-rectangle paint, game-local parts_model previews, the
Minecraft gallery), so the two results can be compared before the full Crystal pass.

Source reading. Both drawings use four colours only: dark outline (58,58,58), brown wood
(165,123,25), pale plaster (222,255,222; also the ground colour outside the outline) and
ochre (197,148,58).

Ecruteak house b010 (64x48, 4x3 cells): rows 1-23 are a tiled roof seen from above (brown
courses between dark lines, x 8-55); x 0-7 and 56-63 are the roof's gable ends (pale battens
between ochre and dark lines). Rows 24-28 are stepped eave edges, row 29 a white beam, row 30
a brown plate. Rows 32-45 are the front wall: ochre posts standing outside it (x 1-2, 61-62)
that hold the eaves, a timbered plaster wall (x 3-60) with a lattice wainscot (rows 42-45),
the lattice door (x 16-31) and a small barred window (x 48-52); rows 46-47 an ochre deck.
Read as a machiya with a hip-and-gable roof and a narrow veranda: walls inset, posts at the
veranda corners, the white beam ringing the wall head.

Sprout Tower b088 (64x80, 4x5 cells): the same roof (rows 0-31); then two upper storeys
(rows 32-39, 48-55: plaster x 16-47 with two barred windows), each over a skirt roof (rows
40-43, 56-59: narrowing courses) and a full-width white eave band (rows 44-47, 60-63); the
ground storey (rows 64-79) has the door, a window and the lattice wainscot. The strips x 0-15
and 48-63 beside the upper walls are the lower roofs' edges in projection, not walls; the
rock and fence art at x 0-7 / 56-63 of rows 64-79 is terrain. Read as a three-tier pagoda:
square plan, upper storeys inset, every roof as wide as the drawing, a hip-and-gable top.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path

from tools.shape_fit.crystal_pass.building_parts import (
    BUNDLE, LIVE, ROOT, box, check_spec, drawing, hip_and_gable, paint, profile, swept_eave)

HOUSE = "crystal_b010_G2_ECRUTEAK_CITY"
TOWER = "crystal_b088_G2_VIOLET_CITY"
IDS = (HOUSE, TOWER)
OUT = ROOT / "local/model-gallery/crystal-claude-qc"

# Source rectangles shared by both drawings (identical roof rows 0-31).
ROOF_TOP = [8, 1, 56, 24]        # tile courses, laid along the roof surface
EAVE_FRONT = [0, 24, 64, 29]     # the stepped eave edges
GABLE = [1, 4, 7, 24]            # gable-end battens: pale, ochre, dark
BEAM = [1, 29, 63, 32]           # white beam, brown plate, dark line
BEAM_SIDE = [8, 29, 16, 32]
RIDGE = [8, 26, 56, 28]          # dark line over brown course
OCHRE_POST = [1, 32, 3, 46]
DARK = [3, 32, 4, 46]


def irimoya(x, z, y0, ridge_rise, *, hip_rise, hip_run):
    """A hip-and-gable roof over [x, z]: upturned eaves all round, the ridge along x."""
    (x0, x1), (z0, z1) = x, z
    across = swept_eave(z1 - z0, ridge_rise)
    along = hip_and_gable(x1 - x0, ridge_rise, hip_rise, hip_run)
    roof = profile(x, z, (y0, y0 + ridge_rise + 1), "x", across,
                   paint(EAVE_FRONT, top=ROOF_TOP, side=GABLE, back=EAVE_FRONT), cross=along)
    cz = (z0 + z1) // 2
    top = y0 + ridge_rise
    ridge = box((x0 + hip_run - 2, x1 - hip_run + 2), (cz - 2, cz + 2), (top - 1, top + 2),
                paint(RIDGE, top=[8, 26, 56, 27], side=[8, 26, 12, 28]))
    # Ridge-end tiles (onigawara) stand a little proud of the ridge.
    ends = [box((ex, ex + 3), (cz - 3, cz + 3), (top - 1, top + 4), paint(DARK, top=DARK, side=DARK))
            for ex in (x0 + hip_run - 3, x1 - hip_run)]
    return [roof, ridge, *ends]


def corner_flicks(x, z, y):
    """Upturned eave corners: a short raised tip at each roof corner."""
    (x0, x1), (z0, z1) = x, z
    tip = paint(EAVE_FRONT, top=[8, 25, 12, 26], side=[8, 25, 12, 28])
    return [box((cx, cx + 3), (cz, cz + 3), y, tip) for cx in (x0, x1 - 3) for cz in (z0, z1 - 3)]


def house_spec():
    plaster_side = [6, 32, 16, 46]   # brown post, plaster, top rail, balusters: repeats as bays
    parts = [
        # Ochre veranda deck the house stands on.
        box((1, 63), (4, 48), (0, 2), paint([1, 46, 63, 48], top=[2, 47, 62, 48], side=[2, 46, 8, 48])),
        # Timbered plaster walls with the lattice wainscot all round.
        box((4, 60), (8, 44), (2, 22), paint([4, 32, 60, 46], top=[8, 33, 16, 40],
                                             side=plaster_side, back=plaster_side)),
        # White beam and brown plate ringing the wall head, under the eaves.
        box((3, 61), (7, 45), (22, 25), paint(BEAM, top=BEAM_SIDE, side=BEAM_SIDE)),
    ]
    # Veranda posts holding the eaves at the four corners.
    for px in (1, 61):
        for pz in (5, 45):
            parts.append(box((px, px + 2), (pz, pz + 2), (2, 25), paint(OCHRE_POST, side=OCHRE_POST)))
    parts += irimoya((0, 64), (0, 48), 24, 21, hip_rise=11, hip_run=10)
    parts += corner_flicks((0, 64), (0, 48), (26, 29))
    # A real door recess with the lattice door at its back, and a raised step.
    parts.append(box((18, 30), (40, 44), (2, 18), carve=True))
    parts.append(box((18, 30), (40, 41), (2, 18), paint([18, 33, 30, 46], side=[18, 33, 19, 46])))
    parts.append(box((16, 32), (44, 47), (0, 3), paint([16, 45, 32, 47], top=[18, 45, 28, 46], side=[16, 45, 18, 47])))
    # Barred windows: the drawn one on the front, one each side wall.
    win = [48, 32, 53, 37]
    parts.append(box((48, 53), (44, 45), (14, 21), paint(win, side=[48, 32, 49, 37])))
    parts.append(box((60, 61), (22, 28), (12, 19), paint(win, side=win)))
    parts.append(box((3, 4), (22, 28), (12, 19), paint(win, side=win)))
    return {"height": 50, "parts": parts}


def storey(parts, x, z, y, front, side, posts=True):
    parts.append(box(x, z, y, paint(front, top=[24, 48, 40, 56], side=side, back=side)))
    if posts:
        (x0, x1), (z0, z1) = x, z
        for px in (x0 - 1, x1 - 1):
            for pz in (z0 - 1, z1 - 1):
                parts.append(box((px, px + 2), (pz, pz + 2), y, paint(OCHRE_POST, side=OCHRE_POST)))


def skirt_roof(parts, y0, rise, inner):
    """A full-width skirt roof with its white eave band, rising to the storey `inner` above."""
    x, z = (0, 64), (16, 80)
    flat = inner[0][1] - inner[0][0]
    across = swept_eave(64, rise, flat=flat)
    along = swept_eave(64, rise, flat=flat)
    parts.append(box((2, 62), (18, 78), (y0 - 3, y0), paint([1, 44, 63, 47], top=[8, 45, 16, 47], side=[8, 44, 16, 47])))
    parts.append(profile(x, z, (y0, y0 + rise + 1), "x", across,
                         paint([8, 40, 56, 44], top=ROOF_TOP, side=[8, 40, 12, 44], back=[8, 40, 56, 44]),
                         cross=along))
    parts.extend(corner_flicks(x, z, (y0 + 2, y0 + 5)))


def tower_spec():
    upper_front = [24, 48, 40, 56]           # plain plaster; windows are their own parts
    upper_side = [24, 48, 32, 56]
    ground_side = [32, 64, 40, 79]           # plaster over the lattice wainscot
    parts = [box((6, 58), (22, 80), (0, 2), paint([8, 78, 56, 80], top=[18, 79, 30, 80], side=[18, 78, 22, 80]))]
    # Ground storey, square plan, the door wall at the front.
    storey(parts, (11, 53), (27, 69), (2, 26), [11, 64, 53, 79], ground_side)
    skirt_roof(parts, 26, 9, ((16, 48), (32, 64)))
    # Second storey, inset to the drawn upper wall width.
    storey(parts, (16, 48), (32, 64), (34, 52), upper_front, upper_side)
    skirt_roof(parts, 52, 9, ((16, 48), (32, 64)))
    # Third storey.
    storey(parts, (16, 48), (32, 64), (60, 78), upper_front, upper_side)
    parts.append(box((2, 62), (18, 78), (77, 80), paint(BEAM, top=BEAM_SIDE, side=BEAM_SIDE)))
    parts += irimoya((0, 64), (16, 80), 80, 21, hip_rise=11, hip_run=10)
    parts += corner_flicks((0, 64), (16, 80), (82, 85))
    # Barred windows on both upper storeys (front, and each side), and the ground one.
    win = [19, 32, 24, 37]
    for y0 in (40, 66):
        for wx in (19, 40):
            parts.append(box((wx, wx + 5), (64, 65), (y0, y0 + 7), paint(win, side=[19, 32, 20, 37])))
        parts.append(box((48, 49), (45, 51), (y0, y0 + 7), paint(win, side=win)))
        parts.append(box((15, 16), (45, 51), (y0, y0 + 7), paint(win, side=win)))
    parts.append(box((40, 45), (69, 70), (16, 23), paint([40, 64, 45, 69], side=[40, 64, 41, 69])))
    # Door recess.
    parts.append(box((18, 30), (65, 69), (2, 18), carve=True))
    parts.append(box((18, 30), (65, 66), (2, 18), paint([18, 65, 30, 78], side=[18, 65, 19, 78])))
    parts.append(box((16, 32), (69, 72), (0, 3), paint([16, 77, 32, 79], top=[18, 78, 28, 79], side=[16, 77, 18, 79])))
    return {"height": 106, "parts": parts}


def generate():
    return {HOUSE: house_spec(), TOWER: tower_spec()}


def apply(table):
    """The table with the two QC templates in place of the live ones (scratch only)."""
    result = copy.deepcopy(table)
    specs = generate()
    found = set()
    for entry in result["buildings"]:
        if entry["id"] in specs:
            found.add(entry["id"])
            for key in ("roofRows", "roof", "bands", "parts", "height"):
                entry.pop(key, None)
            entry.update(specs[entry["id"]])
            entry.setdefault("terrain", [])
    if found != set(specs):
        raise ValueError(f"missing {set(specs) - found}")
    return result


def main():
    import numpy as np
    from PIL import Image
    from tools.shape_fit.crystal import parts_model

    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", type=Path, default=OUT)
    args = ap.parse_args()
    live_bytes = LIVE.read_bytes()
    table = json.loads(live_bytes)
    result = apply(table)
    entries = {b["id"]: b for b in result["buildings"]}
    study = args.out / "study"
    study.mkdir(parents=True, exist_ok=True)
    for name in IDS:
        entry = entries[name]
        art = drawing(entry, BUNDLE)
        problems = check_spec(entry, art.width, art.height)
        if problems:
            raise SystemExit(f"{name}: " + "; ".join(problems))
        occ, col = parts_model.build(entry, np.asarray(art))
        art.resize((art.width * 6, art.height * 6), Image.Resampling.NEAREST).save(study / f"{name}-source.png")
        parts_model.picture(occ, col, study / f"{name}-view.png", s=4)
        parts_model.front_view(occ, col, study / f"{name}-front.png", scale=5)
        (study / f"{name}-spec.json").write_text(json.dumps(generate()[name], indent=2) + "\n")
        print(name, f"{occ.shape[2]}x{occ.shape[0]}x{occ.shape[1]}", int(occ.sum()), "voxels,",
              len(entry["parts"]), "parts")
    table_dir = args.out / "table"
    table_dir.mkdir(parents=True, exist_ok=True)
    path = table_dir / "crystal.json"
    path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("scratch table", hashlib.sha256(path.read_bytes()).hexdigest(), "from live",
          hashlib.sha256(live_bytes).hexdigest())


if __name__ == "__main__":
    main()
