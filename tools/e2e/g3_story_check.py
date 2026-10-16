"""Emerald's story check on the real engine (docs/specs/STORY_EVENTS_PLAN.md §2): what Emerald's story changes in
the world, and whether each change reaches Minecraft.

    python tools/e2e/g3_story_check.py scan        # launch the test instance, scan every map's scripts, summarise
    python tools/e2e/g3_story_check.py summary     # summarise the last scan again
    python tools/e2e/g3_story_check.py cases       # the live cases the last scan gives
    python tools/e2e/g3_story_check.py sweep [--maps A,B]    # the live check, a stand-in client connected
    python tools/e2e/g3_story_check.py objects [--maps A,B]  # hide flags set and cleared, map by map
    python tools/e2e/g3_story_check.py report      # print the last sweep's analysis again

The driver is drivers/kb_g3story.lua. Its files in local/reports/ (g3_story_scan.json, g3_story_sweep.json,
g3_story_objects.json) are ROM-derived (map cells and metatile numbers stay in local/); this script prints only map
symbols, flag/var/special/weather names and ids, and counts, and writes its analyses (the same) beside them. The sweep
and the objects run compare what a stand-in Minecraft client (fake_minecraft, role test) was told per visit with the
engine's own state: the changed cells (story.cells), the NPC list (npc.set/add/remove) and the story toggles.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from run_gen1recomp import REPORTS, launch  # noqa: E402

GAME = "emerald"
SCAN = REPORTS / "g3_story_scan.json"
# pokeemerald's weather ids (refs/gen1recomp src/core/game3/weather.lua:13-31)
WEATHER = {0: "NONE", 1: "SUNNY_CLOUDS", 2: "SUNNY", 3: "RAIN", 4: "SNOW", 5: "RAIN_THUNDERSTORM", 6: "FOG_HORIZONTAL",
           7: "VOLCANIC_ASH", 8: "SANDSTORM", 9: "FOG_DIAGONAL", 10: "UNDERWATER", 11: "SHADE", 12: "DROUGHT",
           13: "DOWNPOUR", 14: "UNDERWATER_BUBBLES", 15: "ABNORMAL", 20: "ROUTE119_CYCLE", 21: "ROUTE123_CYCLE"}
ENTRY = ("onTransition", "onLoad", "onResume", "onReturnToField", "onDiveWarp", "onFrame", "onWarpIntoMap")


def run_scan(timeout: float) -> int:
    REPORTS.mkdir(parents=True, exist_ok=True)
    env = {"KB_SCENE": "scan", "KB_REPORT_DIR": str(REPORTS.resolve()), "KB_DATASET_MEMO": "30"}
    with launch("kb_g3story.lua", game=GAME, extra_env=env) as run:
        result = run.wait(timeout)
    print("RUN", json.dumps(result.summary(), sort_keys=True))
    return 0 if result.returncode == 0 and result.marker("KB_SCAN") else 1


def cond_text(c: dict, names: dict) -> str:
    k = c.get("k")
    if k == "flag":
        name = names["flags"].get(str(c.get("id")), f"0x{c.get('id', 0):X}")
        return f"{name}{'' if c.get('set') else ' clear'}"
    if k == "var":
        name = names["vars"].get(str(c.get("id")), f"0x{c.get('id', 0):X}")
        return f"{name} {c.get('op')} {c.get('value')}"
    if k == "special":
        return f"{c.get('name')}() {c.get('op')} {c.get('value')}"
    return f"{k} {c.get('op', '')} {c.get('value', '')}".strip()


def summary(path: Path) -> int:
    data = json.loads(path.read_text(encoding="utf-8"))
    names = data.get("names") or {"flags": {}, "vars": {}}
    names.setdefault("flags", {})
    names.setdefault("vars", {})
    maps = data["maps"]
    print("TOTALS", json.dumps(data["totals"], sort_keys=True))

    # header weather and caves
    header = Counter()
    caves = []
    coord = Counter()
    for mid, m in maps.items():
        w = (m.get("header") or {}).get("weather")
        if w is not None:
            header[WEATHER.get(w, str(w))] += 1
        if (m.get("header") or {}).get("cave"):
            caves.append(mid)
        for cw in m.get("coordWeather") or []:
            coord[(mid, WEATHER.get(cw.get("trigger"), str(cw.get("trigger"))))] += 1
    print("\nHEADER WEATHER (maps):", ", ".join(f"{k} {v}" for k, v in header.most_common()))
    print(f"CAVE FLAG: {len(caves)} maps")
    print("COORD WEATHER (map: trigger x cells):")
    by_map = defaultdict(list)
    for (mid, trig), n in sorted(coord.items()):
        by_map[mid].append(f"{trig} x{n}")
    for mid in sorted(by_map):
        print(f"  {mid}: {', '.join(by_map[mid])}")

    # world commands by op
    ops = Counter()
    for m in maps.values():
        for w in m["world"]:
            ops[w["op"] if w["op"] != "special" else f"special:{w.get('name')}"] += 1
    print("\nWORLD COMMANDS (distinct row x path):")
    for op, n in sorted(ops.items()):
        print(f"  {op}: {n}")

    # terrain: setmetatile and layouts, per map and entry kind
    print("\nTERRAIN BY MAP (setmetatile rows from entry scripts / from events; layout swaps):")
    for mid in sorted(maps):
        m = maps[mid]
        entry = [w for w in m["world"] if w["op"] == "setmetatile" and w["root"] in ENTRY]
        events = [w for w in m["world"] if w["op"] == "setmetatile" and w["root"] not in ENTRY]
        lays = [w for w in m["world"] if w["op"] == "setmaplayoutindex"]
        spec = [w for w in m["world"] if w["op"] == "special" and w.get("class") in ("terrain", "layout")]
        if not (entry or events or lays or spec):
            continue
        cells_entry = {(w["args"].get("x"), w["args"].get("y")) for w in entry}
        cells_events = {(w["args"].get("x"), w["args"].get("y")) for w in events}
        line = f"  {mid}: setmetatile entry {len(entry)} rows/{len(cells_entry)} cells, events {len(events)} rows/{len(cells_events)} cells"
        if lays:
            parts = []
            for lid, info in sorted((m.get("layouts") or {}).items()):
                parts.append(f"layout {lid}: " + ("var" if info.get("var") else
                             f"{'found' if info.get('found') else info.get('why')}, size {'ok' if info.get('sizeOk') else 'MISMATCH'}, {info.get('changed', 0)} cells"))
            line += "; " + "; ".join(parts)
        if spec:
            line += "; specials " + ", ".join(sorted({w["name"] for w in spec}))
        print(line)
        guards = Counter()
        for w in entry + lays:
            guards["; ".join(cond_text(c, names) for c in w.get("conds") or []) or "(always)"] += 1
        for g, n in guards.most_common(8):
            print(f"      when {g}  [{n}]")

    # weather and light commands
    print("\nWEATHER AND LIGHT COMMANDS:")
    for mid in sorted(maps):
        m = maps[mid]
        rows = [w for w in m["world"] if w["op"] in ("setweather", "doweather", "resetweather", "setflashlevel",
                                                    "animateflash") or (w["op"] == "special" and w.get("class") == "weather")]
        if not rows:
            continue
        parts = Counter()
        for w in rows:
            if w["op"] == "setweather":
                wid = (w.get("args") or {}).get("weather")
                label = f"setweather {WEATHER.get(wid, wid)}"
            elif w["op"] in ("setflashlevel", "animateflash"):
                label = f"{w['op']} {(w.get('args') or {}).get('level')}"
            elif w["op"] == "special":
                label = f"special {w.get('name')}"
            else:
                label = w["op"]
            parts[(label, w["root"])] += 1
        print(f"  {mid}: " + ", ".join(f"{label} ({root})x{n}" for (label, root), n in sorted(parts.items())))

    # other world specials
    print("\nOTHER WORLD SPECIALS (name: maps):")
    spec_maps = defaultdict(set)
    for mid, m in maps.items():
        for w in m["world"]:
            if w["op"] == "special" and w.get("class") not in ("terrain", "layout", "weather"):
                spec_maps[w["name"]].add(mid)
    for name in sorted(spec_maps):
        print(f"  {name}: {len(spec_maps[name])} ({', '.join(sorted(spec_maps[name])[:6])}{'...' if len(spec_maps[name]) > 6 else ''})")

    # objects and warps
    obj = Counter()
    warps = Counter()
    for m in maps.values():
        for w in m["world"]:
            if w["op"] in ("removeobject", "addobject", "setobjectxyperm", "copyobjectxytoperm", "showobjectat",
                           "hideobjectat", "setobjectmovementtype", "createvobject", "setberrytree"):
                obj[w["op"]] += 1
            if w["op"] in ("setwarp", "setdynamicwarp", "setdivewarp", "setholewarp", "setescapewarp"):
                warps[w["op"]] += 1
    print("\nOBJECT COMMANDS:", dict(sorted(obj.items())))
    print("WARP SETTERS:", dict(sorted(warps.items())))
    truncated = sorted(mid for mid, m in maps.items() if m.get("truncated"))
    if truncated:
        print("TRUNCATED WALKS:", ", ".join(truncated))
    return 0


# ---- the live sweep -------------------------------------------------------------------------------------------

SWEEP = REPORTS / "g3_story_sweep.json"
CASES = REPORTS / "g3_story_cases.lua"
ANALYSIS = REPORTS / "g3_story_sweep_analysis.json"
SWEEP_PORT = 27224
WORLD_OPS = {"setmetatile", "setmaplayoutindex", "setweather", "doweather", "resetweather", "setflashlevel",
             "animateflash"}
WORLD_SPECIAL_CLASSES = {"terrain", "layout", "weather"}
# places the sweep leaves out: the Battle Frontier's generated floors and link rooms, secret bases (the player's own
# decorations), the contest and link halls; the scan still lists them
SKIP_PREFIX = ("EM_BATTLE_FRONTIER_", "EM_BATTLE_PYRAMID_", "EM_TRAINER_HILL_", "EM_SECRET_BASE_", "EM_UNION_ROOM",
               "EM_RECORD_CORNER", "EM_TRADE_CENTER", "EM_BATTLE_COLOSSEUM", "EM_CONTEST_HALL", "EM_LINK_CONTEST",
               "EM_SS_TIDAL")
VAR_ABNORMAL = 0x4037
# VAR_ABNORMAL_WEATHER_LOCATION's values and their routes (natives_field_rse.lua GetAbnormalWeatherMapNameAndType)
ABNORMAL_ROUTES = {"EM_ROUTE114": {1, 2}, "EM_ROUTE115": {3, 4}, "EM_ROUTE116": {5, 6}, "EM_ROUTE118": {7, 8},
                   "EM_ROUTE105": {9, 10}, "EM_ROUTE125": {11, 12}, "EM_ROUTE127": {13, 14}, "EM_ROUTE129": {15, 16}}
STATES_PER_MAP = 40
PC_2F_SAMPLE = "EM_OLDALE_TOWN_POKEMON_CENTER_2F"
OPS = {"<": lambda a, b: a < b, "==": lambda a, b: a == b, ">": lambda a, b: a > b, "<=": lambda a, b: a <= b,
       ">=": lambda a, b: a >= b, "!=": lambda a, b: a != b}


def is_world(w: dict) -> bool:
    if w["root"] not in ENTRY:
        return False
    return w["op"] in WORLD_OPS or (w["op"] == "special" and w.get("class") in WORLD_SPECIAL_CLASSES)


def pick_value(cons: list[tuple[str, int]]) -> int | None:
    values = [v for _, v in cons if v is not None]
    candidates = []
    for op, v in cons:
        if op == "==" and v is not None:
            candidates.append(v)
    for v in values:
        candidates += [v, v + 1, max(v - 1, 0)]
    candidates += list(range(0, 32))
    for cand in candidates:
        if all(v is not None and OPS[op](cand, v) for op, v in cons):
            return cand
    return None


def solve(conds: list[dict], root_info: dict | None, map_id: str, flag_ids: dict) -> dict | None:
    """The flags and vars that make one path's conditions hold, or None (contradictory, a temp or special var, a
    condition the sweep can't set)."""
    flags: dict[int, bool] = {}
    cons: dict[int, list] = defaultdict(list)
    mirage = None
    conds = list(conds)
    if root_info and root_info.get("var") is not None:
        conds.append({"k": "var", "id": root_info["var"], "op": "==", "value": root_info.get("value")})
    for c in conds:
        k = c.get("k")
        if k == "flag":
            fid, want = c["id"], bool(c.get("set"))
            if flags.get(fid, want) != want:
                return None
            flags[fid] = want
        elif k == "var":
            vid = c.get("id")
            if vid is None or 0x4000 <= vid <= 0x400F or vid >= 0x8000:
                return None  # temp vars are cleared on map load; special vars belong to a running script
            cons[vid].append((c.get("op"), c.get("value")))
        elif k == "special" and c.get("name") == "IsMirageIslandPresent":
            want = [r for r in (1, 0) if c.get("value") is not None and OPS[c["op"]](r, c["value"])]
            if not want or (mirage is not None and mirage != (want[0] == 1)):
                return None
            mirage = want[0] == 1
        else:
            return None
    vars_: dict[int, int] = {}
    for vid, cs in cons.items():
        v = pick_value(cs)
        if v is None:
            return None
        vars_[vid] = v
    if map_id in ABNORMAL_ROUTES and VAR_ABNORMAL in vars_ and vars_[VAR_ABNORMAL] not in ABNORMAL_ROUTES[map_id]:
        return None
    state = {"flags": flags, "vars": vars_}
    tide = flag_ids.get("FLAG_SYS_SHOAL_TIDE")
    if tide is not None and tide in flags:
        state["tide"] = flags[tide]
    if mirage is not None:
        state["mirage"] = mirage
    return state


