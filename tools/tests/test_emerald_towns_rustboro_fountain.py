"""Complete fountain seams, hollow basin and source-native material contract."""
import copy,json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns_rustboro_fountain as t

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists():pytest.skip('Local exported sources required')
    table=json.loads(t.BASELINE.read_text());old=copy.deepcopy(table);art=t.EmeraldArt(t.BUNDLE);updates=t.generate(table,art);assert table==old
    return table,art,t.rows_for('rustboro'),updates

def test_exact_source_assembly_and_metadata(data):
    table,art,rows,updates=data;entity=t.assemblies(art)[0];assert len(updates)==9
    meta=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
    for n,(x,z) in t.ROLES.items():
        k=rows[n-1]['key'];assert entity['layout'][z][x]==k;assert meta(table['cells'][k])==meta(updates[k]);places=t.witnesses(art,rows[n-1]);assert len(places)==1 and places[0]['collision']==7 and places[0]['elevation']==0

def test_whole_basin_has_continuous_rim_and_center_jet(data):
    _,_,rows,updates=data;whole=np.zeros((32,48,48),bool)
    for n,(x,z) in t.ROLES.items():
        o,_=t.decode(updates[rows[n-1]['key']]);whole[:len(o),z*16:z*16+16,x*16:x*16+16]=o
    assert np.array_equal(whole,t.fountain().occ)
    assert whole[0,24,24] and whole[24,24,24]
    assert whole[7,2,23] and whole[7,45,23] and whole[7,23,2] and whole[7,23,45]
    assert not whole[3:7,12,24].any() and whole[2,12,24]
    assert not whole[:,0,0].any() and not whole[:,47,47].any()

def test_native_materials_exclude_cream_pavement(data):
    _,art,rows,updates=data;allowed=set(t.STONE)|{t.WATER,t.JET}
    for n in t.OWNED:
        k=rows[n-1]['key'];o,p=t.decode(updates[k]);source=np.asarray(art.cell(k,'BAKED').convert('RGB'));colors={tuple(int(v) for v in c) for c in source[p[o]//16,p[o]%16]};assert colors<=allowed
