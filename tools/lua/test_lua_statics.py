"""M8 (wave 2) static encounters and direct battles (lib/kb_engine.lua "direct battles, native
catches, the daycare, renames"; act/kb_battle.lua), wired by main.lua, on the simulated engine with
fake_content.lua: a static Pokemon object talked to (the talk path, OC:3600-3622) goes to Cobblemon
as battle.start kind static with its cell; every result applied through the battle's own
continuation (caught and win: gone for good; fled and lose: per Gen 1; aborted: as a flight); a
ghost trigger like the Marowak's (with the SILPH_SCOPE: static and uncatchable; without: the native
ghost battle); a hideout boss like Giovanni's (kind scripted, his team, his armed loss line); an
aborted static_battle showing its object again (Snorlax's wake-up); no client means native battles;
the shadowed pushBattle is always put back; the battle music (P22) on every held battle, the victory
theme and the map's song after. Every message the peer receives is schema-checked (rig.Peer,
strict). All data is made up (contentsim.py, battlesim.py)."""

import copy

import pytest

from contentsim import CONTENT_DATA, OBJECTS, ContentGame

pytestmark = pytest.mark.lua

U1 = "0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b"
STATIC = OBJECTS["static"]
BOSS = OBJECTS["boss"]


def starts(g):
    return [m["data"] for m in g.received("battle.start")]


def errors(g):
    return [(m["data"]["code"], m.get("re")) for m in g.received("error")]


def held(g):
    """The next battle.start (texts read until it comes)."""
    n = len(starts(g))
    g.read_texts(until=lambda g: len(starts(g)) > n)
    return starts(g)[-1]


def game(at="static", **kw):
    g = ContentGame(at=at, **kw)
    g.give("STARTMON_C", 40)
    return g


def npc_ids(g):
    npcs = g.E.ow.npcs
    return [npcs[i].id.decode() for i in range(1, len(npcs) + 1)]


def pushes(g):
    p = g.E.pushes
    return [p[i] for i in range(1, len(p) + 1)]


def music(g):
    m = g.E.music.battle
    return [(m[i].kind.decode(), m[i].trainer.decode() if m[i].trainer else None, bool(m[i].own))
            for i in range(1, len(m) + 1)]


def victories(g):
    v = g.E.music.victory
    return [v[i].decode() for i in range(1, len(v) + 1)]


def same(g, a, b):
    """Lua identity (lupa wraps one table in different Python objects)."""
    return bool(g.b.eval("function(a, b) return rawequal(a, b) end")(a, b))


# ------------------------------------------------------------ the talk path's static objects


def test_a_static_object_talked_to_goes_to_cobblemon_with_its_cell():
    g = game()
    push = g.E.ow.pushBattle
    g.talk("static")
    data = held(g)
    assert data["kind"] == "static"
    assert data["wild"] == {"dex": 16, "level": 50, "x": 2, "y": 1}, "the object's own cell"
    assert data["rules"] == {"catch": True, "flee": True}
    assert "trainer" not in data and "team" not in data
    g.run(3)
    assert g.mode() == "busy" and g.E.ow.engaging is True
    assert len(g.E.battles) == 0 and len(pushes(g)) == 0, "never pushed: no native battle"
    assert same(g, g.E.ow.pushBattle, push), "the shadowed pushBattle is put back at once"
    assert music(g) == [("wild", None, True)], "the battle's own theme, as pushBattle plays it"
    st = g.status()
    assert st["directTaken"] == 1 and st["directArmed"] >= 1 and st["battleStatic"] == 1
    assert st["battleDirect"] == 1


@pytest.mark.parametrize("outcome", ["caught", "win"])
def test_caught_or_beaten_the_object_is_gone_for_good(outcome):
    g = game()
    g.talk("static")
    data = held(g)
    result = {"battle": data["battle"], "outcome": outcome}
    if outcome == "caught":
        result["caught"] = {"uid": U1, "dex": 16, "level": 50}
    g.send("battle.result", result)
    g.read_texts()
    assert g.E.save.defeatedTrainers[STATIC.encode()] is True, "the engine's own spawn filter keeps it away"
    assert STATIC not in npc_ids(g)
    assert [m["data"]["id"] for m in g.received("npc.remove")] == [STATIC]
    after = [(a.result.decode(), a.kind.decode()) for a in g.E.afterBattles.values()]
    assert after == [(outcome, "wild")], "the continuation's afterBattle with the real result"
    assert g.E.ow.engaging is False
    assert g.E.musicRestores == 1, "the map's song again"


