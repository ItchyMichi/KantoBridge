"""MG2 end to end, on the real engine: the region acts and game.status (docs/specs/MULTI_GAME_PLAN.md
§4.3, §4.7; docs/PROTOCOL.md "Regions (MG2)"), the plan's MG2 check and spike S4. The driver is
drivers/kb_regions.lua; every scene runs on a fresh empty save slot labelled KB_REGIONS_TEST, deleted
again at the end (the install's own saves are never touched: their listing is the same before and after).

  start_new (KB_REGION_REPEATS launches, default 3): at the title on the empty slot, game.status says
      `title`; region.start -> act.result{kind:"start", detail:"new"}; the game reaches `fresh`
      (game.status started) and Oak's speech opens as a UI; nothing advances it and no step is taken:
      no stray input reached the intro.
  start_continue (as many launches): a New Game saved into the slot, back at the title: region.start ->
      detail "continue", save.loaded{fresh:false}, game.status `saved` with ingame true.
  park (one launch): in the overworld, region.park -> ok; a move.step is refused (`script`); the
      snapshot carries `away` = the parked cell; region.resume onto that cell -> ok and the cell is
      unchanged with no `away`; a second resume -> not_parked; a resume onto a made-up map -> bad_target
      while parked; the hold is released again at the end.

Every message passes its schema (strict). Output: types, slot numbers, map symbols, cell numbers,
counts and booleans only; the region id is ours.
"""

import asyncio
import os
import time

import pytest

from fake_minecraft import FakeMinecraft
from run_gen1recomp import INSTALL, launch, preflight

TEST_PORT = 27210
REGION = "kanto"
REPEATS = int(os.environ.get("KB_REGION_REPEATS", "3"))


# ------------------------------------------------------------ file listings (never opened)


def save_files(game):
    """{name: (size, mtime_ns)} of the save files in saves/<game>/ (a listing; nothing is opened)."""
    folder = INSTALL / "saves" / game
    if not folder.is_dir():
        return {}
    out = {}
    for name in sorted(os.listdir(folder)):
        st = (folder / name).stat()
        out[name] = (st.st_size, st.st_mtime_ns)
    return out


def without_slot(files, slot):
    return {n: v for n, v in files.items() if not (n == f"slot{slot}.lua" or n.startswith(f"slot{slot}.lua."))}


# ------------------------------------------------------------ the bridge


async def connect(port):
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=30)
    await mc.hello()
    snap = (await mc.expect("snapshot", timeout=10))["data"]
    status = (await mc.expect("game.status", timeout=10))["data"]
    return mc, snap, status


async def find_or_wait(mc, types, timeout, since=0, pred=None):
    """The first message of one of `types` (a type name or a tuple) received at or after index `since` of
    mc.received (already there, or still to come) that passes `pred`. Messages the wait skips stay in
    mc.received, like expect()'s."""
    wanted = (types,) if isinstance(types, str) else tuple(types)
    deadline = time.monotonic() + timeout
    seen = since
    while True:
        while seen < len(mc.received):
            message = mc.received[seen]
            seen += 1
            if message["type"] in wanted and (pred is None or pred(message)):
                return message
        left = deadline - time.monotonic()
        assert left > 0, f"no {'/'.join(wanted)} within {timeout} s"
        try:
            if await mc.next(min(left, 1.0)) is None:
                raise ConnectionError(f"connection closed while waiting for {'/'.join(wanted)}")
        except asyncio.TimeoutError:
            pass


def errors(mc):
    return [m["data"]["code"] for m in mc.received if m["type"] == "error"]


def kinds(mc, msg_type, since=0):
    return [m["data"].get("kind") for m in mc.received[since:] if m["type"] == msg_type]


