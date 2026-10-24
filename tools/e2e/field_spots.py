"""The field audit's spots in the real client (docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md §4.3, FA6): for each mechanism
class the live sweep moved the hidden player through (tools/e2e/g{1,2,3}_field_check.py sweep), one row is replayed
with the real dev client: the hidden game is put on the row's start cell (with its vehicle: surfing or a bike), the
client follows it there, the client's player takes the row's step, and once both have settled the Minecraft player
must stand in the hidden player's cell, on its map (rule C). The ledge hop, the ice slide, the current, the cracked
floor's fall, the waterfall, the spinner, each warp kind, a coord event's escort.

    xvfb-run -a -s "-screen 0 1280x720x24" python tools/e2e/field_spots.py --game emerald --world LandSky3 \\
        [--record /workspace/videos/kanto-bridge-emerald-field-spots.mp4] [--only ledge_jump,ice] [--per-class 1]

The game runs the story check's spot driver (drivers/kb_g{1,2,3}spots.lua: its `go` command, plus `vehicle`); the
client is RemotePilot. Output: class names, map symbols, cells, counts and booleans only; the per-spot results go to
local/reports/g*_field_spots.json. A video, when asked for, shows the user's own game: it stays on the pod's volume.
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
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

from run_gen1recomp import REPORTS, launch  # noqa: E402
from story_video import Pilot, Stuck, make_home, say  # noqa: E402

GRADLE_DIR = REPO / "fabric-bridge"
SAVES = REPO / "local" / "minecraft" / "saves"
GAMES = {
    "emerald": {"prefix": "g3", "driver": "kb_g3spots.lua", "town": "EM_LITTLEROOT_TOWN", "port": 27250, "wire": ""},
    "crystal": {"prefix": "g2", "driver": "kb_g2spots.lua", "town": "NEW_BARK_TOWN", "port": 27251, "wire": "G2_"},
    "blue": {"prefix": "g1", "driver": "kb_g1spots.lua", "town": "PALLET_TOWN", "port": 27252, "wire": ""},
}
# classes whose rows are about a move the game makes, or a warp (the spot checks the landing)
MOVE_CLASSES = ("ledge_jump", "ledge", "ice", "current", "waterfall", "slide_tile", "slippery_floor", "muddy_slope",
                "cracked_floor", "cracked_floor_hole", "thin_ice_cell", "spinner", "seafoam_current", "whirlpool",
                "door_forced", "pit", "scripted_hole", "coord", "badge_gate", "forced_bike", "forced_surf")


def pick_spots(prefix: str, per_class: int, only: set[str]) -> list[dict]:
    """Rows of the sweep the engine moved the player in, with no problem found, one (or per_class) per class."""
    spots, seen = [], {}
    for af in sorted(REPORTS.glob(f"{prefix}_field_sweep_analysis_*.json")):
        tag = af.name[len(f"{prefix}_field_sweep_analysis"):-len(".json")]
        sweep_f = REPORTS / f"{prefix}_field_sweep{tag}.json"
        if not sweep_f.is_file():
            continue
        recs = {r["n"]: r for r in json.loads(sweep_f.read_text(encoding="utf-8"))["rows"]}
        for e in json.loads(af.read_text(encoding="utf-8"))["rows"]:
            cls = e["cls"]
            base = cls.split(":")[0]
            if base not in MOVE_CLASSES and not cls.startswith("warp:"):
                continue
            if only and cls not in only and base not in only:
                continue
            rec = recs.get(e["n"]) or {}
            start, end = rec.get("start") or {}, rec.get("end") or {}
            if e.get("err") or e.get("problems") and any(not p.startswith("N:") for p in e["problems"]):
                continue
            if not rec.get("dir") or not start.get("x") and start.get("x") != 0:
                continue
            if [end.get("map"), end.get("x"), end.get("y")] == [rec.get("map"), start.get("x"), start.get("y")]:
                continue  # the engine didn't move the player: nothing to follow
            if seen.get(cls, 0) >= per_class:
                continue
            seen[cls] = seen.get(cls, 0) + 1
            spots.append({"cls": cls, "map": rec["map"], "x": start["x"], "y": start["y"], "dir": rec["dir"],
                          "vehicle": rec.get("state") if rec.get("state") not in (None, "walk") else None,
                          "engine_end": [end.get("map"), end.get("x"), end.get("y")], "row": e["n"], "tag": tag})
    return spots


def lua_go(spot_id: int, game: str, spot: dict) -> str:
    parts = [f"id = {spot_id}", 'op = "go"', f'map = "{spot["map"]}"', f'facing = "{spot["dir"]}"', f"x = {spot['x']}",
             f"y = {spot['y']}"]
    if game == "crystal":
        parts += ["events = { }", "flags = { }", "scenes = { }", "mapscenes = { }", "mem = { }"]
    elif game == "blue":
        parts += ["flags = { }"]
    else:
        parts += ["flags = { }", "vars = { " + ", ".join(f"[{int(k)}] = {int(v)}" for k, v in (spot.get("vars") or {}).items()) + " }"]
    if spot.get("vehicle"):
        parts.append(f'vehicle = "{spot["vehicle"]}"')
    return "{ " + ", ".join(parts) + " }"


def wait_spot(run, spot_id: int, timeout: float = 90.0) -> dict:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for line in list(run.result.lines):
            if line.startswith(f"KB_SPOT id={spot_id} ") or line.startswith(f"KB_SPOT_FAIL id={spot_id} "):
                return dict(p.split("=", 1) for p in line.split()[1:] if "=" in p) | {"marker": line.split()[0]}
        time.sleep(0.2)
    raise Stuck(f"spot {spot_id}: no KB_SPOT within {timeout:.0f} s")


def settled(pilot: Pilot, quiet: float = 2.0, timeout: float = 20.0) -> dict:
    """The pilot's state once the hidden player has stood still (same cell, mode idle) for `quiet` seconds."""
    last, since = None, time.monotonic()
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        st = pilot.state()
        key = (st.get("map"), st.get("x"), st.get("y"), st.get("mode"), (st.get("player") or {}).get("x"),
               (st.get("player") or {}).get("z"))
        if key != last:
            last, since = key, time.monotonic()
        elif time.monotonic() - since >= quiet and st.get("mode") == "idle":
            return st
        time.sleep(0.25)
    return pilot.state()


