"""Run gen1-bridge's Lua under lupa (LuaJIT 2.1) inside a copy of Gen1Recomp's
mod sandbox.

    from kblua import Bridge
    b = Bridge()                         # sandbox env + fake mod + fake socket
    json = b.module("lib/kb_json.lua")   # loaded like main.lua loads it
    b.run_main()                         # the entry chunk, like the loader

Strings cross the boundary as bytes (the runtime has no encoding), so binary
data survives; use ``b.s("text")`` to pass text. Everything here is ROM-free.
"""

from __future__ import annotations

import faulthandler
from pathlib import Path

# LuaJIT on Windows x64 unwinds every error() with an SEH exception (code
# 0xE24C4A02) that it catches itself. Python's faulthandler, which pytest
# enables, reports each one as a "Windows fatal exception" with a stack dump,
# burying real output, so it is switched off once Lua tests load.
if faulthandler.is_enabled():
    faulthandler.disable()

try:
    # Explicitly LuaJIT 2.1, the engine's runtime. A bare `import lupa` picks
    # the newest bundled Lua (5.5), which has different semantics.
    import lupa.luajit21 as luajit
except ImportError as exc:  # pragma: no cover - the venv pins lupa 2.8
    raise ImportError(
        "lupa.luajit21 is required (tools/requirements.txt pins lupa 2.8); "
        "run tools/setup.ps1 or tools/setup.sh"
    ) from exc

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
BRIDGE = REPO / "gen1-bridge"

PERMISSIONS = ("network", "engine_internals")  # gen1-bridge/manifest.json


def _b(value):
    return value.encode("utf-8") if isinstance(value, str) else value


