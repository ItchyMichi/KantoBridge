-- Kanto Bridge test driver (FOREIGN_POKEMON_PLAN FP2, the user's stone check,
-- tools/e2e/fp_stone.py): a visitor in Blue evolved in Cobblemon by a stone
-- picked up and used in the hidden game, then saved and loaded again.
-- KB_SCENE picks the launch:
--   first  New Game without the intro (src/core/Game.lua:173-200), then a warp
--          (the supported mod.world facade, WorldAPI:warpTo,
--          src/world/WorldAPI.lua:358-371) onto the cell next to the map's
--          item ball holding KB_ITEM (default MOON_STONE; the map's objects,
--          their `item` field, WorldAPI.lua:340-344), facing it:
--          KB_FP_READY. The dev client gives the Cobblemon party, picks the
--          ball up with A, uses the stone from the bag and saves.
--   again  the game at its title until the client has bound the world, then
--          the saved game continued the way the title's CONTINUE runs it
--          (SaveData.load + Game:restoreSave, src/core/Game.lua:224-242,
--          :1475-1537; as kb_crash.lua does): KB_CONTINUED.
-- Both scenes print KB_FP_PARTY whenever the hidden party changes (checked
-- every 30 frames): per member its slot, whether it is gen1-bridge's
-- stand-in, the dex the record's species has, the real dex gen1-bridge's
-- mod.save keeps for a visitor (save.modData, src/core/Game.lua:1344-1358;
-- "visitorIds"), level, HP/max, the first 8 hex digits of its uid and of the
-- SHA-256 of its nickname (the test compares hashes; no name is printed),
-- and the bag's count of KB_ITEM. Serves until gen1-bridge reports a
-- debug.quit or the time limit passes.
--
-- KB_PACE paces the game as kb_serve.lua does; KB_DATASET_MEMO memoises the
-- import cache check (the pod's network disk; test-only). Runs unsandboxed as
-- a POKEPORT_DRIVER coroutine. Data safety: prints markers, map and item
-- symbols, cells, numbers and hash prefixes only.

io.stdout:setvbuf("no")

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27243
local PACE = tonumber(os.getenv("KB_PACE") or "") or 60
local DATASET_MEMO = tonumber(os.getenv("KB_DATASET_MEMO") or "") or 0
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or 7200
local SCENE = os.getenv("KB_SCENE") or "first"
local MAP = os.getenv("KB_MAP") or "MT_MOON_1F"
local ITEM = os.getenv("KB_ITEM") or "MOON_STONE"
local STAND_IN = "KB_VISITOR"
-- WORLD_SESSIONS_PLAN WS3 (B2): with KB_FP_CLIENT_CONTINUE=1 the again scene leaves the CONTINUE to the client's pick
-- on the mirrored title menu and only reports the overworld's arrival (KB_CONTINUED via=client)
local CLIENT_CONTINUE = os.getenv("KB_FP_CLIENT_CONTINUE") == "1"

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

-- The SHA-256 of a string as its first 8 hex digits ("-" for none), with
-- gen1-bridge's own implementation (lib/kb_sha256.lua, read as plain Lua).
local sha
local function hash8(s)
  if type(s) ~= "string" or s == "" then return "-" end
  if not sha then
    local f = io.open((os.getenv("KB_BRIDGE_DIR") or "") .. "/lib/kb_sha256.lua", "rb")
    if not f then return "?" end
    local src = f:read("*a")
    f:close()
    sha = load(src, "@kb_sha256.lua")()
  end
  return sha.hex(s):sub(1, 8)
end

-- The cell beside the map's ball holding ITEM, facing it (the map's own
-- queries for a walkable cell).
local function ballSpot(game)
  local def = game.data.maps[MAP]
  local ts = def and game.data.tilesets[def.tileset]
  if not (def and ts) then fail("no_map map=" .. word(MAP)) end
  local Map = require("src.world.Map")
  local m = Map.new(def, ts)
  for _, o in ipairs(def.objects or {}) do
    if o.item == ITEM and type(o.x) == "number" and type(o.y) == "number" then
      for _, d in ipairs({ { 0, 1, "up" }, { 0, -1, "down" }, { 1, 0, "left" }, { -1, 0, "right" } }) do
        local x, y = o.x + d[1], o.y + d[2]
        if m:isWalkableCell(x, y) and not m:warpAtCell(x, y) then
          return { x = x, y = y, facing = d[3], ballX = o.x, ballY = o.y }
        end
      end
    end
  end
  fail("no_ball item=" .. word(ITEM) .. " map=" .. word(MAP))
end

