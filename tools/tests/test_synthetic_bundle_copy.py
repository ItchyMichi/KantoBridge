"""The synthetic bundle copy used by fabric-bridge's GameTests matches fake_gen1's export.

Regenerate it after changing tools/fake_gen1/synth.py or synth3.py (from tools/):
    .venv/Scripts/python.exe -m fake_gen1 --write-bundle ../fabric-bridge/src/gametest/resources/kantobridge/synthetic_bundle
    .venv/Scripts/python.exe -m fake_gen1 --write-bundle ../fabric-bridge/src/gametest/resources/kantobridge/synthetic_bundle --game emerald
(MG7: red is the Gen 1 world, emerald the synthetic Gen 3 export.)
"""
from fake_gen1.bundle import write_bundle
from kb_protocol.paths import REPO_ROOT

COPY = REPO_ROOT / "fabric-bridge" / "src" / "gametest" / "resources" / "kantobridge" / "synthetic_bundle"


def _files(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(root.rglob("*")) if p.is_file()}


def test_gametest_bundle_is_current(tmp_path):
    write_bundle(tmp_path, game="red")
    fresh = write_bundle(tmp_path, game="emerald").parent
    assert _files(COPY) == _files(fresh), "stale synthetic bundle: regenerate it (see this file's docstring)"
