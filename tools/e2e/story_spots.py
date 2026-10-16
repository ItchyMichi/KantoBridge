"""Emerald's story spots in the real client (docs/specs/STORY_EVENTS_PLAN.md SE5): the hidden Emerald is put into a
story state the live sweep found (tools/e2e/g3_story_check.py), the real dev client follows it there, and the client
walks into the cell the story changed: through the Elite Four's opened exits, onto Sky Pillar's clean floor, onto the
land Mirage Island and Route 131's swap raise, into Shoal Cave's high tide; and the weather and Dewford Gym's darkness
the client then shows are read back.

    xvfb-run -a -s "-screen 0 1280x720x24" python tools/e2e/story_spots.py --world ThreeRegionsE2E \\
        [--record /workspace/videos/kanto-bridge-emerald-story-spots.mp4] [--only e4_drake_exit,mirage_island]

1. A fresh data home on the pod's container disk (the test install's Emerald import and gen1-bridge linked by name;
   its Union Room switched off), so the game starts at its boot screens with no save.
2. A copy of --world (Hoenn built at slot 1) to play in.
3. Emerald with drivers/kb_g3spots.lua (a new game in Littleroot; it runs the go commands this script writes); the
   dev client (RemotePilot, fabric-bridge/src/client/java/dev/kantobridge/client/dev/RemotePilot.java) with Emerald's
   link only, following it.
4. Each spot: the story state (flags and vars of a sweep case, the tide or Mirage Island forced) and a warp onto the
   cell beside the changed one (planned on the export's cells: local/minecraft/kantobridge/export/emerald/), then the
   pilot's step into it: `enter` passes when the hidden player stands on it (or the map changed: a door), `refuse` when
   the step is refused; `weather` and `dark` compare the client's state with the game's.

The changed cells come from the sweep's truth files in local/reports/ (ROM-derived; they stay on the pod). Output:
spot names, map symbols, cells, counts, booleans and weather names only, never the game's text. A video, when asked
for, shows the user's own game: it stays on the pod's volume and the agent never opens it.
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
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

import g3_story_check as gsc  # noqa: E402
import run_gen1recomp as rg  # noqa: E402
from run_gen1recomp import launch  # noqa: E402
from story_video import Gen3Map, Pilot, Stuck, make_home, say  # noqa: E402

GRADLE_DIR = REPO / "fabric-bridge"
SAVES = REPO / "local" / "minecraft" / "saves"
PILOT_DIR = REPO / "local" / "reports" / "spots"
PORT = 27241
DELTA = {"up": (0, -1), "down": (0, 1), "left": (-1, 0), "right": (1, 0)}
TRAINER_FLAGS_START = 0x500  # pokeemerald include/constants/flags.h: a trainer's flag is this plus its id
WEATHER_NAMES = {0: "none", 1: "clouds", 2: "sunny", 3: "rain", 4: "snow", 5: "thunder", 6: "fog", 7: "ash", 8: "sand",
                 9: "fog_diagonal", 10: "underwater", 11: "shade", 12: "drought", 13: "downpour", 14: "bubbles"}


@dataclass
class Spot:
    name: str
    map: str
    check: str                      # enter, refuse, weather, dark, lit
    label: str = "new game"         # the sweep case's label (its first world command), "new game" for the baseline
    cls: str = ""                   # enter/refuse: the walkability change of the cell to step into, e.g. "solid>walk"
    vars: dict = field(default_factory=dict)   # extra var names -> values on top of the case's
    trainers: bool = False          # also set the trainer flags the map's scripts check (Dewford Gym's lights)
    expect: tuple = ()              # weather: the names the client may show


SPOTS = [
    Spot("e4_sidney_exit", "EM_EVER_GRANDE_CITY_SIDNEYS_ROOM", "enter", "setmetatile (onLoad)", "solid>walk"),
    Spot("e4_drake_exit", "EM_EVER_GRANDE_CITY_DRAKES_ROOM", "enter", "setmetatile (onLoad)", "solid>walk"),
    Spot("mauville_gym_barrier", "EM_MAUVILLE_CITY_GYM", "enter", "special MauvilleGymDeactivatePuzzle (onLoad)", "solid>walk"),
    Spot("sky_pillar_clean_floor", "EM_SKY_PILLAR_1F", "enter", "new game", "solid>walk"),
    Spot("route131_swap", "EM_ROUTE131", "enter", "new game", "water>walk"),
    Spot("mirage_island", "EM_ROUTE130", "enter", "setmaplayoutindex 46 (onTransition)", "water>walk"),
    Spot("sootopolis_crisis", "EM_SOOTOPOLIS_CITY", "enter", "setmaplayoutindex 357 (onTransition)", "water>walk"),
    Spot("shoal_high_tide", "EM_SHOAL_CAVE_LOW_TIDE_ENTRANCE_ROOM", "refuse", "setmaplayoutindex 168 (onTransition)", "walk>water"),
    Spot("weather_route120_rain", "EM_ROUTE120", "weather", expect=("rain",)),
    Spot("weather_route114_drought", "EM_ROUTE114", "weather", "abnormal weather 1", expect=("drought",)),
    Spot("weather_route105_downpour", "EM_ROUTE105", "weather", "abnormal weather 9", expect=("downpour",)),
    Spot("weather_sootopolis_abnormal", "EM_SOOTOPOLIS_CITY", "weather", "setmaplayoutindex 357 (onTransition)",
         expect=("downpour", "drought")),
    Spot("weather_jagged_pass_ash", "EM_JAGGED_PASS", "weather", "setweather VOLCANIC_ASH (onTransition)", expect=("ash",)),
    Spot("weather_seafloor_bubbles", "EM_UNDERWATER_SEAFLOOR_CAVERN", "weather", expect=("bubbles",)),
    Spot("dewford_gym_dark", "EM_DEWFORD_TOWN_GYM", "dark"),
    Spot("dewford_gym_lit", "EM_DEWFORD_TOWN_GYM", "lit", trainers=True),
]


def load_truth():
    """Every sweep case by (map, label): its state (flags, vars, tide, mirage) and the cells it changed."""
    scan = json.loads(gsc.SCAN.read_text(encoding="utf-8"))
    cases = {c["n"]: c for c in gsc.make_cases(scan)}
    truth = {}
    for name in ("g3_story_sweep_full1.json", "g3_story_sweep.json"):
        path = gsc.REPORTS / name
        if not path.is_file():
            continue
        for rec in json.loads(path.read_text(encoding="utf-8"))["cases"]:
            case = cases.get(rec.get("n"))
            if case and rec.get("ok") and rec.get("map") == case["map"]:
                truth[(case["map"], case["label"])] = (case, rec)
    return scan, truth


def lua_literal(spot_id: int, map_id: str, x: int | None, y: int | None, facing: str, flags: dict, vars_: dict,
                tide=None, mirage=None) -> str:
    parts = [f"id = {spot_id}", 'op = "go"', f'map = "{map_id}"', f'facing = "{facing}"']
    if x is not None and y is not None:
        parts += [f"x = {x}", f"y = {y}"]
    parts.append("flags = { " + ", ".join(f"[{int(k)}] = {'true' if v else 'false'}" for k, v in flags.items()) + " }")
    parts.append("vars = { " + ", ".join(f"[{int(k)}] = {int(v)}" for k, v in vars_.items()) + " }")
    if tide is not None:
        parts.append(f"tide = {'true' if tide else 'false'}")
    if mirage is not None:
        parts.append(f"mirage = {'true' if mirage else 'false'}")
    return "{ " + ", ".join(parts) + " }"


def approach(m: Gen3Map, rec: dict, cls: str):
    """A changed cell of the case with that walkability change, and a cell beside it the export lets the player stand
    on (not itself changed), with the direction from there into it; None when the case has none."""
    cells = [c for c in (rec.get("settled") or {}).get("cells") or [] if not c.get("same")]
    changed = {(c["x"], c["y"]) for c in cells}
    for c in cells:
        if f"{c.get('bc')}>{c.get('lc')}" != cls:
            continue
        for d, (dx, dy) in DELTA.items():
            ax, ay = c["x"] - dx, c["y"] - dy
            if (ax, ay) not in changed and m.inside(ax, ay) and m.passable(ax, ay):
                return (ax, ay), (c["x"], c["y"]), d
    return None


def client_command(world: str) -> list[str]:
    return ["./gradlew", "--no-daemon", "--console=plain", "runClient", "-Pkb.autopilot=remote", f"-Pkb.autopilotWorld={world}",
            f"-Pkb.autopilotPilotDir={PILOT_DIR}", "-Pkb.autopilotFollow=emerald", "-Pkb.autopilotQuit=true",
            f"-Pkb.links=emerald:{PORT}"]


def stream(proc, log_path: Path):
    with open(log_path, "w", encoding="utf-8", buffering=1) as log:
        for raw in iter(proc.stdout.readline, b""):
            log.write(raw.decode("utf-8", errors="replace").rstrip() + "\n")


def wait_spot(run, spot_id: int, timeout: float = 60.0) -> dict:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for line in list(run.result.lines):
            if line.startswith(f"KB_SPOT id={spot_id} ") or line.startswith(f"KB_SPOT_FAIL id={spot_id} "):
                return dict(p.split("=", 1) for p in line.split()[1:] if "=" in p) | {"marker": line.split()[0]}
        time.sleep(0.2)
    raise Stuck(f"spot {spot_id}: no KB_SPOT within {timeout:.0f} s")


def wait_followed(pilot: Pilot, map_id: str, cell=None, timeout: float = 60.0) -> dict:
    """The pilot's state once the followed hidden player (state.json's map, x, y) is on map_id (and that cell)."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        st = pilot.state()
        if st.get("map") == map_id and (cell is None or (st.get("x"), st.get("y")) == tuple(cell)):
            return st
        time.sleep(0.25)
    raise Stuck(f"the client did not follow onto {map_id}")


