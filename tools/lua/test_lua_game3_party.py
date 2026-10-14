"""MG7 G3F (docs/specs/GEN3_BRIDGE_PLAN.md §4.10): the Gen 3 adapter's party part
(gen1-bridge/lib/engine/game3/party.lua) on the fake game3 engine (fake_battle3.lua). The adapter alone: species
and moves by national and Showdown numbers; applyParty rebuilding the shadow party in place (a known uid updated,
a new one made on a scratch session so the live party and Pokedex stay untouched, HP scaled, status and
nickname, the battle's move overlay reset); gifts from the engine's giveMon (the starter and other gifts, their
facts read when they go out, waiting for the giving script unless a battle is held, a box gift leaving the hidden
box); heals and the blackout's hold; the Pokedex; nicknames; native catches; when a sync may apply. Then main.lua
with a peer (Rig(family="gen3")): party.give, party.sync, dex.sync and the snapshot's party and Pokedex, every
message schema-checked. Everything is made up."""

import pytest

from adapter3 import Adapter3, plain
from rig import Rig

pytestmark = pytest.mark.lua

TOWN = "EM_TEST_TOWN"
UID1 = "11111111-1111-1111-1111-111111111111"
UID2 = "22222222-2222-2222-2222-222222222222"


def adapter(live=True):
    a = Adapter3()
    s = a.field()
    a.bridge = a.b.eval("function(live) return { live = function() return live end, battle = function() return nil end } end")(live)
    a.eng.attachBridge(a.bridge)
    return a, s


def entry(uid, species, level, hp=20, maxhp=40, moves=(33,), status=None, nick=None, give=None):
    e = {"uid": uid, "species": species, "level": level, "moves": list(moves), "hp": hp, "maxhp": maxhp}
    if status:
        e["status"] = status
    if nick:
        e["nick"] = nick
    if give:
        e["give"] = give
    return e


def test_numbers_are_national_dex_and_showdown_moves():
    a, _ = adapter()
    assert a.eng.dexOf(4) == 254 and a.eng.speciesOfDex(254) == 4
    assert a.eng.speciesOfDex(151) is None, "the fake engine has Hoenn's species only"
    assert a.eng.moveNumOf(354) == 354 and a.eng.moveIdOfNum(355) is None and a.eng.moveIdOfNum(0) is None


def test_apply_party_rebuilds_the_shadow_party_in_place():
    a, s = adapter()
    party = s.party
    res = plain(a.eng.applyParty(a.lua([entry(UID1, 4, 12, hp=20, maxhp=40, moves=(33, 45), status="PSN", nick="ZIGGY"),
                                         entry(UID2, 5, 3)])))
    assert (res["created"], res["updated"], res["size"]) == (2, 0, 2)
    assert a.same(s.party, party), "refilled in place (scripts hold session.party)"
    m = s.party[1]
    assert (m.species, m.level, list(m.moves.values()), m.status, m.nickname) == (4, 12, [33, 45], b"PSN", b"ZIGGY")
    assert m.maxHp == 10 + 3 * 12 and m.hp == round(20 * m.maxHp / 40), "HP scaled to the engine's maximum"
    assert plain(s.dex) is None or 4 not in plain(s.dex.seen or {}), "made on a scratch session: no Pokedex change"
    assert plain(s.move_overlay) == {}
    uids, known = a.eng.partyUids()
    assert plain(uids) == [UID1, UID2] and set(plain(known)) == {UID1, UID2}
    # a known uid keeps its record
    first = s.party[1]
    res = plain(a.eng.applyParty(a.lua([entry(UID1, 4, 13, hp=0, maxhp=40)])))
    assert (res["updated"], res["size"]) == (1, 1)
    assert a.same(s.party[1], first) and first.level == 13 and first.hp == 0 and first.nickname == b""
    assert a.eng.uidOf(first) == UID1.encode()


def test_a_gift_goes_out_with_its_facts_after_its_script_and_its_record_is_kept():
    a, s = adapter()
    a.E.Space.vm.running = True
    ok, _, mon = a.E.giveMon(4, 5, b"", False)[0:3]
    assert ok
    assert plain(a.eng.gifts()) == {}, "the giving script still runs (its nickname may come later)"
    assert a.eng.partySafe() is False
    mon.nickname = b"TREECKO JR"
    a.E.Space.vm.running = False
    gifts = plain(a.eng.gifts())
    assert len(gifts) == 1
    g = gifts[0]
    assert (g["dex"], g["level"], g["moves"], g["nick"], g["source"], g["box"]) == (254, 5, [33], "TREECKO JR", "starter", False)
    assert g["ivs"] == {"hp": 1, "atk": 2, "def": 3, "spa": 4, "spd": 5, "spe": 6}
    a.eng.giftSent(g["give"].encode(), 1)
    # Cobblemon's sync names the gift: its record is the member
    res = plain(a.eng.applyParty(a.lua([entry(UID1, 4, 5, give=g["give"])])))
    assert res["matched"] == 1 and a.same(s.party[1], mon)
    assert plain(a.eng.gifts()) == {}


def test_an_unconfirmed_gift_stays_and_a_box_gift_leaves_the_box():
    a, s = adapter()
    a.E.Flags.IDS[b"FLAG_SYS_POKEMON_GET"] = 0x860
    a.E.setFlag(0x860)
    a.eng.applyParty(a.lua([entry("%08d-1111-1111-1111-111111111111" % i, 4, 5) for i in range(6)]))
    ok, _, mon, box, slot = a.E.giveMon(5, 7, b"", True)
    assert ok and box == 1 and slot == 1
    g = plain(a.eng.gifts())[0]
    assert (g["source"], g["box"], g["dex"]) == ("gift", True, 255)
    a.eng.giftSent(g["give"].encode(), 1)
    assert plain(s.storage.boxes[1].mons) == {}, "Cobblemon's PC keeps it"
    # a party gift Cobblemon hasn't shown yet stays after the sync's members
    a.eng.applyParty(a.lua([entry(UID1, 4, 5)]))
    ok, _, kept = a.E.giveMon(6, 9, b"", False)[0:3]
    res = plain(a.eng.applyParty(a.lua([entry(UID1, 4, 5)])))
    assert res["kept"] == 1 and a.same(s.party[2], kept)


