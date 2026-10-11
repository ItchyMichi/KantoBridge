"""The synthetic world as export data (docs/PROTOCOL.md "Export"). Entirely made up; no ROM data.

One tileset (TEST) with a procedurally drawn 128x48 atlas, maps built from its blocks (TEST_TOWN,
10x9 blocks; TEST_HILL, 8x6 blocks north of it; TEST_HOUSE and TEST_HOUSE_2F), four sprite sheets,
three flower frames, one palette, a grass encounter table, field tables and names. Per-cell flags are
derived from the blocks the same way the engine does it: a cell's behaviour comes from its bottom-left
8x8 tile.

TEST_HILL (the voxel plan's V2) holds what the shape generator needs to see once: a house with a
tapered two-row roof and a windowed facade, a cliff mound with a rim, a fence line, a sign on a
lightest-shade background and a 2x2-tile round tree with a dark outline. Its south row of trees meets
the town's north row of trees, so nobody walks between the two.
"""
import base64
import hashlib
import struct
import zlib

from kb_protocol.jsonfmt import canonical

# --- tiles -------------------------------------------------------------------------------------------
GROUND, GRASS, TREE, WALL, ROOF, DOOR, WATER, FLOWER, LEDGE, FLOOR, COUNTER, POST, MAT, CUT, SHORE, STAIRS = range(16)
# TEST_HILL's art (the voxel plan's V2)
ROOF_EDGE, ROOF2, FACADE, WINDOW, FENCE, RIM, CLIFF, SIGN_TOP, SIGN_POST, RTREE_NW, RTREE_NE, RTREE_SW, RTREE_SE = \
    range(16, 29)
TILES_PER_ROW, ATLAS_TILES = 16, 96
WALKABLE = [GROUND, GRASS, DOOR, FLOWER, FLOOR, MAT, STAIRS]

# --- blocks (4x4 tile ids, row-major) ----------------------------------------------------------------


def _uniform(tile):
    return [tile] * 16


def _quad(tl, tr, bl, br):
    """A block from its four cells, each cell 2x2 tiles of one kind (row-major 4x4 tile ids)."""
    out = []
    for left, right in ((tl, tr), (bl, br)):
        line = [left, left, right, right]
        out += line + line
    return out


B_GROUND, B_GRASS, B_TREES, B_WALL, B_ROOF, B_DOOR, B_WATER, B_FLOWERS, B_LEDGE, B_FLOOR, B_IWALL, B_COUNTER, \
    B_SIGN, B_MAT, B_CUT, B_CUT_DONE, B_STAIRS = range(17)
BLOCKS = [
    _uniform(GROUND),
    _uniform(GRASS),
    _uniform(TREE),
    _uniform(WALL),
    _uniform(ROOF),
    _quad(WALL, WALL, WALL, DOOR),        # house front, door in the bottom-right cell
    _uniform(WATER),
    _quad(FLOWER, GROUND, GROUND, FLOWER),
    _quad(GROUND, GROUND, LEDGE, LEDGE),  # ledge cells in the bottom row, hop down
    _uniform(FLOOR),
    _uniform(WALL),
    _quad(FLOOR, FLOOR, COUNTER, COUNTER),
    _quad(GROUND, GROUND, GROUND, POST),  # sign post in the bottom-right cell
    _quad(MAT, MAT, WALL, WALL),          # exit mat cells on top, wall below
    _quad(CUT, GROUND, GROUND, GROUND),   # a cuttable tree in the top-left cell
    _uniform(GROUND),                     # the same block after Cut
    _quad(FLOOR, STAIRS, FLOOR, FLOOR),   # stairs in the top-right cell
    # TEST_HILL (append only: earlier block ids never move)
    [ROOF_EDGE] * 4 + [ROOF2] * 4 + [FACADE] * 4 + [FACADE, WINDOW, WINDOW, FACADE],  # 17: roof over the facade
    [FACADE, WINDOW, WINDOW, FACADE] + [FACADE] * 12,                                 # 18: facade, left
    [FACADE, WINDOW, WINDOW, FACADE] + [FACADE] * 4 + [FACADE, FACADE, DOOR, DOOR] * 2,  # 19: facade with the door
    [RIM] * 4 + [CLIFF] * 12,                                                         # 20: the mound's rim
    [CLIFF] * 16,                                                                     # 21: the mound's face
    [RTREE_NW, RTREE_NE, GROUND, GROUND, RTREE_SW, RTREE_SE, GROUND, GROUND] + [GROUND] * 8,  # 22: a round tree
    [FENCE] * 8 + [GROUND] * 8,                                                       # 23: a fence line
    [SIGN_TOP, SIGN_TOP, GROUND, GROUND, SIGN_POST, SIGN_POST, GROUND, GROUND] + [GROUND] * 8,  # 24: a sign
]
B_HROOF, B_HFRONT, B_HDOOR, B_RIM, B_CLIFF, B_RTREE, B_FENCE, B_SIGN2 = range(17, 25)

