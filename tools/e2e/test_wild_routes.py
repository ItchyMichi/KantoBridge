"""Wild Routes check C6 end to end, on the real engine (docs/specs/WILD_ROUTES_PLAN.md §8): Viridian's clearing and
the zone's route map in the Gen1Recomp test instance, driven by fake_minecraft.

The driver (drivers/kb_wild.lua) starts a new game without the intro, gives the player a starter and warps onto the
clearing's spot (gen1-bridge/data/routes.json's), facing the trees. Then, as `test`:

  0. at boot gen1-bridge logged routes.installed for viridian_forest_zone with its five trainer classes, not a
     routes.skipped
  1. the clearing: steps through the cleared blocks; the last cell's step script warps the player (kind script) onto
     the route map KB_ROUTE_VIRIDIAN_FOREST_ZONE's x1 (1, 0), facing right
  2. the route map's lower row is closed: a step down is refused (player.blocked)
  3. route.give keep:true (a POTION): the engine's give_item box, route.result ok, the bag one POTION up and the ball
     in route.progress; the same ball again: ok false, reason taken; route.give keep:false (a dungeon's item), twice:
     given both times, never recorded
  4. route.battle (class OPP_YOUNGSTER, a line of our own, one member: ROUTE_1's first grass slot from the export
     bundle "Create Kanto world" wrote, read in memory and never printed; skipped before there is one, since a fresh
     export takes minutes on the pod's network disk): the line's box, battle.start kind scripted (a script-started
     trainer, docs/PROTOCOL.md) with class KB_OPP_YOUNGSTER and one member; a `win` -> route.result ok outcome win, the
     win counted in route.progress
  5. a step west onto A: the warp home, onto the clearing's second-to-last cell, facing the town

Every message passes its schema (strict). Data safety (docs/PLAN.md section 0.3): the output shows only types, kinds,
counts, cells and symbols of maps, items and classes -- never species, names or text.
"""

import asyncio
import json
from collections import Counter

import pytest

from fake_minecraft import FakeMinecraft
from run_gen1recomp import REPO, launch, preflight
from story import StoryWalker

TEST_PORT = 27206
ROUTE = "viridian_forest_zone"
ROUTE_MAP = "KB_ROUTE_VIRIDIAN_FOREST_ZONE"
CLASS = "OPP_YOUNGSTER"
LINE = "THESE WOODS ARE MINE"[:18]  # ZoneLines.MAX_LENGTH until C6 says how long a line may be
DELTA = {"up": (0, -1), "down": (0, 1), "left": (-1, 0), "right": (1, 0)}
OPPOSITE = {"up": "down", "down": "up", "left": "right", "right": "left"}


def spot():
    entry = json.loads((REPO / "gen1-bridge" / "data" / "routes.json").read_text(encoding="utf-8"))["routes"][0]
    assert entry["id"] == ROUTE
    return entry["from"]


def way_back(f):
    """Where A's warp lands: the clearing's last block's near side, facing the town (lib/kb_routes.lua plan())."""
    dx, dy = DELTA[f["facing"]]
    bx, by = f["x"] // 2 + dx * f["depth"], f["y"] // 2 + dy * f["depth"]
    near = {"up": (2 * bx, 2 * by + 1), "down": (2 * bx, 2 * by), "left": (2 * bx + 1, 2 * by),
            "right": (2 * bx, 2 * by)}[f["facing"]]
    return near[0], near[1], OPPOSITE[f["facing"]]


class Visitor(StoryWalker):
    """StoryWalker plus the Wild Routes answers: route.result, route.progress and the mirrored bag."""

    def __init__(self, mc):
        super().__init__(mc)
        self.route_results = []  # route.result data (UiWalker's own results is a dict of act.result by re)
        self.progress = []
        self.bag = {}

    def handle(self, m):
        super().handle(m)
        t, d = m["type"], m["data"]
        if t == "route.result":
            self.route_results.append(d)
        elif t == "route.progress":
            self.progress.append(d["routes"])
        elif t == "bag.sync":
            self.bag = dict(d["items"])
        elif t == "snapshot":
            self.bag = dict(d["bag"])
            if "routes" in d:
                self.progress.append(d["routes"])

    def result(self, op, oid=None):
        found = [r for r in self.route_results if r["op"] == op and (oid is None or r.get("id") == oid)]
        return found[-1] if found else None

    async def ask(self, op, data, oid, count_before):
        await self.mc.send(f"route.{op}", data)
        await self.drive(lambda: len([r for r in self.route_results if r["op"] == op and r.get("id") == oid]) > count_before,
                         timeout=60)
        await self.settle()
        return self.result(op, oid)


