"""A synthetic Gen 2 export (MG9, docs/specs/GEN2_BRIDGE_PLAN.md §6, §10): the g2* resources of a tiny made-up Johto and
Kanto, so the builder's JUnit tests and GameTests can build a Gen 2 region without game data. Everything is made up:
maps G2_TEST_*, art variants TILESET_TEST_*_1, blocks, colours, sprites and species. The shapes and the values follow
the real export (gen1-bridge/lib/engine/gen2/export.lua) and the engine's collision bytes and their meanings (Gen1Recomp
src/world/gen2/Permissions.lua: 0x00 floor, 0x07 wall, 0x12 cut tree, 0x18 tall grass, 0x23 ice, 0x24 whirlpool, 0x29
water, 0x70 a mat pressed down, 0x71 door, 0x72 ladder, 0x90 counter, 0xA3 a ledge hopped down, 0xB2 a side wall that
may not be left upward). The export calls 0x72 a ladder, apart from a staircase (HEIGHTS_ALL_GAMES_PLAN D2).

    resources()            {(kind, id): data}; variant "pass" (MG9 G2H): the town with a Wild Routes end
    index(...)             synth.index's shape, game "crystal"

The world: Johto's town (5x4 blocks: trees along the top, a house with its door, a sign, a fence that may not be
crossed from the north, a Cut tree, water with a whirlpool, ledges) with a route north of it (grass, a gate a script opens, a cave's mouth); the house (a
counter, the mat out), the dark cave (floor, ice, rock, the ladder out, a ladder down to its B1F: the 3D area's shaft,
HEIGHTS_ALL_GAMES_PLAN W3); and Kanto's town, joined to Johto by no connection. The water animates (two frames); the sites are the Cut tree, the whirlpool and the gate.
"""
import base64
import struct
import zlib

from . import synth

GAME = "crystal"
PAGE_SIDE = 32

# Meanings of the collision bytes used here (Permissions.lua's predicates; only what holds)
MEANINGS = {
    0x00: {"perm": "land"},
    0x07: {"perm": "wall"},
    0x12: {"perm": "wall", "cutTree": True, "cuttable": True},
    0x18: {"perm": "land", "grass": True, "encounter": True, "cuttable": True},
    0x23: {"perm": "land", "ice": True},
    0x24: {"perm": "water", "whirlpool": True},
    0x29: {"perm": "water", "encounter": True},
    0x70: {"perm": "land", "warp": "edge", "mat": "down"},
    0x71: {"perm": "land", "warp": "door", "facesDown": True},
    0x72: {"perm": "land", "warp": "ladder"},
    0x90: {"perm": "wall", "counter": True},
    0xA3: {"perm": "land", "ledge": ["down"]},
    0xB2: {"perm": "land", "sides": ["up"], "neighbor": {"down": "down"}},
    0xFF: {"perm": "wall"},
}

# The variants: block id -> (its colour, whether its tiles differ, its four collision bytes TL TR BL BR)
VARIANTS = {
    "TILESET_TEST_1": ("TILESET_TEST", "ROOF_TEST", {
        0: ((20, 20, 20), False, (0x07,) * 4),
        1: ((150, 200, 120), False, (0x00,) * 4),       # ground
        2: ((40, 150, 40), True, (0x18,) * 4),          # tall grass
        3: ((60, 100, 230), True, (0x29,) * 4),         # water (animated)
        4: ((170, 120, 90), True, (0x07,) * 4),         # a house wall
        5: ((120, 70, 40), True, (0x07, 0x07, 0x71, 0x07)),  # its door (bottom left)
        6: ((30, 110, 50), True, (0x12,) * 4),          # a Cut tree
        7: ((20, 90, 30), False, (0x07,) * 4),          # trees (the border)
        8: ((140, 180, 100), True, (0x00, 0x00, 0xA3, 0xA3)),  # ledges along the bottom
        9: ((200, 180, 120), True, (0x07, 0x00, 0x00, 0x00)),  # a sign (top left)
        10: ((160, 150, 140), True, (0xB2, 0xB2, 0x00, 0x00)),  # a fence: its top row may not be left upward
        11: ((50, 80, 200), True, (0x24,) * 4),         # a whirlpool
        12: ((130, 170, 100), True, (0x00,) * 4),       # the Cut tree's stump
        13: ((90, 90, 90), True, (0x07,) * 4),          # a gate, shut
    }),
    "TILESET_TEST_HOUSE_1": ("TILESET_TEST_HOUSE", None, {
        0: ((10, 10, 10), False, (0x07,) * 4),
        1: ((220, 200, 160), False, (0x00,) * 4),       # floor
        2: ((110, 100, 120), False, (0x07,) * 4),       # wall
        3: ((170, 120, 70), True, (0x90,) * 4),         # a counter
        4: ((200, 70, 70), True, (0x00, 0x00, 0x70, 0x70)),  # the mat out (bottom row)
    }),
    "TILESET_TEST_CAVE_1": ("TILESET_TEST_CAVE", None, {
        0: ((5, 5, 5), False, (0x07,) * 4),
        1: ((90, 80, 70), False, (0x00,) * 4),          # floor
        2: ((60, 50, 45), True, (0x07,) * 4),           # rock
        3: ((180, 220, 240), True, (0x23,) * 4),        # ice
        4: ((120, 90, 60), True, (0x72, 0x00, 0x00, 0x00)),  # the ladder out (top left)
        5: ((100, 80, 55), True, (0x00, 0x72, 0x00, 0x00)),  # a ladder between the floors (top right)
    }),
}

