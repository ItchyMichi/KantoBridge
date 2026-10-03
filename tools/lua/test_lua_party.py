"""act/kb_party.lua and kb_engine's gift and party seams (M5), wired by main.lua, on the simulated
engine with the fake script runner (fake_script.lua): give_pokemon -> party.give (after the native
nickname prompt, which reaches the client as text + YES/NO + naming), no duplicate once party.sync
arrives, party.sync applied at any time (rebuild, uids, order, evolution, moves by Gen 1 number,
HP scaling, status, nickname, non-Gen-1 species and moves counted), the timing (a sync during a
give waits; a sync before Cobblemon has the gift keeps it), re-sends after a reconnect, giving up,
box gifts, heals, mod.save uids, and the snapshot's uids. Every message the peer receives is
schema-checked (rig.Peer, strict). All data is made up (battlesim.py)."""

import pytest

from battlesim import BattleGame, MOVE_NUMBERS

pytestmark = pytest.mark.lua

U1 = "0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b"
U2 = "6c1e2d3f-4a5b-4c6d-8e7f-a0b1c2d3e4f5"
U3 = "11111111-2222-4333-8444-555555555555"
N = MOVE_NUMBERS


def gives(g):
    return [m["data"] for m in g.received("party.give")]


def errors(g):
    return [(m["data"]["code"], m.get("re")) for m in g.received("error")]


def sync(g, party, box=0):
    return g.send("party.sync", {"party": party, "box": {"count": box}})


def entry(uid, dex, level, moves, hp=20, maxhp=20, **kw):
    e = {"uid": uid, "dex": dex, "level": level, "moves": moves, "hp": hp, "maxhp": maxhp}
    e.update(kw)
    return e


def species(g):
    return [m.species.decode() for m in g.party()]


def same(g, a, b):
    """Lua identity (lupa wraps one table in different Python objects)."""
    return bool(g.b.eval("function(a, b) return rawequal(a, b) end")(a, b))


def starter(g, nickname=None):
    """The lab's give_pokemon with its nickname prompt answered through the client."""
    g.script([["give_pokemon", "STARTMON_C", 5]])
    if nickname is None:
        g.read_texts(choice=1)           # NO: no nickname
    else:
        g.read_texts(choice=0, until=lambda g: any(u["kind"] == "naming" for u in g.open_uis().values()))
        ui = [k for k, u in g.open_uis().items() if u["kind"] == "naming"][0]
        g.send("ui.name", {"ui": ui, "glyphs": list(nickname)})
        g.read_texts()
    g.run(2)


# ------------------------------------------------------------ gifts


def test_a_gift_goes_out_after_the_nickname_prompt():
    g = BattleGame()
    g.script([["give_pokemon", "STARTMON_C", 5]])
    g.until(lambda m: m["type"] == "ui.open" and m["data"]["kind"] == "choice")
    kinds = [m["data"]["kind"] for m in g.received("ui.open")]
    assert kinds[-2:] == ["text", "choice"] and g.open_uis()[max(g.open_uis())]["kind"] == "choice"
    assert not gives(g), "not before the prompt is answered"
    assert len(g.party()) == 1, "the engine's own copy is in the party meanwhile"
    g.read_texts(choice=1)
    g.run(2)
    (give,) = gives(g)
    mon = g.party()[0]
    assert give == {
        "give": give["give"], "dex": 7, "level": 5, "source": "starter",
        "moves": [N["TACKLE_T"], N["TAIL_T"]],
        "dvs": {"atk": mon.dvs.attack, "def": mon.dvs[b"defense"], "spd": mon.dvs.speed, "spc": mon.dvs.special},
        "ot": "TESTER",
    }
    assert give["give"].startswith("g-")
    assert g.status()["partyGives"] == 1


