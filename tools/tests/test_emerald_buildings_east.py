"""Architecture and scope checks for the eastern Emerald building review."""
import base64
import json
import numpy as np
import pytest
from tools.shape_fit.emerald_pass import buildings_east as east, review
from tools.shape_fit.emerald import parts_model


@pytest.fixture(scope='module')
def models():
    table=json.loads(east.batch.BASELINE.read_bytes())
    old={int(b['id'][9:12]):b for b in east.assigned(table)}
    new={n:east.revise(n,b) for n,b in old.items()}
    return old,new


def occupied(entry, x, y, z):
    result=np.zeros(np.broadcast_shapes(np.shape(x),np.shape(y),np.shape(z)),dtype=bool)
    for part in entry['parts']:
        mask=parts_model.inside(part,np.asarray(x),np.asarray(y),np.asarray(z))
        result=np.where(mask,not part.get('carve',False),result)
    return result


def test_complete_disjoint_scope_and_preserved_matching_metadata(models):
    old,new=models
    assert set(new)==set(east.FINDINGS)==set(range(60,112))-{64,65,85,86}
    for n,entry in new.items():
        review.validate_building(entry['id'],old[n],entry)
        assert {k:v for k,v in entry.items() if k not in {'parts','height','terrain'}} == {k:v for k,v in old[n].items() if k not in {'parts','height','terrain'}}
        removals=review.OVERHEAD_TERRAIN_REMOVALS.get(entry['id'],set())
        assert entry['terrain']==[c for c in old[n]['terrain'] if tuple(c) not in removals]
    assert sum(new[n]!=old[n] for n in old)>=25


@pytest.mark.parametrize('number,x,front,high',[(60,40,60,24),(62,60,78,24),(73,24,60,24),(87,24,60,24),
    (88,42,60,24),(92,24,60,18),(96,24,44,18),(99,136,224,18),(105,100,108,18),(107,88,108,22)])
def test_actual_recess_air_door_back_and_grounded_threshold(models,number,x,front,high):
    entry=models[1][number]
    assert not occupied(entry,x,np.arange(3,high),front-2).any()
    assert occupied(entry,x,np.arange(3,high),front-5).all()
    assert occupied(entry,x,0,front-1)


def test_house_roof_is_one_supported_gable_with_no_false_rear_storey(models):
    for n in (60,73,74,87,88):
        roof=[p for p in models[1][n]['parts'] if p['shape']=='gable']
        assert len(roof)==1
        entry=models[1][n]
        assert occupied(entry,10,roof[0]['y'][0]-1,30)
        assert occupied(entry,10,roof[0]['y'][0],30)
        assert not occupied(entry,10,roof[0]['y'][1]-1,10)


def test_market_has_supported_posts_and_hollow_jars_without_pavement_slab(models):
    entry=models[1][78]
    assert occupied(entry,10,np.arange(28,34),5).all()
    for n,centers in [(78,[(23,40),(39,40)]),(79,[(24,40),(40,40)])]:
        e=models[1][n]
        for x,z in centers:
            h=7 if n==78 else 14
            assert not occupied(e,x,h-1,z)
            assert occupied(e,x,1,z)
    assert not occupied(models[1][79],32,0,20)


def test_battle_tower_has_continuous_skyline_and_recessed_roof(models):
    e=models[1][99]
    assert occupied(e,np.array([22,60,110,180,240]),190,100).all()
    assert not occupied(e,60,194,100)
    assert occupied(e,18,194,100)
    assert not occupied(e,270,80,180)


def test_factory_has_three_chamfered_towers_and_tall_central_bay(models):
    e=models[1][90]
    assert not occupied(e,3,20,19)
    assert occupied(e,12,20,30)
    assert occupied(e,100,115,50)
    assert not occupied(e,30,115,50)
    assert not occupied(e,200,115,50)


