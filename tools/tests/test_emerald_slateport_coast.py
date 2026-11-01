import copy,hashlib,json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import buildings_slateport_coast as t,towns_slateport_architecture as f
from tools.shape_fit.emerald_pass.review import validate_building

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists():pytest.skip('Local exported source required')
    art=t.EmeraldArt(t.BUNDLE);table=json.loads(t.BASELINE.read_text());old=copy.deepcopy(table);entries=t.generate(table,art);assert table==old;models={k:t.parts_model.build(e,np.asarray(art.drawing(t.layout_for(art,t.CASES[k]),'BAKED').convert('RGB'))) for k,e in entries.items()};return table,art,entries,models

def test_unique_exact_patterns_and_true_distinct_warps(data):
    table,art,entries,_=data;assert len(entries)==2
    for k,e in entries.items():
        validate_building(k,e,e);assert not any(b['tiles']==e['tiles'] for b in table['buildings']);proof=t.evidence(art,k,e)[0];assert len(proof['warps'])==1 and proof['warps'][0]['to']==t.CASES[k]['to'];assert e['terrain']==[]

def test_seashore_walk_roof_headroom_and_both_entrances(data):
    _,_,_,models=data;o,_=models['emerald_object_route109_seashore_house'];assert not o[:24,:16].any();assert o[33:40,:16].any();assert not o[2:27,56:64,32:48].any();assert o[:27,55,32:48].any()
    o,_=models['emerald_object_slateport_harbor'];assert not o[2:26,85:96,48:64].any();assert o[:26,84,48:64].any()

def test_harbor_round_windows_and_roof_exclude_forest_material(data):
    _,_,_,models=data;o,c=models['emerald_object_slateport_harbor'];assert o[30:37,92:94,29:35].all();colors=set(map(tuple,c[o]));assert not colors&{(58,82,0),(58,140,49),(132,197,99),(181,255,132),(115,197,165),(66,181,132)}

def test_all30_original_cells_exactly_covered_without_edits(data):
    table,art,_,_=data;proof=f.coverage(art,table);assert len(proof)==30 and set(proof)=={f.rows_for('slateport')[n-1]['key'] for n in f.OWNED}

def test_actual_native_original_and_current_building_catalogs_validate():
    from tools import model_gallery as gallery
    path=t.OUT/'baseline-entities.json'
    if not path.exists():pytest.skip('Exact Java source fixture required')
    baseline=json.loads(t.BASELINE.read_text());art=t.EmeraldArt(t.BUNDLE);entities=json.loads(path.read_text());ids=set(t.CASES);assert {e['id'] for e in entities}==ids
    old=gallery.catalog(baseline,gallery.baseline_entities(entities,hashlib.sha256(t.BASELINE.read_bytes()).hexdigest()),art,'buildings');before=[e for e in old if e['id'] in ids];assert len(before)==2 and all(e['placement_mode']=='source-map-baseline' for e in before)
    current=copy.deepcopy(baseline);current['buildings'].extend(t.generate().values());after=[e for e in gallery.catalog(current,entities,art,'buildings') if e['id'] in ids];assert len(after)==2 and all(len(e['occurrences'])==1 for e in after)