T = B_TREES
TOWN_BLOCKS = [
    [T, T, T, T, T, T, T, T, T, T],
    [T, B_GROUND, B_ROOF, B_ROOF, B_GROUND, B_GROUND, B_GROUND, B_GROUND, B_GROUND, T],
    [T, B_GROUND, B_DOOR, B_WALL, B_GROUND, B_SIGN, B_GROUND, B_GRASS, B_GRASS, T],
    [T, B_GROUND, B_GROUND, B_GROUND, B_GROUND, B_GROUND, B_GROUND, B_GRASS, B_GRASS, T],
    [T, B_GROUND, B_GROUND, B_GROUND, B_GROUND, B_GROUND, B_GROUND, B_GROUND, B_GROUND, T],
    [T, B_GROUND, B_FLOWERS, B_GROUND, B_GROUND, B_GROUND, B_GROUND, B_GROUND, B_GROUND, T],
    [T, B_LEDGE, B_LEDGE, B_LEDGE, B_GROUND, B_GROUND, B_WATER, B_WATER, B_GROUND, T],
    [T, B_GROUND, B_GROUND, B_GROUND, B_GROUND, B_CUT, B_WATER, B_WATER, B_GROUND, T],
    [T, T, T, T, T, T, T, T, T, T],
]
G = B_GROUND
HILL_BLOCKS = [
    [T, T, T, T, T, T, T, T],
    [T, B_HROOF, B_HROOF, G, B_RIM, B_RIM, G, T],
    [T, B_HFRONT, B_HDOOR, G, B_CLIFF, B_CLIFF, B_RTREE, T],
    [T, G, G, G, G, G, G, T],
    [T, B_FENCE, B_FENCE, B_FENCE, G, B_SIGN2, G, T],
    [T, T, T, T, T, T, T, T],
]
W = B_IWALL
HOUSE_BLOCKS = [
    [W, W, W, W, W],
    [W, B_FLOOR, B_COUNTER, B_STAIRS, W],
    [W, B_FLOOR, B_FLOOR, B_FLOOR, W],
    [W, B_FLOOR, B_FLOOR, B_FLOOR, W],
    [W, W, B_MAT, W, W],
]
HOUSE_2F_BLOCKS = [
    [W, W, W, W],
    [W, B_FLOOR, B_STAIRS, W],
    [W, B_FLOOR, B_FLOOR, W],
    [W, W, W, W],
]

# --- the shape profile (docs/specs/PROFILE_PLAN.md) ----------------------------------------------------
# Made up in the format of Dramaless Shape's data/voxel_heights.lua, over this world's own tiles: the hill's round
# tree pinned round, its sign a signpost (prop_bg: white only), the town's post a post, the cut tree a prop on plain
# ground, the cliff a wall (a class P1 leaves to Tier A), a conditional pin on the roof, and the hill's house as a
# building entry (counted from P1, modelled from P2). No game data.


