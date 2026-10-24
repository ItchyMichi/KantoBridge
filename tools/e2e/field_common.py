"""The parts the three field checks share (docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md §4): launching a driver scene,
the live sweep's row loop with a stand-in Minecraft client (fake_minecraft, role test, schema-strict), and the analysis
of what the client heard for each row.

The row loop's handshake (driver <-> this script):

    driver  prints  KB_ROW_READY n=<k> dir=<d> [push=1]   the hidden player stands where the row wants it
    script  sends   move.step{dir, n: <k>}                 as the Minecraft client would on a cell crossing
    script  waits   for the answer carrying n=<k> (player.pos / player.blocked), at most ANSWER_S
    script  writes  KB_REPORT_DIR/<ctl>: "<k>"             the step was answered (or timed out)
    driver  waits   until the field is idle again (or its budget), records the engine's own state
    driver  prints  KB_ROW_DONE n=<k> ...                  then the next row

Rows with dir=none need no step (a driver-made trigger): the script only writes the control file. Every message the
client received between a row's READY and its DONE belongs to that row. Logs print map symbols, ids, kinds, counts and
booleans only (PLAN.md §0.3); the per-row truth stays in local/reports/.
"""

from __future__ import annotations

import asyncio
import json
import time
from collections import Counter, defaultdict
from pathlib import Path

from run_gen1recomp import REPORTS, launch

ANSWER_S = 4.0
DONE_S = 60.0


def facts(line: str) -> dict[str, str]:
    return dict(part.split("=", 1) for part in line.split()[1:] if "=" in part)


def run_static(driver: str, game: str, scene: str, marker: str, timeout: float, extra: dict | None = None) -> dict:
    """Runs a driver scene that needs no client (inventory, parity) and returns its marker's facts."""
    REPORTS.mkdir(parents=True, exist_ok=True)
    env = {"KB_SCENE": scene, "KB_REPORT_DIR": str(REPORTS.resolve()), "KB_DATASET_MEMO": "30",
           "KB_DRIVER_SECONDS": str(int(timeout))}
    env.update(extra or {})
    with launch(driver, game=game, extra_env=env) as run:
        result = run.wait(timeout + 120)
    print("RUN", json.dumps(result.summary(), sort_keys=True))
    line = result.marker(marker)
    out = facts(line) if line else {}
    out["_ok"] = result.returncode == 0 and line is not None
    out["_lines"] = [l for l in result.lines if l.startswith("KB_")]
    return out


class _Lines:
    """Reads the run's safe lines from where it left off, one at a time (a line not used is not lost)."""

    def __init__(self, run):
        self.run = run
        self.at = 0

    def next(self) -> str | None:
        lines = self.run.result.lines
        if self.at >= len(lines):
            return None
        line = lines[self.at]
        self.at += 1
        return line


# FA5: a party that knows the field moves (as Cobblemon syncs one; made-up uids). Gen 3 move numbers: Cut 15, Fly 19,
# Surf 57, Strength 70, Dig 91, Teleport 100, Waterfall 127, Flash 148, Rock Smash 249, Dive 291
FIELD_PARTY = [
    {"uid": "fa5f0001-0000-4000-8000-000000000001", "dex": 260, "level": 50, "moves": [57, 127, 291, 70], "hp": 150, "maxhp": 150},
    {"uid": "fa5f0002-0000-4000-8000-000000000002", "dex": 6, "level": 50, "moves": [15, 19, 249, 148], "hp": 150, "maxhp": 150},
    {"uid": "fa5f0003-0000-4000-8000-000000000003", "dex": 65, "level": 50, "moves": [100, 91], "hp": 120, "maxhp": 120},
]


