"""Wild Routes (WR2): the route list, the exit patched into a town, the route map, the step scripts that
lead out and back (lib/kb_routes.lua), and the acts route.give, route.battle and route.blackout with the
zones' progress (act/kb_route.lua), on the fake engine (routesim.py). Every message the peer receives is
schema-checked (rig.Peer, strict). Everything is made up: maps, tiles, trainers, items and text."""

import json

import pytest

import routesim as R
from kblua import Bridge

pytestmark = pytest.mark.lua


# ------------------------------------------------------------ helpers


def routes_module():
    b = Bridge()
    return b, b.module("lib/kb_routes.lua"), b.module("lib/kb_json.lua")


def parse(entries):
    b, Routes, js = routes_module()
    text = json.dumps({"version": 1, "routes": entries}).encode()
    out, problems = Routes.parse(js, text)
    got = [out[i] for i in range(1, len(out) + 1)]
    probs = [(p.id.decode() if p.id else None, p.code.decode()) for p in (problems[i] for i in range(1, len(problems) + 1))]
    return got, probs


def zone(**changes):
    z = json.loads(json.dumps(R.ZONE))
    for k, v in changes.items():
        if k in ("x", "y", "facing", "depth", "map", "games"):
            z["from"][k] = v
        else:
            z[k] = v
    return z


def lua_list(t):
    return [t[i] for i in range(1, len(t) + 1)]


def cells(t):
    return [tuple(lua_list(c)) for c in lua_list(t)]


def lua_rows(t):
    return R.lua_rows(t)


def plan(b, Routes, js, z, town=None):
    """Routes.plan on TOWN with a walkability query over its blocks (the engine's bottom-left-tile
    rule, here every tile of a block is the same)."""
    town = town or R.TOWN
    w = town["width"]
    walk = {R.B_PATH}
    q = b.eval("""function(blocks, w, walk, water)
      local function block(cx, cy) return blocks[math.floor(cy / 2) * w + math.floor(cx / 2) + 1] end
      return { walkable = function(cx, cy) return walk[block(cx, cy)] == true end,
               water = function(cx, cy) return block(cx, cy) == water end }
    end""")(b.to_lua(town["blocks"]), w, b.to_lua({k: True for k in walk}), R.B_WATER)
    text = json.dumps({"version": 1, "routes": [z]}).encode()
    entries, _ = Routes.parse(js, text)
    occupied = Routes.occupiedCells(b.to_lua(town), None)
    return Routes.plan(entries[1], b.to_lua(town), q, occupied)


# ------------------------------------------------------------ the route list


def test_the_shipped_list_has_viridians_zone_waiting_for_its_spot():
    b, Routes, js = routes_module()
    from kblua import BRIDGE
    out, problems = Routes.parse(js, (BRIDGE / "data" / "routes.json").read_bytes())
    assert len(out) == 1 and len(problems) == 0
    e = out[1]
    assert e.id == b"viridian_forest_zone" and e.unset is True and e.mapId == b"KB_ROUTE_VIRIDIAN_FOREST_ZONE"
    assert e[b"from"].map == b"VIRIDIAN_CITY" and e[b"from"].depth == 2
    assert e[b"from"].games[b"red"] and e[b"from"].games[b"blue"]
    assert [c.decode() for c in lua_list(e.classes)] == [
        "OPP_YOUNGSTER", "OPP_BUG_CATCHER", "OPP_LASS", "OPP_JR_TRAINER_M", "OPP_JR_TRAINER_F"]


@pytest.mark.parametrize("change,code", [
    ({"id": "Bad-Id"}, "bad_id"),
    ({"name": ""}, "bad_name"),
    ({"kind": "connecting"}, "kind_later"),
    ({"kind": "teleporter"}, "bad_kind"),
    ({"theme": "cave"}, "theme_later"),
    ({"map": "not a map"}, "bad_from"),
    ({"games": []}, "bad_games"),
    ({"depth": 9}, "bad_depth"),
    ({"x": 2, "y": None, "facing": "up"}, "bad_spot"),
    ({"facing": "north"}, "bad_spot"),
    ({"areas": {"main": [4, 3]}}, "bad_areas"),
    ({"trainers": {"rematch": "never"}}, "bad_trainers"),
    ({"trainers": {"classes": ["lower"]}}, "bad_classes"),
    ({"dungeon": {"floors": 5}}, "bad_dungeon"),
    ({"music": "no spaces allowed"}, "bad_music"),
])
def test_entries_that_dont_check_out_are_left_out_with_a_code(change, code):
    got, probs = parse([zone(**change)])
    assert got == [] and len(probs) == 1 and probs[0][1] == code


