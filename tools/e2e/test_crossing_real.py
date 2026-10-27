"""MG8 (docs/specs/HOENN_PASS_PLAN.md HP7): the crossing between two real engines, at the links. Blue and Emerald
run side by side, each with gen1-bridge, holding the two ends of the test pass (gen1-bridge/data/routes.json's
kanto_hoenn_test: Viridian City's north-west corner, Littleroot Town's south-east corner); a fake Minecraft client
holds a link to each (MG4) and does what the client's pass choreography and the server do between them
(client/zone/PassChoreography: park the game it leaves on its half's route map, resume the other onto its own; the
server's ledger then projects the money and the bag into it, bag.set, MG6). MULTI_GAME_PLAN §6 check 4 at the links.

Blue runs from the test install (drivers/kb_wild.lua, on the pass's Kanto spot); Emerald from a second data home on
the pod's container disk, its import folder and gen1-bridge linked by name as spike S1 did (one process per game:
MULTI_GAME_PLAN §4.11), its Union Room switched off first (drivers/kb_union_off.lua), drivers/kb_emerald.lua's route
scene. Then:

  1. Blue walks its clearing into KB_ROUTE_KANTO_HOENN_TEST (a script warp onto x1, facing right);
  2. KB_CROSSINGS times (20 by default) each way:
     Kanto -> Hoenn: Blue parked on its route map, Emerald resumed onto KB_ROUTE_KANTO_HOENN_TEST_TO, standing for it
     at x1; the ledger's projection (money and POTIONs, a new stamp) lands in Emerald's bag and comes back in its
     bag.sync;
     Hoenn -> Kanto: Emerald parked, Blue resumed onto its route map, on the cell it was parked on (a park/resume
     pair leaves the hidden player where it was); the projection lands in Blue's bag likewise;
     the money goes down by one each crossing and the POTIONs alternate, so each projection is new;
  3. on the Hoenn side (after the first crossing), a step onto A takes Emerald home to Littleroot's clearing, facing
     the town, and a step takes it back into its route map;
  4. after the last crossing, a step onto A takes Blue home to Viridian's clearing, facing the town.

No warp ends by the warp observer's timeout (warp.no_arrival) and no error or schema problem comes. The two hidden
processes' memory (the sum of each process tree's resident set) is sampled throughout and printed (§6 check 5).

MG9 G2H (docs/specs/GEN2_BRIDGE_PLAN.md §8): the same between Blue and Crystal over the Johto test pass
(kanto_johto_test: Viridian City's north-east corner, Cherrygrove City's clearing), Crystal from its own second home
on drivers/kb_crystal.lua's route scene; its end is a route map of its own (gen2/routes.lua), so the hidden player
really stands on it.

Every message passes its schema (strict). Output: types, map symbols, cells, numbers and booleans only. The second
home and its links vanish with the pod's container disk; nothing under local/ is opened (links are made by name).
"""

import asyncio
import json
import os
import tempfile
from pathlib import Path

import pytest

import run_gen1recomp as rg
from fake_minecraft import FakeMinecraft
from run_gen1recomp import INSTALL, launch, preflight
from test_emerald import find_or_wait, errors
import foreign  # noqa: E402  (FP8: KB_E2E_FOREIGN=1, an all-foreign team)

BLUE_PORT, EMERALD_PORT, CRYSTAL_PORT = 27232, 27233, 27234
CROSSINGS = int(os.environ.get("KB_CROSSINGS", "20"))
MONEY = 3000
REPO = Path(__file__).resolve().parents[2]
ROUTES = {r["id"]: r for r in json.loads((REPO / "gen1-bridge" / "data" / "routes.json").read_text(encoding="utf-8"))["routes"]}
PASS = ROUTES["kanto_hoenn_test"]
KANTO_MAP, HOENN_MAP = "KB_ROUTE_KANTO_HOENN_TEST", "KB_ROUTE_KANTO_HOENN_TEST_TO"
# MG9 G2H: the Johto test pass
JOHTO_PASS = ROUTES["kanto_johto_test"]
JOHTO_KANTO_MAP, JOHTO_MAP = "KB_ROUTE_KANTO_JOHTO_TEST", "KB_ROUTE_KANTO_JOHTO_TEST_TO"
X1 = {"x": 1, "y": 0, "facing": "right"}
DELTA = {"up": (0, -1), "down": (0, 1), "left": (-1, 0), "right": (1, 0)}
OPPOSITE = {"up": "down", "down": "up", "left": "right", "right": "left"}

