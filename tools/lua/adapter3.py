"""The Gen 3 adapter (gen1-bridge/lib/engine/game3.lua and its parts, MG7) as kb_engine loads it on the
fake game3 engine (fake_engine3.lua), without main.lua: what the test_lua_game3_*.py tests drive.
Everything is made up (EM_TEST_* maps, flags, items, sprites, behaviours).

    a = Adapter3()                      # the Game3 object at the title; setGame done
    a.field()                           # EM_TEST_TOWN, the player at (5, 6) facing down
    a.facts()                           # observe() as a dict of str -> plain values
    a.eng.setFacing(b"up")              # any adapter function (strings cross as bytes)
    a.lines()                           # what the adapter logged: [(level, code)]
"""

from __future__ import annotations

from kblua import Bridge, luajit


def plain(value):
    """A Lua value as plain Python: tables become lists (1..n keys) or dicts, bytes become str."""
    if luajit.lua_type(value) == "table":
        keys = list(value.keys())
        if keys and all(isinstance(k, int) for k in keys) and sorted(keys) == list(range(1, len(keys) + 1)):
            return [plain(value[i]) for i in range(1, len(keys) + 1)]
        return {(k.decode() if isinstance(k, bytes) else k): plain(v) for k, v in value.items()}
    if isinstance(value, bytes):
        return value.decode("utf-8")
    return value


class Adapter3:
    """A Bridge on the Gen 3 fake and the adapter kb_engine picks for it, with a recording log
    (``log.lines``: { level, code, fields }) in the adapter's options."""

    def __init__(self, **kw):
        self.b = Bridge(family="gen3", **kw)
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
        Game3 object carry over), with the game set."""
        eng = self.Engine.new(self.b.engine_opts(log=self.log, love=self.b.env.love))
        eng.setGame(self.E.game)
        return eng

    def field(self, map_id="EM_TEST_TOWN", x=5, y=6, facing="down", **opts):
        """The field up (E.enterField): CONTINUE's or NEW GAME's result. Returns the session."""
        return self.E.enterField(map_id.encode(), x, y, facing.encode(), self.b.to_lua(opts))

    def facts(self):
        return plain(self.eng.observe())

    def lua(self, value):
        return self.b.to_lua(value)

    def same(self, x, y) -> bool:
        """Whether two Lua values are the same object (lupa's wrappers don't compare by identity)."""
        if getattr(self, "_rawequal", None) is None:
            self._rawequal = self.b.eval("function(x, y) return rawequal(x, y) end")
        return bool(self._rawequal(x, y))

    def lines(self):
        out = self.log.lines
        return [(out[i].level.decode(), out[i].code.decode()) for i in range(1, len(out) + 1)]
