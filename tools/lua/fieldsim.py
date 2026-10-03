"""A simulated corner of a made-up Kanto with gen1-bridge's main.lua and one connected peer, for the
M8 tests: the story diffs (story.flags, story.toggles, story.blocks, story.lit), field moves from
Minecraft (act.field) and forced movement (spinners, currents, the slope, holes, pads, boulders).
Every map, block id, object, flag and text is made up (fake_field.lua, fake_world.lua).

    g = FieldGame()                         # TEST_FOREST, the player at (2, 2) facing the tree, role test
    g.give_moves("CUT"); g.give("CASCADEBADGE")
    seq = g.send("act.field", {"action": "cut"}); g.result(seq)
    g.read_texts(); g.received("story.blocks")

Maps are block-backed: two cells per block each way, a cell's char from its block's 2x2 pattern
(BLOCKSET), so a Cut or a door swap changes what is walkable.
"""

from __future__ import annotations

from uisim import Game

# block id -> its 2x2 cells (rows top to bottom): . floor, # wall, T cuttable tree, g tall grass,
# w water, = a closed gate
BLOCKSET = {
    0: ["..", ".."],
    1: ["##", "##"],
    2: ["TT", "TT"],   # a tree block: Cut swaps it for 3
    3: ["..", ".."],
    4: ["gg", "gg"],   # tall grass: Cut swaps it for 5
    5: ["..", ".."],
    6: ["ww", "ww"],
    7: ["==", "=="],   # a closed door (the card key opens it: 8)
    8: ["..", ".."],
    9: ["##", "##"],   # a wall nothing should change
    0x25: ["==", "=="],  # Victory Road's barrier, closed (a curated swap site)
    0x1D: ["..", ".."],  # ... and open
}

# TEST_FOREST, 6x4 blocks = 12x8 cells; the player starts at (2, 2) below a tree block at block
# (1, 0) (cells 2-3, 0-1); grass at block (3, 0) (cells 6-7, 0-1); water at blocks (4..5, 1..2)
# (cells 8-11, 2-5); a way out at block (2, 3) (cells 4-5, 6-7) whose edge cells warp: (4, 7) into
# TEST_LAB, (5, 7) back onto TEST_FOREST itself (a same-map reload)
FOREST = {
    "tileset": "OVERWORLD", "width": 6, "blockset": BLOCKSET,
    "blocks": [
        1, 2, 0, 4, 1, 1,
        1, 0, 0, 0, 6, 6,
        1, 0, 0, 0, 6, 6,
        1, 1, 0, 1, 1, 1,
    ],
    "warps": [{"x": 4, "y": 7, "to": "TEST_LAB", "tx": 2, "ty": 4},
              {"x": 5, "y": 7, "to": "TEST_FOREST", "tx": 2, "ty": 3}],
    "objects": [
        {"index": 1, "name": "FOREST_BALL", "item": "POTION", "x": 6, "y": 2},
        {"index": 2, "name": "FOREST_GUARD", "hidden": True, "x": 7, "y": 2},
        {"index": 3, "name": "FOREST_STATIC", "pokemon": "TESTMON", "x": 8, "y": 1},
        {"index": 4, "name": "FOREST_BOY", "x": 9, "y": 1},
    ],
}
# TEST_LAB (FACILITY), 4x3 blocks: a card-key door at block (1, 0), shipped open (8) and stamped
# shut (7) on every load until EVENT_TEST_DOOR
LAB = {
    "tileset": "FACILITY", "width": 4, "blockset": BLOCKSET,
    "blocks": [
        1, 8, 1, 1,
        0, 0, 0, 1,
        0, 0, 0, 1,
    ],
    "warps": [{"x": 2, "y": 5, "to": "TEST_FOREST", "tx": 4, "ty": 6}],
}
# VICTORY_ROAD_1F (a curated swap site at block (4, 6), CAVERN), 6x8 blocks, its barrier stamped
# by the map's onEnter; dark until Flash
VICTORY = {
    "tileset": "CAVERN", "width": 6, "blockset": BLOCKSET,
    "blocks": [
        1, 1, 1, 1, 1, 1,
        1, 0, 0, 0, 0, 1,
        1, 0, 0, 0, 0, 1,
        1, 0, 0, 0, 0, 1,
        1, 0, 0, 0, 0, 1,
        1, 0, 0, 0, 0, 1,
        1, 0, 0, 0, 0x25, 1,
        1, 1, 1, 1, 1, 1,
    ],
}
MAPS = {"TEST_FOREST": FOREST, "TEST_LAB": LAB, "VICTORY_ROAD_1F": VICTORY}

