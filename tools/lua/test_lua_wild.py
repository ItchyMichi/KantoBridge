"""M6 wild encounters through main.lua on the simulated engine (fake_world.lua, fake_script.lua,
fake_wild.lua): grass and indoor rolls suppressed while a client or test session is connected (and
rolled natively without one), Safari Zone and ghost-tower battles passed through natively with
battle.native, surfing and fishing held for Cobblemon as battle.start kind surf / fish with their
wild Pokemon, every outcome's application (win, fled, caught, lose, aborted), REPEL, no bite, the
native old man's demo, re-sends after a reconnect and a hot reload mid-cast. Every message the peer
receives is schema-checked (rig.Peer, strict). All data is made up (battlesim.py)."""

import pytest

from battlesim import BattleGame

pytestmark = pytest.mark.lua

U1 = "6c1e2d3f-4a5b-4c6d-8e7f-a0b1c2d3e4f5"


def give_starter(g, species="STARTMON_C", level=5):
    g.script([["give_pokemon", species, level, True]])
    g.run(2)


class Walker:
    """move.step requests through the step driver, one at a time, each run to its landing."""

    def __init__(self, g):
        self.g, self.n = g, 0

    def step(self, direction, steps=22):
        self.n += 1
        self.g.send("move.step", {"dir": direction, "n": self.n})
        self.g.run(steps)

    def walk(self, dirs):
        for d in dirs:
            self.step(d)


def hold(g, direction, steps=20):
    """A direction held on the hidden game's own input (no client needed)."""
    inp = g.E.game.input
    inp.sourcePress(inp, direction.encode(), b"test-hand")
    g.rig.step(steps)
    inp.sourceRelease(inp, direction.encode(), b"test-hand")
    g.rig.step(2)


def starts(g):
    return [m["data"] for m in g.received("battle.start")]


def natives(g):
    return [(m["data"]["kind"], m["data"]["phase"]) for m in g.received("battle.native")]


def errors(g):
    return [(m["data"]["code"], m.get("re")) for m in g.received("error")]


def roll(g, terrain, species, level):
    g.E.rolls[terrain.encode()] = g.b.to_lua({"species": species, "level": level})


def native_battles(g):
    return list(g.E.battles.values())


def rolls(g, terrain):
    return g.E.rollCalls[terrain.encode()] or 0


def modes(g):
    out = []
    for m in g.received("player.state"):
        if not out or out[-1] != m["data"]["mode"]:
            out.append(m["data"]["mode"])
    return out


# ------------------------------------------------------------ grass and indoor: Cobblemon's habitat pools


def route_game(**kw):
    g = BattleGame(start=("WILD_ROUTE", 2, 3, "down"), **kw)
    give_starter(g)
    return g


def test_grass_rolls_are_suppressed_while_a_test_session_is_connected():
    g = route_game()
    roll(g, "grass", "BIRDMON", 3)
    w = Walker(g)
    w.walk(["right", "up"])                       # onto the grass at (3, 2)
    w.walk(["right", "left"] * 5)                 # ten more grass steps
    assert (g.E.ow.player.cellX, g.E.ow.player.cellY) == (3, 2)
    assert not starts(g) and not native_battles(g), "no battle of any kind"
    assert rolls(g, "grass") == 0, "the engine's roll never ran"
    assert set(modes(g)) <= {"idle"}, "never a battle screen, never held"
    st = g.status()
    assert st["mode"] == "idle"
    assert st["encounterGrass"] == 11 and st["encounterNative"] == 0
    assert st["battleStarted"] == 0 and st["battleNative"] == 0


def test_indoor_rolls_are_suppressed_too():
    g = BattleGame(start=("WILD_CAVE", 1, 1, "right"))
    give_starter(g)
    roll(g, "indoor", "BIRDMON", 3)
    Walker(g).walk(["right", "down", "left", "up"])
    assert not starts(g) and not native_battles(g) and rolls(g, "indoor") == 0
    assert g.status()["encounterIndoor"] == 4


def test_without_a_client_the_hidden_game_rolls_and_fights_natively():
    g = route_game()
    g.peer.close()
    g.rig.step(3)
    roll(g, "grass", "BIRDMON", 3)
    hold(g, "right")
    hold(g, "up")                                 # onto the grass: the vanilla roll, a native battle
    assert rolls(g, "grass") == 1
    (battle,) = native_battles(g)
    assert battle.kind == b"wild" and battle.species == b"BIRDMON" and not battle.ghost
    g.connect()
    st = g.status()
    assert st["encounterNative"] == 1 and st["encounterGrass"] == 0


