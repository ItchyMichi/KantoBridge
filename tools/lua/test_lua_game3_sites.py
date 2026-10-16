"""SE2, SE6, SE7 (docs/specs/STORY_EVENTS_PLAN.md): the Gen 3 adapter's sites part
(gen1-bridge/lib/engine/game3/sites.lua) and what reads it, on the fake game3 engine (fake_engine3.lua with
fake_export3.lua's made-up world and fake_sites3.lua's script bundle, layouts and writers). The export's g3sites:
a script's setmetatile under a condition or in an event script (its collision byte: 0x07 when impassable, else the
metatile's behaviour's), an alternate layout's differing cells, the door repair on a warp cell, the abnormal weather's
route filter, the places left out, the engine writers (Mauville Gym's switches and barriers, Petalburg Gym's doors, the
Mirage Tower, the lottery laptop) and the cells by behaviour (TVs indoors, ash grass, thin and cracked ice, cracked
floors); every-visit changes baked into g3map; a map its scripts darken exported dark. Then the facts: the weather the
game draws and player.state's weather; the darkened map lit only at flash level 0 (facts, the save view's litMaps,
story.lit through main.lua). Every resource and message is schema-checked. Everything is made up."""

import base64
import json
import struct

import pytest

from adapter3 import Adapter3, plain
from kb_protocol import load_protocol
from rig import Rig
from test_lua_game3_export import exported

pytestmark = pytest.mark.lua

PROTOCOL = load_protocol()
TOWN, ROUTE, SEA, HOUSE, GYM = "EM_TEST_TOWN", "EM_TEST_ROUTE", "EM_TEST_SEA", "EM_TEST_HOUSE", "EM_TEST_GYM"


def rows(a, key, lst):
    a.E.script(key.encode(), a.lua(lst))


def scripted_world():
    """fake_export3's world with scripts: TOWN's onLoad sets (1, 1) on every visit and, under FLAG_TEST_ONE, (2, 2)
    impassable and (2, 1) (a warp cell) to an unknown metatile, impassable; its first object's script sets (3, 3);
    ROUTE's onTransition swaps to layout 9 while VAR 0x4050 is 2 and asks VAR_ABNORMAL_WEATHER_LOCATION for 3 (another
    route's) before a setmetatile; SEA's onTransition always swaps to layout 10; HOUSE's TVs (metatile 513)."""
    a = Adapter3()
    E = a.E
    E.exportWorld()
    rows(a, "g3:00000100", [
        {"op": "setmetatile", 1: 1, 2: 1, 3: 0, 4: 0},
        {"op": "checkflag", "flag": 0x101, 1: 0x101},
        {"op": "goto_if", "cond": 1, "target": "g3:00000101", 1: 1, 2: 0x101},
        {"op": "end"},
    ])
    rows(a, "g3:00000101", [
        {"op": "setmetatile", 1: 2, 2: 2, 3: 5, 4: 1},
        {"op": "setmetatile", 1: 2, 2: 1, 3: 300, 4: 1},
        {"op": "return"},
    ])
    rows(a, "g3:00000102", [{"op": "setmetatile", 1: 3, 2: 3, 3: 4, 4: 0}, {"op": "end"}])
    events = E.Space.bundle.events
    town = events.EM_TEST_TOWN
    town.mapScripts = a.lua({"onLoad": "g3:00000100"})
    town.objects[1].scriptKey = b"g3:00000102"
    rows(a, "g3:00000200", [
        {"op": "compare_var_to_value", "var": 0x4050, "value": 2, 1: 0x4050, 2: 2},
        {"op": "goto_if", "cond": 1, "target": "g3:00000201", 1: 1, 2: 0x201},
        {"op": "compare_var_to_value", "var": 0x4037, "value": 3, 1: 0x4037, 2: 3},
        {"op": "goto_if", "cond": 1, "target": "g3:00000202", 1: 1, 2: 0x202},
        {"op": "end"},
    ])
    rows(a, "g3:00000201", [{"op": "setmaplayoutindex", 1: 9}, {"op": "return"}])
    rows(a, "g3:00000202", [{"op": "setmetatile", 1: 0, 2: 0, 3: 1, 4: 0}, {"op": "return"}])
    E.mapEvents(ROUTE.encode(), a.lua({"mapScripts": {"onTransition": "g3:00000200"}}))
    rows(a, "g3:00000300", [{"op": "setmaplayoutindex", 1: 10}, {"op": "end"}])
    E.mapEvents(SEA.encode(), a.lua({"mapScripts": {"onTransition": "g3:00000300"}}))
    # the alternate layouts: the route's own cells (fake_export3's layout(): metatile 7, collision alternating)
    # with (1, 2) water; the sea's (metatile 3) with (0, 0) metatile 5
    E.altLayout(9, 4, 5, a.b.eval("""function(x, y)
      if x == 1 and y == 2 then return 3, 0x29, 3 end
      return 7, (x + y) % 2 == 0 and 0 or 1, 3 end"""))
    E.altLayout(10, 3, 3, a.b.eval("""function(x, y)
      if x == 0 and y == 0 then return 5, 0, 3 end
      return 3, (x + y) % 2 == 0 and 0 or 1, 3 end"""))
    E.InteractionScripts.behaviors.TEST_B[513] = 0x34  # MB_TELEVISION (fake_sites3.lua)
    return a


