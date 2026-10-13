"""gen1-bridge's session (lib/kb_session.lua wired by main.lua) on the fake
engine and fake socket: the rules of docs/PROTOCOL.md "Transport" and
"Handshake". Every message received is schema-checked (rig.Peer)."""

import json

import pytest

from rig import Rig

pytestmark = pytest.mark.lua


def established(role="test"):
    rig = Rig()
    rig.world()
    peer = rig.connect()
    seq = peer.hello(role=role)
    msgs = peer.messages()
    # the NPC observer follows every snapshot with the current map's npc.set (M4) and the
    # bag observer with the mirrored battle items (M6), live streams for the client and test
    # roles only
    follow = ["npc.set", "bag.sync"] if role in ("client", "test") else []
    assert [m["type"] for m in msgs] == ["hello.ok", "snapshot"] + follow
    assert msgs[0]["re"] == seq
    return rig, peer, msgs[:2]


def test_listener_binds_lazily_on_the_first_step():
    rig = Rig()
    assert not rig.listening()
    rig.step()
    assert rig.listening() and rig.status.bound is True
    assert rig.b.net.reuseaddrSet == 0 and rig.b.net.bindHelperUsed == 0


def test_hello_ok_then_snapshot():
    rig, peer, msgs = established()
    ok, snap = msgs
    assert ok["data"]["protocol"] == 1 and ok["data"]["bridge"] == "0.1.0"
    assert ok["data"]["engine"] == "0.3.85" and ok["data"]["game"] == "blue"
    assert ok["data"]["session"] == rig.status.session.decode()
    assert (ok["seq"], snap["seq"]) == (1, 2) and "re" not in snap
    data = snap["data"]
    assert data["player"] == {"map": "TEST_TOWN", "x": 5, "y": 6, "facing": "down"}
    assert data["state"]["mode"] == "idle"
    assert data["flags"] == ["EVENT_TEST_ONE"]
    assert data["bag"] == {"POTION": 2} and data["badges"] == ["BOULDERBADGE"]
    assert data["toggles"] == {} and data["blocks"] == [] and data["lit"] == []
    assert data["npcs"] == {"map": "TEST_TOWN", "list": []}
    assert rig.status.connected is True and rig.status.role == b"test"
    assert rig.status.sessions == 1


def test_first_message_must_be_hello():
    rig = Rig()
    rig.step()
    peer = rig.connect()
    seq = peer.send("ping", {"t": 1})
    rig.step()
    msgs = peer.messages()
    assert [(m["type"], m["data"]["code"], m["re"]) for m in msgs] == [("error", "no_hello", seq)]
    assert peer.closed() and rig.status.connected is False


@pytest.mark.parametrize("hello, reason", [
    ({"protocol": 2, "role": "client", "mod": "x"}, "protocol"),
    ({"protocol": 1, "role": "spectator", "mod": "x"}, "role"),
    ({"protocol": "1", "role": "client", "mod": "x"}, "malformed"),
    ({"protocol": 1, "role": "client"}, "malformed"),
])
def test_bad_hello_is_rejected_and_closed(hello, reason):
    rig = Rig()
    rig.step()
    peer = rig.connect()
    seq = peer.send("hello", hello)
    rig.step()
    msgs = peer.messages()
    assert len(msgs) == 1 and msgs[0]["type"] == "hello.reject" and msgs[0]["re"] == seq
    assert msgs[0]["data"] == {"reason": reason, "protocol": 1}
    assert peer.closed()


def test_reject_is_flushed_before_the_close():
    rig = Rig()
    rig.step()
    peer = rig.connect()
    from rig import call
    call(peer.handle, "setWindow", 10)  # the reply leaves 10 bytes at a time
    peer.send("hello", {"protocol": 9, "role": "client", "mod": "x"})
    received = b""
    for _ in range(30):
        rig.step()
        received += call(peer.handle, "take")
        call(peer.handle, "setWindow", 10)
        if peer.closed():
            break
    assert peer.closed()
    assert json.loads(received.decode())["type"] == "hello.reject"


