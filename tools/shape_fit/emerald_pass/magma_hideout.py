"""Magma Hideout and Fiery Path (indoor-only keys): rock edges, facility plates, machines.

Source and runtime reading (claude-magma/study; native context claude-magma-current, flat
indoor planning): a volcanic cave whose rock ridges and facility walls are native and mostly
2 blocks high, with 9-block walls in places. The rock-edge pieces and the facility's lavender
and beige plate/wall-top cells are flat art inside those solid regions: native columns
(amendment), so they take the surrounding wall height and fold their art like their neighbours.
The machines (white drilling rigs, beige generators, control panels, the emblem) are retained:
their existing models read as machine blocks at the maze's height; the batch-B native capture is
the evidence for that judgement and the place to revise it.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path

from tools.model_gallery import EmeraldArt, write_json

ROOT = Path(__file__).resolve().parents[3]
BASELINE = ROOT / "local/model-gallery/emerald-full-pass-baseline.json"
BUNDLE = ROOT / "local/minecraft/kantobridge/export/emerald"
OUT = ROOT / "local/model-gallery/emerald-pass-study/claude-magma"
AMENDMENTS = Path(__file__).with_name("source_amendments.json")
K = lambda s: "g3q/" + s
ROCK = [K(s) for s in ("18193-18194-18183-18184", "18195-18196-18183-18184", "18197-18198-18183-18184")]
PLATES = [K(s) for s in ("18824-18825-18826-18827", "18824-18833-18826-18294", "18828-18829-18830-18831", "18832-18833-18834-18294",
                         "18833-18833-18294-18294", "18833-18835-18294-18836", "18833-18876-18294-18836", "18837-18838-18839-18840",
                         "18837-18846-18839-18848", "18841-18842-18843-18844", "18845-18846-18847-18848", "18846-18846-18848-18848",
                         "18846-18849-18848-18850", "18865-18866-18867-18868", "18869-18870-18871-18872", "18869-18874-18871-18872",
                         "18869-18878-18871-18872", "18873-18866-18867-18868", "18875-18833-18826-18294", "18877-18866-18867-18868",
                         "18879-18880-18859-18881")]


def indoor_keys():
    return json.loads((ROOT / "local/model-gallery/emerald-pass-study/claude-terrain/indoor-keys.json").read_text())


def entry_hash(entry):
    return hashlib.sha256(json.dumps(entry, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def generate(table, art):
    return {k: {"class": "columns", "iou": 1.0} for k in ROCK + PLATES}


def witnesses(art, key):
    out = []
    for p in art.locations[key]:
        src = art.maps[p["map"]]; raw = base64.b64decode(src["cells"]); w = src["w"]
        out.append({"map": p["map"], "x": p["x"], "y": p["y"], "palette": "BAKED", "collision": raw[(p["y"] * w + p["x"]) * 4 + 2]})
    return out


def write_amendments(art):
    sha = lambda k: hashlib.sha256(art.cell(k, "BAKED").convert("RGB").tobytes()).hexdigest()
    data = json.loads(AMENDMENTS.read_text(encoding="utf-8"))
    for key in ROCK + PLATES:
        spec = {"before": {"class": "voxels"}, "after": {"class": "columns", "iou": 1.0}, "source_pixel_sha256": sha(key),
                "witnesses": witnesses(art, key), "evidence": "claude-magma/runtime-proof.json: native rock and facility wall columns"}
        if key in data["metadata"] and data["metadata"][key] != spec:
            raise ValueError("a different amendment already exists: " + key)
        data["metadata"][key] = spec
    AMENDMENTS.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def ledger(table, art):
    rows = []
    for k in indoor_keys():
        if k in ROCK:
            label, decision, reason = "cave rock edge", "changed", "Rock-edge art inside the native rock ridge: native columns (amendment), the ridge's own height and fold."
        elif k in PLATES:
            label, decision, reason = "facility plate or wall top", "changed", "Plate/wall-top art inside a solid facility region: native columns (amendment), continuous with the facility walls."
        else:
            label, decision, reason = "machine (drilling rig, generator, panel or emblem)", "retained", (
                "Existing model reads as the drawn machine block at the maze's 2-block wall height; checked in the batch-B native context.")
        rows.append({"key": k, "kind": "cell", "label": "Magma Hideout " + label, "family": "magma-hideout", "decision": decision, "reason": reason,
                     "baseline_entry_sha256": entry_hash(table["cells"][k]), "occurrences": art.locations.get(k, []),
                     "evidence": ["study/cells.png", "../claude-magma-current/runtime-proof.json", "runtime-proof.json"]})
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--write-amendments", action="store_true")
    args = ap.parse_args()
    table = json.loads(BASELINE.read_text(encoding="utf-8-sig"))
    art = EmeraldArt(BUNDLE)
    assert set(ROCK + PLATES) <= set(indoor_keys())
    if args.write_amendments:
        write_amendments(art)
    rows = ledger(table, art)
    assert len(rows) == len({r["key"] for r in rows}) == 55
    write_json(OUT / "updates.json", {"cells": generate(table, art), "buildings": {}})
    write_json(OUT / "ledger.json", rows)
    print(f"{len(ROCK + PLATES)} changed, {len(rows) - len(ROCK + PLATES)} retained -> {OUT}")


if __name__ == "__main__":
    main()
