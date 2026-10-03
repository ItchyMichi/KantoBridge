"""M10 performance: lib/kb_perf.lua (gen1-bridge's own cost per logic step: our step and every hook
and event handler of ours until the next step, the engine's own work inside our wrappers left out;
the draw-phase hooks counted per drawn frame) and how main.lua wires it (debug.status perfSteps,
perfAvgUs, perfMaxUs, perfOver1ms, perfTotalUs, perfFrames, perfDrawAvgUs, perfDrawMaxUs; the
drivers' perfReset). The clocks are the tests' own."""

import pytest

from kblua import Bridge
from rig import Rig

pytestmark = pytest.mark.lua


def perf_with_clock():
    """kb_perf on a Lua clock the test moves (c.t, seconds)."""
    b = Bridge()
    c = b.eval("{ t = 0 }")
    clock = b.eval("function(c) return function() return c.t end end")(c)
    opts = b.table()
    opts[b"clock"] = clock
    return b, c, b.module("lib/kb_perf.lua").new(opts)


def stats(p):
    s = p.stats()
    return {k.decode(): v for k, v in s.items()}


# ------------------------------------------------------------ kb_perf


def test_a_cycle_runs_from_one_step_to_the_next():
    b, c, p = perf_with_clock()
    p.beginStep()
    p.add(0.0004)
    assert stats(p)["steps"] == 0, "a cycle counts once it is complete"
    p.beginStep()
    p.add(0.0015)
    p.beginStep()
    s = stats(p)
    assert (s["steps"], s["avgUs"], s["maxUs"], s["over1ms"], s["totalUs"], s["lastUs"]) == (2, 950, 1500, 1, 1900, 1500)
    for k in ("steps", "avgUs", "maxUs", "over1ms", "totalUs", "frames", "drawAvgUs", "drawMaxUs"):
        assert isinstance(s[k], int), k
    p.reset()
    p.beginStep()
    assert (stats(p)["steps"], stats(p)["maxUs"]) == (1, 0)


def test_a_hook_leaves_the_engines_own_work_out():
    b, c, p = perf_with_clock()
    run = b.eval("""function(p, c)
      local hook = p.hook(function(nextFn, x)
        c.t = c.t + 0.0002                 -- ours
        local r1, r2 = nextFn(x)           -- the engine's: 5 ms
        c.t = c.t + 0.0001                 -- ours
        return r1, r2, nil, 'last'
      end)
      p.beginStep()
      local a, b2, n, last = hook(function(x) c.t = c.t + 0.005 return x * 2, 'two' end, 21)
      p.beginStep()
      return a, b2, n, last
    end""")
    a, b2, n, last = run(p, c)
    assert (a, b2, n, last) == (42, b"two", None, b"last"), "every return value passes through"
    s = stats(p)
    assert s["steps"] == 1 and s["maxUs"] == 300, s


def test_a_hook_that_yields_across_steps_is_left_out():
    b, c, p = perf_with_clock()
    run = b.eval("""function(p, c)
      local hook = p.hook(function(nextFn)
        c.t = c.t + 0.0001
        nextFn()                            -- the script's coroutine waits in here
        c.t = c.t + 0.0001
      end)
      p.beginStep()
      local co = coroutine.create(function() hook(function() coroutine.yield() end) end)
      coroutine.resume(co)
      c.t = c.t + 0.5                       -- other frames, other steps
      p.beginStep()
      p.add(0.0003)
      coroutine.resume(co)
      p.beginStep()
    end""")
    run(p, c)
    s = stats(p)
    assert s["steps"] == 2 and s["maxUs"] == 300, "the half second the script waited is not ours"


def test_timed_passes_arguments_and_results_through():
    b, c, p = perf_with_clock()
    run = b.eval("""function(p, c)
      local f = p.timed(function(x, y) c.t = c.t + 0.0007 return y, x, nil end)
      p.beginStep()
      local r = { f(1, 2) }
      p.beginStep()
      return r[1], r[2], #r
    end""")
    assert run(p, c) == (2, 1, 2)
    assert stats(p)["maxUs"] == 700


def test_drawn_frames_are_counted_apart():
    b, c, p = perf_with_clock()
    p.frame()
    p.addDraw(0.0015)
    p.frame()
    p.addDraw(0.0005)
    p.frame()
    s = stats(p)
    assert (s["frames"], s["drawAvgUs"], s["drawMaxUs"], s["steps"]) == (3, 1000, 1500, 0)


# ------------------------------------------------------------ main.lua


def status(rig, peer):
    seq = peer.send("debug.status")
    rig.step()
    return [m for m in peer.messages() if m["type"] == "debug.status" and m.get("re") == seq][0]["data"]


def test_debug_status_reports_the_cost_per_step():
    rig = Rig()
    rig.world()
    peer = rig.connect()
    peer.hello()
    peer.messages()
    rig.step(20)
    st = status(rig, peer)
    for k in ("perfSteps", "perfAvgUs", "perfMaxUs", "perfOver1ms", "perfTotalUs", "perfFrames"):
        assert isinstance(st[k], int) and st[k] >= 0, k
    assert st["perfSteps"] >= 20 and st["perfMaxUs"] >= st["perfAvgUs"]
    # every clock read costs 2 microseconds: our steps cost something now
    rig.status.perfReset()
    rig.b.clock_per_call = 0.000002
    rig.step(30)
    st = status(rig, peer)
    assert st["perfSteps"] >= 29 and st["perfAvgUs"] > 0 and st["perfOver1ms"] == 0
    assert st["perfTotalUs"] >= st["perfAvgUs"] * (st["perfSteps"] - 1)
    # a step that costs more than 1 ms is counted
    rig.b.clock_per_call = 0.001
    rig.step(2)
    rig.b.clock_per_call = 0
    st = status(rig, peer)
    assert st["perfOver1ms"] >= 1 and st["perfMaxUs"] > 1000
    # the drivers read the same numbers from the exports (published every 30 steps)
    assert isinstance(rig.status.perfSteps, int) and isinstance(rig.status.perfAvgUs, int)


def test_the_engines_work_inside_our_hooks_is_not_ours():
    rig = Rig()
    rig.world()
    peer = rig.connect()
    peer.hello()
    peer.messages()
    rig.step(2)
    rig.status.perfReset()
    clock = rig.b._clock
    slow = rig.b.eval("function(c) return function(allowed) c.now = c.now + 0.01 return allowed end end")(clock)
    ctx = rig.b.to_lua({"entity": {}, "x": 1, "y": 1})
    rig.b.FakeMod.call(rig.b.mod, b"movement.collision", slow, True, ctx)
    script = rig.b.eval("function(c) return function() c.now = c.now + 0.02 return nil end end")(clock)
    rig.b.FakeMod.call(rig.b.mod, b"script.command", script, rig.b.to_lua({}), b"show_text", rig.b.to_lua({}))
    rig.step(2)
    st = status(rig, peer)
    assert st["perfMaxUs"] < 5000, "30 ms of the engine's work inside our wrappers isn't counted"


def test_drawn_frames_are_counted_by_render_output_enabled():
    rig = Rig()
    vanilla = rig.b.eval("function() return false end")
    for _ in range(5):
        rig.b.FakeMod.call(rig.b.mod, b"render.output_enabled", vanilla)
    rig.world()
    peer = rig.connect()
    peer.hello()
    peer.messages()
    st = status(rig, peer)
    assert st["perfFrames"] == 5 and st["perfDrawMaxUs"] == 0
