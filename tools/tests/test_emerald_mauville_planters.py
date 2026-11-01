"""Source planter adjacency, continuous tray and narrow metadata correction."""
import json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns_mauville_planters as t
from tools.shape_fit.blue_pass.common import decode

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists():pytest.skip('Local exported source required')
    table=json.loads(t.BASELINE.read_text());art=t.EmeraldArt(t.BUNDLE);return table,art,t.rows_for('mauville'),t.generate(table,art)

def test_exact_three_keys_and_only_false_stack_metadata_removed(data):
    table,art,rows,out=data;assert set(out)=={rows[n-1]['key'] for n in (56,57,61)};geometry={'v','t','up','from'}
    for n in t.OWNED:
        k=rows[n-1]['key'];old={a:b for a,b in table['cells'][k].items() if a not in geometry};new={a:b for a,b in out[k].items() if a not in geometry}
        if n==57:assert old.pop('on')==rows[60]['key']
        assert old==new
    e=t.assemblies(art);assert len(e)==2 and len(e[0]['layout'])==2 and len(e[1]['layout'][0])==3

def test_long_tray_has_continuous_soil_side_rims_and_low_flowers(data):
    _,_,rows,out=data;upper,_=decode(out[rows[56]['key']]);lower,_=decode(out[rows[60]['key']]);whole=np.concatenate((upper,lower),axis=1)
    assert whole[4,15:17,2:14].all() and whole[:7,15:17,1:3].all() and whole[:7,15:17,13:15].all()
    assert not whole[12:].any() and whole[0].any()
    assert not whole[7:10,15:17,2:4].all() # air above the rim, not a wall across the join

def test_only_native_wood_soil_leaf_and_flower_materials(data):
    _,art,_,out=data;allowed=set(t.COLORS.values())
    for k,e in out.items():
        o,p=decode(e);rgb=np.asarray(art.cell(k,'BAKED').convert('RGB')).reshape(-1,3)[p[o]];assert set(map(tuple,rgb))<=allowed
        assert (115,197,165) not in set(map(tuple,rgb)) # source grass is not soil or foliage
