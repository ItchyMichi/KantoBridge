"""Battle Pyramid lower lobby: balustrades, standing screen, wall monitor; floor art retained."""
import json, sys
from pathlib import Path
import numpy as np
import pytest
ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path: sys.path.insert(0, str(ROOT))
from tools.shape_fit.emerald_pass import pyramid_lobby_lower as t
from tools.shape_fit.emerald_pass import review
from tools.shape_fit.blue_pass.common import decode


@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists(): pytest.skip('Local exported source required')
    table = json.loads(t.BASELINE.read_text(encoding='utf-8-sig')); art = t.EmeraldArt(t.BUNDLE)
    return table, art, t.generate(table, art), t.ledger(table, art)


def test_ownership_metadata_and_retained_floor_art(data):
    table, _, out, rows = data
    assert len(rows) == len({r['key'] for r in rows}) == 14 and len(out) == 8
    assert {r['key'] for r in rows if r['decision'] == 'retained'} == set(t.RETAINED)
    for k, e in out.items():
        review.validate_cell(k, table['cells'][k], e)
        assert len(e.get('up', [])) <= 1


def test_low_wall_cells_stay_two_blocks_and_solid(data):
    _, _, out, _ = data
    for k in (t.PIT_TOP, t.TERMINAL_WALL):
        occ, _ = decode(out[k]); assert occ[:16].all() and len(occ) == 16


def test_rails_are_mirror_images_with_banister_and_studs(data):
    _, _, out, _ = data
    w, _ = decode(out['g3q/2514-2515-2516-2517']); e, _ = decode(out['g3q/2519-2520-2521-2522'])
    assert (w == e[:, :, ::-1]).all()
    assert w[10, :, 7:11].all() and not w[11:, :, 7:11].any()           # banister
    assert w[11, 3, 3] and not w[11, 7, 3]                                 # pyramid stud caps, not a ridge
    assert w[0, :, 12:].all() and not w[1:, :, 12:].any()                  # tread strip stays low


def test_monitor_is_one_board_on_two_feet_and_screen_on_a_post(data):
    _, art, out, _ = data
    occ = np.concatenate([decode(out[k])[0][:16] for k in t.MONITOR], axis=2)
    assert occ[1:16, 8, 9:39].all() and not occ[:, :, :9].any() and not occ[:, :, 39:].any()
    assert occ[0, 8, 12:15].all() and not occ[0, 8, 16:30].any()
    s, p = decode(out[t.SCREEN])
    assert s[0:2, 9, 8].all() and s[5, 9, 8] and not s[5, 9, 3] and s[20, 8, 1] and not s[26:].any()
    rgb = np.asarray(art.cell(t.SCREEN, 'BAKED').convert('RGB')).reshape(-1, 3)
    assert (49, 49, 132) not in set(map(tuple, rgb[p[s]]))                 # the dark pit behind is not painted on
