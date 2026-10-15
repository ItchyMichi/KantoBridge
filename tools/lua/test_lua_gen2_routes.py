"""MG9 G2H (docs/specs/GEN2_BRIDGE_PLAN.md §5.11, J9): a Wild Routes end on Gen 2, the gen2 adapter's routes part
(gen1-bridge/lib/engine/gen2/routes.lua) on the fake Gen 2 engine. The adapter alone (Adapter2, with a zone
record as lib/kb_routes.lua's installOwn hands it over, and the fake's maps and tilesets registries): the clearing
planned on the town's own blocks (the exit block plain ground, then `depth` blocks of trees) and every refusal; the
route map registered (the town's record with one block of the exit's floor; a slim one when the registry refuses
the whole record) and the town's blocks patched, a refused patch undoing the route map; the steps (a trigger cell
queues the engine's own warp to x1, A the warp back); the zone's acts (an item into the bag with a found line; a
battle with the class's first trainer as the template and the request's party, native or held for Cobblemon);
the blackout. Then main.lua with a peer (Rig(family="gen2")) and a made-up route list: installed in the entry
chunk, the warp into the route map and back, a region park and resume onto it, the export's routes resource with
the clearing baked into the town's blocks. Everything is made up."""

import base64
import json

import pytest

from adapter2 import Adapter2, plain
from rig import Rig

pytestmark = pytest.mark.lua

TOWN = "TEST_PASS_TOWN"
G2_TOWN = "G2_" + TOWN
ROUTE_MAP = "KB_ROUTE_TEST_PASS_TO"
FLOOR, TREES = 4, 5  # block ids added to the fake's TILESET_TEST: plain floor, a block of trees
W, H = 8, 6


def make_town(E, b):
    """The test town: 8 x 6 blocks of floor with two rows of trees along its south edge."""
    ts = E.data.gen2Tilesets.TILESET_TEST
    ts.collision[FLOOR + 1] = b.to_lua([0x00, 0x00, 0x00, 0x00])
    ts.collision[TREES + 1] = b.to_lua([0x07, 0x07, 0x07, 0x07])
    m = E.newMap(TOWN.encode(), W, H, b.to_lua({"blockColl": True, "group": 24, "map": 4}))
    for by in range(H):
        for bx in range(W):
            m.blocks[by * W + bx + 1] = TREES if by >= 4 else FLOOR
    return m


def zone_record(a, x=6, y=6, facing="down", depth=2, key="test_pass:to", town=G2_TOWN, map_id=ROUTE_MAP):
    """A zone record as lib/kb_routes.lua's installOwn makes one."""
    return a.lua({
        "id": "test_pass", "key": key, "which": "to", "mapId": map_id, "town": town,
        "finish": {"map": town, "x": x, "y": y, "facing": facing, "depth": depth},
        "plan": {"cleared": {}, "triggers": {}, "triggerList": {}, "ready": False}, "active": True, "classes": {},
        "own": True,
    })


def setup(live=None):
    a = Adapter2()
    a.eng = a.Engine.new(a.b.engine_opts(log=a.log, love=a.b.env.love, world=a.E.worldApi, hooks=a.b.mod.hooks,
                                         items=a.b.module("lib/kb_items.lua")))
    a.eng.setGame(a.E.game)
    make_town(a.E, a.b)
    if live is not None:
        a.bridge = a.b.eval("""function(live)
          local b = { facts = {}, plans = 0, live = function() return live end }
          b.battle = function(facts)
            b.facts[#b.facts + 1] = facts
            b.plans = b.plans + 1
            return { id = "b-test-" .. b.plans, kind = "trainer", data = { battle = "b-test-" .. b.plans } }
          end
          return b
        end""")(live)
        a.eng.attachBridge(a.bridge)
    return a


def installed(live=None):
    a = setup(live)
    z = zone_record(a)
    assert a.eng.routeInstall(z, a.E.content) is True
    return a, z


def writes(a):
    w = a.E.contentWrites
    return [(w[i].registry.decode(), w[i].op.decode(), w[i].id.decode()) for i in range(1, len(w) + 1)]


def block(a, map_id, bx, by, w=W):
    return a.E.maps[map_id.encode()].blocks[by * w + bx + 1]


