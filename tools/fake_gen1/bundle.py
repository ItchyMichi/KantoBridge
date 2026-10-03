"""Writes the synthetic export as an export bundle (docs/PROTOCOL.md "Hashes and the bundle").

    python -m fake_gen1 --write-bundle <dir> [--game red]

gives <dir>/<game>/index.json and <dir>/<game>/<kind>/<id>.json. GameTests and builder tests use it
in place of a real export, so it holds only the made-up world.
"""
from pathlib import Path

from kb_protocol.jsonfmt import pretty

from . import synth
from .server import BRIDGE_VERSION, ENGINE_VERSION


def write_bundle(directory, game="red"):
    root = Path(directory) / game
    resources = synth.resources(game)
    index = synth.index(game, ENGINE_VERSION, BRIDGE_VERSION, resources)
    for (kind, rid), data in resources.items():
        path = root / kind / f"{rid}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(synth.encode(data))
    (root / "index.json").write_text(pretty(index), encoding="utf-8", newline="\n")
    return root
