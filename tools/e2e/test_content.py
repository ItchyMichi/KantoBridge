"""M8 (wave 2) end to end, on the real engine: static encounters (a static object, the ghost Marowak,
Giovanni's hideout battle), gifts, in-game trades, the Name Rater, a gym leader and the daycare in the
Gen1Recomp test instance, driven by fake_minecraft as role `test` (docs/PLAN.md M8 items 6, 8, 9, 10
and 11).

The driver (drivers/kb_content.lua) starts a new game without the intro, sets up the party the scene
needs, beats the scene map's trainers (except the gym leader) and warps the player next to the
scene's object, facing it (found with the engine's own data and map scripts), chosen by KB_SCENE:

  static     a static Pokemon object (POWER_PLANT first; its talk script's static_battle):
             act.interact -> its text -> battle.start kind static with its dex, level and cell, rules
             catch+flee, the world held (busy) while the Gen 1 wild theme plays; a `caught` result
             hides the object (npc.remove, story.toggles visible false, its beat flag); the driver
             reloads the map: it stays gone
  ghost      one step from the ghost Marowak's trigger with the SILPH_SCOPE: the step -> its text
             -> battle.start kind static (pushed directly by the trigger's script: the direct-battle
             seam) with its dex and level, rules catch false, flee true; `win` -> its beat flag
  boss       Giovanni in the Rocket Hideout: act.interact -> his text -> battle.start kind scripted
             (pushed directly by his talk script), class OPP_GIOVANNI; `win` -> his beat flag, his
             object hidden
  gift       a talk script that gives a Pokemon (CELADON_MANSION_ROOF_HOUSE's ball first): its
             texts, the nickname prompt answered NO -> party.give source gift with the driver's dex
             and level; Cobblemon's party.sync naming the gift -> no duplicate, nothing pending
  trade      a talk script that trades: the party.sync gives the member a made-up uid; YES, the
             party menu (a mirror screen: A), the trade animation (mirror) -> party.take{uid,
             trade} then party.give source trade (dex and level as the trade says, with a nickname
             and an OT); Cobblemon's sync confirms it
  namerater  the Name Rater: YES, the party menu (A), YES, a made-up name typed on the naming
             screen -> party.rename{uid, nick} with its length
  gym        Brock: act.interact -> his text -> battle.start kind trainer, class OPP_BROCK, two
             members, BIDE (Gen 1 move 117) in the second one's moves, the gym theme playing; a
             `win` -> his badge lines as his loss line, with the badge jingle (M8 wave 3: through
             his encounter's own start, debug.status trainerStarts, lossLines, lossLineSounds),
             one more badge, one more beaten trainer, the prize and the TM in the bag
  daycare    the gentleman: YES, the party menu (A) -> daycare.deposit{uid}; three steps away and
             back -> talking again, the fee YES -> daycare.withdraw{uid, steps: 6}, money -100
  safari     a Safari game in the Safari Zone's grass (the driver makes the engine's catch.rate
             hook a sure catch): back and forth until a native Safari battle (battle.native start),
             A on the mirror throws the first SAFARI BALL, the nickname prompt NO, the battle ends
             (battle.native end) -> party.give source native_catch; Cobblemon's sync names it

In every scene: every message passes its schema (strict), no error, no native battle screen.
Data safety (docs/PLAN.md section 0.3): only types, kinds, counts, booleans, dex numbers, levels and
map/class symbols are printed or compared; text is never looked at.
"""

import asyncio
import time

import pytest

from fake_minecraft import FakeMinecraft
from run_gen1recomp import launch, preflight
from story import StoryWalker
import foreign  # noqa: E402  (FP8: KB_E2E_FOREIGN=1, an all-foreign team)

PORTS = {"static": 27182, "gift": 27181, "trade": 27180, "namerater": 27179, "gym": 27178, "daycare": 27177,
         "ghost": 27176, "boss": 27175, "safari": 27174}