def cell_of(to: dict) -> dict:
    """A warp's `to` without its floor (WATER_AND_BRIDGES_PLAN D7 added `elev`; these checks are about the cell)."""
    return {k: v for k, v in to.items() if k != "elev"}



def way_back(f):
    """Where the step onto A lands: the clearing's last block's near side, facing the town (kb_routes.lua plan)."""
    dx, dy = DELTA[f["facing"]]
    bx, by = f["x"] // 2 + dx * f["depth"], f["y"] // 2 + dy * f["depth"]
    near = {"up": (2 * bx, 2 * by + 1), "down": (2 * bx, 2 * by), "left": (2 * bx + 1, 2 * by),
            "right": (2 * bx, 2 * by)}[f["facing"]]
    return {"x": near[0], "y": near[1], "facing": OPPOSITE[f["facing"]]}


def steps_to_trigger(f):
    """Steps from the exit cell through the clearing onto its trigger (the last block's far side)."""
    exit_in_block = {"up": f["y"] % 2, "down": 1 - f["y"] % 2, "left": f["x"] % 2, "right": 1 - f["x"] % 2}[f["facing"]]
    return exit_in_block + 2 * f["depth"]


def second_home(root: Path, game: str = "emerald") -> Path:
    """The far game's own data home: its import folder and gen1-bridge linked by name (spike S1's make_home)."""
    ident = root / rg.IDENTITY
    (ident / "mods").mkdir(parents=True, exist_ok=True)
    (ident / game).symlink_to(INSTALL / game)
    (ident / "mods" / "gen1-bridge").symlink_to(REPO / "gen1-bridge")
    for name in ("portable.txt",):
        if (INSTALL / name).exists():
            (ident / name).symlink_to(INSTALL / name)
    return ident


async def walk(mc, direction, count, n0):
    since = len(mc.received)
    for i in range(count):
        n = n0 + i
        await mc.send("move.step", {"dir": direction, "n": n})
        step = await find_or_wait(mc, ("player.pos", "player.blocked"), 20, since, lambda m, n=n: m["data"].get("n") == n)
        assert step["type"] == "player.pos", (direction, n, step["data"].get("reason"))
    return since


async def arrival(mc, since, timeout=60):
    return (await find_or_wait(mc, "warp", timeout, since, lambda m: m["data"]["phase"] == "arrive"))["data"]


async def connect(port):
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=40)
    await mc.hello()
    await mc.expect("snapshot", timeout=20)
    return mc


async def where(mc):
    return (await mc.request("resync", reply_type="snapshot", timeout=15))["data"]["player"]


def tree_rss_kb(root_pid):
    """The resident set of a process and its descendants, in KiB (Linux /proc; 0 elsewhere)."""
    children, rss = {}, {}
    for entry in Path("/proc").iterdir() if Path("/proc").is_dir() else []:
        if not entry.name.isdigit():
            continue
        try:
            stat = (entry / "stat").read_text()
            ppid = int(stat.rsplit(")", 1)[1].split()[1])
            line = next((ln for ln in (entry / "status").read_text().splitlines() if ln.startswith("VmRSS:")), None)
        except (OSError, ValueError, IndexError):
            continue
        children.setdefault(ppid, []).append(int(entry.name))
        rss[int(entry.name)] = int(line.split()[1]) if line else 0
    total, todo = 0, [root_pid]
    while todo:
        pid = todo.pop()
        total += rss.get(pid, 0)
        todo += children.get(pid, [])
    return total


async def project(mc, money, potions, stamp):
    """The server's bag.set after a resume, and the bag.sync that reads it back."""
    since = len(mc.received)
    done = (await mc.request("bag.set", {"items": {"POTION": potions}, "money": money, "stamp": stamp},
                             reply_type="act.result", timeout=30))["data"]
    assert done == {"ok": True, "kind": "bag.set"}, (stamp, done)
    synced = (await find_or_wait(mc, "bag.sync", 20, since, lambda m: m["data"].get("stamp") == stamp))["data"]
    return synced.get("money") == money and synced["items"].get("POTION") == potions


async def resume(mc, region, route_map):
    """region.resume onto the route map's x1; where the hidden player then stands."""
    done = (await mc.request("region.resume", {"region": region, "to": dict(X1, map=route_map)},
                             reply_type="act.result", timeout=90))["data"]
    assert done == {"ok": True, "kind": "resume"}, (region, done)
    here = await where(mc)
    return here["map"], here["x"], here["y"]


