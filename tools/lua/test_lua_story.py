"""observe/kb_story.lua (M8, docs/PROTOCOL.md "Story rules (M8)") through main.lua on the simulated
overworld: the snapshot's effective toggles, blocks and lit maps; story.toggles for every change in
effective visibility (toggles, items taken, beaten statics, the engine's own objectVisible rule);
story.blocks for Cut (trees and grass), card-key doors, a curated scripted swap, regrowth on
re-entry and on a same-map reload, and unknown changes (an error code, counted); story.flags every
ten steps and when a script ends; story.lit for Flash and its reset. Plus kb_swapsites' table and
runtime check on their own. Every message the peer receives is schema-checked strictly. All maps,
blocks, objects and flags are made up (fieldsim.py)."""

import pytest

from fieldsim import FieldGame
from kblua import Bridge

pytestmark = pytest.mark.lua


def take(g, m_type):
    return [m["data"] for m in g.log if m["type"] == m_type]


def status(g, key):
    return g.status()[key]


# ------------------------------------------------------------ the snapshot


def test_snapshot_reports_effective_visibility():
    g = FieldGame(connect=False)
    s = g.E.save
    s.objectToggles = g.b.to_lua({"TEST_FOREST": {"FOREST_GUARD": True, "FOREST_BOY": True, "NO_SUCH": False}})
    s.itemsTaken = g.b.to_lua({"TEST_FOREST_obj_1": True})
    s.defeatedTrainers = g.b.to_lua({"TEST_FOREST_obj_3": True, "TEST_FOREST_obj_4": True})
    g.connect()
    snap = take(g, "snapshot")[0]
    # the guard is hidden by default and toggled on; the ball was taken; the static was beaten;
    # the boy's toggle equals his default and a beaten non-static stays visible: not listed
    assert snap["toggles"] == {"TEST_FOREST": {"FOREST_BALL": False, "FOREST_GUARD": True, "FOREST_STATIC": False}}


def test_snapshot_blocks_are_the_current_maps_differences_from_the_map_data():
    g = FieldGame(start=("TEST_LAB", 2, 3, "up"))
    snap = take(g, "snapshot")[0]
    # the card-key door ships open (8) and the map load stamped it shut (7)
    assert snap["blocks"] == [{"map": "TEST_LAB", "bx": 1, "by": 0, "block": 7}]
    assert snap["lit"] == []
    assert status(g, "storyUnknownBlocks") == 0


def test_snapshot_lit_and_state_on_a_dark_map():
    g = FieldGame(start=("VICTORY_ROAD_1F", 2, 2, "down"), connect=False)
    g.E.save.flashLit = True
    g.E.setMapSim(b"VICTORY_ROAD_1F", 2, 2, b"down")
    g.connect()
    snap = take(g, "snapshot")[0]
    assert snap["lit"] == ["VICTORY_ROAD_1F"]
    assert snap["state"]["dark"] is True and snap["state"]["lit"] is True


def test_the_map_data_comes_from_the_dataset_view_without_a_boot_copy():
    g = FieldGame(start=("TEST_LAB", 2, 3, "up"), capture=False)
    assert take(g, "snapshot")[0]["blocks"] == [{"map": "TEST_LAB", "bx": 1, "by": 0, "block": 7}]
    assert g.E.datasetReads > 0
    assert status(g, "storyPristine") is False


# ------------------------------------------------------------ story.toggles


def test_story_toggles_report_every_change_in_effective_visibility():
    g = FieldGame()
    s, b = g.E.save, g.b
    s.itemsTaken = b.to_lua({"TEST_FOREST_obj_1": True})          # the ball taken
    g.run(10)
    assert take(g, "story.toggles") == [{"map": "TEST_FOREST", "obj": "FOREST_BALL", "visible": False}]
    s.objectToggles = b.to_lua({"TEST_FOREST": {"FOREST_GUARD": True}})  # shown
    s.defeatedTrainers[b"TEST_FOREST_obj_3"] = True                     # the static beaten
    g.run(10)
    assert take(g, "story.toggles")[1:] == [
        {"map": "TEST_FOREST", "obj": "FOREST_GUARD", "visible": True},
        {"map": "TEST_FOREST", "obj": "FOREST_STATIC", "visible": False},
    ]
    s.objectToggles.TEST_FOREST.FOREST_GUARD = False                     # back to its default
    g.run(10)
    assert take(g, "story.toggles")[3:] == [{"map": "TEST_FOREST", "obj": "FOREST_GUARD", "visible": False}]
    g.run(30)
    assert len(take(g, "story.toggles")) == 4, "nothing more without a change"


