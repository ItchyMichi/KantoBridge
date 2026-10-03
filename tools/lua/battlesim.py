"""A simulated lab and route with gen1-bridge's main.lua, the fake script runner (fake_script.lua)
and one connected peer, for the M5 tests: battles, gifts, heals and the shadow party; and (M6,
fake_wild.lua) wild maps -- a route with grass and water, a cave, a Safari Zone map, a ghost
tower -- plus items for the bag and item-use tests. Everything is made up: the maps, species,
moves, trainers, items and every text.

    g = BattleGame()                       # OAKS_LAB, the player at (4, 5) facing up, role test
    g.script([["start_battle", "trainer", "OPP_RIVAL1", 2]], npc="OAKS_LAB_obj_1")
    g.run(2); g.received("battle.start")
    g.send("battle.result", {"battle": ..., "outcome": "win"})
    g.read_texts()                         # advances every text box until none is open

The rows are the engine's command rows (Commands.lua's names); fake_script.lua runs them through
the script.command hook like ScriptRunner does.
"""

from __future__ import annotations

from uisim import Game

SPECIES = {
    # name: dex, level-1 moves, learnset, base stats (all made up)
    "STARTMON_A": {"dex": 1, "level1Moves": ["TACKLE_T", "GROWL_T"],
                   "learnset": [{"level": 7, "move": "LEECH_T"}, {"level": 13, "move": "VINE_T"}]},
    "STARTMON_B": {"dex": 4, "level1Moves": ["SCRATCH_T", "GROWL_T"],
                   "learnset": [{"level": 9, "move": "EMBER_T"}, {"level": 15, "move": "LEER_T"}]},
    "STARTMON_C": {"dex": 7, "level1Moves": ["TACKLE_T", "TAIL_T"],
                   "learnset": [{"level": 8, "move": "BUBBLE_T"}, {"level": 15, "move": "WATER_T"}]},
    "BIRDMON": {"dex": 16, "level1Moves": ["GUST_T"],
                "learnset": [{"level": 5, "move": "SAND_T"}, {"level": 12, "move": "QUICK_T"},
                             {"level": 19, "move": "WHIRL_T"}, {"level": 28, "move": "WING_T"},
                             {"level": 36, "move": "AGILITY_T"}]},
    "SLEEPMON": {"dex": 143, "level1Moves": ["HEADBUTT_T", "AMNESIA_T", "REST_T"], "learnset": []},
    "VENUSAUR": {"dex": 3, "level1Moves": ["TACKLE_T", "GROWL_T", "LEECH_T", "VINE_T"], "learnset": []},
    "BIGMON": {"dex": 251, "level1Moves": ["TACKLE_T"], "learnset": []},  # beyond Gen 1
    # M6: a fish, a stone evolution, TM/HM learnsets
    "FISHMON": {"dex": 129, "level1Moves": ["SPLASH_T"], "learnset": []},
    "FOXMON": {"dex": 37, "level1Moves": ["EMBER_T", "TAIL_T"], "learnset": [],
               "evolutions": [{"method": "ITEM", "item": "FIRE_STONE", "species": "FOXMON_2"}]},
    "FOXMON_2": {"dex": 38, "level1Moves": ["EMBER_T", "TAIL_T"], "learnset": []},
}
SPECIES["STARTMON_C"]["tmhm"] = ["SAND_T", "WHIRL_T"]
SPECIES["BIRDMON"]["tmhm"] = ["SAND_T", "WHIRL_T"]
for _name, _def in SPECIES.items():
    _def.setdefault("baseStats", {"hp": 45, "attack": 49, "defense": 49, "speed": 45, "special": 65})
    _def.setdefault("growthRate", "TEST_RATE")
    _def.setdefault("catchRate", 45)
    _def.setdefault("name", "MON" + str(_def["dex"]))

MOVE_NUMBERS = {
    "TACKLE_T": 33, "GROWL_T": 45, "LEECH_T": 73, "VINE_T": 22, "SCRATCH_T": 10, "EMBER_T": 52,
    "LEER_T": 43, "TAIL_T": 39, "BUBBLE_T": 145, "WATER_T": 55, "GUST_T": 16, "SAND_T": 28,
    "QUICK_T": 98, "WHIRL_T": 18, "WING_T": 17, "AGILITY_T": 97, "HEADBUTT_T": 29, "AMNESIA_T": 133,
    "REST_T": 156, "SPLASH_T": 150,
    # the boss moves the special-move table names (engine symbols, made-up numbers)
    "BIDE": 117, "SKY_ATTACK": 143, "MEGA_DRAIN": 72, "BLIZZARD": 59,
}
MOVES = {name: {"index": n, "pp": 20, "name": name} for name, n in MOVE_NUMBERS.items()}

