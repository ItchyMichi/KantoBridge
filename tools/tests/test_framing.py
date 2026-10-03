"""Newline-delimited strict JSON framing."""
import json

import pytest

from kb_protocol import FramingError, LineBuffer, decode_line, encode_line


def test_round_trip_is_canonical_utf8():
    msg = {"type": "log", "seq": 1, "data": {"msg": "é♂ ok", "code": "x", "level": "info"}}
    line = encode_line(msg)
    assert line.endswith(b"\n") and line.count(b"\n") == 1
    assert "é♂".encode("utf-8") in line
    assert line == b'{"data":{"code":"x","level":"info","msg":"\xc3\xa9\xe2\x99\x82 ok"},"seq":1,"type":"log"}\n'
    assert decode_line(line) == msg


def test_empty_object_stays_object():
    assert decode_line(encode_line({"type": "resync", "seq": 1, "data": {}}))["data"] == {}


@pytest.mark.parametrize("text,code", [
    ('{"a": NaN}', "non_finite_number"),
    ('{"a": Infinity}', "non_finite_number"),
    ('{"a": -Infinity}', "non_finite_number"),
    ('{"a": 1, "a": 2}', "duplicate_key"),
    ('{"a": 1} {"b": 2}', "bad_json"),
    ('{"a": 1} x', "bad_json"),
    ("[1, 2]", "not_object"),
    ('"text"', "not_object"),
    ("{'a': 1}", "bad_json"),
    ('{"a": 1,}', "bad_json"),
    ('{"a": "\\ud800"}', "lone_surrogate"),
    ("", "bad_json"),
])
def test_rejects(text, code):
    with pytest.raises(FramingError) as info:
        decode_line(text.encode("utf-8"))
    assert info.value.code == code


def test_rejects_bad_utf8():
    with pytest.raises(FramingError) as info:
        decode_line(b'{"a": "\xff"}')
    assert info.value.code == "bad_utf8"


def test_encode_rejects_nan_and_non_objects():
    with pytest.raises(FramingError):
        encode_line({"a": float("nan")})
    with pytest.raises(FramingError):
        encode_line([1])


def test_line_buffer_reassembles_partial_reads():
    buf = LineBuffer()
    a = encode_line({"type": "ping", "seq": 1, "data": {"t": 5}})
    b = encode_line({"type": "ping", "seq": 2, "data": {"t": 6}})
    stream = a + b"\r\n" + b
    lines = []
    for i in range(len(stream)):
        lines += buf.feed(stream[i:i + 1])
    assert [json.loads(x)["seq"] for x in lines] == [1, 2]
    assert buf.pending == 0


def test_line_buffer_limits_size():
    buf = LineBuffer(max_bytes=16)
    with pytest.raises(FramingError) as info:
        buf.feed(b"x" * 20)
    assert info.value.code == "line_too_long"
    buf = LineBuffer(max_bytes=16)
    with pytest.raises(FramingError):
        buf.feed(b"y" * 17 + b"\n")


def test_encode_limits_size():
    with pytest.raises(FramingError) as info:
        encode_line({"data": "z" * 64}, max_bytes=32)
    assert info.value.code == "line_too_long"