async def park(mc, region):
    done = (await mc.request("region.park", {"region": region}, reply_type="act.result", timeout=30))["data"]
    assert done == {"ok": True, "kind": "park"}, (region, done)


async def _crossing(pids, rss, route=None, far_port=EMERALD_PORT, kanto_map=KANTO_MAP, far_map=HOENN_MAP, far_region="hoenn"):
    """The crossings over `route` (default the Hoenn pass); the far game is Emerald or (G2H) Crystal."""
    route = route or PASS
    near, far = route["from"], route["to"]
    blue, emerald = await connect(BLUE_PORT), await connect(far_port)
    report = {"crossings": 0, "projected": 0}
    # (G2H: the Crystal driver's Pokemon is offered as a gift; a party.sync that names it confirms it)
    await asyncio.sleep(2)
    give = next((m["data"] for m in emerald.received if m["type"] == "party.give"), None)
    if give is not None:
        await emerald.send("party.sync", {"party": foreign.party([{"uid": "5e6f7081-92a3-4bb4-8cd5-e6f708192a3b",
                                                                   "give": give["give"], "dex": give["dex"],
                                                                   "level": give["level"], "moves": give.get("moves") or [33],
                                                                   "hp": 30, "maxhp": 30}]),
                                          "box": {"count": 0}})
    if foreign.FOREIGN:
        # FP8: Blue's side has an all-foreign team too
        await blue.send("party.sync", {"party": foreign.team(), "box": {"count": 0}})

    def sample():
        rss.append(sum(tree_rss_kb(pid) for pid in pids))

    # 1. Blue walks its clearing into its route map; Emerald waits parked
    since = await walk(blue, near["facing"], steps_to_trigger(near), 1)
    into = await arrival(blue, since)
    report["blue_in"] = (into["kind"], into["to"])
    assert into["kind"] == "script" and cell_of(into["to"]) == dict(X1, map=kanto_map), report
    await park(emerald, far_region)
    # 2. the crossings, each way
    for k in range(CROSSINGS):
        money = MONEY - k
        potions = 3 + k % 2
        await park(blue, "kanto")
        at = await resume(emerald, far_region, far_map)
        assert at == (far_map, 1, 0), (k, "far", at)
        report["projected"] += await project(emerald, money, potions, f"{2 * k:032x}")
        sample()
        if k == 0:
            # 3. the far side: home through A, then back into the route map
            since = len(emerald.received)
            await emerald.send("move.step", {"dir": "left", "n": 50})
            home = await arrival(emerald, since)
            report["far_home"] = home["to"]
            assert cell_of(home["to"]) == dict(way_back(far), map=far["map"]), report
            await asyncio.sleep(1)
            since = await walk(emerald, OPPOSITE[home["to"]["facing"]], 1, 60)
            back = await arrival(emerald, since)
            report["far_back_in"] = back["to"]
            assert cell_of(back["to"]) == dict(X1, map=far_map), report
            # (G2H: Gen 2 reports the arrival as the map loads; its map setup's fade still runs a moment, and a
            # player crossing in Minecraft walks the zone first)
            await asyncio.sleep(2)
        await park(emerald, far_region)
        at = await resume(blue, "kanto", kanto_map)
        assert at == (kanto_map, 1, 0), (k, "blue", at)
        report["projected"] += await project(blue, money, potions, f"{2 * k + 1:032x}")
        sample()
        report["crossings"] += 1
    # 4. Kanto's side: home through A
    since = len(blue.received)
    await blue.send("move.step", {"dir": "left", "n": 70})
    home = await arrival(blue, since)
    report["blue_home"] = home["to"]
    assert cell_of(home["to"]) == dict(way_back(near), map=near["map"]), report
    report["errors"] = errors(blue) + errors(emerald)
    report["problems"] = len(blue.conn.problems) + len(emerald.conn.problems)
    assert report["errors"] == [] and report["problems"] == 0, report
    assert report["projected"] == 2 * CROSSINGS, report
    for mc in (blue, emerald):
        await mc.send("debug.quit", {"code": 0})
        await mc.close()
    return report


