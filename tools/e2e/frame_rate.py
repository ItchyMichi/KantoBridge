"""Frame-rate test (docs/specs/LANDSCAPE_MODELS.md): the dev client's meshstats at fixed viewpoints of a built world.

    python tools/e2e/frame_rate.py --world LandModels --spots spots.json [--distances 16,32] [--out report.json]

<spots.json>: {"<name>": {"region": "kanto", "map": "ROUTE_23", "x": 9, "y": 51, "yaw": 180, "pitch": -20}, ...}: a
map cell (the camera stands on it) in a region of the world; yaw 180 looks north. Per render distance, per spot, the
dev client (RemotePilot: "render", "tp", "wait", "measure") stands there, lets the view settle, then meshstats times a
full re-mesh and 30 s of frames (mean and 1% low frame rate, heap). The runs go to --out: numbers only.

The client runs on DISPLAY, which must render on the GPU for the numbers to mean anything (a pod's Xvfb renders in
software: run the whole script under VirtualGL, `vglrun -d egl`, on a GPU pod). No game is linked: the client only
looks at the world. The world is copied first (<world>Perf), so the test never touches the built one.
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
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

from region_shots import wait_for_art  # noqa: E402
from story_spots import stream  # noqa: E402
from story_video import Pilot, Stuck, say  # noqa: E402

REPO = HERE.parents[1]
GRADLE_DIR = REPO / "fabric-bridge"
SAVES = REPO / "local" / "minecraft" / "saves"
PILOT_DIR = REPO / "local" / "reports" / "frame_rate"
MESH_REPORT = REPO / "local" / "reports" / "mesh_report.json"


def viewpoint(world: Path, spot: dict) -> tuple[float, float, float]:
    """The block position of a spot's cell centre, at its map's feet level, from the region's world index."""
    index = json.loads((world / "kantobridge" / "regions" / spot["region"] / "world_index.json").read_text(encoding="utf-8"))
    p = index["maps"][spot["map"]]
    return p["x"] + 2 * spot["x"] + 1.0, float(p["y"]), p["z"] + 2 * spot["y"] + 1.0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--world", required=True)
    ap.add_argument("--spots", required=True)
    ap.add_argument("--distances", default="16,32")
    ap.add_argument("--settle", type=float, default=10.0, help="seconds the view settles before a measurement")
    ap.add_argument("--out", default=str(REPO / "local" / "reports" / "frame_rate.json"))
    args = ap.parse_args(argv)
    spots = json.loads(Path(args.spots).read_text(encoding="utf-8"))
    world = args.world + "Perf"
    if (SAVES / world).exists():
        shutil.rmtree(SAVES / world)
    shutil.copytree(SAVES / args.world, SAVES / world)
    places = {name: viewpoint(SAVES / world, spot) for name, spot in spots.items()}
    PILOT_DIR.mkdir(parents=True, exist_ok=True)
    if MESH_REPORT.exists():
        MESH_REPORT.unlink()
    java_home = os.environ.get("KB_JAVA_HOME") or os.environ.get("JAVA_HOME") or "/opt/java/openjdk"
    client = subprocess.Popen(["./gradlew", "--no-daemon", "--console=plain", "runClient", "-Pkb.autopilot=remote",
                               f"-Pkb.autopilotWorld={world}", f"-Pkb.autopilotPilotDir={PILOT_DIR}", "-Pkb.autopilotQuit=true",
                               "-Pkb.meshstats=true"],
                              cwd=GRADLE_DIR, env=dict(os.environ, JAVA_HOME=java_home), stdin=subprocess.DEVNULL,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    threading.Thread(target=stream, args=(client, PILOT_DIR / "client.log"), daemon=True).start()
    order = []
    code = 1
    try:
        pilot = Pilot(PILOT_DIR)
        deadline = time.monotonic() + 900
        while pilot.state().get("phase") != "ready":
            if time.monotonic() > deadline or client.poll() is not None:
                raise Stuck("the client never reached the world")
            time.sleep(1.0)
        wait_for_art(PILOT_DIR / "client.log")
        for distance in [int(d) for d in args.distances.split(",")]:
            pilot.do("render", distance=distance, timeout=10)
            for name, spot in spots.items():
                x, y, z = places[name]
                pilot.do("tp", x=x, y=y, z=z, yaw=spot.get("yaw", 180), pitch=spot.get("pitch", -20), timeout=10)
                pilot.do("wait", ticks=int(args.settle * 20), timeout=args.settle + 30)
                ok = pilot.do("measure", timeout=300).get("ok")
                order.append({"spot": name, "distance": distance, "measured": bool(ok)})
                say(name, distance, "measured" if ok else "not measured")
        code = 0
    except Stuck as e:
        say("STUCK:", e)
    finally:
        try:
            pilot.do("quit", timeout=30)
        except (Stuck, NameError):
            pass
        try:
            client.wait(timeout=180)
        except subprocess.TimeoutExpired:
            client.kill()
    runs = json.loads(MESH_REPORT.read_text(encoding="utf-8")) if MESH_REPORT.exists() else {}
    Path(args.out).write_text(json.dumps({"world": args.world, "order": order, "meshstats": runs}, indent=1), encoding="utf-8")
    say(f"== {sum(1 for o in order if o['measured'])} of {len(order)} measured; {args.out}")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
