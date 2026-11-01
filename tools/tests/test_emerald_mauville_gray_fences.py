"""Exact garden fence interfaces, endpoints and source-native metal."""
import json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns_mauville_gray_fences as t
from tools.shape_fit.blue_pass.common import decode

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists():pytest.skip('Local exported source required')
    table=json.loads(t.BASELINE.read_text());art=t.EmeraldArt(t.BUNDLE)
    return table,art,t.rows_for('mauville'),t.generate(table,art)

def test_exact_scope_metadata_and_source_complete_objects(data):
    table,art,rows,out=data;assert set(out)=={rows[n-1]['key'] for n in (111,144,145,180,191)}
    geometry={'v','t','up','from'}
    for k,e in out.items():assert {a:b for a,b in e.items() if a not in geometry}=={a:b for a,b in table['cells'][k].items() if a not in geometry}
    e=t.assemblies(art);assert [sum(k is not None for r in x['layout'] for k in r) for x in e]==[3,4,5]
    for x in e:
        p=x['occurrences'][0];g=art.map_cells(art.maps[p['map']]);ref=x['reference_layout']
        assert ref==[r[p['x']:p['x']+len(ref[0])] for r in g[p['y']:p['y']+len(ref)]]

def test_continuous_five_post_run_and_correct_endpoints(data):
    _,_,rows,out=data;occ={n:decode(out[rows[n-1]['key']])[0] for n in t.OWNED}
    whole=np.concatenate([occ[n] for n in (191,145,111)],axis=1)
    assert whole[7:9,4:36,2:4].all() and not whole[:,0,:].any() and not whole[:,40:,:].any()
    assert whole[0].any() and not whole[11:].any()
    horizontal=np.concatenate([occ[144]]*4,axis=2)
    assert horizontal[7:9,7:9,:].all()
    assert not horizontal[:,0].any() and not horizontal[:,-1].any()
    assert occ[180][7:9,-1,2:4].all() and not occ[180][:,0].any()

def test_native_gray_white_materials_exclude_source_grass(data):
    _,art,_,out=data;allowed={(132,132,140),(66,74,107),(255,255,255),(189,206,230)}
    for k,e in out.items():
        o,p=decode(e);pixels=np.asarray(art.cell(k,'BAKED').convert('RGB')).reshape(-1,3)[p[o]]
        assert set(map(tuple,pixels))<=allowed