def test_an_item_taken_wins_over_its_toggle_as_in_the_engine():
    g = FieldGame()
    s, b = g.E.save, g.b
    s.objectToggles = b.to_lua({"TEST_FOREST": {"FOREST_BALL": True}})
    g.run(10)
    assert take(g, "story.toggles") == []                 # its default anyway
    s.itemsTaken = b.to_lua({"TEST_FOREST_obj_1": True})
    g.run(10)
    assert take(g, "story.toggles") == [{"map": "TEST_FOREST", "obj": "FOREST_BALL", "visible": False}]


def test_toggles_use_the_engines_object_visible():
    g = FieldGame(connect=False)
    calls = []
    oc = g.E.modules[b"src.world.OverworldController"]
    real = oc.objectVisible
    oc.objectVisible = g.b.eval("function(real, py) return function(s, m, o) py(m) return real(s, m, o) end end")(
        real, lambda m: calls.append(m))
    g.E.save.itemsTaken = g.b.to_lua({"TEST_FOREST_obj_1": True})
    g.connect()
    assert calls == [b"TEST_FOREST"]
    assert take(g, "snapshot")[0]["toggles"] == {"TEST_FOREST": {"FOREST_BALL": False}}


# ------------------------------------------------------------ story.blocks


def test_a_cut_tree_is_reported_and_grows_back_on_re_entry():
    g = FieldGame()
    g.give_moves("CUT")
    g.give("CASCADEBADGE")
    assert g.field("cut") == {"ok": True, "kind": "cut"}
    g.read_texts()
    assert take(g, "story.blocks") == [{"map": "TEST_FOREST", "changes": [{"bx": 1, "by": 0, "block": 3}]}]
    assert g.E.ow.map.isWalkableCell(g.E.ow.map, 2, 1)
    # out to TEST_LAB and back: the lab's stamped door, then nothing for the forest (the tree grew
    # back, which is the map data again: a client drops a map's live blocks when it leaves it)
    for d in ("right", "right", "down", "down", "down", "down", "down"):
        g.answer(g.step(d))
    g.settle()
    assert g.cell()[0] == "TEST_LAB"
    assert take(g, "story.blocks")[1:] == [{"map": "TEST_LAB", "changes": [{"bx": 1, "by": 0, "block": 7}]}]
    for d in ("down",):
        g.answer(g.step(d))
    g.settle()
    assert g.cell()[0] == "TEST_FOREST"
    assert g.block(1, 0) == 2, "regrown"
    assert len(take(g, "story.blocks")) == 2
    assert status(g, "storyUnknownBlocks") == 0


def test_a_same_map_reload_reports_the_regrown_tree():
    g = FieldGame()
    g.give_moves("CUT")
    g.give("CASCADEBADGE")
    g.field("cut")
    g.read_texts()
    # (5, 7) warps back onto TEST_FOREST itself: setMap regrows the tree
    for d in ("right", "right", "right", "down", "down", "down", "down", "down"):
        g.answer(g.step(d))
    g.settle()
    assert g.cell()[:3] == ("TEST_FOREST", 2, 3)
    assert take(g, "story.blocks")[-1] == {"map": "TEST_FOREST", "changes": [{"bx": 1, "by": 0, "block": 2}]}
    assert status(g, "storyUnknownBlocks") == 0


def test_cut_grass_is_a_known_change():
    g = FieldGame(start=("TEST_FOREST", 6, 2, "up"))
    g.give_moves("CUT")
    g.give("CASCADEBADGE")
    assert g.field("cut") == {"ok": True, "kind": "cut"}
    g.read_texts()
    assert take(g, "story.blocks") == [{"map": "TEST_FOREST", "changes": [{"bx": 3, "by": 0, "block": 5}]}]
    assert status(g, "storyUnknownBlocks") == 0


def test_a_card_key_door_opening_is_reported():
    g = FieldGame(start=("TEST_LAB", 2, 2, "up"))
    g.E.openCardKeyDoor(1, 0, 8)
    g.run(2)
    assert take(g, "story.blocks") == [{"map": "TEST_LAB", "changes": [{"bx": 1, "by": 0, "block": 8}]}]
    assert status(g, "storyUnknownBlocks") == 0
    # re-entering after the event: the door stays open, which is the map data
    g.E.scriptWarp(b"TEST_FOREST", 4, 6)
    g.settle()
    g.E.scriptWarp(b"TEST_LAB", 2, 4)
    g.settle()
    assert len(take(g, "story.blocks")) == 1


