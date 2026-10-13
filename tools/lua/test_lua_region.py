"""MG2: the region acts (act/kb_region.lua and the Gen 1 adapter's hold, start and marker seams, wired
by main.lua) on the simulated engine: region.park holds the hidden game where it stands (steps refused,
the overworld frozen under the hold, the music fading, the cell in the save's mod data and in
snapshot.away), region.resume releases it (in place, or through the engine's own warp onto one of our
route maps), region.start starts the game from its title (NEW GAME with its intro, or CONTINUE), each
refusal with its reason; a link drop pops the hold and leaves the marker; a hot reload keeps the hold.
Every message the peer receives is schema-checked (rig.Peer, strict). All data is made up (centersim.py,
routesim.py)."""

import pytest

import routesim as R
from centersim import CenterGame

pytestmark = pytest.mark.lua

KANTO = {"region": "kanto"}
HERE = {"map": "TEST_CENTER", "x": 3, "y": 3, "facing": "down"}


def result(g, seq):
    """The newest act.result answering seq (a reconnected peer counts its seqs from 1 again), or None."""
    for m in reversed(g.received("act.result")):
        if m.get("re") == seq:
            return m["data"]
    return None


def park(g, steps=2):
    seq = g.send("region.park", KANTO)
    g.run(steps)
    return result(g, seq)


def resume(g, to=HERE, steps=2):
    seq = g.send("region.resume", {"region": "kanto", "to": to})
    g.run(steps)
    return result(g, seq)


def start(g, steps=3):
    seq = g.send("region.start", KANTO)
    g.run(steps)
    return result(g, seq)


def held(g):
    return g.rig.status.regionHeld is True


def marker(g):
    rec = g.b.mod._save[b"region"]
    if rec is None or rec[b"away"] is None:
        return None
    a = rec[b"away"]
    return (a.map.decode(), a.x, a.y)


def top(g):
    return g.E.stack.top(g.E.stack)


def on_overworld(g):
    """The overworld is the top state (lupa wrappers never compare by identity)."""
    t = top(g)
    return t is not None and t.isOverworld is True


def at_title(saved=False, **kw):
    g = CenterGame(**kw)
    if saved:
        g.E.SaveData.setActiveSlot(b"blue", b"slot1")
        g.E.slotFiles[b"slot1"] = True
    g.E.game.returnToTitle(g.E.game)
    g.E.titleRebuilds = 0
    g.run(2)
    return g


# ------------------------------------------------------------ park


def test_park_holds_the_game_where_it_stands():
    g = CenterGame()
    assert park(g) == {"ok": True, "kind": "park"}
    assert held(g) and top(g).kantoBridgeRegionHold is True, "the hold is the top state"
    assert marker(g) == ("TEST_CENTER", 3, 3)
    assert g.E.musicFades == 1, "the map's song fades"
    st = g.status()
    assert st["regionHeld"] is True and st["regionParks"] == 1 and st["mode"] == "busy"
    # steps are refused while held (the step driver answers script outside idle) and the player stays
    g.send("move.step", {"dir": "down", "n": 1})
    g.run(3)
    assert g.received("player.blocked")[-1]["data"]["reason"] == "script"
    p = g.E.ow.player
    assert (p.cellX, p.cellY, p.moving) == (3, 3, False)
    # a park while parked is ok again
    assert park(g) == {"ok": True, "kind": "park"} and g.status()["regionParks"] == 2
    assert g.E.musicFades == 1


def test_the_overworld_is_frozen_under_the_hold():
    g = CenterGame()
    park(g)
    # the fake engine ticks only the top state (StateStack:update): the nurse's emote and the
    # player's input never run while the hold is up, and only the hold's own update does
    ticks = g.E.ticks
    g.run(5)
    assert g.E.ticks == ticks + 5 and top(g).kantoBridgeRegionHold is True
    assert g.E.ow.player.moving is False


def test_park_needs_the_game_and_an_idle_player():
    g = at_title()
    assert park(g) == {"ok": False, "kind": "park", "reason": "not_ingame"}
    g = CenterGame()
    g.send("move.step", {"dir": "down", "n": 1})
    g.run(1)
    assert g.E.ow.player.moving is True
    assert park(g, steps=1) == {"ok": False, "kind": "park", "reason": "not_idle"}
    assert not held(g) and marker(g) is None
    st = g.status()
    assert st["regionFailed"] == 1 and st["regionParks"] == 0


