"""Blue's story check on the real engine (docs/specs/STORY_EVENTS_PLAN.md §6): what Blue's story changes in the
world, and whether each change reaches Minecraft.

    python tools/e2e/g1_story_check.py inventory   # launch the test instance: maps, flags, objects, swap sites
    python tools/e2e/g1_story_check.py cases       # the live cases the inventory gives
    python tools/e2e/g1_story_check.py sweep [--maps A,B]    # the live check, a stand-in client connected
    python tools/e2e/g1_story_check.py objects [--maps A,B]  # objects' toggles hidden and shown, map by map
    python tools/e2e/g1_story_check.py report      # print the last sweep's analysis again

Gen 1's story scripts are the engine's own Lua (data/scripts/**), not data rows, so there is no script walk: the
block writers are the ones gen1-bridge/export/kb_swapsites.lua's audit lists (the curated sites and the field tables'
card-key doors, poster and trash-can door), and the sweep proves them on the real game. The cases: every site's
gating flag set and cleared on its map; every map once in the new game's state and once with every story flag set
(any block change a flag gates on a map's load shows there); and the S.S. Anne, the poster and the Vermilion Gym door
through their own flags. Every changed block must be a swap site's state (or the engine's own record of a cut).

The driver is drivers/kb_g1story.lua. Its files in local/reports/ (g1_story_inventory.json, g1_story_sweep.json,
g1_story_objects.json) are ROM-derived (map blocks and block numbers stay in local/); this script prints only map
symbols, flag names and counts, and writes its analyses (the same) beside them.
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
import g2_story_check as g2  # noqa: E402
from run_gen1recomp import REPORTS, launch  # noqa: E402

GAME = "blue"
INVENTORY = REPORTS / "g1_story_inventory.json"
SWEEP = REPORTS / "g1_story_sweep.json"
CASES = REPORTS / "g1_story_cases.lua"
ANALYSIS = REPORTS / "g1_story_sweep_analysis.json"
OBJECTS = REPORTS / "g1_story_objects.json"
OBJECTS_ANALYSIS = REPORTS / "g1_story_objects_analysis.json"
SWEEP_PORT = 27228
# places the sweep leaves out: the link rooms (and the Union Room, off by decision U3)
SKIP_PREFIX = ("TRADE_CENTER", "COLOSSEUM", "UNION_ROOM")
# a flag every story flag case leaves alone: the ones that change how the game itself runs, not the world
NOT_STORY = ("EVENT_IN_SAFARI_ZONE",)


def run_inventory(timeout: float) -> int:
    REPORTS.mkdir(parents=True, exist_ok=True)
    env = {"KB_SCENE": "inventory", "KB_REPORT_DIR": str(REPORTS.resolve()), "KB_DATASET_MEMO": "30",
           "KB_TEST_PORT": str(SWEEP_PORT)}
    with launch("kb_g1story.lua", game=GAME, extra_env=env) as run:
        result = run.wait(timeout)
    print("RUN", json.dumps(result.summary(), sort_keys=True))
    if result.returncode != 0 or not result.marker("KB_INVENTORY"):
        return 1
    inv = json.loads(INVENTORY.read_text(encoding="utf-8"))
    sites = inv.get("sites") or []
    print(f"INVENTORY maps={len(inv['maps'])} flags={len(inv.get('flags') or [])} sites={len(sites)} "
          f"by kind {dict(Counter(s['kind'] for s in sites))} dark={len(inv.get('darkMaps') or [])}")
    unsourced = Counter(s["kind"] for s in sites if not s.get("source"))
    print("sites without a gating flag:", dict(unsourced))
    return 0


def make_cases(inv: dict) -> list[dict]:
    """The sweep's cases, map by map: the new game's state; each site's gating flag set, then cleared; every story flag
    set. Each case: {map, label, flags {name: bool}}."""
    flags = [f for f in inv.get("flags") or [] if f not in NOT_STORY]
    by_map: dict[str, list] = defaultdict(list)
    for s in inv.get("sites") or []:
        if s.get("source"):
            by_map[s["map"]].append(s["source"])
    cases = []
    for mid in sorted(inv["maps"]):
        if mid.startswith(SKIP_PREFIX):
            continue
        states = {json.dumps({}, sort_keys=True): ({}, "new game")}
        for source in sorted(set(by_map.get(mid, []))):
            for on in (True, False):
                st = {source: on}
                states.setdefault(json.dumps(st, sort_keys=True), (st, f"{source} {'set' if on else 'clear'}"))
        if flags:
            every = {f: True for f in flags}
            states.setdefault("every", (every, "every story flag"))
        for st, label in states.values():
            cases.append({"n": len(cases) + 1, "map": mid, "label": label, "flags": st})
    return cases


def lua_cases(cases: list[dict]) -> str:
    def lua_str(s: str) -> str:
        return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'

    out = ["return {"]
    for c in cases:
        flags = c["flags"]
        if len(flags) > 8 and all(flags.values()):
            # every story flag: the driver sets the whole list it gave the inventory
            fl = "every = true"
        else:
            fl = "flags = { " + ", ".join(f"[{lua_str(k)}] = {'true' if v else 'false'}" for k, v in sorted(flags.items())) + " }"
        out.append(f"  {{ n = {c['n']}, map = {lua_str(c['map'])}, label = {lua_str(c['label'])}, {fl} }},")
    out.append("}")
    return "\n".join(out) + "\n"


def _run_loop(scene: str, timeout: float, maps: str, out: Path, extra: dict | None = None):
    import asyncio

    env = {"KB_SCENE": scene, "KB_REPORT_DIR": str(REPORTS.resolve()), "KB_DRIVER_SECONDS": str(int(timeout)),
           "KB_DATASET_MEMO": "30", "KB_TEST_PORT": str(SWEEP_PORT), "KB_MAPS": maps}
    env.update(extra or {})
    with launch("kb_g1story.lua", game=GAME, extra_env=env, echo=bool(os.environ.get("KB_ECHO"))) as run:
        try:
            received, answered = asyncio.run(g2._sweep_client(run, timeout, SWEEP_PORT))
        except RuntimeError:
            result = run.wait(60)
            print("RUN", json.dumps(result.summary(), sort_keys=True))
            raise
        result = run.wait(180)
    print("RUN", json.dumps(result.summary(), sort_keys=True))
    print("ANSWERED", dict(answered))
    unknown = [line.split("] ", 2)[-1] for line in result.lines if "story.unknown_block" in line]
    return received, json.loads(out.read_text(encoding="utf-8")), result, unknown


def objects_vs_client(sweep: dict, received: list[dict]) -> dict:
    """Gen 1's objects: the client's effective visibility of each object of the visit's map (its story toggle, else its
    record's hidden default) against the engine's (OverworldController.objectVisible), and its NPC list against the
    engine's live people, per case."""
    visits = g2.visits_of(received)
    toggles: dict[str, dict] = defaultdict(dict)
    marks = []  # (message index, map, obj, visible) in order, to replay the toggles up to each visit's end
    for i, m in enumerate(received):
        if m["type"] == "snapshot":
            marks.append((i, None, None, m.get("data") or {}))
        elif m["type"] == "story.toggles":
            d = m.get("data") or {}
            marks.append((i, d.get("map"), d.get("obj"), d.get("visible")))
    out = {}
    used = -1
    for rec in sweep["cases"]:
        s = rec.get("settled") or {}
        if not (rec.get("ok") and s.get("map") == rec.get("map")):
            if (rec.get("arrived") or {}).get("map") == rec.get("map"):
                # arrived, then sent on by the game: its visit is used up all the same
                used = next((k for k in range(used + 1, len(visits)) if visits[k]["map"] == rec["map"]), used)
            continue
        vi = next((k for k in range(used + 1, len(visits)) if visits[k]["map"] == rec["map"]), None)
        if vi is None:
            continue
        used = vi
        end = visits[vi + 1]["index"] if vi + 1 < len(visits) else len(received)
        toggles.clear()
        for i, mp, obj, vis in marks:
            if i >= end:
                break
            if mp is None:
                toggles.clear()
                for map_id, objs in (vis.get("toggles") or {}).items():
                    toggles[map_id].update(objs or {})
            else:
                toggles[mp][obj] = vis
        objs = s.get("objects") or []
        wrong = [o["name"] for o in objs if o.get("name")
                 and toggles[rec["map"]].get(o["name"], not o.get("hidden")) != o.get("visible")]
        people = set(s.get("people") or [])
        npcs = visits[vi]["npcs"] if visits[vi]["npcs"] is not None else set()
        out[rec["n"]] = {"objects": len(objs), "visibility_wrong": wrong[:8],
                         "npcs": "ok" if npcs == people else f"client {len(npcs)} engine {len(people)}"}
    return out


