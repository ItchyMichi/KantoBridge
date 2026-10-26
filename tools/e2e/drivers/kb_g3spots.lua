-- Kanto Bridge test driver: Emerald's story spots for the real client
-- (docs/specs/STORY_EVENTS_PLAN.md SE5), driven by tools/e2e/story_spots.py
-- while the real dev client follows this game. A fresh empty save slot
-- labelled KB_G3SPOTS_TEST is made active, a new game goes straight into the
-- field (Game3:_handleBootAction, as kb_emerald.lua does) and is put in
-- Littleroot below its first door; then the driver serves (paced to KB_PACE
-- frames a second, default 60, as kb_serve.lua: a driver turns the engine's
-- own pacing off) and runs the commands it finds in KB_SPOT_COMMANDS, one Lua
-- table literal a line:
--
--   { id = 1, op = "go", map = "EM_...", x = 4, y = 7, facing = "up",
--     flags = { [2154] = true }, vars = { [16439] = 9 }, tide = true,
--     mirage = false }
--
-- go: the flag store back to the new game's (as it was once the game reached
-- the field), the flags and vars set (Flags.setFlag/setVar on Space.store,
-- persisted by the warp's own deactivate), the shoal tide or Mirage Island
-- forced through their natives when given, then a warp onto the map's cell
-- (no fade: Map.load runs the entry scripts at once; without x and y the
-- map's first warp's cell, else its walkable cell nearest the middle). Once
-- the field is idle (onFrame scripts given 10 s, then halted) it prints
-- KB_SPOT id= map= x= y= cells= weather= flash= halted=. The client sees the
-- warp and follows. When the map the player is on is the command's map, a
-- hop to another map comes first, so the entry scripts run again (not with
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

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27241
local PACE = tonumber(os.getenv("KB_PACE") or "") or 60
local DATASET_MEMO = tonumber(os.getenv("KB_DATASET_MEMO") or "") or 0
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or 7200
local COMMANDS = os.getenv("KB_SPOT_COMMANDS")
local LABEL = "KB_G3SPOTS_TEST"
local TOWN = "EM_LITTLEROOT_TOWN"
local SETTLE_FRAMES = 600 -- then a script still holding the field is halted

local SaveData = require("src.core.SaveData")
local GameVersion = require("src.core.GameVersion")

local function word(v) return (tostring(v):gsub("[^%w_.:+%-]", "?")) end
local function num(id) return tonumber(tostring(id):match("^slot(%d+)$")) or 0 end
local function version() return GameVersion.get() end
local function active() return SaveData.activeSlot(version()) end
local function mod3(name) return package.loaded["src.core.game3." .. name] end

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

-- The field is up and settled: no lock, no warp, no fade, no step.
local function fieldReady(game)
  local F, W, P = mod3("field"), mod3("warp"), mod3("player")
  local Fade = package.loaded["src.ui.game3.fade"]
  if not (game and game.phase == "field" and F and F.running) then return false end
  if F.isLocked and F.isLocked() then return false end
  if W and W.isBusy and W.isBusy() then return false end
  if Fade and Fade.lockInput then return false end
  return not (P and P.moving)
end

local function classOf(coll)
  local P = package.loaded["src.world.gen2.Permissions"]
  if not P then
    local ok, mod = pcall(require, "src.world.gen2.Permissions")
    P = ok and mod or nil
  end
  coll = tonumber(coll) or 0xff
  if coll == 0xff then return "void" end
  if P and P.isWater and P.isWater(coll) then return "water" end
  if P and P.isLedge and P.isLedge(coll) then return "ledge" end
  if P and P.isWalkable then return P.isWalkable(coll) and "walk" or "solid" end
  return (coll ~= 0x07 and coll ~= 0x29) and "walk" or "solid"
end

-- The current map's overrides that differ from its own cells (a count).
local function changedCells()
  local Map = mod3("map")
  local def = Map and Map.currentDef and Map.currentDef() or nil
  local lay = def and def.midLayout
  if type(lay) ~= "table" then return 0 end
  local w = math.floor(tonumber(lay.width) or 0)
  local n = 0
  for k, ov in pairs(type(lay.overrides) == "table" and lay.overrides or {}) do
    k = tonumber(k)
    local x, y = k and k % 1024, k and math.floor(k / 1024)
    local base = k and w > 0 and lay.cells[y * w + x + 1] or nil
    if type(ov) == "table" and type(base) == "table"
        and (ov.mid ~= base.mid or classOf(ov.coll) ~= classOf(base.coll)) then
      n = n + 1
    end
  end
  return n
end

local function storeCopy(store)
  local s = { flags = {}, vars = {} }
  for k, v in pairs(type(store) == "table" and store.flags or {}) do s.flags[k] = v end
  for k, v in pairs(type(store) == "table" and store.vars or {}) do s.vars[k] = v end
  return s
end

local function storeRestore(store, s)
  if type(store) ~= "table" then return end
  store.flags = store.flags or {}
  store.vars = store.vars or {}
  for k in pairs(store.flags) do store.flags[k] = nil end
  for k, v in pairs(s.flags) do store.flags[k] = v end
  for k in pairs(store.vars) do store.vars[k] = nil end
  for k, v in pairs(s.vars) do store.vars[k] = v end
end

local function arrivalCell(game, map)
  local def = game.data and game.data.maps and game.data.maps[map]
  local w = def and def.warps and def.warps[1]
  if w and tonumber(w.x) and tonumber(w.y) then return tonumber(w.x), tonumber(w.y) end
  local Map = mod3("map")
  local lay = def and Map and Map.ensureMidLayout and Map.ensureMidLayout(game, map, def) or nil
  if type(lay) ~= "table" then return nil end
  local tw = math.floor(tonumber(lay.trueWidth) or tonumber(lay.width) or 0)
  local th = math.floor(tonumber(lay.trueHeight) or tonumber(lay.height) or 0)
  local cx, cy = math.floor(tw / 2), math.floor(th / 2)
  local best
  for y = 0, th - 1 do
    for x = 0, tw - 1 do
      local cell = lay.cells[y * lay.width + x + 1]
      if type(cell) == "table" and classOf(cell.coll) == "walk" then
        local d = math.abs(x - cx) + math.abs(y - cy)
        if not best or d < best.d then best = { x = x, y = y, d = d } end
      end
    end
  end
  if best then return best.x, best.y end
  return nil
end

local function haltScripts()
  local Space = mod3("scripting.space")
  local halted = false
  for _, vm in ipairs({ Space and Space.vm, Space and Space._immediateVm }) do
    if vm and vm.isRunning and vm:isRunning() then
      pcall(vm.halt, vm, true)
      halted = true
    end
  end
  local Message = package.loaded["src.ui.game3.message"]
  if Message and Message.reset then pcall(Message.reset) end
  local Choice = package.loaded["src.ui.game3.choice"]
  if type(Choice) == "table" then Choice.active = nil end
  local F = mod3("field")
  if F then F._locks = {} end
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

-- BIKE_PLAN B0/B1: the bike commands (cells, counts, states and frame numbers only).
--
--   { id = 2, op = "scan", dir = "up", bike = "mach" }
--     on the current map, the five longest straight runs a bike may ride in `dir` from a standing start, by the
--     engine's own bike collision (bike/rse.lua Rse.collision, as kb_g3field.lua's bikeVerdict asks it), and the
--     rails: KB_SCAN id= rank= x= y= cells=, KB_RAIL id= x= y= kind=
--   { id = 3, op = "ride", segs = { { dir = "up", b = false, frames = 240 }, ... }, sight = false }
--     the hidden game's own bike driven by held buttons (game.input sources, as mod.input's presses): each segment
--     holds its direction (or none) and B for its frames; trainer sight is off while it runs unless sight = true.
--     Every logic step whose bike facts changed prints KB_RT (f frame, mv moving, c cell, t target, sf step frames,
--     j jump type, ac action kind, as acroState, fc frameCounter, an acroAnim kind, fl the flags the bridge's
--     classifier reads: J jumping, S scripted step, F forced movement, T transitioning, L locked or a script);
--     KB_RIDE id= seg= f= at each segment, KB_RIDE_END id= x= y= moved= frames=
--   { id = 5, op = "sight", on = false }
--     trainer sight off for good (on = true: back), for a ride driven through the bridge
--   { id = 6, op = "trace", frames = 600 }
--     prints KB_RT (as ride does) while somebody else drives (the client's move.ride); KB_TRACE_END id= map= x= y= moved=
--   { id = 7, op = "challenge", begin = true }
--     the Cycling Road challenge begun (Rse.beginCyclingRoadChallenge); KB_CHALLENGE id= on= collisions=
--   { id = 4, op = "watch", frames = 600 }
--     prints KB_BTN f= b= dir= whenever B or the held direction changes (the latency of a client's hold)
local RIDE_SOURCE = "kbdrv:ride"
local DIRS4 = { "up", "down", "left", "right" }
local DELTA4 = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }

local function rideRelease(game, held)
  for btn in pairs(held) do pcall(game.input.sourceRelease, game.input, btn, RIDE_SOURCE) end
  for k in pairs(held) do held[k] = nil end
end

local function ridePress(game, held, want)
  for btn in pairs(held) do
    if not want[btn] then
      pcall(game.input.sourceRelease, game.input, btn, RIDE_SOURCE)
      held[btn] = nil
    end
  end
  for btn in pairs(want) do
    if not held[btn] then
      pcall(game.input.sourcePress, game.input, btn, RIDE_SOURCE)
      held[btn] = true
    end
  end
end

local function bikeFacts(game)
  local P = mod3("player")
  local Rse = package.loaded["src.core.game3.bike.rse"]
  local S = Rse and Rse.state or {}
  local fl = ""
  if P.jumping then fl = fl .. "J" end
  if P._scriptedStep ~= nil then fl = fl .. "S" end
  local FM = mod3("forced_movement")
  if FM and type(FM.isForced) == "function" and FM.isForced() then fl = fl .. "F" end
  local W = mod3("warp")
  local Fade = package.loaded["src.ui.game3.fade"]
  if (W and W.isBusy and W.isBusy()) or (Fade and Fade.lockInput) then fl = fl .. "T" end
  local F = mod3("field")
  local Space = mod3("scripting.space")
  if (F and F.isLocked and F.isLocked()) or (Space and Space.vm and Space.vm:isRunning()) then fl = fl .. "L" end
  local a = P.action
  local ac = "-"
  if type(a) == "table" then ac = a.jump and ("jump_" .. tostring(a.jump)) or (a.walk and ("walk_" .. tostring(a.walk))) or "pose" end
  return ("mv=%d c=%s,%s t=%s,%s sf=%s j=%s ac=%s as=%s fc=%s an=%s bike=%s fl=%s"):format(
    P.moving and 1 or 0, word(P.cellX), word(P.cellY), word(P.moving and P.targetX or "-"), word(P.moving and P.targetY or "-"),
    word(P.stepFrames), word(P.jumping and P.jumpType or "-"), ac, word(S.acroState), word(S.frameCounter),
    word(type(P.acroAnim) == "table" and P.acroAnim.kind or "-"), word(P.biking and (P.bikeType or "?") or "no"),
    fl == "" and "-" or fl)
end

local function scanRuns(game, id, dir, bike)
  local P, C, Map = mod3("player"), mod3("collision"), mod3("map")
  local Rse = require("src.core.game3.bike.rse")
  local def = Map and Map.currentDef and Map.currentDef() or nil
  local lay = def and def.midLayout
  if not (P and C and lay) then
    print(("KB_SCAN_FAIL id=%s reason=no_map"):format(word(id)))
    return
  end
  local tw = math.floor(tonumber(lay.trueWidth) or tonumber(lay.width) or 0)
  local th = math.floor(tonumber(lay.trueHeight) or tonumber(lay.height) or 0)
  local saved = { P.cellX, P.cellY, P.surfing, P.currentElevation, P.elevation, P.biking, P.bikeType }
  P.surfing, P.biking, P.bikeType = false, true, bike or "mach"
  local d = DELTA4[dir] or DELTA4.up
  local function elevOf(x, y) return C.elevationOn and C.elevationOn(def, x, y) or 0 end
  local function ok(x, y, e)
    local tx, ty = x + d[1], y + d[2]
    if not C.inBounds(tx, ty) then return false end
    P.cellX, P.cellY, P.currentElevation, P.elevation = x, y, e, e
    local good, col = pcall(Rse.collision, game, dir)
    return good and tonumber(col) == 0
  end
  local runs, rails = {}, {}
  local okScan, err = pcall(function()
    for x = 0, tw - 1 do
      for y = 0, th - 1 do
        local beh = C.behaviorOn and C.behaviorOn(def, x, y) or nil
        if beh and (C.isVerticalRail(beh) or C.isHorizontalRail(beh) or C.isIsolatedVerticalRail(beh)
            or C.isIsolatedHorizontalRail(beh)) and #rails < 400 then
          rails[#rails + 1] = { x = x, y = y, kind = C.isVerticalRail(beh) and "v" or C.isHorizontalRail(beh) and "h"
            or C.isIsolatedVerticalRail(beh) and "iv" or "ih" }
        end
      end
    end
    -- a run from each start: count cells while the bike may go on (a 15 cell keeps the elevation it was entered with)
    for x = 0, tw - 1 do
      for y = 0, th - 1 do
        local e0 = elevOf(x, y)
        if e0 ~= 15 and C.isWalkable(x, y) then
          local cx, cy, e, n = x, y, e0, 0
          while n < 200 and ok(cx, cy, e) do
            cx, cy = cx + d[1], cy + d[2]
            local ne = elevOf(cx, cy)
            if ne ~= 15 then e = ne end
            n = n + 1
          end
          runs[#runs + 1] = { x = x, y = y, n = n }
        end
      end
    end
  end)
  P.cellX, P.cellY, P.surfing, P.currentElevation, P.elevation, P.biking, P.bikeType =
    saved[1], saved[2], saved[3], saved[4], saved[5], saved[6], saved[7]
  if not okScan then
    print(("KB_SCAN_FAIL id=%s reason=%s"):format(word(id), word(err):sub(1, 60)))
    return
  end
  table.sort(runs, function(a, b) return a.n > b.n or (a.n == b.n and (a.x < b.x or (a.x == b.x and a.y < b.y))) end)
  local seen, rank = {}, 0
  for _, r in ipairs(runs) do
    -- one per column (the longest), five in all
    if rank >= 5 then break end
    local key = (d[1] == 0) and r.x or r.y
    if not seen[key] then
      seen[key] = true
      rank = rank + 1
      print(("KB_SCAN id=%s rank=%d x=%d y=%d dir=%s cells=%d"):format(word(id), rank, r.x, r.y, word(dir), r.n))
    end
  end
  for i, r in ipairs(rails) do
    if i > 12 then break end
    print(("KB_RAIL id=%s x=%d y=%d kind=%s"):format(word(id), r.x, r.y, r.kind))
  end
  print(("KB_SCAN_END id=%s runs=%d rails=%d"):format(word(id), #runs, #rails))
end

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local start, baseline, nextFrame
  local queue, nextLine = {}, 1
  local cmd, frames, patches = nil, 0, {}
  local lastRead = 0
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    if phase == "ready" then
      if status and status.ready and game.phase == "boot" then
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
        local ok, err = pcall(game._handleBootAction, game, { action = "new_game", name = "TEST", gender = 0 })
        if not ok then fail("new_game " .. word(err):sub(1, 40)) end
        phase = "newgame"
      end
    elseif phase == "newgame" then
      if fieldReady(game) then
        local def = game.data and game.data.maps and game.data.maps[TOWN]
        local w = def and def.warps and def.warps[1]
        if not w then fail("no_town_warp") end
        start = { x = tonumber(w.x), y = tonumber(w.y) + 1 }
        local ok = pcall(mod3("warp").request, nil, game, TOWN, start.x, start.y, "down", { fade = false })
        if not ok then fail("warp_request") end
        phase = "town"
      end
    elseif phase == "town" then
      local Map, P = mod3("map"), mod3("player")
      if fieldReady(game) and Map and Map.current == TOWN and P.cellX == start.x and P.cellY == start.y then
        baseline = storeCopy(mod3("scripting.space").store)
        print(("KB_SERVING map=%s x=%d y=%d"):format(TOWN, start.x, start.y))
        phase = "serve"
      end
    elseif phase == "serve" then
      local now = love.timer.getTime()
      if not cmd and #queue == 0 and now - lastRead > 0.25 then
        lastRead = now
        queue, nextLine = readCommands(nextLine)
      end
      if not cmd and #queue > 0 then
        cmd = table.remove(queue, 1)
        frames = 0
        if cmd.op == "sight" then
          -- B1: trainer sight off (or back on) for a run driven through the bridge
          local TS = package.loaded["src.core.game3.trainer_sight"]
          if TS and TS.check then
            if cmd.on == false and not patches.sightAlways then
              patches.sightAlways = TS.check
              TS.check = function() return false end
            elseif cmd.on ~= false and patches.sightAlways then
              TS.check = patches.sightAlways
              patches.sightAlways = nil
            end
          end
          print(("KB_SIGHT id=%s on=%s"):format(word(cmd.id), word(cmd.on ~= false)))
          cmd = nil
        elseif cmd.op == "speed" then
          -- STEP_SPEED_PLAN S0/S4: STEP SPEED as the F10 manager would set it (exports.configure{stepSpeed})
          if status and type(status.configure) == "function" then pcall(status.configure, { stepSpeed = tonumber(cmd.pct) }) end
          print(("KB_SPEED id=%s pct=%s"):format(word(cmd.id), word(status and status.moveSpeedPct or "none")))
          cmd = nil
        elseif cmd.op == "scan" then
          scanRuns(game, cmd.id, cmd.dir or "up", cmd.bike)
          cmd = nil
        elseif cmd.op == "challenge" then
          -- B5: the Cycling Road challenge begun (bike/rse.lua Rse.beginCyclingRoadChallenge), or its count read
          local Rse = package.loaded["src.core.game3.bike.rse"] or require("src.core.game3.bike.rse")
          if cmd.begin then pcall(Rse.beginCyclingRoadChallenge) end
          local S = Rse.state or {}
          print(("KB_CHALLENGE id=%s on=%s collisions=%s"):format(word(cmd.id), word(S.cyclingChallenge == true),
            word(S.collisions)))
          cmd = nil
        elseif cmd.op == "trace" then
          -- B5: the bike's facts while somebody else (the client's move.ride) drives
          cmd.state, cmd.last = "trace", nil
          local P = mod3("player")
          cmd.from = { P.cellX or 0, P.cellY or 0 }
        elseif cmd.op == "ride" or cmd.op == "watch" then
          cmd.state, cmd.seg, cmd.held, cmd.last = cmd.op, 0, {}, nil
          local P = mod3("player")
          cmd.from = { P.cellX or 0, P.cellY or 0 }
          if cmd.op == "ride" and not cmd.sight then
            local TS = package.loaded["src.core.game3.trainer_sight"]
            if TS and TS.check then
              patches.sight = patches.sight or TS.check
              TS.check = function() return false end
            end
          end
        elseif cmd.op ~= "go" or type(cmd.map) ~= "string" then
          print(("KB_SPOT_FAIL id=%s reason=bad_command"):format(word(cmd.id)))
          cmd = nil
        else
          local Map = mod3("map")
          cmd.state = (Map and Map.current == cmd.map and not cmd.nohop) and "hop" or "go"
          if cmd.state == "hop" then
            local hop = cmd.map == TOWN and "EM_ROUTE101" or TOWN
            local hx, hy = arrivalCell(game, hop)
            storeRestore(mod3("scripting.space").store, baseline)
            local ok = hx and pcall(mod3("warp").request, nil, game, hop, hx, hy, "down", { fade = false })
            if not ok then
              print(("KB_SPOT_FAIL id=%s reason=hop"):format(word(cmd.id)))
              cmd = nil
            end
          end
        end
      elseif cmd and cmd.state == "ride" then
        frames = frames + 1
        local segs = type(cmd.segs) == "table" and cmd.segs or {}
        local at, seg = 0, nil
        for i, s in ipairs(segs) do
          at = at + (tonumber(s.frames) or 0)
          if frames <= at then seg = i break end
        end
        if seg ~= nil and seg ~= cmd.seg then
          cmd.seg = seg
          local s = segs[seg]
          local want = {}
          if DELTA4[s.dir] then want[s.dir] = true end
          if s.b then want.b = true end
          ridePress(game, cmd.held, want)
          print(("KB_RIDE id=%s seg=%d f=%d dir=%s b=%s"):format(word(cmd.id), seg, frames, word(s.dir or "-"),
            word(s.b == true)))
        end
        local facts = bikeFacts(game)
        if facts ~= cmd.last then
          cmd.last = facts
          print(("KB_RT f=%d %s"):format(frames, facts))
        end
        if seg == nil then
          rideRelease(game, cmd.held)
          local TS = package.loaded["src.core.game3.trainer_sight"]
          if TS and patches.sight then TS.check = patches.sight end
          patches.sight = nil
          local P, Map = mod3("player"), mod3("map")
          print(("KB_RIDE_END id=%s map=%s x=%s y=%s moved=%d frames=%d"):format(word(cmd.id), word(Map and Map.current),
            word(P.cellX), word(P.cellY), math.abs((P.cellX or 0) - cmd.from[1]) + math.abs((P.cellY or 0) - cmd.from[2]),
            frames))
          cmd = nil
        end
      elseif cmd and cmd.state == "trace" then
        frames = frames + 1
        local facts = bikeFacts(game)
        if facts ~= cmd.last then
          cmd.last = facts
          print(("KB_RT f=%d %s"):format(frames, facts))
        end
        if frames >= (tonumber(cmd.frames) or 600) then
          local P, Map = mod3("player"), mod3("map")
          print(("KB_TRACE_END id=%s map=%s x=%s y=%s moved=%d"):format(word(cmd.id), word(Map and Map.current), word(P.cellX),
            word(P.cellY), math.abs((P.cellX or 0) - cmd.from[1]) + math.abs((P.cellY or 0) - cmd.from[2])))
          cmd = nil
        end
      elseif cmd and cmd.state == "watch" then
        frames = frames + 1
        local dir = "-"
        for _, d in ipairs(DIRS4) do
          if game.input:isDown(d) then dir = d break end
        end
        local now = ("b=%d dir=%s"):format(game.input:isDown("b") and 1 or 0, dir)
        if now ~= cmd.last then
          cmd.last = now
          print(("KB_BTN id=%s f=%d %s"):format(word(cmd.id), frames, now))
        end
        if frames >= (tonumber(cmd.frames) or 600) then
          print(("KB_WATCH_END id=%s"):format(word(cmd.id)))
          cmd = nil
        end
      elseif cmd and cmd.state == "hop" then
        frames = frames + 1
        if frames >= 600 then haltScripts() end
        if fieldReady(game) and frames >= 30 then
          cmd.state, frames = "go", 0
        end
      elseif cmd and cmd.state == "go" then
        local Space = mod3("scripting.space")
        local Flags = require("src.core.game3.scripting.flags")
        storeRestore(Space.store, baseline)
        for id, on in pairs(cmd.flags or {}) do Flags.setFlag(Space.store, nil, tonumber(id), on == true) end
        local ctx = Space.vm and Space.vm.ctx
        for id, value in pairs(cmd.vars or {}) do Flags.setVar(Space.store, ctx, tonumber(id), tonumber(value)) end
        local FR = require("src.core.game3.scripting.natives_field_rse")
        if cmd.tide ~= nil then
          patches.tide = patches.tide or FR.updateShoalTideFlag
          FR.updateShoalTideFlag = function() return true end
        end
        if cmd.mirage ~= nil then
          patches.mirage = patches.mirage or FR.isMirageIslandPresent
          local present = cmd.mirage == true
          FR.isMirageIslandPresent = function() return present end
        end
        local x, y = tonumber(cmd.x), tonumber(cmd.y)
        if not (x and y) then x, y = arrivalCell(game, cmd.map) end
        local ok, res, err = false, nil, nil
        if x and y then
          ok, res, err = pcall(mod3("warp").request, nil, game, cmd.map, x, y, cmd.facing or "down", { fade = false })
        end
        if not ok or (res == nil and err ~= nil) then
          print(("KB_SPOT_FAIL id=%s reason=warp"):format(word(cmd.id)))
          cmd = nil
        else
          cmd.state, frames = "settle", 0
        end
      elseif cmd and cmd.state == "settle" then
        frames = frames + 1
        local Space = mod3("scripting.space")
        local running = Space and Space.vm and Space.vm:isRunning()
        if frames == SETTLE_FRAMES then cmd.halted = haltScripts() end
        if (frames >= 60 and fieldReady(game) and not running) or frames >= SETTLE_FRAMES + 120 then
          local FR = package.loaded["src.core.game3.scripting.natives_field_rse"]
          if FR and patches.tide then FR.updateShoalTideFlag = patches.tide end
          if FR and patches.mirage then FR.isMirageIslandPresent = patches.mirage end
          patches = {}
          local Map, P = mod3("map"), mod3("player")
          local Runtime = mod3("runtime")
          local sess = Runtime and Runtime.getSession and Runtime.getSession()
          -- FA6 (the field audit's spots): the vehicle the spot needs, as the engine's own fields hold it
          if P and cmd.vehicle then
            P.surfing = cmd.vehicle == "surf"
            P.underwater = cmd.vehicle == "underwater"
            P.biking = cmd.vehicle == "mach" or cmd.vehicle == "acro"
            P.bikeType = P.biking and cmd.vehicle or nil
            if sess then sess.biking, sess.bikeType = P.biking, P.bikeType end
          end
          -- BIKE_PLAN B6/B7: the bike in the bag (give_bike = "MACH_BIKE" or "ACRO_BIKE"), for the Bicycle key
          if cmd.give_bike and sess and type(sess.bag) == "table" then
            local Constants = mod3("constants")
            local okC, C = pcall(function() return Constants.of(version()) end)
            C = okC and C or nil
            local Bag = mod3("bag")
            local id = C and C.id and C.id(C, "items", "ITEM_" .. tostring(cmd.give_bike)) or nil
            if Bag and id and (tonumber(Bag.get(sess.bag, id)) or 0) < 1 then pcall(Bag.add, sess.bag, id, 1) end
          end
          local R = mod3("field_weather_rse")
          local FV = mod3("field_view")
          print(("KB_SPOT id=%s map=%s x=%s y=%s cells=%d weather=%s drawn=%s flash=%s halted=%s"):format(
            word(cmd.id), word(Map and Map.current), word(P and P.cellX), word(P and P.cellY), changedCells(),
            word(sess and sess.savedWeather), word(R and R.state and R.state.currWeather),
            word(FV and FV.flashLevel), word(cmd.halted or false)))
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
