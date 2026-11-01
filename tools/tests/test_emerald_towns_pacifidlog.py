"""Pacifidlog scope, movement preservation and source-specific sculpture."""
import copy
import json
import sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns_pacifidlog as t

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists():pytest.skip('Local source export required')
    table=json.loads(t.BASELINE.read_text());before=copy.deepcopy(table);art=t.EmeraldArt(t.BUNDLE);updates=t.generate(table,art)
    assert table==before
    return table,art,t.rows_for(t.GROUP),updates

def test_scope_and_all_source_movement_metadata_preserved(data):
    table,art,rows,updates=data;meta=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
    assert len(t.decisions())==54 and len(updates)==31
    assert set(updates)=={rows[n-1]['key'] for n in t.CHANGED}
    for key,e in updates.items():assert meta(e)==meta(table['cells'][key])
    assert all(t.witnesses(art,row) for row in rows)

def test_native_horizontal_top_paint_and_unchanged_footprint(data):
    table,art,rows,updates=data
    for n in t.SURFACES:
        k=rows[n-1]['key'];old,_=t.decode(table['cells'][k]);o,p=t.decode(updates[k]);assert np.array_equal(o,old)
        top=o&~np.concatenate((o[1:],np.zeros_like(o[:1])))
        y,z,x=np.indices(o.shape);assert np.array_equal(p[top],(z*16+x)[top])
        assert 'walk' not in updates[k]

def test_stone_has_depth_rooted_components_and_no_floor_palette(data):
    _,art,rows,updates=data
    from collections import deque
    for n in t.ROCKS:
        key=rows[n-1]['key'];o,p=t.decode(updates[key]);assert o[0].any() and o.sum()>500
        assert len(set(o.sum(axis=(0,2))))>3
        seen=np.zeros_like(o);todo=deque((0,z,x) for z,x in np.argwhere(o[0]))
        for q in todo:seen[q]=True
        while todo:
            q=todo.popleft()
            for d in ((1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)):
                v=tuple(q[i]+d[i] for i in range(3))
                if all(0<=v[i]<o.shape[i] for i in range(3)) and o[v] and not seen[v]:seen[v]=True;todo.append(v)
        assert np.array_equal(seen,o)
        rgb=np.asarray(art.cell(key,'BAKED').convert('RGB'));colors={tuple(v) for v in rgb[p[o]//16,p[o]%16]}
        forbidden={(214,181,107),(115,115,148),(132,132,165),(165,140,115)}
        assert not colors&forbidden

def test_native_pillar_stack_interfaces_join(data):
    _,_,rows,updates=data
    for lower,upper in ((29,31),(30,32)):
        lo=updates[rows[lower-1]['key']];up=updates[rows[upper-1]['key']]
        assert up['on']==rows[lower-1]['key']
        a,_=t.decode(lo);b,_=t.decode(up);assert np.array_equal(a[15],b[0])
        assert a[:,3:13,:].all() and b[:14,3:13,:].all()
