"""FOREIGN_POKEMON_PLAN FP7 (§4.2, §4.8, §5): Emerald's field actions from Minecraft (gen1-bridge/lib/engine/game3/
fieldmoves.lua: the party menu's path, FieldMoves.fromMenu then Field.executeFieldMove; the dismount, Fly's own map, the
bike, the rope), Lanette's PC as Cobblemon's (the storage special held until pc.closed), and Emerald's details both ways
(shininess, nature and gender through the personality, friendship) on the fake game3 engine. Everything is made up."""

import pytest

from adapter3 import Adapter3, plain

pytestmark = pytest.mark.lua

UID1 = "11111111-1111-4111-8111-111111111111"
MACH_BIKE, ESCAPE_ROPE = 259, 85


def adapter(live=True):
    a = Adapter3()
    s = a.field()
    a.bridge = a.b.eval("function(live) return { live = function() return live end, battle = function() return nil end } end")(live)
    a.eng.attachBridge(a.bridge)
    return a, s


def member(a, moves=(33,), **extra):
    e = {"uid": UID1, "species": 4, "level": 9, "moves": list(moves), "hp": 20, "maxhp": 40}
    e.update(extra)
    a.eng.applyParty(a.lua([e]))


def act(a, action, **opts):
    return tuple(a.eng.fieldAction(action.encode(), a.lua(opts)))


def test_a_field_move_goes_the_party_menus_way():
    a, s = adapter()
    member(a, moves=(15, 57))
    assert act(a, "cut") == (True, b"cut")
    run = a.E.fieldRuns[1]
    assert run.action == b"cut" and a.E.fieldContexts[1].isSurfing is False and a.E.fieldContexts[1].mon is not None
    assert act(a, "strength") == (False, b"no_move"), "nobody knows it"
    a.E.fieldAnswer = a.lua({"SURF": {"ok": False, "badge": "SURF"}})
    assert act(a, "surf") == (False, b"no_badge")
    a.E.fieldAnswer = a.lua({"SURF": {"ok": False}})
    assert act(a, "surf") == (False, b"no_target")


def test_dive_fly_and_the_dismount():
    a, s = adapter()
    member(a, moves=(291, 19, 57))
    assert act(a, "dive") == (True, b"dive")
    assert act(a, "fly") == (True, b"fly_list"), "the game's own region map"
    assert act(a, "fly", dest="MAP_PETALBURG_CITY") == (False, b"no_target")
    a.E.Player.surfing = True
    off = a.b.eval("function(P) return function(dir) P.surfing = false return true end end")(a.E.Player)
    a.E.Player.tryMove = off
    assert act(a, "surf") == (True, b"dismount")


def test_a_field_moves_warp_is_named_by_the_move():
    # FA8 item 11 (K11): Dig (and Teleport, Fly, the rope) from Minecraft mark the warp they set off: the departure hint
    # for a fade-started warp, and the kind of the warp the engine announces (taken once)
    a, s = adapter()
    member(a, moves=(91,))
    assert act(a, "dig") == (True, b"dig")
    assert a.facts().get("departure") == "dig"
    warp = lambda: plain(a.eng.warpDeparture(a.lua({"toMap": "EM_TEST_TOWN", "warp": {"kind": "escape_rope", "map": "EM_TEST_TOWN"}})))  # noqa: E731
    assert warp()["kind"] == "dig"
    assert warp()["kind"] == "rope", "taken once: the next warp is the engine's own"
    assert a.facts().get("departure") is None


def test_the_bike_and_the_escape_rope_from_the_bag():
    a, s = adapter()
    member(a)
    assert act(a, "bicycle") == (False, b"no_item")
    assert a.E.Bag.add(s.bag, MACH_BIKE, 1)
    assert act(a, "bicycle") == (True, b"bike_on")
    assert act(a, "bicycle") == (True, b"bike_off")
    assert act(a, "dig", item="ESCAPE_ROPE") == (False, b"no_item")
    assert a.E.Bag.add(s.bag, ESCAPE_ROPE, 1)
    assert act(a, "dig", item="ESCAPE_ROPE") == (True, b"rope")


def test_lanettes_pc_is_cobblemons_while_a_client_follows():
    a, s = adapter()
    ctx = a.b.eval("function() return { ctx = {} } end")()
    ran = a.b.eval("function() local t = { n = 0 } return t, function() t.n = t.n + 1 end end")
    count, nxt = ran()
    assert a.eng.scriptCommand(nxt, a.lua({"vm": ctx}), b"special", a.lua({"id": 63})) is True, "the script waits"
    assert count.n == 0 and a.eng.pcHeld() is True
    rec = plain(a.eng.pcPending())
    assert rec["state"] == "waiting"
    a.eng.pcSent(1, b"pc-1")
    assert ctx.ctx.nativePoll() is False
    assert a.eng.pcClose(b"closed", b"pc-1") is True
    assert ctx.ctx.nativePoll() is True and a.eng.pcHeld() is False, "Cobblemon's PC closed: the script goes on"
    off, _ = adapter(live=False)
    c2, n2 = off.b.eval("function() local t = { n = 0 } return t, function() t.n = t.n + 1 end end")()
    off.eng.scriptCommand(n2, off.lua({"vm": off.b.eval("function() return { ctx = {} } end")()}), b"special", off.lua({"id": 63}))
    assert c2.n == 1, "no client: the game's own storage"


def test_cobblemons_shininess_nature_gender_and_friendship_on_the_record():
    a, s = adapter()
    member(a, shiny=True, nature="modest", gender="female", friendship=200)
    m = s.party[1]
    assert m.isShiny is True and a.E.Pokemon.natureId(m.personality) == 15
    assert a.E.Pokemon.gender(4, m.personality) == b"F" and m.friendship == 200
    p = m.personality
    member(a, shiny=True, nature="modest", gender="female", friendship=201)
    assert m.personality == p, "a personality that already fits is kept"
