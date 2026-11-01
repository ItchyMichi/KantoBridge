"""Battle Dome (lobby, pre-battle room, corridor): lift walls, counters, ropes, palms, sofas.

Source reading (claude-dome/study): a bright hall with mint floors. Lift assemblies and wall
panels are flat wall art over the wall rows: their solid placements are handed back to native
columns (amendment), like the Arena. The corridor's wall art is exported WALK0 throughout, so its
fitted entries are never used there (retained). Furniture is rebuilt from the shared builders on
the drawing's exact non-floor footprints: yellow reception counters (the L partitions 2 blocks
high), rope stanchions, the red capsule and the teal screen standing on their counters, the
monitor board on its counter, potted palms (existing stacks of fronds on pots), the PC top cap
stacked on Codex's shared PC body (amendment, as Codex did in the Contest Hall), sofas and a
bench on the pre-battle room's seat cells, and its yellow table on WALK0 cells as walk-through
decoration (walk flag, the user's rule for objects the game walks through).
"""
from __future__ import annotations

import argparse
import base64
import copy
import hashlib
import json
from pathlib import Path

import numpy as np

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.blue_pass.common import encode
from tools.shape_fit.emerald_pass import claude_furniture as fur
from tools.shape_fit.emerald_pass.claude_source_paint import CellPaint

ROOT = Path(__file__).resolve().parents[3]
BASELINE = ROOT / "local/model-gallery/emerald-full-pass-baseline.json"
BUNDLE = ROOT / "local/minecraft/kantobridge/export/emerald"
OUT = ROOT / "local/model-gallery/emerald-pass-study/claude-dome"
AMENDMENTS = Path(__file__).with_name("source_amendments.json")
SOURCE = "hand-emerald-battle-dome"
PC_BODY = "g3q/9-10-11-12"
K = lambda s: "g3q/" + s

FLOOR = {(140, 206, 173), (156, 239, 206), (123, 173, 140), (156, 181, 197), (173, 206, 214), (140, 156, 181)}
SLATE, STONE, STONE_L, WHITE = (82, 82, 107), (148, 148, 165), (206, 189, 197), (255, 255, 255)
YELLOW, ROPE, ROPE_L = (255, 222, 132), (189, 41, 16), (222, 74, 33)
LEAF, LEAF_L, LEAF_D, OLIVE = (156, 197, 74), (197, 239, 148), (107, 165, 41), (189, 148, 49)
SOFA, SOFA_D, RED, PINK = (255, 99, 49), (222, 74, 33), (247, 8, 8), (255, 123, 99)
FRAME, PALE = (99, 99, 123), (206, 206, 197)

WALL_COLUMNS = {K(s): l for s, l in [
    ("252-253-254-255", "lift header sign"), ("271-272-273-274", "closed lift doors"), ("281-309-281-310", "lift frame (west)"),
    ("311-282-312-282", "lift frame (east)"), ("281-282-281-289", "wall panel above the lift (west)"), ("281-282-288-289", "wall panel above the lift"),
    ("281-282-288-282", "wall panel above the lift (east)"), ("288-313-290-314", "lift frame foot (west)"), ("315-289-316-290", "lift frame foot (east)"),
    ("256-257-258-259", "ceiling light over the lift (pre-battle room)"), ("275-276-277-278", "lift door"),
    ("264-359-281-360", "corridor door frame (west)"), ("361-361-258-259", "corridor door header"), ("362-265-363-282", "corridor door frame (east)"),
    ("288-368-290-369", "door frame foot (west)"), ("370-289-371-290", "door frame foot (east)")]}
CORRIDOR_WALK = {K(s): l for s, l in [("327-328-329-330", "stand seats (west)"), ("331-332-333-334", "stand seats with pillar top"),
    ("335-336-337-338", "stand seats (east)"), ("349-350-351-352", "Y-pillar shaft"), ("355-356-357-358", "Y-pillar foot"), ("364-365-366-367", "pillar base")]}
PC_FOOT = K("775-776-703-777")
COUNTERS_LOW = [K(s) for s in ("396-396-415-415", "439-440-415-415", "374-438-755-415", "451-375-415-756", "697-698-736-499", "697-698-495-742",
                                "374-396-755-415", "773-774-415-415", "396-375-415-756", "402-375-415-756", "397-398-415-756", "397-398-755-415", "374-433-790-415")]
PARTITIONS = [K(s) for s in ("695-418-502-380", "764-702-382-765", "697-381-495-390", "384-772-374-375", "695-391-502-401", "697-376-782-420",
                             "421-700-422-783", "788-424-374-375", "425-702-789-777", "427-700-428-783")]
