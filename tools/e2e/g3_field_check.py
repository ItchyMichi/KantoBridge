"""Emerald's field mechanics audit on the real engine (docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md §4).

    python tools/e2e/g3_field_check.py survey [--maps A,B]   # FA1 + FA2's dump: classes, warps, verdicts per cell
    python tools/e2e/g3_field_check.py scripts               # FA1's script part (coord events, warps, moves, ...)
    python tools/e2e/g3_field_check.py summary               # the inventory's counts again
    python tools/e2e/g3_field_check.py sweep [--class C] [--maps A,B]  # FA3-FA5: the live rows, a stand-in client
    python tools/e2e/g3_field_check.py report                # the sweep's analysis again

The driver is drivers/kb_g3field.lua. Its files in local/reports/ (g3_field_inventory.json, g3_field_parity.json,
g3_field_scripts.json, g3_field_sweep*.json) are ROM-derived and stay in local/; this script prints only map symbols,
class and behaviour names, ids, kinds and counts.
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

GAME = "emerald"
DRIVER = "kb_g3field.lua"
INVENTORY = REPORTS / "g3_field_inventory.json"
PARITY = REPORTS / "g3_field_parity.json"
SCRIPTS = REPORTS / "g3_field_scripts.json"
SUMMARY = REPORTS / "g3_field_inventory_summary.json"

# What each class has as a path to Minecraft today (the plan's §1 table, checked in FA1/FA3): explicit = its own
# message kind; generic = arrives as `script` (or `door`); none = nothing reaches the client
PATH_TODAY = {
    "ledge_jump": "explicit (ledge)", "ledge_diagonal": "none (engine port: unused?)",
    "impassable_side": "verdict only (no collider)", "impassable_pair": "verdict only",
    "walk_tile": "generic (script)", "slide_tile": "generic (script)", "slippery_floor": "generic (script)",
    "ice": "generic (script)", "thin_ice": "generic (script) + story.cells", "cracked_ice": "generic (script) + story.cells",
    "current": "generic (script)", "waterfall": "generic (script)", "spin_tile": "generic (script)",
    "dive_spot": "act.field dive; warp kind door", "no_surfacing": "none",
    "cracked_floor": "story.cells; warp kind door", "cracked_floor_hole": "warp kind door",
    "fall_warp": "n/a in Emerald", "mt_pyre_hole": "warp kind door", "warp_pad": "warp kind door?",
    "lavaridge_warp": "warp kind door", "mossdeep_gym_warp": "warp kind door", "bridge_over_ocean": "warp kind door",
    "ladder": "warp kind script", "escalator": "warp kind door", "arrow_warp": "warp kind script",
    "warp_door": "warp kind script", "deep_south_warp": "warp kind script",
    "muddy_slope": "generic (script)", "bumpy_slope": "refusal (tile)", "rail": "refusal (tile)",
    "bike_bridge": "none", "no_running": "none (no running)", "run_bike_blocked": "none",
    "long_grass": "none", "hot_springs": "none", "ash_grass": "story.cells", "fortree_bridge": "story.cells",
    "pacifidlog_log": "story.cells", "pond_bridge": "none", "strength_button": "none",
    "secret_base_mat": "left out (D8)", "forced_tile": "generic (script)",
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
    beh = Counter()
    rse_only = Counter()
    warp_beh = Counter()
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
        for k, n in (m.get("behaviors") or {}).items():
            beh[k] += n
            try:
                if int(k.rsplit(":", 1)[1]) >= 0x100:
                    rse_only[k] += n
            except ValueError:
                pass
        for w in m.get("warps") or []:
            warp_beh[w.get("behName") or "none"] += 1
    surveyed = [mid for mid, r in par["maps"].items() if r.get("walk")]
    left = [mid for mid, r in par["maps"].items() if r.get("left")]
    varies = sum(r.get("varies", 0) for r in par["maps"].values())
    print(f"\nINVENTORY: {len(maps)} maps ({len(skipped)} left out by D8, {len(errs)} without a layout); "
          f"verdicts on {len(surveyed)} maps, {len(left)} left their map on entry; {varies} cell-directions depend on "
          f"the walker's elevation")
    print("\nCLASSES (cells, maps, path today):")
    for c, n in sorted(cls_cells.items(), key=lambda kv: (-kv[1], kv[0])):
        ms = sorted(cls_maps[c])
        shown = ", ".join(ms[:8]) + ("..." if len(ms) > 8 else "")
        print(f"  {c}: {n} cells on {len(ms)} maps [{PATH_TODAY.get(c, '-')}] {shown}")
    absent = sorted(set(PATH_TODAY) - set(cls_cells))
    print("\nCLASSES ABSENT:", ", ".join(absent) or "none")
    print(f"\nBEHAVIOURS >= 0x100 (Emerald-only canonical ids, the export's % 256 question): {len(rse_only)} ids, "
          f"{sum(rse_only.values())} cells")
    for k, n in sorted(rse_only.items(), key=lambda kv: -kv[1]):
        print(f"  {k}: {n}")
    print("\nWARP CELLS BY BEHAVIOUR:")
    for k, n in warp_beh.most_common():
        print(f"  {k}: {n}")
    out = {"maps": len(maps), "skipped": len(skipped), "errors": errs, "surveyed": len(surveyed), "left": left,
           "classes": {c: {"cells": n, "maps": sorted(cls_maps[c])} for c, n in cls_cells.items()},
           "absent": absent, "rse_only": dict(rse_only), "warp_behaviours": dict(warp_beh), "varies": varies}
    fc.write(SUMMARY, out)
    return 0


def script_summary() -> int:
    data = json.loads(SCRIPTS.read_text(encoding="utf-8"))
    print("TOTALS", json.dumps(data["totals"], sort_keys=True))
    ops = Counter()
    by_root = Counter()
    moves_maps = set()
    cb = Counter()
    specials = Counter()
    coord_maps = Counter()
    for mid, m in data["maps"].items():
        if m.get("coord"):
            coord_maps[mid] = len([c for c in m["coord"] if c.get("script")])
        for f in m.get("found") or []:
            ops[f["op"]] += 1
            by_root[(f["op"], f["root"])] += 1
            if f["op"].startswith("applymovement"):
                moves_maps.add(mid)
            if f["op"] == "special":
                specials[f["name"]] += 1
        for c in m.get("stepCallbacks") or []:
            cb[(c, mid)] += 1
    print("\nOPS (rows reachable, no conditions):", dict(sorted(ops.items())))
    print("BY ROOT:")
    for (op, root), n in sorted(by_root.items()):
        print(f"  {op} from {root}: {n}")
    print(f"\nCOORD EVENTS WITH A SCRIPT: {sum(coord_maps.values())} on {len(coord_maps)} maps")
    print(f"PLAYER MOVES on {len(moves_maps)} maps")
    print("STEP CALLBACKS (id: maps):")
    per = defaultdict(list)
    for (c, mid) in cb:
        per[c].append(mid)
    for c in sorted(per):
        print(f"  {c}: {', '.join(sorted(per[c]))}")
    print("SPECIALS (name: rows):", dict(sorted(specials.items())))
    return 0


# ---- FA3: the live sweep's rows (tiles), from the inventory and the parity dump --------------------------------

DIRS = ("up", "down", "left", "right")
DELTA = {"up": (0, -1), "down": (0, 1), "left": (-1, 0), "right": (1, 0)}
OPP = {"up": "down", "down": "up", "left": "right", "right": "left"}
SWEEP = REPORTS / "g3_field_sweep.json"
ROWS = REPORTS / "g3_field_rows.lua"
ROWS_JSON = REPORTS / "g3_field_rows.json"
SWEEP_ANALYSIS = REPORTS / "g3_field_sweep_analysis.json"
SWEEP_PORT = 27231
# forced-movement classes (forced_movement.lua TABLE_RSE): entered from every open side (§4.2)
FORCED = ("walk_tile", "slide_tile", "slippery_floor", "ice", "thin_ice", "current", "waterfall", "muddy_slope")
WATER_FORCED = ("current", "waterfall")
# classes whose every cell is stepped on (they change the world or drop the player)
EVERY_CELL = ("cracked_floor", "cracked_floor_hole", "ash_grass", "fortree_bridge", "pacifidlog_log", "thin_ice")
# 10 per map (D9)
SAMPLED = ("tall_grass", "sand", "puddle", "shallow_water", "long_grass", "pond_bridge", "hot_springs", "no_running",
           "counter")
ARROWS = {"SOUTH_ARROW_WARP": "down", "NORTH_ARROW_WARP": "up", "EAST_ARROW_WARP": "right", "WEST_ARROW_WARP": "left",
          "WATER_SOUTH_ARROW_WARP": "down", "DEEP_SOUTH_WARP": "down"}
PER_MAP_SAMPLE = 10
# map states a mechanism needs that a new game hasn't reached (the engine's own tests set them: tests/drivers/
# em_sky_pillar_cracks.lua sets VAR_SKY_PILLAR_STATE 2), by map prefix
MAP_STATE = {"EM_SKY_PILLAR_": {"VAR_SKY_PILLAR_STATE": 2}}


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


def make_rows(only_class: str = "", only_maps: str = "") -> list[dict]:
    inv = json.loads(INVENTORY.read_text(encoding="utf-8"))["maps"]
    par = json.loads(PARITY.read_text(encoding="utf-8"))["maps"]
    want_maps = set(filter(None, only_maps.split(",")))
    rows: list[dict] = []
    seen = set()

    def add(**r):
        key = (r["map"], r["x"], r["y"], r.get("dir"), r.get("state"), r["cls"], r.get("script") or r.get("key"),
               r.get("tx"), r.get("ty"))
        if key in seen:
            return
        seen.add(key)
        if only_class and r["cls"] != only_class and not r["cls"].startswith(only_class + ":"):
            return
        rows.append(r)

    def approach(g, tx, ty, surf_ok=True, walk_ok=True, prefer=None, avoid=frozenset()):
        """(sx, sy, dir, state) for a step onto (tx, ty) the game allows, or None; never from a cell in avoid."""
        order = [prefer] if prefer else []
        order += [d for d in DIRS if d != prefer]
        for d in order:
            dx, dy = DELTA[d]
            sx, sy = tx - dx, ty - dy
            if not g.inside(sx, sy) or (sx, sy) in avoid:
                continue
            if walk_ok and g.st(sx, sy) == "." and g.v(sx, sy, d) in ".J":
                return sx, sy, d, "walk"
            if surf_ok and g.st(sx, sy) == "~" and g.v(sx, sy, d, True) == ".":
                return sx, sy, d, "surf"
        return None

    for mid in sorted(par):
        prec = par[mid]
        if not prec.get("walk") or (want_maps and mid not in want_maps):
            continue
        g = Grid(prec)
        m = inv.get(mid) or {}
        cells = m.get("cells") or {}
        forced_cells = set()
        for c in FORCED:
            forced_cells |= {tuple(xy) for xy in cells.get(c) or []}
        # ledges: the hop from the uphill side, the refusal from the downhill side
        for x, y in cells.get("ledge_jump") or []:
            for d in DIRS:
                dx, dy = DELTA[d]
                sx, sy = x - dx, y - dy
                if g.inside(sx, sy) and g.st(sx, sy) == "." and g.v(sx, sy, d) == "J":
                    add(map=mid, x=sx, y=sy, dir=d, state="walk", cls="ledge_jump", tx=x, ty=y, expect="hop")
                    bx, by = x + dx, y + dy
                    if g.inside(bx, by) and g.st(bx, by) == "." and g.v(bx, by, OPP[d]) == "x":
                        add(map=mid, x=bx, y=by, dir=OPP[d], state="walk", cls="ledge_back", tx=x, ty=y, expect="blocked")
        # one-way sides: a refused step out of (or into) the cell
        for x, y in cells.get("impassable_side") or []:
            for d in DIRS:
                dx, dy = DELTA[d]
                if g.st(x, y) == "." and g.inside(x + dx, y + dy) and g.st(x + dx, y + dy) == "." and g.v(x, y, d) == "x":
                    add(map=mid, x=x, y=y, dir=d, state="walk", cls="impassable_side", tx=x + dx, ty=y + dy, expect="blocked")
                    break
        # forced movement: every entry from a cell that isn't forced
        for c in FORCED:
            for x, y in cells.get(c) or []:
                for d in DIRS:
                    dx, dy = DELTA[d]
                    sx, sy = x - dx, y - dy
                    if not g.inside(sx, sy) or (sx, sy) in forced_cells:
                        continue
                    if c in WATER_FORCED:
                        if g.st(sx, sy) == "~" and g.v(sx, sy, d, True) == ".":
                            add(map=mid, x=sx, y=sy, dir=d, state="surf", cls=c, tx=x, ty=y, expect="forced")
                    elif g.st(sx, sy) == "." and g.v(sx, sy, d) == ".":
                        add(map=mid, x=sx, y=sy, dir=d, state="walk", cls=c, tx=x, ty=y, expect="forced")
        # every cell stepped on
        # cells a row must not start on: they act on arrival (a crack gives way, ice slides, a current pushes)
        acting = {tuple(xy) for c in ("cracked_floor", "cracked_floor_hole") for xy in cells.get(c) or []}
        for c in EVERY_CELL:
            for x, y in cells.get(c) or []:
                a = approach(g, x, y, surf_ok=False, avoid=frozenset(acting))
                if a:
                    add(map=mid, x=a[0], y=a[1], dir=a[2], state=a[3], cls=c if c != "thin_ice" else "thin_ice_cell", tx=x, ty=y,
                        expect="fall" if c.startswith("cracked") else "write", fresh=c.startswith("cracked") or c == "thin_ice")
        # bike tiles refused on foot (the Acro check)
        for c in ("rail", "bumpy_slope"):
            for x, y in cells.get(c) or []:
                for d in DIRS:
                    dx, dy = DELTA[d]
                    sx, sy = x - dx, y - dy
                    if g.inside(sx, sy) and g.st(sx, sy) == "." and g.v(sx, sy, d) == "a":
                        add(map=mid, x=sx, y=sy, dir=d, state="walk", cls=c, tx=x, ty=y, expect="blocked")
                        break
        # 10 per map
        for c in SAMPLED:
            n = 0
            for x, y in (cells.get(c) or [])[: PER_MAP_SAMPLE * 3]:
                if n >= PER_MAP_SAMPLE:
                    break
                a = approach(g, x, y)
                if c == "counter":
                    # a refused step into the counter
                    for d in DIRS:
                        dx, dy = DELTA[d]
                        sx, sy = x - dx, y - dy
                        if g.inside(sx, sy) and g.st(sx, sy) == ".":
                            add(map=mid, x=sx, y=sy, dir=d, state="walk", cls=c, tx=x, ty=y, expect="blocked")
                            n += 1
                            break
                elif a:
                    add(map=mid, x=a[0], y=a[1], dir=a[2], state=a[3], cls=c, tx=x, ty=y, expect="step")
                    n += 1
        # warps: every warp cell, by its mechanism
        for wp in m.get("warps") or []:
            x, y, beh = wp.get("x"), wp.get("y"), wp.get("behName") or "none"
            if x is None or not g.inside(x, y):
                continue
            if beh in ARROWS:
                d = ARROWS[beh]
                state = "surf" if g.st(x, y) == "~" else "walk"
                if g.st(x, y) in ".~":
                    add(map=mid, x=x, y=y, dir=d, state=state, cls="warp:" + beh, tx=x, ty=y, expect="warp", push=True,
                        fresh=True, warp=wp.get("n"))
                continue
            a = approach(g, x, y, prefer="up")
            if a:
                add(map=mid, x=a[0], y=a[1], dir=a[2], state=a[3], cls="warp:" + beh, tx=x, ty=y, expect="warp",
                    fresh=True, warp=wp.get("n"))
    # FA4: scripts that move, warp or change the world during a visit
    if SCRIPTS.is_file() and (not only_class or only_class in ("coord", "script", "onframe")):
        sc = json.loads(SCRIPTS.read_text(encoding="utf-8"))["maps"]
        for mid in sorted(sc):
            prec = par.get(mid) or {}
            if not prec.get("walk") or (want_maps and mid not in want_maps):
                continue
            g = Grid(prec)
            sm = sc[mid]
            found = sm.get("found") or []
            # coord events: stepped onto, their var at its value (temp vars 0x4000-0x400F are cleared on load: left as is)
            for ev in sm.get("coord") or []:
                if not ev.get("script") or ev.get("x") is None:
                    continue
                x, y = ev["x"], ev["y"]
                if not g.inside(x, y):
                    continue
                a = approach(g, x, y)
                if not a:
                    continue
                var = ev.get("var")
                vars_ = {var: ev.get("value") or 0} if var and not (0x4000 <= var <= 0x400F) and var < 0x8000 else None
                ops = sorted({f["op"] for f in found if f.get("root") == "coord" and (f.get("rootInfo") or {}).get("n") == ev["n"]})
                r = dict(map=mid, x=a[0], y=a[1], dir=a[2], state=a[3], cls="coord", tx=x, ty=y, expect="script",
                         fresh=True, wait=1800, ops=ops)
                if vars_:
                    r["vars"] = vars_
                add(**r)
            # object and sign scripts with a warp or a player move: run as the engine runs them (driven)
            keys = {}
            for f in found:
                if f.get("root") in ("object", "bg") and (f["op"].startswith("warp") or f["op"] == "applymovement"):
                    keys.setdefault(f.get("rootKey"), (f.get("root"), f.get("rootInfo") or {}, set()))[2].add(f["op"])
            for key, (root, info, ops) in sorted(keys.items(), key=lambda kv: str(kv[0])):
                if not key or not any(o.startswith("warp") for o in ops):
                    continue
                sx = sy = None
                for y in range(g.h):
                    for x in range(g.w):
                        if g.st(x, y) == ".":
                            sx, sy = x, y
                            break
                    if sx is not None:
                        break
                if sx is None:
                    continue
                r = dict(map=mid, x=sx, y=sy, state="walk", cls="script", tx=sx, ty=sy, expect="script", fresh=True,
                         trigger="script", script=key, wait=2400, ops=sorted(ops), root=root)
                if info.get("localId"):
                    r["lid"] = info["localId"]
                add(**r)
            # onFrame entry scripts with a warp: the warp in with their var at its value
            for f in found:
                if f.get("root") == "onFrame" and f["op"].startswith("warp"):
                    info = f.get("rootInfo") or {}
                    var = info.get("var")
                    if not var:
                        continue
                    arr = None
                    for y in range(g.h):
                        for x in range(g.w):
                            if g.st(x, y) == ".":
                                arr = (x, y)
                                break
                        if arr:
                            break
                    if not arr:
                        continue
                    add(map=mid, x=arr[0], y=arr[1], state="walk", cls="onframe", tx=arr[0], ty=arr[1], expect="script",
                        trigger="warp", vars={var: info.get("value") or 0}, wait=2400, ops=[f["op"]], key=f.get("rootKey"))
    for i, r in enumerate(rows, 1):
        r["n"] = i
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
        # slow mechanics (a fall, a script, a warp's fade) get a longer quiet before the row ends
        if r.get("expect") in ("fall", "warp", "script") or r["cls"] in ("cracked_floor", "thin_ice_cell"):
            r.setdefault("idle", 90)
        keep = {k: r[k] for k in ("n", "map", "x", "y", "dir", "state", "cls", "fresh", "push", "wait", "trigger", "script",
                                  "lid", "idle") if k in r}
        parts = [f"{k} = {val(v)}" for k, v in keep.items()]
        if r.get("vars"):
            parts.append("vars = { " + ", ".join(f"[{int(k)}] = {int(v)}" for k, v in r["vars"].items()) + " }")
        varn = dict(r.get("varn") or {})
        for prefix, st in MAP_STATE.items():
            if r["map"].startswith(prefix):
                varn.update(st)
        if varn:
            parts.append("varn = { " + ", ".join(f"{k} = {int(v)}" for k, v in varn.items()) + " }")
        out.append("  { " + ", ".join(parts) + " },")
    out.append("}")
    return "\n".join(out) + "\n"


def cmd_rows(only_class: str, maps: str) -> int:
    rows = make_rows(only_class, maps)
    by = Counter(r["cls"] if not r["cls"].startswith("warp:") else "warp" for r in rows)
    print(f"ROWS {len(rows)} on {len({r['map'] for r in rows})} maps:", dict(sorted(by.items())))
    return 0


def cmd_sweep(timeout: float, only_class: str, maps: str, tag: str) -> int:
    rows = make_rows(only_class, maps)
    ROWS.write_text(lua_rows(rows), encoding="utf-8")
    fc.write(ROWS_JSON, rows)
    print(f"ROWS {len(rows)} on {len({r['map'] for r in rows})} maps")
    out = REPORTS / f"g3_field_sweep{tag}.json"
    received, done, answered, result = fc.run_rows(DRIVER, GAME, "sweep", SWEEP_PORT, timeout,
                                                   {"KB_ROWS": str(ROWS.resolve()), "KB_SWEEP_FILE": out.name})
    fc.write(REPORTS / f"g3_field_stream{tag}.json", {"received": received, "rows": done})
    analysis = analyse(rows, json.loads(out.read_text(encoding="utf-8")), received, done)
    fc.write(REPORTS / f"g3_field_sweep_analysis{tag}.json", analysis)
    report(analysis)
    return 0 if result.returncode == 0 else 1


# the warp kind the protocol's mechanism names (D2) for each Emerald behaviour, and what the bridge says today
WANT_KIND = {"LADDER": "ladder", "UP_ESCALATOR": "escalator", "DOWN_ESCALATOR": "escalator", "MT_PYRE_HOLE": "hole",
             "LAVARIDGE_GYM_1F_WARP": "hole", "LAVARIDGE_GYM_B1F_WARP": "pad", "AQUA_HIDEOUT_WARP": "pad",
             "MOSSDEEP_GYM_WARP": "pad", "WARP_DOOR": "door", "NORMAL": "door", "CAVE_DOOR": "door",
             "SOUTH_ARROW_WARP": "edge", "NORTH_ARROW_WARP": "edge", "EAST_ARROW_WARP": "edge", "WEST_ARROW_WARP": "edge",
             "WATER_SOUTH_ARROW_WARP": "edge", "DEEP_SOUTH_WARP": "edge", "WATER_DOOR": "door", "CAVE": "stairs",
             "TRICK_HOUSE_PUZZLE_DOOR": "door", "CRACKED_FLOOR_HOLE": "hole", "cracked_floor": "hole"}


def analyse(rows: list[dict], sweep: dict, received: list[dict], done: dict) -> dict:
    return fc.analyse_rows(rows, sweep, received, done, WANT_KIND)


def report(analysis: dict) -> None:
    fc.report_rows(analysis)

# ---- FA5: features and step counters (§3.3, §3.4) -------------------------------------------------------------------

FEATURES = REPORTS / "g3_field_features_rows.lua"
FEATURE_MAPS = {
    "boulder": ["EM_VICTORY_ROAD_B1F", "EM_SEAFLOOR_CAVERN_ROOM1", "EM_SEAFLOOR_CAVERN_ROOM2", "EM_SEAFLOOR_CAVERN_ROOM5"],
    "cut_tree": ["EM_ROUTE116", "EM_ROUTE104", "EM_PETALBURG_WOODS", "EM_ROUTE119"],
    "rock": ["EM_ROUTE111", "EM_ROUTE115", "EM_GRANITE_CAVE_B2F", "EM_SAFARI_ZONE_NORTH"],
}


def make_features() -> list[dict]:
    inv = json.loads(INVENTORY.read_text(encoding="utf-8"))["maps"]
    par = json.loads(PARITY.read_text(encoding="utf-8"))["maps"]
    rows: list[dict] = []

    def add(**r):
        r.setdefault("badges", True)
        r.setdefault("wait", 1500)
        r.setdefault("idle", 60)
        r.setdefault("fresh", True)
        rows.append(r)

    def grid(mid):
        rec = par.get(mid) or {}
        return Grid(rec) if rec.get("walk") else None

    def line(g, length):
        """A walkable cell with `length` open steps ahead in one direction (no ledge), or None."""
        for y in range(g.h):
            for x in range(g.w):
                for d in DIRS:
                    cx, cy, ok = x, y, True
                    for _ in range(length):
                        if g.st(cx, cy) != "." or g.v(cx, cy, d) != ".":
                            ok = False
                            break
                        cx, cy = cx + DELTA[d][0], cy + DELTA[d][1]
                    if ok:
                        return x, y, d
        return None

    # running: B held through four steps, the running shoes' flag set, on maps whose header allows running
    n = 0
    for mid in ("EM_ROUTE101", "EM_ROUTE103", "EM_PETALBURG_CITY", "EM_RUSTBORO_CITY"):
        g = grid(mid)
        if not g or (inv.get(mid) or {}).get("allowRunning") in (0, False):
            continue
        ln = line(g, 4)
        if ln:
            add(map=mid, x=ln[0], y=ln[1], dir=ln[2], dirs=",".join([ln[2]] * 4), state="walk", cls="feature:run",
                hold="b", flagn={"FLAG_SYS_B_DASH": True}, expect="feature")
            n += 1
        if n >= 2:
            break
    # the bikes: Mach five steps (its speeds), Acro three; B held on the Acro (a wheelie)
    for mid in ("EM_ROUTE110", "EM_ROUTE111", "EM_ROUTE101"):
        g = grid(mid)
        ln = g and line(g, 6)
        if ln:
            add(map=mid, x=ln[0], y=ln[1], dir=ln[2], dirs=",".join([ln[2]] * 5), state="mach", cls="feature:mach",
                expect="feature")
            add(map=mid, x=ln[0], y=ln[1], dir=ln[2], dirs=",".join([ln[2]] * 3), state="acro", cls="feature:acro",
                expect="feature")
            add(map=mid, x=ln[0], y=ln[1], dir=ln[2], dirs=",".join([ln[2]] * 2), state="acro", cls="feature:acro_wheelie",
                hold="b", expect="feature")
            break
    # Mach up a muddy slope; on foot up it
    for mid, m in sorted(inv.items()):
        cells = (m.get("cells") or {}).get("muddy_slope") or []
        g = grid(mid)
        if not cells or not g:
            continue
        x, y = cells[0]
        if g.inside(x, y + 1) and g.st(x, y + 1) == "." and g.v(x, y + 1, "up") == ".":
            add(map=mid, x=x, y=y + 1, dir="up", dirs="up,up", state="walk", cls="feature:muddy_foot", expect="feature")
            add(map=mid, x=x, y=y + 1, dir="up", dirs="up,up", state="mach", cls="feature:muddy_mach", expect="feature")
            break
    # Surf on and off; Dive and emerge; Waterfall
    k = 0
    for mid in sorted(par):
        g = grid(mid)
        if not g or k >= 2:
            continue
        done = False
        for y in range(g.h):
            for x in range(g.w):
                if done or g.st(x, y) != ".":
                    continue
                for d in DIRS:
                    wx, wy = x + DELTA[d][0], y + DELTA[d][1]
                    if g.inside(wx, wy) and g.st(wx, wy) == "~" and g.v(x, y, d) == "w" and g.v(x, y, d, True) == ".":
                        add(map=mid, x=x, y=y, face=d, state="walk", cls="feature:surf_on", act="surf", ax=wx, ay=wy,
                            expect="feature")
                        add(map=mid, x=wx, y=wy, face=OPP[d], state="surf", cls="feature:surf_off", act="surf", ax=x, ay=y,
                            expect="feature")
                        done = True
                        k += 1
                        break
    for mid, m in sorted(inv.items()):
        cells = (m.get("cells") or {}).get("dive_spot") or []
        g = grid(mid)
        if cells and g and (m.get("type") != 5):
            x, y = cells[len(cells) // 2]
            if g.st(x, y) == "~":
                add(map=mid, x=x, y=y, face="down", state="surf", cls="feature:dive", act="dive", expect="feature")
                if sum(1 for r in rows if r["cls"] == "feature:dive") >= 3:
                    break
    for mid, m in sorted(inv.items()):
        g = grid(mid)
        if m.get("type") == 5 and g:
            no = {tuple(c) for c in (m.get("cells") or {}).get("no_surfacing") or []}
            pick = next(((x, y) for y in range(g.h) for x in range(g.w) if g.st(x, y) in ".~" and (x, y) not in no), None)
            if pick:
                add(map=mid, x=pick[0], y=pick[1], face="down", state="underwater", cls="feature:emerge", act="dive",
                    expect="feature")
                if sum(1 for r in rows if r["cls"] == "feature:emerge") >= 3:
                    break
    for mid, m in sorted(inv.items()):
        cells = {tuple(c) for c in (m.get("cells") or {}).get("waterfall") or []}
        g = grid(mid)
        if not cells or not g:
            continue
        for x, y in sorted(cells):
            if (x, y + 1) not in cells and g.inside(x, y + 1) and g.st(x, y + 1) == "~":
                add(map=mid, x=x, y=y + 1, face="up", state="surf", cls="feature:waterfall", act="waterfall",
                    ax=x, ay=y, expect="feature", wait=2400)
                break
        if sum(1 for r in rows if r["cls"] == "feature:waterfall") >= 3:
            break
    # Strength, Cut, Rock Smash on the objects the driver finds
    for mid in FEATURE_MAPS["boulder"]:
        if mid in par:
            add(map=mid, x=0, y=0, find="boulder", strength=True, state="walk", cls="feature:strength", expect="feature")
    for mid in FEATURE_MAPS["cut_tree"]:
        if mid in par:
            add(map=mid, x=0, y=0, find="cut_tree", act="cut", state="walk", cls="feature:cut", expect="feature")
    for mid in FEATURE_MAPS["rock"]:
        if mid in par:
            add(map=mid, x=0, y=0, find="rock", act="rock_smash", state="walk", cls="feature:rock_smash", expect="feature")
    # Flash in a cave, Dig out of it, Teleport outside, Fly (the game's own list)
    for mid, act, cls in (("EM_GRANITE_CAVE_1F", "flash", "feature:flash"), ("EM_GRANITE_CAVE_1F", "dig", "feature:dig"),
                          ("EM_ROUTE104", "teleport", "feature:teleport"), ("EM_ROUTE104", "fly", "feature:fly")):
        g = grid(mid)
        pick = g and next(((x, y) for y in range(g.h) for x in range(g.w) if g.st(x, y) == "."), None)
        if pick:
            add(map=mid, x=pick[0], y=pick[1], face="down", state="walk", cls=cls, act=act, expect="feature", wait=2400)
    # step counters (§3.4): twenty steps back and forth, the counters read before and after
    for mid in ("EM_ROUTE101", "EM_ROUTE104"):
        g = grid(mid)
        ln = g and line(g, 2)
        if ln:
            d = ln[2]
            add(map=mid, x=ln[0], y=ln[1], dir=d, dirs=",".join([d, OPP[d]] * 10), state="walk", cls="steps:walk20",
                expect="steps", wait=3000)
            break
    for i, r in enumerate(rows, 1):
        r["n"] = i
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
        keep = {k: r[k] for k in ("n", "map", "x", "y", "dir", "face", "dirs", "state", "cls", "fresh", "push", "wait", "idle",
                                  "act", "ax", "ay", "item", "dest", "hold", "find", "strength", "badges", "norepel") if k in r}
        parts = [f"{k} = {val(v)}" for k, v in keep.items()]
        if r.get("flagn"):
            parts.append("flagn = { " + ", ".join(f"{k} = {val(v)}" for k, v in r["flagn"].items()) + " }")
        out.append("  { " + ", ".join(parts) + " },")
    out.append("}")
    return "\n".join(out) + "\n"


def cmd_features(timeout: float, tag: str) -> int:
    rows = make_features()
    FEATURES.write_text(lua_feature_rows(rows), encoding="utf-8")
    print(f"FEATURE ROWS {len(rows)}:", dict(Counter(r["cls"] for r in rows)))
    out = REPORTS / f"g3_field_features{tag}.json"
    received, done, answered, result = fc.run_rows(DRIVER, GAME, "sweep", SWEEP_PORT, timeout,
                                                   {"KB_ROWS": str(FEATURES.resolve()), "KB_SWEEP_FILE": out.name},
                                                   party=fc.FIELD_PARTY)
    fc.write(REPORTS / f"g3_field_features_stream{tag}.json", {"received": received, "rows": done})
    sweep = json.loads(out.read_text(encoding="utf-8"))
    report_features(rows, sweep, received, done)
    return 0 if result.returncode == 0 else 1


def report_features(rows, sweep, received, done) -> None:
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
        delta = {k: (ca.get(k) - cb.get(k)) for k in ca if isinstance(ca.get(k), (int, float)) and isinstance(cb.get(k), (int, float))
                 and ca.get(k) != cb.get(k)}
        answers = [a for a in (d.get("answers") or []) if a]
        frames = [((a.get("data") or {}).get("kind"), (a.get("data") or {}).get("frames")) for a in answers]
        e = rec.get("end") or {}
        print(f"  #{n} {row.get('cls')} {rec.get('map')} [{rec.get('state')}]: act {d.get('act')}; answers {len(answers)} {frames}; "
              f"engine steps {[(x.get('f'), x.get('run'), x.get('bt')) for x in steps][:6]}; warps {[w['kind'] for w in sf['warps'] if w['phase'] == 'arrive']}; "
              f"pos kinds {dict(Counter(sf['pos_kinds']))}; end {e.get('map')}:{e.get('x')},{e.get('y')} surf={e.get('surfing')} "
              f"under={e.get('underwater')}; client {sf['last']}; counters {delta}; err {rec.get('err')}")


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
    p = sub.add_parser("features")
    p.add_argument("--timeout", type=float, default=3600.0)
    p.add_argument("--tag", default="")
    a = ap.parse_args(argv)
    if a.cmd == "features":
        return cmd_features(a.timeout, a.tag)
    if a.cmd == "rows":
        return cmd_rows(a.cls, a.maps)
    if a.cmd == "sweep":
        return cmd_sweep(a.timeout, a.cls, a.maps, a.tag)
    if a.cmd == "report":
        report(json.loads((REPORTS / f"g3_field_sweep_analysis{a.tag}.json").read_text(encoding="utf-8")))
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
