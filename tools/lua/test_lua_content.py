"""M8 (wave 2) gifts, trades, the Name Rater, the daycare, native catches and the Hall of Fame
(act/kb_party.lua; lib/kb_engine.lua), wired by main.lua, on the simulated engine with
fake_content.lua: an in-game trade (party.take for the member sent, party.give source trade for the
one received, the Gen 1 side consistent, a stale sync never bringing the traded member back, a member
without a uid); the Name Rater (party.rename, a clear, a foreign OT refused); the daycare
(daycare.deposit, the boarded member kept out of syncs, daycare.withdraw with the steps walked, the
member kept until a sync names it, mod.save across CONTINUE); native catches in the Safari Zone
(party.give source native_catch once the battle is over, with the nickname; a box catch; the old
man's demo; offline); the Hall of Fame's heal and save; re-sending after a snapshot and confirming
by sync. Every message the peer receives is schema-checked (rig.Peer, strict). All data is made up
(contentsim.py, battlesim.py)."""

import pytest

from battlesim import MOVE_NUMBERS
from contentsim import OBJECTS, ContentGame

pytestmark = pytest.mark.lua

U1 = "0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b"
U2 = "6c1e2d3f-4a5b-4c6d-8e7f-a0b1c2d3e4f5"
U3 = "11111111-2222-4333-8444-555555555555"
N = MOVE_NUMBERS


def msgs(g, t):
    return [m["data"] for m in g.received(t)]


def gives(g, source):
    return [d for d in msgs(g, "party.give") if d["source"] == source]


def tap(g, btn="a"):
    """A button press on the fake input (no client: nothing answers the hidden game's UIs)."""
    g.b.eval("function(i, b) i:sourcePress(b, 'test') i:sourceRelease(b, 'test') end")(g.E.game.input, btn.encode())
    g.run()


def errors(g):
    return [(m["data"]["code"], m.get("re")) for m in g.received("error")]


def entry(uid, dex, level, moves, hp=30, maxhp=30, **kw):
    e = {"uid": uid, "dex": dex, "level": level, "moves": moves, "hp": hp, "maxhp": maxhp}
    e.update(kw)
    return e


def sync(g, party, steps=3):
    g.send("party.sync", {"party": party, "box": {"count": 0}})
    g.run(steps)


def species(g):
    return [m.species.decode() for m in g.party()]


def play(g, choices=(), names=(), until=None, limit=900):
    """Plays the hidden game's UIs like a player: A at each text's arrow, the next of `choices` at
    each YES/NO (0 YES, 1 NO; YES once they run out), the next of `names` (glyph lists) at each
    naming screen, A once on each mirror screen (the party menu, the trade animation, the Hall of
    Fame), until no UI is open for a few steps (or until(g) holds)."""
    choices, names = list(choices), list(names)
    answered, quiet = set(), 0
    for _ in range(limit):
        g.run()
        if until is not None and until(g):
            return
        uis = g.open_uis()
        if not uis:
            quiet += 1
            if quiet >= 10:
                return
            continue
        quiet = 0
        top = max(uis)
        ui = uis[top]
        key = (top, ui.get("page"), ui.get("line"), ui.get("done"))
        if ui["kind"] == "text" and ui.get("waiting") and key not in answered:
            answered.add(key)
            g.send("ui.advance", {"ui": top})
        elif ui["kind"] == "choice" and top not in answered:
            answered.add(top)
            if (choices.pop(0) if choices else 0) == 1:
                g.send("ui.back", {"ui": top})
            else:
                g.send("ui.pick", {"ui": top, "index": 0})
        elif ui["kind"] == "naming" and top not in answered and names:
            answered.add(top)
            g.send("ui.name", {"ui": top, "glyphs": list(names.pop(0))})
        elif ui["kind"] == "mirror" and top not in answered:
            answered.add(top)
            g.press("a")
    raise AssertionError("the UIs never settled")


# ------------------------------------------------------------ in-game trades


def trade_game():
    g = ContentGame(at="trader")
    g.give("STARTMON_A", 12)          # what the trader wants
    g.give("STARTMON_C", 9)
    sync(g, [entry(U1, 1, 12, [N["TACKLE_T"], N["GROWL_T"], N["LEECH_T"]]),
             entry(U2, 7, 9, [N["TACKLE_T"], N["TAIL_T"], N["BUBBLE_T"]])])
    assert g.status()["partyKnown"] == 2
    return g


