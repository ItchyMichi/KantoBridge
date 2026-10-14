"""MG7 G3B (docs/specs/GEN3_BRIDGE_PLAN.md §4.5): the Gen 3 adapter's UI part
(gen1-bridge/lib/engine/game3/ui.lua) on the fake game3 engine. First the adapter alone (Adapter3): the
core's UI stack built from the message box, the choice, the stack's layers, a battle and the boot phase
(the title, Birch's speech), the facts and cursors, the setters. Then main.lua on the fake with a peer
(Rig(family="gen3")): a script's text box answered with ui.advance, a yes/no answered NO by its cursor, the
start menu without EXIT, the naming screen, and Birch's whole speech from region.start to the field.
Every message is schema-checked. Everything is made up."""

import pytest

from adapter3 import Adapter3, plain
from rig import Rig

pytestmark = pytest.mark.lua


def stack(a):
    return plain(a.eng.uiStack())


def classes(a):
    return [(e["cls"], e.get("screen")) for e in stack(a)]


def top_key(a):
    return stack(a)[-1]["key"]


def birch_step(a, br, button=None):
    """One frame of the fake's Birch speech with ``button`` pressed (or none)."""
    step = a.b.eval("""function(E, br, btn)
      E.birchStep(br, { wasPressed = function(_, k) return k == btn end })
    end""")
    step(a.E, br, button.encode() if button else None)


# ------------------------------------------------------------ the stack


def test_the_field_stack_is_the_overworld_then_the_message_a_choice_and_the_layers():
    a = Adapter3()
    a.field()
    E = a.E
    assert classes(a) == [("Overworld", None)]
    E.openMessage(b"HELLO\nTHERE\fPAGE TWO", True)
    E.openChoice(b"yesno")
    E.pushLayer(b"bag")
    first = stack(a)
    assert [(e["cls"], e.get("screen")) for e in first] == [
        ("Overworld", None), ("TextBox", None), ("ChoiceBox", None), ("Screen", "BagMenu")]
    keys = [e["key"] for e in first]
    assert len(set(keys)) == 4 and keys == [e["key"] for e in stack(a)]  # stable while open
    # a new message is a new UI; an unknown layer keeps its id as the screen
    E.closeChoice()
    E.popLayer()
    E.openMessage(b"AGAIN", True)
    E.pushLayer(b"rse_something_new")
    again = stack(a)
    assert again[1]["key"] not in keys
    assert again[2]["screen"] == "RSE_SOMETHING_NEW"
    assert a.eng.screenSize() == (240, 160)


def test_a_battle_is_one_mirror_until_battles_are_headless():
    a = Adapter3()
    a.field()
    a.E.openMessage(b"A WILD TEST!", True)
    a.E.Battle._active = True
    assert classes(a) == [("Overworld", None), ("BattleState", "BattleState")]
    key = top_key(a)
    assert top_key(a) == key  # one key per battle
    a.E.Battle._active = False
    a.E.Battle._active = True
    stack(a)  # the battle seen gone, then a new one
    assert classes(a)[-1] == ("BattleState", "BattleState")


# ------------------------------------------------------------ the message box


def test_message_text_is_plain_with_its_pages_and_where_the_box_is():
    a = Adapter3()
    a.field()
    E = a.E
    # a colour code (0xFC 0x01 <colour>), the PK MN ligatures, a colour tag, a string variable
    E.Space.vm.ctx = a.b.to_lua({"stringVars": [b"ZIG"]})
    E.Message.show(b"\xfc\x01\x02HI {PK}{MN}!{COLOR RED}\nIT IS {STR_VAR_1}\fLAST\xfc")
    key = top_key(a)
    facts = plain(a.eng.uiOpenFacts(key))
    assert facts["pages"] == [["HI <PK><MN>!", "IT IS ZIG"], ["LAST"]]
    assert facts["cont"] == [[False, False], [False]]
    assert (facts["auto"], facts["stay"], facts["instant"], facts["choice"]) == (False, False, False, False)
    # printing the first line
    E.Message._revealed = 3
    assert plain(a.eng.uiCursor(key)) == {"page": 1, "line": 1, "done": False, "waiting": False, "ready": False}
    E.Message._revealed = 14
    assert plain(a.eng.uiCursor(key))["line"] == 2
    E.Message._waiting = True
    assert plain(a.eng.uiCursor(key)) == {"page": 1, "line": 2, "done": False, "waiting": True, "ready": True}
    E.Message.advance()  # page two, printing
    E.Message._waiting = True
    assert plain(a.eng.uiCursor(key)) == {"page": 2, "line": 1, "done": True, "waiting": True, "ready": True}


