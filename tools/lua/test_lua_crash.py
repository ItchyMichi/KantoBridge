"""M10 crash recovery on the gen1-bridge side (act/kb_save.lua's save pairs, kb_party's operation
count, kb_pairing's session.bound.switched), wired by main.lua, on the simulated engine with save
files (fake_center.lua's E.useSaveFiles: mod.save is the live save's own modData, writeSave keeps a
deep copy of the save table as the slot's file, CONTINUE loads a copy of it, and Bridge.restart()
plays a new process).

save.begin.pair is new for every hidden save and written into that very save (its mod data, in the
same file), so a CONTINUE -- in this process, after a crash, after a restart -- reports it again in
save.loaded{fresh:false, pair}; at is the save's own meta.savedAt. save.loaded goes out after every
session.bound while in game (behind that step's queued party.* and save.begin), and when the hidden
game enters the world from the title (CONTINUE: the loaded save's pair; NEW GAME: fresh), never at
the title. ops counts the Pokemon operations sent since that save was loaded or announced (each
once; an operation made after a save still waiting to be announced waits for it). daycare is the
uid the save's own daycare holds, so it rolls back with the save. Every message the peer receives
is schema-checked (rig.Peer, strict). All data is made up."""

import re

import pytest

from battlesim import MOVE_NUMBERS
from centersim import CenterGame
from contentsim import ContentGame
from rig import HOST, Peer

pytestmark = pytest.mark.lua

PAIR = re.compile(r"^[0-9a-f]{16}$")
U1 = "0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b"
U2 = "6c1e2d3f-4a5b-4c6d-8e7f-a0b1c2d3e4f5"
N = MOVE_NUMBERS
WORLD = "kb-crash-world-1"


# ------------------------------------------------------------ helpers


def game(cls=CenterGame, slot="slot1", **kw):
    kw.setdefault("save_files", True)
    g = cls(**kw)
    if slot is not None:
        g.E.SaveData.setActiveSlot(b"blue", slot.encode())
    return g


def save(g, steps=2):
    """The hidden game's own save (the start menu's SAVE, F1), then a few steps."""
    assert g.E.game.writeSave(g.E.game) is True
    g.run(steps)
    begins = g.received("save.begin") if g.peer is not None else []
    return begins[-1]["data"] if begins else None


def record(g, slot="slot1"):
    """The save pair kept in a slot's save file (its gen1-bridge mod data)."""
    f = g.E.slotSaves[slot.encode()]
    rec = f.modData[b"gen1-bridge"].savePair
    return {"pair": rec.pair.decode(), "at": rec.at, "ops": rec.ops}


def saved_at(g, slot="slot1"):
    return g.E.slotSaves[slot.encode()].meta.savedAt


def bind(g, world=WORLD, multiplayer=False, steps=2):
    seq = g.send("session.bind", {"world": world, "multiplayer": multiplayer})
    g.run(steps)
    return seq


def loaded(g):
    return [m["data"] for m in g.received("save.loaded")]


def types(g, *wanted):
    return [m["type"] for m in g.log if not wanted or m["type"] in wanted]


def restart(g):
    """A crash (or a quit without saving) and a relaunch: a new process, a new connection."""
    g.b.restart()
    g.peer = None
    g.run(1)
    g.connect()
    g.run(1)


def gift(g, species="BIRDMON", level=5):
    g.script([["give_pokemon", species, level, True]])
    g.run(2)


# ------------------------------------------------------------ pairs


def test_each_save_gets_a_new_pair_written_into_the_save_itself():
    g = game()
    first = save(g)
    assert PAIR.match(first["pair"]) and first["reason"] == "menu"
    assert first["at"] == saved_at(g), "at: the save's own meta.savedAt"
    assert record(g) == {"pair": first["pair"], "at": first["at"], "ops": 0}, "in the save file"
    second = save(g)
    assert PAIR.match(second["pair"]) and second["pair"] != first["pair"], "a new pair per save"
    assert record(g)["pair"] == second["pair"] and second["at"] > first["at"]
    st = g.status()
    assert (st["savePairs"], st["saveBegun"], st["saveFresh"], st["saveOps"]) == (2, 2, False, 0)
    assert not g.received("error")