def check_sites(inv: dict, sweep: dict) -> dict:
    """Every block the sweep saw changed is a swap site's state (its before or after) or the engine's own cut record;
    counts and map ids only."""
    sites: dict[tuple, list] = defaultdict(list)
    for st in inv.get("sites") or []:
        sites[(st["map"], st["bx"], st["by"])].append(st)
    counts = Counter()
    missing = Counter()
    reached = set()
    seen = set()
    for c in sweep["cases"]:
        for rec in (c.get("arrived"), c.get("settled")):
            if not rec or rec.get("map") != c.get("map"):
                continue
            for b in rec.get("blocks") or []:
                key = (rec["map"], b["bx"], b["by"], b["block"])
                if key in seen:
                    continue
                seen.add(key)
                counts["live_changes"] += 1
                lst = sites.get((rec["map"], b["bx"], b["by"]), [])
                hit = [st for st in lst if b["block"] in (st["before"], st["after"])]
                if hit:
                    counts["live_site"] += 1
                    reached.update((st["map"], st["bx"], st["by"], st["kind"]) for st in hit)
                else:
                    counts["live_missing"] += 1
                    missing[rec["map"]] += 1
    counts["sites"] = sum(len(v) for v in sites.values())
    counts["sites_reached"] = len(reached)
    by_kind = Counter(st["kind"] for v in sites.values() for st in v)
    reached_kind = Counter(k[3] for k in reached)
    return {"counts": dict(counts), "missing_by_map": dict(missing.most_common()), "sites_by_kind": dict(by_kind),
            "reached_by_kind": dict(reached_kind)}


