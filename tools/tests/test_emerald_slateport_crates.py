import hashlib,json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT)not in sys.path:sys.path.insert(0,str(ROOT))
from tools import model_gallery as gallery
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald import parts_model
from tools.shape_fit.emerald_pass import slateport_crates as t


@pytest.fixture(scope='module')
def data():
    art=gallery.EmeraldArt(t.BUNDLE);base=json.loads(t.BASELINE.read_text());return art,base,t.cell_updates(base,art),t.buildings(art)


def test_source_variants_and_exact_matching_order(data):
    art,base,cells,objects=data;assert len(cells)==4 and len(objects)==4
    assert all('3stack' in k for k in sorted(objects)[:2])
    assert [len(t.occurrences(art,k,v))for k,v in objects.items()]==[2,2,9,7]
    assert set(cells)=={t.rows_for('slateport')[n-1]['key']for n in t.OWNED}
    for k,e in cells.items():
        exclude={'v','t','up','from'};assert{k:v for k,v in e.items()if k not in exclude}=={k:v for k,v in base['cells'][k].items()if k not in exclude}
        o,_=decode(e);assert o.shape==(16,16,16)


def test_crate_piles_are_three_connected_boxes_on_one_lower_footprint(data):
    art,_,_,objects=data
    for k,e in objects.items():
        draw=np.asarray(art.drawing(t.layout(t.CASES[k]),'BAKED').convert('RGB'));o,c=parts_model.build(e,draw);stack='3stack'in k
        visible_height=24 if stack else 8
        assert e['height']==56 and e['preserveCarriers'] and e['preserveTerrain']
        if stack:assert not o[:,:16].any() and o[:24,16:].any()
        assert all(o[y].any()for y in range(visible_height))
        assert not o[visible_height:].any()
        rgb=set(map(tuple,c[o]));assert rgb<={(66,74,107),(140,115,74),(222,206,148),(173,165,107),(66,165,115),(0,214,156)}


def test_source_runtime_overlap_and_native_companion_catalogs(data):
    if not(t.OUT/'runtime-proof.json').exists()or not(t.OUT/'baseline-entities.json').exists():pytest.skip('Actual source fixture and native companions required')
    art,base,cells,objects=data;proof=json.loads((t.OUT/'runtime-proof.json').read_text());assert proof['matched_single_crates']==12 and proof['matched_three_crate_piles']==4 and proof['nested_single_patterns_correctly_suppressed']==4
    assert hashlib.sha256((t.OUT/'updates.json').read_bytes()).hexdigest()==proof['candidate_updates_sha256']
    definitions=json.loads((t.OUT/'baseline-entities.json').read_text());old=gallery.catalog(base,gallery.baseline_entities(definitions,hashlib.sha256(t.BASELINE.read_bytes()).hexdigest()),art,'buildings');current=json.loads((t.OUT/'fitted-proof-current.json').read_text());new=gallery.catalog(current,definitions,art,'buildings')
    assert set(objects)<={e['id']for e in old};assert set(objects)<={e['id']for e in new}
