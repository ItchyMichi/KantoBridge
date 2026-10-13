"""M10 end to end, on the real engine: gen1-bridge's own cost per logic step over a busy scenario, and
the mirror's frame rate (docs/PLAN.md M10 "gen1-bridge per-step budget under 1 ms on average", "mirror
at most 30 fps"). The driver is drivers/kb_perf.lua.

The test fetches the house's and Pallet Town's cell flags as role `builder` (kept in memory only),
then connects as role `test` and subscribes to the mirror at 30 fps for the whole scenario (the driver
starts gen1-bridge's cost counts over right then):

  1. walks down the stairs, out through the exit mat into PALLET_TOWN
  2. walks to several places around the town (its NPCs wander meanwhile)
  3. reads a sign there (act.interact, a text box read to its close)
  4. opens the start menu and closes it (a menu)
  5. opens the trainer card (a mirror screen) and closes it

Then debug.status: perfAvgUs must stay under 1000 (gen1-bridge's own time per logic step: its step and
every hook and event handler of its own until the next step); the mirror's own count of frames in any
second (mirrorPeakFps) at most 30, and the frames the client received at most 30 a second on average.
The draw-phase cost (the mirror's capture, per drawn frame) is reported beside it. Output: types,
kinds, counts, microseconds and map symbols only; routes and frames stay in memory.
"""

import asyncio
import random
import time

import pytest

from fake_minecraft import FakeMinecraft
from run_gen1recomp import EXPORT_EXTRA, launch, preflight
from test_ui import ENGINE_ROWS, UiWalker
from test_walk import DOOR, PALLET_MIN_ROW, WARP_TILE, _export_maps

TEST_PORT = 27204
SIGN = 4096  # per-cell flag bit 12 (docs/PROTOCOL.md "Export resources")
BUDGET_US = 1000


class PerfWalker(UiWalker):
    def __init__(self, mc):
        super().__init__(mc)
        self.frame_times = []

    def handle(self, m):
        super().handle(m)
        if m["type"] == "mirror.frame":
            self.frame_times.append(time.monotonic())


async def _scenario(port, running):
    grids = await _export_maps(port)
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=20)
    await mc.hello()
    w = PerfWalker(mc)
    await w.until(lambda: w.pos is not None, 10, "the snapshot")
    assert w.pos[0] == "REDS_HOUSE_2F", w.pos[0]
    await w.idle()
    report = {}

    await mc.send("mirror.sub", {"fps": 30})
    deadline = time.monotonic() + 20
    while running.result.marker("KB_PERF_RESET") is None:
        assert time.monotonic() < deadline, "the driver never reset the counts"
        await w.pump(0.1)
    started = time.monotonic()
    first_frame = len(w.frame_times)

    # 1. down the stairs and out through the exit mat
    up = grids["REDS_HOUSE_2F"]
    stairs = [(x, y) for y in range(up.h) for x in range(up.w)
              if up.warp(x, y) and up.flags(x, y) & WARP_TILE and not up.flags(x, y) & DOOR]
    report["steps"] = await w.walk_to(up, stairs)
    await w.until(lambda: len(w.warps) >= 2, 20, "the stairs warp")
    await w.idle()
    down = grids["REDS_HOUSE_1F"]
    await w.resync()
    mats = [(x, y) for y in range(down.h) for x in range(down.w)
            if down.warp(x, y) and not down.flags(x, y) & (DOOR | WARP_TILE) and y == down.h - 1]
    above = [(x, y - 1) for x, y in mats if down.walkable(x, y - 1) and not down.warp(x, y - 1)]
    report["steps"] += await w.walk_to(down, above)
    await w.answer(await w.step("down"))
    await w.until(lambda: w.pos[0] == "PALLET_TOWN", 20, "PALLET_TOWN")
    await w.idle()

    # 2. around the town: several places, its NPCs wandering
    town = grids["PALLET_TOWN"]
    await w.resync()
    free = [(x, y) for y in range(PALLET_MIN_ROW, town.h) for x in range(town.w)
            if town.walkable(x, y) and not town.warp(x, y)]
    rng = random.Random(10)
    for _ in range(4):
        goal = rng.choice(free)
        try:
            report["steps"] += await w.walk_to(town, [goal], min_row=PALLET_MIN_ROW)
        except AssertionError:
            pass  # an unreachable pick (a fenced corner): walking there was busy enough
    report["town_steps"] = report["steps"]

    # 3. a sign: the cell below it, facing up
    signs = [(x, y) for y in range(town.h) for x in range(town.w)
             if town.flags(x, y) & SIGN and town.walkable(x, y + 1) and not town.warp(x, y + 1)
             and y + 1 >= PALLET_MIN_ROW]
    assert signs, "no sign to read in PALLET_TOWN"
    # The nearest sign first; one whose cell an idling NPC keeps busy (the M10 gate saw it) gives
    # way to the next: any sign makes the scenario busy enough.
    signs.sort(key=lambda c: abs(c[0] - w.pos[1]) + abs(c[1] - w.pos[2]))
    for sx, sy in signs:
        try:
            report["steps"] += await w.walk_to(town, [(sx, sy + 1)], min_row=PALLET_MIN_ROW)
            break
        except AssertionError:
            continue
    else:
        raise AssertionError("no sign in PALLET_TOWN could be reached")
    last = max(w.uis, default=0)
    result = await w.act("act.interact", {"map": "PALLET_TOWN", "x": sx, "y": sy})
    report["sign"] = result
    box = await w.opened("text", last)
    report["sign_advances"] = await w.read_text(box)
    await w.idle()

    # 4. the start menu
    menu = await w.start_menu()
    await mc.send("ui.back", {"ui": menu})
    await w.closed(menu)
    await w.idle()

    # 5. the trainer card on the mirror
    menu = await w.start_menu()
    labels = [item["label"] for item in w.uis[menu]["data"]["items"]]
    own = [i for i, label in enumerate(labels) if label not in ENGINE_ROWS]
    assert len(own) == 1, "the player's own row isn't the one unknown start menu row"
    await mc.send("ui.pick", {"ui": menu, "index": own[0]})
    card = await w.opened("mirror", menu)
    await asyncio.sleep(2.0)
    await w.pump(0.1)
    result = await w.act("act.button", {"button": "b", "how": "tap"})
    await w.closed(card)
    again = await w.opened("menu", card, screen="StartMenu")
    await mc.send("ui.back", {"ui": again})
    await w.closed(again)
    await w.idle()

    elapsed = time.monotonic() - started
    status = (await mc.request("debug.status", reply_type="debug.status", timeout=10))["data"]
    frames = w.frame_times[first_frame:]
    report["client_frames"] = len(frames)
    report["client_fps"] = round(len(frames) / elapsed, 2)
    # the most frames the client got in any 5 seconds (delivery can bunch a pair; over five seconds
    # it averages out)
    report["client_max_5s"] = max((sum(1 for t in frames if s <= t < s + 5) for s in frames), default=0)
    report["elapsed_s"] = round(elapsed, 1)
    report["errors"] = [m["data"]["code"] for m in mc.received if m["type"] == "error"]
    report["problems"] = len(mc.conn.problems)
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return w, report, status


