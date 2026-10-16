"""Crystal's story check on the real engine (docs/specs/STORY_EVENTS_PLAN.md §5): what Crystal's story changes in
the world, and whether each change reaches Minecraft.

    python tools/e2e/g2_story_check.py scan        # launch the test instance, scan every map's scripts, summarise
    python tools/e2e/g2_story_check.py summary     # summarise the last scan again
    python tools/e2e/g2_story_check.py cases       # the live cases the last scan gives
    python tools/e2e/g2_story_check.py sweep [--maps A,B]    # the live check, a stand-in client connected
    python tools/e2e/g2_story_check.py objects [--maps A,B]  # objects' event flags set and cleared, map by map
    python tools/e2e/g2_story_check.py report      # print the last sweep's analysis again
    python tools/e2e/g2_story_check.py sites       # the export's swap sites against the scan and the sweep

The cases are the scan's (every distinct load-time command once) and the bedroom's decorations (§5.5: the bare room,
then each decoration alone in its slot).

The driver is drivers/kb_g2story.lua. Its files in local/reports/ (g2_story_scan.json, g2_story_sweep.json,
g2_story_objects.json) are ROM-derived (map blocks and block numbers stay in local/); this script prints only map
symbols, flag numbers, special names and counts, and writes its analyses (the same) beside them. The sweep and the
objects run compare what a stand-in Minecraft client (fake_minecraft, role test) was told per visit with the engine's
own state: the changed blocks (story.blocks and the snapshot's blocks), the NPC list (npc.set/add/remove) and the
story toggles.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from run_gen1recomp import REPORTS, launch  # noqa: E402

GAME = "crystal"
SCAN = REPORTS / "g2_story_scan.json"
SWEEP = REPORTS / "g2_story_sweep.json"
CASES = REPORTS / "g2_story_cases.lua"
ANALYSIS = REPORTS / "g2_story_sweep_analysis.json"
OBJECTS = REPORTS / "g2_story_objects.json"
OBJECTS_ANALYSIS = REPORTS / "g2_story_objects_analysis.json"
SITES_REPORT = REPORTS / "g2_story_sites_check.json"
SWEEP_PORT = 27226
SITES_PORT = 27227
PREFIX = "G2_"  # map ids on the wire (GEN2_BRIDGE_PLAN.md J4)

# the entry points a map load runs (World:setMap: NEWMAP, TILES, OBJECTS; the scene script once the field is idle)
ENTRY = ("MAPCALLBACK_NEWMAP", "MAPCALLBACK_TILES", "MAPCALLBACK_OBJECTS", "scene")
# commands that change what the map shows, and the specials that do (the bedroom's decorations)
WORLD_OPS = {"changeblock", "changemapblocks", "appear", "disappear", "moveobject", "variablesprite"}
WORLD_SPECIALS = {"ToggleMaptileDecorations", "ToggleDecorationsVisibility"}
# places the sweep leaves out: the Battle Tower's generated rooms and the link rooms; the scan still lists them
SKIP_PREFIX = ("BATTLE_TOWER_BATTLE_ROOM", "BATTLE_TOWER_ELEVATOR", "BATTLE_TOWER_HALLWAY", "TRADE_CENTER", "COLOSSEUM",
               "TIME_CAPSULE", "MOBILE_TRADE_ROOM", "MOBILE_BATTLE_ROOM")
STATES_PER_MAP = 40
# the bedroom whose decorations are blocks (§5.5), and the four slots its callback draws
BEDROOM = "PLAYERS_HOUSE_2F"
DECORATION_SLOTS = ("bed", "carpet", "plant", "poster")
# readvar's variables (World.lua:109-138) the solver can set: the time of day, the hour and the weekday
VAR_TIMEOFDAY, VAR_HOUR, VAR_WEEKDAY = 0x04, 0x0A, 0x0B
# checktime's bits (MORN 1, DAY 2, NITE 4) and an hour inside each (morning 4-9, day 10-17, night 18-3)
TOD_HOURS = {1: [8, 4, 5, 6, 7, 9], 2: [12, 10, 11, 13, 14, 15, 16, 17], 4: [22, 18, 19, 20, 21, 23, 0, 1, 2, 3]}
TOD_F = {0: 1, 1: 2, 2: 4}  # readvar VAR_TIMEOFDAY: MORN_F 0, DAY_F 1, NITE_F 2 -> the bit

TESTS = {"~=0": lambda x, v: x != 0, "==0": lambda x, v: x == 0, "==": lambda x, v: x == v, "~=": lambda x, v: x != v,
         ">": lambda x, v: x > v, "<": lambda x, v: x < v, "<=": lambda x, v: x <= v, ">=": lambda x, v: x >= v}


def holds(op: str, value, x: int) -> bool:
    test = TESTS.get(op)
    if test is None or (value is None and op not in ("~=0", "==0")):
        return False
    return test(x, value)


def run_scan(timeout: float) -> int:
    REPORTS.mkdir(parents=True, exist_ok=True)
    env = {"KB_SCENE": "scan", "KB_REPORT_DIR": str(REPORTS.resolve()), "KB_DATASET_MEMO": "30",
           "KB_TEST_PORT": str(SWEEP_PORT)}
    with launch("kb_g2story.lua", game=GAME, extra_env=env) as run:
        result = run.wait(timeout)
    print("RUN", json.dumps(result.summary(), sort_keys=True))
    return 0 if result.returncode == 0 and result.marker("KB_SCAN") else 1


def cond_text(c: dict, names: dict) -> str:
    w, op, v = c.get("w") or {}, c.get("op"), c.get("value")
    k = w.get("k")
    if k == "event":
        return f"event {w.get('id')} {'set' if holds(op, v, 1) else 'clear'}"
    if k == "flag":
        fid = w.get("id")
        name = (names.get("engineFlags") or {}).get(str((fid or 0) + 1)) if isinstance(names.get("engineFlags"), dict) else None
        return f"{name or f'engine flag {fid}'} {'set' if holds(op, v, 1) else 'clear'}"
    if k == "scene":
        return f"scene {op} {v if v is not None else ''}".strip()
    if k == "mapscene":
        return f"scene of {w.get('group')}.{w.get('map')} {op} {v if v is not None else ''}".strip()
    if k == "time":
        return f"time in {w.get('mask')} {op} {v if v is not None else ''}".strip()
    if k == "var":
        return f"var 0x{w.get('id', 0):02X} {op} {v if v is not None else ''}".strip()
    if k == "mem":
        return f"mem 0x{w.get('addr', 0):04X} {op} {v if v is not None else ''}".strip()
    if k == "special":
        return f"{w.get('name')}() {op} {v if v is not None else ''}".strip()
    if k == "const":
        return f"const {w.get('value')} {op} {v if v is not None else ''}".strip()
    return f"{w.get('op') or k} {op} {v if v is not None else ''}".strip()


def summary(path: Path) -> int:
    data = json.loads(path.read_text(encoding="utf-8"))
    names = data.get("names") or {}
    maps = data["maps"]
    print("TOTALS", json.dumps(data["totals"], sort_keys=True))
    ops = Counter()
    roots = Counter()
    for m in maps.values():
        for w in m["world"]:
            ops[w["op"] if w["op"] != "special" else f"special:{(w.get('args') or {}).get('name')}"] += 1
            roots[w["root"]] += 1
    print("\nWORLD COMMANDS (distinct row x path):")
    for op, n in sorted(ops.items()):
        print(f"  {op}: {n}")
    print("BY ENTRY POINT:", ", ".join(f"{k} {v}" for k, v in sorted(roots.items())))
    cut = {mid: m["truncatedRoots"] for mid, m in maps.items() if m.get("truncatedRoots")}
    load_cut = sorted(mid for mid, r in cut.items() if any(k in ENTRY for k in r))
    print(f"\nTRUNCATED WALKS: {len(cut)} maps; on a load entry point: {len(load_cut)} "
          f"({', '.join(load_cut[:20])}{' ...' if len(load_cut) > 20 else ''})")
    dark = sorted(mid for mid, m in maps.items() if m.get("dark"))
    print(f"\nDARK MAPS ({len(dark)}): {', '.join(dark)}")
    print("\nBLOCKS BY MAP (changeblock rows on load / from events; changemapblocks):")
    for mid in sorted(maps):
        m = maps[mid]
        load = [w for w in m["world"] if w["op"] == "changeblock" and w["root"] in ENTRY]
        events = [w for w in m["world"] if w["op"] == "changeblock" and w["root"] not in ENTRY]
        whole = [w for w in m["world"] if w["op"] == "changemapblocks"]
        if not (load or events or whole):
            continue
        cells_load = {((w["args"] or {}).get("x"), (w["args"] or {}).get("y")) for w in load}
        cells_events = {((w["args"] or {}).get("x"), (w["args"] or {}).get("y")) for w in events}
        line = (f"  {mid}: on load {len(load)} rows/{len(cells_load)} blocks, from events {len(events)} rows/"
                f"{len(cells_events)} blocks")
        if whole:
            line += f"; changemapblocks {len(whole)}"
        print(line)
        guards = Counter()
        for w in load + whole:
            guards["; ".join(cond_text(c, names) for c in w.get("conds") or []) or "(always)"] += 1
        for g, n in guards.most_common(6):
            print(f"      when {g}  [{n}]")
    print("\nOBJECTS ON LOAD (appear/disappear/moveobject/variablesprite from callbacks and scene scripts):")
    for mid in sorted(maps):
        rows = [w for w in maps[mid]["world"] if w["op"] in ("appear", "disappear", "moveobject", "variablesprite")
                and w["root"] in ENTRY]
        if rows:
            print(f"  {mid}: " + ", ".join(f"{k} {v}" for k, v in sorted(Counter(w["op"] for w in rows).items())))
    specials = Counter()
    for m in maps.values():
        for w in m["world"]:
            if w["op"] == "special":
                specials[(w.get("args") or {}).get("name")] += 1
    print("\nSPECIALS CALLED:", ", ".join(f"{k} {v}" for k, v in sorted(specials.items(), key=lambda kv: str(kv[0]))))
    return 0


# ---- the live cases ------------------------------------------------------------------------------------------------

def pick_value(cons: list[tuple[str, int]], lo: int = 0, hi: int = 255) -> int | None:
    candidates = []
    for op, v in cons:
        if op == "==" and v is not None:
            candidates.append(v)
    for _, v in cons:
        if v is not None:
            candidates += [v, v + 1, max(v - 1, 0)]
    candidates += list(range(0, 32))
    for cand in candidates:
        if lo <= cand <= hi and all(holds(op, v, cand) for op, v in cons):
            return cand
    return None


def solve(conds: list[dict], root: str, root_info: dict | None, map_id: str, scan: dict) -> dict | None:
    """The event flags, engine flags, scenes, script memory and clock that make one path's conditions hold, or None
    (contradictory, a temp flag set, a result the sweep can't set)."""
    events: dict[int, bool] = {}
    flags: dict[int, bool] = {}
    scenes: dict[str, list] = defaultdict(list)
    mapscenes: dict[tuple, list] = defaultdict(list)
    mem: dict[int, list] = defaultdict(list)
    hour_cons: list = []
    day_cons: list = []
    tods = {1, 2, 4}
    if root == "scene" and root_info and root_info.get("scene") is not None:
        scenes[map_id].append(("==", root_info["scene"]))
    for c in conds:
        w, op, v = c.get("w") or {}, c.get("op"), c.get("value")
        k = w.get("k")
        if k in ("event", "flag"):
            on, off = holds(op, v, 1), holds(op, v, 0)
            if on and off:
                continue
            if not (on or off):
                return None
            fid = w.get("id")
            if fid is None:
                return None
            store = events if k == "event" else flags
            if k == "event" and fid < 8:
                if on:
                    return None  # flags 0-7 are cleared on every load (Events:resetMapBuffer)
                continue
            if store.get(fid, on) != on:
                return None
            store[fid] = on
        elif k == "scene":
            scenes[map_id].append((op, v if v is not None else 0))
        elif k == "mapscene":
            mapscenes[(w.get("group"), w.get("map"))].append((op, v if v is not None else 0))
        elif k == "time":
            mask = w.get("mask") or 0
            tods &= {t for t in (1, 2, 4) if holds(op, v, 1 if t & mask else 0)}
        elif k == "var" and w.get("id") == VAR_TIMEOFDAY:
            tods &= {bit for f, bit in TOD_F.items() if holds(op, v, f)}
        elif k == "var" and w.get("id") == VAR_HOUR:
            hour_cons.append((op, v if v is not None else 0))
        elif k == "var" and w.get("id") == VAR_WEEKDAY:
            day_cons.append((op, v if v is not None else 0))
        elif k == "mem" and w.get("addr") is not None:
            mem[w["addr"]].append((op, v if v is not None else 0))
        elif k == "const":
            if not holds(op, v, w.get("value") or 0):
                return None
        else:
            return None
    if not tods:
        return None
    state: dict = {"events": events, "flags": flags}
    picked_scenes = {}
    for mid, cons in scenes.items():
        val = pick_value(cons)
        if val is None:
            return None
        if val != 0:
            picked_scenes[mid] = val
    state["scenes"] = picked_scenes
    groups = {(m.get("group"), m.get("map")): mid for mid, m in scan["maps"].items()}
    picked_ms = []
    for gm, cons in mapscenes.items():
        val = pick_value(cons)
        if val is None:
            return None
        target = groups.get(gm)
        if target == map_id:
            if picked_scenes.get(map_id, 0) != val:
                return None
        elif val != 0:
            picked_ms.append({"group": gm[0], "map": gm[1], "value": val})
    state["mapscenes"] = picked_ms
    if tods != {1, 2, 4} or hour_cons:
        hours = [h for t in (2, 1, 4) if t in tods for h in TOD_HOURS[t]]
        hour = next((h for h in hours if all(holds(op, v, h) for op, v in hour_cons)), None)
        if hour is None:
            return None
        state["hour"] = hour
    if day_cons:
        day = pick_value(day_cons, 0, 6)
        if day is None:
            return None
        state["day"] = day
    picked_mem = {}
    for addr, cons in mem.items():
        val = pick_value(cons)
        if val is None:
            return None
        if val != 0:
            picked_mem[addr] = val
    state["mem"] = picked_mem
    return state


def is_load_world(w: dict) -> bool:
    if w["root"] not in ENTRY:
        return False
    return w["op"] in WORLD_OPS or (w["op"] == "special" and (w.get("args") or {}).get("name") in WORLD_SPECIALS)


def state_key(st: dict) -> str:
    return json.dumps({k: st.get(k) for k in ("events", "flags", "scenes", "mapscenes", "hour", "day", "mem")},
                      sort_keys=True, default=str)


def make_cases(scan: dict) -> tuple[list[dict], Counter]:
    cases = []
    unsolved = Counter()
    for mid in sorted(scan["maps"]):
        if mid.startswith(SKIP_PREFIX):
            continue
        world = [w for w in scan["maps"][mid]["world"] if is_load_world(w)]
        if not world:
            continue
        new = {"events": {}, "flags": {}, "scenes": {}, "mapscenes": [], "mem": {}, "label": "new game"}
        states: dict[str, dict] = {state_key(new): new}
        solved = []
        for w in world:
            st = solve(w.get("conds") or [], w["root"], w.get("rootInfo"), mid, scan)
            if st is None:
                unsolved[mid] += 1
                continue
            what = w["op"] if w["op"] != "special" else f"special {(w.get('args') or {}).get('name')}"
            solved.append((json.dumps([w["op"], w.get("args")], sort_keys=True), dict(st, label=f"{what} ({w['root']})")))
        # every distinct command once first (a map whose callback branches on many flags has more paths than cases),
        # then the other states while the map has room
        covered = set()
        for target, st in solved:
            if target not in covered:
                covered.add(target)
                states.setdefault(state_key(st), st)
        for _, st in solved:
            if len(states) >= STATES_PER_MAP:
                break
            states.setdefault(state_key(st), st)
        for st in list(states.values())[:STATES_PER_MAP]:
            cases.append(dict(st, map=mid, n=len(cases) + 1))
    # the bedroom's decorations (§5.5): the bare room, then each decoration alone in its slot
    decorations = scan.get("decorations") or []
    if BEDROOM in scan["maps"] and decorations:
        bare = {slot: 0 for slot in DECORATION_SLOTS}
        rooms = [("decorations bare", bare)]
        for d in decorations:
            rooms.append((f"decoration {d['slot']} {d['id']}", dict(bare, **{d["slot"]: d["id"]})))
        for label, decor in rooms:
            cases.append({"events": {}, "flags": {}, "scenes": {}, "mapscenes": [], "mem": {}, "label": label,
                          "decorations": decor, "map": BEDROOM, "n": len(cases) + 1})
    return cases, unsolved


def lua_cases(cases: list[dict]) -> str:
    def lua_str(s: str) -> str:
        return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'

    def bools(d: dict) -> str:
        return "{ " + ", ".join(f"[{int(k)}] = {'true' if on else 'false'}" for k, on in sorted(d.items())) + " }"

    out = ["return {"]
    for c in cases:
        parts = [f"n = {c['n']}", f"map = {lua_str(c['map'])}", f"label = {lua_str(c['label'])}",
                 f"events = {bools(c['events'])}", f"flags = {bools(c['flags'])}",
                 "scenes = { " + ", ".join(f"[{lua_str(m)}] = {v}" for m, v in sorted(c["scenes"].items())) + " }",
                 "mapscenes = { " + ", ".join(f"{{ group = {ms['group']}, map = {ms['map']}, value = {ms['value']} }}"
                                              for ms in c["mapscenes"]) + " }",
                 "mem = { " + ", ".join(f"[{int(a)}] = {v}" for a, v in sorted(c["mem"].items())) + " }"]
        if c.get("hour") is not None:
            parts.append(f"hour = {c['hour']}")
        if c.get("day") is not None:
            parts.append(f"day = {c['day']}")
        if c.get("decorations"):
            parts.append("decorations = { " + ", ".join(f"{slot} = {int(v)}" for slot, v in sorted(c["decorations"].items())) + " }")
        out.append("  { " + ", ".join(parts) + " },")
    out.append("}")
    return "\n".join(out) + "\n"


# ---- the live sweep ------------------------------------------------------------------------------------------------

async def _sweep_client(run, timeout: float):
    from fake_minecraft import FakeMinecraft
    import asyncio
    import time as _time

    if run.wait_marker("KB_SWEEP_READY", 600) is None:
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


def _run_loop(scene: str, timeout: float, maps: str, out: Path, extra: dict | None = None):
    import asyncio

    env = {"KB_SCENE": scene, "KB_REPORT_DIR": str(REPORTS.resolve()), "KB_DRIVER_SECONDS": str(int(timeout)),
           "KB_DATASET_MEMO": "30", "KB_TEST_PORT": str(SWEEP_PORT), "KB_MAPS": maps}
    env.update(extra or {})
    with launch("kb_g2story.lua", game=GAME, extra_env=env, echo=bool(os.environ.get("KB_ECHO"))) as run:
        try:
            received, answered = asyncio.run(_sweep_client(run, timeout))
        except RuntimeError:
            result = run.wait(60)
            print("RUN", json.dumps(result.summary(), sort_keys=True))
            raise
        result = run.wait(180)
    print("RUN", json.dumps(result.summary(), sort_keys=True))
    print("ANSWERED", dict(answered))
    unknown = [line.split("] ", 2)[-1] for line in result.lines if "story.unknown_block" in line]
    return received, json.loads(out.read_text(encoding="utf-8")), result, unknown


def run_sweep(timeout: float, maps: str = "") -> int:
    scan = json.loads(SCAN.read_text(encoding="utf-8"))
    cases, unsolved = make_cases(scan)
    CASES.write_text(lua_cases(cases), encoding="utf-8")
    print(f"CASES {len(cases)} on {len({c['map'] for c in cases})} maps; unsolved rows {sum(unsolved.values())}")
    received, sweep, result, unknown = _run_loop("sweep", timeout, maps, SWEEP, {"KB_CASES": str(CASES.resolve())})
    analysis = analyse(received, sweep)
    analysis["unknown_block_lines"] = unknown
    ANALYSIS.write_text(json.dumps(analysis, indent=1, sort_keys=True), encoding="utf-8")
    report(analysis)
    return 0 if result.returncode == 0 else 1


def run_objects(timeout: float, maps: str = "") -> int:
    received, sweep, result, unknown = _run_loop("objects", timeout, maps, OBJECTS, {"KB_SKIP": ",".join(SKIP_PREFIX)})
    analysis = analyse(received, sweep)
    analysis["unknown_block_lines"] = unknown
    OBJECTS_ANALYSIS.write_text(json.dumps(analysis, indent=1, sort_keys=True), encoding="utf-8")
    report_objects(analysis)
    return 0 if result.returncode == 0 else 1


def visits_of(received: list[dict]) -> list[dict]:
    """The hidden player's map visits as the client saw them (engine map ids), each with the blocks it was told (the
    snapshot's, then story.blocks; the last word per block), the NPCs it holds (npc.set, then npc.add/npc.remove) and
    the objects the story toggles hide on that map when it ends."""
    visits: list[dict] = []
    cur = None
    toggles: dict[str, dict[str, bool]] = defaultdict(dict)

    def bare(map_id):
        return map_id[len(PREFIX):] if isinstance(map_id, str) and map_id.startswith(PREFIX) else map_id

    def close():
        if visits:
            visits[-1]["hidden"] = sorted(o for o, v in toggles[PREFIX + visits[-1]["map"]].items() if v is False)

    def arrive(map_id, index):
        nonlocal cur
        map_id = bare(map_id)
        if map_id and map_id != cur:
            close()
            cur = map_id
            visits.append({"map": map_id, "index": index, "blocks": {}, "messages": 0, "npcs": None})

    for i, m in enumerate(received):
        kind, data = m["type"], m.get("data") or {}
        if kind == "snapshot":
            toggles.clear()
            for map_id, objs in (data.get("toggles") or {}).items():
                for obj, vis in (objs or {}).items():
                    toggles[map_id][obj] = vis
            arrive((data.get("player") or {}).get("map"), i)
            if visits:
                for b in data.get("blocks") or []:
                    if bare(b.get("map")) == visits[-1]["map"]:
                        visits[-1]["blocks"][(b["bx"], b["by"])] = b["block"]
        elif kind == "player.pos":
            arrive(data.get("map"), i)
        elif kind == "warp" and data.get("phase") == "arrive":
            arrive((data.get("to") or {}).get("map"), i)
        elif kind == "story.blocks":
            arrive(data.get("map"), i)
            visit = visits[-1]
            visit["messages"] += 1
            for ch in data.get("changes") or []:
                visit["blocks"][(ch["bx"], ch["by"])] = ch["block"]
        elif kind == "story.toggles":
            toggles[data.get("map")][data.get("obj")] = data.get("visible")
        elif kind == "npc.set":
            arrive(data.get("map"), i)
            visits[-1]["npcs"] = {n["id"] for n in data.get("list") or []}
        elif kind == "npc.add" and visits and visits[-1]["npcs"] is not None:
            visits[-1]["npcs"].add(data.get("id"))
        elif kind == "npc.remove" and visits and visits[-1]["npcs"] is not None:
            visits[-1]["npcs"].discard(data.get("id"))
    close()
    return visits


def analyse(received: list[dict], sweep: dict) -> dict:
    visits = visits_of(received)
    counts = Counter(m["type"] for m in received)
    used = -1
    out = []
    for rec in sweep["cases"]:
        entry = {"n": rec.get("n"), "map": rec.get("map"), "label": rec.get("label"), "ok": rec.get("ok"),
                 "err": rec.get("err"), "halted": bool(rec.get("halted"))}
        s = rec.get("settled") or {}
        a = rec.get("arrived") or {}
        entry["current"] = s.get("map")
        entry["blocks"] = len(s.get("blocks") or [])
        entry["blocksOnLoad"] = len(a.get("blocks") or [])
        if not (rec.get("ok") and s.get("map") == rec.get("map")):
            out.append(entry)
            continue
        visit = None
        for vi in range(used + 1, len(visits)):
            if visits[vi]["map"] == rec["map"]:
                visit, used = visits[vi], vi
                break
        if visit is None:
            entry["bridge"] = "no visit seen"
            out.append(entry)
            continue
        truth = {(b["bx"], b["by"]): b["block"] for b in s.get("blocks") or []}
        was = {(b["bx"], b["by"]): b["was"] for b in (a.get("blocks") or []) + (s.get("blocks") or [])}
        held = {k: v for k, v in visit["blocks"].items() if v != was.get(k)}
        missing = [k for k in truth if k not in held]
        wrong = [k for k in truth if k in held and held[k] != truth[k]]
        extra = [k for k in held if k not in truth]
        entry["bridge"] = "ok" if not (missing or wrong or extra) else ("missing" if not (wrong or extra) else "mismatch")
        entry["bridgeCounts"] = {"held": len(held), "missing": len(missing), "wrong": len(wrong), "extra": len(extra),
                                 "messages": visit["messages"]}
        people = set(s.get("people") or [])
        hidden_engine = set(s.get("hidden") or [])
        npcs = visit["npcs"] if visit["npcs"] is not None else set()
        hidden = set(visit.get("hidden") or [])
        entry["objects"] = {
            "people": len(people), "hidden": len(hidden_engine),
            # the client's NPC list is the engine's live people
            "npcs_vs_engine": "ok" if npcs == people else
            f"client {len(npcs)} engine {len(people)}: missing {sorted(people - npcs)[:6]} extra {sorted(npcs - people)[:6]}",
            # the toggles hide exactly the objects the engine masks now
            "toggles": "ok" if hidden == hidden_engine else
            f"hidden {len(hidden)} engine {len(hidden_engine)}: missing {sorted(hidden_engine - hidden)[:6]} "
            f"extra {sorted(hidden - hidden_engine)[:6]}",
        }
        out.append(entry)
    return {"cases": out, "messages": dict(counts), "visits": len(visits)}


def report(analysis: dict) -> None:
    cases = analysis["cases"]
    print(f"\nSWEEP: {len(cases)} cases, {analysis['visits']} visits seen by the client, messages "
          f"{sum(analysis['messages'].values())} ({analysis['messages'].get('story.blocks', 0)} story.blocks)")
    tally = Counter()
    for e in cases:
        bridge = e.get("bridge", "-")
        tally[f"bridge {bridge}"] += 1
        o = e.get("objects") or {}
        if o:
            tally["npcs ok" if o["npcs_vs_engine"] == "ok" else "npcs differ"] += 1
            tally["toggles ok" if o["toggles"] == "ok" else "toggles differ"] += 1
        flags = []
        if e.get("halted"):
            flags.append("halted")
        if e.get("current") and e["current"] != e["map"]:
            flags.append(f"left for {e['current']}")
        if not e.get("ok"):
            flags.append(f"failed {e.get('err')}")
        if bridge != "ok" or flags or (o and (o["npcs_vs_engine"] != "ok" or o["toggles"] != "ok")) or e["blocks"]:
            extra = (" " + json.dumps(e["bridgeCounts"], sort_keys=True)) if e.get("bridgeCounts") and bridge != "ok" else ""
            obj = ""
            if o and (o["npcs_vs_engine"] != "ok" or o["toggles"] != "ok"):
                obj = f"; npcs {o['npcs_vs_engine']}; toggles {o['toggles']}"
            print(f"  #{e['n']:>3} {e['map']} [{e['label']}]: blocks {e['blocks']} (on load {e['blocksOnLoad']}); "
                  f"bridge {bridge}{extra}{obj}{'; ' + ', '.join(flags) if flags else ''}")
    print("TALLY", json.dumps(dict(tally), sort_keys=True))
    if analysis.get("unknown_block_lines"):
        print(f"story.unknown_block lines: {len(analysis['unknown_block_lines'])}")


def report_objects(analysis: dict) -> None:
    cases = analysis["cases"]
    with_obj = [e for e in cases if e.get("objects")]
    ok_npcs = sum(1 for e in with_obj if e["objects"]["npcs_vs_engine"] == "ok")
    ok_tog = sum(1 for e in with_obj if e["objects"]["toggles"] == "ok")
    print(f"\nOBJECTS: {len(cases)} cases on {len({e['map'] for e in cases})} maps; compared {len(with_obj)}; "
          f"client NPCs = engine's people in {ok_npcs}; toggles = engine's masks in {ok_tog}")
    for e in cases:
        o = e.get("objects")
        if not o:
            print(f"  #{e['n']} {e['map']} [{e['label']}]: not compared ({e.get('err') or e.get('bridge') or 'left the map'}"
                  f"{', halted' if e.get('halted') else ''})")
        elif o["npcs_vs_engine"] != "ok" or o["toggles"] != "ok":
            print(f"  #{e['n']} {e['map']} [{e['label']}]: npcs {o['npcs_vs_engine']}; toggles {o['toggles']}"
                  f"{'; halted' if e.get('halted') else ''}")


# ---- the export's swap sites against the scan and the sweep --------------------------------------------------------

async def _fetch_export(port, want, timeout):
    """export.index, then export.get for every entry want(entry) accepts: {(kind, id): data}."""
    import asyncio
    from fake_minecraft import FakeMinecraft

    builder = FakeMinecraft(port=port, role="builder", ping_ms=1000)
    await builder.connect(attempts=60)
    ok = (await builder.hello())["data"]
    assert (ok["game"], ok["family"]) == (GAME, "gen2"), ok
    await builder.expect("snapshot", timeout=15)
    reply = await builder.request("export.index", {}, reply_type="export.index", timeout=timeout)
    entries = [e for e in reply["data"]["resources"] if want(e)]
    asked = {}
    for e in entries:
        message = await builder.send("export.get", {"kind": e["kind"], "id": e["id"]})
        asked[message["seq"]] = e
    pages = {}
    while len(pages) < len(asked):
        message = await builder.next(timeout=180)
        if message is None:
            raise RuntimeError("the connection ended during the fetch")
        if message.get("re") in asked and message["type"] == "export.data":
            e = asked[message["re"]]
            pages[(e["kind"], e["id"])] = message["data"]["data"]
    await builder.close()
    tester = FakeMinecraft(port=port, role="test", ping_ms=1000)
    await tester.connect(attempts=20)
    await tester.hello()
    await tester.expect("snapshot", timeout=15)
    await tester.send("debug.quit", {"code": 0})
    await asyncio.sleep(0.5)
    await tester.close()
    return pages


def sweep_cases() -> list[dict]:
    return json.loads(SWEEP.read_text(encoding="utf-8"))["cases"] if SWEEP.is_file() else []


def check_sites(scan: dict, cases: list[dict], sites: list[dict]) -> dict:
    """Every block a script can write is a swap site's state (the static scan: changeblock rows from any entry point),
    and every block the sweep saw changed is one too; a block with several states keeps only its first site in the
    builder (G2Cells: the first site wins), so a live block that is neither of that site's two is shadowed. Counts and
    map ids only."""
    by_block: dict[tuple, list] = defaultdict(list)
    for s in sites:
        by_block[(s["map"], s["bx"], s["by"])].append(s)
    for k in by_block:
        by_block[k].sort(key=lambda s: (s["kind"], s["before"], s["after"]))
    counts = Counter()
    missing_static: Counter = Counter()
    missing_live: Counter = Counter()
    shadowed: Counter = Counter()
    multi = Counter()
    for (m, _, _), lst in by_block.items():
        if len({s["after"] for s in lst}) > 1:
            multi[m] += 1
    for mid, m in scan["maps"].items():
        for w in m["world"]:
            if w["op"] != "changeblock":
                continue
            a = w.get("args") or {}
            if a.get("x") is None or a.get("y") is None or a.get("block") is None:
                counts["static_bad_row"] += 1
                continue
            if a.get("was") == a["block"]:
                counts["static_noop"] += 1
                continue
            lst = by_block.get((PREFIX + mid, a["x"] // 2, a["y"] // 2), [])
            if any(s["after"] == a["block"] for s in lst):
                counts["static_site"] += 1
            else:
                counts["static_missing"] += 1
                missing_static[mid] += 1
    seen = set()
    for c in cases:
        for rec in (c.get("arrived"), c.get("settled")):
            if not rec or rec.get("map") != c.get("map"):
                continue
            for b in rec.get("blocks") or []:
                key = (rec["map"], b["bx"], b["by"], b["block"])
                if key in seen:
                    continue
                seen.add(key)
                counts["live_changes"] += 1
                lst = by_block.get((PREFIX + rec["map"], b["bx"], b["by"]), [])
                if not any(s["after"] == b["block"] or s["before"] == b["block"] for s in lst):
                    counts["live_missing"] += 1
                    missing_live[rec["map"]] += 1
                elif b["block"] not in (lst[0]["before"], lst[0]["after"]):
                    counts["live_shadowed"] += 1
                    shadowed[rec["map"]] += 1
                else:
                    counts["live_site"] += 1
    # the other way round: every script site the bridge exports is a write the scan found (the scan's walk is capped
    # per script; the bridge's walk follows every reachable row without conditions)
    found = set()
    for mid, m in scan["maps"].items():
        for w in m["world"]:
            a = w.get("args") or {}
            if w["op"] == "changeblock" and None not in (a.get("x"), a.get("y"), a.get("block")):
                found.add((PREFIX + mid, a["x"] // 2, a["y"] // 2, a["block"]))
    export_only: Counter = Counter()
    for st in sites:
        if st["kind"] == "script" and st["map"] != PREFIX + "PLAYERS_HOUSE_2F":
            if (st["map"], st["bx"], st["by"], st["after"]) in found:
                counts["export_script_found"] += 1
            else:
                counts["export_script_not_found"] += 1
                export_only[st["map"]] += 1
    counts["sites"] = len(sites)
    counts["blocks_with_sites"] = len(by_block)
    counts["blocks_with_several_states"] = sum(multi.values())
    return {"counts": dict(counts), "missing_static_by_map": dict(missing_static.most_common()),
            "missing_live_by_map": dict(missing_live.most_common()), "shadowed_by_map": dict(shadowed.most_common()),
            "several_states_by_map": dict(multi.most_common()), "export_only_by_map": dict(export_only.most_common())}


def run_sites(timeout: float) -> int:
    import asyncio

    scan = json.loads(SCAN.read_text(encoding="utf-8"))
    env = {"KB_TEST_PORT": str(SITES_PORT), "KB_SCENE": "field", "KB_DRIVER_EXTRA": str(int(timeout) + 300),
           "KB_DATASET_MEMO": "30"}
    with launch("kb_crystal.lua", game=GAME, extra_env=env, echo=False) as run:
        if run.wait_marker("KB_SERVING", timeout=300) is None:
            print("RUN", json.dumps(run.result.summary(), sort_keys=True))
            return 1
        pages = asyncio.run(asyncio.wait_for(_fetch_export(
            SITES_PORT, lambda e: e["kind"] == "swaps", timeout), timeout + 600))
        result = run.wait(180)
    print("RUN", json.dumps(result.summary(), sort_keys=True))
    sites = (pages.get(("swaps", "all")) or {}).get("sites") or []
    out = check_sites(scan, sweep_cases(), sites)
    out["export"] = {"sites": len(sites), "kinds": dict(Counter(s["kind"] for s in sites)),
                     "maps": len({s["map"] for s in sites})}
    out["bridge_lines"] = [line.split("] ", 2)[-1] for line in result.lines if "story.sites" in line]
    SITES_REPORT.write_text(json.dumps(out, indent=1, sort_keys=True), encoding="utf-8")
    print("SITES", json.dumps(out, sort_keys=True))
    c = out["counts"]
    return 0 if result.returncode == 0 and c.get("static_missing", 0) == 0 and c.get("live_missing", 0) == 0 else 1


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_scan = sub.add_parser("scan")
    p_scan.add_argument("--timeout", type=float, default=900.0)
    sub.add_parser("summary")
    p_sweep = sub.add_parser("sweep")
    p_sweep.add_argument("--timeout", type=float, default=5400.0)
    p_sweep.add_argument("--maps", default="", help="only these maps (comma-separated, engine ids)")
    p_obj = sub.add_parser("objects")
    p_obj.add_argument("--timeout", type=float, default=7200.0)
    p_obj.add_argument("--maps", default="", help="only these maps (comma-separated, engine ids)")
    sub.add_parser("cases")
    sub.add_parser("report")
    p_sites = sub.add_parser("sites", help="the export's swap sites against the scan and the sweep")
    p_sites.add_argument("--timeout", type=float, default=900.0)
    args = parser.parse_args(argv)
    if args.cmd == "scan":
        code = run_scan(args.timeout)
        return code if code != 0 else summary(SCAN)
    if args.cmd == "sweep":
        return run_sweep(args.timeout, args.maps)
    if args.cmd == "objects":
        return run_objects(args.timeout, args.maps)
    if args.cmd == "cases":
        scan = json.loads(SCAN.read_text(encoding="utf-8"))
        cases, unsolved = make_cases(scan)
        print(f"CASES {len(cases)} on {len({c['map'] for c in cases})} maps; unsolved rows {sum(unsolved.values())} "
              f"on {len(unsolved)} maps")
        for c in cases:
            extra = "".join(f" {k}={c[k]}" for k in ("hour", "day") if c.get(k) is not None)
            print(f"  #{c['n']} {c['map']}: {c['label']} events={len(c['events'])} flags={len(c['flags'])} "
                  f"scenes={len(c['scenes'])} mapscenes={len(c['mapscenes'])} mem={len(c['mem'])}{extra}")
        for mid, n in unsolved.most_common(20):
            print(f"  unsolved {mid}: {n}")
        return 0
    if args.cmd == "report":
        report(json.loads(ANALYSIS.read_text(encoding="utf-8")))
        return 0
    if args.cmd == "sites":
        return run_sites(args.timeout)
    return summary(SCAN)


if __name__ == "__main__":
    sys.exit(main())
