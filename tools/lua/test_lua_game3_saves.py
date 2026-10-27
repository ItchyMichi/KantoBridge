"""MG7 (docs/specs/GEN3_BRIDGE_PLAN.md §4.7): the game3 adapter's save slots, playthrough, guarded
saves, the nurse's heal and the save queue on the fake Gen 3 engine. Everything is made up."""

import pytest

from adapter3 import Adapter3, plain

pytestmark = pytest.mark.lua


def save_table(a, map_id="EM_TEST_HOUSE", x=3, y=4, facing="up"):
    """A made-up Gen 3 save (what CONTINUE loads)."""
    s = a.E.newSession(map_id.encode(), x, y, facing.encode())
    s.meta = a.lua({"savedAt": 1700000100, "sessionStart": 1700000000, "engine": "0.3.85"})
    return s


def recording_save(a):
    """Game3:saveGame wrapped so the save's reason (savingReason, as save.writing sees it) is kept."""
    rec = a.b.eval("{}")
    a.E.Game3.saveGame = a.b.eval("""function(eng, original, rec) return function(self)
      rec[#rec + 1] = eng.savingReason() or "none"
      return original(self)
    end end""")(a.eng, a.E.Game3.saveGame, rec)
    return rec


# ------------------------------------------------------------ slots (the engine's shared registry)


def test_copy_slot_copies_a_save_into_a_new_labelled_slot():
    # WORLD_SESSIONS_PLAN WS2 (D14): the shared slots part's copySlot, through the engine's duplicateSlot
    a = Adapter3()
    E, eng = a.E, a.eng
    E.slotSave(b"slot1", save_table(a))
    new = eng.copySlot(b"slot1", b"MULTIPLAYER 0123abcd")
    assert new == b"slot2" and eng.slotHasSave(b"slot2") is True and E.slotReg.names.slot2 == b"MULTIPLAYER 0123abcd"
    none, why = eng.copySlot(b"slot9", None)
    assert none is None and why == b"refused"


def test_slot_functions_against_the_registry():
    a = Adapter3()
    E, eng = a.E, a.eng
    assert plain(eng.slotState()) == {"version": "emerald", "api": True, "cart": False}
    assert eng.resolveActiveSlot() is None and eng.saveSlot() == 0
    assert eng.createSlot(b"KB TEST") == b"slot1" and eng.createSlot() == b"slot2"
    assert plain(E.slotReg.list) == ["slot1", "slot2"] and E.slotReg.names.slot1 == b"KB TEST"
    assert eng.slotRegistered(b"slot2") is True and eng.slotRegistered(b"slot3") is False
    assert eng.activateSlot(b"slot2") is True
    assert plain(eng.slotState())["active"] == "slot2" and eng.resolveActiveSlot() == b"slot2"
    assert eng.saveSlot() == 2
    assert eng.labelSlot(b"slot2", b"KB OTHER") is True and eng.labelSlot(b"slot9", b"X") is False
    assert eng.slotHasSave(b"slot2") is False
    E.slotSave(b"slot2", save_table(a))
    assert eng.slotHasSave(b"slot2") is True and eng.slotHasSave(b"slot1") is False
    assert eng.deleteSlot(b"slot2") is True and eng.deleteSlot(b"slot2") is False
    assert eng.saveSlot() == 1 and eng.slotRegistered(b"slot2") is False  # the active one moved to list[1]
    # every call names the running game's registry
    assert {c["version"] for c in plain(E.slotCalls)} == {"emerald"}


@pytest.mark.parametrize("bad", ["slotx", "slot", "../slot1", "SLOT1", "slot12345678901234"])
def test_malformed_slot_ids_are_refused_without_asking_the_engine(bad):
    a = Adapter3()
    a.E.slotSave(b"slot1", save_table(a))
    calls = len(a.E.slotCalls)
    eng, b = a.eng, bad.encode()
    assert (eng.slotRegistered(b), eng.slotHasSave(b), eng.labelSlot(b, b"X"), eng.deleteSlot(b),
            eng.activateSlot(b)) == (False, False, False, False, False)
    assert len(a.E.slotCalls) == calls


def test_a_failing_create_is_logged_and_nil():
    a = Adapter3()
    a.E.createFails = True
    assert a.eng.createSlot(b"KB TEST") is None
    assert a.lines() == [("warn", "engine.create_slot_failed")]


def test_without_the_slot_api_nothing_is_written():
    a = Adapter3()
    a.E.SaveData.createSlot = None
    assert plain(a.eng.slotState())["api"] is False
    assert a.eng.createSlot() is None and a.eng.activateSlot(b"slot1") is False


# ------------------------------------------------------------ the playthrough and the title