def test_an_in_game_trade_takes_the_member_sent_and_gives_the_one_received():
    g = trade_game()
    g.talk("trader")
    play(g)
    assert msgs(g, "party.take") == [{"uid": U1, "reason": "trade"}]
    traded = gives(g, "trade")
    assert len(traded) == 1
    give = traded[0]
    assert give["dex"] == 37 and give["level"] == 12, "the sent one's level"
    assert give["nick"] == "FOXY" and give["ot"] == "TRAINER"
    assert give["moves"] == [N["EMBER_T"], N["TAIL_T"]]
    types = [m["type"] for m in g.log if m["type"] == "party.take"
             or (m["type"] == "party.give" and m["data"]["source"] == "trade")]
    assert types == ["party.take", "party.give"], "the take first: the party never overflows"
    assert species(g) == ["STARTMON_C", "FOXMON"], "the Gen 1 side traded too"
    assert g.flags().get("EVENT_TRADED_TEST") is True
    st = g.status()
    assert st["trades"] == 1 and st["partyTakes"] == 1 and st["opsPending"] == 1
    # a stale sync (from before the take) still lists the member sent: it never comes back
    sync(g, [entry(U1, 1, 12, [N["TACKLE_T"]]), entry(U2, 7, 9, [N["TACKLE_T"]])])
    assert species(g) == ["STARTMON_C", "FOXMON"], "the traded member stays gone; the received one is kept"
    assert g.status()["partyExcluded"] == 1
    # Cobblemon's own: the take done, the trade gift named
    sync(g, [entry(U2, 7, 9, [N["TACKLE_T"]]), entry(U3, 37, 12, [N["EMBER_T"]], give=give["give"], nick="FOXY")])
    assert species(g) == ["STARTMON_C", "FOXMON"]
    st = g.status()
    assert st["opsPending"] == 0 and st["partyOpsConfirmed"] == 1 and st["giftsPending"] == 0
    assert g.party()[1].traded is True and g.party()[1].ot == b"TRAINER", "the engine's own traded member kept"
    assert errors(g) == []


def test_a_declined_trade_changes_nothing():
    g = trade_game()
    g.talk("trader")
    play(g, choices=[1])
    assert msgs(g, "party.take") == [] and gives(g, "trade") == []
    assert species(g) == ["STARTMON_A", "STARTMON_C"]


def test_a_trade_of_a_member_without_a_uid_still_gives():
    g = ContentGame(at="trader")
    g.give("STARTMON_A", 12)
    g.talk("trader")
    play(g)
    assert msgs(g, "party.take") == []
    assert [d["source"] for d in msgs(g, "party.give")] == ["starter", "trade"], "the first give, then the trade's"
    assert g.status()["tradeNoUid"] == 1


def test_a_pending_take_goes_out_again_after_a_snapshot_until_confirmed():
    g = trade_game()
    g.talk("trader")
    play(g)
    g.send("resync")
    g.run(2)
    assert msgs(g, "party.take") == [{"uid": U1, "reason": "trade"}] * 2
    sync(g, [entry(U2, 7, 9, [N["TACKLE_T"]])])
    g.send("resync")
    g.run(2)
    assert len(msgs(g, "party.take")) == 2, "confirmed: not again"


# ------------------------------------------------------------ the Name Rater


def rater_game(traded=False):
    g = ContentGame(at="rater")
    mon = g.give("STARTMON_C", 9)
    if traded:
        mon.traded = True
    sync(g, [entry(U1, 7, 9, [N["TACKLE_T"], N["TAIL_T"], N["BUBBLE_T"]])])
    return g


def test_the_name_rater_renames_a_member_through_party_rename():
    g = rater_game()
    g.talk("rater")
    play(g, names=[["B", "A", "D"]])
    assert msgs(g, "party.rename") == [{"uid": U1, "nick": "BAD"}]
    assert g.party()[0].nickname == b"BAD"
    assert g.status()["partyRenames"] == 1
    # Cobblemon's sync with the new nickname changes nothing more
    sync(g, [entry(U1, 7, 9, [N["TACKLE_T"]], nick="BAD")])
    g.run(3)
    assert len(msgs(g, "party.rename")) == 1


def test_the_name_rater_can_clear_a_nickname():
    g = rater_game()
    sync(g, [entry(U1, 7, 9, [N["TACKLE_T"]], nick="OLD")])   # Cobblemon's nickname: not a rename
    assert g.party()[0].nickname == b"OLD"
    g.E.data.pokemon.STARTMON_C.name = b"CAB"
    g.talk("rater")
    play(g, names=[["C", "A", "B"]])            # the species' own name clears it
    assert msgs(g, "party.rename") == [{"uid": U1, "nick": ""}]
    assert g.party()[0].nickname is None


