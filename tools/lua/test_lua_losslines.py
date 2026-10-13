"""M8 (wave 3): the loss line an engageTrainer caller hands its battle itself (endBattleText, with
endBattleSound, endBattleIsReward and endBattleSoundPage, OC:4201-4260), shown after a Cobblemon win
the way the battle screen shows it. lib/kb_engine.lua's startTrainerBattle runs the encounter's own
start on a win (the continuation captured when the battle was deferred) with BattleState.newTrainer
stood in for and pushBattle diverted, then the encounter's own onFinish.

Every caller of the engine that passes its own line has a made-up stand-in of the same shape here
(fake_script.lua's engageTrainer takes the engine's full argument list):
  * the Game Corner rocket (data/scripts/story3.lua:597-643): talked to, a headerless trainer, his
    own line, an after-battle text and his exit from the callback;
  * Silph Co Giovanni (data/scripts/story.lua:870-901): a step trigger, his text first, a walk-up,
    then the battle with no battle text (skipBattleText), his line also his victories dialogue;
  * the Karate Master (data/scripts/story4.lua:217-258): the gate cell beside him and his talk, his
    line not the reward dialogue (endBattleIsReward false), no callback from the gate;
  * the Nugget Bridge recruiter (data/scripts/story4.lua:660-680): his pitch, then the battle with no
    battle text, his line, an after-battle text;
  * the gym leaders (data/scripts/gyms.lua:94-131): the badge lines as the loss line with the badge
    jingle on a page (endBattleSound, endBattleSoundPage), the victories dialogue not shown twice.
Plus: an ordinary trainer's header line through the same start; lose and aborted keep M5's path; a
hot reload while the battle is held; no client (native); the start's shadows are put back; no
battle is ever built for a Cobblemon win. Every message the peer receives is schema-checked
(rig.Peer, strict). All data is made up."""

import copy

import pytest

from contentsim import CONTENT_DATA, CONTENT_MAPS, ContentGame

pytestmark = pytest.mark.lua

ROCKET, LEADER, RECRUITER, PLAIN = "LOSS_HALL_obj_1", "LOSS_HALL_obj_2", "LOSS_HALL_obj_3", "LOSS_HALL_obj_4"
BOSS, MASTER = "LOSS_TOWER_obj_1", "LOSS_DOJO_obj_1"

HALL = {
    "rows": [
        "##########",  # 0
        "#........#",  # 1  the rocket (2, 1), the leader (4, 1), the recruiter (6, 1), a plain trainer (8, 1)
        "#........#",  # 2  the player stands below one of them, facing up
        "#........#",  # 3
        "##########",
    ],
    "npcs": [
        {"id": ROCKET, "name": "HALL_ROCKET", "x": 2, "y": 1, "sprite": "SPRITE_TEST_ROCKET",
         "trainerClass": "OPP_ROCKET", "trainerParty": 1},
        {"id": LEADER, "name": "HALL_LEADER", "x": 4, "y": 1, "sprite": "SPRITE_TEST_LEADER",
         "trainerClass": "OPP_BROCK", "trainerParty": 1},
        {"id": RECRUITER, "name": "HALL_RECRUITER", "x": 6, "y": 1, "sprite": "SPRITE_TEST_COOL",
         "trainerClass": "OPP_COOLTRAINER", "trainerParty": 1},
        {"id": PLAIN, "name": "HALL_PLAIN", "x": 8, "y": 1, "sprite": "SPRITE_TEST_TRAINER",
         "trainerClass": "OPP_TEST", "trainerParty": 1},
    ],
}
# the boss at (5, 1) facing down; his trigger is (5, 4), stepped onto from (5, 5)
TOWER = {
    "rows": ["###########", "#.........#", "#.........#", "#.........#", "#.........#", "#.........#",
             "###########"],
    "npcs": [{"id": BOSS, "name": "TOWER_BOSS", "x": 5, "y": 1, "sprite": "SPRITE_TEST_BOSS", "facing": "down",
              "trainerClass": "OPP_BOSS", "trainerParty": 2}],
}
# the master at (4, 1) facing down; his gate is the cell left of him, (3, 1), stepped onto from (3, 2)
DOJO = {
    "rows": ["########", "#......#", "#......#", "#......#", "########"],
    "npcs": [{"id": MASTER, "name": "DOJO_MASTER", "x": 4, "y": 1, "sprite": "SPRITE_TEST_MASTER",
              "facing": "down", "trainerClass": "OPP_BLACKBELT", "trainerParty": 1}],
}
MAPS = dict(CONTENT_MAPS, LOSS_HALL=HALL, LOSS_TOWER=TOWER, LOSS_DOJO=DOJO)

