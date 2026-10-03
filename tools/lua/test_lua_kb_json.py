"""kb_json (gen1-bridge/lib/kb_json.lua) under LuaJIT, checked against Python's json."""

import json
import math
import random
import re

import pytest

from kblua import Bridge

pytestmark = pytest.mark.lua

MAX_EXACT = 2**53


@pytest.fixture(scope="module")
def lua():
    b = Bridge()
    real = b.module("lib/kb_json.lua")
    # Always two results (value, err), so Python can unpack them; lupa returns
    # a single value when Lua returns one.
    b.json = b.eval(
        """function(json)
          local shim = setmetatable({}, { __index = json })
          function shim.decode(s, m) local v, e = json.decode(s, m) return v, e end
          function shim.encode(v) local s, e = json.encode(v) return s, e end
          return shim
        end"""
    )(real)
    b.length = b.eval("function(t) return #t end")
    return b


# ------------------------------------------------------------ conversions


def to_py(lua, v):
    kind = lua.json.kind(v)
    if kind == b"null":
        return None
    if kind == b"object":
        return {k.decode("utf-8"): to_py(lua, x) for k, x in v.items()}
    if kind == b"array":
        return [to_py(lua, v[i]) for i in range(1, lua.length(v) + 1)]
    if isinstance(v, bytes):
        return v.decode("utf-8")
    return v


def to_lua(lua, v):
    if v is None:
        return lua.json.null
    if isinstance(v, (bool, int, float)):
        return v
    if isinstance(v, str):
        return v.encode("utf-8")
    if isinstance(v, list):
        return lua.json.array(lua.table(*[to_lua(lua, x) for x in v]))
    if isinstance(v, dict):
        t = lua.table()
        for k, x in v.items():
            t[k.encode("utf-8")] = to_lua(lua, x)
        return lua.json.object(t)
    raise TypeError(type(v))


def decode(lua, data: bytes):
    value, err = lua.json.decode(data)
    return value, err


def encode(lua, value) -> bytes:
    out, err = lua.json.encode(value)
    assert err is None, err
    return out


# ------------------------------------------------------------ random documents

CHARS = (
    "abcXYZ019 _-.:/\"\\\b\f\n\r\t\x00\x01\x1f\x7f"
    "éèü♂♀×¥ПОКЕ日本語"
    "  ﻿￿"
    "\U0001F600\U0001F4A9\U00010000\U0010FFFF"
)


def rand_str(rng):
    return "".join(rng.choice(CHARS) for _ in range(rng.randrange(0, 12)))


def rand_doc(rng, depth=0, floats=True):
    roll = rng.random()
    if depth >= 5 or roll < 0.45:
        kind = rng.randrange(6 if floats else 5)
        if kind == 0:
            return None
        if kind == 1:
            return rng.random() < 0.5
        if kind == 2:
            return rng.choice([0, 1, -1, 7, 255, MAX_EXACT, -MAX_EXACT, rng.randrange(-MAX_EXACT, MAX_EXACT)])
        if kind == 3:
            return rand_str(rng)
        if kind == 4:
            return rng.randrange(-1000, 1000)
        return rng.choice([0.5, -2.25, 1e-7, 3.141592653589793, 1e300, -1e-300, rng.uniform(-1e6, 1e6)])
    if roll < 0.72:
        return [rand_doc(rng, depth + 1, floats) for _ in range(rng.randrange(0, 5))]
    return {rand_str(rng): rand_doc(rng, depth + 1, floats) for _ in range(rng.randrange(0, 5))}


def docs(seed, n, floats=True):
    rng = random.Random(seed)
    return [rand_doc(rng, floats=floats) for _ in range(n)]


# ------------------------------------------------------------ properties


@pytest.mark.parametrize("ensure_ascii", [False, True])
def test_decodes_what_python_encodes(lua, ensure_ascii):
    rng = random.Random(7)
    for doc in docs(1001 + ensure_ascii, 400):
        indent = rng.choice([None, 2])
        text = json.dumps(doc, ensure_ascii=ensure_ascii, indent=indent)
        value, err = decode(lua, text.encode("utf-8"))
        assert err is None, err
        assert to_py(lua, value) == doc


