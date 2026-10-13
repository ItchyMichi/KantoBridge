-- Kanto Bridge test driver (the caves in 3D plan's H6 pilot video,
-- docs/specs/CAVES_3D_PLAN.md): moves gen1-bridge to the test port, starts a
-- new game without the intro (src/core/Game.lua:173-200), gives the player a
-- level 100 starter and REPEL steps (as kb_field.lua does: no wild battle
-- interrupts a shot), marks every map trainer of KB_PILOT_MAPS beaten (as
-- kb_endgame.lua does, so nobody sights the player), and warps
-- through the supported mod.world facade (WorldAPI:warpTo,
-- src/world/WorldAPI.lua:358-371) to a plain cell beside the first warp of
-- KB_PILOT_FIRST (default: the first of KB_PILOT_MAPS).
--
-- Then it serves the dev client's autopilot (scene "pilot"), which plans its
-- shots from the world index and asks for the hidden player to be put on a
-- cell through KB_PILOT_DIR/request.txt, one line:
--   <seq> <kind> <map> <facing> <x>,<y> [<x>,<y> ...]
-- kind "walk": every cell plain (in bounds, walkable, no water, no grass, no
-- object, no warp), the hidden player put on the first; kind "ladder": the
-- first cell plain and the second a warp (the ladder's top). It answers in
-- KB_PILOT_DIR/answer.txt: "<seq> ok" once the hidden player stands there, or
-- "<seq> refused <reason> [<x>,<y>]". On each arrival it sets save.flashLit,
-- as Flash does, so a dark cave (Rock Tunnel) is lit for the camera (the
-- engine drops it again on any map that isn't dark, OC:514-522).
--
-- It prints KB_PILOT_READY map= x= y= facing= port= at the first stop,
-- KB_PILOT_REQUEST n= kind= map= cells= verdict= for each request,
-- KB_PILOT_ARRIVED n= map= x= y=, KB_MAP_ENTERED map= via= on every
-- map.entered, and KB_END at the end; it serves until gen1-bridge reports a
-- debug.quit (exit 0) or the time limit passes (exit 1).
--
-- KB_PACE and KB_DATASET_MEMO as in kb_wild.lua (the pod: a real client plays
-- against it, and the network disk would make the first export slow).
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame.
-- Data safety: prints and writes only markers, map symbols, cells and counts.

io.stdout:setvbuf("no")

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27209
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or 1200
local MAPS = os.getenv("KB_PILOT_MAPS") or "MT_MOON_1F,MT_MOON_B1F,MT_MOON_B2F"
local FIRST = os.getenv("KB_PILOT_FIRST")
local DIR = os.getenv("KB_PILOT_DIR")
local STARTER_DEX = 4
local PACE = tonumber(os.getenv("KB_PACE") or "") or 0
local DATASET_MEMO = tonumber(os.getenv("KB_DATASET_MEMO") or "") or 0
local POLL_FRAMES = 15
local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
local DIRS = { "up", "down", "left", "right" }
local AWAY = { up = "down", down = "up", left = "right", right = "left" }

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

local function word(v)
  local s = tostring(v)
  return (s:gsub("[^%w_.:+%-]", "?"))
end

local function fail(reason)
  print("KB_FAIL " .. reason)
  love.event.quit(1)
  while true do coroutine.yield() end
end

local function overworld(game)
  local states = game.stack and game.stack.states or {}
  for i = #states, 1, -1 do
    local s = states[i]
    if type(s) == "table" and s.isOverworld then return s end
  end
  return nil
end

local function mapQuery(game, mapId)
  local def = game.data.maps[mapId]
  local ts = def and game.data.tilesets[def.tileset]
  if not (def and ts) then return nil end
  local ok, m = pcall(require("src.world.Map").new, def, ts)
  if not ok then return nil end
  return m, def
end

-- the cells the map's objects start on
local function objectCells(def)
  local out = {}
  for _, o in ipairs(def.objects or {}) do
    if o.x and o.y then out[o.x .. "," .. o.y] = true end
  end
  return out
end

-- a plain cell to stand on: in bounds, walkable, no water, no grass, no
-- object, no warp
local function plain(m, objects, x, y)
  return m:inBounds(x, y) and m:isWalkableCell(x, y) and not m:isWaterCell(x, y) and not m:isGrassCell(x, y)
    and not m:warpAtCell(x, y) and not objects[x .. "," .. y]
end

-- the first stop: a plain cell beside the map's first warp, facing away from it
local function stopSpot(game, mapId)
  local m, def = mapQuery(game, mapId)
  if not m then fail("no_map map=" .. word(mapId)) end
  local objects = objectCells(def)
  for _, w in ipairs(def.warps or {}) do
    for _, d in ipairs(DIRS) do
      local x, y = w.x - DELTA[d][1], w.y - DELTA[d][2]
      if plain(m, objects, x, y) then return { map = mapId, x = x, y = y, facing = AWAY[d] } end
    end
  end
  fail("no_stop map=" .. word(mapId))
end

local function npcId(mapId, o) return mapId .. "_obj_" .. o.index end

-- every map trainer of a map beaten, so nobody sights the player
-- (checkTrainerSight skips beaten ones, OC:4506-4554; the header's event,
-- Data:trainerHeader, src/core/Data.lua:426-429)
local function beatTrainers(game, mapId)
  local def = game.data.maps[mapId]
  local n = 0
  for _, o in ipairs(def and def.objects or {}) do
    if o.trainerClass then
      game.save.defeatedTrainers[npcId(mapId, o)] = true
      local header = game.data:trainerHeader(def.label, o.index)
      if header and header.event then game.save.flags[header.event] = true end
      n = n + 1
    end
  end
  return n
end

local function readRequest()
  local f = io.open(DIR .. "/request.txt", "r")
  if not f then return nil end
  local line = f:read("*l")
  f:close()
  local seq, kind, map, facing, rest = (line or ""):match("^(%d+) (%a+) ([%w_]+) (%a+)(.*)$")
  if not seq then return nil end
  local cells = {}
  for x, y in rest:gmatch("(%d+),(%d+)") do cells[#cells + 1] = { x = tonumber(x), y = tonumber(y) } end
  return { seq = tonumber(seq), kind = kind, map = map, facing = facing, cells = cells }
end

local function answer(seq, verdict)
  local tmp = DIR .. "/answer.txt.tmp"
  local f = io.open(tmp, "w")
  if not f then return end
  f:write(("%d %s\n"):format(seq, verdict))
  f:close()
  os.rename(tmp, DIR .. "/answer.txt")
end

-- what is wrong with a request's cells, or nil
local function check(game, req)
  if not DELTA[req.facing] then return "bad_facing" end
  if req.kind ~= "walk" and req.kind ~= "ladder" then return "bad_kind" end
  if #req.cells == 0 or req.kind == "ladder" and #req.cells ~= 2 then return "bad_cells" end
  local m, def = mapQuery(game, req.map)
  if not m then return "no_map" end
  local objects = objectCells(def)
  for i, c in ipairs(req.cells) do
    if req.kind == "ladder" and i == 2 then
      if not (m:inBounds(c.x, c.y) and m:warpAtCell(c.x, c.y)) then return "no_warp " .. c.x .. "," .. c.y end
    elseif not plain(m, objects, c.x, c.y) then
      return "not_plain " .. c.x .. "," .. c.y
    end
  end
  return nil
end

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local maps = {}
  for id in MAPS:gmatch("[^,]+") do maps[#maps + 1] = id end
  if #maps == 0 then fail("no_maps") end
  if not DIR or DIR == "" then fail("no_pilot_dir") end
  local arrived = false
  local boundAt
  local nextFrame
  local world
  local frames = 0
  local lastSeq = -1
  local pending
  if DATASET_MEMO > 0 then
    print(("KB_DATASET_MEMO seconds=%d ok=%s"):format(DATASET_MEMO, tostring(memoDatasetChecks(DATASET_MEMO))))
  end
  local Runtime = require("src.mods.Runtime")
  Runtime.events:on("map.entered", function(payload)
    local id = type(payload) == "table" and payload.mapId or nil
    local via = type(payload) == "table" and payload.via or nil
    print(("KB_MAP_ENTERED map=%s via=%s"):format(word(id), word(via)))
  end, 0, "kb-pilot-driver")
  local first
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    if phase == "ready" then
      if status and status.ready then
        status.configure({ port = PORT })
        game:startNewGame({ intro = false })
        phase = "world"
      end
    elseif phase == "world" then
      local ow = overworld(game)
      if ow and game.stack:top() == ow and ow.map then
        local save, data = game.save, game.data
        local species
        for id, def in pairs(data.pokemon) do
          if type(def) == "table" and def.dex == STARTER_DEX and (species == nil or id < species) then species = id end
        end
        if not species then fail("no_starter_species") end
        -- level 100 and REPEL steps: the engine's own REPEL filter keeps every
        -- wild Pokemon weaker than the lead away (OC:4934-4940)
        local mon = require("src.pokemon.Pokemon").new(data, species, 100)
        save.repelSteps = 9999
        require("src.battle.BattleState").stampOT(save, mon)
        if not require("src.pokemon.Party").add(save.party, mon) then fail("party_full") end
        local beaten = 0
        for _, id in ipairs(maps) do beaten = beaten + beatTrainers(game, id) end
        print(("KB_PILOT_TRAINERS maps=%d beaten=%d"):format(#maps, beaten))
        world = require("src.world.WorldAPI").new(game, "kb-pilot-driver")
        first = stopSpot(game, FIRST or maps[1])
        arrived = false
        local ok, err = world:warpTo(first.map, first.x, first.y, first.facing, { onDone = function() arrived = true end })
        if not ok then fail("warp " .. word(err)) end
        phase = "warping"
      end
    elseif phase == "warping" then
      local ow = overworld(game)
      if arrived and ow and game.stack:top() == ow and ow.map and ow.map.id == first.map then
        local p = ow.player
        print(("KB_PILOT_READY map=%s x=%s y=%s facing=%s port=%s"):format(word(ow.map.id), word(p.cellX), word(p.cellY),
          word(p.facing), word(status.port)))
        phase = "serve"
      end
    elseif phase == "serve" then
      if PACE > 0 then
        local now = love.timer.getTime()
        nextFrame = math.max((nextFrame or now) + 1 / PACE, now - 0.1)
        if nextFrame > now then love.timer.sleep(nextFrame - now) end
      end
      if not boundAt and (status.sessionBinds or 0) > 0 then
        boundAt = love.timer.getTime()
        print("KB_PILOT_BOUND")
      end
      frames = frames + 1
      local ow = overworld(game)
      if pending then
        local p = ow and ow.player
        if arrived and ow and game.stack:top() == ow and ow.map and ow.map.id == pending.map and p and p.cellX == pending.x
            and p.cellY == pending.y then
          game.save.flashLit = true
          print(("KB_PILOT_ARRIVED n=%d map=%s x=%d y=%d"):format(pending.seq, word(pending.map), pending.x, pending.y))
          answer(pending.seq, "ok")
          pending = nil
        elseif love.timer.getTime() - pending.at > 30 then
          print(("KB_PILOT_ARRIVED n=%d timeout"):format(pending.seq))
          answer(pending.seq, "refused timeout")
          pending = nil
        end
      elseif frames % POLL_FRAMES == 0 then
        local req = readRequest()
        if req and req.seq > lastSeq then
          lastSeq = req.seq
          local wrong = check(game, req)
          local verdict = wrong or "ok"
          if not wrong then
            local c = req.cells[1]
            arrived = false
            local ok, err = world:warpTo(req.map, c.x, c.y, req.facing, { onDone = function() arrived = true end })
            if ok then
              pending = { seq = req.seq, map = req.map, x = c.x, y = c.y, at = love.timer.getTime() }
            else
              verdict = "warp_" .. word(err)
            end
          end
          print(("KB_PILOT_REQUEST n=%d kind=%s map=%s cells=%d verdict=%s"):format(req.seq, word(req.kind), word(req.map),
            #req.cells, word(verdict)))
          if verdict ~= "ok" then answer(req.seq, "refused " .. verdict) end
        end
      end
      if status.quitRequested then
        print(("KB_END map=%s requests=%d"):format(word(ow and ow.map and ow.map.id), lastSeq))
        print(("KB_QUIT sessions=%s code=%s"):format(word(status.sessions), word(status.quitCode)))
        return
      end
    end
    if love.timer.getTime() - started > SECONDS_LIMIT then
      fail("timeout phase=" .. phase)
    end
    game = coroutine.yield() or game
  end
end
