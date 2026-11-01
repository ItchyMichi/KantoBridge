"""Battle Arena (lobby, battle room, corridor): dojo walls, tables, bonsai, screens.

Source reading (claude-arena/study): a dojo of tatami stages and stone floors under shoji
walls. Wall rows (shoji bands, beams, posts, panels, the hanging scroll) are flat wall art drawn
over three rows; their fitted single layers cut the native wall, so they are handed back to the
planner's native columns (class "columns", evidenced amendment): the fold paints exactly the
drawn wall at the measured height, continuous with its neighbours. Walkable cells carrying
tatami, stone edges and the terminal's base stay floor art. Furniture is rebuilt from the shared
builders: wooden tables 2 blocks high (the stacked bonsai pairs stand their tops on the table
cell), bonsai in stone trays, the teal screen on its wooden stand (an existing stack), the
monitor on a two-row table, wooden planter boxes along the east wall, green floor cushions.
The corridor's four bonsai planters are on walkable cells: walk flag (amendment), standing as
decoration without collision, as the user decided for the Pyramid statues.
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
OUT = ROOT / "local/model-gallery/emerald-pass-study/claude-arena"
AMENDMENTS = Path(__file__).with_name("source_amendments.json")
SOURCE = "hand-emerald-battle-arena"
K = lambda s: "g3q/" + s

WOOD_D, WOOD_M, WOOD_L, WOOD_Y = (173, 82, 49), (214, 132, 82), (255, 181, 115), (206, 156, 82)
SLATE, STONE, STONE_L = (82, 82, 107), (148, 148, 165), (206, 189, 197)
LEAF, LEAF_L, LEAF_Y, TATAMI, TATAMI_L = (107, 165, 41), (156, 197, 74), (189, 214, 107), (222, 206, 132), (239, 230, 165)
FRAME, PALE = (99, 99, 123), (206, 206, 197)
BONSAI = {"leaf": [LEAF, LEAF_L], "leaf_light": [LEAF_L, LEAF_Y, LEAF], "trunk": [WOOD_D, SLATE], "soil": [SLATE],
          "stone": [STONE, SLATE], "stone_light": [STONE_L, STONE]}

WALL_COLUMNS = {K(s): label for s, label in [
    ("45-45-124-125", "shoji transom band"), ("45-126-125-127", "shoji band beside a post"), ("45-126-128-127", "post and shoji (west)"),
    ("126-45-127-128", "post and shoji (east)"), ("127-129-130-131", "post at the doorway (east)"), ("126-45-127-125", "shoji band beside a post (east)"),
    ("129-127-131-130", "post at the doorway (west)"), ("140-141-145-141", "slatted wall panel (west)"), ("141-142-141-146", "slatted wall panel (east)"),
    ("147-148-149-150", "hanging scroll on the wall")]}
FLOOR_ART = {K(s): label for s, label in [
    ("15-15-15-15", "tatami"), ("20-21-19-22", "stone step edge (west)"), ("23-23-24-24", "stone landing"), ("25-20-26-18", "stone step edge (east)"),
    ("18-19-17-16", "stone floor"), ("63-20-19-18", "stage front with lantern (west)"), ("20-63-19-18", "stage front with lantern (east)"),
    ("20-20-19-18", "stage front"), ("15-15-63-20", "tatami bench front with lantern (west)"), ("15-15-20-20", "tatami bench front"),
    ("15-15-20-63", "tatami bench front with lantern (east)"), ("16-17-161-161", "tatami bench back edge"), ("18-34-17-172", "mat surround (west)"),
    ("35-35-173-174", "mat surround (north)"), ("36-19-175-16", "mat surround (east)"), ("16-186-17-16", "mat surround (south-west)"),
    ("187-188-17-16", "mat surround (south)"), ("189-17-17-16", "mat surround (south-east)"), ("197-198-19-18", "terminal base drawn on the floor")]}
CORRIDOR_PLANTERS = [K(s) for s in ("224-225-226-227", "228-229-230-231", "224-232-226-227", "233-229-230-231")]
TABLE_FRONTS = [K("37-38-39-40"), K("37-37-39-40")]
STACKED_BONSAI = [K("176-177-178-179"), K("182-183-184-185")]          # on the table front, existing links
MONITOR_TOPS = [K("27-199-29-200"), K("201-202-203-204"), K("205-28-206-30")]
MONITOR_FRONTS = [K("37-211-39-212"), K("213-214-212-212"), K("215-38-212-40")]
PLANTER_BOXES = [K("27-28-29-29"), K("190-191-192-193"), K("29-196-29-29")]
FLOOR_BONSAI, TABLE_BONSAI = K("180-181-178-179"), K("194-195-184-185")
SCREEN, STAND, MAT, PC_CAP = K("41-42-43-44"), K("55-56-39-40"), K("31-32-15-15"), K("15-15-170-171")


def entry_hash(entry):
    return hashlib.sha256(json.dumps(entry, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def stack_cells(parts, h):
    """Combine (occ, paint) parts of one cell column, later parts painting over earlier ones."""
    occ = np.zeros((h, 16, 16), bool)
    paint = np.zeros((h, 16, 16), np.uint8)
    for o, p in parts:
        n = min(h, len(o))
        occ[:n] |= o[:n]
        paint[:n][o[:n]] = p[:n][o[:n]]
    return occ, paint


def generate(table, art):
    updates = {}
    def put(key, occ, paint, walk=False):
        original = copy.deepcopy(table["cells"][key])
        if walk:
            original["walk"] = True
        updates[key] = encode(occ, paint, original, source=SOURCE, trim=True)
    for key in WALL_COLUMNS:
        updates[key] = {"class": "columns", "iou": 1.0}
    for key in CORRIDOR_PLANTERS:
        put(key, *fur.bonsai(CellPaint(art, key), base=0, colours=BONSAI), walk=True)
    for key in TABLE_FRONTS:
        put(key, *fur.table(CellPaint(art, key), height=16, front_rows=(0, 15)))
    for key in STACKED_BONSAI:                                     # their layer starts on the table top
        put(key, *fur.bonsai(CellPaint(art, key), base=0, colours=BONSAI))
    for key in MONITOR_FRONTS:
        put(key, *fur.table(CellPaint(art, key), height=16, front_rows=(0, 15)))
    cells = [CellPaint(art, k) for k in MONITOR_TOPS]
    for i, key in enumerate(MONITOR_TOPS):
        t = fur.table(cells[i], height=16, h=32, front_rows=(12, 15))
        yy, zz, xx = np.indices((32, 16, 48))
        board = (xx >= 9) & (xx < 39) & (zz >= 5) & (zz < 9) & (yy >= 16) & (yy < 28)
        board &= ~((xx >= 12) & (xx < 36) & (zz >= 8) & (yy >= 18) & (yy < 26))
        feet = (((xx >= 12) & (xx < 15)) | ((xx >= 33) & (xx < 36))) & (zz >= 4) & (zz < 10) & (yy == 16)
        part = slice(i * 16, (i + 1) * 16)
        mon = (board | feet)[:, :, part]
        v = (11 - (yy - 16) * 11 // 12).clip(1, 11)[:, :, part]
        u = (xx[:, :, part] - i * 16)
        mpaint = (v * 16 + u).astype(np.uint8)
        up, ox, oz = fur.faces(mon)
        mpaint[mon & (up | ox)] = cells[i].pick([FRAME, SLATE], 8, 6)
        put(key, *stack_cells([t, (mon, mpaint)], 32))
    for key in PLANTER_BOXES:
        put(key, *fur.box(CellPaint(art, key), height=10, inset=1))
    c = CellPaint(art, FLOOR_BONSAI)
    put(FLOOR_BONSAI, *stack_cells([fur.box(c, 6, inset=3, h=32), fur.bonsai(c, base=6, h=32, colours=BONSAI)], 32))
    c = CellPaint(art, TABLE_BONSAI)
    put(TABLE_BONSAI, *stack_cells([fur.table(c, height=16, h=32, front_rows=(10, 15)), fur.bonsai(c, base=16, h=32, colours=BONSAI)], 32))
    put(STAND, *fur.table(CellPaint(art, STAND), height=16, legs=False, front_rows=(0, 15)))
    put(SCREEN, *fur.framed_screen(CellPaint(art, SCREEN), base=0, rows=(0, 12), frame=[PALE, FRAME]))
    c = CellPaint(art, MAT)                                        # the drawing's lower half is tatami, not mat
    occ, paint = fur.cushion(c, height=3)
    occ &= fur.not_floor(c, {TATAMI, TATAMI_L, (222, 206, 132)})[np.indices(occ.shape)[1], np.indices(occ.shape)[2]]
    put(MAT, occ, paint)
    c = CellPaint(art, PC_CAP)
    yy, zz, xx = np.indices((16, 16, 16))
    put(PC_CAP, yy < 2, ((zz % 8) * 16 + xx).astype(np.uint8))   # flat tatami slab: rows 0-7 are the tatami
    return updates


def witnesses(art, key):
    out = []
    for p in art.locations[key]:
        raw = base64.b64decode(art.maps[p["map"]]["cells"]); w = art.maps[p["map"]]["w"]
        out.append({"map": p["map"], "x": p["x"], "y": p["y"], "palette": "BAKED", "collision": raw[(p["y"] * w + p["x"]) * 4 + 2]})
    return out


def amendments(art):
    sha = lambda k: hashlib.sha256(art.cell(k, "BAKED").convert("RGB").tobytes()).hexdigest()
    result = {}
    for key in WALL_COLUMNS:
        result[key] = {"before": {"class": "voxels"}, "after": {"class": "columns", "iou": 1.0}, "source_pixel_sha256": sha(key),
                       "witnesses": witnesses(art, key), "evidence": "claude-arena/runtime-proof.json: native wall columns replace the notching single layer"}
    for key in CORRIDOR_PLANTERS:
        w = witnesses(art, key)
        assert all(x["collision"] == 0 for x in w)
        result[key] = {"before": {"class": "voxels"}, "after": {"class": "voxels", "walk": True}, "source_pixel_sha256": sha(key),
                       "witnesses": w, "evidence": "WALK0 corridor planters; user decision 2026-11-01: walk-through decoration"}
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
    def row(key, label, decision, reason, family):
        rows.append({"key": key, "kind": "cell", "label": "Battle Arena " + label, "family": family, "decision": decision, "reason": reason,
                     "baseline_entry_sha256": entry_hash(table["cells"][key]), "occurrences": art.locations.get(key, []),
                     "evidence": ["study/cells.png", "runtime-proof.json"]})
    for k, label in WALL_COLUMNS.items():
        row(k, label, "changed", "Flat wall art drawn across the wall rows; its fitted single layer cut the native wall. Handed back to native "
            "columns (evidenced amendment): the fold paints the drawn wall at the measured height, continuous with its neighbours.", "battle-arena-dojo-wall")
    for k, label in FLOOR_ART.items():
        row(k, label, "retained", "Walkable in the game (WALK0); the planner draws it as floor wearing this art, which is the drawn floor. "
            "Its fitted entry is not used on these cells.", "battle-arena-floor-art")
    for k in CORRIDOR_PLANTERS:
        row(k, "corridor bonsai planter", "changed", "A bonsai drawn on a walkable corridor cell: walk flag (amendment) and a bonsai in a stone "
            "tray standing as decoration without collision, like the hidden game.", "battle-arena-bonsai")
    for k in TABLE_FRONTS + MONITOR_FRONTS:
        row(k, "wooden table", "changed", "A wooden table filling the cell, 2 blocks high so the stacked tops stand on it; grain and legs by "
            "projection of the drawing.", "battle-arena-table")
    for k in STACKED_BONSAI:
        row(k, "bonsai on its table (stacked)", "changed", "Existing stack top: a bonsai in a stone tray standing on the table top, replacing "
            "the extruded table-and-tree mass.", "battle-arena-bonsai")
    for k in MONITOR_TOPS:
        row(k, "monitor on its table", "changed", "Table back half and the framed teal monitor board standing on it, one board across the "
            "three cells; screen by projection of each cell's drawing.", "battle-arena-reception-furniture")
    for k in PLANTER_BOXES:
        row(k, "wooden planter box along the east wall", "changed", "A wooden planter box 10 voxels high; its moss trays and grain from "
            "the drawing seen from above.", "battle-arena-planter-box")
    row(FLOOR_BONSAI, "bonsai on a low stand", "changed", "A bonsai in a stone tray on a low wooden stand.", "battle-arena-bonsai")
    row(TABLE_BONSAI, "bonsai on a table", "changed", "A table with a bonsai standing on it, matching the stacked pairs.", "battle-arena-bonsai")
    row(STAND, "wooden stand of the information screen", "changed", "Existing stack base: a solid wooden stand 2 blocks high.", "battle-arena-reception-furniture")
    row(SCREEN, "information screen on its stand (stacked)", "changed", "Existing stack top: a framed teal screen standing on the wooden stand.", "battle-arena-reception-furniture")
    row(MAT, "green floor cushion", "changed", "A low cushion with the drawn checker on top, replacing the tilted extrusion.", "battle-arena-cushion")
    row(PC_CAP, "tatami behind the terminal", "changed", "The drawn terminal top belongs to the grounded shared PC body in front; this solid "
        "cell becomes a flat tatami slab like its tatami neighbours.", "battle-arena-floor-art")
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
    assert len(rows) == len({r["key"] for r in rows}) == 52
    write_json(OUT / "updates.json", {"cells": updates, "buildings": {}})
    write_json(OUT / "ledger.json", rows)
    print(f"{len(updates)} changed, {sum(r['decision'] == 'retained' for r in rows)} retained -> {OUT}")


if __name__ == "__main__":
    main()
