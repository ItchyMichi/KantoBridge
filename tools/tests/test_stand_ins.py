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
            await mc.expect("game.status")  # MG2: the progress follows every snapshot
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
            await first.expect("game.status")
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
            await mc.expect("game.status")
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


# --- MG2: game.status and the region acts (docs/PROTOCOL.md "Regions (MG2)") ---------------------


async def _region_pair(**gen1_kw):
    gen1 = await FakeGen1(port=0, **gen1_kw).start()
    mc = await FakeMinecraft(port=gen1.port, role="test", ping_ms=0).connect()
    await mc.hello()
    snap = (await mc.expect("snapshot"))["data"]
    status = (await mc.expect("game.status"))["data"]
    return gen1, mc, snap, status


def test_game_status_follows_the_snapshot_and_marker_changes():
    async def scenario():
        gen1, mc, snap, status = await _region_pair()
        try:
            assert snap["ingame"] is True and "away" not in snap
            assert status == {"game": "red", "playthrough": "saved", "ingame": True, "started": True, "badges": 0,
                              "markers": {"champion": False, "starter": False}, "name": "TESTER", "slot": 1,
                              "visitors": 0}
            # a resync's snapshot is followed by the status again; a bind too
            await mc.send("resync")
            await mc.expect("snapshot")
            await mc.expect("game.status")
            bind = (await mc.send("session.bind", {"world": "w-regions", "multiplayer": False}))["seq"]
            await mc.expect("session.bound", re=bind)
            assert (await mc.expect("game.status"))["data"]["slot"] == 1
            await mc.expect("save.loaded")
            # a marker flag changes: the status says so; an ordinary flag does not
            await gen1.set_flag("EVENT_GOT_STARTER", True)
            await mc.expect("story.flags")
            assert (await mc.expect("game.status"))["data"]["markers"] == {"champion": False, "starter": True}
            await gen1.set_flag("EVENT_TEST_OTHER", True)
            await mc.expect("story.flags")
            await gen1.set_badge("BOULDERBADGE", True)
            changed = await mc.expect("game.status")
            assert changed["data"]["badges"] == 1 and changed["data"]["markers"]["starter"] is True
            assert mc.counts["game.status"] == 5, "no status for the plain flag"
            assert not mc.conn.problems
        finally:
            await mc.close()
            await gen1.stop()
    run(scenario())


def test_park_holds_the_player_and_resume_releases_it():
    async def scenario():
        gen1, mc, snap, _ = await _region_pair()
        try:
            cell = snap["player"]
            parked = await mc.request("region.park", {"region": "kanto"}, reply_type="act.result")
            assert parked["data"] == {"ok": True, "kind": "park"}
            await mc.send("move.step", {"dir": "up", "n": 1})
            blocked = (await mc.expect("player.blocked"))["data"]
            assert (blocked["reason"], blocked["x"], blocked["y"]) == ("script", cell["x"], cell["y"])
            again = await mc.request("region.park", {"region": "kanto"}, reply_type="act.result")
            assert again["data"]["ok"] is True, "a park while parked is ok again"
            held = (await mc.request("resync", reply_type="snapshot"))["data"]
            assert held["away"] == {"map": cell["map"], "x": cell["x"], "y": cell["y"]}
            await mc.expect("game.status")
            # a target that is neither a route map nor the away cell's map is refused; the hold stays
            bad = await mc.request("region.resume", {"region": "kanto", "to": {"map": "TEST_NOWHERE", "x": 1, "y": 1,
                                                                                "facing": "down"}}, reply_type="act.result")
            assert bad["data"] == {"ok": False, "kind": "resume", "reason": "bad_target"}
            assert gen1.parked
            resumed = await mc.request("region.resume", {"region": "kanto", "to": cell}, reply_type="act.result")
            assert resumed["data"] == {"ok": True, "kind": "resume"}
            after = (await mc.request("resync", reply_type="snapshot"))["data"]
            assert after["player"] == cell and "away" not in after, "the cell is unchanged by a park/resume pair"
            await mc.expect("game.status")
            twice = await mc.request("region.resume", {"region": "kanto", "to": cell}, reply_type="act.result")
            assert twice["data"] == {"ok": False, "kind": "resume", "reason": "not_parked"}
            # a resume onto a route map's arrival cell is a warp of kind script
            await mc.request("region.park", {"region": "kanto"}, reply_type="act.result")
            sent = await mc.send("region.resume", {"region": "kanto",
                                                   "to": {"map": "KB_ROUTE_TEST", "x": 1, "y": 0, "facing": "right"}})
            start = await mc.expect("warp")
            arrive = await mc.expect("warp")
            assert (start["data"]["kind"], arrive["data"]["phase"]) == ("script", "arrive")
            assert arrive["data"]["to"] == {"map": "KB_ROUTE_TEST", "x": 1, "y": 0, "facing": "right"}
            assert (await mc.expect("act.result", re=sent["seq"]))["data"]["ok"] is True
            # a start while in the game
            started = await mc.request("region.start", {"region": "kanto"}, reply_type="act.result")
            assert started["data"] == {"ok": False, "kind": "start", "reason": "not_title"}
            # not idle: a text box is open
            await gen1.open_text([["Made-up line."]])
            await mc.expect("ui.open")
            busy = await mc.request("region.park", {"region": "kanto"}, reply_type="act.result")
            assert busy["data"] == {"ok": False, "kind": "park", "reason": "not_idle"}
            assert not mc.conn.problems
        finally:
            await mc.close()
            await gen1.stop()
    run(scenario())