OPPOSITE = {"up": "down", "down": "up", "left": "right", "right": "left"}
U1 = "0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b"
U2 = "6c1e2d3f-4a5b-4c6d-8e7f-a0b1c2d3e4f5"
U3 = "11111111-2222-4333-8444-555555555555"
TACKLE, BIDE = 33, 117   # Gen 1 move ids (= Showdown numbers)


class ContentWalker(StoryWalker):
    """StoryWalker plus what M8's wave 2 sends: takes, renames, the daycare, removals, toggles."""

    def __init__(self, mc):
        super().__init__(mc)
        self.takes, self.renames, self.deposits, self.withdraws = [], [], [], []
        self.removed, self.toggles, self.npc_lists = [], [], []
        self.flags_set = set()      # flag symbols story.flags reported set
        self.natives = []           # battle.native data

    def handle(self, m):
        super().handle(m)
        t, d = m["type"], m["data"]
        if t == "party.take":
            self.takes.append(d)
        elif t == "party.rename":
            self.renames.append(d)
        elif t == "daycare.deposit":
            self.deposits.append(d)
        elif t == "daycare.withdraw":
            self.withdraws.append(d)
        elif t == "npc.remove":
            self.removed.append(d["id"])
        elif t == "story.toggles":
            self.toggles.append(d)
        elif t == "story.flags":
            self.flags_set.update(d["set"])
        elif t == "battle.native":
            self.natives.append(d)
        elif t == "npc.set":
            self.npc_lists.append((d["map"], [n["id"] for n in d["list"]]))

    def arrivals(self):
        return [x for x in self.warps if x["phase"] == "arrive"]

    async def quiet_ui(self, timeout=120, choices=(), names=(), mirror_button=None, grace=1.0):
        """Plays the UIs until the idle mode with nothing open (the first `grace` seconds never
        count, so texts that follow an act.result have time to open)."""
        start = time.monotonic()
        await self.drive(lambda: time.monotonic() - start >= grace and self.mode == "idle" and not self.open_uis(),
                         timeout=timeout, choices=choices, names=names, mirror_button=mirror_button)
        await self.settle(timeout=timeout)

    async def status_until(self, pred, what, timeout=20):
        deadline = time.monotonic() + timeout
        while True:
            st = await self.status()
            if pred(st):
                return st
            if time.monotonic() > deadline:
                raise AssertionError(f"timed out waiting for {what}")
            await asyncio.sleep(0.3)


def entry(uid, dex, level, moves=(TACKLE,), **kw):
    e = {"uid": uid, "dex": dex, "level": level, "moves": list(moves), "hp": 30, "maxhp": 30}
    e.update(kw)
    return e


async def party_sync(w, party):
    await w.mc.send("party.sync", {"party": foreign.party(party), "box": {"count": 0}})


def scene_facts(marker):
    return dict(part.split("=", 1) for part in marker.split()[1:] if "=" in part)


LAST = {}  # the walker of the flow under way, for the failure report


async def _connect(port):
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=20)
    await mc.hello()
    w = ContentWalker(mc)
    LAST["w"] = w
    await w.until(lambda: w.pos is not None, 20, "the snapshot")
    await w.settle()
    return w


async def _finish(w):
    status = await w.status()
    await w.mc.send("debug.quit", {"code": 0})
    await asyncio.sleep(0.5)
    await w.mc.close()
    return status


async def _talk(w, scene):
    return await w.act("act.interact", {"map": scene["map"], "x": int(scene["ox"]), "y": int(scene["oy"])},
                       timeout=20)


async def _static(port, scene):
    w = await _connect(port)
    report = {"interact": await _talk(w, scene)}
    await w.drive(lambda: len(w.battles) >= 1, timeout=60)
    b = w.battles[-1]
    wild = b.get("wild", {})
    report["kind"] = b["kind"]
    report["wild_matches"] = (wild.get("dex") == int(scene["dex"]), wild.get("level") == int(scene["level"]),
                              (wild.get("x"), wild.get("y")) == (int(scene["ox"]), int(scene["oy"])))
    report["rules"] = b["rules"]
    await w.until(lambda: w.mode == "busy", 10, "the busy mode")
    await w.mc.send("battle.result", {"battle": b["battle"], "outcome": "caught",
                                      "caught": {"uid": U1, "dex": wild["dex"], "level": wild["level"]}})
    await w.until(lambda: scene["npc"] in w.removed, 30, "the object's npc.remove")
    await w.settle()
    report["toggle_hidden"] = any(t["map"] == scene["map"] and t["visible"] is False for t in w.toggles)
    # the driver reloads the map once the engine marked the object beaten
    await w.until(lambda: len(w.arrivals()) >= 1, 60, "the map reload")
    await w.settle()
    lists = [ids for m, ids in w.npc_lists if m == scene["map"]]
    report["after_reload"] = scene["npc"] in lists[-1] if lists else None
    report["status"] = await _finish(w)
    return w, report


