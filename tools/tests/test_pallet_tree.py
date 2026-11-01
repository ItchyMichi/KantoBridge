"""Structural regressions for the Pallet boundary tree's hand-authored geometry."""
import base64
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("pallet_tree", ROOT / "tools/shape_fit/pallet_tree.py")
tree = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tree)


def solids(entry):
    raw = base64.b64decode(entry["v"])
    assert len(raw) == 512
    return {(x, y, z) for y in range(16) for z in range(16) for x in range(16)
            if raw[tree.index(x, y, z) >> 3] >> (tree.index(x, y, z) & 7) & 1}


def test_tree_is_one_connected_volume_with_a_stem_not_a_shadow_platform():
    volume = solids(tree.generate())
    assert min(y for x, y, z in volume) == 0
    assert max(y for x, y, z in volume) == 15
    base = {(x, z) for x, y, z in volume if y == 0}
    crown = {(x, z) for x, y, z in volume if y == 8}
    assert 4 <= len(base) <= 12
    assert len(crown) > 8 * len(base)
    assert all(1 <= x <= 14 and 1 <= z <= 14 for x, y, z in volume)
    reached = {next(iter(volume))}
    pending = list(reached)
    while pending:
        x, y, z = pending.pop()
        for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            point = x + dx, y + dy, z + dz
            if point in volume and point not in reached:
                reached.add(point)
                pending.append(point)
    assert reached == volume


def test_side_profile_is_round_and_top_never_samples_the_black_sprite_outline():
    entry = tree.generate()
    volume = solids(entry)
    paint = base64.b64decode(entry["t"])
    assert len(paint) == 4096
    assert volume == {(z, y, x) for x, y, z in volume}
    assert volume == {(15 - x, y, z) for x, y, z in volume}
    # The old front-projected cap used row zero's black contour everywhere.
    for x, y, z in volume:
        if y >= 12 and (x, y + 1, z) not in volume:
            texel = paint[tree.index(x, y, z)]
            assert 3 <= texel // 16 <= 4
            assert 5 <= texel % 16 <= 11


def test_bundled_entry_matches_reproducible_authoring_rules():
    table = json.loads(tree.TABLE.read_text(encoding="utf-8"))
    assert table["cells"][tree.KEY] == tree.generate()
