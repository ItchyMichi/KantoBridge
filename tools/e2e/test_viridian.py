"""M7 end to end, on the real engine: Viridian City's Pokémon Center (the nurse's heal, both saves, the
start menu's SAVE, session.bind), Bill's PC as Cobblemon's PC, the Mart through the generic menus, and
map trainers (a win, then a loss and the blackout) in the Gen1Recomp test instance, driven by
fake_minecraft as role `test` (docs/PLAN.md M7 [T]).

The driver (drivers/kb_viridian.lua) starts a new game without the intro, gives the player a starter,
the parcel-delivered flags and a bag (POTION x3 first, a key item) through the engine's own APIs, and
walks it into a scene by mod.world warps (VIRIDIAN_CITY first, so the town is the remembered outdoor
side), chosen by KB_SCENE:

  story  across the counter from the nurse in VIRIDIAN_POKECENTER:
           1. act.interact the nurse, HEAL: party.heal arrives (Cobblemon heals), then -- once her
              lines are done and the player stands -- the hidden game saves itself (the driver sees
              save.writing: KB_SAVE_WRITTEN) and save.begin{reason:"center"} arrives; save.done ok
           2. the start menu's SAVE (the row matched in memory against the engine's own label), YES:
              save.begin{reason:"menu"}; save.done ok:false is a counted warning (a `log` warn)
           3. session.bind -> session.bound{slot} (re set), kept in mod.save
           4. the driver puts the player beside a map trainer's line of sight (found with the
              engine's own data): one move.step into it plays the "!" bubble over the trainer, its
              walk-up (npc.move kind script) and the battle text, then battle.start{kind:"trainer"}
              (the trainer's id and class, 1-6 members, levels and dex in range, no catch, no
              flee) with the world held (busy) and no native battle screen; win: the loss line and
              prize texts, the trainer beaten, money up, the event flag set
           5. a second trainer, lose: the blackout texts, warp{kind:"blackout"} start then arrive at
              the town the nurse's lastHeal names (the driver's KB_HEALED), then party.heal, money
              halved
  pc     facing the PC: the box row's access text, then pc.open, the hidden game held (busy, no
         BoxMenu ever pushed); a pc.closed with a stale id changes nothing (M10), the one with the
         pc.open's id hands the PC menu back; the player's own PC opens natively
         (PlayerPC) and B returns to the PC menu; LOG OFF
  mart   across the counter from the clerk: the greeting, the BUY/SELL/QUIT menu with money; BUY:
         B at the quantity box, NO at the confirm (nothing changes), then a purchase of 2 (money down
         by twice the unit price the quantity box showed, the bag up by 2, the list sent again with
         the new money, bag.sync); "anything else?" (the menu sent again with the clerk's new line);
         SELL one POTION (money up by the unit, bag down by 1, POTION x2 in bag.sync); QUIT

Every message passes its schema (strict). Data safety (docs/PLAN.md section 0.3): text, labels, names,
prices and money amounts stay in memory; only booleans, counts, kinds, ids and map/class symbols are
printed.
"""

import asyncio
from collections import Counter

import pytest

from fake_minecraft import FakeMinecraft
from run_gen1recomp import launch, preflight
from story import StoryWalker
from test_route1 import BATTLE_ITEMS

PORTS = {"story": 27190, "pc": 27189, "mart": 27188}
CENTER, MART = "VIRIDIAN_POKECENTER", "VIRIDIAN_MART"
WORLD = "kb-e2e-viridian"
DELTA = {"up": (0, -1), "down": (0, 1), "left": (-1, 0), "right": (1, 0)}


