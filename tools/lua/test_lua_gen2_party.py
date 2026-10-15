"""MG9 G2F (docs/specs/GEN2_BRIDGE_PLAN.md §5.10, J12): the Gen 2 adapter's party part (gen1-bridge/lib/engine/gen2/
party.lua) on the fake Gen 2 engine: species and moves by national number; gifts found by diffing the party, their
source from the seam that made them (givepoke: the starter before its flag, an in-game trade with its take, a hatched
egg), each with Gen 2's details (DVs, held item, gender, shininess, Unown's letter, friendship); eggs never a gift;
the Day-Care's two keepers watched (deposit, withdrawal with the experience it gave); party.sync applied (known uids
kept, new members built with DVs that reproduce the entry's gender, shininess and letter, eggs after the members and
in a hidden box when there's no room); the Pokedex, heals and nicknames. Then main.lua with a peer: party.give for the
starter. Everything is made up (TEST_* species and moves)."""

import pytest

from adapter2 import Adapter2, plain
from rig import Rig

pytestmark = pytest.mark.lua

UID1 = "11111111-1111-4111-8111-111111111111"
UID2 = "22222222-2222-4222-8222-222222222222"
UID3 = "33333333-3333-4333-8333-333333333333"


def setup(live=True):
    a = Adapter2()
    a.eng = a.Engine.new(a.b.engine_opts(log=a.log, love=a.b.env.love, world=a.E.worldApi, hooks=a.b.mod.hooks,
                                         items=a.b.module("lib/kb_items.lua")))
    a.eng.setGame(a.E.game)
    w = a.field()
    a.bridge = a.b.eval("function(live) return { live = function() return live end, battle = function() return nil end } end")(live)
    a.eng.attachBridge(a.bridge)
    a.eng.gifts()  # the first look takes the save's party as known
    return a, w


def give(a, species, level, starter_check=True):
    """givepoke through the adapter's script.command (the op appends the member, as World's givePoke does)."""
    run = a.b.eval("""function(E, eng, species, level)
      local function op(ctx, name, args, cmd)
        local save = E.game.save
        local mon = E.Mon.new(E.data, species, level)
        E.Mon.stampOT(save, mon)
        save.party[#save.party + 1] = mon
      end
      return eng.scriptCommand(op, { vm = {} }, "givepoke", {}, nil, { op = "givepoke" })
    end""")
    run(a.E, a.eng, species, level)


def gifts(a):
    g = plain(a.eng.gifts())
    return g if isinstance(g, list) else []


def seed(a, members):
    """Members already in the party with their uids (as a CONTINUE's party gets them back): not gifts."""
    for i, (species, level, uid) in enumerate(members, start=1):
        a.E.game.save.party[i] = a.E.Mon.new(a.E.data, species.encode(), level)
    a.eng.adoptUids(a.lua([uid for _, _, uid in members]))
    return party(a)


def party(a):
    p = a.E.game.save.party
    return [p[i] for i in range(1, len(p) + 1)]


def entry(uid, species, level=5, **kw):
    e = {"uid": uid, "species": species, "level": level, "moves": ["TEST_TACKLE"], "hp": 20, "maxhp": 20}
    e.update(kw)
    return e


def test_numbers():
    a, _ = setup()
    assert a.eng.dexOf(b"TEST_CHIKORITA") == 152 and a.eng.speciesOfDex(152) == b"TEST_CHIKORITA"
    assert a.eng.speciesOfDex(999) is None and a.eng.dexOf(b"NOPE") is None
    assert a.eng.moveNumOf(b"TEST_TACKLE") == 33 and a.eng.moveIdOfNum(33) == b"TEST_TACKLE"
    assert a.eng.moveIdOfNum(252) is None and a.eng.moveNumOf(b"NOPE") is None


def test_the_starter_waits_for_its_script_then_goes_with_its_details():
    a, w = setup()
    w.vmRunning = True
    a.E.nextDVs = a.lua({"attack": 2, "defense": 10, "speed": 10, "special": 10})
    give(a, b"TEST_CHIKORITA", 5)
    assert gifts(a) == [], "its script (and the nickname) still running"
    assert a.eng.partySafe() is False
    w.vmRunning = False
    g = gifts(a)
    assert len(g) == 1 and g[0]["source"] == "starter" and g[0]["dex"] == 152 and g[0]["level"] == 5
    assert g[0]["dvs"] == {"atk": 2, "def": 10, "spd": 10, "spc": 10} and g[0]["shiny"] is True
    # Gender.of: Attack 2 x 16 + Speed 10 = 42 is over the 1-in-8 species' 31: male
    assert g[0]["gender"] == "male" and g[0]["friendship"] == 70 and g[0]["ot"] == "TESTER" and g[0]["moves"] == [33]
    # once the starter flag is set, the next one is a plain gift
    a.E.setFlag(26, True)
    give(a, b"TEST_RATTATA", 3)
    w.vmRunning = False
    assert [x["source"] for x in gifts(a)] == ["starter", "gift"]


