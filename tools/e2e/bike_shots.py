"""The bike's look in the real client (docs/specs/BIKE_PLAN.md B4): the hidden player put on a bike by the game's spots
driver (`vehicle`), the client seated on the bike entity, then screenshots in first person, from behind and from the side,
per bike kind (Emerald's Mach and Acro, Blue's and Crystal's one bike), and a sheet of them.

    DISPLAY=:99 KB_DATASET_MEMO=30 python tools/e2e/bike_shots.py --game emerald --world LandSky6 --out /workspace/videos/bike/shots

The pictures show the user's own game world: they stay on the pod's volume (and go to the user). Output lines: map
symbols, cells, counts.
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

from context_shots import grab  # noqa: E402
from field_spots import GAMES, GRADLE_DIR, SAVES, client_command, inside, stream, wait_spot  # noqa: E402
from run_gen1recomp import REPORTS, launch  # noqa: E402
from story_video import Pilot, Stuck, make_home, say  # noqa: E402

# (label, map, x, y, facing, vehicle); x None: the map's first warp's cell
SPOTS = {
    "emerald": [("mach", "EM_ROUTE110", 4, 41, "up", "mach"), ("acro", "EM_ROUTE110", 4, 36, "up", "acro")],
    "blue": [("plain", "ROUTE_1", None, None, "up", "bike")],
    "crystal": [("plain", "ROUTE_29", None, None, "left", "bike")],
}
SIDE = {"up": "right", "down": "left", "left": "up", "right": "down"}


def go_line(n: int, game: str, m: str, x, y, facing: str, vehicle: str) -> str:
    parts = [f"id = {n}", 'op = "go"', f'map = "{m}"', f'facing = "{facing}"', f'vehicle = "{vehicle}"']
    if x is not None:
        parts += [f"x = {x}", f"y = {y}"]
    if game == "crystal":
        parts += ["events = { }", "flags = { }", "scenes = { }", "mapscenes = { }", "mem = { }"]
    elif game == "blue":
        parts += ["flags = { }"]
    else:
        parts += ["flags = { }", "vars = { }"]
    return "{ " + ", ".join(parts) + " }"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--game", required=True, choices=sorted(SPOTS))
    ap.add_argument("--world", default="LandSky6")
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    g = GAMES[a.game]
    out = Path(a.out) / a.game
    out.mkdir(parents=True, exist_ok=True)
    source = SAVES / a.world
    world = a.world + "Bike" + g["prefix"].upper()
    if (SAVES / world).exists():
        shutil.rmtree(SAVES / world)
    shutil.copytree(source, SAVES / world)
    pilot_dir = REPORTS / f"bike_shots_{g['prefix']}"
    pilot_dir.mkdir(parents=True, exist_ok=True)
    home = make_home(f"bike_shots_{g['prefix']}", a.game)
    env = {"KB_DATASET_MEMO": "30", "KB_PACE": "60", "KB_DRIVER_SECONDS": "3000"}
    with launch("kb_union_off.lua", game=a.game, install=home, echo=False, extra_env=env) as off:
        say("union off:", off.wait(timeout=180).marker("KB_UNION_OFF"))
    commands = pilot_dir / "game_commands.lua"
    commands.write_text("", encoding="utf-8")
    pilot = Pilot(pilot_dir)
    run = launch(g["driver"], game=a.game, install=home, echo=False,
                 extra_env=dict(env, KB_TEST_PORT=str(g["port"]), KB_SPOT_COMMANDS=str(commands)))
    client = None
    results = []
    display = os.environ.get("DISPLAY", ":99")
    try:
        say(a.game + ":", run.wait_marker("KB_SERVING", timeout=300))
        java_home = os.environ.get("KB_JAVA_HOME") or "/opt/java/openjdk"
        client = subprocess.Popen(client_command(a.game, world, pilot_dir, g["port"]), cwd=GRADLE_DIR,
                                  env=dict(os.environ, JAVA_HOME=java_home), stdin=subprocess.DEVNULL,
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        threading.Thread(target=stream, args=(client, pilot_dir / "client.log"), daemon=True).start()
        deadline = time.monotonic() + 1200
        while pilot.state().get("phase") != "ready":
            if time.monotonic() > deadline or client.poll() is not None:
                raise Stuck("the client never reached the world")
            time.sleep(1.0)
        say("follow:", pilot.do("follow", game=a.game).get("ok"))
        time.sleep(20)
        for i, (label, m, x, y, facing, vehicle) in enumerate(SPOTS[a.game], 1):
            with open(commands, "a", encoding="utf-8") as f:
                f.write(go_line(i, a.game, m, x, y, facing, vehicle) + "\n")
            spot = wait_spot(run, i)
            deadline = time.monotonic() + 60
            st = pilot.state()
            while time.monotonic() < deadline and not (st.get("ride") and inside(st)):
                time.sleep(0.5)
                st = pilot.state()
            res = {"label": label, "spot": [spot.get("map"), spot.get("x"), spot.get("y")], "seated": bool(st.get("ride")),
                   "ride": st.get("ride")}
            time.sleep(4)
            shots = []
            for view, cam, look, pitch in (("first", "first", facing, 55), ("side", "third", SIDE[facing], 15),
                                           ("other_side", "third", SIDE[SIDE[SIDE[facing]]], 15)):
                pilot.do("camera", mode=cam, hud=False)
                pilot.do("face", dir=look, pitch=pitch)
                time.sleep(2.5)
                p = out / f"{a.game}_{label}_{view}.png"
                if grab(display, p, False):
                    shots.append(str(p))
            res["shots"] = shots
            results.append(res)
            say(json.dumps({k: res[k] for k in ("label", "spot", "seated", "ride")}))
        pilot.do("camera", mode="first", hud=True)
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
                client.wait(timeout=180)
            except subprocess.TimeoutExpired:
                client.kill()
        run.kill()
        run.wait(timeout=30)
    (out / "shots.json").write_text(json.dumps(results, indent=1), encoding="utf-8")
    say("== shots:", sum(len(r["shots"]) for r in results), "seated:", sum(1 for r in results if r["seated"]), "of", len(results))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
