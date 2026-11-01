"""Every real Rustboro fence join stays connected with source-native metal."""
import copy,json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns_rustboro_rails as t

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists():pytest.skip('Local exported source required')
    table=json.loads(t.BASELINE.read_text());old=copy.deepcopy(table);art=t.EmeraldArt(t.BUNDLE);updates=t.generate(table,art);assert table==old
    return table,art,t.rows_for('rustboro'),updates

def test_scope_metadata_native_metal_and_open_scrolls(data):
    table,art,rows,updates=data;assert len(updates)==31
    meta=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
    for k,e in updates.items():
        assert meta(e)==meta(table['cells'][k]);o,p=t.decode(e);assert len(o)==16
        rgb=np.asarray(art.cell(k,'BAKED').convert('RGB'));colors={tuple(int(v) for v in c) for c in rgb[p[o]//16,p[o]%16]};assert colors<=set(t.METAL)
    o,_=t.decode(updates[rows[35]['key']]);assert not o[8,8,4] and o[2,8,4] and o[14,8,4]

def test_every_actual_source_neighbor_meets(data):
    _,art,_,updates=data;proof=t.neighbor_proof(art);count=0
    for k,places in proof.items():
        o,_=t.decode(updates[k])
        for place in places:
            for direction,neighbor in place['fence_neighbors'].items():
                other,_=t.decode(updates[neighbor])
                a,b={'N':(o[:,0,:],other[:,-1,:]),'S':(o[:,-1,:],other[:,0,:]),'E':(o[:,:,-1],other[:,:,0]),'W':(o[:,:,0],other[:,:,-1])}[direction]
                assert (a&b).any(),(k,place,direction,neighbor);count+=1
    assert count>350

def test_complete_source_components_use_supported_sparse_layouts(data):
    _,art,_,updates=data;entities=t.assemblies(art);components=t.components(art);assert len(entities)==5
    all_members=set()
    for e,component in zip(entities,components):
        x,y=component['origin'];expected={tuple(p) for p in component['cells']};actual=set()
        grid=art.map_cells(art.maps['EM_RUSTBORO_CITY'])
        assert e['reference_layout']==[r[x:x+component['size'][0]] for r in grid[y:y+component['size'][1]]]
        for rz,row in enumerate(e['layout']):
            for rx,k in enumerate(row):
                if k:
                    assert k in updates and k==grid[y+rz][x+rx];actual.add((x+rx,y+rz))
        assert actual==expected and not actual&all_members;all_members|=actual
    assert len(all_members)==208
