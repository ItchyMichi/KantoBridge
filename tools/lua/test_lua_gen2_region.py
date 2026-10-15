"""MG9 (docs/specs/GEN2_BRIDGE_PLAN.md G2A, §5.8): the gen2 adapter's region acts (gen1-bridge/lib/engine/
gen2/region.lua) on the fake Gen 2 engine: the park is Gen 1's hold state on the shared stack, which skips
the whole field step while it is up (src/core/Game2.lua:1314-1318; G2-S7); the release pops it and the map's
song comes back; a start from the title continues the active slot's save or starts a NEW GAME (never
continueGame(nil), which would start one too); the story markers. Everything is made up."""

import pytest

from adapter2 import Adapter2, plain

pytestmark = pytest.mark.lua


def music(a):
    calls = a.E.musicCalls
    return [calls[i].op.decode() for i in range(1, len(calls) + 1)]


def test_the_park_freezes_the_field():
    a = Adapter2()
    assert tuple(a.eng.holdAway()) == (False, b"not_ingame")
    a.field()
    assert a.eng.holdAway() is True and a.eng.awayHeld() is True
    stack = a.E.game.stack
    assert stack.top(stack).kantoBridgeRegionHold is True
    assert music(a) == ["fadeOut"] and a.E.musicCalls[1].frames == 10
    steps = a.E.worldSteps
    a.press("down")
    a.tick(20)
    assert a.E.worldSteps == steps, "no World:step while the hold is on top"
    assert a.E.game.world.player.cellY == 6
    assert a.eng.holdAway() is True, "held already"
    a.release("down")


def test_the_release_pops_the_hold_and_brings_the_song_back():
    a = Adapter2()
    a.field()
    a.eng.holdAway()
    assert a.eng.releaseAway() is True and a.eng.awayHeld() is False
    stack = a.E.game.stack
    assert stack.top(stack) is None and music(a) == ["fadeOut", "restoreMapMusic"]
    steps = a.E.worldSteps
    a.tick(3)
    assert a.E.worldSteps == steps + 3
    assert a.eng.releaseAway() is False, "nothing held"


def test_a_hold_under_another_state_pops_itself_later():
    a = Adapter2()
    a.field()
    a.eng.holdAway()
    box = a.E.openTextBox()
    assert a.eng.releaseAway() is True
    stack = a.E.game.stack
    assert a.same(stack.top(stack), box)
    a.press("a")
    a.tick()  # the box goes
    a.release("a")
    a.tick()  # the hold's own update pops it
    assert stack.top(stack) is None


def test_start_from_the_title_starts_a_new_game():
    a = Adapter2()
    assert a.eng.startFromTitle() == b"new" and len(a.E.newGames) == 1
    assert a.facts()["starting"] is True
    assert tuple(a.eng.startFromTitle()) == (None, b"menu"), "Oak's speech already under way"
    assert len(a.E.continues) == 0


def test_start_from_the_title_continues_the_active_slots_save():
    a = Adapter2()
    save = a.E.newSave()
    save.position = a.lua({"map": "TEST_HOUSE", "x": 2, "y": 3, "facing": "up"})
    a.E.slotSave(b"slot1", save)
    a.E.SaveData.setActiveSlot(b"crystal", b"slot1")
    assert a.eng.startFromTitle() == b"continue"
    assert list(a.E.continues.values()) == [True] and len(a.E.newGames) == 0
    f = a.facts()
    assert (f["inGame"], f["map"], f["x"], f["y"]) == (True, "G2_TEST_HOUSE", 2, 3)
    assert tuple(a.eng.startFromTitle()) == (None, b"not_title")


def test_a_save_that_does_not_load_starts_a_new_game_not_continue_nil():
    a = Adapter2()
    a.E.Save.load = a.b.eval("function() return nil end")
    a.E.Save.exists = a.b.eval("function() return true end")
    assert a.eng.startFromTitle() == b"new"
    assert len(a.E.continues) == 0, "continueGame(nil) would start a new game behind our back"


@pytest.mark.parametrize("setup, reason, warn", [
    ("error", b"failed", None),
    ("play", b"menu", None),
    ("newfails", b"failed", "engine.new_game_failed"),
    ("continuefails", b"failed", "engine.continue_failed"),
])
def test_start_from_the_title_refusals(setup, reason, warn):
    a = Adapter2()
    g = a.E.game
    if setup == "error":
        g.phase = b"error"
    elif setup == "play":
        g.phase = b"play"
    elif setup == "newfails":
        a.E.newGameFails = True
    elif setup == "continuefails":
        a.E.slotSave(b"slot1", a.E.newSave())
        a.E.SaveData.setActiveSlot(b"crystal", b"slot1")
        a.E.continueFails = True
    assert tuple(a.eng.startFromTitle()) == (None, reason)
    if warn:
        assert ("warn", warn) in a.lines()


def test_the_story_markers():
    a = Adapter2()
    a.field()
    names = a.lua({"starter": "E26", "champion": "KB_HALL_OF_FAME", "odd": "NOT_A_FLAG"})
    assert plain(a.eng.progressMarkers(names)) == {"starter": False, "champion": False, "odd": False}
    a.E.setFlag(26)
    a.E.game.save.hallOfFame.count = 2
    assert plain(a.eng.progressMarkers(names)) == {"starter": True, "champion": True, "odd": False}


def test_markers_at_the_title_read_the_saves_flags():
    a = Adapter2()
    a.E.setFlag(26)  # no World: the save's own copy (save.events)
    assert plain(a.eng.progressMarkers(a.lua({"starter": "E26"}))) == {"starter": True}
