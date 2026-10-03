"""M3 end to end, on the real engine: gen1-bridge's step driver and warps in the Gen1Recomp test
instance, driven by fake_minecraft (docs/PLAN.md M3 [T]).

The driver (drivers/kb_walk.lua) starts a new game without the intro, so the player wakes up in
REDS_HOUSE_2F. The test first fetches the three maps' cell flags from the real export as role
`builder` (kept in memory only), then connects as role `test` and walks with move.step:

  1. to the stairs and onto them        warp start/arrive kind "stairs", into REDS_HOUSE_1F
  2. to the exit mat, stepping down     warp kind "edge" (an exit mat on the map edge), into
                                        PALLET_TOWN, then the door walk-out (kind "script")
  3. into a wall, as a push             player.blocked reason "tile"
  4. back up through the house door     warp kind "door", into REDS_HOUSE_1F (on the mat)
  5. a push down against the mat        player.pos kind "sync" for the push, warp kind "edge"

Each step is sent as soon as the previous one starts, so the step driver walks continuously and
turns corners at the landing poll. The test checks that every move.step is answered exactly once
(player.pos or player.blocked with its n: no drift), that every message passes its schema
(strict), that the engine's map.entered fired for each warp (the driver's KB_MAP_ENTERED markers),
and that the game exits 0 with the crash log untouched.

Data safety (docs/PLAN.md section 0.3): routes are computed in memory from the export's cell
flags and never printed. Output and assertion messages show only message types, kinds, counts,
map symbols and step counts.
"""

import asyncio
import time
from collections import Counter, deque

import pytest

from fake_minecraft import FakeMinecraft
from run_gen1recomp import launch, preflight

TEST_PORT = 27196
MAPS = ("REDS_HOUSE_2F", "REDS_HOUSE_1F", "PALLET_TOWN")
# per-cell flag bits (docs/PROTOCOL.md "Export resources")
WALKABLE, WATER, DOOR, WARP_TILE, WARP = 1, 2, 16, 32, 2048
DELTA = {"up": (0, -1), "down": (0, 1), "left": (-1, 0), "right": (1, 0)}
# PALLET_TOWN's onStep stops a Red/Blue player at y == 1 for Oak's cutscene
# (refs/gen1recomp data/scripts/story2.lua); routes there stay on rows >= 3.
PALLET_MIN_ROW = 3
ENTITY_RETRIES = 12


class Grid:
    """One map's cell flags (rows top to bottom)."""

    def __init__(self, data):
        self.cells = data["cells"]
        self.h, self.w = len(self.cells), len(self.cells[0])

    def flags(self, x, y):
        if 0 <= x < self.w and 0 <= y < self.h:
            return self.cells[y][x]
        return None

    def walkable(self, x, y):
        f = self.flags(x, y)
        return f is not None and bool(f & WALKABLE)

    def warp(self, x, y):
        f = self.flags(x, y)
        return f is not None and bool(f & WARP)


def route(grid, start, goals, blocked=(), min_row=0):
    """Directions of a shortest 4-neighbour path over walkable cells that are no warp (unless a
    goal), not blocked and on rows >= min_row; None when there is none."""
    goals = set(goals)
    if start in goals:
        return []
    seen = {start: None}
    queue = deque([start])
    while queue:
        cur = queue.popleft()
        for d, (dx, dy) in DELTA.items():
            nxt = (cur[0] + dx, cur[1] + dy)
            if nxt in seen or not grid.walkable(*nxt) or nxt in blocked or nxt[1] < min_row:
                continue
            if grid.warp(*nxt) and nxt not in goals:
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


