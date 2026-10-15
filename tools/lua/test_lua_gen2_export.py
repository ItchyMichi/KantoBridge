"""MG9 G2D (docs/specs/GEN2_BRIDGE_PLAN.md §6): the Gen 2 adapter's export part (gen1-bridge/lib/engine/gen2/
export.lua) on the fake Gen 2 engine's made-up world (fake_export2.lua). The adapter alone through a recording sink:
every g2* resource and its schema; a map's blocks (the copy taken at boot, not the live ones), border, connections,
warps (0-based, a remembered one), events and objects (kinds, flags, hour windows, walkers, a variable sprite, a big
doll, a palette override); the art variants (one per tileset, palette set and roof; the roof sheet over tiles
$0a-$12; Crystal's bank-1 tiles), the baked pixels on the pages, the collision bytes and what they mean; animated
tiles (water from its strip, a current scrolled, loops from the timer's 0); sprites (keyed shade 0, an override
palette's own sheet); the layout per area; encounters by national dex; names; the swap sites. Then main.lua with a
builder peer: export.index lists the g2* kinds and export.get serves one. Everything is made up."""

import base64
import json
import struct

import pytest

from adapter2 import Adapter2, plain
from kb_protocol import load_protocol
from rig import Rig

pytestmark = pytest.mark.lua

PROTOCOL = load_protocol()
G2_KINDS = {"g2map", "g2tiles", "g2image", "g2anims", "g2sprite", "g2layout", "g2encounters", "g2names", "swaps"}


def exported(a, region="johto"):
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
    a = Adapter2()
    a.E.exportWorld()
    a.eng.capturePristine(a.E.data)
    return a


def png_rgba(b64):
    raw = base64.b64decode(b64)
    assert raw[:8] == b"\x89PNG\r\n\x1a\n" and raw[12:16] == b"IHDR"
    w, h = struct.unpack(">II", raw[16:24])
    at = raw.index(b"RAW") + 3
    return w, h, raw[at:]


