"""MG9 (docs/specs/GEN2_BRIDGE_PLAN.md G2A): gen1-bridge's main.lua on the fake Gen 2 engine
(Rig(family="gen2")), seen from a fake Minecraft peer: the handshake and snapshot on Crystal (map ids
reported as G2_*, J4), steps (Gen 1's movement.collision verdicts; a held direction let go once its step is
under way, chainsHeld), a walked warp, saves and the nurse, and the region acts (the park's hold, a resume
through the world facade, a start from the title). Every message is schema-checked. Everything is made up."""

import pytest

from rig import Rig

pytestmark = pytest.mark.lua


def field_rig(x=5, y=6, facing="down", **kw):
    rig = Rig(family="gen2", **kw)
    rig.world("TEST_TOWN", x, y, facing)
    return rig


def pump(rig, peer, until, limit=40):
    """Steps one at a time until a received message satisfies ``until``; returns everything received."""
    got = []
    for _ in range(limit):
        rig.step()
        got.extend(peer.messages())
        if any(until(m) for m in got):
            return got
    raise AssertionError(f"not within {limit} steps: {[m['type'] for m in got]}")


def reply(msgs, seq):
    return next(m for m in msgs if m.get("re") == seq)


def answer(msgs, n):
    return next(m for m in msgs if m["data"].get("n") == n)


def no_faults(rig):
    lines = rig.logs()
    faults = [line for line in lines
              if "step_failed" in line or "[error]" in line or ("hook" in line and "failed" in line)]
    assert not faults, faults


def music(rig):
    calls = rig.E.musicCalls
    return [calls[i].op.decode() for i in range(1, len(calls) + 1)]


# ------------------------------------------------------------ boot and handshake


def test_main_boots_on_crystal_with_the_gen2_adapter():
    rig = Rig(family="gen2")
    st = rig.status
    assert (st.ready, st.engineId, st.engineAdapter, st.game, st.engine) == (True, b"crystal", True, b"crystal",
                                                                             b"0.3.85")
    # G2A: the UI, the story, the export, battles and routes come with G2B-G2H
    assert sorted(g.decode() for g in st.engineGroupsMissing.values()) == ["battles", "export", "routes", "story",
                                                                           "ui"]
    assert list(st.engineMissing.values()) == []
    logs = rig.logs()
    assert not any("engine.no_adapter" in line or "engine.missing" in line for line in logs)
    peer = rig.connect()
    peer.hello()
    msgs = peer.messages()
    assert msgs[0]["type"] == "hello.ok" and msgs[1]["type"] == "snapshot" and msgs[1]["data"]["ingame"] is False
    rig.step(10)
    assert not peer.closed()
    no_faults(rig)


def test_gold_is_refused_until_its_adapter_is_switched_on():
    # the same adapter runs Gold's `gs` engine, but the catalogue (protocol/games.json) leaves Gold off (J2)
    rig = Rig(family="gen2", game="gold")
    assert (rig.status.engineId, rig.status.engineAdapter) == (b"gs", True)
    peer = rig.connect()
    peer.hello()
    msgs = peer.messages()
    assert [m["type"] for m in msgs] == ["hello.reject"] and msgs[0]["data"]["reason"] == "game"


def test_hello_snapshot_and_the_field_on_crystal():
    rig = field_rig()
    E = rig.E
    E.addNpc(1, rig.b.to_lua({"x": 3, "y": 4, "sprite": "SPRITE_TEST_LASS"}))
    E.addNpc(2, rig.b.to_lua({"x": 8, "y": 4, "sprite": "SPRITE_TEST_YOUNGSTER", "trainer": {"class": 1},
                              "facing": "left"}))
    E.setFlag(26)
    save = E.game.save
    save.player.badges = rig.b.to_lua({"ZEPHYR": True})
    save.inventory = rig.b.to_lua({"POTION": 2})
    peer = rig.connect()
    seq = peer.hello()
    msgs = peer.messages()
    ok, snap = msgs[0], msgs[1]
    assert ok["type"] == "hello.ok" and ok["re"] == seq
    assert (ok["data"]["game"], ok["data"]["family"], ok["data"]["compat"]["maxDex"]) == ("crystal", "gen2", 251)
    data = snap["data"]
    assert snap["type"] == "snapshot" and data["ingame"] is True
    assert data["player"] == {"map": "G2_TEST_TOWN", "x": 5, "y": 6, "facing": "down"}
    assert data["state"]["mode"] == "idle"
    assert data["flags"] == ["E26"]
    assert (data["badges"], data["bag"], data["money"]) == (["ZEPHYRBADGE"], {"POTION": 2}, 3000)
    assert data["npcs"]["map"] == "G2_TEST_TOWN"
    assert [(n["id"], n["kind"], n["sprite"]) for n in data["npcs"]["list"]] == [
        ("O1", "person", "SPRITE_TEST_LASS"), ("O2", "trainer", "SPRITE_TEST_YOUNGSTER")]
    status = next(m for m in msgs if m["type"] == "game.status")["data"]
    assert (status["game"], status["playthrough"], status["badges"], status["markers"]) == (
        "crystal", "fresh", 1, {"starter": True, "champion": False})
    rig.step(30)
    peer.messages()
    seq = peer.send("debug.status")
    rig.step()
    st = next(m for m in peer.messages() if m.get("re") == seq)["data"]
    assert (st["ingame"], st["topState"], st["mode"], st["engineAdapter"]) == (True, "Overworld", "idle", True)
    no_faults(rig)