def test_a_stay_boxs_last_page_waits_for_the_scripts_waitbuttonpress():
    a = Adapter3()
    a.field()
    E = a.E
    E.Message.show(b"ONE\fTWO", a.b.to_lua({"stay": True}))
    key = top_key(a)
    E.Message._waiting = True
    assert plain(a.eng.uiCursor(key))["ready"] is True  # more pages: A turns the page
    E.Message.advance()
    E.Message._waiting = True
    cursor = plain(a.eng.uiCursor(key))
    assert (cursor["done"], cursor["ready"], cursor["waiting"]) == (True, False, False)
    E.Hud._waitButton = a.b.eval("function() end")
    assert plain(a.eng.uiCursor(key))["ready"] is True
    # an auto-scrolled page turns itself; a held box already answered takes nothing
    E.Message.show(b"ONE\fTWO")
    E.Message._waiting, E.Message._autoScroll = True, True
    assert plain(a.eng.uiCursor(top_key(a)))["ready"] is False


# ------------------------------------------------------------ choices and lists


def test_a_yes_no_over_a_message_reads_its_question_and_is_answered_by_the_cursor():
    a = Adapter3()
    a.field()
    E = a.E
    E.openMessage(b"DO IT?\nREALLY?", True)
    E.openChoice(b"yesno")
    key = top_key(a)
    facts = plain(a.eng.uiOpenFacts(key))
    assert (facts["labels"], facts["question"]) == (["YES", "NO"], ["DO IT?", "REALLY?"])
    assert plain(a.eng.uiCursor(key)) == {"index": 1, "busy": False, "noByCursor": True}
    assert a.eng.uiSetIndex(key, 2) is True and E.Choice.cursor == 2
    assert a.eng.uiSetIndex(key, 3) is False and a.eng.uiSetIndex(key, 1.5) is False


def test_a_multichoice_is_a_wrapping_list_with_its_string_variables_filled_in():
    a = Adapter3()
    a.field()
    E = a.E
    E.Space.vm.ctx = a.b.to_lua({"stringVars": [b"ZIG"]})
    E.Choice.multi(a.b.to_lua([b"{STR_VAR_1} DOLL", b"\xfc\x01\x02LEAVE"]), 1, a.b.eval("function() end"))
    assert classes(a)[-1] == ("ListMenu", "ListMenu")
    key = top_key(a)
    facts = plain(a.eng.uiOpenFacts(key))
    assert (facts["items"], facts["rows"], facts["wrap"]) == ([{"label": "ZIG DOLL"}, {"label": "LEAVE"}], 2, True)
    assert plain(a.eng.uiCursor(key)) == {"index": 2, "scroll": 0, "count": 2}
    assert plain(a.eng.uiItems(key)) == [{"label": "ZIG DOLL"}, {"label": "LEAVE"}]
    assert plain(a.eng.uiMoneyFooter(key)) == {}
    assert a.eng.uiSetIndex(key, 1) is True and E.Choice.cursor == 1
    assert a.eng.uiSetIndex(key, 3) is False


def test_the_scrollable_list_menu_keeps_its_window_round_the_cursor():
    a = Adapter3()
    a.field()
    E = a.E
    labels = a.b.to_lua([b"ONE", {"text": b"TWO", "tail": b"x2"}, b"THREE", b"FOUR"])
    E.ListMenu.showItems(labels, 2, a.b.eval("function() end"))
    assert classes(a)[-1] == ("ListMenu", "ListMenu")
    key = top_key(a)
    facts = plain(a.eng.uiOpenFacts(key))
    assert facts["items"] == [{"label": "ONE"}, {"label": "TWO", "right": "x2"}, {"label": "THREE"},
                              {"label": "FOUR"}]
    assert (facts["rows"], facts["wrap"]) == (2, False)
    assert a.eng.uiSetIndex(key, 4) is True and (E.ListMenu.scroll, E.ListMenu.row) == (2, 2)
    assert plain(a.eng.uiCursor(key)) == {"index": 4, "scroll": 2, "count": 4}
    assert a.eng.uiSetIndex(key, 1) is True and (E.ListMenu.scroll, E.ListMenu.row) == (0, 1)
    assert a.eng.uiSetIndex(key, 5) is False


