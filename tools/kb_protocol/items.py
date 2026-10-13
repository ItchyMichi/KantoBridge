"""The canonical item catalogue, protocol/items.json (docs/specs/MULTI_GAME_PLAN.md §4.9, MG6).

Every item the one trainer economy can hold: id -> class, the Cobblemon item it is mirrored as (when it
is), the move a machine teaches (TMs cross by move) and each family's symbol. Java bundles the file in
the jar (``Items``), gen1-bridge carries the generated ``lib/kb_items.lua`` (``catalog.py --write``),
and the stand-ins and tests read it through here.
"""
import json
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from .games import render_lua
from .paths import PROTOCOL_DIR

FILE_NAME = "items.json"
CLASSES = ("ball", "medicine", "battle", "field", "stone", "held", "valuable", "tm", "key")
LUA_HEADER = (
    "-- kb_items: the canonical item catalogue (protocol/items.json, docs/specs/\n"
    "-- MULTI_GAME_PLAN.md §4.9): each item of the one trainer economy with its\n"
    "-- class, the move a machine teaches, and each family's symbol for it.\n"
    "--\n"
    "-- GENERATED from protocol/items.json by tools/kb_protocol/catalog.py (--write);\n"
    "-- never edit by hand. A test fails when it is stale.\n"
    "\n"
)


@dataclass(frozen=True)
class Item:
    id: str
    clazz: str
    cobblemon: str | None
    move: int | None
    symbols: dict


class Items:
    def __init__(self, data):
        self.version = data["version"]
        self.items = {i: Item(i, e["class"], e.get("cobblemon"), e.get("move"), dict(e["symbols"]))
                      for i, e in data["items"].items()}

    @property
    def ids(self):
        return sorted(self.items)

    def get(self, item_id):
        return self.items.get(item_id)

    def symbol(self, family, item_id):
        it = self.items.get(item_id)
        return it.symbols.get(family) if it else None

    def canonical(self, family, symbol):
        """The canonical id of a family's symbol, or None."""
        for it in self.items.values():
            if it.symbols.get(family) == symbol:
                return it.id
        return None

    def mirrored(self):
        """Ids the Minecraft mirror creates stacks for: battle-usable classes with a Cobblemon item."""
        return sorted(i for i, it in self.items.items() if it.cobblemon and it.clazz in ("ball", "medicine", "battle"))

    def crossing(self):
        """Ids the ledger may hold (every class but key)."""
        return sorted(i for i, it in self.items.items() if it.clazz != "key")


def read_items(root=PROTOCOL_DIR):
    with open(Path(root) / FILE_NAME, encoding="utf-8") as fh:
        return json.load(fh)


@lru_cache(maxsize=None)
def load_items(root=None):
    return Items(read_items(PROTOCOL_DIR if root is None else root))


def render_items_lua(data):
    """gen1-bridge/lib/kb_items.lua's text for a catalogue document."""
    return render_lua(data, header=LUA_HEADER)
