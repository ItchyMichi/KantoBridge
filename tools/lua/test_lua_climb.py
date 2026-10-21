"""act/kb_climb.lua (a cave shaft's warp, docs/PROTOCOL.md "Ladders and holes (H5)") on plain facts with a stub
mod.world, and the warp observer's labelling of the warp it queues (observe/kb_warp.lua). All synthetic."""

import pytest

from kblua import Bridge

pytestmark = pytest.mark.lua

STAND = {"inGame": True, "top": "overworld", "map": "TEST_CAVE_1F", "x": 8, "y": 3, "facing": "right",
         "moving": False}
TAKE = {"from": {"map": "TEST_CAVE_1F", "x": 9, "y": 3}, "to": {"map": "TEST_CAVE_B1F", "x": 9, "y": 3, "facing": "down"},
        "kind": "ladder"}


class Climb:
    def __init__(self, busy=False):
        self.b = Bridge()
        self.queued = []
        self.busy = busy
        self.expected = []
        world = self.b.table()
        # a Lua function, as mod.world's (the act checks for one)
        world.queueScript = self.b.eval("function(f) return function(...) return f(...) end end")(
            lambda _world, rows: self._queue(rows))
        warps = self.b.table()
        warps.expect = lambda kind, re, gen: self.expected.append((kind.decode(), re, gen))
        opts = self.b.table()
        opts.world, opts.warps = world, warps
        self.act = self.b.module("act/kb_climb.lua").new(opts)

    def _queue(self, rows):
        if self.busy:
            return None
        self.queued.append([[(v.decode() if isinstance(v, bytes) else v) for v in row.values()] for row in rows.values()])
        return True

    def request(self, data=None, seq=7, gen=1):
        return self.act.request(self.b.to_lua(TAKE if data is None else data), seq, gen)

    def step(self, mode="idle", live=True, gen=1, **facts):
        f = dict(STAND)
        f.update(facts)
        out = self.b.table()
        self.act.step(self.b.to_lua(f), mode.encode(), out, self.b.to_lua({"live": live, "generation": gen}))
        result = []
        for m in out.values():
            data = {k.decode(): (v.decode() if isinstance(v, bytes) else v) for k, v in m[2].items()}
            result.append((m[1].decode(), data, m.re, m.gen))
        return result


def test_a_take_next_to_the_near_end_queues_the_engines_warp_and_is_answered_ok():
    c = Climb()
    assert c.request()
    assert c.step() == [("warp.taken", {"ok": True}, 7, 1)]
    assert c.queued == [[["warp", "TEST_CAVE_B1F", 9, 3, "down"]]]
    assert c.expected == [("stairs", 7, 1)], "the observer labels the warp a ladder's: kind stairs, re 7, the take's connection"
    assert c.step() == []


def test_a_hole_is_labelled_hole():
    c = Climb()
    c.request(dict(TAKE, kind="hole"))
    c.step(x=9, y=3)
    assert c.expected == [("hole", 7, 1)]


@pytest.mark.parametrize("facts, reason", [
    ({"map": "TEST_TOWN"}, "map"),
    ({"x": 7, "y": 3}, "far"),
    ({"x": 8, "y": 4}, "far"),
])
def test_a_take_from_elsewhere_is_refused(facts, reason):
    c = Climb()
    c.request()
    assert c.step(**facts) == [("warp.taken", {"ok": False, "reason": reason}, 7, 1)]
    assert c.queued == [] and c.expected == []


def test_a_busy_world_refuses_it():
    c = Climb(busy=True)
    c.request()
    assert c.step() == [("warp.taken", {"ok": False, "reason": "busy"}, 7, 1)]
    assert c.expected == []


def test_it_waits_for_the_hidden_player_to_stand_then_refuses_if_it_never_does():
    c = Climb()
    c.request()
    assert c.step(moving=True) == []
    assert c.step() == [("warp.taken", {"ok": True}, 7, 1)], "once standing, it goes"
    c.request(seq=8)
    for _ in range(30):
        assert c.step(mode="screen") == []
    assert c.step(mode="screen") == [("warp.taken", {"ok": False, "reason": "mode"}, 8, 1)]


def test_one_at_a_time_and_none_from_an_old_connection():
    c = Climb()
    c.request(seq=7)
    c.request(seq=8)
    assert c.step(moving=True) == [("warp.taken", {"ok": False, "reason": "busy"}, 8, 1)]
    c.request(seq=9, gen=2)
    out = c.step(gen=2)
    assert out == [("warp.taken", {"ok": False, "reason": "busy"}, 9, 2)], "7 was dropped with its connection"
    assert c.step(gen=2) == []


@pytest.mark.parametrize("data", [
    {},
    dict(TAKE, kind="stairs"),
    dict(TAKE, to={"map": "TEST_CAVE_B1F", "x": 9, "y": 3}),
    dict(TAKE, **{"from": {"map": "bad map", "x": 1, "y": 1}}),
    dict(TAKE, to={"map": "TEST_CAVE_B1F", "x": 1.5, "y": 3, "facing": "down"}),
])
def test_malformed_takes_are_bad_messages(data):
    c = Climb()
    ok, code = c.request(data)
    assert ok is False and code == b"bad_message"