def test_duplicate_ids_and_bad_files():
    got, probs = parse([zone(), zone()])
    assert len(got) == 1 and probs == [("test_zone", "duplicate_id")]
    b, Routes, js = routes_module()
    for text in (b"not json", json.dumps({"version": 2, "routes": []}).encode(), None):
        out, problems = Routes.parse(js, text)
        assert len(out) == 0 and problems[1].code in (b"bad_file", b"no_file")


# ------------------------------------------------------------ the exit's block maths


def test_the_clearing_runs_from_the_spot_through_the_trees():
    b, Routes, js = routes_module()
    p = plan(b, Routes, js, zone())
    assert cells(p.cleared) == [(1, 0)]
    assert p.source == R.B_PATH and p.width == 6
    blocks = lua_list(p.blocks)
    assert blocks[0 * 6 + 1] == R.B_PATH and blocks.count(R.B_PATH) == R.TOWN["blocks"].count(R.B_PATH) + 1
    assert sorted(cells(p.triggerList)) == [(2, 0), (3, 0)]
    assert (p.back.x, p.back.y, p.back.facing) == (2, 1, b"down")
    assert p.exitFacing == b"up"


def test_the_clearing_goes_any_way_the_spot_faces():
    b, Routes, js = routes_module()
    p = plan(b, Routes, js, zone(x=9, y=4, facing="right"))  # block (4, 2) -> (5, 2)
    assert cells(p.cleared) == [(5, 2)] and sorted(cells(p.triggerList)) == [(11, 4), (11, 5)]
    assert (p.back.x, p.back.y, p.back.facing) == (10, 4, b"left")
    p = plan(b, Routes, js, zone(x=2, y=7, facing="left"))  # block (1, 3) -> (0, 3)
    assert cells(p.cleared) == [(0, 3)] and sorted(cells(p.triggerList)) == [(0, 6), (0, 7)]
    assert (p.back.x, p.back.y, p.back.facing) == (1, 6, b"right")


@pytest.mark.parametrize("change,code", [
    ({"depth": 2}, "outside_map"),                       # up from block (1, 1) two blocks: off the map
    ({"x": 6, "y": 6, "facing": "left"}, "spot_unwalkable"),  # standing on the pond's block
    ({"x": 2, "y": 4, "facing": "down"}, "not_trees"),   # block (1, 3) below is path
    ({"x": 4, "y": 4, "facing": "down"}, "not_trees"),   # block (2, 3)... path; (3, 3) water is refused too
    ({"x": 8, "y": 2, "facing": "up"}, "occupied"),      # block (4, 0) holds the sign at (8, 0)
    ({"x": 40, "y": 2}, "spot_outside"),
])
def test_signature_checks_refuse_a_bad_spot(change, code):
    b, Routes, js = routes_module()
    p, why = plan(b, Routes, js, zone(**change))
    assert p is None and why.decode() == code


# ------------------------------------------------------------ boot: registrations


def test_boot_registers_the_route_map_scripts_class_song_then_the_patch():
    g = R.RouteGame()
    assert g.status()["routesInstalled"] == 1
    maps = g.writes("maps")
    assert [(w.op.decode(), w.id.decode()) for w in maps] == [("register", R.ROUTE_MAP), ("patch", "TEST_WOODS")]
    rm = maps[0].value
    assert (rm.width, rm.height, rm.tileset, lua_list(rm.blocks), rm.borderBlock) == (
        1, 1, b"TEST_WOODS_TILES", [R.B_PATH], R.B_TREE)
    patched = lua_list(maps[1].value.blocks)
    assert patched[1] == R.B_PATH and len(patched) == 30
    assert [w.id.decode() for w in g.writes("map_scripts")] == [R.ROUTE_MAP, "TEST_WOODS"]
    trainers = g.writes("trainers")
    assert [(w.op.decode(), w.id.decode()) for w in trainers] == [("register", "KB_OPP_TEST")]
    kb = trainers[0].value
    assert kb.name == b"TESTER" and kb.baseMoney == 15 and kb.basePic == b"OPP_TEST"
    assert len(lua_list(kb.parties)) == 1 and kb.parties[1][1].species == b"BIRDMON"
    assert g.record("trainers", "OPP_TEST").parties[2] is not None, "the vanilla class is untouched"
    songs = g.writes("map_songs")
    assert [(w.id.decode(), w.value.decode()) for w in songs] == [(R.ROUTE_MAP, "TEST_SONG")]
    st = g.status()
    assert (st["routesListed"], st["routesSkipped"], st["routesClasses"]) == (1, 0, 1)