def sites_of(res):
    data = res[("g3sites", "all")]
    assert not PROTOCOL.check_export("g3sites", data), [str(p) for p in PROTOCOL.check_export("g3sites", data)]
    return {(s["map"], s["x"], s["y"]): s for s in data["sites"]}, data


def states(site):
    return [(st["metatile"], st["coll"], st["source"]) for st in site["states"]]


def cell(res, map_id, x, y):
    m = res[("g3map", map_id)]
    raw = base64.b64decode(m["cells"])
    return struct.unpack_from("<HBB", raw, (y * m["w"] + x) * 4)


def test_g3sites_lists_the_cells_scripts_and_layouts_change():
    res, warns, ok, _ = exported(scripted_world())
    assert ok is True and "g3_sites_failed" not in warns
    sites, data = sites_of(res)
    # a conditional setmetatile, impassable: 0x07
    assert states(sites[(TOWN, 2, 2)]) == [(5, 0x07, "script")]
    assert sites[(TOWN, 2, 2)]["own"] == {"metatile": 6, "coll": 0, "elev": 3}
    # an object's script: the metatile's behaviour gives its byte (TEST_A's 4 is water: 0x29)
    assert states(sites[(TOWN, 3, 3)]) == [(4, 0x29, "script")]
    # a warp cell under an impassable, unknown metatile: the door repair's 0x71 (collision.lua:167-205)
    assert states(sites[(TOWN, 2, 1)]) == [(300, 0x71, "script")]
    # a conditional layout swap: its differing cell
    assert states(sites[(ROUTE, 1, 2)]) == [(3, 0x29, "layout")]
    # another route's abnormal weather never runs here; the every-visit changes are baked, not sites
    assert (ROUTE, 0, 0) not in sites and (TOWN, 1, 1) not in sites and (SEA, 0, 0) not in sites
    # the TVs indoors: on and off, impassable; (1, 2) is the house's warp cell too, where the door repair makes an
    # unknown behaviour's solid byte a door's (as every bind on the engine does)
    for y in (0, 1):
        assert states(sites[(HOUSE, 1, y)]) == [(48, 0x07, "native"), (49, 0x07, "native")]
    assert states(sites[(HOUSE, 1, 2)]) == [(48, 0x71, "native"), (49, 0x71, "native")]
    assert data["baked"] == 2
    assert [k for k in sites] == sorted(sites, key=lambda k: (k[0], k[2], k[1])), "by map, then row, then column"


def test_every_visit_changes_are_baked_into_g3map():
    res, _, _, _ = exported(scripted_world())
    # TOWN (1, 1): the onLoad's unconditional metatile 0 (behaviour 0: collision 0x00), the elevation kept
    assert cell(res, TOWN, 1, 1) == (0, 0x00, 3)
    # SEA (0, 0): the onTransition's unconditional layout swap
    assert cell(res, SEA, 0, 0) == (5, 0, 3) and cell(res, SEA, 1, 0) == (3, 1, 3)
    # the conditional ones are the layout's own
    assert cell(res, TOWN, 2, 2) == (6, 0, 3) and cell(res, ROUTE, 1, 2) == (7, 1, 3)


