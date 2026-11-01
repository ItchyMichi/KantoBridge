"""Exact Fortree palm topology, source paint, exclusions and rooted whole parts."""
import copy
import hashlib
import json
import sys
from pathlib import Path
from collections import deque
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import object_fortree_palms as t
from tools.shape_fit.blue_pass.common import decode

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists():pytest.skip('Local source export required')
    table=json.loads(t.BASELINE.read_text());before=copy.deepcopy(table);art=t.EmeraldArt(t.BUNDLE);cells=t.generate_cells(table,art);buildings=t.generate_buildings()
    assert table==before
    return table,art,cells,buildings

def test_exact_five_patterns_and_source_hashes(data):
    table,art,cells,buildings=data
    total=0
    for variant,layout in t.layouts().items():
        e=buildings['emerald_object_fortree_palm_'+variant];source=t.p.drawing(e,t.BUNDLE)
        assert hashlib.sha256(source.tobytes()).hexdigest()==t.SOURCES[variant][0]
        places=t.source_evidence(art,e,layout);assert len(places)==(3 if variant=='short' else 2);total+=len(places)
        assert not any(p['warps'] for p in places)
        for place in places:
            for c in place['cells']:assert c['collision']==(0 if variant=='long' and c['local']==[1,2] else 7)
        assert not any(old['tiles']==e['tiles'] or old['id']==e['id'] for old in table['buildings'])
    assert total==5

def test_all_nine_fallbacks_keep_metadata_and_fan_slices_join(data):
    table,art,cells,_=data;rows=t.rows_for('fortree');meta=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
    assert set(cells)=={rows[n-1]['key'] for n in t.OWNED}
    for k,e in cells.items():assert meta(e)==meta(table['cells'][k])
    from tools.tests.test_emerald_towns_lilycove import connected
    whole=np.concatenate([np.concatenate([decode(cells[rows[n-1]['key']])[0] for n in row],axis=2) for row in [(44,45,46),(49,50,51)]],axis=1)
    assert connected(whole)

def test_long_walk_projection_empty_and_every_component_rooted(data):
    _,_,_,buildings=data
    for e in buildings.values():
        source=t.p.drawing(e,t.BUNDLE);o,c=t.parts_model.build(e,np.asarray(source))
        if e['id'].endswith('long'):assert not o[:,32:48,16:32].any() and e['terrain']==[[1,2]]
        # Flood upward from every rooted solid: all canopy lobes must have support.
        seen=np.zeros_like(o);todo=deque(map(tuple,np.argwhere(o[0])))
        todo=deque((0,z,x) for z,x in todo)
        for q in todo:seen[q]=True
        while todo:
            q=todo.popleft()
            for d in ((1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)):
                p=tuple(q[i]+d[i] for i in range(3))
                if all(0<=p[i]<o.shape[i] for i in range(3)) and o[p] and not seen[p]:seen[p]=True;todo.append(p)
        assert np.array_equal(seen,o)

def test_whole_trunks_use_only_native_olive_material(data):
    _,_,_,buildings=data
    for variant in ('short','long'):
        e=buildings['emerald_object_fortree_palm_'+variant];o,c=t.parts_model.build(e,np.asarray(t.p.drawing(e,t.BUNDLE)))
        h=34 if variant=='long' else 18;colors=c[4:h-4,18:26,20:28][o[4:h-4,18:26,20:28]]
        assert len(colors)>0
        assert not np.any((colors[:,1]>colors[:,0]+30)&(colors[:,1]>colors[:,2]+60))
