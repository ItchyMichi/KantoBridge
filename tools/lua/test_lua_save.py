"""act/kb_save.lua and kb_engine's M7 seams, wired by main.lua, on the simulated engine (fake_center.lua):
the nurse's heal (party.heal at the machine, then the hidden game's own save once her lines are done
and the player stands, announced as save.begin reason center), the start menu's SAVE (save.begin reason
menu), save.done (log only: ok, failed, unknown, repeated), saves made while no client is connected,
the save.write veto and failed writes, session.bind -> session.bound with the active slot and the world
kept in mod.save, and a blackout's party.heal following its warp. Every message the peer receives is
schema-checked (rig.Peer, strict). All data is made up (centersim.py)."""

import re

import pytest

from centersim import NURSE, CenterGame

pytestmark = pytest.mark.lua

SAVE_ID = re.compile(r"^v-[0-9a-f]{8}-\d+$")


def types(g, *wanted):
    return [m["type"] for m in g.log if m["type"] in wanted]


def on_overworld(g):
    top = g.E.stack.top(g.E.stack)
    return top is not None and top.isOverworld is True


def heal_at_the_nurse(g, choice=0):
    """Talks to the nurse and plays her lines like a player (HEAL = 0, CANCEL = 1) until every UI is
    closed and the hidden player stands."""
    g.send("act.interact", NURSE)
    g.play(choice=choice)
    g.run(3)


# ------------------------------------------------------------ the Pokemon Center


def test_the_nurse_heals_cobblemon_then_both_games_save():
    g = CenterGame()
    g.give_starter()
    g.hurt()
    heal_at_the_nurse(g)
    assert g.received("act.result")[-1]["data"] == {"ok": True, "kind": "npc"}
    assert [mon.hp == mon.stats.hp for mon in g.party()] == [True], "the hidden game healed"
    order = types(g, "party.heal", "save.begin")
    assert order == ["party.heal", "save.begin"], "party.heal at the machine, then the save"
    begin = g.last("save.begin")
    assert begin["reason"] == "center" and SAVE_ID.match(begin["save"])
    assert g.E.saveWrites == 1, "the hidden game saved itself once"
    # the save went out only after the nurse's last line, with nothing open
    at = next(i for i, m in enumerate(g.log) if m["type"] == "save.begin")
    closes = [i for i, m in enumerate(g.log) if m["type"] == "ui.close"]
    opens = [i for i, m in enumerate(g.log) if m["type"] == "ui.open"]
    assert max(closes) < at and max(opens) < at
    assert g.E.save.lastHeal.map == b"TEST_CENTER", "the engine's own lastHeal, untouched"
    assert g.E.save.player.map == b"TEST_CENTER" and g.E.save.player.x == 3, "captureSave ran"
    st = g.status()
    assert (st["saveHeals"], st["saveCenterArmed"], st["saveCenterWritten"], st["saveCenter"], st["saveBegun"],
            st["saveWrites"], st["saveCenterPending"]) == (1, 1, 1, 1, 1, 1, False)
    assert st["partyHeals"] == 1 and st["mode"] == "idle"
    # Minecraft saved: logged, counted
    g.send("save.done", {"save": begin["save"], "ok": True})
    g.run(1)
    st = g.status()
    assert (st["saveDone"], st["saveOk"], st["saveFailed"]) == (1, 1, 0)
    assert not g.received("error")


def test_cancel_at_the_nurse_heals_and_saves_nothing():
    g = CenterGame()
    g.give_starter()
    heal_at_the_nurse(g, choice=1)
    assert not g.received("party.heal") and not g.received("save.begin")
    assert g.E.saveWrites == 0 and g.E.save.lastHeal is None


def test_the_center_save_waits_for_the_quick_save_gate():
    g = CenterGame()
    g.give_starter()
    g.send("act.interact", NURSE)
    g.play(choice=0, until=lambda g: g.received("party.heal"))
    # a script runs on (the gate is closed while one does) until her lines are done
    g.E.ow.runner.running = True
    g.play(until=lambda g: g.E.ow.healAnim is None and not g.open_uis() and on_overworld(g)
           and g.E.ow.emote is None)
    g.run(20)
    assert g.E.saveWrites == 0 and not g.received("save.begin")
    assert g.status()["saveCenterPending"] is True
    # then a menu on top
    g.E.ow.runner.running = False
    g.push(g.E.screenState(b"TrainerCard", g.opts(sticky=True)))
    g.run(20)
    assert g.E.saveWrites == 0
    g.E.uiPop()
    g.run(2)
    assert g.E.saveWrites == 1 and g.last("save.begin")["reason"] == "center"


