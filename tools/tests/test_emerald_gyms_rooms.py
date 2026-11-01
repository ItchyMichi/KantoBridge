"""Complete platform, visible flower and exact source-backed wall stacking."""
import base64
import numpy as np
import pytest
from tools.shape_fit.emerald_pass import gyms_rooms as p,review
from tools.shape_fit.blue_pass.common import decode


@pytest.fixture(scope='module')
def data():
    table=review.read(review.BASELINE);art=p.c.EmeraldArt(p.c.BUNDLE);registry=review.read(p.REGISTRY)
    return table,art,registry,p.generate(table,art,registry)


def test_complete_blue_dais_and_exact_walk_additions(data):
    table,art,registry,patch=data;joined=np.zeros_like(p.mossdeep_dais().occ);added=[]
    for row in registry['entries']:
        recipe=row.get('recipe') or ''
        if not recipe.startswith('dais_'):continue
        _,x,z=recipe.split('_');x,z=int(x)*16,int(z)*16
        joined[:,z:z+16,x:x+16]=decode(patch[row['key']])[0]
        if row['key'] in table['cells']:continue
        added.append(row['key']);assert patch[row['key']]['walk'] is True
        for place in art.locations[row['key']]:
            m=art.maps[place['map']];raw=base64.b64decode(m['cells'])
            assert raw[(place['y']*m['w']+place['x'])*4+2]==0
    assert len(added)==2
    assert np.array_equal(joined,p.mossdeep_dais().occ)
    assert joined[:3].all() and not joined[8:].any()


def test_flower_is_visible_in_front_of_its_backing_and_all_six_cells_join(data):
    table,art,registry,patch=data;joined=np.zeros_like(p.alcove().occ)
    for row in registry['entries']:
        recipe=row.get('recipe') or ''
        if not recipe.startswith('alcove_'):continue
        _,x,level=recipe.split('_');x,level=int(x)*16,int(level)*16
        joined[level:level+16,:,x:x+16]=decode(patch[row['key']])[0]
    assert np.array_equal(joined,p.alcove().occ)
    assert joined[7:19,7,24].all()  # the stem links pot and blossom
    assert joined[20,8:10,24].all() and not joined[20,11:,24].any()
    assert joined[20,:3,24].all()  # distinct wall behind, no wall hiding flower
    assert joined[0,3:13,24].all()  # grounded shelf


def test_only_four_proven_pairs_change_metadata_and_wall_and_statue_paint_are_clean(data):
    table,art,registry,patch=data;links=0
    assert len(registry['entries'])==39
    for row in registry['entries']:
        key=row['key'];recipe=row.get('recipe');entry=patch.get(key,table['cells'].get(key))
        if key in table['cells']:
            oldmeta={k:v for k,v in table['cells'][key].items() if k not in review.GEOMETRY}
            newmeta={k:v for k,v in entry.items() if k not in review.GEOMETRY}
            if row.get('on'):oldmeta['on']=row['on']
            assert oldmeta==newmeta
        if row.get('on'):
            links+=1
            for place in art.locations[key]:
                m=art.maps[place['map']];g=art.map_cells(m);raw=base64.b64decode(m['cells']);x,y=place['x'],place['y']
                assert g[y+1][x]==row['on']
                assert raw[(y*m['w']+x)*4+2]==raw[((y+1)*m['w']+x)*4+2]==7
        if recipe=='wall':
            occ,_=decode(entry);assert occ[:,13:,:].all() and not occ[:,:13,:].any()
        if recipe=='statue_clean':
            occ,paint=decode(entry);rgb=np.asarray(art.cell(key,'BAKED').convert('RGB'))[paint//16,paint%16]
            gold=(rgb[...,0]>180)&(rgb[...,1]>160)&(rgb[...,2]<160)
            assert not (gold&occ).any()
    assert links==4
