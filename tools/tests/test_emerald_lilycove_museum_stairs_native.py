import copy,hashlib,json,sys
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools import model_gallery as gallery
from tools.shape_fit.emerald_pass import lilycove_museum_stairs_native as t

@pytest.fixture(scope='module')
def data():
    if not (t.OUT/'runtime-proof.json').exists():pytest.skip('Exact native source proof required')
    return t.generate()

def test_retained_descriptor_and_warp_route_are_exact(data):
    entities,old=data;e=entities[0];original=old[t.IDENT]
    for k in original:
        if k!='note':assert e[k]==original[k]
    proof=json.loads((t.OUT/'runtime-proof.json').read_text());r=proof['occurrences'][0];assert r['source_collisions']==[[7,7,7],[7,114,7]];assert r['original_baseline_fragments']==r['corrected_fragments'];assert len(r['source_floor_columns'])==24
    center=[c for c in r['source_floor_columns'] if c['x'] in (2,3) and c['z'] in (2,3)];assert len(center)==4 and all(c['before_tile']==c['after_tile']==6936 and c['feet']==0 for c in center)

def test_actual_native_baseline_current_stair_catalogs_validate(data):
    entities,_=data;art=gallery.EmeraldArt(t.BUNDLE);baseline=json.loads(t.BASELINE.read_text());current=json.loads((t.OUT/'fitted-proof-current.json').read_text());old=gallery.catalog(baseline,gallery.baseline_entities(entities,hashlib.sha256(t.BASELINE.read_bytes()).hexdigest()),art,'scenery');new=gallery.catalog(current,entities,art,'scenery');old=next(e for e in old if e['id']==t.IDENT);new=next(e for e in new if e['id']==t.IDENT);assert old['placement_mode']=='source-map-baseline' and new['placement_mode']=='source-map-current';assert old['bounds']==new['bounds'];assert len(old['fragments'])==len(new['fragments']);assert sum(f['key'].startswith('cube:') and f['y']==0 for f in new['fragments'])==24
