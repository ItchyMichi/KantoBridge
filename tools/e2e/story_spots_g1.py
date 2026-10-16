"""Blue's story spots in the real client (docs/specs/STORY_EVENTS_PLAN.md §6): the hidden Blue is put into a story
state the live sweep found (tools/e2e/g1_story_check.py), the real dev client follows it there, and the client walks
into the cell the story changed: through Lorelei's opened exit and past the Victory Road barriers a boulder opened;
against Bruno's and Agatha's sealed exits, a Cinnabar Gym gate, a Pokemon Mansion gate in each switch state, a Silph Co card-key door, a Rocket Hideout lift gate
and the Vermilion Gym's locked door; Rock Tunnel's darkness, unlit and lit by Flash, is read back.

    xvfb-run -a -s "-screen 0 1280x720x24" python tools/e2e/story_spots_g1.py --world ThreeRegionsE2E \\
        [--record /workspace/videos/kanto-bridge-blue-story-spots.mp4] [--only e4_lorelei_exit,silph_7f_door] [--plan]

1. A fresh data home on the pod's container disk (the test install's Blue import and gen1-bridge linked by name), so
   the game starts with no save of its own.
2. A copy of --world (Kanto built at slot 1) to play in.
3. Blue with drivers/kb_g1spots.lua (a new game, below Pallet Town's first door; it runs the go commands this script
   writes); the dev client (RemotePilot) with Blue's link only, following it.
4. Each spot: the story state of a sweep case (its flags set, or every story flag) and a warp onto a cell up to four
   steps from the changed one (planned on the export's blocks and tilesets: local/minecraft/kantobridge/export/blue/),
   then the pilot's steps into it: `enter` passes when the hidden player stands on it (or the map changed), `refuse`
   when the step is refused and every block of the cell collides for the player in Minecraft (the pilot's `story`
   answer); `dark` and `lit` compare the client's light with the game's.

The changed blocks come from the sweep's truth files in local/reports/ (ROM-derived; they stay on the pod). Output:
spot names, map symbols, cells, counts and booleans only, never the game's text. A video, when asked for, shows the
user's own game: it stays on the pod's volume and the agent never opens it.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

import g1_story_check as g1  # noqa: E402
from run_gen1recomp import launch  # noqa: E402
from story_spots import approach, stream, wait_followed, wait_spot  # noqa: E402
from story_video import EXPORT, G1_LEDGE, Pilot, Stuck, make_home, say  # noqa: E402

GRADLE_DIR = REPO / "fabric-bridge"
SAVES = REPO / "local" / "minecraft" / "saves"
PILOT_DIR = REPO / "local" / "reports" / "spots_g1"
PORT = 27243
TOWN = "PALLET_TOWN"


@dataclass
class Spot:
    name: str
    map: str            # the engine's map id (the wire's too: Kanto's ids carry no prefix)
    check: str          # enter, refuse, dark, lit
    label: str = "new game"  # the sweep case's label (g1_story_check.make_cases)
    cls: str = ""       # enter/refuse: the walkability change of the cell to step into, e.g. "solid>walk"
    flash: bool = False  # lit: Flash used on the map once the field stands


SPOTS = [
    # Lorelei's exit ships closed in the map data and opens once she is beaten; Bruno's and Agatha's ship open, so
    # their change is the seal a new game stamps (story4.lua:862-932)
    Spot("e4_lorelei_exit", "LORELEIS_ROOM", "enter", "EVENT_BEAT_LORELEIS_ROOM_TRAINER_0 set", "solid>walk"),
    Spot("e4_bruno_sealed", "BRUNOS_ROOM", "refuse", "new game", "walk>solid"),
    Spot("e4_agatha_sealed", "AGATHAS_ROOM", "refuse", "new game", "walk>solid"),
    # (Lance's doorway: a warp in lands on the stairs and his walk-in crosses the doorway and locks it, so every case
    # ends in the map data's state, story4.lua:1004-1015: the replay checks it, no spot can)
    Spot("victory_road_1f_barrier", "VICTORY_ROAD_1F", "enter", "EVENT_VICTORY_ROAD_1_BOULDER_ON_SWITCH set", "solid>walk"),
    Spot("victory_road_3f_barrier", "VICTORY_ROAD_3F", "enter", "EVENT_VICTORY_ROAD_3_BOULDER_ON_SWITCH1 set", "solid>walk"),
    Spot("cinnabar_gym_gate", "CINNABAR_GYM", "refuse", "new game", "walk>solid"),
    # every Mansion gate is open in the map data; each switch state closes some (story6.lua:25-58)
    Spot("mansion_gate_switch_off", "POKEMON_MANSION_1F", "refuse", "new game", "walk>solid"),
    Spot("mansion_gate_switch_on", "POKEMON_MANSION_1F", "refuse", "EVENT_MANSION_SWITCH_ON set", "walk>solid"),
    Spot("silph_7f_door", "SILPH_CO_7F", "refuse", "new game", "walk>solid"),
    Spot("rocket_hideout_gate", "ROCKET_HIDEOUT_B1F", "refuse", "new game", "walk>solid"),
    Spot("vermilion_gym_door", "VERMILION_GYM", "refuse", "new game", "walk>solid"),
    Spot("rock_tunnel_dark", "ROCK_TUNNEL_1F", "dark"),
    Spot("rock_tunnel_lit", "ROCK_TUNNEL_1F", "lit", flash=True),
]


def load_truth():
    """The sweep's cases by (map, label), in case order: their state and the blocks they changed."""
    inv = json.loads(g1.INVENTORY.read_text(encoding="utf-8"))
    cases = {c["n"]: c for c in g1.make_cases(inv)}
    truth = {}
    if g1.SWEEP.is_file():
        for rec in json.loads(g1.SWEEP.read_text(encoding="utf-8"))["cases"]:
            case = cases.get(rec.get("n"))
            if case and rec.get("ok") and rec.get("map") == case["map"]:
                truth.setdefault((case["map"], case["label"]), []).append((case, rec))
    return inv, truth


