"""MG7 (docs/specs/GEN3_BRIDGE_PLAN.md G3A): gen1-bridge's main.lua on the fake Gen 3 engine
(Rig(family="gen3")), seen from a fake Minecraft peer: the handshake and snapshot on Emerald, steps
(the tryMove verdicts through main.lua's takeVerdicts; a held direction let go once its step is under
way, chainsHeld), saves, and the region acts. Every message is schema-checked. Everything is made up."""

import re

import pytest

from kblua import BRIDGE
from rig import Rig

pytestmark = pytest.mark.lua


def field_rig(x=5, y=6, facing="down", **kw):
    rig = Rig(family="gen3", **kw)
    rig.world("EM_TEST_TOWN", x, y, facing)
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
    """The reply to a request (its ``re``)."""
    return next(m for m in msgs if m.get("re") == seq)


def answer(msgs, n):
    """The answer to a move.step (its ``n``)."""
    return next(m for m in msgs if m["data"].get("n") == n)


def no_faults(rig):
    lines = rig.logs()
    faults = [line for line in lines
              if "step_failed" in line or "[error]" in line or ("hook" in line and "failed" in line)]
    assert not faults, faults


# ------------------------------------------------------------ boot and handshake


def test_main_boots_on_emerald_with_the_game3_adapter():
    rig = Rig(family="gen3")
    st = rig.status
    assert (st.ready, st.engineId, st.engineAdapter, st.game, st.engine) == (True, b"game3", True, b"emerald",
                                                                             b"0.3.85")
    assert [g.decode() for g in st.engineGroupsMissing.values()] == []  # G3F: every group has a part
    assert list(st.engineMissing.values()) == []
    logs = rig.logs()
    assert not any("engine.groups" in line for line in logs), "G3F: no group is missing, so nothing to name"
    assert not any("engine.missing" in line or "engine.no_adapter" in line for line in logs)
    no_faults(rig)


@pytest.mark.parametrize("parts", [["boot"], ["boot", "observe"], ["boot", "observe", "move", "saves"]])
def test_main_steps_with_parts_missing(parts):
    # GEN3_BRIDGE_PLAN §11: the core calls many optional functions unguarded every step; with only some
    # parts installed, the rest still the stub's, a live session's steps never fault
    src = (BRIDGE / "lib/engine/game3.lua").read_text(encoding="utf-8")
    lua_list = ", ".join(f'"{p}"' for p in parts)
    fewer, n = re.subn(r"Game3\.PARTS = \{[^}]*\}", f"Game3.PARTS = {{ {lua_list} }}", src)
    assert n == 1
    rig = field_rig(files={"lib/engine/game3.lua": fewer})
    assert rig.status.engineAdapter is True and len(rig.status.engineGroupsMissing) > 5
    rig.E.addObject(1, rig.b.to_lua({"x": 3, "y": 4}))
    peer = rig.connect()
    peer.hello()
    assert [m["type"] for m in peer.messages()][:2] == ["hello.ok", "snapshot"]
    peer.send("move.step", {"dir": "down", "n": 1})
    peer.send("region.park", {"region": "hoenn"})
    peer.send("debug.status")
    rig.step(40)
    assert peer.messages() and not peer.closed()
    no_faults(rig)


