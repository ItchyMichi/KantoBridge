"""observe/kb_warp.lua on plain facts and events, and the movement internals in lib/kb_engine.lua
(facing, held buttons, the player's collision verdicts, warp departures, the new observe facts)
on the fake engine. All synthetic."""

import pytest

from kblua import Bridge
from rig import Rig

pytestmark = pytest.mark.lua

STAND = {"inGame": True, "top": "overworld", "map": "TEST_TOWN", "x": 3, "y": 4, "facing": "down",
         "moving": False}


class Tracker:
    def __init__(self):
        self.b = Bridge()
        self.Warp = self.b.module("observe/kb_warp.lua")
        self.t = self.Warp.new(self.b.table())

    def step(self, ev=None, **facts):
        f = dict(STAND)
        f.update(facts)
        out = self.t.step(self.b.to_lua(f), self.b.to_lua(ev or {}))
        result = []
        for m in out.values():
            d = m[2]
            data = {k.decode(): (v.decode() if isinstance(v, bytes) else v) for k, v in d.items() if k != b"to"}
            if d.to is not None:
                data["to"] = {k.decode(): (v.decode() if isinstance(v, bytes) else v) for k, v in d.to.items()}
            result.append(data)
        return result


def test_a_warp_table_warp_starts_at_player_warped_and_arrives_at_map_entered():
    t = Tracker()
    assert t.step({"warped": {"kind": "stairs", "map": "TEST_HOUSE"}}, transitioning=True) == [
        {"phase": "start", "kind": "stairs"}]
    assert t.step(transitioning=True, top="busy") == []
    arrive = t.step({"entered": [{"map": "TEST_UP", "via": "warp"}]}, map="TEST_UP", x=1, y=1)
    assert arrive == [{"phase": "arrive", "kind": "stairs",
                       "to": {"map": "TEST_UP", "x": 1, "y": 1, "facing": "down"}}]
    assert t.step(map="TEST_UP", x=1, y=1) == []


@pytest.mark.parametrize("departure, kind", [
    ({"kind": "door", "map": "TEST_TOWN"}, "door"),
    ({"kind": "edge", "map": "TEST_HOUSE"}, "edge"),
    ({"kind": "pad", "map": "TEST_GYM"}, "pad"),
    ({"kind": "hole", "map": "TEST_CAVE"}, "hole"),
    ({"kind": "edge", "map": "TEST_MART_ELEVATOR"}, "elevator"),
    ({"kind": "weird", "map": "TEST_TOWN"}, "door"),
    ({}, "door"),
])
def test_departure_kinds(departure, kind):
    t = Tracker()
    assert t.step({"warped": departure}) == [{"phase": "start", "kind": kind}]


def test_departures_before_the_fade_name_the_warp():
    t = Tracker()
    for _ in range(3):
        assert t.step(departure="fly", forcedPhase=True) == []
    assert t.step(transitioning=True) == [{"phase": "start", "kind": "fly"}]
    t2 = Tracker()
    assert t2.step(departure="teleport") == []
    assert t2.step(transitioning=True) == [{"phase": "start", "kind": "teleport"}]
    t3 = Tracker()
    assert t3.step(transitioning=True) == [{"phase": "start", "kind": "script"}]


def test_a_departure_that_ends_without_a_fade_is_forgotten():
    t = Tracker()
    assert t.step(departure="fly") == []
    assert t.step() == []  # nothing in flight any more
    assert t.step(transitioning=True) == [{"phase": "start", "kind": "script"}]


def test_blackout():
    t = Tracker()
    assert t.step({"blackout": True}, transitioning=True) == [{"phase": "start", "kind": "blackout"}]


def test_connections_reloads_and_boots_are_not_warps():
    t = Tracker()
    for via in ("connection", "reload", "boot"):
        assert t.step({"entered": [{"map": "TEST_ROUTE", "via": via}]}, map="TEST_ROUTE") == []


def test_a_warp_seen_only_at_its_arrival_still_gets_both_messages():
    t = Tracker()
    out = t.step({"entered": [{"map": "TEST_ROUTE", "via": "fly"}]}, map="TEST_ROUTE", x=2, y=2)
    assert [d["phase"] for d in out] == ["start", "arrive"] and {d["kind"] for d in out} == {"fly"}
    assert t.t.stats().late == 1


