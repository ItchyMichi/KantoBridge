"""M4 end to end, on the real engine: gen1-bridge's UI observer and answers, the NPC observer,
act.interact, act.button and the mirror in the Gen1Recomp test instance, driven by fake_minecraft
(docs/PLAN.md M4 [T]).

The driver (drivers/kb_ui.lua) starts a new game without the intro, so the player wakes up in
REDS_HOUSE_2F. The test fetches the house's cell flags from the real export as role `builder` (kept
in memory only), then connects as role `test`:

  1. mirror.sub at 30 fps, then walks to the stairs: more than 3 frames of 160x144 (checked from
     the PNG header only), each within size bounds; mirror.unsub
  2. downstairs: the npc.set of REDS_HOUSE_1F (its NPCs counted)
  3. walks next to an NPC there and act.interact's it: act.result ok kind npc, then ui.open kind
     text (its page count is all that is looked at); ui.advance each time the box shows its arrow,
     until ui.close
  4. act.button start: the start menu opens as ui.open kind menu (screen StartMenu); ui.back closes it
  5. the start menu again: the player's own row opens the trainer card, a mirror-kind UI; frames
     arrive for it; act.button b closes it and the start menu comes back; ui.back
  6. a YES/NO the game itself asks: the start menu's QUIT ("return to the title?"): answered NO with
     ui.back (B), then again YES with ui.pick 0 (the cursor on YES, then A): the driver sees the
     title screen (KB_TITLE)

Every message passes its schema (strict); every act.* request gets its act.result. Data safety
(docs/PLAN.md section 0.3): menu labels are compared in memory against the engine's own row names
and never printed; text is never looked at beyond page counts; frames are decoded only as far as
the PNG header and never saved. Output shows message types, kinds, counts, sizes and symbols only.
"""

import asyncio
import base64
import struct
import time
from collections import Counter

import pytest

from fake_minecraft import FakeMinecraft
from run_gen1recomp import launch, preflight
from test_walk import DELTA, DOOR, WARP_TILE, Walker, _export_maps

TEST_PORT = 27195
# the start menu rows the engine names itself (src/ui/StartMenu.lua); the one row that is none of
# these is the player's own name, which opens the trainer card
ENGINE_ROWS = {"POKéDEX", "POKéMON", "ITEM", "SAVE", "OPTION", "MODS", "QUIT"}
QUIET_IGNORES = ("pong", "log", "mirror.frame", "npc.move", "npc.face", "npc.add", "npc.remove", "emote")
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


