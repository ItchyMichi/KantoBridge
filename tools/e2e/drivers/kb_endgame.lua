-- Kanto Bridge test driver (M8 wave 3 e2e: the Elite Four, the Champion and
-- the Hall of Fame, the Snorlax flute wake, a loss line a trainer's script
-- hands its battle): moves gen1-bridge to the test
-- port, starts a new game without the intro, gives the player one level-100
-- party member (Pokemon.new + Party.add, src/pokemon/Pokemon.lua:60-88,
-- src/pokemon/Party.lua:7-13, the OT stamped as the engine stamps gifts,
-- BattleState.stampOT :698-705) and REPEL steps (no wild encounter, OC:4793-
-- 4799), sets the scene's flags, badges (e4, champion: all eight, the
-- league's own precondition; Badges.list / itemFor,
-- src/inventory/Badges.lua:16-26) and bag, and warps the player onto a plain
-- cell through the supported mod.world facade (WorldAPI:warpTo,
-- src/world/WorldAPI.lua:358-371). The scenes (KB_SCENE), found with the
-- engine's own data and map queries (Map.new, src/world/Map.lua:112; warps'
-- destMap, src/world/Warp.lua:93-111; OverworldState.objectVisible,
-- OC:227-244):
--
--   e4         INDIGO_PLATEAU_LOBBY, one step from the door into
--              LORELEIS_ROOM (walk=): entering runs the room's own walk-in
--              (data/scripts/story4.lua:869-897); once it is over and the
--              world idle, the driver prints the way to a cell beside
--              Lorelei (KB_TALK_ROUTE dirs=); once she is beaten and the
--              world idle, the way onto the exit warp into BRUNOS_ROOM
--              (KB_EXIT_ROUTE dirs=); once BRUNOS_ROOM is entered and idle,
--              KB_NEXT_ROOM. Routes are breadth-first over the engine's own
--              step verdicts (Collision.canMove, src/world/Collision.lua:84-96)
--              on the live map, never across another warp.
--   champion   LANCES_ROOM, Lance beaten, one step from a warp into
--              CHAMPIONS_ROOM (walk=): entering queues the rival's entrance
--              and his battle (data/scripts/story.lua:1141-1163). While the
--              Credits screen is up the driver runs the logic 10 times a
--              frame (Game.driverSpeed, main.lua:263-267: the credits roll
--              is about 5,900 frames); once the Hall of Fame and the credits
--              are over and the game left the overworld (Commands
--              .record_hall_of_fame, src/script/Commands.lua:913-964), it
--              prints KB_END_STATE (the top screen's id, the save's home map).
--   rocket     facing the Game Corner rocket (TEXT_GAMECORNER_ROCKET, whose
--              talk hands engageTrainer his own loss line,
--              data/scripts/story3.lua:597-643): the M8 wave 3 loss-line
--              check on the real engine.
--   snorlax    beside a sleeping Snorlax (ROUTE_12 first; ItemEffects'
--              SNORLAX_ROUTES, src/inventory/ItemEffects.lua:171-174), facing
--              it, the POKE_FLUTE the only item in the bag. Once Snorlax is
--              gone for good (its beat flag) and the world idle, the driver
--              warps onto the same cell (a reload of the map: KB_BEATEN, then
--              KB_BACK present=<is it there>).
--
-- In every scene, when gen1-bridge first holds a battle for Cobblemon
-- (status.battleHeld), the driver reports whether the hidden game plays the
-- Gen 1 battle theme that battle calls for (KB_HELD music=true|false|none),
-- prints KB_BATTLE_PUSHED for any native battle screen, and serves until
-- gen1-bridge reports a debug.quit (exit 0) or the time limit passes (exit 1).
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per logic
-- step (refs/gen1recomp/main.lua:641-645, :993-1011). Data safety: prints
-- only markers, map, screen and NPC id symbols, directions, counts and
-- booleans -- never a species, a name, a team, a coordinate or any text.

io.stdout:setvbuf("no") -- LÖVE block-buffers stdout; this also unbuffers mod.log

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27173
local SCENE = os.getenv("KB_SCENE") or "e4"
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or 900
local LEAD_DEX = 7
local CREDITS_SPEED = 10
local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
local DIRS = { "up", "down", "left", "right" }

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

