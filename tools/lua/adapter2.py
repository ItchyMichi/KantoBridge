"""The Gen 2 adapter (gen1-bridge/lib/engine/gen2.lua and its parts, MG9) as kb_engine loads it on the
fake Gen 2 engine (fake_engine2.lua), without main.lua: what the test_lua_gen2_*.py tests drive.
Everything is made up (TEST_* maps, collision bytes, flags by number, items, sprites).

    a = Adapter2()                      # the Game2 object at the title; setGame done
    a.field()                           # TEST_TOWN, the player at (5, 6) facing down
    a.facts()                           # observe() as a dict of str -> plain values
    a.eng.setFacing(b"up")              # any adapter function (strings cross as bytes)
    a.lines()                           # what the adapter logged: [(level, code)]
"""

from __future__ import annotations

from adapter3 import plain  # noqa: F401  (re-exported for the gen2 tests)
from kblua import Bridge


class Adapter2:
    """A Bridge on the Gen 2 fake and the adapter kb_engine picks for it, with a recording log
    (``log.lines``: { level, code, fields }) in the adapter's options; the mod's hooks are handed
    over, as main.lua does, so the adapter's own engine hooks (intro.oak_speech.build) reach the fake."""

    def __init__(self, **kw):
        self.b = Bridge(family="gen2", **kw)
        self.E = self.b.engine
        self.Engine = self.b.module("lib/kb_engine.lua")
        self.log = self.b.eval("""(function()
          local lines = {}
          local function rec(level) return function(code, fields)
            lines[#lines + 1] = { level = level, code = code, fields = fields }
          end end
          return { lines = lines, info = rec("info"), warn = rec("warn"), error = rec("error"), debug = rec("debug"),
                   once = function(_, level, code, fields) rec(level)(code, fields) end }
        end)()""")
        self.eng = self.new_instance()

    def new_instance(self):
        """Another adapter instance on the same fake (a hot reload: the engine's modules and the
        Game2 object carry over), with the game set."""
        eng = self.Engine.new(self.b.engine_opts(log=self.log, love=self.b.env.love, world=self.E.worldApi,
                                                 hooks=self.b.mod.hooks))
        eng.setGame(self.E.game)
        return eng

    def field(self, map_id="TEST_TOWN", x=5, y=6, facing="down", via="boot"):
        """The field up (E.enterWorld): CONTINUE's or NEW GAME's result. Returns the World."""
        return self.E.enterWorld(map_id.encode(), x, y, facing.encode(), via.encode())

    def facts(self):
        return plain(self.eng.observe())

    def lua(self, value):
        return self.b.to_lua(value)

    def same(self, x, y) -> bool:
        """Whether two Lua values are the same object (lupa's wrappers don't compare by identity)."""
        if getattr(self, "_rawequal", None) is None:
            self._rawequal = self.b.eval("function(x, y) return rawequal(x, y) end")
        return bool(self._rawequal(x, y))

    def press(self, btn, source="test"):
        inp = self.E.game.input
        inp.sourcePress(inp, btn.encode(), source.encode())

    def release(self, btn, source="test"):
        inp = self.E.game.input
        inp.sourceRelease(inp, btn.encode(), source.encode())

    def tick(self, n=1):
        """The engine's own steps (no main.lua here): E.tick, the setGame count."""
        for _ in range(n):
            self.eng.setGame(self.E.game)
            self.E.tick()

    def lines(self):
        out = self.log.lines
        return [(out[i].level.decode(), out[i].code.decode()) for i in range(1, len(out) + 1)]
