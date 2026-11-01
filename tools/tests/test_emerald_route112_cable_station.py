"""Lower station exact matching, mirrored architecture and native path clearance."""
import copy,json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import building_route112_cable_station as t,towns_lavaridge_stations as f
from tools.shape_fit.emerald_pass.review import validate_building

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists():pytest.skip('Local exported source required')
    table=json.loads(t.BASELINE.read_text());old=copy.deepcopy(table);art=t.EmeraldArt(t.BUNDLE);e=t.generate(table,art)[t.IDENT];assert table==old
    o,c=t.parts_model.build(e,np.asarray(art.drawing(t.layout_for(art),'BAKED').convert('RGB')))
    return table,art,e,o,c

def test_exact_source_mirror_keeps_upper_template_and_both_lower_warps(data):
    table,art,e,_,_=data;validate_building(t.IDENT,e,e);proof=t.evidence(art,e)[0]
    assert proof['warps']==[{'to':'EM_ROUTE112_CABLE_CAR_STATION','warp':0,'x':28,'y':27},{'to':'EM_ROUTE112_CABLE_CAR_STATION','warp':1,'x':29,'y':27}]
    upper=next(b for b in table['buildings'] if b['id']==t.UPPER)
    assert e['parts'][0]['x']==[96-upper['parts'][0]['x'][1],96-upper['parts'][0]['x'][0]]
    assert not any(b['tiles']==e['tiles'] for b in table['buildings'])

def test_source_tree_and_walk_columns_remain_clear(data):
    _,art,e,o,_=data;proof=t.evidence(art,e)[0]
    assert e['terrain']==[[5,0]] and not o[:,:16,80:96].any()
    for cell in proof['cells']:
        if cell['collision']==0:
            x,z=cell['local'];assert not o[:24,z*16:z*16+16,x*16:x*16+16].any()
    assert not o[2:26,69:80,16:48].any() and o[:26,68,16:48].all()

def test_two_native_side_vents_and_gray_threshold(data):
    _,_,_,o,c=data
    for y in (18,32):
        assert o[y:y+3,46:62,86:88].all()
        assert (c[y:y+3,46:62,87]==(66,74,107)).all()
    assert (c[1,72:79,17:47]==(132,132,140)).all()

def test_all_35_source_keys_have_complete_station_coverage(data):
    table,art,_,_,_=data;proof=f.coverage(art,table)
    assert len(proof)==35 and sum(len(p['occurrences']) for p in proof.values())==51
    assert {b['id'] for p in proof.values() for q in p['occurrences'] for b in q['buildings']}=={t.IDENT,t.UPPER}
