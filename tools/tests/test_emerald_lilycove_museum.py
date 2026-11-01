import base64
import numpy as np
import pytest
from tools.shape_fit.emerald_pass import lilycove_museum as m
from tools.tests.test_emerald_oceanic_museum import connected


def test_sculptures_are_connected_and_grounded():
    for role in ('figurine','round_exhibit'):
        shape=m.whole(role)
        assert connected(shape.occ)
        assert shape.occ[0].any()
    normal=m.whole('figurine');mirror=m.whole('figurine',{'mirror':True})
    assert np.array_equal(normal.occ[:,:,::-1],mirror.occ)
    assert not normal.occ[9:22,:,:3].any()  # Air beside the carved narrow neck.


@pytest.mark.skipif(not m.BASELINE.exists(),reason='local source export unavailable')
def test_paintings_are_thin_and_front_uses_exact_source_texels():
    art=m.EmeraldArt(m.BUNDLE)
    for key,spec in m.read(m.REGISTRY).items():
        if spec.get('role')!='painting':continue
        shape=m.model(art.cell(key,'BAKED'),spec)
        assert not shape.occ[:,:2,:].any() and not shape.occ[:,5:,:].any()
        front=shape.occ&~np.concatenate((shape.occ[:,1:,:],np.zeros_like(shape.occ[:,:1,:])),axis=1)
        y,z,x=np.indices(shape.occ.shape)
        assert np.array_equal(shape.paint[front],((15-y)*16+x)[front])
        x0,y0,x1,y1=spec['crop'];sy=spec['height']-16-spec.get('layer',0)*16
        assert ((15-y[front]+sy)>=y0).all() and ((15-y[front]+sy)<y1).all()


@pytest.mark.skipif(not m.BASELINE.exists(),reason='local source export unavailable')
def test_new_painting_links_have_exact_solid_source_neighbors():
    art=m.EmeraldArt(m.BUNDLE);base=m.read(m.BASELINE)
    patch,rows,metadata=m.generate(base,art)
    assert (len(patch),len(rows),len(metadata))==(27,65,9)
    for row in metadata:
        new=patch[row['key']]
        assert row['after']==dict(row['before'],on=new['on'])
        for loc in row['occurrences']:
            assert loc['collision']==7 and loc['below_key']==new['on']
            src=art.maps[loc['map']]
            assert base64.b64decode(src['cells'])[((loc['y']+1)*src['w']+loc['x'])*4+2]==7


def test_painting_before_companions_preserve_old_floor_rows():
    scenes=m.read(m.REGISTRY.with_name('lilycove_museum_assemblies.json'))
    assert len(scenes)==20
    for e in scenes:
        if '_painting_' not in e['id'] or len(e['layout'])<2:continue
        assert all(p['offset'][1]==0 for p in e['baseline_placements'])
        assert any(p['offset'][1]==1 for p in e['placements'])
        assert set(tuple(p['source']) for p in e['placements'])==set(tuple(p['source']) for p in e['baseline_placements'])
