"""M9's online save pairing (act/kb_pairing.lua and kb_engine's save-slot seams, wired by main.lua) on
the simulated engine: fake_center.lua's SaveData slot registry, the title, New Game and CONTINUE.

session.bind{world, multiplayer:true} gives each world its own save slot (created on the first bind,
labelled for Gen1Recomp's launcher, kept in mod.cache's session/pairings.json, never in a slot's own
save), switched to at runtime: at the title at once, a New Game never saved as it is when the target
is empty, a saved game after saving it into its own slot (then the title); otherwise the switch waits
(a log warning says why; Cobblemon's syncs wait, bag.use is refused). Singleplayer keeps the current
slot, or goes back to the offline (home) slot from a world's. session.bound{slot} answers once the
hidden game is on the slot. Every message the peer receives is schema-checked (rig.Peer). All data is
made up; world ids are test strings and never appear in a log line."""

import json

import pytest

from centersim import CenterGame

pytestmark = pytest.mark.lua

A, B, SOLO = "world-a-0001", "world-b-0002", "solo-world-03"
# MG1: one pairing file per game (the process's own); the legacy install-wide file is read once
LEGACY = "session/pairings.json"


def file_for(game):
    return f"session/pairings.{game}.json"


FILE = file_for("blue")


# ------------------------------------------------------------ helpers


def game(home="slot1", saved=True, at_title=True, **kw):
    """A CenterGame whose engine has the home slot active (holding a save unless saved=False); at the
    title (a TitleState screen) or, with at_title=False, in the loaded home game."""
    g = CenterGame(**kw)
    if home is not None:
        g.E.SaveData.setActiveSlot(b"blue", home.encode())
        if saved:
            g.E.slotFiles[home.encode()] = True
    if at_title:
        g.E.game.returnToTitle(g.E.game)
        g.E.titleRebuilds = 0
    else:
        g.E.continueGame()
    g.run(2)
    return g


def bind(g, world, multiplayer=True, steps=2):
    seq = g.send("session.bind", {"world": world, "multiplayer": multiplayer})
    g.run(steps)
    return seq


def bounds(g):
    return [(m["data"]["slot"], m.get("re")) for m in g.received("session.bound")]


def errors(g):
    return [(m["data"]["code"], m.get("re")) for m in g.received("error")]


def active(g):
    a = g.E.activeSlot
    return a.decode() if a is not None else None


def registry(g):
    reg = g.E.slotReg
    return [reg.list[i].decode() for i in range(1, len(reg.list) + 1)]


def label(g, slot):
    v = g.E.slotReg.names[slot.encode()]
    return v.decode() if v is not None else None


def store(g, game="blue"):
    raw = g.b.cache_files().get(file_for(game))
    return json.loads(raw.decode("utf-8")) if raw is not None else None


def calls(g, name):
    c = g.E.slotCalls
    return sum(1 for i in range(1, len(c) + 1) if c[i] == name.encode())


def on_title(g):
    top = g.E.stack.top(g.E.stack)
    return top is not None and top.screenId == b"TitleState"


def in_game(g):
    top = g.E.stack.top(g.E.stack)
    return top is not None and top.isOverworld is True


def warnings(g, code):
    return [m["data"] for m in g.received("log") if m["data"]["code"] == code]


def no_world_ids_logged(g):
    text = "\n".join(g.b.log_lines())
    return all(w not in text for w in (A, B, SOLO))


# ------------------------------------------------------------ at the title


def test_a_new_world_gets_its_own_slot_at_the_title():
    g = game()
    seq = bind(g, A)
    assert bounds(g) == [(2, seq)]
    assert active(g) == "slot2" and registry(g) == ["slot1", "slot2"]
    assert label(g, "slot2").startswith("MULTIPLAYER ") and len(label(g, "slot2")) == len("MULTIPLAYER ") + 8
    assert store(g) == {"format": 1, "games": {"blue": {"home": "slot1", "worlds": {A: {"slot": "slot2"}}}}}
    assert on_title(g) and g.E.titleRebuilds == 0, "nothing was open over the title"
    st = g.status()
    assert (st["pairingCreated"], st["pairingSwitchTitle"], st["pairingSwitches"], st["pairingPending"]) == \
        (1, 1, 1, False)
    assert (st["pairingWorlds"], st["pairingHome"], st["saveBinds"], st["saveSlot"]) == (1, 1, 1, 2)
    assert g.b.mod.exports.slot == 2 and g.b.mod.exports.pairingPending is False
    assert not errors(g) and no_world_ids_logged(g)
    assert any(line.startswith("[info] [gen1-bridge] session.bound ") and "created=true" in line
               and "worldLen=12" in line and "switched=title" in line and "slot=2" in line
               for line in g.b.log_lines())


