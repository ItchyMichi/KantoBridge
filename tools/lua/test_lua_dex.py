"""M6 dex.sync through main.lua on the simulated engine (act/kb_party.lua, kb_engine.applyDex):
Cobblemon's seen/owned dex numbers are added to save.pokedex as species ids -- a union that never
clears what the hidden game set, owned implying seen -- once an overworld is up, and again after New
Game or CONTINUE; bad syncs are refused; the snapshot's dex lists follow. Every message the peer
receives is schema-checked (rig.Peer, strict). All data is made up (battlesim.py)."""

import pytest

from battlesim import BattleGame

pytestmark = pytest.mark.lua


def errors(g):
    return [(m["data"]["code"], m.get("re")) for m in g.received("error")]


def dex(g, which):
    t = g.E.save.pokedex[which.encode()]
    return sorted(k.decode() for k, v in t.items() if v)


def test_dex_sync_adds_to_the_hidden_games_pokedex():
    g = BattleGame()
    g.E.save.pokedex.seen.SLEEPMON = True          # the hidden game's own entries stay
    g.E.save.pokedex.owned.SLEEPMON = True
    g.send("dex.sync", {"seen": [16, 1], "owned": [7]})
    g.run(2)
    assert dex(g, "seen") == ["BIRDMON", "SLEEPMON", "STARTMON_A", "STARTMON_C"], "owned is seen too"
    assert dex(g, "owned") == ["SLEEPMON", "STARTMON_C"]
    st = g.status()
    assert (st["dexSeen"], st["dexOwned"]) == (4, 2)
    assert st["partyDexApplied"] == 1 and st["partyDexAddedSeen"] == 3 and st["partyDexAddedOwned"] == 1


def test_a_smaller_sync_never_clears_anything():
    g = BattleGame()
    g.send("dex.sync", {"seen": [16, 1, 4], "owned": [1, 4]})
    g.run(2)
    g.send("dex.sync", {"seen": [], "owned": []})
    g.run(2)
    assert dex(g, "seen") == ["BIRDMON", "STARTMON_A", "STARTMON_B"]
    assert dex(g, "owned") == ["STARTMON_A", "STARTMON_B"]


def test_dex_numbers_with_no_species_are_counted_and_left_out():
    g = BattleGame()
    g.send("dex.sync", {"seen": [16, 150], "owned": [151]})
    g.run(2)
    assert dex(g, "seen") == ["BIRDMON"] and dex(g, "owned") == []
    assert g.status()["partyDexUnknown"] == 2


def test_numbers_beyond_the_games_ceiling_are_dropped_and_counted():
    # MG7 (G3F): the server sends every species it can name (national numbers); a Gen 1 game keeps 1-151
    g = BattleGame()
    g.send("dex.sync", {"seen": [16, 252, 386], "owned": [7, 254]})
    g.run(2)
    assert dex(g, "seen") == ["BIRDMON", "STARTMON_C"] and dex(g, "owned") == ["STARTMON_C"]
    st = g.status()
    assert st["partyDexBeyond"] == 3 and st["partyDexUnknown"] == 0


@pytest.mark.parametrize("bad", [
    {"seen": [2001], "owned": []},
    {"seen": [0], "owned": []},
    {"seen": [16, 16], "owned": []},
    {"seen": [16]},
    {"seen": ["16"], "owned": []},
    {"seen": {"a": 16}, "owned": []},
])
def test_bad_dex_syncs_are_refused(bad):
    g = BattleGame()
    seq = g.peer.send("dex.sync", bad)
    g.run(2)
    assert ("bad_message", seq) in errors(g) and dex(g, "seen") == []


def test_at_the_title_it_waits_and_after_new_game_it_applies_again():
    g = BattleGame()
    states = g.E.stack.states
    g.E.leaveWorld()
    g.send("dex.sync", {"seen": [16], "owned": [7]})
    g.run(2)
    assert dex(g, "seen") == [] and g.status()["partyDexWaiting"] is True
    g.E.stack.states = states
    g.run(2)
    assert dex(g, "owned") == ["STARTMON_C"]
    # New Game: a fresh Pokedex gets Cobblemon's again
    g.E.save.pokedex = g.b.to_lua({"seen": {}, "owned": {}})
    g.b.emit("save.created", g.b.eval("function(s) return { save = s } end")(g.E.save))
    g.run(2)
    assert dex(g, "seen") == ["BIRDMON", "STARTMON_C"] and dex(g, "owned") == ["STARTMON_C"]


def test_the_snapshot_carries_the_synced_pokedex():
    g = BattleGame()
    g.send("dex.sync", {"seen": [16, 1], "owned": [7]})
    g.run(2)
    seq = g.send("resync")
    g.run()
    snap = [m for m in g.received("snapshot") if m.get("re") == seq][0]["data"]
    assert snap["dex"] == {"seen": [1, 7, 16], "owned": [7]}


def test_a_builder_cannot_sync_the_pokedex():
    g = BattleGame(connect=False)
    g.connect(role="builder")
    seq = g.send("dex.sync", {"seen": [16], "owned": []})
    g.run(2)
    assert ("role", seq) in errors(g) and dex(g, "seen") == []


def test_logs_show_counts_only():
    g = BattleGame()
    g.send("dex.sync", {"seen": [16, 1], "owned": [7]})
    g.run(2)
    text = "\n".join(g.rig.logs())
    assert "party.dex" in text and "BIRDMON" not in text and "STARTMON" not in text