async def row_client(run, port: int, ctl: Path, timeout: float, ready_marker: str = "KB_FIELD", auto_ui: bool = True,
                     party: list | None = None):
    """Drives the driver's rows. Returns (received messages with their row tags, rows {n: {...}}, counts)."""
    from fake_minecraft import FakeMinecraft

    if run.wait_marker(ready_marker, 900) is None:
        raise RuntimeError("the driver never reached the field")
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=60)
    await mc.hello()
    if party:
        await mc.send("party.sync", {"party": party, "box": {"count": 0}})
    lines = _Lines(run)
    deadline = time.monotonic() + timeout
    answered = Counter()
    rows: dict[int, dict] = {}
    got_all: list[dict] = []   # every received message, tagged with the row it belongs to
    current: int | None = None

    async def pump(until: float, want=None):
        """Receives (and answers saves and battles) until want(message) or the time; returns the message or None."""
        while time.monotonic() < until:
            try:
                message = await mc.next(0.2)
            except asyncio.TimeoutError:
                continue
            if message is None:
                raise RuntimeError("the bridge closed the connection")
            got_all.append(dict(message, row=current))
            kind, data = message["type"], message.get("data") or {}
            if kind == "save.begin":
                await mc.send("save.done", {"save": data["save"], "ok": True})
                answered[kind] += 1
            elif kind == "battle.start":
                await mc.send("battle.result", {"battle": data["battle"], "outcome": "aborted"})
                answered[kind] += 1
            elif auto_ui and kind == "ui.open":
                # as a player would: read every text box, take the first choice (yes), leave menus
                ui, k = data.get("ui"), data.get("kind")
                if k == "text" and not data.get("choice"):
                    for _ in range(len(data.get("pages") or []) + 1):
                        await mc.send("ui.advance", {"ui": ui})
                elif k == "choice":
                    await mc.send("ui.pick", {"ui": ui, "index": 0})
                elif k in ("menu", "list", "quantity", "mirror"):
                    await mc.send("ui.back", {"ui": ui})
                elif k == "naming":
                    await mc.send("ui.name", {"ui": ui, "glyphs": []})
                answered["ui." + str(k)] += 1
            if want and want(message):
                return message
        return None

    done_marker = None
    while done_marker is None:
        if time.monotonic() > deadline:
            raise RuntimeError("the sweep did not finish in time")
        if run.proc.poll() is not None and run.result.marker("KB_SWEEP_DONE") is None:
            raise RuntimeError(f"the game ended early (code {run.proc.returncode})")
        try:
            await pump(time.monotonic() + 0.2)
        except RuntimeError:
            if run.result.marker("KB_SWEEP_DONE") is None:
                raise
            break
        while True:
            line = lines.next()
            if line is None:
                break
            if line.startswith("KB_SWEEP_DONE"):
                done_marker = line
                break
            if not line.startswith("KB_ROW_READY"):
                continue
            f = facts(line)
            n = int(f["n"])
            current = n
            rows[n] = {"ready": f, "sent": time.monotonic()}
            if f.get("hold") == "b":
                await mc.send("move.hold", {"button": "b", "on": True})
                await pump(time.monotonic() + 0.2)
            if f.get("act"):
                act = {"action": f["act"]}
                for k in ("ax", "ay"):
                    if k in f:
                        act[k[1]] = int(f[k])
                if f.get("item"):
                    act["item"] = f["item"]
                if f.get("dest"):
                    act["dest"] = f["dest"]
                sent = await mc.send("act.field", act)
                res = await pump(time.monotonic() + 8.0, lambda m: m["type"] == "act.result" and m.get("re") == sent["seq"])
                rows[n]["act"] = None if res is None else res.get("data")
            dirs = [d for d in (f.get("dirs") or f.get("dir") or "").replace("+", ",").split(",")
                    if d in ("up", "down", "left", "right")]
            rows[n]["answers"] = []
            for i, d in enumerate(dirs):
                sn = n if i == 0 else n * 1000 + i
                step = {"dir": d, "n": sn}
                if f.get("push") == "1":
                    step["push"] = True
                await mc.send("move.step", step)
                ans = await pump(time.monotonic() + ANSWER_S,
                                 lambda m, sn=sn: m["type"] in ("player.pos", "player.blocked") and (m.get("data") or {}).get("n") == sn)
                rows[n]["answers"].append(None if ans is None else {"type": ans["type"], "data": ans.get("data")})
                if i == 0:
                    rows[n]["answer"] = rows[n]["answers"][0]
                    rows[n]["answer_ms"] = int((time.monotonic() - rows[n]["sent"]) * 1000)
                if len(dirs) > 1:
                    # the next step once this one has landed (a player keeps walking)
                    await pump(time.monotonic() + 0.35)
            if f.get("hold") == "b":
                await mc.send("move.hold", {"button": "b", "on": False})
            ctl.write_text(str(n), encoding="utf-8")
            # the driver records the engine's state once idle, then prints DONE
            end = time.monotonic() + DONE_S
            got = None
            while got is None and time.monotonic() < end:
                await pump(time.monotonic() + 0.1)
                while True:
                    l2 = lines.next()
                    if l2 is None:
                        break
                    if l2.startswith("KB_ROW_DONE") and facts(l2).get("n") == str(n):
                        got = l2
                        break
                    if l2.startswith("KB_SWEEP_DONE"):
                        done_marker = l2
                        break
                if done_marker:
                    break
            rows[n]["done"] = facts(got) if got else None
            try:
                await pump(time.monotonic() + 0.3)  # the tail of the row (the driver waits 0.8 s before the next)
            except RuntimeError:
                # the game ended: after its last row it quits on its own
                done_marker = done_marker or "KB_SWEEP_DONE (link closed)"
            current = None
            if done_marker:
                break
    try:
        await pump(time.monotonic() + 1.0)
        await mc.send("debug.quit", {"code": 0})
        await asyncio.sleep(0.5)
    except (RuntimeError, ConnectionError, OSError):
        pass  # the driver ends the game once its rows are done
    received = got_all
    await mc.close()
    return received, rows, answered