async def _start(port, detail):
    mc, snap, status = await connect(port)
    report = {"ingame_at_title": snap["ingame"], "status_at_title": {k: status.get(k) for k in
                                                                      ("playthrough", "started", "ingame", "badges")}}
    assert snap["ingame"] is False
    assert (status["playthrough"], status["started"], status["ingame"]) == ("title", False, False)
    assert "name" not in status, "no player name at the title"
    sent = await mc.send("region.start", {"region": REGION})
    since = len(mc.received)
    result = (await mc.expect("act.result", timeout=90, re=sent["seq"]))["data"]
    report["result"] = result
    assert result == {"ok": True, "kind": "start", "detail": detail}, result
    started = (await find_or_wait(mc, "game.status", 90, since, lambda m: m["data"]["started"] is True))["data"]
    report["after"] = {k: started.get(k) for k in ("playthrough", "started", "ingame")}
    if detail == "continue":
        assert (started["playthrough"], started["ingame"]) == ("saved", True)
        loaded = (await find_or_wait(mc, "save.loaded", 60, since))["data"]
        report["loaded"] = {"fresh": loaded["fresh"], "pair": "pair" in loaded}
        assert loaded["fresh"] is False and "pair" in loaded
    else:
        assert started["playthrough"] == "fresh"
        # the intro's first UI of the player's own (Oak's speech is a text box; the naming screen later): the
        # title's mirror and the title menu the start drove close before it, which is theirs
        opened = (await find_or_wait(mc, "ui.open", 90, since,
                                     lambda m: m["data"]["kind"] in ("text", "naming", "choice")))["data"]
        report["first_ui"] = opened["kind"]
        # nothing of ours touches the intro: that UI stays as it opened and no step is taken
        await asyncio.sleep(2)
        while True:  # drain what arrived meanwhile into mc.received
            try:
                if await mc.next(0.1) is None:
                    break
            except asyncio.TimeoutError:
                break
        closed = [m for m in mc.received[since:] if m["type"] == "ui.close" and m["data"]["ui"] == opened["ui"]]
        report["intro_ui_closed"] = bool(closed)
        assert not closed, "the intro's UI was advanced without us"
        assert "walk" not in kinds(mc, "player.pos", since), "a step was taken without us"
    report["errors"] = errors(mc)
    report["problems"] = len(mc.conn.problems)
    assert report["errors"] == [] and report["problems"] == 0
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return report


async def _park(port):
    mc, snap, status = await connect(port)
    report = {"status": {k: status.get(k) for k in ("playthrough", "started", "ingame")}}
    assert snap["ingame"] is True and "away" not in snap
    assert (status["playthrough"], status["started"], status["ingame"]) == ("fresh", True, True)
    cell = snap["player"]
    parked = (await mc.request("region.park", {"region": REGION}, reply_type="act.result", timeout=30))["data"]
    report["park"] = parked
    assert parked == {"ok": True, "kind": "park"}, parked
    await mc.send("move.step", {"dir": cell["facing"], "n": 1})
    blocked = (await mc.expect("player.blocked", timeout=10))["data"]
    report["blocked"] = blocked["reason"]
    assert blocked["reason"] == "script"
    held = (await mc.request("resync", reply_type="snapshot", timeout=10))["data"]
    report["away"] = held.get("away") == {"map": cell["map"], "x": cell["x"], "y": cell["y"]}
    assert report["away"], sorted(held)
    assert held["player"] == cell
    bad = (await mc.request("region.resume", {"region": REGION, "to": {"map": "TEST_NOWHERE", "x": 1, "y": 1,
                                                                        "facing": "down"}},
                            reply_type="act.result", timeout=30))["data"]
    report["bad_target"] = bad
    assert bad == {"ok": False, "kind": "resume", "reason": "bad_target"}, bad
    resumed = (await mc.request("region.resume", {"region": REGION, "to": cell}, reply_type="act.result",
                                timeout=30))["data"]
    report["resume"] = resumed
    assert resumed == {"ok": True, "kind": "resume"}, resumed
    after = (await mc.request("resync", reply_type="snapshot", timeout=10))["data"]
    report["cell_unchanged"] = after["player"] == cell and "away" not in after
    assert report["cell_unchanged"], "a park/resume pair leaves the hidden player where it stood"
    twice = (await mc.request("region.resume", {"region": REGION, "to": cell}, reply_type="act.result",
                              timeout=30))["data"]
    report["not_parked"] = twice
    assert twice == {"ok": False, "kind": "resume", "reason": "not_parked"}, twice
    # the player takes steps again after the resume (or is stopped by the room, never by a hold)
    since = len(mc.received)
    await mc.send("move.step", {"dir": cell["facing"], "n": 2})
    answer = await find_or_wait(mc, ("player.pos", "player.blocked"), 10, since, lambda m: m["data"].get("n") == 2)
    report["step_after_resume"] = (answer["type"], answer["data"].get("reason"))
    if answer["type"] == "player.blocked":
        assert answer["data"]["reason"] != "script", "the hold is gone"
    report["errors"] = errors(mc)
    report["problems"] = len(mc.conn.problems)
    assert report["errors"] == [] and report["problems"] == 0
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return report


