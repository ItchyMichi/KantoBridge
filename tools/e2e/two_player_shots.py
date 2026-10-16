"""Two players in two world spaces at once (multiplayer): each player's client draws every region's own shapes and art,
whichever region the player stands in. Player 1 hosts the world (opened to LAN) and follows Blue in Pallet Town;
player 2 ("Player2") joins it over LAN and follows Crystal in New Bark Town; both screens are grabbed at the same moment.

    python tools/e2e/two_player_shots.py --world ThreeRegionsE2E --p2-repo /root/kantobridge-p2 \\
        [--out /workspace/videos/mp-shots] [--display1 :99] [--display2 :98]

1. A fresh data home per game (the test install's import and gen1-bridge linked by name), each game's Union Room
   switched off (kb_union_off.lua), then Blue with kb_g1spots.lua (a new game below Pallet Town's first door) and
   Crystal with kb_g2spots.lua (below New Bark Town's first door).
2. A copy of --world (Kanto, Johto and Hoenn built) for player 1 to host.
3. Player 1: this repository's dev client (game folder local/minecraft), Blue's link only, on --display1; once in the
   world it opens it to LAN. Player 2: the dev client as "Player2" with game folder local/minecraft-p2 (-Pkb.clientRunDir,
   -Pkb.clientUser; it needs its own export, pack and shape cache: prepared beforehand), run from --p2-repo, a copy of this repository (two Gradle
   runs of one project dir would wait on each other's locks), Crystal's link only, on --display2, joining over LAN.
4. Each follows its game into its town; the views settle; both cameras go behind the player with the HUD hidden; both
   displays are grabbed at once (ffmpeg x11grab, one thread each) to <out>/player1-kanto-pallet_town.png and
   <out>/player2-johto-new_bark_town.png.

The pictures show the user's own games: they stay on the pod's volume for the user and the agent never opens them.
Printed: map symbols, counts and each client's render lines (keys, shapes per region, art sets), never game text.
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

from region_shots import grab, render_lines  # noqa: E402
from run_gen1recomp import launch  # noqa: E402
from story_spots import stream, wait_followed  # noqa: E402
from story_video import Pilot, Stuck, make_home, say  # noqa: E402

SAVES = REPO / "local" / "minecraft" / "saves"
REPORTS = REPO / "local" / "reports"
LAN_PORT = 25599

# player: (game, driver, port, the town on the wire, region, the Gradle run)
PLAYERS = {
    1: ("blue", "kb_g1spots.lua", 27243, "PALLET_TOWN", "kanto", "runClient"),
    2: ("crystal", "kb_g2spots.lua", 27242, "G2_NEW_BARK_TOWN", "johto", "runClient"),
}


def client_command(n: int, world: str, pilot_dir: Path) -> list[str]:
    game, _, port, _, _, task = PLAYERS[n]
    args = ["./gradlew", "--no-daemon", "--console=plain", task, "-Pkb.autopilot=remote", f"-Pkb.autopilotWorld={world}",
            f"-Pkb.autopilotPilotDir={pilot_dir}", f"-Pkb.autopilotFollow={game}", "-Pkb.autopilotQuit=true",
            f"-Pkb.links={game}:{port}"]
    if n == 2:
        args += [f"-Pkb.autopilotServer=127.0.0.1:{LAN_PORT}", "-Pkb.clientRunDir=../local/minecraft-p2", "-Pkb.clientUser=Player2"]
    return args


def wait_ready(pilot: Pilot, client, what: str, timeout: float = 900.0) -> None:
    deadline = time.monotonic() + timeout
    while pilot.state().get("phase") != "ready":
        if time.monotonic() > deadline or client.poll() is not None:
            raise Stuck(f"{what} never reached the world")
        time.sleep(1.0)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--world", default="ThreeRegionsE2E", help="the built world in local/minecraft/saves (copied)")
    parser.add_argument("--p2-repo", required=True, help="a copy of this repository to run player 2's client from")
    parser.add_argument("--out", default="/workspace/videos/mp-shots", help="the folder the pictures go to")
    parser.add_argument("--display1", default=os.environ.get("DISPLAY", ":99"))
    parser.add_argument("--display2", default=":98")
    parser.add_argument("--settle", type=float, default=30.0, help="seconds the views settle in their towns")
    args = parser.parse_args(argv)
    p2_repo = Path(args.p2_repo)

    source = SAVES / args.world
    if not (source / "level.dat").is_file():
        print(f"no world {args.world}", file=sys.stderr)
        return 2
    world = args.world + "MP"
    if (SAVES / world).exists():
        shutil.rmtree(SAVES / world)
    shutil.copytree(source, SAVES / world)
    say("world copied:", world)

    game_env = {"KB_DATASET_MEMO": "30", "KB_PACE": "60", "KB_DRIVER_SECONDS": "3600"}
    runs, clients, pilots = {}, {}, {}
    results = {}
    code = 1
    java_home = os.environ.get("KB_JAVA_HOME") or os.environ.get("JAVA_HOME") or "/opt/java/openjdk"
    try:
        for n, (game, driver, port, _, _, _) in PLAYERS.items():
            home = make_home(f"mp_{game}", game)
            with launch("kb_union_off.lua", game=game, install=home, echo=False, extra_env=game_env) as off:
                say(f"{game} union off:", off.wait(timeout=180).marker("KB_UNION_OFF"))
            commands = REPORTS / f"mp_p{n}" / "game_commands.lua"
            commands.parent.mkdir(parents=True, exist_ok=True)
            commands.write_text("", encoding="utf-8")
            runs[n] = launch(driver, game=game, install=home, echo=False,
                             extra_env=dict(game_env, KB_TEST_PORT=str(port), KB_SPOT_COMMANDS=str(commands)))
        for n, run in runs.items():
            say(f"player {n}'s game:", run.wait_marker("KB_SERVING", timeout=300))
        # player 1 hosts: in the world first, then opened to LAN
        pilots[1] = Pilot(REPORTS / "mp_p1" / "pilot")
        clients[1] = subprocess.Popen(client_command(1, world, (REPORTS / "mp_p1" / "pilot").resolve()), cwd=REPO / "fabric-bridge",
                                      env=dict(os.environ, JAVA_HOME=java_home, DISPLAY=args.display1),
                                      stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        threading.Thread(target=stream, args=(clients[1], REPORTS / "mp_p1" / "client.log"), daemon=True).start()
        wait_ready(pilots[1], clients[1], "player 1")
        say("player 1 in the world; LAN:", pilots[1].do("lan", port=LAN_PORT, timeout=60).get("ok"))
        # player 2 joins from the second checkout (its pilot folder there, read here by its absolute path)
        p2_dir = (REPORTS / "mp_p2" / "pilot").resolve()
        pilots[2] = Pilot(p2_dir)
        clients[2] = subprocess.Popen(client_command(2, world, p2_dir), cwd=p2_repo / "fabric-bridge",
                                      env=dict(os.environ, JAVA_HOME=java_home, DISPLAY=args.display2),
                                      stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        threading.Thread(target=stream, args=(clients[2], REPORTS / "mp_p2" / "client.log"), daemon=True).start()
        wait_ready(pilots[2], clients[2], "player 2")
        say("player 2 joined")
        for n, (game, _, _, town, region, _) in PLAYERS.items():
            out = {"player": n, "game": game, "region": region, "town": town}
            out["follow"] = pilots[n].do("follow", game=game, timeout=60).get("ok")
            st = wait_followed(pilots[n], town, timeout=180)
            out["at"] = [st.get("map"), st.get("x"), st.get("y")]
            results[n] = out
        time.sleep(args.settle)  # chunks around both towns load and mesh on both clients
        for n in PLAYERS:
            pilots[n].do("camera", mode="third", hud=False, timeout=30)
        time.sleep(4.0)
        pictures = {}
        threads = []
        for n, (game, _, _, town, region, _) in PLAYERS.items():
            picture = Path(args.out) / f"player{n}-{region}-{town.lower().removeprefix('g2_')}.png"
            display = args.display1 if n == 1 else args.display2
            t = threading.Thread(target=lambda n=n, d=display, p=picture: pictures.__setitem__(n, (grab(d, p), time.time())))
            threads.append(t)
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        for n, (ok, at) in pictures.items():
            results[n]["picture"] = ok
            results[n]["grabbed_at"] = round(at, 2)
            st = pilots[n].state()
            results[n]["pilot_region"] = st.get("region")
            results[n]["now"] = [st.get("map"), st.get("x"), st.get("y")]
        for n in PLAYERS:
            pilots[n].do("camera", mode="first", hud=True, timeout=30)
            say(json.dumps(results[n], sort_keys=True))
        code = 0 if all(r.get("picture") for r in results.values()) else 1
    except Stuck as e:
        say("STUCK:", e)
    finally:
        for n in (2, 1):
            pilot = pilots.get(n)
            if pilot is not None:
                try:
                    pilot.do("stop", timeout=30)
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
            (REPORTS / f"mp_p{n}" / "game.log").write_text("\n".join(run.result.lines) + "\n", encoding="utf-8")
    for n in PLAYERS:
        for line in render_lines(REPORTS / f"mp_p{n}" / "client.log"):
            print(f"  player {n}", line)
    (REPORTS / "mp_shots.json").write_text(json.dumps(results, indent=1, sort_keys=True), encoding="utf-8")
    say(f"== {sum(1 for r in results.values() if r.get('picture'))} of {len(PLAYERS)} pictures")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
