"""act/kb_move.lua (the step driver) and observe/kb_warp.lua (warps), wired by main.lua, against
the simulated overworld in fake_world.lua: the engine's step timing (P1 section 1.3), its
collision verdicts through the movement.collision hook, warps and their fades, ledges, boulders and
connections. Every message the peer receives is schema-checked strictly (rig.Peer). All maps are
made up."""

import pytest

from kblua import Bridge
from rig import Rig

pytestmark = pytest.mark.lua

TOWN = {
    "rows": [
        "############",  # 0
        "#..........#",  # 1
        "#.###......#",  # 2
        "#.#D#...P..#",  # 3  house door (3,3), warp pad (8,3)
        "#..........#",  # 4
        "#....vvv...#",  # 5  ledges hopped downwards
        "#..........#",  # 6
        "#..........#",  # 7  an NPC stands at (5,7)
        "#..........#",  # 8
        "#####.v#####",  # 9  (5,9) leads south onto the route; a ledge (6,9) hops onto it
    ],
    "warps": [{"x": 3, "y": 3, "to": "TEST_HOUSE", "tx": 1, "ty": 5},
              {"x": 8, "y": 3, "to": "TEST_CAVE", "tx": 1, "ty": 1}],
    "connections": {"south": {"map": "TEST_ROUTE", "offset": 0}},
    "npcs": [{"id": "TEST_TOWN_obj_1", "x": 5, "y": 7}],
    "tileset": "OVERWORLD",  # outside: a spin out of here is a Teleport (M8)
}
ROUTE = {
    "rows": [
        "#####..#####",  # 0  open to the town above
        "#..........#",
        "#..........#",  # 2  a boulder at (8,2)
        "#..........#",
        "#...........",  # 4  open east edge with no connection
        "############",
    ],
    "connections": {"north": {"map": "TEST_TOWN", "offset": 0}},
    "npcs": [{"id": "TEST_ROUTE_obj_1", "x": 8, "y": 2, "sprite": "SPRITE_BOULDER", "pushable": True}],
}
HOUSE = {
    "rows": [
        "########",
        "#......#",
        "#.S....#",  # stairs (2,2)
        "#......#",
        "#......#",
        "#MM#####",  # exit mats on the bottom edge
    ],
    "warps": [{"x": 2, "y": 2, "to": "TEST_UP", "tx": 1, "ty": 1},
              {"x": 1, "y": 5, "to": "TEST_TOWN", "tx": 3, "ty": 3},
              {"x": 2, "y": 5, "to": "TEST_TOWN", "tx": 3, "ty": 3}],
}
UP = {"rows": ["#####", "#S..#", "#...#", "#####"],
      "warps": [{"x": 1, "y": 1, "to": "TEST_HOUSE", "tx": 2, "ty": 2}]}
CAVE = {"rows": ["######", "#.PH.#", "#....#", "######"],
        "warps": [{"x": 2, "y": 1, "to": "TEST_TOWN", "tx": 8, "ty": 4},
                  {"x": 3, "y": 1, "to": "TEST_TOWN", "tx": 9, "ty": 4}]}
ELEVATOR = {"rows": ["####", "#..#", "#M##"],
            "warps": [{"x": 1, "y": 2, "to": "TEST_TOWN", "tx": 1, "ty": 1}]}
# STEP_SPEED_PLAN: an open field for the diagonal staircase
FIELD = {"rows": ["#" * 26] + ["#" + "." * 24 + "#" for _ in range(24)] + ["#" * 26]}
MAPS = {"TEST_TOWN": TOWN, "TEST_ROUTE": ROUTE, "TEST_HOUSE": HOUSE, "TEST_UP": UP,
        "TEST_CAVE": CAVE, "TEST_ELEVATOR": ELEVATOR, "TEST_FIELD": FIELD}

DIRS = ("up", "down", "left", "right")
# STEP_SPEED_PLAN: a step the driver owns takes 8 frames at the default STEP SPEED (2X; Gen 1 walks 16)
WALK = 8