def test_a_builder_connection_does_not_suppress():
    g = route_game(connect=False)
    g.connect(role="builder")
    roll(g, "grass", "BIRDMON", 3)
    hold(g, "right")
    hold(g, "up")
    assert rolls(g, "grass") == 1 and len(native_battles(g)) == 1


# ------------------------------------------------------------ native special battles (P9)


def test_safari_zone_rolls_pass_through_with_battle_native():
    g = BattleGame(start=("SAFARI_TEST", 1, 2, "up"))
    give_starter(g)
    g.E.save.safari = g.b.to_lua({"balls": 30, "steps": 500})
    roll(g, "grass", "BIRDMON", 22)
    Walker(g).walk(["right", "up"])               # onto the Safari grass at (2, 1)
    assert rolls(g, "grass") == 1, "the engine rolled it"
    (battle,) = native_battles(g)
    assert battle.safari is True
    assert natives(g) == [("safari", "start")] and not starts(g)
    assert g.mode() == "screen"
    g.send("act.button", {"button": "a", "how": "tap"})  # the mirror: the battle ends natively
    g.run(3)
    assert natives(g) == [("safari", "start"), ("safari", "end")]
    st = g.status()
    assert st["encounterSafari"] == 1 and st["battleNative"] == 1 and st["battleNativeEnded"] == 1
    assert st["battleNativeActive"] is False


def test_safari_maps_without_a_safari_game_are_like_grass():
    g = BattleGame(start=("SAFARI_TEST", 1, 2, "up"))
    give_starter(g)
    roll(g, "grass", "BIRDMON", 22)
    Walker(g).walk(["right", "up"])
    assert not native_battles(g) and not natives(g) and g.status()["encounterGrass"] == 1


def test_the_ghost_tower_passes_through_until_the_scope_is_in_the_bag():
    g = BattleGame(start=("POKEMON_TOWER_TEST", 1, 1, "right"))
    give_starter(g)
    roll(g, "indoor", "BIRDMON", 20)
    Walker(g).step("right")
    (battle,) = native_battles(g)
    assert battle.ghost is True and natives(g) == [("ghost", "start")]
    g.send("act.button", {"button": "a", "how": "tap"})
    g.run(3)
    assert natives(g) == [("ghost", "start"), ("ghost", "end")]
    g.E.ow.wildEncounterGraceSteps = 0
    g.E.save.inventory.SILPH_SCOPE = 1
    g.run(5)
    Walker(g).step("down")
    assert len(native_battles(g)) == 1, "with the scope: suppressed like any indoor roll"
    st = g.status()
    assert st["encounterGhost"] == 1 and st["encounterIndoor"] == 1


def test_a_running_native_battle_is_announced_again_after_a_snapshot():
    g = BattleGame(start=("POKEMON_TOWER_TEST", 1, 1, "right"))
    give_starter(g)
    roll(g, "indoor", "BIRDMON", 20)
    Walker(g).step("right")
    assert natives(g) == [("ghost", "start")]
    seq = g.send("resync")
    g.run(2)
    assert natives(g) == [("ghost", "start")] * 2
    snap = [i for i, m in enumerate(g.log) if m["type"] == "snapshot" and m.get("re") == seq][0]
    assert [i for i, m in enumerate(g.log) if m["type"] == "battle.native"][-1] > snap


def test_the_old_mans_demo_is_announced():
    g = route_game()
    g.E.oldManDemo(b"BIRDMON", 5, None)
    g.run(2)
    assert natives(g) == [("old_man", "start")]
    g.send("act.button", {"button": "a", "how": "tap"})
    g.run(3)
    assert natives(g) == [("old_man", "start"), ("old_man", "end")]


# ------------------------------------------------------------ surfing: rolled natively, fought in Cobblemon


def surf_game(level=15):
    g = route_game()
    g.E.ow.player.surfing = True
    roll(g, "water", "FISHMON", level)
    Walker(g).step("down")                        # onto the water at (2, 4)
    return g


def test_surfing_holds_the_rolled_pokemon_for_cobblemon():
    g = surf_game()
    (data,) = starts(g)
    assert data["kind"] == "surf" and data["battle"].startswith("b-")
    assert data["wild"] == {"dex": 129, "level": 15, "x": 2, "y": 5}, "the water in front of the player"
    assert data["rules"] == {"catch": True, "flee": True}
    assert "team" not in data and "trainer" not in data
    assert rolls(g, "water") == 1 and not native_battles(g)
    g.run(5)
    assert g.mode() == "busy" and g.E.ow.engaging is True
    st = g.status()
    assert st["encounterWater"] == 1 and st["battleSurf"] == 1 and st["battlePending"] is True
    g.run(30)
    assert len(starts(g)) == 1, "sent once"


