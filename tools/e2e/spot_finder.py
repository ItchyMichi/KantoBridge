"""Where a test pass's end can go in a town (docs/specs/HOENN_PASS_PLAN.md K1's rules; MG9 G2H, the Kanto <-> Johto
test pass): runs gen1-bridge's own exit planner over a town of the real game and prints the spots, nearest the corner
asked for first (drivers/kb_wildspot.lua on a Gen 1 game, drivers/kb_g2spot.lua on Crystal).

    python tools/e2e/spot_finder.py --game crystal --map NEW_BARK_TOWN --corner sw
    python tools/e2e/spot_finder.py --game blue --map VIRIDIAN_CITY --corner sw

Cells, directions and counts only (the drivers print nothing else).
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

from kb_protocol.games import load_games  # noqa: E402
from run_gen1recomp import run  # noqa: E402


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--game", default="crystal")
    parser.add_argument("--map", required=True, help="the engine's own map id (no G2_ prefix)")
    parser.add_argument("--corner", default="se", choices=["se", "sw", "ne", "nw"])
    parser.add_argument("--facings", default="up,down,left,right")
    parser.add_argument("--clear", type=int, default=6, help="cells from any warp, object or sign")
    parser.add_argument("--depth", type=int, default=2)
    parser.add_argument("--show", type=int, default=8)
    args = parser.parse_args(argv)
    gen2 = load_games().family(args.game) == "gen2"
    env = {"KB_SPOT_MAP": args.map, "KB_SPOT_CORNER": args.corner, "KB_SPOT_FACINGS": args.facings,
           "KB_SPOT_CLEAR": str(args.clear), "KB_SPOT_DEPTH": str(args.depth), "KB_SPOT_SHOW": str(args.show),
           "KB_DATASET_MEMO": "30"}
    result = run("kb_g2spot.lua" if gen2 else "kb_wildspot.lua", game=args.game, timeout=600, extra_env=env, echo=False)
    for line in result.lines:
        if line.startswith(("KB_SPOT", "KB_FAIL")):
            print(line)
    return 0 if result.marker("KB_SPOTS") else 1


if __name__ == "__main__":
    raise SystemExit(main())