class Walk:
    """A simulated world, gen1-bridge's main.lua and one connected peer (role test)."""

    def __init__(self, start=("TEST_TOWN", 3, 4, "down"), role="test", fade=32, **kw):
        self.rig = Rig(**kw)
        m, x, y, f = start
        self.rig.simulate({"maps": MAPS, "start": {"map": m, "x": x, "y": y, "facing": f}, "fade": fade})
        self.E = self.rig.E
        self.peer = self.rig.connect()
        self.peer.hello(role=role)
        self.first = self.peer.messages()
        self.n = 0
        self.sent = []
        self.log = []  # (tick, message), every message received

    @property
    def tick(self):
        return int(self.E.ticks)

    @property
    def p(self):
        return self.E.ow.player

    def cell(self):
        p = self.p
        return self.E.ow.map.id.decode(), p.cellX, p.cellY, p.facing.decode()

    def step(self, direction, push=None):
        self.n += 1
        data = {"dir": direction, "n": self.n}
        if push is not None:
            data["push"] = push
        self.peer.send("move.step", data)
        self.sent.append(self.n)
        return self.n

    def run(self, ticks=1):
        out = []
        for _ in range(ticks):
            self.rig.step()
            for m in self.peer.messages():
                self.log.append((self.tick, m))
                out.append(m)
        return out

    def until(self, pred, limit=300):
        """Runs until a received message matches; returns it."""
        for _ in range(limit):
            for m in self.run():
                if pred(m):
                    return m
        raise AssertionError("no matching message within the limit")

    def answer(self, n, limit=300):
        return self.until(lambda m: m["type"] in ("player.pos", "player.blocked") and m["data"].get("n") == n,
                          limit)

    def settle(self, limit=300):
        """Runs until the player stands idle with nothing in flight (and a few ticks more)."""
        for _ in range(limit):
            self.run()
            if self.E.settled():
                self.run(2)
                return
        raise AssertionError("never settled")

    def types(self):
        return [m["type"] for t, m in self.log]

    def answers(self):
        by_n = {}
        for t, m in self.log:
            if m["type"] in ("player.pos", "player.blocked") and "n" in m["data"]:
                by_n.setdefault(m["data"]["n"], []).append(m)
        return by_n

    def assert_no_drift(self):
        answers = self.answers()
        assert sorted(answers) == sorted(self.sent), (sorted(answers), self.sent)
        assert all(len(v) == 1 for v in answers.values()), {n: len(v) for n, v in answers.items()}

    def held(self, tick):
        for row in self.E.trace.values():
            if int(row[1]) == tick:
                return row[5].decode()
        raise KeyError(tick)

    def moving(self, tick):
        for row in self.E.trace.values():
            if int(row[1]) == tick:
                return bool(row[2])
        raise KeyError(tick)

    def starts(self):
        return [int(t) for t in self.E.stepStarts.values()]

    def status(self, peer=None):
        peer = peer or self.peer
        seq = peer.send("debug.status")
        self.rig.step()
        for m in peer.messages():
            if m["type"] == "debug.status" and m.get("re") == seq:
                return m["data"]
        raise AssertionError("no debug.status")


def pos(m):
    d = m["data"]
    return d["map"], d["x"], d["y"], d["facing"], d["kind"], d["frames"]


# ------------------------------------------------------------ press, hold, release


def test_one_step_presses_at_once_and_releases_on_the_landing_poll():
    w = Walk(start=("TEST_TOWN", 9, 4, "right"))
    k = w.tick + 1                       # the logic step whose input.step presses
    n = w.step("right")
    answer = w.answer(n)
    assert pos(answer) == ("TEST_TOWN", 10, 4, "right", "walk", WALK)
    assert w.starts() == [k]             # the step starts in the press's own logic step
    w.run(20)
    assert [w.moving(t) for t in range(k, k + WALK)] == [True] * (WALK - 1) + [False]  # lands in k+WALK-1
    assert all(w.held(t) == "right" for t in range(k, k + WALK))
    assert w.held(k + WALK) == ""          # released on the landing poll (k+WALK)
    assert w.cell() == ("TEST_TOWN", 10, 4, "right")
    assert w.starts() == [k]             # and no second step
    w.assert_no_drift()


def test_the_facing_is_set_first_so_there_is_no_turn():
    w = Walk(start=("TEST_TOWN", 5, 4, "left"))
    k = w.tick + 1
    n = w.step("right")
    assert pos(w.answer(n)) == ("TEST_TOWN", 6, 4, "right", "walk", WALK)
    assert w.starts() == [k]             # an engine turn would have cost 4 frames first


def test_continuous_walking_has_no_idle_frame_between_steps():
    w = Walk(start=("TEST_TOWN", 1, 6, "right"))
    k = w.tick + 1
    first = w.step("right")
    w.answer(first)
    second = w.step("right")             # waits behind the step in flight
    third = w.step("down")               # a corner: the facing is set at the landing poll
    w.answer(second)
    w.answer(third)
    w.settle()
    assert w.starts() == [k, k + WALK, k + 2 * WALK]
    assert all(w.held(t) == "right" for t in range(k, k + 2 * WALK))
    assert all(w.held(t) == "down" for t in range(k + 2 * WALK, k + 3 * WALK))
    assert w.cell() == ("TEST_TOWN", 3, 7, "down")
    assert w.status()["moveChained"] == 2
    w.assert_no_drift()


def test_the_queue_keeps_two_waiting_steps_and_drops_the_oldest():
    w = Walk(start=("TEST_TOWN", 1, 6, "right"))
    ns = [w.step("right") for _ in range(4)]
    w.settle()
    answers = w.answers()
    assert answers[ns[1]][0]["type"] == "player.blocked"
    assert answers[ns[1]][0]["data"]["reason"] == "script"
    assert [answers[n][0]["data"]["x"] for n in (ns[0], ns[2], ns[3])] == [2, 3, 4]
    assert w.cell()[:3] == ("TEST_TOWN", 4, 6)
    assert any("move.dropped" in line and "count=1" in line for line in w.rig.logs())
    assert w.status()["moveDropped"] == 1
    w.assert_no_drift()


