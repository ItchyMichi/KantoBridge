"""MG7 (docs/specs/GEN3_BRIDGE_PLAN.md §4.3, §4.4): the game3 adapter's facing, buttons, step verdicts
(the Player.tryMove tap), warp departures and interaction on the fake Gen 3 engine. Everything is made
up."""

import pytest

from adapter3 import Adapter3, plain

pytestmark = pytest.mark.lua

TAP = b"kantoBridgeTryMove"  # the tap's process-lifetime record on the Player module


def try_move(a, direction):
    """Player.update's call, through the module table (player.lua:1503)."""
    return a.E.Player.tryMove(direction.encode(), a.E.game, False)


# ------------------------------------------------------------ facing and buttons


def test_set_facing_turns_the_standing_player_through_script_face():
    a = Adapter3()
    assert a.eng.setFacing(b"up") is False  # not in the field
    a.field("EM_TEST_TOWN", 5, 6, "down")
    faced = a.b.eval("{}")
    original = a.E.Player.scriptFace
    a.E.Player.scriptFace = a.b.eval("function(rec, f) return function(dir) rec[#rec + 1] = dir f(dir) end end")(
        faced, original)
    assert a.eng.setFacing(b"up") is True and a.E.Player.facing == b"up" and plain(faced) == ["up"]
    assert a.eng.setFacing(b"up") is True and plain(faced) == ["up"]  # already facing: no turn
    assert a.eng.setFacing(b"north") is False and a.E.Player.facing == b"up"
    a.E.startStep(b"left", 16)
    assert a.eng.setFacing(b"down") is False and a.E.Player.facing == b"left"  # refused mid-step


@pytest.mark.parametrize("script_face", [None, "function() error('no face') end"])
def test_set_facing_falls_back_to_the_facing_field(script_face):
    a = Adapter3()
    a.field("EM_TEST_TOWN", 5, 6, "down")
    a.E.Player.scriptFace = a.b.eval(script_face) if script_face else None
    assert a.eng.setFacing(b"right") is True and a.E.Player.facing == b"right"


def test_button_down_reads_the_games_input():
    a = Adapter3()
    a.field()
    inp = a.E.game.input
    assert a.eng.buttonDown(b"b") is False
    inp.sourcePress(inp, b"b", b"mod:gen1-bridge:1")
    assert a.eng.buttonDown(b"b") is True and a.eng.buttonDown(b"a") is False
    inp.sourceRelease(inp, b"b", b"mod:gen1-bridge:1")
    assert a.eng.buttonDown(b"b") is False
    inp.isDown = a.b.eval("function() error('input gone') end")
    assert a.eng.buttonDown(b"b") is False
    a.E.game.input = None
    assert a.eng.buttonDown(b"b") is False


def test_no_display_pin_and_no_collision_hook_on_gen3():
    a = Adapter3()
    a.field()
    assert a.eng.displayPinned() is True and a.eng.pinDisplay() == 0 and a.eng.playerVerdict() is None


# ------------------------------------------------------------ the tryMove tap (§4.3)


def test_take_verdicts_installs_the_tap_once():
    a = Adapter3()
    original = a.E.Player.tryMove
    assert a.E.Player[TAP] is None
    assert plain(a.eng.takeVerdicts()) == {}
    tap = a.E.Player[TAP]
    assert tap is not None and a.same(tap.original, original) and a.same(a.E.Player.tryMove, tap.wrapper)
    a.eng.takeVerdicts()
    assert a.same(a.E.Player[TAP], tap) and a.same(a.E.Player.tryMove, tap.wrapper)
    assert a.same(tap.original, original)


@pytest.mark.parametrize("why, reason", [
    ("elevation", "tile"), ("entity", "entity"), ("bounds", "bounds"), ("tile", "tile"), ("water", "tile"),
    ("rotating_gate", "tile"), ("acro", "tile"), ("something_new", "tile"),
])
def test_a_blocked_try_is_a_verdict_in_the_protocols_words(why, reason):
    a = Adapter3()
    a.field("EM_TEST_TOWN", 5, 6, "down")
    a.eng.takeVerdicts()
    a.E.block(5, 7, why.encode())
    assert try_move(a, "down") == (b"blocked", why.encode())
    assert plain(a.eng.takeVerdicts()) == [{"allowed": False, "dir": "down", "reason": reason}]
    assert plain(a.eng.takeVerdicts()) == {}  # taken


def test_the_map_edge_is_bounds_and_a_step_is_no_verdict():
    a = Adapter3()
    a.field("EM_TEST_TOWN", 0, 6, "left", width=10, height=10)
    a.eng.takeVerdicts()
    assert try_move(a, "left") == (b"blocked", b"bounds")
    assert try_move(a, "right") == b"step" and a.E.Player.moving is True
    assert plain(a.eng.takeVerdicts()) == [{"allowed": False, "dir": "left", "reason": "bounds"}]


