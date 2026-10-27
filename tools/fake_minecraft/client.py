"""fake_minecraft: connects like the fabric-bridge client (docs/PROTOCOL.md).

Every received message is checked against the schemas (leg g2m). Logs show types, sequence numbers
and sizes; with ``verbose`` they show the message with ROM-derived fields redacted. That makes it
safe to point at the real gen1-bridge in the test instance (docs/PLAN.md section 0.3).
"""
import asyncio
import json
import time
from collections import Counter

from kb_protocol import load_protocol, redact_message
from kb_protocol.aio import STREAM_LIMIT, Connection
from kb_protocol.catalog import build_catalog

PROTOCOL_NUMBER = 1
MOD_VERSION = "fake_minecraft-0.1.0"


class HelloRejected(Exception):
    def __init__(self, reason):
        super().__init__(f"hello rejected: {reason}")
        self.reason = reason


class FakeMinecraft:
    def __init__(self, host="127.0.0.1", port=27151, *, role="test", strict=True, ping_ms=2000,
                 log=None, verbose=False):
        self.host = host
        self.port = port
        self.role = role
        self.strict = strict
        self.ping_ms = ping_ms
        self.log = log or (lambda line: None)
        self.verbose = verbose
        self.protocol = load_protocol()
        self.catalog = build_catalog(self.protocol)
        self.conn = None
        self.hello_ok = None
        self.received = []
        self.counts = Counter()
        self._queue = None
        self._reader = None
        self._pinger = None
        self.eof = False

    # --- connection ------------------------------------------------------------------------------

    async def connect(self, attempts=1, initial_delay=0.5, max_delay=5.0):
        """Connect, retrying with the client's backoff (0.5 s doubling to 5 s)."""
        delay = initial_delay
        for attempt in range(1, attempts + 1):
            try:
                reader, writer = await asyncio.open_connection(self.host, self.port, limit=STREAM_LIMIT)
                break
            except OSError:
                if attempt == attempts:
                    raise
                await asyncio.sleep(delay)
                delay = min(delay * 2, max_delay)
        self.conn = Connection(reader, writer, protocol=self.protocol, send_leg="m2g", recv_leg="g2m",
                               strict=self.strict)
        self.eof = False
        self._queue = asyncio.Queue()
        self._reader = asyncio.create_task(self._read_loop())
        return self

    async def _read_loop(self):
        try:
            while True:
                message = await self.conn.recv()
                if message is None:
                    break
                self.received.append(message)
                self.counts[message["type"]] += 1
                self._log_message("rx", message)
                await self._queue.put(message)
        except Exception as exc:  # recorded and surfaced by the next expect()
            await self._queue.put(exc)
        finally:
            self.eof = True
            await self._queue.put(None)

    def _log_message(self, direction, message):
        if self.verbose:
            self.log(f"{direction} " + json.dumps(redact_message(message, self.catalog), ensure_ascii=False))
        else:
            size = len(json.dumps(message, ensure_ascii=False).encode("utf-8"))
            re = f" re={message['re']}" if "re" in message else ""
            self.log(f"{direction} {message['type']} #{message['seq']}{re} ({size} B)")

    async def close(self):
        for task in (self._pinger, self._reader):
            if task is not None:
                task.cancel()
        self._pinger = self._reader = None
        if self.conn is not None:
            await self.conn.close()

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        await self.close()

    # --- messages --------------------------------------------------------------------------------

    async def send(self, msg_type, data=None, re=None):
        message = await self.conn.send(msg_type, data, re)
        self._log_message("tx", message)
        return message

    async def next(self, timeout=5.0):
        """The next received message; None once the connection has ended."""
        item = await asyncio.wait_for(self._queue.get(), timeout)
        if isinstance(item, Exception):
            raise item
        if item is None:
            await self._queue.put(None)  # keep reporting the end
        return item

    async def expect(self, msg_type, timeout=5.0, re=None):
        """Wait for a message of msg_type (and with that re, if given); others are skipped."""
        deadline = time.monotonic() + timeout
        while True:
            left = deadline - time.monotonic()
            if left <= 0:
                raise asyncio.TimeoutError(f"no {msg_type} within {timeout:g} s")
            message = await self.next(left)
            if message is None:
                raise ConnectionError(f"connection closed while waiting for {msg_type}")
            if message["type"] == msg_type and (re is None or message.get("re") == re):
                return message

    async def request(self, msg_type, data=None, reply_type=None, timeout=5.0):
        message = await self.send(msg_type, data)
        return await self.expect(reply_type or msg_type, timeout, re=message["seq"])

    async def leave_route(self, zone, timeout=5.0):
        """WORLD_SESSIONS_PLAN WS1: route.leave{zone} as the client sends it when the body is nowhere in the zone;
        returns gen1-bridge's act.result (the back warp follows as a warp)."""
        return await self.request("route.leave", {"zone": zone}, "act.result", timeout)

    async def hello(self, protocol=PROTOCOL_NUMBER, start_pings=True, **extra):
        data = {"protocol": protocol, "role": self.role, "mod": MOD_VERSION}
        data.update(extra)
        message = await self.send("hello", data)
        while True:
            reply = await self.next()
            if reply is None:
                raise ConnectionError("connection closed during the handshake")
            if reply["type"] == "hello.reject" and reply.get("re") == message["seq"]:
                raise HelloRejected(reply["data"]["reason"])
            if reply["type"] == "hello.ok" and reply.get("re") == message["seq"]:
                self.hello_ok = reply
                if start_pings and self.ping_ms:
                    self._pinger = asyncio.create_task(self._ping_loop())
                return reply

    async def _ping_loop(self):
        while True:
            await asyncio.sleep(self.ping_ms / 1000)
            if self.conn is None or self.conn.closed:
                return
            try:
                await self.send("ping", {"t": int(time.monotonic() * 1000)})
            except (ConnectionError, OSError):
                return
