"""M6 end to end, on the real engine: Route 1 with gen1-bridge's wild-encounter suppression, the
mirrored bag, dex.sync and party.sync in the Gen1Recomp test instance, driven by fake_minecraft
(docs/PLAN.md M6 [T]).

The driver (drivers/kb_route1.lua) starts a new game without the intro, gives the player a starter
and a bag through the engine's own APIs (two battle items, a key item, a field item) and warps onto
ROUTE_1 through mod.world. The test fetches ROUTE_1's cell flags from the real export as a `builder`
(kept in memory only), then connects as `test`:

  1. the bag: right after the snapshot a bag.sync carries only the battle items (POKE_BALL x5,
     POTION x3: never the key item or the field item); bag.use POKE_BALL x2 comes back as a bag.sync
     with 3; bag.use of the key item is refused (error bag.not_mirrored)
  2. 200 grass steps: routes over the export's tall-grass cells (walkable, no ledge, no warp), back
     and forth between two neighbouring grass cells, planning again around Route 1's NPCs. No native
     battle ever starts: no battle screen on the stack (the driver's KB_BATTLE_PUSHED never
     printed), the classifier never shows a battle (no screen or busy mode during the walk, no
     mirror UI for BattleState), no battle.start or battle.native; debug.status counts every grass
     roll as suppressed (encounterGrass equals the grass steps that landed) and no battle
  3. dex.sync with made-up seen/owned numbers: debug.status's save.pokedex counts take them in (a
     union, owned implying seen)
  4. party.sync with two made-up members: the shadow party has exactly those two, both with uids

Every message passes its schema (strict). Data safety (docs/PLAN.md section 0.3): routes are
computed in memory from the export and never printed; the output shows only types, kinds, counts,
ranges and engine symbols (item and map ids), never species, names or text.
"""

import asyncio
import time
from collections import Counter, deque

import pytest

from fake_minecraft import FakeMinecraft
from run_gen1recomp import launch, preflight
from story import StoryWalker, export_grids
from test_walk import DELTA, WARP

TEST_PORT = 27191
MAP = "ROUTE_1"
GRASS_STEPS = 200
GRASS, WATER, LEDGE = 8, 2, 8192
REVERSE = {"up": "down", "down": "up", "left": "right", "right": "left"}
BATTLE_ITEMS = {
    "POKE_BALL", "GREAT_BALL", "ULTRA_BALL", "MASTER_BALL", "SAFARI_BALL", "POTION", "SUPER_POTION",
    "HYPER_POTION", "MAX_POTION", "FULL_RESTORE", "REVIVE", "MAX_REVIVE", "ANTIDOTE", "BURN_HEAL",
    "ICE_HEAL", "AWAKENING", "PARLYZ_HEAL", "FULL_HEAL", "ETHER", "MAX_ETHER", "ELIXER", "MAX_ELIXER",
    "X_ATTACK", "X_ACCURACY", "X_SPEED", "X_DEFEND", "X_SPECIAL", "DIRE_HIT", "GUARD_SPEC",
}
# made up: two uids, Gen 1 dex numbers and Gen 1 move numbers (no game data)
PARTY = [
    {"uid": "3a4b5c6d-7e8f-4a0b-9c1d-2e3f4a5b6c7d", "dex": 16, "level": 4, "moves": [33, 28], "hp": 17,
     "maxhp": 17},
    {"uid": "4b5c6d7e-8f9a-4b1c-8d2e-3f4a5b6c7d8e", "dex": 19, "level": 3, "moves": [33, 39], "hp": 14,
     "maxhp": 15},
]
DEX_SEEN, DEX_OWNED = [10, 13, 16, 19], [16, 19]


class RouteWalker(StoryWalker):
    """StoryWalker plus what M6 sends: bag.sync, battle.native, and the modes seen meanwhile."""

    def __init__(self, mc):
        super().__init__(mc)
        self.bag_syncs = []
        self.natives = []
        self.battle_uis = 0
        self.walk_modes = Counter()
        self.walking = False

    def handle(self, m):
        super().handle(m)
        t, d = m["type"], m["data"]
        if t == "bag.sync":
            self.bag_syncs.append(d["items"])
        elif t == "battle.native":
            self.natives.append((d["kind"], d["phase"]))
        elif t == "ui.open" and d["kind"] == "mirror" and d.get("screen") == "BattleState":
            self.battle_uis += 1
        elif t == "player.state" and self.walking:
            self.walk_modes[d["mode"]] += 1


