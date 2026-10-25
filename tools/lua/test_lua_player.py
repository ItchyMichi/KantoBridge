"""observe/kb_player.lua (the mode classifier and position observer) and the
facts kb_engine.observe() gathers from (fake) engine tables."""

import pytest

from kblua import Bridge
from rig import Rig

pytestmark = pytest.mark.lua

BASE = {"inGame": True, "top": "overworld", "topName": "Overworld", "map": "TEST_TOWN",
        "x": 5, "y": 6, "facing": "down", "moving": False}


class Obs:
    def __init__(self):
        self.b = Bridge()
        self.Player = self.b.module("observe/kb_player.lua")
        self.o = self.Player.new()

    def facts(self, **kw):
        f = dict(BASE)
        f.update(kw)
        return self.b.to_lua(f)

    def update(self, **kw):
        out = self.o.update(self.facts(**kw))
        return [(m[1].decode(), {k.decode(): (v.decode() if isinstance(v, bytes) else v)
                                 for k, v in m[2].items()}) for m in out.values()]

    def classify(self, **kw):
        return self.Player.classify(self.facts(**kw)).decode()


@pytest.mark.parametrize("facts, mode", [
    ({}, "idle"),
    ({"moving": True, "tx": 5, "ty": 5}, "idle"),            # a normal step is still the player's
    ({"top": "screen", "topName": "TextBox"}, "screen"),
    ({"inGame": False, "top": "screen", "topName": "TitleState"}, "screen"),
    ({"top": "busy"}, "busy"),                                # a fade on top
    ({"top": "none"}, "busy"),
    ({"transitioning": True}, "busy"),
    ({"playerScripted": True}, "forced"),
    ({"ledge": True}, "forced"),
    ({"spinning": True}, "forced"),
    ({"forcedPhase": True}, "forced"),
    ({"hopLand": 2}, "forced"),
    ({"emote": True}, "busy"),
    ({"anim": True}, "busy"),
    ({"scripted": True}, "cutscene"),
    ({"scripted": True, "playerScripted": True}, "forced"),   # the player walking in a cutscene
])
def test_classify(facts, mode):
    assert Obs().classify(**facts) == mode


def test_walk_sends_the_target_cell_at_step_start():
    o = Obs()
    first = o.update()
    assert first == [
        ("player.state", {"mode": "idle", "bike": False, "surf": False, "slope": False,
                          "hidden": False, "dark": False, "lit": False}),
        ("player.pos", {"map": "TEST_TOWN", "x": 5, "y": 6, "facing": "down", "kind": "sync", "frames": 0}),
    ]
    assert o.update(moving=True, tx=5, ty=5, facing="up", frames=16) == [
        ("player.pos", {"map": "TEST_TOWN", "x": 5, "y": 5, "facing": "up", "kind": "walk", "frames": 16}),
    ]
    assert o.update(moving=True, tx=5, ty=5, facing="up", frames=16) == []  # mid-step
    assert o.update(x=5, y=5, facing="up") == []                             # landed as announced


def test_continuous_walk_is_one_message_per_step():
    o = Obs()
    o.update()
    out = []
    # landing and the next step start happen within one logic step: moving never reads false
    for y in (5, 4, 3):
        out += o.update(x=5, y=y + 1, moving=True, tx=5, ty=y, facing="up", frames=16)
        out += o.update(x=5, y=y + 1, moving=True, tx=5, ty=y, facing="up", frames=16)
    assert [(d["x"], d["y"], d["kind"]) for t, d in out] == [(5, 5, "walk"), (5, 4, "walk"), (5, 3, "walk")]


def test_turns_and_teleports_are_syncs():
    o = Obs()
    o.update()
    assert o.update(facing="left") == [
        ("player.pos", {"map": "TEST_TOWN", "x": 5, "y": 6, "facing": "left", "kind": "sync", "frames": 0}),
    ]
    assert o.update(facing="left", map="TEST_HOUSE", x=2, y=7) == [
        ("player.pos", {"map": "TEST_HOUSE", "x": 2, "y": 7, "facing": "left", "kind": "sync", "frames": 0}),
    ]


