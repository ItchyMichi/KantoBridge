"""MG7 (docs/specs/GEN3_BRIDGE_PLAN.md §2, §4.1, §4.7): the game3 adapter's loader, identity and
save view on the fake Gen 3 engine (fake_engine3.lua). Everything is made up."""

import pytest

from adapter3 import Adapter3, plain

pytestmark = pytest.mark.lua


# ------------------------------------------------------------ the loader


def test_the_loader_picks_the_game3_adapter_grown_from_the_stub():
    a = Adapter3()
    Engine, eng = a.Engine, a.eng
    assert Engine.ADAPTERS.game3 == b"lib/engine/game3.lua"
    assert eng.hasAdapter() is True
    assert (eng.engineId(), eng.family(), eng.gameVersion()) == (b"game3", b"gen3", b"emerald")
    assert [p.decode() for p in a.b.module("lib/engine/game3.lua").PARTS.values()] == [
        "boot", "observe", "move", "saves", "region", "reliability", "ui", "story", "export", "party", "battle", "bag",
        "routes"]


def test_missing_groups_name_the_groups_no_part_has_written():
    # A group counts once any of its functions is the adapter's own (kb_engine.missingGroups). Since G3F every
    # group has a part: the UI came with G3B, the story with G3C, the export with G3D, the party, battles, items and
    # routes with G3F, trades and the daycare with the follow-up's item 7. Gen 1's own seams (direct battles, the
    # animated tiles) stay the stub's.
    a = Adapter3()
    missing = [g.decode() for g in a.Engine.missingGroups(a.eng).values()]
    assert missing == []
    stubbed = {k.decode() for k in a.eng._stubbed.keys()}
    for name in ("bridgeToken", "nextSerial", "scriptCommand", "inGame", "observe", "takeVerdicts", "holdAway",
                 "metaOf", "playthrough", "saveView", "uiStack", "uiSetGlyphs", "screenSize", "bagCounts", "gifts",
                 "partyNicks", "takeHeals", "releaseCatches", "applyParty", "battleFinish", "blackout", "ops",
                 "opSent", "opSynced", "opDone", "excludedUids", "daycareStep", "daycareUid", "daycareUids"):
        assert name not in stubbed, name
    for name in ("animatedTiles", "worldTalk"):
        assert name in stubbed, name


def test_the_stubbed_functions_answer_no_game_with_the_right_types():
    # GEN3_BRIDGE_PLAN §2: what the core calls unguarded every step never throws
    a = Adapter3()
    a.field()
    eng = a.eng
    # G3B's UI part answers with the overworld alone (nothing open)
    assert [e["cls"] for e in plain(eng.uiStack())] == ["Overworld"]
    assert plain(eng.ops()) == {} and plain(eng.gifts()) == {}
    assert plain(eng.partyNicks()[0]) == {} and plain(eng.bagCounts()) == {}
    toggles, skipped = eng.storyToggles()  # G3C: no object hidden by its flag
    assert plain(toggles) == {} and skipped == 0
    assert eng.takeHeals() == 0 and eng.releaseCatches() == 0 and eng.inventoryCount() == 0
    assert eng.pcHeld() is False and eng.pcPending() is None and eng.noSuchFunction() is None
    nxt = a.b.eval("function(...) return select('#', ...), ... end")
    assert tuple(eng.encounterRoll(nxt, b"def", b"ctx")) == (2, b"def", b"ctx")
    assert tuple(eng.worldTalk(nxt, b"game", b"eo")) == (2, b"game", b"eo")


# ------------------------------------------------------------ identity and the pin


def test_version_and_pin():
    a = Adapter3()
    assert tuple(a.eng.version()) == (b"0.3.85", 2)
    assert a.eng.checkPin() is True and a.lines() == []
    a.E.Version.engine = b"0.0.0-dev+abc123"  # a source checkout counts as the pin
    assert a.eng.checkPin() is True
    a.E.Version.engine = b"0.4.1"
    assert a.eng.checkPin() is False
    assert a.lines() == [("warn", "engine.unchecked_version")]
    assert a.eng.unionRoomPatched() is False and a.eng.unionRoomOff() is False