@pytest.mark.e2e
@pytest.mark.timeout(2400)
def test_a_crossing_between_two_real_engines():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    games = rg.kb_games.detect(INSTALL)
    if not {"blue", "emerald"} <= set(games):
        pytest.skip("needs Blue and Emerald imports in the test install")
    near = PASS["from"]
    with tempfile.TemporaryDirectory(prefix="kb-hoenn-home-") as tmp:
        home = second_home(Path(tmp))
        with launch("kb_union_off.lua", game="emerald", install=home, echo=False) as off:
            assert off.wait(timeout=180).marker("KB_UNION_OFF") is not None
        blue_env = {"KB_TEST_PORT": str(BLUE_PORT), "KB_SPOT_MAP": near["map"], "KB_SPOT_X": str(near["x"]),
                    "KB_SPOT_Y": str(near["y"]), "KB_SPOT_FACING": near["facing"]}
        emerald_env = {"KB_TEST_PORT": str(EMERALD_PORT), "KB_SCENE": "route"}
        with launch("kb_wild.lua", game="blue", extra_env=blue_env) as blue_run, \
                launch("kb_emerald.lua", game="emerald", extra_env=emerald_env, install=home) as emerald_run:
            assert blue_run.wait_marker("KB_WILD_READY", timeout=300) is not None, blue_run.result.summary()
            assert emerald_run.wait_marker("KB_SERVING", timeout=300) is not None, emerald_run.result.summary()
            rss = []
            report = asyncio.run(asyncio.wait_for(_crossing([blue_run.proc.pid, emerald_run.proc.pid], rss),
                                                  120 + 30 * CROSSINGS))
            blue_result, emerald_result = blue_run.wait(timeout=120), emerald_run.wait(timeout=120)
    if rss:
        report["rss_mb"] = {"max": max(rss) // 1024, "last": rss[-1] // 1024}
    print("crossing:", report)
    for name, result in (("blue", blue_result), ("emerald", emerald_result)):
        assert not result.timed_out and result.returncode == 0, (name, result.summary())
        assert result.marker("KB_FAIL") is None, name
    assert any("routes.installed" in line and "route=kanto_hoenn_test" in line for line in blue_result.lines)
    assert any("routes.installed" in line and "own=true" in line for line in emerald_result.lines)
    for name, result in (("blue", blue_result), ("emerald", emerald_result)):
        assert not any("warp.no_arrival" in line for line in result.lines), (name, "a warp ended by the timeout")


@pytest.mark.e2e
@pytest.mark.timeout(2400)
def test_a_crossing_between_blue_and_crystal():
    """MG9 G2H: Blue and Crystal over the Johto test pass, KB_CROSSINGS times each way."""
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    games = rg.kb_games.detect(INSTALL)
    if not {"blue", "crystal"} <= set(games):
        pytest.skip("needs Blue and Crystal imports in the test install")
    near, far = JOHTO_PASS["from"], JOHTO_PASS["to"]
    with tempfile.TemporaryDirectory(prefix="kb-johto-home-") as tmp:
        home = second_home(Path(tmp), "crystal")
        with launch("kb_union_off.lua", game="crystal", install=home, echo=False) as off:
            assert off.wait(timeout=180).marker("KB_UNION_OFF") is not None
        blue_env = {"KB_TEST_PORT": str(BLUE_PORT), "KB_SPOT_MAP": near["map"], "KB_SPOT_X": str(near["x"]),
                    "KB_SPOT_Y": str(near["y"]), "KB_SPOT_FACING": near["facing"]}
        crystal_env = {"KB_TEST_PORT": str(CRYSTAL_PORT), "KB_SCENE": "route", "KB_DATASET_MEMO": "30",
                       "KB_DRIVER_SECONDS": str(600 + 40 * CROSSINGS),
                       "KB_SPOT_MAP": far["map"].removeprefix("G2_"), "KB_SPOT_X": str(far["x"]), "KB_SPOT_Y": str(far["y"]),
                       "KB_SPOT_FACING": far["facing"]}
        with launch("kb_wild.lua", game="blue", extra_env=blue_env) as blue_run, \
                launch("kb_crystal.lua", game="crystal", extra_env=crystal_env, install=home) as crystal_run:
            assert blue_run.wait_marker("KB_WILD_READY", timeout=300) is not None, blue_run.result.summary()
            assert crystal_run.wait_marker("KB_SERVING", timeout=300) is not None, crystal_run.result.summary()
            rss = []
            report = asyncio.run(asyncio.wait_for(
                _crossing([blue_run.proc.pid, crystal_run.proc.pid], rss, route=JOHTO_PASS, far_port=CRYSTAL_PORT,
                          kanto_map=JOHTO_KANTO_MAP, far_map=JOHTO_MAP, far_region="johto"),
                120 + 30 * CROSSINGS))
            blue_result, crystal_result = blue_run.wait(timeout=120), crystal_run.wait(timeout=120)
    if rss:
        report["rss_mb"] = {"max": max(rss) // 1024, "last": rss[-1] // 1024}
    print("crossing:", report)
    for name, result in (("blue", blue_result), ("crystal", crystal_result)):
        assert not result.timed_out and result.returncode == 0, (name, result.summary())
        assert result.marker("KB_FAIL") is None, name
    assert any("routes.installed" in line and "route=kanto_johto_test" in line for line in blue_result.lines)
    assert any("routes.installed" in line and "own=true" in line for line in crystal_result.lines)
    for name, result in (("blue", blue_result), ("crystal", crystal_result)):
        assert not any("warp.no_arrival" in line for line in result.lines), (name, "a warp ended by the timeout")


async def _b1(far_port, far_region, far_map, route):
    """WORLD_SESSIONS_PLAN WS3 (B1): the far game waits at its title with a saved slot. Its game.status says so
    (save{slot}), so the client's choreography sends no party.stash: region.start CONTINUEs the save (detail continue,
    save.loaded fresh false) and the game stands where its save loaded (the choreography then follows it there)."""
    near = route["from"]
    blue, far = await connect(BLUE_PORT), await connect(far_port)
    report = {}
    since = await walk(blue, near["facing"], steps_to_trigger(near), 1)
    into = await arrival(blue, since)
    assert into["kind"] == "script", into
    await park(blue, "kanto")
    status = await find_or_wait(far, "game.status", 30, 0, lambda m: True)
    report["far_status"] = {k: status["data"].get(k) for k in ("playthrough", "started", "save")}
    assert status["data"]["playthrough"] == "title" and status["data"].get("save") is not None, report
    since = len(far.received)
    started = (await far.request("region.start", {"region": far_region}, reply_type="act.result", timeout=180))["data"]
    report["start"] = started
    assert started == {"ok": True, "kind": "start", "detail": "continue"}, report
    loaded = await find_or_wait(far, "save.loaded", 60, since, lambda m: True)
    report["loaded_fresh"] = loaded["data"]["fresh"]
    assert loaded["data"]["fresh"] is False, report
    after = (await far.request("resync", reply_type="snapshot", timeout=30))["data"]
    report["ingame"] = after["ingame"]
    report["party"] = len(after["party"])
    assert after["ingame"] is True, report
    for mc in (blue, far):
        assert not mc.conn.problems
        await mc.send("debug.quit", {"code": 0})
        await mc.close()
    return report


@pytest.mark.e2e
@pytest.mark.timeout(1800)
def test_b1_a_saved_far_game_at_its_title_is_continued_without_a_stash():
    """WORLD_SESSIONS_PLAN WS3, C2's B1 scene: Blue on the Hoenn pass, Emerald launched with a saved slot but not
    continued (drivers/kb_emerald.lua title_continue)."""
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    if not {"blue", "emerald"} <= set(rg.kb_games.detect(INSTALL)):
        pytest.skip("needs Blue and Emerald imports in the test install")
    near = PASS["from"]
    with tempfile.TemporaryDirectory(prefix="kb-b1-home-") as tmp:
        home = second_home(Path(tmp))
        with launch("kb_union_off.lua", game="emerald", install=home, echo=False) as off:
            assert off.wait(timeout=180).marker("KB_UNION_OFF") is not None
        blue_env = {"KB_TEST_PORT": str(BLUE_PORT), "KB_SPOT_MAP": near["map"], "KB_SPOT_X": str(near["x"]),
                    "KB_SPOT_Y": str(near["y"]), "KB_SPOT_FACING": near["facing"]}
        emerald_env = {"KB_TEST_PORT": str(EMERALD_PORT), "KB_SCENE": "title_continue"}
        with launch("kb_wild.lua", game="blue", extra_env=blue_env) as blue_run, \
                launch("kb_emerald.lua", game="emerald", extra_env=emerald_env, install=home) as emerald_run:
            assert blue_run.wait_marker("KB_WILD_READY", timeout=300) is not None, blue_run.result.summary()
            assert emerald_run.wait_marker("KB_TITLE", timeout=300) is not None, emerald_run.result.summary()
            report = asyncio.run(asyncio.wait_for(_b1(EMERALD_PORT, "hoenn", HOENN_MAP, PASS), 600))
            blue_result, emerald_result = blue_run.wait(timeout=120), emerald_run.wait(timeout=120)
    print("b1:", report)
    for name, result in (("blue", blue_result), ("emerald", emerald_result)):
        assert result.marker("KB_FAIL") is None, (name, result.summary())