TRAINERS = {
    "OPP_TEST": {"name": "TESTER", "baseMoney": 15, "parties": [
        [{"species": "BIRDMON", "level": 9}],
        [{"species": "BIRDMON", "level": 10}, {"species": "STARTMON_A", "level": 12}],
    ]},
    "OPP_RIVAL1": {"name": "RIVAL1", "baseMoney": 35, "parties": [
        [{"species": "STARTMON_B", "level": 5}],
        [{"species": "STARTMON_C", "level": 5}],
        [{"species": "STARTMON_A", "level": 5}],
    ]},
    "OPP_BROCK": {"name": "LEADER", "baseMoney": 99, "parties": [
        [{"species": "BIRDMON", "level": 12}, {"species": "STARTMON_A", "level": 14}],
    ]},
    "OPP_RIVAL3": {"name": "RIVAL3", "baseMoney": 99, "parties": [
        [{"species": "BIRDMON", "level": 61}, {"species": "STARTMON_B", "level": 59},
         {"species": "STARTMON_C", "level": 61}, {"species": "SLEEPMON", "level": 61},
         {"species": "STARTMON_A", "level": 63}, {"species": "VENUSAUR", "level": 65}],
    ]},
    "OPP_SCOUT": {"name": "SCOUT", "baseMoney": 20, "partyNames": ["ANN"], "parties": [
        [{"species": "BIRDMON", "level": 20, "moves": ["GUST_T", "QUICK_T"]}],
    ]},
}

# M6: made-up items, by the engine's symbols: battle items, a key item, other items, a stone,
# the candy, a TM and an HM (machine records as RomExtractor stamps them)
ITEMS = {
    "POKE_BALL": {"name": "BALL"}, "GREAT_BALL": {"name": "BALL2"}, "POTION": {"name": "MEDS"},
    "ANTIDOTE": {"name": "CURE"}, "X_ATTACK": {"name": "XA"}, "REPEL": {"name": "SPRAY"},
    "TOWN_MAP": {"name": "MAP", "keyItem": True}, "SILPH_SCOPE": {"name": "SCOPE", "keyItem": True},
    "FIRE_STONE": {"name": "RED STONE"}, "MOON_STONE": {"name": "MOON"}, "RARE_CANDY": {"name": "CANDY"},
    "TM_SAND_T": {"name": "TM99", "machine": {"kind": "TM", "number": 99, "move": "SAND_T"}},
    "HM_WHIRL_T": {"name": "HM09", "machine": {"kind": "HM", "number": 9, "move": "WHIRL_T"}},
}

TEXT = {
    "_TestWonText": "I LOST TO YOU!",
    "_Rival1WinText": "{RIVAL}: HA! I WON!",
}

# trainer headers by map label, keyed by object index (Data:trainerHeader)
HEADERS = {"TEST_ROUTE": {1: {"event": "EVENT_BEAT_TEST_TRAINER", "won": "_TestWonText",
                              "battleText": "YOU THERE! BATTLE!"}}}

DATA = {"pokemon": SPECIES, "moves": MOVES, "trainers": TRAINERS, "text": TEXT, "items": ITEMS,
        "trainer_headers": HEADERS, "constants": {}, "field": {"darkMaps": {"maps": []}},
        "encounters": {"WILD_ROUTE": {"grass": {"rate": 25, "slots": []}}}}

LAB = {
    "rows": [
        "##########",  # 0
        "#........#",  # 1
        "#........#",  # 2
        "#........#",  # 3  the rival at (3, 3)
        "#........#",  # 4
        "#........#",  # 5  the player starts at (4, 5)
        "#........#",  # 6
        "##########",
    ],
    "npcs": [{"id": "OAKS_LAB_obj_1", "x": 3, "y": 3, "sprite": "SPRITE_TEST_RIVAL"}],
}
ROUTE = {
    "rows": [
        "##########",  # 0
        "#........#",  # 1  a trainer at (5, 1) facing down
        "#........#",  # 2
        "#........#",  # 3  a sleeping mon at (8, 3)
        "#........#",  # 4  the player at (5, 4)
        "##########",
    ],
    "npcs": [
        {"id": "TEST_ROUTE_obj_1", "x": 5, "y": 1, "sprite": "SPRITE_TEST_TRAINER", "facing": "down",
         "trainerClass": "OPP_TEST", "trainerParty": 2},
        {"id": "TEST_ROUTE_obj_2", "x": 8, "y": 3, "sprite": "SPRITE_TEST_MON", "pokemon": "SLEEPMON",
         "level": 30},
    ],
}
# M6: wild maps
WILD_ROUTE = {
    "rows": [
        "##########",  # 0
        "#..gggg..#",  # 1  tall grass (3..6, 1) and (3..6, 2)
        "#..gggg..#",  # 2
        "#........#",  # 3  the player starts at (2, 3) facing down, toward the water
        "#wwwwww..#",  # 4  water
        "#wwwwww..#",  # 5
        "##########",
    ],
}
WILD_CAVE = {"rows": ["######", "#....#", "#....#", "######"], "indoor": True}
SAFARI_TEST = {"rows": ["########", "#.gggg.#", "#......#", "#wwww..#", "########"], "region": "SAFARI"}
POKEMON_TOWER_TEST = {"rows": ["######", "#....#", "#....#", "######"], "indoor": True}

