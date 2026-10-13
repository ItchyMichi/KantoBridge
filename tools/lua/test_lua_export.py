"""gen1-bridge's world export (export/kb_export.lua and friends) on the fake engine with a made-up
world (export_world.py): every resource kind against protocol/export/, hashes and sizes against
Python's canonical JSON + SHA-256, the per-cell flags of crafted cells, the whole-world facts
(surface, layout, parents, LAST_MAP, palettes), the data-source fallbacks, budgets, roles and
errors. Every message a peer receives is also checked against the message schemas (rig.Peer)."""

import base64
import hashlib
import json

import pytest

import export_world as W
from kb_protocol import load_protocol
from kb_protocol.jsonfmt import canonical
from rig import Rig, call

pytestmark = pytest.mark.lua

PROTOCOL = load_protocol()
KINDS = {"map", "tileset", "image", "sprite", "palettes", "encounters", "field", "names", "layout", "swaps", "routes"}

# per-cell flag bits (docs/PROTOCOL.md "Export resources")
WALK, WATER, SHORE, GRASS, DOOR, WARP_TILE, COUNTER, CUT = 1, 2, 4, 8, 16, 32, 64, 128
PAD, HOLE, SPINNER, WARP, SIGN, LEDGE = 256, 512, 1024, 2048, 4096, 8192
HOP = {"down": 0, "up": 1, "left": 2, "right": 3}


def ledge(direction):
    return LEDGE | HOP[direction] << 14


# ------------------------------------------------------------ helpers


def export_rig(world=None, role="builder", **kw):
    rig = Rig(world=W.world() if world is None else world, **kw)
    rig.step()
    peer = rig.connect()
    peer.hello(role=role)
    assert [m["type"] for m in peer.messages()] == ["hello.ok", "snapshot"]
    return rig, peer


def pump(rig, peer, done, steps=3000):
    """Steps until done(messages so far) holds, pinging now and then so the session stays up."""
    got = []
    for i in range(steps):
        if i % 120 == 119:
            peer.send("ping", {"t": i})
        rig.step()
        got += [m for m in peer.messages() if m["type"] != "pong"]
        if done(got):
            return got
    raise AssertionError(f"not done after {steps} steps; got {[m['type'] for m in got]}")


def request(rig, peer, msg_type, data):
    seq = peer.send(msg_type, data)
    msgs = pump(rig, peer, lambda ms: any(m.get("re") == seq for m in ms))
    return next(m for m in msgs if m.get("re") == seq)


def fetch_all(rig, peer, index):
    want = {}
    for e in index["resources"]:
        want[peer.send("export.get", {"kind": e["kind"], "id": e["id"]})] = (e["kind"], e["id"])
    msgs = pump(rig, peer, lambda ms: sum(1 for m in ms if m.get("re") in want) == len(want))
    return {want[m["re"]]: m for m in msgs if m.get("re") in want}


def status(rig):
    peer = rig.connect()
    peer.hello(role="test")
    peer.messages()
    return request(rig, peer, "debug.status", {})["data"]


@pytest.fixture(scope="module")
def exported():
    rig, peer = export_rig()
    seen = []
    original = peer.messages

    def recording(check=True):
        msgs = original(check)
        seen.extend(msgs)
        return msgs

    peer.messages = recording
    index = request(rig, peer, "export.index", {})
    assert index["type"] == "export.index"
    pages = fetch_all(rig, peer, index["data"])
    data = {key: page["data"]["data"] for key, page in pages.items()}
    return {"rig": rig, "index": index, "pages": pages, "data": data, "logs": rig.logs(), "seen": seen}


def cells(exported, map_id):
    return exported["data"][("map", map_id)]["cells"]


# ------------------------------------------------------------ shapes, hashes, sizes


def test_every_resource_validates_and_its_hash_and_size_match(exported):
    index = exported["index"]["data"]
    assert {e["kind"] for e in index["resources"]} == KINDS
    assert (index["game"], index["engine"], index["bridge"]) == ("blue", "0.3.85", "0.1.0")
    keys = [(e["kind"], e["id"]) for e in index["resources"]]
    assert keys == sorted(keys), "the index is sorted by kind, then id"
    for e in index["resources"]:
        key = (e["kind"], e["id"])
        data = exported["data"][key]
        problems = PROTOCOL.check_export(e["kind"], data)
        assert not problems, (key, [str(p) for p in problems])
        raw = canonical(data).encode("utf-8")
        assert e["size"] == len(raw), key
        assert e["hash"] == hashlib.sha256(raw).hexdigest(), key
        page = exported["pages"][key]["data"]
        assert (page["kind"], page["id"], page["hash"]) == (e["kind"], e["id"], e["hash"])


def test_export_lines_are_canonical_json():
    rig, peer = export_rig()
    seq = peer.send("export.index", {})
    peer.send("export.get", {"kind": "map", "id": "PALLET_TOWN"})
    raw = b""
    for _ in range(2000):
        rig.step()
        raw += call(peer.handle, "take")
        if b'"re":%d' % (seq + 1) in raw:
            break
    lines = [line for line in raw.split(b"\n") if line]
    assert lines
    for line in lines:
        assert canonical(json.loads(line)).encode("utf-8") == line


