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
    assert golden["index"]["planner"] == "kb-shapes/11"
    assert golden["index"]["params"]["tierB"] is True
    assert golden["index"]["params"]["storyArt"] is True
    assert golden["index"]["params"]["tierC"] is True and golden["index"]["params"]["dressing"] is True
    kinds = {geom.split(":")[0] for geom in golden["index"]["geoms"]}
    assert {"cube", "slab", "tmpl", "tuft", "cell"} <= kinds
    assert golden["shapedBlocks"] > 0
    # V7: every template's signature is the hash of its canonical entry; its blocks are the index's tmpl keys.
    assert golden["index"]["templates"]
    for template in golden["index"]["templates"]:
        entry = {k: v for k, v in template.items() if k != "sig"}
        assert hashlib.sha256(canonical(entry).encode("utf-8")).hexdigest()[:16] == template["sig"]
        assert len(template["tiles"]) == len(template["columns"]) == template["w"] * template["d"]
        # P2: a band building's doorways and overhang rows hold keys on decor carriers besides its fallback columns.
        band = template.get("band", {})
        hull = template.get("hull", {})
        blocks = (sum(len(column) for column in template["columns"]) + sum(band.get("solid", [])) + sum(band.get("air", []))
                  + sum(hull.get("solid", [])))
        assert sum(1 for g in golden["index"]["geoms"] if g.startswith("tmpl:") and "|" + template["sig"] + "|" in g) == blocks


# ---- MG7 (G3E): the synthetic Gen 3 build ----
G3_GOLDEN = GAMETEST / "golden" / "synthetic_g3_world_index.json"
G3_SHAPES = GAMETEST / "golden" / "synthetic_g3_shapes.json"
G3_BUNDLE = GAMETEST / "synthetic_bundle" / "emerald"


def test_golden_gen3_world_index_matches_the_schema_and_the_bundle():
    index = json.loads(G3_GOLDEN.read_text(encoding="utf-8"))
    problems = PROTOCOL.check_world_index(index)
    assert not problems, [str(p) for p in problems]
    bundle_index = json.loads((G3_BUNDLE / "index.json").read_text(encoding="utf-8"))
    assert index["game"] == bundle_index["game"] == "emerald"
    assert index["exportHash"] == hashlib.sha256(canonical(bundle_index).encode("utf-8")).hexdigest()
    assert set(index["maps"]) == {e["id"] for e in bundle_index["resources"] if e["kind"] == "g3map"}
    assert {m["palette"] for m in index["maps"].values()} == {"BAKED"}, "Gen 3's art is baked: one palette"
    house = index["maps"]["EM_TEST_HOUSE"]
    assert (house["layer"], house["kind"], house["parent"]) == (1, "interior", "EM_TEST_TOWN")
    assert [h["pool"] for h in index["habitats"]] == ["kantobridge:em_test_route"]
    # W3 of the 3D heights plan: the cave off the route, its floors stacked in the 3D area, its ladder a real shaft
    caves = {k: m for k, m in index["maps"].items() if k.startswith("EM_TEST_CAVE")}
    # W4: B1F's platform stands 4 blocks over its floor, its ceiling 4 over that (W5), so B1F sits 14 under 1F (not 11),
    # its levels, ramps and ceilings written
    assert {k: (m["y"], m["biome"]) for k, m in caves.items()} == {
        "EM_TEST_CAVE_1F": (64, "kantobridge:cave_3d"), "EM_TEST_CAVE_B1F": (50, "kantobridge:cave_3d")}
    assert len(caves["EM_TEST_CAVE_B1F"]["ramps"]) == 2 and max(caves["EM_TEST_CAVE_B1F"]["levels"][0::2]) == 4  # (level, count) runs
    assert [(s["kind"], s["top"]["map"], s["bottom"]["map"], s["up"]) for s in index["shafts"]] == [
        ("ladder", "EM_TEST_CAVE_1F", "EM_TEST_CAVE_B1F", True)]


def test_golden_gen3_shape_index_matches_the_schema():
    golden = json.loads(G3_SHAPES.read_text(encoding="utf-8"))
    problems = PROTOCOL.check_shape_index(golden["index"])
    assert not problems, [str(p) for p in problems]
    assert hashlib.sha256(canonical(golden["index"]).encode("utf-8")).hexdigest() == golden["indexHash"]
    assert golden["index"]["atlases"] == ["g3q"] and golden["index"]["palettes"] == ["BAKED"]


# ---- MG9 (G2E): the synthetic Gen 2 build ----
G2_GOLDEN = GAMETEST / "golden" / "synthetic_g2_world_index.json"
G2_SHAPES = GAMETEST / "golden" / "synthetic_g2_shapes.json"
G2_BUNDLE = GAMETEST / "synthetic_bundle" / "crystal"


def test_golden_gen2_world_index_matches_the_schema_and_the_bundle():
    index = json.loads(G2_GOLDEN.read_text(encoding="utf-8"))
    problems = PROTOCOL.check_world_index(index)
    assert not problems, [str(p) for p in problems]
    bundle_index = json.loads((G2_BUNDLE / "index.json").read_text(encoding="utf-8"))
    assert index["game"] == bundle_index["game"] == "crystal"
    assert index["exportHash"] == hashlib.sha256(canonical(bundle_index).encode("utf-8")).hexdigest()
    assert set(index["maps"]) == {e["id"] for e in bundle_index["resources"] if e["kind"] == "g2map"}
    assert {m["palette"] for m in index["maps"].values()} == {"BAKED"}, "Gen 2's art is baked as Gen 3's: one palette"
    surface = {k for k, m in index["maps"].items() if m["kind"] == "surface"}
    assert surface == {"G2_TEST_TOWN", "G2_TEST_ROUTE", "G2_TEST_KANTO_TOWN"}, "both areas on the surface"
    cave = index["maps"]["G2_TEST_CAVE"]
    assert (cave["layer"], cave["parent"], cave["dark"]) == (1, "G2_TEST_ROUTE", True)
    # the cave is in the 3D area (HEIGHTS_ALL_GAMES_PLAN D1): the area's biome, one room (its whole map: no pieces key), its
    # habitats reaching 6; W3: its ladder down to B1F a real shaft
    assert (cave["biome"], cave["y"]) == ("kantobridge:cave_3d", 64) and len(cave.get("pieces", [])) <= 1
    assert index["maps"]["G2_TEST_CAVE_B1F"]["y"] == 64 - 11
    assert [(s["kind"], s["top"]["map"], s["bottom"]["map"], s["up"]) for s in index["shafts"]] == [
        ("ladder", "G2_TEST_CAVE", "G2_TEST_CAVE_B1F", True)]
    assert [h["pool"] for h in index["habitats"]] == ["kantobridge:g2_test_cave"] * 2 + ["kantobridge:g2_test_route"]
    assert [h.get("range") for h in index["habitats"]] == [6, 6, None]
    swaps = {c["swap"]["kind"] for c in index["storyCells"] if c["type"] == "tile"}
    assert swaps == {"cut", "whirlpool", "script"}


def test_golden_gen2_shape_index_matches_the_schema():
    golden = json.loads(G2_SHAPES.read_text(encoding="utf-8"))
    problems = PROTOCOL.check_shape_index(golden["index"])
    assert not problems, [str(p) for p in problems]
    assert hashlib.sha256(canonical(golden["index"]).encode("utf-8")).hexdigest() == golden["indexHash"]
    assert golden["index"]["atlases"] == ["g3q"] and golden["index"]["palettes"] == ["BAKED"]