class ViridianWalker(StoryWalker):
    """StoryWalker plus what M7 sends: save.begin, session.bound, pc.open, logs, bag.sync, and one
    timeline of the messages whose order matters."""

    def __init__(self, mc):
        super().__init__(mc)
        self.saves = []
        self.bounds = []
        self.pc_opens = 0
        self.pc_ids = []  # M10: each pc.open's id, echoed by the pc.closed that ends it
        self.logs = []
        self.bag_syncs = []
        self.timeline = []
        self.emote_targets = []
        self.npc_move_ids = Counter()
        self.reopened = Counter()

    def handle(self, m):
        t, d = m["type"], m["data"]
        if t == "ui.open" and d["ui"] in self.uis and not self.uis[d["ui"]]["closed"]:
            self.reopened[d["kind"]] += 1
        super().handle(m)
        if t == "save.begin":
            self.saves.append(d)
        elif t == "session.bound":
            self.bounds.append((d, m.get("re")))
        elif t == "pc.open":
            self.pc_opens += 1
            self.pc_ids.append(d["pc"])
        elif t == "log":
            self.logs.append((d["level"], d["code"]))
        elif t == "bag.sync":
            self.bag_syncs.append(d["items"])
        elif t == "emote":
            self.emote_targets.append((d["target"], d["bubble"]))
        elif t == "npc.move" and d["kind"] == "script":
            self.npc_move_ids[d["id"]] += 1
        if t in ("warp", "party.heal", "save.begin", "pc.open", "battle.start"):
            self.timeline.append((t, d.get("phase") or d.get("reason") or d.get("kind")))

    def top(self):
        uis = self.open_uis()
        if not uis:
            return None, None
        top = max(uis)
        return top, uis[top]

    async def top_is(self, kind, timeout=20, screen=None):
        def ok():
            _, ui = self.top()
            return ui is not None and ui["kind"] == kind and (screen is None or ui["data"].get("screen") == screen)
        await self.until(ok, timeout, f"a {kind} UI on top")
        return self.top()

    async def read_top_text(self, timeout=60):
        """A at each arrow of the text boxes on top, until the top UI is no text box."""
        done = set()
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        while True:
            ui_id, ui = self.top()
            if ui is None or ui["kind"] != "text":
                return
            st = ui["state"]
            key = (ui_id, st.get("page", 0), st.get("line", 0), st.get("done", False))
            if st.get("waiting") and key not in done:
                done.add(key)
                await self.mc.send("ui.advance", {"ui": ui_id})
            if loop.time() > deadline:
                raise AssertionError("the text boxes never finished")
            await self.pump(0.1)

    async def pick(self, ui_id, index):
        await self.mc.send("ui.pick", {"ui": ui_id, "index": index})


async def scene(w, running, name, timeout=60):
    """The facts of the driver's KB_SCENE marker for a scene, once it is printed."""
    prefix = f"KB_SCENE scene={name}"
    await w.until(lambda: running.result.marker(prefix) is not None, timeout, prefix)
    line = running.result.marker(prefix)
    return dict(part.split("=", 1) for part in line.split()[1:] if "=" in part)


async def connect(port):
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=20)
    await mc.hello()
    w = ViridianWalker(mc)
    await w.until(lambda: w.pos is not None and w.npc_sets, 15, "the snapshot and npc.set")
    await w.idle()
    return w


# ------------------------------------------------------------ story: the center, saving, trainers


