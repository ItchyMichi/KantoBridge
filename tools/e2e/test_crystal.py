"""MG9 end to end, on the real engine (docs/specs/GEN2_BRIDGE_PLAN.md §10, phase G2A): the Gen 2 adapter on the
pod's Crystal import. The driver is drivers/kb_crystal.lua; every scene runs on a fresh empty save slot labelled
KB_CRYSTAL_TEST, deleted again at the end (the install's own saves are never touched: their listing is the same
before and after). The runner pins a Gen 2 game's clock at noon (run_gen1recomp.py, POKEPORT_GOLD_HOUR).

  field (one launch): hello.ok names crystal, family gen2 and its compat (251 species, 251 moves); the snapshot is
      in game in G2_PLAYERS_HOUSE_2F (every Gen 2 map id carries the prefix G2_, J4) on the driver's cell;
      game.status says fresh; the walk the driver found to a cell beside something solid is answered player.pos step
      by step, and the step into it player.blocked with the engine's reason; once the player stands off the start
      cell the game saves itself (the driver) and save.begin arrives; the walk to the stairs and the step onto them
      warp the player to G2_PLAYERS_HOUSE_1F; region.park holds the player (a step is refused "script") and
      region.resume releases it.
  start (two launches): at the title with the slot saved, region.start CONTINUEs (act.result detail continue,
      game.status saved and in game, save.loaded fresh false, the snapshot back in G2_PLAYERS_HOUSE_2F); with the slot
      empty it starts NEW GAME (detail new) from the real boot chain (POKEPORT_BOOT_CINEMA=1): Oak's speech runs, its
      clock already answered by the adapter (the clock screen never comes up), its other screens answered by the
      driver until the UI comes with G2B, and game.status reports the new game fresh and in game in
      G2_PLAYERS_HOUSE_2F.

Every message passes its schema (strict). Output: types, slot numbers, map symbols, cell numbers, directions, counts
and booleans only.
"""

import asyncio
import os
import time

import pytest

from fake_minecraft import FakeMinecraft
from run_gen1recomp import INSTALL, launch, preflight

TEST_PORT = 27222
GAME = "crystal"
REGION = "johto"
PREFIX = "G2_"
HOUSE = PREFIX + "PLAYERS_HOUSE_2F"
DOWNSTAIRS = PREFIX + "PLAYERS_HOUSE_1F"
DIRS = {"U": "up", "D": "down", "L": "left", "R": "right"}


def save_files():
    """{name: (size, mtime_ns)} of saves/crystal/ (a listing; nothing is opened)."""
    folder = INSTALL / "saves" / GAME
    if not folder.is_dir():
        return {}
    return {n: ((folder / n).stat().st_size, (folder / n).stat().st_mtime_ns) for n in sorted(os.listdir(folder))}


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


async def _answer_saves(mc):
    """Answers every save.begin not answered yet with save.done (as the client does once Minecraft has saved);
    returns the reasons of all answered so far."""
    answered = mc.__dict__.setdefault("kb_saves", {})
    for m in list(mc.received):
        if m["type"] == "save.begin" and m["data"]["save"] not in answered:
            answered[m["data"]["save"]] = m["data"]["reason"]
            await mc.send("save.done", {"save": m["data"]["save"], "ok": True})
    return list(answered.values())


async def _step(mc, letter, n, timeout=20):
    """One move.step (n numbers it); returns its answer, player.pos or player.blocked. A save the game began
    meanwhile is answered at once."""
    since = len(mc.received)
    await mc.send("move.step", {"dir": DIRS[letter], "n": n})
    answer = await find_or_wait(mc, ("player.pos", "player.blocked"), timeout, since,
                                lambda m: m["data"].get("n") == n)
    await _answer_saves(mc)
    return answer


async def _walk(mc, path, first_n):
    """Walks a path of letters one step at a time; every step must be answered player.pos. Returns the last
    answer (or None for an empty path) and the next free request number."""
    answer, n = None, first_n
    for letter in path:
        answer = await _step(mc, letter, n)
        assert answer["type"] == "player.pos", (letter, n, answer, seen_summary(mc, max(0, len(mc.received) - 20)))
        n += 1
    return answer, n


def _path(value):
    """A driver path: "-" is the empty walk, "none" no walk at all (None)."""
    if value == "none":
        return None
    return "" if value == "-" else value