STANCHIONS = [K(s) for s in ("489-490-491-492", "493-494-495-496", "497-498-496-499", "500-501-502-503", "504-490-505-492", "506-498-507-499")]
POTS = [K(s) for s in ("544-545-546-547", "584-585-586-587", "544-636-546-637", "669-670-671-672", "720-721-722-723", "780-670-781-672")]
FRONDS = [K(s) for s in ("520-521-522-523", "563-564-565-566", "603-604-605-606", "651-652-653-654", "716-717-718-719", "778-652-779-654")]
BENCH = {K("693-686-715-688"): "w", K("724-725-726-727"): "w", K("743-744-745-746"): "w", K("757-758-712-707"): "w"}
SOFAS = {K("728-729-730-731"): "e", K("751-752-753-754"): "e", K("759-760-749-750"): "e", K("747-748-749-750"): "e"}
TABLE = [[K("704-732-706-374"), K("396-733-734-375")], [K("685-374-687-374"), K("734-375-734-375")], [K("704-374-706-761"), K("734-375-762-763")]]
PC_CAP, CAPSULE, SCREEN = K("697-698-766-767"), K("768-769-770-771"), K("784-785-43-44")
MONITOR = [K("697-735-736-737"), K("738-739-203-204"), K("740-698-741-742")]