def test_a_bad_region_id_is_a_bad_message():
    g = CenterGame()
    for data in ({"region": "Kanto"}, {"region": "x" * 25}, {}, {"region": 7}):
        seq = g.send("region.park", data)
        g.run(1)
        errs = [m for m in g.received("error") if m.get("re") == seq]
        assert errs and errs[0]["data"]["code"] == "bad_message", data


def test_the_snapshot_shows_the_parked_cell():
    g = CenterGame()
    before = g.received("snapshot")[0]["data"]
    assert "away" not in before
    park(g)
    seq = g.send("resync")
    snap = g.until(lambda m: m["type"] == "snapshot" and m.get("re") == seq)
    assert snap["data"]["away"] == {"map": "TEST_CENTER", "x": 3, "y": 3}


# ------------------------------------------------------------ resume


def test_resume_onto_the_parked_cell_releases_the_hold_without_a_warp():
    g = CenterGame()
    park(g)
    assert g.E.musicRestores == 0
    assert resume(g) == {"ok": True, "kind": "resume"}
    assert not held(g) and on_overworld(g) and marker(g) is None
    assert g.received("warp") == []
    assert g.E.musicRestores == 1 and g.E.musicRestoreCalls[1].reason == b"region"
    # the game takes steps again
    g.send("move.step", {"dir": "down", "n": 1})
    g.run(3)
    assert g.received("player.pos")[-1]["data"]["kind"] == "walk"
    seq = g.send("resync")
    snap = g.until(lambda m: m["type"] == "snapshot" and m.get("re") == seq)
    assert "away" not in snap["data"]


def test_resume_refusals():
    g = CenterGame()
    assert resume(g) == {"ok": False, "kind": "resume", "reason": "not_parked"}
    park(g)
    # not a route map, not the parked cell's map
    assert resume(g, {"map": "TEST_MART", "x": 2, "y": 3, "facing": "up"}) == {"ok": False, "kind": "resume", "reason": "bad_target"}
    assert held(g), "a refusal leaves the hold up"
    # a warp under way
    g.E.ow.transitioning = True
    assert resume(g) == {"ok": False, "kind": "resume", "reason": "not_idle"}
    g.E.ow.transitioning = False
    assert held(g)
    # malformed targets
    for to in ({"map": "TEST_CENTER", "x": 3, "y": 3}, {"map": "lower", "x": 3, "y": 3, "facing": "down"}, "TEST_CENTER"):
        seq = g.send("region.resume", {"region": "kanto", "to": to})
        g.run(1)
        errs = [m for m in g.received("error") if m.get("re") == seq]
        assert errs and errs[0]["data"]["code"] == "bad_message", to
    assert resume(g) == {"ok": True, "kind": "resume"}


def test_resume_onto_a_route_map_warps_the_engines_own_way():
    g = R.RouteGame(start=("TEST_WOODS", 3, 3, "down"))
    assert park(g) == {"ok": True, "kind": "park"}
    assert marker(g) == ("TEST_WOODS", 3, 3)
    seq = g.send("region.resume", {"region": "kanto", "to": {"map": R.ROUTE_MAP, "x": 1, "y": 0, "facing": "right"}})
    g.run(2)
    assert result(g, seq) is None, "answered once the warp has arrived"
    assert g.queued()[-1] == [["warp", R.ROUTE_MAP, 1, 0, "right"]]
    g.run(40)
    assert result(g, seq) == {"ok": True, "kind": "resume"}
    assert g.where() == (R.ROUTE_MAP, 1, 0) and not held(g) and marker(g) is None
    warps = [m["data"] for m in g.received("warp")]
    assert warps[-2]["phase"] == "start" and warps[-2]["kind"] == "script"
    assert warps[-1] == {"phase": "arrive", "kind": "script", "to": {"map": R.ROUTE_MAP, "x": 1, "y": 0, "facing": "right"}}
    st = g.status()
    assert (st["regionResumes"], st["regionWarps"]) == (1, 1)


