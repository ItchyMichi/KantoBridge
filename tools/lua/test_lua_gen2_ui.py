"""G2B (docs/specs/GEN2_BRIDGE_PLAN.md §5.5): the gen2 adapter's UI part (gen1-bridge/lib/engine/gen2/ui.lua)
on the fake Gen 2 engine (fake_engine2.lua), the adapter alone (Adapter2): the core's UI stack read from the
shared state stack -- the shared TextBox and ChoiceBox, the script menu as a list, the start menu without QUIT
and MODS, Crystal's gender screen as a choice, Oak's name pick and its keyboard as one naming screen, a
keyboard alone, the clock answered with the host's time (J5), the caller box, the starter picture, the boot
chain as one title mirror, every other screen a mirror. main.lua and a peer: test_lua_gen2_protocol.py.
Everything is made up."""

import pytest

from adapter2 import Adapter2, plain

pytestmark = pytest.mark.lua


def stack(a):
    return plain(a.eng.uiStack())


def classes(a):
    return [(e["cls"], e.get("screen")) for e in stack(a)]


def top_key(a):
    return stack(a)[-1]["key"]


def tap(a, button):
    a.press(button)
    a.tick()
    a.release(button)


def to_name_pick(a):
    """NEW GAME, Oak's speech answered by A (the gender screen, his lines) up to the name pick."""
    g = a.E.game
    g.newGame(g)
    for _ in range(20):
        top = g.stack.top(g.stack)
        if top.screenId == b"Gen2NamePick":
            return top
        tap(a, "a")
    raise AssertionError("no name pick")


# ------------------------------------------------------------ the shared text box and yes/no


def test_the_field_stack_is_the_overworld_the_shared_text_box_its_yes_no_and_the_screens():
    a = Adapter2()
    a.field()
    E = a.E
    assert classes(a) == [("Overworld", None)]
    E.openYesNo(a.lua([b"DO IT?\nREALLY?"]), a.b.eval("function(yes) E_answer = yes end"))
    a.tick()  # the box's last page is up: it pushes the yes/no over itself
    first = stack(a)
    assert [(e["cls"], e.get("screen")) for e in first] == [("Overworld", None), ("TextBox", None),
                                                            ("ChoiceBox", None)]
    keys = [e["key"] for e in first]
    assert len(set(keys)) == 3 and keys == [e["key"] for e in stack(a)]  # stable while open
    text, choice = keys[1], keys[2]
    facts = plain(a.eng.uiOpenFacts(text))
    assert (facts["pages"], facts["cont"], facts["choice"]) == ([["DO IT?", "REALLY?"]], [[False, False]], True)
    assert (facts["auto"], facts["stay"], facts["instant"]) == (False, False, False)
    # the yes/no answers it: no press of the box's own
    assert plain(a.eng.uiCursor(text)) == {"page": 1, "line": 2, "done": True, "waiting": False, "ready": False}
    facts = plain(a.eng.uiOpenFacts(choice))
    assert (facts["labels"], facts["question"]) == (["YES", "NO"], ["DO IT?", "REALLY?"])
    assert plain(a.eng.uiCursor(choice)) == {"index": 1, "busy": False}
    assert a.eng.uiSetIndex(choice, 2) is True and E.game.stack.top(E.game.stack).index == 2
    assert a.eng.uiSetIndex(choice, 3) is False and a.eng.uiSetIndex(choice, 1.5) is False
    tap(a, "a")
    assert a.b.eval("function() return E_answer end")() is False and classes(a) == [("Overworld", None)]
    assert a.eng.uiClass(choice) == b"Busy"  # gone
    E.pushScreen(b"Gen2PackMenu")
    assert classes(a)[-1] == ("Screen", "Gen2PackMenu")
    assert tuple(a.eng.screenSize()) == (160, 144)


def test_a_text_boxs_pages_and_when_a_press_takes():
    a = Adapter2()
    a.field()
    box = a.E.openTextBox(a.lua([b"FIRST\nSECOND", b"LAST"]))
    key = top_key(a)
    assert plain(a.eng.uiOpenFacts(key))["pages"] == [["FIRST", "SECOND"], ["LAST"]]
    assert plain(a.eng.uiCursor(key)) == {"page": 1, "line": 2, "done": False, "waiting": True, "ready": True}
    tap(a, "a")
    # the last page: done, and still a press to close it (the v0.3.85 arrow rule)
    assert plain(a.eng.uiCursor(key)) == {"page": 2, "line": 1, "done": True, "waiting": True, "ready": True}
    box.holdFrames = 3
    assert plain(a.eng.uiCursor(key))["ready"] is False
    box.holdFrames = 0
    box.sfxWait = True
    assert plain(a.eng.uiCursor(key))["waiting"] is False and plain(a.eng.uiCursor(key))["ready"] is False


