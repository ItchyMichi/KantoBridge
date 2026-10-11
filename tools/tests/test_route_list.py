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


def test_viridians_zone_waits_for_its_spot():
    zone = json.loads(SHIPPED.read_text(encoding="utf-8"))["routes"][0]
    assert zone["id"] == "viridian_forest_zone" and zone["from"]["map"] == "VIRIDIAN_CITY"
    assert (zone["from"]["x"], zone["from"]["y"], zone["from"]["facing"]) == (None, None, None)


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