def test_a_nickname_from_the_naming_screen_rides_the_give():
    g = BattleGame()
    starter(g, nickname="ABBA")
    (give,) = gives(g)
    assert give["nick"] == "ABBA" and g.party()[0].nickname == b"ABBA"
    naming = [m["data"] for m in g.received("ui.open") if m["data"]["kind"] == "naming"][0]
    assert naming["max"] == 10 and naming["mon"] == 7


def test_later_gifts_are_source_gift_and_prizes_prize():
    g = BattleGame()
    starter(g)
    g.E.save.flags.EVENT_GOT_STARTER = True
    g.script([["give_pokemon", "BIRDMON", 10, True]])
    g.run(2)
    assert [x["source"] for x in gives(g)] == ["starter", "gift"]


def test_party_sync_confirms_the_gift_without_a_duplicate():
    g = BattleGame()
    starter(g)
    (give,) = gives(g)
    given = g.party()[0]
    sync(g, [entry(U1, 7, 5, give["moves"], hp=19, maxhp=21)])
    g.run(2)
    party = g.party()
    assert len(party) == 1 and same(g, party[0], given), "the same member, now Cobblemon's"
    st = g.status()
    assert st["partyMatched"] == 1 and st["giftsPending"] == 0 and st["partyKnown"] == 1
    assert g.b.mod._save.partyUids[1] == U1.encode()
    seq = g.send("resync")
    g.run()
    snap = [m for m in g.received("snapshot") if m.get("re") == seq][0]["data"]
    assert snap["party"][0]["uid"] == U1 and snap["party"][0]["dex"] == 7


def test_a_sync_before_cobblemon_has_the_gift_keeps_it():
    g = BattleGame()
    starter(g)
    (give,) = gives(g)
    sync(g, [])                          # Cobblemon's party, not yet with the gift
    g.run(2)
    assert species(g) == ["STARTMON_C"], "kept until Cobblemon shows it"
    sync(g, [entry(U1, 7, 5, give["moves"])])
    g.run(2)
    assert len(g.party()) == 1 and g.status()["giftsPending"] == 0


def test_a_sync_during_the_give_waits_for_it():
    g = BattleGame()
    g.script([["give_pokemon", "STARTMON_C", 5]])
    g.run(3)                              # the nickname prompt is up
    sync(g, [entry(U2, 16, 9, [N["GUST_T"]])])
    g.run(3)
    assert species(g) == ["STARTMON_C"], "not applied while give_pokemon runs"
    assert g.status()["partyDeferred"] == 1
    g.read_texts(choice=1)
    g.run(2)
    assert species(g) == ["BIRDMON", "STARTMON_C"], "applied after; the gift is kept behind it"


def test_a_give_cut_short_by_the_title_does_not_stall_syncs():
    g = BattleGame()
    g.script([["give_pokemon", "STARTMON_C", 5]])
    g.until(lambda m: m["type"] == "ui.open" and m["data"]["kind"] == "choice")
    g.E.leaveWorld()                      # back to the title mid-prompt: the script never resumes
    g.run(2)
    g.E.stack.states[1] = g.E.ow          # a new world (the same tables stand in for it)
    g.E.save = g.b.to_lua({"player": {"name": "TESTER", "id": 1}, "flags": {}, "party": [],
                           "pokedex": {"seen": {}, "owned": {}}, "boxes": [[]], "currentBox": 1,
                           "inventory": {}, "money": 0, "defeatedTrainers": {}})
    g.E.game.save = g.E.save
    sync(g, [entry(U2, 16, 9, [N["GUST_T"]])])
    g.run(3)
    assert species(g) == ["BIRDMON"]


def test_gifts_go_out_again_after_a_reconnect_until_a_sync_shows_them():
    g = BattleGame()
    starter(g)
    (give,) = gives(g)
    g.send("resync")
    g.run(2)
    assert [x["give"] for x in gives(g)] == [give["give"]] * 2
    g.peer.close()
    g.rig.step(3)
    g.connect()
    g.run(2)
    assert [x["give"] for x in gives(g)] == [give["give"]] * 3
    sync(g, [entry(U1, 7, 5, give["moves"])])
    g.run(2)
    g.send("resync")
    g.run(2)
    assert len(gives(g)) == 3, "confirmed: no more re-sends"


