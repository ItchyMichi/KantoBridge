"""MG7 G3F (docs/specs/GEN3_BRIDGE_PLAN.md §4.10): the Gen 3 adapter's items part
(gen1-bridge/lib/engine/game3/bag.lua) on the fake game3 engine (fake_battle3.lua) with the real catalogue
(protocol/items.json's gen3 symbols): the item table, key items (the key pocket, HMs, badges), the bag's counts and
removals, bag.set's projection (by class, all or nothing per 99, money clamped, key items untouched, what didn't
fit), item uses on party members refused while a client follows, and the whiteout routes asks for. Then main.lua
with a peer: bag.sync with Emerald's items and bag.set into them. Everything is made up (the fake's item ids)."""

import pytest

from adapter3 import Adapter3, plain
from rig import Rig

pytestmark = pytest.mark.lua

TOWN = "EM_TEST_TOWN"
POKE_BALL, POTION, RARE_CANDY, FIRE_STONE, NUGGET, TM01, TM06, HM01, MACH_BIKE = 5, 13, 68, 94, 110, 289, 294, 339, 259


def adapter(live=True):
    a = Adapter3()
    a.eng = a.Engine.new(a.b.engine_opts(log=a.log, love=a.b.env.love, items=a.b.module("lib/kb_items.lua")))
    a.eng.setGame(a.E.game)
    s = a.field()
    a.bridge = a.b.eval("function(live) return { live = function() return live end, battle = function() return nil end } end")(live)
    a.eng.attachBridge(a.bridge)
    return a, s


def give(a, s, item, qty):
    assert a.E.Bag.add(s.bag, item, qty)


def test_the_item_table_and_key_items():
    a, _ = adapter()
    table = plain(a.eng.itemTable())
    assert table["POTION"] == "potion" and table["PARALYZE_HEAL"] == "parlyz_heal" and table["TM06"] == "tm_toxic"
    assert table["TM01"] == "tm_focus_punch" and table["SUN_STONE"] == "sun_stone"
    assert a.eng.itemClass(b"FIRE_STONE") == b"stone" and a.eng.itemClass(b"TM01") == b"tm"
    for key in (b"MACH_BIKE", b"HM01", b"STONEBADGE"):
        assert a.eng.isKeyItem(key) is True, key
    for plain_item in (b"POTION", b"TM01", b"NUGGET"):
        assert a.eng.isKeyItem(plain_item) is False, plain_item


def test_counts_and_removals():
    a, s = adapter()
    give(a, s, POTION, 3)
    give(a, s, POKE_BALL, 120)
    give(a, s, MACH_BIKE, 1)
    counts = plain(a.eng.bagCounts(a.lua(["POTION", "POKE_BALL", "MACH_BIKE", "NUGGET"])))
    assert counts == {"POTION": 3, "POKE_BALL": 120}, "never a key item, never zero"
    assert tuple(a.eng.bagRemove(b"POKE_BALL", 200)) == (120, 120)
    assert tuple(a.eng.bagRemove(b"POTION", 1)) == (1, 3)
    assert a.eng.inventoryCount(b"POTION") == 2
    assert tuple(a.eng.bagRemove(b"NO_SUCH_ITEM", 1)) == (0, 0)


def test_bag_set_projects_the_ledger_into_the_bag():
    a, s = adapter()
    give(a, s, POTION, 5)
    give(a, s, MACH_BIKE, 1)
    give(a, s, HM01, 1)
    a.E.ItemsData.CAPACITY.POKE_BALLS = 1  # one slot of 99 in the ball pocket
    ok, left = a.eng.bagSet(a.lua({"POKE_BALL": 150, "FIRE_STONE": 2, "TM06": 1, "MACH_BIKE": 5}), 1234567)
    assert ok is True
    assert plain(left) == {"POKE_BALL": 51}, "all or nothing per 99: 99 fit the one slot, the other 51 need a second"
    assert a.E.Bag.get(s.bag, POTION) == 0, "what the ledger doesn't hold leaves the bag"
    assert a.E.Bag.get(s.bag, FIRE_STONE) == 2 and a.E.Bag.get(s.bag, TM06) == 1
    assert a.E.Bag.get(s.bag, MACH_BIKE) == 1 and a.E.Bag.get(s.bag, HM01) == 1, "key items and HMs untouched"
    assert s.money == 999999, "clamped"


def test_item_uses_on_members_are_refused_while_a_client_follows():
    a, s = adapter()
    nxt = a.b.eval("function(g, battle, id, slot, bag) return true, 'used', 'ok' end")
    for item in (FIRE_STONE, RARE_CANDY, TM01, HM01):
        assert tuple(a.eng.itemUse(nxt, a.E.game, None, item, 1, s.bag)) == (False, b"noeffect", b"It won't have any effect."), item
    assert tuple(a.eng.itemUse(nxt, a.E.game, None, POTION, 1, s.bag)) == (True, b"used", b"ok")
    assert tuple(a.eng.itemUse(nxt, a.E.game, None, FIRE_STONE, None, s.bag)) == (True, b"used", b"ok"), "not on a member"
    off, s2 = adapter(live=False)
    nxt2 = off.b.eval("function(g, battle, id, slot, bag) return true, 'used', 'ok' end")
    assert tuple(off.eng.itemUse(nxt2, off.E.game, None, FIRE_STONE, 1, s2.bag)) == (True, b"used", b"ok"), "no client"


def test_the_blackout_routes_asks_for():
    a, s = adapter()
    money = s.money
    assert a.eng.blackout() is True
    assert s.money == money // 2 and a.E.respawns == 1
    a.E.Battle._active = True
    assert tuple(a.eng.blackout()) == (False, b"busy")


# ------------------------------------------------------------ main.lua and a peer

def pump(rig, peer, until, limit=30):
    got = []
    for _ in range(limit):
        rig.step()
        got.extend(peer.messages())
        if any(until(m) for m in got):
            return got
    raise AssertionError(f"not within {limit} steps: {[m['type'] for m in got]}")


def test_main_syncs_emeralds_bag_and_projects_the_ledger_into_it():
    rig = Rig(family="gen3")
    rig.world(TOWN, 5, 6, "down")
    s = rig.E.Runtime.getSession()
    assert rig.E.Bag.add(s.bag, POTION, 4)
    assert rig.E.Bag.add(s.bag, MACH_BIKE, 1)
    peer = rig.connect()
    peer.hello()
    got = pump(rig, peer, lambda m: m["type"] == "bag.sync")
    sync = [m for m in got if m["type"] == "bag.sync"][-1]["data"]
    assert sync["items"].get("POTION") == 4 and "MACH_BIKE" not in sync["items"]
    seq = peer.send("bag.set", {"items": {"POKE_BALL": 10, "SUN_STONE": 1}, "money": 500, "stamp": "a" * 64})
    got = pump(rig, peer, lambda m: m["type"] == "act.result" and m.get("re") == seq)
    result = next(m for m in got if m["type"] == "act.result" and m.get("re") == seq)["data"]
    assert result["ok"] is True, result
    assert rig.E.Bag.get(s.bag, POKE_BALL) == 10 and rig.E.Bag.get(s.bag, POTION) == 0 and s.money == 500
