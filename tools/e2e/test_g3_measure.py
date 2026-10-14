"""MG7 G3D, "measure first" (docs/specs/GEN3_BRIDGE_PLAN.md §5, §10 E7): sizes Gen 3's export on the test
install's Emerald import before the export is written. The driver (drivers/kb_g3measure.lua) runs at the title
(no save touched) and prints numbers only: the maps (count, cells, largest size, tileset pairs), per pair the
metatiles and the distinct 8x8 quarters of each plane (under, middle, over) and how many its maps use, the totals
across pairs, the object sprites (sheets, frames, sizes) and the encounter tables. The test prints those lines and
checks they are sane; the numbers go into GEN3_BRIDGE_PLAN.md by hand.
"""

import re

import pytest

from run_gen1recomp import INSTALL, launch, preflight

GAME = "emerald"


def _skip_without_emerald():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    from run_gen1recomp import kb_games
    if GAME not in kb_games.detect(INSTALL):
        pytest.skip("no Emerald import in the test install")


def numbers(line):
    """{key: value} of a KB_MEASURE line (values as ints where they are)."""
    out = {}
    for key, value in re.findall(r"(\w+)=(\S+)", line):
        out[key] = int(value) if re.fullmatch(r"-?\d+", value) else value
    return out


@pytest.mark.e2e
@pytest.mark.timeout(2400)
def test_measure_the_gen3_export():
    _skip_without_emerald()
    with launch("kb_g3measure.lua", game=GAME, extra_env={"KB_DRIVER_SECONDS": "2000"}, echo=False) as running:
        result = running.wait(timeout=2200)
    lines = [line for line in result.lines if line.startswith("KB_MEASURE") or line.startswith("KB_FAIL")]
    for line in lines:
        print(line)
    assert result.marker("KB_FAIL") is None, lines
    assert result.marker("KB_MEASURE_DONE") is not None, result.summary()
    maps = numbers(next(line for line in lines if line.startswith("KB_MEASURE_MAPS")))
    assert maps["maps"] > 100 and maps["pairs"] > 5 and maps["cells"] > 10000, maps
    quarters = [numbers(line) for line in lines if line.startswith("KB_MEASURE_QUARTERS")]
    assert {q["plane"] for q in quarters} == {"under", "middle", "over"}
    under = next(q for q in quarters if q["plane"] == "under")
    assert 0 < under["used"] <= under["distinct"], under
    total = numbers(next(line for line in lines if line.startswith("KB_MEASURE_TOTAL")))
    assert total["failedPairs"] == 0, total
    sprites = numbers(next(line for line in lines if line.startswith("KB_MEASURE_SPRITES")))
    assert sprites["sheets"] > 50, sprites