def _cleanup_run():
    with launch("kb_regions.lua", extra_env={"KB_TEST_PORT": str(TEST_PORT), "KB_SCENE": "cleanup"},
                echo=False) as running:
        return running.wait(timeout=120)


def _launch(scene, ready_marker, scenario, scenario_timeout, home_before):
    """One launch of a scene: wait for its ready marker, run the TCP scenario, check the exit."""
    env = {"KB_TEST_PORT": str(TEST_PORT), "KB_SCENE": scene}
    with launch("kb_regions.lua", extra_env=env) as running:
        booted = running.wait_marker("KB_BOOTED", timeout=150)
        assert booted is not None, running.result.summary()
        ready = running.wait_marker(ready_marker, timeout=150)
        assert ready is not None, running.result.summary()
        report = asyncio.run(asyncio.wait_for(scenario(TEST_PORT), scenario_timeout))
        result = running.wait(timeout=90)
    boot = result.facts("KB_BOOTED")
    print(f"{scene}:", report)
    print(f"{scene} markers:", [line for line in result.lines if line.startswith("KB_")])
    assert not result.timed_out and result.returncode == 0, result.summary()
    assert result.marker("KB_FAIL") is None and not result.crash_log_touched and not result.error_spots
    assert boot["scene"] == scene and int(boot["slot"]) >= 1, boot
    if home_before is not None:
        assert int(boot["home"]) == home_before, "every launch starts on the slot that was active before the test"
    cleaned = result.facts("KB_CLEANED")
    assert int(cleaned["deleted"]) == 1 and int(cleaned["active"]) == int(boot["home"]), cleaned
    return int(boot["slot"]), int(boot["home"]), result, report


@pytest.mark.e2e
@pytest.mark.timeout(1800)
def test_a_game_at_its_title_is_started_by_region_start():
    """S4 / MG2: NEW GAME and CONTINUE from the title, driven by region.start, KB_REGION_REPEATS times each."""
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    before = save_files(pf.game)
    home = None
    clean = False
    try:
        for scene, detail in (("start_new", "new"), ("start_continue", "continue")):
            for _ in range(REPEATS):
                slot, home, result, report = _launch(scene, "KB_TITLE", lambda port: _start(port, detail), 300, home)
                title = result.facts("KB_TITLE")
                assert title["hasSave"] == ("true" if detail == "continue" else "false"), title
                assert result.marker("KB_INGAME") is not None, "the overworld appeared after the start"
                if detail == "continue":
                    assert result.facts("KB_SAVED")["ok"] == "true"
                    assert report["loaded"] == {"fresh": False, "pair": True}
                else:
                    assert report["first_ui"] in ("text", "mirror", "naming"), report["first_ui"]
                assert report["after"]["started"] is True
                mid = without_slot(save_files(pf.game), slot)
                assert mid == before, "the install's own saves untouched (names, sizes, times)"
        clean = True
    finally:
        if not clean:
            _cleanup_run()
    assert save_files(pf.game) == before, "the test slots' files are gone"


@pytest.mark.e2e
@pytest.mark.timeout(600)
def test_park_and_resume_leave_the_hidden_player_where_it_stood():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    before = save_files(pf.game)
    clean = False
    try:
        slot, home, result, report = _launch("park", "KB_SERVING", _park, 240, None)
        serving = result.facts("KB_SERVING")
        assert serving["map"] and int(serving["x"]) >= 0, serving
        assert report["cell_unchanged"] and report["away"] and report["blocked"] == "script"
        clean = True
    finally:
        if not clean:
            _cleanup_run()
    assert save_files(pf.game) == before, "the test slot's files are gone and the rest untouched"