def test_a_gift_is_queued_without_a_client():
    g = BattleGame(connect=False)
    g.script([["give_pokemon", "STARTMON_C", 5, True]])
    g.rig.step(3)
    g.connect()
    g.run(2)
    (give,) = gives(g)
    assert give["source"] == "starter"


def test_a_gift_five_syncs_and_two_minutes_never_show_is_given_up():
    g = BattleGame()
    starter(g)
    for _ in range(6):
        sync(g, [])          # stale syncs (Minecraft re-sends its latest after each snapshot)
        g.run(2)
    assert species(g) == ["STARTMON_C"], "a burst of syncs right after the give doesn't drop it"
    g.rig.advance(121)
    sync(g, [])
    g.run(2)
    assert g.party() == [] and g.status()["partyGivesGivenUp"] == 1
    assert any("party.gift_unconfirmed" in line for line in g.rig.logs())


def test_a_box_gift_leaves_the_gen1_box():
    g = BattleGame()
    for i in range(6):
        g.script([["give_pokemon", "BIRDMON", 5 + i, True]])
        g.run(1)
    g.script([["give_pokemon", "STARTMON_A", 30, True]])
    g.run(2)
    assert len(g.party()) == 6 and len(g.E.save.boxes[1]) == 0, "taken out of the Gen 1 box"
    assert gives(g)[-1]["dex"] == 1 and gives(g)[-1]["level"] == 30
    sync(g, [entry("aaaaaaaa-bbbb-4ccc-8ddd-%012d" % i, 16, 5 + i, [N["GUST_T"]]) for i in range(6)], box=1)
    g.run(2)
    assert g.status()["giftsPending"] == 0 and len(g.party()) == 6


# ------------------------------------------------------------ exact gift matching (party.sync's give, M6)


def two_gifts(g):
    """Two gifts of the same species and level: only their give ids tell them apart."""
    g.script([["give_pokemon", "BIRDMON", 9, True]])
    g.run(2)
    g.script([["give_pokemon", "BIRDMON", 9, True]])
    g.run(2)
    first, second = gives(g)
    return first, second


def test_a_sync_naming_the_give_confirms_that_very_gift():
    g = BattleGame()
    first, second = two_gifts(g)
    a, b = g.party()
    sync(g, [entry(U1, 16, 9, [N["GUST_T"]], give=second["give"])])
    g.run(2)
    party = g.party()
    assert len(party) == 2 and same(g, party[0], b), "the second gift is the synced one, by its id"
    assert same(g, party[1], a), "the first is still pending, kept behind it"
    st = g.status()
    assert st["partyGivesExact"] == 1 and st["giftsPending"] == 1 and st["partyKnown"] == 1


def test_without_give_the_sync_falls_back_to_species_and_level():
    g = BattleGame()
    first, second = two_gifts(g)
    a, b = g.party()
    sync(g, [entry(U1, 16, 9, [N["GUST_T"]])])
    g.run(2)
    assert same(g, g.party()[0], a), "the first pending gift of that species and level"
    assert g.status()["partyGivesExact"] == 0


def test_a_give_naming_no_pending_gift_is_never_matched_by_species():
    g = BattleGame()
    starter(g)
    (give,) = gives(g)
    given = g.party()[0]
    sync(g, [entry(U1, 7, 5, give["moves"], give="g-older-1")])
    g.run(2)
    party = g.party()
    assert len(party) == 2 and not same(g, party[0], given), "a Pokemon of an older gift: created"
    assert same(g, party[1], given) and g.status()["giftsPending"] == 1


