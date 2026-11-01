import numpy as np
import pytest

from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import vegetation as v
from tools.shape_fit.emerald_pass.review import read, validate_cell


@pytest.fixture(scope="module")
def sources():
    if not v.BASELINE.exists() or not v.BUNDLE.exists():
        pytest.skip("user's local baseline/export required")
    table, art = read(v.BASELINE), EmeraldArt(v.BUNDLE)
    return table, art, v.generate(table, art)[0]


def test_source_materials_and_interaction_metadata(sources):
    table, art, updates = sources
    assert len(updates) == 34
    for key, entry in updates.items():
        old=table['cells'][key]
        validate_cell(key,old,entry)
        assert entry.get('on')==old.get('on')
        o,p=decode(entry);a=np.asarray(art.cell(key,'BAKED').convert('RGB'))
        assert o.any()
        assert all(layer.any() for layer in np.split(o,len(o)//16))
        colors={tuple(c) for c in a[p[o]//16,p[o]%16]}
        if key not in v.PLANTERS and key not in (v.FLOWER,v.GRASS):
            assert colors <= set(v.LEAVES) | set(v.BARK)
    empty_key='g3q/12112-12121-12129-12136'
    assert not decode(table['cells'][empty_key])[0].any()
    assert decode(updates[empty_key])[0].sum() > 200


def test_broadleaf_extensions_keep_approved_geometry_and_joins(sources):
    table, art, updates=sources
    original=v.broadleaf()
    for key,mid in v.BROADLEAF.items():
        assert np.array_equal(decode(updates[key])[0],decode(original[v.KEYS[mid]])[0])
    entities,placements=v.entities_for(art,updates)
    assert set(v.BROADLEAF) <= {k for e in entities for row in e['layout'] for k in row}
    assert all(p['occurrences'] for p in placements)
    for p in placements:
        for occurrence in p['occurrences']:
            g=art.map_cells(art.maps[occurrence['map']]);x,y=occurrence['x'],occurrence['y']
            assert [row[x:x+2] for row in g[y:y+2]] == p['layout']


def test_pointed_tree_connected_and_grounded():
    occ,leaf,stem=v.pointed_tree()
    assert 15 < occ[0].sum() < 40
    assert not leaf[:7].any()
    # All four slices join across both source-cell seams.
    assert np.any(occ[:,:,15] & occ[:,:,16])
    assert np.any(occ[:,15,:] & occ[:,16,:])
    visited=set();pending=[tuple(np.argwhere(occ)[0])]
    while pending:
        p=pending.pop()
        if p in visited:continue
        visited.add(p)
        for axis in range(3):
            for delta in [-1,1]:
                q=list(p);q[axis]+=delta;q=tuple(q)
                if all(0<=q[i]<occ.shape[i] for i in range(3)) and occ[q] and q not in visited:pending.append(q)
    assert len(visited)==occ.sum()


def test_planter_rim_soil_and_low_paving_are_distinct(sources):
    _,art,_=sources
    m=v.planter(art.cell(v.PLANTERS[0],'BAKED'),0)
    assert m.occ[0:4,1:7,1:15].all()
    assert m.occ[4,0,:].all() # raised outer rim
    assert m.occ[4,1:7,1:15].all() # soil bed inside rim
    assert not m.occ[3:,9:15,1:15].any() # adjoining paving stays low
