#!/usr/bin/env python3
"""Validate and merge the complete, individually audited Blue scenery pass.

Agent authoring modules emit geometry updates and a decision for every assigned
cell. This merger refuses missing reviews, conflicting edits, accidental changes
to building/interaction metadata, and alterations to the already approved pilots.
"""
from __future__ import annotations

import argparse
import base64
import copy
import hashlib
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TABLE = ROOT / "fabric-bridge/src/main/resources/kantobridge/fitted_shapes/blue.json"
BASELINE = ROOT / "local/model-gallery/blue-rollout-baseline.json"
PROTECTED = {"tilesets.overworld/42-43-58-59", "tilesets.reds_house/14-15-30-31"}
GEOMETRY = {"v", "t", "up", "from"}
# Source-map review confirmed these are lengthwise beds, not vertical stacks.
CORRECTED_BEDS = {
    "tilesets.facility/40-41-56-57": "tilesets.facility/56-57-25-26",
    "tilesets.ship/70-71-86-87": "tilesets.ship/86-87-59-60",
}
CORRECTED_STACKS = {
    **CORRECTED_BEDS,
    "tilesets.pokecenter/57-11-60-27": "tilesets.pokecenter/54-11-26-27",
}
# Four solid rear sections of the mineral case had no fitted entry at all.
ADDITIONS = {f"tilesets.gate/{ids}" for ids in (
    "39-40-48-46", "40-40-46-58", "40-40-58-46", "40-25-46-44")}


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def validate_entry(key, old, new):
    old_meta = {k: v for k, v in old.items() if k not in GEOMETRY}
    new_meta = {k: v for k, v in new.items() if k not in GEOMETRY}
    if key in CORRECTED_STACKS and old_meta.get("on") == CORRECTED_STACKS[key] and "on" not in new_meta:
        old_meta.pop("on")
    if old_meta != new_meta:
        raise ValueError(f"interaction or matching metadata changed: {key}")
    layers = [new, *new.get("up", [])]
    if not 1 <= len(layers) <= 15:
        raise ValueError(f"invalid layer count: {key}")
    occupied = 0
    for layer in layers:
        raw = base64.b64decode(layer["v"], validate=True)
        if len(raw) != 512:
            raise ValueError(f"invalid occupancy byte count: {key}")
        count = sum(v.bit_count() for v in raw)
        if not count:
            raise ValueError(f"revised model has an empty fallback layer: {key}")
        occupied += count
        if len(base64.b64decode(layer.get("t", ""), validate=True)) != 4096:
            raise ValueError(f"reviewed model requires explicit valid paint: {key}")
    if occupied == 0:
        raise ValueError(f"revised model is an empty fallback: {key}")


def merge(baseline, patches, reviews):
    if baseline.get("game") != "blue":
        raise ValueError("this pass is Blue only")
    updates, decisions = {}, {}
    for patch in patches:
        for key, entry in patch.items():
            if key in updates:
                raise ValueError(f"conflicting patch ownership: {key}")
            if key not in ADDITIONS and (key not in baseline["cells"] or baseline["cells"][key].get("class") != "voxels"):
                raise ValueError(f"patch outside fitted voxel scope: {key}")
            if key in PROTECTED and entry != baseline["cells"][key]:
                raise ValueError(f"approved pilot changed: {key}")
            validate_entry(key, baseline["cells"].get(key, {"class": "voxels"}), entry)
            if entry != baseline["cells"].get(key):
                updates[key] = entry
    for review in reviews:
        for row in review:
            key = row["key"]
            if key in decisions:
                raise ValueError(f"duplicate review ownership: {key}")
            if row.get("decision") not in ("changed", "retained", "added") or not row.get("label") or not row.get("reason"):
                raise ValueError(f"incomplete review decision: {key}")
            decisions[key] = copy.deepcopy(row)
    original = {k for k, v in baseline["cells"].items() if v.get("class") == "voxels"}
    added = set(updates) - set(baseline["cells"])
    expected = original | added
    if set(decisions) != expected:
        raise ValueError(f"review coverage differs: missing={sorted(expected-set(decisions))}, extra={sorted(set(decisions)-expected)}")
    for key, row in decisions.items():
        if (row["decision"] in ("changed", "added")) != (key in updates) or (row["decision"] == "added") != (key in added):
            raise ValueError(f"review decision disagrees with actual geometry change: {key}")
    result = copy.deepcopy(baseline)
    result["cells"].update(updates)
    report = {"schema": 1, "game": "blue", "scope": "all fitted scenery cells; buildings preserved",
              "voxel_cells": len(expected), "original_voxel_cells": len(original),
              "changed": len(updates)-len(added), "added": len(added), "added_keys": sorted(added),
              "retained": len(expected)-len(updates),
              "building_templates_preserved": len(baseline.get("buildings", [])),
              "approved_pilots_preserved": sorted(PROTECTED),
              "corrected_bed_assemblies": [k for k in CORRECTED_BEDS if k in updates and "on" not in updates[k]],
              "corrected_stack_links": [k for k in CORRECTED_STACKS if k in updates and "on" not in updates[k]],
              "non_voxel_entries_preserved": [k for k, v in baseline["cells"].items() if v.get("class") != "voxels"],
              "by_atlas": dict(sorted(Counter(k.split('/')[0] for k in updates).items())),
              "entries": [decisions[k] for k in sorted(decisions)]}
    return result, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, default=BASELINE)
    parser.add_argument("--patch", type=Path, action="append", required=True)
    parser.add_argument("--ledger", type=Path, action="append", required=True)
    parser.add_argument("--report", type=Path, default=ROOT / "local/model-gallery/blue-rollout-study/review.json")
    parser.add_argument("--write-table", action="store_true")
    parser.add_argument("--replace-reviewed", action="store_true", help="replace the exact previously reported pass after visual corrections")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    baseline = read(args.baseline)
    result, report = merge(baseline, [read(p) for p in args.patch], [read(p) for p in args.ledger])
    report["baseline_sha256"] = hashlib.sha256(args.baseline.read_bytes()).hexdigest()
    data = (json.dumps(result, indent=2) + "\n").encode()
    report["table_sha256"] = hashlib.sha256(data).hexdigest()
    if args.check and read(TABLE) != result:
        raise SystemExit("bundled Blue table differs from the reviewed patches")
    if args.write_table:
        current = read(TABLE)
        previous_matches = False
        if args.replace_reviewed and args.report.exists():
            previous = read(args.report)
            previous_matches = (previous.get("table_sha256") == hashlib.sha256(TABLE.read_bytes()).hexdigest()
                                and previous.get("baseline_sha256") == report["baseline_sha256"])
        if current != baseline and current != result and not previous_matches:
            raise SystemExit("Blue table changed independently; reconcile before merging")
        temp = TABLE.with_suffix(".review.tmp")
        temp.write_bytes(data)
        temp.replace(TABLE)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"{report['voxel_cells']} reviewed; {report['changed']} revised, {report['added']} added, {report['retained']} retained; all buildings preserved")


if __name__ == "__main__":
    main()
