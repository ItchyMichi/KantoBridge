"""Market jar openings, grounded goods and complete exact placement coverage."""
import json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns_slateport_market as t
from tools.shape_fit.blue_pass.common import decode

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists():pytest.skip('Local exported source required')
    table=json.loads(t.BASELINE.read_text());art=t.EmeraldArt(t.BUNDLE);return table,art,t.rows_for('slateport'),t.generate(table,art)

def test_scope_source_metadata_and_all54_exact_placements(data):
    table,art,rows,out=data;assert set(out)=={rows[n-1]['key'] for n in t.OWNED};geometry={'v','t','up','from'}
    for n in t.OWNED:
        k=rows[n-1]['key'];assert {a:b for a,b in out[k].items() if a not in geometry}=={a:b for a,b in table['cells'][k].items() if a not in geometry};assert {p['collision'] for p in t.witnesses(art,rows[n-1])}=={7}
    assert len(t.assemblies(art))==3

def test_open_jars_have_true_dark_recess_and_grounded_broad_body(data):
    _,_,rows,out=data
    for n in (2,35):
        o,_=decode(out[rows[n-1]['key']]);assert o[0,8,8];assert o[5,8,8];assert not o[6:11,6:10,6:10].any();assert o[4,8,2:14].all();assert o[9].sum()>0

def test_every_object_is_rooted_and_uses_no_paving_material(data):
    _,art,_,out=data;forbidden={(206,206,222),(230,230,239),(148,140,165),(165,156,165),(197,181,222)}
    for k,e in out.items():
        o,p=decode(e);assert o[0].any();colors=set(map(tuple,np.asarray(art.cell(k,'BAKED').convert('RGB')).reshape(-1,3)[p[o]]));assert not colors&forbidden
        # Every occupied component connects through adjacent voxels to a grounded object.
        seen=np.zeros(o.shape,bool);seen[0]=o[0]
        for _ in range(64):
            old=seen.copy();seen[1:]|=old[:-1];seen[:-1]|=old[1:];seen[:,1:]|=old[:,:-1];seen[:,:-1]|=old[:,1:];seen[:,:,1:]|=old[:,:,:-1];seen[:,:,:-1]|=old[:,:,1:];seen&=o
            if np.array_equal(seen,old):break
        assert np.array_equal(seen,o)
