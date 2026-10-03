"""M8 (wave 3) end to end, on the real engine: the Elite Four, the Champion with the Hall of Fame and
the credits, the Snorlax flute wake, and a loss line a trainer's script hands its battle (the Game
Corner rocket) in the Gen1Recomp test instance, driven by fake_minecraft as role `test`
(docs/PLAN.md M8 items 9 and 11).

The driver (drivers/kb_endgame.lua) starts a new game without the intro, gives the player one
level-100 party member and REPEL steps, sets the scene's flags, badges (all eight for the league) and
bag, and warps the player next to the scene (found with the engine's own data and map queries),
chosen by KB_SCENE:

  e4        one step from the lobby's door into LORELEIS_ROOM: the step (a push against the edge if
            it needs one) -> warp arrive LORELEIS_ROOM -> the room's own walk-in (scripted steps)
            -> idle; the driver's way to a cell beside Lorelei (KB_TALK_ROUTE, directions only), her
            cell from the room's npc.set, act.interact -> her text -> battle.start (kind trainer,
            class OPP_LORELEI) -> `win` -> the loss line, the prize and her after-battle text; her
            room's exit opens (story.blocks for LORELEIS_ROOM, counted); the driver's way onto the
            exit (KB_EXIT_ROUTE) -> warp arrive BRUNOS_ROOM and its walk-in; no party.heal from her
            battle to the next room
  champion  one step from Lance's door into CHAMPIONS_ROOM (Lance beaten): the step -> warp arrive
            -> the rival's entrance and text -> battle.start (kind rival, class OPP_RIVAL3, a team
            of 6) -> `win` -> his aftermath, Oak, the walk into HALL_OF_FAME, the induction:
            party.heal, the Hall of Fame and the credits as mirror UIs (counted; A on each mirror
            about once a second, the driver runs the credits at 10x), save.begin reason menu
            (answered save.done ok) -> the game leaves the world for the title: a resync's snapshot
            says ingame false, the driver reports the end state (KB_END_STATE)
  rocket    facing the Game Corner rocket, whose talk hands his battle his own loss line (M8 wave 3):
            act.interact -> his text -> battle.start kind trainer -> `win` -> his loss line shown
            (debug.status lossLines, through his encounter's own start: trainerStarts), the prize, his
            after-battle text, his exit (npc.remove), one more beaten trainer
  snorlax   beside a sleeping Snorlax, the POKE_FLUTE the only item in the bag: START, ITEM, the
            flute (the bag's first row), USE (both rows matched in memory against the engine's own
            labels) -> its texts -> npc.remove (the wake-up hides it) -> battle.start kind static
            (dex and level in range, its cell the Snorlax's) -> `aborted` -> Snorlax shown again
            (npc.add for its id, story.toggles visible true, counted) -> the flute again -> a new
            battle.start -> `caught` -> gone (npc.remove, story.toggles visible false, its beat flag);
            the driver reloads the map: it stays gone (KB_BACK present=false)

In every scene: every message passes its schema (strict), no error, no native battle screen.
Data safety (docs/PLAN.md section 0.3): only types, kinds, counts, booleans and map/class/screen
symbols are printed or compared; text, species, teams and coordinates are never printed (cells are
compared in memory only).
"""

import asyncio
import time
from collections import Counter

import pytest

from fake_minecraft import FakeMinecraft
from run_gen1recomp import launch, preflight
from story import StoryWalker

PORTS = {"e4": 27173, "champion": 27172, "snorlax": 27171, "rocket": 27170}
U1 = "5a1d2c3b-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
END_SCREENS = {"IntroMovie", "TitleState", "CopyrightSplash"}


