"""Register EmeraldClaudeNativeContextTest proofs as gallery native-context exhibits.

Mirrors new_mauville_switches_native.py: each requested source rectangle becomes one
entity whose before/current geometry is the planner's actual columns on the real map,
bound to the proof, the original baseline and the exact current table. Gallery-only;
no fitted geometry, metadata or production registry changes.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path

from tools import model_gallery as gallery
from tools.shape_fit.emerald_pass.qa10_wholes import canonical_hash

ROOT = Path(__file__).resolve().parents[3]
BUNDLE = ROOT / "local/minecraft/kantobridge/export/emerald"


def register(folder: Path):
    runtime_path = folder / "runtime-proof.json"
    runtime = json.loads(runtime_path.read_text(encoding="utf-8"))
    snapshot = folder / "fitted-proof-current.json"
    assert hashlib.sha256(snapshot.read_bytes()).hexdigest() == runtime["current_table_sha256"]
    # Gradle cannot see the study folder: refuse a proof planned for an earlier candidate.
    updates = folder / "updates.json"
    expected = hashlib.sha256(updates.read_bytes()).hexdigest() if updates.exists() else None
    if runtime.get("candidate_updates_sha256") != expected:
        raise ValueError("stale native proof: rerun EmeraldClaudeNativeContextTest (cleanTest) for the current updates.json")
    table = json.loads(snapshot.read_text(encoding="utf-8"))
    art = gallery.EmeraldArt(BUNDLE)
    entities = []
    for row in runtime["occurrences"]:
        row = copy.deepcopy(row)
        common = {"occurrence": {k: row[k] for k in ("map", "x", "y")}, "source_layout": row["layout"]}
        row["current_source_cell_hashes"] = {k: canonical_hash(table["cells"].get(k)) for line in row["layout"] for k in line}
        row["current_source_template_hashes"] = gallery.native_source_template_hashes(table, {"descriptor": common, "record": row}, art)
        proof = {k: runtime[k] for k in ("original_baseline_sha256", "current_table_sha256")}
        proof.update(parent_runtime_evidence={"path": runtime_path.relative_to(ROOT).as_posix(),
                                              "sha256": hashlib.sha256(runtime_path.read_bytes()).hexdigest()},
                     occurrences=[row])
        proof_path = folder / "native-proofs" / f"{row['id']}.json"
        gallery.write_json(proof_path, proof)
        common.update(evidence=proof_path.relative_to(ROOT).as_posix(), evidence_sha256=hashlib.sha256(proof_path.read_bytes()).hexdigest())
        entities.append({
            "id": f"emerald_claude_native_{row['id']}", "kind": "native source context",
            "layout": copy.deepcopy(row["layout"]), "occurrences": [dict(common["occurrence"], palette="BAKED")],
            "note": f"Actual planner columns of {row['map']} ({row['x']},{row['y']}) {len(row['layout'][0])}x{len(row['layout'])}: "
                    "original baseline versus the exact current table, including native walls and floors.",
            "baseline_native_fragments": dict(common, baseline_sha256=runtime["original_baseline_sha256"]),
            "current_native_fragments": dict(common, current_table_sha256=runtime["current_table_sha256"],
                                             source_cell_hashes=row["current_source_cell_hashes"],
                                             source_template_hashes=row["current_source_template_hashes"]),
        })
    gallery.write_json(folder / "native-entities.json", entities)
    registry = json.loads((ROOT / "tools/shape_fit/entities/emerald.json").read_text(encoding="utf-8-sig"))
    ids = {e["id"] for e in entities}
    gallery.write_json(folder / "gallery-entities.json", [e for e in registry if e["id"] not in ids] + entities)
    return entities


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("folder", type=Path)
    args = ap.parse_args()
    entities = register(args.folder.resolve())
    print(f"{len(entities)} native contexts: " + ", ".join(e["id"] for e in entities))


if __name__ == "__main__":
    main()