def test_counts(exported):
    counts = {}
    for kind, _ in exported["data"]:
        counts[kind] = counts.get(kind, 0) + 1
    assert counts == {"map": 13, "tileset": 7, "image": 12, "sprite": 3, "palettes": 1, "encounters": 1, "routes": 1,
                      "field": 1, "names": 1, "layout": 1, "swaps": 1}
    ready = [line for line in exported["logs"] if "export.ready " in line]
    assert len(ready) == 1 and " path=datasets " in ready[0] and " resources=42 " in ready[0]
    assert any("export.counts encounters=1 field=1 image=12 layout=1 map=13 names=1 palettes=1 routes=1 sprite=3"
               " swaps=1 tileset=7" in line for line in exported["logs"])


# ------------------------------------------------------------ maps and cells


def test_map_record_fields(exported):
    m = exported["data"][("map", "PALLET_TOWN")]
    assert (m["id"], m["index"], m["tileset"], m["width"], m["height"], m["border"]) == \
        ("PALLET_TOWN", 0, "OVERWORLD", 6, 5, 2)
    assert m["blocks"][:6] == [2, 2, 7, 2, 8, 2] and len(m["blocks"]) == 30
    assert m["connections"] == {"north": {"map": "ROUTE_1", "offset": 0}, "east": {"map": "ROUTE_22", "offset": 1}}
    assert m["warps"][0] == {"x": 5, "y": 1, "to": "REDS_HOUSE_1F", "warp": 1}
    assert m["signs"] == [{"x": 7, "y": 3}]
    objects = {o["name"]: o for o in m["objects"]}
    assert objects["PALLETTOWN_WALKER"] == {"index": 1, "name": "PALLETTOWN_WALKER", "sprite": "SPRITE_TEST_GIRL",
                                           "x": 2, "y": 8, "movement": "WALK", "range": "ANY_DIR", "hidden": False}
    assert objects["PALLETTOWN_BALL"]["item"] == "POTION"
    assert objects["PALLETTOWN_TRAINER"]["trainer"] == {"cls": "OPP_TEST", "party": 2}
    assert objects["PALLETTOWN_STATIC"]["pokemon"] == {"species": "TESTMON_A", "dex": 16, "level": 30}
    assert objects["PALLETTOWN_STATIC"]["hidden"] is True
    assert "source" not in m and "sram" not in m and "label" not in m
    assert len(m["cells"]) == 10 and all(len(row) == 12 for row in m["cells"])
    house = exported["data"][("map", "REDS_HOUSE_1F")]
    assert house["warps"][0]["to"] == "LAST_MAP" and house["connections"] == {}


def test_overworld_cell_flags(exported):
    c = cells(exported, "PALLET_TOWN")
    assert c[1][5] == WALK | DOOR | WARP, "a door with a warp entry"
    assert c[0][9] == WALK | WARP_TILE | WARP, "a cave warp tile with a warp entry"
    assert c[2][10] == WALK | WARP, "a warp entry on plain ground"
    assert c[2][2] == WALK | GRASS and c[3][3] == WALK | GRASS
    assert c[3][7] == SIGN, "a sign on a solid post"
    assert c[4][0] == WALK and c[5][0] == ledge("down") and c[5][3] == ledge("down")
    assert c[4][4] == CUT, "a cuttable tree"
    assert c[4][8] == ledge("left") and c[4][9] == WALK
    assert c[4][11] == ledge("right") and c[4][10] == WALK
    assert c[6][0] == ledge("up") and c[6][1] == ledge("up") and c[7][0] == WALK
    assert c[6][6] == WATER and c[7][7] == WATER
    assert c[6][8] == WATER | SHORE and c[6][9] == WATER
    assert c[0][0] == 0, "trees"
    assert c[6][4] == WALK, "a flower"


def test_interior_cell_flags(exported):
    house = cells(exported, "REDS_HOUSE_1F")
    assert house[0][5] == WALK | WARP_TILE | WARP, "stairs"
    assert house[3][2] == COUNTER and house[3][3] == COUNTER
    assert house[0][3] == 0, "tile $14 is a TV in a house: water only counts on field.waterTilesets"
    assert house[7][2] == WALK | WARP, "an exit mat warp: no warp tile"
    gym = cells(exported, "TEST_GYM")
    assert gym[2][2] == WALK | SPINNER
    assert gym[2][4] == CUT, "GYM's cuttable plant"
    lab = cells(exported, "TEST_LAB")
    assert lab[2][2] == WALK | PAD | WARP and lab[3][3] == WALK | HOLE | WARP
    assert lab[2][4] == WALK and lab[3][5] == WALK, "pad and hole tiles without a warp are floor"
    cave = cells(exported, "TEST_CAVE")
    assert cave[0][2] == WALK | HOLE | WARP
    assert cave[2][2] == WATER and cave[2][4] == WATER | SHORE
    assert cave[4][0] == ledge("down") and cave[4][1] == ledge("down"), "a ledge row for CAVERN only"
    assert cave[4][4] == WARP and cave[5][1] == WARP


# ------------------------------------------------------------ the whole world


def test_surface_layout_and_outside(exported):
    d = exported["data"]
    layout = d[("layout", "kanto")]
    assert layout == {"root": "PALLET_TOWN", "maps": {
        "PALLET_TOWN": {"x": 0, "y": 0, "w": 6, "h": 5},
        "ROUTE_1": {"x": 0, "y": -4, "w": 6, "h": 4},
        "ROUTE_22": {"x": 6, "y": 1, "w": 4, "h": 3},
    }}
    for (kind, mid), m in d.items():
        if kind == "map":
            assert m["surface"] == (mid in layout["maps"]), mid
            assert m["outside"] == (m["tileset"] == "OVERWORLD"), mid