# ------------------------------------------------------------ menus


def test_the_script_menu_is_a_list_answered_one_based():
    a = Adapter2()
    a.field()
    E = a.E
    E.openScriptMenu(a.lua([b"ONE", b"TWO", b"THREE", b"FOUR"]), a.lua({"cols": 2}))
    assert classes(a)[-1] == ("ListMenu", "ListMenu")
    key = top_key(a)
    facts = plain(a.eng.uiOpenFacts(key))
    labels = [{"label": x} for x in ("ONE", "TWO", "THREE", "FOUR")]
    assert (facts["items"], facts["rows"], facts["wrap"]) == (labels, 4, False)
    assert plain(a.eng.uiItems(key)) == labels and plain(a.eng.uiMoneyFooter(key)) == {}
    assert plain(a.eng.uiCursor(key)) == {"index": 1, "scroll": 0, "count": 4, "busy": False}
    assert a.eng.uiSetIndex(key, 4) is True and plain(a.eng.uiCursor(key))["index"] == 4
    assert a.eng.uiSetIndex(key, 5) is False and a.eng.uiSetIndex(key, 0) is False
    tap(a, "a")  # (row 2 - 1) * 2 cols + col 2
    E.openScriptMenu(a.lua([b"YES", b"NO"]), a.lua({"wrap": True}))
    assert plain(a.eng.uiOpenFacts(top_key(a)))["wrap"] is True
    tap(a, "b")  # B is 0
    E.openScriptMenu(a.lua([b"ONLY"]), a.lua({"disableB": True}))
    tap(a, "b")  # ... unless the header disables it
    assert list(E.menuAnswers.values()) == [4, 0] and classes(a)[-1] == ("ListMenu", "ListMenu")


def test_the_start_menu_is_a_menu_without_quit_and_mods():
    a = Adapter2()
    a.field()
    E = a.E
    menu = E.openStartMenu()
    assert classes(a)[-1] == ("Menu", "StartMenu")
    key = top_key(a)
    facts = plain(a.eng.uiOpenFacts(key))
    assert [i["label"] for i in facts["items"]] == ["TEST POKEDEX", "TEST POKEMON", "TEST PACK", "TEST STATUS",
                                                    "TEST SAVE", "TEST OPTION"]
    assert (facts["rows"], facts["wrap"]) == (6, True)
    assert plain(a.eng.uiCursor(key)) == {"index": 1, "scroll": 0, "count": 6, "busy": False}
    assert a.eng.uiSetIndex(key, 3) is True and menu.list.index == 3
    assert a.eng.uiSetIndex(key, 7) is False
    menu.list.index = 8  # QUIT, reached by the engine's own keys
    assert plain(a.eng.uiCursor(key))["index"] == 6
    menu.phase = b"confirm"  # its "are you sure?" is up: no answers
    assert plain(a.eng.uiCursor(key))["busy"] is True
    menu.phase = None
    menu.list.index = 3
    tap(a, "a")
    assert list(E.startChoices.values()) == [b"pack"] and classes(a)[-1] == ("Screen", "Gen2PackMenu")
    # a hidden row between two others: the client's rows are the engine's without it
    E.game.stack.clear(E.game.stack)
    menu = E.openStartMenu(a.lua([b"pokemon", b"mods", b"save", b"quit"]))
    key = top_key(a)
    assert [i["label"] for i in plain(a.eng.uiItems(key))] == ["TEST POKEMON", "TEST SAVE"]
    assert a.eng.uiSetIndex(key, 2) is True and menu.list.index == 3


# ------------------------------------------------------------ Oak's speech: the gender, the name


def test_crystals_gender_screen_is_a_choice_its_cursor_answers():
    a = Adapter2()
    E = a.E
    E.genderTyping = 2
    E.translations[b"TEST GIRL"] = b"TEST LASS"  # drawn through the string catalog
    g = E.game
    g.newGame(g)
    assert classes(a) == [("Screen", "OakSpeech"), ("ChoiceBox", "GenderSelect")]
    key = top_key(a)
    facts = plain(a.eng.uiOpenFacts(key))
    assert (facts["labels"], facts["question"]) == (["TEST BOY", "TEST LASS"], ["TEST QUESTION", "OF TWO LINES"])
    # B does nothing here: NO is the cursor and A
    assert plain(a.eng.uiCursor(key)) == {"index": 1, "busy": True, "noByCursor": True}  # still typing
    a.tick(2)
    assert plain(a.eng.uiCursor(key))["busy"] is False
    assert a.eng.uiSetIndex(key, 2) is True and a.eng.uiSetIndex(key, 3) is False
    tap(a, "a")
    assert g.save.player.gender == b"female" and E.speechRuns[1].answers.gender_select == b"female"


