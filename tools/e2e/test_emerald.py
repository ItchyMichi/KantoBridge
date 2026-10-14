"""MG7 end to end, on the real engine (docs/specs/GEN3_BRIDGE_PLAN.md §8.3, phase G3A): the Gen 3 adapter
on the pod's Emerald import. The driver is drivers/kb_emerald.lua; every scene runs on a fresh empty save slot
labelled KB_EMERALD_TEST, deleted again at the end (the install's own saves are never touched: their listing is
the same before and after).

  field (one launch): hello.ok names emerald and family gen3; the snapshot is in game in Littleroot on the
      driver's cell; game.status says fresh; a step aside and then up into the house wall beside the door (when
      the driver found one) is answered player.blocked with the engine's reason; a step down is answered player.pos one cell down; the
      game then saves itself (the driver) and save.begin arrives; two steps up walk into the door and a warp
      takes the player off the town map; region.park holds the player (a step is refused "script") and
      region.resume releases it.
  sign (G3B, one launch): below one of Littleroot's signs, act.interact reads it (act.result kind sign); its
      script's text box opens as a ui.open text, is advanced page by page with ui.advance (the last page once
      the script's waitbuttonpress is armed) and closes; the player then walks again.
  story (G3C, one launch): the snapshot carries the town's changed cells; the driver changes one cell
      (Field.setMetatile) and story.cells reports it, puts it back and story.cells reports the map's own
      metatile, then sets one object's hide flag and story.toggles hides O<localId> while story.flags lists
      a flag set; the markers say which cell and which object.
  start (two launches): at the title with the slot saved, region.start CONTINUEs (act.result detail continue,
      game.status saved and in game, save.loaded fresh false); with the slot empty it starts NEW GAME (detail
      new), and (G3B) Birch's speech is answered through the UI as the client would (its text advanced, BOY
      picked, the name KB given on the naming screen, YES to it) until the new game is in the field.

Every message passes its schema (strict). Output: types, slot numbers, map symbols, cell numbers, counts and
booleans only.
"""

import asyncio
import os
import time

import pytest

from fake_minecraft import FakeMinecraft
from run_gen1recomp import INSTALL, launch, preflight

TEST_PORT = 27220
GAME = "emerald"
TOWN = "EM_LITTLEROOT_TOWN"


def save_files():
    """{name: (size, mtime_ns)} of saves/emerald/ (a listing; nothing is opened)."""
    folder = INSTALL / "saves" / GAME
    if not folder.is_dir():
        return {}
    return {n: ((folder / n).stat().st_size, (folder / n).stat().st_mtime_ns) for n in sorted(os.listdir(folder))}


def without_slot(files, slot):
    return {n: v for n, v in files.items() if not (n == f"slot{slot}.lua" or n.startswith(f"slot{slot}.lua."))}


async def find_or_wait(mc, types, timeout, since=0, pred=None):
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
        assert left > 0, f"no {'/'.join(wanted)} within {timeout} s; seen since: {seen_summary(mc, since)}"
        try:
            if await mc.next(min(left, 1.0)) is None:
                raise ConnectionError(f"connection closed while waiting for {'/'.join(wanted)}")
        except asyncio.TimeoutError:
            pass


def seen_summary(mc, since):
    """The types of the messages received since `since`, with cells and reasons for the player's (ids only)."""
    out = []
    for m in [m for m in mc.received[since:] if m["type"] not in ("pong", "npc.move", "npc.face")][-40:]:
        d = m.get("data") or {}
        if m["type"] in ("player.pos", "player.blocked", "player.state", "warp"):
            out.append((m["type"], d.get("map"), d.get("x"), d.get("y"), d.get("kind"), d.get("reason"), d.get("mode"),
                        d.get("phase"), d.get("n")))
        else:
            out.append(m["type"])
    return out


def errors(mc):
    return [m["data"]["code"] for m in mc.received if m["type"] == "error"]


async def connect(port):
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=30)
    ok = (await mc.hello())["data"]
    snap = (await mc.expect("snapshot", timeout=15))["data"]
    status = (await mc.expect("game.status", timeout=15))["data"]
    return mc, ok, snap, status