def test_parents_follow_the_bfs(exported):
    d = exported["data"]
    parent = {mid: m["parent"] for (kind, mid), m in d.items() if kind == "map"}
    assert parent["PALLET_TOWN"] is None and parent["ROUTE_1"] is None
    assert parent["REDS_HOUSE_1F"] == {"map": "PALLET_TOWN", "warp": 1, "x": 5, "y": 1}
    assert parent["REDS_HOUSE_2F"] == {"map": "REDS_HOUSE_1F", "warp": 2, "x": 5, "y": 0}
    assert parent["TEST_CAVE"] == {"map": "PALLET_TOWN", "warp": 2, "x": 9, "y": 0}
    assert parent["TEST_LAB"] == {"map": "TEST_CAVE", "warp": 2, "x": 4, "y": 4}
    assert parent["TEST_GATE"] == {"map": "ROUTE_1", "warp": 1, "x": 2, "y": 2}
    assert parent["TEST_TUNNEL"] == {"map": "TEST_UNDERGROUND_A", "warp": 2, "x": 2, "y": 2}, \
        "A (index 70) is searched before B (index 72)"
    assert parent["TEST_COLOSSEUM"] is None, "no warp leads there"


def test_last_map_and_palettes(exported):
    d = exported["data"]
    last = {mid: m["lastMap"] for (kind, mid), m in d.items() if kind == "map"}
    assert last["PALLET_TOWN"] is None, "outside maps have none"
    assert last["REDS_HOUSE_1F"] == "PALLET_TOWN" and last["REDS_HOUSE_2F"] == "PALLET_TOWN"
    assert last["TEST_LAB"] == "PALLET_TOWN", "carried through the cave"
    assert last["TEST_UNDERGROUND_A"] == "ROUTE_1", "a fixed lastMapRewrites row"
    assert last["TEST_UNDERGROUND_B"] == "PALLET_TOWN"
    assert last["TEST_GATE"] is None, "the rewrite depends on the position"
    assert last["TEST_TUNNEL"] is None, "two ways in, two memories"
    assert last["TEST_COLOSSEUM"] is None
    palette = {mid: m["palette"] for (kind, mid), m in d.items() if kind == "map"}
    assert palette["PALLET_TOWN"] == "PALLET" and palette["ROUTE_1"] == "ROUTE"
    assert palette["REDS_HOUSE_2F"] == "PALLET", "interiors take their outdoor map's palette"
    assert palette["TEST_CAVE"] == "CAVE", "byTileset wins over the outdoor map"
    assert palette["TEST_GATE"] == "ROUTE" and palette["TEST_UNDERGROUND_A"] == "ROUTE"
    assert palette["TEST_COLOSSEUM"] == "ROUTE", "the default"
    assert palette["TEST_TUNNEL"] == "PALLET", "ambiguous: the first candidate, and a warning"
    assert any("export.warning code=palette_ambiguous count=1 first=TEST_TUNNEL" in line for line in exported["logs"])
    dark = {mid for (kind, mid), m in d.items() if kind == "map" and m["dark"]}
    assert dark == {"TEST_CAVE"}


# ------------------------------------------------------------ tilesets, images, sprites


def test_tileset_classes(exported):
    d = exported["data"]
    ow = d[("tileset", "OVERWORLD")]
    assert (ow["image"], ow["tilesPerRow"], ow["tiles"], len(ow["blocks"])) == ("tilesets.overworld", 16, 96, 15)
    assert ow["walkable"] == sorted([W.GROUND, W.GRASS, W.DOOR, W.CAVE, W.FLOWER, W.STAND])
    assert (ow["grass"], ow["door"], ow["warp"], ow["counter"]) == (W.GRASS, [W.DOOR], [W.CAVE], [])
    assert ow["water"] == [0x14, 0x32, 0x48] and ow["shore"] == [0x32, 0x48]
    assert ow["cutTree"] == [0x3D] and ow["spinner"] == [] and ow["pads"] == []
    assert ow["animation"] == "TILEANIM_WATER_FLOWER"
    house = d[("tileset", "HOUSE")]
    assert house["water"] == [] and house["shore"] == [] and house["grass"] is None
    assert house["counter"] == [W.COUNTER] and house["tiles"] == 80
    assert d[("tileset", "GATE")]["image"] == "tilesets.house", "two tilesets share one atlas"
    gym = d[("tileset", "GYM")]
    assert gym["spinner"] == [0x3C, 0x3D, 0x4C, 0x4D] and gym["cutTree"] == [0x50]
    assert d[("tileset", "FACILITY")]["pads"] == [{"tile": 0x11, "kind": "hole"}, {"tile": 0x20, "kind": "pad"}]
    assert d[("tileset", "CAVERN")]["pads"] == [{"tile": 0x22, "kind": "hole"}]
    assert d[("tileset", "UNDERGROUND")]["tiles"] == 32


def test_animated_tiles_are_data(exported):
    """The voxel plan's V2: each tileset's animated tiles as the engine describes them, image paths as export ids."""
    d = exported["data"]
    ow = d[("tileset", "OVERWORLD")]["animated"]
    assert ow[0] == {"tiles": [0x14], "kind": "hshift", "period": 20, "offsets": [1, 2, 3, 2, 1, 0, 7, 0]}
    assert ow[1] == {"tiles": [0x03], "kind": "frames", "period": 20, "sequence": [1, 2, 3],
                     "images": ["tilesets.flower1", "tilesets.flower2", "tilesets.flower3"]}
    gym = d[("tileset", "GYM")]["animated"]
    assert gym == [{"tiles": [0x3C, 0x3D, 0x4C, 0x4D], "kind": "toggle", "image": "tilesets.spinners", "gate": "spinning",
                    "stripOffsets": [1, 3, 0, 2]}], "a toggle's strip offsets follow its tiles"
    assert d[("tileset", "HOUSE")]["animated"] == [], "no animation: an empty list"
    for (kind, _), data in d.items():
        if kind == "tileset":
            for entry in data["animated"]:
                for image in entry.get("images", []) + ([entry["image"]] if "image" in entry else []):
                    assert ("image", image) in d, "every animation image is exported"


