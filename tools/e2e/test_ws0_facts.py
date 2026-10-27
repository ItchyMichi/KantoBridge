"""WORLD_SESSIONS_PLAN WS0 items 3-5 on the real engine, per game family (Blue, Crystal, Emerald: whichever are
imported in the test install): a copy of the boot slot's save (SaveData.duplicateSlot, D14's safe copy), the title's
summary of it, CONTINUE, the region hold, a save written while the hold is up (a parked game saving), a return to the
title after it and the held save loading again. The driver is drivers/kb_ws0facts.lua; it runs gen1-bridge's own engine
adapter so one script serves every family. The install's own saves are only read (copied), never written; the copy is
deleted at the end.

Output: markers with map symbols, slot numbers, counts and booleans only.
"""

import asyncio

import pytest

import kb_games
from fake_minecraft import FakeMinecraft
from run_gen1recomp import INSTALL, launch, preflight

TEST_PORT = 27209
GAMES = ("blue", "crystal", "emerald")


async def _serve(port, until, timeout):
    """A test session that answers every save.begin, until until() holds; then debug.quit."""
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=30)
    await mc.hello()
    begins = []
    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout
    while loop.time() < deadline and not until():
        try:
            m = await mc.next(0.5)
        except asyncio.TimeoutError:
            continue
        if m is None:
            break
        if m["type"] == "save.begin":
            begins.append(m["data"].get("reason"))
            await mc.send("save.done", {"save": m["data"]["save"], "ok": True})
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return begins


@pytest.mark.e2e
@pytest.mark.timeout(2400)
@pytest.mark.parametrize("game", GAMES)
def test_ws0_held_save_title_summary_and_copy(game):
    pf = preflight()
    if game not in kb_games.detect(INSTALL):
        pytest.skip(f"{game} isn't imported in the test install")
    with launch("kb_ws0facts.lua", game=game, extra_env={"KB_TEST_PORT": str(TEST_PORT), "KB_DATASET_MEMO": "30"}) as running:
        booted = running.wait_marker("KB_BOOTED", timeout=300)
        assert booted is not None, running.result.summary()
        if "homeSave=true" not in booted:
            running.kill()
            pytest.skip(f"{game}: the test install's boot slot holds no save to copy")
        begins = asyncio.run(_serve(TEST_PORT, lambda: running.result.marker("KB_CONTINUED_AGAIN") is not None
                                    or running.result.marker("KB_FAIL") is not None, 1200))
        result = running.wait(timeout=120)
    names = ("KB_BOOTED", "KB_COPY", "KB_TITLE_SUMMARY", "KB_CONTINUED", "KB_INGAME", "KB_HELD", "KB_HELD_SAVE",
             "KB_TO_TITLE", "KB_TITLE_AGAIN", "KB_CONTINUED_AGAIN", "KB_CLEANED", "KB_FAIL")
    for name in names:
        print(game, result.marker(name))
    print(game, "save.begin reasons:", begins)
    assert result.marker("KB_FAIL") is None, result.summary()
    assert "ok=true" in result.marker("KB_COPY")
    assert result.marker("KB_CONTINUED_AGAIN") is not None
