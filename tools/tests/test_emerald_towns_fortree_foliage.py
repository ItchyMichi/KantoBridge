"""Source-specific Fortree foliage and complete Gym marker checks."""
import copy
import json
import sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns_fortree_foliage as t
from tools.tests.test_emerald_towns_lilycove import connected

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists():pytest.skip('Local immutable source export required')
    table=json.loads(t.BASELINE.read_text());old=copy.deepcopy(table);art=t.EmeraldArt(t.BUNDLE);updates=t.generate(table,art)
    assert table==old
    return table,art,t.rows_for('fortree'),updates

def test_ownership_and_no_metadata_changes(data):
    table,art,rows,updates=data;assert len(t.OWNED)==36 and len(t.CHANGED)==24 and set(t.decisions())==t.OWNED
    from tools.shape_fit.emerald_pass.towns_fortree_ground import OWNED as ground
    assert not t.OWNED&ground
    meta=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
    assert set(updates)=={rows[n-1]['key'] for n in t.CHANGED}
    for k,e in updates.items():assert meta(e)==meta(table['cells'][k])

def test_foliage_and_tree_materials_exclude_cliff_windows_and_ground(data):
    _,art,rows,updates=data
    for n in t.CHANGED-{53,56}:
        k=rows[n-1]['key'];o,p=t.decode(updates[k]);rgb=np.asarray(art.cell(k,'BAKED').convert('RGB'));colors=rgb[p[o]//16,p[o]%16]
        allowed=set(t.vegetation.LEAVES)|{(107,90,90)}
        assert set(map(tuple,colors))<=allowed
        assert o[0].any()

def test_marker_has_native_letters_no_grass_and_existing_vertical_link(data):
    _,art,rows,updates=data
    bottom=t.decode(updates[rows[55]['key']])[0];top=t.decode(updates[rows[52]['key']])[0];whole=np.concatenate((bottom,top))
    assert connected(whole) and whole[0].any() and whole[20].any() and not whole[21:].any()
    assert updates[rows[52]['key']]['on']==rows[55]['key']
    for n in (53,56):
        k=rows[n-1]['key'];o,p=t.decode(updates[k]);rgb=np.asarray(art.cell(k,'BAKED').convert('RGB'));colors=rgb[p[o]//16,p[o]%16]
        assert not np.any((colors[:,1]>colors[:,0])&(colors[:,1]>colors[:,2]))
    k=rows[55]['key'];o,p=t.decode(updates[k]);rgb=np.asarray(art.cell(k,'BAKED').convert('RGB'))
    assert np.array_equal(rgb[p[5:10,10,2:14]//16,p[5:10,10,2:14]%16],rgb[6:11,2:14][::-1])

def test_complete_hedge_meets_across_cell_edges(data):
    _,art,rows,updates=data
    oo=[t.decode(updates[rows[n-1]['key']])[0] for n in (57,58,59)]
    assert connected(np.concatenate(oo,axis=2))
    for o in oo:assert o[0].any() and not o[:,0,:].all()

def test_projected_cells_remain_native_walk_floor(data):
    _,art,rows,updates=data
    for n in (47,61,62,63,64):
        r=rows[n-1];assert {p['collision'] for p in t.witnesses(art,r)}=={0}
        assert not any(updates[r['key']].get(flag) for flag in ('walk','seat','on'))
