import numpy as np
import pytest
from tools.shape_fit.emerald_pass import arena
from tools.shape_fit.blue_pass.common import decode


def test_arena_keeps_footprint_and_interactions_but_projects_every_floor_texel():
    if not arena.BASELINE.exists():
        pytest.skip('local immutable Emerald baseline not installed')
    baseline = arena.read(arena.BASELINE)
    for key, entry in arena.generate(baseline).items():
        old, _ = decode(baseline['cells'][key])
        occ, paint = decode(entry)
        assert np.array_equal(occ, old)
        assert occ[:4].all() and not occ[4:].any()
        assert np.array_equal(paint[3], np.arange(256, dtype=np.uint8).reshape(16, 16))
        for name in ('on', 'seat', 'walk', 'class'):
            assert entry.get(name) == baseline['cells'][key].get(name)