def test_hello_snapshot_and_steps_in_the_field():
    rig = field_rig()
    E = rig.E
    E.addObject(1, rig.b.to_lua({"x": 3, "y": 4, "gfx": 4}))
    E.addObject(2, rig.b.to_lua({"x": 8, "y": 4, "gfx": 1, "sight": 2, "facing": "left"}))
    E.addObject(127, rig.b.to_lua({"x": 5, "y": 6}))
    E.setFlag(b"FLAG_SYS_POKEMON_GET")
    E.setFlag(b"FLAG_BADGE01_GET")
    E.game.session.bag.pockets.ITEMS = rig.b.to_lua([{"id": 2, "qty": 2}])
    peer = rig.connect()
    seq = peer.hello()
    msgs = peer.messages()
    ok, snap = msgs[0], msgs[1]
    assert ok["type"] == "hello.ok" and ok["re"] == seq
    assert (ok["data"]["game"], ok["data"]["family"], ok["data"]["compat"]["maxDex"]) == ("emerald", "gen3", 386)
    data = snap["data"]
    assert snap["type"] == "snapshot" and data["ingame"] is True
    assert data["player"] == {"map": "EM_TEST_TOWN", "x": 5, "y": 6, "facing": "down"}
    assert data["state"]["mode"] == "idle"
    assert data["flags"] == ["FLAG_BADGE01_GET", "FLAG_SYS_POKEMON_GET"]
    assert (data["badges"], data["bag"], data["money"]) == (["STONEBADGE"], {"TEST_POTION": 2}, 3000)
    assert [(n["id"], n["kind"], n["sprite"]) for n in data["npcs"]["list"]] == [
        ("O1", "person", "OBJ_EVENT_GFX_TEST_GIRL"), ("O2", "trainer", "OBJ_EVENT_GFX_TEST_HIKER")]
    types = [m["type"] for m in msgs[2:]]
    assert {"npc.set", "bag.sync", "game.status"} <= set(types)
    status = next(m for m in msgs if m["type"] == "game.status")["data"]
    assert (status["game"], status["playthrough"], status["badges"], status["markers"]) == (
        "emerald", "fresh", 1, {"starter": True, "champion": False})
    rig.step(30)
    peer.messages()
    seq = peer.send("debug.status")
    rig.step()
    st = next(m for m in peer.messages() if m.get("re") == seq)["data"]
    assert (st["ingame"], st["topState"], st["mode"], st["engineAdapter"], st["engineGroupsMissing"]) == (
        True, "Overworld", "idle", True, 0)
    assert (st["badges"], st["money"], st["bagTotal"], st["npcs"]) == (1, 3000, 2, 3)
    no_faults(rig)


# ------------------------------------------------------------ steps (§4.3)


def test_the_fake_chains_a_held_direction_as_the_engine_does():
    # what chainsHeld answers: a direction held through a landing starts the next step in the same
    # update, so no logic step ever sees the player standing between the two
    rig = field_rig()
    E, inp = rig.E, rig.E.game.input
    inp.sourcePress(inp, b"down", b"test:1")
    standing = []
    for _ in range(40):
        rig.step()
        standing.append(not E.Player.moving)
    assert E.tryMoves and len(E.tryMoves) == 3 and not any(standing)
    inp.sourceRelease(inp, b"down", b"test:1")
    rig.step(20)
    assert (E.Player.cellY, E.Player.moving) == (9, False)


def test_a_move_step_lets_go_of_the_direction_once_the_step_is_under_way():
    # Gen 3's Player.update lands a step and polls the d-pad in the same update (player.lua:1437-1504),
    # so a held direction walks on with no standing poll between steps: the facts say chainsHeld, and
    # the mover lets go as soon as its step is under way (act/kb_move.lua)
    rig = field_rig()
    E = rig.E
    inp = E.game.input
    peer = rig.connect()
    peer.hello()
    peer.messages()
    peer.send("move.step", {"dir": "down", "n": 1})
    rig.step()  # the press; the engine's update starts the step
    assert inp.isDown(inp, b"down") is True and E.Player.moving is True
    rig.step()  # the step is seen under way: answered, and the press let go
    pos = answer(peer.messages(), 1)
    assert pos["type"] == "player.pos" and (pos["data"]["x"], pos["data"]["y"], pos["data"]["kind"]) == (5, 7, "walk")
    assert inp.isDown(inp, b"down") is False and E.Player.moving is True
    rig.step(30)
    later = [m for m in peer.messages() if m["type"] == "player.pos" and m["data"]["kind"] == "walk"]
    assert later == [] and (E.Player.cellX, E.Player.cellY, E.Player.moving) == (5, 7, False)
    assert len(E.tryMoves) == 1
    # two more, queued: one cell each, the second started on the landing poll
    peer.send("move.step", {"dir": "down", "n": 2})
    peer.send("move.step", {"dir": "right", "n": 3})
    msgs = pump(rig, peer, lambda m: m["data"].get("n") == 3)
    assert [(m["data"]["x"], m["data"]["y"]) for m in msgs if m["type"] == "player.pos" and "n" in m["data"]] == [
        (5, 8), (6, 8)]
    rig.step(30)
    assert (E.Player.cellX, E.Player.cellY) == (6, 8) and len(E.tryMoves) == 3
    no_faults(rig)


