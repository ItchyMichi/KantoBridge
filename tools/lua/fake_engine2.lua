-- A stand-in for the parts of Gen1Recomp v0.3.85's Gen 2 engine (`gs` for
-- Gold and Silver, `crystal`) that gen1-bridge's gen2 adapter reads
-- (lib/engine/gen2.lua and its parts, lib/engine/gen2/*.lua and
-- lib/engine/common/*.lua; docs/specs/GEN2_BRIDGE_PLAN.md §10): the Game2
-- object (phase, world, save, stack, input, data and the methods the adapter
-- calls), the World with its map, player and people, the engine modules the
-- adapter requires, mod.world's Gen 2 facade, and a field step so main.lua can
-- be stepped on it. Written for Kanto Bridge; module and field names follow
-- the engine (file:line of each below, refs/gen1recomp at v0.3.85).
-- Everything is synthetic: made-up maps (TEST_*), collision bytes, flags by
-- number, items and sprites.
--
--   local E = FakeEngine2.new({ game = "crystal" })
--   bridge modules: E.modules (require name -> table); the Game2 object: E.game
--   E.enterWorld("TEST_TOWN", 5, 6, "down")   CONTINUE's or NEW GAME's result (map.entered)
--   E.leaveWorld()                            back to the title (Game2:returnToTitle's state)
--   E.coll("TEST_TOWN", x, y, 0x07)           a cell's COLL byte (0x00 floor by default)
--   E.addWarp("TEST_TOWN", x, y, "TEST_HOUSE", destX, destY)
--   E.addNpc(index, { x =, y =, sprite =, trainer =, itemball =, movement = })
--   E.walkNpc(index, dir)                     an NPC step: movement.collision with the NPC as mover
--   E.setFlag(id, on)                         an event flag by number (World.events)
--   E.openTextBox(), E.openChoice(), E.pushScreen("Gen2StartMenu")
--   E.newSave(), E.slotSave("slot1", save)    a Gen 2 save, one in a slot (what CONTINUE loads)
--   E.bindMod(FakeMod, mod)                   events and hooks go to that mod object
--   E.tick()                                  the engine's part of a logic step (after input.step)
--
-- Test switches: E.saveResult (nil: written, "refuse", "error"), E.quickSaveOk
-- (false: the field can't save), E.unionApplied, E.newGameFails,
-- E.continueFails, E.refuseWarp; records: E.newGames, E.continues, E.saveCalls,
-- E.titleCalls, E.musicCalls, E.warps, E.queued, E.replaced, E.slotCalls,
-- E.tryMoves, E.worldSteps.

local FakeEngine2 = {}

local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
local DIR_ORDER = { "up", "down", "left", "right" } -- heldDirection's order (World.lua:10368-10381)
local TURN_FRAMES, WALK_FRAMES = 4, 16             -- Player.lua:12-13

-- The COLL bytes a step may land on (Permissions.isWalkable's LAND set and the
-- warps; src/core/CollPermissions.lua:9-52): floor, tall and long grass, ice,
-- the warps, the ledges (a refused step, then a hop: not simulated here).
local WALKABLE = { [0x00] = true, [0x18] = true, [0x14] = true, [0x23] = true, [0x60] = true, [0x68] = true,
                   [0x70] = true, [0x71] = true, [0x72] = true, [0x76] = true, [0x78] = true, [0x7a] = true,
                   [0x7b] = true, [0x7c] = true, [0x7e] = true }
-- the warp bytes a walked step takes (Permissions.lua:300-345)
local WARP = { [0x60] = true, [0x68] = true, [0x70] = true, [0x71] = true, [0x72] = true, [0x76] = true,
               [0x78] = true, [0x7a] = true, [0x7b] = true, [0x7c] = true, [0x7e] = true }

local function cellKey(x, y) return x .. "," .. y end

local function deepCopy(v)
  if type(v) ~= "table" then return v end
  local out = {}
  for k, x in pairs(v) do out[k] = deepCopy(x) end
  return out
end

-- ------------------------------------------------------------ input

-- The engine's shared Input as mod.input presses into it (src/core/Input.lua;
-- src/mods/Loader.lua:1466-1503): sources per button; a press sets the held
-- state at once and queues an edge; Input:step turns the queued edges into one
-- step of wasPressed (as fake_engine3.lua's).
local function newInput()
  local input = { state = {}, sources = {}, pressQueue = {}, pressed = {} }
  function input:sourcePress(btn, source)
    local s = self.sources[btn] or {}
    self.sources[btn] = s
    if not s[source] then
      s[source] = true
      self.pressQueue[#self.pressQueue + 1] = btn
    end
    self.state[btn] = true
  end
  function input:sourceRelease(btn, source)
    local s = self.sources[btn]
    if s then
      s[source] = nil
      if next(s) == nil then self.state[btn] = false end
    else
      self.state[btn] = false
    end
  end
  function input:step()
    self.pressed = {}
    for _, btn in ipairs(self.pressQueue) do
      self.pressed[btn] = true
      local s = self.sources[btn]
      if s == nil or next(s) ~= nil then self.state[btn] = true end
    end
    for btn, s in pairs(self.sources) do
      if next(s) == nil then self.sources[btn] = nil end
    end
    self.pressQueue = {}
  end
  function input:isDown(btn) return self.state[btn] == true end
  function input:wasPressed(btn) return self.pressed[btn] == true end
  return input
end

-- ------------------------------------------------------------ the engine

function FakeEngine2.new(opts)
  opts = opts or {}
  local E = {
    newGames = {}, continues = {}, saveCalls = {}, titleCalls = {}, musicCalls = {}, warps = {}, queued = {},
    replaced = {}, slotCalls = {}, tryMoves = {}, worldSteps = 0, ticks = 0,
  }
  local emit = function() end
  local callHook = function(_, vanilla, ...) return vanilla(...) end

  -- src/core/Version.lua:6-23: the release, the native-shell contract, the mod
  -- API major and the save format
  E.Version = { engine = opts.engine or "0.3.85", shell = 3, modApi = 2, saveFormat = 5 }
  -- src/core/GameVersion.lua:59-111: Gold and Silver are engine "gs", Crystal
  -- "crystal"; get (:209), generation (:231-233), engine (:235-238)
  E.GameVersion = { current = opts.game or "crystal" }
  E.engineId = opts.engineId or (E.GameVersion.current == "crystal" and "crystal" or "gs")
  function E.GameVersion.get() return E.GameVersion.current end
  function E.GameVersion.generation() return 2 end
  function E.GameVersion.engine() return E.engineId end

  -- ---- save slots: SaveData's registry (src/core/SaveData.lua:1642-1900; Gen 2
  -- saves resolve the active slot the same way, src/core/gen2/Save.lua:109-152),
  -- keyed by version (as fake_engine3.lua's)
  E.slotReg = { list = {}, names = {} }
  E.slotFiles, E.slotSaves = {}, {}
  E.activeSlot = nil
  local function call(name, version)
    E.slotCalls[#E.slotCalls + 1] = { name = name, version = version }
  end
  local function known(version) return version == E.GameVersion.current end
  local function registered(id)
    for _, v in ipairs(E.slotReg.list) do
      if v == id then return true end
    end
    return false
  end
  local SaveData = {}
  function SaveData.activeSlot(version)
    call("activeSlot", version)
    if not known(version) then return nil end
    return E.activeSlot
  end
  function SaveData.setActiveSlot(version, id)
    call("setActiveSlot", version)
    if not known(version) then return nil end
    if not registered(id) then E.slotReg.list[#E.slotReg.list + 1] = id end
    E.slotReg.active, E.activeSlot = id, id
    return id
  end
  function SaveData.createSlot(version)
    call("createSlot", version)
    if not known(version) then return nil end
    local max = 0
    for _, id in ipairs(E.slotReg.list) do
      local n = tonumber(id:match("^slot(%d+)$"))
      if n and n > max then max = n end
    end
    local id = "slot" .. (max + 1)
    E.slotReg.list[#E.slotReg.list + 1] = id
    return id
  end
  function SaveData.renameSlot(version, id, label)
    call("renameSlot", version)
    if not known(version) then return false, "unknown version" end
    if not registered(id) then return false, "slot not registered" end
    E.slotReg.names[id] = label
    return true
  end
  function SaveData.deleteSlot(version, id)
    call("deleteSlot", version)
    if not known(version) then return false, "unknown version" end
    if not registered(id) then return false, "slot not registered" end
    for i, v in ipairs(E.slotReg.list) do
      if v == id then table.remove(E.slotReg.list, i) break end
    end
    E.slotFiles[id], E.slotSaves[id], E.slotReg.names[id] = nil, nil, nil
    if E.slotReg.active == id then E.slotReg.active = E.slotReg.list[1] end
    if E.activeSlot == id then E.activeSlot = E.slotReg.active end
    return true
  end
  function SaveData.listSlots(version)
    call("listSlots", version)
    local out = {}
    for i, v in ipairs(known(version) and E.slotReg.list or {}) do out[i] = v end
    return out
  end
  function SaveData.loadOptions()
    local list, names = {}, {}
    for i, v in ipairs(E.slotReg.list) do list[i] = v end
    for k, v in pairs(E.slotReg.names) do names[k] = v end
    return { saveSlots = { [E.GameVersion.current] = { list = list, active = E.slotReg.active, names = names } } }
  end
  function SaveData.persistenceFs()
    return { getInfo = function(path)
      local version, id = path:match("^saves/([%w_]+)/(slot%d+)%.lua")
      if version == E.GameVersion.current and E.slotFiles[id] then return { type = "file" } end
      return nil
    end }
  end
  E.SaveData = SaveData

  -- ---- saves (src/core/gen2/Save.lua): a new game's table (Save.newGame,
  -- :192-310, made-up values), exists/load (:882-934: nil without a save)

  function E.newSave()
    return {
      version = E.GameVersion.current,
      player = { name = "TESTER", id = 4321, gender = "male", money = 3000, coins = 0, badges = {}, kantoBadges = {} },
      rival = { name = "???" },
      position = nil, inventory = {}, pcItems = {}, party = {}, boxes = {}, pokedex = { seen = {}, caught = {} },
      events = { flags = {} }, engineFlags = {}, mapScenes = {}, hallOfFame = { count = 0, teams = {} },
      rtc = {}, options = { textSpeed = "fast" }, modData = {},
    }
  end
  -- A Gen 2 save in a slot (nil removes it).
  function E.slotSave(id, save)
    if save ~= nil and not registered(id) then E.slotReg.list[#E.slotReg.list + 1] = id end
    E.slotFiles[id] = save ~= nil or nil
    E.slotSaves[id] = save
  end
  local Save = {}
  function Save.exists(version)
    return known(version) and E.slotSaves[E.activeSlot or "flat"] ~= nil
  end
  function Save.load(version)
    if not known(version) then return nil end
    local save = E.slotSaves[E.activeSlot or "flat"]
    return save ~= nil and deepCopy(save) or nil
  end
  E.Save = Save

  -- src/core/Music.lua: fadeOut (:363), MAP_FADE (:418), restoreMap (:545), recorded
  E.Music = { MAP_FADE = 10 }
  function E.Music.fadeOut(frames) E.musicCalls[#E.musicCalls + 1] = { op = "fadeOut", frames = frames } end
  function E.Music.restoreMap() E.musicCalls[#E.musicCalls + 1] = { op = "restoreMap" } end

  -- the shared text box and yes/no (src/render/TextBox.lua, src/ui/ChoiceBox.lua):
  -- the adapter tells them by their metatables; A pops either here
  E.TextBox = {}
  E.TextBox.__index = E.TextBox
  function E.TextBox.new(pages)
    return setmetatable({ pages = pages or { "TEST LINE" }, pageIndex = 1, waiting = true, done = false }, E.TextBox)
  end
  function E.TextBox:update()
    if E.game.input:wasPressed("a") then E.game.stack:pop() end
  end
  E.ChoiceBox = {}
  E.ChoiceBox.__index = E.ChoiceBox
  function E.ChoiceBox.new(onChoose)
    return setmetatable({ index = 1, labels = { "YES", "NO" }, onChoose = onChoose }, E.ChoiceBox)
  end
  function E.ChoiceBox:update()
    local input = E.game.input
    if input:wasPressed("a") or input:wasPressed("b") then
      local yes = input:wasPressed("a") and self.index == 1
      E.game.stack:pop()
      if self.onChoose then self.onChoose(yes) end
    end
  end

  -- src/world/gen2/FieldMoves.lua:136-150 (the badges' order)
  E.FieldMoves = {
    JOHTO_BADGES = { "ZEPHYR", "HIVE", "PLAIN", "FOG", "MINERAL", "STORM", "GLACIER", "RISING" },
    KANTO_BADGES = { "BOULDER", "CASCADE", "THUNDER", "RAINBOW", "SOUL", "MARSH", "VOLCANO", "EARTH" },
  }
  -- src/world/gen2/UnionCenter2F.lua:222-224 (E.unionApplied: the option on)
  E.UnionCenter2F = {}
  function E.UnionCenter2F.isApplied(maps) return E.unionApplied == true and maps ~= nil end

  -- The cell predicates the observer asks about forced movement (src/world/gen2/Permissions.lua:111,
  -- :135-160): ice (0x23 and its alias 0x2b), the currents 0x38-0x3b, no door pushes on the fake.
  E.Permissions = {}
  function E.Permissions.isIce(c) return c == 0x23 or c == 0x2b end
  function E.Permissions.currentDirection(c)
    return ({ [0x38] = "right", [0x39] = "left", [0x3a] = "up", [0x3b] = "down" })[c]
  end
  function E.Permissions.doorForcedDirection() return nil end

  -- ---- maps (src/world/gen2/Map.lua): made-up records in data.gen2Maps (blocks
  -- of 2x2 cells), the COLL bytes per cell in E.colls[map][x,y] (0x00 floor)

  E.maps = {}
  E.colls = {}
  function E.newMap(id, w, h, o)
    o = o or {}
    E.maps[id] = { id = id, width = w, height = h, blocks = {}, borderBlock = 1, tileset = "TILESET_TEST",
                   environment = o.environment or "TOWN", palette = o.palette or "PALETTE_AUTO", warps = {},
                   objects = {}, connections = {} }
    E.colls[id] = {}
    for i = 1, w * h do E.maps[id].blocks[i] = 1 end
    return E.maps[id]
  end
  E.newMap("TEST_TOWN", 10, 10)
  E.newMap("TEST_HOUSE", 5, 4, { environment = "INDOOR", palette = "PALETTE_DAY" })
  E.newMap("TEST_HOUSE_2F", 4, 4, { environment = "INDOOR", palette = "PALETTE_DAY" })
  E.newMap("TEST_CAVE", 8, 8, { environment = "CAVE", palette = "PALETTE_DARK" })
  E.newMap("TEST_CENTER", 5, 4, { environment = "INDOOR", palette = "PALETTE_DAY" })
  E.newGameStart = { map = "TEST_HOUSE_2F", x = 3, y = 3, facing = "down" } -- World.lua:1468-1519

  function E.coll(map, x, y, byte) E.colls[map][cellKey(x, y)] = byte end
  -- a walked warp (x, y in cells; the destination's cell given directly here)
  function E.addWarp(map, x, y, dest, destX, destY, byte)
    local w = E.maps[map].warps
    w[#w + 1] = { x = x, y = y, destMap = dest, destWarp = 1, destX = destX, destY = destY }
    E.coll(map, x, y, byte or 0x71)
  end

  local Map = {}
  Map.__index = Map
  function Map.new(def)
    return setmetatable({ id = def.id, def = def, width = def.width, height = def.height,
                          widthCells = def.width * 2, heightCells = def.height * 2, blocks = def.blocks,
                          borderBlock = def.borderBlock }, Map)
  end
  function Map:inBounds(cx, cy) -- :41-44
    return cx >= 0 and cy >= 0 and cx < self.widthCells and cy < self.heightCells
  end
  function Map:cellCollision(cx, cy) -- :55-63 (0xff off the map)
    if not self:inBounds(cx, cy) then return 0xff end
    return (E.colls[self.id] or {})[cellKey(cx, cy)] or 0x00
  end
  function Map:isWalkable(cx, cy) -- :65-68
    return self:inBounds(cx, cy) and WALKABLE[self:cellCollision(cx, cy)] == true
  end
  function Map:warpAt(cx, cy) -- :113-117
    for _, w in ipairs(self.def.warps or {}) do
      if w.x == cx and w.y == cy then return w end
    end
    return nil
  end
  function Map:isCounterCell(cx, cy) return self:cellCollision(cx, cy) == 0x90 end -- :123
  function Map:blockId(bx, by) -- :46-51
    if bx < 0 or by < 0 or bx >= self.width or by >= self.height then return self.borderBlock end
    return self.blocks[by * self.width + bx + 1] or 0
  end
  E.Map = Map

  -- ---- the player (src/world/gen2/Player.lua): fields (:34-58), tryMove (:97-136:
  -- a different facing turns first when armed, 4 frames; the verdict goes
  -- through movement.collision; "edge" off the map), scriptFace (:143-145)

  local Player = {}
  Player.__index = Player
  function Player.new(cx, cy, facing)
    return setmetatable({ cellX = cx, cellY = cy, facing = facing or "down", moving = false, targetX = cx,
                          targetY = cy, progress = 0, stepFrames = WALK_FRAMES, jumping = false, turnTimer = 0,
                          turnArmed = true }, Player)
  end
  function Player:scriptFace(dir) if dir then self.facing = dir end end
  local function passthrough(allowed) return allowed end
  function Player:tryMove(dir, map, world)
    E.tryMoves[#E.tryMoves + 1] = { dir = dir, mover = "player" }
    if self.moving then return nil end
    if self.facing ~= dir then
      self.facing = dir
      self.bumpFrames = nil
      if self.turnArmed then
        self.turnArmed = false
        self.turnTimer = TURN_FRAMES
        return "turned"
      end
    end
    if self.turnTimer > 0 then return nil end
    local d = DELTA[dir]
    local tx, ty = self.cellX + d[1], self.cellY + d[2]
    local allowed, why = true, nil
    if not map:inBounds(tx, ty) then
      allowed, why = false, "bounds"
    elseif not map:isWalkable(tx, ty) then
      allowed, why = false, "tile"
    elseif world:npcAt(tx, ty) then
      allowed, why = false, "entity"
    end
    local ctx = { map = map, mover = self, dir = dir, fromX = self.cellX, fromY = self.cellY, toX = tx, toY = ty,
                  reason = why }
    allowed = callHook("movement.collision", passthrough, allowed, ctx)
    if not allowed then
      self.bumpFrames = 1
      return ctx.reason == "bounds" and "edge" or "blocked"
    end
    self.targetX, self.targetY, self.moving, self.progress, self.bumpFrames = tx, ty, true, 0, nil
    return "moved"
  end
  E.Player = Player

  -- ---- the event flags (src/world/gen2/Events.lua:11-58): byte -> bits; set
  -- emits flag.changed { name = number, value } on a real transition
  local Events = {}
  Events.__index = Events
  function Events.new(flags) return setmetatable({ flags = flags or {} }, Events) end
  function Events:get(id)
    if not id or id < 0 then return false end
    local row = self.flags[math.floor(id / 8)] or 0
    return math.floor(row / (2 ^ (id % 8))) % 2 == 1
  end
  function Events:set(id, value)
    local before = self:get(id)
    local byte, mask = math.floor(id / 8), 2 ^ (id % 8)
    local row = self.flags[byte] or 0
    if value and not before then self.flags[byte] = row + mask end
    if not value and before then self.flags[byte] = row - mask end
    if before ~= (value and true or false) then emit("flag.changed", { name = id, value = value and true or false }) end
  end
  E.Events = Events

  -- ---- the World (src/world/gen2/World.lua): the overworld of one map, its
  -- player and people, rebuilt on every CONTINUE (Game2.lua:347-348)

  local World = {}
  World.__index = World
  E.World = World
  function World.new(mapId, cx, cy, facing, save)
    local w = setmetatable({ game = E.game, npcs = {}, blockEdits = {}, mapSetup = nil, pendingWarp = nil,
                             battleActive = nil, moveState = nil, waitButton = nil, playerState = "normal",
                             flashUsed = false, pendingSceneScript = false, vmRunning = false, clockHour = 12,
                             heldDir = nil }, World)
    w.events = Events.new(type(save) == "table" and type(save.events) == "table" and save.events.flags or nil)
    w.player = Player.new(cx, cy, facing)
    w:setMap(mapId, cx, cy, facing)
    return w
  end
  function World:setMap(mapId, cx, cy, facing)
    self.map = Map.new(E.maps[mapId])
    self.player.cellX, self.player.cellY, self.player.targetX, self.player.targetY = cx, cy, cx, cy
    self.player.moving, self.player.progress = false, 0
    if facing then self.player.facing = facing end
    self.npcs = {}
    for _, n in ipairs(E.npcDefs[mapId] or {}) do
      self.npcs[#self.npcs + 1] = { def = n.def, id = mapId .. "_obj_" .. n.def.index, mapId = mapId, cellX = n.x,
                                   cellY = n.y, facing = n.facing or "down", moving = false, targetX = n.x,
                                   targetY = n.y, progress = 0 }
    end
    self.blockEdits = {}
  end
  function World:npcAt(cx, cy)
    for _, n in ipairs(self.npcs) do
      if n.cellX == cx and n.cellY == cy then return n end
    end
    return nil
  end
  function World:scriptRunning() return self.vmRunning == true end -- :1527-1529
  function World:busy() -- :1531-1565
    return self.vmRunning or self.mapSetup ~= nil or self.moveState ~= nil or self.waitButton ~= nil
  end
  function World:acceptsMenuInput() -- :1567-1578
    if self.battleActive or self:busy() then return false end
    if self.player.moving then return false end
    return true
  end
  function World:downhill() return E.downhill == true end
  function World:playerCollision() return self.map:cellCollision(self.player.cellX, self.player.cellY) end -- :5247-5251
  function World:hour() return self.clockHour end
  function World:restoreMapMusic() E.musicCalls[#E.musicCalls + 1] = { op = "restoreMapMusic" } end -- :2760
  function World:pollInput(input) -- :11553-11557: directions are read with isDown only
    self.heldDir = nil
    for _, d in ipairs(DIR_ORDER) do
      if input:isDown(d) then self.heldDir = d break end
    end
  end
  -- A full map load (setMap: map.exited, then map.entered; a same-map warp too, :10134-10354)
  function World:loadMap(mapId, cx, cy, facing, via)
    local from = self.map and self.map.id or nil
    if from then emit("map.exited", { mapId = from, toMapId = mapId }) end
    self:setMap(mapId, cx, cy, facing)
    emit("map.entered", { mapId = mapId, map = self.map, fromMapId = from, via = via or "warp" })
  end
  -- A walked warp (takeWarp, :10473-10547): warp.destination, player.warped, the load
  function World:takeWarp(w)
    local function vanilla(map, x, y) return map, x, y end
    local map, x, y = callHook("warp.destination", vanilla, w.destMap, w.destX or 1, w.destY or 1,
      { warp = w, lastMap = self.map.id, destWarp = w.destWarp })
    if not E.maps[map] then return end
    emit("player.warped", { fromMap = self.map.id, toMap = map, x = x, y = y, warp = w, toWarp = w.destWarp })
    self:loadMap(map, x, y, nil, "warp")
  end
  -- A step under way advances and lands (world.stepped, :11470-11481, with the
  -- cell's COLL byte; a walked warp); a held direction starts the next step in
  -- the same update (:11452, :11541); a tick with no direction held while idle
  -- re-arms the turn (:11530-11535).
  function World:movePlayer(dir)
    local r = self.player:tryMove(dir, self.map, self)
    return r
  end
  function World:step()
    E.worldSteps = E.worldSteps + 1
    local p = self.player
    if p.turnTimer > 0 then p.turnTimer = p.turnTimer - 1 end
    if p.moving then
      p.progress = p.progress + 1
      if p.progress >= p.stepFrames then
        p.cellX, p.cellY, p.moving, p.progress = p.targetX, p.targetY, false, 0
        local tile = self.map:cellCollision(p.cellX, p.cellY)
        emit("world.stepped", { mapId = self.map.id, x = p.cellX, y = p.cellY, tile = tile, tod = "DAY",
                                daytime = "DAY" })
        local w = self.map:warpAt(p.cellX, p.cellY)
        if w and WARP[tile] then
          self:takeWarp(w)
          return
        end
        if self.heldDir then self:movePlayer(self.heldDir) end
      end
      return
    end
    if self:busy() then return end
    if self.heldDir then
      self:movePlayer(self.heldDir)
    else
      p.turnArmed = true
    end
  end
  -- The A press (World:interact, :8649-8676): world.interacted on the faced
  -- cell, before its script; an NPC answers with a text box.
  function World:interact()
    local p = self.player
    local d = DELTA[p.facing]
    local x, y = p.cellX + d[1], p.cellY + d[2]
    local n = self:npcAt(x, y)
    if not n and self.map:isCounterCell(x, y) then
      x, y = x + d[1], y + d[2]
      n = self:npcAt(x, y)
    end
    local kind = n and (n.def.trainer and "trainer" or "npc") or "none"
    emit("world.interacted", { mapId = self.map.id, x = x, y = y, kind = kind, target = n })
    if n then E.game.stack:push(E.TextBox.new({ "TEST TALK" })) end
  end

  -- People: E.npcDefs[map] = list of { def, x, y, facing } (the map's object
  -- defs, RomExtractorGen2.lua:1321-1350: index, sprite, movement, type,
  -- trainer, itemball, eventFlag)
  E.npcDefs = {}
  function E.addNpc(index, o)
    o = o or {}
    local map = o.map or (E.game.world and E.game.world.map.id) or "TEST_TOWN"
    E.npcDefs[map] = E.npcDefs[map] or {}
    local def = { index = index, sprite = o.sprite or "SPRITE_TEST_YOUNGSTER", spriteId = 1,
                  movement = o.movement or 0x05, type = o.type or 0, trainer = o.trainer, itemball = o.itemball }
    local rec = { def = def, x = o.x, y = o.y, facing = o.facing }
    table.insert(E.npcDefs[map], rec)
    local w = E.game.world
    if w and w.map.id == map then
      w.npcs[#w.npcs + 1] = { def = def, id = map .. "_obj_" .. index, mapId = map, cellX = o.x, cellY = o.y,
                              facing = o.facing or "down", moving = false, targetX = o.x, targetY = o.y,
                              progress = 0, hidden = o.hidden }
    end
    return def
  end
  -- An NPC's step: movement.collision with the NPC as mover (Npc.lua:521-535)
  function E.walkNpc(index, dir)
    local w = E.game.world
    for _, n in ipairs(w.npcs) do
      if n.def.index == index then
        local d = DELTA[dir]
        local ctx = { map = w.map, mover = n, dir = dir, fromX = n.cellX, fromY = n.cellY, toX = n.cellX + d[1],
                      toY = n.cellY + d[2], reason = nil }
        local ok = callHook("movement.collision", passthrough, true, ctx)
        if ok then n.cellX, n.cellY = ctx.toX, ctx.toY end
        return ok
      end
    end
    return nil
  end

  function E.setFlag(id, on)
    local w = E.game.world
    if w then
      w.events:set(id, on ~= false)
    else
      local s = E.game.save
      s.events = s.events or { flags = {} }
      local ev = Events.new(s.events.flags)
      ev:set(id, on ~= false)
      s.events.flags = ev.flags
    end
  end

  -- ---- the Game2 object (src/core/Game2.lua)

  local Game2 = {}
  Game2.__index = Game2
  E.Game2 = Game2

  -- the state stack (src/core/StateStack.lua:21-49): screen.pushed/popped after enter/exit
  local function newStack()
    local stack = { states = {} }
    function stack:push(state)
      table.insert(self.states, state)
      emit("screen.pushed", { state = state })
    end
    function stack:pop()
      local state = table.remove(self.states)
      if state then emit("screen.popped", { state = state }) end
      return state
    end
    function stack:top() return self.states[#self.states] end
    function stack:clear() while self:top() do self:pop() end end
    return stack
  end

  E.game = setmetatable({ generation = 2, input = newInput(), stack = newStack(), phase = "boot", world = nil,
                          save = nil, data = { gen2Maps = E.maps } }, Game2)
  E.game.save = E.newSave() -- the boot skeleton (Game2.lua:1254)
  E.data = E.game.data

  -- The title screen while nothing else is up (Gen2TitleState, a Screens record)
  function E.titleScreen()
    E.game.stack:push({ screenId = "Gen2TitleState", update = function() end })
  end
  E.titleScreen()

  -- The field up: a World on the map, the play phase, map.entered (Game2:startWorld,
  -- :262-290; CONTINUE's load, :312-358)
  function E.enterWorld(mapId, x, y, facing, via)
    local g = E.game
    g.stack:clear()
    g.world = World.new(mapId, x, y, facing, g.save)
    g.phase = "play"
    emit("map.entered", { mapId = mapId, map = g.world.map, fromMapId = nil, via = via or "boot" })
    return g.world
  end
  -- back to the title (returnToTitle's result, :374-381)
  function E.leaveWorld()
    local g = E.game
    g.world, g.phase = nil, "boot"
    g.stack:clear()
    E.titleScreen()
  end

  -- Oak's speech (src/ui/gen2/OakSpeech.lua): the step list through
  -- intro.oak_speech.build (:255-270), Crystal's gender first (:247-251); each
  -- step a state on the stack the test answers with A, a `fn` step run in place
  -- (:686-692); the field at the end (startWorld, Game2.lua:277-290)
  E.speechRuns = {}
  local function oakSpeech()
    local g = E.game
    local steps = {
      { id = "init_clock", kind = "initclock" }, { id = "oak_welcome", kind = "say" },
      { id = "ask_player_name", kind = "say" }, { id = "name_player", kind = "name", saveKey = "name" },
      { id = "legend", kind = "say" },
    }
    if E.GameVersion.current == "crystal" then table.insert(steps, 1, { id = "gender_select", kind = "gender" }) end
    local speech = { screenId = "Gen2OakSpeech", game = g, answers = {} }
    function speech.recordAnswer(sp, step, index, label, value) sp.answers[step.saveKey or step.id] = value end
    local list = callHook("intro.oak_speech.build", function(s) return s end, steps, speech)
    speech.steps = type(list) == "table" and list or steps
    E.speechRuns[#E.speechRuns + 1] = speech
    local i = 0
    local function advance()
      i = i + 1
      local step = speech.steps[i]
      if not step then
        g.stack:pop() -- the speech
        local st = E.newGameStart
        g.world = World.new(st.map, st.x, st.y, st.facing, g.save)
        g.phase = "play"
        emit("map.entered", { mapId = st.map, map = g.world.map, fromMapId = nil, via = "boot" })
        return
      end
      if step.kind == "fn" then
        step.run(speech, advance)
        return
      end
      local screen = ({ gender = "Gen2GenderSelect", initclock = "Gen2InitClock", name = "Gen2NamePick" })[step.kind]
      local state
      if screen then
        state = { screenId = screen }
      else
        state = E.TextBox.new({ "TEST SPEECH" })
      end
      state.update = function()
        if g.input:wasPressed("a") then
          g.stack:pop()
          if step.kind == "initclock" then g.save.rtc.startMinute = 77 end
          if step.kind == "name" then g.save.player.name = "PRESET" end
          if step.kind == "gender" then g.save.player.gender = "male" end
          advance()
        end
      end
      g.stack:push(state)
    end
    function speech.update() end
    g.stack:push(speech)
    advance()
  end

  -- :292-310: a fresh save (save.created), then Oak's speech
  function Game2:newGame()
    E.newGames[#E.newGames + 1] = true
    if E.newGameFails then error("fake newGame failed") end
    self.stack:clear()
    self.save = E.newSave()
    emit("save.created", { save = self.save })
    oakSpeech()
  end
  -- :312-358: nil starts a NEW GAME; else save.loading, a new World,
  -- map.entered{via="continue"}, save.loaded
  function Game2:continueGame(save)
    E.continues[#E.continues + 1] = save ~= nil
    if save == nil then return self:newGame() end
    if E.continueFails then error("fake continueGame failed") end
    emit("save.loading", { raw = save })
    self.stack:clear()
    self.save = save
    local pos = type(save.position) == "table" and save.position or { map = "TEST_HOUSE_2F", x = 3, y = 3, facing = "down" }
    self.world = World.new(pos.map, pos.x, pos.y, pos.facing, save)
    self.phase = "play"
    emit("map.entered", { mapId = pos.map, map = self.world.map, fromMapId = nil, via = "continue" })
    emit("save.loaded", { save = save, meta = save.meta })
  end
  -- :374-381
  function Game2:returnToTitle()
    E.titleCalls[#E.titleCalls + 1] = true
    E.leaveWorld()
  end
  -- :1022-1042: the save.write veto, the snapshot into the save, save.writing, the file
  function Game2:writeSave()
    E.saveCalls[#E.saveCalls + 1] = { phase = self.phase }
    if E.saveResult == "refuse" then return false end
    if E.saveResult == "error" then error("fake writeSave failed") end
    local s = self.save
    local w = self.world
    if w then
      s.position = { map = w.map.id, x = w.player.cellX, y = w.player.cellY, facing = w.player.facing }
      s.events = { flags = deepCopy(w.events.flags) }
    end
    s.meta = { savedAt = 1700000000 + #E.saveCalls, sessionStart = 1700000000, engine = E.Version.engine }
    emit("save.writing", { save = s, meta = s.meta })
    local slot = E.activeSlot or "flat"
    E.slotSaves[slot] = deepCopy(s)
    if E.activeSlot then E.slotFiles[E.activeSlot] = true end
    return true
  end
  -- :1044-1050: the World's own START gate
  function Game2:quickSaveAllowed()
    local w = self.world
    if self.phase ~= "play" or not w then return false end
    if not w:acceptsMenuInput() then return false end
    return E.quickSaveOk ~= false
  end

  -- ---- screens a test opens

  function E.openTextBox(pages) local s = E.TextBox.new(pages) E.game.stack:push(s) return s end
  function E.openChoice(cb) local s = E.ChoiceBox.new(cb) E.game.stack:push(s) return s end
  function E.pushScreen(id) local s = { screenId = id, update = function() end } E.game.stack:push(s) return s end

  -- ---- mod.world, the Gen 2 WorldAPI (src/world/gen2/WorldAPI.lua): current
  -- (:114-120), warpTo (:398-411: a known map, a script warp's load, no
  -- player.warped), getFlag/setFlag (:452-475: numbers only), replaceBlock
  -- (:477-484: block units, the active map, world.block_replaced), queueScript
  -- (:636-730: the allowlist, refused before the first row otherwise)

  E.worldApi = {}
  local api = E.worldApi
  function api.overworld(_) return E.game.world end
  function api.current(_)
    local w = E.game.world
    if not w then return nil, "no overworld" end
    return { mapId = w.map.id, x = w.player.cellX, y = w.player.cellY, facing = w.player.facing }
  end
  function api.warpTo(_, map, x, y, facing)
    E.warps[#E.warps + 1] = { map = map, x = x, y = y, facing = facing }
    local w = E.game.world
    if not w then return nil, "no overworld" end
    if E.refuseWarp then return nil, "busy" end
    if type(map) ~= "string" or not E.maps[map] then return nil, "unknown map " .. tostring(map) end
    w:loadMap(map, x, y, facing, "warp")
    return true
  end
  function api.getFlag(_, id)
    if type(id) ~= "number" then return nil, "Gen 2 flags are numbers" end
    local w = E.game.world
    return w and w.events:get(id) or false
  end
  function api.setFlag(_, id, value)
    if type(id) ~= "number" then return nil, "Gen 2 flags are numbers" end
    local w = E.game.world
    if not w then return nil, "no overworld" end
    w.events:set(id, value ~= false)
    return true
  end
  function api.replaceBlock(_, bx, by, block)
    local w = E.game.world
    if not w then return nil, "no overworld" end
    E.replaced[#E.replaced + 1] = { map = w.map.id, bx = bx, by = by, block = block }
    emit("world.block_replaced", { mapId = w.map.id, bx = bx, by = by, block = block })
    return true
  end
  local ALLOWED = { start_battle = true, warp = true, text = true, setflag = true, clearflag = true }
  function api.queueScript(_, rows, extra)
    for _, row in ipairs(rows or {}) do
      if not ALLOWED[row[1]] then return nil, "unsupported script command " .. tostring(row[1]) end
    end
    E.queued[#E.queued + 1] = rows
    for _, row in ipairs(rows) do
      if row[1] == "warp" then
        local ok, err = api.warpTo(api, row[2], row[3], row[4], row[5])
        if not ok then return nil, err end
      end
    end
    if type(extra) == "table" and extra.onDone then extra.onDone(true) end
    return true
  end

  E.modules = {
    ["src.core.Version"] = E.Version,
    ["src.core.GameVersion"] = E.GameVersion,
    ["src.core.SaveData"] = E.SaveData,
    ["src.core.gen2.Save"] = E.Save,
    ["src.core.Music"] = E.Music,
    ["src.render.TextBox"] = E.TextBox,
    ["src.ui.ChoiceBox"] = E.ChoiceBox,
    ["src.world.gen2.World"] = E.World,
    ["src.world.gen2.Player"] = E.Player,
    ["src.world.gen2.Map"] = E.Map,
    ["src.world.gen2.FieldMoves"] = E.FieldMoves,
    ["src.world.gen2.UnionCenter2F"] = E.UnionCenter2F,
    ["src.world.gen2.Permissions"] = E.Permissions,
  }
  -- no ModUI or dataset tables on this fake
  E.ui, E.datasets = nil, nil

  -- ---- one logic step (Game2's stepBody after the input.step hook,
  -- Game2.lua:1289-1348): Input:step; the stack's top state updates and the
  -- step ends there (:1314-1318); else, in the play phase with a World, the
  -- d-pad poll, A -> interact, World:step.
  function E.tick()
    E.ticks = E.ticks + 1
    local g = E.game
    g.input:step()
    local top = g.stack:top()
    if top then
      if type(top.update) == "function" then top.update(top) end
      return
    end
    if g.phase ~= "play" or not g.world then return end
    local w = g.world
    w:pollInput(g.input)
    if g.input:wasPressed("a") then w:interact() end
    if g.stack:top() then return end
    w:step()
  end

  -- Events and hooks go to the mod object main.lua runs on (FakeMod.emit/call).
  function E.bindMod(FakeMod, mod)
    emit = function(name, payload) FakeMod.emit(mod, name, payload) end
    callHook = function(name, vanilla, ...) return FakeMod.call(mod, name, vanilla, ...) end
  end

  return E
end

return FakeEngine2
