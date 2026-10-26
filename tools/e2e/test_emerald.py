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
  battle (G3F, one launch): party.sync gives the hidden player one Pokemon; the driver starts a trainer
      battle through the engine's BattleBridge and battle.start arrives held (national dex numbers, Showdown
      moves, IVs, a TRAINER_CLASS_ role); battle.result win pays the engine's prize (the money goes up) and
      its texts are read through the UI; a wild battle (dex 263) is answered fled; a second trainer battle is
      answered lose: the hidden player blacks out (half the money, a warp to the heal point) and party.heal
      reaches Minecraft.
  route (MG8, one launch): on the exit cell of the Wild Routes test pass's Hoenn end (gen1-bridge/data/routes.json),
      five steps down walk the clearing the adapter opened into its trigger: a warp of kind script onto the route map
      KB_ROUTE_KANTO_HOENN_TEST_TO at x1; a step left there is the way back (a warp onto the clearing's back cell,
      facing the town); then region.park and region.resume onto the route map land on it again.
  start (two launches): at the title with the slot saved, region.start CONTINUEs (act.result detail continue,
      game.status saved and in game, save.loaded fresh false); with the slot empty it starts NEW GAME (detail
      new), and (G3B) Birch's speech is answered through the UI as the client would (its text advanced, BOY
      picked, the name KB given on the naming screen, YES to it) until the new game is in the field.
  party (SEVERAL_GAMES_FOLLOWUPS.md item 7, one launch): the test plays Cobblemon (every party operation applied
      once, a party.sync after each) and the client's UI. Four members; the engine's in-game trade of the fourth
      is party.take{reason:"trade"} for it and party.give{source:"trade"} (nickname and OT) for the one received, and
      a sync from before Cobblemon applied them changes nothing (the snapshot's party: the three and the one
      received). Two boarded (Daycare.deposit) are two daycare.deposit; after a session.bind save.loaded names both
      (daycare and boarded); the game saves, goes to the title, region.start CONTINUEs and save.loaded names both
      again (their uids kept in the save's mod data). Ten daycare steps and Daycare.take are daycare.withdraw with
      steps 10. A gift egg goes out as nothing and a sync keeps it (the snapshot's party has it, no uid); one step
      down hatches it through the engine's own step events and hatch screen ("Huh?" as text, the screen as a
      mirror where A is YES to a nickname, KB on the naming screen) and it goes out as party.give{source:"egg"}
      with that nickname.

Every message passes its schema (strict). Output: types, slot numbers, map symbols, cell numbers, counts and
booleans only.
"""

import asyncio
import os
import time

import pytest

import uuid

from fake_minecraft import FakeMinecraft
from run_gen1recomp import INSTALL, launch, preflight
import foreign  # noqa: E402  (FP8: KB_E2E_FOREIGN=1, an all-foreign team)

TEST_PORT = 27220
GAME = "emerald"
TOWN = "EM_LITTLEROOT_TOWN"

def cell_of(to: dict) -> dict:
    """A warp's `to` without its floor (WATER_AND_BRIDGES_PLAN D7 added `elev`; these checks are about the cell)."""
    return {k: v for k, v in to.items() if k != "elev"}



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
    await asyncio.sleep(0.2)
    # the user's item 11: the engine fades in after the map.entered that ended the warp; that tail starts no second warp
    phases = [(m["data"]["phase"], m["data"]["kind"]) for m in mc.received[since:] if m["type"] == "warp"]
    report["warp_messages"] = phases
    assert phases == [("start", report["warp"][1]), ("arrive", report["warp"][1])], phases
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


UID = "6b1f0d7e-3c2a-4e8b-9f10-2a3b4c5d6e7f"


async def _advance_texts_until(mc, since, until, timeout):
    """Advances every text box the hidden game opens (as the client does) until a message matches `until`;
    returns that message and how many text boxes were advanced."""
    answered, texts = set(), 0
    deadline = time.monotonic() + timeout
    seen = since
    while True:
        while seen < len(mc.received):
            m = mc.received[seen]
            seen += 1
            if until(m):
                return m, texts
            if m["type"] == "ui.open" and m["data"]["kind"] == "text" and m["data"]["ui"] not in answered:
                answered.add(m["data"]["ui"])
                texts += 1
                for _ in range(len(m["data"]["pages"]) + 1):
                    await mc.send("ui.advance", {"ui": m["data"]["ui"]})
        left = deadline - time.monotonic()
        assert left > 0, f"nothing matched within {timeout} s; seen since: {seen_summary(mc, since)}"
        try:
            if await mc.next(min(left, 1.0)) is None:
                raise ConnectionError("connection closed")
        except asyncio.TimeoutError:
            pass


def _team_ok(team):
    """Every member as the core sends a Gen 3 one: national dex, level, 1-4 Showdown moves, IVs and no DVs."""
    return all(1 <= t["dex"] <= 386 and 1 <= t["level"] <= 100 and 1 <= len(t["moves"]) <= 4
               and all(1 <= n <= 354 for n in t["moves"]) and "ivs" in t and "dvs" not in t for t in team)


async def _money(mc):
    return (await mc.request("resync", reply_type="snapshot", timeout=10))["data"]["money"]


async def _battle(port, serving):
    mc, ok, snap, status = await connect(port)
    report = {}
    since = len(mc.received)
    await mc.send("party.sync", {"party": foreign.party([{"uid": UID, "dex": 263, "level": 10, "moves": [33, 39], "hp": 30,
                                                          "maxhp": 30}]), "box": {"count": 0}})
    # 1: a trainer battle, won
    first = (await find_or_wait(mc, "battle.start", 90, since))["data"]
    report["first"] = (first["kind"], first["trainer"]["cls"].startswith("TRAINER_CLASS_"), first["trainer"]["party"],
                       len(first["team"]), _team_ok(first["team"]), first["rules"])
    assert first["kind"] in ("trainer", "scripted", "rival") and first["trainer"]["cls"].startswith("TRAINER_CLASS_"), report
    assert _team_ok(first["team"]) and first["rules"] == {"catch": False, "flee": False}, report
    before = await _money(mc)
    since = len(mc.received)
    await mc.send("battle.result", {"battle": first["battle"], "outcome": "win"})
    # 2: the wild battle starts once the prize texts were read and the trainer's done ran (the bridge sends a
    # pending battle.start again after every snapshot: a new battle has a new id)
    def new_battle(m):
        return m["type"] == "battle.start" and m["data"]["battle"] not in ids

    ids = {first["battle"]}
    wild, texts = await _advance_texts_until(mc, since, new_battle, 90)
    wild = wild["data"]
    won = await _money(mc)
    report["win"] = (texts, won > before)
    assert texts >= 1 and won > before, (report, before, won)
    report["wild"] = (wild["kind"], wild["wild"]["dex"], wild["wild"]["level"], wild["rules"])
    assert (wild["kind"], wild["wild"]["dex"], wild["wild"]["level"]) == ("static", 263, 3), report
    assert wild["rules"] == {"catch": True, "flee": True}, report
    since = len(mc.received)
    ids.add(wild["battle"])
    await mc.send("battle.result", {"battle": wild["battle"], "outcome": "fled"})
    # 3: the second trainer battle, lost: the blackout
    third, texts = await _advance_texts_until(mc, since, new_battle, 90)
    third = third["data"]
    report["fled_texts"] = texts
    report["third"] = (third["kind"], third["trainer"]["party"], len(third["team"]), _team_ok(third["team"]))
    assert _team_ok(third["team"]), report
    money = await _money(mc)
    since = len(mc.received)
    await mc.send("battle.result", {"battle": third["battle"], "outcome": "lose"})
    heal, texts = await _advance_texts_until(mc, since, lambda m: m["type"] == "party.heal", 120)
    warps = [m for m in mc.received[since:] if m["type"] == "warp"]
    after = (await mc.request("resync", reply_type="snapshot", timeout=10))["data"]
    report["lose"] = (texts, len(warps), after["money"] == money // 2, after["player"]["map"])
    assert warps and after["money"] == money // 2, (report, money, after["money"])
    report["errors"] = errors(mc)
    report["problems"] = len(mc.conn.problems)
    assert report["errors"] == [] and report["problems"] == 0, report
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return report


ROUTE_MAP = "KB_ROUTE_KANTO_HOENN_TEST_TO"


async def _route(port, serving):
    x, y = int(serving["x"]), int(serving["y"])
    mc, ok, snap, status = await connect(port)
    assert (snap["player"]["map"], snap["player"]["x"], snap["player"]["y"]) == (TOWN, x, y), snap["player"]
    report = {}
    since = len(mc.received)
    for n in range(1, 6):  # one at a time (the mover keeps a short waiting line)
        await mc.send("move.step", {"dir": "down", "n": n})
        step = await find_or_wait(mc, ("player.pos", "player.blocked"), 20, since, lambda m, n=n: m["data"].get("n") == n)
        assert step["type"] == "player.pos", (n, step)
    arrive = (await find_or_wait(mc, "warp", 60, since, lambda m: m["data"]["phase"] == "arrive"))["data"]
    report["in"] = (arrive["kind"], arrive["to"])
    assert arrive["kind"] == "script" and cell_of(arrive["to"]) == {"map": ROUTE_MAP, "x": 1, "y": 0, "facing": "right"}, report
    blocked = [m["data"]["reason"] for m in mc.received[since:] if m["type"] == "player.blocked"]
    report["blocked_on_the_way"] = blocked
    assert blocked == [], report
    await asyncio.sleep(1)
    since = len(mc.received)
    await mc.send("move.step", {"dir": "right", "n": 6})
    wall = await find_or_wait(mc, ("player.blocked", "player.pos"), 20, since, lambda m: m["data"].get("n") == 6)
    report["right"] = (wall["type"], wall["data"].get("reason"))
    assert wall["type"] == "player.blocked", report
    await mc.send("move.step", {"dir": "left", "n": 7})
    back = (await find_or_wait(mc, "warp", 60, since, lambda m: m["data"]["phase"] == "arrive"))["data"]
    report["back"] = back["to"]
    assert cell_of(back["to"]) == {"map": TOWN, "x": x, "y": y + 4, "facing": "up"}, report
    await asyncio.sleep(2)
    parked = (await mc.request("region.park", {"region": "hoenn"}, reply_type="act.result", timeout=30))["data"]
    report["park"] = parked
    assert parked == {"ok": True, "kind": "park"}, parked
    resumed = (await mc.request("region.resume", {"region": "hoenn", "to": {"map": ROUTE_MAP, "x": 1, "y": 0,
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


async def _fp7(port, serving):
    """FP7: Surf from Minecraft onto the water in front and back onto the land; then Lanette's PC is Cobblemon's."""
    x, y, wx, wy = int(serving["x"]), int(serving["y"]), int(serving["wx"]), int(serving["wy"])
    mc, ok, snap, status = await connect(port)
    report = {"start": (snap["player"]["x"], snap["player"]["y"]), "badge": serving["badge"]}
    uid = "0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b"
    await mc.send("party.sync", {"party": foreign.party([{"uid": uid, "dex": 260, "level": 30, "moves": [57, 33], "hp": 90,
                                                          "maxhp": 90}]),
                                 "box": {"count": 0}})
    await asyncio.sleep(2)
    seq = (await mc.send("act.field", {"action": "surf", "x": wx, "y": wy}))["seq"]
    res = await find_or_wait(mc, "act.result", 30, 0, lambda m: m.get("re") == seq)
    report["surf"] = (res["data"]["ok"], res["data"].get("kind"), res["data"].get("reason"))
    assert report["surf"][:2] == (True, "surf"), report
    await asyncio.sleep(4)
    seq = (await mc.send("act.field", {"action": "surf", "x": x, "y": y}))["seq"]
    res = await find_or_wait(mc, "act.result", 30, 0, lambda m: m.get("re") == seq)
    report["dismount"] = (res["data"]["ok"], res["data"].get("kind"), res["data"].get("reason"))
    assert report["dismount"][:2] == (True, "dismount"), report
    opened = await find_or_wait(mc, "pc.open", 60)
    report["pc"] = opened["data"]["pc"]
    await asyncio.sleep(1)
    await mc.send("pc.closed", {"pc": opened["data"]["pc"]})
    await asyncio.sleep(3)
    assert not errors(mc), errors(mc)
    await mc.send("debug.quit", {"code": 0})
    await asyncio.sleep(2)
    await mc.close()
    return report


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
@pytest.mark.timeout(900)
def test_emerald_battles_are_held_for_the_client():
    _skip_without_emerald()
    before = save_files()
    clean = False
    try:
        result, report = _launch("battle", "KB_SERVING", _battle, 400)
        trainers = [dict(p.split("=", 1) for p in line.split()[1:]) for line in result.lines if line.startswith("KB_TRAINER ")]
        done = [dict(p.split("=", 1) for p in line.split()[1:]) for line in result.lines if line.startswith("KB_BATTLE_DONE ")]
        assert [int(t["id"]) for t in trainers] == [report["first"][2], report["third"][1]], (trainers, report)
        assert [int(t["members"]) for t in trainers] == [report["first"][3], report["third"][2]], (trainers, report)
        assert [(d["n"], d["result"]) for d in done] == [("1", "win"), ("2", "run"), ("3", "lose")], done
        clean = True
    finally:
        if not clean:
            _cleanup_run()
    assert save_files() == before, "the test slot's files are gone and the rest untouched"


@pytest.mark.e2e
@pytest.mark.timeout(900)
def test_the_passes_hoenn_end_is_walked_in_and_out():
    _skip_without_emerald()
    before = save_files()
    clean = False
    try:
        result, report = _launch("route", "KB_SERVING", _route, 300)
        assert any("routes.installed" in line and "own=true" in line for line in result.lines), "the adapter's end"
        clean = True
    finally:
        if not clean:
            _cleanup_run()
    assert save_files() == before, "the test slot's files are gone and the rest untouched"


UID_A = "a1a1a1a1-0000-4000-8000-000000000001"  # national dex 263: boarded, back after ten steps
UID_B = "b2b2b2b2-0000-4000-8000-000000000002"  # 265: boarded
UID_C = "c3c3c3c3-0000-4000-8000-000000000003"  # 261: stays
UID_D = "d4d4d4d4-0000-4000-8000-000000000004"  # 280: traded away


def _member(uid, dex, level, give=None):
    m = {"uid": uid, "dex": dex, "level": level, "moves": [33], "hp": 20, "maxhp": 20}
    if give:
        m["give"] = give
    return m


class _Cobblemon:
    """Cobblemon's side of the party scene: the party and the daycare by uid, each operation applied once (gen1-bridge
    sends one again after every snapshot) and answered with a party.sync, as the server does."""

    def __init__(self, mc, party):
        self.mc, self.party, self.boarded, self.gives = mc, list(party), {}, {}

    async def sync(self, party=None):
        members = self.party if party is None else party
        await self.mc.send("party.sync", {"party": [dict(m) for m in members], "box": {"count": 0}})

    async def apply(self, m):
        t, d = m["type"], m.get("data") or {}
        uids = [p["uid"] for p in self.party]
        if t == "party.take" and d["uid"] in uids:
            self.party = [p for p in self.party if p["uid"] != d["uid"]]
        elif t == "party.give" and d["give"] not in self.gives:
            self.gives[d["give"]] = str(uuid.uuid4())
            self.party.append(_member(self.gives[d["give"]], d["dex"], d["level"], give=d["give"]))
        elif t == "daycare.deposit" and d["uid"] in uids:
            self.boarded[d["uid"]] = next(p for p in self.party if p["uid"] == d["uid"])
            self.party = [p for p in self.party if p["uid"] != d["uid"]]
        elif t == "daycare.withdraw" and d["uid"] in self.boarded:
            self.party.append(self.boarded.pop(d["uid"]))
        else:
            return False
        await self.sync()
        return True


async def _serve(mc, cob, since, until, timeout, mirror_button=None):
    """Plays Cobblemon (every party operation applied, then a sync) and the client's UI (texts advanced, the first
    choice picked, the name KB; with `mirror_button`, that button tapped every 1.5 s while a mirror screen is the
    newest UI, as a player answers a screen drawn by the hidden game) until a message matches `until` (checked after
    it is applied); returns it and the counts of the UI kinds seen."""
    answered, kinds, mirrors, pressed = set(), {}, [], 0.0
    deadline = time.monotonic() + timeout
    seen = since
    while True:
        while seen < len(mc.received):
            m = mc.received[seen]
            seen += 1
            await cob.apply(m)
            if until(m):
                return m, kinds
            if m["type"] == "ui.close" and m["data"]["ui"] in mirrors:
                mirrors.remove(m["data"]["ui"])
            if m["type"] == "ui.open" and m["data"]["ui"] not in answered:
                d = m["data"]
                answered.add(d["ui"])
                kinds[d["kind"]] = kinds.get(d["kind"], 0) + 1
                if d["kind"] == "text":
                    for _ in range(len(d["pages"]) + 1):
                        await mc.send("ui.advance", {"ui": d["ui"]})
                elif d["kind"] == "choice":
                    await mc.send("ui.pick", {"ui": d["ui"], "index": 0})
                elif d["kind"] == "naming":
                    await mc.send("ui.name", {"ui": d["ui"], "glyphs": NAME})
                    mirrors.clear()
                elif d["kind"] == "mirror":
                    mirrors.append(d["ui"])
        if mirror_button and mirrors and time.monotonic() - pressed >= 1.5:
            pressed = time.monotonic()
            kinds["presses"] = kinds.get("presses", 0) + 1
            await mc.send("act.button", {"button": mirror_button, "how": "tap"})
        left = deadline - time.monotonic()
        assert left > 0, f"nothing matched within {timeout} s: {kinds}; seen since: {seen_summary(mc, since)}"
        try:
            if await mc.next(min(left, 1.0)) is None:
                raise ConnectionError("connection closed")
        except asyncio.TimeoutError:
            pass


async def _hidden_party(mc):
    """The shadow party as a fresh snapshot shows it: (dex, uid or None) per member."""
    snap = (await mc.request("resync", reply_type="snapshot", timeout=10))["data"]
    return [(p["dex"], p.get("uid")) for p in snap["party"]]


async def _party(port, serving):
    mc, ok, snap, status = await connect(port)
    report = {}
    old = [_member(UID_A, 263, 8), _member(UID_B, 265, 6), _member(UID_C, 261, 5), _member(UID_D, 280, 5)]
    cob = _Cobblemon(mc, old)
    since = len(mc.received)
    await cob.sync()
    # 1. the in-game trade of the fourth member
    take = (await find_or_wait(mc, "party.take", 90, since))["data"]
    give = (await find_or_wait(mc, "party.give", 30, since, lambda m: m["data"]["source"] == "trade"))
    report["trade"] = (take, give["data"]["dex"], give["data"]["level"], "nick" in give["data"], "ot" in give["data"])
    assert take == {"uid": UID_D, "reason": "trade"} and "nick" in give["data"] and "ot" in give["data"], report
    # Cobblemon's sync from before it applied them changes nothing
    await cob.sync(old)
    await asyncio.sleep(1.5)
    report["stale"] = await _hidden_party(mc)
    assert report["stale"] == [(263, UID_A), (265, UID_B), (261, UID_C), (give["data"]["dex"], None)], report
    since = len(mc.received)
    await cob.apply(give)
    await cob.apply({"type": "party.take", "data": take})
    # 2. two boarded
    await _serve(mc, cob, since, lambda m: len(cob.boarded) == 2, 90)
    report["boarded"] = sorted(cob.boarded)
    assert set(cob.boarded) == {UID_A, UID_B}, report
    since = len(mc.received)
    await mc.send("session.bind", {"world": "hoenn-party-test", "multiplayer": False})
    loaded = (await find_or_wait(mc, "save.loaded", 30, since))["data"]
    report["loaded_bind"] = (loaded.get("daycare"), loaded.get("boarded"))
    assert (loaded.get("daycare"), loaded.get("boarded")) == (UID_A, [UID_A, UID_B]), report
    # 3. the game saves and goes back to the title; CONTINUE finds both boarded again
    await _serve(mc, cob, since, lambda m: m["type"] == "game.status" and m["data"].get("ingame") is False, 90)
    sent = await mc.send("region.start", {"region": "hoenn"})
    since = len(mc.received)
    result = (await find_or_wait(mc, "act.result", 90, since, lambda m: m.get("re") == sent["seq"]))["data"]
    assert result == {"ok": True, "kind": "start", "detail": "continue"}, result
    loaded = (await _serve(mc, cob, since, lambda m: m["type"] == "save.loaded", 90))[0]["data"]
    report["loaded_continue"] = (loaded["fresh"], loaded.get("daycare"), loaded.get("boarded"))
    assert report["loaded_continue"] == (False, UID_A, [UID_A, UID_B]), report
    await cob.sync()
    # 4. ten daycare steps, then the first one boarded comes back with them
    withdraw = (await _serve(mc, cob, since, lambda m: m["type"] == "daycare.withdraw", 90))[0]["data"]
    report["withdraw"] = withdraw
    assert withdraw == {"uid": UID_A, "steps": 10}, report
    # 5. a gift egg: nothing goes out for it, and a sync keeps it
    since = len(mc.received)
    deadline = time.monotonic() + 60
    party = await _hidden_party(mc)
    while (360, None) not in party:
        assert time.monotonic() < deadline, f"no egg: {party}"
        await asyncio.sleep(1.0)
        party = await _hidden_party(mc)
    await cob.sync()
    await asyncio.sleep(1.5)
    report["egg"] = await _hidden_party(mc)
    assert (360, None) in report["egg"], report
    assert not [m for m in mc.received[since:] if m["type"] == "party.give"], "an egg went out"
    # 6. one step down: the engine's step events hatch it ("Huh?" as text, then its screen as a mirror: A on it is
    # YES to a nickname), the name KB
    since = len(mc.received)
    await mc.send("move.step", {"dir": "down", "n": 61})
    hatched, kinds = await _serve(mc, cob, since, lambda m: m["type"] == "party.give" and m["data"]["source"] == "egg",
                                  180, mirror_button="a")
    report["hatch"] = (hatched["data"]["dex"], hatched["data"]["level"], hatched["data"].get("nick"), kinds)
    assert (hatched["data"]["dex"], hatched["data"]["level"], hatched["data"].get("nick")) == (360, 5, "KB"), report
    assert kinds.get("mirror", 0) >= 1 and kinds.get("naming") == 1, report
    report["errors"] = errors(mc)
    report["problems"] = len(mc.conn.problems)
    assert report["errors"] == [] and report["problems"] == 0, report
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return report


@pytest.mark.e2e
@pytest.mark.timeout(1200)
def test_emeralds_trades_daycare_and_eggs_reach_cobblemon():
    _skip_without_emerald()
    before = save_files()
    clean = False
    try:
        result, report = _launch("party", "KB_SERVING", _party, 900, extra=600)
        markers = [line.split()[0] for line in result.lines if line.startswith("KB_")]
        for marker in ("KB_TRADE", "KB_BOARDED", "KB_SAVED", "KB_WITHDRAWN", "KB_EGG", "KB_HATCHED"):
            assert marker in markers, (marker, markers)
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


@pytest.mark.e2e
@pytest.mark.timeout(900)
def test_emerald_surf_from_minecraft_and_lanettes_pc_is_cobblemons():
    # FOREIGN_POKEMON_PLAN FP7 (§4.2, §4.8): act.field on the real Emerald, and the storage PC held for Cobblemon's
    _skip_without_emerald()
    before = save_files()
    clean = False
    try:
        result, report = _launch("fp7", "KB_SERVING", _fp7, 400)
        assert result.facts("KB_PC")["yielded"] == "true", result.facts("KB_PC")
        assert any(line.startswith("KB_PC_DONE") for line in result.lines), "the script went on after pc.closed"
        clean = True
    finally:
        if not clean:
            _cleanup_run()
    assert save_files() == before, "the test slot's files are gone and the rest untouched"