def export_page(kind: str, page_id: str) -> dict:
    d = json.loads((EXPORT / "blue" / kind / f"{page_id}.json").read_text(encoding="utf-8"))
    return d.get("data", d)


class LiveMap:
    """A map's cells as the hidden game has them in a sweep case: the export's blocks (local/minecraft/kantobridge/
    export/blue/map/<map>.json) with the case's changed blocks over them, each cell classed by its collision tile (the
    bottom-left tile of its quarter, as the engine and the builder read it) against the tileset's walkable and water
    tiles; a ledge (the export's cell flag) is no walk."""

    def __init__(self, map_id: str, rec: dict | None):
        d = export_page("map", map_id)
        t = export_page("tileset", d["tileset"])
        self.bw, self.bh = d["width"], d["height"]
        self.w, self.h = self.bw * 2, self.bh * 2
        self.data = list(d["blocks"])
        self.art = t["blocks"]
        self.walk_tiles, self.water_tiles = set(t.get("walkable") or []), set(t.get("water") or [])
        self.flags = d["cells"]
        live = list(self.data)
        for b in ((rec or {}).get("settled") or {}).get("blocks") or []:
            live[b["by"] * self.bw + b["bx"]] = b["block"]
        self.cells = {}
        self.changed = {}
        for cy in range(self.h):
            for cx in range(self.w):
                i = (cy // 2) * self.bw + cx // 2
                lc = self.cls(live[i], cx % 2, cy % 2, cx, cy)
                self.cells[(cx, cy)] = lc
                if live[i] != self.data[i]:
                    bc = self.cls(self.data[i], cx % 2, cy % 2, cx, cy)
                    if bc != lc or self.quarter(live[i], cx % 2, cy % 2) != self.quarter(self.data[i], cx % 2, cy % 2):
                        self.changed[(cx, cy)] = {"bc": bc, "lc": lc}
        self.warps = {(w["x"], w["y"]) for w in d.get("warps") or []}
        self.objects = {(o["x"], o["y"]) for o in d.get("objects") or []}

    def cls(self, block: int, qx: int, qy: int, cx: int, cy: int) -> str:
        tiles = self.art[block] if block < len(self.art) else []
        if len(tiles) < 16:
            return "solid"
        tile = tiles[(qy * 2 + 1) * 4 + qx * 2]
        if tile in self.walk_tiles:
            return "ledge" if self.flags[cy][cx] & G1_LEDGE else "walk"
        return "water" if tile in self.water_tiles else "solid"

    def quarter(self, block: int, qx: int, qy: int) -> tuple:
        tiles = self.art[block] if block < len(self.art) else []
        return tuple(tiles[r * 4 + c] for r in (qy * 2, qy * 2 + 1) for c in (qx * 2, qx * 2 + 1) if r * 4 + c < len(tiles))

    def walkable(self, x, y):
        return self.cells.get((x, y)) == "walk" and (x, y) not in self.warps and (x, y) not in self.objects

    def can_step(self, a, b):
        return True  # Gen 1 maps have no heights


def lua_literal(spot_id: int, map_id: str, x, y, facing: str, case: dict, flash: bool) -> str:
    def lua_str(s: str) -> str:
        return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'

    parts = [f"id = {spot_id}", 'op = "go"', f"map = {lua_str(map_id)}", f"facing = {lua_str(facing)}"]
    if x is not None and y is not None:
        parts += [f"x = {x}", f"y = {y}"]
    flags = case.get("flags") or {}
    if len(flags) > 8 and all(flags.values()):
        parts.append("every = true")
    else:
        parts.append("flags = { " + ", ".join(f"{k} = {'true' if v else 'false'}" for k, v in sorted(flags.items())) + " }")
    if flash:
        parts.append("flash = true")
    return "{ " + ", ".join(parts) + " }"


def client_command(world: str) -> list[str]:
    return ["./gradlew", "--no-daemon", "--console=plain", "runClient", "-Pkb.autopilot=remote", f"-Pkb.autopilotWorld={world}",
            f"-Pkb.autopilotPilotDir={PILOT_DIR}", "-Pkb.autopilotFollow=blue", "-Pkb.autopilotQuit=true",
            f"-Pkb.links=blue:{PORT}"]


def plan_spot(spot: Spot, truth: dict):
    """The spot's case and approach: (case, rec, found, classes seen)."""
    entries = truth.get((spot.map, spot.label)) or []
    classes = set()
    for c, r in entries:
        m = LiveMap(spot.map, r)
        classes |= {f"{v['bc']}>{v['lc']}" for v in m.changed.values()}
        found = approach(m, spot.cls, into=spot.check == "enter")
        if found is not None:
            return c, r, found, classes
    return (entries[0][0], entries[0][1], None, classes) if entries else (None, None, None, classes)


def run_spot(spot: Spot, n: int, truth: dict, commands: Path, run, pilot: Pilot) -> dict:
    out = {"spot": spot.name, "map": spot.map, "check": spot.check}
    new_game = {"flags": {}, "n": 0}
    found = None
    if spot.check in ("enter", "refuse"):
        case, rec, found, classes = plan_spot(spot, truth)
        if case is None:
            out["result"] = "no sweep case"
            return out
        if found is None:
            out["result"] = f"no {spot.cls} cell with a walkable way to it (changes seen: {sorted(classes)})"
            return out
    else:
        case = new_game
    out["case"] = case["n"]
    x = y = None
    facing = "down"
    target = None
    steps = 1
    if found is not None:
        (x, y), target, facing, steps = found
        out["from"], out["into"], out["dir"], out["steps"] = [x, y], list(target), facing, steps
    with open(commands, "a", encoding="utf-8") as f:
        f.write(lua_literal(n, spot.map, x, y, facing, case, spot.flash) + "\n")
    game = wait_spot(run, n)
    out["game"] = {k: game.get(k) for k in ("marker", "map", "x", "y", "blocks", "dark", "flash", "halted")}
    if game["marker"] != "KB_SPOT" or game.get("map") != spot.map:
        out["result"] = "the game didn't reach the spot"
        return out
    wait_followed(pilot, spot.map, (x, y) if x is not None else None)
    time.sleep(3.0)  # the client settles: the story cells and the light
    if spot.check in ("enter", "refuse"):
        for _ in range(steps - 1):
            pilot.do("step", dir=facing, timeout=30)
        cell_state = pilot.do("story", map=spot.map, x=target[0], y=target[1], timeout=10)
        out["story_cell"] = {k: cell_state.get(k) for k in ("type", "active", "drawn", "state", "story_blocks", "closed_blocks")}
        before = (pilot.state().get("move") or {}).copy()
        res = pilot.do("step", dir=facing, timeout=30)
        time.sleep(1.5)  # a refused step's snap-back lands
        st = pilot.state()
        moved = st.get("map") != spot.map or (st.get("x"), st.get("y")) == tuple(target)
        after = st.get("move") or {}
        snaps = (after.get("snaps", 0) - before.get("snaps", 0)) + (after.get("blocked", 0) - before.get("blocked", 0))
        out["step"] = {"ok": res.get("ok"), "now": [st.get("map"), st.get("x"), st.get("y")], "snapped": snaps}
        if spot.check == "enter":
            out["result"] = "PASS" if moved else "FAIL"
        else:
            # refused by the hidden game, and by Minecraft too: every block of the cell collides for this player
            walled = (cell_state.get("story_blocks") or 0) > 0 and cell_state.get("closed_blocks") == cell_state.get("story_blocks")
            out["result"] = "PASS" if not moved and walled else "FAIL (Minecraft lets the player in)" if not moved else "FAIL"
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

    _, truth = load_truth()
    spots = [s for s in SPOTS if not args.only or s.name in args.only.split(",")]
    if args.plan:
        for s in spots:
            if s.check not in ("enter", "refuse"):
                print(json.dumps({"spot": s.name, "check": s.check}))
                continue
            case, _, found, classes = plan_spot(s, truth)
            print(json.dumps({"spot": s.name, "case": case and case["n"], "changes": sorted(classes), "plan": found}))
        return 0
    source = SAVES / args.world
    if not (source / "level.dat").is_file():
        print(f"no world {args.world}", file=sys.stderr)
        return 2
    world = args.world + "SpotsG1"
    if (SAVES / world).exists():
        shutil.rmtree(SAVES / world)
    shutil.copytree(source, SAVES / world)
    say("world copied:", world, "spots:", len(spots))

    home = make_home("spots_g1", "blue")
    game_env = {"KB_DATASET_MEMO": "30", "KB_PACE": "60", "KB_DRIVER_SECONDS": str(args.timeout + 600)}
    PILOT_DIR.mkdir(parents=True, exist_ok=True)
    commands = PILOT_DIR / "game_commands.lua"
    commands.write_text("", encoding="utf-8")
    pilot = Pilot(PILOT_DIR)
    run = launch("kb_g1spots.lua", game="blue", install=home, echo=False,
                 extra_env=dict(game_env, KB_TEST_PORT=str(PORT), KB_SPOT_COMMANDS=str(commands),
                                KB_INVENTORY=str(g1.INVENTORY.resolve())))
    client = None
    results = []
    code = 1
    try:
        say("blue:", run.wait_marker("KB_SERVING", timeout=300))
        java_home = os.environ.get("KB_JAVA_HOME") or os.environ.get("JAVA_HOME") or "/opt/java/openjdk"
        client = subprocess.Popen(client_command(world), cwd=GRADLE_DIR, env=dict(os.environ, JAVA_HOME=java_home),
                                  stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        threading.Thread(target=stream, args=(client, PILOT_DIR / "client.log"), daemon=True).start()
        deadline = time.monotonic() + 900
        while pilot.state().get("phase") != "ready":
            if time.monotonic() > deadline or client.poll() is not None:
                raise Stuck("the client never reached the world")
            time.sleep(1.0)
        say("client in the world; follow blue:", pilot.do("follow", game="blue").get("ok"))
        wait_followed(pilot, TOWN, timeout=120)
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
        (PILOT_DIR / "blue.log").write_text("\n".join(run.result.lines) + "\n", encoding="utf-8")
    (g1.REPORTS / "g1_story_spots.json").write_text(json.dumps(results, indent=1, sort_keys=True), encoding="utf-8")
    passed = sum(1 for r in results if r.get("result") == "PASS")
    say(f"== {passed} of {len(results)} spots passed")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