def recorder(a):
    return a.b.eval("""function()
      local r = { calls = {} }
      r.fn = function(outcome) r.calls[#r.calls + 1] = outcome == nil and "nil" or outcome end
      return r
    end""")()


def calls(r):
    return [c.decode() for c in r.calls.values()]


def top(a):
    return a.E.game.stack.top(a.E.game.stack)


def tap(a):
    a.press("a")
    a.tick()
    a.release("a")
    a.tick()


# ------------------------------------------------------------ the end


def test_the_clearing_is_planned_on_the_towns_blocks_and_the_route_map_registered():
    a, z = installed()
    p = z.plan
    assert p.ready is True and z.active is True
    assert plain(p.cleared) == [[3, 4], [3, 5]]
    assert plain(p.triggerList) == [[6, 11], [7, 11]]
    assert plain(p.back) == {"x": 6, "y": 10, "facing": "up"} and p.exitFacing == b"down"
    assert writes(a) == [("maps", "register", ROUTE_MAP), ("maps", "patch", TOWN)]
    # the clearing is the town's own now (the pristine blocks every load restores)
    assert block(a, TOWN, 3, 4) == FLOOR and block(a, TOWN, 3, 5) == FLOOR
    assert block(a, TOWN, 2, 4) == TREES and block(a, TOWN, 4, 5) == TREES, "the trees beside it stay"
    rm = a.E.maps[ROUTE_MAP.encode()]
    assert (rm.id, rm.width, rm.height, plain(rm.blocks)) == (ROUTE_MAP.encode(), 1, 1, [FLOOR])
    assert (rm.tileset, rm.environment, rm.borderBlock, rm.outdoor) == (b"TILESET_TEST", b"TOWN", 1, True)
    assert rm.group is None and rm.map is None, "nothing that looks maps up by group and number finds it"
    for field in ("warps", "objects", "signs", "connections", "coordEvents", "bgEvents", "callbacks"):
        assert plain(getattr(rm, field)) in ({}, []), field
    assert a.eng.routePlan() == 1


@pytest.mark.parametrize("spot,code", [
    ({"x": 6, "y": 8}, "spot_unwalkable"),                 # the exit on the trees
    ({"x": 6, "y": 2}, "not_trees"),                       # floor beyond the exit
    ({"x": 6, "y": 6, "depth": 3}, "outside_map"),         # past the map's last row
    ({"x": 14, "y": 6, "facing": "right"}, "outside_map"),  # past its east edge
    ({"x": 6, "y": 6, "facing": "sideways"}, "bad_spot"),
    ({"town": "G2_TEST_NOPE"}, "no_map"),
])
def test_a_spot_that_does_not_check_out_is_refused(spot, code):
    a = setup()
    z = zone_record(a, **spot)
    assert tuple(a.eng.routeInstall(z, a.E.content)) == (False, code.encode())
    assert writes(a) == [] and z.plan.ready is False and a.E.maps[ROUTE_MAP.encode()] is None


def test_a_warp_or_an_object_on_the_clearing_refuses_it():
    a = setup()
    a.E.maps[TOWN.encode()].warps[1] = a.lua({"x": 6, "y": 9, "destMap": "TEST_HOUSE", "destWarp": 1})
    assert tuple(a.eng.routeInstall(zone_record(a), a.E.content)) == (False, b"occupied")
    b2 = setup()
    b2.E.maps[TOWN.encode()].objects[1] = b2.lua({"x": 7, "y": 6, "sprite": "SPRITE_TEST_LASS"})
    assert tuple(b2.eng.routeInstall(zone_record(b2), b2.E.content)) == (False, b"occupied"), "on the exit block"


def test_a_slim_route_map_when_the_registry_refuses_the_whole_record():
    a = setup()
    a.E.refuseFullMaps = True
    assert a.eng.routeInstall(zone_record(a), a.E.content) is True
    rm = a.E.maps[ROUTE_MAP.encode()]
    assert rm.callbacks is None and rm.environment == b"TOWN" and rm.tileset == b"TILESET_TEST"
    assert writes(a) == [("maps", "register", ROUTE_MAP), ("maps", "patch", TOWN)]