def run_rows(driver: str, game: str, scene: str, port: int, timeout: float, extra: dict | None = None,
             auto_ui: bool = True, party: list | None = None):
    """Launches a row scene and drives it; returns (received, rows, answered, run result)."""
    REPORTS.mkdir(parents=True, exist_ok=True)
    ctl = REPORTS / f"{game}_field_ctl.txt"
    if ctl.exists():
        ctl.unlink()
    env = {"KB_SCENE": scene, "KB_REPORT_DIR": str(REPORTS.resolve()), "KB_DATASET_MEMO": "30",
           "KB_DRIVER_SECONDS": str(int(timeout)), "KB_TEST_PORT": str(port), "KB_CTL": str(ctl.resolve())}
    env.update(extra or {})
    with launch(driver, game=game, extra_env=env, echo=False) as run:
        received, rows, answered = asyncio.run(row_client(run, port, ctl, timeout, auto_ui=auto_ui, party=party))
        result = run.wait(180)
    print("RUN", json.dumps(result.summary(), sort_keys=True))
    print("ANSWERED", dict(answered))
    return received, rows, answered, result


def row_messages(received: list[dict], n: int) -> list[dict]:
    return [m for m in received if m.get("row") == n]


def stream_facts(msgs: list[dict]) -> dict:
    """What a row's messages say: the kinds, the last position, warps, story writes, modes; counts only."""
    out = {"pos_kinds": [], "warps": [], "modes": [], "blocked": [], "story_cells": 0, "story_blocks": 0,
           "last": None, "npc_moves": 0, "types": Counter()}
    for m in msgs:
        t, d = m["type"], m.get("data") or {}
        out["types"][t] += 1
        if t == "player.pos":
            out["pos_kinds"].append(d.get("kind"))
            out["last"] = {"map": d.get("map"), "x": d.get("x"), "y": d.get("y"), "kind": d.get("kind")}
        elif t == "player.blocked":
            out["blocked"].append(d.get("reason"))
        elif t == "warp":
            out["warps"].append({"phase": d.get("phase"), "kind": d.get("kind"), "to": d.get("to")})
            if d.get("phase") == "arrive" and d.get("to"):
                to = d["to"]
                out["last"] = {"map": to.get("map"), "x": to.get("x"), "y": to.get("y"), "kind": "warp"}
        elif t == "player.state":
            if not out["modes"] or out["modes"][-1] != d.get("mode"):
                out["modes"].append(d.get("mode"))
        elif t == "story.cells":
            out["story_cells"] += len(d.get("cells") or [])
        elif t == "story.blocks":
            out["story_blocks"] += len(d.get("changes") or [])
        elif t == "npc.move":
            out["npc_moves"] += 1
    out["types"] = dict(out["types"])
    return out


def write(path: Path, data) -> None:
    path.write_text(json.dumps(data, indent=1, sort_keys=True, default=list), encoding="utf-8")


# ---- the analysis of a row sweep (every game)