def test_ping_pong_and_resync():
    rig, peer, _ = established()
    seq = peer.send("ping", {"t": 123456})
    rig.step()
    pong = peer.messages()
    assert [(m["type"], m["re"], m["data"]) for m in pong] == [("pong", seq, {"t": 123456})]
    seq = peer.send("resync")
    rig.step()
    snap = peer.messages()
    assert [(m["type"], m.get("re")) for m in snap] == [("snapshot", seq), ("npc.set", None), ("bag.sync", None)]


def test_bad_ping_gets_bad_message():
    rig, peer, _ = established()
    seq = peer.send("ping", {"t": -1})
    rig.step()
    assert [(m["type"], m["data"]["code"], m["re"]) for m in peer.messages()] == [("error", "bad_message", seq)]


def test_silence_for_ten_seconds_drops_the_client():
    rig, peer, _ = established()
    for _ in range(5):  # pings every 2 s keep it alive
        rig.advance(2.0)
        peer.send("ping", {"t": 1})
        rig.step()
    assert not peer.closed()
    rig.advance(10.5)
    rig.step()
    assert peer.closed() and rig.status.connected is False
    assert any("session.timeout" in line for line in rig.logs())


def test_a_client_that_never_says_hello_times_out_too():
    rig = Rig()
    rig.step()
    peer = rig.connect()
    rig.advance(11)
    rig.step()
    assert peer.closed()


def test_debug_messages_are_test_only():
    rig, peer, _ = established(role="client")
    seq = peer.send("debug.status")
    rig.step()
    assert [(m["type"], m["data"]["code"], m["re"]) for m in peer.messages()] == [("error", "test_only", seq)]
    peer.send("debug.quit")
    rig.step()
    assert rig.status.quitRequested is False


def test_debug_status_and_quit():
    rig, peer, _ = established()
    seq = peer.send("debug.status")
    rig.step()
    msgs = peer.messages()
    assert [m["type"] for m in msgs] == ["debug.status"] and msgs[0]["re"] == seq
    data = msgs[0]["data"]
    assert data["topState"] == "Overworld" and data["mode"] == "idle"
    assert (data["npcs"], data["flags"], data["ingame"], data["port"]) == (0, 1, True, 27151)
    assert data["optionsPinned"] is True
    peer.send("debug.quit", {"code": 0})
    rig.step()
    assert rig.status.quitRequested is True and rig.status.quitCode == 0


def test_a_new_connection_replaces_the_old():
    rig, first, _ = established()
    second = rig.connect()
    assert first.closed() and not second.closed()
    second.hello()
    assert [m["type"] for m in second.messages()] == ["hello.ok", "snapshot", "npc.set", "bag.sync"]
    assert rig.status.sessions == 2


def test_hello_twice_is_an_error():
    rig, peer, _ = established()
    seq = peer.hello()
    assert [(m["type"], m["data"]["code"], m["re"]) for m in peer.messages()] == [("error", "already_hello", seq)]


def test_dispatch_unknown_wrong_leg_and_unhandled():
    rig, peer, _ = established()
    s1 = peer.send("no.such.type")
    s2 = peer.send("player.pos", {"map": "TEST_TOWN", "x": 1, "y": 1, "facing": "up", "kind": "walk", "frames": 16})
    peer.send("move.step", {"dir": "up", "n": 1})  # ours to receive, handled from M3
    rig.step()
    errors = [(m["data"]["code"], m["re"]) for m in peer.messages() if m["type"] == "error"]
    assert errors == [("unknown_type", s1), ("wrong_leg", s2)]


def test_player_updates_stream_after_the_snapshot():
    rig, peer, _ = established()
    rig.E.startStep(b"up", 16)
    rig.step()
    msgs = peer.messages()
    assert [(m["type"], m["data"]) for m in msgs] == [
        ("player.pos", {"map": "TEST_TOWN", "x": 5, "y": 5, "facing": "up", "kind": "walk", "frames": 16}),
    ]
    rig.step(5)
    rig.E.land()
    rig.step()
    assert peer.messages() == []
    rig.E.push(rig.E.text())
    rig.step(3)
    msgs = peer.messages()
    assert [(m["type"], m["data"].get("mode", m["data"].get("kind"))) for m in msgs] == [
        ("player.state", "screen"), ("ui.open", "text")]
    rig.E.pop()
    rig.step()
    assert [(m["type"], m["data"].get("mode")) for m in peer.messages()] == [
        ("player.state", "idle"), ("ui.close", None)]