async def _field(port, serving):
    x, y = int(serving["x"]), int(serving["y"])
    wall, bump = _path(serving["wall"]), serving["bump"]
    stairs, into = _path(serving["stairs"]), serving["into"]
    assert PREFIX + serving["map"] == HOUSE, serving
    mc, ok, snap, status = await connect(port)
    report = {"hello": (ok.get("game"), ok.get("family"), (ok.get("compat") or {}).get("maxDex"),
                        (ok.get("compat") or {}).get("maxMove")),
              "status": (status["playthrough"], status["started"], status["ingame"])}
    assert (ok.get("game"), ok.get("family")) == (GAME, "gen2"), ok
    assert (ok.get("compat") or {}).get("maxDex") == 251 and (ok.get("compat") or {}).get("maxMove") == 251, ok
    assert snap["ingame"] is True
    report["snapshot"] = (snap["player"]["map"], snap["player"]["x"], snap["player"]["y"])
    assert (snap["player"]["map"], snap["player"]["x"], snap["player"]["y"]) == (HOUSE, x, y), snap["player"]
    assert (status["playthrough"], status["started"], status["ingame"]) == ("fresh", True, True), status
    since_connect = len(mc.received)
    n = 1

    # the walk to a cell beside something solid, and the step into it (the engine's movement.collision reason)
    report["wall"] = (wall, bump)
    if wall is not None and bump in DIRS:
        last, n = await _walk(mc, wall, n)
        answer = await _step(mc, bump, n)
        n += 1
        report["bump"] = (answer["type"], answer["data"].get("reason"), answer["data"].get("map"))
        assert answer["type"] == "player.blocked" and answer["data"]["reason"] in ("tile", "entity", "bounds"), answer
        assert answer["data"]["map"] == HOUSE, answer
        here = (answer["data"]["x"], answer["data"]["y"])
    else:
        here = (x, y)

    # back to the start cell, the way the walk came (the stairs walk starts there)
    back = "".join({"U": "D", "D": "U", "L": "R", "R": "L"}[c] for c in reversed(wall or ""))
    last, n = await _walk(mc, back, n)
    if back:
        assert (last["data"]["x"], last["data"]["y"]) == (x, y), last

    # the game saves itself once the player stands off the start cell (the driver): one step out and back if the
    # walk above never left it
    if not (wall or ""):
        assert stairs, "the driver found a walk to the stairs"
        last, n = await _walk(mc, stairs[0], n)
        last, n = await _walk(mc, {"U": "D", "D": "U", "L": "R", "R": "L"}[stairs[0]], n)
    await find_or_wait(mc, "save.begin", 60, since_connect)
    report["save"] = await _answer_saves(mc)
    assert len(report["save"]) == 1, report["save"]

    # the stairs: the walk, then the step onto the warp; the client follows the hidden player downstairs
    assert stairs is not None and into in DIRS, serving
    report["stairs"] = (len(stairs), into)
    last, n = await _walk(mc, stairs, n)
    since = len(mc.received)
    await mc.send("move.step", {"dir": DIRS[into], "n": n})
    n += 1
    try:
        warp = await find_or_wait(mc, "warp", 30, since)
    except AssertionError as exc:
        st = (await mc.request("debug.status", {}, reply_type="debug.status", timeout=10))["data"]
        facts = {k: st.get(k) for k in ("mode", "topState", "ingame", "steps")}
        facts["move"] = {k: v for k, v in st.items() if k.startswith("move")}
        raise AssertionError(f"{exc}; status {facts}") from None
    report["warp"] = (warp["data"]["phase"], warp["data"]["kind"])
    assert warp["data"]["phase"] == "start", warp
    arrived = await find_or_wait(mc, "player.pos", 30, since, lambda m: m["data"]["map"] != HOUSE)
    report["arrived"] = arrived["data"]["map"]
    assert arrived["data"]["map"] == DOWNSTAIRS, arrived
    await asyncio.sleep(2)  # the arrival settles
    report["warp_messages"] = [(m["data"]["phase"], m["data"]["kind"]) for m in mc.received[since:] if m["type"] == "warp"]
    assert ("arrive", report["warp"][1]) in report["warp_messages"], report["warp_messages"]

    # the region acts: park (a step refused "script"), then resume where the player stands
    cell = (await mc.request("resync", reply_type="snapshot", timeout=10))["data"]["player"]
    assert cell["map"] == DOWNSTAIRS, cell
    parked = (await mc.request("region.park", {"region": REGION}, reply_type="act.result", timeout=30))["data"]
    report["park"] = parked
    assert parked == {"ok": True, "kind": "park"}, parked
    since = len(mc.received)
    await mc.send("move.step", {"dir": "down", "n": n})
    blocked = await find_or_wait(mc, "player.blocked", 20, since)
    report["parked_step"] = blocked["data"]["reason"]
    assert blocked["data"]["reason"] == "script"
    resumed = (await mc.request("region.resume", {"region": REGION, "to": cell}, reply_type="act.result",
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
    report = {"hello": (ok.get("game"), ok.get("family")),
              "status_at_title": (status["playthrough"], status["started"], status["ingame"])}
    assert (ok.get("game"), ok.get("family")) == (GAME, "gen2"), ok
    assert snap["ingame"] is False and status["playthrough"] == "title"
    sent = await mc.send("region.start", {"region": REGION})
    since = len(mc.received)
    result = (await mc.expect("act.result", timeout=90, re=sent["seq"]))["data"]
    report["result"] = result
    assert result == {"ok": True, "kind": "start", "detail": detail}, result
    if detail == "new":
        # Oak's speech runs (the driver answers its screens until G2B); then the new game is in the field
        ingame = (await find_or_wait(mc, "game.status", 600, since, lambda m: m["data"].get("ingame") is True))["data"]
        report["after"] = (ingame["playthrough"], ingame["ingame"], ingame["started"], "name" in ingame)
        assert (ingame["playthrough"], ingame["started"]) == ("fresh", True), ingame
    else:
        started = (await find_or_wait(mc, "game.status", 90, since, lambda m: m["data"]["started"] is True
                                      and m["data"]["ingame"] is True))["data"]
        report["after"] = (started["playthrough"], started["ingame"])
        assert (started["playthrough"], started["ingame"]) == ("saved", True)
        loaded = (await find_or_wait(mc, "save.loaded", 60, since))["data"]
        report["loaded"] = loaded["fresh"]
        assert loaded["fresh"] is False
    player = (await mc.request("resync", reply_type="snapshot", timeout=10))["data"]["player"]
    report["map"] = player["map"]
    assert player["map"] == HOUSE, player
    report["errors"] = errors(mc)
    report["problems"] = len(mc.conn.problems)
    assert report["errors"] == [] and report["problems"] == 0
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return report


def _cleanup_run():
    with launch("kb_crystal.lua", game=GAME, extra_env={"KB_TEST_PORT": str(TEST_PORT), "KB_SCENE": "cleanup"},
                echo=False) as running:
        return running.wait(timeout=180)


def _launch(scene, ready_marker, scenario, timeout, extra=0, cinema=False):
    env = {"KB_TEST_PORT": str(TEST_PORT), "KB_SCENE": scene, "KB_DRIVER_EXTRA": str(extra)}
    if cinema:
        env["POKEPORT_BOOT_CINEMA"] = "1"  # the real boot chain: copyright, splash, intro, title
    with launch("kb_crystal.lua", game=GAME, extra_env=env) as running:
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
    assert result.marker("KB_INITCLOCK") is None, "the clock screen is the adapter's to answer (J5)"
    cleaned = result.facts("KB_CLEANED")
    assert int(cleaned["deleted"]) == 1, cleaned
    return result, report


def _skip_without_crystal():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    from run_gen1recomp import kb_games
    if GAME not in kb_games.detect(INSTALL):
        pytest.skip("no Crystal import in the test install")


@pytest.mark.e2e
@pytest.mark.timeout(900)
def test_the_hidden_crystal_player_is_followed_and_driven():
    _skip_without_crystal()
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
@pytest.mark.timeout(1500)
def test_crystal_starts_from_its_title():
    _skip_without_crystal()
    before = save_files()
    clean = False
    try:
        result, report = _launch("title_continue", "KB_TITLE", lambda port, facts: _start(port, "continue"), 300)
        assert result.facts("KB_TITLE")["hasSave"] == "true"
        result, report = _launch("title_new", "KB_TITLE", lambda port, facts: _start(port, "new"), 700, extra=400,
                                 cinema=True)
        assert result.facts("KB_TITLE")["hasSave"] == "false"
        speech = result.facts("KB_SPEECH")
        assert int(speech["texts"]) >= 3 and int(speech["gender"]) == 1 and int(speech["names"]) >= 1, speech
        field = result.facts("KB_FIELD")
        assert PREFIX + field["map"] == HOUSE and field["clock"] == "0", field
        clean = True
    finally:
        if not clean:
            _cleanup_run()
    assert save_files() == before, "the test slots' files are gone and the rest untouched"
