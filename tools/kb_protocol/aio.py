"""One NDJSON protocol connection over asyncio streams.

Used by fake_gen1 (server side) and fake_minecraft (client side). Every message sent and received
can be checked against the schemas for its leg. Problems are reported as Problem lists (pointers and
schema keywords only), never values.
"""
import asyncio

from .builders import Sender
from .framing import MAX_LINE_BYTES, FramingError, decode_line, encode_line

# asyncio's StreamReader stops at 64 KiB per line by default; the protocol allows 4 MiB.
STREAM_LIMIT = MAX_LINE_BYTES + 1


class ProtocolViolation(Exception):
    """A message failed its schema check. str() holds the type and problems only."""

    def __init__(self, direction, msg_type, problems):
        self.direction = direction
        self.msg_type = msg_type
        self.problems = list(problems)
        super().__init__(f"{direction} {msg_type}: " + "; ".join(str(p) for p in self.problems))


class Connection:
    """Send and receive envelopes on one stream pair.

    ``send_leg``/``recv_leg`` name the protocol legs (g2m, m2g) used for schema checks when a
    ``protocol`` is given. With ``strict``, invalid messages raise ProtocolViolation; otherwise they
    are recorded in ``problems`` and still returned.
    """

    def __init__(self, reader, writer, *, protocol=None, send_leg=None, recv_leg=None, strict=True):
        self.reader = reader
        self.writer = writer
        self.protocol = protocol
        self.send_leg = send_leg
        self.recv_leg = recv_leg
        self.strict = strict
        self.sender = Sender()
        self.problems = []
        self.closed = False

    def _check(self, direction, leg, message):
        if self.protocol is None:
            return
        problems = self.protocol.check(message, leg=leg)
        if problems:
            violation = ProtocolViolation(direction, message.get("type"), problems)
            self.problems.append(violation)
            if self.strict:
                raise violation

    async def send(self, msg_type, data=None, re=None):
        message = self.sender.make(msg_type, data, re)
        await self.send_raw(message)
        return message

    async def send_raw(self, message):
        """Send a complete envelope as is (tests use this for malformed or replayed messages)."""
        self._check("send", self.send_leg, message)
        self.writer.write(encode_line(message))
        await self.writer.drain()

    async def send_bytes(self, data):
        self.writer.write(data)
        await self.writer.drain()

    async def recv(self, timeout=None):
        """The next message, or None at end of stream. Raises FramingError on a bad line."""
        while True:
            try:
                coro = self.reader.readuntil(b"\n")
                line = await (asyncio.wait_for(coro, timeout) if timeout is not None else coro)
            except asyncio.IncompleteReadError as exc:
                if exc.partial.strip():
                    raise FramingError("truncated") from exc
                self.closed = True
                return None
            except asyncio.LimitOverrunError as exc:
                raise FramingError("line_too_long") from exc
            except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError):
                self.closed = True
                return None
            line = line.rstrip(b"\n")
            if line.endswith(b"\r"):
                line = line[:-1]
            if not line:
                continue
            message = decode_line(line)
            self._check("recv", self.recv_leg, message)
            return message

    async def close(self):
        if self.closed and self.writer.is_closing():
            return
        self.closed = True
        self.writer.close()
        try:
            await self.writer.wait_closed()
        except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError, OSError):
            pass