class EndgameWalker(StoryWalker):
    """StoryWalker plus what the endgame scenes look at: story diffs, saves (answered ok at once),
    NPC adds and removes by id, the snapshot's ingame flag."""

    def __init__(self, mc):
        super().__init__(mc)
        self.blocks = []          # (map, number of changes) of every story.blocks
        self.toggles = []         # story.toggles data
        self.flags_set = set()    # flag symbols story.flags reported set
        self.saves = []           # save.begin data
        self.npc_events = []      # ("npc.add" | "npc.remove", id)
        self.mirrors = []         # (ui id, screen) of every mirror UI opened
        self.ingame = None        # the last snapshot's ingame
        self.timeline = []        # (type, detail) of warps, heals, saves and battles, in order

    def handle(self, m):
        super().handle(m)
        t, d = m["type"], m["data"]
        if t == "story.blocks":
            self.blocks.append((d["map"], len(d["changes"])))
        elif t == "story.toggles":
            self.toggles.append(d)
        elif t == "story.flags":
            self.flags_set.update(d["set"])
        elif t == "save.begin":
            self.saves.append(d)
            asyncio.get_running_loop().create_task(self.mc.send("save.done", {"save": d["save"], "ok": True}))
        elif t in ("npc.add", "npc.remove"):
            self.npc_events.append((t, d["id"]))
        elif t == "ui.open" and d["kind"] == "mirror":
            self.mirrors.append((d["ui"], d.get("screen")))
        elif t == "snapshot":
            self.ingame = d["ingame"]
        if t in ("warp", "party.heal", "save.begin", "battle.start", "battle.native"):
            self.timeline.append((t, d.get("phase") or d.get("reason") or d.get("kind")))

    def arrivals(self, to=None):
        return [x for x in self.warps if x["phase"] == "arrive" and (to is None or x["to"]["map"] == to)]

    def mark(self):
        return len(self.timeline)

    def since(self, mark, msg_type):
        return [detail for t, detail in self.timeline[mark:] if t == msg_type]

    async def quiet_ui(self, timeout=120, choices=(), mirror_button=None, grace=1.0):
        """Plays the UIs until the idle mode with nothing open (the first `grace` seconds never
        count, so texts that follow an act.result have time to open)."""
        start = time.monotonic()
        await self.drive(lambda: time.monotonic() - start >= grace and self.mode == "idle" and not self.open_uis(),
                         timeout=timeout, choices=choices, mirror_button=mirror_button)
        await self.settle(timeout=timeout)

    async def enter(self, direction, dest, timeout=30):
        """A step onto a warp (a push against the edge if it needs one), until arriving in dest."""
        await self.answer(await self.step(direction), timeout=15)
        try:
            await self.until(lambda: self.arrivals(dest), 8, "a warp")
            return False
        except AssertionError:
            await self.answer(await self.step(direction, push=True), timeout=15)
            await self.until(lambda: self.arrivals(dest), timeout, f"the warp into {dest}")
            return True

    async def follow_route(self, dirs):
        """The driver's route, step by step (each answered by a player.pos)."""
        for d in dirs:
            a = await self.answer(await self.step(d), timeout=15)
            assert a["type"] == "player.pos", f"a route step was refused: {a['data'].get('reason')}"
        await self.idle()

    async def pick_label(self, ui_id, label):
        """ui.pick on the row whose label is `label` (an engine label, compared in memory)."""
        labels = [it["label"] for it in self.uis[ui_id]["data"]["items"]]
        assert label in labels, f"the menu has no {len(label)}-letter engine row"
        await self.mc.send("ui.pick", {"ui": ui_id, "index": labels.index(label)})


def facts(line):
    return dict(part.split("=", 1) for part in line.split()[1:] if "=" in part)


async def driver_marker(w, running, name, timeout=90):
    """The facts of the driver's first `name` marker, pumping messages meanwhile."""
    deadline = time.monotonic() + timeout
    while running.result.marker(name) is None:
        if time.monotonic() > deadline:
            raise AssertionError(f"timed out waiting for the driver's {name}")
        await w.pump(0.2)
    return facts(running.result.marker(name))


LAST = {}  # the walker of the flow under way, for the failure report


async def _connect(port):
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=20)
    await mc.hello()
    w = EndgameWalker(mc)
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