def test_a_refused_patch_undoes_the_route_map_and_a_second_install_finds_it_taken():
    a = setup()
    a.E.rejectPatch = True
    assert tuple(a.eng.routeInstall(zone_record(a), a.E.content)) == (False, b"patch_rejected")
    assert a.E.maps[ROUTE_MAP.encode()] is None and block(a, TOWN, 3, 4) == TREES
    assert writes(a) == [("maps", "register", ROUTE_MAP), ("maps", "remove", ROUTE_MAP)]
    a.E.rejectPatch = False
    assert a.eng.routeInstall(zone_record(a), a.E.content) is True
    assert tuple(a.eng.routeInstall(zone_record(a, key="again"), a.E.content)) == (False, b"map_taken")


def test_a_hot_reload_finds_its_own_route_map_and_clearing():
    # `maps` writes into the live data, which outlives the mod instance: the next instance's install plans on the
    # town as it stands (its clearing already open) and registers nothing
    a, _ = installed()
    n = len(writes(a))
    again = a.new_instance()
    z = zone_record(a)
    assert again.routeInstall(z, a.E.content) is True
    assert plain(z.plan.cleared) == [[3, 4], [3, 5]] and plain(z.plan.triggerList) == [[6, 11], [7, 11]]
    assert len(writes(a)) == n, "no second route map, no second patch"
    assert z.plan.source == FLOOR


# ------------------------------------------------------------ the steps


def test_the_trigger_warps_onto_x1_and_a_warps_back_facing_the_town():
    a, _ = installed()
    w = a.field(TOWN, 6, 10, "down")
    a.eng.routeStepped()
    assert len(a.E.queued) == 0, "the back cell isn't a trigger"
    w.player.cellY = 11
    a.eng.routeStepped()
    rows = plain(a.E.queued[1])
    assert rows == [["warp", ROUTE_MAP, 1, 0, "right"]], "the engine's own warp, queued"
    w = a.E.game.world
    assert (w.map.id, w.player.cellX, w.player.cellY, w.player.facing) == (ROUTE_MAP.encode(), 1, 0, b"right")
    a.eng.routeStepped()
    assert len(a.E.queued) == 1, "x1 is no trigger"
    w.player.cellX = 0
    a.eng.routeStepped()
    assert plain(a.E.queued[2]) == [["warp", TOWN, 6, 10, "up"]]
    w = a.E.game.world
    assert (w.map.id, w.player.cellX, w.player.cellY, w.player.facing) == (TOWN.encode(), 6, 10, b"up")
    counts = plain(a.eng.routeCounts())
    assert (counts["warpsOut"], counts["warpsBack"], counts["busy"]) == (1, 1, 0)


# ------------------------------------------------------------ the acts


def test_a_zone_item_goes_into_the_bag_with_a_found_line():
    a, _ = installed()
    a.field(ROUTE_MAP, 1, 0, "right")
    a.E.game.save.player.name = b"KRIS"
    done = recorder(a)
    req = a.lua({"op": "give", "item": "POTION"})
    req.onDone = done.fn
    assert a.eng.routeRun(b"give", req) is True
    assert a.E.game.save.inventory.POTION == 1
    assert top(a).pages[1][1] == b"KRIS found POTION!"
    assert calls(done) == []
    tap(a)
    assert calls(done) == ["nil"], "after the line"
    bad = a.lua({"op": "give", "item": "NOT_AN_ITEM"})
    bad.onDone = done.fn
    assert tuple(a.eng.routeRun(b"give", bad)) == (False, b"unknown_item")


def battle_req(a, done, cls="YOUNGSTER", party=None, line=None):
    req = a.lua({"op": "battle", "class": cls, "line": line,
                 "party": party or [{"species": "TEST_RATTATA", "level": 5, "moves": ["TEST_TACKLE", "NOT_A_MOVE"]},
                                    {"species": "TEST_CHIKORITA", "level": 7}]})
    req.onDone = done.fn
    return req


