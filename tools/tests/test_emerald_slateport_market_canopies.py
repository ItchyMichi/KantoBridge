import copy,hashlib,json,sys
from pathlib import Path
import numpy as np
import pytest
from PIL import Image
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import buildings_slateport_market as t,towns_slateport_canopies as f
from tools.shape_fit.emerald_pass.review import validate_building

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists():pytest.skip('Local exported source required')
    art=t.EmeraldArt(t.BUNDLE);table=json.loads(t.BASELINE.read_text());old=copy.deepcopy(table);entries=t.generate(table,art);assert table==old;models={k:t.parts_model.build(e,np.asarray(art.drawing(t.layout_for(art,t.CASES[k]),'BAKED').convert('RGB'))) for k,e in entries.items()};return table,art,entries,models

def test_four_unique_missing_patterns_and_all_walk_low_clearance(data):
    table,art,entries,models=data;assert len(entries)==4;walks=0
    for k,e in entries.items():
        validate_building(k,e,e);assert not any(b['tiles']==e['tiles'] for b in table['buildings']);proof=t.evidence(art,k,e)[0];assert not proof['warps'];o,_=models[k]
        for cell in proof['cells']:
            if cell['collision']==0:
                x,z=cell['local'];assert not o[:26,z*16:(z+1)*16,x*16:(x+1)*16].any();walks+=1
    assert walks==42

def test_goods_exactly_reuse_source_local_reviewed_sculptures(data):
    _,art,_,models=data
    for ident,c in t.CASES.items():
        draw=np.asarray(art.drawing(t.layout_for(art,c),'BAKED').convert('RGB'));o,colors=models[ident]
        for x,mode in c['items']:
            sprite=Image.fromarray(draw[32:48,x*16:(x+1)*16]);m=t.goods.tray(sprite,True) if mode=='bottles' else t.goods.vessel(sprite,'green');assert np.array_equal(o[:16,32:48,x*16:(x+1)*16],m.occ);expected=np.asarray(sprite).reshape(-1,3)[m.paint];assert np.array_equal(colors[:16,32:48,x*16:(x+1)*16][m.occ],expected[m.occ])

def test_four_grounded_supports_only_in_solid_post_cells(data):
    _,_,_,models=data
    for ident,(o,colors) in models.items():
        width=t.CASES[ident]['w']*16
        for x in (9,width-13):
            for z in (34,43):assert o[:26,z:z+4,x:x+4].all()
        assert not o[:26,:32].any()

def test_all_ten_post_occurrences_have_complete_stall_coverage(data):
    table,art,_,_=data;proof=f.coverage(art,table);assert len(proof)==3 and sum(len(v['occurrences']) for v in proof.values())==10

def test_actual_native_original_and_current_canopy_catalogs_validate():
    from tools import model_gallery as gallery
    path=t.OUT/'baseline-entities.json'
    if not path.exists():pytest.skip('Exact Java source fixture required')
    baseline=json.loads(t.BASELINE.read_text());art=t.EmeraldArt(t.BUNDLE);entities=json.loads(path.read_text());ids=set(t.CASES);assert {e['id'] for e in entities}==ids
    old=gallery.catalog(baseline,gallery.baseline_entities(entities,hashlib.sha256(t.BASELINE.read_bytes()).hexdigest()),art,'buildings');before=[e for e in old if e['id'] in ids];assert len(before)==4 and all(e['placement_mode']=='source-map-baseline' for e in before)
    current=copy.deepcopy(baseline);current['buildings'].extend(t.generate().values());after=[e for e in gallery.catalog(current,entities,art,'buildings') if e['id'] in ids];assert len(after)==4 and all(len(e['occurrences'])==1 for e in after)
