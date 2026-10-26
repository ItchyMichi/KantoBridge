-- Kanto Bridge test driver: Crystal's story spots for the real client
-- (docs/specs/STORY_EVENTS_PLAN.md §5), driven by tools/e2e/story_spots_g2.py
-- while the real dev client follows this game. A fresh empty save slot
-- labelled KB_G2SPOTS_TEST is made active, driver mode's own start (Gen 2
-- boots straight into the field on a new game, src/core/Game2.lua:1263-1274)
-- is moved below New Bark Town's first door; then the driver serves (paced to
-- KB_PACE frames a second, default 60, as kb_serve.lua: a driver turns the
-- engine's own pacing off) and runs the commands it finds in
-- KB_SPOT_COMMANDS, one Lua table literal a line:
--
--   { id = 1, op = "go", map = "WILLS_ROOM", x = 5, y = 3, facing = "up",
--     events = { [778] = true }, flags = {}, scenes = { WILLS_ROOM = 1 },
--     mapscenes = {}, mem = {}, hour = 12, day = 1, flash = false,
--     decorations = { bed = 2, carpet = 0, plant = 0, poster = 16 } }
--
-- go: the World's story state back to the new game's (event flags, engine
-- flags, badges, map scenes, variable sprites, the script memory, the
-- bedroom's decorations, as kb_g2story.lua keeps them), the command's state
-- and clock set (hour 12 and Monday unless it says), then a warp onto the
-- map's cell
-- (World:warpToMapId: the load runs NEWMAP, TILES and OBJECTS at once;
-- without x and y the map's first warp's cell). Once the field has stood idle
-- for a second (a script still holding it after 10 s is halted) Flash is lit
-- when the command asks (World.flashUsed, as the field move leaves it) and it
-- prints KB_SPOT id= map= x= y= blocks= dark= flash= halted= held= busy= (what
-- held the field when it was halted, and what holds it now). The client sees
-- the warp and follows. When the map the player is on is the command's map, a
-- hop to another map comes first, so the callbacks run again (not with
-- nohop = true: the survey's screenshots, tools/e2e/map_shots.py).
--
-- Markers: KB_BOOTED slot= home= precleaned=, KB_SERVING map= x= y=,
-- KB_SPOT ..., KB_SPOT_FAIL id= reason=, KB_CLEANED deleted= active=, KB_QUIT
-- code=; KB_FAIL <reason>. Map symbols, cells, ids, counts and booleans only.
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- with the Game (refs/gen1recomp/main.lua:755-759, :1136-1157). Paths cited
-- are refs/gen1recomp's (Gen1Recomp v0.3.85).

io.stdout:setvbuf("no")

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27242
local PACE = tonumber(os.getenv("KB_PACE") or "") or 60
local DATASET_MEMO = tonumber(os.getenv("KB_DATASET_MEMO") or "") or 0
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or 7200
local COMMANDS = os.getenv("KB_SPOT_COMMANDS")
local LABEL = "KB_G2SPOTS_TEST"
local TOWN, HOP_ALT = "NEW_BARK_TOWN", "ROUTE_29"
local DEFAULT_HOUR, DEFAULT_DAY = 12, 1
local SETTLE_FRAMES = 600 -- then a script still holding the field is halted

local SaveData = require("src.core.SaveData")
local GameVersion = require("src.core.GameVersion")

local floor = math.floor
local function word(v) return (tostring(v):gsub("[^%w_.:+%-]", "?")) end
local function num(id) return tonumber(tostring(id):match("^slot(%d+)$")) or 0 end
local function version() return GameVersion.get() end
local function active() return SaveData.activeSlot(version()) end

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

local function slots()
  local ok, list = pcall(SaveData.listSlots, version())
  return ok and type(list) == "table" and list or {}
end

-- Deletes every test slot (never another), making `home` active first.
local function cleanup(home)
  local deleted, v = 0, version()
  for _, s in ipairs(slots()) do
    if type(s) == "table" and s.label == LABEL then
      if active() == s.id then
        local back = home
        if not back then
          for _, o in ipairs(slots()) do
            if type(o) == "table" and o.label ~= LABEL then back = o.id break end
          end
        end
        if back and back ~= s.id then SaveData.setActiveSlot(v, back) end
      end
      if SaveData.deleteSlot(v, s.id) then deleted = deleted + 1 end
    end
  end
  if home and active() ~= home then SaveData.setActiveSlot(v, home) end
  return deleted, active()
end

local homeRef
local function fail(reason)
  local ok, deleted = pcall(cleanup, homeRef)
  print(("KB_FAIL %s cleaned=%s"):format(reason, word(ok and deleted or "error")))
  love.event.quit(1)
  while true do coroutine.yield() end
end

local function topState(game)
  local stack = game and game.stack
  return stack and stack.top and stack:top() or nil
end

-- The field is up and settled (kb_g2story.lua's test).
local function fieldReady(game)
  local w = game and game.world
  if not (game and game.phase == "play" and w and w.map and w.player) then return false end
  if topState(game) ~= nil then return false end
  local ok, accepts = pcall(w.acceptsMenuInput, w)
  if not (ok and accepts) then return false end
  local p = w.player
  if p.moving or (tonumber(p.turnTimer) or 0) > 0 or p.jumping then return false end
  return not (w.mapSetup or w.pendingSceneScript or w.pendingWarp or w.waitButton or w.peopleDirty)
end

local function copy(t)
  local out = {}
  for k, v in pairs(type(t) == "table" and t or {}) do out[k] = type(v) == "table" and copy(v) or v end
  return out
end
local function refill(dst, src)
  if type(dst) ~= "table" then return end
  for k in pairs(dst) do dst[k] = nil end
  for k, v in pairs(src) do dst[k] = type(v) == "table" and copy(v) or v end
end

local function snapshot(game)
  local w, save = game.world, game.save or {}
  local player = save.player or {}
  return { events = w.events:serialize(), scenes = copy(w.mapScenes), engine = copy(save.engineFlags),
           badges = copy(player.badges), kanto = copy(player.kantoBadges), sprites = copy(w.variableSprites),
           mem = w.vm and w.vm.serializeMem and w.vm:serializeMem() or copy(w.vm and w.vm.mem),
           decorations = copy(save.decorations) }
end
local function restore(game, base)
  local w, save = game.world, game.save or {}
  local player = save.player or {}
  w.events:restore(copy(base.events))
  if type(w.mapScenes) == "table" then refill(w.mapScenes, base.scenes) end
  if type(save.engineFlags) == "table" then refill(save.engineFlags, base.engine) end
  if type(player.badges) == "table" then refill(player.badges, base.badges) end
  if type(player.kantoBadges) == "table" then refill(player.kantoBadges, base.kanto) end
  if type(w.variableSprites) == "table" then refill(w.variableSprites, base.sprites) end
  if w.vm and w.vm.restoreMem then w.vm:restoreMem(copy(base.mem)) end
  if type(save.decorations) == "table" then refill(save.decorations, base.decorations) end
end

local function applyState(game, cmd)
  local w = game.world
  for id, on in pairs(cmd.events or {}) do w.events:set(tonumber(id), on == true) end
  for id, on in pairs(cmd.flags or {}) do pcall(w.setEngineFlag, w, tonumber(id), on == true) end
  for map, v in pairs(cmd.scenes or {}) do w.mapScenes[map] = v end
  for _, ms in ipairs(cmd.mapscenes or {}) do
    local ok, id = pcall(w.mapIdByGroupMap, w, ms.group, ms.map)
    if ok and id then w.mapScenes[id] = ms.value end
  end
  for addr, v in pairs(cmd.mem or {}) do if w.vm then w.vm.mem[tonumber(addr)] = v end end
  if type(cmd.decorations) == "table" then
    -- the bedroom's four block slots as the PC's decoration menu leaves them (Decorations.state; 0 is empty)
    local okD, D = pcall(require, "src.core.gen2.Decorations")
    local state = okD and type(D) == "table" and type(D.state) == "function" and D.state(game.save) or nil
    if type(state) == "table" then
      for _, slot in ipairs({ "bed", "carpet", "plant", "poster" }) do state[slot] = tonumber(cmd.decorations[slot]) or 0 end
    end
  end
  w.clockHour = cmd.hour or DEFAULT_HOUR
  w.clockDay = cmd.day or DEFAULT_DAY
end

local function arrivalCell(game, map)
  local def = (game.data and game.data.gen2Maps or game.world.maps or {})[map]
  local w = def and type(def.warps) == "table" and def.warps[1]
  if type(w) == "table" and tonumber(w.x) and tonumber(w.y) then return tonumber(w.x), tonumber(w.y) end
  if type(def) == "table" then return floor((tonumber(def.width) or 2)), floor((tonumber(def.height) or 2)) end
  return nil
end

-- What holds the field now (World:busy's markers, World.lua:1531-1556, and the field's own gates), as a list.
local BUSY = { "mapSetup", "textbox", "moveState", "choicebox", "fishing", "headbutt", "fieldMove", "flyAnim", "skyfall",
               "battleActive", "waitButton", "pendingSceneScript", "pendingWarp", "peopleDirty" }
local function busyFlags(game)
  local w = game.world
  local out = {}
  if w and w.vm and w.vm.busy then out[#out + 1] = "vm" end
  for _, k in ipairs(BUSY) do if w and w[k] ~= nil and w[k] ~= false then out[#out + 1] = k end end
  if topState(game) ~= nil then out[#out + 1] = "stack" end
  return #out > 0 and table.concat(out, "+") or "none"
end

-- Stops a script that still holds the field (test-only): the VM's run state cleared, boxes taken off the stack and
-- every marker World:busy reads cleared, so the player walks again.
local function haltScripts(game)
  local w = game.world
  local vm = w and w.vm
  local halted = false
  if vm and vm.busy then
    vm.busy, vm.co, vm.pending, vm.waitLeft, vm.waitSfx, vm.waitSfxLeft, vm.deferred = false, nil, nil, nil, nil, nil, nil
    halted = true
  end
  local n = 0
  while topState(game) ~= nil and n < 8 do
    pcall(game.stack.pop, game.stack)
    n = n + 1
    halted = true
  end
  if w then
    for _, k in ipairs({ "textbox", "moveState", "choicebox", "fishing", "headbutt", "fieldMove", "flyAnim", "skyfall",
                         "waitButton", "pendingSceneScript" }) do
      if w[k] ~= nil then
        w[k] = nil
        halted = true
      end
    end
  end
  return halted
end

local function changedBlocks(w)
  local m = w.map
  local live = type(m.def) == "table" and m.def.blocks or {}
  local edits = type(w.blockEdits) == "table" and w.blockEdits[m.id] or {}
  local n = 0
  for i, before in pairs(type(edits) == "table" and edits or {}) do
    if type(i) == "number" and live[i] ~= before then n = n + 1 end
  end
  return n
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
  local start, baseline, nextFrame
  local queue, nextLine = {}, 1
  local cmd, frames, idle = nil, 0, 0
  local lastRead = 0
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    if phase == "ready" then
      local w = game and game.world
      if status and status.ready and game.phase == "play" and w and w.map then
        status.configure({ port = PORT })
        if DATASET_MEMO > 0 then memoDatasetChecks(DATASET_MEMO) end
        homeRef = active()
        local precleaned = cleanup(homeRef)
        local v = version()
        local slot = SaveData.createSlot(v)
        if not slot then fail("no_slot") end
        SaveData.renameSlot(v, slot, LABEL)
        if SaveData.setActiveSlot(v, slot) ~= slot then fail("activate_failed") end
        print(("KB_BOOTED slot=%d home=%d precleaned=%d"):format(num(slot), num(homeRef), precleaned))
        phase = "house"
      end
    elseif phase == "house" then
      if fieldReady(game) then
        local w = game.world
        local x, y = arrivalCell(game, TOWN)
        if not x then fail("no_town_warp") end
        start = { x = x, y = y + 1 }
        if not pcall(w.warpToMapId, w, TOWN, start.x, start.y, "down") then fail("warp_town") end
        frames = 0
        phase = "town"
      end
    elseif phase == "town" then
      local w = game.world
      frames = frames + 1
      if fieldReady(game) and w.map.id == TOWN and w.player.cellX == start.x and w.player.cellY == start.y and frames > 30 then
        baseline = snapshot(game)
        print(("KB_SERVING map=%s x=%d y=%d"):format(TOWN, start.x, start.y))
        phase = "serve"
      end
    elseif phase == "serve" then
      local w = game.world
      local now = love.timer.getTime()
      if not cmd and #queue == 0 and now - lastRead > 0.25 then
        lastRead = now
        queue, nextLine = readCommands(nextLine)
      end
      if not cmd and #queue > 0 then
        cmd = table.remove(queue, 1)
        frames, idle = 0, 0
        if cmd.op == "speed" then
          -- STEP_SPEED_PLAN S0/S4: STEP SPEED as the F10 manager would set it (exports.configure{stepSpeed})
          if type(status.configure) == "function" then pcall(status.configure, { stepSpeed = tonumber(cmd.pct) }) end
          print(("KB_SPEED id=%s pct=%s"):format(word(cmd.id), word(status.moveSpeedPct or "none")))
          cmd = nil
        elseif cmd.op ~= "go" or type(cmd.map) ~= "string" then
          print(("KB_SPOT_FAIL id=%s reason=bad_command"):format(word(cmd.id)))
          cmd = nil
        elseif w.map.id == cmd.map and not cmd.nohop then
          local hop = cmd.map == TOWN and HOP_ALT or TOWN
          local hx, hy = arrivalCell(game, hop)
          restore(game, baseline)
          local ok = hx and pcall(w.warpToMapId, w, hop, hx, hy, "down")
          if ok then
            cmd.state = "hop"
          else
            print(("KB_SPOT_FAIL id=%s reason=hop"):format(word(cmd.id)))
            cmd = nil
          end
        else
          cmd.state = "go"
        end
      elseif cmd and cmd.state == "hop" then
        frames = frames + 1
        if frames == SETTLE_FRAMES then haltScripts(game) end
        idle = fieldReady(game) and idle + 1 or 0
        if idle >= 30 or frames > SETTLE_FRAMES + 60 then
          cmd.state, frames, idle = "go", 0, 0
        end
      elseif cmd and cmd.state == "go" then
        restore(game, baseline)
        applyState(game, cmd)
        local x, y = tonumber(cmd.x), tonumber(cmd.y)
        if not (x and y) then x, y = arrivalCell(game, cmd.map) end
        local ok, res = false, nil
        if x and y then ok, res = pcall(w.warpToMapId, w, cmd.map, x, y, cmd.facing or "down") end
        if not ok or res == false then
          print(("KB_SPOT_FAIL id=%s reason=warp"):format(word(cmd.id)))
          cmd = nil
        else
          cmd.state, frames, idle = "settle", 0, 0
        end
      elseif cmd and cmd.state == "settle" then
        frames = frames + 1
        if frames == SETTLE_FRAMES then
          cmd.held = busyFlags(game)
          cmd.halted = haltScripts(game)
        end
        idle = fieldReady(game) and idle + 1 or 0
        if idle >= 60 or frames >= SETTLE_FRAMES + 120 then
          if cmd.flash then w.flashUsed = true end
          -- FA6 (the field audit's spots): the vehicle the spot needs (World:applyPlayerState, World.lua:6029)
          if cmd.vehicle == "surf" or cmd.vehicle == "bike" then pcall(w.applyPlayerState, w, cmd.vehicle) end
          -- BIKE_PLAN B6/B7: the Bicycle in the pack (give_bike = true), for the Bicycle key
          if cmd.give_bike and type(game.save) == "table" then
            game.save.inventory = type(game.save.inventory) == "table" and game.save.inventory or {}
            if (tonumber(game.save.inventory.BICYCLE) or 0) < 1 then game.save.inventory.BICYCLE = 1 end
          end
          local dark = type(w.map.def) == "table" and w.map.def.palette == "PALETTE_DARK"
          print(("KB_SPOT id=%s map=%s x=%s y=%s blocks=%d dark=%s flash=%s halted=%s held=%s busy=%s"):format(
            word(cmd.id), word(w.map.id), word(w.player.cellX), word(w.player.cellY), changedBlocks(w),
            tostring(dark), tostring(w.flashUsed == true), tostring(cmd.halted or false), word(cmd.held or "none"),
            word(busyFlags(game))))
          cmd = nil
        end
      end
      if status.quitRequested then
        local deleted, nowSlot = cleanup(homeRef)
        print(("KB_CLEANED deleted=%d active=%d"):format(deleted, num(nowSlot)))
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