DATA = copy.deepcopy(CONTENT_DATA)
DATA["trainers"]["OPP_ROCKET"] = {"name": "ROCKET", "baseMoney": 30, "parties": [[{"species": "BIRDMON", "level": 20}]]}
DATA["trainers"]["OPP_COOLTRAINER"] = {"name": "COOLTRAINER", "baseMoney": 35,
                                       "parties": [[{"species": "STARTMON_C", "level": 18}]]}
DATA["trainers"]["OPP_BLACKBELT"] = {"name": "MASTER", "baseMoney": 25,
                                     "parties": [[{"species": "STARTMON_B", "level": 37}]]}
DATA["trainers"]["OPP_BOSS"]["parties"].append([{"species": "SLEEPMON", "level": 37}])
DATA["text"]["_PlainWonText"] = "WELL FOUGHT!"
DATA["text"]["_MasterHeaderWonText"] = "THE HEADER LINE"
DATA["trainer_headers"]["LOSS_HALL"] = {4: {"event": "EVENT_BEAT_TEST_PLAIN", "won": "_PlainWonText",
                                            "battleText": "EN GARDE!"}}
DATA["trainer_headers"]["LOSS_DOJO"] = {1: {"won": "_MasterHeaderWonText", "battleText": "HWA! COME ON!"}}

# each caller's shape, as a function of E returning the talk function (npc, done) or the map script
ROCKET_TALK = """function(E)
  return function(npc, done)
    E.engageTrainer(npc, function()
      if not E.trainerDefeated(npc) then return done() end
      E.uiPush(E.textBox("I BETTER TELL BOSS!", { onDone = function()
        E.rocketLeft = true
        done()
      end }))
    end, "DANG!")
  end
end"""

LEADER_TALK = """function(E, page)
  return function(npc, done)
    E.engageTrainer(npc, done, "I LOST!\\fHERE IS\\nTHE BADGE!\\fUSE IT WELL.", nil, "Level_Up", nil, page)
  end
end"""

RECRUITER_TALK = """function(E)
  return function(npc, done)
    E.uiPush(E.textBox("JOIN US!", { onDone = function()
      E.engageTrainer(npc, function()
        if not E.trainerDefeated(npc) then return done() end
        E.uiPush(E.textBox("YOU COULD BE A TOP LEADER!", { onDone = done }))
      end, "DEFEATED!", true)
    end }))
  end
end"""

BOSS_TRIGGER = """function(E)
  return { onStep = function(_, w, x, y)
    if E.save.flags.EVENT_BEAT_TEST_BOSS2 or x ~= 5 or y ~= 4 then return false end
    local boss
    for _, n in ipairs(w.npcs) do if n.def.name == "TOWER_BOSS" then boss = n end end
    if not boss or E.trainerDefeated(boss) then return false end
    E.uiPush(E.textBox("SO WE MEET AGAIN!", { onDone = function()
      E.scriptMove(boss, "down", 1, function()
        E.engageTrainer(boss, function()
          if E.save.flags.EVENT_BEAT_TEST_BOSS2 then E.uiPush(E.textBox("BLAST IT ALL!")) end
        end, "ARRGH!", true)
      end)
    end }))
    return true
  end }
end"""

