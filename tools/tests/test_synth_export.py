"""The synthetic export (fake_gen1.synth) matches the export schemas and the engine's cell rules."""
import base64
import hashlib
import json
import struct

import pytest

from fake_gen1 import synth
from kb_protocol import load_protocol

PROTOCOL = load_protocol()
RESOURCES = synth.resources()


def test_every_export_kind_is_covered():
    assert {kind for kind, _ in RESOURCES} == set(PROTOCOL.exports)
    kinds = PROTOCOL.documents["https://kantobridge.invalid/protocol/defs.schema.json"]["$defs"]["exportKind"]["enum"]
    assert sorted(kinds) == sorted(PROTOCOL.exports), "defs exportKind and protocol/export/ differ"


@pytest.mark.parametrize("key", sorted(RESOURCES), ids=lambda k: f"{k[0]}:{k[1]}")
def test_resource_validates(key):
    problems = PROTOCOL.check_export(key[0], RESOURCES[key])
    assert not problems, [str(p) for p in problems]


def test_index_matches_resources():
    index = synth.index(res=RESOURCES)
    assert not PROTOCOL.check({"type": "export.index", "seq": 1, "re": 1, "data": index})
    for entry in index["resources"]:
        raw = synth.encode(RESOURCES[(entry["kind"], entry["id"])])
        assert entry["size"] == len(raw)
        assert entry["hash"] == hashlib.sha256(raw).hexdigest()


def test_map_shapes():
    for name in synth.MAPS:
        m = RESOURCES[("map", name)]
        assert len(m["blocks"]) == m["width"] * m["height"]
        assert len(m["cells"]) == m["height"] * 2
        assert all(len(row) == m["width"] * 2 for row in m["cells"])


def test_cell_flags_follow_the_bottom_left_tile():
    cells = RESOURCES[("map", "TEST_TOWN")]["cells"]
    door = cells[5][5]
    assert door & synth.F_DOOR and door & synth.F_WALK and door & synth.F_WARP
    assert not cells[4][5] & synth.F_WALK, "the house wall above the door"
    sign = cells[5][11]
    assert sign & synth.F_SIGN and not sign & synth.F_WALK
    assert cells[4][15] & synth.F_GRASS and cells[4][15] & synth.F_WALK
    ledge = cells[13][3]
    assert ledge & synth.F_LEDGE and (ledge >> 14) == synth.LEDGE_DIRS["down"] and not ledge & synth.F_WALK
    assert cells[12][3] & synth.F_WALK and not cells[12][3] & synth.F_LEDGE
    assert cells[12][13] & synth.F_WATER
    assert cells[14][10] & synth.F_CUT
    assert cells[0][0] == 0, "trees"
    house = RESOURCES[("map", "TEST_HOUSE")]["cells"]
    assert house[8][4] & synth.F_WARP and house[8][4] & synth.F_WALK and not house[8][4] & synth.F_WARPTILE
    assert house[3][4] & synth.F_COUNTER


def test_world_index_schema_accepts_a_synthetic_index():
    index = {
        "version": 1, "game": "red", "exportHash": hashlib.sha256(b"{}").hexdigest(),
        "scale": {"cellBlocks": 2, "surfaceY": 64, "layerStep": 22},
        "maps": {
            "TEST_TOWN": {"x": 0, "y": 64, "z": 0, "w": 20, "h": 18, "layer": 0, "kind": "surface",
                          "palette": "TEST_PALETTE", "biome": "kantobridge:kanto_outdoor", "dark": False,
                          "parent": None},
            "TEST_HOUSE": {"x": 2, "y": 42, "z": 2, "w": 10, "h": 10, "layer": 1, "kind": "interior",
                           "palette": "TEST_PALETTE", "biome": "kantobridge:kanto_interior", "dark": False,
                           "parent": "TEST_TOWN"},
        },
        "slotMapHash": "a1b2c3d4e5f60718",
        "storyCells": [
            {"key": "TEST_TOWN:10:8", "type": "object", "obj": "TESTTOWN_GIRL"},
            {"key": "TEST_TOWN:10:14", "type": "tile",
             "swap": {"bx": 5, "by": 7, "kind": "cut", "before": 14, "after": 15}},
            {"key": "TEST_TOWN:12:12", "type": "water"},
        ],
        "habitats": [{"map": "TEST_TOWN", "x": 31, "y": 61, "z": 11, "pool": "kantobridge:test_town"}],
    }
    assert not PROTOCOL.check_world_index(index)
    # Wild Routes (WR3): a zone and its route map, placed at the trailhead
    zoned = json.loads(json.dumps(index))
    zoned["maps"]["KB_ROUTE_TEST_ZONE"] = {"x": 32, "y": 64, "z": 3410, "w": 2, "h": 2, "layer": 0, "kind": "route",
                                           "palette": "TEST_PALETTE", "biome": "kantobridge:wild_forest", "dark": False,
                                           "parent": None}
    zoned["zones"] = [{"id": "test_zone", "routeMap": "KB_ROUTE_TEST_ZONE", "theme": "forest", "x": 0, "y": 64,
                       "z": 3200, "w": 620, "d": 600, "biome": "kantobridge:wild_forest",
                       # WR5: trainers and balls in the route map's grid; each ball is also a story cell of type route
                       "trainers": [{"id": "a1.t1", "x": 12, "y": -3, "facing": "left", "sight": 4, "class": "OPP_TEST",
                                     "sprite": "SPRITE_TEST_GUARD", "party": [{"species": "TESTMON_A", "level": 7}]}],
                       "balls": [{"id": "a1.i1", "x": 20, "y": 5, "item": "POTION"}], "ballSprite": "SPRITE_TEST_BALL"}]
    zoned["storyCells"].append({"key": "KB_ROUTE_TEST_ZONE:20:5", "type": "route", "obj": "a1.i1"})
    assert not PROTOCOL.check_world_index(zoned)
    for path, bad in ((("theme",), "cave"), (("trainers", 0, "sight"), 9), (("trainers", 0, "party"), []),
                      (("balls", 0, "id"), "A1"), (("trainers", 0, "facing"), "north")):
        broken = json.loads(json.dumps(zoned))
        target = broken["zones"][0]
        for key in path[:-1]:
            target = target[key]
        target[path[-1]] = bad
        assert PROTOCOL.check_world_index(broken), path
    del zoned["zones"][0]["balls"]
    assert PROTOCOL.check_world_index(zoned), "trainers and balls are required"
    zoned["zones"][0]["theme"] = "cave"
    assert PROTOCOL.check_world_index(zoned)
    index["maps"]["TEST_TOWN"]["kind"] = "outside"
    assert PROTOCOL.check_world_index(index)


def _png_size(b64):
    raw = base64.b64decode(b64)
    assert raw[:8] == b"\x89PNG\r\n\x1a\n" and raw[12:16] == b"IHDR"
    return struct.unpack(">II", raw[16:24])


def test_images_are_pngs_of_the_declared_size():
    for (kind, _), data in RESOURCES.items():
        if kind in ("image", "sprite"):
            assert _png_size(data["png"]) == (data["w"], data["h"])
    sprite = RESOURCES[("sprite", "SPRITE_TEST_GIRL")]
    assert sprite["h"] == 16 * sprite["frames"] and sprite["walker"]
