"""observe/kb_ui.lua (the UI observer) and act/kb_uiact.lua (the answers), wired by main.lua, on
the simulated engine with fake widgets that behave like the engine's (fake_ui.lua): every kind's
open/update/close, the text box's ready gate, YES/NO, menus, lists, quantities, naming with and
without presets, the adapted screens, mirror screens and act.button, the markup. Every message the
peer receives is schema-checked (rig.Peer). All text is made up."""

import json

import pytest

from uisim import Game

pytestmark = pytest.mark.lua

POKE = "POKé"


def kinds(msgs):
    return [(m["type"], m["data"].get("kind")) for m in msgs if m["type"].startswith("ui.")]


def ui_msgs(msgs):
    return [m for m in msgs if m["type"].startswith("ui.")]


def opened(g, kind=None):
    return [m["data"] for m in g.received("ui.open") if kind is None or m["data"]["kind"] == kind]


def errors(g):
    return [(m["data"]["code"], m.get("re")) for m in g.received("error")]


# ------------------------------------------------------------ the markup


@pytest.fixture(scope="module")
def Ui():
    from kblua import Bridge
    return Bridge().module("observe/kb_ui.lua")


@pytest.mark.parametrize("raw, want, fixes", [
    ("HELLO!", "HELLO!", 0),
    ("#MON", POKE + "MON", 0),                                   # the POKé macro
    ("<PK><MN> CENTER", "<PK><MN> CENTER", 0),                   # tokens stay
    ("TEST'd TEST'll", "TEST'd TEST'll", 0),                     # ligatures stay as two characters
    ("é♂♀×¥…▶", "é♂♀×¥…▶", 0),
    ("{NUM:hCoins, 2 | X}COINS", "COINS", 1),                    # a token the engine left behind
    ("A<BOLD_A>B", "AAB", 1),
    ("<DOT><COLON><LV>5<ID><to><ED>", ".:" + ":L5IDtoED", 6),    # font-only sequences
    ("<PKMN>", "<PK><MN>", 1),
    ("<OTHER>", "<OTHER>", 0),                                   # anything else is literal
    ("TWO\nLINES", "TWO LINES", 1),
    ("BELL\x07", "BELL", 1),
])
def test_markup(Ui, raw, want, fixes):
    out, n = Ui.markup(raw.encode("utf-8"))
    assert out.decode("utf-8") == want and n == fixes


def test_markup_repairs_bad_utf8_and_caps_the_length(Ui):
    out, n = Ui.markup(b"AB\xffC\xc3")
    assert out == b"AB?C?" and n == 2
    out, n = Ui.markup(("é" * 1200).encode("utf-8"))
    assert out.decode("utf-8") == "é" * 1000 and n == 1
    assert Ui.markup(None) == (b"", 1)


# ------------------------------------------------------------ text


def test_text_box_open_updates_ready_gate_and_close():
    g = Game()
    g.push(g.E.textBox(g.s("HELLO!\f#MON\vLINE TWO\vLINE 3"), g.opts()))
    g.run()
    (op,) = opened(g)
    assert op == {"ui": 1, "kind": "text", "pages": [["HELLO!"], [POKE + "MON", "LINE TWO", "LINE 3"]],
                  "cont": [[False], [False, True, True]]}
    assert g.received("player.state")[-1]["data"]["mode"] == "screen"
    # the client is ahead of the box: its advance waits until the arrow is up and preWait is over
    g.send("ui.advance", {"ui": 1})
    g.until(lambda m: m["type"] == "ui.update" and m["data"].get("waiting") is True)
    box = g.top()
    assert box.waiting and box.preWait > 0 and g.tapped() == []
    g.run(box.preWait)
    assert g.tapped() == []
    g.run()  # preWait is 0 now: A goes in the same logic step the box reads it
    assert g.tapped() == ["a"] and box.pageIndex == 2 and box.holdFrames == 20
    update = g.until(lambda m: m["type"] == "ui.update" and "page" in m["data"])
    assert update["data"] == {"ui": 1, "page": 1, "waiting": False}  # line 0, as before
    # two queued advances: each waits for its own stop (the scroll, then the next scroll),
    # never tapping during the page clear's hold or while the line types
    g.send("ui.advance", {"ui": 1})
    g.send("ui.advance", {"ui": 1})
    holds = []
    while box.holdFrames > 0 or not box.waiting:
        holds.append(box.holdFrames)
        g.run()
    assert holds[0] > 0 and g.tapped() == ["a"], "nothing tapped before the box waits again"
    g.until(lambda m: m["type"] == "ui.update" and m["data"].get("line") == 2, limit=200)
    assert g.tapped() == ["a", "a", "a"]
    done = g.until(lambda m: m["type"] == "ui.update" and m["data"].get("done") is True)
    assert done["data"]["waiting"] is True
    g.send("ui.advance", {"ui": 1})
    close = g.until(lambda m: m["type"] == "ui.close")
    assert close["data"] == {"ui": 1} and g.depth() == 1
    assert g.received("player.state")[-1]["data"]["mode"] == "idle"
    # a late advance names a UI that is gone
    seq = g.send("ui.advance", {"ui": 1})
    g.run()
    assert errors(g) == [("ui.stale", seq)]


def test_advances_are_capped_and_die_with_their_box():
    g = Game()
    g.push(g.E.textBox(g.s("ONE\fTWO"), g.opts()))
    g.run()
    for _ in range(20):
        g.send("ui.advance", {"ui": 1})
    g.until(lambda m: m["type"] == "ui.close", limit=300)
    assert g.tapped() == ["a", "a"]
    g.push(g.E.textBox(g.s("NEXT"), g.opts()))
    g.run(30)
    assert opened(g)[-1]["ui"] == 2 and g.depth() == 2, "the extra advances never reach the next box"
    assert g.tapped() == ["a", "a"]


