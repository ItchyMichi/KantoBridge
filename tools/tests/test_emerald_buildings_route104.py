"""Exact added templates, source walk headroom and full facade coverage."""
import copy,json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import buildings_route104 as t
from tools.shape_fit.emerald_pass.review import validate_building

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists():pytest.skip('Local exported source required')
    table=json.loads(t.BASELINE.read_text());old=copy.deepcopy(table);art=t.EmeraldArt(t.BUNDLE);entries=t.generate(table,art);assert table==old
    return table,art,entries

def test_exact_unique_source_patterns_and_warps(data):
    table,art,entries=data;assert len(entries)==2
    for ident,e in entries.items():
        case=t.CASES[ident];places=t.evidence(art,ident,e);assert len(places)==1 and places[0]['warps'][0]['to']==case['warp'];assert all(e['tiles']!=b['tiles'] for b in table['buildings']);validate_building(ident,e,e)

def test_walk_roof_projections_empty_below_eaves_and_doors_clear(data):
    _,art,entries=data
    for ident,e in entries.items():
        o,_=t.parts_model.build(e,np.asarray(art.drawing(t.layout_for(art,ident),'BAKED').convert('RGB')));places=t.evidence(art,ident,e)
        for cell in places[0]['cells']:
            if cell['collision']==0:
                x,z=cell['local'];assert not o[:29,z*16:z*16+16,x*16:x*16+16].any();assert o[29:,z*16:z*16+16,x*16:x*16+16].any()
        assert not o[2:23,55:63,32:48].any()
        assert o[23:35,24:52,o.shape[2]-3].all(), 'Side wall must meet roof without a floating lintel gap'

def test_all27_facade_keys_covered_by_complete_source_matches(data):
    from tools.shape_fit.emerald_pass import towns_rustboro_architecture as a
    table,art,_=data;proof=a.coverage(art,table);assert len(proof)==27
    for k,p in proof.items():assert p['fully_covered'] and len(p['occurrences'])==len(art.locations[k]);assert all(loc['buildings'] for loc in p['occurrences'])