def test_the_border_the_engine_draws(exported):
    """borderDrawn: OVERWORLD maps get the renderer's void fill (the tree wall by default), others their own block."""
    d = exported["data"]
    assert d[("map", "PALLET_TOWN")]["border"] == 2
    assert d[("map", "PALLET_TOWN")]["borderDrawn"] == 0x0F, "the tree wall around outdoor maps"
    assert d[("map", "REDS_HOUSE_1F")]["borderDrawn"] == d[("map", "REDS_HOUSE_1F")]["border"]
    display = d[("field", "all")]["display"]
    assert display == {"voidFill": "trees", "voidFillDefault": True}


@pytest.mark.parametrize("option,drawn", [("water", 0x43), ("black", None)])
def test_the_void_fill_option_is_exported_not_pinned(option, drawn):
    world = W.world()
    world["voidFill"] = option
    rig, peer = export_rig(world=world)
    town = request(rig, peer, "export.get", {"kind": "map", "id": "PALLET_TOWN"})["data"]["data"]
    assert town["borderDrawn"] == drawn
    field = request(rig, peer, "export.get", {"kind": "field", "id": "all"})["data"]["data"]
    assert field["display"] == {"voidFill": option, "voidFillDefault": False}


def test_images_are_the_engine_pngs(exported):
    world = W.world()
    d = exported["data"]
    images = {mid: v for (kind, mid), v in d.items() if kind == "image"}
    assert set(images) == {
        "tilesets.overworld", "tilesets.house", "tilesets.gym", "tilesets.facility", "tilesets.cavern",
        "tilesets.underground", "tilesets.flower1", "tilesets.flower2", "tilesets.flower3",
        "tilesets.spinners", "fx.cut_tree", "fx.emotes"}
    by_id = {}
    for path, (w, h, raw) in world["images"].items():
        folder, name = path.rsplit("/", 2)[-2:]
        by_id[f"{folder}.{name[:-4]}"] = (w, h, raw)
    for iid, img in images.items():
        w, h, raw = by_id[iid]
        assert (img["w"], img["h"]) == (w, h)
        assert base64.b64decode(img["png"]) == raw, iid


def test_sprites(exported):
    d = exported["data"]
    girl, mom, ball = (d[("sprite", s)] for s in ("SPRITE_TEST_GIRL", "SPRITE_TEST_MOM", "SPRITE_TEST_BALL"))
    assert (girl["w"], girl["h"], girl["frames"], girl["walker"]) == (16, 96, 6, True)
    assert (mom["h"], mom["frames"], mom["walker"]) == (48, 3, False)
    assert (ball["h"], ball["frames"], ball["walker"]) == (16, 1, False)
    assert base64.b64decode(girl["png"]) == W.world()["images"]["fake_cache/sprites/girl.png"][2]


# ------------------------------------------------------------ the singletons


def test_palettes(exported):
    p = exported["data"][("palettes", "sgb")]
    assert p["mode"] == "sgb" and set(p["palettes"]) == {"PALLET", "ROUTE", "CAVE", "MEWMON"}
    assert p["palettes"]["MEWMON"][1] == [200, 150, 150], "the named-record form, as triples"
    assert p["palettes"]["CAVE"][0] == [240, 232, 216]


def test_encounters(exported):
    e = exported["data"][("encounters", "all")]
    assert e["buckets"] == [51, 102, 141, 166, 191, 216, 229, 242, 253, 256]
    assert set(e["maps"]) == {"PALLET_TOWN", "TEST_CAVE"}, "a table without slots is left out"
    grass = e["maps"]["PALLET_TOWN"]["grass"]
    assert grass["rate"] == 25 and len(grass["slots"]) == 10
    assert grass["slots"][0] == {"species": "TESTMON_A", "dex": 16, "level": 2}
    assert set(e["maps"]["TEST_CAVE"]) == {"grass", "water"}
    assert e["rods"]["old"] == [{"species": "TESTMON_B", "dex": 19, "level": 5}]
    assert len(e["rods"]["good"]) == 2
    assert e["rods"]["super"] == {"PALLET_TOWN": [{"species": "TESTMON_B", "dex": 19, "level": 15}],
                                  "TEST_CAVE": [{"species": "TESTMON_A", "dex": 16, "level": 20}]}


def test_field_tables(exported):
    t = exported["data"][("field", "all")]["tables"]
    assert t["ledges"] == W.LEDGES
    assert t["bookshelves"] == {"HOUSE": {"61": {"kind": "books"}}}, "integer keys become strings"
    assert t["cutTreeSwaps"] == [{"before": 3, "after": 4}, {"before": 5, "after": 0}]
    assert t["darkMaps"]["maps"] == ["TEST_CAVE"]
    assert t["forcedMovement"] == {"slopeMaps": ["ROUTE_1"], "tiles": []}, "an empty Lua table is []"
    assert "townMap" not in t and "hiddenExtras" not in t, "only the listed tables"
    assert set(t) <= set(W.world()["registries"]["field"])