def test_a_hold_lost_to_a_focus_reset_is_pressed_again():
    w = Walk(start=("TEST_TOWN", 1, 6, "right"))
    k = w.tick + 1
    w.answer(w.step("right"))
    w.step("right")
    w.run(4)
    w.E.resetInput()                     # Game:focus -> Input:reset drops every mod hold
    assert w.E.game.input.state.right is not True
    w.settle()
    assert w.starts() == [k, k + WALK]     # the walk went on without a gap
    assert w.status()["moveRepressed"] == 1
    w.assert_no_drift()


# ------------------------------------------------------------ refusals


@pytest.mark.parametrize("start, direction, reason", [
    (("TEST_TOWN", 1, 1, "down"), "up", "tile"),
    (("TEST_TOWN", 5, 6, "up"), "down", "entity"),
    (("TEST_ROUTE", 11, 4, "left"), "right", "bounds"),
])
def test_a_refused_step_is_blocked_and_the_player_only_turns(start, direction, reason):
    w = Walk(start=start)
    n = w.step(direction)
    blocked = w.answer(n)
    assert blocked["type"] == "player.blocked"
    assert blocked["data"] == {"dir": direction, "reason": reason, "map": start[0], "x": start[1],
                               "y": start[2], "n": n}
    w.run(3)
    assert w.cell() == (start[0], start[1], start[2], direction)
    assert w.starts() == []
    assert w.held(w.tick) == ""          # let go once the engine has tried
    syncs = [pos(m) for t, m in w.log if m["type"] == "player.pos"]
    assert syncs == [(start[0], start[1], start[2], direction, "sync", 0)]  # the turn
    w.assert_no_drift()


def test_steps_while_not_idle_are_refused_with_script():
    w = Walk(start=("TEST_TOWN", 5, 4, "down"))
    w.E.ow.runner.running = True         # a cutscene holds the world
    w.run(1)
    n = w.step("left")
    blocked = w.answer(n)
    assert blocked["data"]["reason"] == "script" and blocked["data"]["x"] == 5
    assert not any(op.op == b"press" for op in w.rig.b.mod._input.values())
    w.E.ow.runner.running = False
    m = w.step("left")
    assert pos(w.answer(m))[:3] == ("TEST_TOWN", 4, 4)
    w.assert_no_drift()


def test_leaving_idle_releases_every_hold_and_refuses_the_waiting_steps():
    w = Walk(start=("TEST_TOWN", 1, 6, "right"))
    first = w.step("right")
    w.answer(first)
    second, third = w.step("right"), w.step("right")
    w.run(3)
    w.E.push(w.E.text())                 # a text box opens mid-step
    out = w.run(2)
    assert w.held(w.tick) == ""
    blocked = [m["data"] for m in out if m["type"] == "player.blocked"]
    assert [(b["n"], b["reason"]) for b in blocked] == [(second, "script"), (third, "script")]
    assert {"mode": "screen"}.items() <= [m["data"] for m in out if m["type"] == "player.state"][0].items()
    w.E.pop()
    w.settle()
    assert w.cell()[:3] == ("TEST_TOWN", 2, 6)  # the step in flight still lands
    w.assert_no_drift()


# ------------------------------------------------------------ face, hold B


def test_move_face_turns_in_place_and_answers_with_a_sync():
    w = Walk(start=("TEST_TOWN", 5, 4, "down"))
    w.run(1)
    w.peer.send("move.face", {"dir": "left"})
    out = w.run(2)
    assert [pos(m) for m in out if m["type"] == "player.pos"] == [("TEST_TOWN", 5, 4, "left", "sync", 0)]
    w.peer.send("move.face", {"dir": "left"})  # already facing that way: still answered
    out = w.run(2)
    assert [pos(m) for m in out if m["type"] == "player.pos"] == [("TEST_TOWN", 5, 4, "left", "sync", 0)]
    assert w.starts() == []


def test_move_face_waits_for_the_steps_in_flight():
    w = Walk(start=("TEST_TOWN", 1, 6, "right"))
    w.step("right")
    w.run(2)
    w.peer.send("move.face", {"dir": "up"})
    w.settle()
    assert w.cell() == ("TEST_TOWN", 2, 6, "up")
    assert pos([m for t, m in w.log if m["type"] == "player.pos"][-1]) == ("TEST_TOWN", 2, 6, "up", "sync", 0)


def test_move_hold_b_follows_the_idle_state():
    w = Walk(start=("TEST_TOWN", 5, 4, "down"))
    w.peer.send("move.hold", {"button": "b", "on": True})
    w.run(2)
    assert w.E.game.input.state.b is True
    w.E.push(w.E.text())
    w.run(2)
    assert w.E.game.input.state.b is not True   # a held B would close the text box
    w.E.pop()
    w.run(2)
    assert w.E.game.input.state.b is True       # wanted again once idle
    w.peer.send("move.hold", {"button": "b", "on": False})
    w.run(2)
    assert w.E.game.input.state.b is not True


