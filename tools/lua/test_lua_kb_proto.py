"""kb_proto: envelope building, checking and dispatch."""

import json

import pytest

from kblua import Bridge

pytestmark = pytest.mark.lua


class P:
    def __init__(self):
        self.lua = lua = Bridge()
        self.json = lua.module("lib/kb_json.lua")
        self.Proto = lua.module("lib/kb_proto.lua")
        Log = lua.module("lib/kb_log.lua")
        sha = lua.module("lib/kb_sha256.lua")
        self.logs, self.out = [], []
        lo = lua.table()
        lo[b"write"] = lambda level, line: self.logs.append(line.decode())
        lo[b"hash"] = sha.short
        o = lua.table()
        o[b"json"] = self.json
        o[b"write"] = lambda line: self.out.append(line)
        o[b"log"] = Log.new(lo)
        self.proto = self.Proto.new(o)

    def sent(self):
        msgs = [json.loads(line) for line in self.out]
        self.out.clear()
        return msgs

    def receive(self, obj_or_bytes):
        line = obj_or_bytes if isinstance(obj_or_bytes, bytes) else json.dumps(obj_or_bytes).encode()
        return self.proto.receive(line)

    def obj(self, **kw):
        t = self.lua.table()
        for k, v in kw.items():
            t[k.encode()] = v.encode() if isinstance(v, str) else v
        return t


def test_send_shapes_and_numbers():
    p = P()
    assert p.proto.send(b"resync") == 1
    assert p.proto.send(b"ping", p.obj(t=5)) == 2
    assert p.out == [b'{"data":{},"seq":1,"type":"resync"}', b'{"data":{"t":5},"seq":2,"type":"ping"}']
    p.out.clear()
    p.proto.reset()
    assert p.proto.send(b"resync") == 1
    assert p.proto.send(b"Bad.Type") == (None, b"bad_type")


def test_reply_and_handlers():
    p = P()
    got = []

    def on_ping(data, msg):
        got.append((data.t, msg.seq))
        p.proto.reply(msg, b"pong", p.obj(t=data.t))

    p.proto.on(b"ping", on_ping)
    assert p.receive({"type": "ping", "seq": 7, "data": {"t": 123}}) is True
    assert got == [(123, 7)]
    assert p.sent() == [{"type": "pong", "seq": 1, "re": 7, "data": {"t": 123}}]


def test_requests_get_their_replies():
    p = P()
    replies, handled = [], []
    p.proto.on(b"debug.status", lambda data, msg: handled.append(msg.seq))
    seq = p.proto.request(b"debug.status", None, lambda data, msg: replies.append(data.npcs))
    p.receive({"type": "debug.status", "seq": 1, "re": seq, "data": {"npcs": 3}})
    p.receive({"type": "debug.status", "seq": 2, "re": seq, "data": {"npcs": 4}})
    assert replies == [3] and handled == [2]


@pytest.mark.parametrize(
    "line, reason, re",
    [
        (b"not json", "json", None),
        (b"[1,2]", "not_object", None),
        (b'{"type":"ping","seq":1,"data":{},"extra":1}', "unknown_field", 1),
        (b'{"type":"Ping","seq":1,"data":{}}', "bad_type", 1),
        (b'{"type":"a..b","seq":1,"data":{}}', "bad_type", 1),
        (b'{"type":".a","seq":1,"data":{}}', "bad_type", 1),
        (b'{"type":"a.","seq":1,"data":{}}', "bad_type", 1),
        (b'{"type":"' + b"a" * 65 + b'","seq":1,"data":{}}', "bad_type", 1),
        (b'{"type":"ping","seq":0,"data":{}}', "bad_seq", None),
        (b'{"type":"ping","seq":1.5,"data":{}}', "bad_seq", None),
        (b'{"type":"ping","seq":"1","data":{}}', "bad_seq", None),
        (b'{"type":"ping","seq":9007199254740992,"data":{}}', "bad_seq", None),
        (b'{"type":"ping","seq":1,"re":0,"data":{}}', "bad_re", 1),
        (b'{"type":"ping","seq":1,"data":[]}', "bad_data", 1),
        (b'{"type":"ping","seq":1}', "bad_data", 1),
    ],
)
def test_bad_envelopes_get_an_error(line, reason, re):
    p = P()
    p.proto.on(b"ping", lambda data, msg: None)
    assert p.receive(line) is False
    (err,) = p.sent()
    expected = {"type": "error", "seq": 1, "data": {"code": "bad_envelope", "msg": reason}}
    if re is not None:
        expected["re"] = re
    assert err == expected
    assert any(entry.startswith("proto.bad_envelope") for entry in p.logs)


def test_errors_never_echo_the_line():
    p = P()
    p.receive(b'{"type":"ui.text","seq":1,"data":{"text":"SECRET WORDS"},"x":1}')
    p.receive(b'{"SECRET WORDS"')
    assert all(b"SECRET" not in line for line in p.out)
    assert all("SECRET" not in line for line in p.logs)


def test_unknown_types_and_error_loops():
    p = P()
    assert p.receive({"type": "warp", "seq": 3, "data": {}}) is False
    assert p.sent() == [{"type": "error", "seq": 1, "re": 3, "data": {"code": "unknown_type", "msg": "unknown_type"}}]
    # an incoming error is never answered with another error
    assert p.receive({"type": "error", "seq": 4, "data": {"code": "x", "msg": "y"}}) is False
    assert p.receive(b'{"type":"error","seq":5}') is False
    assert p.sent() == []


def test_throwing_handler_is_contained():
    p = P()
    p.proto.on(b"boom", p.lua.eval("function() error('kaboom') end"))
    assert p.receive({"type": "boom", "seq": 9, "data": {}}) is False
    assert p.sent() == [{"type": "error", "seq": 1, "re": 9, "data": {"code": "handler_failed", "msg": "handler_failed"}}]
    assert any(line.startswith("proto.handler_failed at=") for line in p.logs)


def test_valid_type_rules():
    lua = Bridge()
    Proto = lua.module("lib/kb_proto.lua")
    for good in [b"hello", b"hello.ok", b"ui.open", b"npc.set", b"a.b_c.d9", b"debug.status"]:
        assert Proto.validType(good) is True
    for bad in [b"", b"Hello", b"1a", b"a-b", b"a..b", b"a.", b".a", b"a b", b"a" * 65]:
        assert Proto.validType(bad) is False
