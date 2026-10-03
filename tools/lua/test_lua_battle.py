"""act/kb_battle.lua and kb_engine's battle seams (M5), wired by main.lua, on the simulated engine
with the fake script runner (fake_script.lua): team building (moves at level, the special-move
table, the rival's starter offset, DVs), every interception path (scripted start_battle, rival_battle,
static_battle, a map trainer by sight and by talk), applying battle.result (win, lose, the Oak's-lab
rival loss, aborted, fled, caught), the busy mode while waiting, re-sending battle.start after a
reconnect, a resync and a hot reload, and errors. Every message the peer receives is
schema-checked (rig.Peer, strict). All data is made up (battlesim.py)."""

import pytest

from battlesim import BattleGame, MOVE_NUMBERS

pytestmark = pytest.mark.lua

RIVAL = "OAKS_LAB_obj_1"
LAB_ROWS = [
    ["save_end_battle_text", "WHAT? I PICKED THE WRONG ONE!"],
    ["start_battle", "trainer", "OPP_RIVAL1", 2],
    ["heal_party"],
    ["set_flag", "EVENT_BATTLED_TEST_RIVAL"],
    ["jump_if_false", 8],  # a loss skips the two rows below
    ["show_text", "SMELL YA LATER!"],
    ["set_flag", "EVENT_AFTER_TEST_RIVAL"],
]


def give_starter(g, species="STARTMON_C"):
    """A party member from the engine's own give (no client answer needed: no runner prompt)."""
    g.script([["give_pokemon", species, 5, True]])
    g.run(2)


def starts(g):
    return [m for m in g.received("battle.start")]


def last_start(g):
    return starts(g)[-1]["data"]


def errors(g):
    return [(m["data"]["code"], m.get("re")) for m in g.received("error")]


# ------------------------------------------------------------ team building


@pytest.fixture()
def engine_game():
    return BattleGame(connect=False)


def engine_of(g):
    """A kb_engine instance on the fake game (the same tables main.lua's sees)."""
    Engine = g.b.module("lib/kb_engine.lua")
    opts = g.b.table()
    opts[b"require"] = g.b.env.require
    eng = Engine.new(opts)
    eng.setGame(g.E.game)
    return eng


def lua_list(t):
    return [t[i] for i in range(1, len(t) + 1)]


def as_team(t):
    out = []
    for m in lua_list(t):
        d = m.dvs
        out.append({"species": m.species.decode(), "dex": m.dex, "level": m.level, "moves": lua_list(m.moves),
                    "dvs": (d.atk, d[b"def"], d.spd, d.spc)})
    return out


def test_team_moves_at_level_dvs_and_money(engine_game):
    eng = engine_of(engine_game)
    t, name, money = eng.trainerTeam(b"OPP_TEST", 2)
    got = as_team(t)
    n = MOVE_NUMBERS
    # BIRDMON L10: GUST (L1) and SAND (L5); STARTMON_A L12: TACKLE, GROWL, LEECH (L7)
    assert got == [
        {"species": "BIRDMON", "dex": 16, "level": 10, "moves": [n["GUST_T"], n["SAND_T"]], "dvs": (9, 8, 8, 8)},
        {"species": "STARTMON_A", "dex": 1, "level": 12, "moves": [n["TACKLE_T"], n["GROWL_T"], n["LEECH_T"]],
         "dvs": (9, 8, 8, 8)},
    ]
    assert name == b"TESTER" and money == 15


def test_team_keeps_the_last_four_moves(engine_game):
    eng = engine_of(engine_game)
    t, _, _ = eng.trainerTeam(b"OPP_RIVAL3", 1)
    n = MOVE_NUMBERS
    birds = as_team(t)[0]
    # BIRDMON L61 knows GUST, SAND, QUICK, WHIRL, WING, AGILITY -> the last four, then SKY_ATTACK in slot 3
    assert birds["moves"] == [n["QUICK_T"], n["WHIRL_T"], n["SKY_ATTACK"], n["AGILITY_T"]]


