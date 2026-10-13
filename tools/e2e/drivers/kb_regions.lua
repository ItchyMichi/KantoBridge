-- Kanto Bridge test driver (MG2 e2e: the region acts and game.status on the
-- real engine; tools/e2e/test_regions.py; docs/specs/MULTI_GAME_PLAN.md §5
-- MG2's check and spike S4). Every scene first registers a fresh empty save
-- slot labelled KB_REGIONS_TEST and makes it active (the engine's own slot
-- API, src/core/SaveData.lua:1699-1900), so nothing touches the install's own
-- saves; at the end (or on a failure) the test slots are deleted and the slot
-- that was active at boot is active again. KB_SCENE picks the part:
--
--   start_new       leaves the game at its title screen on the empty test
--                   slot (Game:returnToTitle pops the boot movie and pushes
--                   the title, src/core/Game.lua:247-251) and serves: the
--                   test's region.start runs NEW GAME through the title menu.
--   start_continue  New Game without the intro on the test slot, a save
--                   written into it (Game:writeSave, Game.lua:1362-1385),
--                   back to the title, then serves: region.start CONTINUEs.
--   park            New Game without the intro on the test slot, then serves
--                   in the overworld: the test parks and resumes.
--   cleanup         only the clean-up (after a failed run), then quits.
--
-- Markers: KB_BOOTED scene= slot= home= precleaned=, KB_SAVED slot= ok=,
-- KB_TITLE slot= hasSave=, KB_SERVING map= x= y=, KB_INGAME map= x= y= (the
-- overworld's first appearance after a start, under the intro too),
-- KB_CLEANED deleted= active=, KB_QUIT sessions= code=; KB_FAIL <reason> on
-- a timeout (after the same clean-up). Slot numbers, map symbols, cell
-- numbers, booleans and counts only: never text.
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- with the Game (refs/gen1recomp/main.lua:755-759, :1136-1157).

io.stdout:setvbuf("no") -- LÖVE block-buffers stdout; this also unbuffers mod.log

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27210
local SCENE = os.getenv("KB_SCENE") or "park"
local EXTRA_SECONDS = tonumber(os.getenv("KB_DRIVER_EXTRA") or "") or 0 -- the runner's export allowance
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or (300 + EXTRA_SECONDS)
local LABEL = "KB_REGIONS_TEST" -- the test slots' launcher label, how a later run finds leftovers

local SaveData = require("src.core.SaveData")
local GameVersion = require("src.core.GameVersion")

local function word(v)
  local s = tostring(v)
  return (s:gsub("[^%w_.:+%-]", "?"))
end

local function num(id)
  return tonumber(tostring(id):match("^slot(%d+)$")) or 0
end

local function version() return GameVersion.get() end
local function active() return SaveData.activeSlot(version()) end

-- the registry's slots as the launcher lists them (SaveData.listSlots,
-- src/core/SaveData.lua:1642): id, exists, label
local function slots()
  local ok, list = pcall(SaveData.listSlots, version())
  return ok and type(list) == "table" and list or {}
end

local function testSlots()
  local out = {}
  for _, s in ipairs(slots()) do
    if type(s) == "table" and s.label == LABEL then out[#out + 1] = s.id end
  end
  return out
end

local function slotHasSave(id)
  for _, s in ipairs(slots()) do
    if type(s) == "table" and s.id == id then return s.exists == true end
  end
  return false
end

local function overworld(game)
  local states = game and game.stack and game.stack.states or {}
  for i = #states, 1, -1 do
    local s = states[i]
    if type(s) == "table" and s.isOverworld and s.map and s.player then return s end
  end
  return nil
end

local function onTop(game)
  local top = game and game.stack and game.stack:top()
  return top ~= nil and top.isOverworld == true and top.map ~= nil
end

local function titleOnTop(game)
  local top = game and game.stack and game.stack:top()
  return top ~= nil and top.screenId == "TitleState"
end

-- Deletes every test slot (never another), making `home` (the slot active
-- at boot) active first when a test slot is the active one. Returns the
-- count deleted and the active slot afterwards.
local function cleanup(home)
  local deleted = 0
  local v = version()
  for _, id in ipairs(testSlots()) do
    if active() == id then
      local back = home
      if not back then
        for _, s in ipairs(slots()) do
          if type(s) == "table" and s.label ~= LABEL then back = s.id break end
        end
      end
      if back and back ~= id then SaveData.setActiveSlot(v, back) end
    end
    if SaveData.deleteSlot(v, id) then deleted = deleted + 1 end
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

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local slot
  local announced = false
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    if phase == "ready" then
      if status and status.ready then
        status.configure({ port = PORT })
        homeRef = active()
        if SCENE == "cleanup" then
          local deleted, now = cleanup(homeRef)
          print(("KB_CLEANED deleted=%d active=%d"):format(deleted, num(now)))
          print("KB_QUIT sessions=0 code=0")
          return
        end
        -- what an earlier failed run may have left, then this run's own slot
        local precleaned = cleanup(homeRef)
        local v = version()
        slot = SaveData.createSlot(v)
        if not slot then fail("no_slot") end
        SaveData.renameSlot(v, slot, LABEL)
        if SaveData.setActiveSlot(v, slot) ~= slot then fail("activate_failed") end
        print(("KB_BOOTED scene=%s slot=%d home=%d precleaned=%d"):format(word(SCENE), num(slot), num(homeRef),
          precleaned))
        phase = "boot"
      end
    elseif phase == "boot" then
      -- the boot movie is on the stack once Game:load has run (Game.lua:
      -- 136-149: the splash is pushed right after game.ready)
      if game.stack:top() ~= nil then
        if SCENE == "start_new" then
          game:returnToTitle()
          phase = "title"
        elseif SCENE == "start_continue" then
          game:startNewGame({ intro = false }) -- Game.lua:181-200
          phase = "newgame"
        elseif SCENE == "park" then
          game:startNewGame({ intro = false })
          phase = "world"
        else
          fail("unknown_scene")
        end
      end
    elseif phase == "newgame" then
      if onTop(game) then
        local written = game:writeSave()
        print(("KB_SAVED slot=%d ok=%s"):format(num(active()), word(written == true)))
        if written ~= true then fail("save_failed") end
        game:returnToTitle() -- Game.lua:247-251: everything popped, the title pushed
        phase = "title"
      end
    elseif phase == "title" then
      if titleOnTop(game) then
        print(("KB_TITLE slot=%d hasSave=%s"):format(num(active()), word(slotHasSave(active()))))
        phase = "serve"
      end
    elseif phase == "world" then
      if onTop(game) then
        local ow = overworld(game)
        print(("KB_SERVING map=%s x=%d y=%d"):format(word(ow.map.id), ow.player.cellX or -1, ow.player.cellY or -1))
        announced = true
        phase = "serve"
      end
    elseif phase == "serve" then
      local ow = overworld(game)
      if ow and not announced then
        -- the overworld appeared (a region.start landed; under the intro it sits below Oak's speech)
        print(("KB_INGAME map=%s x=%d y=%d"):format(word(ow.map.id), ow.player.cellX or -1, ow.player.cellY or -1))
        announced = true
      end
      if status.quitRequested then
        local deleted, now = cleanup(homeRef)
        print(("KB_CLEANED deleted=%d active=%d"):format(deleted, num(now)))
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
