"""MG7 G3F (docs/specs/GEN3_BRIDGE_PLAN.md §4.10): the Gen 3 adapter's battle part
(gen1-bridge/lib/engine/game3/battle.lua) on the fake game3 engine (fake_battle3.lua). The adapter alone
(Adapter3, with a recording bridge): BattleBridge.start wrapped once per process; a trainer, a wild, a surf and a
fishing battle held (headless, no auto fight) with the facts the core wants; the trainer's team by national dex
with IVs; the result: a win pays the prize and shows the defeat line and the money text before the script's done,
a loss whites out, an abort stops the script (Birch's battle goes on as a win); what stays native (no client, a
link, the Safari Zone, a held battle already); a failed start; the land rolls left to the habitat pools; a hot
reload keeps one wrapper. Then main.lua with a peer (Rig(family="gen3")): battle.start (schema-checked: national
dex, Showdown moves, IVs) and battle.result. Everything is made up."""

import pytest

from adapter3 import Adapter3, plain
from rig import Rig

pytestmark = pytest.mark.lua

TOWN = "EM_TEST_TOWN"
TRAINERS = {
    7: {"class": 1, "className": "YOUNGSTER", "name": "TESTY", "defeat": "You win this time!",
        "party": [{"species": 11, "level": 5, "moves": [33, 45]}, {"species": 12, "level": 7}]},
    8: {"class": 2, "className": "RIVAL", "name": "MAY", "party": [{"species": 30, "level": 9, "moves": [1]}]},
}


def setup(live=True, party=True):
    a = Adapter3()
    s = a.field()
    if party:
        a.E.giveMon(1, 10, b"", False)
    a.E.trainerTable = a.lua(TRAINERS)
    a.bridge = a.b.eval("""function(live)
      local b = { facts = {}, plans = 0, live = function() return live end }
      b.battle = function(facts)
        b.facts[#b.facts + 1] = facts
        b.plans = b.plans + 1
        return { id = "b-test-" .. b.plans, kind = facts.wild and (facts.kind or "static") or "trainer", data = { battle = "b-test-" .. b.plans } }
      end
      return b
    end""")(live)
    a.eng.attachBridge(a.bridge)
    return a, s


def last_facts(a):
    return plain(a.bridge.facts[len(a.bridge.facts)])


def test_a_trainer_battle_is_held_headless_with_the_cores_facts_and_team():
    a, s = setup()
    a.E.Space.store.vars[0x800F] = 3  # VAR_LAST_TALKED: object 3
    a.E.Objects._byId[3] = a.lua({"cellX": 4, "cellY": 2})
    a.E.startTrainer(7)
    start = a.E.starts[len(a.E.starts)]
    assert start.headless is True and start.autoFight is False
    assert a.E.Battle.isActive() is True
    facts = last_facts(a)
    assert (facts["cls"], facts["party"], facts["path"], facts["rival"], facts["npc"], facts["x"], facts["y"]) == (
        "TRAINER_CLASS_YOUNGSTER", 7, "trainer", False, "O3", 4, 2)
    rec = a.eng.battlePending()
    assert rec.id == b"b-test-1" and rec.state == b"waiting"
    # the observer: busy (a held battle), never a screen; the UI stack shows the overworld alone
    f = a.facts()
    assert (f["top"], f["topName"], f["battle"]) == ("overworld", "Overworld", True)
    assert [e["cls"] for e in plain(a.eng.uiStack())] == ["Overworld"]
    # the team as the core wants it: national dex, level, Showdown moves (the learnset's when the foe has none), IVs
    team, name, money = a.eng.trainerTeam(b"TRAINER_CLASS_YOUNGSTER", 7)
    assert plain(team) == [
        {"dex": 261, "level": 5, "moves": [33, 45], "ivs": {"hp": 7, "atk": 7, "def": 7, "spa": 7, "spd": 7, "spe": 7}},
        {"dex": 262, "level": 7, "moves": [33, 45], "ivs": {"hp": 7, "atk": 7, "def": 7, "spa": 7, "spd": 7, "spe": 7}},
    ]
    assert name == b"YOUNGSTER TESTY" and money == 0
    assert a.eng.partySafe() is False, "the battle writes the party back: no sync now"


def test_the_rival_class_is_the_rival():
    a, _ = setup()
    a.E.startTrainer(8)
    facts = last_facts(a)
    assert facts["rival"] is True and facts["cls"] == "TRAINER_CLASS_RIVAL"
    assert facts["path"] == "script", "no object talked to: a script's battle"


