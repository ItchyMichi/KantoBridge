"""M10 end to end, on the real engine: the handshake's hardening, crash recovery with save pairs, and a
reconnect in the middle of a text box (docs/PLAN.md M10 [L]; DESIGN section 9: "if one side crashes,
the other pauses and resyncs from the last save"). The driver is drivers/kb_crash.lua.

Launch 1 (crash1), at the boot screens first:
  a. a hello with another protocol -> hello.reject{reason:"protocol"} and the connection closed; a
     ping before any hello -> error{no_hello} and closed; a connection that says nothing -> closed
     after gen1-bridge's 10-second hello timeout (error{no_hello})
  b. the test's own connection: hello.ok with tested == engine (no engine.untested warning);
     session.bind for a made-up world -> its own slot, switched to at the title (session.bound
     switched "title"), no save.loaded at the title; the driver starts a New Game there ->
     save.loaded{fresh:true, ops:0}
  c. the driver pushes a text box of its own; the test drops the connection while it is open and
     connects again: hello.ok, snapshot, then the text box again as ui.open (kind text); the bind
     again -> session.bound switched "none", then save.loaded; the box is read to its close
  d. the driver saves -> save.begin{pair P, at} (answered save.done ok); the driver gives a Pokemon
     (party.give) -> a bind -> save.loaded{fresh:false, pair:P, ops:1}; a reconnect sends the
     unconfirmed gift again with the same give id, and the next bind's save.loaded still says ops 1
     (distinct operations, not messages)
  e. the test kills the process, without any save after the gift
Launch 2 (crash2): the bind at the title -> switched "none", no save.loaded; the driver continues
the saved game as CONTINUE does -> save.loaded{fresh:false, pair:P, at, ops:0} and the gift is gone
from the hidden party. At debug.quit the driver forgets the world and deletes its slot. A failed run
is cleaned up by a third launch (scene cleanup).

Every message passes its schema (strict). Output: types, slot numbers, counts, booleans and 8-hex
prefixes of pairs only; the world id is made up, the text box's words are the driver's own.
"""

import asyncio
import time

import pytest

from fake_minecraft import FakeMinecraft
from fake_minecraft.client import HelloRejected
from run_gen1recomp import launch, preflight

TEST_PORT = 27202
WORLD = "kb-e2e-crash-world"
ENV = {"KB_WORLD": WORLD, "KB_TEST_PORT": str(TEST_PORT)}


async def marker(running, name, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        line = running.result.marker(name)
        if line is not None:
            return dict(part.split("=", 1) for part in line.split()[1:] if "=" in part)
        await asyncio.sleep(0.1)
    raise AssertionError(f"no {name} marker within {timeout} s")


async def until_closed(mc, timeout):
    """Reads until gen1-bridge closes the connection; returns what arrived and the seconds it took."""
    start = time.monotonic()
    got = []
    while True:
        left = timeout - (time.monotonic() - start)
        if left <= 0:
            raise AssertionError("the connection stayed open")
        m = await mc.next(left)
        if m is None:
            return got, time.monotonic() - start
        got.append(m)


async def expect_from(mc, msg_type, start, timeout=20, pred=lambda m: True):
    """The first message of msg_type at index >= start of mc.received (waiting for it)."""
    deadline = time.monotonic() + timeout
    while True:
        for m in mc.received[start:]:
            if m["type"] == msg_type and pred(m):
                return m
        left = deadline - time.monotonic()
        if left <= 0:
            raise AssertionError(f"no {msg_type} within {timeout} s")
        try:
            await mc.next(min(left, 0.5))
        except asyncio.TimeoutError:
            pass


async def bind(mc, timeout=30):
    sent = await mc.send("session.bind", {"world": WORLD, "multiplayer": True})
    start = len(mc.received)
    bound = await expect_from(mc, "session.bound", start, timeout, lambda m: m.get("re") == sent["seq"])
    return bound, sent["seq"]


async def connect(port):
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=30)
    ok = await mc.hello()
    snap = await mc.expect("snapshot", timeout=10)
    return mc, ok["data"], snap["data"]