def test_a_game_at_its_title_is_started_by_region_start():
    async def scenario():
        for slot_has_save, detail, playthrough in ((True, "continue", "saved"), (False, "new", "fresh")):
            gen1, mc, snap, status = await _region_pair(state="title", slot_has_save=slot_has_save)
            try:
                assert snap["ingame"] is False
                assert (status["playthrough"], status["started"], status["ingame"]) == ("title", False, False)
                assert "name" not in status
                await mc.send("move.step", {"dir": "up", "n": 1})
                assert (await mc.expect("player.blocked"))["data"]["reason"] == "script"
                parked = await mc.request("region.park", {"region": "kanto"}, reply_type="act.result")
                assert parked["data"] == {"ok": False, "kind": "park", "reason": "not_ingame"}
                started = await mc.request("region.start", {"region": "kanto"}, reply_type="act.result")
                assert started["data"] == {"ok": True, "kind": "start", "detail": detail}
                sync = await mc.expect("player.pos")
                assert sync["data"]["kind"] == "sync"
                loaded = (await mc.expect("save.loaded"))["data"]
                assert loaded["fresh"] is (detail == "new") and (("pair" in loaded) is (detail == "continue"))
                after = (await mc.expect("game.status"))["data"]
                assert (after["playthrough"], after["started"], after["ingame"], after["name"]) == \
                    (playthrough, True, True, "TESTER")
                again = await mc.request("region.start", {"region": "kanto"}, reply_type="act.result")
                assert again["data"]["reason"] == "not_title"
                assert (await mc.request("resync", reply_type="snapshot"))["data"]["ingame"] is True
                assert not mc.conn.problems
            finally:
                await mc.close()
                await gen1.stop()
    run(scenario())


def test_a_crossing_between_two_fakes():
    # MULTI_GAME_PLAN §4.8 / MG5 [T]: the M->G half of a crossing against two hidden games: the followed game is parked,
    # the far game is started from its title (an empty party.sync reaches it first), later the far game is parked and
    # the first one resumed onto its route map's arrival cell (a warp of kind script). The Minecraft half (the fade,
    # the teleport, region.state) is the server's and the client's, covered by the GameTests and JUnit.
    async def scenario():
        a, mc_a, snap_a, status_a = await _region_pair(game="red")
        b, mc_b, snap_b, status_b = await _region_pair(game="blue", state="title", slot_has_save=False)
        try:
            assert status_a["playthrough"] == "saved" and status_b["playthrough"] == "title"
            cell_a = snap_a["player"]
            # 1. park the followed game where it stands
            parked = await mc_a.request("region.park", {"region": "kanto"}, reply_type="act.result")
            assert parked["data"] == {"ok": True, "kind": "park"}
            assert a.parked and not b.ingame
            # 2. the far game is at its title: stash (the server's job) and start it; the empty party reaches it
            await mc_b.send("party.sync", {"party": [], "box": {"count": 0}})
            started = await mc_b.request("region.start", {"region": "test_kanto2"}, reply_type="act.result")
            assert started["data"] == {"ok": True, "kind": "start", "detail": "new"}
            assert (await mc_b.expect("player.pos"))["data"]["kind"] == "sync"
            assert (await mc_b.expect("save.loaded"))["data"]["fresh"] is True
            after_b = (await mc_b.expect("game.status"))["data"]
            assert (after_b["playthrough"], after_b["ingame"], after_b["started"]) == ("fresh", True, True)
            assert b.ingame and a.parked, "the first game waits on its route map while the second is played"
            # 3. back: park the far game, resume the first onto its route map's arrival cell
            parked_b = await mc_b.request("region.park", {"region": "test_kanto2"}, reply_type="act.result")
            assert parked_b["data"]["ok"] is True
            sent = await mc_a.send("region.resume", {"region": "kanto",
                                                     "to": {"map": "KB_ROUTE_TEST", "x": 1, "y": 0, "facing": "right"}})
            start = await mc_a.expect("warp")
            arrive = await mc_a.expect("warp")
            assert (start["data"]["kind"], arrive["data"]["phase"]) == ("script", "arrive")
            assert arrive["data"]["to"] == {"map": "KB_ROUTE_TEST", "x": 1, "y": 0, "facing": "right"}
            assert (await mc_a.expect("act.result", re=sent["seq"]))["data"]["ok"] is True
            resumed = (await mc_a.request("resync", reply_type="snapshot"))["data"]
            assert "away" not in resumed and resumed["player"]["map"] == "KB_ROUTE_TEST"
            await mc_a.expect("game.status")
            # 4. and over again: resume the far game onto its own away cell (a plain resume, no intro)
            resumed_b = await mc_b.request("region.resume", {"region": "test_kanto2", "to": b.player.as_data()},
                                           reply_type="act.result")
            assert resumed_b["data"] == {"ok": True, "kind": "resume"}
            assert not b.parked and not mc_a.conn.problems and not mc_b.conn.problems
            assert cell_a["map"] != "KB_ROUTE_TEST", "the first game's hidden player left its town for the route map"
        finally:
            await mc_a.close()
            await mc_b.close()
            await a.stop()
            await b.stop()
    run(scenario())