async def _ghost(port, scene):
    w = await _connect(port)
    report = {}
    first = await w.answer(await w.step(scene["facing"]))
    report["step"] = (first["type"], first["data"].get("x") == int(scene["ox"]),
                      first["data"].get("y") == int(scene["oy"]))
    await w.drive(lambda: len(w.battles) >= 1, timeout=60)
    b = w.battles[-1]
    wild = b.get("wild", {})
    report["kind"] = b["kind"]
    report["wild_matches"] = (wild.get("dex") == int(scene["dex"]), wild.get("level") == int(scene["level"]))
    report["rules"] = b["rules"]
    await w.until(lambda: w.mode == "busy", 10, "the busy mode")
    await w.mc.send("battle.result", {"battle": b["battle"], "outcome": "win"})
    await w.quiet_ui(timeout=120)
    await w.until(lambda: "EVENT_BEAT_GHOST_MAROWAK" in w.flags_set, 30, "the ghost's beat flag")
    report["beaten"] = True
    report["status"] = await _finish(w)
    return w, report


async def _boss(port, scene):
    w = await _connect(port)
    report = {"interact": await _talk(w, scene)}
    await w.drive(lambda: len(w.battles) >= 1, timeout=90)
    b = w.battles[-1]
    report["battle"] = (b["kind"], b["trainer"]["cls"], b["trainer"]["party"], b["trainer"].get("npc") == scene["npc"],
                        1 <= len(b.get("team", [])) <= 6)
    report["rules"] = b["rules"]
    await w.until(lambda: w.mode == "busy", 10, "the busy mode")
    before = await w.status()
    await w.mc.send("battle.result", {"battle": b["battle"], "outcome": "win"})
    await w.quiet_ui(timeout=180)
    await w.until(lambda: "EVENT_BEAT_ROCKET_HIDEOUT_GIOVANNI" in w.flags_set, 30, "his beat flag")
    after = await w.status()
    report["removed"] = scene["npc"] in w.removed
    report["money_up"] = after["money"] > before["money"]
    report["defeated_up"] = after["defeated"] == before["defeated"] + 1
    report["status"] = after
    await _finish(w)
    return w, report


async def _gift(port, scene):
    w = await _connect(port)
    report = {"interact": await _talk(w, scene)}
    await w.drive(lambda: len(w.gives) >= 1, timeout=90, choices=[1])   # the nickname prompt: NO
    await w.quiet_ui(choices=[1])
    give = w.gives[-1]
    report["give"] = (give["source"], give["dex"] == int(scene["dex"]), give["level"] == int(scene["level"]))
    lead = entry(U1, 7, 20)
    await party_sync(w, [lead, entry(U2, give["dex"], give["level"], give.get("moves", [TACKLE]), give=give["give"])])
    st = await w.status_until(lambda s: s["partyKnown"] == 2 and s["giftsPending"] == 0, "the gift confirmed")
    report["party"] = (st["partySize"], st["partyKnown"], st["giftsPending"])
    report["status"] = await _finish(w)
    return w, report


