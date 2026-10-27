"""WORLD_SESSIONS_PLAN WS5, check C4 on the real engine (Blue played, Crystal parked; Emerald too when imported), with
fake_minecraft in the client's role. The driver is drivers/kb_ws3.lua: an offline save of its own at the title.

The join: session.bind{multiplayer, save:"copy", continue} -> the world's own slot, continued (Crystal also parked).
"Save and Quit": save.request{quit} -> act.result ok and save.begin{quit}; then session.close -> session.closed{title:true,
home:<the offline slot>}: the game is at its title on its offline slot again (PROGRESS question 3). The rejoin: the
recorded slot with continue play -> started "continue" on the world's slot again. Last, a quit whose save is refused
(at the title: not_ingame) still closes cleanly.

Output: types, slot numbers and booleans only.
"""

import asyncio
import tempfile

import pytest

import kb_games
from fake_minecraft import FakeMinecraft
from run_gen1recomp import INSTALL, launch, preflight

TEST_PORT = 27213
WORLD = "kb-e2e-ws5-world"
GAMES = ("blue", "crystal", "emerald")


async def scene(port, cont):
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=30)
    await mc.hello()
    await mc.expect("snapshot", timeout=20)
    f = {}
    sent = await mc.send("session.bind", {"world": WORLD, "multiplayer": True, "save": "copy", "continue": cont})
    f["bound"] = (await mc.expect("session.bound", timeout=180, re=sent["seq"]))["data"]
    since = len(mc.received)
    req = await mc.send("save.request", {"reason": "quit"})
    f["quit_save"] = (await mc.expect("act.result", timeout=60, re=req["seq"]))["data"]
    begin = next((m for m in mc.received[since:] if m["type"] == "save.begin"), None) or await mc.expect("save.begin", timeout=20)
    f["quit_reason"] = begin["data"]["reason"]
    await mc.send("save.done", {"save": begin["data"]["save"], "ok": True})
    close = await mc.send("session.close", {})
    f["closed"] = (await mc.expect("session.closed", timeout=60, re=close["seq"]))["data"]
    f["after_close"] = (await mc.request("resync", reply_type="snapshot", timeout=20))["data"]["ingame"]
    sent = await mc.send("session.bind", {"world": WORLD, "multiplayer": True, "slot": f["bound"]["slot"], "continue": "play"})
    f["rejoin"] = (await mc.expect("session.bound", timeout=180, re=sent["seq"]))["data"]
    close = await mc.send("session.close", {})
    f["closed2"] = (await mc.expect("session.closed", timeout=60, re=close["seq"]))["data"]
    req = await mc.send("save.request", {"reason": "quit"})
    f["title_save"] = (await mc.expect("act.result", timeout=60, re=req["seq"]))["data"]
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return f


@pytest.mark.e2e
@pytest.mark.timeout(1800)
@pytest.mark.parametrize("game", GAMES)
def test_ws5_quit_saves_closes_to_the_title_and_the_offline_slot_and_a_rejoin_continues(game):
    pf = preflight()
    if game not in kb_games.detect(INSTALL):
        pytest.skip(f"{game} isn't imported in the test install")
    cont = "play" if game == "blue" else "park"
    req = tempfile.mkdtemp(prefix="kb-ws5-")
    env = {"KB_TEST_PORT": str(TEST_PORT), "KB_DATASET_MEMO": "30", "KB_WORLD": WORLD, "KB_WS3_DIR": req}
    home = None
    try:
        with launch("kb_ws3.lua", game=game, extra_env=dict(env, KB_SCENE="offline")) as running:
            offline = running.wait_marker("KB_OFFLINE", timeout=600)
            assert offline is not None, running.result.summary()
            off = dict(p.split("=", 1) for p in offline.split()[1:] if "=" in p)
            booted = running.result.marker("KB_BOOTED")
            home = dict(p.split("=", 1) for p in booted.split()[1:] if "=" in p).get("home")
            f = asyncio.run(asyncio.wait_for(scene(TEST_PORT, cont), 900))
            r = running.wait(timeout=120)
        actives = [line for line in r.lines if line.startswith("KB_ACTIVE")]
        print(game, "ws5:", f, "|", actives)
        assert f["bound"].get("started") == "continue" and f["bound"].get("parked") == (cont == "park"), f
        assert f["quit_save"] == {"kind": "save", "ok": True} and f["quit_reason"] == "quit", f
        assert f["closed"] == {"title": True, "home": int(off["slot"])}, f
        assert f["after_close"] is False, f
        assert any(line.startswith(f"KB_ACTIVE slot={off['slot']} ingame=false") for line in actives), actives
        assert f["rejoin"]["slot"] == f["bound"]["slot"] and f["rejoin"].get("started") == "continue", f
        assert f["closed2"]["title"] is True, f
        assert f["title_save"] == {"kind": "save", "ok": False, "reason": "not_ingame"}, f
    finally:
        with launch("kb_ws3.lua", game=game, extra_env=dict(env, KB_SCENE="cleanup",
                                                           **({"KB_HOME_SLOT": f"slot{home}"} if home else {}))) as running:
            print(game, "cleanup:", running.wait(timeout=300).marker("KB_CLEANED"))
