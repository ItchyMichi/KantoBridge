"""Battle Pyramid lobby, lower level: stair balustrades, the standing screen, the wall monitor.

Source and runtime reading (claude-pyramid/study, native contexts): the lower wall (rows 8-12,
the edge of the raised back area and the dark stair pits) is native and only 2 blocks high,
so these single-layer cells close no notch; they need coherent shapes. The lobby is one level
(elevation 4; elevation-0 cells are "any level").
- Balustrades (x=6/8, rows 8-12): drawn from above, a banister bar with light studs beside the
  walkable stair runner (x=7, WALK). Built as the 2-block stone block they sit in with a raised
  banister and studs, the drawing projected onto the top, its edge columns onto the sides.
- Standing screen (x=5, row 12, SOLID): a teal-framed screen with scan-lines on a post. One
  object in its own cell; the row-11 cell above (its top frame over the dark pit) becomes the
  pit's plain dark 2-block block, so the pit wall stays closed (no stack, no amendment).
- Wall monitor (x=0..2, row 12, SOLID): one framed teal board across three cells on a central
  stand; front by projection of each cell's own drawing, wall background excluded.
- Terminal top (x=13, row 11): behind Codex's shared PC body (g3q/9-10-11-12, 2 blocks, grounded),
  this cell is the low sandstone wall; the drawn PC top is not stacked (it would empty the wall).
Walkable cells drawing the runner and the objects' feet stay floor art (retained).
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
OUT = ROOT / "local/model-gallery/emerald-pass-study/claude-pyramid-lower"
SOURCE = "hand-emerald-pyramid-lobby-lower"

NAVY, SLATE, TAN, MORTAR = (49, 49, 132), (99, 99, 123), (230, 197, 123), (181, 156, 123)
CREAM, DUSK, GRAY, PALE = (255, 230, 173), (90, 82, 123), (140, 123, 123), (206, 206, 197)
LIGHT_GRAY, WHITE = (148, 148, 148), (255, 255, 255)
RAILS = {"g3q/2514-2515-2516-2517": "west stair balustrade (x=6)", "g3q/2519-2520-2521-2522": "east stair balustrade (x=8)"}
PIT_TOP = "g3q/2432-2432-2669-2670"
SCREEN = "g3q/43-44-2671-2672"
TERMINAL_WALL = "g3q/2446-2445-2500-2501"
MONITOR = ["g3q/2446-2673-2443-2674", "g3q/2675-2676-203-204", "g3q/2677-2445-2678-2444"]
RETAINED = {
    "g3q/2518-2518-2518-2518": ("stair runner (x=7, WALK0)", "The game walks the runner on one level; the planner draws it as floor wearing the tread art, which reads as the drawn staircase without lifting the player."),
    "g3q/2533-2534-2459-2460": ("terminal foot drawn on the floor (WALK0)", "Projected foot of the shared PC body, which is already grounded; the walkable cell stays floor art."),
    "g3q/2679-2680-2459-2460": ("standing screen foot drawn on the floor (WALK0)", "Projected foot of the standing screen, which stands on its own post; floor art."),
    "g3q/2457-2681-2459-2460": ("wall monitor west foot (WALK0)", "Floor with the monitor frame's projected corner; the monitor stands in its solid row; floor art."),
    "g3q/2682-2683-2459-2460": ("wall monitor stand foot (WALK0)", "Projected stand foot; the board's own stand is modelled in the solid row; floor art."),
    "g3q/2684-2458-2459-2460": ("wall monitor east foot (WALK0)", "Floor with the monitor frame's projected corner; floor art."),
}


def faces(occ):
    pad = np.pad(occ, 1)
    up = ~pad[2:, 1:-1, 1:-1]
    ox = ~pad[1:-1, 1:-1, :-2] | ~pad[1:-1, 1:-1, 2:]
    oz = ~pad[1:-1, :-2, 1:-1] | ~pad[1:-1, 2:, 1:-1]
    return up, ox, oz


def balustrade(cell, west_rail):
    """Drawn from above (west rail, x across the cell): x1-5 a stone wall with pyramid-capped studs
    every 8 rows, x7-10 the light banister, x11 its shadow, x12-15 the edge of the stair treads.
    The east rail is the mirror image."""
    yy, zz, xx = np.indices((16, 16, 16))
    lx = xx if west_rail else 15 - xx                             # the west rail's own frame
    wall = (lx >= 1) & (lx < 6) & (yy < 9)
    stud = np.zeros_like(wall)
    for cz in (3.5, 11.5):                                        # two pyramid caps per cell
        for k in range(3):
            stud |= (yy == 9 + k) & (np.abs(lx + .5 - 3.5) < 2.5 - k) & (np.abs(zz + .5 - cz) < 2.5 - k)
    banister = (lx >= 7) & (lx < 11) & (yy < 11)
    treads = (lx >= 12) & (yy < 1)
    edge = (lx < 1) | ((lx >= 6) & (lx < 7)) | ((lx >= 11) & (lx < 12))
    occ = wall | stud | banister | treads | (edge & (yy < 8))
    up, ox, oz = faces(occ)
    top = zz * 16 + xx                                            # top surfaces: the drawing from above
    side_u = np.where(lx < 6, np.where(west_rail, 1, 14), np.where(lx < 12, np.where(west_rail, 9, 6), xx))
    side = np.where(ox, zz * 16 + side_u, top)                   # x-facing sides: that part's own column along z
    paint = np.where(up | ~ox, top, side).astype(np.uint8)
    return occ, paint


def plain_block(cell, colours):
    occ = np.ones((16, 16, 16), bool)
    yy, zz, xx = np.indices(occ.shape)
    base, joint = cell.pick(colours, 8, 8), cell.pick([*colours[1:], colours[0]], 8, 0)
    return occ, np.where((yy % 8 == 7) & (len(colours) > 1), joint, base).astype(np.uint8)


def standing_screen(cell):
    """A teal-framed screen with scan-lines, 14 wide and 5 deep, on a post and a round foot."""
    h = 32
    yy, zz, xx = np.indices((h, 16, 16))
    foot = (np.hypot(xx + .5 - 8, zz + .5 - 9) < 3.2) & (yy < 2)
    post = (np.hypot(xx + .5 - 8, zz + .5 - 9) < 1.3) & (yy < 9)
    frame = (xx >= 1) & (xx < 15) & (zz >= 6) & (zz < 11) & (yy >= 8) & (yy < 26)
    occ = foot | post | frame
    occ &= ~((xx >= 3) & (xx < 13) & (zz >= 10) & (yy >= 11) & (yy < 23))      # screen set back 1 in the frame
    up, ox, oz = faces(occ)
    # this cell draws the framed screen in rows 0..13 (its top frame line is in the cell above)
    v = (13 - (yy - 8) * 14 // 18).clip(0, 13)
    paint = (v * 16 + xx).astype(np.uint8)
    paint[foot | post] = cell.pick([LIGHT_GRAY, SLATE], 8, 15)
    paint[frame & ~oz & (up | ox)] = cell.pick([PALE, SLATE], 1, 1)
    return occ, paint


def monitor(cells):
    """One framed board across three cells on a central stand; screen faces south."""
    yy, zz, xx = np.indices((16, 16, 48))
    # the drawn frame spans x 9-38 and rows 1-15 of the three cells: a 30-wide board on two feet
    board = (xx >= 9) & (xx < 39) & (zz >= 7) & (zz < 11) & (yy >= 1) & (yy < 16)
    stand = (((xx >= 12) & (xx < 15)) | ((xx >= 33) & (xx < 36))) & (zz >= 6) & (zz < 12) & (yy < 1)
    occ = board | stand
    occ &= ~((xx >= 12) & (xx < 36) & (zz >= 10) & (yy >= 3) & (yy < 13))     # screen set back in its frame
    up, ox, oz = faces(occ)
    paint = np.zeros(occ.shape, np.uint8)
    v = (16 - yy).clip(1, 15)
    for i, cell in enumerate(cells):
        part = slice(i * 16, (i + 1) * 16)
        u = xx[:, :, part] - i * 16
        paint[:, :, part] = (v[:, :, part] * 16 + u).astype(np.uint8)
        frame = occ[:, :, part] & (up | ox)[:, :, part]
        paint[:, :, part][frame] = cell.pick([SLATE, DUSK], 8, 12)
        stand_part = stand[:, :, part]
        if stand_part.any():
            paint[:, :, part][stand_part] = cell.pick([PALE, LIGHT_GRAY, WHITE], 8, 15)
    # a wall-background texel on the face would show the sandstone behind the board: use the frame
    for i, cell in enumerate(cells):
        part = slice(i * 16, (i + 1) * 16)
        p = paint[:, :, part]
        bg = np.isin(p, [v_ * 16 + u_ for v_ in range(16) for u_ in range(16) if tuple(cell.pixels[v_, u_]) in (TAN, MORTAR)])
        p[bg & occ[:, :, part]] = cell.pick([SLATE, DUSK], 8, 12)
    return occ, paint


def entry_hash(entry):
    return hashlib.sha256(json.dumps(entry, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def generate(table, art):
    updates = {}
    put = lambda k, occ, paint: updates.__setitem__(k, encode(occ, paint, copy.deepcopy(table["cells"][k]), source=SOURCE, trim=True))
    for k in RAILS:
        put(k, *balustrade(CellPaint(art, k), west_rail=k == "g3q/2514-2515-2516-2517"))
    put(PIT_TOP, *plain_block(CellPaint(art, PIT_TOP), [NAVY, SLATE]))
    put(TERMINAL_WALL, *plain_block(CellPaint(art, TERMINAL_WALL), [TAN, MORTAR]))
    put(SCREEN, *standing_screen(CellPaint(art, SCREEN)))
    occ, paint = monitor([CellPaint(art, k) for k in MONITOR])
    for i, k in enumerate(MONITOR):
        put(k, occ[:, :, i * 16:(i + 1) * 16], paint[:, :, i * 16:(i + 1) * 16])
    return updates


def ledger(table, art):
    rows = []
    def row(key, label, decision, reason, family):
        rows.append({"key": key, "kind": "cell", "label": "Battle Pyramid lobby " + label, "family": family, "decision": decision,
                     "reason": reason, "baseline_entry_sha256": entry_hash(table["cells"][key]), "occurrences": art.locations.get(key, []),
                     "evidence": ["../claude-pyramid/study/cells.png", "../claude-pyramid/runtime-proof.json", "runtime-proof.json"]})
    for k, label in RAILS.items():
        row(k, label, "changed", "Replaces the low post blobs: the 2-block stone block between the native pit wall and the walkable runner, "
            "a raised banister bar on the runner side and the drawn light studs in relief; the drawing projects onto the top.", "battle-pyramid-stair-balustrade")
    row(PIT_TOP, "dark stair-pit wall above the standing screen (row 11)", "changed", "Only the screen's top frame is drawn over this cell; the screen is "
        "modelled whole in its own row. Plain dark pit block matching the native 2-block pit walls, so the pit stays closed.", "battle-pyramid-lower-wall")
    row(TERMINAL_WALL, "sandstone wall behind the terminal (row 11)", "changed", "The drawn PC top belongs to Codex's grounded shared PC body in front; "
        "this cell becomes the low sandstone wall matching its native neighbours rather than a blob behind the PC.", "battle-pyramid-lower-wall")
    row(SCREEN, "standing information screen", "changed", "A teal-framed screen with scan-lines (projection of this drawing) set into a pale frame, on a post "
        "and a round foot, 26 voxels high, replacing the extruded frame-and-background mass.", "battle-pyramid-reception-furniture")
    for i, k in enumerate(MONITOR):
        row(k, f"wall monitor ({['west', 'centre', 'east'][i]} third)", "changed", "One framed teal board across the three solid cells on a central stand; "
            "its icons and scan-lines by projection of each cell's own drawing, the sandstone behind it excluded.", "battle-pyramid-reception-furniture")
    for k, (label, reason) in RETAINED.items():
        row(k, label, "retained", reason, "battle-pyramid-lower-floor-art")
    return rows


def main():
    argparse.ArgumentParser(description=__doc__).parse_args()
    table = json.loads(BASELINE.read_text(encoding="utf-8-sig"))
    art = EmeraldArt(BUNDLE)
    updates = generate(table, art)
    write_json(OUT / "updates.json", {"cells": updates, "buildings": {}})
    write_json(OUT / "ledger.json", ledger(table, art))
    print(f"{len(updates)} changed, {len(RETAINED)} retained -> {OUT}")


if __name__ == "__main__":
    main()