def test_a_known_world_keeps_its_slot_and_another_world_gets_another():
    g = game()
    first = bind(g, A)
    again = bind(g, A)
    other = bind(g, B)
    back = bind(g, A)
    assert bounds(g) == [(2, first), (2, again), (3, other), (2, back)]
    assert calls(g, "createSlot") == 2, "one slot per world"
    assert active(g) == "slot2" and registry(g) == ["slot1", "slot2", "slot3"]
    worlds = store(g)["games"]["blue"]["worlds"]
    assert worlds == {A: {"slot": "slot2"}, B: {"slot": "slot3"}}
    st = g.status()
    assert (st["pairingCreated"], st["pairingReused"], st["pairingSwitches"]) == (2, 2, 3)


def test_an_open_title_menu_is_rebuilt_for_the_new_slot():
    g = game()
    g.push(g.E.menu(b"TitleMenu"))  # the CONTINUE / NEW GAME menu over the title, built for slot1
    g.run(1)
    bind(g, A)
    assert active(g) == "slot2" and g.E.titleRebuilds == 1 and on_title(g)
    assert g.status()["pairingRebuilt"] == 1


def test_singleplayer_without_pairings_keeps_the_current_slot_and_writes_nothing():
    g = game()
    seq = bind(g, SOLO, multiplayer=False)
    assert bounds(g) == [(1, seq)] and active(g) == "slot1"
    assert g.b.cache_files() == {}, "no pairing file for singleplayer alone"
    assert calls(g, "createSlot") == 0 and calls(g, "setActiveSlot") == 1  # the test's own setup


def test_singleplayer_after_a_world_goes_back_home():
    g = game()
    bind(g, A)
    seq = bind(g, SOLO, multiplayer=False)
    assert bounds(g)[-1] == (1, seq) and active(g) == "slot1"
    st = g.status()
    assert (st["pairingHomeReturns"], st["pairingSwitchTitle"], st["pairingHome"]) == (1, 2, 1)
    # and a singleplayer world on the home slot changes nothing again
    bind(g, SOLO, multiplayer=False)
    assert bounds(g)[-1][0] == 1 and g.status()["pairingSwitches"] == 2


def test_singleplayer_remembers_a_new_offline_slot_once_worlds_are_paired():
    g = game()
    bind(g, A)
    bind(g, SOLO, multiplayer=False)
    # the player picks another offline slot in Gen1Recomp's launcher
    g.E.SaveData.setActiveSlot(b"blue", b"slot7")
    bind(g, SOLO, multiplayer=False)
    assert bounds(g)[-1][0] == 7 and store(g)["games"]["blue"]["home"] == "slot7"
    bind(g, B)
    bind(g, SOLO, multiplayer=False)
    assert active(g) == "slot7"


def test_without_any_slot_a_home_is_created_on_the_way_back():
    g = game(home=None)
    bind(g, A)
    assert active(g) == "slot1" and registry(g) == ["slot1"]
    assert "home" not in store(g)["games"]["blue"]
    bind(g, SOLO, multiplayer=False)
    assert active(g) == "slot2" and bounds(g)[-1][0] == 2
    assert store(g)["games"]["blue"]["home"] == "slot2" and label(g, "slot2") is None
    assert g.status()["pairingHomes"] == 1


def test_a_flat_legacy_save_becomes_slot1_before_any_world_slot():
    g = game(home=None)
    g.E.legacySave = True  # a flat save written in this process: the slot cache still says none
    bind(g, A)
    assert calls(g, "refreshSlotResolution") == 1 and g.E.migrations == 1
    assert registry(g) == ["slot1", "slot2"] and active(g) == "slot2"
    assert store(g)["games"]["blue"]["home"] == "slot1"
    bind(g, SOLO, multiplayer=False)
    assert active(g) == "slot1"