def test_pike_tail_rises_from_body_with_continuous_curve_in_permitted_footprint(models):
    e=models[1][91]
    tail=[p for p in e['parts'] if p['shape']=='cylinder' and p['y'][1]-p['y'][0]==1]
    assert len(tail)==72
    excluded={tuple(c) for c in e['terrain']}
    for part in tail:
        x,z=part['center'];y=part['y'][0]
        assert occupied(e,x,y,z)
        if y>40: assert occupied(e,x,y-1,z)
        xgrid,zgrid=np.meshgrid(np.arange(80)+.5,np.arange(208)+.5)
        mask=parts_model.inside(part,xgrid,y+.5,zgrid)
        assert all((int(xx)//16,int(zz)//16) not in excluded for xx,zz in zip(xgrid[mask],zgrid[mask]))
    assert not occupied(e,50,100,62)


def test_boat_deck_is_hollow_and_sea_corner_is_empty(models):
    e=models[1][83]
    assert not occupied(e,4,0,2)
    for z in (13,36):
        assert not occupied(e,20,7,z-2)
        assert occupied(e,20,4,z-2)
        assert occupied(e,29,40,z)
    # Two distinct hull footprints and masts, not a second hull painted on a sail.
    assert not occupied(e,np.arange(48),0,24).any()
    assert not occupied(e,20,30,24)


def test_jar_shoulders_are_wider_than_mouth_and_have_open_centers(models):
    e=models[1][79]
    for x in (24,40):
        assert occupied(e,x+6,6,40)
        assert not occupied(e,x+6,13,40)
        assert occupied(e,x+4,13,40)
        assert not occupied(e,x,np.arange(10,14),40).any()


def test_house_window_panels_match_wall_projection(models):
    for n in (60,87,88):
        e=models[1][n];wall=e['parts'][0];wr=wall['paint']['front']
        panels=[p for p in e['parts'] if p.get('z')==[60,61]]
        assert len(panels)==(1 if n==87 else 2)
        for part in panels:
            rect=part['paint']['front']
            for t in (.1,.5,.9):
                y=part['y'][0]+(part['y'][1]-part['y'][0])*t
                source_y=wr[1]+(1-y/wall['y'][1])*(wr[3]-wr[1])
                assert source_y==pytest.approx(rect[3]-(rect[3]-rect[1])*t)


def test_corrected_wall_and_roof_materials_exclude_source_ground(models):
    from tools.shape_fit.emerald import building_trials
    for n in (62,67,83,100,101,104):
        e=models[1][n];drawing=np.asarray(building_trials.drawing(e,east.batch.BUNDLE))
        if n in (100,104):
            rect=e['parts'][1]['paint']['top']
            pixels=drawing[rect[1]:rect[3],rect[0]:rect[2]].reshape(-1,3)
            # Native orange/red roofing contains no green grass palette pixels.
            assert not ((pixels[:,1]>pixels[:,0])&(pixels[:,1]>pixels[:,2])).any()
        elif n in (67,101):
            rect=e['parts'][0]['paint']['side']
            assert rect[2]-rect[0]>=4
            pixels=drawing[rect[1]:rect[3],rect[0]:rect[2]].reshape(-1,3)
            assert len(np.unique(pixels,axis=0))<=5
        elif n==83:
            sails=[p for p in e['parts'] if p['shape']=='profile']
            assert len(sails)==4
            for sail in sails:
                rect=sail['paint']['front']
                assert rect[2]-rect[0]==rect[3]-rect[1]==1
        else:
            rect=e['parts'][2]['paint']['front']
            pixels=drawing[rect[1]:rect[3],rect[0]:rect[2]].reshape(-1,3)
            assert np.all(pixels==255)


def test_sky_pillar_retains_arch_and_true_window_recesses(models):
    e=models[1][110]
    assert not occupied(e,40,22,90)
    assert occupied(e,32,25,90)
    assert not occupied(e,22,44,90)
    assert occupied(e,22,44,88)
    assert occupied(e,5,160,90)
    assert e['tiles']==models[0][110]['tiles']


def test_retained_gate_has_open_full_depth_path(models):
    for n,x,y in [(98,40,20),(106,56,20)]:
        e=models[1][n]
        assert not occupied(e,x,y,np.arange(len(e['tiles'])*8)).any()
    # The summit stair doorway is an inset entrance, not a freestanding gate.
    assert not occupied(models[1][111],24,12,np.arange(16,32)).any()
    assert occupied(models[1][111],24,12,15)


def test_reception_passage_is_open_through_both_faces(models):
    entry=models[1][97]
    assert not occupied(entry,56,12,np.arange(19,62)).any()
    assert occupied(entry,44,12,40)
    assert occupied(entry,56,32,40)


def test_palace_upper_roof_intersects_supporting_middle_roof(models):
    parts=models[1][107]['parts']
    x,z=np.meshgrid(np.arange(56,120)+.5,np.arange(18,78)+.5)
    assert np.count_nonzero(parts_model.inside(parts[3],x,61.5,z))>100
    assert np.any(parts_model.inside(parts[3],x,62.5,z)&parts_model.inside(parts[4],x,62.5,z))


def test_tower_portico_stays_before_excluded_forecourt_row(models):
    entry=models[1][99]
    # Only x5/11 (WALK) and x8 (door) are excluded from the final source row.
    assert not occupied(entry,136,10,np.arange(225,240)).any()
    assert occupied(entry,120,10,222)
    for x in (88,184): assert not occupied(entry,x,np.arange(22)[:,None],np.arange(224,240)[None,:]).any()
    for x in (8,24,40,56,72,104,120,152,168,200,216,232,248,264):
        assert occupied(entry,x,10,238)


def test_tower_claims_only_exported_solid_facade_and_preserves_walk_door_cells(models):
    from tools import model_gallery
    old,new=models;entry=new[99]
    expected={(x,14) for x in range(17) if x not in (5,8,11)}
    assert {tuple(c) for c in old[99]['terrain']} - {tuple(c) for c in entry['terrain']} == expected
    assert entry['terrain']==[[5,14],[8,14],[11,14]]
    art=model_gallery.EmeraldArt(east.batch.BUNDLE);source=art.maps['EM_BATTLE_FRONTIER_OUTSIDE_EAST']
    raw=base64.b64decode(source['cells'])
    for x in range(17):
        collision=raw[(14*source['w']+8+x)*4+2]
        assert collision==(0 if x in (5,11) else 113 if x==8 else 7)


def test_pyramid_rear_foundation_claims_exact_source_solids_and_joins_first_terrace(models):
    from tools import model_gallery
    old,new=models;entry=new[103];expected={(x,1) for x in range(5,10)}
    assert {tuple(c) for c in old[103]['terrain']} - {tuple(c) for c in entry['terrain']} == expected
    assert entry['terrain']==[c for c in old[103]['terrain'] if tuple(c) not in expected]
    art=model_gallery.EmeraldArt(east.batch.BUNDLE);source=art.maps['EM_BATTLE_FRONTIER_OUTSIDE_EAST']
    raw=base64.b64decode(source['cells'])
    for x in range(15):
        assert raw[(6*source['w']+51+x)*4+2]==(7 if 5<=x<=9 else 0)
    for x in range(80,160): assert occupied(entry,x,4,np.arange(16,43)).all()
    assert not occupied(entry,79,4,np.arange(16,32)).any()
    assert not occupied(entry,160,4,np.arange(16,32)).any()


def test_material_crops_preserve_window_band_scale_and_clean_facade_edges(models):
    new=models[1]
    for n,index in [(70,1),(71,0)]:
        material=new[n]['parts'][index]['paint']
        assert material['side'][2]-material['side'][0]<=10
        assert material['side']==material['back']
    for n in (87,88): assert new[n]['parts'][0]['paint']['front'][3]==63
    bands=[q for q in new[109]['parts'] if q.get('paint',{}).get('front')==[1,46,160,64]]
    assert len(bands)==1 and bands[0]['y']==[18,40]
    assert occupied(new[110],40,12,66)
    assert not occupied(new[110],40,12,80)


@pytest.mark.parametrize('number,places',[
    (81,[('EM_SLATEPORT_CITY',8,30)]),
    (98,[('EM_BATTLE_FRONTIER_OUTSIDE_WEST',24,y) for y in (47,52,57)]),
    (105,[('EM_BATTLE_FRONTIER_OUTSIDE_EAST',33,23)])])
def test_overhead_carriers_are_empty_below_headroom_and_exported_walk_cells(models,number,places):
    from tools import model_gallery
    old,new=models;entry=new[number]
    cells=review.OVERHEAD_TERRAIN_REMOVALS[entry['id']]
    art=model_gallery.EmeraldArt(east.batch.BUNDLE)
    found=next(b for b in model_gallery.building_catalog({'game':'emerald','cells':{},'buildings':[old[number]]},art) if b['id']==entry['id'])
    assert {(p['map'],p['x'],p['y']) for p in found['occurrences']}==set(places)
    for cx,cz in cells:
        y,z,x=np.meshgrid(np.arange(entry['height']),np.arange(cz*16,(cz+1)*16),np.arange(cx*16,(cx+1)*16),indexing='ij')
        occ=occupied(entry,x,y,z)
        assert not occ[:16].any()
        assert occ[16:].any()
        for name,ox,oz in places:
            source=art.maps[name];raw=base64.b64decode(source['cells']);idx=((oz+cz)*source['w']+ox+cx)*4
            assert raw[idx+2]==0 # G3Cells.WALK, not merely a pixel-derived guess.
    assert entry['terrain']==[c for c in old[number]['terrain'] if tuple(c) not in cells]
