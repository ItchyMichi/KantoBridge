"""M5 end to end, on the real engine: the starter gift and the Oak's-lab rival battle in the Gen1Recomp
test instance, driven by fake_minecraft (docs/PLAN.md M5 [T]).

The driver (drivers/kb_battle.lua) starts a new game without the intro and sets up what Oak's escort
leaves behind, with the engine's own APIs: the escort's flags, the lab's Oak swap, and a scripted warp
into OAKS_LAB below a starter ball. The test fetches OAKS_LAB's cell flags from the real export as a
`builder` (kept in memory only), then connects as `test` and plays it like a player:

  1. act.interact on the ball above: the Pokédex entry (a mirror screen, closed with B), "do you want
     it?" answered YES (ui.pick 0), the texts read, the nickname prompt answered NO (ui.back): a
     party.give arrives (source starter, dex one of 1/4/7, level 5); the test answers with a party.sync
     holding a made-up uid for it, and gen1-bridge's shadow party takes the uid (no duplicate)
  2. the rival takes his ball; the test walks down to the rival's trigger row: his challenge plays and
     a battle.start arrives (kind rival, class OPP_RIVAL1, 1-6 team members, levels in range), the
     hidden game holds (mode busy), no native battle screen appears
  3. (lose only) the client drops and connects again while the battle is held: after the new
     snapshot, battle.start comes again with the same id
  4. battle.result win (or lose): the result's texts are read, the lab script goes on (HealParty ->
     party.heal), and the hidden game's flag count went up (EVENT_BATTLED_RIVAL_IN_OAKS_LAB); a loss in
     the lab continues the story: no blackout, still in OAKS_LAB

Every message passes its schema (strict). Data safety (docs/PLAN.md section 0.3): the route is computed
in memory from the export; the output shows only types, kinds, counts, ranges and symbols, never
species, moves, names or text.
"""

import asyncio
from collections import Counter

import pytest

from fake_minecraft import FakeMinecraft
from run_gen1recomp import launch, preflight
from story import StoryWalker, export_grids
from test_walk import route

PORTS = {"win": 27194, "lose": 27193}
STARTER_UID = "5b0c7e2a-3d4f-4a1b-9c2d-7e8f9a0b1c2d"  # made up
TRIGGER_ROW = 6  # OaksLabRivalChallengesPlayerScript: wYCoord == 6 (data/scripts/oaks_lab.lua:303-306)


async def _lab(port, outcome):
    grids = await export_grids(port, ("OAKS_LAB",))
    lab = grids["OAKS_LAB"]
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=20)
    await mc.hello()
    w = StoryWalker(mc)
    await w.until(lambda: w.pos is not None and w.npc_sets, 10, "the snapshot and npc.set")
    assert w.pos[0] == "OAKS_LAB", w.pos[0]
    await w.idle()
    report = {}
    before = await w.status()
    report["flags_before"] = before["flags"]

    # 1. the starter ball right above the player
    balls = {cell for npc_id, cell in w.npc_cells.items() if "BALL" in w.sprites.get(npc_id, "")}
    report["balls"] = len(balls)
    x, y = w.pos[1], w.pos[2]
    assert (x, y - 1) in balls, "no starter ball above the player"
    result = await w.act("act.interact", {"map": "OAKS_LAB", "x": x, "y": y - 1})
    assert result.get("ok") is True, result
    # the Pokédex entry (mirror: B), "do you want it?" YES, the texts, the nickname prompt NO
    await w.drive(lambda: bool(w.gives), timeout=90, choices=[0, 1], mirror_button="b")
    give = w.gives[0]
    report["give"] = {"source": give["source"], "dex_is_a_starter": give["dex"] in (1, 4, 7),
                      "level": give["level"], "moves": len(give.get("moves", [])), "dvs": "dvs" in give,
                      "nick": "nick" in give}
    await mc.send("party.sync", {"party": [{"uid": STARTER_UID, "dex": give["dex"], "level": give["level"],
                                            "moves": give.get("moves", []), "hp": 20, "maxhp": 20}],
                                 "box": {"count": 0}})
    # the rival takes his ball, then the world is the player's again
    await w.drive(lambda: w.mode == "idle" and not w.open_uis(), timeout=90)
    await w.idle()
    mid = await w.status()
    report["party_after_sync"] = {k: mid[k] for k in ("partySize", "partyKnown", "giftsPending", "partyMatched")}

    # 2. down to the rival's trigger row; his challenge, then the battle
    goals = [(cx, TRIGGER_ROW) for cx in range(lab.w) if lab.walkable(cx, TRIGGER_ROW) and not lab.warp(cx, TRIGGER_ROW)]
    dirs = route(lab, (w.pos[1], w.pos[2]), goals, blocked=w.npcs)
    assert dirs, "no route to the rival's trigger row"
    report["steps_to_trigger"] = len(dirs)
    assert await w.follow(dirs), "the walk to the trigger row was refused"
    await w.drive(lambda: bool(w.battles), timeout=90)
    start = w.battles[0]
    team = start.get("team", [])
    levels = [m["level"] for m in team]
    report["battle"] = {
        "kind": start["kind"], "cls": start.get("trainer", {}).get("cls"), "team": len(team),
        "levels_in_range": all(1 <= lv <= 10 for lv in levels),
        "dex_in_range": all(1 <= m["dex"] <= 151 for m in team),
        "moves_per_member": sorted({len(m["moves"]) for m in team}),
        "rules": start["rules"], "npc": "npc" in start.get("trainer", {}),
    }
    await asyncio.sleep(1.0)
    await w.pump(0.2)
    held = await w.status()
    report["held"] = {"mode": w.mode, "battlePending": held["battlePending"], "flags": held["flags"]}

    # 3. a reconnect while the battle is held: battle.start again, same id, after the snapshot
    if outcome == "lose":
        report["first_connection_problems"] = len(await w.reconnect())
        mc = w.mc
        await w.until(lambda: len(w.battles) >= 2, 20, "battle.start again after the reconnect")
        tail = w.order[w.order.index("snapshot", 1):]
        report["resent"] = {"same_id": w.battles[1]["battle"] == start["battle"],
                            "same_payload": w.battles[1] == start, "after_snapshot": tail[:2] == ["snapshot", "battle.start"]}

    # 4. the result
    await mc.send("battle.result", {"battle": start["battle"], "outcome": outcome})
    await w.drive(lambda: w.mode == "idle" and not w.open_uis() and w.heals >= 1, timeout=120)
    await w.idle()
    after = await w.status()
    report["flags_after"] = after["flags"]
    report["after"] = {k: after[k] for k in ("battlePending", "battleResults", "battleWin", "battleLose",
                                              "partySize", "partyHeals", "mode")}
    report["pos_map"] = w.pos[0]
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return w, report, before, held, after


