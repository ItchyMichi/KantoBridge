"""MG7 G3C (docs/specs/GEN3_BRIDGE_PLAN.md §4.6): the Gen 3 adapter's story part
(gen1-bridge/lib/engine/game3/story.lua) on the fake game3 engine. The adapter alone (Adapter3): toggles
from every map's object templates and their hide flags (the flag store's number, string and hex keys;
templates without a flag, the camera and seam objects left out; the game data's maps where the bundle has
none), and the current map's changed cells with their own metatiles. Then main.lua with a peer
(Rig(family="gen3")): the snapshot's toggles and cells, story.toggles and story.flags when a flag changes,
and story.cells for a cell changed, changed again, gone back, a same-map reload and another map. Every
message is schema-checked. Everything is made up."""

import pytest

from adapter3 import Adapter3, plain
from rig import Rig

pytestmark = pytest.mark.lua

TOWN, HOUSE, CAVE = "EM_TEST_TOWN", "EM_TEST_HOUSE", "EM_TEST_CAVE"


def base(x, y):
    """The fake layout's own metatile at (x, y)."""
    return 1 + (x + y) % 8


def objects(a):
    E = a.E
    E.mapObjects(TOWN.encode(), a.b.to_lua([
        {"localId": 1, "flag": 0x101, "x": 3, "y": 4},
        {"localId": 2, "flag": 0, "x": 5, "y": 4},        # no hide flag: always visible
        {"localId": 3, "flag": 0xFFFF, "x": 6, "y": 4},   # the same
        {"localId": 127, "flag": 0x102, "x": 1, "y": 1},  # the camera
        {"localId": 0xC1, "flag": 0x102, "x": 2, "y": 1},  # carried across a seam
    ]))
    E.mapObjects(HOUSE.encode(), a.b.to_lua([{"localId": 4, "flag": 0x102, "x": 2, "y": 2}]))


def test_toggles_are_the_objects_whose_hide_flag_is_set_on_every_map():
    a = Adapter3()
    a.field()
    objects(a)
    toggles, skipped = a.eng.storyToggles()
    assert plain(toggles) == {} and skipped == 0
    a.E.setFlag(0x101)
    assert plain(a.eng.storyToggles()[0]) == {TOWN: {"O1": False}}
    a.E.setFlag(0x102)
    assert plain(a.eng.storyToggles()[0]) == {TOWN: {"O1": False}, HOUSE: {"O4": False}}
    a.E.setFlag(0x101, False)
    assert plain(a.eng.storyToggles()[0]) == {HOUSE: {"O4": False}}
    # the store's other key forms (flags.lua:377-389), and the game data's maps where the bundle has none
    a.E.setFlag(0x102, False)
    a.E.Space.store.flags[b"0x103"] = True
    a.E.game.data = a.b.to_lua({"maps": {CAVE: {"objects": [{"localId": 7, "flag": 0x103}]},
                                         TOWN: {"objects": [{"localId": 9, "flag": 0x103}]}}})
    assert plain(a.eng.storyToggles()[0]) == {CAVE: {"O7": False}}  # the bundle's town templates win


def test_cells_are_the_current_maps_overrides_that_differ_from_its_own():
    a = Adapter3()
    assert a.eng.storyCells() is None  # no field
    a.field()
    assert plain(a.eng.storyCells()) == {"map": TOWN, "cells": {}}
    a.E.setMetatile(3, 4, 99)
    a.E.setMetatile(5, 6, base(5, 6))  # an override that changes nothing
    a.E.setMetatile(40, 4, 99)         # off the map
    assert plain(a.eng.storyCells()) == {"map": TOWN, "cells": {4 * 1024 + 3: 99}}
    assert a.eng.cellBase(3, 4) == base(3, 4) and a.eng.cellBase(-1, 0) is None and a.eng.cellBase(20, 0) is None


# ------------------------------------------------------------ main.lua and a peer


def field_rig():
    rig = Rig(family="gen3")
    rig.world(TOWN, 5, 6, "down")
    return rig


def connected(rig):
    peer = rig.connect()
    peer.hello()
    return peer


def pump(rig, peer, until, limit=30):
    got = []
    for _ in range(limit):
        rig.step()
        got.extend(peer.messages())
        if any(until(m) for m in got):
            return got
    raise AssertionError(f"not within {limit} steps: {[m['type'] for m in got]}")


