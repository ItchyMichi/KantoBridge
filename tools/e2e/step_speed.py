"""STEP_SPEED_PLAN S0/S4 on the real engines: a 40-cell diagonal staircase sent the way the client sends it at 45° (one
move.step per cell boundary, at Minecraft's diagonal cadence: every 16/√2 ≈ 11.3 frames, 188 ms, walking; every
94 ms running), the next step sent whatever the answers, through a test session (the role a client has, without
Minecraft). Per game and STEP SPEED: answers by kind, player.blocked{script} count, debug.status moveDropped, the
answers' frames, lost answers (none within 3 s), and whether every answered cell is the staircase's next cell. Also a
ledge hop (kind ledge, 32 frames; moveFastPassed). The spots drivers (kb_g1spots / kb_g2spots / kb_g3spots) place the
hidden player and set STEP SPEED (op "speed": exports.configure{stepSpeed}).

The staircase stays in a small open square: (right, up) × 5, then (left, down) × 5 back to the start, twice. Before
the timed runs a slow probe walks one loop step by step; a start whose probe meets anything but a plain step is
skipped for the next candidate.

    KB_DATASET_MEMO=30 python tools/e2e/step_speed.py --game blue|crystal|emerald [--speeds 100,200] [--run]

Output: map symbols, cells, kinds, frames and counts; local/reports/step_speed_<game>.json.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

from bike_bridge import sweep_rows  # noqa: E402
from fake_minecraft import FakeMinecraft  # noqa: E402
from field_spots import GAMES, lua_go, wait_spot  # noqa: E402
from run_gen1recomp import REPORTS, launch  # noqa: E402
from story_video import make_home, say  # noqa: E402

PORTS = {"blue": 27261, "crystal": 27262, "emerald": 27263}
# open maps with no trainer in sight of the start (Emerald's trainers are switched off: the driver's "sight")
PLACES = {
    "blue": ["ROUTE_1", "PALLET_TOWN", "VIRIDIAN_CITY"],
    "crystal": ["NEW_BARK_TOWN", "ROUTE_29", "CHERRYGROVE_CITY", "VIOLET_CITY", "ROUTE_31", "ROUTE_34", "ROUTE_36",
                "NATIONAL_PARK"],
    "emerald": ["EM_ROUTE102", "EM_ROUTE101", "EM_LITTLEROOT_TOWN", "EM_ROUTE110"],
}
LEDGE_MAPS = {"blue": ("ROUTE_1",), "crystal": ("ROUTE_29",), "emerald": ("EM_ROUTE111",)}
DELTA = {"up": (0, -1), "down": (0, 1), "left": (-1, 0), "right": (1, 0)}
LOOP = ["right", "up"] * 5 + ["left", "down"] * 5   # 20 cells, back to the start
WALK_S = 16 / 2 ** 0.5 / 60                         # 188 ms
RUN_S = 8 / 2 ** 0.5 / 60                           # 94 ms


def path_cells(x, y, dirs):
    out = []
    for d in dirs:
        dx, dy = DELTA[d]
        x, y = x + dx, y + dy
        out.append((x, y))
    return out


class Session:
    def __init__(self, run, commands: Path, game: str, mc: FakeMinecraft):
        self.run, self.commands, self.game, self.mc = run, commands, game, mc
        self.sid = 400
        self.n = 0

    def command(self, text: str) -> int:
        self.sid += 1
        with open(self.commands, "a", encoding="utf-8") as f:
            f.write(text.replace("ID", str(self.sid)) + "\n")
        return self.sid

    def go(self, m, x, y, facing):
        self.sid += 1
        with open(self.commands, "a", encoding="utf-8") as f:
            line = lua_go(self.sid, self.game, {"map": m, "x": x, "y": y, "dir": facing})
            if self.game == "emerald":
                # the running shoes (FLAG_SYS_B_DASH, constants/emerald/flags.lua), so B held runs (Player.canDash)
                line = line.replace("flags = { }", "flags = { [2240] = true }", 1)
            f.write(line + "\n")
        return wait_spot(self.run, self.sid, timeout=120)

    def speed(self, pct: int):
        cid = self.command(f'{{ id = ID, op = "speed", pct = {pct} }}')
        return self.run.wait_marker(f"KB_SPEED id={cid}", 30)

    async def drain(self, seconds: float) -> list[dict]:
        out = []
        deadline = time.monotonic() + seconds
        while True:
            left = deadline - time.monotonic()
            if left <= 0:
                return out
            try:
                m = await self.mc.next(left)
            except asyncio.TimeoutError:
                return out
            if m is None:
                return out
            out.append(m)

    async def status(self) -> dict:
        st = await self.mc.request("debug.status", timeout=10.0)
        return st["data"]

    async def step_wait(self, d: str, timeout: float = 3.0):
        self.n += 1
        n = self.n
        await self.mc.send("move.step", {"dir": d, "n": n})
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                m = await self.mc.next(deadline - time.monotonic())
            except asyncio.TimeoutError:
                break
            if m and m["type"] in ("player.pos", "player.blocked") and m["data"].get("n") == n:
                return m
        return None


def answered(msgs, sent):
    by_n = {}
    for m in msgs:
        if m["type"] in ("player.pos", "player.blocked") and m["data"].get("n") in sent:
            by_n.setdefault(m["data"]["n"], []).append(m)
    return by_n


async def probe(s: Session, m: str, x: int, y: int) -> bool:
    spot = await asyncio.to_thread(s.go, m, x, y, "right")
    if spot.get("marker") != "KB_SPOT" or spot.get("map") is None:
        return False
    sx, sy = int(spot["x"]), int(spot["y"])
    await s.drain(0.6)
    for i, (d, (cx, cy)) in enumerate(zip(LOOP, path_cells(sx, sy, LOOP))):
        a = await s.step_wait(d)
        if a is None or a["type"] != "player.pos" or a["data"].get("kind") not in ("walk", "run") \
                or (a["data"]["x"], a["data"]["y"]) != (cx, cy):
            got = None if a is None else (a["type"], a["data"].get("kind"), a["data"].get("reason"), a["data"].get("x"),
                                          a["data"].get("y"))
            say("probe miss", m, (sx, sy), "step", i, d, "want", (cx, cy), "got", got)
            return False
        await s.drain(0.35)
    return True


async def staircase(s: Session, m: str, x: int, y: int, run: bool) -> dict:
    spot = await asyncio.to_thread(s.go, m, x, y, "right")
    sx, sy = int(spot["x"]), int(spot["y"])
    await s.drain(0.8)
    if run:
        await s.mc.send("move.hold", {"button": "b", "on": True})
        await s.drain(0.3)
    before = await s.status()
    dirs = LOOP * 2
    expect = path_cells(sx, sy, dirs)
    sent = {}
    msgs: list[dict] = []
    t0 = time.monotonic()

    async def sender():
        for i, d in enumerate(dirs):
            target = t0 + i * (RUN_S if run else WALK_S)
            delay = target - time.monotonic()
            if delay > 0:
                await asyncio.sleep(delay)
            s.n += 1
            sent[s.n] = i
            await s.mc.send("move.step", {"dir": d, "n": s.n})

    async def reader(stop: asyncio.Event):
        while not stop.is_set():
            try:
                mm = await s.mc.next(0.1)
            except asyncio.TimeoutError:
                continue
            if mm is None:
                return
            mm["t"] = round(time.monotonic() - t0, 3)
            msgs.append(mm)

    stop = asyncio.Event()
    rd = asyncio.create_task(reader(stop))
    await sender()
    await asyncio.sleep(3.0)
    stop.set()
    await rd
    if run:
        await s.mc.send("move.hold", {"button": "b", "on": False})
    await s.drain(0.5)
    after = await s.status()
    by_n = answered(msgs, sent)
    kinds = Counter()
    frames = Counter()
    script = blocked_other = cells_ok = 0
    for n, i in sent.items():
        for a in by_n.get(n, []):
            if a["type"] == "player.pos":
                kinds[a["data"]["kind"]] += 1
                frames[a["data"]["frames"]] += 1
                if (a["data"]["x"], a["data"]["y"]) == expect[i]:
                    cells_ok += 1
            else:
                r = a["data"].get("reason")
                kinds["blocked:" + str(r)] += 1
                if r == "script":
                    script += 1
                else:
                    blocked_other += 1

    def delta(k):
        a, b = after.get(k), before.get(k)
        return (a - b) if isinstance(a, int) and isinstance(b, int) else None

    return {
        "map": m, "start": [sx, sy], "steps": len(sent), "run": run,
        "answers": sum(len(v) for v in by_n.values()), "lost": sum(1 for n in sent if n not in by_n),
        "double": sum(1 for v in by_n.values() if len(v) > 1),
        "kinds": dict(kinds), "frames": {str(k): v for k, v in sorted(frames.items())},
        "script": script, "blocked_other": blocked_other, "cells_ok": cells_ok,
        "moveDropped": delta("moveDropped"), "moveFastSteps": delta("moveFastSteps"),
        "moveFastPassed": delta("moveFastPassed"), "moveSpeedPct": after.get("moveSpeedPct"),
        "end": [after.get("mode")],
        "seconds": round(max([mm["t"] for mm in msgs] or [0]), 2),
    }


async def ledge(s: Session, game: str) -> dict | None:
    pre = GAMES[game]["prefix"]
    rows = []
    for cls in ("ledge_jump", "ledge"):
        rows = sweep_rows(cls, LEDGE_MAPS[game], pre) or rows
        if rows:
            break
    if not rows:
        for cls in ("ledge_jump", "ledge"):
            rows = rows or [r for r in sweep_rows(cls, tuple(PLACES[game]) + LEDGE_MAPS[game], pre)]
    if not rows:
        return None
    got = None
    for r in rows[:4]:
        if r.get("x") is None or r.get("dir") not in DELTA:
            continue
        spot = await asyncio.to_thread(s.go, r["map"], r["x"], r["y"], r["dir"])
        if spot.get("marker") != "KB_SPOT":
            continue
        await s.drain(0.8)
        before = await s.status()
        a = await s.step_wait(r["dir"], timeout=4.0)
        await s.drain(1.5)
        after = await s.status()
        got = {"map": r["map"], "start": [r["x"], r["y"]], "dir": r["dir"],
               "answer": None if a is None else [a["type"], a["data"].get("kind"), a["data"].get("frames"),
                                                 a["data"].get("x"), a["data"].get("y")],
               "moveFastPassed": (after.get("moveFastPassed") or 0) - (before.get("moveFastPassed") or 0)
               if isinstance(after.get("moveFastPassed"), int) else None}
        if a is not None and a["data"].get("kind") == "ledge":
            return got
    return got


async def scenario(run, commands: Path, game: str, speeds: list[int], with_run: bool, starts: list) -> dict:
    report = {"game": game, "runs": []}
    mc = await FakeMinecraft(port=PORTS[game], role="test").connect(attempts=20)
    s = Session(run, commands, game, mc)
    try:
        await mc.hello()
        await s.drain(1.0)
        if game == "emerald":
            s.command('{ id = ID, op = "sight", on = false }')
        # the start: the given ones, else a grid over the open maps
        found = None
        cands = starts + [(m, x, y) for m in PLACES[game] for y in range(6, 40, 2) for x in range(1, 30, 2)]
        tried = 0
        for m, x, y in cands:
            tried += 1
            if await probe(s, m, x, y):
                found = (m, x, y)
                break
            if tried >= 300:
                break
        report["probe"] = {"tried": tried, "found": found}
        say(game, "start:", found, "after", tried, "candidates")
        if found is None:
            return report
        for pct in speeds:
            say(game, "speed", pct, s.speed(pct))
            for running in ([False, True] if (with_run and game == "emerald") else [False]):
                r = await staircase(s, *found, running)
                r["pct"] = pct
                report["runs"].append(r)
                say(game, f"pct={pct} run={running}:", json.dumps({k: r[k] for k in (
                    "steps", "answers", "lost", "script", "blocked_other", "moveDropped", "frames", "cells_ok",
                    "moveFastSteps", "moveSpeedPct")}))
            led = await ledge(s, game)
            report.setdefault("ledges", []).append({"pct": pct, **(led or {})})
            say(game, f"pct={pct} ledge:", json.dumps(led))
    finally:
        await mc.close()
    return report


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--game", required=True, choices=sorted(PORTS))
    ap.add_argument("--speeds", default="100,200", help="STEP SPEED values to run, in order (S0: the default only)")
    ap.add_argument("--run", action="store_true", help="Emerald again with B held (running)")
    ap.add_argument("--start", action="append", default=[], help="MAP:X:Y, tried before the grid")
    ap.add_argument("--tag", default="", help="a suffix for the report's name (s0, s4)")
    a = ap.parse_args(argv)
    g = GAMES[a.game]
    speeds = [int(v) for v in a.speeds.split(",") if v]
    starts = [(p.split(":")[0], int(p.split(":")[1]), int(p.split(":")[2])) for p in a.start]
    home = make_home(f"step_speed_{g['prefix']}", a.game)
    env = {"KB_DATASET_MEMO": "30", "KB_PACE": "60", "KB_DRIVER_SECONDS": "2400"}
    with launch("kb_union_off.lua", game=a.game, install=home, echo=False, extra_env=env) as off:
        say("union off:", off.wait(timeout=180).marker("KB_UNION_OFF"))
    commands = REPORTS / f"step_speed_{g['prefix']}_commands.lua"
    commands.write_text("", encoding="utf-8")
    run = launch(g["driver"], game=a.game, install=home, echo=False,
                 extra_env=dict(env, KB_TEST_PORT=str(PORTS[a.game]), KB_SPOT_COMMANDS=str(commands)))
    report = {}
    try:
        say(a.game + ":", run.wait_marker("KB_SERVING", timeout=300))
        report = asyncio.run(scenario(run, commands, a.game, speeds, a.run, starts))
    finally:
        run.kill()
        run.wait(timeout=30)
        (REPORTS / f"step_speed_{g['prefix']}{a.tag}.log").write_text("\n".join(run.result.lines) + "\n",
                                                                       encoding="utf-8")
    out = REPORTS / f"step_speed_{a.game}{a.tag}.json"
    out.write_text(json.dumps(report, indent=1, sort_keys=True), encoding="utf-8")
    say("== report", out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
