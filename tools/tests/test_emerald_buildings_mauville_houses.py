"""Missing source-specific houses, native WATER exclusions and source doors."""
import copy,json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import buildings_mauville_houses as t
from tools.shape_fit.emerald_pass.review import validate_building

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists():pytest.skip('Local exported source required')
    table=json.loads(t.BASELINE.read_text());old=copy.deepcopy(table);art=t.EmeraldArt(t.BUNDLE);entries=t.generate(table,art);assert table==old
    return table,art,entries

def test_unique_source_patterns_and_exact_warps(data):
    table,art,entries=data;assert len(entries)==2
    for ident,e in entries.items():
        validate_building(ident,e,e);assert all(e['tiles']!=b['tiles'] for b in table['buildings']);proof=t.evidence(art,ident,e);assert len(proof)==1
        assert len(proof[0]['warps'])==(1 if ident.endswith('trick_house') else 0)

def test_trick_house_two_roofs_water_exclusion_and_door(data):
    _,art,entries=data;ident='emerald_object_route110_trick_house';e=entries[ident];o,c=t.parts_model.build(e,np.asarray(art.drawing(t.layout_for(art,ident),'BAKED').convert('RGB')))
    assert not o[:,:16,:16].any() and not o[:,:16,64:].any()
    assert o[65,35,40] and not o[50:,:,0:16].any() and not o[50:,:,64:].any()
    assert o[:49,45,40].all() # raised central roof has a grounded body
    assert not o[2:25,88:96,33:47].any() and o[:25,87,33:47].any()

def test_doorless_house_north_walk_headroom_and_solid_front(data):
    _,art,entries=data;ident='emerald_object_route118_doorless_house';e=entries[ident];o,_=t.parts_model.build(e,np.asarray(art.drawing(t.layout_for(art,ident),'BAKED').convert('RGB')))
    assert not o[:29,:16,:].any() and o[32:,:16,:].any()
    assert o[:29,59,16:48].all() # no invented opening below the window

def test_all_paired_facades_covered_without_owner_overlap(data):
    from tools.shape_fit.emerald_pass import towns_mauville_house_facades as f,towns_mauville_gate_facades as g,towns_mauville_bikes as b
    table,art,_=data;proof=f.coverage(art,table);assert len(proof)==25
    assert not f.OWNED & g.OWNED and not f.OWNED & b.OWNED
    assert all(p['fully_covered'] and all(loc['buildings'] for loc in p['occurrences']) for p in proof.values())
