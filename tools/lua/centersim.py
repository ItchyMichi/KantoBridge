"""A simulated Pokémon Center, Mart and sight-line route with gen1-bridge's main.lua, for the M7 tests:
the nurse's heal and the center save, the start menu's SAVE, save.done, session.bind, Bill's PC, the
Mart's flows and trainers that sight the player. Built on battlesim's BattleGame (the fake script
runner, fake_wild.lua and fake_center.lua). Everything is made up: the maps, the prices, the trainers
and every text.

    g = CenterGame()                      # TEST_CENTER, the player at (3, 3) below the counter, facing up
    g.give_starter()
    g.send("act.interact", {"map": "TEST_CENTER", "x": 3, "y": 1})     # the nurse, across the counter
    g.play(choice=0, until=lambda g: g.received("save.begin"))
"""

from __future__ import annotations

import copy

from battlesim import DATA, MAPS, BattleGame

# the nurse behind the counter at (3, 2); a PC tile on the wall at (7, 1), used facing up from (7, 2)
CENTER = {
    "rows": [
        "##########",  # 0
        "#..N...#.#",  # 1  the nurse (3, 1); the PC (7, 1) is part of the wall
        "#..C.....#",  # 2  the counter (3, 2)
        "#........#",  # 3  the player starts at (3, 3)
        "#........#",  # 4
        "##########",
    ],
    "npcs": [{"id": "TEST_CENTER_obj_1", "x": 3, "y": 1, "sprite": "SPRITE_TEST_NURSE", "nurse": True}],
    "pcs": [{"x": 7, "y": 1, "facing": "up"}],
}
# the clerk behind the counter at (2, 2); the player across it at (2, 3)
MART = {
    "rows": [
        "##########",  # 0
        "#.N......#",  # 1  the clerk (2, 1)
        "#.C......#",  # 2  the counter (2, 2)
        "#........#",  # 3  the player (2, 3)
        "##########",
    ],
    "npcs": [{"id": "TEST_MART_obj_1", "x": 2, "y": 1, "sprite": "SPRITE_TEST_CLERK",
              "mart": ["POKE_BALL", "POTION"]}],
}
# two trainers facing down: A at (2, 1) sees 3 cells, B at (7, 1) sees 2
SIGHT = {
    "rows": [
        "##########",  # 0
        "#........#",  # 1  trainer A (2, 1), trainer B (7, 1)
        "#........#",  # 2
        "#........#",  # 3
        "#........#",  # 4  the player starts at (3, 4): one step left is in A's sight, 3 cells away
        "##########",
    ],
    "npcs": [
        {"id": "SIGHT_ROUTE_obj_1", "x": 2, "y": 1, "sprite": "SPRITE_TEST_TRAINER", "facing": "down",
         "trainerClass": "OPP_TEST", "trainerParty": 1},
        {"id": "SIGHT_ROUTE_obj_2", "x": 7, "y": 1, "sprite": "SPRITE_TEST_TRAINER", "facing": "down",
         "trainerClass": "OPP_TEST", "trainerParty": 2},
    ],
}
CENTER_MAPS = dict(MAPS, TEST_CENTER=CENTER, TEST_MART=MART, SIGHT_ROUTE=SIGHT)

PRICES = {"POKE_BALL": 200, "GREAT_BALL": 600, "POTION": 300, "ANTIDOTE": 100, "X_ATTACK": 500, "REPEL": 350,
          "TOWN_MAP": 0, "SILPH_SCOPE": 0, "FIRE_STONE": 2100, "MOON_STONE": 0, "RARE_CANDY": 4800,
          "TM_SAND_T": 2000, "HM_WHIRL_T": 0}
CENTER_DATA = copy.deepcopy(DATA)
for _id, _price in PRICES.items():
    CENTER_DATA["items"][_id]["price"] = _price
CENTER_DATA["text"]["_SightAWonText"] = "YOU GOT ME!"
CENTER_DATA["trainer_headers"]["SIGHT_ROUTE"] = {
    1: {"event": "EVENT_BEAT_SIGHT_A", "won": "_SightAWonText", "range": 3, "battleText": "HEY YOU!"},
    2: {"event": "EVENT_BEAT_SIGHT_B", "range": 2, "battleText": "OVER HERE!"},
}

