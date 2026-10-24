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
UID3 = "33333333-3333-3333-3333-333333333333"
UID4 = "44444444-4444-4444-8444-444444444444"


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


def test_a_sync_waits_for_a_trade_under_way_and_an_egg_hatching():
    a, _ = adapter()
    a.E.Trade._offered = a.lua({"species": 6})
    a.E.Space.vm.running = True
    assert a.eng.partySafe() is False, "the trade's member was chosen by its party slot"
    a.E.Trade._offered = None
    assert a.eng.partySafe() is True
    a.E.TradeScene.open = True
    assert a.eng.partySafe() is False
    a.E.TradeScene.open = False
    a.E.EggHatch.open = True
    assert a.eng.partySafe() is False
    a.E.EggHatch.open = False
    a.E.Space.vm.running = False
    assert a.eng.partySafe() is True


# ------------------------------------------------------------ trades, the daycare, eggs (follow-up item 7)

def test_a_trade_takes_the_member_sent_and_gives_the_one_received():
    a, s = adapter()
    a.eng.applyParty(a.lua([entry(UID1, 4, 5), entry(UID2, 5, 7)]))
    sent = s.party[2]
    a.E.Space.vm.running = True  # the trade's script runs on (its scene, a trade evolution)
    offered = a.E.tradeMon(6, 7, b"DOTS", b"KOBE")
    assert a.same(a.E.Trade.tradeMons(s, 1, offered), sent)
    ops = plain(a.eng.ops())
    assert [(o["kind"], o["uid"], o["reason"], o["state"]) for o in ops] == [("take", UID2, "trade", "new")]
    assert set(plain(a.eng.excludedUids())) == {UID2}
    assert plain(a.eng.gifts()) == {}, "the received one waits for the trade's script"
    a.E.Space.vm.running = False
    gifts = plain(a.eng.gifts())
    assert [(g["source"], g["dex"], g["level"], g["nick"], g["ot"], g["box"]) for g in gifts] == [
        ("trade", 256, 7, "DOTS", "KOBE", False)]
    # Cobblemon's sync from before the take (the core leaves the taken uid out): the one received stays
    res = plain(a.eng.applyParty(a.lua([entry(UID1, 4, 5)])))
    assert res["kept"] == 1 and len(s.party) == 2 and a.same(s.party[2], offered)
    # the sync after it: the take confirmed (the core's opDone), the gift matched
    a.eng.opDone(ops[0]["id"])
    a.eng.giftSent(gifts[0]["give"].encode(), 1)
    res = plain(a.eng.applyParty(a.lua([entry(UID1, 4, 5), entry(UID3, 6, 7, give=gifts[0]["give"])])))
    assert res["matched"] == 1 and a.same(s.party[2], offered) and a.eng.uidOf(offered) == UID3.encode()
    assert plain(a.eng.ops()) == {} and plain(a.eng.excludedUids()) == {}
    # a member Cobblemon never named (a gift not sent yet) is simply forgotten
    a.E.giveMon(7, 3, b"", False)
    a.E.Trade.tradeMons(s, 2, a.E.tradeMon(8, 3, b"X", b"Y"))
    assert [g["source"] for g in plain(a.eng.gifts())] == ["trade"]
    assert plain(a.eng.ops()) == {} and ("warn", "engine.trade_no_uid") in a.lines()
    counts = plain(a.eng.bridgeCounts())
    assert (counts["trades"], counts["tradeNoUid"]) == (2, 1)


