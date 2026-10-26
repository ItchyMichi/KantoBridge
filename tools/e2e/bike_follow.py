"""The bike's facts on the real engines (docs/specs/BIKE_PLAN.md B0): the hidden Emerald player on the Mach and the Acro
bike, driven by held buttons (the spots driver's `ride`, the engine's own bike code), while the real dev client follows
through today's forced legs. Three measurements:

  (a) the follow: per second the cells of lag between the hidden player and the client, the mover's legs, snaps,
      teleports and stuck moves, the frame rate at 4 frames a cell, and whether the server ever answered "moved too
      quickly" (a 30-cell Mach run up Route 110, an Acro wheelie run, Route 119's rails);
  (b) the key path: the client's existing hold (the sprint key on foot: move.hold b) from the pilot's key to the engine
      seeing B held, and a ride's start to the client's first motion;
  (c) the classifier: which of the bike's in-place actions (a wheelie standing, the bunny hop's charge and hops, a
      collide, a turn) flip player.state's mode off idle, from the driver's facts and the client's mode.

    DISPLAY=:99 KB_DATASET_MEMO=30 python tools/e2e/bike_follow.py --world LandSky6 [--record /workspace/videos/x.mp4]

Output: map symbols, cells, counts, frames, milliseconds; local/reports/g3_bike_follow.json.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

from field_spots import GRADLE_DIR, SAVES, client_command, inside, lua_go, stream, wait_spot  # noqa: E402
from run_gen1recomp import REPORTS, launch  # noqa: E402
from story_video import Pilot, Stuck, make_home, say  # noqa: E402

PORT = 27253


class Sampler:
    """The pilot's state every ~40 ms, with the time it was read."""

    def __init__(self, pilot: Pilot):
        self.pilot = pilot
        self.samples: list[tuple[float, dict]] = []
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def _run(self):
        while not self._stop.is_set():
            st = self.pilot.state()
            if st:
                self.samples.append((time.monotonic(), st))
            time.sleep(0.04)

    def since(self, t0: float, t1: float | None = None) -> list[tuple[float, dict]]:
        return [(t, s) for t, s in list(self.samples) if t >= t0 and (t1 is None or t <= t1)]

    def stop(self):
        self._stop.set()


def lag_cells(st: dict) -> int | None:
    hb, pl = st.get("hidden_block") or {}, st.get("player") or {}
    if "x" not in hb or "x" not in pl:
        return None
    dx = (pl["x"] - hb["x"]) // 2
    dz = (pl["z"] - hb["z"]) // 2
    return int(abs(dx) + abs(dz))


def line_time(run, start_index: int, prefix: str, timeout: float) -> tuple[float | None, str | None, int]:
    """Waits for the first driver line starting with prefix after start_index; its arrival time (monotonic)."""
    deadline = time.monotonic() + timeout
    i = start_index
    while time.monotonic() < deadline:
        lines = run.result.lines
        while i < len(lines):
            if lines[i].startswith(prefix):
                return time.monotonic(), lines[i], i + 1
            i += 1
        time.sleep(0.005)
    return None, None, i


def facts(line: str) -> dict:
    return dict(p.split("=", 1) for p in line.split()[1:] if "=" in p)


