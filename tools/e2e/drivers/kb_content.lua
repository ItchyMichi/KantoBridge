-- Kanto Bridge test driver (M8 wave 2 e2e: static encounters, gifts, trades,
-- the Name Rater, gyms, the daycare): moves gen1-bridge to the test port,
-- starts a new game without the intro, sets up the party the scene needs
-- (Pokemon.new + Party.add, src/pokemon/Pokemon.lua:60-88,
-- src/pokemon/Party.lua:7-13, the OT stamped as the engine stamps gifts,
-- BattleState.stampOT :698-705), REPEL steps (no wild encounter: with the
-- strong lead the engine's REPEL keeps them away, OC:4934-4940), beats the
-- scene map's other trainers, and warps the player onto a plain cell next to
-- the scene's object, facing it, through the supported mod.world facade
-- (WorldAPI:warpTo, src/world/WorldAPI.lua:358-371). The scenes (KB_SCENE),
-- found with the engine's own data, map scripts and map queries (Map.new,
-- src/world/Map.lua:112; MapScripts.talkScript, src/script/MapScripts.lua
-- :239-243; OverworldState.objectVisible, OC:232-249):
--
--   static     a visible static Pokemon object (pokemon + level; on Red/Blue
--              every one has a hand-ported talk script ending in
--              static_battle, data/scripts/flavor/power_plant.lua and
--              friends), POWER_PLANT first. Once the object is no longer
--              visible (its toggle or beat record) and the world is idle, the
--              driver warps onto the same cell (a reload of the map:
--              KB_BEATEN, then KB_BACK present=<is it there>).
--   ghost      the ghost Marowak's trigger (POKEMON_TOWER_6F (10,16),
--              data/scripts/story3.lua:134-138) one step away, the
--              SILPH_SCOPE in the bag: its battle is pushed directly
--              (story3.lua:140-194)
--   boss       Giovanni in the Rocket Hideout (ROCKET_HIDEOUT_B4F,
--              TEXT_ROCKETHIDEOUTB4F_GIOVANNI): his talk script pushes his
--              battle directly (story3.lua:458-513)
--   gift       an object whose talk rows give a Pokemon (give_pokemon),
--              CELADON_MANSION_ROOF_HOUSE's ball first; one party member
--   trade      an object whose talk rows trade (Commands.trade; the trade's
--              field.trades record names the species wanted and given); one
--              party member of the wanted species
--   namerater  the Name Rater (NAME_RATERS_HOUSE); one party member
--   gym        Brock (PEWTER_GYM, trainerClass OPP_BROCK); the gym's other
--              trainers beaten
--   daycare    the daycare gentleman (DAYCARE); two party members; the
--              marker names a free direction to walk and back (walk=)
--   safari     a Safari Zone game under way (save.safari = { balls, steps },
--              BattleState:makeSafari's state, src/battle/BattleState.lua
--              :1076-1083) on a SAFARI_ZONE_* grass cell with a grass
--              neighbour (walk=), no REPEL; the driver answers the engine's
--              own catch.rate hook (BattleState:catchAttempt, :2830-2843)
--              with a sure catch, so the first SAFARI BALL thrown catches
--
-- In every scene, when gen1-bridge first holds a battle for Cobblemon
-- (status.battleHeld), the driver reports whether the hidden game plays the
-- Gen 1 battle theme the battle's kind calls for (KB_HELD music=true|false|
-- none: Music.current against data.audio.battle, src/core/Music.lua:461-468,
-- :481-485). It prints KB_BATTLE_PUSHED for any native battle screen and
-- serves until gen1-bridge reports a debug.quit (exit 0) or the time limit
-- passes (exit 1).
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- (refs/gen1recomp/main.lua:755-759, :1136-1157). Data safety: prints only
-- markers, map and class symbols, cells, dex numbers, levels, counts and
-- booleans -- never a species, a name, a block id or any text.

io.stdout:setvbuf("no") -- LÖVE block-buffers stdout; this also unbuffers mod.log

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27182
local SCENE = os.getenv("KB_SCENE") or "static"
local EXTRA_SECONDS = tonumber(os.getenv("KB_DRIVER_EXTRA") or "") or 0 -- the runner's export allowance
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or (600 + EXTRA_SECONDS)
local LEAD_DEX = 7
local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
local DIRS = { "up", "down", "left", "right" }
local OPPOSITE = { up = "down", down = "up", left = "right", right = "left" }

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

local function dexOf(game, species)
  local def = game.data.pokemon[species]
  return def and def.dex
end

