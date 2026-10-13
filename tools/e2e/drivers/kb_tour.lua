-- Kanto Bridge test driver (the caves and woods plan's CW5 video,
-- docs/specs/CAVES_AND_WOODS_PLAN.md §6): moves gen1-bridge to the test port,
-- starts a new game without the intro (src/core/Game.lua:173-200), gives the
-- player a starter (as kb_wild.lua does) and warps through the supported
-- mod.world facade (WorldAPI:warpTo, src/world/WorldAPI.lua:358-371) to each
-- stop of KB_TOUR_STOPS in turn (map symbols, comma-separated; default Mt.
-- Moon's first floor, then Viridian Forest): a plain cell beside the map's
-- first warp, facing away from it, found with the engine's own map queries.
-- The first stop is taken at once; each next one KB_TOUR_SECONDS after the
-- client has bound its session (the dev client's autopilot, scene "tour",
-- records a look round at every stop). Then it serves until gen1-bridge
-- reports a debug.quit (exit 0) or the time limit passes (exit 1).
--
-- It prints KB_TOUR_STOP n= map= x= y= facing= at each arrival, KB_MAP_ENTERED
-- map=<symbol> via=<word> on every map.entered, and KB_END at the end.
--
-- KB_PACE and KB_DATASET_MEMO as in kb_wild.lua (the pod: a real client plays
-- against it, and the network disk would make the first export slow).
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame.
-- Data safety: prints only markers, map symbols, cells and counts.

io.stdout:setvbuf("no")

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27209
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or 1200
local STOP_SECONDS = tonumber(os.getenv("KB_TOUR_SECONDS") or "") or 60
local STOPS = os.getenv("KB_TOUR_STOPS") or "MT_MOON_1F,VIRIDIAN_FOREST"
local STARTER_DEX = 4
local PACE = tonumber(os.getenv("KB_PACE") or "") or 0
local DATASET_MEMO = tonumber(os.getenv("KB_DATASET_MEMO") or "") or 0
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

-- a plain cell to stand on: in bounds, walkable, no water, no grass, no
-- object, no warp
local function plain(m, def, x, y)
  for _, o in ipairs(def.objects or {}) do
    if o.x == x and o.y == y then return false end
  end
  return m:inBounds(x, y) and m:isWalkableCell(x, y) and not m:isWaterCell(x, y) and not m:isGrassCell(x, y)
    and not m:warpAtCell(x, y)
end

-- the stop: a plain cell beside the map's first warp, facing away from it
local function stopSpot(game, mapId)
  local m, def = mapQuery(game, mapId)
  if not m then fail("no_map map=" .. word(mapId)) end
  for _, w in ipairs(def.warps or {}) do
    for _, d in ipairs(DIRS) do
      local x, y = w.x - DELTA[d][1], w.y - DELTA[d][2]
      if plain(m, def, x, y) then return { map = mapId, x = x, y = y, facing = AWAY[d] } end
    end
  end
  fail("no_stop map=" .. word(mapId))
end

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local stops = {}
  for id in STOPS:gmatch("[^,]+") do stops[#stops + 1] = id end
  if #stops == 0 then fail("no_stops") end
  local stop = 0
  local arrived = false
  local boundAt
  local nextFrame
  local world
  if DATASET_MEMO > 0 then
    print(("KB_DATASET_MEMO seconds=%d ok=%s"):format(DATASET_MEMO, tostring(memoDatasetChecks(DATASET_MEMO))))
  end
  local Runtime = require("src.mods.Runtime")
  Runtime.events:on("map.entered", function(payload)
    local id = type(payload) == "table" and payload.mapId or nil
    local via = type(payload) == "table" and payload.via or nil
    print(("KB_MAP_ENTERED map=%s via=%s"):format(word(id), word(via)))
  end, 0, "kb-tour-driver")
  local function go(n)
    local spot = stopSpot(game, stops[n])
    arrived = false
    local ok, err = world:warpTo(spot.map, spot.x, spot.y, spot.facing, { onDone = function() arrived = true end })
    if not ok then fail("warp " .. word(err)) end
    stop = n
    phase = "warping"
  end
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
        local mon = require("src.pokemon.Pokemon").new(data, species, 5)
        require("src.battle.BattleState").stampOT(save, mon)
        if not require("src.pokemon.Party").add(save.party, mon) then fail("party_full") end
        world = require("src.world.WorldAPI").new(game, "kb-tour-driver")
        go(1)
      end
    elseif phase == "warping" then
      local ow = overworld(game)
      if arrived and ow and game.stack:top() == ow and ow.map and ow.map.id == stops[stop] then
        local p = ow.player
        print(("KB_TOUR_STOP n=%d map=%s x=%s y=%s facing=%s port=%s"):format(stop, word(ow.map.id), word(p.cellX),
          word(p.cellY), word(p.facing), word(status.port)))
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
        print("KB_TOUR_BOUND")
      end
      if boundAt and stop < #stops and love.timer.getTime() - boundAt > STOP_SECONDS * stop then
        go(stop + 1)
      end
      if status.quitRequested then
        local ow = overworld(game)
        print(("KB_END map=%s stops=%d"):format(word(ow and ow.map and ow.map.id), stop))
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
