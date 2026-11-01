"""Shallow ledges preserve movement and occupancy while correcting materials."""
import copy
import json
import sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns_fallarbor_ground as t

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists():pytest.skip('Local source export required')
    table=json.loads(t.BASELINE.read_text());before=copy.deepcopy(table);art=t.EmeraldArt(t.BUNDLE);updates=t.generate(table,art)
    assert table==before
    return table,art,t.rows_for('fallarbor'),updates

def test_exact_scope_and_preserved_metadata(data):
    table,art,rows,updates=data
    assert len(t.OWNED)==28 and set(t.decisions())==t.OWNED
    assert set(updates)=={rows[n-1]['key'] for n in t.CHANGED}
    meta=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
    for k,e in updates.items():assert meta(e)==meta(table['cells'][k])

def test_ledge_occupancy_native_top_and_clean_vertical_material(data):
    table,art,rows,updates=data
    for n in t.CHANGED:
        k=rows[n-1]['key'];old,_=t.decode(table['cells'][k]);o,p=t.decode(updates[k]);assert np.array_equal(old,o)
        y,z,x=np.indices(o.shape);top=o&~np.concatenate((o[1:],np.zeros_like(o[:1])))
        assert np.array_equal(p[top],(z*16+x).astype(np.uint8)[top])
        rgb=np.asarray(art.cell(k,'BAKED').convert('RGB'));colors={tuple(c) for c in rgb[p[o&~top]//16,p[o&~top]%16]}
        allowed={(99,66,82),(132,90,90),(189,148,140)} if n in t.ASH|t.BRIDGES else {(90,90,58),(115,115,74),(148,140,115)}
        assert colors<=allowed

def test_bridge_floor_and_source_layout_unchanged(data):
    table,art,rows,updates=data
    for n in t.BRIDGES:
        k=rows[n-1]['key'];assert {r['collision'] for r in t.witnesses(art,rows[n-1])}=={0}
        assert not any(updates[k].get(f) for f in ('walk','seat','on'))
    for e in t.assemblies(art):
        loc=e['occurrences'][0];g=art.map_cells(art.maps[loc['map']]);w=len(e['layout'][0]);d=len(e['layout'])
        assert e['layout']==[r[loc['x']:loc['x']+w] for r in g[loc['y']:loc['y']+d]]