def test_other_results_and_odd_directions_are_no_verdicts():
    a = Adapter3()
    a.field()
    a.eng.takeVerdicts()
    for result in ("turned", "ledge", "door", "connection", "push"):
        a.E.Player[TAP].original = a.b.eval(f"function() return '{result}' end")
        assert try_move(a, "up") == result.encode()
    a.E.Player[TAP].original = a.b.eval("function() return 'blocked', 'tile' end")
    assert a.E.Player.tryMove(b"sideways", a.E.game, False) == (b"blocked", b"tile")
    assert plain(a.eng.takeVerdicts()) == {}


def test_the_wrapper_passes_the_originals_results_through_unchanged():
    a = Adapter3()
    a.field()
    a.E.Player.tryMove = a.b.eval("""function(dir, game, run)
      if dir == "up" then return "blocked", "entity", "extra", nil end
      if dir == "down" then return end
      return nil, nil
    end""")
    a.eng.takeVerdicts()
    count = a.b.eval("function(P, dir, g) return select('#', P.tryMove(dir, g, false)), P.tryMove(dir, g, false) end")
    assert count(a.E.Player, b"up", a.E.game) == (4, b"blocked", b"entity", b"extra", None)
    assert count(a.E.Player, b"down", a.E.game) == 0
    assert count(a.E.Player, b"left", a.E.game) == (2, None, None)
    assert plain(a.eng.takeVerdicts()) == [{"allowed": False, "dir": "up", "reason": "entity"}] * 2


def test_the_verdicts_keep_the_newest_sixteen():
    a = Adapter3()
    a.field("EM_TEST_TOWN", 5, 6)
    a.eng.takeVerdicts()
    a.E.block(5, 5, b"tile")
    a.E.block(5, 7, b"entity")
    for i in range(20):
        try_move(a, "up" if i < 10 else "down")
    got = plain(a.eng.takeVerdicts())
    assert len(got) == 16 and [v["dir"] for v in got] == ["up"] * 6 + ["down"] * 10


def test_a_hot_reload_points_the_tap_at_the_new_instance():
    a = Adapter3()
    a.field("EM_TEST_TOWN", 5, 6)
    a.eng.takeVerdicts()
    tap = a.E.Player[TAP]
    second = a.new_instance()
    second.takeVerdicts()
    assert a.same(a.E.Player[TAP], tap) and a.same(a.E.Player.tryMove, tap.wrapper)  # not wrapped twice
    a.E.block(5, 7, b"tile")
    try_move(a, "down")
    assert plain(second.takeVerdicts()) == [{"allowed": False, "dir": "down", "reason": "tile"}]
    assert plain(a.eng.takeVerdicts()) == {}


def test_a_failing_sink_never_breaks_the_engines_call():
    a = Adapter3()
    a.field("EM_TEST_TOWN", 5, 6)
    a.eng.takeVerdicts()
    a.E.Player[TAP].sink = a.b.eval("function() error('sink broke') end")
    a.E.block(5, 7, b"tile")
    assert try_move(a, "down") == (b"blocked", b"tile")


# ------------------------------------------------------------ warps (§4.3)


def departure(a):
    d = a.eng.warpDeparture()
    return None if d is None else plain(d)


@pytest.mark.parametrize("under, ahead, kind", [
    ("MB_TEST_PAD", None, "pad"),
    ("MB_TEST_HOLE", None, "hole"),
    ("MB_TEST_STAIRS", None, "stairs"),
    ("MB_TEST_ARROW", None, "edge"),
    (None, "MB_TEST_DOOR", "door"),
    (None, "MB_TEST_EXIT", "door"),    # another warp behaviour: a door
    (None, "MB_TEST_STAIRS", "stairs"),
    ("MB_TEST_HOLE", "MB_TEST_DOOR", "hole"),  # the cell under the player first
    (None, None, "door"),              # nothing recognised: a door
])
def test_warp_departure_kinds(under, ahead, kind):
    a = Adapter3()
    a.field("EM_TEST_HOUSE", 4, 5, "up")
    if under:
        a.E.behavior(4, 5, under.encode())
    if ahead:
        a.E.behavior(4, 4, ahead.encode())
    assert departure(a) == {"map": "EM_TEST_HOUSE", "x": 4, "y": 5, "facing": "up", "kind": kind}


def test_a_warp_to_its_own_map_says_so():
    # FA8 item 5: player.warped's toMap is the map left (a pad across the room): inMap, so the tracker settles it
    a = Adapter3()
    a.field("EM_TEST_HOUSE", 4, 5, "up")
    same = plain(a.eng.warpDeparture(a.lua({"toMap": "EM_TEST_HOUSE", "warp": {"kind": "rse_teleport_tile", "map": "EM_TEST_HOUSE"}})))
    assert same["inMap"] is True and same["kind"] == "pad"
    other = plain(a.eng.warpDeparture(a.lua({"toMap": "EM_TEST_TOWN", "warp": {"kind": "door", "map": "EM_TEST_TOWN"}})))
    assert "inMap" not in other