def _grid(block_rows):
    """The tile grid (north row first) of a rectangle of blocks."""
    rows = []
    for block_row in block_rows:
        for ty in range(4):
            rows.append([BLOCKS[b][ty * 4 + tx] for b in block_row for tx in range(4)])
    return rows


def _lua_list(values):
    return "{ " + ", ".join(str(v) for v in values) + " }"


HILL_HOUSE_TILES = _grid([[B_HROOF, B_HROOF], [B_HFRONT, B_HDOOR]])
PROFILE_TEXT = "\n".join([
    "-- A made-up shape profile for the synthetic world (fake_gen1/synth.py).",
    "return {",
    "  version = 1,",
    "  heights = { wall = 16, prop = 16 },",
    "  tilesets = {",
    "    TEST = {",
    f"      cylinder = {_lua_list([RTREE_NW, RTREE_NE, RTREE_SW, RTREE_SE])},",
    f"      signpost = {_lua_list([SIGN_TOP, SIGN_POST])},",
    f"      post = {_lua_list([POST])},",
    f"      prop = {_lua_list([CUT])},",
    f"      wall = {_lua_list([WALL])},",
    # P3 (docs/specs/PROFILE_PLAN.md §5.6): the mound a cliff, the fence line, the counter, the house's stairs.
    f"      cliff = {_lua_list([RIM, CLIFF])},",
    f"      fence = {_lua_list([FENCE])},",
    f"      counter = {_lua_list([COUNTER])},",
    f"      stair_e = {_lua_list([STAIRS])},",
    f"      prop_ground = {{ [{CUT}] = {GROUND} }},",
    f"      prop_bg = {{ {{ tiles = {_lua_list([SIGN_TOP])}, shades = {{ \"white\" }} }} }},",
    f"      when_above = {{ [{ROOF2}] = {{ {{ above = {_lua_list([ROOF_EDGE])}, class = \"roof\" }} }} }},",
    "    },",
    "  },",
    "  buildings = {",
    "    TEST = {",
    "      {",
    "        id = \"hill_house\",",
    "        tiles = {",
] + [f"          {_lua_list(row)}," for row in HILL_HOUSE_TILES] + [
    "        },",
    "        roofRows = 16, roofBack = 2, roofFront = 4, roofCycle = { 2, 9 }, slab = 2, frontEave = 1,",
    "      },",
    # P3: a desk (the parts path) over the house's counter row, an upright part riding it.
    "      {",
    "        id = \"desk\",",
    f"        tiles = {{ {_lua_list([COUNTER] * 4)}, {_lua_list([COUNTER] * 4)} }},",
    "        roofRows = 0, slab = 0,",
    "        desk = { fascia = { 0, 1 }, base = { 2, 15 } },",
    "        parts = { { x = { 9, 14 }, top = { 0, 1 }, facade = { 2, 7 }, depth = 4, z = 2 } },",
    "      },",
    "    },",
    "  },",
    "}",
    "",
])