def test_builder_role_gets_no_live_stream():
    rig, peer, _ = established(role="builder")
    rig.E.startStep(b"up", 16)
    rig.step()
    assert peer.messages() == []


def test_warnings_are_forwarded_as_log_messages_with_a_rate_limit():
    rig, peer, _ = established()
    for _ in range(12):  # each bad line logs proto.bad_envelope at warn
        peer.send_raw(b'{"type":"x","seq":0,"data":{}}\n')
    rig.step()
    msgs = peer.messages()
    logs = [m for m in msgs if m["type"] == "log"]
    assert len([m for m in msgs if m["type"] == "error"]) == 12
    assert 1 <= len(logs) <= 5
    assert all(m["data"]["level"] == "warn" and m["data"]["code"] == "proto.bad_envelope" for m in logs)
    rig.advance(2.0)
    peer.send_raw(b'{"type":"x","seq":0,"data":{}}\n')
    rig.step()
    assert any(m["type"] == "log" for m in peer.messages())


def test_logs_never_carry_message_text():
    rig, peer, _ = established()
    peer.send_raw('{"type":"ui.text","seq":1,"data":{"text":"SECRET WORDS"},"x":1}\n'.encode())
    peer.send_raw(b'{"SECRET WORDS"\n')
    rig.step()
    for m in peer.messages():
        assert "SECRET" not in json.dumps(m)
    assert all("SECRET" not in line for line in rig.logs())


def test_configure_moves_the_port_before_and_after_binding():
    rig = Rig()
    rig.status.configure(rig.b.to_lua({"port": 27199}))
    rig.step()
    assert rig.listening(27199) and not rig.listening(27151)
    peer = rig.connect(port=27199)
    peer.hello()
    assert peer.types() == ["hello.ok", "snapshot"]
    rig.status.configure(rig.b.to_lua({"port": 27198}))
    rig.step()
    assert peer.closed() and rig.listening(27198) and not rig.listening(27199)
    assert rig.status.port == 27198


def test_port_comes_from_the_mod_option():
    rig = Rig(mod_options={"port": 27160})
    rig.step()
    assert rig.listening(27160) and not rig.listening(27151)
    schema = rig.b.mod._optionSchema
    assert schema[1].key == b"port" and schema[1].default == 27151


def test_a_failing_step_never_breaks_the_hook():
    rig = Rig()
    rig.world()
    rig.E.ow.player = rig.b.to_lua({"cellX": "broken"})  # a field of the wrong type
    rig.E.ow.player.moving = True
    rig.E.ow.player.targetX = "oops"
    called = []
    vanilla = lambda *args: called.append(1)
    for _ in range(3):
        rig.b.FakeMod.call(rig.b.mod, b"input.step", vanilla, rig.E.game, 1 / 60)
    assert called == [1, 1, 1]


def test_oversize_line_gets_an_error_then_the_close():
    # kb_session built directly, with a small line limit in a copy of the catalog
    from kblua import Bridge
    from rig import Peer, Rig
    rig = Rig.__new__(Rig)
    b = rig.b = Bridge()
    json_mod = b.module("lib/kb_json.lua")
    catalog = b.module("lib/kb_catalog.lua")
    catalog.maxLineBytes = 200
    o = b.table()
    for key, value in {
        "json": json_mod, "Net": b.module("lib/kb_net.lua"), "Proto": b.module("lib/kb_proto.lua"),
        "catalog": catalog, "socket": b.fake.socket, "port": 27151, "clock": lambda: b.now,
        "bridge": b"0.1.0", "engine": lambda: b"0.3.85", "game": lambda: b"red", "sessionId": b"s-test",
        "snapshot": lambda: b.eval("{}"), "status": lambda: b.eval("{}"), "onQuit": lambda code: None,
    }.items():
        o[key.encode()] = value
    session = b.module("lib/kb_session.lua").new(o)
    session.step()
    fake = b.net
    peer = Peer(rig, fake.connect(fake, b"127.0.0.1", 27151))
    session.step()
    peer.send_raw(b"x" * 300 + b"\n")
    session.step()
    assert [(m["type"], m["data"]["code"]) for m in peer.messages(check=False)] == [("error", "line_too_long")]
    assert peer.closed()