def analyse_rows(rows: list[dict], sweep: dict, received: list[dict], done: dict, want_kind: dict,
                 blocks: bool = False) -> dict:
    """blocks: the live changes are Gen 2's blocks (a row's `changed` and story.blocks' changes as {bx, by, block})
    rather than story.cells' cells ({x, y, l: [metatile, ...]} against {x, y, metatile})."""
    by_n = {r["n"]: r for r in rows}
    out = []
    for rec in sweep.get("rows") or []:
        n = rec.get("n")
        row = by_n.get(n, {})
        msgs = row_messages(received, n)
        sf = stream_facts(msgs)
        d = done.get(n) or {}
        ans = [m for m in msgs if m["type"] in ("player.pos", "player.blocked") and (m.get("data") or {}).get("n") == n]
        e = rec.get("end") or {}
        entry = {"n": n, "map": rec.get("map"), "cls": rec.get("cls"), "state": rec.get("state"), "expect": row.get("expect"),
                 "err": rec.get("err"), "halted": bool(rec.get("halted")), "answers": len(ans),
                 "answer": ans[0]["type"] + ":" + str((ans[0].get("data") or {}).get("kind") or (ans[0].get("data") or {}).get("reason"))
                 if ans else None,
                 "pos_kinds": dict(Counter(k for k in sf["pos_kinds"])), "warps": [w["kind"] for w in sf["warps"] if w["phase"] == "arrive"],
                 "modes": sf["modes"], "engine_end": [e.get("map"), e.get("x"), e.get("y")],
                 "client_end": [(sf["last"] or {}).get("map"), (sf["last"] or {}).get("x"), (sf["last"] or {}).get("y")]
                 if sf["last"] else None, "changed": len(rec.get("changed") or []), "story_cells": sf["story_cells"],
                 "frames": rec.get("frames"), "stepcb": rec.get("stepcb"), "driven": bool(rec.get("driven"))}
        problems = []
        if not rec.get("err"):
            if row.get("dir") and len(ans) != 1:
                problems.append(f"answers={len(ans)}")
            moved = e.get("map") is not None and [e.get("map"), e.get("x"), e.get("y")] != [rec.get("map"), (rec.get("start") or {}).get("x"), (rec.get("start") or {}).get("y")]
            if moved:
                ce = entry["client_end"]
                if ce is None or ce != entry["engine_end"]:
                    problems.append("C:left_behind")
            # D: every cell the engine changed during the row (same map) reached the client as story.cells
            if rec.get("changed") and (e.get("map") == rec.get("map")):
                held = {}
                if blocks:
                    for m in msgs:
                        if m["type"] == "story.blocks" and (m.get("data") or {}).get("map") == rec.get("map"):
                            for c in (m["data"].get("changes") or []):
                                held[(c["bx"], c["by"])] = c.get("block")
                    miss = [c for c in rec["changed"] if held.get((c["bx"], c["by"])) != c["block"]]
                else:
                    for m in msgs:
                        if m["type"] == "story.cells" and (m.get("data") or {}).get("map") == rec.get("map"):
                            for c in (m["data"].get("cells") or []):
                                held[(c["x"], c["y"])] = c.get("metatile")
                    miss = [c for c in rec["changed"] if held.get((c["x"], c["y"])) != c["l"][0]]
                if miss:
                    problems.append(f"D:not_shown={len(miss)}")
            exp = row.get("expect")
            if exp == "blocked" and not (entry["answer"] or "").startswith("player.blocked"):
                problems.append("expect_blocked")
            if exp == "warp" or exp == "fall":
                if not entry["warps"]:
                    problems.append("no_warp")
                else:
                    beh = (row.get("cls") or "").split(":", 1)[-1]
                    want = want_kind.get(beh) or want_kind.get(row.get("cls"))
                    if want and entry["warps"][0] != want:
                        problems.append(f"N:kind {entry['warps'][0]} (mechanism {want})")
            if exp == "script" and any(o.startswith("warp") for o in row.get("ops") or []) and not entry["warps"]:
                problems.append("N:script_no_warp")
            if exp == "forced" and not moved:
                problems.append("forced_no_move")
            if rec.get("halted"):
                problems.append("halted")
        entry["problems"] = problems
        out.append(entry)
    return {"rows": out}


def report_rows(analysis: dict) -> None:
    rows = analysis["rows"]
    by_cls = defaultdict(Counter)
    for e in rows:
        cls = e["cls"]
        by_cls[cls]["rows"] += 1
        if e["err"]:
            by_cls[cls]["err:" + str(e["err"]).split()[0]] += 1
        for p in e["problems"]:
            by_cls[cls][p.split(" (")[0] if p.startswith("N:") else p.split("=")[0]] += 1
        if not e["problems"] and not e["err"]:
            by_cls[cls]["ok"] += 1
    print(f"\nSWEEP: {len(rows)} rows")
    for cls in sorted(by_cls):
        c = by_cls[cls]
        print(f"  {cls}: " + ", ".join(f"{k} {v}" for k, v in sorted(c.items())))
    kinds = Counter()
    for e in rows:
        if e["warps"]:
            kinds[(e["cls"], e["warps"][0])] += 1
    print("\nWARP KINDS SEEN (class: kind x rows):")
    for (cls, k), n in sorted(kinds.items()):
        print(f"  {cls}: {k} x{n}")
    pk = defaultdict(Counter)
    for e in rows:
        for k, n in e["pos_kinds"].items():
            pk[e["cls"]][k] += n
    print("\nPLAYER.POS KINDS (class: kind count):")
    for cls in sorted(pk):
        print(f"  {cls}: " + ", ".join(f"{k} {v}" for k, v in pk[cls].most_common()))