# --- maps --------------------------------------------------------------------------------------------
MAPS = {
    "TEST_TOWN": {
        "index": 0, "blocks": TOWN_BLOCKS, "border": B_TREES, "surface": True, "outside": True,
        "warps": [{"x": 5, "y": 5, "to": "TEST_HOUSE", "warp": 1}],
        "signs": [{"x": 11, "y": 5}],
        "objects": [
            {"index": 1, "name": "TESTTOWN_GIRL", "sprite": "SPRITE_TEST_GIRL", "x": 10, "y": 8,
             "movement": "WALK", "range": "ANY_DIR", "hidden": False},
            {"index": 2, "name": "TESTTOWN_BALL", "sprite": "SPRITE_TEST_BALL", "x": 15, "y": 4,
             "movement": "STAY", "range": "NONE", "hidden": False, "item": "POTION"},
            {"index": 3, "name": "TESTTOWN_GUARD", "sprite": "SPRITE_TEST_GUARD", "x": 12, "y": 10,
             "movement": "STAY", "range": "LEFT", "hidden": False, "trainer": {"cls": "OPP_TEST", "party": 1}},
        ],
        "parent": None, "lastMap": None,
        "connections": {"north": {"map": "TEST_HILL", "offset": 0}},
    },
    "TEST_HILL": {
        "index": 12, "blocks": HILL_BLOCKS, "border": B_TREES, "surface": True, "outside": True,
        "warps": [], "signs": [{"x": 10, "y": 8}], "objects": [],
        "parent": None, "lastMap": None,
        "connections": {"south": {"map": "TEST_TOWN", "offset": 0}},
    },
    "TEST_HOUSE": {
        "index": 37, "blocks": HOUSE_BLOCKS, "border": B_IWALL, "surface": False, "outside": False,
        "warps": [{"x": 4, "y": 8, "to": "LAST_MAP", "warp": 1}, {"x": 5, "y": 8, "to": "LAST_MAP", "warp": 1},
                  {"x": 7, "y": 2, "to": "TEST_HOUSE_2F", "warp": 1}],
        "signs": [],
        "objects": [
            {"index": 1, "name": "TESTHOUSE_MOM", "sprite": "SPRITE_TEST_MOM", "x": 6, "y": 4,
             "movement": "STAY", "range": "LEFT", "hidden": False},
        ],
        "parent": {"map": "TEST_TOWN", "warp": 1, "x": 5, "y": 5}, "lastMap": "TEST_TOWN",
    },
    "TEST_HOUSE_2F": {
        "index": 38, "blocks": HOUSE_2F_BLOCKS, "border": B_IWALL, "surface": False, "outside": False,
        "warps": [{"x": 5, "y": 2, "to": "TEST_HOUSE", "warp": 3}],
        "signs": [],
        "objects": [],
        "parent": {"map": "TEST_HOUSE", "warp": 3, "x": 7, "y": 2}, "lastMap": "TEST_TOWN",
    },
}

SPRITES = {"SPRITE_TEST_GIRL": 6, "SPRITE_TEST_GUARD": 6, "SPRITE_TEST_MOM": 3, "SPRITE_TEST_BALL": 1}
SPECIES = {"TESTMON_A": (16, "TESTMON A"), "TESTMON_B": (19, "TESTMON B")}
MOVES = {"TEST_TACKLE": (33, "TEST TACKLE"), "TEST_GROWL": (45, "TEST GROWL")}
ITEMS = {"POTION": (20, "TEST POTION", False), "POKE_BALL": (4, "TEST BALL", False), "TEST_KEY": (63, "TEST KEY", True)}

# Cell flag bits (protocol/export/map.schema.json).
F_WALK, F_WATER, F_SHORE, F_GRASS, F_DOOR, F_WARPTILE, F_COUNTER, F_CUT = 1, 2, 4, 8, 16, 32, 64, 128
F_PAD, F_HOLE, F_SPINNER, F_WARP, F_SIGN, F_LEDGE = 256, 512, 1024, 2048, 4096, 8192
LEDGE_DIRS = {"down": 0, "up": 1, "left": 2, "right": 3}
LEDGES = [{"facing": "down", "input": "down", "standingTile": GROUND, "ledgeTile": LEDGE}]