@pytest.mark.parametrize("outcome", ["fled", "lose"])
def test_fled_or_lost_the_object_stays(outcome):
    g = game()
    bird = g.npc(STATIC)
    g.talk("static")
    data = held(g)
    g.send("battle.result", {"battle": data["battle"], "outcome": outcome})
    g.read_texts()
    assert g.E.save.defeatedTrainers[STATIC.encode()] is None
    after = [a.result.decode() for a in g.E.afterBattles.values()]
    assert after == ["run" if outcome == "fled" else "lose"]
    assert bird.frozen is False, "the continuation unfroze it"
    if outcome == "lose":
        texts = [m["data"]["pages"] for m in g.received("ui.open") if m["data"]["kind"] == "text"]
        assert any("blacked" in line for pages in texts for page in pages for line in page), "the blackout lines"
        assert any(w["data"]["kind"] == "blackout" for w in g.received("warp"))
    else:
        assert STATIC in npc_ids(g)


def test_aborted_ends_as_a_flight_and_can_be_fought_again():
    g = game()
    bird = g.npc(STATIC)
    g.talk("static")
    data = held(g)
    g.send("battle.result", {"battle": data["battle"], "outcome": "aborted"})
    g.read_texts()
    assert [a.result.decode() for a in g.E.afterBattles.values()] == ["run"]
    assert STATIC in npc_ids(g) and bird.frozen is False and g.E.ow.engaging is False
    assert g.E.musicRestores == 1, "the theme was playing: the map's song comes back"
    g.talk("static")
    again = held(g)
    assert again["battle"] != data["battle"] and again["kind"] == "static"
    assert errors(g) == []


def test_no_client_means_the_static_battle_is_native():
    g = ContentGame(at="static", connect=False)
    g.give("STARTMON_C", 40)
    g.E.interact()
    box = g.E.stack.top(g.E.stack)
    assert box.isTextBox
    g.E.uiPop()
    box.onDone()                       # the A press that closes the text
    assert [p.kind.decode() for p in pushes(g)] == ["wild"], "pushed natively"
    assert g.E.battles[1].species == b"BIRDMON"


def test_a_static_the_protocol_cannot_express_is_pushed_natively():
    g = game()
    g.npc(STATIC)[b"def"].pokemon = b"BIGMON"   # dex 251: beyond Gen 1
    g.talk("static")
    g.read_texts(until=lambda g: len(g.E.pushes) > 0, limit=200)
    assert starts(g) == []
    assert [p.species.decode() for p in pushes(g)] == ["BIGMON"]
    st = g.status()
    assert st["directDeclined"] == 1 and st["directNative"] == 1


# ------------------------------------------------------------ the ghost trigger (the Marowak's shape)


def ghost_game(scope):
    g = ContentGame(at="ghost")
    g.give("STARTMON_C", 40)
    if scope:
        g.E.save.inventory.SILPH_SCOPE = 1
    return g


def test_the_ghost_with_the_scope_is_a_static_battle_that_cannot_be_caught():
    g = ghost_game(scope=True)
    g.send("move.step", {"dir": "up", "n": 1})
    g.run(20)                          # the step lands on the trigger
    data = held(g)
    assert data["kind"] == "static"
    assert data["wild"] == {"dex": 143, "level": 30, "x": 3, "y": 1}, "no object: the cell in front"
    assert data["rules"] == {"catch": False, "flee": True}
    assert g.received("battle.native") == []
    g.send("battle.result", {"battle": data["battle"], "outcome": "win"})
    g.read_texts()
    assert g.flags().get("EVENT_BEAT_TEST_GHOST") is True, "the trigger's own win effect"
    assert g.E.ghostStepsRight is None


