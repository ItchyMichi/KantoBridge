"""Where each fitted Crystal key is actually drawn, from the planner itself.

`./gradlew shapeFit -Pkb.fitArgs="survey <bundle> <out.json>"` (FitTool.survey, the jar's table on) plans every map
and records, per cell, why it is or isn't a unit. This tallies those reasons per fitted key:

    drawn by its entry      unit:fit (a solid cell's unit), seat, walk_voxels, stack_top
    not drawn               walk (a walkable cell without walk/seat), building (a template claims it), water, ledge,
                            not_low (Tier A raised and folded it: a tall wall), counter (Crystal keeps the native
                            counter fold), no_rule

    python -m tools.shape_fit.crystal_pass.census [--survey <survey.json>]   -> crystal-pass-study/census/census.json

A key whose every placement is "not drawn" has no runtime effect: retain it with that reason.
"""
from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path

from tools.shape_fit.crystal_pass.common import STUDY, baseline

DRAWN = {"unit:fit", "seat", "walk_voxels", "stack_top"}
SURVEY = STUDY / "census/survey-live.json"
CENSUS = STUDY / "census/census.json"


def tally(survey: dict, fitted: set[str]) -> dict:
    keys = survey["keys"]
    per = defaultdict(Counter)
    where = defaultdict(list)
    for m in survey["maps"]:
        for i, k in enumerate(m["cell"]):
            key = keys[k]
            if key in fitted:
                reason = m["why"][i] or "none"
                per[key][reason] += 1
                if reason in DRAWN and len(where[key]) < 6:
                    where[key].append([m["map"], i % m["w"], i // m["w"]])
    out = {}
    for key in sorted(fitted):
        reasons = dict(per.get(key, {}))
        drawn = sum(v for r, v in reasons.items() if r in DRAWN)
        out[key] = {"drawn": drawn, "placements": sum(reasons.values()), "reasons": reasons, "drawn_at": where.get(key, [])}
    return out


def load():
    return json.loads(CENSUS.read_text(encoding="utf-8"))


def label(entry) -> str:
    if not entry["placements"]:
        return "unplaced"
    if not entry["drawn"]:
        return "never drawn: " + ",".join(sorted(entry["reasons"]))
    return f"drawn {entry['drawn']}/{entry['placements']}"


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--survey", type=Path, default=SURVEY)
    args = ap.parse_args()
    survey = json.loads(args.survey.read_text(encoding="utf-8"))
    result = {"survey": str(args.survey), "game": survey["game"], "keys": tally(survey, set(baseline()["cells"]))}
    CENSUS.parent.mkdir(parents=True, exist_ok=True)
    CENSUS.write_text(json.dumps(result, indent=1) + "\n", encoding="utf-8")
    keys = result["keys"]
    never = [k for k, v in keys.items() if v["placements"] and not v["drawn"]]
    print(f"{len(keys)} keys: {sum(1 for v in keys.values() if v['drawn'])} drawn somewhere, {len(never)} never drawn, "
          f"{sum(1 for v in keys.values() if not v['placements'])} unplaced -> {CENSUS}")


if __name__ == "__main__":
    main()