class Rig:
    def __init__(self, run, pilot: Pilot, commands: Path, client_log: Path):
        self.run, self.pilot, self.commands, self.client_log = run, pilot, commands, client_log
        self.next_id = 100

    def lua(self, text: str):
        with open(self.commands, "a", encoding="utf-8") as f:
            f.write(text + "\n")

    def cid(self) -> int:
        self.next_id += 1
        return self.next_id

    def go(self, m: str, x: int | None, y: int | None, facing: str, vehicle: str | None) -> dict:
        i = self.cid()
        if x is None:
            self.lua(f'{{ id = {i}, op = "go", map = "{m}", facing = "{facing}", flags = {{ }}, vars = {{ }} }}')
        else:
            self.lua(lua_go(i, "emerald", {"map": m, "x": x, "y": y, "dir": facing, "vehicle": vehicle}))
        game = wait_spot(self.run, i)
        if game.get("marker") != "KB_SPOT":
            return {"ok": False, "game": game}
        deadline = time.monotonic() + 60
        st = self.pilot.state()
        while time.monotonic() < deadline and not (st.get("map") == m and inside(st)):
            time.sleep(0.25)
            st = self.pilot.state()
        time.sleep(3.0)
        return {"ok": inside(self.pilot.state()), "game": game}

    def scan(self, direction: str, bike: str = "mach") -> tuple[list[dict], list[dict]]:
        i = self.cid()
        n0 = len(self.run.result.lines)
        self.lua(f'{{ id = {i}, op = "scan", dir = "{direction}", bike = "{bike}" }}')
        t, line, _ = line_time(self.run, n0, f"KB_SCAN_END id={i}", 120)
        if line is None:
            line_time(self.run, n0, f"KB_SCAN_FAIL id={i}", 1)
            return [], []
        runs = [facts(ln) for ln in self.run.result.lines[n0:] if ln.startswith(f"KB_SCAN id={i} ")]
        rails = [facts(ln) for ln in self.run.result.lines[n0:] if ln.startswith(f"KB_RAIL id={i} ")]
        return runs, rails

    def ride(self, segs: list[dict], sampler: Sampler, label: str) -> dict:
        """Runs a ride; returns the driver's facts lines, the samples and the derived numbers."""
        i = self.cid()
        n0 = len(self.run.result.lines)
        before = self.pilot.state()
        moves0 = before.get("move") or {}
        stuck0 = self.stuck_count()
        parts = []
        for s in segs:
            p = [f"frames = {int(s['frames'])}"]
            if s.get("dir"):
                p.append(f'dir = "{s["dir"]}"')
            if s.get("b"):
                p.append("b = true")
            parts.append("{ " + ", ".join(p) + " }")
        t_send = time.monotonic()
        self.lua(f'{{ id = {i}, op = "ride", segs = {{ {", ".join(parts)} }} }}')
        t_start, _, _ = line_time(self.run, n0, f"KB_RIDE id={i} seg=1 ", 30)
        total = sum(int(s["frames"]) for s in segs)
        t_end, end_line, _ = line_time(self.run, n0, f"KB_RIDE_END id={i} ", total / 60.0 + 30)
        time.sleep(2.5)  # the client's last legs and the settle
        after = self.pilot.state()
        lines = [ln for ln in self.run.result.lines[n0:] if ln.startswith(("KB_RT", "KB_RIDE"))]
        samples = sampler.since(t_send)
        out = {"label": label, "end": facts(end_line) if end_line else None}
        # (b) the ride's start to the client's first motion (the player moved more than 0.05 blocks)
        p0 = (before.get("player") or {})
        first_move = None
        for t, st in samples:
            pl = st.get("player") or {}
            if t_start and t >= t_start and "x" in pl and "x" in p0 and (abs(pl["x"] - p0["x"]) > 0.05 or abs(pl["z"] - p0["z"]) > 0.05):
                first_move = t
                break
        out["start_to_motion_ms"] = round((first_move - t_start) * 1000) if first_move and t_start else None
        # (a) lag per second
        per_sec: dict[int, int] = {}
        fps, modes = [], []
        last_mode = None
        for t, st in samples:
            if t_start is None:
                break
            sec = int(t - t_start)
            lag = lag_cells(st)
            if lag is not None and st.get("map") and sec >= 0:
                per_sec[sec] = max(per_sec.get(sec, 0), lag)
            if isinstance(st.get("fps"), (int, float)):
                fps.append(st["fps"])
            mode = st.get("mode")
            if mode != last_mode:
                modes.append([round((t - t_start) * 1000), mode])
                last_mode = mode
        out["lag_per_second"] = [per_sec[k] for k in sorted(per_sec)]
        out["lag_max"] = max(per_sec.values()) if per_sec else None
        out["fps_avg"] = round(sum(fps) / len(fps), 1) if fps else None
        out["fps_min"] = min(fps) if fps else None
        out["client_modes"] = modes[:40]
        moves1 = after.get("move") or {}
        out["moves"] = {k: moves1.get(k, 0) - moves0.get(k, 0) for k in sorted(set(moves1) | set(moves0))
                        if isinstance(moves1.get(k, 0), (int, float)) and moves1.get(k, 0) != moves0.get(k, 0)}
        out["stuck"] = self.stuck_count() - stuck0
        out["end_inside"] = inside(after)
        out["hidden_end"] = [after.get("map"), after.get("x"), after.get("y")]
        # the engine's steps: frames per step as the driver saw them begin
        steps, sf_seen = 0, {}
        prev_t = None
        for ln in lines:
            if not ln.startswith("KB_RT"):
                continue
            f = facts(ln)
            t = (f.get("t"), f.get("c"))
            if f.get("mv") == "1" and t != prev_t:
                steps += 1
                sf_seen[f.get("sf")] = sf_seen.get(f.get("sf"), 0) + 1
            prev_t = t if f.get("mv") == "1" else None
        out["engine_steps"] = steps
        out["step_frames"] = sf_seen
        out["rt_lines"] = len(lines)
        out["driver"] = lines[:400]
        return out

    def stuck_count(self) -> int:
        try:
            text = self.client_log.read_text(encoding="utf-8", errors="replace")
        except OSError:
            return 0
        return text.count("forced_stuck")

    def server_complaints(self) -> dict:
        try:
            text = self.client_log.read_text(encoding="utf-8", errors="replace")
        except OSError:
            return {}
        return {"moved_too_quickly": text.count("moved too quickly"), "moved_wrongly": text.count("moved wrongly")}


