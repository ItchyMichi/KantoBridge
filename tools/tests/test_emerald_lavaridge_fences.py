"""Whole source fence seams and native metal, without terrain paint."""
import json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns_lavaridge_fences as t
from tools.shape_fit.blue_pass.common import decode

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists():pytest.skip('Local exported source required')
    table=json.loads(t.BASELINE.read_text());art=t.EmeraldArt(t.BUNDLE);return table,art,t.generate(table,art)

def test_exact_seven_keys_preserve_all_metadata_and_four_complete_runs(data):
    table,art,out=data;rows=t.rows_for('lavaridge');assert set(out)=={rows[n-1]['key'] for n in range(63,70)}
    geometry={'v','t','up','from'}
    for k,e in out.items():assert {a:b for a,b in e.items() if a not in geometry}=={a:b for a,b in table['cells'][k].items() if a not in geometry}
    e=t.assemblies(art);assert [sum(k is not None for r in x['layout'] for k in r) for x in e]==[23,7,2,2]
    assert max(max(len(x['layout']),len(x['layout'][0]))*2 for x in e)==20

def test_every_adjacent_source_fence_cell_has_a_real_rail_join(data):
    _,art,out=data;occ={k:decode(v)[0] for k,v in out.items()}
    for e in t.assemblies(art):
        layout=e['layout']
        for z,row in enumerate(layout):
            for x,k in enumerate(row):
                if k is None:continue
                if x+1<len(row) and row[x+1] is not None:assert (occ[k][:,:,-1]&occ[row[x+1]][:,:,0]).any()
                if z+1<len(layout) and layout[z+1][x] is not None:assert (occ[k][:,-1]&occ[layout[z+1][x]][:,0]).any()

def test_only_source_native_metal_and_grounded_narrow_posts(data):
    _,art,out=data;allowed={(132,132,140),(66,74,107),(255,255,255)}
    for k,e in out.items():
        o,p=decode(e);rgb=np.asarray(art.cell(k,'BAKED').convert('RGB')).reshape(-1,3)[p[o]]
        assert set(map(tuple,rgb))<=allowed and o[0].any() and not o[11:].any()
        assert o.sum()<400