async def status(mc):
    return (await mc.request("debug.status", reply_type="debug.status", timeout=10))["data"]


def short(pair):
    return pair[:8] if isinstance(pair, str) else pair


async def _handshake(port):
    """(a): the three ways a connection without a good hello ends."""
    report = {}
    mc = FakeMinecraft(port=port, role="test", ping_ms=0)
    await mc.connect(attempts=30)
    try:
        await mc.hello(protocol=2, start_pings=False)
        report["protocol"] = "accepted?"
    except HelloRejected as rejected:
        report["protocol"] = rejected.reason
    got, _ = await until_closed(mc, 10)
    report["protocol_closed"] = True
    report["problems"] = len(mc.conn.problems)
    await mc.close()

    mc = FakeMinecraft(port=port, role="test", ping_ms=0)
    await mc.connect(attempts=30)
    await mc.send("ping", {"t": 1})
    got, _ = await until_closed(mc, 10)
    report["before_hello"] = [(m["type"], m["data"].get("code")) for m in got]
    report["problems"] += len(mc.conn.problems)
    await mc.close()

    mc = FakeMinecraft(port=port, role="test", ping_ms=0)
    await mc.connect(attempts=30)
    got, seconds = await until_closed(mc, 20)
    report["silent"] = [(m["type"], m["data"].get("code")) for m in got]
    report["silent_seconds"] = round(seconds, 1)
    report["problems"] += len(mc.conn.problems)
    await mc.close()
    return report


