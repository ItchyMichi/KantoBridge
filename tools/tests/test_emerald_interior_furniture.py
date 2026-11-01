import numpy as np
import pytest

from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import interior_furniture as furniture
from tools.shape_fit.emerald_pass.review import validate_cell


@pytest.fixture(scope='module')
def authored():
    if not furniture.BASELINE.exists() or not furniture.BUNDLE.exists():
        pytest.skip('local Emerald comparison inputs not installed')
    table, art = furniture.read(furniture.BASELINE), EmeraldArt(furniture.BUNDLE)
    return table, art, furniture.read(furniture.REGISTRY), furniture.generate(table, art)


def test_furniture_keeps_flags_and_grounded_supports(authored):
    table, _, registry, updates = authored
    for key, entry in updates.items():
        validate_cell(key, table['cells'][key], entry)
        occ, _ = decode(entry)
        assert occ.any()
        if registry[key]['role'] in ('armchair', 'bench', 'sofa'):
            assert occ[7].any() and not occ[:5].all(), 'seats need air below cushions'
        if registry[key]['role'] == 'floor':
            assert np.array_equal(occ, decode(table['cells'][key])[0])
    removed = {k for k, v in registry.items() if v.get('remove_on')}
    assert removed == {'g3q/3773-3774-3736-3775', 'g3q/3738-3776-3777-3741'}


def test_three_tread_stairs_and_whole_layouts_match_source(authored):
    _, art, registry, updates = authored
    for assembly in furniture.read(furniture.REGISTRY.with_name('interior_furniture_assemblies.json')):
        layout = assembly['entity']['layout']
        for place in assembly['occurrences']:
            grid = art.map_cells(art.maps[place['map']])
            assert [grid[place['y']+z][place['x']:place['x']+len(row)] for z, row in enumerate(layout)] == layout
        if registry[layout[0][0]]['role'] == 'stairs':
            heights = np.concatenate([decode(updates[row[1]])[0].sum(axis=0)[:, 8] for row in layout])
            assert list(heights) == [12]*16 + [8]*8 + [4]*8
        if registry[layout[0][0]]['role'] == 'armchair':
            assert not assembly['entity']['kind'].startswith('stack')
