"""MG9 (docs/specs/GEN2_BRIDGE_PLAN.md G2A, §5.2): the gen2 adapter's observe part
(gen1-bridge/lib/engine/gen2/observe.lua) on the fake Gen 2 engine: the facts at the title, during Oak's
speech and in the field (chainsHeld: a held direction chains into the next step), what covers the field
(the shared TextBox and ChoiceBox, a Gen2<Name> screen, the park's hold as busy), and the people of the
current map. Everything is made up."""

import pytest

from adapter2 import Adapter2, plain

pytestmark = pytest.mark.lua


def test_the_title_and_a_world_being_set_up():
    a = Adapter2()
    assert a.facts() == {"inGame": False, "top": "screen", "topName": "TitleState"}
    assert a.eng.inGame() is False
    a.E.game.phase = b"play"  # a CONTINUE between the cleared stack and the new World
    a.E.game.stack.clear(a.E.game.stack)
    assert a.facts() == {"inGame": False, "top": "none", "topName": "None"}


def test_oaks_speech_is_a_new_game_starting():
    a = Adapter2()
    g = a.E.game
    g.newGame(g)
    assert a.facts() == {"inGame": False, "starting": True, "top": "screen", "topName": "OakSpeech"}
    a.press("a")  # the gender screen answered: a text box over the speech, still the intro
    a.tick()
    a.release("a")
    assert a.facts()["starting"] is True


def test_the_field_facts():
    a = Adapter2()
    a.field("TEST_TOWN", 5, 6, "down")
    f = a.facts()
    assert (f["inGame"], f["map"], f["x"], f["y"], f["facing"], f["top"], f["topName"]) == (
        True, "G2_TEST_TOWN", 5, 6, "down", "overworld", "Overworld")
    assert f["chainsHeld"] is True and not any(f[k] for k in ("moving", "ledge", "surf", "bike", "battle", "scripted",
                                                                  "transitioning", "playerScripted", "darkMap"))
    a.press("down")
    a.tick()  # facing down already: the step starts at once
    f = a.facts()
    assert (f["moving"], f["tx"], f["ty"]) == (True, 5, 7)
    w = a.E.game.world
    w.playerState = b"surf"
    w.mapSetup = a.lua({})
    w.vmRunning = True
    w.battleActive = True
    f = a.facts()
    assert (f["surf"], f["bike"], f["transitioning"], f["scripted"], f["battle"]) == (True, False, True, True, True)


def test_forced_movement_is_the_engines_own_kinds_only():
    # turningDirection is set by every ordinary held step (World.lua:10786-10789), so it alone is no forced
    # move; on ice it is the slide, and a current under the player pushes (World:step, :11512-11535)
    a = Adapter2()
    a.field("TEST_TOWN", 5, 6, "down")
    w = a.E.game.world
    w.turningDirection = b"down"
    assert a.facts().get("forcedPhase") is None
    a.E.coll(b"TEST_TOWN", 5, 6, 0x23)
    assert a.facts()["forcedPhase"] is True
    w.turningDirection = None
    assert a.facts().get("forcedPhase") is None
    a.E.coll(b"TEST_TOWN", 5, 6, 0x3b)
    assert a.facts()["forcedPhase"] is True
    a.E.coll(b"TEST_TOWN", 5, 6, 0x00)
    a.E.downhill = True
    assert a.facts()["forcedPhase"] is True


def test_a_dark_map_and_flash():
    a = Adapter2()
    a.field("TEST_CAVE", 3, 3, "up")
    f = a.facts()
    assert (f["map"], f["darkMap"], f["flashLit"]) == ("G2_TEST_CAVE", True, False)
    a.E.game.world.flashUsed = True
    assert a.facts()["flashLit"] is True
    assert [d.decode() for d in a.eng.darkMaps().values()] == ["G2_TEST_CAVE"]


@pytest.mark.parametrize("opener, top, name", [
    ("openTextBox", "screen", "TextBox"),
    ("openChoice", "screen", "ChoiceBox"),
    ("startMenu", "screen", "Gen2StartMenu"),
    ("hold", "busy", "Busy"),
])
def test_what_covers_the_field(opener, top, name):
    a = Adapter2()
    a.field()
    E = a.E
    if opener == "startMenu":
        E.pushScreen(b"Gen2StartMenu")
    elif opener == "hold":
        E.game.stack.push(E.game.stack, a.lua({"kantoBridgeRegionHold": True}))
    else:
        E[opener.encode()]()
    f = a.facts()
    assert (f["inGame"], f["top"], f["topName"]) == (True, top, name)
    assert tuple(a.eng.classifyState(E.game.stack.top(E.game.stack))) == (top.encode(), name.encode())


def test_the_people_of_the_current_map():
    a = Adapter2()
    a.field()
    E = a.E
    E.addNpc(1, a.lua({"x": 3, "y": 4, "sprite": "SPRITE_TEST_LASS"}))
    E.addNpc(2, a.lua({"x": 8, "y": 4, "sprite": "SPRITE_TEST_YOUNGSTER", "trainer": {"class": 1}, "facing": "left"}))
    E.addNpc(3, a.lua({"x": 6, "y": 2, "sprite": "SPRITE_TEST_BALL", "type": 1}))
    E.addNpc(4, a.lua({"x": 7, "y": 2, "sprite": "SPRITE_TEST_BOULDER", "movement": 0x19}))
    E.addNpc(5, a.lua({"x": 1, "y": 1, "hidden": True}))
    lst, map_id, gen = a.eng.npcs()
    people = plain(lst)
    assert map_id == b"G2_TEST_TOWN" and gen >= 1
    assert [(n["id"], n["kind"], n["sprite"], n["x"], n["y"]) for n in people] == [
        ("O1", "person", "SPRITE_TEST_LASS", 3, 4), ("O2", "trainer", "SPRITE_TEST_YOUNGSTER", 8, 4),
        ("O3", "item", "SPRITE_TEST_BALL", 6, 2), ("O4", "boulder", "SPRITE_TEST_BOULDER", 7, 2)]
    assert people[1]["facing"] == "left" and people[0]["moving"] is False
    assert a.facts()["npcs"] == 5
    # a new map: a new generation
    a.E.worldApi.warpTo(a.E.worldApi, b"TEST_HOUSE", 2, 2, b"up")
    lst2, map2, gen2 = a.eng.npcs()
    assert map2 == b"G2_TEST_HOUSE" and gen2 == gen + 1 and plain(lst2) == {}


def test_no_people_out_of_the_field():
    a = Adapter2()
    assert a.eng.npcs() is None
