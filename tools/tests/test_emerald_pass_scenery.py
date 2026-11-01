"""Semantic geometry checks for the first Emerald scenery batch."""
import copy
import json

import numpy as np
import pytest

from tools.model_gallery import cyclic_stack_keys, catalog, EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import scenery


def test_tree_grounding_connected_canopy_and_seam():
    occ, leaf = scenery.tree()
    assert occ.shape == (32, 16, 16)
    assert 8 < occ[0].sum() < 25  # roots, never an extruded shadow pedestal
    assert not leaf[:7].any()
    assert np.any(occ[15] & occ[16])  # crown/base are physically joined
    assert not occ[:, :, [0, 15]].any()
    # Flood fill proves the lobes and tip are attached to the rooted trunk.
    seen = set()
    todo = [tuple(np.argwhere(occ)[0])]
    while todo:
        p = todo.pop()
        if p in seen:
            continue
        seen.add(p)
        for axis in range(3):
            for delta in (-1, 1):
                q = list(p)
                q[axis] += delta
                q = tuple(q)
                if all(0 <= q[i] < occ.shape[i] for i in range(3)) and occ[q] and q not in seen:
                    todo.append(q)
    assert len(seen) == occ.sum()


@pytest.mark.skipif(not scenery.BASELINE.exists() or not scenery.BUNDLE.exists(), reason="local source export and preserved baseline required")
def test_only_base_reverse_link_changes_and_materials_exclude_background():
    table = json.loads(scenery.BASELINE.read_text())
    updates = scenery.generate(table)
    assert set(updates) == {*scenery.CROWNS, scenery.BASE}
    art = EmeraldArt(scenery.BUNDLE)
    allowed_leaf = {(58, 82, 0), (58, 140, 49), (132, 197, 99), (181, 255, 132)}
    for key, entry in updates.items():
        occ, paint = decode(entry)
        source = np.array(art.cell(key, "BAKED").convert("RGB"))
        colors = {tuple(c) for c in source[paint[occ] // 16, paint[occ] % 16]}
        assert colors <= allowed_leaf | ({(66, 58, 49), (107, 90, 90)} if key == scenery.BASE else set())
        old = table["cells"][key]
        for field in set(old) | set(entry):
            if field not in {"v", "t", "up", "from"}:
                if field == "on" and key == scenery.BASE:
                    assert field not in entry
                else:
                    assert entry.get(field) == old.get(field)
    revised = copy.deepcopy(table)
    revised["cells"].update(updates)
    assert cyclic_stack_keys(revised["cells"]) == []


@pytest.mark.skipif(not scenery.BASELINE.exists() or not scenery.BUNDLE.exists(), reason="local source export and preserved baseline required")
def test_compound_id_preserves_before_after_coverage():
    table = json.loads(scenery.BASELINE.read_text())
    art = EmeraldArt(scenery.BUNDLE)
    before = {e["id"]: e for e in catalog(table, scenery.entities(), art)}
    table["cells"].update(scenery.generate(table))
    after = {e["id"]: e for e in catalog(table, scenery.entities(), art)}
    for e in scenery.entities():
        assert before[e["id"]]["layout"] == after[e["id"]]["layout"] == e["layout"]
        assert before[e["id"]]["bounds"] == after[e["id"]]["bounds"] == [2, 4, 2]