local function npcId(mapId, o) return mapId .. "_obj_" .. o.index end

-- every map trainer of a map beaten, so nobody sights the player
-- (checkTrainerSight skips beaten ones, OC:4372-4420; the header's event,
-- Data:trainerHeader, src/core/Data.lua:425-428)
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

-- where to stand to take a warp into `dest`: a plain cell next to one of
-- the map's warps to it, and the direction of the step onto the warp
local function doorSpot(game, mapId, dest)
  local m, def = mapQuery(game, mapId)
  if not m then fail("no_map map=" .. word(mapId)) end
  local cells = objectCells(def)
  for _, w in ipairs(def.warps or {}) do
    if w.destMap == dest then
      for _, d in ipairs(DIRS) do
        local x, y = w.x - DELTA[d][1], w.y - DELTA[d][2]
        if plain(m, cells, x, y) then return { map = mapId, x = x, y = y, facing = d, walk = d } end
      end
    end
  end
  fail("no_door_spot map=" .. word(mapId) .. " dest=" .. word(dest))
end

-- where to stand to use the flute on an object: a plain cell next to it,
-- facing it
local function besideObject(game, mapId, o)
  local m, def = mapQuery(game, mapId)
  if not m then return nil end
  local cells = objectCells(def)
  for _, d in ipairs(DIRS) do
    local x, y = o.x - DELTA[d][1], o.y - DELTA[d][2]
    if plain(m, cells, x, y) then return { map = mapId, x = x, y = y, facing = d } end
  end
  return nil
end

local function objectOf(game, mapId, pred)
  local def = game.data.maps[mapId]
  for _, o in ipairs(def and def.objects or {}) do
    if pred(o) then return o end
  end
  return nil
end