def run_sweep(timeout: float, maps: str = "") -> int:
    inv = json.loads(INVENTORY.read_text(encoding="utf-8"))
    cases = make_cases(inv)
    CASES.write_text(lua_cases(cases), encoding="utf-8")
    print(f"CASES {len(cases)} on {len({c['map'] for c in cases})} maps")
    received, sweep, result, unknown = _run_loop("sweep", timeout, maps, SWEEP, {"KB_CASES": str(CASES.resolve())})
    analysis = g2.analyse(received, sweep)
    analysis["unknown_block_lines"] = unknown
    analysis["objects_gen1"] = objects_vs_client(sweep, received)
    analysis["sites"] = check_sites(inv, sweep)
    ANALYSIS.write_text(json.dumps(analysis, indent=1, sort_keys=True, default=str), encoding="utf-8")
    report(analysis)
    return 0 if result.returncode == 0 else 1


def run_objects(timeout: float, maps: str = "") -> int:
    received, sweep, result, unknown = _run_loop("objects", timeout, maps, OBJECTS, {"KB_SKIP": ",".join(SKIP_PREFIX)})
    analysis = g2.analyse(received, sweep)
    analysis["objects_gen1"] = objects_vs_client(sweep, received)
    OBJECTS_ANALYSIS.write_text(json.dumps(analysis, indent=1, sort_keys=True, default=str), encoding="utf-8")
    report_objects(analysis)
    return 0 if result.returncode == 0 else 1


