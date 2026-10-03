"""M8 end to end, on the real engine: field moves from Minecraft (act.field), the story's live map
blocks and forced movement in the Gen1Recomp test instance, driven by fake_minecraft as role `test`
(docs/PLAN.md M8 items 3, 4, 5 and 11).

The driver (drivers/kb_field.lua) starts a new game without the intro, gives the player one level-100
party member that knows the scene's field move, the move's badge and REPEL steps (no wild encounter),
beats the scene map's trainers, and warps into the scene (found with the engine's own data), chosen
by KB_SCENE:

  cut       facing a cuttable tree: act.field cut -> act.result ok kind cut; the text read; the
            story.blocks of the tree's block (the cut swap); a step through where it stood and
            back, turned away from it (the driver's cue); the driver warps the player away and
            back (two warps), the tree has grown back
            (the driver's KB_BACK regrown=true) and a step into it is refused (player.blocked tile);
            no story.blocks for it on the way back (the map data again)
  surf      facing water: act.field surf -> ok kind surf; the mount's scripted step onto the water;
            player.state surf; a step on the water (player.pos kind surf); back onto land, surf off
  spin      one step from a spinner arrow: the step (kind walk), then the slide (player.pos kind
            spin, 48 frames a cell) in the forced mode, then idle
  elevator  above one of an elevator car's warps: a step onto it (a push against the edge if it
            needs one): warp start and arrive kind elevator
  strength  facing a boulder: act.field strength -> ok kind strength; the texts; a step into the
            boulder arms the push (player.blocked entity), the second pushes it (player.pos kind
            push, 0 frames; npc.move kind push for the boulder)

In every scene: every message passes its schema (strict), no error, no battle at all, and
debug.status shows the boot copy of the map data was taken and no unknown block change happened.
Data safety (docs/PLAN.md section 0.3): only types, kinds, counts, frames, booleans and map symbols
are printed; text is never looked at.
"""

import asyncio
import time
from collections import Counter

import pytest

from fake_minecraft import FakeMinecraft
from run_gen1recomp import launch, preflight
from story import StoryWalker

PORTS = {"cut": 27187, "surf": 27186, "spin": 27185, "elevator": 27184, "strength": 27183}
DELTA = {"up": (0, -1), "down": (0, 1), "left": (-1, 0), "right": (1, 0)}
OPPOSITE = {"up": "down", "down": "up", "left": "right", "right": "left"}


class FieldWalker(StoryWalker):
    """StoryWalker plus what M8 sends: story.*, the player's state flags and every player.pos."""

    def __init__(self, mc):
        super().__init__(mc)
        self.story = []           # (type, data)
        self.state = {}
        self.positions = []       # player.pos data, in order
        self.npc_move_list = []   # npc.move data

    def handle(self, m):
        super().handle(m)
        t, d = m["type"], m["data"]
        if t == "battle.start":
            # the driver's REPEL should keep wild Pokemon away; if one still comes (a surfing roll
            # held for Cobblemon, M6), run from it so the scene goes on
            asyncio.get_running_loop().create_task(
                self.mc.send("battle.result", {"battle": d["battle"], "outcome": "fled"}))
        if t.startswith("story."):
            self.story.append((t, d))
        elif t == "player.state":
            self.state = d
        elif t == "snapshot":
            self.state = d["state"]
        elif t == "player.pos":
            self.positions.append(d)
        elif t == "npc.move":
            self.npc_move_list.append(d)

    def blocks(self, map_id):
        return [c for t, d in self.story if t == "story.blocks" and d["map"] == map_id for c in d["changes"]]

    def arrivals(self):
        return [w for w in self.warps if w["phase"] == "arrive"]

    async def field(self, action, **data):
        data["action"] = action
        return await self.act("act.field", data, timeout=20)

    async def quiet(self, timeout=60, grace=1.0):
        """Plays the texts a field move shows (they open right after its act.result, so the first
        `grace` seconds never count as done), then waits for the idle mode."""
        start = time.monotonic()
        await self.drive(lambda: time.monotonic() - start >= grace and self.mode == "idle" and not self.open_uis(),
                         timeout=timeout)
        await self.settle(timeout=timeout)


def scene_facts(marker):
    return dict(part.split("=", 1) for part in marker.split()[1:] if "=" in part)


LAST = {}  # the walker of the flow under way, for the failure report


async def _connect(port):
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=20)
    await mc.hello()
    w = FieldWalker(mc)
    LAST["w"] = w
    await w.until(lambda: w.pos is not None, 20, "the snapshot")
    await w.settle()
    return w


