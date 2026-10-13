"""M6 items used from the Gen 1 bag on a party member, through main.lua on the simulated engine
(kb_engine's item.use seam over fake_wild.lua's BagMenu-like flow): an evolution stone the species
evolves with and RARE_CANDY are applied by Cobblemon (party.item; the Gen 1 effect skipped, the item
consumed, the party menu closed), a TM/HM is taught natively and the move and slot it took go out as
party.item; anything the engine refuses, any use in battle, without a client or on a member with no
Cobblemon uid stays native. Every message the peer receives is schema-checked (rig.Peer, strict).
All data is made up (battlesim.py)."""

import pytest

from battlesim import BattleGame, MOVE_NUMBERS

pytestmark = pytest.mark.lua

U1 = "0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b"
U2 = "6c1e2d3f-4a5b-4c6d-8e7f-a0b1c2d3e4f5"
N = MOVE_NUMBERS


def entry(uid, dex, level, moves, hp=20, maxhp=20):
    return {"uid": uid, "dex": dex, "level": level, "moves": moves, "hp": hp, "maxhp": maxhp}


def items_sent(g):
    return [m["data"] for m in g.received("party.item")]


def native_items(g):
    return [(x.kind.decode(), x.item.decode()) for x in g.E.nativeItems.values()]


def stock(g, **items):
    for item, qty in items.items():
        g.E.Bag.add(g.E.save, item.encode(), qty)


def items_game(party=None, connect=True):
    g = BattleGame(start=("WILD_ROUTE", 2, 3, "down"), connect=connect)
    if not connect:
        g.connect()
    g.send("party.sync", {"party": party or [entry(U1, 37, 10, [N["EMBER_T"], N["TAIL_T"]]),
                                             entry(U2, 7, 12, [N["TACKLE_T"], N["TAIL_T"]])],
                          "box": {"count": 0}})
    g.run(2)
    stock(g, FIRE_STONE=1, MOON_STONE=1, RARE_CANDY=3, TM_SAND_T=1, HM_WHIRL_T=1, POKE_BALL=2)
    return g


def mon(g, i):
    return g.party()[i]


def row(list_, item):
    rows = list_[b"items"]                    # (lupa's .items is the wrapper's own method)
    for i in range(1, len(rows) + 1):
        if rows[i].value == item.encode():
            return rows[i]
    return None


# ------------------------------------------------------------ stones and the candy: Cobblemon applies them


def test_an_evolution_stone_goes_to_cobblemon():
    g = items_game()
    fox = mon(g, 0)
    g.E.useItem(b"FIRE_STONE", fox)
    assert fox.species == b"FOXMON", "no Gen 1 evolution"
    assert not native_items(g)
    assert g.E.save.inventory.FIRE_STONE is None, "the stone is used up from the Gen 1 bag"
    assert row(g.E.lastBagList, "FIRE_STONE") is None, "its bag row is gone"
    assert g.E.flashes == 1 and g.top().isFlash is True, "the party menu closed behind the bag's flash"
    g.run()
    assert items_sent(g) == [{"item": "FIRE_STONE", "uid": U1}]
    # Cobblemon evolves it; the next sync brings the new species back to the same member
    g.send("party.sync", {"party": [entry(U1, 38, 10, [N["EMBER_T"], N["TAIL_T"]]),
                                    entry(U2, 7, 12, [N["TACKLE_T"], N["TAIL_T"]])], "box": {"count": 0}})
    g.run(2)
    assert mon(g, 0).species == b"FOXMON_2"
    st = g.status()
    assert st["itemStone"] == 1 and st["partyItems"] == 1


def test_a_stone_the_species_does_not_use_stays_native():
    g = items_game()
    g.E.useItem(b"MOON_STONE", mon(g, 0))
    g.run()
    assert native_items(g) == [("failed", "MOON_STONE")] and not items_sent(g)
    assert g.E.save.inventory.MOON_STONE == 1