async def _e4(port, scene, running):
    w = await _connect(port)
    report = {}
    # 1. into Lorelei's room: the room walks the player in by itself
    script_before = len(w.script_frames)
    report["pushed_in"] = await w.enter(scene["walk"], "LORELEIS_ROOM")
    await w.settle()
    report["walk_in_steps"] = len(w.script_frames) - script_before
    # 2. Lorelei: a talk (unless she starts it by sight)
    if not w.battles:
        route = await driver_marker(w, running, "KB_TALK_ROUTE")
        await w.follow_route([] if route["dirs"] == "none" else route["dirs"].split(","))
        ox, oy = w.npc_cells[scene["npc"]]
        report["interact"] = await w.act("act.interact", {"map": "LORELEIS_ROOM", "x": ox, "y": oy}, timeout=20)
    await w.drive(lambda: len(w.battles) >= 1, timeout=90)
    b = w.battles[-1]
    report["battle"] = (b["kind"], b["trainer"]["cls"])
    await w.until(lambda: w.mode == "busy", 10, "the busy mode")
    mark = w.mark()
    before = await w.status()
    blocks_before = len(w.blocks)
    await w.mc.send("battle.result", {"battle": b["battle"], "outcome": "win"})
    await w.quiet_ui(timeout=180)
    after = await w.status()
    report["door_blocks"] = sum(1 for m, n in w.blocks[blocks_before:] if m == "LORELEIS_ROOM" and n >= 1)
    report["beaten"] = "EVENT_BEAT_LORELEIS_ROOM_TRAINER_0" in w.flags_set
    report["defeated_up"] = after["defeated"] == before["defeated"] + 1
    report["money_up"] = after["money"] > before["money"]
    report["loss_lines"] = after["lossLines"] - before["lossLines"]
    report["starts"] = after["trainerStarts"] - before["trainerStarts"]
    # 3. through the open door into the next room
    route = await driver_marker(w, running, "KB_EXIT_ROUTE")
    dirs = route["dirs"].split(",")
    assert route["dirs"] != "none" and dirs, "a way onto the exit"
    await w.follow_route(dirs[:-1])
    report["pushed_out"] = await w.enter(dirs[-1], "BRUNOS_ROOM")
    await w.settle()
    await driver_marker(w, running, "KB_NEXT_ROOM")
    report["heals_between"] = len(w.since(mark, "party.heal"))
    report["arrivals"] = [x["to"]["map"] for x in w.arrivals()]
    report["status"] = await _finish(w)
    return w, report


async def _champion(port, scene, running):
    w = await _connect(port)
    report = {}
    report["pushed_in"] = await w.enter(scene["walk"], "CHAMPIONS_ROOM")
    await w.drive(lambda: len(w.battles) >= 1, timeout=120)
    b = w.battles[-1]
    report["battle"] = (b["kind"], b["trainer"]["cls"], len(b.get("team", [])))
    await w.until(lambda: w.mode == "busy", 10, "the busy mode")
    mark = w.mark()
    mirrors_before = len(w.mirrors)
    await w.mc.send("battle.result", {"battle": b["battle"], "outcome": "win"})

    def credits_over():
        return any(s == "Credits" and w.uis[i]["closed"] for i, s in w.mirrors[mirrors_before:])

    # his aftermath, Oak, the walk, the induction and the credits: A at every text box and mirror
    await w.drive(credits_over, timeout=720, mirror_button="a")
    await w.until(lambda: any(s in END_SCREENS for _, s in w.mirrors[mirrors_before:]), 60, "the title")
    end = await driver_marker(w, running, "KB_END_STATE")
    await w.resync()
    screens = Counter(s for _, s in w.mirrors[mirrors_before:])
    report["hall"] = screens["HallOfFame"]
    report["credits"] = screens["Credits"]
    report["end_screen"] = any(s in END_SCREENS for s in screens)
    report["heals"] = len(w.since(mark, "party.heal"))
    report["saves"] = w.since(mark, "save.begin")
    report["arrivals"] = [x["to"]["map"] for x in w.arrivals()]
    report["flags"] = sorted(f for f in w.flags_set if f in ("EVENT_BEAT_CHAMPION_RIVAL",
                                                              "EVENT_BEAT_CHAMPION_RIVAL_THIS_RUN"))
    report["ingame"] = w.ingame
    report["end"] = {k: end.get(k) for k in ("overworld", "hall", "credits", "home")}
    report["status"] = await _finish(w)
    return w, report