def inside(st: dict) -> bool:
    hb, pl = st.get("hidden_block") or {}, st.get("player") or {}
    if "x" not in hb or "x" not in pl:
        return False
    return hb["x"] - 0.05 <= pl["x"] <= hb["x"] + 2.05 and hb["z"] - 0.05 <= pl["z"] <= hb["z"] + 2.05


def client_command(game: str, world: str, pilot_dir: Path, port: int) -> list[str]:
    return ["./gradlew", "--no-daemon", "--console=plain", "runClient", "-Pkb.autopilot=remote", f"-Pkb.autopilotWorld={world}",
            f"-Pkb.autopilotPilotDir={pilot_dir}", f"-Pkb.autopilotFollow={game}", "-Pkb.autopilotQuit=true",
            f"-Pkb.links={game}:{port}"]


def stream(proc, log_path: Path):
    with open(log_path, "w", encoding="utf-8", buffering=1) as log:
        for raw in iter(proc.stdout.readline, b""):
            log.write(raw.decode("utf-8", errors="replace").rstrip() + "\n")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--game", required=True, choices=sorted(GAMES))
    ap.add_argument("--world", default="LandSky3")
    ap.add_argument("--record")
    ap.add_argument("--only", default="")
    ap.add_argument("--per-class", type=int, default=1)
    ap.add_argument("--timeout", type=int, default=3600)
    ap.add_argument("--plan", action="store_true", help="print the spots only")
    a = ap.parse_args(argv)
    g = GAMES[a.game]
    spots = pick_spots(g["prefix"], a.per_class, set(filter(None, a.only.split(","))))
    # Emerald's Sky Pillar needs its later state (as the sweep set it)
    for s in spots:
        if a.game == "emerald" and s["map"].startswith("EM_SKY_PILLAR_"):
            scan = json.loads((REPORTS / "g3_story_scan.json").read_text(encoding="utf-8"))
            ids = {v: int(k) for k, v in ((scan.get("names") or {}).get("vars") or {}).items()}
            if "VAR_SKY_PILLAR_STATE" in ids:
                s["vars"] = {ids["VAR_SKY_PILLAR_STATE"]: 2}
    say("spots:", len(spots), json.dumps([(s["cls"], s["map"]) for s in spots]))
    if a.plan:
        return 0
    source = SAVES / a.world
    if not (source / "level.dat").is_file():
        print(f"no world {a.world}", file=sys.stderr)
        return 2
    world = a.world + "Field" + g["prefix"].upper()
    if (SAVES / world).exists():
        shutil.rmtree(SAVES / world)
    shutil.copytree(source, SAVES / world)
    pilot_dir = REPORTS / f"field_spots_{g['prefix']}"
    pilot_dir.mkdir(parents=True, exist_ok=True)
    home = make_home(f"field_spots_{g['prefix']}", a.game)
    game_env = {"KB_DATASET_MEMO": "30", "KB_PACE": "60", "KB_DRIVER_SECONDS": str(a.timeout + 600)}
    with launch("kb_union_off.lua", game=a.game, install=home, echo=False, extra_env=game_env) as off:
        say("union off:", off.wait(timeout=180).marker("KB_UNION_OFF"))
    commands = pilot_dir / "game_commands.lua"
    commands.write_text("", encoding="utf-8")
    pilot = Pilot(pilot_dir)
    run = launch(g["driver"], game=a.game, install=home, echo=False,
                 extra_env=dict(game_env, KB_TEST_PORT=str(g["port"]), KB_SPOT_COMMANDS=str(commands)))
    client = None
    results = []
    code = 1
    try:
        say(a.game + ":", run.wait_marker("KB_SERVING", timeout=300))
        java_home = os.environ.get("KB_JAVA_HOME") or os.environ.get("JAVA_HOME") or "/opt/java/openjdk"
        env = dict(os.environ, JAVA_HOME=java_home)
        client = subprocess.Popen(client_command(a.game, world, pilot_dir, g["port"]), cwd=GRADLE_DIR, env=env,
                                  stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        threading.Thread(target=stream, args=(client, pilot_dir / "client.log"), daemon=True).start()
        deadline = time.monotonic() + 1200
        while pilot.state().get("phase") != "ready":
            if time.monotonic() > deadline or client.poll() is not None:
                raise Stuck("the client never reached the world")
            time.sleep(1.0)
        say("client in the world; follow:", pilot.do("follow", game=a.game).get("ok"))
        town = g["wire"] + g["town"]
        deadline = time.monotonic() + 180
        while pilot.state().get("map") != town and time.monotonic() < deadline:
            time.sleep(0.5)
        time.sleep(15)
        if a.record:
            pilot.do("record", file=a.record, timeout=30)
        for i, spot in enumerate(spots, 1):
            out = dict(spot)
            started = time.monotonic()
            try:
                with open(commands, "a", encoding="utf-8") as f:
                    f.write(lua_go(i, a.game, spot) + "\n")
                game = wait_spot(run, i)
                out["game"] = {k: game.get(k) for k in ("marker", "map", "x", "y")}
                if game["marker"] != "KB_SPOT":
                    out["result"] = "the game didn't reach the spot"
                else:
                    want = g["wire"] + spot["map"]
                    deadline = time.monotonic() + 60
                    st = pilot.state()
                    while time.monotonic() < deadline and not (st.get("map") == want and inside(st)):
                        time.sleep(0.25)
                        st = pilot.state()
                    time.sleep(3.0)  # the region's cells and the story state settle
                    before = (pilot.state().get("move") or {}).copy()
                    res = pilot.do("step", dir=spot["dir"], timeout=30)
                    st = settled(pilot)
                    after = st.get("move") or {}
                    out["step"] = {"ok": res.get("ok")}
                    out["hidden"] = [st.get("map"), st.get("x"), st.get("y")]
                    out["inside"] = inside(st)
                    out["moves"] = {k: after.get(k, 0) - before.get(k, 0) for k in ("steps", "blocked", "snaps", "teleports",
                                                                                     "forced", "syncs") if after.get(k, 0) != before.get(k, 0)}
                    ee = [g["wire"] + spot["engine_end"][0], spot["engine_end"][1], spot["engine_end"][2]]
                    same_end = out["hidden"] == ee
                    if out["inside"] and same_end:
                        out["result"] = "PASS"
                    elif out["inside"]:
                        out["result"] = "PASS (the game ended elsewhere than in the sweep)"
                    else:
                        out["result"] = "FAIL (the client is not in the hidden player's cell)"
            except Stuck as e:
                out["result"] = f"STUCK: {e}"
            out["seconds"] = round(time.monotonic() - started, 1)
            results.append(out)
            say(json.dumps({k: out.get(k) for k in ("cls", "map", "result", "hidden", "engine_end", "moves", "seconds")}, sort_keys=True))
        code = 0 if all(r.get("result", "").startswith("PASS") for r in results) else 1
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
        (pilot_dir / f"{a.game}.log").write_text("\n".join(run.result.lines) + "\n", encoding="utf-8")
    (REPORTS / f"{g['prefix']}_field_spots.json").write_text(json.dumps(results, indent=1, sort_keys=True), encoding="utf-8")
    passed = sum(1 for r in results if r.get("result", "").startswith("PASS"))
    say(f"== {passed} of {len(results)} spots passed")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