@pytest.mark.parametrize("facts, kind", [
    ({"bike": True}, "bike"),
    ({"surf": True}, "surf"),
    ({"spinning": True}, "spin"),
    ({"playerScripted": True}, "script"),
    ({"forcedPhase": True}, "script"),
])
def test_step_kinds(facts, kind):
    o = Obs()
    o.update(**facts)
    (msg,) = [m for m in o.update(moving=True, tx=6, ty=6, facing="right", frames=8, **facts)
              if m[0] == "player.pos"]
    assert msg[1]["kind"] == kind and msg[1]["frames"] == 8


def positions(out):
    return [(d["map"], d["x"], d["y"], d["facing"], d["kind"], d["frames"]) for t, d in out if t == "player.pos"]


def test_a_ledge_hop_is_one_message_at_its_landing_cell():
    o = Obs()
    o.update()
    hop = {"ledge": True, "playerScripted": True, "hopFrames": 32}
    assert positions(o.update(**hop)) == []                             # queued: starts next step
    assert positions(o.update(moving=True, tx=5, ty=7, frames=16, **hop)) == [
        ("TEST_TOWN", 5, 8, "down", "ledge", 32)]                       # the landing, the whole hop
    assert positions(o.update(moving=True, tx=5, ty=7, frames=16, **hop)) == []
    assert positions(o.update(x=5, y=7, **hop)) == []                   # between the two cells
    assert positions(o.update(x=5, y=7, moving=True, tx=5, ty=8, frames=16, **hop)) == []
    assert positions(o.update(x=5, y=8, hopLand=3)) == []               # landed where announced
    assert positions(o.update(x=5, y=8)) == []
    assert positions(o.update(x=5, y=8, facing="left")) == [("TEST_TOWN", 5, 8, "left", "sync", 0)]


def test_a_hop_onto_a_connected_map_finishes_with_the_seam_step():
    o = Obs()
    o.update(x=5, y=6)
    hop = {"ledge": True, "playerScripted": True, "hopFrames": 32}
    assert positions(o.update(x=5, y=6, moving=True, tx=5, ty=7, frames=16, **hop)) == [
        ("TEST_TOWN", 5, 8, "down", "ledge", 32)]                       # off this map: 2 cells on
    assert positions(o.update(x=5, y=7, **hop)) == []
    # the seam: the hop's onDone crosses into the connected map, then clears the hop flag
    assert positions(o.update(map="TEST_ROUTE", x=5, y=-1, moving=True, tx=5, ty=0, frames=16, hopLand=2)) == [
        ("TEST_ROUTE", 5, 0, "down", "ledge", 16)]
    assert positions(o.update(map="TEST_ROUTE", x=5, y=0)) == []


def test_messages_carry_tags_for_the_step_driver():
    o = Obs()
    out = o.o.update(o.facts())
    assert [m[3].decode() for m in out.values()] == ["state", "sync"]
    out = o.o.update(o.facts(moving=True, tx=5, ty=5, facing="up", frames=16))
    assert [m[3].decode() for m in out.values()] == ["step"]


def test_force_sync_resends_an_unchanged_position():
    o = Obs()
    o.update()
    o.o.forceSync()
    assert positions(o.update(moving=True, tx=5, ty=5, facing="up", frames=16)) == [
        ("TEST_TOWN", 5, 5, "up", "walk", 16)]                          # a step goes first
    assert positions(o.update(x=5, y=5, facing="up")) == [("TEST_TOWN", 5, 5, "up", "sync", 0)]
    assert positions(o.update(x=5, y=5, facing="up")) == []


def test_state_changes_are_sent_once():
    o = Obs()
    o.update()
    assert o.update(top="screen") == [("player.state", {"mode": "screen", "bike": False, "surf": False,
                                                        "slope": False, "hidden": False, "dark": False,
                                                        "lit": False})]
    assert o.update(top="screen") == []
    assert [d["mode"] for t, d in o.update()] == ["idle"]


def test_dark_and_lit():
    o = Obs()
    (state,) = [d for t, d in o.update(darkMap=True) if t == "player.state"]
    assert (state["dark"], state["lit"]) == (True, False)
    (state,) = [d for t, d in o.update(darkMap=True, flashLit=True) if t == "player.state"]
    assert (state["dark"], state["lit"]) == (True, True)
    (state,) = [d for t, d in o.update(flashLit=True) if t == "player.state"]
    assert (state["dark"], state["lit"]) == (False, False)  # lit only means something on a dark map