def test_a_zone_battle_takes_the_classs_first_trainer_with_the_requests_party():
    a, _ = installed()
    a.field(ROUTE_MAP, 1, 0, "right")
    done = recorder(a)
    assert a.eng.routeRun(b"battle", battle_req(a, done, line="TEST ZONE LINE")) is True
    assert top(a).pages[1][1] == b"TEST ZONE LINE", "the trainer's line first"
    tap(a)
    start = a.E.battleStarts[1]
    tr = start.trainer
    assert (tr.classId, tr.memberId, tr.baseMoney) == (b"YOUNGSTER", b"JOEY1", 4), "the class's first trainer"
    party = [(m.species, m.level, m.dvs.attack, m.dvs.defense) for m in tr.party.values()]
    assert party == [(b"TEST_RATTATA", 5, 9, 8), (b"TEST_CHIKORITA", 7, 9, 8)], "the request's, at 9/8/8/8"
    assert [mv.id for mv in tr.party[1].moves.values()] == [b"TEST_TACKLE"], "unknown moves left out"
    assert start.battleType is None, "a loss whites out (not CANLOSE)"
    a.tick(2)  # the transition, then the battle screen (native: no client follows)
    a.E.finishNative(b"win")
    assert calls(done) == ["win"]


@pytest.mark.parametrize("cls,party,code", [
    ("NOT_A_CLASS", None, "unknown_class"),
    ("YOUNGSTER", [{"species": "NOT_A_MON", "level": 5}], "bad_party"),
    ("YOUNGSTER", [{"species": "TEST_RATTATA", "level": 101}], "bad_party"),
])
def test_a_zone_battle_with_an_unknown_class_or_party_is_refused(cls, party, code):
    a, _ = installed()
    a.field(ROUTE_MAP, 1, 0, "right")
    assert tuple(a.eng.routeRun(b"battle", battle_req(a, recorder(a), cls=cls, party=party))) == (False, code.encode())
    assert len(a.E.battleStarts) == 0


def test_a_held_zone_battle_goes_to_cobblemon_and_its_outcome_comes_back():
    a, _ = installed(live=True)
    a.field(ROUTE_MAP, 1, 0, "right")
    done = recorder(a)
    assert a.eng.routeRun(b"battle", battle_req(a, done)) is True
    facts = plain(a.bridge.facts[1])
    assert facts == {"path": "script", "cls": "YOUNGSTER", "party": 1, "rival": False, "map": ROUTE_MAP}
    team, _, base = a.eng.trainerTeam(b"YOUNGSTER", 1)
    assert [(m["dex"], m["level"]) for m in plain(team)] == [(19, 5), (152, 7)] and base == 4
    assert a.eng.battleFinish(b"b-test-1", b"lose") is True
    a.tick()
    assert calls(done) == ["lose"] and a.E.blackouts == 1, "a zone loss whites out as a trainer's does"


def test_a_blackout_heals_halves_the_money_and_warps_to_the_spawn_point():
    a = setup()
    assert tuple(a.eng.blackout()) == (False, b"not_ingame")
    a.field("TEST_TOWN", 5, 6, "down")
    a.E.game.save.player.money = 3001
    assert a.eng.blackout() is True
    assert a.E.game.save.player.money == 1500 and a.E.heals == 1
    assert [x.decode() for x in a.E.calledAsm.values()] == ["HalveMoney", "GetWhiteoutSpawn"]
    assert plain(a.E.mapSetups) == [0xF1]
    w = a.E.game.world
    assert (w.map.id, w.player.cellX, w.player.cellY) == (b"TEST_CENTER", 2, 3)
    a.E.bugContest = True
    assert a.eng.blackout() is True
    assert a.E.game.save.player.money == 1500, "the contest costs nothing"


# ------------------------------------------------------------ main.lua and a peer

ROUTES = {"version": 2, "routes": [{
    "id": "test_pass", "name": "TEST PASS", "kind": "connecting", "theme": "forest",
    "from": {"region": "kanto", "games": ["red", "blue"], "map": "TEST_WOODS", "x": 6, "y": 4, "facing": "up", "depth": 2},
    "to": {"region": "johto", "games": ["crystal"], "map": G2_TOWN, "x": 6, "y": 6, "facing": "down", "depth": 2,
           "classes": ["YOUNGSTER"], "music": None},
    "areas": {"main": [2, 3], "side": 50}, "levels": {"from": "neighbours", "perArea": 1, "maxRise": 3},
    "trainers": {"perArea": [1, 2], "rematch": "each_visit"}, "dungeon": {"chance": 0}}]}