def test_the_ghost_fled_steps_the_player_off_the_trigger():
    g = ghost_game(scope=True)
    g.send("move.step", {"dir": "up", "n": 1})
    g.run(20)                          # the step lands on the trigger
    data = held(g)
    g.send("battle.result", {"battle": data["battle"], "outcome": "fled"})
    g.read_texts()
    g.run(40)
    assert g.E.ghostStepsRight == 1 and "EVENT_BEAT_TEST_GHOST" not in g.flags()


def test_the_ghost_without_the_scope_stays_native_and_is_announced():
    g = ghost_game(scope=False)
    g.send("move.step", {"dir": "up", "n": 1})
    g.run(20)
    g.read_texts(until=lambda g: g.received("battle.native"), limit=300)
    assert starts(g) == []
    assert [m["data"] for m in g.received("battle.native")] == [{"kind": "ghost", "phase": "start"}]
    assert [bool(p.ghost) for p in pushes(g)] == [True]


# ------------------------------------------------------------ the hideout boss (Giovanni's shape)


def test_the_boss_battle_pushed_directly_is_scripted_with_his_team():
    g = game(at="boss")
    g.talk("boss")
    data = held(g)
    assert data["kind"] == "scripted"
    assert data["trainer"] == {"cls": "OPP_BOSS", "party": 1, "name": "BOSS", "npc": BOSS, "map": "TEST_HALL2",
                               "x": 4, "y": 1}
    assert [(m["dex"], m["level"]) for m in data["team"]] == [(143, 25), (16, 29)]
    assert data["rules"] == {"catch": False, "flee": False}
    assert music(g) == [("trainer", "OPP_BOSS", True)]


def test_the_boss_win_shows_his_loss_line_and_runs_his_aftermath():
    g = game(at="boss")
    money = g.E.save.money
    g.talk("boss")
    data = held(g)
    g.send("battle.result", {"battle": data["battle"], "outcome": "win"})
    g.read_texts()
    texts = [m["data"]["pages"][0][0] for m in g.received("ui.open") if m["data"]["kind"] == "text"]
    assert "BOSS: IT CANNOT BE!" in texts, "the armed loss line with its tag"
    assert "MEET AGAIN." in texts, "the continuation's farewell"
    assert g.E.save.money == money + 99 * 29
    assert g.flags().get("EVENT_BEAT_TEST_BOSS") is True
    assert g.E.save.defeatedTrainers[BOSS.encode()] is True
    assert victories(g) == ["trainer"], "the victory theme under the texts"
    assert g.E.musicRestores == 1


@pytest.mark.parametrize("outcome", ["lose", "aborted", "caught"])
def test_the_boss_not_beaten_can_be_talked_to_again(outcome):
    g = game(at="boss")
    g.talk("boss")
    data = held(g)
    result = {"battle": data["battle"], "outcome": outcome}
    if outcome == "caught":
        result["caught"] = {"uid": U1, "dex": 143, "level": 25}
    g.send("battle.result", result)
    g.read_texts()
    assert "EVENT_BEAT_TEST_BOSS" not in g.flags()
    assert g.E.save.defeatedTrainers[BOSS.encode()] is None
    after = [a.result.decode() for a in g.E.afterBattles.values()]
    assert after == ["lose" if outcome == "lose" else "run"], "a trainer can't be fled or caught: aborted"


# ------------------------------------------------------------ static_battle (Snorlax's wake-up)


WAKE_ROWS = [
    ["show_text", "IT WOKE UP!"],
    ["hide_object", "TEST_ROUTE", "ROUTE_SLEEPER"],
    ["static_battle", "SLEEPMON", 30, "EVENT_BEAT_TEST_SLEEPER"],
]


def wake_game():
    g = ContentGame(at="static", start=("TEST_ROUTE", 7, 3, "right"))
    g.give("STARTMON_C", 40)
    g.npc("TEST_ROUTE_obj_2")[b"def"].name = b"ROUTE_SLEEPER"
    return g