def test_the_cycling_road_roll_is_a_slope_step_and_b_brakes_it():
    w = Walk(start=("TEST_ROUTE", 2, 1, "down"))
    w.E.save.onBike = True
    w.p.slopeMap = True
    w.peer.send("move.hold", {"button": "b", "on": True})
    w.run(20)
    assert w.starts() == []                   # braking
    w.peer.send("move.hold", {"button": "b", "on": False})
    rolled = w.until(lambda m: m["type"] == "player.pos" and m["data"]["kind"] != "sync")
    assert pos(rolled) == ("TEST_ROUTE", 2, 2, "down", "slope", 8)
    assert "n" not in rolled["data"]
    n = w.step("right")                       # a step asked for on the slope map is the player's own
    assert pos(w.answer(n))[4:] == ("bike", 16)


# ------------------------------------------------------------ push, ledges, boulders


def test_a_push_against_a_wall_is_tried_then_released():
    w = Walk(start=("TEST_TOWN", 1, 1, "left"))
    n = w.step("left", push=True)
    blocked = w.answer(n)
    assert (blocked["type"], blocked["data"]["reason"]) == ("player.blocked", "tile")
    w.run(2)
    assert w.held(w.tick) == ""
    w.assert_no_drift()


def test_a_push_that_can_move_is_an_ordinary_step():
    w = Walk(start=("TEST_TOWN", 5, 4, "right"))
    n = w.step("right", push=True)
    # a push is never shortened (STEP_SPEED_PLAN §2.3: ownsStep is false for push:true): Gen 1's 16 frames
    assert pos(w.answer(n)) == ("TEST_TOWN", 6, 4, "right", "walk", 16)


def test_a_ledge_hop_is_reported_once_at_its_landing():
    w = Walk(start=("TEST_TOWN", 6, 4, "down"))
    n = w.step("down")
    hop = w.answer(n)
    assert pos(hop) == ("TEST_TOWN", 6, 6, "down", "ledge", 32)
    w.run(1)
    assert w.held(w.tick) == ""               # forced: every hold let go
    w.settle()
    assert w.cell() == ("TEST_TOWN", 6, 6, "down")
    moves = [pos(m) for t, m in w.log if m["type"] == "player.pos"]
    assert moves == [("TEST_TOWN", 6, 6, "down", "ledge", 32)]
    modes = [m["data"]["mode"] for t, m in w.log if m["type"] == "player.state"]
    assert modes == ["forced", "idle"]
    w.assert_no_drift()


def test_a_hop_onto_the_connected_map_ends_on_that_map():
    w = Walk(start=("TEST_TOWN", 6, 8, "down"))
    n = w.step("down")
    assert pos(w.answer(n)) == ("TEST_TOWN", 6, 10, "down", "ledge", 32)
    w.settle()
    moves = [pos(m) for t, m in w.log if m["type"] == "player.pos"]
    assert moves == [("TEST_TOWN", 6, 10, "down", "ledge", 32), ("TEST_ROUTE", 6, 0, "down", "ledge", 16)]
    assert "warp" not in w.types()
    assert w.cell() == ("TEST_ROUTE", 6, 0, "down")


def test_a_strength_boulder_push_answers_with_kind_push():
    w = Walk(start=("TEST_ROUTE", 7, 2, "right"))
    w.E.ow.strengthActive = True
    first = w.step("right")
    blocked = w.answer(first)                 # the first try only arms the push
    assert (blocked["type"], blocked["data"]["reason"]) == ("player.blocked", "entity")
    second = w.step("right")
    push = w.answer(second)
    assert pos(push) == ("TEST_ROUTE", 7, 2, "right", "push", 0)
    w.settle()
    boulder = w.E.ow.npcs[1]
    assert (boulder.cellX, boulder.cellY) == (9, 2)
    assert w.cell() == ("TEST_ROUTE", 7, 2, "right")  # the player stays
    modes = [m["data"]["mode"] for t, m in w.log if m["type"] == "player.state"]
    assert modes == ["cutscene", "busy", "idle"]
    w.assert_no_drift()


# ------------------------------------------------------------ connections and warps


def test_a_connection_crossing_is_a_step_onto_the_new_map_not_a_warp():
    w = Walk(start=("TEST_TOWN", 5, 8, "down"))
    first, second = w.step("down"), w.step("down")
    assert pos(w.answer(first)) == ("TEST_TOWN", 5, 9, "down", "walk", WALK)
    assert pos(w.answer(second)) == ("TEST_ROUTE", 5, 0, "down", "walk", WALK)
    w.settle()
    assert "warp" not in w.types()
    assert w.cell() == ("TEST_ROUTE", 5, 0, "down")
    assert w.status()["mapsEntered"] >= 1
    w.assert_no_drift()


def warp_messages(w):
    return [m["data"] for t, m in w.log if m["type"] == "warp"]


def arrival_order(w):
    """The messages of the step that carried the warp arrival."""
    tick = next(t for t, m in w.log if m["type"] == "warp" and m["data"]["phase"] == "arrive")
    return [m for t, m in w.log if t == tick]


