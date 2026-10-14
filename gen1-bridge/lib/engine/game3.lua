-- game3: the engine adapter for Gen1Recomp's Gen 3 engine, `game3`
-- (docs/specs/GEN3_BRIDGE_PLAN.md; MULTI_GAME_PLAN.md MG7). Emerald first:
-- the catalogue (protocol/games.json) switches the adapter on per game, so
-- FireRed, LeafGreen, Ruby and Sapphire stay off until they are checked.
--
-- It grows from the loader's stub (kb_engine.lua, Engine.stub): every
-- function the core calls answers "no game" with the right type until a part
-- takes it over, so a group not written yet can never throw where the core
-- calls it unguarded (GEN3_BRIDGE_PLAN.md §2). The groups live in parts,
-- lib/engine/game3/<part>.lua, loaded with the mod's own `import` (never
-- `require`); each part's install(base) defines its group's functions with
-- base.def, which also strikes the name from the adapter's `_stubbed` set
-- (Engine.missingGroups reads it).
--
-- Like gen1.lua this is engine-internals code (the `engine_internals`
-- permission): every engine module and member it relies on is in the boot
-- part's REQUIRES (lib/engine/game3/boot.lua), checked at game.ready
-- (checkRequired -> engine.missing),
-- and every cited engine line is audited by tools/pin_check.py.

local Game3 = {}

-- The parts, in install order (later parts may use what earlier ones put on
-- the base). G3A: identity, observing, movement, saves and sessions, the
-- region acts, reliability; G3B: the UI; G3C: the story (toggles, cells);
-- G3D: the export (the g3* resources); G3F: the party (before the battles,
-- whose attachBridge installs its gift seam), battles, the bag (the items
-- group; "items" is a name Gen1Recomp keeps for its own modules), routes.
Game3.PARTS = { "boot", "observe", "move", "saves", "region", "reliability", "ui", "story", "export", "party", "battle",
                "bag", "routes" }

function Game3.new(opts)
  assert(type(opts) == "table" and opts.checked, "game3: the loader's pin (opts.checked) is required")
  assert(type(opts.stub) == "function", "game3: the loader's stub (opts.stub) is required")
  assert(type(opts.import) == "function", "game3: the mod's import is required")
  local adapter = opts.stub()
  local stubbed = {}
  for name, v in pairs(adapter) do
    if type(v) == "function" then stubbed[name] = true end
  end
  rawset(adapter, "_stubbed", stubbed)
  local Base = opts.import("lib/engine/game3/base.lua")
  local base = Base.new(opts, adapter)
  for _, part in ipairs(Game3.PARTS) do
    local module = opts.import("lib/engine/game3/" .. part .. ".lua")
    module.install(base)
  end
  return adapter
end

return Game3
