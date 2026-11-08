"""MG6 (docs/specs/MULTI_GAME_PLAN.md §4.9, one trainer economy): bag.set through main.lua on the
simulated engine (act/kb_ledger.lua over kb_engine's bagSet and fake_wild.lua's Bag with its 20-slot
cap): the projection replaces the bag's non-key catalogue items and writes the money, within the
game's limits (what doesn't fit comes back as too_many with `left`), key items, HMs, badges and items
the catalogue doesn't know are never touched, the stamp lands in the save's mod data and every
bag.sync echoes it (the confirming one in the same step), not_ingame at the title, busy under a
screen, bad requests refused, roles. What changes while a projection waits for a free world (a bag.use,
an item found, prize money) stays when it lands; a bag.use that came before it isn't applied twice. Every message the peer receives is schema-checked (rig.Peer,
strict). All data is made up (battlesim.py)."""

import pytest

from battlesim import BattleGame

pytestmark = pytest.mark.lua

STAMP = "0123456789abcdef0123456789abcdef"


def stock(g, **items):
    for item, qty in items.items():
        g.E.Bag.add(g.E.save, item.encode(), qty)


def ledger_game(**kw):
    g = BattleGame(start=("WILD_ROUTE", 2, 3, "down"), connect=False, **kw)
    # the fake save starts with POTION x2, 3000 money and a badge; balls, an X item, a key item, a
    # field item, candy and an item the catalogue doesn't know (a story item)
    stock(g, POKE_BALL=5, X_ATTACK=1, TOWN_MAP=1, REPEL=3, RARE_CANDY=2, TEST_RELIC=1)
    return g


def results(g):
    return [(m["data"], m.get("re")) for m in g.received("act.result")]


def errors(g):
    return [(m["data"]["code"], m.get("re")) for m in g.received("error")]


def syncs(g):
    return [m["data"] for m in g.received("bag.sync")]


def inv(g):
    return {k.decode(): v for k, v in g.E.save.inventory.items()}


def bag_set(g, items, money=1234, stamp=STAMP):
    return g.send("bag.set", {"items": items, "money": money, "stamp": stamp})


def test_the_projection_replaces_the_bag_writes_the_money_and_is_confirmed_in_the_same_step():
    g = ledger_game()
    g.connect()
    seq = bag_set(g, {"POTION": 5, "POKE_BALL": 150, "REPEL": 2, "TM_MEGA_PUNCH": 1, "HP_UP": 1})
    out = g.run()
    assert [m["type"] for m in out] == ["act.result", "bag.sync"], "the answer, then the confirming sync"
    assert results(g) == [({"ok": True, "kind": "bag.set"}, seq)]
    bag = inv(g)
    assert bag["POTION"] == 5 and bag["POKE_BALL"] == 150 and bag["REPEL"] == 2
    assert bag["TM_MEGA_PUNCH"] == 1 and bag["HP_UP"] == 1
    assert "X_ATTACK" not in bag and "RARE_CANDY" not in bag, "catalogue items not in the projection are gone"
    assert bag["TOWN_MAP"] == 1 and bag["BOULDERBADGE"] is True and bag["TEST_RELIC"] == 1, \
        "a key item, a badge and an item the catalogue doesn't know are never touched"
    assert g.E.save.money == 1234
    # one Bag.add per 99: 150 balls are two stacks (the stacks model), the rows one per stack
    rows = [(r.id.decode(), r.count) for r in g.E.Bag.rows(g.E.save, g.E.data).values() if r.id == b"POKE_BALL"]
    assert rows == [("POKE_BALL", 99), ("POKE_BALL", 51)]
    # class order: balls, medicine (sorted), field items, TMs
    order = [v.decode() for v in g.E.save.bagOrder.values()]
    added = [o for o in order if o in ("POKE_BALL", "HP_UP", "POTION", "REPEL", "TM_MEGA_PUNCH")]
    assert added == ["POKE_BALL", "POKE_BALL", "HP_UP", "POTION", "REPEL", "TM_MEGA_PUNCH"]
    sync = syncs(g)[-1]
    assert sync["items"] == {"POTION": 5, "POKE_BALL": 150, "REPEL": 2, "TM_MEGA_PUNCH": 1, "HP_UP": 1}
    assert sync["money"] == 1234 and sync["stamp"] == STAMP
    assert g.b.mod._save.ledger.stamp.decode() == STAMP, "the stamp is in the save's mod data"
    st = g.status()
    assert st["ledgerSets"] == 1 and st["ledgerOk"] == 1 and st["ledgerTooMany"] == 0 and st["ledgerStamp"] is True
    assert g.status()["money"] == 1234


