"""Crystal's field mechanics audit on the real engine (docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md §4).

    python tools/e2e/g2_field_check.py survey [--maps A,B]   # FA1 + FA2's dump: classes, warps, verdicts per cell
    python tools/e2e/g2_field_check.py scripts               # FA1's script part (coord events, warps, moves, ...)
    python tools/e2e/g2_field_check.py summary               # the inventory's counts again
    python tools/e2e/g2_field_check.py script-summary        # the script walk's counts again
    python tools/e2e/g2_field_check.py rows [--class C] [--maps A,B]   # FA3/FA4's rows, counted (no run)
    python tools/e2e/g2_field_check.py sweep [--class C] [--maps A,B]  # FA3/FA4: the live rows, a stand-in client
    python tools/e2e/g2_field_check.py report                # the sweep's analysis again
    python tools/e2e/g2_field_check.py features [--tag T]    # FA5: features and step counters (§3.3, §3.4)

The driver is drivers/kb_g2field.lua. Its files in local/reports/ (g2_field_inventory.json, g2_field_parity.json,
g2_field_scripts.json, g2_field_rows.*, g2_field_sweep*.json, g2_field_stream*.json, g2_field_features*) are
ROM-derived and stay in local/;
this script prints only map symbols, class names, COLL byte numbers, ids, kinds and counts. Verdicts: walk and surf only (the bike runs the walking arm in Gen 2).
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


# ---- FA3 + FA4: the live sweep's rows, from the inventory, the parity dump and the script walk -----------------

DIRS = ("up", "down", "left", "right")
DELTA = {"up": (0, -1), "down": (0, 1), "left": (-1, 0), "right": (1, 0)}
OPP = {"up": "down", "down": "up", "left": "right", "right": "left"}
ROWS = REPORTS / "g2_field_rows.lua"
ROWS_JSON = REPORTS / "g2_field_rows.json"
SWEEP_PORT = 27232
# surf entries (the engine pushes the player on, one step a frame); the whirlpool pushes back
WATER_FORCED = ("current", "waterfall")
WATER_ACTING = ("current", "waterfall", "whirlpool")
# 10 per map (D9), from the survey's `samples` (the common classes are counted only, not listed)
SAMPLED = ("grass", "super_tall_grass", "counter")
PER_MAP_SAMPLE = 10
# the Cycling Road: downhill (ENGINE_DOWNHILL) and always on the bike, set by the map's own callback
CYCLING_ROAD = "ROUTE_17"
ROLL_FRAMES = 90
# the script rows that move the player to a warp destination (warpmod / warpsound change no position)
SCRIPT_WARP_OPS = ("warp", "warpfacing", "warpcheck", "newloadmap")


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


def _xy(cells) -> list[tuple]:
    """A class's listed cells as (x, y, fact or None)."""
    out = []
    for c in cells or []:
        if isinstance(c, (list, tuple)) and len(c) >= 2:
            out.append((c[0], c[1], c[2] if len(c) > 2 else None))
    return out


def _spread(items: list, n: int) -> list:
    """Up to n*3 items spread evenly over the list (the samples come row by row)."""
    k = n * 3
    if len(items) <= k:
        return list(items)
    step = len(items) / k
    return [items[int(i * step)] for i in range(k)]


def _first_stand(g, cell=".") -> tuple | None:
    for y in range(g.h):
        for x in range(g.w):
            if g.st(x, y) == cell:
                return x, y
    return None