# ------------------------------------------------------------ steps


def test_a_move_step_lets_go_of_the_direction_once_the_step_is_under_way():
    rig = field_rig()
    E = rig.E
    inp = E.game.input
    peer = rig.connect()
    peer.hello()
    peer.messages()
    peer.send("move.step", {"dir": "down", "n": 1})
    rig.step()  # the press; the engine's World:step starts the step
    assert inp.isDown(inp, b"down") is True and E.game.world.player.moving is True
    rig.step()  # seen under way: answered, and the press let go
    pos = answer(peer.messages(), 1)
    assert pos["type"] == "player.pos" and (pos["data"]["map"], pos["data"]["x"], pos["data"]["y"],
                                            pos["data"]["kind"]) == ("G2_TEST_TOWN", 5, 7, "walk")
    assert inp.isDown(inp, b"down") is False
    rig.step(30)
    p = E.game.world.player
    assert (p.cellX, p.cellY, p.moving) == (5, 7, False)
    # a turn first: the mover faces with setFacing (no 4-frame turn), then holds
    peer.send("move.step", {"dir": "right", "n": 2})
    msgs = pump(rig, peer, lambda m: m["data"].get("n") == 2)
    pos = answer(msgs, 2)["data"]
    assert (pos["x"], pos["y"]) == (6, 7)
    rig.step(30)
    assert (p.cellX, p.cellY, p.facing) == (6, 7, b"right")
    no_faults(rig)


@pytest.mark.parametrize("block, reason, start, direction", [
    ("wall", "tile", (5, 6), "down"),
    ("npc", "entity", (5, 6), "down"),
    (None, "bounds", (0, 6), "left"),
])
def test_a_blocked_step_is_answered_with_the_engines_reason(block, reason, start, direction):
    rig = field_rig(*start, facing=direction)
    if block == "wall":
        rig.E.coll(b"TEST_TOWN", 5, 7, 0x07)
    elif block == "npc":
        rig.E.addNpc(1, rig.b.to_lua({"x": 5, "y": 7}))
    peer = rig.connect()
    peer.hello()
    peer.messages()
    peer.send("move.step", {"dir": direction, "n": 7})
    msgs = pump(rig, peer, lambda m: m["data"].get("n") == 7, limit=8)
    blocked = answer(msgs, 7)
    assert blocked["type"] == "player.blocked"
    assert blocked["data"] == {"dir": direction, "reason": reason, "map": "G2_TEST_TOWN", "x": start[0],
                               "y": start[1], "n": 7}
    rig.step(5)
    inp = rig.E.game.input
    assert inp.isDown(inp, direction.encode()) is False
    no_faults(rig)


def test_a_walked_warp_is_reported_with_its_kind():
    rig = field_rig()
    rig.E.addWarp(b"TEST_TOWN", 5, 7, b"TEST_HOUSE", 2, 3, 0x71)
    peer = rig.connect()
    peer.hello()
    peer.messages()
    peer.send("move.step", {"dir": "down", "n": 4})
    msgs = pump(rig, peer, lambda m: m["type"] == "warp" and m["data"].get("phase") == "arrive", limit=60)
    warps = [m["data"] for m in msgs if m["type"] == "warp"]
    assert warps[0]["phase"] == "start" and warps[0]["kind"] == "door"
    assert warps[-1]["to"]["map"] == "G2_TEST_HOUSE" and (warps[-1]["to"]["x"], warps[-1]["to"]["y"]) == (2, 3)
    no_faults(rig)


# ------------------------------------------------------------ saves


def test_a_menu_save_goes_out_as_save_begin():
    rig = field_rig()
    peer = rig.connect()
    peer.hello()
    peer.messages()
    assert rig.E.game.writeSave(rig.E.game) is True  # the START menu's SAVE
    msgs = pump(rig, peer, lambda m: m["type"] == "save.begin", limit=3)
    begin = next(m for m in msgs if m["type"] == "save.begin")["data"]
    assert begin["reason"] == "menu" and begin["save"].startswith("v-")
    no_faults(rig)


def test_the_nurses_heal_arms_the_center_save():
    rig = field_rig()
    rig.world("TEST_CENTER", 4, 4, "up")
    peer = rig.connect()
    peer.hello()
    peer.messages()
    ran = rig.b.eval("{ n = 0 }")
    vanilla = rig.b.eval("""function(ran) return function(ctx, op, args, cmd)
      ran.n = ran.n + 1 ran.cmd = cmd return 'yield' end end""")(ran)
    ctx = rig.b.eval("{ vm = { specialName = function(_, id) return id == 7 and 'HealParty' or 'Other' end } }")
    cmd = rig.b.to_lua({"op": "special", "id": 7})
    # Gen 2's VM: script.command(nextFn, ctx, name, args, cmd) (src/script/gen2/Vm.lua:1824-1851)
    result = rig.b.FakeMod.call(rig.b.mod, b"script.command", vanilla, ctx, b"special", rig.b.eval("{}"), cmd)
    assert result == b"yield" and ran.n == 1 and ran.cmd.id == 7  # the engine's own command, its op handed back
    msgs = pump(rig, peer, lambda m: m["type"] == "save.begin", limit=4)
    assert next(m for m in msgs if m["type"] == "save.begin")["data"]["reason"] == "center"
    assert len(rig.E.saveCalls) == 1 and rig.status.centerSaves == 1
    no_faults(rig)


