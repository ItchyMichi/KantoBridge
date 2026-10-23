"""In-scene review pictures for fitted shapes (docs/specs/FITTED_SHAPES.md section 12): for each cell, the hidden game's
screen and the real client's view at the same spot, so a model is judged in its scene and not in isolation.

    python tools/e2e/context_shots.py --game crystal --world FittedE2E --cells cells.txt --locations locate_crystal.json \\
        [--out /workspace/videos/context] [--game-display :97] [--settle 6]

DISPLAY is Minecraft's screen (Xvfb :99 at 1280x720); --game-display is Gen1Recomp's own (another Xvfb), so the two
never cover each other.

1. A fresh data home for the game (story_video.make_home), its Union Room switched off first (kb_union_off.lua), a copy of
   --world to play in.
2. The game with its story-spot driver (kb_g1spots / kb_g2spots / kb_g3spots: a new game, then the go commands this
   script writes) on --game-display; the dev client (RemotePilot) linked to it alone, following it.
3. Per cell (one line of --cells, a key of --locations from FitTool locate): a go onto the stand cell beside the unit,
   facing it; once the client has followed and the view settled, the camera goes behind the player with the HUD
   hidden, and both displays are grabbed: <out>/<game>/<cell>_mc.png (the client) and <cell>_game.png (the hidden game,
   cropped to its window). results.json lists each cell's outcome.

The pictures show the user's own games: they stay on the pod's volume and the user's tailnet viewer. Printed: map
symbols, cells and counts only.
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

from region_shots import wait_for_art  # noqa: E402
from run_gen1recomp import launch  # noqa: E402
from story_spots import stream, wait_followed, wait_spot  # noqa: E402
from story_video import Pilot, Stuck, make_home, say  # noqa: E402

REPO = HERE.parents[1]
GRADLE_DIR = REPO / "fabric-bridge"
SAVES = REPO / "local" / "minecraft" / "saves"
PILOT_DIR = REPO / "local" / "reports" / "context_shots"

# game: (driver, port, wire prefix the client reports, prefix the driver's map names drop)
GAMES = {
    "blue": ("kb_g1spots.lua", 27243, "", ""),
    "crystal": ("kb_g2spots.lua", 27242, "G2_", "G2_"),
    "emerald": ("kb_g3spots.lua", 27241, "", ""),
}


def grab(display: str, out: Path, crop: bool) -> bool:
    """The whole display as a PNG; with crop, cut to the non-black box (the game's window on a black root)."""
    out.parent.mkdir(parents=True, exist_ok=True)
    done = subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "x11grab", "-video_size", "1280x720", "-i", display,
                           "-frames:v", "1", str(out)], stdin=subprocess.DEVNULL, capture_output=True, timeout=60)
    if done.returncode != 0 or not out.is_file():
        return False
    if crop:
        try:
            from PIL import Image
            img = Image.open(out).convert("RGB")
            box = img.point(lambda v: 255 if v > 8 else 0).getbbox()
            if box:
                img.crop(box).save(out)
        except Exception:  # noqa: BLE001 - an uncropped picture is still useful
            pass
    return True


def go_literal(n: int, map_id: str, x: int, y: int, facing: str) -> str:
    return f'{{ id = {n}, op = "go", map = "{map_id}", x = {x}, y = {y}, facing = "{facing}" }}'


REGIONS = {"blue": "kanto", "crystal": "johto", "emerald": "hoenn"}


