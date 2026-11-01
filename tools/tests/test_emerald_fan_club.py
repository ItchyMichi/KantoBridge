import base64
import numpy as np
import pytest
from tools.shape_fit.emerald_pass import fan_club as f
from test_emerald_oceanic_museum import connected


def test_furniture_and_fronds_are_supported_with_open_undersides():
    for role in ('sofa_east','sofa_west','sofa_north','glass_table','low_bookcase','tall_bookcase','plant'):
        assert connected(f.whole(role).occ),role
    table=f.whole('glass_table').occ
    assert not table[:6,12:25,8:56].any()
    assert table[8,10:27,5:59].all()
    sofa=f.whole('sofa_north').occ
    assert not sofa[:4,18:25,6:57].any()
    assert np.array_equal(sofa[:,:,16:32],sofa[:,:,32:48])


@pytest.mark.skipif(not f.BASELINE.exists(),reason='local source export unavailable')
def test_stack_witnesses_and_native_materials():
    art=f.EmeraldArt(f.BUNDLE);base=f.read(f.BASELINE);patch,rows,metadata=f.generate(base,art)
    assert len(patch)==31 and len(rows)==33 and len(metadata)==4
    for row in metadata:
        new=patch[row['key']];targets={new.get('on'),*new.get('alsoOn',[])}
        for p in row['occurrences']:
            assert p['below_key'] in targets
            s=art.maps[p['map']]
            assert base64.b64decode(s['cells'])[((p['y']+1)*s['w']+p['x'])*4+2]==7
    for k,spec in f.read(f.REGISTRY).items():
        if spec.get('role')!='plant':continue
        m=f.model(art.cell(k,'BAKED'),spec);rgb=np.asarray(art.cell(k,'BAKED').convert('RGB'))[m.paint//16,m.paint%16].astype(int)
        if spec['layer']==1:
            colors=rgb[m.occ];assert ((colors[:,1]>colors[:,0]+5)&(colors[:,1]>colors[:,2]+5)).all()
        else:
            y=np.indices(m.occ.shape)[0];colors=rgb[m.occ&(y<6)]
            assert (abs(colors[:,0]-colors[:,1])<20).all()


def test_baselines_keep_old_floor_cases_and_existing_true_plant_pairs():
    specs=f.read(f.REGISTRY.with_name('fan_club_assemblies.json'))
    assert len(specs)==12
    by={s['id']:s for s in specs}
    for name in ('low_bookcase','tall_bookcase','plant_ne'):
        s=by['emerald_fan_club_'+name]
        assert all(p['offset'][1]==0 for p in s['baseline_placements'])
        assert any(p['offset'][1]==1 for p in s['placements'])
    for name in ('plant_nw','plant_sw','plant_se'):
        s=by['emerald_fan_club_'+name];assert s['placements']==s['baseline_placements']
