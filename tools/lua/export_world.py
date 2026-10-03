"""A made-up world for gen1-bridge's export tests: engine records shaped like Gen1Recomp's
(src/import/RomExtractor.lua's maps, tilesets, sprites; the field and constants tables FieldDefaults
seeds), with invented content. No ROM data: block layouts, names and pictures are synthetic.

Map and tileset ids are engine symbols (PALLET_TOWN is the layout root; OVERWORLD, GYM, FACILITY and
CAVERN are where the engine keys its cut-tree, spinner and pad tiles), and tile numbers follow the
engine's constants where it has them ($14 water, $32 shore, $3d cut tree, ...). Everything else is
invented. ``world()`` returns a fresh dict each call, so tests may edit it.

Cell layout of PALLET_TOWN (12x10 cells) used by the tests:
    (5,1) door + warp 1        (9,0) cave warp tile + warp 2    (10,2) warp 3 on plain ground
    (2..3,2..3) tall grass     (7,3) sign post                  (0..3,5) ledges (hop down)
    (4,4) cut tree             (8,4) ledge (hop left)           (11,4) ledge (hop right)
    (0..1,6) ledges (hop up)   (6..7,6..7) water                (8,6) shore
"""
from __future__ import annotations

import struct
import zlib

# --- tiles ------------------------------------------------------------------------------------------
GROUND, GRASS, TREE, CUT_TREE, WATER, SHORE = 0x00, 0x52, 0x0F, 0x3D, 0x14, 0x32
DOOR, CAVE, SIGNPOST, FLOWER = 0x1B, 0x5E, 0x18, 0x03
LEDGE_DOWN, LEDGE_LEFT, LEDGE_RIGHT, LEDGE_UP, STAND = 0x36, 0x27, 0x0D, 0x37, 0x2C

FLOOR, WALL, COUNTER, STAIRS, TV = 0x00, 0x01, 0x18, 0x1A, 0x14  # HOUSE ($14 is not water here)
SPIN, PLANT = 0x3C, 0x50                                         # GYM
PAD, HOLE = 0x20, 0x11                                           # FACILITY
CAVE_HOLE, ROCK = 0x22, 0x01                                     # CAVERN

LEDGES = [
    {"facing": "down", "input": "down", "standingTile": GROUND, "ledgeTile": LEDGE_DOWN},
    {"facing": "left", "input": "left", "standingTile": GROUND, "ledgeTile": LEDGE_LEFT},
    {"facing": "right", "input": "right", "standingTile": GROUND, "ledgeTile": LEDGE_RIGHT},
    {"facing": "up", "input": "up", "standingTile": STAND, "ledgeTile": LEDGE_UP},
    {"facing": "down", "input": "down", "standingTile": FLOOR, "ledgeTile": ROCK, "tileset": "CAVERN"},
]


def uniform(tile):
    return [tile] * 16


def quad(tl, tr, bl, br):
    """A block of four cells, each 2x2 tiles of one kind (row-major 4x4 tile ids)."""
    out = []
    for left, right in ((tl, tr), (bl, br)):
        line = [left, left, right, right]
        out += line + line
    return out


OVERWORLD_BLOCKS = [
    uniform(GROUND),                             # 0
    uniform(GRASS),                              # 1
    uniform(TREE),                               # 2
    quad(CUT_TREE, GROUND, GROUND, GROUND),      # 3 a cuttable tree (top-left cell)
    uniform(GROUND),                             # 4 the same block after Cut
    uniform(WATER),                              # 5
    quad(SHORE, WATER, WATER, WATER),            # 6 shore in the top-left cell
    quad(TREE, TREE, TREE, DOOR),                # 7 a door in the bottom-right cell
    quad(TREE, CAVE, TREE, TREE),                # 8 a cave warp tile in the top-right cell
    quad(GROUND, GROUND, LEDGE_DOWN, LEDGE_DOWN),  # 9 ledges in the bottom row
    quad(LEDGE_LEFT, GROUND, GROUND, GROUND),    # 10
    quad(GROUND, LEDGE_RIGHT, GROUND, GROUND),   # 11
    quad(LEDGE_UP, LEDGE_UP, STAND, STAND),      # 12
    quad(FLOWER, GROUND, GROUND, FLOWER),        # 13
    quad(GROUND, GROUND, GROUND, SIGNPOST),      # 14
]
HOUSE_BLOCKS = [
    uniform(FLOOR),                              # 0
    uniform(WALL),                               # 1
    quad(FLOOR, STAIRS, FLOOR, FLOOR),           # 2 stairs in the top-right cell
    quad(FLOOR, FLOOR, COUNTER, COUNTER),        # 3 counters in the bottom row
    quad(WALL, TV, WALL, WALL),                  # 4
]
GYM_BLOCKS = [
    uniform(FLOOR), uniform(WALL),
    quad(SPIN, FLOOR, FLOOR, FLOOR),             # 2 a spinner arrow
    uniform(FLOOR), uniform(FLOOR),
    quad(PLANT, FLOOR, FLOOR, FLOOR),            # 5 a cuttable plant
]
FACILITY_BLOCKS = [
    uniform(FLOOR), uniform(WALL),
    quad(PAD, FLOOR, FLOOR, HOLE),               # 2 a warp pad and a hole
    uniform(WALL),                               # 3 the card-key door, closed
    uniform(FLOOR),                              # 4 the card-key door, open
]
CAVERN_BLOCKS = [
    uniform(FLOOR), uniform(ROCK),
    quad(CAVE_HOLE, FLOOR, FLOOR, FLOOR),        # 2 a hole
    uniform(WATER),                              # 3
    quad(SHORE, WATER, WATER, WATER),            # 4
]
SIMPLE_BLOCKS = [uniform(FLOOR), uniform(WALL)]

