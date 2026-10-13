"""M6 bag mirroring through main.lua on the simulated engine (observe/kb_bag.lua, kb_engine's bag
seam over fake_wild.lua's Bag module): bag.sync carries only the battle items of PLAN Appendix B
(never key items, badges or other items), after every snapshot and whenever those quantities change;
bag.use takes items out of the hidden game's bag through Bag.remove (never below zero, a counted
mismatch warning), refuses items that aren't mirrored, waits at the title, and is for client and
test roles only. Every message the peer receives is schema-checked (rig.Peer, strict). All data is
made up (battlesim.py)."""

import pytest

from battlesim import BattleGame

pytestmark = pytest.mark.lua

# PLAN Appendix B, by the engine's Gen 1 symbols
APPENDIX_B = [
    "POKE_BALL", "GREAT_BALL", "ULTRA_BALL", "MASTER_BALL", "SAFARI_BALL",
    "POTION", "SUPER_POTION", "HYPER_POTION", "MAX_POTION", "FULL_RESTORE", "REVIVE", "MAX_REVIVE",
    "ANTIDOTE", "BURN_HEAL", "ICE_HEAL", "AWAKENING", "PARLYZ_HEAL", "FULL_HEAL",
    "ETHER", "MAX_ETHER", "ELIXER", "MAX_ELIXER",
    "X_ATTACK", "X_ACCURACY", "X_SPEED", "X_DEFEND", "X_SPECIAL", "DIRE_HIT", "GUARD_SPEC",
]


def syncs(g):
    return [m["data"]["items"] for m in g.received("bag.sync")]


def errors(g):
    return [(m["data"]["code"], m.get("re")) for m in g.received("error")]


def stock(g, **items):
    for item, qty in items.items():
        g.E.Bag.add(g.E.save, item.encode(), qty)


def bag_game(**kw):
    g = BattleGame(start=("WILD_ROUTE", 2, 3, "down"), connect=False, **kw)
    # the fake save starts with POTION x2 and a badge; add balls, an X item, a key item, others
    stock(g, POKE_BALL=5, X_ATTACK=1, TOWN_MAP=1, REPEL=3, RARE_CANDY=2)
    return g


def test_the_battle_item_list_is_appendix_b():
    from kblua import Bridge
    Bag = Bridge().module("observe/kb_bag.lua")
    assert [Bag.ITEMS[i].decode() for i in range(1, len(Bag.ITEMS) + 1)] == APPENDIX_B


def test_after_the_hello_only_battle_items_are_mirrored():
    g = bag_game()
    g.connect()
    types = [m["type"] for m in g.log]
    assert types[:4] == ["hello.ok", "snapshot", "npc.set", "bag.sync"]
    assert syncs(g) == [{"POKE_BALL": 5, "POTION": 2, "X_ATTACK": 1}], "no key item, badge or field item"
    snap = g.received("snapshot")[0]["data"]
    assert snap["bag"]["TOWN_MAP"] == 1 and snap["bag"]["REPEL"] == 3, "the snapshot keeps the whole bag"


def test_changes_are_sent_and_nothing_else_is():
    g = bag_game()
    g.connect()
    g.run(10)
    assert len(syncs(g)) == 1, "nothing changed: nothing sent"
    stock(g, GREAT_BALL=2, REPEL=1)
    g.run(2)
    assert syncs(g)[-1] == {"POKE_BALL": 5, "POTION": 2, "X_ATTACK": 1, "GREAT_BALL": 2}
    g.E.Bag.remove(g.E.save, b"POTION", 2)
    g.run(2)
    assert syncs(g)[-1] == {"POKE_BALL": 5, "X_ATTACK": 1, "GREAT_BALL": 2}, "a used-up item leaves the map"
    stock(g, TOWN_MAP=1)
    g.run(3)
    assert len(syncs(g)) == 3, "a key item never triggers one"


def test_bag_use_takes_items_out_and_the_next_sync_shows_it():
    g = bag_game()
    g.connect()
    g.send("bag.use", {"item": "POKE_BALL", "qty": 2})
    out = g.run()
    assert [m["type"] for m in out] == ["bag.sync"], "applied, then mirrored in the same step"
    assert out[0]["data"]["items"]["POKE_BALL"] == 3 and g.E.save.inventory.POKE_BALL == 3
    g.send("bag.use", {"item": "X_ATTACK", "qty": 1})
    g.run()
    assert g.E.save.inventory.X_ATTACK is None, "the slot goes at zero"
    order = [g.E.save.bagOrder[i] for i in range(1, len(g.E.save.bagOrder) + 1)]
    assert b"X_ATTACK" not in order, "Bag.remove drops its bag order entry too"
    assert "X_ATTACK" not in syncs(g)[-1]
    st = g.status()
    assert st["bagUses"] == 2 and st["bagRemoved"] == 3 and st["bagMismatch"] == 0
    # ENGINE_UPGRADE_PLAN B4: Bag.remove(save, id, qty, data, slot) gets the live game data, no slot
    calls = [(c.id.decode(), c.qty, same(g, c.data, g.E.data), c.slot) for c in g.E.bagRemoves.values()]
    assert calls == [("POKE_BALL", 2, True, None), ("X_ATTACK", 1, True, None)]


def same(g, a, b):
    """Lua identity (lupa wraps one table in different Python objects)."""
    return bool(g.b.eval("function(a, b) return rawequal(a, b) end")(a, b))


