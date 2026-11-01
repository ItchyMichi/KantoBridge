"""Battle Factory (lobby, pre-battle room, battle room): pillars, pipes, consoles, plants, bins.

Source reading (claude-factory/study): an industrial hall. Wall pillars, machine bays and the
pipe runs are flat wall art over the wall rows: their solid placements go back to native columns
(amendment). The lobby's lavender counters are exported COUNTER (collision 144), which the
planner draws as its own plain counter block without fitted entries; the battle room's cells are
all WALK0; the monitor's feet are WALK0: all retained as they are drawn. The pre-battle room's
control consoles, rental-ball racks and corner tables are exported WALK0: walk flag (amendment)
and solid-looking consoles standing as decoration without collision, the user's rule. The lobby
monitor hangs on the wall's front row as a full-height wall section carrying the board; potted
plants (existing frond stacks), the screen on its counter, red sofas on seat cells and the grey
bins (an existing two-cell stack) come from the shared builders.
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
OUT = ROOT / "local/model-gallery/emerald-pass-study/claude-factory"
AMENDMENTS = Path(__file__).with_name("source_amendments.json")
SOURCE = "hand-emerald-battle-factory"
WALL_H = 72
K = lambda s: "g3q/" + s

FLOOR = {(214, 214, 197), (173, 173, 140), (255, 255, 255)}
SLATE, WHITE = (82, 82, 107), (255, 255, 255)
LEAF, LEAF_L, LEAF_D = (156, 197, 74), (197, 239, 148), (107, 165, 41)
FRAME, PALE = (99, 99, 123), (206, 206, 197)

WALL_COLUMNS = {K(s): l for s, l in [
    ("864-865-864-865", "wall pillar"), ("878-878-887-840", "machine bay (west)"), ("878-878-840-840", "machine bay"), ("878-878-840-887", "machine bay (east)"),
    ("872-1126-1127-1128", "pipe run"), ("1129-872-1130-1131", "pipe run"), ("872-1126-873-1132", "pipe run"), ("1126-872-1132-873", "pipe run"),
    ("1126-1129-1132-1130", "pipe run"), ("872-872-1131-873", "pipe run"), ("1126-872-1130-1131", "pipe run"), ("1168-1169-1132-1170", "pipe valve"),
    ("1129-872-1132-873", "pipe run")]}
RETAINED = {K(s): (l, r) for s, l, r in [
    ("843-844-843-844", "lavender counter post", "COUNTER"), ("845-846-847-848", "lavender counter end", "COUNTER"),
    ("846-846-848-848", "lavender counter", "COUNTER"), ("846-849-848-850", "lavender counter end", "COUNTER"),
    ("864-865-851-852", "battle room pillar foot", "WALK"), ("1012-1012-1013-1012", "battle room machine edge", "WALK"),
    ("853-854-1018-1019", "battle room floor panel", "WALK"), ("853-854-1020-1021", "battle room floor panel", "WALK"),
    ("1022-1013-1023-1022", "battle room machine edge", "WALK"), ("1012-1012-1012-1123", "battle room corner", "WALK"),
    ("1123-1124-1124-1125", "battle room corner", "WALK"), ("802-1196-884-799", "monitor foot (west)", "WALK"),
    ("1197-1198-881-882", "monitor stand foot", "WALK"), ("1199-803-798-799", "monitor foot (east)", "WALK")]}
CONSOLES = [K(s) for s in (
    "856-857-858-859", "857-860-859-861", "942-943-944-945", "958-959-960-961", "962-963-964-965", "966-967-968-969", "944-945-958-959",
    "970-971-972-129", "971-973-129-974", "978-979-884-885", "980-981-881-882", "982-983-984-985", "983-986-985-987", "960-961-881-882",
    "946-947-942-943", "950-947-948-949", "950-953-951-952", "988-989-990-991", "992-993-994-995", "996-942-997-944", "943-942-945-944",
    "943-801-945-882", "1004-1005-1006-1007", "1008-1009-1007-1007", "1010-958-1011-960", "959-958-961-960", "959-803-961-885",
    "1119-1120-962-963", "1121-1122-966-967", "964-965-978-979", "968-969-980-981", "946-947-1183-1184", "950-947-1185-993",
    "950-953-1186-129", "1187-991-1004-1005", "994-995-1008-1009", "1188-815-1010-855")]
POTS = [K(s) for s in ("897-898-899-900", "922-898-1181-900", "922-923-899-900", "918-919-920-921", "918-919-920-911")]
FRONDS = [K(s) for s in ("893-894-895-896", "1179-894-1180-896", "924-925-926-927")]
SEATS = [K(s) for s in ("912-913-914-915", "936-937-938-939", "914-915-954-955")]
BIN_TOP, BIN_BASE = K("804-805-806-807"), K("830-831-834-835")
SCREEN = K("862-863-43-44")
SCREEN_COUNTERS = [K("868-869-847-848"), K("870-871-848-850")]
MONITOR = [K("815-1190-855-1191"), K("1192-1193-203-204"), K("1194-815-1195-855")]


def entry_hash(entry):
    return hashlib.sha256(json.dumps(entry, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def merge_parts(parts, h):
    occ = np.zeros((h, 16, 16), bool); paint = np.zeros((h, 16, 16), np.uint8)
    for o, p in parts:
        n = min(h, len(o)); occ[:n] |= o[:n]; paint[:n][o[:n]] = p[:n][o[:n]]
    return occ, paint


def console(cell, height=14):
    """A machine console on the drawing's object footprint (floor excluded, each row filled between
    its outer object pixels so white highlights stay inside): top from above, faces by column."""
    foot = fur.not_floor(cell, FLOOR - {WHITE})
    white = np.array([[tuple(cell.pixels[v, u]) == WHITE for u in range(16)] for v in range(16)])
    obj = fur.not_floor(cell, FLOOR)
    for v in range(16):
        cols = np.nonzero(obj[v])[0]
        if len(cols):
            obj[v, cols.min():cols.max() + 1] = True
    obj &= foot | white
    yy, zz, xx = np.indices((16, 16, 16))
    occ = obj[zz, xx] & (yy < height)
    if not occ.any():
        occ = (yy < 2)
    up, ox, oz = fur.faces(occ)
    v = np.clip(15 - (yy * 16) // max(1, height), 0, 15)
    paint = np.where(up, zz * 16 + xx, v * 16 + np.where(ox & ~oz, zz, xx))
    return occ, paint.astype(np.uint8)


def bin_parts(cell, top):
    """A grey cylindrical bin: the lower body in the base cell, the lid and rim in the stacked top."""
    yy, zz, xx = np.indices((16, 16, 16))
    r = np.hypot(xx + .5 - 8, zz + .5 - 8)
    occ = (r < 6.0) & (yy < (5 if top else 16))
    if top:
        occ |= (r < 6.6) & (yy >= 4) & (yy < 6)
    up, ox, oz = fur.faces(occ)
    paint = np.where(up, zz * 16 + xx, (15 - yy).clip(0, 15) * 16 + xx).astype(np.uint8)
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
    desks = {K(s) for s in ("970-971-972-129", "971-973-129-974", "982-983-984-985", "983-986-985-987")}
    for key in CONSOLES:                       # the white corner desks are white like the floor: whole-cell desks
        c = CellPaint(art, key)
        put(key, *(fur.box(c, 12, inset=1) if key in desks else console(c)), walk=True)
    for key in POTS:
        c = CellPaint(art, key)
        put(key, *fur.pot(c, body=[(148, 148, 165), PALE, SLATE], rim=[WHITE, PALE], soil=[(132, 132, 82), SLATE], height=10))
    for key in FRONDS:
        put(key, *fur.palm(CellPaint(art, key), leaf=[LEAF_L, LEAF], leaf_dark=[LEAF, LEAF_D]))
    for key, ends in zip(SEATS, (("start",), (), ("end",))):
        c = CellPaint(art, key)
        put(key, *fur.sofa(c, ([(255, 99, 49), (222, 74, 33)], [(222, 74, 33), SLATE]), "e", arms_at=ends))
    put(BIN_TOP, *bin_parts(CellPaint(art, BIN_TOP), True))
    put(BIN_BASE, *bin_parts(CellPaint(art, BIN_BASE), False))
    for key in SCREEN_COUNTERS:
        put(key, *fur.mask_counter(CellPaint(art, key), FLOOR, height=12))
    c = CellPaint(art, SCREEN)
    put(SCREEN, *merge_parts([fur.box(c, 6, inset=4, h=32), fur.framed_screen(c, base=6, h=32, rows=(0, 12), x0=2, x1=14, frame=[PALE, FRAME])], 32))
    # Monitor hung on the front row of the native wall: full-height wall sections with the board.
    cells = [CellPaint(art, k) for k in MONITOR]
    yy, zz, xx = np.indices((WALL_H, 16, 48))
    board = (xx >= 9) & (xx < 39) & (zz >= 15) & (yy >= 2) & (yy < 15)
    for i, key in enumerate(MONITOR):
        part = slice(i * 16, (i + 1) * 16)
        occ = (zz[:, :, part] < 15) | board[:, :, part]
        u = xx[:, :, part] - i * 16
        v = (15 - (yy[:, :, part] - 1)).clip(0, 15)
        p = (v * 16 + u).astype(np.uint8)
        wall = cells[i].pick([(255, 255, 255), PALE], 8, 0)
        p[(yy[:, :, part] >= 15) | ~board[:, :, part] & (zz[:, :, part] < 15)] = wall
        stripe = (yy[:, :, part] < 2) & (zz[:, :, part] < 15)
        p[stripe] = cells[i].pick([(206, 99, 58), (247, 140, 99), FRAME], 8, 14)
        put(key, occ, p)
    return updates


def witnesses(art, key):
    out = []
    for p in art.locations[key]:
        src = art.maps[p["map"]]; raw = base64.b64decode(src["cells"]); w = src["w"]
        out.append({"map": p["map"], "x": p["x"], "y": p["y"], "palette": "BAKED", "collision": raw[(p["y"] * w + p["x"]) * 4 + 2]})
    return out


def write_amendments(art):
    sha = lambda k: hashlib.sha256(art.cell(k, "BAKED").convert("RGB").tobytes()).hexdigest()
    data = json.loads(AMENDMENTS.read_text(encoding="utf-8"))
    new = {}
    for key in WALL_COLUMNS:
        new[key] = {"before": {"class": "voxels"}, "after": {"class": "columns", "iou": 1.0}, "source_pixel_sha256": sha(key),
                    "witnesses": witnesses(art, key), "evidence": "claude-factory/runtime-proof.json: native wall columns replace the notching single layer"}
    for key in CONSOLES:
        w = witnesses(art, key); assert all(x["collision"] == 0 for x in w), key
        new[key] = {"before": {"class": "voxels"}, "after": {"class": "voxels", "walk": True}, "source_pixel_sha256": sha(key),
                    "witnesses": w, "evidence": "WALK0 pre-battle console; user decision 2026-11-01: walk-through decoration"}
    for key, spec in new.items():
        if key in data["metadata"] and data["metadata"][key] != spec:
            raise ValueError("a different amendment already exists: " + key)
        data["metadata"][key] = spec
    AMENDMENTS.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def ledger(table, art):
    rows = []
    def row(key, label, decision, reason, family):
        rows.append({"key": key, "kind": "cell", "label": "Battle Factory " + label, "family": family, "decision": decision, "reason": reason,
                     "baseline_entry_sha256": entry_hash(table["cells"][key]), "occurrences": art.locations.get(key, []),
                     "evidence": ["study/cells.png", "runtime-proof.json"]})
    for k, l in WALL_COLUMNS.items():
        row(k, l, "changed", "Flat wall art over the wall rows; its fitted single layer cut the native wall at solid placements. Handed back "
            "to native columns (amendment): the planner folds the drawn wall at the measured height.", "battle-factory-wall")
    for k, (l, why) in RETAINED.items():
        reason = ("Exported COUNTER (collision 144): the planner draws its own plain counter block wearing this art and never uses the fitted "
                  "entry." if why == "COUNTER" else "Exported WALK0 at every placement: floor wearing this art; the fitted entry is never used.")
        row(k, l, "retained", reason, "battle-factory-native")
    for k in CONSOLES:
        row(k, "pre-battle control console", "changed", "Console on the drawing's object footprint (floor excluded, rows filled), 14 high, top and "
            "faces from the drawing. Exported WALK0: walk flag (amendment) so it stands as decoration without collision.", "battle-factory-console")
    for k in POTS:
        row(k, "plant pot", "changed", "Round pot with rim and soil, the base of the plant (existing frond stacks).", "battle-factory-plant")
    for k in FRONDS:
        row(k, "plant fronds (stack top)", "changed", "Existing stack top: arching fronds standing on the pot.", "battle-factory-plant")
    for k in SEATS:
        row(k, "red sofa", "changed", "Seat cell (seat flag kept): a red padded sofa with its back to the east wall.", "battle-factory-seat")
    row(BIN_TOP, "bin lid (stack top)", "changed", "Existing stack top: the lid and rim of a grey cylindrical bin.", "battle-factory-bin")
    row(BIN_BASE, "bin body", "changed", "A grey cylindrical bin body, 2 blocks high, carrying the stacked lid.", "battle-factory-bin")
    for k in SCREEN_COUNTERS:
        row(k, "counter beside the screen", "changed", "Counter on the drawing's exact footprint, 12 high, top and fronts from the drawing.", "battle-factory-counter")
    row(SCREEN, "teal screen on a stand", "changed", "A framed teal screen on a short box stand.", "battle-factory-reception-furniture")
    for k in MONITOR:
        row(k, "wall monitor", "changed", "The monitor hangs on the native wall's front row: a full-height wall section (9 blocks) whose south "
            "face carries the framed board by projection, its wall above and the orange skirting below from this drawing.", "battle-factory-reception-furniture")
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
    assert len(rows) == len({r["key"] for r in rows}) == 83, len(rows)
    write_json(OUT / "updates.json", {"cells": updates, "buildings": {}})
    write_json(OUT / "ledger.json", rows)
    print(f"{len(updates)} changed, {sum(r['decision'] == 'retained' for r in rows)} retained -> {OUT}")


if __name__ == "__main__":
    main()