class UiWalker(Walker):
    """Walker plus what M4 sends: UIs, NPCs, act.result and mirror frames."""

    def __init__(self, mc):
        super().__init__(mc)
        self.uis = {}          # ui id -> {"kind", "data", "state" (merged updates), "closed"}
        self.sequence = []     # ("open"|"close", kind)
        self.results = {}      # re -> act.result data
        self.frames = []       # (w, h, png bytes) of every mirror.frame, from its header only
        self.frame_ns = []
        self.npc_sets = []     # (map, count)
        self.npc_cells = {}    # id -> (x, y) on the current map
        self.updates = Counter()

    def handle(self, m):
        super().handle(m)
        t, d = m["type"], m["data"]
        if t == "npc.set":
            self.npc_sets.append((d["map"], len(d["list"])))
            self.npc_cells = {n["id"]: (n["x"], n["y"]) for n in d["list"]}
            self.npcs = set(self.npc_cells.values())
        elif t in ("npc.add", "npc.move"):
            self.npc_cells[d["id"]] = (d["x"], d["y"])
            self.npcs = set(self.npc_cells.values())
        elif t == "npc.remove":
            self.npc_cells.pop(d["id"], None)
            self.npcs = set(self.npc_cells.values())
        elif t == "ui.open":
            self.uis[d["ui"]] = {"kind": d["kind"], "data": d, "state": {}, "closed": False}
            self.sequence.append(("open", d["kind"]))
        elif t == "ui.update":
            ui = self.uis.get(d["ui"])
            if ui is not None:
                ui["state"].update(d)
            self.updates[ui["kind"] if ui else "?"] += 1
        elif t == "ui.close":
            ui = self.uis.get(d["ui"])
            if ui is not None:
                ui["closed"] = True
                self.sequence.append(("close", ui["kind"]))
        elif t == "act.result":
            self.results[m["re"]] = d
        elif t == "mirror.frame":
            raw = base64.b64decode(d["png"])
            assert raw[:8] == PNG_SIGNATURE and raw[12:16] == b"IHDR", "a mirror frame is not a PNG"
            w, h = struct.unpack(">II", raw[16:24])
            self.frames.append((w, h, len(raw)))
            self.frame_ns.append(d["n"])

    async def idle(self, timeout=30, quiet=0.5):
        """Idle mode and nothing but heartbeats, frames and NPC moves for a moment."""
        deadline = time.monotonic() + timeout
        last = time.monotonic()
        while not (self.mode == "idle" and time.monotonic() - last >= quiet):
            if time.monotonic() > deadline:
                raise AssertionError("timed out waiting for the idle mode")
            m = await self.pump(0.1)
            if m is not None and m["type"] not in QUIET_IGNORES:
                last = time.monotonic()

    async def act(self, msg_type, data, timeout=15):
        sent = await self.mc.send(msg_type, data)
        await self.until(lambda: sent["seq"] in self.results, timeout, f"the act.result of {msg_type}")
        return self.results[sent["seq"]]

    def newest(self, kind, after=0, screen=None):
        for ui_id in sorted(self.uis, reverse=True):
            ui = self.uis[ui_id]
            if ui_id > after and ui["kind"] == kind and (screen is None or ui["data"].get("screen") == screen):
                return ui_id
        return None

    async def opened(self, kind, after, timeout=15, screen=None):
        await self.until(lambda: self.newest(kind, after, screen) is not None, timeout, f"ui.open kind {kind}")
        return self.newest(kind, after, screen)

    async def closed(self, ui_id, timeout=15):
        await self.until(lambda: self.uis[ui_id]["closed"], timeout, "ui.close")

    async def read_text(self, ui_id, timeout=60):
        """Like a player: A each time the box shows its arrow, until it closes."""
        deadline = time.monotonic() + timeout
        answered = set()
        sent = 0
        ui = self.uis[ui_id]
        while not ui["closed"]:
            st = ui["state"]
            stop = (st.get("page", 0), st.get("line", 0), st.get("done", False))
            if st.get("waiting") and stop not in answered:
                answered.add(stop)
                await self.mc.send("ui.advance", {"ui": ui_id})
                sent += 1
            if time.monotonic() > deadline:
                raise AssertionError("the text box never closed")
            await self.pump(0.1)
        return sent

    async def start_menu(self):
        last = max(self.uis, default=0)
        result = await self.act("act.button", {"button": "start", "how": "tap"})
        assert result == {"ok": True}, result
        return await self.opened("menu", last, screen="StartMenu")