def test_a_curated_scripted_swap_is_reported_on_entry_and_when_it_changes():
    g = FieldGame()
    s = g.E.save
    g.E.mapScripts[b"VICTORY_ROAD_1F"] = g.b.eval("""function(s) return { onEnter = function(E, ow)
        ow:replaceBlock(4, 6, s.flags.EVENT_VICTORY_ROAD_1_BOULDER_ON_SWITCH and 0x1D or 0x25)
      end } end""")(s)
    s.flags.EVENT_VICTORY_ROAD_1_BOULDER_ON_SWITCH = True
    g.E.scriptWarp(b"VICTORY_ROAD_1F", 2, 2)
    g.settle()
    assert take(g, "story.blocks") == [{"map": "VICTORY_ROAD_1F", "changes": [{"bx": 4, "by": 6, "block": 0x1D}]}]
    g.E.ow.replaceBlock(g.E.ow, 4, 6, 0x25)   # the barrier back (a site's closed state)
    g.run(2)
    assert take(g, "story.blocks")[1] == {"map": "VICTORY_ROAD_1F", "changes": [{"bx": 4, "by": 6, "block": 0x25}]}
    assert status(g, "storyUnknownBlocks") == 0


def test_an_unknown_block_change_is_still_reported_and_logged_as_an_error():
    g = FieldGame()
    g.E.ow.replaceBlock(g.E.ow, 2, 2, 9)      # no site, no cut: an unknown change
    g.run(2)
    assert take(g, "story.blocks") == [{"map": "TEST_FOREST", "changes": [{"bx": 2, "by": 2, "block": 9}]}]
    assert status(g, "storyUnknownBlocks") == 1
    errors = [line for line in g.rig.logs() if "story.unknown_block" in line]
    assert errors == ["[error] [gen1-bridge] story.unknown_block count=1 map=TEST_FOREST total=1"]
    logs = take(g, "log")
    assert any(m["code"] == "story.unknown_block" and m["level"] == "error" for m in logs)


def test_a_hot_reload_keeps_the_story_going():
    g = FieldGame()
    g.b.reload()          # F5: a new instance copies the map data at its own mods.loaded
    g.run(2)
    g.connect()
    g.give_moves("CUT")
    g.give("CASCADEBADGE")
    assert g.field("cut") == {"ok": True, "kind": "cut"}
    g.read_texts()
    assert take(g, "story.blocks") == [{"map": "TEST_FOREST", "changes": [{"bx": 1, "by": 0, "block": 3}]}]
    assert status(g, "storyPristine") is True and status(g, "storyUnknownBlocks") == 0


def test_no_story_without_a_live_client():
    g = FieldGame(connect=False)
    g.E.ow.replaceBlock(g.E.ow, 2, 2, 9)
    g.E.save.flags[b"EVENT_TEST_NEW"] = True
    g.run(20)
    g.connect()
    snap = take(g, "snapshot")[0]
    assert {"map": "TEST_FOREST", "bx": 2, "by": 2, "block": 9} in snap["blocks"]
    assert "EVENT_TEST_NEW" in snap["flags"]
    g.run(20)
    assert take(g, "story.blocks") == [] and take(g, "story.flags") == []


# ------------------------------------------------------------ story.flags


def test_flags_are_diffed_every_ten_steps():
    g = FieldGame()
    g.run(10)
    assert take(g, "story.flags") == []
    g.E.save.flags[b"EVENT_TEST_NEW"] = True
    g.E.save.flags[b"EVENT_TEST_ONE"] = None
    got = []
    for i in range(10):
        got = [m for m in g.run() if m["type"] == "story.flags"]
        if got:
            break
    assert got and got[0]["data"] == {"set": ["EVENT_TEST_NEW"], "clear": ["EVENT_TEST_ONE"]}
    assert i < 10
    g.run(30)
    assert len(take(g, "story.flags")) == 1


def test_flags_are_diffed_the_step_a_script_ends():
    g = FieldGame()
    g.run(3)  # off the ten-step beat
    runner = g.E.ow.runner
    runner.running = True
    g.run(1)
    g.E.save.flags[b"EVENT_TEST_SCRIPT"] = True
    runner.running = False
    out = g.run(1)
    assert [m["data"] for m in out if m["type"] == "story.flags"] == [{"set": ["EVENT_TEST_SCRIPT"], "clear": []}]


# ------------------------------------------------------------ story.lit


