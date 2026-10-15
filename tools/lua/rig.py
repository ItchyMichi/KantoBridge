"""A test rig: gen1-bridge's main.lua running in the sandbox under lupa, on the
fake engine, with fake Minecraft peers on the fake socket.

    rig = Rig()                        # main.lua loaded, game.ready emitted
    rig.world("TEST_TOWN", 5, 6)       # the overworld is on the stack
    peer = rig.connect()               # a client on 127.0.0.1:27151
    peer.send("hello", {...}); rig.step()
    peer.messages()                    # decoded and schema-checked (leg g2m)

    rig = Rig(family="gen3")           # MG7: Emerald on the game3 fake (fake_engine3.lua)
    rig.world("EM_TEST_TOWN", 5, 6)    # the field is up (E.enterField)

Every message a peer receives is checked against protocol/ with
kb_protocol, strictly: a schema problem fails the test at once. Everything
here is synthetic (made-up maps, items and text).
"""

from __future__ import annotations

import json

from kb_protocol import load_protocol
from kblua import Bridge

PROTOCOL = load_protocol()
HOST = b"127.0.0.1"

# made-up species and moves for mod.content (the real ones come from the import)
CONTENT = {
    "pokemon": {"TESTMON": {"dex": 7}, "OTHERMON": {"dex": 16}, "BIGMON": {"dex": 251}},
    "moves": {"TEST_MOVE": {"index": 33}, "OTHER_MOVE": {"index": 39}},
}


def call(obj, name, *args):
    return obj[name.encode()](obj, *args)


class Peer:
    """One fake Minecraft connection, speaking NDJSON through the fake socket."""

    def __init__(self, rig, handle):
        self.rig = rig
        self.handle = handle
        self.seq = 0
        self.buffer = b""

    def send_raw(self, data: bytes):
        call(self.handle, "send", data)

    def send(self, msg_type, data=None, re=None, seq=None):
        if seq is None:
            self.seq += 1
            seq = self.seq
        env = {"type": msg_type, "seq": seq, "data": {} if data is None else data}
        if re is not None:
            env["re"] = re
        self.send_raw(json.dumps(env, ensure_ascii=False).encode("utf-8") + b"\n")
        return seq

    def messages(self, check=True):
        """Everything received since the last call, decoded and schema-checked."""
        self.buffer += call(self.handle, "take")
        *lines, self.buffer = self.buffer.split(b"\n")
        out = []
        for line in lines:
            if not line:
                continue
            msg = json.loads(line.decode("utf-8"))
            if check:
                problems = PROTOCOL.check(msg, leg="g2m")
                assert not problems, (msg["type"], [str(p) for p in problems])
            out.append(msg)
        return out

    def types(self):
        return [m["type"] for m in self.messages()]

    def closed(self) -> bool:
        return bool(call(self.handle, "closedByServer"))

    def close(self):
        call(self.handle, "close")

    def hello(self, role="test", protocol=1, steps=1):
        seq = self.send("hello", {"protocol": protocol, "role": role, "mod": "rig-0.1.0"})
        self.rig.step(steps)
        return seq


class Rig:
    """``world`` (a dict from export_world.world()) installs a made-up world for the export:
    dataset views, live data and images. ``dataset_mode`` is the fake view's behaviour:
    "ok", "missing" (open fails) or "broken" (it reads nothing). ``loader`` (fake_loader's
    keywords) puts a stand-in mod loader in mods.loaded's payload (the profile plan)."""

    def __init__(self, ready=True, world=None, dataset_mode="ok", loader=None, prepare=None, **kw):
        kw.setdefault("content", CONTENT)
        self.b = Bridge(engine=True, **kw)
        self.E = self.b.engine
        if world is not None:
            self.E.setWorld(self.b.to_lua(world))
        self.E.datasetMode = dataset_mode.encode()
        # MG9 (G2H): ``prepare(E, b)`` sets the fake up before main.lua's entry chunk runs (a Gen 2 route end is
        # planned and registered there, on the town as the data holds it)
        if prepare is not None:
            prepare(self.E, self.b)
        self.b.run_main()
        if ready:
            self.b.mods_loaded(None if loader is None else self.b.fake_loader(**loader))
            self.b.ready()

    @property
    def status(self):
        return self.b.mod.exports

    def world(self, map_id="TEST_TOWN", x=5, y=6, facing="down"):
        self.E.enterWorld(map_id.encode(), x, y, facing.encode())

    def simulate(self, spec):
        """Installs a simulated overworld (tools/lua/fake_world.lua): from now on every step also
        runs Input:step and the top state's update, with the engine's step timing."""
        self.E.simulate(self.b.to_lua(spec))

    def step(self, n=1):
        self.b.step(n)

    def advance(self, seconds):
        """Moves the clock without running steps (a long frame)."""
        self.b.now = self.b.now + seconds

    def connect(self, port=27151, steps=1):
        fake = self.b.net
        if not self.listening(port):
            self.step()  # the listener binds lazily, on a step
        handle = fake.connect(fake, HOST, port)
        assert not isinstance(handle, tuple), handle  # (nil, "connection refused")
        peer = Peer(self, handle)
        self.step(steps)
        return peer

    def listening(self, port=27151) -> bool:
        fake = self.b.net
        return bool(fake.listening(fake, HOST, port))

    def logs(self):
        return self.b.log_lines()
