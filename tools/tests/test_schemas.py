"""The protocol schemas are well formed, and every example validates."""
import json
import re

import pytest
from jsonschema import Draft202012Validator

from kb_protocol import Sender, load_protocol
from kb_protocol.catalog import expected_files, load_examples
from kb_protocol.paths import DOCS_DIR, PROTOCOL_DIR
from kb_protocol.registry import LEGS, RE_POLICIES

PROTOCOL = load_protocol()
EXAMPLES = load_examples(PROTOCOL)
ALL_EXAMPLES = [(t, e) for t in sorted(EXAMPLES) for e in EXAMPLES[t]]


def _schema_files():
    return [PROTOCOL_DIR / "envelope.schema.json", PROTOCOL_DIR / "defs.schema.json",
            *sorted((PROTOCOL_DIR / "messages").glob("*.schema.json"))]


@pytest.mark.parametrize("path", _schema_files(), ids=lambda p: p.name)
def test_schema_is_valid_2020_12(path):
    schema = json.loads(path.read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    assert schema["$schema"] == "https://json-schema.org/draft/2020-12/schema"
    assert schema["$id"].startswith("https://kantobridge.invalid/protocol/")


@pytest.mark.parametrize("msg_type", PROTOCOL.types)
def test_message_schema_metadata(msg_type):
    schema = PROTOCOL.messages[msg_type]
    assert schema["title"] == msg_type
    assert schema["$id"].endswith(f"/messages/{msg_type}.schema.json")
    assert re.fullmatch(r"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*", msg_type)
    legs = schema["x-legs"]
    assert legs and len(set(legs)) == len(legs) and set(legs) <= set(LEGS)
    assert schema["x-re"] in RE_POLICIES


def test_every_type_has_examples():
    assert sorted(EXAMPLES) == PROTOCOL.types
    for msg_type, envelopes in EXAMPLES.items():
        assert isinstance(envelopes, list) and envelopes, msg_type


@pytest.mark.parametrize("msg_type,envelope", ALL_EXAMPLES,
                         ids=[f"{t}#{e['seq']}" for t, e in ALL_EXAMPLES])
def test_example_validates_on_each_leg(msg_type, envelope):
    assert envelope["type"] == msg_type
    for leg in PROTOCOL.legs(msg_type):
        problems = PROTOCOL.check(envelope, leg=leg)
        assert not problems, [str(p) for p in problems]


def test_derived_files_are_current():
    stale = [path.name for path, text in expected_files(PROTOCOL).items()
             if not path.exists() or path.read_text(encoding="utf-8") != text]
    assert not stale, f"stale {stale}: run python -m kb_protocol.catalog --write from tools/"


# --- negative cases: the checker catches the mistakes the bridge halves could make ---------------

def _first(msg_type):
    return json.loads(json.dumps(EXAMPLES[msg_type][0]))


def _kinds(problems):
    return {(p.where, p.kind) for p in problems}


def test_rejects_unknown_type():
    assert _kinds(PROTOCOL.check({"type": "no.such", "seq": 1, "data": {}})) == {("/type", "unknown_type")}


def test_rejects_bad_envelopes():
    assert PROTOCOL.check({"type": "ping", "seq": 0, "data": {"t": 1}})
    assert PROTOCOL.check({"type": "ping", "seq": 1, "data": []})
    assert PROTOCOL.check({"type": "ping", "seq": 1})
    assert PROTOCOL.check({"type": "ping", "seq": 1, "data": {"t": 1}, "extra": 1})
    assert PROTOCOL.check({"type": "Ping", "seq": 1, "data": {"t": 1}})


def test_re_policy():
    pong = _first("pong")
    del pong["re"]
    assert ("/re", "re_required") in _kinds(PROTOCOL.check(pong))
    ping = _first("ping")
    ping["re"] = 3
    assert ("/re", "re_forbidden") in _kinds(PROTOCOL.check(ping))


def test_wrong_leg():
    assert ("/type", "wrong_leg") in _kinds(PROTOCOL.check(_first("player.pos"), leg="m2g"))
    assert not PROTOCOL.check(_first("battle.start"), leg="c2s")
    assert ("/type", "wrong_leg") in _kinds(PROTOCOL.check(_first("battle.start"), leg="s2c"))


def test_rejects_unknown_fields_and_bad_values():
    msg = _first("player.pos")
    msg["data"]["speed"] = 3
    assert ("/data", "additionalProperties") in _kinds(PROTOCOL.check(msg))
    msg = _first("player.pos")
    msg["data"]["facing"] = "north"
    assert ("/data/facing", "enum") in _kinds(PROTOCOL.check(msg))
    msg = _first("player.pos")
    msg["data"]["map"] = "pallet town"
    assert ("/data/map", "pattern") in _kinds(PROTOCOL.check(msg))


def test_conditional_requirements():
    warp = {"type": "warp", "seq": 1, "data": {"phase": "arrive", "kind": "door"}}
    assert ("/data", "required") in _kinds(PROTOCOL.check(warp))
    text = {"type": "ui.open", "seq": 1, "data": {"ui": 1, "kind": "text"}}
    assert ("/data", "required") in _kinds(PROTOCOL.check(text))
    trainer = {"type": "battle.start", "seq": 1,
               "data": {"battle": "b", "kind": "trainer", "rules": {"catch": False, "flee": False}}}
    assert PROTOCOL.check(trainer)
    caught = {"type": "battle.result", "seq": 1, "data": {"battle": "b", "outcome": "caught"}}
    assert PROTOCOL.check(caught)


def test_request_reply_shapes():
    assert not PROTOCOL.check({"type": "export.index", "seq": 1, "data": {}})
    assert PROTOCOL.check({"type": "export.index", "seq": 1, "data": {"game": "red"}})
    assert not PROTOCOL.check({"type": "debug.status", "seq": 1, "data": {}})


def test_problems_never_contain_values():
    secret = "Some game text that must never be printed"
    msg = {"type": "ui.open", "seq": 1, "data": {"ui": 1, "kind": "text", "pages": [[secret, 5]], "cont": []}}
    problems = PROTOCOL.check(msg)
    assert problems
    assert all(secret not in str(p) for p in problems)


def test_sender_numbers_from_one():
    sender = Sender()
    first = sender.make("ping", {"t": 1})
    second = sender.reply(first, "pong", {"t": 1})
    assert (first["seq"], second["seq"], second["re"]) == (1, 2, 1)
    assert not PROTOCOL.check(first) and not PROTOCOL.check(second)
    assert sender.make("resync")["data"] == {}


# --- PROTOCOL.md and the schemas describe the same messages -------------------------------------

def _doc_message_rows():
    """(type, dir cell, rom cell) for each row of PROTOCOL.md's "Type | Dir | Data | ROM" tables."""
    doc = (DOCS_DIR / "PROTOCOL.md").read_text(encoding="utf-8")
    rows, in_table = [], False
    for line in doc.splitlines():
        if not line.startswith("|"):
            in_table = False
            continue
        cells = [c.strip() for c in re.split(r"(?<!\\)\|", line.strip())[1:-1]]
        if cells == ["Type", "Dir", "Data", "ROM"]:
            in_table = True
            continue
        if in_table and not set(line) <= set("|-: "):
            match = re.fullmatch(r"`([a-z][a-z0-9_.]*)`", cells[0])
            assert match, f"bad message row: {line[:60]}"
            rows.append((match.group(1), cells[1], cells[-1]))
    return rows


def test_protocol_doc_lists_every_type():
    types = [row[0] for row in _doc_message_rows()]
    assert len(types) == len(set(types)), "a type is listed twice in PROTOCOL.md"
    assert sorted(types) == PROTOCOL.types, "PROTOCOL.md message tables and protocol/messages differ"


def test_protocol_doc_columns_match_schemas():
    catalog = json.loads((PROTOCOL_DIR / "catalog.json").read_text(encoding="utf-8"))
    arrows = {"G→M": "g2m", "M→G": "m2g", "C→S": "c2s", "S→C": "s2c"}
    for msg_type, dir_cell, rom_cell in _doc_message_rows():
        legs = {arrows[d.strip()] for d in dir_cell.split(",")}
        assert legs == set(PROTOCOL.legs(msg_type)), f"{msg_type}: Dir column vs x-legs"
        assert (rom_cell == "yes") == catalog["messages"][msg_type]["rom"], f"{msg_type}: ROM column"
