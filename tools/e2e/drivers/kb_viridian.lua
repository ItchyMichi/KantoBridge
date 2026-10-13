-- Kanto Bridge test driver (M7 Viridian City e2e): moves gen1-bridge to the
-- test port, starts a new game without the intro, gives the player a
-- starter (Pokemon.new + Party.add, src/pokemon/Pokemon.lua:60-88,
-- src/pokemon/Party.lua:7-13, the OT stamped as gifts are,
-- BattleState.stampOT :698-705), the story flags that make the Viridian
-- Mart a shop (the parcel delivered: data/scripts/story.lua:12-66), a bag
-- through the engine's own Bag.add (src/inventory/Bag.lua:443-460), and
-- walks it into a scene through the supported mod.world facade
-- (WorldAPI:warpTo, src/world/WorldAPI.lua:358-370): first onto
-- VIRIDIAN_CITY below the scene building's door, so the engine remembers
-- the town as the outdoor side (OverworldState:startWarpTo,
-- OC:5512-5515, rememberOutdoor: lastOutdoor, which the nurse's lastHeal keeps, OC:3987-3994),
-- then into the building. The scene (KB_SCENE, environment) is:
--
--   story  in VIRIDIAN_POKECENTER across the counter from the nurse
--          (KB_SCENE scene=nurse). When the nurse's heal lands
--          (save.lastHeal changes) it prints KB_HEALED with lastHeal's map and
--          town. Once gen1-bridge reports a session.bind (status.sessionBinds)
--          and the world is idle (Game:quickSaveAllowed, src/core/Game.lua
--          :1387-1405), it warps beside a map trainer's line of sight
--          (KB_SCENE scene=trainer_a); once that trainer is beaten, then
--          talked to (world.interacted) and the world idle, beside a second
--          one (KB_SCENE scene=trainer_b).
--   pc     in VIRIDIAN_POKECENTER facing the PC tile
--          (field.hiddenExtras.pcTiles, OC:2938-2953): KB_SCENE scene=pc
--   mart   in VIRIDIAN_MART across the counter from the clerk:
--          KB_SCENE scene=mart
--
-- Trainers are found with the engine's own data and queries, nothing
-- printed but symbols, cells and counts: a visible (objectVisible,
-- OC:232-247), unbeaten STAY trainer facing a pinned direction (NPC.new,
-- src/world/NPC.lua:53-65) with a header range of 2 or more
-- (Data:trainerHeader, src/core/Data.lua:426-429), a line of plain
-- walkable cells 2..min(range, 3) long (checkTrainerSight's rules,
-- OC:4506-4554: a straight line, on screen), and a free cell beside the
-- line's end that no trainer can see, from which one step enters the line.
-- Maps with entry or step scripts (MapScripts.get) are skipped.
--
-- Markers: KB_SAVE_WRITTEN n=<count> for every save.writing (Game:writeSave
-- emits it, src/core/Game.lua:1376-1378), KB_BLACKOUT for world.blacked_out,
-- KB_BATTLE_PUSHED for a native battle screen, KB_SCREEN_PUSHED id=<screen
-- id> for the ShopMenu, BoxMenu and PlayerPC screens. It serves until
-- gen1-bridge reports a debug.quit (exit 0) or the time limit passes
-- (exit 1).
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- (refs/gen1recomp/main.lua:755-759, :1136-1157). Data safety: prints only
-- markers, map, flag, class and item symbols, NPC ids, cells and counts --
-- never a species, a name, a price or any text.

io.stdout:setvbuf("no") -- LÖVE block-buffers stdout; this also unbuffers mod.log

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27190
local SCENE = os.getenv("KB_SCENE") or "story"
local EXTRA_SECONDS = tonumber(os.getenv("KB_DRIVER_EXTRA") or "") or 0 -- the runner's export allowance
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or (900 + EXTRA_SECONDS)
local TOWN, CENTER, MART = "VIRIDIAN_CITY", "VIRIDIAN_POKECENTER", "VIRIDIAN_MART"
local STARTER_DEX = 7
local FLAGS = { "EVENT_GOT_STARTER", "EVENT_GOT_OAKS_PARCEL", "EVENT_OAK_GOT_PARCEL" }
-- a battle item to sell first (bag order), plus a key item
local STOCK = { { "POTION", 3 }, { "TOWN_MAP", 1 } }
-- where map trainers are looked for first; then every other map, sorted
local TRAINER_MAPS = { "VIRIDIAN_FOREST", "ROUTE_3", "ROUTE_24", "ROUTE_25", "ROUTE_4", "ROUTE_6", "ROUTE_9",
                       "ROUTE_11" }
local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
local OPPOSITE = { up = "down", down = "up", left = "right", right = "left" }
local PERP = { up = { "left", "right" }, down = { "left", "right" }, left = { "up", "down" }, right = { "up", "down" } }
local FACING_FROM_RANGE = { DOWN = "down", UP = "up", LEFT = "left", RIGHT = "right" }

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

-- a plain cell to stand on: in bounds, walkable, no object, no warp
local function plain(m, cells, x, y)
  return m:inBounds(x, y) and m:isWalkableCell(x, y) and not cells[x .. ":" .. y] and not m:warpAtCell(x, y)
end

-- the cell outside the town's door into `inside`: the warp's own cell's
-- neighbour (below first) that is plain
local function doorstep(game, inside)
  local m, def = mapQuery(game, TOWN)
  if not m then fail("no_town") end
  local cells = objectCells(def)
  for _, w in ipairs(def.warps or {}) do
    if w.destMap == inside then
      for _, d in ipairs({ "down", "left", "right", "up" }) do
        local x, y = w.x + DELTA[d][1], w.y + DELTA[d][2]
        if plain(m, cells, x, y) then return x, y end
      end
    end
  end
  fail("no_doorstep id=" .. word(inside))
end

-- across a counter from the object whose text entry has `field` (nurse,
-- mart): the cell two away with a counter tile between, and the facing
local function acrossCounter(game, mapId, field)
  local m, def = mapQuery(game, mapId)
  if not m then fail("no_map id=" .. word(mapId)) end
  local cells = objectCells(def)
  for _, o in ipairs(def.objects or {}) do
    local entry = o.text and game.data:textEntry(def.label, o.text) or nil
    if type(entry) == "table" and entry[field] then
      for d, v in pairs(DELTA) do
        local cx, cy, px, py = o.x + v[1], o.y + v[2], o.x + 2 * v[1], o.y + 2 * v[2]
        if m:inBounds(cx, cy) and m:isCounterCell(cx, cy) and plain(m, cells, px, py) then
          return px, py, OPPOSITE[d], o, entry
        end
      end
    end
  end
  fail("no_counter_spot field=" .. field)
end

local function pcSpot(game)
  local extras = game.data.field and game.data.field.hiddenExtras
  local list = extras and extras.pcTiles and extras.pcTiles[CENTER] or {}
  local m, def = mapQuery(game, CENTER)
  local cells = objectCells(def)
  for _, h in ipairs(list) do
    local facing = h.facing or "up"
    local v = DELTA[facing]
    if v then
      local x, y = h.x - v[1], h.y - v[2]
      if plain(m, cells, x, y) then return x, y, facing, h end
    end
  end
  fail("no_pc_spot")
end

-- ------------------------------------------------------------ trainers

local function trainerScenes(game, count, skip)
  local data, save = game.data, game.save
  local OverworldState = require("src.world.OverworldController")
  local MapScripts = require("src.script.MapScripts")
  local ids, listed = {}, {}
  for _, id in ipairs(TRAINER_MAPS) do ids[#ids + 1], listed[id] = id, true end
  local rest = {}
  for id in pairs(data.maps) do
    if not listed[id] then rest[#rest + 1] = id end
  end
  table.sort(rest)
  for _, id in ipairs(rest) do ids[#ids + 1] = id end
  local out = {}
  for _, mapId in ipairs(ids) do
    local view = MapScripts.get and MapScripts.get(mapId) or nil
    local scripted = type(view) == "table" and (view.onEnter or view.onStep or view.noSight)
    local m, def = mapQuery(game, mapId)
    if m and not scripted and type(def.objects) == "table" then
      local cells = objectCells(def)
      -- how many trainers may see each cell (any direction for a turning one)
      local seen = {}
      local trainers = {}
      for _, o in ipairs(def.objects) do
        if o.trainerClass and OverworldState.objectVisible(save, mapId, o) then
          local header = data:trainerHeader(def.label, o.index)
          local range = header and header.range or 0
          local pinned = o.movement == "STAY" and FACING_FROM_RANGE[o.range] or nil
          for d, v in pairs(DELTA) do
            if pinned == nil or pinned == d then
              for k = 1, range do
                local key = (o.x + v[1] * k) .. ":" .. (o.y + v[2] * k)
                seen[key] = (seen[key] or 0) + 1
              end
            end
          end
          trainers[#trainers + 1] = { o = o, header = header, range = range, facing = pinned }
        end
      end
      for _, t in ipairs(trainers) do
        local o = t.o
        local npcId = mapId .. "_obj_" .. o.index
        local beaten = save.defeatedTrainers and save.defeatedTrainers[npcId]
          or (t.header and t.header.event and save.flags[t.header.event])
        if t.facing and t.range >= 2 and not beaten and not skip[npcId] then
          local v = DELTA[t.facing]
          for dist = math.min(t.range, 3), 2, -1 do
            local clear = true
            for k = 1, dist do
              if not plain(m, cells, o.x + v[1] * k, o.y + v[2] * k) then clear = false end
            end
            local sx, sy = o.x + v[1] * dist, o.y + v[2] * dist
            -- only this trainer sees the cell the step lands on
            if clear and seen[sx .. ":" .. sy] == 1 then
              for _, p in ipairs(PERP[t.facing]) do
                local ax, ay = sx + DELTA[p][1], sy + DELTA[p][2]
                if plain(m, cells, ax, ay) and not seen[ax .. ":" .. ay] and not m:isGrassCell(ax, ay) then
                  out[#out + 1] = { map = mapId, npc = npcId, cls = o.trainerClass, x = ax, y = ay,
                                    dir = OPPOSITE[p], dist = dist, tx = o.x, ty = o.y }
                  skip[npcId] = true
                  if #out >= count then return out end
                  break
                end
              end
            end
            if skip[npcId] then break end
          end
        end
      end
    end
  end
  return out
end

-- ------------------------------------------------------------ the driver

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local arrived = false
  local saves, pushedBattles = 0, 0
  local healSeen, target, trainers
  local talkedTo = {} -- npc ids talked to (world.interacted, OC:2645-2648)
  local beatenSeen = false
  local Runtime = require("src.mods.Runtime")
  Runtime.events:on("save.writing", function()
    saves = saves + 1
    print(("KB_SAVE_WRITTEN n=%d"):format(saves))
  end, 0, "kb-viridian-driver")
  Runtime.events:on("world.blacked_out", function() print("KB_BLACKOUT") end, 0, "kb-viridian-driver")
  Runtime.events:on("world.interacted", function(payload)
    local target = type(payload) == "table" and payload.target or nil
    if type(target) == "table" and type(target.id) == "string" then talkedTo[target.id] = true end
  end, 0, "kb-viridian-driver")
  Runtime.events:on("screen.pushed", function(payload)
    local state = type(payload) == "table" and payload.state or nil
    if type(state) ~= "table" then return end
    if state.isBattleState then
      pushedBattles = pushedBattles + 1
      print(("KB_BATTLE_PUSHED count=%d"):format(pushedBattles))
    elseif state.screenId == "ShopMenu" or state.screenId == "BoxMenu" or state.screenId == "PlayerPC" then
      print(("KB_SCREEN_PUSHED id=%s"):format(word(state.screenId)))
    end
  end, 0, "kb-viridian-driver")

  local function warp(mapId, x, y, facing)
    arrived = false
    local world = require("src.world.WorldAPI").new(game, "kb-viridian-driver")
    local ok, err = world:warpTo(mapId, x, y, facing, { onDone = function() arrived = true end })
    if not ok then fail("warp " .. word(err)) end
  end

  local function landed(mapId)
    local ow = overworld(game)
    return arrived and ow and ow.map and ow.map.id == mapId and idle(game)
  end

  local function trainerScene(name, t)
    local ow = overworld(game)
    local p = ow.player
    print(("KB_SCENE scene=%s map=%s x=%s y=%s facing=%s dir=%s npc=%s cls=%s dist=%s tx=%s ty=%s port=%s"):format(
      name, word(ow.map.id), word(p.cellX), word(p.cellY), word(p.facing), word(t.dir), word(t.npc), word(t.cls),
      word(t.dist), word(t.tx), word(t.ty), word(PORT)))
  end

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
        local species
        for id, def in pairs(data.pokemon) do
          if type(def) == "table" and def.dex == STARTER_DEX and (species == nil or id < species) then species = id end
        end
        if not species then fail("no_starter_species") end
        local mon = require("src.pokemon.Pokemon").new(data, species, 12)
        require("src.battle.BattleState").stampOT(save, mon)
        if not require("src.pokemon.Party").add(save.party, mon) then fail("party_full") end
        mon.hp = math.max(1, math.floor(mon.hp / 3)) -- hurt, so the nurse's heal shows
        for _, flag in ipairs(FLAGS) do save.flags[flag] = true end
        local Bag = require("src.inventory.Bag")
        save.inventory, save.bagOrder = {}, {}
        for _, row in ipairs(STOCK) do
          if not data.items[row[1]] then fail("item_missing id=" .. word(row[1])) end
          if not Bag.add(save, row[1], row[2], data) then fail("bag_add id=" .. word(row[1])) end
        end
        if SCENE == "mart" then
          local x, y, facing, o, entry = acrossCounter(game, MART, "mart")
          -- the stock's first item id and the stock's size (symbols and counts only)
          local stock = type(entry.mart) == "table" and entry.mart or {}
          target = { map = MART, x = x, y = y, facing = facing, scene = "mart", px = o.x, py = o.y,
                     extra = ("first=%s stock=%d"):format(word(stock[1]), #stock) }
        elseif SCENE == "pc" then
          local x, y, facing, h = pcSpot(game)
          target = { map = CENTER, x = x, y = y, facing = facing, scene = "pc", px = h.x, py = h.y }
        else
          local x, y, facing, o = acrossCounter(game, CENTER, "nurse")
          target = { map = CENTER, x = x, y = y, facing = facing, scene = "nurse", px = o.x, py = o.y }
        end
        local dx, dy = doorstep(game, target.map)
        warp(TOWN, dx, dy, "up")
        phase = "town"
      end
    elseif phase == "town" then
      if landed(TOWN) then
        warp(target.map, target.x, target.y, target.facing)
        phase = "inside"
      end
    elseif phase == "inside" then
      if landed(target.map) then
        local ow = overworld(game)
        local p = ow.player
        healSeen = game.save.lastHeal
        print(("KB_SCENE scene=%s map=%s x=%s y=%s facing=%s tx=%s ty=%s money=%s party=%d port=%s%s"):format(
          target.scene, word(ow.map.id), word(p.cellX), word(p.cellY), word(p.facing), word(target.px),
          word(target.py), word(game.save.money and game.save.money > 0), #game.save.party, word(status.port),
          target.extra and (" " .. target.extra) or ""))
        phase = SCENE == "story" and "nurse" or "serve"
      end
    elseif phase == "nurse" then
      local heal = game.save.lastHeal
      if heal ~= healSeen and type(heal) == "table" then
        local out = type(heal.outdoor) == "table" and heal.outdoor.id or nil
        print(("KB_HEALED map=%s outdoor=%s"):format(word(heal.map), word(out)))
        phase = "bind"
      end
    elseif phase == "bind" then
      if (status.sessionBinds or 0) >= 1 and idle(game) then
        trainers = trainerScenes(game, 2, {})
        if #trainers < 2 then fail("trainers found=" .. #trainers) end
        local t = trainers[1]
        warp(t.map, t.x, t.y, t.dir)
        phase = "trainer_a"
      end
    elseif phase == "trainer_a" then
      if landed(trainers[1].map) then
        trainerScene("trainer_a", trainers[1])
        phase = "fight_a"
      end
    elseif phase == "fight_a" then
      local t = trainers[1]
      if not beatenSeen and game.save.defeatedTrainers[t.npc] then
        beatenSeen, talkedTo[t.npc] = true, nil -- only a talk after the win counts
      end
      -- beaten, then talked to (its after-battle line), and the world idle again
      if beatenSeen and talkedTo[t.npc] and idle(game) then
        local b = trainers[2]
        warp(b.map, b.x, b.y, b.dir)
        phase = "trainer_b"
      end
    elseif phase == "trainer_b" then
      if landed(trainers[2].map) then
        trainerScene("trainer_b", trainers[2])
        phase = "serve"
      end
    end
    if phase ~= "ready" and phase ~= "world" and status and status.quitRequested then
      local ow = overworld(game)
      print(("KB_END map=%s party=%d saves=%d battles_pushed=%d"):format(
        word(ow and ow.map and ow.map.id), #game.save.party, saves, pushedBattles))
      print(("KB_QUIT sessions=%s code=%s"):format(word(status.sessions), word(status.quitCode)))
      return
    end
    if love.timer.getTime() - started > SECONDS_LIMIT then
      fail("timeout phase=" .. phase)
    end
    game = coroutine.yield() or game
  end
end