def test_rare_candy_goes_to_cobblemon():
    g = items_game()
    fox = mon(g, 0)
    g.E.useItem(b"RARE_CANDY", fox)
    g.run()
    assert fox.level == 10 and not native_items(g), "no Gen 1 level-up"
    assert items_sent(g) == [{"item": "RARE_CANDY", "uid": U1}]
    assert g.E.save.inventory.RARE_CANDY == 2 and row(g.E.lastBagList, "RARE_CANDY").count == 2
    assert g.status()["itemCandy"] == 1


def test_rare_candy_at_level_100_stays_native():
    g = items_game(party=[entry(U1, 37, 100, [N["EMBER_T"]])])
    g.E.useItem(b"RARE_CANDY", mon(g, 0))
    g.run()
    assert native_items(g) == [("failed", "RARE_CANDY")] and not items_sent(g)


# ------------------------------------------------------------ TMs and HMs: taught natively, then Cobblemon


def test_a_tm_with_a_free_slot_sends_its_move():
    g = items_game()
    bird = mon(g, 1)
    g.E.useItem(b"TM_SAND_T", bird)
    g.run()
    assert native_items(g) == [("learned", "TM_SAND_T")]
    assert [mv.id for mv in bird.moves.values()] == [b"TACKLE_T", b"TAIL_T", b"SAND_T"]
    assert items_sent(g) == [{"item": "TM_SAND_T", "uid": U2, "move": N["SAND_T"]}], "no slot: it was added"
    assert g.E.save.inventory.TM_SAND_T is None, "a TM is used up"
    st = g.status()
    assert st["itemMachine"] == 1 and st["itemTaught"] == 1 and st["tmWatching"] is False


def test_an_hm_over_a_forgotten_move_sends_the_slot():
    g = items_game(party=[entry(U2, 7, 12, [N["TACKLE_T"], N["TAIL_T"], N["GROWL_T"], N["BUBBLE_T"]])])
    bird = mon(g, 0)
    g.E.forgetSlot = 3
    g.E.useItem(b"HM_WHIRL_T", bird)
    g.run(2)
    assert not items_sent(g), "the forget prompt is up"
    assert g.status()["tmWatching"] is True
    # a party.sync meanwhile waits: it would rewrite the moves under the prompt
    g.send("party.sync", {"party": [entry(U2, 7, 12, [N["TACKLE_T"]])], "box": {"count": 0}})
    g.run(2)
    assert len(bird.moves) == 4
    g.send("act.button", {"button": "a", "how": "tap"})   # the mirror: forget the third move
    g.run(3)
    assert items_sent(g) == [{"item": "HM_WHIRL_T", "uid": U2, "move": N["WHIRL_T"], "slot": 2}]
    assert g.E.save.inventory.HM_WHIRL_T == 1, "an HM stays in the bag"
    g.run(3)
    assert [mv.id for mv in g.party()[0].moves.values()] == [b"TACKLE_T"], "then the waiting sync applied"
    assert g.status()["partyDeferred"] == 1


def test_a_declined_tm_sends_nothing():
    g = items_game(party=[entry(U2, 7, 12, [N["TACKLE_T"], N["TAIL_T"], N["GROWL_T"], N["BUBBLE_T"]])])
    g.E.useItem(b"TM_SAND_T", mon(g, 0))
    g.run(2)
    g.send("act.button", {"button": "b", "how": "tap"})
    g.run(3)
    assert native_items(g) == [("declined", "TM_SAND_T")] and not items_sent(g)
    st = g.status()
    assert st["itemDeclined"] == 1 and st["tmWatching"] is False
    assert g.E.save.inventory.TM_SAND_T == 1


def test_a_tm_the_species_cannot_learn_stays_native():
    g = items_game()
    g.E.useItem(b"TM_SAND_T", mon(g, 0))      # FOXMON has no such machine move
    g.run()
    assert native_items(g) == [("failed", "TM_SAND_T")] and not items_sent(g)
    assert g.status()["tmWatching"] is False


def bag_list_on(g, item):
    """The fake BagMenu list with its cursor on the item's row, as opts for E.useItem."""
    lst = g.E.bagList()
    lst.index = [i for i in range(1, len(lst[b"items"]) + 1) if lst[b"items"][i].value == item.encode()][0]
    opts = g.b.table()
    opts[b"list"] = lst
    return lst, opts


