"""The voxel plan's shape gates (docs/specs/VOXEL_BLOCKS_PLAN.md section 6.4) on made-up counts-only reports: equal
shape hashes across two builds, the filtered build's shape index equal to the full one's, a rebuild that changes no
block, a shape map that fits its parts, a recorded self-check; and the budgets of section 3.8 the report can check."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "e2e"))

import build_world  # noqa: E402

FULL = {"shape_index_hash": "a" * 64, "shape_map_hash": "b" * 64, "shape_map_wire_chars": 90_000, "blocks_placed": 2_000_000,
        "blocks_cleared": 0, "selfcheck": {"maps": 220, "holes": 0, "ground_match_min": 1.0, "structure_match_p05": 0.6,
                                           "structure_match_min": 0.4}, "shape_quads_max_block": 40, "shape_quads_mean_block": 5.5,
        "shape_quads_section_p95": 3_000, "shape_quads_section_max": 9_000, "throughput": {"blocks_per_s": 48_000.0},
        "elapsed_ms": 60_000, "void_hull_cells": 0, "shape_decor_skipped": 12,
        "structures_by_kind": {"volume": 900, "object": 40, "hull": 3000, "flat": 0, "building": 20, "fallback": 200},
        "story_cells_by_type": {"object": 700, "tile": 162, "water": 9000}, "closed_shape_cells": 162, "shape_templates": 35}
FULL["selfcheck"]["templates_failed"] = 1
AGAIN = dict(FULL, blocks_placed=0)
FILTERED = dict(FULL, shape_map_hash="c" * 64, blocks_placed=40_000)


def test_good_builds_pass():
    assert build_world.shape_gates(FULL, AGAIN, FILTERED) == []
    assert build_world.budget_gates(FULL) == []


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


def test_each_budget_fails_on_its_own():
    assert build_world.budget_gates(dict(FULL, shape_quads_max_block=97)) == ["quads_per_block"]
    assert build_world.budget_gates(dict(FULL, shape_quads_mean_block=24.5)) == ["quads_per_block"]
    assert build_world.budget_gates(dict(FULL, shape_quads_section_p95=12_001)) == ["quads_per_section"]
    assert build_world.budget_gates(dict(FULL, shape_quads_section_max=30_001)) == ["quads_per_section"]
    assert build_world.budget_gates(dict(FULL, void_hull_cells=1)) == ["void_hull_cells"]
    assert build_world.budget_gates(dict(FULL, shape_decor_skipped=None)) == ["tier_b_reported"]
    assert build_world.budget_gates(dict(FULL, structures_by_kind=None)) == ["tier_b_reported", "templates_reported", "template_fallbacks"]
    assert build_world.budget_gates(dict(FULL, closed_shape_cells=161)) == ["closed_story_art"]
    check = FULL["selfcheck"]
    assert build_world.budget_gates(dict(FULL, selfcheck=dict(check, holes=3))) == ["selfcheck_holes"]
    assert build_world.budget_gates(dict(FULL, selfcheck=dict(check, ground_match_min=0.999))) == ["selfcheck_ground"]
    assert build_world.budget_gates(dict(FULL, selfcheck=dict(check, structure_match_p05=0.54))) == ["selfcheck_structure"]
    assert build_world.budget_gates(dict(FULL, selfcheck=dict(check, structure_match_min=0.34))) == ["selfcheck_structure"]
    assert build_world.budget_gates(dict(FULL, throughput={"blocks_per_s": 39_999.0})) == ["placement_rate"]
    assert build_world.budget_gates(dict(FULL, elapsed_ms=90_001)) == ["build_time"]
    assert build_world.budget_gates(dict(FULL, elapsed_ms=90_001, throughput={}), full_build=False) == []
    # V7: templates reported; no jump in failed templates, no drop in buildings (a full build only).
    assert build_world.budget_gates(dict(FULL, shape_templates=None)) == ["templates_reported"]
    assert build_world.budget_gates(dict(FULL, selfcheck=dict(check, templates_failed=201))) == ["templates_reported",
                                                                                                "template_fallbacks"]
    failed = build_world.MAX_TEMPLATES_FAILED + 1
    assert build_world.budget_gates(dict(FULL, selfcheck=dict(check, templates_failed=failed))) == ["template_fallbacks"]
    fewer = dict(FULL["structures_by_kind"], building=build_world.MIN_TEMPLATE_BUILDINGS - 1)
    assert build_world.budget_gates(dict(FULL, structures_by_kind=fewer)) == ["template_fallbacks"]
    assert build_world.budget_gates(dict(FULL, structures_by_kind=fewer), full_build=False) == []
