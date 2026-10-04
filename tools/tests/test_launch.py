"""tools/launch.ps1 (M10): its dry-run plans against made-up installs, and its port probe.

Nothing here starts Gen1Recomp, Gradle or Minecraft: -DryRun only prints the plan, and the probe
waits for a socket this test opens itself.
"""
import json
import os
import shutil
import socket
import subprocess
import sys
import time

import pytest

TOOLS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(TOOLS, "launch.ps1")

pytestmark = pytest.mark.skipif(sys.platform != "win32", reason="launch.ps1 is for Windows")


def _shells():
    found = []
    for name in ("powershell", "pwsh"):  # Windows PowerShell 5.1 and PowerShell 7
        path = shutil.which(name)
        if path:
            found.append(path)
    return found


SHELLS = _shells()


def _run(shell, *args, timeout=60):
    command = [shell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", SCRIPT, *args]
    done = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    return done.returncode, done.stdout + done.stderr


def _install(folder):
    """A made-up Gen1Recomp install: the files launch.ps1 checks for, all empty."""
    os.makedirs(os.path.join(folder, "mods", "gen1-bridge"))
    for name in ("gen1recomp.exe", "portable.txt"):
        open(os.path.join(folder, name), "w").close()
    return folder


@pytest.fixture
def setup(tmp_path):
    jdk = tmp_path / "jdk-21"
    (jdk / "bin").mkdir(parents=True)
    (jdk / "bin" / "java.exe").write_text("")
    minecraft = tmp_path / "minecraft"
    minecraft.mkdir()
    (minecraft / "server.properties").write_text("online-mode=true\nlevel-name=saves/Kanto\n")
    return {
        "gen1": _install(str(tmp_path / "gen1")),
        "gen1p2": _install(str(tmp_path / "gen1p2")),
        "jdk": str(jdk),
        "minecraft": str(minecraft),
        "config": str(tmp_path / "launch.json"),
        "root": tmp_path,
    }


def _dev(setup, *extra):
    return ["-DryRun", "-Config", setup["config"], "-Gen1Recomp", setup["gen1"], "-JavaHome", setup["jdk"],
            "-MinecraftDir", setup["minecraft"], *extra]


@pytest.mark.parametrize("shell", SHELLS)
def test_dev_plan_one_player(shell, setup):
    code, out = _run(shell, *_dev(setup))
    assert code == 0, out
    assert "--game=blue --console" in out, "dev mode defaults: Blue, with the log console"
    assert "wait until Gen1Recomp listens on 127.0.0.1:27151" in out
    assert "gradlew.bat runClient with JAVA_HOME=" + setup["jdk"] in out
    assert "runServer" not in out and "runClient2" not in out
    assert "[plan] done" in out
    assert "Based on the Pokemon Gen 1 Recompilation Project by BOIS CLUB GAMES, LLC" in out, "Gen1Recomp's required credit"


@pytest.mark.parametrize("shell", SHELLS)
def test_config_and_parameters(shell, setup):
    with open(setup["config"], "w", encoding="utf-8") as f:
        json.dump({"game": "red", "slot": "slot2"}, f)
    code, out = _run(shell, *_dev(setup, "-NoConsole", "-Watch"))
    assert code == 0, out
    assert "--game=red --slot=slot2" in out and "--console" not in out
    assert "watch: restart Gen1Recomp" in out
    code, out = _run(shell, *_dev(setup, "-Game", "blue"))
    assert code == 0 and "--game=blue" in out, "a parameter beats the config"


@pytest.mark.parametrize("shell", SHELLS)
def test_dev_plan_two_players(shell, setup):
    code, out = _run(shell, *_dev(setup, "-Players", "2", "-P2Gen1Recomp", setup["gen1p2"]))
    assert code == 0, out
    assert out.index("start Gen1Recomp") < out.index("start player 2's Gen1Recomp")
    assert "player 2's Gen1Recomp listens on 127.0.0.1:27152" in out
    assert "set online-mode=false" in out
    assert "runServer" in out and "wait until the dev server listens on port 25565" in out
    assert out.index("runServer") < out.index("runClient with") < out.index("runClient2")
    with open(os.path.join(setup["minecraft"], "server.properties")) as f:
        assert "online-mode=true" in f.read(), "a dry run changes nothing"


@pytest.mark.parametrize("shell", SHELLS)
def test_refusals(shell, setup):
    code, out = _run(shell, *_dev(setup, "-Players", "2", "-P2Gen1Recomp", setup["gen1"]))
    assert code == 1 and "second Gen1Recomp install" in out
    code, out = _run(shell, *_dev(setup, "-Players", "2", "-P2Gen1Recomp", str(setup["root"] / "nowhere")))
    assert code == 1 and "is missing" in out
    os.remove(os.path.join(setup["gen1"], "gen1recomp.exe"))
    code, out = _run(shell, *_dev(setup))
    assert code == 1 and "no gen1recomp.exe" in out


@pytest.mark.parametrize("shell", SHELLS)
def test_player_mode_plan(shell, setup):
    root = setup["root"] / "KantoBridge"
    _install(str(root / "gen1recomp"))
    (root / "prism").mkdir()
    (root / "prism" / "prismlauncher.exe").write_text("")
    code, out = _run(shell, "-Mode", "player", "-Root", str(root), "-DryRun")
    assert code == 0, out
    assert "--game=blue" in out and "--console" not in out
    assert "prismlauncher.exe --launch KantoBridge" in out
    assert "watch: restart Gen1Recomp" in out, "player mode watches by default"
    code, out = _run(shell, "-Mode", "player", "-Root", str(setup["root"] / "empty"), "-DryRun")
    assert code == 1


@pytest.mark.parametrize("shell", SHELLS)
def test_probe_finds_a_listener(shell):
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.bind(("127.0.0.1", 0))
    server.listen(1)
    port = server.getsockname()[1]
    try:
        started = time.monotonic()
        code, out = _run(shell, "-Probe", "-Port", str(port), "-ProbePid", str(os.getpid()), "-TimeoutSec", "20")
        assert code == 0 and f"listening {port}" in out, out
        assert time.monotonic() - started < 20
        code, out = _run(shell, "-Probe", "-Port", str(port), "-ProbePid", "4", "-TimeoutSec", "1")
        assert code == 1 and "timeout" in out, "another process's listener doesn't count"
    finally:
        server.close()