def test_a_reloaded_instance_closes_the_old_ones_sockets_first():
    rig, peer, msgs = established()
    old_session = msgs[0]["data"]["session"]
    rig.b.reload()  # F5: the old instance's hooks stop; game.ready reaches the new one
    assert peer.closed(), "the old connection is closed at once, not at some later GC"
    assert not rig.listening(), "the old listener is gone before the new instance binds"
    assert "[info] [gen1-bridge] bridge.replaced_instance" in rig.logs()
    fresh = rig.connect()
    fresh.hello()
    ok, snap = fresh.messages()[:2]
    assert ok["data"]["session"] != old_session and snap["type"] == "snapshot"
    assert rig.b.net.reuseaddrSet == 0


def test_the_first_instance_finds_no_one_to_replace():
    rig = Rig()
    assert not any("bridge.replaced_instance" in line for line in rig.logs())


# ------------------------------------------------------------ M9: two instances on one PC


def test_a_busy_port_falls_back_and_says_so():
    rig = Rig()
    fake = rig.b.net
    fake.occupy(fake, b"127.0.0.1", 27151)  # another Gen1Recomp instance keeps it
    rig.step()
    assert not rig.listening(27151) and not rig.listening(27152)
    assert rig.status.port == 27151 and rig.status.portFallback is False
    rig.advance(3)
    rig.step()
    assert rig.listening(27152)
    assert (rig.status.port, rig.status.portConfigured, rig.status.portFallback) == (27152, 27151, True)
    assert "[info] [gen1-bridge] net.listening configured=27151 fallback=true host=127.0.0.1 port=27152" \
        in rig.logs()
    rig.world()
    peer = rig.connect(port=27152)
    peer.hello()
    peer.messages()
    seq = peer.send("debug.status")
    rig.step()
    reply = [m for m in peer.messages() if m["type"] == "debug.status" and m.get("re") == seq][0]["data"]
    assert (reply["port"], reply["portConfigured"], reply["portFallback"]) == (27152, 27151, True)
    assert fake.reuseaddrSet == 0


def test_a_driver_port_falls_back_the_same_way():
    rig = Rig()
    fake = rig.b.net
    fake.occupy(fake, b"127.0.0.1", 27160)
    fake.occupy(fake, b"127.0.0.1", 27161)
    rig.status.configure(rig.b.to_lua({"port": 27160}))
    rig.step()
    rig.advance(3)
    rig.step()
    assert rig.listening(27162)
    assert (rig.status.port, rig.status.portConfigured, rig.status.portFallback) == (27162, 27160, True)


def test_the_port_option_applies_at_once_from_the_mod_manager():
    rig, peer, _ = established()
    payload = rig.b.to_lua({"mod": "gen1-bridge", "key": "port", "value": 27165})
    rig.b.emit("mod.options_changed", payload)
    rig.step()
    assert peer.closed() and rig.listening(27165) and not rig.listening(27151)
    assert rig.status.port == 27165 and rig.status.portConfigured == 27165
    assert "[info] [gen1-bridge] net.port_option port=27165" in rig.logs()
    # another mod's option, another key, a bad value: nothing moves
    rig.b.emit("mod.options_changed", rig.b.to_lua({"mod": "other-mod", "key": "port", "value": 27166}))
    rig.b.emit("mod.options_changed", rig.b.to_lua({"mod": "gen1-bridge", "key": "colour", "value": 27167}))
    rig.b.emit("mod.options_changed", rig.b.to_lua({"mod": "gen1-bridge", "key": "port", "value": 80}))
    rig.step()
    assert rig.listening(27165) and rig.status.portConfigured == 27165
    assert "[warn] [gen1-bridge] net.port_option_invalid" in rig.logs()