def test_the_weather_is_part_of_the_state():
    # SE6 (docs/specs/STORY_EVENTS_PLAN.md): Gen 3's facts carry the weather the game draws; a change is a state
    # change; a name the protocol lacks is left out, as is the weather of a game without one
    o = Obs()
    o.update()
    (state,) = [d for t, d in o.update(weather="rain") if t == "player.state"]
    assert state["weather"] == "rain"
    assert o.update(weather="rain") == []
    (state,) = [d for t, d in o.update(weather="drought") if t == "player.state"]
    assert state["weather"] == "drought"
    (state,) = [d for t, d in o.update(weather="hail") if t == "player.state"]
    assert "weather" not in state


def test_running_is_part_of_the_state_where_the_game_has_it():
    # FA8 item 7 (D4): Emerald's running shoes (facts.running) are player.state.run; a game without running sends none
    o = Obs()
    (state,) = [d for t, d in o.update(running=False) if t == "player.state"]
    assert state["run"] is False
    (state,) = [d for t, d in o.update(running=True) if t == "player.state"]
    assert state["run"] is True
    assert o.update(running=True) == []
    o2 = Obs()
    (state,) = [d for t, d in o2.update() if t == "player.state"]
    assert "run" not in state


def test_rebase_suppresses_what_the_snapshot_said():
    o = Obs()
    o.o.rebase(o.facts(moving=True, tx=5, ty=5, facing="up", frames=16))
    assert o.update(moving=True, tx=5, ty=5, facing="up", frames=16) == []
    assert o.update(x=5, y=5, facing="up") == []


def test_title_screen_sends_state_but_no_position():
    o = Obs()
    out = o.update(inGame=False, top="screen", topName="TitleState", map=None, x=None, y=None, facing=None)
    assert [t for t, d in out] == ["player.state"]
    assert out[0][1]["mode"] == "screen"


# ------------------------------------------------------------ kb_engine.observe() on the fake engine


def engine_for(rig):
    b = rig.b
    Engine = b.module("lib/kb_engine.lua")
    eng = Engine.new(b.engine_opts(ui=rig.E.ui))  # MG2: the loader picks lib/engine/gen1.lua
    eng.setGame(rig.E.game)
    return eng


def facts_of(eng):
    f = eng.observe()
    return {k.decode(): (v.decode() if isinstance(v, bytes) else v) for k, v in f.items()}


def test_observe_outside_the_overworld():
    rig = Rig()
    eng = engine_for(rig)
    assert facts_of(eng) == {"inGame": False, "top": "none", "topName": "None"}
    rig.E.push(rig.E.menu(b"TitleState"))
    assert facts_of(eng)["top"] == "screen" and facts_of(eng)["topName"] == "TitleState"


def test_observe_a_step_and_the_flags():
    rig = Rig()
    rig.world("TEST_CAVE", 3, 4, "left")
    eng = engine_for(rig)
    rig.E.startStep(b"left", 8)
    rig.E.save.onBike = True
    rig.E.save.flashLit = True
    rig.E.ow.player.slopeMap = True
    rig.E.ow.playerHidden = True
    f = facts_of(eng)
    assert (f["map"], f["x"], f["y"], f["tx"], f["ty"], f["frames"]) == ("TEST_CAVE", 3, 4, 2, 4, 8)
    assert f["moving"] and f["bike"] and f["slope"] and f["hidden"] and f["darkMap"] and f["flashLit"]
    assert not f["playerScripted"] and f["top"] == "overworld"


def test_observe_scripted_moves_and_holds():
    rig = Rig()
    rig.world()
    eng = engine_for(rig)
    moves = rig.E.ow.scriptMoves
    moves[1] = rig.b.to_lua({"dir": "up", "remaining": 2})
    moves[1].entity = rig.E.ow.player
    f = facts_of(eng)
    assert f["playerScripted"] and f["scripted"]
    moves[1].entity = rig.b.table()  # an NPC's walk: the world is held, the player isn't moved
    f = facts_of(eng)
    assert not f["playerScripted"] and f["scripted"]
    rig.E.ow.scriptMoves = rig.b.table()
    rig.E.ow.runner.running = True
    assert facts_of(eng)["scripted"]
    rig.E.ow.runner.running = False
    for field in ("teleportOut", "flyAnim", "holeFall", "spinArrive"):
        rig.E.ow[field.encode()] = True
        assert facts_of(eng)["forcedPhase"], field
        rig.E.ow[field.encode()] = None
    rig.E.ow.emote = rig.b.table()
    assert facts_of(eng)["emote"]


