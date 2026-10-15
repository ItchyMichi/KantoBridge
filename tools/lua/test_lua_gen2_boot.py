"""MG9 (docs/specs/GEN2_BRIDGE_PLAN.md G2A, §5.1): the gen2 adapter's boot and identity part
(gen1-bridge/lib/engine/gen2/boot.lua) on the fake Gen 2 engine (fake_engine2.lua): the loader picks it
for both of the engine's ids, the pin and the members it reads, the Union Room guard, the save view
(event flags by number, the Hall of Fame marker, sixteen badges), the map ids as reported (J4) and the
world facade, the new game's clock (J5). Everything is made up."""

import pytest

from adapter2 import Adapter2, plain

pytestmark = pytest.mark.lua


# ------------------------------------------------------------ the loader


def test_the_loader_picks_the_gen2_adapter_for_both_engine_ids():
    a = Adapter2()
    Engine, eng = a.Engine, a.eng
    assert Engine.ADAPTERS.crystal == b"lib/engine/gen2.lua" and Engine.ADAPTERS.gs == b"lib/engine/gen2.lua"
    assert eng.hasAdapter() is True
    assert (eng.engineId(), eng.family(), eng.gameVersion()) == (b"crystal", b"gen2", b"crystal")
    assert [p.decode() for p in a.b.module("lib/engine/gen2.lua").PARTS.values()] == [
        "gen2/boot", "gen2/observe", "gen2/move", "common/slots", "gen2/saves", "common/savequeue", "gen2/region",
        "common/reliability", "gen2/ui", "gen2/story", "gen2/export", "gen2/party", "gen2/battle", "gen2/bag",
        "gen2/fieldmoves", "gen2/routes"]
    gold = Adapter2(game="gold")
    # engineId stays the stub's: the live id (Gold and Silver run the `gs` engine)
    assert (gold.eng.engineId(), gold.eng.family(), gold.eng.gameVersion()) == (b"gs", b"gen2", b"gold")


def test_the_groups_left_to_the_later_phases():
    # A group counts once any of its functions is the adapter's own (kb_engine.missingGroups). G2A writes
    # identity, observing, movement, saves, sessions, the region acts and reliability, G2B the UI, G2C the
    # story, G2D the export, G2F the party, battles and bag (whose inventoryCount the routes group shares; the
    # route end itself is G2H's).
    a = Adapter2()
    assert sorted(g.decode() for g in a.Engine.missingGroups(a.eng).values()) == []


def test_every_required_member_is_read_from_the_engine():
    a = Adapter2()
    assert list(a.eng.checkRequired().values()) == []
    a.E.World.restoreMapMusic = None
    a.E.Game2.writeSave = None
    missing = [m.decode() for m in a.eng.checkRequired().values()]
    assert missing == ["Game2:writeSave", "src.world.gen2.World.restoreMapMusic"]


def test_the_pin_and_an_unchecked_release():
    a = Adapter2()
    assert tuple(a.eng.version()) == (b"0.3.85", 2) and a.eng.checkPin() is True
    a.E.Version.engine = b"0.3.88"
    assert a.eng.checkPin() is False and ("warn", "engine.unchecked_version") in a.lines()


def test_the_union_room_guard_covers_gen2():
    # UnionCenter2F.apply widens POKECENTER_2F and adds UNION_ROOM before mods load, only with the option
    # on (src/world/gen2/UnionCenter2F.lua:222-260): the export would then disagree with the hidden game
    a = Adapter2()
    assert a.eng.unionRoomPatched() is False and a.eng.unionRoomOff() is False
    a.E.unionApplied = True
    assert a.eng.unionRoomPatched() is True
    a.E.unionApplied = False
    a.E.maps.UNION_ROOM = a.lua({"id": "UNION_ROOM"})
    assert a.eng.unionRoomPatched() is True


# ------------------------------------------------------------ the save view


def test_the_save_view_in_the_field():
    a = Adapter2()
    a.field("TEST_TOWN", 5, 6, "left")
    save = a.E.game.save
    assert a.same(a.eng.save(), save)
    for flag in (26, 27, 1600):
        a.E.setFlag(flag)
    save.player.badges = a.lua({"ZEPHYR": True, "HIVE": True})
    save.player.kantoBadges = a.lua(["BOULDER"])  # a list of names reads the same
    save.inventory = a.lua({"POTION": 3, "TM_TEST": 1, "bad id": 2, "EMPTY": 0})
    save.player.coins = 40
    view = plain(a.eng.saveView())
    assert view["player"] == {"map": "G2_TEST_TOWN", "x": 5, "y": 6, "facing": "left", "name": "TESTER"}
    assert view["flags"] == {"E26": True, "E27": True, "E1600": True}
    assert view["inventory"] == {"POTION": 3, "TM_TEST": 1, "ZEPHYRBADGE": 1, "HIVEBADGE": 1, "BOULDERBADGE": 1}
    assert (view["money"], view["coins"], view["party"], view["flashLit"]) == (3000, 40, {}, False)
    items = [i.decode() for i in a.eng.badgeItems().values()]
    assert len(items) == 16 and items[0] == "ZEPHYRBADGE" and items[7] == "RISINGBADGE"
    assert items[8] == "BOULDERBADGE" and items[15] == "EARTHBADGE"


