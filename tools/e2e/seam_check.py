"""What the real dev client does at a seam of a region's surface (the user's item 5, 2026-10-14; docs/
SEVERAL_GAMES_FOLLOWUPS.md): where two neighbouring maps' connection disagrees with the layout (Hoenn's loops), the
hidden player crosses by the connection while the Minecraft player walks into the neighbour's placement. Emerald starts
a new game on the given cell (drivers/kb_emerald.lua's spot scene), the dev client follows it (RemotePilot) and steps
the given way; after each step and after a pause this prints the hidden map and cell, the player's block, the hidden
cell's block and the movement sync's counters (steps, pushes, blocked, snaps, drifts, steers, teleports, jumps), and
the people shown on the map with the sprites the pack had no sheet for (the follow-up's item 8).

    xvfb-run -a -s "-screen 0 1280x720x24" python tools/e2e/seam_check.py --world PassE2E \\
        --map EM_FALLARBOR_TOWN --x 2 --y 9 --dir left --steps 5 [--game-display :98]

Map symbols, cells, block coordinates and counts only.
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

import story_video as sv  # noqa: E402
from run_gen1recomp import launch  # noqa: E402


def summary(st):
    p, h = st.get("player") or {}, st.get("hidden_block") or {}
    off = None
    if p and h:
        off = (round(p["x"] - (h["x"] + 1.0), 1), round(p["z"] - (h["z"] + 1.0), 1))
    return (f"{st.get('map')} ({st.get('x')}, {st.get('y')}) facing {st.get('facing')} mode {st.get('mode')} "
            f"player-minus-hidden {off} move {json.dumps(st.get('move'))} puppets {json.dumps(st.get('puppets'))}")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--world", default="PassE2E")
    parser.add_argument("--map", required=True)
    parser.add_argument("--x", type=int, required=True)
    parser.add_argument("--y", type=int, required=True)
    parser.add_argument("--dir", required=True, choices=sorted(sv.DELTA))
    parser.add_argument("--steps", type=int, default=5)
    parser.add_argument("--game-display")
    args = parser.parse_args(argv)

    world = args.world + "Seam"
    if (sv.SAVES / world).exists():
        shutil.rmtree(sv.SAVES / world)
    shutil.copytree(sv.SAVES / args.world, sv.SAVES / world)
    home = sv.make_home("seam", "emerald")
    env = {"KB_DATASET_MEMO": "30", "KB_DRIVER_SECONDS": "1800", "KB_TEST_PORT": str(sv.EMERALD_PORT), "KB_SCENE": "spot",
           "KB_SPOT_MAP": args.map, "KB_SPOT_X": str(args.x), "KB_SPOT_Y": str(args.y), "KB_SPOT_FACING": args.dir}
    if args.game_display:
        env["DISPLAY"] = args.game_display
    with launch("kb_union_off.lua", game="emerald", install=home, echo=False, extra_env=env) as off:
        sv.say("union off", off.wait(timeout=180).marker("KB_UNION_OFF"))
    pilot = sv.Pilot(sv.PILOT_DIR)
    emerald = launch("kb_emerald.lua", game="emerald", install=home, echo=False, extra_env=env)
    client = None
    code = 1
    try:
        sv.say("emerald:", emerald.wait_marker("KB_SERVING", timeout=300))
        java_home = os.environ.get("KB_JAVA_HOME") or os.environ.get("JAVA_HOME") or "/opt/java/openjdk"
        client = subprocess.Popen(sv.client_command(world), cwd=sv.GRADLE_DIR, env=dict(os.environ, JAVA_HOME=java_home),
                                  stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        threading.Thread(target=sv.stream, args=(client, sv.PILOT_DIR / "client.log"), daemon=True).start()
        deadline = time.monotonic() + 900
        while pilot.state().get("phase") != "ready":
            if time.monotonic() > deadline or client.poll() is not None:
                raise sv.Stuck("the client never reached the world")
            time.sleep(1.0)
        sv.say("follow emerald:", pilot.do("follow", game="emerald").get("ok"))
        # linked: the player on the hidden cell, on the spot's map, for a few seconds
        deadline = time.monotonic() + 180
        settled = 0
        while settled < 15:
            st = pilot.state()
            p, h = st.get("player") or {}, st.get("hidden_block") or {}
            near = p and h and abs(p["x"] - (h["x"] + 1.0)) < 1.2 and abs(p["z"] - (h["z"] + 1.0)) < 1.2
            settled = settled + 1 if st.get("map") == args.map and near and not st.get("locked") else 0
            if time.monotonic() > deadline:
                raise sv.Stuck("never linked on the spot: " + summary(st))
            time.sleep(0.2)
        sv.say("start", summary(pilot.state()))
        for i in range(args.steps):
            r = pilot.do("step", dir=args.dir, timeout=20)
            sv.say(f"step {i + 1}", "ok" if r.get("ok") else r.get("reason"), summary(pilot.state()))
            time.sleep(2.0)
            sv.say("  2 s later", summary(pilot.state()))
        code = 0
    except sv.Stuck as e:
        sv.say("STUCK:", e)
    finally:
        try:
            pilot.send("quit")
        except OSError:
            pass
        if client is not None:
            try:
                client.wait(timeout=120)
            except subprocess.TimeoutExpired:
                client.kill()
        emerald.kill()
        emerald.wait(timeout=30)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
