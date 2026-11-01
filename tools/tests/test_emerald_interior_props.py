import numpy as np
import pytest

from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import interior_props as props
from tools.shape_fit.emerald_pass.review import validate_cell


def inputs():
    if not props.BASELINE.exists() or not props.BUNDLE.exists():
        pytest.skip('local Emerald comparison inputs not installed')
    return props.read(props.BASELINE), EmeraldArt(props.BUNDLE)


def test_supported_props_exclude_floor_pixels_from_leaf_and_metal_materials():
    table, art = inputs()
    registry = props.read(props.REGISTRY)
    for key, entry in props.generate(table, art).items():
        validate_cell(key, table['cells'][key], entry)
        occ, paint = decode(entry)
        assert occ[0].any(), key
        source = np.asarray(art.cell(key, 'BAKED').convert('RGB')).astype(int)
        colors = source[paint // 16, paint % 16]
        role = registry[key]['role']
        if role in ('plant_crown', 'pedestal_crown'):
            r, g, b = colors[occ].T
            assert np.all((g > r + 5) & (g > b + 5)), key
        if role == 'stool':
            r, g, b = colors[:7][occ[:7]].T
            assert np.all((abs(r-g) < 20) & (b >= r-15)), key


def test_whole_prop_exhibits_use_vertical_placement_and_real_adjacent_cells():
    _, art = inputs()
    entities, placements = props.assemblies(art, props.read(props.REGISTRY))
    assert len(entities) == len(placements) == 8
    for entity, placement in zip(entities, placements):
        assert entity['kind'].startswith('stack')
        assert placement['occurrences']
        for location in placement['occurrences']:
            grid = art.map_cells(art.maps[location['map']])
            x, y = location['x'], location['y']
            assert entity['layout'] == [[grid[y][x]], [grid[y+1][x]]]