async def _story(port, running):
    w = await connect(port)
    mc = w.mc
    report = {}
    nurse = await scene(w, running, "nurse")
    assert w.pos[0] == CENTER, w.pos[0]
    before = await w.status()

    # 1. the nurse
    result = await w.act("act.interact", {"map": CENTER, "x": int(nurse["tx"]), "y": int(nurse["ty"])})
    assert result == {"ok": True, "kind": "npc"}, result
    await w.drive(lambda: bool(w.saves), timeout=180, choices=[0])
    await w.settle()
    st = await w.status()
    heal_at = w.timeline.index(("party.heal", None))
    save_at = w.timeline.index(("save.begin", "center"))
    report["center"] = {
        "heal_then_save": heal_at < save_at, "reason": w.saves[0]["reason"],
        "written": st["saveCenterWritten"], "refused": st["saveCenterRefused"], "failed": st["saveCenterFailed"],
        "writes": st["saveWrites"], "heals": w.heals,
    }
    await mc.send("save.done", {"save": w.saves[0]["save"], "ok": True})

    # 2. the start menu's SAVE
    menu_id = await w.start_menu()
    labels = [it["label"] for it in w.uis[menu_id]["data"]["items"]]
    assert "SAVE" in labels, "the start menu has a SAVE row"
    await w.pick(menu_id, labels.index("SAVE"))
    await w.drive(lambda: len(w.saves) >= 2, timeout=90, choices=[0])
    await w.settle()
    await mc.send("save.done", {"save": w.saves[1]["save"], "ok": False})
    await asyncio.sleep(0.3)
    st = await w.status()
    report["menu"] = {"reason": w.saves[1]["reason"], "ids_differ": w.saves[0]["save"] != w.saves[1]["save"],
                      "done": st["saveDone"], "ok": st["saveOk"], "failed": st["saveFailed"],
                      "warned": ("warn", "save.minecraft_failed") in w.logs}

    # 3. session.bind
    sent = await mc.send("session.bind", {"world": WORLD, "multiplayer": False})
    await w.until(lambda: w.bounds, 10, "session.bound")
    st = await w.status()
    bound, re = w.bounds[0]
    report["session"] = {"re_ok": re == sent["seq"], "slot_ok": 0 <= bound["slot"] <= 255,
                         "slot_matches": bound["slot"] == st["saveSlot"], "stored": st["saveStored"],
                         "binds": st["saveBinds"]}

    # 4. trainer A: one step into its sight, a win
    a = await scene(w, running, "trainer_a", timeout=90)
    report["trainer_a_map"] = a["map"]
    await w.until(lambda: w.pos is not None and (w.pos[0], w.pos[1], w.pos[2]) == (a["map"], int(a["x"]), int(a["y"])),
                  30, "the warp beside trainer A")
    await w.idle()
    st_a = await w.status()
    await w.step(a["dir"])
    await w.drive(lambda: bool(w.battles), timeout=90)
    start = w.battles[0]
    team = start.get("team", [])
    report["battle_a"] = {
        "kind": start["kind"], "npc": start["trainer"].get("npc") == a["npc"], "cls": start["trainer"]["cls"] == a["cls"],
        "team": len(team), "levels_ok": all(1 <= m["level"] <= 100 for m in team),
        "dex_ok": all(1 <= m["dex"] <= 151 for m in team), "rules": start["rules"],
        "bubble": (a["npc"], "!") in w.emote_targets, "walk_up": w.npc_move_ids[a["npc"]],
    }
    await asyncio.sleep(1.0)
    await w.pump(0.2)
    held = await w.status()
    report["held"] = {"mode": w.mode, "pending": held["battlePending"]}
    texts_before = sum(1 for event, kind in w.sequence if (event, kind) == ("open", "text"))
    await mc.send("battle.result", {"battle": start["battle"], "outcome": "win"})
    await w.drive(lambda: w.mode == "idle" and not w.open_uis(), timeout=120)
    await w.idle()
    won = await w.status()
    report["won"] = {"win": won["battleWin"], "defeated_up": won["defeated"] == st_a["defeated"] + 1,
                     "money_up": won["money"] > st_a["money"], "flags_up": won["flags"] > st_a["flags"],
                     "pending": won["battlePending"],
                     "texts": sum(1 for event, kind in w.sequence if (event, kind) == ("open", "text")) - texts_before}
    # talking to the beaten trainer: its after-battle line, no battle
    tx, ty = w.npc_cells[a["npc"]]
    texts_before_talk = sum(1 for event, kind in w.sequence if (event, kind) == ("open", "text"))
    talked = await w.act("act.interact", {"map": a["map"], "x": tx, "y": ty})
    await w.drive(lambda: w.mode == "idle" and not w.open_uis(), timeout=60)
    await w.idle()
    report["after_talk"] = {"ok": talked.get("ok") is True and talked.get("kind") == "npc",
                            "texts": sum(1 for event, kind in w.sequence if (event, kind) == ("open", "text"))
                            - texts_before_talk,
                            "no_battle": len(w.battles) == 1}

    # 5. trainer B: a loss, the blackout
    b = await scene(w, running, "trainer_b", timeout=90)
    report["trainer_b_map"] = b["map"]
    await w.until(lambda: (w.pos[0], w.pos[1], w.pos[2]) == (b["map"], int(b["x"]), int(b["y"])), 30,
                  "the warp beside trainer B")
    await w.idle()
    st_b = await w.status()
    heals_b = w.heals
    mark = len(w.timeline)
    await w.step(b["dir"])
    await w.drive(lambda: len(w.battles) >= 2, timeout=90)
    lose = w.battles[1]
    report["battle_b"] = {"kind": lose["kind"], "npc": lose["trainer"].get("npc") == b["npc"]}
    await mc.send("battle.result", {"battle": lose["battle"], "outcome": "lose"})
    await w.drive(lambda: w.heals > heals_b and w.mode == "idle" and not w.open_uis(), timeout=150)
    await w.idle()
    lost = await w.status()
    healed = running.result.facts("KB_HEALED")
    after = [e for e in w.timeline[mark:] if e[0] in ("warp", "party.heal")]
    arrive = [d for d in w.warps if d["phase"] == "arrive"][-1]
    report["blackout"] = {
        "order": after[-3:] == [("warp", "start"), ("warp", "arrive"), ("party.heal", None)],
        "kind": arrive["kind"], "to_town": arrive["to"]["map"] == healed.get("outdoor"),
        "town": arrive["to"]["map"], "money_halved": lost["money"] == st_b["money"] // 2,
        "lose": lost["battleLose"], "heal_held_now": lost["healHeld"], "pos_map": w.pos[0],
    }
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return w, report, before