def test_playthrough_title_fresh_and_saved():
    a = Adapter3()
    eng = a.eng
    assert eng.playthrough() == b"title"
    a.E.game.boot.phase = b"oak"  # Birch's speech: a NEW GAME under way, never saved
    assert eng.playthrough() == b"fresh"
    s = a.field()
    assert eng.playthrough() == b"fresh"
    loaded = a.b.eval("function(s) return { [s] = true } end")(s)  # what save.loaded brought
    assert eng.playthrough(loaded) == b"saved" and eng.playthrough(a.lua({})) == b"fresh"
    s.meta = a.lua({"savedAt": 1700000100})
    assert eng.playthrough() == b"saved"
    a.E.leaveField()
    assert eng.playthrough() == b"title"


def test_a_queued_save_makes_the_playthrough_saved():
    a = Adapter3()
    a.field()
    a.eng.queueSave(b"menu")
    assert a.eng.playthrough() == b"saved"
    a.E.leaveField()  # a NEW GAME after the title: another session, fresh again
    a.field()
    assert a.eng.playthrough() == b"fresh"


def test_title_menu_open_is_the_boot_menu_phase():
    a = Adapter3()
    assert a.eng.titleMenuOpen() is False
    a.E.game.boot.phase = b"menu"
    assert a.eng.titleMenuOpen() is True
    a.field()
    assert a.eng.titleMenuOpen() is False


def test_to_title_skips_the_intro():
    a = Adapter3()
    a.field()
    assert a.eng.toTitle() is True
    assert plain(a.E.titleCalls) == [{"skipIntro": True}]
    assert (a.E.game.phase, a.E.game.boot.phase, a.eng.inGame()) == (b"boot", b"title", False)
    a.E.Game3.returnToTitle = a.b.eval("function() error('teardown failed') end")
    assert a.eng.toTitle() is False and a.lines() == [("warn", "engine.return_to_title_failed")]


# ------------------------------------------------------------ guarded saves


def test_the_switch_gate_is_quick_save_allowed_in_the_field():
    a = Adapter3()
    assert a.eng.switchGateOpen() is False  # the title
    a.field()
    assert a.eng.switchGateOpen() is True
    a.E.openMessage(b"A MADE-UP LINE.")  # the HUD is busy
    assert a.eng.switchGateOpen() is False
    a.E.closeMessage()
    a.E.Space.vm.running = True  # a script: START not allowed (hud.lua:164-178)
    assert a.eng.switchGateOpen() is False
    a.E.Space.vm.running = False
    assert a.eng.holdAway() is True  # any lock tag, the park's too: as Gen 1's hold, no save while parked
    assert a.eng.switchGateOpen() is False
    assert a.eng.releaseAway() is True and a.eng.switchGateOpen() is True
    a.E.quickSaveOk = False  # no SAVE offered here
    assert a.eng.switchGateOpen() is False
    a.E.quickSaveOk = None
    a.E.Game3.quickSaveAllowed = a.b.eval("function() error('gate broke') end")
    assert a.eng.switchGateOpen() is False


def test_switch_save_written_refused_and_error():
    a = Adapter3()
    a.E.SaveData.setActiveSlot(b"emerald", b"slot1")
    a.field()
    reasons = recording_save(a)
    assert a.eng.switchSave() == b"written"
    assert plain(reasons) == ["switch"] and a.eng.savingReason() is None
    assert a.E.slotFiles.slot1 is True and a.E.slotSaves.slot1.map == b"EM_TEST_TOWN"
    a.E.saveResult = b"refuse"  # a save.write veto
    assert a.eng.switchSave() == b"refused"
    a.E.saveResult = b"error"
    assert a.eng.switchSave() == b"error" and a.eng.savingReason() is None
    assert a.lines() == [("warn", "engine.write_save_failed")]
    a.E.saveResult = None
    a.E.game.session = None  # saveGame's own guard: nothing written
    assert a.eng.switchSave() == b"refused"


def test_drop_playthrough_counts_and_clears():
    a = Adapter3()
    a.field()
    a.eng.queueSave(b"menu")
    a.eng.armCenterSave()
    assert a.eng.dropPlaythrough() == 1
    assert a.eng.takeSave() is None and a.eng.centerSaveArmed() is False and a.eng.dropPlaythrough() == 0


# ------------------------------------------------------------ the nurse (special HealPlayerParty)


def next_recorder(a):
    rec = a.b.eval("{}")
    fn = a.b.eval("""function(rec) return function(ctx, op, row)
      rec[#rec + 1] = { ctx = ctx, op = op, row = row }
      return "ran", op
    end end""")(rec)
    return rec, fn


@pytest.mark.parametrize("row", [{"op": "special", "id": 7}, {"op": "special", 1: 7}, {"special": 7}])
def test_the_heal_special_arms_nurse_healed_once(row):
    a = Adapter3()
    a.field("EM_TEST_CENTER", 7, 4)
    rec, nxt = next_recorder(a)
    ctx, lua_row = a.lua({"vm": "made-up"}), a.lua(row)
    assert a.eng.scriptCommand(nxt, ctx, b"special", lua_row) == (b"ran", b"special")
    assert len(rec) == 1 and a.same(rec[1].ctx, ctx) and a.same(rec[1].row, lua_row) and rec[1].op == b"special"
    assert plain(a.eng.nurseHealed()) == {"map": "EM_TEST_CENTER"}
    assert a.eng.nurseHealed() is None