def test_special_moves_lone_team_and_champion(engine_game):
    eng = engine_of(engine_game)
    n = MOVE_NUMBERS
    t, _, _ = eng.trainerTeam(b"OPP_BROCK", 1)
    got = as_team(t)
    # the leader's 2nd Pokemon (L14: TACKLE, GROWL, LEECH, VINE) gets BIDE in slot min(3, #moves + 1)
    assert got[1]["moves"] == [n["TACKLE_T"], n["GROWL_T"], n["BIDE"], n["VINE_T"]]
    assert got[0]["moves"] == [n["GUST_T"], n["SAND_T"], n["QUICK_T"]]
    t, _, _ = eng.trainerTeam(b"OPP_RIVAL3", 1)
    got = as_team(t)
    # the champion's starter (6th) gets its final form's move in slot 3
    assert got[5]["species"] == "VENUSAUR"
    assert got[5]["moves"] == [n["TACKLE_T"], n["GROWL_T"], n["MEGA_DRAIN"], n["VINE_T"]]


def test_special_move_slot_rule_on_short_move_lists(engine_game):
    Engine = engine_game.b.module("lib/kb_engine.lua")
    party = engine_game.b.to_lua([{"species": "X", "moves": ["A"]}, {"species": "Y", "moves": ["A"]}])
    Engine.applySpecialMoves(b"OPP_BROCK", 1, party)
    assert lua_list(party[2].moves) == [b"A", b"BIDE"]          # one move: lands in slot 2
    party = engine_game.b.to_lua([{"species": "X", "moves": ["A"]}] * 5)
    Engine.applySpecialMoves(b"OPP_GIOVANNI", 1, party)          # Giovanni only in party 3
    assert lua_list(party[5].moves) == [b"A"]
    Engine.applySpecialMoves(b"OPP_GIOVANNI", 3, party)
    assert lua_list(party[5].moves) == [b"A", b"FISSURE"]


def test_slot_moves_win_and_trainer_dvs_constant(engine_game):
    engine_game.E.data.constants = engine_game.b.to_lua({"trainerDvs": {"attack": 1, "defense": 2, "speed": 3,
                                                                       "special": 4}})
    eng = engine_of(engine_game)
    t, name, _ = eng.trainerTeam(b"OPP_SCOUT", 1)
    got = as_team(t)
    assert got[0]["moves"] == [MOVE_NUMBERS["GUST_T"], MOVE_NUMBERS["QUICK_T"]]
    assert got[0]["dvs"] == (1, 2, 3, 4)
    assert name == b"SCOUT ANN"                                   # the personal name


def test_rival_classes_are_named_after_the_rival(engine_game):
    eng = engine_of(engine_game)
    _, name, money = eng.trainerTeam(b"OPP_RIVAL1", 1)
    assert name == b"RIVALTEST" and money == 35
    assert eng.trainerTeam(b"OPP_NOBODY", 1)[0] is None
    assert eng.trainerTeam(b"OPP_TEST", 9)[0] is None


def test_the_trainer_party_hook_can_replace_the_party(engine_game):
    g = engine_game
    hook = g.b.eval("function(nextFn, cls, idx, party) return { { species = 'SLEEPMON', level = 33 } } end")
    g.b.mod.hooks.wrap(g.b.mod.hooks, b"trainer.party", hook)
    eng = engine_of(g)
    t, _, _ = eng.trainerTeam(b"OPP_TEST", 1)
    got = as_team(t)
    assert [(m["species"], m["level"]) for m in got] == [("SLEEPMON", 33)]


# ------------------------------------------------------------ the scripted path


def lab_battle(g, rows=LAB_ROWS, steps=3):
    g.script(rows, npc=RIVAL)
    g.run(steps)
    return last_start(g)


