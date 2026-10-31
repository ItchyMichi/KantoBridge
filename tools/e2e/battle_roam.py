"""BATTLE_ROAM_PLAN's live check (docs/specs/BATTLE_ROAM_PLAN.md): the real dev client in Oak's lab, Blue's rival battle in
Cobblemon, the battle screen minimised (Cobblemon's R), a walk about, and the snap back onto the hidden player's cell once
the battle ends. The client is steered through RemotePilot (story_video.py's Pilot and Player).

    xvfb-run -a -s "-screen 0 1280x720x24" python tools/e2e/battle_roam.py --world PassE2E

1. A fresh Blue data home (story_video.make_home; its Union Room off) and a copy of --world (Kanto built).
2. drivers/kb_battle.lua: a new game in Oak's lab below a starter ball (KB_LAB_READY); the dev client follows Blue.
3. The starter (A on the ball, the prompts answered as story_video does), then steps down to the rival's trigger row
   until his battle starts in Cobblemon.
4. In the battle: `roam` in each direction (the screen minimised, forward + jump + sneak held): the lock is ROAM, the
   player moves, never rises (no jump) or crouches, stays within the fence, and the hidden cell never changes. A long walk
   checks the fence holds.
5. The pilot fights (battle on); once the battle is over the player stands on the hidden player's cell again.

Prints PASS or FAIL lines and exits 0 only when every check passed. Data safety (docs/PLAN.md section 0.3): map symbols,
cells, distances and counts only.
"""
from __future__ import annotations

import argparse
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

PILOT_DIR = sv.REPO / "local" / "reports" / "roam"
PORT = 27243
RADIUS = 6.0  # BattleRoam.RADIUS
CELL = 2  # Scale.CELL_BLOCKS

checks: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> bool:
    checks.append((name, ok, detail))
    sv.say("PASS" if ok else "FAIL", name, detail)
    return ok


def client_command(world: str) -> list[str]:
    return ["./gradlew", "--no-daemon", "--console=plain", "runClient", "-Pkb.autopilot=remote", f"-Pkb.autopilotWorld={world}",
            f"-Pkb.autopilotPilotDir={PILOT_DIR}", "-Pkb.autopilotFollow=blue", "-Pkb.autopilotQuit=true",
            f"-Pkb.links=blue:{PORT}"]


def on_hidden_cell(st: dict) -> tuple[bool, str]:
    hb, pl = st.get("hidden_block") or {}, st.get("player") or {}
    if "x" not in hb or "x" not in pl:
        return False, "no position"
    inside = hb["x"] <= pl["x"] < hb["x"] + CELL and hb["z"] <= pl["z"] < hb["z"] + CELL
    return inside, f"player {pl['x']:.2f},{pl['z']:.2f} cell {hb['x']},{hb['z']}"


def to_the_battle(p: sv.Player, timeout: float = 300.0) -> dict:
    """Walks towards the lab's door (planned on the export's cells, round the tables), answering the rival's texts,
    until Cobblemon's battle runs (his trigger row is on the way)."""
    lab = sv.map_of("OAKS_LAB")
    door = {(x, y) for x, y, dest in lab.warps if dest == "PALLET_TOWN"}
    objects = {(x, y) for x, y, _ in lab.objects}
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        st = p.st()
        if st.get("battle"):
            return st
        if st.get("ui"):
            p.answer(st)
        elif st.get("map") == "OAKS_LAB" and not st.get("locked") and st.get("mode") in (None, "idle"):
            pos = (st["x"], st["y"])
            path = sv.plan(lab, pos, door, objects - door) or sv.plan(lab, pos, door)
            if not path:
                raise sv.Stuck(f"no path to the lab's door from {pos}")
            p.pilot.do("step", dir=path[0], timeout=20)
        elif st.get("map") != "OAKS_LAB":
            raise sv.Stuck(f"left the lab without a battle ({st.get('map')})")
        time.sleep(0.15)
    raise sv.Stuck("the rival's battle never started")


