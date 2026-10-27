"""WORLD_SESSIONS_PLAN WS4: save.request (act/kb_save.lua's requestStep and kb_engine.writeSaveAs, wired by main.lua) on
the simulated engine: an idle game saves at once (save.begin with the request's reason, act.result ok); a game at its
title answers not_ingame; a closed quick-save gate (a script, a menu) makes the request wait and save once it opens, or
answer not_idle after SAVE_WAIT steps; a game held by region.park saves under its hold; a newer request replaces an older
one (busy); a vetoed write answers vetoed. Every message the peer receives is schema-checked (rig.Peer, strict)."""

import pytest

from centersim import CenterGame

pytestmark = pytest.mark.lua


def ask(g, reason="world", steps=3):
    seq = g.send("save.request", {"reason": reason})
    g.run(steps)
    return seq


def answer(g, seq):
    found = [m["data"] for m in g.received("act.result") if m.get("re") == seq]
    return found[-1] if found else None


def test_an_idle_game_saves_with_the_requests_reason():
    g = CenterGame()
    seq = ask(g)
    assert answer(g, seq) == {"kind": "save", "ok": True}
    assert g.last("save.begin")["reason"] == "world"
    assert g.E.saveWrites == 1
    seq = ask(g, "quit")
    assert answer(g, seq)["ok"] is True and g.last("save.begin")["reason"] == "quit"
    st = g.status()
    assert (st["saveRequests"], st["saveRequestWritten"], st["saveWorld"], st["saveQuit"]) == (2, 2, 1, 1)


def test_at_the_title_nothing_is_saved():
    g = CenterGame()
    g.E.game.returnToTitle(g.E.game)
    g.run(2)
    seq = ask(g)
    assert answer(g, seq) == {"kind": "save", "ok": False, "reason": "not_ingame"}
    assert g.E.saveWrites == 0


def test_a_closed_gate_waits_then_saves_or_gives_up():
    g = CenterGame()
    g.E.ow.runner.running = True  # a script: the quick-save gate is closed
    seq = ask(g, steps=30)
    assert answer(g, seq) is None and g.E.saveWrites == 0, "waits"
    g.E.ow.runner.running = False
    g.run(3)
    assert answer(g, seq)["ok"] is True and g.E.saveWrites == 1
    g.E.ow.runner.running = True
    seq = ask(g, steps=1)
    for k in range(7):  # 700 steps, the session kept alive like a client's pings
        g.send("ping", {"t": k})
        g.run(100)
    assert answer(g, seq) == {"kind": "save", "ok": False, "reason": "not_idle"}
    assert g.E.saveWrites == 1


def test_a_held_game_saves_under_its_hold():
    g = CenterGame()
    park = g.send("region.park", {"region": "kanto"})
    g.run(3)
    assert [m["data"] for m in g.received("act.result") if m.get("re") == park][-1]["ok"] is True
    seq = ask(g)
    assert answer(g, seq)["ok"] is True and g.last("save.begin")["reason"] == "world"
    assert g.rig.status.regionHeld is True, "still held"


def test_a_newer_request_replaces_an_older_and_a_veto_is_answered():
    g = CenterGame()
    g.E.ow.runner.running = True
    first = g.send("save.request", {"reason": "world"})
    g.run(2)
    second = ask(g, "quit")
    assert answer(g, first) == {"kind": "save", "ok": False, "reason": "busy"}
    g.E.saveVeto = True
    g.E.ow.runner.running = False
    g.run(3)
    assert answer(g, second) == {"kind": "save", "ok": False, "reason": "vetoed"}


def test_a_bad_reason_is_refused():
    g = CenterGame()
    seq = g.send("save.request", {"reason": "menu"})
    g.run(2)
    assert any(m["data"]["code"] == "bad_message" and m.get("re") == seq for m in g.received("error"))
