"""Fortree source geometry, contiguous rail seams and narrow WALK render flags."""
import base64
import numpy as np
import pytest
from tools.shape_fit.emerald_pass import fortree_gym as p,review
from tools.shape_fit.blue_pass.common import decode


@pytest.fixture(scope='module')
def data():
    table=review.read(review.BASELINE);art=p.c.EmeraldArt(p.c.BUNDLE);registry=review.read(p.REGISTRY)
    return table,art,registry,p.generate(table,art,registry)


def test_bamboo_is_closed_round_and_uses_green_source_materials(data):
    table,art,registry,patch=data
    bamboo=[r for r in registry['entries'] if (r.get('recipe') or '').startswith('bamboo_')]
    assert len(bamboo)==8
    for row in bamboo:
        occ,paint=decode(patch[row['key']]);assert occ[:,6:10,6:10].all()
        assert np.array_equal(occ,occ.transpose(0,2,1))
        assert not occ[:,0].any() and not occ[:,:,0].any()
        rgb=np.asarray(art.cell(row['key'],'BAKED').convert('RGB')).reshape(-1,3)[paint][occ]
        assert ((rgb[:,1]>rgb[:,0])&(rgb[:,1]>rgb[:,2])).all()


def test_long_turnstile_sections_join_across_every_source_row(data):
    _,_,registry,patch=data
    spec=next(s for s in registry['assemblies'] if s['id']=='emerald_fortree_long_turnstile')
    sections=[decode(patch[line[0]])[0] for line in spec['source_layout']]
    for a,b in zip(sections,sections[1:]):
        assert (a[:,-1]&b[:,0]).sum()>=16
    full=np.concatenate(sections,axis=1)
    assert full[:,2:62,2:6].any(axis=0).all()
    assert full[:,2:62,10:14].any(axis=0).all()
    assert not full[11:].any()


def test_six_walkable_arena_cells_keep_geometry_and_all_other_metadata(data):
    table,art,registry,patch=data
    added_walk=[]
    for row in registry['entries']:
        key=row['key']
        if key not in patch:continue
        if row.get('recipe')=='walk_platform':
            added_walk.append(key)
            expected=dict(table['cells'][key],walk=True)
            assert patch[key]==expected
            for place in art.locations[key]:
                m=art.maps[place['map']];raw=base64.b64decode(m['cells'])
                assert raw[(place['y']*m['w']+place['x'])*4+2]==0
        else:review.validate_cell(key,table['cells'][key],patch[key])
    assert len(added_walk)==6


def test_actual_assemblies_keep_projection_cells_out_of_the_physical_wall(data):
    table,art,registry,patch=data
    assert len(registry['entries'])==28 and len(patch)==23
    projection=registry['entries'][13]['key']
    assert projection not in patch
    for spec in registry['assemblies']:
        x,y,w,h=spec['rect'];grid=art.map_cells(art.maps[spec['map']])
        assert spec['source_layout']==[r[x:x+w] for r in grid[y:y+h]]
        model=spec.get('model_layout',spec['source_layout'])
        assert projection not in [k for line in model for k in line]
        for place in spec.get('placements',[]):
            xx,z=place['source'];dx,dy,dz=place['offset'];key=model[z][xx]
            assert dx==xx and dy in (0,1)
            if dy:
                assert dz==z+1 and table['cells'][key]['on']==model[z+1][xx]
            else:assert dz==z
