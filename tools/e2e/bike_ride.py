"""The bike ridden from the real client's keys (docs/specs/BIKE_PLAN.md B5, B7): the hidden Emerald player put on a bike by
the spots driver, the pilot holding Minecraft's movement keys and sneak (the client's move.ride), the driver tracing the
engine's bike facts meanwhile (`trace`). Per ride: the engine's steps (frames, hops), where both players ended, the
client's legs, bumps and lag, the ride's latency from the key to the seat's first motion; optionally a video.

    DISPLAY=:99 KB_DATASET_MEMO=30 python tools/e2e/bike_ride.py --world LandSky6 [--record x.mp4] [--only mach,wheelie]

Output: map symbols, cells, counts, frames, milliseconds; local/reports/g3_bike_ride.json.
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

from bike_bridge import sweep_rows  # noqa: E402
from bike_bridge12 import go_line  # noqa: E402
from bike_follow import Sampler, facts, lag_cells, line_time  # noqa: E402
from field_spots import GAMES, GRADLE_DIR, SAVES, client_command, inside, lua_go, stream, wait_spot  # noqa: E402
from run_gen1recomp import REPORTS, launch  # noqa: E402
from story_video import Pilot, Stuck, make_home, say  # noqa: E402

PORTS = {"emerald": 27255, "blue": 27258, "crystal": 27259}


def rides12(game: str) -> list[dict]:
    """B6: Blue's and Crystal's Cycling Road (the roll, steering, the brake, the Bicycle key refused on the road) and Route
    16 (the key off and on, a ride into the gate)."""
    if game == "blue":
        road, gate = ("ROUTE_17", 9, 57), ("ROUTE_16", 16, 10, "right")
    else:
        rows = sweep_rows("cycling_road", ("ROUTE_17",), "g2") or sweep_rows("cycling_road_roll", ("ROUTE_17",), "g2")
        r = rows[0] if rows else {"x": 10, "y": 40}
        road, gate = ("ROUTE_17", r["x"], r["y"]), ("ROUTE_16", 9, 6, "right")
    base = {"map": road[0], "x": road[1], "y": road[2], "facing": "down", "vehicle": "bike", "give": True}
    return [
        dict(base, name="roll", keys=[([], 60)]),
        dict(base, name="steer", keys=[(["strafe_left"], 6), ([], 20), (["strafe_right"], 6), ([], 20)]),
        dict(base, name="brake", keys=[(["sneak"], 50), ([], 20)]),
        dict(base, name="key_on_the_road", keys=[(["bicycle"], 2), ([], 30)]),
        {"name": "key_off_on_and_the_gate", "map": gate[0], "x": gate[1], "y": gate[2], "facing": gate[3], "vehicle": "bike",
         "give": True, "keys": [(["bicycle"], 2), ([], 50), (["bicycle"], 2), ([], 60), (["forward"], 40)]},
    ]


def rides(ledge: dict | None) -> list[dict]:
    """The rides: a spot (map, cell, facing, vehicle) and key presses: (keys, ticks held), then a pause of ticks."""
    out = [
        {"name": "mach", "map": "EM_ROUTE110", "x": 4, "y": 41, "facing": "up", "vehicle": "mach",
         "keys": [(["forward"], 50)]},
        {"name": "wheelie", "map": "EM_ROUTE110", "x": 4, "y": 36, "facing": "up", "vehicle": "acro",
         "keys": [(["sneak"], 8), (["forward", "sneak"], 40)]},
        {"name": "bunny_hop", "map": "EM_ROUTE110", "x": 4, "y": 36, "facing": "up", "vehicle": "acro",
         "keys": [(["sneak"], 70)]},
        {"name": "side_jump", "map": "EM_ROUTE110", "x": 4, "y": 36, "facing": "up", "vehicle": "acro",
         "keys": [(["strafe_right", "sneak"], 3)]},
        {"name": "jump_key_is_b", "map": "EM_ROUTE110", "x": 4, "y": 36, "facing": "up", "vehicle": "acro",
         "keys": [(["jump"], 70)]},
        {"name": "rail_hops", "map": "EM_ROUTE119", "x": 10, "y": 8, "facing": "up", "vehicle": "acro",
         "keys": [(["sneak"], 50), (["forward", "sneak"], 40)]},
        {"name": "muddy_slope", "map": "EM_ROUTE111", "x": 29, "y": 11, "facing": "up", "vehicle": "mach",
         "keys": [(["forward"], 45)]},
        {"name": "cracked_floor", "map": "EM_GRANITE_CAVE_B1F", "x": 9, "y": 1, "facing": "right", "vehicle": "mach",
         "keys": [(["forward"], 30)]},
    ]
    if ledge:
        out.append({"name": "ledge_hop", "map": ledge["map"], "x": ledge["x"], "y": ledge["y"], "facing": ledge["dir"],
                    "vehicle": "acro", "keys": [(["sneak"], 50), (["forward", "sneak"], 15)]})
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--world", default="LandSky6")
    ap.add_argument("--game", default="emerald", choices=sorted(PORTS))
    ap.add_argument("--record")
    ap.add_argument("--only", default="")
    a = ap.parse_args(argv)
    only = set(filter(None, a.only.split(",")))
    g = GAMES[a.game]
    PORT = PORTS[a.game]
    if a.game == "emerald":
        ledges = sweep_rows("ledge_jump", ("EM_ROUTE111",)) or sweep_rows("ledge_jump", ("EM_ROUTE110", "EM_ROUTE112", "EM_ROUTE113"))
        plan = rides(ledges[0] if ledges else None)
    else:
        plan = rides12(a.game)
    plan = [r for r in plan if not only or r["name"] in only]
    world = a.world + "Ride" + g["prefix"].upper()
    if (SAVES / world).exists():
        shutil.rmtree(SAVES / world)
    shutil.copytree(SAVES / a.world, SAVES / world)
    pilot_dir = REPORTS / f"bike_ride_{g['prefix']}"
    pilot_dir.mkdir(parents=True, exist_ok=True)
    home = make_home(f"bike_ride_{g['prefix']}", a.game)
    env = {"KB_DATASET_MEMO": "30", "KB_PACE": "60", "KB_DRIVER_SECONDS": "3600"}
    with launch("kb_union_off.lua", game=a.game, install=home, echo=False, extra_env=env) as off:
        say("union off:", off.wait(timeout=180).marker("KB_UNION_OFF"))
    commands = pilot_dir / "game_commands.lua"
    commands.write_text("", encoding="utf-8")
    pilot = Pilot(pilot_dir)
    run = launch(g["driver"], game=a.game, install=home, echo=False,
                 extra_env=dict(env, KB_TEST_PORT=str(PORT), KB_SPOT_COMMANDS=str(commands)))
    client = None
    sampler = None
    report = {"rides": []}
    try:
        say(a.game + ":", run.wait_marker("KB_SERVING", timeout=300))
        client = subprocess.Popen(client_command(a.game, world, pilot_dir, PORT), cwd=GRADLE_DIR,
                                  env=dict(os.environ, JAVA_HOME=os.environ.get("KB_JAVA_HOME") or "/opt/java/openjdk"),
                                  stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        client_log = pilot_dir / "client.log"
        threading.Thread(target=stream, args=(client, client_log), daemon=True).start()
        deadline = time.monotonic() + 1200
        while pilot.state().get("phase") != "ready":
            if time.monotonic() > deadline or client.poll() is not None:
                raise Stuck("the client never reached the world")
            time.sleep(1.0)
        say("follow:", pilot.do("follow", game=a.game).get("ok"))
        time.sleep(15)
        if a.game == "emerald":
            with open(commands, "a", encoding="utf-8") as f:
                f.write('{ id = 90, op = "sight", on = false }\n')
        sampler = Sampler(pilot)
        if a.record:
            pilot.do("record", file=a.record, timeout=30)
        n = 100
        for r in plan:
            n += 1
            with open(commands, "a", encoding="utf-8") as f:
                if a.game == "emerald":
                    line = lua_go(n, "emerald", {"map": r["map"], "x": r["x"], "y": r["y"], "dir": r["facing"], "vehicle": r["vehicle"]})
                    if r.get("give"):
                        line = line[:-2] + ', give_bike = "' + ("ACRO_BIKE" if r["vehicle"] == "acro" else "MACH_BIKE") + '" }'
                else:
                    line = go_line(n, a.game, r["map"], r["x"], r["y"], r["facing"], r["vehicle"], r.get("give", False))
                f.write(line + "\n")
            spot = wait_spot(run, n)
            deadline = time.monotonic() + 45
            st = pilot.state()
            while time.monotonic() < deadline and not (st.get("ride") and inside(st)):
                time.sleep(0.3)
                st = pilot.state()
            seated = bool(st.get("ride"))
            pilot.do("camera", mode="third", hud=True)
            pilot.do("face", dir=r["facing"], pitch=20)
            time.sleep(2.0)
            before = pilot.state()
            m0 = before.get("move") or {}
            n += 1
            total = sum(t for _, t in r["keys"]) * 3 + 150
            n0 = len(run.result.lines)
            if a.game == "emerald":
                with open(commands, "a", encoding="utf-8") as f:
                    f.write(f'{{ id = {n}, op = "trace", frames = {total} }}\n')
                line_time(run, n0, f"KB_RT f=", 5)
            t_key = time.monotonic()
            for keys, ticks in r["keys"]:
                if keys:
                    pilot.do("keys", keys=keys, hold=ticks, timeout=30 + ticks / 10)
                else:
                    time.sleep(ticks / 20)
            end_line = None
            if a.game == "emerald":
                t_end, end_line, _ = line_time(run, n0, f"KB_TRACE_END id={n} ", total / 60 + 30)
            time.sleep(2.0)
            after = pilot.state()
            m1 = after.get("move") or {}
            # the key to the seat's first motion (the ride's latency end to end)
            p0 = (before.get("ride") or before.get("player") or {})
            first = None
            lags = []
            for t, s in sampler.since(t_key):
                p = s.get("ride") or s.get("player") or {}
                if first is None and "x" in p and "x" in p0 and (abs(p["x"] - p0["x"]) > 0.05 or abs(p["z"] - p0["z"]) > 0.05):
                    first = t
                lag = lag_cells(s)
                if lag is not None:
                    lags.append(lag)
            rt = [facts(x) for x in run.result.lines[n0:] if x.startswith("KB_RT")]
            starts = [f for i, f in enumerate(rt) if f.get("mv") == "1" and (i == 0 or rt[i - 1].get("t") != f.get("t") or rt[i - 1].get("mv") != "1")]
            res = {"name": r["name"], "seated": seated, "start": [spot.get("map"), spot.get("x"), spot.get("y")],
                   "end": facts(end_line) if end_line else None,
                   "engine_steps": [(f.get("sf"), f.get("j")) for f in starts],
                   "tricks": sorted({f.get("as") for f in rt}),
                   "key_to_motion_ms": round((first - t_key) * 1000) if first else None,
                   "lag_max": max(lags) if lags else None,
                   "moves": {k: m1.get(k, 0) - m0.get(k, 0) for k in m1 if isinstance(m1.get(k), (int, float)) and m1.get(k, 0) != m0.get(k, 0)},
                   "ride_after": after.get("ride"), "hidden": [after.get("map"), after.get("x"), after.get("y")],
                   "inside": inside(after)}
            report["rides"].append(res)
            say(json.dumps({k: res[k] for k in ("name", "seated", "end", "engine_steps", "tricks", "key_to_motion_ms", "lag_max",
                                                "inside", "moves")})[:900])
        complaints = client_log.read_text(encoding="utf-8", errors="replace")
        report["server"] = {"moved_too_quickly": complaints.count("moved too quickly"), "moved_wrongly": complaints.count("moved wrongly"),
                            "forced_stuck": complaints.count("forced_stuck")}
        say("server:", report["server"])
    except Stuck as e:
        say("STUCK:", e)
        report["stuck"] = str(e)
    finally:
        if sampler:
            sampler.stop()
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
        (pilot_dir / f"{a.game}.log").write_text("\n".join(run.result.lines) + "\n", encoding="utf-8")
    out = REPORTS / f"{g['prefix']}_bike_ride.json"
    out.write_text(json.dumps(report, indent=1, sort_keys=True), encoding="utf-8")
    say("== report", out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
