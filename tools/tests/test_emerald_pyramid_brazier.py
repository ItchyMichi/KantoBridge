"""Battle Pyramid flame brazier: one connected stacked object, open cage, native paint only."""
import json, sys
from pathlib import Path
import numpy as np
import pytest
ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path: sys.path.insert(0, str(ROOT))
from tools.shape_fit.emerald_pass import pyramid_brazier as t
from tools.shape_fit.emerald_pass import review
from tools.shape_fit.blue_pass.common import decode


@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists(): pytest.skip('Local exported source required')
    table = json.loads(t.BASELINE.read_text(encoding='utf-8-sig')); art = t.EmeraldArt(t.BUNDLE)
    out = t.generate(table, art)
    whole = np.concatenate([decode(out[t.BASE])[0][:16], decode(out[t.FLAME])[0][:16]])
    return table, art, out, whole


def test_exact_stack_keys_and_metadata_including_on_link(data):
    table, _, out, _ = data
    assert set(out) == {t.BASE, t.FLAME} and out[t.FLAME]['on'] == t.BASE
    for k, e in out.items():
        review.validate_cell(k, table['cells'][k], e)          # the merger's own guard
        assert len(e.get('up', [])) == 0


def test_one_connected_grounded_object(data):
    *_, whole = data
    seen = np.zeros_like(whole); stack = [(0, z, x) for z, x in np.argwhere(whole[0])]
    for p in stack: seen[p] = True
    while stack:
        y, z, x = stack.pop()
        for dy, dz, dx in ((1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)):
            q = (y+dy, z+dz, x+dx)
            if all(0 <= q[i] < whole.shape[i] for i in range(3)) and whole[q] and not seen[q]:
                seen[q] = True; stack.append(q)
    # Only the drawn tip's last voxel steps sideways (edge contact, as drawn); all else is face-connected.
    assert seen[:25].sum() == whole[:25].sum()
    assert whole[25].any() and not whole[26:].any()


def test_open_windows_lattice_recesses_and_feet(data):
    *_, whole = data
    for z, x in ((15, 7), (0, 8), (7, 15), (8, 0)):
        assert not whole[10, z, x]                              # window open on every face
    assert whole[10, 15, 0] and whole[10, 15, 3]                # corner posts
    assert not whole[13, 15, 1] and not whole[13, 15, 2]        # slat gap recessed one voxel
    assert whole[13, 14, 1] and whole[12, 15, 1]                # ...with the column behind and slats around
    assert whole[0, 15, 0] and not whole[0, 15, 1] and whole[0, 15, 3]   # studded feet
    assert whole[9, 8, 8] and whole[9, 8, 4] and not whole[9, 8, 3]     # fire fills the cage, behind the face
    assert whole[16, 0, 0] and not whole[16, 8, 4]              # cap ring with the flame opening


def test_paint_is_native_cage_fire_and_rim_never_lobby_floor(data):
    _, art, out, _ = data
    floor = {(140, 123, 123), (181, 156, 123)}
    for k, e in out.items():
        o, p = decode(e)
        rgb = set(map(tuple, np.asarray(art.cell(k, 'BAKED').convert('RGB')).reshape(-1, 3)[p[o]]))
        assert not rgb & floor
    o, p = decode(out[t.FLAME])
    rgb = set(map(tuple, np.asarray(art.cell(t.FLAME, 'BAKED').convert('RGB')).reshape(-1, 3)[p[o]]))
    assert rgb <= {t.GAP, t.RIM_LIT, t.RED, t.ORANGE}
