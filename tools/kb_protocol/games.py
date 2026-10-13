"""The game catalogue, protocol/games.json (docs/specs/MULTI_GAME_PLAN.md §4.2).

The one place that names the games: id -> family, engine, generation, label, default region,
compatibility profile, art set, whether gen1-bridge has an adapter, and the story markers. Java
bundles the file in the jar (``Games``), gen1-bridge carries the generated ``lib/kb_games.lua``
(``catalog.py --write``), and the stand-ins and tests read it through here.

Also renders the Lua copy: ``render_lua(catalogue)``.
"""
import json
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from .paths import PROTOCOL_DIR

FILE_NAME = "games.json"
FAMILIES = ("gen1", "gen2", "gen3")
LUA_HEADER = (
    "-- kb_games: the game catalogue (protocol/games.json, docs/specs/MULTI_GAME_PLAN.md\n"
    "-- §4.2): each Gen1Recomp game id's family, engine, generation, label, default\n"
    "-- region, compatibility profile, art set, whether this mod has an adapter for it,\n"
    "-- and the story markers game.status reports.\n"
    "--\n"
    "-- GENERATED from protocol/games.json by tools/kb_protocol/catalog.py (--write);\n"
    "-- never edit by hand. A test fails when it is stale.\n"
    "\n"
)


@dataclass(frozen=True)
class Game:
    id: str
    family: str
    engine: str
    generation: int
    label: str
    region: str
    compat: dict
    art_set: str
    adapter: bool
    markers: dict
    layout: str | None = None


class Games:
    """The parsed catalogue (not validated here: ``Protocol.check_games`` does that in the tests)."""

    def __init__(self, data):
        self.version = data["version"]
        self.games = {}
        for game_id, g in data["games"].items():
            self.games[game_id] = Game(game_id, g["family"], g["engine"], int(g["generation"]), g["label"], g["region"],
                                       dict(g["compat"]), g["artSet"], bool(g["adapter"]), dict(g["markers"]),
                                       g.get("layout"))

    @property
    def ids(self):
        return sorted(self.games)

    def __contains__(self, game_id):
        return game_id in self.games

    def get(self, game_id):
        return self.games.get(game_id)

    def family(self, game_id):
        g = self.games.get(game_id)
        return g.family if g else None

    def region(self, game_id):
        g = self.games.get(game_id)
        return g.region if g else None

    def compat(self, game_id):
        g = self.games.get(game_id)
        return dict(g.compat) if g else None

    def adapter(self, game_id):
        g = self.games.get(game_id)
        return g.adapter if g else False

    def with_adapter(self):
        return [i for i in self.ids if self.games[i].adapter]

    def compatible(self, art, world):
        """True when art of one game's export can draw a world built from another's: the same game or family."""
        if art is None or world is None:
            return False
        return art == world or (self.family(art) is not None and self.family(art) == self.family(world))


def read_games(root=PROTOCOL_DIR):
    with open(Path(root) / FILE_NAME, encoding="utf-8") as fh:
        return json.load(fh)


@lru_cache(maxsize=None)
def load_games(root=None):
    return Games(read_games(PROTOCOL_DIR if root is None else root))


# --- the Lua copy ----------------------------------------------------------------------------------

_LUA_IDENT = r"^[A-Za-z_][A-Za-z0-9_]*$"
_LUA_KEYWORDS = {"and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in",
                 "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while"}


def _lua_string(text):
    out = text.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\r", "\\r")
    return '"' + out + '"'


def _lua_key(key):
    import re
    if re.match(_LUA_IDENT, key) and key not in _LUA_KEYWORDS:
        return key
    return "[" + _lua_string(key) + "]"


def _lua_value(value, indent):
    if value is True:
        return "true"
    if value is False:
        return "false"
    if value is None:
        return "nil"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        raise ValueError("the catalogue carries no fractional numbers")
    if isinstance(value, str):
        return _lua_string(value)
    pad = "  " * (indent + 1)
    if isinstance(value, list):
        if not value:
            return "{}"
        return "{\n" + "".join(pad + _lua_value(v, indent + 1) + ",\n" for v in value) + "  " * indent + "}"
    if isinstance(value, dict):
        if not value:
            return "{}"
        return ("{\n" + "".join(pad + _lua_key(k) + " = " + _lua_value(value[k], indent + 1) + ",\n" for k in sorted(value))
                + "  " * indent + "}")
    raise TypeError(type(value).__name__)


def render_lua(data):
    """gen1-bridge/lib/kb_games.lua's text for a catalogue document (sorted keys, deterministic)."""
    return LUA_HEADER + "return " + _lua_value(data, 0) + "\n"
