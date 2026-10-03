-- A small simulated overworld for the fake engine, so gen1-bridge's step
-- driver can be tested against real step timing. Written for Kanto Bridge
-- from the engine behaviour documented in refs/_research/P1_gen1_movement.md
-- (sections 0-3, 6, 7) and lib/kb_engine.lua's citations; the code is our
-- own and every map is made up.
--
--   FakeWorld.attach(E)        adds the simulation to a FakeEngine
--   E.bindMod(FakeMod, mod)    events and hooks go to that mod object
--   E.simulate(spec)           installs a made-up world and turns ticks on
--   E.tick()                   one logic step after input.step: Input:step,
--                              then the top state's update
--
-- spec = {
--   maps = { ID = { rows = { "#####", "#...#", ... }, tileset = "X",
--                   warps = { { x, y, to = "OTHER", tx, ty } },
--                   connections = { north = { map = "OTHER", offset = 0 } },
--                   npcs = { { id, x, y, sprite, pushable } } } },
--   start = { map, x, y, facing },
--   fade = 32,                 -- warp fade frames (doors are 32 in the engine)
-- }
-- Cells:  .  floor          #  wall            D  door tile (warp tile too)
--         S  stairs (warp tile)               M  exit mat (no door/warp tile)
--         P  warp pad       H  hole            w  water
--         g  tall grass (walkable)
--         v ^ < >  a ledge hopped in that direction
-- A map spec may also carry region, ghostBattles and indoor (copied into its
-- def, for the M6 encounter rules) and npcs' facing, item, pokemon, ...
--
-- M8 (fake_field.lua): a map spec with `blocks` (block ids, row-major),
-- `width` (in blocks) and `blockset` (block id -> { "ab", "cd" }, the chars
-- of its 2x2 cells) draws its cells from the LIVE blocks in def.blocks, so a
-- Map:setBlock (Cut, a door, a script swap) changes what is walkable. More
-- cells: T a cuttable tree (solid), = a closed gate (solid). `objects`
-- become the def's object records (index, name, hidden, item, pokemon) for
-- the effective-visibility tests; `tileset`, `slope` (the Cycling Road) and
-- `dark` (field.darkMaps) are the map's own. Every def is registered in
-- E.data.maps (the engine's records are the live map's def, Map.lua:112-114)
-- and a pristine copy serves the fake dataset view (E.simPristine).
--
-- What one tick does, in the engine's order (OverworldState:update): the
-- top state updates (a warp fade counts down and then switches the map);
-- on the overworld: departure phases, the dust animation, an emote's hold,
-- NPC steps, scripted moves (two phases), the `scripted` gate, handleInput
-- (directions by isDown in the order up, down, left, right: edge exits and
-- connections, ledge hops, boulder pushes, then tryMove with its 4-frame
-- turn and the movement.collision hook; a blocked step on a warp cell
-- facing the edge warps), hopLand, Player:update (landing), and
-- onStepComplete for non-scripted steps (warps on arrival).

local FakeWorld = {}

local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
local DIR_ORDER = { "up", "down", "left", "right" }
local COMPASS = { up = "north", down = "south", left = "west", right = "east" }
local WALK = { ["."] = true, D = true, S = true, M = true, P = true, H = true, g = true }
local LEDGES = { v = "down", ["^"] = "up", ["<"] = "left", [">"] = "right" }

-- ------------------------------------------------------------ input

