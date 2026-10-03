-- kb_engine: the only gen1-bridge file that touches Gen1Recomp engine
-- internals (the manifest's `engine_internals` permission).
--
-- Every function names the engine source it relies on, as `file:line` in the
-- pinned v0.3.49 (identical to refs/gen1recomp at cb02aeb). When the pin
-- changes, re-check each reference below before anything else (CLAUDE.md).
-- Elsewhere, gen1-bridge reaches the engine only through this table and the
-- supported mod API (mod.events, mod.hooks, mod.input, mod.world; the
-- export's dataset views come through here too).
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

-- ------------------------------------------------------------ export constants
--
-- Engine facts the world export needs that the engine keeps as inline
-- literals or locals, so there is nothing to call. Copied here, cited, and
-- re-checked with everything else on a pin change.

-- Cuttable tree tiles per tileset: OVERWORLD $3d and GYM $50
-- (OverworldState:tryCut, src/world/OverworldController.lua:3304-3307, and
-- the same gate in useCutFieldMove :3424-3427). OVERWORLD $52 is cuttable
-- tall grass, not a tree, so it is left out.
Engine.CUT_TREE_TILES = { OVERWORLD = { 0x3d }, GYM = { 0x50 } }

-- Shore tiles when a tileset record carries no shoreTiles: $32 and $48,
-- except on SHIP_PORT (src/world/Map.lua:20-22, :128-131). Map.new folds
-- water and shore into one set, so the shore half is the one thing that
-- can't be asked of the engine.
Engine.SHORE_FALLBACK = { 0x32, 0x48 }
Engine.NO_SHORE_TILESETS = { SHIP_PORT = true }

-- Where each registry the export reads lives in the live Data table: the
-- registry targets (src/mods/Schemas.lua: pokemon :1726, moves :1856,
-- items :1893, maps :1922, tilesets :1980, encounters :2046, sprites :2253,
-- palettes :2825, constants :3101, field :3130).
Engine.EXPORT_TARGETS = {
  maps = { "maps" }, tilesets = { "tilesets" }, sprites = { "sprites" },
  palettes = { "palettes", "palettes" }, encounters = { "encounters" },
  pokemon = { "pokemon" }, moves = { "moves" }, items = { "items" },
  field = { "field" }, constants = { "constants" },
}

-- A deep copy of plain data: functions, userdata, cycles and metatables are
-- dropped, like DatasetViews' copyData refuses them
-- (src/mods/DatasetViews.lua:117-142). Returns the copy and how many values
-- were dropped.
local function copyPlain(value, seen, dropped)
  local t = type(value)
  if t == "string" or t == "number" or t == "boolean" then return value, dropped end
  if t ~= "table" or seen[value] then return nil, dropped + 1 end
  seen[value] = true
  local out = {}
  for k, v in pairs(value) do
    local kt = type(k)
    if kt == "string" or kt == "number" then
      local copy
      copy, dropped = copyPlain(v, seen, dropped)
      if copy ~= nil then out[k] = copy end
    else
      dropped = dropped + 1
    end
  end
  seen[value] = nil
  return out, dropped
end

-- opts.require:  the mod's (sandboxed) require; tests pass a fake
-- opts.log:      kb_log logger (optional)
-- opts.ui:       mod.ui, the UI module tables (src/ui/ModUI.lua:8-27)
-- opts.datasets: mod.datasets, the dataset views (src/mods/Loader.lua:1361-1369)
function Engine.new(opts)
  opts = opts or {}
  local requireFn = opts.require or function() return nil end
  local log = opts.log
  local ui = opts.ui
  local datasets = opts.datasets
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

  -- ------------------------------------------------------------ export (M2)
  --
  -- The world export reads the active game's PRISTINE data. The live Data
  -- tables change during play: Map:setBlock writes into the map record's
  -- blocks (src/world/Map.lua:244-249) for Cut, card-key doors and script
  -- swaps, and elevator panels rewrite warps. So the export reads one of two
  -- sources, both of which hand out detached plain tables:
  --   * "datasets": mod.datasets:open(version) (src/mods/Loader.lua:1361-1369,
  --     src/mods/DatasetViews.lua:427-479) decodes the version's cache again
  --     and copies every record it hands out (get/each, :368-405);
  --   * "live": PLAN section 8's fallback, a deep copy of the live Data taken
  --     before any map has loaded (the mods.loaded payload's `data`,
  --     src/mods/Loader.lua:2163; Game:load runs the loader before anything
  --     is on the stack, src/core/Game.lua:47-119).
  --
  -- A source has: path ("datasets" or "live"); records(name, checkpoint),
  -- the sorted { {id, value}, ... } of a record registry (nil plus a reason
  -- when it can't be read); value(name, key), one key of a deep registry
  -- (field, constants). checkpoint() may yield the caller's coroutine; it is
  -- never called inside a pcall.

  -- The dataset view of a game version, or nil plus the reason open gave
  -- ("unknown_version", "not_imported", "invalid_cache", ...).
  function engine.openDataset(version)
    if not (datasets and datasets.open) then return nil, "no_datasets_api" end
    local ok, view, reason = pcall(datasets.open, datasets, version)
    if not ok then return nil, "open_failed" end
    if type(view) ~= "table" or type(view.content) ~= "table" then
      return nil, type(reason) == "string" and reason or "no_view"
    end
    return view
  end

  function engine.datasetSource(view)
    local src = { path = "datasets" }
    function src.records(name, checkpoint)
      local reg = view.content[name]
      if type(reg) ~= "table" or type(reg.each) ~= "function" then return nil, "no_registry" end
      local ok, iter = pcall(reg.each, reg)
      if not ok or type(iter) ~= "function" then return nil, "each_failed" end
      local out = {}
      while true do
        local okNext, id, value = pcall(iter)
        if not okNext then return nil, "read_failed" end
        if id == nil then break end
        if type(id) == "string" and value ~= nil then out[#out + 1] = { id = id, value = value } end
        if checkpoint then checkpoint() end
      end
      return out
    end
    function src.value(name, key)
      local reg = view.content[name]
      if type(reg) ~= "table" or type(reg.get) ~= "function" then return nil end
      local ok, value = pcall(reg.get, reg, key)
      return ok and value or nil
    end
    return src
  end

  -- Deep-copies the export's registries out of a live Data table now.
  -- checkpoint (optional) runs between records.
  function engine.liveSource(data, checkpoint)
    if type(data) ~= "table" then return nil, "no_data" end
    local snapshot, dropped = {}, 0
    for name, path in pairs(Engine.EXPORT_TARGETS) do
      local node = data
      for i = 1, #path do node = type(node) == "table" and node[path[i]] or nil end
      local copy = {}
      if type(node) == "table" then
        for key, value in pairs(node) do
          if type(key) == "string" then
            local c, d = copyPlain(value, {}, 0)
            dropped = dropped + d
            if c ~= nil then copy[key] = c end
            if checkpoint then checkpoint() end
          end
        end
      end
      snapshot[name] = copy
    end
    local src = { path = "live", dropped = dropped }
    function src.records(name)
      local reg = snapshot[name]
      if not reg then return nil, "no_registry" end
      local ids = {}
      for id, value in pairs(reg) do
        if type(value) == "table" then ids[#ids + 1] = id end
      end
      table.sort(ids)
      local out = {}
      for i = 1, #ids do out[i] = { id = ids[i], value = reg[ids[i]] } end
      return out
    end
    function src.value(name, key)
      local reg = snapshot[name]
      return reg and reg[key] or nil
    end
    return src
  end

  -- True while an overworld is on the stack: a loaded map is what starts
  -- changing the live Data, so the late fallback refuses to copy it then.
  function engine.worldShowing()
    return engine.overworld() ~= nil
  end

  -- liveSource over the Game's own Data table (Game:load sets self.data =
  -- Data, src/core/Game.lua:44), so the live table never leaves this file.
  function engine.liveSourceFromGame(checkpoint)
    return engine.liveSource(game and type(game.data) == "table" and game.data or nil, checkpoint)
  end

  local function mapModule()
    local Map = try("src.world.Map")
    if Map and type(Map.new) == "function" then return Map end
    return nil
  end

  -- Per-cell questions, answered by the engine's own runtime map built from
  -- the records (Map.new, src/world/Map.lua:112-142). A cell's behaviour
  -- comes from its bottom-left 8x8 tile (cellTile :208-211); the queries are
  -- isWalkableCell :217, isGrassCell :221 (never off-map), isWaterCell :239
  -- (water + shore; the caller checks field.waterTilesets), isDoorTileCell
  -- :253, warpPadOrHoleAt :265, isCounterCell :272, and blockAt :193. (A warp
  -- fires on arrival on a door or warp tile, isWarpTileCell :258; the warp
  -- tiles are the tileset record's warpTiles, which the caller reads.)
  -- Coordinates are cells; the map is width*2 x height*2 of them.
  function engine.mapQuery(def, tileset)
    local Map = mapModule()
    if not Map then return nil, "no_map_module" end
    local ok, m = pcall(Map.new, def, tileset)
    if not ok or type(m) ~= "table" then return nil, "map_new_failed" end
    local q = { w = def.width * 2, h = def.height * 2 }
    function q.tile(cx, cy) return m:cellTile(cx, cy) end
    function q.walkable(cx, cy) return m:isWalkableCell(cx, cy) and true or false end
    function q.grass(cx, cy) return m:isGrassCell(cx, cy) and true or false end
    function q.water(cx, cy) return m:isWaterCell(cx, cy) and true or false end
    function q.door(cx, cy) return m:isDoorTileCell(cx, cy) and true or false end
    function q.counter(cx, cy) return m:isCounterCell(cx, cy) and true or false end
    function q.padOrHole(cx, cy) return m:warpPadOrHoleAt(cx, cy) end
    function q.block(bx, by) return m:blockAt(bx, by) end
    return q
  end

  -- Map.isOutside (src/world/Map.lua:146-158): OVERWORLD maps, plus the
  -- outsideTilesets the overworld passes in (field.outsideTilesets, as at
  -- src/world/OverworldController.lua:5419). This is what sets the LAST_MAP
  -- memory.
  function engine.isOutside(def, outsideTilesets)
    local Map = mapModule()
    if not (Map and Map.isOutside) then return nil end
    local ok, outside = pcall(Map.isOutside, def, outsideTilesets)
    if not ok then return nil end
    return outside and true or false
  end

  -- The tile classes of one tileset that come from engine constants (water,
  -- shore, warp pads and holes; src/world/Map.lua:20-41, :128-131, :265-269),
  -- asked of the engine itself: for every tile id, a 1x1 probe map whose
  -- cells are all that tile. Returns { water = {ids}, pads = {{tile, kind}} }
  -- (water includes shore; the caller applies field.waterTilesets).
  function engine.tileProbe(tilesetId, tileset)
    local Map = mapModule()
    if not Map or type(tileset) ~= "table" then return nil, "no_map_module" end
    local row = {}
    local probeTs = {}
    for k, v in pairs(tileset) do probeTs[k] = v end
    probeTs.blocks = { row }
    local def = { id = "KB_PROBE", tileset = tilesetId, width = 1, height = 1,
                  blocks = { 0 }, borderBlock = 0, warps = {}, signs = {}, objects = {} }
    local water, pads = {}, {}
    for t = 0, 255 do
      for i = 1, 16 do row[i] = t end
      local ok, m = pcall(Map.new, def, probeTs)
      if not ok or type(m) ~= "table" then return nil, "map_new_failed" end
      local okW, isWater = pcall(m.isWaterCell, m, 0, 0)
      if okW and isWater then water[#water + 1] = t end
      local okP, kind = pcall(m.warpPadOrHoleAt, m, 0, 0)
      if okP and (kind == "pad" or kind == "hole") then pads[#pads + 1] = { tile = t, kind = kind } end
    end
    return { water = water, pads = pads }
  end

  -- Shore tiles of a tileset (Engine.SHORE_FALLBACK above).
  function engine.shoreTiles(tilesetId, tileset)
    local shore = type(tileset) == "table" and tileset.shoreTiles or nil
    if shore == nil and not Engine.NO_SHORE_TILESETS[tilesetId] then shore = Engine.SHORE_FALLBACK end
    local out = {}
    for _, t in ipairs(type(shore) == "table" and shore or {}) do out[#out + 1] = t end
    return out
  end

  function engine.cutTreeTiles(tilesetId)
    local out = {}
    for _, t in ipairs(Engine.CUT_TREE_TILES[tilesetId] or {}) do out[#out + 1] = t end
    return out
  end

  -- Spinner-arrow tiles of a tileset, the tiles the spinner toggle patches:
  -- TileRenderer.SPINNER_ARROW_TILES (src/render/TileRenderer.lua:134-137).
  function engine.spinnerTiles(tilesetId)
    local TileRenderer = try("src.render.TileRenderer")
    local list = TileRenderer and type(TileRenderer.SPINNER_ARROW_TILES) == "table"
      and TileRenderer.SPINNER_ARROW_TILES[tilesetId] or nil
    local out = {}
    for _, t in ipairs(type(list) == "table" and list or {}) do
      if type(t) == "number" then out[#out + 1] = t end
    end
    return out
  end

  -- The asset paths a tileset's tile animations draw from: the record's own
  -- animatedTiles, else TileRenderer.defaultAnimatedTiles (the flower frames
  -- and the spinner strip; src/render/TileRenderer.lua:313-334, precedence
  -- :538-539). Each entry carries `images` (frames) or `image` (a strip).
  function engine.animationImages(tileset)
    local TileRenderer = try("src.render.TileRenderer")
    local specs = type(tileset) == "table" and tileset.animatedTiles or nil
    if specs == nil and TileRenderer and TileRenderer.defaultAnimatedTiles then
      local ok, list = pcall(TileRenderer.defaultAnimatedTiles, tileset)
      specs = ok and list or nil
    end
    local out = {}
    for _, spec in ipairs(type(specs) == "table" and specs or {}) do
      if type(spec) == "table" then
        for _, path in ipairs(type(spec.images) == "table" and spec.images or {}) do
          if type(path) == "string" then out[#out + 1] = path end
        end
        if type(spec.image) == "string" then out[#out + 1] = spec.image end
      end
    end
    return out
  end

  -- The engine's own neighbour placement: OverworldState.computeNeighbors
  -- (src/world/OverworldController.lua:296-345; the module returns
  -- OverworldState, :6683), a BFS over the connections that composes the
  -- strip offsets. With an unbounded hop count it places every connected
  -- map. Returns { {id, ox, oy}, ... } in world pixels (32 per block),
  -- without the root.
  function engine.computeNeighbors(maps, rootId)
    local OC = try("src.world.OverworldController")
    if not (OC and type(OC.computeNeighbors) == "function") then return nil, "no_overworld_module" end
    local ok, list = pcall(OC.computeNeighbors, maps, rootId, 1000000)
    if not ok or type(list) ~= "table" then return nil, "neighbors_failed" end
    local out = {}
    for _, entry in ipairs(list) do
      if type(entry) == "table" and type(entry.id) == "string" then
        out[#out + 1] = { id = entry.id, ox = entry.ox, oy = entry.oy }
      end
    end
    return out
  end

  -- The SGB palette cascade of OverworldState:paletteNameFor
  -- (src/world/OverworldController.lua:897-919): a map's own `palette`, then
  -- field.palettes byMap, byTileset and the byPrefix rows (its local
  -- paletteLookup, :854-866, restated here because it is a local); nil when
  -- nothing matches, which is when an interior takes its remembered outdoor
  -- map's palette, and then `default`.
  function engine.paletteLookup(palettes, mapId, tilesetId, ownPalette)
    if type(ownPalette) == "string" then return ownPalette end
    if type(palettes) ~= "table" or type(mapId) ~= "string" then return nil end
    local byMap = palettes.byMap
    if type(byMap) == "table" and byMap[mapId] then return byMap[mapId] end
    local byTileset = palettes.byTileset
    if type(byTileset) == "table" and tilesetId and byTileset[tilesetId] then return byTileset[tilesetId] end
    for _, row in ipairs(type(palettes.byPrefix) == "table" and palettes.byPrefix or {}) do
      if type(row) == "table" and type(row.prefix) == "string"
          and mapId:find(row.prefix, 1, true) == 1 then
        return row.palette
      end
    end
    return nil
  end

  -- PNG bytes of one asset, loaded the way the engine loads it:
  -- Assets.imageData (src/render/Assets.lua:67-71; mod overrides first, then
  -- the active version's mounted cache), encoded like the importer writes
  -- PNGs, ImageData:encode("png") with no filename
  -- (src/import/ImageWriter.lua:170-182). Returns png, width, height, or nil
  -- plus a reason.
  function engine.imagePng(path)
    local Assets = try("src.render.Assets")
    if not (Assets and type(Assets.imageData) == "function") then return nil, "no_assets_module" end
    local ok, data = pcall(Assets.imageData, path)
    if not ok or data == nil then return nil, "load_failed" end
    local okDims, w, h = pcall(function() return data:getWidth(), data:getHeight() end)
    local okEnc, file = pcall(data.encode, data, "png")
    local png
    if okEnc and file ~= nil then
      local okStr, bytes = pcall(file.getString, file)
      if okStr and type(bytes) == "string" then png = bytes end
      if file.release then pcall(file.release, file) end
    end
    if data.release then pcall(data.release, data) end
    if not okDims or type(w) ~= "number" or type(h) ~= "number" then return nil, "no_dimensions" end
    if not png then return nil, "encode_failed" end
    return png, w, h
  end

  return engine
end

return Engine
