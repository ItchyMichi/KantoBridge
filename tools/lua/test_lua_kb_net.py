"""kb_net (listener, framing, send queue) against the fake LuaSocket 3.0-rc1."""

import pytest

from kblua import Bridge

pytestmark = pytest.mark.lua

HOST, PORT = b"127.0.0.1", 27151


class Harness:
    def __init__(self, **opts):
        self.lua = Bridge()
        lua = self.lua
        self.Net = lua.module("lib/kb_net.lua")
        sha = lua.module("lib/kb_sha256.lua")
        Log = lua.module("lib/kb_log.lua")
        self.logs = []
        log_opts = lua.table()
        log_opts[b"write"] = lambda level, line: self.logs.append(line.decode())
        log_opts[b"hash"] = sha.short
        self.log = Log.new(log_opts)
        self.lines, self.connects, self.disconnects = [], 0, []
        self.time = 0.0
        self.on_line = None
        o = lua.table()
        o[b"socket"] = lua.fake.socket
        o[b"clock"] = lambda: self.time
        o[b"log"] = self.log
        o[b"onLine"] = self._line
        o[b"onConnect"] = self._connect
        o[b"onDisconnect"] = lambda reason: self.disconnects.append(reason.decode())
        for k, v in opts.items():
            o[k.encode()] = v
        self.net = self.Net.new(o)

    def _line(self, line):
        self.lines.append(line)
        if self.on_line:
            self.on_line(line)

    def _connect(self):
        self.connects += 1

    def pump(self, n=1):
        for _ in range(n):
            self.net.pump()

    def connect(self):
        fake = self.lua.net
        peer = fake.connect(fake, HOST, PORT)  # a tuple (nil, err) on failure
        assert not isinstance(peer, tuple), peer
        return peer


def call(obj, name, *args):
    return obj[name.encode()](obj, *args)


def test_listener_is_lazy_and_never_reuses_the_address():
    h = Harness()
    fake = h.lua.net
    assert fake.created == 0 and not call(fake, "listening", HOST, PORT)
    h.pump()
    assert call(fake, "listening", HOST, PORT)
    assert h.net.listening()
    assert fake.reuseaddrSet == 0 and fake.bindHelperUsed == 0
    assert "net.listening host=127.0.0.1 port=27151" in h.logs


def test_bind_retries_every_second_and_logs_once():
    h = Harness()
    fake = h.lua.net
    call(fake, "occupy", HOST, PORT)
    h.pump(5)
    assert fake.created == 1 and not h.net.listening()
    h.time = 0.5
    h.pump(5)
    assert fake.created == 1
    h.time = 1.0
    h.pump()
    assert fake.created == 2
    busy = [line for line in h.logs if line.startswith("net.bind_failed")]
    assert busy == ["net.bind_failed err=addr_in_use port=27151 retry=1 stage=bind"]
    call(fake, "free", HOST, PORT)
    h.time = 2.0
    h.pump()
    assert h.net.listening()


def test_accepted_client_is_made_non_blocking():
    h = Harness()
    h.pump()
    peer = h.connect()
    h.pump()
    assert h.net.connected() and h.connects == 1
    assert peer.conn.server.timeout == 0
    # a blocking client would raise inside the fake on this receive
    h.pump(3)


def test_framing_reassembles_and_tolerates_crlf():
    h = Harness()
    h.pump()
    peer = h.connect()
    h.pump()
    call(peer, "send", b"a\nb\r\n\n\r\nc")
    h.pump()
    assert h.lines == [b"a", b"b"]
    call(peer, "send", b"d\nlast")
    h.pump()
    assert h.lines == [b"a", b"b", b"cd"]
    call(peer, "send", b"\n")
    h.pump()
    assert h.lines[-1] == b"last"


def test_long_line_arrives_in_pieces():
    h = Harness()
    h.pump()
    peer = h.connect()
    h.pump()
    line = b"x" * 100_000
    for i in range(0, len(line), 3000):
        call(peer, "send", line[i:i + 3000])
        h.pump()
    assert h.lines == []
    call(peer, "send", b"\n")
    h.pump()
    assert h.lines == [line]


def test_oversize_line_drops_the_client():
    h = Harness(maxLine=100)
    h.pump()
    peer = h.connect()
    h.pump()
    call(peer, "send", b"y" * 150)
    h.pump()
    assert not h.net.connected()
    assert h.disconnects == ["oversize"]
    assert call(peer, "closedByServer")


def test_read_budget_spreads_a_flood_over_steps():
    h = Harness(readBudget=8192)
    h.pump()
    peer = h.connect()
    h.pump()
    call(peer, "send", b"".join(b"%05d" % i + b"z" * 3000 + b"\n" for i in range(10)))
    h.pump()
    first = len(h.lines)
    assert 0 < first < 10
    h.pump(10)
    assert len(h.lines) == 10