async def _trade(port, scene):
    w = await _connect(port)
    givedex, getdex, level = int(scene["givedex"]), int(scene["getdex"]), int(scene["level"])
    await party_sync(w, [entry(U1, givedex, level)])
    await w.status_until(lambda s: s["partyKnown"] == 1, "the member's uid")
    report = {"interact": await _talk(w, scene)}
    await w.drive(lambda: w.takes and any(g["source"] == "trade" for g in w.gives), timeout=180, choices=[0],
                  mirror_button="a")
    await w.quiet_ui(choices=[0], mirror_button="a")
    give = [g for g in w.gives if g["source"] == "trade"][-1]
    report["takes"] = w.takes
    report["give"] = (give["dex"] == getdex, give["level"] == level, len(give.get("ot", "")) > 0,
                      len(give.get("nick", "")) > 0)
    await party_sync(w, [entry(U3, getdex, level, give.get("moves", [TACKLE]), give=give["give"])])
    st = await w.status_until(lambda s: s["opsPending"] == 0 and s["giftsPending"] == 0, "the trade confirmed")
    report["party"] = (st["partySize"], st["partyKnown"], st["trades"])
    report["status"] = await _finish(w)
    return w, report


async def _namerater(port, scene):
    w = await _connect(port)
    await party_sync(w, [entry(U1, int(scene["dex"]), int(scene["level"]))])
    await w.status_until(lambda s: s["partyKnown"] == 1, "the member's uid")
    report = {"interact": await _talk(w, scene)}
    await w.drive(lambda: len(w.renames) >= 1, timeout=180, choices=[0, 0], names=[["A", "B", "C"]],
                  mirror_button="a")
    await w.quiet_ui(mirror_button="a")
    report["renames"] = [(r["uid"], len(r["nick"])) for r in w.renames]
    report["status"] = await _finish(w)
    return w, report


async def _gym(port, scene):
    w = await _connect(port)
    before = await w.status()
    report = {"interact": await _talk(w, scene)}
    await w.drive(lambda: len(w.battles) >= 1, timeout=90)
    b = w.battles[-1]
    team = b.get("team", [])
    report["battle"] = (b["kind"], b["trainer"]["cls"], b["trainer"]["party"], len(team))
    report["bide"] = len(team) >= 2 and BIDE in team[1]["moves"]
    report["rules"] = b["rules"]
    await w.until(lambda: w.mode == "busy", 10, "the busy mode")
    await w.mc.send("battle.result", {"battle": b["battle"], "outcome": "win"})
    await w.quiet_ui(timeout=180)
    after = await w.status()
    report["badges"] = (before["badges"], after["badges"])
    report["defeated"] = (before["defeated"], after["defeated"])
    report["money_up"] = after["money"] > before["money"]
    report["bag_kinds_up"] = after["bagKinds"] > before["bagKinds"]
    report["status"] = after
    await _finish(w)
    return w, report


async def _daycare(port, scene):
    w = await _connect(port)
    dex1, dex2, level = int(scene["dex1"]), int(scene["dex2"]), int(scene["level"])
    await party_sync(w, [entry(U1, dex1, level), entry(U2, dex2, level)])
    await w.status_until(lambda s: s["partyKnown"] == 2, "the members' uids")
    report = {"interact": await _talk(w, scene)}
    await w.drive(lambda: len(w.deposits) >= 1, timeout=180, choices=[0], mirror_button="a")
    await w.quiet_ui(mirror_button="a")
    report["deposits"] = w.deposits
    await party_sync(w, [entry(U2, dex2, level)])         # Cobblemon's: the server holds U1
    st = await w.status_until(lambda s: s["opsPending"] == 0, "the deposit confirmed")
    report["held"] = (st["daycareHeld"], st["partySize"])
    walk = scene["walk"]
    walked = 0
    for _ in range(3):
        for d in (walk, OPPOSITE[walk]):
            a = await w.answer(await w.step(d))
            walked += 1 if a["type"] == "player.pos" else 0
            await w.idle()
    report["walked"] = walked
    money = (await w.status())["money"]
    await _talk(w, scene)
    await w.drive(lambda: len(w.withdraws) >= 1, timeout=180, choices=[0])
    await w.quiet_ui(choices=[0])
    report["withdraws"] = w.withdraws
    st = await w.status()
    report["money_paid"] = money - st["money"]
    await party_sync(w, [entry(U2, dex2, level), entry(U1, dex1, level)])
    st = await w.status_until(lambda s: s["opsPending"] == 0, "the withdrawal confirmed")
    report["party"] = (st["partySize"], st["partyKnown"], st["daycareHeld"])
    report["status"] = await _finish(w)
    return w, report