def test_a_connecting_routes_map_is_a_target_too():
    c = dict(R.CONNECTING)
    g = R.RouteGame(zones=[c], start=("TEST_WOODS", 3, 3, "down"))
    park(g)
    seq = g.send("region.resume", {"region": "kanto", "to": {"map": R.PASS_MAP, "x": 1, "y": 0, "facing": "right"}})
    g.run(42)
    assert result(g, seq) == {"ok": True, "kind": "resume"} and g.where() == (R.PASS_MAP, 1, 0)


# ------------------------------------------------------------ the link


def test_a_link_drop_pops_the_hold_and_leaves_the_marker_for_the_next_client():
    g = CenterGame()
    park(g)
    g.peer.close()
    g.run(3)
    assert not held(g) and on_overworld(g), "the game can be played alone"
    assert marker(g) == ("TEST_CENTER", 3, 3)
    assert any("region.released reason=link" in line for line in g.rig.logs())
    g.connect()
    assert g.received("snapshot")[-1]["data"]["away"] == {"map": "TEST_CENTER", "x": 3, "y": 3}
    # the marker alone is taken up by a resume
    assert resume(g) == {"ok": True, "kind": "resume"} and marker(g) is None


def test_a_hot_reload_keeps_the_hold():
    # save_files: mod.save is the live save's own modData bucket, as in the engine, so the marker
    # outlives the mod instance; the hold lives in the Game object's process-lifetime slot
    g = CenterGame(save_files=True)
    park(g)
    g.b.reload()
    g.connect()
    g.run(1)
    assert held(g) and top(g).kantoBridgeRegionHold is True
    assert not any("region.released" in line for line in g.rig.logs()), "nothing was dropped"
    assert g.received("snapshot")[-1]["data"]["away"] == {"map": "TEST_CENTER", "x": 3, "y": 3}
    assert resume(g) == {"ok": True, "kind": "resume"} and not held(g) and on_overworld(g)


def test_requests_of_an_old_connection_are_dropped_and_too_many_are_busy():
    g = CenterGame()
    for _ in range(9):
        g.send("region.park", KANTO)
    g.run(12)  # one request a step
    reasons = [m["data"].get("reason") for m in g.received("act.result")]
    assert reasons.count("busy") == 1 and reasons.count(None) == 8
    # a new connection: what the old one asked for dies unanswered
    g.send("region.park", KANTO)
    g.peer.close()
    g.run(2)
    n = len(g.received("act.result"))
    g.connect()
    g.run(3)
    assert len(g.received("act.result")) == n


# ------------------------------------------------------------ start


def test_start_from_the_title_is_a_new_game_with_its_intro_when_no_save_loads():
    g = at_title()
    assert start(g) == {"ok": True, "kind": "start", "detail": "new"}
    assert g.E.newGames == 1 and g.E.intros == 1 and g.E.restores is None
    assert top(g).isTextBox is True, "the intro's first screen is up"
    opened = [m["data"] for m in g.received("ui.open")]
    assert opened and opened[-1]["kind"] == "text"
    status = g.received("game.status")[-1]["data"]
    assert (status["playthrough"], status["started"], status["ingame"], status["name"]) == ("fresh", True, True, "NEWTEST")
    assert g.status()["regionStarts"] == 1


def test_start_continues_the_saved_game_when_the_slot_holds_one():
    g = at_title(saved=True)
    assert start(g) == {"ok": True, "kind": "start", "detail": "continue"}
    assert g.E.restores == 1 and g.E.newGames == 0
    assert g.E.lastRestoreOpts.freshBoot is True and g.E.lastRestoreOpts.continued is True
    assert on_overworld(g)
    status = g.received("game.status")[-1]["data"]
    assert (status["playthrough"], status["started"]) == ("saved", True)


def test_start_closes_an_open_title_menu_first():
    g = at_title()
    g.E.uiPush(g.E.menu("TitleMenu"))
    g.run(1)
    seq = g.send("region.start", KANTO)
    g.run(1)
    assert result(g, seq) is None and g.E.titleRebuilds == 1, "the menu was closed; the start waits a step"
    g.run(3)
    assert result(g, seq) == {"ok": True, "kind": "start", "detail": "new"}


def test_start_needs_the_title():
    g = CenterGame()
    assert start(g) == {"ok": False, "kind": "start", "reason": "not_title"}
    assert g.E.newGames == 0
