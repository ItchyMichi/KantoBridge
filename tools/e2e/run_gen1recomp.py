"""Launch the Gen1Recomp test instance with one of our driver scripts.

    python tools/e2e/run_gen1recomp.py kb_ready.lua [--game blue] [--timeout 180]

Only ``local/gen1recomp-e2e/`` is ever launched, never the user's own
``local/gen1recomp/``. The game boots in driver mode (POKEPORT_DRIVER, see
refs/gen1recomp/main.lua:641-645 and :993-1011): the driver is a coroutine
resumed once per frame, and the process exits 0 when it ends, 1 on a driver
error and 3 on any uncaught Lua error (main.lua:77-83).

Data safety (docs/PLAN.md section 0.3). The game's output can contain
ROM-derived text, so every line is filtered before anything is printed:
  * lines starting with ``KB_`` (our drivers' markers) pass;
  * lines tagged ``[gen1-bridge]`` (our mod's redacted log) pass;
  * the engine's own ``loaded mod gen1-bridge <version>`` line passes;
  * ``LUA ERROR`` and ``driver error:`` lines show only the marker and the
    ``file.lua:NN`` locations in them;
  * everything else is only counted and hashed in aggregate.
Files under ``local/`` are never opened: pre-flight checks file names only, and
the crash log (%APPDATA%/pokemon-love2d/lua-error.log) is only stat-ed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
INSTALL = REPO / "local" / "gen1recomp-e2e"
DRIVERS = HERE / "drivers"
REPORTS = REPO / "local" / "reports"
VERSIONS = ("red", "blue")
MARKER = "rom-cache.complete"  # src/import/CacheContract.lua:29, inside the version folder

BRIDGE_TAG = "[gen1-bridge]"
# Our own mod.log lines (kb_log writes only symbols, counts and hashes).
BRIDGE_LINE = re.compile(r"^\[(info|warn|error)\] \[gen1-bridge\] ")
LOADED_LINE = re.compile(r"^\[(info|warn|error)\] loaded mod gen1-bridge [\w.+-]+$")
LOCATION = re.compile(r"[\w./\\-]+\.lua:\d+")


@dataclass
class Preflight:
    ok: bool
    game: str | None
    reasons: list[str]


def preflight(install: Path = INSTALL) -> Preflight:
    """Checks the test install by listing names only."""
    reasons: list[str] = []
    names = set(os.listdir(install)) if install.is_dir() else set()
    if "gen1recomp.exe" not in names:
        reasons.append("gen1recomp.exe missing")
    if "portable.txt" not in names:
        reasons.append("portable.txt missing (the install would not be portable)")
    mods = install / "mods"
    if not (mods.is_dir() and "gen1-bridge" in os.listdir(mods)):
        reasons.append("mods/gen1-bridge junction missing")
    imported = [v for v in VERSIONS if v in names and MARKER in os.listdir(install / v)]
    if not imported:
        reasons.append("no completed ROM import (red/ or blue/ with its completion marker)")
    game = imported[0] if len(imported) == 1 else None
    if len(imported) > 1:
        reasons.append("both red and blue are imported: pass --game")
    return Preflight(ok=not reasons, game=game, reasons=reasons)


def safe_line(line: str) -> str | None:
    """The printable form of one output line, or None to count it only."""
    text = line.strip()
    if text.startswith("KB_"):
        return text
    if BRIDGE_LINE.match(text) or LOADED_LINE.match(text):
        return text
    if BRIDGE_TAG in text:
        # The engine writing about our mod (a failed hook, a load error): its error text is not
        # ours to vouch for, so show only where it happened.
        spots = LOCATION.findall(text)
        return f"KB_ENGINE_NOTE gen1-bridge at {', '.join(spots) if spots else 'unknown location'}"
    for marker in ("LUA ERROR", "driver error:"):
        if text.startswith(marker):
            spots = LOCATION.findall(text)
            return f"{marker.rstrip(':')} at {', '.join(spots) if spots else 'unknown location'}"
    return None


def crash_log() -> Path | None:
    appdata = os.environ.get("APPDATA")
    return Path(appdata) / "pokemon-love2d" / "lua-error.log" if appdata else None


def _stat(path: Path | None):
    try:
        return path.stat().st_mtime if path else None
    except OSError:
        return None


@dataclass
class RunResult:
    returncode: int | None
    timed_out: bool
    elapsed: float
    lines: list[str] = field(default_factory=list)  # safe lines only
    other_count: int = 0
    other_sha8: str = ""
    error_spots: list[str] = field(default_factory=list)
    crash_log_touched: bool = False

    def marker(self, name: str) -> str | None:
        for line in self.lines:
            if line == name or line.startswith(name + " "):
                return line
        return None

    def facts(self, name: str) -> dict[str, str]:
        line = self.marker(name) or ""
        return dict(part.split("=", 1) for part in line.split()[1:] if "=" in part)

    def summary(self) -> dict:
        return {
            "returncode": self.returncode,
            "timed_out": self.timed_out,
            "elapsed_s": round(self.elapsed, 1),
            "safe_lines": len(self.lines),
            "other_lines": self.other_count,
            "other_sha8": self.other_sha8,
            "error_spots": self.error_spots,
            "crash_log_touched": self.crash_log_touched,
            "markers": [line.split()[0] for line in self.lines if line.startswith("KB_")],
        }


def run(driver: str | Path, game: str | None = None, timeout: float = 180.0,
        extra_env: dict[str, str] | None = None, echo: bool = True,
        install: Path = INSTALL) -> RunResult:
    driver = Path(driver)
    if not driver.is_absolute():
        driver = (DRIVERS / driver) if (DRIVERS / driver).is_file() else driver.resolve()
    if not driver.is_file():
        raise FileNotFoundError(driver)
    pf = preflight(install)
    if not pf.ok and not (game and pf.reasons == ["both red and blue are imported: pass --game"]):
        raise RuntimeError("pre-flight not done: " + "; ".join(pf.reasons))
    game = game or pf.game
    if game not in VERSIONS:
        raise ValueError(f"game must be one of {VERSIONS}")

    env = dict(os.environ)
    env.update({
        "POKEPORT_DRIVER": str(driver),
        "POKEPORT_VERSION": game,
        "POKEPORT_CONSOLE": "1",      # no hidden console: stdout stays our pipe
        "POKEPORT_BACKGROUND": "1",   # mutes audio
        "PYTHONUTF8": "1",
    })
    env.update(extra_env or {})
    exe = install / "gen1recomp.exe"
    crash = crash_log()
    crash_before = _stat(crash)

    result = RunResult(returncode=None, timed_out=False, elapsed=0.0)
    digest = hashlib.sha256()
    spots: list[str] = []
    error_seen = False

    def consume(stream):
        nonlocal error_seen
        for raw in iter(stream.readline, b""):
            line = raw.decode("utf-8", errors="replace")
            shown = safe_line(line)
            if shown is not None:
                result.lines.append(shown)
                if shown.startswith(("LUA ERROR", "driver error")):
                    error_seen = True
                if echo:
                    print(shown, flush=True)
                continue
            result.other_count += 1
            digest.update(raw)
            if error_seen:  # traceback lines: keep their locations only
                for spot in LOCATION.findall(line):
                    if spot not in spots:
                        spots.append(spot)

    start = time.monotonic()
    proc = subprocess.Popen(
        [str(exe), f"--game={game}"],  # never --console: it would take stdout away
        cwd=str(install), env=env,
        stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    )
    reader = threading.Thread(target=consume, args=(proc.stdout,), daemon=True)
    reader.start()
    try:
        result.returncode = proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        result.timed_out = True
        proc.kill()
        result.returncode = proc.wait(timeout=30)
    reader.join(timeout=10)
    result.elapsed = time.monotonic() - start
    result.other_sha8 = digest.hexdigest()[:8]
    result.error_spots = spots
    result.crash_log_touched = _stat(crash) != crash_before
    return result


def write_report(name: str, result: RunResult) -> Path:
    REPORTS.mkdir(parents=True, exist_ok=True)
    path = REPORTS / f"e2e_{name}.json"
    path.write_text(json.dumps(result.summary(), indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return path


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("driver", help="driver file name in tools/e2e/drivers, or a path")
    parser.add_argument("--game", choices=VERSIONS, help="default: the one imported in the test install")
    parser.add_argument("--timeout", type=float, default=180.0)
    parser.add_argument("--report", action="store_true", help="write local/reports/e2e_<driver>.json")
    args = parser.parse_args(argv)
    pf = preflight()
    if not pf.ok and not args.game:
        print("pre-flight not done: " + "; ".join(pf.reasons), file=sys.stderr)
        return 2
    result = run(args.driver, game=args.game, timeout=args.timeout)
    print("RUN " + json.dumps(result.summary(), sort_keys=True), flush=True)
    if args.report:
        print(f"report: {write_report(Path(args.driver).stem, result)}")
    if result.crash_log_touched:
        print("note: Gen1Recomp's crash log was written (not opened): %APPDATA%\\pokemon-love2d\\lua-error.log")
    return 0 if (result.returncode == 0 and not result.timed_out) else 1


if __name__ == "__main__":
    sys.exit(main())
