"""MG9 (docs/specs/GEN2_BRIDGE_PLAN.md G2A, G2B, G2C): gen1-bridge's main.lua on the fake Gen 2 engine
(Rig(family="gen2")), seen from a fake Minecraft peer: the handshake and snapshot on Crystal (map ids
reported as G2_*, J4), steps (Gen 1's movement.collision verdicts; a held direction let go once its step is
under way, chainsHeld), a walked warp, saves and the nurse, the region acts (the park's hold, a resume
through the world facade, a start from the title), the UI (a question and its yes/no, a script menu,
the start menu, Oak's whole speech answered through the UI with the client's own name or a preset), and the
story (numbered flags, who stands where, a map's block changes against the swap sites). Every message is
schema-checked. Everything is made up."""

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


def connected(rig):
    peer = rig.connect()
    peer.hello()
    peer.messages()
    return peer


def opened(msgs, kind):
    return next(m["data"] for m in msgs if m["type"] == "ui.open" and m["data"]["kind"] == kind)


def music(rig):
    calls = rig.E.musicCalls
    return [calls[i].op.decode() for i in range(1, len(calls) + 1)]


# ------------------------------------------------------------ boot and handshake


def test_main_boots_on_crystal_with_the_gen2_adapter():
    rig = Rig(family="gen2")
    st = rig.status
    assert (st.ready, st.engineId, st.engineAdapter, st.game, st.engine) == (True, b"crystal", True, b"crystal",
                                                                             b"0.3.85")
    # the export, battles and routes come with G2D-G2H
    assert sorted(g.decode() for g in st.engineGroupsMissing.values()) == ["battles", "export", "routes"]
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


# ------------------------------------------------------------ the UI (G2B)


def test_a_question_and_its_yes_no_are_answered_through_the_ui():
    rig = field_rig()
    E = rig.E
    peer = connected(rig)
    E.openYesNo(rig.b.to_lua([b"TAKE IT?"]), rig.b.eval("function(yes) E_answer = yes end"))
    msgs = pump(rig, peer, lambda m: m["type"] == "ui.open" and m["data"]["kind"] == "choice")
    text, choice = opened(msgs, "text"), opened(msgs, "choice")
    assert (text["pages"], text["choice"]) == ([["TAKE IT?"]], True)
    assert (choice["labels"], choice["index"], choice["question"]) == (["YES", "NO"], 0, "TAKE IT?")
    peer.send("ui.pick", {"ui": choice["ui"], "index": 1})  # NO: B
    msgs = pump(rig, peer, lambda m: m["type"] == "ui.close" and m["data"]["ui"] == text["ui"])
    assert rig.b.eval("function() return E_answer end")() is False
    assert [m["data"]["ui"] for m in msgs if m["type"] == "ui.close"] == [choice["ui"], text["ui"]]
    no_faults(rig)


def test_a_script_menu_is_a_list_and_the_start_menu_leaves_quit_out():
    rig = field_rig()
    E = rig.E
    peer = connected(rig)
    E.openScriptMenu(rig.b.to_lua([b"ONE", b"TWO", b"THREE"]))
    lst = opened(pump(rig, peer, lambda m: m["type"] == "ui.open"), "list")
    assert ([i["label"] for i in lst["items"]], lst["index"], lst["screen"]) == (["ONE", "TWO", "THREE"], 0,
                                                                                  "ListMenu")
    peer.send("ui.pick", {"ui": lst["ui"], "index": 2})
    pump(rig, peer, lambda m: m["type"] == "ui.close" and m["data"]["ui"] == lst["ui"])
    E.openScriptMenu(rig.b.to_lua([b"ONE", b"TWO"]))
    lst = opened(pump(rig, peer, lambda m: m["type"] == "ui.open"), "list")
    peer.send("ui.back", {"ui": lst["ui"]})
    pump(rig, peer, lambda m: m["type"] == "ui.close" and m["data"]["ui"] == lst["ui"])
    assert list(E.menuAnswers.values()) == [3, 0]  # 1-based, B is 0
    E.openStartMenu()
    menu = opened(pump(rig, peer, lambda m: m["type"] == "ui.open"), "menu")
    assert [i["label"] for i in menu["items"]] == ["TEST POKEDEX", "TEST POKEMON", "TEST PACK", "TEST STATUS",
                                                   "TEST SAVE", "TEST OPTION"]
    assert menu["screen"] == "StartMenu"
    peer.send("ui.pick", {"ui": menu["ui"], "index": 2})
    msgs = pump(rig, peer, lambda m: m["type"] == "ui.open" and m["data"]["kind"] == "mirror")
    assert opened(msgs, "mirror")["screen"] == "Gen2PackMenu" and list(E.startChoices.values()) == [b"pack"]
    no_faults(rig)


