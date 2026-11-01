import hashlib
import json
import sys
from pathlib import Path
import pytest
ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from tools import model_gallery as gallery
from tools.shape_fit.emerald_pass import new_mauville_switches_native as t


@pytest.fixture(scope='module')
def entities():
    if not (t.OUT / 'runtime-proof.json').exists():
        pytest.skip('Exact native source proof required')
    return t.generate()


def test_all_ten_source_switches_keep_finite_floor_states(entities):
    assert len(entities) == 3
    assert all(len(e['occurrences']) == 1 for e in entities)
    proof = json.loads((t.OUT / 'runtime-proof.json').read_text())
    assert len(proof['occurrences']) == 10
    states = [r['story_site'] for r in proof['occurrences'] if r['story_site']['type'] == 'CELL']
    assert len(states) == 4 and all(r['states'][0]['height'] == 0 for r in states)
    assert sum(r['story_site']['type'] == 'OBJECT' for r in proof['occurrences']) == 6


def test_native_catalogs_draw_colored_floor_without_fitted_slabs(entities):
    art = gallery.EmeraldArt(t.BUNDLE)
    baseline = json.loads(t.BASELINE.read_text())
    current = json.loads((t.OUT / 'fitted-proof-current.json').read_text())
    old = gallery.catalog(baseline, gallery.baseline_entities(entities, hashlib.sha256(t.BASELINE.read_bytes()).hexdigest()), art, 'scenery')
    new = gallery.catalog(current, entities, art, 'scenery')
    for entity in entities:
        before = next(e for e in old if e['id'] == entity['id'])
        after = next(e for e in new if e['id'] == entity['id'])
        assert before['bounds'] == after['bounds'] == [4, 1, 2]
        assert before['fragments'] == after['fragments']
        assert len(after['fragments']) == 8
        assert all(f['key'].startswith('cube:') and f['y'] == 0 for f in after['fragments'])
