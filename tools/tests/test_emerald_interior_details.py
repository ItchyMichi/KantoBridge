import base64

import numpy as np
import pytest

from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import interior_details as details
from tools.shape_fit.emerald_pass.review import validate_cell


@pytest.fixture(scope='module')
def authored():
    if not details.BASELINE.exists() or not details.BUNDLE.exists():
        pytest.skip('local Emerald comparison inputs not installed')
    table, art = details.read(details.BASELINE), EmeraldArt(details.BUNDLE)
    return table, art, details.read(details.REGISTRY), details.generate(table, art)


def test_printed_faces_remain_continuous_across_raised_panels_and_curtains(authored):
    table, _, registry, updates = authored
    for key, entry in updates.items():
        validate_cell(key, table['cells'][key], entry)
        if registry[key]['role'] not in ('panel', 'curtain'):
            continue
        occ, paint = decode(entry)
        y, z, x = np.indices(occ.shape)
        exposed = occ & ~np.concatenate((occ[:, 1:], np.zeros_like(occ[:, :1])), axis=1)
        assert np.array_equal(paint[exposed], ((15-y)*16+x)[exposed]), key


def test_new_stacks_have_exact_solid_source_neighbors(authored):
    _, art, registry, _ = authored
    witnessed = 0
    for key, spec in registry.items():
        if not spec.get('added_on'):
            continue
        for place in art.locations[key]:
            scene = art.maps[place['map']]
            x, y = place['x'], place['y']
            assert art.map_cells(scene)[y+1][x] == spec['added_on']
            assert base64.b64decode(scene['cells'])[((y+1)*scene['w']+x)*4+2] == 7
            witnessed += 1
    assert witnessed == 14


def test_whole_steps_and_extinguisher_are_connected(authored):
    _, art, registry, updates = authored
    specs = details.read(details.REGISTRY.with_name('interior_details_assemblies.json'))
    for spec in specs:
        layout = spec['source_layout']
        for place in spec['occurrences']:
            grid = art.map_cells(art.maps[place['map']])
            assert [grid[place['y']+z][place['x']:place['x']+len(row)] for z, row in enumerate(layout)] == layout
        if registry.get(layout[0][0], {}).get('role') == 'stairs':
            heights = np.concatenate([decode(updates[row[1]])[0].sum(axis=0)[:, 8] for row in layout])
            assert list(heights) == [12]*16+[8]*8+[4]*8
        if registry.get(layout[0][0], {}).get('role') == 'extinguisher_upper':
            upper, lower = [decode(updates[row[0]])[0] for row in layout]
            assert (upper[0, 5:12, 4:12] & lower[15, 5:12, 4:12]).any()


def test_judge_desk_joins_at_floor_seam_and_excludes_green_stage_ground(authored):
    _, art, _, updates = authored
    keys=['g3q/3810-3810-3816-3817','g3q/3821-3822-3810-3810']
    upper, lower = [decode(updates[k])[0] for k in keys]
    assert np.array_equal(upper[:,-1,:], lower[:,0,:])
    assert upper[8:10,8:16,1:15].all() and lower[8:10,0:8,1:15].all()
    assert not upper[:,0:8,:].any() and not lower[:,8:,:].any()
    assert not upper[2:8,11:16,4:12].any()
    for key in keys:
        assert updates[key]['walk'] is True and 'on' not in updates[key]
        occ,paint=decode(updates[key]);rgb=np.asarray(art.cell(key,'BAKED').convert('RGB')).reshape(256,3)
        used=rgb[paint[occ]].astype(int)
        assert not ((used[:,1]>used[:,0]+10)&(used[:,1]>used[:,2]+10)).any()
