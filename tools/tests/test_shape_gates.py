"""The voxel plan's shape gates (docs/specs/VOXEL_BLOCKS_PLAN.md section 6.4) on made-up counts-only reports: equal
shape hashes across two builds, the filtered build's shape index equal to the full one's, a rebuild that changes no
block, a shape map that fits its parts, a recorded self-check, and (V2 only) no block changed since the last build."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "e2e"))

import build_world  # noqa: E402

FULL = {"shape_index_hash": "a" * 64, "shape_map_hash": "b" * 64, "shape_map_wire_chars": 90_000, "blocks_placed": 2_000_000,
        "blocks_cleared": 0, "selfcheck": {"maps": 220, "holes": 0}}
AGAIN = dict(FULL, blocks_placed=0)
FILTERED = dict(FULL, shape_map_hash="c" * 64, blocks_placed=40_000)


def test_good_builds_pass():
    assert build_world.shape_gates(FULL, AGAIN, FILTERED) == []
    assert build_world.shape_gates(FULL, AGAIN, FILTERED, {"blocks_placed": 2_000_000}) == []


def test_each_gate_fails_on_its_own():
    assert build_world.shape_gates({}, AGAIN, FILTERED) == ["shapes_reported", "shape_hashes_repeat", "filtered_shares_the_index",
                                                            "shape_map_fits", "selfcheck_recorded"]
    assert build_world.shape_gates(FULL, dict(AGAIN, shape_map_hash="d" * 64), FILTERED) == ["shape_hashes_repeat"]
    assert build_world.shape_gates(FULL, dict(AGAIN, blocks_placed=3), FILTERED) == ["rebuild_changes_nothing"]
    assert build_world.shape_gates(FULL, dict(AGAIN, blocks_cleared=3), FILTERED) == ["rebuild_changes_nothing"]
    assert build_world.shape_gates(FULL, AGAIN, dict(FILTERED, shape_index_hash="e" * 64)) == ["filtered_shares_the_index"]
    too_big = 8 * build_world.PART_CHARS + 1
    assert build_world.shape_gates(dict(FULL, shape_map_wire_chars=too_big), dict(AGAIN, shape_map_wire_chars=too_big),
                                   FILTERED) == ["shape_map_fits"]
    assert build_world.shape_gates(dict(FULL, selfcheck=None), AGAIN, FILTERED) == ["selfcheck_recorded"]
    assert build_world.shape_gates(FULL, AGAIN, FILTERED, {"blocks_placed": 1}) == ["blocks_unchanged"]
