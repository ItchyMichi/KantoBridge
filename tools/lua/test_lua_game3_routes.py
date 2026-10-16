"""MG8 (docs/specs/HOENN_PASS_PLAN.md K2-K4, HP3): a Wild Routes end on Gen 3, the game3 adapter's routes part
(gen1-bridge/lib/engine/game3/routes.lua) on the fake game3 engine. The adapter alone (Adapter3, with a zone
record as lib/kb_routes.lua hands it over): the clearing planned on the town's own cells (2x2 blocks of
metatiles: the exit block walkable, then `depth` blocks of trees) and every refusal; the clearing opened on every
load of the town with the exit's metatiles and elevation, which the story part counts as the map's own; the park on
the trigger cell standing for the route map at x1 (facts, NPCs, the warp events the core is told of); the way back
(the step onto A: the engine's warp to the back cell, facing the town) and every other step a wall; the resume onto
the route map; the zone's acts (an item into the bag with a found line; a battle with a class's first trainer as
the template and the request's party, held for Cobblemon and answered, an abort too). Then main.lua with a peer
(Rig(family="gen3")) and a made-up route list: the warp into the route map and back, a region park and resume onto
it, and the export's routes resource. Everything is made up."""

import json

import pytest

from adapter3 import Adapter3, plain
from rig import Rig

pytestmark = pytest.mark.lua

TOWN = "EM_TEST_TOWN"
ROUTE_MAP = "KB_ROUTE_TEST_PASS_TO"
TREES, GROUND = 0x07, 0x00


def layout(cx, cy):
    """The test town: ground of elevation 3 with made-up metatiles, a band of trees (elevation 0) in its last four
    rows, and a pond at (2, 2)."""
    if cy >= 16:
        return 50 + cx % 2, TREES, 0
    if (cx, cy) == (2, 2):
        return 60, 0x29, 3
    return 1 + (cx + cy) % 8, GROUND, 3


def zone_record(a, x=10, y=14, facing="down", depth=2, key="test_pass:to"):
    """A zone record as lib/kb_routes.lua's installOwn makes one."""
    return a.lua({
        "id": "test_pass", "key": key, "which": "to", "mapId": ROUTE_MAP, "town": TOWN,
        "finish": {"map": TOWN, "x": x, "y": y, "facing": facing, "depth": depth},
        "plan": {"cleared": {}, "triggers": {}, "triggerList": {}, "ready": False}, "active": True, "classes": {},
        "own": True,
    })


def setup(**spot):
    a = Adapter3()
    a.E.layouts[TOWN.encode()] = a.b.eval("function(f) return function(cx, cy) return f(cx, cy) end end")(
        lambda cx, cy: layout(cx, cy))
    s = a.field(TOWN, 10, 14, "down")
    z = zone_record(a, **spot)
    return a, s, z


def override(a, x, y):
    ov = a.E.Map._def.midLayout.overrides[y * 1024 + x]
    return None if ov is None else (ov.mid, ov.coll, ov.elev)


def test_the_clearing_is_planned_on_the_towns_own_cells():
    a, _, z = setup()
    assert a.eng.routeInstall(z) is True
    p = z.plan
    assert p.ready is True and z.active is True
    assert plain(p.cleared) == [[5, 8], [5, 9]]
    # each cleared cell copies the exit block's metatile at its place, with the exit's elevation
    mids = {(c.x, c.y): (c.mid, c.elev) for c in p.cells.values()}
    exit_mids = [layout(10, 14)[0], layout(11, 14)[0], layout(10, 15)[0], layout(11, 15)[0]]
    assert mids[(10, 16)] == (exit_mids[0], 3) and mids[(11, 17)] == (exit_mids[3], 3) and len(mids) == 8
    assert plain(p.triggerList) == [[10, 19], [11, 19]]
    assert plain(p.back) == {"x": 10, "y": 18, "facing": "up"} and p.exitFacing == b"down"


@pytest.mark.parametrize("spot,code", [
    ({"x": 10, "y": 16}, "spot_unwalkable"),         # the exit on the trees
    ({"x": 10, "y": 10}, "not_trees"),               # ground beyond the exit
    ({"x": 10, "y": 14, "depth": 4}, "outside_map"),  # past the map's last row
    ({"x": 2, "y": 2, "facing": "down", "depth": 1}, "spot_unwalkable"),  # the pond in the exit block
])
def test_a_spot_that_does_not_check_out_switches_the_zone_off(spot, code):
    a, _, z = setup(**spot)
    assert a.eng.routeInstall(z) is True, "taken (the core counts it), but off"
    assert z.active is False and z.problem == code.encode() and z.plan.ready is False
    assert ("warn", "routes.own_failed") in a.lines()


