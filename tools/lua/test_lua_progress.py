"""MG2: game.status (observe/kb_progress.lua, wired by main.lua) on the simulated engine: the title's
status after the snapshot, a New Game's (fresh, the player's name), CONTINUE's (saved), a gained badge
and a set story marker (sent once each, compared every few steps), the active slot, a bind's follow-up,
and nothing while nothing changes. Every message the peer receives is schema-checked (rig.Peer, strict).
All data is made up (centersim.py)."""

import pytest

from centersim import CenterGame

pytestmark = pytest.mark.lua


def statuses(g):
    return [m["data"] for m in g.received("game.status")]


def at_title(**kw):
    g = CenterGame(**kw)
    g.E.game.returnToTitle(g.E.game)
    g.run(12)  # the status is compared every 10 steps
    return g


def test_the_title_status_follows_the_snapshot():
    g = CenterGame(connect=False)
    g.E.game.returnToTitle(g.E.game)
    g.run(1)
    g.connect()
    types = [m["type"] for m in g.log if not m["type"].startswith("ui.")]  # the title screen is a UI
    assert types == ["hello.ok", "snapshot", "game.status"], "no world: nothing else follows"
    assert statuses(g) == [{"game": "blue", "playthrough": "title", "ingame": False, "started": False, "badges": 0,
                            "markers": {"starter": False, "champion": False}, "slot": 0}]


def test_in_game_the_status_names_the_save_and_follows_the_snapshots_followers():
    g = CenterGame()
    types = [m["type"] for m in g.log]
    assert types[:5] == ["hello.ok", "snapshot", "npc.set", "bag.sync", "game.status"]
    assert statuses(g) == [{"game": "blue", "playthrough": "fresh", "ingame": True, "started": True, "badges": 1,
                            "markers": {"starter": False, "champion": False}, "name": "TESTER", "slot": 0,
                            "visitors": 0}]
    g.run(30)
    assert len(statuses(g)) == 1, "nothing changed: nothing sent"
    seq = g.send("resync")
    g.until(lambda m: m["type"] == "snapshot" and m.get("re") == seq)
    g.run(1)
    assert len(statuses(g)) == 2, "a snapshot is followed by the status again"


def test_a_new_game_and_a_continue_change_the_playthrough():
    g = at_title()
    assert statuses(g)[-1]["playthrough"] == "title"
    g.E.startNewGame()
    g.run(2)
    s = statuses(g)[-1]
    assert (s["playthrough"], s["started"], s["ingame"], s["name"]) == ("fresh", True, True, "NEWTEST")
    g.E.game.returnToTitle(g.E.game)
    g.E.slotFiles[b"flat"] = True
    g.run(12)
    assert statuses(g)[-1]["playthrough"] == "title" and "name" not in statuses(g)[-1]
    g.E.continueGame()
    g.run(2)
    s = statuses(g)[-1]
    assert (s["playthrough"], s["started"]) == ("saved", True)


def test_badges_and_markers_are_reported_when_they_change():
    g = CenterGame()
    n = len(statuses(g))
    g.E.save.inventory[b"CASCADEBADGE"] = True
    g.run(12)
    assert statuses(g)[-1]["badges"] == 2 and len(statuses(g)) == n + 1
    g.E.save.flags[b"EVENT_GOT_STARTER"] = True
    g.run(12)
    assert statuses(g)[-1]["markers"] == {"starter": True, "champion": False} and len(statuses(g)) == n + 2
    g.E.save.flags[b"EVENT_BEAT_CHAMPION_RIVAL"] = True
    g.run(12)
    assert statuses(g)[-1]["markers"] == {"starter": True, "champion": True}
    st = g.status()
    assert st["progressSent"] == n + 3 and st["progressChanges"] == 3


def test_the_slot_and_a_bind_are_reported():
    g = CenterGame()
    n = len(statuses(g))
    g.E.SaveData.setActiveSlot(b"blue", b"slot2")
    g.run(12)
    assert statuses(g)[-1]["slot"] == 2
    seq = g.send("session.bind", {"world": "world-a-0001", "multiplayer": False})
    g.run(3)
    assert any(m.get("re") == seq for m in g.received("session.bound"))
    assert len(statuses(g)) >= n + 2, "session.bound is followed by the status"


def test_the_status_never_goes_to_a_builder():
    g = CenterGame(connect=False)
    g.connect(role="builder")
    assert statuses(g) == []


def test_at_the_title_the_status_carries_the_active_slots_save_summary():
    # WORLD_SESSIONS_PLAN WS2: game.status.save (the first-join screen; B1's stash only before a truly new game)
    g = CenterGame()
    g.E.SaveData.setActiveSlot(b"blue", b"slot1")
    g.E.game.returnToTitle(g.E.game)
    g.run(12)
    s = statuses(g)[-1]
    assert s["playthrough"] == "title" and "save" not in s, "an empty slot: no save"
    g.E.slotFiles[b"slot1"] = True
    g.E.slotSummaries = g.b.to_lua({"slot1": {"name": "SAVER", "badges": 3}})
    seq = g.send("resync")
    g.until(lambda m: m["type"] == "snapshot" and m.get("re") == seq)
    g.run(2)
    assert statuses(g)[-1]["save"] == {"slot": 1, "name": "SAVER", "badges": 3}
    g.E.continueGame()
    g.run(2)
    assert "save" not in statuses(g)[-1], "started: the save says itself through name and badges"