def test_the_daycare_boards_two_and_gives_each_back_with_its_steps():
    a, s = adapter()
    a.eng.applyParty(a.lua([entry(UID1, 4, 5), entry(UID2, 5, 7), entry(UID3, 6, 9)]))
    first, second = s.party[2], s.party[1]
    assert a.eng.daycareStep(None) is None, "nobody boarded"
    a.E.Daycare.deposit(s, 2)
    ev = plain(a.eng.daycareStep(None))
    assert (ev["kind"], ev["uid"]) == ("deposit", UID2)
    assert ev["store"] == {"boarded": [{"uid": UID2, "pid": first.personality}]}
    a.E.Daycare.deposit(s, 1)
    ev = plain(a.eng.daycareStep(a.lua(ev["store"])))
    assert (ev["kind"], ev["uid"]) == ("deposit", UID1)
    stored = ev["store"]
    assert stored == {"boarded": [{"uid": UID2, "pid": first.personality}, {"uid": UID1, "pid": second.personality}]}
    assert [(o["kind"], o["uid"]) for o in plain(a.eng.ops())] == [("deposit", UID2), ("deposit", UID1)]
    assert set(plain(a.eng.excludedUids())) == {UID1, UID2}
    assert plain(a.eng.daycareUids()) == [UID2, UID1] and a.eng.daycareUid() == UID2.encode()
    for _ in range(3):
        a.E.Daycare.step(s)
        assert a.eng.daycareStep(a.lua(stored)) is None
    for _ in range(4):
        a.E.Daycare.step(s)  # steps the watch doesn't see one by one: Daycare.withdraw is given them all
    a.E.Daycare.take(s, 1)  # the first one boarded, with its seven steps (the second moves up a slot)
    ev = plain(a.eng.daycareStep(a.lua(stored)))
    assert (ev["kind"], ev["uid"], ev["steps"]) == ("withdraw", UID2, 7)
    assert ev["store"] == {"boarded": [{"uid": UID1, "pid": second.personality}]}
    assert a.same(s.party[2], first) and a.eng.uidOf(first) == UID2.encode()
    assert [(o["kind"], o["uid"], o.get("steps")) for o in plain(a.eng.ops())][2] == ("withdraw", UID2, 7)
    # Cobblemon's sync from before the withdrawal (the core leaves the boarded uid out): the one back stays
    res = plain(a.eng.applyParty(a.lua([entry(UID3, 6, 9)])))
    assert res["kept"] == 1 and len(s.party) == 2 and a.same(s.party[2], first)
    assert set(plain(a.eng.excludedUids())) == {UID1}
    counts = plain(a.eng.bridgeCounts())
    assert (counts["deposits"], counts["withdraws"], counts["boarded"], counts["daycareHeld"]) == (2, 1, 1, True)
    # a CONTINUE (a new session from the save): the member the daycare holds gets its uid back from mod.save, by
    # its personality value; the old session's operations are dropped
    a.E.game.session = None
    s2 = a.field()
    loaded = a.lua({"species": 4, "speciesId": 4, "level": 5, "personality": second.personality, "moves": [33]})
    s2.daycare = a.lua({"steps": [5, 0], "stepCounter": 0})
    s2.daycare[1] = loaded
    ev = plain(a.eng.daycareStep(a.lua({"boarded": [{"uid": UID1, "pid": second.personality}]})))
    assert ev == {"kind": "adopted", "count": 1}
    assert plain(a.eng.daycareUids()) == [UID1] and a.eng.uidOf(loaded) == UID1.encode()
    assert plain(a.eng.ops()) == {}
    # one the save's daycare holds that mod.save doesn't know
    a.E.game.session = None
    s3 = a.field()
    s3.daycare = a.lua({"steps": [0, 0], "stepCounter": 0})
    s3.daycare[1] = a.lua({"species": 4, "speciesId": 4, "level": 5, "personality": 1, "moves": [33]})
    assert plain(a.eng.daycareStep(a.lua(stored))) == {"kind": "unknown"}
    assert plain(a.eng.daycareUids()) == {}
    s3.daycare[1] = None
    assert plain(a.eng.daycareStep(None)) == {"kind": "cleared", "store": False}


def test_eggs_stay_in_the_hidden_game_until_they_hatch():
    a, s = adapter()
    a.eng.applyParty(a.lua([entry(UID1, 4, 5)]))
    egg = a.E.Breeding.giveEggFromDaycare(s)
    ok, _, gift_egg = a.E.Party.giveEgg(s, 7, a.lua({}))[0:3]
    assert ok and gift_egg.isEgg is True
    assert plain(a.eng.gifts()) == {}, "an egg is never a gift (Cobblemon has no eggs)"
    res = plain(a.eng.applyParty(a.lua([entry(UID1, 4, 5)])))
    assert (res["eggs"], res["size"]) == (2, 3) and a.same(s.party[2], egg) and a.same(s.party[3], gift_egg)
    # Cobblemon's party full: the eggs wait in a hidden box, and come back once it has room
    six = [entry("%08d-1111-1111-1111-111111111111" % i, 4, 5) for i in range(6)]
    res = plain(a.eng.applyParty(a.lua(six)))
    assert (res["parked"], res["eggs"], res["size"]) == (2, 0, 6)
    box = s.storage.boxes[1].mons
    assert a.same(box[1], egg) and a.same(box[2], gift_egg)
    res = plain(a.eng.applyParty(a.lua(six[:5])))
    assert (res["back"], res["size"]) == (1, 6) and a.same(s.party[6], egg) and box[1] is None
    res = plain(a.eng.applyParty(a.lua([entry(UID1, 4, 5)])))
    assert (res["eggs"], res["back"], res["size"]) == (2, 1, 3) and box[2] is None
    # hatching: a gift with source egg once the hatch screen (its nickname prompt) is done
    a.E.EggHatch.open = True
    a.E.Breeding.hatchMon(s, egg)
    egg.nickname = b"SPROUT"
    assert plain(a.eng.gifts()) == {} and a.eng.partySafe() is False
    a.E.EggHatch.open = False
    gifts = plain(a.eng.gifts())
    assert [(g["source"], g["dex"], g["level"], g["nick"]) for g in gifts] == [("egg", 255, 5, "SPROUT")]
    res = plain(a.eng.applyParty(a.lua([entry(UID1, 4, 5)])))
    assert (res["kept"], res["eggs"], res["size"]) == (1, 1, 3)
    counts = plain(a.eng.bridgeCounts())
    assert (counts["hatched"], counts["eggGifts"], counts["eggsParked"], counts["eggsBack"], counts["eggs"],
            counts["eggsLost"]) == (1, 1, 2, 2, 1, 0)


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


