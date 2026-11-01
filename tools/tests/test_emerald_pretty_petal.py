"""Pretty Petal source collisions, complete furniture seams and honest additions."""
import copy
import numpy as np
import pytest
from tools.shape_fit.emerald_pass import pretty_petal as p,review
from tools.shape_fit.blue_pass.common import decode


@pytest.fixture(scope='module')
def data():
    table=review.read(review.BASELINE);art=p.c.EmeraldArt(p.c.BUNDLE)
    return table,art,*p.generate(table,art)


def test_complete_exact_source_ownership_and_only_witnessed_amendments(data):
    table,art,patch,rows,metadata,source=data
    assert len(rows)==len(patch)==29 and len(metadata)==13
    assert len([r for r in rows if r['decision']=='added'])==2
    for row in rows:
        key=row['key'];old=table['cells'].get(key,{'class':'voxels'});new=patch[key]
        cleaned={k:v for k,v in new.items() if k in review.GEOMETRY}
        cleaned.update({k:v for k,v in old.items() if k not in review.GEOMETRY})
        review.validate_cell(key,old,cleaned)
        assert row['occurrences']==art.locations[key]
        if row['decision']=='added':assert row['baseline_absent'] and row['baseline_entry_sha256'] is None and key not in table['cells']
    for s in source:
        assert {o['map'] for o in s['occurrences']}=={p.MAP,'EM_ROUTE104_PROTOTYPE_PRETTY_PETAL_FLOWER_SHOP'}
    by={s['position']:s['key'] for s in source}
    for top,bottom in p.PAIRS.items():assert patch[by[top]]['on']==by[bottom]
    for n in p.REMOVE_ON:assert 'on' not in patch[by[n]]
    assert {r['key'] for r in metadata if r['after'].get('counter')}=={by[n] for n in p.COUNTERS}
    assert all(o['collision']==144 for r in metadata if r['after'].get('counter') for o in r['occurrences'])


def test_whole_wall_bookcase_and_plant_tables_join_without_duplicate_sections(data):
    _,_,patch,rows,_,_=data;by={r['source_ordinal']:r['key'] for r in rows}
    for top,bottom in ((1,1001),(2,1002)):
        o=np.concatenate([decode(patch[by[bottom]])[0],decode(patch[by[top]])[0]])
        assert np.array_equal(o,p.wall().occ) and (o[15]&o[16]).any()
    book=np.concatenate([np.concatenate([decode(patch[by[11]])[0],decode(patch[by[12]])[0]],axis=2),np.concatenate([decode(patch[by[6]])[0],decode(patch[by[7]])[0]],axis=2)])
    assert np.array_equal(book,p.bookcase().occ)
    assert not book[0,10,16] and book[0,10,1] and (book[15]&book[16]).any()
    table=np.concatenate([np.concatenate([decode(patch[by[16]])[0],decode(patch[by[17]])[0]],axis=2),np.concatenate([decode(patch[by[21]])[0],decode(patch[by[22]])[0]],axis=2)],axis=1)
    assert np.array_equal(table,p.plant_table().occ)
    assert table[8].all() and not table[0,16,16] and table[0,1,1]


def test_walk_projections_and_counter_caps_keep_bounded_occupancy(data):
    _,_,patch,rows,_,_=data;by={r['source_ordinal']:r['key'] for r in rows}
    for n in p.FLOORS:
        o,_=decode(patch[by[n]]);assert o[0].all() and not o[1:].any() and patch[by[n]]['walk']
    for n in (3,4,10):
        o,_=decode(patch[by[n]]);assert len(o)==32 and o[0].any() and o[24].any()
        assert not o[:,:,:1].any() and not o[:,:2].any() and o[0].sum()<60
    for n in p.COUNTERS:
        o,_=decode(patch[by[n]]);assert len(o)==16 and not o[15:].any()
    for n in (23,25):
        o,_=decode(patch[by[n]]);assert o[0].sum()==16 and not o[0,8,8] and patch[by[n]]['seat']


def test_flower_runs_stay_floor_depth_and_all_source_wholes_are_registered(data):
    _,art,patch,rows,_,_=data;by={r['source_ordinal']:r['key'] for r in rows}
    middle,_=decode(patch[by[14]]);end,_=decode(patch[by[19]])
    assert middle[0].any() and end[0].any() and np.nonzero(middle)[0].max()==np.nonzero(end)[0].max()==14
    specs=p.assemblies(art);assert len(specs)==16
    for spec in specs:
        if 'crates' in spec['id']:assert all(q['offset'][1]==0 for q in spec['placements'])
        if 'picture' in spec['id'] or spec['id'].endswith('bookcase'):assert all(q['offset'][2]==1 for q in spec['placements'])
    counter=next(s for s in specs if s['id'].endswith('counter_and_stools'))
    assert counter['model_layout'][0][0] is None and counter['model_layout'][2][0] is None
