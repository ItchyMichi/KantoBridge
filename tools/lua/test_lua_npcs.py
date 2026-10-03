"""observe/kb_npcs.lua (the NPC diff and emotes): unit tests on plain kb_engine facts, then
through main.lua on the simulated engine (npc.set on map entry and after a snapshot, moves of
every kind, turns, adds and removes, emotes, the snapshot's list). Every message the peer
receives is schema-checked (rig.Peer). All maps and NPCs are made up."""

import pytest

from kblua import Bridge
from uisim import Game

pytestmark = pytest.mark.lua


def npc(id_, x, y, **kw):
    out = {"id": id_, "sprite": "SPRITE_TEST", "kind": "person", "x": x, "y": y, "facing": "down",
           "moving": False, "marching": False, "progress": 0, "frames": 32}
    out.update(kw)
    return out


class Obs:
    def __init__(self):
        self.b = Bridge()
        self.Npcs = self.b.module("observe/kb_npcs.lua")
        opts = self.b.table()
        self.json = self.b.module("lib/kb_json.lua")
        opts[b"json"] = self.json
        self.o = self.Npcs.new(opts)
        self.is_null = self.b.eval("function(json) return function(v) return v == json.null end end")(self.json)
        self.serial = 0

    def emote(self, target, bubble="unset", frames=60, new=True):
        if new:
            self.serial += 1
        e = {"serial": self.serial, "frames": frames}
        if target is not None:
            e["target"] = target
        lua = self.b.to_lua(e)
        if bubble != "unset":
            lua[b"bubble"] = bubble
        return lua

    def step(self, raw, map_id="TEST_TOWN", gen=1, emote=None):
        out = self.b.table()
        lua_raw = None if raw is None else self.b.to_lua(raw)
        if lua_raw is not None:
            for i, n in enumerate(raw, start=1):
                for k in ("moving", "marching"):
                    lua_raw[i][k.encode()] = n.get(k, False)
                if n.get("tx") is None:
                    lua_raw[i][b"tx"] = None
        self.o.step(lua_raw, map_id.encode() if map_id else None, gen, emote, out)
        msgs = []
        for m in out.values():
            data = self.b.eval("function(t) return t end")(m[2])
            msgs.append((m[1].decode(), self.plain(data)))
        return msgs

    def plain(self, t):
        if self.is_null(t):
            return None
        if hasattr(t, "items"):
            keys = list(t.keys())
            if keys and all(isinstance(k, int) for k in keys):
                return [self.plain(t[i]) for i in range(1, len(keys) + 1)]
            if not keys:
                return []
            return {k.decode(): self.plain(v) for k, v in t.items()}
        if isinstance(t, bytes):
            return t.decode()
        if t is not None and not isinstance(t, (int, float, bool)):
            return None  # json.null
        return t


def test_first_step_sends_the_set_with_moving_npcs_at_their_target():
    o = Obs()
    out = o.step([npc("A", 1, 2), npc("B", 3, 4, moving=True, tx=3, ty=5, facing="down")])
    assert out == [("npc.set", {"map": "TEST_TOWN", "list": [
        {"id": "A", "sprite": "SPRITE_TEST", "kind": "person", "x": 1, "y": 2, "facing": "down", "moving": False},
        {"id": "B", "sprite": "SPRITE_TEST", "kind": "person", "x": 3, "y": 5, "facing": "down", "moving": True},
    ]})]
    assert o.step([npc("A", 1, 2), npc("B", 3, 4, moving=True, tx=3, ty=5)]) == []


def test_adds_removes_and_appearances():
    o = Obs()
    o.step([npc("A", 1, 2)])
    assert o.step([npc("A", 1, 2), npc("C", 5, 5, kind="item", sprite="SPRITE_BALL")]) == [
        ("npc.add", {"map": "TEST_TOWN", "id": "C", "sprite": "SPRITE_BALL", "kind": "item", "x": 5, "y": 5,
                     "facing": "down", "moving": False})]
    assert o.step([npc("C", 5, 5, kind="item", sprite="SPRITE_BALL")]) == [("npc.remove", {"id": "A"})]
    assert o.step([npc("C", 5, 5, kind="item", sprite="SPRITE_OTHER")]) == [
        ("npc.remove", {"id": "C"}),
        ("npc.add", {"map": "TEST_TOWN", "id": "C", "sprite": "SPRITE_OTHER", "kind": "item", "x": 5, "y": 5,
                     "facing": "down", "moving": False})]