def test_the_pairing_survives_a_hot_reload_and_a_restart():
    g = game()
    bind(g, A)
    bind(g, SOLO, multiplayer=False)
    g.b.reload()
    g.connect()
    bind(g, A)
    assert bounds(g)[-1][0] == 2 and calls(g, "createSlot") == 1, "hot reload: the same slot"
    # a restart: a new process with the same mod.cache files and the same slot registry
    files = g.b.cache_files()
    h = CenterGame(mod_cache=files)
    for slot in registry(g):
        h.E.SaveData.setActiveSlot(b"blue", slot.encode())
    h.E.SaveData.setActiveSlot(b"blue", b"slot1")
    h.E.game.returnToTitle(h.E.game)
    h.run(1)
    seq = bind(h, A)
    assert bounds(h) == [(2, seq)] and calls(h, "createSlot") == 0 and active(h) == "slot2"
    other = bind(h, B)
    assert bounds(h)[-1] == (3, other)


# ------------------------------------------------------------ in game


def test_a_saved_game_is_saved_into_its_own_slot_then_the_title():
    g = game(at_title=False)
    writes = g.E.saveWrites
    seq = bind(g, A)
    assert bounds(g) == [(2, seq)]
    assert g.E.saveWrites == writes + 1 and g.E.slotWrites[b"slot1"] == 1, "saved into its own slot first"
    assert active(g) == "slot2" and on_title(g)
    assert not g.received("save.begin"), "the old pairing's save is never announced"
    st = g.status()
    assert (st["pairingSwitchSaved"], st["saveSwitchWrites"], st["saveBegun"], st["ingame"]) == (1, 1, 0, False)
    # New Game into the empty slot: the first save lands there
    g.E.startNewGame()
    g.run(2)
    g.E.game.writeSave(g.E.game)
    g.run(2)
    assert g.E.slotWrites[b"slot2"] == 1 and g.E.slotWrites[b"slot1"] == 1
    assert g.received("save.begin")[-1]["data"]["reason"] == "menu"


def test_a_busy_game_waits_holds_the_new_worlds_data_then_switches():
    g = game(at_title=False)
    g.E.ow.runner.running = True  # a script runs: the F1 gate is closed
    seq = bind(g, A, steps=5)
    assert bounds(g) == [] and active(g) == "slot1" and in_game(g)
    assert [w["level"] for w in warnings(g, "session.switch_waiting")] == ["warn"]
    st = g.status()
    assert (st["pairingPending"], st["pairingWaitBusy"], st["pairingWaits"]) == (True, 1, 1)
    assert g.b.mod.exports.pairingPending is True
    # the new world's data waits: a sync is held, a bag use refused
    g.send("party.sync", {"party": [{"uid": "00000000-0000-4000-8000-000000000001", "dex": 7, "level": 5,
                                     "moves": [33], "hp": 20, "maxhp": 20}], "box": {"count": 0}})
    use = g.send("bag.use", {"item": "POTION", "qty": 1})
    g.run(5)
    assert g.party() == [] and ("session.pending", use) in errors(g)
    assert g.status()["partyWaiting"] is True
    g.E.ow.runner.running = False
    g.run(3)
    assert bounds(g) == [(2, seq)] and active(g) == "slot2" and on_title(g)
    assert g.E.slotWrites[b"slot1"] == 1 and g.status()["pairingPending"] is False
    # the held sync is the new world's: it applies to the paired game
    g.E.startNewGame()
    g.run(3)
    assert [mon.species for mon in g.party()] == [b"STARTMON_C"]


def test_a_new_game_never_saved_moves_to_an_empty_slot_as_it_is():
    g = game()
    g.E.startNewGame()
    g.run(2)
    writes = g.E.saveWrites
    seq = bind(g, A)
    assert bounds(g) == [(2, seq)] and active(g) == "slot2"
    assert in_game(g) and g.E.saveWrites == writes, "no save, no title: the game goes on"
    assert g.status()["pairingSwitchFresh"] == 1
    g.E.game.writeSave(g.E.game)
    g.run(1)
    assert g.E.slotWrites[b"slot2"] == 1 and g.E.slotWrites[b"slot1"] is None