def test_stairs_on_arrival():
    w = Walk(start=("TEST_HOUSE", 2, 1, "down"))
    n = w.step("down")
    assert pos(w.answer(n)) == ("TEST_HOUSE", 2, 2, "down", "walk", WALK)
    w.settle()
    assert warp_messages(w) == [
        {"phase": "start", "kind": "stairs"},
        {"phase": "arrive", "kind": "stairs", "to": {"map": "TEST_UP", "x": 1, "y": 1, "facing": "down"}},
    ]
    step_msgs = [m["type"] for m in arrival_order(w)]
    assert step_msgs.index("warp") < step_msgs.index("player.pos")
    assert pos([m for m in arrival_order(w) if m["type"] == "player.pos"][0]) == ("TEST_UP", 1, 1, "down", "sync", 0)
    modes = [m["data"]["mode"] for t, m in w.log if m["type"] == "player.state"]
    assert modes == ["busy", "idle"]
    w.assert_no_drift()


def test_a_door_then_its_exit_mat_by_pushing():
    w = Walk(start=("TEST_TOWN", 3, 4, "down"))
    into = w.step("up")
    assert pos(w.answer(into)) == ("TEST_TOWN", 3, 3, "up", "walk", WALK)
    w.settle()
    assert warp_messages(w) == [
        {"phase": "start", "kind": "door"},
        {"phase": "arrive", "kind": "door", "to": {"map": "TEST_HOUSE", "x": 1, "y": 5, "facing": "up"}},
    ]
    assert w.cell() == ("TEST_HOUSE", 1, 5, "up")
    out = w.step("down", push=True)          # the Minecraft player pushes against the bottom wall
    assert pos(w.answer(out)) == ("TEST_HOUSE", 1, 5, "down", "sync", 0)
    w.settle()
    assert warp_messages(w)[2:] == [
        {"phase": "start", "kind": "edge"},
        {"phase": "arrive", "kind": "edge", "to": {"map": "TEST_TOWN", "x": 3, "y": 3, "facing": "down"}},
    ]
    # landing on a door tile walks the player out one cell: a scripted step, no n
    walkout = [m for t, m in w.log if m["type"] == "player.pos" and m["data"]["kind"] == "script"]
    assert [pos(m) for m in walkout] == [("TEST_TOWN", 3, 4, "down", "script", 16)]
    assert "n" not in walkout[0]["data"]
    assert w.cell() == ("TEST_TOWN", 3, 4, "down")
    w.assert_no_drift()


def test_an_exit_mat_walked_onto_with_the_direction_held_warps_on_arrival():
    w = Walk(start=("TEST_HOUSE", 1, 3, "down"))
    a, b = w.step("down"), w.step("down")
    w.answer(a)
    assert pos(w.answer(b)) == ("TEST_HOUSE", 1, 5, "down", "walk", WALK)
    w.settle()
    assert [d["kind"] for d in warp_messages(w)] == ["edge", "edge"]
    assert w.cell() == ("TEST_TOWN", 3, 4, "down")
    w.assert_no_drift()


def test_pads_and_holes():
    w = Walk(start=("TEST_TOWN", 8, 4, "down"))
    w.answer(w.step("up"))
    w.settle()
    assert warp_messages(w) == [
        {"phase": "start", "kind": "pad"},
        {"phase": "arrive", "kind": "pad", "to": {"map": "TEST_CAVE", "x": 1, "y": 1, "facing": "up"}},
    ]
    modes = [m["data"]["mode"] for t, m in w.log if m["type"] == "player.state"]
    assert modes[0] == "forced"                # the spin-out before the fade
    w.answer(w.step("down"))
    w.answer(w.step("right"))
    w.answer(w.step("right"))
    w.answer(w.step("up"))                     # onto the hole
    w.settle()
    assert [d["kind"] for d in warp_messages(w)[2:]] == ["hole", "hole"]
    assert w.cell()[:3] == ("TEST_TOWN", 9, 4)
    w.assert_no_drift()


def test_an_elevator_car_exit_is_kind_elevator():
    w = Walk(start=("TEST_ELEVATOR", 1, 1, "down"))
    w.answer(w.step("down"))
    w.settle()
    assert [d["kind"] for d in warp_messages(w)] == ["elevator", "elevator"]


@pytest.mark.parametrize("how, kind, via", [
    ("flyTo", "fly", "fly"),
    ("teleportTo", "teleport", "warp"),
    ("blackOutTo", "blackout", "warp"),
    ("scriptWarp", "script", "warp"),
])
def test_warps_without_a_warp_entry(how, kind, via):
    w = Walk(start=("TEST_TOWN", 5, 4, "down"))
    w.run(2)
    w.E[how.encode()](b"TEST_ROUTE", 2, 2)
    w.settle()
    assert warp_messages(w) == [
        {"phase": "start", "kind": kind},
        {"phase": "arrive", "kind": kind, "to": {"map": "TEST_ROUTE", "x": 2, "y": 2, "facing": "down"}},
    ]
    assert w.cell() == ("TEST_ROUTE", 2, 2, "down")


def test_a_step_sent_during_a_warp_is_refused():
    w = Walk(start=("TEST_HOUSE", 2, 1, "down"))
    w.answer(w.step("down"))                  # onto the stairs
    w.until(lambda m: m["type"] == "warp")
    late = w.step("left")
    blocked = w.answer(late)
    assert (blocked["type"], blocked["data"]["reason"]) == ("player.blocked", "script")
    w.settle()
    assert w.cell()[:3] == ("TEST_UP", 1, 1)
    w.assert_no_drift()


