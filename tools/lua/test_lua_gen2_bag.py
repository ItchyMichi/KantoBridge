"""MG9 G2F (docs/specs/GEN2_BRIDGE_PLAN.md §5.10, J13): the Gen 2 adapter's bag part (gen1-bridge/lib/engine/gen2/
bag.lua) and field moves (gen2/fieldmoves.lua) on the fake Gen 2 engine with the real catalogue (protocol/items.json's gen2
symbols): the item table, key items (the key pocket, HMs), counts and removals, bag.set's projection (money into
save.player.money), item uses on party members refused while a client follows (Gen 2 has no item.use: Game2's own
methods are guarded), Bill's PC held for Cobblemon's, and act.field's moves and items through the hidden game's own
menus' paths. Everything is made up (the fake's records)."""

import pytest

from adapter2 import Adapter2, plain

pytestmark = pytest.mark.lua


def adapter(live=True):
    a = Adapter2()
    a.eng = a.Engine.new(a.b.engine_opts(log=a.log, love=a.b.env.love, world=a.E.worldApi, hooks=a.b.mod.hooks,
                                         items=a.b.module("lib/kb_items.lua")))
    a.eng.setGame(a.E.game)
    w = a.field()
    a.bridge = a.b.eval("function(live) return { live = function() return live end, battle = function() return nil end } end")(live)
    a.eng.attachBridge(a.bridge)
    return a, w


def inv(a):
    return a.E.game.save.inventory


def top(a):
    return a.E.game.stack.top(a.E.game.stack)


def tap(a):
    a.press("a")
    a.tick()
    a.release("a")
    a.tick()


def test_the_item_table_and_key_items():
    a, _ = adapter()
    table = plain(a.eng.itemTable())
    assert table["POTION"] == "potion" and table["THUNDERSTONE"] == "thunder_stone" and table["BERRY"] == "oran_berry"
    assert table["TM_DYNAMICPUNCH"] == "tm_dynamic_punch" and table["HM_CUT"] == "hm_cut"
    assert a.eng.itemClass(b"KINGS_ROCK") == b"held" and a.eng.itemClass(b"LEVEL_BALL") == b"ball"
    for key in (b"BICYCLE", b"HM_CUT", b"OLD_ROD"):
        assert a.eng.isKeyItem(key) is True, key
    for plain_item in (b"POTION", b"TM_DYNAMICPUNCH", b"BERRY"):
        assert a.eng.isKeyItem(plain_item) is False, plain_item
    assert a.eng.tmMove(b"TM_DYNAMICPUNCH") == 33 and a.eng.tmMove(b"POTION") is None


def test_counts_removals_limits_and_the_projection():
    a, _ = adapter()
    i = inv(a)
    i[b"POTION"], i[b"POKE_BALL"], i[b"BICYCLE"] = 3, 120, 1
    counts = plain(a.eng.bagCounts(a.lua(["POTION", "POKE_BALL", "BICYCLE", "BERRY"])))
    assert counts == {"POTION": 3, "POKE_BALL": 120}, "never a key item, never zero"
    assert tuple(a.eng.bagRemove(b"POKE_BALL", 200)) == (120, 120)
    assert tuple(a.eng.bagRemove(b"POTION", 1)) == (1, 3)
    assert a.eng.inventoryCount(b"POTION") == 2
    limits = plain(a.eng.bagLimits())
    assert limits == {"slots": 20, "stack": 99, "used": 1}
    ok, left = a.eng.bagSet(a.lua({"POKE_BALL": 5, "BERRY": 2, "RARE_CANDY": 1, "NOT_A_GEN2_ITEM": 3}), 1234567)
    assert ok is True and plain(left) == {}
    assert i[b"POTION"] is None and i[b"POKE_BALL"] == 5 and i[b"BERRY"] == 2 and i[b"BICYCLE"] == 1
    assert a.E.game.save.player.money == 999999, "clamped"


def test_member_items_are_refused_while_a_client_follows():
    a, _ = adapter()
    g = a.E.game
    g.usePartyItem(g, b"RARE_CANDY")
    assert top(a).pages[1][1] == b"It won't have any effect." and len(a.E.partyItemUses) == 0
    tap(a)
    g.usePartyItem(g, b"MOON_STONE")
    tap(a)
    g.useFieldItem(g, b"TM_DYNAMICPUNCH")
    tap(a)
    assert plain(a.eng.itemCounts()) == {"refused": 3} and len(a.E.fieldItemUses) == 0
    g.usePartyItem(g, b"POTION")
    assert [x.decode() for x in a.E.partyItemUses.values()] == ["POTION"], "healing goes on"
    off, _ = adapter(live=False)
    off.E.game.usePartyItem(off.E.game, b"RARE_CANDY")
    assert [x.decode() for x in off.E.partyItemUses.values()] == ["RARE_CANDY"]