def test_oaks_name_pick_and_its_keyboard_are_one_naming_screen():
    a = Adapter2()
    E = a.E
    g = E.game
    pick = to_name_pick(a)
    st = stack(a)
    assert [(e["cls"], e.get("screen")) for e in st] == [("Screen", "OakSpeech"), ("NamingScreen", "NamingScreen"),
                                                         ("Menu", None)]
    naming, presets = st[1]["key"], st[2]["key"]
    assert plain(a.eng.uiCursor(naming)) == {"choosing": True, "typed": 0, "busy": False}
    facts = plain(a.eng.uiOpenFacts(naming))
    assert (facts["title"], facts["presets"], facts["max"], facts["value"]) == (
        "TEST NAME?", ["TESTA", "TESTB", "TESTC"], 7, "")
    # the keyboard NEW NAME opens, both cases, before it is up; the blank (a typed space) left out
    assert facts["grid"][0][2] == ["S", "T", "U", "V", "W", "X", "Y", "Z"]
    assert facts["grid"][1][2][-1] == "<PK>" and len(facts["grid"]) == 2
    assert plain(a.eng.uiItems(presets)) == [{"label": "NEW NAME"}, {"label": "TESTA"}, {"label": "TESTB"},
                                             {"label": "TESTC"}]
    assert plain(a.eng.uiCursor(presets)) == {"index": 1, "scroll": 0, "count": 4, "busy": False}
    assert a.eng.uiSetGlyphs(naming, a.lua([b"K", b"A", b"I"])) is False  # only the names are up
    assert a.eng.uiSetIndex(presets, 3) is True and pick.cursor == 3
    assert a.eng.uiSetIndex(presets, 5) is False
    assert a.eng.uiSetIndex(presets, 1) is True
    tap(a, "a")  # NEW NAME: the keyboard over the pick, the same naming screen
    st = stack(a)
    assert [(e["cls"], e.get("screen")) for e in st] == [("Screen", "OakSpeech"), ("NamingScreen", "NamingScreen")]
    assert st[1]["key"] == naming
    assert plain(a.eng.uiCursor(naming)) == {"choosing": False, "typed": 0, "busy": False}
    kb = g.stack.top(g.stack)
    assert a.eng.uiSetGlyphs(naming, a.lua([b"A"] * 8)) is False  # past the 7 it takes
    assert tuple(a.eng.uiSetGlyphs(naming, a.lua([b"K", b"A", b"I"]))) == (True, b"a")
    assert (kb.text, kb.row, kb.col) == (b"KAI", 3, 6)  # the cursor on END
    assert plain(a.eng.uiCursor(naming))["typed"] == 3
    tap(a, "a")
    assert g.save.player.name == b"KAI" and E.speechRuns[1].answers.name == b"KAI"
    assert all(e["key"] != naming for e in stack(a))


def test_the_keyboard_takes_a_glyph_only_while_the_name_is_short_of_its_length_in_bytes():
    # addCharacter checks the text's byte length before each glyph (NamingScreen.lua:159-163), so a
    # many-byte glyph can still go in last, and nothing after it
    a = Adapter2()
    to_name_pick(a)
    tap(a, "a")  # NEW NAME
    naming = top_key(a)
    kb = a.E.game.stack.top(a.E.game.stack)
    assert tuple(a.eng.uiSetGlyphs(naming, a.lua([b"A", b"B", b"C", b"D", b"E", b"F", b"<PK>"]))) == (True, b"a")
    assert kb.text == b"ABCDEF<PK>"
    assert a.eng.uiSetGlyphs(naming, a.lua([b"A", b"B", b"C", b"D", b"<PK>", b"E"])) is False
    assert a.eng.uiSetGlyphs(naming, a.lua([b"A", b""])) is False


def test_a_preset_is_picked_and_the_pics_slide_holds_the_answers():
    a = Adapter2()
    E = a.E
    E.namePickSlide = 2
    pick = to_name_pick(a)
    naming, presets = stack(a)[1]["key"], stack(a)[2]["key"]
    assert plain(a.eng.uiCursor(naming))["busy"] is True and plain(a.eng.uiCursor(presets))["busy"] is True
    a.tick(2)
    assert plain(a.eng.uiCursor(naming))["busy"] is False
    a.eng.uiSetIndex(presets, 3)
    tap(a, "a")
    assert pick.slide == b"out" and plain(a.eng.uiCursor(naming))["busy"] is True
    a.tick(2)
    assert E.game.save.player.name == b"TESTB"