def test_a_gift_whose_op_yields_for_its_nickname_is_still_the_starter():
    # Crystal's givepoke asks for the nickname inside the op (Vm.lua:616-637): the record is in the party while the
    # op hasn't returned, and the party diff sees it then
    a, w = setup()
    w.vmRunning = True
    run = a.b.eval("""function(E, eng, adapterGifts)
      local function op(ctx, name, args, cmd)
        local save = E.game.save
        local mon = E.Mon.new(E.data, "TEST_TOTODILE" and "TEST_CHIKORITA", 5)
        save.party[#save.party + 1] = mon
        adapterGifts()  -- a step's diff while the op is suspended at its prompt
      end
      return eng.scriptCommand(op, { vm = {} }, "givepoke", {}, nil, { op = "givepoke" })
    end""")
    run(a.E, a.eng, a.eng.gifts)
    w.vmRunning = False
    g = gifts(a)
    assert len(g) == 1 and g[0]["source"] == "starter"


def test_an_unown_carries_its_letter():
    a, w = setup()
    a.E.nextDVs = a.lua({"attack": 4, "defense": 4, "speed": 4, "special": 4})  # middle bits 2,2,2,2 -> 170 -> C? (18)
    give(a, b"UNOWN", 10)
    g = gifts(a)[0]
    letter = a.E.Unown.letterFromDVs(a.lua({"attack": 4, "defense": 4, "speed": 4, "special": 4}))
    assert g["form"] == chr(96 + letter) and g["gender"] == "genderless"


def test_eggs_are_never_gifts_until_they_hatch():
    a, w = setup()
    a.E.giveEgg(b"TEST_TOGEPI", 5)
    assert gifts(a) == []
    egg_slot = len(a.E.game.save.party)
    a.E.Breeding.hatch(a.E.data, a.E.game.save, egg_slot, b"TOGE")
    g = gifts(a)
    assert len(g) == 1 and g[0]["source"] == "egg" and g[0]["dex"] == 175 and g[0]["nick"] == "TOGE"
    assert g[0]["friendship"] == 120


def test_an_in_game_trade_takes_the_member_sent_and_gives_the_one_received():
    a, w = setup()
    seed(a, [("TEST_RATTATA", 7, UID1)])
    assert plain(a.eng.partyUids()[0]) == [UID1]
    row = a.lua({"get": "TEST_MAGIKARP", "nickname": "TESTFISH"})
    a.E.NpcTrade.perform(a.E.data, a.E.game.save, row, 1)
    ops = plain(a.eng.ops())
    assert [(o["kind"], o["uid"], o["reason"]) for o in ops] == [("take", UID1, "trade")]
    assert UID1 in plain(a.eng.excludedUids())
    g = gifts(a)
    assert len(g) == 1 and g[0]["source"] == "trade" and g[0]["dex"] == 129 and g[0]["ot"] == "TEST OT"


def test_the_day_care_watch_reports_deposits_and_withdrawals_with_their_experience():
    a, w = setup()
    seed(a, [("TEST_RATTATA", 7, UID1), ("TEST_CHIKORITA", 5, UID2)])
    assert a.eng.daycareStep(None) is None, "nobody boarded yet"
    a.E.Breeding.deposit(a.E.data, a.E.game.save, b"lady", 1)
    ev = plain(a.eng.daycareStep(None))
    assert ev["kind"] == "deposit" and ev["uid"] == UID1 and ev["store"] == {"boarded": [{"uid": UID1, "keeper": "lady"}]}
    assert plain(a.eng.daycareUids()) == [UID1] and UID1 in plain(a.eng.excludedUids())
    # the Day-Care gives experience while it holds it; the withdrawal rebuilds the record
    held = a.E.game.save.dayCare.lady.mon
    held.experience = held.experience + 40
    ok, rebuilt, price = a.E.Breeding.withdraw(a.E.data, a.E.game.save, b"lady")
    assert ok is True
    ev = plain(a.eng.daycareStep(None))
    assert ev["kind"] == "withdraw" and ev["uid"] == UID1 and ev["steps"] == 40 and ev["store"] is False
    assert a.eng.uidOf(rebuilt) == UID1.encode(), "the uid follows the rebuilt record"
    assert gifts(a) == [], "a member back from the Day-Care is no gift"
    assert [(o["kind"], o.get("steps")) for o in plain(a.eng.ops())] == [("deposit", None), ("withdraw", 40)]


