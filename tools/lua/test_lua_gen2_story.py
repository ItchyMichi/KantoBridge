"""MG9 G2C (docs/specs/GEN2_BRIDGE_PLAN.md §5.6): the Gen 2 adapter's story part (gen1-bridge/lib/engine/gen2/
story.lua) on the fake Gen 2 engine: the map data copied at mods.loaded, the current map's blocks and their load
serial, this visit's Cut and Whirlpool edits, who stands where (the flag and the hour window off the current map,
the live masks on it), and the swap sites (Cut trees, Whirlpools, the blocks the scripts a map reaches change,
Crystal's whole-map swap, the bedroom's decorations). Map ids leave as G2_* (J4). Everything is made up (TEST_*
maps, blocks, flags, scripts, decorations)."""

import pytest

from adapter2 import Adapter2, plain

pytestmark = pytest.mark.lua


def captured(a):
    """The map data copied as main.lua's mods.loaded handler copies it."""
    return a.eng.capturePristine(a.E.data)


def sites(a):
    out = plain(a.eng.swapSites())
    return [(s["map"], s["bx"], s["by"], s["kind"], s["before"], s["after"]) for s in out]


def set_block(a, map_id, bx, by, block):
    m = a.E.maps[map_id.encode()]
    m.blocks[by * m.width + bx + 1] = block


def toggles(a):
    out, skipped = a.eng.storyToggles()
    return plain(out), skipped


# ------------------------------------------------------------ the map data


def test_the_story_group_is_the_adapters_own():
    a = Adapter2()
    missing = sorted(g.decode() for g in a.Engine.missingGroups(a.eng).values())
    assert "story" not in missing and missing == ["battles", "routes"]


def test_the_map_data_is_copied_at_mods_loaded_and_read_by_the_reported_id():
    a = Adapter2()
    set_block(a, "TEST_TOWN", 2, 1, 7)
    assert a.eng.pristineCaptured() is False
    assert captured(a) == 5
    assert a.eng.pristineCaptured() is True
    blocks, width = a.eng.pristineBlocks(b"G2_TEST_TOWN")
    assert width == 10 and len(blocks) == 100 and blocks[1 * 10 + 2 + 1] == 7
    assert a.eng.pristineBlockAt(b"G2_TEST_TOWN", 2, 1) == 7
    assert a.eng.pristineBlockAt(b"G2_TEST_TOWN", 10, 0) is None, "off the map"
    assert tuple(a.eng.pristineBlocks(b"G2_NO_SUCH_MAP")) == (None, None)
    # the copy is the boot's: a later edit of the shared record (a dropped World's) doesn't reach it
    set_block(a, "TEST_TOWN", 2, 1, 9)
    assert a.eng.pristineBlockAt(b"G2_TEST_TOWN", 2, 1) == 7


def test_without_a_boot_copy_and_no_dataset_view_there_is_no_map_data():
    a = Adapter2()
    assert tuple(a.eng.pristineBlocks(b"G2_TEST_TOWN")) == (None, None)
    assert a.eng.pristineCaptured() is False


# ------------------------------------------------------------ the current map's blocks


def test_the_story_map_has_the_reported_id_and_a_serial_per_load():
    a = Adapter2()
    assert a.eng.storyMap() is None, "nothing out of the field"
    w = a.field()
    sm = plain(a.eng.storyMap())
    assert (sm["map"], sm["width"], sm["height"]) == ("G2_TEST_TOWN", 10, 10)
    assert plain(a.eng.storyMap())["load"] == sm["load"], "the same load"
    w.loadMap(w, b"TEST_TOWN", 5, 6, b"down", b"warp")   # a same-map reload is a new load
    assert plain(a.eng.storyMap())["load"] == sm["load"] + 1


def test_live_blocks_and_their_changes():
    a = Adapter2()
    w = a.field()
    known = a.eng.liveBlocks()
    assert len(known) == 100 and a.eng.liveBlockChanges(known) is None
    w.changeBlock(w, 3, 2, 0x22)
    changes = plain(a.eng.liveBlockChanges(known))
    assert changes == [[2 * 10 + 3 + 1, 0x22]]


