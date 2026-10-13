"""M9 end to end, on the real engine: online save pairing and the port fallback (docs/PLAN.md M9 [L];
DESIGN section 8 "Saving": each multiplayer world gets its own paired online story save, kept separate
from the offline save). The driver is drivers/kb_multiplayer.lua.

Pairing, two launches of the test instance (a restart in between):

  1. at the boot screens: session.bind for world A (multiplayer) -> session.bound with a slot that
     isn't the offline (home) slot; A again -> the same slot; world B -> another slot; a singleplayer
     world -> the home slot again. Each switch happens at the title (gen1-bridge's runtime path).
  2. after the restart, A -> the same slot as before (the pairing file in mod.cache survived; no slot
     created); the driver starts a New Game in A's slot and saves it; B is bound in game -> the game is
     saved into A's slot first (never announced as save.begin), then the title in B's slot; the driver
     starts a New Game in B's slot and leaves it unsaved; A is bound -> A's slot has a save, so the
     switch waits (log session.switch_waiting, reason new_game) until the driver quits to the title,
     where it runs; the singleplayer world -> home. At debug.quit the driver forgets A and B and
     deletes their slots.

Throughout, the home slot's save files are untouched: their names, sizes and modification times (a
listing, never opened) are the same before and after; the test slots' files are gone at the end; the
pairing file exists (by name). A failed run is cleaned up by a third launch (scene cleanup).

Port fallback: the test keeps the configured port and the next one busy (listening sockets), launches
the instance on that port and checks it listens on the one after (the driver's KB_LISTENING, then
debug.status over TCP: port, portConfigured, portFallback).

Every message passes its schema (strict). Output: types, slot numbers, ports, counts, booleans and
8-hex digests of file listings only; the world ids are made up.
"""

import asyncio
import hashlib
import os
import socket
import time

import pytest

from fake_minecraft import FakeMinecraft
from run_gen1recomp import INSTALL, launch, preflight

TEST_PORT = 27169
FALLBACK_PORT = 27160  # it and the next one are kept busy; 27160-27168 is the fallback span
WORLD_A, WORLD_B, WORLD_SOLO = "kb-e2e-world-a", "kb-e2e-world-b", "kb-e2e-solo-world"
WORLD_ENV = {"KB_WORLD_A": WORLD_A, "KB_WORLD_B": WORLD_B}


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


def slot_files(files, slot):
    return {n: v for n, v in files.items() if n == f"slot{slot}.lua" or n.startswith(f"slot{slot}.lua.")}


def digest(files):
    text = "\n".join(f"{n}:{s}:{m}" for n, (s, m) in sorted(files.items()))
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:8]


def pairing_file_names():
    folder = INSTALL / "mod_cache" / "gen1-bridge" / "session"
    return sorted(os.listdir(folder)) if folder.is_dir() else []


# ------------------------------------------------------------ the bridge


async def connect(port):
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=30)
    await mc.hello()
    snap = await mc.expect("snapshot", timeout=10)
    return mc, snap["data"]


async def bind(mc, world, multiplayer, timeout=20):
    sent = await mc.send("session.bind", {"world": world, "multiplayer": multiplayer})
    reply = await mc.expect("session.bound", timeout=timeout, re=sent["seq"])
    return reply["data"]["slot"]


async def status(mc):
    return (await mc.request("debug.status", reply_type="debug.status", timeout=10))["data"]


