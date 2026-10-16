"""Crystal's story spots in the real client (docs/specs/STORY_EVENTS_PLAN.md §5): the hidden Crystal is put into a
story state the live sweep found (tools/e2e/g2_story_check.py), the real dev client follows it there, and the client
walks into the cell the story changed: through the Elite Four's opened exits, down the Mahogany Mart's stairs, through
the Radio Tower's and the Rocket hideout's opened doors and the Goldenrod underground's opened shutters; against the
Ruins of Alph's closed walls and Route 19's rocks; Dark Cave's darkness, unlit and lit by Flash, is read back; and the
bedroom's decorations (§5.5): bare, then a carpet, a bed and a plant, each cell drawn and colliding as the game has it.

    xvfb-run -a -s "-screen 0 1280x720x24" python tools/e2e/story_spots_g2.py --world ThreeRegionsE2E \\
        [--record /workspace/videos/kanto-bridge-crystal-story-spots.mp4] [--only e4_will_exit,route19_rocks]

1. A fresh data home on the pod's container disk (the test install's Crystal import and gen1-bridge linked by name),
   so the game starts with no save of its own.
2. A copy of --world (Johto built at slot 2) to play in.
3. Crystal with drivers/kb_g2spots.lua (driver mode's new game, below New Bark Town's first door; it runs the go
   commands this script writes); the dev client (RemotePilot) with Crystal's link only, following it.
4. Each spot: the story state (event flags, engine flags, scenes, script memory and clock of a sweep case, and the
   spot's own extras: a scene past a cutscene) and a warp onto a cell up to four steps from the changed one (planned
   on the export's blocks and collision: local/minecraft/kantobridge/export/crystal/), then the pilot's steps into
   it: `enter` passes when the hidden player stands on it (or the map changed: a door or stairs), `refuse` when the
   step is refused and every block of the cell collides for the player in Minecraft (the pilot's `story` answer);
   `dark` and `lit` compare the client's light with the game's; `decor` asks the pilot about every cell any decoration
   changes: each `cell` story cell must show the live block (`shows`) and collide as the live block's cell does.

The changed blocks come from the sweep's truth files in local/reports/ (ROM-derived; they stay on the pod). Output:
spot names, map symbols, cells, counts and booleans only, never the game's text. A video, when asked for, shows the
user's own game: it stays on the pod's volume and the agent never opens it.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import shutil
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

import g2_story_check as gsc  # noqa: E402
from run_gen1recomp import launch  # noqa: E402
from story_spots import approach, stream, wait_followed, wait_spot  # noqa: E402
from story_video import EXPORT, Pilot, Stuck, make_home, say  # noqa: E402

GRADLE_DIR = REPO / "fabric-bridge"
SAVES = REPO / "local" / "minecraft" / "saves"
PILOT_DIR = REPO / "local" / "reports" / "spots_g2"
PORT = 27242
PREFIX = gsc.PREFIX
TOWN = "NEW_BARK_TOWN"


@dataclass
class Spot:
    name: str
    map: str                        # the engine's map id (the wire's has the G2_ prefix)
    check: str                      # enter, refuse, dark, lit, decor
    label: str = "new game"         # the sweep case's label (its first world command), "new game" for the baseline
    cls: str = ""                   # enter/refuse: the walkability change of the cell to step into, e.g. "solid>walk"
    scenes: dict = field(default_factory=dict)  # extra map scenes on top of the case's (past a map's cutscene)
    flash: bool = False             # lit: Flash used on the map once the field stands
    last: bool = False              # the last case whose label starts with `label` (a slot's last decoration)


# The Elite Four's rooms: scene 1 is past the door that locks behind the player (the scene script that walks them in).
SPOTS = [
    Spot("e4_will_exit", "WILLS_ROOM", "enter", "changeblock (MAPCALLBACK_TILES)", "solid>walk", {"WILLS_ROOM": 1}),
    Spot("e4_koga_exit", "KOGAS_ROOM", "enter", "changeblock (MAPCALLBACK_TILES)", "solid>walk", {"KOGAS_ROOM": 1}),
    Spot("e4_bruno_exit", "BRUNOS_ROOM", "enter", "changeblock (MAPCALLBACK_TILES)", "solid>walk", {"BRUNOS_ROOM": 1}),
    Spot("e4_karen_exit", "KARENS_ROOM", "enter", "changeblock (MAPCALLBACK_TILES)", "solid>walk", {"KARENS_ROOM": 1}),
    Spot("e4_lance_exit", "LANCES_ROOM", "enter", "changeblock (MAPCALLBACK_TILES)", "solid>walk", {"LANCES_ROOM": 1}),
    # the stairs appear on floor that was walkable: the change is the art and the warp under it
    Spot("mahogany_mart_stairs", "MAHOGANY_MART_1F", "enter", "changeblock (MAPCALLBACK_TILES)", "walk>walk"),
    Spot("radio_tower_shutter", "RADIO_TOWER_3F", "enter", "changeblock (MAPCALLBACK_TILES)", "solid>walk"),
    Spot("rocket_b2f_door", "TEAM_ROCKET_BASE_B2F", "enter", "changeblock (MAPCALLBACK_TILES)", "solid>walk"),
    Spot("rocket_b3f_door", "TEAM_ROCKET_BASE_B3F", "enter", "changeblock (MAPCALLBACK_TILES)", "solid>walk"),
    Spot("underground_shutter", "GOLDENROD_UNDERGROUND_SWITCH_ROOM_ENTRANCES", "enter", "changeblock (MAPCALLBACK_TILES)",
         "solid>walk"),
    Spot("ruins_kabuto_wall", "RUINS_OF_ALPH_KABUTO_CHAMBER", "refuse", "new game", "walk>solid"),
    Spot("route19_rocks", "ROUTE_19", "refuse", "new game", "walk>solid"),
    Spot("dark_cave_dark", "DARK_CAVE_VIOLET_ENTRANCE", "dark"),
    Spot("dark_cave_lit", "DARK_CAVE_VIOLET_ENTRANCE", "lit", flash=True),
    # §5.5: the bedroom's decorations (a poster hangs on a wall: no story cell stands there, so none is asked about)
    Spot("bedroom_bare", gsc.BEDROOM, "decor", "decorations bare"),
    Spot("bedroom_carpet", gsc.BEDROOM, "decor", "decoration carpet", last=True),
    Spot("bedroom_bed", gsc.BEDROOM, "decor", "decoration bed", last=True),
    Spot("bedroom_plant", gsc.BEDROOM, "decor", "decoration plant", last=True),
]


def load_truth():
    """The sweep's cases by (map, label), in case order (several cases can share a label): their state and the
    blocks they changed."""
    scan = json.loads(gsc.SCAN.read_text(encoding="utf-8"))
    cases = {c["n"]: c for c in gsc.make_cases(scan)[0]}
    truth = {}
    if gsc.SWEEP.is_file():
        for rec in json.loads(gsc.SWEEP.read_text(encoding="utf-8"))["cases"]:
            case = cases.get(rec.get("n"))
            if case and rec.get("ok") and rec.get("map") == case["map"]:
                truth.setdefault((case["map"], case["label"]), []).append((case, rec))
    return scan, truth


def entries_for(truth: dict, spot: Spot) -> list:
    """The sweep cases of a spot: its label's, or with `last` the last case whose label starts with it."""
    if not spot.last:
        return truth.get((spot.map, spot.label)) or []
    found = sorted((pair for (m, label), pairs in truth.items() if m == spot.map and label.startswith(spot.label + " ")
                    for pair in pairs), key=lambda pair: pair[0]["n"])
    return found[-1:]


