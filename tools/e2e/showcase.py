"""Records a showcase of the real game in the dev client (Wild Routes check C5): Viridian's clearing, the warp and a
walk through the zone, played by the client's Autopilot against the Gen1Recomp test instance.

    python tools/e2e/showcase.py --record /workspace/videos/wild-zone.mp4 [--world Kanto] [--port 27208]

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
EXCEPTION = re.compile(r"\b([\w$.]+(?:Exception|Error))\b")


def spot() -> dict:
    entry = json.loads((REPO / "gen1-bridge" / "data" / "routes.json").read_text(encoding="utf-8"))["routes"][0]
    return entry["from"]


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


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--record", help="the .mp4 to write (default: no recording)")
    parser.add_argument("--world", help="a world folder in local/minecraft/saves to open instead of creating one")
    parser.add_argument("--port", type=int, default=27208, help="gen1-bridge's port for this run")
    parser.add_argument("--game", default=None, help="red or blue (default: the imported one)")
    parser.add_argument("--game-display", help="Gen1Recomp's X display (default: DISPLAY)")
    parser.add_argument("--export-timeout", type=int, default=3600)
    parser.add_argument("--timeout", type=int, default=5400, help="the client run's limit, seconds")
    args = parser.parse_args(argv)

    pf = preflight()
    if not pf.ok and not args.game:
        print("pre-flight not done: " + "; ".join(pf.reasons), file=sys.stderr)
        return 2
    f = spot()
    if f.get("x") is None:
        print("routes.json has no spot for the zone", file=sys.stderr)
        return 2
    env = {"KB_TEST_PORT": str(args.port), "KB_SPOT_MAP": f["map"], "KB_SPOT_X": str(f["x"]), "KB_SPOT_Y": str(f["y"]),
           "KB_SPOT_FACING": f["facing"], "KB_PACE": "60", "KB_DATASET_MEMO": "30",
           "KB_DRIVER_SECONDS": str(args.timeout + args.export_timeout)}
    if args.game_display:
        env["DISPLAY"] = args.game_display
    AUTOPILOT_LOG.unlink(missing_ok=True)
    started = time.monotonic()
    with launch("kb_wild.lua", game=args.game, extra_env=env, echo=False) as game:
        ready = game.wait_marker("KB_WILD_READY", timeout=240)
        print(ready or "Gen1Recomp: no KB_WILD_READY " + json.dumps(game.result.summary()), flush=True)
        if ready is None:
            return 1
        installed = [line for line in game.result.lines if "routes.installed" in line or "routes.skipped" in line]
        print("routes:", installed, flush=True)
        world_exists = bool(args.world) and (SAVES / args.world).is_dir()
        if not world_exists:
            t0 = time.monotonic()
            code = gradle(["exportCli", f"-Pkb.exportPort={args.port}", f"-Pkb.exportTimeout={args.export_timeout}"],
                          args.export_timeout + 300, show_export)
            print(f"export warmed in {time.monotonic() - t0:.0f} s", flush=True)
            if code != 0:
                return 1
        client = ["runClient", "-Pkb.autopilot=wild_zone", "-Pkb.autopilotQuit=true", f"-Pkb.autopilotPort={args.port}"]
        if args.record:
            client.append(f"-Pkb.autopilotRecord={args.record}")
        if world_exists:
            client.append(f"-Pkb.autopilotWorld={args.world}")
        code = gradle(client, args.timeout, show_client)
        game.kill()
        result = game.wait(timeout=30)
    print("Gen1Recomp:", json.dumps({k: v for k, v in result.summary().items() if k != "markers"}), flush=True)
    steps = AUTOPILOT_LOG.read_text(encoding="utf-8").splitlines() if AUTOPILOT_LOG.is_file() else []
    done = any(line.endswith("autopilot: done") for line in steps)
    print(f"showcase: {'done' if done else 'not done'} in {time.monotonic() - started:.0f} s, {len(steps)} autopilot steps")
    return 0 if done and code == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