def test_main_sends_trades_the_daycare_and_hatched_eggs():
    rig = Rig(family="gen3")
    rig.world(TOWN, 5, 6, "down")
    peer = rig.connect()
    peer.hello()
    pump(rig, peer, lambda m: m["type"] == "snapshot")

    def member(uid, dex):
        return {"uid": uid, "dex": dex, "level": 10, "moves": [33], "hp": 30, "maxhp": 30}

    team = [member(UID1, 254), member(UID2, 255), member(UID3, 256)]
    peer.send("party.sync", {"party": team, "box": {"count": 0}})
    rig.step(3)
    s = rig.E.Runtime.getSession()
    assert len(s.party) == 3
    # an in-game trade: the member sent goes as party.take, the one received as party.give (source trade)
    rig.E.Trade.tradeMons(s, 2, rig.E.tradeMon(7, 9, b"DOTS", b"KOBE"))
    got = pump(rig, peer, lambda m: m["type"] == "party.give")
    assert [m["data"] for m in got if m["type"] == "party.take"] == [{"uid": UID3, "reason": "trade"}]
    give = next(m for m in got if m["type"] == "party.give")["data"]
    assert (give["source"], give["dex"], give["level"], give["nick"], give["ot"]) == ("trade", 257, 9, "DOTS", "KOBE")
    # two members boarded at the daycare
    rig.E.Daycare.deposit(s, 1)
    got = pump(rig, peer, lambda m: m["type"] == "daycare.deposit")
    rig.E.Daycare.deposit(s, 1)
    got += pump(rig, peer, lambda m: m["type"] == "daycare.deposit")
    assert [m["data"] for m in got if m["type"] == "daycare.deposit"] == [{"uid": UID1}, {"uid": UID2}]
    # Cobblemon's sync from before any of it changes nothing: the traded and boarded uids stay out, the one
    # received stays
    peer.send("party.sync", {"party": team, "box": {"count": 0}})
    rig.step(3)
    assert len(s.party) == 1 and s.party[1].nickname == b"DOTS"
    # an egg from the daycare: nothing goes out until it hatches, then a gift with source egg
    egg = rig.E.Breeding.giveEggFromDaycare(s)
    assert not any(m["type"] == "party.give" for m in pump_for(rig, peer, 5))
    rig.E.Breeding.hatchMon(s, egg)
    got = pump(rig, peer, lambda m: m["type"] == "party.give")
    hatched = next(m for m in got if m["type"] == "party.give")["data"]
    assert (hatched["source"], hatched["dex"], hatched["level"]) == ("egg", 255, 5)
    # save.loaded names both members the daycare holds
    peer.send("session.bind", {"world": "hoenn-test-world", "multiplayer": False})
    got = pump(rig, peer, lambda m: m["type"] == "save.loaded")
    loaded = next(m for m in got if m["type"] == "save.loaded")["data"]
    assert (loaded["daycare"], loaded["boarded"]) == (UID1, [UID1, UID2])


def pump_for(rig, peer, steps):
    got = []
    for _ in range(steps):
        rig.step()
        got.extend(peer.messages())
    return got


# ------------------------------------------------------------ FP3: visitors in Emerald (FOREIGN_POKEMON_PLAN section 3)

STATS = {"hp": 61, "atk": 30, "def": 31, "spa": 32, "spd": 33, "spe": 34}


def registered(a):
    content = a.b.eval("""function(E)
      local kept = { pokemon = {}, hooks = {} }
      E.Pokemon.name = function(sp) return tonumber(sp) == 440 and "VISITOR" or nil end
      local content = { pokemon = { register = function(_, id, rec) kept.pokemon[id] = rec return rec end } }
      local hooks = { wrap = function(_, name, fn) kept.hooks[name] = fn end }
      return content, hooks, kept
    end""")
    c, hooks, kept = content(a.E)
    assert a.eng.registerVisitor(c, b"/v", hooks) is True
    return kept


