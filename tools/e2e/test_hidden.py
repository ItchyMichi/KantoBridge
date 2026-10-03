"""M10 end to end, on the real engine: running hidden (DESIGN section 9, "Gen1Recomp keeps full speed
while minimized and unfocused"; lib/kb_engine.lua "reliability (M10)").

The engine's loop caps a window that isn't visible at 10 frames a second and then catches up at most
about 2 logic steps a frame (refs/gen1recomp/main.lua:1739-1756, src/core/FixedStep.lua:75-86): a
third of the logic speed. A test driver can't show that: a driver run turns the frame pacing off and
steps the game once per frame (main.lua:1637-1642, :993-1011). So this test launches the test
instance WITHOUT a driver (run_gen1recomp.launch_plain: the paced loop a player runs), finds the port
gen1-bridge listens on from the process's own listening socket, reads gen1-bridge's logic step counter
(debug.status steps, Game.logicStep) and drawn frames (perfFrames) over TCP, and changes the window
from outside, as a player or a launcher would (Win32 ShowWindow on the game's own window):

  1. shown, a client connected                 the baseline (about 60 steps a second)
  2. hidden (SW_HIDE), no client connected     the engine alone: about 20 (the "before")
  3. hidden, a client connected                gen1-bridge keeps the normal cap: about 60 (the "after"),
                                               its frames paced to about 60
  4. minimised, a client connected             about 60 steps (LÖVE's isVisible stays true for a
                                               minimised window on Windows), and its frames paced to
                                               about 60 a second by gen1-bridge (the loop alone spins)
  5. minimised, no client connected            about 60 steps as well; the frames are the engine's own
                                               (not paced by gen1-bridge: hundreds a second here)
  6. shown again                               about 60

Windows only. The game is killed at the end (it never saved anything). Output: rates and counts.
"""

import asyncio
import re
import subprocess
import sys
import time

import pytest

from fake_minecraft import FakeMinecraft
from run_gen1recomp import launch_plain, preflight

PHASE_SECONDS = 4.0
SW_HIDE, SW_SHOW, SW_MINIMIZE, SW_RESTORE = 0, 5, 6, 9
PORTS = range(27151, 27160)  # the configured port and its fallback span


def _user32():
    import ctypes
    from ctypes import wintypes
    user32 = ctypes.WinDLL("user32", use_last_error=True)
    user32.ShowWindow.argtypes = [wintypes.HWND, ctypes.c_int]
    return ctypes, wintypes, user32


def game_window(pid):
    """The game's own top-level window (SDL's window class), by process id."""
    ctypes, wintypes, user32 = _user32()
    found = []
    proc = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

    def each(hwnd, _):
        owner = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(owner))
        if owner.value == pid:
            name = ctypes.create_unicode_buffer(64)
            user32.GetClassNameW(hwnd, name, 64)
            if name.value == "SDL_app":
                found.append(hwnd)
        return True

    user32.EnumWindows(proc(each), 0)
    return found[0] if found else None


def show(hwnd, how):
    _, _, user32 = _user32()
    user32.ShowWindow(hwnd, how)


