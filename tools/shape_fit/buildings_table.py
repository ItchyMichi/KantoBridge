#!/usr/bin/env python3
"""Put a game's generated buildings into its fitted table (docs/specs/HAND_MODELS.md section 7).

    python buildings_table.py <game> <kb-hub data dir> <building_batch out dir>

Each building of <out>/<game>/buildings.json that was made becomes an entry of the table's "buildings" list: its atlas,
its tile grid (the art tiles of its box, north row first: each cell's key a-b-c-d is a b over c d) and the numbers its
model used (roofRows, roof, bands). The game claims each grid wherever the map has it and builds the model on every
client (FittedBuildings, BoxBuilding). The list is replaced.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]


def main() -> int:
    game, data, out = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])
    listing = json.loads((out / game / "buildings.json").read_text(encoding="utf-8"))
    entries = []
    for b in listing:
        if not b.get("ok") or "roofRows" not in b:
            continue
        info = json.loads((data / "images" / "maps" / game / f"{b['map']}.json").read_text(encoding="utf-8"))
        x0, y0, w, h = b["box"]
        atlas = None
        rows = [[0] * (2 * w) for _ in range(2 * h)]
        for cy in range(h):
            for cx in range(w):
                key = info["keys"][info["cell"][(y0 + cy) * info["w"] + x0 + cx]]
                a, ids = key.rsplit("/", 1)
                atlas = atlas or a
                t = [int(v) for v in ids.split("-")]
                rows[2 * cy][2 * cx], rows[2 * cy][2 * cx + 1] = t[0], t[1]
                rows[2 * cy + 1][2 * cx], rows[2 * cy + 1][2 * cx + 1] = t[2], t[3]
        entries.append({"id": b["id"], "atlas": atlas, "tiles": rows, "roofRows": b["roofRows"], "roof": b["roof"], "bands": b["bands"]})
    table_file = REPO / "fabric-bridge/src/main/resources/kantobridge/fitted_shapes" / f"{game}.json"
    table = json.loads(table_file.read_text(encoding="utf-8"))
    table["buildings"] = entries
    table_file.write_text(json.dumps(table, indent=2) + "\n", encoding="utf-8")
    print(f"{game}: {len(entries)} buildings in the table")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
