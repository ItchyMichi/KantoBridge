"""A screenshot of every region's starting town in the real client, all three games linked at once: the shapes and
textures of every region drawn together (MG3), not only the followed one's.

    xvfb-run -a -s "-screen 0 1280x720x24" python tools/e2e/region_shots.py --world ThreeRegionsE2E \\
        [--out /workspace/videos/region-shots] [--only blue,crystal]

1. A fresh data home per game on the pod's container disk (the test install's import and gen1-bridge linked by name),
   so no game starts with a save of its own; each game's Union Room switched off first (kb_union_off.lua).
2. A copy of --world (Kanto, Johto and Hoenn built) to play in.
3. Each game with its story-spot driver, which starts a new game and stands below the starting town's first door
   (kb_g1spots.lua: Pallet Town; kb_g2spots.lua: New Bark Town; kb_g3spots.lua: Littleroot Town); the dev client
   (RemotePilot) with all three links.
4. Per game: the client follows it into its town, the view settles (chunks, shapes), the camera goes behind the
   player with the HUD hidden, and the X display is grabbed (ffmpeg x11grab) to <out>/<region>-<town>.png.

The pictures show the user's own games: they stay on the pod's volume for the user and the agent never opens them.
Printed: map symbols, counts and the client's render lines (keys, shapes per region, art sets), never game text.
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

from run_gen1recomp import launch  # noqa: E402
from story_spots import stream, wait_followed  # noqa: E402
from story_video import Pilot, Stuck, make_home, say  # noqa: E402

GRADLE_DIR = REPO / "fabric-bridge"
SAVES = REPO / "local" / "minecraft" / "saves"
PILOT_DIR = REPO / "local" / "reports" / "region_shots"

# game: (driver, port, the town on the wire, region, the Union Room switched off first: every install runs with it OFF, and
# gen1-bridge refuses the client's export while it is on)
GAMES = {
    "blue": ("kb_g1spots.lua", 27243, "PALLET_TOWN", "kanto", True),
    "crystal": ("kb_g2spots.lua", 27242, "G2_NEW_BARK_TOWN", "johto", True),
    "emerald": ("kb_g3spots.lua", 27241, "EM_LITTLEROOT_TOWN", "hoenn", True),
}


def client_command(world: str, games: list[str]) -> list[str]:
    links = ",".join(f"{g}:{GAMES[g][1]}" for g in games)
    return ["./gradlew", "--no-daemon", "--console=plain", "runClient", "-Pkb.autopilot=remote", f"-Pkb.autopilotWorld={world}",
            f"-Pkb.autopilotPilotDir={PILOT_DIR}", f"-Pkb.autopilotFollow={games[0]}", "-Pkb.autopilotQuit=true", f"-Pkb.links={links}"]


def grab(display: str, out: Path) -> bool:
    out.parent.mkdir(parents=True, exist_ok=True)
    done = subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "x11grab", "-video_size", "1280x720", "-i", display,
                           "-frames:v", "1", str(out)], stdin=subprocess.DEVNULL, capture_output=True, timeout=60)
    return done.returncode == 0 and out.is_file()


def render_lines(log: Path) -> list[str]:
    """The client's render and shape lines (counts, short hashes, art set and region ids only)."""
    keep = []
    try:
        for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
            for mark in ("render: ", "shapes: "):
                if mark in line:
                    keep.append(line[line.index(mark):][:300])
    except OSError:
        pass
    return keep


def drawn_with_art(text: str) -> bool:
    """A render snapshot in `text` with every region's keys drawn by a loaded art set (no placeholder line right before
    it): the reload with the new pack has landed."""
    last = None
    for line in text.splitlines():
        if "drawn as placeholders" in line:
            last = "placeholders"
        elif "render: snapshot" in line:
            if last != "placeholders":
                return True
            last = None
    return False


