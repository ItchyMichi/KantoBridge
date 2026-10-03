"""M8 (wave 2) simulations: battlesim's game with a made-up hall of story objects -- a static
Pokemon object, a hideout boss that pushes his battle directly, an in-game trader, the Name Rater,
the daycare gentleman -- and a ghost tower with a step trigger, on the fakes of fake_content.lua.
Everything is made up: the maps, species, trainers, trades and every text.

    g = ContentGame(at="static")           # the player below the static object, facing up
    g.talk("static")                       # act.interact on it
    g.read_texts(until=lambda g: g.received("battle.start"))
"""

from __future__ import annotations

import copy

from battlesim import DATA, MAPS, BattleGame

HALL = {
    "rows": [
        "############",  # 0
        "#..........#",  # 1  the story objects on row 1
        "#..........#",  # 2  the player stands on row 2, below one of them, facing up
        "#..........#",  # 3
        "#..........#",  # 4
        "############",
    ],
    "npcs": [
        {"id": "TEST_HALL2_obj_1", "name": "HALL_BIRD", "x": 2, "y": 1, "sprite": "SPRITE_TEST_BIRD",
         "pokemon": "BIRDMON", "level": 50},
        {"id": "TEST_HALL2_obj_2", "name": "HALL_BOSS", "x": 4, "y": 1, "sprite": "SPRITE_TEST_BOSS",
         "trainerClass": "OPP_BOSS", "trainerParty": 1},
        {"id": "TEST_HALL2_obj_3", "name": "HALL_TRADER", "x": 6, "y": 1, "sprite": "SPRITE_TEST_TRADER"},
        {"id": "TEST_HALL2_obj_4", "name": "HALL_RATER", "x": 8, "y": 1, "sprite": "SPRITE_TEST_RATER"},
        {"id": "TEST_HALL2_obj_5", "name": "HALL_DAYCARE", "x": 10, "y": 1, "sprite": "SPRITE_TEST_DAYCARE"},
    ],
}
# the ghost's trigger is (3, 2): stepping up from (3, 3) sets it off
GHOST_TOWER = {"rows": ["#######", "#.....#", "#.....#", "#.....#", "#.....#", "#######"], "indoor": True}

SPOTS = {  # where the player stands, facing up, below each object
    "static": ("TEST_HALL2", 2, 2), "boss": ("TEST_HALL2", 4, 2), "trader": ("TEST_HALL2", 6, 2),
    "rater": ("TEST_HALL2", 8, 2), "daycare": ("TEST_HALL2", 10, 2), "ghost": ("GHOST_TOWER", 3, 3),
}
OBJECTS = {"static": "TEST_HALL2_obj_1", "boss": "TEST_HALL2_obj_2", "trader": "TEST_HALL2_obj_3",
           "rater": "TEST_HALL2_obj_4", "daycare": "TEST_HALL2_obj_5"}

CONTENT_MAPS = dict(MAPS)
CONTENT_MAPS["TEST_HALL2"] = HALL
CONTENT_MAPS["GHOST_TOWER"] = GHOST_TOWER

CONTENT_DATA = copy.deepcopy(DATA)
CONTENT_DATA["trainers"]["OPP_BOSS"] = {"name": "BOSS", "baseMoney": 99, "parties": [
    [{"species": "SLEEPMON", "level": 25}, {"species": "BIRDMON", "level": 29}],
]}
# a made-up in-game trade: a STARTMON_A for a FOXMON called "FOXY"
CONTENT_DATA["field"]["trades"] = [{"give": "STARTMON_A", "get": "FOXMON", "nickname": "FOXY", "dialogset": 1}]
TRADE_ROWS = [["trade", 1, "EVENT_TRADED_TEST"]]


class ContentGame(BattleGame):
    def __init__(self, at="static", start=None, **kw):
        m, x, y = SPOTS[at]
        kw.setdefault("maps", CONTENT_MAPS)
        kw.setdefault("data", CONTENT_DATA)
        super().__init__(start=start or (m, x, y, "up"), **kw)
        E = self.E
        E.talkFns[OBJECTS["boss"].encode()] = E.bossTalk
        E.talkFns[OBJECTS["rater"].encode()] = E.nameRaterTalk
        E.talkFns[OBJECTS["daycare"].encode()] = E.daycareTalk
        E.talkRows[OBJECTS["trader"].encode()] = self.b.to_lua(TRADE_ROWS)
        E.ghostTrigger(b"GHOST_TOWER", 3, 2, b"SLEEPMON", 30, b"EVENT_BEAT_TEST_GHOST")

    def talk(self, what):
        """act.interact on one of the hall's objects (the player stands below it)."""
        _, x, y = SPOTS[what]
        return self.send("act.interact", {"map": "TEST_HALL2", "x": x, "y": y - 1})

    def give(self, species, level, nickname=None):
        """A party member from the engine's own give (no prompt), returned."""
        self.script([["give_pokemon", species, level, True]])
        self.run(2)
        mon = self.party()[-1]
        if nickname is not None:
            mon.nickname = nickname.encode()
        return mon

    def open_kind(self, kind):
        return [k for k, u in self.open_uis().items() if u["kind"] == kind]

    def mirror_open(self, screen):
        return [k for k, u in self.open_uis().items() if u["kind"] == "mirror" and u.get("screen") == screen]

    def press(self, button="a"):
        """act.button on the mirror screen on top (the party menu, the trade animation)."""
        return self.send("act.button", {"button": button, "how": "tap"})