def test_a_win_pays_the_prize_and_shows_the_texts_before_the_scripts_done():
    a, s = setup()
    a.E.startTrainer(7, a.lua({"defeatText": "Aw, man!"}))
    money = s.money
    assert a.eng.battleFinish(b"b-test-1", b"win") is True
    assert a.E.Battle.isActive() is False and a.E.aborts == 1
    assert s.money == money + 40 * 7, "Prize.rewardRse for the last member's level"
    assert s.battleOutcome == 1
    # the defeat line, then the money text, then the script's done
    M = a.E.Message
    assert M.isOpen() and M.currentPage() == b"Aw, man!"
    assert len(a.E.battleResults) == 0
    M.close()
    assert M.currentPage() == b"TESTER got $280 for winning!"
    M.close()
    assert plain(a.E.battleResults) == [{"label": "trainer", "result": "win"}]
    assert a.eng.battlePending() is None
    assert a.eng.battleFinish(b"b-test-1", b"win") is False, "answered once"


def test_a_loss_whites_out_and_an_abort_stops_the_script():
    a, s = setup()
    a.E.startTrainer(7)
    money = s.money
    assert a.eng.battleFinish(b"b-test-1", b"lose") is True
    assert s.money == money // 2 and a.E.respawns == 1
    assert plain(a.E.battleResults) == [{"label": "trainer", "result": "lose"}]
    a.E.startTrainer(7)
    halts = a.E.halts or 0
    assert a.eng.battleFinish(b"b-test-2", b"abort") is True
    assert a.E.halts == halts + 1, "the script stops there, so the trainer challenges again"
    assert len(a.E.battleResults) == 1, "the script's done never ran"
    assert a.eng.battleFinish(b"b-test-9", b"win") is False, "another id"


def test_wild_battles_surf_fish_and_birchs_battle():
    a, s = setup()
    a.E.Player.cellX, a.E.Player.cellY, a.E.Player.facing = 5, 6, b"up"
    a.E.startWild(4, 3)
    facts = last_facts(a)
    assert (facts["wild"], facts["kind"], facts["dex"], facts["level"], facts["x"], facts["y"]) == (True, "static", 254, 3, 5, 5)
    assert facts["catch"] is True and facts["flee"] is True
    a.eng.battleFinish(b"b-test-1", b"run")
    a.E.Player.surfing = True
    a.E.startWild(5, 3)
    assert last_facts(a)["kind"] == "surf"
    a.eng.battleFinish(b"b-test-2", b"caught")
    assert s.battleOutcome == 7
    a.E.Player.surfing = False
    a.E.fish(6, 8)
    assert last_facts(a)["kind"] == "fish" and a.E.fishRods == 1
    a.eng.battleFinish(b"b-test-3", b"run")
    # Birch's battle: no catching, no fleeing; an abort goes on as a win (the story has no other way out)
    a.E.startWild(7, 2, a.lua({"withDone": True, "firstBattleKind": "birch"}))
    facts = last_facts(a)
    assert facts["catch"] is False and facts["flee"] is False
    a.eng.battleFinish(b"b-test-4", b"abort")
    assert plain(a.E.battleResults) == [{"label": "wild", "result": "win"}]


def test_what_stays_native():
    a, _ = setup(live=False)
    a.E.startTrainer(7)
    assert a.E.starts[len(a.E.starts)].headless is False and a.eng.battlePending() is None, "no client: native"
    a.E.endNative(b"win")
    a, _ = setup()
    a.E.startWild(4, 3, a.lua({"link": True}))
    assert a.E.starts[len(a.E.starts)].headless is False and len(a.bridge.facts) == 0, "a link battle"
    a.E.endNative(b"win")
    a.E.Safari.active = True
    a.E.startWild(4, 3)
    assert a.E.starts[len(a.E.starts)].headless is False, "the Safari Zone"
    assert a.eng.nativeBattle() == b"safari"
    a.E.endNative(b"run")
    a.E.Safari.active = False
    a.E.startTrainer(7)
    a.E.startWild(4, 3)  # a battle while one is held: the engine refuses it as it would
    assert len(a.bridge.facts) == 1


def test_a_start_that_fails_leaves_nothing_held():
    a, _ = setup(party=False)
    a.E.startTrainer(7)
    assert a.eng.battlePending() is None and a.E.Battle.isActive() is False, "an empty party: the engine's answer"
    a, _ = setup()
    a.E.failNextStart = True
    a.E.startTrainer(7)
    assert a.eng.battlePending() is None
    assert plain(a.E.battleResults) == [{"label": "trainer", "result": "win"}], "the engine's done('win') reaches the script"


def test_land_rolls_are_the_habitat_pools_and_water_rolls_go_on():
    a, _ = setup()
    nxt = a.b.eval("function(def, ctx) return 'rolled' end")
    assert a.eng.encounterRoll(nxt, b"table", a.lua({"terrain": "land"}), a.bridge) is None
    assert a.eng.encounterRoll(nxt, b"table", a.lua({"terrain": "water"}), a.bridge) == b"rolled"
    off = a.b.eval("{ live = function() return false end }")
    assert a.eng.encounterRoll(nxt, b"table", a.lua({"terrain": "land"}), off) == b"rolled"


