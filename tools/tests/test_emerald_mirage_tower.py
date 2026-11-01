"""Exact Mirage Tower footprint, projection routing and source-preserving layers."""
import copy,json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import building_mirage_tower as t
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass.review import validate_building,validate_cell

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists():pytest.skip('Local exported source required')
    table=json.loads(t.BASELINE.read_text());old=copy.deepcopy(table);art=t.EmeraldArt(t.BUNDLE);e=t.generate(table,art)[t.ID];cells=t.generate_cells(table,art);assert table==old
    return table,art,e,cells

def test_exact_unique_template_and_warp(data):
    table,art,e,_=data;validate_building(t.ID,e,e);assert not any(e['tiles']==b['tiles'] for b in table['buildings']);proof=t.evidence(art,e)[0];assert proof['warps']==[{'to':'EM_MIRAGE_TOWER_1F','warp':0,'x':19,'y':58}]
    assert all(c['collision']==0 and c['elevation']==3 for c in proof['cells'] if c['terrain_excluded'])

def test_tower_mass_grounding_lower_footprint_and_entrance(data):
    _,art,e,_=data;o,_=t.parts_model.build(e,np.asarray(art.drawing(t.layout_for(art),'BAKED').convert('RGB')))
    assert not o[:,:64,:].any() and o[:85,80,24].all()
    assert o[50,80].sum()>o[60,80].sum() # upper drum smaller than lower collar
    assert not o[2:24,88:96,17:31].any() and o[:24,87,17:31].any()

def test_twelve_walk_projections_are_horizontal_with_exact_metadata(data):
    table,art,e,cells=data;rows=t.rows_for('mauville');assert set(cells)=={rows[n-1]['key'] for n in t.OWNED}
    for k,new in cells.items():
        validate_cell(k,table['cells'][k],new);o,p=decode(new);assert o[0].all() and not o[1:].any();assert np.array_equal(p[0],np.arange(256,dtype=np.uint8).reshape(16,16))
        assert not any(new.get(flag) for flag in ('on','walk','alsoOn','stackFit'))