async def _field(port, serving):
    x, y, wall = int(serving["x"]), int(serving["y"]), serving["wall"]
    mc, ok, snap, status = await connect(port)
    report = {"hello": (ok.get("game"), ok.get("family")), "status": (status["playthrough"], status["started"])}
    assert (ok.get("game"), ok.get("family")) == (GAME, "gen3"), ok
    assert snap["ingame"] is True
    assert (snap["player"]["map"], snap["player"]["x"], snap["player"]["y"]) == (TOWN, x, y), snap["player"]
    assert (status["playthrough"], status["started"], status["ingame"]) == ("fresh", True, True), status

    if wall in ("left", "right"):
        # one step aside, a step up into the wall (the tryMove verdict's reason), and back
        since = len(mc.received)
        await mc.send("move.step", {"dir": wall, "n": 11})
        aside = await find_or_wait(mc, ("player.pos", "player.blocked"), 20, since, lambda m: m["data"].get("n") == 11)
        assert aside["type"] == "player.pos", aside
        await mc.send("move.step", {"dir": "up", "n": 12})
        answer = await find_or_wait(mc, ("player.pos", "player.blocked"), 20, since, lambda m: m["data"].get("n") == 12)
        report["wall"] = (answer["type"], answer["data"].get("reason"))
        assert answer["type"] == "player.blocked" and answer["data"]["reason"] in ("tile", "entity", "bounds"), answer
        back = "right" if wall == "left" else "left"
        await mc.send("move.step", {"dir": back, "n": 13})
        home = await find_or_wait(mc, ("player.pos", "player.blocked"), 20, since, lambda m: m["data"].get("n") == 13)
        assert home["type"] == "player.pos" and (home["data"]["x"], home["data"]["y"]) == (x, y), home

    # (n numbers a request so its answer can be matched; each move.step is one step)
    since = len(mc.received)
    await mc.send("move.step", {"dir": "down", "n": 2})
    pos = await find_or_wait(mc, ("player.pos", "player.blocked"), 20, since, lambda m: m["data"].get("n") == 2)
    report["down"] = (pos["type"], pos["data"].get("map"), pos["data"].get("x"), pos["data"].get("y"))
    assert pos["type"] == "player.pos" and (pos["data"]["map"], pos["data"]["x"], pos["data"]["y"]) == (TOWN, x, y + 1), pos

    begin = (await find_or_wait(mc, "save.begin", 60, since))["data"]
    report["save"] = begin["reason"]
    await mc.send("save.done", {"save": begin["save"], "ok": True})

    since = len(mc.received)
    await mc.send("move.step", {"dir": "up", "n": 3})
    await mc.send("move.step", {"dir": "up", "n": 4})
    try:
        warp = await find_or_wait(mc, "warp", 30, since)
    except AssertionError as exc:
        st = (await mc.request("debug.status", {}, reply_type="debug.status", timeout=10))["data"]
        keys = ("mode", "topState", "ingame", "steps")
        facts = {k: st.get(k) for k in keys}
        facts["move"] = {k: v for k, v in st.items() if k.startswith("move")}
        raise AssertionError(f"{exc}; status {facts}") from None
    report["warp"] = (warp["data"]["phase"], warp["data"]["kind"])
    arrived = await find_or_wait(mc, "player.pos", 30, since, lambda m: m["data"]["map"] != TOWN)
    report["arrived"] = arrived["data"]["map"]

    await asyncio.sleep(2)  # the door's arrival settles
    cell = (await mc.request("resync", reply_type="snapshot", timeout=10))["data"]["player"]
    parked = (await mc.request("region.park", {"region": "hoenn"}, reply_type="act.result", timeout=30))["data"]
    report["park"] = parked
    assert parked == {"ok": True, "kind": "park"}, parked
    since = len(mc.received)
    await mc.send("move.step", {"dir": "down", "n": 1})
    blocked = await find_or_wait(mc, "player.blocked", 20, since)
    report["parked_step"] = blocked["data"]["reason"]
    assert blocked["data"]["reason"] == "script"
    resumed = (await mc.request("region.resume", {"region": "hoenn", "to": cell}, reply_type="act.result",
                                timeout=30))["data"]
    report["resume"] = resumed
    assert resumed == {"ok": True, "kind": "resume"}, resumed

    report["errors"] = errors(mc)
    report["problems"] = len(mc.conn.problems)
    assert report["errors"] == [] and report["problems"] == 0
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return report


async def _start(port, detail):
    mc, ok, snap, status = await connect(port)
    report = {"status_at_title": (status["playthrough"], status["started"], status["ingame"])}
    assert snap["ingame"] is False and status["playthrough"] == "title"
    sent = await mc.send("region.start", {"region": "hoenn"})
    since = len(mc.received)
    result = (await mc.expect("act.result", timeout=90, re=sent["seq"]))["data"]
    report["result"] = result
    assert result == {"ok": True, "kind": "start", "detail": detail}, result
    if detail == "new":
        kinds, ingame = await _answer_birch(mc, since, 540)
        report["speech"] = kinds
        report["after"] = (ingame["playthrough"], ingame["ingame"], ingame.get("name") == "".join(NAME))
        assert kinds.get("text", 0) >= 3 and kinds.get("choice", 0) >= 2 and kinds.get("naming") == 1, kinds
        assert ingame["playthrough"] == "fresh" and ingame.get("name") == "".join(NAME), ingame
    if detail == "continue":
        started = (await find_or_wait(mc, "game.status", 90, since, lambda m: m["data"]["started"] is True))["data"]
        report["after"] = (started["playthrough"], started["ingame"])
        assert (started["playthrough"], started["ingame"]) == ("saved", True)
        loaded = (await find_or_wait(mc, "save.loaded", 60, since))["data"]
        report["loaded"] = loaded["fresh"]
        assert loaded["fresh"] is False
    report["errors"] = errors(mc)
    report["problems"] = len(mc.conn.problems)
    assert report["errors"] == [] and report["problems"] == 0
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return report