MAPS = {"OAKS_LAB": LAB, "TEST_ROUTE": ROUTE, "WILD_ROUTE": WILD_ROUTE, "WILD_CAVE": WILD_CAVE,
        "SAFARI_TEST": SAFARI_TEST, "POKEMON_TOWER_TEST": POKEMON_TOWER_TEST}


def content():
    """mod.content records for main.lua's species and move lookups (snapshot, naming)."""
    return {"pokemon": {k: {"dex": v["dex"]} for k, v in SPECIES.items()},
            "moves": {k: {"index": v["index"]} for k, v in MOVES.items()}}


class BattleGame(Game):
    def __init__(self, start=("OAKS_LAB", 4, 5, "up"), role="test", connect=True, **kw):
        kw.setdefault("content", content())
        super().__init__(start=start, role=role, maps=MAPS, connect=False, script=True, **kw)
        self.E.installData(self.b.to_lua(DATA))
        if connect:
            self.connect(role)

    # ------------------------------------------------------------ scripts

    def script(self, rows, **extra):
        """Runs command rows on the overworld's runner; extra npc="<id>" names the script's NPC."""
        ex = self.b.table()
        for k, v in extra.items():
            if k == "npc":
                v = self.npc(v)
            ex[k.encode()] = v
        self.E.runScript(self.b.to_lua(rows), ex)

    def npc(self, npc_id):
        npcs = self.E.ow.npcs
        for i in range(1, len(npcs) + 1):
            if npcs[i].id == npc_id.encode():
                return npcs[i]
        raise KeyError(npc_id)

    def flags(self):
        f = self.E.save.flags
        return {k.decode(): v for k, v in f.items() if v is True}

    def party(self):
        p = self.E.save.party
        return [p[i] for i in range(1, len(p) + 1)]

    def mode(self):
        states = self.received("player.state")
        return states[-1]["data"]["mode"] if states else None

    def ids(self, msg_type):
        return [m["data"] for m in self.received(msg_type)]

    # ------------------------------------------------------------ answering the hidden game's UIs

    def open_uis(self):
        """Open UIs by id: the last ui.open of each id not yet closed."""
        opened = {}
        for m in self.log:
            if m["type"] == "ui.open":
                opened[m["data"]["ui"]] = dict(m["data"])
            elif m["type"] == "ui.update" and m["data"]["ui"] in opened:
                opened[m["data"]["ui"]].update(m["data"])
            elif m["type"] == "ui.close":
                opened.pop(m["data"]["ui"], None)
        return opened

    def read_texts(self, limit=600, choice=None, until=None):
        """Advances every text box whose arrow shows, answers YES/NO with `choice` (0 YES, 1 NO;
        None leaves it), until no UI is open (or until(g) holds) for a few steps."""
        answered = set()
        quiet = 0
        for _ in range(limit):
            self.run()
            if until is not None and until(self):
                return
            uis = self.open_uis()
            if not uis:
                quiet += 1
                if quiet >= 8:
                    return
                continue
            quiet = 0
            top = max(uis)
            ui = uis[top]
            key = (top, ui.get("page"), ui.get("line"), ui.get("done"))
            if ui["kind"] == "text" and ui.get("waiting") and key not in answered:
                answered.add(key)
                self.send("ui.advance", {"ui": top})
            elif ui["kind"] == "choice" and choice is not None and top not in answered:
                answered.add(top)
                if choice == 1:
                    self.send("ui.back", {"ui": top})
                else:
                    self.send("ui.pick", {"ui": top, "index": 0})
        raise AssertionError("the UIs never settled")
