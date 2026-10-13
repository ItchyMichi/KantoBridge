"""M10 running hidden (DESIGN section 9; lib/kb_engine.lua "reliability (M10)"): while a client or
test session is connected, love.window.isVisible answers true for a window that isn't shown, so the
engine's loop keeps its normal frame cap (main.lua:1907-1924) and draws; a frame it fakes is paced to
at most 60 a second, and so is a minimised window's (which the loop would otherwise spin through at
hundreds a second); the wrapper is taken out when the link drops, when its keep-alive runs out, and
on a hot reload; nothing changes for a window that is shown, without a client, for a builder
connection, or an engine without love.window. The window is the tests' own fake (kblua window=True)."""

import pytest

from rig import Rig

pytestmark = pytest.mark.lua


def connected(role="test", **kw):
    rig = Rig(window=True, **kw)
    rig.world()
    peer = rig.connect()
    peer.hello(role=role)
    peer.messages()
    rig.step()
    return rig, peer


def is_visible(rig):
    return rig.b.window_table.isVisible()


def status(rig, peer):
    seq = peer.send("debug.status")
    rig.step()
    return [m for m in peer.messages() if m["type"] == "debug.status" and m.get("re") == seq][0]["data"]


def test_a_hidden_window_reports_visible_while_a_client_is_connected():
    rig = Rig(window=True)
    own = rig.b.window_table.isVisible
    rig.world()
    rig.step(3)
    assert rig.b.eval("rawequal")(rig.b.window_table.isVisible, own), "nothing installed without a client"
    peer = rig.connect()
    peer.hello()
    peer.messages()
    rig.step()
    assert not rig.b.eval("rawequal")(rig.b.window_table.isVisible, own), "the wrapper is in place"
    assert is_visible(rig) is True and rig.b.sleeps == [], "a shown window: passed through"
    rig.b.window.visible = False  # hidden (minimised windows stay visible on Windows: nothing to do there)
    assert is_visible(rig) is True, "hidden, but the loop keeps its cap"
    st = status(rig, peer)
    assert (st["hiddenActive"], st["hiddenVisible"], st["hiddenFaked"], st["hiddenInstalls"]) == (True, False, 1, 1)
    # the link drops: the window's own function is back at once
    peer.close()
    rig.step(2)
    assert rig.b.eval("rawequal")(rig.b.window_table.isVisible, own)
    assert is_visible(rig) is False


def test_faked_frames_are_paced_to_60_a_second():
    rig, peer = connected()
    rig.b.window.visible = False
    assert is_visible(rig) is True
    assert is_visible(rig) is True  # at once again: present() didn't wait, so the wrapper does
    sleeps = rig.b.sleeps
    assert len(sleeps) == 1 and 0 < sleeps[0] <= 1 / 60 + 1e-9
    rig.b.now = rig.b.now + 0.02
    assert is_visible(rig) is True and len(rig.b.sleeps) == 1, "a frame after 20 ms needs no wait"
    st = status(rig, peer)
    assert st["hiddenFaked"] == 3 and st["hiddenPaced"] == 1


def test_the_keep_alive_runs_out_without_steps():
    rig, peer = connected()
    rig.b.window.visible = False
    assert is_visible(rig) is True
    rig.b.now = rig.b.now + 1.5  # no logic step for over a second: the instance is gone or stuck
    assert is_visible(rig) is False
    rig.step()
    assert is_visible(rig) is True, "refreshed by the next live step"


def test_a_builder_connection_changes_nothing():
    rig, peer = connected(role="builder")
    rig.b.window.visible = False
    assert is_visible(rig) is False


def test_a_hot_reload_takes_the_wrapper_out_and_the_new_instance_puts_its_own():
    rig, peer = connected()
    rig.b.window.visible = False
    assert is_visible(rig) is True
    rig.b.reload()  # F5: the takeover closes the old instance's sockets and its wrapper
    assert is_visible(rig) is False, "the window's own answer again"
    peer = rig.connect()
    peer.hello()
    peer.messages()
    rig.step()
    assert is_visible(rig) is True
    st = status(rig, peer)
    assert st["hiddenActive"] is True and st["hiddenInstalls"] == 1, "the new instance's own count"


def test_no_window_module_no_wrapper():
    rig = Rig()
    rig.world()
    peer = rig.connect()
    peer.hello()
    peer.messages()
    rig.step(3)
    st = status(rig, peer)
    assert (st["hiddenActive"], st["hiddenInstalls"], st["hiddenVisible"]) == (False, 0, True)
    assert not any("step_failed" in line for line in rig.logs())


def test_a_minimised_window_is_reported_as_it_is():
    rig, peer = connected()
    rig.b.window.minimized = True  # Windows: still "visible" (SDL_WINDOW_SHOWN stays set)
    st = status(rig, peer)
    assert (st["hiddenMinimized"], st["hiddenVisible"], st["hiddenFaked"]) == (True, True, 0)


def test_a_minimised_windows_frames_are_paced_while_a_client_is_connected():
    rig, peer = connected()
    rig.b.window.minimized = True  # still "visible": the loop runs its normal cap and draws
    assert is_visible(rig) is True
    assert is_visible(rig) is True, "visible as it is, never faked"
    sleeps = rig.b.sleeps
    assert len(sleeps) == 1 and 0 < sleeps[0] <= 1 / 60 + 1e-9, "the second frame waits for its turn"
    for _ in range(58):  # a second of frames as fast as the loop can go
        assert is_visible(rig) is True
    assert abs(sum(rig.b.sleeps) - 59 / 60) < 1e-6, "60 frames take a second"
    st = status(rig, peer)
    assert (st["hiddenMinimizedFrames"], st["hiddenPaced"], st["hiddenFaked"]) == (60, 59, 0)
    # shown again: the engine's own pacing, untouched
    rig.b.window.minimized = False
    n = len(rig.b.sleeps)
    for _ in range(3):
        assert is_visible(rig) is True
    assert len(rig.b.sleeps) == n


def test_without_a_client_a_minimised_window_is_the_engines_own_business():
    rig, peer = connected()
    rig.b.window.minimized = True
    peer.close()
    rig.step(2)
    for _ in range(5):
        assert is_visible(rig) is True
    assert rig.b.sleeps == [], "no pacing without a client"
    # nor once the keep-alive ran out (an instance that stopped stepping)
    rig, peer = connected()
    rig.b.window.minimized = True
    rig.b.now = rig.b.now + 1.5
    for _ in range(5):
        assert is_visible(rig) is True
    assert rig.b.sleeps == []


def test_a_window_minimised_after_a_long_while_shown_starts_its_pacing_afresh():
    rig, peer = connected()
    rig.b.window.minimized = True
    assert is_visible(rig) is True
    rig.b.window.minimized = False
    rig.b.now = rig.b.now + 0.5  # shown for a while
    rig.step()
    rig.b.window.minimized = True
    assert is_visible(rig) is True and rig.b.sleeps == [], "the stale deadline is anchored again, no wait"
