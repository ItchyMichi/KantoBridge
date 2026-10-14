"""MG7 (docs/specs/GEN3_BRIDGE_PLAN.md §4.2): the game3 adapter's observe() and npcs() on the fake
Gen 3 engine, and what kb_player makes of the facts. Everything is made up."""

import pytest

from adapter3 import Adapter3, plain

pytestmark = pytest.mark.lua


def classify(a):
    return a.b.module("observe/kb_player.lua").classify(a.eng.observe()).decode()


# ------------------------------------------------------------ outside the field


def test_the_title_and_the_main_menu_are_a_screen():
    a = Adapter3()
    assert a.facts() == {"inGame": False, "top": "screen", "topName": "TitleState"}
    a.E.game.boot.phase = b"menu"
    assert a.facts() == {"inGame": False, "top": "screen", "topName": "TitleState"}
    assert classify(a) == "screen" and a.eng.inGame() is False


def test_birchs_speech_is_a_new_game_starting():
    # the boot phase "oak" (BootModules.startNewGame): no field yet, but the start took
    a = Adapter3()
    a.E.game.boot.phase = b"oak"
    assert a.facts() == {"inGame": False, "starting": True, "top": "screen", "topName": "BirchSpeech"}


def test_no_game_or_another_phase_is_none():
    a = Adapter3()
    bare = a.Engine.new(a.b.engine_opts())  # game.ready not seen yet
    assert plain(bare.observe()) == {"inGame": False, "top": "none", "topName": "None"}
    a.E.game.phase = b"quest_log"
    assert a.facts() == {"inGame": False, "top": "none", "topName": "None"}
    a.field()
    a.E.Field.running = False  # Field stopped under a field phase: not in the field
    assert a.facts()["inGame"] is False and a.eng.inGame() is False


# ------------------------------------------------------------ in the field


def test_the_idle_field_is_the_overworld():
    a = Adapter3()
    a.field("EM_TEST_TOWN", 5, 6, "left")
    a.E.addObject(1, a.lua({"x": 2, "y": 2}))
    f = a.facts()
    assert f == {"inGame": True, "chainsHeld": True, "map": "EM_TEST_TOWN", "x": 5, "y": 6, "facing": "left",
                 "moving": False, "frames": 16, "ledge": False, "surf": False, "bike": False, "runner": False,
                 "hidden": False, "playerScripted": False, "transitioning": False, "battle": False,
                 "scripted": False, "top": "overworld", "topName": "Overworld", "npcs": 1}
    assert classify(a) == "idle" and a.eng.inGame() is True


def test_a_step_in_progress():
    a = Adapter3()
    a.field("EM_TEST_TOWN", 5, 6, "down")
    a.E.startStep(b"up", 8)
    f = a.facts()
    assert (f["moving"], f["x"], f["y"], f["tx"], f["ty"], f["facing"], f["frames"]) == (True, 5, 6, 5, 5, "up", 8)
    assert classify(a) == "idle"
    a.E.land()
    f = a.facts()
    assert (f["moving"], f["x"], f["y"]) == (False, 5, 5) and "tx" not in f


def test_the_players_states():
    a = Adapter3()
    a.field()
    P = a.E.Player
    P.jumping, P.surfing, P.biking, P.running, P.visible = True, True, True, True, False
    f = a.facts()
    assert (f["ledge"], f["surf"], f["bike"], f["runner"], f["hidden"]) == (True, True, True, True, True)
    assert classify(a) == "forced"  # a ledge jump
    P.jumping = False
    P._scriptedStep = True
    assert a.facts()["playerScripted"] is True and classify(a) == "forced"
    P._scriptedStep = None
    a.E.Forced.forced = True
    assert a.facts()["forcedPhase"] is True and classify(a) == "forced"
    a.E.Forced.forced = False
    assert "forcedPhase" not in a.facts() and classify(a) == "idle"


def test_the_map_falls_back_to_the_sessions():
    a = Adapter3()
    a.field("EM_TEST_ROUTE", 1, 2)
    a.E.Map.current = None
    assert a.facts()["map"] == "EM_TEST_ROUTE"


@pytest.mark.parametrize("cover, name", [
    ("message", "TextBox"),
    ("choice", "ChoiceBox"),
    ("start", "StartMenu"),
    ("box_storage", "BoxMenu"),
    ("rse_region_map", "TownMap"),
    ("scene-kit", "SCENE_KIT"),   # a layer without a gen1 name: its id, upper-cased
    ("battle", "BattleState"),
])
def test_what_covers_the_field_is_a_screen(cover, name):
    a = Adapter3()
    a.field()
    if cover == "message":
        a.E.openMessage(b"A MADE-UP LINE.")
    elif cover == "choice":
        a.E.openChoice(b"yesno")
    elif cover == "battle":
        a.E.Battle._active = True
    else:
        a.E.pushLayer(cover.encode())
    f = a.facts()
    assert (f["inGame"], f["top"], f["topName"]) == (True, "screen", name)
    assert f["battle"] is (cover == "battle")
    assert classify(a) == "screen"


def test_a_battle_names_the_screen_before_the_hud():
    a = Adapter3()
    a.field()
    a.E.openMessage(b"A MADE-UP LINE.")
    a.E.pushLayer(b"party")
    assert a.facts()["topName"] == "PartyMenu"  # the stack layer over the message
    a.E.Battle._active = True
    assert a.facts()["topName"] == "BattleState"