def wait_for_shapes(log: Path, region: str, timeout: float = 900.0) -> None:
    """Until the client's shape table for the game's region is ready: built fresh, Hoenn's takes minutes, and a picture
    taken before it shows every model as tile cubes."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        text = log.read_text(encoding="utf-8", errors="replace") if log.is_file() else ""
        if f"shapes: table for region {region} " in text:
            say(f"the {region} shape table is ready")
            return
        time.sleep(3.0)
    say(f"the {region} shape table was not ready within {timeout:.0f} s; shooting anyway")


def client_env(java_home):
    """The client's environment: KB_CLIENT_HEAP (e.g. 4g) caps its heap, as a player's launcher would."""
    env = dict(os.environ, JAVA_HOME=java_home)
    if os.environ.get("KB_CLIENT_HEAP"):
        env["JAVA_TOOL_OPTIONS"] = (env.get("JAVA_TOOL_OPTIONS", "") + " -Xmx" + os.environ["KB_CLIENT_HEAP"]).strip()
    return env


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--game", required=True, choices=sorted(GAMES))
    parser.add_argument("--world", default="FittedE2E")
    parser.add_argument("--cells", required=True, help="cell keys, one a line")
    parser.add_argument("--locations", required=True, help="FitTool locate's JSON for this game")
    parser.add_argument("--out", default="/workspace/videos/context")
    parser.add_argument("--game-display", default=":97")
    parser.add_argument("--settle", type=float, default=6.0, help="seconds the view settles at each spot")
    parser.add_argument("--pitch", type=float, default=None, help="the camera's pitch at each spot (negative looks up)")
    args = parser.parse_args(argv)
    driver, port, wire, drop = GAMES[args.game]
    display = os.environ.get("DISPLAY", ":99")
    cells = [c.strip() for c in Path(args.cells).read_text(encoding="utf-8").splitlines() if c.strip()]
    places = json.loads(Path(args.locations).read_text(encoding="utf-8"))
    out_dir = Path(args.out) / args.game

    world = args.world + "Context"
    if (SAVES / world).exists():
        shutil.rmtree(SAVES / world)
    shutil.copytree(SAVES / args.world, SAVES / world)
    PILOT_DIR.mkdir(parents=True, exist_ok=True)
    game_env = {"KB_DATASET_MEMO": "30", "KB_PACE": "60", "KB_DRIVER_SECONDS": "14400", "DISPLAY": args.game_display}
    home = make_home(f"context_{args.game}", args.game)
    with launch("kb_union_off.lua", game=args.game, install=home, echo=False, extra_env=game_env) as off:
        say("union off:", off.wait(timeout=180).marker("KB_UNION_OFF"))
    commands = PILOT_DIR / f"{args.game}_commands.lua"
    commands.write_text("", encoding="utf-8")
    run = launch(driver, game=args.game, install=home, echo=False,
                 extra_env=dict(game_env, KB_TEST_PORT=str(port), KB_SPOT_COMMANDS=str(commands)))
    results, client, code = [], None, 1
    try:
        say(run.wait_marker("KB_SERVING", timeout=300))
        pilot = Pilot(PILOT_DIR)
        java_home = os.environ.get("KB_JAVA_HOME") or os.environ.get("JAVA_HOME") or "/opt/java/openjdk"
        client = subprocess.Popen(["./gradlew", "--no-daemon", "--console=plain", "runClient", "-Pkb.autopilot=remote",
                                   f"-Pkb.autopilotWorld={world}", f"-Pkb.autopilotPilotDir={PILOT_DIR}",
                                   f"-Pkb.autopilotFollow={args.game}", "-Pkb.autopilotQuit=true",
                                   f"-Pkb.links={args.game}:{port}"],
                                  cwd=GRADLE_DIR, env=client_env(java_home),
                                  stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        threading.Thread(target=stream, args=(client, PILOT_DIR / "client.log"), daemon=True).start()
        deadline = time.monotonic() + 900
        while pilot.state().get("phase") != "ready":
            if time.monotonic() > deadline or client.poll() is not None:
                raise Stuck("the client never reached the world")
            time.sleep(1.0)
        wait_for_art(PILOT_DIR / "client.log")
        pilot.do("follow", game=args.game, timeout=60)
        wait_for_shapes(PILOT_DIR / "client.log", REGIONS[args.game])
        for n, cell in enumerate(cells, 1):
            place = places.get(cell)
            out = {"cell": cell}
            if not place or "x" not in place:
                out["result"] = "no place"
                results.append(out)
                continue
            map_id = place["map"]
            driver_map = map_id[len(drop):] if drop and map_id.startswith(drop) else map_id
            out.update(map=map_id, at=[place["x"], place["y"]], unit=[place["ux"], place["uy"]])
            try:
                with open(commands, "a", encoding="utf-8") as f:
                    f.write(go_literal(n, driver_map, place["x"], place["y"], place["facing"]) + "\n")
                game = wait_spot(run, n, timeout=90)
                if game["marker"] != "KB_SPOT":
                    out["result"] = "the game refused the spot"
                    results.append(out)
                    continue
                wire_map = map_id if map_id.startswith(wire) else wire + map_id
                wait_followed(pilot, wire_map, (place["x"], place["y"]), timeout=90)
                # One cell further back where the way is free, then face the unit: a first-person view with the whole unit in
                # frame (third person puts the player's own figure in front of it).
                back = {"up": "down", "down": "up", "left": "right", "right": "left"}[place["facing"]]
                try:
                    out["stepped_back"] = bool(pilot.do("step", dir=back, timeout=20).get("ok"))
                except Stuck:
                    out["stepped_back"] = False
                time.sleep(args.settle)
                if args.pitch is None:
                    pilot.do("face", dir=place["facing"], timeout=10)
                else:
                    pilot.do("face", dir=place["facing"], pitch=args.pitch, timeout=10)
                pilot.do("camera", mode="first", hud=False, timeout=30)
                time.sleep(2.5)
                stem = cell.replace("/", "_")
                out["mc"] = grab(display, out_dir / f"{stem}_mc.png", crop=False)
                out["game"] = grab(args.game_display, out_dir / f"{stem}_game.png", crop=True)
                pilot.do("camera", mode="first", hud=True, timeout=30)
                out["result"] = "ok" if out["mc"] and out["game"] else "grab failed"
            except Stuck as e:
                out["result"] = f"stuck: {e}"
            results.append(out)
            say(f"{n}/{len(cells)}", json.dumps(out, sort_keys=True))
        code = 0
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
        run.kill()
        run.wait(timeout=30)
        (PILOT_DIR / f"{args.game}.log").write_text("\n".join(run.result.lines) + "\n", encoding="utf-8")
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "results.json").write_text(json.dumps(results, indent=1), encoding="utf-8")
    say(f"== {sum(1 for r in results if r.get('result') == 'ok')} of {len(results)} cells")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