# ------------------------------------------------------------ the region acts


def test_region_start_new_game_answers_once_oaks_speech_runs():
    rig = Rig(family="gen2")
    peer = rig.connect()
    peer.hello()
    msgs = peer.messages()
    assert msgs[1]["type"] == "snapshot" and msgs[1]["data"]["ingame"] is False
    seq = peer.send("region.start", {"region": "johto"})
    msgs = pump(rig, peer, lambda m: m.get("re") == seq, limit=4)
    assert reply(msgs, seq)["data"] == {"ok": True, "kind": "start", "detail": "new"}
    assert len(rig.E.newGames) == 1
    seq = peer.send("region.start", {"region": "johto"})  # Oak's speech already under way
    msgs = pump(rig, peer, lambda m: m.get("re") == seq, limit=4)
    assert reply(msgs, seq)["data"]["ok"] is False
    assert len(rig.E.newGames) == 1
    no_faults(rig)


def test_region_start_continues_the_active_slots_save():
    rig = Rig(family="gen2")
    E = rig.E
    save = E.newSave()
    save.position = rig.b.to_lua({"map": "TEST_HOUSE", "x": 2, "y": 3, "facing": "up"})
    E.slotSave(b"slot1", save)
    E.SaveData.setActiveSlot(b"crystal", b"slot1")
    peer = rig.connect()
    peer.hello()
    peer.messages()
    seq = peer.send("region.start", {"region": "johto"})
    msgs = pump(rig, peer, lambda m: m.get("re") == seq, limit=4)
    assert reply(msgs, seq)["data"] == {"ok": True, "kind": "start", "detail": "continue"}
    if not any(m["type"] == "game.status" for m in msgs):
        msgs += pump(rig, peer, lambda m: m["type"] == "game.status", limit=12)
    status = [m["data"] for m in msgs if m["type"] == "game.status"][-1]
    assert (status["playthrough"], status["ingame"], status["slot"]) == ("saved", True, 1)
    pos = [m["data"] for m in msgs if m["type"] == "player.pos"][-1]
    assert (pos["map"], pos["x"], pos["y"], pos["kind"]) == ("G2_TEST_HOUSE", 2, 3, "sync")
    no_faults(rig)


def test_region_park_holds_the_field_and_resume_warps_through_the_facade():
    rig = field_rig()
    E = rig.E
    peer = rig.connect()
    peer.hello()
    peer.messages()
    seq = peer.send("region.park", {"region": "johto"})
    msgs = pump(rig, peer, lambda m: m.get("re") == seq, limit=4)
    assert reply(msgs, seq)["data"] == {"ok": True, "kind": "park"}
    rig.step()
    stack = E.game.stack
    assert rig.status.regionHeld is True and stack.top(stack).kantoBridgeRegionHold is True
    steps = E.worldSteps
    peer.send("move.step", {"dir": "down", "n": 9})  # parked: the hidden player takes no step
    msgs = pump(rig, peer, lambda m: m["data"].get("n") == 9, limit=4)
    assert answer(msgs, 9)["type"] == "player.blocked" and E.worldSteps == steps
    # another map is refused (only the parked map or one of our route maps, act/kb_region.lua)
    seq = peer.send("region.resume", {"region": "johto", "to": {"map": "G2_TEST_HOUSE", "x": 2, "y": 2,
                                                                 "facing": "down"}})
    msgs = pump(rig, peer, lambda m: m.get("re") == seq, limit=6)
    assert reply(msgs, seq)["data"] == {"ok": False, "kind": "resume", "reason": "bad_target"}
    # the parked map at another cell: released, then warped there through the world facade
    seq = peer.send("region.resume", {"region": "johto", "to": {"map": "G2_TEST_TOWN", "x": 3, "y": 4,
                                                                 "facing": "down"}})
    msgs = pump(rig, peer, lambda m: m.get("re") == seq, limit=8)
    assert reply(msgs, seq)["data"] == {"ok": True, "kind": "resume"}
    rig.step(3)
    assert rig.status.regionHeld is False and stack.top(stack) is None
    warp = E.warps[len(E.warps)]
    assert (warp.map, warp.x, warp.y) == (b"TEST_TOWN", 3, 4), "the facade took the prefix off"
    p = E.game.world.player
    assert (E.game.world.map.id, p.cellX, p.cellY) == (b"TEST_TOWN", 3, 4)
    assert music(rig) == ["fadeOut", "restoreMapMusic"]
    no_faults(rig)