class Bridge:
    """One fresh LuaJIT state with the sandbox, a fake ``mod`` and a fake socket.

    ``modules`` maps require names to values (or Lua code returning a value)
    for the sandbox's require; ``socket`` is the fake LuaSocket by default.
    ``strict`` (the default) makes reading an unknown global, or creating a
    new one, an error, so typos and leaks fail the tests.

    ``engine=True`` adds ``fake_engine.lua``: its modules join ``modules``,
    its UI tables become ``mod.ui``, and ``self.engine`` controls the fake
    game. ``love.timer.getTime`` reads ``self.now`` (seconds), which
    ``step()`` advances by one logic step at a time. ``graphics=True`` adds a fake
    ``love.graphics`` and ``love.data`` (``fake_love.lua``, controlled through ``self.love``).
    """

    STEP = 1 / 60

    def __init__(self, permissions=PERMISSIONS, modules=None, developer=False, strict=True,
                 engine=False, mod_options=None, content=None, game="blue", graphics=False,
                 script=False):
        self.rt = luajit.LuaRuntime(unpack_returned_tuples=True, encoding=None)
        self.lua = self.rt.globals()
        self.Sandbox = self._load_harness("sandbox.lua")
        self.FakeMod = self._load_harness("fake_mod.lua")
        self.FakeSocket = self._load_harness("fake_socket.lua")
        self.fake = self.FakeSocket.new()
        self.net = self.fake.net
        self.read_calls: list[str] = []
        self.engine = None
        if engine:
            FakeEngine = self._load_harness("fake_engine.lua")
            eopts = self.table()
            eopts[b"game"] = _b(game)
            self.engine = FakeEngine.new(eopts)
            self._load_harness("fake_world.lua").attach(self.engine)
            self._load_harness("fake_ui.lua").attach(self.engine)
            if script:
                # the script runner, battles, trainers and gifts (M5), then wild encounters,
                # fishing, the bag and item use (M6); before the modules are handed to the
                # sandbox, since they add engine modules
                self._load_harness("fake_script.lua").attach(self.engine)
                self._load_harness("fake_wild.lua").attach(self.engine)
                # M7: the Pokemon Center, the PC, the Mart, saving and trainer sight
                self._load_harness("fake_center.lua").attach(self.engine)

        # love facade source: only timer is needed so far. perCall advances the clock on every
        # read, so work inside one step "takes time" (budget tests); 0 by default.
        self._clock = self.eval("{ now = 0, perCall = 0 }")
        love = self.eval("function(c) return { timer = { getTime = function()"
                         " c.now = c.now + c.perCall return c.now end } } end")(self._clock)
        # graphics=True adds fake_love.lua's love.graphics and love.data (the mirror)
        self.love = None
        if graphics:
            self.love = self._load_harness("fake_love.lua").new()
            love[b"graphics"] = self.love.graphics
            love[b"data"] = self.love.data

        mods = self.table()
        mods[b"socket"] = self.fake.socket
        if self.engine is not None:
            for name, value in self.engine.modules.items():
                mods[name] = value
        for name, value in (modules or {}).items():
            mods[_b(name)] = self.eval(value) if isinstance(value, str) else value
        perms = self.table()
        for p in permissions:
            perms[_b(p)] = True
        opts = self.table()
        opts[b"permissions"] = perms
        opts[b"modules"] = mods
        opts[b"strict"] = strict
        opts[b"love"] = love
        self._env_opts = opts
        self.env = self.Sandbox.env(opts)

        mod_opts = self.table()
        mod_opts[b"read"] = self._read
        mod_opts[b"developer"] = developer
        if self.engine is not None:
            mod_opts[b"ui"] = self.engine.ui
            mod_opts[b"datasets"] = self.engine.datasets
        if mod_options:
            mod_opts[b"options"] = self.to_lua(mod_options)
        if content:
            mod_opts[b"content"] = self.to_lua(content)
        self._mod_opts = mod_opts
        self.mod = self.FakeMod.new(mod_opts)
        if self.engine is not None:
            self.engine.bindMod(self.FakeMod, self.mod)  # the fake world's events and hooks

    # ------------------------------------------------------------ clock, steps

    @property
    def now(self) -> float:
        return self._clock[b"now"]

    @now.setter
    def now(self, value: float):
        self._clock[b"now"] = value

    @property
    def clock_per_call(self) -> float:
        return self._clock[b"perCall"]

    @clock_per_call.setter
    def clock_per_call(self, value: float):
        self._clock[b"perCall"] = value

    def ready(self):
        """Emits game.ready with the fake game, as Game:load does (Game.lua:119)."""
        payload = self.table()
        payload[b"game"] = self.engine.game if self.engine is not None else self.table()
        self.emit("game.ready", payload)

    def mods_loaded(self):
        """Emits mods.loaded with the live data, as the loader does after the merge
        (Loader.lua:2163), before game.ready."""
        payload = self.table()
        payload[b"data"] = self.engine.data if self.engine is not None else self.table()
        self.emit("mods.loaded", payload)

    def step(self, n: int = 1):
        """Runs n logic steps: the clock advances and the input.step hooks run, then (with the
        fake world simulating, fake_world.lua) Input:step and the top state's update."""
        vanilla = self.eval("function() end")
        game = self.engine.game if self.engine is not None else self.table()
        for _ in range(n):
            self.now = self.now + self.STEP
            self.FakeMod.call(self.mod, b"input.step", vanilla, game, self.STEP)
            if self.engine is not None:
                self.engine.tick()

    def reload(self):
        """Simulates F5 (HotReload.run, src/dev/HotReload.lua:27-64): a fresh sandbox env and
        mod object run main.lua again, then game.ready reaches only the new instance. The old
        instance's hooks are never called again; the fake socket and fake game carry over."""
        self.env = self.Sandbox.env(self._env_opts)
        self.mod = self.FakeMod.new(self._mod_opts)
        if self.engine is not None:
            self.engine.bindMod(self.FakeMod, self.mod)
        self.run_main()
        self.mods_loaded()
        self.ready()

    def to_lua(self, value):
        """A Python value as Lua (dicts and lists become tables, str becomes bytes)."""
        if isinstance(value, dict):
            t = self.table()
            for k, v in value.items():
                t[_b(k)] = self.to_lua(v)
            return t
        if isinstance(value, (list, tuple)):
            t = self.table()
            for i, v in enumerate(value, start=1):
                t[i] = self.to_lua(v)
            return t
        return _b(value)

    # ------------------------------------------------------------ helpers

    def _load_harness(self, name):
        source = (HERE / name).read_bytes()
        chunk = self.rt.execute(b"return function(src, name) return assert(loadstring(src, name)) end")
        return chunk(source, _b("@" + name))()

    def _read(self, rel):
        rel = rel.decode("utf-8") if isinstance(rel, bytes) else rel
        self.read_calls.append(rel)
        path = (BRIDGE / rel).resolve()
        if BRIDGE.resolve() not in path.parents or not path.is_file():
            return None, b"not found"
        return path.read_bytes()

    def table(self, *args, **kwargs):
        return self.rt.table(*args, **kwargs)

    def eval(self, code: str):
        """Evaluates Lua in the harness (unsandboxed) state."""
        return self.rt.eval(_b(code))

    def execute(self, code: str):
        return self.rt.execute(_b(code))

    @staticmethod
    def s(text: str) -> bytes:
        return text.encode("utf-8")

    # ------------------------------------------------------------ loading

    def module(self, rel: str):
        """Loads a gen1-bridge file into the sandbox env, as main.lua does."""
        source = (BRIDGE / rel).read_bytes()
        # `import` is a Python keyword, so call the Lua function by index
        return self.Sandbox[b"import"](self.env, source, _b(rel))

    def run_main(self):
        """Runs main.lua as the loader would (Loader.lua:1793-1803)."""
        source = (BRIDGE / "main.lua").read_bytes()
        self.Sandbox.runEntry(self.env, source, b"main.lua", self.mod)

    def emit(self, name: str, payload=None):
        self.FakeMod.emit(self.mod, _b(name), payload)

    def log_lines(self) -> list[str]:
        out = []
        log = self.mod._log
        for i in range(1, len(log) + 1):
            out.append(log[i].decode("utf-8"))
        return out

    def env_keys(self) -> set[bytes]:
        return set(self.rt.eval(b"function(t) local k = {} for n in next, t do k[#k + 1] = n end return k end")(
            self.env).values())