def speech_through_the_ui(rig, peer, name, girl=False):
    """region.start, then every UI Oak's speech opens answered as the client would: text advanced, the
    gender picked by its index, the naming screen given ``name``. Returns everything received."""
    E = rig.E
    seq = peer.send("region.start", {"region": "johto"})
    seen, answered = [], set()
    for _ in range(200):
        rig.step()
        new = peer.messages()
        seen.extend(new)
        for m in new:
            if m["type"] != "ui.open" or m["data"]["ui"] in answered:
                continue
            data = m["data"]
            answered.add(data["ui"])
            if data["kind"] == "text":
                peer.send("ui.advance", {"ui": data["ui"]})
            elif data["kind"] == "choice":
                peer.send("ui.pick", {"ui": data["ui"], "index": 1 if girl else 0})
            elif data["kind"] == "naming":
                peer.send("ui.name", {"ui": data["ui"], "glyphs": list(name)})
        if E.game.phase == b"play":
            break
    assert E.game.phase == b"play" and reply(seen, seq)["data"]["detail"] == "new"
    return seen


def test_oaks_speech_is_answered_through_the_ui_with_the_clients_own_name():
    # MULTI_GAME_PLAN D6: the client names the player itself; the name pick's NEW NAME, then the keyboard
    # (text set, the cursor on END, A) -- one naming screen from the presets to END
    rig = Rig(family="gen2")
    E = rig.E
    peer = connected(rig)
    seen = speech_through_the_ui(rig, peer, "KAI", girl=True)
    save = E.game.save
    assert (save.player.name, save.player.gender, save.rtc.startMinute) == (b"KAI", b"female", 0)
    assert E.keyboards == 1
    opens = [m["data"] for m in seen if m["type"] == "ui.open"]
    assert [d["kind"] for d in opens] == ["choice", "text", "text", "naming", "text"]
    gender, naming = opens[0], opens[3]
    assert (gender["labels"], gender["screen"]) == (["TEST BOY", "TEST GIRL"], "GenderSelect")
    assert (naming["title"], naming["presets"], naming["max"], naming["value"]) == (
        "TEST NAME?", ["TESTA", "TESTB", "TESTC"], 7, "")
    assert naming["grid"][0][0][:3] == ["A", "B", "C"]
    assert sum(1 for m in seen if m["type"] == "ui.close" and m["data"]["ui"] == naming["ui"]) == 1
    assert [m for m in seen if m["type"] == "error"] == []
    no_faults(rig)


def test_a_preset_name_is_answered_by_picking_it():
    rig = Rig(family="gen2")
    E = rig.E
    peer = connected(rig)
    speech_through_the_ui(rig, peer, "TESTB")
    assert (E.game.save.player.name, E.game.save.player.gender, E.keyboards) == (b"TESTB", b"male", 0)
    no_faults(rig)


# ------------------------------------------------------------ the story (G2C)


def story_rig(**world):
    """A rig whose map data is copied at mods.loaded after the test's own changes to the made-up maps: TEST_TOWN's
    TILES callback (E.tileCallbacks) runs script 1:4000, which changes block (2, 1) to 0x22 while flag 30 is clear;
    block (1, 0) is a Cut tree (0x40) and (2, 0) tall grass (0x50); a person behind flag 40."""
    rig = Rig(family="gen2", ready=False)
    E = rig.E
    E.scripts[b"1:4000"] = rig.b.to_lua([{"op": "checkevent", "event": 30}, {"op": "iftrue", "script": "1:4010"},
                                         {"op": "changeblock", "x": 4, "y": 2, "block": 0x22},
                                         {"op": "endcallback"}])
    E.scripts[b"1:4010"] = rig.b.to_lua([{"op": "endcallback"}])
    town = E.maps[b"TEST_TOWN"]
    town.callbacks = rig.b.to_lua([{"callback": "MAPCALLBACK_TILES", "scriptKey": "1:4000"}])
    town.blocks[2] = 0x40
    town.blocks[3] = 0x50
    E.tileCallbacks[b"TEST_TOWN"] = rig.b.to_lua([rig.b.eval("""function(w)
      if not w.events:get(30) then w:changeBlock(2, 1, 0x22) end
    end""")])
    E.addNpc(1, rig.b.to_lua({"map": "TEST_TOWN", "x": 1, "y": 1, "eventFlag": 40}))
    rig.b.mods_loaded()
    rig.b.ready()
    rig.world("TEST_TOWN", world.get("x", 5), world.get("y", 6), world.get("facing", "down"))
    return rig


