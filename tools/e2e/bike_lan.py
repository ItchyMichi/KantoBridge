"""BIKE_PLAN B7: two players on a LAN world, the second client recording the first player's bike. Player 1 hosts (opened
to LAN) and follows one hidden Emerald, put on the Mach Bike on Route 110 and ridden from the pilot's keys (W), then on
the Acro in a wheelie; player 2 ("Player2", game folder local/minecraft-p2, run from --p2-repo) joins over LAN and follows a
second hidden Emerald, put a few cells behind on foot, looking along the lane: its screen is recorded, and its state says
how many other bikes it sees and of which kind.

    DISPLAY=:99 KB_DATASET_MEMO=30 python tools/e2e/bike_lan.py --world LandSky6 --p2-repo /root/kantobridge-p2 \\
        --record /workspace/videos/bike/b7_second_client.mp4

Output: map symbols, cells, counts, kinds; local/reports/g3_bike_lan.json.
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

from field_spots import SAVES, inside, lua_go, stream, wait_spot  # noqa: E402
from run_gen1recomp import REPORTS, launch  # noqa: E402
from story_video import Pilot, Stuck, make_home, say  # noqa: E402

REPO = HERE.parents[1]
LAN_PORT = 25598
PORTS = {1: 27260, 2: 27261}


def client_command(n: int, world: str, pilot_dir: Path) -> list[str]:
    args = ["./gradlew", "--no-daemon", "--console=plain", "runClient", "-Pkb.autopilot=remote", f"-Pkb.autopilotWorld={world}",
            f"-Pkb.autopilotPilotDir={pilot_dir}", "-Pkb.autopilotFollow=emerald", "-Pkb.autopilotQuit=true",
            f"-Pkb.links=emerald:{PORTS[n]}"]
    if n == 2:
        args += [f"-Pkb.autopilotServer=127.0.0.1:{LAN_PORT}", "-Pkb.clientRunDir=../local/minecraft-p2", "-Pkb.clientUser=Player2"]
    return args


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--world", default="LandSky6")
    ap.add_argument("--p2-repo", required=True)
    ap.add_argument("--record", required=True, help="player 2's screen")
    ap.add_argument("--display1", default=os.environ.get("DISPLAY", ":99"))
    ap.add_argument("--display2", default=":98")
    a = ap.parse_args(argv)
    world = a.world + "BikeLan"
    if (SAVES / world).exists():
        shutil.rmtree(SAVES / world)
    shutil.copytree(SAVES / a.world, SAVES / world)
    env = {"KB_DATASET_MEMO": "30", "KB_PACE": "60", "KB_DRIVER_SECONDS": "3600"}
    java_home = os.environ.get("KB_JAVA_HOME") or "/opt/java/openjdk"
    runs, clients, pilots, commands = {}, {}, {}, {}
    report = {}
    try:
        for n in (1, 2):
            home = make_home(f"bike_lan_{n}", "emerald")
            with launch("kb_union_off.lua", game="emerald", install=home, echo=False, extra_env=env) as off:
                say(f"game {n} union off:", off.wait(timeout=180).marker("KB_UNION_OFF"))
            commands[n] = REPORTS / f"bike_lan_p{n}" / "game_commands.lua"
            commands[n].parent.mkdir(parents=True, exist_ok=True)
            commands[n].write_text("", encoding="utf-8")
            runs[n] = launch("kb_g3spots.lua", game="emerald", install=home, echo=False,
                             extra_env=dict(env, KB_TEST_PORT=str(PORTS[n]), KB_SPOT_COMMANDS=str(commands[n])))
        for n in (1, 2):
            say(f"game {n}:", runs[n].wait_marker("KB_SERVING", timeout=300))
            with open(commands[n], "a", encoding="utf-8") as f:
                f.write('{ id = 90, op = "sight", on = false }\n')
        pilots[1] = Pilot((REPORTS / "bike_lan_p1" / "pilot").resolve())
        clients[1] = subprocess.Popen(client_command(1, world, (REPORTS / "bike_lan_p1" / "pilot").resolve()), cwd=REPO / "fabric-bridge",
                                      env=dict(os.environ, JAVA_HOME=java_home, DISPLAY=a.display1), stdin=subprocess.DEVNULL,
                                      stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        threading.Thread(target=stream, args=(clients[1], REPORTS / "bike_lan_p1" / "client.log"), daemon=True).start()
        deadline = time.monotonic() + 1200
        while pilots[1].state().get("phase") != "ready":
            if time.monotonic() > deadline or clients[1].poll() is not None:
                raise Stuck("player 1 never reached the world")
            time.sleep(1.0)
        say("player 1 in; LAN:", pilots[1].do("lan", port=LAN_PORT, timeout=60).get("ok"))
        p2_dir = (REPORTS / "bike_lan_p2" / "pilot").resolve()
        pilots[2] = Pilot(p2_dir)
        clients[2] = subprocess.Popen(client_command(2, world, p2_dir), cwd=Path(a.p2_repo) / "fabric-bridge",
                                      env=dict(os.environ, JAVA_HOME=java_home, DISPLAY=a.display2), stdin=subprocess.DEVNULL,
                                      stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        threading.Thread(target=stream, args=(clients[2], REPORTS / "bike_lan_p2" / "client.log"), daemon=True).start()
        deadline = time.monotonic() + 1200
        while pilots[2].state().get("phase") != "ready":
            if time.monotonic() > deadline or clients[2].poll() is not None:
                raise Stuck("player 2 never joined")
            time.sleep(1.0)
        say("player 2 joined")
        for n in (1, 2):
            say(f"player {n} follow:", pilots[n].do("follow", game="emerald", timeout=60).get("ok"))
        time.sleep(15)
        # player 1 on the Mach at the lane's start, player 2 on foot five cells behind
        spots = {1: (4, 36, "up", "mach"), 2: (4, 41, "up", None)}
        for n, (x, y, facing, vehicle) in spots.items():
            spot = {"map": "EM_ROUTE110", "x": x, "y": y, "dir": facing, "vehicle": vehicle}
            with open(commands[n], "a", encoding="utf-8") as f:
                f.write(lua_go(100 + n, "emerald", spot) + "\n")
        for n in (1, 2):
            wait_spot(runs[n], 100 + n, timeout=120)
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline and not (inside(pilots[1].state()) and inside(pilots[2].state())
                                                   and pilots[1].state().get("ride") and pilots[2].state().get("other_bikes")):
            time.sleep(0.5)
        st1, st2 = pilots[1].state(), pilots[2].state()
        report["seated"] = bool(st1.get("ride"))
        report["p2_sees_bikes"] = st2.get("other_bikes")
        say("player 1 seated:", report["seated"], "player 2 sees bikes:", report["p2_sees_bikes"])
        pilots[2].do("camera", mode="first", hud=False)
        pilots[2].do("face", dir="up", pitch=10)
        pilots[1].do("face", dir="up", pitch=10)
        time.sleep(3)
        pilots[2].do("record", file=a.record, timeout=30)
        time.sleep(2)
        p0 = (st1.get("ride") or {})
        pilots[1].do("keys", keys=["forward"], hold=30, timeout=30)
        time.sleep(4)
        st1 = pilots[1].state()
        report["mach_moved_blocks"] = round(abs((st1.get("ride") or {}).get("z", 0) - p0.get("z", 0)), 1)
        report["p2_sees_after_ride"] = pilots[2].state().get("other_bikes")
        # the Acro, back at the start, in a wheelie up the lane
        spot = {"map": "EM_ROUTE110", "x": 4, "y": 36, "dir": "up", "vehicle": "acro"}
        with open(commands[1], "a", encoding="utf-8") as f:
            f.write(lua_go(103, "emerald", spot) + "\n")
        wait_spot(runs[1], 103, timeout=120)
        time.sleep(5)
        pilots[1].do("keys", keys=["sneak"], hold=10, timeout=30)
        pilots[1].do("keys", keys=["forward", "sneak"], hold=30, timeout=30)
        time.sleep(3)
        pilots[1].do("keys", keys=["sneak"], hold=60, timeout=30)  # a bunny hop in place, seen from behind
        time.sleep(3)
        report["p2_final"] = {k: pilots[2].state().get(k) for k in ("other_bikes", "map", "x", "y")}
        report["p1_final"] = {"ride": pilots[1].state().get("ride"), "cell": [st1.get("map"), st1.get("x"), st1.get("y")]}
        say(json.dumps(report))
    except Stuck as e:
        say("STUCK:", e)
        report["stuck"] = str(e)
    finally:
        for n in (2, 1):
            pilot = pilots.get(n)
            if pilot is not None:
                try:
                    pilot.do("stop", timeout=60)
                    pilot.send("quit")
                except Stuck:
                    pass
            client = clients.get(n)
            if client is not None:
                try:
                    client.wait(timeout=120)
                except subprocess.TimeoutExpired:
                    client.kill()
        for n, run in runs.items():
            run.kill()
            run.wait(timeout=30)
    (REPORTS / "g3_bike_lan.json").write_text(json.dumps(report, indent=1, sort_keys=True), encoding="utf-8")
    say("== report", REPORTS / "g3_bike_lan.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