def make_cases(scan: dict) -> list[dict]:
    names = scan.get("names") or {}
    flag_ids = {n: int(i) for i, n in (names.get("flags") or {}).items()}
    cases = []
    for mid in sorted(scan["maps"]):
        if mid.startswith(SKIP_PREFIX):
            continue
        world = [w for w in scan["maps"][mid]["world"] if is_world(w)]
        if not world:
            continue
        states: dict[str, dict] = {"{}": {"flags": {}, "vars": {}, "label": "new game"}}
        if mid in ABNORMAL_ROUTES:
            for v in sorted(ABNORMAL_ROUTES[mid]):
                states.setdefault(json.dumps({"flags": {}, "vars": {VAR_ABNORMAL: v}}, sort_keys=True),
                                  {"flags": {}, "vars": {VAR_ABNORMAL: v}, "label": f"abnormal weather {v}"})
        for w in world:
            st = solve(w.get("conds") or [], w.get("rootInfo"), mid, flag_ids)
            if st is None:
                continue
            key = json.dumps({k: st[k] for k in ("flags", "vars", "tide", "mirage") if k in st}, sort_keys=True)
            if key not in states:
                what = w["op"] if w["op"] != "special" else f"special {w.get('name')}"
                if w["op"] == "setmaplayoutindex":
                    what += f" {(w.get('args') or {}).get('layout')}"
                if w["op"] == "setweather":
                    what += f" {WEATHER.get((w.get('args') or {}).get('weather'))}"
                states[key] = dict(st, label=f"{what} ({w['root']})")
        kept = list(states.values())
        # the Pokemon Centers' upper floors share one layout and one set of scripts: every state on one of them,
        # the new game's on the others
        if mid.endswith("_POKEMON_CENTER_2F") and mid != PC_2F_SAMPLE:
            kept = kept[:1]
        for st in kept[:STATES_PER_MAP]:
            cases.append(dict(st, map=mid, n=len(cases) + 1))
    return cases