def test_a_zone_for_another_game_or_without_a_spot_registers_nothing():
    other = zone(games=["gold"])
    unset = zone(id="later_zone", x=None, y=None, facing=None)
    g = R.RouteGame(zones=[other, unset])
    st = g.status()
    assert (st["routesInstalled"], st["routesOtherGame"], st["routesUnset"]) == (0, 1, 1)
    assert g.writes("maps") == [] and g.writes("map_scripts") == []


def test_a_rejected_route_map_skips_the_zone():
    g = R.RouteGame(reject={"maps": True})
    st = g.status()
    assert st["routesInstalled"] == 0 and st["routesSkipped"] == 1
    assert g.writes("map_scripts") == [] and g.writes("trainers") == []
    assert any("routes.skipped" in line and "map_rejected" in line for line in g.rig.logs())


def test_rejected_scripts_undo_the_route_map_and_leave_the_town_alone():
    g = R.RouteGame(reject={"map_scripts": True})
    assert g.status()["routesInstalled"] == 0
    assert [(w.op.decode(), w.id.decode()) for w in g.writes("maps")] == [("register", R.ROUTE_MAP), ("remove", R.ROUTE_MAP)]
    assert g.record("maps", R.ROUTE_MAP) is None


def test_a_bad_spot_skips_the_zone_with_its_code():
    g = R.RouteGame(zones=[zone(x=2, y=4, facing="down")])
    assert g.status()["routesInstalled"] == 0
    assert any("routes.skipped" in line and "not_trees" in line for line in g.rig.logs())


# ------------------------------------------------------------ out and back


def test_the_clearings_end_leads_to_the_route_map_and_its_a_leads_back():
    g = R.RouteGame(start=("TEST_WOODS", 2, 1, "up"))
    assert g.step_script("TEST_WOODS", 2, 1) is False, "only the clearing's last cells lead out"
    assert g.step_script("TEST_WOODS", 3, 0) is True
    assert g.queued() == [[["warp", R.ROUTE_MAP, 1, 0, "right"]]]
    g.until(lambda m: m["type"] == "warp" and m["data"]["phase"] == "arrive")
    assert g.where() == (R.ROUTE_MAP, 1, 0)
    assert g.step_script(R.ROUTE_MAP, 1, 0) is False
    assert g.step_script(R.ROUTE_MAP, 0, 0) is True
    assert g.queued()[-1] == [["warp", "TEST_WOODS", 2, 1, "down"]]
    g.until(lambda m: m["type"] == "warp" and m["data"]["phase"] == "arrive")
    assert g.where() == ("TEST_WOODS", 2, 1)
    st = g.status()
    assert (st["routesWarpsOut"], st["routesWarpsBack"]) == (1, 1)
    warps = [m["data"] for m in g.received("warp") if m["data"]["phase"] == "arrive"]
    assert [w["kind"] for w in warps][-2:] == ["script", "script"]


def test_the_route_maps_lower_row_is_closed_like_a_wall():
    g = R.RouteGame()
    g.send("move.step", {"dir": "down", "n": 1})
    blocked = g.until(lambda m: m["type"] in ("player.pos", "player.blocked") and m["data"].get("n") == 1)
    assert blocked["type"] == "player.blocked"
    assert (blocked["data"]["reason"], blocked["data"]["map"], blocked["data"]["x"], blocked["data"]["y"]) == (
        "tile", R.ROUTE_MAP, 1, 0)
    assert g.where() == (R.ROUTE_MAP, 1, 0)
    assert g.status()["routesClosed"] >= 1
    # the town's cells are the engine's own: the same step there walks
    t = R.RouteGame(start=("TEST_WOODS", 2, 2, "down"))
    t.send("move.step", {"dir": "down", "n": 1})
    walked = t.until(lambda m: m["type"] in ("player.pos", "player.blocked") and m["data"].get("n") == 1)
    assert walked["type"] == "player.pos" and t.status()["routesClosed"] == 0


