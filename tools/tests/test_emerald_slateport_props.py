import json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns_slateport_props as t
from tools.shape_fit.blue_pass.common import decode

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists():pytest.skip('Local source required')
    table=json.loads(t.BASELINE.read_text());art=t.EmeraldArt(t.BUNDLE);return table,art,t.rows_for('slateport'),t.generate(table,art)

def test_exact_scope_metadata_and_three_source_assemblies(data):
    table,art,rows,out=data;assert set(out)=={rows[n-1]['key'] for n in t.OWNED};geometry={'v','t','up','from'}
    for k,e in out.items():assert {a:b for a,b in e.items() if a not in geometry}=={a:b for a,b in table['cells'][k].items() if a not in geometry}
    assert len(t.assemblies(art))==3

def test_open_hook_thin_board_and_no_pavement_or_grass_material(data):
    _,art,rows,out=data;hook,_=decode(out[rows[88]['key']]);assert hook[0].any() and not hook[7,8,9] and hook[11,8,9]
    board,_=decode(out[rows[107]['key']]);assert not board[:,:,:].any(axis=(0,2))[:7].any();assert not board[:,:,:].any(axis=(0,2))[11:].any();assert board[0].any() and not board[:2,7:11,4:12].any()
    for n in (18,89):
        k=rows[n-1]['key'];o,p=decode(out[k]);colors=set(map(tuple,np.asarray(art.cell(k,'BAKED').convert('RGB')).reshape(-1,3)[p[o]]));assert not colors&{(115,197,165),(206,206,222),(230,230,239)}
