"""Spike S5 (docs/PLAN.md M0): the e2e runner launches the Gen1Recomp test
instance with a driver that prints KB_READY after game.ready, and it exits.

Runs only with --e2e. Skips when the pre-flight (PLAN section 0.6) isn't done.
"""

import pytest

from run_gen1recomp import preflight, run, safe_line


@pytest.mark.e2e
@pytest.mark.timeout(300)
def test_s5_driver_reports_ready():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    result = run("kb_ready.lua", timeout=240)
    assert not result.timed_out, result.summary()
    assert result.returncode == 0, result.summary()
    assert result.marker("KB_FAIL") is None, result.lines
    facts = result.facts("KB_READY")
    assert facts, result.lines
    assert facts["state"] == "loaded"
    assert facts["engine"] == "0.3.49" and facts["status_engine"] == "0.3.49"
    assert facts["bridge"] == "0.1.0" and facts["protocol"] == "1" and facts["modules"] == "26"
    assert facts["mod_errors"] == "0"
    assert "[info] [gen1-bridge] gen1-bridge loaded" in result.lines
    assert any(line.startswith("[info] [gen1-bridge] game.ready ") for line in result.lines)
    assert not result.crash_log_touched


def test_output_filter_is_strict():
    # no game needed: the filter that guards every e2e run
    assert safe_line("KB_READY state=loaded\n") == "KB_READY state=loaded"
    assert safe_line("[info] [gen1-bridge] net.listening port=27151") is not None
    assert safe_line("[info] loaded mod gen1-bridge 0.1.0") == "[info] loaded mod gen1-bridge 0.1.0"
    assert safe_line("[info] loaded mod other-mod 1.0") is None
    assert safe_line("Hello! This is some game text.") is None
    assert safe_line("[info] game loaded") is None
    assert safe_line("[warn] hook input.step failed in [gen1-bridge]: lib/kb_net.lua:40: TEXT") == \
        "KB_ENGINE_NOTE gen1-bridge at lib/kb_net.lua:40"
    assert safe_line("[info] something [gen1-bridge] mentioned") == "KB_ENGINE_NOTE gen1-bridge at unknown location"
    assert safe_line("LUA ERROR: src/x.lua:12: bad thing near 'TEXT'") == "LUA ERROR at src/x.lua:12"
    assert safe_line("driver error: kb_ready.lua:3: oops SECRET") == "driver error at kb_ready.lua:3"