@pytest.mark.parametrize("outcome, engine_result", [("win", b"win"), ("fled", b"run"), ("caught", b"caught")])
def test_surf_outcomes_that_continue(outcome, engine_result):
    g = surf_game()
    (data,) = starts(g)
    result = {"battle": data["battle"], "outcome": outcome}
    if outcome == "caught":
        result["caught"] = {"uid": U1, "dex": 129, "level": 15}
    g.send("battle.result", result)
    g.run(3)
    assert [(a.result, a.kind) for a in g.E.afterBattles.values()] == [(engine_result, b"wild")]
    assert g.E.ow.engaging is False and g.mode() == "idle"
    assert g.E.ow.wildEncounterGraceSteps == 3, "the engine's grace steps after a wild battle"
    assert not g.received("warp") and len(g.party()) == 1, "a catch reaches the party through party.sync"
    st = g.status()
    assert st["battlePending"] is False and st["battleResults"] == 1


def test_surf_loss_blacks_out():
    g = surf_game()
    g.E.healPoint = g.b.to_lua({"map": "WILD_ROUTE", "x": 8, "y": 1})
    money = g.E.save.money
    (data,) = starts(g)
    g.send("battle.result", {"battle": data["battle"], "outcome": "lose"})
    g.run(3)
    texts = [m["data"] for m in g.received("ui.open") if m["data"]["kind"] == "text"]
    assert "blacked" in "".join(sum(texts[-1]["pages"], [])), "the blackout lines"
    g.read_texts()
    g.run(60)
    assert [w["data"]["kind"] for w in g.received("warp")] == ["blackout", "blackout"]
    assert g.E.save.money == money // 2 and len(g.received("party.heal")) >= 1
    assert [a.result for a in g.E.afterBattles.values()] == [b"lose"]


def test_surf_aborted_ends_it_without_effects():
    g = surf_game()
    (data,) = starts(g)
    g.send("battle.result", {"battle": data["battle"], "outcome": "aborted"})
    g.run(3)
    assert len(g.E.afterBattles) == 0 and g.E.ow.engaging is False and g.mode() == "idle"
    assert g.status()["battleAbort"] == 1


def test_surf_caught_needs_its_caught_block():
    g = surf_game()
    (data,) = starts(g)
    seq = g.send("battle.result", {"battle": data["battle"], "outcome": "caught"})
    g.run()
    assert ("bad_message", seq) in errors(g) and g.mode() == "busy"


def test_repel_keeps_weaker_surf_pokemon_away():
    g = route_game()
    g.E.save.repelSteps = 50
    g.E.ow.player.surfing = True
    roll(g, "water", "FISHMON", 3)               # weaker than the level-5 lead
    Walker(g).step("down")
    assert not starts(g) and not native_battles(g)
    assert g.status()["encounterRepel"] == 1


def test_a_reconnect_sends_the_surf_battle_again():
    g = surf_game()
    (data,) = starts(g)
    g.peer.close()
    g.rig.step(5)
    g.connect()
    g.run(2)
    assert [d["battle"] for d in starts(g)] == [data["battle"]] * 2
    g.send("battle.result", {"battle": data["battle"], "outcome": "win"})
    g.run(3)
    assert g.mode() == "idle"


# ------------------------------------------------------------ fishing


def fish_game(**kw):
    g = route_game(**kw)
    g.E.fishPools.OLD_ROD = g.b.to_lua({"always": {"species": "FISHMON", "level": 5}})
    g.E.fishPools.GOOD_ROD = g.b.to_lua([{"species": "FISHMON", "level": 10}])
    return g


def test_a_bite_plays_natively_then_cobblemon_fights_it():
    g = fish_game()
    g.E.goFishing(b"OLD_ROD")
    g.run(40)                                     # the cast box (auto) types and pops itself
    assert not native_battles(g) and not starts(g)
    texts = [m["data"] for m in g.received("ui.open") if m["data"]["kind"] == "text"]
    assert texts[-1]["pages"] == [["Oh!", "It's a bite!"]], "the bite's own text"
    g.read_texts()
    (data,) = starts(g)
    assert data["kind"] == "fish" and data["wild"] == {"dex": 129, "level": 5, "x": 2, "y": 4}
    assert data["rules"] == {"catch": True, "flee": True}
    assert g.E.ow.fishing is None and g.E.ow.player.fishing is None, "the rod is put away"
    assert g.mode() == "busy" and not native_battles(g)
    st = g.status()
    assert st["encounterFish"] == 1 and st["battleFish"] == 1
    g.send("battle.result", {"battle": data["battle"], "outcome": "fled"})
    g.run(3)
    assert [(a.result, a.kind) for a in g.E.afterBattles.values()] == [(b"run", b"wild")]
    assert g.mode() == "idle" and g.E.ow.engaging is False


