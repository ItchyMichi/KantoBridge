"""MG9 G2F (docs/specs/GEN2_BRIDGE_PLAN.md §5.10): the Gen 2 adapter's battle part (gen1-bridge/lib/engine/gen2/
battle.lua) on the fake Gen 2 engine, with a recording bridge: World.startBattle, pushBattleTransition and
BattleState.new wrapped once per process; a surfing wild, a fishing wild, a forced-shiny static and a trainer held
(no wipe, a placeholder in place of the battle screen) with the facts the core wants; the trainer's team by national
dex with Gen 2's DVs and held items; the answer: a win pays the prize and shows the defeat and money lines before the
engine's own closure, a CANLOSE loss its loss line (no whiteout), an abort hands the closure nil; what stays native
(no client, the contest, the catch tutorial) and its battle.native kind; the grass rolls left to the habitat pools.
Then main.lua with a peer: battle.start and battle.result. Everything is made up (TEST_* species, moves, trainers)."""

import pytest

from adapter2 import Adapter2, plain
from rig import Rig

pytestmark = pytest.mark.lua


def setup(live=True, party=True):
    a = Adapter2()
    a.eng = a.Engine.new(a.b.engine_opts(log=a.log, love=a.b.env.love, world=a.E.worldApi, hooks=a.b.mod.hooks,
                                         items=a.b.module("lib/kb_items.lua")))
    a.eng.setGame(a.E.game)
    w = a.field()
    if party:
        mon = a.E.Mon.new(a.E.data, b"TEST_CHIKORITA", 5)
        a.E.game.save.party[1] = mon
    a.bridge = a.b.eval("""function(live)
      local b = { facts = {}, plans = 0, live = function() return live end }
      b.battle = function(facts)
        b.facts[#b.facts + 1] = facts
        b.plans = b.plans + 1
        return { id = "b-test-" .. b.plans, kind = facts.wild and (facts.kind or "static") or "trainer",
                 data = { battle = "b-test-" .. b.plans } }
      end
      return b
    end""")(live)
    a.eng.attachBridge(a.bridge)
    return a, w


def last_facts(a):
    return plain(a.bridge.facts[len(a.bridge.facts)])


def top_id(a):
    top = a.E.game.stack.top(a.E.game.stack)
    return top.screenId.decode() if top is not None and top.screenId is not None else None


def recorder(a):
    return a.b.eval("""function()
      local r = { calls = {} }
      r.fn = function(outcome) r.calls[#r.calls + 1] = outcome == nil and "nil" or outcome end
      return r
    end""")()


def tap(a):
    a.press("a")
    a.tick()
    a.release("a")
    a.tick()


def ends(a):
    return [e.decode() for e in a.E.battleEnds.values()]


def test_the_battle_group_is_the_adapters_own():
    a, _ = setup()
    missing = sorted(g.decode() for g in a.Engine.missingGroups(a.eng).values())
    assert "battles" not in missing and "party" not in missing and "items" not in missing


def test_a_surfing_wild_is_held_with_its_dvs_and_details():
    a, w = setup()
    w.playerState = b"surf"
    a.E.nextDVs = a.lua({"attack": 14, "defense": 10, "speed": 10, "special": 10})
    assert a.E.wildEncounter(b"TEST_MAGIKARP", 12, b"water") is True
    facts = last_facts(a)
    assert facts["path"] == "wild" and facts["kind"] == "surf" and facts["dex"] == 129 and facts["level"] == 12
    assert facts["dvs"] == {"atk": 14, "def": 10, "spd": 10, "spc": 10}
    assert facts["shiny"] is True and facts["gender"] == "male" and facts["catch"] is True and facts["flee"] is True
    assert (facts["x"], facts["y"]) == (5, 7), "the water in front"
    # no wipe: the placeholder is up at once, and the field waits under it
    assert top_id(a) == "Gen2BattleState" and a.E.game.stack.top(a.E.game.stack).kbHeld is True
    rec = a.eng.battlePending()
    assert rec.id == b"b-test-1" and rec.state == b"waiting"
    assert a.eng.battleFinish(b"b-test-1", b"run") is True
    a.tick()
    assert ends(a) == ["run"] and top_id(a) is None and a.eng.battlePending() is None
    assert w.battleActive is None


