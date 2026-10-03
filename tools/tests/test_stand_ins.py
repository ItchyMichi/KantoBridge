"""fake_minecraft against fake_gen1: the connection rules of docs/PROTOCOL.md."""
import asyncio

import pytest

from fake_gen1 import FakeGen1
from fake_minecraft import FakeMinecraft, HelloRejected
from kb_protocol import encode_line


def run(coro):
    return asyncio.run(asyncio.wait_for(coro, 30))


async def _pair(role="test", timeout_s=10.0, ping_ms=0, **client_kw):
    gen1 = await FakeGen1(port=0, timeout_s=timeout_s).start()
    mc = await FakeMinecraft(port=gen1.port, role=role, ping_ms=ping_ms, **client_kw).connect()
    return gen1, mc


def test_handshake_then_snapshot():
    async def scenario():
        gen1, mc = await _pair()
        try:
            ok = await mc.hello()
            assert ok["re"] == 1
            assert ok["data"]["protocol"] == 1 and ok["data"]["game"] == "red"
            snap = await mc.expect("snapshot")
            assert snap["data"]["player"] == {"map": "TEST_TOWN", "x": 5, "y": 8, "facing": "down"}
            assert snap["data"]["npcs"]["map"] == "TEST_TOWN" and len(snap["data"]["npcs"]["list"]) == 3
            assert not mc.conn.problems
        finally:
            await mc.close()
            await gen1.stop()
    run(scenario())


def test_protocol_mismatch_is_rejected():
    async def scenario():
        gen1, mc = await _pair()
        try:
            with pytest.raises(HelloRejected) as info:
                await mc.hello(protocol=2)
            assert info.value.reason == "protocol"
            assert await mc.next() is None, "gen1 side closes after hello.reject"
        finally:
            await mc.close()
            await gen1.stop()
    run(scenario())


def test_hello_must_come_first():
    async def scenario():
        gen1, mc = await _pair()
        try:
            await mc.send("resync")
            err = await mc.expect("error")
            assert err["data"]["code"] == "no_hello" and err["re"] == 1
            assert await mc.next() is None
        finally:
            await mc.close()
            await gen1.stop()
    run(scenario())


def test_heartbeat_pongs():
    async def scenario():
        gen1, mc = await _pair(ping_ms=100)
        try:
            await mc.hello()
            pongs = [await mc.expect("pong", timeout=3) for _ in range(3)]
            assert all("re" in p for p in pongs)
        finally:
            await mc.close()
            await gen1.stop()
    run(scenario())


def test_silent_client_is_dropped():
    async def scenario():
        gen1, mc = await _pair(timeout_s=0.3)
        try:
            await mc.hello(start_pings=False)
            await mc.expect("snapshot")
            assert await mc.next(timeout=3) is None
        finally:
            await mc.close()
            await gen1.stop()
    run(scenario())


def test_new_connection_replaces_old():
    async def scenario():
        gen1, first = await _pair()
        second = None
        try:
            await first.hello()
            second = await FakeMinecraft(port=gen1.port, ping_ms=0).connect()
            await second.hello()
            await first.expect("snapshot")
            assert await first.next(timeout=3) is None, "the old connection is closed"
            assert gen1.connections == 2
            assert (await second.request("debug.status"))["data"]["connections"] == 2
        finally:
            await first.close()
            if second:
                await second.close()
            await gen1.stop()
    run(scenario())


def test_resync_answers_with_snapshot():
    async def scenario():
        gen1, mc = await _pair()
        try:
            await mc.hello()
            await mc.expect("snapshot")
            snap = await mc.request("resync", reply_type="snapshot")
            assert snap["data"]["player"]["map"] == "TEST_TOWN"
        finally:
            await mc.close()
            await gen1.stop()
    run(scenario())


