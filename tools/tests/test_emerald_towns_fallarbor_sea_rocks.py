"""Complete sea outcrop seams, grounding and source-only material checks."""
import copy
import json
import sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns_fallarbor_sea_rocks as t

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists():pytest.skip('Local source export required')
    table=json.loads(t.BASELINE.read_text());before=copy.deepcopy(table);art=t.EmeraldArt(t.BUNDLE);updates=t.generate(table,art);assert table==before
    return table,art,t.rows_for('fallarbor'),updates

def test_all_fifteen_wholes_and_source_metadata(data):
    table,art,rows,updates=data;assert len(updates)==4;entity=t.assemblies(art)[0];assert len(entity['occurrences'])==15
    meta=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
    for n in t.OWNED:
        k=rows[n-1]['key'];assert meta(updates[k])==meta(table['cells'][k]);assert len(art.locations[k])==15
        assert {r['collision'] for r in t.witnesses(art,rows[n-1])}=={7}

def test_four_quarters_reconstruct_one_grounded_solid_without_trench(data):
    _,_,rows,updates=data;out=np.zeros((32,32,32),bool)
    for n,(x,z) in t.ROLES.items():
        o,p=t.decode(updates[rows[n-1]['key']]);out[:,z*16:z*16+16,x*16:x*16+16]=o
    assert np.array_equal(out,t.whole().occ)
    assert not (out[1:]&~out[:-1]).any()
    assert out[:24,15:17,15:17].all()
    assert np.array_equal(out[:,:,15],out[:,:,16]) and np.array_equal(out[:,15,:],out[:,16,:])

def test_no_water_or_gray_shadow_on_stone(data):
    _,art,rows,updates=data
    for n in t.OWNED:
        k=rows[n-1]['key'];o,p=t.decode(updates[k]);rgb=np.asarray(art.cell(k,'BAKED').convert('RGB'));colors={tuple(c) for c in rgb[p[o]//16,p[o]%16]}
        assert colors<=set(t.COMMON)|{(173,173,140),(206,206,165)}