def wait_for_art(log: Path, timeout: float = 1200.0) -> None:
    """When the client found other regions' keys without art, the pack's rebuild (export, pack, reload) and the snapshot
    after it, before any picture."""
    deadline = time.monotonic() + 60.0
    rebuilding = False
    while time.monotonic() < deadline:
        text = log.read_text(encoding="utf-8", errors="replace") if log.is_file() else ""
        if "drawn as placeholders" in text or "exporting this player's art" in text:
            rebuilding = True
            break
        time.sleep(2.0)
    if not rebuilding:
        return
    say("the client rebuilds its pack with every game's art; waiting")
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        text = log.read_text(encoding="utf-8", errors="replace")
        done = text.rfind("reloading resources with the generated pack")
        if done >= 0 and drawn_with_art(text[done:]):
            say("pack rebuilt and reloaded")
            time.sleep(20.0)  # the shape tables rebuild with the new art
            return
        if "textures.export." in text or "textures.pack." in text:
            say("the pack rebuild failed (see the client log)")
            return
        time.sleep(3.0)
    say("the pack rebuild did not finish in time")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--world", default="ThreeRegionsE2E", help="the built world in local/minecraft/saves (copied)")
    parser.add_argument("--out", default="/workspace/videos/region-shots", help="the folder the pictures go to")
    parser.add_argument("--only", default="", help="these games (comma-separated; default all three)")
    parser.add_argument("--settle", type=float, default=25.0, help="seconds the view settles in each town")
    args = parser.parse_args(argv)
    games = [g for g in GAMES if not args.only or g in args.only.split(",")]
    display = os.environ.get("DISPLAY", ":99")

    source = SAVES / args.world
    if not (source / "level.dat").is_file():
        print(f"no world {args.world}", file=sys.stderr)
        return 2
    world = args.world + "Shots"
    if (SAVES / world).exists():
        shutil.rmtree(SAVES / world)
    shutil.copytree(source, SAVES / world)
    say("world copied:", world, "games:", games)

    game_env = {"KB_DATASET_MEMO": "30", "KB_PACE": "60", "KB_DRIVER_SECONDS": "3600"}
    PILOT_DIR.mkdir(parents=True, exist_ok=True)
    runs = {}
    client = None
    results = []
    code = 1
    try:
        for g in games:
            driver, port, _, _, union = GAMES[g]
            home = make_home(f"shots_{g}", g)
            if union:
                with launch("kb_union_off.lua", game=g, install=home, echo=False, extra_env=game_env) as off:
                    say(f"{g} union off:", off.wait(timeout=180).marker("KB_UNION_OFF"))
            commands = PILOT_DIR / f"{g}_commands.lua"
            commands.write_text("", encoding="utf-8")
            runs[g] = launch(driver, game=g, install=home, echo=False,
                             extra_env=dict(game_env, KB_TEST_PORT=str(port), KB_SPOT_COMMANDS=str(commands)))
        for g in games:
            say(f"{g}:", runs[g].wait_marker("KB_SERVING", timeout=300))
        pilot = Pilot(PILOT_DIR)
        java_home = os.environ.get("KB_JAVA_HOME") or os.environ.get("JAVA_HOME") or "/opt/java/openjdk"
        client = subprocess.Popen(client_command(world, games), cwd=GRADLE_DIR, env=dict(os.environ, JAVA_HOME=java_home),
                                  stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        threading.Thread(target=stream, args=(client, PILOT_DIR / "client.log"), daemon=True).start()
        deadline = time.monotonic() + 900
        while pilot.state().get("phase") != "ready":
            if time.monotonic() > deadline or client.poll() is not None:
                raise Stuck("the client never reached the world")
            time.sleep(1.0)
        wait_for_art(PILOT_DIR / "client.log")
        for g in games:
            _, _, town, region, _ = GAMES[g]
            out = {"game": g, "region": region, "town": town}
            try:
                out["follow"] = pilot.do("follow", game=g, timeout=60).get("ok")
                st = wait_followed(pilot, town, timeout=180)
                out["at"] = [st.get("map"), st.get("x"), st.get("y")]
                time.sleep(args.settle)  # chunks around the town load and mesh; the shapes and the pack settle
                pilot.do("camera", mode="third", hud=False, timeout=30)
                time.sleep(4.0)
                picture = Path(args.out) / f"{region}-{town.lower()}.png"
                out["picture"] = str(picture) if grab(display, picture) else None
                pilot.do("camera", mode="first", hud=True, timeout=30)
                st = pilot.state()
                out["pilot_region"] = st.get("region")
            except Stuck as e:
                out["error"] = str(e)
            results.append(out)
            say(json.dumps(out, sort_keys=True))
        code = 0 if all(r.get("picture") for r in results) else 1
    except Stuck as e:
        say("STUCK:", e)
    finally:
        try:
            pilot.do("stop", timeout=30)
            pilot.send("quit")
        except (Stuck, NameError):
            pass
        if client is not None:
            try:
                client.wait(timeout=120)
            except subprocess.TimeoutExpired:
                client.kill()
        for g, run in runs.items():
            run.kill()
            run.wait(timeout=30)
            (PILOT_DIR / f"{g}.log").write_text("\n".join(run.result.lines) + "\n", encoding="utf-8")
    for line in render_lines(PILOT_DIR / "client.log"):
        print("  client", line)
    (PILOT_DIR / "results.json").write_text(json.dumps(results, indent=1, sort_keys=True), encoding="utf-8")
    say(f"== {sum(1 for r in results if r.get('picture'))} of {len(results)} pictures")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
