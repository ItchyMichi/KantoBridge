"""Exact bath topology, source coverage and native material checks."""
import json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns_lavaridge_garden as t
from tools.shape_fit.blue_pass.common import decode

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists():pytest.skip('Local exported source required')
    table=json.loads(t.BASELINE.read_text());art=t.EmeraldArt(t.BUNDLE);return table,art,t.rows_for('lavaridge'),t.generate(table,art)

def test_exact_keys_and_only_false_bath_stack_removed(data):
    table,art,rows,out=data;assert set(out)=={rows[n-1]['key'] for n in (8,9,70,73)};geometry={'v','t','up','from'}
    for n in t.CHANGED:
        k=rows[n-1]['key'];old={a:b for a,b in table['cells'][k].items() if a not in geometry};new={a:b for a,b in out[k].items() if a not in geometry}
        if n==70:assert old.pop('on')==rows[72]['key']
        assert old==new
    entities=t.assemblies(art);assert len(entities[0]['occurrences'])==2
    for n in (70,73):assert {p['collision'] for p in t.witnesses(art,rows[n-1])}=={0}

def test_bath_is_shallow_connected_oval_without_background(data):
    _,art,rows,out=data;a,ap=decode(out[rows[69]['key']]);b,bp=decode(out[rows[72]['key']]);whole=np.concatenate((a,b),axis=1)
    assert whole[0,15:17,2:14].all();assert not whole[2:].any();assert not whole[:,:,0].any();assert not whole[:,:5].any();assert not whole[:,25:].any()
    for n in (70,73):
        k=rows[n-1]['key'];o,p=decode(out[k]);colors=set(map(tuple,np.asarray(art.cell(k,'BAKED').convert('RGB')).reshape(-1,3)[p[o]]));assert colors<=set(t.COLORS.values())

def test_flower_materials_and_whole_building_coverage(data):
    table,art,rows,out=data
    for n in (8,9):
        k=rows[n-1]['key'];o,p=decode(out[k]);colors=set(map(tuple,np.asarray(art.cell(k,'BAKED').convert('RGB')).reshape(-1,3)[p[o]]));assert (255,197,148) in colors and (206,66,82) in colors;assert (115,197,165) not in colors
    proof=t.coverage(art,json.loads(t.LIVE.read_text()));assert set(proof)=={rows[n-1]['key'] for n in (71,74,76,77)}