# ------------------------------------------------------------ foreign Pokémon (FP1)

U1 = "0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b"
U2 = "6a1d3c4e-5f60-4a7b-8c9d-0e1f2a3b4c5d"
U3 = "7b2e4d6f-8a1c-4e3b-9d5f-1a2b3c4d5e6f"


def member(uid, dex, **kw):
    m = {"uid": uid, "dex": dex, "level": 20, "moves": [33], "hp": 40, "maxhp": 40,
         "stats": {"hp": 40, "atk": 20, "def": 20, "spa": 20, "spd": 20, "spe": 20}, "types": ["normal"]}
    m.update(kw)
    return m


def test_hello_ok_says_whether_the_stand_in_registered():
    async def scenario():
        for visitor in (True, False):
            gen1 = await FakeGen1(port=0, game="blue", visitor=visitor).start()
            mc = await FakeMinecraft(port=gen1.port, role="test", ping_ms=0).connect()
            try:
                ok = (await mc.hello())["data"]
                assert ok["compat"] == {"maxDex": 151, "maxMove": 165, "nick": 10, "visitor": visitor}
                assert not mc.conn.problems
            finally:
                await mc.close()
                await gen1.stop()
    run(scenario())


def test_game_status_counts_the_visitors_of_each_sync():
    # FOREIGN_POKEMON_PLAN §4.1 / R2: dex 0, a species past the game's maxDex and (Gen 1) any form are visitors;
    # a region that leaves them out (session.bind.incompatible) shows none
    async def scenario():
        gen1, mc, snap, status = await _region_pair(game="blue")
        try:
            assert status["visitors"] == 0
            party = [member(U1, 445, nature="jolly", canLearn=[15, 57], stones=[]), member(U2, 25),
                     member(U3, 0)]
            await mc.send("party.sync", {"party": party, "box": {"count": 0}})
            assert (await mc.expect("game.status"))["data"]["visitors"] == 2
            await mc.send("party.sync", {"party": [member(U1, 37, form="alola"), member(U2, 25)], "box": {"count": 0}})
            assert (await mc.expect("game.status"))["data"]["visitors"] == 1
            bind = (await mc.send("session.bind", {"world": "w-fp", "multiplayer": False, "incompatible": "leave_out"}))["seq"]
            await mc.expect("session.bound", re=bind)
            assert (await mc.expect("game.status"))["data"]["visitors"] == 0, "the region leaves them out"
            assert gen1.incompatible == "leave_out"
            assert not mc.conn.problems
        finally:
            await mc.close()
            await gen1.stop()
    run(scenario())