def report(analysis: dict) -> None:
    cases = analysis["cases"]
    print(f"\nSWEEP: {len(cases)} cases, {analysis['visits']} visits seen by the client, messages "
          f"{sum(analysis['messages'].values())} ({analysis['messages'].get('story.blocks', 0)} story.blocks)")
    tally = Counter()
    objs = analysis.get("objects_gen1") or {}
    for e in cases:
        bridge = e.get("bridge", "-")
        tally[f"bridge {bridge}"] += 1
        o = objs.get(e["n"]) or objs.get(str(e["n"]))
        if o:
            tally["visibility ok" if not o["visibility_wrong"] else "visibility differs"] += 1
            tally["npcs ok" if o["npcs"] == "ok" else "npcs differ"] += 1
        flags = []
        if e.get("halted"):
            flags.append("halted")
        if e.get("current") and e["current"] != e["map"]:
            flags.append(f"left for {e['current']}")
        if not e.get("ok"):
            flags.append(f"failed {e.get('err')}")
        bad_obj = o and (o["visibility_wrong"] or o["npcs"] != "ok")
        if bridge != "ok" or flags or bad_obj or e["blocks"]:
            extra = (" " + json.dumps(e["bridgeCounts"], sort_keys=True)) if e.get("bridgeCounts") and bridge != "ok" else ""
            ob = f"; objects {o['visibility_wrong']} npcs {o['npcs']}" if bad_obj else ""
            print(f"  #{e['n']:>3} {e['map']} [{e['label']}]: blocks {e['blocks']} (on load {e['blocksOnLoad']}); "
                  f"bridge {bridge}{extra}{ob}{'; ' + ', '.join(flags) if flags else ''}")
    print("TALLY", json.dumps(dict(tally), sort_keys=True))
    if analysis.get("sites"):
        print("SITES", json.dumps(analysis["sites"], sort_keys=True))
    if analysis.get("unknown_block_lines"):
        print(f"story.unknown_block lines: {len(analysis['unknown_block_lines'])}")


def report_objects(analysis: dict) -> None:
    cases = analysis["cases"]
    objs = analysis.get("objects_gen1") or {}
    compared = [e for e in cases if (objs.get(e["n"]) or objs.get(str(e["n"])))]
    ok_vis = sum(1 for e in compared if not (objs.get(e["n"]) or objs.get(str(e["n"])))["visibility_wrong"])
    ok_npcs = sum(1 for e in compared if (objs.get(e["n"]) or objs.get(str(e["n"])))["npcs"] == "ok")
    print(f"\nOBJECTS: {len(cases)} cases on {len({e['map'] for e in cases})} maps; compared {len(compared)}; "
          f"client visibility = engine's in {ok_vis}; client NPCs = engine's people in {ok_npcs}")
    for e in cases:
        o = objs.get(e["n"]) or objs.get(str(e["n"]))
        if not o:
            print(f"  #{e['n']} {e['map']} [{e['label']}]: not compared ({e.get('err') or e.get('bridge') or 'left the map'})")
        elif o["visibility_wrong"] or o["npcs"] != "ok":
            print(f"  #{e['n']} {e['map']} [{e['label']}]: visibility differs for {o['visibility_wrong']}; npcs {o['npcs']}")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_inv = sub.add_parser("inventory")
    p_inv.add_argument("--timeout", type=float, default=900.0)
    sub.add_parser("cases")
    p_sweep = sub.add_parser("sweep")
    p_sweep.add_argument("--timeout", type=float, default=7200.0)
    p_sweep.add_argument("--maps", default="", help="only these maps (comma-separated)")
    p_obj = sub.add_parser("objects")
    p_obj.add_argument("--timeout", type=float, default=7200.0)
    p_obj.add_argument("--maps", default="", help="only these maps (comma-separated)")
    sub.add_parser("report")
    args = parser.parse_args(argv)
    if args.cmd == "inventory":
        return run_inventory(args.timeout)
    if args.cmd == "cases":
        cases = make_cases(json.loads(INVENTORY.read_text(encoding="utf-8")))
        by_label = Counter(c["label"].split(" ")[-1] if c["label"].startswith("EVENT_") else c["label"] for c in cases)
        print(f"CASES {len(cases)} on {len({c['map'] for c in cases})} maps: {dict(by_label)}")
        for c in cases:
            if c["label"] not in ("new game", "every story flag"):
                print(f"  #{c['n']} {c['map']}: {c['label']}")
        return 0
    if args.cmd == "sweep":
        return run_sweep(args.timeout, args.maps)
    if args.cmd == "objects":
        return run_objects(args.timeout, args.maps)
    if args.cmd == "report":
        report(json.loads(ANALYSIS.read_text(encoding="utf-8")))
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