MASTER_GATE = """function(E)
  return { onStep = function(_, w, x, y)
    if E.save.flags.EVENT_BEAT_TEST_MASTER or x ~= 3 or y ~= 1 then return false end
    local master
    for _, n in ipairs(w.npcs) do if n.def.name == "DOJO_MASTER" then master = n end end
    if not master or E.trainerDefeated(master) then return false end
    w.player.facing = "right"
    master.facing = "left"
    E.engageTrainer(master, nil, "HWA! ARRGH! BEATEN!", nil, nil, false)
    return true
  end }
end"""

MASTER_TALK = """function(E)
  return function(npc, done)
    E.engageTrainer(npc, done, "HWA! ARRGH! BEATEN!", nil, nil, false)
  end
end"""


class LossGame(ContentGame):
    def __init__(self, start, **kw):
        kw.setdefault("maps", MAPS)
        kw.setdefault("data", DATA)
        super().__init__(start=start, **kw)
        self.give("STARTMON_C", 40)

    def lua(self, code, *args):
        return self.b.eval(code)(self.E, *args)

    def talk_fn(self, npc_id, code, *args):
        self.E.talkFns[npc_id.encode()] = self.lua(code, *args)

    def victories(self, table):
        self.E.victories = self.b.to_lua(table)

    def texts(self, after=0):
        """The pages of every text box opened since log index `after`."""
        return [m["data"]["pages"] for m in self.log[after:]
                if m["type"] == "ui.open" and m["data"]["kind"] == "text"]

    def held(self, rounds=10):
        """The next battle.start, the texts read (and walk-ups waited for) until it comes."""
        n = len(self.received("battle.start"))
        for _ in range(rounds):
            self.read_texts(until=lambda g: len(g.received("battle.start")) > n)
            if len(self.received("battle.start")) > n:
                break
            self.run(10)
        return self.received("battle.start")[-1]["data"]

    def win(self, data, outcome="win"):
        mark = len(self.log)
        self.send("battle.result", {"battle": data["battle"], "outcome": outcome})
        self.read_texts()
        return mark

    def same(self, a, b):
        return bool(self.b.eval("function(a, b) return rawequal(a, b) end")(a, b))

    def battle_module(self):
        return self.E.modules[b"src.battle.BattleState"]

    def rewards(self):
        return [(r.cls.decode(), r.party, r.shown) for r in self.E.rewards.values()]


def first_lines(pages_list):
    return [pages[0][0] for pages in pages_list]


def money_box(pages):
    return any("for winning!" in line for page in pages for line in page)


def hall(at):
    x = {ROCKET: 2, LEADER: 4, RECRUITER: 6, PLAIN: 8}[at]
    return LossGame(start=("LOSS_HALL", x, 2, "up"))


def talk(g, npc_id):
    x = {ROCKET: 2, LEADER: 4, RECRUITER: 6, PLAIN: 8}[npc_id]
    g.send("act.interact", {"map": "LOSS_HALL", "x": x, "y": 1})


def assert_clean(g, before_push, before_new):
    """No battle built or pushed for the Cobblemon win, the shadows put back, the world let go."""
    assert g.E.trainerBuilds is None, "BattleState.newTrainer never built a battle"
    assert len(g.E.pushes) == 0 and len(g.E.battles) == 0, "nothing pushed, no native battle"
    assert g.same(g.E.ow.pushBattle, before_push), "pushBattle put back"
    assert g.same(g.battle_module().newTrainer, before_new), "BattleState.newTrainer put back"
    assert g.E.ow.engaging is False and g.mode() == "idle"


# ------------------------------------------------------------ each caller's shape


