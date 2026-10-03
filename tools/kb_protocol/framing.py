"""Newline-delimited strict JSON (docs/PROTOCOL.md, "Framing").

One UTF-8 JSON object per line. Parsing rejects NaN/Infinity, duplicate keys, lone surrogates,
non-object top levels and trailing data. Lines may end in CRLF; empty lines are ignored.
"""
import json

from .jsonfmt import canonical

MAX_LINE_BYTES = 4 * 1024 * 1024


class FramingError(ValueError):
    """A line that is not a valid protocol frame. ``code`` is safe to log; the text is not."""

    def __init__(self, code):
        super().__init__(code)
        self.code = code


def encode_line(message, max_bytes=MAX_LINE_BYTES):
    if not isinstance(message, dict):
        raise FramingError("not_object")
    try:
        data = canonical(message).encode("utf-8")
    except (TypeError, ValueError, UnicodeEncodeError) as exc:
        raise FramingError("unencodable") from exc
    if len(data) + 1 > max_bytes:
        raise FramingError("line_too_long")
    return data + b"\n"


def _reject_constant(_name):
    raise FramingError("non_finite_number")


def _no_duplicates(pairs):
    out = {}
    for key, value in pairs:
        if key in out:
            raise FramingError("duplicate_key")
        out[key] = value
    return out


def _check_surrogates(value):
    if isinstance(value, str):
        try:
            value.encode("utf-8")
        except UnicodeEncodeError as exc:
            raise FramingError("lone_surrogate") from exc
    elif isinstance(value, list):
        for item in value:
            _check_surrogates(item)
    elif isinstance(value, dict):
        for key, item in value.items():
            _check_surrogates(key)
            _check_surrogates(item)


def decode_line(line):
    """Parse one line (bytes or str, without or with its newline) into a dict."""
    if isinstance(line, bytes):
        try:
            text = line.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise FramingError("bad_utf8") from exc
    else:
        text = line
    text = text.rstrip("\n")
    if text.endswith("\r"):
        text = text[:-1]
    try:
        value = json.loads(text, parse_constant=_reject_constant, object_pairs_hook=_no_duplicates)
    except FramingError:
        raise
    except (json.JSONDecodeError, RecursionError) as exc:
        raise FramingError("bad_json") from exc
    if not isinstance(value, dict):
        raise FramingError("not_object")
    _check_surrogates(value)
    return value


class LineBuffer:
    """Reassembles lines from arbitrary byte chunks."""

    def __init__(self, max_bytes=MAX_LINE_BYTES):
        self.max_bytes = max_bytes
        self._buf = bytearray()

    def feed(self, chunk):
        """Add bytes; return the complete non-empty lines (without newlines)."""
        self._buf += chunk
        lines = []
        while True:
            idx = self._buf.find(b"\n")
            if idx < 0:
                break
            line = bytes(self._buf[:idx])
            del self._buf[: idx + 1]
            if line.endswith(b"\r"):
                line = line[:-1]
            if len(line) + 1 > self.max_bytes:
                raise FramingError("line_too_long")
            if line:
                lines.append(line)
        if len(self._buf) + 1 > self.max_bytes:
            raise FramingError("line_too_long")
        return lines

    @property
    def pending(self):
        return len(self._buf)
