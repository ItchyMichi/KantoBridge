"""MG9 (docs/specs/GEN2_BRIDGE_PLAN.md G2A, §5.3, §5.4): the gen2 adapter's movement, warps and interaction
(gen1-bridge/lib/engine/gen2/move.lua) on the fake Gen 2 engine: the player's verdicts from
movement.collision (NPC movers left out), facing without the 4-frame turn, held buttons (a tapped direction
never moves on Gen 2), the warp kinds by COLL byte, what an A press hit, the map's cells and counters.
Everything is made up."""

import pytest

from adapter2 import Adapter2, plain

pytestmark = pytest.mark.lua


def ctx(a, mover, reason, d="down"):
    w = a.E.game.world
    return a.lua({"map": w.map, "mover": mover, "dir": d, "fromX": 5, "fromY": 6, "toX": 5, "toY": 7,
                  "reason": reason})


def test_verdicts_are_the_players_only():
    a = Adapter2()
    a.field()
    w = a.E.game.world
    verdict = plain(a.eng.playerVerdict(ctx(a, w.player, "tile"), False))
    assert verdict == {"allowed": False, "dir": "down", "reason": "tile"}
    assert plain(a.eng.playerVerdict(ctx(a, w.player, None), True)) == {"allowed": True, "dir": "down"}
    npc = a.E.addNpc(1, a.lua({"x": 3, "y": 4}))
    assert a.eng.playerVerdict(ctx(a, w.npcs[1], "entity"), False) is None, "an NPC's own step"
    assert npc.index == 1
    assert plain(a.eng.playerVerdict(ctx(a, w.player, "Bad Reason"), False)) == {"allowed": False, "dir": "down"}


def test_the_fake_reads_held_directions_as_the_engine_does():
    # World:pollInput reads isDown only (World.lua:10368-10381): a tap never moves or turns; a short hold
    # of another direction turns (4 frames), a longer one turns and steps (G2-S2)
    a = Adapter2()
    a.field("TEST_TOWN", 5, 6, "down")
    inp = a.E.game.input
    inp.sourcePress(inp, b"left", b"tap")
    inp.sourceRelease(inp, b"left", b"tap")
    a.tick(3)
    p = a.E.game.world.player
    assert (p.facing, p.cellX, p.moving) == (b"down", 5, False)
    a.press("left")
    a.tick()
    a.release("left")
    a.tick(8)
    assert (p.facing, p.cellX, p.moving) == (b"left", 5, False)
    a.press("up")
    a.tick(6)
    a.release("up")
    a.tick(20)
    assert (p.facing, p.cellY) == (b"up", 5)


def test_set_facing_turns_without_the_four_frame_turn():
    a = Adapter2()
    a.field("TEST_TOWN", 5, 6, "down")
    p = a.E.game.world.player
    p.bumpFrames = 1
    assert a.eng.setFacing(b"right") is True and p.facing == b"right" and p.bumpFrames is None
    a.press("right")  # facing it already: the held direction steps at once
    a.tick()
    assert p.moving is True and p.turnTimer == 0
    assert a.eng.setFacing(b"up") is False, "mid-step"
    assert a.eng.setFacing(b"sideways") is False


def test_button_down():
    a = Adapter2()
    a.field()
    assert a.eng.buttonDown(b"a") is False
    a.press("a")
    assert a.eng.buttonDown(b"a") is True
    a.release("a")
    assert a.eng.buttonDown(b"a") is False


@pytest.mark.parametrize("under, ahead, kind", [
    (0x71, None, "door"), (0x7b, None, "door"), (0x7a, None, "stairs"), (0x72, None, "stairs"), (0x7c, None, "pad"),
    (0x60, None, "hole"), (0x70, None, "edge"), (0x7e, None, "edge"), (None, 0x71, "door"), (None, None, "door"),
])
def test_the_warp_kind_comes_from_the_coll_byte(under, ahead, kind):
    a = Adapter2()
    a.field("TEST_TOWN", 5, 6, "down")
    if under is not None:
        a.E.coll(b"TEST_TOWN", 5, 6, under)
    if ahead is not None:
        a.E.coll(b"TEST_TOWN", 5, 7, ahead)
    assert plain(a.eng.warpDeparture()) == {"map": "G2_TEST_TOWN", "x": 5, "y": 6, "facing": "down", "kind": kind}


def test_no_departure_out_of_the_field():
    a = Adapter2()
    assert a.eng.warpDeparture() is None


def test_what_an_a_press_hit():
    a = Adapter2()
    a.field()
    target = a.lua({"def": {"index": 3}, "moving": True})
    facts = plain(a.eng.interactedFacts(a.lua({"mapId": "TEST_TOWN", "x": 5, "y": 7, "kind": "npc", "target": target})))
    assert facts == {"map": "G2_TEST_TOWN", "x": 5, "y": 7, "kind": "npc", "npcId": "O3", "npcMoving": True}
    sign = plain(a.eng.interactedFacts(a.lua({"mapId": "TEST_TOWN", "x": 2, "y": 2, "kind": "sign"})))
    assert sign == {"map": "G2_TEST_TOWN", "x": 2, "y": 2, "kind": "sign"}
    odd = plain(a.eng.interactedFacts(a.lua({"mapId": "TEST_TOWN", "x": 2, "y": 2, "kind": "Not A Kind"})))
    assert odd["kind"] == "none"
    assert a.eng.interactedFacts(None) is None


def test_the_maps_cells_and_counters():
    a = Adapter2()
    assert a.eng.cellInMap(0, 0) is None, "no map out of the field"
    a.field("TEST_TOWN", 5, 6)
    assert (a.eng.cellInMap(0, 0), a.eng.cellInMap(19, 19), a.eng.cellInMap(20, 0), a.eng.cellInMap(-1, 3)) == (
        True, True, False, False)
    a.E.coll(b"TEST_TOWN", 5, 8, 0x90)
    assert a.eng.counterCell(5, 8) is True and a.eng.counterCell(5, 9) is False
