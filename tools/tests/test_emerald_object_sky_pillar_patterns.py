"""Exact source identity, stair continuity and doorway clearance."""
import copy
import hashlib
import json
import sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import object_sky_pillar_patterns as t

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists():pytest.skip('Local source export required')
    table=json.loads(t.BASELINE.read_text());before=copy.deepcopy(table);art=t.EmeraldArt(t.BUNDLE);entries=t.generate(table,art)
    assert table==before
    return table,art,entries

def test_exact_patterns_eight_occurrences_and_native_movement(data):
    table,art,entries=data;total=0
    for name in t.CASES:
        e=entries['emerald_object_sky_pillar_'+name];source=t.p.drawing(e,t.BUNDLE)
        assert hashlib.sha256(source.tobytes()).hexdigest()==t.CASES[name][-1]
        places=t.evidence(art,e,name);total+=len(places)
        assert not any(old['tiles']==e['tiles'] or old['id']==e['id'] for old in table['buildings'])
        if name=='upper_stairs':
            cells=places[0]['cells']
            assert len([c for c in cells if c['collision']==0 and c['elevation']==3])==4
            assert len([c for c in cells if c['collision']==7])==4
        elif name=='ascending_portal':
            assert all(len(loc['warps'])==1 for loc in places)
            assert all([c['collision'] for c in loc['cells']]==[7,7,7,7,114,7] for loc in places)
    assert total==8

def test_reviewed_originals_preserved_and_actual_tower_door_differs(data):
    table,art,entries=data
    old=next(e for e in table['buildings'] if e['id'].startswith('emerald_b110_'))
    assert t.building_catalog({'game':'emerald','cells':{},'buildings':[old]},art)[0]['occurrences']==[]
    e=entries['emerald_object_sky_pillar_tower'];reviewed=t.buildings_east.revise(110,old)
    assert e['parts'][:-2]==reviewed['parts'] and e['terrain']==reviewed['terrain']
    o,_=t.parts_model.build(e,np.asarray(t.p.drawing(e,t.BUNDLE)))
    assert o[:18,87,32:48].all() and not o[:18,88:92,32:48].any()
    assert sum(a!=b for ar,br in zip(old['tiles'],e['tiles']) for a,b in zip(ar,br))==6

def test_whole_flight_has_no_cell_reset_no_headroom_obstruction(data):
    _,_,entries=data;e=entries['emerald_object_sky_pillar_upper_stairs'];o,c=t.parts_model.build(e,np.asarray(t.p.drawing(e,t.BUNDLE)))
    assert e['terrain']==[]
    heights=o[:, :,24].sum(axis=0)
    assert heights.tolist()==[h for h in (12,11,9,8,6,5,3,2) for _ in range(4)]
    assert np.all(o[0,:,16:48]) and not o[12:,:,16:48].any()
    assert np.array_equal(c[31,10,5],c[31,10,58])

def test_ascending_portal_interior_steps_leave_arch_clear(data):
    _,_,entries=data;e=entries['emerald_object_sky_pillar_ascending_portal'];o,_=t.parts_model.build(e,np.asarray(t.p.drawing(e,t.BUNDLE)))
    assert all(o[:h,z:z+4,16:32].all() for z,h in ((16,8),(20,6),(24,4),(28,2)))
    assert not o[8:24,16:32,16:32].any()
    assert not o[24:30,16:32,20:28].any()
