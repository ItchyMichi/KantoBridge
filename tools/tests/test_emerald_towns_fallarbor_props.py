"""Native materials and open portal policy for Fallarbor props."""
import copy
import json
import sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns_fallarbor_props as t

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists():pytest.skip('Local source export required')
    table=json.loads(t.BASELINE.read_text());before=copy.deepcopy(table);art=t.EmeraldArt(t.BUNDLE);updates=t.generate(table,art);assert table==before
    return table,art,t.rows_for('fallarbor'),updates

def test_scope_metadata_and_no_changed_warp(data):
    table,art,rows,updates=data;assert len(t.OWNED)==14 and len(updates)==11
    assert set(t.decisions())==t.OWNED
    meta=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
    for key,e in updates.items():assert meta(e)==meta(table['cells'][key])
    assert {r['collision'] for r in t.witnesses(art,rows[5])}=={7}
    assert {r['collision'] for r in t.witnesses(art,rows[32])}=={113}
    assert {r['collision'] for r in t.witnesses(art,rows[97])}=={114}

def test_boulders_grounded_without_background_material(data):
    _,art,rows,updates=data
    for n in t.ROCKS|t.CLUSTERS:
        k=rows[n-1]['key'];o,p=t.decode(updates[k]);assert o[0].any()
        # All columns reach ground: no unsupported ring, blue shadow foot,
        # or hourglass lower profile inherited from the old projection.
        assert not (o[1:]&~o[:-1]).any()
        rgb=np.asarray(art.cell(k,'BAKED').convert('RGB'));colors={tuple(c) for c in rgb[p[o]//16,p[o]%16]};assert colors<=set(t.STONE)

def test_closed_recess_and_narrow_sign(data):
    _,art,rows,updates=data;o,_=t.decode(updates[rows[5]['key']]);assert o[:,:,0].all() and o[:,:,:4].all()
    assert not o[5:11,14:16,5:11].any() and o[5:11,13,5:11].all()
    o,_=t.decode(updates[rows[11]['key']]);assert o[:6,7:10,7:9].all()
    assert not o[:6,:,:7].any() and not o[:6,:,9:].any()
    o,p=t.decode(updates[rows[11]['key']]);rgb=np.asarray(art.cell(rows[11]['key'],'BAKED').convert('RGB'))
    assert not any(tuple(c) in {(173,173,140),(206,206,165),(132,132,140)} for c in rgb[p[o]//16,p[o]%16])