# Item 8's shape: animated blocks by variant -> frames' ticks (60 Hz) each
ANIMATED = {"TILESET_TEST_1": {3: 12}}

# The maps in blocks: rows of block ids
TOWN = [[7, 7, 7, 7, 7],
        [1, 4, 5, 9, 1],
        [10, 1, 1, 6, 3],
        [8, 8, 1, 11, 3]]
ROUTE = [[2, 2, 2],
         [2, 13, 2],
         [1, 5, 1]]
HOUSE = [[2, 2, 2],
         [1, 3, 1],
         [1, 4, 1]]
CAVE = [[1, 3, 2, 5],
        [1, 4, 1, 1]]
CAVE_B1F = [[1, 5],
            [1, 1]]
KANTO = [[1, 1],
         [1, 2]]

GRASS_CHANCES = [30, 60, 80, 90, 95, 99, 100]

# MG9 G2H: the pass variant's town, the same with two rows of trees below it and the clearing the adapter opens baked
# in (lib/engine/gen2/routes.lua: its exit block (2, 3), facing down, two blocks deep: a copy of the exit's ground)
PASS_TOWN = TOWN + [[7, 7, 1, 7, 7],
                    [7, 7, 1, 7, 7]]
PASS_ROUTE = "test_pass"


def _tile(rgb, block, t, varied):
    """8x8 RGBA bytes: a colour and a darker diagonal; when varied, each of the block's 16 tiles a little different."""
    r, g, b = rgb
    out = bytearray()
    for y in range(8):
        for x in range(8):
            d = 30 if x == y else 0
            v = (t * 5) if varied else 0
            out += bytes((max(0, r - d - v) % 256, max(0, g - d) % 256, (b + v) % 256, 255))
    return bytes(out)


def _lighter(tile):
    q = bytearray(tile)
    for i in range(0, len(q), 4):
        q[i:i + 3] = bytes(min(255, c + 40) for c in q[i:i + 3])
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


def tiles_and_pages(anims=None):
    """(g2tiles by variant, the page images): every block's 16 tiles, shared where alike; an animated block's tiles are
    tiles of their own (their page holds the first frame), and ``anims`` gets each one's g2anims entry."""
    known, pages = {}, []
    animated = {}
    tiles = {}

    def tid(data):
        if data not in known:
            known[data] = len(pages)
            pages.append(data)
        return known[data]

    def animated_tid(first, second, ticks):
        frames = [tid(first), tid(second)]
        key = (tuple(frames), ticks)
        if key not in animated:
            animated[key] = len(pages)
            pages.append(first)
            if anims is not None:
                anims.append({"q": animated[key], "f": frames, "s": [[0, ticks], [1, ticks]]})
        return animated[key]

    for variant, (tileset, roof, blocks) in VARIANTS.items():
        rows, colls, used = [], [], set()
        for block in range(max(blocks) + 1):
            rgb, varied, coll = blocks[block]
            ticks = ANIMATED.get(variant, {}).get(block)
            ids = []
            for t in range(16):
                data = _tile(rgb, block, t, varied)
                ids.append(animated_tid(data, _lighter(data), ticks) if ticks else tid(data))
            rows.append(ids)
            colls.append(list(coll))
            used.update(coll)
        entry = {"id": variant, "tileset": tileset, "blocks": rows, "coll": colls,
                 "meanings": {str(c): MEANINGS[c] for c in sorted(used)}}
        if roof:
            entry["roof"] = roof
        tiles[variant] = entry
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
        pid = f"g2q.{page}"
        images[pid] = {"id": pid, "w": side, "h": side, "png": _b64(_png_rgba(side, side, bytes(rgba)))}
    return tiles, images


