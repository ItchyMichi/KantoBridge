"""Trainer Hill roof: the tower, its faceted roof and the building edges as native walls.

Source reading (claude-trainer/study, EM_TRAINER_HILL_ROOF): an indoor-type map where only the
rooftop deck (rows 5-10) is walkable; the tower and its faceted roof (north), the building's
edges (south) and the side terraces are solid. The planner builds those solid regions as walls
of measured height around the deck, and every pending fitted cell inside them was a low single
layer: handed back to native columns (amendment), so the tower and edges fold their drawn art
onto continuous walls. The walkable bollard bases at the deck's west and east ends become
walk-through posts (walk flag, the user's rule); their stacked tops, and the post tops along the
south edge (existing stacks onto solid bases), are retained.
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
OUT = ROOT / "local/model-gallery/emerald-pass-study/claude-trainer-roof"
STUDY = ROOT / "local/model-gallery/emerald-pass-study/claude-trainer/study/cells.json"
AMENDMENTS = Path(__file__).with_name("source_amendments.json")
SOURCE = "hand-emerald-trainer-roof"
POST = "g3q/10194-10195-10196-10197"
RETAINED = {"g3q/9677-9677-10167-10168": "post tops along the deck's south edge (existing stack onto the solid bases)",
            "g3q/9677-9677-10192-10193": "bollard top (stacked on the walk-through post; nothing solid below, so floor)"}
STEEL, STEEL_D, CAP = (99, 99, 140), (66, 74, 107), (222, 222, 255)


def roof_keys():
    cells = json.loads(STUDY.read_text(encoding="utf-8"))
    return sorted(k for k, v in cells.items() if {p["map"] for p in v["placements"]} == {"EM_TRAINER_HILL_ROOF"})


def columns_keys():
    return [k for k in roof_keys() if k not in RETAINED and k != POST]


def entry_hash(entry):
    return hashlib.sha256(json.dumps(entry, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def post(cell):
    yy, zz, xx = np.indices((16, 16, 16))
    r = np.hypot(xx + .5 - 8, zz + .5 - 8)
    occ = ((r < 1.6) & (yy < 13)) | ((r < 2.6) & (yy < 1)) | ((r < 2.2) & (yy >= 12) & (yy < 14))
    up, _, _ = fur.faces(occ)
    paint = np.where(up | (yy >= 12), cell.pick([CAP, STEEL], 8, 2), cell.pick([STEEL, STEEL_D], 8, 8)).astype(np.uint8)
    return occ, paint


def generate(table, art):
    updates = {k: {"class": "columns", "iou": 1.0} for k in columns_keys()}
    original = copy.deepcopy(table["cells"][POST]); original["walk"] = True
    updates[POST] = encode(*post(CellPaint(art, POST)), original, source=SOURCE, trim=True)
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
    for key in columns_keys():
        new[key] = {"before": {"class": "voxels"}, "after": {"class": "columns", "iou": 1.0}, "source_pixel_sha256": sha(key),
                    "witnesses": witnesses(art, key), "evidence": "claude-trainer-roof/runtime-proof.json: native wall columns"}
    w = witnesses(art, POST); assert all(x["collision"] == 0 for x in w)
    new[POST] = {"before": {"class": "voxels"}, "after": {"class": "voxels", "walk": True}, "source_pixel_sha256": sha(POST),
                 "witnesses": w, "evidence": "WALK0 bollard base; user decision 2026-11-01: walk-through decoration"}
    for key, spec in new.items():
        if key in data["metadata"] and data["metadata"][key] != spec:
            raise ValueError("a different amendment already exists: " + key)
        data["metadata"][key] = spec
    AMENDMENTS.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def ledger(table, art):
    rows = []
    def row(key, label, decision, reason):
        rows.append({"key": key, "kind": "cell", "label": "Trainer Hill roof " + label, "family": "trainer-hill-roof", "decision": decision,
                     "reason": reason, "baseline_entry_sha256": entry_hash(table["cells"][key]), "occurrences": art.locations.get(key, []),
                     "evidence": ["../claude-trainer/study/map-EM_TRAINER_HILL_ROOF.png", "runtime-proof.json"]})
    for k in columns_keys():
        row(k, "tower, faceted roof or building edge section", "changed", "Solid region of the roof map built as a native wall; its fitted "
            "single layer was a low block in that wall. Native columns (amendment): the planner folds the drawn tower and edges at the "
            "measured height, continuous around the deck.")
    for k, l in RETAINED.items():
        row(k, l, "retained", "Existing stack top; drawn by the stack (or as floor where nothing solid stands below); nothing to change.")
    row(POST, "bollard post at the deck end", "changed", "A slim steel post with base and pale cap on the WALK0 cell; walk flag (amendment) "
        "so it stands as decoration without collision, like the hidden game.")
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
    assert len(rows) == len({r["key"] for r in rows}) == 97, len(rows)
    write_json(OUT / "updates.json", {"cells": updates, "buildings": {}})
    write_json(OUT / "ledger.json", rows)
    print(f"{len(updates)} changed, {sum(r['decision'] == 'retained' for r in rows)} retained -> {OUT}")


if __name__ == "__main__":
    main()