# ------------------------------------------------------------ M10: handshake hardening

UUID = "0123abcd-4567-89ab-cdef-0123456789ab"


def test_hello_ok_says_which_engine_this_bridge_is_tested_with():
    rig, peer, msgs = established()
    ok = msgs[0]["data"]
    assert ok["tested"] == "0.3.85" == ok["engine"]
    rig.step(2)
    assert not [m for m in peer.messages() if m["type"] == "log"]
    assert not any("engine.untested" in line for line in rig.logs())


def test_an_untested_engine_gets_one_warning_per_connection():
    rig = Rig()
    rig.E.Version.engine = b"0.3.86"
    rig.world()
    for n in (1, 2):
        peer = rig.connect()
        peer.hello()
        msgs = peer.messages()
        ok = msgs[0]["data"]
        assert (ok["engine"], ok["tested"]) == ("0.3.86", "0.3.85")
        warns = [m["data"] for m in msgs if m["type"] == "log" and m["data"]["code"] == "engine.untested"]
        assert len(warns) == 1 and warns[0]["level"] == "warn"
        assert "engine=0.3.86" in warns[0]["msg"] and "tested=0.3.85" in warns[0]["msg"]
        kinds = [m["type"] for m in msgs]
        assert kinds.index("snapshot") < kinds.index("log"), "nothing between hello.ok and the snapshot"
        rig.step(3)
        assert not [m for m in peer.messages() if m["type"] == "log" and m["data"]["code"] == "engine.untested"]
    assert sum("engine.untested" in line for line in rig.logs()) == 2


def test_a_connection_without_hello_is_closed_after_ten_seconds():
    rig = Rig()
    rig.step()
    peer = rig.connect()
    rig.advance(4)
    rig.step()
    peer.send_raw(b'{"type":"hel')  # a line that never ends keeps nothing alive
    rig.advance(5)
    rig.step()
    assert not peer.closed()
    rig.advance(1.5)
    rig.step()
    assert [(m["type"], m["data"]["code"], m["data"]["msg"]) for m in peer.messages()] == \
        [("error", "no_hello", "hello_timeout")]
    assert peer.closed() and rig.status.connected is False
    assert "[info] [gen1-bridge] session.hello_timeout seconds=10" in rig.logs()
    # the next connection is served as usual, and the count is in debug.status
    fresh = rig.connect()
    fresh.hello()
    fresh.messages()
    seq = fresh.send("debug.status")
    rig.step()
    st = [m for m in fresh.messages() if m["type"] == "debug.status" and m.get("re") == seq][0]["data"]
    assert st["sessionHelloTimeouts"] == 1


def test_a_first_line_that_isnt_an_envelope_gets_bad_envelope_then_the_close():
    rig = Rig()
    rig.step()
    peer = rig.connect()
    peer.send_raw(b'{"not": "an envelope"}\n')
    rig.step()
    assert [(m["type"], m["data"]["code"]) for m in peer.messages()] == [("error", "bad_envelope")]
    assert peer.closed()


def test_a_bad_line_after_hello_keeps_the_connection():
    rig, peer, _ = established()
    peer.send_raw(b'{"not": "an envelope"}\n')
    rig.step()
    assert [m["data"]["code"] for m in peer.messages() if m["type"] == "error"] == ["bad_envelope"]
    assert not peer.closed()


