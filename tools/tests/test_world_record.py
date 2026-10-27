"""WORLD_SESSIONS_PLAN WS2: the world record's schema (protocol/world/world.schema.json) accepts what the creation flow
writes and refuses what WorldRecord refuses (the start among the games' regions is WorldRecord's own check)."""

import json
from pathlib import Path

import jsonschema
import pytest

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / "protocol" / "world" / "world.schema.json").read_text(encoding="utf-8"))
DEFS = json.loads((ROOT / "protocol" / "defs.schema.json").read_text(encoding="utf-8"))


def validate(doc):
    resolver = jsonschema.RefResolver.from_schema(SCHEMA, store={DEFS["$id"]: DEFS, SCHEMA["$id"]: SCHEMA})
    jsonschema.Draft202012Validator(SCHEMA, resolver=resolver).validate(doc)


def test_a_kanto_and_johto_world_starting_in_johto():
    validate({"version": 1, "start": "johto", "games": [{"game": "blue", "region": "kanto"}, {"game": "crystal", "region": "johto"}]})


@pytest.mark.parametrize("doc", [
    {"version": 2, "start": "kanto", "games": [{"game": "blue", "region": "kanto"}]},
    {"version": 1, "start": "kanto", "games": []},
    {"version": 1, "start": "kanto", "games": [{"game": "blue"}]},
    {"version": 1, "start": "Kanto", "games": [{"game": "blue", "region": "kanto"}]},
    {"version": 1, "start": "kanto", "games": [{"game": "blue", "region": "kanto", "slot": 0}]},
    {"version": 1, "games": [{"game": "blue", "region": "kanto"}]},
])
def test_what_the_schema_refuses(doc):
    with pytest.raises(jsonschema.ValidationError):
        validate(doc)
