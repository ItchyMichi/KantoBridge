"""Battle Pyramid lobby flame brazier: one complete stacked object (Claude trial).

Source reading (EM_BATTLE_FRONTIER_BATTLE_PYRAMID_LOBBY, placements (4,4)/(4,5) and
(10,4)/(10,5); both cells SOLID7, elevation 4). The lower cell g3q/2480-2481-2482-2483
draws the front of a square fire cage: two dark-blue posts at each corner with golden
horizontal slats and dark gaps between them, an open window in the middle showing the
fire (red rim, orange body, pale-yellow core), a dark fire bed, and a golden base band
with dark studs at the floor. The upper cell g3q/2469-2458-2470-2471 (already `on` the
lower cell) draws the cage's top rim along its bottom row and a single wavy flame
tongue rising over the lobby floor. Excluded as non-geometry: the upper cell's gray
floor, tan tile border and right-hand floor shading.

Construction: one 16x16 column, 26 voxels high, cut at y=16 into the existing stack.
Four 4x4 lattice corner columns over a solid base; the drawing's dark gaps become
one-voxel recesses and the floor studs become feet. Every face has the open window
with the drawing's top rail stubs and rounded dark lower corners. The fire is a body
of revolution whose radius per height is the drawn fire's half-width per row, then a
tapering, slightly leaning tongue with two short side tongues. A dark cap ring (the
upper drawing's rim row) closes the cage top around the flame. Box and fire faces use
front projection of the lower drawing along the axis each voxel faces; the cap and
tongues use exact rim and flame colours of the upper drawing.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.blue_pass.common import encode
from tools.shape_fit.emerald_pass.claude_source_paint import CellPaint, assemble, previews

ROOT = Path(__file__).resolve().parents[3]
BASELINE = ROOT / "local/model-gallery/emerald-full-pass-baseline.json"
BUNDLE = ROOT / "local/minecraft/kantobridge/export/emerald"
OUT = ROOT / "local/model-gallery/emerald-pass-study/pyramid-brazier"
SOURCE = "hand-emerald-pyramid-brazier"
BASE = "g3q/2480-2481-2482-2483"
FLAME = "g3q/2469-2458-2470-2471"
HEIGHT = 32
GAP = (90, 82, 123)                       # the drawing's dark slat gaps / rim colour
RIM_LIT, RED, ORANGE = (156, 115, 58), (255, 8, 0), (255, 156, 0)
# Half-width of the drawn fire per height (lower drawing rows 26..16 -> y 5..15).
FIRE_RADIUS = {5: 2.0, 6: 3.0, 7: 4.0, 8: 4.0, 9: 4.0, 10: 4.0, 11: 4.0, 12: 4.0, 13: 3.0, 14: 3.0, 15: 3.0}
# Drawn tongue in the upper cell (rows 14..6 -> y 17..25): centre column and half-width.
TONGUE = {16: (8.0, 2.0), 17: (8.0, 2.0), 18: (7.5, 1.5), 19: (7.5, 1.5), 20: (8.0, 1.0),
          21: (8.0, 1.0), 22: (8.0, 1.0), 23: (7.5, .71), 24: (7.5, .71), 25: (6.5, .71)}
# The drawing only shows the tongue's sway across x; a gentle S-curve in z gives it depth.
TONGUE_Z = {16: 8.0, 17: 8.0, 18: 7.7, 19: 7.5, 20: 7.5, 21: 7.7, 22: 8.0, 23: 8.4, 24: 8.6, 25: 8.6}
# Short side tongues so the fire is not a flat cut-out from the side: (x, z, radius) by y.
SIDE_TONGUES = ({16: (9.5, 6.5, 1.2), 17: (9.8, 6.2, 1.0), 18: (10.1, 5.9, .71)},
                {16: (6.2, 9.6, 1.1), 17: (5.9, 9.9, .71)})
BOX, FIRE, CAP, CAP_EDGE, TONGUE_CORE, TONGUE_TIP = 1, 2, 3, 4, 5, 6


def design(lower_drawing):
    """Global (occupancy, material) of the whole brazier, indexed (y, z, x)."""
    yy, zz, xx = np.indices((HEIGHT, 16, 16))
    mat = np.zeros((HEIGHT, 16, 16), np.uint8)
    corner = ((xx < 4) | (xx >= 12)) & ((zz < 4) | (zz >= 12))
    mat[(yy < 5)] = BOX                                   # base band and fire bed
    mat[corner & (yy < 16)] = BOX                         # four lattice columns

    # Window outline on all four faces (outer layer): top rail stubs, dark lower fillets.
    def face_cells(u_values, y):
        for u in u_values:
            mat[y, 15, u] = mat[y, 0, u] = BOX            # south / north faces
            mat[y, u, 15] = mat[y, u, 0] = BOX            # east / west faces
    for y in (14, 15):
        face_cells((4, 11), y)
    face_cells((4, 5, 10, 11), 5)
    face_cells((4, 11), 6)

    # Recess the drawing's dark gaps one voxel into each outer face (slat gaps, feet).
    gap = (lower_drawing == np.asarray(GAP)).all(axis=2)  # (row, col)
    for y in range(16):
        row = 15 - y
        for u in range(16):
            if gap[row, u]:
                for z, x in ((15, u), (0, u), (u, 15), (u, 0)):
                    if mat[y, z, x] == BOX and 0 < u < 15:
                        mat[y, z, x] = 0

    # Fire: a body of revolution with the drawn half-width per height.
    pr = np.hypot(xx + .5 - 8, zz + .5 - 8)
    for y, radius in FIRE_RADIUS.items():
        mat[y][(pr[y] < radius)] = FIRE
    # Cap ring over the cage, in the upper cell (its bottom drawing row is this rim).
    ring = (yy == 16) & ~((xx >= 4) & (xx < 12) & (zz >= 4) & (zz < 12))
    mat[ring] = CAP
    mat[ring & (xx >= 3) & (xx < 13) & (zz >= 3) & (zz < 13)] = CAP_EDGE
    # Main tongue and two side tongues.
    for y, (cx, radius) in TONGUE.items():
        d = np.hypot(xx[y] + .5 - cx, zz[y] + .5 - TONGUE_Z[y])
        core = (d < radius) & (xx[y] + .5 <= cx) & (y < 23)
        mat[y][d < radius] = TONGUE_TIP
        mat[y][core] = TONGUE_CORE
    for tongue in SIDE_TONGUES:
        for y, (cx, cz, radius) in tongue.items():
            d = np.hypot(xx[y] + .5 - cx, zz[y] + .5 - cz)
            mat[y][(d < radius) & (mat[y] == 0)] = TONGUE_TIP if y > 16 else TONGUE_CORE
    return mat > 0, mat


def paint(occ, mat, art):
    """Per-voxel source address from each stack piece's own drawing."""
    lower = CellPaint(art, BASE)
    upper = CellPaint(art, FLAME)
    result = np.zeros(occ.shape, np.uint8)
    pad = np.pad(occ, 1)
    for y, z, x in np.argwhere(occ):
        m = int(mat[y, z, x])
        v = 15 - (y % 16)
        if m in (BOX, FIRE):
            open_z = not pad[y + 1, z, x + 1] or not pad[y + 1, z + 2, x + 1]
            open_x = not pad[y + 1, z + 1, x] or not pad[y + 1, z + 1, x + 2]
            u = z if (open_x and not open_z) else x
            result[y, z, x] = v * 16 + u                  # front projection along its facing
        elif m == CAP:
            result[y, z, x] = upper.pick([GAP], x + .5, 15.5)
        elif m == CAP_EDGE:
            result[y, z, x] = upper.pick([RIM_LIT, GAP], x + .5, 15.5)
        else:
            colours = [ORANGE, RED] if m == TONGUE_CORE else [RED, ORANGE]
            result[y, z, x] = upper.pick(colours, x + .5, v + .5)
    # Front projection must land on drawn cage/fire pixels, never on background.
    lower_px = lower.pixels.reshape(-1, 3)
    bad = [(y, z, x) for y, z, x in np.argwhere(occ[:16])
           if mat[y, z, x] == FIRE and tuple(lower_px[result[y, z, x]]) not in {RED, ORANGE, (255, 230, 173), (230, 197, 123)}]
    if bad:
        raise ValueError(f"fire voxels projected outside the drawn fire: {bad[:5]}")
    return result


