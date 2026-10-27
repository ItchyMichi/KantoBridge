"""WORLD_SESSIONS_PLAN WS3, check C2 on the real engine (each imported family: Blue, Crystal, Emerald), with
fake_minecraft in the client's role (as test_multiplayer.py does). The driver is drivers/kb_ws3.lua.

Launch 1: an "offline" slot of the driver's own holds a saved game; the game waits at its title, and game.status says
so (save{slot}). The first join of a server world: session.bind{multiplayer, save:"copy", continue} (play for Blue,
park for the others, as a world following Blue would send) -> session.bound{save:"copy", started:"continue",
parked} on a new slot (the world's own) whose game is the offline save's (the same map); Blue then takes a step and
saves. Launch 2 (a relaunch): the rejoin binds the recorded slot (slot, continue play, no choice) -> started
"continue" on that slot, where Blue saved. The offline slot's file is never written (a listing before and after). A
last launch forgets the world's pairing and deletes the test slots.

Output: markers and messages with map symbols, cells, slot numbers and booleans only; the world id is made up.
"""

import asyncio
import os
import tempfile
from pathlib import Path

import pytest

import kb_games
from fake_minecraft import FakeMinecraft
from run_gen1recomp import INSTALL, launch, preflight

TEST_PORT = 27211
WORLD = "kb-e2e-ws3-world"
GAMES = ("blue", "crystal", "emerald")
DIRS = ("up", "down", "left", "right")


def listing(game, slot):
    path = INSTALL / "saves" / game / f"slot{slot}.lua"
    if not path.is_file():
        return None
    st = path.stat()
    return st.st_size, st.st_mtime_ns


def facts_of(line):
    return dict(p.split("=", 1) for p in line.split()[1:] if "=" in p) if line else {}


async def join(port, data, walk=False, request_dir=None, running=None):
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=30)
    await mc.hello()
    await mc.expect("snapshot", timeout=20)
    status = await mc.expect("game.status", timeout=20)
    sent = await mc.send("session.bind", data)
    bound = await mc.expect("session.bound", timeout=180, re=sent["seq"])
    # data safety: the player's name never leaves the test (names, counts and slots only)
    title = {k: v for k, v in status["data"].items() if k != "name"}
    if isinstance(title.get("save"), dict):
        title["save"] = {k: v for k, v in title["save"].items() if k != "name"}
        title["save_named"] = "name" in status["data"]["save"]
    facts = {"title_status": title, "bound": bound["data"]}
    if walk:
        for d in DIRS:
            await mc.send("move.step", {"dir": d, "n": DIRS.index(d) + 1})
            try:
                m = await mc.expect("player.pos", timeout=3)
                if m["data"].get("kind") != "sync":
                    facts["walked"] = d
                    break
            except asyncio.TimeoutError:
                pass
        await asyncio.sleep(1.5)
        Path(request_dir, "request.txt").write_text("save\n", encoding="utf-8")
        for _ in range(100):
            if running.result.marker("KB_SAVED"):
                break
            await asyncio.sleep(0.2)
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return facts


@pytest.mark.e2e
@pytest.mark.timeout(2400)
@pytest.mark.parametrize("game", GAMES)
def test_ws3_first_join_copies_the_offline_save_and_a_rejoin_continues_its_slot(game):
    pf = preflight()
    if game not in kb_games.detect(INSTALL):
        pytest.skip(f"{game} isn't imported in the test install")
    cont = "play" if game == "blue" else "park"
    req = tempfile.mkdtemp(prefix="kb-ws3-")
    env = {"KB_TEST_PORT": str(TEST_PORT), "KB_DATASET_MEMO": "30", "KB_WORLD": WORLD, "KB_WS3_DIR": req}
    home = None
    try:
        with launch("kb_ws3.lua", game=game, extra_env=dict(env, KB_SCENE="offline")) as running:
            offline = running.wait_marker("KB_OFFLINE", timeout=600)
            assert offline is not None, running.result.summary()
            booted = facts_of(running.result.marker("KB_BOOTED"))
            home = booted.get("home")
            off = facts_of(offline)
            before = listing(game, off["slot"])
            first = asyncio.run(asyncio.wait_for(join(TEST_PORT, {"world": WORLD, "multiplayer": True, "save": "copy",
                                                                   "continue": cont}, walk=(cont == "play"),
                                                          request_dir=req, running=running), 600))
            r1 = running.wait(timeout=120)
        actives = [line for line in r1.lines if line.startswith("KB_ACTIVE")]
        print(game, "launch 1:", offline, "|", first, "|", actives, "|", r1.marker("KB_SAVED"))
        b = first["bound"]
        assert first["title_status"].get("save", {}).get("slot") == int(off["slot"]), first["title_status"]
        assert (b.get("save"), b.get("started"), b.get("parked")) == ("copy", "continue", cont == "park"), b
        assert b["slot"] != int(off["slot"]), "the world's own slot"
        assert any(f"slot={b['slot']} ingame=true map={off['map']}" in line for line in actives), actives
        assert listing(game, off["slot"]) == before, "the offline save is never written"
        saved = facts_of(r1.marker("KB_SAVED")) if cont == "play" else None
        if cont == "play":
            assert saved and saved["slot"] == str(b["slot"]), r1.marker("KB_SAVED")
        with launch("kb_ws3.lua", game=game, extra_env=dict(env, KB_SCENE="serve", KB_HOME_SLOT=f"slot{home}")) as running:
            assert running.wait_marker("KB_BOOTED", timeout=600) is not None, running.result.summary()
            second = asyncio.run(asyncio.wait_for(join(TEST_PORT, {"world": WORLD, "multiplayer": True, "slot": b["slot"],
                                                                    "continue": "play"}), 600))
            r2 = running.wait(timeout=120)
        actives2 = [line for line in r2.lines if line.startswith("KB_ACTIVE")]
        print(game, "launch 2:", second, "|", actives2)
        b2 = second["bound"]
        assert (b2["slot"], b2.get("started"), b2.get("parked")) == (b["slot"], "continue", False), b2
        assert "save" not in b2
        where = saved["map"] if saved else off["map"]
        assert any(f"slot={b['slot']} ingame=true map={where}" in line for line in actives2), actives2
    finally:
        with launch("kb_ws3.lua", game=game, extra_env=dict(env, KB_SCENE="cleanup",
                                                           **({"KB_HOME_SLOT": f"slot{home}"} if home else {}))) as running:
            print(game, "cleanup:", running.wait(timeout=300).marker("KB_CLEANED"))
