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
from collections import Counter
from pathlib import Path

from run_gen1recomp import REPORTS, launch

ANSWER_S = 4.0
DONE_S = 30.0


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
    """Reads the run's safe lines from where it left off."""

    def __init__(self, run):
        self.run = run
        self.at = 0

    def take(self) -> list[str]:
        lines = self.run.result.lines
        out = lines[self.at:]
        self.at += len(out)
        return out


async def row_client(run, port: int, ctl: Path, timeout: float, ready_marker: str = "KB_FIELD", auto_ui: bool = True):
    """Drives the driver's rows. Returns (received messages with their row tags, rows {n: {...}}, counts)."""
    from fake_minecraft import FakeMinecraft

    if run.wait_marker(ready_marker, 900) is None:
        raise RuntimeError("the driver never reached the field")
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=60)
    await mc.hello()
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
        if run.proc.poll() is not None:
            raise RuntimeError(f"the game ended early (code {run.proc.returncode})")
        await pump(time.monotonic() + 0.2)
        for line in lines.take():
            if line.startswith("KB_SWEEP_DONE"):
                done_marker = line
                break
            if not line.startswith("KB_ROW_READY"):
                continue
            f = facts(line)
            n = int(f["n"])
            current = n
            rows[n] = {"ready": f, "sent": time.monotonic()}
            if f.get("dir") in ("up", "down", "left", "right"):
                step = {"dir": f["dir"], "n": n}
                if f.get("push") == "1":
                    step["push"] = True
                await mc.send("move.step", step)
                ans = await pump(time.monotonic() + ANSWER_S,
                                 lambda m: m["type"] in ("player.pos", "player.blocked") and (m.get("data") or {}).get("n") == n)
                rows[n]["answer"] = None if ans is None else {"type": ans["type"], "data": ans.get("data")}
                rows[n]["answer_ms"] = int((time.monotonic() - rows[n]["sent"]) * 1000)
            ctl.write_text(str(n), encoding="utf-8")
            # the driver records the engine's state once idle, then prints DONE
            end = time.monotonic() + DONE_S
            got = None
            while got is None and time.monotonic() < end:
                await pump(time.monotonic() + 0.2)
                for l2 in lines.take():
                    if l2.startswith("KB_ROW_DONE") and facts(l2).get("n") == str(n):
                        got = l2
                        break
                    if l2.startswith("KB_SWEEP_DONE"):
                        done_marker = l2
                if done_marker:
                    break
            rows[n]["done"] = facts(got) if got else None
            await pump(time.monotonic() + 0.3)  # the tail of the row (a door walk-out, a late state)
            current = None
            if done_marker:
                break
    await pump(time.monotonic() + 1.0)
    received = got_all
    await mc.send("debug.quit", {"code": 0})
    await asyncio.sleep(0.5)
    await mc.close()
    return received, rows, answered


def run_rows(driver: str, game: str, scene: str, port: int, timeout: float, extra: dict | None = None,
             auto_ui: bool = True):
    """Launches a row scene and drives it; returns (received, rows, answered, run result)."""
    REPORTS.mkdir(parents=True, exist_ok=True)
    ctl = REPORTS / f"{game}_field_ctl.txt"
    if ctl.exists():
        ctl.unlink()
    env = {"KB_SCENE": scene, "KB_REPORT_DIR": str(REPORTS.resolve()), "KB_DATASET_MEMO": "30",
           "KB_DRIVER_SECONDS": str(int(timeout)), "KB_TEST_PORT": str(port), "KB_CTL": str(ctl.resolve())}
    env.update(extra or {})
    with launch(driver, game=game, extra_env=env, echo=False) as run:
        received, rows, answered = asyncio.run(row_client(run, port, ctl, timeout, auto_ui=auto_ui))
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
            out["story_blocks"] += len(d.get("blocks") or [])
        elif t == "npc.move":
            out["npc_moves"] += 1
    out["types"] = dict(out["types"])
    return out


def write(path: Path, data) -> None:
    path.write_text(json.dumps(data, indent=1, sort_keys=True, default=list), encoding="utf-8")
