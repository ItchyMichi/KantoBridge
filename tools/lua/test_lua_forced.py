"""Forced movement (M8, PLAN M8 item 3; docs/PROTOCOL.md "Movement rules") through main.lua on the
simulated overworld (fake_world.lua + fake_field.lua): a spinner's slide (player.pos kind "spin",
48 frames a cell, mode forced), a Seafoam current's drag and push-back (kind "current", the
surfing step length), the surf mount's own scripted step (still "script"), a hole a map script
opens (warp kind "hole" without player.warped), and the brake on the Cycling Road while idle.
(Pads, holes through the warp table, ledges and Strength pushes: test_lua_move.py and
test_lua_field.py.) Every message is schema-checked strictly. All maps are made up."""

import pytest

from fieldsim import FieldGame

pytestmark = pytest.mark.lua

GYM = {
    "rows": [
        "########",
        "#......#",
        "#......#",  # a spinner at (3,2): right 2, then down 1
        "#......#",
        "#......#",  # a hole (1,4) a map script drops the player through
        "########",
    ],
}
SEA = {
    "rows": [
        "#########",
        "#..wwwww#",  # a current at (4,1): down 2
        "#..wwwww#",
        "#..wwwww#",  # the push-back cell (6,3): up 1 until EVENT_TEST_PLUG
        "#########",
    ],
    "tileset": "CAVERN",
}
MAPS = {"TEST_GYM": GYM, "TEST_SEA": SEA}
FIELD = {
    "spinners": {"TEST_GYM": [{"x": 3, "y": 2, "moves": [{"dir": "right", "count": 2}, {"dir": "down", "count": 1}]}]},
    # the currents run until their plug boulders fall (currentsDisabledByEvents; none listed would
    # mean "all set", as in the engine's allSet, OC:5182-5187)
    "seafoam": {"TEST_SEA": {"currents": [{"x": 4, "y": 1, "moves": [{"dir": "down", "count": 2}]}],
                             "currentsDisabledByEvents": ["EVENT_TEST_BOULDERS"],
                             "forcedExit": {"coords": [{"x": 6, "y": 3}], "activeUntilEvents": ["EVENT_TEST_PLUG"]}}},
}


def game(start, **kw):
    return FieldGame(start=start, maps=MAPS, field=FIELD, **kw)


def positions(g):
    return [(m["data"]["x"], m["data"]["y"], m["data"]["kind"], m["data"]["frames"], m["data"].get("n"))
            for m in g.log if m["type"] == "player.pos" and m["data"]["kind"] != "sync"]


def modes(g):
    return [m["data"]["mode"] for m in g.log if m["type"] == "player.state"]


def test_a_spinner_slides_the_player_cell_by_cell():
    g = game(("TEST_GYM", 2, 2, "right"))
    n = g.step("right")
    g.settle()
    assert positions(g) == [
        (3, 2, "walk", 8, n),        # the step onto the arrow, answered
        (4, 2, "spin", 48, None),     # the slide: 16 / 2 * 6 frames a cell (Player.lua:131-133)
        (5, 2, "spin", 48, None),
        (6 - 1, 3, "spin", 48, None),
    ]
    assert "forced" in modes(g) and modes(g)[-1] == "idle"
    assert g.cell() == ("TEST_GYM", 5, 3, "down")


def test_a_step_sent_during_the_slide_is_refused():
    g = game(("TEST_GYM", 2, 2, "right"))
    g.answer(g.step("right"))
    g.until(lambda m: m["type"] == "player.pos" and m["data"]["kind"] == "spin")
    late = g.answer(g.step("left"))
    assert (late["type"], late["data"]["reason"]) == ("player.blocked", "script")
    g.settle()


def test_a_seafoam_current_drags_a_surfer():
    g = game(("TEST_SEA", 3, 1, "right"))
    g.E.ow.player.surfing = True
    n = g.step("right")
    g.settle()
    assert positions(g) == [
        (4, 1, "surf", 8, n),
        (4, 2, "current", 16, None),
        (4, 3, "current", 16, None),
    ]
    assert g.cell()[:3] == ("TEST_SEA", 4, 3)


def test_the_push_back_is_a_current_until_its_event():
    g = game(("TEST_SEA", 5, 3, "right"))
    g.E.ow.player.surfing = True
    n = g.step("right")
    g.settle()
    assert positions(g) == [(6, 3, "surf", 8, n), (6, 2, "current", 16, None)]
    g.E.save.flags[b"EVENT_TEST_PLUG"] = True
    g.E.setMapSim(b"TEST_SEA", 5, 3, b"right")
    g.settle()
    n2 = g.step("right")
    g.settle()
    assert positions(g)[-1] == (6, 3, "surf", 8, n2), "plugged: no push-back"


def test_the_surf_mounts_own_step_is_no_current():
    g = game(("TEST_SEA", 2, 1, "right"))
    g.give_moves("SURF")
    g.give("SOULBADGE")
    assert g.field("surf") == {"ok": True, "kind": "surf"}
    g.read_texts()
    assert positions(g) == [(3, 1, "script", 16, None)]


def test_a_hole_a_map_script_opens_is_a_hole_warp():
    g = game(("TEST_GYM", 1, 3, "down"))
    g.E.mapScripts[b"TEST_GYM"] = g.b.eval("""function(E) return { onStep = function(E2, ow, x, y)
        if x == 1 and y == 4 then
          ow.player.inputLocked = true
          ow.holeFall = { frames = 2, go = function() E.startWarpTo("TEST_SEA", 2, 2, "down") end }
          return true
        end
      end } end""")(g.E)
    n = g.step("down")
    g.settle()
    assert [m["data"] for m in g.log if m["type"] == "warp"] == [
        {"phase": "start", "kind": "hole"},
        {"phase": "arrive", "kind": "hole", "to": {"map": "TEST_SEA", "x": 2, "y": 2, "facing": "down"}},
    ]
    assert positions(g)[0] == (1, 4, "walk", 8, n)


def test_the_brake_holds_b_only_while_idle_on_the_slope():
    g = game(("TEST_GYM", 4, 1, "down"))
    g.E.save.onBike = True
    g.E.ow.player.slopeMap = True
    g.send("move.hold", {"button": "b", "on": True})
    g.run(20)
    assert positions(g) == [], "braking: no roll"
    assert ("press", "b") in g.taps()
    g.send("move.hold", {"button": "b", "on": False})
    rolled = g.until(lambda m: m["type"] == "player.pos" and m["data"]["kind"] == "slope")["data"]
    assert (rolled["x"], rolled["y"], rolled["frames"]) == (4, 2, 8)
    state = [m["data"] for m in g.log if m["type"] == "player.state"][-1]
    assert state["bike"] is True and state["slope"] is True
