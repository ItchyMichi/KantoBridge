"""MG7 (docs/specs/GEN3_BRIDGE_PLAN.md §4.8): the game3 adapter's region acts (the park hold, the start
from the title, the story markers) on the fake Gen 3 engine. Everything is made up."""

import pytest

from adapter3 import Adapter3, plain

pytestmark = pytest.mark.lua

TAG = b"kb_park"


def locked(a):
    return a.E.Field._locks[TAG] is True


# ------------------------------------------------------------ the park hold


def test_hold_away_refuses_outside_the_field():
    a = Adapter3()
    assert a.eng.holdAway() == (False, b"not_ingame")
    assert a.eng.awayHeld() is False and plain(a.E.audioCalls) == {}


def test_hold_away_locks_the_field_and_fades_the_music():
    a = Adapter3()
    a.field()
    assert a.eng.holdAway() is True
    assert locked(a) and a.eng.awayHeld() is True
    assert plain(a.E.audioCalls) == [{"op": "fadeOut", "speed": 4}]
    # the hold is a lock: the observer sees a held (scripted) field
    assert a.facts()["scripted"] is True
    assert a.eng.holdAway() is True and len(a.E.audioCalls) == 1  # held again: nothing new


def test_hold_away_fails_when_the_lock_does_not_take():
    a = Adapter3()
    a.field()
    a.E.Field.lock = a.b.eval("function() end")  # a lock that leaves no tag
    assert a.eng.holdAway() == (False, b"failed") and a.eng.awayHeld() is False


def test_away_held_puts_the_lock_back_after_the_tags_are_wiped():
    a = Adapter3()
    a.field()
    a.eng.holdAway()
    a.E.Field._locks = a.lua({})  # Field.start / Field.stop wipe every tag (field.lua:109, :155)
    assert not locked(a)
    assert a.eng.awayHeld() is True and locked(a)
    a.E.enterField(b"EM_TEST_TOWN", 5, 6, b"down")  # CONTINUE again: Field.start
    assert not locked(a) and a.eng.awayHeld() is True and locked(a)
    a.E.leaveField()  # out of the field: still held, nothing to lock
    assert a.eng.awayHeld() is True and not locked(a)


def test_release_away_unlocks_and_fades_the_music_back():
    a = Adapter3()
    a.field()
    assert a.eng.releaseAway() is False  # nothing held
    a.eng.holdAway()
    assert a.eng.releaseAway() is True
    assert not locked(a) and a.eng.awayHeld() is False
    # the song that played, by its id (currentSong() is the playing record, audio.lua:447-452, :1507)
    assert plain(a.E.audioCalls)[1] == {"op": "fadeIn", "song": 412, "speed": 4}
    assert a.eng.releaseAway() is False and a.facts()["scripted"] is False


def test_no_song_playing_brings_none_back():
    a = Adapter3()
    a.field()
    a.E.song = None  # silence (a map without music, or a fade already over)
    a.eng.holdAway()
    a.eng.releaseAway()
    assert [c["op"] for c in plain(a.E.audioCalls)] == ["fadeOut"]


def test_a_refused_unlock_is_retried_by_away_held():
    a = Adapter3()
    a.field()
    a.eng.holdAway()
    a.E.refuseUnlock = True  # a fly landing, a fall warp, a field callback (field.lua:30-37)
    assert a.eng.releaseAway() is True and locked(a)
    assert a.eng.awayHeld() is False and locked(a)  # not held any more; the tag still to go
    assert a.eng.awayHeld() is False and locked(a)
    a.E.refuseUnlock = False
    assert a.eng.awayHeld() is False and not locked(a)
    assert a.eng.awayHeld() is False and a.eng.releaseAway() is False  # the record is gone
    assert [c["op"] for c in plain(a.E.audioCalls)] == ["fadeOut", "fadeIn"]


def test_a_park_while_a_release_waits_holds_again():
    a = Adapter3()
    a.field()
    a.eng.holdAway()
    a.E.refuseUnlock = True
    a.eng.releaseAway()
    a.E.refuseUnlock = False
    assert a.eng.holdAway() is True and locked(a) and a.eng.awayHeld() is True and locked(a)


# ------------------------------------------------------------ the start from the title


