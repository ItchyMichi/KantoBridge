"""BIKE_PLAN B6 on the real engines: Blue's and Crystal's one bike through gen1-bridge's ride holds, from a test session
(the role a client has, without Minecraft): the Cycling Road (Route 17) rolls the biker downhill; a held direction steers
(a step across the slope), a held B brakes (no roll), letting go rolls on; the Bicycle from the bag (act.field) on Route 16
and refused on the slope where the game refuses it; a ride into Route 16's gate. Engine behaviour only; nothing of ours
changes either game.

    KB_DATASET_MEMO=30 python tools/e2e/bike_bridge12.py --game blue|crystal

Output: kinds, frames, cells, map symbols, reasons; local/reports/g{1,2}_bike_bridge.json.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

from bike_bridge import collect, poses, states, sweep_rows, warps  # noqa: E402
from fake_minecraft import FakeMinecraft  # noqa: E402
from field_spots import GAMES, wait_spot  # noqa: E402
from run_gen1recomp import REPORTS, launch  # noqa: E402
from story_video import make_home, say  # noqa: E402

PORTS = {"blue": 27256, "crystal": 27257}


def go_line(n, game, m, x, y, facing, vehicle=None, give=False):
    parts = [f"id = {n}", 'op = "go"', f'map = "{m}"', f'facing = "{facing}"']
    if x is not None:
        parts += [f"x = {x}", f"y = {y}"]
    if vehicle:
        parts.append(f'vehicle = "{vehicle}"')
    if give:
        parts.append("give_bike = true")
    if game == "crystal":
        parts += ["events = { }", "flags = { }", "scenes = { }", "mapscenes = { }", "mem = { }"]
    else:
        parts += ["flags = { }"]
    return "{ " + ", ".join(parts) + " }"


async def scenario(run, commands: Path, game: str) -> dict:
    report = {}
    wire = GAMES[game]["wire"]
    mc = await FakeMinecraft(port=PORTS[game], role="test").connect(attempts=20)
    sid = [300]
    try:
        await mc.hello()

        def go(m, x, y, facing, vehicle=None, give=False):
            sid[0] += 1
            with open(commands, "a", encoding="utf-8") as f:
                f.write(go_line(sid[0], game, m, x, y, facing, vehicle, give) + "\n")
            return wait_spot(run, sid[0], timeout=120)

        async def ride(direction, b, seconds):
            await mc.send("move.ride", {"dir": direction, "b": b})
            return await collect(mc, seconds)

        async def field(action="bicycle"):
            msg = await mc.send("act.field", {"action": action})
            try:
                ans = await mc.expect("act.result", timeout=5.0, re=msg["seq"])
            except asyncio.TimeoutError:
                return {"ok": None}
            await collect(mc, 1.0)
            return {"ok": ans["data"].get("ok"), "reason": ans["data"].get("reason"), "result": ans["data"].get("result")}

        if game == "blue":
            road = ("ROUTE_17", 9, 57)
        else:
            rows = sweep_rows("cycling_road", ("ROUTE_17",), "g2") or sweep_rows("cycling_road_roll", ("ROUTE_17",), "g2")
            report["road_rows"] = rows[:3]
            r = rows[0] if rows else {"x": 10, "y": 40}
            road = ("ROUTE_17", r["x"], r["y"])
        if game == "crystal":
            # a held direction wins over Crystal's roll (Bike.forcedDirection): held from the arrival, the biker waits at
            # the top of the road until it is let go of
            await mc.send("move.ride", {"dir": "left", "b": False})
        spot = await asyncio.to_thread(go, road[0], road[1], road[2], "down", "bike", True)
        report["road_start"] = [spot.get("map"), spot.get("x"), spot.get("y")]
        if game == "crystal":
            await collect(mc, 0.5)
            await mc.send("move.ride", {"dir": None, "b": False})
        rolled = await collect(mc, 2.0)
        report["roll"] = {"steps": poses(rolled)[:8], "states": states(rolled)[:3]}
        say(game, "roll:", poses(rolled)[:6], states(rolled)[:2])
        steer = await ride("left", False, 0.4)
        after = await ride(None, False, 1.2)
        report["steer_left"] = {"steps": poses(steer), "then": poses(after)[:4]}
        say(game, "steer left:", poses(steer), "then", poses(after)[:3])
        steer = await ride("right", False, 0.4)
        await ride(None, False, 0.4)
        report["steer_right"] = poses(steer)
        brake = await ride(None, True, 1.5)
        report["brake"] = poses(brake)
        say(game, "brake (B held):", poses(brake))
        report["dismount_on_road"] = await field()
        say(game, "get off on the road:", report["dismount_on_road"])
        await ride(None, False, 0.3)
        # Route 16: off and on through the bag's Bicycle, then into the gate
        gate = ("ROUTE_16", 16, 10, "right") if game == "blue" else ("ROUTE_16", 9, 6, "right")
        spot = await asyncio.to_thread(go, gate[0], gate[1], gate[2], gate[3], "bike", True)
        report["route16_start"] = [spot.get("map"), spot.get("x"), spot.get("y")]
        report["off"] = await field()
        report["on"] = await field()
        say(game, "Route 16 off/on:", report["off"], report["on"])
        into = await ride(gate[3], False, 2.0)
        await ride(None, False, 0.5)
        report["into_gate"] = {"steps": poses(into)[:6], "warps": warps(into), "states": states(into)[:3]}
        say(game, "into the gate:", poses(into)[:4], warps(into))
        status = await mc.request("debug.status")
        report["move_stats"] = status["data"].get("move")
    finally:
        await mc.close()
    return report


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--game", required=True, choices=sorted(PORTS))
    a = ap.parse_args(argv)
    g = GAMES[a.game]
    home = make_home(f"bike_bridge_{g['prefix']}", a.game)
    env = {"KB_DATASET_MEMO": "30", "KB_PACE": "60", "KB_DRIVER_SECONDS": "1200"}
    with launch("kb_union_off.lua", game=a.game, install=home, echo=False, extra_env=env) as off:
        say("union off:", off.wait(timeout=180).marker("KB_UNION_OFF"))
    commands = REPORTS / f"bike_bridge_{g['prefix']}_commands.lua"
    commands.write_text("", encoding="utf-8")
    run = launch(g["driver"], game=a.game, install=home, echo=False,
                 extra_env=dict(env, KB_TEST_PORT=str(PORTS[a.game]), KB_SPOT_COMMANDS=str(commands)))
    report = {}
    try:
        say(a.game + ":", run.wait_marker("KB_SERVING", timeout=300))
        report = asyncio.run(scenario(run, commands, a.game))
    finally:
        run.kill()
        run.wait(timeout=30)
        (REPORTS / f"bike_bridge_{g['prefix']}.log").write_text("\n".join(run.result.lines) + "\n", encoding="utf-8")
    out = REPORTS / f"{g['prefix']}_bike_bridge.json"
    out.write_text(json.dumps(report, indent=1, sort_keys=True), encoding="utf-8")
    say("== report", out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
