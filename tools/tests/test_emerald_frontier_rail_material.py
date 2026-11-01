"""The captured Dome defect is paint only; neither topology nor flags may move."""
import copy
import json
import numpy as np
import pytest
from tools.shape_fit.emerald_pass import frontier_rail_material as f


def test_only_exact_native_grass_addresses_change():
    if not f.BASELINE.exists():pytest.skip('Local immutable export required')
    table=json.loads(f.BASELINE.read_text());before=copy.deepcopy(table);art=f.EmeraldArt(f.BUNDLE)
    updates=f.generate(table,art);assert table==before;assert set(updates)==set(f.KEYS)
    for key,entry in updates.items():
        old=table['cells'][key];o,p=f.decode(old);a,b=f.decode(entry);assert np.array_equal(o,a)
        rgb=np.asarray(art.cell(key,'BAKED').convert('RGB'));colors=rgb[p//16,p%16]
        grass=o & np.any(np.all(colors[...,None,:]==np.asarray(f.GRASS),axis=-1),axis=-1)
        assert grass.sum()==dict(zip(f.KEYS,(30,30,34,71,34,67)))[key]
        assert np.array_equal(p[~grass],b[~grass]);assert np.all(rgb[b[grass]//16,b[grass]%16]==f.GRAY)
        meta=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
        assert meta(old)==meta(entry)


def test_actual_complete_fence_contains_both_source_posts():
    if not f.BUNDLE.exists():pytest.skip('Local immutable export required')
    art=f.EmeraldArt(f.BUNDLE);item=f.entity(art);grid=art.map_cells(art.maps[item['occurrences'][0]['map']])
    assert item['layout']==[[grid[y][25]] for y in range(9,18)]
    assert [row[0] for row in item['layout']].count(f.KEYS[0])==1
    assert [row[0] for row in item['layout']].count(f.KEYS[1])==1
    west=f.entity(art,True);assert west['layout']==[[grid[y][13]] for y in range(9,18)]
    for key in f.KEYS[2:5]:assert [row[0] for row in west['layout']].count(key)==1