def test_not_even_a_nibble_stays_native():
    g = fish_game()
    g.E.fishBites = False
    g.E.goFishing(b"GOOD_ROD")
    g.run(40)
    texts = [m["data"] for m in g.received("ui.open") if m["data"]["kind"] == "text"]
    assert texts[-1]["pages"] == [["NO NIBBLE."]]
    g.read_texts()
    assert not starts(g) and not native_battles(g)
    assert g.status()["encounterNoBite"] == 1


def test_fishing_without_a_client_bites_natively():
    g = fish_game()
    g.peer.close()
    g.rig.step(3)
    g.E.goFishing(b"OLD_ROD")
    g.rig.step(60)                                # the cast, then the engine's own bite text
    top = g.top()
    g.E.game.input.sourcePress(g.E.game.input, b"a", b"hand")   # A on the bite text
    g.rig.step(1)
    g.E.game.input.sourceRelease(g.E.game.input, b"a", b"hand")
    g.rig.step(3)
    (battle,) = native_battles(g)
    assert battle.hooked is True and top.isTextBox is True


def test_a_client_that_drops_before_the_bite_gets_a_native_battle():
    g = fish_game()
    g.E.goFishing(b"OLD_ROD")
    g.run(5)
    g.peer.close()
    g.rig.step(40)
    assert g.top().isTextBox is True, "the engine's own bite text"
    assert not native_battles(g)


def test_fishing_in_the_safari_zone_is_native():
    g = BattleGame(start=("SAFARI_TEST", 2, 2, "down"))
    give_starter(g)
    g.E.save.safari = g.b.to_lua({"balls": 30, "steps": 500})
    g.E.fishPools.OLD_ROD = g.b.to_lua({"always": {"species": "FISHMON", "level": 5}})
    g.E.goFishing(b"OLD_ROD")
    g.run(40)
    g.read_texts(until=lambda g: native_battles(g))
    g.run(2)                                      # announced on the next step
    (battle,) = native_battles(g)
    assert battle.safari is True and battle.hooked is True and not starts(g)
    assert natives(g) == [("safari", "start")]


def test_a_hot_reload_during_the_cast_is_answered_by_the_new_instance():
    g = fish_game()
    g.E.goFishing(b"OLD_ROD")
    g.run(5)
    g.b.reload()                                  # F5 mid-cast: the old hooks are gone
    g.rig.step(3)
    g.connect()
    g.run(40)
    g.read_texts()
    (data,) = starts(g)
    assert data["kind"] == "fish" and not native_battles(g)
    g.send("battle.result", {"battle": data["battle"], "outcome": "win"})
    g.run(3)
    assert g.mode() == "idle"


def test_logs_never_carry_species_or_levels():
    g = surf_game()
    (data,) = starts(g)
    g.send("battle.result", {"battle": data["battle"], "outcome": "win"})
    g.run(3)
    text = "\n".join(g.rig.logs())
    assert "FISHMON" not in text and "BIRDMON" not in text
    assert "battle.held" in text and "battle.result" in text


# ------------------------------------------------------------ static encounters follow the same rules


def test_an_aborted_static_battle_leaves_the_pokemon_where_it_was():
    g = BattleGame(start=("TEST_ROUTE", 5, 4, "up"))
    give_starter(g)
    g.script([["static_battle", "SLEEPMON", 30, "EVENT_BEAT_TEST_SLEEPMON"]], npc="TEST_ROUTE_obj_2")
    g.run(3)
    (data,) = starts(g)
    assert data["kind"] == "static" and data["rules"] == {"catch": True, "flee": True}
    g.send("battle.result", {"battle": data["battle"], "outcome": "aborted"})
    g.run(5)
    assert "EVENT_BEAT_TEST_SLEEPMON" not in g.flags() and (g.E.hiddenObjects or 0) == 0
    assert len(g.E.afterBattles) == 0 and g.mode() == "idle"
    assert not g.E.ow.runner.isRunning(g.E.ow.runner), "the script stopped: it can run again"
