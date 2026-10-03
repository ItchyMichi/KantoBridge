"""act/kb_pc.lua and kb_engine's ui.pc.items seam (M7), wired by main.lua, on the simulated engine
(fake_center.lua's PC menu): the box row ("SOMEONE'S PC" / "BILL'S PC") sends pc.open after its own
access text and holds the hidden game (mode busy, no UI, no Gen 1 box screen) until pc.closed, after
which the PC menu takes input again; the player's item PC and LOG OFF stay native; no client means the
native box screen; a dropped link releases the hold; a hot reload keeps it answerable; a hold whose
world went away is dropped. Every message the peer receives is schema-checked (rig.Peer, strict). All
data is made up (centersim.py)."""

import pytest

from centersim import PC, CenterGame

pytestmark = pytest.mark.lua


def at_the_pc(**kw):
    g = CenterGame(start=("TEST_CENTER", 7, 2, "up"), **kw)
    return g


def open_pc(g):
    """act.interact on the PC, the 'turned on' text read: returns the PC menu's (id, data)."""
    g.send("act.interact", PC)
    g.run(2)
    assert g.received("act.result")[-1]["data"] == {"ok": True, "kind": "hidden"}
    g.read_top_text()
    return g.wait_ui("menu")


def choose_box_row(g):
    menu_id, menu = open_pc(g)
    g.send("ui.pick", {"ui": menu_id, "index": 0})
    g.run(2)
    g.read_top_text()   # the "accessed" text, two pages
    g.run(3)
    return menu_id, menu


def subs(g):
    return [s.decode() for s in g.E.subScreens.values()]


def test_the_box_row_opens_cobblemons_pc_and_holds_until_pc_closed():
    g = at_the_pc()
    menu_id, menu = choose_box_row(g)
    assert [it["label"] for it in menu["items"]] == ["SOMEONE'S PC", "TESTER's PC", "LOG OFF"]
    assert len(g.received("pc.open")) == 1 and g.received("pc.open")[0]["data"] == {}
    assert subs(g) == [], "no Gen 1 box screen"
    g.run(30)
    assert g.mode() == "busy" and len(g.received("pc.open")) == 1, "held, sent once"
    assert menu_id in g.open_uis(), "the PC menu stays open under the hold"
    st = g.status()
    assert (st["pcOpened"], st["pcHeld"], st["pcTaken"], st["pcNative"], st["pcHeld"]) == (1, True, 1, 0, True)
    g.send("pc.closed")
    g.run(3)
    assert g.mode() == "screen" and g.status()["pcHeld"] is False
    top_id, _ = g.top_ui()
    assert top_id == menu_id, "the PC menu is back on top"
    # LOG OFF, natively
    g.send("ui.pick", {"ui": menu_id, "index": 2})
    g.run(3)
    assert not g.open_uis() and g.mode() == "idle"
    st = g.status()
    assert (st["pcClosed"], st["pcUnexpected"], st["pcReleased"]) == (1, 0, 0)
    assert not g.received("error")


def test_bills_pc_after_meeting_him():
    g = at_the_pc()
    g.E.save.flags.EVENT_MET_BILL = True
    _, menu = choose_box_row(g)
    assert menu["items"][0]["label"] == "BILL'S PC"
    assert len(g.received("pc.open")) == 1


def test_the_players_item_pc_stays_native():
    g = at_the_pc()
    menu_id, _ = open_pc(g)
    g.send("ui.pick", {"ui": menu_id, "index": 1})
    g.run(2)
    g.read_top_text()
    sub_id, sub = g.wait_ui("menu", screen="PlayerPC")
    assert subs(g) == ["PlayerPC"] and not g.received("pc.open")
    g.send("ui.back", {"ui": sub_id})
    g.run(3)
    again_id, _ = g.wait_ui("menu")
    assert again_id != sub_id, "the PC menu again, natively"
    assert g.status()["pcNative"] == 0, "only the box row is wrapped"


def test_without_a_client_the_box_row_is_native():
    g = at_the_pc(connect=False)
    g.E.openPC()
    pc_menu = None
    for _ in range(200):
        g.rig.step()
        top = g.E.stack.top(g.E.stack)
        if top is not None and top.isTextBox:
            g.E.game.input.sourcePress(g.E.game.input, b"a", b"test")
            g.E.game.input.sourceRelease(g.E.game.input, b"a", b"test")
        elif top is not None and top.items is not None and pc_menu is None:
            pc_menu = top
            pc_menu.index = 1
            g.E.game.input.sourcePress(g.E.game.input, b"a", b"test")
            g.E.game.input.sourceRelease(g.E.game.input, b"a", b"test")
        if subs(g):
            break
    assert subs(g) == ["BoxMenu"], "the native box screen"


def test_a_dropped_link_releases_the_hold():
    g = at_the_pc()
    menu_id, _ = choose_box_row(g)
    assert g.received("pc.open")
    g.peer.close()
    g.run(3)
    st_held = g.b.mod.exports.pcHeld
    assert st_held is False, "released"
    g.connect()
    g.run(3)
    assert g.mode() == "screen" and not g.received("pc.open")[1:], "not sent again"
    st = g.status()
    assert st["pcReleased"] == 1 and st["pcHeld"] is False


def test_pc_closed_without_a_hold_is_counted_and_ignored():
    g = at_the_pc()
    g.send("pc.closed")
    g.run(1)
    st = g.status()
    assert st["pcUnexpected"] == 1 and not g.received("error")


def test_a_hot_reload_ends_the_older_instances_hold():
    g = at_the_pc()
    choose_box_row(g)
    assert g.E.stack.top(g.E.stack).kantoBridgePcHold is True
    g.b.reload()      # the reload drops the link, so the new instance releases the old one's hold
    g.run(2)
    assert g.E.stack.top(g.E.stack).kantoBridgePcHold is None, "popped"
    g.connect()
    g.run(2)
    assert g.mode() == "screen" and len(g.received("pc.open")) == 1
    st = g.status()
    assert st["pcReleased"] == 1 and st["pcHeld"] is False


def test_a_hot_reload_with_the_menu_open_asks_the_new_instance():
    g = at_the_pc()
    menu_id, _ = open_pc(g)
    g.b.reload()
    g.connect()
    menu_id, _ = g.wait_ui("menu")
    g.send("ui.pick", {"ui": menu_id, "index": 0})
    g.run(2)
    g.read_top_text()
    g.run(3)
    assert len(g.received("pc.open")) == 1, "the old wrapper answers to the new instance"
    g.send("pc.closed")
    g.run(3)
    assert g.status()["pcClosed"] == 1


def test_leaving_for_the_title_drops_the_hold():
    g = at_the_pc()
    choose_box_row(g)
    g.E.leaveWorld()
    g.run(2)
    st = g.status()
    assert st["pcDropped"] == 1 and st["pcHeld"] is False


def test_a_builder_cannot_close_a_pc():
    g = at_the_pc(role="builder")
    seq = g.send("pc.closed")
    g.run(1)
    assert [(m["data"]["code"], m.get("re")) for m in g.received("error")] == [("role", seq)]