@pytest.mark.parametrize("flags", [{"auto": {"delay": 5}}, {"stay": True}])
def test_auto_and_stay_boxes_take_no_advance(flags):
    g = Game()
    g.push(g.E.textBox(g.s("NO BUTTON"), g.opts(**flags)))
    g.run()
    (op,) = opened(g)
    assert op[next(iter(flags))] is True
    g.send("ui.advance", {"ui": 1})
    g.run(40)
    assert g.tapped() == []
    if "auto" in flags:
        assert g.received("ui.close") and g.depth() == 1
    else:
        assert g.depth() == 2 and not g.received("ui.close")
        assert not any(m["data"].get("waiting") for m in g.received("ui.update")), "no arrow on a stay box"


def test_stay_prompt_box_takes_one_press():
    g = Game()
    g.push(g.E.textBox(g.s("WAIT FOR IT"), g.opts(stay={"prompt": True})))
    g.run()
    assert "stay" not in opened(g)[0], "its end takes a press, like a plain box's"
    g.send("ui.advance", {"ui": 1})
    done = g.until(lambda m: m["type"] == "ui.update" and m["data"].get("done") is True)
    assert done["data"]["waiting"] is True
    g.run(3)
    assert g.tapped() == ["a"] and g.top().stayShown and g.depth() == 2
    assert g.received("ui.update")[-1]["data"] == {"ui": 1, "waiting": False}, "then it just stays up"


@pytest.mark.parametrize("flags, auto, stay", [
    ({"auto": {"delay": 5}}, True, False),
    ({"auto": {"promptFirst": True}}, False, False),   # one press first
    ({"auto": {"wait": True}}, False, False),          # a plain box once its sound is over
    ({"stay": True}, False, True),
    ({"stay": {"onShown": None}}, False, True),
    ({"stay": {"prompt": True}}, False, False),
])
def test_auto_and_stay_mean_no_press_at_the_end(flags, auto, stay):
    g = Game()
    g.push(g.E.textBox(g.s("FLAGS"), g.opts(**flags)))
    g.run()
    (op,) = opened(g)
    assert (op.get("auto", False), op.get("stay", False)) == (auto, stay)


def test_instant_box_opens_finished():
    g = Game()
    g.push(g.E.textBox(g.s("ONE\fTWO\nTHREE"), g.opts(instant=True)))
    out = g.run()
    (op,) = opened(g)
    assert op["instant"] is True
    (upd,) = [m["data"] for m in out if m["type"] == "ui.update"]
    assert upd == {"ui": 1, "page": 1, "line": 1, "waiting": True, "done": True}


def test_long_pages_are_split_into_protocol_pages_of_eight_lines():
    g = Game()
    text = "\v".join(f"LINE {i}" for i in range(11))
    g.push(g.E.textBox(g.s(text), g.opts(instant=True)))
    out = g.run()
    (op,) = opened(g)
    assert [len(p) for p in op["pages"]] == [8, 3] and op["pages"][1][0] == "LINE 8"
    (upd,) = [m["data"] for m in out if m["type"] == "ui.update"]
    assert (upd["page"], upd["line"]) == (1, 2)


def test_advance_and_other_answers_check_the_kind():
    g = Game()
    g.push(g.E.textBox(g.s("TEXT"), g.opts()))
    g.run()
    s1 = g.send("ui.pick", {"ui": 1, "index": 0})
    s2 = g.send("ui.qty", {"ui": 1, "qty": 2})
    s3 = g.send("ui.back", {"ui": 1})
    s4 = g.send("ui.advance", {"ui": 99})
    s5 = g.send("ui.pick", {"ui": "one", "index": 0})
    g.run()
    assert errors(g) == [("ui.wrong_kind", s1), ("ui.wrong_kind", s2), ("ui.wrong_kind", s3),
                         ("ui.stale", s4), ("bad_message", s5)]


def test_a_builder_cannot_answer():
    g = Game(role="builder")
    g.push(g.E.textBox(g.s("TEXT"), g.opts()))
    g.run()
    seq = g.send("ui.advance", {"ui": 1})
    g.run()
    assert errors(g) == [("role", seq)] and not g.received("ui.open")


# ------------------------------------------------------------ text: the v0.3.85 rules (ENGINE_UPGRADE_PLAN B3)


def updates(g, ui=1):
    return [m["data"] for m in g.received("ui.update") if m["data"]["ui"] == ui]


def test_a_scrolled_line_after_a_lone_row_shows_under_a_blank_row():
    """TextBox:beginLine (:386-401): Gen 1's fixed two-row box scrolls a lone first row out when
    the next line is a cont, so the box shows a blank row and the new line; visibleText (:404-412)
    maps shownSource to the page's lines."""
    g = Game()
    box = g.push(g.E.textBox(g.s("FIRST\vSECOND\vTHIRD"), g.opts()))
    g.run()
    assert list(box.shownSource.values()) == [1]
    g.send("ui.advance", {"ui": 1})
    g.until(lambda m: m["type"] == "ui.update" and m["data"].get("line") == 1)
    assert list(box.shownSource.values()) == [False, 2], "the lone row scrolled out, a blank one above"
    assert [v.decode() for v in box.visibleText(box).values()] == ["", "SECOND"]
    g.send("ui.advance", {"ui": 1})
    g.until(lambda m: m["type"] == "ui.update" and m["data"].get("line") == 2)
    assert list(box.shownSource.values()) == [2, 3], "two rows in view: no blank"
    assert [v.decode() for v in box.visibleText(box).values()] == ["SECOND", "THIRD"]