def test_send_keeps_the_unsent_tail():
    h = Harness()
    h.pump()
    peer = h.connect()
    h.pump()
    call(peer, "setWindow", 3)
    assert h.net.send(b"hello")
    h.pump()
    assert call(peer, "take") == b"hel"
    h.pump(3)
    assert call(peer, "take") == b""
    call(peer, "setWindow", 100)
    h.pump()
    assert call(peer, "take") == b"lo\n"
    assert h.net.queued() == 0


def test_small_lines_are_coalesced_into_one_send():
    h = Harness()
    h.pump()
    peer = h.connect()
    h.pump()
    for i in range(10):
        h.net.send(b"line%d" % i)
    h.pump()
    assert call(peer, "take") == b"".join(b"line%d\n" % i for i in range(10))
    assert call(peer, "sendCalls") == 1


def test_send_without_client_and_bad_lines():
    h = Harness()
    h.pump()
    assert h.net.send(b"nobody") is False
    peer = h.connect()
    h.pump()
    with pytest.raises(Exception):
        h.net.send(b"two\nlines")
    assert h.net.send(b"ok") is True
    h.pump()
    assert call(peer, "take") == b"ok\n"


def test_backlog_limit_drops_a_stuck_client():
    h = Harness(maxQueued=1000)
    h.pump()
    peer = h.connect()
    h.pump()
    call(peer, "setWindow", 0)
    sent = [h.net.send(b"q" * 99) for _ in range(20)]
    assert sent[0] is True and sent[-1] is False
    assert h.disconnects == ["backlog"]
    assert not h.net.connected()


def test_new_connection_replaces_the_old():
    h = Harness()
    h.pump()
    first = h.connect()
    h.pump()
    second = h.connect()
    h.pump()
    assert h.connects == 2 and h.disconnects == ["replaced"]
    assert call(first, "closedByServer") and not call(second, "closedByServer")
    h.net.send(b"to-second")
    h.pump()
    assert call(second, "take") == b"to-second\n" and call(first, "take") == b""


def test_peer_close_is_detected_after_draining():
    h = Harness()
    h.pump()
    peer = h.connect()
    h.pump()
    call(peer, "send", b"bye\n")
    call(peer, "close")
    h.pump()
    assert h.lines == [b"bye"] and h.disconnects == ["closed"]


def test_disconnect_from_a_handler_stops_delivery():
    h = Harness()
    h.pump()
    peer = h.connect()
    h.pump()
    h.on_line = lambda line: h.net.disconnect(b"bad_hello")
    call(peer, "send", b"one\ntwo\nthree\n")
    h.pump()
    assert h.lines == [b"one"] and h.disconnects == ["bad_hello"]


def test_close_releases_the_port():
    h = Harness()
    h.pump()
    h.connect()
    h.pump()
    h.net.close()
    assert not call(h.lua.net, "listening", HOST, PORT)
    assert h.disconnects == ["shutdown"]


def test_missing_socket_module_is_survivable():
    lua = Bridge()
    Net = lua.module("lib/kb_net.lua")
    o = lua.table()
    o[b"socket"] = lua.eval("function() error('socket not available') end")
    o[b"clock"] = lambda: 0
    net = Net.new(o)
    net.pump()
    net.pump()
    assert not net.listening()


def test_framer_alone():
    lua = Bridge()
    Net = lua.module("lib/kb_net.lua")
    framer = Net.newFramer(10)
    got = []
    assert framer.push(b"ab\ncd", lambda line: got.append(line)) is True
    assert got == [b"ab"] and framer.pending() == 2
    assert framer.push(b"efghijklm", lambda line: got.append(line)) == (None, b"oversize")
    assert framer.pending() == 0


# ------------------------------------------------------------ M9: a busy port falls back along the span


def fallback_harness():
    return Harness(portSpan=8, fallbackAfter=3)


def listening_on(h, port):
    return bool(call(h.lua.net, "listening", HOST, port))


def test_a_busy_port_falls_back_to_the_next_free_one_after_the_grace():
    h = fallback_harness()
    fake = h.lua.net
    call(fake, "occupy", HOST, PORT)
    h.pump()
    h.time = 1.0
    h.pump()
    h.time = 2.0
    h.pump()
    assert not h.net.listening(), "the configured port gets its grace first"
    h.time = 3.0
    h.pump()
    assert h.net.listening() and listening_on(h, PORT + 1) and not listening_on(h, PORT)
    assert (h.net.port(), h.net.configuredPort(), h.net.fallback()) == (PORT + 1, PORT, True)
    assert fake.reuseaddrSet == 0 and fake.bindHelperUsed == 0, "never a port taken by reuse"
    assert [line for line in h.logs if line.startswith("net.")] == [
        "net.bind_failed err=addr_in_use port=27151 retry=1 stage=bind",
        "net.listening configured=27151 fallback=true host=127.0.0.1 port=27152",
    ]
    stats = h.net.stats()
    assert (stats.port, stats.portConfigured, stats.portFallback) == (PORT + 1, PORT, True)
    # a client reaches it there
    peer = call(fake, "connect", HOST, PORT + 1)
    assert not isinstance(peer, tuple)
    h.pump()
    assert h.net.connected()


