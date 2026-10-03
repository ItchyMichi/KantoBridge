"""M2, M6, M7 and M8 gates (docs/PLAN.md section 6): the real export builds headless.

The full build (every reachable map) covers M2's checks, M6's habitat blocks and pools, M7's maps
and M8's full-build gate (every map placed, 0 warnings, 0 overlaps) in one build of about a minute.

Builds into its own world, saves/KantoE2E, recreated on every run, so it never touches the world the
user plays in (saves/Kanto). Prints and checks only the counts-only build report.
"""
import pytest

import build_world
from run_gen1recomp import preflight


@pytest.mark.e2e
@pytest.mark.timeout(3600)
def test_full_build_from_the_real_export():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    if not (build_world.MC_DIR / "eula.txt").exists():
        pytest.skip("the user hasn't accepted the Minecraft EULA (local/minecraft/eula.txt)")
    maps = []  # every reachable map
    build_world._delete_world("KantoE2E")
    bundle = build_world.export_bundle()
    code = build_world.build(bundle, maps, "KantoE2E")
    assert build_world.REPORT.exists(), f"no build report (server exit {code})"
    data, failed = build_world.check_report(maps)
    details = build_world.DIAGNOSTICS.read_text(encoding="utf-8") if build_world.DIAGNOSTICS.exists() else None
    assert code == 0 and not failed, {"exit": code, "failed": failed,
                                      "warning_codes": data.get("warning_codes"), "diagnostics": details}
