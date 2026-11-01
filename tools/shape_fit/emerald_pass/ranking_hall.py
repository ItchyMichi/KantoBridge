"""Battle Frontier Ranking Hall: kiosks, green chairs, trophy case, wall plaques.

Source reading (claude-frontier_ranking_hall/study): a marble hall with terracotta wall blocks.
Blue ranking kiosks stand on solid cells. Each green chair is drawn over two seat-flagged WALK0
cells (backrest over the seat); the planner draws both as seat units in their own footprints,
so the chair is built as one object across the pair: the backrest at the south edge of the north
cell, the seat and arms in the south cell (seat flags and the existing link kept). The trophy case
is a 3x3 drawing: its glass body is the solid middle row, the projected cap and foot rows are
WALK0 floor art (retained). The direction plaques are flat art on the wall blocks: native columns
(amendment). Authored independently of Codex's staged, unintegrated candidate (frontier-ranking/).
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
OUT = ROOT / "local/model-gallery/emerald-pass-study/claude-frontier_ranking_hall"
AMENDMENTS = Path(__file__).with_name("source_amendments.json")
SOURCE = "hand-emerald-ranking-hall"
K = lambda s: "g3q/" + s

KIOSK = K("1746-1747-1748-1749")
CHAIR_BACKS = [K(s) for s in ("1798-1799-1800-1801", "1802-1803-1800-1801", "1734-1735-1804-1805", "1822-1823-1800-1801")]
CHAIR_SEATS = [K(s) for s in ("1800-1801-1810-1811", "1800-1801-1812-1813", "1800-1801-1814-1815", "1800-1801-1828-1829")]
PLAQUES = {K("1761-1761-1830-1831"): "west direction plaque (upper)", K("1761-1761-1806-1808"): "east direction plaque (upper)",
           K("1832-1833-1789-1789"): "west direction plaque (lower)", K("1807-1809-1789-1789"): "east direction plaque (lower)"}
CASE = [K("1710-1769-1712-1771"), K("1770-1773-1772-1775"), K("1774-1711-1776-1713")]
CASE_FLOOR = {K(s): l for s, l in [("1730-1750-1732-1752", "trophy case cap (west)"), ("1751-1751-1753-1753", "trophy case cap"),
                                   ("1754-1731-1755-1733", "trophy case cap (east)"), ("1730-1781-1732-1745", "trophy case foot (west)"),
                                   ("1782-1782-1740-1741", "trophy case foot"), ("1783-1731-1744-1733", "trophy case foot (east)")]}
GREEN, GREEN_D, NAVY, PALE_BLUE, WHITE, ICE = (107, 222, 115), (74, 181, 99), (66, 74, 107), (214, 222, 247), (255, 255, 255), (247, 255, 255)


def entry_hash(entry):
    return hashlib.sha256(json.dumps(entry, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def kiosk(cell):
    yy, zz, xx = np.indices((32, 16, 16))
    body = (xx >= 2) & (xx < 14) & (zz >= 5) & (zz < 11) & (yy < 22)
    cap = (xx >= 1) & (xx < 15) & (zz >= 4) & (zz < 12) & (yy >= 20) & (yy < 22)
    occ = body | cap
    up, ox, oz = fur.faces(occ)
    v = (15 - (yy * 16) // 22).clip(0, 15)
    paint = np.where(oz & ~up, v * 16 + xx, cell.pick([PALE_BLUE, WHITE], 8, 2)).astype(np.uint8)
    paint[occ & (yy < 2)] = cell.pick([NAVY], 8, 15)
    return occ, paint


def chair_back(cell):
    """Backrest at the south edge of the north cell, its front the drawing's rows 6-15."""
    yy, zz, xx = np.indices((16, 16, 16))
    occ = (xx >= 1) & (xx < 15) & (zz >= 12) & (yy < 16)
    up, ox, oz = fur.faces(occ)
    v = (15 - (yy * 10) // 16).clip(6, 15)
    paint = np.where(oz & (zz == 15), v * 16 + xx, cell.pick([GREEN_D, GREEN], 8, 12)).astype(np.uint8)
    paint[up] = cell.pick([GREEN, GREEN_D], 8, 7)
    return occ, paint


def chair_seat(cell):
    """Seat cushion (top from the drawing's rows 0-6), front from rows 7-13, padded arms."""
    yy, zz, xx = np.indices((16, 16, 16))
    seat = (xx >= 1) & (xx < 15) & (zz < 13) & (yy < 7)
    arms = ((xx < 3) | (xx >= 13)) & (xx >= 1) & (xx < 15) & (zz < 12) & (yy < 10)
    occ = seat | arms
    up, ox, oz = fur.faces(occ)
    top = (zz * 7 // 13).clip(0, 6) * 16 + xx
    front = (13 - yy * 7 // 10).clip(7, 13) * 16 + xx
    paint = np.where(up, top, front).astype(np.uint8)
    paint[arms & up] = cell.pick([GREEN, GREEN_D], 2, 3)
    return occ, paint


def case(cells):
    """One glass trophy case over the three body cells: white frame, glass front by projection."""
    yy, zz, xx = np.indices((32, 16, 48))
    body = (xx >= 1) & (xx < 47) & (zz >= 2) & (zz < 14) & (yy < 28)
    occ = body
    up, ox, oz = fur.faces(occ)
    v = (15 - (yy * 16) // 28).clip(0, 15)
    out = []
    for i, cell in enumerate(cells):
        part = slice(i * 16, (i + 1) * 16)
        o = occ[:, :, part]
        p = (v[:, :, part] * 16 + xx[:, :, part] - i * 16).astype(np.uint8)
        frame = o & (up | ox)[:, :, part]
        p[frame] = cell.pick([ICE, WHITE, PALE_BLUE], 8, 2)
        p[o & (yy[:, :, part] < 2)] = cell.pick([NAVY, (99, 99, 123)], 8, 15)
        out.append((o, p))
    return out


def generate(table, art):
    updates = {}
    put = lambda key, occ, paint: updates.__setitem__(key, encode(occ, paint, copy.deepcopy(table["cells"][key]), source=SOURCE, trim=True))
    for key in PLAQUES:
        updates[key] = {"class": "columns", "iou": 1.0}
    put(KIOSK, *kiosk(CellPaint(art, KIOSK)))
    for key in CHAIR_BACKS:
        put(key, *chair_back(CellPaint(art, key)))
    for key in CHAIR_SEATS:
        put(key, *chair_seat(CellPaint(art, key)))
    for key, (o, p) in zip(CASE, case([CellPaint(art, k) for k in CASE])):
        put(key, o, p)
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
    for key in PLAQUES:
        spec = {"before": {"class": "voxels"}, "after": {"class": "columns", "iou": 1.0}, "source_pixel_sha256": sha(key),
                "witnesses": witnesses(art, key), "evidence": "claude-frontier_ranking_hall/runtime-proof.json: native wall columns"}
        if key in data["metadata"] and data["metadata"][key] != spec:
            raise ValueError("a different amendment already exists: " + key)
        data["metadata"][key] = spec
    AMENDMENTS.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def ledger(table, art):
    rows = []
    def row(key, label, decision, reason, family):
        rows.append({"key": key, "kind": "cell", "label": "Ranking Hall " + label, "family": family, "decision": decision, "reason": reason,
                     "baseline_entry_sha256": entry_hash(table["cells"][key]), "occurrences": art.locations.get(key, []),
                     "evidence": ["study/cells.png", "runtime-proof.json"]})
    row(KIOSK, "blue ranking kiosk", "changed", "A standing kiosk 22 high: screen face by projection of the drawing, pale cap, dark plinth.", "ranking-hall-kiosk")
    for k in CHAIR_BACKS:
        row(k, "green chair backrest", "changed", "North cell of the chair (seat flag and link kept): the padded backrest standing at its south edge, "
            "right behind the seat, its front the drawn backrest.", "ranking-hall-chair")
    for k in CHAIR_SEATS:
        row(k, "green chair seat", "changed", "South cell of the chair (seat flag kept): cushion and padded arms, top and front from the drawing.", "ranking-hall-chair")
    for k, l in PLAQUES.items():
        row(k, l, "changed", "Flat sign art on the wall blocks: native columns (amendment), folded at the measured height.", "ranking-hall-wall")
    for k in CASE:
        row(k, "trophy case glass body", "changed", "One glass trophy case over the three solid body cells, 28 high: white frame, glass, shelves and "
            "trophies by projection of each cell's drawing.", "ranking-hall-trophy-case")
    for k, l in CASE_FLOOR.items():
        row(k, l, "retained", "WALK0 projection of the case's cap or foot: floor art; the fitted entry is never used.", "ranking-hall-floor-art")
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
    assert len(rows) == len({r["key"] for r in rows}) == 22, len(rows)
    write_json(OUT / "updates.json", {"cells": updates, "buildings": {}})
    write_json(OUT / "ledger.json", rows)
    print(f"{len(updates)} changed, {sum(r['decision'] == 'retained' for r in rows)} retained -> {OUT}")


if __name__ == "__main__":
    main()