class Walker:
    """Sends move.step and tracks what gen1-bridge reports: the hidden position, the mode, warps
    and the answer to every n."""

    def __init__(self, mc):
        self.mc = mc
        self.n = 0
        self.sent = []
        self.answers = {}
        self.warps = []
        self.pos = None
        self.mode = None
        self.npcs = set()
        self.counts = Counter()
        self.twice = 0
        self.unknown = 0
        self.pipelined = 0  # steps sent while the one before was still walking
        self.pos_kinds = Counter()

    def handle(self, m):
        t, d = m["type"], m["data"]
        self.counts[t] += 1
        if t == "snapshot":
            p = d["player"]
            self.pos = (p["map"], p["x"], p["y"], p["facing"])
            self.mode = d["state"]["mode"]
            self.npcs = {(n["x"], n["y"]) for n in d["npcs"]["list"]} if d["npcs"]["map"] == p["map"] else set()
        elif t == "player.state":
            self.mode = d["mode"]
        elif t == "player.pos":
            self.pos = (d["map"], d["x"], d["y"], d["facing"])
            self.pos_kinds[d["kind"]] += 1
        elif t == "player.blocked":
            self.pos = (d["map"], d["x"], d["y"], d["dir"])
        elif t == "warp":
            self.warps.append(d)
        if t in ("player.pos", "player.blocked") and "n" in d:
            n = d["n"]
            if n in self.answers:
                self.twice += 1
            elif n not in self.sent:
                self.unknown += 1
            else:
                self.answers[n] = m

    async def pump(self, timeout):
        try:
            m = await self.mc.next(timeout)
        except asyncio.TimeoutError:
            return None
        if m is None:
            raise ConnectionError("gen1-bridge closed the connection")
        self.handle(m)
        return m

    async def until(self, cond, timeout, what):
        deadline = time.monotonic() + timeout
        while not cond():
            left = deadline - time.monotonic()
            if left <= 0:
                raise AssertionError(f"timed out waiting for {what}")
            await self.pump(min(left, 0.5))

    async def step(self, direction, push=False):
        self.n += 1
        data = {"dir": direction, "n": self.n}
        if push:
            data["push"] = True
        self.sent.append(self.n)
        await self.mc.send("move.step", data)
        return self.n

    async def answer(self, n, timeout=15):
        await self.until(lambda: n in self.answers, timeout, "a move.step answer")
        return self.answers[n]

    async def idle(self, timeout=30, quiet=0.5):
        """Waits until gen1-bridge reports the idle mode and nothing but heartbeats has arrived for
        a moment (a step in flight lands, a warp's fade and walk-out finish)."""
        deadline = time.monotonic() + timeout
        last = time.monotonic()
        while not (self.mode == "idle" and time.monotonic() - last >= quiet):
            if time.monotonic() > deadline:
                raise AssertionError("timed out waiting for the idle mode")
            m = await self.pump(0.1)
            # NPCs wandering and mirror frames (M4) are no sign of the player's walk
            if m is not None and m["type"] not in ("pong", "log", "npc.move", "npc.face", "npc.add",
                                                   "npc.remove", "emote", "mirror.frame"):
                last = time.monotonic()

    async def resync(self):
        message = await self.mc.send("resync")
        while True:
            m = await self.pump(10)
            if m is None:
                raise AssertionError("no snapshot after resync")
            if m["type"] == "snapshot" and m.get("re") == message["seq"]:
                return

    async def follow(self, dirs):
        """Sends the steps, each one as soon as the one before has started, so they run back to
        back. Returns True, or False after a refusal (the caller plans again)."""
        if not dirs:
            return True
        outstanding = deque([await self.step(dirs[0])])
        for d in dirs[1:]:
            m = await self.answer(outstanding.popleft())
            if m["type"] != "player.pos":
                return False
            outstanding.append(await self.step(d))
            self.pipelined += 1
        while outstanding:
            m = await self.answer(outstanding.popleft())
            if m["type"] != "player.pos":
                return False
        return True

    async def walk_to(self, grid, goals, min_row=0):
        """Walks to one of the goal cells, planning again around NPCs that get in the way. A goal
        that is a warp ends the walk on another map."""
        steps, start_map = 0, self.pos[0]
        for _ in range(ENTITY_RETRIES):
            here = self.pos[1], self.pos[2]
            dirs = route(grid, here, goals, blocked=self.npcs, min_row=min_row)
            if dirs is None:
                # Unreachable even without NPCs: a real dead end. Otherwise an NPC stands on the
                # goal or in a narrow pass (M10 gate flake): wait for it to wander off, plan again.
                assert route(grid, here, goals, min_row=min_row) is not None, "no route to the goal cells"
                await asyncio.sleep(0.6)
                await self.resync()
                continue
            ok = await self.follow(dirs)
            steps += len(dirs)
            await self.idle()
            if ok and (self.pos[0] != start_map or (self.pos[1], self.pos[2]) in set(goals)):
                return steps
            await asyncio.sleep(0.6)  # a wandering NPC: wait, look again, plan again
            await self.resync()
        raise AssertionError("could not reach the goal cells")


