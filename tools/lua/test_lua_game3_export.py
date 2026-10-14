"""MG7 G3D (docs/specs/GEN3_BRIDGE_PLAN.md §5): the Gen 3 adapter's export part
(gen1-bridge/lib/engine/game3/export.lua) on the fake game3 engine's made-up world (fake_export3.lua). The adapter
alone through a recording sink: every g3* resource and its schema; the maps' own cells (layout bytes, the true
width out of a wider stride), border, connections, warps (a dynamic one) and objects (kinds, flags, the camera left
out); the pairs' flattened quarters (over, else middle, else under; alike art shared; the over flag; behaviours
named); the pages' pixels; sprites; the layout from connections; encounters by national dex; names. Then main.lua
with a builder peer: export.index lists the g3* kinds, export.get serves one, hashes are the canonical JSON's
SHA-256. Everything is made up."""

import base64
import hashlib
import json
import struct

import pytest

from adapter3 import Adapter3
from kb_protocol import load_protocol
from kb_protocol.jsonfmt import canonical
from rig import Rig

pytestmark = pytest.mark.lua

PROTOCOL = load_protocol()
G3_KINDS = {"g3map", "g3tiles", "g3image", "g3sprite", "g3layout", "g3encounters", "g3names"}


def exported(a, region="hoenn"):
    """Runs exportResources with a recording sink: ({(kind, id): data}, [warning codes], ok, err)."""
    run = a.b.eval("""function(eng, json, region)
      local out, warns = {}, {}
      local sink = {
        put = function(kind, id, data) out[#out + 1] = { kind = kind, id = id, text = json.encode(data) } return true end,
        checkpoint = function() end,
        warn = function(code, example) warns[#warns + 1] = code end,
        json = json, region = region,
        base64 = function(s) error("the adapter has love.data") end,
      }
      local ok, err = eng.exportResources(sink)
      return out, warns, ok, err
    end""")
    json_mod = a.b.module("lib/kb_json.lua")
    out, warns, ok, err = run(a.eng, json_mod, region.encode())
    res = {}
    for i in range(1, len(out) + 1):
        e = out[i]
        res[(e.kind.decode(), e.id.decode())] = json.loads(e.text.decode())
    return res, [warns[i].decode() for i in range(1, len(warns) + 1)], ok, err


def world():
    a = Adapter3()
    a.E.exportWorld()
    return a


def png_rgba(b64):
    raw = base64.b64decode(b64)
    assert raw[:8] == b"\x89PNG\r\n\x1a\n" and raw[12:16] == b"IHDR"
    w, h = struct.unpack(">II", raw[16:24])
    at = raw.index(b"RAW") + 3
    return w, h, raw[at:]


def test_every_resource_passes_its_schema():
    res, warns, ok, err = exported(world())
    assert ok is True and err is None
    for (kind, rid), data in res.items():
        assert kind in G3_KINDS
        problems = PROTOCOL.check_export(kind, data)
        assert not problems, (kind, rid, [str(p) for p in problems])
    kinds = sorted({k for k, _ in res})
    assert kinds == sorted(G3_KINDS)
    assert sorted(i for k, i in res if k == "g3map") == ["EM_TEST_HOUSE", "EM_TEST_ROUTE", "EM_TEST_SEA", "EM_TEST_TOWN"]
    assert sorted(i for k, i in res if k == "g3tiles") == ["TEST_A", "TEST_B"]
    # the made-up world has no Littleroot (the layout's root), and one slot names an unknown species
    assert warns == ["g3_no_root", "g3_encounter_slot"]


def test_a_map_carries_its_layouts_own_cells_and_its_events():
    res, _, _, _ = exported(world())
    town = res[("g3map", "EM_TEST_TOWN")]
    assert (town["w"], town["h"], town["trueW"], town["trueH"], town["pair"], town["kind"], town["mapType"],
            town["music"], town["weather"]) == (8, 4, 6, 4, "TEST_A", "town", 1, 300, 2)
    raw = base64.b64decode(town["cells"])
    assert len(raw) == 8 * 4 * 4
    cells = [struct.unpack_from("<HBB", raw, i * 4) for i in range(32)]
    # row-major over the layout's whole grid (8 wide: a map 6 wide, padded)
    assert cells[0] == (0, 0, 3) and cells[1] == (1, 1, 3) and cells[8] == ((0 + 6) % 8, 1, 3)
    assert cells[31] == ((7 + 3 * 6) % 8, 0, 3)
    assert town["border"] == {"w": 2, "h": 1, "mids": [5, 6]}
    assert town["connections"] == [{"dir": "north", "map": "EM_TEST_ROUTE", "offset": 1},
                                   {"dir": "east", "map": "EM_TEST_SEA", "offset": -2},
                                   {"dir": "dive", "map": "EM_TEST_SEA", "offset": 0}]
    assert res[("g3map", "EM_TEST_ROUTE")]["connections"] == [{"dir": "south", "map": "EM_TEST_TOWN", "offset": -1}]
    assert town["warps"] == [{"x": 2, "y": 1, "warp": 0, "to": "EM_TEST_HOUSE"}, {"x": 4, "y": 1, "warp": 0, "dynamic": True}]
    # stays: the engine's movement for the template (looking about and standing keep the cell, walking leaves it)
    assert town["objects"] == [
        {"lid": 1, "gfx": "OBJ_EVENT_GFX_TEST_HIKER", "x": 3, "y": 2, "elev": 3, "flag": 0x101, "kind": "person",
         "stays": True},
        {"lid": 2, "gfx": "OBJ_EVENT_GFX_TEST_ITEM_BALL", "x": 5, "y": 1, "elev": 3, "flag": 0x102, "kind": "item",
         "stays": True},
        {"lid": 3, "gfx": "OBJ_EVENT_GFX_TEST_GIRL", "x": 0, "y": 3, "elev": 3, "kind": "trainer", "stays": False},
    ]


