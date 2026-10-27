-- Kanto Bridge test driver (WORLD_SESSIONS_PLAN WS0 items 3-5, on every game
-- family; tools/e2e/test_ws0_facts.py). It runs gen1-bridge's own engine
-- adapter (status.engine(), lib/kb_engine.lua) so the same steps work on
-- Gen 1, Gen 2 and Gen 3:
--   copy      the slot active at boot (the install's own save, never written)
--             is copied into a new slot with the engine's own
--             SaveData.duplicateSlot (src/core/SaveData.lua:1937-1990):
--             KB_COPY ok= exists= newid= (the copy's playthroughId differs
--             from the source's, its player map is the same) -- WS0 item 5,
--             D14. The copy is made active; nothing touches the source.
--   title     KB_TITLE_SUMMARY: what the launcher's and the title's summary
--             (SaveData.listSlots -> slotSummary, :1499-1560) gives for the
--             copy at the title: whether a name is there (never the name),
--             badges, dex count -- item 3's second half.
--   continue  engine.startFromTitle() (CONTINUE of the copy): KB_CONTINUED.
--   hold      engine.holdAway(): the region hold, as a parked game has it.
--   save      engine.switchSave() (the adapter's own save behind the engine's
--             quick-save gate) while the hold is up: KB_HELD_SAVE result= --
--             item 3; the save.begin it announces goes to the test.
--   release, then engine.toTitle(): KB_TITLE_AGAIN playthrough= top= -- item
--             4 (a clean title after a save); then startFromTitle again:
--             KB_CONTINUED_AGAIN map= (the held save loads).
-- At the end the copy is deleted and the boot slot made active again
-- (KB_CLEANED). KB_SCENE=cleanup does only that (slots labelled KB_WS0).
--
-- Markers carry map symbols, slot numbers, counts and booleans only. Runs
-- unsandboxed as a POKEPORT_DRIVER coroutine.

io.stdout:setvbuf("no")

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27209
local SCENE = os.getenv("KB_SCENE") or "facts"
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or 600
local DATASET_MEMO = tonumber(os.getenv("KB_DATASET_MEMO") or "") or 0
local LABEL = "KB_WS0"

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

local function slotRow(id)
  local ok, list = pcall(SaveData.listSlots, version())
  for _, s in ipairs(ok and list or {}) do
    if type(s) == "table" and s.id == id then return s end
  end
  return nil
end

local function cleanup(home)
  local deleted = 0
  local ok, list = pcall(SaveData.listSlots, version())
  for _, s in ipairs(ok and list or {}) do
    if type(s) == "table" and s.label == LABEL and s.id ~= home then
      if active() == s.id and home then SaveData.setActiveSlot(version(), home) end
      if SaveData.deleteSlot(version(), s.id) then deleted = deleted + 1 end
    end
  end
  if home and active() ~= home then SaveData.setActiveSlot(version(), home) end
  return deleted
end

-- the hidden player's map when in game (the adapter's facts: any family), else nil
local function ingameMap(engine)
  local ok, f = pcall(engine.observe)
  if ok and type(f) == "table" and f.inGame and type(f.map) == "string" then return f.map end
  return nil
end

local homeRef
local function fail(reason)
  local ok, n = pcall(cleanup, homeRef)
  print(("KB_FAIL %s cleaned=%s"):format(reason, word(ok and n or "error")))
  love.event.quit(1)
  while true do coroutine.yield() end
end

local function playerMapOf(id)
  local body = SaveData.readSlotSource(version(), id)
  if not body then return nil, nil end
  local ok, save = pcall(require("src.core.SaveSerializer").decode, body)
  if not ok or type(save) ~= "table" then return nil, nil end
  local p = save.player or {}
  local meta = type(save.meta) == "table" and save.meta or {}
  return p.map, meta.playthroughId
end

return function(game)
  local started = love.timer.getTime()
  local phase, wait = "ready", 0
  local copy, engine
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    if phase == "ready" then
      if status and status.ready then
        status.configure({ port = PORT })
        if DATASET_MEMO > 0 then memoDatasetChecks(DATASET_MEMO) end
        homeRef = active()
        local pre = cleanup(homeRef)
        if SCENE == "cleanup" then
          print(("KB_CLEANED deleted=%d active=%d"):format(pre, num(active())))
          print("KB_QUIT sessions=0 code=0")
          return
        end
        engine = status.engine()
        local row = slotRow(homeRef)
        print(("KB_BOOTED game=%s home=%d homeSave=%s precleaned=%d"):format(word(version()), num(homeRef),
          word(row and row.exists), pre))
        if not (row and row.exists) then fail("no_home_save") end
        local srcMap, srcId = playerMapOf(homeRef)
        local ok, id, err = pcall(SaveData.duplicateSlot, version(), homeRef, LABEL)
        if not ok or not id then fail("duplicate " .. word(ok and err or id)) end
        copy = id
        local map, pid = playerMapOf(copy)
        local crow = slotRow(copy)
        print(("KB_COPY ok=true slot=%d exists=%s sameMap=%s newPlaythrough=%s map=%s"):format(num(copy),
          word(crow and crow.exists), word(map ~= nil and map == srcMap), word(pid ~= nil and pid ~= srcId), word(map)))
        if SaveData.setActiveSlot(version(), copy) ~= copy then fail("activate") end
        phase = "boot"
      end
    elseif phase == "boot" then
      if game.stack and game.stack:top() ~= nil then
        wait = wait + 1
        if wait > 30 then
          local row = slotRow(copy)
          print(("KB_TITLE_SUMMARY slot=%d exists=%s hasName=%s badges=%s dex=%s time=%s"):format(num(copy),
            word(row and row.exists), word(row and type(row.name) == "string" and #row.name > 0),
            word(row and type(row.meta) == "table" and row.meta.badges), word(row and type(row.meta) == "table" and row.meta.dexCount),
            word(row and type(row.meta) == "table" and row.meta.timeText ~= nil)))
          phase, wait = "continue", 0
        end
      end
    elseif phase == "continue" then
      local res, why = engine.startFromTitle()
      if res then
        print(("KB_CONTINUED how=%s"):format(word(res)))
        phase, wait = "ingame", 0
      elseif why ~= "menu" then
        wait = wait + 1
        if wait > 600 then fail("continue " .. word(why)) end
      end
    elseif phase == "ingame" then
      local map = ingameMap(engine)
      if map then
        wait = wait + 1
        if wait > 120 then
          print(("KB_INGAME map=%s playthrough=%s"):format(word(map), word(engine.playthrough())))
          local ok, err = engine.holdAway()
          print(("KB_HELD ok=%s why=%s"):format(word(ok), word(err)))
          phase, wait = "save", 0
        end
      end
    elseif phase == "save" then
      wait = wait + 1
      if wait == 30 then
        local ok, res = pcall(engine.switchSave)
        print(("KB_HELD_SAVE result=%s held=%s"):format(word(ok and res or "error"), word(engine.awayHeld and engine.awayHeld())))
      elseif wait == 90 then
        if engine.releaseAway then engine.releaseAway() end
        local ok = engine.toTitle()
        print(("KB_TO_TITLE ok=%s"):format(word(ok)))
        phase, wait = "title", 0
      end
    elseif phase == "title" then
      wait = wait + 1
      if wait == 120 then
        local top = game.stack and game.stack:top()
        print(("KB_TITLE_AGAIN playthrough=%s overworld=%s top=%s held=%s"):format(word(engine.playthrough()),
          word(ingameMap(engine) ~= nil), word(top and (top.screenId or (top.kantoBridgeRegionHold and "hold") or "other")),
          word(engine.awayHeld and engine.awayHeld())))
        phase, wait = "again", 0
      end
    elseif phase == "again" then
      local res, why = engine.startFromTitle()
      if res then
        phase, wait = "again2", 0
      elseif why ~= "menu" then
        wait = wait + 1
        if wait > 600 then fail("continue2 " .. word(why)) end
      end
    elseif phase == "again2" then
      local map = ingameMap(engine)
      wait = wait + 1
      if map and wait > 60 then
        print(("KB_CONTINUED_AGAIN map=%s"):format(word(map)))
        engine.toTitle()
        phase = "serve"
      elseif wait > 900 then
        fail("again_ingame")
      end
    elseif phase == "serve" then
      if status.quitRequested then
        print(("KB_CLEANED deleted=%d active=%d"):format(cleanup(homeRef), num(active())))
        print(("KB_QUIT sessions=%s code=%s"):format(word(status.sessions), word(status.quitCode)))
        return
      end
    end
    if love.timer.getTime() - started > SECONDS_LIMIT then fail("timeout phase=" .. phase) end
    game = coroutine.yield() or game
  end
end