def test_static_battle_sends_the_objects_cell():
    g = wake_game()
    g.script(WAKE_ROWS, npc="TEST_ROUTE_obj_2")
    data = held(g)
    assert data["kind"] == "static" and data["wild"] == {"dex": 143, "level": 30, "x": 8, "y": 3}
    assert data["rules"] == {"catch": True, "flee": True}
    assert music(g) == [("wild", None, False)], "Music.playBattle with the wild theme"


def test_an_aborted_static_battle_shows_its_object_again():
    g = wake_game()
    g.script(WAKE_ROWS, npc="TEST_ROUTE_obj_2")
    data = held(g)
    assert "TEST_ROUTE_obj_2" not in npc_ids(g), "hidden before the battle"
    g.send("battle.result", {"battle": data["battle"], "outcome": "aborted"})
    g.read_texts()
    g.run(3)
    assert g.E.save.objectToggles.TEST_ROUTE.ROUTE_SLEEPER is True
    assert "TEST_ROUTE_obj_2" in npc_ids(g), "back where the flute can wake it"
    assert "EVENT_BEAT_TEST_SLEEPER" not in g.flags()


def test_a_caught_static_battle_keeps_its_object_hidden_with_the_flag():
    g = wake_game()
    g.script(WAKE_ROWS, npc="TEST_ROUTE_obj_2")
    data = held(g)
    g.send("battle.result", {"battle": data["battle"], "outcome": "caught",
                             "caught": {"uid": U1, "dex": 143, "level": 30}})
    g.read_texts()
    assert g.flags().get("EVENT_BEAT_TEST_SLEEPER") is True
    assert g.E.save.objectToggles.TEST_ROUTE.ROUTE_SLEEPER is False
    assert g.E.shown is None


# ------------------------------------------------------------ battle music on the other paths


def test_gym_and_final_themes_and_the_victory_for_a_badge_fight():
    g = ContentGame(at="static", start=("TEST_ROUTE", 5, 2, "up"))
    g.give("STARTMON_C", 40)
    g.E.victories = g.b.to_lua({"OPP_BROCK#1": {"badge": "BOULDERBADGE", "text": "TAKE THIS BADGE!"}})
    g.script([["start_battle", "trainer", "OPP_BROCK", 1]])
    data = held(g)
    assert music(g)[-1] == ("gym", "OPP_BROCK", False)
    g.send("battle.result", {"battle": data["battle"], "outcome": "win"})
    g.read_texts()
    assert victories(g) == ["gym"]
    g.script([["rival_battle", "OPP_RIVAL3", 1]])
    data = held(g)
    assert music(g)[-1] == ("final", "OPP_RIVAL3", False)
    g.send("battle.result", {"battle": data["battle"], "outcome": "lose"})
    g.read_texts()
    assert victories(g) == ["gym"], "no victory theme for a loss"


def test_a_won_final_battle_plays_the_leaders_jingle_and_keeps_its_music():
    """BattleState:playVictoryMusic (BattleState.lua:5897-5911, v0.3.85): the final battle shares the
    gym leaders' jingle; finish (:5963-5970) no longer brings the map's song back after a WON final
    battle (the Hall of Fame sequence owns the music), so kb_engine skips its restore too."""
    g = ContentGame(at="static", start=("TEST_ROUTE", 5, 2, "up"))
    g.give("STARTMON_C", 70)
    g.script([["rival_battle", "OPP_RIVAL3", 1]])
    data = held(g)
    assert music(g)[-1] == ("final", "OPP_RIVAL3", False)
    g.send("battle.result", {"battle": data["battle"], "outcome": "win"})
    g.read_texts()
    assert victories(g) == ["gym"]
    assert [(c.kind.decode(), c.trainer) for c in g.E.music.victoryCalls.values()] == [("gym", None)]
    assert g.E.musicRestores == 0, "never restored after a won final battle"
    assert g.E.save.money > 0 and g.mode() == "idle"


