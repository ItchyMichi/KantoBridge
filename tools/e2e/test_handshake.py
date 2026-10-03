"""M1 end to end, on the real engine: fake_minecraft (role test) against
gen1-bridge in the Gen1Recomp test instance (docs/PLAN.md M1 [T]).

Runs only with --e2e, and skips when the pre-flight (PLAN section 0.6) isn't
done. gen1-bridge is moved to a test port, so a user instance on 27151 can't
interfere. Only types, symbols and counts are ever printed; assertion
messages name keys, never values from the game.
"""

import asyncio

import pytest

from fake_minecraft import FakeMinecraft
from run_gen1recomp import launch, preflight

TEST_PORT = 27199


async def _talk(port, game):
    first = FakeMinecraft(port=port, role="test", ping_ms=300)
    await first.connect(attempts=20)
    ok = await first.hello()
    data = ok["data"]
    assert (data["protocol"], data["engine"], data["game"]) == (1, "0.3.49", game)
    snap = await first.expect("snapshot", timeout=10)
    player = snap["data"]["player"]
    assert {"map", "x", "y", "facing"} <= set(player), sorted(player)
    for _ in range(3):
        await first.expect("pong", timeout=5)
    assert not first.conn.problems  # strict: every message passed its schema
    session = data["session"]
    await first.close()

    second = FakeMinecraft(port=port, role="test", ping_ms=300)
    await second.connect(attempts=20)
    again = await second.hello()
    assert again["data"]["session"] == session, "a reconnect reaches the same gen1-bridge start"
    await second.expect("snapshot", timeout=10)
    status = (await second.request("debug.status", reply_type="debug.status"))["data"]
    assert status["ingame"] is True and isinstance(status["npcs"], int) and isinstance(status["flags"], int)
    assert status["sessions"] == 2 and status["port"] == port
    assert status["optionsPinned"] is True, "display options pinned to classic (PLAN P21)"
    await second.send("debug.quit", {"code": 0})
    await second.close()
    return first.counts, second.counts


@pytest.mark.e2e
@pytest.mark.timeout(300)
def test_handshake_heartbeat_and_reconnect():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    with launch("kb_handshake.lua", extra_env={"KB_TEST_PORT": str(TEST_PORT)}) as running:
        marker = running.wait_marker("KB_OVERWORLD", timeout=150)
        assert marker is not None, running.result.summary()
        assert f"port={TEST_PORT}" in marker
        first, second = asyncio.run(asyncio.wait_for(_talk(TEST_PORT, pf.game), 90))
        result = running.wait(timeout=60)
    print("first connection:", dict(sorted(first.items())))
    print("second connection:", dict(sorted(second.items())))
    assert not result.timed_out and result.returncode == 0, result.summary()
    assert result.marker("KB_QUIT") is not None and result.marker("KB_FAIL") is None
    assert not result.crash_log_touched
    assert not result.error_spots