def test_party_sync_keeps_known_members_and_builds_new_ones_to_match():
    a, w = setup()
    known = seed(a, [("TEST_RATTATA", 7, UID1)])[0]
    res = plain(a.eng.applyParty(a.lua([
        entry(UID1, "TEST_RATTATA", 9, hp=10, maxhp=40, status="PAR", nick="RATTY", item="oran_berry", shiny=False,
              friendship=200),
        entry(UID2, "TEST_RATTATA", 12, gender="female", shiny=True),
        entry(UID3, "UNOWN", 20, form="m", shiny=False),
    ])))
    assert res["updated"] == 1 and res["created"] == 2 and res["size"] == 3
    p = party(a)
    assert a.same(p[0], known) and p[0].level == 9 and p[0].status == b"paralyze" and p[0].nickname == b"RATTY"
    assert p[0].item == b"BERRY" and p[0].happiness == 200 and p[0].experience == 729
    assert p[0].hp == round(10 * p[0].maxHp / 40) or p[0].hp == max(1, int(10 * p[0].maxHp / 40 + 0.5))
    assert p[1].shiny is True and a.E.Mon.vanillaShiny(p[1].dvs) is True
    assert a.E.Gender.of(0x7F, p[1].dvs) == b"female" and p[1].ot == b"TESTER"
    assert a.E.Unown.letterFromDVs(p[2].dvs) == ord("m") - 96 and p[2].unownLetter == ord("m") - 96
    assert plain(a.eng.partyUids()[0]) == [UID1, UID2, UID3]


def test_eggs_stay_after_the_members_and_wait_in_a_box_when_there_is_no_room():
    a, w = setup()
    egg = a.E.giveEgg(b"TEST_TOGEPI", 5)
    six = [entry(f"0000000{i}-0000-4000-8000-000000000000", "TEST_RATTATA", 3 + i) for i in range(1, 7)]
    res = plain(a.eng.applyParty(a.lua(six)))
    assert res["size"] == 6 and res["parked"] == 1
    box = a.E.game.save.boxes[1]
    assert a.same(box[1], egg)
    res = plain(a.eng.applyParty(a.lua(six[:5])))
    assert res["back"] == 1 and res["size"] == 6 and a.same(party(a)[5], egg)
    assert len(a.E.game.save.boxes[1]) == 0
    assert gifts(a) == []


def test_a_gift_cobblemon_names_is_matched_and_one_it_hasnt_shown_stays():
    a, w = setup()
    give(a, b"TEST_CHIKORITA", 5)
    g = gifts(a)[0]
    give(a, b"TEST_RATTATA", 3)
    res = plain(a.eng.applyParty(a.lua([dict(entry(UID1, "TEST_CHIKORITA", 5), give=g["give"])])))
    assert res["matched"] == 1 and res["kept"] == 1 and res["size"] == 2
    assert [x["source"] for x in gifts(a)] == ["gift"], "the second still pending"


def test_party_safety():
    a, w = setup()
    assert a.eng.partySafe() is True
    a.E.pushScreen(b"Gen2PartyMenu")
    assert a.eng.partySafe() is False
    a.E.game.stack.pop(a.E.game.stack)
    w.battleActive = True
    assert a.eng.partySafe() is False


def test_the_pokedex_heals_and_nicknames():
    a, w = setup()
    res = plain(a.eng.applyDex(a.lua([152, 19, 999]), a.lua([152])))
    assert res == {"seen": 2, "owned": 1, "unknown": 1}
    dex = a.E.game.save.pokedex
    assert dex.seen[b"TEST_CHIKORITA"] is True and dex.caught[b"TEST_CHIKORITA"] is True
    a.eng.queueHeal(b"nurse")
    assert a.eng.takeHeals() == 1 and a.eng.takeHeals() == 0
    seed(a, [("TEST_RATTATA", 7, UID1)])
    a.eng.applyParty(a.lua([entry(UID1, "TEST_RATTATA", 7, nick="RAT")]))
    nicks, _ = a.eng.partyNicks()
    assert plain(nicks) == [{"uid": UID1, "nick": "RAT"}]
    a.eng.queueRename(UID1.encode(), b"RATTO")
    assert plain(a.eng.takeRenames()) == [{"uid": UID1, "nick": "RATTO"}]


def test_main_gives_the_starter_with_its_details():
    rig = Rig(family="gen2")
    rig.world("TEST_TOWN", 5, 6, "down")
    E = rig.E
    peer = rig.connect()
    peer.hello()
    peer.messages()
    rig.step()
    E.givePoke(b"TEST_CHIKORITA", 5)
    got = []
    for _ in range(6):
        rig.step()
        got.extend(peer.messages())
    gives = [m["data"] for m in got if m["type"] == "party.give"]
    assert len(gives) == 1
    give_ = gives[0]
    assert give_["source"] == "starter" and give_["dex"] == 152 and give_["level"] == 5 and give_["moves"] == [33]
    assert set(give_["dvs"]) == {"atk", "def", "spd", "spc"} and give_["gender"] in ("male", "female")
    assert give_["shiny"] is False and give_["friendship"] == 70
