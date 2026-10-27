-- Kanto Bridge test driver (WORLD_SESSIONS_PLAN WS3, check C2 on the real
-- engine; tools/e2e/test_ws3_join.py). Runs on any game family through
-- gen1-bridge's own adapter (status.testEngine). KB_SCENE picks the launch:
--   offline  a scratch "offline" slot (label KB_WS3) made active, a game of
--            the driver's own started there (as kb_ws0facts.lua does) and
--            saved, then back to the title: KB_OFFLINE slot= map=. Then it
--            serves at the title; the test's binds do the rest.
--   serve    the same install after a relaunch: serves at the title.
--   cleanup  the world's pairing forgotten (KB_WORLD), every KB_WS3 slot and
--            every slot the pairing made for the world deleted, the boot slot
--            active again: KB_CLEANED.
-- While serving, a line "save" in KB_WS3_DIR/request.txt makes the driver
-- save the running game (Game:writeSave; Gen 3 saveGame) once: KB_SAVED
-- map= x= y= slot=. KB_ACTIVE slot= ingame= whenever either changes.
-- Markers: map symbols, cells, slot numbers, booleans only. Runs unsandboxed
-- as a POKEPORT_DRIVER coroutine.

io.stdout:setvbuf("no")

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27211
local SCENE = os.getenv("KB_SCENE") or "offline"
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or 900
local DATASET_MEMO = tonumber(os.getenv("KB_DATASET_MEMO") or "") or 0
local WORLD = os.getenv("KB_WORLD") or "kb-e2e-ws3-world"
local DIR = os.getenv("KB_WS3_DIR")
local HOME = os.getenv("KB_HOME_SLOT")
local LABEL = "KB_WS3"

local SaveData = require("src.core.SaveData")
local GameVersion = require("src.core.GameVersion")

local function word(v)
  local s = tostring(v)
  return (s:gsub("[^%w_.:+%-]", "?"))
end
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

local function ingameMap(engine)
  local ok, f = pcall(engine.observe)
  if ok and type(f) == "table" and f.inGame and type(f.map) == "string" then return f.map, f.x, f.y end
  return nil
end

local function cleanup(status, home)
  local paired = status.forgetWorld(WORLD)
  local deleted = 0
  local ok, list = pcall(SaveData.listSlots, version())
  for _, s in ipairs(ok and list or {}) do
    if type(s) == "table" and s.id ~= home and (s.label == LABEL or s.id == paired) then
      if active() == s.id and home then SaveData.setActiveSlot(version(), home) end
      if SaveData.deleteSlot(version(), s.id) then deleted = deleted + 1 end
    end
  end
  if home and active() ~= home then SaveData.setActiveSlot(version(), home) end
  return deleted
end

local function readRequest()
  if not DIR then return nil end
  local f = io.open(DIR .. "/request.txt", "r")
  if not f then return nil end
  local line = f:read("*l")
  f:close()
  return line
end

local statusRef, homeRef
local function fail(reason)
  local ok, n = pcall(cleanup, statusRef, homeRef)
  print(("KB_FAIL %s cleaned=%s"):format(reason, word(ok and n or "error")))
  love.event.quit(1)
  while true do coroutine.yield() end
end

return function(game)
  local started = love.timer.getTime()
  local phase, wait, gen, engine, made = "ready", 0, 1, nil, nil
  local saved = false
  local lastActive, lastIngame
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    statusRef = status
    if phase == "ready" then
      local stacked = type(game.stack) == "table" and type(game.stack.top) == "function" and game.stack:top() ~= nil
      if status and status.ready and (stacked or game.phase == "boot" or game.phase == "play" or game.phase == "field") then
        status.configure({ port = PORT })
        if DATASET_MEMO > 0 then memoDatasetChecks(DATASET_MEMO) end
        engine = status.testEngine()
        gen = tonumber((GameVersion.info(version()) or {}).generation) or 1
        homeRef = HOME or active()
        if SCENE == "cleanup" then
          print(("KB_CLEANED deleted=%d active=%d"):format(cleanup(status, homeRef), num(active())))
          print("KB_QUIT sessions=0 code=0")
          return
        end
        print(("KB_BOOTED scene=%s game=%s gen=%d home=%d"):format(word(SCENE), word(version()), gen, num(homeRef)))
        if SCENE == "offline" then
          made = SaveData.createSlot(version())
          if not made then fail("no_slot") end
          SaveData.renameSlot(version(), made, LABEL)
          SaveData.setActiveSlot(version(), made)
          if gen == 2 and game.phase ~= "play" then fail("gen2_not_in_play")
          elseif gen == 3 then
            local ok = pcall(game._handleBootAction, game, { action = "new_game", name = "TEST", gender = 0 })
            if not ok then fail("new_game") end
          elseif gen == 1 then
            game:startNewGame({ intro = false })
          end
          phase = "make"
        else
          -- Gen 2's driver mode boots straight into the field: a relaunch waits at its title like the real one
          if gen == 2 and game.phase == "play" then pcall(game.returnToTitle, game) end
          phase = "serve"
        end
      end
    elseif phase == "make" then
      local map = ingameMap(engine)
      if map then
        wait = wait + 1
        if wait > 60 then
          local ok
          if gen == 3 then ok = pcall(game.saveGame, game) else ok = pcall(game.writeSave, game) end
          engine.toTitle()
          print(("KB_OFFLINE slot=%d map=%s ok=%s"):format(num(made), word(map), word(ok)))
          phase = "serve"
        end
      else
        wait = wait + 1
        if wait > 3600 then fail("make") end
      end
    elseif phase == "serve" then
      local a, map = active(), ingameMap(engine)
      local ingame = map ~= nil
      if a ~= lastActive or ingame ~= lastIngame then
        lastActive, lastIngame = a, ingame
        print(("KB_ACTIVE slot=%d ingame=%s map=%s"):format(num(a), word(ingame), word(map)))
      end
      if not saved and ingame and readRequest() == "save" then
        local ok
        if gen == 3 then ok = pcall(game.saveGame, game) else ok = pcall(game.writeSave, game) end
        local m, x, y = ingameMap(engine)
        print(("KB_SAVED map=%s x=%s y=%s slot=%d ok=%s"):format(word(m), word(x), word(y), num(active()), word(ok)))
        saved = true
      end
      if status.quitRequested then
        print(("KB_QUIT sessions=%s code=%s"):format(word(status.sessions), word(status.quitCode)))
        return
      end
    end
    if love.timer.getTime() - started > SECONDS_LIMIT then fail("timeout phase=" .. phase) end
    game = coroutine.yield() or game
  end
end
