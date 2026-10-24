"""FOREIGN_POKEMON_PLAN FP3's real-engine check (and part of FP8): a visitor in Blue, Crystal and Emerald, made by
gen1-bridge from a party.sync, through each engine's own screens, recompute, field-move check, cartridge export, save
and CONTINUE (drivers/kb_fpvisit.lua). No Minecraft: this script is the client (fake_minecraft, role test).

    xvfb-run -a python tools/e2e/fp_visitors.py [--games blue,crystal,emerald]

Per game: hello.ok must say compat.visitor; the sync's second member (national dex 445, past every game's ceiling) must
become the stand-in record with Cobblemon's stats and keep them through everything; the snapshot must report it as
dex 445 with visitor true; game.status must count one visitor; the export must go through. The driver works on a
throwaway save slot and deletes it at debug.quit. Output: KB_FPV_ markers (numbers, booleans, ids), our checks, and
a result JSON in local/reports/fpvisit.json. Nothing ROM-derived is printed.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

from fake_minecraft import FakeMinecraft  # noqa: E402
from run_gen1recomp import launch  # noqa: E402

PORT = 27244
NATIVE = {"blue": 25, "crystal": 152, "emerald": 252}
U1 = "0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b"
U2 = "6a1d3c4e-5f60-4a7b-8c9d-0e1f2a3b4c5d"
STATS = {"hp": 160, "atk": 140, "def": 105, "spa": 90, "spd": 95, "spe": 112}


def say(*parts):
    print(time.strftime("%H:%M:%S"), *parts, flush=True)


def marker(run, tag):
    for line in reversed(list(run.result.lines)):
        if line.startswith("KB_FPV_" + tag):
            return dict(part.split("=", 1) for part in line.split()[1:] if "=" in part)
    return None


async def wait_marker(run, tag, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        m = marker(run, tag)
        if m is not None:
            return m
        if run.proc.poll() is not None:
            return marker(run, tag)
        await asyncio.sleep(0.5)
    return None


CAUGHT = "c0ffee00-0000-4000-8000-00000000c0de"


async def journey_checks(game, run, mc, check):
    """FP8: the driver's journey (KB_FPV_JOURNEY=1): the client answers its battles as Cobblemon would, then checks what
    reached Minecraft: the rename, the daycare's deposit and withdrawal of the visitor, Crystal's contest battle with the
    lead only and its catch, Emerald's Steven battle as a multi battle."""
    seen = 0
    answered = set()
    deadline = time.monotonic() + 600
    while time.monotonic() < deadline and marker(run, "JOURNEY_DONE") is None and run.proc.poll() is None:
        while seen < len(mc.received):
            m = mc.received[seen]
            seen += 1
            if m.get("type") == "battle.start" and m["data"]["battle"] not in answered:
                d = m["data"]
                answered.add(d["battle"])
                await asyncio.sleep(1)
                if d.get("kind") == "contest":
                    await mc.send("battle.result", {"battle": d["battle"], "outcome": "caught",
                                                    "caught": {"uid": CAUGHT, "dex": d["wild"]["dex"], "level": d["wild"]["level"]}})
                else:
                    await mc.send("battle.result", {"battle": d["battle"], "outcome": "win"})
        try:
            await mc.next(1.0)
        except asyncio.TimeoutError:
            pass
    for tag in ("RENAME", "DAYCARE_IN", "DAYCARE_OUT", "CONTEST_START", "CONTEST", "STEVEN_START", "STEVEN", "JOURNEY_DONE"):
        say(game, tag, marker(run, tag))
    renames = [m["data"] for m in mc.received if m.get("type") == "party.rename"]
    check("name_rater", any(r.get("uid") == U2 and r.get("nick") for r in renames), renames)
    deposits = [m["data"].get("uid") for m in mc.received if m.get("type") == "daycare.deposit"]
    withdraws = [m["data"].get("uid") for m in mc.received if m.get("type") == "daycare.withdraw"]
    check("daycare_visitor", U2 in deposits and U2 in withdraws, {"deposits": deposits, "withdraws": withdraws})
    starts = [m["data"] for m in mc.received if m.get("type") == "battle.start"]
    if game == "crystal":
        c = [d for d in starts if d.get("kind") == "contest"]
        check("contest_lead_only", len(c) == 1 and c[0].get("use") == [U1], [d.get("use") for d in c])
        ct = marker(run, "CONTEST") or {}
        check("contest_catch", ct.get("caught") == "true" and ct.get("same") == "true", ct)
    if game == "emerald":
        mm = [d for d in starts if d.get("format") == "multi"]
        check("steven_multi", len(mm) == 1 and mm[0].get("use") and len(mm[0].get("ally", {}).get("team", [])) == 1,
              [{k: d.get(k) for k in ("use", "split")} | {"ally": len(d.get("ally", {}).get("team", []))} for d in mm])
        check("steven_won", (marker(run, "STEVEN") or {}).get("outcome") == "win", marker(run, "STEVEN"))
    check("journey_done", marker(run, "JOURNEY_DONE") is not None, None)


