-- A tiny stand-in for the parts of Gen1Recomp v0.3.49 that gen1-bridge's
-- kb_engine reads: the Game object (stack, save, data), the overworld with
-- its player, and the engine modules it requires. Written for Kanto Bridge;
-- field names follow the engine (see lib/kb_engine.lua for the file:line of
-- each). Everything is synthetic: made-up maps, flags and items.
--
--   local E = FakeEngine.new()
--   bridge modules: E.modules (require name -> table), mod.ui: E.ui
--   E.enterWorld("TEST_TOWN", 5, 6, "down"); E.startStep("up"); E.land()
--   E.push(state) / E.pop(); E.text() makes a TextBox-like state

local FakeEngine = {}

local DIRS = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }

function FakeEngine.new(opts)
  opts = opts or {}
  local E = { applied = {} }

  E.Version = { engine = opts.engine or "0.3.49", modApi = 2 }
  E.GameVersion = { current = opts.game or "blue" }
  function E.GameVersion.get() return E.GameVersion.current end
  E.GameModule = { logicStep = 0 }
  E.Badges = {
    list = function() return { { id = "BOULDERBADGE" }, { id = "CASCADEBADGE" }, { id = "TESTBADGE", item = "TEST_BADGE_ITEM" } } end,
    itemFor = function(entry) return entry.item or entry.id end,
  }
  local function recorder(name)
    return { applyOptions = function(o)
      E.applied[#E.applied + 1] = name
      E.lastApplied = o
    end }
  end
  E.modules = {
    ["src.core.Version"] = E.Version,
    ["src.core.GameVersion"] = E.GameVersion,
    ["src.core.Game"] = E.GameModule,
    ["src.inventory.Badges"] = E.Badges,
    ["src.render.Tilt"] = recorder("Tilt"),
    ["src.render.Pipelines"] = recorder("Pipelines"),
    ["src.render.Zoom"] = recorder("Zoom"),
  }

  -- mod.ui module tables; instances use them as metatables
  E.ui = { TextBox = {}, Menu = {}, ListMenu = {}, ChoiceBox = {}, QuantityBox = {},
           NamingScreen = {}, PicBox = {} }

  local stack = { states = {} }
  function stack:top() return self.states[#self.states] end
  E.stack = stack

  E.save = {
    player = { map = "TEST_HOUSE", x = 3, y = 6, facing = "down", name = "TESTER" },
    flags = { EVENT_TEST_ONE = true, EVENT_TEST_OFF = false },
    objectToggles = {},
    inventory = { POTION = 2, BOULDERBADGE = true },
    money = 3000,
    coins = 0,
    party = {},
    pokedex = { seen = {}, owned = {} },
    options = {
      tilt = 2, zoom = -1, battleLayout = "wide", battleFit = "fill",
      uiLayout = "dynamic", textSpeed = 3, colors = "gbc",
      pipelines = { test_voxel = 2, test_off = 0 },
    },
  }
  E.data = { field = { darkMaps = { maps = { "TEST_CAVE" } } } }
  E.game = { stack = stack, save = E.save, data = E.data }

  -- the overworld module table: isOverworld, plus the fields kb_engine reads
  local runner = { running = false }
  function runner:isRunning() return self.running end
  E.ow = { isOverworld = true, scriptMoves = {}, npcs = {}, runner = runner, hopLand = 0 }

  function E.enterWorld(map, x, y, facing)
    E.ow.map = { id = map }
    E.ow.player = { cellX = x, cellY = y, facing = facing or "down", moving = false }
    stack.states = { E.ow }
  end

  function E.leaveWorld()
    stack.states = {}
  end

  function E.push(state) stack.states[#stack.states + 1] = state end
  function E.pop() stack.states[#stack.states] = nil end

  function E.text() return setmetatable({ isTextBox = true }, E.ui.TextBox) end
  function E.menu(screenId) return setmetatable({ screenId = screenId }, E.ui.Menu) end
  function E.transition() return {} end -- no marker: transient

  -- A step as Player:tryMove or updateScriptMoves starts it (targets, frames).
  function E.startStep(dir, frames)
    local p = E.ow.player
    local d = DIRS[dir]
    p.facing = dir
    p.targetX, p.targetY = p.cellX + d[1], p.cellY + d[2]
    p.stepFramesCur = frames or 16
    p.moving = true
  end

  -- Landing (Player.lua:246-248): snap to the target, clear it.
  function E.land()
    local p = E.ow.player
    p.cellX, p.cellY = p.targetX, p.targetY
    p.targetX, p.targetY = nil, nil
    p.moving = false
  end

  -- Land and start the next step within the same logic step, as a held
  -- direction does on the landing poll.
  function E.landAndContinue(dir, frames)
    E.land()
    E.startStep(dir, frames)
  end

  function E.npc(id, sprite, x, y, def)
    local npc = { id = id, def = def or { sprite = sprite }, cellX = x, cellY = y, facing = "down", moving = false }
    E.ow.npcs[#E.ow.npcs + 1] = npc
    return npc
  end

  FakeEngine.addExport(E)
  return E
end

-- ------------------------------------------------------------ export (M2)
--
-- Stand-ins for the engine pieces the world export asks (lib/kb_engine.lua
-- names the real file:line of each): the runtime Map, the neighbour BFS, the
-- tile-animation table, image loading, and the dataset views. Behaviour
-- follows the engine's documented rules; the code is our own, and the world
-- they serve is made up (tools/lua/export_world.py).

local function setOf(list)
  local out = {}
  for _, v in ipairs(type(list) == "table" and list or {}) do out[v] = true end
  return out
end

local function deepCopy(v)
  if type(v) ~= "table" then return v end
  local out = {}
  for k, x in pairs(v) do out[k] = deepCopy(x) end
  return out
end

-- The runtime map: a cell's behaviour is its bottom-left 8x8 tile; water
-- falls back to $14 and shore to $32/$48 (none on SHIP_PORT); pads and holes
-- per tileset unless the record carries its own.
local function newMapModule()
  local Map = {}
  Map.__index = Map
  local WATER, SHORE = { 0x14 }, { 0x32, 0x48 }
  local PADS = { FACILITY = { [0x20] = "pad", [0x11] = "hole" }, CAVERN = { [0x22] = "hole" },
                 INTERIOR = { [0x55] = "pad" } }
  Map.calls = 0

  function Map.new(def, ts)
    Map.calls = Map.calls + 1
    local m = setmetatable({ def = def, tileset = ts, id = def.id,
                             widthCells = def.width * 2, heightCells = def.height * 2 }, Map)
    m.walk, m.doors, m.warpSet = setOf(ts.walkable), setOf(ts.doorTiles), setOf(ts.warpTiles)
    m.wet = setOf(ts.waterTiles or WATER)
    local shore = ts.shoreTiles
    if shore == nil and def.tileset ~= "SHIP_PORT" then shore = SHORE end
    for t in pairs(setOf(shore)) do m.wet[t] = true end
    return m
  end

  function Map.isOutdoor(def)
    if def.outdoor ~= nil then return def.outdoor end
    return def.tileset == "OVERWORLD"
  end

  function Map.isOutside(def, tilesets)
    if Map.isOutdoor(def) then return true end
    return setOf(tilesets or { "OVERWORLD", "PLATEAU" })[def.tileset] == true
  end

  -- the region rule and the ghost-battle rule (M6): a def's own field wins,
  -- else the id prefix
  function Map.inRegion(def, region, prefix)
    if def.region ~= nil then return def.region == region end
    return prefix ~= nil and def.id:find(prefix, 1, true) == 1
  end
  function Map.ghostBattles(def)
    if def.ghostBattles ~= nil then return def.ghostBattles end
    if def.id:find("POKEMON_TOWER", 1, true) == 1 then return { unlessItem = "SILPH_SCOPE" } end
    return nil
  end

  function Map:inBounds(cx, cy) return cx >= 0 and cy >= 0 and cx < self.widthCells and cy < self.heightCells end
  function Map:blockAt(bx, by)
    local d = self.def
    if bx < 0 or by < 0 or bx >= d.width or by >= d.height then return d.borderBlock end
    return d.blocks[by * d.width + bx + 1]
  end
  function Map:cellTile(cx, cy)
    local tx, ty = cx * 2, cy * 2 + 1
    local block = self.tileset.blocks[self:blockAt(math.floor(tx / 4), math.floor(ty / 4)) + 1]
    return block[(ty % 4) * 4 + (tx % 4) + 1]
  end
  function Map:isWalkableCell(cx, cy) return self.walk[self:cellTile(cx, cy)] or false end
  function Map:isGrassCell(cx, cy)
    if not self:inBounds(cx, cy) then return false end
    local g = self.tileset.grassTile
    return g ~= nil and self:cellTile(cx, cy) == g
  end
  function Map:isWaterCell(cx, cy) return self.wet[self:cellTile(cx, cy)] or false end
  function Map:isDoorTileCell(cx, cy) return self.doors[self:cellTile(cx, cy)] or false end
  function Map:isWarpTileCell(cx, cy)
    local t = self:cellTile(cx, cy)
    return self.doors[t] or self.warpSet[t] or false
  end
  function Map:warpPadOrHoleAt(cx, cy)
    local pads = self.tileset.warpPadTiles or PADS[self.def.tileset]
    return pads and pads[self:cellTile(cx, cy)] or nil
  end
  function Map:isCounterCell(cx, cy) return setOf(self.tileset.counterTiles)[self:cellTile(cx, cy)] or false end
  return Map
end

-- The neighbour BFS: each new neighbour is placed off its parent by the
-- connection direction and offset (32 px per block).
local function newOverworldModule()
  local OC = {}
  local ORDER = { "north", "south", "west", "east" }
  function OC.computeNeighbors(maps, rootId)
    local out, placed = {}, { [rootId] = true }
    local queue, i = { { id = rootId, x = 0, y = 0 } }, 1
    while queue[i] do
      local cur = queue[i]
      i = i + 1
      local def = maps[cur.id]
      for _, dir in ipairs(ORDER) do
        local c = def.connections and def.connections[dir]
        local nd = c and maps[c.map]
        if nd and not placed[c.map] then
          placed[c.map] = true
          local x, y
          if dir == "north" then x, y = cur.x + c.offset * 32, cur.y - nd.height * 32
          elseif dir == "south" then x, y = cur.x + c.offset * 32, cur.y + def.height * 32
          elseif dir == "west" then x, y = cur.x - nd.width * 32, cur.y + c.offset * 32
          else x, y = cur.x + def.width * 32, cur.y + c.offset * 32 end
          out[#out + 1] = { id = c.map, ox = x, oy = y }
          queue[#queue + 1] = { id = c.map, x = x, y = y }
        end
      end
    end
    return out
  end
  return OC
end

function FakeEngine.addExport(E)
  E.Map = newMapModule()
  E.modules["src.world.Map"] = E.Map
  E.modules["src.world.OverworldController"] = newOverworldModule()

  -- tile animations: water shifts in place; flowers and the spinner strip
  -- come from images the world names (E.animationPaths)
  E.animationPaths = { flowers = {}, spinners = nil }
  local TileRenderer = { SPINNER_ARROW_TILES = { GYM = { 0x3c, 0x3d, 0x4c, 0x4d }, FACILITY = { 0x20, 0x21, 0x30, 0x31 } } }
  -- the border the renderer draws: OVERWORLD maps by the player's void fill, others their own block
  TileRenderer.VOID_FILLS = { "trees", "water", "black" }
  TileRenderer.voidFill = "trees"
  function TileRenderer.borderBlockFor(map)
    if map.def.tileset == "OVERWORLD" then
      if TileRenderer.voidFill == "water" then return 0x43 end
      if TileRenderer.voidFill == "black" then return false end
      return 0x0F
    end
    return map.def.borderBlock
  end
  function TileRenderer.defaultAnimatedTiles(ts)
    local out = {}
    if ts.animation == "TILEANIM_WATER" or ts.animation == "TILEANIM_WATER_FLOWER" then
      out[#out + 1] = { tile = 0x14, kind = "hshift", period = 20, offsets = { 1, 2, 3, 2, 1, 0, 7, 0 } }
    end
    if ts.animation == "TILEANIM_WATER_FLOWER" then
      out[#out + 1] = { tile = 0x03, kind = "frames", period = 20, images = E.animationPaths.flowers, sequence = { 1, 2, 3 } }
    end
    if TileRenderer.SPINNER_ARROW_TILES[ts.id] and E.animationPaths.spinners then
      out[#out + 1] = { tiles = TileRenderer.SPINNER_ARROW_TILES[ts.id], kind = "toggle",
                        image = E.animationPaths.spinners, gate = "spinning",
                        stripOffsets = { [0x3c] = 1, [0x3d] = 3, [0x4c] = 0, [0x4d] = 2 } }
    end
    return out
  end
  E.modules["src.render.TileRenderer"] = TileRenderer

  -- image loading: E.images[path] = { w, h, png }; anything else fails
  E.images = {}
  E.imageLoads = 0
  local Assets = {}
  function Assets.imageData(path)
    local img = E.images[path]
    if not img then error("Could not open file " .. tostring(path), 2) end
    E.imageLoads = E.imageLoads + 1
    local data = {}
    function data.getWidth() return img[1] end
    function data.getHeight() return img[2] end
    function data.encode(_, fmt)
      assert(fmt == "png", "only png")
      return { getString = function() return img[3] end, release = function() end }
    end
    function data.release() end
    return data
  end
  E.modules["src.render.Assets"] = Assets

  -- The dataset views (mod.datasets): open(version) gives a view whose
  -- registries hand out deep copies, ids sorted; E.datasetMode "ok",
  -- "missing" (open fails) or "broken" (the view reads nothing, like a cache
  -- the engine rejected).
  E.datasetMode = "ok"
  E.datasetOpens = 0
  E.datasetReads = 0
  local function registry(source)
    local reg = {}
    function reg.get(_, id)
      if E.datasetMode == "broken" then return nil end
      E.datasetReads = E.datasetReads + 1
      return deepCopy(source and source[id])
    end
    function reg.has(_, id) return reg.get(nil, id) ~= nil end
    function reg.each()
      local ids = {}
      if E.datasetMode ~= "broken" then
        for id in pairs(source or {}) do ids[#ids + 1] = id end
      end
      table.sort(ids)
      local i = 0
      return function()
        i = i + 1
        local id = ids[i]
        if id == nil then return nil end
        E.datasetReads = E.datasetReads + 1
        return id, deepCopy(source[id])
      end
    end
    return reg
  end
  E.datasets = {
    open = function(_, version)
      E.datasetOpens = E.datasetOpens + 1
      if E.datasetMode == "missing" then return nil, "not_imported" end
      if version ~= E.GameVersion.current then return nil, "not_imported" end
      local w = E.world or {}
      local content = {}
      for _, name in ipairs({ "maps", "tilesets", "sprites", "palettes", "encounters", "pokemon",
                              "moves", "items", "field", "constants" }) do
        -- the simulated overworld's maps as imported (M8, fake_world.lua's
        -- E.simPristine) when no export world is installed
        content[name] = registry(w[name] or (name == "maps" and E.simPristine or nil))
      end
      return { version = version, generation = 1, content = content }
    end,
  }

  -- Installs a made-up world: registries (maps, tilesets, sprites, palettes,
  -- encounters, pokemon, moves, items, field, constants), images and the
  -- animation image paths. The live Data mirrors it (palettes nest under
  -- palettes.palettes, as in the engine).
  function E.setWorld(world)
    E.world = world.registries
    E.images = world.images or {}
    E.animationPaths = world.animations or E.animationPaths
    -- the player's void-fill option (the renderer's border for OVERWORLD maps)
    if E.modules["src.render.TileRenderer"] then
      E.modules["src.render.TileRenderer"].voidFill = world.voidFill or "trees"
    end
    local r = E.world
    E.data.maps, E.data.tilesets, E.data.sprites = r.maps, r.tilesets, r.sprites
    E.data.palettes = { palettes = r.palettes }
    E.data.encounters, E.data.pokemon, E.data.moves, E.data.items = r.encounters, r.pokemon, r.moves, r.items
    E.data.field, E.data.constants = r.field, r.constants
  end
end

return FakeEngine