def entry_hash(entry):
    return hashlib.sha256(json.dumps(entry, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def generate(table, art):
    occ, mat = design(CellPaint(art, BASE).pixels)
    texels = paint(occ, mat, art)
    return {
        BASE: encode(occ[:16], texels[:16], table["cells"][BASE], source=SOURCE, trim=True),
        FLAME: encode(occ[16:], texels[16:], table["cells"][FLAME], source=SOURCE, trim=True),
    }


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
    common = ("Whole brazier designed as one 16x16 column and cut at y=16 into the existing stack; "
              "interaction metadata, including the upper cell's on-link, preserved. ")
    ledger = [
        {"key": BASE, "kind": "cell", "label": "Battle Pyramid flame brazier cage and fire body",
         "family": "battle-pyramid-flame-brazier", "decision": "changed",
         "reason": common + "Replaces the full-cell extrusion with four lattice corner columns (recessed slat gaps), "
                   "open windows with rail stubs and dark fillets, a studded golden base, a dark fire bed and a "
                   "fire body of revolution sized from the drawn fire rows.",
         "baseline_entry_sha256": entry_hash(table["cells"][BASE]), "occurrences": art.locations[BASE],
         "evidence": ["source.png", "context.png", "current-angled.png", "baseline-angled.png"]},
        {"key": FLAME, "kind": "cell", "label": "Battle Pyramid flame brazier rim and flame tongue",
         "family": "battle-pyramid-flame-brazier", "decision": "changed",
         "reason": common + "Replaces the floor-and-flame extrusion with the cage's dark cap ring (the drawing's "
                   "bottom rim row) and a tapering leaning tongue with two short side tongues; the gray lobby "
                   "floor, tan tile border and floor shading are excluded.",
         "baseline_entry_sha256": entry_hash(table["cells"][FLAME]), "occurrences": art.locations[FLAME],
         "evidence": ["source.png", "context.png", "current-angled.png", "baseline-angled.png"]},
    ]
    write_json(args.out / "updates.json", {"cells": updates, "buildings": {}})
    write_json(args.out / "ledger.json", ledger)
    art.drawing([[FLAME], [BASE]], "BAKED").resize((128, 256), Image.Resampling.NEAREST).save(args.out / "source.png")
    place = art.locations[BASE][0]
    x, y = place["x"] * 16, place["y"] * 16
    art.map_image(place["map"]).crop((x - 48, y - 80, x + 64, y + 32)).resize((448, 448), Image.Resampling.NEAREST).save(args.out / "context.png")
    previews(*assemble(updates, [BASE, FLAME], art, stack=True), args.out, "current", 14)
    previews(*assemble(table["cells"], [BASE, FLAME], art, stack=True), args.out, "baseline", 14)
    print(f"{len(updates)} brazier stack pieces authored -> {args.out}")


if __name__ == "__main__":
    main()
