#!/usr/bin/env python3
"""Map review: merge an agent's marks for a map into kb-hub's flags and queue the map (docs/specs/MAP_MARKING_RULES.md).

    python agent_merge.py <kb-hub data dir> <agent file.json> [...]

An agent file (<data>/files/agent/<game>__<map>.json):

    {"game": "blue", "map": "BILLS_HOUSE", "context": "what the room is (short, with the sources looked at)",
     "entities": [{"kind": "object (model as one)", "note": "Stool", "cells": [[1, 5]], "background": [[2, 5]]}, ...],
     "cells": {"2,4": {"flag": "needs a model", "note": "..."}}}

Kinds and flags are the maps page's. Each entity's keys come from the map's info (<data>/images/maps/<game>/<map>.json);
cells outside the map, unknown kinds and background cells inside an entity are dropped (reported). The entities and
flags go into <data>/files/flags_<game>.json marked "by": "agent" (an earlier agent's marks for the map are replaced, the
user's are kept), and the map is queued in <data>/files/submissions.json.
"""
from __future__ import annotations

import datetime
import json
import sys
from pathlib import Path

KINDS = {"object (model as one)", "stack (tall object)", "sideways pair", "make by hand", "keep as blocks", "floor decoration"}
ALIASES = {"object": "object (model as one)", "stack": "stack (tall object)", "pair": "sideways pair", "hand": "make by hand",
           "blocks": "keep as blocks", "floor": "floor decoration"}
FLAGS = {"needs a model", "keep as a block", "stack (2x1)", "group (2x2+)", "floor: no model", "floor: slab/ledge", "make by hand",
         "model is wrong"}


def main() -> int:
    import fcntl

    data = Path(sys.argv[1])
    lock = open(data / "files" / ".agent_merge.lock", "w")
    fcntl.flock(lock, fcntl.LOCK_EX)  # agents merge as they finish: one at a time
    now = datetime.datetime.now(datetime.timezone.utc).isoformat()
    for f in sys.argv[2:]:
        a = json.loads(Path(f).read_text(encoding="utf-8"))
        game, map_id = a["game"], a["map"]
        info = json.loads((data / "images" / "maps" / game / f"{map_id}.json").read_text(encoding="utf-8"))
        w, h = info["w"], info["h"]
        inside = lambda c: 0 <= c[0] < w and 0 <= c[1] < h  # noqa: E731
        key = lambda c: info["keys"][info["cell"][c[1] * w + c[0]]]  # noqa: E731
        problems = []
        ents = []
        taken = {tuple(c) for e in a.get("entities", []) for c in e.get("cells", []) if inside(c)}
        for e in a.get("entities", []):
            e["kind"] = ALIASES.get(e.get("kind"), e.get("kind"))
            if e.get("kind") not in KINDS:
                problems.append(f"unknown kind {e.get('kind')!r}")
                continue
            cells = sorted({tuple(c) for c in e.get("cells", []) if inside(c)}, key=lambda c: (c[1], c[0]))
            if not cells:
                problems.append("an entity without cells inside the map")
                continue
            back = [list(c) for c in e.get("background", []) if inside(c) and tuple(c) not in taken]
            xs, ys = [c[0] for c in cells], [c[1] for c in cells]
            ents.append({"kind": e["kind"], "note": e.get("note", ""), "cells": [list(c) for c in cells],
                         "box": [min(xs), min(ys), max(xs) - min(xs) + 1, max(ys) - min(ys) + 1], "keys": [key(c) for c in cells],
                         "background": [{"cell": c, "key": key(c)} for c in back], "at": now, "by": "agent"})
        flags_cells = {}
        for xy, v in (a.get("cells") or {}).items():
            x, y = (int(t) for t in xy.split(","))
            if not inside((x, y)) or v.get("flag") not in FLAGS:
                problems.append(f"cell flag {xy} {v.get('flag')!r} dropped")
                continue
            flags_cells[xy] = {"flag": v["flag"], "note": v.get("note", ""), "key": key((x, y)), "at": now, "by": "agent"}
        ffile = data / "files" / f"flags_{game}.json"
        flags = json.loads(ffile.read_text(encoding="utf-8")) if ffile.is_file() else {}
        flags.setdefault("_entities", {})
        kept = [e for e in flags["_entities"].get(map_id, []) if e.get("by") != "agent"]
        flags["_entities"][map_id] = kept + ents
        mine = {k: v for k, v in flags.get(map_id, {}).items() if v.get("by") != "agent"}
        flags[map_id] = {**flags_cells, **mine}
        flags.setdefault("_context", {})[map_id] = a.get("context", "")
        ffile.write_text(json.dumps(flags, indent=1), encoding="utf-8")
        sfile = data / "files" / "submissions.json"
        subs = json.loads(sfile.read_text(encoding="utf-8")) if sfile.is_file() else []
        subs = [x for x in subs if not (x["game"] == game and x["map"] == map_id and x["status"] == "submitted")]
        subs.append({"game": game, "map": map_id, "flags": len(flags[map_id]), "entities": len(flags["_entities"][map_id]),
                     "at": now, "status": "submitted", "by": "agent"})
        sfile.write_text(json.dumps(subs, indent=1), encoding="utf-8")
        print(f"{game} {map_id}: {len(ents)} entities, {len(flags_cells)} cell flags" + (f"; dropped: {problems}" if problems else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