def test_the_name_rater_refuses_a_traded_member():
    g = rater_game(traded=True)
    g.talk("rater")
    play(g)
    assert msgs(g, "party.rename") == []


def test_a_sync_changing_a_nickname_is_not_a_rename():
    g = rater_game()
    sync(g, [entry(U1, 7, 9, [N["TACKLE_T"]], nick="COBBLE")])
    g.run(3)
    assert msgs(g, "party.rename") == []


def test_a_rename_made_without_a_client_goes_out_on_connect():
    g = ContentGame(at="rater")
    g.give("STARTMON_C", 9)
    sync(g, [entry(U1, 7, 9, [N["TACKLE_T"]])])
    g.peer.close()
    g.run(3)
    g.party()[0].nickname = b"QUIET"
    g.run(3)
    g.log = []
    g.connect()
    g.run(2)
    assert msgs(g, "party.rename") == [{"uid": U1, "nick": "QUIET"}]


# ------------------------------------------------------------ the daycare


def daycare_game():
    g = ContentGame(at="daycare")
    g.give("STARTMON_C", 10)
    g.give("BIRDMON", 10)
    sync(g, [entry(U1, 7, 10, [N["TACKLE_T"]]), entry(U2, 16, 10, [N["GUST_T"]])])
    return g


def walk(g, steps):
    for i in range(steps):
        d = "down" if i % 2 == 0 else "up"
        g.send("move.step", {"dir": d, "n": 100 + i})
        g.run(24)


def test_a_daycare_deposit_and_withdrawal():
    g = daycare_game()
    g.talk("daycare")
    play(g)
    assert msgs(g, "daycare.deposit") == [{"uid": U1}]
    assert species(g) == ["BIRDMON"] and g.E.save.daycare.mon.species == b"STARTMON_C"
    assert g.b.mod._save.daycare.uid == U1.encode(), "kept in mod.save"
    # a stale sync still listing it never brings it back
    sync(g, [entry(U1, 7, 10, [N["TACKLE_T"]]), entry(U2, 16, 10, [N["GUST_T"]])])
    assert species(g) == ["BIRDMON"]
    sync(g, [entry(U2, 16, 10, [N["GUST_T"]])])
    walk(g, 4)
    assert g.E.save.daycare.steps == 4
    money = g.E.save.money
    g.talk("daycare")
    play(g)
    assert msgs(g, "daycare.withdraw") == [{"uid": U1, "steps": 4}]
    assert g.E.save.money == money - 100
    assert species(g) == ["BIRDMON", "STARTMON_C"]
    assert g.b.mod._save.daycare is None
    # until Cobblemon returns it, a sync without it keeps it
    sync(g, [entry(U2, 16, 10, [N["GUST_T"]])])
    assert species(g) == ["BIRDMON", "STARTMON_C"]
    sync(g, [entry(U2, 16, 10, [N["GUST_T"]]), entry(U1, 7, 10, [N["TACKLE_T"]])])
    st = g.status()
    assert st["opsPending"] == 0 and st["partyDeposits"] == 1 and st["partyWithdraws"] == 1
    assert errors(g) == []


def test_the_daycare_survives_a_continue():
    g = daycare_game()
    g.talk("daycare")
    play(g)
    # CONTINUE: the save comes back as new tables (the boarded member included), save.loaded fires
    g.b.eval("""function(E)
      local function copy(v, seen)
        if type(v) ~= 'table' then return v end
        local out = {}
        for k, x in pairs(v) do out[k] = copy(x) end
        return out
      end
      local s = copy(E.save)
      E.save = s
      E.game.save = s
    end""")(g.E)
    g.b.emit("save.loaded")
    g.run(3)
    assert g.status()["daycareHeld"] is True, "the boarded uid adopted from mod.save"
    walk(g, 2)
    g.talk("daycare")
    play(g)
    assert msgs(g, "daycare.withdraw") == [{"uid": U1, "steps": 2}]


def test_a_member_without_a_uid_boards_silently():
    g = ContentGame(at="daycare")
    g.give("STARTMON_C", 10)
    g.give("BIRDMON", 10)
    g.talk("daycare")
    play(g)
    assert msgs(g, "daycare.deposit") == []
    assert g.status()["depositNoUid"] == 1


