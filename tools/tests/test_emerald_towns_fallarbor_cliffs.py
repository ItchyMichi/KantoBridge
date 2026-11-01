"""Exact cliff interfaces, native material selection and layer policy."""
import base64
import copy
import json
import sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns_fallarbor_cliffs as t
from tools.shape_fit.emerald.littleroot_tree import KEYS

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists():pytest.skip('Local source export required')
    table=json.loads(t.BASELINE.read_text());before=copy.deepcopy(table);art=t.EmeraldArt(t.BUNDLE);updates=t.generate(table,art);assert table==before
    return table,art,t.rows_for('fallarbor'),updates

def test_scope_native_layer_policy_and_protected_tree_identity(data):
    table,art,rows,updates=data;assert len(updates)==9 and set(t.decisions())==t.OWNED
    assert not set(updates)&set(KEYS.values())
    meta=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
    for k,e in updates.items():assert meta(e)==meta(table['cells'][k]);assert not e.get('up')

def test_ash_return_reaches_both_source_solid_interfaces(data):
    _,art,rows,updates=data;o,p=t.decode(updates[rows[0]['key']]);assert o[:,:,0].all() and o[:,-1,:].all();assert not o[:,0,-1].any()
    for loc in t.witnesses(art,rows[0]):
        m=art.maps[loc['map']];raw=base64.b64decode(m['cells']);x,y=loc['x'],loc['y']
        assert raw[(y*m['w']+x-1)*4+2]==7 and raw[((y+1)*m['w']+x)*4+2]==7

def test_complete_olive_cap_has_no_trench_or_unsupported_skirt(data):
    _,_,rows,updates=data;whole=np.zeros((16,32,32),bool)
    for n,(x,z) in t.ROLES.items():
        o,_=t.decode(updates[rows[n-1]['key']]);whole[:,z*16:z*16+16,x*16:x*16+16]=o
    assert np.array_equal(whole,t.cap().occ);assert whole[:14,15:17,15:17].all();assert not (whole[1:]&~whole[:-1]).any()

def test_native_stone_only_and_no_duplicate_projected_leaves(data):
    _,art,rows,updates=data
    for n in t.OWNED:
        k=rows[n-1]['key'];o,p=t.decode(updates[k]);rgb=np.asarray(art.cell(k,'BAKED').convert('RGB'));colors={tuple(c) for c in rgb[p[o]//16,p[o]%16]}
        expected=set(t.PINK) if n==1 else set(t.ORANGE) if n in (59,60) else set(t.OLIVE)|{(206,206,165)}
        assert colors<=expected