@pytest.mark.parametrize("via, kind", [(None, "walk"), ("script", "script"), ("push", "push")])
def test_a_step_start_is_one_move_to_the_target(via, kind):
    o = Obs()
    o.step([npc("A", 1, 2)])
    extra = {"via": via} if via else {}
    moving = npc("A", 1, 2, moving=True, tx=2, ty=2, facing="right", frames=16, **extra)
    assert o.step([moving]) == [("npc.move", {"id": "A", "x": 2, "y": 2, "facing": "right", "frames": 16,
                                              "kind": kind})]
    assert o.step([moving]) == []                                         # mid-step
    assert o.step([npc("A", 2, 2, facing="right")]) == []                  # landed where announced


def test_chained_steps_and_jumps():
    o = Obs()
    o.step([npc("A", 1, 2)])
    o.step([npc("A", 1, 2, moving=True, tx=1, ty=3)])
    # landed and started the next tile within one logic step: the target changed
    assert o.step([npc("A", 1, 3, moving=True, tx=1, ty=4)]) == [
        ("npc.move", {"id": "A", "x": 1, "y": 4, "facing": "down", "frames": 32, "kind": "walk"})]
    o.step([npc("A", 1, 4)])
    # placed somewhere else without a step (a script's place_npc, a reset to spawn)
    assert o.step([npc("A", 7, 7)]) == [
        ("npc.move", {"id": "A", "x": 7, "y": 7, "facing": "down", "frames": 0, "kind": "script"})]


def test_turns_and_marching():
    o = Obs()
    o.step([npc("A", 1, 2)])
    assert o.step([npc("A", 1, 2, facing="left")]) == [("npc.face", {"id": "A", "facing": "left"})]
    march = dict(moving=True, marching=True, facing="left")
    assert o.step([npc("A", 1, 2, progress=1, **march)]) == [
        ("npc.move", {"id": "A", "x": 1, "y": 2, "facing": "left", "frames": 32, "kind": "march"})]
    assert o.step([npc("A", 1, 2, progress=20, **march)]) == []
    # a new cycle (the ambient marchers re-arm at once): progress starts again
    assert o.step([npc("A", 1, 2, progress=0, **march)])[0][1]["kind"] == "march"


def test_map_changes_generations_and_rebase_send_the_set_again():
    o = Obs()
    o.step([npc("A", 1, 2)])
    assert [t for t, _ in o.step([npc("B", 1, 1)], map_id="TEST_HOUSE")] == ["npc.set"]
    assert [t for t, _ in o.step([npc("B", 1, 1)], map_id="TEST_HOUSE", gen=2)] == ["npc.set"]
    o.o.rebase()
    assert [t for t, _ in o.step([npc("B", 1, 1)], map_id="TEST_HOUSE", gen=2)] == ["npc.set"]
    assert o.step(None, map_id=None) == []                     # the title screen
    assert [t for t, _ in o.step([npc("B", 1, 1)], map_id="TEST_HOUSE", gen=2)] == ["npc.set"]


def test_npcs_that_dont_fit_are_left_out():
    o = Obs()
    out = o.step([npc("A", 1, 2), npc("B", 1, 2, sprite="lower"), npc("C!", 1, 1), npc("D", 1, 1, facing="north")])
    assert [n["id"] for n in out[0][1]["list"]] == ["A"]
    assert o.o.stats().dropped == 3


@pytest.mark.parametrize("bubble, want", [("unset", "!"), (1, "!"), (2, "?"), (3, "happy"), (False, None), (9, None)])
def test_emotes(bubble, want):
    o = Obs()
    o.step([npc("A", 1, 2)])
    e = o.emote("A", bubble=bubble, frames=60)
    assert o.step([npc("A", 1, 2)], emote=e) == [("emote", {"target": "A", "bubble": want, "frames": 60})]
    assert o.step([npc("A", 1, 2)], emote=o.emote("A", bubble=bubble, frames=59, new=False)) == []


def test_emote_on_the_player_and_silent_beats():
    o = Obs()
    o.step([npc("A", 1, 2)])
    assert o.step([npc("A", 1, 2)], emote=o.emote("player", bubble=2)) == [
        ("emote", {"target": "player", "bubble": "?", "frames": 60})]
    assert o.step([npc("A", 1, 2)], emote=o.emote(None)) == []      # a beat with no entity


# ------------------------------------------------------------ through main.lua


def npc_msgs(g, msgs=None):
    msgs = g.log if msgs is None else msgs
    return [(m["type"], m["data"]) for m in msgs if m["type"].startswith("npc.") or m["type"] == "emote"]