BUNDLES = REPO / "local" / "minecraft" / "kantobridge" / "export"


def _member(game):
    """One member for the zone trainer: ROUTE_1's first grass slot from the client's export bundle, in memory; None
    when there is no bundle yet."""
    root = BUNDLES / game
    try:
        index = json.loads((root / "index.json").read_text(encoding="utf-8"))
        entry = next(e for e in index["resources"] if e["kind"] == "encounters")
        data = json.loads((root / "encounters" / f"{entry['id']}.json").read_text(encoding="utf-8"))
        slot = data["maps"]["ROUTE_1"]["grass"]["slots"][0]
        return {"species": slot["species"], "level": slot["level"]}
    except (OSError, ValueError, KeyError, IndexError, StopIteration):
        return None


async def _visit(port, f, member):
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=20)
    await mc.hello()
    w = Visitor(mc)
    await w.until(lambda: w.pos is not None, 10, "the snapshot")
    await w.settle()
    facts = {"start": w.pos}

    # 1. through the clearing: the last cell's step script warps onto the route map
    for _ in range(2 * f["depth"]):
        n = await w.step(f["facing"])
        answer = await w.answer(n)
        assert answer["type"] == "player.pos", (answer["type"], answer["data"].get("reason"))
    await w.until(lambda: any(d["phase"] == "arrive" for d in w.warps), 30, "the warp into the route map")
    await w.settle()
    facts["warp_out"] = [(d["phase"], d["kind"]) for d in w.warps]
    facts["in_zone"] = w.pos

    # 2. the lower row is closed
    answer = await w.answer(await w.step("down"))
    facts["down"] = (answer["type"], answer["data"].get("reason"))
    await w.settle()

    # 3. a ball's item, kept; then a dungeon's item, not kept
    potions = w.bag.get("POTION", 0)
    facts["give1"] = await w.ask("give", {"route": ROUTE, "id": "a1.i1", "item": "POTION"}, "a1.i1", 0)
    facts["potions_after_give"] = w.bag.get("POTION", 0) - potions
    facts["give_again"] = await w.ask("give", {"route": ROUTE, "id": "a1.i1", "item": "POTION"}, "a1.i1", 1)
    for count in (0, 1):
        facts[f"give_dungeon{count}"] = await w.ask(
            "give", {"route": ROUTE, "id": "v1.f1.i1", "item": "POTION", "keep": False}, "v1.f1.i1", count)
    facts["potions"] = w.bag.get("POTION", 0) - potions
    facts["taken"] = (w.progress[-1].get(ROUTE, {}).get("taken", []) if w.progress else None)

    # 4. a zone trainer: the line, the battle, a win
    if member is not None:
        starts = len(w.battles)
        await w.mc.send("route.battle", {"route": ROUTE, "id": "a1.t1", "class": CLASS, "party": [member],
                                         "lines": {"before": LINE}})
        await w.drive(lambda: len(w.battles) > starts or w.result("battle", "a1.t1") is not None, timeout=60)
        assert len(w.battles) > starts, ("no battle.start", w.result("battle", "a1.t1"))
        start = w.battles[-1]
        facts["battle"] = (start["kind"], start.get("trainer", {}).get("cls"), len(start.get("team", [])))
        await w.mc.send("battle.result", {"battle": start["battle"], "outcome": "win"})
        await w.drive(lambda: w.result("battle", "a1.t1") is not None, timeout=90)
        await w.settle()
        facts["battle_result"] = w.result("battle", "a1.t1")
        facts["wins"] = w.progress[-1].get(ROUTE, {}).get("wins", {}) if w.progress else None

    # 5. home: west onto A
    warps = len(w.warps)
    await w.step("left")  # x1 (1, 0) to A (0, 0)
    await w.until(lambda: any(d["phase"] == "arrive" for d in w.warps[warps:]), 30, "the warp home")
    await w.settle()
    facts["warp_home"] = [(d["phase"], d["kind"]) for d in w.warps[warps:]]
    facts["home"] = w.pos

    status = await w.status()
    facts["status"] = {k: v for k, v in sorted(status.items()) if k.startswith(("route", "warp"))}
    await w.mc.send("debug.quit", {"code": 0})
    await w.mc.close()
    return w, facts