def test_steps_blocks_and_door_warp():
    async def scenario():
        gen1, mc = await _pair()
        try:
            await mc.hello()
            await mc.send("move.step", {"dir": "up", "n": 1})
            pos = await mc.expect("player.pos")
            assert (pos["data"]["x"], pos["data"]["y"], pos["data"]["kind"], pos["data"]["n"]) == (5, 7, "walk", 1)
            await mc.send("move.step", {"dir": "up", "n": 2})
            await mc.expect("player.pos")
            await mc.send("move.step", {"dir": "up", "n": 3})  # into the door at (5, 5)
            await mc.expect("player.pos")
            start = await mc.expect("warp")
            arrive = await mc.expect("warp")
            assert (start["data"]["phase"], arrive["data"]["phase"]) == ("start", "arrive")
            assert arrive["data"]["to"]["map"] == "TEST_HOUSE"
            npcs = await mc.expect("npc.set")
            assert npcs["data"]["map"] == "TEST_HOUSE"
            await mc.send("move.step", {"dir": "left", "n": 4})
            await mc.send("move.step", {"dir": "left", "n": 5})
            await mc.send("move.step", {"dir": "left", "n": 6})  # into the wall at x 0
            await mc.expect("player.pos")
            await mc.expect("player.pos")
            blocked = await mc.expect("player.blocked")
            assert (blocked["data"]["reason"], blocked["data"]["n"]) == ("tile", 6)
        finally:
            await mc.close()
            await gen1.stop()
    run(scenario())


def test_talk_to_npc_opens_and_closes_text():
    async def scenario():
        gen1, mc = await _pair()
        try:
            await mc.hello()
            await gen1.teleport("TEST_TOWN", 10, 9, "up")
            result = await mc.request("act.interact", {"map": "TEST_TOWN", "x": 10, "y": 8}, reply_type="act.result")
            assert result["data"] == {"ok": True, "kind": "npc"}
            opened = await mc.expect("ui.open")
            assert opened["data"]["kind"] == "text" and len(opened["data"]["pages"]) == 1
            await mc.send("ui.advance", {"ui": opened["data"]["ui"]})
            closed = await mc.expect("ui.close")
            assert closed["data"]["ui"] == opened["data"]["ui"]
            state = await mc.expect("player.state")
            assert state["data"]["mode"] == "idle"
        finally:
            await mc.close()
            await gen1.stop()
    run(scenario())


def test_bad_message_gets_error_and_debug_is_test_only():
    async def scenario():
        gen1, mc = await _pair(role="client")
        try:
            await mc.hello()
            mc.conn.strict = False  # let the client send a message that fails its schema
            bad = await mc.send("move.step", {"dir": "north", "n": 1})
            err = await mc.expect("error", re=bad["seq"])
            assert err["data"]["code"] == "bad_message"
            mc.conn.strict = True
            err = await mc.expect("error", re=(await mc.send("debug.status"))["seq"])
            assert err["data"]["code"] == "test_only"
        finally:
            await mc.close()
            await gen1.stop()
    run(scenario())


def test_oversize_line_drops_the_connection():
    async def scenario():
        gen1, mc = await _pair()
        try:
            await mc.hello()
            await mc.expect("snapshot")
            await mc.conn.send_bytes(b"x" * (4 * 1024 * 1024 + 16) + b"\n")
            assert await mc.next(timeout=10) is None
        finally:
            await mc.close()
            await gen1.stop()
    run(scenario())


def test_client_reconnects_with_backoff():
    async def scenario():
        gen1 = await FakeGen1(port=0).start()
        port = gen1.port
        await gen1.stop()
        mc = FakeMinecraft(port=port, ping_ms=0)

        async def restart_later():
            await asyncio.sleep(0.7)
            return await FakeGen1(port=port).start()

        restarter = asyncio.create_task(restart_later())
        try:
            await mc.connect(attempts=6)
            assert (await mc.hello())["data"]["protocol"] == 1
        finally:
            await mc.close()
            await (await restarter).stop()
    run(scenario())


def test_frames_are_canonical_lines():
    line = encode_line({"type": "ping", "seq": 1, "data": {"t": 2}})
    assert line == b'{"data":{"t":2},"seq":1,"type":"ping"}\n'