def last_remove(g):
    last = list(g.E.bagRemoves.values())[-1]
    same = g.b.eval("function(a, b) return rawequal(a, b) end")
    return last.id, last.qty, last.slot, bool(same(last.data, g.E.data))


def test_bag_rows_carry_their_slot_and_a_native_use_consumes_by_it():
    """BagMenu.lua at v0.3.85: rows carry `slot` and `stack` (buildItems :20-40) and consume takes
    one out of the stack under the cursor with the live data (:42-48); a TM taught natively goes
    through it."""
    g = items_game()
    lst, opts = bag_list_on(g, "TM_SAND_T")
    rows = {r.value.decode(): (r.slot, r.stack, r.count) for r in lst[b"items"].values() if r.value is not None}
    assert rows["RARE_CANDY"] == (1, 3, 3) and rows["FIRE_STONE"] == (1, 1, 1)
    assert rows["HM_WHIRL_T"] == (1, 1, None), "an HM shows no count"
    g.E.useItem(b"TM_SAND_T", mon(g, 1), opts)
    g.run()
    assert native_items(g) == [("learned", "TM_SAND_T")]
    assert last_remove(g) == (b"TM_SAND_T", 1, 1, True), "the stack under the cursor, with the live data"
    assert row(lst, "TM_SAND_T") is None, "its row is gone"


def test_an_item_cobblemon_applies_is_consumed_with_the_live_data():
    """kb_engine's own consume (ENGINE_UPGRADE_PLAN B4): Bag.remove(save, id, 1, data) -- the total
    is right either way; the plan keeps the slot out of it (Appendix B, BagMenu.lua:39-46)."""
    g = items_game()
    lst, opts = bag_list_on(g, "RARE_CANDY")
    g.E.useItem(b"RARE_CANDY", mon(g, 0), opts)
    g.run()
    assert not native_items(g) and items_sent(g) == [{"item": "RARE_CANDY", "uid": U1}]
    assert last_remove(g) == (b"RARE_CANDY", 1, None, True)
    assert g.E.save.inventory.RARE_CANDY == 2


# ------------------------------------------------------------ what stays native


def test_without_a_client_the_gen1_effect_applies():
    g = items_game()
    g.peer.close()
    g.rig.step(3)
    fox = mon(g, 0)
    g.E.useItem(b"FIRE_STONE", fox)
    g.rig.step(3)
    assert fox.species == b"FOXMON_2" and native_items(g) == [("evolved", "FIRE_STONE")]
    g.connect()
    g.run(2)
    assert not items_sent(g)


def test_in_battle_items_stay_native():
    g = items_game()
    g.E.useItem(b"RARE_CANDY", mon(g, 0), g.b.to_lua({"battle": {"kind": "wild"}}))
    g.run()
    assert native_items(g) == [("failed", "RARE_CANDY")] and not items_sent(g)


def test_a_member_cobblemon_does_not_know_stays_native():
    g = items_game()
    g.script([["give_pokemon", "FOXMON", 8, True]])   # a gift with no uid yet
    g.run(2)
    gift = g.party()[-1]
    g.E.useItem(b"FIRE_STONE", gift)
    g.run()
    assert gift.species == b"FOXMON_2" and not items_sent(g)


def test_a_use_whose_client_dropped_goes_to_the_next_one_once():
    g = items_game()
    g.E.useItem(b"RARE_CANDY", mon(g, 0))
    g.peer.close()
    g.rig.step(3)
    g.connect()
    g.run(2)
    assert items_sent(g) == [{"item": "RARE_CANDY", "uid": U1}]
    g.send("resync")
    g.run(2)
    assert len(items_sent(g)) == 1, "never sent twice"


def test_logs_never_carry_species_or_moves():
    g = items_game()
    g.E.useItem(b"TM_SAND_T", mon(g, 1))
    g.E.useItem(b"FIRE_STONE", mon(g, 0))
    g.run()
    text = "\n".join(g.rig.logs())
    assert "FOXMON" not in text and "SAND_T" not in text and "STARTMON" not in text
    assert "party.item" in text
