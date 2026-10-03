-- kb_engine: the only gen1-bridge file that touches Gen1Recomp engine
-- internals (the manifest's `engine_internals` permission).
--
-- Every function names the engine source it relies on, as `file:line` in the
-- pinned v0.3.49 (identical to refs/gen1recomp at cb02aeb). When the pin
-- changes, re-check each reference below before anything else (CLAUDE.md).
-- Elsewhere, gen1-bridge reaches the engine only through this table and the
-- supported mod API (mod.events, mod.hooks, mod.input, mod.world).
--
-- M0 is a skeleton: version and game access. Later milestones add their
-- readers and writers here (player and NPC state, UI states, flags, export).

local Engine = {}

-- The engine release whose internals this file was checked against.
Engine.CHECKED = "0.3.49"

-- opts.require: the mod's (sandboxed) require; tests pass a fake
-- opts.log:     kb_log logger (optional)
function Engine.new(opts)
  opts = opts or {}
  local requireFn = opts.require or function() return nil end
  local log = opts.log
  local engine = {}
  local game

  local function try(name)
    local ok, result = pcall(requireFn, name)
    if ok and type(result) == "table" then return result end
    return nil
  end

  -- The Game object from the game.ready payload { game = self }
  -- (src/core/Game.lua:119, emitted once services are up, before the title).
  function engine.setGame(g) game = g end
  function engine.game() return game end

  -- Engine release and mod API: src/core/Version.lua:6-23 (`engine`, stamped
  -- "0.3.49" in the release build; "0.0.0-dev[+commit]" in a source checkout,
  -- :70-88) and `modApi` (:19).
  function engine.version()
    local Version = try("src.core.Version")
    if not Version then return nil end
    return tostring(Version.engine), Version.modApi
  end

  -- True when the running engine is the one these internals were checked
  -- against, or a source checkout (which reports 0.0.0-dev). Logs once when
  -- it differs, since every reference in this file may then be stale.
  function engine.checkPin()
    local v = engine.version()
    if v == nil then return false end
    local ok = v == Engine.CHECKED or v:find("^0%.0%.0%-dev") ~= nil
    if not ok and log then
      log.once("engine.pin", "warn", "engine.unchecked_version",
        { engine = v, checked = Engine.CHECKED })
    end
    return ok
  end

  -- The top state of the screen stack: src/core/StateStack.lua:47
  -- (game.stack is StateStack, src/core/Game.lua:93).
  function engine.top()
    local stack = game and game.stack
    return stack and stack:top() or nil
  end

  return engine
end

return Engine