@pytest.mark.e2e
@pytest.mark.timeout(900)
def test_the_clearing_the_route_map_and_its_acts():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    f = spot()
    env = {"KB_TEST_PORT": str(TEST_PORT), "KB_SPOT_MAP": f["map"], "KB_SPOT_X": str(f["x"]),
           "KB_SPOT_Y": str(f["y"]), "KB_SPOT_FACING": f["facing"]}
    with launch("kb_wild.lua", extra_env=env) as running:
        marker = running.wait_marker("KB_WILD_READY", timeout=180)
        assert marker is not None, running.result.summary()
        member = _member(pf.game or "blue")
        w, facts = asyncio.run(asyncio.wait_for(_visit(TEST_PORT, f, member), 600))
        result = running.wait(timeout=60)

    installed = [line for line in result.lines if "routes.installed" in line or "routes.skipped" in line]
    entered = [line.split(" ", 1)[1] for line in result.lines if line.startswith("KB_MAP_ENTERED")]
    print("routes:", installed)
    print("facts:", facts)
    if member is None:
        print("battle: skipped (no export bundle yet; Create Kanto world writes one)")
    print("map.entered:", entered)
    print("messages received:", dict(sorted(w.counts.items())))

    assert any(f"route={ROUTE}" in line and "routes.installed" in line and "classes=5" in line for line in installed), installed
    assert not any("routes.skipped" in line for line in installed), installed
    assert facts["start"] == (f["map"], f["x"], f["y"], f["facing"]), facts["start"]
    assert facts["warp_out"] == [("start", "script"), ("arrive", "script")], facts["warp_out"]
    assert facts["in_zone"] == (ROUTE_MAP, 1, 0, "right"), facts["in_zone"]
    assert facts["down"][0] == "player.blocked", facts["down"]
    assert facts["give1"] == {"op": "give", "ok": True, "route": ROUTE, "id": "a1.i1"}, facts["give1"]
    assert facts["potions_after_give"] == 1
    assert facts["give_again"]["ok"] is False and facts["give_again"]["reason"] == "taken", facts["give_again"]
    assert facts["give_dungeon0"]["ok"] is True and facts["give_dungeon1"]["ok"] is True
    assert facts["potions"] == 3, facts["potions"]
    assert facts["taken"] == ["a1.i1"], facts["taken"]
    if member is not None:
        assert facts["battle"] == ("scripted", "KB_" + CLASS, 1), facts["battle"]
        assert facts["battle_result"] == {"op": "battle", "ok": True, "route": ROUTE, "id": "a1.t1", "outcome": "win"}
        assert facts["wins"] == {"a1.t1": 1}, facts["wins"]
    bx, by, facing = way_back(f)
    assert facts["warp_home"] == [("start", "script"), ("arrive", "script")], facts["warp_home"]
    assert facts["home"] == (f["map"], bx, by, facing), facts["home"]
    assert f"map={ROUTE_MAP} via=warp" in entered and entered[-1] == f"map={f['map']} via=warp", entered
    assert not w.mc.conn.problems  # strict: every message passed its schema
    assert w.counts["error"] == 0, w.errors
    assert not result.timed_out and result.returncode == 0, result.summary()
    assert result.marker("KB_QUIT") is not None and result.marker("KB_FAIL") is None
    assert result.facts("KB_END").get("potions") == "3" and result.facts("KB_END").get("map") == f["map"]
    assert not result.crash_log_touched
    assert not result.error_spots


# ---- WORLD_SESSIONS_PLAN WS0: a save on the route map, then a reload (the user's bug) ----

WS0_PORT = 27207
WS0_WORLD = "kb-e2e-ws0-world"