local function speciesOfDex(game, dex)
  local found
  for id, def in pairs(game.data.pokemon) do
    if type(def) == "table" and def.dex == dex and (found == nil or id < found) then found = id end
  end
  return found
end

local function visible(game, mapId, o)
  local OverworldState = require("src.world.OverworldController")
  return OverworldState.objectVisible(game.save, mapId, o) and true or false
end

-- where to stand to talk to an object: a plain cell next to it, or across a
-- counter (the A press reaches over one, OC:2655-2660), facing it
local function besideObject(game, mapId, o)
  local m, def = mapQuery(game, mapId)
  if not m then return nil end
  local cells = objectCells(def)
  for _, d in ipairs(DIRS) do
    local px, py = o.x - DELTA[d][1], o.y - DELTA[d][2]
    if plain(m, cells, px, py) then
      return { map = mapId, x = px, y = py, facing = d, ox = o.x, oy = o.y, npc = mapId .. "_obj_" .. o.index }
    end
    if m:inBounds(px, py) and m:isCounterCell(px, py) then
      local qx, qy = px - DELTA[d][1], py - DELTA[d][2]
      if plain(m, cells, qx, qy) then
        return { map = mapId, x = qx, y = qy, facing = d, ox = o.x, oy = o.y, npc = mapId .. "_obj_" .. o.index }
      end
    end
  end
  return nil
end

-- every map trainer of a map beaten, so nobody sights the player
-- (checkTrainerSight skips beaten ones, OC:4506-4554; the header's event,
-- Data:trainerHeader, src/core/Data.lua:426-429); `except` stays unbeaten
local function beatTrainers(game, mapId, except)
  local def = game.data.maps[mapId]
  local n = 0
  for _, o in ipairs(def and def.objects or {}) do
    if o.trainerClass and o.trainerClass ~= except then
      game.save.defeatedTrainers[mapId .. "_obj_" .. o.index] = true
      local header = game.data:trainerHeader(def.label, o.index)
      if header and header.event then game.save.flags[header.event] = true end
      n = n + 1
    end
  end
  return n
end

-- ------------------------------------------------------------ the scenes

local function staticSpot(game)
  for _, mapId in ipairs(mapOrder(game, { "POWER_PLANT" })) do
    local def = game.data.maps[mapId]
    for _, o in ipairs(def and def.objects or {}) do
      if o.pokemon and type(o.level) == "number" and visible(game, mapId, o) and dexOf(game, o.pokemon) then
        local spot = besideObject(game, mapId, o)
        if spot then
          spot.dex, spot.level, spot.obj = dexOf(game, o.pokemon), o.level, o
          return spot
        end
      end
    end
  end
  fail("no_static_spot")
end

-- one step from the ghost Marowak's trigger cell (story3.lua:134-138)
local GHOST_MAP, GHOST_X, GHOST_Y, GHOST_SPECIES, GHOST_LEVEL = "POKEMON_TOWER_6F", 10, 16, "MAROWAK", 30
local function ghostSpot(game)
  local m, def = mapQuery(game, GHOST_MAP)
  if not m then fail("no_ghost_map") end
  local cells = objectCells(def)
  if not m:isWalkableCell(GHOST_X, GHOST_Y) then fail("ghost_trigger_not_walkable") end
  for _, d in ipairs(DIRS) do
    local px, py = GHOST_X - DELTA[d][1], GHOST_Y - DELTA[d][2]
    if plain(m, cells, px, py) then
      return { map = GHOST_MAP, x = px, y = py, facing = d, ox = GHOST_X, oy = GHOST_Y, npc = "none" }
    end
  end
  fail("no_ghost_spot")
end

-- an object whose talk rows have a `verb` row (and pass `accept`)
local function talkRowsSpot(game, verb, prefer, accept)
  local MapScripts = require("src.script.MapScripts")
  for _, mapId in ipairs(mapOrder(game, prefer)) do
    local def = game.data.maps[mapId]
    for _, o in ipairs(def and def.objects or {}) do
      local script = o.text and MapScripts.talkScript(mapId, o.text) or nil
      if type(script) == "table" and visible(game, mapId, o) then
        for _, row in ipairs(script) do
          if type(row) == "table" and row[1] == verb and (not accept or accept(row)) then
            local spot = besideObject(game, mapId, o)
            if spot then
              spot.row = row
              return spot
            end
          end
        end
      end
    end
  end
  fail("no_" .. verb .. "_spot")
end

-- the object with a given text id on a map
local function textSpot(game, mapId, textId)
  local def = game.data.maps[mapId]
  for _, o in ipairs(def and def.objects or {}) do
    if o.text == textId and visible(game, mapId, o) then
      local spot = besideObject(game, mapId, o)
      if spot then return spot end
    end
  end
  fail("no_spot id=" .. word(textId))
