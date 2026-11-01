"""Complete cascade geometry, native crystal slices, and exact walk variants."""
import base64
from collections import deque
import numpy as np
import pytest
from tools.shape_fit.emerald_pass import sootopolis_gym as p,review
from tools.shape_fit.blue_pass.common import decode


@pytest.fixture(scope='module')
def data():
    table=review.read(review.BASELINE);art=p.c.EmeraldArt(p.c.BUNDLE);registry=review.read(p.REGISTRY)
    return table,art,registry,p.generate(table,art,registry)


def test_six_cascade_slices_form_one_connected_outlet_waterfall_and_pool(data):
    _,_,registry,patch=data;full=p.cascade().occ;joined=np.zeros_like(full)
    for row in registry['entries']:
        if not (row.get('recipe') or '').startswith('cascade_'):continue
        _,x,z=row['recipe'].split('_');x,z=int(x)*16,int(z)*16
        occ,_=decode(patch[row['key']]);assert len(occ)==16
        joined[:,z:z+16,x:x+16]=occ
    assert np.array_equal(full,joined)
    assert not full[11,14,8]  # Recessed spout opening has real depth.
    assert full[12,15:22,8].all() and full[3:13,20:22,8].all()
    assert full[2,22:46,8].all()
    seen={tuple(np.argwhere(full)[0])};queue=deque(seen)
    while queue:
        point=queue.popleft()
        for axis in range(3):
            for delta in (-1,1):
                neighbor=list(point);neighbor[axis]+=delta;neighbor=tuple(neighbor)
                if all(0<=v<full.shape[n] for n,v in enumerate(neighbor)) and full[neighbor] and neighbor not in seen:
                    seen.add(neighbor);queue.append(neighbor)
    assert len(seen)==full.sum()


def test_both_solid_and_walkable_cascade_occurrences_keep_their_source_collision(data):
    table,art,registry,patch=data
    for row in registry['entries']:
        if not (row.get('recipe') or '').startswith('cascade_'):continue
        key=row['key'];assert patch[key]['walk'] is True
        kinds=[]
        for place in art.locations[key]:
            m=art.maps[place['map']];raw=base64.b64decode(m['cells'])
            kinds.append(raw[(place['y']*m['w']+place['x'])*4+2])
        assert sorted(kinds)==[0,0,7,7]
        assert 'on' not in patch[key]


def test_crystal_slices_keep_three_spires_and_exclude_the_white_wall(data):
    _,art,registry,patch=data
    upper=registry['entries'][18]['key'];lower=registry['entries'][19]['key']
    joined=np.concatenate([decode(patch[lower])[0],decode(patch[upper])[0]])
    assert np.array_equal(joined,p.crystal().occ)
    assert patch[upper]['on']==lower
    assert joined[:4].any() and not joined[23:].any()
    assert joined[8,:,3:5].any() and joined[8,:,7:9].any() and joined[8,:,11:13].any()
    occ,paint=decode(patch[upper]);colors=np.asarray(art.cell(upper,'BAKED').convert('RGB')).reshape(-1,3)[paint][occ]
    assert (colors[:,2]>colors[:,0]).all() and not (colors.min(1)>190).any()


def test_only_sixteen_source_proven_walk_render_flags_change(data):
    table,art,registry,patch=data;walk=0
    assert len(patch)==22 and len(registry['entries'])==26
    for row in registry['entries']:
        key=row['key']
        if key not in patch:continue
        expected={k:v for k,v in table['cells'][key].items() if k not in review.GEOMETRY}
        if row.get('render_on_walk'):
            expected['walk']=True;walk+=1
            if row['recipe'].startswith('platform_'):
                for place in art.locations[key]:
                    m=art.maps[place['map']];raw=base64.b64decode(m['cells'])
                    assert raw[(place['y']*m['w']+place['x'])*4+2]==0
        assert {k:v for k,v in patch[key].items() if k not in review.GEOMETRY}==expected
    assert walk==16
    for spec in registry['assemblies']:
        x,y,w,h=spec['rect'];grid=art.map_cells(art.maps[spec['map']])
        assert spec['source_layout']==[r[x:x+w] for r in grid[y:y+h]]


def test_checkerboard_is_on_the_continuous_horizontal_platform(data):
    _,art,registry,patch=data;joined=np.zeros_like(p.dais().occ)
    for row in registry['entries']:
        if not (row.get('recipe') or '').startswith('platform_'):continue
        _,x,z=row['recipe'].split('_');x,z=int(x)*16,int(z)*16
        occ,paint=decode(patch[row['key']]);joined[:,z:z+16,x:x+16]=occ
        if x==32 and z==0:
            # The mint squares used to be painted on the front of the old box.
            assert paint[2,8,8]==8*16+8
            assert paint[2,5,4]==5*16+4
    assert np.array_equal(joined,p.dais().occ)
    assert joined[:3,6:26,6:74].all()
    assert not joined[3:].any()