def test_a_choice_question_reads_the_rows_in_view():
    g = Game()
    answers = []
    g.push(g.E.textBox(g.s("THE LONG\vQUESTION?"), g.opts(choice=lambda yes: answers.append(yes))))
    g.run()
    g.send("ui.advance", {"ui": 1})  # the scroll
    choice = g.until(lambda m: m["type"] == "ui.open" and m["data"]["kind"] == "choice")
    # the first row scrolled out of the fixed two-row box; the blank row adds no words
    assert choice["data"]["question"] == "QUESTION?"
    g.send("ui.back", {"ui": 2})
    g.until(lambda m: m["type"] == "ui.close" and m["data"]["ui"] == 1)
    assert answers == [False]


def test_an_instant_box_holds_the_pages_last_rows():
    g = Game()
    box = g.push(g.E.textBox(g.s("ONE\nTWO\nTHREE"), g.opts(instant=True)))
    g.run()
    assert list(box.shownSource.values()) == [2, 3]
    assert [v.decode() for v in box.visibleText(box).values()] == ["TWO", "THREE"]
    box2 = g.push(g.E.textBox(g.s("ALONE\vLAST"), g.opts(instant=True)))
    g.run()
    assert list(box2.shownSource.values()) == [False, 2], "a cont after a lone row: the blank row again"


@pytest.mark.parametrize("text, arrow", [("ALL DONE{DONE}", False), ("ALL DONE{PROMPT}", True), ("ALL DONE", True)])
def test_a_done_ending_takes_its_press_without_the_arrow(text, arrow):
    """TextBox.new (:150-157): a {DONE} ending sets waitButton, which arrowVisible (:435) hides the
    engine's arrow for; the press still closes the box (:636-643). The bridge's `waiting` is the
    client's press signal, not the drawn arrow (kb_engine textArrow; G3 of the engine upgrade found
    every {DONE} box unpressable otherwise), so it is up for all three endings."""
    g = Game()
    box = g.push(g.E.textBox(g.s(text), g.opts()))
    g.run()
    (op,) = opened(g)
    assert op["pages"] == [["ALL DONE"]], "the ending token never reaches the box"
    done = g.until(lambda m: m["type"] == "ui.update" and m["data"].get("done") is True)
    assert done["data"].get("waiting", False) is True, "the press signal, whatever the drawn arrow"
    assert bool(box.waitButton) is (not arrow) and box.arrowVisible(box) is arrow
    g.send("ui.advance", {"ui": 1})
    g.until(lambda m: m["type"] == "ui.close")
    assert g.tapped() == ["a"] and g.depth() == 1


def test_a_caller_may_hold_a_prompt_ending_for_its_own_press():
    g = Game()
    box = g.push(g.E.textBox(g.s("HELD{PROMPT}"), g.opts(waitButton=True)))
    g.run()
    done = g.until(lambda m: m["type"] == "ui.update" and m["data"].get("done") is True)
    assert done["data"]["waiting"] is True and box.waitButton is False, "{PROMPT} wins over the caller"


def test_stay_press_takes_one_press_with_no_arrow():
    """TextBox:update (:541-548): stay.press waits for A/B like stay.prompt but the engine draws no
    arrow (arrowVisible :434-440); the bridge's `waiting` (the press signal) is up until the press,
    then the box stays up without it."""
    g = Game()
    shown = []
    g.push(g.E.textBox(g.s("PRESS TO GO ON"), g.opts(stay={"press": True, "onShown": lambda box: shown.append(1)})))
    g.run()
    (op,) = opened(g)
    assert "stay" not in op, "its end takes a press"
    done = g.until(lambda m: m["type"] == "ui.update" and m["data"].get("done") is True)
    assert done["data"].get("waiting") is True, "the press signal is up though the engine draws no arrow"
    g.run(5)
    assert g.tapped() == [] and not shown
    g.send("ui.advance", {"ui": 1})
    g.run(3)
    assert g.tapped() == ["a"] and g.top().stayShown and shown == [1] and g.depth() == 2
    assert updates(g)[-1].get("waiting") is False, "the press was taken: the box stays up, nothing to press"


def test_auto_prompt_first_takes_one_press_then_closes_itself():
    """TextBox:update (:559-563): auto.promptFirst waits out one arrowed press, then the auto delay
    runs and the box pops itself (:603-621)."""
    g = Game()
    g.push(g.E.textBox(g.s("ONE PRESS"), g.opts(auto={"promptFirst": True, "delay": 4})))
    g.run()
    (op,) = opened(g)
    assert "auto" not in op, "its end takes a press"
    done = g.until(lambda m: m["type"] == "ui.update" and m["data"].get("done") is True)
    assert done["data"]["waiting"] is True, "the arrow for the one press"
    g.run(10)
    assert g.depth() == 2 and g.tapped() == []
    g.send("ui.advance", {"ui": 1})
    g.until(lambda m: m["type"] == "ui.close", limit=20)
    assert g.tapped() == ["a"] and g.depth() == 1
    assert [u.get("waiting") for u in updates(g)][-1] is False, "no arrow during the auto delay"


def test_a_sound_effect_holds_a_finished_box():
    """TextBox:sfxHeld (:415-420) over Sound.sfxBusy: a box with sfxWait neither shows its arrow
    (:429) nor takes the press (:636) until the effect is over; the wait then clears."""
    g = Game()
    g.E.sfxBusyFrames = 12
    box = g.push(g.E.textBox(g.s("DING!"), g.opts(sfxWait=True)))
    g.run()
    done = g.until(lambda m: m["type"] == "ui.update" and m["data"].get("done") is True)
    assert done["data"].get("waiting", False) is False and box.sfxWait is True
    g.send("ui.advance", {"ui": 1})
    g.run(5)
    assert g.tapped() == [] and g.depth() == 2, "held while the effect plays"
    arrow = g.until(lambda m: m["type"] == "ui.update" and m["data"].get("waiting") is True, limit=20)
    assert arrow["data"] == {"ui": 1, "waiting": True} and box.sfxWait is None
    g.until(lambda m: m["type"] == "ui.close", limit=20)
    assert g.tapped() == ["a"]