def lua_cases(cases: list[dict]) -> str:
    def lua_str(s: str) -> str:
        return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'
    out = ["return {"]
    for c in cases:
        parts = [f"n = {c['n']}", f"map = {lua_str(c['map'])}", f"label = {lua_str(c['label'])}"]
        parts.append("flags = { " + ", ".join(f"[{fid}] = {'true' if on else 'false'}" for fid, on in sorted(c["flags"].items())) + " }")
        parts.append("vars = { " + ", ".join(f"[{vid}] = {v}" for vid, v in sorted(c["vars"].items())) + " }")
        if "tide" in c:
            parts.append(f"tide = {'true' if c['tide'] else 'false'}")
        if "mirage" in c:
            parts.append(f"mirage = {'true' if c['mirage'] else 'false'}")
        out.append("  { " + ", ".join(parts) + " },")
    out.append("}")
    return "\n".join(out) + "\n"


async def _sweep_client(run, timeout: float):
    from fake_minecraft import FakeMinecraft
    import asyncio
    import time as _time

    if run.wait_marker("KB_FIELD", 600) is None:
        raise RuntimeError("the driver never reached the field")
    mc = FakeMinecraft(port=SWEEP_PORT, role="test", ping_ms=300)
    await mc.connect(attempts=60)
    await mc.hello()
    deadline = _time.monotonic() + timeout
    answered = Counter()
    while run.result.marker("KB_SWEEP_DONE") is None:
        if _time.monotonic() > deadline:
            raise RuntimeError("the sweep did not finish in time")
        if run.proc.poll() is not None:
            raise RuntimeError(f"the game ended early (code {run.proc.returncode})")
        try:
            message = await mc.next(1.0)
        except asyncio.TimeoutError:
            continue
        if message is None:
            raise RuntimeError("the bridge closed the connection")
        kind, data = message["type"], message.get("data") or {}
        # nothing the sweep wants should wait on the client: saves are acknowledged, held battles aborted
        if kind == "save.begin":
            await mc.send("save.done", {"save": data["save"], "ok": True})
            answered[kind] += 1
        elif kind == "battle.start":
            await mc.send("battle.result", {"battle": data["battle"], "outcome": "aborted"})
            answered[kind] += 1
    await asyncio.sleep(1.0)
    received = list(mc.received)
    await mc.send("debug.quit", {"code": 0})
    await asyncio.sleep(0.5)
    await mc.close()
    return received, answered


