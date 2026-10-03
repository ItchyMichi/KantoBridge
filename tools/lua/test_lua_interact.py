"""act/kb_interact.lua (act.interact: face the cell and press A), through main.lua on the
simulated engine: the facing and the A tap in one logic step, the result from world.interacted,
the counter reach, the retry for an NPC caught mid-step, and every refusal. Every message the peer
receives is schema-checked (rig.Peer). All maps, NPCs and texts are made up."""

import pytest

from kblua import Bridge
from uisim import Game

pytestmark = pytest.mark.lua


def interact(g, x, y, map_id="TEST_ROOM"):
    return g.send("act.interact", {"map": map_id, "x": x, "y": y})


def result(g, seq, limit=200):
    for m in g.log:
        if m["type"] == "act.result" and m.get("re") == seq:
            return m["data"]
    return g.until(lambda m: m["type"] == "act.result" and m.get("re") == seq, limit=limit)["data"]


@pytest.fixture(scope="module")
def lua():
    b = Bridge()
    return b, b.module("act/kb_interact.lua")


@pytest.mark.parametrize("target, want", [
    ((3, 2), "up"), ((3, 4), "down"), ((2, 3), "left"), ((4, 3), "right"),
    ((3, 1), "up"),            # across the counter at (3, 2)
    ((5, 3), None), ((4, 4), None), ((3, 3), None), ((3, 6), None),
])
def test_direction(lua, target, want):
    b, Interact = lua
    counter = b.eval("function(x, y) return x == 3 and y == 2 end")
    got = Interact.direction(3, 3, target[0], target[1], counter)
    got = got[0] if isinstance(got, tuple) else got
    assert (got.decode() if isinstance(got, bytes) else got) == want


def test_talking_across_the_counter():
    g = Game()  # (3, 3) facing up; the clerk at (3, 1) behind the counter (3, 2)
    g.E.ow.player.facing = b"left"
    seq = interact(g, 3, 1)
    g.run()
    # one logic step: the facing is set and A tapped before the overworld reads input
    assert g.tapped() == ["a"] and g.E.ow.player.facing == b"up"
    assert result(g, seq) == {"ok": True, "kind": "npc"}
    assert g.received("ui.open")[0]["data"]["kind"] == "text"
    assert ("npc.face", {"id": "TEST_ROOM_obj_1", "facing": "down"}) in \
        [(m["type"], m["data"]) for m in g.log], "the clerk turns to the player"
    st = g.status()
    assert (st["interactRequests"], st["interactOk"], st["interactTaps"]) == (1, 1, 1)


def test_an_adjacent_npc_a_sign_and_nothing():
    g = Game(start=("TEST_ROOM", 6, 2, "down"))
    assert result(g, interact(g, 6, 1)) == {"ok": True, "kind": "npc"}
    g.send("ui.advance", {"ui": 1})
    g.send("ui.advance", {"ui": 1})
    g.until(lambda m: m["type"] == "ui.close", limit=300)
    g.run(3)
    assert result(g, interact(g, 5, 2)) == {"ok": False, "kind": "none", "reason": "nothing"}
    g.E.ow.player.cellX, g.E.ow.player.cellY = 8, 4
    g.run()
    assert result(g, interact(g, 8, 3)) == {"ok": True, "kind": "sign"}


@pytest.mark.parametrize("target, map_id, reason", [
    ((5, 5), "TEST_ROOM", "not_adjacent"),
    ((4, 4), "TEST_ROOM", "not_adjacent"),   # diagonal
    ((3, 1), "TEST_HALL", "wrong_map"),
])
def test_refusals(target, map_id, reason):
    g = Game()
    assert result(g, interact(g, target[0], target[1], map_id)) == {"ok": False, "reason": reason}
    assert g.tapped() == []


EDGE = {"TEST_EDGE": {"rows": ["....", "....", "...."]}}


@pytest.mark.parametrize("player, target", [
    ((0, 0), (-1, 0)), ((0, 0), (0, -1)),   # -1 near a west or north seam
    ((3, 2), (4, 2)), ((3, 2), (3, 3)),     # one past the east or south edge
])
def test_a_cell_across_a_map_seam_is_refused(player, target):
    g = Game(start=("TEST_EDGE", player[0], player[1], "down"), maps=EDGE)
    assert result(g, interact(g, target[0], target[1], "TEST_EDGE")) == {"ok": False, "reason": "off_map"}
    assert g.tapped() == [] and g.E.ow.player.facing == b"down", "not even a turn"


