"""Several games (MULTI_GAME_PLAN §4.6, MG3): the region table (<world>/kantobridge/regions.json), a region record
(regions/<id>/region.json) and the slot map's version 2 match their schemas, and the checkers refuse what the plan
forbids. Everything here is made up (ids and placeholder numbers only)."""
import copy
import hashlib

from kb_protocol import load_protocol

PROTOCOL = load_protocol()
HASH = hashlib.sha256(b"kb-test").hexdigest()

TABLE = {"version": 1, "pitch": 8192,
         "regions": [{"id": "kanto", "game": "blue", "slot": 0}, {"id": "hoenn", "game": "emerald", "slot": 1}]}

RECORD = {
    "version": 1, "id": "hoenn", "game": "emerald", "family": "gen3", "exportHash": HASH,
    "slot": 1, "origin": {"x": 8192, "y": 64, "z": 0},
    "bounds": {"minX": 7980, "minZ": -9400, "maxX": 13400, "maxZ": 4200, "minY": -256, "maxY": 319},
    "areas": {"surface": {"x": 8192, "z": 0, "w": 704, "d": 744}, "zones": {"x": 8192, "z": 3744, "w": 5120, "d": 640},
              "dungeons": {"x": 8192, "z": -3600, "w": 1200, "d": 600}, "caves": {"x": 8192, "z": -9400, "w": 0, "d": 0}},
    "families": [16, 31],
    "files": {"index": "regions/hoenn/world_index.json", "shapeIndex": "regions/hoenn/shape_index.json",
              "shapeMap": "regions/hoenn/shape_map.bin"},
    "hashes": {"index": HASH, "shapeIndex": HASH, "shapeMap": HASH},
    "generator": {"shapes": "kb-shapes/11"},
    "rules": {"profile": "gen3", "movement": {"walk": 16, "bike": 8},
              "compat": {"maxDex": 386, "maxMove": 354, "nick": 10, "onIncompatible": "leave_out"}},
    "namespaces": {"artSet": "emerald", "pools": "kantobridge:hoenn"},
}


def _problems(check, data):
    return [str(p) for p in check(data)]


def _record(**changes):
    out = copy.deepcopy(RECORD)
    for path, value in changes.items():
        node = out
        keys = path.split(".")
        for key in keys[:-1]:
            node = node[key]
        if value is None:
            del node[keys[-1]]
        else:
            node[keys[-1]] = value
    return out


# ------------------------------------------------------------ the region table


def test_the_region_table_matches_its_schema():
    assert not _problems(PROTOCOL.check_regions, TABLE)
    legacy_like = {"version": 1, "pitch": 8192, "regions": [{"id": "kanto", "game": "red", "slot": 0}]}
    assert not _problems(PROTOCOL.check_regions, legacy_like)


def test_a_bad_region_table_is_refused():
    bad = copy.deepcopy(TABLE)
    bad["regions"][0]["id"] = "Kanto"
    assert _problems(PROTOCOL.check_regions, bad) == ["/regions/0/id: pattern"]
    bad = copy.deepcopy(TABLE)
    bad["pitch"] = 4000
    assert _problems(PROTOCOL.check_regions, bad) == ["/pitch: minimum"]
    bad = copy.deepcopy(TABLE)
    bad["regions"][1]["slot"] = 64
    assert _problems(PROTOCOL.check_regions, bad) == ["/regions/1/slot: maximum"]
    bad = copy.deepcopy(TABLE)
    bad["regions"][1]["game"] = "Emerald"
    assert _problems(PROTOCOL.check_regions, bad) == ["/regions/1/game: pattern"]
    bad = copy.deepcopy(TABLE)
    bad["regions"][0]["origin"] = {"x": 0}
    assert _problems(PROTOCOL.check_regions, bad) == ["/regions/0: additionalProperties"]


# ------------------------------------------------------------ the region record


