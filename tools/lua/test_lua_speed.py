"""STEP_SPEED_PLAN.md S1/S2: act/kb_speed.lua (the STEP SPEED policy, pure) and Gen 1's movement.speed facts
(lib/engine/gen1.lua stepContext) on the fake engine. Everything is made up."""

import math

import pytest

from kblua import Bridge
from rig import Rig

pytestmark = pytest.mark.lua


class Policy:
    def __init__(self, pct=None):
        self.b = Bridge()
        self.Speed = self.b.module("act/kb_speed.lua")
        self.logged = self.b.eval("{}")
        log = self.b.eval("""function(rec) return { once = function(key, level, code)
          rec[#rec + 1] = level .. ":" .. code end } end""")(self.logged)
        opts = self.b.table()
        opts[b"log"] = log
        if pct is not None:
            opts[b"pct"] = pct
        self.s = self.Speed.new(opts)

    def ctx(self, run=False, pass_=False, player=True):
        c = self.b.table()
        c[b"isPlayer"], c[b"run"], c[b"pass"] = player, run, pass_
        return c

    def apply(self, frames, run=False, pass_=False, player=True, owns=True, ctx=True):
        return self.s.apply(frames, self.ctx(run, pass_, player) if ctx else None, owns)

    def logs(self):
        return [self.logged[i].decode() for i in range(1, len(self.logged) + 1)]

    def counts(self):
        st = self.s.stats()
        return int(st.fast), int(st.passed)


# the plan's table (§2.1): engine frames, run, then 1X, 1.5X, 2X, 3X
TABLE = [
    ("walk", 16, False, (16, 11, 8, 6)),
    ("emerald run", 8, True, (8, 6, 4, 3)),
    ("blue/crystal surf", 16, False, (16, 11, 8, 6)),
    ("emerald surf", 8, False, (8, 8, 8, 6)),
]


@pytest.mark.parametrize("name,frames,run,expected", TABLE, ids=[t[0] for t in TABLE])
def test_the_plans_table_for_every_choice(name, frames, run, expected):
    for pct, want in zip((100, 150, 200, 300), expected):
        p = Policy(pct)
        assert p.apply(frames, run=run) == want, (name, pct)


def test_the_default_is_2x():
    p = Policy()
    assert p.s.pct() == 200 and p.apply(16) == 8 and p.apply(8, run=True) == 4
    assert [int(v) for v in p.Speed.CHOICES.values()] == [100, 150, 200, 300]


@pytest.mark.parametrize("why", ["not owned", "pass", "no context", "not the player"])
def test_no_change_when_the_step_is_not_the_drivers_own_walk(why):
    p = Policy(300)
    if why == "not owned":
        assert p.apply(16, owns=False) == 16 and p.counts() == (0, 0)
    elif why == "pass":
        assert p.apply(32, pass_=True) == 32 and p.apply(48, pass_=True) == 48 and p.counts() == (0, 2)
    elif why == "no context":
        assert p.apply(16, ctx=False) == 16 and p.counts() == (0, 0)
    else:
        assert p.apply(16, player=False) == 16 and p.counts() == (0, 0)


def test_it_never_lengthens_a_step():
    for pct in (100, 150, 200, 300):
        p = Policy(pct)
        assert p.apply(5) == 5 and p.apply(1) == 1 and p.apply(3, run=True) == min(3, math.ceil(800 / pct))


def test_results_are_whole_numbers_of_at_least_one():
    for pct in (100, 150, 200, 300):
        p = Policy(pct)
        for frames in (1, 2, 7, 8, 11, 15.5, 16, 17, 40):
            for run in (False, True):
                out = p.apply(frames, run=run)
                assert out >= 1 and out == int(out), (pct, frames, run, out)
                assert out <= frames


def test_set_pct_rejects_other_values_and_logs_once():
    p = Policy(150)
    assert p.s.setPct(300) is True and p.s.pct() == 300
    assert p.s.setPct(250) is False and p.s.pct() == 200
    assert p.s.setPct("fast") is False and p.s.setPct(None) is False and p.s.pct() == 200
    assert p.logs() == ["warn:move.speed_option_invalid"] * 3  # once per key (kb_log's once keeps it to one line)
    assert p.s.setPct(100) is True and p.apply(16) == 16


def test_an_invalid_option_at_creation_is_2x():
    p = Policy(123)
    assert p.s.pct() == 200 and p.apply(16) == 8 and p.logs() == ["warn:move.speed_option_invalid"]


def test_the_counters_count_shortened_and_passed_steps():
    p = Policy(200)
    p.apply(16)                # shortened
    p.apply(8, run=True)       # shortened
    p.apply(8)                 # Emerald's surf: already at the target, not counted
    p.apply(32, pass_=True)    # a hop: passed
    p.apply(16, owns=False)    # nobody's: neither
    assert p.counts() == (2, 1)
    st = p.s.stats()
    assert (int(st.pct), int(st.fast), int(st.passed)) == (200, 2, 1)


# ------------------------------------------------------------ Gen 1's facts (lib/engine/gen1.lua)


def gen1_engine(rig):
    b = rig.b
    eng = b.module("lib/kb_engine.lua").new(b.engine_opts(ui=rig.E.ui))
    eng.setGame(rig.E.game)
    return eng


def test_gen1_step_context_reads_the_hooks_ctx():
    rig = Rig()
    rig.world("TEST_TOWN", 3, 4, "down")
    eng = gen1_engine(rig)
    b = rig.b
    p = rig.E.ow.player
    ctx = b.eval("function(p, d, bike) return { player = p, dir = d, onBike = bike, surfing = false } end")
    assert eng.stepContext(ctx(p, b"up", False)) is None, "no live player until observe() has run"
    eng.observe()
    c = eng.stepContext(ctx(p, b"up", False))
    assert (c.isPlayer, c[b"pass"], c.run, c.dir) == (True, False, False, b"up")
    p.ledgeHop = True
    assert eng.stepContext(ctx(p, b"up", False))[b"pass"] is True, "a ledge hop keeps Gen 1's 32 frames"
    p.ledgeHop = None
    p.spinning = True
    assert eng.stepContext(ctx(p, b"up", False))[b"pass"] is True, "a spinner's slide"
    p.spinning = None
    assert eng.stepContext(ctx(p, b"up", True))[b"pass"] is True, "a bike's step is a ride"
    other = eng.stepContext(ctx(b.eval("{ cellX = 1 }"), b"left", False))
    assert other.isPlayer is False
    assert eng.stepContext(ctx(p, b"sideways", False)).dir is None
    assert eng.stepContext(None) is None and eng.stepContext(b.table()) is None