def test_fishing_and_a_trap_battle():
    a, w = setup()
    a.E.wildEncounter(b"TEST_MAGIKARP", 5, b"water", a.lua({"battleType": "fish"}))
    assert last_facts(a)["kind"] == "fish"
    a.eng.battleFinish(b"b-test-1", b"caught")
    a.tick()
    a.E.wildEncounter(b"TEST_RATTATA", 7, b"water", a.lua({"battleType": 9}))
    facts = last_facts(a)
    assert facts["kind"] == "static" and facts["flee"] is False, "TRAP: no escape"
    a.eng.battleFinish(b"b-test-2", b"win")
    a.tick()
    assert ends(a) == ["caught", "win"]


def test_the_grass_rolls_are_the_habitat_pools_while_a_client_follows():
    a, _ = setup()
    roll = a.b.eval("function() return { species = 'TEST_RATTATA', level = 3 } end")
    assert a.eng.encounterRoll(roll, a.lua({}), a.lua({"terrain": "grass", "kind": "wild"}), a.bridge) is None
    assert a.eng.encounterRoll(roll, a.lua({}), a.lua({"terrain": "grass", "kind": "sweet_scent"}), a.bridge) is None
    assert a.eng.encounterRoll(roll, a.lua({}), a.lua({"terrain": "water", "kind": "wild"}), a.bridge) is not None
    off = a.b.eval("{ live = function() return false end }")
    assert a.eng.encounterRoll(roll, a.lua({}), a.lua({"terrain": "grass", "kind": "wild"}), off) is not None
    assert plain(a.eng.bridgeCounts())["landRolls"] == 2


def test_a_trainer_is_held_with_its_team_and_a_win_pays_with_its_lines():
    a, w = setup()
    a.E.addNpc(3, a.lua({"x": 5, "y": 8, "trainer": {"class": 3, "member": 2}}))
    npc = w.npcs[len(w.npcs)]
    w.trainerNpc = npc
    done = recorder(a)
    money = a.E.game.save.player.money
    w.startScriptedBattle(w, b"YOUNGSTER", 2, done.fn)
    facts = last_facts(a)
    assert facts == {"path": "trainer", "cls": "YOUNGSTER", "party": 2, "rival": False, "map": "G2_TEST_TOWN",
                     "npc": "O3", "x": 5, "y": 8}
    team, name, base = a.eng.trainerTeam(b"YOUNGSTER", 2)
    team = plain(team)
    assert [m["dex"] for m in team] == [19, 152] and [m["level"] for m in team] == [2, 4]
    assert team[0]["dvs"] == {"atk": 9, "def": 8, "spd": 8, "spc": 8}, "a trainer's are always 9/8/8/8"
    assert team[0]["moves"] == [33] and team[1]["item"] == "oran_berry" and "item" not in team[0]
    assert name == b"TEST YOUNGSTER TEST MIKEY" and base == 4
    assert a.eng.battleFinish(b"b-test-1", b"win") is True
    a.tick()
    # the defeat line, then the money line, each a text box over the placeholder; then the engine's closure
    assert top_id(a) is None and a.E.game.stack.top(a.E.game.stack).pages[1][1] == b"TEST WIN TEXT"
    tap(a)
    assert a.E.game.stack.top(a.E.game.stack).pages[1][1] == b"TEST TESTER GOT 16"
    tap(a)
    assert ends(a) == ["win"] and [c.decode() for c in done.calls.values()] == ["win"]
    assert a.E.game.save.player.money == money + 16 and top_id(a) is None


def test_an_abort_hands_the_closure_nil_so_the_script_stops():
    a, w = setup()
    done = recorder(a)
    w.startScriptedBattle(w, b"YOUNGSTER", 1, done.fn)
    assert last_facts(a)["path"] == "script", "no engaged object: a scripted battle"
    assert a.eng.battleFinish(b"b-test-1", b"abort") is True
    a.tick()
    assert ends(a) == ["nil"] and [c.decode() for c in done.calls.values()] == ["nil"]
    assert a.eng.battlePending() is None


