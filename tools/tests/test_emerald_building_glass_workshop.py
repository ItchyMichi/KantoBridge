"""Source matching, native WALK space and door headroom for the workshop."""
import json
import sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import building_glass_workshop as t
from tools.shape_fit.emerald_pass.review import validate_building

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists():pytest.skip('Local source export required')
    table=json.loads(t.BASELINE.read_text());art=t.EmeraldArt(t.BUNDLE);e=t.generate(table,art)[t.IDENT]
    return table,art,e

def test_exact_source_occurrence_and_warp(data):
    table,art,e=data;places=t.evidence(art,e);assert len(places)==1
    assert places[0]['warps']==[{'to':'EM_ROUTE113_GLASS_WORKSHOP','warp':0,'x':33,'y':5}]
    assert {tuple(c['local']) for c in places[0]['cells'] if c['collision']==0}=={(0,0),(1,0),(2,0),(2,2)}
    assert all(e['tiles']!=b['tiles'] for b in table['buildings'])
    validate_building(t.IDENT,e,e)

def test_walk_projections_are_clear_below_overhead_roof(data):
    _,art,e=data;o,c=t.parts_model.build(e,np.asarray(art.drawing(t.layout_for(art),'BAKED').convert('RGB')))
    for x,z in ((0,0),(1,0),(2,0),(2,2)):
        assert not o[:28,z*16:(z+1)*16,x*16:(x+1)*16].any()
        assert o[28:,z*16:(z+1)*16,x*16:(x+1)*16].any()
    assert not o[2:23,57:63,16:32].any()
    assert o[:28,48:56,32:48].all()  # actual lower window facade remains grounded

def test_all_retained_fallarbor_facades_have_exact_whole_coverage(data):
    from tools.shape_fit.emerald_pass import towns_fallarbor_architecture as a
    table,art,_=data;proof=a.coverage(art,table)
    assert len(proof)==22 and all(p['fully_covered'] for p in proof.values())
    for k,p in proof.items():
        assert len(p['occurrences'])==len(art.locations[k])
        assert all(loc['buildings'] for loc in p['occurrences'])