def test_a_new_game_waits_when_the_worlds_slot_has_a_save():
    g = game()
    bind(g, A)
    g.E.slotFiles[b"slot2"] = True  # world A's story was saved before
    bind(g, SOLO, multiplayer=False)
    assert active(g) == "slot1"
    g.E.startNewGame()  # a New Game started in the offline slot, never saved
    g.run(2)
    seq = bind(g, A, steps=4)
    assert bounds(g)[-1][1] != seq and active(g) == "slot1" and in_game(g)
    assert [w["level"] for w in warnings(g, "session.switch_waiting")] == ["warn"]
    assert g.status()["pairingWaitNewGame"] == 1
    # the player quits to the title (START, QUIT): the switch runs there
    g.E.game.returnToTitle(g.E.game)
    g.run(2)
    assert bounds(g)[-1] == (2, seq) and active(g) == "slot2"


def test_a_refused_switch_save_waits_and_is_tried_again():
    g = game(at_title=False)
    g.E.saveVeto = True
    seq = bind(g, A, steps=3)
    assert bounds(g) == [] and g.status()["pairingWaitSaveFailed"] == 1
    assert any("session.switch_save_failed" in line for line in g.b.log_lines())
    g.E.saveVeto = False
    g.run(10)
    assert bounds(g) == [], "not before the retry"
    g.b.now = g.b.now + 5
    g.run(2)
    assert bounds(g) == [(2, seq)] and active(g) == "slot2"
    assert g.status()["pairingSaveFailed"] == 1


def test_a_dropped_connection_cancels_a_waiting_switch():
    g = game(at_title=False)
    g.E.ow.runner.running = True
    bind(g, A)
    assert g.status()["pairingPending"] is True
    g.peer.close()
    g.rig.step(2)
    assert g.b.mod.exports.pairingPending is False
    g.E.ow.runner.running = False
    g.rig.step(3)
    assert active(g) == "slot1", "no switch without the client that asked"
    g.connect()
    seq = bind(g, A)
    assert bounds(g) == [(2, seq)] and active(g) == "slot2"
    assert g.status()["pairingCancelled"] == 1


def test_a_newer_bind_replaces_a_waiting_one():
    g = game(at_title=False)
    g.E.ow.runner.running = True
    bind(g, A)
    seq = bind(g, SOLO, multiplayer=False)
    assert bounds(g) == [(1, seq)] and active(g) == "slot1"
    st = g.status()
    assert (st["pairingSuperseded"], st["pairingPending"]) == (1, False)
    g.E.ow.runner.running = False
    g.run(3)
    assert active(g) == "slot1" and len(bounds(g)) == 1


def test_a_switch_leaves_the_old_worlds_data_behind():
    g = game(at_title=False)
    g.give_starter()
    g.run(2)
    assert g.received("party.give"), "a gift the old world hasn't confirmed"
    g.send("dex.sync", {"seen": [16], "owned": [16]})
    g.run(2)
    assert g.E.save.pokedex.owned[b"BIRDMON"] is True
    bind(g, A)
    assert active(g) == "slot2" and on_title(g)
    st = g.status()
    assert st["pairingDropped"] >= 1 and st["giftsPending"] == 0
    # nothing of the old world reaches the new one: no gift again after a resync, and the old
    # world's Pokedex isn't applied to the paired game's New Game
    gives = len(g.received("party.give"))
    g.send("resync")
    g.run(2)
    assert len(g.received("party.give")) == gives
    g.E.startNewGame()
    g.run(3)
    assert g.E.save.pokedex.owned[b"BIRDMON"] is None and g.party() == []


def test_syncs_sent_before_a_switching_bind_are_forgotten():
    g = game()
    g.send("party.sync", {"party": [{"uid": "00000000-0000-4000-8000-000000000002", "dex": 7, "level": 5,
                                     "moves": [33], "hp": 20, "maxhp": 20}], "box": {"count": 0}})
    g.run(1)  # at the title the sync waits
    bind(g, A)
    g.E.startNewGame()
    g.run(3)
    assert g.party() == [] and g.status()["partyForgotten"] == 1


# ------------------------------------------------------------ refusals


def test_no_game_version_is_refused():
    g = game()
    g.E.GameVersion.current = None
    seq = bind(g, A)
    assert errors(g) == [("session.no_game", seq)] and bounds(g) == []