def cell_tile(blocks, cx, cy):
    """The collision tile of a cell: its bottom-left 8x8 tile (engine Map:cellTile)."""
    tx, ty = cx * 2, cy * 2 + 1
    block = blocks[ty // 4][tx // 4]
    return BLOCKS[block][(ty % 4) * 4 + (tx % 4)]


def cell_flags(name):
    m = MAPS[name]
    blocks = m["blocks"]
    height, width = len(blocks) * 2, len(blocks[0]) * 2
    warps = {(w["x"], w["y"]) for w in m["warps"]}
    signs = {(s["x"], s["y"]) for s in m["signs"]}
    rows = []
    for cy in range(height):
        row = []
        for cx in range(width):
            t = cell_tile(blocks, cx, cy)
            f = 0
            f |= F_WALK if t in WALKABLE else 0
            f |= F_WATER if t in (WATER, SHORE) else 0
            f |= F_SHORE if t == SHORE else 0
            f |= F_GRASS if t == GRASS else 0
            f |= F_DOOR if t == DOOR else 0
            f |= F_WARPTILE if t == STAIRS else 0  # exit mats warp by stepping off the edge, not on arrival
            f |= F_COUNTER if t == COUNTER else 0
            f |= F_CUT if t == CUT else 0
            f |= F_WARP if (cx, cy) in warps else 0
            f |= F_SIGN if (cx, cy) in signs else 0
            for ledge in LEDGES:
                above = cell_tile(blocks, cx, cy - 1) if cy > 0 else None
                if t == ledge["ledgeTile"] and above == ledge["standingTile"]:
                    f |= F_LEDGE | (LEDGE_DIRS[ledge["facing"]] << 14)
            row.append(f)
        rows.append(row)
    return rows


# --- images ------------------------------------------------------------------------------------------
SHADES = (255, 170, 85, 0)


def _png(width, height, pixels, alpha=False):
    """A minimal PNG writer: 8-bit grey (or grey+alpha), one filter byte per row."""
    channels = 2 if alpha else 1
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            shade = pixels[y][x]
            raw.append(SHADES[shade])
            if alpha:
                raw.append(0 if shade == 0 else 255)

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 4 if alpha else 0, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
            + chunk(b"IEND", b""))