def gym_world():
    """A gym (pair TEST_A) whose scripts call Mauville Gym's and Petalburg Gym's specials, the Mirage Tower's shake and
    the lottery's laptop; its cells: Mauville's switch (1, 1) raised, row 3 barriers on, off, on; Petalburg's room 1
    door (2, 1) and (2, 2); ash grass (Fallarbor's (0, 2) and another (1, 2)), cracked ice (3, 2), cracked floors (a
    cave's (0, 4) and another (1, 4)), thin ice (2, 4)."""
    a = Adapter3()
    E = a.E
    E.exportWorld()
    L = {"on": 54, "off": 55, "raised": 53}
    behaviors = E.InteractionScripts.behaviors.TEST_A
    for mid, beh in ((40, 0x30), (8, 0x30), (9, 0x31), (11, 0x32), (45, 0x33), (10, 0x33)):
        behaviors[mid] = beh
    E.siteMap(GYM.encode(), a.lua({"pair": "TEST_A", "kind": "indoor", "mapType": 1, "warps": []}), a.lua([
        [[0, 0], [0, 0], [0, 0], [0, 0]],
        [[0, 0], [L["raised"], 0], [0, 0], [0, 0]],
        [[40, 0], [8, 0], [0, 0], [11, 0]],
        [[L["on"], 0x07], [L["off"], 0], [L["on"], 0x07], [0, 0]],
        [[45, 0], [10, 0], [9, 0], [0, 0]],
    ]))
    rows(a, "g3:00000400", [{"op": "special", "id": 21, 1: 21}, {"op": "end"}])
    rows(a, "g3:00000401", [{"op": "special", "id": 20, 1: 20}, {"op": "special", "id": 23, 1: 23},
                            {"op": "special", "id": 24, 1: 24}, {"op": "special", "id": 25, 1: 25}, {"op": "end"}])
    E.mapEvents(GYM.encode(), a.lua({"mapScripts": {"onLoad": "g3:00000400"},
                                     "bgEvents": [{"type": "sign", "x": 0, "y": 0, "scriptKey": "g3:00000401"}]}))
    E.mirageTower.invisibleMetatiles = a.lua([{"x": 3, "y": 0, "metatile": 7}])
    return a


def test_g3sites_takes_the_engine_writers_states():
    res, warns, ok, _ = exported(gym_world())
    assert ok is True
    sites, _ = sites_of(res)
    # Mauville Gym: the switch pressed; each barrier in its other state, impassable as the engine sets it on (a
    # metatile without a behaviour passable: 0x00)
    assert states(sites[(GYM, 1, 1)]) == [(52, 0x00, "native")]
    assert states(sites[(GYM, 0, 3)]) == [(55, 0x00, "native")]
    assert states(sites[(GYM, 1, 3)]) == [(54, 0x07, "native")]
    assert states(sites[(GYM, 2, 3)]) == [(55, 0x00, "native")]
    # Petalburg Gym: room 1's door in each of its five frames, impassable (story_specials.lua:136-141)
    assert states(sites[(GYM, 2, 1)]) == [(60 + f, 0x07, "native") for f in range(5)]
    assert states(sites[(GYM, 2, 2)]) == [(68 + f, 0x07, "native") for f in range(5)]
    # the Mirage Tower's hidden cell and the lottery's laptop on (3, 0)
    assert states(sites[(GYM, 3, 0)]) == [(7, 0x07, "native"), (50, 0x07, "native"), (51, 0x07, "native")]
    # by behaviour: Fallarbor's ash to Fallarbor's grass, other ash to Lavaridge's; thin ice cracks then breaks,
    # cracked ice breaks; a cave's cracked floor to its hole, another to Sky Pillar's
    assert states(sites[(GYM, 0, 2)]) == [(41, 0x00, "native")]
    assert states(sites[(GYM, 1, 2)]) == [(42, 0x00, "native")]
    assert states(sites[(GYM, 3, 2)]) == [(44, 0x00, "native")]
    assert states(sites[(GYM, 2, 4)]) == [(43, 0x00, "native"), (44, 0x00, "native")]
    assert states(sites[(GYM, 0, 4)]) == [(46, 0x00, "native")]
    assert states(sites[(GYM, 1, 4)]) == [(47, 0x00, "native")]


def test_left_out_places_have_no_sites():
    a = scripted_world()
    a.E.siteMap(b"EM_SECRET_BASE_TEST", a.lua({"pair": "TEST_A", "kind": "indoor", "mapType": 8, "warps": []}),
                a.lua([[[0, 0], [0, 0]]]))
    rows(a, "g3:00000500", [{"op": "setmetatile", 1: 1, 2: 0, 3: 7, 4: 1}, {"op": "end"}])
    a.E.mapEvents(b"EM_SECRET_BASE_TEST", a.lua({"bgEvents": [{"type": "sign", "x": 0, "y": 0,
                                                               "scriptKey": "g3:00000500"}]}))
    res, _, _, _ = exported(a)
    sites, _ = sites_of(res)
    assert not [k for k in sites if k[0] == "EM_SECRET_BASE_TEST"]


