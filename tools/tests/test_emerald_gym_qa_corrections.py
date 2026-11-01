"""Actual-capture regressions: closed round globes, level floors, native paint."""
import base64
from collections import deque
import numpy as np
import pytest
from tools.shape_fit.emerald_pass import gym_qa_corrections as p, review
from tools.shape_fit.blue_pass.common import decode


@pytest.fixture(scope='module')
def data():
    table=review.read(review.BASELINE);art=p.c.EmeraldArt(p.c.BUNDLE)
    rows,specs=p.selection()
    return table,art,rows,specs,p.generate(table,art,rows)


def test_statues_are_connected_spherical_objects_without_room_floor_paint(data):
    table,art,rows,specs,patch=data
    full=p.statue().occ
    # A genuine sphere has the same cross-section looking north or east and
    # all eight octants are present. This catches the omitted quadrant and
    # the old shallow source extrusion, including hidden backside defects.
    ball=full[19:31]
    assert np.array_equal(ball,ball.transpose(0,2,1))
    assert np.array_equal(ball,ball[:,:,::-1])
    assert np.array_equal(ball,ball[:,::-1,:])
    assert ball.sum()>850
    seen={tuple(np.argwhere(full)[0])};queue=deque(seen)
    while queue:
        point=queue.popleft()
        for axis in range(3):
            for delta in (-1,1):
                neighbor=list(point);neighbor[axis]+=delta;neighbor=tuple(neighbor)
                if all(0<=v<full.shape[n] for n,v in enumerate(neighbor)) and full[neighbor] and neighbor not in seen:
                    seen.add(neighbor);queue.append(neighbor)
    assert len(seen)==full.sum()
    for spec in specs[:6]:
        upper,lower=[line[0] for line in spec['source_layout']]
        joined=np.concatenate([decode(patch[lower])[0],decode(patch[upper])[0]])
        assert np.array_equal(joined,full)
        assert patch[upper]['on']==lower==table['cells'][upper]['on']
        for key in (upper,lower):
            occ,paint=decode(patch[key]);colors=np.asarray(art.cell(key,'BAKED').convert('RGB')).reshape(-1,3)[paint][occ].astype(int)
            assert not ((colors[:,0]-colors[:,2]>80)&(colors[:,0]>160)).any(),key


def test_dais_has_no_center_trench_and_two_shallow_stairs(data):
    _,art,rows,_,patch=data
    full=p.dewford_dais().occ;joined=np.zeros_like(full)
    for row in rows:
        if row['recipe'].startswith('dais_'):
            _,x,z=row['recipe'].split('_');x,z=int(x)*16,int(z)*16
            occ,paint=decode(patch[row['key']]);joined[:,z:z+16,x:x+16]=occ
            for zz in range(16):
                for xx in range(16):
                    yy=np.flatnonzero(occ[:,zz,xx])[-1]
                    rgb=np.asarray(art.cell(row['key'],'BAKED').convert('RGB')).astype(int)[zz,xx]
                    if not (rgb[0]==rgb[1] and rgb[1]-rgb[2]>=20):
                        assert paint[yy,zz,xx]==zz*16+xx
                    else:
                        painted=np.asarray(art.cell(row['key'],'BAKED').convert('RGB')).reshape(-1,3)[paint[yy,zz,xx]].astype(int)
                        assert not (painted[0]==painted[1] and painted[1]-painted[2]>=20)
    assert np.array_equal(full,joined)
    assert full[:3,2:26,4:60].all()
    assert not full[3:,5:26,5:59].any()
    for x in (5,47):
        assert full[:1,30:32,x:x+12].all() and not full[1:,30:32,x:x+12].any()
        assert full[:2,28:30,x:x+12].all() and not full[2:,28:30,x:x+12].any()


def test_ledge_remains_low_source_collision163_with_visible_brick_face(data):
    _,art,rows,_,patch=data
    for row in rows:
        if row['recipe']!='ledge':continue
        occ,paint=decode(patch[row['key']]);assert occ[:4].all();assert not occ[4:].any()
        face=np.asarray(art.cell(row['key'],'BAKED').convert('RGB')).reshape(-1,3)[paint[1:4,15]]
        assert len(np.unique(face.reshape(-1,3),axis=0))>2
        for place in art.locations[row['key']]:
            m=art.maps[place['map']];raw=base64.b64decode(m['cells'])
            assert raw[(place['y']*m['w']+place['x'])*4+2]==163


def test_exact_map_membership_and_all_matching_flags_preserved(data):
    table,art,rows,specs,patch=data
    assert len(rows)==len(patch)==22
    for key,entry in patch.items():review.validate_cell(key,table['cells'][key],entry)
    for spec in specs:
        x,y,w,h=spec['rect'];grid=art.map_cells(art.maps[spec['map']])
        assert spec['source_layout']==[r[x:x+w] for r in grid[y:y+h]]
        assert all(k in patch for line in spec['source_layout'] for k in line)
