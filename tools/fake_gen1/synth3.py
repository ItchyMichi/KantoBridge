"""A synthetic Gen 3 export (MG7, docs/specs/GEN3_BRIDGE_PLAN.md §5, §8.4): the g3* resources of a tiny made-up
Hoenn, so the builder's JUnit tests and GameTests can build a Gen 3 region without game data. Everything is made up:
maps EM_TEST_*, tileset pairs TEST_PAIR_*, metatiles, colours, sprites and species. The shapes and the cell values
follow the real export (gen1-bridge/lib/engine/game3/export.lua) and the engine's collision bytes (Gen1Recomp
src/core/game3/scripting/collision_frlg.lua: 0x00 walk, 0x07 blocked, 0x18 tall grass, 0x29 water, 0x71 door, 0x72
warp keeping the facing, 0x82 sign, 0x90 counter, 0xA0-0xA3 ledges east, west, north, south).

    resources()            {(kind, id): data}
    index(...)             synth.index's shape, game "emerald"
"""
import base64
import struct
import zlib

from . import synth

GAME = "emerald"
PAGE_SIDE = 32
WALK, BLOCKED, GRASS, WATER, DOOR, WARP_FACE, SIGN, COUNTER, LEDGE_S = 0x00, 0x07, 0x18, 0x29, 0x71, 0x72, 0x82, 0x90, 0xA3

# Behaviour numbers: made up, with the engine's MB_* names.
BEHAVIORS = {0: "MB_NORMAL", 2: "MB_TALL_GRASS", 0x11: "MB_POND_WATER", 0x3B: "MB_JUMP_SOUTH", 0x69: "MB_ANIMATED_DOOR",
             0x80: "MB_COUNTER", 0x65: "MB_SOUTH_ARROW_WARP"}

# The behaviours whose cells roll land encounters (the engine's encounter tile bit without the surfable one).
LAND_BEHAVIORS = {2}

# The pairs: metatile -> (behaviour, its look: a colour and whether its quarters differ, over-plane art)
PAIRS = {
    "TEST_PAIR_A": {
        0: (0, (90, 170, 90), False, False),        # ground
        1: (2, (40, 140, 40), True, False),         # tall grass
        2: (0x11, (60, 90, 220), True, False),      # water
        3: (0, (150, 110, 80), False, False),       # a house wall
        4: (0x69, (110, 70, 40), True, False),      # its door
        5: (0, (30, 100, 40), True, True),          # a tree (drawn over sprites)
        6: (0x3B, (120, 160, 90), True, False),     # a ledge, jumping south
        7: (0, (200, 180, 120), True, False),       # a sign
    },
    "TEST_PAIR_B": {
        512: (0, (210, 190, 150), False, False),    # floor
        513: (0, (120, 120, 130), False, False),    # wall
        514: (0x65, (180, 60, 60), True, False),    # the exit mat
        515: (0x80, (160, 120, 60), True, False),   # a counter
    },
}

# The maps: (pair, w, h, trueW, trueH, rows of (metatile, collision)) plus their events
_T = {".": (0, WALK), "g": (1, GRASS), "~": (2, WATER), "#": (3, BLOCKED), "D": (4, DOOR), "T": (5, BLOCKED),
      "v": (6, LEDGE_S), "s": (7, SIGN)}
_H = {".": (512, WALK), "#": (513, BLOCKED), "M": (514, WARP_FACE), "c": (515, COUNTER)}
TOWN = ["TTTTTTTT",
        "T......T",
        "T.##s..T",
        "T.#D...T",
        "vvvv..vv",
        "........"]
ROUTE = ["gggg~~",
         "gggg~~",
         "......",
         "gg....",
         "......"]
# A raised grass patch: elevation 4 at the west end of row 3, reached through a cell of elevation 0 (stairs: any
# elevation steps on it); the rest of the route is the ground's 3, so the patch's other sides are walls.
ROUTE_ELEV = {(0, 3): 4, (1, 3): 4, (2, 3): 0}
# The land slots' chances, as the engine's roll uses them (made up here: Gen 3's well-known shape).
LAND_WEIGHTS = [20, 20, 10, 10, 10, 10, 5, 5, 4, 4, 1, 1]
# MG8 (docs/specs/HOENN_PASS_PLAN.md HP5): the pass variant's town, the same with two rows of trees below it, a
# clearing cut through them under the cells (4, 4)-(5, 5) as the adapter opens one (exit block (2, 2) facing down,
# depth 1: the cleared block (2, 3) baked in with the exit's metatile), and a trainer by the clearing.
PASS_TOWN = TOWN + ["TTTT..TT",
                    "TTTT..TT"]
PASS_ROUTE = "test_pass"
HOUSE = ["####",   # 3 wide, padded to 4 (the fourth column shows the border)
         "#c.#",
         "#..#",
         "#M.#"]


def _quarter(rgb, mid, q, varied):
    """8x8 RGBA bytes: a colour, a darker diagonal; when varied, each quarter a little different."""
    r, g, b = rgb
    out = bytearray()
    for y in range(8):
        for x in range(8):
            d = 30 if x == y else 0
            v = (q * 9) if varied else 0
            out += bytes((max(0, r - d - v) % 256, max(0, g - d) % 256, (b + v) % 256, 255))
    return bytes(out)