def test_python_decodes_what_lua_encodes(lua):
    for doc in docs(2002, 400):
        out = encode(lua, to_lua(lua, doc))
        assert json.loads(out.decode("utf-8")) == doc


def test_bytes_match_python_sorted_compact(lua):
    for doc in docs(3003, 400, floats=False):
        expected = json.dumps(doc, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        assert encode(lua, to_lua(lua, doc)) == expected.encode("utf-8")


def test_round_trip_is_stable(lua):
    for doc in docs(4004, 200, floats=False):
        once = encode(lua, to_lua(lua, doc))
        value, err = decode(lua, once)
        assert err is None
        assert encode(lua, value) == once


# ------------------------------------------------------------ kinds


@pytest.mark.parametrize(
    "text",
    [b"{}", b"[]", b"[null]", b'{"a":null}', b'[[],{},[{}]]', b'{"a":[],"b":{}}', b"null", b"[null,null,1]"],
)
def test_kinds_survive(lua, text):
    value, err = decode(lua, text)
    assert err is None
    assert encode(lua, value) == text


def test_unmarked_tables(lua):
    assert encode(lua, lua.eval("{}")) == b"[]"
    assert encode(lua, lua.eval("{1, 2, 3}")) == b"[1,2,3]"
    assert encode(lua, lua.eval("{b = 1, a = {}}")) == b'{"a":[],"b":1}'
    assert encode(lua, lua.json.object(lua.eval("{}"))) == b"{}"
    assert encode(lua, lua.json.array(lua.eval("{}"))) == b"[]"


@pytest.mark.parametrize(
    "code, reason",
    [
        ("{1, 2, x = 3}", "mixed or sparse"),
        ("{[1] = 1, [3] = 3}", "mixed or sparse"),
        ("{[1.5] = 1}", "neither a string nor a positive integer"),
        ("{[true] = 1}", "neither a string nor a positive integer"),
        ("(function() local t = {} t.self = t return t end)()", "cycle"),
        ("{f = print}", "cannot encode a function"),
        ("{x = 0/0}", "NaN or infinity"),
        ("{x = 1/0}", "NaN or infinity"),
        ("{x = -1/0}", "NaN or infinity"),
        ("{s = '\\255'}", "invalid UTF-8"),
        ("setmetatable({}, {})", "foreign metatable"),
    ],
)
def test_encode_refuses(lua, code, reason):
    out, err = lua.json.encode(lua.eval(code))
    assert out is None
    assert reason in err.decode()


def test_encode_marked_table_rules(lua):
    obj = lua.json.object(lua.eval("{1}"))
    assert lua.json.encode(obj)[0] is None
    arr = lua.json.array(lua.eval("{a = 1}"))
    assert lua.json.encode(arr)[0] is None


def test_encode_depth_limit(lua):
    deep = lua.eval("function(n) local t = {} local c = t for i = 1, n - 1 do c[1] = {} c = c[1] end return t end")
    assert lua.json.encode(deep(64))[0] is not None
    out, err = lua.json.encode(deep(65))
    assert out is None and b"too deep" in err


def test_numbers(lua):
    assert encode(lua, MAX_EXACT) == b"9007199254740992"
    assert encode(lua, -MAX_EXACT) == b"-9007199254740992"
    assert encode(lua, 0.1) == b"0.10000000000000001"
    assert encode(lua, lua.eval("-0.0")) == b"0"
    assert encode(lua, 1e20) == b"1e+20"
    value, err = decode(lua, b"-0")
    assert err is None and value == 0 and math.copysign(1, value) == 1
    assert decode(lua, b"9007199254740992")[0] == MAX_EXACT
    assert decode(lua, b"1.5e3")[0] == 1500
    assert decode(lua, b"1E-2")[0] == 0.01


def test_strings(lua):
    assert decode(lua, b'"\\ud83d\\ude00"')[0] == "\U0001F600".encode("utf-8")
    assert decode(lua, b'"\\u00e9\\/\\b\\f\\n\\r\\t\\"\\\\"')[0] == "é/\b\f\n\r\t\"\\".encode("utf-8")
    assert decode(lua, b'"\\u0000"')[0] == b"\x00"
    assert encode(lua, b"\x00\x1f\x7f") == b'"\\u0000\\u001f\x7f"'
    assert encode(lua, "♂é".encode("utf-8")) == '"♂é"'.encode("utf-8")


MUST_REJECT = [
    b"", b" ", b"[", b"]", b"{", b"}", b"[1,]", b"[,1]", b"{,}", b'{"a":1,}', b'{"a" 1}', b"{a:1}",
    b"{'a':1}", b"'x'", b"01", b"-01", b"00", b"1.", b".5", b"-", b"+1", b"1e", b"1e+", b"0x10",
    b"NaN", b"Infinity", b"-Infinity", b"nan", b"tru", b"nul", b"True", b"[1 2]", b"[1]x", b"{} {}",
    b"1 2", b'"abc', b'"\\x"', b'"\\u12"', b'"\\u12g4"', b'"\\ud83d"', b'"\\ude00"', b'"\\ud83d\\u0041"',
    b'"a\nb"', b'"\x01"', b'"\t"', b"[1]//c", b"/*c*/1", b'{"a":1,"a":2}', b"9007199254740993",
    b"-9007199254740993", b"1e400", b"-1e400", b'"\xff"', b'"\xc0\xaf"', b'"\xed\xa0\x80"',
    b'"\xf4\x90\x80\x80"', b'"\xe2\x82"', b"\xef\xbb\xbf{}", b"[" * 65 + b"]" * 65,
]


@pytest.mark.parametrize("text", MUST_REJECT)
def test_decode_rejects(lua, text):
    value, err = decode(lua, text)
    assert value is None
    assert re.fullmatch(rb"kb_json: [a-zA-Z0-9 ',:\[\]\{\}\\-]+( at byte \d+)?", err), err


def test_python_rejects_or_differs_too(lua):
    # sanity: the corpus is not just a list of things Python happens to accept
    accepted_by_python = 0
    for text in MUST_REJECT:
        try:
            json.loads(text.decode("utf-8"))
            accepted_by_python += 1
        except Exception:
            pass
    assert accepted_by_python < len(MUST_REJECT)


def test_depth_limit(lua):
    ok = b"[" * 64 + b"]" * 64
    assert decode(lua, ok)[1] is None
    assert b"too deep" in decode(lua, b"[" * 65 + b"]" * 65)[1]


def test_errors_never_quote_the_input(lua):
    marker = "SECRETTEXT"
    for text in [
        f'{{"{marker}": tru}}',
        f'["{marker}", 01]',
        f'["{marker}\\q"]',
        f'{{"{marker}":1, "{marker}":2}}',
        f'"{marker}',
    ]:
        value, err = decode(lua, text.encode())
        assert value is None
        assert marker.encode() not in err
    out, err = lua.json.encode(lua.eval('{SECRETTEXT = {x = 0/0}}'))
    assert out is None and b"SECRETTEXT" in err  # keys that look like ids may show in a path
    out, err = lua.json.encode(lua.eval('{["two words"] = {x = 0/0}}'))
    assert out is None and b"two words" not in err and b"<key>" in err


def test_decode_limits(lua):
    assert lua.json.decode(b"[1]", 2) == (None, b"kb_json: input too long")
    assert lua.json.decode(lua.eval("{}"))[0] is None


def test_big_string_is_fast(lua):
    import time

    text = ('"' + ("é\\n\\u00e9" * 50000) + "x" * (1 << 20) + '"').encode("utf-8")
    start = time.perf_counter()
    value, err = decode(lua, text)
    assert err is None
    out = encode(lua, value)
    assert json.loads(out) == json.loads(text)
    assert time.perf_counter() - start < 5.0