@pytest.mark.e2e
@pytest.mark.timeout(600 + EXPORT_EXTRA)
def test_the_per_step_budget_and_the_mirror_rate_over_a_busy_scenario():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    with launch("kb_perf.lua", extra_env={"KB_TEST_PORT": str(TEST_PORT)}) as running:
        marker = running.wait_marker("KB_OVERWORLD", timeout=150)
        assert marker is not None, running.result.summary()
        w, report, st = asyncio.run(asyncio.wait_for(_scenario(TEST_PORT, running), 480 + EXPORT_EXTRA))
        result = running.wait(timeout=60)

    perf = {k: st[k] for k in ("perfSteps", "perfAvgUs", "perfMaxUs", "perfOver1ms", "perfTotalUs", "perfFrames",
                               "perfDrawAvgUs", "perfDrawMaxUs")}
    mirror = {k: st[k] for k in ("mirrorFrames", "mirrorCaptures", "mirrorSkipped", "mirrorPeakFps", "mirrorCapped",
                                 "mirrorAvgUs", "mirrorMaxUs", "mirrorEncodeAvgUs")}
    print("report:", report)
    print("perf:", perf)
    print("mirror:", mirror)
    print("npc.set (map, count):", w.npc_sets)
    print("messages received:", dict(sorted(w.counts.items())))
    print("driver:", [line for line in result.lines if line.startswith(("KB_PERF", "KB_FRAMES", "KB_QUIT"))])

    assert not w.mc.conn.problems and report["errors"] == [] and report["problems"] == 0
    assert report["sign"] == {"ok": True, "kind": "sign"} and report["sign_advances"] >= 1
    assert report["town_steps"] >= 20, "walked around"
    assert any(m == "PALLET_TOWN" and n >= 1 for m, n in w.npc_sets), "a town with NPCs"
    # the budget: gen1-bridge's own time per logic step, on average
    assert perf["perfSteps"] >= 600, perf
    assert perf["perfAvgUs"] < BUDGET_US, perf
    # the mirror: never more than 30 frames in a second (gen1-bridge's own sliding-window peak, the
    # authoritative check, and the client's average)
    assert mirror["mirrorPeakFps"] <= 30 and report["client_fps"] <= 30.5
    # The client's 5-second windows count frames by when this harness read them, and it reads in
    # bursts while busy elsewhere (the M10 gate saw 154): allow a third of a second's burst.
    assert report["client_max_5s"] <= 160 and report["client_frames"] >= 100
    assert not result.timed_out and result.returncode == 0, result.summary()
    assert result.marker("KB_QUIT") is not None and result.marker("KB_FAIL") is None
    assert not result.crash_log_touched and not result.error_spots