def test_names(exported):
    n = exported["data"][("names", "all")]
    assert n["species"] == {"TESTMON_A": {"dex": 16, "name": "SECRET MON A"},
                            "TESTMON_B": {"dex": 19, "name": "SECRET MON ♂"}}, "beyond 151 is left out"
    assert n["moves"]["TEST_GROWL"] == {"num": 45, "name": "SECRET GROWL"}
    assert n["items"]["TEST_KEY"] == {"num": 63, "name": "SECRET KEY", "key": True}
    assert n["items"]["TM_TEST"]["num"] == 201 and n["items"]["POTION"]["key"] is False


def test_swap_sites(exported):
    sites = exported["data"][("swaps", "all")]["sites"]
    assert sites == [
        {"map": "PALLET_TOWN", "bx": 2, "by": 2, "kind": "cut", "before": 3, "after": 4},
        # the trash-can door: closed is the engine's own $24 (data/scripts/seafoam.lua:21), whatever
        # the map ships; open is the field table's doorBlock
        {"map": "REDS_HOUSE_2F", "bx": 1, "by": 1, "kind": "script", "before": 0x24, "after": 2,
         "source": "EVENT_2ND_LOCK_OPENED"},
        {"map": "TEST_GYM", "bx": 0, "by": 0, "kind": "script", "before": 1, "after": 0,
         "source": "EVENT_TEST_POSTER"},
        {"map": "TEST_GYM", "bx": 2, "by": 1, "kind": "cut", "before": 5, "after": 0},
        {"map": "TEST_LAB", "bx": 1, "by": 0, "kind": "card_key", "before": 3, "after": 4,
         "source": "EVENT_TEST_DOOR"},
    ]


# ------------------------------------------------------------ roles, errors, caching


def test_only_builder_and_test_roles_may_export():
    rig, peer = export_rig(role="client")
    reply = request(rig, peer, "export.index", {})
    assert reply["type"] == "error" and reply["data"]["code"] == "role"
    reply = request(rig, peer, "export.get", {"kind": "map", "id": "PALLET_TOWN"})
    assert reply["type"] == "error" and reply["data"]["code"] == "role"
    assert status(rig)["exportState"] == 0, "nothing was built"


def test_not_found_and_bad_requests():
    rig, peer = export_rig(role="test")
    reply = request(rig, peer, "export.get", {"kind": "map", "id": "NO_SUCH_MAP"})
    assert reply["type"] == "error" and reply["data"]["code"] == "not_found"
    reply = request(rig, peer, "export.get", {"kind": "tileset", "id": "PALLET_TOWN"})
    assert reply["data"]["code"] == "not_found", "ids are per kind"
    reply = request(rig, peer, "export.get", {"kind": "nonsense", "id": "X"})
    assert reply["data"]["code"] == "bad_message"
    reply = request(rig, peer, "export.get", {"kind": "map", "id": "bad id with spaces"})
    assert reply["data"]["code"] == "bad_message"


def test_the_build_runs_once_and_is_cached():
    rig, peer = export_rig()
    first = request(rig, peer, "export.index", {})
    reads, loads = rig.E.datasetReads, rig.E.imageLoads
    second = request(rig, peer, "export.index", {})
    assert first["data"] == second["data"]
    assert (rig.E.datasetReads, rig.E.imageLoads) == (reads, loads)
    again = rig.connect()
    again.hello(role="builder")
    again.messages()
    third = request(rig, again, "export.index", {})
    assert third["data"] == first["data"] and rig.E.datasetReads == reads


def test_the_index_names_the_running_game_and_the_layout_its_region():
    # MG1: no coercion; the layout resource is named after the game's default region (the
    # catalogue's): kanto for Red and Blue, hoenn for Emerald
    rig, peer = export_rig(game="emerald", manifest={"id": "gen1-bridge", "games": ["emerald"]})
    index = request(rig, peer, "export.index", {})
    assert index["type"] == "export.index" and index["data"]["game"] == "emerald"
    keys = {(e["kind"], e["id"]) for e in index["data"]["resources"]}
    assert ("layout", "hoenn") in keys and ("layout", "kanto") not in keys


def test_a_game_outside_the_catalogue_fails_the_build():
    rig, peer = export_rig()
    rig.E.GameVersion.current = b"nosuch"  # after the handshake: the export never coerces it
    reply = request(rig, peer, "export.index", {})
    assert reply["type"] == "error" and reply["data"]["code"] == "export_failed"
    assert rig.status.exportState == b"failed"
    assert any("export.failed at=export.unknown_game" in line for line in rig.logs())


def test_requests_before_the_index_wait_for_the_build():
    rig, peer = export_rig()
    seq = peer.send("export.get", {"kind": "map", "id": "TEST_LAB"})
    msgs = pump(rig, peer, lambda ms: any(m.get("re") == seq for m in ms))
    reply = next(m for m in msgs if m.get("re") == seq)
    assert reply["type"] == "export.data" and reply["data"]["id"] == "TEST_LAB"


def test_a_new_connection_gets_no_replies_meant_for_the_old_one():
    rig, peer = export_rig()
    rig.b.clock_per_call = 0.001  # the build now spans many steps
    peer.send("export.index", {})
    rig.step(3)
    assert rig.status.exportState == b"building"
    newer = rig.connect()  # replaces the first connection mid-build
    assert peer.closed()
    newer.hello(role="builder")
    newer.messages()
    for i in range(1000):
        if i % 100 == 99:
            newer.send("ping", {"t": i})
        rig.step()
        assert not [m for m in newer.messages() if m["type"] in ("export.index", "export.data")]
        if rig.status.exportState == b"ready":
            break
    assert rig.status.exportState == b"ready"
    rig.step(5)
    assert not [m for m in newer.messages() if m["type"] in ("export.index", "export.data")]