async def _snorlax(port, scene, running):
    w = await _connect(port)
    report = {}
    npc = scene["npc"]
    cell = w.npc_cells.get(npc)
    assert cell is not None, "Snorlax is on the map"

    async def flute():
        menu = await w.start_menu()
        await w.pick_label(menu, "ITEM")
        bag = await w.opened("list", menu)
        await w.mc.send("ui.pick", {"ui": bag, "index": 0})        # the flute: the bag's only item
        use = await w.opened("menu", bag)
        await w.pick_label(use, "USE")
        n = len(w.battles)
        await w.drive(lambda: len(w.battles) > n, timeout=90)
        await w.until(lambda: w.mode == "busy", 10, "the busy mode")
        return w.battles[-1]

    def count(kind):
        return sum(1 for t, i in w.npc_events if t == kind and i == npc)

    first = await flute()
    wild = first.get("wild", {})
    report["first"] = (first["kind"], 1 <= wild.get("dex", 0) <= 151, 1 <= wild.get("level", 0) <= 100,
                       (wild.get("x"), wild.get("y")) == cell, first["rules"]["catch"])
    # the wake-up hid it before the battle: npc.remove at once, story.toggles on the story's next beat
    await w.until(lambda: any(t["visible"] is False for t in w.toggles), 15, "the hidden Snorlax's story.toggles")
    report["hidden_first"] = count("npc.remove")
    toggles_before = len(w.toggles)
    await w.mc.send("battle.result", {"battle": first["battle"], "outcome": "aborted"})
    await w.quiet_ui(timeout=60)
    await w.until(lambda: any(t["visible"] is True for t in w.toggles[toggles_before:]), 15,
                  "the shown Snorlax's story.toggles")
    report["back"] = count("npc.add")
    report["toggle_back"] = sum(1 for t in w.toggles[toggles_before:] if t["visible"] is True)
    # from the second flute on: its wake-up hides Snorlax again, and the catch keeps it hidden for good.
    # The story reports the hide on its next 10-step beat, which may come before or after battle.start
    # reaches the test (M8: toggles go out on the story's beat), so the check counts from here
    toggles_before = len(w.toggles)
    second = await flute()
    report["second_new_id"] = second["battle"] != first["battle"]
    report["second_kind"] = second["kind"]
    w2 = second.get("wild", {})
    await w.mc.send("battle.result", {"battle": second["battle"], "outcome": "caught",
                                      "caught": {"uid": U1, "dex": w2["dex"], "level": w2["level"]}})
    await w.quiet_ui(timeout=60)
    await w.until(lambda: any(t["visible"] is False for t in w.toggles[toggles_before:]), 15,
                  "the caught Snorlax's story.toggles")
    report["removed"] = count("npc.remove")
    since = w.toggles[toggles_before:]
    report["toggle_gone"] = sum(1 for t in since if t["visible"] is False)
    report["toggle_last_hidden"] = since[-1]["visible"] is False
    report["beat_flag"] = any(f.startswith("EVENT_BEAT_ROUTE") and f.endswith("_SNORLAX") for f in w.flags_set)
    # the driver reloads the map once the engine marked it beaten
    await w.until(lambda: len(w.arrivals()) >= 1, 60, "the map reload")
    await w.settle()
    report["status"] = await _finish(w)
    return w, report


async def _rocket(port, scene, running):
    w = await _connect(port)
    report = {}
    before = await w.status()
    ox, oy = w.npc_cells[scene["npc"]]
    report["interact"] = await w.act("act.interact", {"map": scene["map"], "x": ox, "y": oy}, timeout=20)
    await w.drive(lambda: len(w.battles) >= 1, timeout=90)
    b = w.battles[-1]
    report["battle"] = (b["kind"], b["trainer"]["cls"] == scene["cls"], b["trainer"].get("npc") == scene["npc"])
    await w.until(lambda: w.mode == "busy", 10, "the busy mode")
    texts_before = sum(1 for u in w.uis.values() if u["kind"] == "text")
    await w.mc.send("battle.result", {"battle": b["battle"], "outcome": "win"})
    await w.quiet_ui(timeout=180)
    after = await w.status()
    report["texts_after"] = sum(1 for u in w.uis.values() if u["kind"] == "text") - texts_before
    report["loss_lines"] = after["lossLines"] - before["lossLines"]
    report["starts"] = after["trainerStarts"] - before["trainerStarts"]
    report["defeated_up"] = after["defeated"] == before["defeated"] + 1
    report["money_up"] = after["money"] > before["money"]
    report["left"] = ("npc.remove", scene["npc"]) in w.npc_events
    report["status"] = await _finish(w)
    return w, report


FLOWS = {"e4": _e4, "champion": _champion, "snorlax": _snorlax, "rocket": _rocket}