async def _ui(port):
    grids = await _export_maps(port)
    mc = FakeMinecraft(port=port, role="test", ping_ms=300)
    await mc.connect(attempts=20)
    await mc.hello()
    w = UiWalker(mc)
    await w.until(lambda: w.pos is not None and w.npc_sets, 10, "the snapshot and npc.set")
    assert w.pos[0] == "REDS_HOUSE_2F", w.pos[0]
    await w.idle()
    report = {}

    # 1. the mirror while walking to the stairs
    await mc.send("mirror.sub", {"fps": 30})
    up = grids["REDS_HOUSE_2F"]
    stairs = [(x, y) for y in range(up.h) for x in range(up.w)
              if up.warp(x, y) and up.flags(x, y) & WARP_TILE and not up.flags(x, y) & DOOR]
    assert stairs, "no stairs warp upstairs"
    report["steps_to_stairs"] = await w.walk_to(up, stairs)
    await w.until(lambda: len(w.warps) >= 2, 20, "the stairs warp")
    await w.idle()
    await mc.send("mirror.unsub", {})
    await asyncio.sleep(0.3)
    report["walk_frames"] = len(w.frames)
    assert w.pos[0] == "REDS_HOUSE_1F", w.pos[0]

    # 2. the NPCs downstairs
    await w.until(lambda: any(m == "REDS_HOUSE_1F" for m, _ in w.npc_sets), 10, "REDS_HOUSE_1F's npc.set")
    assert w.npc_cells, "no NPC downstairs"

    # 3. walk next to an NPC and talk to it
    down = grids["REDS_HOUSE_1F"]
    npc_id = sorted(w.npc_cells)[0]
    nx, ny = w.npc_cells[npc_id]
    beside = [(nx + dx, ny + dy) for dx, dy in DELTA.values()
              if down.walkable(nx + dx, ny + dy) and not down.warp(nx + dx, ny + dy)]
    assert beside, "no free cell beside the NPC"
    report["steps_to_npc"] = await w.walk_to(down, beside)
    await w.idle()
    last = max(w.uis, default=0)
    result = await w.act("act.interact", {"map": "REDS_HOUSE_1F", "x": nx, "y": ny})
    assert result == {"ok": True, "kind": "npc"}, result
    # the conversation: every text box it opens, each read to its close
    pages, advances, after = [], 0, last
    for _ in range(6):
        try:
            box = await w.opened("text", after, timeout=3)
        except AssertionError:
            break
        pages.append(len(w.uis[box]["data"]["pages"]))
        advances += await w.read_text(box)
        after = box
    report["text_pages"], report["advances"] = pages, advances
    assert pages and pages[0] >= 1, "no text box opened"
    await w.idle()

    # 4. the start menu: open with START, close with B
    menu = await w.start_menu()
    report["start_menu_rows"] = len(w.uis[menu]["data"]["items"])
    report["start_menu_wrap"] = w.uis[menu]["data"].get("wrap")
    await mc.send("ui.back", {"ui": menu})
    await w.closed(menu)
    await w.idle()

    # 5. the trainer card: a mirror-kind UI, driven with act.button
    menu = await w.start_menu()
    labels = [item["label"] for item in w.uis[menu]["data"]["items"]]
    own = [i for i, label in enumerate(labels) if label not in ENGINE_ROWS]
    assert len(own) == 1, "the player's own row isn't the one unknown start menu row"
    await mc.send("ui.pick", {"ui": menu, "index": own[0]})
    card = await w.opened("mirror", menu)
    report["card_screen"] = w.uis[card]["data"].get("screen")
    before = len(w.frames)
    await mc.send("mirror.sub", {"fps": 10})
    await w.until(lambda: len(w.frames) > before, 10, "a frame of the trainer card")
    report["card_frames"] = len(w.frames) - before
    result = await w.act("act.button", {"button": "b", "how": "tap"})
    assert result == {"ok": True}, result
    await w.closed(card)
    await mc.send("mirror.unsub", {})
    again = await w.opened("menu", card, screen="StartMenu")
    await mc.send("ui.back", {"ui": again})
    await w.closed(again)
    await w.idle()

    # 6. a YES/NO from the game: QUIT -> "return to the title?" answered NO (B) ...
    async def quit_prompt():
        menu = await w.start_menu()
        rows = [item["label"] for item in w.uis[menu]["data"]["items"]]
        assert "QUIT" in rows, "no QUIT row"
        await mc.send("ui.pick", {"ui": menu, "index": rows.index("QUIT")})
        prompt = await w.opened("text", menu)
        choice = await w.opened("choice", prompt)
        report.setdefault("prompt_choice_flags", []).append(w.uis[prompt]["data"].get("choice"))
        return prompt, choice

    prompt, choice = await quit_prompt()
    data = w.uis[choice]["data"]
    report["choice"] = (len(data["labels"]), data["index"], "question" in data)
    assert len(data["labels"]) == 2 and data["index"] == 1, "QUIT's YES/NO starts on NO"
    await mc.send("ui.back", {"ui": choice})
    await w.closed(prompt)
    await w.idle()
    assert w.pos[0] == "REDS_HOUSE_1F" and w.mode == "idle"
    status = (await mc.request("debug.status", reply_type="debug.status", timeout=10))["data"]

    # ... and then YES (ui.pick 0): back to the title screen
    prompt, choice = await quit_prompt()
    await mc.send("ui.pick", {"ui": choice, "index": 0})
    await w.closed(prompt, timeout=20)
    await asyncio.sleep(1.0)
    after = (await mc.request("debug.status", reply_type="debug.status", timeout=10))["data"]
    await mc.send("debug.quit", {"code": 0})
    await mc.close()
    return w, report, status, after