def test_a_sound_effect_holds_a_scroll_too():
    g = Game()
    g.E.sfxBusyFrames = 10
    box = g.push(g.E.textBox(g.s("ONE\fTWO"), g.opts(sfxWait=True)))
    g.run()
    g.send("ui.advance", {"ui": 1})
    g.run(12)
    assert box.waiting and box.preWait == 0 and g.tapped() == [], "the arrow would be up, the effect holds it"
    g.until(lambda m: m["type"] == "ui.update" and m["data"].get("page") == 1, limit=30)
    assert g.tapped() == ["a"]


def test_no_letter_delay_prints_a_line_a_frame():
    """TextBox:update (:687-689): noLetterDelay makes the typewriter's delay 0, a whole line a frame."""
    g = Game()
    slow = g.push(g.E.textBox(g.s("ABCDEFGHIJ\nKLMNOPQRST"), g.opts()))
    g.run(4)
    assert slow.charIndex == 4 and not slow.done
    g.E.uiPop()
    fast = g.push(g.E.textBox(g.s("ABCDEFGHIJ\nKLMNOPQRST"), g.opts(noLetterDelay=True)))
    g.run(2)
    assert fast.done and fast.lineIndex == 2, "two lines, two frames"


# ------------------------------------------------------------ YES/NO


def choice_flow(g, **opts):
    answers = []
    g.push(g.E.textBox(g.s("DO IT\nNOW?"), g.opts(choice=lambda yes: answers.append(yes), **opts)))
    g.until(lambda m: m["type"] == "ui.open" and m["data"]["kind"] == "choice")
    return answers


def test_choice_opens_over_its_text_with_the_question():
    g = Game()
    answers = choice_flow(g)
    text, choice = opened(g)
    assert text["kind"] == "text" and text["choice"] is True, "no arrow and no advance at its end"
    assert choice == {"ui": 2, "kind": "choice", "labels": ["YES", "NO"], "index": 0, "question": "DO IT NOW?"}
    assert not any(m["data"].get("waiting") for m in g.received("ui.update") if m["data"]["ui"] == 1)
    # B always answers NO, whatever the cursor
    g.send("ui.back", {"ui": 2})
    g.until(lambda m: m["type"] == "ui.close" and m["data"]["ui"] == 1)
    assert answers == [False] and g.tapped() == ["b"]
    assert [m["data"]["ui"] for m in g.received("ui.close")] == [2, 1]


def test_choice_yes_moves_the_cursor_then_taps_a():
    g = Game()
    answers = choice_flow(g, defaultNo=True)
    assert opened(g, "choice")[0]["index"] == 1
    g.send("ui.pick", {"ui": 2, "index": 0})
    g.run()
    box = g.top()
    assert g.tapped() == ["a"] and box.index == 1 and box.pending is True
    g.run()
    upd = [m["data"] for m in g.received("ui.update") if m["data"]["ui"] == 2]
    assert upd == [{"ui": 2, "index": 0}], "the cursor on YES shows during the answer's hold"
    g.until(lambda m: m["type"] == "ui.close" and m["data"]["ui"] == 1)
    assert answers == [True]


def test_choice_pick_no_is_b_and_bad_indexes_are_refused():
    g = Game()
    answers = choice_flow(g)
    bad = g.send("ui.pick", {"ui": 2, "index": 2})
    g.send("ui.pick", {"ui": 2, "index": 1})
    g.until(lambda m: m["type"] == "ui.close" and m["data"]["ui"] == 1)
    assert answers == [False] and g.tapped() == ["b"] and errors(g) == [("bad_message", bad)]


def test_no_tap_while_the_choice_holds_its_answer():
    g = Game()
    answers = choice_flow(g)
    g.send("ui.pick", {"ui": 2, "index": 1})
    late = g.send("ui.pick", {"ui": 2, "index": 0})
    g.run(10)
    assert g.tapped() == ["b"], "the second answer waits behind the 15-frame hold"
    g.until(lambda m: m["type"] == "ui.close" and m["data"]["ui"] == 1)
    g.run()
    assert g.tapped() == ["b"] and answers == [False]
    assert errors(g) == [("ui.stale", late)], "then it dies with the box"


# ------------------------------------------------------------ the start menu, menus and lists


def open_start_menu(g):
    seq = g.send("act.button", {"button": "start", "how": "tap"})
    result = g.until(lambda m: m["type"] == "act.result" and m.get("re") == seq)
    assert result["data"] == {"ok": True}
    g.until(lambda m: m["type"] == "ui.open" and m["data"].get("screen") == "StartMenu")
    return opened(g, "menu")[-1]


def test_start_menu_opens_as_a_menu_and_back_closes_it():
    g = Game()
    menu = open_start_menu(g)
    assert menu == {"ui": 1, "kind": "menu", "screen": "StartMenu", "items": [
        {"label": "ITEM"}, {"label": "TESTER"}, {"label": "QUIT"}], "index": 0, "top": 0, "rows": 3, "wrap": True}
    assert g.tapped() == ["start"]
    g.send("ui.back", {"ui": 1})
    close = g.until(lambda m: m["type"] == "ui.close")
    assert close["data"] == {"ui": 1} and g.tapped() == ["start", "b"] and g.depth() == 1