def test_check_required_finds_everything_on_the_fake():
    a = Adapter3()
    assert plain(a.eng.checkRequired()) == {}


def test_check_required_names_a_missing_member_module_and_game_method():
    a = Adapter3(modules={"src.core.game3.audio": "false"})  # require hands back no table
    a.E.Warp.isBusy = None
    a.E.Field.callbackPending = None
    a.E.Game3.quickSaveAllowed = None
    assert plain(a.eng.checkRequired()) == ["Game3:quickSaveAllowed", "src.core.game3.audio",
                                            "src.core.game3.field.callbackPending", "src.core.game3.warp.isBusy"]


def test_set_game_counts_logic_steps_and_take_over_closes_the_previous_instance():
    a = Adapter3()
    assert a.eng.game() is not None and a.eng.logicStep() == 1  # Adapter3 set the game once
    a.eng.setGame(None)  # a step without a payload keeps the game
    assert a.eng.logicStep() == 2 and a.same(a.eng.game(), a.E.game)
    closed = a.b.eval("{ n = 0 }")
    close_a = a.b.eval("function(c) return function() c.n = c.n + 1 end end")(closed)
    assert a.eng.takeOver(close_a) is False
    second = a.new_instance()
    assert second.takeOver(a.b.eval("function() end")) is True and closed.n == 1


# ------------------------------------------------------------ the save view (§4.7)


def test_no_save_view_at_the_title():
    a = Adapter3()
    assert a.eng.save() is None and a.eng.saveView() is None


def test_save_is_the_live_session_and_the_view_is_gen1_shaped():
    a = Adapter3()
    s = a.field("EM_TEST_HOUSE", 3, 4, "up")
    s.money, s.coins, s.name = 4321, 12, b"MAY"
    s.meta = a.lua({"savedAt": 1700000100, "sessionStart": 1700000000, "engine": "0.3.85"})
    assert a.same(a.eng.save(), s)
    v = plain(a.eng.saveView())
    assert v["player"] == {"map": "EM_TEST_HOUSE", "x": 3, "y": 4, "facing": "up", "name": "MAY"}
    assert (v["money"], v["coins"], v["version"]) == (4321, 12, "emerald")
    assert v["party"] == {} and v["pokedex"] == {"seen": {}, "owned": {}} and v["flashLit"] is False
    assert v["options"] == {"textSpeed": "fast"} and v["meta"]["savedAt"] == 1700000100


def test_the_view_names_set_flags_by_their_emerald_names():
    a = Adapter3()
    a.field()
    a.E.setFlag(b"FLAG_TEST_ONE")                       # number and numeric-string keys
    flags = a.E.Space.store.flags
    flags[b"0x102"] = True                              # a hex key (FLAG_TEST_TWO)
    flags[b"FLAG_TEST_THREE"] = True                    # a name key
    flags[0x214] = False                                # cleared (FLAG_SYS_GAME_CLEAR)
    flags[0x999] = True                                 # no Emerald name
    a.E.setFlag(b"FLAG_SYS_POKEMON_GET")
    assert set(plain(a.eng.saveView())["flags"]) == {"FLAG_TEST_ONE", "FLAG_TEST_TWO", "FLAG_TEST_THREE",
                                                       "FLAG_SYS_POKEMON_GET"}
    assert all(v is True for v in plain(a.eng.saveView())["flags"].values())


def test_the_view_falls_back_to_the_sessions_flags_without_a_live_store():
    a = Adapter3()
    s = a.field()
    s.flags[0x101] = True  # the session's copy (stale between saves, space.lua:111-113)
    a.E.Space.store = None
    a.eng.setGame(None)
    assert plain(a.eng.saveView())["flags"] == {"FLAG_TEST_ONE": True}


