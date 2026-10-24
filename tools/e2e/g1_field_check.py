"""Blue's field mechanics audit on the real engine (docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md §4).

    python tools/e2e/g1_field_check.py survey [--maps A,B]   # FA1 + FA2's dump: classes, warps, verdicts per cell
    python tools/e2e/g1_field_check.py scripts               # FA1's script part (hooks, coord checks, warps, moves, ...)
    python tools/e2e/g1_field_check.py summary               # the inventory's counts again
    python tools/e2e/g1_field_check.py script-summary        # the script part's counts again

The driver is drivers/kb_g1field.lua (port 27230 unless KB_TEST_PORT says otherwise). Its files in local/reports/
(g1_field_inventory.json, g1_field_parity.json, g1_field_scripts.json) are ROM-derived and stay in local/; this script
prints only map symbols, class names, kinds, ids and counts.

Gen 1's verdict has no bike column: Player:tryMove and Collision.canMove never read the bicycle (the bike changes the
speed only), so the parity dump carries `walk` and `surf` strings only.
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

GAME = "blue"
DRIVER = "kb_g1field.lua"
INVENTORY = REPORTS / "g1_field_inventory.json"
PARITY = REPORTS / "g1_field_parity.json"
SCRIPTS = REPORTS / "g1_field_scripts.json"
SUMMARY = REPORTS / "g1_field_inventory_summary.json"
SCRIPT_SUMMARY = REPORTS / "g1_field_scripts_summary.json"

# What each class has as a path to Minecraft today (the plan's §1 table and the Blue brief, checked in FA1/FA3):
# explicit = its own message kind or export bit; generic = arrives as `script` (or `door`); none = nothing reaches
# the client. Blue's export (GB/export/kb_cells.lua) carries walk, water, shore, grass, door, warp tile, counter, cut,
# pad, hole (on warp entries), spinner, warp, sign and ledge + direction; the raw field tables ride along
# (kb_export.lua:56-62), nothing per cell for the rest.
PATH_TODAY = {
    "walk": "export walk bit; player.pos walk",
    "water": "export water bit; player.pos surf",
    "shore": "export water+shore bits",
    "grass": "export grass bit",
    "door": "export door bit; warp kind door",
    "warp_tile": "export warp-tile bit; warp kind stairs",
    "warp": "export warp bit; warp kind by departure",
    "sign": "export sign bit",
    "counter": "export counter bit",
    "pad": "export pad bit; warp kind pad",
    "hole": "export hole bit; warp kind hole (holeFall hint)",
    "pad_tile_no_warp": "none (floor art; the engine asks only under a warp)",
    "hole_tile_no_warp": "none (floor art; the scripted holes sit here)",
    "cut": "export cut bit; act.field cut; story.blocks",
    "ledge": "explicit (ledge) + export ledge bits",
    "pair_land": "verdict only (PairEdges: two-way walls, not exported per cell)",
    "pair_water": "verdict only (PairEdges, surfing)",
    "spinner": "explicit (spin) + export spinner bit",
    "forced_bike": "generic (mode change; raw field table only)",
    "forced_surf": "generic (mode change; raw field table only)",
    "seafoam_current": "explicit (current)",
    "seafoam_entry_current": "explicit (current)",
    "seafoam_forced_exit": "generic (script push-back)",
    "seafoam_boulder_hole": "push + story (boulder falls)",
    "surf_blocked": "refusal (act.field surf)",
    "slope_map": "explicit (slope + B brake)",
    "dark_map": "story.lit",
    "badge_gate": "generic (script push-back)",
    "boulder": "explicit (push)",
    "scripted_hole": "fade, no landing in the export (K10); warp kind hole by hint",
    "step_warp": "warp (kind script / door)",
    "step_move": "generic (script)",
    "step_text": "ui mirror only",
    "step_script": "generic (script)",
    "step_block": "story.blocks",
    "step_consumed": "none (the step is swallowed: no encounter roll)",
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


def _shown(ms) -> str:
    ms = sorted(ms)
    return ", ".join(ms[:8]) + ("..." if len(ms) > 8 else "")


def summary() -> int:
    inv = json.loads(INVENTORY.read_text(encoding="utf-8"))
    par = json.loads(PARITY.read_text(encoding="utf-8")) if PARITY.is_file() else {"maps": {}}
    maps = inv["maps"]
    cls_cells = Counter()
    cls_maps = defaultdict(set)
    warp_kind = Counter()
    warp_fires = Counter()
    holes = {}
    onstep_maps = []
    probe_errors = Counter()
    kb_diff = Counter()
    kb_checked = 0
    skipped, errs, warp_errs = [], [], []
    for mid, m in maps.items():
        if m.get("err"):
            errs.append(mid)
            continue
        if m.get("skipped"):
            skipped.append(mid)
        if m.get("warpErr"):
            warp_errs.append(mid)
        for c, n in (m.get("counts") or {}).items():
            cls_cells[c] += n
            cls_maps[c].add(mid)
        for w in m.get("warps") or []:
            warp_kind[w.get("kind") or "none"] += 1
            warp_fires[w.get("fires") or "none"] += 1
        hole_cells = (m.get("cells") or {}).get("scripted_hole") or []
        if hole_cells:
            holes[mid] = len(hole_cells)
        probe = m.get("onStep")
        if probe:
            onstep_maps.append(mid)
            if probe.get("errors"):
                probe_errors[mid] = probe["errors"]
            if probe.get("err"):
                probe_errors[mid] = probe_errors.get(mid, 0) + 1
        kc = m.get("kbCells") or {}
        if kc.get("checked"):
            kb_checked += 1
            for bit, n in (kc.get("diff") or {}).items():
                kb_diff[bit] += n
    surveyed = [mid for mid, r in par["maps"].items() if r.get("walk")]
    left = sorted(mid for mid, r in par["maps"].items() if r.get("left"))
    failed = sorted(mid for mid, r in par["maps"].items() if r.get("err") and not r.get("skipped"))
    halted = sorted(mid for mid, r in par["maps"].items() if r.get("halted"))
    with_blocks = sorted(mid for mid, r in par["maps"].items() if r.get("blocks"))
    edge_warps = Counter()
    verdicts = {"walk": Counter(), "surf": Counter()}
    for r in par["maps"].values():
        for e in r.get("edgeWarps") or []:
            edge_warps[e[3] if len(e) > 3 else "?"] += 1
        for k in ("walk", "surf"):
            verdicts[k].update(r.get(k) or "")

    print(f"\nINVENTORY: {len(maps)} maps ({len(skipped)} left out by D8, {len(errs)} without a static read, "
          f"{len(warp_errs)} warps refused); verdicts on {len(surveyed)} maps, {len(left)} left their map on entry, "
          f"{len(failed)} failed, {len(halted)} halted")
    if left:
        print("  left:", _shown(left))
    if failed:
        print("  failed:", _shown(failed))
    print("\nCLASSES (cells, maps, path today):")
    for c, n in sorted(cls_cells.items(), key=lambda kv: (-kv[1], kv[0])):
        print(f"  {c}: {n} cells on {len(cls_maps[c])} maps [{PATH_TODAY.get(c, '-')}] {_shown(cls_maps[c])}")
    absent = sorted(set(PATH_TODAY) - set(cls_cells))
    print("\nCLASSES ABSENT:", ", ".join(absent) or "none")
    print("\nWARPS BY KIND (gen1-bridge's warpDeparture rule, statically):", dict(warp_kind.most_common()))
    print("WARPS BY TRIGGER (arrive = door/warp tile; edge = exit mat off the edge; collision = a blocked step):",
          dict(warp_fires.most_common()))
    print("LIVE EDGE / COLLISION WARP CELL-DIRECTIONS:", dict(edge_warps))
    print(f"\nSCRIPTED HOLES (onStep probe): {sum(holes.values())} cells on {len(holes)} maps")
    for mid, n in sorted(holes.items()):
        print(f"  {mid}: {n}")
    print(f"\nonSTEP MAPS (probed): {len(onstep_maps)}; probe errors on {len(probe_errors)} maps"
          + (f" ({_shown(probe_errors)})" if probe_errors else ""))
    print(f"\nLIVE BLOCK CHANGES on entry (story.blocks): {len(with_blocks)} maps" +
          (f" ({_shown(with_blocks)})" if with_blocks else ""))
    print("VERDICTS (cell-directions):", {k: dict(sorted(v.items())) for k, v in verdicts.items()})
    print(f"\nKB_CELLS vs ENGINE ({kb_checked} maps compared): disagreeing cells per bit", dict(sorted(kb_diff.items())))
    out = {"maps": len(maps), "skipped": len(skipped), "errors": errs, "warpErrors": warp_errs,
           "surveyed": len(surveyed), "left": left, "failed": failed, "halted": halted,
           "classes": {c: {"cells": n, "maps": sorted(cls_maps[c])} for c, n in cls_cells.items()},
           "absent": absent, "warpKinds": dict(warp_kind), "warpTriggers": dict(warp_fires),
           "edgeWarps": dict(edge_warps), "scriptedHoles": holes, "onStepMaps": sorted(onstep_maps),
           "probeErrors": dict(probe_errors), "blocksOnEntry": with_blocks,
           "verdicts": {k: dict(v) for k, v in verdicts.items()}, "kbCells": {"maps": kb_checked, "diff": dict(kb_diff)}}
    fc.write(SUMMARY, out)
    return 0


def script_summary() -> int:
    data = json.loads(SCRIPTS.read_text(encoding="utf-8"))
    print("TOTALS", json.dumps(data.get("totals") or {}, sort_keys=True))
    print("TEXT SCAN (data/scripts/**.lua, rows by op):", json.dumps(data.get("textTotals") or {}, sort_keys=True))
    hooks = Counter()
    onstep = []
    probe_cls = Counter()
    probe_maps = defaultdict(set)
    text_maps = defaultdict(set)
    for mid, m in (data.get("maps") or {}).items():
        h = m.get("hooks") or {}
        for k in ("onStep", "onEnter", "onBoulderMoved", "onInteract", "onVictory"):
            if h.get(k):
                hooks[k] += 1
        if h.get("onStep"):
            onstep.append(mid)
        for c, cells in (m.get("probeClasses") or {}).items():
            probe_cls[c] += len(cells)
            probe_maps[c].add(mid)
        for op in (m.get("text") or {}):
            text_maps[op].add(mid)
    print("\nHOOKS (maps):", dict(sorted(hooks.items())))
    print(f"onStep on {len(onstep)} maps: {_shown(onstep)}")
    print("\nPROBE CLASSES (cells, maps):")
    for c, n in sorted(probe_cls.items(), key=lambda kv: (-kv[1], kv[0])):
        print(f"  {c}: {n} cells on {len(probe_maps[c])} maps [{PATH_TODAY.get(c, '-')}] {_shown(probe_maps[c])}")
    print("\nTEXT SCAN BY FILE (op counts):")
    for path, rec in sorted((data.get("files") or {}).items()):
        counts = rec.get("counts") or {}
        if counts:
            print(f"  {path}: {json.dumps(counts, sort_keys=True)}")
    print("\nTEXT SCAN MAPS PER OP:", {op: len(ms) for op, ms in sorted(text_maps.items())})
    tables = data.get("tables") or {}
    print("\nENGINE TABLES:")
    print("  badgeGates:", {k: {"coords": len(v.get("coords") or []), "guards": len(v.get("guards") or [])}
                            for k, v in sorted((tables.get("badgeGates") or {}).items())})
    fm = tables.get("forcedMovement") or {}
    print("  forcedMovement tiles:", {k: len(v) for k, v in sorted((fm.get("tiles") or {}).items())},
          "slopeMaps:", fm.get("slopeMaps"), "clearMaps:", fm.get("clearMaps"))
    print("  seafoam:", {k: {f: (len(v[f]) if isinstance(v.get(f), list) else bool(v.get(f)))
                             for f in ("currents", "forcedExit", "holes", "surfBlocked", "entryCurrent")}
                         for k, v in sorted((tables.get("seafoam") or {}).items())})
    print("  spinners:", {k: len(v) for k, v in sorted((tables.get("spinners") or {}).items())})
    print("  safari stepMaps:", len((tables.get("safari") or {}).get("stepMaps") or []),
          "lastMapRewrites:", len(tables.get("lastMapRewrites") or []))
    fc.write(SCRIPT_SUMMARY, {"totals": data.get("totals"), "textTotals": data.get("textTotals"),
                              "hooks": dict(hooks), "onStepMaps": sorted(onstep),
                              "probeClasses": {c: {"cells": n, "maps": sorted(probe_maps[c])}
                                               for c, n in probe_cls.items()},
                              "textMaps": {op: sorted(ms) for op, ms in text_maps.items()}})
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