def listening_port(pid, timeout=120):
    """The port of the process's own listening socket in gen1-bridge's span (netstat), so the test
    never talks to another Gen1Recomp on this PC."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        out = subprocess.run(["netstat", "-ano", "-p", "TCP"], capture_output=True, text=True).stdout
        for row in out.splitlines():
            parts = row.split()
            if len(parts) >= 5 and parts[3] == "LISTENING" and parts[4] == str(pid):
                m = re.match(r"127\.0\.0\.1:(\d+)$", parts[1])
                if m and int(m.group(1)) in PORTS:
                    return int(m.group(1))
        time.sleep(1)
    return None


async def _connect(port):
    mc = FakeMinecraft(port=port, role="test", ping_ms=500)
    await mc.connect(attempts=30)
    await mc.hello()
    await mc.expect("snapshot", timeout=10)
    return mc


async def _status(mc):
    return (await mc.request("debug.status", reply_type="debug.status", timeout=10))["data"]


async def _measure(port, hwnd):
    report = {}
    mc = await _connect(port)
    await asyncio.sleep(2.0)  # past the boot screens' first frames

    async def connected(name):
        a = await _status(mc)
        t0 = time.monotonic()
        await asyncio.sleep(PHASE_SECONDS)
        b = await _status(mc)
        dt = time.monotonic() - t0
        report[name] = {"steps_s": round((b["steps"] - a["steps"]) / dt, 1),
                        "frames_s": round((b["perfFrames"] - a["perfFrames"]) / dt, 1),
                        "visible": b["hiddenVisible"], "minimized": b["hiddenMinimized"],
                        "wrapper": b["hiddenActive"], "faked": b["hiddenFaked"] - a["hiddenFaked"],
                        "minimized_frames": b["hiddenMinimizedFrames"] - a["hiddenMinimizedFrames"]}

    async def alone(name):
        nonlocal mc
        a = await _status(mc)
        t0 = time.monotonic()
        await mc.close()
        await asyncio.sleep(PHASE_SECONDS)
        mc = await _connect(port)
        b = await _status(mc)
        dt = time.monotonic() - t0
        report[name] = {"steps_s": round((b["steps"] - a["steps"]) / dt, 1),
                        "frames_s": round((b["perfFrames"] - a["perfFrames"]) / dt, 1),
                        "visible": b["hiddenVisible"], "minimized": b["hiddenMinimized"],
                        "removals": b["hiddenRemovals"] - a["hiddenRemovals"]}

    await connected("shown")
    show(hwnd, SW_HIDE)
    await asyncio.sleep(0.5)
    await alone("hidden_no_client")
    await connected("hidden_client")
    show(hwnd, SW_SHOW)
    await asyncio.sleep(0.5)
    show(hwnd, SW_MINIMIZE)
    await asyncio.sleep(0.5)
    await connected("minimized_client")
    await alone("minimized_no_client")
    show(hwnd, SW_RESTORE)
    await asyncio.sleep(0.5)
    await connected("shown_again")
    st = await _status(mc)
    report["installs"], report["paced"] = st["hiddenInstalls"], st["hiddenPaced"]
    report["errors"] = [m["data"]["code"] for m in mc.received if m["type"] == "error"]
    report["problems"] = len(mc.conn.problems)
    await mc.close()
    return report


@pytest.mark.e2e
@pytest.mark.timeout(400)
@pytest.mark.skipif(sys.platform != "win32", reason="the window is shown, hidden and minimised through Win32")
def test_the_hidden_game_keeps_full_speed_while_a_client_is_connected():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    with launch_plain() as running:
        port = listening_port(running.proc.pid)
        assert port is not None, "gen1-bridge never listened"
        hwnd = None
        deadline = time.monotonic() + 60
        while hwnd is None and time.monotonic() < deadline:
            hwnd = game_window(running.proc.pid)
            time.sleep(0.5)
        assert hwnd is not None, "no game window"
        try:
            report = asyncio.run(asyncio.wait_for(_measure(port, hwnd), 300))
        finally:
            show(hwnd, SW_RESTORE)
        running.kill()
        result = running.wait(timeout=30)
    for name in ("shown", "hidden_no_client", "hidden_client", "minimized_client", "minimized_no_client",
                 "shown_again"):
        print(f"{name}:", report[name])
    print("wrapper installs:", report["installs"], "paced frames:", report["paced"])
    print("other output lines:", result.other_count, "error spots:", result.error_spots)

    assert report["errors"] == [] and report["problems"] == 0
    assert 55 <= report["shown"]["steps_s"] <= 65
    # before: the engine alone, hidden, at a third of its speed
    assert report["hidden_no_client"]["steps_s"] <= 35 and report["hidden_no_client"]["removals"] >= 1
    # after: a client connected keeps it at full speed, and the frames drawn (the mirror's) paced
    hidden = report["hidden_client"]
    assert hidden["visible"] is False and hidden["wrapper"] is True and hidden["faked"] > 0
    assert 55 <= hidden["steps_s"] <= 65 and hidden["frames_s"] <= 70
    # minimised (Windows): still "visible" to LOVE, full speed with or without a client; with a
    # client its frames are paced to about 60 a second (the loop alone spins through hundreds)
    minimized = report["minimized_client"]
    assert minimized["minimized"] is True and minimized["faked"] == 0 and minimized["minimized_frames"] > 0
    assert 55 <= minimized["steps_s"] <= 65 and 55 <= minimized["frames_s"] <= 65, minimized
    alone = report["minimized_no_client"]
    assert 55 <= alone["steps_s"] <= 65 and alone["removals"] >= 1
    assert alone["frames_s"] >= minimized["frames_s"] - 5, "without a client: the engine's own frames, not ours"
    assert 55 <= report["shown_again"]["steps_s"] <= 65 and report["shown_again"]["visible"] is True
    assert not result.crash_log_touched and not result.error_spots