-- the shortest way on the live map from the player to a goal cell, as
-- directions: the engine's own step verdicts (Collision.canMove, the NPCs
-- and the player block their cells), never onto a warp cell that isn't the
-- goal. nil when there is none.
local function route(ow, isGoal)
  local Collision = require("src.world.Collision")
  local map, entities = ow.map, ow.entities or {}
  local p = ow.player
  local function key(x, y) return x .. ":" .. y end
  local prev = { [key(p.cellX, p.cellY)] = false }
  local queue, head = { { p.cellX, p.cellY } }, 1
  while head <= #queue and head <= 4096 do
    local x, y = queue[head][1], queue[head][2]
    head = head + 1
    if isGoal(x, y) then
      local dirs, k = {}, key(x, y)
      while prev[k] do
        table.insert(dirs, 1, prev[k].dir)
        k = key(prev[k].x, prev[k].y)
      end
      return dirs
    end
    for _, d in ipairs(DIRS) do
      local nx, ny = x + DELTA[d][1], y + DELTA[d][2]
      local nk = key(nx, ny)
      if prev[nk] == nil and (isGoal(nx, ny) or not map:warpAtCell(nx, ny))
          and Collision.canMove(map, entities, { cellX = x, cellY = y }, d) then
        prev[nk] = { x = x, y = y, dir = d }
        queue[#queue + 1] = { nx, ny }
      end
    end
  end
  return nil
end

local function dirsWord(dirs)
  if dirs == nil then return nil end
  if #dirs == 0 then return "none" end
  return table.concat(dirs, ",")
end

-- ------------------------------------------------------------ the driver

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local arrived = false
  local pushedBattles = 0
  local heldSeen = false
  local target
  local printed = {}
  local creditsSeen, hallSeen = false, false
  local Runtime = require("src.mods.Runtime")
  Runtime.events:on("screen.pushed", function(payload)
    local state = type(payload) == "table" and payload.state or nil
    if type(state) == "table" and state.isBattleState then
      pushedBattles = pushedBattles + 1
      print(("KB_BATTLE_PUSHED count=%d"):format(pushedBattles))
    end
  end, 0, "kb-endgame-driver")

  local function once(name, line)
    if printed[name] then return end
    printed[name] = true
    print(line)
  end

  local function warp(mapId, x, y, facing)
    arrived = false
    local world = require("src.world.WorldAPI").new(game, "kb-endgame-driver")
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

  local function topScreen()
    local top = game.stack:top()
    return type(top) == "table" and top.screenId or nil
  end

  -- all eight badges in the bag (Badges.list / itemFor,
  -- src/inventory/Badges.lua:16-26), as the league's gate requires
  local function giveBadges()
    local Badges = require("src.inventory.Badges")
    local n = 0
    for _, entry in ipairs(Badges.list(game.data) or {}) do
      local item = Badges.itemFor and Badges.itemFor(entry) or entry.item or entry.id
      if type(item) == "string" then
        game.save.inventory[item] = 1
        n = n + 1
      end
    end
    return n
  end

  -- the scenes' setup: target = { map, x, y, facing, walk?, npc?, obj?, extra }
  local function setup()
    local save, data = game.save, game.data
    save.repelSteps = 9999
    local lead = speciesOfDex(game, LEAD_DEX)
    if not lead then fail("no_lead_species") end
    newMon(lead, 100)
    if SCENE == "e4" then
      local badges = giveBadges()
      local t = doorSpot(game, "INDIGO_PLATEAU_LOBBY", "LORELEIS_ROOM")
      local lorelei = objectOf(game, "LORELEIS_ROOM", function(o) return o.trainerClass == "OPP_LORELEI" end)
      if not lorelei then fail("no_lorelei") end
      t.npc = npcId("LORELEIS_ROOM", lorelei)
      t.extra = (" next=BRUNOS_ROOM cls=%s badges=%d"):format(word(lorelei.trainerClass), badges)
      return t
    elseif SCENE == "champion" then
      local badges = giveBadges()
      save.flags.EVENT_BEAT_LANCE = true
      save.flags.EVENT_LANCES_ROOM_LOCK_DOOR = true
      local beaten = beatTrainers(game, "LANCES_ROOM")
      local t = doorSpot(game, "LANCES_ROOM", "CHAMPIONS_ROOM")
      local rival = objectOf(game, "CHAMPIONS_ROOM", function(o) return o.name == "CHAMPIONSROOM_RIVAL" end)
      if not rival then fail("no_rival") end
      t.npc = npcId("CHAMPIONS_ROOM", rival)
      t.extra = (" beaten=%d badges=%d"):format(beaten, badges)
      return t
    elseif SCENE == "rocket" then
      local o = objectOf(game, "GAME_CORNER", function(obj) return obj.text == "TEXT_GAMECORNER_ROCKET" end)
      if not (o and visible(game, "GAME_CORNER", o)) then fail("no_rocket") end
      local t = besideObject(game, "GAME_CORNER", o)
      if not t then fail("no_rocket_spot") end
      t.npc = npcId("GAME_CORNER", o)
      t.extra = (" cls=%s"):format(word(o.trainerClass))
      return t
    elseif SCENE == "snorlax" then
      local Bag = require("src.inventory.Bag")
      for id, qty in pairs(save.inventory or {}) do
        if type(qty) == "number" and qty > 0 and not tostring(id):find("BADGE$") then Bag.remove(save, id, qty) end
      end
      if not Bag.add(save, "POKE_FLUTE", 1, data) then fail("no_flute") end
      save.flags.EVENT_GOT_POKE_FLUTE = true
      for _, mapId in ipairs({ "ROUTE_12", "ROUTE_16" }) do
        local name = mapId:gsub("_", "") .. "_SNORLAX"
        local o = objectOf(game, mapId, function(obj) return obj.name == name end)
        if o and visible(game, mapId, o) then
          local t = besideObject(game, mapId, o)
          if t then
            t.npc, t.obj = npcId(mapId, o), o
            t.beatFlag = "EVENT_BEAT_" .. mapId:gsub("_", "") .. "_SNORLAX"
            local kinds = 0
            for _, qty in pairs(save.inventory) do
              if type(qty) == "number" and qty > 0 then kinds = kinds + 1 end
            end
            t.extra = (" beaten=%d bag=%d"):format(beatTrainers(game, mapId), kinds)
            return t
          end
        end
      end
      fail("no_snorlax_spot")
    end
    fail("unknown_scene")
  end

  -- the e4 scene's routes and the next room
  local function serveE4(ow)
    if not (ow and ow.map and idle(game)) then return end
    local id = ow.map.id
    if id == "LORELEIS_ROOM" and not game.save.flags.EVENT_BEAT_LORELEIS_ROOM_TRAINER_0
        and game.save.flags.EVENT_AUTOWALKED_INTO_LORELEIS_ROOM and not printed.talk then
      local lorelei
      for _, n in ipairs(ow.npcs or {}) do
        if n.id == target.npc then lorelei = n end
      end
      if not lorelei then fail("lorelei_not_here") end
      local dirs = route(ow, function(x, y)
        return math.abs(x - lorelei.cellX) + math.abs(y - lorelei.cellY) == 1
      end)
      if not dirs then fail("no_talk_route") end
      once("talk", ("KB_TALK_ROUTE map=%s dirs=%s steps=%d"):format(id, dirsWord(dirs), #dirs))
    elseif id == "LORELEIS_ROOM" and game.save.flags.EVENT_BEAT_LORELEIS_ROOM_TRAINER_0 and not printed.exit then
      -- Map:warpAtCell gives { index, def } (src/world/Map.lua:133-136)
      local dirs = route(ow, function(x, y)
        local w = ow.map:warpAtCell(x, y)
        return w ~= nil and type(w.def) == "table" and w.def.destMap == "BRUNOS_ROOM"
      end)
      if not dirs then fail("no_exit_route") end
      once("exit", ("KB_EXIT_ROUTE map=%s dirs=%s steps=%d"):format(id, dirsWord(dirs), #dirs))
    elseif id == "BRUNOS_ROOM" then
      once("next", ("KB_NEXT_ROOM map=%s auto=%s"):format(id,
        word(game.save.flags.EVENT_AUTOWALKED_INTO_BRUNOS_ROOM == true)))
    end
  end

  -- the champion scene: the credits at speed, then the end state
  local function serveChampion()
    local screen = topScreen()
    if screen == "HallOfFame" then hallSeen = true end
    if screen == "Credits" then
      creditsSeen = true
      game.driverSpeed = CREDITS_SPEED
    elseif game.driverSpeed ~= nil then
      game.driverSpeed = nil
    end
    if creditsSeen and overworld(game) == nil and screen ~= "Credits" then
      local home = game.save and game.save.player and game.save.player.map
      once("end", ("KB_END_STATE overworld=false hall=%s credits=true top=%s home=%s"):format(
        word(hallSeen), word(screen), word(home)))
    end
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
        target = setup()
        warp(target.map, target.x, target.y, target.facing)
        phase = "scene"
      end
    elseif phase == "scene" then
      if landed(target.map) then
        local ow = overworld(game)
        print(("KB_SCENE scene=%s map=%s facing=%s walk=%s npc=%s party=%d port=%s%s"):format(
          SCENE, word(ow.map.id), word(ow.player.facing), word(target.walk or "none"), word(target.npc or "none"),
          #game.save.party, word(status.port), target.extra or ""))
        phase = "serve"
      end
    elseif phase == "serve" then
      if not heldSeen and status.battleHeld then
        heldSeen = true
        local kind = SCENE == "champion" and "final" or (SCENE == "snorlax" and "wild" or "trainer")
        print(("KB_HELD kind=%s music=%s"):format(kind, battleMusic(kind)))
      end
      local ow = overworld(game)
      if SCENE == "e4" then
        serveE4(ow)
      elseif SCENE == "champion" then
        serveChampion()
      elseif SCENE == "snorlax" and game.save.flags[target.beatFlag] and not visible(game, target.map, target.obj)
          and idle(game) then
        print(("KB_BEATEN npc=%s flag=true"):format(word(target.npc)))
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
      print(("KB_END map=%s battles_pushed=%d party=%d"):format(word(ow and ow.map and ow.map.id or "none"),
        pushedBattles, #game.save.party))
      print(("KB_QUIT sessions=%s code=%s"):format(word(status.sessions), word(status.quitCode)))
      return
    end
    if love.timer.getTime() - started > SECONDS_LIMIT then
      fail("timeout phase=" .. phase)
    end
    game = coroutine.yield() or game
  end
end