async def _export_maps(port):
    builder = FakeMinecraft(port=port, role="builder", ping_ms=1000)
    await builder.connect(attempts=20)
    await builder.hello()
    await builder.expect("snapshot", timeout=10)
    index = (await builder.request("export.index", {}, reply_type="export.index", timeout=240))["data"]
    have = {(e["kind"], e["id"]) for e in index["resources"]}
    assert all(("map", m) in have for m in MAPS), "the export lacks one of Pallet's maps"
    grids = {}
    for map_id in MAPS:
        reply = await builder.request("export.get", {"kind": "map", "id": map_id}, reply_type="export.data",
                                      timeout=60)
        grids[map_id] = Grid(reply["data"]["data"])
    assert not builder.conn.problems
    await builder.close()
    return grids


async def _walk(port):
    grids = await _export_maps(port)
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=20)
    await mc.hello()
    w = Walker(mc)
    await w.until(lambda: w.pos is not None, 10, "the snapshot")
    assert w.pos[0] == "REDS_HOUSE_2F", w.pos[0]
    await w.idle()
    legs = {}

    # 1. upstairs: walk onto the stairs (a warp entry on a warp tile that is no door)
    up = grids["REDS_HOUSE_2F"]
    stairs = [(x, y) for y in range(up.h) for x in range(up.w)
              if up.warp(x, y) and up.flags(x, y) & WARP_TILE and not up.flags(x, y) & DOOR]
    assert stairs, "no stairs warp upstairs"
    legs["stairs"] = await w.walk_to(up, stairs)
    await w.until(lambda: len(w.warps) >= 2, 20, "the stairs warp")
    await w.idle()
    assert w.pos[0] == "REDS_HOUSE_1F", w.pos[0]

    # 2. downstairs: walk to a cell right above an exit mat, then step down onto the mat; with the
    #    direction held toward the map edge the warp fires on arrival
    down = grids["REDS_HOUSE_1F"]
    await w.resync()  # this map's NPCs
    mats = [(x, y) for y in range(down.h) for x in range(down.w)
            if down.warp(x, y) and not down.flags(x, y) & (DOOR | WARP_TILE) and y == down.h - 1]
    assert mats, "no exit mat downstairs"
    above = [(x, y - 1) for x, y in mats if down.walkable(x, y - 1) and not down.warp(x, y - 1)]
    assert above, "no way onto the exit mats"
    legs["to_mat"] = await w.walk_to(down, above)
    out = await w.step("down")
    first = await w.answer(out)
    assert first["type"] == "player.pos" and first["data"]["kind"] == "walk", first["type"]
    await w.until(lambda: len(w.warps) >= 4, 20, "the exit mat warp")
    await w.idle()
    door = w.warps[3]["to"]
    assert door["map"] == "PALLET_TOWN" and w.pos[0] == "PALLET_TOWN", (door["map"], w.pos[0])

    # 3. outdoors: walk to a cell beside a wall, then push into it
    town = grids["PALLET_TOWN"]
    await w.resync()

    def solid(x, y):
        f = town.flags(x, y)
        return f is not None and not f & (WALKABLE | WATER | WARP)

    beside = {}
    for y in range(PALLET_MIN_ROW, town.h):
        for x in range(town.w):
            if town.walkable(x, y) and not town.warp(x, y):
                for d, (dx, dy) in DELTA.items():
                    if solid(x + dx, y + dy):
                        beside.setdefault((x, y), d)
    assert beside, "no wall to bump into"
    legs["to_wall"] = await w.walk_to(town, list(beside), min_row=PALLET_MIN_ROW)
    bump_dir = beside[(w.pos[1], w.pos[2])]
    bump = await w.step(bump_dir, push=True)
    blocked = await w.answer(bump)
    assert blocked["type"] == "player.blocked", blocked["type"]
    assert blocked["data"]["reason"] == "tile", blocked["data"]["reason"]
    await w.idle()

    # 4. back in through the house door, from the cell below it
    below_door = (door["x"], door["y"] + 1)
    legs["to_door"] = await w.walk_to(town, [below_door], min_row=PALLET_MIN_ROW)
    into = await w.step("up")
    entering = await w.answer(into)
    assert entering["type"] == "player.pos" and entering["data"]["kind"] == "walk", entering["type"]
    await w.until(lambda: len(w.warps) >= 6, 20, "the door warp")
    await w.idle()
    assert w.pos[0] == "REDS_HOUSE_1F", w.pos[0]

    # 5. on the mat: push down against the bottom edge
    push = await w.step("down", push=True)
    pushed = await w.answer(push)
    assert pushed["type"] == "player.pos" and pushed["data"]["kind"] == "sync", pushed["type"]
    await w.until(lambda: len(w.warps) >= 8, 20, "the push warp")
    await w.idle()
    assert w.pos[0] == "PALLET_TOWN", w.pos[0]

    status = (await mc.request("debug.status", reply_type="debug.status", timeout=10))["data"]
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return w, legs, status