def test_a_box_gift_named_by_give_is_confirmed():
    g = BattleGame()
    for i in range(6):
        g.script([["give_pokemon", "BIRDMON", 5 + i, True]])
        g.run(1)
    g.script([["give_pokemon", "STARTMON_A", 30, True]])
    g.run(2)
    box_gift = gives(g)[-1]
    members = [entry("aaaaaaaa-bbbb-4ccc-8ddd-%012d" % i, 16, 5 + i, [N["GUST_T"]], give=gives(g)[i]["give"])
               for i in range(5)]
    members.append(entry(U3, 1, 30, [N["TACKLE_T"]], give=box_gift["give"]))
    sync(g, members, box=1)
    g.run(2)
    st = g.status()
    assert st["giftsPending"] == 1, "the sixth party gift is not in this sync"
    assert st["partyGivesExact"] == 6 and species(g)[5] == "STARTMON_A"


def test_a_bad_give_field_is_refused():
    g = BattleGame()
    seq = sync(g, [entry(U1, 16, 9, [N["GUST_T"]], give="not ok!")])
    g.run(2)
    assert ("bad_message", seq) in errors(g)


# ------------------------------------------------------------ party.sync


def test_sync_rebuilds_the_party_in_cobblemons_order():
    g = BattleGame()
    starter(g)
    (give,) = gives(g)
    sync(g, [entry(U1, 7, 5, give["moves"]), entry(U2, 16, 9, [N["GUST_T"], N["SAND_T"]], hp=10, maxhp=40)])
    g.run(2)
    first, second = g.party()
    assert species(g) == ["STARTMON_C", "BIRDMON"]
    assert second.level == 9 and second.ot == b"TESTER"
    assert [mv.id for mv in second.moves.values()] == [b"GUST_T", b"SAND_T"]
    assert second.hp == round(10 * second.stats.hp / 40), "HP scaled to the Gen 1 maximum"
    # reorder, evolve the first (new species, level), poison and nickname the second
    sync(g, [entry(U2, 16, 9, [N["GUST_T"], N["SAND_T"]], hp=40, maxhp=40, status="psn", nick="BIRDY"),
             entry(U1, 1, 16, [N["TACKLE_T"], N["VINE_T"], N["LEECH_T"]], hp=0, maxhp=50)])
    g.run(2)
    a, b = g.party()
    assert same(g, a, second) and same(g, b, first), "kept by uid, in place, reordered"
    assert a.status == b"PSN" and a.nickname == b"BIRDY" and a.hp == a.stats.hp
    assert b.species == b"STARTMON_A" and b.level == 16 and b.hp == 0
    assert b.exp == 16 ** 3 and [mv.id for mv in b.moves.values()] == [b"TACKLE_T", b"VINE_T", b"LEECH_T"]
    tackle = b.moves[1]
    sync(g, [entry(U1, 1, 16, [N["TACKLE_T"]], hp=50, maxhp=50)])
    g.run(2)
    assert len(g.party()) == 1 and same(g, g.party()[0], b)
    assert same(g, g.party()[0].moves[1], tackle), "PP kept for a known move"


def test_sync_leaves_out_species_and_moves_beyond_gen1_with_a_counted_warning():
    g = BattleGame()
    sync(g, [entry(U1, 251, 10, [N["TACKLE_T"]]),
             entry(U2, 16, 9, [N["GUST_T"], 300, 166])])
    g.run(2)
    assert species(g) == ["BIRDMON"]
    assert [mv.id for mv in g.party()[0].moves.values()] == [b"GUST_T"]
    st = g.status()
    assert st["partyNonGen1Species"] == 1 and st["partyNonGen1Moves"] == 2
    logs = g.rig.logs()
    assert any("party.non_gen1_species" in line for line in logs)
    assert any("party.non_gen1_moves" in line for line in logs)


def test_a_sync_with_no_gen1_move_keeps_the_members_moves():
    g = BattleGame()
    sync(g, [entry(U2, 16, 12, [300])])
    g.run(2)
    assert [mv.id for mv in g.party()[0].moves.values()] == [b"GUST_T", b"SAND_T", b"QUICK_T"]