def test_the_center_save_carries_a_pair_too():
    g = game()
    g.give_starter()
    g.hurt()
    g.send("act.interact", {"map": "TEST_CENTER", "x": 3, "y": 1})
    g.play(choice=0)
    g.run(3)
    begin = g.last("save.begin")
    assert begin["reason"] == "center" and PAIR.match(begin["pair"]) and record(g)["pair"] == begin["pair"]


def test_save_loaded_follows_every_bind_in_game_with_the_ops_since():
    g = game()
    begin = save(g)
    seq = bind(g)
    bound = g.received("session.bound")[-1]
    assert bound["re"] == seq and bound["data"] == {"slot": 1, "switched": "none"}
    assert loaded(g) == [{"fresh": False, "pair": begin["pair"], "at": begin["at"], "ops": 0}]
    at_bound = g.log.index(bound)
    at_loaded = next(i for i, m in enumerate(g.log) if m["type"] == "save.loaded")
    assert at_bound < at_loaded, "after session.bound"
    # an operation after the save counts once, also when it is sent again after a snapshot
    gift(g)
    assert len(g.received("party.give")) == 1
    bind(g)
    assert loaded(g)[-1]["ops"] == 1
    g.send("resync")
    g.run(2)
    assert len(g.received("party.give")) == 2, "re-sent after the snapshot (same give id)"
    bind(g)
    assert loaded(g)[-1] == {"fresh": False, "pair": begin["pair"], "at": begin["at"], "ops": 1}
    # the next save starts over
    again = save(g)
    bind(g)
    assert loaded(g)[-1] == {"fresh": False, "pair": again["pair"], "at": again["at"], "ops": 0}
    assert g.status()["saveLoadedSent"] == 4


def test_new_game_and_continue_say_which_save_runs_and_the_title_says_nothing():
    g = game()
    begin = save(g)
    gift(g)  # after the save, never saved
    g.E.game.returnToTitle(g.E.game)
    g.run(2)
    bind(g)
    assert loaded(g) == [] and g.received("session.bound"), "nothing at the title"
    g.E.continueGame()
    g.run(2)
    assert loaded(g) == [{"fresh": False, "pair": begin["pair"], "at": begin["at"], "ops": 0}]
    assert g.party() == [], "the gift after the save is gone with it"
    g.E.game.returnToTitle(g.E.game)
    g.E.startNewGame()
    g.run(2)
    assert loaded(g)[-1] == {"fresh": True, "ops": 0}
    gift(g)
    bind(g)
    assert loaded(g)[-1] == {"fresh": True, "ops": 1}, "a New Game counts its own operations"
    first = save(g)
    bind(g)
    assert loaded(g)[-1] == {"fresh": False, "pair": first["pair"], "at": first["at"], "ops": 0}


def test_the_pair_comes_back_after_a_crash_and_continue():
    g = game()
    begin = save(g)
    gift(g)
    bind(g)
    assert loaded(g)[-1]["ops"] == 1
    restart(g)  # killed without saving
    hello = g.received("hello.ok")[-1]["data"]
    assert hello["session"] != g.received("hello.ok")[0]["data"]["session"], "a new process"
    n = len(loaded(g))
    seq = bind(g)
    assert g.received("session.bound")[-1]["re"] == seq and len(loaded(g)) == n, "at the title: nothing"
    g.E.continueGame()
    g.run(2)
    assert loaded(g)[-1] == {"fresh": False, "pair": begin["pair"], "at": begin["at"], "ops": 0}
    assert g.party() == []
    # and once more after another restart: the same save, the same pair
    restart(g)
    g.E.continueGame()
    g.run(2)
    assert loaded(g)[-1]["pair"] == begin["pair"]


def test_a_save_after_the_operations_comes_back_with_them():
    g = game()
    save(g)
    gift(g)
    later = save(g)
    restart(g)
    g.E.continueGame()
    g.run(2)
    assert loaded(g)[-1] == {"fresh": False, "pair": later["pair"], "at": later["at"], "ops": 0}
    assert [m.species for m in g.party()] == [b"BIRDMON"]