-- Sources per button, as the engine's Input keeps them: a press sets the
-- held state at once and queues an edge; Input:step turns queued edges into
-- one step of wasPressed; reset wipes everything (a focus change).
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
  function input:reset()
    self.state, self.sources, self.pressQueue, self.pressed = {}, {}, {}, {}
  end
  function input:held()
    local out = {}
    for _, d in ipairs({ "up", "down", "left", "right", "a", "b", "start", "select" }) do
      if self.state[d] then out[#out + 1] = d end
    end
    return table.concat(out, ",")
  end
  return input
end

-- ------------------------------------------------------------ maps

local SimMap = {}
SimMap.__index = SimMap

local function copyList(list)
  local out = {}
  for i, v in ipairs(list or {}) do
    if type(v) == "table" then
      local c = {}
      for k, x in pairs(v) do c[k] = x end
      out[i] = c
    else
      out[i] = v
    end
  end
  return out
end

local function newMap(id, spec)
  local rows = spec.rows
  local m = setmetatable({
    id = id, rows = rows, warpAt = {},
    def = { id = id, label = id, tileset = spec.tileset or "TEST", connections = spec.connections or {},
            region = spec.region, ghostBattles = spec.ghostBattles, indoor = spec.indoor,
            objects = copyList(spec.objects), warps = {}, signs = {} },
    spec = spec,
    renderer = { rebuild = function() end },
  }, SimMap)
  if spec.blocks then
    local w = spec.width
    m.def.width, m.def.height = w, #spec.blocks / w
    m.def.blocks = copyList(spec.blocks)
    m.def.borderBlock = spec.border or spec.blocks[1]
    m.blockset = spec.blockset
    m.widthCells, m.heightCells = w * 2, m.def.height * 2
  else
    m.widthCells, m.heightCells = #rows[1], #rows
  end
  for i, w in ipairs(spec.warps or {}) do
    m.warpAt[w.y * m.widthCells + w.x] = { index = i, def = w }
  end
  return m
end

function SimMap:inBounds(x, y) return x >= 0 and y >= 0 and x < self.widthCells and y < self.heightCells end
function SimMap:char(x, y)
  if not self:inBounds(x, y) then return "#" end
  if self.blockset then
    local d = self.def
    local block = d.blocks[math.floor(y / 2) * d.width + math.floor(x / 2) + 1]
    local pattern = self.blockset[block] or { "##", "##" }
    return pattern[y % 2 + 1]:sub(x % 2 + 1, x % 2 + 1)
  end
  return self.rows[y + 1]:sub(x + 1, x + 1)
end
-- Map:blockAt / Map:setBlock (src/world/Map.lua:193-198, :244-249): the
-- record's own array; off the map, the border block / nothing
function SimMap:blockAt(bx, by)
  local d = self.def
  if not d.blocks then return nil end
  if bx < 0 or by < 0 or bx >= d.width or by >= d.height then return d.borderBlock end
  return d.blocks[by * d.width + bx + 1]
end
function SimMap:setBlock(bx, by, block)
  local d = self.def
  if not d.blocks or bx < 0 or by < 0 or bx >= d.width or by >= d.height then return end
  d.blocks[by * d.width + bx + 1] = block
end
function SimMap:isWalkableCell(x, y) return WALK[self:char(x, y)] == true end
function SimMap:isWaterCell(x, y) return self:char(x, y) == "w" end
function SimMap:isGrassCell(x, y) return self:char(x, y) == "g" end
function SimMap:isDoorTileCell(x, y) return self:char(x, y) == "D" end
function SimMap:isWarpTileCell(x, y)
  local c = self:char(x, y)
  return c == "D" or c == "S" or c == "P" or c == "H"
end
function SimMap:warpPadOrHoleAt(x, y)
  local c = self:char(x, y)
  if c == "P" then return "pad" elseif c == "H" then return "hole" end
  return nil
end
function SimMap:warpAtCell(x, y)
  if not self:inBounds(x, y) then return nil end
  return self.warpAt[y * self.widthCells + x]
end
function SimMap:ledgeAt(x, y) return LEDGES[self:char(x, y)] end
-- C: a counter tile, which the A press reaches across (OC:2542-2547)
function SimMap:isCounterCell(x, y) return self:char(x, y) == "C" end

-- ------------------------------------------------------------ the simulation

function FakeWorld.attach(E)
  E.runtime = {
    emit = function() end,
    call = function(_, vanilla, ...) return vanilla(...) end,
    wants = function() return false end,
  }
  E.simOn = false
  E.game.input = newInput()
  E.trace = {} -- per tick: { tick, moving, x, y, held }
  E.ticks = 0
  E.stepStarts = {} -- tick numbers at which the player's steps started (handleInput/scripts)

  function E.bindMod(FakeMod, mod)
    E.runtime = {
      emit = function(name, payload) FakeMod.emit(mod, name, payload) end,
      call = function(name, vanilla, ...) return FakeMod.call(mod, name, vanilla, ...) end,
      -- Runtime.wantsHook (src/mods/Runtime.lua:75-78)
      wants = function(name)
        local chain = mod._chains[name]
        return chain ~= nil and #chain > 0
      end,
    }
  end

  local sim = { maps = {}, fade = 32 }
  E.sim = sim

  local function ow() return E.ow end
  local function player() return E.ow.player end

  local function emit(name, payload) E.runtime.emit(name, payload) end

  local function npcsFor(mapId)
    local out = {}
    for _, n in ipairs(sim.maps[mapId].spec.npcs or {}) do
      out[#out + 1] = {
        id = n.id or (mapId .. "_obj_" .. (#out + 1)),
        def = { index = #out + 1, name = n.name, sprite = n.sprite or "SPRITE_TEST", pushable = n.pushable,
                item = n.item, pokemon = n.pokemon, level = n.level, trainerClass = n.trainerClass,
                trainerParty = n.trainerParty, nurse = n.nurse, mart = n.mart },
        cellX = n.x, cellY = n.y, facing = n.facing or "down", moving = false, progress = 0,
        stepFrames = n.stepFrames,
      }
    end
    return out
  end

  local function stepLength(dir)
    local p = player()
    local onBike = E.save.onBike and not p.ledgeHop
    local frames = onBike and 8 or 16
    if onBike and p.slopeMap and (dir or p.facing) ~= "down" then frames = 16 end
    -- a spinner's slide (Player.lua:131-133): 16 / 2 * 6 = 48
    if p.spinning and not p.spinFrames then frames = frames / 2 * 6 end
    return frames
  end
  E.stepLength = stepLength

  local function occupied(x, y, ignore)
    for _, e in ipairs(E.ow.entities or {}) do
      if e ~= ignore and ((e.cellX == x and e.cellY == y) or (e.targetX == x and e.targetY == y)) then
        return e
      end
    end
    return nil
  end

  -- Collision.canMove: bounds, tile, entity, then the movement.collision hook
  local function canMove(mover, dir)
    local m, d = E.ow.map, DELTA[dir]
    local tx, ty = mover.cellX + d[1], mover.cellY + d[2]
    local allowed, why = true, nil
    if not m:inBounds(tx, ty) then
      allowed, why = false, "bounds"
    elseif not m:isWalkableCell(tx, ty) and not (mover.surfing and m:isWaterCell(tx, ty)) then
      allowed, why = false, "tile"
    elseif occupied(tx, ty, mover) then
      allowed, why = false, "entity"
    end
    local ctx = { map = m, mover = mover, dir = dir, fromX = mover.cellX, fromY = mover.cellY,
                  toX = tx, toY = ty, reason = why }
    allowed = E.runtime.call("movement.collision", function(a) return a end, allowed, ctx)
    if allowed then return true end
    return false, ctx.reason
  end

  local function scriptMove(entity, dir, tiles, onDone)
    local list = E.ow.scriptMoves
    list[#list + 1] = { entity = entity, dir = dir, remaining = tiles, onDone = onDone }
  end
  E.scriptMove = scriptMove
  -- OverworldState:marchInPlace: one walk-in-place beat (OC:5667-5671)
  function E.marchInPlace(entity, onDone)
    local list = E.ow.scriptMoves
    list[#list + 1] = { entity = entity, inPlace = true, remaining = 1, onDone = onDone }
  end

  local function setMap(mapId, x, y, facing, opts)
    opts = opts or {}
    local w, p = ow(), player()
    local from = w.map and w.map.id
    w.map = sim.maps[mapId]
    w.strengthActive = false
    -- M8 (fake_field.lua): Cut regrowth, the card-key stamps, darkness, the
    -- slope flag, in setMap's order (OC:479-515)
    if E.onMapLoad then E.onMapLoad(mapId, opts) end
    w.npcs = npcsFor(mapId)
    p.cellX, p.cellY = x, y
    p.facing = facing or p.facing
    p.moving, p.targetX, p.targetY = false, nil, nil
    w.entities = { p }
    for _, n in ipairs(w.npcs) do w.entities[#w.entities + 1] = n end
    emit("map.entered", {
      mapId = mapId, map = w.map, fromMapId = from,
      via = opts.via or (opts.seamless and "connection") or (from and "warp" or "boot"),
    })
    -- the map's onEnter hook (OC:695-707; fake_field.lua's E.mapScripts)
    if E.onMapEnter then E.onMapEnter(mapId, from) end
  end

  -- a warp fade on top of the overworld; its last frame switches the map
  local function fade(frames, midpoint)
    local t = { t = 0, frames = frames, isFade = true }
    function t.update(self)
      self.t = self.t + 1
      if self.t >= self.frames then
        E.pop()
        midpoint()
      end
    end
    return t
  end

  local function startWarpTo(mapId, x, y, facing, opts)
    local w = ow()
    w.transitioning = true
    local doorWarp = w.doorWarp
    w.doorWarp = nil
    E.push(fade(opts and opts.fade or sim.fade, function()
      setMap(mapId, x, y, facing or "down", opts)
      local p = player()
      w.warpEntryCell = { x = x, y = y }
      if doorWarp and w.map:isDoorTileCell(x, y) then
        -- PlayerStepOutFromDoor: one scripted step down off a door tile
        if canMove(p, "down") then
          w.warpEntryCell = nil
          scriptMove(p, "down", 1)
        else
          p.facing = "down"
        end
      end
      p.inputLocked = false
      w.transitioning = false
    end))
  end
  E.startWarpTo = startWarpTo
  E.setMapSim = setMap

  local function takeWarp(w)
    local o, p = ow(), player()
    local d = w.def
    emit("player.warped", { fromMap = o.map.id, toMap = d.to, x = d.tx, y = d.ty, warp = d })
    local pad = o.map:warpPadOrHoleAt(p.cellX, p.cellY)
    if pad == "pad" then
      p.inputLocked, p.spinning = true, true
      o.teleportOut = { frames = 15, go = function() startWarpTo(d.to, d.tx, d.ty, p.facing) end }
      return
    elseif pad == "hole" then
      p.inputLocked = true
      o.holeFall = { frames = 2, go = function() startWarpTo(d.to, d.tx, d.ty, p.facing) end }
      return
    end
    o.doorWarp = true
    startWarpTo(d.to, d.tx, d.ty, p.facing)
  end

  local function facingEdge(x, y, dir)
    local m = ow().map
    return (dir == "up" and y == 0) or (dir == "down" and y == m.heightCells - 1)
      or (dir == "left" and x == 0) or (dir == "right" and x == m.widthCells - 1)
  end

  local function crossConnection(dir, conn)
    local dest = sim.maps[conn.map]
    local p = player()
    local x, y
    if dir == "up" then x, y = p.cellX - conn.offset * 2, dest.heightCells - 1
    elseif dir == "down" then x, y = p.cellX - conn.offset * 2, 0
    elseif dir == "left" then x, y = dest.widthCells - 1, p.cellY - conn.offset * 2
    else x, y = 0, p.cellY - conn.offset * 2 end
    if not dest:isWalkableCell(x, y) then return false end
    setMap(conn.map, x, y, p.facing, { seamless = true })
    local d = DELTA[dir]
    p.cellX, p.cellY = x - d[1], y - d[2]
    p.facing = dir
    p.targetX, p.targetY = x, y
    p.moving, p.progress = true, 0
    p.stepFramesCur = stepLength(dir)
    E.stepStarts[#E.stepStarts + 1] = E.ticks
    return true
  end

  local function checkEdgeExit(dir)
    local w, p = ow(), player()
    local d = DELTA[dir]
    if w.map:inBounds(p.cellX + d[1], p.cellY + d[2]) then return false end
    local wp = w.map:warpAtCell(p.cellX, p.cellY)
    if wp then
      if not w.standingOnWarp then return false end
      takeWarp(wp)
      return true
    end
    local conn = w.map.def.connections[COMPASS[dir]]
    if conn then return crossConnection(dir, conn) end
    return false
  end

  local function finishHop()
    player().ledgeHop = nil
    ow().hopLand = 3
  end

  local function checkLedgeHop(dir)
    local w, p = ow(), player()
    local d = DELTA[dir]
    local fx, fy = p.cellX + d[1], p.cellY + d[2]
    if not w.map:inBounds(fx, fy) or w.map:ledgeAt(fx, fy) ~= dir then return false end
    local lx, ly = fx + d[1], fy + d[2]
    if not w.map:inBounds(lx, ly) then
      -- the landing is on the connected map: hop onto the ledge cell, then
      -- the seam step crosses (checkEdgeExit from the hop's onDone)
      p.ledgeHop = true
      local hop = stepLength(dir) * 2
      p.hopFrames, p.hopTotal = hop, hop
      scriptMove(p, dir, 1, function()
        checkEdgeExit(dir)
        finishHop()
      end)
      return true
    end
    if occupied(lx, ly, p) or not w.map:isWalkableCell(lx, ly) then return false end
    p.ledgeHop = true -- before stepLength: no bike speed-up mid-hop
    local hop = stepLength(dir) * 2
    p.hopFrames, p.hopTotal = hop, hop
    scriptMove(p, dir, 2, finishHop)
    return true
  end

  local function checkBoulderPush(dir)
    local w, p = ow(), player()
    local d = DELTA[dir]
    local fx, fy = p.cellX + d[1], p.cellY + d[2]
    local npc
    for _, n in ipairs(w.npcs) do
      if n.cellX == fx and n.cellY == fy and n.def.pushable then npc = n end
    end
    if not npc or npc.moving then w.boulderTried = nil return false end
    if not w.strengthActive then return false end
    if w.boulderTried ~= npc then
      w.boulderTried = npc -- the first try only arms the push
      return false
    end
    local bx, by = fx + d[1], fy + d[2]
    if not w.map:isWalkableCell(bx, by) or occupied(bx, by, npc) then
      w.boulderTried = nil
      return false
    end
    scriptMove(npc, dir, 1, function()
      w.boulderTried = nil
      w.dustAnim = { frames = 24, boulder = true }
      emit("world.boulder_moved", { mapId = w.map.id, npcId = npc.id, x = npc.cellX, y = npc.cellY })
    end)
    return true
  end

  local function tryMove(dir)
    local p = player()
    if p.moving or p.inputLocked then return nil end
    if p.facing ~= dir then
      p.facing = dir
      p.bumpFrames = nil
      if p.turnArmed then
        p.turnArmed = false
        p.turnTimer = 4
        return "turned"
      end
    end
    if p.turnTimer > 0 then return nil end
    local ok, why = canMove(p, dir)
    if not ok then
      p.bumpFrames = 16
      return "blocked", why
    end
    local d = DELTA[dir]
    p.targetX, p.targetY = p.cellX + d[1], p.cellY + d[2]
    p.moving, p.progress = true, 0
    p.stepFramesCur = stepLength(dir)
    E.stepStarts[#E.stepStarts + 1] = E.ticks
    return "moved"
  end

  local function dirHeld()
    local input = E.game.input
    for _, d in ipairs(DIR_ORDER) do
      if input:isDown(d) then return true end
    end
    return false
  end

  local function handleInput()
    local w, p, input = ow(), player(), E.game.input
    if p.moving then return end
    -- A and START on the standing poll (fake_ui.lua: interact, the start menu)
    if E.overworldButtons and E.overworldButtons(input) then return end
    for _, dir in ipairs(DIR_ORDER) do
      if input:isDown(dir) then
        if p.facing == dir or (not p.turnArmed and p.turnTimer <= 0) then
          if p.facing ~= dir then
            p.facing = dir
            p.bumpFrames = nil
          end
          if checkEdgeExit(dir) then return end
          if checkLedgeHop(dir) then return end
          if checkBoulderPush(dir) then return end
        end
        local result = tryMove(dir)
        E.lastTry = result
        -- a step from the water onto land ends the surf (stopSurfingOntoLand,
        -- OC:1739, :3283-3290)
        if result == "moved" and p.surfing and w.map:isWalkableCell(p.targetX, p.targetY) then
          p.surfing = false
        end
        if result == "blocked" and w.standingOnWarp then
          local wp = w.map:warpAtCell(p.cellX, p.cellY)
          if wp and facingEdge(p.cellX, p.cellY, dir) then takeWarp(wp) end
        end
        return
      end
    end
    p.turnArmed = true
    -- the Cycling Road pull: no d-pad, neither A nor B held
    local braking = input:isDown("a") or input:isDown("b")
    if E.save.onBike and p.slopeMap and not braking then
      p.facing = "down"
      if checkEdgeExit("down") then return end
      tryMove("down")
    end
  end

  local function playerUpdate()
    local p = player()
    if p.hopFrames and p.hopFrames > 0 then p.hopFrames = p.hopFrames - 1 end
    if p.turnTimer > 0 then p.turnTimer = p.turnTimer - 1 end
    if not p.moving and p.bumpFrames and p.bumpFrames > 0 then p.bumpFrames = p.bumpFrames - 1 end
    if not p.moving then return false end
    p.progress = p.progress + 1
    if p.progress >= (p.stepFramesCur or 16) then
      p.cellX, p.cellY = p.targetX, p.targetY
      p.targetX, p.targetY = nil, nil
      p.moving = false
      return true
    end
    return false
  end

  local function npcUpdate(n)
    if not n.moving then return end
    n.progress = n.progress + 1
    if n.marching then
      -- a walk-in-place beat: no translation (NPC.lua:116-124)
      if n.progress >= (n.stepFrames or 32) then n.progress, n.moving, n.marching = 0, false, false end
      return
    end
    if n.progress >= (n.stepFrames or 32) then
      n.cellX, n.cellY = n.targetX, n.targetY
      n.targetX, n.targetY = nil, nil
      n.moving = false
    end
  end

  local function updateScriptMoves()
    local w = ow()
    local i = 1
    while i <= #w.scriptMoves do
      local mv = w.scriptMoves[i]
      if not mv.entity.moving and mv.remaining <= 0 then
        table.remove(w.scriptMoves, i)
        if mv.onDone then mv.onDone() end
      else
        i = i + 1
      end
    end
    for _, mv in ipairs(w.scriptMoves) do
      local e = mv.entity
      if not e.moving and mv.remaining > 0 and mv.inPlace then
        e.moving, e.marching, e.progress = true, true, 0
        mv.remaining = mv.remaining - 1
      elseif not e.moving and mv.remaining > 0 then
        e.facing = mv.dir
        local d = DELTA[mv.dir]
        e.targetX, e.targetY = e.cellX + d[1], e.cellY + d[2]
        if e == w.player then
          e.stepFramesCur = stepLength(mv.dir)
          E.stepStarts[#E.stepStarts + 1] = E.ticks
        end
        e.moving, e.progress = true, 0
        mv.remaining = mv.remaining - 1
      end
    end
  end

  local function refreshStandingOnWarp()
    local w, p = ow(), player()
    w.standingOnWarp = w.map:warpAtCell(p.cellX, p.cellY) ~= nil
      and not (w.map:isWarpTileCell(p.cellX, p.cellY) and not w.map:isDoorTileCell(p.cellX, p.cellY))
  end

  local function onStepComplete()
    local w, p = ow(), player()
    E.stepsCompleted = (E.stepsCompleted or 0) + 1
    emit("world.stepped", { mapId = w.map.id, x = p.cellX, y = p.cellY })
    if p.surfing and w.map:isWalkableCell(p.cellX, p.cellY) then p.surfing = false end
    -- M8 (fake_field.lua): a map's onStep, spinners and Seafoam currents
    -- (OC:4689-4710)
    if E.stepHooks and E.stepHooks() then return end
    w.boulderTried = nil
    local entry = w.warpEntryCell
    if entry and (p.cellX ~= entry.x or p.cellY ~= entry.y) then
      w.warpEntryCell = nil
      entry = nil
    end
    refreshStandingOnWarp()
    if entry then return end
    local wp = w.map:warpAtCell(p.cellX, p.cellY)
    if wp and w.map:isWarpTileCell(p.cellX, p.cellY) then
      takeWarp(wp)
      return
    elseif wp and dirHeld() and facingEdge(p.cellX, p.cellY, p.facing) then
      takeWarp(wp)
      return
    end
    -- the rest of onStepComplete (M6: the wild-encounter roll, fake_wild.lua)
    if E.afterStep then E.afterStep() end
  end
  E.onStepComplete = onStepComplete

  local function owUpdate()
    local w, p = ow(), player()
    -- the script runner's frame waits and condition polls (OC:1297;
    -- ScriptRunner:update, src/script/ScriptRunner.lua:290-308)
    if w.runner and type(w.runner.update) == "function" then w.runner:update() end
    p.onBike = E.save.onBike
    if w.dustAnim then
      w.dustAnim.frames = w.dustAnim.frames - 1
      if w.dustAnim.frames <= 0 then w.dustAnim = nil end
    end
    -- the Cut tree's split (startCutTreeAnim, OC:3355-3362; M8)
    if w.cutAnim then
      w.cutAnim.frames = w.cutAnim.frames - 1
      if w.cutAnim.frames <= 0 then w.cutAnim = nil end
    end
    if w.flyFade then
      w.flyFade = w.flyFade - 1
      if w.flyFade <= 0 then
        w.flyFade = nil
        local dest = w.flyDest
        w.flyDest = nil
        startWarpTo(dest.map, dest.x, dest.y, "down", { via = "fly" })
      end
      return
    end
    if w.teleportOut then
      w.teleportOut.frames = w.teleportOut.frames - 1
      if w.teleportOut.frames <= 0 then
        local go = w.teleportOut.go
        w.teleportOut = nil
        p.spinning = false
        go()
        return
      end
    end
    if w.holeFall then
      w.holeFall.frames = w.holeFall.frames - 1
      if w.holeFall.frames <= 0 then
        local go = w.holeFall.go
        w.holeFall = nil
        go()
        return
      end
    end
    if w.emote then
      w.emote.frames = w.emote.frames - 1
      if w.emote.frames <= 0 then
        -- the hold's continuation runs when it ends (OC:1510-1527)
        local done = w.emote.onDone
        w.emote = nil
        if done then done() end
      end
      playerUpdate()
      return
    end
    -- the Pokemon Center's machine holds the world (OC:1348-1360; M7,
    -- fake_center.lua)
    if w.healAnim then
      w.healAnim.frames = w.healAnim.frames - 1
      if w.healAnim.frames <= 0 then
        local done = w.healAnim.onDone
        w.healAnim = nil
        if done then done() end
      end
      return
    end
    for _, n in ipairs(w.npcs) do npcUpdate(n) end
    updateScriptMoves()
    local scripted = w.runner:isRunning() or #w.scriptMoves > 0 or (w.hopLand or 0) > 0
      or w.engaging or w.emote or w.teleportOut or (w.dustAnim and w.dustAnim.boulder) or w.cutAnim
    -- trainers' sight before the player's input (OC:1553-1566; M7,
    -- fake_center.lua's E.sightCheck)
    if not scripted and not w.transitioning and E.sightCheck and E.sightCheck() then scripted = true end
    if not scripted and not w.transitioning and E.stack:top() == w then handleInput() end
    if (w.hopLand or 0) > 0 then w.hopLand = w.hopLand - 1 end
    local stepped = playerUpdate()
    local entry = w.warpEntryCell
    if entry and (p.cellX ~= entry.x or p.cellY ~= entry.y) then w.warpEntryCell = nil end
    if stepped and not scripted then onStepComplete() end
  end

  -- One logic step after the input.step hooks (Game:step: Input:step, then
  -- the top state's update).
  function E.tick()
    if not E.simOn then return end
    E.ticks = E.ticks + 1
    E.game.input:step()
    local top = E.stack:top()
    if top ~= nil then
      if top == E.ow then
        owUpdate()
      elseif type(top.update) == "function" then
        top:update()
      end
    end
    local p = E.ow.player
    E.trace[#E.trace + 1] = { E.ticks, p.moving == true, p.cellX, p.cellY, E.game.input:held() }
  end

  local function deepCopy(v)
    if type(v) ~= "table" then return v end
    local out = {}
    for k, x in pairs(v) do out[k] = deepCopy(x) end
    return out
  end

  function E.simulate(spec)
    sim.maps, sim.fade = {}, spec.fade or 32
    E.data.maps = E.data.maps or {}
    E.simPristine = {}
    for id, m in pairs(spec.maps) do
      sim.maps[id] = newMap(id, m)
      E.data.maps[id] = sim.maps[id].def
      E.simPristine[id] = deepCopy(sim.maps[id].def)
    end
    local s = spec.start
    E.ow.scriptMoves, E.ow.hopLand = {}, 0
    E.ow.player = { cellX = s.x, cellY = s.y, facing = s.facing or "down", moving = false, progress = 0,
                    turnTimer = 0, turnArmed = true, inputLocked = false }
    -- Player:facingCell (src/world/Player.lua:262-264)
    function E.ow.player.facingCell(self)
      local d = DELTA[self.facing]
      return self.cellX + d[1], self.cellY + d[2]
    end
    E.ow.map = nil
    E.stack.states = { E.ow }
    setMap(s.map, s.x, s.y, s.facing or "down", { via = "boot" })
    E.simOn = true
  end

  -- test controls
  function E.resetInput() E.game.input:reset() end -- a focus change's Input:reset
  -- the player stands idle on the overworld with nothing in flight or held
  function E.settled()
    local w, p, s = E.ow, E.ow.player, E.game.input.state
    return E.stack:top() == w and not p.moving and #w.scriptMoves == 0 and not w.transitioning
      and (w.hopLand or 0) == 0 and not w.dustAnim and not w.cutAnim and not w.emote and not w.teleportOut
      and not w.flyFade and not w.holeFall and not p.inputLocked
      and not (s.up or s.down or s.left or s.right)
  end
  function E.flyTo(map, x, y)
    ow().flyFade = 10
    ow().flyDest = { map = map, x = x, y = y }
    player().inputLocked = true
  end
  function E.teleportTo(map, x, y)
    player().inputLocked, player().spinning = true, true
    ow().teleportOut = { frames = 20, go = function() startWarpTo(map, x, y, "down") end }
  end
  function E.blackOutTo(map, x, y)
    emit("world.blacked_out", { landing = { map = map, x = x, y = y } })
    startWarpTo(map, x, y, "down")
  end
  function E.scriptWarp(map, x, y, facing) startWarpTo(map, x, y, facing or "down") end
end

return FakeWorld