def test_the_center_save_waits_for_a_step_to_land():
    g = CenterGame()
    g.give_starter()
    g.send("act.interact", NURSE)
    g.play(choice=0, until=lambda g: g.received("party.heal"))
    g.E.ow.runner.running = True
    g.play(until=lambda g: g.E.ow.healAnim is None and not g.open_uis() and on_overworld(g)
           and g.E.ow.emote is None)
    # the player is mid-step when the gate would otherwise open
    g.E.ow.runner.running = False
    g.E.startStep(b"down")
    g.run(3)
    assert g.E.saveWrites == 0, "not while walking"
    g.run(20)
    assert g.E.saveWrites == 1
    assert g.E.save.player.y == 4, "captured where the player stands after landing"


def test_no_client_at_the_heal_means_no_center_save_and_a_later_heal():
    g = CenterGame(connect=False)
    g.give_starter()
    g.E.ow.player.facing = b"up"
    g.E.nurseHeal(g.npc("TEST_CENTER_obj_1"), None)
    # play the nurse natively: A at every box, HEAL
    for _ in range(400):
        g.rig.step()
        top = g.E.stack.top(g.E.stack)
        if top is not None and not top.isOverworld:
            g.E.game.input.sourcePress(g.E.game.input, b"a", b"test")
            g.E.game.input.sourceRelease(g.E.game.input, b"a", b"test")
    assert g.E.save.lastHeal is not None and g.E.saveWrites == 0, "native play: no automatic save"
    g.connect()
    g.run(2)
    assert len(g.received("party.heal")) == 1, "the heal waited for the client"
    assert not g.received("save.begin")
    st = g.status()
    assert st["saveCenterOffline"] == 1 and st["saveCenterArmed"] == 0


@pytest.mark.parametrize("how", ["veto", "fails"])
def test_a_vetoed_or_failed_center_save_is_a_counted_warning(how):
    g = CenterGame()
    g.give_starter()
    if how == "veto":
        g.E.saveVeto = True
    else:
        g.E.saveFails = True
    heal_at_the_nurse(g)
    st = g.status()
    assert st["saveCenterRefused"] == 1 and st["saveCenterWritten"] == 0
    assert any("save.center_refused" in line for line in g.b.log_lines())
    if how == "veto":
        assert not g.received("save.begin"), "no save.writing, nothing to announce"
    else:
        assert g.last("save.begin")["reason"] == "center", "save.writing fired before the write failed"


def test_leaving_for_the_title_drops_the_armed_center_save():
    g = CenterGame()
    g.give_starter()
    g.send("act.interact", NURSE)
    g.play(choice=0, until=lambda g: g.received("party.heal"))
    assert g.status()["saveCenterPending"] is True
    g.E.leaveWorld()
    g.run(3)
    st = g.status()
    assert st["saveCenterDropped"] == 1 and st["saveCenterPending"] is False and g.E.saveWrites == 0


def test_a_hot_reload_keeps_the_armed_center_save():
    g = CenterGame()
    g.give_starter()
    g.send("act.interact", NURSE)
    g.play(choice=0, until=lambda g: g.received("party.heal"))
    g.b.reload()
    g.connect()
    g.play(choice=0)
    g.run(3)
    assert g.E.saveWrites == 1 and g.last("save.begin")["reason"] == "center"


# ------------------------------------------------------------ the start menu's SAVE, save.done


def save_from_the_start_menu(g, choice=0):
    g.E.startMenuSave = True
    g.send("act.button", {"button": "start", "how": "tap"})
    menu_id, menu = g.wait_ui("menu", screen="StartMenu")
    row = [it["label"] for it in menu["items"]].index("SAVE")
    g.send("ui.pick", {"ui": menu_id, "index": row})
    g.play(choice=choice)
    g.run(3)


def test_the_start_menus_save_is_announced_as_menu():
    g = CenterGame()
    save_from_the_start_menu(g)
    assert g.E.saveWrites == 1
    begin = g.last("save.begin")
    assert begin["reason"] == "menu" and SAVE_ID.match(begin["save"])
    assert not g.open_uis() and g.mode() == "idle"
    st = g.status()
    assert (st["saveMenu"], st["saveCenter"], st["saveBegun"]) == (1, 0, 1)


def test_save_done_failed_unknown_repeated_and_bad():
    g = CenterGame()
    save_from_the_start_menu(g)
    begin = g.last("save.begin")
    g.send("save.done", {"save": begin["save"], "ok": False})
    g.send("save.done", {"save": begin["save"], "ok": True})       # repeated
    g.send("save.done", {"save": "v-00000000-999", "ok": True})     # never sent
    bad = g.send("save.done", {"save": begin["save"]})
    g.run(2)
    st = g.status()
    assert (st["saveDone"], st["saveOk"], st["saveFailed"], st["saveRepeated"], st["saveUnknown"]) == (1, 0, 1, 1, 1)
    assert [(m["data"]["code"], m.get("re")) for m in g.received("error")] == [("bad_message", bad)]
    logs = [m["data"] for m in g.received("log")]
    assert any(d["code"] == "save.minecraft_failed" and d["level"] == "warn" for d in logs)
    assert any(d["code"] == "save.unknown_done" for d in logs)