@pytest.mark.e2e
@pytest.mark.timeout(1200)
def test_the_pokemon_center_saving_and_map_trainers():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    port = PORTS["story"]
    with launch("kb_viridian.lua", extra_env={"KB_TEST_PORT": str(port), "KB_SCENE": "story"}) as running:
        marker = running.wait_marker("KB_SCENE scene=nurse", timeout=180)
        assert marker is not None, running.result.summary()
        assert f"port={port}" in marker
        w, report, before = asyncio.run(asyncio.wait_for(_story(port, running), 1000))
        result = running.wait(timeout=60)

    print("report:", report)
    print("modes:", w.modes)
    print("warps:", [(d["phase"], d["kind"]) for d in w.warps])
    print("messages received:", dict(sorted(w.counts.items())))
    print("driver:", [line for line in result.lines if line.startswith((
        "KB_SCENE", "KB_HEALED", "KB_SAVE_WRITTEN", "KB_BLACKOUT", "KB_BATTLE_PUSHED", "KB_SCREEN_PUSHED", "KB_END",
        "KB_QUIT"))])

    assert not w.mc.conn.problems  # strict: every message passed its schema
    assert not w.errors, w.errors
    c = report["center"]
    assert c["heal_then_save"] and c["reason"] == "center" and c["heals"] >= 1
    assert (c["written"], c["refused"], c["failed"]) == (1, 0, 0), "the spike: Game:writeSave from the bridge"
    assert result.marker("KB_SAVE_WRITTEN n=1") is not None, "the hidden game's save.writing"
    assert result.marker("KB_HEALED") is not None
    m = report["menu"]
    assert m["reason"] == "menu" and m["ids_differ"] and (m["done"], m["ok"], m["failed"]) == (2, 1, 1) and m["warned"]
    assert result.marker("KB_SAVE_WRITTEN n=2") is not None
    s = report["session"]
    assert s == {"re_ok": True, "slot_ok": True, "slot_matches": True, "stored": True, "binds": 1}
    a = report["battle_a"]
    assert a["kind"] == "trainer" and a["npc"] and a["cls"] and 1 <= a["team"] <= 6 and a["levels_ok"] and a["dex_ok"]
    assert a["rules"] == {"catch": False, "flee": False}
    assert a["bubble"], "the '!' over the trainer"
    assert a["walk_up"] >= 1, "the trainer walked up"
    assert report["held"] == {"mode": "busy", "pending": True}
    won = report["won"]
    assert won["texts"] >= 1, "the loss line and the prize, as text boxes"
    assert {k: v for k, v in won.items() if k != "texts"} == {"win": 1, "defeated_up": True, "money_up": True,
                                                             "flags_up": True, "pending": False}
    assert report["after_talk"]["ok"] and report["after_talk"]["texts"] >= 1 and report["after_talk"]["no_battle"]
    assert report["battle_b"] == {"kind": "trainer", "npc": True}
    bo = report["blackout"]
    assert bo["order"] and bo["kind"] == "blackout" and bo["to_town"] and bo["money_halved"] and bo["lose"] == 1
    assert bo["heal_held_now"] is False and bo["pos_map"] == bo["town"]
    assert result.marker("KB_BLACKOUT") is not None
    assert result.marker("KB_BATTLE_PUSHED") is None, "no native battle screen: Cobblemon fought them"
    assert not result.timed_out and result.returncode == 0, result.summary()
    assert result.marker("KB_QUIT") is not None and result.marker("KB_FAIL") is None
    assert not result.crash_log_touched
    assert not result.error_spots