# ------------------------------------------------------------ the connection and the rules


def test_builders_may_not_move_and_bad_requests_are_refused():
    w = Walk(role="builder")
    seq = w.peer.send("move.step", {"dir": "up", "n": 1})
    out = w.run(1)
    assert [(m["type"], m["data"]["code"], m["re"]) for m in out if m["type"] == "error"] == [("error", "role", seq)]
    v = Walk()
    bad = v.peer.send("move.hold", {"button": "b", "on": "yes"})
    out = v.run(1)
    assert [(m["data"]["code"], m["re"]) for m in out if m["type"] == "error"] == [("bad_message", bad)]


def test_a_dropped_connection_lets_go_and_forgets_its_steps():
    w = Walk(start=("TEST_TOWN", 1, 6, "right"))
    w.answer(w.step("right"))
    w.step("right")
    w.step("right")
    w.peer.close()
    w.run(3)
    assert w.held(w.tick) == ""
    w.run(30)
    assert w.cell()[:3] == ("TEST_TOWN", 2, 6)  # the step in flight lands, nothing more
    again = w.rig.connect()
    again.hello()
    again.messages()
    status = w.status(again)
    assert (status["moveQueued"], status["moveHeld"], status["moveAnswered"]) == (0, 0, 1)


def test_a_step_at_the_title_screen_is_refused_with_a_valid_message():
    rig = Rig()                               # no overworld on the stack
    peer = rig.connect()
    peer.hello()
    peer.messages()
    peer.send("move.step", {"dir": "up", "n": 1})
    rig.step()
    (blocked,) = [m for m in peer.messages() if m["type"] == "player.blocked"]
    assert blocked["data"] == {"dir": "up", "reason": "script", "map": "NONE", "x": 0, "y": 0, "n": 1}


def test_a_step_sent_with_the_hello_belongs_to_the_new_connection():
    w = Walk(start=("TEST_TOWN", 5, 4, "down"))
    w.peer.close()
    w.run(2)
    fresh = w.rig.connect(steps=0)
    fresh.send("hello", {"protocol": 1, "role": "test", "mod": "rig-0.1.0"})
    fresh.send("move.step", {"dir": "left", "n": 1})
    w.rig.step(3)
    answers = [m for m in fresh.messages() if m["type"] == "player.pos" and m["data"].get("n") == 1]
    assert [pos(m) for m in answers] == [("TEST_TOWN", 4, 4, "left", "walk", WALK)]


def test_after_a_hot_reload_the_new_instance_walks():
    w = Walk(start=("TEST_TOWN", 5, 4, "down"))
    w.answer(w.step("left"))
    w.settle()
    w.rig.b.reload()                          # F5: a new instance, the old one's hooks are gone
    w.rig.step(2)
    peer = w.rig.connect()
    peer.hello()
    peer.messages()
    peer.send("move.step", {"dir": "down", "n": 1})
    peer.send("move.step", {"dir": "up", "n": 2})
    peer.send("move.step", {"dir": "up", "n": 3})
    got = {}
    for _ in range(60):
        w.rig.step()
        for m in peer.messages():
            if m["type"] in ("player.pos", "player.blocked") and "n" in m["data"]:
                got.setdefault(m["data"]["n"], []).append(m)
    assert sorted(got) == [1, 2, 3] and all(len(v) == 1 for v in got.values())
    assert [pos(got[n][0])[:3] for n in (1, 2)] == [("TEST_TOWN", 4, 5), ("TEST_TOWN", 4, 4)]
    blocked = got[3][0]                       # (4,3) is a wall: the new instance's hook saw the verdict
    assert (blocked["type"], blocked["data"]["reason"]) == ("player.blocked", "tile")


# ------------------------------------------------------------ the ride (BIKE_PLAN B1)


def test_a_ride_direction_is_pressed_and_kept_across_landings():
    w = Walk(start=("TEST_TOWN", 1, 6, "right"))
    w.E.save.onBike = True
    w.run(1)
    w.peer.send("move.ride", {"dir": "right", "b": False})
    out = w.run(40)
    assert not [m for m in out if m["type"] == "player.blocked"]  # nothing answered: a ride is no step
    starts = w.starts()
    assert len(starts) >= 3                                      # the engine walks on by itself
    t0 = starts[0]
    assert all(w.held(t) == "right" for t in range(t0, w.tick))  # never let go of on a landing
    assert all(m["data"]["kind"] == "bike" for m in out if m["type"] == "player.pos" and m["data"]["kind"] != "sync")
    w.peer.send("move.ride", {"dir": None, "b": False})
    w.settle()
    x = w.cell()[1]
    w.run(20)
    assert w.cell()[1] == x and w.held(w.tick) == ""             # released: it stops on a cell