def test_a_save_without_a_record_gets_a_pair_from_its_own_meta():
    g = game()
    save(g)
    f = g.E.slotSaves[b"slot1"]
    f.modData[b"gen1-bridge"].savePair = None  # a save written before M10, or without gen1-bridge
    g.E.continueGame()
    g.run(2)
    first = loaded(g)[-1]
    assert first["fresh"] is False and PAIR.match(first["pair"]) and first["at"] == f.meta.savedAt
    assert first["ops"] == 0
    g.E.continueGame()
    g.run(2)
    assert loaded(g)[-1]["pair"] == first["pair"], "the same on every load"
    f.meta.savedAt = f.meta.savedAt + 1
    g.E.continueGame()
    g.run(2)
    assert loaded(g)[-1]["pair"] != first["pair"], "another save, another pair"
    assert g.status()["saveLegacy"] is True


def test_a_hot_reload_keeps_the_running_saves_identity():
    g = game()
    begin = save(g)
    gift(g)
    g.b.reload()
    g.connect()
    g.run(1)
    bind(g)
    assert loaded(g)[-1] == {"fresh": False, "pair": begin["pair"], "at": begin["at"], "ops": 1}


# ------------------------------------------------------------ ordering and queued operations


def test_queued_operations_and_saves_go_out_in_save_order_then_save_loaded():
    g = game(connect=False)
    g.give_starter()                               # before the save
    assert g.E.game.writeSave(g.E.game) is True    # no client: the save waits
    gift(g, "BIRDMON", 7)                          # after the save
    g.run(2)
    # the client says hello and binds in one go, as fabric-bridge does on connecting
    fake = g.b.net
    peer = Peer(g.rig, fake.connect(fake, HOST, 27151))
    g.peer = peer
    peer.send("hello", {"protocol": 1, "role": "test", "mod": "rig-0.1.0"})
    seq = peer.send("session.bind", {"world": WORLD, "multiplayer": False})
    g.run(1)
    order = [(m["type"], m["data"].get("source", m["data"].get("ops"))) for m in g.log
             if m["type"] in ("session.bound", "party.give", "save.begin", "save.loaded")]
    assert order == [("session.bound", None), ("party.give", "starter"), ("save.begin", None),
                     ("save.loaded", 0)], order
    assert g.received("session.bound")[0]["re"] == seq
    begin = g.last("save.begin")
    assert loaded(g)[0]["pair"] == begin["pair"] == record(g)["pair"]
    g.run(1)
    gives = [m["data"]["level"] for m in g.received("party.give")]
    assert gives == [5, 7], "the gift made after the save goes out after its save.begin"
    st = g.status()
    assert st["partyGivesHeld"] >= 1 and st["saveOps"] == 1
    bind(g)
    assert loaded(g)[-1]["ops"] == 1


def test_an_operation_made_before_the_save_counts_for_the_save_before():
    g = game(connect=False)
    gift(g)
    g.E.game.writeSave(g.E.game)
    g.run(1)
    g.connect()
    g.run(2)
    assert types(g, "party.give", "save.begin") == ["party.give", "save.begin"]
    bind(g)
    assert loaded(g)[-1]["ops"] == 0, "the save already holds it"


def test_a_reconnect_in_a_text_box_sends_the_snapshot_the_box_then_after_the_bind_save_loaded():
    g = game()
    begin = save(g)
    box = g.E.textBox(g.s("A MADE-UP LINE."), g.opts())
    g.push(box)
    g.run(3)
    first = [m["data"]["ui"] for m in g.received("ui.open")]
    assert first, "the text box is open"
    g.peer.close()
    g.run(2)
    g.log = []
    fake = g.b.net
    peer = Peer(g.rig, fake.connect(fake, HOST, 27151))
    g.peer = peer
    peer.send("hello", {"protocol": 1, "role": "client", "mod": "rig-0.1.0"})
    g.run(1)
    seq = peer.send("session.bind", {"world": WORLD, "multiplayer": False})
    g.run(2)
    order = [m["type"] for m in g.log if m["type"] in ("hello.ok", "snapshot", "ui.open", "session.bound",
                                                         "save.loaded")]
    assert order == ["hello.ok", "snapshot", "ui.open", "session.bound", "save.loaded"], order
    assert g.received("ui.open")[0]["data"]["kind"] == "text"
    assert g.received("session.bound")[0]["re"] == seq
    assert loaded(g) == [{"fresh": False, "pair": begin["pair"], "at": begin["at"], "ops": 0}]


