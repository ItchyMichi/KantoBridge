"""fake_minecraft against fake_gen1: the connection rules of docs/PROTOCOL.md."""
import asyncio
import hashlib
import json

import pytest

from fake_gen1 import FakeGen1
from fake_minecraft import FakeMinecraft, HelloRejected
from kb_protocol import encode_line
from kb_protocol.jsonfmt import canonical


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


def test_hello_ok_names_the_catalogue_family_and_compat():
    # MG1: hello.ok carries the game's family and the adapter's limits; --game takes any catalogued id
    async def scenario():
        for game, family, max_dex in (("red", "gen1", 151), ("yellow", "gen1", 151), ("emerald", "gen3", 386)):
            gen1 = await FakeGen1(port=0, game=game).start()
            mc = await FakeMinecraft(port=gen1.port, role="test", ping_ms=0).connect()
            try:
                ok = (await mc.hello())["data"]
                assert (ok["game"], ok["family"], ok["compat"]["maxDex"]) == (game, family, max_dex), game
                await mc.expect("snapshot")
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


def test_unknown_role_is_rejected_as_role():
    async def scenario():
        gen1, mc = await _pair()
        try:
            # A well-formed but unknown role: "role", as gen1-bridge answers (M10). Sent as bytes,
            # because fake_minecraft won't send a schema-invalid hello.
            await mc.conn.send_bytes(encode_line({"type": "hello", "seq": 1,
                                                  "data": {"protocol": 1, "role": "spectator", "mod": "test"}}))
            reject = await mc.expect("hello.reject")
            assert (reject["data"]["reason"], reject["re"]) == ("role", 1)
            assert await mc.next() is None
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
            assert arrive["data"]["to"] == {"map": "TEST_HOUSE", "x": 4, "y": 8, "facing": "up"}
            npcs = await mc.expect("npc.set")
            assert npcs["data"]["map"] == "TEST_HOUSE"
            for n, direction in enumerate(("up", "left", "left"), start=4):
                await mc.send("move.step", {"dir": direction, "n": n})
                await mc.expect("player.pos")
            await mc.send("move.step", {"dir": "left", "n": 7})  # into the house wall
            blocked = await mc.expect("player.blocked")
            assert (blocked["data"]["reason"], blocked["data"]["n"], blocked["data"]["x"]) == ("tile", 7, 2)
            for n, direction in enumerate(("right", "right", "down"), start=8):
                await mc.send("move.step", {"dir": direction, "n": n})
                await mc.expect("player.pos")  # back onto the exit mat at (4, 8): no warp on arrival
            await mc.send("move.step", {"dir": "down", "n": 11})  # off the edge from the mat
            leave = await mc.expect("warp")
            back = await mc.expect("warp")
            assert (leave["data"]["phase"], leave["data"]["kind"]) == ("start", "edge")
            assert back["data"]["to"] == {"map": "TEST_TOWN", "x": 5, "y": 5, "facing": "down"}
            answer = await mc.expect("player.pos")
            assert (answer["data"]["kind"], answer["data"]["n"]) == ("sync", 11), "the step's one answer"
            walk_out = await mc.expect("player.pos")
            assert (walk_out["data"]["kind"], walk_out["data"]["y"], "n" in walk_out["data"]) == ("script", 6, False)
        finally:
            await mc.close()
            await gen1.stop()
    run(scenario())


def test_stairs_warp_between_floors():
    async def scenario():
        gen1, mc = await _pair()
        try:
            await mc.hello()
            await gen1.teleport("TEST_HOUSE", 4, 5, "right")
            for n, direction in enumerate(("right", "right", "right", "up", "up", "up"), start=1):
                await mc.send("move.step", {"dir": direction, "n": n})
            arrive = None
            while arrive is None:
                message = await mc.next()
                if message["type"] == "warp" and message["data"]["phase"] == "arrive":
                    arrive = message
            assert arrive["data"]["kind"] == "stairs"
            assert arrive["data"]["to"] == {"map": "TEST_HOUSE_2F", "x": 5, "y": 2, "facing": "down"}
        finally:
            await mc.close()
            await gen1.stop()
    run(scenario())


def test_a_cave_ladder_is_taken_where_the_player_climbed_to():
    async def scenario():
        gen1, mc = await _pair()
        try:
            await mc.hello()
            await gen1.teleport("TEST_CAVE_1F", 8, 3, "right")
            take = {"from": {"map": "TEST_CAVE_1F", "x": 9, "y": 3},
                    "to": {"map": "TEST_CAVE_B1F", "x": 9, "y": 3, "facing": "down"}, "kind": "ladder"}
            far = await mc.request("warp.take", dict(take, **{"from": {"map": "TEST_CAVE_1F", "x": 3, "y": 5}}),
                                   reply_type="warp.taken")
            assert far["data"] == {"ok": False, "reason": "far"}
            taken = await mc.request("warp.take", take, reply_type="warp.taken")
            assert taken["data"] == {"ok": True}
            start = await mc.expect("warp")
            assert start["re"] == taken["re"] and start["data"] == {"phase": "start", "kind": "stairs"}
            arrive = await mc.expect("warp")
            assert arrive["re"] == taken["re"] and arrive["data"]["to"] == take["to"]
        finally:
            await mc.close()
            await gen1.stop()
    run(scenario())