def test_the_first_rival_battle_can_be_lost():
    a, w = setup()
    a.E.game.save.rival.name = b"TESTRIVAL"
    done = recorder(a)
    money = a.E.game.save.player.money
    w.startScriptedBattle(w, b"RIVAL1", 1, done.fn, 1)
    facts = last_facts(a)
    assert facts["cls"] == "RIVAL1" and facts["rival"] is True and facts["party"] == 1
    assert a.eng.trainerTeam(b"RIVAL1", 1)[1] == b"TESTRIVAL"
    a.eng.battleFinish(b"b-test-1", b"lose")
    a.tick()
    assert a.E.game.stack.top(a.E.game.stack).pages[1][1] == b"TEST LOSS TEXT"
    tap(a)
    assert ends(a) == ["lose"] and a.E.blackouts == 0 and a.E.game.save.player.money == money, "CANLOSE: no whiteout"


def test_a_loss_whites_out():
    a, w = setup()
    w.startScriptedBattle(w, b"YOUNGSTER", 1, None)
    a.eng.battleFinish(b"b-test-1", b"lose")
    a.tick()
    assert ends(a) == ["lose"] and a.E.blackouts == 1


def test_what_stays_native_and_its_kind():
    a, w = setup()
    a.E.wildEncounter(b"TEST_RATTATA", 5, b"water", a.lua({"contest": True}))
    assert len(a.bridge.facts) == 0 and top_id(a) == "Gen2BattleTransition"
    assert a.eng.nativeBattle() is None, "not before its screen is up"
    a.tick()
    assert top_id(a) == "Gen2BattleState" and a.eng.nativeBattle() == b"contest"
    a.E.finishNative(b"win")
    assert a.eng.nativeBattle() is None and ends(a) == ["win"]
    # the catch tutorial (the Dude's empty party): old_man, as Gen 1's demo
    a.E.wildEncounter(b"TEST_RATTATA", 5, b"water", a.lua({"tutorial": True, "party": {}}))
    a.tick()
    assert a.eng.nativeBattle() == b"old_man"
    a.E.finishNative(b"caught")
    # no client: the hidden game fights it
    b2, w2 = setup(live=False)
    w2.startScriptedBattle(w2, b"YOUNGSTER", 1, None)
    assert len(b2.bridge.facts) == 0 and top_id(b2) == "Gen2BattleTransition"


def test_a_held_battle_whose_save_went_is_dropped_and_one_wrapper_per_process():
    a, w = setup()
    w.startScriptedBattle(w, b"YOUNGSTER", 1, None)
    assert a.eng.battleStale() is False
    a.E.game.save = a.E.newSave()
    assert a.eng.battleStale() is True and a.eng.battlePending() is None
    other = a.new_instance()
    other.attachBridge(a.bridge)
    assert a.E.World._kb_wrapped_startBattle is True
    assert a.E.World._kb_original_startBattle is not None


def test_main_sends_battle_start_and_takes_the_result():
    rig = Rig(family="gen2")
    rig.world("TEST_TOWN", 5, 6, "down")
    E = rig.E
    E.game.save.party[1] = E.Mon.new(E.data, b"TEST_CHIKORITA", 5)
    peer = rig.connect()
    peer.hello()
    peer.messages()
    w = E.game.world
    w.startScriptedBattle(w, b"YOUNGSTER", 2, None)
    got = []
    for _ in range(10):
        rig.step()
        got.extend(peer.messages())
        if any(m["type"] == "battle.start" for m in got):
            break
    start = next(m["data"] for m in got if m["type"] == "battle.start")
    assert start["kind"] == "scripted" and start["trainer"]["cls"] == "YOUNGSTER" and start["trainer"]["party"] == 2
    assert [m["dex"] for m in start["team"]] == [19, 152] and start["team"][1]["item"] == "oran_berry"
    assert start["team"][0]["dvs"] == {"atk": 9, "def": 8, "spd": 8, "spc": 8} and start["rules"]["catch"] is False
    assert start["team"][1]["gender"] in ("male", "female")
    peer.send("battle.result", {"battle": start["battle"], "outcome": "win"})
    inp = E.game.input
    for i in range(12):
        rig.step()
        top = E.game.stack.top(E.game.stack)
        if i % 2 == 0 and top is not None and top.pages is not None:
            inp.sourcePress(inp, b"a", b"test")
        else:
            inp.sourceRelease(inp, b"a", b"test")
    peer.messages()
    assert [e.decode() for e in E.battleEnds.values()] == ["win"]
