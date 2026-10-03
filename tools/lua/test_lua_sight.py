"""Map trainers that sight the player (M7), through main.lua on the simulated engine: a move.step into a
trainer's line of sight (fake_center.lua's E.sightCheck) plays the "!" hold and the walk-up (emote,
npc.move kind script) and the battle text, then the encounter is deferred through
trainer.before_battle and sent as battle.start kind trainer; a win marks the trainer beaten (its
event flag, the prize, the loss line, the map's song back), a loss blacks out to the nurse's town
with party.heal after the warp's arrival, and an aborted battle lets the trainer see the player again
only after a step. Every message the peer receives is schema-checked (rig.Peer, strict). All data is
made up (centersim.py)."""

import pytest

from centersim import NURSE, CenterGame

pytestmark = pytest.mark.lua

TRAINER_A = "SIGHT_ROUTE_obj_1"


def on_the_route(**kw):
    g = CenterGame(start=("SIGHT_ROUTE", 3, 4, "left"), **kw)
    g.give_starter()
    g.E.trainerSight = True
    return g


def step_into_sight(g):
    g.send("move.step", {"dir": "left", "n": 1})
    g.play(until=lambda g: g.received("battle.start"))
    return g.last("battle.start")


def test_a_step_into_sight_plays_the_walk_up_and_starts_a_trainer_battle():
    g = on_the_route()
    data = step_into_sight(g)
    pos = [m["data"] for m in g.received("player.pos") if m["data"].get("n") == 1]
    assert pos and (pos[0]["x"], pos[0]["y"], pos[0]["kind"]) == (2, 4, "walk")
    emotes = [m["data"] for m in g.received("emote")]
    assert emotes and emotes[0]["target"] == TRAINER_A and emotes[0]["bubble"] == "!"
    moves = [m["data"] for m in g.received("npc.move") if m["data"]["id"] == TRAINER_A]
    assert [(m["kind"], m["y"]) for m in moves] == [("script", 2), ("script", 3)], "two cells of walk-up"
    texts = [m["data"]["pages"] for m in g.received("ui.open") if m["data"]["kind"] == "text"]
    assert texts[0] == [["HEY YOU!"]]
    assert data["kind"] == "trainer" and data["rules"] == {"catch": False, "flee": False}
    assert data["trainer"] == {"cls": "OPP_TEST", "party": 1, "name": "TESTER", "npc": TRAINER_A,
                               "map": "SIGHT_ROUTE", "x": 2, "y": 3}
    assert [m["level"] for m in data["team"]] == [9]
    g.run(5)
    assert g.mode() == "busy" and g.E.ow.engaging is True and not list(g.E.battles)


def test_a_win_beats_the_trainer_pays_and_brings_the_song_back():
    g = on_the_route()
    money = g.E.save.money
    data = step_into_sight(g)
    g.send("battle.result", {"battle": data["battle"], "outcome": "win"})
    g.run(3)
    lines = [m["data"]["pages"][0][0] for m in g.received("ui.open") if m["data"]["kind"] == "text"]
    assert lines[-1] == "TESTER: YOU GOT ME!", "the loss line, tagged"
    g.play()
    assert g.E.save.defeatedTrainers[TRAINER_A.encode()] is True and "EVENT_BEAT_SIGHT_A" in g.flags()
    assert g.E.save.money == money + 15 * 9
    assert g.E.musicRestores == 1, "the map's song, as a battle's exit brings it back"
    assert g.E.ow.engaging is False and g.npc(TRAINER_A).frozen is False and g.mode() == "idle"
    # beaten: stepping out of and back into the line starts nothing
    g.send("move.step", {"dir": "down", "n": 2})
    g.run(20)
    g.send("move.step", {"dir": "up", "n": 3})
    g.run(40)
    assert len(g.received("battle.start")) == 1
    st = g.status()
    assert st["defeated"] == 1 and st["battleWin"] == 1


def test_a_loss_blacks_out_to_the_nurses_town_then_heals():
    g = CenterGame()
    g.give_starter()
    g.E.ow.lastOutdoor = g.b.to_lua({"id": "SIGHT_ROUTE", "x": 8, "y": 4})
    g.send("act.interact", NURSE)
    g.play(choice=0, until=lambda g: g.received("save.begin"))
    g.send("save.done", {"save": g.last("save.begin")["save"], "ok": True})
    assert g.E.save.lastHeal.outdoor.id == b"SIGHT_ROUTE"
    heals = len(g.received("party.heal"))
    g.E.scriptWarp(b"SIGHT_ROUTE", 3, 4, b"left")
    g.run(40)
    g.E.trainerSight = True
    money = g.E.save.money
    data = step_into_sight(g)
    mark = len(g.log)
    g.send("battle.result", {"battle": data["battle"], "outcome": "lose"})
    g.play(until=lambda g: len(g.received("party.heal")) > heals)
    g.run(3)
    after = [(m["type"], m["data"].get("phase"), m["data"].get("kind")) for m in g.log[mark:]
             if m["type"] in ("warp", "party.heal")]
    assert after == [("warp", "start", "blackout"), ("warp", "arrive", "blackout"), ("party.heal", None, None)]
    arrive = [m["data"] for m in g.received("warp")][-1]
    assert arrive["to"]["map"] == "SIGHT_ROUTE" and (arrive["to"]["x"], arrive["to"]["y"]) == (8, 4)
    assert g.E.save.money == money // 2
    assert g.E.save.defeatedTrainers[TRAINER_A.encode()] is None
    pages = [m["data"]["pages"] for m in g.log[mark:] if m["type"] == "ui.open" and m["data"]["kind"] == "text"]
    assert any("blacked" in " ".join(sum(p, [])) for p in pages)
    st = g.status()
    assert st["battleLose"] == 1 and st["healHeld"] is False


def test_an_aborted_battle_waits_for_the_player_to_move():
    g = on_the_route()
    data = step_into_sight(g)
    g.send("battle.result", {"battle": data["battle"], "outcome": "aborted"})
    g.play()
    g.run(30)
    assert len(g.received("battle.start")) == 1, "the cancelled sighting holds while the player stands"
    assert g.E.ow.cancelledTrainerSight.npcId == TRAINER_A.encode()
    g.send("move.step", {"dir": "right", "n": 2})
    g.run(20)
    g.send("move.step", {"dir": "left", "n": 3})
    g.play(until=lambda g: len(g.received("battle.start")) == 2)
    assert g.received("battle.start")[-1]["data"]["battle"] != data["battle"]


def test_without_a_client_the_trainer_battles_natively():
    g = CenterGame(start=("SIGHT_ROUTE", 2, 4, "up"), connect=False)
    g.give_starter()
    g.E.trainerSight = True
    for _ in range(200):
        g.rig.step()
        top = g.E.stack.top(g.E.stack)
        if top is not None and top.isTextBox:
            g.E.game.input.sourcePress(g.E.game.input, b"a", b"test")
            g.E.game.input.sourceRelease(g.E.game.input, b"a", b"test")
        if len(g.E.battles) > 0:
            break
    battles = list(g.E.battles.values())
    assert len(battles) == 1 and battles[0].cls == b"OPP_TEST"