def run_sweep(timeout: float, maps: str = "") -> int:
    import asyncio

    scan = json.loads(SCAN.read_text(encoding="utf-8"))
    cases = make_cases(scan)
    CASES.write_text(lua_cases(cases), encoding="utf-8")
    print(f"CASES {len(cases)} on {len({c['map'] for c in cases})} maps")
    env = {"KB_SCENE": "sweep", "KB_REPORT_DIR": str(REPORTS.resolve()), "KB_CASES": str(CASES.resolve()),
           "KB_DRIVER_SECONDS": str(int(timeout)), "KB_DATASET_MEMO": "30", "KB_TEST_PORT": str(SWEEP_PORT),
           "KB_MAPS": maps}
    with launch("kb_g3story.lua", game=GAME, extra_env=env, echo=False) as run:
        received, answered = asyncio.run(_sweep_client(run, timeout))
        result = run.wait(180)
    print("RUN", json.dumps(result.summary(), sort_keys=True))
    print("ANSWERED", dict(answered))
    analysis = analyse(received, json.loads(SWEEP.read_text(encoding="utf-8")))
    ANALYSIS.write_text(json.dumps(analysis, indent=1, sort_keys=True), encoding="utf-8")
    report(analysis)
    return 0 if result.returncode == 0 else 1


