"""kb_sha256 against hashlib, and kb_log's redaction rules."""

import hashlib
import random
import re

import pytest

from kblua import Bridge

pytestmark = pytest.mark.lua


@pytest.fixture(scope="module")
def lua():
    b = Bridge()
    b.sha = b.module("lib/kb_sha256.lua")
    b.Log = b.module("lib/kb_log.lua")
    return b


# ------------------------------------------------------------ sha256


@pytest.mark.parametrize("n", [0, 1, 3, 55, 56, 57, 63, 64, 65, 111, 119, 120, 127, 128, 129, 1000, 4096])
def test_sha256_matches_hashlib(lua, n):
    data = bytes(random.Random(n).randrange(256) for _ in range(n))
    assert lua.sha.hex(data).decode() == hashlib.sha256(data).hexdigest()
    assert lua.sha.digest(data) == hashlib.sha256(data).digest()
    assert lua.sha.short(data).decode() == hashlib.sha256(data).hexdigest()[:8]


def test_sha256_one_mebibyte(lua):
    data = bytes(random.Random(1).randrange(256) for _ in range(1 << 20))
    assert lua.sha.hex(data).decode() == hashlib.sha256(data).hexdigest()


def test_sha256_incremental(lua):
    rng = random.Random(9)
    data = bytes(rng.randrange(256) for _ in range(5000))
    h = lua.sha.new()
    pos = 0
    while pos < len(data):
        step = rng.choice([0, 1, 7, 63, 64, 65, 500])
        h.update(h, data[pos:pos + step])
        pos += step
    assert h.hex(h).decode() == hashlib.sha256(data).hexdigest()


# ------------------------------------------------------------ kb_log


def make_log(lua, debug=False):
    lines = []

    def write(level, line):
        lines.append((level.decode(), line.decode("utf-8")))

    opts = lua.table()
    opts[b"write"] = write
    opts[b"hash"] = lua.sha.short
    opts[b"debug"] = debug
    return lua.Log.new(opts), lines


def fields(lua, **kw):
    t = lua.table()
    for k, v in kw.items():
        t[k.encode()] = v.encode("utf-8") if isinstance(v, str) else v
    return t


def short(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:8]


def test_safe_values_print(lua):
    log, lines = make_log(lua)
    log.info(b"net.listening", fields(lua, host="127.0.0.1", port=27151, ok=True, map="PALLET_TOWN"))
    assert lines == [("info", "net.listening host=127.0.0.1 map=PALLET_TOWN ok=true port=27151")]


def test_text_is_redacted(lua):
    log, lines = make_log(lua)
    text = "Hello there! POKéMON"
    log.warn(b"ui.text", fields(lua, line=text, tiny="a b"))
    level, line = lines[0]
    assert level == "warn"
    assert text not in line and "Hello" not in line
    assert f"line=<str:{len(text)}:{short(text)}>" in line
    assert f"tiny=<str:3:{short('a b')}>" in line


def test_odd_values(lua):
    log, lines = make_log(lua)
    t = lua.eval("{ n = 1.5, big = 2^60, list = {1, 2, 3}, f = print, nan = 0/0 }")
    log.info(b"x", t)
    line = lines[0][1]
    assert "n=1.5" in line and "list=<tbl:3>" in line and "f=<function>" in line and "nan=nan" in line
    assert re.search(r"big=1\.15\d*e\+18", line)
    log.info(b"bad code with spaces")
    log.info(lua.eval("{}"))
    assert lines[1][1] == "<bad-code>" and lines[2][1] == "<bad-code>"
    log.info(b"k", lua.eval('{ ["two words"] = 1 }'))
    assert lines[3][1] == "k <key>=1"


def test_once_and_rearm(lua):
    log, lines = make_log(lua)
    for _ in range(3):
        log.once(b"bind", b"warn", b"net.bind_failed", fields(lua, err="addr_in_use"))
    assert len(lines) == 1
    log.rearm(b"bind")
    log.once(b"bind", b"warn", b"net.bind_failed")
    assert len(lines) == 2


def test_debug_lines(lua):
    log, lines = make_log(lua)
    log.debug(b"quiet")
    assert lines == []
    log.setDebug(True)
    log.debug(b"loud")
    assert lines == [("info", "debug loud")]


def test_forward_only_warnings_and_errors(lua):
    log, _ = make_log(lua)
    seen = []
    log.setForward(lambda level, code, line: seen.append((level, code, line)))
    log.info(b"a")
    log.warn(b"b", fields(lua, n=1))
    log.error(b"c")
    assert seen == [(b"warn", b"b", b"b n=1"), (b"error", b"c", b"c")]
    log.setForward(lua.eval("function() error('boom') end"))
    log.error(b"still.fine")  # a throwing forwarder never breaks the caller


def test_mod_writer_never_uses_the_line_as_a_format(lua):
    calls = []
    mod_log = lua.table()

    def record(level):
        return lambda self, fmt, *args: calls.append((level, fmt, args))

    mod_log[b"info"] = record("info")
    mod_log[b"warn"] = record("warn")
    mod_log[b"error"] = record("error")
    write = lua.Log.modWriter(mod_log)
    write(b"warn", b"100%s done %d")
    assert calls == [("warn", b"%s", (b"100%s done %d",))]
