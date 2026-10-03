"""M5 end to end, on the real engine: the opening and Oak's escort in the Gen1Recomp test instance,
driven by fake_minecraft as a player would play them (docs/PLAN.md M5 [L]).

The driver (drivers/kb_opening.lua) leaves the game at its boot screens. The test fetches four maps'
cell flags from the real export as a `builder` (kept in memory only), then connects as `test`:

  1. the boot screens arrive as mirror UIs; START (act.button) until the title menu opens as a menu
     UI; NEW GAME is picked with ui.pick (its row matched in memory against the engine's own label)
  2. Oak's speech: every text box read with ui.advance; both naming screens arrive as ui.open kind
     naming (presets and the letter grid) and are answered with ui.name (made-up names); the hidden
     game's names then have those lengths and hashes (debug.status: lengths and short hashes only)
  3. out of the house: the stairs and the exit mat, as in test_walk
  4. Pallet Town: the walk to the north edge's trigger row (y == 1, data/scripts/story2.lua:105-111)
  5. Oak's escort, with no input but A at the text boxes: the "!" over the player (emote), Oak
     appearing (npc.add) and walking up (npc.move kind script), the escort's lockstep walk (player.pos
     kind script, each with its frames), the scripted warp into OAKS_LAB (warp kind script), the
     walk-in, Oak's texts; the classifier's modes along the way (screen, busy, cutscene, forced) and
     idle at the end, with the escort's flags set (the driver's KB_FLAG markers)

Every message passes its schema (strict). Data safety (docs/PLAN.md section 0.3): routes come from
the export in memory; the output shows only types, kinds, counts, symbols and the names' lengths.
"""

import asyncio
import hashlib
import time
from collections import Counter

import pytest

from fake_minecraft import FakeMinecraft
from run_gen1recomp import launch, preflight
from story import StoryWalker, export_grids
from test_walk import DOOR, WARP_TILE, route

TEST_PORT = 27192
MAPS = ("REDS_HOUSE_2F", "REDS_HOUSE_1F", "PALLET_TOWN", "OAKS_LAB")
PLAYER_NAME, RIVAL_NAME = "KBTEST", "KBRIV"   # made up, typed on the real grid
ESCORT_ROW = 1
ESCORT_FLAGS = ("EVENT_FOLLOWED_OAK_INTO_LAB", "EVENT_FOLLOWED_OAK_INTO_LAB_2", "EVENT_OAK_ASKED_TO_CHOOSE_MON")


def short_hash(name):
    return int(hashlib.sha256(name.encode("utf-8")).hexdigest()[:7], 16)


async def _opening(port, running):
    grids = await export_grids(port, MAPS)
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=20)
    await mc.hello()
    w = StoryWalker(mc)
    await w.until(lambda: w.counts["snapshot"] >= 1, 10, "the snapshot")
    report = {}
    first = (await w.status())
    report["at_boot"] = {"ingame": first["ingame"], "topState": first["topState"]}

    # 1. the boot screens and the title menu
    def title_menu():
        for ui_id, ui in w.open_uis().items():
            if ui["kind"] == "menu" and any(item["label"] == "NEW GAME" for item in ui["data"]["items"]):
                return ui_id
        return None

    pressed, deadline = 0.0, time.monotonic() + 90
    while title_menu() is None:
        assert time.monotonic() < deadline, "the title menu never opened"
        open_uis = w.open_uis()
        top = max(open_uis) if open_uis else None
        if top is not None and open_uis[top]["kind"] == "mirror" and time.monotonic() - pressed > 0.8:
            pressed = time.monotonic()
            await mc.send("act.button", {"button": "start", "how": "tap"})
        await w.pump(0.1)
    menu = title_menu()
    rows = [item["label"] for item in w.uis[menu]["data"]["items"]]
    report["boot_mirrors"] = sorted({ui["data"].get("screen", "?") for ui in w.uis.values() if ui["kind"] == "mirror"})
    report["title_rows"] = len(rows)
    await mc.send("ui.pick", {"ui": menu, "index": rows.index("NEW GAME")})

    # 2. Oak's speech, both names typed in "Minecraft"
    positions_before = w.counts["player.pos"]
    await w.drive(lambda: w.counts["player.pos"] > positions_before and w.mode == "idle" and not w.open_uis()
                  and sum(1 for ui in w.uis.values() if ui["kind"] == "naming") >= 2,
                  timeout=300, names=[list(PLAYER_NAME), list(RIVAL_NAME)])
    await w.idle()
    namings = [ui["data"] for ui in w.uis.values() if ui["kind"] == "naming"]
    report["namings"] = [{"presets": len(n["presets"]), "max": n["max"], "grid_pages": len(n.get("grid", []))}
                         for n in namings]
    report["speech_uis"] = dict(Counter(kind for event, kind in w.sequence if event == "open"))
    named = await w.status()
    report["names"] = {k: named[k] for k in ("playerNameLen", "rivalNameLen")}
    report["names_match"] = (named["playerNameHash"] == short_hash(PLAYER_NAME),
                             named["rivalNameHash"] == short_hash(RIVAL_NAME))
    assert w.pos[0] == "REDS_HOUSE_2F", w.pos[0]

    # 3. out of the house: the stairs, then the exit mat
    up = grids["REDS_HOUSE_2F"]
    stairs = [(x, y) for y in range(up.h) for x in range(up.w)
              if up.warp(x, y) and up.flags(x, y) & WARP_TILE and not up.flags(x, y) & DOOR]
    await w.walk_to(up, stairs)
    await w.until(lambda: w.pos[0] == "REDS_HOUSE_1F", 20, "the stairs warp")
    await w.idle()
    down = grids["REDS_HOUSE_1F"]
    mats = [(x, y) for y in range(down.h) for x in range(down.w)
            if down.warp(x, y) and not down.flags(x, y) & (DOOR | WARP_TILE) and y == down.h - 1]
    above = [(x, y - 1) for x, y in mats if down.walkable(x, y - 1) and not down.warp(x, y - 1)]
    await w.walk_to(down, above)
    await w.step("down")
    await w.until(lambda: w.pos[0] == "PALLET_TOWN", 20, "the exit mat warp")
    await w.idle()

    # 4. Pallet Town: north to the trigger row
    town = grids["PALLET_TOWN"]
    goals = [(x, ESCORT_ROW) for x in range(town.w) if town.walkable(x, ESCORT_ROW) and not town.warp(x, ESCORT_ROW)]
    dirs = route(town, (w.pos[1], w.pos[2]), goals, blocked=w.npcs, min_row=ESCORT_ROW)
    assert dirs, "no route to Pallet Town's north edge"
    report["steps_to_edge"] = len(dirs)
    marks = {"script": len(w.script_frames), "modes": len(w.modes), "warps": len(w.warps),
             "adds": w.npc_adds, "moves": Counter(w.npc_moves), "emotes": Counter(w.emotes)}
    assert await w.follow(dirs), "the walk north was refused"

    # 5. the escort: only A at the text boxes
    def escorted():
        return all(running.result.marker(f"KB_FLAG name={flag}") is not None for flag in ESCORT_FLAGS)

    await w.drive(lambda: escorted() and w.pos[0] == "OAKS_LAB" and w.mode == "idle" and not w.open_uis(),
                  timeout=240)
    await w.idle()
    report["escort"] = {
        "modes": w.modes[marks["modes"]:],
        "script_steps": len(w.script_frames) - marks["script"],
        "script_frames": sorted(set(w.script_frames[marks["script"]:])),
        "warps": [(d["phase"], d["kind"], d.get("to", {}).get("map")) for d in w.warps[marks["warps"]:]],
        "npc_adds": w.npc_adds - marks["adds"],
        "npc_moves": dict(w.npc_moves - marks["moves"]),
        "emotes": dict(w.emotes - marks["emotes"]),
    }
    end = await w.status()
    report["end"] = {"pos_map": w.pos[0], "mode": w.mode, "flags": end["flags"]}
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return w, report