def test_a_stale_record_goes_and_a_hot_reload_keeps_one_wrapper():
    a, s = setup()
    a.E.startTrainer(7)
    assert a.eng.battleStale() is False
    a.E.Battle._active = False  # the engine reset the battle
    assert a.eng.battleStale() is True and a.eng.battlePending() is None
    wrapped = a.E.BattleBridge.start
    eng2 = a.new_instance()
    eng2.attachBridge(a.bridge)
    assert a.same(a.E.BattleBridge.start, wrapped), "one wrapper per process"
    a.E.startTrainer(7)
    assert eng2.battlePending().id == b"b-test-2", "the new instance's handler holds it"


# ------------------------------------------------------------ main.lua and a peer

def rig_in_field():
    rig = Rig(family="gen3")
    rig.world(TOWN, 5, 6, "down")
    rig.E.giveMon(1, 10, b"", False)
    rig.E.trainerTable = rig.b.to_lua(TRAINERS)
    return rig


def pump(rig, peer, until, limit=30):
    got = []
    for _ in range(limit):
        rig.step()
        got.extend(peer.messages())
        if any(until(m) for m in got):
            return got
    raise AssertionError(f"not within {limit} steps: {[m['type'] for m in got]}")


def test_main_sends_battle_start_and_takes_battle_result():
    rig = rig_in_field()
    peer = rig.connect()
    peer.hello()
    pump(rig, peer, lambda m: m["type"] == "snapshot")
    rig.E.startTrainer(7)
    got = pump(rig, peer, lambda m: m["type"] == "battle.start")
    start = next(m for m in got if m["type"] == "battle.start")["data"]
    assert start["kind"] == "trainer" or start["kind"] == "scripted"
    assert start["trainer"]["cls"] == "TRAINER_CLASS_YOUNGSTER" and start["trainer"]["party"] == 7
    assert [(t["dex"], t["level"], t["moves"]) for t in start["team"]] == [(261, 5, [33, 45]), (262, 7, [33, 45])]
    assert start["team"][0]["ivs"]["spe"] == 7 and "dvs" not in start["team"][0]
    assert start["rules"] == {"catch": False, "flee": False}
    peer.send("battle.result", {"battle": start["battle"], "outcome": "win"})
    rig.step(2)
    assert rig.E.Battle.isActive() is False and rig.E.aborts == 1
    assert rig.E.Message.isOpen(), "the prize text"


# ------------------------------------------------------------ FP5: native kinds, Trainer Hill, the facility desks

def test_every_native_battle_is_announced_and_trainer_hill_is_held():
    a, _ = setup()
    a.E.startWild(4, 3, a.lua({"factory": True}))
    assert a.E.starts[len(a.E.starts)].headless is False and a.eng.nativeBattle() == b"facility"
    a.E.endNative(b"win")
    assert a.eng.nativeBattle() is None
    a.E.startWild(4, 3, a.lua({"pokedude": True}))
    assert a.eng.nativeBattle() == b"old_man", "a tutorial"
    a.E.endNative(b"win")
    a.E.startTrainer(7, a.lua({"trainerHill": True}))
    assert a.E.starts[len(a.E.starts)].headless is True, "Trainer Hill's battles are Cobblemon's (FP5)"


def test_the_frontier_and_tent_desks_are_refused_while_a_client_follows():
    a, _ = setup()
    vm = a.b.eval("function() return { ctx = { name = 'vmctx' } } end")()
    ran = a.b.eval("function() local t = { n = 0 } return t, function() t.n = t.n + 1 return 'ran' end end")
    count, nxt = ran()
    a.E.Map.current = b"MAP_BATTLE_FRONTIER_BATTLE_TOWER_LOBBY"
    assert a.eng.scriptCommand(nxt, a.lua({"vm": vm}), b"special", a.lua({"id": 42})) is None
    assert count.n == 0 and a.E.specialVars[1].id == 0x800D and a.E.specialVars[1].value == 0
    assert plain(a.eng.refusedFeature()) == {"feature": "battle_frontier", "n": 1}
    a.E.Map.current = b"MAP_VERDANTURF_TOWN_BATTLE_TENT_LOBBY"
    a.eng.scriptCommand(nxt, a.lua({"vm": vm}), b"special", a.lua({"id": 248}))
    assert plain(a.eng.refusedFeature()) == {"feature": "battle_tent", "n": 2}
    assert a.eng.scriptCommand(nxt, a.lua({"vm": vm}), b"special", a.lua({"id": 8})) == b"ran", "other specials run"
    off, _ = setup(live=False)
    vm2 = off.b.eval("function() return { ctx = {} } end")()
    c2, n2 = off.b.eval("function() local t = { n = 0 } return t, function() t.n = t.n + 1 end end")()
    off.eng.scriptCommand(n2, off.lua({"vm": vm2}), b"special", off.lua({"id": 42}))
    assert c2.n == 1 and off.eng.refusedFeature() is None, "no client: the desk as usual"
