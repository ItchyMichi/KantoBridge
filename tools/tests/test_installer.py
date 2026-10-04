"""tools/installer (M10 skeleton): the plan, the pins, and the pin check. Nothing is downloaded."""
import hashlib
import json
import os
import shutil
import subprocess
import sys

import pytest

TOOLS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(TOOLS, "installer", "install.ps1")
PINS = os.path.join(TOOLS, "installer", "pins.json")

windows_only = pytest.mark.skipif(sys.platform != "win32", reason="the installer is for Windows")
SHELLS = [path for path in (shutil.which("powershell"), shutil.which("pwsh")) if path]


def _run(shell, *args):
    command = [shell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", SCRIPT, *args]
    done = subprocess.run(command, capture_output=True, text=True, timeout=60)
    return done.returncode, done.stdout + done.stderr


def test_pins_are_well_formed():
    with open(PINS, encoding="utf-8") as f:
        pins = json.load(f)
    assert pins["format"] == 1 and pins["minecraft"]["version"] == "1.21.1"
    ids = [entry["id"] for entry in pins["files"]]
    assert len(ids) == len(set(ids))
    for entry in pins["files"]:
        if entry["url"]:
            assert entry["url"].startswith("https://")
            assert len(entry["sha256"]) == 64 and int(entry["sha256"], 16) >= 0
    gen1 = next(entry for entry in pins["files"] if entry["id"] == "gen1recomp")
    assert gen1["url"].startswith("https://github.com/bryanthaboi/gen1recomp/"), "official repository only"
    assert "gen1recomp.com" not in json.dumps(pins).replace("never gen1recomp.com", "")


@windows_only
@pytest.mark.parametrize("shell", SHELLS)
def test_plan_installs_nothing(shell, tmp_path):
    root = tmp_path / "KantoBridge"
    code, out = _run(shell, "-Root", str(root), "-Game", "blue")
    assert code == 0, out
    for step in range(1, 10):
        assert f"[plan] {step}. " in out
    assert "--launcher" in out and "never touches the ROM" in out
    assert "Based on the Pokemon Gen 1 Recompilation Project by BOIS CLUB GAMES, LLC" in out, "Gen1Recomp's required credit"
    assert not root.exists(), "the skeleton creates nothing"
    code, out = _run(shell, "-Root", str(root), "-Execute")
    assert code == 2 and "isn't available yet" in out
    assert not root.exists()


@windows_only
@pytest.mark.parametrize("shell", SHELLS)
def test_verify_checks_size_and_hash(shell, tmp_path):
    payload = b"made-up release bytes"
    pins = {"format": 1, "minecraft": {"version": "1.21.1", "fabricLoader": "0.19.5", "java": 21},
            "files": [{"id": "thing", "version": "1", "url": "https://example.invalid/thing.zip",
                       "sha256": hashlib.sha256(payload).hexdigest(), "size": len(payload)}]}
    (tmp_path / "pins.json").write_text(json.dumps(pins))
    good = tmp_path / "thing.zip"
    good.write_bytes(payload)
    code, out = _run(shell, "-Pins", str(tmp_path / "pins.json"), "-Verify", str(good), "-Id", "thing")
    assert code == 0 and "thing OK" in out, out
    bad = tmp_path / "bad.zip"
    bad.write_bytes(payload[:-1] + b"!")
    code, out = _run(shell, "-Pins", str(tmp_path / "pins.json"), "-Verify", str(bad), "-Id", "thing")
    assert code == 1 and "doesn't match" in out
    code, out = _run(shell, "-Pins", str(tmp_path / "pins.json"), "-Verify", str(good), "-Id", "nope")
    assert code == 1 and "no pin" in out