@pytest.mark.e2e
@pytest.mark.timeout(900)
@pytest.mark.parametrize("outcome", ["win", "lose"])
def test_the_starter_and_the_rival_battle_in_oaks_lab(outcome):
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    port = PORTS[outcome]
    with launch("kb_battle.lua", extra_env={"KB_TEST_PORT": str(port)}) as running:
        marker = running.wait_marker("KB_LAB_READY", timeout=150)
        assert marker is not None, running.result.summary()
        assert f"port={port}" in marker
        w, report, before, held, after = asyncio.run(asyncio.wait_for(_lab(port, outcome), 600))
        result = running.wait(timeout=60)

    warps = [(d["phase"], d["kind"]) for d in w.warps]
    print("outcome:", outcome)
    print("report:", report)
    print("modes:", w.modes)
    print("warps:", warps)
    print("ui kinds opened:", dict(Counter(kind for event, kind in w.sequence if event == "open")))
    print("messages received:", dict(sorted(w.counts.items())))
    print("driver:", [line for line in result.lines if line.startswith(("KB_FLAG", "KB_END", "KB_BLACKOUT",
                                                                         "KB_BATTLE_PUSHED", "KB_QUIT"))])

    assert not w.mc.conn.problems  # strict: every message passed its schema
    assert not w.errors, w.errors
    # 1. the gift, and the shadow party taking Cobblemon's uid without a duplicate
    assert report["give"]["source"] == "starter" and report["give"]["dex_is_a_starter"]
    assert report["give"]["level"] == 5 and 1 <= report["give"]["moves"] <= 4 and report["give"]["dvs"]
    assert not report["give"]["nick"], "the nickname prompt was answered NO"
    assert report["party_after_sync"] == {"partySize": 1, "partyKnown": 1, "giftsPending": 0, "partyMatched": 1}
    assert len(w.gives) == 1
    # 2. the battle
    b = report["battle"]
    assert b["kind"] == "rival" and b["cls"] == "OPP_RIVAL1" and 1 <= b["team"] <= 6
    assert b["levels_in_range"] and b["dex_in_range"] and all(1 <= n <= 4 for n in b["moves_per_member"])
    assert b["rules"] == {"catch": False, "flee": False} and b["npc"]
    if outcome == "lose":
        assert report["resent"] == {"same_id": True, "same_payload": True, "after_snapshot": True}
        assert report["first_connection_problems"] == 0
        assert len(w.battles) == 2, "sent once, then once more after the reconnect"
    else:
        assert len(w.battles) == 1, "sent once"
    assert report["held"]["mode"] == "busy" and report["held"]["battlePending"] is True
    assert report["held"]["flags"] == report["flags_before"] + 2, "EVENT_GOT_STARTER and the chosen ball's flag"
    # 3. the result applied, the story going on
    assert report["flags_after"] > report["held"]["flags"], "the progress flags went up"
    assert after["battlePending"] is False and after["battleResults"] == 1
    assert after[f"battle{outcome.capitalize()}"] == 1
    assert w.heals >= 1, "the lab's HealParty heals Cobblemon's party too"
    assert report["pos_map"] == "OAKS_LAB" and after["mode"] == "idle"
    assert ("start", "blackout") not in warps, "no blackout in the lab"
    assert result.marker("KB_BLACKOUT") is None
    assert result.marker("KB_BATTLE_PUSHED") is None, "no native battle screen: Cobblemon fought it"
    assert result.marker("KB_FLAG name=EVENT_BATTLED_RIVAL_IN_OAKS_LAB") is not None
    assert result.marker("KB_FLAG name=EVENT_GOT_STARTER") is not None
    assert not result.timed_out and result.returncode == 0, result.summary()
    assert result.marker("KB_QUIT") is not None and result.marker("KB_FAIL") is None
    assert not result.crash_log_touched
    assert not result.error_spots