def test_start_waits_for_a_step_to_land():
    g = Game()
    g.send("move.step", {"dir": "down", "n": 1})
    g.run(2)
    assert g.E.ow.player.moving
    g.send("act.button", {"button": "start", "how": "tap"})
    g.run()
    assert g.tapped() == []
    result = g.until(lambda m: m["type"] == "act.result", limit=40)
    assert result["data"] == {"ok": True} and g.tapped() == ["start"]
    assert not g.E.ow.player.moving


def test_menu_pick_moves_the_cursor_and_confirms():
    g = Game()
    open_start_menu(g)
    g.send("ui.pick", {"ui": 1, "index": 2})  # QUIT -> its YES/NO
    g.until(lambda m: m["type"] == "ui.open" and m["data"]["kind"] == "choice")
    assert [m["data"]["ui"] for m in g.received("ui.close")] == [1], "QUIT closes the start menu"
    assert g.tapped() == ["start", "a"]
    g.send("ui.back", {"ui": 3})
    g.until(lambda m: m["type"] == "ui.close" and m["data"]["ui"] == 2)
    assert list(g.E.answers.values()) == [False]


def test_keep_open_rows_stack_a_list_over_the_menu():
    g = Game()
    open_start_menu(g)
    g.send("ui.pick", {"ui": 1, "index": 0})  # ITEM: the bag opens over the kept-open menu
    g.until(lambda m: m["type"] == "ui.open" and m["data"]["kind"] == "list")
    (bag,) = opened(g, "list")
    assert bag == {"ui": 2, "kind": "list", "screen": "BagMenu", "title": "ITEMS",
                   "items": [{"label": "TEST BALL", "right": "×3"}, {"label": "CANCEL"}],
                   "index": 0, "top": 0, "rows": 4, "wrap": False}
    assert not g.received("ui.close")
    # rows changing while the list is open come as an update
    rows = g.top()[b"items"]
    rows[1].count = 2
    upd = g.until(lambda m: m["type"] == "ui.update" and "items" in m["data"])
    assert upd["data"]["items"][0] == {"label": "TEST BALL", "right": "×2"}
    g.send("ui.back", {"ui": 2})  # the bag's B closes both
    g.until(lambda m: m["type"] == "ui.close" and m["data"]["ui"] == 1)
    assert [m["data"]["ui"] for m in g.received("ui.close")] == [2, 1] and g.depth() == 1


def test_list_pick_scrolls_the_window_and_calls_choose():
    g = Game()
    chosen = []
    rows = [{"label": f"ROW {i}"} for i in range(12)]
    g.push(g.E.listMenu(g.s("A LIST"), g.items(rows), g.opts(
        rows=4, footer="PICK ONE", onChoose=lambda item, lst: chosen.append(item.label))))
    g.run()
    (lst,) = opened(g, "list")
    assert (lst["rows"], lst["footer"], lst["title"], len(lst["items"])) == (4, "PICK ONE", "A LIST", 12)
    g.send("ui.pick", {"ui": 1, "index": 9})
    g.run(2)
    assert chosen == [b"ROW 9"] and g.top().scroll == 6
    upd = g.received("ui.update")[-1]["data"]
    assert (upd["index"], upd["top"]) == (9, 6)
    bad = g.send("ui.pick", {"ui": 1, "index": 12})
    g.run(2)
    assert ("bad_message", bad) in errors(g) and chosen == [b"ROW 9"]


@pytest.mark.parametrize("make, wrap", [
    (lambda g: g.E.menuState(g.items([{"label": "ONE"}, {"label": "TWO"}]), g.opts()), True),
    (lambda g: g.E.menuState(g.items([{"label": "ONE"}, {"label": "TWO"}]), g.opts(noWrap=True)), False),
    (lambda g: g.E.listMenu(b"A LIST", g.items([{"label": "ONE"}]), g.opts()), False),
    (lambda g: g.E.listMenu(b"A LIST", g.items([{"label": "ONE"}]), g.opts(wrap=True)), True),
    (lambda g: g.E.pokedexMenu(g.items([{"label": "001 ONE"}])), False),
    (lambda g: g.E.prizeCounter(g.b.to_lua([{"name": "ONE", "cost": 1}])), False),
])
def test_menus_and_lists_say_whether_the_cursor_wraps(make, wrap):
    g = Game()
    g.push(make(g))
    g.run()
    assert opened(g)[0]["wrap"] is wrap


def test_indexes_are_zero_based():
    g = Game()
    m = g.push(g.E.menuState(g.items([{"label": "ONE"}, {"label": "TWO"}, {"label": "THREE"}]), g.opts()))
    g.run()
    assert opened(g)[0]["index"] == 0, "the engine's index 1, the first item"
    m.index = 3
    g.run()
    assert g.received("ui.update")[-1]["data"] == {"ui": 1, "index": 2}


def test_menu_title_footer_and_markup():
    g = Game()
    g.push(g.E.menuState(g.items([{"label": "#DEX"}, {"label": "<PK><MN>"}]),
                         g.opts(title="CHOOSE\nONE", footer="THE #MON SHOP")))
    g.run()
    (menu,) = opened(g, "menu")
    assert menu["items"] == [{"label": POKE + "DEX"}, {"label": "<PK><MN>"}]
    assert menu["title"] == "CHOOSE ONE" and menu["footer"] == "THE " + POKE + "MON SHOP"
    assert menu["rows"] == 2 and "screen" not in menu


