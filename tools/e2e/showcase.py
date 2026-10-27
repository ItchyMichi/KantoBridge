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
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

from run_gen1recomp import launch, preflight  # noqa: E402

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


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--scene", choices=("wild_zone", "tour", "pilot"), default="wild_zone", help="the autopilot's scene")
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
