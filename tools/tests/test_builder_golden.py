"""fabric-bridge's builder output for the synthetic world matches the protocol schemas.

The golden WorldIndex is what the Java builder produces from the synthetic bundle (a GameTest asserts
the builder reproduces it byte for byte); here it is checked against the schema and the bundle.
"""
import hashlib
import json

from kb_protocol import load_protocol
from kb_protocol.jsonfmt import canonical
from kb_protocol.paths import REPO_ROOT

GAMETEST = REPO_ROOT / "fabric-bridge" / "src" / "gametest" / "resources" / "kantobridge"
GOLDEN = GAMETEST / "golden" / "synthetic_world_index.json"
SHAPES = GAMETEST / "golden" / "synthetic_shapes.json"
BUNDLE = GAMETEST / "synthetic_bundle" / "red"
PROTOCOL = load_protocol()


def test_golden_world_index_matches_the_schema():
    index = json.loads(GOLDEN.read_text(encoding="utf-8"))
    problems = PROTOCOL.check_world_index(index)
    assert not problems, [str(p) for p in problems]


def test_golden_world_index_matches_the_bundle():
    index = json.loads(GOLDEN.read_text(encoding="utf-8"))
    bundle_index = json.loads((BUNDLE / "index.json").read_text(encoding="utf-8"))
    assert index["game"] == bundle_index["game"]
    assert index["exportHash"] == hashlib.sha256(canonical(bundle_index).encode("utf-8")).hexdigest()
    assert set(index["maps"]) == {e["id"] for e in bundle_index["resources"] if e["kind"] == "map"}
    town = index["maps"]["TEST_TOWN"]
    assert (town["layer"], town["kind"], town["parent"]) == (0, "surface", None)
    layers = {name: m["layer"] for name, m in index["maps"].items()}
    assert layers["TEST_HOUSE"] == 1 and layers["TEST_HOUSE_2F"] == 2, "floors stack downwards"
    keys = [cell["key"] for cell in index["storyCells"]]
    assert len(keys) == len(set(keys)), "story cells are unique"


def test_slot_map_schema_accepts_a_minimal_map():
    slot_map = {"version": 1, "slots": [
        {"atlas": "tilesets.test", "palette": "TEST_PALETTE", "tile": 0, "shape": "full", "family": 0, "slot": 0},
        {"atlas": "tilesets.test", "palette": "TEST_PALETTE", "tile": 6, "shape": "half", "family": 0, "slot": 0},
    ]}
    assert not PROTOCOL.check_slot_map(slot_map)
    slot_map["slots"][0]["family"] = 16
    assert PROTOCOL.check_slot_map(slot_map)


def test_golden_shape_index_matches_the_schema():
    """The voxel plan (from V2): the synthetic build's shape index (checked byte for byte by a JUnit test) fits its schema,
    and its hash is the SHA-256 of its canonical text."""
    golden = json.loads(SHAPES.read_text(encoding="utf-8"))
    problems = PROTOCOL.check_shape_index(golden["index"])
    assert not problems, [str(p) for p in problems]
    assert hashlib.sha256(canonical(golden["index"]).encode("utf-8")).hexdigest() == golden["indexHash"]
    assert golden["index"]["planner"] == "kb-shapes/2"
    assert golden["shapedBlocks"] > 0