# ------------------------------------------------------------ Bill's PC


async def _pc(port, running):
    w = await connect(port)
    mc = w.mc
    report = {}
    pc = await scene(w, running, "pc")
    result = await w.act("act.interact", {"map": CENTER, "x": int(pc["tx"]), "y": int(pc["ty"])})
    assert result.get("ok") is True, result
    report["interact_kind"] = result.get("kind")
    await w.top_is("text")                                     # "... turned on the PC."
    await w.read_top_text()
    menu_id, menu = await w.top_is("menu")
    report["rows"] = len(menu["data"]["items"])
    await w.pick(menu_id, 0)                                   # the box row
    await w.until(lambda: w.pc_opens >= 1 or (w.top()[1] or {}).get("kind") == "text", 20, "the access text")
    await w.read_top_text()
    await w.until(lambda: w.pc_opens >= 1, 20, "pc.open")
    await asyncio.sleep(1.0)
    await w.pump(0.2)
    held = await w.status()
    report["held"] = {"mode": w.mode, "pcHeld": held["pcHeld"], "opens": w.pc_opens,
                      "menu_open": menu_id in w.open_uis()}
    # M10: a pc.closed naming another id leaves the hold alone; the open's own id ends it
    await mc.send("pc.closed", {"pc": "pc-00000000-1"})
    await asyncio.sleep(0.5)
    await w.pump(0.2)
    stale = await w.status()
    report["stale"] = {"pcHeld": stale["pcHeld"], "stale": stale["pcStale"]}
    await mc.send("pc.closed", {"pc": w.pc_ids[-1]})
    await w.until(lambda: w.mode == "screen", 20, "the PC menu back")
    released = await w.status()
    report["released"] = {"pcHeld": released["pcHeld"], "closed": released["pcClosed"],
                          "top_is_menu": w.top()[0] == menu_id}
    # the player's own PC, natively
    await w.pick(menu_id, 1)
    await w.until(lambda: (w.top()[1] or {}).get("kind") == "text", 20, "the access text")
    await w.read_top_text()
    sub_id, _ = await w.top_is("menu", screen="PlayerPC")
    await mc.send("ui.back", {"ui": sub_id})
    await w.until(lambda: w.uis[sub_id]["closed"] and w.top()[0] not in (None, sub_id), 20, "the PC menu again")
    again_id, again = await w.top_is("menu")
    report["player_pc"] = {"back_to_pc_menu": again_id != sub_id and again["data"].get("screen") is None}
    await w.pick(again_id, len(again["data"]["items"]) - 1)    # LOG OFF
    await w.settle()
    st = await w.status()
    report["after"] = {"opens": w.pc_opens, "unexpected": st["pcUnexpected"], "native": st["pcNative"],
                       "taken": st["pcTaken"], "mode": st["mode"]}
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return w, report