def test_busy_ports_of_the_span_are_skipped():
    h = fallback_harness()
    fake = h.lua.net
    for p in (PORT, PORT + 1, PORT + 2):
        call(fake, "occupy", HOST, p)
    h.pump()
    h.time = 3.0
    h.pump()
    assert h.net.port() == PORT + 3 and listening_on(h, PORT + 3)


def test_a_refused_port_falls_back_too():
    h = fallback_harness()
    fake = h.lua.net
    # a port Windows keeps for itself (WSAEACCES): bind says "permission denied"
    master = h.lua.eval("""function(fake, host, port)
      local real = fake.socket.tcp
      fake.socket.tcp = function()
        local s = real()
        local bind = s.bind
        s.bind = function(self, h, p)
          if tonumber(p) == port then return nil, "permission denied" end
          return bind(self, h, p)
        end
        return s
      end
    end""")
    master(h.lua.fake, HOST, PORT)
    h.pump()
    h.time = 3.5
    h.pump()
    assert h.net.port() == PORT + 1 and h.net.fallback()
    assert "net.bind_failed err=access port=27151 retry=1 stage=bind" in h.logs


def test_every_port_busy_retries_every_second_and_logs_once():
    h = fallback_harness()
    fake = h.lua.net
    for p in range(PORT, PORT + 9):
        call(fake, "occupy", HOST, p)
    for t in (0.0, 1.0, 2.0, 3.0, 4.0, 5.0):
        h.time = t
        h.pump()
    assert not h.net.listening() and h.net.port() == PORT
    assert [line for line in h.logs if line.startswith("net.bind_failed")] == [
        "net.bind_failed err=addr_in_use port=27151 retry=1 stage=bind",
        "net.bind_failed err=all_busy port=27151 retry=1 span=8 stage=bind",
    ]
    created = fake.created
    h.time = 5.5
    h.pump()
    assert fake.created == created, "nothing tried between retries"
    call(fake, "free", HOST, PORT + 5)
    h.time = 6.0
    h.pump()
    assert h.net.port() == PORT + 5 and listening_on(h, PORT + 5)
    assert fake.reuseaddrSet == 0


def test_the_configured_port_wins_when_it_frees_within_the_grace():
    h = fallback_harness()
    fake = h.lua.net
    call(fake, "occupy", HOST, PORT)
    h.pump()
    call(fake, "free", HOST, PORT)
    h.time = 1.0
    h.pump()
    assert h.net.port() == PORT and not h.net.fallback() and listening_on(h, PORT)
    assert "net.listening host=127.0.0.1 port=27151" in h.logs


def test_set_port_starts_over_from_the_new_configured_port():
    h = fallback_harness()
    fake = h.lua.net
    call(fake, "occupy", HOST, PORT)
    h.pump()
    h.time = 3.0
    h.pump()
    assert h.net.port() == PORT + 1
    h.net.setPort(27170)
    h.pump()
    assert (h.net.port(), h.net.configuredPort(), h.net.fallback()) == (27170, 27170, False)
    assert listening_on(h, 27170) and not listening_on(h, PORT + 1)
    # the same configured port again changes nothing
    h.net.setPort(27170)
    assert listening_on(h, 27170)


def test_the_span_stops_at_the_last_port():
    h = Harness(port=65534, portSpan=8, fallbackAfter=0)
    fake = h.lua.net
    call(fake, "occupy", HOST, 65534)
    call(fake, "occupy", HOST, 65535)
    h.pump()
    assert not h.net.listening()
    assert any("err=all_busy" in line for line in h.logs)


def test_a_broken_listener_starts_over_from_the_configured_port():
    h = fallback_harness()
    fake = h.lua.net
    call(fake, "occupy", HOST, PORT)
    h.pump()
    h.time = 3.0
    h.pump()
    assert h.net.port() == PORT + 1
    call(fake, "free", HOST, PORT)
    # the listener breaks (accept fails): it is rebuilt from the configured port
    server = fake.listeners[b"127.0.0.1:27152"]
    server.closed = True
    h.time = 3.1
    h.pump()
    assert not h.net.listening()
    h.time = 4.2
    h.pump()
    assert h.net.port() == PORT and listening_on(h, PORT)