@pytest.mark.parametrize("why, reason, start, direction", [
    ("elevation", "tile", (5, 6), "down"),
    ("water", "tile", (5, 6), "down"),
    ("entity", "entity", (5, 6), "down"),
    (None, "bounds", (0, 6), "left"),   # off the map: tryMove's own bounds
])
def test_a_blocked_step_is_answered_with_the_engines_reason(why, reason, start, direction):
    rig = field_rig(*start, facing="up")
    if why:
        rig.E.block(5, 7, why.encode())
    peer = rig.connect()
    peer.hello()
    peer.messages()
    peer.send("move.step", {"dir": direction, "n": 7})
    msgs = pump(rig, peer, lambda m: m["data"].get("n") == 7, limit=6)
    blocked = answer(msgs, 7)
    assert blocked["type"] == "player.blocked"
    assert blocked["data"] == {"dir": direction, "reason": reason, "map": "EM_TEST_TOWN", "x": start[0],
                               "y": start[1], "n": 7}
    rig.step(5)
    inp = rig.E.game.input
    assert inp.isDown(inp, direction.encode()) is False and len(rig.E.tryMoves) == 1
    assert not any("move.no_outcome" in line for line in rig.logs())
    no_faults(rig)


def test_a_hot_reload_keeps_one_tap_and_its_verdicts_reach_the_new_instance():
    rig = field_rig()
    peer = rig.connect()
    peer.hello()
    rig.step(3)  # main's steps take the verdicts: the tap is on the Player module
    tap = rig.E.Player.kantoBridgeTryMove
    assert tap is not None
    rig.b.reload()  # F5: a new main.lua instance and adapter; the engine's modules carry over
    assert rig.status.ready is True and peer.closed() and "[info] [gen1-bridge] bridge.replaced_instance" in rig.logs()
    peer = rig.connect()
    peer.hello()
    peer.messages()
    rig.E.block(5, 7, b"entity")
    peer.send("move.step", {"dir": "down", "n": 3})
    msgs = pump(rig, peer, lambda m: m["data"].get("n") == 3, limit=6)
    assert answer(msgs, 3)["type"] == "player.blocked" and answer(msgs, 3)["data"]["reason"] == "entity"
    same = rig.b.eval("function(x, y) return rawequal(x, y) end")
    assert same(rig.E.Player.kantoBridgeTryMove, tap) and same(rig.E.Player.tryMove, tap.wrapper)
    no_faults(rig)


# ------------------------------------------------------------ saves (§4.7)


def test_a_menu_save_goes_out_as_save_begin():
    rig = field_rig()
    peer = rig.connect()
    peer.hello()
    peer.messages()
    assert rig.E.game.saveGame(rig.E.game) is True  # the START menu's SAVE
    msgs = pump(rig, peer, lambda m: m["type"] == "save.begin", limit=3)
    begin = next(m for m in msgs if m["type"] == "save.begin")["data"]
    assert begin["reason"] == "menu" and begin["save"].startswith("v-") and begin["at"] == 1700000001
    no_faults(rig)


def test_the_nurses_heal_arms_the_center_save():
    rig = field_rig()
    rig.world("EM_TEST_CENTER", 7, 4, "up")
    peer = rig.connect()
    peer.hello()
    peer.messages()
    ran = rig.b.eval("{ n = 0 }")
    vanilla = rig.b.eval("function(ran) return function(ctx, op, row) ran.n = ran.n + 1 return 'yield' end end")(ran)
    row = rig.b.to_lua({"op": "special", "id": 7})  # special HealPlayerParty (the fake's made-up id)
    result = rig.b.FakeMod.call(rig.b.mod, b"script.command", vanilla, rig.b.eval("{}"), b"special", row)
    assert result == b"yield" and ran.n == 1  # the engine's own command ran
    msgs = pump(rig, peer, lambda m: m["type"] == "save.begin", limit=3)
    assert next(m for m in msgs if m["type"] == "save.begin")["data"]["reason"] == "center"
    assert len(rig.E.saveCalls) == 1 and rig.status.centerSaves == 1
    no_faults(rig)


# ------------------------------------------------------------ the region acts (§4.8)