class Recorder(Visitor):
    """Visitor plus the sequence the plan's WS0 records: every snapshot, player.pos, warp, player.state and save
    message with its map, cell and in-game flag, in arrival order (symbols, cells and booleans only)."""

    def __init__(self, mc, seq):
        super().__init__(mc)
        self.seq = seq
        self.saves = []
        self.bound = []

    def handle(self, m):
        super().handle(m)
        t, d = m["type"], m["data"]
        if t == "snapshot":
            p = d.get("player") or {}
            self.seq.append(("snapshot", d.get("ingame"), p.get("map"), p.get("x"), p.get("y"), d.get("route")))
        elif t == "player.pos":
            self.seq.append(("pos", d.get("kind"), d["map"], d["x"], d["y"]))
        elif t == "warp":
            self.seq.append(("warp", d.get("phase"), d.get("kind"), d.get("map"), d.get("x"), d.get("y")))
        elif t == "player.state" and (not self.seq or self.seq[-1][:2] != ("state", d.get("mode"))):
            self.seq.append(("state", d.get("mode"), d.get("route")))
        elif t in ("save.begin", "save.loaded"):
            self.seq.append((t, d.get("reason"), d.get("fresh")))
            if t == "save.begin":
                self.saves.append(d)
        elif t == "session.bound":
            self.bound.append(d)
            self.seq.append(("bound", d.get("slot"), d.get("switched")))

    async def bind(self):
        n = len(self.bound)
        await self.mc.send("session.bind", {"world": WS0_WORLD, "multiplayer": False})
        await self.until(lambda: len(self.bound) > n, 30, "session.bound")


async def _ws0_first(port, f, seq):
    """Launch 1: into the zone, the driver's save on the route map, then scene A (drop and reconnect)."""
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=20)
    await mc.hello()
    w = Recorder(mc, seq)
    await w.until(lambda: w.pos is not None, 10, "the snapshot")
    await w.bind()
    await w.settle()
    for _ in range(2 * f["depth"]):
        await w.answer(await w.step(f["facing"]))
    await w.until(lambda: any(d["phase"] == "arrive" for d in w.warps), 30, "the warp into the route map")
    await w.settle()
    await w.until(lambda: w.saves, 60, "save.begin of the driver's save on the route map")
    for s in w.saves:
        await w.mc.send("save.done", {"save": s["save"], "ok": True})
    await w.settle()
    facts = {"saved_at": w.pos}
    # scene A: the client drops and comes back with the engine running
    seq.append(("--", "scene A: reconnect"))
    w.problems_before = await w.reconnect()
    await w.until(lambda: w.seq and w.seq[-1][0] == "snapshot", 15, "the snapshot after the reconnect")
    await w.bind()
    await w.settle()
    facts["a_after"] = w.pos
    facts["a_route"] = next((e[5] for e in reversed(w.seq) if e[0] == "snapshot"), None)
    # WS1 (D9): route.leave walks the hidden player home through the back warp
    warps = len(w.warps)
    leave = await w.mc.send("route.leave", {"zone": ROUTE})
    await w.until(lambda: any(d["phase"] == "arrive" for d in w.warps[warps:]), 30, "the back warp after route.leave")
    await w.settle()
    facts["a_leave"] = w.results.get(leave["seq"])
    facts["a_home"] = w.pos
    status = await w.status()
    facts["a_status"] = {k: v for k, v in sorted(status.items()) if k.startswith(("route", "playthrough", "ingame"))}
    await w.mc.send("debug.quit", {"code": 0})
    await w.mc.close()
    return w, facts


async def _ws0_second(port, f, seq):
    """Launch 2 (scene B): the engine relaunched on the same data home at its title; the bind; the driver's CONTINUE;
    then a step west onto A, which must take the hidden player home if it stands free on the route map."""
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=20)
    await mc.hello()
    w = Recorder(mc, seq)
    await w.until(lambda: w.seq and w.seq[0][0] == "snapshot", 10, "the snapshot at the title")
    await w.bind()
    await w.until(lambda: any(e[0] == "save.loaded" for e in w.seq), 120, "save.loaded after the CONTINUE")
    await w.settle()
    facts = {"b_after": w.pos, "b_route": next((e[2] for e in reversed(w.seq) if e[0] == "state" and e[2]), None)}
    warps = len(w.warps)
    if w.pos and w.pos[0] == ROUTE_MAP:
        await w.step("left")
        try:
            await w.until(lambda: any(d["phase"] == "arrive" for d in w.warps[warps:]), 30, "the warp home")
            await w.settle()
        except AssertionError as e:
            facts["b_home_error"] = str(e)
    facts["b_home"] = w.pos
    status = await w.status()
    facts["b_status"] = {k: v for k, v in sorted(status.items()) if k.startswith(("route", "playthrough", "ingame"))}
    await w.mc.send("debug.quit", {"code": 0})
    await w.mc.close()
    return w, facts