def test_frozen_menu_answers_wait():
    g = Game()
    m = g.push(g.E.menuState(g.items([{"label": "ONE"}, {"label": "TWO"}]), g.opts()))
    m.frozen = True
    g.run()
    g.send("ui.pick", {"ui": 1, "index": 1})
    g.run(5)
    assert g.tapped() == []
    m.frozen = False
    g.run(2)
    assert g.tapped() == ["a"]


def test_answers_wait_for_their_ui_to_be_on_top_then_time_out():
    g = Game()
    g.push(g.E.menuState(g.items([{"label": "ONE"}, {"label": "TWO"}]), g.opts()))
    g.run()
    g.push(g.E.textBox(g.s("OVER IT"), g.opts(stay=True)))
    g.run()
    seq = g.send("ui.back", {"ui": 1})
    g.run(10)
    assert g.tapped() == [] and errors(g) == []
    g.run(200)
    assert errors(g) == [("ui.timeout", seq)] and g.tapped() == []


# ------------------------------------------------------------ quantity


def test_quantity_sets_the_number_and_confirms():
    g = Game()
    done = []
    g.push(g.E.quantityBox(g.opts(max=15, unitPrice=200, onDone=lambda q: done.append(q))))
    g.run()
    (q,) = opened(g, "quantity")
    assert q == {"ui": 1, "kind": "quantity", "qty": 1, "max": 15, "unit": 200}
    bad = g.send("ui.qty", {"ui": 1, "qty": 16})
    g.run(2)
    assert errors(g) == [("bad_message", bad)] and done == []
    g.send("ui.qty", {"ui": 1, "qty": 12})
    g.until(lambda m: m["type"] == "ui.close")
    assert done == [12] and g.tapped() == ["a"]


def test_quantity_back_cancels_and_cursor_moves_are_updates():
    g = Game()
    done = []
    box = g.push(g.E.quantityBox(g.opts(max=5, onDone=lambda q: done.append(q))))
    g.run()
    box.qty = 4  # e.g. a player pressing up in Gen1Recomp's window
    upd = g.until(lambda m: m["type"] == "ui.update")
    assert upd["data"] == {"ui": 1, "qty": 4}
    g.send("ui.back", {"ui": 1})
    g.until(lambda m: m["type"] == "ui.close")
    assert done == [None]


# ------------------------------------------------------------ naming


def naming(g, presets=None, **kw):
    names = []
    opts = g.opts(title="YOUR NAME?", maxLen=7, onDone=lambda name, typed: names.append((name, typed)), **kw)
    if presets:
        opts.presets = g.b.to_lua(presets)
    g.push(g.E.namingScreen(opts))
    g.until(lambda m: m["type"] == "ui.open")
    return names


GRID = [[["A", "B", "C", "D"], ["<PK>", "<MN>", "é", "×"], ["-", "?", " "]],
        [["a", "b", "c", "d"], ["♂", "♀", ".", ","], ["!", "/", " "]]]


def test_naming_opens_with_presets_and_the_grid():
    g = Game()
    naming(g, presets=["TESTER", "ALEX"], default="")
    (op,) = ui_msgs(g.log)
    assert op["type"] == "ui.open"
    assert op["data"] == {"ui": 1, "kind": "naming", "title": "YOUR NAME?", "max": 7,
                          "presets": ["TESTER", "ALEX"], "value": "", "grid": GRID}
    assert g.depth() == 3, "the preset menu sits on the naming screen and is not a UI of its own"


def test_naming_a_preset_picks_it_from_the_menu():
    g = Game()
    names = naming(g, presets=["TESTER", "ALEX"])
    g.send("ui.name", {"ui": 1, "glyphs": list("ALEX")})  # a preset needn't be typeable on the grid
    g.until(lambda m: m["type"] == "ui.close")
    assert names == [(b"ALEX", False)] and g.tapped() == ["a"] and g.depth() == 1


def test_naming_a_typed_name_that_equals_a_preset_still_picks_the_preset():
    g = Game()
    names = naming(g, presets=["CAB", "DAB"])
    g.send("ui.name", {"ui": 1, "glyphs": ["D", "A", "B"]})
    g.until(lambda m: m["type"] == "ui.close")
    assert names == [(b"DAB", False)] and g.tapped() == ["a"]


def test_naming_a_new_name_picks_new_name_then_confirms_the_glyphs():
    g = Game()
    names = naming(g, presets=["TESTER", "ALEX"])
    g.send("ui.name", {"ui": 1, "glyphs": ["<PK>", "A", "é", " ", "♂"]})
    g.until(lambda m: m["type"] == "ui.close", limit=20)
    assert names == [(("<PK>Aé ♂").encode("utf-8"), True)]
    assert g.tapped() == ["a", "start"], "NEW NAME, then START confirms the grid"
    assert not g.received("ui.update")


def test_naming_without_presets_confirms_at_once():
    g = Game()
    names = naming(g)
    assert opened(g)[0]["presets"] == []
    g.send("ui.name", {"ui": 1, "glyphs": ["D", "C", "B"]})
    g.until(lambda m: m["type"] == "ui.close")
    assert names == [(b"DCB", True)] and g.tapped() == ["start"]


def test_naming_an_empty_name_is_the_screens_own_empty_confirm():
    """Item 9: no glyphs on a screen without presets: START on the empty grid, so the engine's confirm decides: no
    nickname (""), or the screen's default (the Name Rater's old nickname)."""
    g = Game()
    names = naming(g, mon={"species": "TESTMON"})
    assert opened(g)[0]["value"] == ""
    g.send("ui.name", {"ui": 1, "glyphs": []})
    g.until(lambda m: m["type"] == "ui.close")
    assert names == [(b"", True)] and g.tapped() == ["start"] and errors(g) == []
    g = Game()
    names = naming(g, default="OLDNICK")
    g.send("ui.name", {"ui": 1, "glyphs": []})
    g.until(lambda m: m["type"] == "ui.close")
    assert names == [(b"OLDNICK", True)]