def test_flash_lights_the_dark_maps_and_leaving_resets_them():
    g = FieldGame(start=("VICTORY_ROAD_1F", 2, 2, "down"))
    g.give_moves("FLASH")
    g.give("BOULDERBADGE")
    states = take(g, "snapshot")[0]["state"]
    assert states["dark"] is True and states["lit"] is False
    assert g.field("flash") == {"ok": True, "kind": "flash"}
    g.read_texts()
    assert take(g, "story.lit") == [{"map": "VICTORY_ROAD_1F", "lit": True}]
    assert take(g, "player.state")[-1]["lit"] is True
    assert g.field("flash")["reason"] == "not_here", "lit already: no longer dark"
    g.E.scriptWarp(b"TEST_FOREST", 2, 3)
    g.settle()
    assert take(g, "story.lit")[1:] == [{"map": "VICTORY_ROAD_1F", "lit": False}]
    assert take(g, "player.state")[-1]["dark"] is False


# ------------------------------------------------------------ kb_swapsites


@pytest.fixture(scope="module")
def sites():
    b = Bridge()
    return b, b.module("export/kb_swapsites.lua")


def test_the_curated_rows_are_well_formed(sites):
    b, S = sites
    rows = S.CURATED
    assert len(rows) == 4 + 3 + 2 + 13 + 6
    for i in range(1, len(rows) + 1):
        r = rows[i]
        site = S.site(r[1], r[2], r[3], r[4], r[5], r[6], r[7])
        assert site is not None and not isinstance(site, tuple), i
    kinds = {rows[i][4].decode() for i in range(1, len(rows) + 1)}
    assert kinds == {"boulder", "script"}


def test_site_refuses_a_swap_that_changes_nothing(sites):
    b, S = sites
    assert S.site(b"TEST_MAP", 1, 1, b"script", 4, 4) == (None, b"swap_no_change")
    assert S.site(b"TEST_MAP", 1, 1, b"weird", 4, 5) == (None, b"swap_bad_site")


def test_script_sites_read_the_ships_blocks_from_the_map(sites):
    b, S = sites
    rows = S.scriptSites(b.eval("function(m, x, y) if m == 'VERMILION_DOCK' then return 40 + x end end"))
    ship = [rows[i] for i in range(1, len(rows) + 1) if rows[i][1] == b"VERMILION_DOCK"]
    assert len(ship) == 8
    assert {(r[2], r[3], r[5], r[6]) for r in ship} == {
        (x, y, 40 + x, 1 if y == 1 else 13) for x in range(5, 9) for y in (1, 2)}
    assert all(r[7] == b"EVENT_SS_ANNE_LEFT" for r in ship)
    none = S.scriptSites(b.eval("function() return nil end"))
    assert len(none) == len(S.CURATED), "no dock, no ship rows"


def test_the_runtime_index_allows_each_sites_states(sites):
    b, S = sites
    field = b.to_lua({
        "cardKeyDoors": {"maps": ["TEST_LAB", "SILPH_CO_11F"], "openBlock": 9, "silphCo11F": {"openBlock": 11},
                         "closedDoors": {"TEST_LAB": [{"bx": 1, "by": 0, "block": 7, "open": 8, "event": "E1"}],
                                         "SILPH_CO_11F": [{"bx": 2, "by": 3, "block": 5, "open": 6, "event": "E2"}],
                                         "TEST_HIDEOUT": [{"bx": 4, "by": 4, "block": 3, "open": 2, "event": "E3"}]}},
        "hiddenExtras": {"trashCans": {"map": "TEST_GYM", "doorBlock": {"bx": 2, "by": 2, "block": 5}}},
    })
    index, n = S.runtimeIndex(field, b"blue", b.eval("function() return nil end"))
    assert n == 3 + 1 + len(S.CURATED)
    allows = lambda m, x, y, blk: bool(S.allows(index, m, x, y, blk))
    assert allows(b"TEST_LAB", 1, 0, 7) and allows(b"TEST_LAB", 1, 0, 8) and allows(b"TEST_LAB", 1, 0, 9)
    assert not allows(b"TEST_LAB", 1, 0, 10) and not allows(b"TEST_LAB", 0, 0, 7)
    assert allows(b"SILPH_CO_11F", 2, 3, 11) and not allows(b"SILPH_CO_11F", 2, 3, 9)
    assert allows(b"TEST_HIDEOUT", 4, 4, 3) and not allows(b"TEST_HIDEOUT", 4, 4, 9), "a lift gate is no card-key door"
    assert allows(b"TEST_GYM", 2, 2, 0x24) and allows(b"TEST_GYM", 2, 2, 5)
    assert allows(b"POKEMON_MANSION_2F", 9, 4, 0x54) and allows(b"POKEMON_MANSION_2F", 9, 4, 0x0e)
