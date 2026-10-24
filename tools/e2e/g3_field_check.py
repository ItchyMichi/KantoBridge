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
        if only_class and r["cls"] != only_class:
            return
        rows.append(r)

    def approach(g, tx, ty, surf_ok=True, walk_ok=True, prefer=None):
        """(sx, sy, dir, state) for a step onto (tx, ty) the game allows, or None."""
        order = [prefer] if prefer else []
        order += [d for d in DIRS if d != prefer]
        for d in order:
            dx, dy = DELTA[d]
            sx, sy = tx - dx, ty - dy
            if not g.inside(sx, sy):
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
        for c in EVERY_CELL:
            for x, y in cells.get(c) or []:
                a = approach(g, x, y, surf_ok=False)
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
        keep = {k: r[k] for k in ("n", "map", "x", "y", "dir", "state", "cls", "fresh", "push", "wait", "trigger", "script",
                                  "lid") if k in r}
        parts = [f"{k} = {val(v)}" for k, v in keep.items()]
        if r.get("vars"):
            parts.append("vars = { " + ", ".join(f"[{int(k)}] = {int(v)}" for k, v in r["vars"].items()) + " }")
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
    by_n = {r["n"]: r for r in rows}
    out = []
    for rec in sweep.get("rows") or []:
        n = rec.get("n")
        row = by_n.get(n, {})
        msgs = fc.row_messages(received, n)
        sf = fc.stream_facts(msgs)
        d = done.get(n) or {}
        ans = [m for m in msgs if m["type"] in ("player.pos", "player.blocked") and (m.get("data") or {}).get("n") == n]
        e = rec.get("end") or {}
        entry = {"n": n, "map": rec.get("map"), "cls": rec.get("cls"), "state": rec.get("state"), "expect": row.get("expect"),
                 "err": rec.get("err"), "halted": bool(rec.get("halted")), "answers": len(ans),
                 "answer": ans[0]["type"] + ":" + str((ans[0].get("data") or {}).get("kind") or (ans[0].get("data") or {}).get("reason"))
                 if ans else None,
                 "pos_kinds": dict(Counter(k for k in sf["pos_kinds"])), "warps": [w["kind"] for w in sf["warps"] if w["phase"] == "arrive"],
                 "modes": sf["modes"], "engine_end": [e.get("map"), e.get("x"), e.get("y")],
                 "client_end": [(sf["last"] or {}).get("map"), (sf["last"] or {}).get("x"), (sf["last"] or {}).get("y")]
                 if sf["last"] else None, "changed": len(rec.get("changed") or []), "story_cells": sf["story_cells"],
                 "frames": rec.get("frames")}
        problems = []
        if not rec.get("err"):
            if len(ans) != 1:
                problems.append(f"answers={len(ans)}")
            moved = e.get("map") is not None and [e.get("map"), e.get("x"), e.get("y")] != [rec.get("map"), (rec.get("start") or {}).get("x"), (rec.get("start") or {}).get("y")]
            if moved:
                ce = entry["client_end"]
                if ce is None or ce != entry["engine_end"]:
                    problems.append("C:left_behind")
            # D: every cell the engine changed during the row (same map) reached the client as story.cells
            if rec.get("changed") and (e.get("map") == rec.get("map")):
                held = {}
                for m in msgs:
                    if m["type"] == "story.cells" and (m.get("data") or {}).get("map") == rec.get("map"):
                        for c in (m["data"].get("cells") or []):
                            held[(c["x"], c["y"])] = c.get("metatile")
                miss = [c for c in rec["changed"] if held.get((c["x"], c["y"])) != c["l"][0]]
                if miss:
                    problems.append(f"D:not_shown={len(miss)}")
            exp = row.get("expect")
            if exp == "blocked" and not (entry["answer"] or "").startswith("player.blocked"):
                problems.append("expect_blocked")
            if exp == "warp" or exp == "fall":
                if not entry["warps"]:
                    problems.append("no_warp")
                else:
                    beh = (row.get("cls") or "").split(":", 1)[-1]
                    want = WANT_KIND.get(beh) or WANT_KIND.get(row.get("cls"))
                    if want and entry["warps"][0] != want:
                        problems.append(f"N:kind {entry['warps'][0]} (mechanism {want})")
            if exp == "script" and any(o.startswith("warp") for o in row.get("ops") or []) and not entry["warps"]:
                problems.append("N:script_no_warp")
            if exp == "forced" and not moved:
                problems.append("forced_no_move")
            if rec.get("halted"):
                problems.append("halted")
        entry["problems"] = problems
        out.append(entry)
    return {"rows": out}


def report(analysis: dict) -> None:
    rows = analysis["rows"]
    by_cls = defaultdict(Counter)
    for e in rows:
        cls = e["cls"]
        by_cls[cls]["rows"] += 1
        if e["err"]:
            by_cls[cls]["err:" + str(e["err"]).split()[0]] += 1
        for p in e["problems"]:
            by_cls[cls][p.split(" (")[0] if p.startswith("N:") else p.split("=")[0]] += 1
        if not e["problems"] and not e["err"]:
            by_cls[cls]["ok"] += 1
    print(f"\nSWEEP: {len(rows)} rows")
    for cls in sorted(by_cls):
        c = by_cls[cls]
        print(f"  {cls}: " + ", ".join(f"{k} {v}" for k, v in sorted(c.items())))
    kinds = Counter()
    for e in rows:
        if e["warps"]:
            kinds[(e["cls"], e["warps"][0])] += 1
    print("\nWARP KINDS SEEN (class: kind x rows):")
    for (cls, k), n in sorted(kinds.items()):
        print(f"  {cls}: {k} x{n}")
    pk = defaultdict(Counter)
    for e in rows:
        for k, n in e["pos_kinds"].items():
            pk[e["cls"]][k] += n
    print("\nPLAYER.POS KINDS (class: kind count):")
    for cls in sorted(pk):
        print(f"  {cls}: " + ", ".join(f"{k} {v}" for k, v in pk[cls].most_common()))


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
    a = ap.parse_args(argv)
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
