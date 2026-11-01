"""Physical tree halves, native projection policy and exact forest adjacency."""
import copy
import json
import sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns_fallarbor_trees as t

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists():pytest.skip('Local source export required')
    table=json.loads(t.BASELINE.read_text());before=copy.deepcopy(table);art=t.EmeraldArt(t.BUNDLE);updates=t.generate(table,art);assert table==before
    return table,art,t.rows_for('fallarbor'),updates

def test_scope_metadata_and_actual_root_pairs(data):
    table,art,rows,updates=data;assert len(updates)==23 and set(t.decisions())==t.OWNED
    meta=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
    for k,e in updates.items():assert meta(e)==meta(table['cells'][k])
    proof=t.physical_pairs(art);assert len(proof)==16
    for places in proof.values():assert {p['collision'] for p in places}=={7}
    partial=[p for pp in proof.values() for p in pp if not p['complete_pair']]
    assert sum(p['neighbor_key'] is not None for p in partial)==2  # exactly the two Workshop roof overlaps

def test_lower_halves_reconstruct_whole_tree_without_extra_projected_tree(data):
    _,_,rows,updates=data;whole=t.tree().occ
    for n in t.BASE_LEFT|t.BASE_RIGHT:
        qx=0 if n in t.BASE_LEFT else 1;o,p=t.decode(updates[rows[n-1]['key']]);assert np.array_equal(o[:36],whole[:,:,qx*16:qx*16+16]);assert not o[36:].any()
    assert whole[:18,7:11,14:18].all() and whole[0].any()
    assert whole[28:].any() and not whole[:7,:,:10].any() and not whole[:7,:,22:].any()

def test_native_materials_and_horizontal_projection_cells(data):
    _,art,rows,updates=data
    for n in t.OWNED:
        k=rows[n-1]['key'];o,p=t.decode(updates[k])
        if n in t.PROJECTIONS:
            assert o[0].all() and not o[1:].any();assert np.array_equal(p[0],np.arange(256,dtype=np.uint8).reshape(16,16))
            assert not any(updates[k].get(f) for f in ('walk','on','alsoOn','stackFit'))
        else:
            rgb=np.asarray(art.cell(k,'BAKED').convert('RGB'));colors={tuple(c) for c in rgb[p[o]//16,p[o]%16]};assert colors<=set(t.LEAVES[:3])|set(t.BARK)