def rig(**kw):
    r = Rig(family="gen2", files={"data/routes.json": json.dumps(ROUTES)}, prepare=lambda E, b: make_town(E, b), **kw)
    return r


def pump(r, peer, until, limit=40):
    got = []
    for _ in range(limit):
        r.step()
        got.extend(peer.messages())
        if any(until(m) for m in got):
            return got
    raise AssertionError(f"not within {limit} steps: {[m['type'] for m in got]}")


def no_faults(r):
    faults = [line for line in r.logs()
              if "step_failed" in line or "[error]" in line or ("hook" in line and "failed" in line)]
    assert not faults, faults


def test_main_installs_the_end_and_follows_the_hidden_player_through_the_clearing_and_back():
    r = rig()
    r.world(TOWN, 6, 8, "down")
    st = r.status
    peer = r.connect()
    peer.hello()
    pump(r, peer, lambda m: m["type"] == "snapshot")
    assert st.routesInstalled == 1, "the route's Crystal end, installed by the adapter in the entry chunk"
    # down the clearing to its trigger, a step at a time: the engine's steps, then world.stepped
    got = []
    for n in (1, 2, 3):
        peer.send("move.step", {"dir": "down", "n": n})
        got += pump(r, peer, lambda m, n=n: m["data"].get("n") == n, limit=60)
        assert next(m for m in got if m["data"].get("n") == n)["type"] == "player.pos", n
    if not any(m["type"] == "warp" and m["data"]["phase"] == "arrive" for m in got):
        got += pump(r, peer, lambda m: m["type"] == "warp" and m["data"]["phase"] == "arrive", limit=120)
    arrive = next(m for m in got if m["type"] == "warp" and m["data"]["phase"] == "arrive")["data"]
    assert arrive["kind"] == "script" and arrive["to"] == {"map": ROUTE_MAP, "x": 1, "y": 0, "facing": "right"}
    # the lower row is closed (the core's movement.collision wrapper on a route map)
    seq_down = 4
    peer.send("move.step", {"dir": "down", "n": seq_down})
    got = pump(r, peer, lambda m: m["data"].get("n") == seq_down)
    blocked = next(m for m in got if m["data"].get("n") == seq_down)
    assert blocked["type"] == "player.blocked"
    # the way back: a step left onto A
    peer.send("move.step", {"dir": "left", "n": 5})
    got = pump(r, peer, lambda m: m["type"] == "warp" and m["data"]["phase"] == "arrive", limit=120)
    back = next(m for m in got if m["type"] == "warp" and m["data"]["phase"] == "arrive")["data"]
    assert back["to"] == {"map": G2_TOWN, "x": 6, "y": 10, "facing": "up"} and back["kind"] == "script"
    no_faults(r)


def answer_until(r, peer, until, limit=200):
    """Steps, advancing every text box the hidden game opens, until a message satisfies ``until``."""
    got, answered = [], set()
    for _ in range(limit):
        r.step()
        new = peer.messages()
        got.extend(new)
        for m in new:
            if m["type"] == "ui.open" and m["data"]["ui"] not in answered and m["data"]["kind"] == "text":
                answered.add(m["data"]["ui"])
                peer.send("ui.advance", {"ui": m["data"]["ui"]})
        if any(until(m) for m in got):
            return got
    raise AssertionError(f"not within {limit} steps: {[m['type'] for m in got]}")