class Grid:
    def __init__(self, grid):
        self.g = grid

    def grass(self, x, y):
        f = self.g.flags(x, y)
        return f is not None and bool(f & 1) and bool(f & GRASS) and not f & (LEDGE | WARP | WATER)

    def walkable(self, x, y):
        f = self.g.flags(x, y)
        return f is not None and bool(f & 1) and not f & (LEDGE | WARP)


def bfs(grid, start, goals, blocked):
    """Shortest 4-neighbour path over walkable non-warp, non-ledge cells; directions or None."""
    goals = set(goals) - set(blocked)
    if start in goals:
        return []
    seen = {start: None}
    queue = deque([start])
    while queue:
        cur = queue.popleft()
        for d, (dx, dy) in DELTA.items():
            nxt = (cur[0] + dx, cur[1] + dy)
            if nxt in seen or nxt in blocked or not grid.walkable(*nxt):
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


def grass_pairs(grid):
    """(cell, direction) for every grass cell with a grass neighbour."""
    out = []
    for y in range(grid.g.h):
        for x in range(grid.g.w):
            if grid.grass(x, y):
                for d, (dx, dy) in DELTA.items():
                    if grid.grass(x + dx, y + dy):
                        out.append(((x, y), d))
    return out


async def walk_grass(w, grid, count):
    """Walks until `count` steps landed on grass cells, back and forth between two neighbouring grass
    cells, planning again (another pair) whenever an NPC is in the way. Returns the grass steps landed
    (approach included) and how many plans it took."""
    pairs = grass_pairs(grid)
    assert pairs, "ROUTE_1's export has no neighbouring grass cells"
    landed, plans = 0, 0
    deadline = time.monotonic() + 600
    while landed < count:
        assert time.monotonic() < deadline, "the grass walk took too long"
        plans += 1
        assert plans <= 40, "too many refusals on the grass"
        here = (w.pos[1], w.pos[2])
        npcs = set(w.npcs)
        far = [(a, d) for a, d in pairs
               if min((abs(a[0] - n[0]) + abs(a[1] - n[1]) for n in npcs), default=99) >= 4]
        choices = far or pairs
        # the nearest pair whose cells are free
        best = None
        for a, d in choices:
            b = (a[0] + DELTA[d][0], a[1] + DELTA[d][1])
            if a in npcs or b in npcs:
                continue
            path = bfs(grid, here, [a], npcs)
            if path is not None and (best is None or len(path) < len(best[0])):
                best = (path, d)
        assert best is not None, "no reachable grass pair"
        path, d = best
        left = count - landed
        dirs = path + [d, REVERSE[d]] * ((left + 1) // 2)
        sent_from = w.n
        ok = await w.follow(dirs)
        await w.idle()
        for n in range(sent_from + 1, w.n + 1):
            m = w.answers.get(n)
            if m and m["type"] == "player.pos" and m["data"]["kind"] == "walk":
                if grid.grass(m["data"]["x"], m["data"]["y"]):
                    landed += 1
        if not ok:
            await asyncio.sleep(0.8)  # a wandering NPC: let it move, look again
            await w.resync()
    return landed, plans


async def _route1(port):
    grids = await export_grids(port, (MAP,))
    grid = Grid(grids[MAP])
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=20)
    await mc.hello()
    w = RouteWalker(mc)
    await w.until(lambda: w.pos is not None and w.npc_sets and w.bag_syncs, 15,
                  "the snapshot, npc.set and bag.sync")
    assert w.pos[0] == MAP, w.pos[0]
    await w.idle()
    report = {}

    # 1. the mirrored bag
    report["bag_first"] = dict(w.bag_syncs[0])
    await mc.send("bag.use", {"item": "POKE_BALL", "qty": 2})
    await w.until(lambda: len(w.bag_syncs) >= 2, 10, "the bag.sync after a bag.use")
    report["bag_after_use"] = dict(w.bag_syncs[1])
    refused = await mc.send("bag.use", {"item": "TOWN_MAP", "qty": 1})
    await w.until(lambda: any(re == refused["seq"] for _, re in w.errors), 10, "the refusal")
    report["bag_refusal"] = [code for code, re in w.errors if re == refused["seq"]]
    before = await w.status()
    report["before"] = {k: before[k] for k in ("partySize", "dexSeen", "dexOwned", "encounterGrass",
                                               "battleStarted", "bagUses", "bagRefused")}

    # 2. two hundred grass steps
    w.walking = True
    t0 = time.monotonic()
    landed, plans = await walk_grass(w, grid, GRASS_STEPS)
    w.walking = False
    report["walk"] = {"grass_steps": landed, "plans": plans, "seconds": round(time.monotonic() - t0, 1),
                      "steps_sent": w.n, "answered": len(w.answers)}
    walked = await w.status()
    report["walked"] = {k: walked.get(k) for k in (
        "encounterGrass", "encounterIndoor", "encounterWater", "encounterNative", "encounterSafari",
        "encounterGhost", "encounterOther", "battleStarted", "battleNative", "battlePending", "battleHeld",
        "mode", "moveDropped")}

    # 3. dex.sync
    await mc.send("dex.sync", {"seen": DEX_SEEN, "owned": DEX_OWNED})
    await asyncio.sleep(0.5)
    dexed = await w.status()
    report["dex"] = {k: dexed[k] for k in ("dexSeen", "dexOwned", "partyDexApplied", "partyDexAddedSeen",
                                           "partyDexAddedOwned", "partyDexUnknown")}

    # 4. party.sync
    await mc.send("party.sync", {"party": PARTY, "box": {"count": 0}})
    await asyncio.sleep(0.5)
    synced = await w.status()
    report["party"] = {k: synced[k] for k in ("partySize", "partyKnown", "partyApplied", "partyCreated")}

    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return w, report, before


