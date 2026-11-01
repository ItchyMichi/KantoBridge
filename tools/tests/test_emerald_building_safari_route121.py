"""Source identity, clipping and entrance checks for the Safari exterior."""
import hashlib
import json
import sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import building_safari_route121 as gate


def test_native_footprint_and_material_rectangles():
    e=gate.generate()[gate.ID]
    assert len(e['tiles'])==12 and all(len(r)==10 for r in e['tiles'])
    assert e['terrain']==[]
    for part in e['parts']:
        x0,x1,z0,z1=gate.parts_model.bounds(part)
        assert 0<=x0<x1<=80 and 0<=z0<z1<=96
        assert 0<=part['y'][0]<part['y'][1]<=e['height']
        for u0,v0,u1,v1 in part.get('paint',{}).values():
            assert 0<=u0<u1<=80 and 0<=v0<v1<=96


def test_door_is_recessed_and_parapet_is_lower_at_clipped_north():
    e=gate.generate()[gate.ID]
    y,z,x=np.indices((e['height'],96,80));o=np.zeros(y.shape,bool)
    for part in e['parts']:
        mask=gate.parts_model.inside(part,x,y,z);o=o&~mask if part.get('carve') else o|mask
    assert o[12,85,40] and not o[12,93,40] and o[1,95,40]
    assert o[27,93,40] and o[43,94,40] and o[43,40,1]
    assert not o[43,0,40] and o[41,0,40]


def test_exact_unique_exported_template_and_warp():
    if not gate.BASELINE.exists() or not gate.BUNDLE.exists():pytest.skip('Local export required')
    table=json.loads(gate.BASELINE.read_text());e=gate.generate()[gate.ID];art=gate.EmeraldArt(gate.BUNDLE)
    assert all(v['id']!=gate.ID and v['tiles']!=e['tiles'] for v in table['buildings'])
    assert hashlib.sha256(art.drawing(gate.LAYOUT,'BAKED').tobytes()).hexdigest()==gate.SOURCE_RGBA_SHA256
    proof=gate.evidence(art,e);assert len(proof)==1
    assert proof[0]['warps']==[{'to':'EM_ROUTE121_SAFARI_ZONE_ENTRANCE','warp':2,'x':37,'y':5}]
    assert [c['collision'] for c in proof[0]['cells']].count(7)==29
    assert proof[0]['cells'][27]['collision']==113
