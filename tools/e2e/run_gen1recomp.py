"""Launch the Gen1Recomp test instance with one of our driver scripts.

    python tools/e2e/run_gen1recomp.py kb_ready.lua [--game blue] [--timeout 180]

Only ``local/gen1recomp-e2e/`` is ever launched, never the user's own
``local/gen1recomp/``. On Linux (the RunPod dev pod, which has no instance of
the user's own) the test instance is the official release's extracted AppImage
in ``local/gen1recomp-linux/`` with ``local/gen1recomp-data/`` as its
XDG_DATA_HOME: LÖVE keeps the fused game's folder (imports, mods, saves) in
``local/gen1recomp-data/pokemon-love2d/``, the counterpart of the portable
folder, and that folder is what ``INSTALL`` names there. The game boots in driver mode (POKEPORT_DRIVER, see
refs/gen1recomp/main.lua:755-759 and :1136-1157): the driver is a coroutine
resumed once per frame, and the process exits 0 when it ends, 1 on a driver
error and 3 on any uncaught Lua error (main.lua:84-90).

Data safety (docs/PLAN.md section 0.3). The game's output can contain
ROM-derived text, so every line is filtered before anything is printed:
  * lines starting with ``KB_`` (our drivers' markers) pass;
  * lines tagged ``[gen1-bridge]`` (our mod's redacted log) pass;
  * the engine's own ``loaded mod gen1-bridge <version>`` line passes;
  * ``LUA ERROR`` and ``driver error:`` lines show only the marker and the
    ``file.lua:NN`` locations in them;
  * everything else is only counted and hashed in aggregate.
Files under ``local/`` are never opened: pre-flight checks file names only
(tools/kb_games.py: the game folders with their completion marker, any id of
the game catalogue), and the crash log
(%APPDATA%/pokemon-love2d/lua-error.log) is only stat-ed.
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
LINUX = sys.platform.startswith("linux")
# The Gen1Recomp release gen1-bridge is tested with (lib/kb_engine.lua Engine.TESTED, the adapter loader; tools/tests/test_pins.py
# keeps every copy of this number in step).
PINNED = "0.3.85"
# A slow host builds the world export slowly (the RunPod pod under Xvfb: about 460 s on either engine release,
# ENGINE_UPGRADE_PLAN G2): the tests that fetch the export wait INDEX_TIMEOUT for export.index and add EXPORT_EXTRA
# to their own limits; a PC keeps the defaults (240 s, 0). Set KB_EXPORT_INDEX_TIMEOUT to raise them. The drivers'
# own time limits (their KB_DRIVER_SECONDS defaults) stretch by the same allowance: launch() passes it as
# KB_DRIVER_EXTRA, so a slow export never trips a driver before the test's own (stretched) wait does.
INDEX_TIMEOUT = int(os.environ.get("KB_EXPORT_INDEX_TIMEOUT", "240"))
EXPORT_EXTRA = max(0, INDEX_TIMEOUT - 240)


def _versioned(name: str, env: str) -> Path:
    """ENGINE_UPGRADE_PLAN UP4 / U5: the folder ``local/<name>`` an engine build or data home lives in.

    An environment override (``KB_GEN1RECOMP_INSTALL``, ``KB_GEN1RECOMP_APP``, ``KB_GEN1RECOMP_HOME``) wins;
    else ``local/<name>-<PINNED>`` when it exists, so two engine releases sit side by side and the
    previous one stays untouched as the fallback until the upgrade's last gate; else ``local/<name>``.
    """
    override = os.environ.get(env)
    if override:
        return Path(override)
    versioned = REPO / "local" / f"{name}-{PINNED}"
    return versioned if versioned.is_dir() else REPO / "local" / name


WINDOWS_INSTALL = _versioned("gen1recomp-e2e", "KB_GEN1RECOMP_INSTALL")
# Linux: the extracted AppImage (AppRun runs bin/love --fused game.love) and its data home (XDG_DATA_HOME).
LINUX_APP = _versioned("gen1recomp-linux", "KB_GEN1RECOMP_APP")
LINUX_HOME = _versioned("gen1recomp-data", "KB_GEN1RECOMP_HOME")
IDENTITY = "pokemon-love2d"  # the game's LÖVE identity: its data folder's name
# The folder holding the imports (red/, blue/), mods/, saves/ and mod_cache/.
INSTALL = LINUX_HOME / IDENTITY if LINUX else WINDOWS_INSTALL
DRIVERS = HERE / "drivers"
REPORTS = REPO / "local" / "reports"
# MG4: the imports are detected by tools/kb_games.py (folder names + the completion marker, any catalogued id).
try:
    import kb_games
except ImportError:  # run as a script: tools/ isn't on the path
    sys.path.insert(0, str(REPO / "tools"))
    import kb_games
VERSIONS = tuple(kb_games.load_games().ids)  # every catalogued game id (the old red/blue pair, widened)
MARKER = kb_games.MARKER  # src/import/CacheContract.lua:35, inside the version folder

BRIDGE_TAG = "[gen1-bridge]"
# Our own mod.log lines (kb_log writes only symbols, counts and hashes).
BRIDGE_LINE = re.compile(r"^\[(info|warn|error)\] \[gen1-bridge\] ")
LOADED_LINE = re.compile(r"^\[(info|warn|error)\] loaded mod gen1-bridge [\w.+-]+$")
LOCATION = re.compile(r"[\w./\\-]+\.lua:\d+")


# The one pre-flight reason --game answers (several completed imports in the test install).
SEVERAL_IMPORTED = "several games are imported: pass --game"


@dataclass
class Preflight:
    ok: bool
    game: str | None
    reasons: list[str]


def preflight(install: Path = INSTALL) -> Preflight:
    """Checks the test install by listing names only."""
    reasons: list[str] = []
    names = set(os.listdir(install)) if install.is_dir() else set()
    if LINUX:
        if not (LINUX_APP / "AppRun").is_file():
            reasons.append(f"{LINUX_APP.name}/AppRun missing (the extracted AppImage, under local/)")
    else:
        if "gen1recomp.exe" not in names:
            reasons.append("gen1recomp.exe missing")
        if "portable.txt" not in names:
            reasons.append("portable.txt missing (the install would not be portable)")
    mods = install / "mods"
    if not (mods.is_dir() and "gen1-bridge" in os.listdir(mods)):
        reasons.append("mods/gen1-bridge junction missing")
    imported = kb_games.detect(install)
    if not imported:
        reasons.append(f"no completed ROM import in {install.parent.name}/{install.name} (a game's folder, blue/ or "
                       "another catalogued id, with its completion marker; an import made by an older engine release is "
                       "stale too: ENGINE_UPGRADE_PLAN B1)")
    # The game to run is the one imported game gen1-bridge has an adapter for (red/blue today, protocol/games.json);
    # imports of other families sit beside it (the pod holds Crystal and Emerald for later milestones) and don't count
    # until their adapters exist. Several adapter games imported: pass --game.
    playable = [g for g in imported if kb_games.load_games().adapter(g)]
    game = playable[0] if len(playable) == 1 else None
    if imported and not playable:
        reasons.append("no imported game has an adapter yet (" + ", ".join(imported) + ")")
    if len(playable) > 1:
        reasons.append(SEVERAL_IMPORTED + " (" + ", ".join(playable) + ")")
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
    if LINUX:
        return INSTALL / "lua-error.log"  # LÖVE's save folder, as %APPDATA%'s on Windows
    appdata = os.environ.get("APPDATA")
    return Path(appdata) / IDENTITY / "lua-error.log" if appdata else None


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
    hint: str | None = None  # the runner's reading of a silent timeout (ENGINE_UPGRADE_PLAN B1)

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
            "hint": self.hint,
        }


class Launch:
    """A running test instance. Filtered output streams into ``result.lines``.

        with launch("kb_handshake.lua", extra_env={...}) as run:
            run.wait_marker("KB_OVERWORLD", timeout=120)
            ...                             # talk to it over TCP
            result = run.wait(timeout=60)   # exit code, markers, counts

    Leaving the ``with`` block kills the game if it is still running.
    """

    def __init__(self, proc: subprocess.Popen, crash: Path | None, echo: bool):
        self.proc = proc
        self.result = RunResult(returncode=None, timed_out=False, elapsed=0.0)
        self._crash = crash
        self._crash_before = _stat(crash)
        self._echo = echo
        self._digest = hashlib.sha256()
        self._spots: list[str] = []
        self._error_seen = False
        self._cond = threading.Condition()
        self._done = False
        self._start = time.monotonic()
        self._reader = threading.Thread(target=self._consume, daemon=True)
        self._reader.start()

    def _consume(self):
        for raw in iter(self.proc.stdout.readline, b""):
            line = raw.decode("utf-8", errors="replace")
            shown = safe_line(line)
            with self._cond:
                if shown is not None:
                    self.result.lines.append(shown)
                    if shown.startswith(("LUA ERROR", "driver error")):
                        self._error_seen = True
                    self._cond.notify_all()
                else:
                    self.result.other_count += 1
                    self._digest.update(raw)
                    if self._error_seen:  # traceback lines: keep their locations only
                        for spot in LOCATION.findall(line):
                            if spot not in self._spots:
                                self._spots.append(spot)
            if shown is not None and self._echo:
                print(shown, flush=True)
        with self._cond:
            self._done = True
            self._cond.notify_all()

    def wait_marker(self, name: str, timeout: float) -> str | None:
        """The first safe line starting with name, or None if the game ended or time ran out."""
        deadline = time.monotonic() + timeout
        with self._cond:
            while True:
                found = self.result.marker(name)
                if found is not None:
                    return found
                left = deadline - time.monotonic()
                if self._done or left <= 0:
                    return None
                self._cond.wait(left)

    def wait(self, timeout: float) -> RunResult:
        """Waits for the game to exit (killing it after timeout) and finishes the result."""
        try:
            self.result.returncode = self.proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            self.result.timed_out = True
            self.proc.kill()
            self.result.returncode = self.proc.wait(timeout=30)
            if not any(line.startswith("KB_") for line in self.result.lines):
                # ENGINE_UPGRADE_PLAN B1: an import made by another engine release is "not imported": the
                # scripted boot stops at the importer and no driver marker ever prints (the launcher waits)
                self.result.hint = ("no driver marker before the timeout: the game is still at its launcher or "
                                    "importer; a ROM import from another Gen1Recomp release is stale and must be "
                                    "redone for this one (ENGINE_UPGRADE_PLAN B1)")
        self._reader.join(timeout=10)
        self.result.elapsed = time.monotonic() - self._start
        self.result.other_sha8 = self._digest.hexdigest()[:8]
        self.result.error_spots = list(self._spots)
        self.result.crash_log_touched = _stat(self._crash) != self._crash_before
        return self.result

    def kill(self):
        if self.proc.poll() is None:
            self.proc.kill()
            self.proc.wait(timeout=30)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.kill()
        return False


def _start(driver: Path | None, game: str | None, extra_env: dict[str, str] | None, echo: bool,
           install: Path) -> Launch:
    pf = preflight(install)
    if not pf.ok and not (game and len(pf.reasons) == 1 and pf.reasons[0].startswith(SEVERAL_IMPORTED)):
        raise RuntimeError("pre-flight not done: " + "; ".join(pf.reasons))
    game = game or pf.game
    if game not in VERSIONS:
        raise ValueError(f"game must be a catalogued game id, one of {VERSIONS}")

    env = dict(os.environ)
    env.pop("POKEPORT_DRIVER", None)
    env.update({
        "POKEPORT_VERSION": game,
        "POKEPORT_CONSOLE": "1",      # no hidden console: stdout stays our pipe
        "POKEPORT_BACKGROUND": "1",   # mutes audio
        "PYTHONUTF8": "1",
    })
    if driver is not None:
        env["POKEPORT_DRIVER"] = str(driver)
    if LINUX:
        # the data home holds the folder `install` names; OpenAL gets no sound device on a headless pod
        env.update({"XDG_DATA_HOME": str(install.parent), "ALSOFT_DRIVERS": "null"})
        command, cwd = [str(LINUX_APP / "AppRun"), f"--game={game}"], LINUX_APP
    else:
        command, cwd = [str(install / "gen1recomp.exe"), f"--game={game}"], install  # never --console: it takes stdout away
    if EXPORT_EXTRA > 0:
        env.setdefault("KB_DRIVER_EXTRA", str(EXPORT_EXTRA))
    env.update(extra_env or {})
    proc = subprocess.Popen(
        command, cwd=str(cwd), env=env,
        stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    )
    return Launch(proc, crash_log(), echo)


def launch(driver: str | Path, game: str | None = None, extra_env: dict[str, str] | None = None,
           echo: bool = True, install: Path = INSTALL) -> Launch:
    """Starts the test instance with a driver and returns at once."""
    driver = Path(driver)
    if not driver.is_absolute():
        driver = (DRIVERS / driver) if (DRIVERS / driver).is_file() else driver.resolve()
    if not driver.is_file():
        raise FileNotFoundError(driver)
    return _start(driver, game, extra_env, echo, install)


def launch_plain(game: str | None = None, extra_env: dict[str, str] | None = None, echo: bool = True,
                 install: Path = INSTALL) -> Launch:
    """M10: starts the test instance WITHOUT a driver, so the engine's own paced main loop runs as it
    does for a player (a driver turns its frame pacing off, refs/gen1recomp/main.lua:1776-1781, and
    steps the game once per frame). It boots straight into the game's title (--game=); gen1-bridge
    listens on its configured port. Without a driver stdout stays block-buffered, so its lines come
    late: talk to gen1-bridge over TCP instead of waiting for markers. Nothing ends it but the
    caller: leave the ``with`` block (or call kill()); it is never given a save to write."""
    return _start(None, game, extra_env, echo, install)


def run(driver: str | Path, game: str | None = None, timeout: float = 180.0,
        extra_env: dict[str, str] | None = None, echo: bool = True,
        install: Path = INSTALL) -> RunResult:
    """Starts the test instance with a driver and waits for it to exit."""
    with launch(driver, game=game, extra_env=extra_env, echo=echo, install=install) as running:
        return running.wait(timeout)


def write_report(name: str, result: RunResult) -> Path:
    REPORTS.mkdir(parents=True, exist_ok=True)
    path = REPORTS / f"e2e_{name}.json"
    path.write_text(json.dumps(result.summary(), indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return path


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("driver", help="driver file name in tools/e2e/drivers, or a path")
    parser.add_argument("--game", choices=VERSIONS, help="default: the one game imported in the test install")
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
        print(f"note: Gen1Recomp's crash log was written (not opened): {crash_log()}")
    return 0 if (result.returncode == 0 and not result.timed_out) else 1


if __name__ == "__main__":
    sys.exit(main())
