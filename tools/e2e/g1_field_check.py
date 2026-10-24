"""Blue's field mechanics audit on the real engine (docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md §4).

    python tools/e2e/g1_field_check.py survey [--maps A,B]   # FA1 + FA2's dump: classes, warps, verdicts per cell
    python tools/e2e/g1_field_check.py scripts               # FA1's script part (hooks, coord checks, warps, moves, ...)
    python tools/e2e/g1_field_check.py summary               # the inventory's counts again
    python tools/e2e/g1_field_check.py script-summary        # the script part's counts again
    python tools/e2e/g1_field_check.py rows [--class C] [--maps A,B]   # FA3-FA4's rows, counted (no game)
    python tools/e2e/g1_field_check.py sweep [--class C] [--maps A,B]  # FA3-FA4: the live rows, a stand-in client
    python tools/e2e/g1_field_check.py report                # the sweep's analysis again
    python tools/e2e/g1_field_check.py feature-rows          # FA5's rows, listed (no game)
    python tools/e2e/g1_field_check.py features              # FA5: §3.3 features and §3.4 step counters, live

The driver is drivers/kb_g1field.lua (port 27230 unless KB_TEST_PORT says otherwise; the sweep uses SWEEP_PORT). Its
files in local/reports/ (g1_field_inventory.json, g1_field_parity.json, g1_field_scripts.json, g1_field_rows.*,
g1_field_sweep*.json, g1_field_stream*.json) are ROM-derived and stay in local/; this script prints only map symbols,
class names, kinds, ids and counts.

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


# ---- FA3-FA4: the live sweep's rows, from the inventory, the parity dump and the scripts file -------------------

DIRS = ("up", "down", "left", "right")
DELTA = {"up": (0, -1), "down": (0, 1), "left": (-1, 0), "right": (1, 0)}
OPP = {"up": "down", "down": "up", "left": "right", "right": "left"}
ROWS = REPORTS / "g1_field_rows.lua"
ROWS_JSON = REPORTS / "g1_field_rows.json"
SWEEP_PORT = 27233
PER_MAP_SAMPLE = 10          # grass, signs, counters, cut trees, tile-pair edges: 10 a map (D9)
PROBE_CELLS_PER_SIG = 2      # onStep probe signatures other than holes: this many cells each
PROBE_ROWS_PER_MAP = 20
SLOPE_ROLL_FRAMES = 120      # the Cycling Road's roll watched this long, then off the bike (the driver's row.roll)
SLOPE_STARTS = 3
WARP_CALLS = ("startWarpTo", "takeWarp", "warpToHealPoint", "flyTo")


class Grid:
    def __init__(self, rec):
        self.w, self.h = rec["w"], rec["h"]
        self.walk, self.surf, self.stand = rec["walk"], rec["surf"], rec.get("stand") or ""

    def inside(self, x, y):
        return 0 <= x < self.w and 0 <= y < self.h

    def st(self, x, y):
        return self.stand[y * self.w + x] if self.inside(x, y) and self.stand else "#"

    def v(self, x, y, d, surf=False):
        s = self.surf if surf else self.walk
        return s[(y * self.w + x) * 4 + DIRS.index(d)]


def _xy(cell) -> tuple[int, int] | None:
    if isinstance(cell, (list, tuple)) and len(cell) >= 2 and isinstance(cell[0], int) and isinstance(cell[1], int):
        return cell[0], cell[1]
    return None


def probe_kind(sig: dict) -> tuple[str, dict | None]:
    """An onStep probe signature's class (kb_g1field.lua probeClasses' rule, strongest first) and a hole's landing."""
    has, hole = set(), None
    for c in sig.get("calls") or []:
        fn = c.get("fn") or ""
        a = c.get("a") or []
        if fn == "fallThroughHole":
            has.add("hole")
            if hole is None and len(a) >= 3:
                hole = {"map": a[0], "x": a[1], "y": a[2]}
        elif fn in WARP_CALLS:
            has.add("warp")
        elif fn == "scriptMove" and c.get("who") == "player":
            has.add("move")
        elif fn == "replaceBlock":
            has.add("block")
        elif fn == "text":
            has.add("text")
        elif fn in ("queueScript", "startParallel") or fn.startswith("runner."):
            has.add("script")
        for arg in a:
            if isinstance(arg, dict) and isinstance(arg.get("rows"), list):
                for r in arg["rows"]:
                    op = r.get("op")
                    if op == "warp":
                        has.add("warp")
                    elif op == "move_player":
                        has.add("move")
                    elif op == "replace_block":
                        has.add("block")
    for k in ("hole", "warp", "move", "block", "script", "text"):
        if k in has:
            return k, hole
    return ("consumed" if sig.get("ret") else "none"), None


