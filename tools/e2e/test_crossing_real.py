"""MG8 (docs/specs/HOENN_PASS_PLAN.md HP7): the crossing between two real engines, at the links. Blue and Emerald
run side by side, each with gen1-bridge, holding the two ends of the test pass (gen1-bridge/data/routes.json's
kanto_hoenn_test: Viridian City's north-west corner, Littleroot Town's south-east corner); a fake Minecraft client
holds a link to each (MG4) and does what the client's pass choreography does between them
(client/zone/PassChoreography: park the game it leaves on its half's route map, resume the other onto its own).

Blue runs from the test install (drivers/kb_wild.lua, on the pass's Kanto spot); Emerald from a second data home on
the pod's container disk, its import folder and gen1-bridge linked by name as spike S1 did (one process per game:
MULTI_GAME_PLAN §4.11), its Union Room switched off first (drivers/kb_union_off.lua), drivers/kb_emerald.lua's route
scene. Then:

  1. Blue walks its clearing into KB_ROUTE_KANTO_HOENN_TEST (a script warp onto x1, facing right);
  2. Kanto -> Hoenn: Blue parked on its route map, Emerald resumed onto KB_ROUTE_KANTO_HOENN_TEST_TO: both answer
     ok, and Emerald stands for its route map at x1;
  3. on the Hoenn side, a step onto A takes Emerald home to Littleroot's clearing, facing the town, and five steps
     take it back into its route map;
  4. Hoenn -> Kanto: Emerald parked, Blue resumed onto its route map (where it stands): ok;
  5. on the Kanto side, a step onto A takes Blue home to Viridian's clearing, facing the town.

Every message passes its schema (strict). Output: types, map symbols, cells and booleans only. The second home and
its links vanish with the pod's container disk; nothing under local/ is opened (links are made by name).
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

BLUE_PORT, EMERALD_PORT = 27232, 27233
REPO = Path(__file__).resolve().parents[2]
PASS = json.loads((REPO / "gen1-bridge" / "data" / "routes.json").read_text(encoding="utf-8"))["routes"][1]
KANTO_MAP, HOENN_MAP = "KB_ROUTE_KANTO_HOENN_TEST", "KB_ROUTE_KANTO_HOENN_TEST_TO"
X1 = {"x": 1, "y": 0, "facing": "right"}
DELTA = {"up": (0, -1), "down": (0, 1), "left": (-1, 0), "right": (1, 0)}
OPPOSITE = {"up": "down", "down": "up", "left": "right", "right": "left"}


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


def second_home(root: Path) -> Path:
    """Emerald's own data home: its import folder and gen1-bridge linked by name (spike S1's make_home)."""
    ident = root / rg.IDENTITY
    (ident / "mods").mkdir(parents=True, exist_ok=True)
    (ident / "emerald").symlink_to(INSTALL / "emerald")
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


async def _crossing():
    near, far = PASS["from"], PASS["to"]
    blue, emerald = await connect(BLUE_PORT), await connect(EMERALD_PORT)
    report = {}
    # 1. Blue walks its clearing into its route map
    since = await walk(blue, near["facing"], steps_to_trigger(near), 1)
    into = await arrival(blue, since)
    report["blue_in"] = (into["kind"], into["to"])
    assert into["kind"] == "script" and into["to"] == dict(X1, map=KANTO_MAP), report
    # 2. Kanto -> Hoenn: park Blue there, resume Emerald onto its route map
    parked = (await blue.request("region.park", {"region": "kanto"}, reply_type="act.result", timeout=30))["data"]
    report["blue_park"] = parked
    assert parked == {"ok": True, "kind": "park"}, parked
    held = (await emerald.request("region.park", {"region": "hoenn"}, reply_type="act.result", timeout=30))["data"]
    assert held == {"ok": True, "kind": "park"}, held
    resumed = (await emerald.request("region.resume", {"region": "hoenn", "to": dict(X1, map=HOENN_MAP)},
                                     reply_type="act.result", timeout=90))["data"]
    report["emerald_resume"] = resumed
    assert resumed == {"ok": True, "kind": "resume"}, resumed
    here = await where(emerald)
    report["emerald_at"] = (here["map"], here["x"], here["y"])
    assert (here["map"], here["x"], here["y"]) == (HOENN_MAP, 1, 0), report
    # 3. Hoenn's side: home through A, then back into the route map
    since = len(emerald.received)
    await emerald.send("move.step", {"dir": "left", "n": 50})
    home = await arrival(emerald, since)
    report["emerald_home"] = home["to"]
    assert home["to"] == dict(way_back(far), map=far["map"]), report
    await asyncio.sleep(1)
    since = await walk(emerald, OPPOSITE[home["to"]["facing"]], 1, 60)
    back = await arrival(emerald, since)
    report["emerald_back_in"] = back["to"]
    assert back["to"] == dict(X1, map=HOENN_MAP), report
    # 4. Hoenn -> Kanto: park Emerald, resume Blue onto its route map (where it stands)
    parked = (await emerald.request("region.park", {"region": "hoenn"}, reply_type="act.result", timeout=30))["data"]
    assert parked == {"ok": True, "kind": "park"}, parked
    resumed = (await blue.request("region.resume", {"region": "kanto", "to": dict(X1, map=KANTO_MAP)},
                                  reply_type="act.result", timeout=60))["data"]
    report["blue_resume"] = resumed
    assert resumed == {"ok": True, "kind": "resume"}, resumed
    # 5. Kanto's side: home through A
    since = len(blue.received)
    await blue.send("move.step", {"dir": "left", "n": 70})
    home = await arrival(blue, since)
    report["blue_home"] = home["to"]
    assert home["to"] == dict(way_back(near), map=near["map"]), report
    report["errors"] = errors(blue) + errors(emerald)
    report["problems"] = len(blue.conn.problems) + len(emerald.conn.problems)
    assert report["errors"] == [] and report["problems"] == 0, report
    for mc in (blue, emerald):
        await mc.send("debug.quit", {"code": 0})
        await mc.close()
    return report


@pytest.mark.e2e
@pytest.mark.timeout(1500)
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
            report = asyncio.run(asyncio.wait_for(_crossing(), 600))
            blue_result, emerald_result = blue_run.wait(timeout=120), emerald_run.wait(timeout=120)
    print("crossing:", report)
    for name, result in (("blue", blue_result), ("emerald", emerald_result)):
        assert not result.timed_out and result.returncode == 0, (name, result.summary())
        assert result.marker("KB_FAIL") is None, name
    assert any("routes.installed" in line and "route=kanto_hoenn_test" in line for line in blue_result.lines)
    assert any("routes.installed" in line and "own=true" in line for line in emerald_result.lines)