def test_a_warp_that_never_arrives_arrives_from_where_the_player_stands():
    t = Tracker()
    t.Warp.ARRIVE_TIMEOUT = 5
    assert t.step({"warped": {"kind": "door", "map": "TEST_TOWN"}}) == [{"phase": "start", "kind": "door"}]
    out = []
    for _ in range(7):
        out += t.step()
    assert out == [{"phase": "arrive", "kind": "door", "to": {"map": "TEST_TOWN", "x": 3, "y": 4, "facing": "down"}}]
    assert t.t.stats().timeouts == 1


def test_the_fade_in_after_an_arrival_starts_no_warp():
    # Emerald fades in after the map.entered that ended its warp (the user's item 11: a phantom warp after every door)
    t = Tracker()
    assert t.step(transitioning=True) == [{"phase": "start", "kind": "script"}]
    arrive = t.step({"entered": [{"map": "TEST_UP", "via": "warp"}]}, transitioning=True, map="TEST_UP", x=1, y=1)
    assert [d["phase"] for d in arrive] == ["arrive"]
    for _ in range(5):
        assert t.step(transitioning=True, map="TEST_UP", x=1, y=1) == []
    assert t.step(map="TEST_UP", x=1, y=1) == []
    assert t.step(transitioning=True, map="TEST_UP", x=1, y=1) == [{"phase": "start", "kind": "script"}], "a new one, later"
    assert t.t.stats().tails == 5


def test_a_fade_with_no_map_change_settles_where_the_player_stands():
    # a cutscene's fade (Emerald's clock, a battle's end): over soon after the fade, not after the timeout
    t = Tracker()
    t.Warp.SETTLE = 3
    assert t.step(transitioning=True) == [{"phase": "start", "kind": "script"}]
    assert t.step(transitioning=True, top="screen") == []
    out = []
    for _ in range(4):
        out += t.step()
    assert out == [{"phase": "arrive", "kind": "script", "to": {"map": "TEST_TOWN", "x": 3, "y": 4, "facing": "down"}}]
    stats = t.t.stats()
    assert stats.settled == 1 and stats.timeouts == 0


def test_warps_with_a_departure_never_settle_early():
    # a door's, Fly's: only a map.entered or the timeout ends them
    for first in ({"warped": {"kind": "door", "map": "TEST_TOWN"}}, None):
        t = Tracker()
        t.Warp.SETTLE = 2
        t.Warp.ARRIVE_TIMEOUT = 50
        if first:
            assert t.step(first) == [{"phase": "start", "kind": "door"}]
        else:
            assert t.step(departure="fly") == []
            assert t.step(transitioning=True) == [{"phase": "start", "kind": "fly"}]
        out = []
        for _ in range(10):
            out += t.step()
        assert out == [] and t.t.stats().settled == 0


def test_an_arrival_that_doesnt_fit_the_schema_is_held_back():
    t = Tracker()
    t.step({"warped": {"kind": "door", "map": "TEST_TOWN"}})
    assert t.step({"entered": [{"map": "lower", "via": "warp"}]}, map="lower") == []
    assert t.t.stats().heldBack == 1 and not t.t.active()


# ------------------------------------------------------------ kb_engine movement internals


def engine_for(rig):
    b = rig.b
    Engine = b.module("lib/kb_engine.lua")
    eng = Engine.new(b.engine_opts(ui=rig.E.ui))  # MG2: the loader picks lib/engine/gen1.lua
    eng.setGame(rig.E.game)
    return eng


def test_set_facing_turns_a_standing_player_only():
    rig = Rig()
    rig.world("TEST_TOWN", 3, 4, "down")
    eng = engine_for(rig)
    p = rig.E.ow.player
    p.bumpFrames = 9
    assert eng.setFacing(b"left") is True and p.facing == b"left" and p.bumpFrames is None
    assert eng.setFacing(b"north") is False and p.facing == b"left"
    rig.E.startStep(b"up")
    assert eng.setFacing(b"right") is False and p.facing == b"up"   # mid-step: refused
    rig.E.leaveWorld()
    assert eng.setFacing(b"down") is False