@pytest.mark.e2e
@pytest.mark.timeout(600)
def test_dialogue_menus_npcs_and_the_mirror():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    with launch("kb_ui.lua", extra_env={"KB_TEST_PORT": str(TEST_PORT)}) as running:
        marker = running.wait_marker("KB_OVERWORLD", timeout=150)
        assert marker is not None, running.result.summary()
        assert f"port={TEST_PORT}" in marker
        w, report, status, after = asyncio.run(asyncio.wait_for(_ui(TEST_PORT), 480))
        result = running.wait(timeout=60)

    sizes = [size for _, _, size in w.frames]
    dims = sorted({(fw, fh) for fw, fh, _ in w.frames})
    print("report:", report)
    print("npc.set (map, count):", w.npc_sets)
    print("ui sequence:", w.sequence)
    print("ui updates by kind:", dict(w.updates))
    print("act.result:", sorted((k, tuple(sorted(v.items()))) for k, v in w.results.items()))
    print("mirror frames:", len(w.frames), "dims:", dims, "png bytes min/max:", (min(sizes), max(sizes)) if sizes else None)
    print("mirror cost (us): captures", status["mirrorCaptures"], "skipped", status["mirrorSkipped"],
          "avg", status["mirrorAvgUs"], "max", status["mirrorMaxUs"], "encode avg", status["mirrorEncodeAvgUs"])
    print("status:", {k: v for k, v in sorted(status.items()) if k.startswith(("ui", "npc", "interact", "mirror"))})
    print("after YES:", {k: after[k] for k in ("ingame", "topState", "mode", "uiOpen")})
    print("messages received:", dict(sorted(w.counts.items())))
    print("driver:", [line for line in result.lines if line.startswith(("KB_UI", "KB_FRAMES", "KB_TITLE", "KB_QUIT"))])

    assert not w.mc.conn.problems  # strict: every message passed its schema
    assert w.counts["error"] == 0
    # 1. the mirror
    assert report["walk_frames"] > 3
    assert dims == [(160, 144)]
    # a solid frame (a warp fade) compresses to about 100 bytes; a room to a couple of KB
    assert all(60 <= size <= 64 * 1024 for size in sizes), (min(sizes), max(sizes))
    assert sum(size > 500 for size in sizes) > 3, "pictures, not only fades"
    assert w.frame_ns == sorted(w.frame_ns) and len(set(w.frame_ns)) == len(w.frame_ns)
    # 2. NPCs: the house's two maps were each sent as a whole
    maps = [m for m, _ in w.npc_sets]
    assert "REDS_HOUSE_2F" in maps and "REDS_HOUSE_1F" in maps
    assert [n for m, n in w.npc_sets if m == "REDS_HOUSE_1F"][-1] >= 1
    # 3. talking
    assert report["text_pages"] and report["advances"] >= len(report["text_pages"])
    # 5. the trainer card is a mirror screen
    assert report["card_screen"] == "TrainerCard" and report["card_frames"] >= 1
    # 6. the YES/NO, both ways
    assert report["choice"][:2] == (2, 1)
    assert report["prompt_choice_flags"] == [True, True], "the prompt's text says a choice follows"
    assert report["start_menu_wrap"] is True
    assert after["ingame"] is False, "YES took the game back to the title"
    assert result.marker("KB_TITLE") is not None
    kinds_opened = Counter(kind for event, kind in w.sequence if event == "open")
    assert kinds_opened["menu"] >= 5 and kinds_opened["choice"] >= 2 and kinds_opened["mirror"] >= 1
    assert kinds_opened["text"] >= 3
    # every act.* request answered
    assert all(set(v) <= {"ok", "kind", "reason"} for v in w.results.values())
    assert status["interactOk"] == 1 and status["uiactBacks"] >= 3
    assert not result.timed_out and result.returncode == 0, result.summary()
    assert result.marker("KB_QUIT") is not None and result.marker("KB_FAIL") is None
    assert int(result.facts("KB_FRAMES")["on"]) > 0, "frames were timed with the mirror on"
    assert not result.crash_log_touched
    assert not result.error_spots
