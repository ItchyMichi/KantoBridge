"""Mauville's actual furniture joins, projected WALK hoods and narrow metadata."""
import copy
import numpy as np
import pytest
from tools.shape_fit.emerald_pass import mauville_game_corner as p,review
from tools.shape_fit.blue_pass.common import decode


@pytest.fixture(scope='module')
def data():
    baseline=review.read(review.BASELINE);art=p.c.EmeraldArt(p.c.BUNDLE)
    return baseline,art,p.generate(baseline,art)


def test_exact_32_cells_and_only_source_proven_alias_and_counters(data):
    baseline,art,(patch,rows,metadata,additions,added,projection)=data
    assert len(patch)==len(rows)==32 and len(metadata)==5 and additions=={} and added==[]
    by={r['source_ordinal']:r['key'] for r in rows}
    for row in rows:
        key=row['key'];old=baseline['cells'][key];new=copy.deepcopy(patch[key]);new.pop('alsoOn',None);new.pop('counter',None)
        review.validate_cell(key,old,new);assert row['occurrences']==art.locations[key]
    assert patch[by[1]]['on']==by[3] and patch[by[1]]['alsoOn']==[by[2]]
    assert {r['key'] for r in metadata if r['after'].get('counter')}=={by[n] for n in (27,28,30,31)}
    assert all(old['on']==patch[key]['on'] for key,old in baseline['cells'].items() if key in patch and 'on' in old)


def test_missing_north_hoods_are_walk_projection_only(data):
    baseline,_,(patch,_,_,additions,_,projection)=data
    assert len(projection)==2 and sum(len(r['occurrences']) for r in projection)==4
    assert all(r['decision']=='reference_only' and all(o['collision']==0 for o in r['occurrences']) for r in projection)
    assert all(k not in baseline['cells'] and k not in patch and k not in additions for k in p.CAPS.values())


def test_complete_roulette_and_sofa_have_supported_continuous_parts(data):
    _,_,(patch,rows,_,_,_,_)=data;by={r['source_ordinal']:r['key'] for r in rows}
    whole=np.zeros((16,48,32),bool)
    for n,x,z in [(6,0,0),(7,16,0),(10,0,16),(11,16,16),(16,0,32),(17,16,32)]:whole[:,z:z+16,x:x+16]=decode(patch[by[n]])[0]
    assert np.array_equal(whole,p.roulette().occ)
    assert whole[8].all() and not whole[0,24,16] and whole[0].sum()==36
    assert whole[13,8,8] and not whole[14:].any()
    couch=np.concatenate([decode(patch[by[n]])[0] for n in (4,5)],axis=2)
    assert np.array_equal(couch,p.sofa().occ) and not couch[0,8,16]


def test_vending_shell_plant_bin_and_slot_orientation(data):
    _,art,(patch,rows,_,_,_,_)=data;by={r['source_ordinal']:r['key'] for r in rows}
    for bottom in (2,3):assert np.array_equal(np.concatenate([decode(patch[by[bottom]])[0],decode(patch[by[1]])[0]]),p.vendor().occ)
    assert not p.vendor().occ[5,14,8] and p.vendor().occ[5,13,8]
    plant=np.concatenate([decode(patch[by[21]])[0],decode(patch[by[15]])[0]])
    assert np.array_equal(plant,p.c.plant().occ) and not plant[:,0,0].any()
    for n in (14,20):
        o,_=decode(patch[by[n]]);assert not o[7:,8,8].any() and o[6,8,8] and o[12,8,13]
    west,_=decode(patch[by[12]]);east,_=decode(patch[by[13]])
    assert np.array_equal(west[:,:,::-1],east)
    for n in (6,7,16,17):
        o,paint=decode(patch[by[n]]);rgb=np.asarray(art.cell(by[n],'BAKED').convert('RGB'))[paint//16,paint%16]
        legs=rgb[(np.indices(o.shape)[0]<6)&o].astype(int)
        assert len(legs)>0 and (legs[:,1]>110).all()  # native wood, not red floor


def test_actual_whole_banks_preserve_reference_only_hood_and_counter_void(data):
    _,art,_=data;specs=p.assemblies(art);assert len(specs)==9
    for spec in specs:
        if spec['id'].endswith('slots'):
            assert spec['model_layout'][0]==[None]*4
            assert len(spec['placements'])==16
        if spec['id'].endswith('counter'):
            assert all(spec['model_layout'][z][x] is None for z in (0,2) for x in range(1,6))
            assert len(spec['placements'])==18