def test_scripted_rival_battle_holds_the_script_and_sends_battle_start():
    g = BattleGame()
    give_starter(g)
    data = lab_battle(g)
    assert data["kind"] == "rival" and data["battle"].startswith("b-")
    assert data["rules"] == {"catch": False, "flee": False}
    assert data["trainer"] == {"cls": "OPP_RIVAL1", "party": 2, "name": "RIVALTEST", "npc": RIVAL,
                               "map": "OAKS_LAB", "x": 3, "y": 3}
    assert data["team"] == [{"dex": 7, "level": 5, "moves": [MOVE_NUMBERS["TACKLE_T"], MOVE_NUMBERS["TAIL_T"]],
                             "dvs": {"atk": 9, "def": 8, "spd": 8, "spc": 8}}]
    assert g.mode() == "busy"
    g.run(30)
    assert len(starts(g)) == 1, "sent once"
    assert len(g.E.battles) == 0, "no native battle"
    assert "EVENT_BATTLED_TEST_RIVAL" not in g.flags(), "the script waits"
    assert g.E.ow.runner.isRunning(g.E.ow.runner)


def test_win_shows_the_loss_line_and_prize_then_resumes_the_script():
    g = BattleGame()
    give_starter(g)
    money = g.E.save.money
    data = lab_battle(g)
    g.send("battle.result", {"battle": data["battle"], "outcome": "win"})
    g.run(3)
    texts = [m["data"] for m in g.received("ui.open") if m["data"]["kind"] == "text"]
    assert texts[-1]["pages"][0][0].startswith("RIVALTEST: WHAT?"), "the trainer's line with its tag"
    assert g.E.save.money == money + 35 * 5, "base money x the last level"
    g.read_texts()
    opened = [m["data"]["pages"] for m in g.received("ui.open") if m["data"]["kind"] == "text"]
    lines = [line for pages in opened for page in pages for line in page]
    assert "TESTER got ¥175" in lines and "for winning!" in lines, "the prize line"
    assert opened[-1] == [["SMELL YA LATER!"]], "the script went on after the texts"
    flags = g.flags()
    assert "EVENT_BATTLED_TEST_RIVAL" in flags and "EVENT_AFTER_TEST_RIVAL" in flags
    assert g.E.resumed == 1, "through start_battle's own resume path"
    after = [(a.result, a.cls) for a in g.E.afterBattles.values()]
    assert after == [(b"win", b"OPP_RIVAL1")]
    assert len(g.received("party.heal")) == 1, "heal_party heals Cobblemon's party too"
    assert g.mode() == "idle" and not list(g.E.scriptErrors.values())
    st = g.status()
    assert st["battleWin"] == 1 and st["battleStarted"] == 1 and st["battlePending"] is False


def test_the_oaks_lab_rival_loss_goes_on_without_a_blackout():
    g = BattleGame()
    give_starter(g)
    money = g.E.save.money
    data = lab_battle(g)
    g.send("battle.result", {"battle": data["battle"], "outcome": "lose"})
    g.run(3)
    first = [m["data"] for m in g.received("ui.open") if m["data"]["kind"] == "text"][-1]
    assert first["pages"][0][0] == "RIVALTEST: HA! I WON!" and len(first["pages"]) == 1
    g.read_texts()
    flags = g.flags()
    assert "EVENT_BATTLED_TEST_RIVAL" in flags
    assert "EVENT_AFTER_TEST_RIVAL" not in flags, "lastCheck false skipped the win branch"
    assert not g.received("warp"), "no blackout warp"
    assert g.E.save.money == money and g.E.ow.map.id == b"OAKS_LAB"
    assert [(a.result, a.cls) for a in g.E.afterBattles.values()] == [(b"lose", b"OPP_RIVAL1")]
    assert len(g.received("party.heal")) == 1  # the lab's own heal_party


def test_a_loss_elsewhere_blacks_out():
    g = BattleGame(start=("TEST_ROUTE", 5, 4, "up"))
    give_starter(g)
    g.E.healPoint = g.b.to_lua({"map": "OAKS_LAB", "x": 4, "y": 5})
    money = g.E.save.money
    g.script([["start_battle", "trainer", "OPP_RIVAL1", 1], ["set_flag", "EVENT_NEVER"]])
    g.run(3)
    data = last_start(g)
    g.send("battle.result", {"battle": data["battle"], "outcome": "lose"})
    g.run(3)
    first = [m["data"] for m in g.received("ui.open") if m["data"]["kind"] == "text"][-1]
    assert first["pages"][0][0] == "RIVALTEST: HA! I WON!"
    g.read_texts()
    g.run(60)  # the warp's fade
    lines = [m["data"]["pages"] for m in g.received("ui.open") if m["data"]["kind"] == "text"]
    assert any("blacked" in "".join(sum(p, [])) for p in lines), "the blackout lines"
    warps = [m["data"] for m in g.received("warp")]
    assert [w["kind"] for w in warps] == ["blackout", "blackout"] and warps[-1]["to"]["map"] == "OAKS_LAB"
    assert g.E.save.money == money // 2
    assert len(g.received("party.heal")) >= 1, "a blackout heals Cobblemon's party"


