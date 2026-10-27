"""WORLD_SESSIONS_PLAN WS3: every saved game in a world continues at join (act/kb_continue.lua through the region acts'
queue, wired by main.lua and act/kb_pairing.lua) on the simulated engine: session.bind{continue:"play"} at the title
CONTINUEs a saved slot (else NEW GAME), continue "park" CONTINUEs and holds the game where the save loaded (a slot
without a save stays at its title), a game already in play is left (play) or held where it stands (park), continue
"title" answers at once; session.bound says what happened (started, parked) and comes only once it has. Every message
the peer receives is schema-checked (rig.Peer, strict). All data is made up."""

import pytest

from centersim import CenterGame

pytestmark = pytest.mark.lua

W = "world-cont-001"


def at_title(saved=True):
    g = CenterGame()
    g.E.SaveData.setActiveSlot(b"blue", b"slot1")
    if saved:
        g.E.slotFiles[b"slot1"] = True
    g.E.game.returnToTitle(g.E.game)
    g.E.titleRebuilds = 0
    g.run(2)
    return g


def bind(g, cont, steps=12, multiplayer=False):
    seq = g.send("session.bind", {"world": W, "multiplayer": multiplayer, "continue": cont})
    g.run(steps)
    return seq


def bound(g, seq):
    found = [m["data"] for m in g.received("session.bound") if m.get("re") == seq]
    return found[-1] if found else None


def on_overworld(g):
    t = g.E.stack.top(g.E.stack)
    return t is not None and t.isOverworld is True


def held(g):
    return g.rig.status.regionHeld is True


def test_play_continues_a_saved_slot_and_answers_once_in_game():
    g = at_title()
    seq = bind(g, "play")
    b = bound(g, seq)
    assert b is not None and (b["started"], b["parked"]) == ("continue", False)
    assert on_overworld(g) and not held(g)
    assert any(m["data"]["fresh"] is False for m in g.received("save.loaded")), "the CONTINUE's save.loaded"
    st = g.status()
    assert (st["continueContinued"], st["continueParked"], st["continuePending"]) == (1, 0, False)


def test_play_without_a_save_starts_a_new_game():
    g = at_title(saved=False)
    b = bound(g, bind(g, "play"))
    assert b["started"] == "new" and b["parked"] is False


def test_park_continues_and_holds_where_the_save_loaded():
    g = at_title()
    seq = bind(g, "park", steps=20)
    b = bound(g, seq)
    assert (b["started"], b["parked"]) == ("continue", True)
    assert held(g)
    snap_seq = g.send("resync")
    snap = g.until(lambda m: m["type"] == "snapshot" and m.get("re") == snap_seq)
    assert "away" in snap["data"], "the marker shows the parked cell"


def test_park_without_a_save_stays_at_the_title():
    g = at_title(saved=False)
    b = bound(g, bind(g, "park"))
    assert (b["started"], b["parked"]) == ("none", False)
    assert not on_overworld(g)


def test_in_game_play_leaves_it_and_park_holds_it():
    g = CenterGame()
    g.run(2)
    b = bound(g, bind(g, "play"))
    assert (b["started"], b["parked"]) == ("none", False) and on_overworld(g)
    b = bound(g, bind(g, "park", steps=20))
    assert (b["started"], b["parked"]) == ("none", True) and held(g)


def test_title_answers_at_once_and_nothing_starts():
    g = at_title()
    seq = bind(g, "title", steps=2)
    assert bound(g, seq)["started"] == "none"
    assert not on_overworld(g)


def test_without_continue_the_bind_answers_as_before():
    g = at_title()
    seq = g.send("session.bind", {"world": W, "multiplayer": False})
    g.run(2)
    b = bound(g, seq)
    assert b is not None and "started" not in b and not on_overworld(g)