# ------------------------------------------------------------ the save before a slot switch (M9)


def test_the_save_before_a_switch_keeps_the_pair_its_world_knows():
    g = game()
    g.E.slotFiles[b"slot1"] = True
    begin = save(g)
    gift(g)  # sent: ops 1
    bind(g, world="kb-crash-world-2", multiplayer=True)
    bound = g.received("session.bound")[-1]["data"]
    assert bound["switched"] == "saved" and bound["slot"] == 2
    assert g.last("save.begin") == begin, "the switch's save is never announced"
    assert record(g, "slot1") == {"pair": begin["pair"], "at": begin["at"], "ops": 1}
    # back to the offline slot and its game: the same pair, and the operation it holds
    bind(g)
    assert g.received("session.bound")[-1]["data"] == {"slot": 1, "switched": "title"}
    g.E.continueGame()
    g.run(2)
    assert loaded(g)[-1] == {"fresh": False, "pair": begin["pair"], "at": begin["at"], "ops": 1}


def test_a_new_game_moved_into_an_empty_slot_reports_itself_fresh():
    g = game()
    g.E.game.returnToTitle(g.E.game)
    g.E.startNewGame()
    g.run(2)
    gift(g)
    bind(g, world="kb-crash-world-3", multiplayer=True)
    assert g.received("session.bound")[-1]["data"] == {"slot": 2, "switched": "moved"}
    assert loaded(g)[-1] == {"fresh": True, "ops": 1}


# ------------------------------------------------------------ the daycare (rolls back with the save)


def daycare_game():
    g = game(ContentGame, at="daycare")
    g.give("STARTMON_C", 10)
    g.give("BIRDMON", 10)
    g.send("party.sync", {"party": [
        {"uid": U1, "dex": 7, "level": 10, "moves": [N["TACKLE_T"]], "hp": 30, "maxhp": 30},
        {"uid": U2, "dex": 16, "level": 10, "moves": [N["GUST_T"]], "hp": 30, "maxhp": 30},
    ], "box": {"count": 0}})
    g.run(3)
    return g


def play(g, limit=900):
    """Plays the hidden game's UIs like a player: A at each text arrow, YES at each YES/NO, A once
    on each mirror screen (the party menu), until nothing is open for a few steps."""
    answered, quiet = set(), 0
    for _ in range(limit):
        g.run()
        uis = g.open_uis()
        if not uis:
            quiet += 1
            if quiet >= 10:
                return
            continue
        quiet = 0
        top = max(uis)
        ui = uis[top]
        key = (top, ui.get("page"), ui.get("line"), ui.get("done"))
        if ui["kind"] == "text" and ui.get("waiting") and key not in answered:
            answered.add(key)
            g.send("ui.advance", {"ui": top})
        elif ui["kind"] == "choice" and top not in answered:
            answered.add(top)
            g.send("ui.pick", {"ui": top, "index": 0})
        elif ui["kind"] == "mirror" and top not in answered:
            answered.add(top)
            g.press("a")
    raise AssertionError("the UIs never settled")


def board(g):
    g.talk("daycare")
    play(g)
    assert [m["data"] for m in g.received("daycare.deposit")] == [{"uid": U1}]


def test_the_daycare_uid_follows_the_save_it_belongs_to():
    g = daycare_game()
    before = save(g)
    board(g)
    bind(g)
    assert loaded(g)[-1] == {"fresh": False, "pair": before["pair"], "at": before["at"], "ops": 1,
                             "daycare": U1}
    # killed without saving: the save from before the deposit has an empty daycare
    restart(g)
    g.E.continueGame()
    g.run(3)
    assert loaded(g)[-1] == {"fresh": False, "pair": before["pair"], "at": before["at"], "ops": 0}
    assert g.E.save.daycare is None or g.E.save.daycare.mon is None


