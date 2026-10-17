#!/usr/bin/env python3
"""Fitted shapes: add the depth run's kept fits to the tables and record the outcome in the failure lists
(docs/specs/FITTED_SHAPES.md section 9).

    python merge_depth.py <game> <fit_depth_<game>.json> <fit_depth_<game>.report.json> <depth dir>/<game>

- fabric-bridge/src/main/resources/kantobridge/fitted_shapes/<game>.json gains the depth fit's kept cells (cells the
  first run had no model for, so no key is replaced), each marked "from": "depth".
- tools/shape_fit/failed/<game>.json: each cell gains "depth": its rule (free-standing, tiled: thin, pattern), the best
  fit and IoUs, and whether the table took it. Cells still without a shape stay listed for another method.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]


def main() -> int:
    game, fit_file, report_file, depth_dir = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4])
    table_file = REPO / "fabric-bridge/src/main/resources/kantobridge/fitted_shapes" / f"{game}.json"
    failed_file = REPO / "tools/shape_fit/failed" / f"{game}.json"
    table = json.loads(table_file.read_text(encoding="utf-8"))
    kept = json.loads(fit_file.read_text(encoding="utf-8"))["cells"]
    report = {r["cell"]: r for r in json.loads(report_file.read_text(encoding="utf-8")) if "cell" in r}
    added = 0
    for key, entry in sorted(kept.items()):
        if key in table["cells"]:
            continue
        table["cells"][key] = dict(entry, **{"from": "depth"})
        added += 1
    table["cells"] = dict(sorted(table["cells"].items()))
    table.setdefault("summary", {})["kept_depth"] = added
    table_file.write_text(json.dumps(table, indent=2) + "\n", encoding="utf-8")

    failed = json.loads(failed_file.read_text(encoding="utf-8"))
    counts = {}
    for row in failed["cells"]:
        d_file = depth_dir / row["cell"].replace("/", "_") / "depth.json"
        d = json.loads(d_file.read_text(encoding="utf-8")) if d_file.is_file() else {}
        r = report.get(row["cell"])
        info = {"rule": d.get("rule", "not run")}
        if r:
            info.update(fit=r["fit"], fit_iou=r["fit_iou"], today_iou=r["today_iou"], in_table=row["cell"] in kept)
        row["depth"] = info
        state = "in table" if info.get("in_table") else info["rule"] if info["rule"].startswith("pattern") or info["rule"] == "not run" else "no better fit"
        counts[state] = counts.get(state, 0) + 1
    failed["note"] += " The depth run (section 9) added a 'depth' entry per cell; 'in_table' ones now have a shape."
    failed["summary"]["depth"] = counts
    failed_file.write_text(json.dumps(failed, indent=1) + "\n", encoding="utf-8")
    print(game, "added", added, "to the table; failure list:", counts)
    return 0


if __name__ == "__main__":
    sys.exit(main())