def test_the_game_corner_rocket_shows_his_own_line_then_his_exit():
    g = hall(ROCKET)
    g.talk_fn(ROCKET, ROCKET_TALK)
    push, new = g.E.ow.pushBattle, g.battle_module().newTrainer
    talk(g, ROCKET)
    data = g.held()
    assert data["kind"] == "trainer" and data["trainer"]["cls"] == "OPP_ROCKET"
    money = g.E.save.money
    mark = g.win(data)
    texts = g.texts(mark)
    assert texts[0] == [["ROCKET: DANG!"]], "his own line, tagged, first"
    assert money_box(texts[1]), "then the prize"
    assert texts[2] == [["I BETTER TELL BOSS!"]], "then his callback's after-battle text"
    assert g.E.rocketLeft is True and g.E.save.defeatedTrainers[ROCKET.encode()] is True
    assert g.E.save.money == money + 30 * 20
    assert g.rewards() == [("OPP_ROCKET", 1, True)], "his armed line counts as shown (rewardDialogueShown)"
    after = [(a.result.decode(), a.kind.decode()) for a in g.E.afterBattles.values()]
    assert after == [("win", "trainer")]
    assert g.E.starts == 1, "the encounter's own start ran once"
    assert_clean(g, push, new)
    st = g.status()
    assert (st["trainerStarts"], st["trainerStartsFailed"], st["lossLines"], st["lossLineSounds"]) == (1, 0, 1, 0)


def test_silph_giovanni_skips_the_battle_text_and_shows_his_line_once():
    g = LossGame(start=("LOSS_TOWER", 5, 5, "up"))
    g.E.mapScripts[b"LOSS_TOWER"] = g.lua(BOSS_TRIGGER)
    g.victories({"OPP_BOSS#2": {"flag": "EVENT_BEAT_TEST_BOSS2", "dialogue": "ARRGH!"}})
    push, new = g.E.ow.pushBattle, g.battle_module().newTrainer
    g.send("move.step", {"dir": "up", "n": 1})
    g.run(20)                          # the step lands on the trigger
    data = g.held()
    assert first_lines(g.texts()) == ["SO WE MEET AGAIN!"], "his text, then the battle with no battle text"
    assert data["trainer"]["cls"] == "OPP_BOSS" and data["trainer"]["party"] == 2
    assert (data["trainer"]["x"], data["trainer"]["y"]) == (5, 2), "where his walk-up left him"
    mark = g.win(data)
    texts = g.texts(mark)
    assert texts[0] == [["BOSS: ARRGH!"]] and money_box(texts[1])
    assert texts[2:] == [[["BLAST IT ALL!"]]], "the victories dialogue isn't shown again; his aftermath is"
    assert "EVENT_BEAT_TEST_BOSS2" in g.flags()
    assert g.rewards() == [("OPP_BOSS", 2, True)]
    assert_clean(g, push, new)


@pytest.mark.parametrize("how", ["gate", "talk"])
def test_the_karate_master_line_is_not_the_reward_dialogue(how):
    g = LossGame(start=("LOSS_DOJO", 3, 2, "up") if how == "gate" else ("LOSS_DOJO", 4, 2, "up"))
    g.victories({"OPP_BLACKBELT#1": {"flag": "EVENT_BEAT_TEST_MASTER", "dialogue": "TAKE A MON!"}})
    if how == "gate":
        g.E.mapScripts[b"LOSS_DOJO"] = g.lua(MASTER_GATE)
        g.send("move.step", {"dir": "up", "n": 1})
        g.run(20)                      # the step lands on the gate
    else:
        g.talk_fn(MASTER, MASTER_TALK)
        g.send("act.interact", {"map": "LOSS_DOJO", "x": 4, "y": 1})
    data = g.held()
    assert first_lines(g.texts()) == ["HWA! COME ON!"], "his header's battle text"
    mark = g.win(data)
    texts = g.texts(mark)
    assert texts[0] == [["MASTER: HWA! ARRGH! BEATEN!"]], "the caller's line over the header's"
    assert money_box(texts[1])
    assert texts[2:] == [[["TAKE A MON!"]]], "endBattleIsReward false: the reward dialogue still shows"
    assert g.rewards() == [("OPP_BLACKBELT", 1, False)]
    assert "EVENT_BEAT_TEST_MASTER" in g.flags() and g.E.save.defeatedTrainers[MASTER.encode()] is True
    assert g.E.ow.engaging is False and g.mode() == "idle"
    assert not list(g.E.scriptErrors.values())


