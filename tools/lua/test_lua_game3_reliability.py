"""MG7 (docs/specs/GEN3_BRIDGE_PLAN.md §4.9): the game3 adapter's token and serial, save meta, the
state that outlives a hot reload, and running hidden, on the fake Gen 3 engine. Everything is made
up."""

import pytest

from adapter3 import Adapter3, plain

pytestmark = pytest.mark.lua


def test_bridge_token_keeps_the_first_token_across_reloads():
    a = Adapter3()
    bare = a.Engine.new(a.b.engine_opts())  # before game.ready: no state to keep it in
    assert bare.bridgeToken(b"abcd1234") == b"abcd1234" and bare.bridgeToken() == b"x"
    assert a.eng.bridgeToken(b"first111") == b"first111"
    assert a.eng.bridgeToken(b"second22") == b"first111" and a.eng.bridgeToken() == b"first111"
    second = a.new_instance()  # a hot reload: the token lives on the Game3 object
    assert second.bridgeToken(b"third333") == b"first111"


def test_next_serial_counts_across_reloads():
    a = Adapter3()
    assert a.Engine.new(a.b.engine_opts()).nextSerial() == 0
    assert [a.eng.nextSerial() for _ in range(3)] == [1, 2, 3]
    assert a.new_instance().nextSerial() == 4


def test_meta_of_the_session_or_a_save():
    a = Adapter3()
    assert plain(a.eng.metaOf()) == {}  # no session
    s = a.field()
    assert plain(a.eng.metaOf()) == {}  # a NEW GAME never saved
    s.meta = a.lua({"savedAt": 1700000100.7, "sessionStart": 1700000000, "engine": "0.3.85", "mods": {"x": 1}})
    assert plain(a.eng.metaOf()) == {"savedAt": 1700000100, "sessionStart": 1700000000, "engine": "0.3.85"}
    other = a.lua({"meta": {"savedAt": -5, "sessionStart": "soon", "engine": "v" * 40}})
    assert plain(a.eng.metaOf(other)) == {}
    nan = a.lua({"meta": {"savedAt": float("nan"), "sessionStart": 2 ** 60, "engine": 7}})
    assert plain(a.eng.metaOf(nan)) == {}


def test_persistent_tables_outlive_a_reload():
    a = Adapter3()
    assert a.Engine.new(a.b.engine_opts()).persistent(b"routes") is None
    t = a.eng.persistent(b"routes")
    t.kept = 5
    assert a.same(a.eng.persistent(b"routes"), t) and not a.same(a.eng.persistent(b"other"), t)
    assert a.new_instance().persistent(b"routes").kept == 5


def test_running_hidden_wraps_the_window_and_lets_go():
    a = Adapter3(window=True)
    eng, win = a.eng, a.b.window_table
    assert eng.runHidden(False, 0) is False and plain(eng.hiddenStats())["installs"] == 0
    assert eng.runHidden(True, a.b.now) is True
    st = plain(eng.hiddenStats())
    assert (st["installs"], st["active"], st["visible"]) == (1, True, True)
    a.b.window.visible = False  # hidden: the engine's frame cap is kept by answering visible
    assert win.isVisible() is True and plain(eng.hiddenStats())["faked"] == 1
    assert eng.runHidden(False, a.b.now) is False
    st = plain(eng.hiddenStats())
    assert (st["removals"], st["active"], st["visible"]) == (1, False, False) and win.isVisible() is False