def counters(rig, peer):
    seq = peer.send("debug.status")
    msgs = pump(rig, peer, lambda m: m.get("re") == seq)
    return reply(msgs, seq)["data"]


def story(msgs, kind):
    return [m["data"] for m in msgs if m["type"] == kind]


def test_the_snapshot_carries_the_story_and_the_map_data_was_copied():
    rig = story_rig()
    assert any("story.pristine maps=5" in line for line in rig.logs())
    peer = rig.connect()
    peer.hello()
    snap = next(m for m in peer.messages() if m["type"] == "snapshot")["data"]
    # the callback's block, against the map data the world was built from; nobody hidden
    assert snap["blocks"] == [{"map": "G2_TEST_TOWN", "bx": 2, "by": 1, "block": 0x22}]
    assert snap["toggles"] == {} and snap["flags"] == []
    st = counters(rig, peer)
    assert (st["storyPristine"], st["storyUnknownBlocks"]) == (True, 0)
    no_faults(rig)


def test_flags_toggles_and_blocks_follow_the_story():
    rig = story_rig()
    E = rig.E
    w = E.game.world
    peer = rig.connect()
    peer.hello()
    peer.messages()
    # a flag set by a script: story.flags with its E<number> name
    w.events.set(w.events, 40, True)
    w.events.set(w.events, 30, True)
    msgs = pump(rig, peer, lambda m: m["type"] == "story.flags")
    assert story(msgs, "story.flags") == [{"set": ["E30", "E40"], "clear": []}]
    # nobody moves until the next load; the reload hides the person and the callback leaves the block alone
    rig.step(10)
    assert story(peer.messages(), "story.toggles") == []
    w.loadMap(w, b"TEST_TOWN", 5, 6, b"down", b"warp")
    msgs = pump(rig, peer, lambda m: m["type"] == "story.toggles")
    assert story(msgs, "story.toggles") == [{"map": "G2_TEST_TOWN", "obj": "O1", "visible": False}]
    assert story(msgs, "story.blocks") == [{"map": "G2_TEST_TOWN", "changes": [{"bx": 2, "by": 1, "block": 1}]}]
    # a Cut tree and its tall grass during the visit: both known (the tree a site, the grass the World's record)
    w.changeBlock(w, 1, 0, 0x41)
    w.changeBlock(w, 2, 0, 0x51)
    msgs = pump(rig, peer, lambda m: m["type"] == "story.blocks")
    assert story(msgs, "story.blocks") == [{"map": "G2_TEST_TOWN", "changes": [{"bx": 1, "by": 0, "block": 0x41},
                                                                                {"bx": 2, "by": 0, "block": 0x51}]}]
    st = counters(rig, peer)
    assert (st["storyUnknownBlocks"], st["storySites"]) == (0, 2)
    no_faults(rig)


def test_an_unknown_block_change_is_reported_and_logged():
    rig = story_rig()
    w = rig.E.game.world
    peer = rig.connect()
    peer.hello()
    peer.messages()
    w.changeBlock(w, 4, 4, 0x77)    # no site, no Cut
    msgs = pump(rig, peer, lambda m: m["type"] == "story.blocks")
    assert story(msgs, "story.blocks") == [{"map": "G2_TEST_TOWN", "changes": [{"bx": 4, "by": 4, "block": 0x77}]}]
    assert counters(rig, peer)["storyUnknownBlocks"] == 1
    errors = [line for line in rig.logs() if "story.unknown_block" in line]
    assert errors == ["[error] [gen1-bridge] story.unknown_block count=1 map=G2_TEST_TOWN total=1"]

