"""Which games a Gen1Recomp install holds (docs/specs/MULTI_GAME_PLAN.md §4.11, MG4).

    python -m kb_games [--install DIR]... [--launch-json PATH] [--json]      (from tools/)

Gen1Recomp keeps each imported game in a folder of its install named by the game id (``blue/``,
``emerald/``, ...) with a completion marker, ``rom-cache.complete``, inside (the engine's
src/import/CacheContract.lua). This module reads **folder names and the marker's presence only**,
never a file's contents: the folders are ROM-derived (CLAUDE.md). The ids it knows are the game
catalogue's (protocol/games.json).

Used by the launcher (``tools/launch.ps1`` has the same rule in PowerShell), the setup tool
(``tools/setup_games.*``) and the e2e runner (``tools/e2e/run_gen1recomp.py``'s pre-flight).
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

try:
    from kb_protocol import load_games
except ImportError:  # run as a script from another directory
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from kb_protocol import load_games

MARKER = "rom-cache.complete"
TOOLS = Path(__file__).resolve().parent
REPO = TOOLS.parent
DEFAULT_LAUNCH_JSON = REPO / "local" / "launch.json"
# The world space of a game is its default region; its install is local/gen1recomp-<space>/ (D3),
# except the first, today's local/gen1recomp/.
LEGACY_SPACE = "kanto"


def detect(install: Path | str) -> list[str]:
    """The catalogued games imported in ``install``: its sub-folders named like a game id that hold
    the completion marker (names and the marker's presence only). Sorted; empty when the folder
    doesn't exist."""
    install = Path(install)
    if not install.is_dir():
        return []
    names = set(os.listdir(install))
    out = []
    for game in load_games().ids:
        if game in names and (install / game).is_dir() and (install / game / MARKER).is_file():
            out.append(game)
    return out


def install_dir(space: str, root: Path = REPO / "local") -> Path:
    """Where the install of a world space lives: ``local/gen1recomp/`` for kanto (today's install),
    ``local/gen1recomp-<space>/`` otherwise."""
    return root / ("gen1recomp" if space == LEGACY_SPACE else f"gen1recomp-{space}")


def space_of(game: str) -> str | None:
    """The world space (default region) of a catalogued game, or None."""
    return load_games().region(game)


def read_launch(path: Path | str, repo: Path = REPO) -> dict:
    """``launch.json`` as a version-2 document: ``{"installs": {<space>: {"dir": Path, "games": [...],
    "play": <game> | None}}, "ports": {"first": 27151, "player2": 10}, ...other keys...}``.

    Version 1 (``game``, ``gen1recomp``, ``gen1recompP2``, ``port``) is read as the ``kanto`` install (and
    its player-2 install as ``kanto_p2``). A missing file gives the defaults: ``local/gen1recomp`` as kanto.
    Relative ``dir`` values resolve against the repository. Never reads anything inside an install."""
    path = Path(path)
    doc = {}
    if path.is_file():
        with open(path, encoding="utf-8") as fh:
            doc = json.load(fh)
        if not isinstance(doc, dict):
            raise ValueError("launch.json must hold an object")
    out = {"installs": {}, "ports": {"first": 27151, "player2": 10}}
    for key, value in doc.items():
        if key not in ("installs", "ports", "game", "gen1recomp", "gen1recompP2", "port"):
            out[key] = value
    ports = doc.get("ports")
    if isinstance(ports, dict):
        if isinstance(ports.get("first"), int):
            out["ports"]["first"] = ports["first"]
        if isinstance(ports.get("player2"), int):
            out["ports"]["player2"] = ports["player2"]
    elif isinstance(doc.get("port"), int):
        out["ports"]["first"] = doc["port"]
    installs = doc.get("installs")
    if isinstance(installs, dict) and installs:
        for space, entry in installs.items():
            if not isinstance(entry, dict):
                continue
            folder = entry.get("dir") or str(install_dir(space, repo / "local"))
            games = entry.get("games") if isinstance(entry.get("games"), list) else None
            out["installs"][space] = {
                "dir": (repo / folder) if not Path(folder).is_absolute() else Path(folder),
                "games": [g for g in games if isinstance(g, str)] if games is not None else None,
                "play": entry.get("play") if isinstance(entry.get("play"), str) else None,
            }
    else:
        first = doc.get("gen1recomp") or str(install_dir(LEGACY_SPACE, repo / "local"))
        out["installs"][LEGACY_SPACE] = {
            "dir": (repo / first) if not Path(first).is_absolute() else Path(first),
            "games": None,
            "play": doc.get("game") if isinstance(doc.get("game"), str) else None,
        }
        if isinstance(doc.get("gen1recompP2"), str):
            p2 = doc["gen1recompP2"]
            out["installs"][LEGACY_SPACE + "_p2"] = {
                "dir": (repo / p2) if not Path(p2).is_absolute() else Path(p2), "games": None, "play": None,
            }
    return out


def installs(launch_json: Path | str = DEFAULT_LAUNCH_JSON, repo: Path = REPO) -> dict[str, list[str]]:
    """``{install dir: [games]}`` for every install ``launch.json`` names (or the default install),
    by detection (folder names and markers)."""
    out = {}
    for entry in read_launch(launch_json, repo)["installs"].values():
        out[str(entry["dir"])] = detect(entry["dir"])
    return out


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="python -m kb_games", description=__doc__.splitlines()[0])
    parser.add_argument("--install", action="append", default=[], metavar="DIR",
                        help="an install folder to inspect (repeatable); default: the installs of --launch-json")
    parser.add_argument("--launch-json", default=str(DEFAULT_LAUNCH_JSON), help="launch.json (default: local/launch.json)")
    parser.add_argument("--json", action="store_true", help="print {install: [games]} as JSON")
    args = parser.parse_args(argv)
    if args.install:
        found = {str(Path(d)): detect(d) for d in args.install}
    else:
        found = installs(args.launch_json)
    if args.json:
        print(json.dumps(found, indent=2, sort_keys=True))
    else:
        for folder, games in found.items():
            print(f"{folder}: {' '.join(games) if games else '(no completed import)'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
