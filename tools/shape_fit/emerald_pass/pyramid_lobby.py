"""Battle Pyramid lobby back wall: grand columns, four jackal statues in niches, pyramids.

Source and runtime reading (claude-pyramid/study, native context claude-trial/pyramid-native-current):
the back wall (rows 0-3) is a native Tier A wall 9 blocks high, but every fitted cell inside it was
a single 2-block layer, so the planner left 7-block notches where the statues and columns are
drawn. Each jackal statue is drawn over the wall rows 1-3 and stands on a tall pedestal whose
front is in row 4; the grey silhouette above it (row 1) is its cast shadow on the wall. The two
grand columns are drawn over rows 0-5 (navy drums, an orange highlight on the lit side) and stand
on a plinth in row 6. Two small cream pyramids stand in row 4.

Construction (the user's decision of 1 November: niches, walk-through where the game walks):
- wall-row cells of the statue bays (rows 0-2) and the column cells of rows 0-4 become full
  9-block (72-voxel) walls/piers, closing the notches; the bay's row-2 wall is the visible back of
  a niche and carries sandstone courses from its own drawing, statue pixels replaced by wall;
- each statue is one pedestal (front painted by projection of the pedestal drawing) carrying a
  seated jackal, in the row-4 cell; the row-3 cell behind it is the niche, holding a low sill;
- at x=3/x=9 both statue cells are WALK0 in the export: they receive the `walk` flag (evidenced
  metadata amendment) and stand as layered decoration without collision, like the hidden game;
- the row-5 column cell is a round shaft with base and capital, full height, engaged to the
  square pier behind it; the pyramids are stepped square pyramids.
Three cells already agree with their neighbours and are retained.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path

import numpy as np

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.blue_pass.common import encode
from tools.shape_fit.emerald_pass.claude_source_paint import CellPaint

ROOT = Path(__file__).resolve().parents[3]
BASELINE = ROOT / "local/model-gallery/emerald-full-pass-baseline.json"
BUNDLE = ROOT / "local/minecraft/kantobridge/export/emerald"
OUT = ROOT / "local/model-gallery/emerald-pass-study/claude-pyramid"
AMENDMENTS = Path(__file__).with_name("source_amendments.json")
SOURCE = "hand-emerald-pyramid-lobby"
WALL_H = 72                       # the native back wall is 9 blocks high

TAN, MORTAR, GRAY, DUSK = (230, 197, 123), (181, 156, 123), (140, 123, 123), (90, 82, 123)
NAVY, ORANGE, BRONZE, CREAM = (49, 49, 132), (255, 156, 0), (156, 115, 58), (255, 230, 173)
WALL_COLOURS = {TAN}              # the jackal itself is drawn in the darker tan, so only the light stone is wall

BAY_WALLS = {  # key: (label, visible niche back?)
    "g3q/2441-2442-2447-2448": ("statue bay wall with the statue's cast shadow (row 1)", False),
    "g3q/2472-2473-2474-2475": ("statue bay wall behind the jackal's head (row 2), niche back", True),
    "g3q/2455-2438-2456-2440": ("west arch springing over the statue bay (row 0)", False),
    "g3q/2437-2506-2439-2507": ("east arch springing over the statue bay (row 0)", False),
}
PIERS = {
    "g3q/2451-2452-2453-2454": "west grand column capital and arch (row 0)",
    "g3q/2476-2477-2478-2479": "west grand column shaft (row 1)",
    "g3q/2488-2489-2488-2489": "west grand column shaft (rows 2-4)",
    "g3q/2508-2509-2510-2511": "east grand column capital and arch (row 0)",
    "g3q/2527-2528-2529-2530": "east grand column shaft (row 1)",
    "g3q/2536-2537-2536-2537": "east grand column shaft (rows 2-4)",
}
COLUMNS = {
    "g3q/2488-2489-2490-2491": "west grand column, free shaft (row 5)",
    "g3q/2536-2537-2538-2539": "east grand column, free shaft (row 5)",
}
STATUES = {  # pedestal cells (row 4): key -> (label, walk-through?)
    "g3q/2502-2503-2504-2505": ("jackal statue on its pedestal (x=5/11, solid)", False),
    "g3q/2541-2542-2535-2543": ("jackal statue on its pedestal (x=3/9, walkable in the game)", True),
}
NICHES = {  # row-3 cells behind the statues
    "g3q/2484-2485-2486-2487": ("statue niche sill (x=5/11, solid)", False),
    "g3q/2484-2485-2531-2532": ("statue niche sill (x=3/9, walkable in the game)", True),
}
PYRAMID = "g3q/2461-2462-2463-2464"
RETAINED = {
    "g3q/2432-2449-2432-2450": ("west side-bay vault corner (row 0)",
                                "Dark vault corner of the 2-block native side bay; its single layer matches every native neighbour of the bay, so it closes no notch."),
    "g3q/2512-2432-2513-2432": ("east side-bay vault corner (row 0)",
                                "Mirror of the west corner: consistent with the 2-block native side bay."),
    "g3q/2492-2493-2459-2460": ("grand column plinth in front of the shaft (row 6, WALK0)",
                                "The game walks this cell; the planner draws it as floor wearing the plinth art at the round column's foot, which reads correctly."),
}


def yzx(h=WALL_H):
    return np.indices((h, 16, 16))


def facing_u(occ):
    """Per voxel: the drawing column a voxel shows, by the horizontal side it faces."""
    pad = np.pad(occ, 1)
    open_x = ~pad[1:-1, 1:-1, :-2] | ~pad[1:-1, 1:-1, 2:]
    open_z = ~pad[1:-1, :-2, 1:-1] | ~pad[1:-1, 2:, 1:-1]
    yy, zz, xx = np.indices(occ.shape)
    return np.where(open_x & ~open_z, zz, xx)


def wallified(cell):
    """Each drawing address replaced, when it shows the statue, by the nearest wall pixel of its row."""
    px = cell.pixels
    wall = np.array([[tuple(px[v, u]) in WALL_COLOURS for u in range(16)] for v in range(16)])
    result = np.zeros((16, 16), np.uint8)
    joint = [v for v in range(16) if all(tuple(px[v, u]) == MORTAR for u in range(16))]
    for v in range(16):
        if v in joint:                  # a full mortar course line stays a course line
            result[v] = v * 16 + np.arange(16)
            continue
        rows = [v] if wall[v].any() else sorted(range(16), key=lambda r: abs(r - v))
        for u in range(16):
            r = next(r for r in rows if wall[r].any())
            cols = np.nonzero(wall[r])[0]
            result[v, u] = r * 16 + int(cols[np.argmin(np.abs(cols - u))])
    return result


def full_wall(cell, niche_back):
    occ = np.ones((WALL_H, 16, 16), bool)
    yy, zz, xx = yzx()
    v = (WALL_H - 1 - yy) % 16
    u = facing_u(occ)
    table = wallified(cell) if niche_back else (np.arange(256, dtype=np.uint8).reshape(16, 16))
    return occ, table[v, u]


def pier(cell):
    """The column's projection over the wall rows: inside the wall, only its side returns show
    (beside the 2-block native side bays), so they are plain dark masonry with lighter courses."""
    occ = np.ones((WALL_H, 16, 16), bool)
    yy, zz, xx = yzx()
    dark, joint = cell.pick([DUSK], 8, 8), cell.pick([GRAY, DUSK], 8, 8)
    return occ, np.where(yy % 8 == 7, joint, dark).astype(np.uint8)


def round_column(cell):
    yy, zz, xx = yzx()
    r = np.hypot(xx + .5 - 8, zz + .5 - 8)
    occ = (r < 7.6) | ((r < 8.2) & ((yy < 3) | (yy >= WALL_H - 4)))   # shaft, base ring, capital
    occ |= (zz < 4) & (yy >= 0)                                        # engaged to the pier behind
    dx, dz = np.abs(xx + .5 - 8), (zz + .5 - 8)
    u = np.where((dx > np.abs(dz)) & (zz >= 4), zz, xx)
    paint = ((WALL_H - 1 - yy) % 16 * 16 + u).astype(np.uint8)
    ring = (r >= 7.6) & occ & (zz >= 4)
    paint[ring] = cell.pick([NAVY, DUSK], 8, 15)
    return occ, paint


def statue(cell):
    """Pedestal (front by projection of the drawing) and a seated jackal facing south."""
    h = 48
    yy, zz, xx = np.indices((h, 16, 16))
    px, py, pz = xx + .5, yy + .5, zz + .5
    P = 20
    pedestal = (xx >= 1) & (xx < 15) & (zz >= 2) & (zz < 15) & (yy < P)
    cap = (xx >= 1) & (xx < 15) & (zz >= 2) & (zz < 15) & (yy == P - 1)
    ell = lambda c, r: ((px - c[0]) / r[0]) ** 2 + ((py - c[1]) / r[1]) ** 2 + ((pz - c[2]) / r[2]) ** 2 <= 1
    # A seated jackal facing south: broad haunches, upright chest, two forelegs with paws, neck,
    # head with a long snout and two tall ears, broad at the base.
    haunch = ell((8, P + 5, 6.6), (5.0, 5.6, 4.6)) & (yy >= P)
    torso = ell((8, P + 10, 9.4), (3.9, 7.2, 3.3)) & (yy >= P)
    legs = ((np.hypot(px - 6.1, pz - 12.2) < 1.45) | (np.hypot(px - 9.9, pz - 12.2) < 1.45)) & (yy >= P) & (yy < P + 10)
    paws = (yy == P) & (zz >= 12) & (zz < 15) & (((xx >= 5) & (xx < 8)) | ((xx >= 9) & (xx < 12)))
    neck = (np.hypot(px - 8, pz - 10.4) < 2.1) & (yy >= P + 15) & (yy < P + 19)
    head = ell((8, P + 19.5, 10.6), (2.9, 2.7, 3.1))
    snout = (xx >= 7) & (xx < 9) & (yy >= P + 18) & (yy < P + 20) & (zz >= 12) & (zz < 15)
    ears = np.zeros_like(pedestal)
    for ex in (5, 9):
        for k in range(6):
            w = 2 if k < 4 else 1
            ears |= (yy == P + 21 + k) & (zz >= 9) & (zz < 9 + w) & (xx >= ex + (k >= 4) * (ex == 5)) & (xx < ex + (k >= 4) * (ex == 5) + w)
    jackal = haunch | torso | legs | paws | neck | head | snout | ears
    occ = pedestal | jackal
    paint = np.zeros(occ.shape, np.uint8)
    # Pedestal: the drawing's pedestal rows (0..12; 12 is its foot shade), courses repeated above.
    v = np.where(yy <= 12, 12 - yy, 20 - yy).clip(0, 15)
    u = facing_u(occ)
    paint[pedestal] = (v * 16 + u)[pedestal]
    paint[cap & ~jackal] = cell.pick([TAN], 8, 1)
    collar = jackal & (yy >= P + 15) & (yy < P + 17)
    for (y, z, x) in np.argwhere(jackal):
        if collar[y, z, x]:
            colours = [ORANGE]
        elif ears[y, z, x] or (snout[y, z, x] and z == 14):
            colours = [DUSK]
        elif not occ[min(y + 1, h - 1), z, x] or y == h - 1:
            colours = [MORTAR, GRAY]        # lit top surfaces
        else:
            colours = [GRAY]
        paint[y, z, x] = cell.pick(colours, x + .5, 6)
    return occ, paint


def sill(cell):
    occ = np.zeros((16, 16, 16), bool)
    occ[:2, :, :] = True
    paint = np.full(occ.shape, cell.pick([TAN], 8, 14), np.uint8)
    return occ, paint


def pyramid(cell):
    yy, zz, xx = np.indices((16, 16, 16))
    half = 7 - yy * .55
    dx, dz = np.abs(xx + .5 - 8), np.abs(zz + .5 - 8)
    occ = (dx < half) & (dz < half) & (half > 0)
    paint = np.zeros(occ.shape, np.uint8)
    light = (zz + .5 - 8 >= np.abs(xx + .5 - 8)) | (xx + .5 - 8 >= np.abs(zz + .5 - 8))
    top = np.zeros_like(occ)
    top[:-1] = occ[:-1] & ~occ[1:]
    top[-1] = occ[-1]
    apex = occ & (yy == np.nonzero(occ.any(axis=(1, 2)))[0].max())
    for (y, z, x) in np.argwhere(occ):
        colours = [NAVY, DUSK] if apex[y, z, x] else ([CREAM] if light[y, z, x] else [MORTAR])
        paint[y, z, x] = cell.pick(colours, x + .5, 15 - y)
    return occ, paint


def entry_hash(entry):
    return hashlib.sha256(json.dumps(entry, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def source_sha(art, key):
    return hashlib.sha256(art.cell(key, "BAKED").convert("RGB").tobytes()).hexdigest()


def generate(table, art):
    updates = {}
    def put(key, occ, paint, walk=False):
        original = copy.deepcopy(table["cells"][key])
        if walk:
            original["walk"] = True
        updates[key] = encode(occ, paint, original, source=SOURCE, trim=True)
    for key, (_, niche_back) in BAY_WALLS.items():
        put(key, *full_wall(CellPaint(art, key), niche_back))
    for key in PIERS:
        put(key, *pier(CellPaint(art, key)))
    for key in COLUMNS:
        put(key, *round_column(CellPaint(art, key)))
    for key, (_, walk) in STATUES.items():
        put(key, *statue(CellPaint(art, key)), walk=walk)
    for key, (_, walk) in NICHES.items():
        put(key, *sill(CellPaint(art, key)), walk=walk)
    put(PYRAMID, *pyramid(CellPaint(art, PYRAMID)))
    return updates


def amendments(art):
    """The two walk-through statue cells: exact before/after metadata and every WALK0 witness."""
    result = {}
    for key in [k for k, (_, w) in {**STATUES, **NICHES}.items() if w]:
        places = art.locations[key]
        witnesses = []
        for p in places:
            raw = __import__("base64").b64decode(art.maps[p["map"]]["cells"])
            w = art.maps[p["map"]]["w"]
            witnesses.append({"map": p["map"], "x": p["x"], "y": p["y"], "palette": "BAKED",
                              "collision": raw[(p["y"] * w + p["x"]) * 4 + 2]})
        assert all(w["collision"] == 0 for w in witnesses), key
        result[key] = {"before": {"class": "voxels"}, "after": {"class": "voxels", "walk": True},
                       "source_pixel_sha256": source_sha(art, key), "witnesses": witnesses,
                       "evidence": "claude-pyramid/runtime-proof.json (source_collisions WALK0); user decision 2026-11-01: walk-through statues"}
    return result


def write_amendments(art):
    data = json.loads(AMENDMENTS.read_text(encoding="utf-8"))
    for key, spec in amendments(art).items():
        if key in data["metadata"] and data["metadata"][key] != spec:
            raise ValueError("a different amendment already exists: " + key)
        data["metadata"][key] = spec
    AMENDMENTS.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def ledger(table, art):
    rows = []
    def row(key, label, decision, reason, family="battle-pyramid-lobby-architecture"):
        rows.append({"key": key, "kind": "cell", "label": "Battle Pyramid lobby " + label, "family": family,
                     "decision": decision, "reason": reason, "baseline_entry_sha256": entry_hash(table["cells"][key]),
                     "occurrences": art.locations.get(key, []),
                     "evidence": ["study/cells.png", "runtime-proof.json", "../../claude-trial/pyramid-native-current/sheets"]})
    for key, (label, niche_back) in BAY_WALLS.items():
        row(key, label, "changed", "Native context: the fitted single layer cut a 7-block notch into the 9-block native back wall. "
            "Now a full 72-voxel wall" + (" whose south face is the statue niche's back, sandstone courses from this drawing with the "
            "jackal's pixels replaced by the nearest wall pixel of their row (the statue itself stands on its pedestal)." if niche_back
            else "; the drawn statue shadow / arch art stays inside the wall mass."))
    for key, label in PIERS.items():
        row(key, label, "changed", "The drawn grand column's projection over the wall rows. Its fitted single layer left a notch and "
            "a low box; now a full-height square pier (72 voxels) painted by projection of its navy drums, continuous with the wall "
            "and engaged to the round shaft in row 5.")
    for key, label in COLUMNS.items():
        row(key, label, "changed", "The free part of the grand column: a round shaft (radius 7.6) with base ring and capital, full "
            "9-block height, engaged to the pier behind; drums and the lit-side highlight by projection of this drawing.")
    for key, (label, walk) in STATUES.items():
        row(key, label, "changed", "Whole statue in its pedestal cell: a 20-voxel pedestal (front, sides and courses by projection of "
            "this drawing) and a seated jackal (haunches, chest, forelegs, head, snout, pointed ears, orange collar), 44 voxels in "
            "all, standing in front of the niche." + (" Exported WALK0: walk flag added (amendment) so it stands as layered "
            "decoration without collision, as the hidden game walks it." if walk else ""), family="battle-pyramid-jackal-statue")
    for key, (label, walk) in NICHES.items():
        row(key, label, "changed", "Row behind the pedestal: the statue niche, between the 9-block native walls and in front of the "
            "full-height bay wall. A 2-voxel sandstone sill replaces the low statue-body box." + (" Exported WALK0: walk "
            "flag added so the floor is clean ground and the sill is decoration." if walk else " Solid in the game: the planner's "
            "barriers keep its collision."), family="battle-pyramid-jackal-statue")
    row(PYRAMID, "small stepped pyramid ornament", "changed", "The drawn cream triangle is a free-standing square pyramid on the "
        "floor: 14 voxels square, 13 high in 0.55-voxel steps, lit south/east faces cream, shaded faces darker, navy apex.",
        family="battle-pyramid-ornament")
    for key, (label, reason) in RETAINED.items():
        row(key, label, "retained", reason)
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--write-amendments", action="store_true")
    args = ap.parse_args()
    table = json.loads(BASELINE.read_text(encoding="utf-8-sig"))
    art = EmeraldArt(BUNDLE)
    if args.write_amendments:
        write_amendments(art)
    updates = generate(table, art)
    write_json(OUT / "updates.json", {"cells": updates, "buildings": {}})
    write_json(OUT / "ledger.json", ledger(table, art))
    print(f"{len(updates)} changed, {len(RETAINED)} retained -> {OUT}")


if __name__ == "__main__":
    main()
