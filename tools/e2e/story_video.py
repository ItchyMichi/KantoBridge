"""Records MG8's story video (the user's request, 2026-10-14): a new game in Hoenn up to the starter, out through the
test pass's clearing into the Hoenn half and across to Kanto, a new game there up to Kanto's starter, and Bill's PC in
Viridian City holding the Hoenn starter. One take, played by the real dev client, which this script steers through
RemotePilot (fabric-bridge/src/client/java/dev/kantobridge/client/dev/RemotePilot.java): commands go into
local/reports/story/commands.jsonl, the client's state comes back in local/reports/story/state.json.

    xvfb-run -a -s "-screen 0 1280x720x24" python tools/e2e/story_video.py --world PassE2E \\
        --record /workspace/videos/kanto-bridge-hoenn-to-kanto.mp4 [--game-display :98]

1. Two fresh data homes on the pod's container disk (the test install's Emerald and Blue imports and gen1-bridge linked
   by name, as spike S1 did; their Union Rooms switched off): no saves, so both games start at their titles.
2. A copy of --world (Kanto and Hoenn built, each with its half of the test pass: MG8) to play in.
3. Emerald with drivers/kb_serve.lua; the dev client (RemotePilot; links emerald and blue, waiting for Emerald);
   Blue once the client is in the world, so the client follows Emerald.
4. The story, played as a player would: Emerald's title, NEW GAME and Birch's speech (BOY, the name typed on the grid);
   the truck; Mom; the clock upstairs; the TV; the rival's house; Route 101, Birch's bag, the starter and the battle
   (Cobblemon: the first move each turn); the lab; then Littleroot's south-east corner, the clearing, the Hoenn half and
   its gate house's pass; the crossing starts Blue's NEW GAME (the name entered by the client, the rival's from the
   presets); the house, Pallet Town's north edge, Oak's escort, a Poke Ball on the table, the rival's battle; Route 1,
   Viridian City's Pokemon Center and its PC: Bill's PC opens Cobblemon's PC, where the crossing stashed the Hoenn
   starter.

Walks are planned on the export's cells (local/minecraft/kantobridge/export/<game>/), read in memory: warps by their
destination, objects by their sprite. Where the export has no position (Gen 3's wall clock), the walk tries the cells
under the room's top wall. Data safety (docs/PLAN.md section 0.3): the output shows map symbols, cells, counts, UI kinds
and screen ids only, never the games' text. The video shows the user's own games: it is for the user, stays on the pod's
volume, and the agent never opens it.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import shutil
import subprocess
import sys
import threading
import time
from collections import deque
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

import run_gen1recomp as rg  # noqa: E402
from run_gen1recomp import INSTALL, launch  # noqa: E402

GRADLE_DIR = REPO / "fabric-bridge"
EXPORT = REPO / "local" / "minecraft" / "kantobridge" / "export"
SAVES = REPO / "local" / "minecraft" / "saves"
PILOT_DIR = REPO / "local" / "reports" / "story"
HOMES = Path("/root/kb-story-homes")
EMERALD_PORT, BLUE_PORT = 27241, 27242
NAME = "KAI"
DELTA = {"up": (0, -1), "down": (0, 1), "left": (-1, 0), "right": (1, 0)}
OPPOSITE = {"up": "down", "down": "up", "left": "right", "right": "left"}
# Gen 1 cell flags (docs/PROTOCOL.md "Export resources")
G1_WALKABLE, G1_WARP, G1_LEDGE = 1, 2048, 8192


class Stuck(Exception):
    pass


def say(*parts):
    print(time.strftime("%H:%M:%S"), *parts, flush=True)


# ---------------------------------------------------------------- the pilot


class Pilot:
    """RemotePilot's two files: commands appended, the state read back."""

    def __init__(self, folder: Path):
        folder.mkdir(parents=True, exist_ok=True)
        self.commands = folder / "commands.jsonl"
        self.state_file = folder / "state.json"
        self.commands.write_text("", encoding="utf-8")
        self.state_file.unlink(missing_ok=True)
        self.next_id = 0

    def send(self, op, **fields):
        self.next_id += 1
        with open(self.commands, "a", encoding="utf-8") as f:
            f.write(json.dumps(dict(id=self.next_id, op=op, **fields)) + "\n")
        return self.next_id

    def state(self):
        for _ in range(40):
            try:
                return json.loads(self.state_file.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                time.sleep(0.05)
        return {}

    def do(self, op, timeout=30.0, **fields):
        cid = self.send(op, **fields)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            st = self.state()
            if st.get("done", 0) >= cid:
                return st.get("result") or {}
            time.sleep(0.05)
        raise Stuck(f"{op} not done within {timeout:.0f} s")


# ---------------------------------------------------------------- maps


class Gen3Map:
    def __init__(self, map_id):
        d = json.loads((EXPORT / "emerald" / "g3map" / f"{map_id}.json").read_text(encoding="utf-8"))
        d = d.get("data", d)
        self.id = map_id
        self.w, self.h, self.stride = d["trueW"], d["trueH"], d["w"]
        raw = base64.b64decode(d["cells"])
        self.coll = [raw[i * 4 + 2] for i in range(len(raw) // 4)]
        self.elev = [raw[i * 4 + 3] for i in range(len(raw) // 4)]
        self.warps = [(w["x"], w["y"], w.get("to")) for w in d.get("warps") or []]
        self.objects = [(o["x"], o["y"], o.get("gfx") or "") for o in d.get("objects") or []]

    def inside(self, x, y):
        return 0 <= x < self.w and 0 <= y < self.h

    def passable(self, x, y):
        return self.inside(x, y) and self.coll[y * self.stride + x] == 0

    def can_move(self, a, b):
        if not self.passable(*b):
            return False
        ea, eb = self.elev[a[1] * self.stride + a[0]] if self.inside(*a) else 0, self.elev[b[1] * self.stride + b[0]]
        return ea == eb or ea in (0, 15) or eb in (0, 15)


class Gen1Map:
    def __init__(self, map_id):
        d = json.loads((EXPORT / "blue" / "map" / f"{map_id}.json").read_text(encoding="utf-8"))
        d = d.get("data", d)
        self.id = map_id
        self.cells = d["cells"]
        self.h, self.w = len(self.cells), len(self.cells[0])
        last = d.get("lastMap")
        self.warps = [(w["x"], w["y"], last if w.get("to") in (None, "LAST_MAP") else w.get("to")) for w in d.get("warps") or []]
        self.objects = [(o["x"], o["y"], o.get("sprite") or "") for o in d.get("objects") or []]

    def inside(self, x, y):
        return 0 <= x < self.w and 0 <= y < self.h

    def passable(self, x, y):
        if not self.inside(x, y):
            return False
        f = self.cells[y][x]
        return bool(f & G1_WALKABLE) and not f & G1_LEDGE

    def can_move(self, a, b):
        return self.passable(*b)


_MAPS = {}


def map_of(map_id):
    if map_id not in _MAPS:
        _MAPS[map_id] = Gen3Map(map_id) if map_id.startswith("EM_") else Gen1Map(map_id)
    return _MAPS[map_id]


def plan(m, start, goals, blocked=()):
    """Directions of a shortest path to a goal cell over passable cells, no warp but a goal's; None when none."""
    goals = set(goals)
    if start in goals:
        return []
    warps = {(x, y) for x, y, _ in m.warps}
    seen = {start: None}
    queue = deque([start])
    while queue:
        cur = queue.popleft()
        for d, (dx, dy) in DELTA.items():
            nxt = (cur[0] + dx, cur[1] + dy)
            if nxt in seen or nxt in blocked:
                continue
            if nxt not in goals and (not m.can_move(cur, nxt) or nxt in warps):
                continue
            if nxt in goals and not m.inside(*nxt):
                continue
            seen[nxt] = (cur, d)
            if nxt in goals:
                dirs = []
                while seen[nxt] is not None:
                    nxt, d = seen[nxt]
                    dirs.append(d)
                return dirs[::-1]
            queue.append(nxt)
    return None


# ---------------------------------------------------------------- the player


class Player:
    def __init__(self, pilot: Pilot):
        self.pilot = pilot
        self.seen_uis = set()
        self.last_ui = None
        self.last_press = 0.0
        self.last_map = None
        self.typed = set()
        self.named = set()
        self.mirrors = 0

    # --- the state

    def st(self):
        st = self.pilot.state()
        m = st.get("map")
        if m != self.last_map:
            say("map", m, st.get("x"), st.get("y"), "followed", st.get("followed"))
            self.last_map = m
        ui = st.get("ui")
        key = (ui.get("kind"), ui.get("screen")) if ui else None
        if key and key not in self.seen_uis:
            self.seen_uis.add(key)
            say("ui", *key)
        return st

    def press(self, key):
        self.pilot.do("key", key=key, hold=2)
        self.last_press = time.monotonic()

    # --- screens

    def answer(self, st, menu_index=0):
        """One answer to the top hidden UI (menus: row `menu_index`, or back out with B when it is None); True when one
        was given."""
        ui = st.get("ui")
        if not ui or ui.get("answered"):
            return False
        kind = ui.get("kind")
        now = time.monotonic()
        if kind == "text":
            if ui.get("stop") in ("scroll", "page", "end") and now - self.last_press > 0.35:
                self.press("a")
                return True
            return False
        if now - self.last_press < 0.5:
            return False
        if kind in ("choice", "menu"):
            if menu_index is None:
                self.press("b")
                return True
            want = menu_index if menu_index < ui.get("count", 1) else 0
            index = ui.get("index", 0)
            self.press("a" if index == want else "down" if index < want else "up")
            return True
        if kind == "naming":
            uid = ui.get("id")
            game = st.get("followed")
            if ui.get("naming") == "presets" and ui.get("presets", 0) > 0:
                # the rival's name: the first preset (entry 0 is NEW NAME)
                self.press("a" if ui.get("preset") == 1 else "down")
            elif uid in self.typed:
                self.press("start")
            elif not ui.get("mon") and game not in self.named:
                # a game's first naming screen without presets is the player's (Blue's is entered by the client)
                self.typed.add(uid)
                self.named.add(game)
                say("naming: typing the player's name")
                self.pilot.do("type", text=NAME, timeout=60)
                self.last_press = time.monotonic()
            else:
                # a nickname: the species' own name (the client can't send an empty one)
                self.typed.add(uid)
                say("naming: a nickname, the species' own name")
                self.pilot.do("type", text="@species", timeout=60)
                self.last_press = time.monotonic()
            return True
        if kind == "mirror":
            if now - self.last_press > 0.8:
                self.mirrors += 1
                self.press("a")
                return True
            return False
        if kind in ("list", "quantity"):
            self.press("b")
            return True
        return False

    def settle(self, quiet=1.5, timeout=240.0, menu_index=0, until=None, only=False):
        """Answers screens until `until(state)` holds, or (unless `only`) until nothing is open, nothing holds the player
        and no battle runs for `quiet` seconds."""
        deadline = time.monotonic() + timeout
        quiet_since = None
        while time.monotonic() < deadline:
            st = self.st()
            if until is not None and until(st):
                return st
            busy = bool(st.get("ui")) or st.get("battle") or st.get("locked") or st.get("crossing") or (st.get("screen") is not None)
            if st.get("ui"):
                self.answer(st, menu_index)
            if busy or not st.get("ingame"):
                quiet_since = None
            elif quiet_since is None:
                quiet_since = time.monotonic()
            elif time.monotonic() - quiet_since >= quiet and not only:
                return st
            time.sleep(0.1)
        raise Stuck(f"screens didn't settle within {timeout:.0f} s")

    # --- walking

    def walk_to(self, goals, map_id, max_steps=250, blocked=()):
        """Steps until the hidden player stands on a goal cell of map_id; returns "there" or "map" (it left)."""
        goals = set(goals)
        temp = {}
        for _ in range(max_steps):
            st = self.settle(quiet=0.25)
            if st.get("map") != map_id:
                return "map"
            pos = (st["x"], st["y"])
            if pos in goals:
                return "there"
            m = map_of(map_id)
            now = time.monotonic()
            temp = {c: t for c, t in temp.items() if t > now}
            objects = {(x, y) for x, y, _ in m.objects} - goals
            path = plan(m, pos, goals, set(blocked) | set(temp) | objects)
            if path is None:
                path = plan(m, pos, goals, set(blocked) | set(temp))
            if path is None:
                raise Stuck(f"no path on {map_id} from {pos} to {len(goals)} goal cells")
            d = path[0]
            r = self.pilot.do("step", dir=d, timeout=20)
            if r.get("reason") == "map":
                return "map"
            if not r.get("ok") and r.get("reason") == "blocked":
                nxt = (pos[0] + DELTA[d][0], pos[1] + DELTA[d][1])
                temp[nxt] = time.monotonic() + 6.0
        raise Stuck(f"too many steps on {map_id}")

    def go_warp(self, map_id, to, near=None):
        """Walks onto a warp of map_id leading to `to` (None: a dynamic warp) until the map changes."""
        m = map_of(map_id)
        cells = [(x, y) for x, y, dest in m.warps if dest == to]
        if not cells:
            raise Stuck(f"{map_id} has no warp to {to}")
        for attempt in range(4):
            st = self.st()
            if st.get("map") != map_id:
                return
            if self.walk_to(cells, map_id) == "map":
                self.wait_map(map_id)
                return
            # on the warp cell: a door or stairs warps at once; a mat or the truck's door wants a push outwards
            st = self.st()
            for d in self.pushes(m, (st["x"], st["y"])):
                r = self.pilot.do("step", dir=d, timeout=20)
                if r.get("reason") == "map" or self.st().get("map") != map_id:
                    self.wait_map(map_id)
                    return
                st = self.st()
                if (st["x"], st["y"]) not in cells:
                    break
        raise Stuck(f"the warp from {map_id} to {to} didn't take")

    @staticmethod
    def pushes(m, pos):
        out = []
        for d, (dx, dy) in DELTA.items():
            nxt = (pos[0] + dx, pos[1] + dy)
            if not m.inside(*nxt) or not m.passable(*nxt):
                out.append(d)
        return out + [d for d in ("down", "up", "left", "right") if d not in out]

    def wait_map(self, old, timeout=60.0):
        return self.settle(quiet=1.0, timeout=timeout, only=True, until=lambda st: st.get("map") not in (None, old)
                           and st.get("ingame") and not st.get("locked"))

    def edge(self, map_id, direction):
        """Walks off map_id's edge in `direction` (a connection) and waits for the next map."""
        m = map_of(map_id)
        if direction == "up":
            goals = [(x, 0) for x in range(m.w) if m.passable(x, 0)]
        elif direction == "down":
            goals = [(x, m.h - 1) for x in range(m.w) if m.passable(x, m.h - 1)]
        elif direction == "left":
            goals = [(0, y) for y in range(m.h) if m.passable(0, y)]
        else:
            goals = [(m.w - 1, y) for y in range(m.h) if m.passable(m.w - 1, y)]
        for attempt in range(6):
            if self.walk_to(goals, map_id) == "map":
                return self.wait_map(map_id)
            r = self.pilot.do("step", dir=direction, timeout=20)
            if r.get("reason") == "map" or self.st().get("map") != map_id:
                return self.wait_map(map_id)
        raise Stuck(f"couldn't leave {map_id} going {direction}")

    def interact(self, direction):
        self.pilot.do("face", dir=direction)
        time.sleep(0.2)
        self.press("a")
        time.sleep(0.6)

    def next_to(self, map_id, target):
        """Walks beside `target` and returns the direction facing it."""
        tx, ty = target
        m = map_of(map_id)
        sides = {(tx + dx, ty + dy): OPPOSITE[d] for d, (dx, dy) in DELTA.items() if m.passable(tx + dx, ty + dy)}
        if not sides:
            raise Stuck(f"nothing walkable beside {target} on {map_id}")
        self.walk_to(sides.keys(), map_id)
        st = self.st()
        return sides.get((st["x"], st["y"]))


# ---------------------------------------------------------------- Hoenn


def emerald_story(p: Player):
    say("== Emerald: the title and Birch's speech")
    p.settle(quiet=4.0, timeout=600, only=True, until=lambda st: st.get("ingame") and st.get("map") == "EM_INSIDE_OF_TRUCK"
             and not st.get("ui") and not st.get("locked"))
    say("== the truck")
    p.settle(quiet=3.0, timeout=180)
    p.go_warp("EM_INSIDE_OF_TRUCK", None)  # its door is a dynamic warp (the script sets Littleroot)
    say("== Littleroot: Mom")
    p.settle(quiet=2.5, timeout=240)
    house1, house2 = "EM_LITTLEROOT_TOWN_BRENDANS_HOUSE_1F", "EM_LITTLEROOT_TOWN_BRENDANS_HOUSE_2F"
    if p.st().get("map") == "EM_LITTLEROOT_TOWN":
        p.go_warp("EM_LITTLEROOT_TOWN", house1)
        p.settle(quiet=2.5)
    say("== the house: upstairs to the clock")
    p.go_warp(house1, house2)
    p.settle(quiet=2.0)
    set_the_clock(p, house2)
    say("== downstairs: the TV")
    p.go_warp(house2, house1)
    p.settle(quiet=3.0, timeout=300)
    p.go_warp(house1, "EM_LITTLEROOT_TOWN")
    p.settle(quiet=2.0)
    say("== the rival's house")
    rival1, rival2 = "EM_LITTLEROOT_TOWN_MAYS_HOUSE_1F", "EM_LITTLEROOT_TOWN_MAYS_HOUSE_2F"
    p.go_warp("EM_LITTLEROOT_TOWN", rival1)
    p.settle(quiet=2.5)
    p.go_warp(rival1, rival2)
    p.settle(quiet=2.5)
    meet_the_rival(p, rival2)
    p.go_warp(rival2, rival1)
    p.settle(quiet=2.5)
    p.go_warp(rival1, "EM_LITTLEROOT_TOWN")
    p.settle(quiet=2.0)
    say("== north to Route 101")
    p.edge("EM_LITTLEROOT_TOWN", "up")
    p.settle(quiet=3.0, timeout=300)
    say("== Birch's bag")
    for attempt in range(3):
        st = p.st()
        if st.get("map") != "EM_ROUTE101":
            break
        bag = next(((x, y) for x, y, gfx in map_of("EM_ROUTE101").objects if "BAG" in gfx), None)
        if bag is None:
            raise Stuck("no bag object on Route 101")
        facing = p.next_to("EM_ROUTE101", bag)
        p.interact(facing)
        p.settle(quiet=4.0, timeout=600, only=True, until=lambda st: st.get("map") == "EM_LITTLEROOT_TOWN_PROFESSOR_BIRCHS_LAB"
                 and not st.get("ui") and not st.get("battle") and not st.get("locked") and st.get("battle_choices", 0) > 0)
    say("== the lab")
    p.settle(quiet=4.0, timeout=300)
    p.go_warp("EM_LITTLEROOT_TOWN_PROFESSOR_BIRCHS_LAB", "EM_LITTLEROOT_TOWN")
    p.settle(quiet=2.0)


def set_the_clock(p: Player, room):
    """The wall clock: the export has no position for it, so A on each cell under the top wall until it answers."""
    m = map_of(room)
    warps = {(x, y) for x, y, _ in m.warps}
    st = p.st()
    start = (st["x"], st["y"])
    cells = [(x, y) for y in range(m.h) for x in range(m.w)
             if m.passable(x, y) and not m.passable(x, y - 1) and (x, y) not in warps]
    cells.sort(key=lambda c: (c[1], abs(c[0] - start[0])))
    before = p.mirrors
    for cell in cells:
        if p.walk_to([cell], room) == "map":
            raise Stuck("left the room looking for the clock")
        p.interact("up")
        p.settle(quiet=2.5, timeout=240)
        if p.mirrors > before:
            say("clock set at", cell)
            p.settle(quiet=3.0, timeout=240)
            return
    raise Stuck("no clock answered")


def meet_the_rival(p: Player, room):
    """The rival comes in once the player is inside the room: walk to its far side."""
    m = map_of(room)
    st = p.st()
    start = (st["x"], st["y"])
    far = max(((x, y) for y in range(m.h) for x in range(m.w) if m.passable(x, y)),
              key=lambda c: abs(c[0] - start[0]) + abs(c[1] - start[1]))
    p.walk_to([far], room)
    p.settle(quiet=3.0, timeout=300)


def into_the_route(p: Player):
    spot = json.loads((REPO / "gen1-bridge" / "data" / "routes.json").read_text(encoding="utf-8"))["routes"][1]["to"]
    town = spot["map"]
    say("== the clearing at", spot["x"], spot["y"])
    p.walk_to([(spot["x"], spot["y"])], town)
    for _ in range(12):
        r = p.pilot.do("step", dir=spot["facing"], timeout=20)
        st = p.st()
        if st.get("map", "").startswith("KB_ROUTE_"):
            break
        if not r.get("ok") and r.get("reason") == "blocked":
            raise Stuck("the clearing is closed")
    p.settle(quiet=3.0, timeout=120)
    say("== the Hoenn half: to its pass")
    r = p.pilot.do("zone_walk", timeout=200)
    say("zone walk:", r.get("ok"), r.get("reason"))
    if not r.get("ok"):
        raise Stuck("the walk to the pass failed: " + str(r.get("reason")))


# ---------------------------------------------------------------- Kanto


def kanto_story(p: Player):
    say("== the crossing: Blue's NEW GAME")
    p.settle(quiet=4.0, timeout=600, only=True, until=lambda st: st.get("followed") == "blue" and st.get("ingame")
             and st.get("map") == "REDS_HOUSE_2F" and not st.get("ui") and not st.get("locked") and not st.get("crossing"))
    p.settle(quiet=3.0)
    say("== downstairs and out")
    p.go_warp("REDS_HOUSE_2F", "REDS_HOUSE_1F")
    p.settle(quiet=2.0)
    p.go_warp("REDS_HOUSE_1F", "PALLET_TOWN")
    p.settle(quiet=2.0)
    say("== Pallet Town's north edge: Oak")
    m = map_of("PALLET_TOWN")
    row = [(x, 1) for x in range(m.w) if m.passable(x, 1)]
    p.walk_to(row, "PALLET_TOWN")
    p.settle(quiet=4.0, timeout=600, only=True, until=lambda st: st.get("map") == "OAKS_LAB" and not st.get("ui")
             and not st.get("locked") and st.get("mode") in (None, "idle"))
    p.settle(quiet=3.0, timeout=300)
    say("== a Poke Ball on the table")
    balls = sorted((x, y) for x, y, sprite in map_of("OAKS_LAB").objects if "BALL" in sprite)
    if not balls:
        raise Stuck("no Poke Balls in the lab")
    facing = p.next_to("OAKS_LAB", balls[0])
    p.interact(facing)
    p.settle(quiet=3.0, timeout=300)
    say("== the rival's battle on the way out")
    for attempt in range(3):
        if p.st().get("map") != "OAKS_LAB":
            break
        try:
            p.go_warp("OAKS_LAB", "PALLET_TOWN")
        except Stuck:
            p.settle(quiet=3.0, timeout=600)
    p.settle(quiet=2.0)
    say("== Route 1 to Viridian City")
    p.edge("PALLET_TOWN", "up")
    p.edge("ROUTE_1", "up")
    p.settle(quiet=2.0)
    p.go_warp("VIRIDIAN_CITY", "VIRIDIAN_POKECENTER")
    p.settle(quiet=2.0)
    say("== Bill's PC")
    use_pc(p, "VIRIDIAN_POKECENTER")
    time.sleep(10.0)
    p.pilot.do("key", key="esc", hold=2)
    p.settle(quiet=3.0, timeout=120, menu_index=None)


def on_pc(st):
    return "PC" in (st.get("screen") or "")


def use_pc(p: Player, room):
    """A on the cells under the top wall, rightmost first, until Bill's PC opens Cobblemon's PC; anything else that
    answers (the nurse's counter) is answered and left."""
    m = map_of(room)
    warps = {(x, y) for x, y, _ in m.warps}
    cells = [(x, y) for y in range(m.h) for x in range(m.w)
             if m.passable(x, y) and not m.passable(x, y - 1) and (x, y) not in warps]
    st = p.st()
    here = (st["x"], st["y"])
    cells = [c for c in cells if plan(m, here, [c]) is not None]  # behind the counter: out of reach
    cells.sort(key=lambda c: (-c[0], c[1]))
    for cell in cells[:8]:
        try:
            if p.walk_to([cell], room) == "map":
                raise Stuck("left the Pokemon Center looking for the PC")
        except Stuck as e:
            if "left the" in str(e):
                raise
            say("pc: skipped a cell:", e)
            continue
        p.interact("up")
        st = p.settle(quiet=3.0, timeout=120, until=on_pc)
        if on_pc(st):
            say("pc open at", cell, "screen", st.get("screen"))
            return
        p.settle(quiet=1.5, timeout=60, menu_index=None)
    raise Stuck("no PC answered")


# ---------------------------------------------------------------- the run


def make_home(name: str, game: str) -> Path:
    root = HOMES / name
    if root.exists():
        shutil.rmtree(root)
    ident = root / rg.IDENTITY
    (ident / "mods").mkdir(parents=True)
    (ident / game).symlink_to(INSTALL / game)  # the import folder by name; never opened here
    (ident / "mods" / "gen1-bridge").symlink_to(REPO / "gen1-bridge")
    if (INSTALL / "portable.txt").exists():
        (ident / "portable.txt").symlink_to(INSTALL / "portable.txt")
    return ident


def keep_logs(runs: dict, stop: threading.Event):
    """Each game's safe lines (run_gen1recomp's filter: gen1-bridge's codes and counts, the driver's markers) into
    local/reports/story/<game>.log, every few seconds and at the end."""
    while True:
        for game, run in list(runs.items()):
            if run is not None:
                (PILOT_DIR / f"{game}.log").write_text("\n".join(list(run.result.lines)) + "\n", encoding="utf-8")
        if stop.wait(5.0):
            break


def client_command(world: str) -> list[str]:
    return ["./gradlew", "--no-daemon", "--console=plain", "runClient", "-Pkb.autopilot=remote", f"-Pkb.autopilotWorld={world}",
            f"-Pkb.autopilotPilotDir={PILOT_DIR}", "-Pkb.autopilotFollow=emerald", "-Pkb.autopilotQuit=true",
            f"-Pkb.links=emerald:{EMERALD_PORT},blue:{BLUE_PORT}"]


def stream(proc, log_path: Path):
    """The client's output to a file; only the autopilot's lines and exception class names are printed."""
    with open(log_path, "w", encoding="utf-8", buffering=1) as log:
        for raw in iter(proc.stdout.readline, b""):
            line = raw.decode("utf-8", errors="replace").rstrip()
            log.write(line + "\n")
            if "autopilot:" in line and "done id=" not in line and "command id=" not in line:
                print("  client", line[line.index("autopilot:"):], flush=True)
            elif "Exception" in line and "kantobridge" in line.lower():
                print("  client exception line", flush=True)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--world", default="PassE2E", help="the built world in local/minecraft/saves (copied first)")
    parser.add_argument("--record", help="the .mp4 to write")
    parser.add_argument("--game-display", help="the hidden games' X display (default: DISPLAY)")
    parser.add_argument("--timeout", type=int, default=5400)
    parser.add_argument("--scene", choices=("story", "zone"), default="story",
                        help="zone: a test of the second half only (drivers/kb_emerald.lua's route scene puts a new game on "
                        "the clearing's exit cell, no truck, no story), then the Hoenn half, the crossing and Kanto")
    args = parser.parse_args(argv)

    source = SAVES / args.world
    if not (source / "level.dat").is_file():
        print(f"no world {args.world}", file=sys.stderr)
        return 2
    world = args.world + "Story"
    if (SAVES / world).exists():
        shutil.rmtree(SAVES / world)
    shutil.copytree(source, SAVES / world)
    say("world copied:", world)

    homes = {"emerald": make_home("hoenn", "emerald"), "blue": make_home("kanto", "blue")}
    game_env = {"KB_DATASET_MEMO": "30", "KB_PACE": "60", "KB_DRIVER_SECONDS": str(args.timeout + 600)}
    if args.game_display:
        game_env["DISPLAY"] = args.game_display
    for game, home in homes.items():
        with launch("kb_union_off.lua", game=game, install=home, echo=False, extra_env=game_env) as off:
            say("union off", game, off.wait(timeout=180).marker("KB_UNION_OFF"))

    pilot = Pilot(PILOT_DIR)
    player = Player(pilot)
    if args.scene == "zone":
        emerald = launch("kb_emerald.lua", game="emerald", install=homes["emerald"], echo=False,
                         extra_env=dict(game_env, KB_TEST_PORT=str(EMERALD_PORT), KB_SCENE="route"))
    else:
        emerald = launch("kb_serve.lua", game="emerald", install=homes["emerald"], echo=False,
                         extra_env=dict(game_env, KB_TEST_PORT=str(EMERALD_PORT)))
    runs = {"emerald": emerald}
    stop_logs = threading.Event()
    threading.Thread(target=keep_logs, args=(runs, stop_logs), daemon=True).start()
    blue = None
    client = None
    code = 1
    try:
        say("emerald:", emerald.wait_marker("KB_SERVING" if args.scene == "zone" else "KB_BOOTED", timeout=240))
        java_home = os.environ.get("KB_JAVA_HOME") or os.environ.get("JAVA_HOME") or "/opt/java/openjdk"
        client = subprocess.Popen(client_command(world), cwd=GRADLE_DIR, env=dict(os.environ, JAVA_HOME=java_home),
                                  stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        threading.Thread(target=stream, args=(client, PILOT_DIR / "client.log"), daemon=True).start()
        deadline = time.monotonic() + 900
        while pilot.state().get("phase") != "ready":
            if time.monotonic() > deadline or client.poll() is not None:
                raise Stuck("the client never reached the world")
            time.sleep(1.0)
        say("client in the world; following", pilot.state().get("followed"))
        # a fresh player follows the legacy region (Kanto) once its link is up: the player chooses Hoenn first
        say("follow emerald:", pilot.do("follow", game="emerald").get("ok"))
        blue = launch("kb_serve.lua", game="blue", install=homes["blue"], echo=False,
                      extra_env=dict(game_env, KB_TEST_PORT=str(BLUE_PORT)))
        runs["blue"] = blue
        say("blue:", blue.wait_marker("KB_BOOTED", timeout=240))
        pilot.do("battle", mode="on")
        if args.record:
            pilot.do("record", file=args.record, timeout=30)
        if args.scene == "story":
            emerald_story(player)
        into_the_route(player)
        kanto_story(player)
        say("== done")
        code = 0
    except Stuck as e:
        st = pilot.state()
        say("STUCK:", e)
        say("state:", json.dumps({k: st.get(k) for k in ("map", "x", "y", "facing", "mode", "ingame", "followed", "screen", "ui",
                                                         "locked", "battle", "result")}))
    finally:
        try:
            pilot.do("stop", timeout=60)
            pilot.send("quit")
        except Stuck:
            pass
        if client is not None:
            try:
                client.wait(timeout=120)
            except subprocess.TimeoutExpired:
                client.kill()
        for run in (emerald, blue):
            if run is not None:
                run.kill()
                run.wait(timeout=30)
        stop_logs.set()
    say("uis seen:", sorted(map(str, player.seen_uis)))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
