"""The fixed measuring viewpoints build_world.py writes (docs/specs/VOXEL_BLOCKS_PLAN.md V1a): made from the build
report's block coordinates alone, numbers and map ids only."""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "e2e"))

import build_world  # noqa: E402

REPORT = {
    "spawn_map": "TEST_TOWN",
    "viewpoints": {"spawn_x": 10, "spawn_feet_y": 64, "spawn_z": -20, "surface_min_x": -12, "surface_min_y": 63,
                   "surface_min_z": -40, "surface_max_x": 52, "surface_max_y": 71, "surface_max_z": 36},
}


def test_spawn_and_overview():
    out = build_world.viewpoints(REPORT, "KantoE2E")
    assert out["version"] == 1 and out["world"] == "KantoE2E"
    spawn, above = out["viewpoints"]
    assert spawn["id"] == "spawn" and spawn["map"] == "TEST_TOWN"
    assert (spawn["x"], spawn["y"], spawn["z"]) == (11.0, 64.0, -19.0), "the middle of the spawn cell, at its feet level"
    assert above["id"] == "above" and above["map"] is None
    assert (above["x"], above["z"]) == (20.0, -2.0), "the centre of the surface box"
    assert above["y"] == 71 + build_world.OVERVIEW_HEIGHT and above["pitch"] == 90.0
    assert spawn["command"] == "/tp @s 11 64 -19 180 10"


def test_missing_coordinates_give_fewer_points():
    assert build_world.viewpoints({"viewpoints": {}}, "W")["viewpoints"] == []
    assert build_world.viewpoints({}, "W")["viewpoints"] == []


def test_written_file_is_plain_json(tmp_path):
    path = build_world.write_viewpoints(REPORT, "KantoE2E", tmp_path / "reports" / "viewpoints.json")
    data = json.loads(path.read_text(encoding="utf-8"))
    for point in data["viewpoints"]:
        for key, value in point.items():
            assert isinstance(value, (int, float, str)) or value is None, key
