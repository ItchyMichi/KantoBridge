"""Semantic geometry and provenance guards for the west building inventory."""
import copy
import json
from pathlib import Path
import sys
from collections import deque

import numpy as np
import pytest

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from tools.shape_fit.emerald_pass import buildings_west as west
from tools.shape_fit.emerald import parts_model


@pytest.fixture(scope="module")
def table():
    if not west.BASELINE.exists():
        pytest.skip("local immutable authoring baseline is required")
    return json.loads(west.BASELINE.read_text())


def occupied(spec, width, depth):
    y, z, x = np.meshgrid(np.arange(spec["height"]), np.arange(depth), np.arange(width), indexing="ij")
    result = np.zeros(x.shape, bool)
    for part in spec["parts"]:
        mask = parts_model.inside(part, x, y, z)
        result = result & ~mask if part.get("carve") else result | mask
    return result


def test_complete_disjoint_inventory_and_no_input_mutation(table):
    original = copy.deepcopy(table)
    specs = west.specifications(table)
    assert len(west.NUMBERS) == 45
    assert set(specs).isdisjoint(west.RETAIN)
    assert set(specs) | set(west.RETAIN) == set(west.NUMBERS)
    assert len(specs) == 33 and len(west.RETAIN) == 12
    assert table == original
    assert not set(west.NUMBERS) & {0, 1, 2, 3, 4, 6, 9, 10, 27, 38, 44, 45, 48, 49}


def test_all_changed_geometry_and_uv_bounds(table):
    entries = {e["id"]: e for e in table["buildings"]}
    for name, spec in west.generate(table).items():
        drawing = west.p.drawing(entries[name], west.BUNDLE)
        width, depth = drawing.size
        for part in spec["parts"]:
            x0, x1, z0, z1 = parts_model.bounds(part)
            assert 0 <= x0 < x1 <= width, (name, part)
            assert 0 <= z0 < z1 <= depth, (name, part)
            assert 0 <= part["y"][0] < part["y"][1] <= spec["height"], (name, part)
            if "profile" in part:
                assert max(part["profile"]) <= part["y"][1] - part["y"][0]
            for face, rect in part.get("paint", {}).items():
                if face != "wrap":
                    u0, v0, u1, v1 = rect
                    assert 0 <= u0 < u1 <= width and 0 <= v0 < v1 <= depth, (name, part)


def test_authored_parts_do_not_cross_excluded_terrain_columns(table):
    entries={e['id']:e for e in table['buildings']}
    for name,spec in west.generate(table).items():
        entry=entries[name]
        if not entry.get('terrain'):continue
        width,depth=west.p.drawing(entry,west.BUNDLE).size
        occ=occupied(spec,width,depth)
        for x,z in entry['terrain']:
            assert not occ[:,z*16:(z+1)*16,x*16:(x+1)*16].any(),(name,x,z)


@pytest.mark.parametrize('width',(80,112))
def test_gate_canopy_connects_piers_without_filling_passage(width):
    spec=west.gateway(width);occ=occupied(spec,width,80)
    assert occ[46,35,width//2]
    assert not occ[20,35,width//2]
    assert occ[0,35,6] and occ[0,35,width-6]
    assert not occ[:,48:,:].any()


@pytest.mark.parametrize("number", west.TERRACES)
def test_terraces_have_real_roof_rim_and_only_actual_entries(number):
    occ = occupied(west.terrace(number), 48, 64)
    assert occ[37, 30, 1] and not occ[37, 30, 24]
    assert occ[35, 30, 24]
    if number in west.TERRACE_DOORS:
        assert occ[12, 57, 24] and not occ[12, 62, 24]
        assert occ[1, 63, 24]
    else:
        assert occ[12, 60, 24]
        assert not any(p.get("carve") for p in west.terrace(number)["parts"])


@pytest.mark.parametrize("number", west.HOUSES)
def test_house_entries_are_source_aligned_and_back_set(number):
    spec = west.house(number)
    width, door, windows, _ = west.HOUSES[number]
    occ = occupied(spec, width, 64)
    center = (door[0] + door[1]) // 2
    assert occ[10, 56, center] and not occ[10, 60, center]
    assert occ[1, 62, center]
    assert occ[51, 33, width // 2]
    assert len([p for p in spec["parts"] if p["shape"] == "gable"]) == 1
    assert not occ[-1].any()


def test_space_dish_is_concave_and_receiver_is_distinct(table):
    spec = west.specifications(table)[56]
    occ = occupied(spec, 144, 128)
    assert occ[107, 46, 112]  # basin floor
    assert not occ[114, 46, 112]  # open interior, away from receiver
    assert occ[115, 46, 124]  # high rim
    assert occ[118, 46, 106]  # separate receiver
    dish = occ[104:121, 19:73, 86:126]
    pending = {tuple(point) for point in np.argwhere(dish)}
    queue = deque([pending.pop()])
    while queue:
        point = queue.popleft()
        for axis in range(3):
            for direction in (-1, 1):
                neighbor = list(point)
                neighbor[axis] += direction
                neighbor = tuple(neighbor)
                if neighbor in pending:
                    pending.remove(neighbor)
                    queue.append(neighbor)
    assert not pending, "Dish rings must overlap vertically, not float above each other"


@pytest.mark.parametrize("number", west.GYMS)
def test_gym_door_uses_actual_source_center_and_sign_is_not_siding(number):
    spec = west.gym(number)
    occ = occupied(spec, 96, 80)
    assert occ[12, 74, 56] and not occ[12, 78, 56]
    assert occ[12, 77, 66]  # right jamb, not a wrongly shifted door opening
    assert spec["parts"][0]["paint"]["side"][2] < 82


def test_launch_gantries_have_air_bays_and_grounded_posts(table):
    spec = west.specifications(table)[57]
    occ = occupied(spec, 96, 96)
    assert occ[0, 80, 0] and occ[100, 80, 0]
    assert not occ[10, 88, 8]  # tower interior is air
    assert not occ[4, 94, 8]  # clear gap beside the diagonal braces
    assert occ[41, 88, 8]  # horizontal cross member
    assert occ[50, 87, 48]  # preserved rocket body
    assert not occ[-1].any()


def test_candidate_files_preserve_every_non_geometry_field(table):
    patch_path = west.OUT / "updates.json"
    ledger_path = west.OUT / "ledger.json"
    if not patch_path.exists():
        pytest.skip("candidate files have not been emitted")
    patch = json.loads(patch_path.read_text())
    ledger = json.loads(ledger_path.read_text())
    entries = {e["id"]: e for e in table["buildings"]}
    assert patch["cells"] == {}
    assert len(ledger) == 45
    for row in ledger:
        assert row["baseline_entry_sha256"] == west.digest(entries[row["key"]])
        assert row["reason"] and row["evidence"]
    for name, entry in patch["buildings"].items():
        assert {k: v for k, v in entry.items() if k not in ("height", "parts")} == {k: v for k, v in entries[name].items() if k not in ("height", "parts")}