def _tile_pattern(tile, x, y):
    """Shade (0..3) of one pixel of a synthetic tile: simple, recognisable patterns."""
    if tile == GRASS:
        return 2 if (x + y) % 3 == 0 else 1
    if tile == TREE:
        return 3 if (x - 3) ** 2 + (y - 3) ** 2 < 10 else 2
    if tile in (WALL, COUNTER, POST):
        return 3 if y in (0, 7) or x in (0, 7) else 2
    if tile == ROOF:
        return 3 if (x + y) % 4 == 0 else 2
    if tile == DOOR:
        return 3 if x in (1, 6) or y == 1 else 2
    if tile in (WATER, SHORE):
        return 1 if (x + 2 * y) % 5 else 2
    if tile == FLOWER:
        return 3 if (x, y) in ((3, 3), (4, 4), (3, 4), (4, 3)) else 0
    if tile == LEDGE:
        return 3 if y >= 6 else 0
    if tile == FLOOR:
        return 1 if (x // 4 + y // 4) % 2 else 0
    if tile in (MAT, STAIRS):
        return 3 if y % 2 else 1
    if tile == CUT:
        return 3 if abs(x - 3.5) + abs(y - 3.5) < 3 else 1
    if tile == ROOF_EDGE:
        # the roof's tapered top: the lightest shade above its dark eave line
        return 0 if y == 0 or (y == 1 and x in (0, 7)) else (3 if y == 1 or (x + y) % 3 == 0 else 2)
    if tile == ROOF2:
        return 3 if (x + 2 * y) % 4 == 0 else 2
    if tile == FACADE:
        return 3 if y == 7 else 1
    if tile == WINDOW:
        if x in (0, 7) or y in (0, 7):
            return 1
        return 3 if x in (1, 6) or y in (1, 6) else 0  # a pane sealed by its dark frame
    if tile == FENCE:
        return 3 if x in (3, 4) or y in (2, 5) else 0
    if tile == RIM:
        return 3 if y < 2 else 2
    if tile == CLIFF:
        return 3 if x % 4 == 0 else 2
    if tile == SIGN_TOP:
        if not (1 <= x <= 6 and y >= 1):
            return 0
        return 3 if x in (1, 6) or y == 1 else 1
    if tile == SIGN_POST:
        if y < 4:
            return 3 if x in (1, 6) or y == 3 else (1 if 1 <= x <= 6 else 0)
        return 3 if x in (3, 4) else 0
    if tile in (RTREE_NW, RTREE_NE, RTREE_SW, RTREE_SE):
        px = x + (8 if tile in (RTREE_NE, RTREE_SE) else 0)
        py = y + (8 if tile in (RTREE_SW, RTREE_SE) else 0)
        r2 = (px - 7.5) ** 2 + (py - 7.5) ** 2
        return 2 if r2 < 36 else (3 if r2 < 56 else 0)
    return 0


def atlas_png():
    width, height = TILES_PER_ROW * 8, (ATLAS_TILES // TILES_PER_ROW) * 8
    pixels = [[0] * width for _ in range(height)]
    for tile in range(ATLAS_TILES):
        ox, oy = (tile % TILES_PER_ROW) * 8, (tile // TILES_PER_ROW) * 8
        for y in range(8):
            for x in range(8):
                pixels[oy + y][ox + x] = _tile_pattern(tile, x, y)
    return width, height, _png(width, height, pixels)


def flower_png(frame):
    """One 8x8 flower frame: a dot that grows over the lightest ground."""
    size = (1, 2, 3)[frame - 1]
    pixels = [[3 if abs(x - 3.5) < size and abs(y - 3.5) < size else 0 for x in range(8)] for y in range(8)]
    return 8, 8, _png(8, 8, pixels)


def sprite_png(frames):
    width, height = 16, 16 * frames
    pixels = [[0] * width for _ in range(height)]
    for frame in range(frames):
        for y in range(16):
            for x in range(16):
                body = 4 <= x <= 11 and 2 <= y <= 14
                pixels[frame * 16 + y][x] = 3 if body and (y < 6 or x in (4, 11)) else (2 if body else 0)
    return width, height, _png(width, height, pixels, alpha=True)


def _b64(data):
    return base64.b64encode(data).decode("ascii")


# --- resources ---------------------------------------------------------------------------------------

def resources(game="red"):
    """{(kind, id): data} for every export resource of the synthetic world."""
    out = {}
    for name, m in MAPS.items():
        blocks = m["blocks"]
        out[("map", name)] = {
            "id": name, "index": m["index"], "tileset": "TEST",
            "width": len(blocks[0]), "height": len(blocks),
            "blocks": [b for row in blocks for b in row],
            "border": m["border"], "surface": m["surface"], "outside": m["outside"],
            "connections": m.get("connections", {}), "warps": m["warps"], "signs": m["signs"],
            "objects": m["objects"], "cells": cell_flags(name), "parent": m["parent"], "lastMap": m["lastMap"],
            "dark": False, "palette": "TEST_PALETTE",
            # the TEST tileset isn't the engine's OVERWORLD one: the border drawn is the map's own
            "borderDrawn": m["border"],
        }
    out[("tileset", "TEST")] = {
        "id": "TEST", "image": "tilesets.test", "tilesPerRow": TILES_PER_ROW, "tiles": ATLAS_TILES,
        "blocks": BLOCKS, "animation": "TILEANIM_WATER_FLOWER",
        "walkable": sorted(WALKABLE), "counter": [COUNTER], "grass": GRASS, "door": [DOOR],
        "warp": [STAIRS], "water": [WATER, SHORE], "shore": [SHORE], "cutTree": [CUT], "spinner": [],
        "pads": [],
        "animated": [
            {"tiles": [WATER], "kind": "hshift", "period": 20, "offsets": [1, 2, 3, 2, 1, 0, 7, 0]},
            {"tiles": [FLOWER], "kind": "frames", "period": 20, "sequence": [1, 2, 3, 1, 1, 2, 3, 1],
             "images": ["tilesets.flower1", "tilesets.flower2", "tilesets.flower3"]},
        ],
    }
    w, h, png = atlas_png()
    out[("image", "tilesets.test")] = {"id": "tilesets.test", "w": w, "h": h, "png": _b64(png)}
    for frame in (1, 2, 3):
        w, h, png = flower_png(frame)
        out[("image", f"tilesets.flower{frame}")] = {"id": f"tilesets.flower{frame}", "w": w, "h": h, "png": _b64(png)}
    for sprite, frames in SPRITES.items():
        w, h, png = sprite_png(frames)
        out[("sprite", sprite)] = {"id": sprite, "w": w, "h": h, "frames": frames, "walker": frames >= 6,
                                   "png": _b64(png)}
    out[("palettes", "sgb")] = {"mode": "sgb", "palettes": {
        "TEST_PALETTE": [[248, 248, 232], [160, 208, 128], [64, 128, 96], [24, 32, 40]]}}
    town_grass = {"rate": 25, "slots": [
        {"species": "TESTMON_A" if i % 2 == 0 else "TESTMON_B",
         "dex": SPECIES["TESTMON_A" if i % 2 == 0 else "TESTMON_B"][0], "level": 2 + i // 3}
        for i in range(10)]}
    out[("encounters", "all")] = {
        "buckets": [51, 102, 141, 166, 191, 216, 229, 242, 253, 256],
        "maps": {"TEST_TOWN": {"grass": town_grass}},
        "rods": {"old": [{"species": "TESTMON_B", "dex": 19, "level": 5}], "good": [], "super": {}},
    }
    out[("field", "all")] = {"tables": {
        "ledges": LEDGES,
        "cutTreeSwaps": [{"before": B_CUT, "after": B_CUT_DONE}],
        "darkMaps": {"maps": []},
    }, "display": {"voidFill": "trees", "voidFillDefault": True}}
    out[("names", "all")] = {
        "species": {k: {"dex": v[0], "name": v[1]} for k, v in SPECIES.items()},
        "moves": {k: {"num": v[0], "name": v[1]} for k, v in MOVES.items()},
        "items": {k: {"num": v[0], "name": v[1], "key": v[2]} for k, v in ITEMS.items()},
    }
    out[("layout", "kanto")] = {"root": "TEST_TOWN", "maps": {
        "TEST_TOWN": {"x": 0, "y": 0, "w": len(TOWN_BLOCKS[0]), "h": len(TOWN_BLOCKS)},
        "TEST_HILL": {"x": 0, "y": -len(HILL_BLOCKS), "w": len(HILL_BLOCKS[0]), "h": len(HILL_BLOCKS)}}}
    out[("swaps", "all")] = {"sites": [
        {"map": "TEST_TOWN", "bx": 5, "by": 7, "kind": "cut", "before": B_CUT, "after": B_CUT_DONE}]}
    out[("profile", "dramaless")] = {"mod": "DRAMALESS_SHAPE", "version": "0.0.0-synthetic", "file": "data/voxel_heights.lua",
                                     "enabled": False, "text": PROFILE_TEXT}
    # Wild Routes (WR2): gen1-bridge always exports the zone list; the synthetic world has none yet
    out[("routes", "all")] = {"version": 1, "routes": []}
    return out


def encode(data):
    """The canonical bytes of one resource: what is hashed, sized and stored in a bundle."""
    return canonical(data).encode("utf-8")


def index(game="red", engine="0.0.0-fake", bridge="fake_gen1-0.1.0", res=None):
    res = res or resources(game)
    entries = []
    for (kind, rid), data in sorted(res.items()):
        raw = encode(data)
        entries.append({"kind": kind, "id": rid, "hash": hashlib.sha256(raw).hexdigest(), "size": len(raw)})
    return {"game": game, "engine": engine, "bridge": bridge, "resources": entries}
