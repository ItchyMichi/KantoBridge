"""Battle Pyramid lobby: notch-free 9-block wall cells, statues on pedestals, walk-through amendments."""
import json, sys
from pathlib import Path
import numpy as np
import pytest
ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path: sys.path.insert(0, str(ROOT))
from tools.shape_fit.emerald_pass import pyramid_lobby as t
from tools.shape_fit.emerald_pass import review
from tools.shape_fit.blue_pass.common import decode


@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists(): pytest.skip('Local exported source required')
    table = json.loads(t.BASELINE.read_text(encoding='utf-8-sig')); art = t.EmeraldArt(t.BUNDLE)
    return table, art, t.generate(table, art), t.ledger(table, art)


def test_every_reviewed_key_is_owned_once_and_metadata_passes_the_merger(data):
    table, _, out, rows = data
    assert len(rows) == len({r['key'] for r in rows}) == 20 and len(out) == 17
    assert {r['key'] for r in rows if r['decision'] == 'retained'} == set(t.RETAINED)
    for k, e in out.items():
        review.validate_cell(k, table['cells'][k], e)          # walk flags only through the allowlist
    walks = {k for k, e in out.items() if e.get('walk')}
    assert walks == {'g3q/2484-2485-2531-2532', 'g3q/2541-2542-2535-2543'}
    for k in walks:
        spec = review.METADATA_AMENDMENTS[k]
        assert spec['after'] == {'class': 'voxels', 'walk': True} and spec['witnesses']
        assert all(w['collision'] == 0 for w in spec['witnesses'])


def test_wall_and_column_cells_reach_the_native_nine_block_wall(data):
    _, _, out, _ = data
    for k in [*t.BAY_WALLS, *t.PIERS, *t.COLUMNS]:
        occ, _ = decode(out[k])
        ys = np.nonzero(occ.any(axis=(1, 2)))[0]
        assert ys.min() == 0 and ys.max() + 1 == t.WALL_H, k
    occ, _ = decode(out['g3q/2472-2473-2474-2475'])
    assert occ[:t.WALL_H].all()                                # the niche back is solid wall


def test_niche_back_shows_only_wall_stone_never_the_jackal(data):
    _, art, out, _ = data
    k = 'g3q/2472-2473-2474-2475'; occ, paint = decode(out[k])
    rgb = np.asarray(art.cell(k, 'BAKED').convert('RGB')).reshape(-1, 3)
    south = paint[:t.WALL_H, 15, :]
    assert set(map(tuple, rgb[south.ravel()])) <= {t.TAN, t.MORTAR}


def test_statue_is_one_grounded_connected_pedestal_and_jackal(data):
    _, _, out, _ = data
    for k in t.STATUES:
        occ, _ = decode(out[k])
        assert occ[0].any() and occ[19, 8, 8] and occ[30, 7:9, 7:9].any() and occ[43:47].any() and not occ[48:].any()
        seen = np.zeros_like(occ); stack = [(0, z, x) for z, x in np.argwhere(occ[0])]
        for p in stack: seen[p] = True
        while stack:
            y, z, x = stack.pop()
            for d in ((1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)):
                q = (y+d[0], z+d[1], x+d[2])
                if all(0 <= q[i] < occ.shape[i] for i in range(3)) and occ[q] and not seen[q]:
                    seen[q] = True; stack.append(q)
        assert (seen == occ).all(), k


def test_niche_sills_and_pyramid(data):
    _, _, out, _ = data
    for k in t.NICHES:
        occ, _ = decode(out[k]); assert occ[:2].all() and not occ[2:].any()
    occ, _ = decode(out[t.PYRAMID])
    assert occ[0, 1:15, 1:15].all() and not occ[0, 0].any() and occ[11].sum() == 4 and not occ[12:].any()
