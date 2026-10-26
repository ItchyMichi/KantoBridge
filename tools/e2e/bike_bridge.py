"""BIKE_PLAN B1 on the real engine: gen1-bridge's ride holds driving Emerald's own bike code, through a test session
(the role a client has, without Minecraft): move.ride on the Mach accelerates to 4 frames a cell within 3 steps and the
release lets it stop; B held standing on the Acro gives player.state trick wheelie, then hop after 40 frames; one
move.ride with a new direction and B is a side jump (player.pos jump normal). The spots driver (kb_g3spots.lua) puts the
hidden player on the bike at the cells it is given.

    KB_DATASET_MEMO=30 python tools/e2e/bike_bridge.py [--x 4 --y 41]

Output: kinds, frames, cells, counts; local/reports/g3_bike_bridge.json.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

from fake_minecraft import FakeMinecraft  # noqa: E402
from field_spots import lua_go, wait_spot  # noqa: E402
from run_gen1recomp import REPORTS, launch  # noqa: E402
from story_video import make_home, say  # noqa: E402

PORT = 27254


async def collect(mc: FakeMinecraft, seconds: float) -> list[dict]:
    out = []
    deadline = time.monotonic() + seconds
    while True:
        left = deadline - time.monotonic()
        if left <= 0:
            return out
        try:
            m = await mc.next(left)
        except asyncio.TimeoutError:
            return out
        if m is None:
            return out
        if m["type"] in ("player.pos", "player.state", "player.blocked", "warp"):
            out.append({"t": round(time.monotonic(), 3), "type": m["type"], "data": m["data"]})


def warps(msgs):
    return [(m["data"].get("phase"), (m["data"].get("to") or {}).get("map")) for m in msgs if m["type"] == "warp"]


def poses(msgs):
    return [(m["data"]["x"], m["data"]["y"], m["data"]["kind"], m["data"]["frames"], m["data"].get("jump"))
            for m in msgs if m["type"] == "player.pos" and m["data"]["kind"] != "sync"]


def states(msgs):
    return [(m["data"].get("mode"), m["data"].get("bikeKind"), m["data"].get("trick"), m["data"].get("gear"))
            for m in msgs if m["type"] == "player.state"]


def sweep_rows(cls: str, maps: tuple[str, ...], prefix: str = "g3") -> list[dict]:
    """Rows of the earlier field sweep (FA5) of a class on those maps: start cell, direction, end (cells only)."""
    out = []
    for af in sorted(REPORTS.glob(f"{prefix}_field_sweep_analysis_*.json")):
        tag = af.name[len(f"{prefix}_field_sweep_analysis"):-len(".json")]
        sf = REPORTS / f"{prefix}_field_sweep{tag}.json"
        if not sf.is_file():
            continue
        recs = {r["n"]: r for r in json.loads(sf.read_text(encoding="utf-8"))["rows"]}
        for e in json.loads(af.read_text(encoding="utf-8"))["rows"]:
            r = recs.get(e["n"]) or {}
            if e["cls"].split(":")[0] == cls and r.get("map") in maps:
                st = r.get("start") or {}
                out.append({"map": r["map"], "x": st.get("x"), "y": st.get("y"), "dir": r.get("dir"), "end": r.get("end")})
    return out


async def b5(run, commands: Path) -> dict:
    """BIKE_PLAN B5 on the real engine through move.ride: rails, a ledge hop, the muddy slope, the cracked floor, the
    Cycling Road challenge's count."""
    report = {}
    mc = await FakeMinecraft(port=PORT, role="test").connect(attempts=20)
    sid = [200]
    try:
        await mc.hello()
        with open(commands, "a", encoding="utf-8") as f:
            f.write('{ id = 199, op = "sight", on = false }\n')

        def go(m, vehicle, gx, gy, facing):
            sid[0] += 1
            with open(commands, "a", encoding="utf-8") as f:
                f.write(lua_go(sid[0], "emerald", {"map": m, "x": gx, "y": gy, "dir": facing, "vehicle": vehicle}) + "\n")
            return wait_spot(run, sid[0])

        def lua(text):
            sid[0] += 1
            with open(commands, "a", encoding="utf-8") as f:
                f.write(text.replace("ID", str(sid[0])) + "\n")
            return sid[0]

        async def ride(direction, b, seconds):
            await mc.send("move.ride", {"dir": direction, "b": b})
            return await collect(mc, seconds)

        async def case(name, m, vehicle, gx, gy, facing, plan):
            spot = await asyncio.to_thread(go, m, vehicle, gx, gy, facing)
            await collect(mc, 0.8)
            msgs = []
            for direction, b, seconds in plan:
                msgs += await ride(direction, b, seconds)
            msgs += await ride(None, False, 1.2)
            out = {"start": [spot.get("map"), spot.get("x"), spot.get("y")], "steps": poses(msgs), "warps": warps(msgs),
                   "bumps": sum(1 for x in msgs if x["type"] == "player.blocked" and "n" not in x["data"]),
                   "states": states(msgs)[:6]}
            report[name] = out
            say(name, json.dumps({k: out[k] for k in ("start", "steps", "warps", "bumps")})[:600])
            return out

        hop = [(None, True, 0.9)]  # B held 40 frames standing: the bunny hop
        # rails (Route 119): along a vertical rail hopping, across it refused
        await case("rail_along", "EM_ROUTE119", "acro", 10, 8, "up", hop + [("up", True, 1.6)])
        await case("rail_across", "EM_ROUTE119", "acro", 11, 6, "left", hop + [("left", True, 1.0)])
        await case("rail_mach", "EM_ROUTE119", "mach", 10, 8, "up", [("up", False, 1.0)])
        # a ledge hopped in a wheelie (the first outdoor ledge row of Route 111, else any route's)
        ledges = sweep_rows("ledge_jump", ("EM_ROUTE111",)) or sweep_rows("ledge_jump", ("EM_ROUTE110", "EM_ROUTE112", "EM_ROUTE113"))
        if ledges:
            L = ledges[0]
            report["ledge_row"] = L
            await case("ledge_wheelie_hop", L["map"], "acro", L["x"], L["y"], L["dir"], hop + [(L["dir"], True, 1.0)])
            await case("ledge_mach", L["map"], "mach", L["x"], L["y"], L["dir"], [(L["dir"], False, 1.0)])
        # the muddy slope (Route 111 (29, 8) up): at full speed from three cells back, and from a standing start
        await case("muddy_full_speed", "EM_ROUTE111", "mach", 29, 11, "up", [("up", False, 2.0)])
        await case("muddy_slow", "EM_ROUTE111", "mach", 29, 8, "up", [("up", False, 1.5)])
        await case("muddy_acro", "EM_ROUTE111", "acro", 29, 8, "up", [("up", False, 1.5)])
        # the cracked floor (Granite Cave B1F): from a standing start it gives way; at full speed it holds
        await case("cracked_slow", "EM_GRANITE_CAVE_B1F", "mach", 14, 1, "right", [("right", False, 1.2)])
        await case("cracked_full_speed", "EM_GRANITE_CAVE_B1F", "mach", 9, 1, "right", [("right", False, 1.5)])
        # the Cycling Road challenge counts a bump
        spot = await asyncio.to_thread(go, "EM_ROUTE110", "mach", 4, 12, "up")
        cid = lua('{ id = ID, op = "challenge", begin = true }')
        before = await asyncio.to_thread(run.wait_marker, f"KB_CHALLENGE id={cid}", 20)
        bump = await ride("up", False, 1.5)
        await ride(None, False, 0.8)
        cid = lua('{ id = ID, op = "challenge" }')
        after = await asyncio.to_thread(run.wait_marker, f"KB_CHALLENGE id={cid}", 20)
        report["challenge"] = {"before": before, "after": after, "bumps": sum(1 for x in bump if x["type"] == "player.blocked")}
        say("challenge:", before, "->", after)
    finally:
        await mc.close()
    return report


