"""Source topology, whole-model seams, and distinct furniture supports."""
import copy
import numpy as np
import pytest
from tools.shape_fit.emerald_pass import seashore_house as p,review
from tools.shape_fit.blue_pass.common import decode


@pytest.fixture(scope='module')
def data():
    table=review.read(review.BASELINE);art=p.c.EmeraldArt(p.c.BUNDLE)
    patch,rows,metadata=p.generate(table,art)
    return table,art,patch,rows,metadata


def test_complete_source_ownership_retains_good_stands_and_only_adds_exact_pairs(data):
    table,art,patch,rows,metadata=data
    assert len(rows)==20 and len(patch)==18 and len(metadata)==6
    by={r['source_ordinal']:r['key'] for r in rows}
    for row in rows:
        key=row['key'];old=table['cells'][key];new=patch.get(key,old);adjusted=copy.deepcopy(new)
        if row['source_ordinal'] in p.PAIRS:adjusted.pop('on')
        review.validate_cell(key,old,adjusted)
        assert row['occurrences']==art.locations[key]
        if row['source_ordinal'] in p.RETAIN:assert key not in patch and row['decision']=='retained'
    for upper,lower in p.PAIRS.items():assert patch[by[upper]]['on']==by[lower]
    assert sum(len(m['occurrences']) for m in metadata)==10
    assert all(o['collision']==o['below_collision']==7 for m in metadata for o in m['occurrences'])


def test_paired_wall_and_machine_geometry_reassembles_exactly(data):
    _,_,patch,rows,_=data;by={r['source_ordinal']:r['key'] for r in rows}
    for top,bottom,whole in [(1,5,p.wall('plain')),(2,6,p.wall('pink')),(17,19,p.wall('double')),(18,20,p.wall('yellow')),(3,7,p.cabinet()),(4,8,p.cabinet())]:
        occ=np.concatenate([decode(patch[by[bottom]])[0],decode(patch[by[top]])[0]])
        assert np.array_equal(occ,whole.occ)
        assert (occ[15]&occ[16]).any()
    wall=p.wall('pink').occ
    assert wall[15,12,9] and not wall[15,15,9]  # open float backed by intact wall
    machine=p.cabinet().occ
    assert not machine[0,8,8] and machine[0,5,2] and machine[2,8,8]


def test_table_run_has_only_end_legs_and_continuous_thin_top(data):
    _,art,patch,rows,_=data;by={r['source_ordinal']:r['key'] for r in rows}
    run=np.concatenate([decode(patch[by[n]])[0] for n in (11,9,12)],axis=2)
    assert run[8,1:15,:].all() and not run[:6,:,16:32].any()
    assert run[0].sum()==36 and not run[10:].any()
    assert not run[0,8,8] and run[0,3,2] and run[0,11,43]
    for n in (9,11,12,14,15):
        o,paint=decode(patch[by[n]]);assert (paint[o]//16<12).all()
        rgb=np.asarray(art.cell(by[n],'BAKED').convert('RGB'))[paint//16,paint%16][o].astype(int)
        assert not ((rgb[:,1]>rgb[:,0]+20)&(rgb[:,2]>rgb[:,0])).any()


def test_actual_complete_assemblies_preserve_truthful_before_placements(data):
    _,art,_,_,_=data;specs=p.assemblies(art);assert len(specs)==15
    assert len([s for s in specs if 'table_' in s['id']])==10
    paired=[s for s in specs if 'baseline_placements' in s];assert len(paired)==4
    for spec in paired:
        assert all(q['offset'][1]==0 for q in spec['baseline_placements'])
        assert all(q['offset'][2]==1 for q in spec['placements'])
    tea=next(s for s in specs if s['id'].endswith('tea_service'))
    assert tea['model_layout'][0][0] is None and len(tea['placements'])==3