def decoration_cells(truth: dict) -> set:
    """Every cell any of the bedroom's decoration cases changes."""
    cells = set()
    for (m, label), pairs in truth.items():
        if m == gsc.BEDROOM and label.startswith("decoration"):
            for _, rec in pairs:
                cells |= set(LiveMap(gsc.BEDROOM, rec).changed)
    return cells


def export_page(kind: str, page_id: str) -> dict:
    d = json.loads((EXPORT / "crystal" / kind / f"{page_id}.json").read_text(encoding="utf-8"))
    return d.get("data", d)


class LiveMap:
    """A map's cells as the hidden game has them in a sweep case: the export's blocks (local/minecraft/kantobridge/
    export/crystal/g2map/G2_<map>.json) with the case's changed blocks over them, each cell classed by its collision
    byte's meaning (g2tiles): walk, ledge, water or solid (block 0 and unknown bytes are walls)."""

    def __init__(self, map_id: str, rec: dict | None):
        d = export_page("g2map", PREFIX + map_id)
        t = export_page("g2tiles", d["variant"])
        self.bw, self.bh = d["w"], d["h"]
        self.w, self.h = self.bw * 2, self.bh * 2
        data = list(base64.b64decode(d["blocks"]))
        self.data = data
        self.coll, self.meanings, self.art = t["coll"], t["meanings"], t["blocks"]
        live = list(data)
        for b in ((rec or {}).get("settled") or {}).get("blocks") or []:
            live[b["by"] * self.bw + b["bx"]] = b["block"]
        self.cells = {}
        self.changed = {}
        for cy in range(self.h):
            for cx in range(self.w):
                i = (cy // 2) * self.bw + cx // 2
                lc = self.cls(live[i], cx % 2, cy % 2)
                self.cells[(cx, cy)] = lc
                if live[i] != data[i]:
                    bc = self.cls(data[i], cx % 2, cy % 2)
                    if bc != lc or self.quarter(live[i], cx % 2, cy % 2) != self.quarter(data[i], cx % 2, cy % 2):
                        self.changed[(cx, cy)] = {"bc": bc, "lc": lc}
        self.warps = {(w["x"], w["y"]) for w in d.get("warps") or []}
        self.objects = {(o["x"], o["y"]) for o in d.get("objects") or []}

    def cls(self, block: int, qx: int, qy: int) -> str:
        if block == 0 or block >= len(self.coll):
            return "solid"
        m = self.meanings.get(str(self.coll[block][qy * 2 + qx])) or {"perm": "wall"}
        if m["perm"] == "land":
            return "ledge" if m.get("ledge") else "walk"
        return "water" if m["perm"] == "water" else "solid"

    def quarter(self, block: int, qx: int, qy: int) -> tuple:
        tiles = self.art[block] if block < len(self.art) else []
        return tuple(tiles[r * 4 + c] for r in (qy * 2, qy * 2 + 1) for c in (qx * 2, qx * 2 + 1)
                     if r * 4 + c < len(tiles))

    def walkable(self, x, y):
        return self.cells.get((x, y)) == "walk" and (x, y) not in self.warps and (x, y) not in self.objects

    def can_step(self, a, b):
        return True  # Gen 2 maps have no heights


def lua_literal(spot_id: int, map_id: str, x, y, facing: str, case: dict, scenes: dict, flash: bool) -> str:
    def lua_str(s: str) -> str:
        return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'

    parts = [f"id = {spot_id}", 'op = "go"', f"map = {lua_str(map_id)}", f"facing = {lua_str(facing)}"]
    if x is not None and y is not None:
        parts += [f"x = {x}", f"y = {y}"]
    parts.append("events = { " + ", ".join(f"[{int(k)}] = {'true' if v else 'false'}" for k, v in case["events"].items()) + " }")
    parts.append("flags = { " + ", ".join(f"[{int(k)}] = {'true' if v else 'false'}" for k, v in case["flags"].items()) + " }")
    merged = dict(case["scenes"], **scenes)
    parts.append("scenes = { " + ", ".join(f"[{lua_str(m)}] = {int(v)}" for m, v in sorted(merged.items())) + " }")
    parts.append("mapscenes = { " + ", ".join(f"{{ group = {ms['group']}, map = {ms['map']}, value = {ms['value']} }}"
                                             for ms in case["mapscenes"]) + " }")
    parts.append("mem = { " + ", ".join(f"[{int(a)}] = {int(v)}" for a, v in case["mem"].items()) + " }")
    for k in ("hour", "day"):
        if case.get(k) is not None:
            parts.append(f"{k} = {int(case[k])}")
    if flash:
        parts.append("flash = true")
    if case.get("decorations"):
        parts.append("decorations = { " + ", ".join(f"{slot} = {int(v)}" for slot, v in sorted(case["decorations"].items())) + " }")
    return "{ " + ", ".join(parts) + " }"


def client_command(world: str) -> list[str]:
    return ["./gradlew", "--no-daemon", "--console=plain", "runClient", "-Pkb.autopilot=remote", f"-Pkb.autopilotWorld={world}",
            f"-Pkb.autopilotPilotDir={PILOT_DIR}", "-Pkb.autopilotFollow=crystal", "-Pkb.autopilotQuit=true",
            f"-Pkb.links=crystal:{PORT}"]


def run_spot(spot: Spot, n: int, truth: dict, commands: Path, run, pilot: Pilot) -> dict:
    out = {"spot": spot.name, "map": spot.map, "check": spot.check}
    wire = PREFIX + spot.map
    entries = entries_for(truth, spot)
    new_game = {"events": {}, "flags": {}, "scenes": {}, "mapscenes": [], "mem": {}, "n": 0}
    case, rec = entries[0] if entries else (new_game, None)
    if spot.check in ("enter", "refuse", "decor") and not entries:
        out["result"] = "no sweep case"
        return out
    found = None
    if spot.check in ("enter", "refuse"):
        classes = set()
        for c, r in entries:
            m = LiveMap(spot.map, r)
            classes |= {f"{v['bc']}>{v['lc']}" for v in m.changed.values()}
            found = approach(m, spot.cls, into=spot.check == "enter")
            if found is not None:
                case, rec = c, r
                break
        if found is None:
            out["result"] = f"no {spot.cls} cell with a walkable way to it (changes seen: {sorted(classes)})"
            return out
    out["case"] = case["n"]
    x = y = None
    facing = "down"
    target = None
    steps = 1
    if found is not None:
        (x, y), target, facing, steps = found
        out["from"], out["into"], out["dir"], out["steps"] = [x, y], list(target), facing, steps
    with open(commands, "a", encoding="utf-8") as f:
        f.write(lua_literal(n, spot.map, x, y, facing, case, spot.scenes, spot.flash) + "\n")
    game = wait_spot(run, n)
    out["game"] = {k: game.get(k) for k in ("marker", "map", "x", "y", "blocks", "dark", "flash", "halted", "held", "busy")}
    if game["marker"] != "KB_SPOT" or game.get("map") != spot.map:
        out["result"] = "the game didn't reach the spot"
        return out
    st = wait_followed(pilot, wire, (x, y) if x is not None else None)
    time.sleep(3.0)  # the client settles: the story cells and the light
    if spot.check in ("enter", "refuse"):
        for _ in range(steps - 1):
            pilot.do("step", dir=facing, timeout=30)
        cell_state = pilot.do("story", map=wire, x=target[0], y=target[1], timeout=10)
        out["story_cell"] = {k: cell_state.get(k) for k in ("type", "active", "drawn", "state", "surf", "story_blocks",
                                                             "closed_blocks")}
        st = pilot.state()
        before = (st.get("move") or {}).copy()
        res = pilot.do("step", dir=facing, timeout=30)
        time.sleep(1.5)  # a refused step's snap-back lands
        st = pilot.state()
        moved = st.get("map") != wire or (st.get("x"), st.get("y")) == tuple(target)
        after = st.get("move") or {}
        snaps = (after.get("snaps", 0) - before.get("snaps", 0)) + (after.get("blocked", 0) - before.get("blocked", 0))
        out["step"] = {"ok": res.get("ok"), "now": [st.get("map"), st.get("x"), st.get("y")], "snapped": snaps}
        if spot.check == "enter":
            out["result"] = "PASS" if moved else "FAIL"
        else:
            # refused by the hidden game, and by Minecraft too: every block of the cell collides for this player
            walled = (cell_state.get("story_blocks") or 0) > 0 and cell_state.get("closed_blocks") == cell_state.get("story_blocks")
            out["result"] = "PASS" if not moved and walled else "FAIL (Minecraft lets the player in)" if not moved else "FAIL"
    elif spot.check == "decor":
        # every cell a decoration changes: a cell story cell shows the live block and collides as its cell does
        m = LiveMap(spot.map, rec)
        live = {(b["bx"], b["by"]): b["block"] for b in (rec.get("settled") or {}).get("blocks") or []}
        tally = {"cells": 0, "right": 0, "other_type": 0}
        wrong = []
        for x, y in sorted(decoration_cells(truth)):
            cell_state = pilot.do("story", map=wire, x=x, y=y, timeout=10)
            if cell_state.get("type") != "cell":
                tally["other_type"] += 1
                continue
            tally["cells"] += 1
            block = live.get((x // 2, y // 2), m.data[(y // 2) * m.bw + x // 2])
            collides = m.cells[(x, y)] in ("solid", "water")
            if cell_state.get("shows") == block and bool(cell_state.get("active")) == collides:
                tally["right"] += 1
            else:
                wrong.append({"x": x, "y": y, "shows_live": cell_state.get("shows") == block, "active": cell_state.get("active"),
                              "collides": collides})
        out["decor"] = dict(tally, wrong=wrong[:6])
        out["result"] = "PASS" if tally["cells"] > 0 and tally["right"] == tally["cells"] else "FAIL"
    else:
        time.sleep(2.0)
        st = pilot.state()
        dark, lit, map_dark, nv = st.get("dark"), st.get("lit"), st.get("map_dark"), st.get("night_vision")
        out["light"] = {"flash": game.get("flash"), "dark": dark, "lit": lit, "map_dark": map_dark, "night_vision": nv}
        if dark is None:
            out["result"] = "unknown (the client reports no darkness)"
        elif spot.check == "dark":
            out["result"] = "PASS" if map_dark and dark and not lit and not nv else "FAIL"
        else:
            # Flash used: the client lights the dark-built map (Night Vision)
            out["result"] = "PASS" if map_dark and (lit or not dark) and nv else "FAIL"
    return out


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--world", default="ThreeRegionsE2E", help="the built world in local/minecraft/saves (copied)")
    parser.add_argument("--record", help="the .mp4 to write")
    parser.add_argument("--only", default="", help="these spots (comma-separated names)")
    parser.add_argument("--plan", action="store_true", help="only print each spot's case and approach")
    parser.add_argument("--timeout", type=int, default=3600)
    args = parser.parse_args(argv)

    scan, truth = load_truth()
    spots = [s for s in SPOTS if not args.only or s.name in args.only.split(",")]
    if args.plan:
        for s in spots:
            entries = entries_for(truth, s)
            plan = None
            classes = set()
            for c, r in entries:
                m = LiveMap(s.map, r)
                classes |= {f"{v['bc']}>{v['lc']}" for v in m.changed.values()}
                if s.check in ("enter", "refuse"):
                    plan = approach(m, s.cls, into=s.check == "enter")
                    if plan:
                        plan = (c["n"], plan)
                        break
            print(json.dumps({"spot": s.name, "cases": len(entries), "changes": sorted(classes), "plan": plan}))
        return 0
    source = SAVES / args.world
    if not (source / "level.dat").is_file():
        print(f"no world {args.world}", file=sys.stderr)
        return 2
    world = args.world + "SpotsG2"
    if (SAVES / world).exists():
        shutil.rmtree(SAVES / world)
    shutil.copytree(source, SAVES / world)
    say("world copied:", world, "spots:", len(spots))

    home = make_home("spots_g2", "crystal")
    game_env = {"KB_DATASET_MEMO": "30", "KB_PACE": "60", "KB_DRIVER_SECONDS": str(args.timeout + 600)}
    PILOT_DIR.mkdir(parents=True, exist_ok=True)
    commands = PILOT_DIR / "game_commands.lua"
    commands.write_text("", encoding="utf-8")
    pilot = Pilot(PILOT_DIR)
    run = launch("kb_g2spots.lua", game="crystal", install=home, echo=False,
                 extra_env=dict(game_env, KB_TEST_PORT=str(PORT), KB_SPOT_COMMANDS=str(commands)))
    client = None
    results = []
    code = 1
    try:
        say("crystal:", run.wait_marker("KB_SERVING", timeout=300))
        java_home = os.environ.get("KB_JAVA_HOME") or os.environ.get("JAVA_HOME") or "/opt/java/openjdk"
        client = subprocess.Popen(client_command(world), cwd=GRADLE_DIR, env=dict(os.environ, JAVA_HOME=java_home),
                                  stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        threading.Thread(target=stream, args=(client, PILOT_DIR / "client.log"), daemon=True).start()
        deadline = time.monotonic() + 900
        while pilot.state().get("phase") != "ready":
            if time.monotonic() > deadline or client.poll() is not None:
                raise Stuck("the client never reached the world")
            time.sleep(1.0)
        say("client in the world; follow crystal:", pilot.do("follow", game="crystal").get("ok"))
        wait_followed(pilot, PREFIX + TOWN, timeout=120)
        time.sleep(15)  # the region's documents and the pack settle before the first warp
        if args.record:
            pilot.do("record", file=args.record, timeout=30)
        for i, spot in enumerate(spots, 1):
            started = time.monotonic()
            try:
                out = run_spot(spot, i, truth, commands, run, pilot)
            except Stuck as e:
                out = {"spot": spot.name, "map": spot.map, "check": spot.check, "result": f"STUCK: {e}"}
            out["seconds"] = round(time.monotonic() - started, 1)
            results.append(out)
            say(json.dumps(out, sort_keys=True))
        code = 0 if all(r.get("result") == "PASS" for r in results) else 1
    except Stuck as e:
        say("STUCK:", e)
    finally:
        try:
            pilot.do("stop", timeout=60)
            pilot.send("quit")
        except Stuck:
            pass
        if client is not None:
            try:
                client.wait(timeout=120)
            except subprocess.TimeoutExpired:
                client.kill()
        run.kill()
        run.wait(timeout=30)
        (PILOT_DIR / "crystal.log").write_text("\n".join(run.result.lines) + "\n", encoding="utf-8")
    (gsc.REPORTS / "g2_story_spots.json").write_text(json.dumps(results, indent=1, sort_keys=True), encoding="utf-8")
    passed = sum(1 for r in results if r.get("result") == "PASS")
    say(f"== {passed} of {len(results)} spots passed")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