@pytest.mark.parametrize("hello, reason", [
    # another protocol is "protocol" whatever else it carries
    ({"protocol": 2}, "protocol"),
    ({"protocol": 7, "role": 5, "colour": True}, "protocol"),
    ({"protocol": 1000, "role": "client", "mod": "x"}, "protocol"),
    # a role string that isn't one of ours
    ({"protocol": 1, "role": "CLIENT", "mod": "x"}, "role"),
    ({"protocol": 1, "role": "", "mod": "x"}, "role"),
    # malformed fields
    ({"protocol": 0, "role": "client", "mod": "x"}, "malformed"),
    ({"protocol": 1.5, "role": "client", "mod": "x"}, "malformed"),
    ({"protocol": 1001, "role": "client", "mod": "x"}, "malformed"),
    ({"role": "client", "mod": "x"}, "malformed"),
    ({"protocol": 1, "mod": "x"}, "malformed"),
    ({"protocol": 1, "role": 3, "mod": "x"}, "malformed"),
    ({"protocol": 1, "role": "client", "mod": ""}, "malformed"),
    ({"protocol": 1, "role": "client", "mod": "has space"}, "malformed"),
    ({"protocol": 1, "role": "client", "mod": "x" * 33}, "malformed"),
    ({"protocol": 1, "role": "client", "mod": "x", "minecraft": "1.21 1"}, "malformed"),
    ({"protocol": 1, "role": "client", "mod": "x", "minecraft": 121}, "malformed"),
    ({"protocol": 1, "role": "client", "mod": "x", "player": {"uuid": "nope", "name": "Steve"}}, "malformed"),
    ({"protocol": 1, "role": "client", "mod": "x", "player": {"uuid": UUID, "name": "bad name"}}, "malformed"),
    ({"protocol": 1, "role": "client", "mod": "x", "player": {"uuid": UUID, "name": "x" * 17}}, "malformed"),
    ({"protocol": 1, "role": "client", "mod": "x", "player": {"uuid": UUID}}, "malformed"),
    ({"protocol": 1, "role": "client", "mod": "x", "player": {"uuid": UUID, "name": "Steve", "op": True}},
     "malformed"),
    ({"protocol": 1, "role": "client", "mod": "x", "world": {"id": "w-1", "multiplayer": "yes"}}, "malformed"),
    ({"protocol": 1, "role": "client", "mod": "x", "world": {"id": "has space", "multiplayer": True}}, "malformed"),
    ({"protocol": 1, "role": "client", "mod": "x", "world": {"multiplayer": True}}, "malformed"),
    ({"protocol": 1, "role": "client", "mod": "x", "world": []}, "malformed"),
    ({"protocol": 1, "role": "client", "mod": "x", "colour": "blue"}, "malformed"),
    # a malformed field wins over the role
    ({"protocol": 1, "role": "spectator", "mod": "has space"}, "malformed"),
])
def test_every_bad_hello_is_rejected_with_its_reason_then_closed(hello, reason):
    rig = Rig()
    rig.step()
    peer = rig.connect()
    seq = peer.send("hello", hello)
    rig.step()
    msgs = peer.messages()
    assert [(m["type"], m["re"], m["data"]) for m in msgs] == [("hello.reject", seq, {"reason": reason, "protocol": 1})]
    assert peer.closed()
    assert f"[info] [gen1-bridge] session.rejected reason={reason}" in rig.logs()


def test_a_hello_with_every_optional_field_is_accepted():
    rig = Rig()
    rig.world()
    peer = rig.connect()
    seq = peer.send("hello", {"protocol": 1, "role": "client", "mod": "kantobridge-0.1.0+local", "minecraft": "1.21.1",
                              "player": {"uuid": UUID, "name": "Steve_1"},
                              "world": {"id": "w-1:kanto", "multiplayer": True}})
    rig.step()
    msgs = peer.messages()
    assert msgs[0]["type"] == "hello.ok" and msgs[0]["re"] == seq and msgs[1]["type"] == "snapshot"


def test_rejections_are_counted_by_reason():
    rig = Rig()
    rig.world()
    for hello in ({"protocol": 2}, {"protocol": 1, "role": "who", "mod": "x"}, {"protocol": 1, "role": "test"}):
        peer = rig.connect()
        peer.send("hello", hello)
        rig.step()
        assert peer.closed()
    peer = rig.connect()
    peer.hello()
    peer.messages()
    seq = peer.send("debug.status")
    rig.step()
    st = [m for m in peer.messages() if m["type"] == "debug.status" and m.get("re") == seq][0]["data"]
    assert (st["sessionRejected"], st["sessionRejectedProtocol"], st["sessionRejectedRole"],
            st["sessionRejectedMalformed"]) == (3, 1, 1, 1)