def test_aborted_ends_the_script_so_it_can_run_again():
    g = BattleGame()
    give_starter(g)
    first = lab_battle(g)
    g.send("battle.result", {"battle": first["battle"], "outcome": "aborted"})
    g.run(5)
    assert not g.E.ow.runner.isRunning(g.E.ow.runner)
    assert "EVENT_BATTLED_TEST_RIVAL" not in g.flags() and not g.received("party.heal")
    assert g.E.resumed is None and g.E.scriptsEnded == 2   # the give's script and this one
    assert g.mode() == "idle"
    second = lab_battle(g)
    assert second["battle"] != first["battle"]
    g.send("battle.result", {"battle": second["battle"], "outcome": "win"})
    g.read_texts()
    assert "EVENT_BATTLED_TEST_RIVAL" in g.flags()


def test_rival_battle_adds_the_starter_offset():
    g = BattleGame()
    give_starter(g)
    g.E.save.flags.EVENT_CHOSE_SQUIRTLE = True
    g.script([["rival_battle", "OPP_RIVAL1", 1]])
    g.run(3)
    data = last_start(g)
    assert data["kind"] == "rival" and data["trainer"]["party"] == 2
    assert data["team"][0]["dex"] == 7
    g.send("battle.result", {"battle": data["battle"], "outcome": "win"})
    g.read_texts()
    assert g.E.resumed == 1


def test_other_scripted_trainers_are_kind_scripted():
    g = BattleGame()
    give_starter(g)
    g.script([["start_battle", "trainer", "OPP_TEST", 1]])
    g.run(3)
    data = last_start(g)
    assert data["kind"] == "scripted" and "npc" not in data["trainer"] and data["trainer"]["map"] == "OAKS_LAB"


@pytest.mark.parametrize("outcome, flagged", [("caught", True), ("fled", True), ("win", True), ("lose", False)])
def test_static_battle(outcome, flagged):
    g = BattleGame(start=("TEST_ROUTE", 5, 4, "up"))
    give_starter(g)
    g.E.healPoint = g.b.to_lua({"map": "TEST_ROUTE", "x": 1, "y": 1})
    g.script([["static_battle", "SLEEPMON", 30, "EVENT_BEAT_TEST_SLEEPMON"]], npc="TEST_ROUTE_obj_2")
    g.run(3)
    data = last_start(g)
    assert data["kind"] == "static" and "team" not in data and "trainer" not in data
    assert data["wild"] == {"dex": 143, "level": 30, "x": 8, "y": 3}
    assert data["rules"] == {"catch": True, "flee": True}
    result = {"battle": data["battle"], "outcome": outcome}
    if outcome == "caught":
        result["caught"] = {"uid": "6c1e2d3f-4a5b-4c6d-8e7f-a0b1c2d3e4f5", "dex": 143, "level": 30}
    g.send("battle.result", result)
    g.read_texts()
    assert ("EVENT_BEAT_TEST_SLEEPMON" in g.flags()) is flagged
    engine_result = {"caught": b"caught", "fled": b"run", "win": b"win", "lose": b"lose"}[outcome]
    assert [a.result for a in g.E.afterBattles.values()] == [engine_result]


def test_caught_needs_its_caught_block():
    g = BattleGame(start=("TEST_ROUTE", 5, 4, "up"))
    give_starter(g)
    g.script([["static_battle", "SLEEPMON", 30]])
    g.run(3)
    data = last_start(g)
    seq = g.send("battle.result", {"battle": data["battle"], "outcome": "caught"})
    g.run()
    assert ("bad_message", seq) in errors(g)
    assert g.mode() == "busy", "still waiting"


