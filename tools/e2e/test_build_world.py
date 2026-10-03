"""M2 and M6 gates (docs/PLAN.md section 6): the real export builds headless.

The Route 1 set (Pallet Town, its interiors, Route 1 and Viridian City) covers M2's checks and
M6's habitat blocks and pools in one build.

Builds into its own world, saves/KantoE2E, recreated on every run, so it never touches the world the
user plays in (saves/Kanto). Prints and checks only the counts-only build report.
"""
import pytest

import build_world
from run_gen1recomp import preflight


@pytest.mark.e2e
@pytest.mark.timeout(3600)
def test_route1_build_from_the_real_export():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    if not (build_world.MC_DIR / "eula.txt").exists():
        pytest.skip("the user hasn't accepted the Minecraft EULA (local/minecraft/eula.txt)")
    maps = build_world.MAP_SETS["route1"]
    build_world._delete_world("KantoE2E")
    bundle = build_world.export_bundle()
    code = build_world.build(bundle, maps, "KantoE2E")
    assert build_world.REPORT.exists(), f"no build report (server exit {code})"
    data, failed = build_world.check_report(maps)
    assert code == 0 and not failed, {"exit": code, "failed": failed,
                                      "warning_codes": data.get("warning_codes")}