def make_rows(only_class: str = "", only_maps: str = "") -> list[dict]:
    inv = json.loads(INVENTORY.read_text(encoding="utf-8"))["maps"]
    par = json.loads(PARITY.read_text(encoding="utf-8"))["maps"]
    want_maps = set(filter(None, only_maps.split(",")))
    rows: list[dict] = []
    seen = set()
    no_samples = []

    def add(**r):
        key = (r["map"], r["x"], r["y"], r.get("dir"), r.get("state"), r["cls"], r.get("root"), r.get("idx"),
               r.get("scene"), r.get("tx"), r.get("ty"))
        if key in seen:
            return
        seen.add(key)
        if only_class and r["cls"] != only_class and not r["cls"].startswith(only_class + ":"):
            return
        rows.append(r)

    def approach(g, tx, ty, surf_ok=True, walk_ok=True, prefer=None, avoid=frozenset()):
        """(sx, sy, dir, state) for a step onto (tx, ty) the game allows, or None; never from a cell in avoid.
        Gen 2's J is a hop over the neighbour (from a ledge cell), never a step onto it: only '.' counts."""
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

    for mid in sorted(par):
        prec = par[mid]
        if not prec.get("walk") or (want_maps and mid not in want_maps):
            continue
        g = Grid(prec)
        m = inv.get(mid) or {}
        cells = m.get("cells") or {}
        warp_cells = {(wp.get("x"), wp.get("y")) for wp in m.get("warps") or []}
        water_acting = {(x, y) for c in WATER_ACTING for x, y, _ in _xy(cells.get(c))}
        ice = {(x, y) for x, y, _ in _xy(cells.get("ice"))}
        # ledges: Gen 2's hop starts ON the ledge cell and jumps over the next one (World:tryLedgeJump, World.lua:
        # 10677-10706); the refusal from the landing side (or the cell jumped over)
        for x, y, _ in _xy(cells.get("ledge")):
            if g.st(x, y) != ".":
                continue
            for d in DIRS:
                if g.v(x, y, d) != "J":
                    continue
                dx, dy = DELTA[d]
                lx, ly, mx, my = x + 2 * dx, y + 2 * dy, x + dx, y + dy
                add(map=mid, x=x, y=y, dir=d, state="walk", cls="ledge", tx=lx, ty=ly, expect="hop")
                if g.inside(lx, ly) and g.st(lx, ly) == "." and g.v(lx, ly, OPP[d]) == "x":
                    add(map=mid, x=lx, y=ly, dir=OPP[d], state="walk", cls="ledge_back", tx=mx, ty=my, expect="blocked")
                elif g.inside(mx, my) and g.st(mx, my) == "." and g.v(mx, my, OPP[d]) == "x":
                    add(map=mid, x=mx, y=my, dir=OPP[d], state="walk", cls="ledge_back", tx=x, ty=y, expect="blocked")
        # side walls (land) and buoys (water): a refused step out of the cell, and one into it
        for c in ("side_wall", "buoy"):
            for x, y, _ in _xy(cells.get(c)):
                s = g.st(x, y)
                if s not in ".~":
                    continue
                surf = s == "~"
                state = "surf" if surf else "walk"
                for d in DIRS:
                    dx, dy = DELTA[d]
                    nx, ny = x + dx, y + dy
                    if g.inside(nx, ny) and g.st(nx, ny) == s and g.v(x, y, d, surf) == "x":
                        add(map=mid, x=x, y=y, dir=d, state=state, cls=c, tx=nx, ty=ny, expect="blocked")
                        break
                for d in DIRS:
                    dx, dy = DELTA[d]
                    sx, sy = x - dx, y - dy
                    if g.inside(sx, sy) and g.st(sx, sy) == s and g.v(sx, sy, d, surf) == "x":
                        add(map=mid, x=sx, y=sy, dir=d, state=state, cls=c + "_entry", tx=x, ty=y, expect="blocked")
                        break
        # ice: every entry from an open neighbour that isn't ice
        for x, y, _ in _xy(cells.get("ice")):
            for d in DIRS:
                dx, dy = DELTA[d]
                sx, sy = x - dx, y - dy
                if g.inside(sx, sy) and (sx, sy) not in ice and g.st(sx, sy) == "." and g.v(sx, sy, d) == ".":
                    add(map=mid, x=sx, y=sy, dir=d, state="walk", cls="ice", tx=x, ty=y, expect="forced")
        # currents and waterfalls: every surf entry from water that doesn't act on its own
        for c in WATER_FORCED:
            for x, y, _ in _xy(cells.get(c)):
                for d in DIRS:
                    dx, dy = DELTA[d]
                    sx, sy = x - dx, y - dy
                    if g.inside(sx, sy) and (sx, sy) not in water_acting and g.st(sx, sy) == "~" \
                            and g.v(sx, sy, d, True) == ".":
                        add(map=mid, x=sx, y=sy, dir=d, state="surf", cls=c, tx=x, ty=y, expect="forced")
        # whirlpools: a surf step onto one (the push back, World:runForcedMovement), else the refusal
        for x, y, _ in _xy(cells.get("whirlpool")):
            refused = None
            for d in DIRS:
                dx, dy = DELTA[d]
                sx, sy = x - dx, y - dy
                if not g.inside(sx, sy) or (sx, sy) in water_acting or g.st(sx, sy) != "~":
                    continue
                if g.v(sx, sy, d, True) == ".":
                    add(map=mid, x=sx, y=sy, dir=d, state="surf", cls="whirlpool", tx=x, ty=y, expect="pushback",
                        fresh=True)
                elif refused is None:
                    refused = (sx, sy, d)
            if refused:
                sx, sy, d = refused
                add(map=mid, x=sx, y=sy, dir=d, state="surf", cls="whirlpool", tx=x, ty=y, expect="blocked")
        # door pushes on cells no warp leaves from (the warp cells' push shows on every warp row's arrival)
        for x, y, push in _xy(cells.get("door_forced")):
            if (x, y) in warp_cells or push not in DELTA:
                continue
            dx, dy = DELTA[push]
            sx, sy = x + dx, y + dy
            if g.inside(sx, sy) and g.st(sx, sy) == "." and g.v(sx, sy, OPP[push]) == ".":
                add(map=mid, x=sx, y=sy, dir=OPP[push], state="walk", cls="door_forced", tx=x, ty=y, expect="forced")
            else:
                a = approach(g, x, y, surf_ok=False)
                if a:
                    add(map=mid, x=a[0], y=a[1], dir=a[2], state=a[3], cls="door_forced", tx=x, ty=y, expect="forced")
        # 10 per map
        samples = m.get("samples")
        if samples is None:
            no_samples.append(mid)
        for c in SAMPLED:
            n = 0
            for x, y, _ in _spread(_xy((samples or {}).get(c)), PER_MAP_SAMPLE):
                if n >= PER_MAP_SAMPLE:
                    break
                if c == "counter":
                    # a refused step into the counter
                    for d in DIRS:
                        dx, dy = DELTA[d]
                        sx, sy = x - dx, y - dy
                        if g.inside(sx, sy) and g.st(sx, sy) == "." and g.v(sx, sy, d) == "x":
                            add(map=mid, x=sx, y=sy, dir=d, state="walk", cls=c, tx=x, ty=y, expect="blocked")
                            n += 1
                            break
                else:
                    a = approach(g, x, y, avoid=frozenset(warp_cells))
                    if a:
                        add(map=mid, x=a[0], y=a[1], dir=a[2], state=a[3], cls=c, tx=x, ty=y, expect="step")
                        n += 1
        # warps: every warp cell, by its meaning
        carpet_dir = {(x, y): d for x, y, d in _xy(cells.get("carpet")) if d in DELTA}
        for wp in m.get("warps") or []:
            x, y, meaning = wp.get("x"), wp.get("y"), wp.get("meaning") or "none"
            if x is None or not g.inside(x, y):
                continue
            if meaning == "carpet":
                # stand on the mat and press in its direction (World:checkCarpetWhileStanding, World.lua:10842)
                d = carpet_dir.get((x, y))
                if d and g.st(x, y) == ".":
                    add(map=mid, x=x, y=y, dir=d, state="walk", cls="warp:carpet", tx=x, ty=y, expect="warp",
                        push=True, fresh=True, warp=wp.get("n"))
                continue
            a = approach(g, x, y, prefer="up", avoid=frozenset(warp_cells))
            if a:
                expect = "fall" if meaning == "pit" else ("step" if meaning == "none" else "warp")
                add(map=mid, x=a[0], y=a[1], dir=a[2], state=a[3], cls="warp:" + meaning, tx=x, ty=y, expect=expect,
                    fresh=True, warp=wp.get("n"), inMap=bool(wp.get("inMap")))
        # the Cycling Road: steering on the bike (any held direction wins, Bike.forcedDirection, Bike.lua:139-143)
        # and the roll with nothing held; the rows end at their wait
        if mid == CYCLING_ROAD:
            picks = {}
            for y in range(2, max(3, g.h // 3)):
                for x in range(g.w):
                    if g.st(x, y) != "." or g.v(x, y, "down") != ".":
                        continue
                    for d in ("left", "right", "up"):
                        if d not in picks and g.v(x, y, d) == ".":
                            picks[d] = (x, y)
            for d, (x, y) in sorted(picks.items()):
                dx, dy = DELTA[d]
                add(map=mid, x=x, y=y, dir=d, state="bike", cls="cycling_road", tx=x + dx, ty=y + dy, expect="step",
                    wait=ROLL_FRAMES, roll=True)
            if picks:
                x, y = sorted(picks.values())[0]
                add(map=mid, x=x, y=y, dir=None, state="bike", cls="cycling_road_roll", tx=x, ty=y, expect="forced",
                    wait=ROLL_FRAMES, roll=True)
    if no_samples:
        print(f"NOTE: {len(no_samples)} maps without `samples` in the inventory (an older survey): no grass/counter "
              f"rows there; run the survey again for them")
    # FA4: scripts that move, warp or change the world during a visit
    if SCRIPTS.is_file() and (not only_class or only_class in ("coord", "script", "callback", "scene")):
        sc = json.loads(SCRIPTS.read_text(encoding="utf-8"))["maps"]
        for mid in sorted(sc):
            prec = par.get(mid) or {}
            if not prec.get("walk") or (want_maps and mid not in want_maps):
                continue
            g = Grid(prec)
            sm = sc[mid]
            found = sm.get("found") or []
            m = inv.get(mid) or {}
            warps = m.get("warps") or []
            # coord events: stepped onto, the map's scene set to the one that enables them (World:tryCoordScript,
            # World.lua:7933-7948)
            for ev in sm.get("coord") or []:
                if not ev.get("script") or ev.get("x") is None:
                    continue
                x, y = ev["x"], ev["y"]
                if not g.inside(x, y):
                    continue
                a = approach(g, x, y)
                if not a:
                    continue
                ops = sorted({f["op"] for f in found
                              if f.get("root") == "coord" and (f.get("rootInfo") or {}).get("n") == ev["n"]})
                add(map=mid, x=a[0], y=a[1], dir=a[2], state=a[3], cls="coord", tx=x, ty=y, expect="script", fresh=True,
                    wait=1800, ops=ops, scene=int(ev.get("sceneId") or 0))
            # object and sign scripts with a warp: started as the interaction starts them (driven)
            keys = {}
            for f in found:
                info = f.get("rootInfo") or {}
                idx = info.get("object") if f.get("root") == "object" else info.get("n")
                if f.get("root") in ("object", "bg") and idx is not None and f["op"] in SCRIPT_WARP_OPS:
                    keys.setdefault((f["root"], idx), set()).add(f["op"])
            start = _first_stand(g)
            for (root, idx), ops in sorted(keys.items()):
                if start is None:
                    break
                add(map=mid, x=start[0], y=start[1], state="walk", cls="script", tx=start[0], ty=start[1],
                    expect="script", fresh=True, trigger="script", root=root, idx=int(idx), wait=2400, ops=sorted(ops))
            # map callbacks and scene scripts with a warp: the warp in is the trigger (onto the first warp's cell, as
            # the survey's visit; the scene set for a scene script)
            entry = {}
            for f in found:
                root = f.get("root") or ""
                if f["op"] not in SCRIPT_WARP_OPS:
                    continue
                if root.startswith("MAPCALLBACK"):
                    entry.setdefault(("callback", root, None), set()).add(f["op"])
                elif root == "scene":
                    scene = (f.get("rootInfo") or {}).get("scene")
                    if scene is not None:
                        entry.setdefault(("scene", root, int(scene)), set()).add(f["op"])
            if entry:
                arr = (warps[0].get("x"), warps[0].get("y")) if warps and warps[0].get("x") is not None else start
                if arr is None:
                    continue
                for (cls, root, scene), ops in sorted(entry.items(), key=lambda kv: (kv[0][0], kv[0][1], kv[0][2] or 0)):
                    r = dict(map=mid, x=arr[0], y=arr[1], state="walk", cls=cls, tx=arr[0], ty=arr[1], expect="script",
                             trigger="warp", wait=2400, ops=sorted(ops), root=root)
                    if scene is not None:
                        r["scene"] = scene
                    add(**r)
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
        if r.get("expect") in ("fall", "warp", "script", "pushback"):
            r.setdefault("idle", 90)
        keep = {k: r[k] for k in ("n", "map", "x", "y", "dir", "state", "cls", "fresh", "push", "wait", "trigger", "root",
                                  "idx", "scene", "idle", "roll") if r.get(k) is not None}
        if r.get("trigger") == "warp":
            keep.pop("root", None)  # the callback's name is for the report only
        parts = [f"{k} = {val(v)}" for k, v in keep.items()]
        out.append("  { " + ", ".join(parts) + " },")
    out.append("}")
    return "\n".join(out) + "\n"


def cmd_rows(only_class: str, maps: str) -> int:
    rows = make_rows(only_class, maps)
    by = Counter(r["cls"] if not r["cls"].startswith("warp:") else "warp" for r in rows)
    print(f"ROWS {len(rows)} on {len({r['map'] for r in rows})} maps:", dict(sorted(by.items())))
    return 0


def _internal(v):
    """A reported map id as the engine knows it (the bridge's G2_ prefix off, gen2/base.lua b.ext / b.int)."""
    return v[3:] if isinstance(v, str) and v.startswith("G2_") else v


def normalise(received: list[dict]) -> list[dict]:
    """The client's messages with the map ids the driver records (the analysis compares the two)."""
    out = []
    for m in received:
        d = m.get("data")
        if isinstance(d, dict):
            d = dict(d)
            if "map" in d:
                d["map"] = _internal(d["map"])
            for k in ("to", "from"):
                if isinstance(d.get(k), dict) and "map" in d[k]:
                    d[k] = dict(d[k], map=_internal(d[k]["map"]))
            m = dict(m, data=d)
        out.append(m)
    return out


def cmd_sweep(timeout: float, only_class: str, maps: str, tag: str) -> int:
    rows = make_rows(only_class, maps)
    ROWS.write_text(lua_rows(rows), encoding="utf-8")
    fc.write(ROWS_JSON, rows)
    print(f"ROWS {len(rows)} on {len({r['map'] for r in rows})} maps")
    out = REPORTS / f"g2_field_sweep{tag}.json"
    received, done, answered, result = fc.run_rows(DRIVER, GAME, "sweep", SWEEP_PORT, timeout,
                                                   {"KB_ROWS": str(ROWS.resolve()), "KB_SWEEP_FILE": out.name})
    fc.write(REPORTS / f"g2_field_stream{tag}.json", {"received": received, "rows": done})
    if not out.is_file():
        print("NO SWEEP FILE (the driver did not finish its rows)")
        return 1
    analysis = analyse(rows, json.loads(out.read_text(encoding="utf-8")), received, done)
    fc.write(REPORTS / f"g2_field_sweep_analysis{tag}.json", analysis)
    report(analysis)
    return 0 if result.returncode == 0 else 1


# the warp kind the protocol's mechanism names (D2) for each Crystal warp meaning (the plan's FA3 table; the bridge
# today: gen2/move.lua WARP_KINDS, ladders as stairs)
WANT_KIND = {"door": "door", "ladder": "ladder", "staircase": "stairs", "cave": "door", "warp_panel": "pad",
             "pit": "hole", "carpet": "edge", "warp_unused": "door"}


def analyse(rows: list[dict], sweep: dict, received: list[dict], done: dict) -> dict:
    # Gen 2's live changes are blocks (story.blocks' changes {bx, by, block}): the D check reads those
    return fc.analyse_rows(rows, sweep, normalise(received), done, WANT_KIND, blocks=True)


def report(analysis: dict) -> None:
    fc.report_rows(analysis)


# ---- FA5: features and step counters (§3.3, §3.4) -------------------------------------------------------------------

FEATURES = REPORTS / "g2_field_features_rows.lua"
# A party that knows every Gen 2 field move (as Cobblemon syncs one; made-up uids). Gen 2 move numbers: Cut 15,
# Fly 19, Headbutt 29, Surf 57, Strength 70, Dig 91, Teleport 100, Waterfall 127, Flash 148, Rock Smash 249,
# Whirlpool 250; national dex numbers of Gen 2 species that learn them: Feraligatr 160, Farfetch'd 83, Alakazam 65,
# Sandslash 28. The bridge's act.field takes the first member that knows the move (FieldMoves.partyMoveUser).
CRYSTAL_PARTY = [
    {"uid": "fa5c0001-0000-4000-8000-000000000001", "dex": 160, "level": 50, "moves": [57, 127, 250, 70], "hp": 160,
     "maxhp": 160},
    {"uid": "fa5c0002-0000-4000-8000-000000000002", "dex": 83, "level": 50, "moves": [15, 19, 29], "hp": 130,
     "maxhp": 130},
    {"uid": "fa5c0003-0000-4000-8000-000000000003", "dex": 65, "level": 50, "moves": [148, 100], "hp": 120, "maxhp": 120},
    {"uid": "fa5c0004-0000-4000-8000-000000000004", "dex": 28, "level": 50, "moves": [249, 91], "hp": 150, "maxhp": 150},
]
# the objects are found by the driver at run time (movement 0x19 boulders, 0x18 rocks, brief §6); these maps have them
FEATURE_MAPS = {
    "boulder": ["ICE_PATH_B1F", "BLACKTHORN_GYM_2F", "MOUNT_MORTAR_B1F"],
    "rock": ["CIANWOOD_CITY", "ROUTE_40", "DARK_CAVE_VIOLET_ENTRANCE", "DARK_CAVE_BLACKTHORN_ENTRANCE"],
}
ROUTES = ("ROUTE_29", "ROUTE_30", "ROUTE_31", "ROUTE_32", "ROUTE_34", "ROUTE_35", "ROUTE_36")
CAVES = ("DARK_CAVE_VIOLET_ENTRANCE", "UNION_CAVE_1F", "DARK_CAVE_BLACKTHORN_ENTRANCE", "MOUNT_MORTAR_1F_OUTSIDE")
# the actions on the cell in front: the client names it (act.field x/y, the player turned to it first)
FEATURE_TARGETED = ("cut", "surf", "headbutt", "rock_smash", "whirlpool", "waterfall")


def make_features() -> list[dict]:
    inv = json.loads(INVENTORY.read_text(encoding="utf-8"))["maps"]
    par = json.loads(PARITY.read_text(encoding="utf-8"))["maps"]
    rows: list[dict] = []

    def add(**r):
        r.setdefault("badges", True)
        r.setdefault("wait", 2400)
        r.setdefault("idle", 60)
        if not r.get("chain"):
            r.setdefault("fresh", True)
        rows.append(r)

    def grid(mid):
        rec = par.get(mid) or {}
        return Grid(rec) if rec.get("walk") else None

    def warps_of(mid):
        return {(wp.get("x"), wp.get("y")) for wp in (inv.get(mid) or {}).get("warps") or []}

    def env(mid):
        return (inv.get(mid) or {}).get("environment")

    def line(g, length, avoid=frozenset()):
        """A walkable cell with `length` open steps ahead in one direction (no ledge, no warp cell), or None."""
        for y in range(g.h):
            for x in range(g.w):
                for d in DIRS:
                    cx, cy, ok = x, y, True
                    for _ in range(length):
                        nx, ny = cx + DELTA[d][0], cy + DELTA[d][1]
                        if g.st(cx, cy) != "." or g.v(cx, cy, d) != "." or (nx, ny) in avoid or (cx, cy) in avoid:
                            ok = False
                            break
                        cx, cy = nx, ny
                    if ok:
                        return x, y, d
        return None

    def stand(g, avoid=frozenset(), cell="."):
        """The open cell nearest the map's middle (not a warp cell), or None."""
        mx, my = g.w // 2, g.h // 2
        best = None
        for y in range(g.h):
            for x in range(g.w):
                if g.st(x, y) == cell and (x, y) not in avoid:
                    dist = abs(x - mx) + abs(y - my)
                    if best is None or dist < best[0]:
                        best = (dist, x, y)
        return (best[1], best[2]) if best else None

    def beside(g, cells, stand_cell=".", refused="x", avoid=frozenset()):
        """(sx, sy, dir, tx, ty): a cell beside one of `cells` facing it, the step into it refused (a tree, a
        whirlpool from the water) - the first in the list."""
        for tx, ty, _ in cells:
            for d in DIRS:
                sx, sy = tx - DELTA[d][0], ty - DELTA[d][1]
                if not g.inside(sx, sy) or (sx, sy) in avoid or g.st(sx, sy) != stand_cell:
                    continue
                if refused is None or g.v(sx, sy, d, stand_cell == "~") == refused:
                    return sx, sy, d, tx, ty
        return None

    def pick_maps(prefer, test):
        out = [m for m in prefer if m in par and test(m)]
        out += [m for m in sorted(par) if m not in out and test(m)]
        return out

    # Surf on (act surf facing water: the engine's SurfStartStep moves the player on) and a step on the water
    # after; off (act surf facing land: Gen 2's menu says "already surfing", the dismount is a step onto land);
    # and Surf without the FOG badge (the refusal)
    k = 0
    for mid in pick_maps(ROUTES, lambda m: grid(m) is not None):
        if k >= 2:
            break
        g = grid(mid)
        avoid = warps_of(mid)
        found = None
        for y in range(g.h):
            for x in range(g.w):
                if found or g.st(x, y) != "." or (x, y) in avoid:
                    continue
                for d in DIRS:
                    wx, wy = x + DELTA[d][0], y + DELTA[d][1]
                    if g.inside(wx, wy) and g.st(wx, wy) == "~" and g.v(x, y, d) == "w" and g.v(x, y, d, True) == ".":
                        found = (x, y, d, wx, wy)
                        break
        if not found:
            continue
        x, y, d, wx, wy = found
        add(map=mid, x=x, y=y, face=d, state="walk", cls="feature:surf_on", act="surf", ax=wx, ay=wy)
        fx, fy = wx + DELTA[d][0], wy + DELTA[d][1]
        if g.inside(fx, fy) and g.st(fx, fy) == "~" and g.v(wx, wy, d, True) == ".":
            add(map=mid, x=wx, y=wy, chain=True, state="keep", cls="feature:surf_step", dirs=d)
        add(map=mid, x=wx, y=wy, face=OPP[d], state="surf", cls="feature:surf_off", act="surf", ax=x, ay=y)
        add(map=mid, x=wx, y=wy, face=OPP[d], chain=True, state="keep", cls="feature:surf_off_step", dirs=OPP[d])
        if k == 0:
            add(map=mid, x=x, y=y, face=d, state="walk", cls="feature:surf_no_badge", act="surf", ax=wx, ay=wy,
                badges=False)
        k += 1
    # The bicycle (the PACK's BICYCLE given as `giveitem` gives it): on, five steps (the engine's frames per step
    # against five on foot), off; without one in the PACK; indoors
    for mid in pick_maps(ROUTES, lambda m: env(m) == "ROUTE" and grid(m) is not None):
        g = grid(mid)
        ln = line(g, 6, warps_of(mid))
        if not ln:
            continue
        x, y, d = ln
        five = ",".join([d] * 5)
        add(map=mid, x=x, y=y, dir=d, dirs=five, state="walk", cls="feature:walk_speed")
        add(map=mid, x=x, y=y, face=d, state="walk", cls="feature:bicycle_on", act="bicycle", give="BICYCLE")
        add(map=mid, x=x, y=y, dir=d, dirs=five, chain=True, state="keep", cls="feature:bike_speed")
        add(map=mid, x=x, y=y, face=d, chain=True, state="keep", cls="feature:bicycle_off", act="bicycle")
        add(map=mid, x=x, y=y, face=d, state="walk", cls="feature:bicycle_no_item", act="bicycle")
        break
    for mid in pick_maps(("ELMS_LAB", "PLAYERS_HOUSE_1F", "CHERRYGROVE_POKECENTER_1F"),
                         lambda m: env(m) == "INDOOR" and grid(m) is not None):
        pick = stand(grid(mid), warps_of(mid))
        if pick:
            add(map=mid, x=pick[0], y=pick[1], face="down", state="walk", cls="feature:bicycle_indoors", act="bicycle",
                give="BICYCLE")
            break
    # The Cycling Road (downhill, always on the bike, set by the map's callback): steering while it rolls, B held
    # (no brake in Gen 2), and the bicycle refused there (ALWAYS_ON_BIKE: "can't get off")
    g = grid(CYCLING_ROAD)
    if g:
        avoid = warps_of(CYCLING_ROAD)
        pick = None
        for y in range(2, max(3, g.h // 3)):
            for x in range(g.w):
                if pick is None and g.st(x, y) == "." and (x, y) not in avoid and all(
                        g.v(x, y, d) == "." for d in ("down", "left", "right", "up")):
                    pick = (x, y)
        if pick:
            x, y = pick
            add(map=CYCLING_ROAD, x=x, y=y, dir="left", dirs="left,right,up", state="bike", cls="feature:cycling_road_steer",
                roll=True, wait=ROLL_FRAMES * 3)
            add(map=CYCLING_ROAD, x=x, y=y, dir="down", dirs="down,down", hold="b", state="bike",
                cls="feature:cycling_road_brake", roll=True, wait=ROLL_FRAMES * 3)
            add(map=CYCLING_ROAD, x=x, y=y, face="down", state="bike", cls="feature:cycling_road_dismount",
                act="bicycle", give="BICYCLE", roll=True, wait=ROLL_FRAMES * 2)
    # Waterfall: surfing at the foot, facing up (CheckMapCanWaterfall: facing up, the cell above a waterfall)
    n = 0
    for mid, m in sorted(inv.items()):
        cells = {(c[0], c[1]) for c in (m.get("cells") or {}).get("waterfall") or []}
        g = grid(mid)
        if not cells or not g or n >= 2:
            continue
        acting = {(c[0], c[1]) for cl in WATER_ACTING for c in (m.get("cells") or {}).get(cl) or []}
        for x, y in sorted(cells, key=lambda c: (-c[1], c[0])):
            if (x, y + 1) not in acting and g.inside(x, y + 1) and g.st(x, y + 1) == "~":
                add(map=mid, x=x, y=y + 1, face="up", state="surf", cls="feature:waterfall", act="waterfall", ax=x, ay=y,
                    wait=3600)
                n += 1
                break
    # Whirlpool: surfing beside one, facing it (the block swap, FieldMoves.lua:329)
    n = 0
    for mid, m in sorted(inv.items()):
        cells = _xy((m.get("cells") or {}).get("whirlpool"))
        g = grid(mid)
        if not cells or not g or n >= 2:
            continue
        acting = {(c[0], c[1]) for cl in WATER_ACTING for c in (m.get("cells") or {}).get(cl) or []}
        b = beside(g, cells, "~", None, frozenset(acting))
        if b:
            sx, sy, d, tx, ty = b
            add(map=mid, x=sx, y=sy, face=d, state="surf", cls="feature:whirlpool", act="whirlpool", ax=tx, ay=ty)
            n += 1
    # Strength: on (act strength), then three steps at the boulder (push, into the cell it left, push); and the
    # same push without Strength (the boulder stays)
    first = True
    for mid in FEATURE_MAPS["boulder"]:
        if mid not in par:
            continue
        add(map=mid, x=0, y=0, find="boulder", act="strength", state="walk", cls="feature:strength")
        add(map=mid, x=0, y=0, find="boulder", pushes=True, chain=True, state="keep", cls="feature:strength_push")
        if first:
            add(map=mid, x=0, y=0, find="boulder", pushes=True, state="walk", cls="feature:push_no_strength")
            first = False
    # Rock Smash on the rocks found at run time; Headbutt and Cut facing the trees the survey listed
    for mid in FEATURE_MAPS["rock"]:
        if mid in par:
            add(map=mid, x=0, y=0, find="rock", act="rock_smash", state="walk", cls="feature:rock_smash", wait=3000)
    for cls, act, limit in (("headbutt_tree", "headbutt", 2), ("cut_tree", "cut", 3)):
        n = 0
        for mid, m in sorted(inv.items()):
            cells = _xy((m.get("cells") or {}).get(cls))
            g = grid(mid)
            if not cells or not g or n >= limit:
                continue
            b = beside(g, cells, ".", "x", frozenset(warps_of(mid)))
            if b:
                sx, sy, d, tx, ty = b
                add(map=mid, x=sx, y=sy, face=d, state="walk", cls="feature:" + act, act=act, ax=tx, ay=ty, wait=3000)
                n += 1
    # Flash in a dark cave (and refused on a lit route); Dig and the Escape Rope out of a cave (the dig triple banked
    # as a walk in banks it; and Dig with nothing banked, as a warp-in leaves it); Teleport and Fly outside (Gen 2's
    # Fly opens its own map; a named destination is refused by the bridge)
    dark = pick_maps(CAVES, lambda m: bool((inv.get(m) or {}).get("dark")) and grid(m) is not None)
    caves = pick_maps(CAVES, lambda m: env(m) in ("CAVE", "DUNGEON") and grid(m) is not None)
    outside = pick_maps(ROUTES, lambda m: env(m) in ("ROUTE", "TOWN") and grid(m) is not None)
    if dark:
        mid = dark[0]
        pick = stand(grid(mid), warps_of(mid))
        if pick:
            add(map=mid, x=pick[0], y=pick[1], face="down", state="walk", cls="feature:flash", act="flash")
    if outside:
        mid = outside[0]
        pick = stand(grid(mid), warps_of(mid))
        if pick:
            add(map=mid, x=pick[0], y=pick[1], face="down", state="walk", cls="feature:flash_lit", act="flash")
            add(map=mid, x=pick[0], y=pick[1], face="down", state="walk", cls="feature:teleport", act="teleport",
                wait=3600)
            add(map=mid, x=pick[0], y=pick[1], face="down", state="walk", cls="feature:fly", act="fly", wait=3600)
            add(map=mid, x=pick[0], y=pick[1], face="down", state="walk", cls="feature:fly_dest", act="fly",
                dest="CHERRYGROVE_CITY")
    if caves:
        mid = caves[0]
        pick = stand(grid(mid), warps_of(mid))
        if pick:
            add(map=mid, x=pick[0], y=pick[1], face="down", state="walk", cls="feature:dig", act="dig", backup=True,
                wait=3600)
            add(map=mid, x=pick[0], y=pick[1], face="down", state="walk", cls="feature:escape_rope", act="dig",
                item="ESCAPE_ROPE", give="ESCAPE_ROPE", backup=True, wait=3600)
            add(map=mid, x=pick[0], y=pick[1], face="down", state="walk", cls="feature:dig_no_triple", act="dig")
    # Step counters (§3.4): twenty steps back and forth on foot and on the bike, the counters read before and after
    # (a Repel on: its steps count down with the rest)
    for mid in outside:
        g = grid(mid)
        ln = g and line(g, 2, warps_of(mid))
        if ln:
            x, y, d = ln
            twenty = ",".join([d, OPP[d]] * 10)
            add(map=mid, x=x, y=y, dir=d, dirs=twenty, state="walk", cls="steps:walk20", repel=200, wait=4000, idle=90)
            add(map=mid, x=x, y=y, dir=d, dirs=twenty, state="bike", cls="steps:bike20", repel=200, wait=4000, idle=90)
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
        keep = {k: r[k] for k in ("n", "map", "x", "y", "dir", "face", "dirs", "state", "cls", "fresh", "chain", "push",
                                  "wait", "idle", "roll", "act", "ax", "ay", "item", "dest", "hold", "find", "pushes",
                                  "badges", "give", "backup", "repel", "norepel") if r.get(k) is not None}
        if keep.get("act") not in FEATURE_TARGETED:
            keep.pop("ax", None)
            keep.pop("ay", None)
        parts = [f"{k} = {val(v)}" for k, v in keep.items()]
        out.append("  { " + ", ".join(parts) + " },")
    out.append("}")
    return "\n".join(out) + "\n"


def cmd_features(timeout: float, tag: str) -> int:
    rows = make_features()
    FEATURES.write_text(lua_feature_rows(rows), encoding="utf-8")
    fc.write(REPORTS / "g2_field_features_rows.json", rows)
    print(f"FEATURE ROWS {len(rows)}:", dict(Counter(r["cls"] for r in rows)))
    out = REPORTS / f"g2_field_features{tag}.json"
    received, done, answered, result = fc.run_rows(DRIVER, GAME, "sweep", SWEEP_PORT, timeout,
                                                   {"KB_ROWS": str(FEATURES.resolve()), "KB_SWEEP_FILE": out.name},
                                                   party=CRYSTAL_PARTY)
    received = normalise(received)
    fc.write(REPORTS / f"g2_field_features_stream{tag}.json", {"received": received, "rows": done})
    if not out.is_file():
        print("NO FEATURES FILE (the driver did not finish its rows)")
        return 1
    sweep = json.loads(out.read_text(encoding="utf-8"))
    report_features(rows, sweep, received, done)
    return 0 if result.returncode == 0 else 1


def _changed(before: dict, after: dict) -> dict:
    """The counters that moved, as before->after (stepCount wraps at 256 and the poison count resets at 4, so a
    plain difference would mislead)."""
    out = {}
    for k in sorted(set(before) | set(after)):
        b, a = before.get(k), after.get(k)
        if b != a:
            out[k] = f"{b}->{a}"
    return out


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
        answers = [a for a in (d.get("answers") or []) if a]
        frames = [(a.get("type", "").split(".")[-1], (a.get("data") or {}).get("kind") or (a.get("data") or {}).get("reason"),
                   (a.get("data") or {}).get("frames")) for a in answers]
        act = d.get("act")
        act_s = None if act is None else (act.get("kind") if act.get("ok") else "refused:" + str(act.get("reason")))
        ui = [(m.get("data") or {}).get("kind") for m in msgs if m["type"] == "ui.open"]
        e = rec.get("end") or {}
        delta = _changed(rec.get("countersBefore") or {}, rec.get("countersAfter") or {})
        ready = d.get("ready") or {}
        sent = len([x for x in (ready.get("dirs") or ready.get("dir") or "").replace("+", ",").split(",") if x in DELTA])
        pos = sum(1 for a in answers if a.get("type") == "player.pos")
        left = {k: v for k, v in (rec.get("left") or {}).items() if v is not None}
        print(f"  #{n} {row.get('cls')} {rec.get('map')} [{rec.get('state')}] act {act_s}; ui {ui}; "
              f"steps sent {sent}, answered {len(answers)} {frames}; "
              f"engine steps {len(steps)} {[(x.get('f'), x.get('st')) for x in steps][:6]}; "
              f"setups {rec.get('setups') or []}; maps {rec.get('maps') or []}; "
              f"client warps {[(w['kind'], (w.get('to') or {}).get('map')) for w in sf['warps'] if w['phase'] == 'arrive']}; "
              f"modes {sf['modes']}; story.blocks {sf['story_blocks']}; "
              f"end {e.get('map')}:{e.get('x')},{e.get('y')} {e.get('state')}; client {sf['last']}; "
              f"counters {delta}; left {left}; object still {rec.get('objectStill')}; "
              f"given {rec.get('given')}; triple {rec.get('backup')}; err {rec.get('err')}; "
              f"halted {rec.get('halted')} cutoff {rec.get('cutoff')}")
        if (row.get("cls") or "").startswith("steps:"):
            b, a = (rec.get("countersBefore") or {}), (rec.get("countersAfter") or {})
            counted = None
            if isinstance(b.get("stepCount"), int) and isinstance(a.get("stepCount"), int):
                counted = (a["stepCount"] - b["stepCount"]) % 256
            print(f"     STEPS: sent {sent}, player.pos {pos}, engine started {len(steps)}, stepCount +{counted}; "
                  f"{'equal' if counted == pos == len(steps) else 'NOT EQUAL'}")


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
        report(json.loads((REPORTS / f"g2_field_sweep_analysis{a.tag}.json").read_text(encoding="utf-8")))
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
