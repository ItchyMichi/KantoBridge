"""Whole-tower geometry and source identity guards for justified additions."""
import hashlib
import json
from pathlib import Path
import sys

import numpy as np
import pytest

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from tools.shape_fit.emerald import parts_model
from tools.shape_fit.emerald_pass import buildings_sootopolis as towers


def occupancy(spec):
    y, z, x = np.meshgrid(np.arange(spec['height']), np.arange(80), np.arange(48), indexing='ij')
    result = np.zeros(x.shape, bool)
    for part in spec['parts']:
        inside = parts_model.inside(part, x, y, z)
        result = result & ~inside if part.get('carve') else result | inside
    return result


@pytest.mark.parametrize('facade', ('door', 'pointed', 'diamond'))
def test_rounded_grounded_wall_continuous_cone_and_source_only_door(facade):
    occ = occupancy(towers.tower(facade))
    assert not occ[:, :32, :].any()
    assert occ[0, 56, 24] and occ[30, 56, 24] and occ[70, 56, 24]
    assert not occ[0, 34, 2]  # rounded corner, not a cube
    assert not occ[65, 56, 5]  # tapered roof, not an upright roof box
    if facade == 'door':
        assert occ[12, 70, 24] and not occ[12, 76, 24] and occ[1, 78, 24]
    else:
        assert occ[12, 76, 24]  # no invented door on a window annex


def test_exact_patterns_terrain_exclusions_and_no_background_roof_paint():
    if not towers.BUNDLE.exists() or not towers.BASELINE.exists():
        pytest.skip('Local Emerald source and immutable baseline required')
    from tools.model_gallery import EmeraldArt, building_catalog
    art = EmeraldArt(towers.BUNDLE); new = towers.generate(art)
    old = json.loads(towers.BASELINE.read_text())['buildings']
    assert len(new) == 10 and {int(i.split('_')[1][1:]) for i in new} == set(range(113, 123))
    assert all(e['tiles'] != b['tiles'] and e['id'] != b['id'] for e in new.values() for b in old)
    for entry, (_, _, _, facade, source_hash) in zip(new.values(), towers.PATTERNS):
        source = towers.p.drawing(entry, towers.BUNDLE)
        assert hashlib.sha256(source.tobytes()).hexdigest() == source_hash
        cream = entry['parts'][0]['paint']['front']
        assert source.getpixel(tuple(cream[:2])) == (222,222,181)
        occ = occupancy(entry)
        for x, z in entry['terrain']:
            assert not occ[:, z*16:(z+1)*16, x*16:(x+1)*16].any()
        for part in entry['parts']:
            x0, x1, z0, z1 = parts_model.bounds(part)
            assert 0 <= x0 < x1 <= 48 and 32 <= z0 < z1 <= 80
            for face, rect in part.get('paint', {}).items():
                if face == 'wrap':
                    continue
                u0,v0,u1,v1 = rect
                assert 0 <= u0 < u1 <= 48 and 0 <= v0 < v1 <= 80
                if part['y'][0] >= 32:
                    assert source.getpixel((u0,v0)) in ((82,90,82),(123,132,123),(66,74,107))
    catalog = building_catalog({'game':'emerald','cells':{},'buildings':list(new.values())}, art)
    assert sum(len(i['occurrences']) for i in catalog) == 11
    assert {i['id']:len(i['templates'][0]['doors']) for i in catalog} == {
        f'emerald_b{n:03}_{towers.MAP}':int(facade == 'door') for n, _, _, facade, _ in towers.PATTERNS}
