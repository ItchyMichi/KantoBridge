"""Native deck source orientation, grounded fallback and WALK-only scope."""
import json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns_mauville_decks as t
from tools.shape_fit.emerald_pass.review import validate_cell

def test_source_oriented_grounded_slab_and_native_walk_only():
    if not t.BASELINE.exists():pytest.skip('Local exported source required')
    table=json.loads(t.BASELINE.read_text());art=t.EmeraldArt(t.BUNDLE);rows=t.rows_for('mauville');updates=t.generate(table,art);assert len(updates)==3
    for n in t.OWNED:
        k=rows[n-1]['key'];entry=updates.get(k,table['cells'][k]);touched=t.witnesses(art,rows[n-1]);assert all(p['collision']==0 for p in touched);assert not any(entry.get(f) for f in ('on','walk','alsoOn','stackFit'));validate_cell(k,table['cells'][k],entry)
        o,p=t.decode(entry);h=2 if n in t.CHANGED else 1;assert o[:h].all() and not o[h:].any();rgb=np.asarray(art.cell(k,'BAKED').convert('RGB'));assert np.array_equal(rgb.reshape(-1,3)[p[h-1]],rgb)

def test_native_requests_match_exact_exported_rectangles():
    if not t.BRIDGE_PROOF.exists():pytest.skip('Local native fixture required')
    art=t.EmeraldArt(t.BUNDLE);entities=t.assemblies(art);assert len(entities)==3
    assert [(len(e['layout'][0]),len(e['layout'])) for e in entities]==[(16,9),(7,5),(24,7)]