end

local function classSpot(game, mapId, cls)
  local def = game.data.maps[mapId]
  for _, o in ipairs(def and def.objects or {}) do
    if o.trainerClass == cls and visible(game, mapId, o) then
      local spot = besideObject(game, mapId, o)
      if spot then return spot end
    end
  end
  fail("no_spot cls=" .. word(cls))
end

-- a Safari Zone grass cell with a grass neighbour to walk to and back
local function safariSpot(game)
  for _, mapId in ipairs(mapOrder(game, { "SAFARI_ZONE_CENTER", "SAFARI_ZONE_EAST" })) do
    if mapId:find("^SAFARI_ZONE_") then
      local m, def = mapQuery(game, mapId)
      if m then
        local cells = objectCells(def)
        local function grass(x, y)
          return m:inBounds(x, y) and m:isWalkableCell(x, y) and m:isGrassCell(x, y) and not cells[x .. ":" .. y]
            and not m:warpAtCell(x, y)
        end
        for y = 0, def.height * 2 - 1 do
          for x = 0, def.width * 2 - 1 do
            if grass(x, y) then
              for _, d in ipairs(DIRS) do
                if grass(x + DELTA[d][1], y + DELTA[d][2]) then
                  return { map = mapId, x = x, y = y, facing = d, ox = x, oy = y, npc = "none", walk = d }
                end
              end
            end
          end
        end
      end
    end
  end
  fail("no_safari_spot")
end

-- a free direction to step from the spot and back (daycare steps)
local function freeDir(game, spot)
  local m, def = mapQuery(game, spot.map)
  local cells = objectCells(def)
  for _, d in ipairs(DIRS) do
    local x, y = spot.x + DELTA[d][1], spot.y + DELTA[d][2]
    if d ~= spot.facing and plain(m, cells, x, y) then return d end
  end
  return nil
end