def test_what_does_not_fit_comes_back_as_too_many_and_the_rest_is_written():
    g = ledger_game()
    g.E.data.constants.bagSize = 3                # a tiny bag: 2 slots stay taken (TOWN_MAP, TEST_RELIC)
    g.connect()
    seq = bag_set(g, {"POKE_BALL": 10, "POTION": 5, "X_ATTACK": 1}, money=50)
    g.run()
    assert results(g) == [({"ok": False, "kind": "bag.set", "reason": "too_many", "left": {"POTION": 5, "X_ATTACK": 1}}, seq)]
    bag = inv(g)
    assert bag["POKE_BALL"] == 10 and "POTION" not in bag and "X_ATTACK" not in bag, "balls first (class order), the one free slot"
    assert bag["TOWN_MAP"] == 1 and bag["TEST_RELIC"] == 1
    assert g.E.save.money == 50
    assert syncs(g)[-1]["items"] == {"POKE_BALL": 10} and syncs(g)[-1]["stamp"] == STAMP, "written anyway, stamped anyway"
    st = g.status()
    assert st["ledgerSets"] == 1 and st["ledgerTooMany"] == 1 and st["ledgerLeft"] == 2 and st["ledgerRefused"] == 1


def test_the_stamp_is_echoed_by_every_later_sync():
    g = ledger_game()
    g.connect()
    assert "stamp" not in syncs(g)[0], "never projected: no stamp"
    bag_set(g, {"POTION": 1}, money=3000)
    g.run()
    stock(g, POTION=1)
    g.run(2)
    assert syncs(g)[-1]["items"]["POTION"] == 2 and syncs(g)[-1]["stamp"] == STAMP
    g.send("resync")
    g.run()
    assert syncs(g)[-1]["stamp"] == STAMP and syncs(g)[-1]["money"] == 3000
    # a second projection replaces the stamp (a sync goes out for the stamp alone)
    before = len(syncs(g))
    bag_set(g, {"POTION": 2}, money=3000, stamp="fedcba98765432100123456789abcdef")
    g.run()
    assert len(syncs(g)) == before + 1 and syncs(g)[-1]["stamp"] == "fedcba98765432100123456789abcdef"


def test_at_the_title_the_projection_is_refused():
    g = ledger_game()
    g.connect()
    g.E.leaveWorld()
    seq = bag_set(g, {"POTION": 9})
    g.run(2)
    assert results(g) == [({"ok": False, "kind": "bag.set", "reason": "not_ingame"}, seq)]
    assert inv(g)["POTION"] == 2 and g.E.save.money == 3000 and g.status()["ledgerStamp"] is False


def test_under_a_screen_the_projection_waits_then_gives_up_as_busy():
    g = ledger_game()
    g.connect()
    g.E.push(g.E.text())
    seq = bag_set(g, {"POTION": 9})
    g.run(100)
    assert results(g) == [] and inv(g)["POTION"] == 2, "waiting for a free world"
    g.run(30)
    assert results(g) == [({"ok": False, "kind": "bag.set", "reason": "busy"}, seq)]
    assert inv(g)["POTION"] == 2 and g.status()["ledgerBusy"] == 1
    g.E.pop()
    seq2 = bag_set(g, {"POTION": 9})
    g.run()
    assert results(g)[-1] == ({"ok": True, "kind": "bag.set"}, seq2) and inv(g)["POTION"] == 9


def test_symbols_the_game_does_not_know_are_dropped_and_counted():
    g = ledger_game()
    g.connect()
    seq = bag_set(g, {"NOT_AN_ITEM": 1, "POTION": 1})
    g.run()
    assert results(g) == [({"ok": True, "kind": "bag.set"}, seq)]
    assert "NOT_AN_ITEM" not in inv(g) and inv(g)["POTION"] == 1
    assert g.status()["ledgerUnknown"] == 1
    assert any("bag.set_unknown" in line for line in g.rig.logs())