def make_rows(only_class: str = "", only_maps: str = "") -> list[dict]:
    inv = json.loads(INVENTORY.read_text(encoding="utf-8"))["maps"]
    par = json.loads(PARITY.read_text(encoding="utf-8"))["maps"]
    scripts = {}
    if SCRIPTS.is_file():
        scripts = json.loads(SCRIPTS.read_text(encoding="utf-8")).get("maps") or {}
    want_maps = set(filter(None, only_maps.split(",")))
    rows: list[dict] = []
    seen = set()
    notes = Counter()

    def add(**r):
        key = (r["map"], r["x"], r["y"], r.get("dir"), r.get("state"), r["cls"], r.get("tx"), r.get("ty"))
        if key in seen:
            return False
        seen.add(key)
        if only_class and r["cls"] != only_class and not r["cls"].startswith(only_class + ":"):
            return False
        rows.append(r)
        return True

    def approach(g, tx, ty, surf_ok=True, walk_ok=True, prefer=None, avoid=frozenset()):
        """(sx, sy, dir, state) for a plain step onto (tx, ty) the engine allows, or None; never from a cell in avoid.
        Gen 1's 'J' is a hop over the target, not a step onto it: only '.' counts."""
        order = [prefer] if prefer else []
        order += [d for d in DIRS if d != prefer]
        for d in order:
            dx, dy = DELTA[d]
            sx, sy = tx - dx, ty - dy
            if not g.inside(sx, sy) or (sx, sy) in avoid:
                continue
            if walk_ok and g.st(sx, sy) == "." and g.v(sx, sy, d) == ".":
                return sx, sy, d, "walk"
            if surf_ok and g.st(sx, sy) == "~" and g.v(sx, sy, d, True) == ".":
                return sx, sy, d, "surf"
        return None

    def refused(g, tx, ty):
        """(sx, sy, dir) for a step into (tx, ty) the engine refuses, from a cell where the player stands."""
        for d in DIRS:
            dx, dy = DELTA[d]
            sx, sy = tx - dx, ty - dy
            if g.inside(sx, sy) and g.st(sx, sy) == "." and g.v(sx, sy, d) in "xw":
                return sx, sy, d
        return None

    for mid in sorted(par):
        prec = par[mid]
        if not prec.get("walk") or (want_maps and mid not in want_maps):
            continue
        g = Grid(prec)
        m = inv.get(mid) or {}
        cells = m.get("cells") or {}
        counts = m.get("counts") or {}
        elevator = bool(m.get("elevator")) or "ELEVATOR" in mid

        def cl(name):
            out = []
            for c in cells.get(name) or []:
                xy = _xy(c)
                if xy and g.inside(*xy):
                    out.append((xy, c[2] if len(c) > 2 else None))
            return out

        spinners = {xy for xy, _ in cl("spinner")}
        currents = {xy for n in ("seafoam_current", "seafoam_entry_current", "seafoam_forced_exit") for xy, _ in cl(n)}
        forced = {xy for n in ("forced_bike", "forced_surf") for xy, _ in cl(n)}
        acting = frozenset(spinners | currents | forced)

        # ledges: the hop from the cell before the ledge (verdict J), the refusal from below
        for (x, y), facing in cl("ledge"):
            if facing not in DELTA:
                continue
            dx, dy = DELTA[facing]
            sx, sy = x - dx, y - dy
            if g.inside(sx, sy) and g.st(sx, sy) == "." and g.v(sx, sy, facing) == "J":
                add(map=mid, x=sx, y=sy, dir=facing, state="walk", cls="ledge", tx=x, ty=y, expect="hop")
            bx, by = x + dx, y + dy
            if g.inside(bx, by) and g.st(bx, by) == "." and g.v(bx, by, OPP[facing]) in "xw":
                add(map=mid, x=bx, y=by, dir=OPP[facing], state="walk", cls="ledge_back", tx=x, ty=y,
                    expect="blocked")
        # tile pairs: a step across a pair edge, both cells standable (10 a map per kind); refused where the parity
        # dump says so
        for kind, surf, mark in (("land", False, "."), ("water", True, "~")):
            n = 0
            for e in (m.get("pairEdges") or {}).get(kind) or []:
                if n >= PER_MAP_SAMPLE:
                    break
                if not (isinstance(e, list) and len(e) >= 3 and e[2] in DELTA):
                    continue
                x, y, d = e[0], e[1], e[2]
                dx, dy = DELTA[d]
                if not (g.inside(x, y) and g.inside(x + dx, y + dy)):
                    continue
                if g.st(x, y) != mark or g.st(x + dx, y + dy) != mark:
                    continue
                if add(map=mid, x=x, y=y, dir=d, state="surf" if surf else "walk", cls="pair_" + kind,
                       tx=x + dx, ty=y + dy, expect="blocked" if g.v(x, y, d, surf) in "xw" else "step"):
                    n += 1
        # spinners: every spinner cell stepped onto from a cell that doesn't act
        for (x, y), _ in cl("spinner"):
            a = approach(g, x, y, surf_ok=False, avoid=acting)
            if a:
                add(map=mid, x=a[0], y=a[1], dir=a[2], state=a[3], cls="spinner", tx=x, ty=y, expect="forced",
                    wait=2400)
            else:
                notes["spinner:no_approach"] += 1
        # the Seafoam currents and the B4F forced exit: surfing onto them from water that doesn't act
        for name in ("seafoam_current", "seafoam_entry_current", "seafoam_forced_exit"):
            for (x, y), _ in cl(name):
                a = approach(g, x, y, walk_ok=False, avoid=acting)
                if a:
                    add(map=mid, x=a[0], y=a[1], dir=a[2], state="surf", cls=name, tx=x, ty=y, expect="forced",
                        wait=2400, fresh=True)
                else:
                    notes[name + ":no_approach"] += 1
        # forced bike/surf tiles: on foot without a BICYCLE (turned back), with one (mounted); the surf mouths
        for (x, y), _ in cl("forced_bike"):
            a = approach(g, x, y, surf_ok=False, avoid=acting)
            if a:
                add(map=mid, x=a[0], y=a[1], dir=a[2], state="walk", cls="forced_bike", tx=x, ty=y, expect="script",
                    fresh=True)
                add(map=mid, x=a[0], y=a[1], dir=a[2], state="walk", cls="forced_bike:mount", tx=x, ty=y,
                    expect="mount", mount="bike", bicycle=True, fresh=True)
        for (x, y), _ in cl("forced_surf"):
            a = approach(g, x, y, avoid=acting)
            if a:
                add(map=mid, x=a[0], y=a[1], dir=a[2], state=a[3], cls="forced_surf", tx=x, ty=y, expect="mount",
                    mount="surf", fresh=True)
        # the Cycling Road: on the bike, no step: the roll. No brake row: the client releases its hold=b before
        # it writes the go, and a bike mounted before READY rolls at once (OC:1883-1905)
        if m.get("slope"):
            starts = [(x, y) for y in range(1, g.h - 5) for x in range(g.w)
                      if all(g.st(x, y + k) == "." and g.v(x, y + k, "down") == "." for k in range(4))]
            picks = sorted({starts[i * (len(starts) - 1) // max(1, SLOPE_STARTS - 1)] for i in range(SLOPE_STARTS)}
                           if starts else set(), key=lambda c: (c[1], c[0]))
            for x, y in picks:
                add(map=mid, x=x, y=y, dir=None, state="bike", cls="slope_roll", tx=x, ty=y, expect="forced",
                    late=True, roll=SLOPE_ROLL_FRAMES, wait=1800, face="down", fresh=True)
        # badge gates (the Route 22 gate checkpoint, the Route 23 guard rows): a step onto the row, from below where it
        # can, without the badge: the push-back (two cells a row at most)
        per_y = Counter()
        for (x, y), _ in sorted(cl("badge_gate"), key=lambda c: (c[0][1], c[0][0])):
            if per_y[y] >= 2:
                continue
            a = approach(g, x, y, surf_ok=False, prefer="up")
            if a and add(map=mid, x=a[0], y=a[1], dir=a[2], state="walk", cls="badge_gate", tx=x, ty=y,
                         expect="script", fresh=True, wait=1800):
                per_y[y] += 1
        # boulders (Strength active): one row, two steps toward the boulder (dirs=d,d): the first try only arms
        # (refused: entity), the second pushes
        for b in m.get("boulders") or []:
            bx, by = b.get("x"), b.get("y")
            if b.get("hidden") or not isinstance(bx, int) or not isinstance(by, int) or not g.inside(bx, by):
                continue
            for d in DIRS:
                dx, dy = DELTA[d]
                sx, sy, lx, ly = bx - dx, by - dy, bx + dx, by + dy
                if not (g.inside(sx, sy) and g.inside(lx, ly)):
                    continue
                if g.st(sx, sy) == "." and g.v(sx, sy, d) == "." and g.st(lx, ly) == "." and g.v(bx, by, d) == ".":
                    add(map=mid, x=sx, y=sy, dir=d, dirs=f"{d},{d}", state="walk", cls="boulder", tx=bx, ty=by,
                        expect="push", strength=True, fresh=True, wait=1200)
                    break
            else:
                notes["boulder:no_side"] += 1
        # sampled: grass stepped onto; signs, counters and cut trees refused (10 a map)
        for name, how in (("grass", "step"), ("sign", "refuse"), ("counter", "refuse"), ("cut", "refuse")):
            if counts.get(name) and not cells.get(name):
                notes[name + ":cells_not_listed (rerun the survey)"] += 1
                continue
            n = 0
            for (x, y), _ in cl(name):
                if n >= PER_MAP_SAMPLE:
                    break
                if how == "step" or g.st(x, y) in ".~":
                    a = approach(g, x, y)
                    if a and add(map=mid, x=a[0], y=a[1], dir=a[2], state=a[3], cls=name, tx=x, ty=y, expect="step"):
                        n += 1
                else:
                    r = refused(g, x, y)
                    if r and add(map=mid, x=r[0], y=r[1], dir=r[2], state="walk", cls=name, tx=x, ty=y,
                                 expect="blocked"):
                        n += 1
        # warp entries by kind: door / warp tiles stepped onto; exit mats and edge warps stood on and pushed off the
        # edge (or into the wall: the carpet maps' collision warps), push=true
        edge_dirs = defaultdict(list)
        for e in prec.get("edgeWarps") or []:
            if isinstance(e, list) and len(e) >= 3 and e[2] in DELTA:
                edge_dirs[(e[0], e[1])].append(e[2])
        for wp in m.get("warps") or []:
            x, y = wp.get("x"), wp.get("y")
            if not isinstance(x, int) or not isinstance(y, int) or not g.inside(x, y):
                continue
            cls = "warp:" + ("elevator" if elevator else (wp.get("kind") or "door"))
            if wp.get("fires") == "arrive":
                a = approach(g, x, y, prefer="up")
                if a:
                    add(map=mid, x=a[0], y=a[1], dir=a[2], state=a[3], cls=cls, tx=x, ty=y, expect="warp",
                        fresh=True, warp=wp.get("n"))
                else:
                    notes["warp:no_approach"] += 1
            else:
                dirs = edge_dirs.get((x, y)) or [d for d in (wp.get("offEdge") or []) if d in DELTA]
                if dirs and g.st(x, y) in ".~":
                    add(map=mid, x=x, y=y, dir=dirs[0], state="surf" if g.st(x, y) == "~" else "walk", cls=cls, tx=x,
                        ty=y, expect="warp", push=True, fresh=True, warp=wp.get("n"))
                else:
                    notes["warp:no_push_dir"] += 1
        # FA4: the onStep hook's triggers (the probe's signatures: the scripts file's, else the inventory's), stepped
        # onto in the new game's state; the scripted holes every cell
        probe = (scripts.get(mid) or {}).get("probe") or m.get("onStep") or {}
        nprobe = 0
        for sig in probe.get("sigs") or []:
            kind, hole = probe_kind(sig)
            if kind == "none":
                continue
            taken = 0
            for c in sig.get("cells") or []:
                xy = _xy(c)
                if not xy or not g.inside(*xy):
                    continue
                if kind != "hole" and (taken >= PROBE_CELLS_PER_SIG or nprobe >= PROBE_ROWS_PER_MAP):
                    break
                a = approach(g, xy[0], xy[1], avoid=acting)
                if not a:
                    notes["step_" + kind + ":no_approach"] += 1
                    continue
                if kind == "hole":
                    r = dict(cls="scripted_hole", expect="fall")
                    if hole:
                        r["to"] = hole
                elif kind == "consumed":
                    r = dict(cls="step_consumed", expect="step")
                else:
                    r = dict(cls="step_" + kind, expect="script", ops=["warp"] if kind == "warp" else [kind])
                if add(map=mid, x=a[0], y=a[1], dir=a[2], state=a[3], tx=xy[0], ty=xy[1], fresh=True, wait=1800, **r):
                    taken += 1
                    nprobe += 1
    # a map whose entry sends the player on (the survey's visit left it): the warp in is the trigger (driven)
    for mid in sorted(par):
        prec = par[mid]
        if want_maps and mid not in want_maps:
            continue
        if prec.get("left") and not prec.get("err") and not (inv.get(mid) or {}).get("skipped"):
            add(map=mid, x=-1, y=-1, dir=None, state="walk", cls="entry_warp", tx=-1, ty=-1, expect="script",
                trigger="warp", ops=["warp"], wait=2400)
    for i, r in enumerate(rows, 1):
        r["n"] = i
    if notes:
        print("ROW NOTES (left out):", dict(sorted(notes.items())))
    return rows


def lua_rows(rows: list[dict]) -> str:
    def val(v):
        if isinstance(v, bool):
            return "true" if v else "false"
        if isinstance(v, (int, float)):
            return str(v)
        return '"' + str(v).replace("\\", "\\\\").replace('"', '\\"') + '"'
    out = ["return {"]
    for r in rows:
        # slow mechanics (a fall, a script, a warp's fade, a slide) get a longer quiet before the row ends
        if r.get("expect") in ("fall", "warp", "script", "forced", "mount"):
            r.setdefault("idle", 90)
        keep = {k: r[k] for k in ("n", "map", "x", "y", "dir", "state", "cls", "fresh", "push", "wait", "trigger",
                                  "idle", "face", "dirs", "hold", "strength", "bicycle", "late", "roll")
                if k in r and r[k] is not None}
        out.append("  { " + ", ".join(f"{k} = {val(v)}" for k, v in keep.items()) + " },")
    out.append("}")
    return "\n".join(out) + "\n"


def cmd_rows(only_class: str, maps: str) -> int:
    rows = make_rows(only_class, maps)
    by = Counter(r["cls"] if not r["cls"].startswith("warp:") else "warp" for r in rows)
    kinds = Counter(r["cls"] for r in rows if r["cls"].startswith("warp:"))
    print(f"ROWS {len(rows)} on {len({r['map'] for r in rows})} maps:", dict(sorted(by.items())))
    print("WARP ROWS BY KIND:", dict(sorted(kinds.items())))
    return 0


def cmd_sweep(timeout: float, only_class: str, maps: str, tag: str) -> int:
    rows = make_rows(only_class, maps)
    ROWS.write_text(lua_rows(rows), encoding="utf-8")
    fc.write(ROWS_JSON, rows)
    print(f"ROWS {len(rows)} on {len({r['map'] for r in rows})} maps")
    out = REPORTS / f"g1_field_sweep{tag}.json"
    received, done, answered, result = fc.run_rows(DRIVER, GAME, "sweep", SWEEP_PORT, timeout,
                                                   {"KB_ROWS": str(ROWS.resolve()), "KB_SWEEP_FILE": out.name})
    fc.write(REPORTS / f"g1_field_stream{tag}.json", {"received": received, "rows": done})
    analysis = analyse(rows, json.loads(out.read_text(encoding="utf-8")), received, done)
    fc.write(REPORTS / f"g1_field_sweep_analysis{tag}.json", analysis)
    report(analysis)
    return 0 if result.returncode == 0 else 1


# the warp kind the protocol's mechanism names (D2) for each Blue row class, by gen1-bridge's own warpDeparture rule
# (lib/engine/gen1.lua:1407-1433: pad/hole, door, stairs, edge when facing off the map); the scripted holes fall
# (ow.holeFall: "hole"); the elevator cars' warps are elevators
WANT_KIND = {"pad": "pad", "hole": "hole", "door": "door", "stairs": "stairs", "edge": "edge",
             "elevator": "elevator", "scripted_hole": "hole"}


def analyse(rows: list[dict], sweep: dict, received: list[dict], done: dict) -> dict:
    """field_common's analysis (Gen 1's world changes are blocks: story.blocks) and Blue's own expectations: a ledge
    hop answered as one (player.pos kind ledge) landing two cells on, a boulder's push answered push, a mount, a
    scripted hole landing on its floor."""
    out = fc.analyse_rows(rows, sweep, received, done, WANT_KIND, blocks=True)
    by_n = {r["n"]: r for r in rows}
    recs = {r.get("n"): r for r in sweep.get("rows") or []}
    for e in out["rows"]:
        row, rec = by_n.get(e["n"]) or {}, recs.get(e["n"]) or {}
        e["steps"] = rec.get("steps")
        if e["err"]:
            continue
        end = rec.get("end") or {}
        exp = row.get("expect")
        if exp == "hop":
            if e["answer"] != "player.pos:ledge":
                e["problems"].append("expect_hop")
            d = DELTA.get(row.get("dir") or "")
            if d and end.get("map") == row.get("map") and [end.get("x"), end.get("y")] != [row["tx"] + d[0],
                                                                                         row["ty"] + d[1]]:
                e["problems"].append("hop_landing")
        elif exp == "push":
            # the first step arms (refused: entity), the second (n * 1000 + 1) pushes
            second = [m for m in fc.row_messages(received, e["n"]) if m["type"] in ("player.pos", "player.blocked")
                      and (m.get("data") or {}).get("n") == e["n"] * 1000 + 1]
            if not second or second[0]["type"] != "player.pos" or (second[0].get("data") or {}).get("kind") != "push":
                e["problems"].append("expect_push")
        elif exp == "mount":
            if not end.get("biking" if row.get("mount") == "bike" else "surfing"):
                e["problems"].append("no_mount")
        elif exp == "fall" and row.get("to") and end.get("map") != row["to"].get("map"):
            e["problems"].append("fall_elsewhere")
        if rec.get("targetBusy") and not row.get("strength") and (e["answer"] or "").endswith(":entity"):
            e["problems"].append("busy")
    return out


def report(analysis: dict) -> None:
    fc.report_rows(analysis)


# ---- FA5: features and step counters (§3.3, §3.4) -------------------------------------------------------------------

FEATURES = REPORTS / "g1_field_features_rows.lua"
FEATURES_JSON = REPORTS / "g1_field_features_rows.json"

# A Blue party that knows the field moves, synced as Cobblemon would (party.sync; made-up uids). Gen 1 move numbers:
# Cut 15, Fly 19, Surf 57, Strength 70, Dig 91, Teleport 100, Flash 148; Gen 1 dex numbers. The third member is
# poisoned (status psn, protocol/defs.schema.json gen1Status): applyFieldPoison takes 1 HP every 4th step (OC:4689)
BLUE_PARTY = [
    {"uid": "fa5b0001-0000-4000-8000-000000000001", "dex": 9, "level": 50, "moves": [57, 70], "hp": 150, "maxhp": 150},
    {"uid": "fa5b0002-0000-4000-8000-000000000002", "dex": 6, "level": 50, "moves": [15, 19, 91, 70], "hp": 150,
     "maxhp": 150},
    {"uid": "fa5b0003-0000-4000-8000-000000000003", "dex": 64, "level": 50, "moves": [100, 148], "hp": 120,
     "maxhp": 120, "status": "psn"},
]

# where each feature is tried first (map symbols as the engine names them); any other map that fits follows
FEATURE_MAPS = {
    "water": ["PALLET_TOWN", "CERULEAN_CITY", "VERMILION_CITY", "ROUTE_24", "ROUTE_19"],
    "open": ["ROUTE_1", "ROUTE_2", "VIRIDIAN_CITY", "ROUTE_22"],
    "cut": ["ROUTE_2", "CERULEAN_CITY", "ROUTE_9", "VERMILION_CITY", "ROUTE_8"],
    "boulder": ["VICTORY_ROAD_1F", "SEAFOAM_ISLANDS_1F", "VICTORY_ROAD_2F", "VICTORY_ROAD_3F"],
    "dark": ["ROCK_TUNNEL_1F", "ROCK_TUNNEL_B1F"],
    "cave": ["MT_MOON_1F", "ROCK_TUNNEL_1F", "DIGLETTS_CAVE"],
    "indoor": ["REDS_HOUSE_2F", "REDS_HOUSE_1F", "BLUES_HOUSE"],
    "safari": ["SAFARI_ZONE_CENTER", "SAFARI_ZONE_EAST", "SAFARI_ZONE_WEST", "SAFARI_ZONE_NORTH"],
}
FLY_DEST = "PALLET_TOWN"
BIKE_STEPS = 5
WALK20 = 10                  # ten there-and-back pairs: twenty steps
BRAKE_ROLL_FRAMES = 120      # the Cycling Road's roll watched this long after the go, then off the bike
# classes whose cells a feature row never starts on or walks over (they act by themselves)
ACTING = ("door", "warp_tile", "warp", "pad", "hole", "spinner", "forced_bike", "forced_surf", "seafoam_current",
          "seafoam_entry_current", "seafoam_forced_exit", "seafoam_boulder_hole", "badge_gate", "ledge",
          "scripted_hole", "surf_blocked", "pad_tile_no_warp", "hole_tile_no_warp")


def _ordered(names: list[str], pool) -> list[str]:
    return [m for m in names if m in pool] + sorted(m for m in pool if m not in names)


def make_features() -> list[dict]:
    inv = json.loads(INVENTORY.read_text(encoding="utf-8"))["maps"]
    par = json.loads(PARITY.read_text(encoding="utf-8"))["maps"]
    scripts = json.loads(SCRIPTS.read_text(encoding="utf-8")).get("maps") or {} if SCRIPTS.is_file() else {}
    rows: list[dict] = []
    notes = Counter()

    def add(**r):
        r.setdefault("badges", True)
        r.setdefault("wait", 1500)
        r.setdefault("idle", 60)
        r.setdefault("fresh", not r.get("follow"))
        n = len((r.get("dirs") or "").split(",")) if r.get("dirs") else (r.get("fwd") or 0)
        # by the clock: the act's answer (8 s at most) and every step's (4 s at most) before the client's go
        r.setdefault("stepwait", 6.0 + (9.0 if r.get("act") else 0.0) + 1.5 * n)
        rows.append(r)

    grids = {mid: Grid(rec) for mid, rec in par.items() if rec.get("walk") and not rec.get("left") and not rec.get("err")}

    def avoid(mid):
        m = inv.get(mid) or {}
        out = set()
        for name in ACTING:
            for c in (m.get("cells") or {}).get(name) or []:
                xy = _xy(c)
                if xy:
                    out.add(xy)
        for w in m.get("warps") or []:
            if isinstance(w.get("x"), int) and isinstance(w.get("y"), int):
                out.add((w["x"], w["y"]))
        probe = (scripts.get(mid) or {}).get("probe") or m.get("onStep") or {}
        for sig in probe.get("sigs") or []:
            if probe_kind(sig)[0] == "none":
                continue
            for c in sig.get("cells") or []:
                xy = _xy(c)
                if xy:
                    out.add(xy)
        for b in m.get("boulders") or []:
            if isinstance(b.get("x"), int):
                out.add((b["x"], b["y"]))
        return out

    def ok_cell(g, bad, x, y, mark="."):
        return g.inside(x, y) and g.st(x, y) == mark and (x, y) not in bad

    def line(mid, length):
        """(x, y, d): `length` open steps one way from a quiet cell, no ledge, nothing acting on the way."""
        g, bad = grids[mid], avoid(mid)
        for y in range(g.h):
            for x in range(g.w):
                for d in DIRS:
                    cx, cy, ok = x, y, True
                    for _ in range(length):
                        nx, ny = cx + DELTA[d][0], cy + DELTA[d][1]
                        if not ok_cell(g, bad, cx, cy) or g.v(cx, cy, d) != "." or not ok_cell(g, bad, nx, ny):
                            ok = False
                            break
                        cx, cy = nx, ny
                    if ok:
                        return x, y, d
        return None

    def back_and_forth(mid):
        g, bad = grids[mid], avoid(mid)
        for y in range(g.h):
            for x in range(g.w):
                for d in DIRS:
                    nx, ny = x + DELTA[d][0], y + DELTA[d][1]
                    if ok_cell(g, bad, x, y) and ok_cell(g, bad, nx, ny) and g.v(x, y, d) == "." \
                            and g.v(nx, ny, OPP[d]) == ".":
                        return x, y, d
        return None

    def any_cell(mid):
        g, bad = grids[mid], avoid(mid)
        return next(((x, y) for y in range(g.h) for x in range(g.w) if ok_cell(g, bad, x, y)), None)

    def shore(mid):
        """(x, y, d, wx, wy): a land cell facing surfable water (a walker refused 'w', a surfer let through)."""
        g, bad = grids[mid], avoid(mid)
        for y in range(g.h):
            for x in range(g.w):
                if not ok_cell(g, bad, x, y):
                    continue
                for d in DIRS:
                    wx, wy = x + DELTA[d][0], y + DELTA[d][1]
                    if ok_cell(g, bad, wx, wy, "~") and g.v(x, y, d) == "w" and g.v(x, y, d, True) == "." \
                            and g.v(wx, wy, OPP[d], True) == ".":
                        return x, y, d, wx, wy
        return None

    # Surf on (facing water) and off (surfing, facing land), with the badge; on without SOULBADGE (refused); fishing
    # from the same shore: the OLD ROD (always a bite in Gen 1) and the SUPER ROD (the map's pool or none)
    k = 0
    for mid in _ordered(FEATURE_MAPS["water"], grids):
        if k >= 2:
            break
        s = shore(mid)
        if not s:
            continue
        x, y, d, wx, wy = s
        add(map=mid, x=x, y=y, face=d, state="walk", cls="feature:surf_on", act="surf", ax=wx, ay=wy, expect="surf",
            wait=1800)
        add(map=mid, x=wx, y=wy, face=OPP[d], state="surf", cls="feature:surf_off", act="surf", ax=x, ay=y,
            expect="dismount", wait=1800)
        if k == 0:
            add(map=mid, x=x, y=y, face=d, state="walk", cls="feature:surf_nobadge", act="surf", ax=wx, ay=wy,
                badges=False, expect="refused")
            for rod in ("OLD_ROD", "SUPER_ROD"):
                add(map=mid, x=x, y=y, face=d, state="walk", cls="feature:fish_" + rod.lower(), act="fish", ax=wx,
                    ay=wy, item=rod, give=rod, expect="fish", wait=2400, idle=90)
        k += 1
    if not k:
        notes["surf:no_shore"] += 1
    # the bicycle: on (act, the BICYCLE in the bag), BIKE_STEPS steps on it (frames per step), off again (act); the
    # same steps on foot for the walking speed; refused indoors
    for mid in _ordered(FEATURE_MAPS["open"], grids):
        ln = line(mid, BIKE_STEPS + 1)
        if not ln:
            continue
        x, y, d = ln
        add(map=mid, x=x, y=y, face=d, state="walk", cls="feature:walk_speed", dir=d, dirs=",".join([d] * BIKE_STEPS),
            badges=False, expect="steps")
        add(map=mid, x=x, y=y, face=d, state="walk", cls="feature:bike_on", act="bicycle", bicycle=True, expect="bike_on")
        add(map=mid, x=0, y=0, follow=True, fwd=BIKE_STEPS, state="keep", cls="feature:bike_speed", bicycle=True,
            expect="steps")
        add(map=mid, x=0, y=0, follow=True, state="keep", cls="feature:bike_off", act="bicycle", bicycle=True,
            expect="bike_off")
        break
    else:
        notes["bike:no_line"] += 1
    for mid in _ordered(FEATURE_MAPS["indoor"], grids)[:1]:
        c = any_cell(mid)
        if c:
            add(map=mid, x=c[0], y=c[1], face="down", state="walk", cls="feature:bike_indoors", act="bicycle",
                bicycle=True, expect="refused")
    # the Cycling Road: on the bike, B held (the driver's hold until READY, then only the client's move.hold window),
    # then the roll; no step
    for mid in sorted(grids):
        if not (inv.get(mid) or {}).get("slope"):
            continue
        g, bad = grids[mid], avoid(mid)
        starts = [(x, y) for y in range(1, g.h - 5) for x in range(g.w)
                  if all(ok_cell(g, bad, x, y + i) and g.v(x, y + i, "down") == "." for i in range(4))]
        if starts:
            x, y = starts[len(starts) // 2]
            add(map=mid, x=x, y=y, face="down", state="bike", cls="feature:slope_brake", hold="b", brake=True,
                roll=BRAKE_ROLL_FRAMES, badges=False, expect="brake", wait=1800)
        break
    # Cut: a tree the engine cuts (the inventory's cut cells), faced from a cell the walker stands on
    cuts = 0
    for mid in _ordered(FEATURE_MAPS["cut"], grids):
        if cuts >= 2:
            break
        g, bad = grids[mid], avoid(mid)
        for c in (inv.get(mid) or {}).get("cells", {}).get("cut") or []:
            xy = _xy(c)
            hit = None
            for d in DIRS if xy else ():
                sx, sy = xy[0] - DELTA[d][0], xy[1] - DELTA[d][1]
                if ok_cell(g, bad, sx, sy) and g.v(sx, sy, d) in "xw":
                    hit = (sx, sy, d)
                    break
            if hit:
                add(map=mid, x=hit[0], y=hit[1], face=hit[2], state="walk", cls="feature:cut", act="cut", ax=xy[0],
                    ay=xy[1], expect="cut", wait=1800)
                cuts += 1
                break
    # Strength: the act (a boulder found at runtime, faced), then two steps into it (the first arms, the second pushes)
    nb = 0
    for mid in _ordered(FEATURE_MAPS["boulder"], [m for m in grids if (inv.get(m) or {}).get("boulders")]):
        if nb >= 2:
            break
        add(map=mid, x=0, y=0, find="boulder", state="walk", cls="feature:strength", act="strength", expect="strength",
            wait=1800)
        add(map=mid, x=0, y=0, follow=True, fwd=2, state="keep", cls="feature:strength_push", strengthAfter=True,
            expect="push", wait=1800)
        nb += 1
    # Flash in Rock Tunnel; Dig and the ESCAPE ROPE out of a cave; Teleport and Fly (to a visited town) outside
    for mid in _ordered(FEATURE_MAPS["dark"], grids)[:1]:
        c = any_cell(mid)
        if c:
            add(map=mid, x=c[0], y=c[1], face="down", state="walk", cls="feature:flash", act="flash", expect="lit",
                wait=1800)
    caves = [m for m in _ordered(FEATURE_MAPS["cave"], grids) if (inv.get(m) or {}).get("tileset") == "CAVERN"]
    for mid in caves[:1]:
        c = any_cell(mid)
        if c:
            add(map=mid, x=c[0], y=c[1], face="down", state="walk", cls="feature:dig", act="dig", expect="warp",
                wait=2400, idle=90)
            add(map=mid, x=c[0], y=c[1], face="down", state="walk", cls="feature:escape_rope", act="dig",
                item="ESCAPE_ROPE", give="ESCAPE_ROPE", badges=False, expect="warp", wait=2400, idle=90)
    for mid in _ordered(FEATURE_MAPS["open"], grids)[:1]:
        c = any_cell(mid)
        if c:
            add(map=mid, x=c[0], y=c[1], face="down", state="walk", cls="feature:teleport", act="teleport",
                expect="warp", wait=2400, idle=90)
            add(map=mid, x=c[0], y=c[1], face="down", state="walk", cls="feature:fly", act="fly", dest=FLY_DEST,
                expect="warp", wait=2400, idle=90)
    # step counters (§3.4): twenty steps there and back, the counters read before and after; a boarded day-care mon on
    # the first, a Safari game (set directly) on a Safari Zone map
    for mid in _ordered(FEATURE_MAPS["open"], grids):
        bf = back_and_forth(mid)
        if bf:
            x, y, d = bf
            add(map=mid, x=x, y=y, face=d, state="walk", cls="steps:walk20", dir=d,
                dirs=",".join([d, OPP[d]] * WALK20), daycare=True, badges=False, expect="steps", wait=2400)
            break
    for mid in _ordered(FEATURE_MAPS["safari"], grids)[:1]:
        bf = back_and_forth(mid)
        if bf:
            x, y, d = bf
            add(map=mid, x=x, y=y, face=d, state="walk", cls="steps:safari20", dir=d,
                dirs=",".join([d, OPP[d]] * WALK20), safari=True, badges=False, expect="steps", wait=2400)
    for i, r in enumerate(rows, 1):
        r["n"] = i
    if notes:
        print("FEATURE NOTES (left out):", dict(sorted(notes.items())))
    return rows


def lua_feature_rows(rows: list[dict]) -> str:
    def val(v):
        if isinstance(v, bool):
            return "true" if v else "false"
        if isinstance(v, (int, float)):
            return str(v)
        return '"' + str(v).replace("\\", "\\\\").replace('"', '\\"') + '"'
    out = ["return {"]
    for r in rows:
        keep = {k: r[k] for k in ("n", "map", "x", "y", "dir", "face", "dirs", "state", "cls", "fresh", "push", "wait",
                                  "idle", "act", "ax", "ay", "item", "dest", "hold", "find", "strength", "badges",
                                  "bicycle", "give", "follow", "fwd", "strengthAfter", "brake", "roll", "daycare",
                                  "safari", "stepwait")
                if k in r and r[k] is not None}
        out.append("  { " + ", ".join(f"{k} = {val(v)}" for k, v in keep.items()) + " },")
    out.append("}")
    return "\n".join(out) + "\n"


def cmd_feature_rows() -> int:
    rows = make_features()
    print(f"FEATURE ROWS {len(rows)}:", dict(Counter(r["cls"] for r in rows)))
    for r in rows:
        print(f"  #{r['n']} {r['cls']} {r['map']}" + (" (follows)" if r.get("follow") else ""))
    return 0


def cmd_features(timeout: float, tag: str) -> int:
    rows = make_features()
    FEATURES.write_text(lua_feature_rows(rows), encoding="utf-8")
    fc.write(FEATURES_JSON, rows)
    print(f"FEATURE ROWS {len(rows)}:", dict(Counter(r["cls"] for r in rows)))
    out = REPORTS / f"g1_field_features{tag}.json"
    received, done, answered, result = fc.run_rows(DRIVER, GAME, "sweep", SWEEP_PORT, timeout,
                                                   {"KB_ROWS": str(FEATURES.resolve()), "KB_SWEEP_FILE": out.name},
                                                   party=BLUE_PARTY)
    fc.write(REPORTS / f"g1_field_features_stream{tag}.json", {"received": received, "rows": done})
    sweep = json.loads(out.read_text(encoding="utf-8"))
    report_features(rows, sweep, received, done)
    return 0 if result.returncode == 0 else 1


def report_features(rows, sweep, received, done) -> None:
    """Per row: act.result, the step answers and their frames, the engine's own step frames, warps, the end cell and
    state, the counters' change; and what the client did on the brake row (its move.hold window)."""
    by_n = {r["n"]: r for r in rows}
    print("\nFEATURES:")
    for rec in sweep.get("rows") or []:
        n = rec["n"]
        row = by_n.get(n, {})
        d = done.get(n) or done.get(str(n)) or {}
        msgs = fc.row_messages(received, n)
        sf = fc.stream_facts(msgs)
        steps = rec.get("stepsSeen") or []
        cb, ca = rec.get("countersBefore") or {}, rec.get("countersAfter") or {}
        delta = {k: (ca.get(k) - cb.get(k)) for k in ca if isinstance(ca.get(k), (int, float))
                 and isinstance(cb.get(k), (int, float)) and ca.get(k) != cb.get(k)}
        gone = sorted(k for k in cb if k not in ca)
        answers = [a for a in (d.get("answers") or []) if a]
        frames = [((a.get("data") or {}).get("kind") or (a.get("data") or {}).get("reason"),
                   (a.get("data") or {}).get("frames")) for a in answers]
        act = d.get("act")
        act_s = None if act is None else (act.get("kind") if act.get("ok") else "refused:" + str(act.get("reason")))
        e = rec.get("end") or {}
        st = rec.get("startState") or {}
        line = (f"  #{n} {row.get('cls')} {rec.get('map')} [{rec.get('state')}]: act {act_s if row.get('act') else '-'}; "
                f"answers {len(answers)} {frames}; engine steps {[(x.get('f'), 'bike' if x.get('bike') else 'foot') for x in steps][:8]}"
                f"{'...' if len(steps) > 8 else ''} (todSteps +{rec.get('steps')}); "
                f"warps {[w['kind'] for w in sf['warps'] if w['phase'] == 'arrive']}; modes {sf['modes']}; "
                f"end {e.get('map')}:{e.get('x')},{e.get('y')} surf={e.get('surfing')} bike={e.get('biking')} "
                f"strength={st.get('strength')}->{e.get('strength')} dark={st.get('dark')}->{e.get('dark')}; "
                f"client {sf['last']}; counters {delta}" + (f" (gone: {gone})" if gone else "")
                + (f"; battle {rec.get('battle')} battle.start x{sf['types'].get('battle.start', 0)}" if row.get("act") == "fish" else "")
                + (f"; heal point {st.get('heal')}" if row.get("act") in ("dig", "teleport") else "")
                + f"; err {rec.get('err')}")
        print(line)
        if row.get("brake"):
            b = rec.get("brake") or {}
            print(f"     brake: B held by the driver until READY at {b.get('x')},{b.get('y')}; the client held B "
                  f"{'(move.hold on, ~0.2 s, then off)' if row.get('hold') == 'b' else '(no hold)'} with no step and "
                  f"wrote the go: the bike was at {b.get('ctlX')},{b.get('ctlY')} with {b.get('stepsBeforeGo')} "
                  f"steps started; the roll after the go: {len(steps) - (b.get('stepsBeforeGo') or 0)} steps in "
                  f"{row.get('roll')} frames, then off the bike")
        if row.get("cls", "").startswith("steps:") and steps:
            psn = [(x.get("psn"), x.get("hp")) for x in steps]
            print(f"     poison counter / poisoned HP per step: {psn}")


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
    p = sub.add_parser("rows")
    p.add_argument("--class", dest="cls", default="")
    p.add_argument("--maps", default="")
    p = sub.add_parser("sweep")
    p.add_argument("--timeout", type=float, default=14400.0)
    p.add_argument("--class", dest="cls", default="")
    p.add_argument("--maps", default="")
    p.add_argument("--tag", default="")
    p = sub.add_parser("report")
    p.add_argument("--tag", default="")
    sub.add_parser("feature-rows")
    p = sub.add_parser("features")
    p.add_argument("--timeout", type=float, default=3600.0)
    p.add_argument("--tag", default="")
    a = ap.parse_args(argv)
    if a.cmd == "features":
        return cmd_features(a.timeout, a.tag)
    if a.cmd == "feature-rows":
        return cmd_feature_rows()
    if a.cmd == "rows":
        return cmd_rows(a.cls, a.maps)
    if a.cmd == "sweep":
        return cmd_sweep(a.timeout, a.cls, a.maps, a.tag)
    if a.cmd == "report":
        report(json.loads((REPORTS / f"g1_field_sweep_analysis{a.tag}.json").read_text(encoding="utf-8")))
        return 0
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