def test_snapshot_and_npc_set_agree():
    g = Game()
    snap = g.received("snapshot")[0]["data"]["npcs"]
    (sets,) = g.received("npc.set")
    assert sets["data"] == snap
    assert [(n["id"], n["kind"], n["x"], n["y"]) for n in snap["list"]] == [
        ("TEST_ROOM_obj_1", "person", 3, 1), ("TEST_ROOM_obj_2", "person", 6, 1), ("TEST_ROOM_obj_3", "item", 1, 4)]
    assert snap["map"] == "TEST_ROOM"


def test_moves_turns_and_removals_from_the_simulated_world():
    g = Game()
    girl = g.E.ow.npcs[2]
    # her own wandering step (NPC:update starts it with a target)
    girl.targetX, girl.targetY, girl.moving, girl.progress, girl.facing = 7, 1, True, 0, b"right"
    out = g.run()
    assert npc_msgs(g, out) == [("npc.move", {"id": "TEST_ROOM_obj_2", "x": 7, "y": 1, "facing": "right",
                                              "frames": 32, "kind": "walk"})]
    g.run(40)
    assert girl.cellX == 7 and not girl.moving
    # a cutscene walk and a walk-in-place beat
    g.E.scriptMove(girl, b"down", 2)
    g.run(80)
    moves = [d for t, d in npc_msgs(g) if t == "npc.move"]
    assert [(d["x"], d["y"], d["kind"]) for d in moves[1:]] == [(7, 2, "script"), (7, 3, "script")]
    g.E.marchInPlace(girl)
    g.run(40)
    assert [d["kind"] for t, d in npc_msgs(g) if t == "npc.move"][-1] == "march"
    # a turn, then the item ball taken
    girl.facing = b"up"
    assert npc_msgs(g, g.run()) == [("npc.face", {"id": "TEST_ROOM_obj_2", "facing": "up"})]
    g.b.eval("function(list) table.remove(list, 3) end")(g.E.ow.npcs)
    assert npc_msgs(g, g.run()) == [("npc.remove", {"id": "TEST_ROOM_obj_3"})]


def test_a_boulder_push_is_kind_push():
    maps = {"TEST_ROOM": {"rows": ["#######", "#.....#", "#######"],
                          "npcs": [{"id": "TEST_ROOM_obj_1", "x": 3, "y": 1, "sprite": "SPRITE_BOULDER",
                                    "pushable": True}]}}
    g = Game(start=("TEST_ROOM", 2, 1, "right"), maps=maps)
    assert g.received("npc.set")[0]["data"]["list"][0]["kind"] == "boulder"
    g.E.scriptMove(g.E.ow.npcs[1], b"right", 1)
    g.run(3)
    assert [d["kind"] for t, d in npc_msgs(g) if t == "npc.move"] == ["push"]


def test_map_entry_sends_the_new_maps_set():
    g = Game()
    g.E.scriptWarp(b"TEST_HALL", 1, 2)
    g.until(lambda m: m["type"] == "npc.set" and m["data"]["map"] == "TEST_HALL", limit=100)
    (hall,) = [d for t, d in npc_msgs(g) if t == "npc.set" and d["map"] == "TEST_HALL"]
    assert hall["list"] == [{"id": "TEST_HALL_obj_1", "sprite": "SPRITE_TEST_GUARD", "kind": "trainer",
                             "x": 2, "y": 1, "facing": "down", "moving": False}]
    st = g.status()
    assert st["npcSets"] == 2 and st["npcKnown"] == 1


def test_emote_through_main():
    g = Game()
    girl = g.E.ow.npcs[2]
    g.E.ow.emote = g.b.to_lua({"frames": 60, "bubble": 2})
    g.E.ow.emote.npc = girl
    out = g.run()
    assert npc_msgs(g, out) == [("emote", {"target": "TEST_ROOM_obj_2", "bubble": "?", "frames": 60})]
    g.run(70)
    assert len(g.received("emote")) == 1
    g.E.ow.emote = g.b.to_lua({"frames": 30})
    g.E.ow.emote.npc = g.E.ow.player
    out = g.run()
    assert npc_msgs(g, out) == [("emote", {"target": "player", "bubble": "!", "frames": 30})]


def test_resync_sends_the_set_again():
    g = Game()
    seq = g.send("resync")
    snap = g.until(lambda m: m["type"] == "snapshot" and m.get("re") == seq)
    after = g.log[g.log.index(snap):]
    assert [m["type"] for m in after] == ["snapshot", "npc.set"]
    assert after[1]["data"] == snap["data"]["npcs"]
