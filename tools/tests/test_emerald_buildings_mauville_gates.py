"""Exact gate variants, water exclusion and native WALK entrance headroom."""
import copy,json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import buildings_mauville_gates as t
from tools.shape_fit.emerald_pass.review import validate_building

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists():pytest.skip('Local exported source required')
    table=json.loads(t.BASELINE.read_text());old=copy.deepcopy(table);art=t.EmeraldArt(t.BUNDLE);entries=t.generate(table,art);assert table==old
    return table,art,entries

def test_unique_exact_patterns_warps_and_bounds(data):
    table,art,entries=data;assert len(entries)==3
    for ident,e in entries.items():
        proof=t.evidence(art,ident,e);assert len(proof)==1 and len(proof[0]['warps'])==len(t.CASES[ident]['doors']);assert all(e['tiles']!=b['tiles'] for b in table['buildings']);validate_building(ident,e,e)

def test_walk_columns_clear_and_water_row_wholly_excluded(data):
    _,art,entries=data
    for ident,e in entries.items():
        o,_=t.parts_model.build(e,np.asarray(art.drawing(t.layout_for(art,ident),'BAKED').convert('RGB')))
        for cell in t.evidence(art,ident,e)[0]['cells']:
            x,z=cell['local'];column=o[:,z*16:z*16+16,x*16:x*16+16]
            if cell['collision']==0:assert not column[:24].any()
            if cell['terrain_excluded']:assert cell['collision']==41 and z==0 and not column.any()
        for col in t.CASES[ident]['doors']:
            assert not o[2:24,72:80,col*16+1:col*16+15].any()
            assert o[:24,71,col*16+1:col*16+15].any()

def test_roof_recess_and_source_native_coping(data):
    _,art,entries=data
    for ident,e in entries.items():
        o,c=t.parts_model.build(e,np.asarray(art.drawing(t.layout_for(art,ident),'BAKED').convert('RGB')));w=t.CASES[ident]['size'][0]*16
        assert o[34,35,20] and not o[35:,35,20].any()
        assert o[38,77:80,0:w].all()
        # Coping cap blocks must use the source's pale gray, never grass or
        # water from the thin background strip above the original roof.
        assert np.all(c[39][o[39]]==np.array([214,214,214]))

def test_paired_facades_have_complete_claimed_source_coverage(data):
    from tools.shape_fit.emerald_pass import towns_mauville_gate_facades as facades
    table,art,_=data;proof=facades.coverage(art,table)
    assert len(proof)==17 and sum(len(p['occurrences']) for p in proof.values())==87
    assert all(p['fully_covered'] and all(loc['buildings'] for loc in p['occurrences']) for p in proof.values())
    # These reused door/base keys also occur in still-unreviewed Trick House
    # and Route118 buildings, so coverage of the new gates is insufficient.
    assert 24 not in facades.OWNED and 41 not in facades.OWNED
