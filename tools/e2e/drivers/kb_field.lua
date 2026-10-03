-- Kanto Bridge test driver (M8 field moves and forced movement e2e): moves
-- gen1-bridge to the test port, starts a new game without the intro, gives
-- the player one level-100 party member (Pokemon.new + Party.add,
-- src/pokemon/Pokemon.lua:60-88, src/pokemon/Party.lua:7-13, the OT stamped
-- as gifts are, BattleState.stampOT :698-705) that knows the scene's field
-- move (an extra move entry, as a TM would add it) and the scene's badge in
-- the bag (save.inventory, src/inventory/Badges.lua:22-37), REPEL steps
-- (save.repelSteps: with the strong lead, no wild encounter), beats every map
-- trainer of the scene's map (save.defeatedTrainers and the header's event,
-- so nobody sights the player), and warps into the scene through the
-- supported mod.world facade (WorldAPI:warpTo, src/world/WorldAPI.lua
-- :358-371). The scene (KB_SCENE, environment), found with the engine's own
-- data and map queries (Map.new, src/world/Map.lua:112), nothing printed but
-- symbols, cells and counts:
--
--   cut       facing a cuttable tree (OVERWORLD tile $3d in a block with a
--             field.cutTreeSwaps row, OverworldState:tryCut's own gate,
--             OC:3292-3313) from a plain cell. Once the engine has recorded
--             the cut (ow.cutBlocks, OC:3318-3322) and the player stands on
--             the scene cell again, idle, turned away from the tree (the
--             test's signal that it is done there), the driver warps it away
--             to ROUTE_1 (KB_AWAY) and back onto the scene cell facing the
--             tree (KB_BACK, with whether the tree's block is the map's own
--             again).
--   surf      facing water (Map:isWaterCell on a water tileset) with three
--             water cells in a row
--   spin      one step from a spinner arrow (field.spinners) of VIRIDIAN_GYM
--             or the Rocket Hideout
--   elevator  inside an *_ELEVATOR car, one step above one of its warps
--   strength  facing a Strength boulder (SPRITE_BOULDER, visible) with a free
--             walkable cell behind it
--
-- It prints KB_BATTLE_PUSHED for any native battle screen and serves until
-- gen1-bridge reports a debug.quit (exit 0) or the time limit passes
-- (exit 1).
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- (refs/gen1recomp/main.lua:641-645, :993-1011). Data safety: prints only
-- markers, map, move and item symbols, cells and counts -- never a species,
-- a name, a block id or any text.

io.stdout:setvbuf("no") -- LÖVE block-buffers stdout; this also unbuffers mod.log

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27187
local SCENE = os.getenv("KB_SCENE") or "cut"
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or 600
local STARTER_DEX = 7
local CUT_TREE_TILE = 0x3d -- OverworldState:tryCut's OVERWORLD tree tile (OC:3304)
local AWAY_MAP = "ROUTE_1"
local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
local DIRS = { "up", "down", "left", "right" }
local OPPOSITE = { up = "down", down = "up", left = "right", right = "left" }

-- the move and badge each scene needs (constants.hmBadges,
-- src/world/FieldDefaults.lua:220-224)
local NEEDS = {
  cut = { move = "CUT", badge = "CASCADEBADGE" },
  surf = { move = "SURF", badge = "SOULBADGE" },
  strength = { move = "STRENGTH", badge = "RAINBOWBADGE" },
}

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

-- the world waits for the player: the overworld on top and the engine's
-- own quick-save gate open (no script, scripted move, emote, warp or step)
local function idle(game)
  local ow = overworld(game)
  if not (ow and game.stack:top() == ow) then return false end
  local ok, allowed = pcall(game.quickSaveAllowed, game)
  return ok and allowed == true
end

local function mapQuery(game, mapId)
  local def = game.data.maps[mapId]
  local ts = def and game.data.tilesets[def.tileset]
  if not (def and ts) then return nil end
  local ok, m = pcall(require("src.world.Map").new, def, ts)
  if not ok then return nil end
  return m, def
end

local function objectCells(def)
  local cells = {}
  for _, o in ipairs(def.objects or {}) do
    if type(o.x) == "number" and type(o.y) == "number" then cells[o.x .. ":" .. o.y] = true end
  end
  return cells
end

-- a plain cell to stand on: in bounds, walkable, no water, no grass, no
-- object, no warp
local function plain(m, cells, x, y)
  return m:inBounds(x, y) and m:isWalkableCell(x, y) and not m:isWaterCell(x, y) and not m:isGrassCell(x, y)
    and not cells[x .. ":" .. y] and not m:warpAtCell(x, y)
end

-- maps in a preferred order, then every other one sorted
local function mapOrder(game, first)
  local ids, listed = {}, {}
  for _, id in ipairs(first) do
    if game.data.maps[id] then ids[#ids + 1], listed[id] = id, true end
  end
  local rest = {}
  for id in pairs(game.data.maps) do
    if not listed[id] then rest[#rest + 1] = id end
  end
  table.sort(rest)
  for _, id in ipairs(rest) do ids[#ids + 1] = id end
  return ids
end

local function scripted(mapId)
  local MapScripts = require("src.script.MapScripts")
  local view = MapScripts.get and MapScripts.get(mapId) or nil
  return type(view) == "table" and (view.onEnter or view.onStep) and true or false
end

-- ------------------------------------------------------------ the scenes

local function cutSpot(game)
  local swaps = {}
  for _, row in ipairs(game.data.field.cutTreeSwaps or {}) do swaps[row.before] = true end
  for _, mapId in ipairs(mapOrder(game, { "ROUTE_2", "ROUTE_9", "ROUTE_8", "ROUTE_13", "ROUTE_14" })) do
    local m, def = mapQuery(game, mapId)
    if m and def.tileset == "OVERWORLD" and not scripted(mapId) then
      local cells = objectCells(def)
      for y = 0, def.height * 2 - 1 do
        for x = 0, def.width * 2 - 1 do
          local bx, by = math.floor(x / 2), math.floor(y / 2)
          if m:cellTile(x, y) == CUT_TREE_TILE and not m:isWalkableCell(x, y) and swaps[m:blockAt(bx, by)] then
            for _, d in ipairs(DIRS) do
              local px, py = x - DELTA[d][1], y - DELTA[d][2]
              -- standing in the tree's own block would change under the player
              if plain(m, cells, px, py) and (math.floor(px / 2) ~= bx or math.floor(py / 2) ~= by) then
                return { map = mapId, x = px, y = py, facing = d, tx = x, ty = y, bx = bx, by = by,
                         block = m:blockAt(bx, by) }
              end
            end
          end
        end
      end
    end
  end
  fail("no_cut_spot")
end

local function surfSpot(game)
  local waterTilesets = {}
  for _, t in ipairs(game.data.field.waterTilesets or {}) do waterTilesets[t] = true end
  for _, mapId in ipairs(mapOrder(game, { "PALLET_TOWN", "ROUTE_21", "CERULEAN_CITY", "VERMILION_CITY" })) do
    local m, def = mapQuery(game, mapId)
    if m and def.tileset == "OVERWORLD" and waterTilesets[def.tileset] and not scripted(mapId) then
      local cells = objectCells(def)
      for y = 0, def.height * 2 - 1 do
        for x = 0, def.width * 2 - 1 do
          if plain(m, cells, x, y) then
            for _, d in ipairs(DIRS) do
              local ok = true
              for k = 1, 3 do
                local wx, wy = x + DELTA[d][1] * k, y + DELTA[d][2] * k
                if not (m:inBounds(wx, wy) and m:isWaterCell(wx, wy) and not m:isWalkableCell(wx, wy)
                        and not cells[wx .. ":" .. wy]) then
                  ok = false
                end
              end
              if ok then return { map = mapId, x = x, y = y, facing = d } end
            end
          end
        end
      end
    end
  end
  fail("no_surf_spot")
end

local function spinSpot(game)
  local spinners = game.data.field.spinners or {}
  for _, mapId in ipairs(mapOrder(game, { "VIRIDIAN_GYM", "ROCKET_HIDEOUT_B2F", "ROCKET_HIDEOUT_B3F" })) do
    local list = spinners[mapId]
    local m, def = mapQuery(game, mapId)
    if m and type(list) == "table" then
      local cells = objectCells(def)
      local arrows = {}
      for _, sp in ipairs(list) do arrows[sp.x .. ":" .. sp.y] = true end
      for _, sp in ipairs(list) do
        if m:isWalkableCell(sp.x, sp.y) then
          for _, d in ipairs(DIRS) do
            local px, py = sp.x - DELTA[d][1], sp.y - DELTA[d][2]
            if plain(m, cells, px, py) and not arrows[px .. ":" .. py] then
              local steps = 0
              for _, mv in ipairs(sp.moves or {}) do steps = steps + (mv.count or 1) end
              return { map = mapId, x = px, y = py, facing = d, sx = sp.x, sy = sp.y, steps = steps }
            end
          end
        end
      end
    end
  end
  fail("no_spin_spot")
end

local function elevatorSpot(game)
  for _, mapId in ipairs(mapOrder(game, { "CELADON_MART_ELEVATOR" })) do
    if mapId:find("_ELEVATOR$") then
      local m, def = mapQuery(game, mapId)
      if m then
        local cells = objectCells(def)
        for _, w in ipairs(def.warps or {}) do
          for _, d in ipairs(DIRS) do
            local px, py = w.x - DELTA[d][1], w.y - DELTA[d][2]
            if plain(m, cells, px, py) then
              return { map = mapId, x = px, y = py, facing = d, wx = w.x, wy = w.y }
            end
          end
        end
      end
    end
  end
  fail("no_elevator_spot")
end

local function boulderSpot(game)
  local OverworldState = require("src.world.OverworldController")
  for _, mapId in ipairs(mapOrder(game, { "VICTORY_ROAD_1F", "VICTORY_ROAD_2F", "SEAFOAM_ISLANDS_1F" })) do
    local m, def = mapQuery(game, mapId)
    if m and type(def.objects) == "table" then
      local cells = objectCells(def)
      for _, o in ipairs(def.objects) do
        local pushable = o.pushable
        if pushable == nil then pushable = o.sprite == "SPRITE_BOULDER" end
        if pushable and OverworldState.objectVisible(game.save, mapId, o) then
          for _, d in ipairs(DIRS) do
            local px, py = o.x - DELTA[d][1], o.y - DELTA[d][2]
            local tx, ty = o.x + DELTA[d][1], o.y + DELTA[d][2]
            local landing = m:inBounds(tx, ty) and m:isWalkableCell(tx, ty) and not cells[tx .. ":" .. ty]
              and not m:warpAtCell(tx, ty) and not m:warpPadOrHoleAt(tx, ty)
            if plain(m, cells, px, py) and landing then
              return { map = mapId, x = px, y = py, facing = d, ox = o.x, oy = o.y, npc = mapId .. "_obj_" .. o.index }
            end
          end
        end
      end
    end
  end
  fail("no_boulder_spot")
end

local FIND = { cut = cutSpot, surf = surfSpot, spin = spinSpot, elevator = elevatorSpot, strength = boulderSpot }

-- every map trainer of a map beaten, so nobody sights the player
-- (checkTrainerSight skips beaten ones, OC:4372-4420; the header's event,
-- Data:trainerHeader, src/core/Data.lua:425-428)
local function beatTrainers(game, mapId)
  local def = game.data.maps[mapId]
  local n = 0
  for _, o in ipairs(def and def.objects or {}) do
    if o.trainerClass then
      game.save.defeatedTrainers[mapId .. "_obj_" .. o.index] = true
      local header = game.data:trainerHeader(def.label, o.index)
      if header and header.event then game.save.flags[header.event] = true end
      n = n + 1
    end
  end
  return n
end

local function plainCell(game, mapId)
  local m, def = mapQuery(game, mapId)
  if not m then fail("no_map id=" .. word(mapId)) end
  local cells = objectCells(def)
  local w, h = def.width * 2, def.height * 2
  for y = math.floor(h / 2), h - 2 do
    for x = 1, w - 2 do
      if plain(m, cells, x, y) then return x, y end
    end
  end
  fail("no_plain_cell id=" .. word(mapId))
end

-- ------------------------------------------------------------ the driver

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local arrived = false
  local pushedBattles = 0
  local target
  local Runtime = require("src.mods.Runtime")
  Runtime.events:on("screen.pushed", function(payload)
    local state = type(payload) == "table" and payload.state or nil
    if type(state) == "table" and state.isBattleState then
      pushedBattles = pushedBattles + 1
      print(("KB_BATTLE_PUSHED count=%d"):format(pushedBattles))
    end
  end, 0, "kb-field-driver")

  local function warp(mapId, x, y, facing)
    arrived = false
    local world = require("src.world.WorldAPI").new(game, "kb-field-driver")
    local ok, err = world:warpTo(mapId, x, y, facing, { onDone = function() arrived = true end })
    if not ok then fail("warp " .. word(err)) end
  end

  local function landed(mapId)
    local ow = overworld(game)
    return arrived and ow and ow.map and ow.map.id == mapId and idle(game)
  end

  local function at(x, y)
    local ow = overworld(game)
    return ow and ow.map and ow.map.id == target.map and ow.player.cellX == x and ow.player.cellY == y
  end

  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    if phase == "ready" then
      if status and status.ready then
        status.configure({ port = PORT })
        -- New Game without Oak's speech (src/core/Game.lua:171-198)
        game:startNewGame({ intro = false })
        phase = "world"
      end
    elseif phase == "world" then
      local ow = overworld(game)
      if ow and game.stack:top() == ow and ow.map then
        local save, data = game.save, game.data
        local find = FIND[SCENE]
        if not find then fail("unknown_scene") end
        local species
        for id, def in pairs(data.pokemon) do
          if type(def) == "table" and def.dex == STARTER_DEX and (species == nil or id < species) then species = id end
        end
        if not species then fail("no_starter_species") end
        -- level 100 and REPEL steps: the engine's own REPEL filter keeps every
        -- wild Pokemon weaker than the lead away (OC:4793-4799), so no surfing
        -- encounter interrupts a scene
        local mon = require("src.pokemon.Pokemon").new(data, species, 100)
        save.repelSteps = 9999
        require("src.battle.BattleState").stampOT(save, mon)
        local need = NEEDS[SCENE]
        if need then
          if not data.moves[need.move] then fail("no_move id=" .. word(need.move)) end
          mon.moves = mon.moves or {}
          if #mon.moves >= 4 then table.remove(mon.moves) end
          mon.moves[#mon.moves + 1] = { id = need.move, pp = data.moves[need.move].pp or 10 }
          save.inventory[need.badge] = true
        end
        if not require("src.pokemon.Party").add(save.party, mon) then fail("party_full") end
        target = find(game)
        local beaten = beatTrainers(game, target.map)
        target.beaten = beaten
        warp(target.map, target.x, target.y, target.facing)
        phase = "scene"
      end
    elseif phase == "scene" then
      if landed(target.map) then
        local ow = overworld(game)
        local p = ow.player
        local extra = ""
        if SCENE == "cut" then
          extra = (" tx=%s ty=%s bx=%s by=%s"):format(word(target.tx), word(target.ty), word(target.bx),
            word(target.by))
        elseif SCENE == "spin" then
          extra = (" sx=%s sy=%s steps=%s"):format(word(target.sx), word(target.sy), word(target.steps))
        elseif SCENE == "elevator" then
          extra = (" wx=%s wy=%s"):format(word(target.wx), word(target.wy))
        elseif SCENE == "strength" then
          extra = (" ox=%s oy=%s npc=%s"):format(word(target.ox), word(target.oy), word(target.npc))
        end
        print(("KB_SCENE scene=%s map=%s x=%s y=%s facing=%s beaten=%s party=%d port=%s%s"):format(
          SCENE, word(ow.map.id), word(p.cellX), word(p.cellY), word(p.facing), word(target.beaten),
          #game.save.party, word(status.port), extra))
        phase = SCENE == "cut" and "cutting" or "serve"
      end
    elseif phase == "cutting" then
      -- the engine's own record of the cut, then the player back on the
      -- scene cell, idle: away and back again
      local ow = overworld(game)
      local cuts = ow and ow.cutBlocks and ow.cutBlocks[target.map]
      if type(cuts) == "table" and #cuts > 0 and at(target.x, target.y) and idle(game)
          and ow.player.facing == OPPOSITE[target.facing] then
        print(("KB_CUT count=%d"):format(#cuts))
        local x, y = plainCell(game, AWAY_MAP)
        warp(AWAY_MAP, x, y, "down")
        phase = "away"
      end
    elseif phase == "away" then
      if landed(AWAY_MAP) then
        print(("KB_AWAY map=%s"):format(AWAY_MAP))
        warp(target.map, target.x, target.y, target.facing)
        phase = "back"
      end
    elseif phase == "back" then
      if landed(target.map) then
        local ow = overworld(game)
        local regrown = ow.map:blockAt(target.bx, target.by) == target.block
        print(("KB_BACK map=%s regrown=%s"):format(word(ow.map.id), word(regrown)))
        phase = "serve"
      end
    end
    if phase ~= "ready" and phase ~= "world" and status and status.quitRequested then
      local ow = overworld(game)
      print(("KB_END map=%s battles_pushed=%d"):format(word(ow and ow.map and ow.map.id), pushedBattles))
      print(("KB_QUIT sessions=%s code=%s"):format(word(status.sessions), word(status.quitCode)))
      return
    end
    if love.timer.getTime() - started > SECONDS_LIMIT then
      fail("timeout phase=" .. phase)
    end
    game = coroutine.yield() or game
  end
end
