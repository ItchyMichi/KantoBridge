"""M2, M6, M7 and M8 gates (docs/PLAN.md section 6): the real export builds headless; and the voxel plan's shape
gates (docs/specs/VOXEL_BLOCKS_PLAN.md section 6.4).

The full build (every reachable map) covers M2's checks, M6's habitat blocks and pools, M7's maps
and M8's full-build gate (every map placed, 0 warnings, 0 overlaps) in one build of about a minute.
The shape gates add a filtered build (Pallet Town, into its own world) and the full build run again
over itself: equal shape hashes, the filtered build's shape index equal to the full one's, no block
changed by the rebuild; the full build meets the budgets the report can check (quads per block and
per section, placement rate, build time, the shape map's size) and its self-check finds no hole.

Builds into its own worlds, saves/KantoE2E and saves/KantoE2EPallet, recreated on every run, so it
never touches the world the user plays in (saves/Kanto). Prints and checks only the counts-only
build reports; the fresh full build's report and self-check are the ones left in local/reports/.
"""
import pytest

import build_world
from run_gen1recomp import INSTALL, preflight


def _build(bundle, maps, world):
    code = build_world.build(bundle, maps, world)
    assert build_world.REPORT.exists(), f"no build report (server exit {code})"
    data, failed = build_world.check_report(maps)
    details = build_world.DIAGNOSTICS.read_text(encoding="utf-8") if build_world.DIAGNOSTICS.exists() else None
    assert code == 0 and not failed, {"exit": code, "failed": failed,
                                      "warning_codes": data.get("warning_codes"), "diagnostics": details}
    return data


@pytest.mark.e2e
@pytest.mark.timeout(3600)
def test_full_build_from_the_real_export():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    if not (build_world.MC_DIR / "eula.txt").exists():
        pytest.skip("the user hasn't accepted the Minecraft EULA (local/minecraft/eula.txt)")
    bundle = build_world.export_bundle()

    # A filtered build first, into its own world, so the full build's files are the ones left behind.
    pallet = build_world.MAP_SETS["pallet"]
    build_world._delete_world("KantoE2EPallet")
    filtered = _build(bundle, pallet, "KantoE2EPallet")
    build_world._delete_world("KantoE2EPallet")

    maps = []  # every reachable map
    build_world._delete_world("KantoE2E")
    full = _build(bundle, maps, "KantoE2E")
    report_text = build_world.REPORT.read_text(encoding="utf-8")
    selfcheck_text = build_world.SELFCHECK.read_text(encoding="utf-8") if build_world.SELFCHECK.exists() else None
    again = _build(bundle, maps, "KantoE2E")
    build_world.REPORT.write_text(report_text, encoding="utf-8")
    if selfcheck_text is not None:
        build_world.SELFCHECK.write_text(selfcheck_text, encoding="utf-8")
    assert selfcheck_text is not None, "no shape_selfcheck.json beside the report"

    failed = build_world.shape_gates(full, again, filtered) + build_world.budget_gates(full)
    assert not failed, {"failed": failed, "shape_map_wire_chars": full.get("shape_map_wire_chars"),
                        "rebuild": (again.get("blocks_placed"), again.get("blocks_cleared")),
                        "quads": (full.get("shape_quads_mean_block"), full.get("shape_quads_max_block"),
                                  full.get("shape_quads_section_p95"), full.get("shape_quads_section_max")),
                        "throughput": full.get("throughput"), "elapsed_ms": full.get("elapsed_ms"),
                        "selfcheck": full.get("selfcheck")}

    # The profile plan (P1): with Dramaless Shape in the test install, its table is read and its pins make units.
    if (INSTALL / "mods" / "DRAMALESS_SHAPE" / "data" / "voxel_heights.lua").is_file():
        profile = full.get("profile") or {}
        assert profile.get("status") == "ok" and profile.get("pins", 0) > 0 and profile.get("enabled") is False, profile
        assert (full.get("units_by_source") or {}).get("pin", 0) > 0, full.get("units_by_source")

    points = build_world.viewpoints(full, "KantoE2E")["viewpoints"]
    assert [p["id"] for p in points] == ["spawn", "above"], points
    build_world.write_viewpoints(full, "KantoE2E")