def test_a_ride_is_released_off_idle_and_pressed_again():
    w = Walk(start=("TEST_TOWN", 1, 6, "right"))
    w.E.save.onBike = True
    w.peer.send("move.ride", {"dir": "right", "b": True})
    w.run(3)
    assert w.E.game.input.state.b is True
    w.E.push(w.E.text())
    w.run(2)
    assert w.held(w.tick) == "" and w.E.game.input.state.b is not True
    w.E.pop()
    w.run(3)
    assert w.held(w.tick) == "right,b" and w.E.game.input.state.b is True


def test_a_step_is_refused_while_a_ride_direction_is_held():
    w = Walk(start=("TEST_TOWN", 1, 6, "right"))
    w.E.save.onBike = True
    w.peer.send("move.ride", {"dir": "right", "b": False})
    w.run(2)
    n = w.step("down")
    blocked = w.answer(n)
    assert (blocked["type"], blocked["data"]["reason"]) == ("player.blocked", "script")
    w.peer.send("move.ride", {"dir": None, "b": False})
    w.settle()
    n = w.step("down")                                           # off the ride, steps are steps again
    assert w.answer(n)["type"] == "player.pos"


def test_a_ride_with_a_direction_drops_the_waiting_steps():
    w = Walk(start=("TEST_TOWN", 1, 6, "right"))
    first = w.step("right")
    w.answer(first)
    second, third = w.step("right"), w.step("right")
    w.peer.send("move.ride", {"dir": "down", "b": False})
    w.run(3)
    blocked = {m["data"]["n"]: m["data"]["reason"] for t, m in w.log if m["type"] == "player.blocked"}
    assert blocked == {second: "script", third: "script"}


def test_b_is_pressed_with_the_direction_in_one_step():
    w = Walk(start=("TEST_TOWN", 5, 4, "down"))
    w.E.save.onBike = True
    w.run(1)
    w.peer.send("move.ride", {"dir": "left", "b": True})
    w.run(1)
    t = w.tick
    assert w.held(t) == "left,b"   # both in the one step


def test_a_bad_ride_is_refused():
    w = Walk(start=("TEST_TOWN", 5, 4, "down"))
    for bad in ({"dir": "north", "b": False}, {"dir": "up"}, {"dir": "up", "b": 1}):
        seq = w.peer.send("move.ride", bad)
        out = w.run(1)
        assert [m["data"]["code"] for m in out if m["type"] == "error"] in (["bad_message"], ["schema"], ["invalid"]), out


# ------------------------------------------------------------ STEP SPEED (STEP_SPEED_PLAN.md S1, S2)


class Owns:
    """The driver alone (act/kb_move.lua) on hand-made facts: what driver.ownsStep says as a step goes."""

    def __init__(self):
        self.b = Bridge()
        Move = self.b.module("act/kb_move.lua")
        Player = self.b.module("observe/kb_player.lua")
        self.d = self.b.eval("""function(Move, Player) return Move.new({
          engine = { setFacing = function() return true end, buttonDown = function() return true end },
          Player = Player, press = function(btn) return btn end, release = function() end }) end""")(Move, Player)
        self.link = self.b.eval("{ live = true, generation = 1 }")
        self.x, self.y = 3, 4

    def facts(self, moving=False, facing="right", tx=None, ty=None):
        f = self.b.eval("{}")
        f[b"inGame"], f[b"map"], f[b"x"], f[b"y"] = True, b"TEST_TOWN", self.x, self.y
        f[b"moving"], f[b"facing"] = moving, facing.encode()
        if tx is not None:
            f[b"tx"], f[b"ty"] = tx, ty
        return f

    def step(self, mode="idle", link=True, **kw):
        out = self.b.eval("{}")
        f = self.facts(**kw)
        if kw.get("moving") and kw.get("tx") is not None:
            # the observer's message for a step that just started
            msg = self.b.eval("""function(x, y, facing) return { "player.pos", { map = "TEST_TOWN", x = x, y = y,
              facing = facing, kind = "walk", frames = 8 }, "step" } end""")(kw["tx"], kw["ty"], f[b"facing"])
            out[1] = msg
        self.d.step(f, mode.encode(), self.b.eval("{}"), out, self.link if link else None)
        return out

    def request(self, direction, n, push=None):
        data = self.b.eval("{}")
        data[b"dir"], data[b"n"] = direction.encode(), n
        if push is not None:
            data[b"push"] = push
        assert self.d.request(data, 1) is True

    def owns(self, direction=None):
        return self.d.ownsStep(direction.encode() if direction else None)


def test_owns_step_follows_the_drivers_own_step():
    o = Owns()
    o.request("right", 1)
    assert o.owns("right") is False, "before the press"
    o.step()
    assert o.owns("right") is True and o.owns() is True, "pressed, in idle"
    assert o.owns("up") is False, "another direction"
    o.step(moving=True, tx=4, ty=4)
    assert o.owns("right") is True, "under way (the seam's second half reads it here)"
    o.step(moving=True, tx=4, ty=4)
    assert o.owns("right") is True
    o.x = 4
    o.step()                                      # landed, nothing waits
    assert o.owns("right") is False


