"""Spike S4 (docs/PLAN.md M0): lupa loads gen1-bridge modules in the
sandbox-parity environment, and messages round-trip through the fake socket,
including partial reads and a send-window-limited tail."""

import json

import pytest

from kblua import Bridge

pytestmark = pytest.mark.lua

# The wiring M1 will put in main.lua, written the same way: modules imported
# into the mod env, the socket resolved lazily through the sandbox's require.
WIRING = b"""
local import = ...
local json = import("lib/kb_json.lua")
local sha256 = import("lib/kb_sha256.lua")
local Log = import("lib/kb_log.lua")
local Net = import("lib/kb_net.lua")
local Proto = import("lib/kb_proto.lua")
local now = 0
local log = Log.new({ write = function() end, hash = sha256.short })
local proto
local net = Net.new({
  socket = function() return require("socket") end,
  clock = function() return now end,
  log = log,
  onLine = function(line) proto.receive(line) end,
  onConnect = function() proto.reset() end,
})
proto = Proto.new({ json = json, write = net.send, log = log })
proto.on("ping", function(data, msg)
  proto.reply(msg, "pong", { t = data.t, echo = data.text, n = #data.list })
end)
return {
  step = function() now = now + 1 / 60; net.pump() end,
  net = net,
  proto = proto,
}
"""


def wire(b):
    chunk = b.env.load(WIRING, b"@s4_wiring.lua")
    return chunk(lambda rel: b.module(rel.decode()))


def call(obj, name, *args):
    return obj[name.encode()](obj, *args)


def test_s4_round_trip_through_the_fake_socket():
    b = Bridge()
    before = b.env_keys()
    api = wire(b)
    api.step()
    assert api.net.listening()

    peer = call(b.net, "connect", b"127.0.0.1", 27151)
    api.step()
    assert api.net.connected()

    ping = {"type": "ping", "seq": 1, "data": {"t": 5, "text": "Synthetic é ♂ text", "list": [1, 2, 3]}}
    line = json.dumps(ping, ensure_ascii=False).encode("utf-8") + b"\n"
    call(peer, "setWindow", 10)  # the reply can only leave 10 bytes at a time
    call(peer, "send", line[:17])  # a partial read first
    api.step()
    assert call(peer, "take") == b""
    call(peer, "send", line[17:])
    api.step()
    received = call(peer, "take")
    assert len(received) == 10  # the window held back the tail

    for _ in range(20):
        call(peer, "setWindow", 10)
        api.step()
        received += call(peer, "take")
        if received.endswith(b"\n"):
            break
    reply = json.loads(received.decode("utf-8"))
    assert reply == {"type": "pong", "seq": 1, "re": 1,
                     "data": {"t": 5, "echo": "Synthetic é ♂ text", "n": 3}}
    assert b.env_keys() == before, "the wiring leaked a global into the mod env"


def test_s4_without_network_permission_nothing_listens():
    b = Bridge(permissions=("engine_internals",))
    api = wire(b)
    for _ in range(3):
        api.step()
    assert not api.net.listening()
