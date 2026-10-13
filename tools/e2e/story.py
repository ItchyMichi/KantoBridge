"""Shared helpers for the M5 end-to-end tests (test_battle.py, test_opening.py): a walker that also
keeps what the story sends -- battle.start, party.give, party.heal, the modes in order, the engine's
own (script) steps, NPC moves and emotes -- and answers the hidden game's UIs like a player.

Data safety (docs/PLAN.md section 0.3): everything received is kept in memory only; callers print
types, kinds, counts, ranges and symbols, never text, names, species or moves.
"""

from __future__ import annotations

import asyncio
import time
from collections import Counter

from fake_minecraft import FakeMinecraft
from run_gen1recomp import INDEX_TIMEOUT
from test_ui import UiWalker
from test_walk import Grid


class StoryWalker(UiWalker):
    def __init__(self, mc):
        super().__init__(mc)
        self.battles = []          # battle.start data
        self.gives = []            # party.give data
        self.heals = 0
        self.modes = []            # player.state modes, consecutive repeats folded
        self.script_frames = []    # frames of every player.pos kind "script"
        self.npc_moves = Counter()  # npc.move kinds
        self.npc_adds = 0
        self.npc_removes = 0
        self.sprites = {}          # npc id -> sprite symbol
        self.emotes = Counter()    # "player" / "npc"
        self.errors = []           # (code, re)
        self.order = []            # snapshot / battle.start / party.give in arrival order
        self._status_re = None     # the debug.status request waiting for its reply
        self._status = None

    def handle(self, m):
        super().handle(m)
        t, d = m["type"], m["data"]
        if t in ("snapshot", "battle.start", "party.give"):
            self.order.append(t)
        if t == "debug.status" and self._status_re is not None and m.get("re") == self._status_re:
            self._status = d
        elif t == "battle.start":
            self.battles.append(d)
        elif t == "party.give":
            self.gives.append(d)
        elif t == "party.heal":
            self.heals += 1
        elif t == "player.state":
            if not self.modes or self.modes[-1] != d["mode"]:
                self.modes.append(d["mode"])
        elif t == "player.pos" and d["kind"] == "script":
            self.script_frames.append(d["frames"])
        elif t == "npc.set":
            for n in d["list"]:
                self.sprites[n["id"]] = n["sprite"]
        elif t == "npc.add":
            self.npc_adds += 1
            self.sprites[d["id"]] = d["sprite"]
        elif t == "npc.remove":
            self.npc_removes += 1
        elif t == "npc.move":
            self.npc_moves[d["kind"]] += 1
        elif t == "emote":
            self.emotes["player" if d["target"] == "player" else "npc"] += 1
        elif t == "error":
            self.errors.append((d["code"], m.get("re")))

    def open_uis(self):
        return {ui_id: ui for ui_id, ui in self.uis.items() if not ui["closed"]}

    async def drive(self, until, timeout=120.0, choices=(), names=(), mirror_button=None):
        """Plays the hidden game's UIs until until() holds: A at each text box's arrow, the next of
        `choices` at each YES/NO (0 YES, 1 NO; NO once they run out), the next of `names` (a list of
        glyphs) at each naming screen, and mirror_button (if any) on a mirror screen about once a
        second."""
        choices, names = list(choices), list(names)
        done = set()
        pressed_at = {}
        deadline = time.monotonic() + timeout
        while not until():
            if time.monotonic() > deadline:
                raise AssertionError("timed out playing the story's UIs")
            await self.pump(0.1)
            open_uis = self.open_uis()
            if not open_uis:
                continue
            top = max(open_uis)
            ui = open_uis[top]
            st = ui["state"]
            kind = ui["kind"]
            if kind == "text":
                key = ("t", top, st.get("page", 0), st.get("line", 0), st.get("done", False))
                if st.get("waiting") and key not in done:
                    done.add(key)
                    await self.mc.send("ui.advance", {"ui": top})
            elif kind == "choice" and ("c", top) not in done:
                done.add(("c", top))
                answer = choices.pop(0) if choices else 1
                if answer == 1:
                    await self.mc.send("ui.back", {"ui": top})
                else:
                    await self.mc.send("ui.pick", {"ui": top, "index": 0})
            elif kind == "naming" and ("n", top) not in done and names:
                done.add(("n", top))
                await self.mc.send("ui.name", {"ui": top, "glyphs": list(names.pop(0))})
            elif kind == "mirror" and mirror_button is not None:
                now = time.monotonic()
                if now - pressed_at.get(top, 0) > 1.0:
                    pressed_at[top] = now
                    await self.mc.send("act.button", {"button": mirror_button, "how": "tap"})

    async def settle(self, timeout=60.0):
        """Idle with no UI open, then quiet for a moment."""
        await self.until(lambda: self.mode == "idle" and not self.open_uis(), timeout, "idle with no UI open")
        await self.idle(timeout=timeout)

    async def reconnect(self):
        """Drops this connection and connects again (role test): the snapshot, then whatever
        gen1-bridge sends again. Returns the old connection's schema problems."""
        problems = list(self.mc.conn.problems)
        port = self.mc.port
        await self.mc.close()
        await asyncio.sleep(0.5)
        mc = FakeMinecraft(port=port, role="test", ping_ms=300)
        await mc.connect(attempts=20)
        await mc.hello()
        self.mc = mc
        return problems

    async def status(self, timeout=10.0):
        """debug.status, with everything that arrives meanwhile passed through handle() (the
        client's request() would skip those messages)."""
        sent = await self.mc.send("debug.status")
        self._status_re, self._status = sent["seq"], None
        await self.until(lambda: self._status is not None, timeout, "the debug.status reply")
        self._status_re = None
        return self._status


async def export_grids(port, maps):
    """The cell flags of some maps from the real export (role builder), kept in memory only."""
    builder = FakeMinecraft(port=port, role="builder", ping_ms=1000)
    await builder.connect(attempts=20)
    await builder.hello()
    await builder.expect("snapshot", timeout=10)
    index = (await builder.request("export.index", {}, reply_type="export.index", timeout=INDEX_TIMEOUT))["data"]
    have = {(e["kind"], e["id"]) for e in index["resources"]}
    assert all(("map", m) in have for m in maps), "the export lacks a map the test needs"
    grids = {}
    for map_id in maps:
        reply = await builder.request("export.get", {"kind": "map", "id": map_id}, reply_type="export.data",
                                      timeout=60)
        grids[map_id] = Grid(reply["data"]["data"])
    assert not builder.conn.problems
    await builder.close()
    await asyncio.sleep(0.2)
    return grids