async def _crash1(port, running):
    report = {"handshake": await _handshake(port)}

    # b. the test's own connection; the world's own slot at the title, then a New Game there
    mc, ok, snap = await connect(port)
    report["hello_ok"] = {"tested": ok.get("tested") == ok["engine"] == "0.3.49", "ingame": snap["ingame"]}
    bound, seq = await bind(mc)
    report["bound_title"] = {"switched": bound["data"]["switched"], "re": bound["re"] == seq,
                             "slot": bound["data"]["slot"]}
    report["new_game"] = await marker(running, "KB_NEWGAME", 60)
    fresh = await expect_from(mc, "save.loaded", 0, 30)
    report["loaded_new_game"] = fresh["data"]
    report["loaded_at_title"] = [m for m in mc.received if m["type"] == "save.loaded"].index(fresh) == 0

    # c. a text box; the connection drops while it is open, and comes back
    await marker(running, "KB_TEXT", 60)
    box = await expect_from(mc, "ui.open", 0, 30, lambda m: m["data"]["kind"] == "text")
    first_counts = dict(mc.counts)
    await mc.close()
    await asyncio.sleep(0.5)
    mc, ok2, snap2 = await connect(port)
    report["same_session"] = ok2["session"] == ok["session"]
    again = await expect_from(mc, "ui.open", 0, 15, lambda m: m["data"]["kind"] == "text")
    order = [m["type"] for m in mc.received]
    report["reconnect_order"] = order.index("snapshot") < mc.received.index(again)
    report["box_again"] = {"kind": again["data"]["kind"], "pages": len(again["data"]["pages"]),
                           "same_pages": len(again["data"]["pages"]) == len(box["data"]["pages"])}
    bound, seq = await bind(mc)
    report["bound_again"] = bound["data"]["switched"]
    loaded = await expect_from(mc, "save.loaded", mc.received.index(bound), 15)
    report["loaded_after_reconnect"] = loaded["data"]
    # read the box like a player: A each time it waits
    ui_id = again["data"]["ui"]
    deadline = time.monotonic() + 30
    answered = set()
    state = {}
    while True:
        for m in mc.received:
            if m["type"] == "ui.update" and m["data"]["ui"] == ui_id:
                state.update(m["data"])
        if any(m["type"] == "ui.close" and m["data"]["ui"] == ui_id for m in mc.received):
            break
        stop = (state.get("page", 0), state.get("line", 0), state.get("done", False))
        if state.get("waiting") and stop not in answered:
            answered.add(stop)
            await mc.send("ui.advance", {"ui": ui_id})
        assert time.monotonic() < deadline, "the text box never closed"
        try:
            await mc.next(0.2)
        except asyncio.TimeoutError:
            pass
    report["advances"] = len(answered)

    # d. the save, then a gift after it
    report["saved"] = await marker(running, "KB_SAVED", 60)
    begin = await expect_from(mc, "save.begin", 0, 30)
    await mc.send("save.done", {"save": begin["data"]["save"], "ok": True})
    report["gave"] = await marker(running, "KB_GAVE", 60)
    give = await expect_from(mc, "party.give", 0, 30)
    report["give"] = {"dex": give["data"]["dex"], "level": give["data"]["level"]}
    bound, seq = await bind(mc)
    first_loaded = await expect_from(mc, "save.loaded", mc.received.index(bound), 15)
    report["ops_before_reconnect"] = first_loaded["data"]["ops"]
    # a reconnect: the unconfirmed gift goes out again with the same give id, the same operation, so
    # ops stays where it was (distinct operations, the server skipping the repeat too)
    await mc.close()
    await asyncio.sleep(0.5)
    mc, _, _ = await connect(port)
    again = await expect_from(mc, "party.give", 0, 15)
    report["give_resent_same_id"] = again["data"]["give"] == give["data"]["give"]
    bound, seq = await bind(mc)
    loaded = await expect_from(mc, "save.loaded", mc.received.index(bound), 15)
    st = await status(mc)
    report["status"] = {k: st[k] for k in ("saveOps", "saveFresh", "saveLoadedSent", "saveSwitchWrites",
                                           "pairingSwitchTitle", "sessionUntested")}
    report["errors"] = [m["data"]["code"] for m in mc.received if m["type"] == "error"]
    report["untested_logs"] = sum(1 for m in mc.received if m["type"] == "log" and m["data"]["code"] == "engine.untested")
    report["problems"] = len(mc.conn.problems)
    await mc.close()
    return report, begin["data"], loaded["data"], first_counts


async def _crash2(port, running):
    mc, ok, snap = await connect(port)
    report = {"ingame_at_boot": snap["ingame"]}
    bound, seq = await bind(mc)
    report["bound"] = {"switched": bound["data"]["switched"], "slot": bound["data"]["slot"]}
    report["continued"] = await marker(running, "KB_CONTINUED", 60)
    loaded = await expect_from(mc, "save.loaded", 0, 30)
    report["loaded_count"] = sum(1 for m in mc.received if m["type"] == "save.loaded")
    st = await status(mc)
    report["status"] = {k: st[k] for k in ("saveOps", "saveFresh", "saveLegacy", "partySize")}
    report["errors"] = [m["data"]["code"] for m in mc.received if m["type"] == "error"]
    report["problems"] = len(mc.conn.problems)
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return report, loaded["data"]


def _cleanup_run():
    with launch("kb_crash.lua", extra_env=dict(ENV, KB_SCENE="cleanup"), echo=False) as running:
        return running.wait(timeout=120)


