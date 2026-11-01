"""Exact six-place tree-house source, warp and native canopy guards."""
import json
import hashlib
from pathlib import Path
import sys
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import building_fortree_treehouse as h
from tools.tests.test_emerald_towns_lilycove import connected


def occupancy():
    e=h.generate()[h.ID];y,z,x=np.indices((52,48,48));o=np.zeros(y.shape,bool)
    for part in e['parts']:
        p=h.parts_model.inside(part,x,y,z);o=o&~p if part.get('carve') else o|p
    return o


def test_whole_connected_house_has_open_recess_and_threshold():
    o=occupancy();assert connected(o)
    assert o[12,34,24] and not o[12,43,24] and o[1,44,24]
    assert o[12,43,12] and o[12,43,35] and o[40,24,24]


def test_parts_stay_inside_exact_three_square_source():
    e=h.generate()[h.ID];assert e['terrain']==[]
    assert len(e['tiles'])==6 and all(len(row)==6 for row in e['tiles'])
    for part in e['parts']:
        x0,x1,z0,z1=h.parts_model.bounds(part);assert 0<=x0<x1<=48 and 0<=z0<z1<=48
        assert 0<=part['y'][0]<part['y'][1]<=52
        for x0,y0,x1,y1 in part.get('paint',{}).values():assert 0<=x0<x1<=48 and 0<=y0<y1<=48


def test_native_canopy_has_no_platform_or_door_material():
    if not h.BUNDLE.exists():pytest.skip('Local export required')
    e=h.generate()[h.ID];source=np.asarray(h.p.drawing(e,h.BUNDLE))
    for part in e['parts']:
        if part['shape']!='dome':continue
        for x0,y0,x1,y1 in part['paint'].values():
            pixels=source[y0:y1,x0:x1];assert np.all(pixels[:,:,1]>pixels[:,:,2])


def test_one_exact_source_preserves_all_six_original_destinations():
    if not h.BUNDLE.exists() or not h.BASELINE.exists():pytest.skip('Local export required')
    e=h.generate()[h.ID];art=h.EmeraldArt(h.BUNDLE);b=json.loads(h.BASELINE.read_text())
    assert all(v['tiles']!=e['tiles'] and v['id']!=h.ID for v in b['buildings'])
    assert hashlib.sha256(h.p.drawing(e,h.BUNDLE).tobytes()).hexdigest()==h.SOURCE_SHA256
    proof=h.evidence(art,e);assert len(proof)==6
    assert {r['warps'][0]['to'] for r in proof}=={*[f'EM_FORTREE_CITY_HOUSE{i}' for i in range(1,6)],'EM_FORTREE_CITY_DECORATION_SHOP'}
    for r in proof:
        assert [c['collision'] for c in r['cells']].count(7)==8
        assert r['cells'][7]['collision']==113