async def one(game: str, env: dict) -> dict:
    if game == "crystal":
        # a Gen 2 driver boot is a skeleton game unless the boot cinema is asked for (the G2S and FP0 harnesses did so)
        env = dict(env, POKEPORT_BOOT_CINEMA="1")
    checks = {}

    def check(name, ok, detail=None):
        checks[name] = {"ok": bool(ok), "detail": detail}
        say(game, "CHECK", name, "PASS" if ok else "FAIL", "" if detail is None else json.dumps(detail))

    journey = env.get("KB_FPV_JOURNEY") == "1"
    run = launch("kb_fpvisit.lua", game=game, echo=False, extra_env=dict(env, KB_FPV_VERSION=game, KB_TEST_PORT=str(PORT)))
    try:
        field = await wait_marker(run, "FIELD", 900)
        say(game, "field:", field)
        if not field or field.get("field") != "true":
            check("field", False, field)
            return checks
        mc = FakeMinecraft(port=PORT, role="test", ping_ms=1000)
        await mc.connect(attempts=20)
        try:
            ok = (await mc.hello())["data"]
            check("hello_visitor", (ok.get("compat") or {}).get("visitor") is True, ok.get("compat"))
            native = {"uid": U1, "dex": NATIVE[game], "level": 12, "moves": [33], "hp": 30, "maxhp": 30,
                      "stats": {"hp": 30, "atk": 20, "def": 20, "spa": 20, "spd": 20, "spe": 20}, "types": ["normal"]}
            foreign = {"uid": U2, "dex": 445, "level": 48, "name": "Garchomp", "moves": [15, 89, 337], "hp": 150,
                       "maxhp": 160, "stats": STATS, "types": ["dragon", "ground"], "gender": "male", "nature": "jolly",
                       "canLearn": [15, 89], "stones": []}
            await mc.send("party.sync", {"party": [native, foreign], "box": {"count": 0}})
            done = await wait_marker(run, "DONE", 600)
            for tag in ("VISITOR", "PIN", "SCREENS", "CUT", "SAVE", "EXPORT", "AFTER_CONTINUE", "DONE"):
                say(game, tag, marker(run, tag))
            v = marker(run, "VISITOR") or {}
            check("visitor_record", v.get("visitor") == "true" and v.get("at") == "2" and v.get("hp") == "160"
                  and v.get("atk") == "140", v)
            if game == "crystal":
                check("crystal_name", v.get("name") == "GARCHOMP", v.get("name"))
            check("pin_kept", (marker(run, "PIN") or {}).get("kept") == "true", marker(run, "PIN"))
            sc = marker(run, "SCREENS") or {}
            check("screens", all(sc.get(k) == "ok" for k in ("party", "summary", "hof")) and sc.get("kept") == "true", sc)
            check("cut_by_visitor", (marker(run, "CUT") or {}).get("user") == "visitor", marker(run, "CUT"))
            check("export", (marker(run, "EXPORT") or {}).get("result") == "encoded", marker(run, "EXPORT"))
            check("continue_kept", done is not None and done.get("kept") == "true", marker(run, "AFTER_CONTINUE"))
            snap = (await mc.request("resync", {}, "snapshot", timeout=30))["data"]
            party = [(p.get("uid"), p.get("dex"), p.get("visitor", False)) for p in snap.get("party", [])]
            check("snapshot", party == [(U1, NATIVE[game], False), (U2, 445, True)], party)
            st = (await mc.request("debug.status", {}, "debug.status", timeout=30))["data"]
            check("status_counts", st.get("partyVisitorsNow") == 1 and st.get("partyForeignSpecies", 0) == 0,
                  {k: st.get(k) for k in ("partyVisitorsNow", "partyForeignSpecies", "partySize")})
            statuses = [m["data"] for m in mc.received if m.get("type") == "game.status"]
            check("game_status_visitors", any(s.get("visitors") == 1 for s in statuses), [s.get("visitors") for s in statuses])
            if journey:
                await journey_checks(game, run, mc, check)
            check("schema_clean", not mc.conn.problems, [str(p) for p in mc.conn.problems][:3])
            await mc.send("debug.quit", {"code": 0})
            await asyncio.sleep(2)
        finally:
            await mc.close()
        result = run.wait(timeout=120)
        say(game, "exit", result.returncode, marker(run, "CLEANED"))
    finally:
        if run.proc.poll() is None:
            run.kill()
            run.wait(timeout=30)
    return checks


async def main_async(games, env):
    out = {}
    for game in games:
        say("==", game)
        out[game] = await one(game, env)
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--games", default="blue,crystal,emerald")
    ap.add_argument("--journey", action="store_true", help="FP8: the rename, the daycare, the contest and Steven's battle")
    args = ap.parse_args(argv)
    env = {"KB_DATASET_MEMO": "30", "KB_DRIVER_SECONDS": "1500"}
    if args.journey:
        env["KB_FPV_JOURNEY"] = "1"
    games = [g for g in args.games.split(",") if g]
    results = asyncio.run(main_async(games, env))
    path = REPO / "local" / "reports" / "fpvisit.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    ok = all(c["ok"] for checks in results.values() for c in checks.values()) and all(results.values())
    say("result:", "PASS" if ok else "FAIL", json.dumps({g: {k: v["ok"] for k, v in c.items()} for g, c in results.items()}))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
