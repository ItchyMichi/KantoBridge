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
  ui (G2B, one launch, the real boot chain): region.start starts NEW GAME and this test answers Oak's speech
      through the bridge's UI as a player at the client would (text advanced, BOY, the name KB on the naming
      screen; a name pick that comes as a menu gets NEW NAME): the ui.open kinds come as a choice before the naming
      screen, at least three text boxes and exactly one naming screen; no mirror of the clock screen (the adapter
      answers it, J5) and game.status names the new game KB. Then the walk to the stairs (the driver's) takes the
      player down to G2_PLAYERS_HOUSE_1F, where Mom's scene opens text boxes and a yes/no (the phone) that this test
      answers, while her weekday wheel is the adapter's (never a mirror; the driver fails the run if it sticks);
      once the field has stood idle the driver's walk leads beside Mom, act.interact talks to her (act.result
      npc, a text ui.open, its ui.updates, its ui.close), and act.button START opens the start menu as a menu
      UI without its QUIT and MODS rows, which ui.back closes.

Every message passes its schema (strict). Output: types, slot numbers, map symbols, cell numbers, directions, counts
and booleans only.
"""

import asyncio
import os
import time

import pytest

from fake_minecraft import FakeMinecraft
from run_gen1recomp import INSTALL, launch, preflight
import foreign  # noqa: E402  (FP8: KB_E2E_FOREIGN=1, an all-foreign team)

TEST_PORT = 27222
GAME = "crystal"
REGION = "johto"
PREFIX = "G2_"
HOUSE = PREFIX + "PLAYERS_HOUSE_2F"
DOWNSTAIRS = PREFIX + "PLAYERS_HOUSE_1F"
TOWN = PREFIX + "NEW_BARK_TOWN"
LAB = PREFIX + "ELMS_LAB"
STARTER_FLAG = "E26"  # EVENT_GOT_A_POKEMON_FROM_ELM, the same number on Gold and Crystal (PROGRESS.md G2-S4)
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


async def _walk_on(mc, path, n, tries=12):
    """Walks a path of letters; a step refused because someone stands in the way or a script runs is tried again
    after a moment (people wander), any other refusal fails. Returns the next free request number."""
    for letter in path:
        for attempt in range(tries):
            answer = await _step(mc, letter, n)
            n += 1
            if answer["type"] == "player.pos":
                break
            reason = answer["data"].get("reason")
            assert reason in ("entity", "script", "busy") and attempt < tries - 1, (
                letter, answer, seen_summary(mc, max(0, len(mc.received) - 20)))
            await asyncio.sleep(0.6)
    return n


async def _take_warp(mc, into, n, from_map):
    """From the cell beside a warp: every press of `into` but the last a step (onto a mat), the last the warp; waits
    for the warp to start and for the player on another map. Returns the next request number and that map."""
    n = await _walk_on(mc, into[:-1], n)
    since = len(mc.received)
    await mc.send("move.step", {"dir": DIRS[into[-1]], "n": n})
    n += 1
    warp = await find_or_wait(mc, "warp", 30, since)
    assert warp["data"]["phase"] == "start", warp
    arrived = await find_or_wait(mc, "player.pos", 30, since, lambda m: m["data"]["map"] != from_map)
    return n, arrived["data"]["map"]


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


NAME = ["K", "B"]
INITCLOCK = "Gen2InitClock"
HIDDEN_ROWS = ("QUIT", "MODS")


async def _answer_ui(mc, since, timeout, until=None, done=None, press_mirrors=()):
    """Answers every UI the hidden game opens, as a player at the client would, until a message matches `until`
    or `done()` holds (asked about once a second): text boxes advanced, a choice index 0 (BOY, YES), a menu index
    0 (NEW NAME, should the name pick come as a menu), the naming screen KB. Mirrors are left alone (their screen
    ids are kept), but for those whose screen is in `press_mirrors` (Elm's starter picture, PicBox): A, about once
    a second while one is open. Returns the kinds in order, the mirrors' screens and the matching message (or
    None)."""
    answered, kinds, mirrors = set(), [], []
    texts = {}  # a text UI's id -> its state as the updates have told it (they carry only what changed)
    pressing = {}  # an open mirror to press -> when it was last pressed
    deadline = time.monotonic() + timeout
    seen = since
    while True:
        while seen < len(mc.received):
            m = mc.received[seen]
            seen += 1
            if until is not None and until(m):
                return kinds, mirrors, m
            # a text box is pressed at each stop, as a player answers the arrow: every page end, every continued
            # line within a page (Gen 2's CONT stops, which a count of pages misses) and a {DONE} box's last press;
            # the bridge reports each as ui.update waiting (the v0.3.85 rule: a press does something now); an
            # update carries only the fields that changed, so the box's state is merged and a press goes out each
            # time waiting turns true
            if m["type"] == "ui.update" and m["data"].get("ui") in texts:
                st = texts[m["data"]["ui"]]
                was = st.get("waiting") is True
                st.update({k: v for k, v in m["data"].items() if k in ("page", "line", "waiting", "done")})
                if st.get("waiting") is True and not was:
                    await mc.send("ui.advance", {"ui": m["data"]["ui"]})
                continue
            if m["type"] == "ui.close":
                pressing.pop(m["data"].get("ui"), None)
            if m["type"] != "ui.open" or m["data"]["ui"] in answered:
                continue
            d = m["data"]
            answered.add(d["ui"])
            kinds.append(d["kind"])
            if d["kind"] == "text":
                texts[d["ui"]] = {}
            elif d["kind"] in ("choice", "menu"):
                await mc.send("ui.pick", {"ui": d["ui"], "index": 0})
            elif d["kind"] == "naming":
                await mc.send("ui.name", {"ui": d["ui"], "glyphs": NAME})
            elif d["kind"] == "mirror":
                mirrors.append(str(d.get("screen")))
                if d.get("screen") in press_mirrors:
                    pressing[d["ui"]] = 0.0
        for ui_id, last in list(pressing.items()):
            if time.monotonic() - last >= 1.0:
                pressing[ui_id] = time.monotonic()
                await mc.send("act.button", {"button": "a", "how": "tap"})
        if done is not None and done():
            return kinds, mirrors, None
        left = deadline - time.monotonic()
        updates = [{k: v for k, v in m["data"].items() if k in ("ui", "page", "line", "waiting", "done", "index")}
                   for m in mc.received[since:] if m["type"] == "ui.update"][-8:]
        opens = [{k: v for k, v in m["data"].items() if k in ("ui", "kind", "stay", "auto", "choice", "instant")}
                 | {"pages": len(m["data"].get("pages") or [])}
                 for m in mc.received[since:] if m["type"] == "ui.open"][-4:]
        assert left > 0, (f"no end within {timeout} s: kinds {kinds}, mirrors {mirrors}; {seen_summary(mc, since)}; "
                          f"opens {opens}; updates {updates}")
        try:
            if await mc.next(min(left, 1.0)) is None:
                raise ConnectionError("connection closed while answering the UI")
        except asyncio.TimeoutError:
            pass


def _counts(kinds):
    return {k: kinds.count(k) for k in sorted(set(kinds))}


async def _new_game_to_mom(mc, running, report):
    """NEW GAME from the title through the UI only: Oak's speech, the bedroom, the stairs and Mom's scene downstairs,
    until the field stands idle after her (the driver's KB_NPC). Returns the next free move.step number."""
    # NEW GAME, Oak's speech answered through the UI
    sent = await mc.send("region.start", {"region": REGION})
    since = len(mc.received)
    result = (await mc.expect("act.result", timeout=90, re=sent["seq"]))["data"]
    report["result"] = result
    assert result == {"ok": True, "kind": "start", "detail": "new"}, result
    kinds, mirrors, ingame = await _answer_ui(
        mc, since, int(os.environ.get("KB_UI_TIMEOUT", "600")),
        until=lambda m: m["type"] == "game.status" and m["data"].get("ingame") is True)
    ingame = ingame["data"]
    report["speech"] = _counts(kinds)
    report["speech_mirrors"] = sorted(set(mirrors))
    assert "choice" in kinds and "naming" in kinds and kinds.index("choice") < kinds.index("naming"), kinds
    assert kinds.count("text") >= 3 and kinds.count("naming") == 1 and kinds.count("menu") <= 1, kinds
    assert INITCLOCK not in mirrors, "the clock screen is the adapter's to answer (J5)"
    report["after"] = (ingame["playthrough"], ingame["ingame"], ingame.get("name") == "".join(NAME))
    assert ingame["playthrough"] == "fresh" and ingame.get("name") == "".join(NAME), report["after"]

    # the walk to the stairs (the driver's, once the new game stands in the field) and down
    assert await asyncio.to_thread(running.wait_marker, "KB_FIELD", 60) is not None, "the driver saw the field"
    field = running.result.facts("KB_FIELD")
    assert PREFIX + field["map"] == HOUSE and field["clock"] == "0", field
    stairs, into = _path(field["stairs"]), field["into"]
    assert stairs is not None and into in DIRS, field
    player = (await mc.request("resync", reply_type="snapshot", timeout=10))["data"]["player"]
    assert player["map"] == HOUSE, player
    n = 1
    last, n = await _walk(mc, stairs, n)
    since = len(mc.received)
    await mc.send("move.step", {"dir": DIRS[into], "n": n})
    n += 1
    warp = await find_or_wait(mc, "warp", 30, since)
    assert warp["data"]["phase"] == "start", warp
    arrived = await find_or_wait(mc, "player.pos", 30, since, lambda m: m["data"]["map"] != HOUSE)
    report["arrived"] = arrived["data"]["map"]
    assert arrived["data"]["map"] == DOWNSTAIRS, arrived

    # Mom's scene is a coord event in front of the stairs (pokecrystal maps/PlayersHouse1F.asm, MeetMom): walked
    # onto one step at a time until it starts (a UI opens or a step is refused by the script), then answered through
    # the UI until the field stands idle after it (the driver's KB_NPC); her weekday wheel is the adapter's
    since = len(mc.received)
    walked = []
    for _ in range(10):
        if any(m["type"] == "ui.open" for m in mc.received[since:]) or len([w for w in walked if w == "pos"]) >= 4:
            break
        answer = await _step(mc, "D", n)
        n += 1
        walked.append("pos" if answer["type"] == "player.pos" else str(answer["data"].get("reason")))
        if answer["type"] == "player.blocked":
            await asyncio.sleep(0.5)  # the arrival's fade, or the scene taking over
    report["mom_walk"] = walked
    print(f"mom walk: {walked}", flush=True)
    kinds, mirrors, _ = await _answer_ui(mc, since, 300, done=lambda: running.result.marker("KB_NPC") is not None)
    report["mom"] = _counts(kinds)
    report["mom_mirrors"] = sorted(set(mirrors))
    report["daywheel"] = running.result.facts("KB_DAYWHEEL")
    assert kinds.count("text") >= 2 and kinds.count("choice") >= 1, kinds
    assert INITCLOCK not in mirrors, "Mom's weekday wheel is the adapter's to answer (J5)"
    return n


async def _ui(port, facts, running):
    mc, ok, snap, status = await connect(port)
    report = {"hello": (ok.get("game"), ok.get("family"))}
    assert (ok.get("game"), ok.get("family")) == (GAME, "gen2"), ok
    assert snap["ingame"] is False and status["playthrough"] == "title"

    n = await _new_game_to_mom(mc, running, report)

    # a person's line, read through the UI
    npc = running.result.facts("KB_NPC")
    path, face = _path(npc["path"]), npc["face"]
    assert path is not None and face in DIRS and npc["x"] != "none", npc
    last, n = await _walk(mc, path, n)
    since = len(mc.received)
    sent = await mc.send("act.interact", {"map": DOWNSTAIRS, "x": int(npc["x"]), "y": int(npc["y"])})
    result = (await mc.expect("act.result", timeout=20, re=sent["seq"]))["data"]
    report["interact"] = (result.get("ok"), result.get("kind"), result.get("reason"))
    assert result.get("ok") is True and result.get("kind") == "npc", result
    text = (await find_or_wait(mc, "ui.open", 20, since, lambda m: m["data"]["kind"] == "text"))["data"]
    talk_kinds, _, closed = await _answer_ui(
        mc, since, 60, until=lambda m: m["type"] == "ui.close" and m["data"]["ui"] == text["ui"])
    updates = sum(1 for m in mc.received[since:] if m["type"] == "ui.update" and m["data"]["ui"] == text["ui"])
    report["talk"] = (len(text["pages"]), updates, _counts(talk_kinds))
    assert text["pages"] and updates >= 1 and closed is not None, report["talk"]

    # the start menu, without QUIT and MODS, closed with B
    await asyncio.sleep(1)  # the talk's script ends and the field stands again
    since = len(mc.received)
    sent = await mc.send("act.button", {"button": "start", "how": "tap"})
    result = (await mc.expect("act.result", timeout=30, re=sent["seq"]))["data"]
    report["start_button"] = (result.get("ok"), result.get("reason"))
    assert result.get("ok") is True, result
    menu = (await find_or_wait(mc, "ui.open", 20, since, lambda m: m["data"]["kind"] == "menu"))["data"]
    rows = [str(item.get("label", "")).strip().upper() for item in menu.get("items") or []]
    hidden = sum(1 for row in rows if row in HIDDEN_ROWS)
    report["start_menu"] = (len(rows), hidden, menu.get("screen"))
    assert rows and hidden == 0, report["start_menu"]
    await mc.send("ui.back", {"ui": menu["ui"]})
    await find_or_wait(mc, "ui.close", 20, since, lambda m: m["data"]["ui"] == menu["ui"])

    report["errors"] = errors(mc)
    report["problems"] = len(mc.conn.problems)
    assert report["errors"] == [] and report["problems"] == 0
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return report


async def _story(port, facts, running):
    mc, ok, snap, status = await connect(port)
    report = {"hello": (ok.get("game"), ok.get("family"))}
    assert (ok.get("game"), ok.get("family")) == (GAME, "gen2"), ok
    assert snap["ingame"] is False and status["playthrough"] == "title"
    since, ball = await _to_starter(mc, running, report)
    # the ball taken: its object off the lab's table, in the story and in the engine's own masks
    obj = "O" + ball["index"]
    await asyncio.sleep(1)  # the next toggle diff (every ten logic steps)
    hidden = [m["data"] for m in mc.received[since:] if m["type"] == "story.toggles" and m["data"]["map"] == LAB]
    report["toggles"] = hidden
    assert {"map": LAB, "obj": obj, "visible": False} in hidden, hidden
    assert await asyncio.to_thread(running.wait_marker, "KB_STARTER", 30) is not None, "the driver saw flag 26"
    assert running.result.facts("KB_STARTER")["masked"] == "true"

    # the story's counters: the map data copied at boot, the swap sites, no block change outside them
    st = (await mc.request("debug.status", reply_type="debug.status", timeout=10))["data"]
    report["story"] = {k: st.get(k) for k in ("storyPristine", "storySites", "storyUnknownBlocks", "storyMapLoads",
                                              "storyBlockChanges", "storyFlagMsgs", "storyToggleMsgs")}
    assert st["storyPristine"] is True and st["storySites"] > 0 and st["storyUnknownBlocks"] == 0, report["story"]
    report["errors"] = errors(mc)
    report["problems"] = len(mc.conn.problems)
    assert report["errors"] == [] and report["problems"] == 0
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return report


async def _to_starter(mc, running, report):
    """A new game through the UI up to Elm's starter: Oak's speech, the house, the town, the lab, a ball, YES, until
    flag 26 goes out. Returns where the ball's messages start and the driver's ball facts."""
    n = await _new_game_to_mom(mc, running, report)

    # the bedroom's decoration callback: the blocks it changed went out as story.blocks for the bedroom, against
    # the map data the world was built from
    bedroom = running.result.facts("KB_BEDROOM")
    sent = [c for m in mc.received if m["type"] == "story.blocks" and m["data"]["map"] == HOUSE
            for c in m["data"]["changes"]]
    report["bedroom"] = (int(bedroom["edits"]), int(bedroom["differ"]), len(sent))
    assert int(bedroom["edits"]) >= 1 and len(sent) == int(bedroom["differ"]), report["bedroom"]

    # out of the house and over to Elm's lab (the driver's walks; people wander, so a blocked step is tried again)
    door = running.result.facts("KB_EXIT")
    path, into = _path(door["path"]), door["into"]
    assert path is not None and into != "none" and all(c in DIRS for c in into), door
    n = await _walk_on(mc, path, n)
    n, arrived = await _take_warp(mc, into, n, DOWNSTAIRS)
    report["town"] = arrived
    assert arrived == TOWN, arrived
    assert await asyncio.to_thread(running.wait_marker, "KB_LAB", 60) is not None, "the driver saw the town idle"
    lab = running.result.facts("KB_LAB")
    path, into = _path(lab["path"]), lab["into"]
    assert path is not None and into != "none" and all(c in DIRS for c in into), lab
    n = await _walk_on(mc, path, n)
    since = len(mc.received)
    n, arrived = await _take_warp(mc, into, n, TOWN)
    report["lab"] = arrived
    assert arrived == LAB, arrived

    # Elm's scene through the UI, until the field stands idle after it (the driver's KB_BALL)
    kinds, mirrors, _ = await _answer_ui(mc, since, 300, done=lambda: running.result.marker("KB_BALL") is not None)
    report["elm"] = _counts(kinds)
    assert kinds.count("text") >= 2, kinds
    ball = running.result.facts("KB_BALL")
    path, face = _path(ball["path"]), ball["face"]
    assert path is not None and face in DIRS and ball["x"] != "none", ball
    n = await _walk_on(mc, path, n)

    # a ball: its picture (A on the mirror), YES, the nickname KB, Elm's directions, until flag 26 goes out
    since = len(mc.received)
    sent = await mc.send("act.interact", {"map": LAB, "x": int(ball["x"]), "y": int(ball["y"])})
    result = (await mc.expect("act.result", timeout=20, re=sent["seq"]))["data"]
    report["interact"] = (result.get("ok"), result.get("kind"), result.get("reason"))
    assert result.get("ok") is True, result
    kinds, mirrors, flags = await _answer_ui(
        mc, since, 300, until=lambda m: m["type"] == "story.flags" and STARTER_FLAG in m["data"]["set"],
        press_mirrors=("PicBox",))
    report["starter"] = _counts(kinds)
    report["starter_mirrors"] = sorted(set(mirrors))
    assert "PicBox" in mirrors and kinds.count("choice") >= 1, (kinds, mirrors)
    return since, ball


CAUGHT_UID = "6b1c2d3e-4f50-4a61-8b72-9c8d0e1f2a3b"
STARTER_UID = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"


async def _battles(port, facts, running):
    """G2F: the starter's party.give and its sync; three held battles the driver starts (a fished wild caught, a
    Route 30 trainer beaten, the first rival lost), each answered as Minecraft would."""
    mc, ok, snap, status = await connect(port)
    report = {"hello": (ok.get("game"), ok.get("family"))}
    assert (ok.get("game"), ok.get("family")) == (GAME, "gen2"), ok
    await _to_starter(mc, running, report)

    # the starter's gift, with Gen 2's details; a party.sync that names it confirms it
    give = (await find_or_wait(mc, "party.give", 60, 0))["data"]
    report["give"] = {k: give.get(k) for k in ("source", "dex", "level", "shiny", "gender", "friendship")}
    assert give["source"] == "starter" and give["dex"] in (152, 155, 158) and give["level"] == 5, report["give"]
    assert set(give["dvs"]) == {"atk", "def", "spd", "spc"} and give["gender"] in ("male", "female"), report["give"]
    member = {"uid": STARTER_UID, "give": give["give"], "dex": give["dex"], "level": give["level"],
              "moves": give.get("moves") or [33], "hp": 20, "maxhp": 20, "shiny": give["shiny"],
              "gender": give["gender"], "friendship": give["friendship"]}
    await mc.send("party.sync", {"party": foreign.party([member]), "box": {"count": 0}})

    # a wild Magikarp on the rod, held for Cobblemon and caught there
    since = len(mc.received)
    assert await asyncio.to_thread(running.wait_marker, "KB_WILD", 120) is not None, "the driver's wild"
    assert running.result.facts("KB_WILD")["started"] == "true"
    start = (await find_or_wait(mc, "battle.start", 30, since, lambda m: m["data"]["kind"] == "fish"))["data"]
    wild = start["wild"]
    report["wild"] = (wild["dex"], wild["level"], sorted(wild.get("dvs", {})), start["rules"])
    assert wild["dex"] == 129 and wild["level"] == 10 and "dvs" in wild and start["rules"]["catch"] is True
    await mc.send("battle.result", {"battle": start["battle"], "outcome": "caught",
                                    "caught": {"uid": CAUGHT_UID, "dex": 129, "level": 10}})
    assert await asyncio.to_thread(running.wait_marker, "KB_WILD_DONE", 60) is not None, "the field back"
    done = running.result.facts("KB_WILD_DONE")
    report["wild_done"] = done
    assert done["party"] == "1" and done["money"] == "0", "the catch is Cobblemon's"

    # a Route 30 trainer, beaten: the defeat line and the money line, then its flag
    since = len(mc.received)
    assert await asyncio.to_thread(running.wait_marker, "KB_TRAINER", 120) is not None, "the driver's trainer"
    tr = running.result.facts("KB_TRAINER")
    report["trainer_at"] = tr
    assert tr["x"] != "none", tr
    # its challenge line first (a text box, advanced as a player would), then the battle
    kinds, _, found = await _answer_ui(mc, since, 120, until=lambda m: m["type"] == "battle.start")
    report["challenge"] = _counts(kinds)
    start = found["data"]
    assert start["kind"] in ("trainer", "scripted"), start["kind"]
    team = start["team"]
    report["trainer"] = (start["kind"], start["trainer"]["cls"], len(team), [sorted(m["dvs"]) for m in team])
    assert all(m["dvs"] == {"atk": 9, "def": 8, "spd": 8, "spc": 8} for m in team), "a trainer's DVs"
    since = len(mc.received)
    await mc.send("battle.result", {"battle": start["battle"], "outcome": "win"})
    kinds, _, _ = await _answer_ui(mc, since, 180, done=lambda: running.result.marker("KB_TRAINER_DONE") is not None)
    done = running.result.facts("KB_TRAINER_DONE")
    report["trainer_done"] = (done, _counts(kinds))
    assert done["beaten"] == "true" and int(done["money"]) > 0 and kinds.count("text") >= 2, report["trainer_done"]

    # the first rival: BATTLETYPE_CANLOSE, lost in Cobblemon, and the story goes on where it stands (the driver starts
    # it on the frame the trainer's battle ends, so its battle.start may already be here when KB_TRAINER_DONE is seen)
    assert await asyncio.to_thread(running.wait_marker, "KB_RIVAL", 120) is not None, "the driver's rival"
    assert running.result.facts("KB_RIVAL")["started"] == "true"
    early = [m for m in mc.received[since:] if m["type"] == "battle.start" and m["data"].get("kind") == "rival"]
    if early:
        start = early[0]["data"]
    else:
        _, _, found = await _answer_ui(mc, len(mc.received), 120, until=lambda m: m["type"] == "battle.start")
        start = found["data"]
    assert start["kind"] == "rival", start["kind"]
    report["rival"] = (start["trainer"]["cls"], len(start["team"]))
    since = len(mc.received)
    await mc.send("battle.result", {"battle": start["battle"], "outcome": "lose"})
    kinds, _, _ = await _answer_ui(mc, since, 120, done=lambda: running.result.marker("KB_RIVAL_DONE") is not None)
    done = running.result.facts("KB_RIVAL_DONE")
    report["rival_done"] = done
    assert done["outcome"] == "lose" and done["same_map"] == "true" and done["money"] == "0", "no whiteout"

    st = (await mc.request("debug.status", reply_type="debug.status", timeout=10))["data"]
    report["counts"] = {k: st.get(k) for k in sorted(st) if k.startswith(("battle", "party")) and
                        isinstance(st.get(k), (int, bool))}
    report["errors"] = errors(mc)
    report["problems"] = len(mc.conn.problems)
    assert report["errors"] == [] and report["problems"] == 0
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return report


ROUTE_UID = "3c4d5e6f-7081-4a92-8ba3-c4d5e6f70819"
ROUTE_MAP = "KB_ROUTE_KANTO_JOHTO_TEST_TO"
CHERRYGROVE = PREFIX + "CHERRYGROVE_CITY"


async def _route(port, serving):
    """G2H: the Johto end of the test pass on the real engine. The driver stands the hidden player on the exit cell
    (Cherrygrove City's, gen1-bridge/data/routes.json's kanto_johto_test); the test walks the clearing into the route
    map (a script warp onto x1), gives an item and fights a zone trainer there as the server would (route.give,
    route.battle, answered as Minecraft would), finds the lower row closed, goes home through A, then parks the game
    and resumes it onto the route map."""
    x, y = int(serving["x"]), int(serving["y"])
    mc, ok, snap, status = await connect(port)
    report = {"hello": (ok.get("game"), ok.get("family"))}
    assert (snap["player"]["map"], snap["player"]["x"], snap["player"]["y"]) == (CHERRYGROVE, x, y), snap["player"]
    # the driver's Pokemon: confirmed with a party.sync when the bridge offers it as a gift
    await asyncio.sleep(2)
    give = next((m["data"] for m in mc.received if m["type"] == "party.give"), None)
    report["give"] = None if give is None else give.get("source")
    if give is not None:
        member = {"uid": ROUTE_UID, "give": give["give"], "dex": give["dex"], "level": give["level"],
                  "moves": give.get("moves") or [33], "hp": 30, "maxhp": 30}
        await mc.send("party.sync", {"party": foreign.party([member]), "box": {"count": 0}})
    # four steps left through the clearing; the last lands on the trigger
    since = len(mc.received)
    for n in range(1, 5):
        await mc.send("move.step", {"dir": "left", "n": n})
        step = await find_or_wait(mc, ("player.pos", "player.blocked"), 20, since, lambda m, n=n: m["data"].get("n") == n)
        assert step["type"] == "player.pos", (n, step["data"])
    arrive = (await find_or_wait(mc, "warp", 60, since, lambda m: m["data"]["phase"] == "arrive"))["data"]
    report["in"] = (arrive["kind"], arrive["to"])
    assert arrive["kind"] == "script" and arrive["to"] == {"map": ROUTE_MAP, "x": 1, "y": 0, "facing": "right"}, report
    await asyncio.sleep(1)
    # a ball's item, as the server gives it: the bag, then the found line (a text box, advanced)
    since = len(mc.received)
    await mc.send("route.give", {"route": "kanto_johto_test", "id": "test-ball-1", "item": "POTION"})
    kinds, _, found = await _answer_ui(mc, since, 60, until=lambda m: m["type"] == "route.result"
                                       and m["data"].get("id") == "test-ball-1")
    report["give_result"] = (found["data"], _counts(kinds))
    assert found["data"] == {"op": "give", "ok": True, "route": "kanto_johto_test", "id": "test-ball-1"}, report
    # a zone trainer: Crystal's YOUNGSTER with the request's party, held for Cobblemon, won there
    since = len(mc.received)
    await mc.send("route.battle", {"route": "kanto_johto_test", "id": "test-trainer-1", "class": "YOUNGSTER",
                                   "party": [{"species": "PIDGEY", "level": 4}, {"species": "SENTRET", "level": 5}]})
    _, _, found = await _answer_ui(mc, since, 60, until=lambda m: m["type"] == "battle.start" or (
        m["type"] == "route.result" and m["data"].get("id") == "test-trainer-1"))
    assert found["type"] == "battle.start", found["data"]
    start = found["data"]
    report["battle"] = (start["kind"], start["trainer"]["cls"], [(m["dex"], m["level"]) for m in start["team"]])
    assert start["trainer"]["cls"] == "YOUNGSTER" and [(m["dex"], m["level"]) for m in start["team"]] == [(16, 4), (161, 5)], report
    since = len(mc.received)
    await mc.send("battle.result", {"battle": start["battle"], "outcome": "win"})
    kinds, _, found = await _answer_ui(mc, since, 120, until=lambda m: m["type"] == "route.result"
                                       and m["data"].get("id") == "test-trainer-1")
    report["battle_result"] = (found["data"], _counts(kinds))
    assert found["data"] == {"op": "battle", "ok": True, "route": "kanto_johto_test", "id": "test-trainer-1",
                             "outcome": "win"}, report
    await asyncio.sleep(1)
    # the lower row is closed; the step onto A is the way back, facing the town
    since = len(mc.received)
    await mc.send("move.step", {"dir": "down", "n": 10})
    wall = await find_or_wait(mc, ("player.blocked", "player.pos"), 20, since, lambda m: m["data"].get("n") == 10)
    report["down"] = (wall["type"], wall["data"].get("reason"))
    assert wall["type"] == "player.blocked", report
    await mc.send("move.step", {"dir": "left", "n": 11})
    back = (await find_or_wait(mc, "warp", 60, since, lambda m: m["data"]["phase"] == "arrive"))["data"]
    report["back"] = back["to"]
    assert back["to"] == {"map": CHERRYGROVE, "x": x - 3, "y": y, "facing": "right"}, report
    await asyncio.sleep(2)
    parked = (await mc.request("region.park", {"region": "johto"}, reply_type="act.result", timeout=30))["data"]
    report["park"] = parked
    assert parked == {"ok": True, "kind": "park"}, parked
    resumed = (await mc.request("region.resume", {"region": "johto", "to": {"map": ROUTE_MAP, "x": 1, "y": 0,
                                                                           "facing": "right"}},
                                reply_type="act.result", timeout=60))["data"]
    report["resume"] = resumed
    assert resumed == {"ok": True, "kind": "resume"}, resumed
    here = (await mc.request("resync", reply_type="snapshot", timeout=10))["data"]["player"]
    report["after_resume"] = (here["map"], here["x"], here["y"])
    assert (here["map"], here["x"], here["y"]) == (ROUTE_MAP, 1, 0), report
    report["errors"] = errors(mc)
    report["problems"] = len(mc.conn.problems)
    assert report["errors"] == [] and report["problems"] == 0, report
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return report


def _cleanup_run():
    with launch("kb_crystal.lua", game=GAME, extra_env={"KB_TEST_PORT": str(TEST_PORT), "KB_SCENE": "cleanup"},
                echo=False) as running:
        return running.wait(timeout=180)


def _launch(scene, ready_marker, scenario, timeout, extra=0, cinema=False, with_run=False):
    """One launch of the driver's scene; the scenario gets the port and the ready marker's facts (and, with
    with_run, the running instance, to wait for the markers the driver prints later)."""
    env = {"KB_TEST_PORT": str(TEST_PORT), "KB_SCENE": scene, "KB_DRIVER_EXTRA": str(extra)}
    if cinema:
        env["POKEPORT_BOOT_CINEMA"] = "1"  # the real boot chain: copyright, splash, intro, title
    with launch("kb_crystal.lua", game=GAME, extra_env=env) as running:
        booted = running.wait_marker("KB_BOOTED", timeout=240)
        assert booted is not None, running.result.summary()
        ready = running.wait_marker(ready_marker, timeout=240)
        assert ready is not None, running.result.summary()
        facts = running.result.facts(ready_marker)
        run = scenario(TEST_PORT, facts, running) if with_run else scenario(TEST_PORT, facts)
        report = asyncio.run(asyncio.wait_for(run, timeout))
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


@pytest.mark.e2e
@pytest.mark.timeout(1500)
def test_crystal_new_game_mom_and_menus_through_the_ui():
    _skip_without_crystal()
    before = save_files()
    clean = False
    try:
        result, report = _launch("title_ui", "KB_TITLE", _ui, 900, extra=600, cinema=True, with_run=True)
        assert result.facts("KB_TITLE")["hasSave"] == "false"
        assert result.marker("KB_NPC") is not None and result.marker("KB_DAYWHEEL") is not None
        clean = True
    finally:
        if not clean:
            _cleanup_run()
    assert save_files() == before, "the test slot's files are gone and the rest untouched"


@pytest.mark.e2e
@pytest.mark.timeout(1800)
def test_crystal_story_the_starter_flag_its_ball_and_the_bedrooms_blocks():
    _skip_without_crystal()
    before = save_files()
    clean = False
    try:
        result, report = _launch("story", "KB_TITLE", _story, 1200, extra=900, cinema=True, with_run=True)
        sites = [line for line in result.lines if "story.sites" in line]
        print("story sites:", sites)
        assert len(sites) == 1, sites
        clean = True
    finally:
        if not clean:
            _cleanup_run()
    assert save_files() == before, "the test slot's files are gone and the rest untouched"


@pytest.mark.e2e
@pytest.mark.timeout(1800)
def test_crystal_battles_the_starter_a_wild_a_trainer_and_the_rival():
    _skip_without_crystal()
    before = save_files()
    clean = False
    try:
        result, report = _launch("battles", "KB_TITLE", _battles, 1500, extra=1200, cinema=True, with_run=True)
        clean = True
    finally:
        if not clean:
            _cleanup_run()
    assert save_files() == before, "the test slot's files are gone and the rest untouched"


@pytest.mark.e2e
@pytest.mark.timeout(1200)
def test_crystal_route_the_johto_end_is_walked_in_and_out():
    _skip_without_crystal()
    before = save_files()
    clean = False
    try:
        result, report = _launch("route", "KB_SERVING", _route, 600)
        assert any("routes.installed" in line and "own=true" in line for line in result.lines), "the adapter's end"
        clean = True
    finally:
        if not clean:
            _cleanup_run()
    assert save_files() == before, "the test slot's files are gone and the rest untouched"
