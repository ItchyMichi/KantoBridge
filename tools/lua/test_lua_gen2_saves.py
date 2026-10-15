"""MG9 (docs/specs/GEN2_BRIDGE_PLAN.md G2A, §5.7): the gen2 adapter's saves (gen1-bridge/lib/engine/gen2/
saves.lua) and the parts it shares with game3 (lib/engine/common/slots.lua, savequeue.lua) on the fake Gen 2
engine: the playthrough, Game2:writeSave behind quickSaveAllowed, the title, the nurse's `special HealParty`
seen in script.command (the decoded op as the hook's fifth argument, handed back to the engine), the center
save, the slot registry and the queue of announced saves. Everything is made up."""

import pytest

from adapter2 import Adapter2, plain

pytestmark = pytest.mark.lua


def new_game_in_field(a):
    """NEW GAME answered through Oak's speech (every screen an A press) into the field."""
    g = a.E.game
    g.newGame(g)
    for _ in range(40):
        if g.stack.top(g.stack) is None:
            break
        a.press("a")
        a.tick()
        a.release("a")
        a.tick()
    assert g.phase == b"play"


# ------------------------------------------------------------ the playthrough and the title


def test_the_playthrough():
    a = Adapter2()
    assert a.eng.playthrough(None) == b"title"
    g = a.E.game
    g.newGame(g)
    assert a.eng.playthrough(None) == b"fresh", "Oak's speech: a new game under way"
    new_game_in_field(a)
    assert a.eng.playthrough(None) == b"fresh"
    loaded = a.b.eval("function(s) return { [s] = true } end")(g.save)
    assert a.eng.playthrough(loaded) == b"saved"
    a.eng.queueSave(b"menu", None)
    assert a.eng.playthrough(None) == b"saved", "saved since"


def test_title_menu_open():
    a = Adapter2()
    assert a.eng.titleMenuOpen() is False
    a.E.pushScreen(b"Gen2MainMenu")
    assert a.eng.titleMenuOpen() is True


def test_switch_save_writes_through_write_save():
    a = Adapter2()
    a.field()
    assert a.eng.switchGateOpen() is True
    assert a.eng.switchSave() == b"written" and len(a.E.saveCalls) == 1
    a.E.saveResult = b"refuse"
    assert a.eng.switchSave() == b"refused"
    a.E.saveResult = b"error"
    assert a.eng.switchSave() == b"error" and ("warn", "engine.write_save_failed") in a.lines()
    assert a.eng.savingReason() is None


def test_the_save_gate_follows_the_worlds_own():
    a = Adapter2()
    assert a.eng.switchGateOpen() is False, "the title"
    a.field()
    a.press("down")
    a.tick()
    assert a.eng.switchGateOpen() is False, "mid-step"
    a.release("down")
    a.tick(20)
    assert a.eng.switchGateOpen() is True
    a.E.quickSaveOk = False
    assert a.eng.switchGateOpen() is False


def test_to_title():
    a = Adapter2()
    a.field()
    assert a.eng.toTitle() is True and len(a.E.titleCalls) == 1 and a.E.game.phase == b"boot"


# ------------------------------------------------------------ the Pokémon Center


def nurse(a, special_name, cmd_id=7):
    """script.command as Gen 2's VM raises it (src/script/gen2/Vm.lua:1824-1851): (ctx, name, args, cmd)."""
    ran = a.b.eval("{ n = 0, cmd = nil }")
    vanilla = a.b.eval("""function(ran) return function(ctx, name, args, cmd)
      ran.n = ran.n + 1 ran.cmd = cmd return 'yield' end end""")(ran)
    vm = a.b.eval("function(name) return { specialName = function(_, id) return name end } end")(special_name)
    ctx_ = a.lua({})
    ctx_.vm = vm
    cmd = a.lua({"op": "special", "id": cmd_id})
    result = a.eng.scriptCommand(vanilla, ctx_, b"special", a.lua({}), None, cmd)
    return result, ran, cmd


def test_the_nurses_heal_is_seen_in_script_command():
    a = Adapter2()
    a.field("TEST_CENTER", 4, 4, "up")
    result, ran, cmd = nurse(a, b"HealParty")
    assert result == b"yield" and ran.n == 1 and a.same(ran.cmd, cmd), "the engine's own command, its op handed back"
    assert plain(a.eng.nurseHealed()) == {"map": "G2_TEST_CENTER"}
    assert a.eng.nurseHealed() is None
    nurse(a, b"HealMachineAnim")
    assert a.eng.nurseHealed() is None


def test_the_center_save():
    a = Adapter2()
    a.field()
    assert a.eng.armCenterSave() is True and a.eng.centerSaveArmed() is True
    a.press("down")
    a.tick()
    assert a.eng.centerSaveStep() is None, "the field busy: it waits"
    a.release("down")
    a.tick(20)
    assert a.eng.centerSaveStep() == b"written" and a.eng.centerSaveArmed() is False
    a.eng.armCenterSave()
    g = a.E.game
    g.continueGame(g, a.E.newSave())  # another playthrough
    assert a.eng.centerSaveStep() == b"dropped"


# ------------------------------------------------------------ slots and the queue (lib/engine/common/)


def test_slots_through_the_shared_registry():
    a = Adapter2()
    assert plain(a.eng.slotState()) == {"version": "crystal", "api": True, "cart": False}
    sid = a.eng.createSlot(b"KB TEST")
    assert sid == b"slot1" and a.E.slotReg.names.slot1 == b"KB TEST"
    assert a.eng.activateSlot(sid) is True and a.eng.resolveActiveSlot() == b"slot1" and a.eng.saveSlot() == 1
    assert a.eng.slotRegistered(sid) is True and a.eng.slotHasSave(sid) is False
    a.field()
    a.eng.switchSave()
    assert a.eng.slotHasSave(sid) is True
    assert a.eng.deleteSlot(sid) is True and a.eng.slotRegistered(sid) is False


def test_the_queue_of_announced_saves():
    a = Adapter2()
    a.field()
    a.eng.bridgeToken(b"tok")
    sid, replaced = a.eng.queueSave(b"menu", a.lua({"pair": "p1", "at": 5}))
    assert sid == b"v-tok-1" and replaced is False and a.eng.queuedSaveSerial() == 1
    got = plain(a.eng.takeSave())
    assert got == {"save": "v-tok-1", "reason": "menu", "pair": "p1", "at": 5, "current": True}
    assert a.eng.takeSave() is None
    a.eng.queueSave(b"menu", None)
    assert a.eng.dropPlaythrough() == 1 and a.eng.takeSave() is None


def test_reliability_is_the_shared_part():
    a = Adapter2()
    a.field()
    assert plain(a.eng.metaOf(None)) == {}, "a new game's save has no meta yet"
    a.E.game.writeSave(a.E.game)
    meta = plain(a.eng.metaOf(None))
    assert meta["engine"] == "0.3.85" and meta["savedAt"] > 1700000000
    t = a.eng.persistent(b"test")
    assert a.same(a.eng.persistent(b"test"), t)