def test_button_down_reads_the_game_input():
    rig = Rig()
    eng = engine_for(rig)
    gi = rig.E.game.input
    assert eng.buttonDown(b"up") is False
    gi.sourcePress(gi, b"up", b"key:w")
    assert eng.buttonDown(b"up") is True
    gi.reset(gi)
    assert eng.buttonDown(b"up") is False


def test_player_verdicts_are_told_from_npc_verdicts():
    rig = Rig()
    rig.world()
    eng = engine_for(rig)
    eng.observe()  # caches the live player
    b = rig.b
    ctx = b.eval("function(m, d, r) return { mover = m, dir = d, reason = r, fromX = 1, fromY = 2 } end")
    v = eng.playerVerdict(ctx(rig.E.ow.player, b"up", b"tile"), False)
    assert (v.allowed, v.dir, v.reason) == (False, b"up", b"tile")
    assert eng.playerVerdict(ctx(b.table(), b"up", b"tile"), False) is None   # an NPC
    odd = eng.playerVerdict(ctx(rig.E.ow.player, b"sideways", b"not a symbol!"), True)
    assert (odd.allowed, odd.dir, odd.reason) == (True, None, None)


@pytest.mark.parametrize("cell, facing, kind", [
    ("D", "up", "door"), ("S", "down", "stairs"), ("P", "up", "pad"), ("H", "up", "hole"),
    ("M", "down", "edge"),     # an exit mat, facing off the map
    ("M", "left", "door"),     # a collision warp facing into the map (a gate doorway)
])
def test_warp_departure_kinds(cell, facing, kind):
    rig = Rig()
    rows = ["#####", "#...#", "#." + cell + ".#"] if cell != "M" else ["#####", "#...#", "#." + cell + "#."]
    rig.simulate({"maps": {"TEST_MAP": {"rows": rows, "warps": [{"x": 2, "y": 2, "to": "TEST_MAP", "tx": 1, "ty": 1}]}},
                  "start": {"map": "TEST_MAP", "x": 2, "y": 2, "facing": facing}})
    eng = engine_for(rig)
    d = eng.warpDeparture()
    assert (d.kind.decode(), d.map.decode(), d.x, d.y, d.facing.decode()) == (kind, "TEST_MAP", 2, 2, facing)
    rig.E.leaveWorld()
    assert eng.warpDeparture() is None


def test_observe_reports_hops_boulders_and_departures():
    rig = Rig()
    rig.world("TEST_TOWN", 3, 4, "down")
    eng = engine_for(rig)
    E, b = rig.E, rig.b
    p = E.ow.player
    p.ledgeHop, p.hopTotal = True, 32
    facts = eng.observe()
    assert facts.ledge is True and facts.hopFrames == 32
    p.ledgeHop = None
    assert eng.observe().hopFrames is None
    boulder = E.npc(b"TEST_TOWN_obj_1", b"SPRITE_BOULDER", 4, 4)
    E.ow.scriptMoves[1] = b.eval("function(e) return { entity = e, dir = 'right', remaining = 1 } end")(boulder)
    assert eng.observe().boulderPush == b"right"
    boulder[b"def"][b"pushable"] = False  # an explicit "not pushable" wins over the sprite
    assert eng.observe().boulderPush is None
    E.ow.scriptMoves = b.table()
    E.ow.map[b"def"] = b.to_lua({"id": "TEST_TOWN", "tileset": "OVERWORLD"})
    for field, departure in (("flyFade", b"fly"), ("flyAnim", b"fly"), ("teleportOut", b"teleport"),
                             ("holeFall", b"hole")):
        E.ow[field.encode()] = b.table()
        assert eng.observe().departure == departure, field
        E.ow[field.encode()] = None
    assert eng.observe().departure is None
    # M8: the spin out of a dungeon is a Dig (TELEPORT works only outside, PartyMenu.lua:708-735),
    # and a spin gen1-bridge marked keeps its mark (the bag's ESCAPE ROPE)
    E.ow.map[b"def"].tileset = b"CAVERN"
    E.ow.teleportOut = b.table()
    assert eng.observe().departure == b"dig"
    eng.markDeparture(b"rope")
    assert eng.observe().departure == b"rope"
    E.ow.teleportOut = b.table()  # a new spin: the mark was for the old one
    assert eng.observe().departure == b"dig"
    E.ow.teleportOut = None
    E.startStep(b"down")
    p.progress = 5
    assert eng.observe().progress == 5