# ------------------------------------------------------------ budget and serving


def test_the_build_is_spread_over_steps_within_the_budget():
    rig, peer = export_rig()
    rig.b.clock_per_call = 0.001  # every clock read costs 1 ms: the budget (6 ms) runs out fast
    seq = peer.send("export.index", {})
    steps = 0
    while True:
        steps += 1
        if steps % 100 == 0:
            peer.send("ping", {"t": steps})
        rig.step()
        msgs = peer.messages()
        if any(m.get("re") == seq for m in msgs):
            break
        assert steps < 3000
    rig.b.clock_per_call = 0
    st = status(rig)
    assert st["exportState"] == 2 and st["exportSteps"] > 20, st["exportSteps"]
    assert steps > 20
    assert st["exportMaxStepMs"] <= 40, "no single step ran far past the budget"
    assert st["exportDatasets"] is True and st["exportLive"] is False


def make_service(rig, **opts):
    """kb_export on its own, with a fake session that records replies."""
    b = rig.b
    Engine = b.module("lib/kb_engine.lua")
    eo = b.table()
    eo[b"require"] = b.env.require
    eo[b"datasets"] = rig.E.datasets
    engine = Engine.new(eo)
    engine.setGame(rig.E.game)
    sent = []
    sess = b.eval("""function(sent, py)
      local s = { gen = 1, queuedBytes = 0, handlers = {} }
      function s.on(t, fn) s.handlers[t] = fn end
      function s.generation() return s.gen end
      function s.established() return true end
      function s.queued() return s.queuedBytes end
      function s.replyEncoded(msg, t, text) py(t, msg.seq, #text) s.queuedBytes = s.queuedBytes + #text end
      function s.sendError(code, re) py('error:' .. code, re, 0) end
      return s
    end""")(None, lambda t, seq, size: sent.append((t.decode(), seq, size)))
    o = b.table()
    # a Lua clock: lupa hands a Python callback that reads Lua tables the wrong state while a
    # Lua coroutine (the build) is running
    clock = b.eval("function(c) return function() return c.now end end")(b._clock)
    for key, value in {"json": b.module("lib/kb_json.lua"), "sha256": b.module("lib/kb_sha256.lua"),
                       "clock": clock, "engine": engine, "Cells": b.module("export/kb_cells.lua"),
                       "Images": b.module("export/kb_images.lua"),
                       "SwapSites": b.module("export/kb_swapsites.lua"),
                       "catalog": b.module("lib/kb_catalog.lua"), "bridge": b"0.1.0",
                       "games": b.module("lib/kb_games.lua")}.items():
        o[key.encode()] = value
    for key, value in opts.items():
        o[key.encode()] = value
    service = b.module("export/kb_export.lua").new(o)
    service.attach(sess)
    return service, sess, sent


def test_serving_is_budgeted_and_waits_for_a_slow_reader():
    rig = Rig(world=W.world())
    service, sess, sent = make_service(rig, serveBytes=4000, backlog=20000)
    msg = rig.b.eval("function(seq) return { seq = seq } end")
    role = b"builder"
    sess.handlers[b"export.index"](rig.b.table(), msg(1), role)
    for _ in range(50):
        service.step()
        if sent:
            break
    assert sent[0][:2] == ("export.index", 1)
    ids = [("map", m) for m in sorted(W.world()["registries"]["maps"])]
    for n, (kind, mid) in enumerate(ids, start=2):
        data = rig.b.to_lua({"kind": kind, "id": mid})
        sess.handlers[b"export.get"](data, msg(n), role)
    before = len(sent)
    service.step()
    first_step = sent[before:]
    assert 1 <= len(first_step) < len(ids), "at most about serveBytes per step"
    assert sum(size for _, _, size in first_step[:-1]) < 4000
    sess.queuedBytes = 25000  # the reader is behind: nothing more until it drains
    service.step()
    assert len(sent) == before + len(first_step)
    sess.queuedBytes = 0
    for _ in range(50):
        service.step()
    assert len(sent) == before + len(ids)
    assert [s for _, s, _ in sent[before:]] == list(range(2, 2 + len(ids))), "in request order"


def test_stale_requests_are_dropped():
    rig = Rig(world=W.world())
    service, sess, sent = make_service(rig)
    msg = rig.b.eval("function(seq) return { seq = seq } end")
    sess.handlers[b"export.index"](rig.b.table(), msg(1), b"builder")
    sess.gen = 2  # a new connection before the reply went out
    for _ in range(50):
        service.step()
    assert sent == [] and service.state() == b"ready"


# ------------------------------------------------------------ data sources


def test_datasets_unavailable_copies_the_live_data_at_boot():
    rig, peer = export_rig(dataset_mode="missing")
    assert any("export.live_copy" in line and "when=boot" in line and "reason=not_imported" in line
               for line in rig.logs())
    index = request(rig, peer, "export.index", {})["data"]
    st = status(rig)
    assert st["exportLive"] is True and st["exportBootLive"] is True and st["exportDatasets"] is False
    other, other_peer = export_rig()
    assert index == request(other, other_peer, "export.index", {})["data"], "the same export either way"