def test_observe_classifies_the_top_state():
    rig = Rig()
    rig.world()
    eng = engine_for(rig)
    cases = [
        (rig.E.text(), "screen", "TextBox"),
        (rig.E.menu(b"StartMenu"), "screen", "StartMenu"),
        (rig.b.eval("function(ui) return setmetatable({}, ui.ChoiceBox) end")(rig.E.ui), "screen", "ChoiceBox"),
        (rig.b.to_lua({"isBattleState": True}), "screen", "BattleState"),
        (rig.E.transition(), "busy", "Busy"),
    ]
    for state, kind, name in cases:
        rig.E.push(state)
        f = facts_of(eng)
        assert (f["top"], f["topName"]) == (kind, name)
        assert f["inGame"], "the overworld under a screen is still the game"
        rig.E.pop()


def test_npcs_are_read_and_classified():
    rig = Rig()
    rig.world()
    eng = engine_for(rig)
    E, b = rig.E, rig.b
    E.npc(b"TEST_TOWN_obj_1", b"SPRITE_TEST_GIRL", 3, 4)
    E.npc(b"TEST_TOWN_obj_2", b"SPRITE_TEST_BALL", 5, 1, b.to_lua({"sprite": "SPRITE_TEST_BALL", "item": "POTION"}))
    E.npc(b"TEST_TOWN_obj_3", b"SPRITE_TEST_BALL", 6, 1, b.to_lua({"sprite": "SPRITE_TEST_BALL", "item": "0"}))
    E.npc(b"TEST_TOWN_obj_4", b"SPRITE_BOULDER", 7, 1)
    E.npc(b"TEST_TOWN_obj_5", b"SPRITE_TEST_BIRD", 8, 1, b.to_lua({"sprite": "SPRITE_TEST_BIRD", "pokemon": "TESTMON"}))
    E.npc(b"TEST_TOWN_obj_6", b"SPRITE_TEST_GUARD", 9, 1, b.to_lua({"sprite": "SPRITE_TEST_GUARD", "trainerClass": "TEST"}))
    trainer = E.ow.npcs[6]
    trainer.appearance = b"SPRITE_TEST_OTHER"
    npcs, map_id, generation = eng.npcs()
    got = [(n.id.decode(), n.sprite.decode(), n.kind.decode()) for n in npcs.values()]
    assert map_id == b"TEST_TOWN"
    assert got == [
        ("TEST_TOWN_obj_1", "SPRITE_TEST_GIRL", "person"),
        ("TEST_TOWN_obj_2", "SPRITE_TEST_BALL", "item"),
        ("TEST_TOWN_obj_3", "SPRITE_TEST_BALL", "person"),   # item "0" is ITEM_NONE
        ("TEST_TOWN_obj_4", "SPRITE_BOULDER", "boulder"),
        ("TEST_TOWN_obj_5", "SPRITE_TEST_BIRD", "static"),
        ("TEST_TOWN_obj_6", "SPRITE_TEST_OTHER", "trainer"),  # appearance wins
    ]
    assert generation == 1 and eng.npcs()[2] == 1, "the same ow.npcs table: the same generation"
    E.ow.npcs = b.table()  # setMap builds a new table (OC:558-565)
    assert eng.npcs()[2] == 2


def test_positions_that_dont_fit_the_schema_are_held_back():
    o = Obs()
    o.update()
    assert o.update(facing="north") == []                      # not a direction
    assert o.update(map="lower_case", x=1) == []                # not a map symbol
    assert o.update(moving=True, tx=5.5, ty=5, facing="up", frames=16) == []
    (msg,) = o.update(moving=True, tx=5, ty=5, facing="up", frames=15.6)
    assert msg[1]["frames"] == 16                               # whole frames only
