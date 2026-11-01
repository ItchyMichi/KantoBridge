import copy,hashlib,json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import building_abandoned_ship as t,towns_slateport_ship as f
from tools.shape_fit.emerald_pass.review import validate_building

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists():pytest.skip('Local exported source required')
    art=t.EmeraldArt(t.BUNDLE);table=json.loads(t.BASELINE.read_text());old=copy.deepcopy(table);entries=t.generate(table,art);assert table==old;e=entries[t.IDENT];model=t.parts_model.build(e,np.asarray(art.drawing(t.layout_for(art),'BAKED').convert('RGB')));return table,art,e,model

def test_exact_ship_source_warp_and_sea_rock_exclusions(data):
    table,art,e,_=data;validate_building(t.IDENT,e,e);assert not any(b['tiles']==e['tiles'] for b in table['buildings']);proof=t.evidence(art,e)[0];assert proof['warps']==[{'to':'EM_ABANDONED_SHIP_DECK','warp':0,'x':29,'y':6}];assert len(e['terrain'])==7
    water=[c for c in proof['cells'] if c['collision']==41];assert len(water)==3 and all(c['terrain_excluded'] for c in water);assert next(c for c in proof['cells'] if c['local']==[5,0])['terrain_excluded']

def test_ship_walk_overhang_headroom_and_connected_warp_threshold(data):
    _,_,e,(o,c)=data;assert not o[:38,:16].any();assert o[38:43,:16].any();assert o[0,40:58,32:48].all();assert not o[2:,48:64,32:48].any()
    for x,z in e['terrain']:assert not o[:,z*16:(z+1)*16,x*16:(x+1)*16].any()
    assert set(map(tuple,c[o]))<={(132,132,90),(189,189,156),(230,230,206),(165,156,132),(66,74,107),(115,115,123),(99,148,123)}

def test_distinct_roofs_windows_and_twelve_exact_source_fragments(data):
    table,art,_,(o,c)=data;assert not o[38:43,31:37,47:52].any();assert o[37,31:37,47:52].all();assert o[35,19:42,65].all();assert o[26:30,43,30:33].all();proof=f.coverage(art,table);assert len(proof)==12 and sum(len(v['occurrences']) for v in proof.values())==12

def test_actual_native_original_and_current_ship_catalogs_validate():
    from tools import model_gallery as gallery
    path=t.OUT/'baseline-entities.json'
    if not path.exists():pytest.skip('Exact Java source fixture required')
    baseline=json.loads(t.BASELINE.read_text());art=t.EmeraldArt(t.BUNDLE);entities=json.loads(path.read_text());assert [e['id'] for e in entities]==[t.IDENT]
    old=gallery.catalog(baseline,gallery.baseline_entities(entities,hashlib.sha256(t.BASELINE.read_bytes()).hexdigest()),art,'buildings');before=[e for e in old if e['id']==t.IDENT];assert len(before)==1 and before[0]['placement_mode']=='source-map-baseline'
    current=copy.deepcopy(baseline);current['buildings'].extend(t.generate().values());after=[e for e in gallery.catalog(current,entities,art,'buildings') if e['id']==t.IDENT];assert len(after)==1 and len(after[0]['occurrences'])==1
