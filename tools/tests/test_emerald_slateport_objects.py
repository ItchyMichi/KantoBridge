import copy,hashlib,json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import buildings_slateport_objects as t,towns_slateport_objects as f
from tools.shape_fit.emerald_pass.review import validate_building

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists():pytest.skip('Local exported source required')
    art=t.EmeraldArt(t.BUNDLE);table=json.loads(t.BASELINE.read_text());old=copy.deepcopy(table);entries=t.generate(table,art);assert table==old;models={k:t.parts_model.build(e,np.asarray(art.drawing(t.layout_for(art,t.CASES[k]),'BAKED').convert('RGB'))) for k,e in entries.items()};return table,art,entries,models

def test_exact_source_patterns_bounds_and_absent_warps(data):
    table,art,entries,_=data;assert len(entries)==2
    for k,e in entries.items():
        validate_building(k,e,e);assert not any(b['tiles']==e['tiles'] for b in table['buildings']);proof=t.evidence(art,k,e)[0];assert not proof['warps'];assert e['terrain']==[]
        raw=[c['collision'] for c in proof['cells']];assert raw==([7]*6 if k.endswith('_boat') else [0]*4+[7]*4)

def test_grounded_open_hull_seats_and_native_boat_materials(data):
    _,_,_,models=data;o,c=models['emerald_object_slateport_display_boat'];assert o[0,15:17,10:41].all();assert o[6,10:22,21:33].all();assert not o[7:9,10:22,21:33].any();assert not o[12,10:22,21:33].any();assert o[9:12,5:27,18:21].all();assert o[11:13,28:32,34:37].all();assert not o[:,0:3].any()
    assert set(map(tuple,c[o]))<={(66,74,107),(165,156,132),(115,115,123),(230,230,206),(189,189,156)}

def test_lighthouse_walk_projections_empty_with_supported_lantern(data):
    _,_,_,models=data;o,c=models['emerald_object_slateport_lighthouse'];assert not o[:,:32].any();assert o[0,48,16] and o[68,48,16];assert o[:63,48,16].all();assert o[33:36,34:62,15:17].all();assert not o[39:47,35:38,11:21].any();assert o[39:47,38,11:21].all();assert set(map(tuple,c[o]))<={(255,255,255),(197,181,222),(123,132,156),(41,49,90)}

def test_all_ten_original_fragments_have_exact_whole_coverage(data):
    table,art,_,_=data;proof=f.coverage(art,table);assert len(proof)==10 and sum(len(v['occurrences']) for v in proof.values())==10

def test_actual_native_original_and_current_object_catalogs_validate():
    from tools import model_gallery as gallery
    path=t.OUT/'baseline-entities.json'
    if not path.exists():pytest.skip('Exact Java source fixture required')
    baseline=json.loads(t.BASELINE.read_text());art=t.EmeraldArt(t.BUNDLE);entities=json.loads(path.read_text());ids=set(t.CASES);assert {e['id'] for e in entities}==ids
    old=gallery.catalog(baseline,gallery.baseline_entities(entities,hashlib.sha256(t.BASELINE.read_bytes()).hexdigest()),art,'buildings');before=[e for e in old if e['id'] in ids];assert len(before)==2 and all(e['placement_mode']=='source-map-baseline' for e in before)
    current=copy.deepcopy(baseline);current['buildings'].extend(t.generate().values());after=[e for e in gallery.catalog(current,entities,art,'buildings') if e['id'] in ids];assert len(after)==2 and all(len(e['occurrences'])==1 for e in after)
