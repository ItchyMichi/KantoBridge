import hashlib
import json
import sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools import model_gallery as gallery
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import towns_slateport_edges as t


def test_exact_edge_scope_preserves_all_metadata_and_source_identity():
    base=json.loads(t.BASELINE.read_text());art=gallery.EmeraldArt(t.BUNDLE);patch=t.generate(base,art);rows=t.rows_for('slateport')
    assert set(patch)=={rows[n-1]['key'] for n in t.OWNED-t.RETAINED}
    assert sum(len(art.locations[rows[n-1]['key']])for n in t.OWNED)==125
    for k,e in patch.items():
        excluded={'v','t','up','from'}
        assert {n:v for n,v in e.items()if n not in excluded}=={n:v for n,v in base['cells'][k].items()if n not in excluded}
        o,_=decode(e);assert not o[4:].any()


def test_curb_returns_exclude_paving_and_shore_paint_excludes_water():
    art=gallery.EmeraldArt(t.BUNDLE);rows=t.rows_for('slateport');patch=t.generate(art=art)
    for n,count in [(19,512),(20,768),(21,768),(24,512),(88,768)]:
        o,p=decode(patch[rows[n-1]['key']]);assert o.sum()==count
        rgb=np.asarray(art.cell(rows[n-1]['key'],'BAKED').convert('RGB'))[p//16,p%16][o]
        assert set(map(tuple,rgb))<={(255,255,255),(165,165,165),(132,132,140)}
    for n in (50,51,52,53):
        k=rows[n-1]['key'];o,p=decode(patch[k]);rgb=np.asarray(art.cell(k,'BAKED').convert('RGB'))[p//16,p%16][o]
        assert set(map(tuple,rgb))<={(255,255,255),(165,165,165),(132,132,140),(132,90,90),(99,66,82)}


def test_source_runtime_proof_binds_candidate_and_all_occurrences():
    if not (t.OUT/'runtime-proof.json').exists():pytest.skip('Actual source fixture required')
    proof=json.loads((t.OUT/'runtime-proof.json').read_text())
    assert hashlib.sha256((t.OUT/'updates.json').read_bytes()).hexdigest()==proof['candidate_updates_sha256']
    assert hashlib.sha256((t.OUT/'fitted-proof-current.json').read_bytes()).hexdigest()==proof['current_table_sha256']
    assert sum(r['role']=='edge'for r in proof['occurrences'])==125
    assert sum(r['role']=='crate'for r in proof['occurrences'])==20
    for r in proof['occurrences']:
        if r['ordinal'] in (33,92):
            assert not r['original_baseline_fragments'] and not r['corrected_fragments']
            assert all(c['before_tile']==c['after_tile']for c in r['source_floor_columns'])