def test_the_way_out_waits_while_a_script_runs():
    g = R.RouteGame(start=("TEST_WOODS", 2, 1, "up"))
    g.script([["show_text", "WAIT A MOMENT."]])
    g.run(2)
    assert g.step_script("TEST_WOODS", 2, 0) is False
    assert g.status()["routesWarpsBusy"] == 1


# ------------------------------------------------------------ route.give


def give(g, item="POTION", oid="a1.i1", route="test_zone"):
    g.send("route.give", {"route": route, "id": oid, "item": item})
    g.read_texts(until=lambda g: any(r.get("id") == oid and r["op"] == "give" for r in g.results()))
    return [r for r in g.results() if r.get("id") == oid][-1]


def test_a_ball_gives_its_item_through_the_engines_give_item_once():
    g = R.RouteGame()
    before = g.E.save.inventory[b"POTION"] or 0
    assert give(g) == {"op": "give", "ok": True, "route": "test_zone", "id": "a1.i1"}
    assert g.E.save.inventory[b"POTION"] == before + 1
    assert g.queued()[-1] == [["give_item", "POTION", 1]]
    assert g.progress()[-1] == {"test_zone": {"taken": ["a1.i1"], "wins": {}}}
    stored = g.b.mod._save[b"routes"][b"test_zone"]
    assert stored.taken[b"a1.i1"] is True
    again = give(g)
    assert again["ok"] is False and again["reason"] == "taken"
    assert g.E.save.inventory[b"POTION"] == before + 1
    st = g.status()
    assert (st["routeGiven"], st["routeOk"], st["routeFailed"]) == (1, 1, 1)


def test_a_dungeon_item_is_given_without_being_recorded():
    g = R.RouteGame()
    before = g.E.save.inventory[b"POTION"] or 0
    g.send("route.give", {"route": "test_zone", "id": "v1.f2.i1", "item": "POTION", "keep": False})
    g.read_texts(until=lambda g: any(r.get("id") == "v1.f2.i1" for r in g.results()))
    assert [r for r in g.results() if r.get("id") == "v1.f2.i1"][-1]["ok"] is True
    assert g.E.save.inventory[b"POTION"] == before + 1
    assert not any("v1.f2.i1" in p.get("test_zone", {}).get("taken", []) for p in g.progress()), "not recorded"
    # the same id again: given again (a new visit's item)
    g.send("route.give", {"route": "test_zone", "id": "v1.f2.i1", "item": "POTION", "keep": False})
    g.read_texts(until=lambda g: sum(1 for r in g.results() if r.get("id") == "v1.f2.i1") == 2)
    assert g.E.save.inventory[b"POTION"] == before + 2


def test_a_full_bag_keeps_the_ball():
    g = R.RouteGame()
    g.E.bagFull = True
    r = give(g)
    assert r["ok"] is False and r["reason"] == "bag_full"
    assert g.progress() == []


@pytest.mark.parametrize("kw,reason", [
    ({"route": "no_such_zone"}, "unknown_route"),
    ({"item": "NO_SUCH_ITEM"}, "unknown_item"),
])
def test_give_refusals(kw, reason):
    g = R.RouteGame()
    r = give(g, **kw)
    assert r["ok"] is False and r["reason"] == reason


def test_a_script_that_dies_settles_the_request_as_failed():
    g = R.RouteGame()
    # the runner loses its script without ever calling onDone (what a script error does in the engine)
    g.b.eval("function(C) C.give_item = function(ctx) ctx.runner.co = nil coroutine.yield() end end")(g.E.Commands)
    for n, oid in enumerate(("a1.i1", "a1.i2"), start=1):
        g.send("route.give", {"route": "test_zone", "id": oid, "item": "POTION"})
        r = g.until(lambda m: m["type"] == "route.result" and m["data"].get("id") == oid)["data"]
        assert r["ok"] is False and r["reason"] == "failed", "and the next request isn't stuck behind it"
        assert g.status()["routeStale"] == n


def test_give_needs_the_hidden_player_on_the_route_map():
    g = R.RouteGame(start=("TEST_WOODS", 4, 4, "down"))
    r = give(g)
    assert r["ok"] is False and r["reason"] == "not_in_zone"


