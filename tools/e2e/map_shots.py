"""Hidden-game screenshots for the fitted-shapes survey (docs/specs/FITTED_SHAPES.md section 14): the game alone (no
client) with its story-spot driver, the player warped onto a list of map cells, the screen grabbed at each.

    python tools/e2e/map_shots.py --game emerald --spots spots.json --out /workspace/videos/mapshots [--settle 1.5]

--spots: [{"map": "EM_SLATEPORT_CITY", "x": 10, "y": 44, "name": "..."}, ...] (map ids as the survey has them; Crystal's
G2_ prefix is dropped for its driver). DISPLAY is the game's screen (an Xvfb). Each shot is <out>/<game>/<name>.png, the
game's window cropped from the display; results_<shard>.json lists each spot's outcome with the cell the driver reports.

The pictures show the user's own game: they stay on the pod's volume and the user's tailnet viewer. Printed: map symbols,
cells and counts only.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

from context_shots import GAMES, go_literal, grab  # noqa: E402
from run_gen1recomp import launch  # noqa: E402
from story_spots import Stuck, wait_spot  # noqa: E402
from story_video import make_home, say  # noqa: E402

REPO = HERE.parents[1]
PILOT_DIR = REPO / "local" / "reports" / "map_shots"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--game", required=True, choices=sorted(GAMES))
    parser.add_argument("--spots", required=True)
    parser.add_argument("--out", default="/workspace/videos/mapshots")
    parser.add_argument("--settle", type=float, default=2.5, help="seconds after the driver reports the spot")
    parser.add_argument("--settle-first", type=float, default=3.5, help="on a map's first spot (its name box fades)")
    parser.add_argument("--shard", default="0/1", help="i/n: this run's share of the spots, by whole maps")
    parser.add_argument("--port", type=int, default=0, help="the driver's port (default: the game's)")
    args = parser.parse_args(argv)
    driver, port, _wire, drop = GAMES[args.game]
    display = os.environ.get("DISPLAY", ":97")
    spots = json.loads(Path(args.spots).read_text(encoding="utf-8"))
    shard, shards = (int(v) for v in args.shard.split("/"))
    maps = sorted({s["map"] for s in spots})
    mine = set(maps[shard::shards])
    spots = [s for s in spots if s["map"] in mine]
    port = args.port or port
    out_dir = Path(args.out) / args.game
    out_dir.mkdir(parents=True, exist_ok=True)
    PILOT_DIR.mkdir(parents=True, exist_ok=True)
    game_env = {"KB_DATASET_MEMO": "30", "KB_PACE": "60", "KB_DRIVER_SECONDS": "36000", "DISPLAY": display}
    home = make_home(f"mapshots_{args.game}_{shard}", args.game)
    with launch("kb_union_off.lua", game=args.game, install=home, echo=False, extra_env=game_env) as off:
        say("union off:", off.wait(timeout=180).marker("KB_UNION_OFF"))
    commands = PILOT_DIR / f"{args.game}_{shard}_commands.lua"
    commands.write_text("", encoding="utf-8")
    run = launch(driver, game=args.game, install=home, echo=False,
                 extra_env=dict(game_env, KB_TEST_PORT=str(port), KB_SPOT_COMMANDS=str(commands)))
    results, code, last_map = [], 1, None
    try:
        say(run.wait_marker("KB_SERVING", timeout=300))
        for n, spot in enumerate(spots, 1):
            map_id = spot["map"]
            driver_map = map_id[len(drop):] if drop and map_id.startswith(drop) else map_id
            out = {"name": spot["name"], "map": map_id, "at": [spot["x"], spot["y"]]}
            try:
                with open(commands, "a", encoding="utf-8") as f:
                    f.write(go_literal(n, driver_map, spot["x"], spot["y"], "down")[:-2] + ", nohop = true }\n")
                game = wait_spot(run, n, timeout=60)
                out["marker"] = game["marker"]
                out["game"] = [game.get("map"), game.get("x"), game.get("y")]
                if game["marker"] == "KB_SPOT":
                    time.sleep(args.settle_first if map_id != last_map else args.settle)
                    last_map = map_id
                    out["ok"] = grab(display, out_dir / f"{spot['name']}.png", crop=True)
            except Stuck as e:
                out["stuck"] = str(e)
            results.append(out)
            if n % 25 == 0 or n == len(spots):
                say(f"{n}/{len(spots)}", sum(1 for r in results if r.get("ok")), "ok")
        code = 0
    finally:
        run.kill()
        run.wait(timeout=30)
        (PILOT_DIR / f"{args.game}_{shard}.log").write_text("\n".join(run.result.lines) + "\n", encoding="utf-8")
        (out_dir / f"results_{shard}.json").write_text(json.dumps(results, indent=1), encoding="utf-8")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