@pytest.mark.e2e
@pytest.mark.timeout(1500)
def test_ws0_a_save_on_the_route_map_then_a_reconnect_and_a_relaunch():
    """WORLD_SESSIONS_PLAN WS0 (1), the engine's half: what the bridge says after a save on the route map, (A) across a
    reconnect with the engine running, (B) across a relaunch and CONTINUE. WS1 (check C1, the engine's part): the route
    state is in the snapshot after A and in player.state after B's CONTINUE; route.leave in A walks the hidden player
    home through the back warp; in B the hidden player is free (a step onto A goes home)."""
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    f = spot()
    env = {"KB_TEST_PORT": str(WS0_PORT), "KB_SPOT_MAP": f["map"], "KB_SPOT_X": str(f["x"]),
           "KB_SPOT_Y": str(f["y"]), "KB_SPOT_FACING": f["facing"], "KB_SCENE": "save", "KB_ROUTE_MAP": ROUTE_MAP}
    seq = []
    slots = {}
    try:
        with launch("kb_wild.lua", extra_env=env) as running:
            assert running.wait_marker("KB_WILD_READY", timeout=180) is not None, running.result.summary()
            line = running.result.marker("KB_SLOT")
            slots = dict(p.split("=", 1) for p in line.split()[1:]) if line else {}
            w1, facts1 = asyncio.run(asyncio.wait_for(_ws0_first(WS0_PORT, f, seq), 600))
            r1 = running.wait(timeout=60)
        saved = r1.marker("KB_SAVED")
        print("launch 1:", saved, r1.marker("KB_END"))
        print("facts 1:", facts1)
        assert saved is not None and "ok=true" in saved, r1.summary()
        env2 = {"KB_TEST_PORT": str(WS0_PORT), "KB_SCENE": "continue", "KB_ROUTE_MAP": ROUTE_MAP,
                "KB_HOME_SLOT": f"slot{slots.get('home')}", "KB_SCRATCH_SLOT": f"slot{slots.get('slot')}"}
        seq.append(("--", "scene B: relaunch"))
        with launch("kb_wild.lua", extra_env=env2) as running:
            assert running.wait_marker("KB_BOOTED", timeout=180) is not None, running.result.summary()
            w2, facts2 = asyncio.run(asyncio.wait_for(_ws0_second(WS0_PORT, f, seq), 600))
            r2 = running.wait(timeout=60)
        entered = [line.split(" ", 1)[1] for line in r2.lines if line.startswith("KB_MAP_ENTERED")]
        print("launch 2:", r2.marker("KB_LOADED"), "|", r2.marker("KB_CONTINUED"), "|", r2.marker("KB_CLEANED"))
        print("map.entered 2:", entered)
        print("facts 2:", facts2)
        slots = {}  # cleaned by the second launch
    finally:
        for e in seq:
            print("seq:", e)
        if slots.get("slot") and slots.get("slot") != slots.get("home"):
            with launch("kb_wild.lua", extra_env={"KB_TEST_PORT": str(WS0_PORT), "KB_SCENE": "cleanup",
                                                  "KB_HOME_SLOT": f"slot{slots['home']}",
                                                  "KB_SCRATCH_SLOT": f"slot{slots['slot']}"}) as running:
                print("cleanup:", running.wait(timeout=120).marker("KB_CLEANED"))
    assert facts1["saved_at"][0] == ROUTE_MAP, facts1
    assert r2.marker("KB_CONTINUED") is not None, r2.summary()
    # WS1, C1 (engine side)
    zone = {"zone": ROUTE, "map": ROUTE_MAP}
    assert facts1["a_after"][0] == ROUTE_MAP and facts1["a_route"] == zone, facts1
    assert facts1["a_leave"] == {"kind": "route.leave", "ok": True}, facts1
    bx, by, facing = way_back(f)
    assert facts1["a_home"] == (f["map"], bx, by, facing), facts1
    assert facts2["b_after"][0] == ROUTE_MAP and facts2["b_route"] == zone, facts2
    assert facts2["b_home"][0] == f["map"], facts2
    assert "remapped=0" in r2.marker("KB_CONTINUED")