async def _safari(port, scene):
    w = await _connect(port)
    walk = scene["walk"]
    d = walk
    deadline = time.monotonic() + 420

    def caught():
        return any(g["source"] == "native_catch" for g in w.gives)

    steps = 0
    while not caught():
        if time.monotonic() > deadline:
            raise AssertionError("no native catch")
        if w.mode == "idle" and not w.open_uis():
            await w.answer(await w.step(d), timeout=30)
            steps += 1
            d = OPPOSITE[walk] if d == walk else walk
            await w.pump(0.2)
        else:
            # a native Safari battle (a mirror screen): A throws the first SAFARI BALL and moves its
            # messages on; the nickname prompt is answered NO
            await w.drive(lambda: caught() or (w.mode == "idle" and not w.open_uis()), timeout=180, choices=[1],
                          mirror_button="a")
    await w.quiet_ui(choices=[1], mirror_button="a")
    give = [g for g in w.gives if g["source"] == "native_catch"][-1]
    natives = [(n["kind"], n["phase"]) for n in w.natives]
    report = {"natives": natives[-2:], "steps_walked": steps,
              "give": (1 <= give["dex"] <= 151, 1 <= give["level"] <= 100, len(give.get("moves", [])) >= 1,
                       set(give.get("dvs", {})) == {"atk", "def", "spd", "spc"})}
    await party_sync(w, [entry(U1, int(scene["lead"]), 10),
                         entry(U2, give["dex"], give["level"], give.get("moves", [TACKLE]), give=give["give"])])
    st = await w.status_until(lambda s: s["giftsPending"] == 0 and s["partyKnown"] == 2, "the catch confirmed")
    report["party"] = (st["partySize"], st["partyKnown"], st["catchesReleased"])
    report["status"] = await _finish(w)
    return w, report


FLOWS = {"static": _static, "gift": _gift, "trade": _trade, "namerater": _namerater, "gym": _gym,
         "daycare": _daycare, "ghost": _ghost, "boss": _boss, "safari": _safari}


@pytest.mark.e2e
@pytest.mark.timeout(900)
@pytest.mark.parametrize("scene_name", ["static", "ghost", "boss", "gift", "trade", "namerater", "gym", "daycare",
                                        "safari"])