ATLAS = "fake_cache/tilesets/{}.png"


def tileset(tid, image, height, blocks, walkable, animation="TILEANIM_NONE", **extra):
    rec = {
        "id": tid, "source": f"TEST:{tid}", "header": [1, 2, 3], "image": ATLAS.format(image),
        "imageWidth": 128, "imageHeight": height, "tilesPerRow": 16, "blocks": blocks,
        "walkable": sorted(walkable), "counterTiles": [], "doorTiles": [], "warpTiles": [],
        "animation": animation,
    }
    rec.update(extra)
    return rec


def map_record(mid, index, ts, rows, warps=(), signs=(), objects=(), connections=None, border=1):
    return {
        "id": mid, "label": mid.title().replace("_", ""), "index": index, "source": f"TEST:{mid}",
        "tileset": ts, "width": len(rows[0]), "height": len(rows), "blocks": [b for row in rows for b in row],
        "borderBlock": border, "connections": connections or {},
        "warps": [dict(zip(("x", "y", "destMap", "destWarp"), w)) for w in warps],
        "signs": [{"x": x, "y": y, "text": "TEXT_TEST_SIGN"} for x, y in signs],
        "objects": list(objects), "sram": {"header": [9, 9, 9]},
    }


def obj(index, name, sprite, x, y, movement="STAY", rng="DOWN", **extra):
    o = {"index": index, "name": name, "sprite": sprite, "x": x, "y": y, "movement": movement,
         "range": rng, "text": f"TEXT_TEST_{index}"}
    o.update(extra)
    return o