class Tracker:
    def __init__(self):
        self.b = Bridge()
        self.t = self.b.module("observe/kb_warp.lua").new(self.b.table())

    def step(self, ev=None, **facts):
        f = dict(STAND)
        f.update(facts)
        out = self.t.step(self.b.to_lua(f), self.b.to_lua(ev or {}))
        return [(m[2].phase.decode(), m[2].kind.decode(), m.re) for m in out.values()]

    def gens(self, ev=None, **facts):
        f = dict(STAND)
        f.update(facts)
        out = self.t.step(self.b.to_lua(f), self.b.to_lua(ev or {}))
        return [(m[2].phase.decode(), m.re, m.gen) for m in out.values()]


def test_the_observer_gives_the_takes_warp_its_kind_and_re():
    t = Tracker()
    t.t.expect(b"stairs", 7, 1)
    assert t.step(transitioning=True) == [("start", "stairs", 7)]
    assert t.step({"entered": [{"map": "TEST_CAVE_B1F", "via": "warp"}]}, map="TEST_CAVE_B1F", x=9, y=3) == [
        ("arrive", "stairs", 7)]
    assert t.step(transitioning=True) == [("start", "script", None)], "only the one warp"


def test_the_takes_warp_messages_carry_its_connections_generation():
    # main.lua sends a message with a re as a reply, and only when its gen is the live connection's: without the gen
    # the take's warp messages were dropped (the H6 pilot's first take on the real game timed out)
    t = Tracker()
    t.t.expect(b"stairs", 7, 3)
    assert t.gens(transitioning=True) == [("start", 7, 3)]
    assert t.gens({"entered": [{"map": "TEST_CAVE_B1F", "via": "warp"}]}, map="TEST_CAVE_B1F", x=9, y=3) == [("arrive", 7, 3)]
    assert t.gens(transitioning=True) == [("start", None, None)], "an ordinary warp is streamed"


def test_an_expectation_no_warp_follows_lapses():
    t = Tracker()
    t.t.expect(b"hole", 9, 1)
    for _ in range(121):
        t.step()
    assert t.step(transitioning=True) == [("start", "script", None)]


def test_a_warp_table_warp_keeps_its_own_kind():
    t = Tracker()
    t.t.expect(b"stairs", 7, 1)
    assert t.step({"warped": {"kind": "door", "map": "TEST_CAVE_1F"}}, transitioning=True) == [("start", "door", None)]


def test_a_script_warp_the_engine_announces_is_the_takes():
    # Gen 3's engine announces a requested warp as it starts (player.warped; the adapter's departure says "script"):
    # it takes the expectation, as a fade-started script warp does (HEIGHTS_ALL_GAMES_PLAN W3)
    t = Tracker()
    t.t.expect(b"hole", 9, 2)
    assert t.step({"warped": {"kind": "script", "map": "EM_TEST_CAVE"}}, transitioning=True) == [("start", "hole", 9)]
    assert t.step({"entered": [{"map": "EM_TEST_CAVE_B1F", "via": "warp"}]}, map="EM_TEST_CAVE_B1F", x=3, y=4) == [
        ("arrive", "hole", 9)]
    assert t.step({"warped": {"kind": "script", "map": "EM_TEST_CAVE_B1F"}}, transitioning=True) == [
        ("start", "script", None)], "with no take waiting, a script warp is just that"


def test_the_main_loop_sends_the_takes_warp_as_its_answer():
    # Through gen1-bridge's main.lua (the simulated route game: mod.world's queueScript on the fake script runner):
    # the take's warp.taken and both its warp messages go out with the take's re (the H6 pilot found them dropped)
    from routesim import ROUTE_MAP, RouteGame

    g = RouteGame()
    m, x, y = g.where()
    assert m == ROUTE_MAP
    seq = g.send("warp.take", {"from": {"map": m, "x": x, "y": y}, "to": {"map": "TEST_WOODS", "x": 4, "y": 4, "facing": "down"},
                               "kind": "ladder"})
    taken = g.until(lambda msg: msg["type"] == "warp.taken")
    assert taken.get("re") == seq and taken["data"] == {"ok": True}
    start = g.until(lambda msg: msg["type"] == "warp" and msg["data"]["phase"] == "start")
    assert start.get("re") == seq and start["data"]["kind"] == "stairs"
    arrive = g.until(lambda msg: msg["type"] == "warp" and msg["data"]["phase"] == "arrive")
    assert arrive.get("re") == seq
    assert arrive["data"]["to"] == {"map": "TEST_WOODS", "x": 4, "y": 4, "facing": "down"}
    assert g.where() == ("TEST_WOODS", 4, 4)
