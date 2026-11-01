import json
import hashlib
import sys
from pathlib import Path
import numpy as np
ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import lilycove_museum_tread_repair as t


def test_tread_facing_preserves_warp_metadata_and_upper_passage():
    base = json.loads(t.BASELINE.read_text())
    new = t.generate(base)[t.KEY]
    old = base['cells'][t.KEY]
    geometry = {'v', 't', 'up', 'from'}
    assert {k: v for k, v in old.items() if k not in geometry} == {k: v for k, v in new.items() if k not in geometry} == {'class': 'voxels', 'walk': True}
    before, _ = decode(old)
    after, paint = decode(new)
    assert np.array_equal(before[1:], after[1:])
    assert not after[1:, 3:, :].any()
    assert after[0, 3:, :].all()
    assert np.array_equal(paint[0, 3:, :], np.arange(3*16, 16*16).reshape(13, 16))


def test_readable_four_orange_native_tread_bands_are_on_horizontal_facing():
    from tools import model_gallery as gallery
    art = gallery.EmeraldArt(t.BUNDLE)
    sprite = np.asarray(art.cell(t.KEY, 'BAKED').convert('RGB'))
    _, paint = decode(t.generate()[t.KEY])
    rgb = sprite[paint[0] // 16, paint[0] % 16].astype(int)
    orange = (rgb[:, :, 0] > rgb[:, :, 2] + 30) & (rgb[:, :, 0] > 130)
    assert np.flatnonzero(orange.any(1)).tolist() == [8, 10, 12, 13, 15]
    assert [orange[z].sum() for z in (8, 10, 12, 13, 15)] == [11, 12, 13, 13, 14]


def test_native_whole_catalog_preserves_floor_and_exact_geometry_guard():
    import pytest
    from tools import model_gallery as gallery
    if not (t.OUT / 'runtime-proof.json').exists():
        pytest.skip('Source-map fixture required')
    entities, original = t.native_entities()
    art = gallery.EmeraldArt(t.BUNDLE)
    before = gallery.catalog(json.loads(t.BASELINE.read_text()), gallery.baseline_entities(entities, hashlib.sha256(t.BASELINE.read_bytes()).hexdigest()), art, 'scenery')
    after = gallery.catalog(json.loads((t.OUT / 'fitted-proof-current.json').read_text()), entities, art, 'scenery')
    old = next(e for e in before if e['id'] == t.IDENT)
    new = next(e for e in after if e['id'] == t.IDENT)
    assert old['bounds'] == new['bounds']
    assert [f for f in old['fragments'] if f['key'].startswith('cube:')] == [f for f in new['fragments'] if f['key'].startswith('cube:')]
    assert original[t.IDENT]['layout'] == entities[0]['layout']