async def _finish(w):
    status = await w.status()
    await w.mc.send("debug.quit", {"code": 0})
    await asyncio.sleep(0.5)
    await w.mc.close()
    return status


async def _cut(port, scene):
    w = await _connect(port)
    report = {}
    facing = scene["facing"]
    tx, ty, bx, by = int(scene["tx"]), int(scene["ty"]), int(scene["bx"]), int(scene["by"])
    report["result"] = await w.field("cut")
    await w.quiet()
    cut = w.blocks(scene["map"])
    report["cut_changes"] = len(cut)
    report["cut_block_reported"] = any(c["bx"] == bx and c["by"] == by for c in cut)
    # through where the tree stood, and back onto the scene cell, turned away from it (the driver's
    # cue to warp the player away and back)
    first = await w.answer(await w.step(facing))
    report["through"] = first["type"] == "player.pos" and first["data"]["kind"] == "walk" and \
        (first["data"]["x"], first["data"]["y"]) == (tx, ty)
    await w.idle()
    if report["through"]:
        await w.answer(await w.step(OPPOSITE[facing]))
    else:
        await w.mc.send("move.face", {"dir": OPPOSITE[facing]})
    await w.idle()
    # the driver: away to ROUTE_1, then back onto the scene cell
    await w.until(lambda: len(w.arrivals()) >= 2, 120, "two warp arrivals (away and back)")
    await w.settle()
    report["arrivals"] = [(a["kind"], a["to"]["map"]) for a in w.arrivals()]
    report["changes_after_back"] = len([c for c in w.blocks(scene["map"])]) - len(cut)
    regrown = await w.answer(await w.step(facing))
    report["regrown_refusal"] = (regrown["type"], regrown["data"].get("reason"))
    await w.idle()
    report["status"] = await _finish(w)
    return w, report


async def _surf(port, scene):
    w = await _connect(port)
    report = {}
    facing = scene["facing"]
    report["result"] = await w.field("surf")
    await w.quiet()
    report["mount"] = [(p["kind"], p["frames"]) for p in w.positions if p["kind"] != "sync"]
    report["surf_after_mount"] = w.state.get("surf")
    on = await w.answer(await w.step(facing))
    report["on_water"] = (on["type"], on["data"].get("kind"), on["data"].get("frames"))
    await w.idle()
    for _ in range(2):
        await w.answer(await w.step(OPPOSITE[facing]))
        await w.idle()
    report["surf_after_landing"] = w.state.get("surf")
    report["status"] = await _finish(w)
    return w, report


async def _spin(port, scene):
    w = await _connect(port)
    report = {}
    onto = await w.answer(await w.step(scene["facing"]))
    report["onto"] = (onto["type"], onto["data"].get("kind"), onto["data"].get("frames"))
    await w.until(lambda: any(p["kind"] == "spin" for p in w.positions), 30, "a spin step")
    await w.settle()
    spins = [p for p in w.positions if p["kind"] == "spin"]
    report["spin_steps"] = len(spins)
    report["spin_frames"] = sorted({p["frames"] for p in spins})
    report["forced_seen"] = "forced" in w.modes
    report["status"] = await _finish(w)
    return w, report


async def _elevator(port, scene):
    w = await _connect(port)
    report = {}
    facing = scene["facing"]
    await w.answer(await w.step(facing))
    try:
        await w.until(lambda: len(w.arrivals()) >= 1, 10, "a warp")
        report["needed_push"] = False
    except AssertionError:
        report["needed_push"] = True
        await w.answer(await w.step(facing, push=True))
        await w.until(lambda: len(w.arrivals()) >= 1, 30, "a warp after the push")
    await w.settle()
    report["warps"] = [(x["phase"], x["kind"]) for x in w.warps]
    report["status"] = await _finish(w)
    return w, report


async def _strength(port, scene):
    w = await _connect(port)
    report = {}
    facing = scene["facing"]
    report["result"] = await w.field("strength")
    await w.quiet()
    report["again"] = await w.field("strength")
    first = await w.answer(await w.step(facing))
    report["first"] = (first["type"], first["data"].get("reason"))
    second = await w.answer(await w.step(facing))
    report["second"] = (second["type"], second["data"].get("kind"), second["data"].get("frames"))
    await w.settle()
    pushes = [d for d in w.npc_move_list if d["kind"] == "push"]
    report["boulder_pushes"] = [(d["id"] == scene["npc"], d["frames"] > 0) for d in pushes]
    report["status"] = await _finish(w)
    return w, report