def test_a_cart_refuses_worlds_but_singleplayer_still_answers():
    g = game()
    g.E.cart = b"some_cart"
    seq = bind(g, A)
    solo = bind(g, SOLO, multiplayer=False)
    assert errors(g) == [("session.no_slots", seq)] and bounds(g) == [(1, solo)]


def test_an_engine_without_the_slot_api_answers_singleplayer_only():
    g = game()
    g.E.SaveData.createSlot = None
    seq = bind(g, A)
    solo = bind(g, SOLO, multiplayer=False)
    assert errors(g) == [("session.no_slots", seq)] and bounds(g) == [(1, solo)]


def test_a_slot_that_cant_be_created_is_refused():
    g = game()
    g.E.createFails = True
    seq = bind(g, A)
    assert errors(g) == [("session.no_slot", seq)] and active(g) == "slot1"
    assert store(g) is None


def test_a_pairing_that_cant_be_written_takes_its_slot_back():
    g = game()
    g.b.mod._cacheControl.failWrites = True
    seq = bind(g, A)
    assert errors(g) == [("session.store_failed", seq)]
    assert registry(g) == ["slot1"] and active(g) == "slot1" and calls(g, "deleteSlot") == 1
    st = g.status()
    assert (st["pairingStoreFailed"], st["pairingRefused"], st["pairingPending"]) == (1, 1, False)


def test_a_slot_number_the_protocol_cant_carry_is_refused():
    g = game()
    g.E.slotGap = 300  # createSlot hands out slot301
    seq = bind(g, A)
    assert errors(g) == [("session.no_slot", seq)] and registry(g) == ["slot1"]


def test_bad_binds_and_roles():
    g = game()
    bad = g.send("session.bind", {"world": "has space", "multiplayer": True})
    long = g.send("session.bind", {"world": "w" * 65, "multiplayer": True})
    g.run(1)
    assert errors(g) == [("bad_message", bad), ("bad_message", long)]
    assert store(g) is None and calls(g, "createSlot") == 0


# ------------------------------------------------------------ the pairing file


def test_the_pairing_file_is_written_through_a_copy_and_kept_with_a_backup():
    g = game()
    bind(g, A)
    files = g.b.cache_files()
    assert set(files) == {FILE, FILE + ".bak"}, "the .tmp copy is gone once the file is in place"
    assert files[FILE] == files[FILE + ".bak"]
    text = files[FILE].decode("utf-8")
    assert text == json.dumps(json.loads(text), sort_keys=True, separators=(",", ":")), "canonical JSON"


def test_a_broken_pairing_file_is_read_from_its_backup():
    g = game()
    bind(g, A)
    files = g.b.cache_files()
    files[FILE] = b"{not json"
    h = CenterGame(mod_cache=files)
    for slot in ("slot1", "slot2"):
        h.E.SaveData.setActiveSlot(b"blue", slot.encode())
    h.E.SaveData.setActiveSlot(b"blue", b"slot1")
    h.E.game.returnToTitle(h.E.game)
    h.run(1)
    bind(h, A)
    assert bounds(h)[-1][0] == 2 and calls(h, "createSlot") == 0
    assert h.status()["pairingStoreRecovered"] == 1
    assert any("session.store_recovered from=bak" in line for line in h.b.log_lines())


def test_an_unreadable_pairing_file_starts_over():
    g = CenterGame(mod_cache={FILE: b"[]"})
    g.E.SaveData.setActiveSlot(b"blue", b"slot1")
    g.E.game.returnToTitle(g.E.game)
    g.run(1)
    bind(g, A)
    assert bounds(g)[-1][0] == 2 and g.status()["pairingStoreUnreadable"] == 1
    assert store(g)["games"]["blue"]["worlds"] == {A: {"slot": "slot2"}}


def test_a_world_slot_deleted_in_the_launcher_comes_back_labelled():
    g = game()
    bind(g, A)
    bind(g, SOLO, multiplayer=False)
    g.E.SaveData.deleteSlot(b"blue", b"slot2")
    assert registry(g) == ["slot1"]
    bind(g, A)
    assert active(g) == "slot2" and registry(g) == ["slot1", "slot2"]
    assert label(g, "slot2").startswith("MULTIPLAYER ") and g.status()["pairingRelabelled"] == 1