def roam_checks(p: sv.Player, start: dict) -> None:
    cell = (start.get("map"), start.get("x"), start.get("y"))
    best = 0.0
    for d in ("down", "left", "right", "up"):
        r = p.pilot.do("roam", dir=d, ticks=30, timeout=30)
        st = p.st()
        sv.say("roam", d, {k: r.get(k) for k in ("ok", "moved", "from_anchor", "rise", "crouched", "lock")})
        best = max(best, r.get("moved") or 0.0)
        check(f"roam {d}: lock is roam", r.get("lock") == "roam", str(r.get("lock")))
        # a step-up (a slab, 0.5) is fine; a jump rises past 1
        check(f"roam {d}: no jump", (r.get("rise") or 0.0) < 0.7, f"rise {r.get('rise')}")
        check(f"roam {d}: no crouch", not r.get("crouched"))
        check(f"roam {d}: inside the fence", (r.get("from_anchor") or 0.0) <= RADIUS + 0.75, f"from_anchor {r.get('from_anchor')}")
        check(f"roam {d}: the hidden player stayed", (st.get("map"), st.get("x"), st.get("y")) == cell,
              f"{(st.get('map'), st.get('x'), st.get('y'))} vs {cell}")
    check("roaming moved the player", best >= 2.0, f"best {best:.2f} blocks")
    # the long walk, the way that went farthest, held against the fence
    r = p.pilot.do("roam", dir="down", ticks=120, timeout=40)
    check("the fence holds a long walk", (r.get("from_anchor") or 0.0) <= RADIUS + 0.75, f"from_anchor {r.get('from_anchor')}")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--world", default="PassE2E", help="a built world with Kanto in local/minecraft/saves (copied first)")
    parser.add_argument("--timeout", type=int, default=1800)
    args = parser.parse_args(argv)

    source = sv.SAVES / args.world
    if not (source / "level.dat").is_file():
        print(f"no world {args.world}", file=sys.stderr)
        return 2
    world = args.world + "Roam"
    if (sv.SAVES / world).exists():
        shutil.rmtree(sv.SAVES / world)
    shutil.copytree(source, sv.SAVES / world)
    sv.say("world copied:", world)

    home = sv.make_home("kanto", "blue")
    game_env = {"KB_DATASET_MEMO": "30", "KB_PACE": "60", "KB_DRIVER_SECONDS": str(args.timeout + 600)}
    with launch("kb_union_off.lua", game="blue", install=home, echo=False, extra_env=game_env) as off:
        sv.say("union off", off.wait(timeout=180).marker("KB_UNION_OFF"))

    PILOT_DIR.mkdir(parents=True, exist_ok=True)
    pilot = sv.Pilot(PILOT_DIR)
    p = sv.Player(pilot)
    blue = launch("kb_battle.lua", game="blue", install=home, echo=False, extra_env=dict(game_env, KB_TEST_PORT=str(PORT)))
    client = None
    try:
        sv.say("blue:", blue.wait_marker("KB_LAB_READY", timeout=300))
        java_home = os.environ.get("KB_JAVA_HOME") or os.environ.get("JAVA_HOME") or "/opt/java/openjdk"
        client = subprocess.Popen(client_command(world), cwd=sv.GRADLE_DIR, env=dict(os.environ, JAVA_HOME=java_home),
                                  stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        threading.Thread(target=sv.stream, args=(client, PILOT_DIR / "client.log"), daemon=True).start()
        deadline = time.monotonic() + 900
        while pilot.state().get("phase") != "ready":
            if time.monotonic() > deadline or client.poll() is not None:
                raise sv.Stuck("the client never reached the world")
            time.sleep(1.0)
        p.settle(quiet=2.0, timeout=300)
        sv.say("== the starter")
        p.interact("up")
        p.settle(quiet=3.0, timeout=300)
        sv.say("== down to the rival's battle")
        st = to_the_battle(p)
        # Cobblemon asking, its screen up: held
        st = p.settle(timeout=120, only=True, until=lambda s: s.get("must_choose"))
        if st.get("screen") != "BattleGUI":
            sv.say("the battle screen isn't up by itself:", st.get("screen"))
        else:
            check("the battle screen holds the player", st.get("lock") == "battle" and st.get("locked"),
                  f"{st.get('lock')} {st.get('locked')}")
        start = st
        sv.say("== roaming")
        roam_checks(p, start)
        sv.say("== the battle, fought")
        pilot.do("battle", mode="on")
        p.settle(timeout=600, only=True, until=lambda s: not s.get("battle"))
        st = p.settle(quiet=3.0, timeout=300)
        inside, detail = on_hidden_cell(st)
        check("back on the hidden player's cell after the battle", inside, detail)
        teleports = (st.get("move") or {}).get("teleports")
        sv.say("move counts:", st.get("move"), "teleports", teleports)
    except sv.Stuck as e:
        st = pilot.state()
        check("no stall", False, str(e))
        sv.say("state:", {k: st.get(k) for k in ("map", "x", "y", "mode", "screen", "ui", "lock", "locked", "battle", "result")})
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
        blue.kill()
        blue.wait(timeout=30)
        (PILOT_DIR / "blue.log").write_text("\n".join(list(blue.result.lines)) + "\n", encoding="utf-8")
    failed = [name for name, ok, _ in checks if not ok]
    sv.say(f"{len(checks) - len(failed)}/{len(checks)} checks passed")
    return 0 if checks and not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