@pytest.mark.e2e
@pytest.mark.timeout(1200)
@pytest.mark.parametrize("scene_name", ["e4", "champion", "snorlax", "rocket"])
def test_endgame_scene(scene_name):
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    port = PORTS[scene_name]
    with launch("kb_endgame.lua", extra_env={"KB_TEST_PORT": str(port), "KB_SCENE": scene_name}) as running:
        marker = running.wait_marker("KB_SCENE", timeout=180)
        assert marker is not None, running.result.summary()
        scene = facts(marker)
        assert scene["scene"] == scene_name and scene["port"] == str(port)
        try:
            w, report = asyncio.run(asyncio.wait_for(FLOWS[scene_name](port, scene, running), 900))
        except BaseException:
            w = LAST.get("w")
            if w is not None:  # symbols and counts only
                print("failed at: mode", w.mode, "open uis", [u["kind"] for u in w.open_uis().values()])
                print("received:", dict(sorted(w.counts.items())))
                print("timeline:", w.timeline[-12:])
            raise
        result = running.wait(timeout=60)

    status = report.pop("status")
    print("scene:", scene_name, "map:", scene["map"])
    print("report:", report)
    print("received:", dict(sorted(w.counts.items())))
    print("status:", {k: status.get(k) for k in ("battleStarted", "battleWin", "battleStatic", "battleResults", "trainerStarts",
                                                  "lossLines", "musicBattle", "musicVictory", "saveBegun",
                                                  "defeated", "storyUnknownBlocks")})
    print("driver:", [line for line in result.lines if line.startswith(("KB_HELD", "KB_TALK_ROUTE", "KB_EXIT_ROUTE",
                                                                         "KB_NEXT_ROOM", "KB_END_STATE", "KB_BEATEN",
                                                                         "KB_BACK", "KB_END", "KB_BATTLE_PUSHED",
                                                                         "KB_QUIT"))])

    assert not w.mc.conn.problems  # strict: every message passed its schema
    assert not w.errors, w.errors
    held = result.marker("KB_HELD")
    if scene_name in ("e4", "champion"):
        assert scene["badges"] == "8" and status["badges"] == 8, "the league with all eight badges"
    if scene_name == "e4":
        assert report["walk_in_steps"] >= 1, "the room's own walk-in"
        if "interact" in report:
            assert report["interact"]["ok"] is True, report["interact"]
        assert report["battle"] == ("trainer", "OPP_LORELEI")
        assert report["beaten"] and report["defeated_up"] and report["money_up"]
        assert report["starts"] == 1, "the win went through her encounter's own start"
        assert report["loss_lines"] == 1, "her loss line was shown"
        assert report["door_blocks"] >= 1, "her room's exit opened (story.blocks)"
        assert report["arrivals"][-1] == "BRUNOS_ROOM"
        assert report["heals_between"] == 0, "no heal between the rooms"
        assert status["storyUnknownBlocks"] == 0
        assert held is not None and not held.endswith("music=false")
    elif scene_name == "champion":
        assert report["battle"] == ("rival", "OPP_RIVAL3", 6)
        assert "HALL_OF_FAME" in report["arrivals"]
        assert report["flags"] == ["EVENT_BEAT_CHAMPION_RIVAL", "EVENT_BEAT_CHAMPION_RIVAL_THIS_RUN"]
        assert report["heals"] >= 1, "the Hall of Fame heals"
        assert "menu" in report["saves"], "the Hall of Fame's save"
        assert report["hall"] >= 1 and report["credits"] >= 1, "the Hall of Fame and the credits on the mirror"
        assert report["end_screen"], "back at the boot screens"
        assert report["ingame"] is False, "the snapshot after the credits: out of the world"
        assert report["end"]["overworld"] == "false" and report["end"]["hall"] == "true"
        assert held is not None and not held.endswith("music=false"), "the final battle theme while held"
    elif scene_name == "snorlax":
        assert report["first"] == ("static", True, True, True, True), report["first"]
        assert report["hidden_first"] >= 1, "the wake-up hid it before the battle"
        assert report["back"] >= 1 and report["toggle_back"] >= 1, "aborted: Snorlax shown again"
        assert report["second_new_id"] and report["second_kind"] == "static"
        assert report["removed"] >= 2 and report["toggle_gone"] >= 1, "caught: gone"
        assert report["toggle_last_hidden"], "the story's last word on it: hidden"
        assert report["beat_flag"]
        assert result.marker("KB_BACK").endswith("present=false"), "still gone after a reload"
        assert held is not None and not held.endswith("music=false")
    elif scene_name == "rocket":
        assert report["interact"]["ok"] is True, report["interact"]
        assert report["battle"] == ("trainer", True, True)
        assert report["starts"] == 1, "the win went through his encounter's own start"
        assert report["loss_lines"] == 1, "his own loss line was shown"
        assert report["texts_after"] >= 3, "his line, the prize, his after-battle text"
        assert report["defeated_up"] and report["money_up"]
        assert report["left"], "his exit hid him"
        assert held is not None and not held.endswith("music=false")
    assert result.marker("KB_BATTLE_PUSHED") is None, "no native battle screen"
    assert not result.timed_out and result.returncode == 0, result.summary()
    assert result.marker("KB_QUIT") is not None and result.marker("KB_FAIL") is None
    assert not result.crash_log_touched
    assert not result.error_spots