def test_no_departure_outside_the_field():
    a = Adapter3()
    assert departure(a) is None


def test_a_requested_warp_is_a_script_warp_unless_it_stands_on_a_warp_tile():
    # Warp.request (a script's or a mod's warp, and the step warps the engine hands it: ladders, cave doors, arrows)
    # announces kind "warp": the tile under the player names it, else a script warp (never the tile in front: a
    # script's warp facing a door is no door's). request marks it for a cave shaft's take (HEIGHTS_ALL_GAMES_PLAN W3).
    a = Adapter3()
    a.field("EM_TEST_HOUSE", 4, 5, "up")
    a.E.behavior(4, 4, b"MB_TEST_STAIRS")
    warp = lambda kind: plain(a.eng.warpDeparture(a.lua({"warp": {"kind": kind, "map": "EM_TEST_TOWN", "x": 1, "y": 1}})))  # noqa: E731
    requested = warp("warp")
    assert requested["kind"] == "script" and requested["request"] is True
    assert warp("stairs")["kind"] == "stairs" and "request" not in warp("stairs")
    a.E.behavior(4, 5, b"MB_LADDER")
    assert warp("warp")["kind"] == "ladder" and warp("warp")["request"] is True


@pytest.mark.parametrize("under, engine_kind, kind", [
    ("MB_LADDER", "warp", "ladder"),                # Emerald's own behaviours by the engine's names (FA8 item 4)
    ("MB_AQUA_HIDEOUT_WARP", "rse_teleport_tile", "pad"),
    ("MB_CRACKED_FLOOR_HOLE", "fall", "hole"),
    (None, "fall", "hole"),                         # the engine's names where no tile says
    (None, "dive", "dive"),
    (None, "escalator", "escalator"),
    (None, "escape_rope", "rope"),
    (None, "lavaridge_b1f", "pad"),
    ("MB_TEST_PAD", "teleport", "pad"),             # the tile first
    (None, "something_new", "door"),
])
def test_warp_departure_kinds_from_emeralds_behaviours_and_the_engines_names(under, engine_kind, kind):
    a = Adapter3()
    a.field("EM_TEST_HOUSE", 4, 5, "up")
    if under:
        a.E.behavior(4, 5, under.encode())
    d = plain(a.eng.warpDeparture(a.lua({"warp": {"kind": engine_kind, "map": "EM_TEST_TOWN", "x": 1, "y": 1}})))
    assert d["kind"] == kind


# ------------------------------------------------------------ interaction (§4.4)


def test_interacted_facts_from_world_interacted():
    a = Adapter3()
    a.field()
    facts = lambda payload: plain(a.eng.interactedFacts(a.lua(payload)))  # noqa: E731
    npc = facts({"mapId": "EM_TEST_TOWN", "x": 3, "y": 4, "kind": "npc", "target": {"localId": 2, "moving": True}})
    assert npc == {"map": "EM_TEST_TOWN", "x": 3, "y": 4, "kind": "npc", "npcId": "O2", "npcMoving": True}
    assert facts({"mapId": "EM_TEST_TOWN", "x": 1, "y": 1, "kind": "sign", "target": "SIGN_KEY"}) == {
        "map": "EM_TEST_TOWN", "x": 1, "y": 1, "kind": "sign"}
    assert facts({"mapId": "EM_TEST_TOWN", "x": 2, "y": 2, "kind": "hidden_item"})["kind"] == "hidden_item"
    odd = facts({"mapId": "em test", "x": 2, "y": 2, "kind": "Not-A-Kind"})
    assert odd == {"x": 2, "y": 2, "kind": "none"}
    no_id = facts({"mapId": "EM_TEST_TOWN", "x": 2, "y": 2, "kind": "npc", "target": {"moving": False}})
    assert no_id["npcMoving"] is False and "npcId" not in no_id
    assert a.eng.interactedFacts(None) is None


def test_cell_in_map_reads_the_layouts_size():
    a = Adapter3()
    assert a.eng.cellInMap(1, 1) is None
    a.field("EM_TEST_TOWN", 5, 6, width=12, height=8)
    assert [a.eng.cellInMap(x, y) for x, y in [(0, 0), (11, 7), (12, 3), (3, 8), (-1, 0)]] == [
        True, True, False, False, False]
    a.E.Map._def = a.lua({"width": 4, "height": 4})  # no midLayout: the def's own size
    assert (a.eng.cellInMap(3, 3), a.eng.cellInMap(4, 3)) == (True, False)
    a.E.Map._def = None
    assert a.eng.cellInMap(1, 1) is None


def test_counter_cell_by_the_behaviours_name():
    a = Adapter3()
    assert a.eng.counterCell(3, 2) is False
    a.field()
    a.E.behavior(3, 2, b"MB_COUNTER")
    assert (a.eng.counterCell(3, 2), a.eng.counterCell(3, 3)) == (True, False)