def _blocks(rows):
    return _b64(bytes(b for row in rows for b in row))


def _map(mid, rows, variant, tileset, env, area, **extra):
    data = {"id": mid, "w": len(rows[0]), "h": len(rows), "blocks": _blocks(rows), "border": 7 if env != "INDOOR" else 2,
            "variant": variant, "tileset": tileset, "env": env, "palette": "PALETTE_AUTO", "daytime": "DAY",
            "group": 3, "num": 1, "area": area, "connections": [], "warps": [], "coords": [], "signs": [], "objects": []}
    data.update(extra)
    return data


def _sheet(frames, colour, body):
    out = bytearray()
    for f in range(frames):
        for y in range(16):
            for x in range(16):
                out += bytes((colour[0] + f * 10, colour[1], colour[2], 255)) if body(x, y) else bytes((0, 0, 0, 0))
    return _b64(_png_rgba(16, 16 * frames, bytes(out)))


def resources(game=GAME, variant=None):
    """The synthetic export; variant "pass" (MG9 G2H) gives the town a Wild Routes end (the Johto half of a connecting
    route), its clearing baked in, a trainer of the zone's class, and the routes resource."""
    res = {}
    anims = []
    tiles, images = tiles_and_pages(anims)
    res[("g2anims", "all")] = {"id": "all", "anims": anims}
    for vid, data in tiles.items():
        res[("g2tiles", vid)] = data
    for pid, data in images.items():
        res[("g2image", pid)] = data
    out1 = ("TILESET_TEST_1", "TILESET_TEST")
    res[("g2map", "G2_TEST_TOWN")] = _map(
        "G2_TEST_TOWN", TOWN, *out1, "TOWN", "johto", fishGroup="FISHGROUP_TEST",
        connections=[{"dir": "north", "map": "G2_TEST_ROUTE", "offset": 1}],
        warps=[{"x": 4, "y": 3, "warp": 0, "to": "G2_TEST_HOUSE"}],
        coords=[{"x": 4, "y": 4, "scene": 0}], signs=[{"x": 6, "y": 2, "kind": 0}],
        objects=[{"lid": 1, "sprite": "SPRITE_TEST_LASS", "x": 1, "y": 4, "movement": 6, "kind": "person", "stays": True},
                 {"lid": 2, "sprite": "SPRITE_TEST_BALL", "palette": 0, "x": 5, "y": 4, "movement": 1, "kind": "item",
                  "flag": 40, "stays": True},
                 {"lid": 3, "sprite": "SPRITE_TEST_LASS", "x": 2, "y": 5, "movement": 2, "kind": "trainer", "stays": False},
                 {"lid": 4, "sprite": "SPRITE_TEST_ROCK", "x": 5, "y": 5, "movement": 0x18, "kind": "rock", "flag": 41,
                  "stays": True},
                 {"lid": 5, "var": 0xF3, "x": 0, "y": 5, "movement": 0x21, "kind": "person", "stays": True, "big": "asym"}])
    res[("g2map", "G2_TEST_ROUTE")] = _map(
        "G2_TEST_ROUTE", ROUTE, *out1, "ROUTE", "johto",
        connections=[{"dir": "south", "map": "G2_TEST_TOWN", "offset": -1}],
        warps=[{"x": 2, "y": 5, "warp": 0, "to": "G2_TEST_CAVE"}],
        objects=[{"lid": 1, "sprite": "SPRITE_TEST_BOULDER", "x": 4, "y": 2, "movement": 0x19, "kind": "boulder",
                  "stays": True}])
    res[("g2map", "G2_TEST_HOUSE")] = _map(
        "G2_TEST_HOUSE", HOUSE, "TILESET_TEST_HOUSE_1", "TILESET_TEST_HOUSE", "INDOOR", "johto",
        warps=[{"x": 2, "y": 5, "warp": 0, "to": "G2_TEST_TOWN"}, {"x": 3, "y": 5, "warp": 0, "to": "G2_TEST_TOWN"}],
        objects=[{"lid": 1, "sprite": "SPRITE_TEST_LASS", "x": 1, "y": 2, "movement": 7, "kind": "person", "stays": True,
                  "hours": [-1, 2]}])
    res[("g2map", "G2_TEST_CAVE")] = _map(
        "G2_TEST_CAVE", CAVE, "TILESET_TEST_CAVE_1", "TILESET_TEST_CAVE", "CAVE", "johto", palette="PALETTE_DARK",
        daytime="NITE", dark=True, warps=[{"x": 2, "y": 2, "warp": 0, "to": "G2_TEST_ROUTE"},
                                          {"x": 7, "y": 0, "warp": 0, "to": "G2_TEST_CAVE_B1F"}])
    res[("g2map", "G2_TEST_CAVE_B1F")] = _map(
        "G2_TEST_CAVE_B1F", CAVE_B1F, "TILESET_TEST_CAVE_1", "TILESET_TEST_CAVE", "CAVE", "johto", palette="PALETTE_DARK",
        daytime="NITE", dark=True, warps=[{"x": 3, "y": 0, "warp": 1, "to": "G2_TEST_CAVE"}])
    res[("g2map", "G2_TEST_KANTO_TOWN")] = _map("G2_TEST_KANTO_TOWN", KANTO, *out1, "TOWN", "kanto", group=1)
    res[("g2layout", "johto")] = {"root": "G2_TEST_TOWN", "unit": "blocks", "maps": {
        "G2_TEST_TOWN": {"x": 0, "y": 0, "w": 5, "h": 4}, "G2_TEST_ROUTE": {"x": 1, "y": -3, "w": 3, "h": 3}},
        "start": {"map": "G2_TEST_HOUSE", "x": 1, "y": 4}}
    res[("g2layout", "kanto")] = {"root": "G2_TEST_KANTO_TOWN", "unit": "blocks", "maps": {
        "G2_TEST_KANTO_TOWN": {"x": 0, "y": 0, "w": 2, "h": 2}}}
    res[("swaps", "all")] = {"sites": [
        {"map": "G2_TEST_ROUTE", "bx": 1, "by": 1, "kind": "script", "before": 13, "after": 1},
        {"map": "G2_TEST_TOWN", "bx": 3, "by": 2, "kind": "cut", "before": 6, "after": 12},
        {"map": "G2_TEST_TOWN", "bx": 3, "by": 3, "kind": "whirlpool", "before": 11, "after": 3}]}

    def walker(x, y):
        return 4 <= x < 12 and 2 <= y < 16

    res[("g2sprite", "SPRITE_TEST_LASS")] = {"id": "SPRITE_TEST_LASS", "sprite": "SPRITE_TEST_LASS", "palette": 0, "w": 16,
                                             "h": 96, "frames": 6, "walker": True, "type": "WALKING_SPRITE",
                                             "png": _sheet(6, (200, 80, 120), walker)}

    def ball(x, y):
        return (x - 7.5) ** 2 + (y - 7.5) ** 2 <= 36

    for sid, palette, colour in (("SPRITE_TEST_BALL", 1, (60, 60, 220)), ("SPRITE_TEST_BALL.p0", 0, (220, 60, 60))):
        res[("g2sprite", sid)] = {"id": sid, "sprite": "SPRITE_TEST_BALL", "palette": palette, "w": 16, "h": 16,
                                  "frames": 1, "type": "STILL_SPRITE", "png": _sheet(1, colour, ball)}
    for sid, colour in (("SPRITE_TEST_BOULDER", (150, 120, 90)), ("SPRITE_TEST_ROCK", (120, 110, 100))):
        res[("g2sprite", sid)] = {"id": sid, "sprite": sid, "palette": 3, "w": 16, "h": 16, "frames": 1,
                                  "type": "STILL_SPRITE", "png": _sheet(1, colour, ball)}

    def slots(dexes, level):
        return [{"dex": d, "level": level} for d in dexes]

    res[("g2encounters", "all")] = {
        "grass": {
            "G2_TEST_ROUTE": {"rates": {"MORN": 10, "DAY": 20, "NITE": 30},
                              "slots": {"MORN": slots((16, 16, 19, 19, 161, 161, 163), 3),
                                        "DAY": slots((16, 19, 19, 161, 161, 163, 163), 4),
                                        "NITE": slots((163, 163, 19, 19, 161, 161, 16), 5)}},
            "G2_TEST_CAVE": {"rates": {"MORN": 10, "DAY": 10, "NITE": 10},
                             "slots": {d: slots((41, 41, 74, 74, 41, 74, 95), 6) for d in ("MORN", "DAY", "NITE")}}},
        "water": {"G2_TEST_TOWN": {"rate": 5, "slots": slots((60, 60, 129), 10)}},
        "swarmGrass": {}, "swarmWater": {},
        "fish": {"FISHGROUP_TEST": {"chance": 128, "old": [{"dex": 129, "level": 5, "chance": 255}], "good": [],
                                    "super": []}},
        "trees": {}, "rocks": {}, "treeSets": {}, "contest": [],
        "grassChances": GRASS_CHANCES, "waterChances": [60, 90, 100]}
    res[("g2names", "all")] = {
        "species": {"TEST_PIDGEY": 16, "TEST_RATTATA": 19, "TEST_ZUBAT": 41, "TEST_POLIWAG": 60, "TEST_GEODUDE": 74,
                    "TEST_ONIX": 95, "TEST_MAGIKARP": 129, "TEST_SENTRET": 161, "TEST_HOOTHOOT": 163},
        "moves": {"TEST_TACKLE": 33, "TEST_POUND": 1},
        "items": {"TEST_POTION": 18, "TEST_POKE_BALL": 5}}
    if variant == "pass":
        town = res[("g2map", "G2_TEST_TOWN")]
        town["h"] = len(PASS_TOWN)
        town["blocks"] = _blocks(PASS_TOWN)
        town["objects"] = town["objects"] + [{"lid": 6, "sprite": "SPRITE_TEST_YOUNGSTER", "x": 8, "y": 2, "movement": 6,
                                              "kind": "trainer", "stays": True}]
        res[("g2layout", "johto")]["maps"]["G2_TEST_TOWN"]["h"] = len(PASS_TOWN)
        res[("g2sprite", "SPRITE_TEST_YOUNGSTER")] = {
            "id": "SPRITE_TEST_YOUNGSTER", "sprite": "SPRITE_TEST_YOUNGSTER", "palette": 0, "w": 16, "h": 96,
            "frames": 6, "walker": True, "type": "WALKING_SPRITE", "png": _sheet(6, (80, 120, 200), walker)}
        res[("routes", "all")] = {"version": 2, "routes": [{
            "id": PASS_ROUTE, "name": "TEST PASS", "kind": "connecting", "theme": "forest",
            "town": {"map": "G2_TEST_TOWN", "cleared": [[2, 4], [2, 5]], "triggers": [[4, 11], [5, 11]],
                     "back": {"x": 4, "y": 10, "facing": "up"}},
            "routeMap": {"id": "KB_ROUTE_TEST_PASS_TO", "width": 1, "height": 1, "a": {"x": 0, "y": 0},
                         "arrive": {"x": 1, "y": 0, "facing": "right"}},
            "ends": {"from": {"region": "kanto", "games": ["red", "blue"]}, "to": {"region": "johto", "games": [game]}},
            "end": "to",
            "trainers": {"perArea": [1, 2], "rematch": "each_visit", "classes": ["TEST_YOUNGSTER"]},
            "areas": {"main": [2, 3], "side": 50}, "levels": {"from": "neighbours", "perArea": 1, "maxRise": 3},
            "dungeon": {"chance": 0, "floors": "by_level"}}]}
        # the zone's balls draw on the item tiers by name (data/kantobridge/wild_routes/items.json)
        res[("g2names", "all")]["items"].update({"POTION": 18, "POKE_BALL": 5})
    return res


def index(game=GAME, engine="0.0.0-fake", bridge="fake_gen1-0.1.0", res=None):
    return synth.index(game, engine, bridge, res or resources(game))
