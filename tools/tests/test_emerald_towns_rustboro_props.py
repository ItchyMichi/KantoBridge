"""Preserved lamp/sign stacks, clean native materials and protected forest."""
import copy,json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns_rustboro_props as t
from tools.shape_fit.emerald.littleroot_tree import KEYS

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists():pytest.skip('Local exported source required')
    table=json.loads(t.BASELINE.read_text());old=copy.deepcopy(table);art=t.EmeraldArt(t.BUNDLE);updates=t.generate(table,art);assert table==old
    return table,art,t.rows_for('rustboro'),updates

def test_exact_stack_pairs_scope_and_metadata(data):
    table,art,rows,updates=data;assert len(updates)==8;proof=t.stack_proof(art);assert len(proof)==18
    meta=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
    assert not set(updates)&set(KEYS.values())
    for k,e in updates.items():assert meta(e)==meta(table['cells'][k])
    for upper,base in ((2,4),(3,4),(43,46)):
        u,_=t.decode(updates[rows[upper-1]['key']]);b,_=t.decode(updates[rows[base-1]['key']]);assert (b[15]&u[0]).any()
    assert 'on' not in updates[rows[57]['key']]

def test_lamp_root_and_narrow_shaft_hold_lantern(data):
    _,_,rows,updates=data;b,_=t.decode(updates[rows[3]['key']]);u,_=t.decode(updates[rows[1]['key']]);whole=np.concatenate((b,u));assert np.array_equal(whole,t.lamp().occ)
    assert whole[0].sum()>whole[10].sum() and whole[24].sum()>whole[10].sum()
    assert whole[:,7:9,7:9].any(axis=(1,2)).all()

def test_no_native_paving_or_turquoise_grass_on_props(data):
    _,art,rows,updates=data;forbidden={(239,239,214),(206,206,165),(189,189,132),(214,214,189),(181,181,148),(156,156,123),(115,197,165),(25,165,107)}
    for n in t.OWNED:
        k=rows[n-1]['key'];o,p=t.decode(updates[k]);source=np.asarray(art.cell(k,'BAKED').convert('RGB'));colors={tuple(int(v) for v in c) for c in source[p[o]//16,p[o]%16]};assert not colors&forbidden