def test_a_sync_waits_at_the_title_and_applies_in_game():
    g = BattleGame()
    g.E.leaveWorld()
    sync(g, [entry(U2, 16, 9, [N["GUST_T"]])])
    g.run(2)
    assert g.party() == []
    g.E.stack.states = g.b.to_lua([])
    g.E.stack.states[1] = g.E.ow
    g.run(2)
    assert species(g) == ["BIRDMON"]


def test_the_last_sync_applies_again_after_new_game_or_continue():
    g = BattleGame()
    sync(g, [entry(U2, 16, 9, [N["GUST_T"]])])
    g.run(2)
    g.E.save.party = g.b.to_lua([])
    g.b.emit("save.created", g.b.eval("function(s) return { save = s } end")(g.E.save))
    g.run(2)
    assert species(g) == ["BIRDMON"]


@pytest.mark.parametrize("bad", [
    {"party": [entry("NOT-A-UID", 16, 9, [16])], "box": {"count": 0}},
    {"party": [entry(U1, 16, 9, [16]), entry(U1, 7, 5, [33])], "box": {"count": 0}},
    {"party": [entry(U1, 16, 9, [16], status="sick")], "box": {"count": 0}},
    {"party": [entry(U1, 16, 101, [16])], "box": {"count": 0}},
    {"party": [entry(U1, 16, 9, [16, 33, 45, 22, 10])], "box": {"count": 0}},
    {"party": []},
])
def test_bad_syncs_are_refused(bad):
    g = BattleGame()
    seq = g.send("party.sync", bad)
    g.run(2)
    assert ("bad_message", seq) in errors(g) and g.party() == []


def test_uids_come_back_from_mod_save_after_a_reload():
    g = BattleGame()
    sync(g, [entry(U2, 16, 9, [N["GUST_T"]]), entry(U3, 7, 5, [N["TACKLE_T"]])])
    g.run(2)
    assert [g.b.mod._save.partyUids[i] for i in (1, 2)] == [U2.encode(), U3.encode()]
    saved = g.b.mod._save
    g.b._mod_opts[b"save"] = saved          # the reloaded mod object reads the same save bucket
    g.b.reload()
    g.rig.step(2)
    g.connect()
    seq = g.send("resync")
    g.run()
    snap = [m for m in g.received("snapshot") if m.get("re") == seq][0]["data"]
    assert [p["uid"] for p in snap["party"]] == [U2, U3]


def test_engine_nickname_rules():
    from kblua import Bridge
    Party = Bridge().module("act/kb_party.lua")
    assert Party.engineNick(b"<PK><MN>ABCDEFGHIJ") == b"<PK><MN>ABCDEFGH"
    assert Party.engineNick("é\x01ÉB".encode()) == "éÉB".encode()
    assert Party.engineNick(b"") is None and Party.engineNick(None) is None


# ------------------------------------------------------------ heals


def test_heal_party_and_a_blackout_heal_cobblemons_party_too():
    g = BattleGame()
    g.script([["heal_party"], ["heal_party"]])
    g.run(2)
    assert len(g.received("party.heal")) == 1, "several heals collapse into one"
    g.E.healPoint = g.b.to_lua({"map": "OAKS_LAB", "x": 1, "y": 1})
    g.E.blackOutTo(b"OAKS_LAB", 1, 1)
    g.run(2)
    assert len(g.received("party.heal")) == 2


def test_a_heal_waits_for_a_client():
    g = BattleGame(connect=False)
    g.script([["heal_party"]])
    g.rig.step(2)
    g.connect()
    g.run(2)
    assert len(g.received("party.heal")) == 1


def test_logs_never_carry_nicknames():
    g = BattleGame()
    starter(g, nickname="ABBA")
    sync(g, [entry(U1, 7, 5, [33], nick="DCBA")])
    g.run(2)
    text = "\n".join(g.rig.logs())
    assert "ABBA" not in text and "DCBA" not in text and "TESTER" not in text
    assert "party.give" in text and "party.synced" in text