def test_other_commands_pass_through_and_arm_nothing():
    a = Adapter3()
    a.field("EM_TEST_CENTER", 7, 4)
    rec, nxt = next_recorder(a)
    assert a.eng.scriptCommand(nxt, None, b"special", a.lua({"id": 8})) == (b"ran", b"special")  # another special
    assert a.eng.scriptCommand(nxt, None, b"special", a.lua({"id": 99})) == (b"ran", b"special")  # no name
    assert a.eng.scriptCommand(nxt, None, b"setflag", a.lua({1: 7})) == (b"ran", b"setflag")
    assert a.eng.scriptCommand(nxt, None, b"special", None) == (b"ran", b"special")
    assert len(rec) == 4 and a.eng.nurseHealed() is None


def test_the_special_name_falls_back_to_the_constants():
    a = Adapter3()
    a.field("EM_TEST_CENTER", 7, 4)
    a.E.Natives.specialName = None
    _, nxt = next_recorder(a)
    a.eng.scriptCommand(nxt, None, b"special", a.lua({"id": 7}))
    assert plain(a.eng.nurseHealed()) == {"map": "EM_TEST_CENTER"}


def test_the_hook_runs_the_command_before_game_ready():
    a = Adapter3()
    bare = a.Engine.new(a.b.engine_opts())  # no game yet: no state to arm
    rec, nxt = next_recorder(a)
    assert bare.scriptCommand(nxt, None, b"special", a.lua({"id": 7})) == (b"ran", b"special")
    assert len(rec) == 1 and bare.nurseHealed() is None


# ------------------------------------------------------------ the center save


def test_the_center_save_waits_for_the_gate_and_writes():
    a = Adapter3()
    eng = a.eng
    assert eng.centerSaveStep() is None and eng.armCenterSave() is False  # nothing armed; the title
    a.field("EM_TEST_CENTER", 7, 4)
    reasons = recording_save(a)
    a.E.Field.lock(b"default")  # the nurse's script holds the field (lock / release)
    a.E.openMessage(b"A MADE-UP FAREWELL.")
    assert eng.armCenterSave() is True and eng.centerSaveArmed() is True
    assert eng.centerSaveStep() is None and eng.centerSaveArmed() is True  # the HUD is busy: it waits
    a.E.closeMessage()
    assert eng.centerSaveStep() is None  # the script's lock still up: saveGame has no guards of its own
    a.E.Field.unlock(b"default")
    assert eng.centerSaveStep() == b"written" and plain(reasons) == ["center"]
    assert eng.centerSaveArmed() is False and eng.centerSaveStep() is None


def test_the_center_save_is_dropped_with_its_playthrough():
    a = Adapter3()
    a.field("EM_TEST_CENTER", 7, 4)
    a.eng.armCenterSave()
    a.E.leaveField()
    a.field("EM_TEST_TRUCK", 2, 2)  # another session (a NEW GAME after the title)
    assert a.eng.centerSaveStep() == b"dropped" and a.eng.centerSaveArmed() is False
    assert len(a.E.saveCalls) == 0


@pytest.mark.parametrize("result, answer", [("refuse", "refused"), ("error", "error")])
def test_the_center_save_reports_a_failed_write(result, answer):
    a = Adapter3()
    a.field("EM_TEST_CENTER", 7, 4)
    a.eng.armCenterSave()
    a.E.saveResult = result.encode()
    assert a.eng.centerSaveStep() == answer.encode() and a.eng.centerSaveArmed() is False


# ------------------------------------------------------------ the save queue (save.begin)


def test_queue_take_and_serial():
    a = Adapter3()
    eng = a.eng
    a.field()
    assert eng.bridgeToken(b"tok12345") == b"tok12345"
    assert eng.queuedSaveSerial() is None and eng.takeSave() is None
    assert eng.queueSave(b"menu", a.lua({"pair": "p-1", "at": 5})) == (b"v-tok12345-1", False)
    assert eng.queuedSaveSerial() == 1
    assert eng.queueSave(b"center", a.lua({"pair": "p-2", "at": 6})) == (b"v-tok12345-2", True)  # replaced
    assert plain(eng.takeSave()) == {"save": "v-tok12345-2", "reason": "center", "pair": "p-2", "at": 6,
                                     "current": True}
    assert eng.takeSave() is None and eng.queuedSaveSerial() is None
    assert eng.nextSerial() == 3  # one counter behind save ids and the other ids


def test_a_save_taken_after_the_playthrough_changed_is_not_current():
    a = Adapter3()
    a.field()
    a.eng.queueSave(b"switch")
    a.E.leaveField()
    assert plain(a.eng.takeSave())["current"] is False
    a.eng.queueSave(b"menu")  # at the title (no session)
    assert plain(a.eng.takeSave())["current"] is False


def test_no_queue_before_game_ready():
    a = Adapter3()
    bare = a.Engine.new(a.b.engine_opts())
    assert bare.queueSave(b"menu") == (None, False) and bare.takeSave() is None and bare.savingReason() is None
