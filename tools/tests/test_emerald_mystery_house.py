import numpy as np
import pytest
from tools.shape_fit.emerald_pass import mystery_house as p,review
from tools.shape_fit.blue_pass.common import decode

@pytest.fixture(scope='module')
def data():
    table=review.read(review.BASELINE);art=p.c.EmeraldArt(p.c.BUNDLE)
    return table,art,*p.generate(table,art)

def test_complete_transferred_ownership_preserves_every_metadata_field(data):
    table,art,patch,rows=data
    assert len(rows)==13 and len(patch)==7 and len(p.RETAIN)==6
    assert 'g3q/7625-7626-7627-7628' not in {r['key'] for r in rows}
    for r in rows:
        key=r['key'];new=patch.get(key,table['cells'][key]);review.validate_cell(key,table['cells'][key],new)
        assert r['occurrences']==art.locations[key]
        if r['source_ordinal'] in p.RETAIN:assert key not in patch

def test_actual_plant_pair_is_supported_and_has_no_carpet_backing(data):
    table,art,patch,rows=data;by={r['source_ordinal']:r['key'] for r in rows}
    full=np.concatenate([decode(patch[by[10]])[0],decode(patch[by[5]])[0]])
    assert np.array_equal(full,p.plant().occ) and (full[15]&full[16]).any()
    assert not full[:,:1,:].any() and not full[:,:,:1].any()
    assert patch[by[5]]['on']==by[10] and full[0].any()
    source=p.review.read(p.STUDY/'source-evidence.json')
    assert [o['collision'] for s in source if s['position']==5 for o in s['occurrences']]==[0]
    assert [o['collision'] for s in source if s['position']==10 for o in s['occurrences']]==[7]

def test_local_table_quarters_match_approved_shared_table_and_chairs_face_inward(data):
    _,art,patch,rows=data;by={r['source_ordinal']:r['key'] for r in rows}
    for n,key in [(7,p.shared_final.YELLOW[0][0]),(8,p.shared_final.YELLOW[0][1]),(11,p.shared_final.YELLOW[1][0])]:
        o,_=decode(patch[by[n]]);expected,_=p.shared_final.yellow_table(art.cell(by[n],'BAKED'),key);assert np.array_equal(o,expected)
    left,_=decode(patch[by[3]]);right,_=decode(patch[by[4]])
    assert np.array_equal(left,np.rot90(right,2,axes=(1,2)))
    assert left[0].sum()==right[0].sum()==16 and not left[0,8,8]
