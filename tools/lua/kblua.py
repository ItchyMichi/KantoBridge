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
    """

    def __init__(self, permissions=PERMISSIONS, modules=None, developer=False, strict=True):
        self.rt = luajit.LuaRuntime(unpack_returned_tuples=True, encoding=None)
        self.lua = self.rt.globals()
        self.Sandbox = self._load_harness("sandbox.lua")
        self.FakeMod = self._load_harness("fake_mod.lua")
        self.FakeSocket = self._load_harness("fake_socket.lua")
        self.fake = self.FakeSocket.new()
        self.net = self.fake.net
        self.read_calls: list[str] = []

        mods = self.table()
        mods[b"socket"] = self.fake.socket
        for name, value in (modules or {}).items():
            mods[_b(name)] = self.eval(value) if isinstance(value, str) else value
        perms = self.table()
        for p in permissions:
            perms[_b(p)] = True
        opts = self.table()
        opts[b"permissions"] = perms
        opts[b"modules"] = mods
        opts[b"strict"] = strict
        self.env = self.Sandbox.env(opts)

        mod_opts = self.table()
        mod_opts[b"read"] = self._read
        mod_opts[b"developer"] = developer
        self.mod = self.FakeMod.new(mod_opts)

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