def test_content_scene(scene_name):
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    if foreign.FOREIGN and scene_name == "trade":
        pytest.skip("FP8: an in-game trade asks for its own species, which an all-foreign party never has")
    port = PORTS[scene_name]
    with launch("kb_content.lua", extra_env={"KB_TEST_PORT": str(port), "KB_SCENE": scene_name}) as running:
        marker = running.wait_marker("KB_SCENE", timeout=180)
        assert marker is not None, running.result.summary()
        scene = scene_facts(marker)
        assert scene["scene"] == scene_name and scene["port"] == str(port)
        try:
            w, report = asyncio.run(asyncio.wait_for(FLOWS[scene_name](port, scene), 600))
        except BaseException:
            w = LAST.get("w")
            if w is not None:  # symbols and counts only
                print("failed at: mode", w.mode, "open uis", [u["kind"] for u in w.open_uis().values()])
                print("received:", dict(sorted(w.counts.items())))
            raise
        result = running.wait(timeout=60)

    status = report.pop("status")
    print("scene:", scene_name, "map:", scene["map"])
    print("report:", report)
    print("received:", dict(sorted(w.counts.items())))
    print("status:", {k: status.get(k) for k in ("directTaken", "directArmed", "battleStatic", "battleTrainer",
                                                  "trades", "partyTakes", "partyRenames", "partyDeposits",
                                                  "partyWithdraws", "opsPending", "musicBattle", "musicVictory",
                                                  "badges", "defeated", "trainerStarts", "lossLines",
                                                  "lossLineSounds")})
    print("driver:", [line for line in result.lines if line.startswith(("KB_HELD", "KB_BEATEN", "KB_BACK", "KB_END",
                                                                         "KB_BATTLE_PUSHED", "KB_QUIT"))])

    assert not w.mc.conn.problems  # strict: every message passed its schema
    assert not w.errors, w.errors
    if "interact" in report:
        assert report["interact"]["ok"] is True, report["interact"]
    held = result.marker("KB_HELD")
    if scene_name == "static":
        assert report["kind"] == "static" and report["wild_matches"] == (True, True, True)
        assert report["rules"] == {"catch": True, "flee": True}
        assert report["toggle_hidden"], "story.toggles reported the object hidden"
        assert report["after_reload"] is False, "the beaten object is not in the reloaded map's NPCs"
        assert result.marker("KB_BACK").endswith("present=false")
        assert status["battleStatic"] == 1, "its talk script's static_battle"
        assert held is not None and not held.endswith("music=false"), "the Gen 1 wild theme while held"
    elif scene_name == "ghost":
        assert report["step"] == ("player.pos", True, True)
        assert report["kind"] == "static" and report["wild_matches"] == (True, True)
        assert report["rules"] == {"catch": False, "flee": True}, "the RESTLESS SOUL can't be caught"
        assert report["beaten"]
        assert status["directTaken"] == 1 and status["directArmed"] >= 1
        assert held is not None and not held.endswith("music=false")
    elif scene_name == "boss":
        assert report["battle"] == ("scripted", "OPP_GIOVANNI", 1, True, True)
        assert report["rules"] == {"catch": False, "flee": False}
        assert report["removed"], "his aftermath hid him"
        assert report["money_up"] and report["defeated_up"]
        assert status["directTaken"] == 1
        assert held is not None and not held.endswith("music=false")
    elif scene_name == "gift":
        assert report["give"] == ("gift", True, True)
        assert report["party"] == (2, 2, 0), "Cobblemon's sync took the gift: no duplicate"
        assert len(w.battles) == 0
    elif scene_name == "trade":
        assert report["takes"] == [{"uid": U1, "reason": "trade"}]
        assert report["give"] == (True, True, True, True)
        assert report["party"] == (1, 1, 1)
        assert len(w.battles) == 0
    elif scene_name == "namerater":
        assert report["renames"] == [(U1, 3)]
        assert len(w.battles) == 0
    elif scene_name == "gym":
        assert report["battle"] == ("trainer", "OPP_BROCK", 1, 2)
        assert report["bide"], "the special-move table: BIDE in the leader's second Pokemon"
        assert report["rules"] == {"catch": False, "flee": False}
        assert report["badges"][1] == report["badges"][0] + 1
        assert report["defeated"][1] == report["defeated"][0] + 1
        assert report["money_up"] and report["bag_kinds_up"], "the prize and the TM"
        assert held is not None and not held.endswith("music=false"), "the Gen 1 gym theme while held"
        assert status["musicBattle"] >= 1
        # M8 wave 3: the badge lines his talk hands engageTrainer are his loss line, with the jingle
        assert (status["trainerStarts"], status["lossLines"], status["lossLineSounds"]) == (1, 1, 1)
    elif scene_name == "daycare":
        assert report["deposits"] == [{"uid": U1}]
        assert report["held"] == (True, 1)
        assert report["walked"] == 6
        assert report["withdraws"] == [{"uid": U1, "steps": 6}]
        assert report["money_paid"] == 100
        assert report["party"] == (2, 2, False)
    elif scene_name == "safari":
        assert report["natives"] == [("safari", "start"), ("safari", "end")]
        assert report["give"] == (True, True, True, True)
        assert report["party"] == (2, 2, 1), "the engine's own caught member kept, named by the sync"
        assert len(w.battles) == 0, "the Safari battle stays native"
    if scene_name != "safari":
        assert result.marker("KB_BATTLE_PUSHED") is None
    assert not result.timed_out and result.returncode == 0, result.summary()
    assert result.marker("KB_QUIT") is not None and result.marker("KB_FAIL") is None
    assert not result.crash_log_touched
    assert not result.error_spots
