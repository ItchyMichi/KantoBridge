-- gen2: the engine adapter for Gen1Recomp's Gen 2 engine, one adapter for
-- both of its ids: `gs` (Gold, Silver) and `crystal` (docs/specs/
-- GEN2_BRIDGE_PLAN.md; MULTI_GAME_PLAN.md §8; decision J1). Crystal is a few
-- dozen branches of the same engine, not a fork (src/core/GameVersion.lua:59-111,
-- src/world/gen2/World.lua:1830-1843). Crystal first: the catalogue
-- (protocol/games.json) switches the adapter on per game, so Gold and Silver
-- stay off until one of them is imported and checked (J2).
--
-- It grows from the loader's stub exactly as game3.lua does (kb_engine.lua,
-- Engine.stub; GEN3_BRIDGE_PLAN.md §2): every function the core calls answers
-- "no game" with the right type until a part takes it over. The groups live in
-- parts, lib/engine/gen2/<part>.lua, and the engine-neutral ones shared with
-- game3 in lib/engine/common/; all are loaded with the mod's own `import`
-- (never `require`), and each part's install(base) defines its group's
-- functions with base.def.
--
-- Like gen1.lua and game3.lua this is engine-internals code (the
-- `engine_internals` permission): every engine module and member it relies on
-- is in the boot part's REQUIRES (lib/engine/gen2/boot.lua), checked at
-- game.ready (checkRequired -> engine.missing), and every cited engine line
-- is audited by tools/pin_check.py. Paths are refs/gen1recomp's (Gen1Recomp
-- v0.3.85, the pin).

local Gen2 = {}

-- The parts, in install order, as paths under lib/engine/. G2A: identity,
-- observing, movement and warps, save slots, saves and sessions, the queue of
-- announced saves, the region acts, reliability; G2B: the UI stack
-- (GEN2_BRIDGE_PLAN.md §11).
Gen2.PARTS = { "gen2/boot", "gen2/observe", "gen2/move", "common/slots", "gen2/saves", "common/savequeue",
               "gen2/region", "common/reliability", "gen2/ui" }

function Gen2.new(opts)
  assert(type(opts) == "table" and opts.checked, "gen2: the loader's pin (opts.checked) is required")
  assert(type(opts.stub) == "function", "gen2: the loader's stub (opts.stub) is required")
  assert(type(opts.import) == "function", "gen2: the mod's import is required")
  local adapter = opts.stub()
  local stubbed = {}
  for name, v in pairs(adapter) do
    if type(v) == "function" then stubbed[name] = true end
  end
  rawset(adapter, "_stubbed", stubbed)
  local Base = opts.import("lib/engine/gen2/base.lua")
  local base = Base.new(opts, adapter)
  for _, part in ipairs(Gen2.PARTS) do
    local module = opts.import("lib/engine/" .. part .. ".lua")
    module.install(base)
  end
  return adapter
end

return Gen2