# ------------------------------------------------------------ the map trainer path


def route_game():
    g = BattleGame(start=("TEST_ROUTE", 5, 2, "up"))
    give_starter(g)
    return g


def test_map_trainer_by_sight_is_deferred_and_a_win_applies_its_side_effects():
    g = route_game()
    trainer = g.npc("TEST_ROUTE_obj_1")
    money = g.E.save.money
    g.E.startTrainerApproach(trainer)
    g.read_texts(until=lambda g: g.received("battle.start"))
    data = last_start(g)
    assert data["kind"] == "trainer"
    assert data["trainer"] == {"cls": "OPP_TEST", "party": 2, "name": "TESTER", "npc": "TEST_ROUTE_obj_1",
                               "map": "TEST_ROUTE", "x": 5, "y": 1}
    assert [m["level"] for m in data["team"]] == [10, 12]
    g.run(2)
    assert g.mode() == "busy" and g.E.ow.engaging is True and not list(g.E.battles)
    g.send("battle.result", {"battle": data["battle"], "outcome": "win"})
    g.run(3)
    line = [m["data"] for m in g.received("ui.open") if m["data"]["kind"] == "text"][-1]["pages"][0][0]
    assert line == "TESTER: I LOST TO YOU!"
    g.read_texts()
    assert g.E.save.defeatedTrainers.TEST_ROUTE_obj_1 is True
    assert "EVENT_BEAT_TEST_TRAINER" in g.flags()
    assert [(r.cls, r.party, r.shown) for r in g.E.rewards.values()] == [(b"OPP_TEST", 2, False)]
    assert [(a.result, a.kind) for a in g.E.afterBattles.values()] == [(b"win", b"trainer")]
    assert g.E.save.money == money + 15 * 12
    assert g.E.ow.engaging is False and trainer.frozen is False
    assert g.mode() == "idle"


def test_map_trainer_by_talk_holds_the_world_while_waiting():
    g = route_game()
    trainer = g.npc("TEST_ROUTE_obj_1")
    g.send("act.interact", {"map": "TEST_ROUTE", "x": 5, "y": 1})
    g.read_texts(until=lambda g: g.received("battle.start"))
    data = last_start(g)
    assert data["kind"] == "trainer"
    g.run(2)
    assert g.E.ow.engaging is True, "held as a sighting holds it"
    assert g.mode() == "busy"
    g.send("battle.result", {"battle": data["battle"], "outcome": "win"})
    g.read_texts()
    assert g.E.ow.engaging is False and trainer.frozen is False
    assert g.E.save.defeatedTrainers.TEST_ROUTE_obj_1 is True


@pytest.mark.parametrize("outcome", ["aborted", "fled"])
def test_map_trainer_aborted_or_fled_cancels_the_encounter(outcome):
    g = route_game()
    trainer = g.npc("TEST_ROUTE_obj_1")
    g.E.startTrainerApproach(trainer)
    g.read_texts(until=lambda g: g.received("battle.start"))
    data = last_start(g)
    g.send("battle.result", {"battle": data["battle"], "outcome": outcome})
    g.run(5)
    assert g.E.save.defeatedTrainers.TEST_ROUTE_obj_1 is None
    assert len(g.E.rewards) == 0 and len(g.E.afterBattles) == 0 and len(g.E.battles) == 0
    assert g.E.ow.cancelledTrainerSight.npcId == b"TEST_ROUTE_obj_1"
    assert g.E.ow.engaging is False and trainer.frozen is False
    st = g.status()
    assert st["battleAbort"] == 1 and st["battleRefused"] == (1 if outcome == "fled" else 0)


def test_map_trainer_loss_blacks_out():
    g = route_game()
    g.E.healPoint = g.b.to_lua({"map": "OAKS_LAB", "x": 4, "y": 5})
    g.E.startTrainerApproach(g.npc("TEST_ROUTE_obj_1"))
    g.read_texts(until=lambda g: g.received("battle.start"))
    data = last_start(g)
    g.send("battle.result", {"battle": data["battle"], "outcome": "lose"})
    g.read_texts()
    g.run(60)  # the warp's fade
    assert [w["data"]["kind"] for w in g.received("warp")] == ["blackout", "blackout"]
    assert g.E.save.defeatedTrainers.TEST_ROUTE_obj_1 is None


