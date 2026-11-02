"""Prepare a Crystal review batch: candidate table, complete-object catalog and gallery manifest.

    python -m tools.shape_fit.crystal_pass.batch --modules players-house [...] --name batch-01 [--all-modules M ...]

1. Merges the cumulative modules (`--all-modules`, default: --modules) over the immutable baseline into
   local/model-gallery/crystal-pass-study/candidates/<name>.json (never the live table).
2. Writes tools/shape_fit/entities/crystal.json: every module's complete objects (entities.json), sorted by
   id, deduplicated; an id defined twice with different layouts is an error.
3. Prepares local/model-gallery/crystal-review/<name>/ with model_gallery.py: before = the baseline,
   current = the candidate, every changed cell of --modules and every object they touch, angled views.

Capture with the wrapper's runClient arguments, then `model_gallery.py sheets --manifest ...`.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

from tools.shape_fit.crystal_pass.review import BASELINE, STUDY, merge, read, table_bytes, digest

ROOT = Path(__file__).resolve().parents[3]
ENTITIES = ROOT / "tools/shape_fit/entities/crystal.json"
GALLERY = ROOT / "local/model-gallery/crystal-review"


def entities(modules):
    seen = {}
    for name in modules:
        path = STUDY / name / "entities.json"
        for ent in read(path) if path.exists() else []:
            if ent["id"] in seen and seen[ent["id"]] != ent:
                raise ValueError(f"object {ent['id']} defined differently by two modules")
            seen[ent["id"]] = ent
    return [seen[k] for k in sorted(seen)]


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--modules", nargs="+", required=True, help="modules whose models this batch reviews")
    ap.add_argument("--all-modules", nargs="*", help="every merged module (default: --modules)")
    ap.add_argument("--name", required=True)
    ap.add_argument("--only", nargs="*", default=None, help="explicit gallery selectors instead of the batch's own")
    ap.add_argument("--retained", action="store_true", help="also show retained cells")
    args = ap.parse_args()
    every = args.all_modules or args.modules
    baseline_bytes = BASELINE.read_bytes()
    baseline = json.loads(baseline_bytes)
    result, report = merge(baseline, [read(STUDY / m / "updates.json") for m in every],
                           [read(STUDY / m / "ledger.json") for m in every])
    data = table_bytes(result)
    cand = STUDY / "candidates" / f"{args.name}.json"
    cand.parent.mkdir(parents=True, exist_ok=True)
    cand.write_bytes(data)
    ents = entities(every)
    ENTITIES.write_text(json.dumps(ents, indent=1) + "\n", encoding="utf-8")
    selectors = args.only
    if selectors is None:
        selectors = []
        for m in args.modules:
            rows = read(STUDY / m / "ledger.json")
            keys = {r["key"] for r in rows if r["kind"] == "cell" and (args.retained or r["decision"] == "changed")}
            selectors += sorted(keys)
            selectors += [r["key"] for r in rows if r["kind"] == "building" and r["decision"] == "changed"]
            path = STUDY / m / "entities.json"
            selectors += [e["id"] for e in (read(path) if path.exists() else [])]
    out = GALLERY / args.name
    if out.exists():
        raise SystemExit(f"{out} exists: use a new batch name (evidence folders are never prepared over)")
    building = re.compile(r"crystal_b\d{3}_")
    buildings = [s for s in selectors if building.match(s)]
    cells = [s for s in selectors if not building.match(s)]
    for subset, picks, folder in (("all", cells, out), ("buildings", buildings, out.with_name(out.name + "-buildings"))):
        if not picks:
            continue
        cmd = [sys.executable, str(ROOT / "tools/model_gallery.py"), "prepare", "--game", "crystal",
               "--bundle", str(ROOT / "local/minecraft/kantobridge/export/crystal"), "--subset", subset,
               "--table", str(cand), "--baseline-table", str(BASELINE), "--entities", str(ENTITIES),
               "--angled", "--out", str(folder), "--world", f"Crystal Gallery {folder.name}"]
        for s in picks:
            cmd += ["--only", s]
        print(" ".join(cmd[:6]), f"... {len(picks)} selectors -> {folder}")
        subprocess.run(cmd, check=True, cwd=ROOT)
    print(json.dumps(report["counts"]), "candidate", digest(data), cand)


if __name__ == "__main__":
    main()