def test_declining_the_save_saves_nothing():
    g = CenterGame()
    save_from_the_start_menu(g, choice=1)
    assert g.E.saveWrites == 0 and not g.received("save.begin")


def test_save_ids_are_unique_and_share_the_bridge_token():
    g = CenterGame()
    for _ in range(3):
        save_from_the_start_menu(g)
    ids = [m["data"]["save"] for m in g.received("save.begin")]
    assert len(ids) == 3 and len(set(ids)) == 3
    assert len({i.split("-")[1] for i in ids}) == 1


def test_saves_made_without_a_client_wait_newest_only():
    g = CenterGame(connect=False)
    for _ in range(3):
        g.E.game.writeSave(g.E.game)
    g.run(2)
    g.connect()
    g.run(2)
    begins = g.received("save.begin")
    assert len(begins) == 1 and begins[0]["data"]["reason"] == "menu"
    st = g.status()
    assert st["saveWrites"] == 3 and st["saveCollapsed"] == 2 and st["saveBegun"] == 1
    g.run(5)
    assert len(g.received("save.begin")) == 1, "sent once"


def test_a_builder_neither_gets_saves_nor_answers_them():
    g = CenterGame(role="builder")
    g.E.game.writeSave(g.E.game)
    g.run(2)
    assert not g.received("save.begin")
    seq = g.send("save.done", {"save": "v-00000000-1", "ok": True})
    g.run(1)
    assert [(m["data"]["code"], m.get("re")) for m in g.received("error")] == [("role", seq)]


# ------------------------------------------------------------ session.bind


@pytest.mark.parametrize("slot, expected", [(b"slot3", 3), (None, 0), (b"slot999", 0), (b"odd", 0)])
def test_session_bind_answers_the_active_slot_and_keeps_the_world(slot, expected):
    g = CenterGame()
    g.E.activeSlot = slot
    seq = g.send("session.bind", {"world": "kanto-test-world", "multiplayer": False})
    g.run(1)
    bound = g.received("session.bound")
    assert [(m["data"], m.get("re")) for m in bound] == [({"slot": expected}, seq)]
    rec = g.b.mod._save.session
    assert rec.world == b"kanto-test-world" and rec.multiplayer is False and rec.slot == expected
    st = g.status()
    assert (st["saveBinds"], st["saveBound"], st["saveSlot"], st["saveStored"], st["saveMultiplayer"]) == \
        (1, True, expected, True, False)
    assert not any("kanto-test-world" in line for line in g.b.log_lines()), "the world id is never logged"


def test_session_bind_errors_and_roles():
    g = CenterGame()
    bad = g.send("session.bind", {"world": "has space", "multiplayer": False})
    worse = g.send("session.bind", {"world": "w", "multiplayer": "no"})
    g.run(1)
    assert [(m["data"]["code"], m.get("re")) for m in g.received("error")] == [("bad_message", bad),
                                                                              ("bad_message", worse)]
    assert not g.received("session.bound")
    b = CenterGame(role="builder")
    seq = b.send("session.bind", {"world": "w", "multiplayer": True})
    b.run(1)
    assert [(m["data"]["code"], m.get("re")) for m in b.received("error")] == [("role", seq)]


def test_a_bind_at_the_title_is_kept_by_the_next_save():
    g = CenterGame()
    g.E.leaveWorld()
    g.run(1)
    g.send("session.bind", {"world": "kanto-title", "multiplayer": True})
    g.run(1)
    assert g.received("session.bound")
    assert g.b.mod._save.session is None, "the title's bucket is not the next save's"
    g.b.mod._save.session = None
    g.b.emit("save.created", g.b.to_lua({"save": {}}))
    assert g.b.mod._save.session.world == b"kanto-title" and g.b.mod._save.session.multiplayer is True


# ------------------------------------------------------------ blackout ordering


def test_a_blackout_heal_follows_its_warp_arrival():
    g = CenterGame(start=("SIGHT_ROUTE", 3, 4, "up"))
    g.give_starter()
    g.E.healPoint = g.b.to_lua({"map": "TEST_CENTER", "x": 3, "y": 3})
    g.E.ow.afterBattle(g.E.ow, b"lose", g.b.to_lua({"kind": "trainer", "oppClass": "OPP_TEST"}))
    g.run(2)
    assert not g.received("party.heal"), "held until the warp arrives"
    assert g.status()["healHeld"] is True
    g.run(40)
    seq = [(m["type"], m["data"].get("phase")) for m in g.log if m["type"] in ("warp", "party.heal")]
    assert seq == [("warp", "start"), ("warp", "arrive"), ("party.heal", None)]
    arrive = [m["data"] for m in g.received("warp")][-1]
    assert arrive["kind"] == "blackout" and arrive["to"]["map"] == "TEST_CENTER"
    assert g.status()["healHeld"] is False
