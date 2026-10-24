"""Crystal's field mechanics audit on the real engine (docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md §4).

    python tools/e2e/g2_field_check.py survey [--maps A,B]   # FA1 + FA2's dump: classes, warps, verdicts per cell
    python tools/e2e/g2_field_check.py scripts               # FA1's script part (coord events, warps, moves, ...)
    python tools/e2e/g2_field_check.py summary               # the inventory's counts again
    python tools/e2e/g2_field_check.py script-summary        # the script walk's counts again

The driver is drivers/kb_g2field.lua. Its files in local/reports/ (g2_field_inventory.json, g2_field_parity.json,
g2_field_scripts.json) are ROM-derived and stay in local/; this script prints only map symbols, class names, COLL
byte numbers, ids, kinds and counts. Verdicts: walk and surf only (the bike runs the walking arm in Gen 2).
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from run_gen1recomp import REPORTS  # noqa: E402
import field_common as fc  # noqa: E402

GAME = "crystal"
DRIVER = "kb_g2field.lua"
INVENTORY = REPORTS / "g2_field_inventory.json"
PARITY = REPORTS / "g2_field_parity.json"
SCRIPTS = REPORTS / "g2_field_scripts.json"
SUMMARY = REPORTS / "g2_field_inventory_summary.json"

# What each class has as a path to Minecraft today (the plan's §1 table, GB/lib/engine/gen2/observe.lua forced()
# and move.lua WARP_KINDS, checked in FA1/FA3): explicit = its own message kind; generic = arrives as `script`;
# none = nothing reaches the client
PATH_TODAY = {
    "ledge": "explicit (ledge)",
    "ledge_two_facings": "explicit (ledge); the export keeps the first facing only (G2Cells.flags)",
    "side_wall": "verdict only (no collider)", "buoy": "verdict only (no collider)",
    "ice": "generic (script: latched direction)", "current": "generic (script)",
    "waterfall": "generic (script); the climb is scripted (runWaterfall)",
    "whirlpool": "field move + story.blocks; the push-back generic (script)",
    "pit": "warp kind hole", "door": "warp kind door", "ladder": "warp kind stairs", "staircase": "warp kind stairs",
    "cave": "warp kind door", "warp_panel": "warp kind pad", "carpet": "warp kind edge",
    "warp_unused": "warp kind door (default)", "door_forced": "generic (script: door push)",
    "counter": "explicit (counter reach)", "grass": "encounters (Cobblemon)", "super_tall_grass": "encounters",
    "cut_tree": "act.field cut + story.blocks", "headbutt_tree": "party menu only",
    "unported_walk": "none (engine port gap)",
}
# the Cycling Road and Strength are not cell classes: noted with the summary
NOT_CELLS = {
    "cycling_road": "the whole ride forced (downhill counted as forced, observe.lua); no steering, no brake",
    "strength": "no push kind; the boulder moves as an npc walk, no boulder_moved listener",
}


def cmd_survey(timeout: float, maps: str) -> int:
    out = fc.run_static(DRIVER, GAME, "survey", "KB_SURVEY", timeout, {"KB_MAPS": maps})
    print("SURVEY", json.dumps({k: v for k, v in out.items() if not k.startswith("_")}, sort_keys=True))
    if not out["_ok"]:
        for line in out["_lines"][-12:]:
            print("  ", line)
        return 1
    return summary()


def cmd_scripts(timeout: float) -> int:
    out = fc.run_static(DRIVER, GAME, "scripts", "KB_SCRIPTS", timeout)
    print("SCRIPTS", json.dumps({k: v for k, v in out.items() if not k.startswith("_")}, sort_keys=True))
    if not out["_ok"]:
        for line in out["_lines"][-12:]:
            print("  ", line)
        return 1
    return script_summary()


def summary() -> int:
    inv = json.loads(INVENTORY.read_text(encoding="utf-8"))
    par = json.loads(PARITY.read_text(encoding="utf-8")) if PARITY.is_file() else {"maps": {}}
    maps = inv["maps"]
    cls_cells = Counter()
    cls_maps = defaultdict(set)
    unclassed = Counter()
    warp_meaning = Counter()
    in_map = Counter()
    skipped = []
    errs = []
    for mid, m in maps.items():
        if m.get("err"):
            errs.append(mid)
            continue
        if m.get("skipped"):
            skipped.append(mid)
        for c, n in (m.get("counts") or {}).items():
            cls_cells[c] += n
            cls_maps[c].add(mid)
        for k, n in (m.get("unclassed") or {}).items():
            unclassed[k] += n
        for w in m.get("warps") or []:
            warp_meaning[w.get("meaning") or "none"] += 1
            if w.get("inMap"):
                in_map[mid] += 1
    surveyed = [mid for mid, r in par["maps"].items() if r.get("walk")]
    left = sorted(mid for mid, r in par["maps"].items() if r.get("left"))
    failed = sorted(mid for mid, r in par["maps"].items() if r.get("err") and not r.get("left"))
    halted = sorted(mid for mid, r in par["maps"].items() if r.get("halted"))
    edited = sorted(mid for mid, r in par["maps"].items() if r.get("blocks"))
    print(f"\nINVENTORY: {len(maps)} maps ({len(skipped)} left out by D8, {len(errs)} without blocks or tileset); "
          f"verdicts on {len(surveyed)} maps, {len(left)} left their map on entry, {len(failed)} failed, "
          f"{len(halted)} halted, {len(edited)} with live block edits")
    if left:
        print("  left:", ", ".join(left[:20]) + ("..." if len(left) > 20 else ""))
    print("\nCLASSES (cells, maps, path today):")
    for c, n in sorted(cls_cells.items(), key=lambda kv: (-kv[1], kv[0])):
        ms = sorted(cls_maps[c])
        shown = ", ".join(ms[:8]) + ("..." if len(ms) > 8 else "")
        print(f"  {c}: {n} cells on {len(ms)} maps [{PATH_TODAY.get(c, '-')}] {shown}")
    absent = sorted(set(PATH_TODAY) - set(cls_cells))
    print("\nCLASSES ABSENT:", ", ".join(absent) or "none")
    print("NOT CELL CLASSES:")
    for k, v in NOT_CELLS.items():
        print(f"  {k}: {v}")
    print(f"\nUNCLASSED BYTES (no class beyond the base permission): {len(unclassed)} bytes, "
          f"{sum(unclassed.values())} cells")
    for k, n in sorted(unclassed.items()):
        print(f"  {k}: {n}")
    print("\nWARP CELLS BY MEANING:")
    for k, n in warp_meaning.most_common():
        print(f"  {k}: {n}")
    print(f"IN-MAP LADDERS (warp destMap == the map): {sum(in_map.values())} on {len(in_map)} maps "
          f"{', '.join(sorted(in_map)[:10])}{'...' if len(in_map) > 10 else ''}")
    out = {"maps": len(maps), "skipped": len(skipped), "errors": errs, "surveyed": len(surveyed), "left": left,
           "failed": failed, "halted": halted, "edited": edited,
           "classes": {c: {"cells": n, "maps": sorted(cls_maps[c])} for c, n in cls_cells.items()},
           "absent": absent, "unclassed": dict(unclassed), "warp_meanings": dict(warp_meaning),
           "in_map_ladders": dict(in_map)}
    fc.write(SUMMARY, out)
    return 0


def script_summary() -> int:
    data = json.loads(SCRIPTS.read_text(encoding="utf-8"))
    print("TOTALS", json.dumps(data["totals"], sort_keys=True))
    ops = Counter()
    by_root = Counter()
    moves_maps = set()
    specials = Counter()
    coord_maps = Counter()
    coord_scenes = Counter()
    bike_maps = defaultdict(set)
    warp_to = Counter()
    for mid, m in data["maps"].items():
        coords = m.get("coord") or []
        if coords:
            coord_maps[mid] = len([c for c in coords if c.get("script")])
            for c in coords:
                coord_scenes[c.get("sceneId")] += 1
        for f in m.get("found") or []:
            ops[f["op"]] += 1
            by_root[(f["op"], f["root"])] += 1
            if f["op"].startswith("applymovement"):
                moves_maps.add(mid)
            if f["op"] == "special":
                specials[f["name"]] += 1
            if f["op"] in ("setflag", "clearflag"):
                bike_maps[f"{f['op']} {f.get('name')}"].add(mid)
            if f["op"] in ("warp", "warpfacing") and f.get("to"):
                warp_to[(mid, f["to"])] += 1
    print("\nOPS (rows reachable, no conditions):", dict(sorted(ops.items())))
    print("BY ROOT:")
    for (op, root), n in sorted(by_root.items()):
        print(f"  {op} from {root}: {n}")
    print(f"\nCOORD EVENTS WITH A SCRIPT: {sum(coord_maps.values())} on {len(coord_maps)} maps; by scene id:",
          dict(sorted(coord_scenes.items(), key=lambda kv: (kv[0] is None, kv[0] or 0))))
    print(f"PLAYER MOVES on {len(moves_maps)} maps")
    print(f"SCRIPT WARPS (warp/warpfacing): {sum(warp_to.values())} rows, "
          f"{len({m for m, _ in warp_to})} maps; to the same map: {sum(n for (m, t), n in warp_to.items() if m == t)}")
    print("BIKE FLAGS (op flag: maps):")
    for k in sorted(bike_maps):
        print(f"  {k}: {', '.join(sorted(bike_maps[k]))}")
    print("SPECIALS (name: rows):", dict(sorted(specials.items())))
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("survey")
    p.add_argument("--timeout", type=float, default=5400.0)
    p.add_argument("--maps", default="")
    p = sub.add_parser("scripts")
    p.add_argument("--timeout", type=float, default=900.0)
    sub.add_parser("summary")
    sub.add_parser("script-summary")
    a = ap.parse_args(argv)
    if a.cmd == "survey":
        return cmd_survey(a.timeout, a.maps)
    if a.cmd == "scripts":
        return cmd_scripts(a.timeout)
    if a.cmd == "summary":
        return summary()
    if a.cmd == "script-summary":
        return script_summary()
    return 2


if __name__ == "__main__":
    sys.exit(main())
