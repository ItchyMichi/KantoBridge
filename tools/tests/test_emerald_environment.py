import copy

import numpy as np
import pytest

from tools.model_gallery import EmeraldArt, catalog
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import environment as e


@pytest.fixture(scope='module')
def proposal():
    if not e.BASELINE.exists() or not e.BUNDLE.exists():
        pytest.skip('local accepted baseline and authorized export required')
    table=e.read(e.BASELINE);art=EmeraldArt(e.BUNDLE);rows=e.selections()
    updates=e.generate(table,art,rows);definitions,placements=e.assemblies_for(table,art,rows)
    return table,art,rows,updates,definitions,placements


def test_proposal_is_exactly_owned_and_preserves_interaction_metadata(proposal):
    table,_,rows,updates,_,_=proposal
    assert len(rows)==547 and len(updates)==273
    owned={r['key'] for r in rows}
    operations={r['key']:r['operation'] for r in rows}
    assert set(updates)<=owned
    for key,new in updates.items():
        old=table['cells'][key]
        expected={k:v for k,v in old.items() if k not in {'v','t','up','from'}}
        if key in e.CORRECTED_ON:expected.pop('on')
        if key in e.WALK_STEPS_KEYS:expected['walk']=True
        assert {k:v for k,v in new.items() if k not in {'v','t','up','from'}}==expected
        occ,_=decode(new)
        assert occ.any()
        if operations[key] not in {'flag','submarine'}:assert occ[0].any()
        assert all(layer.any() for layer in np.split(occ,len(occ)//16))


def test_horizontal_paint_keeps_the_original_vertical_faces(proposal):
    table,_,rows,updates,_,_=proposal
    for row in rows:
        if row['operation'] not in {'floor','bridge'}:continue
        original=table['cells'][row['key']];o,p=decode(original);new_o,new_p=decode(updates[row['key']])
        assert np.array_equal(o,new_o)
        above=np.roll(o,-1,axis=0);above[-1]=False
        assert np.array_equal(p[o&above],new_p[o&above])
        _,z,x=np.indices(o.shape)
        assert np.array_equal(new_p[o&~above],(z*16+x)[o&~above])


def test_every_proposed_assembly_has_real_source_placements(proposal):
    _,art,_,_,definitions,placements=proposal
    assert len(definitions)==46
    assert not set(e.RETIRED_WHOLE_INTERPRETATIONS)&{d['id'] for d in definitions}
    for item in placements:
        assert item['occurrences']
        for place in item['occurrences']:
            grid=art.map_cells(art.maps[place['map']]);x,y=place['x'],place['y']
            assert all(key is None or grid[y+dy][x+dx]==key
                       for dy,line in enumerate(item['layout']) for dx,key in enumerate(line))


def test_corrected_adjacent_objects_keep_the_same_before_comparison_id(proposal):
    table,art,_,updates,definitions,_=proposal
    corrections=[d for d in definitions if d.get('replaces_stack')]
    assert len(corrections)==2
    keys={k for d in corrections for row in d['layout'] for k in row}
    before={'game':'emerald','cells':{k:table['cells'][k] for k in keys},'buildings':[]}
    current=copy.deepcopy(before);current['cells'].update({k:updates[k] for k in keys})
    old={d['id']:d for d in catalog(before,corrections,art)}
    new={d['id']:d for d in catalog(current,corrections,art)}
    for d in corrections:
        assert old[d['id']]['placement_mode']=='stack'
        assert new[d['id']]['placement_mode']=='floor'


def test_portal_is_open_and_submarine_is_joined(proposal):
    _,art,rows,updates,_,_=proposal
    for row in rows:
        if row['operation']=='arch':
            o,_=decode(updates[row['key']]);assert not o[:10,:,5:11].any()
            assert o[12:16].all()
        elif row['operation']=='threshold':
            o,_=decode(updates[row['key']]);assert o[0].all() and not o[1:].any()
    m=e.submarine()
    assert all(np.any(m.occ[:,:,x-1]&m.occ[:,:,x]) for x in (16,32,48))
    assert m.occ[0].any()
    # Native quartz/gray colors remain available in every shoreline quarter;
    # no water-blue paint is used above its one-voxel water/shore surface.
    for row in rows:
        if row['operation']=='sea':
            o,p=decode(updates[row['key']]);a=np.asarray(art.cell(row['key'],'BAKED').convert('RGB'))
            colors={tuple(c) for c in a[p[1:][o[1:]]//16,p[1:][o[1:]]%16]}
            assert colors <= {(99,99,123),(132,132,140),(222,230,239)}


def test_changed_source_or_baseline_requires_new_inspection(proposal):
    table,art,rows,_,_,_=proposal
    stale=[dict(rows[0])];stale[0]['sprite_sha256']='0'*64
    with pytest.raises(ValueError,match='source changed'):
        e.generate(table,art,stale)