def test_naming_refuses_an_empty_name_where_the_screen_has_presets():
    # the player's and rival's names: the engine would re-prompt, so the empty answer is refused before it
    g = Game()
    names = naming(g, presets=["TESTER", "ALEX"])
    seq = g.send("ui.name", {"ui": 1, "glyphs": []})
    g.run(3)
    assert errors(g) == [("bad_message", seq)] and names == [] and g.tapped() == []


@pytest.mark.parametrize("glyphs", [list("TOOLONGX"), ["A", "1"], ["AB"], ["lower case"], ["ED"]])
def test_naming_refuses_bad_glyphs(glyphs):
    g = Game()
    names = naming(g)
    seq = g.send("ui.name", {"ui": 1, "glyphs": glyphs})
    g.run(3)
    assert errors(g) == [("bad_message", seq)] and names == [] and g.tapped() == []


def test_naming_mon_is_its_dex_number():
    g = Game()
    naming(g, mon={"species": "TESTMON"})
    assert opened(g)[0]["mon"] == 7


# ------------------------------------------------------------ adapted and mirror screens


def test_pokedex_list_adapter():
    g = Game()
    chosen = []
    items = [{"label": f"{i:03d} TESTMON{i}"} for i in range(1, 11)]
    g.push(g.E.pokedexMenu(g.items(items), g.opts(footer="SEEN 10 OWN 2",
                                                   onChoose=lambda item, m: chosen.append(item.label))))
    g.run()
    (dex,) = opened(g, "list")
    assert (dex["screen"], dex["rows"], dex["footer"], len(dex["items"])) == ("PokedexMenu", 7, "SEEN 10 OWN 2", 10)
    g.send("ui.pick", {"ui": 1, "index": 8})
    g.run(2)
    assert chosen == [b"009 TESTMON9"] and g.top().scroll == 2


def test_prize_counter_adapter_has_its_no_thanks_row():
    g = Game()
    picks = []
    prizes = g.b.to_lua([{"name": "TEST DOLL", "cost": 120}, {"name": "TEST TOY", "cost": 300},
                         {"name": "TEST TM", "cost": 4000}])
    g.push(g.E.prizeCounter(prizes, g.opts(onPick=lambda prize, i: picks.append(i))))
    g.run()
    (p,) = opened(g, "list")
    assert p["screen"] == "PrizeCounter" and p["rows"] == 4
    assert p["items"] == [{"label": "TEST DOLL", "right": "120"}, {"label": "TEST TOY", "right": "300"},
                          {"label": "TEST TM", "right": "4000"}, {"label": "NO THANKS"}]
    g.send("ui.pick", {"ui": 1, "index": 1})
    g.run(2)
    assert picks == [2]
    g.send("ui.pick", {"ui": 1, "index": 3})  # NO THANKS cancels
    g.until(lambda m: m["type"] == "ui.close")


def test_town_map_fly_list_and_its_viewer():
    g = Game()
    flown = []
    towns = [{"name": "TEST TOWN", "map": "TEST_TOWN"}, {"name": "TEST CITY", "map": "TEST_CITY"}]
    g.push(g.E.townMap(g.opts(fly=True, towns=towns, onFly=lambda m: flown.append(m))))
    g.run()
    (fly,) = opened(g)
    assert fly == {"ui": 1, "kind": "list", "screen": "TownMap", "items": [{"label": "TEST TOWN"}, {"label": "TEST CITY"}],
                   "index": 0, "top": 0, "rows": 2, "wrap": True}
    g.send("ui.pick", {"ui": 1, "index": 1})
    g.until(lambda m: m["type"] == "ui.close")
    assert flown == [b"TEST_CITY"]
    g.push(g.E.townMap(g.opts(fly=False)))
    g.run()
    assert opened(g)[-1] == {"ui": 2, "kind": "mirror", "screen": "TownMap"}


@pytest.mark.parametrize("make, screen", [
    (lambda g: g.E.screenState(b"TrainerCard"), "TrainerCard"),
    (lambda g: g.E.screenState(b"PartyMenu", g.opts(isMenu=True)), "PartyMenu"),
    (lambda g: g.E.listMenu(b"DEMO", g.items([{"label": "ROW"}]), g.opts(script=True, screenId="DemoList")),
     "DemoList"),
])
def test_other_screens_are_mirrors(make, screen):
    g = Game()
    g.push(make(g))
    g.run()
    assert opened(g) == [{"ui": 1, "kind": "mirror", "screen": screen}]
    seq = g.send("ui.pick", {"ui": 1, "index": 0})
    g.run()
    assert errors(g) == [("ui.wrong_kind", seq)]


def test_act_button_drives_a_mirror_screen():
    g = Game()
    card = g.push(g.E.screenState(b"TrainerCard"))
    g.run()
    s1 = g.send("act.button", {"button": "down", "how": "tap"})
    g.run()
    s2 = g.send("act.button", {"button": "select", "how": "down"})
    g.run(3)
    held = g.E.game.input.state.select
    s3 = g.send("act.button", {"button": "select", "how": "up"})
    g.run()
    s4 = g.send("act.button", {"button": "b", "how": "tap"})
    g.until(lambda m: m["type"] == "ui.close")
    results = [(m.get("re"), m["data"]) for m in g.received("act.result")]
    assert results == [(s1, {"ok": True}), (s2, {"ok": True}), (s3, {"ok": True}), (s4, {"ok": True})]
    assert held is True and not g.E.game.input.state.select
    assert list(card.presses.values()) == [b"down", b"select"]
    assert g.taps() == [("tap", "down"), ("press", "select"), ("release", "select"), ("tap", "b")]