async def _sign(port, serving):
    x, y, sx, sy = int(serving["x"]), int(serving["y"]), int(serving["sx"]), int(serving["sy"])
    mc, ok, snap, status = await connect(port)
    assert (snap["player"]["map"], snap["player"]["x"], snap["player"]["y"]) == (TOWN, x, y), snap["player"]
    report = {}
    since = len(mc.received)
    result = (await mc.request("act.interact", {"map": TOWN, "x": sx, "y": sy}, reply_type="act.result",
                               timeout=20))["data"]
    report["interact"] = (result.get("ok"), result.get("kind"), result.get("reason"))
    assert result.get("ok") is True and result.get("kind") == "sign", result
    text = (await find_or_wait(mc, "ui.open", 20, since, lambda m: m["data"]["kind"] == "text"))["data"]
    report["text"] = (len(text["pages"]), sum(len(p) for p in text["pages"]))
    assert text["pages"] and text["pages"][0] and text["pages"][0][0], "a sign says something"
    for _ in range(len(text["pages"]) + 1):  # one press per stop; the extra one dies with the box
        await mc.send("ui.advance", {"ui": text["ui"]})
    await find_or_wait(mc, "ui.close", 30, since, lambda m: m["data"]["ui"] == text["ui"])
    # the field is the player's again: a step down
    since = len(mc.received)
    await mc.send("move.step", {"dir": "down", "n": 21})
    pos = await find_or_wait(mc, ("player.pos", "player.blocked"), 20, since, lambda m: m["data"].get("n") == 21)
    report["after"] = (pos["type"], pos["data"].get("y"))
    assert pos["type"] == "player.pos" and pos["data"]["y"] == y + 1, pos
    report["errors"] = errors(mc)
    report["problems"] = len(mc.conn.problems)
    assert report["errors"] == [] and report["problems"] == 0
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return report


async def _story(port, serving):
    mc, ok, snap, status = await connect(port)
    report = {"snapshot_cells": len(snap.get("cells") or []), "has_cells": "cells" in snap}
    assert "cells" in snap, "Gen 3's snapshot carries its cells"
    since = len(mc.received)
    first = await find_or_wait(mc, "story.cells", 30, since)
    report["cell"] = [(c["x"], c["y"], c["metatile"]) for c in first["data"]["cells"]]
    assert first["data"]["map"] == TOWN
    after = mc.received.index(first) + 1
    back = await find_or_wait(mc, "story.cells", 30, after)
    report["back"] = [(c["x"], c["y"], c["metatile"]) for c in back["data"]["cells"]]
    toggle = (await find_or_wait(mc, "story.toggles", 30, since, lambda m: m["data"]["map"] == TOWN))["data"]
    report["toggle"] = (toggle["map"], toggle["obj"], toggle["visible"])
    flags = await find_or_wait(mc, "story.flags", 30, since, lambda m: len(m["data"]["set"]) > 0)
    report["flags_set"] = len(flags["data"]["set"])
    report["errors"] = errors(mc)
    report["problems"] = len(mc.conn.problems)
    assert report["errors"] == [] and report["problems"] == 0
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return report


NAME = ["K", "B"]


async def _answer_birch(mc, since, timeout):
    """Answers Birch's speech through the UI, as the client does, until the new game reports in game: every
    text box advanced, BOY, the name KB, YES. Returns the counts of the UI kinds seen (no text)."""
    answered, kinds = set(), {}
    deadline = time.monotonic() + timeout
    seen = since
    while True:
        while seen < len(mc.received):
            m = mc.received[seen]
            seen += 1
            if m["type"] == "game.status" and m["data"].get("ingame") is True:
                return kinds, m["data"]
            if m["type"] != "ui.open" or m["data"]["ui"] in answered:
                continue
            d = m["data"]
            answered.add(d["ui"])
            kinds[d["kind"]] = kinds.get(d["kind"], 0) + 1
            if d["kind"] == "text":
                for _ in range(len(d["pages"]) + 1):
                    await mc.send("ui.advance", {"ui": d["ui"]})
            elif d["kind"] == "choice":
                await mc.send("ui.pick", {"ui": d["ui"], "index": 0})  # BOY; YES to the name
            elif d["kind"] == "naming":
                await mc.send("ui.name", {"ui": d["ui"], "glyphs": NAME})
        left = deadline - time.monotonic()
        assert left > 0, f"the speech did not end within {timeout} s: {kinds}"
        try:
            if await mc.next(min(left, 1.0)) is None:
                raise ConnectionError("connection closed during the speech")
        except asyncio.TimeoutError:
            pass