async def marker(running, name, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        line = running.result.marker(name)
        if line is not None:
            return dict(part.split("=", 1) for part in line.split()[1:] if "=" in part)
        await asyncio.sleep(0.1)
    raise AssertionError(f"no {name} marker within {timeout} s")


def errors(mc):
    return [m["data"]["code"] for m in mc.received if m["type"] == "error"]


async def _pairing1(port):
    mc, snap = await connect(port)
    report = {"ingame_at_boot": snap["ingame"]}
    a = await bind(mc, WORLD_A, True)
    report["a_again"] = await bind(mc, WORLD_A, True)
    b = await bind(mc, WORLD_B, True)
    home = await bind(mc, WORLD_SOLO, False)
    st = await status(mc)
    report["status"] = {k: st[k] for k in ("pairingCreated", "pairingReused", "pairingSwitchTitle", "pairingSwitches",
                                           "pairingHomeReturns", "pairingPending", "pairingWorlds", "saveSlot",
                                           "ingame", "saveBegun")}
    report["errors"] = errors(mc)
    report["problems"] = len(mc.conn.problems)
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return a, b, home, report


async def _pairing2(port, running, game):
    mc, snap = await connect(port)
    report = {"ingame_at_boot": snap["ingame"]}
    report["a"] = await bind(mc, WORLD_A, True)
    st = await status(mc)
    report["created_after_restart"] = st["pairingCreated"]

    # the driver's New Game in A's slot, saved
    report["saved"] = await marker(running, "KB_SAVED", 90)
    report["files_after_saved"] = save_files(game)

    # B while in a saved game: saved into A's slot first, then the title in B's slot
    report["b"] = await bind(mc, WORLD_B, True, timeout=40)
    st = await status(mc)
    report["after_b"] = {k: st[k] for k in ("pairingSwitchSaved", "saveSwitchWrites", "ingame", "saveSlot")}

    # the driver's New Game in B's slot, never saved; A's slot has a save: the switch waits until the
    # driver quits to the title
    report["fresh"] = await marker(running, "KB_FRESH", 90)
    sent = await mc.send("session.bind", {"world": WORLD_A, "multiplayer": True})
    bound = await mc.expect("session.bound", timeout=60, re=sent["seq"])
    report["a_after_wait"] = bound["data"]["slot"]
    at = next(i for i, m in enumerate(mc.received) if m is bound)
    waits = [m for m in mc.received[:at] if m["type"] == "log" and m["data"]["code"] == "session.switch_waiting"]
    report["waited_new_game"] = any("reason=new_game" in m["data"]["msg"] for m in waits)
    report["quit_to_title"] = running.result.marker("KB_QUIT_TO_TITLE") is not None

    report["home"] = await bind(mc, WORLD_SOLO, False)
    st = await status(mc)
    report["status"] = {k: st[k] for k in ("pairingCreated", "pairingSwitchTitle", "pairingSwitchSaved",
                                           "pairingSwitchFresh", "pairingWaitNewGame", "pairingPending",
                                           "pairingHomeReturns", "saveSwitchWrites", "saveSlot", "ingame")}
    report["save_begins"] = [m["data"]["reason"] for m in mc.received if m["type"] == "save.begin"]
    report["errors"] = errors(mc)
    report["problems"] = len(mc.conn.problems)
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return report


def _cleanup_run():
    with launch("kb_multiplayer.lua", extra_env=dict(WORLD_ENV, KB_TEST_PORT=str(TEST_PORT), KB_SCENE="cleanup"),
                echo=False) as running:
        return running.wait(timeout=120)


@pytest.mark.e2e
@pytest.mark.timeout(900)
def test_each_world_gets_its_own_save_slot_and_the_offline_save_is_untouched():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    game = pf.game
    before = save_files(game)
    clean = False
    try:
        # ---- launch 1: pairing at the title
        env = dict(WORLD_ENV, KB_TEST_PORT=str(TEST_PORT), KB_SCENE="pairing1")
        with launch("kb_multiplayer.lua", extra_env=env) as running:
            booted = running.wait_marker("KB_BOOTED", timeout=150)
            assert booted is not None, running.result.summary()
            boot = running.result.facts("KB_BOOTED")
            a, b, home, r1 = asyncio.run(asyncio.wait_for(_pairing1(TEST_PORT), 120))
            first = running.wait(timeout=60)
        print("launch 1:", {"home": home, "a": a, "b": b, **r1})
        print("launch 1 markers:", [line for line in first.lines if line.startswith(("KB_PRE", "KB_BOOTED",
                                                                                        "KB_ACTIVE", "KB_QUIT"))])
        assert not first.timed_out and first.returncode == 0, first.summary()
        assert first.marker("KB_FAIL") is None and not first.crash_log_touched and not first.error_spots
        assert int(boot["home"]) in (0, home) and int(boot["active"]) == home, "booted on the offline slot"
        assert r1["ingame_at_boot"] is False
        assert home not in (a, b) and a != b and a >= 1 and b >= 1, "a slot per world, apart from the offline one"
        assert r1["a_again"] == a, "a known world keeps its slot"
        st = r1["status"]
        assert (st["pairingCreated"], st["pairingReused"], st["pairingSwitchTitle"], st["pairingSwitches"]) == \
            (2, 1, 3, 3)
        assert (st["pairingHomeReturns"], st["pairingPending"], st["pairingWorlds"], st["saveSlot"]) == \
            (1, False, 2, home)
        assert st["ingame"] is False and st["saveBegun"] == 0
        assert r1["errors"] == [] and r1["problems"] == 0
        # MG1: one pairing file per game (the process's own), session/pairings.<game>.json
        # MG1: the game's own file (and its .bak); an install that paired before MG1 keeps its legacy install-wide
        # pairings.json beside it, read once and never rewritten or deleted
        names = set(pairing_file_names())
        assert {f"pairings.{game}.json", f"pairings.{game}.json.bak"} <= names, "the pairing file, by name"
        assert names <= {f"pairings.{game}.json", f"pairings.{game}.json.bak", "pairings.json", "pairings.json.bak"}, names
        mid = save_files(game)
        assert slot_files(mid, home) == slot_files(before, home), "the offline save untouched"
        assert not slot_files(mid, a) and not slot_files(mid, b), "no saves in the new slots yet"

        # ---- launch 2: after a restart; in game; a waiting switch; clean-up
        env = dict(WORLD_ENV, KB_TEST_PORT=str(TEST_PORT), KB_SCENE="pairing2")
        with launch("kb_multiplayer.lua", extra_env=env) as running:
            booted = running.wait_marker("KB_BOOTED", timeout=150)
            assert booted is not None, running.result.summary()
            boot2 = running.result.facts("KB_BOOTED")
            r2 = asyncio.run(asyncio.wait_for(_pairing2(TEST_PORT, running, game), 300))
            second = running.wait(timeout=60)
        files_saved = r2.pop("files_after_saved")
        print("launch 2:", r2)
        print("launch 2 markers:", [line for line in second.lines if line.startswith((
            "KB_BOOTED", "KB_ACTIVE", "KB_SAVE_WRITING", "KB_SAVED", "KB_FRESH", "KB_QUIT_TO_TITLE", "KB_CLEANED",
            "KB_QUIT"))])
        assert not second.timed_out and second.returncode == 0, second.summary()
        assert second.marker("KB_FAIL") is None and not second.crash_log_touched and not second.error_spots
        assert (int(boot2["pairedA"]), int(boot2["pairedB"]), int(boot2["active"])) == (a, b, home), \
            "the pairing survived the restart"
        assert r2["a"] == a and r2["created_after_restart"] == 0
        assert r2["saved"] == {"slot": str(a), "ok": "true"}
        assert slot_files(files_saved, a), "the New Game's save landed in A's slot"
        assert slot_files(files_saved, home) == slot_files(before, home)
        assert r2["b"] == b
        assert r2["after_b"] == {"pairingSwitchSaved": 1, "saveSwitchWrites": 1, "ingame": False, "saveSlot": b}
        assert r2["fresh"] == {"slot": str(b), "saved": "false"}
        assert r2["a_after_wait"] == a and r2["waited_new_game"] and r2["quit_to_title"]
        assert r2["home"] == home
        st = r2["status"]
        assert (st["pairingCreated"], st["pairingSwitchSaved"], st["pairingWaitNewGame"], st["pairingPending"]) == \
            (0, 1, 1, False)
        assert (st["pairingSwitchTitle"], st["pairingHomeReturns"], st["saveSwitchWrites"], st["saveSlot"]) == \
            (3, 1, 1, home)
        assert st["ingame"] is False
        assert r2["save_begins"] == ["menu"], "the driver's own save only: the switch's save is never announced"
        assert r2["errors"] == [] and r2["problems"] == 0
        writes = [line for line in second.lines if line.startswith("KB_SAVE_WRITING")]
        assert writes == [f"KB_SAVE_WRITING slot={a}"] * 2, "the New Game's save and the switch's, both in A's slot"
        cleaned = second.facts("KB_CLEANED")
        assert (int(cleaned["deleted"]), int(cleaned["active"])) == (2, home)
        clean = True
    finally:
        if not clean:
            _cleanup_run()
    after = save_files(game)
    print("save files:", {"before": digest(before), "after": digest(after),
                          "home_before": digest(slot_files(before, home)),
                          "home_after": digest(slot_files(after, home)), "count": len(after)})
    assert slot_files(after, home) == slot_files(before, home), "the offline save untouched (names, sizes, times)"
    assert after == before, "the test slots' files are gone"


# ------------------------------------------------------------ the port fallback


def _free(port):
    probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        probe.bind(("127.0.0.1", port))
        return True
    except OSError:
        return False
    finally:
        probe.close()


def _occupy(port):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.bind(("127.0.0.1", port))
    sock.listen(1)
    return sock


async def _port(port):
    mc, _ = await connect(port)
    st = await status(mc)
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return {k: st[k] for k in ("port", "portConfigured", "portFallback")}, len(mc.conn.problems), errors(mc)


@pytest.mark.e2e
@pytest.mark.timeout(300)
def test_a_busy_port_makes_the_instance_take_the_next_free_one():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    ports = [FALLBACK_PORT, FALLBACK_PORT + 1, FALLBACK_PORT + 2]
    if not all(_free(p) for p in ports):
        pytest.skip("the test's ports are in use on this machine")
    held = [_occupy(FALLBACK_PORT), _occupy(FALLBACK_PORT + 1)]  # another instance and a stray listener
    try:
        env = {"KB_TEST_PORT": str(FALLBACK_PORT), "KB_SCENE": "port"}
        with launch("kb_multiplayer.lua", extra_env=env) as running:
            line = running.wait_marker("KB_LISTENING", timeout=150)
            assert line is not None, running.result.summary()
            facts = running.result.facts("KB_LISTENING")
            reply, problems, errs = asyncio.run(asyncio.wait_for(_port(FALLBACK_PORT + 2), 60))
            result = running.wait(timeout=60)
    finally:
        for sock in held:
            sock.close()
    print("listening:", facts, "debug.status:", reply)
    print("bridge lines:", [ln for ln in result.lines if "net.bind_failed" in ln or "net.listening" in ln])
    assert facts == {"port": str(FALLBACK_PORT + 2), "configured": str(FALLBACK_PORT), "fallback": "true"}
    assert reply == {"port": FALLBACK_PORT + 2, "portConfigured": FALLBACK_PORT, "portFallback": True}
    assert problems == 0 and errs == []
    # the line ends with `reuseaddr=true` on every platform but Windows (gen1-bridge README, "Network")
    assert any(f"net.listening configured={FALLBACK_PORT} fallback=true host=127.0.0.1 "
               f"port={FALLBACK_PORT + 2}" in ln for ln in result.lines)
    assert not result.timed_out and result.returncode == 0, result.summary()
    assert result.marker("KB_QUIT") is not None and result.marker("KB_FAIL") is None
    assert not result.crash_log_touched and not result.error_spots
