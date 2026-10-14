"""MG7 (GEN3_BRIDGE_PLAN.md §8.4): the synthetic Gen 3 export (fake_gen1.synth3) matches the g3* export schemas,
its cells, quarters and pages agree, and a Gen 3 game's bundle is written with it."""
import base64
import struct
import zlib

import pytest

from fake_gen1 import synth3
from fake_gen1.bundle import write_bundle
from kb_protocol import load_protocol

PROTOCOL = load_protocol()
RESOURCES = synth3.resources()


def test_every_gen3_kind_is_covered():
    assert {kind for kind, _ in RESOURCES} == {k for k in PROTOCOL.exports if k.startswith("g3")}


@pytest.mark.parametrize("key", sorted(RESOURCES), ids=lambda k: f"{k[0]}:{k[1]}")
def test_resource_validates(key):
    problems = PROTOCOL.check_export(key[0], RESOURCES[key])
    assert not problems, [str(p) for p in problems]


PASS = synth3.resources(variant="pass")


@pytest.mark.parametrize("key", sorted(k for k in PASS if PASS[k] != RESOURCES.get(k)), ids=lambda k: f"{k[0]}:{k[1]}")
def test_the_pass_variants_own_resources_validate(key):
    # MG8: the town with a Wild Routes end (its two tree rows, the clearing baked in, a trainer) and the routes resource
    problems = PROTOCOL.check_export(key[0], PASS[key])
    assert not problems, [str(p) for p in problems]


def test_the_pass_variants_clearing_is_open():
    town = PASS[("g3map", "EM_TEST_TOWN")]
    raw = base64.b64decode(town["cells"])

    def coll(x, y):
        return raw[(y * town["w"] + x) * 4 + 2]

    assert (town["h"], town["trueH"]) == (8, 8)
    assert [coll(x, 7) for x in range(8)] == [0x07, 0x07, 0x07, 0x07, 0x00, 0x00, 0x07, 0x07]
    item = PASS[("routes", "all")]["routes"][0]
    assert item["town"]["cleared"] == [[2, 3]] and item["end"] == "to"


def test_index_validates_and_names_emerald():
    index = synth3.index()
    assert index["game"] == "emerald"
    assert not PROTOCOL.check({"type": "export.index", "seq": 1, "re": 1, "data": index})


def _png_rgba(b64):
    raw = base64.b64decode(b64)
    w, h, depth, colour = struct.unpack(">IIBB", raw[16:26])
    assert (depth, colour) == (8, 6), "RGBA"
    at, data = 8, b""
    while at < len(raw):
        n = struct.unpack(">I", raw[at:at + 4])[0]
        tag = raw[at + 4:at + 8]
        if tag == b"IDAT":
            data += raw[at + 8:at + 8 + n]
        at += 12 + n
    rows = zlib.decompress(data)
    stride = w * 4 + 1
    return w, h, b"".join(rows[y * stride + 1:(y + 1) * stride] for y in range(h))


def test_cells_quarters_and_pages_agree():
    town = RESOURCES[("g3map", "EM_TEST_TOWN")]
    raw = base64.b64decode(town["cells"])
    assert len(raw) == town["w"] * town["h"] * 4
    mid, coll, elev = struct.unpack_from("<HBB", raw, (3 * 8 + 3) * 4)
    assert (mid, coll, elev) == (4, 0x71, 3), "the house's door"
    house = RESOURCES[("g3map", "EM_TEST_HOUSE")]
    assert (house["w"], house["trueW"]) == (4, 3), "a padded map"
    tiles = RESOURCES[("g3tiles", "TEST_PAIR_A")]
    page_w, page_h, rgba = _png_rgba(RESOURCES[("g3image", "g3q.0")]["png"])
    assert (page_w, page_h) == (256, 256)
    ground = tiles["quarters"][tiles["mids"].index(0)]
    assert len(set(ground)) == 1, "a plain metatile's quarters are one quarter"
    tree = tiles["quarters"][tiles["mids"].index(5)]
    assert tiles["over"][tiles["mids"].index(5)] and len(set(tree)) == 4
    q = tree[0]
    px, py = (q % 32) * 8, (q // 32) * 8
    assert rgba[(py * 256 + px) * 4:(py * 256 + px) * 4 + 4] == bytes((10, 40, 10, 255)), "the over plane's pixel"


def test_a_gen3_bundle_is_written_with_the_gen3_export(tmp_path):
    root = write_bundle(tmp_path, "emerald")
    kinds = {p.parent.name for p in root.rglob("*.json") if p.name != "index.json"}
    assert kinds == {"g3map", "g3tiles", "g3image", "g3sprite", "g3layout", "g3encounters", "g3names"}