OBJECTS = REPORTS / "g3_story_objects.json"
OBJECTS_ANALYSIS = REPORTS / "g3_story_objects_analysis.json"


def run_objects(timeout: float, maps: str = "") -> int:
    import asyncio

    env = {"KB_SCENE": "objects", "KB_REPORT_DIR": str(REPORTS.resolve()), "KB_DRIVER_SECONDS": str(int(timeout)),
           "KB_DATASET_MEMO": "30", "KB_TEST_PORT": str(SWEEP_PORT), "KB_MAPS": maps}
    with launch("kb_g3story.lua", game=GAME, extra_env=env, echo=False) as run:
        received, answered = asyncio.run(_sweep_client(run, timeout))
        result = run.wait(180)
    print("RUN", json.dumps(result.summary(), sort_keys=True))
    print("ANSWERED", dict(answered))
    analysis = analyse(received, json.loads(OBJECTS.read_text(encoding="utf-8")))
    OBJECTS_ANALYSIS.write_text(json.dumps(analysis, indent=1, sort_keys=True), encoding="utf-8")
    report_objects(analysis)
    return 0 if result.returncode == 0 else 1


def report_objects(analysis: dict) -> None:
    cases = analysis["cases"]
    ok_npcs = sum(1 for e in cases if (e.get("objects") or {}).get("npcs_vs_engine") == "ok")
    ok_tog = sum(1 for e in cases if (e.get("objects") or {}).get("toggles") == "ok")
    with_obj = [e for e in cases if e.get("objects")]
    print(f"\nOBJECTS: {len(cases)} cases on {len({e['map'] for e in cases})} maps; compared {len(with_obj)}; "
          f"client NPCs = engine's visible objects in {ok_npcs}; toggles = flags in {ok_tog}")
    for e in cases:
        o = e.get("objects")
        if not o:
            print(f"  #{e['n']} {e['map']} [{e['label']}]: not compared ({e.get('err') or e.get('bridge') or 'no visit'}"
                  f"{', halted' if e.get('halted') else ''})")
            continue
        if o["npcs_vs_engine"] != "ok" or o["toggles"] != "ok" or o["engine_vs_flags"]:
            print(f"  #{e['n']} {e['map']} [{o['mode']}]: live {o['live']}/{o['hideable']} hideable; npcs {o['npcs_vs_engine']}; "
                  f"toggles {o['toggles']}; engine differs from flags for {o['engine_vs_flags']}"
                  f"{'; halted' if e.get('halted') else ''}")