def rows(g):
    return [(r.id.decode(), r.slot, r.count) for r in g.E.Bag.rows(g.E.save, g.E.data).values()]


def test_stacks_split_at_99_and_the_mirror_carries_the_total():
    """Bag.lua at v0.3.85: an item over 99 fills a second stack (addTo :383-425), rows are one per
    stack (Bag.rows :369-373), bagStacks holds only a split that isn't the derived 99+rest shape
    (setCounts :113-120); bag.use takes from the first stack on (removeFrom :427-463, no slot);
    the mirror sees totals only."""
    g = bag_game()
    g.connect()
    stock(g, POKE_BALL=150)                       # 5 + 150 = 155: two stacks, 99 and 56
    g.run(2)
    assert syncs(g)[-1]["POKE_BALL"] == 155, "totals, never stacks"
    assert [r for r in rows(g) if r[0] == "POKE_BALL"] == [("POKE_BALL", 1, 99), ("POKE_BALL", 2, 56)]
    assert g.E.save.bagStacks is None, "the derived shape needs no side table"
    g.send("bag.use", {"item": "POKE_BALL", "qty": 60})
    g.run()
    assert g.E.save.inventory.POKE_BALL == 95 and syncs(g)[-1]["POKE_BALL"] == 95
    assert [r for r in rows(g) if r[0] == "POKE_BALL"] == [("POKE_BALL", 1, 39), ("POKE_BALL", 2, 56)]
    assert list(g.E.save.bagStacks.POKE_BALL.values()) == [39, 56], "no longer derived: kept beside the totals"
    order = [v.decode() for v in g.E.save.bagOrder.values()]
    assert order.count("POKE_BALL") == 2
    g.send("bag.use", {"item": "POKE_BALL", "qty": 95})
    g.run()
    assert g.E.save.inventory.POKE_BALL is None and g.E.save.bagStacks is None
    assert "POKE_BALL" not in [v.decode() for v in g.E.save.bagOrder.values()], "both stacks' order entries gone"
    assert g.status()["bagRemoved"] == 155


def test_using_more_than_the_bag_holds_never_goes_below_zero():
    g = bag_game()
    g.connect()
    g.send("bag.use", {"item": "POTION", "qty": 5})
    g.send("bag.use", {"item": "GREAT_BALL", "qty": 1})
    g.run()
    assert g.E.save.inventory.POTION is None and g.E.save.inventory.GREAT_BALL is None
    st = g.status()
    assert st["bagMismatch"] == 2 and st["bagRemoved"] == 2
    assert any("bag.use_mismatch" in line for line in g.rig.logs())


def test_items_that_are_not_mirrored_are_refused():
    g = bag_game()
    g.connect()
    a = g.send("bag.use", {"item": "TOWN_MAP", "qty": 1})
    b = g.send("bag.use", {"item": "REPEL", "qty": 1})
    g.run()
    assert ("bag.not_mirrored", a) in errors(g) and ("bag.not_mirrored", b) in errors(g)
    assert g.E.save.inventory.TOWN_MAP == 1 and g.E.save.inventory.REPEL == 3
    assert g.status()["bagRefused"] == 2


@pytest.mark.parametrize("bad", [
    {"item": "POKE_BALL", "qty": 0},
    {"item": "POKE_BALL", "qty": 1000},
    {"item": "poke_ball", "qty": 1},
    {"item": "POKE_BALL", "qty": 1.5},
])
def test_bad_bag_uses_are_refused(bad):
    g = bag_game()
    g.connect()
    seq = g.peer.send("bag.use", bad)
    g.run()
    assert ("bad_message", seq) in errors(g) and g.E.save.inventory.POKE_BALL == 5


def test_at_the_title_nothing_is_mirrored_and_uses_wait():
    g = bag_game()
    g.connect()
    states = g.E.stack.states
    g.E.leaveWorld()
    g.send("bag.use", {"item": "POKE_BALL", "qty": 1})
    g.send("resync")
    g.run(3)
    assert len(syncs(g)) == 1, "no bag.sync from the title"
    assert g.E.save.inventory.POKE_BALL == 5 and g.status()["bagWaiting"] == 1
    g.E.stack.states = states                      # back in the world
    g.run(2)
    assert g.E.save.inventory.POKE_BALL == 4 and syncs(g)[-1]["POKE_BALL"] == 4


def test_a_resync_and_a_new_connection_mirror_again():
    g = bag_game()
    g.connect()
    g.send("resync")
    g.run()
    assert len(syncs(g)) == 2
    g.peer.close()
    g.rig.step(3)
    g.connect()
    g.run()
    assert len(syncs(g)) == 3 and syncs(g)[-1] == syncs(g)[0]


def test_a_builder_gets_no_bag_and_cannot_use_items():
    g = bag_game()
    g.connect(role="builder")
    seq = g.send("bag.use", {"item": "POKE_BALL", "qty": 1})
    g.run(2)
    assert not syncs(g) and ("role", seq) in errors(g) and g.E.save.inventory.POKE_BALL == 5


def test_logs_show_symbols_and_counts_only():
    g = bag_game()
    g.connect()
    g.send("bag.use", {"item": "POKE_BALL", "qty": 9})
    g.run()
    text = "\n".join(g.rig.logs())
    assert "bag.use_mismatch" in text and "POKE_BALL" in text and "asked=9" in text