def test_what_the_hidden_game_reports_is_schema_valid():
    # FP1: party.item with id and kind, party.order, party.change, party.hold, party.learn, party.forget,
    # party.take{quest}; the client stand-in checks every message it receives (strict)
    async def scenario():
        gen1, mc = await _pair()
        try:
            await mc.hello()
            item = await gen1.use_item("TM_TEST", U1, "machine", move=89, forget=33)
            await gen1.order([U2, U1])
            change = await gen1.change(U1, hp=12, status="psn")
            give = await gen1.hold(U1, "leftovers")
            take = await gen1.hold(U1, None)
            await gen1.learn(U2, 7, forget=33)
            await gen1.forget(U2, 7)
            await gen1.take(U2, "quest")
            got = {}
            for msg_type in ("party.item", "party.order", "party.change", "party.hold", "party.hold", "party.learn",
                             "party.forget", "party.take"):
                got.setdefault(msg_type, []).append((await mc.expect(msg_type))["data"])
            assert got["party.item"][0] == {"item": "TM_TEST", "uid": U1, "id": item, "kind": "machine", "move": 89,
                                            "forget": 33}
            assert got["party.order"][0] == {"uids": [U2, U1]}
            assert got["party.change"][0] == {"uid": U1, "id": change, "hp": 12, "status": "psn"}
            assert [h["item"] for h in got["party.hold"]] == ["leftovers", None]
            assert [h["id"] for h in got["party.hold"]] == [give, take] and give != take
            assert got["party.take"][0] == {"uid": U2, "reason": "quest"}
            # the answer to a refused item travels M->G now
            await mc.send("act.result", {"ok": False, "kind": "item", "reason": "move_known", "id": item}, re=1)
            await asyncio.sleep(0.1)
            assert gen1.counts().get("act.result") == 1
            assert not mc.conn.problems and not gen1.conn.problems
        finally:
            await mc.close()
            await gen1.stop()
    run(scenario())



def test_a_ride_walks_on_at_the_bikes_frames_and_refuses_steps():
    # BIKE_PLAN B1: move.ride holds a direction; the stand-in walks its player on at 8 frames a cell
    async def scenario():
        gen1, mc = await _pair()
        try:
            await mc.hello()
            gen1.state.bike, gen1.state.bike_kind = True, "mach"
            await mc.send("move.ride", {"dir": "up", "b": False})
            first = await mc.expect("player.pos")
            second = await mc.expect("player.pos")
            assert (first["data"]["kind"], first["data"]["frames"], "n" in first["data"]) == ("bike", 8, False)
            assert second["data"]["y"] == first["data"]["y"] - 1
            await mc.send("move.step", {"dir": "left", "n": 1})
            refused = await mc.expect("player.blocked")
            assert (refused["data"]["reason"], refused["data"]["n"]) == ("script", 1)
            await mc.send("move.ride", {"dir": None, "b": False})
            await asyncio.sleep(0.2)
            assert gen1.state.as_data()["bikeKind"] == "mach" and gen1.rides == 2
        finally:
            await mc.close()
            await gen1.stop()
    run(scenario())


def test_route_state_and_route_leave():
    """WORLD_SESSIONS_PLAN WS1: on a route map the stand-in reports route{zone, map} in player.state and the snapshot;
    route.leave takes it home (act.result, then a warp of kind script); off the route map it's refused."""
    async def scenario():
        gen1, mc = await _pair()
        try:
            await mc.hello()
            await mc.expect("snapshot")
            refused = await mc.leave_route("test_zone")
            assert refused["data"] == {"ok": False, "kind": "route.leave", "reason": "not_on_route"}
            gen1.route_back["test_zone"] = ("TEST_TOWN", 4, 4, "down")
            await gen1.warp_to("KB_ROUTE_TEST_ZONE", 1, 0, "right", kind="script")
            state = await mc.expect("player.state")
            assert state["data"]["route"] == {"zone": "test_zone", "map": "KB_ROUTE_TEST_ZONE"}
            snap = (await mc.request("resync", reply_type="snapshot"))["data"]
            assert snap["route"] == {"zone": "test_zone", "map": "KB_ROUTE_TEST_ZONE"}
            other = await mc.leave_route("other_zone")
            assert other["data"]["reason"] == "not_on_route"
            ok = await mc.leave_route("test_zone")
            assert ok["data"] == {"ok": True, "kind": "route.leave"}
            arrive = await mc.expect("warp")
            arrive = arrive if arrive["data"]["phase"] == "arrive" else await mc.expect("warp")
            assert arrive["data"]["kind"] == "script" and arrive["data"]["to"]["map"] == "TEST_TOWN"
            assert "route" not in (await mc.request("resync", reply_type="snapshot"))["data"]
            assert not mc.conn.problems
        finally:
            await mc.close()
            await gen1.stop()
    run(scenario())