local function partyLine(game)
  local save, data = game.save, game.data
  if type(save) ~= "table" or type(save.party) ~= "table" then return "size=0" end
  local bucket = type(save.modData) == "table" and save.modData["gen1-bridge"] or nil
  local uids = type(bucket) == "table" and bucket.partyUids or {}
  local ids = type(bucket) == "table" and bucket.visitorIds or {}
  local parts = { ("size=%d"):format(#save.party) }
  for i, mon in ipairs(save.party) do
    local def = data.pokemon[mon.species]
    local uid = type(uids) == "table" and uids[i] or nil
    local real = type(uid) == "string" and type(ids) == "table" and type(ids[uid]) == "table" and ids[uid].dex or nil
    local maxhp = type(mon.stats) == "table" and mon.stats.hp or nil
    parts[#parts + 1] = ("m%d=%s:%s:%s:%s:%s/%s:%s:%s"):format(i, mon.species == STAND_IN and "visitor" or "native",
      word(def and def.dex), word(real), word(mon.level), word(mon.hp), word(maxhp),
      type(uid) == "string" and uid:sub(1, 8) or "-", hash8(mon.nickname))
  end
  local inv = type(save.inventory) == "table" and save.inventory or {}
  parts[#parts + 1] = ("item=%s:%s"):format(word(ITEM), word(inv[ITEM] or 0))
  return table.concat(parts, " ")
end

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local arrived = false
  local nextFrame
  local frames = 0
  local lastLine
  local boundAt
  local Runtime = require("src.mods.Runtime")
  Runtime.events:on("map.entered", function(payload)
    local id = type(payload) == "table" and payload.mapId or nil
    print(("KB_MAP_ENTERED map=%s"):format(word(id)))
  end, 0, "kb-fpstone-driver")
  for _, name in ipairs({ "save.loaded", "save.created", "save.writing" }) do
    Runtime.events:on(name, function() print("KB_EVENT " .. name) end, 0, "kb-fpstone-driver")
  end
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    if phase == "ready" then
      if status and status.ready then
        status.configure({ port = PORT })
        if DATASET_MEMO > 0 then
          print(("KB_DATASET_MEMO seconds=%d ok=%s"):format(DATASET_MEMO, tostring(memoDatasetChecks(DATASET_MEMO))))
        end
        print(("KB_BOOTED port=%s scene=%s"):format(word(status.port), word(SCENE)))
        if SCENE == "first" then
          game:startNewGame({ intro = false })
          phase = "world"
        else
          phase = "title"
        end
      end
    elseif phase == "world" then
      local ow = overworld(game)
      if ow and game.stack:top() == ow and ow.map then
        local spot = ballSpot(game)
        local world = require("src.world.WorldAPI").new(game, "kb-fpstone-driver")
        local ok, err = world:warpTo(MAP, spot.x, spot.y, spot.facing, { onDone = function() arrived = true end })
        if not ok then fail("warp " .. word(err)) end
        phase = "warping"
      end
    elseif phase == "warping" then
      local ow = overworld(game)
      if arrived and ow and game.stack:top() == ow and ow.map and ow.map.id == MAP then
        local p = ow.player
        print(("KB_FP_READY map=%s x=%s y=%s facing=%s port=%s"):format(word(ow.map.id), word(p.cellX), word(p.cellY),
          word(p.facing), word(status.port)))
        phase = "serve"
      end
    elseif phase == "title" and CLIENT_CONTINUE then
      local ow = game.stack and game.stack.top and game.stack:top()
      if type(ow) == "table" and ow.isOverworld and ow.map then
        print(("KB_CONTINUED via=client party=%d"):format(#(game.save.party or {})))
        phase = "serve"
      end
    elseif phase == "title" then
      if (status.sessionBinds or 0) >= 1 then
        boundAt = boundAt or love.timer.getTime()
        -- a moment for the bind's answer (the slot it pairs is the one played before)
        if love.timer.getTime() - boundAt > 3 then
          local SaveData = require("src.core.SaveData")
          local loaded, recovered = SaveData.load()
          if not loaded then fail("no_save") end
          game:restoreSave(loaded, recovered, { freshBoot = true, continued = true })
          print(("KB_CONTINUED party=%d"):format(#(game.save.party or {})))
          phase = "serve"
        end
      end
    elseif phase == "serve" then
      if status.quitRequested then
        print(("KB_QUIT sessions=%s code=%s"):format(word(status.sessions), word(status.quitCode)))
        return
      end
    end
    frames = frames + 1
    if frames % 30 == 0 and overworld(game) then
      local line = partyLine(game)
      if line ~= lastLine then
        print("KB_FP_PARTY " .. line)
        lastLine = line
      end
    end
    if PACE > 0 then
      local now = love.timer.getTime()
      nextFrame = math.max((nextFrame or now) + 1 / PACE, now - 0.1)
      if nextFrame > now then love.timer.sleep(nextFrame - now) end
    end
    if love.timer.getTime() - started > SECONDS_LIMIT then fail("timeout phase=" .. phase) end
    game = coroutine.yield() or game
  end
end