def slots(species, base):
    return [{"species": species[i % len(species)], "level": base + i // 3} for i in range(10)]


def png(width, height, seed=0):
    """A minimal greyscale PNG (our own writer): a recognisable 4-shade pattern."""
    shades = (255, 170, 85, 0)
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            raw.append(shades[(x // 2 + y // 3 + seed) % 4])

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    head = struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", head) + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
            + chunk(b"IEND", b""))


def world(version="blue"):
    P = "PALLET_TOWN"
    maps = {
        P: map_record(P, 0, "OVERWORLD", [
            [2, 2, 7, 2, 8, 2],
            [0, 1, 0, 14, 0, 0],
            [9, 9, 3, 0, 10, 11],
            [12, 0, 13, 5, 6, 0],
            [2, 2, 2, 2, 2, 2],
        ], warps=[(5, 1, "REDS_HOUSE_1F", 1), (9, 0, "TEST_CAVE", 1), (10, 2, "TEST_GYM", 1),
                  (2, 8, "TEST_UNDERGROUND_B", 1)],
            signs=[(7, 3)],
            objects=[
                obj(1, "PALLETTOWN_WALKER", "SPRITE_TEST_GIRL", 2, 8, "WALK", "ANY_DIR"),
                obj(2, "PALLETTOWN_BALL", "SPRITE_TEST_BALL", 10, 3, "STAY", "NONE", item="POTION"),
                obj(3, "PALLETTOWN_TRAINER", "SPRITE_TEST_GIRL", 3, 8, "STAY", "RIGHT",
                    trainerClass="OPP_TEST", trainerParty=2),
                obj(4, "PALLETTOWN_STATIC", "SPRITE_TEST_BALL", 11, 8, "STAY", "DOWN",
                    pokemon="TESTMON_A", level=30, hidden=True),
            ],
            connections={"north": {"map": "ROUTE_1", "offset": 0}, "east": {"map": "ROUTE_22", "offset": 1}},
            border=2),
        "ROUTE_1": map_record("ROUTE_1", 12, "OVERWORLD", [[0] * 6 for _ in range(4)],
                              warps=[(2, 2, "TEST_GATE", 1)],
                              connections={"south": {"map": P, "offset": 0}}, border=2),
        "ROUTE_22": map_record("ROUTE_22", 33, "OVERWORLD", [[0] * 4 for _ in range(3)],
                               warps=[(1, 1, "TEST_UNDERGROUND_A", 1)],
                               connections={"west": {"map": P, "offset": -1}}, border=2),
        "REDS_HOUSE_1F": map_record("REDS_HOUSE_1F", 37, "HOUSE", [
            [1, 4, 2, 1],
            [0, 3, 0, 1],
            [0, 0, 0, 1],
            [1, 0, 1, 1],
        ], warps=[(2, 7, "LAST_MAP", 1), (5, 0, "REDS_HOUSE_2F", 1)],
            objects=[obj(1, "REDSHOUSE1F_MOM", "SPRITE_TEST_MOM", 4, 4, "STAY", "LEFT")]),
        "REDS_HOUSE_2F": map_record("REDS_HOUSE_2F", 38, "HOUSE", [
            [1, 2, 1],
            [0, 0, 1],
            [1, 1, 1],
        ], warps=[(3, 0, "REDS_HOUSE_1F", 2)]),
        "TEST_GYM": map_record("TEST_GYM", 45, "GYM", [
            [1, 1, 1],
            [0, 2, 5],
            [1, 0, 1],
        ], warps=[(2, 5, "LAST_MAP", 3)]),
        "TEST_CAVE": map_record("TEST_CAVE", 59, "CAVERN", [
            [1, 2, 1],
            [0, 3, 4],
            [1, 0, 1],
        ], warps=[(1, 5, "LAST_MAP", 2), (4, 4, "TEST_LAB", 1), (2, 0, "TEST_LAB", 3)]),
        # pads and holes count only where a warp is: (2,2) and (3,3) have warps, (4,2) and (5,3) don't
        "TEST_LAB": map_record("TEST_LAB", 50, "FACILITY", [
            [1, 4, 1],
            [0, 2, 2],
        ], warps=[(0, 3, "TEST_CAVE", 2), (2, 2, "TEST_GYM", 1), (3, 3, "TEST_CAVE", 1)]),
        "TEST_GATE": map_record("TEST_GATE", 60, "GATE", [[0, 0], [0, 0], [0, 0]],
                                warps=[(1, 0, "LAST_MAP", 1), (1, 5, "LAST_MAP", 1)]),
        "TEST_UNDERGROUND_A": map_record("TEST_UNDERGROUND_A", 70, "UNDERGROUND", [[0, 0], [0, 0]],
                                         warps=[(0, 0, "LAST_MAP", 1), (2, 2, "TEST_TUNNEL", 1)]),
        "TEST_TUNNEL": map_record("TEST_TUNNEL", 71, "UNDERGROUND", [[0, 0], [0, 0]],
                                  warps=[(0, 0, "TEST_UNDERGROUND_A", 2), (3, 3, "TEST_UNDERGROUND_B", 2)]),
        "TEST_UNDERGROUND_B": map_record("TEST_UNDERGROUND_B", 72, "UNDERGROUND", [[0, 0], [0, 0]],
                                         warps=[(0, 0, "LAST_MAP", 4), (2, 2, "TEST_TUNNEL", 2)]),
        "TEST_COLOSSEUM": map_record("TEST_COLOSSEUM", 80, "HOUSE", [[0, 0], [0, 0]]),
    }
    ow_walk = [GROUND, GRASS, DOOR, CAVE, FLOWER, STAND]
    tilesets = {
        "OVERWORLD": tileset("OVERWORLD", "overworld", 48, OVERWORLD_BLOCKS, ow_walk, "TILEANIM_WATER_FLOWER",
                             grassTile=GRASS, doorTiles=[DOOR], warpTiles=[CAVE]),
        "HOUSE": tileset("HOUSE", "house", 40, HOUSE_BLOCKS, [FLOOR, STAIRS], counterTiles=[COUNTER],
                         warpTiles=[STAIRS]),
        "GATE": tileset("GATE", "house", 40, SIMPLE_BLOCKS, [FLOOR]),
        "GYM": tileset("GYM", "gym", 48, GYM_BLOCKS, [FLOOR, SPIN]),
        "FACILITY": tileset("FACILITY", "facility", 48, FACILITY_BLOCKS, [FLOOR, PAD, HOLE]),
        "CAVERN": tileset("CAVERN", "cavern", 40, CAVERN_BLOCKS, [FLOOR, CAVE_HOLE], "TILEANIM_WATER"),
        "UNDERGROUND": tileset("UNDERGROUND", "underground", 16, SIMPLE_BLOCKS, [FLOOR]),
    }
    sprites = {
        "SPRITE_TEST_GIRL": {"id": "SPRITE_TEST_GIRL", "source": "TEST", "image": "fake_cache/sprites/girl.png",
                             "frames": 6, "walker": True},
        "SPRITE_TEST_MOM": {"id": "SPRITE_TEST_MOM", "source": "TEST", "image": "fake_cache/sprites/mom.png",
                            "frames": 3, "walker": False},
        "SPRITE_TEST_BALL": {"id": "SPRITE_TEST_BALL", "source": "TEST", "image": "fake_cache/sprites/ball.png",
                             "frames": 1, "walker": False},
    }
    palettes = {
        "PALLET": [[248, 248, 248], [168, 216, 104], [96, 160, 40], [24, 24, 24]],
        "ROUTE": [[248, 248, 232], [160, 208, 128], [64, 128, 96], [24, 32, 40]],
        "CAVE": [[240, 232, 216], [200, 160, 120], [128, 96, 64], [16, 16, 16]],
        "MEWMON": {"colors": [{"r": 255, "g": 255, "b": 255}, {"r": 200, "g": 150, "b": 150},
                              {"r": 100, "g": 50, "b": 50}, {"r": 0, "g": 0, "b": 0}]},
    }
    species_a, species_b = "TESTMON_A", "TESTMON_B"
    encounters = {
        P: {"source": "TEST", "grass": {"rate": 25, "slots": slots([species_a, species_b], 2)}},
        "TEST_CAVE": {"source": "TEST", "grass": {"rate": 10, "slots": slots([species_b], 7)},
                      "water": {"rate": 5, "slots": slots([species_a], 15)}},
        "ROUTE_1": {"source": "TEST", "grass": {"rate": 0, "slots": []}},
    }
    pokemon = {
        species_a: {"id": species_a, "index": 101, "dex": 16, "name": "SECRET MON A"},
        species_b: {"id": species_b, "index": 102, "dex": 19, "name": "SECRET MON ♂"},
        "TESTMON_NEW": {"id": "TESTMON_NEW", "index": 103, "dex": 152, "name": "SECRET NEW"},
    }
    moves = {
        "TEST_TACKLE": {"id": "TEST_TACKLE", "index": 33, "name": "SECRET TACKLE"},
        "TEST_GROWL": {"id": "TEST_GROWL", "index": 45, "name": "SECRET GROWL"},
    }
    items = {
        "POTION": {"id": "POTION", "index": 20, "name": "SECRET POTION", "price": 300},
        "POKE_BALL": {"id": "POKE_BALL", "index": 4, "name": "SECRET BALL", "price": 200},
        "TEST_KEY": {"id": "TEST_KEY", "index": 63, "name": "SECRET KEY", "price": 0, "keyItem": True},
        "TM_TEST": {"id": "TM_TEST", "index": 201, "name": "SECRET TM", "price": 1000,
                    "machine": {"kind": "TM", "move": "TEST_TACKLE", "number": 1}},
    }
    field = {
        "ledges": LEDGES,
        "tilePairs": {"land": [{"tileset": "CAVERN", "a": FLOOR, "b": ROCK}], "water": []},
        "cutTreeSwaps": [{"before": 3, "after": 4}, {"before": 5, "after": 0}],
        "waterTilesets": ["OVERWORLD", "GYM", "FACILITY", "CAVERN"],
        "outsideTilesets": ["OVERWORLD", "PLATEAU"],
        "darkMaps": {"entryMap": "TEST_CAVE", "flashBadge": "BOULDERBADGE", "maps": ["TEST_CAVE"], "palOffset": 6},
        "spinners": {"TEST_GYM": [{"x": 2, "y": 2, "moves": [{"dir": "up", "count": 1}]}]},
        "cardKeyDoors": {"closedDoors": {"TEST_LAB": [{"block": 3, "bx": 1, "by": 0, "open": 4,
                                                       "event": "EVENT_TEST_DOOR"}]},
                         "maps": ["TEST_LAB"], "doorTiles": [WALL], "openBlock": 4},
        "gameCornerPoster": {"map": "TEST_GYM", "x": 0, "y": 0, "closedBlock": 1, "openBlock": 0,
                             "event": "EVENT_TEST_POSTER", "poster": {"x": 0, "y": 0}, "posterText": "TEST_POSTER"},
        "hiddenExtras": {"trashCans": {"map": "REDS_HOUSE_2F", "doorBlock": {"bx": 1, "by": 1, "block": 2}}},
        "lastMapRewrites": {
            "TEST_GATE": {"axis": "y", "rules": [{"below": 2, "map": "ROUTE_1"}, {"map": "ROUTE_22"}]},
            "TEST_UNDERGROUND_A": {"rules": [{"map": "ROUTE_1"}]},
            "TEST_UNDERGROUND_B": {"rules": [{"map": P}]},
        },
        "palettes": {"byMap": {P: "PALLET"}, "byTileset": {"CAVERN": "CAVE"},
                     "byPrefix": [{"prefix": "ROUTE_", "palette": "ROUTE"}], "default": "ROUTE"},
        "flyWarps": {P: {"x": 5, "y": 6}},
        "forcedMovement": {"slopeMaps": ["ROUTE_1"], "tiles": {}},
        "warpCarpets": {"edgeMaps": [], "function2Maps": [], "function2Tilesets": ["HOUSE"],
                        "tiles": {"down": [FLOOR], "up": [], "left": [], "right": []},
                        "ssAnneBow": {"map": "TEST_SHIP", "tile": 0x6C}},
        "bookshelves": {"HOUSE": {0x3D: {"kind": "books"}}},
        "fishing": {"OLD_ROD": {"always": {"species": species_b, "level": 5}},
                    "GOOD_ROD": {"pool": [{"species": species_a, "level": 10}, {"species": species_b, "level": 10}]},
                    "SUPER_ROD": {"perMap": "superRod"}},
        "superRod": {P: [{"level": 15, "species": species_b}], "TEST_CAVE": [{"level": 20, "species": species_a}]},
        "overworldFx": {"cutTree": {"path": "fake_cache/fx/cut_tree.png", "width": 16, "height": 16}},
        "emotionBubbles": {"path": "fake_cache/fx/emotes.png", "width": 48, "height": 16,
                           "bubbles": [{"name": "SHOCK", "x": 0, "y": 0, "w": 16, "h": 16}]},
        "townMap": {"locations": {P: {"name": "SECRET TOWN", "x": 1, "y": 2}}},
    }
    constants = {"encounterBuckets": [51, 102, 141, 166, 191, 216, 229, 242, 253, 256], "levelCap": 100}
    images = {
        ATLAS.format("overworld"): (128, 48, png(128, 48, 0)),
        ATLAS.format("house"): (128, 40, png(128, 40, 1)),
        ATLAS.format("gym"): (128, 48, png(128, 48, 2)),
        ATLAS.format("facility"): (128, 48, png(128, 48, 3)),
        ATLAS.format("cavern"): (128, 40, png(128, 40, 0)),
        ATLAS.format("underground"): (128, 16, png(128, 16, 1)),
        "fake_cache/tilesets/flower1.png": (8, 8, png(8, 8, 1)),
        "fake_cache/tilesets/flower2.png": (8, 8, png(8, 8, 2)),
        "fake_cache/tilesets/flower3.png": (8, 8, png(8, 8, 3)),
        "fake_cache/tilesets/spinners.png": (32, 8, png(32, 8, 0)),
        "fake_cache/fx/cut_tree.png": (16, 16, png(16, 16, 2)),
        "fake_cache/fx/emotes.png": (48, 16, png(48, 16, 3)),
        "fake_cache/sprites/girl.png": (16, 96, png(16, 96, 1)),
        "fake_cache/sprites/mom.png": (16, 48, png(16, 48, 2)),
        "fake_cache/sprites/ball.png": (16, 16, png(16, 16, 3)),
    }
    return {
        "version": version,
        "registries": {
            "maps": maps, "tilesets": tilesets, "sprites": sprites, "palettes": palettes,
            "encounters": encounters, "pokemon": pokemon, "moves": moves, "items": items,
            "field": field, "constants": constants,
        },
        "images": images,
        "animations": {
            "flowers": ["fake_cache/tilesets/flower1.png", "fake_cache/tilesets/flower2.png",
                        "fake_cache/tilesets/flower3.png"],
            "spinners": "fake_cache/tilesets/spinners.png",
        },
    }
