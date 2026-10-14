"""Writes the synthetic export as an export bundle (docs/PROTOCOL.md "Hashes and the bundle").

    python -m fake_gen1 --write-bundle <dir> [--game red]

gives <dir>/<game>/index.json and <dir>/<game>/<kind>/<id>.json. GameTests and builder tests use it
in place of a real export, so it holds only the made-up world. MG7: a Gen 3 game (emerald) gets the
synthetic Gen 3 export (synth3: the g3* kinds).
"""
from pathlib import Path

from kb_protocol.jsonfmt import pretty

from kb_protocol.games import load_games

from . import synth, synth3
from .server import BRIDGE_VERSION, ENGINE_VERSION


def write_bundle(directory, game="red"):
    root = Path(directory) / game
    gen3 = load_games().family(game) == "gen3"
    resources = synth3.resources(game) if gen3 else synth.resources(game)
    index = synth.index(game, ENGINE_VERSION, BRIDGE_VERSION, resources)
    for (kind, rid), data in resources.items():
        path = root / kind / f"{rid}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(synth.encode(data))
    (root / "index.json").write_text(pretty(index), encoding="utf-8", newline="\n")
    return root