def test_a_gift_goes_at_once_while_a_battle_is_held():
    a, _ = adapter()
    a.E.Space.vm.running = True
    a.E.giveMon(4, 5, b"", False)
    assert plain(a.eng.gifts()) == {}
    # the battle part's record of a held battle (battle.lua): Birch's battle follows the starter in one script
    a.E.game.kantoBridgeState.battle = a.lua({"state": "waiting", "id": "b-x"})
    assert len(plain(a.eng.gifts())) == 1, "the gift goes first"


def test_heals_wait_for_the_blackouts_warp():
    a, _ = adapter()
    a.eng.queueHeal(b"nurse")
    assert a.eng.takeHeals() == 1 and a.eng.takeHeals() == 0
    a.eng.queueHeal(b"blackout")
    assert a.eng.takeHeals() == 0, "held until its warp arrives"
    a.eng.releaseHeals()
    assert a.eng.takeHeals() == 1


def test_the_pokedex_nicknames_and_native_catches():
    a, s = adapter()
    res = plain(a.eng.applyDex(a.lua([252, 254, 1]), a.lua([254])))
    assert res == {"seen": 2, "owned": 1, "unknown": 1}
    assert plain(s.dex.seen) == {"2": True, "4": True} or (s.dex.seen[2] and s.dex.seen[4])
    assert s.dex.owned[4] is True
    a.eng.applyParty(a.lua([entry(UID1, 4, 5, nick="OLD")]))
    nicks, sess = a.eng.partyNicks()
    assert plain(nicks) == [{"uid": UID1, "nick": "OLD"}] and a.same(sess, s)
    a.eng.queueRename(UID1.encode(), b"NEW")
    assert plain(a.eng.takeRenames()) == [{"uid": UID1, "nick": "NEW"}] and plain(a.eng.takeRenames()) == {}
    # a catch in a battle the hidden game fights (the Safari Zone) becomes a gift once the battle ends
    caught = a.lua({"species": 9, "speciesId": 9, "level": 22, "moves": [33], "nickname": "", "ivs": {}})
    a.E.Battle._active = True
    assert a.eng.nativeCaught(a.lua({"mon": caught, "destination": "party"})) is True
    assert a.eng.releaseCatches() == 0, "not while the battle is up"
    a.E.Battle._active = False
    assert a.eng.releaseCatches() == 1
    g = plain(a.eng.gifts())[0]
    assert (g["source"], g["dex"], g["level"]) == ("native_catch", 259, 22)


def test_a_sync_waits_for_a_party_screen_and_a_battle():
    a, _ = adapter()
    assert a.eng.partySafe() is True
    a.E.Stack._layers = a.lua([{"id": "party"}])
    assert a.eng.partySafe() is False
    a.E.Stack._layers = a.lua([])
    a.E.Battle._active = True
    assert a.eng.partySafe() is False
    a.E.Battle._active = False
    assert a.eng.partySafe() is True


# ------------------------------------------------------------ main.lua and a peer

def pump(rig, peer, until, limit=30):
    got = []
    for _ in range(limit):
        rig.step()
        got.extend(peer.messages())
        if any(until(m) for m in got):
            return got
    raise AssertionError(f"not within {limit} steps: {[m['type'] for m in got]}")


def test_main_gives_syncs_and_snapshots_the_gen3_party():
    rig = Rig(family="gen3")
    rig.world(TOWN, 5, 6, "down")
    peer = rig.connect()
    peer.hello()
    pump(rig, peer, lambda m: m["type"] == "snapshot")
    rig.E.giveMon(4, 5, b"", False)
    got = pump(rig, peer, lambda m: m["type"] == "party.give")
    give = next(m for m in got if m["type"] == "party.give")["data"]
    assert (give["dex"], give["level"], give["moves"], give["source"]) == (254, 5, [33], "starter")
    assert give["ivs"] == {"hp": 1, "atk": 2, "def": 3, "spa": 4, "spd": 5, "spe": 6} and "dvs" not in give
    peer.send("party.sync", {"party": [{"uid": UID1, "dex": 254, "level": 6, "moves": [33, 354], "hp": 15, "maxhp": 25,
                                        "give": give["give"]},
                                       {"uid": UID2, "dex": 151, "level": 50, "moves": [1], "hp": 100, "maxhp": 100}],
                             "box": {"count": 0}})
    rig.step(3)
    s = rig.E.Runtime.getSession()
    assert len(s.party) == 1, "a species the fake engine hasn't got (it has Hoenn's only): left out, counted"
    assert (s.party[1].level, list(s.party[1].moves.values())) == (6, [33, 354])
    peer.send("dex.sync", {"seen": [254, 386], "owned": [254]})
    rig.step(2)
    assert s.dex.owned[4] is True
    # a new snapshot carries the party and the Pokedex by national number
    peer.send("resync", {})
    got = pump(rig, peer, lambda m: m["type"] == "snapshot")
    snap = next(m for m in got if m["type"] == "snapshot")["data"]
    assert [(p["dex"], p["level"], p["moves"]) for p in snap["party"]] == [(254, 6, [33, 354])]
    assert snap["party"][0]["uid"] == UID1
    assert 254 in snap["dex"]["owned"]
