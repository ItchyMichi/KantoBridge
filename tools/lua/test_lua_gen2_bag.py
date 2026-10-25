"""MG9 G2F (docs/specs/GEN2_BRIDGE_PLAN.md §5.10, J13): the Gen 2 adapter's bag part (gen1-bridge/lib/engine/gen2/
bag.lua) and field moves (gen2/fieldmoves.lua) on the fake Gen 2 engine with the real catalogue (protocol/items.json's gen2
symbols): the item table, key items (the key pocket, HMs), counts and removals, bag.set's projection (money into
save.player.money), item uses on party members forwarded to Cobblemon while a client follows (FP4: Gen 2 has no
item.use, so ItemEffects and Game2:useFieldItem are wrapped), Bill's PC held for Cobblemon's, and act.field's moves and items through the hidden game's own
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


UID1 = "11111111-1111-4111-8111-111111111111"
UID2 = "22222222-2222-4222-8222-222222222222"


def synced(a, *members):
    """The party as a sync builds it (uids, and FP4's stones and canLearn on the records)."""
    rows = []
    for uid, species, level, extra in members:
        e = {"uid": uid, "species": species, "level": level, "moves": ["TEST_GROWL"], "hp": 10, "maxhp": 40}
        e.update(extra)
        rows.append(e)
    a.eng.applyParty(a.lua(rows))
    p = a.E.game.save.party
    return [p[k] for k in range(1, len(p) + 1)]


def uses(a):
    out = []
    for u in plain(a.eng.takeItemUses()):
        out.append({k: v for k, v in u.items() if v is not None})
    return out


def test_medicine_and_pp_items_run_natively_and_go_to_cobblemon():
    # FP4 (FOREIGN_POKEMON_PLAN §4.4): the engine's effect on the record, then party.item when it used the item
    a, _ = adapter()
    g = a.E.game
    inv(a)[b"POTION"], inv(a)[b"ETHER"], inv(a)[b"HP_UP"], inv(a)[b"ELIXER"] = 2, 1, 1, 1
    (mon,) = synced(a, (UID1, "TEST_CHIKORITA", 9, {}))
    g.usePartyItem(g, b"POTION")
    assert mon.hp > 10 and inv(a)[b"POTION"] == 1
    g.usePartyItem(g, b"HP_UP")
    a.E.itemMoveSlot = 1
    g.usePartyItem(g, b"ETHER")
    g.usePartyItem(g, b"ELIXER")
    assert uses(a) == [{"item": "POTION", "uid": UID1, "kind": "medicine"},
                       {"item": "HP_UP", "uid": UID1, "kind": "medicine"},
                       {"item": "ETHER", "uid": UID1, "kind": "medicine", "move": 45},
                       {"item": "ELIXER", "uid": UID1, "kind": "medicine"}], "kb_party names pp and vitamin"
    mon.hp = mon.maxHp
    g.usePartyItem(g, b"POTION")
    assert uses(a) == [] and inv(a)[b"POTION"] == 1, "the engine's refusal: nothing spent, nothing sent"


def test_stones_and_rare_candy_are_cobblemons_and_skip_the_engines_effect():
    a, _ = adapter()
    g = a.E.game
    inv(a)[b"MOON_STONE"], inv(a)[b"RARE_CANDY"] = 2, 1
    (mon,) = synced(a, (UID1, "TEST_CHIKORITA", 9, {"stones": ["moon_stone"]}))
    g.usePartyItem(g, b"MOON_STONE")
    assert mon.species == b"TEST_CHIKORITA" and inv(a)[b"MOON_STONE"] == 1, "no native evolution; the stone spent"
    assert b"reacting" in top(a).pages[1][1]
    tap(a)
    g.usePartyItem(g, b"RARE_CANDY")
    assert mon.level == 9 and inv(a)[b"RARE_CANDY"] is None
    assert uses(a) == [{"item": "MOON_STONE", "uid": UID1, "kind": "stone"}, {"item": "RARE_CANDY", "uid": UID1, "kind": "candy"}]
    # Cobblemon's stones say no: the engine's own refusal, nothing spent
    synced(a, (UID1, "TEST_TOGEPI", 9, {"stones": []}))
    tap(a)
    g.usePartyItem(g, b"MOON_STONE")
    assert uses(a) == [] and inv(a)[b"MOON_STONE"] == 1
    # without the sync's stones, the engine's check answers
    (togepi,) = synced(a, (UID2, "TEST_TOGEPI", 9, {}))
    tap(a)
    g.usePartyItem(g, b"MOON_STONE")
    assert togepi.species == b"TEST_TOGEPI" and uses(a) == [{"item": "MOON_STONE", "uid": UID2, "kind": "stone"}]


def test_a_tm_is_answered_by_cobblemons_learnset_and_names_the_move_it_replaced():
    a, _ = adapter()
    g = a.E.game
    inv(a)[b"TM_DYNAMICPUNCH"] = 2
    (mon,) = synced(a, (UID1, "TEST_CHIKORITA", 9, {"canLearn": [33], "moves": ["TEST_GROWL", "TEST_SPLASH", "TEST_CUT", "TEST_SURF"]}))
    a.E.forgetSlot = 2
    g.useFieldItem(g, b"TM_DYNAMICPUNCH")
    top(a).opts.onChoose(1, mon)
    assert [mv.id for mv in mon.moves.values()] == [b"TEST_GROWL", b"TEST_TACKLE", b"TEST_CUT", b"TEST_SURF"]
    assert uses(a) == [{"item": "TM_DYNAMICPUNCH", "uid": UID1, "kind": "machine", "move": 33, "slot": 1, "forget": 150}]
    assert inv(a)[b"TM_DYNAMICPUNCH"] == 1
    assert a.E.data.pokemon.TEST_CHIKORITA.tmhm is None, "the species' list is back"
    # Cobblemon's learnset says no: the engine's own "can't learn"
    (mon,) = synced(a, (UID1, "TEST_CHIKORITA", 9, {"canLearn": []}))
    g.useFieldItem(g, b"TM_DYNAMICPUNCH")
    top(a).opts.onChoose(1, mon)
    assert top(a).pages[1][1] == b"CAN'T LEARN" and uses(a) == []


def test_without_a_client_or_a_uid_items_stay_native():
    off, _ = adapter(live=False)
    g = off.E.game
    inv(off)[b"MOON_STONE"] = 1
    synced(off, (UID1, "TEST_TOGEPI", 9, {}))
    g.usePartyItem(g, b"MOON_STONE")
    assert off.E.partyItemUses[1].scene == b"evolution" and uses(off) == []


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


def test_rock_smash_queues_the_smash_script_as_the_party_menu_does():
    # FA8 item 14: World:useFieldMove queues nothing that smashes; PartyMenu:useFieldMove queues SmashRockScript with
    # the rock as the last talked-to object (src/ui/gen2/PartyMenu.lua:457-468)
    a, w = adapter()
    mon = a.E.Mon.new(a.E.data, b"TEST_CHIKORITA", 5, a.lua({"moves": [{"id": "ROCK_SMASH", "pp": 15, "maxPp": 15}]}))
    a.E.game.save.party[1] = mon
    w.vm = a.lua({"scripts": {}})
    a.E.FieldMoves.rockSmashFromMenuScript = a.b.eval(
        "function(std, scripts, special) return { { op = 'refreshmap' }, { op = 'sjump', script = 'SMASH' } } end")
    a.E.fieldMoveResult = a.lua({"ok": True, "action": "rocksmash", "lastTalked": 4})
    w.queuedFieldMove = a.lua({"action": "rocksmash"})
    assert tuple(a.eng.fieldAction(b"rock_smash", a.lua({}))) == (True, b"rock_smash")
    assert w.queuedFieldMove is None and w.vm.lastTalked == 4
    assert w.queuedScript[2].script == b"SMASH"
    a.E.FieldMoves.rockSmashFromMenuScript = a.b.eval("function() return nil end")
    assert tuple(a.eng.fieldAction(b"rock_smash", a.lua({}))) == (False, b"failed"), "no SmashRockScript: nothing queued"


def test_a_field_moves_warp_is_named_by_the_move_until_its_fade():
    # FA8 item 16 (K11): Dig, Teleport, Fly and the rope from Minecraft mark the warp they set off; observe() reports
    # the mark as the departure while the fade starts, then forgets it
    a, w = adapter()
    mon = a.E.Mon.new(a.E.data, b"TEST_CHIKORITA", 5, a.lua({"moves": [{"id": "DIG", "pp": 10, "maxPp": 10}]}))
    a.E.game.save.party[1] = mon
    assert tuple(a.eng.fieldAction(b"dig", a.lua({}))) == (True, b"dig")
    assert a.facts().get("departure") == "dig"
    w.mapSetup = a.lua({})
    assert a.facts().get("departure") == "dig", "still named as the fade starts"
    w.mapSetup = None
    assert a.facts().get("departure") is None, "forgotten once the warp is under way and done"
    assert tuple(a.eng.fieldAction(b"cut", a.lua({}))) == (False, b"no_move")
    assert a.facts().get("departure") is None


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


def test_the_battle_tower_desk_refuses_in_its_own_words_while_a_client_follows():
    # FP5 (FOREIGN_POKEMON_PLAN §4.12): the desk's rules check fails with our line between its own header and tail
    a, _ = adapter()
    lines, failed = a.E.BattleTower.checkRules(a.E.game.save.party)
    assert failed is True and list(lines.values())[0] == b"_ExcuseMeYoureNotReadyText"
    assert b"TOWER" in list(lines.values())[1] and list(lines.values())[2] == b"_BattleTowerReturnWhenReadyText"
    assert plain(a.eng.refusedFeature()) == {"feature": "battle_tower", "n": 1}
    off, _ = adapter(live=False)
    lines, failed = off.E.BattleTower.checkRules(off.E.game.save.party)
    assert failed is False
