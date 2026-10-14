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
import json
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
    game. ``game`` is the id the fake engine's GameVersion reports (MG1: any
    catalogue id); ``manifest`` the fake mod's manifest (its ``games`` list). ``love.timer.getTime`` reads ``self.now`` (seconds), which
    ``step()`` advances by one logic step at a time. ``graphics=True`` adds a fake
    ``love.graphics`` and ``love.data`` (``fake_love.lua``, controlled through ``self.love``).

    MG7: ``family="gen3"`` loads ``fake_engine3.lua`` (Gen1Recomp's ``game3`` engine, which
    ``lib/kb_engine.lua`` answers with ``lib/engine/game3.lua``) instead of the Gen 1 fake, and implies
    ``engine=True``; ``game`` then defaults to "emerald", ``engine_id`` to "game3", and the mod's
    manifest to gen1-bridge's own ``games`` (the engine loads the mod on Emerald only for a manifest that
    names it). The Gen 1 fake's add-ons (``script``, ``save_files``, ``world_api``) are Gen 1's only.
    """

    STEP = 1 / 60

    def __init__(self, permissions=PERMISSIONS, modules=None, developer=False, strict=True,
                 engine=False, mod_options=None, content=None, game=None, graphics=False,
                 script=False, mod_cache=None, save_files=False, window=False, files=None,
                 registries=None, world_api=False, jit_os="Windows", manifest=None, engine_id=None,
                 family="gen1"):
        assert family in ("gen1", "gen3"), family
        gen3 = family == "gen3"
        if gen3:
            assert not (script or save_files or world_api), "the Gen 1 fake's add-ons have no Gen 3 counterpart"
            engine = True
            if manifest is None:
                games = json.loads((BRIDGE / "manifest.json").read_text(encoding="utf-8"))["games"]
                manifest = {"id": "gen1-bridge", "games": games}
        if game is None:
            game = "emerald" if gen3 else "blue"
        if engine_id is None:
            engine_id = "game3" if gen3 else "gen1"
        self.family = family
        self.rt = luajit.LuaRuntime(unpack_returned_tuples=True, encoding=None)
        # the platform main.lua sees (jit.os): Windows by default, whatever the host, so every test
        # runs the same on a PC and a Linux pod; "Linux"/"OSX" for the SO_REUSEADDR rule
        self.jit_os = jit_os
        # Wild Routes (WR2): mod:read sees these files first (rel -> text), e.g. a made-up
        # data/routes.json
        self.files = {k: _b(v) for k, v in (files or {}).items()}
        self.lua = self.rt.globals()
        self.Sandbox = self._load_harness("sandbox.lua")
        self.FakeMod = self._load_harness("fake_mod.lua")
        self.FakeSocket = self._load_harness("fake_socket.lua")
        self.fake = self.FakeSocket.new()
        self.net = self.fake.net
        self.read_calls: list[str] = []
        self.engine = None
        if gen3:
            # MG7 (GEN3_BRIDGE_PLAN.md §8.1): the game3 engine's modules and the Game3 object
            eopts = self.table()
            eopts[b"game"] = _b(game)
            eopts[b"engineId"] = _b(engine_id)
            self.engine = self._load_harness("fake_engine3.lua").new(eopts)
            # G3D: the export's engine parts and love.image / love.data (fake_export3.lua)
            self._load_harness("fake_export3.lua").attach(self.engine)
            # G3F: battles, the party, the bag (fake_battle3.lua)
            self._load_harness("fake_battle3.lua").attach(self.engine)
        elif engine:
            FakeEngine = self._load_harness("fake_engine.lua")
            eopts = self.table()
            eopts[b"game"] = _b(game)
            # MG2: what GameVersion.engine() reports ("gen1" | "gs" | "crystal" | "game3"); kb_engine
            # loads the adapter for it, or its stub
            eopts[b"engineId"] = _b(engine_id)
            self.engine = FakeEngine.new(eopts)
            self._load_harness("fake_world.lua").attach(self.engine)
            self._load_harness("fake_ui.lua").attach(self.engine)
            # M8: field moves, live map blocks, spinners and currents
            self._load_harness("fake_field.lua").attach(self.engine)
            if script:
                # the script runner, battles, trainers and gifts (M5), then wild encounters,
                # fishing, the bag and item use (M6); before the modules are handed to the
                # sandbox, since they add engine modules
                self._load_harness("fake_script.lua").attach(self.engine)
                self._load_harness("fake_wild.lua").attach(self.engine)
                # M7: the Pokemon Center, the PC, the Mart, saving and trainer sight
                self._load_harness("fake_center.lua").attach(self.engine)
                # M8 (wave 2): direct battles, talk scripts, trades, the Name Rater, the
                # daycare, native catches, the Hall of Fame, the music API
                self._load_harness("fake_content.lua").attach(self.engine)

        # love facade source: only timer is needed so far. perCall advances the clock on every
        # read, so work inside one step "takes time" (budget tests); 0 by default. M10: sleep
        # advances the clock too (and is recorded in c.sleeps).
        self._clock = self.eval("{ now = 0, perCall = 0, sleeps = {} }")
        love = self.eval("function(c) return { timer = { getTime = function()"
                         " c.now = c.now + c.perCall return c.now end,"
                         " sleep = function(s) c.sleeps[#c.sleeps + 1] = s c.now = c.now + s end } } end")(
            self._clock)
        # M10: window=True adds a fake love.window (isVisible, isMinimized) whose answers the
        # tests set through self.window.visible / .minimized
        self.window = None
        if window:
            self.window = self.eval("{ visible = true, minimized = false }")
            win = self.eval("function(w) return { isVisible = function() return w.visible end,"
                            " isMinimized = function() return w.minimized end } end")(self.window)
            love[b"window"] = win
            self.window_table = win
        self._love = love
        # graphics=True adds fake_love.lua's love.graphics and love.data (the mirror)
        self.love = None
        if graphics:
            self.love = self._load_harness("fake_love.lua").new()
            love[b"graphics"] = self.love.graphics
            love[b"data"] = self.love.data
        if self.family == "gen3":
            # G3D: love.image for the export's pages (and love.data unless the mirror's fake has it)
            love[b"image"] = self.engine.loveImage
            if not graphics:
                love[b"data"] = self.engine.loveData

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
        opts[b"jitOs"] = _b(self.jit_os)
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
        # Wild Routes (WR2): full content registries (fake_registry.lua; ``registries`` is
        # {"records": {registry: {id: record}}, "reject": {registry: True}}) and mod.world
        # (fake_worldapi.lua, on the fake engine)
        self.registries = None
        if registries is not None:
            self.FakeRegistry = self._load_harness("fake_registry.lua")
            self.registries = self.FakeRegistry.content(self.to_lua(registries))
            mod_opts[b"registries"] = self.registries
        self.world_api = None
        if world_api:
            assert self.engine is not None, "world_api needs engine=True"
            self.world_api = self._load_harness("fake_worldapi.lua").new(self.engine)
            mod_opts[b"world"] = self.world_api
        # M9: mod.cache's files (rel -> bytes), e.g. another run's, for a simulated restart
        mod_opts[b"cache"] = self.to_lua(mod_cache or {})
        # MG1: the mod's manifest as the loader hands it over (mod.manifest; its `games` list),
        # e.g. {"id": "gen1-bridge", "games": ["red", "blue"]}; None is the fake mod's bare
        # manifest (no `games`: Gen 1 only, as the engine reads an omitted list)
        if manifest is not None:
            mod_opts[b"manifest"] = self.to_lua(manifest)
        self._mod_opts = mod_opts
        self.mod = self.FakeMod.new(mod_opts)
        if self.engine is not None:
            self.engine.bindMod(self.FakeMod, self.mod)  # the fake world's events and hooks
            # M10: mod.save backed by the live save's modData, and save files per slot
            # (fake_center.lua; needs script=True)
            if save_files:
                self.engine.useSaveFiles(self.mod)

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
        """Emits game.ready with the fake game, as Game:load does (Game.lua:121)."""
        payload = self.table()
        payload[b"game"] = self.engine.game if self.engine is not None else self.table()
        self.emit("game.ready", payload)

    def mods_loaded(self, loader=None):
        """Emits mods.loaded with the live data, as the loader does after the merge
        (Loader.lua:2163), before game.ready. ``loader`` (a Lua table, see fake_loader) stands
        for the loader itself, which the payload also carries."""
        payload = self.table()
        payload[b"data"] = self.engine.data if self.engine is not None else self.table()
        if loader is not None:
            payload[b"loader"] = loader
        self.emit("mods.loaded", payload)

    def fake_loader(self, mods=None, files=None, disabled=()):
        """A stand-in for the engine's mod loader as gen1-bridge reads it (src/mods/Loader.lua):
        ``mods`` maps a mod id to {"version", "path"} (its manifest version and folder), ``files``
        maps a path to its text, read through ``fs.read`` and ``fs.getInfo`` like love.filesystem,
        and ``disabled`` lists ids the player switched off."""
        make = self.eval("""function(mods, files, disabled)
          local out = { mods = {}, disabled = {} }
          for id, m in pairs(mods) do out.mods[id] = { manifest = { version = m.version }, path = m.path } end
          for _, id in ipairs(disabled) do out.disabled[id] = true end
          out.fs = {
            read = function(path) return files[path] end,
            getInfo = function(path)
              local text = files[path]
              if text == nil then return nil end
              return { type = "file", size = #text }
            end,
          }
          return out
        end""")
        return make(self.to_lua(mods or {}), self.to_lua(files or {}), self.to_lua(list(disabled)))

    def step(self, n: int = 1):
        """Runs n logic steps: the clock advances and the input.step hooks run, then (with the
        fake world simulating, fake_world.lua) Input:step and the top state's update (MG7: on the
        Gen 3 fake, Input:step and Player.update's part, fake_engine3.lua)."""
        vanilla = self.eval("function() end")
        game = self.engine.game if self.engine is not None else self.table()
        for _ in range(n):
            self.now = self.now + self.STEP
            if self.engine is not None and self.family == "gen1":
                # Game.logicStep counts fixed steps before input.step runs (Game.lua:313-323)
                gm = self.engine.GameModule
                gm[b"logicStep"] = (gm[b"logicStep"] or 0) + 1
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

    def restart(self):
        """M10: a new process on the same machine (a crash, or a quit without saving, then a
        relaunch). The fake engine keeps its save files, slot registry and mod.cache
        (E.restartProcess, which also closes the old instance's sockets as the OS would and
        leaves the title up with a boot skeleton); main.lua runs again in a fresh sandbox env with
        a fresh mod object: mods.loaded, save.created for the skeleton, then game.ready, in
        Game:load's order (src/core/Game.lua:43-121)."""
        save = self.engine.restartProcess()
        self.env = self.Sandbox.env(self._env_opts)
        self.mod = self.FakeMod.new(self._mod_opts)
        self.engine.bindMod(self.FakeMod, self.mod)
        self.run_main()
        self.mods_loaded()
        payload = self.table()
        payload[b"save"] = save
        self.emit("save.created", payload)
        self.ready()

    @property
    def sleeps(self) -> list[float]:
        """M10: every love.timer.sleep the bridge made, in seconds."""
        s = self._clock[b"sleeps"]
        return [s[i] for i in range(1, len(s) + 1)]

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
        if rel in self.files:
            return self.files[rel]
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

    def importer(self):
        """A Lua `import(rel)` over gen1-bridge's files, as main.lua's: what kb_engine's loader takes
        to load the adapter (MG2). Files given with ``files=`` come first, like mod:read."""
        if getattr(self, "_importer", None) is None:
            def read(rel):
                got = self._read(rel)
                return got if isinstance(got, bytes) else None
            self._importer = self.eval("""function(read, imp, env) return function(rel)
              local src = read(rel)
              if type(src) ~= "string" then error(("cannot read %s"):format(tostring(rel)), 0) end
              return imp(env, src, "@" .. rel)
            end end""")(read, self.Sandbox[b"import"], self.env)
        return self._importer

    def engine_opts(self, **extra):
        """The opts main.lua hands kb_engine.new: require, datasets, ui and the importer, plus extra."""
        o = self.table()
        o[b"require"] = self.env.require
        o[b"import"] = self.importer()
        if self.engine is not None:
            o[b"datasets"] = self.engine.datasets
            o[b"ui"] = self.engine.ui
        for k, v in extra.items():
            o[_b(k)] = v
        return o

    def run_main(self):
        """Runs main.lua as the loader would (Loader.lua:1793-1803)."""
        source = (BRIDGE / "main.lua").read_bytes()
        self.Sandbox.runEntry(self.env, source, b"main.lua", self.mod)

    def emit(self, name: str, payload=None):
        self.FakeMod.emit(self.mod, _b(name), payload)

    def cache_files(self) -> dict[str, bytes]:
        """mod.cache's files (M9), as a plain dict another Bridge can start from."""
        files = self.mod._cache
        return {k.decode("utf-8"): v for k, v in files.items()}

    def log_lines(self) -> list[str]:
        out = []
        log = self.mod._log
        for i in range(1, len(log) + 1):
            out.append(log[i].decode("utf-8"))
        return out

    def env_keys(self) -> set[bytes]:
        return set(self.rt.eval(b"function(t) local k = {} for n in next, t do k[#k + 1] = n end return k end")(
            self.env).values())
