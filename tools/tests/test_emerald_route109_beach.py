import copy,hashlib,json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import buildings_route109_beach as t,towns_slateport_beach as f
from tools.shape_fit.emerald_pass.review import validate_building

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists():pytest.skip('Local exported source required')
    art=t.EmeraldArt(t.BUNDLE);table=json.loads(t.BASELINE.read_text());old=copy.deepcopy(table);entries=t.generate(table,art);assert table==old;models={k:t.parts_model.build(e,np.asarray(art.drawing(t.layout_for(art,t.CASES[k]),'BAKED').convert('RGB'))) for k,e in entries.items()};return table,art,entries,models

def test_five_exact_source_patterns_cover_38_noncolliding_objects(data):
    table,art,entries,_=data;assert len(entries)==5;count=0
    for k,e in entries.items():
        validate_building(k,e,e);assert not any(b['tiles']==e['tiles'] for b in table['buildings']);proof=t.evidence(art,k,e);count+=len(proof);assert all(c['collision']==0 and not c['terrain_excluded'] for p in proof for c in p['cells']);assert all(not p['warps'] for p in proof)
    assert count==38

def test_thin_umbrella_canopies_have_grounded_poles_and_no_shadow_mass(data):
    _,_,_,models=data
    for k,(o,c) in models.items():
        if 'umbrella' not in k:continue
        assert not o[:,:16].any();assert o[:34,31:33,15:17].all();assert o[:24].sum()==24*4;assert o[26:].any();assert not set(map(tuple,c[o]))&{(206,156,82),(222,206,132),(214,181,107),(239,230,165)}

def test_chair_open_frame_back_and_low_connected_mat(data):
    _,_,_,models=data;o,_=models['emerald_object_route109_folding_chair'];assert o[0,17:20,2:4].all() and o[0,36:39,12:14].all();assert not o[:6,25:35,4:12].any();assert o[7:9,25:39,4:12].all();assert o[18,15,4:12].all()
    o,c=models['emerald_object_route109_rolled_mat'];assert o[0,14:39,2:14].all();assert not o[2:,17:].any();assert o[4,13,3:13].all();assert not set(map(tuple,c[o]))&{(222,206,132),(214,181,107),(239,230,165)}

def test_twenty_original_cells_have_exact_complete_coverage(data):
    table,art,_,_=data;proof=f.coverage(art,table);assert len(proof)==20;assert sum(len(v['occurrences']) for v in proof.values())==156

def test_actual_native_original_and_current_beach_catalogs_validate():
    from tools import model_gallery as gallery
    path=t.OUT/'baseline-entities.json'
    if not path.exists():pytest.skip('Exact Java source fixture required')
    baseline=json.loads(t.BASELINE.read_text());art=t.EmeraldArt(t.BUNDLE);entities=json.loads(path.read_text());ids=set(t.CASES);assert {e['id'] for e in entities}==ids
    old=gallery.catalog(baseline,gallery.baseline_entities(entities,hashlib.sha256(t.BASELINE.read_bytes()).hexdigest()),art,'buildings');before=[e for e in old if e['id'] in ids];assert len(before)==5 and all(e['placement_mode']=='source-map-baseline' for e in before)
    current=copy.deepcopy(baseline);current['buildings'].extend(t.generate().values());after=[e for e in gallery.catalog(current,entities,art,'buildings') if e['id'] in ids];assert len(after)==5 and sum(len(e['occurrences']) for e in after)==38