def tile_px(res, tid, x=0, y=0):
    """One pixel (RGBA bytes) of baked tile `tid` from its page."""
    _, _, rgba = png_rgba(res[("g2image", f"g2q.{tid // 1024}")]["png"])
    q = tid % 1024
    px, py = (q % 32) * 8 + x, (q // 32) * 8 + y
    return rgba[(py * 256 + px) * 4:(py * 256 + px) * 4 + 4]


def test_every_resource_passes_its_schema():
    res, warns, ok, err = exported(world())
    assert ok is True and err is None
    for (kind, rid), data in res.items():
        assert kind in G2_KINDS
        problems = PROTOCOL.check_export(kind, data)
        assert not problems, (kind, rid, [str(p) for p in problems])
    assert {k for k, _ in res} == G2_KINDS
    assert sorted(i for k, i in res if k == "g2map") == [
        "G2_NEW_BARK_TOWN", "G2_PALLET_TOWN", "G2_TEST_CAVE", "G2_TEST_CENTER", "G2_TEST_HOUSE", "G2_TEST_HOUSE_2F",
        "G2_TEST_ROUTE", "G2_TEST_TOWN"]
    # one night slot names a species the game doesn't have
    assert warns == ["g2_encounter_slot"]


def test_a_maps_blocks_border_and_where_it_is():
    a = world()
    town = a.E.maps[b"TEST_TOWN"]
    town.blocks[3] = 9          # a live edit after boot (a dropped World's): the export keeps the boot copy
    res, _, _, _ = exported(a)
    m = res[("g2map", "G2_TEST_TOWN")]
    raw = base64.b64decode(m["blocks"])
    assert (m["w"], m["h"], len(raw)) == (10, 10, 100)
    assert (raw[0], raw[1], raw[2]) == (3, 2, 1), "the boot copy"
    assert (m["border"], m["tileset"], m["env"], m["palette"], m["daytime"]) == (1, "TILESET_TEST", "TOWN",
                                                                                 "PALETTE_AUTO", "DAY")
    assert (m["group"], m["num"], m["area"], m["fishGroup"]) == (3, 1, "johto", "FISHGROUP_TEST")
    assert m["connections"] == [{"dir": "north", "map": "G2_TEST_ROUTE", "offset": 1},
                                {"dir": "west", "map": "G2_NEW_BARK_TOWN", "offset": 2}]
    assert m["warps"] == [{"x": 4, "y": 4, "warp": 1, "to": "G2_TEST_HOUSE"},
                          {"x": 6, "y": 4, "warp": 0, "dynamic": True}]
    assert m["coords"] == [{"x": 2, "y": 3, "scene": 1}]
    assert m["signs"] == [{"x": 5, "y": 1, "kind": 0}, {"x": 7, "y": 1, "kind": 7}]
    cave = res[("g2map", "G2_TEST_CAVE")]
    assert (cave.get("dark"), cave["daytime"], cave["env"]) == (True, "NITE", "CAVE"), "a dark map baked as after Flash"
    assert res[("g2map", "G2_PALLET_TOWN")]["area"] == "kanto"


def test_objects_kinds_flags_hours_walkers_and_big_ones():
    res, _, _, _ = exported(world())
    objs = {o["lid"]: o for o in res[("g2map", "G2_TEST_TOWN")]["objects"]}
    assert objs[1] == {"lid": 1, "x": 3, "y": 4, "sprite": "SPRITE_TEST_LASS", "movement": 6, "kind": "person",
                       "stays": True}
    assert objs[2] == {"lid": 2, "x": 6, "y": 2, "sprite": "SPRITE_TEST_BALL", "movement": 1, "kind": "item",
                       "palette": 0, "flag": 40, "hours": [18, 3], "stays": True}
    assert (objs[3]["kind"], objs[3]["stays"]) == ("trainer", False), "a wanderer with room to walk"
    assert (objs[4].get("sprite"), objs[4]["var"], objs[4]["big"], objs[4]["stays"]) == (None, 0xf3, "asym", True)


def test_art_variants_baked_pixels_and_collision_meanings():
    res, _, _, _ = exported(world())
    variants = {i: t for (k, i), t in res.items() if k == "g2tiles"}
    # TILESET_TEST at DAY outdoors (TOWN, group 3: roof colours), indoors (another pool row) and in the dark cave
    # (its NITE look); TILESET_JOHTO with group 3's roof sheet
    maps = {i: m for (k, i), m in res.items() if k == "g2map"}
    assert maps["G2_TEST_TOWN"]["variant"] == maps["G2_TEST_ROUTE"]["variant"] != maps["G2_TEST_HOUSE"]["variant"]
    assert maps["G2_TEST_HOUSE"]["variant"] == maps["G2_TEST_CENTER"]["variant"]
    assert variants[maps["G2_PALLET_TOWN"]["variant"]]["roof"] == "ROOF_TEST"
    town = variants[maps["G2_TEST_TOWN"]["variant"]]
    assert len(town["blocks"]) == 128 and len(town["coll"]) == 128
    assert town["coll"][3] == [0x12, 0xa3, 0xb2, 0x24]
    # block 0's tile 0 (shade 0 at its top-left pixel) through palette slot 1: pool 1's colour 1 = (11, 20, 7)
    t0 = town["blocks"][0][0]
    assert tile_px(res, t0) == bytes((11, 20, 7, 255))
    # tile 1 (slot 2, pool 2) at (0, 0): shade (1 + 0 + 0) % 4 = 1 -> colour 2 = (22, 40, 7)
    assert tile_px(res, town["blocks"][0][1]) == bytes((22, 40, 7, 255))
    # tile 6 draws with slot 7, the roof slot: outdoors group 3's day roof colours replace colours 2 and 3
    # (shades 1 and 2); its pixel (0, 0) is shade (6 + 0 + 0) % 4 = 2, (3, 0) shade 1, (1, 0) shade 3
    roofed = town["blocks"][0][6]
    assert tile_px(res, roofed, 0, 0) == bytes((0, 200, 0, 255))
    assert tile_px(res, roofed, 3, 0) == bytes((200, 0, 0, 255))
    assert tile_px(res, roofed, 1, 0) == bytes((74, 80, 7, 255))
    indoor = variants[maps["G2_TEST_HOUSE"]["variant"]]
    assert tile_px(res, indoor["blocks"][0][6], 0, 0) == bytes((153, 60, 7, 255)), "indoors: pool 15's own colour 3"
    # the roof sheet: TILESET_JOHTO's tiles $0a-$12 are the roof's (shade 3), the rest the tileset's own
    pallet = variants[maps["G2_PALLET_TOWN"]["variant"]]
    roof_tile = pallet["blocks"][0][0]
    plain_tile = pallet["blocks"][0][9]
    assert tile_px(res, roof_tile)[:3] != tile_px(res, plain_tile)[:3]
    meanings = town["meanings"]
    assert meanings["0"] == {"perm": "land"}
    assert meanings["24"] == {"perm": "land", "grass": True, "encounter": True, "cuttable": True}
    assert meanings["113"] == {"perm": "land", "warp": "door", "facesDown": True}
    assert meanings["112"] == {"perm": "land", "warp": "edge", "mat": "down"}
    assert meanings["163"] == {"perm": "land", "ledge": ["down"]}
    assert meanings["178"] == {"perm": "land", "sides": ["up"], "neighbor": {"down": "down"}}
    assert meanings["36"] == {"perm": "water", "whirlpool": True}
    assert meanings["18"] == {"perm": "wall", "cutTree": True, "cuttable": True}


def test_identical_pixels_share_a_tile_and_pages_hold_them():
    res, _, _, _ = exported(world())
    variants = {i: t for (k, i), t in res.items() if k == "g2tiles"}
    named = {q for t in variants.values() for b in t["blocks"] for q in b}
    pages = sorted(int(i.split(".")[1]) for k, i in res if k == "g2image")
    assert pages == list(range(len(pages)))
    assert all(q // 1024 in pages for q in named)
    town = next(t for t in variants.values() if t["id"] == res[("g2map", "G2_TEST_TOWN")]["variant"])
    # blocks 0 and 3 hold the same tiles: the same ids
    assert town["blocks"][0] == town["blocks"][3]
    for (k, i), d in res.items():
        if k == "g2image":
            w, h, rgba = png_rgba(d["png"])
            assert (w, h, len(rgba)) == (256, 256, 256 * 256 * 4)


def test_animated_tiles_have_tiles_of_their_own_frames_and_loops():
    res, _, _, _ = exported(world())
    anims = res[("g2anims", "all")]["anims"]
    town = next(t for (k, i), t in res.items() if k == "g2tiles" and i == res[("g2map", "G2_TEST_TOWN")]["variant"])
    water_q, current_q = town["blocks"][0][2], town["blocks"][0][9]
    by_q = {a["q"]: a for a in anims}
    water, current = by_q[water_q], by_q[current_q]
    # water: four strip rows, two timer ticks each, a tick every 3 frames (the program's period)
    assert len(water["f"]) == 4 and water["s"] == [[0, 6], [1, 6], [2, 6], [3, 6]]
    # its page holds the frame at timer 0: the strip's row 1 (shade 0) through tile 2's palette slot (3)
    assert tile_px(res, water_q) == tile_px(res, water["f"][0])
    # a current scrolled right then back: offsets 0 1 2 3 2 1 0 7 over the timer; the made-up tile repeats every
    # four pixels, so offset 7 looks like offset 3 and is the same frame
    assert current["s"] == [[0, 3], [1, 3], [2, 3], [3, 3], [2, 3], [1, 3], [0, 3], [3, 3]]
    assert len(current["f"]) == 4
    assert water_q not in water["f"], "the animated tile is a tile of its own"


def test_sprites_keyed_with_their_palettes_and_an_override():
    res, _, _, _ = exported(world())
    sprites = {i: d for (k, i), d in res.items() if k == "g2sprite"}
    assert sorted(sprites) == ["SPRITE_TEST_BALL", "SPRITE_TEST_BALL.p0", "SPRITE_TEST_LASS"]
    lass = sprites["SPRITE_TEST_LASS"]
    assert (lass["w"], lass["h"], lass["frames"], lass["walker"], lass["type"], lass["palette"]) == (
        16, 32, 2, True, "WALKING_SPRITE", 0)
    w, h, rgba = png_rgba(lass["png"])
    assert (w, h) == (16, 32)
    assert rgba[0:4] == bytes((255, 255, 255, 0)), "shade 0 is cut out"
    assert rgba[4:8] == bytes((1, 1, 1, 255)), "shade 1 through PAL_OW_RED's DAY colours"
    ball, red_ball = sprites["SPRITE_TEST_BALL"], sprites["SPRITE_TEST_BALL.p0"]
    assert (ball["palette"], red_ball["palette"], red_ball["sprite"]) == (1, 0, "SPRITE_TEST_BALL")
    assert png_rgba(ball["png"])[2][:4] == bytes((8, 8, 8, 255)) and png_rgba(red_ball["png"])[2][:4] == bytes(
        (2, 2, 2, 255))


def test_the_layout_per_area_and_the_start():
    res, _, _, _ = exported(world())
    johto, kanto = res[("g2layout", "johto")], res[("g2layout", "kanto")]
    assert (johto["root"], johto["unit"], johto["start"]) == ("G2_NEW_BARK_TOWN", "blocks",
                                                              {"map": "G2_TEST_HOUSE_2F", "x": 3, "y": 3})
    # TEST_TOWN east of New Bark (4x4) two blocks down; TEST_ROUTE north of TEST_TOWN one block right
    assert johto["maps"] == {"G2_NEW_BARK_TOWN": {"x": 0, "y": 0, "w": 4, "h": 4},
                             "G2_TEST_TOWN": {"x": 4, "y": -2, "w": 10, "h": 10},
                             "G2_TEST_ROUTE": {"x": 5, "y": -8, "w": 4, "h": 6}}
    assert kanto == {"root": "G2_PALLET_TOWN", "unit": "blocks",
                     "maps": {"G2_PALLET_TOWN": {"x": 0, "y": 0, "w": 5, "h": 5}}}


def test_encounters_and_names_by_number():
    res, _, _, _ = exported(world())
    enc = res[("g2encounters", "all")]
    grass = enc["grass"]["G2_TEST_TOWN"]
    assert grass["rates"] == {"MORN": 10, "DAY": 20, "NITE": 30}
    assert grass["slots"] == {"MORN": [{"dex": 1, "level": 2}], "DAY": [{"dex": 2, "level": 3}],
                              "NITE": [{"dex": 3, "level": 4}]}
    assert enc["water"]["G2_TEST_TOWN"] == {"rate": 5, "slots": [{"dex": 2, "level": 10}]}
    assert enc["fish"]["FISHGROUP_TEST"] == {"chance": 128, "old": [{"dex": 1, "level": 5, "chance": 255}], "good": [],
                                             "super": [{"dex": 2, "level": 20, "chance": 255,
                                                        "nite": {"dex": 3, "level": 21}}]}
    assert (enc["trees"], enc["contest"]) == ({"G2_TEST_TOWN": "TREEMON_SET_TEST"},
                                              [{"dex": 2, "min": 7, "max": 18, "chance": 20}])
    assert enc["treeSets"] == {"TREEMON_SET_TEST": {"common": [{"dex": 1, "level": 7, "chance": 50}], "rare": []}}
    assert (enc["grassChances"], enc["waterChances"]) == ([30, 60, 80, 90, 95, 99, 100], [60, 90, 100])
    names = res[("g2names", "all")]
    assert names == {"species": {"TEST_ONE": 1, "TEST_TWO": 2, "TEST_THREE": 3},
                     "moves": {"TEST_TACKLE": 1, "TEST_SPLASH": 2}, "items": {"TEST_POTION": 1, "TEST_BALL": 2}}


def test_the_swap_sites_are_the_storys():
    a = world()
    res, _, _, _ = exported(a)
    sites = res[("swaps", "all")]["sites"]
    assert sites == [{"map": "G2_TEST_TOWN", "bx": 4, "by": 0, "kind": "cut", "before": 0x40, "after": 0x41}]
    assert sites == plain(a.eng.swapSites())


def test_without_the_engines_art_there_is_no_export():
    a = world()
    a.E.data.gen2Tilesets = None
    res, warns, ok, err = exported(a)
    assert ok is False and err == b"no_art_data" and res == {}


# ------------------------------------------------------------ through main.lua


def test_main_serves_the_g2_kinds_to_a_builder():
    rig = Rig(family="gen2", ready=False)
    rig.E.exportWorld()
    rig.b.mods_loaded()
    rig.b.ready()
    peer = rig.connect()
    peer.hello(role="builder")
    peer.messages()
    seq = peer.send("export.index", {})
    msgs = []
    for _ in range(400):
        rig.step()
        msgs.extend(peer.messages())
        if any(m.get("re") == seq for m in msgs):
            break
    index = next(m for m in msgs if m.get("re") == seq)
    assert index["type"] == "export.index", index
    kinds = {e["kind"] for e in index["data"]["resources"]}
    assert kinds == G2_KINDS | {"routes"}, sorted(kinds)
    entry = next(e for e in index["data"]["resources"] if e["kind"] == "g2map" and e["id"] == "G2_TEST_TOWN")
    seq = peer.send("export.get", {"kind": "g2map", "id": "G2_TEST_TOWN"})
    got = []
    for _ in range(50):
        rig.step()
        got.extend(peer.messages())
        if any(m.get("re") == seq for m in got):
            break
    data = next(m for m in got if m.get("re") == seq)
    assert data["type"] == "export.data" and data["data"]["hash"] == entry["hash"]
    assert data["data"]["data"]["variant"].startswith("TILESET_TEST_")