@pytest.mark.parametrize("cls,jingle", [("OPP_LANCE", "trainer"), ("OPP_BROCK", "gym"), ("OPP_LORELEI", "trainer")])
def test_the_victory_jingle_is_the_leaders_only_for_a_badge_fight(cls, jingle):
    """BattleState:playVictoryMusic (:5903-5909): a gym-kind battle that isn't a leader's (Lance:
    the gym theme, no badge) wins to the trainer jingle; a badge fight (isGymLeader) to the leaders'."""
    data = copy.deepcopy(CONTENT_DATA)
    data["trainers"].setdefault(cls, {"name": "X", "baseMoney": 50,
                                      "parties": [[{"species": "BIRDMON", "level": 50}]]})
    g = ContentGame(at="static", start=("TEST_ROUTE", 5, 2, "up"), data=data)
    g.give("STARTMON_C", 60)
    g.E.victories = g.b.to_lua({"OPP_BROCK#1": {"badge": "BOULDERBADGE", "text": "TAKE THIS BADGE!"}})
    g.script([["start_battle", "trainer", cls, 1]])
    data = held(g)
    g.send("battle.result", {"battle": data["battle"], "outcome": "win"})
    g.read_texts()
    assert victories(g) == [jingle]
    assert g.E.musicRestores == 1, "the map's song again after an ordinary win"


@pytest.mark.parametrize("cls,kind", [("OPP_LORELEI", "trainer"), ("OPP_LANCE", "gym"), ("OPP_GIOVANNI", "trainer")])
def test_league_and_boss_themes_follow_the_engines_rule(cls, kind):
    """computeMusicKind (BattleState.lua:1791-1818): the Elite Four fight to the trainer theme, Lance
    to the gym leaders', Giovanni's party 1 is no badge fight (only party 3 is, in his gym)."""
    data = copy.deepcopy(CONTENT_DATA)
    data["trainers"][cls] = {"name": "X", "baseMoney": 50, "parties": [[{"species": "BIRDMON", "level": 50}]]}
    g = ContentGame(at="static", start=("TEST_ROUTE", 5, 2, "up"), data=data)
    g.give("STARTMON_C", 40)
    g.E.victories = g.b.to_lua({"OPP_GIOVANNI#3": {"badge": "EARTHBADGE"}})
    g.script([["start_battle", "trainer", cls, 1]])
    held(g)
    assert music(g)[-1] == (kind, cls, False)


def test_surf_held_plays_the_wild_theme():
    g = ContentGame(at="static", start=("WILD_ROUTE", 2, 3, "down"))
    g.give("STARTMON_C", 40)
    g.E.ow.player.surfing = True
    g.E.rolls.water = g.b.to_lua({"species": "FISHMON", "level": 10})
    g.E.ow.player.cellX, g.E.ow.player.cellY = 2, 4
    g.send("move.step", {"dir": "right", "n": 1})
    g.run(40)
    assert starts(g) and starts(g)[-1]["kind"] == "surf"
    assert music(g)[-1] == ("wild", None, False)
    data = starts(g)[-1]
    g.send("battle.result", {"battle": data["battle"], "outcome": "aborted"})
    g.run(5)
    assert g.E.musicRestores == 1, "restored after an abort too, since the theme played"


# ------------------------------------------------------------ robustness


def test_a_hot_reload_while_the_text_is_open_keeps_the_seam():
    g = game()
    g.talk("static")
    g.read_texts(until=lambda g: any(u["kind"] == "text" for u in g.open_uis().values()), limit=100)
    g.b.reload()          # F5 with the text open: its continuation was armed by the old instance
    g.rig.step(3)
    g.log = []
    g.connect()
    data = held(g)
    assert data["kind"] == "static"
    g.send("battle.result", {"battle": data["battle"], "outcome": "caught",
                             "caught": {"uid": U1, "dex": 16, "level": 50}})
    g.read_texts()
    assert g.E.save.defeatedTrainers[STATIC.encode()] is True


def test_plain_talk_is_untouched():
    g = game(at="rater")
    g.E.talkFns[OBJECTS["rater"].encode()] = None
    g.E.talk[OBJECTS["rater"].encode()] = b"JUST A TEST."
    g.talk("rater")
    g.read_texts()
    assert starts(g) == [] and pushes(g) == []
    assert g.npc(OBJECTS["rater"]).frozen is False
    assert errors(g) == []