def test_a_daycare_saved_with_its_boarder_reports_it_after_a_restart():
    g = daycare_game()
    board(g)
    after = save(g)
    restart(g)
    g.E.continueGame()
    g.run(3)
    assert loaded(g)[-1] == {"fresh": False, "pair": after["pair"], "at": after["at"], "ops": 0, "daycare": U1}
    assert g.status()["daycareHeld"] is True, "the boarded uid came back from the save's own mod data"


# ------------------------------------------------------------ the orchestrator's contract (M10)


def reconnect(g, role="test"):
    g.peer.close()
    g.run(2)
    g.connect(role)
    g.run(2)


def test_a_give_sent_again_after_a_reconnect_counts_once():
    """ops counts distinct operations: the re-send of an unconfirmed gift after a reconnect (same give
    id) is the same operation, and the server skips it too."""
    g = game()
    begin = save(g)
    gift(g)
    first = g.received("party.give")
    assert len(first) == 1
    bind(g)
    assert loaded(g)[-1]["ops"] == 1
    reconnect(g)
    again = g.received("party.give")
    assert len(again) == 2 and again[1]["data"]["give"] == first[0]["data"]["give"], "re-sent, same id"
    bind(g)
    assert loaded(g)[-1] == {"fresh": False, "pair": begin["pair"], "at": begin["at"], "ops": 1}
    reconnect(g)
    bind(g)
    assert loaded(g)[-1]["ops"] == 1 and len(g.received("party.give")) == 3
    assert g.status()["saveOpsSent"] == 1


def test_an_operation_queued_offline_counts_once_when_it_first_goes_out():
    g = game()
    begin = save(g)
    g.peer.close()
    g.run(2)
    gift(g)  # no client: it waits
    g.connect()
    g.run(2)
    bind(g)
    assert len(g.received("party.give")) == 1
    assert loaded(g)[-1] == {"fresh": False, "pair": begin["pair"], "at": begin["at"], "ops": 1}


def test_the_daycare_is_the_live_one_after_a_reconnect():
    """save.loaded.daycare is what the running game's daycare holds: after a deposit nobody saved, a
    reconnect still reports it (the server keeps it in its daycare); only a reload of an older save
    takes it back (test_the_daycare_uid_follows_the_save_it_belongs_to)."""
    g = daycare_game()
    before = save(g)
    board(g)
    reconnect(g)
    assert [m["data"] for m in g.received("daycare.deposit")] == [{"uid": U1}, {"uid": U1}], \
        "sent again until a sync confirms it, the same operation"
    bind(g)
    assert loaded(g)[-1] == {"fresh": False, "pair": before["pair"], "at": before["at"], "ops": 1, "daycare": U1}


# ------------------------------------------------------------ a dropped client: nothing waits for Minecraft


def test_a_dropped_client_lets_go_of_every_hold():
    """The step driver's held direction and a button held on a mirror screen are let go at once
    (Bill's PC hold, a waiting slot switch and the mirror: test_lua_pc, test_lua_pairing,
    test_lua_mirror; a battle waiting for Cobblemon stays pending: test_lua_battle)."""
    g = game()
    g.push(g.E.screenState(b"TrainerCard", g.opts(sticky=True)))
    g.run()
    g.send("act.button", {"button": "select", "how": "down"})
    g.run(3)
    assert g.E.game.input.state.select is True
    g.peer.close()
    g.run(2)
    assert not g.E.game.input.state.select and ("release", "select") in g.taps()
    g.connect()
    g.E.uiPop()
    g.run(3)
    g.send("move.step", {"dir": "down", "n": 1})
    g.run(2)
    assert g.E.game.input.state.down is True, "held while the step runs"
    g.peer.close()
    g.run(1)
    assert not g.E.game.input.state.down
    g.run(30)
    assert not g.E.game.input.state.down and g.E.ow.player.moving is False, "the game goes on, standing"
