"""Claude's QC Rustboro fountain: whole octagon, grounded, connected, native stone/water paint."""
import json, sys
from pathlib import Path
import numpy as np
import pytest
ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path: sys.path.insert(0, str(ROOT))
from tools.shape_fit.emerald_pass import claude_qc_rustboro_fountain as t
from tools.shape_fit.emerald_pass import review
from tools.shape_fit.emerald_pass.claude_source_paint import assemble


@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists(): pytest.skip('Local exported source required')
    table = json.loads(t.BASELINE.read_text(encoding='utf-8-sig')); art = t.EmeraldArt(t.BUNDLE)
    out = t.generate(table, art)
    return table, art, out, assemble(out, t.LAYOUT, art)


def test_exact_nine_keys_metadata_and_merger_guard(data):
    table, _, out, _ = data
    assert set(out) == {k for row in t.LAYOUT for k in row}
    for k, e in out.items():
        review.validate_cell(k, table['cells'][k], e)
    assert [len(out[k].get('up', [])) for row in t.LAYOUT for k in row] == [0, 0, 0, 0, 1, 0, 0, 0, 0]


def test_symmetric_octagon_inside_footprint_paving_excluded(data):
    *_, (occ, _) = data
    assert (occ == occ[:, :, ::-1]).all() and (occ == occ[:, ::-1, :]).all() and (occ == occ.transpose(0, 2, 1)).all()
    assert not occ[:, 0, :].any() and not occ[:, :, 0].any() and not occ[:, 47, :].any()
    assert not occ[:, 2, 2].any() and occ[0, 2, 24]               # cut corners, full sides
    assert occ[:, 24, 1].sum() == 2 and occ[:, 24, 2].sum() == 6  # lip overhang above the wall


def test_grounded_and_connected_with_supported_dish_and_jet(data):
    *_, (occ, _) = data
    seen = np.zeros_like(occ); stack = [(0, z, x) for z, x in np.argwhere(occ[0])]
    for p in stack: seen[p] = True
    while stack:
        y, z, x = stack.pop()
        for dy, dz, dx in ((1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)):
            q = (y+dy, z+dz, x+dx)
            if all(0 <= q[i] < occ.shape[i] for i in range(3)) and occ[q] and not seen[q]:
                seen[q] = True; stack.append(q)
    assert (occ == seen).all()                                    # spray sheets included: no loose voxel
    assert occ[17, 23:25, 23:25].all() and not occ[18:].any()
    assert occ[:7, 24, 24].all() and occ[10, 24, 16] and not occ[10, 24, 20]   # pedestal, rim, open dish


def test_paint_is_native_fountain_material_never_paving(data):
    *_, (occ, rgb) = data
    allowed = {t.WATER, t.STONE, t.GROOVE, t.EDGE, t.FACE, t.BOWL, t.SPRAY, t.RIPPLE, t.JET_BASE}
    assert set(map(tuple, rgb[occ])) <= allowed
    south_wall = rgb[1, 45, 20:28]; north_wall = rgb[1, 2, 20:28]
    assert {tuple(c) for c in south_wall} == {t.FACE} and {tuple(c) for c in north_wall} == {t.STONE}
