-- Kanto Bridge test driver (M6 Route 1 e2e): moves gen1-bridge to the test
-- port, starts a new game without the intro, gives the player a starter
-- (Pokemon.new + Party.add, src/pokemon/Pokemon.lua:60-88,
-- src/pokemon/Party.lua:7-13, the OT stamped as gifts are,
-- BattleState.stampOT :698-705), fills the bag through the engine's own
-- Bag.add (src/inventory/Bag.lua:443-460) with two battle items, one key
-- item and one field item, and warps onto ROUTE_1 through the supported
-- mod.world facade (WorldAPI:warpTo, src/world/WorldAPI.lua:358-371). The
-- arrival cell is found with the engine's own map queries (Map.new,
-- src/world/Map.lua:112): walkable, no grass, no water, no warp, away from
-- the map's objects, low on the map. Then it prints KB_ROUTE_READY and
-- serves until gen1-bridge reports a debug.quit (exit 0) or the time limit
-- passes (exit 1). The test (tools/e2e/test_route1.py) does everything else
-- over the bridge.
--
-- It prints KB_BATTLE_PUSHED when a native battle screen appears (there
-- must be none: grass encounters are Cobblemon's habitat pools now) and
-- KB_BLACKOUT when the engine blacks out.
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- (refs/gen1recomp/main.lua:755-759, :1136-1157). Data safety: prints only
-- markers, map and item symbols, cells and counts -- never a species, a
-- name or any text.

io.stdout:setvbuf("no") -- LÖVE block-buffers stdout; this also unbuffers mod.log

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27191
local EXTRA_SECONDS = tonumber(os.getenv("KB_DRIVER_EXTRA") or "") or 0 -- the runner's export allowance
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or (600 + EXTRA_SECONDS)
local MAP = "ROUTE_1"
local STARTER_DEX = 4
-- two battle items (PLAN Appendix B), a key item and a field item
local STOCK = { { "POKE_BALL", 5 }, { "POTION", 3 }, { "TOWN_MAP", 1 }, { "REPEL", 2 } }

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

-- A cell low on ROUTE_1 to arrive on, right next to a patch of tall grass
-- (a grass cell with a grass neighbour), so the walk to it can't be cut off
-- by a one-way ledge (the map's own queries; nothing printed but the cell).
local function arrivalCell(game)
  local def = game.data.maps[MAP]
  local ts = def and game.data.tilesets[def.tileset]
  if not (def and ts) then return nil end
  local Map = require("src.world.Map")
  local m = Map.new(def, ts)
  local w, h = def.width * 2, def.height * 2
  local function nearObject(x, y)
    for _, o in ipairs(def.objects or {}) do
      if type(o.x) == "number" and type(o.y) == "number" and math.abs(o.x - x) + math.abs(o.y - y) <= 4 then
        return true
      end
    end
    return false
  end
  local function good(x, y)
    return x >= 0 and y >= 0 and x < w and y < h and m:isWalkableCell(x, y) and not m:isGrassCell(x, y)
      and not m:isWaterCell(x, y) and not m:warpAtCell(x, y) and not nearObject(x, y)
  end
  local function grass(x, y)
    return x >= 0 and y >= 0 and x < w and y < h and m:isGrassCell(x, y) and m:isWalkableCell(x, y)
  end
  local NEIGHBOURS = { { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 } }
  local function patchNextTo(x, y)
    for _, d in ipairs(NEIGHBOURS) do
      local gx, gy = x + d[1], y + d[2]
      if grass(gx, gy) then
        for _, e in ipairs(NEIGHBOURS) do
          if grass(gx + e[1], gy + e[2]) then return true end
        end
      end
    end
    return false
  end
  local mid = math.floor(w / 2)
  for y = h - 3, 2, -1 do
    for dx = 0, w do
      for _, x in ipairs({ mid + dx, mid - dx }) do
        if good(x, y) and patchNextTo(x, y) then return x, y end
      end
    end
  end
  return nil
end

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local arrived = false
  local pushedBattles = 0
  local Runtime = require("src.mods.Runtime")
  Runtime.events:on("world.blacked_out", function() print("KB_BLACKOUT") end, 0, "kb-route1-driver")
  Runtime.events:on("screen.pushed", function(payload)
    local state = type(payload) == "table" and payload.state or nil
    if type(state) == "table" and state.isBattleState then
      pushedBattles = pushedBattles + 1
      print(("KB_BATTLE_PUSHED count=%d"):format(pushedBattles))
    end
  end, 0, "kb-route1-driver")
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    if phase == "ready" then
      if status and status.ready then
        status.configure({ port = PORT })
        -- New Game without Oak's speech (src/core/Game.lua:173-200)
        game:startNewGame({ intro = false })
        phase = "world"
      end
    elseif phase == "world" then
      local ow = overworld(game)
      if ow and game.stack:top() == ow and ow.map then
        local save, data = game.save, game.data
        -- the starter: the species with this dex number (never printed)
        local species
        for id, def in pairs(data.pokemon) do
          if type(def) == "table" and def.dex == STARTER_DEX and (species == nil or id < species) then species = id end
        end
        if not species then fail("no_starter_species") end
        local mon = require("src.pokemon.Pokemon").new(data, species, 5)
        require("src.battle.BattleState").stampOT(save, mon)
        if not require("src.pokemon.Party").add(save.party, mon) then fail("party_full") end
        -- the bag: only what the test expects, through the engine's Bag
        local Bag = require("src.inventory.Bag")
        save.inventory, save.bagOrder = {}, {}
        for _, row in ipairs(STOCK) do
          if not data.items[row[1]] then fail("item_missing id=" .. word(row[1])) end
          if not Bag.add(save, row[1], row[2], data) then fail("bag_add id=" .. word(row[1])) end
        end
        local x, y = arrivalCell(game)
        if not x then fail("no_arrival_cell") end
        local world = require("src.world.WorldAPI").new(game, "kb-route1-driver")
        local ok, err = world:warpTo(MAP, x, y, "up", { onDone = function() arrived = true end })
        if not ok then fail("warp " .. word(err)) end
        phase = "warping"
      end
    elseif phase == "warping" then
      local ow = overworld(game)
      if arrived and ow and game.stack:top() == ow and ow.map and ow.map.id == MAP then
        local p = ow.player
        local battle, other = 0, 0
        for id, qty in pairs(game.save.inventory) do
          if id == "POKE_BALL" or id == "POTION" then battle = battle + qty else other = other + 1 end
        end
        print(("KB_ROUTE_READY map=%s x=%s y=%s facing=%s party=%d bag_battle=%d bag_other=%d port=%s"):format(
          word(ow.map.id), word(p.cellX), word(p.cellY), word(p.facing), #game.save.party, battle, other,
          word(status.port)))
        phase = "serve"
      end
    elseif phase == "serve" then
      if status.quitRequested then
        local ow = overworld(game)
        print(("KB_END map=%s party=%d battles_pushed=%d"):format(
          word(ow and ow.map and ow.map.id), #game.save.party, pushedBattles))
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
