"""Records a showcase of the real game in the dev client (Wild Routes check C5): Viridian's clearing, the warp and a
walk through the zone, played by the client's Autopilot against the Gen1Recomp test instance. With --scene tour (the
caves and woods plan's CW5): a look round at each stop of drivers/kb_tour.lua (Mt. Moon, then Viridian Forest), each
recorded into its own file and joined into --record. With --scene pilot (the caves in 3D plan's H6): the autopilot
plans its shots from the world index (a cave's stairs to its raised ground and the cliff's edge, a ladder down and back
up) and drivers/kb_pilot.lua puts the hidden player where each starts; each shot is its own file, joined into --record.
--shots names the shots instead (H7's video): stairs:<map> or ladder:<map>, comma-separated.

    python tools/e2e/showcase.py --record /workspace/videos/wild-zone.mp4 [--world Kanto] [--port 27208]
    python tools/e2e/showcase.py --scene tour --record /workspace/videos/caves-woods.mp4 --world KantoCW
    python tools/e2e/showcase.py --scene pilot --record /workspace/videos/caves-3d.mp4
    python tools/e2e/showcase.py --scene pilot --maps ROCK_TUNNEL_1F,ROCK_TUNNEL_B1F \
        --shots ladder:ROCK_TUNNEL_1F --record /workspace/videos/rock-tunnel.mp4

Linux with an X server (Xvfb on the pod): DISPLAY is Minecraft's screen; --game-display gives Gen1Recomp its own, so
its window never covers Minecraft's in the recording.

1. Launches the test instance with drivers/kb_wild.lua: a new game without the intro, a starter, the player on the
   clearing's spot (gen1-bridge/data/routes.json), facing the trees, served at 60 frames a second (KB_PACE), with the
   engine's per-record cache check memoised (KB_DATASET_MEMO: on the pod's network disk it makes the export take a
   quarter of an hour instead of seconds).
2. Warms gen1-bridge's export with ExportCli, which also writes the bundle into local/minecraft/kantobridge/export/
   (tools/e2e/test_wild_routes.py's zone trainer reads it). Skipped when --world names a world that exists.
3. Runs the dev client with -Pkb.autopilot=wild_zone (client/dev/Autopilot.java), which creates (or opens) the world,
   records the scene and quits; local/reports/autopilot.log lists its steps.
4. Kills Gen1Recomp, which never saves.

WORLD_SESSIONS_PLAN WS0 (the user's save/reload bug in a wild route): --rejoin reconnect|relaunch runs the wild_zone
scene with the driver's save scene (a scratch slot; the hidden game saved once the hidden player stands on the route
map) and the autopilot's "save in the zone, leave the world, rejoin" (-Pkb.autopilotRejoin). With relaunch, once the
autopilot has left the world, Gen1Recomp is killed and launched again on the same data home with the driver's continue
scene (CONTINUE once the client binds), as a player restarting the game would. A last launch deletes the scratch slot.

Data safety (docs/PLAN.md section 0.3): Gen1Recomp's output passes run_gen1recomp's filter; of the client's output only
the autopilot's lines are printed, and of its errors only the exception classes; everything else is counted. The video
shows the user's own game: it is for the user, stays on the pod's volume, and the agent never opens it.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

from run_gen1recomp import INSTALL, LINUX_APP, launch, launch_plain, preflight  # noqa: E402

GRADLE_DIR = REPO / "fabric-bridge"
SAVES = REPO / "local" / "minecraft" / "saves"
AUTOPILOT_LOG = REPO / "local" / "reports" / "autopilot.log"
PILOT_DIR = REPO / "local" / "reports" / "pilot"
EXCEPTION = re.compile(r"\b([\w$.]+(?:Exception|Error))\b")


def spot() -> dict:
    entry = json.loads((REPO / "gen1-bridge" / "data" / "routes.json").read_text(encoding="utf-8"))["routes"][0]
    return entry["from"]


def entry_id() -> str:
    return json.loads((REPO / "gen1-bridge" / "data" / "routes.json").read_text(encoding="utf-8"))["routes"][0]["id"]


def slots_of(game) -> dict:
    line = game.result.marker("KB_SLOT")
    return dict(p.split("=", 1) for p in line.split()[1:]) if line else {}


def relauncher(game, args, slots, out):
    """WS0 relaunch: once the autopilot has left the world, kill Gen1Recomp and launch it again with the continue
    scene; the new launch is kept in out["game"] (the caller ends it)."""
    import threading

    def run():
        deadline = time.monotonic() + args.timeout
        while time.monotonic() < deadline:
            lines = AUTOPILOT_LOG.read_text(encoding="utf-8").splitlines() if AUTOPILOT_LOG.is_file() else []
            if any("autopilot: left_world" in line for line in lines):
                break
            time.sleep(1)
        else:
            return
        time.sleep(3)
        game.kill()
        print("relaunch: killed", json.dumps({k: v for k, v in game.wait(timeout=30).summary().items() if k != "markers"}),
              flush=True)
        env = {"KB_TEST_PORT": str(args.port), "KB_SCENE": "continue", "KB_ROUTE_MAP": "KB_ROUTE_" + entry_id().upper(),
               "KB_HOME_SLOT": f"slot{slots.get('home')}", "KB_SCRATCH_SLOT": f"slot{slots.get('slot')}",
               "KB_PACE": "60", "KB_DATASET_MEMO": "30", "KB_DRIVER_SECONDS": str(args.timeout)}
        ctx = launch("kb_wild.lua", game=args.game, extra_env=env, echo=False)
        second = ctx.__enter__()
        out["ctx"], out["game"] = ctx, second
        print("relaunch:", second.wait_marker("KB_BOOTED", timeout=240), flush=True)

    thread = threading.Thread(target=run, daemon=True)
    thread.start()
    return thread


def gradle(args: list[str], timeout: float, show) -> int:
    """Runs a Gradle task, printing only what show() returns for a line; the rest is counted."""
    java_home = os.environ.get("KB_JAVA_HOME") or os.environ.get("JAVA_HOME") or "/opt/java/openjdk"
    env = dict(os.environ, JAVA_HOME=java_home)
    command = ["./gradlew", "--no-daemon", "--console=plain", *args]
    proc = subprocess.Popen(command, cwd=GRADLE_DIR, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT)
    other = 0
    deadline = time.monotonic() + timeout
    try:
        for raw in iter(proc.stdout.readline, b""):
            line = raw.decode("utf-8", errors="replace").rstrip()
            shown = show(line)
            if shown:
                print(shown, flush=True)
            else:
                other += 1
            if time.monotonic() > deadline:
                print("gradle: timed out", flush=True)
                proc.kill()
                break
        code = proc.wait(timeout=60)
    finally:
        if proc.poll() is None:
            proc.kill()
    print(f"gradle {args[0]}: exit {code}, {other} other lines", flush=True)
    return code


def show_export(line: str) -> str | None:
    return line if line.startswith("export:") else None


def show_client(line: str) -> str | None:
    if "autopilot:" in line:
        return line[line.index("autopilot:"):]
    if "Exception" in line or "Error" in line or "FAILED" in line or "BUILD " in line:
        names = sorted(set(EXCEPTION.findall(line)))
        if names:
            return "client error: " + ", ".join(names)
        if line.startswith(("BUILD ", "FAILURE")):
            return line
    return None


def join_parts(record: Path, stops: int) -> bool:
    """Joins the tour's per-stop recordings (record-stop<n>.mp4) into record with ffmpeg's concat demuxer."""
    parts = [record.with_name(f"{record.stem}-stop{n}{record.suffix}") for n in range(1, stops + 1)]
    parts = [p for p in parts if p.is_file()]
    if not parts:
        return False
    listing = record.with_name(record.stem + "-parts.txt")
    listing.write_text("".join(f"file '{p.name}'\n" for p in parts), encoding="utf-8")
    code = subprocess.call(["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", str(listing), "-c", "copy",
                            str(record)])
    print(f"joined {len(parts)} parts: exit {code}", flush=True)
    return code == 0


COLD_DIR = REPO / "local" / "reports" / "ws7"


def engine_pids(game):
    """PIDs of running engines of ``game`` (their command lines name --game=<id>)."""
    out = subprocess.run(["pgrep", "-f", "--", f"--game={game}"], capture_output=True, text=True).stdout.split()
    return sorted(int(p) for p in out if int(p) != os.getpid())


BRIDGE_PORT = 27151


def cold_scene(args):
    """WORLD_SESSIONS_PLAN WS7, check C6: a launch.json of this run's own (the test install as Blue's install, the Linux
    AppImage as the command template), no engine running (or, with --external, a plain Blue started here), then the dev
    client with -Pkb.engines=minecraft opens --world: the mod starts Blue, it binds and continues, the autopilot quits
    through the choreography and waits for the engine's stop. Prints the autopilot's lines (each link's engine state, PID,
    reason), the engine processes and PID file before and after."""
    world = args.world
    if not world or not (SAVES / world).is_dir():
        print("--world must name a world (with a world record listing blue)", file=sys.stderr)
        return 2
    # A plain engine (no test driver) listens on gen1-bridge's own BRIDGE PORT setting (main.lua: default 27151, the
    # test install never changes it), as on a player's PC; --port is the drivers' port and doesn't apply here.
    port = BRIDGE_PORT
    COLD_DIR.mkdir(parents=True, exist_ok=True)
    launch_json = COLD_DIR / "launch.json"
    launch_json.write_text(json.dumps({
        "installs": {"kanto": {"dir": str(INSTALL), "games": ["blue"], "play": "blue"}},
        "ports": {"first": port},
        "engine": {"command": [str(LINUX_APP / "AppRun"), "--game={game}"], "cwd": str(LINUX_APP),
                   "env": {"XDG_DATA_HOME": "{installParent}", "ALSOFT_DRIVERS": "null", "POKEPORT_BACKGROUND": "1",
                           "KB_DATASET_MEMO": "30"}},
    }, indent=2) + "\n", encoding="utf-8")
    pid_file = INSTALL / "kb_engine.pid"
    print("before: engines", engine_pids("blue"), "pid file", pid_file.exists(), flush=True)
    AUTOPILOT_LOG.unlink(missing_ok=True)
    started = time.monotonic()
    external = None
    if args.external:
        external = launch_plain(game="blue", extra_env={"KB_DATASET_MEMO": "30"}, echo=False)
        time.sleep(45)  # the plain engine boots to its title and listens
        print("external engine:", external.proc.pid, flush=True)
    client = ["runClient", "-Pkb.autopilot=coldstart", "-Pkb.autopilotQuit=true", f"-Pkb.autopilotPort={port}",
              f"-Pkb.links=blue:{port}", f"-Pkb.autopilotWorld={world}", "-Pkb.engines=minecraft",
              f"-Pkb.enginesLaunch={launch_json}"]
    code = 1
    try:
        code = gradle(client, args.timeout, show_client)
        time.sleep(3)
        print("after: engines", engine_pids("blue"), "pid file", pid_file.exists(), flush=True)
        if external is not None:
            print("external alive:", external.proc.poll() is None, flush=True)
    finally:
        if external is not None:
            external.kill()
            external.wait(timeout=30)
    steps = AUTOPILOT_LOG.read_text(encoding="utf-8").splitlines() if AUTOPILOT_LOG.is_file() else []
    done = any(line.endswith("autopilot: done") for line in steps)
    print(f"showcase: {'done' if done else 'not done'} in {time.monotonic() - started:.0f} s, {len(steps)} autopilot steps")
    return 0 if done and code == 0 else 1


def create_scene(args):
    """WORLD_SESSIONS_PLAN WS6, check C5: every game of --games running with a saved scratch game (kb_ws3.lua offline, its
    own port), the dev client's "Create world" over all their links with the start --start (Autopilot scene create: the
    exports, one build of every region, the join). Prints the autopilot's lines (the record, the region and block the
    player stands on, every link's game, region, follow state and map), the world.json and each engine's markers; then
    each install's scratch slots are removed (kb_ws3.lua cleanup)."""
    import contextlib
    games = [g.strip() for g in args.games.split(",") if g.strip()]
    world = args.world or "WS6Create"
    if (SAVES / world).exists() and not args.keep_world:
        shutil.rmtree(SAVES / world)
    AUTOPILOT_LOG.unlink(missing_ok=True)
    started = time.monotonic()
    homes, runs, code = {}, {}, 1
    with contextlib.ExitStack() as stack:
        for i, game in enumerate(games):
            env = {"KB_TEST_PORT": str(args.port + i), "KB_DATASET_MEMO": "30", "KB_SCENE": "offline", "KB_WORLD": "kb-e2e-ws6",
                   "KB_DRIVER_SECONDS": str(args.timeout + args.export_timeout)}
            running = stack.enter_context(launch("kb_ws3.lua", game=game, extra_env=env, echo=False))
            offline = running.wait_marker("KB_OFFLINE", timeout=600)
            booted = running.result.marker("KB_BOOTED") or ""
            homes[game] = dict(part.split("=", 1) for part in booted.split()[1:] if "=" in part).get("home")
            print(game, "offline:", offline, flush=True)
            if offline is None:
                return 1
            runs[game] = running
        links = ",".join(f"{game}:{args.port + i}" for i, game in enumerate(games))
        client = ["runClient", "-Pkb.autopilot=create", "-Pkb.autopilotQuit=true", f"-Pkb.autopilotPort={args.port}",
                  f"-Pkb.links={links}", f"-Pkb.autopilotStart={args.start}", f"-Pkb.autopilotWorld={world}"]
        if args.client_heap:
            client.append(f"-Pkb.clientHeap={args.client_heap}")
        code = gradle(client, args.timeout + args.export_timeout, show_client)
        for game, running in runs.items():
            running.kill()
            result = running.wait(timeout=30)
            print(game, "actives:", [line for line in result.lines if line.startswith("KB_ACTIVE")][-3:], flush=True)
    for game in games:
        env = {"KB_TEST_PORT": str(args.port), "KB_SCENE": "cleanup", "KB_WORLD": "kb-e2e-ws6",
               **({"KB_HOME_SLOT": f"slot{homes[game]}"} if homes.get(game) else {})}
        with launch("kb_ws3.lua", game=game, extra_env=env, echo=False) as clean:
            print(game, "cleanup:", clean.wait(timeout=300).marker("KB_CLEANED"), flush=True)
    record = SAVES / world / "kantobridge" / "world.json"
    print("world.json:", record.read_text(encoding="utf-8").strip() if record.is_file() else None, flush=True)
    steps = AUTOPILOT_LOG.read_text(encoding="utf-8").splitlines() if AUTOPILOT_LOG.is_file() else []
    done = any(line.endswith("autopilot: done") for line in steps)
    print(f"showcase: {'done' if done else 'not done'} in {time.monotonic() - started:.0f} s, {len(steps)} autopilot steps")
    return 0 if done and code == 0 else 1


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--scene", choices=("wild_zone", "tour", "pilot", "create", "coldstart"), default="wild_zone",
                        help="the autopilot's scene (create: WORLD_SESSIONS_PLAN WS6's check C5, with --games/--start; "
                        "coldstart: WS7's check C6 on --world, the engines started by the mod from a launch.json of its own)")
    parser.add_argument("--external", action="store_true", help="with --scene coldstart: Blue started by this script first "
                        "(the mod must use it and leave it running)")
    parser.add_argument("--games", default="blue,crystal", help="with --scene create: the games run beside the client, "
                        "each on its own port from --port up, each with a saved scratch game (kb_ws3.lua offline)")
    parser.add_argument("--keep-world", action="store_true", help="with --scene create: open --world when it exists "
                        "(the join of a world made before) instead of making it again")
    parser.add_argument("--start", default="johto", help="with --scene create: the start region")
    parser.add_argument("--client-heap", default=None, help="the dev client's heap, e.g. 6g")
    parser.add_argument("--maps", default="MT_MOON_1F,MT_MOON_B1F,MT_MOON_B2F",
                        help="with --scene pilot: the cave's maps (their trainers are marked beaten; the first is the first stop)")
    parser.add_argument("--shots", default="",
                        help="with --scene pilot: stairs:<map> or ladder:<map>, comma-separated (default: a stairs and a ladder)")
    parser.add_argument("--stops", default="MT_MOON_1F,VIRIDIAN_FOREST", help="with --scene tour: the stops' map symbols")
    parser.add_argument("--stop-seconds", type=int, default=45, help="with --scene tour: each stop's time after the bind")
    parser.add_argument("--record", help="the .mp4 to write (default: no recording)")
    parser.add_argument("--world", help="a world folder in local/minecraft/saves to open instead of creating one")
    parser.add_argument("--port", type=int, default=27208, help="gen1-bridge's port for this run")
    parser.add_argument("--game", default=None, help="red or blue (default: the imported one)")
    parser.add_argument("--game-display", help="Gen1Recomp's X display (default: DISPLAY)")
    parser.add_argument("--export-timeout", type=int, default=3600)
    parser.add_argument("--timeout", type=int, default=5400, help="the client run's limit, seconds")
    parser.add_argument("--rejoin", choices=("reconnect", "relaunch", "clearing", "quit"),
                        help="WS0: save in the zone, leave, rejoin (clearing, WS1 C1: then the body put on the clearing; quit, "
                             "WS5: the game waits at its title with a save, the first join answers 'continue', the quit "
                             "choreography leaves, the rejoin continues it)")
    parser.add_argument("--fresh-session", action="store_true",
                        help="WS5: the world's kantobridge/session.json is moved aside for the run (a first join) and put back")
    args = parser.parse_args(argv)

    if args.scene == "create":
        return create_scene(args)
    if args.scene == "coldstart":
        return cold_scene(args)
    pf = preflight()
    if not pf.ok and not args.game:
        print("pre-flight not done: " + "; ".join(pf.reasons), file=sys.stderr)
        return 2
    tour = args.scene == "tour"
    pilot = args.scene == "pilot"
    if pilot:
        PILOT_DIR.mkdir(parents=True, exist_ok=True)
        for name in ("request.txt", "answer.txt"):
            (PILOT_DIR / name).unlink(missing_ok=True)
        env = {"KB_TEST_PORT": str(args.port), "KB_PILOT_MAPS": args.maps, "KB_PILOT_DIR": str(PILOT_DIR), "KB_PACE": "60",
               "KB_DATASET_MEMO": "30", "KB_DRIVER_SECONDS": str(args.timeout + args.export_timeout)}
    elif tour:
        env = {"KB_TEST_PORT": str(args.port), "KB_TOUR_STOPS": args.stops, "KB_TOUR_SECONDS": str(args.stop_seconds),
               "KB_PACE": "60", "KB_DATASET_MEMO": "30", "KB_DRIVER_SECONDS": str(args.timeout + args.export_timeout)}
    else:
        f = spot()
        if f.get("x") is None:
            print("routes.json has no spot for the zone", file=sys.stderr)
            return 2
        env = {"KB_TEST_PORT": str(args.port), "KB_SPOT_MAP": f["map"], "KB_SPOT_X": str(f["x"]), "KB_SPOT_Y": str(f["y"]),
               "KB_SPOT_FACING": f["facing"], "KB_PACE": "60", "KB_DATASET_MEMO": "30",
               "KB_DRIVER_SECONDS": str(args.timeout + args.export_timeout)}
        if args.rejoin:
            env.update({"KB_SCENE": "titlesave" if args.rejoin == "quit" else "save",
                        "KB_ROUTE_MAP": "KB_ROUTE_" + entry_id().upper()})
    if args.game_display:
        env["DISPLAY"] = args.game_display
    AUTOPILOT_LOG.unlink(missing_ok=True)
    started = time.monotonic()
    driver, marker = ("kb_pilot.lua", "KB_PILOT_READY") if pilot else ("kb_tour.lua", "KB_TOUR_STOP") if tour else (
        "kb_wild.lua", "KB_TITLESAVE" if args.rejoin == "quit" else "KB_WILD_READY")
    moved = None
    if args.fresh_session and args.world:
        record = SAVES / args.world / "kantobridge" / "session.json"
        if record.is_file():
            moved = record.with_name("session.json.ws5bak")
            record.rename(moved)
            print("session.json moved aside", flush=True)
    with launch(driver, game=args.game, extra_env=env, echo=False) as game:
        ready = game.wait_marker(marker, timeout=240)
        print(ready or f"Gen1Recomp: no {marker} " + json.dumps(game.result.summary()), flush=True)
        if ready is None:
            return 1
        installed = [line for line in game.result.lines if "routes.installed" in line or "routes.skipped" in line]
        print("routes:", installed, flush=True)
        if pilot:
            print("trainers:", [line for line in game.result.lines if line.startswith("KB_PILOT_TRAINERS")], flush=True)
        world_exists = bool(args.world) and (SAVES / args.world).is_dir()
        if not world_exists:
            t0 = time.monotonic()
            code = gradle(["exportCli", f"-Pkb.exportPort={args.port}", f"-Pkb.exportTimeout={args.export_timeout}"],
                          args.export_timeout + 300, show_export)
            print(f"export warmed in {time.monotonic() - t0:.0f} s", flush=True)
            if code != 0:
                return 1
        client = ["runClient", f"-Pkb.autopilot={args.scene}", "-Pkb.autopilotQuit=true", f"-Pkb.autopilotPort={args.port}"]
        if tour:
            client.append(f"-Pkb.autopilotStops={len(args.stops.split(','))}")
        if pilot:
            client.append(f"-Pkb.autopilotPilotDir={PILOT_DIR}")
            if args.shots:
                client.append(f"-Pkb.autopilotShots={args.shots}")
        if args.record:
            client.append(f"-Pkb.autopilotRecord={args.record}")
        if world_exists:
            client.append(f"-Pkb.autopilotWorld={args.world}")
        slots, second = slots_of(game), {}
        if args.rejoin:
            client.append(f"-Pkb.autopilotRejoin={args.rejoin}")
            print("slots:", slots, flush=True)
            if args.rejoin == "relaunch":
                relauncher(game, args, slots, second)
        code = gradle(client, args.timeout, show_client)
        game.kill()
        result = game.wait(timeout=30)
        if second.get("game") is not None:
            g2 = second["game"]
            print("relaunch markers:", [line for line in g2.result.lines if line.startswith(
                ("KB_LOADED", "KB_CONTINUED", "KB_MAP_ENTERED"))], flush=True)
            g2.kill()
            g2.wait(timeout=30)
            second["ctx"].__exit__(None, None, None)
        if args.rejoin:
            print("save:", result.marker("KB_SAVED"), flush=True)
            if slots.get("slot") and slots.get("slot") != slots.get("home"):
                with launch("kb_wild.lua", game=args.game, echo=False, extra_env={
                        "KB_TEST_PORT": str(args.port), "KB_SCENE": "cleanup", "KB_HOME_SLOT": f"slot{slots['home']}",
                        "KB_SCRATCH_SLOT": f"slot{slots['slot']}"}) as clean:
                    print("cleanup:", clean.wait(timeout=180).marker("KB_CLEANED"), flush=True)
    if moved is not None:
        record = moved.with_name("session.json")
        record.unlink(missing_ok=True)
        moved.rename(record)
        print("session.json put back", flush=True)
    if args.rejoin == "quit":
        print("driver:", [line for line in result.lines if line.startswith(("KB_TITLESAVE", "KB_INGAME"))], flush=True)
    print("Gen1Recomp:", json.dumps({k: v for k, v in result.summary().items() if k != "markers"}), flush=True)
    steps = AUTOPILOT_LOG.read_text(encoding="utf-8").splitlines() if AUTOPILOT_LOG.is_file() else []
    done = any(line.endswith("autopilot: done") for line in steps)
    if tour and args.record:
        done &= join_parts(Path(args.record), len(args.stops.split(",")))
    if pilot:
        print("driver:", [line for line in result.lines if line.startswith(("KB_PILOT_REQUEST", "KB_PILOT_ARRIVED"))], flush=True)
        if args.record:
            done &= join_parts(Path(args.record), len(args.shots.split(",")) if args.shots else 2)
    print(f"showcase: {'done' if done else 'not done'} in {time.monotonic() - started:.0f} s, {len(steps)} autopilot steps")
    return 0 if done and code == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
