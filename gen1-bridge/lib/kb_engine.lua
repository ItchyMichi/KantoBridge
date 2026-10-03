-- kb_engine: the only gen1-bridge file that touches Gen1Recomp engine
-- internals (the manifest's `engine_internals` permission).
--
-- Every function names the engine source it relies on, as `file:line` in the
-- pinned v0.3.49 (identical to refs/gen1recomp at cb02aeb). When the pin
-- changes, re-check each reference below before anything else (CLAUDE.md).
-- Elsewhere, gen1-bridge reaches the engine only through this table and the
-- supported mod API (mod.events, mod.hooks, mod.input, mod.world).
--
-- Readers hand back plain values (strings, numbers, booleans, fresh tables),
-- never live engine tables, so nothing outside this file can mutate the game
-- by accident. Shorthand: OC = src/world/OverworldController.lua.

local Engine = {}

-- The engine release whose internals this file was checked against.
Engine.CHECKED = "0.3.49"

-- PLAN P21: the hidden game's display options pinned to classic, so mirror
-- frames have the fixed 160x144 geometry, and text at FAST. Keys and
-- defaults: SaveData.defaultOptions (src/core/SaveData.lua:569-654).
Engine.CLASSIC = {
  tilt = 0,                -- src/render/Tilt.lua:80-91
  zoom = 0,                -- src/render/Zoom.lua:113-115 (0 = FIT)
  battleLayout = "og",     -- src/battle/BattleState.lua:79-81 (not "wide")
  battleFit = "fixed",     -- src/battle/BattleState.lua:94-96 (not "fill")
  uiLayout = "centered",   -- src/core/Game.lua:573-580 (not "dynamic")
  textSpeed = 1,           -- src/render/TextBox.lua:652-654: 1 frame per glyph = FAST
}

local SYMBOL = "^[A-Za-z0-9_]+$"

local function symbol(v)
  return type(v) == "string" and #v > 0 and #v <= 64 and v:find(SYMBOL) ~= nil
end

-- opts.require: the mod's (sandboxed) require; tests pass a fake
-- opts.log:     kb_log logger (optional)
-- opts.ui:      mod.ui, the UI module tables (src/ui/ModUI.lua:8-27)
function Engine.new(opts)
  opts = opts or {}
  local requireFn = opts.require or function() return nil end
  local log = opts.log
  local ui = opts.ui
  local engine = {}
  local game

  local function try(name)
    local ok, result = pcall(requireFn, name)
    if ok and type(result) == "table" then return result end
    return nil
  end

  -- The Game object from the game.ready payload { game = self }
  -- (src/core/Game.lua:119, emitted once services are up, before the title).
  -- input.step also passes it (src/core/Game.lua:320).
  function engine.setGame(g) if g ~= nil then game = g end end
  function engine.game() return game end

  -- The process-lifetime slot (PLAN section 4.1, "Hot-reload safety").
  -- Nothing unloads a mod: a hot reload (src/dev/HotReload.lua:27-64) or a
  -- return to the launcher drops the old loader, but the old instance's raw
  -- sockets stay open until a garbage collection finds them unreachable,
  -- which can take a long time. The Game object outlives both, so each
  -- instance leaves a function on it that closes its sockets; the next
  -- instance calls it before binding. Returns true when it closed a
  -- previous instance.
  local SLOT = "kantoBridgeCloseInstance"
  function engine.takeOver(closeMine)
    if type(game) ~= "table" then return false end
    local previous = rawget(game, SLOT)
    rawset(game, SLOT, closeMine)
    if type(previous) == "function" and previous ~= closeMine then
      local ok = pcall(previous)
      if not ok and log then log.warn("engine.takeover_failed", {}) end
      return true
    end
    return false
  end

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

  -- "red" or "blue": GameVersion.get (src/core/GameVersion.lua:178-180).
  function engine.gameVersion()
    local GameVersion = try("src.core.GameVersion")
    local v = GameVersion and GameVersion.get and GameVersion.get()
    return type(v) == "string" and v or nil
  end

  -- Game.logicStep counts fixed logic steps (src/core/Game.lua:311-315).
  function engine.logicStep()
    local Game = try("src.core.Game")
    return Game and tonumber(Game.logicStep) or 0
  end

  -- The live save table (src/core/Game.lua:95, :1496 on load). Callers read
  -- it; only pinDisplay writes to it.
  function engine.save()
    return game and type(game.save) == "table" and game.save or nil
  end

  -- The top state of the screen stack: src/core/StateStack.lua:47
  -- (game.stack is StateStack, src/core/Game.lua:93).
  function engine.top()
    local stack = game and game.stack
    return stack and stack.top and stack:top() or nil
  end

  -- The overworld when it is on the stack (it stays under menus and
  -- battles). StateStack.states is the stack, bottom to top
  -- (src/core/StateStack.lua:9-11); the overworld module marks itself with
  -- isOverworld (OC:35). Same authority WorldAPI:overworld uses
  -- (src/world/WorldAPI.lua:96-109), minus its fallback to a stale module
  -- table after a return to the title.
  function engine.overworld()
    local stack = game and game.stack
    local states = stack and stack.states
    if type(states) ~= "table" then return nil end
    for i = #states, 1, -1 do
      local state = states[i]
      if type(state) == "table" and state.isOverworld then
        if state.map and state.player then return state end
        return nil
      end
    end
    return nil
  end

  local UI_CLASSES = { "TextBox", "Menu", "ListMenu", "ChoiceBox", "QuantityBox", "NamingScreen", "PicBox" }

  -- Classifies a state: "overworld", "screen" (it takes input: text, menus,
  -- battles, full screens) or "busy" (a fade, a flash, a slide or a panel
  -- with no input), plus a symbol naming it.
  --   isOverworld OC:35; screenId stamped by Screens.build
  --   (src/ui/Screens.lua:174); isTextBox (src/render/TextBox.lua:22);
  --   isBattleState (src/battle/BattleState.lua:49); the mod.ui module
  --   tables are the instances' metatables (src/ui/ModUI.lua:8-27); isMenu
  --   (e.g. src/ui/BagMenu.lua:11). Anything else is transient
  --   (src/render/Transition.lua and friends).
  function engine.classifyState(state)
    if state == nil then return "none", "None" end
    if type(state) ~= "table" then return "busy", "Busy" end
    if state.isOverworld then return "overworld", "Overworld" end
    if symbol(state.screenId) then return "screen", state.screenId end
    if state.isTextBox then return "screen", "TextBox" end
    if state.isBattleState then return "screen", "BattleState" end
    local mt = getmetatable(state)
    if ui and mt ~= nil then
      for i = 1, #UI_CLASSES do
        local name = UI_CLASSES[i]
        if ui[name] ~= nil and mt == ui[name] then return "screen", name end
      end
    end
    if state.isMenu then return "screen", "Menu" end
    return "busy", "Busy"
  end

  -- Rock Tunnel and friends: field.darkMaps (OC:216-222). Built once per data
  -- table, since observe() asks every step.
  local darkFor, darkSet = nil, {}
  local function darkMapSet()
    local field = game and game.data and game.data.field
    local def = field and field.darkMaps
    if def ~= darkFor then
      darkFor, darkSet = def, {}
      local list = def and def.maps
      for _, id in ipairs(type(list) == "table" and list or {}) do darkSet[id] = true end
    end
    return darkSet
  end

  function engine.darkMaps()
    local out = {}
    for id in pairs(darkMapSet()) do
      if symbol(id) then out[#out + 1] = id end
    end
    table.sort(out)
    return out
  end

  -- Badge inventory keys in gym order: Badges.list / itemFor
  -- (src/inventory/Badges.lua:16-26).
  function engine.badgeItems()
    local Badges = try("src.inventory.Badges")
    local out = {}
    if Badges and Badges.list then
      for _, entry in ipairs(Badges.list(game and game.data) or {}) do
        local item = Badges.itemFor and Badges.itemFor(entry) or entry.item or entry.id
        if symbol(item) then out[#out + 1] = item end
      end
    end
    return out
  end

  local function playerScripted(ow, p)
    -- OC:5655-5660: { entity, dir, remaining, onDone, collide }
    for _, mv in ipairs(type(ow.scriptMoves) == "table" and ow.scriptMoves or {}) do
      if mv.entity == p then return true end
    end
    return false
  end

  -- Everything the player observer needs, as plain values. The input.step
  -- hook runs before the stack updates (src/core/Game.lua:320 vs :356), so
  -- this is the world as the previous step left it.
  function engine.observe()
    local facts = { inGame = false }
    facts.top, facts.topName = engine.classifyState(engine.top())
    local save = engine.save()
    local ow = engine.overworld()
    if not ow then return facts end
    local p = ow.player
    facts.inGame = true
    facts.map = ow.map.id                       -- WorldAPI.lua:116-118 reads the same
    facts.x, facts.y = p.cellX, p.cellY         -- Player.lua:247 (snapped on landing)
    facts.facing = p.facing                     -- "up"/"down"/"left"/"right" (OC:67)
    facts.moving = p.moving == true             -- Player.lua:150, :178-184
    if facts.moving then
      facts.tx, facts.ty = p.targetX, p.targetY -- set at step start (Player.lua:179, OC:5711)
      facts.frames = p.stepFramesCur            -- Player.lua:183, OC:5714
    end
    facts.playerScripted = playerScripted(ow, p)
    facts.ledge = p.ledgeHop and true or false  -- OC:1985-2037, Player.lua:124
    facts.spinning = (p.spinning or ow.spinnerSliding) and true or false -- OC:4825-4871
    -- departures and arrivals that move or hide the player (OC:1366-1498,
    -- :1546-1552) and the locks set with them (Player.lua:97, :150; OC:2456,
    -- :2486, :5329, :5354, :5522, :5528)
    facts.forcedPhase = (ow.teleportOut or ow.flyFade or ow.flyAnim or ow.flyArrive
      or ow.holeFall or ow.holeArrive or ow.spinArrive or p.inputLocked) and true or false
    facts.hopLand = tonumber(ow.hopLand) or 0     -- OC:1548, :1572
    facts.transitioning = ow.transitioning and true or false -- OC:5423, :5550-5552
    facts.emote = ow.emote ~= nil and ow.emote ~= false      -- OC:1510-1527
    facts.anim = (ow.cutAnim or (ow.dustAnim and ow.dustAnim.boulder)) and true or false
    local runner = ow.runner
    local running = runner and runner.isRunning and runner:isRunning() or false
    -- the rest of OC:1546-1552's `scripted`, plus the early-return holds
    -- healAnim and pikaHop (OC:1344-1363)
    facts.scripted = (running or #(ow.scriptMoves or {}) > 0 or ow.engaging
      or ow.healAnim or ow.pikaHop) and true or false
    facts.surf = p.surfing and true or false                         -- Player.lua:139, :331
    facts.bike = (save and save.onBike) and true or false            -- OC:1301-1302
    facts.slope = (facts.bike and p.slopeMap) and true or false      -- Player.lua:128
    facts.hidden = ow.playerHidden and true or false                 -- OC:6491-6494
    facts.darkMap = darkMapSet()[facts.map] == true                  -- OC:510-515
    facts.flashLit = (save and save.flashLit) and true or false      -- OC:510-513, :1063
    facts.npcs = type(ow.npcs) == "table" and #ow.npcs or 0
    return facts
  end

  -- The live NPCs of the current map, as PLAN section 4.1 "NPCs" describes
  -- them: ow.npcs holds the visible ones (NPC.lua:31-67, OC:551-558); sprite
  -- is appearance or def.sprite (NPC.lua:68-79); item balls have def.item
  -- (except "0", OC:3451-3456); boulders are SPRITE_BOULDER or pushable
  -- (Map.lua:188-191); statics have def.pokemon; trainers trainerClass.
  -- Returns the list and the map id, or nil outside the overworld.
  function engine.npcs()
    local ow = engine.overworld()
    if not ow then return nil end
    local out = {}
    for _, npc in ipairs(type(ow.npcs) == "table" and ow.npcs or {}) do
      local def = type(npc.def) == "table" and npc.def or {}
      local sprite = npc.appearance or def.sprite
      local kind = "person"
      if def.item ~= nil and def.item ~= "0" then
        kind = "item"
      elseif sprite == "SPRITE_BOULDER" or def.pushable then
        kind = "boulder"
      elseif def.pokemon ~= nil then
        kind = "static"
      elseif def.trainerClass ~= nil then
        kind = "trainer"
      end
      out[#out + 1] = {
        id = npc.id, sprite = sprite, kind = kind,
        x = npc.cellX, y = npc.cellY, facing = npc.facing, moving = npc.moving == true,
      }
    end
    return out, ow.map.id
  end

  -- True when options already hold the classic pin (debug.status reports it).
  function engine.displayPinned(options)
    if type(options) ~= "table" then return false end
    for key, value in pairs(Engine.CLASSIC) do
      if options[key] ~= value then return false end
    end
    for _, level in pairs(type(options.pipelines) == "table" and options.pipelines or {}) do
      if level ~= 0 then return false end
    end
    return true
  end

  -- Pins options (a save.options table) to Engine.CLASSIC and turns every
  -- render pipeline (Dramaless voxel and friends) to level 0, keeping the
  -- keys. Returns how many values changed. With reapply, the modules that
  -- cache these values are told at once: Tilt.applyOptions
  -- (src/render/Tilt.lua:80-91), Pipelines.applyOptions
  -- (src/render/Pipelines.lua:230-252) and Zoom.applyOptions
  -- (src/render/Zoom.lua:113-115), in Game:applyOptions' order
  -- (src/core/Game.lua:1431-1436). battleLayout, battleFit, uiLayout and
  -- textSpeed are read live. Nothing is written to disk here; the engine
  -- persists options only when its own OPTIONS screen saves them
  -- (Game:writeOptions, src/core/Game.lua:1418-1421).
  function engine.pinDisplay(options, reapply)
    if type(options) ~= "table" then return 0 end
    local changed = 0
    for key, value in pairs(Engine.CLASSIC) do
      if options[key] ~= value then
        options[key] = value
        changed = changed + 1
      end
    end
    if type(options.pipelines) == "table" then
      for id, level in pairs(options.pipelines) do
        if level ~= 0 then
          options.pipelines[id] = 0
          changed = changed + 1
        end
      end
    end
    if changed > 0 and reapply then
      for _, name in ipairs({ "src.render.Tilt", "src.render.Pipelines", "src.render.Zoom" }) do
        local cached = try(name)
        if cached and cached.applyOptions then
          local ok = pcall(cached.applyOptions, options)
          if not ok and log then log.once("pin:" .. name, "warn", "engine.pin_apply_failed", { module = name }) end
        end
      end
    end
    return changed
  end

  return engine
end

return Engine