def test_mirror_holds_are_released_when_the_screen_closes_and_the_chord_is_refused():
    g = Game()
    g.push(g.E.screenState(b"SlotMachine", g.opts(sticky=True)))
    g.run()
    for btn in ("a", "b", "start"):
        g.send("act.button", {"button": btn, "how": "down"})
        g.run()
    seq = g.send("act.button", {"button": "select", "how": "down"})
    g.run()
    assert [m["data"] for m in g.received("act.result")][-1] == {"ok": False, "reason": "chord"}
    assert g.received("act.result")[-1]["re"] == seq
    g.E.uiPop()
    g.run()
    assert [op for op, _ in g.taps()].count("release") == 3


def test_act_button_outside_a_mirror_is_refused():
    g = Game()
    s1 = g.send("act.button", {"button": "a", "how": "tap"})
    g.run()
    g.push(g.E.textBox(g.s("TEXT"), g.opts()))
    g.run()
    s2 = g.send("act.button", {"button": "down", "how": "tap"})
    g.run()
    s3 = g.send("act.button", {"button": "start", "how": "tap"})
    g.run()
    s4 = g.send("act.button", {"button": "jump", "how": "tap"})
    g.run()
    results = [(m.get("re"), m["data"]) for m in g.received("act.result")]
    assert results == [(s1, {"ok": False, "reason": "not_mirror"}), (s2, {"ok": False, "reason": "not_mirror"}),
                       (s3, {"ok": False, "reason": "not_mirror"})]
    assert errors(g) == [("bad_message", s4)] and g.tapped() == []


def test_start_on_the_start_menu_closes_it():
    g = Game()
    open_start_menu(g)
    seq = g.send("act.button", {"button": "start", "how": "tap"})
    g.until(lambda m: m["type"] == "ui.close")
    assert [m["data"] for m in g.received("act.result") if m.get("re") == seq] == [{"ok": True}]
    assert g.tapped() == ["start", "start"] and g.depth() == 1


# ------------------------------------------------------------ the stack


def test_busy_states_open_nothing_and_keep_what_is_below():
    g = Game()
    g.push(g.E.menuState(g.items([{"label": "ONE"}]), g.opts()))
    g.run()
    g.push(g.b.eval("{}"))  # a fade or a panel: no input
    g.run(3)
    assert len(opened(g)) == 1 and not g.received("ui.close")
    g.E.uiPop()
    g.E.uiPop()
    g.run()
    assert [m["data"]["ui"] for m in g.received("ui.close")] == [1]


def test_the_mod_manager_and_everything_above_it_are_ignored():
    g = Game()
    g.push(g.E.menuState(g.items([{"label": "ONE"}]), g.opts()))
    g.run()
    g.push(g.E.screenState(b"ManagerState"))
    g.push(g.E.menuState(g.items([{"label": "MOD OPTION"}]), g.opts()))
    g.run(3)
    assert len(opened(g)) == 1 and not g.received("ui.close")


def test_a_resync_sends_the_open_uis_again_with_their_ids():
    g = Game()
    answers = choice_flow(g)
    seq = g.send("resync")
    snap = g.until(lambda m: m["type"] == "snapshot" and m.get("re") == seq)
    after = g.log[g.log.index(snap):]
    assert [(m["type"], m["data"].get("ui"), m["data"].get("kind")) for m in after] == [
        ("snapshot", None, None), ("npc.set", None, None), ("ui.open", 1, "text"), ("ui.open", 2, "choice"),
        ("ui.update", 1, None), ("bag.sync", None, None), ("game.status", None, None)]
    assert after[-3]["data"] == {"ui": 1, "line": 1, "done": True}, "where the text box is now"
    g.send("ui.back", {"ui": 2})
    g.until(lambda m: m["type"] == "ui.close" and m["data"]["ui"] == 1)
    assert answers == [False]


def test_a_new_connection_gets_the_open_uis_and_old_answers_die():
    g = Game()
    choice_flow(g)
    g.send("ui.pick", {"ui": 2, "index": 0})
    old = g.peer
    g.connect()  # replaces the old connection before the step that would apply the answer
    assert old.closed()
    snap = max(i for i, m in enumerate(g.log) if m["type"] == "snapshot")
    assert [(m["type"], m["data"].get("ui")) for m in g.log[snap:] if m["type"].startswith("ui.")] == \
        [("ui.open", 1), ("ui.open", 2), ("ui.update", 1)]
    g.run(30)
    assert g.tapped() == [] and g.depth() == 3


def test_status_counts_ui_work():
    g = Game()
    open_start_menu(g)
    g.send("ui.back", {"ui": 1})
    g.until(lambda m: m["type"] == "ui.close")
    st = g.status()
    assert (st["uiOpened"], st["uiClosed"], st["uiOpen"]) == (1, 1, 0)
    assert st["uiactBacks"] == 1 and st["uiactButtons"] == 1 and st["screenEvents"] >= 2
    # the words: mode, topState, unionRoom ("on"/"off"); the list: engineMissing (UP1)
    assert all(isinstance(v, (int, bool)) for k, v in st.items()
               if k not in ("mode", "topState", "unionRoom", "engineMissing"))


def test_logs_never_carry_ui_text():
    g = Game()
    g.push(g.E.textBox(g.s("SECRET WORDS\fMORE SECRETS"), g.opts()))
    g.run()
    g.send("ui.name", {"ui": 1, "glyphs": ["SECRET"]})
    g.run(5)
    assert all("SECRET" not in line for line in g.rig.logs())
    assert all("SECRET" not in json.dumps(m) for m in g.log if m["type"] in ("log", "error"))