def test_an_object_on_the_clearing_switches_it_off():
    a, _, z = setup()
    a.E.mapObjects(TOWN.encode(), a.lua([{"localId": 4, "x": 11, "y": 17}]))
    a.eng.routeInstall(z)
    assert z.active is False and z.problem == b"occupied"


def test_a_bad_end_is_refused():
    a, _, z = setup()
    z.finish.facing = b"north"
    assert tuple(a.eng.routeInstall(z)) == (False, b"bad_spot")


def test_the_clearing_opens_on_every_load_and_is_the_maps_own():
    a, _, z = setup()
    a.eng.routeInstall(z)
    a.eng.routeMapEntered(a.lua({"mapId": TOWN, "via": "boot"}))
    exit_mid = layout(10, 14)[0]
    assert override(a, 10, 16) == (exit_mid, 0x00, 3), "passable, the exit's metatile and elevation"
    assert all(override(a, x, y) is not None for x in (10, 11) for y in (16, 17, 18, 19))
    # the story part counts them as the map's own (the export bakes them in)
    cells = a.eng.storyCells()
    assert plain(cells.cells) == {} or not any((k % 1024, k // 1024) in [(10, 16)] for k in plain(cells.cells))
    # SE1: the own cell's metatile and collision class (the clearing is open ground)
    assert tuple(a.eng.cellBase(10, 16)) == (exit_mid, b"ground")
    assert a.eng.cellBase(5, 5)[0] == layout(5, 5)[0]
    # a reload clears every override; the next map.entered opens the clearing again
    a.E.reloadCells()
    assert override(a, 10, 16) is None
    a.eng.routeMapEntered(a.lua({"mapId": TOWN, "via": "warp"}))
    assert override(a, 10, 16) == (exit_mid, 0x00, 3)


def walk_to_trigger(a):
    a.E.Player.cellX, a.E.Player.cellY, a.E.Player.facing = 10, 19, b"down"
    a.eng.routeStepped()


def test_the_trigger_parks_the_player_on_the_route_map():
    a, _, z = setup()
    a.eng.routeInstall(z)
    a.eng.routeMapEntered(a.lua({"mapId": TOWN}))
    a.E.Player.cellX, a.E.Player.cellY = 10, 17
    a.eng.routeStepped()
    assert a.facts()["map"] == TOWN, "not a trigger"
    walk_to_trigger(a)
    f = a.facts()
    assert (f["map"], f["x"], f["y"], f["facing"], f["scripted"], f["moving"]) == (ROUTE_MAP, 1, 0, "right", False, False)
    assert a.E.Field._locks[b"kb_route"] is True
    ev = plain(a.eng.takeRouteEvents())
    assert ev == {"warped": {"kind": "script"}, "entered": [{"map": ROUTE_MAP, "via": "warp"}]}
    assert a.eng.takeRouteEvents() is None, "once"
    npcs, map_id = a.eng.npcs()[0:2]
    assert plain(npcs) == {} and map_id == ROUTE_MAP.encode()
    # a step anywhere else is a wall (the zone is the client's)
    assert tuple(a.eng.routeStep(b"right")) == (False, b"tile")
    assert tuple(a.eng.routeStep(b"up")) == (False, b"tile")
    # the region park on top (a pass): the world counts as held
    assert a.eng.holdAway() is True
    assert a.facts()["scripted"] is True
    a.eng.releaseAway()
    assert a.facts()["scripted"] is False


def test_the_step_onto_a_is_the_way_back():
    a, _, z = setup()
    a.eng.routeInstall(z)
    a.eng.routeMapEntered(a.lua({"mapId": TOWN}))
    walk_to_trigger(a)
    a.eng.takeRouteEvents()
    assert a.eng.routeStep(b"left") is True
    assert plain(a.E.worldApi.warps)[-1] == {"map": TOWN, "x": 10, "y": 18, "facing": "up"}
    f = a.facts()
    assert (f["map"], f["x"], f["y"], f["facing"]) == (TOWN, 10, 18, "up")
    assert b"kb_route" not in dict(a.E.Field._locks.items())
    assert a.eng.routeStep(b"left") is None, "off the route map: the press as ever"
    # the warp's load cleared the cells; the core's map.entered opens them again
    a.eng.routeMapEntered(a.lua({"mapId": TOWN, "via": "warp"}))
    assert override(a, 10, 19) is not None


def test_a_refused_warp_keeps_the_player_on_the_route_map():
    a, _, z = setup()
    a.eng.routeInstall(z)
    a.eng.routeMapEntered(a.lua({"mapId": TOWN}))
    walk_to_trigger(a)
    a.E.refuseWarp = True
    assert tuple(a.eng.routeStep(b"left")) == (False, b"script")
    assert a.facts()["map"] == ROUTE_MAP


def test_a_resume_enters_the_route_map():
    a, _, z = setup()
    a.eng.routeInstall(z)
    a.E.Player.cellX, a.E.Player.cellY = 3, 3
    assert a.eng.routeEnter(b"test_pass:to") is True
    # the warp to the trigger cell loaded the town; the core's map.entered parks there
    a.eng.routeMapEntered(a.lua({"mapId": TOWN, "via": "warp"}))
    f = a.facts()
    assert (f["map"], f["x"], f["y"]) == (ROUTE_MAP, 1, 0)
    assert plain(a.E.worldApi.warps)[-1] == {"map": TOWN, "x": 10, "y": 19, "facing": "down"}
    assert a.eng.takeRouteEvents() is None, "the engine's own warp carries the arrival"
    assert a.eng.routeEnter(b"test_pass:to") is True, "already there"
    assert a.eng.routeEnter(b"no_such:to") is False


TRAINERS = {7: {"class": 1, "className": "YOUNGSTER", "name": "TESTY", "party": [{"species": 11, "level": 5}]}}


def acts(a):
    a.E.trainerTable = a.lua(TRAINERS)
    a.E.giveMon(1, 10, b"", False)
    a.bridge = a.b.eval("""function()
      local b = { facts = {}, plans = 0, live = function() return true end }
      b.battle = function(facts)
        b.facts[#b.facts + 1] = facts
        b.plans = b.plans + 1
        return { id = "b-route-" .. b.plans, kind = "scripted", data = { battle = "b-route-" .. b.plans } }
      end
      return b
    end""")()
    a.eng.attachBridge(a.bridge)
    a.done = []
    a.on_done = a.b.eval("function(list) return function(result) list[#list + 1] = result or 'none' end end")(a.lua([]))
    return a.on_done


def test_a_zone_item_goes_into_the_bag_with_a_found_line():
    a, s, z = setup()
    on_done = acts(a)
    a.eng.routeInstall(z)
    potion = 13
    before = a.E.Bag.get(s.bag, potion)
    results = a.lua([])
    cb = a.b.eval("function(list) return function(r) list[#list + 1] = r or 'done' end end")(results)
    assert a.eng.routeRun(b"give", a.lua({"item": "POTION", "onDone": cb})) is True
    assert a.E.Bag.get(s.bag, potion) == before + 1
    assert a.E.Message.isOpen() and b"TEST ITEM 13" in a.E.Message.currentPage()
    assert plain(results) == {}, "after the line"
    a.E.Message.close()
    assert plain(results) == ["done"]
    assert tuple(a.eng.routeRun(b"give", a.lua({"item": "NO_SUCH", "onDone": cb}))) == (False, b"unknown_item")


def test_a_zone_battle_is_held_for_cobblemon_and_answered():
    a, s, z = setup()
    acts(a)
    a.eng.routeInstall(z)
    results = a.lua([])
    cb = a.b.eval("function(list) return function(r) list[#list + 1] = r or 'none' end end")(results)
    req = {"class": "TRAINER_CLASS_YOUNGSTER", "party": [{"species": "TEST_ONE", "level": 4, "moves": ["TEST_TACKLE"]},
                                                         {"species": "TEST_TWO", "level": 6}], "onDone": cb}
    assert a.eng.routeRun(b"battle", a.lua(req)) is True
    facts = plain(a.bridge.facts[1])
    assert facts["cls"] == "TRAINER_CLASS_YOUNGSTER" and facts["party"] == 7, "the class's first trainer as the template"
    team, _, _ = a.eng.trainerTeam(b"TRAINER_CLASS_YOUNGSTER", 7)
    # the request's party, not the template's (national = species + 250 on the fake)
    rec = a.eng.battlePending()
    assert rec.id == b"b-route-1" and rec.route is True
    assert a.eng.battleFinish(b"b-route-1", b"win") is True
    while a.E.Message.isOpen():
        a.E.Message.close()
    assert plain(results) == ["win"]
    # an abort answers too (the zone's act waits), as a run
    assert a.eng.routeRun(b"battle", a.lua(req)) is True
    assert a.eng.battleFinish(b"b-route-2", b"abort") is True
    assert plain(results) == ["win", "run"]
    assert tuple(a.eng.routeRun(b"battle", a.lua(dict(req, **{"class": "TRAINER_CLASS_NOBODY"})))) == (False, b"unknown_class")
    bad = dict(req, party=[{"species": "NO_SUCH", "level": 4}])
    assert tuple(a.eng.routeRun(b"battle", a.lua(bad))) == (False, b"bad_party")


def test_the_battles_team_is_the_requests():
    a, s, z = setup()
    acts(a)
    a.eng.routeInstall(z)
    req = {"class": "TRAINER_CLASS_YOUNGSTER", "party": [{"species": "TEST_TWO", "level": 9}], "onDone":
           a.b.eval("function() end")}
    a.eng.routeRun(b"battle", a.lua(req))
    start = a.E.starts[len(a.E.starts)]
    assert [(m.species, m.level) for m in start.foe.party.values()] == [(2, 9)]


# ------------------------------------------------------------ main.lua and a peer

ROUTES = {"version": 2, "routes": [{
    "id": "test_pass", "name": "TEST PASS", "kind": "connecting", "theme": "forest",
    "from": {"region": "kanto", "games": ["red", "blue"], "map": "TEST_WOODS", "x": 6, "y": 4, "facing": "up", "depth": 2},
    "to": {"region": "hoenn", "games": ["emerald"], "map": TOWN, "x": 10, "y": 14, "facing": "down", "depth": 2,
           "classes": ["TRAINER_CLASS_YOUNGSTER"], "music": None},
    "areas": {"main": [2, 3], "side": 50}, "levels": {"from": "neighbours", "perArea": 1, "maxRise": 3},
    "trainers": {"perArea": [1, 2], "rematch": "each_visit"}, "dungeon": {"chance": 0}}]}


def rig():
    r = Rig(family="gen3", files={"data/routes.json": json.dumps(ROUTES)})
    r.E.layouts[TOWN.encode()] = r.b.eval("function(f) return function(cx, cy) return f(cx, cy) end end")(
        lambda cx, cy: layout(cx, cy))
    r.world(TOWN, 10, 14, "down")
    return r


def pump(r, peer, until, limit=40):
    got = []
    for _ in range(limit):
        r.step()
        got.extend(peer.messages())
        if any(until(m) for m in got):
            return got
    raise AssertionError(f"not within {limit} steps: {[m['type'] for m in got]}")


def test_main_follows_the_hidden_player_into_the_route_map_and_back():
    r = rig()
    st = r.status
    peer = r.connect()
    peer.hello()
    pump(r, peer, lambda m: m["type"] == "snapshot")
    assert st.routesInstalled == 1, "the route's Emerald end, installed by the adapter"
    # walk down the clearing to its trigger: the engine's steps (the fake's), then world.stepped
    for y in (15, 16, 17, 18, 19):
        r.E.startStep(b"down")
        r.step()
        r.E.land()
    got = pump(r, peer, lambda m: m["type"] == "warp" and m["data"]["phase"] == "arrive")
    arrive = next(m for m in got if m["type"] == "warp" and m["data"]["phase"] == "arrive")["data"]
    assert arrive["kind"] == "script" and arrive["to"] == {"map": ROUTE_MAP, "x": 1, "y": 0, "facing": "right"}
    # the way back: move.step left onto A
    seq = peer.send("move.step", {"dir": "left", "n": 1})
    got = pump(r, peer, lambda m: m["type"] == "warp" and m["data"]["phase"] == "arrive")
    back = next(m for m in got if m["type"] == "warp" and m["data"]["phase"] == "arrive")["data"]
    assert back["to"] == {"map": TOWN, "x": 10, "y": 18, "facing": "up"}
    assert back["kind"] == "script", "a warp within the town: the engine says nothing, the adapter tells the core"
    for _ in range(10):
        r.step()
        got += peer.messages()
    assert sum(1 for m in got if m["type"] == "warp" and m["data"]["phase"] == "arrive") == 1, "arrived once"
    assert not any(m["type"] == "player.blocked" and m["data"].get("n") == 1 for m in got)


def test_main_resumes_a_parked_game_onto_the_route_map():
    r = rig()
    peer = r.connect()
    peer.hello()
    pump(r, peer, lambda m: m["type"] == "snapshot")
    parked = peer.send("region.park", {"region": "hoenn"})
    got = pump(r, peer, lambda m: m["type"] == "act.result" and m.get("re") == parked)
    assert next(m for m in got if m.get("re") == parked)["data"] == {"ok": True, "kind": "park"}
    seq = peer.send("region.resume", {"region": "hoenn", "to": {"map": ROUTE_MAP, "x": 1, "y": 0, "facing": "right"}})
    got = pump(r, peer, lambda m: m["type"] == "act.result" and m.get("re") == seq, limit=80)
    assert next(m for m in got if m.get("re") == seq)["data"] == {"ok": True, "kind": "resume"}
    facts = r.b.mod.exports  # the snapshot after a resync shows the route map
    sseq = peer.send("resync", {})
    got = pump(r, peer, lambda m: m["type"] == "snapshot")
    snap = next(m for m in got if m["type"] == "snapshot")["data"]
    assert snap["player"]["map"] == ROUTE_MAP and (snap["player"]["x"], snap["player"]["y"]) == (1, 0)


def test_the_export_names_the_end_and_bakes_the_clearing_in():
    import base64
    r = Rig(family="gen3", files={"data/routes.json": json.dumps(ROUTES)})
    r.E.exportWorld()
    # the town's data: 20 x 20 of the pair's metatiles (0..7), trees (metatile 7, collision 0x07, elevation 0) in its
    # last four rows
    cells = {}
    for cy in range(20):
        for cx in range(20):
            cells[cy * 20 + cx + 1] = {"mid": 7, "coll": TREES, "elev": 0} if cy >= 16 else \
                {"mid": (cx + cy) % 7, "coll": GROUND, "elev": 3}
    lay = r.b.to_lua({"width": 20, "height": 20, "trueWidth": 20, "trueHeight": 20, "cells": cells, "overrides": {},
                      "borderWidth": 1, "borderHeight": 1, "borderMids": [7]})
    r.b.eval("function(def, lay) rawset(def, 'midLayout', lay) end")(r.E.game.data.maps.EM_TEST_TOWN, lay)
    r.step()
    peer = r.connect()
    peer.hello(role="builder")
    peer.messages()
    seq = peer.send("export.index", {})
    got = []
    for i in range(2000):
        if i % 120 == 119:
            peer.send("ping", {"t": i})
        r.step()
        got += [m for m in peer.messages() if m["type"] != "pong"]
        if any(m.get("re") == seq for m in got):
            break
    index = next(m for m in got if m.get("re") == seq)["data"]
    assert {"routes", "g3map"} <= {e["kind"] for e in index["resources"]}

    def fetch(kind, rid):
        s = peer.send("export.get", {"kind": kind, "id": rid})
        for _ in range(200):
            r.step()
            for m in peer.messages():
                if m.get("re") == s:
                    return m["data"]["data"]
        raise AssertionError("no export.data")

    routes = fetch("routes", "all")
    assert routes["version"] == 2 and len(routes["routes"]) == 1
    item = routes["routes"][0]
    assert (item["id"], item["kind"], item["end"]) == ("test_pass", "connecting", "to")
    assert item["ends"]["to"] == {"region": "hoenn", "games": ["emerald"]}
    assert item["town"] == {"map": TOWN, "cleared": [[5, 8], [5, 9]], "triggers": [[10, 19], [11, 19]],
                            "back": {"x": 10, "y": 18, "facing": "up"}}
    assert item["routeMap"]["id"] == ROUTE_MAP and item["trainers"]["classes"] == ["TRAINER_CLASS_YOUNGSTER"]
    town = fetch("g3map", TOWN)
    raw = base64.b64decode(town["cells"])

    def cell(x, y):
        i = (y * town["w"] + x) * 4
        return raw[i] | raw[i + 1] << 8, raw[i + 2], raw[i + 3]

    exit_mid = (10 + 14) % 7
    assert cell(10, 16) == (exit_mid, 0x00, 3) and cell(11, 19) == ((11 + 15) % 7, 0x00, 3), "open, the exit's metatile"
    assert cell(12, 16) == (7, TREES, 0), "the trees beside the clearing stay"