@pytest.mark.e2e
@pytest.mark.timeout(600)
def test_bills_pc_opens_cobblemons_pc():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    port = PORTS["pc"]
    with launch("kb_viridian.lua", extra_env={"KB_TEST_PORT": str(port), "KB_SCENE": "pc"}) as running:
        marker = running.wait_marker("KB_SCENE scene=pc", timeout=180)
        assert marker is not None, running.result.summary()
        w, report = asyncio.run(asyncio.wait_for(_pc(port, running), 400))
        result = running.wait(timeout=60)

    print("report:", report)
    print("modes:", w.modes)
    print("messages received:", dict(sorted(w.counts.items())))
    print("driver:", [line for line in result.lines if line.startswith(("KB_SCENE", "KB_SCREEN_PUSHED", "KB_END",
                                                                         "KB_QUIT"))])

    assert not w.mc.conn.problems
    assert not w.errors, w.errors
    assert report["interact_kind"] == "hidden" and report["rows"] >= 3
    assert report["held"] == {"mode": "busy", "pcHeld": True, "opens": 1, "menu_open": True}
    assert report["stale"] == {"pcHeld": True, "stale": 1}, "only the open's own id ends the hold"
    assert report["released"] == {"pcHeld": False, "closed": 1, "top_is_menu": True}
    assert report["player_pc"]["back_to_pc_menu"]
    assert report["after"] == {"opens": 1, "unexpected": 0, "native": 0, "taken": 1, "mode": "idle"}
    assert result.marker("KB_SCREEN_PUSHED id=BoxMenu") is None, "never the Gen 1 box screens"
    assert result.marker("KB_SCREEN_PUSHED id=PlayerPC") is not None, "the item PC stays native"
    assert not result.timed_out and result.returncode == 0, result.summary()
    assert result.marker("KB_QUIT") is not None and result.marker("KB_FAIL") is None
    assert not result.crash_log_touched
    assert not result.error_spots


# ------------------------------------------------------------ the Mart


async def _mart(port, running):
    w = await connect(port)
    mc = w.mc
    report = {}
    clerk = await scene(w, running, "mart")
    st0 = await w.status()
    result = await w.act("act.interact", {"map": MART, "x": int(clerk["tx"]), "y": int(clerk["ty"])})
    assert result.get("ok") is True, result
    menu_id, menu = await w.top_is("menu", timeout=30, screen="ShopMenu")
    md = menu["data"]
    report["menu"] = {"rows": len(md["items"]), "money_ok": md.get("money") == st0["money"],
                      "footer": "footer" in md}
    first_footer = md.get("footer")

    # BUY: B at the quantity box, NO at the confirm, then a purchase of 2
    await w.pick(menu_id, 0)
    list_id, lst = await w.top_is("list", timeout=30)
    report["buy_list"] = {"money_ok": lst["data"].get("money") == st0["money"], "rows": len(lst["data"]["items"]),
                          "priced": all("right" in it for it in lst["data"]["items"][:-1])}
    await w.pick(list_id, 0)
    qty_id, qty = await w.top_is("quantity")
    unit = qty["data"].get("unit")
    await mc.send("ui.back", {"ui": qty_id})
    await w.top_is("list")
    await w.pick(list_id, 0)
    qty_id, _ = await w.top_is("quantity")
    await mc.send("ui.qty", {"ui": qty_id, "qty": 2})
    await w.drive(lambda: w.top()[0] == list_id, timeout=60, choices=[1])   # NO
    mid = await w.status()
    report["cancels_change_nothing"] = (mid["money"], mid["bagTotal"]) == (st0["money"], st0["bagTotal"])
    await w.pick(list_id, 0)
    qty_id, _ = await w.top_is("quantity")
    reopened_before = w.reopened["list"]
    await mc.send("ui.qty", {"ui": qty_id, "qty": 2})
    await w.drive(lambda: w.top()[0] == list_id and qty_id not in w.open_uis(), timeout=60, choices=[0])   # YES
    await asyncio.sleep(0.5)
    await w.pump(0.2)
    bought = await w.status()
    spent_ok = unit is not None and st0["money"] - bought["money"] == 2 * unit
    first = clerk.get("first")
    report["first_item"] = first
    report["bought"] = {"spent_twice_the_unit": spent_ok, "bag_up_2": bought["bagTotal"] == st0["bagTotal"] + 2,
                        "list_reopened": w.reopened["list"] > reopened_before,
                        "list_money_ok": w.uis[list_id]["data"].get("money") == bought["money"],
                        # a battle item (PLAN Appendix B) shows in bag.sync too
                        "bag_sync": first not in BATTLE_ITEMS or w.bag_syncs[-1].get(first) == 2}
    # "anything else?": back to the menu with the clerk's new line
    await mc.send("ui.back", {"ui": list_id})
    await w.until(lambda: w.top()[0] == menu_id, 30, "the shop menu again")
    await asyncio.sleep(0.3)
    await w.pump(0.2)
    report["anything_else"] = {"footer_changed": w.uis[menu_id]["data"].get("footer") != first_footer,
                               "money_ok": w.uis[menu_id]["data"].get("money") == bought["money"]}

    # SELL one POTION (first in the bag)
    await w.pick(menu_id, 1)
    sell_id, sell = await w.top_is("list", timeout=30)
    await w.pick(sell_id, 0)
    qty_id, sq = await w.top_is("quantity")
    sell_unit = sq["data"].get("unit")
    report["sell_max"] = sq["data"].get("max")
    await mc.send("ui.qty", {"ui": qty_id, "qty": 1})
    await w.drive(lambda: w.top()[0] == sell_id and qty_id not in w.open_uis(), timeout=60, choices=[0])
    await asyncio.sleep(0.5)
    await w.pump(0.2)
    sold = await w.status()
    report["sold"] = {"paid_the_unit": sell_unit is not None and sold["money"] - bought["money"] == sell_unit,
                      "bag_down_1": sold["bagTotal"] == bought["bagTotal"] - 1,
                      "potions_2": w.bag_syncs[-1].get("POTION") == 2}
    # QUIT
    await mc.send("ui.back", {"ui": sell_id})
    await w.until(lambda: w.top()[0] == menu_id, 30, "the shop menu again")
    await w.pick(menu_id, 2)
    await w.drive(lambda: w.mode == "idle" and not w.open_uis(), timeout=60)
    await w.idle()
    end = await w.status()
    report["end"] = {"mode": end["mode"], "reopened": end["uiReopened"] >= 2}
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return w, report


