"""Wild Routes (WR2): gen1-bridge/data/routes.json and the Lua tests' made-up list match
protocol/world/route_list.schema.json; the shipped list holds no map contents."""
import json

from kb_protocol import load_protocol
from kb_protocol.paths import REPO_ROOT

PROTOCOL = load_protocol()
SHIPPED = REPO_ROOT / "gen1-bridge" / "data" / "routes.json"


def test_the_shipped_route_list_matches_the_schema():
    data = json.loads(SHIPPED.read_text(encoding="utf-8"))
    problems = PROTOCOL.check_route_list(data)
    assert not problems, [str(p) for p in problems]


def test_viridians_zone_has_its_spot():
    # the bottom-right corner's best spot, found on the real game by tools/e2e/drivers/kb_wildspot.lua (2026-10-11)
    zone = json.loads(SHIPPED.read_text(encoding="utf-8"))["routes"][0]
    assert zone["id"] == "viridian_forest_zone" and zone["from"]["map"] == "VIRIDIAN_CITY"
    assert (zone["from"]["x"], zone["from"]["y"], zone["from"]["facing"], zone["from"]["depth"]) == (35, 29, "right", 2)


def test_the_test_pass_has_its_temporary_spots():
    # MG8 (docs/specs/HOENN_PASS_PLAN.md K1): the user's "a corner of the first or second town", found on the pods'
    # exports by the clearing rules; Pallet Town had no spot, so Kanto's end is in Viridian City
    route = json.loads(SHIPPED.read_text(encoding="utf-8"))["routes"][1]
    assert route["id"] == "kanto_hoenn_test" and route["kind"] == "connecting"
    near, far = route["from"], route["to"]
    assert (near["region"], near["map"], near["x"], near["y"], near["facing"], near["depth"]) == (
        "kanto", "VIRIDIAN_CITY", 6, 4, "up", 2)
    assert (far["region"], far["games"], far["map"], far["x"], far["y"], far["facing"], far["depth"]) == (
        "hoenn", ["emerald"], "EM_LITTLEROOT_TOWN", 18, 14, "down", 2)


def test_the_lua_tests_list_matches_the_schema():
    import sys
    sys.path.insert(0, str(REPO_ROOT / "tools" / "lua"))
    import routesim
    problems = PROTOCOL.check_route_list(json.loads(routesim.route_list()))
    assert not problems, [str(p) for p in problems]


def test_a_bad_list_is_refused():
    bad = {"version": 1, "routes": [{"id": "x", "name": "X", "kind": "free_roam",
                                     "from": {"games": ["red"], "map": "TOWN", "x": 1, "y": 1, "facing": "north"}}]}
    assert [str(p) for p in PROTOCOL.check_route_list(bad)] == ["/routes/0/from/facing: enum"]