def entry_hash(entry):
    return hashlib.sha256(json.dumps(entry, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def merge_parts(parts, h):
    occ = np.zeros((h, 16, 16), bool); paint = np.zeros((h, 16, 16), np.uint8)
    for o, p in parts:
        n = min(h, len(o)); occ[:n] |= o[:n]; paint[:n][o[:n]] = p[:n][o[:n]]
    return occ, paint


def table_over_cells(cells):
    """One low yellow table over a 2x3 group of cells: slab, corner legs only, sliced per cell."""
    rows, cols = len(cells), len(cells[0])
    yy, zz, xx = np.indices((16, rows * 16, cols * 16))
    slab = (yy >= 5) & (yy < 7) & (xx >= 1) & (xx < cols * 16 - 1) & (zz >= 2) & (zz < rows * 16 - 2)
    legs = ((xx < 3) | (xx >= cols * 16 - 3)) & ((zz < 4) | (zz >= rows * 16 - 4)) & (yy < 7) & (xx >= 1) & (xx < cols * 16 - 1) & (zz >= 2) & (zz < rows * 16 - 2)
    occ = slab | legs
    up, _, _ = fur.faces(occ)
    out = {}
    for r in range(rows):
        for c in range(cols):
            cell = cells[r][c]
            part = (slice(None), slice(r * 16, r * 16 + 16), slice(c * 16, c * 16 + 16))
            o = occ[part]
            p = np.where(up[part], (zz[part] - r * 16) * 16 + (xx[part] - c * 16), cell.pick([SLATE], 8, 15)).astype(np.uint8)
            top_bad = ~np.isin(p, [v * 16 + u for v in range(16) for u in range(16) if tuple(cell.pixels[v, u]) in (YELLOW, WHITE)])
            p[o & up[part] & top_bad] = cell.pick([YELLOW], 8, 8)
            out[(r, c)] = (o, p)
    return out


def generate(table, art):
    updates = {}
    def put(key, occ, paint, walk=False, on=None):
        original = copy.deepcopy(table["cells"][key])
        if walk:
            original["walk"] = True
        if on:
            original["on"] = on
        updates[key] = encode(occ, paint, original, source=SOURCE, trim=True)
    for key in WALL_COLUMNS:
        updates[key] = {"class": "columns", "iou": 1.0}
    for key in COUNTERS_LOW:
        put(key, *fur.mask_counter(CellPaint(art, key), FLOOR, height=12))
    for key in PARTITIONS:
        put(key, *fur.mask_counter(CellPaint(art, key), FLOOR, height=16))
    for key in STANCHIONS:
        put(key, *fur.stanchions(CellPaint(art, key), FLOOR, post=[STONE_L, WHITE, STONE], rope=[ROPE, ROPE_L]))
    for key in POTS:
        put(key, *fur.pot(CellPaint(art, key), body=[STONE_L, STONE], rim=[WHITE, STONE_L], soil=[OLIVE, SLATE], height=10))
    for key in FRONDS:
        put(key, *fur.palm(CellPaint(art, key), leaf=[LEAF_L, LEAF], leaf_dark=[LEAF, LEAF_D]))
    arms = {K("693-686-715-688"): ("start",), K("724-725-726-727"): (), K("743-744-745-746"): (), K("757-758-712-707"): ("end",),
            K("751-752-753-754"): ("start",), K("759-760-749-750"): ("end",)}
    for key, side in {**BENCH, **SOFAS}.items():
        c = CellPaint(art, key)
        colours = ([SOFA, SOFA_D], [SOFA_D, SLATE]) if key in SOFAS else ([STONE_L, WHITE, STONE], [STONE, SLATE])
        put(key, *fur.sofa(c, colours, side, arms_at=arms.get(key, ("start", "end"))))
    grid = [[CellPaint(art, k) for k in row] for row in TABLE]
    for (r, c), (o, p) in table_over_cells(grid).items():
        put(TABLE[r][c], o, p, walk=True)
    # PC top cap stacked on the shared PC body: a pale housing above the monitor.
    c = CellPaint(art, PC_CAP)
    yy, zz, xx = np.indices((16, 16, 16))
    cap = (xx >= 2) & (xx < 14) & (zz >= 3) & (zz < 12) & (yy < 6)
    up, _, _ = fur.faces(cap)
    put(PC_CAP, cap, np.where(up, c.pick([WHITE, PALE], 8, 4), c.pick([PALE, STONE_L, STONE], 8, 10)).astype(np.uint8), on=PC_BODY)
    # Red capsule standing on its counter.
    c = CellPaint(art, CAPSULE)
    base = fur.mask_counter(c, FLOOR, height=12, h=32)
    yy, zz, xx = np.indices((32, 16, 16))
    r = np.hypot(xx + .5 - 8, zz + .5 - 8)
    dome = (r < 5.5) & (yy >= 12) & (yy < 16) | ((xx + .5 - 8) ** 2 + (zz + .5 - 8) ** 2 + ((yy + .5 - 16) * 1.2) ** 2 <= 25) & (yy >= 16)
    up, _, _ = fur.faces(dome)
    dp = np.where(up & (yy >= 18), c.pick([PINK, RED], 8, 4), np.where(yy < 16, c.pick([STONE_L, WHITE], 8, 12), c.pick([RED, PINK], 8, 8))).astype(np.uint8)
    put(CAPSULE, *merge_parts([base, (dome, dp)], 32))
    # Teal screen standing on the counter end.
    c = CellPaint(art, SCREEN)
    put(SCREEN, *merge_parts([fur.mask_counter(c, FLOOR, height=12, h=32), fur.framed_screen(c, base=12, h=32, rows=(0, 12), x0=2, x1=14, frame=[PALE, FRAME])], 32))
    # Monitor board on its counter, across three cells.
    cells = [CellPaint(art, k) for k in MONITOR]
    yy, zz, xx = np.indices((32, 16, 48))
    board = (xx >= 9) & (xx < 39) & (zz >= 5) & (zz < 9) & (yy >= 12) & (yy < 24)
    board &= ~((xx >= 12) & (xx < 36) & (zz >= 8) & (yy >= 14) & (yy < 22))
    for i, key in enumerate(MONITOR):
        part = slice(i * 16, (i + 1) * 16)
        mon = board[:, :, part]
        v = (11 - (yy[:, :, part] - 12) * 11 // 12).clip(1, 11)
        mp = (v * 16 + xx[:, :, part] - i * 16).astype(np.uint8)
        up, ox, _ = fur.faces(mon)
        mp[mon & (up | ox)] = cells[i].pick([FRAME, SLATE], 8, 6)
        put(key, *merge_parts([fur.mask_counter(cells[i], FLOOR, height=12, h=32), (mon, mp)], 32))
    return updates


def witnesses(art, key, below=None):
    out = []
    for p in art.locations[key]:
        src = art.maps[p["map"]]; raw = base64.b64decode(src["cells"]); w = src["w"]
        row = {"map": p["map"], "x": p["x"], "y": p["y"], "palette": "BAKED", "collision": raw[(p["y"] * w + p["x"]) * 4 + 2]}
        if below:
            grid = art.map_cells(src)
            row.update(below_key=grid[p["y"] + 1][p["x"]], below_collision=raw[((p["y"] + 1) * w + p["x"]) * 4 + 2])
        out.append(row)
    return out


def write_amendments(art):
    sha = lambda k: hashlib.sha256(art.cell(k, "BAKED").convert("RGB").tobytes()).hexdigest()
    data = json.loads(AMENDMENTS.read_text(encoding="utf-8"))
    new_meta = {}
    for key in WALL_COLUMNS:
        new_meta[key] = {"before": {"class": "voxels"}, "after": {"class": "columns", "iou": 1.0}, "source_pixel_sha256": sha(key),
                         "witnesses": witnesses(art, key), "evidence": "claude-dome/runtime-proof.json: native wall columns replace the notching single layer"}
    for key in [k for row in TABLE for k in row]:
        w = witnesses(art, key); assert all(x["collision"] == 0 for x in w)
        new_meta[key] = {"before": {"class": "voxels"}, "after": {"class": "voxels", "walk": True}, "source_pixel_sha256": sha(key),
                         "witnesses": w, "evidence": "WALK0 pre-battle table; user decision 2026-11-01: walk-through decoration"}
    w = witnesses(art, PC_CAP, below=True); assert all(x["below_key"] == PC_BODY for x in w)
    new_stack = {PC_CAP: {"before": {}, "after": {"on": PC_BODY}, "evidence": "Dome lobby PC top above the shared PC body; Codex's Contest cap precedent", "witnesses": w}}
    for section, items in (("metadata", new_meta), ("stacks", new_stack)):
        for key, spec in items.items():
            if key in data[section] and data[section][key] != spec:
                raise ValueError("a different amendment already exists: " + key)
            data[section][key] = spec
    AMENDMENTS.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def ledger(table, art):
    rows = []
    def row(key, label, decision, reason, family):
        rows.append({"key": key, "kind": "cell", "label": "Battle Dome " + label, "family": family, "decision": decision, "reason": reason,
                     "baseline_entry_sha256": entry_hash(table["cells"][key]), "occurrences": art.locations.get(key, []),
                     "evidence": ["study/cells.png", "runtime-proof.json"]})
    for k, l in WALL_COLUMNS.items():
        row(k, l, "changed", "Flat wall art over the wall rows; at its solid placements the fitted single layer cut the native wall. Handed back "
            "to native columns (amendment): the planner folds the drawn wall at the measured height.", "battle-dome-wall")
    for k, l in CORRIDOR_WALK.items():
        row(k, "corridor " + l, "retained", "Exported WALK0 at every placement, so the planner draws floor wearing this art and never uses the "
            "fitted entry; nothing to change here.", "battle-dome-floor-art")
    row(PC_FOOT, "PC foot drawn on the floor", "retained", "WALK0 projected foot of the grounded shared PC body; floor art.", "battle-dome-floor-art")
    for k in COUNTERS_LOW:
        row(k, "reception counter section", "changed", "Counter on exactly the drawing's non-floor footprint, 12 high, flat yellow top from above, "
            "striped fronts from the drawn columns; replaces the depth-noise extrusion.", "battle-dome-counter")
    for k in PARTITIONS:
        row(k, "curved reception partition section", "changed", "The tall L partition on the drawing's footprint, 2 blocks high, top and faces "
            "from the drawing.", "battle-dome-counter")
    for k in STANCHIONS:
        row(k, "rope barrier", "changed", "Round silver posts where the drawing shows them and a red rope sagging between, replacing the slabs.", "battle-dome-rope")
    for k in POTS:
        row(k, "palm pot", "changed", "Round pot with rim and soil, the base of the existing palm stack.", "battle-dome-palm")
    for k in FRONDS:
        row(k, "palm fronds (stack top)", "changed", "Existing stack top: seven arching fronds from a short crown, standing on the pot.", "battle-dome-palm")
    for k in BENCH:
        row(k, "pre-battle bench along the west wall", "changed", "Seat cell (seat flag kept): a padded bench with its back to the wall.", "battle-dome-seat")
    for k in SOFAS:
        row(k, "pre-battle red sofa", "changed", "Seat cell (seat flag kept): a red padded sofa with back and arms.", "battle-dome-seat")
    for k in [k for r in TABLE for k in r]:
        row(k, "pre-battle yellow table", "changed", "One low yellow table over the 2x3 WALK0 cells (corner legs only); walk flag (amendment) so it "
            "stands as decoration without collision, like the hidden game.", "battle-dome-table")
    row(PC_CAP, "PC top cap", "changed", "Stacked on the shared PC body (amendment; Codex's Contest precedent): a pale housing over the monitor, "
        "instead of a blob behind it.", "battle-dome-reception-furniture")
    row(CAPSULE, "red capsule on the counter", "changed", "Counter section with the red-and-white capsule dome standing on it.", "battle-dome-reception-furniture")
    row(SCREEN, "teal screen on the counter end", "changed", "Counter section with the framed teal screen standing on it.", "battle-dome-reception-furniture")
    for k in MONITOR:
        row(k, "monitor on the counter", "changed", "Counter section with one framed teal board across the three cells.", "battle-dome-reception-furniture")
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
    rows = ledger(table, art)
    assert len(rows) == len({r["key"] for r in rows}) == 84, len(rows)
    write_json(OUT / "updates.json", {"cells": updates, "buildings": {}})
    write_json(OUT / "ledger.json", rows)
    print(f"{len(updates)} changed, {sum(r['decision'] == 'retained' for r in rows)} retained -> {OUT}")


if __name__ == "__main__":
    main()