def test_a_keyboard_alone_has_no_presets_and_an_empty_name_is_the_callers_default():
    a = Adapter2()
    a.field()
    E = a.E
    E.openNaming(a.lua({"type": "nickname"}))
    assert classes(a) == [("Overworld", None), ("NamingScreen", "NamingScreen")]
    key = top_key(a)
    facts = plain(a.eng.uiOpenFacts(key))
    assert (facts["title"], facts["presets"], facts["max"]) == ("TEST NICK?", {}, 10)
    assert plain(a.eng.uiCursor(key)) == {"choosing": False, "typed": 0, "busy": False}
    assert tuple(a.eng.uiSetGlyphs(key, a.lua({}))) == (True, b"a")
    tap(a, "a")
    assert list(E.named.values()) == [b""]
    E.openNaming(a.lua({"type": "box"}))
    facts = plain(a.eng.uiOpenFacts(top_key(a)))
    assert (facts["title"], facts["max"], facts["grid"][0][3]) == ("TEST BOX?", 8,
                                                                    ["1", "2", "3", "4", "5", "6", "7", "8", "9"])
    assert tuple(a.eng.uiSetGlyphs(top_key(a), a.lua([b"B", b"1"]))) == (True, b"a")
    kb = E.game.stack.top(E.game.stack)
    assert (kb.row, kb.col) == (4, 6)  # a box page's bottom row is one lower


# ------------------------------------------------------------ the clock (J5), the phone, the picture


def test_the_clock_and_moms_day_wheel_are_answered_with_the_hosts_own_time():
    a = Adapter2()
    a.field()
    E = a.E
    save = E.game.save
    E.hostWeekday = 5
    E.openInitClock(b"day")
    assert classes(a) == [("Overworld", None), ("Busy", None)]  # never shown to the client
    a.tick(3)
    assert E.game.stack.top(E.game.stack) is None
    assert [plain(x) for x in E.clockAnswers.values()] == [[5]] and save.rtc.startDay == 0
    E.hostMinutes = 8 * 60 + 30
    E.openInitClock(b"clock")
    stack(a)
    E.hostMinutes = 8 * 60 + 31  # the minute turns over while it walks its pages
    a.tick(8)
    assert E.game.stack.top(E.game.stack) is None
    assert plain(E.clockAnswers[2]) == [8, 31] and save.rtc.startMinute == 0


def test_a_phone_calls_box_is_busy_under_its_text_and_the_starter_picture_is_a_mirror():
    a = Adapter2()
    a.field()
    E = a.E
    E.openCall()
    assert classes(a) == [("Overworld", None), ("Busy", None), ("TextBox", None)]
    tap(a, "a")
    assert classes(a) == [("Overworld", None), ("Busy", None)]
    E.game.stack.pop(E.game.stack)
    w = E.game.world
    w.waitForButton(w, a.b.eval("function() E_pic = true end"))
    assert classes(a) == [("Overworld", None), ("PicBox", "PicBox")]
    a.tick()  # the tick it was armed on
    tap(a, "a")
    assert a.b.eval("function() return E_pic end")() is True and classes(a) == [("Overworld", None)]
    E.openTextBox()
    w.waitForButton(w, a.b.eval("function() end"))
    assert classes(a)[-1] == ("TextBox", None)  # polled only with nothing on the stack


# ------------------------------------------------------------ the boot, mirrors, busy states


def test_the_boot_chain_is_one_title_mirror_and_the_rest_are_mirrors_or_busy():
    a = Adapter2()
    E = a.E
    assert classes(a) == [("Screen", "TitleState")]
    title = top_key(a)
    E.pushScreen(b"Gen2MainMenu")
    assert stack(a) == [{"key": title, "cls": "Screen", "screen": "TitleState"}]
    E.pushScreen(b"Gen2OptionsMenu")
    assert classes(a) == [("Screen", "TitleState"), ("Screen", "Gen2OptionsMenu")]
    a.field()
    assert classes(a) == [("Overworld", None)]
    E.leaveWorld()  # back to the title: a new boot chain, a new mirror
    assert classes(a) == [("Screen", "TitleState")] and top_key(a) != title
    a.field()
    E.pushScreen(b"Gen2BattleState")
    E.pushScreen(b"Gen2BattleTransition")
    assert classes(a)[1:] == [("BattleState", "BattleState"), ("Busy", None)]
    E.game.stack.clear(E.game.stack)
    for sid in (b"Gen2MenuFade", b"Gen2BlankScreen"):
        E.pushScreen(sid)
    assert a.eng.holdAway() is True  # the park's hold
    assert classes(a) == [("Overworld", None), ("Busy", None), ("Busy", None), ("Busy", None)]