def test_a_map_its_entry_scripts_darken_is_dark():
    a = Adapter3()
    a.E.exportWorld()
    rows(a, "g3:00000600", [
        {"op": "checkflag", "flag": 0x101, 1: 0x101},
        {"op": "goto_if", "cond": 1, "target": "g3:00000601", 1: 1, 2: 0x601},
        {"op": "setflashlevel", 1: 3},
        {"op": "end"},
    ])
    rows(a, "g3:00000601", [{"op": "setflashlevel", 1: 0}, {"op": "end"}])
    a.E.mapEvents(HOUSE.encode(), a.lua({"mapScripts": {"onTransition": "g3:00000600"}}))
    # a flash level set only by an object's script darkens nothing
    rows(a, "g3:00000602", [{"op": "setflashlevel", 1: 5}, {"op": "end"}])
    a.E.Space.bundle.events.EM_TEST_TOWN.objects[1].scriptKey = b"g3:00000602"
    res, _, _, _ = exported(a)
    assert res[("g3map", HOUSE)]["dark"] is True
    assert "dark" not in res[("g3map", TOWN)]
    assert HOUSE in plain(a.eng.darkMaps())


# ---------------------------------------------------------------------------------------------- the facts


def test_the_facts_carry_the_weather_the_game_draws():
    a = Adapter3()
    a.field()
    E = a.E
    assert a.facts()["weather"] == "none"           # not started
    E.weather(3)
    assert a.facts()["weather"] == "rain"
    E.weather(13, a.lua({"next": 12}))               # the abnormal weather mid-change draws downpour
    assert a.facts()["weather"] == "downpour"
    E.weather(12, a.lua({"suspended": True}))        # a battle's freeze
    assert a.facts()["weather"] == "none"
    E.weather(99)
    assert a.facts()["weather"] == "none"           # an id the renderer doesn't know


def dewford(a):
    """HOUSE's onTransition sets flash level 4: a map its scripts darken."""
    rows(a, "g3:00000700", [{"op": "setflashlevel", 1: 4}, {"op": "end"}])
    a.E.mapEvents(HOUSE.encode(), a.lua({"mapScripts": {"onTransition": "g3:00000700"}}))
    a.E.game.data.maps[HOUSE.encode()] = a.lua({"id": HOUSE})


def test_a_map_its_scripts_darken_is_lit_only_at_flash_level_0():
    a = Adapter3()
    dewford(a)
    a.field(HOUSE, 2, 2)
    a.E.flashLevel(4)
    f = a.facts()
    assert (f["darkMap"], f["flashLit"]) == (True, False)
    view = a.eng.saveView()
    assert plain(view.litMaps) == {HOUSE: False}
    a.E.flashLevel(0)
    a.eng.setGame(a.E.game)  # the next logic step: the save view is built once a step (boot.lua)
    f = a.facts()
    assert (f["darkMap"], f["flashLit"]) == (True, True)
    assert plain(a.eng.saveView().litMaps) == {HOUSE: True}
    # Flash's flag never lights it, and elsewhere it is dark
    a.E.setFlag(b"FLAG_SYS_USE_FLASH")
    a.E.flashLevel(4)
    assert a.facts()["flashLit"] is False


def test_main_sends_the_weather_and_the_darkened_maps_light():
    rig = Rig(family="gen3")
    E = rig.E
    E.script(b"g3:00000700", rig.b.to_lua([{"op": "setflashlevel", 1: 4}, {"op": "end"}]))
    E.mapEvents(HOUSE.encode(), rig.b.to_lua({"mapScripts": {"onTransition": "g3:00000700"}}))
    E.game.data.maps[HOUSE.encode()] = rig.b.to_lua({"id": HOUSE})
    rig.world(HOUSE, 2, 2, "down")
    E.flashLevel(4)
    E.weather(3)
    peer = rig.connect()
    peer.hello()
    snap = next(m["data"] for m in peer.messages() if m["type"] == "snapshot")
    assert snap["state"]["weather"] == "rain" and snap["state"]["dark"] is True and snap["state"]["lit"] is False
    assert HOUSE not in snap["lit"]
    E.weather(5)
    E.flashLevel(0)
    got = []
    for _ in range(30):
        rig.step()
        got.extend(peer.messages())
        if any(m["type"] == "story.lit" for m in got) and any(
                m["type"] == "player.state" and m["data"].get("weather") == "thunder" for m in got):
            break
    states_ = [m["data"] for m in got if m["type"] == "player.state"]
    assert states_ and states_[-1]["weather"] == "thunder" and states_[-1]["lit"] is True
    assert {"map": HOUSE, "lit": True} in [m["data"] for m in got if m["type"] == "story.lit"]
    faults = [line for line in rig.logs() if "step_failed" in line or "[error]" in line]
    assert not faults, faults
