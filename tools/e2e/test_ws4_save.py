"""WORLD_SESSIONS_PLAN WS4 on the real engine (each imported family), with fake_minecraft in the client's role: a game in
play answers save.request{world} with act.result ok and a save.begin{reason:"world"} (answered save.done); a held game
(region.park) saves too; Blue with a text box of the driver's own open answers not_idle after its wait; at the title,
not_ingame. The driver is drivers/kb_ws3.lua (an offline save of its own, continued by a bind's continue play).

Output: types, reasons, slot numbers and booleans only.
"""

import asyncio
import tempfile
import time
from pathlib import Path

import pytest

import kb_games
from fake_minecraft import FakeMinecraft
from run_gen1recomp import INSTALL, launch, preflight

TEST_PORT = 27212
WORLD = "kb-e2e-ws4-world"
GAMES = ("blue", "crystal", "emerald")


async def ask(mc, reason="world", timeout=60):
    since = len(mc.received)
    sent = await mc.send("save.request", {"reason": reason})
    result = await mc.expect("act.result", timeout=timeout, re=sent["seq"])
    begin = next((m for m in mc.received[since:] if m["type"] == "save.begin"), None)
    if begin is None and result["data"].get("ok"):
        begin = await mc.expect("save.begin", timeout=20)
    if begin is not None:
        await mc.send("save.done", {"save": begin["data"]["save"], "ok": True})
    return result["data"], (begin["data"]["reason"] if begin else None)


async def scene(port, game, req_dir, running):
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=30)
    await mc.hello()
    await mc.expect("snapshot", timeout=20)
    facts = {}
    sent = await mc.send("session.bind", {"world": WORLD, "multiplayer": False, "save": "current", "continue": "play"})
    facts["bound"] = (await mc.expect("session.bound", timeout=180, re=sent["seq"]))["data"].get("started")
    await asyncio.sleep(2)
    facts["idle"] = await ask(mc)
    park = await mc.request("region.park", {"region": "kanto"}, reply_type="act.result", timeout=60)
    facts["park"] = park["data"].get("ok")
    facts["held"] = await ask(mc)
    await mc.request("region.resume", {"region": "kanto", "to": (await mc.request("resync", reply_type="snapshot"))["data"]["player"]},
                     reply_type="act.result", timeout=60)
    if game == "blue":
        Path(req_dir, "request.txt").write_text("text\n", encoding="utf-8")
        for _ in range(50):
            if running.result.marker("KB_TEXT"):
                break
            await asyncio.sleep(0.2)
        started = time.monotonic()
        facts["text"] = await ask(mc, timeout=60)
        facts["text_wait_s"] = round(time.monotonic() - started)
    Path(req_dir, "request.txt").write_text("title\n", encoding="utf-8")
    for _ in range(50):
        if running.result.marker("KB_TITLE"):
            break
        await asyncio.sleep(0.2)
    await asyncio.sleep(1)
    facts["title"] = await ask(mc)
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return facts


@pytest.mark.e2e
@pytest.mark.timeout(1800)
@pytest.mark.parametrize("game", GAMES)
def test_ws4_a_save_request_saves_a_game_in_play_or_held_and_refuses_otherwise(game):
    pf = preflight()
    if game not in kb_games.detect(INSTALL):
        pytest.skip(f"{game} isn't imported in the test install")
    req = tempfile.mkdtemp(prefix="kb-ws4-")
    env = {"KB_TEST_PORT": str(TEST_PORT), "KB_DATASET_MEMO": "30", "KB_WORLD": WORLD, "KB_WS3_DIR": req}
    home = None
    try:
        with launch("kb_ws3.lua", game=game, extra_env=dict(env, KB_SCENE="offline")) as running:
            assert running.wait_marker("KB_OFFLINE", timeout=600) is not None, running.result.summary()
            booted = running.result.marker("KB_BOOTED")
            home = dict(p.split("=", 1) for p in booted.split()[1:] if "=" in p).get("home")
            facts = asyncio.run(asyncio.wait_for(scene(TEST_PORT, game, req, running), 900))
            running.wait(timeout=120)
        print(game, "ws4:", facts)
        assert facts["bound"] == "continue"
        assert facts["idle"] == ({"kind": "save", "ok": True}, "world"), facts
        assert facts["park"] is True and facts["held"] == ({"kind": "save", "ok": True}, "world"), facts
        if game == "blue":
            assert facts["text"] == ({"kind": "save", "ok": False, "reason": "not_idle"}, None), facts
        assert facts["title"] == ({"kind": "save", "ok": False, "reason": "not_ingame"}, None), facts
    finally:
        with launch("kb_ws3.lua", game=game, extra_env=dict(env, KB_SCENE="cleanup",
                                                           **({"KB_HOME_SLOT": f"slot{home}"} if home else {}))) as running:
            print(game, "cleanup:", running.wait(timeout=300).marker("KB_CLEANED"))