def test_a_slot_id_handed_out_again_drops_the_stale_pairing():
    g = game()
    bind(g, A)
    bind(g, SOLO, multiplayer=False)
    g.E.SaveData.deleteSlot(b"blue", b"slot2")  # the launcher deleted A's slot ...
    bind(g, B)                                    # ... and createSlot gives B the same id
    assert active(g) == "slot2" and store(g)["games"]["blue"]["worlds"] == {B: {"slot": "slot2"}}
    assert g.status()["pairingPurged"] == 1
    bind(g, A)
    assert active(g) == "slot3", "A gets a slot of its own again"


def test_each_game_version_pairs_on_its_own_in_its_own_file():
    # MG1: a second process of the install, running Red, keeps session/pairings.red.json and never
    # reads (or rewrites) Blue's file
    g = game()
    bind(g, A)
    files = g.b.cache_files()
    blue_text = files[FILE]
    red = CenterGame(game="red", mod_cache=files)
    red.E.SaveData.setActiveSlot(b"red", b"slot1")
    red.E.game.returnToTitle(red.E.game)
    red.run(1)
    bind(red, A)
    assert bounds(red)[-1][0] == 2 and calls(red, "createSlot") == 1, "Red's own slot for the world"
    assert store(red, "red") == {"format": 1, "games": {"red": {"home": "slot1", "worlds": {A: {"slot": "slot2"}}}}}
    assert store(red, "blue") == json.loads(blue_text.decode("utf-8")) and red.b.cache_files()[FILE] == blue_text
    assert set(red.b.cache_files()) == {FILE, FILE + ".bak", file_for("red"), file_for("red") + ".bak"}
    assert red.status()["pairingStoreLegacy"] == 0


def test_the_legacy_install_wide_file_is_read_once_for_this_game_and_left_alone():
    # before MG1 every game's entries shared session/pairings.json; a process finding no file of its
    # own takes its game's entry from it, and writes its own file on the next change
    legacy = json.dumps({"format": 1, "games": {
        "blue": {"home": "slot1", "worlds": {A: {"slot": "slot2"}}},
        "red": {"home": "slot1", "worlds": {A: {"slot": "slot3"}}},
    }}, sort_keys=True, separators=(",", ":")).encode("utf-8")
    g = CenterGame(mod_cache={LEGACY: legacy})
    for slot in ("slot1", "slot2"):
        g.E.SaveData.setActiveSlot(b"blue", slot.encode())
    g.E.SaveData.setActiveSlot(b"blue", b"slot1")
    g.E.game.returnToTitle(g.E.game)
    g.run(1)
    bind(g, A)
    assert bounds(g)[-1][0] == 2 and calls(g, "createSlot") == 0, "the legacy entry's slot"
    assert g.status()["pairingStoreLegacy"] == 1
    assert any("session.store_legacy worlds=1" in line for line in g.b.log_lines())
    assert g.b.cache_files() == {LEGACY: legacy}, "nothing written for a reused pairing"
    bind(g, B)
    assert bounds(g)[-1][0] == 3 and calls(g, "createSlot") == 1
    files = g.b.cache_files()
    assert files[LEGACY] == legacy, "the legacy file is never rewritten"
    assert set(files) == {LEGACY, FILE, FILE + ".bak"}
    assert store(g) == {"format": 1, "games": {"blue": {"home": "slot1",
                                                          "worlds": {A: {"slot": "slot2"}, B: {"slot": "slot3"}}}}}
    # a restart reads the per-game file, not the legacy one again
    h = CenterGame(mod_cache=files)
    for slot in ("slot1", "slot2", "slot3"):
        h.E.SaveData.setActiveSlot(b"blue", slot.encode())
    h.E.SaveData.setActiveSlot(b"blue", b"slot1")
    h.E.game.returnToTitle(h.E.game)
    h.run(1)
    bind(h, B)
    assert bounds(h)[-1][0] == 3 and h.status()["pairingStoreLegacy"] == 0


def test_driver_exports():
    g = game()
    bind(g, A)
    ex = g.b.mod.exports
    assert ex.pairedSlot(A.encode()) == b"slot2" and ex.homeSlot() == b"slot1"
    assert ex.pairedSlot(B.encode()) is None
    assert ex.forgetWorld(A.encode()) == b"slot2" and ex.forgetWorld(A.encode()) is None
    assert store(g)["games"]["blue"]["worlds"] == {}