def test_bills_pc_is_held_for_cobblemons():
    a, _ = adapter()
    a.E.openCenterPc()
    tap(a)
    assert top(a).pages[1][1] == b"TEST BILLS PC", "the row's own line first"
    tap(a)
    assert top(a).kantoBridgePcHold is True and len(a.E.pcScreens) == 0, "the hold, not Gen 2's box screens"
    rec = plain(a.eng.pcPending())
    assert rec["state"] == "waiting" and a.eng.pcHeld() is True
    a.eng.pcSent(1, b"pc-test-1")
    assert tuple(a.eng.pcClose(b"closed", b"pc-other")) == (False, b"stale")
    assert a.eng.pcClose(b"closed", b"pc-test-1") is True
    a.tick()
    assert top(a).screenId == b"Gen2CenterPcMenu", "back to the PC's menu"
    assert plain(a.eng.pcCounts()) == {"taken": 1, "native": 0}
    off, _ = adapter(live=False)
    off.E.openCenterPc()
    tap(off)
    tap(off)
    assert [x.decode() for x in off.E.pcScreens.values()] == ["Gen2PcMenu"]


def test_field_moves_go_through_the_party_menus_path():
    a, w = adapter()
    mon = a.E.Mon.new(a.E.data, b"TEST_CHIKORITA", 5, a.lua({"moves": [{"id": "CUT", "pp": 30, "maxPp": 30},
                                                                       {"id": "HEADBUTT", "pp": 15, "maxPp": 15}]}))
    a.E.game.save.party[1] = mon
    assert tuple(a.eng.fieldAction(b"cut", a.lua({}))) == (True, b"cut")
    call = a.E.fieldMoveCalls[1]
    assert call.move == b"CUT" and a.same(call.mon, mon)
    assert tuple(a.eng.fieldAction(b"headbutt", a.lua({}))) == (True, b"headbutt")
    assert tuple(a.eng.fieldAction(b"surf", a.lua({}))) == (False, b"no_move")
    a.E.fieldMoveResult = a.lua({"ok": False, "badge": "HIVE"})
    assert tuple(a.eng.fieldAction(b"cut", a.lua({}))) == (False, b"no_badge")
    a.E.fieldMoveResult = a.lua({"ok": False})
    assert tuple(a.eng.fieldAction(b"cut", a.lua({}))) == (False, b"no_target")
    a.E.fieldMoveResult = None
    mon.moves[3] = a.lua({"id": "FLY", "pp": 15, "maxPp": 15})
    a.E.fieldMoveResult = a.lua({"ok": True, "inMenu": True})
    assert tuple(a.eng.fieldAction(b"fly", a.lua({}))) == (True, b"fly_list")
    assert tuple(a.eng.fieldAction(b"fly", a.lua({"dest": "G2_TEST_CAVE"}))) == (False, b"no_target")
    w.vmRunning = True
    assert tuple(a.eng.fieldAction(b"cut", a.lua({}))) == (False, b"busy")


def test_field_items_go_through_the_packs_path():
    a, w = adapter()
    i = inv(a)
    assert tuple(a.eng.fieldAction(b"bicycle", a.lua({}))) == (False, b"no_item")
    i[b"BICYCLE"], i[b"OLD_ROD"], i[b"ESCAPE_ROPE"] = 1, 1, 2
    a.E.fieldItemResults = a.lua({"BICYCLE": "bike_on", "OLD_ROD": "bite", "ESCAPE_ROPE": "escape_rope"})
    assert tuple(a.eng.fieldAction(b"bicycle", a.lua({}))) == (True, b"bike_on")
    assert tuple(a.eng.fieldAction(b"fish", a.lua({}))) == (True, b"fish"), "the best rod in the bag"
    assert tuple(a.eng.fieldAction(b"dig", a.lua({"item": "ESCAPE_ROPE"}))) == (True, b"rope")
    a.E.fieldItemResults = a.lua({"OLD_ROD": "nowhere"})
    assert tuple(a.eng.fieldAction(b"fish", a.lua({"item": "OLD_ROD"}))) == (False, b"no_target")
    assert [x.decode() for x in a.E.fieldItemUses.values()] == ["BICYCLE", "OLD_ROD", "ESCAPE_ROPE", "OLD_ROD"]