def test_start_from_title_continues_a_save():
    a = Adapter3()
    save = a.E.newSession(b"EM_TEST_HOUSE", 3, 4, b"up")
    a.E.slotSave(b"slot1", save)
    a.E.SaveData.setActiveSlot(b"emerald", b"slot1")
    assert a.eng.startFromTitle() == b"continue"
    assert [x["action"] for x in plain(a.E.bootActions)] == ["continue"]
    f = a.facts()
    assert (f["inGame"], f["map"], f["x"], f["y"]) == (True, "EM_TEST_HOUSE", 3, 4)
    assert a.eng.startFromTitle() == (None, b"not_title")


def test_start_from_title_starts_a_new_game_without_a_save():
    a = Adapter3()
    assert a.eng.startFromTitle() == b"new"
    start = a.E.newGameStarts[1]
    assert a.same(start.boot, a.E.BootUi) and a.same(start.state, a.E.game.boot)
    assert a.E.game.boot.phase == b"oak" and plain(a.E.bootActions) == {}
    assert a.facts() == {"inGame": False, "starting": True, "top": "screen", "topName": "BirchSpeech"}
    assert a.eng.startFromTitle() == (None, b"menu")  # Birch's speech already under way
    assert len(a.E.newGameStarts) == 1


def test_start_from_title_from_the_main_menu():
    a = Adapter3()
    a.E.game.boot.phase = b"menu"
    assert a.eng.startFromTitle() == b"new" and a.E.game.boot.phase == b"oak"


def test_start_from_title_refuses_the_field_and_odd_phases():
    a = Adapter3()
    a.field()
    assert a.eng.startFromTitle() == (None, b"not_title")
    a.E.leaveField()
    a.E.game.phase = b"quest_log"
    assert a.eng.startFromTitle() == (None, b"failed")
    a.E.game.phase, a.E.game.boot = b"boot", None
    assert a.eng.startFromTitle() == (None, b"failed")
    assert plain(a.E.bootActions) == {} and plain(a.E.newGameStarts) == {}


def test_a_failing_start_is_logged_and_failed():
    a = Adapter3()
    a.E.newGameFails = True
    assert a.eng.startFromTitle() == (None, b"failed")
    assert a.lines() == [("warn", "engine.new_game_failed")]


def test_a_continue_that_fails_or_never_reaches_the_field_is_failed():
    a = Adapter3()
    a.E.slotSave(b"slot1", a.E.newSession(b"EM_TEST_HOUSE", 3, 4, b"up"))
    a.E.SaveData.setActiveSlot(b"emerald", b"slot1")
    original = a.E.Game3._handleBootAction
    a.E.Game3._handleBootAction = a.b.eval("function() end")  # silent: the save didn't load
    assert a.eng.startFromTitle() == (None, b"failed")
    a.E.Game3._handleBootAction = a.b.eval("function() error('load failed') end")
    assert a.eng.startFromTitle() == (None, b"failed")
    assert a.lines() == [("warn", "engine.continue_failed")]
    a.E.Game3._handleBootAction = original
    a.E.Game3._hasContinueSave = a.b.eval("function() error('no options file') end")  # unreadable: NEW GAME
    assert a.eng.startFromTitle() == b"new"


# ------------------------------------------------------------ the story markers


def test_progress_markers_by_emerald_names():
    a = Adapter3()
    markers = a.b.module("lib/kb_games.lua").games.emerald.markers  # the catalogue's
    assert plain(markers) == {"starter": "FLAG_SYS_POKEMON_GET", "champion": "FLAG_SYS_GAME_CLEAR"}
    assert plain(a.eng.progressMarkers(markers)) == {"starter": False, "champion": False}  # the title
    a.field()
    a.E.setFlag(b"FLAG_SYS_POKEMON_GET")
    assert plain(a.eng.progressMarkers(markers)) == {"starter": True, "champion": False}
    a.E.setFlag(b"FLAG_SYS_GAME_CLEAR")
    assert plain(a.eng.progressMarkers(markers)) == {"starter": True, "champion": True}


def test_progress_markers_never_read_the_firered_numbers():
    # the engine's own name lookups go through FireRed's table (flags.lua:17-48): the adapter
    # resolves the names itself and asks for numbers
    a = Adapter3()
    a.field()
    a.E.Space.store.flags[a.E.Flags.IDS.FLAG_SYS_POKEMON_GET] = True  # FireRed's number for the name
    got = plain(a.eng.progressMarkers(a.lua({"starter": "FLAG_SYS_POKEMON_GET", "odd": "FLAG_NO_SUCH",
                                               "skip": 5})))
    assert got == {"starter": False, "odd": False}
    assert plain(a.eng.progressMarkers(None)) == {}