# ------------------------------------------------------------ host version exclusives (a guest of the other version)


def test_a_guest_of_the_other_version_needs_nothing_more():
    """The world is built from the host's export (say Blue); a guest's hidden game may be Red. Its
    grass and cave rolls are suppressed all the same (the host's habitat pools spawn), and its
    pairings are its own version's."""
    from battlesim import BattleGame
    g = BattleGame(start=("WILD_ROUTE", 2, 3, "down"), game="red")
    assert g.received("hello.ok")[0]["data"]["game"] == "red"
    g.script([["give_pokemon", "STARTMON_C", 5, True]])
    g.run(2)
    g.E.rolls[b"grass"] = g.b.to_lua({"species": "BIRDMON", "level": 3})
    n = 0
    for d in ["right", "up", "right", "left", "right", "left"]:
        n += 1
        g.send("move.step", {"dir": d, "n": n})
        g.run(22)
    assert not g.received("battle.start") and (g.E.rollCalls[b"grass"] or 0) == 0
    assert g.status()["encounterGrass"] == 5


# ------------------------------------------------------------ M10: session.bound.switched, session.waiting


def switched(g):
    return [m["data"].get("switched") for m in g.received("session.bound")]


def waitings(g):
    return [(m["data"], m.get("re")) for m in g.received("session.waiting")]


def test_session_bound_says_how_the_slot_was_reached():
    g = game()
    bind(g, A)  # at the title: moved there
    bind(g, A)  # already on it
    assert switched(g) == ["title", "none"]
    g.E.startNewGame()
    g.run(2)
    bind(g, B)  # a New Game never saved goes on into B's empty slot as it is
    assert switched(g)[-1] == "moved" and in_game(g)
    g.E.game.writeSave(g.E.game)
    g.run(2)
    bind(g, SOLO, multiplayer=False)  # a saved game: saved into its own slot first, then the title
    assert switched(g)[-1] == "saved" and on_title(g)
    bind(g, SOLO, multiplayer=False)
    assert switched(g)[-1] == "none"
    assert not waitings(g), "nothing waited"


def test_session_waiting_says_why_once_per_reason_with_the_binds_seq():
    g = game(at_title=False)
    g.E.ow.runner.running = True  # a script runs: the F1 gate is closed
    seq = bind(g, A, steps=5)
    assert waitings(g) == [({"slot": 2, "reason": "busy"}, seq)]
    g.run(10)
    assert len(waitings(g)) == 1, "not again while the reason stays"
    # the gate opens, but the save is refused: the reason changes
    g.E.saveVeto = True
    g.E.ow.runner.running = False
    g.run(3)
    assert waitings(g) == [({"slot": 2, "reason": "busy"}, seq), ({"slot": 2, "reason": "save_failed"}, seq)]
    g.E.saveVeto = False
    g.b.now = g.b.now + 5
    g.run(3)
    assert bounds(g) == [(2, seq)] and switched(g) == ["saved"]
    # the log line stays for diagnostics
    assert [w["level"] for w in warnings(g, "session.switch_waiting")] == ["warn", "warn"]
    assert g.status()["pairingWaitingSent"] == 2


def test_a_new_game_waiting_for_the_title_says_new_game():
    g = game()
    bind(g, A)
    g.E.slotFiles[b"slot2"] = True  # world A's story was saved before
    bind(g, SOLO, multiplayer=False)
    g.E.startNewGame()
    g.run(2)
    seq = bind(g, A, steps=4)
    assert waitings(g) == [({"slot": 2, "reason": "new_game"}, seq)]
    g.E.game.returnToTitle(g.E.game)
    g.run(2)
    assert g.received("session.bound")[-1]["data"] == {"slot": 2, "switched": "title"}
    assert g.received("session.bound")[-1]["re"] == seq


def test_a_newer_bind_answers_with_its_own_seq():
    g = game(at_title=False)
    g.E.ow.runner.running = True
    first = bind(g, A)
    second = bind(g, SOLO, multiplayer=False)
    assert waitings(g) == [({"slot": 2, "reason": "busy"}, first)]
    assert [(m["data"], m.get("re")) for m in g.received("session.bound")] == \
        [({"slot": 1, "switched": "none"}, second)]
