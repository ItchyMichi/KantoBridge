"""Redaction removes every ROM-derived value and nothing else."""
import hashlib
import json
import re

import pytest

from kb_protocol import load_protocol, placeholder, redact_data, redact_message
from kb_protocol.catalog import build_catalog, load_examples
from kb_protocol.paths import PROTOCOL_DIR

PROTOCOL = load_protocol()
CATALOG = build_catalog(PROTOCOL)
EXAMPLES = load_examples(PROTOCOL)
PLACEHOLDER = r"<rom:(str|arr|obj|num|bool|null):\d+:[0-9a-f]{8}>"


def test_placeholder_format():
    # The hash covers the canonical JSON text, so a string's quotes are included.
    assert placeholder("abc") == "<rom:str:3:" + hashlib.sha256(b'"abc"').hexdigest()[:8] + ">"
    assert re.fullmatch(PLACEHOLDER, placeholder({"x": [1]}))
    assert placeholder("é♂").startswith("<rom:str:2:")
    assert placeholder([1, 2, 3]).startswith("<rom:arr:3:")
    assert placeholder({"a": 1}).startswith("<rom:obj:1:")
    assert placeholder(True).startswith("<rom:bool:0:")
    assert placeholder(None).startswith("<rom:null:0:")
    assert placeholder(7).startswith("<rom:num:0:")


def test_placeholder_hash_ignores_key_order():
    assert placeholder({"a": 1, "b": 2}) == placeholder({"b": 2, "a": 1})
    assert placeholder("a") != placeholder("b")


def test_vectors_match_python():
    vectors = json.loads((PROTOCOL_DIR / "redaction-vectors.json").read_text(encoding="utf-8"))
    for case in vectors["placeholders"]:
        assert placeholder(case["value"]) == case["placeholder"]


def test_wildcards_and_missing_paths():
    data = {"list": [{"name": "x", "id": 1}, {"id": 2}], "map": {"k1": {"name": "y"}}}
    out = redact_data(data, ["/list/*/name", "/map/*/name", "/absent", "/list/*/absent"])
    assert out["list"][0]["name"].startswith("<rom:str:1:")
    assert out["list"][0]["id"] == 1 and out["list"][1] == {"id": 2}
    assert out["map"]["k1"]["name"].startswith("<rom:str:1:")
    assert data["list"][0]["name"] == "x", "input must not be mutated"


def test_unknown_type_redacts_all_data():
    out = redact_message({"type": "no.such", "seq": 1, "data": {"text": "secret"}}, CATALOG)
    assert out["data"].startswith("<rom:obj:1:")


def _strings(value):
    """String values inside value (keys are schema field names, not content)."""
    if isinstance(value, str):
        yield value
    elif isinstance(value, list):
        for item in value:
            yield from _strings(item)
    elif isinstance(value, dict):
        for item in value.values():
            yield from _strings(item)


def _nodes_at(data, path):
    nodes = [data]
    for token in path[1:].split("/") if path else []:
        nxt = []
        for node in nodes:
            if token == "*" and isinstance(node, (list, dict)):
                nxt.extend(node if isinstance(node, list) else node.values())
            elif isinstance(node, dict) and token in node:
                nxt.append(node[token])
        nodes = nxt
    return nodes


@pytest.mark.parametrize("msg_type", sorted(EXAMPLES))
def test_examples_redact_cleanly(msg_type):
    paths = CATALOG["messages"][msg_type]["redact"]
    for envelope in EXAMPLES[msg_type]:
        redacted = redact_message(envelope, CATALOG)
        assert {k: v for k, v in redacted.items() if k != "data"} == \
            {k: v for k, v in envelope.items() if k != "data"}
        for path in paths:
            for node in _nodes_at(redacted["data"], path):
                assert isinstance(node, str) and re.fullmatch(PLACEHOLDER, node), f"{msg_type} {path}"
        # Free text (anything with a space) inside a ROM field must not survive anywhere.
        text = json.dumps(redacted, ensure_ascii=False)
        for path in paths:
            for value in _nodes_at(envelope["data"], path):
                for s in _strings(value):
                    if " " in s:
                        assert s not in text, f"{msg_type}: ROM-derived text survived redaction"