def test_the_inventory_strips_item_and_carries_the_set_badges():
    a = Adapter3()
    s = a.field()
    pockets = s.bag.pockets
    pockets.ITEMS = a.lua([{"id": 2, "qty": 3}, {"id": 2, "qty": 1}, {"id": 99, "qty": 1}, {"id": 4, "qty": 2},
                           {"id": 1, "qty": 0}])
    pockets.POKE_BALLS = a.lua([{"id": 1, "qty": 5}])
    pockets.KEY_ITEMS = a.lua([{"id": 3, "qty": 1}])
    a.E.setFlag(b"FLAG_BADGE01_GET")
    a.E.setFlag(b"FLAG_BADGE03_GET")
    inv = plain(a.eng.saveView())["inventory"]
    # two stacks add up; an unknown id, a lower-case constant and an empty stack are left out
    assert inv == {"TEST_POTION": 4, "TEST_BALL": 5, "TEST_KEY": 1, "STONEBADGE": 1, "DYNAMOBADGE": 1}


def test_badge_items_come_from_the_flag_tables_with_the_fallback():
    a = Adapter3()
    a.field()
    names = ["STONE", "KNUCKLE", "DYNAMO", "HEAT", "BALANCE", "FEATHER", "MIND", "RAIN"]
    assert plain(a.eng.badgeItems()) == [n + "BADGE" for n in names]
    a.E.flagTables.BADGES[1].name = b"TESTSTONE"  # the table's own names win
    assert plain(a.eng.badgeItems())[0] == "TESTSTONEBADGE"
    a.E.flagTables.BADGES[8] = None  # not Emerald's eight: the built-in names
    assert plain(a.eng.badgeItems()) == [n + "BADGE" for n in names]
    assert plain(a.eng.darkMaps()) == {}


def test_dark_maps_are_the_caves_that_need_flash_and_flash_lights_them():
    """Item 8: darkMaps lists the maps whose header has the cave flag (FieldView's mapIsCave); the view's flashLit
    and the facts follow FLAG_SYS_USE_FLASH (FieldView's flashActive), the facts' darkMap the current map."""
    a = Adapter3()
    a.E.game.data = a.lua({"maps": {"EM_TEST_CAVE": {"id": "EM_TEST_CAVE", "cave": 1},
                                    "EM_TEST_DEEP": {"id": "EM_TEST_DEEP", "cave": 1},
                                    "EM_TEST_TOWN": {"id": "EM_TEST_TOWN", "cave": 0},
                                    "EM_TEST_HOUSE": {"id": "EM_TEST_HOUSE"}}})
    assert plain(a.eng.darkMaps()) == ["EM_TEST_CAVE", "EM_TEST_DEEP"]
    a.field("EM_TEST_CAVE", 3, 4, "up")
    f = a.facts()
    assert (f["darkMap"], f["flashLit"]) == (True, False)
    assert plain(a.eng.saveView())["flashLit"] is False
    a.E.setFlag(b"FLAG_SYS_USE_FLASH")
    a.eng.setGame(a.E.game)  # the next logic step
    assert plain(a.eng.saveView())["flashLit"] is True
    f = a.facts()
    assert (f["darkMap"], f["flashLit"]) == (True, True)
    state = plain(a.b.module("observe/kb_player.lua").state(a.eng.observe()))
    assert (state["dark"], state["lit"]) == (True, True)
    a.E.leaveField()
    a.field("EM_TEST_TOWN")
    assert a.facts()["darkMap"] is False


def test_the_view_is_built_once_per_logic_step():
    a = Adapter3()
    a.field()
    first = a.eng.saveView()
    a.E.setFlag(b"FLAG_TEST_ONE")
    assert a.same(a.eng.saveView(), first)  # the same step: the same view
    a.eng.setGame(a.E.game)  # the next logic step
    again = a.eng.saveView()
    assert not a.same(again, first) and plain(again)["flags"] == {"FLAG_TEST_ONE": True}
    a.E.leaveField()  # a new session is a new view at once
    a.field()
    assert not a.same(a.eng.saveView(), again)
