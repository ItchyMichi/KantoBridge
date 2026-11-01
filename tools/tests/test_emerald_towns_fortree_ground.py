"""Fortree bridge floors retain runtime policy and native top paint."""
import copy
import json
import sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns_fortree_ground as t

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists():pytest.skip('Local source export required')
    table=json.loads(t.BASELINE.read_text());before=copy.deepcopy(table);art=t.EmeraldArt(t.BUNDLE);updates=t.generate(table,art)
    assert table==before
    return table,art,t.rows_for('fortree'),updates

def test_complete_owned_decisions_and_no_metadata_change(data):
    table,art,rows,updates=data;assert len(t.OWNED)==32 and set(t.decisions())==t.OWNED
    assert set(updates)=={rows[n-1]['key'] for n in t.CHANGED}
    meta=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
    for key,new in updates.items():assert meta(new)==meta(table['cells'][key])

def test_all_bridges_use_existing_walk_floor_policy(data):
    table,art,rows,updates=data
    for n in t.BRIDGES:
        key=rows[n-1]['key'];places=t.witnesses(art,rows[n-1])
        assert {p['collision'] for p in places}=={0}
        assert {p['elevation'] for p in places}<={4,15}
        assert not any(updates[key].get(f) for f in ('walk','seat','on'))

def test_ground_occupancy_unchanged_and_top_is_native_horizontal(data):
    table,art,rows,updates=data
    for n in t.CHANGED-{48,54}:
        k=rows[n-1]['key'];old,_=t.decode(table['cells'][k]);o,p=t.decode(updates[k]);assert np.array_equal(old,o)
        y,z,x=np.indices(o.shape);top=o&~np.concatenate((o[1:],np.zeros_like(o[:1])))
        assert np.array_equal(p[top],(z*16+x).astype(np.uint8)[top])

def test_two_suspension_planks_join_by_native_dark_ropes(data):
    table,art,rows,updates=data
    for n in (48,54):
        key=rows[n-1]['key'];o,p=t.decode(updates[key]);assert o[:4,1:14,1:7].all() and o[:4,1:14,9:15].all()
        assert not o[:,14,[1,6,9,14]].any()  # native background outside rounded board ends
        assert not o[:,7:9,7:9].any() and not o[4:].any()
        assert o[1,4,:].all() and o[1,10,:].all()
        rgb=np.asarray(art.cell(key,'BAKED').convert('RGB'));assert tuple(rgb[p[1,4,8]//16,p[1,4,8]%16])==(66,74,107)

def test_soil_bed_and_fringe_source_semantics(data):
    _,art,rows,_=data
    assert {p['collision'] for p in t.witnesses(art,rows[70])}=={7}
    assert {p['collision'] for p in t.witnesses(art,rows[71])}=={0}