NURSE = {"map": "TEST_CENTER", "x": 3, "y": 1}
PC = {"map": "TEST_CENTER", "x": 7, "y": 1}
CLERK = {"map": "TEST_MART", "x": 2, "y": 1}


class CenterGame(BattleGame):
    def __init__(self, start=("TEST_CENTER", 3, 3, "up"), **kw):
        super().__init__(start=start, maps=CENTER_MAPS, data=CENTER_DATA, **kw)

    def open_uis(self):
        """Open UIs by id, as a client keeps them: everything starts over at each snapshot (a new
        connection numbers its UIs again)."""
        start = max((i for i, m in enumerate(self.log) if m["type"] == "snapshot"), default=0)
        opened = {}
        for m in self.log[start:]:
            if m["type"] == "ui.open":
                opened[m["data"]["ui"]] = dict(m["data"])
            elif m["type"] == "ui.update" and m["data"]["ui"] in opened:
                opened[m["data"]["ui"]].update(m["data"])
            elif m["type"] == "ui.close":
                opened.pop(m["data"]["ui"], None)
        return opened

    def mode(self):
        """The newest mode: a player.state's, or a snapshot's."""
        for m in reversed(self.log):
            if m["type"] == "player.state":
                return m["data"]["mode"]
            if m["type"] == "snapshot":
                return m["data"]["state"]["mode"]
        return None

    def give_starter(self, species="STARTMON_C"):
        """A party member from the engine's own give (no prompt)."""
        self.script([["give_pokemon", species, 5, True]])
        self.run(2)

    def hurt(self):
        """Every party member down to 1 HP, so a heal is visible."""
        for mon in self.party():
            mon.hp = 1

    def last(self, msg_type):
        got = self.received(msg_type)
        return got[-1]["data"] if got else None

    def play(self, limit=900, choice=None, until=None, picks=()):
        """Plays the hidden game's UIs like a player until until(g) holds (or, without until, nothing
        is open and the mode is idle for a few steps): A at each text arrow, `choice` at each YES/NO
        (0 YES, 1 NO, None leaves it), and each of `picks` (a list of (kind, index) for the next
        menus/lists, in order)."""
        picks = list(picks)
        answered = set()
        quiet = 0
        for _ in range(limit):
            self.run()
            if until is not None and until(self):
                return
            uis = self.open_uis()
            if not uis:
                quiet = quiet + 1 if self.mode() == "idle" else 0
                if quiet >= 8 and until is None:
                    return
                continue
            quiet = 0
            top = max(uis)
            ui = uis[top]
            key = (top, ui.get("page"), ui.get("line"), ui.get("done"), ui.get("index"))
            if ui["kind"] == "text" and ui.get("waiting") and key not in answered:
                answered.add(key)
                self.send("ui.advance", {"ui": top})
            elif ui["kind"] == "choice" and choice is not None and ("c", top) not in answered:
                answered.add(("c", top))
                if choice == 1:
                    self.send("ui.back", {"ui": top})
                else:
                    self.send("ui.pick", {"ui": top, "index": 0})
            elif ui["kind"] in ("menu", "list") and picks and picks[0][0] == ui["kind"] and ("p", top, len(picks)) not in answered:
                answered.add(("p", top, len(picks)))
                kind, index = picks.pop(0)
                self.send("ui.pick", {"ui": top, "index": index})
        raise AssertionError("the UIs never settled")

    def top_ui(self):
        uis = self.open_uis()
        if not uis:
            return None, None
        top = max(uis)
        return top, uis[top]

    def wait_ui(self, kind, limit=300, screen=None):
        """Runs until a UI of that kind (and screen) is the top; returns (id, data)."""
        for _ in range(limit):
            ui_id, ui = self.top_ui()
            if ui is not None and ui["kind"] == kind and (screen is None or ui.get("screen") == screen):
                return ui_id, ui
            self.run()
        raise AssertionError(f"no {kind} UI on top")

    def read_top_text(self, limit=400):
        """Advances the top text box(es) until the top UI is no longer a text box."""
        answered = set()
        for _ in range(limit):
            ui_id, ui = self.top_ui()
            if ui is None or ui["kind"] != "text":
                return
            key = (ui_id, ui.get("page"), ui.get("line"), ui.get("done"))
            if ui.get("waiting") and key not in answered:
                answered.add(key)
                self.send("ui.advance", {"ui": ui_id})
            self.run()
        raise AssertionError("the text never finished")