def visits_of(received: list[dict]) -> list[dict]:
    """The hidden player's map visits as the client saw them, each with the story.cells it was told, the NPCs it
    holds (npc.set, then npc.add/npc.remove) and the objects the story toggles hide on that map when it ends."""
    visits: list[dict] = []
    cur = None
    toggles: dict[str, dict[str, bool]] = defaultdict(dict)

    def arrive(map_id, index):
        nonlocal cur
        if map_id and map_id != cur:
            if visits:
                visits[-1]["hidden"] = sorted(o for o, v in toggles[visits[-1]["map"]].items() if v is False)
            cur = map_id
            visits.append({"map": map_id, "index": index, "cells": {}, "messages": 0, "npcs": None})

    for i, m in enumerate(received):
        kind, data = m["type"], m.get("data") or {}
        if kind == "snapshot":
            toggles.clear()
            for map_id, objs in (data.get("toggles") or {}).items():
                for obj, vis in (objs or {}).items():
                    toggles[map_id][obj] = vis
            arrive((data.get("player") or {}).get("map"), i)
        elif kind == "player.pos":
            arrive(data.get("map"), i)
        elif kind == "warp" and data.get("phase") == "arrive":
            arrive((data.get("to") or {}).get("map"), i)
        elif kind == "story.cells":
            arrive(data.get("map"), i)
            visit = visits[-1]
            visit["messages"] += 1
            for cell in data.get("cells") or []:
                visit["cells"][(cell["x"], cell["y"])] = cell["metatile"]
        elif kind == "story.toggles":
            toggles[data.get("map")][data.get("obj")] = data.get("visible")
        elif kind == "npc.set":
            arrive(data.get("map"), i)
            visits[-1]["npcs"] = {n["id"] for n in data.get("list") or []}
        elif kind == "npc.add" and visits and visits[-1]["npcs"] is not None:
            visits[-1]["npcs"].add(data.get("id"))
        elif kind == "npc.remove" and visits and visits[-1]["npcs"] is not None:
            visits[-1]["npcs"].discard(data.get("id"))
    if visits:
        visits[-1]["hidden"] = sorted(o for o, v in toggles[visits[-1]["map"]].items() if v is False)
    return visits


