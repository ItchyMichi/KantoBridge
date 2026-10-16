"""Crystal's story check (tools/e2e/g2_story_check.py, docs/specs/STORY_EVENTS_PLAN.md §5) on made-up scans: the
solver that turns a path's conditions into a story state, the cases (every distinct command of a map once), the
swap-site check and the bridge comparison. Synthetic map ids and numbers only."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "e2e"))

import g2_story_check as g  # noqa: E402

SCAN = {"maps": {
    "TEST_ROOM": {"group": 1, "map": 2, "world": []},
    "OTHER_ROOM": {"group": 1, "map": 3, "world": []},
}}


def ev(eid, op):
    return {"w": {"k": "event", "id": eid}, "op": op}


def test_events_flags_and_temp_flags():
    st = g.solve([ev(100, "~=0"), {"w": {"k": "flag", "id": 7}, "op": "==0"}], "MAPCALLBACK_TILES", {}, "TEST_ROOM", SCAN)
    assert st["events"] == {100: True} and st["flags"] == {7: False}
    # flags 0-7 are cleared on every load: set never holds there, clear always does
    assert g.solve([ev(3, "~=0")], "MAPCALLBACK_TILES", {}, "TEST_ROOM", SCAN) is None
    assert g.solve([ev(3, "==0")], "MAPCALLBACK_TILES", {}, "TEST_ROOM", SCAN)["events"] == {}
    # contradictions
    assert g.solve([ev(100, "~=0"), ev(100, "==0")], "MAPCALLBACK_TILES", {}, "TEST_ROOM", SCAN) is None
    # a comparison an event's 0/1 can't meet
    assert g.solve([{"w": {"k": "event", "id": 100}, "op": "==", "value": 2}], "scene", {"scene": 0}, "TEST_ROOM",
                   SCAN) is None


def test_scenes_map_scenes_and_the_scene_root():
    st = g.solve([], "scene", {"scene": 2}, "TEST_ROOM", SCAN)
    assert st["scenes"] == {"TEST_ROOM": 2}
    st = g.solve([{"w": {"k": "mapscene", "group": 1, "map": 3}, "op": "==", "value": 1}], "MAPCALLBACK_TILES", {},
                 "TEST_ROOM", SCAN)
    assert st["mapscenes"] == [{"group": 1, "map": 3, "value": 1}]
    # the map's own scene through checkmapscene must agree with the scene root
    assert g.solve([{"w": {"k": "mapscene", "group": 1, "map": 2}, "op": "==", "value": 1}], "scene", {"scene": 0},
                   "TEST_ROOM", SCAN) is None


def test_clock_time_of_day_hour_weekday_and_memory():
    night = g.solve([{"w": {"k": "time", "mask": 4}, "op": "~=0"}], "MAPCALLBACK_OBJECTS", {}, "TEST_ROOM", SCAN)
    assert night["hour"] in g.TOD_HOURS[4]
    not_morning = g.solve([{"w": {"k": "time", "mask": 1}, "op": "==0"}], "MAPCALLBACK_OBJECTS", {}, "TEST_ROOM", SCAN)
    assert not_morning["hour"] not in g.TOD_HOURS[1]
    day_f = g.solve([{"w": {"k": "var", "id": g.VAR_TIMEOFDAY}, "op": "==", "value": 1}], "MAPCALLBACK_OBJECTS", {},
                    "TEST_ROOM", SCAN)
    assert day_f["hour"] in g.TOD_HOURS[2]
    friday = g.solve([{"w": {"k": "var", "id": g.VAR_WEEKDAY}, "op": "==", "value": 5}], "MAPCALLBACK_OBJECTS", {},
                     "TEST_ROOM", SCAN)
    assert friday["day"] == 5
    late = g.solve([{"w": {"k": "var", "id": g.VAR_HOUR}, "op": ">", "value": 20}], "MAPCALLBACK_OBJECTS", {},
                   "TEST_ROOM", SCAN)
    assert late["hour"] > 20
    mem = g.solve([{"w": {"k": "mem", "addr": 0xD6A8}, "op": "==", "value": 3}], "MAPCALLBACK_TILES", {}, "TEST_ROOM",
                  SCAN)
    assert mem["mem"] == {0xD6A8: 3}
    # a constant the script set: only the branch it takes
    assert g.solve([{"w": {"k": "const", "value": 2}, "op": "==", "value": 2}], "MAPCALLBACK_TILES", {}, "TEST_ROOM",
                   SCAN)["events"] == {}
    assert g.solve([{"w": {"k": "const", "value": 2}, "op": "==", "value": 3}], "MAPCALLBACK_TILES", {}, "TEST_ROOM",
                   SCAN) is None
    # results the sweep can't set
    assert g.solve([{"w": {"k": "special", "name": "UnownPuzzle"}, "op": "~=0"}], "bg", {}, "TEST_ROOM", SCAN) is None


def block(x, y, b, root="MAPCALLBACK_TILES", conds=()):
    return {"op": "changeblock", "args": {"x": x, "y": y, "block": b, "was": 1}, "root": root, "rootInfo": {},
            "conds": list(conds)}


def test_cases_cover_every_command_once_then_fill():
    scan = {"maps": {"TEST_ROOM": {"group": 1, "map": 2, "world": [
        block(2, 2, 5, conds=[ev(100, "==0")]),
        block(2, 2, 6, conds=[ev(100, "~=0")]),
        block(4, 0, 9, conds=[ev(100, "~=0"), ev(101, "~=0")]),
        block(4, 0, 9, conds=[ev(100, "==0"), ev(101, "~=0")]),
        block(6, 0, 7, root="bg"),  # not a load entry point
        {"op": "appear", "args": {"object": 2}, "root": "scene", "rootInfo": {"scene": 1}, "conds": []},
        block(8, 8, 3, conds=[{"w": {"k": "special", "name": "X"}, "op": "~=0"}]),
    ]}, "BATTLE_TOWER_HALLWAY": {"world": [block(0, 0, 2)]}}}
    cases, unsolved = g.make_cases(scan)
    assert {c["map"] for c in cases} == {"TEST_ROOM"}  # skipped places stay out
    labels = [c["label"] for c in cases]
    assert labels[0] == "new game" and len(cases) == 6
    assert any(c["scenes"] == {"TEST_ROOM": 1} for c in cases)
    assert unsolved["TEST_ROOM"] == 1
    lua = g.lua_cases(cases)
    assert lua.startswith("return {") and '[100] = true' in lua and '["TEST_ROOM"] = 1' in lua


def test_the_bedrooms_decorations_get_a_case_each():
    scan = {"maps": {"PLAYERS_HOUSE_2F": {"world": []}},
            "decorations": [{"id": 2, "slot": "bed"}, {"id": 3, "slot": "bed"}, {"id": 7, "slot": "carpet"}]}
    cases, _ = g.make_cases(scan)
    assert [c["label"] for c in cases] == ["decorations bare", "decoration bed 2", "decoration bed 3", "decoration carpet 7"]
    assert cases[0]["decorations"] == {"bed": 0, "carpet": 0, "plant": 0, "poster": 0}
    assert cases[2]["decorations"] == {"bed": 3, "carpet": 0, "plant": 0, "poster": 0}
    assert "decorations = { bed = 3, carpet = 0, plant = 0, poster = 0 }" in g.lua_cases(cases)
    # no bedroom in the scan, or no decorations: no such case
    assert g.make_cases({"maps": {}, "decorations": scan["decorations"]})[0] == []


def test_sites_check():
    scan = {"maps": {"TEST_ROOM": {"world": [block(2, 2, 5), block(4, 0, 1), block(6, 6, 8)]}}}
    sites = [
        {"map": "G2_TEST_ROOM", "bx": 1, "by": 1, "kind": "script", "before": 1, "after": 5},
        {"map": "G2_TEST_ROOM", "bx": 1, "by": 1, "kind": "script", "before": 1, "after": 6},
        {"map": "G2_TEST_ROOM", "bx": 9, "by": 9, "kind": "script", "before": 1, "after": 4},
    ]
    cases = [{"map": "TEST_ROOM", "settled": {"map": "TEST_ROOM", "blocks": [
        {"bx": 1, "by": 1, "block": 6, "was": 1},   # the block's second state: the builder keeps the first
        {"bx": 2, "by": 2, "block": 9, "was": 1},   # no site
    ]}}]
    out = g.check_sites(scan, cases, sites)
    c = out["counts"]
    assert c["static_site"] == 1 and c["static_noop"] == 1 and c["static_missing"] == 1
    assert c["live_shadowed"] == 1 and c["live_missing"] == 1
    assert c["blocks_with_several_states"] == 1
    assert c["export_script_found"] == 1 and c["export_script_not_found"] == 2
    assert out["missing_static_by_map"] == {"TEST_ROOM": 1}


def test_the_bridge_comparison():
    received = [
        {"type": "snapshot", "data": {"player": {"map": "G2_START"}, "toggles": {}, "blocks": []}},
        {"type": "warp", "data": {"phase": "arrive", "to": {"map": "G2_TEST_ROOM"}}},
        {"type": "npc.set", "data": {"map": "G2_TEST_ROOM", "list": [{"id": "O1"}, {"id": "O2"}]}},
        {"type": "story.blocks", "data": {"map": "G2_TEST_ROOM", "changes": [{"bx": 1, "by": 1, "block": 6}]}},
        {"type": "story.toggles", "data": {"map": "G2_TEST_ROOM", "obj": "O3", "visible": False}},
        {"type": "npc.remove", "data": {"id": "O2"}},
        {"type": "player.pos", "data": {"map": "G2_START"}},
    ]
    sweep = {"cases": [{"n": 1, "map": "TEST_ROOM", "label": "x", "ok": True,
                        "arrived": {"map": "TEST_ROOM", "blocks": [{"bx": 1, "by": 1, "block": 6, "was": 1}]},
                        "settled": {"map": "TEST_ROOM", "blocks": [{"bx": 1, "by": 1, "block": 6, "was": 1}],
                                    "people": ["O1"], "hidden": ["O3"]}}]}
    a = g.analyse(received, sweep)
    e = a["cases"][0]
    assert e["bridge"] == "ok" and e["objects"]["npcs_vs_engine"] == "ok" and e["objects"]["toggles"] == "ok"
    sweep["cases"][0]["settled"]["blocks"].append({"bx": 2, "by": 0, "block": 3, "was": 1})
    assert g.analyse(received, sweep)["cases"][0]["bridge"] == "missing"