def test_the_region_record_matches_its_schema():
    assert not _problems(PROTOCOL.check_region, RECORD)
    # a record without the optional movement rule, and an area that was not built (zero size)
    assert not _problems(PROTOCOL.check_region, _record(**{"rules.movement": None}))


def test_a_bad_region_record_is_refused():
    assert _problems(PROTOCOL.check_region, _record(id="Hoenn")) == ["/id: pattern"]
    assert _problems(PROTOCOL.check_region, _record(**{"areas.caves": None})) == ["/areas: required"]
    assert _problems(PROTOCOL.check_region, _record(families=[16, 96])) == ["/families/1: maximum"]  # MG7: 96 per shape
    assert _problems(PROTOCOL.check_region, _record(**{"namespaces.pools": "cobblemon:hoenn"})) == ["/namespaces/pools: pattern"]
    # the implicit legacy record may name the root documents; anything outside the data folder is refused
    assert _problems(PROTOCOL.check_region, _record(**{"files.index": "world_index.json"})) == []
    assert _problems(PROTOCOL.check_region, _record(**{"files.index": "../world_index.json"})) == ["/files/index: pattern"]
    assert _problems(PROTOCOL.check_region, _record(family="gen9")) == ["/family: enum"]
    assert _problems(PROTOCOL.check_region, _record(**{"rules.compat.onIncompatible": "drop"})) == ["/rules/compat/onIncompatible: enum"]
    assert _problems(PROTOCOL.check_region, _record(**{"areas.surface.w": -1})) == ["/areas/surface/w: minimum"]


# ------------------------------------------------------------ the slot map, version 2


def _entry(region=None, family=0, slot=0, tile=0):
    out = {"atlas": "tilesets.test", "palette": "TEST_PALETTE", "tile": tile, "shape": "full", "family": family, "slot": slot}
    if region is not None:
        out["region"] = region
    return out


def test_a_version_2_slot_map_names_its_regions_and_their_families():
    slot_map = {"version": 2,
                "regions": {"kanto": {"set": "blue", "families": [0, 15]}, "hoenn": {"set": "emerald", "families": [16, 31]}},
                "slots": [_entry("kanto", family=0, slot=0), _entry("hoenn", family=31, slot=255, tile=1)]}
    assert not _problems(PROTOCOL.check_slot_map, slot_map)


def test_the_version_1_slot_map_keeps_its_caps():
    v1 = {"version": 1, "slots": [_entry(family=15, slot=255)]}
    assert not _problems(PROTOCOL.check_slot_map, v1)
    v1["slots"][0]["family"] = 16
    assert _problems(PROTOCOL.check_slot_map, v1)  # 16 families in version 1
    v1["slots"][0]["family"] = 0
    v1["regions"] = {"kanto": {"set": "blue", "families": [0, 15]}}
    assert _problems(PROTOCOL.check_slot_map, v1)  # no region table in version 1
    v1 = {"version": 1, "slots": [_entry("kanto")]}
    assert _problems(PROTOCOL.check_slot_map, v1)  # no region on a version-1 entry


def test_a_bad_version_2_slot_map_is_refused():
    v2 = {"version": 2, "regions": {"kanto": {"set": "blue", "families": [0, 15]}}, "slots": [_entry(family=3)]}
    assert _problems(PROTOCOL.check_slot_map, v2)  # an entry without its region
    v2 = {"version": 2, "slots": [_entry("kanto", family=3)]}
    assert _problems(PROTOCOL.check_slot_map, v2)  # no region table
    v2 = {"version": 2, "regions": {"kanto": {"set": "blue", "families": [0, 96]}}, "slots": [_entry("kanto")]}
    assert _problems(PROTOCOL.check_slot_map, v2)  # a family range beyond 95 (MG7: 96 per shape)
    v2 = {"version": 2, "regions": {"Kanto": {"set": "blue", "families": [0, 15]}}, "slots": [_entry("kanto")]}
    assert _problems(PROTOCOL.check_slot_map, v2)  # a bad region id in the table
