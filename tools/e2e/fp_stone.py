"""FOREIGN_POKEMON_PLAN FP2's end-to-end check (the user's request, 2026-10-24): a foreign Pokémon in Blue evolved in
Cobblemon by a stone found and used in the hidden game, then saved and loaded again on both sides. One real Blue (the
test install's import, a fresh data home) beside the real dev client, which this script steers through RemotePilot
(story_video.py's Pilot and Player).

    xvfb-run -a -s "-screen 0 1280x720x24" python tools/e2e/fp_stone.py --world FPKanto [--shots /workspace/fp]

--world is a built Kanto region world in local/minecraft/saves (build_world.py --maps all --region kanto --slot 0); it
is copied first and the copy is played.

1. Blue with drivers/kb_fpstone.lua, scene first: New Game without the intro, the hidden player beside Mt. Moon 1F's
   Moon Stone ball, facing it.
2. The dev client joins the world, following Blue. Cobblemon's party gets a Bulbasaur and a level 20 Skitty (national
   dex 300, past Blue's 151): Blue shows Skitty as a visitor, second, named SKITTY.
3. A on the ball (the game's own pickup), then START, ITEM, the stone, USE, the second member: gen1-bridge sends
   party.item, Cobblemon evolves Skitty with the Moon Stone (Delcatty, 301), the next sync makes the visitor DELCATTY.
4. START, SAVE, YES: the hidden save and Minecraft's save. The client quits (the world is saved), Blue is stopped.
5. Blue again (scene again: its title) and the client again in the same world; once the client has bound the world,
   the driver continues the save as the title's CONTINUE does (the client's pick on the title menu times out in
   gen1-bridge today: an open issue of its own).
6. Both sides are read again: Cobblemon's party and the hidden records must still be Bulbasaur and Delcatty, the
   visitor with its real dex and name.

Checks read the driver's KB_FP_PARTY lines (slot kinds, dex numbers, levels, HP, uid prefixes and nickname hashes)
and the pilot's party answers (Cobblemon's species names and numbers). Screenshots (--shots) show the user's own game:
they are for the user and stay on the pod's volume; this script never opens them. Data safety (docs/PLAN.md §0.3):
the output shows map symbols, cells, counts, numbers, hashes and Cobblemon's species names only.
"""
from __future__ import annotations

import argparse
import hashlib
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
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

import story_video as sv  # noqa: E402
from run_gen1recomp import launch  # noqa: E402
from story_video import Pilot, Player, Stuck, say  # noqa: E402

PORT = 27243
PILOT_DIR = REPO / "local" / "reports" / "fpstone"
PARTY = re.compile(r"KB_FP_PARTY (.*)")


def nick_hash(name: str) -> str:
    return hashlib.sha256(name.encode("utf-8")).hexdigest()[:8]


def parse_party(line: str) -> dict:
    """A KB_FP_PARTY line as {size, item, members: [{kind, dex, real, level, hp, uid, nick}]}."""
    out = {"members": []}
    for part in line.split():
        key, _, value = part.partition("=")
        if key == "size":
            out["size"] = int(value)
        elif key == "item":
            out["item"] = int(value.split(":")[1])
        elif key.startswith("m"):
            kind, dex, real, level, hp, uid, nick = value.split(":")
            out["members"].append({"kind": kind, "dex": dex, "real": real, "level": level, "hp": hp, "uid": uid, "nick": nick})
    return out


class Blue:
    """One Blue launch and the newest KB_FP_PARTY it printed."""

    def __init__(self, home: Path, scene: str, env: dict):
        self.run = launch("kb_fpstone.lua", game="blue", install=home, echo=False,
                          extra_env=dict(env, KB_SCENE=scene, KB_TEST_PORT=str(PORT), KB_BRIDGE_DIR=str(REPO / "gen1-bridge")))

    def lines(self):
        return list(self.run.result.lines)

    def party(self):
        for line in reversed(self.lines()):
            m = PARTY.search(line)
            if m:
                return parse_party(m.group(1))
        return None

    def wait_party(self, test, timeout=60.0, what="party"):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            p = self.party()
            if p is not None and test(p):
                return p
            time.sleep(0.3)
        raise Stuck(f"hidden party never showed {what}: {self.party()}")

    def has(self, marker):
        return any(marker in line for line in self.lines())

    def stop(self):
        self.run.kill()
        self.run.wait(timeout=30)


