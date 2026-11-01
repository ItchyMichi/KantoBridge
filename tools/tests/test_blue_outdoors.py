"""Semantic checks for outdoor assemblies and traversable floor objects."""
import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from tools.shape_fit.blue_pass import outdoors
from tools.shape_fit.blue_pass.common import decode


@pytest.fixture
def art(monkeypatch):
    # Shape tests need source addresses, not locally exported copyrighted art.
    shades = np.tile(np.arange(16, dtype=np.uint8) % 4, (16, 1))
    monkeypatch.setattr(outdoors, "source_shades", lambda _art, _key: shades)
    return object()


def components(occ):
    unseen = set(map(tuple, np.argwhere(occ)))
    found = []
    while unseen:
        todo = [unseen.pop()]
        group = []
        while todo:
            pos = todo.pop()
            group.append(pos)
            for axis in range(3):
                for delta in (-1, 1):
                    p = list(pos)
                    p[axis] += delta
                    p = tuple(p)
                    if p in unseen:
                        unseen.remove(p)
                        todo.append(p)
        found.append(group)
    return found


def test_tree_is_one_grounded_assembly_across_all_four_cells(art):
    parts = outdoors.forest_tree(art)
    occ = np.zeros((32, 32, 32), bool)
    for z, row in enumerate(outdoors.TREE):
        for x, ids in enumerate(row):
            model = parts[outdoors.key("forest", ids)]
            occ[:, z*16:(z+1)*16, x*16:(x+1)*16] = model.occ
    assert len(components(occ)) == 1
    assert 0 < occ[0].sum() < 100  # grounded roots, no whole-footprint shadow slab
    assert (occ[:, :, 15] & occ[:, :, 16]).any()
    assert (occ[:, 15, :] & occ[:, 16, :]).any()
    assert occ[25:].any() and not occ[31].any()


@pytest.mark.parametrize("atlas,lower,upper,tall", [
    ("cemetery", "55-63-61-62", "1-1-39-47", False),
    ("forest", "26-27-75-76", "48-48-10-11", False),
    ("plateau", "21-22-48-49", "16-18-40-41", True),
])
def test_statue_halves_connect_and_keep_stack_link(art, atlas, lower, upper, tall):
    lo, hi = outdoors.key(atlas, lower), outdoors.key(atlas, upper)
    parts = outdoors.statue(art, lo, hi, tall)
    occ = np.concatenate([parts[lo].occ, parts[hi].occ])
    assert len(components(occ)) == 1
    assert (occ[15] & occ[16]).any()
    assert parts[hi].entry({"class": "voxels", "on": lo})["on"] == lo
    assert np.count_nonzero(occ[:, :, 8].any(axis=0)) >= 10


def test_fence_corners_join_rails_to_posts(art):
    for ids in ("16-35-33-32", "32-33-35-16", "33-32-16-35", "35-16-32-33"):
        occ = outdoors.fence(art, outdoors.key("overworld", ids)).occ
        assert len(components(occ)) == 1
        assert occ[0].any()


def test_safari_balustrade_has_open_gaps_and_continuous_rails(art):
    occ = outdoors.balustrade(art, outdoors.key("forest", "66-66-67-67")).occ
    assert len(components(occ)) == 1
    assert occ[12, 8].all()
    assert not occ[6, 8, 7] and occ[6, 8, 3]
    assert occ[0].any()


def test_down_ladder_stays_low_and_walkable(art):
    k = outdoors.key("cavern", "8-9-24-25")
    entry = outdoors.ladder_well(art, k).entry({"class": "voxels", "walk": True})
    occ, _ = decode(entry)
    assert entry["walk"] is True
    assert occ.any() and not occ[2:].any()


def test_ground_is_a_surface_and_vegetation_is_small_and_grounded(art):
    floor = outdoors.floor(art, outdoors.key("overworld", "57-57-57-57"))
    assert floor.occ[0].all() and not floor.occ[1:].any()
    grass = outdoors.grass(art, outdoors.key("overworld", "82-82-82-82")).occ
    groups = components(grass)
    assert len(groups) == 4
    assert all(any(y == 0 for y, _, _ in group) for group in groups)
    assert not grass[5:].any()


def test_underground_steps_are_connected_and_ascending(art):
    occ = outdoors.stairs(art, outdoors.key("underground", "3-4-19-20")).occ
    assert len(components(occ)) == 1
    heights = occ[:, 8, 2:14].sum(axis=0)
    assert np.all(np.diff(heights) >= 0)
    assert len(np.unique(heights)) == 3