FLOWS = {"cut": _cut, "surf": _surf, "spin": _spin, "elevator": _elevator, "strength": _strength}


@pytest.mark.e2e
@pytest.mark.timeout(900)
@pytest.mark.parametrize("scene_name", ["cut", "surf", "spin", "elevator", "strength"])
def test_field_scene(scene_name):
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    port = PORTS[scene_name]
    with launch("kb_field.lua", extra_env={"KB_TEST_PORT": str(port), "KB_SCENE": scene_name}) as running:
        marker = running.wait_marker("KB_SCENE", timeout=180)
        assert marker is not None, running.result.summary()
        scene = scene_facts(marker)
        assert scene["scene"] == scene_name and scene["port"] == str(port)
        try:
            w, report = asyncio.run(asyncio.wait_for(FLOWS[scene_name](port, scene), 600))
        except BaseException:
            w = LAST.get("w")
            if w is not None:  # symbols and counts only
                print("failed at: mode", w.mode, "state", w.state, "open uis", len(w.open_uis()))
                print("last positions:", [(p["kind"], p["frames"]) for p in w.positions[-6:]])
                print("received:", dict(sorted(w.counts.items())))
            raise
        result = running.wait(timeout=60)

    status = report.pop("status")
    print("scene:", scene_name, "map:", scene["map"], "trainers beaten:", scene.get("beaten"))
    print("report:", report)
    print("story:", dict(Counter(t for t, d in w.story)))
    print("pos kinds:", dict(Counter(p["kind"] for p in w.positions)))
    print("warps:", [(x["phase"], x["kind"]) for x in w.warps])
    print("status:", {k: status.get(k) for k in ("storyPristine", "storyUnknownBlocks", "storyBlockMsgs",
                                                  "storyMapLoads", "storySites", "fieldOk", "fieldFailed")})
    print("driver:", [line for line in result.lines if line.startswith(("KB_CUT", "KB_AWAY", "KB_BACK", "KB_END",
                                                                         "KB_BATTLE_PUSHED", "KB_QUIT"))])

    assert not w.mc.conn.problems  # strict: every message passed its schema
    assert not w.errors, w.errors
    assert len(w.battles) == 0, "REPEL and the level-100 lead keep wild Pokemon away"
    assert status["storyPristine"] is True, "the map data was copied at boot"
    assert status["storyUnknownBlocks"] == 0, "every block change was a known one"
    if scene_name == "cut":
        assert status["storySites"] > 0, "the runtime check's sites were built for the cut's change"
        assert report["result"] == {"ok": True, "kind": "cut"}
        assert report["cut_block_reported"] and report["cut_changes"] >= 1
        assert report["arrivals"][0][1] == "ROUTE_1" and report["arrivals"][1][1] == scene["map"]
        assert report["changes_after_back"] == 0, "the regrown tree is the map data: nothing to report"
        assert report["regrown_refusal"] == ("player.blocked", "tile")
        assert result.marker("KB_BACK").endswith("regrown=true")
    elif scene_name == "surf":
        assert report["result"] == {"ok": True, "kind": "surf"}
        assert report["mount"] == [("script", 16)], "the mount's one scripted step onto the water"
        assert report["surf_after_mount"] is True
        assert report["on_water"] == ("player.pos", "surf", 16)
        assert report["surf_after_landing"] is False
    elif scene_name == "spin":
        assert report["onto"] == ("player.pos", "walk", 16)
        assert report["spin_steps"] >= int(scene["steps"]) >= 1
        assert report["spin_frames"] == [48], "16 / 2 * 6 frames a cell (Player.lua:131-133)"
        assert report["forced_seen"]
    elif scene_name == "elevator":
        assert report["warps"][:2] == [("start", "elevator"), ("arrive", "elevator")]
    elif scene_name == "strength":
        assert report["result"] == {"ok": True, "kind": "strength"}
        assert report["again"] == {"ok": False, "reason": "already"}
        assert report["first"] == ("player.blocked", "entity")
        assert report["second"] == ("player.pos", "push", 0)
        assert report["boulder_pushes"] == [(True, True)]
    assert result.marker("KB_BATTLE_PUSHED") is None
    assert not result.timed_out and result.returncode == 0, result.summary()
    assert result.marker("KB_QUIT") is not None and result.marker("KB_FAIL") is None
    assert not result.crash_log_touched
    assert not result.error_spots