def client_command(world: str) -> list[str]:
    return ["./gradlew", "--no-daemon", "--console=plain", "runClient", "-Pkb.autopilot=remote", f"-Pkb.autopilotWorld={world}",
            f"-Pkb.autopilotPilotDir={PILOT_DIR}", "-Pkb.autopilotFollow=blue", "-Pkb.autopilotQuit=true",
            f"-Pkb.links=blue:{PORT}",
            # one player across both runs (a dev client otherwise logs in as a new random PlayerNNN each launch)
            "-Pkb.clientUser=FPTester"]


def start_client(world: str, pilot: Pilot):
    java_home = os.environ.get("KB_JAVA_HOME") or os.environ.get("JAVA_HOME") or "/opt/java/openjdk"
    client = subprocess.Popen(client_command(world), cwd=sv.GRADLE_DIR, env=dict(os.environ, JAVA_HOME=java_home),
                              stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    log = PILOT_DIR / f"client-{int(time.time())}.log"
    threading.Thread(target=sv.stream, args=(client, log), daemon=True).start()
    deadline = time.monotonic() + 900
    while pilot.state().get("phase") != "ready":
        if time.monotonic() > deadline or client.poll() is not None:
            raise Stuck("the client never reached the world")
        time.sleep(1.0)
    say("client in the world; following", pilot.state().get("followed"))
    return client


def stop_client(client, pilot: Pilot):
    try:
        pilot.send("quit")
    except Exception:  # noqa: BLE001
        pass
    try:
        client.wait(timeout=180)
    except subprocess.TimeoutExpired:
        client.kill()


def cobblemon_party(pilot: Pilot) -> list[dict]:
    r = pilot.do("party", timeout=30)
    if not r.get("ok"):
        raise Stuck(f"party read failed: {r.get('reason')}")
    return r.get("party") or []


def shot(pilot: Pilot, args, name: str):
    if args.shots:
        pilot.do("screenshot", file=f"fpstone-{name}.png", timeout=20)
        say("screenshot", name)


def choose(p: Player, want: int, kinds=("menu", "choice", "list"), timeout=30.0):
    """Moves the top hidden menu's cursor to row `want` and presses A."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        st = p.st()
        ui = st.get("ui")
        if ui and ui.get("kind") in kinds and not ui.get("answered"):
            index = ui.get("index", 0)
            if index == want:
                say("choose: row", want, "of", ui.get("count"), "in", ui.get("kind"), ui.get("screen"))
                p.press("a")
                return ui
            p.press("down" if index < want else "up")
            # the hidden game moves its cursor a few frames later: wait for the state to show it
            moved_by = time.monotonic() + 2.0
            while time.monotonic() < moved_by:
                now = (p.st().get("ui") or {}).get("index")
                if now != index:
                    break
                time.sleep(0.1)
        else:
            time.sleep(0.15)
    raise Stuck(f"no menu to choose row {want} in")


def open_start(p: Player) -> dict:
    p.settle(quiet=1.0, timeout=60)
    p.press("start")
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        ui = p.st().get("ui")
        if ui and ui.get("kind") == "menu":
            return ui
        time.sleep(0.15)
    raise Stuck("the start menu didn't open")


def start_row(p: Player, rows, opened, what):
    """START, then the first of `rows` whose screen `opened(state)` says is the right one (backing out of a wrong one):
    the rows' labels are the game's text, so they are found by what they open."""
    for row in rows:
        open_start(p)
        choose(p, row, kinds=("menu",))
        deadline = time.monotonic() + 6
        while time.monotonic() < deadline:
            st = p.st()
            if opened(st):
                say(what, "is row", row)
                return st
            time.sleep(0.2)
        close_all(p)
        p.settle(quiet=1.0, timeout=60, menu_index=None)
    raise Stuck(f"no start menu row opened {what}")


def continue_from_title(p: Player, timeout=300.0):
    """The title's first row, CONTINUE, then A on Gen 1's save summary (drawn over the menu, so the answered menu
    stays on top until the game moves on), until the game is in its overworld."""
    deadline = time.monotonic() + timeout
    answered_since = None
    while time.monotonic() < deadline:
        st = p.st()
        if st.get("ingame") and not st.get("ui") and not st.get("locked"):
            say("continued:", st.get("map"), st.get("x"), st.get("y"))
            return st
        ui = st.get("ui")
        if ui and ui.get("kind") == "menu" and ui.get("answered"):
            answered_since = answered_since or time.monotonic()
            if time.monotonic() - answered_since > 2.0:
                say("title: A on the save summary")
                p.press("a")
                answered_since = time.monotonic()
        else:
            answered_since = None
            if ui:
                p.answer(st, 0)
        time.sleep(0.2)
    raise Stuck("CONTINUE never reached the overworld")


def close_all(p: Player):
    for _ in range(12):
        st = p.st()
        if not st.get("ui") and not st.get("screen"):
            return
        p.press("b")
        time.sleep(0.4)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--world", default="FPKanto", help="the built Kanto region world in local/minecraft/saves (copied)")
    parser.add_argument("--shots", action="store_true", help="screenshots at each check (the user's, never opened here)")
    parser.add_argument("--timeout", type=int, default=3600)
    args = parser.parse_args(argv)

    source = sv.SAVES / args.world
    if not (source / "level.dat").is_file():
        print(f"no world {args.world}", file=sys.stderr)
        return 2
    world = args.world + "Stone"
    if (sv.SAVES / world).exists():
        shutil.rmtree(sv.SAVES / world)
    shutil.copytree(source, sv.SAVES / world)
    say("world copied:", world)

    home = sv.make_home("fpstone", "blue")
    env = {"KB_DATASET_MEMO": "30", "KB_PACE": "60", "KB_DRIVER_SECONDS": str(args.timeout + 600)}
    with launch("kb_union_off.lua", game="blue", install=home, echo=False, extra_env=env) as off:
        say("union off", off.wait(timeout=180).marker("KB_UNION_OFF"))

    results = {"checks": {}}

    def check(name, ok, detail=None):
        results["checks"][name] = {"ok": bool(ok), "detail": detail}
        say("CHECK", name, "PASS" if ok else "FAIL", "" if detail is None else json.dumps(detail))

    pilot = Pilot(PILOT_DIR)
    player = Player(pilot)
    blue = client = None
    code = 1
    try:
        # ---- run 1: the visitor, the stone, the evolution, the save
        say("== run 1")
        blue = Blue(home, "first", env)
        say("blue:", blue.run.wait_marker("KB_FP_READY", timeout=600))
        client = start_client(world, pilot)
        pilot.do("battle", mode="on")
        player.settle(quiet=2.0, timeout=120)
        for props in ("bulbasaur level=12", "skitty level=20"):
            r = pilot.do("give", properties=props, timeout=30)
            say("give", props, r.get("ok"), (r.get("pokemon") or {}).get("species"))
        before = cobblemon_party(pilot)
        say("cobblemon party:", [(m["species"], m["dex"], m["level"]) for m in before])
        hidden = blue.wait_party(lambda p: p.get("size") == 2 and len(p["members"]) == 2, what="two members")
        v = hidden["members"][1]
        check("visitor_in_blue", v["kind"] == "visitor" and v["real"] == "300" and v["nick"] == nick_hash("SKITTY")
              and hidden["members"][0]["kind"] == "native", hidden)
        shot(pilot, args, "1-visitor")

        say("== the Moon Stone ball")
        st = player.settle(quiet=1.5, timeout=60)
        facing = st.get("facing")
        pilot.do("face", dir=facing)
        player.press("a")
        player.settle(quiet=2.0, timeout=120)
        hidden = blue.wait_party(lambda p: p.get("item", 0) >= 1, what="the stone in the bag")
        check("stone_found", hidden["item"] >= 1, {"item": hidden["item"]})

        say("== START, ITEM, the stone, USE, the visitor")
        bag = start_row(player, (1, 2), lambda st: (st.get("ui") or {}).get("kind") == "list", "ITEM")
        say("bag:", (bag.get("ui") or {}).get("screen"), (bag.get("ui") or {}).get("count"))
        choose(player, 0, kinds=("list", "menu"))          # the bag: the stone is its only row
        time.sleep(0.8)
        choose(player, 0, kinds=("menu", "choice"))        # USE
        time.sleep(1.5)
        st = player.st()
        say("party picker:", (st.get("ui") or {}).get("kind"), st.get("screen"))
        player.press("down")                               # the second member, the visitor
        time.sleep(0.5)
        player.press("a")
        evolved = None
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            party = cobblemon_party(pilot)
            if len(party) == 2 and party[1]["dex"] == 301:
                evolved = party
                break
            time.sleep(1.0)
        check("evolved_in_cobblemon", evolved is not None and evolved[1]["species"] == "Delcatty"
              and evolved[1]["uid"] == before[1]["uid"], evolved or cobblemon_party(pilot))
        hidden = blue.wait_party(lambda p: len(p["members"]) == 2 and p["members"][1]["real"] == "301", what="Delcatty")
        v = hidden["members"][1]
        check("visitor_rewritten", v["kind"] == "visitor" and v["nick"] == nick_hash("DELCATTY") and hidden["item"] == 0, hidden)
        close_all(player)
        player.settle(quiet=2.0, timeout=120, menu_index=None)
        shot(pilot, args, "2-evolved")

        say("== START, SAVE, YES")
        start_row(player, (3, 4), lambda st: (st.get("ui") or {}).get("kind") in ("choice", "text")
                  and (st.get("ui") or {}).get("screen") != "StartMenu", "SAVE")
        player.settle(quiet=3.0, timeout=120, menu_index=0)  # YES, then the save's text
        deadline = time.monotonic() + 60
        while not blue.has("KB_EVENT save.writing") and time.monotonic() < deadline:
            time.sleep(0.5)
        check("hidden_saved", blue.has("KB_EVENT save.writing"))
        saved_hidden = blue.party()
        time.sleep(5.0)
        stop_client(client, pilot)
        client = None
        blue.stop()
        blue = None

        # ---- run 2: both again from their saves
        say("== run 2")
        pilot = Pilot(PILOT_DIR)
        player = Player(pilot)
        blue = Blue(home, "again", env)
        say("blue:", blue.run.wait_marker("KB_BOOTED", timeout=600))
        client = start_client(world, pilot)
        # the save loaded by the engine's own CONTINUE (the driver, once the world is bound: the title menu's pick
        # times out in gen1-bridge, an open issue), then the overworld
        say("blue:", blue.run.wait_marker("KB_CONTINUED", timeout=300))
        player.settle(quiet=3.0, timeout=300, menu_index=None,
                      until=lambda st: st.get("ingame") and not st.get("ui") and not st.get("locked"))
        loaded = blue.wait_party(lambda p: len(p["members"]) == 2, what="the loaded party")
        say("hidden after CONTINUE:", loaded)
        v = loaded["members"][1]
        check("hidden_after_continue", v["kind"] == "visitor" and v["real"] == "301" and v["nick"] == nick_hash("DELCATTY")
              and loaded["members"][0]["kind"] == "native"
              and [m["uid"] for m in loaded["members"]] == [m["uid"] for m in saved_hidden["members"]], loaded)
        party = cobblemon_party(pilot)
        check("cobblemon_after_reload", len(party) == 2 and party[0]["species"] == "Bulbasaur"
              and party[1]["species"] == "Delcatty" and party[1]["level"] == 20 and party[1]["uid"] == before[1]["uid"]
              and party[1]["form"] == "Normal", party)
        shot(pilot, args, "3-reloaded")
        code = 0 if all(c["ok"] for c in results["checks"].values()) else 1
    except Stuck as e:
        st = pilot.state()
        say("STUCK:", e)
        say("state:", json.dumps({k: st.get(k) for k in ("map", "x", "y", "facing", "mode", "ingame", "followed", "screen", "ui",
                                                         "locked", "battle", "result")}))
        results["stuck"] = str(e)
    finally:
        if client is not None:
            stop_client(client, pilot)
        if blue is not None:
            for line in blue.lines()[-40:]:
                print("  blue", line)
            blue.stop()
    (PILOT_DIR / "result.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    say("result:", "PASS" if code == 0 else "FAIL", json.dumps({k: v["ok"] for k, v in results["checks"].items()}))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