@pytest.mark.e2e
@pytest.mark.timeout(900)
def test_a_crash_and_continue_report_the_same_save_pair():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    clean = False
    try:
        # ---- launch 1: handshake, New Game, the reconnect in a text box, the save and a gift, the kill
        with launch("kb_crash.lua", extra_env=dict(ENV, KB_SCENE="crash1")) as running:
            booted = running.wait_marker("KB_BOOTED", timeout=150)
            assert booted is not None, running.result.summary()
            r1, begin, before_kill, _ = asyncio.run(asyncio.wait_for(_crash1(TEST_PORT, running), 400))
            running.kill()  # no save after the gift: the hidden save is the one before it
            first = running.wait(timeout=30)
        printable = dict(r1)
        print("launch 1:", printable)
        print("save.begin: reason", begin["reason"], "pair", short(begin["pair"]), "at set", "at" in begin)
        print("save.loaded before the kill:", {**before_kill, "pair": short(before_kill.get("pair"))})
        print("launch 1 markers:", [line for line in first.lines if line.startswith("KB_")])
        assert first.marker("KB_FAIL") is None and not first.error_spots, first.summary()
        assert not first.crash_log_touched

        hs = r1["handshake"]
        assert hs["protocol"] == "protocol" and hs["protocol_closed"]
        assert hs["before_hello"] == [("error", "no_hello")]
        assert hs["silent"] == [("error", "no_hello")] and 9 <= hs["silent_seconds"] <= 13, hs
        assert hs["problems"] == 0
        assert r1["hello_ok"] == {"tested": True, "ingame": False}
        assert r1["bound_title"]["switched"] == "title" and r1["bound_title"]["re"]
        assert r1["loaded_new_game"] == {"fresh": True, "ops": 0} and r1["loaded_at_title"]
        assert r1["same_session"] and r1["reconnect_order"]
        assert r1["box_again"]["kind"] == "text" and r1["box_again"]["same_pages"]
        assert r1["bound_again"] == "none"
        assert r1["loaded_after_reconnect"] == {"fresh": True, "ops": 0}
        assert r1["advances"] >= 1
        assert r1["saved"]["ok"] == "true"
        assert begin["reason"] == "menu" and len(begin["pair"]) == 16 and isinstance(begin.get("at"), int)
        assert r1["give"]["dex"] == 25 and r1["give"]["level"] == 5
        assert r1["ops_before_reconnect"] == 1 and r1["give_resent_same_id"]
        assert before_kill == {"fresh": False, "pair": begin["pair"], "at": begin["at"], "ops": 1}, \
            "the gift sent again after the reconnect is the same operation"
        assert r1["status"]["saveOps"] == 1 and r1["status"]["sessionUntested"] == 0
        assert r1["untested_logs"] == 0 and r1["errors"] == [] and r1["problems"] == 0

        # ---- launch 2: CONTINUE after the kill
        with launch("kb_crash.lua", extra_env=dict(ENV, KB_SCENE="crash2")) as running:
            booted = running.wait_marker("KB_BOOTED", timeout=150)
            assert booted is not None, running.result.summary()
            r2, after = asyncio.run(asyncio.wait_for(_crash2(TEST_PORT, running), 300))
            second = running.wait(timeout=60)
        print("launch 2:", r2)
        print("save.loaded after CONTINUE:", {**after, "pair": short(after.get("pair"))})
        print("launch 2 markers:", [line for line in second.lines if line.startswith("KB_")])
        assert not second.timed_out and second.returncode == 0, second.summary()
        assert second.marker("KB_FAIL") is None and not second.error_spots and not second.crash_log_touched
        assert r2["ingame_at_boot"] is False
        assert r2["bound"] == {"switched": "none", "slot": r1["bound_title"]["slot"]}
        assert after == {"fresh": False, "pair": begin["pair"], "at": begin["at"], "ops": 0}, "the same save"
        assert r2["loaded_count"] == 1, "only on entering the game, never at the title"
        assert r2["status"]["partySize"] == 0, "the gift after the save went with the crash"
        assert int(r2["continued"]["party"]) == 0
        assert r2["errors"] == [] and r2["problems"] == 0
        cleaned = second.facts("KB_CLEANED")
        assert int(cleaned["deleted"]) == 1 and cleaned["active"] == cleaned["home"]
        clean = True
    finally:
        if not clean:
            result = _cleanup_run()
            print("cleanup run:", [line for line in result.lines if line.startswith("KB_")])