def _over(quarter):
    """A tree's over plane flattened in: a dark top-left pixel."""
    q = bytearray(quarter)
    q[0:4] = bytes((10, 40, 10, 255))
    return bytes(q)


def _png_rgba(width, height, rgba):
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        raw += rgba[y * width * 4:(y + 1) * width * 4]

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
            + chunk(b"IEND", b""))


def _b64(data):
    return base64.b64encode(data).decode("ascii")


def _cells(rows, table, w, h, elev=None):
    """Cells of a map: elevation 3 (the ground's), else what ``elev`` gives by (x, y)."""
    out = bytearray()
    for y in range(h):
        row = rows[y] if y < len(rows) else ""
        for x in range(w):
            mid, coll = table[row[x]] if x < len(row) else (513, BLOCKED)
            out += struct.pack("<HBB", mid, coll, (elev or {}).get((x, y), 3))
    return _b64(bytes(out))


def tiles_and_pages():
    """(g3tiles by pair, the page images): every metatile's quarters, shared where alike."""
    known, pages = {}, []
    tiles = {}

    def qid(data):
        if data not in known:
            known[data] = len(pages)
            pages.append(data)
        return known[data]

    for pair, mids in PAIRS.items():
        entry = {"id": pair, "mids": [], "quarters": [], "beh": [], "over": [], "behaviors": {}, "land": []}
        for mid, (beh, rgb, varied, over) in sorted(mids.items()):
            qs = []
            for q in range(4):
                data = _quarter(rgb, mid, q, varied)
                if over and q == 0:
                    data = _over(data)
                qs.append(qid(data))
            entry["mids"].append(mid)
            entry["quarters"].append(qs)
            entry["beh"].append(beh)
            entry["over"].append(over)
            entry["behaviors"][str(beh)] = BEHAVIORS[beh]
            if beh in LAND_BEHAVIORS and beh not in entry["land"]:
                entry["land"].append(beh)
        entry["land"].sort()
        tiles[pair] = entry
    side = PAGE_SIDE * 8
    images = {}
    for page in range((len(pages) + PAGE_SIDE * PAGE_SIDE - 1) // (PAGE_SIDE * PAGE_SIDE)):
        rgba = bytearray(side * side * 4)
        for i in range(PAGE_SIDE * PAGE_SIDE):
            n = page * PAGE_SIDE * PAGE_SIDE + i
            if n >= len(pages):
                break
            px, py = (i % PAGE_SIDE) * 8, (i // PAGE_SIDE) * 8
            for r in range(8):
                at = ((py + r) * side + px) * 4
                rgba[at:at + 32] = pages[n][r * 32:(r + 1) * 32]
        pid = f"g3q.{page}"
        images[pid] = {"id": pid, "w": side, "h": side, "png": _b64(_png_rgba(side, side, bytes(rgba)))}
    return tiles, images


def resources(game=GAME, variant=None):
    """The synthetic export; variant "pass" (MG8) gives the town a Wild Routes end (the Hoenn half of a connecting
    route), its clearing baked in, and the routes resource."""
    res = {}
    tiles, images = tiles_and_pages()
    for pair, data in tiles.items():
        res[("g3tiles", pair)] = data
    for pid, data in images.items():
        res[("g3image", pid)] = data
    res[("g3map", "EM_TEST_TOWN")] = {
        "id": "EM_TEST_TOWN", "w": 8, "h": 6, "trueW": 8, "trueH": 6, "pair": "TEST_PAIR_A",
        "cells": _cells(TOWN, _T, 8, 6), "border": {"w": 2, "h": 2, "mids": [5, 5, 5, 5]},
        "connections": [{"dir": "north", "map": "EM_TEST_ROUTE", "offset": 1}],
        "warps": [{"x": 3, "y": 3, "warp": 0, "to": "EM_TEST_HOUSE"}],
        "objects": [{"lid": 1, "gfx": "OBJ_EVENT_GFX_TEST_PERSON", "x": 5, "y": 5, "elev": 3, "kind": "person",
                     "stays": True},
                    {"lid": 2, "gfx": "OBJ_EVENT_GFX_TEST_ITEM_BALL", "x": 6, "y": 1, "elev": 3, "flag": 257,
                     "kind": "item", "stays": True},
                    {"lid": 3, "gfx": "OBJ_EVENT_GFX_TEST_PERSON", "x": 1, "y": 5, "elev": 3, "kind": "person",
                     "stays": False}],
        "mapType": 1, "kind": "town", "music": 300, "weather": 2,
    }
    res[("g3map", "EM_TEST_ROUTE")] = {
        "id": "EM_TEST_ROUTE", "w": 6, "h": 5, "trueW": 6, "trueH": 5, "pair": "TEST_PAIR_A",
        "cells": _cells(ROUTE, _T, 6, 5, ROUTE_ELEV), "border": {"w": 2, "h": 2, "mids": [5, 5, 5, 5]},
        "connections": [{"dir": "south", "map": "EM_TEST_TOWN", "offset": -1}],
        "warps": [], "objects": [], "mapType": 3, "kind": "route", "music": 301, "weather": 0,
    }
    res[("g3map", "EM_TEST_HOUSE")] = {
        "id": "EM_TEST_HOUSE", "w": 4, "h": 4, "trueW": 3, "trueH": 4, "pair": "TEST_PAIR_B",
        "cells": _cells(HOUSE, _H, 4, 4), "border": {"w": 1, "h": 1, "mids": [513]},
        "connections": [], "warps": [{"x": 1, "y": 3, "warp": 0, "to": "EM_TEST_TOWN"}],
        "objects": [{"lid": 1, "gfx": "OBJ_EVENT_GFX_TEST_PERSON", "x": 1, "y": 0, "elev": 3, "kind": "person",
                     "stays": True}],
        "mapType": 8, "kind": "indoor", "music": 302, "weather": 0,
    }
    res[("g3layout", "hoenn")] = {"root": "EM_TEST_TOWN", "unit": "cells", "maps": {
        "EM_TEST_TOWN": {"x": 0, "y": 0, "w": 8, "h": 6}, "EM_TEST_ROUTE": {"x": 1, "y": -5, "w": 6, "h": 5}}}
    frames = 9
    sheet = bytearray()
    for f in range(frames):
        for y in range(32):
            for x in range(16):
                body = 4 <= x < 12 and 4 <= y < 30
                sheet += bytes((40 + f * 10, 80, 160, 255)) if body else bytes((0, 0, 0, 0))
    res[("g3sprite", "OBJ_EVENT_GFX_TEST_PERSON")] = {
        "id": "OBJ_EVENT_GFX_TEST_PERSON", "gid": 7, "w": 16, "h": 32, "frames": frames,
        "png": _b64(_png_rgba(16, 32 * frames, bytes(sheet)))}
    res[("g3encounters", "all")] = {"landWeights": LAND_WEIGHTS, "maps": {"EM_TEST_ROUTE": {
        "land": {"rate": 20, "slots": [{"dex": d, "min": 2, "max": 4} for d in (252, 255, 258, 252, 255, 258,
                                                                                 261, 261, 263, 263, 265, 265)]},
        "water": {"rate": 4, "slots": [{"dex": 270, "min": 5, "max": 10}] * 5}}}}
    if variant == "pass":
        town = res[("g3map", "EM_TEST_TOWN")]
        town["h"] = town["trueH"] = 8
        town["cells"] = _cells(PASS_TOWN, _T, 8, 8)
        town["objects"] = town["objects"] + [{"lid": 4, "gfx": "OBJ_EVENT_GFX_TEST_TRAINER", "x": 6, "y": 5, "elev": 3,
                                              "kind": "trainer", "stays": True}]
        res[("g3layout", "hoenn")]["maps"]["EM_TEST_TOWN"]["h"] = 8
        res[("routes", "all")] = {"version": 2, "routes": [{
            "id": PASS_ROUTE, "name": "TEST PASS", "kind": "connecting", "theme": "forest",
            "town": {"map": "EM_TEST_TOWN", "cleared": [[2, 3]], "triggers": [[4, 7], [5, 7]],
                     "back": {"x": 4, "y": 6, "facing": "up"}},
            "routeMap": {"id": "KB_ROUTE_TEST_PASS_TO", "width": 1, "height": 1, "a": {"x": 0, "y": 0},
                         "arrive": {"x": 1, "y": 0, "facing": "right"}},
            "ends": {"from": {"region": "kanto", "games": ["red", "blue"]}, "to": {"region": "hoenn", "games": [game]}},
            "end": "to",
            "trainers": {"perArea": [1, 2], "rematch": "each_visit", "classes": ["TRAINER_CLASS_TEST"]},
            "areas": {"main": [2, 3], "side": 50}, "levels": {"from": "neighbours", "perArea": 1, "maxRise": 3},
            "dungeon": {"chance": 0, "floors": "by_level"}}]}
    res[("g3names", "all")] = {
        "species": {"SPECIES_TEST_TREECKO": 252, "SPECIES_TEST_TORCHIC": 255, "SPECIES_TEST_MUDKIP": 258,
                    "SPECIES_TEST_POOCHYENA": 261, "SPECIES_TEST_ZIGZAGOON": 263, "SPECIES_TEST_WURMPLE": 265,
                    "SPECIES_TEST_LOTAD": 270},
        "moves": {"MOVE_TEST_TACKLE": 33, "MOVE_TEST_POUND": 1},
        "items": {"ITEM_TEST_POTION": 13, "ITEM_TEST_POKE_BALL": 4}}
    if variant == "pass":
        # the zone's balls draw on the item tiers by name (data/kantobridge/wild_routes/items.json), as Gen 1's bundle
        # does with its POTION and POKE_BALL
        res[("g3names", "all")]["items"].update({"ITEM_POTION": 13, "ITEM_POKE_BALL": 4})
    return res


def index(game=GAME, engine="0.0.0-fake", bridge="fake_gen1-0.1.0", res=None):
    return synth.index(game, engine, bridge, res or resources(game))