def test_the_nugget_bridge_recruiter_skips_the_battle_text():
    g = hall(RECRUITER)
    g.talk_fn(RECRUITER, RECRUITER_TALK)
    push, new = g.E.ow.pushBattle, g.battle_module().newTrainer
    talk(g, RECRUITER)
    data = g.held()
    assert first_lines(g.texts()) == ["JOIN US!"], "his pitch, then the battle with no battle text"
    mark = g.win(data)
    texts = g.texts(mark)
    assert texts[0] == [["COOLTRAINER: DEFEATED!"]] and money_box(texts[1])
    assert texts[2:] == [[["YOU COULD BE A TOP LEADER!"]]]
    assert g.rewards() == [("OPP_COOLTRAINER", 1, True)]
    assert_clean(g, push, new)


def test_a_gym_leader_badge_lines_play_the_jingle_on_their_page():
    g = hall(LEADER)
    g.talk_fn(LEADER, LEADER_TALK, 2)
    g.victories({"OPP_BROCK#1": {"badge": "BOULDERBADGE", "text": "TAKE THIS TM TOO!",
                                 "dialogue": "I LOST!\fHERE IS\nTHE BADGE!\fUSE IT WELL."}})
    push, new = g.E.ow.pushBattle, g.battle_module().newTrainer
    talk(g, LEADER)
    data = g.held()
    mark = len(g.log)
    g.send("battle.result", {"battle": data["battle"], "outcome": "win"})
    # the jingle plays once the badge page has typed, before the next box opens
    g.read_texts(until=lambda g: any(p == [["USE IT WELL."]] for p in g.texts(mark)))
    assert [s.decode() for s in g.E.sounds.values()] == ["Level_Up"]
    g.read_texts()
    texts = g.texts(mark)
    assert texts[0] == [["LEADER: I LOST!"], ["HERE IS", "THE BADGE!"]], "the tag once, the pages up to the jingle"
    assert texts[1] == [["USE IT WELL."]]
    assert money_box(texts[2])
    assert texts[3:] == [[["TAKE THIS TM TOO!"]]], "the dialogue rode the loss line; the TM lines follow"
    assert g.E.save.inventory.BOULDERBADGE == 1
    assert g.rewards() == [("OPP_BROCK", 1, True)]
    assert [v.decode() for v in g.E.music.victory.values()] == ["gym"]
    assert_clean(g, push, new)
    st = g.status()
    assert st["lossLines"] == 1 and st["lossLineSounds"] == 1


def test_a_jingle_page_past_the_line_plays_nothing():
    g = hall(LEADER)
    g.talk_fn(LEADER, LEADER_TALK, 5)
    talk(g, LEADER)
    data = g.held()
    mark = g.win(data)
    texts = g.texts(mark)
    assert texts[0] == [["LEADER: I LOST!"], ["HERE IS", "THE BADGE!"], ["USE IT WELL."]], "one box"
    assert money_box(texts[1])
    assert len(g.E.sounds) == 0
    assert g.status()["lossLineSounds"] == 0


def test_an_ordinary_trainer_header_line_goes_through_the_same_start():
    g = hall(PLAIN)
    push, new = g.E.ow.pushBattle, g.battle_module().newTrainer
    talk(g, PLAIN)
    data = g.held()
    assert first_lines(g.texts()) == ["EN GARDE!"]
    mark = g.win(data)
    texts = g.texts(mark)
    assert texts[0] == [["TESTER: WELL FOUGHT!"]] and money_box(texts[1]) and len(texts) == 2
    assert "EVENT_BEAT_TEST_PLAIN" in g.flags()
    assert g.rewards() == [("OPP_TEST", 1, False)], "no caller line: the reward dialogue isn't marked shown"
    assert g.npc(PLAIN).frozen is False, "the talk's own completion ran"
    assert_clean(g, push, new)


# ------------------------------------------------------------ the other results, reloads, no client