def test_region_start_new_game_answers_once_birchs_speech_runs():
    rig = Rig(family="gen3")
    peer = rig.connect()
    peer.hello()
    msgs = peer.messages()
    assert msgs[1]["type"] == "snapshot" and msgs[1]["data"]["ingame"] is False
    seq = peer.send("region.start", {"region": "hoenn"})
    msgs = pump(rig, peer, lambda m: m.get("re") == seq, limit=4)
    # answered at once: the adapter's facts say a NEW GAME is starting (Birch's speech, no field yet)
    assert reply(msgs, seq)["data"] == {"ok": True, "kind": "start", "detail": "new"}
    assert rig.E.game.boot.phase == b"oak" and len(rig.E.newGameStarts) == 1
    # no save.created until the speech ends: game.status follows on its periodic compare
    msgs += pump(rig, peer, lambda m: m["type"] == "game.status", limit=12)
    status = [m["data"] for m in msgs if m["type"] == "game.status"][-1]
    assert (status["playthrough"], status["ingame"], status["started"]) == ("fresh", False, True)
    seq = peer.send("region.start", {"region": "hoenn"})  # Birch's speech already under way
    msgs = pump(rig, peer, lambda m: m.get("re") == seq, limit=4)
    assert reply(msgs, seq)["data"] == {"ok": False, "kind": "start", "reason": "not_title"}
    no_faults(rig)


def test_region_start_continues_the_active_slots_save():
    rig = Rig(family="gen3")
    E = rig.E
    E.slotSave(b"slot1", E.newSession(b"EM_TEST_HOUSE", 3, 4, b"up"))
    E.SaveData.setActiveSlot(b"emerald", b"slot1")
    peer = rig.connect()
    peer.hello()
    peer.messages()
    seq = peer.send("region.start", {"region": "hoenn"})
    msgs = pump(rig, peer, lambda m: m.get("re") == seq, limit=4)
    assert reply(msgs, seq)["data"] == {"ok": True, "kind": "start", "detail": "continue"}
    if not any(m["type"] == "game.status" for m in msgs):
        msgs += pump(rig, peer, lambda m: m["type"] == "game.status", limit=12)
    status = [m["data"] for m in msgs if m["type"] == "game.status"][-1]
    assert (status["playthrough"], status["ingame"], status["slot"]) == ("saved", True, 1)
    pos = [m["data"] for m in msgs if m["type"] == "player.pos"][-1]
    assert (pos["map"], pos["x"], pos["y"], pos["kind"]) == ("EM_TEST_HOUSE", 3, 4, "sync")
    no_faults(rig)


def test_region_park_holds_the_field_and_resume_lets_it_go():
    rig = field_rig()
    peer = rig.connect()
    peer.hello()
    peer.messages()
    seq = peer.send("region.park", {"region": "hoenn"})
    msgs = pump(rig, peer, lambda m: m.get("re") == seq, limit=4)
    assert reply(msgs, seq)["data"] == {"ok": True, "kind": "park"}
    rig.step()
    assert rig.status.regionHeld is True and rig.E.Field._locks[b"kb_park"] is True
    rig.E.Field._locks = rig.b.eval("{}")  # Field.start wiped the tags: the next step puts it back
    rig.step()
    assert rig.E.Field._locks[b"kb_park"] is True
    peer.send("move.step", {"dir": "down", "n": 9})  # parked: the hidden player takes no step
    msgs = pump(rig, peer, lambda m: m["data"].get("n") == 9, limit=4)
    assert answer(msgs, 9)["data"]["reason"] == "script" and len(rig.E.tryMoves) == 0
    seq = peer.send("region.resume", {"region": "hoenn", "to": {"map": "EM_TEST_TOWN", "x": 5, "y": 6,
                                                                 "facing": "down"}})
    msgs = pump(rig, peer, lambda m: m.get("re") == seq, limit=4)
    assert reply(msgs, seq)["data"] == {"ok": True, "kind": "resume"}
    rig.step()
    assert rig.status.regionHeld is False and rig.E.Field._locks[b"kb_park"] is None
    calls = rig.E.audioCalls
    assert [calls[i].op for i in range(1, len(calls) + 1)] == [b"fadeOut", b"fadeIn"]
    no_faults(rig)
