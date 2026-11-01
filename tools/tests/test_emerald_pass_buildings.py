"""The first Emerald building batch preserves footprints and real openings."""
import copy
import importlib.util
import json
from pathlib import Path
import sys

import numpy as np
import pytest

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from tools.shape_fit.emerald_pass import buildings
from tools.shape_fit.emerald import building_trials as pilots, parts_model


def volume(spec, width, depth):
    y, z, x = np.meshgrid(np.arange(spec["height"]), np.arange(depth), np.arange(width), indexing="ij")
    occupied = np.zeros(x.shape, dtype=bool)
    for part in spec["parts"]:
        inside = parts_model.inside(part, x, y, z)
        occupied = occupied & ~inside if part.get("carve") else occupied | inside
    return occupied


@pytest.mark.parametrize("name,spec", buildings.generate().items())
def test_footprint_and_paint_addresses_stay_in_the_source_drawing(name, spec):
    width = depth = 80 if name == buildings.HOUSE_ID else 64
    occ = volume(spec, width, depth)
    assert occ[0].any() and not occ[-1].any()
    for part in spec["parts"]:
        x0, x1, z0, z1 = parts_model.bounds(part)
        assert 0 <= x0 < x1 <= width and 0 <= z0 < z1 <= depth
        assert 0 <= part["y"][0] < part["y"][1] <= spec["height"]
        for face, rectangle in part.get("paint", {}).items():
            if face != "wrap":
                u0, v0, u1, v1 = rectangle
                assert 0 <= u0 < u1 <= width and 0 <= v0 < v1 <= depth


def test_mart_keeps_entry_recess_square_badge_and_lower_roof():
    spec = buildings.mart()
    occ = volume(spec, 64, 64)
    assert occ[10, 57, 24] and not occ[10, 60, 24]
    assert occ[1, 62, 24]
    badge = next(p for p in spec["parts"] if p.get("paint", {}).get("front") == [24, 24, 40, 40])
    assert badge["x"][1] - badge["x"][0] == badge["y"][1] - badge["y"][0] == 16
    assert occ[42, 63, 30]
    assert np.nonzero(occ)[0].max() < np.nonzero(volume(pilots.center(), 64, 64))[0].max()
    assert spec["parts"][0]["paint"]["side"][2] <= 16


def test_second_house_door_and_window_are_really_repositioned():
    spec = buildings.second_house()
    occ = volume(spec, 80, 80)
    assert occ[10, 72, 24] and not occ[10, 75, 24]
    assert occ[10, 75, 56]  # solid facade where the other house has its entrance
    assert occ[1, 77, 24]
    window = next(p for p in spec["parts"] if p.get("x") == [48, 64] and p.get("z") == [76, 77])
    assert window["paint"]["front"] == [48, 59, 64, 70]
    assert len([p for p in spec["parts"] if p["shape"] == "gable"]) == 1


def test_generating_candidates_never_mutates_accepted_pilots_or_aliases_models():
    before = copy.deepcopy(pilots.generate())
    candidates = buildings.generate()
    assert not set(candidates) & set(pilots.IDS)
    candidates[buildings.CENTER_IDS[0]]["parts"][0]["x"][0] = 999
    assert candidates[buildings.CENTER_IDS[1]]["parts"][0]["x"][0] == 4
    assert pilots.generate() == before


def test_local_sources_keep_background_and_sign_out_of_side_materials():
    if not buildings.BASELINE.is_file() or not (buildings.BUNDLE / "g3image").is_dir():
        pytest.skip("local authoring baseline and exported art unavailable")
    entries = {b["id"]: b for b in json.loads(buildings.BASELINE.read_text())["buildings"]}
    for name, spec in buildings.generate().items():
        drawing = np.asarray(pilots.drawing(entries[name], buildings.BUNDLE)).astype(int)
        side = spec["parts"][0]["paint"]["side"]
        x0, y0, x1, y1 = side
        pixels = drawing[y0:y1, x0:x1]
        assert not np.any((pixels[..., 1] > pixels[..., 0] + 20) & (pixels[..., 1] > pixels[..., 2] + 10)), name
        if name != buildings.HOUSE_ID:
            assert x1 < 40  # the separate P.C./MART sign starts at x40