def test_made_up_gift_and_battle_are_schema_valid():
    async def scenario():
        gen1, mc = await _pair()
        try:
            await mc.hello()
            await gen1.give(1, 5, "starter")
            gift = await mc.expect("party.give")
            assert (gift["data"]["dex"], gift["data"]["source"]) == (1, "starter")
            for kind in ("trainer", "surf"):
                await gen1.start_battle(kind)
                start = await mc.expect("battle.start")
                assert start["data"]["kind"] == kind
            await mc.send("battle.result", {"battle": start["data"]["battle"], "outcome": "fled"})
            await asyncio.sleep(0.1)
            assert gen1.counts().get("battle.result") == 1
            assert not mc.conn.problems
        finally:
            await mc.close()
            await gen1.stop()
    run(scenario())


def test_bind_answers_with_switched_and_save_loaded():
    """M10: session.bound says how the slot was reached, then save.loaded says which save runs."""
    async def scenario():
        gen1, mc = await _pair()
        try:
            ok = await mc.hello()
            assert ok["data"]["tested"] == ok["data"]["engine"]
            bind = (await mc.send("session.bind", {"world": "w-test", "multiplayer": False}))["seq"]
            bound = await mc.expect("session.bound", re=bind)
            assert bound["data"]["switched"] == "none"
            loaded = (await mc.expect("save.loaded"))["data"]
            assert (loaded["fresh"], loaded["ops"], loaded["pair"]) == (False, 0, gen1.pair)

            await gen1.give(1, 5, "gift")
            await mc.expect("party.give")
            await gen1.save("center")
            save = (await mc.expect("save.begin"))["data"]
            assert save["pair"] == gen1.pair and save["reason"] == "center"
            await gen1.give(4, 5, "gift")
            await mc.expect("party.give")
            await gen1.reload_save()
            loaded = (await mc.expect("save.loaded"))["data"]
            assert (loaded["pair"], loaded["ops"]) == (save["pair"], 0), "back to the save: no ops since"

            gen1.bind_wait = ("busy", 0.2)
            bind = (await mc.send("session.bind", {"world": "w-other", "multiplayer": True}))["seq"]
            waiting = await mc.expect("session.waiting", re=bind)
            assert waiting["data"]["reason"] == "busy"
            bound = await mc.expect("session.bound", re=bind)
            assert bound["data"]["switched"] == "title"

            await gen1.open_pc()
            pc = (await mc.expect("pc.open"))["data"]["pc"]
            await mc.send("pc.closed", {"pc": "pc-stale"})
            await asyncio.sleep(0.1)
            assert gen1.pc_open == pc, "a stale pc.closed doesn't end the hold"
            await mc.send("pc.closed", {"pc": pc})
            await asyncio.sleep(0.1)
            assert gen1.pc_open is None
            assert not mc.conn.problems
        finally:
            await mc.close()
            await gen1.stop()
    run(scenario())


def test_two_pairs_run_side_by_side():
    """Multiplayer on one PC (M9): two bridge pairs on different ports stay independent."""
    async def scenario():
        first = await FakeGen1(port=0).start()
        second = await FakeGen1(port=0, game="blue").start()
        clients = []
        try:
            assert first.port != second.port
            for gen1 in (first, second):
                client = await FakeMinecraft(port=gen1.port, ping_ms=0).connect()
                clients.append(client)
                await client.hello()
            await clients[0].send("move.step", {"dir": "up", "n": 1})
            moved = await clients[0].expect("player.pos")
            assert moved["data"]["y"] == 7
            assert (await clients[1].request("debug.status"))["data"]["connections"] == 1
            assert (first.player.y, second.player.y) == (7, 8), "a step on one pair never moves the other"
            assert (clients[0].hello_ok["data"]["game"], clients[1].hello_ok["data"]["game"]) == ("red", "blue")
            assert first.session != second.session
        finally:
            for client in clients:
                await client.close()
            await first.stop()
            await second.stop()
    run(scenario())


def test_export_index_and_resources():
    async def scenario():
        gen1, mc = await _pair(role="builder")
        try:
            await mc.hello()
            index = (await mc.request("export.index", {}))["data"]
            assert index["game"] == "red" and len(index["resources"]) >= 10
            for entry in index["resources"]:
                reply = await mc.request("export.get", {"kind": entry["kind"], "id": entry["id"]},
                                         reply_type="export.data")
                data = reply["data"]
                assert (data["kind"], data["id"], data["hash"]) == (entry["kind"], entry["id"], entry["hash"])
                assert not mc.protocol.check_export(entry["kind"], data["data"])
                raw = canonical(data["data"]).encode("utf-8")
                assert len(raw) == entry["size"] and hashlib.sha256(raw).hexdigest() == entry["hash"]
            missing = await mc.send("export.get", {"kind": "map", "id": "NO_SUCH_MAP"})
            assert (await mc.expect("error", re=missing["seq"]))["data"]["code"] == "not_found"
        finally:
            await mc.close()
            await gen1.stop()
    run(scenario())


def test_export_needs_builder_role():
    async def scenario():
        gen1, mc = await _pair(role="client")
        try:
            await mc.hello()
            sent = await mc.send("export.index", {})
            assert (await mc.expect("error", re=sent["seq"]))["data"]["code"] == "role"
        finally:
            await mc.close()
            await gen1.stop()
    run(scenario())


def test_write_bundle(tmp_path):
    from fake_gen1.bundle import write_bundle
    root = write_bundle(tmp_path, game="blue")
    index = json.loads((root / "index.json").read_text(encoding="utf-8"))
    assert root.name == "blue" and index["game"] == "blue"
    for entry in index["resources"]:
        raw = (root / entry["kind"] / f"{entry['id']}.json").read_bytes()
        assert hashlib.sha256(raw).hexdigest() == entry["hash"] and len(raw) == entry["size"]


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
