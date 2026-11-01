"""Complete source furniture, native palette joins and exact counter semantics."""
import copy
import numpy as np
import pytest
from tools.shape_fit.emerald_pass import mossdeep_corner as p,review
from tools.shape_fit.blue_pass.common import decode


@pytest.fixture(scope='module')
def data():
    table=review.read(review.BASELINE);art=p.c.EmeraldArt(p.c.BUNDLE)
    patch,rows,metadata=p.generate(table,art)
    return table,art,patch,rows,metadata


def test_exact_owned_keys_and_interactions_remain_source_bound(data):
    table,art,patch,rows,metadata=data
    assert len(rows)==22 and len(patch)==21 and len(metadata)==3
    assert {r['source_ordinal'] for r in rows}==set(p.ROLES)
    assert 'g3q/7313-7313-7384-7385' not in {r['key'] for r in rows}
    for row in rows:
        key=row['key'];old=table['cells'][key];new=patch.get(key,old)
        adjusted=copy.deepcopy(new)
        if row['source_ordinal'] in (18,19,20):
            assert new['counter'] is True and 'counter' not in old
            adjusted.pop('counter')
        review.validate_cell(key,old,adjusted)
        assert row['occurrences']==art.locations[key]
        for field in ('on','alsoOn','seat','walk'):assert new.get(field)==old.get(field)
    assert all(all(o['collision']==144 for o in r['occurrences']) for r in metadata)


def test_table_quarters_reassemble_one_supported_rounded_table(data):
    _,_,patch,rows,_=data;by={r['source_ordinal']:r['key'] for r in rows}
    whole=np.zeros((16,32,32),bool)
    for n,x,z in [(7,0,0),(8,16,0),(15,0,16),(16,16,16)]:whole[:,z:z+16,x:x+16]=decode(patch[by[n]])[0]
    assert np.array_equal(whole,p.game_table().occ)
    assert whole[8,16,15:17].all() and whole[8,15:17,16].all()
    assert not whole[0,16,16] and whole[0,4,4] and whole[0,27,27]
    assert not whole[8,0,0] and not whole[9:].any()


def test_cupboard_halves_join_with_matching_native_slats_and_no_room_yellow(data):
    _,art,patch,rows,_=data;by={r['source_ordinal']:r['key'] for r in rows}
    for upper,lower in [(3,9),(6,14)]:
        lo,lp=decode(patch[by[lower]]);hi,hp=decode(patch[by[upper]])
        assert np.array_equal(np.concatenate([lo,hi]),p.cabinet().occ)
        lc=np.asarray(art.cell(by[lower],'BAKED').convert('RGB'))[lp//16,lp%16]
        hc=np.asarray(art.cell(by[upper],'BAKED').convert('RGB'))[hp//16,hp%16]
        assert np.array_equal(lc[15][lo[15]],hc[0][hi[0]])
        colors=hc[hi].astype(int)
        assert not ((colors[:,0]>220)&(colors[:,1]>210)&(colors[:,2]<100)).any()
        assert hi[11,13,2] and not hi[14:].any()


def test_stool_and_counter_dimensions_preserve_seating_and_talk_through(data):
    _,_,patch,rows,_=data;by={r['source_ordinal']:r['key'] for r in rows}
    for n in (1,2,4,5):
        occ,_=decode(patch[by[n]])
        assert occ[0,4,4] and occ[0,10,10] and not occ[0,8,8]
        assert occ[7,8,8] and not occ[9:].any()
    for n in (18,19,20):
        occ,_=decode(patch[by[n]])
        assert occ[7].any() and not occ[8:].any()
    # The full service top is continuous before its original source cells split it.
    whole=p.counter().occ
    assert whole[7,:30,:].all() and not whole[8:].any()
    assert not whole[0,16,24] and whole[0,26,44]
