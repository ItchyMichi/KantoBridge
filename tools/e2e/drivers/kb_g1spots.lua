-- Kanto Bridge test driver: Blue's story spots for the real client
-- (docs/specs/STORY_EVENTS_PLAN.md §6), driven by tools/e2e/story_spots_g1.py
-- while the real dev client follows this game (in a fresh data home, so no
-- save of the user's is touched). It starts a new game without the intro
-- (src/core/Game.lua:173-200), warps below Pallet Town's first door, then
-- serves (paced to KB_PACE frames a second, default 60, as kb_serve.lua: a
-- driver turns the engine's own pacing off) and runs the commands it finds in
-- KB_SPOT_COMMANDS, one Lua table literal a line:
--
--   { id = 1, op = "go", map = "LORELEIS_ROOM", x = 5, y = 3, facing = "up",
--     flags = { EVENT_BEAT_LORELEIS_ROOM_TRAINER_0 = true }, every = false,
--     flash = false }
--
-- go: a hop to another map first (the story state goes back to the new
-- game's there, as kb_g1story.lua keeps it: save.flags, save.objectToggles,
-- the Flash light, every map's blocks back to the boot copy), then the
-- command's flags set (`every`: every flag the inventory names) and a warp
-- onto the map's cell (WorldAPI:warpTo, src/world/WorldAPI.lua:358-371;
-- without x and y the map's first warp's cell). Once the field has stood idle
-- for a second (a box answered B, a scene still holding it after 10 s
-- halted) Flash is lit when the command asks (save.flashLit and the map's
-- light, as the field move leaves them: OC:514-522) and it prints KB_SPOT id=
-- map= x= y= blocks= dark= flash= halted=. The client sees the warp and
-- follows.
--
-- Markers: KB_BOOTED pristine=, KB_SERVING map= x= y=, KB_SPOT ...,
-- KB_SPOT_FAIL id= reason=, KB_QUIT code=; KB_FAIL <reason>. Map symbols,
-- cells, ids, counts and booleans only.
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- with the Game (refs/gen1recomp/main.lua:755-759, :1136-1157). Paths cited
-- are refs/gen1recomp's (Gen1Recomp v0.3.85).

io.stdout:setvbuf("no")

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27243
local PACE = tonumber(os.getenv("KB_PACE") or "") or 60
local DATASET_MEMO = tonumber(os.getenv("KB_DATASET_MEMO") or "") or 0
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or 7200
local COMMANDS = os.getenv("KB_SPOT_COMMANDS")
local INVENTORY = os.getenv("KB_INVENTORY")
local TOWN = "PALLET_TOWN"
local HOPS = { "PALLET_TOWN", "ROUTE_1", "VIRIDIAN_CITY" }
local SETTLE_FRAMES = 600 -- then a scene still holding the field is halted
local ANSWER_FRAMES = 4

local floor = math.floor
local function word(v) return (tostring(v):gsub("[^%w_.:+%-]", "?")) end

local function fail(reason)
  print(("KB_FAIL %s"):format(reason))
  love.event.quit(1)
  while true do coroutine.yield() end
end

local function memoDatasetChecks(seconds)
  local ok, DatasetViews = pcall(require, "src.mods.DatasetViews")
  if not ok or type(DatasetViews) ~= "table" or type(DatasetViews._ready) ~= "function" then return false end
  local ready = DatasetViews._ready
  DatasetViews._ready = function(self, view)
    local now = love.timer.getTime()
    if type(view) == "table" and view.kbReadyAt and now - view.kbReadyAt < seconds and not view.invalid
        and not view.unavailable then
      return true
    end
    local passed = ready(self, view)
    if passed and type(view) == "table" then view.kbReadyAt = now end
    return passed
  end
  return true
end

local function overworld(game)
  local states = game and game.stack and game.stack.states or {}
  for i = #states, 1, -1 do
    local s = states[i]
    if type(s) == "table" and s.isOverworld then
      if s.map and s.player then return s end
      return nil
    end
  end
  return nil
end

local function topState(game)
  local stack = game and game.stack
  return stack and stack.top and stack:top() or nil
end

-- as kb_g1story.lua: the overworld on top, no warp, no script running or queued, no scripted move, the player still
local function fieldReady(game)
  local ow = overworld(game)
  if not ow or topState(game) ~= ow or ow.transitioning then return false end
  local p = ow.player
  if p.moving or p.inputLocked then return false end
  if ow.runner and ow.runner.isRunning and ow.runner:isRunning() then return false end
  if #(ow.scriptMoves or {}) > 0 or #(ow.pendingScripts or {}) > 0 or ow.emote then return false end
  return true
end

-- ---- the story state (kb_g1story.lua's)

local pristine = {}
local function capture(game)
  local n = 0
  for id, def in pairs(game.data and game.data.maps or {}) do
    if type(id) == "string" and type(def) == "table" and type(def.blocks) == "table" then
      local b = {}
      for i = 1, #def.blocks do b[i] = def.blocks[i] end
      pristine[id] = b
      n = n + 1
    end
  end
  return n
end

local function copy(t)
  local out = {}
  for k, v in pairs(type(t) == "table" and t or {}) do out[k] = type(v) == "table" and copy(v) or v end
  return out
end

local function snapshot(game)
  local save = game.save
  return { flags = copy(save.flags), toggles = copy(save.objectToggles), flashLit = save.flashLit }
end

local function restore(game, base)
  local save = game.save
  for k in pairs(save.flags) do save.flags[k] = nil end
  for k, v in pairs(base.flags) do save.flags[k] = v end
  save.objectToggles = copy(base.toggles)
  save.flashLit = base.flashLit
  for id, b in pairs(pristine) do
    local def = game.data.maps[id]
    local live = def and def.blocks
    if type(live) == "table" then
      for i = 1, #b do if live[i] ~= b[i] then live[i] = b[i] end end
    end
  end
end

local everyFlags
local function loadEvery()
  everyFlags = {}
  local f = INVENTORY and io.open(INVENTORY, "rb")
  if not f then return end
  local s = f:read("*a")
  f:close()
  local list = s:match('"flags":%[(.-)%]')
  for name in (list or ""):gmatch('"(EVENT_[A-Z0-9_]+)"') do
    if name ~= "EVENT_IN_SAFARI_ZONE" then everyFlags[#everyFlags + 1] = name end
  end
end

local function applyState(game, cmd)
  local save = game.save
  if cmd.every then
    if not everyFlags then loadEvery() end
    for _, name in ipairs(everyFlags) do save.flags[name] = true end
  end
  for name, on in pairs(type(cmd.flags) == "table" and cmd.flags or {}) do
    if type(name) == "string" then save.flags[name] = on and true or nil end
  end
end

local function changedBlocks(ow)
  local live = ow.map.def.blocks or {}
  local b = pristine[ow.map.id] or {}
  local n = 0
  for i = 1, #live do if b[i] ~= nil and live[i] ~= b[i] then n = n + 1 end end
  return n
end

local function arrivalCell(game, mapId)
  local def = game.data.maps[mapId]
  local warps = def and type(def.warps) == "table" and def.warps or {}
  if type(warps[1]) == "table" and tonumber(warps[1].x) then return tonumber(warps[1].x), tonumber(warps[1].y) end
  -- a map without warps (a route): its first walkable dry cell, as kb_g1story.lua lands
  local ts = def and game.data.tilesets and game.data.tilesets[def.tileset]
  local ok, m = false, nil
  if ts then ok, m = pcall(require("src.world.Map").new, def, ts) end
  if ok and m then
    for y = 0, (tonumber(def.height) or 1) * 2 - 1 do
      for x = 0, (tonumber(def.width) or 1) * 2 - 1 do
        local okW, walk = pcall(m.isWalkableCell, m, x, y)
        local okD, wet = pcall(m.isWaterCell, m, x, y)
        if okW and walk and not (okD and wet) then return x, y end
      end
    end
  end
  return nil
end

-- A box on top of the field is answered B a few frames after it comes up (kb_g1story.lua's answer).
local boxFrames = 0
local function answer(game)
  local top, ow = topState(game), overworld(game)
  if top == nil or top == ow or (type(top) == "table" and top.isBattleState) then
    boxFrames = 0
    return
  end
  boxFrames = boxFrames + 1
  if boxFrames >= ANSWER_FRAMES then
    boxFrames = 0
    game.input:sourcePress("b", "kb-g1spots")
    game.input:sourceRelease("b", "kb-g1spots")
  end
end

-- kb_g1story.lua's halt (test-only)
local function halt(game)
  local ow = overworld(game)
  local n = 0
  while ow and topState(game) ~= ow and topState(game) ~= nil and n < 8 do
    pcall(game.stack.pop, game.stack)
    n = n + 1
  end
  if not ow then return n > 0 end
  local halted = n > 0
  if #(ow.scriptMoves or {}) > 0 then ow.scriptMoves = {} halted = true end
  if #(ow.pendingScripts or {}) > 0 then ow.pendingScripts = {} halted = true end
  if ow.runner and ow.runner:isRunning() then
    ow.runner = require("src.script.ScriptRunner").new(game, ow)
    halted = true
  end
  if ow.emote then ow.emote = nil halted = true end
  if ow.player then ow.player.inputLocked = false end
  return halted
end

-- The commands written so far (one table literal a line), from line `from` on.
local function readCommands(from)
  local out = {}
  if not COMMANDS then return out, from end
  local f = io.open(COMMANDS, "r")
  if not f then return out, from end
  local n = 0
  for line in f:lines() do
    n = n + 1
    if n >= from and line:find("%S") then
      local chunk = load("return " .. line, "=command", "t", {})
      local ok, cmd = false, nil
      if chunk then ok, cmd = pcall(chunk) end
      out[#out + 1] = ok and type(cmd) == "table" and cmd or { op = "bad", line = n }
    end
  end
  f:close()
  return out, n + 1
end

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local start, baseline, nextFrame, world
  local queue, nextLine = {}, 1
  local cmd, frames, idle = nil, 0, 0
  local lastRead = 0
  local arrived = false
  local function warp(mapId, x, y, facing)
    arrived = false
    local ok, res = pcall(world.warpTo, world, mapId, x, y, facing or "down", { onDone = function() arrived = true end })
    return ok and res
  end
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    if phase == "ready" then
      if status and status.ready and game.data and game.data.maps and not overworld(game) then
        status.configure({ port = PORT })
        if DATASET_MEMO > 0 then memoDatasetChecks(DATASET_MEMO) end
        print(("KB_BOOTED pristine=%d"):format(capture(game)))
        game:startNewGame({ intro = false })
        phase = "house"
      end
    elseif phase == "house" then
      if fieldReady(game) then
        world = require("src.world.WorldAPI").new(game, "kb-g1spots-driver")
        local x, y = arrivalCell(game, TOWN)
        if not x then fail("no_town_warp") end
        start = { x = x, y = y + 1 }
        if not warp(TOWN, start.x, start.y, "down") then fail("warp_town") end
        frames = 0
        phase = "town"
      end
    elseif phase == "town" then
      local ow = overworld(game)
      frames = frames + 1
      if arrived and fieldReady(game) and ow.map.id == TOWN and ow.player.cellX == start.x
          and ow.player.cellY == start.y and frames > 30 then
        baseline = snapshot(game)
        print(("KB_SERVING map=%s x=%d y=%d"):format(TOWN, start.x, start.y))
        phase = "serve"
      end
    elseif phase == "serve" then
      local ow = overworld(game)
      local now = love.timer.getTime()
      answer(game)
      if not cmd and #queue == 0 and now - lastRead > 0.25 then
        lastRead = now
        queue, nextLine = readCommands(nextLine)
      end
      if not cmd and #queue > 0 then
        cmd = table.remove(queue, 1)
        frames, idle = 0, 0
        if cmd.op ~= "go" or type(cmd.map) ~= "string" or type(game.data.maps[cmd.map]) ~= "table" then
          print(("KB_SPOT_FAIL id=%s reason=bad_command"):format(word(cmd.id)))
          cmd = nil
        else
          local hop
          if cmd.nohop then
            -- the survey's screenshots (tools/e2e/map_shots.py): straight to the cell, no hop
            cmd.state, frames, idle = "go", 0, 0
          end
          for _, id in ipairs(cmd.nohop and {} or HOPS) do
            if not hop and id ~= cmd.map and not (ow and ow.map.id == id) then hop = id end
          end
          local hx, hy
          if hop then hx, hy = arrivalCell(game, hop) end
          if cmd.nohop then
            -- set above
          elseif hx and warp(hop, hx, hy, "down") then
            cmd.state = "hop"
          else
            print(("KB_SPOT_FAIL id=%s reason=hop"):format(word(cmd.id)))
            cmd = nil
          end
        end
      elseif cmd and cmd.state == "hop" then
        frames = frames + 1
        if frames == SETTLE_FRAMES then halt(game) end
        idle = (arrived and fieldReady(game)) and idle + 1 or 0
        if idle >= 30 or frames > SETTLE_FRAMES + 60 then
          cmd.state, frames, idle = "go", 0, 0
        end
      elseif cmd and cmd.state == "go" then
        restore(game, baseline)
        applyState(game, cmd)
        local x, y = tonumber(cmd.x), tonumber(cmd.y)
        if not (x and y) then x, y = arrivalCell(game, cmd.map) end
        if not (x and y and warp(cmd.map, x, y, cmd.facing)) then
          print(("KB_SPOT_FAIL id=%s reason=warp"):format(word(cmd.id)))
          cmd = nil
        else
          cmd.state, frames, idle = "settle", 0, 0
        end
      elseif cmd and cmd.state == "settle" then
        frames = frames + 1
        if frames == SETTLE_FRAMES then cmd.halted = halt(game) end
        idle = (arrived and fieldReady(game)) and idle + 1 or 0
        if idle >= 60 or frames >= SETTLE_FRAMES + 120 then
          if cmd.flash and ow then
            game.save.flashLit = true
            pcall(ow.setDark, ow, false)
          end
          print(("KB_SPOT id=%s map=%s x=%s y=%s blocks=%d dark=%s flash=%s halted=%s"):format(
            word(cmd.id), word(ow and ow.map.id), word(ow and ow.player.cellX), word(ow and ow.player.cellY),
            ow and changedBlocks(ow) or -1, tostring(ow and ow.dark == true), tostring(game.save.flashLit == true),
            tostring(cmd.halted or false)))
          cmd = nil
        end
      end
      if status.quitRequested then
        print(("KB_QUIT code=%s"):format(word(status.quitCode or 0)))
        return
      end
    end
    if PACE > 0 and phase == "serve" then
      local now = love.timer.getTime()
      nextFrame = math.max((nextFrame or now) + 1 / PACE, now - 0.1)
      if nextFrame > now then love.timer.sleep(nextFrame - now) end
    end
    if love.timer.getTime() - started > SECONDS_LIMIT then
      fail("timeout phase=" .. phase)
    end
    game = coroutine.yield() or game
  end
end