def test_the_hall_of_fame_is_the_champion_marker():
    a = Adapter2()
    a.field()
    assert "KB_HALL_OF_FAME" not in plain(a.eng.saveView())["flags"]
    a.E.game.save.hallOfFame.count = 1
    a.eng.setGame(a.E.game)  # a new step: the view is built again
    assert plain(a.eng.saveView())["flags"]["KB_HALL_OF_FAME"] is True
    assert plain(a.eng.progressMarkers(a.lua({"starter": "E26", "champion": "KB_HALL_OF_FAME"}))) == {
        "starter": False, "champion": True}


def test_the_save_view_at_the_title_reads_the_saves_position():
    a = Adapter2()
    save = a.E.game.save
    save.position = a.lua({"map": "TEST_HOUSE", "x": 2, "y": 3, "facing": "up"})
    view = plain(a.eng.saveView())
    assert view["player"] == {"map": "G2_TEST_HOUSE", "x": 2, "y": 3, "facing": "up", "name": "TESTER"}


def test_the_save_view_is_built_once_per_step():
    a = Adapter2()
    a.field()
    v1 = a.eng.saveView()
    assert a.same(a.eng.saveView(), v1)
    a.eng.setGame(a.E.game)
    assert not a.same(a.eng.saveView(), v1)


# ------------------------------------------------------------ map ids and the world facade (J4)


def test_map_ids_go_out_prefixed_and_come_back_bare():
    a = Adapter2()
    assert a.eng.mapOf(b"PALLET_TOWN") == b"G2_PALLET_TOWN"
    assert a.eng.mapOf(b"KB_ROUTE_TEST") == b"KB_ROUTE_TEST"  # our own maps keep their names
    assert a.eng.mapOf(None) is None


def test_the_world_facade_takes_the_prefix_off_the_warps_it_is_handed():
    a = Adapter2()
    a.field()
    facade = a.eng.worldFacade(a.E.worldApi)
    assert facade.warpTo(facade, b"G2_TEST_HOUSE", 2, 2, b"up") is True
    warp = a.E.warps[len(a.E.warps)]
    assert (warp.map, warp.x, warp.y) == (b"TEST_HOUSE", 2, 2) and a.E.game.world.map.id == b"TEST_HOUSE"
    rows = a.lua([["warp", "G2_TEST_TOWN", 5, 6, "down"], ["setflag", 26]])
    assert facade.queueScript(facade, rows) is True
    queued = a.E.queued[len(a.E.queued)]
    assert queued[1][2] == b"TEST_TOWN" and queued[2][1] == b"setflag" and rows[1][2] == b"G2_TEST_TOWN"
    assert a.E.game.world.map.id == b"TEST_TOWN"
    assert plain(facade.current(facade)) == {"mapId": "G2_TEST_TOWN", "x": 5, "y": 6, "facing": "down"}
    # everything else is mod.world's own, called on it
    assert facade.setFlag(facade, 30, True) is True and a.E.game.world.events.get(a.E.game.world.events, 30) is True
    assert facade.replaceBlock(facade, 1, 2, 9) is True and a.E.replaced[1].block == 9


# ------------------------------------------------------------ the new game's clock (J5)


def test_oaks_speech_answers_the_clock_with_the_hosts_own_time():
    a = Adapter2()
    g = a.E.game
    g.newGame(g)
    speech = a.E.speechRuns[1]
    steps = [(s.id.decode(), s.kind.decode()) for s in speech.steps.values()]
    # Crystal's gender first, then the clock as a `fn` step: no clock screen (src/ui/gen2/OakSpeech.lua:255-270)
    assert steps[0] == ("gender_select", "gender") and steps[1] == ("init_clock", "fn")
    assert g.stack.top(g.stack).screenId == b"Gen2GenderSelect"
    a.press("a")
    a.tick()
    a.release("a")
    assert g.save.rtc.startMinute == 0  # a zero offset: the host's clock
    screens = []
    for _ in range(40):
        top = g.stack.top(g.stack)
        if top is None:
            break
        screens.append(top.screenId.decode() if top.screenId else "TextBox")
        button = "down" if top.screenId == b"Gen2NamePick" and top.cursor == 1 else "a"  # to the first preset
        a.press(button)
        a.tick()
        a.release(button)
        a.tick()
    assert "Gen2InitClock" not in screens and "Gen2NamePick" in screens
    assert (g.phase, g.world.map.id) == (b"play", b"TEST_HOUSE_2F")
