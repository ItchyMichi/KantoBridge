"""Structural checks for the three hand-authored Emerald building pilots."""
import importlib.util
import json
from pathlib import Path
import sys

import numpy as np
import pytest

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


trials = module("emerald_building_trials", "tools/shape_fit/emerald/building_trials.py")
parts = module("emerald_parts_model", "tools/shape_fit/emerald/parts_model.py")
SIZES = ((80, 80), (112, 80), (64, 64))


def volume(spec, width, depth):
    y, z, x = np.meshgrid(np.arange(spec["height"]), np.arange(depth), np.arange(width), indexing="ij")
    occupied = np.zeros(x.shape, dtype=bool)
    for part in spec["parts"]:
        inside = parts.inside(part, x, y, z)
        occupied = occupied & ~inside if part.get("carve") else occupied | inside
    return occupied


@pytest.mark.parametrize("name,size", list(zip(trials.IDS, SIZES)))
def test_building_remains_inside_original_art_grid_with_valid_source_addresses(name, size):
    spec = trials.generate()[name]
    width, depth = size
    occ = volume(spec, width, depth)
    assert occ.any() and occ[0].any()
    assert occ[-1].sum() == 0  # no roof clipped by the allocated height
    for part in spec["parts"]:
        x0, x1, z0, z1 = parts.bounds(part)
        assert 0 <= x0 < x1 <= width and 0 <= z0 < z1 <= depth
        assert 0 <= part["y"][0] < part["y"][1] <= spec["height"]
        for face, rectangle in part.get("paint", {}).items():
            if face == "wrap":
                continue
            u0, v0, u1, v1 = rectangle
            assert 0 <= u0 < u1 <= width and 0 <= v0 < v1 <= depth


def test_house_has_a_single_facade_and_an_open_door_recess():
    spec = trials.house()
    occ = volume(spec, 80, 80)
    assert occ[10, 72, 56] and not occ[10, 75, 56]
    assert occ[1, 77, 56]  # grounded threshold
    # The old upper-storey box occupied this air pocket below the rear eave.
    assert not occ[48, 62, 40]
    assert occ[43, 50, 40]  # the roof itself is one connected mass
    assert len([p for p in spec["parts"] if p["shape"] == "gable"]) == 1


def test_lab_dish_is_a_hollow_bowl_with_a_supported_receiver():
    occ = volume(trials.lab(), 112, 80)
    assert occ[54, 27, 40]  # dish floor
    assert not occ[56, 27, 40] and not occ[58, 27, 40]
    assert occ[57, 27, 45]  # outer rim remains
    assert occ[57, 27, 33] and occ[60, 27, 33]  # receiver stem/cap
    assert occ[10, 72, 76] and not occ[10, 75, 76]


def test_center_medallion_has_an_undistorted_square_uv_and_entry_is_recessed():
    spec = trials.center()
    occ = volume(spec, 64, 64)
    badge = next(p for p in spec["parts"] if p.get("paint", {}).get("front") == [24, 24, 40, 40])
    assert badge["x"][1] - badge["x"][0] == badge["y"][1] - badge["y"][0] == 16
    assert occ[42, 63, 30]  # badge stands forward of curved roof
    assert occ[10, 57, 24] and not occ[10, 60, 24]
    # The wall swatch must not contain the red P.C. sign at source x40..56.
    wall = spec["parts"][0]
    assert wall["paint"]["side"][2] <= 16


def test_pilots_and_declared_emerald_batch_are_the_only_reproducible_building_changes():
    from tools.shape_fit.emerald_pass.buildings import generate as batch_buildings
    from tools.shape_fit.emerald_pass.scenery import generate as batch_scenery
    table = json.loads(trials.TABLE.read_text(encoding="utf-8"))
    by_id = {entry["id"]: entry for entry in table["buildings"]}
    expected_buildings = {**trials.generate(), **batch_buildings()}
    for name, update in expected_buildings.items():
        assert by_id[name]["parts"] == update["parts"]
        assert by_id[name]["height"] == update["height"]
    baseline_path = ROOT / "local/model-gallery/buildings-baseline-emerald.json"
    if baseline_path.is_file():
        baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
        # The building pass still cannot mutate any cell. A separate scenery
        # author owns four declared keys; validate their exact generator output
        # while allowing a deliberate selective rollback to this baseline.
        expected_cells = batch_scenery(baseline)
        assert table["cells"].keys() == baseline["cells"].keys()
        for key, old in baseline["cells"].items():
            if table["cells"][key] != old:
                assert key in expected_cells, f"unowned cell mutation: {key}"
                assert table["cells"][key] == expected_cells[key]
        changed = []
        for old, new in zip(baseline["buildings"], table["buildings"], strict=True):
            if old != new:
                changed.append(new["id"])
                assert {k: v for k, v in old.items() if k not in ("height", "parts")} == {
                    k: v for k, v in new.items() if k not in ("height", "parts")}
        assert set(changed) == set(expected_buildings)


def test_center_wall_source_excludes_the_drawing_background_if_local_export_exists():
    bundle = ROOT / "local/minecraft/kantobridge/export/emerald"
    if not (bundle / "g3image").is_dir():
        pytest.skip("local exported art unavailable")
    table = json.loads(trials.TABLE.read_text(encoding="utf-8"))
    entry = next(b for b in table["buildings"] if b["id"] == trials.IDS[2])
    art = np.asarray(trials.drawing(entry, bundle)).astype(int)
    for rect in trials.center()["parts"][0]["paint"].values():
        x0, y0, x1, y1 = rect
        rgb = art[y0:y1, x0:x1]
        assert not np.any((rgb[:, :, 1] > rgb[:, :, 0] + 20) &
                          (rgb[:, :, 1] > rgb[:, :, 2] + 10))