def test_the_start_menu_is_a_menu_without_exit_and_mods():
    a = Adapter3()
    a.field()
    E = a.E
    E.StartMenu.show()
    assert classes(a)[-1] == ("Menu", "StartMenu")
    key = top_key(a)
    facts = plain(a.eng.uiOpenFacts(key))
    assert [i["label"] for i in facts["items"]] == ["TEST DEX", "TEST MON", "TEST BAG", "TESTER", "TEST SAVE",
                                                    "TEST OPTS"]
    assert (facts["rows"], facts["wrap"]) == (6, True)
    assert plain(a.eng.uiCursor(key)) == {"index": 1, "scroll": 0, "count": 6, "busy": False}
    assert a.eng.uiSetIndex(key, 3) is True and E.StartMenu.cursor == 3
    assert a.eng.uiSetIndex(key, 7) is False
    E.StartMenu.cursor = 8  # EXIT, reached by the engine's own keys
    assert plain(a.eng.uiCursor(key))["index"] == 6
    E.StartMenu._confirmExit = True  # its "return to the title?" question is up: no answers
    assert plain(a.eng.uiCursor(key))["busy"] is True


def test_the_naming_screen_reads_its_keyboard_and_finishes_through_ok():
    a = Adapter3()
    a.field()
    E = a.E
    E.Naming.open(a.b.to_lua({"maxLen": 5}))
    assert classes(a)[-1] == ("NamingScreen", "NamingScreen")
    key = top_key(a)
    facts = plain(a.eng.uiOpenFacts(key))
    assert (facts["title"], facts["max"], facts["value"], facts["presets"]) == ("TEST NAME?", 5, "", {})
    assert facts["grid"][0] == [["A", "B", "C"], ["D", "E", "F"], ["G", "H", "I"], ["J", "K"]]
    assert facts["grid"][1][0] == ["a", "b", "c"]
    assert plain(a.eng.uiCursor(key)) == {"choosing": False, "typed": 0, "busy": False}
    assert a.eng.uiSetGlyphs(key, a.b.to_lua([b"A", b"B", b"C", b"D", b"E", b"F"])) is False  # past maxLen
    assert a.eng.uiSetGlyphs(key, a.b.to_lua([b"K", b"A", b"I"])) == (True, b"a")
    st = E.Naming._state
    assert (st.name, st.row, st.btn, st.col) == (b"KAI", 4, 3, 4)  # the cursor on OK
    st.swapT = 0
    assert plain(a.eng.uiCursor(key))["busy"] is True  # a page swap ignores input


# ------------------------------------------------------------ the boot phase


def test_the_title_and_main_menu_are_the_mirror():
    a = Adapter3()
    assert classes(a) == [("Screen", "TitleState")]
    a.E.game.boot.phase = b"menu"
    assert classes(a) == [("Screen", "TitleState")]


def test_birchs_speech_is_its_slide_show_with_text_choices_and_naming_on_top():
    a = Adapter3()
    E = a.E
    E.BootModules.startNewGame(E.BootUi, E.game.boot)
    br = E.game.boot.custom.newGame
    assert classes(a) == [("Screen", "BirchSpeech"), ("TextBox", None)]
    key = top_key(a)
    facts = plain(a.eng.uiOpenFacts(key))
    assert facts["pages"] == [["WELCOME TO THE", "TEST WORLD!"], ["THIS IS A", "TEST SPEECH."]]
    birch_step(a, br)  # prints to its first page break
    assert plain(a.eng.uiCursor(key)) == {"page": 1, "line": 2, "done": False, "waiting": True, "ready": True}
    birch_step(a, br, "a")  # the page cleared, the second printed up to its wait
    assert plain(a.eng.uiCursor(key)) == {"page": 2, "line": 2, "done": False, "waiting": True, "ready": True}
    birch_step(a, br, "a")
    assert plain(a.eng.uiCursor(key))["done"] is True
    birch_step(a, br)  # the next text
    birch_step(a, br)  # printed; the gender menu
    entries = classes(a)
    assert entries == [("Screen", "BirchSpeech"), ("TextBox", None), ("ChoiceBox", None)]
    gkey = top_key(a)
    facts = plain(a.eng.uiOpenFacts(gkey))
    assert (facts["labels"], facts["question"]) == (["BOY", "GIRL"], ["ARE YOU A BOY?", "OR A GIRL?"])
    assert plain(a.eng.uiCursor(gkey)) == {"index": 1, "busy": False, "noByCursor": True}
    assert a.eng.uiSetIndex(gkey, 2) is True and br.genderMenu.cursor == 1
    birch_step(a, br, "a")  # GIRL
    birch_step(a, br)  # "what is your name?" printed; Birch waits for a press
    assert plain(a.eng.uiCursor(top_key(a)))["ready"] is True
    birch_step(a, br, "a")
    assert classes(a) == [("Screen", "BirchSpeech"), ("NamingScreen", "NamingScreen")]
    nkey = top_key(a)
    assert plain(a.eng.uiCursor(nkey))["busy"] is True  # the screen fading in
    birch_step(a, br)
    birch_step(a, br)
    assert plain(a.eng.uiCursor(nkey))["busy"] is False
    assert a.eng.uiSetGlyphs(nkey, a.b.to_lua([b"K", b"A", b"I"])) == (True, b"a")
    birch_step(a, br, "a")  # OK
    birch_step(a, br)
    birch_step(a, br)  # faded out: back to the speech
    birch_step(a, br)  # "so it is you?" printed; the yes/no
    assert classes(a)[-1] == ("ChoiceBox", None)
    ykey = top_key(a)
    assert plain(a.eng.uiOpenFacts(ykey))["labels"] == ["YES", "NO"]
    assert br.playerName == b"KAI" and br.gender == 1