@pytest.mark.parametrize("bad", [
    {"items": {"poke_ball": 1}, "money": 1, "stamp": STAMP},
    {"items": {"POKE_BALL": 10000}, "money": 1, "stamp": STAMP},
    {"items": {"POKE_BALL": 1}, "money": 1000000, "stamp": STAMP},
    {"items": {"POKE_BALL": 1}, "money": 1, "stamp": "xyz"},
    {"items": {"POKE_BALL": 1}, "money": 1},
    {"items": [], "money": 1, "stamp": STAMP},
])
def test_bad_bag_sets_are_refused(bad):
    g = ledger_game()
    g.connect()
    seq = g.peer.send("bag.set", bad)
    g.run()
    assert ("bad_message", seq) in errors(g) and inv(g)["POKE_BALL"] == 5 and results(g) == []


def test_a_builder_cannot_project():
    g = ledger_game()
    g.connect(role="builder")
    seq = bag_set(g, {"POTION": 9})
    g.run(2)
    assert ("role", seq) in errors(g) and inv(g)["POTION"] == 2


# ---- what happens while a projection waits ----

STAMP2 = "fedcba98765432100123456789abcdef"


def test_a_ball_thrown_while_the_projection_waits_stays_thrown():
    """The server worked the projection out before the throw; the throw's bag.use came after it and applied at once.
    The landing must not put the ball back (the server's remembered projection is already one ball less)."""
    g = ledger_game()
    g.connect()
    g.E.push(g.E.text())
    bag_set(g, {"POKE_BALL": 5, "POTION": 2}, money=3000)
    g.send("bag.use", {"item": "POKE_BALL", "qty": 1})
    g.run(5)
    assert inv(g)["POKE_BALL"] == 4 and results(g) == [], "the use applies at once; the projection waits"
    g.E.pop()
    g.run()
    assert inv(g)["POKE_BALL"] == 4, "the landing put the thrown ball back"
    assert syncs(g)[-1]["items"] == {"POKE_BALL": 4, "POTION": 2} and syncs(g)[-1]["stamp"] == STAMP
    assert g.status()["ledgerKept"] == 1
    # the bag trace tells the story: the use in and applied, the projection noted, landing with the throw kept
    logs = g.rig.logs()
    assert any("bagtrace.use_in" in line and "item=POKE_BALL" in line for line in logs)
    assert any("bagtrace.use_applied" in line and "removed=1" in line for line in logs)
    assert any("bagtrace.set_in" in line and "noted=true" in line for line in logs)
    assert any("bagtrace.set_land" in line and "POKE_BALL=4" in line and "kept=1" in line for line in logs)
    assert any("bagtrace.sync_out" in line and "POKE_BALL=4" in line and "stamp=012345" in line for line in logs)


def test_an_item_found_and_prize_money_while_the_projection_waits_are_kept():
    g = ledger_game()
    g.connect()
    g.E.push(g.E.text())
    bag_set(g, {"POTION": 2}, money=3000)
    g.run(5)
    stock(g, POTION=1)          # an item ball
    g.E.save.money = 3200       # prize money
    g.E.pop()
    g.run()
    assert inv(g)["POTION"] == 3 and g.E.save.money == 3200, "the landing wiped what the game gained meanwhile"
    sync = syncs(g)[-1]
    assert sync["items"] == {"POTION": 3} and sync["money"] == 3200 and sync["stamp"] == STAMP,         "the confirming sync carries the gains, so the server reads them back"


def test_a_use_that_came_before_the_projection_is_not_applied_twice():
    """The server debited the ledger, sent the bag.use, then a projection that already counts it: same frame."""
    g = ledger_game()
    g.connect()
    g.send("bag.use", {"item": "POKE_BALL", "qty": 1})
    bag_set(g, {"POKE_BALL": 4, "POTION": 2}, money=3000)
    g.run()
    assert inv(g)["POKE_BALL"] == 4, "the use applied after the landing too"
    assert syncs(g)[-1]["items"] == {"POKE_BALL": 4, "POTION": 2}


def test_a_landing_is_no_change_of_the_games_own_for_the_projection_after_it():
    g = ledger_game()
    g.connect()
    g.E.push(g.E.text())
    bag_set(g, {"POTION": 5}, money=3000)
    bag_set(g, {"POTION": 7}, money=3000, stamp=STAMP2)
    g.run(5)
    stock(g, POTION=1)          # found while both wait
    g.E.pop()
    g.run(4)
    assert [r["ok"] for r, _ in results(g)] == [True, True]
    assert inv(g)["POTION"] == 8, "the second projection plus the one found, not the first landing again"
    assert syncs(g)[-1]["stamp"] == STAMP2 and syncs(g)[-1]["items"] == {"POTION": 8}