def test_a_broken_view_falls_back_while_the_live_data_is_pristine():
    rig, peer = export_rig(dataset_mode="broken")
    index = request(rig, peer, "export.index", {})
    assert index["type"] == "export.index" and len(index["data"]["resources"]) == 42
    assert any("export.live_copy" in line and "when=export" in line for line in rig.logs())
    assert status(rig)["exportLive"] is True


def test_no_fallback_once_a_map_has_loaded():
    rig, peer = export_rig(dataset_mode="broken")
    rig.b.emit("map.entered", rig.b.table())
    reply = request(rig, peer, "export.index", {})
    assert reply["type"] == "error" and reply["data"]["code"] == "export_failed"
    assert any("export.no_pristine_data" in line for line in rig.logs())
    assert any(line.startswith("[error] [gen1-bridge] export.failed at=export.no_data") for line in rig.logs())
    reply = request(rig, peer, "export.get", {"kind": "map", "id": "PALLET_TOWN"})
    assert reply["data"]["code"] == "export_failed"
    assert status(rig)["exportState"] == 3


def test_the_view_is_read_not_the_live_tables():
    world = W.world()
    rig, peer = export_rig(world=world)
    rig.E.data.maps = rig.b.table()  # the live tables are never consulted on the datasets path
    data = request(rig, peer, "export.get", {"kind": "map", "id": "PALLET_TOWN"})
    assert data["type"] == "export.data"


# ------------------------------------------------------------ the shape profile (the profile plan)

PROFILE_PATH = "mods/DRAMALESS_SHAPE/data/voxel_heights.lua"
# made-up profile text; the marker must never reach a log line
PROFILE_TEXT = "-- MADE_UP_MARKER\nreturn { version = 1, heights = { wall = 16 } }\n"


def profile_loader(text=PROFILE_TEXT, disabled=("DRAMALESS_SHAPE",), version="2.0.4"):
    return {"mods": {"DRAMALESS_SHAPE": {"version": version, "path": "mods/DRAMALESS_SHAPE"},
                     "gen1-bridge": {"version": "0.1.0", "path": "mods/gen1-bridge"}},
            "files": {PROFILE_PATH: text}, "disabled": list(disabled)}


def test_an_installed_profile_is_exported_as_text():
    rig, peer = export_rig(loader=profile_loader())
    index = request(rig, peer, "export.index", {})["data"]
    entries = {(e["kind"], e["id"]) for e in index["resources"]}
    assert ("profile", "dramaless") in entries
    page = request(rig, peer, "export.get", {"kind": "profile", "id": "dramaless"})
    assert page["type"] == "export.data"
    data = page["data"]["data"]
    assert not PROTOCOL.check_export("profile", data)
    assert data == {"mod": "DRAMALESS_SHAPE", "version": "2.0.4", "file": "data/voxel_heights.lua", "enabled": False,
                    "text": PROFILE_TEXT}
    assert page["data"]["hash"] == hashlib.sha256(canonical(data).encode("utf-8")).hexdigest()
    found = [line for line in rig.logs() if "export.profile_found" in line]
    assert found and "enabled=false" in found[0] and f"bytes={len(PROFILE_TEXT)}" in found[0]
    assert not any("MADE_UP_MARKER" in line for line in rig.logs())


def test_an_enabled_profile_mod_is_reported_as_enabled():
    rig, peer = export_rig(loader=profile_loader(disabled=()))
    data = request(rig, peer, "export.get", {"kind": "profile", "id": "dramaless"})["data"]["data"]
    assert data["enabled"] is True


def test_an_odd_version_is_exported_as_unknown():
    rig, peer = export_rig(loader=profile_loader(version="2.0.4 beta!"))
    data = request(rig, peer, "export.get", {"kind": "profile", "id": "dramaless"})["data"]["data"]
    assert data["version"] == "unknown"


@pytest.mark.parametrize("loader, reason", [
    (None, "no_loader"),
    ({"mods": {}, "files": {}}, "absent"),
    ({"mods": {"DRAMALESS_SHAPE": {"version": "2.0.4", "path": "mods/DRAMALESS_SHAPE"}}, "files": {}}, "no_file"),
    (profile_loader(text="x" * (1024 * 1024)), "too_big"),
])
def test_without_a_readable_profile_there_is_no_profile_resource(loader, reason):
    rig, peer = export_rig(loader=loader)
    index = request(rig, peer, "export.index", {})["data"]
    assert not [e for e in index["resources"] if e["kind"] == "profile"]
    assert any("export.profile_absent" in line and f"reason={reason}" in line for line in rig.logs())
    reply = request(rig, peer, "export.get", {"kind": "profile", "id": "dramaless"})
    assert reply["type"] == "error" and reply["data"]["code"] == "not_found"


# ------------------------------------------------------------ what can't be exported