def test_this_visits_cut_and_whirlpool_edits_are_the_worlds_own_record():
    a = Adapter2()
    for bx, block in ((1, 0x40), (2, 0x50), (3, 0x60), (4, 0x11)):
        set_block(a, "TEST_TOWN", bx, 0, block)
    w = a.field()
    for bx, block in ((1, 0x41), (2, 0x51), (3, 0x61), (4, 0x12)):
        w.changeBlock(w, bx, 0, block)
    # the tree, the grass and the whirlpool; a script's edit of a block no row swaps is not one
    assert plain(a.eng.cutRecords(b"G2_TEST_TOWN")) == [{"bx": 1, "by": 0, "block": 0x40},
                                                       {"bx": 2, "by": 0, "block": 0x50},
                                                       {"bx": 3, "by": 0, "block": 0x60}]
    assert len(a.eng.cutRecords(b"G2_TEST_HOUSE")) == 0, "another map"
    assert plain(a.eng.cutSwaps()) == {0x40: 0x41, 0x50: 0x51, 0x60: 0x61}
    # the next load puts the originals back, and the record goes with them
    w.loadMap(w, b"TEST_HOUSE", 2, 2, b"down", b"warp")
    w.loadMap(w, b"TEST_TOWN", 5, 6, b"down", b"warp")
    assert len(a.eng.cutRecords(b"G2_TEST_TOWN")) == 0
    assert a.E.maps[b"TEST_TOWN"].blocks[2] == 0x40


# ------------------------------------------------------------ who stands where


def objects(a):
    """TEST_TOWN's people: O1 always there, O2 hidden by flag 40, O3 out at night only (hours 18-3), O4 an
    item ball whose flag 41 is set, O5 out only in the day's time-of-day bit (-1, 2); TEST_HOUSE's O1 behind
    flag 42 (set)."""
    E = a.E
    E.addNpc(1, a.lua({"map": "TEST_TOWN", "x": 1, "y": 1}))
    E.addNpc(2, a.lua({"map": "TEST_TOWN", "x": 2, "y": 1, "eventFlag": 40}))
    E.addNpc(3, a.lua({"map": "TEST_TOWN", "x": 3, "y": 1, "hours": [18, 3]}))
    E.addNpc(4, a.lua({"map": "TEST_TOWN", "x": 4, "y": 1, "eventFlag": 41, "type": 1}))
    E.addNpc(5, a.lua({"map": "TEST_TOWN", "x": 5, "y": 1, "hours": [-1, 2]}))
    E.addNpc(1, a.lua({"map": "TEST_HOUSE", "x": 1, "y": 1, "eventFlag": 42}))


def test_toggles_off_the_current_map_follow_the_flag_and_the_hour():
    a = Adapter2()
    objects(a)
    a.E.setFlag(41)
    a.E.setFlag(42)
    w = a.field("TEST_HOUSE", 2, 2)
    out, skipped = toggles(a)
    # noon: the night person is away; flag 41 hides the ball; flag 40 is clear; O1 and O5 stand
    assert out == {"G2_TEST_TOWN": {"O3": False, "O4": False}, "G2_TEST_HOUSE": {"O1": False}} and skipped == 0
    w.clockHour = 20
    assert toggles(a)[0]["G2_TEST_TOWN"] == {"O4": False, "O5": False}, "night: the day-bit person is away"


def test_toggles_on_the_current_map_are_the_live_masks():
    a = Adapter2()
    objects(a)
    w = a.field("TEST_TOWN", 5, 6)
    assert toggles(a)[0] == {"G2_TEST_TOWN": {"O3": False}}
    # a plain setevent moves nobody until the next load (World:loadObjectMasks)
    w.events.set(w.events, 40, True)
    assert toggles(a)[0] == {"G2_TEST_TOWN": {"O3": False}}
    w.loadMap(w, b"TEST_TOWN", 5, 6, b"down", b"warp")
    assert toggles(a)[0] == {"G2_TEST_TOWN": {"O2": False, "O3": False}}
    # appear (objectId = index + 1) clears the flag and the mask; disappear sets both
    w.appearObject(w, 3)
    w.disappearObject(w, 2)
    assert toggles(a)[0] == {"G2_TEST_TOWN": {"O1": False, "O3": False}}
    assert w.events.get(w.events, 40) is False
    # an unscripted mask the hour poll would re-derive keeps its value meanwhile
    w.clockHour = 20
    assert toggles(a)[0] == {"G2_TEST_TOWN": {"O1": False, "O3": False}}


def test_no_toggles_out_of_the_field():
    a = Adapter2()
    objects(a)
    assert toggles(a) == ({}, 0)


# ------------------------------------------------------------ swap sites


