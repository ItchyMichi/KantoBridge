"""The texture-pack manifest schema (protocol/packs/kb_pack.schema.json, docs/PACKS.md) is well formed, its synthetic
example validates, and it refuses what the client's parser (KbPackManifest) drops. The Java side checks that the same
example parses without a warning (KbPackManifestTest)."""
import copy
import json

import pytest
from jsonschema import Draft202012Validator

from kb_protocol.paths import PROTOCOL_DIR

SCHEMA = json.loads((PROTOCOL_DIR / "packs" / "kb_pack.schema.json").read_text(encoding="utf-8"))
EXAMPLE = json.loads((PROTOCOL_DIR / "packs" / "kb_pack.example.json").read_text(encoding="utf-8"))
VALIDATOR = Draft202012Validator(SCHEMA)


def test_schema_is_valid_2020_12():
    Draft202012Validator.check_schema(SCHEMA)
    assert SCHEMA["$schema"] == "https://json-schema.org/draft/2020-12/schema"
    assert SCHEMA["$id"] == "https://kantobridge.invalid/protocol/packs/kb_pack.schema.json"


def test_example_validates():
    assert not [e.message for e in VALIDATOR.iter_errors(EXAMPLE)]


def _with(path, value):
    manifest = copy.deepcopy(EXAMPLE)
    node = manifest
    for part in path[:-1]:
        node = node[part]
    if value is None:
        del node[path[-1]]
    else:
        node[path[-1]] = value
    return manifest


@pytest.mark.parametrize("path,value", [
    (["kantobridge_pack"], 2),                                         # pack.format
    (["id"], "Has Capitals"),                                          # pack.format
    (["targets", "games"], []),                                        # pack.format
    (["targets", "geometry"], 2),                                      # pack.format
    (["textures", "tile:tilesets.test/04"], "hd_test:block/x"),         # pack.unknown_key: not canonical
    (["textures", "tile:tilesets.test/1024"], "hd_test:block/x"),       # pack.unknown_key: tile out of range
    (["textures", "cell:tilesets.test/1-2-3-4"], "hd_test:block/x"),    # pack.unknown_key: no texture for a cell
    (["textures", "tile:tilesets.test/4"], "hd_test:textures/block/roof.png"),  # pack.texture_missing: a file, not a sprite
    (["textures", "sprite:SPRITE_TEST_GIRL"], "hd_test:npc/girl"),      # pack.texture_missing: not a texture file
    (["models", "cell:tilesets.test/2-2-2-2", "voxelsPerBlock"], 12),   # pack.vox_format
    (["models", "cell:tilesets.test/2-2-2-2", "turn"], 45),             # pack.vox_format
    (["models", "cell:tilesets.test/2-2-2-2", "vox"], "hd_test:kb_models/tree.obj"),  # pack.vox_format
    (["models", "map:TEST_TOWN"], {"vox": "hd_test:kb_models/x.vox"}),  # pack.unknown_key: no model for a map
    (["rules", "class:tree", "style"], "round"),                        # pack.rule
    (["rules", "class:prop", "depth"], 9),                              # pack.rule
    (["rules", "class:wall"], {"style": "hull"}),                       # pack.rule: no rule for walls
    (["rules", "class:flower", "style"], "hull"),                       # pack.rule
    (["rules", "tile:tilesets.test/5", "class"], "castle"),             # pack.rule
    (["rules", "struct:tilesets.test/0123456789abcdef", "bands", "roofRows"], 7),  # pack.rule
    (["characters", "style"], "model"),                                 # pack.rule: model is per sprite
    (["dressing", "map:TEST_HOUSE", "headroom"], 1),                    # pack.rule
])
def test_refuses_what_the_parser_drops(path, value):
    assert list(VALIDATOR.iter_errors(_with(path, value)))


def test_a_class_rule_needs_a_style_or_a_depth():
    assert list(VALIDATOR.iter_errors(_with(["rules", "class:prop"], {"depth": 0})))
    assert not list(VALIDATOR.iter_errors(_with(["rules", "class:prop"], {"style": "object", "depth": 0})))


def test_the_example_is_synthetic():
    # Synthetic ids only (tools/fake_gen1/synth.py): nothing in it comes from a ROM.
    text = json.dumps(EXAMPLE)
    assert "tilesets.test" in text and "TEST_" in text
