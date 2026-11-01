"""Claude's independent QC recreation of the Rustboro octagonal fountain.

NOT part of the integrated Emerald pass: Codex's reviewed fountain stays live.
This authors the same nine source keys from the source drawing alone, so the two
versions can be compared in the Minecraft gallery. It was written without reading
Codex's fountain generator or its geometry.

Source reading (EM_RUSTBORO_CITY (27,38), all nine cells SOLID7): one 48x48 drawing
of a regular octagonal basin on the plaza paving. The rim is a pale stone coping
with a mid-gray groove line and a dark inner edge; the south, south-west and
south-east outer walls show a blue-gray face under the coping lip. Flat teal water
fills the basin. A raised round dish in the middle holds a gray nozzle mound with a
white jet that flares at the top, spray dots falling either side. Excluded as
non-geometry: the paving outside the octagon, the dark ellipse under the dish (its
cast shadow on the water) and the shaded paving south-east of the basin.

Proportions: the octagon fills the 3x3 footprint (inradius 23 voxels; the drawing's
south wall rows are the vertical face, not extra floor depth). Heights follow the
drawing's own vertical rows: about seven rows of south rim face -> coping at 7
voxels; the dish rim drawn ~9 rows above its footprint centre -> 11; the jet tip
~16 rows above it -> 18.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path

import numpy as np

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.emerald_pass.claude_source_paint import assemble, previews, slice_object

ROOT = Path(__file__).resolve().parents[3]
BASELINE = ROOT / "local/model-gallery/emerald-full-pass-baseline.json"
BUNDLE = ROOT / "local/minecraft/kantobridge/export/emerald"
OUT = ROOT / "local/model-gallery/claude-trial/fountain"
SOURCE = "claude-qc-rustboro-fountain"
LAYOUT = [
    ["g3q/25717-25718-25719-25720", "g3q/25721-25722-25723-25724", "g3q/25725-25726-25727-25728"],
    ["g3q/25733-25734-25735-25736", "g3q/25737-25738-25739-25740", "g3q/25741-25742-25743-25744"],
    ["g3q/25752-25753-25324-25754", "g3q/25755-25755-25756-25757", "g3q/25758-25759-25760-25325"],
]
LABELS = [
    ["north-west diagonal rim", "north rim", "north-east diagonal rim"],
    ["west rim", "central dish, nozzle and jet", "east rim"],
    ["south-west diagonal rim", "south rim", "south-east diagonal rim"],
]
ENTITY = "emerald_rustboro_octagonal_fountain"

# Exact colours of the exported drawing.
WATER, STONE, GROOVE, EDGE = (132, 181, 181), (222, 222, 222), (132, 132, 140), (99, 99, 123)
FACE, BOWL, SPRAY, RIPPLE, JET_BASE = (173, 181, 189), (140, 148, 156), (197, 222, 239), (148, 197, 197), (156, 181, 214)
MATERIALS = {
    1: [STONE],            # coping top and lip, north/east/west outer walls
    2: [GROOVE],           # groove line between the outer and inner coping bands
    3: [EDGE],             # inner coping edge above the water
    4: [GROOVE],           # inner coping face above the water
    5: [FACE],             # south-facing outer wall under the lip
    6: [WATER],            # basin water
    7: [BOWL],             # pedestal and nozzle mound
    8: [STONE],            # dish rim
    9: [GROOVE],           # dish outer side
    10: [EDGE],            # dish underside
    11: [WATER],           # dish water
    12: [RIPPLE, WATER],   # splash ring where the spray lands
    13: [SPRAY],           # jet column, crown and falling spray
    15: [JET_BASE, SPRAY], # jet base
}
C, HEIGHT = 24.0, 18
R_IN, R_INNER_BAND, R_GROOVE, R_WALL, R_LIP = 16, 19, 20, 22, 23
WATER_TOP, LIP_TOP, COPING_TOP = 4, 6, 7


def design():
    """The whole fountain: (occupancy, material code) in global voxels (y, z, x)."""
    yy, zz, xx = np.indices((HEIGHT, 48, 48))
    dx, dz = xx + .5 - C, zz + .5 - C
    adx, adz = np.abs(dx), np.abs(dz)
    diag = (adx + adz) / math.sqrt(2)
    r = np.maximum(np.maximum(adx, adz), diag)          # regular octagon metric
    pr = np.hypot(dx, dz)                                 # round parts
    mat = np.zeros((HEIGHT, 48, 48), np.uint8)

    # Basin: solid wall under the coping, water inside it.
    wall = (r >= R_IN) & (r < R_WALL) & (yy < WATER_TOP)
    south = (dz > 0) & (diag >= adx)                      # S, SW and SE faces
    mat[wall] = 1
    mat[wall & (r >= R_WALL - 1) & south] = 5
    mat[(r < R_IN) & (yy < WATER_TOP)] = 6
    # Coping: outer band with an overhanging lip, inner band one voxel higher.
    coping = (r >= R_IN) & (r < R_LIP) & (yy >= WATER_TOP) & (yy < LIP_TOP)
    coping |= (r >= R_IN) & (r < R_INNER_BAND) & (yy == LIP_TOP)
    mat[coping] = 1
    mat[coping & (r >= R_INNER_BAND) & (r < R_GROOVE) & (yy == LIP_TOP - 1)] = 2
    mat[coping & (r < R_IN + 1)] = 4
    mat[coping & (r < R_IN + 1) & (yy == COPING_TOP - 1)] = 3

    # Pedestal rising from the water with a slight flare, then the raised dish.
    mat[(pr < 3.5) & (yy == WATER_TOP)] = 7
    mat[(pr < 2.5) & (yy >= WATER_TOP) & (yy < 7)] = 7
    mat[(pr < 5.0) & (yy == 7)] = 10
    mat[(pr < 6.5) & (yy == 8)] = 9
    mat[(pr >= 6.5) & (pr < 8.2) & (yy == 9)] = 9
    mat[(pr < 6.5) & (yy == 9)] = 11
    mat[(pr < 5.5) & (pr >= 3.5) & (yy == 9)] = 12
    mat[(pr >= 7.0) & (pr < 9.0) & (yy == 10)] = 8
    # Gray nozzle mound in the dish, the jet column, its crown and tip.
    mound = ((dx / 3.0) ** 2 + ((yy + .5 - 10) / 2.5) ** 2 + (dz / 3.0) ** 2 <= 1) & (yy >= 10)
    mat[mound] = 7
    jet = (adx < 1) & (adz < 1)
    mat[jet & (yy >= 12) & (yy < 16)] = 13
    mat[jet & (yy == 12)] = 15
    crown = (adx < 2) & (adz < 2) & ~((adx > 1) & (adz > 1))   # 4x4 with its corners rounded off
    mat[crown & (yy >= 15) & (yy < 17)] = 13
    mat[jet & (yy == 17)] = 13
    # Four two-voxel sheets of spray arc out of the crown and fall into the dish.
    for out, y in [(2, 15), (2, 14), (3, 14), (3, 13), (3, 12), (4, 12), (4, 11), (4, 10)]:
        for sign in (-1, 1):
            along = (C + sign * (out + .5)) - .5
            mat[y, 23:25, int(along)] = 13
            mat[y, int(along), 23:25] = 13
    return mat > 0, mat


def hint(y, z, x):
    """Drawing position a voxel represents: the GBA view lifts height into rows."""
    return x + .5, z + .5 - y


def entry_hash(entry):
    return hashlib.sha256(json.dumps(entry, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def generate(table, art):
    occ, mat = design()
    originals = {k: table["cells"][k] for row in LAYOUT for k in row}
    return slice_object(occ, mat, MATERIALS, LAYOUT, art, originals, SOURCE, hint)


def main():
    from PIL import Image
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--baseline", type=Path, default=BASELINE)
    ap.add_argument("--bundle", type=Path, default=BUNDLE)
    ap.add_argument("--out", type=Path, default=OUT)
    args = ap.parse_args()
    table = json.loads(args.baseline.read_text(encoding="utf-8-sig"))
    art = EmeraldArt(args.bundle)
    updates = generate(table, art)
    args.out.mkdir(parents=True, exist_ok=True)
    ledger = []
    for r, keys in enumerate(LAYOUT):
        for c, key in enumerate(keys):
            ledger.append({
                "key": key, "kind": "cell", "label": f"Rustboro fountain {LABELS[r][c]}",
                "family": "rustboro-octagonal-fountain (Claude QC recreation)", "decision": "changed",
                "reason": "One regular octagonal basin designed whole and sliced 3x3: stepped stone coping "
                          "with groove line and overhanging lip, blue-gray south-facing walls, flat teal water, "
                          "pedestal-borne round dish with nozzle mound, jet crown and four falling spray sheets. "
                          "Paving, the dish's cast shadow and the south-east paving shadow are excluded.",
                "baseline_entry_sha256": entry_hash(table["cells"][key]),
                "occurrences": art.locations.get(key, []),
                "evidence": ["source.png", "context.png", "current-angled.png", "baseline-angled.png"],
            })
    write_json(args.out / "updates.json", {"cells": updates, "buildings": {}})
    write_json(args.out / "ledger.json", ledger)
    art.drawing(LAYOUT, "BAKED").resize((384, 384), Image.Resampling.NEAREST).save(args.out / "source.png")
    place = art.locations[LAYOUT[0][0]][0]
    x, y = place["x"] * 16, place["y"] * 16
    art.map_image(place["map"]).crop((x - 48, y - 48, x + 96, y + 96)).resize((576, 576), Image.Resampling.NEAREST).save(args.out / "context.png")
    previews(*assemble(updates, LAYOUT, art), args.out, "current")
    previews(*assemble(table["cells"], LAYOUT, art), args.out, "baseline")
    print(f"{len(updates)} fountain cells authored -> {args.out}")


if __name__ == "__main__":
    main()