def analyse(received: list[dict], sweep: dict) -> dict:
    visits = visits_of(received)
    counts = Counter(m["type"] for m in received)
    used = -1
    out = []
    for rec in sweep["cases"]:
        entry = {"n": rec.get("n"), "map": rec.get("map"), "label": rec.get("label"), "ok": rec.get("ok"),
                 "err": rec.get("err"), "halted": bool(rec.get("halted")), "stuck": bool(rec.get("stuck"))}
        s = rec.get("settled") or {}
        cells = s.get("cells") or []
        changed = [c for c in cells if not c.get("same")]
        entry["current"] = s.get("map")
        entry["cells"] = len(changed)
        entry["mids"] = sum(1 for c in changed if c["b"][0] != c["l"][0])
        entry["collOnly"] = sum(1 for c in changed if c["b"][0] == c["l"][0])
        entry["classChanged"] = sum(1 for c in changed if c.get("bc") != c.get("lc"))
        entry["classes"] = dict(Counter(f"{c.get('bc')}>{c.get('lc')}" for c in changed if c.get("bc") != c.get("lc")))
        e = rec.get("entry") or {}
        entry["entryCells"] = sum(1 for c in (e.get("cells") or []) if not c.get("same"))
        w = rec.get("weather") or {}
        entry["weather"] = {"saved": WEATHER.get(w.get("saved"), w.get("saved")),
                            "drawn": WEATHER.get(w.get("drawn"), w.get("drawn")), "flash": w.get("flash")}
        ew = rec.get("entryWeather") or {}
        entry["entryWeather"] = WEATHER.get(ew.get("saved"), ew.get("saved"))
        # the bridge's side: the cells the client holds for this visit against the engine's
        if rec.get("ok") and s.get("map") == rec.get("map"):
            visit = None
            for vi in range(used + 1, len(visits)):
                if visits[vi]["map"] == rec["map"]:
                    visit, used = visits[vi], vi
                    break
            if visit is None:
                entry["bridge"] = "no visit seen"
            else:
                base = {(c["x"], c["y"]): c["b"][0] for c in cells + (e.get("cells") or [])}
                truth = {(c["x"], c["y"]): c["l"][0] for c in changed if c["b"][0] != c["l"][0]}
                held = {k: v for k, v in visit["cells"].items() if base.get(k) != v}
                missing = [k for k in truth if k not in held]
                wrong = [k for k in truth if k in held and held[k] != truth[k]]
                extra = [k for k in held if k not in truth]
                entry["bridge"] = "ok" if not (missing or wrong or extra) else "mismatch"
                entry["bridgeCounts"] = {"held": len(held), "missing": len(missing), "wrong": len(wrong),
                                         "extra": len(extra), "messages": visit["messages"]}
                obj = rec.get("objects")
                if obj:
                    live = {f"O{lid}" for lid in obj.get("live") or []}
                    hide = {f"O{o['lid']}" for o in obj.get("hideable") or []}
                    # the flags as they are after the load: temp flags are cleared on every load and entry scripts
                    # set or clear some, so the case's own setting is not the truth
                    flagged = {f"O{o['lid']}" for o in obj.get("hideable") or [] if o.get("set")}
                    npcs = visit["npcs"] if visit["npcs"] is not None else set()
                    hidden = set(visit.get("hidden") or [])
                    entry["objects"] = {
                        "mode": obj["mode"], "live": len(live), "hideable": len(hide), "flagged": len(flagged),
                        # the client's NPC list is the engine's visible objects (Gen 3 spawns only those near the
                        # player, so a far one is in neither)
                        "npcs_vs_engine": "ok" if npcs == live else
                        f"client {len(npcs)} engine {len(live)}: missing {sorted(live - npcs)[:6]} extra {sorted(npcs - live)[:6]}",
                        # the toggles hide exactly the objects whose flag is set now
                        "toggles": "ok" if hidden == flagged else
                        f"hidden {len(hidden)} flagged {len(flagged)}: missing {sorted(flagged - hidden)[:6]} extra {sorted(hidden - flagged)[:6]}",
                        # the engine: an object whose flag is set never stands on the map
                        "engine_vs_flags": sorted(flagged & live)[:8],
                    }
        out.append(entry)
    return {"cases": out, "messages": dict(counts), "visits": len(visits)}