def test_a_field_lock_or_a_running_script_is_scripted():
    a = Adapter3()
    a.field()
    a.E.Field.lock(b"default")
    f = a.facts()
    assert f["scripted"] is True and f["top"] == "overworld" and classify(a) == "cutscene"
    a.E.Field.unlock(b"default")
    a.E.Space.vm.running = True
    assert a.facts()["scripted"] is True
    a.E.Space.vm.running = False
    assert a.facts()["scripted"] is False


@pytest.mark.parametrize("what", ["warp", "fade", "lockInput", "callback"])
def test_a_warp_a_fade_or_a_field_callback_is_transitioning(what):
    a = Adapter3()
    a.field()
    if what == "warp":
        a.E.Warp._busy = True
    elif what == "fade":
        a.E.Fade.active = True
    elif what == "lockInput":
        a.E.Fade.lockInput = True
    else:
        a.E.Field._fieldCallback = True
    f = a.facts()
    assert f["transitioning"] is True
    if what in ("warp", "lockInput", "callback"):
        assert f["top"] == "overworld" and classify(a) == "busy"


# ------------------------------------------------------------ NPCs (world note §4)


def npc_list(a):
    got = a.eng.npcs()
    if got is None:
        return None, None, None
    raw, map_id, gen = got
    return plain(raw), map_id.decode(), gen


def objects(a):
    add = a.E.addObject
    add(1, a.lua({"x": 2, "y": 3, "gfx": 4, "facing": "left"}))                 # a person
    add(2, a.lua({"x": 4, "y": 3, "gfx": 1, "sight": 3}))                       # a trainer
    add(3, a.lua({"x": 6, "y": 3, "gfx": 3, "item": "ITEM_TEST_POTION"}))       # an item ball
    add(4, a.lua({"x": 8, "y": 3, "gfx": 2}))                                   # a boulder
    add(5, a.lua({"x": 1, "y": 1, "hidden": True}))                             # despawned
    add(6, a.lua({"x": 1, "y": 2, "invisible": True}))                          # blocks, not drawn
    add(7, a.lua({"x": 1, "y": 3, "visible": False}))
    add(127, a.lua({"x": 5, "y": 6}))                                           # the camera
    add(0xC1, a.lua({"x": 9, "y": 9}))                                          # a seam copy
    add(8, a.lua({"x": 3, "y": 8, "gfx": 77, "moving": True, "tx": 3, "ty": 9}))  # no constant name
    add(9, a.lua({"x": 7, "y": 7, "gfx": 1, "sight": 0}))                       # no sight: a person


def test_npcs_lists_the_visible_objects_as_gen1_entries():
    a = Adapter3()
    a.field("EM_TEST_TOWN", 5, 6)
    objects(a)
    raw, map_id, gen = npc_list(a)
    assert map_id == "EM_TEST_TOWN" and gen >= 1
    assert [(n["id"], n["kind"], n["sprite"]) for n in raw] == [
        ("O1", "person", "OBJ_EVENT_GFX_TEST_GIRL"),
        ("O2", "trainer", "OBJ_EVENT_GFX_TEST_HIKER"),
        ("O3", "item", "OBJ_EVENT_GFX_TEST_ITEM_BALL"),
        ("O4", "boulder", "OBJ_EVENT_GFX_TEST_BOULDER"),
        ("O8", "person", "OBJ_EVENT_GFX_77"),
        ("O9", "person", "OBJ_EVENT_GFX_TEST_HIKER"),
    ]
    first = raw[0]
    assert (first["x"], first["y"], first["facing"], first["moving"]) == (2, 3, "left", False)
    walker = raw[4]
    assert (walker["moving"], walker["tx"], walker["ty"], walker["frames"]) == (True, 3, 9, 16)
    assert a.facts()["npcs"] == 11  # every object of the table counts
    # the protocol's shape (kb_npcs.list): every entry passes, the walker at its target
    shaped, dropped = a.b.module("observe/kb_npcs.lua").list(a.eng.npcs()[0])
    assert dropped == 0 and [(n["id"], n["y"]) for n in plain(shaped)][4] == ("O8", 9)


def test_npcs_is_nothing_outside_the_field():
    a = Adapter3()
    assert a.eng.npcs() is None
    a.field()
    a.E.leaveField()
    assert a.eng.npcs() is None


def test_the_npc_generation_follows_the_object_table_and_the_map():
    a = Adapter3()
    a.field("EM_TEST_TOWN", 5, 6)
    objects(a)
    _, _, gen = npc_list(a)
    a.E.Objects._byId[1].cellX = 3  # the same table: an NPC moved
    assert npc_list(a)[2] == gen
    a.E.reloadObjects()  # Objects.loadMap: new tables
    a.E.addObject(1, a.lua({"x": 2, "y": 2}))
    raw, _, gen2 = npc_list(a)
    assert gen2 == gen + 1 and [n["id"] for n in raw] == ["O1"]
    a.E.Map.current = b"EM_TEST_ROUTE"  # a seam crossing keeps the table, not the map
    raw, map_id, gen3 = npc_list(a)
    assert (map_id, gen3) == ("EM_TEST_ROUTE", gen2 + 1)
    assert npc_list(a)[2] == gen3