@pytest.mark.e2e
@pytest.mark.timeout(1200)
def test_route1_grass_steps_bag_dex_and_party():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    with launch("kb_route1.lua", extra_env={"KB_TEST_PORT": str(TEST_PORT)}) as running:
        marker = running.wait_marker("KB_ROUTE_READY", timeout=180)
        assert marker is not None, running.result.summary()
        assert f"port={TEST_PORT}" in marker and f"map={MAP}" in marker
        w, report, before = asyncio.run(asyncio.wait_for(_route1(TEST_PORT), 900))
        result = running.wait(timeout=60)

    ready = result.facts("KB_ROUTE_READY")
    print("ready:", {k: ready[k] for k in ("map", "party", "bag_battle", "bag_other")})
    print("report:", report)
    print("modes while walking:", dict(w.walk_modes))
    print("messages received:", dict(sorted(w.counts.items())))
    print("driver:", [line for line in result.lines if line.startswith(("KB_END", "KB_BLACKOUT",
                                                                         "KB_BATTLE_PUSHED", "KB_QUIT"))])

    assert not w.mc.conn.problems  # strict: every message passed its schema
    assert ready["party"] == "1", "the driver's starter"
    # 1. the bag: only battle items, then a use, then a refusal
    assert set(report["bag_first"]) <= BATTLE_ITEMS
    assert report["bag_first"] == {"POKE_BALL": 5, "POTION": 3}, "never the key item or the field item"
    assert report["bag_after_use"] == {"POKE_BALL": 3, "POTION": 3}
    assert report["bag_refusal"] == ["bag.not_mirrored"]
    assert [code for code, _ in w.errors] == ["bag.not_mirrored"], w.errors
    # 2. no native battle on 200 grass steps: every roll suppressed, nothing started
    walk, walked = report["walk"], report["walked"]
    assert walk["grass_steps"] >= GRASS_STEPS
    assert walked["encounterGrass"] - before["encounterGrass"] == walk["grass_steps"], (walked, walk)
    assert walked["encounterNative"] == 0 and walked["battleStarted"] == 0 and walked["battleNative"] == 0
    assert walked["battlePending"] is False and walked["battleHeld"] is False and walked["mode"] == "idle"
    assert not w.battles and not w.natives and w.battle_uis == 0
    assert set(w.walk_modes) <= {"idle"}, dict(w.walk_modes)
    assert result.marker("KB_BATTLE_PUSHED") is None, "no native battle screen"
    assert result.marker("KB_BLACKOUT") is None
    # 3. the Pokedex: a union, owned implying seen
    dex = report["dex"]
    assert dex["partyDexApplied"] == 1 and dex["partyDexUnknown"] == 0
    assert dex["dexSeen"] == before["dexSeen"] + dex["partyDexAddedSeen"]
    assert dex["dexOwned"] == before["dexOwned"] + dex["partyDexAddedOwned"]
    assert dex["dexSeen"] >= len(DEX_SEEN) and dex["dexOwned"] >= len(DEX_OWNED)
    # 4. the shadow party is Cobblemon's two
    assert report["party"]["partySize"] == 2 and report["party"]["partyKnown"] == 2
    assert not result.timed_out and result.returncode == 0, result.summary()
    assert result.marker("KB_QUIT") is not None and result.marker("KB_FAIL") is None
    assert not result.crash_log_touched
    assert not result.error_spots