def scripted(a):
    """TEST_TOWN's TILES callback runs 1:4000: changeblock (4, 2) -> 0x22 unless flag 30, else 1:4010's
    (6, 6) -> 0x23 by its args; one changeblock writes the map's own block (no change); 1:4020 is reached by no
    map. TEST_HOUSE has a coord event whose script swaps the whole map from TEST_CAVE's blockdata."""
    E = a.E
    E.scripts[b"1:4000"] = a.lua([{"op": "checkevent", "event": 30}, {"op": "iftrue", "script": "1:4010"},
                                  {"op": "changeblock", "x": 4, "y": 2, "block": 0x22},
                                  {"op": "changeblock", "x": 0, "y": 0, "block": 1}, {"op": "endcallback"}])
    E.scripts[b"1:4010"] = a.lua([{"op": "changeblock", "args": [6, 6, 0x23]}, {"op": "endcallback"}])
    E.scripts[b"1:4020"] = a.lua([{"op": "changeblock", "x": 2, "y": 2, "block": 0x24}, {"op": "end"}])
    E.maps[b"TEST_TOWN"].callbacks = a.lua([{"callback": "MAPCALLBACK_TILES", "scriptKey": "1:4000"}])
    E.scripts[b"2:5000"] = a.lua([{"op": "changemapblocks", "bank": 3, "address": 0x4002}, {"op": "end"}])
    E.maps[b"TEST_HOUSE"].coordEvents = a.lua([{"x": 1, "y": 1, "scriptKey": "2:5000"}])
    cave = E.maps[b"TEST_CAVE"]
    cave.blockdata = a.lua({"bank": 3, "address": 0x4000})
    for i in range(1, 65):
        cave.blocks[i] = 1
    for i in (3, 4, 9):          # TEST_HOUSE's run starts at the cave's third block
        cave.blocks[i] = 0x30 + i


def test_cut_trees_and_whirlpools_are_sites_and_cut_grass_is_not():
    a = Adapter2()
    set_block(a, "TEST_TOWN", 1, 0, 0x40)
    set_block(a, "TEST_TOWN", 2, 0, 0x50)
    set_block(a, "TEST_TOWN", 3, 5, 0x60)
    captured(a)
    assert sites(a) == [("G2_TEST_TOWN", 1, 0, "cut", 0x40, 0x41), ("G2_TEST_TOWN", 3, 5, "whirlpool", 0x60, 0x61)]


def test_the_blocks_a_maps_scripts_change_are_sites():
    a = Adapter2()
    scripted(a)
    captured(a)
    got = sites(a)
    town = [s for s in got if s[0] == "G2_TEST_TOWN"]
    # cells (4, 2) and (6, 6) are blocks (2, 1) and (3, 3); the write of the map's own block and the
    # script no map reaches give nothing
    assert town == [("G2_TEST_TOWN", 2, 1, "script", 1, 0x22), ("G2_TEST_TOWN", 3, 3, "script", 1, 0x23)]
    house = [s for s in got if s[0] == "G2_TEST_HOUSE"]
    # the whole-map swap: TEST_HOUSE (5x4) from the cave's blocks 3..22, the three that differ
    assert house == [("G2_TEST_HOUSE", 0, 0, "script", 1, 0x33), ("G2_TEST_HOUSE", 1, 0, "script", 1, 0x34),
                     ("G2_TEST_HOUSE", 1, 1, "script", 1, 0x39)]


def test_the_bedrooms_decorations_are_sites():
    a = Adapter2()
    a.E.newMap(b"PLAYERS_HOUSE_2F", 4, 3, a.lua({"environment": "INDOOR", "palette": "PALETTE_DAY"}))
    captured(a)
    room = [s[1:] for s in sites(a) if s[0] == "G2_PLAYERS_HOUSE_2F"]
    # beds at (0, 2), the plant (3, 2), the poster (3, 0), the carpet's four blocks; the doll is an object
    assert room == [(0, 0, "script", 1, 0x08), (3, 0, "script", 1, 0x1f), (0, 1, "script", 1, 0x09),
                    (1, 1, "script", 1, 0x0a), (2, 1, "script", 1, 0x09), (0, 2, "script", 1, 0x1b),
                    (0, 2, "script", 1, 0x1c), (3, 2, "script", 1, 0x20)]


def test_sites_are_worked_out_once_and_logged_as_counts():
    a = Adapter2()
    scripted(a)
    set_block(a, "TEST_TOWN", 1, 0, 0x40)
    captured(a)
    first = a.eng.swapSites()
    assert a.same(first, a.eng.swapSites())
    assert ("info", "story.sites") in a.lines()
    fields = next(line.fields for line in [a.log.lines[i] for i in range(1, len(a.log.lines) + 1)]
                  if line.code == b"story.sites")
    assert plain(fields) == {"sites": 6, "cut": 1, "whirlpool": 0, "script": 2, "decoration": 0, "mapBlocks": 3,
                             "maps": 5, "pristine": True}
    # a new copy of the map data works them out again
    captured(a)
    assert not a.same(first, a.eng.swapSites())


def test_no_sites_out_of_a_game():
    a = Adapter2()
    a.E.game.data = None
    assert a.eng.swapSites() is None