def test_owns_step_on_the_landing_poll_is_the_chained_step():
    o = Owns()
    o.request("right", 1)
    o.request("up", 2)
    o.step()
    o.step(moving=True, tx=4, ty=4)
    o.x = 4
    o.step(facing="right")                        # the landing poll: the waiting step starts in the same step
    assert o.owns("up") is True and o.owns("right") is False


def test_owns_step_is_false_for_a_push_off_idle_without_a_link_and_while_riding():
    o = Owns()
    o.request("left", 1, push=True)
    o.step()
    assert o.owns("left") is False, "a push"
    o = Owns()
    o.request("right", 1)
    o.step()
    o.step(mode="cutscene")
    assert o.owns("right") is False and o.owns() is False, "off idle"
    o = Owns()
    o.request("right", 1)
    o.step()
    o.step(link=False)
    assert o.owns("right") is False, "no link"
    o = Owns()
    ride = o.b.eval("{ dir = 'up', b = false }")
    assert o.d.ride(ride, 1) is True
    o.request("up", 1)
    o.step()
    assert o.owns("up") is False and o.owns() is False, "a ride direction held: the step is refused"


def diag(w, steps, cadence, dirs=("right", "down")):
    """The client at 45 degrees: one move.step per cell boundary, every `cadence` logic steps (16 / sqrt(2) ~ 11.3
    walking: 11, 12, 11, ...)."""
    sent = []
    acc = 0.0
    for i in range(steps):
        sent.append(w.step(dirs[i % len(dirs)]))
        acc += cadence
        w.run(int(acc) - int(acc - cadence))
    w.settle()
    return sent


def script_refusals(w, sent):
    ans = w.answers()
    return sum(1 for n in sent for m in ans.get(n, []) if m["type"] == "player.blocked" and m["data"]["reason"] == "script")


def test_a_diagonal_at_minecrafts_cadence_drops_nothing_at_2x():
    w = Walk(start=("TEST_FIELD", 2, 2, "right"))
    sent = diag(w, 30, 16 / 2 ** 0.5)
    w.assert_no_drift()
    st = w.status()
    assert script_refusals(w, sent) == 0 and st["moveDropped"] == 0
    frames = {m["data"]["frames"] for n in sent for m in w.answers()[n] if m["type"] == "player.pos"}
    assert frames == {8}
    assert w.cell() == ("TEST_FIELD", 17, 17, "down")
    assert (st["moveSpeedPct"], st["moveFastSteps"], st["moveFastPassed"]) == (200, 30, 0)


def test_the_same_diagonal_at_1x_falls_behind_and_drops_steps():
    w = Walk(start=("TEST_FIELD", 2, 2, "right"), mod_options={"step_speed": 100})
    sent = diag(w, 30, 16 / 2 ** 0.5)
    w.assert_no_drift()
    st = w.status()
    assert st["moveDropped"] > 0 and script_refusals(w, sent) == st["moveDropped"]
    frames = {m["data"]["frames"] for n in sent for m in w.answers()[n] if m["type"] == "player.pos"}
    assert frames == {16}
    assert (st["moveSpeedPct"], st["moveFastSteps"]) == (100, 0)


def test_step_speed_from_configure_and_from_the_manager():
    w = Walk(start=("TEST_FIELD", 2, 2, "right"))
    w.rig.status.configure(w.rig.b.to_lua({"stepSpeed": 100}))
    assert pos(w.answer(w.step("right")))[5] == 16
    w.settle()
    w.rig.b.emit("mod.options_changed", w.rig.b.to_lua({"mod": "gen1-bridge", "key": "step_speed", "value": 150}))
    assert pos(w.answer(w.step("right")))[5] == 11
    w.settle()
    w.rig.b.emit("mod.options_changed", w.rig.b.to_lua({"mod": "gen1-bridge", "key": "step_speed", "value": 7}))
    assert pos(w.answer(w.step("right")))[5] == 8, "an invalid value is 2X"
    w.settle()
    assert w.status()["moveSpeedPct"] == 200
    lines = w.rig.b.log_lines()
    assert "[info] [gen1-bridge] move.speed pct=150" in lines
    assert sum(1 for x in lines if "move.speed_option_invalid" in x) == 1


def test_a_ledge_hop_keeps_its_32_frames_and_counts_as_passed():
    w = Walk(start=("TEST_TOWN", 6, 4, "down"))
    n = w.step("down")
    assert pos(w.answer(n)) == ("TEST_TOWN", 6, 6, "down", "ledge", 32)
    w.settle()
    st = w.status()
    assert st["moveFastPassed"] >= 1 and st["moveFastSteps"] == 0


def test_a_step_nobody_asked_for_keeps_gen1s_timing():
    # the user's own press in the hidden window: the driver holds nothing, the engine's 16 frames stand
    w = Walk(start=("TEST_FIELD", 2, 2, "right"))
    gi = w.E.game.input
    gi.sourcePress(gi, b"right", b"key:d")
    w.run(3)
    gi.sourceRelease(gi, b"right", b"key:d")
    w.settle()
    steps = [m for t, m in w.log if m["type"] == "player.pos" and m["data"]["kind"] == "walk"]
    assert steps and {m["data"]["frames"] for m in steps} == {16}
    assert w.status()["moveFastSteps"] == 0
