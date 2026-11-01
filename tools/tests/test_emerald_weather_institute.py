"""Exact source identity, terrain/walk boundaries, and supported architecture."""
import hashlib
import numpy as np
import pytest
from tools.shape_fit.emerald_pass import building_weather_institute as w,review


@pytest.fixture(scope='module')
def model():
    entry=w.generate();art=w.EmeraldArt(w.BUNDLE);source=w.p.drawing(entry,w.BUNDLE)
    occupied,colors=w.parts_model.build(entry,np.asarray(source))
    return entry,art,source,occupied,colors


def test_new_template_matches_only_the_actual_route119_source_and_warp(model):
    entry,art,source,_,_=model
    assert hashlib.sha256(source.tobytes()).hexdigest()==w.SOURCE_SHA256
    review.validate_building(w.ID,entry,entry)
    assert all(old['tiles']!=entry['tiles'] and old['id']!=entry['id'] for old in review.read(review.BASELINE)['buildings'])
    places=w.evidence(art,entry);assert len(places)==1
    assert places[0]['warps']==[dict(to='EM_ROUTE119_WEATHER_INSTITUTE_1F',warp=0,x=6,y=32)]
    for cell in places[0]['cells']:
        if not cell['terrain']:
            assert cell['collision']==(113 if cell['local']==[5,6] else 7)


def test_projected_cliff_forest_and_walk_ground_are_not_claimed(model):
    entry,_,_,occ,_=model
    assert entry['terrain']==[[x,0] for x in range(11)]+[[0,5],[0,6],[1,6],[9,6],[10,6]]
    assert w.LAYOUT[5][0]=='g3q/10755-17619-10757-17620'
    assert w.LAYOUT[6][1]=='g3q/17623-17624-17625-17460'
    for x,z in entry['terrain']:assert not occ[:,z*16:z*16+16,x*16:x*16+16].any()
    assert not occ[:,:16].any()
    assert not occ[:,112:].any() and not occ[:,:,176:].any()
    # The existing door carrier has a shallow threshold, open space in front
    # and a recessed door back; no source-ground cell becomes a wall.
    assert occ[0,110,88]
    assert not occ[2:32,99:108,80:96].any()
    assert occ[1:33,90,80:96].all()


def test_radomes_vent_openings_and_front_columns_are_physically_supported(model):
    _,_,_,occ,_=model
    for cx in (32,144):
        assert occ[:89,43,cx].all()
        assert not occ[90:,43,cx].any()
    for x in (48,72,96,122):assert occ[:61,106,x:x+6].all()
    for z in (23,35,47,59,71,83):
        for x in (49,59,69,79,89,99,109,119):
            assert occ[66,z+2,x+4]
            assert not occ[68,z+3,x+4]  # Real two-voxel opening below hood.
            assert occ[69,z+3,x+4]


def test_no_grass_or_cliff_background_becomes_building_material(model):
    _,_,source,occ,colors=model
    rgb=colors[occ].astype(int)
    assert not ((rgb[:,1]>rgb[:,0]+10)&(rgb[:,1]>rgb[:,2]+10)).any()
    # The original first attempt sampled the brown cliff at (62,4) for its roof.
    cliff=np.asarray(source)[4,62]
    assert not np.all(rgb==cliff,axis=1).any()