def test_main_runs_the_zones_acts_on_the_route_map():
    # the server names the route only; the hidden game holds its `to` end (keyed "<id>:to" by kb_routes): the acts
    # find it by the route map the hidden player stands on
    r = rig()
    r.world(ROUTE_MAP, 1, 0, "right")
    mon = r.E.Mon.new(r.E.data, b"TEST_CHIKORITA", 8)
    r.E.game.save.party[1] = mon
    peer = r.connect()
    peer.hello()
    pump(r, peer, lambda m: m["type"] == "snapshot")
    peer.send("route.give", {"route": "test_pass", "id": "b1", "item": "POTION"})
    # the found line waits for its press well past the act's stale count (an own run's steps count only while the
    # world stands free): no answer before the box is closed
    got = []
    for _ in range(80):
        r.step()
        got.extend(peer.messages())
    assert any(m["type"] == "ui.open" for m in got) and not any(m["type"] == "route.result" for m in got)
    box = next(m["data"]["ui"] for m in got if m["type"] == "ui.open")
    peer.send("ui.advance", {"ui": box})
    got = answer_until(r, peer, lambda m: m["type"] == "route.result")
    result = next(m for m in got if m["type"] == "route.result")["data"]
    assert result == {"op": "give", "ok": True, "route": "test_pass", "id": "b1"}
    assert r.E.game.save.inventory.POTION == 1

    peer.send("route.battle", {"route": "test_pass", "id": "t1", "class": "YOUNGSTER",
                               "party": [{"species": "TEST_RATTATA", "level": 4}]})
    got = answer_until(r, peer, lambda m: m["type"] == "battle.start")
    start = next(m for m in got if m["type"] == "battle.start")["data"]
    assert start["trainer"]["cls"] == "YOUNGSTER" and [(m["dex"], m["level"]) for m in start["team"]] == [(19, 4)]
    peer.send("battle.result", {"battle": start["battle"], "outcome": "win"})
    got = answer_until(r, peer, lambda m: m["type"] == "route.result")
    result = next(m for m in got if m["type"] == "route.result")["data"]
    assert result == {"op": "battle", "ok": True, "route": "test_pass", "id": "t1", "outcome": "win"}
    no_faults(r)


def test_main_resumes_a_parked_game_onto_the_route_map():
    r = rig()
    r.world(TOWN, 6, 6, "down")
    peer = r.connect()
    peer.hello()
    pump(r, peer, lambda m: m["type"] == "snapshot")
    parked = peer.send("region.park", {"region": "johto"})
    got = pump(r, peer, lambda m: m.get("re") == parked)
    assert next(m for m in got if m.get("re") == parked)["data"] == {"ok": True, "kind": "park"}
    seq = peer.send("region.resume", {"region": "johto", "to": {"map": ROUTE_MAP, "x": 1, "y": 0, "facing": "right"}})
    got = pump(r, peer, lambda m: m.get("re") == seq, limit=80)
    assert next(m for m in got if m.get("re") == seq)["data"] == {"ok": True, "kind": "resume"}
    w = r.E.game.world
    assert (w.map.id, w.player.cellX, w.player.cellY) == (ROUTE_MAP.encode(), 1, 0)
    no_faults(r)


def test_the_export_names_the_end_and_bakes_the_clearing_in():
    r = rig(ready=False)
    r.b.mods_loaded()
    r.b.ready()
    r.world("TEST_TOWN", 5, 6, "down")
    peer = r.connect()
    peer.hello(role="builder")
    peer.messages()
    seq = peer.send("export.index", {})
    got = []
    for i in range(3000):
        if i % 120 == 119:
            peer.send("ping", {"t": i})
        r.step()
        got += [m for m in peer.messages() if m["type"] != "pong"]
        if any(m.get("re") == seq for m in got):
            break
    index = next(m for m in got if m.get("re") == seq)["data"]
    assert {"routes", "g2map"} <= {e["kind"] for e in index["resources"]}
    assert not any(e["kind"] == "g2map" and e["id"].startswith("KB_") for e in index["resources"]), \
        "the route map is the builder's to place (the routes resource), not one of the game's maps"

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
    assert item["ends"]["to"] == {"region": "johto", "games": ["crystal"]}
    assert item["town"] == {"map": G2_TOWN, "cleared": [[3, 4], [3, 5]], "triggers": [[6, 11], [7, 11]],
                            "back": {"x": 6, "y": 10, "facing": "up"}}
    assert item["routeMap"]["id"] == ROUTE_MAP and item["trainers"]["classes"] == ["YOUNGSTER"]
    town = fetch("g2map", G2_TOWN)
    raw = base64.b64decode(town["blocks"])
    assert (town["w"], town["h"]) == (W, H)
    assert raw[4 * W + 3] == FLOOR and raw[5 * W + 3] == FLOOR, "the clearing, as the hidden game runs it"
    assert raw[4 * W + 2] == TREES
