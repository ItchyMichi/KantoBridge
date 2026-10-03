"""The synthetic world fake_gen1 serves. Made up from scratch; no ROM data.

TEST_TOWN is 10x9 blocks (20x18 cells) with a tree border, one house, two NPCs, an item ball, a
sign and a trainer. TEST_HOUSE is its 4x4-block interior. Coordinates are 16-px cells.
"""
from dataclasses import dataclass, field

DIRS = {"up": (0, -1), "down": (0, 1), "left": (-1, 0), "right": (1, 0)}


@dataclass
class Npc:
    id: str
    sprite: str
    kind: str
    x: int
    y: int
    facing: str = "down"
    moving: bool = False
    text: tuple = ()

    def as_data(self):
        return {"id": self.id, "sprite": self.sprite, "kind": self.kind, "x": self.x, "y": self.y,
                "facing": self.facing, "moving": self.moving}


@dataclass
class Warp:
    to_map: str
    to_x: int
    to_y: int
    facing: str
    kind: str = "door"


@dataclass
class MapDef:
    id: str
    width: int
    height: int
    walls: set = field(default_factory=set)
    warps: dict = field(default_factory=dict)
    signs: dict = field(default_factory=dict)
    npcs: list = field(default_factory=list)

    def walkable(self, x, y):
        return 0 <= x < self.width and 0 <= y < self.height and (x, y) not in self.walls

    def npc_at(self, x, y):
        for npc in self.npcs:
            if (npc.x, npc.y) == (x, y):
                return npc
        return None


def _border(width, height):
    cells = set()
    for x in range(width):
        cells.add((x, 0))
        cells.add((x, height - 1))
    for y in range(height):
        cells.add((0, y))
        cells.add((width - 1, y))
    return cells


def build_world():
    town = MapDef("TEST_TOWN", 20, 18)
    town.walls |= _border(20, 18)
    # The house: rows 3-5, columns 4-7, with its door at (5, 5).
    town.walls |= {(x, y) for x in range(4, 8) for y in range(3, 6)} - {(5, 5)}
    town.walls.add((8, 6))  # the sign post
    town.signs[(8, 6)] = ("TEST TOWN", "A place for tests.")
    town.warps[(5, 5)] = Warp("TEST_HOUSE", 3, 6, "up", "door")
    town.npcs = [
        Npc("TEST_TOWN_obj_1", "SPRITE_TEST_GIRL", "person", 10, 8, "down",
            text=("Hi! I'm a made-up", "test person.")),
        Npc("TEST_TOWN_obj_2", "SPRITE_TEST_BALL", "item", 15, 4),
        Npc("TEST_TOWN_obj_3", "SPRITE_TEST_GUARD", "trainer", 12, 12, "left",
            text=("Let's battle!",)),
    ]

    house = MapDef("TEST_HOUSE", 8, 8)
    house.walls |= _border(8, 8) - {(3, 7)}
    house.walls |= {(1, 1), (2, 1)}  # a made-up table
    house.warps[(3, 7)] = Warp("TEST_TOWN", 5, 6, "down", "door")
    house.npcs = [Npc("TEST_HOUSE_obj_1", "SPRITE_TEST_MOM", "person", 5, 3, "left",
                      text=("Welcome home,", "tester!"))]
    return {m.id: m for m in (town, house)}


@dataclass
class Player:
    map: str = "TEST_TOWN"
    x: int = 5
    y: int = 8
    facing: str = "down"

    def as_data(self):
        return {"map": self.map, "x": self.x, "y": self.y, "facing": self.facing}


@dataclass
class State:
    mode: str = "idle"
    bike: bool = False
    surf: bool = False
    slope: bool = False
    hidden: bool = False
    dark: bool = False
    lit: bool = False

    def as_data(self):
        return {"mode": self.mode, "bike": self.bike, "surf": self.surf, "slope": self.slope,
                "hidden": self.hidden, "dark": self.dark, "lit": self.lit}


@dataclass
class Story:
    flags: set = field(default_factory=set)
    toggles: dict = field(default_factory=dict)
    bag: dict = field(default_factory=lambda: {"POTION": 1})
    money: int = 3000
    coins: int = 0
    badges: list = field(default_factory=list)
    party: list = field(default_factory=list)
    seen: set = field(default_factory=set)
    owned: set = field(default_factory=set)