def rail_runs(rails: list[dict]) -> list[dict]:
    """Vertical rail cells grouped into runs (x, y0..y1)."""
    cells = sorted((int(r["x"]), int(r["y"])) for r in rails if r.get("kind") == "v")
    runs = []
    for x, y in cells:
        if runs and runs[-1]["x"] == x and runs[-1]["y1"] == y - 1:
            runs[-1]["y1"] = y
        else:
            runs.append({"x": x, "y0": y, "y1": y})
    return sorted(runs, key=lambda r: r["y0"] - r["y1"])


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--world", default="LandSky6")
    ap.add_argument("--record")
    ap.add_argument("--timeout", type=int, default=2400)
    ap.add_argument("--only", default="", help="comma list of: mach,acro,classifier,rails,latency")
    a = ap.parse_args(argv)
    only = set(filter(None, a.only.split(",")))
    want = (lambda k: not only or k in only)
    source = SAVES / a.world
    if not (source / "level.dat").is_file():
        print(f"no world {a.world}", file=sys.stderr)
        return 2
    world = a.world + "BikeG3"
    if (SAVES / world).exists():
        shutil.rmtree(SAVES / world)
    shutil.copytree(source, SAVES / world)
    pilot_dir = REPORTS / "bike_follow_g3"
    pilot_dir.mkdir(parents=True, exist_ok=True)
    home = make_home("bike_follow_g3", "emerald")
    game_env = {"KB_DATASET_MEMO": "30", "KB_PACE": "60", "KB_DRIVER_SECONDS": str(a.timeout + 600)}
    with launch("kb_union_off.lua", game="emerald", install=home, echo=False, extra_env=game_env) as off:
        say("union off:", off.wait(timeout=180).marker("KB_UNION_OFF"))
    commands = pilot_dir / "game_commands.lua"
    commands.write_text("", encoding="utf-8")
    pilot = Pilot(pilot_dir)
    run = launch("kb_g3spots.lua", game="emerald", install=home, echo=False,
                 extra_env=dict(game_env, KB_TEST_PORT=str(PORT), KB_SPOT_COMMANDS=str(commands)))
    client = None
    report: dict = {"world": a.world, "rides": []}
    sampler = None
    try:
        say("emerald:", run.wait_marker("KB_SERVING", timeout=300))
        java_home = os.environ.get("KB_JAVA_HOME") or os.environ.get("JAVA_HOME") or "/opt/java/openjdk"
        client = subprocess.Popen(client_command("emerald", world, pilot_dir, PORT), cwd=GRADLE_DIR,
                                  env=dict(os.environ, JAVA_HOME=java_home), stdin=subprocess.DEVNULL,
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        client_log = pilot_dir / "client.log"
        threading.Thread(target=stream, args=(client, client_log), daemon=True).start()
        deadline = time.monotonic() + 1200
        while pilot.state().get("phase") != "ready":
            if time.monotonic() > deadline or client.poll() is not None:
                raise Stuck("the client never reached the world")
            time.sleep(1.0)
        say("client in the world; follow:", pilot.do("follow", game="emerald").get("ok"))
        deadline = time.monotonic() + 180
        while pilot.state().get("map") != "EM_LITTLEROOT_TOWN" and time.monotonic() < deadline:
            time.sleep(0.5)
        time.sleep(15)
        rig = Rig(run, pilot, commands, client_log)
        sampler = Sampler(pilot)
        if a.record:
            pilot.do("record", file=a.record, timeout=30)

        # Route 110: the longest straight runs north a Mach Bike may ride
        rig.go("EM_ROUTE110", None, None, "up", None)  # the map's first warp's cell: the scan needs the map loaded
        runs, _ = rig.scan("up", "mach")
        report["r110_runs"] = runs
        say("route 110 runs up:", json.dumps(runs))
        best = runs[0] if runs else None

        if best and want("mach"):
            x, y, n = int(best["x"]), int(best["y"]), int(best["cells"])
            say("mach start:", rig.go("EM_ROUTE110", x, y, "up", "mach"))
            cells = min(n, 32)
            frames = 16 + 8 + 4 * max(0, cells - 2) + 6
            r = rig.ride([{"dir": "up", "frames": frames}, {"frames": 90}], sampler, f"mach_up_{cells}")
            report["rides"].append(r)
            say(json.dumps({k: r[k] for k in ("label", "end", "lag_max", "lag_per_second", "moves", "stuck", "fps_avg",
                                               "start_to_motion_ms", "engine_steps", "step_frames", "end_inside")}))
            # back down at full speed too (the second direction, and a stop on release)
            r = rig.ride([{"dir": "down", "frames": frames}, {"frames": 90}], sampler, f"mach_down_{cells}")
            report["rides"].append(r)
            say(json.dumps({k: r[k] for k in ("label", "end", "lag_max", "lag_per_second", "moves", "stuck", "fps_avg",
                                               "start_to_motion_ms", "engine_steps", "step_frames", "end_inside")}))

        if best and want("acro"):
            x, y, n = int(best["x"]), int(best["y"]), int(best["cells"])
            say("acro start:", rig.go("EM_ROUTE110", x, y, "up", "acro"))
            cells = min(n, 24)
            # a wheelie run: B first (the wheelie stands), then B with up (moving at 8 frames)
            r = rig.ride([{"b": True, "frames": 20}, {"dir": "up", "b": True, "frames": 8 * cells + 10}, {"frames": 60}],
                         sampler, "acro_wheelie_run")
            report["rides"].append(r)
            say(json.dumps({k: r[k] for k in ("label", "end", "lag_max", "moves", "stuck", "engine_steps", "step_frames",
                                               "end_inside")}))
            r = rig.ride([{"dir": "down", "frames": 6 * cells + 10}, {"frames": 60}], sampler, "acro_plain_run")
            report["rides"].append(r)
            say(json.dumps({k: r[k] for k in ("label", "end", "lag_max", "moves", "stuck", "engine_steps", "step_frames",
                                               "end_inside")}))

        if best and want("classifier"):
            x, y = int(best["x"]), int(best["y"])
            say("classifier start:", rig.go("EM_ROUTE110", x, y + 2, "up", "acro"))
            plan = [
                ("acro_tap_b_wheelie_standing", [{"b": True, "frames": 6}, {"frames": 60}]),
                ("acro_hold_b_bunny_hop", [{"b": True, "frames": 150}, {"frames": 60}]),
                ("acro_turn_in_place", [{"dir": "left", "frames": 3}, {"frames": 40}, {"dir": "up", "frames": 3}, {"frames": 40}]),
                ("acro_side_jump", [{"dir": "up", "frames": 3}, {"frames": 30}, {"dir": "left", "b": True, "frames": 4},
                                    {"frames": 60}]),
                ("acro_turn_jump", [{"dir": "down", "b": True, "frames": 4}, {"frames": 60}]),
                ("acro_hop_moving", [{"b": True, "frames": 50}, {"dir": "up", "b": True, "frames": 40}, {"frames": 60}]),
            ]
            for label, segs in plan:
                r = rig.ride(segs, sampler, label)
                report["rides"].append(r)
                say(label, json.dumps({k: r[k] for k in ("end", "client_modes", "moves")}))
            # collides: into the run's far end (the Mach, then the Acro)
            for bike in ("mach", "acro"):
                say(f"{bike} collide start:", rig.go("EM_ROUTE110", int(best["x"]), int(best["y"]) - int(best["cells"]) + 1,
                                                    "up", bike))
                r = rig.ride([{"dir": "up", "frames": 90}, {"frames": 40}], sampler, f"{bike}_collide")
                report["rides"].append(r)
                say(f"{bike}_collide", json.dumps({k: r[k] for k in ("end", "client_modes", "moves")}))

        if want("rails"):
            rig.go("EM_ROUTE119", None, None, "up", None)
            runs119, rails = rig.scan("up", "acro")
            rr = rail_runs(rails)
            report["r119_rails"] = {"cells": len(rails), "runs": rr[:5], "runs_up": runs119}
            say("route 119 rails:", json.dumps(rr[:5]))
            if rr:
                top = rr[0]
                # from the cell below the run, along it in a wheelie
                say("rail start:", rig.go("EM_ROUTE119", top["x"], top["y1"] + 1, "up", "acro"))
                n = top["y1"] - top["y0"] + 1
                r = rig.ride([{"b": True, "frames": 20}, {"dir": "up", "b": True, "frames": 8 * (n + 3) + 10}, {"frames": 60}],
                             sampler, "acro_rail_wheelie")
                report["rides"].append(r)
                say(json.dumps({k: r[k] for k in ("label", "end", "lag_max", "moves", "stuck", "engine_steps", "end_inside")}))

        if want("latency"):
            # (b) the client's sprint key holds B on foot (move.hold b): the pilot's key to the engine's B
            say("latency start:", rig.go("EM_ROUTE110", int(best["x"]) if best else None, int(best["y"]) if best else None, "up", None))
            i = rig.cid()
            n0 = len(run.result.lines)
            rig.lua(f'{{ id = {i}, op = "watch", frames = 1500 }}')
            line_time(run, n0, f"KB_BTN id={i} ", 10)
            n1 = len(run.result.lines)
            delays_on, delays_off = [], []
            for _ in range(8):
                t0 = time.monotonic()
                pilot.send("key", key="sprint", hold=20)
                t1, _, n1 = line_time(run, n1, f"KB_BTN id={i} f=", 3)
                if t1:
                    delays_on.append(round((t1 - t0) * 1000))
                # the release: 20 ticks after the press
                t2, _, n1 = line_time(run, n1, f"KB_BTN id={i} f=", 4)
                if t2 and t1:
                    delays_off.append(round((t2 - t1) * 1000) - 1000)
                time.sleep(1.2)
            report["latency"] = {"key_to_engine_b_ms": delays_on, "release_minus_hold_ms": delays_off}
            say("latency:", json.dumps(report["latency"]))
        report["server"] = rig.server_complaints()
        say("server:", json.dumps(report["server"]))
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
        (pilot_dir / "emerald.log").write_text("\n".join(run.result.lines) + "\n", encoding="utf-8")
    (REPORTS / "g3_bike_follow.json").write_text(json.dumps(report, indent=1, sort_keys=True), encoding="utf-8")
    say("== report", REPORTS / "g3_bike_follow.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