@pytest.mark.e2e
@pytest.mark.timeout(600)
def test_the_mart_buy_sell_and_cancel():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    port = PORTS["mart"]
    with launch("kb_viridian.lua", extra_env={"KB_TEST_PORT": str(port), "KB_SCENE": "mart"}) as running:
        marker = running.wait_marker("KB_SCENE scene=mart", timeout=180)
        assert marker is not None, running.result.summary()
        w, report = asyncio.run(asyncio.wait_for(_mart(port, running), 400))
        result = running.wait(timeout=60)

    print("report:", report)
    print("ui kinds opened:", dict(Counter(kind for event, kind in w.sequence if event == "open")))
    print("reopened:", dict(w.reopened))
    print("messages received:", dict(sorted(w.counts.items())))
    print("driver:", [line for line in result.lines if line.startswith(("KB_SCENE", "KB_SCREEN_PUSHED", "KB_END",
                                                                         "KB_QUIT"))])

    assert not w.mc.conn.problems
    assert not w.errors, w.errors
    assert report["menu"] == {"rows": 3, "money_ok": True, "footer": True}
    assert report["buy_list"]["money_ok"] and report["buy_list"]["priced"] and report["buy_list"]["rows"] >= 2
    assert report["cancels_change_nothing"]
    assert report["bought"] == {"spent_twice_the_unit": True, "bag_up_2": True, "list_reopened": True,
                                "list_money_ok": True, "bag_sync": True}
    assert report["anything_else"] == {"footer_changed": True, "money_ok": True}
    assert report["sell_max"] == 3
    assert report["sold"] == {"paid_the_unit": True, "bag_down_1": True, "potions_2": True}
    assert report["end"] == {"mode": "idle", "reopened": True}
    assert result.marker("KB_SCREEN_PUSHED id=ShopMenu") is not None
    assert not result.timed_out and result.returncode == 0, result.summary()
    assert result.marker("KB_QUIT") is not None and result.marker("KB_FAIL") is None
    assert not result.crash_log_touched
    assert not result.error_spots
