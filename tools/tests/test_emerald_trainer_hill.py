"""Trainer Hill source identity, native cliff triangles and native path clearance."""
import copy,json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import building_trainer_hill as t
from tools.shape_fit.emerald_pass import towns_mauville_trainer_hill as f
from tools.shape_fit.emerald_pass.review import validate_building

def test_native_roof_sampling_uses_explicit_triangles_and_double_hip_ridge():
    z,x=np.indices((8,8));u=(x+.5)/8;v=(z+.5)/8
    assert np.allclose(t.roof_heights(0,16,False,False),8*(1-v))
    assert np.allclose(t.roof_heights(0,16,True,False),8*np.maximum(u-v,0))
    assert np.allclose(t.roof_heights(16,16,True,True),16*np.minimum(u,1-u))
    # A bilinear roof would incorrectly raise the flat SW triangle.
    assert t.roof_heights(0,16,True,False)[6,1]==0

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.PROOF.exists():pytest.skip('Local source and native column proof required')
    table=json.loads(t.BASELINE.read_text());original=copy.deepcopy(table);art=t.EmeraldArt(t.BUNDLE);e=t.generate(table,art)[t.IDENT];assert table==original
    draw=np.asarray(art.drawing(t.layout_for(art),'BAKED').convert('RGB'));o,c=t.parts_model.build(e,draw)
    return table,art,e,o,c

def test_unique_exact_source_native_cliff_exclusions_and_warp(data):
    table,art,e,_,_=data;validate_building(t.IDENT,e,e);assert not any(b['tiles']==e['tiles'] for b in table['buildings'])
    assert e['preserveTerrain'] is True
    evidence=t.evidence(art,e)[0];assert evidence['warps'][0]['x']==31 and evidence['warps'][0]['y']==113
    assert len(e['terrain'])==18 and all([x,6] in e['terrain'] for x in range(5))
    assert {tuple(c['local']) for c in evidence['cells'] if c['collision']==0}=={(4,3),(2,5),(3,5)}

def test_walk_projection_headroom_warp_aperture_and_terrain_clearance(data):
    _,_,e,o,_=data
    for x,z in ((4,3),(2,5),(3,5)):assert not o[:24,z*16:z*16+16,x*16:x*16+16].any()
    for x,z in e['terrain']:assert not o[:,z*16:z*16+16,x*16:x*16+16].any()
    assert not o[2:23,128:144,34:46].any() and o[:23,126,34:46].any()
    assert o[40:88,65,40].all() and o[88:94,55,15].all()

def test_exposed_mixed_cliff_edge_keeps_native_profile(data):
    _,_,_,o,_=data;native=t.native_foundation()
    # North edge of the mixed cliff/roof row lies outside the new hall body.
    expected=np.floor(native[16:18]+.5).astype(int)
    heights=o[:,16:18].sum(axis=0)
    assert np.array_equal(heights,expected)

def test_all_source_facade_cells_have_exact_unique_roles(data):
    _,art,_,_,_=data;proof=f.coverage(art)
    assert len(proof)==33 and sum(x['terrain_excluded'] for x in proof.values())==10
    assert all(x['occurrence']['map']=='EM_ROUTE111' for x in proof.values())