def _cleanup_run():
    with launch("kb_emerald.lua", game=GAME, extra_env={"KB_TEST_PORT": str(TEST_PORT), "KB_SCENE": "cleanup"},
                echo=False) as running:
        return running.wait(timeout=180)


def _launch(scene, ready_marker, scenario, timeout, extra=0):
    env = {"KB_TEST_PORT": str(TEST_PORT), "KB_SCENE": scene, "KB_DRIVER_EXTRA": str(extra)}
    with launch("kb_emerald.lua", game=GAME, extra_env=env) as running:
        booted = running.wait_marker("KB_BOOTED", timeout=240)
        assert booted is not None, running.result.summary()
        ready = running.wait_marker(ready_marker, timeout=240)
        assert ready is not None, running.result.summary()
        facts = running.result.facts(ready_marker)
        report = asyncio.run(asyncio.wait_for(scenario(TEST_PORT, facts), timeout))
        result = running.wait(timeout=120 + extra)
    print(f"{scene}:", report)
    print(f"{scene} markers:", [line for line in result.lines if line.startswith("KB_")])
    assert not result.timed_out and result.returncode == 0, result.summary()
    assert result.marker("KB_FAIL") is None and not result.crash_log_touched and not result.error_spots
    cleaned = result.facts("KB_CLEANED")
    assert int(cleaned["deleted"]) == 1, cleaned
    return result, report


def _skip_without_emerald():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    from run_gen1recomp import kb_games
    if GAME not in kb_games.detect(INSTALL):
        pytest.skip("no Emerald import in the test install")


@pytest.mark.e2e
@pytest.mark.timeout(900)
def test_the_hidden_emerald_player_is_followed_and_driven():
    _skip_without_emerald()
    before = save_files()
    clean = False
    try:
        result, report = _launch("field", "KB_SERVING", _field, 400)
        assert result.facts("KB_SAVED")["ok"] == "true"
        clean = True
    finally:
        if not clean:
            _cleanup_run()
    assert save_files() == before, "the test slot's files are gone and the rest untouched"


@pytest.mark.e2e
@pytest.mark.timeout(900)
def test_an_emerald_sign_is_read_through_the_ui():
    _skip_without_emerald()
    before = save_files()
    clean = False
    try:
        result, report = _launch("sign", "KB_SERVING", _sign, 300)
        clean = True
    finally:
        if not clean:
            _cleanup_run()
    assert save_files() == before, "the test slot's files are gone and the rest untouched"


@pytest.mark.e2e
@pytest.mark.timeout(900)
def test_emeralds_story_cells_and_toggles_reach_the_client():
    _skip_without_emerald()
    before = save_files()
    clean = False
    try:
        result, report = _launch("story", "KB_SERVING", _story, 300)
        cell, flag = result.facts("KB_CELL"), result.facts("KB_FLAG")
        x, y = int(cell["x"]), int(cell["y"])
        assert report["cell"] == [(x, y, int(cell["metatile"]))], (report, cell)
        assert report["back"] == [(x, y, int(cell["base"]))], (report, cell)
        assert report["toggle"] == (TOWN, f"O{int(flag['lid'])}", False), (report, flag)
        clean = True
    finally:
        if not clean:
            _cleanup_run()
    assert save_files() == before, "the test slot's files are gone and the rest untouched"


@pytest.mark.e2e
@pytest.mark.timeout(1500)
def test_emerald_starts_from_its_title():
    _skip_without_emerald()
    before = save_files()
    clean = False
    try:
        result, report = _launch("title_continue", "KB_TITLE", lambda port, facts: _start(port, "continue"), 300)
        assert result.facts("KB_TITLE")["hasSave"] == "true"
        result, report = _launch("title_new", "KB_TITLE", lambda port, facts: _start(port, "new"), 600, extra=300)
        assert result.facts("KB_TITLE")["hasSave"] == "false"
        clean = True
    finally:
        if not clean:
            _cleanup_run()
    assert save_files() == before, "the test slots' files are gone and the rest untouched"