def quarter_bytes(rgba_of):
    """The 8x8 quarter q of a 16x16 metatile as RGBA bytes, from rgba_of(x, y) over the metatile."""
    def q(n):
        ox, oy = (n % 2) * 8, (n // 2) * 8
        return b"".join(bytes(rgba_of(ox + x, oy + y)) for y in range(8) for x in range(8))
    return q


def test_pairs_flatten_their_planes_share_alike_quarters_and_name_behaviours():
    res, _, _, _ = exported(world())
    a = res[("g3tiles", "TEST_A")]
    assert a["mids"] == [0, 1, 2, 3, 4, 5, 6, 7]
    q = a["quarters"]
    assert q[0] == q[3] == q[6] and q[1] != q[4] and q[2] != q[5], "0, 3 and 6 look alike; 1 and 2 have plane pixels"
    assert q[1][1:] == q[4][1:] and q[2][:3] == q[5][:3], "only the quarter with a plane pixel differs"
    assert a["over"] == [False, False, True, False, False, False, False, False]
    assert a["beh"] == [0, 64, 64, 32, 32, 0, 0, 16]
    assert a["behaviors"] == {"0": "MB_NORMAL", "64": "MB_TEST_GRASS", "32": "MB_TEST_WATER", "16": "MB_TEST_WALL"}
    assert a["land"] == [64], "the engine's rule: the grass rolls land encounters"
    b = res[("g3tiles", "TEST_B")]
    assert b["mids"] == [512, 513] and b["over"] == [False, False] and b["land"] == []
    # the pages hold the flattened quarters at their ids
    pages = {int(i.split(".")[1]): png_rgba(d["png"]) for (k, i), d in res.items() if k == "g3image"}
    assert sorted(pages) == [0] and pages[0][:2] == (256, 256)

    def at(qid):
        _, _, rgba = pages[qid // 1024]
        index = qid % 1024
        px, py = (index % 32) * 8, (index // 32) * 8
        return b"".join(rgba[((py + r) * 256 + px) * 4:((py + r) * 256 + px + 8) * 4] for r in range(8))

    def under(mid):
        return lambda x, y: (10 + mid % 3, (2 if y >= 8 else 0) + (1 if x >= 8 else 0), (x + y) % 2, 255)

    for mid_index, mid in enumerate(a["mids"]):
        for n in range(4):
            want = bytearray(quarter_bytes(under(mid))(n))
            if mid == 1 and n == 0:
                want[(1 * 8 + 1) * 4:(1 * 8 + 1) * 4 + 4] = bytes((200, 1, 1, 255))  # the middle plane's pixel
            if mid == 2 and n == 3:
                want[(1 * 8 + 1) * 4:(1 * 8 + 1) * 4 + 4] = bytes((1, 200, 1, 255))  # the over plane's pixel
            assert at(q[mid_index][n]) == bytes(want), (mid, n)
    distinct = {x for row in q + b["quarters"] for x in row}
    assert sorted(distinct) == list(range(len(distinct))), "ids are dense from 0"
    # TEST_A: 3 looks x 4 quarters, plus the two quarters with plane pixels; TEST_B: its art depends on x only, so
    # each metatile's top and bottom quarters are alike: 2 per metatile
    assert len(distinct) == 3 * 4 + 2 + 2 * 2


def test_sprites_layout_encounters_and_names():
    res, _, _, _ = exported(world())
    hiker = res[("g3sprite", "OBJ_EVENT_GFX_TEST_HIKER")]
    assert (hiker["gid"], hiker["w"], hiker["h"], hiker["frames"]) == (1, 16, 32, 9)
    w, h, rgba = png_rgba(hiker["png"])
    assert (w, h) == (16, 32 * 9) and rgba[:4] == bytes((1, 0, 0, 255))
    assert ("g3sprite", "OBJ_EVENT_GFX_TEST_GIRL") in res
    layout = res[("g3layout", "hoenn")]
    assert layout == {"root": "EM_LITTLEROOT_TOWN", "unit": "cells", "maps": {}}, "the made-up world has no Littleroot"


def test_the_layout_places_the_surface_by_its_connections():
    a = world()
    maps = a.E.game.data.maps
    maps[b"EM_LITTLEROOT_TOWN"] = maps[b"EM_TEST_TOWN"]
    maps[b"EM_TEST_ROUTE"].connections.south.map = b"EM_LITTLEROOT_TOWN"
    maps[b"EM_TEST_SEA"].connections.west.map = b"EM_LITTLEROOT_TOWN"
    res, _, _, _ = exported(a)
    layout = res[("g3layout", "hoenn")]["maps"]
    # the root (the town's layout, 8x4 as the engine places it) at 0,0; north offset 1: x 1, y -5 (the route is 5
    # high); east offset -2: x 8
    assert layout["EM_LITTLEROOT_TOWN"] == {"x": 0, "y": 0, "w": 8, "h": 4}
    assert layout["EM_TEST_ROUTE"] == {"x": 1, "y": -5, "w": 4, "h": 5}
    assert layout["EM_TEST_SEA"] == {"x": 8, "y": -2, "w": 3, "h": 3}
    assert "EM_TEST_HOUSE" not in layout, "interiors are not on the surface"


def test_encounters_by_national_dex_and_names():
    res, _, _, _ = exported(world())
    enc = res[("g3encounters", "all")]["maps"]
    assert enc["EM_TEST_ROUTE"] == {
        "land": {"rate": 20, "slots": [{"dex": 251, "min": 2, "max": 3}, {"dex": 252, "min": 4, "max": 4}]},
        "fish": {"rate": 30, "slots": [{"dex": 253, "min": 5, "max": 10}]},
    }
    assert enc["EM_TEST_SEA"] == {"water": {"rate": 4, "slots": [{"dex": 252, "min": 20, "max": 30}]}}
    assert res[("g3encounters", "all")]["landWeights"] == [20, 20, 10, 10, 10, 10, 5, 5, 4, 4, 1, 1]
    names = res[("g3names", "all")]
    assert names["species"] == {"SPECIES_TEST_NAMED": 253, "SPECIES_TEST_ONE": 251, "SPECIES_TEST_TWO": 252}
    assert names["moves"] == {"MOVE_TEST_SPLASH": 2, "MOVE_TEST_TACKLE": 1}
    assert names["items"]["ITEM_TEST_POTION"] == 2


def test_without_the_engines_rules_the_optional_facts_are_left_out():
    a = world()
    a.E.Encounters.rules = None
    a.E.Encounters._h = None
    a.E.Objects.canonMovementType = None
    a.E.GfxIds.hostMovement = None
    res, warnings, ok, _ = exported(a)
    assert ok is True
    assert "land" not in res[("g3tiles", "TEST_A")]
    assert "landWeights" not in res[("g3encounters", "all")]
    assert all("stays" not in o for o in res[("g3map", "EM_TEST_TOWN")]["objects"])
    assert "g3_land_weights_missing" in warnings
    for key, data in res.items():
        assert not PROTOCOL.check_export(key[0], data), key


def test_no_maps_is_a_failure():
    a = Adapter3()
    a.E.game.data = None
    _, _, ok, err = exported(a)
    assert ok is False and err == b"no_maps"


# ------------------------------------------------------------ main.lua and a builder


def test_main_serves_the_gen3_export_to_a_builder():
    rig = Rig(family="gen3")
    rig.E.exportWorld()
    rig.step()
    peer = rig.connect()
    peer.hello(role="builder")
    peer.messages()
    seq = peer.send("export.index", {})
    got = []
    for i in range(2000):
        if i % 120 == 119:
            peer.send("ping", {"t": i})
        rig.step()
        got += [m for m in peer.messages() if m["type"] != "pong"]
        if any(m.get("re") == seq for m in got):
            break
    index = next(m for m in got if m.get("re") == seq)
    assert index["type"] == "export.index", index
    data = index["data"]
    assert data["game"] == "emerald"
    kinds = {r["kind"] for r in data["resources"]}
    assert kinds == G3_KINDS | {"routes"}, "MG8: the core adds the zones this game runs, as for Gen 1"
    entry = next(r for r in data["resources"] if r["kind"] == "g3map" and r["id"] == "EM_TEST_TOWN")
    seq = peer.send("export.get", {"kind": "g3map", "id": "EM_TEST_TOWN"})
    for _ in range(50):
        rig.step()
        got = [m for m in peer.messages() if m.get("re") == seq]
        if got:
            break
    reply = got[0]
    assert reply["type"] == "export.data" and reply["data"]["hash"] == entry["hash"]
    text = canonical(reply["data"]["data"])
    assert hashlib.sha256(text.encode()).hexdigest() == entry["hash"] and len(text.encode()) == entry["size"]
    assert not PROTOCOL.check_export("g3map", reply["data"]["data"])
    lines = rig.logs()
    assert not [l for l in lines if "step_failed" in l or "[error]" in l], lines