def test_a_cell_on_the_map_edge_is_fine():
    g = Game(start=("TEST_EDGE", 0, 0, "down"), maps=EDGE)
    assert result(g, interact(g, 1, 0, "TEST_EDGE")) == {"ok": False, "kind": "none", "reason": "nothing"}
    assert g.tapped() == ["a"]


def test_not_idle_while_a_screen_is_up():
    g = Game()
    g.push(g.E.textBox(g.s("BUSY"), g.opts()))
    g.run()
    assert result(g, interact(g, 3, 2)) == {"ok": False, "reason": "not_idle"}
    assert g.tapped() == []


def test_one_interaction_at_a_time():
    g = Game()
    first = interact(g, 3, 1)
    second = interact(g, 3, 1)
    g.run(3)
    assert result(g, second) == {"ok": False, "reason": "busy"}
    assert result(g, first) == {"ok": True, "kind": "npc"}


def test_it_waits_for_a_step_to_land():
    g = Game()  # (3, 3): walk right to (4, 3), then talk to the girl? no: face the counter row
    g.send("move.step", {"dir": "down", "n": 1})
    g.run(2)
    assert g.E.ow.player.moving
    seq = interact(g, 3, 5)   # adjacent to the landing cell (3, 4)
    g.run()
    assert g.tapped() == []
    assert result(g, seq) == {"ok": False, "kind": "none", "reason": "nothing"}
    assert g.tapped() == ["a"] and (g.E.ow.player.cellX, g.E.ow.player.cellY) == (3, 4)


def test_a_moving_npc_is_retried_once_it_stands():
    g = Game(start=("TEST_ROOM", 6, 2, "up"))
    girl = g.E.ow.npcs[2]
    # stepping into (6, 1) from (7, 1): the A press finds her on her target cell
    girl.cellX, girl.targetX, girl.targetY, girl.moving, girl.progress = 7, 6, 1, True, 0
    seq = interact(g, 6, 1)
    g.run(3)
    assert g.tapped() == ["a"] and g.depth() == 1, "no talk while she steps"
    data = result(g, seq)
    assert data == {"ok": True, "kind": "npc"} and g.tapped() == ["a", "a"]
    assert girl.cellX == 6 and not girl.moving, "the retry waited for her to land"
    assert g.status()["interactRetries"] == 1


def test_a_moving_npc_that_walks_out_of_reach():
    g = Game(start=("TEST_ROOM", 6, 2, "up"))
    girl = g.E.ow.npcs[2]
    girl.targetX, girl.targetY, girl.moving, girl.progress = 7, 1, True, 0
    seq = interact(g, 6, 1)
    assert result(g, seq) == {"ok": False, "kind": "npc", "reason": "npc_moved"}
    assert g.tapped() == ["a"]


def test_an_npc_that_keeps_moving_fails_after_one_retry():
    g = Game(start=("TEST_ROOM", 6, 2, "up"))
    girl = g.E.ow.npcs[2]
    girl.targetX, girl.targetY, girl.moving, girl.progress, girl.stepFrames = 7, 1, True, 0, 1000
    seq = interact(g, 6, 1)
    assert result(g, seq, limit=300) == {"ok": False, "kind": "npc", "reason": "npc_moving"}
    assert g.tapped() == ["a", "a"]


def test_a_tap_that_does_nothing_times_out():
    g = Game()
    g.E.overworldButtons = None  # an overworld that ignores A
    seq = interact(g, 3, 2)
    assert result(g, seq) == {"ok": False, "reason": "no_effect"}


def test_roles_and_bad_messages():
    g = Game(role="builder")
    seq = g.send("act.interact", {"map": "TEST_ROOM", "x": 3, "y": 2})
    g.run()
    assert [(m["data"]["code"], m["re"]) for m in g.received("error")] == [("role", seq)]
    g = Game()
    bad = [g.send("act.interact", d) for d in ({"map": "TEST_ROOM", "x": 3},
                                               {"map": "test_room", "x": 3, "y": 2},
                                               {"map": "TEST_ROOM", "x": 3.5, "y": 2})]
    g.run()
    assert [(m["data"]["code"], m["re"]) for m in g.received("error")] == [("bad_message", s) for s in bad]


def test_a_dropped_connection_takes_its_interaction_with_it():
    g = Game()
    g.E.ow.npcs[1].moving = True
    g.E.ow.npcs[1].targetX, g.E.ow.npcs[1].targetY, g.E.ow.npcs[1].stepFrames = 3, 1, 1000
    interact(g, 3, 1)
    g.run(2)
    g.connect()
    g.run(120)
    assert g.received("act.result") == [] and g.tapped() == ["a"]
