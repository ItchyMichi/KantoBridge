"""Lilycove source flags, native materials, complete joins and supports."""
import copy
import json
import sys
from pathlib import Path
from collections import deque
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns_lilycove as t


@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists():pytest.skip('Local immutable export required')
    table=json.loads(t.BASELINE.read_text());art=t.EmeraldArt(t.BUNDLE);rows=t.rows_for('lilycove');before=copy.deepcopy(table);updates=t.generate(table,art)
    assert table==before
    return table,art,rows,updates


def connected(o):
    todo=deque([tuple(np.argwhere(o)[0])]);seen=set(todo)
    while todo:
        q=todo.popleft()
        for d in ((1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)):
            p=tuple(q[i]+d[i] for i in range(3))
            if all(0<=p[i]<o.shape[i] for i in range(3)) and o[p] and p not in seen:seen.add(p);todo.append(p)
    return len(seen)==int(o.sum())


def test_ownership_and_exact_metadata_exceptions(data):
    table,art,rows,updates=data;assert set(t.decisions())==set(range(1,57))
    assert set(updates)=={rows[n-1]['key'] for n in t.CHANGED}
    meta=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
    for n in t.CHANGED:
        k=rows[n-1]['key'];expected=meta(table['cells'][k])
        if n==25:expected.pop('on')
        if n==27:expected['walk']=True
        assert meta(updates[k])==expected
    k=rows[26]['key'];assert np.array_equal(t.decode(table['cells'][k])[0],t.decode(updates[k])[0])
    assert np.array_equal(t.decode(table['cells'][k])[1],t.decode(updates[k])[1])


def test_fence_blue_materials_and_complete_cell_joins(data):
    _,art,rows,updates=data
    models={n:t.decode(updates[rows[n-1]['key']])[0] for n in (4,5,6,7,8,52)}
    for n in models:
        k=rows[n-1]['key'];o,p=t.decode(updates[k]);rgb=np.asarray(art.cell(k,'BAKED').convert('RGB'));colors=rgb[p[o]//16,p[o]%16]
        assert np.all(colors[:,2]>colors[:,1])  # no green source ground
    assert connected(np.concatenate([models[4],models[5],models[6]],axis=2))
    assert connected(np.concatenate([models[7],models[4]],axis=1))
    assert connected(np.concatenate([models[8],models[6]],axis=1))
    assert connected(np.concatenate([models[52],models[8]],axis=1))


def test_lamp_and_feeder_are_grounded_connected_whole_objects(data):
    _,art,rows,updates=data
    lower=t.decode(updates[rows[13]['key']])[0];upper=t.decode(updates[rows[2]['key']])[0]
    whole=np.concatenate((lower,upper));assert whole[0].any() and connected(whole)
    assert updates[rows[2]['key']]['on']==rows[13]['key']
    feeder=t.decode(updates[rows[24]['key']])[0]
    assert connected(feeder) and feeder[0].sum()==16 and feeder[22].any()
    assert not feeder[23:].any() and not updates[rows[24]['key']].get('on')


def test_source_topology_proves_two_narrow_metadata_changes(data):
    _,art,rows,_=data;upper=t.witnesses(art,rows[24]);assert len(upper)==11 and {p['collision'] for p in upper}=={7}
    base_keys=[]
    for p in upper:
        g=art.map_cells(art.maps[p['map']]);base_keys.append(g[p['y']+1][p['x']])
    assert base_keys.count(rows[28]['key'])==10 and base_keys.count(rows[29]['key'])==1
    turn=t.witnesses(art,rows[26]);assert len(turn)==4 and {p['collision'] for p in turn}=={0}


def test_ground_and_cliff_profiles_match_adjoining_carriers(data):
    table,art,rows,updates=data
    for n in (9,10):
        o,_=t.decode(updates[rows[n-1]['key']]);assert o[:4,:,8].all() and not o[4:].any()
    for n in (11,12,28,29,30,34,36,39):
        o,_=t.decode(updates[rows[n-1]['key']]);assert o[0].all() and not o[1:].any()
    for n in (21,22):
        k=rows[n-1]['key'];assert np.array_equal(t.decode(table['cells'][k])[0],t.decode(updates[k])[0])