@pytest.mark.e2e
@pytest.mark.timeout(600)
def test_walking_stairs_doors_and_a_wall():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    with launch("kb_walk.lua", extra_env={"KB_TEST_PORT": str(TEST_PORT)}) as running:
        marker = running.wait_marker("KB_OVERWORLD", timeout=150)
        assert marker is not None, running.result.summary()
        assert f"port={TEST_PORT}" in marker
        w, legs, status = asyncio.run(asyncio.wait_for(_walk(TEST_PORT), 420))
        result = running.wait(timeout=60)

    kinds = [(d["phase"], d["kind"]) for d in w.warps]
    arrivals = [d["to"]["map"] for d in w.warps if d["phase"] == "arrive"]
    answer_types = Counter(m["type"] for m in w.answers.values())
    entered = [line.split(" ", 1)[1] for line in result.lines if line.startswith("KB_MAP_ENTERED")]
    print("steps sent:", len(w.sent), "answers:", dict(answer_types), "legs (steps):", legs,
          "sent while walking:", w.pipelined)
    print("warps:", kinds)
    print("arrivals:", arrivals)
    print("map.entered:", entered)
    print("messages received:", dict(sorted(w.counts.items())), "player.pos kinds:", dict(sorted(w.pos_kinds.items())))
    print("status:", {k: v for k, v in sorted(status.items()) if k.startswith(("move", "warp", "maps"))})

    # no drift: every move.step answered exactly once, nothing answered that wasn't asked
    assert sorted(w.answers) == sorted(w.sent), (len(w.answers), len(w.sent))
    assert w.twice == 0 and w.unknown == 0, (w.twice, w.unknown)
    assert set(answer_types) <= {"player.pos", "player.blocked"}
    assert not w.mc.conn.problems  # strict: every message passed its schema
    assert w.counts["error"] == 0
    assert kinds == [("start", "stairs"), ("arrive", "stairs"), ("start", "edge"), ("arrive", "edge"),
                     ("start", "door"), ("arrive", "door"), ("start", "edge"), ("arrive", "edge")], kinds
    assert arrivals == ["REDS_HOUSE_1F", "PALLET_TOWN", "REDS_HOUSE_1F", "PALLET_TOWN"], arrivals
    # both arrivals on the house door walk the player out one cell: the engine's scripted step
    assert w.pos_kinds["script"] == 2, dict(w.pos_kinds)
    warp_entries = [e for e in entered if e.endswith("via=warp")]
    assert warp_entries == ["map=REDS_HOUSE_1F via=warp", "map=PALLET_TOWN via=warp",
                            "map=REDS_HOUSE_1F via=warp", "map=PALLET_TOWN via=warp"], entered
    assert status["moveDropped"] == 0 and status["moveTimeouts"] == 0
    # every step sent while the one before was walking began on its landing poll: no idle frame
    assert w.pipelined > 0 and status["moveChained"] == w.pipelined, (status["moveChained"], w.pipelined)
    assert status["warpStarted"] == 4 and status["warpArrived"] == 4
    assert status["moveAnswered"] == len(w.sent)
    assert not result.timed_out and result.returncode == 0, result.summary()
    assert result.marker("KB_QUIT") is not None and result.marker("KB_FAIL") is None
    assert not result.crash_log_touched
    assert not result.error_spots
