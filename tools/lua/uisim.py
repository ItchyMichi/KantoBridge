"""A simulated room with gen1-bridge's main.lua and one connected peer, for the M4 tests (UI,
NPCs, interaction, the mirror). Everything is made up: the maps, the NPCs and every text.

    g = Game()                     # TEST_ROOM, the player at (3, 3) facing up, role test
    g.push(g.E.textBox(g.s("HELLO!"), g.opts(stay=True)))
    g.run(5)                       # steps; every received message is schema-checked
    g.received("ui.open")          # messages of a type received so far
    g.send("ui.advance", {"ui": 1})
    g.taps()                       # the buttons gen1-bridge tapped through mod.input
"""

from __future__ import annotations

from rig import Rig

ROOM = {
    "rows": [
        "##########",  # 0
        "#........#",  # 1  a clerk (3,1) behind the counter, a girl (6,1)
        "#..C.....#",  # 2  the counter (3,2)
        "#........#",  # 3  the player starts at (3,3); a sign at (8,3)
        "#........#",  # 4
        "##########",
    ],
    "npcs": [
        {"id": "TEST_ROOM_obj_1", "x": 3, "y": 1, "sprite": "SPRITE_TEST_CLERK", "facing": "left"},
        {"id": "TEST_ROOM_obj_2", "x": 6, "y": 1, "sprite": "SPRITE_TEST_GIRL"},
        {"id": "TEST_ROOM_obj_3", "x": 1, "y": 4, "sprite": "SPRITE_TEST_BALL", "item": "TEST_ITEM"},
    ],
    "signs": [{"x": 8, "y": 3, "text": "A TEST SIGN."}],
}
HALL = {"rows": ["######", "#....#", "#....#", "######"],
        "npcs": [{"id": "TEST_HALL_obj_1", "x": 2, "y": 1, "sprite": "SPRITE_TEST_GUARD", "trainerClass": "TEST"}]}
MAPS = {"TEST_ROOM": ROOM, "TEST_HALL": HALL}


class Game:
    def __init__(self, start=("TEST_ROOM", 3, 3, "up"), role="test", maps=None, connect=True, **kw):
        self.rig = Rig(**kw)
        m, x, y, f = start
        self.rig.simulate({"maps": maps or MAPS, "start": {"map": m, "x": x, "y": y, "facing": f}})
        self.E = self.rig.E
        self.b = self.rig.b
        self.log = []
        self.peer = None
        if connect:
            self.connect(role)

    def connect(self, role="test"):
        self.peer = self.rig.connect()
        self.peer.hello(role=role)
        self.log.extend(self.peer.messages())
        return self.peer

    # ------------------------------------------------------------ Lua values

    def s(self, text: str) -> bytes:
        return text.encode("utf-8")

    def opts(self, **kw):
        t = self.b.table()
        for k, v in kw.items():
            if isinstance(v, str):
                v = v.encode("utf-8")
            elif isinstance(v, (dict, list)):
                v = self.b.to_lua(v)
            t[k.encode()] = v
        return t

    def items(self, rows):
        """A Lua list of menu/list rows from dicts (strings become bytes)."""
        t = self.b.table()
        for i, row in enumerate(rows, start=1):
            r = self.b.table()
            for k, v in row.items():
                r[k.encode()] = v.encode("utf-8") if isinstance(v, str) else v
            t[i] = r
        return t

    def push(self, state):
        return self.E.uiPush(state)

    def top(self):
        return self.E.stack.top(self.E.stack)

    def depth(self):
        return len(self.E.stack.states)

    # ------------------------------------------------------------ running

    def run(self, n=1):
        out = []
        for _ in range(n):
            self.rig.step()
            if self.peer is not None:
                got = self.peer.messages()
                self.log.extend(got)
                out.extend(got)
        return out

    def until(self, pred, limit=400):
        for _ in range(limit):
            for m in self.run():
                if pred(m):
                    return m
        raise AssertionError("no matching message within the limit")

    def send(self, msg_type, data=None):
        return self.peer.send(msg_type, data)

    def received(self, msg_type=None):
        return [m for m in self.log if msg_type is None or m["type"] == msg_type]

    def taps(self):
        """Every mod.input call so far: ('tap', 'a'), ('press', 'b'), ('release', None)."""
        calls = self.b.mod._input
        out = []
        for i in range(1, len(calls) + 1):
            c = calls[i]
            btn = c.btn if c.btn is not None else (c.token.btn if c.token is not None else None)
            out.append((c.op.decode(), btn.decode() if btn is not None else None))
        return out

    def tapped(self):
        return [btn for op, btn in self.taps() if op == "tap"]

    def status(self):
        seq = self.send("debug.status")
        reply = self.until(lambda m: m["type"] == "debug.status" and m.get("re") == seq)
        return reply["data"]