-- ------------------------------------------------------------ the driver

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local arrived = false
  local pushedBattles = 0
  local heldSeen = false
  local target
  local Runtime = require("src.mods.Runtime")
  Runtime.events:on("screen.pushed", function(payload)
    local state = type(payload) == "table" and payload.state or nil
    if type(state) == "table" and state.isBattleState then
      pushedBattles = pushedBattles + 1
      print(("KB_BATTLE_PUSHED count=%d"):format(pushedBattles))
    end
  end, 0, "kb-content-driver")

  local function warp(mapId, x, y, facing)
    arrived = false
    local world = require("src.world.WorldAPI").new(game, "kb-content-driver")
    local ok, err = world:warpTo(mapId, x, y, facing, { onDone = function() arrived = true end })
    if not ok then fail("warp " .. word(err)) end
  end

  local function landed(mapId)
    local ow = overworld(game)
    return arrived and ow and ow.map and ow.map.id == mapId and idle(game)
  end

  local function newMon(species, level)
    local data, save = game.data, game.save
    local mon = require("src.pokemon.Pokemon").new(data, species, level)
    require("src.battle.BattleState").stampOT(save, mon)
    if not require("src.pokemon.Party").add(save.party, mon) then fail("party_full") end
    return mon
  end

  -- the battle theme the held battle's kind calls for, playing right now
  local function battleMusic(kind)
    local audio = game.data.audio
    local want = audio and audio.battle and audio.battle[kind]
    if want == nil then return "none" end
    local ok, current = pcall(require("src.core.Music").current)
    return tostring(ok and current == want)
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
        save.repelSteps = 9999
        local lead = speciesOfDex(game, LEAD_DEX)
        if not lead then fail("no_lead_species") end
        local extra = ""
        if SCENE == "static" then
          target = staticSpot(game)
          newMon(lead, 100)
          extra = (" dex=%s level=%s"):format(word(target.dex), word(target.level))
        elseif SCENE == "ghost" then
          target = ghostSpot(game)
          newMon(lead, 100)
          if not require("src.inventory.Bag").add(save, "SILPH_SCOPE", 1, data) then fail("no_scope") end
          extra = (" dex=%s level=%s"):format(word(dexOf(game, GHOST_SPECIES)), word(GHOST_LEVEL))
        elseif SCENE == "boss" then
          target = textSpot(game, "ROCKET_HIDEOUT_B4F", "TEXT_ROCKETHIDEOUTB4F_GIOVANNI")
          newMon(lead, 100)
          target.beaten = beatTrainers(game, target.map, "OPP_GIOVANNI")
          extra = (" beaten=%s"):format(word(target.beaten))
        elseif SCENE == "gift" then
          target = talkRowsSpot(game, "give_pokemon", { "CELADON_MANSION_ROOF_HOUSE" }, function(row)
            return data.pokemon[row[2]] ~= nil and type(row[3]) == "number"
          end)
          newMon(lead, 20)
          extra = (" dex=%s level=%s"):format(word(dexOf(game, target.row[2])), word(target.row[3]))
        elseif SCENE == "trade" then
          target = talkRowsSpot(game, "trade", {}, function(row)
            local t = data.field.trades and data.field.trades[row[2]]
            return t ~= nil and data.pokemon[t.give] ~= nil and data.pokemon[t.get] ~= nil
              and not (row[3] and save.flags[row[3]])
          end)
          local t = data.field.trades[target.row[2]]
          newMon(t.give, 20)
          extra = (" givedex=%s getdex=%s level=20"):format(word(dexOf(game, t.give)), word(dexOf(game, t.get)))
        elseif SCENE == "namerater" then
          target = textSpot(game, "NAME_RATERS_HOUSE", "TEXT_NAMERATERSHOUSE_NAME_RATER")
          local mon = newMon(lead, 20)
          extra = (" dex=%s level=20"):format(word(dexOf(game, mon.species)))
        elseif SCENE == "gym" then
          target = classSpot(game, "PEWTER_GYM", "OPP_BROCK")
          newMon(lead, 100)
          target.beaten = beatTrainers(game, target.map, "OPP_BROCK")
          extra = (" beaten=%s"):format(word(target.beaten))
        elseif SCENE == "safari" then
          target = safariSpot(game)
          newMon(lead, 10)
          save.repelSteps = 0
          save.safari = { balls = 30, steps = 500 }
          Runtime.hooks:wrap("catch.rate", function() return true, 3 end, 0, "kb-content-driver")
          extra = (" walk=%s lead=%s"):format(target.walk, word(LEAD_DEX))
        elseif SCENE == "daycare" then
          target = textSpot(game, "DAYCARE", "TEXT_DAYCARE_GENTLEMAN")
          newMon(lead, 20)
          local second = speciesOfDex(game, LEAD_DEX + 1) or lead
          newMon(second, 20)
          target.walk = freeDir(game, target)
          if not target.walk then fail("no_walk_dir") end
          extra = (" walk=%s dex1=%s dex2=%s level=20"):format(target.walk, word(LEAD_DEX),
            word(dexOf(game, second)))
        else
          fail("unknown_scene")
        end
        target.extra = extra
        if SCENE ~= "gym" and SCENE ~= "boss" then beatTrainers(game, target.map) end
        warp(target.map, target.x, target.y, target.facing)
        phase = "scene"
      end
    elseif phase == "scene" then
      if landed(target.map) then
        local ow = overworld(game)
        local p = ow.player
        print(("KB_SCENE scene=%s map=%s x=%s y=%s facing=%s ox=%s oy=%s npc=%s party=%d port=%s%s"):format(
          SCENE, word(ow.map.id), word(p.cellX), word(p.cellY), word(p.facing), word(target.ox), word(target.oy),
          word(target.npc), #game.save.party, word(status.port), target.extra))
        phase = "serve"
      end
    elseif phase == "serve" then
      if not heldSeen and status.battleHeld then
        heldSeen = true
        local kind = (SCENE == "gym") and "gym" or ((SCENE == "static" or SCENE == "ghost") and "wild" or "trainer")
        print(("KB_HELD kind=%s music=%s"):format(kind, battleMusic(kind)))
      end
      if SCENE == "static" and not visible(game, target.map, target.obj) and idle(game) then
        print(("KB_BEATEN npc=%s"):format(word(target.npc)))
        warp(target.map, target.x, target.y, target.facing)
        phase = "back"
      end
    elseif phase == "back" then
      if landed(target.map) then
        local present = false
        for _, n in ipairs(overworld(game).npcs or {}) do
          if n.id == target.npc then present = true end
        end
        print(("KB_BACK map=%s present=%s"):format(word(target.map), word(present)))
        phase = "done"
      end
    end
    if phase ~= "ready" and phase ~= "world" and status and status.quitRequested then
      local ow = overworld(game)
      print(("KB_END map=%s battles_pushed=%d party=%d"):format(word(ow and ow.map and ow.map.id), pushedBattles,
        #game.save.party))
      print(("KB_QUIT sessions=%s code=%s"):format(word(status.sessions), word(status.quitCode)))
      return
    end
    if love.timer.getTime() - started > SECONDS_LIMIT then
      fail("timeout phase=" .. phase)
    end
    game = coroutine.yield() or game
  end
end
