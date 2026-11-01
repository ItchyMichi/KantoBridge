"""Native materials, repeated physical sections and complete platform proofs."""
import base64
import numpy as np
import pytest
from tools.shape_fit.emerald_pass import mauville_gym as p,review
from tools.shape_fit.blue_pass.common import decode


@pytest.fixture(scope='module')
def data():
    t=review.read(review.BASELINE);a=p.common.EmeraldArt(p.common.BUNDLE);r=review.read(p.REGISTRY)
    return t,a,r,p.generate(t,a,r)


def test_coils_use_native_metal_blue_and_cyan_without_yellow_floor_and_have_connected_shafts(data):
    _,art,registry,patch=data
    for row in registry['entries']:
        recipe=row.get('recipe') or ''
        if not recipe.startswith('coil_'):continue
        occ,paint=decode(patch[row['key']]);rgb=np.asarray(art.cell(row['key'],'BAKED').convert('RGB'))[paint//16,paint%16]
        assert (rgb[occ,2]>=rgb[occ,0]).all()
        assert (rgb[occ,2]>=rgb[occ,1]).all()
        h=13 if recipe=='coil_cap' else 16
        assert occ[:h,8,8].all()
        assert not occ[:,:,:2].any() and not occ[:,:,14:].any()
    assert p.coil('middle').occ[2,:,2:14].sum()>p.coil('middle').occ[10,:,2:14].sum()


def test_every_primary_or_alternate_link_matches_its_actual_map_base_and_metadata_is_narrow(data):
    old,art,registry,patch=data
    for row in registry['entries']:
        if row['key'] not in patch or row['key'] not in old['cells']:continue
        before={k:v for k,v in old['cells'][row['key']].items() if k not in review.GEOMETRY}
        after={k:v for k,v in patch[row['key']].items() if k not in review.GEOMETRY}
        for name in ('on','alsoOn'):
            if row.get(name):before[name]=row[name]
        assert before==after
        if not(row.get('on') or row.get('alsoOn')):continue
        for place in art.locations[row['key']]:
            m=art.maps[place['map']];g=art.map_cells(m);x,y=place['x'],place['y'];raw=base64.b64decode(m['cells'])
            assert g[y+1][x] in [after['on'],*after.get('alsoOn',[])]
            assert raw[((y+1)*m['w']+x)*4+2]==7
    proof=review.read(p.common.OUT.parent/'gym-study/mauville-stack-evidence.json')
    assert proof['headroom']==9
    for spec in registry['assemblies']:
        if spec['id'].endswith('tall_pylon'):
            assert len(spec['source_layout'])==4
            assert spec['source_layout'][1]==spec['source_layout'][2]
            assert 4*2<=proof['headroom']


def test_complete_platform_is_one_surface_and_new_centers_are_exported_walk_cells(data):
    old,art,registry,patch=data;joined=np.zeros_like(p.dais().occ);added=[]
    for row in registry['entries']:
        recipe=row.get('recipe') or ''
        if not recipe.startswith('dais_'):continue
        _,x,z=recipe.split('_');x,z=int(x)*16,int(z)*16
        joined[:,z:z+16,x:x+16]=decode(patch[row['key']])[0]
        if row['key'] in old['cells']:continue
        added.append(row['key']);assert patch[row['key']]['walk'] is True
        for place in art.locations[row['key']]:
            m=art.maps[place['map']];raw=base64.b64decode(m['cells'])
            assert raw[(place['y']*m['w']+place['x'])*4+2]==0
    assert len(added)==4
    assert np.array_equal(joined,p.dais().occ)
    assert not joined[6:].any()
