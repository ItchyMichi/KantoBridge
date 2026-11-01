"""Exact-match, source footprint and structural guards for the missing house."""
import copy
import hashlib
import json
from collections import deque
from pathlib import Path
import sys

import numpy as np
import pytest

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from tools.shape_fit.emerald import parts_model
from tools.shape_fit.emerald_pass import building_route124 as house


def occupied(entry):
    y, z, x = np.meshgrid(np.arange(entry['height']), np.arange(64), np.arange(64), indexing='ij')
    result = np.zeros(x.shape, bool)
    for part in entry['parts']:
        mask = parts_model.inside(part, x, y, z)
        result = result & ~mask if part.get('carve') else result | mask
    return result


def test_roof_projection_row_is_unoccupied_before_runtime_clipping():
    entry = house.generate()[house.ID]
    occ = occupied(entry)
    assert entry['terrain'] == [[x, 0] for x in range(4)]
    assert not occ[:, :16, :].any()
    assert occ[0, 30, 20] and occ[40, 25, 32] and occ[50, 30, 32]
    # Source door local (1,3) has a back slab, open recess and threshold.
    assert occ[12, 55, 24] and not occ[12, 60, 24] and occ[1, 63, 24]
    assert occ[15, 61, 40]


def test_parts_and_native_uv_rectangles_are_inside_exact_source():
    entry = house.generate()[house.ID]
    for part in entry['parts']:
        x0, x1, z0, z1 = parts_model.bounds(part)
        assert 0 <= x0 < x1 <= 64 and 16 <= z0 < z1 <= 64
        assert 0 <= part['y'][0] < part['y'][1] <= entry['height']
        for rect in part.get('paint', {}).values():
            u0, v0, u1, v1 = rect
            assert 0 <= u0 < u1 <= 64 and 0 <= v0 < v1 <= 64


def test_all_geometry_is_one_grounded_component():
    occ = occupied(house.generate()[house.ID])
    start = tuple(np.argwhere(occ)[0]); seen = {start}; queue = deque([start])
    while queue:
        y, z, x = queue.popleft()
        for dy, dz, dx in ((1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)):
            q = (y+dy,z+dz,x+dx)
            if all(0 <= q[i] < occ.shape[i] for i in range(3)) and occ[q] and q not in seen:
                seen.add(q); queue.append(q)
    assert len(seen) == int(occ.sum())
    assert start[0] == 0


def test_addition_has_exact_new_source_and_original_map_warp():
    if not house.BASELINE.exists() or not house.BUNDLE.exists():
        pytest.skip('Local immutable baseline and Emerald export required')
    from tools import model_gallery
    table = json.loads(house.BASELINE.read_text()); original = copy.deepcopy(table)
    entry = house.generate()[house.ID]
    assert len(table['buildings']) == 112
    assert all(e['id'] != house.ID and e['tiles'] != entry['tiles'] for e in table['buildings'])
    source = house.p.drawing(entry, house.BUNDLE)
    assert hashlib.sha256(source.tobytes()).hexdigest() == house.SOURCE_SHA256
    evidence = house.source_evidence(model_gallery.EmeraldArt(house.BUNDLE), entry)
    assert [(e['map'],e['x'],e['y']) for e in evidence] == [('EM_ROUTE124',69,45)]
    assert evidence[0]['warps'] == [{'to':'EM_ROUTE124_DIVING_TREASURE_HUNTERS_HOUSE','warp':0,'x':70,'y':48}]
    assert [c['collision_byte'] for c in evidence[0]['cells'][:4]] == [0]*4
    assert evidence[0]['cells'][13]['collision_byte'] == 113
    assert table == original
