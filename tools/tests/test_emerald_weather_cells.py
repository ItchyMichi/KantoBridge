"""Weather source ownership, complete roof instruments and native material safety."""
import base64
import numpy as np
import pytest
from tools.shape_fit.emerald_pass import weather_cells as p, review
from tools.shape_fit.blue_pass.common import decode


@pytest.fixture(scope='module')
def data():
    table=review.read(review.BASELINE);art=p.c.EmeraldArt(p.c.BUNDLE)
    patch,rows=p.generate(table,art)
    return table,art,patch,rows


def test_exact_weather_ownership_preserves_matching_and_all_occurrences(data):
    table,art,patch,rows=data
    owned=review.read(p.c.OUT.parent/'towns-fortree/weather-ownership.json')['keys']
    assert len(rows)==len(patch)==54
    assert set(patch)=={r['key'] for r in owned}
    for row in rows:
        key=row['key'];review.validate_cell(key,table['cells'][key],patch[key])
        assert row['occurrences']==art.locations[key]
        assert all(x['map']=='EM_ROUTE119' for x in row['occurrences'])
        assert row['source_ordinal'] not in (108,111,116,127,130)
        for place in row['occurrences']:
            m=art.maps[place['map']];raw=base64.b64decode(m['cells'])
            # Source source collision remains untouched, including the entrance.
            assert raw[(place['y']*m['w']+place['x'])*4+2] in (0,7,113)


def test_rooftop_instruments_are_whole_grounded_objects_before_slicing(data):
    _,art,patch,rows=data
    for side in ('left','right'):
        expected=p.instruments(side).occ
        assembled=np.zeros_like(expected)
        for row in rows:
            if not row['recipe'].startswith('instrument_'+side+'_'):continue
            _,_,x,z=row['recipe'].split('_');x,z=int(x)*16,int(z)*16
            occ,_=decode(patch[row['key']])
            assembled[:occ.shape[0],z:z+16,x:x+16]=occ
        assert np.array_equal(assembled,expected)
        # Rounded white instrument joins at the central seam; both small domes
        # stay separate above their common low, circular platform.
        assert assembled[4:19,4:12,15:17].any()
        assert not assembled[10:,20:,:].any()
        assert assembled[0,16,16] and not assembled[0,0,0]
        assert assembled[3,8,16] and assembled[18,8,16]


def test_vent_hoods_have_open_fronts_supported_lids_and_native_material(data):
    _,art,patch,rows=data
    for row in rows:
        if row['recipe'] not in ('roof_grid','roof_left','roof_right'):continue
        occ,_=decode(patch[row['key']])
        xs=(1,9) if row['recipe']=='roof_grid' else (1,) if row['recipe']=='roof_left' else (9,)
        assert occ[:2].all()
        for x in xs:
            for z in (1,9):
                assert not occ[2:4,z+2:z+4,x+1:x+5].any()
                assert occ[4,z:z+4,x:x+6].all()
                assert occ[2:4,z:z+4,x].all()
    for row in rows:
        occ,paint=decode(patch[row['key']])
        rgb=np.asarray(art.cell(row['key'],'BAKED').convert('RGB')).reshape(-1,3).astype(int)[paint][occ]
        assert not ((rgb[:,1]>rgb[:,0]+10)&(rgb[:,1]>rgb[:,2]+10)).any(),row['key']
        if row['recipe']=='cliff':
            assert ((rgb[:,0]>rgb[:,1]+10)&(rgb[:,0]<200)).all()


def test_partial_edges_leave_grass_clear_and_curved_windows_remain_shallow(data):
    _,_,patch,rows=data
    for row in rows:
        occ,_=decode(patch[row['key']])
        if row['recipe']=='edge_left':assert not occ[:,:,8:].any()
        if row['recipe']=='edge_right':assert not occ[:,:,:8].any()
        if row['recipe']=='glass':
            assert not occ[:,:10].any()
            assert occ.sum(axis=1).max()==3
            # Convex center lies one voxel ahead of its side returns.
            assert np.flatnonzero(occ[5,:,8]).max()>np.flatnonzero(occ[5,:,0]).max()