def report(analysis: dict) -> None:
    cases = analysis["cases"]
    print(f"\nSWEEP: {len(cases)} cases, {analysis['visits']} visits seen by the client, messages "
          f"{sum(analysis['messages'].values())} ({analysis['messages'].get('story.cells', 0)} story.cells)")
    for e in cases:
        bridge = e.get("bridge", "-")
        if e.get("bridgeCounts") and bridge != "ok":
            bridge += " " + json.dumps(e["bridgeCounts"], sort_keys=True)
        flags = []
        if e.get("halted"):
            flags.append("halted")
        if e.get("stuck"):
            flags.append("stuck")
        if e.get("current") and e["current"] != e["map"]:
            flags.append(f"left for {e['current']}")
        if not e.get("ok"):
            flags.append(f"failed {e.get('err')}")
        w = e["weather"]
        print(f"  #{e['n']:>3} {e['map']} [{e['label']}]: cells {e['cells']} (metatile {e['mids']}, collision only "
              f"{e['collOnly']}), walkability {e['classChanged']} {e['classes'] or ''}; weather {w['saved']}/{w['drawn']}, "
              f"flash {w['flash']}; bridge {bridge}{'; ' + ', '.join(flags) if flags else ''}")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_scan = sub.add_parser("scan")
    p_scan.add_argument("--timeout", type=float, default=900.0)
    sub.add_parser("summary")
    p_sweep = sub.add_parser("sweep")
    p_sweep.add_argument("--timeout", type=float, default=5400.0)
    p_sweep.add_argument("--maps", default="", help="only these maps (comma-separated)")
    p_obj = sub.add_parser("objects")
    p_obj.add_argument("--timeout", type=float, default=7200.0)
    p_obj.add_argument("--maps", default="", help="only these maps (comma-separated)")
    sub.add_parser("cases")
    sub.add_parser("report")
    args = parser.parse_args(argv)
    if args.cmd == "scan":
        code = run_scan(args.timeout)
        if code != 0:
            return code
        return summary(SCAN)
    if args.cmd == "sweep":
        return run_sweep(args.timeout, args.maps)
    if args.cmd == "objects":
        return run_objects(args.timeout, args.maps)
    if args.cmd == "cases":
        cases = make_cases(json.loads(SCAN.read_text(encoding="utf-8")))
        by_map = Counter(c["map"] for c in cases)
        print(f"CASES {len(cases)} on {len(by_map)} maps")
        for c in cases:
            print(f"  #{c['n']} {c['map']}: {c['label']} flags={len(c['flags'])} vars={len(c['vars'])}"
                  f"{' tide=' + str(c['tide']) if 'tide' in c else ''}{' mirage=' + str(c['mirage']) if 'mirage' in c else ''}")
        return 0
    if args.cmd == "report":
        report(json.loads(ANALYSIS.read_text(encoding="utf-8")))
        return 0
    return summary(SCAN)


if __name__ == "__main__":
    sys.exit(main())
