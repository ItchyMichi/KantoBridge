"""A simulated Wild Routes zone (WR2) with gen1-bridge's main.lua: a made-up town with a tree line, its
block records and tileset in full content registries (fake_registry.lua), a route list naming a zone
whose exit clears a path through the trees, mod.world (fake_worldapi.lua), and the zone's route map as
a walkable sim map. Built on battlesim's BattleGame (the fake script runner, battles, the bag).
Everything is made up: the maps, tiles, trainers, items and every text.

    g = RouteGame()                       # the player on the route map's x1, facing right
    g.send("route.give", {"route": "test_zone", "id": "a1.i1", "item": "POTION"})
    g.read_texts(until=lambda g: g.received("route.result"))
"""

from __future__ import annotations

import copy
import json

from battlesim import DATA, ITEMS, MAPS, MOVES, SPECIES, TRAINERS, BattleGame

PATH, TREE, WATER = 1, 2, 3  # made-up tile ids
B_PATH, B_TREE, B_WATER = 0, 1, 2  # block ids of TILESET
TILESET = {
    "id": "TEST_WOODS_TILES", "image": "tilesets/test_woods.png",
    "blocks": [[PATH] * 16, [TREE] * 16, [WATER] * 16],
    "walkable": [PATH], "waterTiles": [WATER], "doorTiles": [], "warpTiles": [],
}
# 6 x 5 blocks: a ring of trees around path; a sign hidden in the trees at cell (8, 0)
TOWN_BLOCKS = [
    [B_TREE, B_TREE, B_TREE, B_TREE, B_TREE, B_TREE],
    [B_TREE, B_PATH, B_PATH, B_PATH, B_PATH, B_TREE],
    [B_TREE, B_PATH, B_PATH, B_PATH, B_PATH, B_TREE],
    [B_TREE, B_PATH, B_PATH, B_WATER, B_PATH, B_TREE],
    [B_TREE, B_TREE, B_TREE, B_TREE, B_TREE, B_TREE],
]
TOWN = {
    "id": "TEST_WOODS", "index": 3, "tileset": "TEST_WOODS_TILES", "width": 6, "height": 5, "borderBlock": B_TREE,
    "blocks": [b for row in TOWN_BLOCKS for b in row], "warps": [], "signs": [{"x": 8, "y": 0, "text": "TEST_SIGN"}],
    "objects": [],
}

# the zone: from cell (2, 2) facing up, one block deep: clears block (1, 0); its far cells (2, 0) and (3, 0)
# lead out; the way back lands on (2, 1) facing down
ZONE = {
    "id": "test_zone", "name": "TEST WILDS", "kind": "free_roam", "theme": "forest",
    "from": {"games": ["red", "blue"], "map": "TEST_WOODS", "x": 2, "y": 2, "facing": "up", "depth": 1},
    "areas": {"main": [3, 4], "side": 50},
    "levels": {"from": "neighbours", "perArea": 1, "maxRise": 3},
    "trainers": {"perArea": [1, 3], "rematch": "each_visit", "classes": ["OPP_TEST", "OPP_NOBODY"]},
    "dungeon": {"chance": 60, "floors": "by_level"},
    "music": "TEST_SONG",
}
ROUTE_MAP = "KB_ROUTE_TEST_ZONE"

# MG2 (routes v2): a connecting route between two regions. Its `from` end is in the woods (cell (2, 7)
# facing left: block (1, 3) -> (0, 3), beside the zone's spot), its `to` end in a second town,
# TEST_WOODS2 (the same shape), registered by another
# game's process in the product; the tests also run both ends in one game (the route map of the
# `to` end is then KB_ROUTE_TEST_PASS_TO). The `to` end carries its own classes and music.
TOWN2 = dict(TOWN, id="TEST_WOODS2", index=4)
CONNECTING = {
    "id": "test_pass", "name": "TEST PASS", "kind": "connecting", "theme": "forest",
    "from": {"region": "kanto", "games": ["red", "blue"], "map": "TEST_WOODS", "x": 2, "y": 7, "facing": "left", "depth": 1},
    "to": {"region": "hoenn", "games": ["emerald"], "map": "TEST_WOODS2", "x": 9, "y": 4, "facing": "right", "depth": 1,
           "classes": ["OPP_TEST"], "music": None},
    "areas": {"main": [2, 3], "side": 50},
    "levels": {"from": "neighbours", "perArea": 1, "maxRise": 3},
    "trainers": {"perArea": [1, 2], "rematch": "each_visit", "classes": ["OPP_TEST", "OPP_NOBODY"]},
    "dungeon": {"chance": 0},
    "music": "TEST_SONG",
}
PASS_MAP, PASS_MAP_TO = "KB_ROUTE_TEST_PASS", "KB_ROUTE_TEST_PASS_TO"


