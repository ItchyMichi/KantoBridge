"""M1 end to end, on the real engine: a hot reload (F5's HotReload.run) closes
the old gen1-bridge instance's connection, the new instance binds the same
port, and a reconnect reaches it with a new session id.

Runs only with --e2e; skips when the pre-flight isn't done. Prints only
types, symbols and counts.
"""

import asyncio

import pytest

from fake_minecraft import FakeMinecraft
from run_gen1recomp import launch, preflight

TEST_PORT = 27198


async def _talk(port):
    first = FakeMinecraft(port=port, role="test", ping_ms=300)
    await first.connect(attempts=20)
    old = (await first.hello())["data"]["session"]
    await first.expect("snapshot", timeout=10)
    # the reload closes this connection at once (the takeover slot): wait for the end
    while (await first.next(timeout=10)) is not None:
        pass
    await first.close()

    second = FakeMinecraft(port=port, role="test", ping_ms=300)
    await second.connect(attempts=20)
    new = (await second.hello())["data"]["session"]
    assert new != old, "the reconnect reached the reloaded instance"
    await second.expect("snapshot", timeout=10)
    status = (await second.request("debug.status", reply_type="debug.status"))["data"]
    assert status["sessions"] == 1 and status["port"] == port
    await second.send("debug.quit", {"code": 0})
    await second.close()


@pytest.mark.e2e
@pytest.mark.timeout(300)
def test_hot_reload_releases_and_rebinds_the_port():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    with launch("kb_reload.lua", extra_env={"KB_TEST_PORT": str(TEST_PORT)}) as running:
        assert running.wait_marker("KB_OVERWORLD", timeout=150) is not None, running.result.summary()
        asyncio.run(asyncio.wait_for(_talk(TEST_PORT), 120))
        result = running.wait(timeout=60)
    assert not result.timed_out and result.returncode == 0, result.summary()
    assert result.marker("KB_RELOADED new_session=true") is not None, result.summary()
    assert result.marker("KB_QUIT") is not None and result.marker("KB_FAIL") is None
    loads = [line for line in result.lines if line == "[info] [gen1-bridge] gen1-bridge loaded"]
    assert len(loads) == 2, "the entry chunk ran again on reload"
    assert "[info] [gen1-bridge] bridge.replaced_instance" in result.lines
    assert not any("net.bind_failed" in line for line in result.lines), "the port was free for the new instance"
    assert not result.crash_log_touched and not result.error_spots