# ------------------------------------------------------------ main.lua and a peer


def field_rig():
    rig = Rig(family="gen3")
    rig.world("EM_TEST_TOWN", 5, 6, "down")
    return rig


def pump(rig, peer, until, limit=40):
    got = []
    for _ in range(limit):
        rig.step()
        got.extend(peer.messages())
        if any(until(m) for m in got):
            return got
    raise AssertionError(f"not within {limit} steps: {[m['type'] for m in got]}")


def opened(msgs, kind):
    return next(m["data"] for m in msgs if m["type"] == "ui.open" and m["data"]["kind"] == kind)


def connected(rig):
    peer = rig.connect()
    peer.hello()
    peer.messages()
    return peer


def no_faults(rig):
    faults = [line for line in rig.logs()
              if "step_failed" in line or "[error]" in line or ("hook" in line and "failed" in line)]
    assert not faults, faults


def test_a_scripts_text_box_is_advanced_page_by_page_and_closes_on_its_last_press():
    rig = field_rig()
    E = rig.E
    peer = connected(rig)
    E.scriptMessage(b"FIRST PAGE\nSECOND LINE\fLAST PAGE")
    msgs = pump(rig, peer, lambda m: m["type"] == "ui.open")
    text = opened(msgs, "text")
    assert text["pages"] == [["FIRST PAGE", "SECOND LINE"], ["LAST PAGE"]]
    ui = text["ui"]
    peer.send("ui.advance", {"ui": ui})
    msgs = pump(rig, peer, lambda m: m["type"] == "ui.update" and m["data"].get("page") == 1)
    peer.send("ui.advance", {"ui": ui})
    msgs = pump(rig, peer, lambda m: m["type"] == "ui.close" and m["data"]["ui"] == ui)
    assert E.scriptPresses == 1 and not E.Message.open and not E.Space.vm.running
    no_faults(rig)


def test_a_yes_no_answered_no_moves_the_cursor_and_presses_a():
    rig = field_rig()
    E = rig.E
    peer = connected(rig)
    E.openMessage(b"TAKE IT?", True)
    E.Choice.yesNo(rig.b.eval("function(yes) E_answer = yes end"), rig.b.to_lua({"ignoreBPress": True}))
    msgs = pump(rig, peer, lambda m: m["type"] == "ui.open" and m["data"]["kind"] == "choice")
    choice = opened(msgs, "choice")
    assert (choice["labels"], choice["question"]) == (["YES", "NO"], "TAKE IT?")
    peer.send("ui.pick", {"ui": choice["ui"], "index": 1})
    pump(rig, peer, lambda m: m["type"] == "ui.close" and m["data"]["ui"] == choice["ui"])
    assert rig.b.eval("function() return E_answer end")() is False  # NO, though B does nothing here
    no_faults(rig)


def test_start_opens_the_start_menu_and_a_pick_opens_the_bag_as_a_mirror():
    rig = field_rig()
    E = rig.E
    peer = connected(rig)
    seq = peer.send("act.button", {"button": "start", "how": "tap"})
    msgs = pump(rig, peer, lambda m: m["type"] == "ui.open")
    assert next(m for m in msgs if m.get("re") == seq)["data"]["ok"] is True
    menu = opened(msgs, "menu")
    assert (menu["screen"], len(menu["items"]), menu["items"][2]["label"]) == ("StartMenu", 6, "TEST BAG")
    peer.send("ui.pick", {"ui": menu["ui"], "index": 2})
    msgs = pump(rig, peer, lambda m: m["type"] == "ui.open" and m["data"]["kind"] == "mirror")
    assert opened(msgs, "mirror")["screen"] == "BagMenu" and list(E.startPicks.values()) == [b"bag"]
    no_faults(rig)