def test_bad_data_is_left_out_and_counted():
    world = W.world()
    reg = world["registries"]
    reg["maps"]["TEST_BROKEN"] = W.map_record("TEST_BROKEN", 90, "HOUSE", [[0, 77]])  # block 77 doesn't exist
    reg["maps"]["TEST_NO_INDEX"] = W.map_record("TEST_NO_INDEX", 91, "HOUSE", [[0]])
    del reg["maps"]["TEST_NO_INDEX"]["index"]
    reg["maps"]["TEST_COLOSSEUM"]["objects"].append({"index": 1, "sprite": "SPRITE_TEST_MOM", "x": 0, "y": 0})
    reg["field"]["forcedMovement"]["speed"] = 0.5
    reg["sprites"]["SPRITE_TEST_MISSING"] = {"id": "SPRITE_TEST_MISSING", "image": "fake_cache/sprites/none.png",
                                             "frames": 1}
    reg["sprites"]["SPRITE_TEST_SHORT"] = {"id": "SPRITE_TEST_SHORT", "image": "fake_cache/sprites/mom.png",
                                           "frames": 6, "walker": True}
    reg["encounters"]["PALLET_TOWN"]["grass"]["slots"][0]["species"] = "NO_SUCH_MON"
    rig, peer = export_rig(world=world)
    index = request(rig, peer, "export.index", {})["data"]
    ids = {(e["kind"], e["id"]) for e in index["resources"]}
    assert ("map", "TEST_BROKEN") not in ids and ("map", "TEST_NO_INDEX") not in ids
    assert ("sprite", "SPRITE_TEST_MISSING") not in ids
    logs = rig.logs()
    for code in ("map_bad_blocks", "map_no_index", "object_bad", "field_number", "sprite_load_failed",
                 "encounter_bad_slot"):
        assert any(f"export.warning code={code} count=1" in line for line in logs), code
    pages = fetch_all(rig, peer, index)
    for (kind, rid), page in pages.items():
        assert not PROTOCOL.check_export(kind, page["data"]["data"]), (kind, rid)
    short = pages[("sprite", "SPRITE_TEST_SHORT")]["data"]["data"]
    assert (short["frames"], short["walker"]) == (3, False), "a sheet shorter than its record"
    slots = pages[("encounters", "all")]["data"]["data"]["maps"]["PALLET_TOWN"]["grass"]["slots"]
    assert len(slots) == 9
    st = status(rig)
    assert st["exportWarnings"] >= 7


def test_field_tables_with_mixed_keys_become_objects():
    world = W.world()
    world["registries"]["field"]["badgeGates"] = {1: "GATE_A", 2: "GATE_B", "extra": {3: True}}
    rig, peer = export_rig(world=world)
    data = request(rig, peer, "export.get", {"kind": "field", "id": "all"})["data"]["data"]
    assert data["tables"]["badgeGates"] == {"1": "GATE_A", "2": "GATE_B", "extra": {"3": True}}


def test_a_cell_two_ledge_rows_claim_keeps_the_first_and_warns():
    rig = Rig()
    cells_mod = rig.b.module("export/kb_cells.lua")
    # a 2x2-cell map: (0,1) is ledge tile 7 with ground 0 above it and to its right
    q = rig.b.eval("""function()
      local tiles = { [0] = { [0] = 0, [1] = 0 }, [1] = { [0] = 7, [1] = 0 } }
      local q = { w = 2, h = 2 }
      function q.tile(x, y) return tiles[y][x] end
      local no = function() return false end
      q.walkable, q.grass, q.water, q.door, q.counter = no, no, no, no, no
      function q.padOrHole() return nil end
      function q.block() return 0 end
      return q
    end""")()
    warned = []
    rows_lua = rig.b.to_lua([
        {"facing": "down", "input": "down", "standingTile": 0, "ledgeTile": 7},
        {"facing": "left", "input": "left", "standingTile": 0, "ledgeTile": 7},
        {"facing": "up", "input": "down", "standingTile": 0, "ledgeTile": 7},  # facing != input: never a hop
    ])
    ctx = rig.b.table()
    ctx[b"ledges"] = cells_mod.ledgeRows(rows_lua, b"OVERWORLD")
    ctx[b"warn"] = lambda code: warned.append(code)
    rows = cells_mod.compute(q, ctx)
    assert rows[2][1] == LEDGE | HOP["down"] << 14
    assert warned == [b"ledge_multi"]


def test_logs_never_carry_game_text(exported):
    rig = exported["rig"]
    for line in rig.logs():
        assert "SECRET" not in line and "fake_cache" not in line
    forwarded = [m for m in exported["seen"] if m["type"] == "log"]
    assert [m["data"]["code"] for m in forwarded] == ["export.warning"], "the palette warning, forwarded"
    for m in forwarded:
        assert "SECRET" not in json.dumps(m) and "fake_cache" not in json.dumps(m)


def test_debug_status_reports_the_export(exported):
    st = status(exported["rig"])
    assert st["exportState"] == 2 and st["exportResources"] == 42
    assert st["exportServed"] >= 41 and st["exportBytes"] > 10000 and st["exportLargest"] > 1000
    assert st["exportDatasets"] is True and st["exportLive"] is False and st["exportBootLive"] is False
    assert st["exportWarnings"] == 1, "palette_ambiguous"


# ------------------------------------------------------------ helper modules


@pytest.mark.parametrize("size", [0, 1, 2, 3, 4, 5, 6, 7, 100, 1000, 4097])
def test_base64_matches_python(size):
    rig = Rig()
    images = rig.b.module("export/kb_images.lua")
    raw = bytes((i * 7 + size) % 256 for i in range(size))
    assert images.base64(raw) == base64.b64encode(raw)


@pytest.mark.parametrize("path, expected", [
    (b"some/where/tilesets/overworld.png", b"tilesets.overworld"),
    (b"fx/cut_tree.png", b"fx.cut_tree"),
    (b"sprites/red_bike.PNG", b"sprites.red_bike"),
    (b"overworld.png", None),
    (b"a/b c/d.png", None),
])
def test_image_ids_come_from_the_path(path, expected):
    rig = Rig()
    images = rig.b.module("export/kb_images.lua")
    assert images.idFor(path) == expected


def test_kb_catalog_carries_the_export_page_limit():
    rig = Rig()
    assert rig.b.module("lib/kb_catalog.lua").exportPageBytes == 1048576
