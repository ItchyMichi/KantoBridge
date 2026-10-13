"""The opening (M5 [L]): Oak's speech as the hidden game builds it (src/ui/OakSpeech.lua, P2 section
7) -- the OakSpeech screen as a mirror UI, its text boxes (the held one with stay.prompt), the picture
slides (busy: no UI), the player and rival naming screens with the intro's preset menu, the
confirmations -- through the M4 observer path, which needs no hook (hot-reload safe): ui.open{naming}
with presets and the grid, answered by ui.name (a typed name picks NEW NAME first, then the glyphs
and START; a preset's name picks the preset). debug.status reports the names' lengths and short
hashes only. Every message is schema-checked (rig.Peer). All text and names are made up."""

import hashlib

import pytest

from battlesim import BattleGame

pytestmark = pytest.mark.lua

# A small OakSpeech stand-in on the fake engine, with the real speech's shape: say steps push
# text boxes; ask_* steps push a held box (stay.prompt) whose onShown runs the next step; a name
# step slides the picture (a state with no input: PicSlide) and then pushes the NamingScreen with
# presets and the intro box; onDone writes save.player.name / rival, closes the held box, and
# goes on (OakSpeech.lua:431-510, :642-647).
SPEECH = r"""
return function(E)
  local speech = { step = 0, answers = {} }
  local steps
  local function advance()
    speech.step = speech.step + 1
    local s = steps[speech.step]
    if s then s() else E.uiPop() speech.finished = true end
  end
  -- TextBox.new substitutes {PLAYER}/{RIVAL} (fake_script.lua, TextBox.lua:172)
  local function say(text) E.uiPush(E.modules["src.render.TextBox"].new(E.game, text, advance)) end
  local function hold(text)
    speech.holdBox = E.textBox(text, { stay = { prompt = true, onShown = function() advance() end } })
    E.uiPush(speech.holdBox)
  end
  local function slide(onDone)
    local s = { t = 0 }
    function s.update(self)
      self.t = self.t + 1
      if self.t >= 18 then E.uiPop() onDone() end
    end
    E.uiPush(s)
  end
  local function name(who, title, presets)
    slide(function()
      E.uiPush(E.namingScreen({ title = title, presets = presets, maxLen = 7, onDone = function(n, custom)
        if who == "rival" then E.save.player.rival = n else E.save.player.name = n end
        speech.answers[who] = custom and "typed" or "preset"
        if E.stack:top() == speech.holdBox then E.uiPop() end
        advance()
      end }))
    end)
  end
  steps = {
    function() say("HELLO THERE!\fWELCOME TO THE TEST!") end,
    function() hold("FIRST, WHAT IS\nYOUR NAME?") end,
    function() name("player", "YOUR NAME?", { "TESTA", "TESTB", "TESTC" }) end,
    function() say("RIGHT! SO YOUR\nNAME IS {PLAYER}!") end,
    function() hold("AND HIS NAME?") end,
    function() name("rival", "RIVAL'S NAME?", { "RIVA", "RIVB" }) end,
    function() say("YOUR LEGEND\nUNFOLDS!") end,
  }
  -- the speech's own state: its first picture reveals before the first box (OakSpeech:update,
  -- :698-707), so it is the top for a few frames
  local state = E.screenState("OakSpeech", { sticky = true })
  local base = state.update
  state.reveal = 12
  function state.update(self)
    if self.reveal then
      self.reveal = self.reveal - 1
      if self.reveal <= 0 then self.reveal = nil advance() end
      return
    end
    base(self)
  end
  E.uiPush(state)
  return speech
end
"""


def short_hash(name: str) -> int:
    return int(hashlib.sha256(name.encode("utf-8")).hexdigest()[:7], 16)


def naming_uis(g):
    return [m["data"] for m in g.received("ui.open") if m["data"]["kind"] == "naming"]


def answer_names(g, answers, limit=1200):
    """Reads the speech like a player: A at each arrow, a name at each naming screen."""
    pending = list(answers)
    named = set()
    advanced = set()
    for _ in range(limit):
        g.run()
        uis = g.open_uis()
        if not uis:
            continue
        top = max(uis)
        ui = uis[top]
        if ui["kind"] == "naming" and top not in named:
            named.add(top)
            g.send("ui.name", {"ui": top, "glyphs": pending.pop(0)})
        elif ui["kind"] == "text" and ui.get("waiting"):
            key = (top, ui.get("page"), ui.get("line"), ui.get("done"))
            if key not in advanced:
                advanced.add(key)
                g.send("ui.advance", {"ui": top})
        if not pending and g.speech.finished:
            return
    raise AssertionError("the speech never finished")


@pytest.fixture()
def opening():
    g = BattleGame()
    g.speech = g.b.execute(SPEECH)(g.E)
    return g


def test_the_speech_and_both_names_from_minecraft(opening):
    g = opening
    answer_names(g, [["A", "B", "C"], ["R", "I", "V", "B"]])
    opened = [m["data"] for m in g.received("ui.open")]
    assert opened[0]["kind"] == "mirror" and opened[0]["screen"] == "OakSpeech"
    namings = naming_uis(g)
    assert [(n["title"], n["presets"], n["max"]) for n in namings] == [
        ("YOUR NAME?", ["TESTA", "TESTB", "TESTC"], 7), ("RIVAL'S NAME?", ["RIVA", "RIVB"], 7)]
    assert all(n["grid"] and n["grid"][0][0][0] == "A" for n in namings), "the grid without ED"
    held = [d for d in opened if d["kind"] == "text" and d["pages"][0][0] == "FIRST, WHAT IS"]
    assert held and "stay" not in held[0], "a held box takes its one press"
    # a typed name: NEW NAME, then the glyphs and START; a preset's name: the preset row
    assert g.E.save.player.name == b"ABC" and g.E.save.player.rival == b"RIVB"
    assert g.speech.answers.player == b"typed" and g.speech.answers.rival == b"preset"
    confirm = [d for d in opened if d["kind"] == "text" and d["pages"][0][0] == "RIGHT! SO YOUR"]
    assert confirm and confirm[0]["pages"][0][1] == "NAME IS ABC!", "the engine substituted {PLAYER}"
    st = g.status()
    assert (st["playerNameLen"], st["playerNameHash"]) == (3, short_hash("ABC"))
    assert (st["rivalNameLen"], st["rivalNameHash"]) == (4, short_hash("RIVB"))
    assert not g.received("error")
    closes = len(g.received("ui.close"))
    assert closes == len(opened), "every UI the speech opened closed again"


def test_the_picture_slides_open_no_ui_and_report_busy(opening):
    g = opening
    answer_names(g, [["A", "B", "C"], ["R", "I", "V", "B"]])
    # between the held box's press and the naming screen: the slide (no input) -> busy, no UI
    seq = [(m["type"], m["data"].get("kind") or m["data"].get("mode")) for m in g.log
           if m["type"] in ("ui.open", "player.state")]
    first_naming = seq.index(("ui.open", "naming"))
    before = seq[:first_naming]
    assert ("player.state", "busy") in before[-3:], "the slide reports busy just before the naming"
    assert g.speech.finished