def run_spot(spot: Spot, n: int, scan: dict, truth: dict, commands: Path, run, pilot: Pilot) -> dict:
    out = {"spot": spot.name, "map": spot.map, "check": spot.check}
    entry = truth.get((spot.map, spot.label))
    if entry is None:
        out["result"] = "no sweep case"
        return out
    case, rec = entry
    flags = {int(k): v for k, v in case["flags"].items()}
    vars_ = {int(k): v for k, v in case["vars"].items()}
    names = scan.get("names") or {}
    var_ids = {v: int(k) for k, v in (names.get("vars") or {}).items()}
    for name, value in spot.vars.items():
        vars_[var_ids[name]] = value
    if spot.trainers:
        for w in scan["maps"].get(spot.map, {}).get("world", []):
            for c in w.get("conds") or []:
                if c.get("k") == "trainer" and c.get("id") is not None:
                    flags[TRAINER_FLAGS_START + int(c["id"])] = True
        out["trainer_flags"] = sum(1 for k in flags if k >= TRAINER_FLAGS_START)
    x = y = None
    facing = "down"
    target = None
    if spot.check in ("enter", "refuse"):
        m = Gen3Map(spot.map)
        found = approach(m, rec, spot.cls)
        if found is None:
            out["result"] = f"no {spot.cls} cell with a cell to stand on beside it"
            return out
        (x, y), target, facing = found
        out["from"], out["into"], out["dir"] = [x, y], list(target), facing
    with open(commands, "a", encoding="utf-8") as f:
        f.write(lua_literal(n, spot.map, x, y, facing, flags, vars_, case.get("tide"), case.get("mirage")) + "\n")
    game = wait_spot(run, n)
    out["game"] = {k: game.get(k) for k in ("marker", "map", "x", "y", "cells", "drawn", "flash")}
    if game["marker"] != "KB_SPOT" or game.get("map") != spot.map:
        out["result"] = "the game didn't reach the spot"
        return out
    st = wait_followed(pilot, spot.map, (x, y) if x is not None else None)
    time.sleep(3.0)  # the client settles: the story cells and the weather fade
    if spot.check in ("enter", "refuse"):
        before = (st.get("move") or {}).copy()
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
            # refused by the hidden game, and by Minecraft too: no walk in and back (a snap or a blocked answer)
            out["result"] = "PASS" if not moved and snaps == 0 else "FAIL (rubber band)" if not moved else "FAIL"
    elif spot.check == "weather":
        drawn = WEATHER_NAMES.get(int(game["drawn"])) if str(game.get("drawn", "")).isdigit() else game.get("drawn")
        client = st.get("weather")
        out["weather"] = {"game": drawn, "client": client}
        out["result"] = ("PASS" if client in spot.expect and drawn in spot.expect else
                         "unknown (the client reports no weather)" if client is None else "FAIL")
    elif spot.check in ("dark", "lit"):
        dark, lit, map_dark, nv = st.get("dark"), st.get("lit"), st.get("map_dark"), st.get("night_vision")
        out["light"] = {"flash": game.get("flash"), "dark": dark, "lit": lit, "map_dark": map_dark, "night_vision": nv}
        if dark is None:
            out["result"] = "unknown (the client reports no darkness)"
        elif spot.check == "dark":
            # the world builds the map without lights and the hidden game says it is unlit
            out["result"] = "PASS" if map_dark and dark and not lit and not nv else "FAIL"
        else:
            # lit: the game's flash level is 0 there, so the client lights it (Night Vision on a dark-built map)
            out["result"] = "PASS" if map_dark and (lit or not dark) and nv else "FAIL"
    return out


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--world", default="ThreeRegionsE2E", help="the built world in local/minecraft/saves (copied)")
    parser.add_argument("--record", help="the .mp4 to write")
    parser.add_argument("--only", default="", help="these spots (comma-separated names)")
    parser.add_argument("--timeout", type=int, default=3600)
    args = parser.parse_args(argv)

    source = SAVES / args.world
    if not (source / "level.dat").is_file():
        print(f"no world {args.world}", file=sys.stderr)
        return 2
    scan, truth = load_truth()
    spots = [s for s in SPOTS if not args.only or s.name in args.only.split(",")]
    world = args.world + "Spots"
    if (SAVES / world).exists():
        shutil.rmtree(SAVES / world)
    shutil.copytree(source, SAVES / world)
    say("world copied:", world, "spots:", len(spots))

    home = make_home("spots", "emerald")
    game_env = {"KB_DATASET_MEMO": "30", "KB_PACE": "60", "KB_DRIVER_SECONDS": str(args.timeout + 600)}
    with launch("kb_union_off.lua", game="emerald", install=home, echo=False, extra_env=game_env) as off:
        say("union off:", off.wait(timeout=180).marker("KB_UNION_OFF"))
    PILOT_DIR.mkdir(parents=True, exist_ok=True)
    commands = PILOT_DIR / "game_commands.lua"
    commands.write_text("", encoding="utf-8")
    pilot = Pilot(PILOT_DIR)
    run = launch("kb_g3spots.lua", game="emerald", install=home, echo=False,
                 extra_env=dict(game_env, KB_TEST_PORT=str(PORT), KB_SPOT_COMMANDS=str(commands)))
    client = None
    results = []
    code = 1
    try:
        say("emerald:", run.wait_marker("KB_SERVING", timeout=300))
        java_home = os.environ.get("KB_JAVA_HOME") or os.environ.get("JAVA_HOME") or "/opt/java/openjdk"
        client = subprocess.Popen(client_command(world), cwd=GRADLE_DIR, env=dict(os.environ, JAVA_HOME=java_home),
                                  stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        threading.Thread(target=stream, args=(client, PILOT_DIR / "client.log"), daemon=True).start()
        deadline = time.monotonic() + 900
        while pilot.state().get("phase") != "ready":
            if time.monotonic() > deadline or client.poll() is not None:
                raise Stuck("the client never reached the world")
            time.sleep(1.0)
        say("client in the world; follow emerald:", pilot.do("follow", game="emerald").get("ok"))
        wait_followed(pilot, "EM_LITTLEROOT_TOWN", timeout=120)
        if args.record:
            pilot.do("record", file=args.record, timeout=30)
        for i, spot in enumerate(spots, 1):
            started = time.monotonic()
            try:
                out = run_spot(spot, i, scan, truth, commands, run, pilot)
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
        (PILOT_DIR / "emerald.log").write_text("\n".join(run.result.lines) + "\n", encoding="utf-8")
    (gsc.REPORTS / "g3_story_spots.json").write_text(json.dumps(results, indent=1, sort_keys=True), encoding="utf-8")
    passed = sum(1 for r in results if r.get("result") == "PASS")
    say(f"== {passed} of {len(results)} spots passed")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