async def scenario(run, commands: Path, x: int, y: int) -> dict:
    report = {}
    mc = await FakeMinecraft(port=PORT, role="test").connect(attempts=20)
    try:
        await mc.hello()
        sid = [100]

        def go(vehicle, gx, gy, facing="up"):
            sid[0] += 1
            with open(commands, "a", encoding="utf-8") as f:
                f.write(lua_go(sid[0], "emerald", {"map": "EM_ROUTE110", "x": gx, "y": gy, "dir": facing, "vehicle": vehicle}) + "\n")
            return wait_spot(run, sid[0])

        async def ride(direction, b, seconds):
            await mc.send("move.ride", {"dir": direction, "b": b})
            return await collect(mc, seconds)

        # trainer sight off: nobody stops the ride for a battle
        with open(commands, "a", encoding="utf-8") as f:
            f.write('{ id = 99, op = "sight", on = false }\n')
        # the Mach: held up, then let go
        say("mach:", await asyncio.to_thread(go, "mach", x, y))
        await collect(mc, 1.0)
        held = await ride("up", False, 2.0)
        free = await ride(None, False, 1.5)
        report["mach_held"] = {"steps": poses(held)[:12], "states": states(held)}
        report["mach_release"] = {"steps": poses(free), "states": states(free)}
        frames = [p[3] for p in poses(held)]
        report["mach_fastest_by_step"] = frames.index(4) + 1 if 4 in frames else None
        say("mach frames:", frames[:10], "fastest at step", report["mach_fastest_by_step"], "after release:", [p[3] for p in poses(free)])
        # a move.step while a ride direction is held is refused
        await mc.send("move.ride", {"dir": "down", "b": False})
        await mc.send("move.step", {"dir": "left", "n": 1})
        refused = await collect(mc, 1.0)
        report["step_while_riding"] = [m["data"].get("reason") for m in refused if m["type"] == "player.blocked" and m["data"].get("n") == 1]
        await ride(None, False, 1.0)
        # the Acro: B held standing
        say("acro:", await asyncio.to_thread(go, "acro", x, y - 5))
        await collect(mc, 1.0)
        standing = await ride(None, True, 1.5)
        report["acro_b_held"] = {"states": states(standing)}
        await ride(None, False, 1.0)
        tricks = [s[2] for s in states(standing)]
        say("acro B held, tricks:", tricks)
        # a side jump from one message (facing up: a new direction with B)
        jump = await ride("right", True, 0.6)
        await ride(None, False, 1.0)
        report["side_jump"] = poses(jump)
        say("side jump:", poses(jump))
        # the wheelie run: B then up with B
        await ride(None, True, 0.4)
        run_msgs = await ride("up", True, 1.5)
        await ride(None, False, 1.0)
        report["wheelie_run"] = {"steps": poses(run_msgs)[:8], "states": states(run_msgs)}
        say("wheelie run:", [p[3] for p in poses(run_msgs)], states(run_msgs)[:4])
        status = await mc.request("debug.status")
        report["move_stats"] = (status["data"].get("move") or {})
    finally:
        await mc.close()
    return report


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--x", type=int, default=4)
    ap.add_argument("--y", type=int, default=41)
    ap.add_argument("--b5", action="store_true", help="BIKE_PLAN B5's mechanics instead of B1's")
    a = ap.parse_args(argv)
    home = make_home("bike_bridge_g3", "emerald")
    env = {"KB_DATASET_MEMO": "30", "KB_PACE": "60", "KB_DRIVER_SECONDS": "1200"}
    with launch("kb_union_off.lua", game="emerald", install=home, echo=False, extra_env=env) as off:
        say("union off:", off.wait(timeout=180).marker("KB_UNION_OFF"))
    commands = REPORTS / "bike_bridge_commands.lua"
    commands.write_text("", encoding="utf-8")
    run = launch("kb_g3spots.lua", game="emerald", install=home, echo=False,
                 extra_env=dict(env, KB_TEST_PORT=str(PORT), KB_SPOT_COMMANDS=str(commands)))
    report = {}
    try:
        say("emerald:", run.wait_marker("KB_SERVING", timeout=300))
        report = asyncio.run(b5(run, commands) if a.b5 else scenario(run, commands, a.x, a.y))
    finally:
        run.kill()
        run.wait(timeout=30)
        (REPORTS / "bike_bridge_g3.log").write_text("\n".join(run.result.lines) + "\n", encoding="utf-8")
    name = "g3_bike_b5.json" if a.b5 else "g3_bike_bridge.json"
    (REPORTS / name).write_text(json.dumps(report, indent=1, sort_keys=True), encoding="utf-8")
    say("== report", REPORTS / name)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
