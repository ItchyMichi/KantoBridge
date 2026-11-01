"""Battle Pyramid top: the ladder climbing the stepped pyramid (ledger only).

The ladder column at x=17 alternates WALK0 cells at elevation 0 ("any level", the climb) with
WALK0 landings at elevations 4 and 6 (the steps). The planner's native levels carry the climb and
never use a fitted entry on these walkable cells, so both keys are retained: drawn as the ladder
art on the step faces and landings.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

from tools.model_gallery import EmeraldArt, write_json

ROOT = Path(__file__).resolve().parents[3]
BASELINE = ROOT / "local/model-gallery/emerald-full-pass-baseline.json"
BUNDLE = ROOT / "local/minecraft/kantobridge/export/emerald"
OUT = ROOT / "local/model-gallery/emerald-pass-study/claude-pyramid-top"
KEYS = {"g3q/2571-2572-2571-2572": "ladder rungs on a step face (elevation 0, the climb)",
        "g3q/2571-2572-2585-2586": "ladder foot on a step landing (elevations 4 and 6)"}


def generate(table, art):
    return {}


def ledger(table, art):
    h = lambda e: hashlib.sha256(json.dumps(e, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return [{"key": k, "kind": "cell", "label": "Battle Pyramid top " + l, "family": "battle-pyramid-ladder", "decision": "retained",
             "reason": "WALK0 at every placement on the stepped pyramid; the native levels carry the climb and the fitted entry is never "
                       "used, so the ladder is drawn as its art on the step faces and landings.",
             "baseline_entry_sha256": h(table["cells"][k]), "occurrences": art.locations.get(k, []),
             "evidence": ["../claude-pyramid/study/cells.json", "../claude-pyramid/study/map-EM_BATTLE_FRONTIER_BATTLE_PYRAMID_TOP.png"]}
            for k, l in KEYS.items()]


def main():
    table = json.loads(BASELINE.read_text(encoding="utf-8-sig"))
    art = EmeraldArt(BUNDLE)
    write_json(OUT / "updates.json", {"cells": {}, "buildings": {}})
    write_json(OUT / "ledger.json", ledger(table, art))
    print("2 retained ->", OUT)


if __name__ == "__main__":
    main()