def cells_of(msgs):
    return [(c["x"], c["y"], c["metatile"]) for m in msgs if m["type"] == "story.cells" for c in m["data"]["cells"]]


def no_faults(rig):
    faults = [line for line in rig.logs()
              if "step_failed" in line or "[error]" in line or ("hook" in line and "failed" in line)]
    assert not faults, faults


def test_the_snapshot_holds_the_toggles_and_the_current_maps_cells():
    rig = field_rig()
    E = rig.E
    E.mapObjects(TOWN.encode(), rig.b.to_lua([{"localId": 1, "flag": 0x101}]))
    E.setFlag(0x101)
    E.setMetatile(2, 3, 50)
    peer = connected(rig)
    snap = next(m["data"] for m in peer.messages() if m["type"] == "snapshot")
    assert snap["toggles"] == {TOWN: {"O1": False}}
    assert snap["cells"] == [{"map": TOWN, "x": 2, "y": 3, "metatile": 50}]
    assert "FLAG_TEST_ONE" in snap["flags"]
    rig.step(12)
    assert not [m for m in peer.messages() if m["type"].startswith("story.")], "nothing new since the snapshot"
    no_faults(rig)


def test_a_flag_hides_and_shows_its_object_and_is_reported():
    rig = field_rig()
    E = rig.E
    E.mapObjects(HOUSE.encode(), rig.b.to_lua([{"localId": 4, "flag": 0x102}]))
    peer = connected(rig)
    peer.messages()
    E.setFlag(0x102)
    msgs = pump(rig, peer, lambda m: m["type"] == "story.toggles")
    assert next(m["data"] for m in msgs if m["type"] == "story.toggles") == {"map": HOUSE, "obj": "O4", "visible": False}
    msgs += pump(rig, peer, lambda m: m["type"] == "story.flags") if not any(
        m["type"] == "story.flags" for m in msgs) else []
    assert "FLAG_TEST_TWO" in next(m["data"] for m in msgs if m["type"] == "story.flags")["set"]
    E.setFlag(0x102, False)
    msgs = pump(rig, peer, lambda m: m["type"] == "story.toggles")
    assert next(m["data"] for m in msgs if m["type"] == "story.toggles") == {"map": HOUSE, "obj": "O4", "visible": True}
    no_faults(rig)


def test_cells_are_reported_as_they_change_go_back_and_reload():
    rig = field_rig()
    E = rig.E
    peer = connected(rig)
    peer.messages()
    E.setMetatile(5, 5, 77)
    msgs = pump(rig, peer, lambda m: m["type"] == "story.cells")
    assert next(m["data"]["map"] for m in msgs if m["type"] == "story.cells") == TOWN
    assert cells_of(msgs) == [(5, 5, 77)]
    E.setMetatile(5, 5, 78)
    E.setMetatile(1, 0, 60)
    assert cells_of(pump(rig, peer, lambda m: m["type"] == "story.cells")) == [(1, 0, 60), (5, 5, 78)]
    E.setMetatile(5, 5, None)  # back to the map's own
    assert cells_of(pump(rig, peer, lambda m: m["type"] == "story.cells")) == [(5, 5, base(5, 5))]
    # a same-map reload: its scripts set (1, 0) again, not (2, 2): nothing for (1, 0), (2, 2) unchanged
    E.setMetatile(2, 2, 61)
    pump(rig, peer, lambda m: m["type"] == "story.cells")
    E.reloadCells()
    E.setMetatile(1, 0, 60)
    assert cells_of(pump(rig, peer, lambda m: m["type"] == "story.cells")) == [(2, 2, base(2, 2))]
    rig.step(5)
    assert cells_of(peer.messages()) == []
    no_faults(rig)


def test_another_map_starts_from_its_own_cells():
    rig = field_rig()
    E = rig.E
    peer = connected(rig)
    E.setMetatile(5, 5, 77)
    pump(rig, peer, lambda m: m["type"] == "story.cells")
    E.enterField(HOUSE.encode(), 2, 2, b"up", rig.b.to_lua({"via": "warp"}))
    E.setMetatile(3, 3, 40)
    msgs = pump(rig, peer, lambda m: m["type"] == "story.cells")
    data = [m["data"] for m in msgs if m["type"] == "story.cells"]
    assert [(d["map"], [(c["x"], c["y"], c["metatile"]) for c in d["cells"]]) for d in data] == [(HOUSE, [(3, 3, 40)])]
    no_faults(rig)