def test_the_stand_in_registers_at_440_with_its_art_through_the_hooks():
    a, _ = adapter()
    kept = registered(a)
    rec = kept.pokemon.KB_VISITOR
    assert rec.index == 440 and rec.catchRate == 0 and list(rec.types.values()) == [b"MYSTERY"]
    sprite = kept.hooks[b"pokemon.sprite"]
    nxt = a.b.eval("function(path) return 'native' end")
    assert sprite(nxt, b"x", a.lua({"gen3Species": 440, "side": "back"})) == b"/v/game3_back.png"
    assert sprite(nxt, b"x", a.lua({"gen3Species": 4, "side": "front"})) == b"native"
    assert kept.hooks[b"pokemon.icon"](nxt, b"x", a.lua({"gen3Species": 440})) == b"/v/game3_icon.png"
    assert a.eng.visitorSpecies() == 440
    moves = a.eng.visitorMoves()
    assert moves[57] is True and moves[291] is True and moves[33] is None, "Surf and Dive, not Tackle"


def test_a_visitors_stats_stand_through_apply_stats():
    a, s = adapter()
    registered(a)
    a.eng.applyParty(a.lua([dict(entry(UID1, 440, 20, hp=50, maxhp=61, nick="SKITTY"), stats=STATS, stones=["moon_stone"])]))
    m = s.party[1]
    assert (m.species, m.nickname, m.maxHp, m.attack, m.spAtk, m.hp) == (440, b"SKITTY", 61, 30, 32, 50)
    a.E.Pokemon.applyStats(m)       # a load, a summary: the engine's recompute
    assert (m.maxHp, m.speed, m.spDef) == (61, 34, 33)
    assert m.kbStones.MOON_STONE is True


def test_main_makes_visitors_of_what_emerald_lacks_and_drops_moves_past_its_ceiling():
    # R2 through main.lua: dex 0, a species past 386 and a form Emerald lacks are visitors
    # in their places; §4.2 a visitor keeps Emerald's field moves only; a native drops a move past 354
    def prepare(E, b):
        E.Pokemon.name = b.eval("function(sp) return tonumber(sp) == 440 and 'VISITOR' or nil end")
    rig = Rig(family="gen3", prepare=prepare)
    rig.world(TOWN, 5, 6, "down")
    peer = rig.connect()
    peer.hello()
    got = pump(rig, peer, lambda m: m["type"] == "snapshot")
    assert next(m for m in got if m["type"] == "hello.ok")["data"]["compat"]["visitor"] is True
    peer.send("party.sync", {"party": [
        {"uid": UID1, "dex": 254, "level": 6, "moves": [33, 400], "hp": 15, "maxhp": 25},
        {"uid": UID2, "dex": 427, "level": 20, "moves": [33, 57, 291], "hp": 50, "maxhp": 61, "name": "Buneary",
         "stats": STATS, "types": ["normal"]},
        {"uid": UID3, "dex": 0, "level": 5, "moves": [15], "hp": 20, "maxhp": 20, "nick": "CUSTOM"},
        {"uid": UID4, "dex": 351, "level": 9, "moves": [33], "hp": 20, "maxhp": 20, "form": "sunny", "name": "Castform"},
    ], "box": {"count": 0}})
    rig.step(3)
    s = rig.E.Runtime.getSession()
    assert [s.party[i].species for i in range(1, 5)] == [4, 440, 440, 440]
    assert list(s.party[1].moves.values()) == [33], "a move past Emerald's 354 is dropped"
    v = s.party[2]
    assert list(v.moves.values()) == [57, 291] and v.nickname == b"BUNEARY" and (v.maxHp, v.hp, v.speed) == (61, 50, 34)
    assert s.party[3].nickname == b"CUSTOM" and list(s.party[3].moves.values()) == [15]
    assert s.party[4].nickname == b"CASTFORM"
    seq = peer.send("debug.status")
    rig.step()
    st = [m for m in peer.messages() if m["type"] == "debug.status" and m.get("re") == seq][0]["data"]
    assert st["partyVisitorsNow"] == 3 and st["partyForeignMoves"] == 1
    peer.send("resync", {})
    got = pump(rig, peer, lambda m: m["type"] == "snapshot")
    snap = next(m for m in got if m["type"] == "snapshot")["data"]
    assert [(p["dex"], p.get("visitor", False)) for p in snap["party"]] == [(254, False), (427, True), (0, True), (351, True)]
