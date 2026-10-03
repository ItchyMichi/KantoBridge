"""The synthetic world fake_gen1 plays. Made up from scratch; no ROM data.

The maps, walls, warps, signs and objects all come from `synth`, the same data fake_gen1 exports,
so walking here agrees with the world the builder makes from that export. Coordinates are 16-px
cells.
"""
from dataclasses import dataclass, field

from . import synth

DIRS = {"up": (0, -1), "down": (0, 1), "left": (-1, 0), "right": (1, 0)}

# Made-up lines for the people and the sign (by object name, and by sign cell).
TEXTS = {
    "TESTTOWN_GIRL": ("Hi! I'm a made-up", "test person."),
    "TESTTOWN_GUARD": ("Let's battle!",),
    "TESTHOUSE_MOM": ("Welcome home,", "tester!"),
}
SIGN_TEXTS = {("TEST_TOWN", 11, 5): ("TEST TOWN", "A place for tests.")}
# Where a warp lands: the destination warp's cell, then a step away from it for exits to the town.
ARRIVALS = {
    ("TEST_TOWN", 5, 5): ("TEST_HOUSE", 4, 8, "up", "door"),
    ("TEST_HOUSE", 4, 8): ("TEST_TOWN", 5, 6, "down", "door"),
    ("TEST_HOUSE", 5, 8): ("TEST_TOWN", 5, 6, "down", "door"),
}
OBJECT_KINDS = {"item": "item", "trainer": "trainer", "pokemon": "static"}


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


def _facing(obj):
    return obj["range"].lower() if obj["range"] in ("UP", "DOWN", "LEFT", "RIGHT") else "down"


def build_world():
    world = {}
    for name in synth.MAPS:
        data = synth.resources()[("map", name)]
        cells = data["cells"]
        m = MapDef(name, data["width"] * 2, data["height"] * 2)
        m.walls = {(x, y) for y, row in enumerate(cells) for x, flags in enumerate(row)
                   if not flags & synth.F_WALK}
        for warp in data["warps"]:
            to = ARRIVALS[(name, warp["x"], warp["y"])]
            m.warps[(warp["x"], warp["y"])] = Warp(*to)
        for sign in data["signs"]:
            m.signs[(sign["x"], sign["y"])] = SIGN_TEXTS[(name, sign["x"], sign["y"])]
        for obj in data["objects"]:
            kind = next((k for key, k in OBJECT_KINDS.items() if key in obj), "person")
            m.npcs.append(Npc(f"{name}_obj_{obj['index']}", obj["sprite"], kind, obj["x"], obj["y"],
                              _facing(obj), text=TEXTS.get(obj["name"], ())))
        world[name] = m
    return world


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
