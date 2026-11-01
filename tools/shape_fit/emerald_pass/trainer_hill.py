"""Trainer Hill entrance and floors 1F-4F: stairwells, wall box and control console.

Source reading (claude-trainer/study): every floor has an ascending and a descending stairwell
set into the back wall, a wall control box, and a 3x3 control console in the room (its centre
button cell varies per floor); the entrance has the reception lift and an information stand.
Stairwell frames and the wall box are flat wall art over the wall rows: native columns
(amendment), so the planner's doorway fold handles the openings. The entrance's lift and
information stand lie inside Codex's frozen native reception context, whose proof binds their
entries: retained unchanged. The two warp
stair cells keep their models (one existing walk stair) and warp behaviour. The console is one
object across its nine solid cells: a tall white louvred back unit, a desk top carrying the
button cross in relief, and the desk edge with the blue front band; all faces from each cell's
own drawing. Authored independently of Codex's frozen trainer-hill-architecture package, which
remains unintegrated evidence.
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
OUT = ROOT / "local/model-gallery/emerald-pass-study/claude-trainer"
AMENDMENTS = Path(__file__).with_name("source_amendments.json")
SOURCE = "hand-emerald-trainer-hill"
K = lambda s: "g3q/" + s

WALL_COLUMNS = {K(s): l for s, l in [
    ("9613-9614-9601-9601", "wall control box"), ("9581-9615-9583-9616", "stairwell frame (west)"), ("9617-9617-9618-9619", "stairwell lintel"),
    ("9620-9582-9621-9584", "stairwell frame (east)"), ("9599-9633-9601-9634", "stairwell jamb (west)"), ("9637-9600-9638-9601", "stairwell jamb (east)"),
    ("9738-9615-9740-9616", "entrance stairwell frame (west)"), ("9620-9739-9621-9741", "entrance stairwell frame (east)"),
    ("9747-9633-9749-9634", "entrance stairwell jamb (west)"), ("9637-9748-9638-9750", "entrance stairwell jamb (east)")]}
RETAINED = {K("3656-3656-9635-9636"): ("ascending stair (warp, walk flag)", "Warp stair with its existing walk stair model; warp behaviour untouched."),
            K("9643-9644-9645-9646"): ("descending stair (warp)", "Warp doorway cell: the planner's doorway handling draws it; warp behaviour untouched.")}
FROZEN = "Inside Codex's frozen native reception context (emerald_trainer_hill_furniture_east_counter), whose proof binds this entry; kept unchanged."
for _k, _l in [("9738-9615-9740-10022", "lift frame (west)"), ("9617-9617-10023-10024", "lift lintel"), ("9620-9739-10025-9741", "lift frame (east)"),
               ("9747-10028-9749-10029", "lift jamb (west)"), ("10030-10031-10032-10033", "lift doors"), ("10034-9748-10035-9750", "lift jamb (east)"),
               ("10042-10043-10044-10045", "entrance information stand")]:
    RETAINED[K(_k)] = (_l, FROZEN)
CONSOLE = [[K("9586-9587-9588-9589"), K("9587-9587-9589-9589"), K("9587-9590-9589-9591")],
           [K("9603-9604-9605-9606"), None, K("9604-9609-9610-9611")],
           [K("9622-9623-9624-9601"), K("9625-9625-9601-9601"), K("9626-9627-9601-9628")]]
CENTRES = [K("9592-9607-9594-9608"), K("9592-9593-9594-9595"), K("9612-9593-9598-9595"), K("9596-9597-9598-9595")]
WHITE, LILAC, NAVY = (255, 255, 255), (222, 222, 255), (66, 74, 107)


def entry_hash(entry):
    return hashlib.sha256(json.dumps(entry, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def console_part(row, cell):
    """One cell of the console, by its row in the 3x3 (0 back unit, 1 desk, 2 desk edge)."""
    h = 32 if row == 0 else 16
    yy, zz, xx = np.indices((h, 16, 16))
    if row == 0:
        occ = (zz >= 4) & (yy < 22)                                 # tall back unit (its north 4 voxels left as a gap)
    else:
        occ = yy < 12
        if row == 1:
            px = cell.pixels
            btn = np.array([[tuple(px[v, u]) in ((82, 82, 107), (107, 0, 0), (255, 0, 0)) for u in range(16)] for v in range(16)])
            occ |= (yy == 12) & btn[zz, xx]                         # the button cross in relief
    up, ox, oz = fur.faces(occ)
    if row == 0:
        v = (14 - (yy * 14) // 22).clip(1, 14)                     # white louvre bands up the back unit's face
        paint = np.where(up, cell.pick([WHITE], 8, 2), v * 16 + xx)
    elif row == 1:
        paint = np.where(up, zz * 16 + xx, cell.pick([(132, 132, 173), LILAC], 8, 8))
    else:
        v = (14 - (yy * 7) // 12).clip(8, 14)                      # rows 8-14: lavender, blue band, dark edge
        paint = np.where(up, (zz // 2) * 16 + xx, v * 16 + xx)
    return occ, paint.astype(np.uint8)


def generate(table, art):
    updates = {}
    put = lambda key, occ, paint: updates.__setitem__(key, encode(occ, paint, copy.deepcopy(table["cells"][key]), source=SOURCE, trim=True))
    for key in WALL_COLUMNS:
        updates[key] = {"class": "columns", "iou": 1.0}
    for r, row in enumerate(CONSOLE):
        for key in row:
            if key:
                put(key, *console_part(r, CellPaint(art, key)))
    for key in CENTRES:
        put(key, *console_part(1, CellPaint(art, key)))
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
                "witnesses": witnesses(art, key), "evidence": "claude-trainer/runtime-proof.json: native wall columns"}
        if key in data["metadata"] and data["metadata"][key] != spec:
            raise ValueError("a different amendment already exists: " + key)
        data["metadata"][key] = spec
    AMENDMENTS.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def ledger(table, art):
    rows = []
    def row(key, label, decision, reason, family):
        rows.append({"key": key, "kind": "cell", "label": "Trainer Hill " + label, "family": family, "decision": decision, "reason": reason,
                     "baseline_entry_sha256": entry_hash(table["cells"][key]), "occurrences": art.locations.get(key, []),
                     "evidence": ["study/cells.png", "runtime-proof.json"]})
    for k, l in WALL_COLUMNS.items():
        row(k, l, "changed", "Flat wall art over the wall rows: native columns (amendment); the planner's doorway fold draws the stairwell "
            "openings at the measured wall height.", "trainer-hill-wall")
    for k, (l, r) in RETAINED.items():
        row(k, l, "retained", r, "trainer-hill-warp" if r != FROZEN else "trainer-hill-frozen")
    labels = [["console back unit (west)", "console back unit", "console back unit (east)"], ["console desk (west)", None, "console desk (east)"],
              ["console edge (west)", "console edge", "console edge (east)"]]
    for r, line in enumerate(CONSOLE):
        for c, key in enumerate(line):
            if key:
                row(key, labels[r][c], "changed", "One 3x3 control console: a tall white louvred back unit, a desk top carrying the button cross "
                    "in relief, the desk edge with the blue front band; faces from this cell's drawing.", "trainer-hill-console")
    for key in CENTRES:
        row(key, "console desk centre (per-floor button colour)", "changed", "Centre of the console desk with its button cross in relief.", "trainer-hill-console")
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
    assert len(rows) == len({r["key"] for r in rows}) == 31, len(rows)
    write_json(OUT / "updates.json", {"cells": updates, "buildings": {}})
    write_json(OUT / "ledger.json", rows)
    print(f"{len(updates)} changed, {sum(r['decision'] == 'retained' for r in rows)} retained -> {OUT}")


if __name__ == "__main__":
    main()