def test_malformed_requests_are_errors():
    g = R.RouteGame()
    seq = g.peer.send("route.give", {"route": "test_zone", "id": "a1.i1", "item": "lower"})
    err = g.until(lambda m: m["type"] == "error" and m.get("re") == seq)
    assert err["data"]["code"] == "bad_message"


# ------------------------------------------------------------ route.battle


def battle(g, outcome, cls="OPP_TEST", party=None, oid="a2.t1"):
    """Sends route.battle and answers its battle.start with `outcome`; returns (battle.start data or
    None, the route.result)."""
    party = party or [{"species": "BIRDMON", "level": 7}]
    starts0 = len(g.received("battle.start"))
    results0 = len([r for r in g.results() if r["op"] == "battle"])

    def answered(g):
        return len([r for r in g.results() if r["op"] == "battle"]) > results0

    g.send("route.battle", {"route": "test_zone", "id": oid, "class": cls, "party": party,
                            "lines": {"before": "THE WOODS ARE MINE!"}})
    g.read_texts(until=lambda g: len(g.received("battle.start")) > starts0 or answered(g))
    starts = g.received("battle.start")
    if len(starts) == starts0:
        return None, [r for r in g.results() if r["op"] == "battle"][-1]
    start = starts[-1]["data"]
    g.send("battle.result", {"battle": start["battle"], "outcome": outcome})
    g.read_texts(until=answered)
    return start, [r for r in g.results() if r["op"] == "battle"][-1]


def test_a_zone_trainer_fights_through_the_script_battle_and_pays_its_prize():
    g = R.RouteGame()
    g.script([["give_pokemon", "STARTMON_C", 5, True]])
    g.run(3)
    money = g.E.save.money
    start, result = battle(g, "win")
    assert start is not None and start["trainer"]["cls"] == "KB_OPP_TEST" and start["trainer"]["party"] == 1
    assert [m["dex"] for m in start["team"]] == [16]
    assert result == {"op": "battle", "ok": True, "route": "test_zone", "id": "a2.t1", "outcome": "win"}
    assert g.E.save.money == money + 15 * 7
    assert g.progress()[-1] == {"test_zone": {"taken": [], "wins": {"a2.t1": 1}}}
    _, again = battle(g, "win")
    assert again["outcome"] == "win" and g.progress()[-1]["test_zone"]["wins"] == {"a2.t1": 2}, "a rematch counts"


def test_losing_to_a_zone_trainer_blacks_out_the_engines_way():
    g = R.RouteGame()
    g.script([["give_pokemon", "STARTMON_C", 5, True]])
    g.run(3)
    _, result = battle(g, "lose")
    assert result["outcome"] == "lose" and g.progress() == []
    assert any(a.result == b"lose" for a in (g.E.afterBattles[i] for i in range(1, len(g.E.afterBattles) + 1)))


def test_a_class_the_zone_doesnt_have_is_refused():
    g = R.RouteGame()
    start, result = battle(g, "win", cls="OPP_NOBODY")
    assert start is None and result["ok"] is False and result["reason"] == "unknown_class"


# ------------------------------------------------------------ route.blackout, progress


def test_a_lost_wild_battle_blacks_out_anywhere():
    g = R.RouteGame(start=("TEST_WOODS", 4, 4, "down"))
    g.script([["give_pokemon", "STARTMON_C", 5, True]])
    g.run(3)
    g.E.save.money = 1000
    g.send("route.blackout", {})
    g.until(lambda m: m["type"] == "route.result")
    assert g.results()[-1] == {"op": "blackout", "ok": True}
    assert g.E.save.money == 500
    assert g.status()["routeBlackouts"] == 1


def test_the_snapshot_carries_progress_and_a_loaded_save_sends_its_own():
    g = R.RouteGame()
    give(g)
    seq = g.peer.send("resync", {})
    snap = g.until(lambda m: m["type"] == "snapshot")
    assert snap["data"]["routes"] == {"test_zone": {"taken": ["a1.i1"], "wins": {}}}
    # a save with other progress is loaded: it goes out whole
    g.b.mod._save[b"routes"] = g.b.to_lua({"test_zone": {"taken": {"a3.i9": True}, "wins": {"a1.t2": 3}}})
    payload = g.b.table()
    payload[b"save"] = g.E.save
    g.b.emit("save.loaded", payload)
    g.run(3)
    assert g.progress()[-1] == {"test_zone": {"taken": ["a3.i9"], "wins": {"a1.t2": 3}}}