# ------------------------------------------------------------ native catches (the Safari Zone)


def test_a_safari_catch_crosses_over_once_the_battle_is_over():
    g = ContentGame(at="static")
    g.give("STARTMON_C", 9)
    sync(g, [entry(U1, 7, 9, [N["TACKLE_T"]])])
    g.E.catchBattle(b"FOXMON", 20, g.b.to_lua({"safari": True}))
    play(g, until=lambda g: any(u["kind"] == "naming" for u in g.open_uis().values()))
    assert gives(g, "native_catch") == [], "not while the battle (and its nickname prompt) is on"
    assert [m["kind"] for m in msgs(g, "battle.native")] == ["safari"]
    play(g, names=[["D", "A", "B"]])
    caught = gives(g, "native_catch")
    assert len(caught) == 1
    give = caught[0]
    assert give["dex"] == 37 and give["level"] == 20
    assert give["nick"] == "DAB"
    assert give["moves"] == [N["EMBER_T"], N["TAIL_T"]] and set(give["dvs"]) == {"atk", "def", "spd", "spc"}
    assert msgs(g, "battle.native")[-1] == {"kind": "safari", "phase": "end"}
    assert species(g) == ["STARTMON_C", "FOXMON"]
    # Cobblemon's sync names the gift: the engine's own member is kept, no duplicate
    sync(g, [entry(U1, 7, 9, [N["TACKLE_T"]]), entry(U2, 37, 20, [N["EMBER_T"]], give=give["give"], nick="DAB")])
    assert species(g) == ["STARTMON_C", "FOXMON"]
    st = g.status()
    assert st["giftsPending"] == 0 and st["catchesReleased"] == 1 and st["partyCatches"] == 1


def test_a_catch_into_a_full_party_goes_to_the_box_and_out_of_it():
    g = ContentGame(at="static")
    for _ in range(6):
        g.give("STARTMON_C", 9)
    g.E.catchBattle(b"FOXMON", 20, g.b.to_lua({"safari": True}))
    play(g, choices=[1])
    assert len(gives(g, "native_catch")) == 1
    box = g.E.save.boxes[g.E.save.currentBox]
    assert len(box) == 0, "Cobblemon's PC keeps it"


def test_the_old_mans_demo_keeps_nothing():
    g = ContentGame(at="static")
    g.give("STARTMON_C", 9)
    g.E.catchBattle(b"FOXMON", 5, g.b.to_lua({"demo": True}))
    play(g)
    assert gives(g, "native_catch") == []
    assert g.status()["catchesCaught"] == 0


def test_a_catch_made_without_a_client_goes_out_on_connect():
    g = ContentGame(at="static", connect=False)
    g.give("STARTMON_C", 9)
    g.E.catchBattle(b"FOXMON", 20, g.b.to_lua({"safari": True}))
    tap(g)                                       # the catch
    for _ in range(40):                          # the nickname question: B (NO) until the battle is over
        if len(g.E.stack.states) == 1:
            break
        tap(g, "b")
        g.run(3)
    assert len(g.E.stack.states) == 1
    g.connect()
    g.run(3)
    sources = [d["source"] for d in msgs(g, "party.give")]
    assert sources == ["starter", "native_catch"]


# ------------------------------------------------------------ the Hall of Fame


def test_the_hall_of_fame_heals_and_saves():
    g = ContentGame(at="static")
    g.give("STARTMON_C", 9)
    heals = len(msgs(g, "party.heal"))
    g.script([["record_hall_of_fame"]])
    play(g)
    assert len(msgs(g, "party.heal")) == heals + 1
    assert [d["reason"] for d in msgs(g, "save.begin")] == ["menu"]
    screens = [d.get("screen") for d in msgs(g, "ui.open") if d["kind"] == "mirror"]
    assert "HallOfFame" in screens and "Credits" in screens


# ------------------------------------------------------------ gifts through the M5 path


def test_a_gift_given_by_a_talk_script_goes_out_as_a_gift():
    g = ContentGame(at="trader")
    g.E.talkRows[OBJECTS["trader"].encode()] = g.b.to_lua([["give_pokemon", "FOXMON", 25, False, True]])
    g.E.save.flags.EVENT_GOT_STARTER = True
    g.talk("trader")
    play(g, choices=[1])
    assert [(d["source"], d["dex"], d["level"]) for d in msgs(g, "party.give")] == [("gift", 37, 25)]