@pytest.mark.e2e
@pytest.mark.timeout(900)
def test_the_opening_and_oaks_escort():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    with launch("kb_opening.lua", extra_env={"KB_TEST_PORT": str(TEST_PORT)}) as running:
        marker = running.wait_marker("KB_BOOTED", timeout=150)
        assert marker is not None, running.result.summary()
        assert f"port={TEST_PORT}" in marker
        w, report = asyncio.run(asyncio.wait_for(_opening(TEST_PORT, running), 800))
        result = running.wait(timeout=60)

    print("report:", report)
    print("messages received:", dict(sorted(w.counts.items())))
    print("driver:", [line for line in result.lines if line.startswith(("KB_FLAG", "KB_END", "KB_QUIT",
                                                                         "KB_MAP_ENTERED"))])

    assert not w.mc.conn.problems  # strict: every message passed its schema
    assert not w.errors, w.errors
    assert w.counts["ui.open"] == w.counts["ui.close"], "every UI opened was closed, and only those"
    # 1. boot screens as mirrors, the title menu as a menu
    assert report["at_boot"]["ingame"] is False
    assert "TitleState" in report["boot_mirrors"] and report["title_rows"] >= 3
    # 2. the speech's names typed in "Minecraft"
    assert len(report["namings"]) == 2 and all(n["presets"] >= 1 and n["max"] >= 5 for n in report["namings"])
    assert all(n["grid_pages"] == 2 for n in report["namings"])
    assert report["names"] == {"playerNameLen": len(PLAYER_NAME), "rivalNameLen": len(RIVAL_NAME)}
    assert report["names_match"] == (True, True)
    # 5. the escort, reported as it happens
    e = report["escort"]
    assert {"screen", "busy", "cutscene", "forced"} <= set(e["modes"]), e["modes"]
    assert e["modes"][-1] == "idle"
    assert e["script_steps"] >= 12 and all(f > 0 for f in e["script_frames"]), e
    assert e["npc_adds"] >= 1, "Oak appears"
    assert e["npc_moves"].get("script", 0) >= 10, "Oak walks (scripted)"
    assert e["emotes"].get("player", 0) >= 1, "the ! over the player"
    assert ("start", "script", None) in e["warps"] and ("arrive", "script", "OAKS_LAB") in e["warps"]
    assert report["end"]["pos_map"] == "OAKS_LAB" and report["end"]["mode"] == "idle"
    for flag in ESCORT_FLAGS:
        assert result.marker(f"KB_FLAG name={flag}") is not None, flag
    assert not result.timed_out and result.returncode == 0, result.summary()
    assert result.marker("KB_QUIT") is not None and result.marker("KB_FAIL") is None
    assert not result.crash_log_touched
    assert not result.error_spots
