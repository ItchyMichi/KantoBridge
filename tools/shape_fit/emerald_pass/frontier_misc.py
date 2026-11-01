"""Battle Tower lobby, lifts, lounges and exchange corner: terminals, wall monitor, PC tops.

Source reading (claude-frontier/study): the Battle Tower lobby's yellow reception counters each
end in a terminal drawn over two cells (its teal monitor behind, the keyboard deck in front); a
framed board hangs on the wall's front row; the PC tops of the lobby and exchange corner and the
lifts' back-wall pieces are flat wall art over the wall rows (native columns, amendment). The
PC feet and lounge machine feet are WALK0 floor art (retained).
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
OUT = ROOT / "local/model-gallery/emerald-pass-study/claude-frontier"
AMENDMENTS = Path(__file__).with_name("source_amendments.json")
SOURCE = "hand-emerald-frontier-misc"
WALL_H = 72
K = lambda s: "g3q/" + s

WALL_COLUMNS = {K("0-0-1403-1403"): "lift back wall", K("0-0-1404-1308"): "lift back wall with panel",
                K("341-341-1440-1441"): "Battle Tower PC top on the wall", K("341-341-1528-1529"): "exchange corner PC top on the wall"}
RETAINED = {K(s): l for s, l in [("1442-1443-1260-1261", "Battle Tower PC foot"), ("1258-1625-1256-1257", "wall monitor foot (west)"),
                                  ("1626-1627-1260-1261", "wall monitor stand foot"), ("1628-1259-1256-1257", "wall monitor foot (east)"),
                                  ("1649-1650-1651-1652", "lounge machine foot"), ("1442-1443-1670-1671", "exchange corner PC foot")]}
MONITOR_TOP = K("1287-1288-43-44")
DECKS = [K("868-869-1243-848"), K("1475-1476-848-848")]
MONITOR = [K("1279-1615-290-1616"), K("1617-1618-203-204"), K("1619-1264-1620-290")]
SLATE, FRAME, PALE, STEEL, WALL, STRIPE = (82, 82, 107), (99, 99, 123), (206, 206, 197), (148, 148, 165), (222, 214, 230), (222, 74, 33)


def entry_hash(entry):
    return hashlib.sha256(json.dumps(entry, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def terminal_monitor(cell):
    """Counter end (12 high, its own grey) with the teal monitor standing at its back."""
    yy, zz, xx = np.indices((32, 16, 16))
    counter = (xx >= 1) & (xx < 15) & (yy < 12)
    screen = (xx >= 2) & (xx < 14) & (zz >= 6) & (zz < 11) & (yy >= 12) & (yy < 24)
    screen &= ~((xx >= 4) & (xx < 12) & (zz >= 10) & (yy >= 14) & (yy < 22))
    occ = counter | screen
    up, ox, oz = fur.faces(occ)
    v = (13 - (yy - 12) * 13 // 12).clip(0, 13)
    paint = np.where(screen & oz, v * 16 + xx, 0).astype(np.uint8)
    paint[screen & ~oz] = cell.pick([PALE, FRAME], 8, 2)
    paint[counter] = cell.pick([STEEL, (148, 148, 148), FRAME], 8, 14)
    paint[counter & up] = cell.pick([(214, 214, 181), PALE, STEEL], 8, 12)
    return occ, paint


def terminal_deck(cell):
    """Counter end carrying the terminal's sloped keyboard deck, keys from the drawing above."""
    yy, zz, xx = np.indices((16, 16, 16))
    counter = (xx >= 1) & (xx < 15) & (yy < 12)
    deck = (xx >= 2) & (xx < 14) & (zz < 10) & (yy >= 12) & (yy < 12 + (10 - zz) // 4)
    occ = counter | deck
    up, ox, oz = fur.faces(occ)
    paint = np.where(up, zz * 16 + xx, (15 - yy).clip(0, 15) * 16 + xx).astype(np.uint8)
    paint[counter & ~up] = cell.pick([STEEL, FRAME], 8, 14)
    return occ, paint


def generate(table, art):
    updates = {}
    put = lambda key, occ, paint: updates.__setitem__(key, encode(occ, paint, copy.deepcopy(table["cells"][key]), source=SOURCE, trim=True))
    for key in WALL_COLUMNS:
        updates[key] = {"class": "columns", "iou": 1.0}
    put(MONITOR_TOP, *terminal_monitor(CellPaint(art, MONITOR_TOP)))
    for key in DECKS:
        put(key, *terminal_deck(CellPaint(art, key)))
    cells = [CellPaint(art, k) for k in MONITOR]
    yy, zz, xx = np.indices((WALL_H, 16, 48))
    board = (xx >= 9) & (xx < 39) & (zz >= 15) & (yy >= 2) & (yy < 15)
    for i, key in enumerate(MONITOR):
        part = slice(i * 16, (i + 1) * 16)
        occ = (zz[:, :, part] < 15) | board[:, :, part]
        p = np.full(occ.shape, cells[i].pick([WALL, PALE, FRAME], 8, 1), np.uint8)
        b = board[:, :, part]
        p[b] = ((15 - (yy[:, :, part] - 1)).clip(0, 15) * 16 + xx[:, :, part] - i * 16)[b]
        p[(yy[:, :, part] < 3) & ~b] = cells[i].pick([STRIPE, FRAME], 8, 13)
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
    for key in WALL_COLUMNS:
        spec = {"before": {"class": "voxels"}, "after": {"class": "columns", "iou": 1.0}, "source_pixel_sha256": sha(key),
                "witnesses": witnesses(art, key), "evidence": "claude-frontier/runtime-proof.json: native wall columns replace the notching single layer"}
        if key in data["metadata"] and data["metadata"][key] != spec:
            raise ValueError("a different amendment already exists: " + key)
        data["metadata"][key] = spec
    AMENDMENTS.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def ledger(table, art):
    rows = []
    def row(key, label, decision, reason, family):
        rows.append({"key": key, "kind": "cell", "label": "Battle Frontier " + label, "family": family, "decision": decision, "reason": reason,
                     "baseline_entry_sha256": entry_hash(table["cells"][key]), "occurrences": art.locations.get(key, []),
                     "evidence": ["study/cells.png", "runtime-proof.json"]})
    for k, l in WALL_COLUMNS.items():
        row(k, l, "changed", "Flat wall art over the wall rows: native columns (amendment), folded at the measured height.", "frontier-wall")
    for k, l in RETAINED.items():
        row(k, l, "retained", "WALK0 at every placement: floor wearing the drawn foot; the fitted entry is never used.", "frontier-floor-art")
    row(MONITOR_TOP, "Battle Tower counter terminal monitor", "changed", "Counter end with the framed teal monitor standing at its back.", "frontier-terminal")
    for k in DECKS:
        row(k, "Battle Tower counter terminal deck", "changed", "Counter end carrying the terminal's sloped keyboard deck.", "frontier-terminal")
    for k in MONITOR:
        row(k, "Battle Tower wall monitor", "changed", "Full-height wall section on the wall's front row carrying the framed board, red skirting below.", "frontier-terminal")
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
    assert len(rows) == len({r["key"] for r in rows}) == 16, len(rows)
    write_json(OUT / "updates.json", {"cells": updates, "buildings": {}})
    write_json(OUT / "ledger.json", rows)
    print(f"{len(updates)} changed, {sum(r['decision'] == 'retained' for r in rows)} retained -> {OUT}")


if __name__ == "__main__":
    main()