def route_list(*zones):
    zones = list(zones) or [ZONE]
    # MG2: a list holding a connecting route is version 2 (the schema's and kb_routes' rule)
    version = 2 if any(z.get("kind") == "connecting" for z in zones) else 1
    return json.dumps({"version": version, "routes": zones})


def registries(town=None, reject=None):
    pokemon = {k: {"dex": v["dex"]} for k, v in SPECIES.items()}
    moves = {k: {"index": v["index"]} for k, v in MOVES.items()}
    return {
        "records": {
            "maps": {"TEST_WOODS": town or TOWN, "TEST_WOODS2": TOWN2},
            "tilesets": {"TEST_WOODS_TILES": TILESET},
            "trainers": copy.deepcopy(TRAINERS),
            "music": {"TEST_SONG": {"id": "TEST_SONG"}},
            "items": copy.deepcopy(ITEMS),
            "pokemon": pokemon,
            "moves": moves,
        },
        "reject": reject or {},
    }


# the sim's maps: the town as cells (trees '#', path '.'), and the route map, two cells wide (A, x1)
# (the clearing, cells (2..3, 0..1), already open: the engine runs the patched map)
SIM_TOWN = {"rows": ["##..########", "##..########", "##........##", "##........##", "##........##",
                     "##........##", "##....ww..##", "##....ww..##", "############", "############"]}
SIM_ROUTE = {"rows": ["..", ".."]}
ROUTE_MAPS = dict(MAPS, TEST_WOODS=SIM_TOWN, TEST_WOODS2=SIM_TOWN, **{ROUTE_MAP: SIM_ROUTE, PASS_MAP: SIM_ROUTE, PASS_MAP_TO: SIM_ROUTE})


class RouteGame(BattleGame):
    def __init__(self, start=(ROUTE_MAP, 1, 0, "right"), zones=None, town=None, reject=None, files=None, **kw):
        files = dict(files or {})
        files.setdefault("data/routes.json", route_list(*(zones or [])))
        super().__init__(start=start, maps=ROUTE_MAPS, data=DATA, files=files,
                         registries=registries(town, reject), world_api=True, **kw)
        # the engine's merge: the KB_<CLASS> records gen1-bridge registered reach the live data
        self.b.FakeRegistry.merge(self.b.registries, self.E.data)

    # ------------------------------------------------------------ registries

    def writes(self, registry):
        w = self.b.registries[registry.encode()].writes
        return [w[i] for i in range(1, len(w) + 1)]

    def record(self, registry, rid):
        reg = self.b.registries[registry.encode()]
        return reg.get(reg, rid.encode())

    def step_script(self, map_id, x, y):
        """Runs the composed map step scripts of map_id at (x, y), as the engine does after a step;
        True when one consumed the step."""
        ms = self.b.registries[b"map_scripts"]
        return bool(ms.onStep(map_id.encode(), self.E.game, self.E.ow, x, y))

    def queued(self):
        q = self.b.world_api.queued
        return [lua_rows(q[i]) for i in range(1, len(q) + 1)]

    # ------------------------------------------------------------ the zone

    def place(self, map_id, x, y, facing="down"):
        self.E.startWarpTo(map_id.encode(), x, y, facing.encode())
        self.run(30)

    def where(self):
        p = self.E.ow.player
        return self.E.ow.map.id.decode(), p.cellX, p.cellY

    def progress(self):
        return [m["data"]["routes"] for m in self.received("route.progress")]

    def results(self):
        return [m["data"] for m in self.received("route.result")]


def lua_rows(rows):
    out = []
    for i in range(1, len(rows) + 1):
        row = rows[i]
        out.append([v.decode() if isinstance(v, bytes) else v for v in (row[j] for j in range(1, len(row) + 1))])
    return out