FIELD = {
    "cutTreeSwaps": [{"before": 2, "after": 3}, {"before": 4, "after": 5}],
    "cardKeyDoors": {"maps": ["TEST_LAB"], "openBlock": 8,
                     "closedDoors": {"TEST_LAB": [{"bx": 1, "by": 0, "block": 7, "open": 8,
                                                    "event": "EVENT_TEST_DOOR"}]}},
    "darkMaps": {"maps": ["VICTORY_ROAD_1F"]},
    "outsideTilesets": ["OVERWORLD", "PLATEAU"],
    "flyOrder": ["TEST_FOREST", "TEST_TOWN"],
    "flyWarps": {"TEST_FOREST": {"x": 2, "y": 3}, "TEST_TOWN": {"x": 1, "y": 1}},
}


class FieldGame(Game):
    """uisim.Game on block-backed maps with the M8 field data. `capture` emits mods.loaded again
    after the maps are installed (the boot copy of the map data; otherwise the dataset view
    serves it)."""

    def __init__(self, start=("TEST_FOREST", 2, 2, "up"), maps=None, field=None, capture=True, connect=True,
                 **kw):
        super().__init__(start=start, maps=maps or MAPS, connect=False, **kw)
        self._n = 0
        data = self.E.data
        f = dict(FIELD)
        f.update(field or {})
        for k, v in f.items():
            data.field[k.encode()] = self.b.to_lua(v)
        self.E.save.party = self.b.table()
        self.E.save.lastHeal = self.b.to_lua({"map": "TEST_FOREST", "x": 2, "y": 3})
        if capture:
            # the boot copy of the map data, taken before any map load changes it (Loader.lua:2163)
            self.b.mods_loaded()
        # the start map loads again now that the field data is in (its door stamps, its darkness)
        m, x, y, f = start
        self.E.setMapSim(m.encode(), x, y, f.encode())
        if connect:
            self.connect()

    # ------------------------------------------------------------ setup

    def give(self, *items, qty=1):
        for item in items:
            self.E.save.inventory[item.encode()] = True if item.endswith("BADGE") else qty

    def give_moves(self, *moves):
        """One party member that knows the moves."""
        mon = self.b.to_lua({"species": "TESTMON", "level": 30, "hp": 50, "stats": {"hp": 50},
                             "moves": [{"id": m} for m in moves]})
        party = self.E.save.party
        party[len(party) + 1] = mon

    def cell(self):
        p = self.E.ow.player
        return self.E.ow.map.id.decode(), p.cellX, p.cellY, p.facing.decode()

    def block(self, bx, by):
        m = self.E.ow.map
        return m.blockAt(m, bx, by)

    # ------------------------------------------------------------ the client

    def result(self, seq, limit=200):
        """The act.result answering seq (already received, or the next one to arrive)."""
        for m in self.log:
            if m["type"] == "act.result" and m.get("re") == seq:
                return m["data"]
        return self.until(lambda m: m["type"] == "act.result" and m.get("re") == seq, limit)["data"]

    def first(self, m_type, limit=200, **match):
        """The first message of a type (with these data fields) received so far, or the next one."""
        def ok(m):
            return m["type"] == m_type and all(m["data"].get(k) == v for k, v in match.items())
        for m in self.log:
            if ok(m):
                return m
        return self.until(ok, limit)

    def field(self, action, limit=200, **data):
        data["action"] = action
        return self.result(self.send("act.field", data), limit)

    def read_texts(self, limit=600):
        """Answers every text box with ui.advance at its arrow until no UI is open (like a player)."""
        uis, done = {}, set()
        for m in self.log:
            self._track(uis, m)
        for _ in range(limit):
            for m in self.run():
                self._track(uis, m)
            open_ids = [i for i, u in uis.items() if not u["closed"]]
            if not open_ids and self.E.settled():
                self.run(2)
                return
            if not open_ids:
                continue
            top = max(open_ids)
            u = uis[top]
            if u["kind"] == "text":
                key = (top, u["page"], u["line"], u["done"])
                if u["waiting"] and key not in done:
                    done.add(key)
                    self.send("ui.advance", {"ui": top})
        raise AssertionError("text boxes still open")

    @staticmethod
    def _track(uis, m):
        t, d = m["type"], m["data"]
        if t == "ui.open":
            uis[d["ui"]] = {"kind": d["kind"], "closed": False, "page": 0, "line": 0, "done": False,
                            "waiting": False, "data": d}
        elif t == "ui.update" and d["ui"] in uis:
            for k in ("page", "line", "done", "waiting"):
                if k in d:
                    uis[d["ui"]][k] = d[k]
        elif t == "ui.close" and d["ui"] in uis:
            uis[d["ui"]]["closed"] = True
        elif t == "snapshot":
            uis.clear()

    def settle(self, limit=400):
        for _ in range(limit):
            self.run()
            if self.E.settled():
                self.run(2)
                return
        raise AssertionError("never settled")

    def step(self, direction):
        self._n += 1
        self.send("move.step", {"dir": direction, "n": self._n})
        return self._n

    def answer(self, n, limit=300):
        return self.until(lambda m: m["type"] in ("player.pos", "player.blocked") and m["data"].get("n") == n,
                          limit)

    def story(self, kind=None):
        return [m["data"] for m in self.log if m["type"].startswith("story.") and (kind is None or m["type"] == kind)]