def test_a_naming_screen_takes_the_name_and_finishes_through_ok():
    rig = field_rig()
    E = rig.E
    peer = connected(rig)
    E.Naming.open(rig.b.to_lua({"maxLen": 5}))
    msgs = pump(rig, peer, lambda m: m["type"] == "ui.open")
    naming = opened(msgs, "naming")
    assert (naming["max"], naming["grid"][0][0]) == (5, ["A", "B", "C"])
    peer.send("ui.name", {"ui": naming["ui"], "glyphs": ["K", "A", "I"]})
    pump(rig, peer, lambda m: m["type"] == "ui.close" and m["data"]["ui"] == naming["ui"])
    assert list(E.namedResults.values()) == [b"KAI"]
    no_faults(rig)


def test_birchs_whole_speech_is_answered_through_the_ui_and_ends_in_the_field():
    rig = Rig(family="gen3")
    E = rig.E
    peer = connected(rig)
    seq = peer.send("region.start", {"region": "hoenn"})
    msgs = pump(rig, peer, lambda m: m.get("re") == seq, limit=4)
    seen = list(msgs)
    answered = set()

    def answer(data):
        kind, ui = data["kind"], data["ui"]
        if kind == "text":
            for _ in range(len(data["pages"]) + 1):
                peer.send("ui.advance", {"ui": ui})
        elif kind == "choice" and data["labels"] == ["BOY", "GIRL"]:
            peer.send("ui.pick", {"ui": ui, "index": 1})
        elif kind == "choice":
            peer.send("ui.pick", {"ui": ui, "index": 0})
        elif kind == "naming":
            peer.send("ui.name", {"ui": ui, "glyphs": ["K", "A", "I"]})

    new = list(msgs)  # the speech's first text box opens with the act's answer
    for _ in range(400):
        for m in new:
            if m["type"] == "ui.open" and m["data"]["ui"] not in answered and m["data"]["kind"] != "mirror":
                answered.add(m["data"]["ui"])
                answer(m["data"])
        if E.game.phase == b"field":
            break
        rig.step()
        new = peer.messages()
        seen.extend(new)
    assert E.game.phase == b"field"
    br = E.game.boot.custom.newGame
    assert (br.result.action, br.result.name, br.result.gender) == (b"new_game", b"KAI", 1)
    kinds = [m["data"]["kind"] for m in seen if m["type"] == "ui.open"]
    assert kinds.count("text") >= 5 and kinds.count("choice") == 2 and kinds.count("naming") == 1
    # the title's mirror closed; the slide show under the speech's text is announced only when it is the top
    assert kinds[0] == "text"
    errors = [m["data"]["code"] for m in seen if m["type"] == "error"]
    assert errors == []
    no_faults(rig)


def test_the_mirror_sends_the_gbas_240x160_frames():
    import base64
    import struct

    rig = Rig(family="gen3", graphics=True)
    rig.world("EM_TEST_TOWN", 5, 6, "down")
    peer = connected(rig)
    peer.send("mirror.sub", {"fps": 30})
    rig.step()
    b = rig.b
    vanilla = b.eval("function() return false end")
    assert b.FakeMod.call(b.mod, b"render.output_enabled", vanilla) is True
    # one love.draw: the composite at 4x (960x640) in a 1000x640 window, then render.output
    canvas = b.love.source(b"gen3 frame", 1000, 640)
    ctx = b.to_lua({"width": 1000, "height": 640, "gameX": 20, "gameY": 0, "gameWidth": 960, "gameHeight": 640,
                    "scale": 4, "dpiX": 1, "dpiY": 1, "generation": 1})
    ctx[b"canvas"] = canvas
    b.love.setActive(b.love.source(b"the screen", 1, 1))
    assert b.FakeMod.call(b.mod, b"render.output", vanilla, ctx) is False
    rig.step()
    (frame,) = [m["data"] for m in peer.messages() if m["type"] == "mirror.frame"]
    assert (frame["w"], frame["h"]) == (240, 160)
    raw = base64.b64decode(frame["png"])
    assert struct.unpack(">II", raw[16:24]) == (240, 160)
    (draw,) = list(b.love.draws.values())
    assert (draw.sx, draw.sy) == (0.25, 0.25) and draw.to.w == 240
    no_faults(rig)