def test_aborted_keeps_m5s_cancel_and_shows_no_line():
    g = hall(ROCKET)
    g.talk_fn(ROCKET, ROCKET_TALK)
    talk(g, ROCKET)
    data = g.held()
    mark = g.win(data, "aborted")
    assert g.texts(mark) == [], "nothing shown"
    assert g.E.starts is None, "the encounter's start never ran"
    assert g.E.ow.cancelledTrainerSight.npcId == ROCKET.encode()
    assert g.E.save.defeatedTrainers[ROCKET.encode()] is None and g.E.rocketLeft is None
    assert g.E.ow.engaging is False and g.mode() == "idle"
    # and he can be fought again, his line still his
    talk(g, ROCKET)
    again = g.held()
    assert again["battle"] != data["battle"]
    mark = g.win(again)
    assert g.texts(mark)[0] == [["ROCKET: DANG!"]]


def test_a_loss_blacks_out_without_his_line():
    g = hall(ROCKET)
    g.talk_fn(ROCKET, ROCKET_TALK)
    g.E.healPoint = g.b.to_lua({"map": "LOSS_HALL", "x": 1, "y": 3})
    talk(g, ROCKET)
    data = g.held()
    mark = g.win(data, "lose")
    g.run(60)
    lines = [line for pages in g.texts(mark) for page in pages for line in page]
    assert not any("DANG!" in line for line in lines)
    assert any("blacked" in line for line in lines)
    assert g.E.starts is None and g.E.save.defeatedTrainers[ROCKET.encode()] is None
    assert [w["data"]["kind"] for w in g.received("warp")][:1] == ["blackout"]


def test_a_hot_reload_while_held_still_shows_his_line():
    g = hall(ROCKET)
    g.talk_fn(ROCKET, ROCKET_TALK)
    talk(g, ROCKET)
    data = g.held()
    g.b.reload()          # F5: the record and its continuation live in the process-lifetime slot
    g.rig.step(3)
    g.connect()
    g.run(2)
    assert g.received("battle.start")[-1]["data"] == data
    mark = g.win(data)
    texts = g.texts(mark)
    assert texts[0] == [["ROCKET: DANG!"]] and texts[2] == [["I BETTER TELL BOSS!"]]
    assert g.E.trainerBuilds is None and g.E.rocketLeft is True


def test_without_a_client_his_battle_is_native_and_carries_his_line():
    g = LossGame(start=("LOSS_HALL", 2, 2, "up"), connect=False)
    g.talk_fn(ROCKET, ROCKET_TALK)
    pressed = False
    for _ in range(200):
        g.rig.step()
        top = g.E.stack.top(g.E.stack)
        if not pressed and top is not None and top.isOverworld:
            pressed = True
            g.E.game.input.sourcePress(g.E.game.input, b"a", b"test")
            g.E.game.input.sourceRelease(g.E.game.input, b"a", b"test")
        elif top is not None and top.isTextBox:
            g.E.game.input.sourcePress(g.E.game.input, b"a", b"test")
            g.E.game.input.sourceRelease(g.E.game.input, b"a", b"test")
        if len(g.E.pushes) > 0:
            break
    pushes = list(g.E.pushes.values())
    assert len(pushes) == 1 and pushes[0].cls == b"OPP_ROCKET", "the engine's own battle"
    assert pushes[0].endText == b"DANG!", "his line handed to the native battle"
    assert g.E.trainerBuilds == 1


def test_without_pushbattle_the_win_falls_back_to_the_header_path():
    g = hall(PLAIN)
    talk(g, PLAIN)
    data = g.held()
    g.b.eval("function(ow) rawset(ow, 'pushBattle', nil) end")(g.E.ow)
    mark = g.win(data)
    assert g.texts(mark)[0] == [["TESTER: WELL FOUGHT!"]], "the header's line captured at deferral"
    assert "EVENT_BEAT_TEST_PLAIN" in g.flags()
    assert g.E.starts is None, "never started: cancelled as M5 did"
    assert g.E.ow.cancelledTrainerSight.npcId == PLAIN.encode()
    assert g.status()["trainerStarts"] == 0