# ------------------------------------------------------------ links, reloads, errors


def test_without_a_client_the_hidden_game_fights_itself():
    g = BattleGame(connect=False)
    give_starter(g)
    g.script(LAB_ROWS, npc=RIVAL)
    g.rig.step(3)
    battles = list(g.E.battles.values())
    assert len(battles) == 1 and battles[0].cls == b"OPP_RIVAL1", "a native battle"


def test_a_reconnect_and_a_resync_send_the_pending_battle_again():
    g = BattleGame()
    give_starter(g)
    data = lab_battle(g)
    g.send("resync")
    g.run(2)
    assert [m["data"]["battle"] for m in starts(g)] == [data["battle"]] * 2
    snap = [i for i, m in enumerate(g.log) if m["type"] == "snapshot"][-1]
    assert [i for i, m in enumerate(g.log) if m["type"] == "battle.start"][-1] > snap
    g.peer.close()
    g.rig.step(5)
    g.connect()
    g.run(2)
    assert [m["data"]["battle"] for m in starts(g)] == [data["battle"]] * 3
    snap = [i for i, m in enumerate(g.log) if m["type"] == "snapshot"][-1]
    resent = [i for i, m in enumerate(g.log) if m["type"] == "battle.start"][-1]
    assert resent > snap and g.log[snap]["data"]["state"]["mode"] == "busy"
    g.send("battle.result", {"battle": data["battle"], "outcome": "win"})
    g.read_texts()
    assert "EVENT_BATTLED_TEST_RIVAL" in g.flags()


def test_a_hot_reload_keeps_the_pending_battle():
    g = BattleGame()
    give_starter(g)
    data = lab_battle(g)
    g.b.reload()          # F5: the old instance's hooks are gone; the script waits in its wrapper
    g.rig.step(3)
    g.connect()
    g.run(2)
    assert starts(g)[-1]["data"] == data, "the same battle, re-sent by the new instance"
    g.send("battle.result", {"battle": data["battle"], "outcome": "win"})
    g.read_texts()
    assert "EVENT_BATTLED_TEST_RIVAL" in g.flags() and g.E.resumed == 1
    assert not list(g.E.scriptErrors.values())


def test_results_for_other_battles_and_bad_results_are_refused():
    g = BattleGame()
    give_starter(g)
    data = lab_battle(g)
    a = g.send("battle.result", {"battle": "b-nope-1", "outcome": "win"})
    b = g.send("battle.result", {"battle": data["battle"], "outcome": "maybe"})
    g.run()
    assert ("battle.unknown", a) in errors(g) and ("bad_message", b) in errors(g)
    g.send("battle.result", {"battle": data["battle"], "outcome": "win"})
    g.read_texts()
    c = g.send("battle.result", {"battle": data["battle"], "outcome": "win"})
    g.run()
    assert ("battle.unknown", c) in errors(g), "applied once"


def test_a_builder_cannot_answer_a_battle():
    g = BattleGame(connect=False)
    g.connect(role="builder")
    seq = g.send("battle.result", {"battle": "b-x-1", "outcome": "win"})
    g.run()
    assert ("role", seq) in errors(g)


def test_back_at_the_title_a_pending_battle_is_dropped():
    g = BattleGame()
    give_starter(g)
    lab_battle(g)
    g.E.leaveWorld()
    g.run(2)
    st = g.status()
    assert st["battleDropped"] == 1 and st["battlePending"] is False and st["battleHeld"] is False


def test_logs_never_carry_names_or_teams():
    g = BattleGame()
    give_starter(g)
    data = lab_battle(g)
    g.send("battle.result", {"battle": data["battle"], "outcome": "win"})
    g.read_texts()
    text = "\n".join(g.rig.logs())
    for secret in ("RIVALTEST", "WRONG ONE", "SMELL", "STARTMON", "TESTER"):
        assert secret not in text
    assert "battle.held" in text and "battle.result" in text
