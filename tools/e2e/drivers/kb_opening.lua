-- Kanto Bridge test driver (M5 opening and Oak's escort e2e): moves
-- gen1-bridge to the test port and leaves the game at its boot screens (the
-- intro movie, then the title), printing KB_BOOTED; the test
-- (tools/e2e/test_opening.py) does everything over the bridge: START through
-- the title, NEW GAME from the title menu, Oak's speech with both names typed
-- in "Minecraft", the walk out of the house, and Oak's escort from Pallet
-- Town's north edge into his lab. The driver serves until gen1-bridge
-- reports a debug.quit (exit 0) or the time limit passes (exit 1).
--
-- It prints KB_MAP_ENTERED map=<symbol> via=<word> for each map.entered
-- event (src/world/OverworldController.lua:685-690), KB_FLAG name=<symbol>
-- when one of the escort's flags turns on (data/scripts/story2.lua:159,
-- :183-184 write them straight into save.flags, so they are polled), and at
-- the end gen1-bridge's counters.
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- (refs/gen1recomp/main.lua:755-759, :1136-1157). Data safety: prints only
-- markers, map and flag symbols and counts.

io.stdout:setvbuf("no") -- LÖVE block-buffers stdout; this also unbuffers mod.log

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27192
-- KB_DATASET_MEMO=<seconds> (as kb_export.lua): the engine re-checks a dataset view's readiness on every
-- record read, which on a network disk (the pod's volume) stretches gen1-bridge's export to many minutes;
-- memoising the check for that many seconds brings it back to seconds (the data never changes during an
-- export). Test-only: patches DatasetViews._ready (src/mods/DatasetViews.lua) in place.
local DATASET_MEMO = tonumber(os.getenv("KB_DATASET_MEMO") or "") or 0
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
local EXTRA_SECONDS = tonumber(os.getenv("KB_DRIVER_EXTRA") or "") or 0 -- the runner's export allowance
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or (600 + EXTRA_SECONDS)
local WATCHED = { "EVENT_FOLLOWED_OAK_INTO_LAB", "EVENT_FOLLOWED_OAK_INTO_LAB_2", "EVENT_OAK_ASKED_TO_CHOOSE_MON" }

local function word(v)
  local s = tostring(v)
  return (s:gsub("[^%w_.:+%-]", "?"))
end

local function fail(reason)
  print("KB_FAIL " .. reason)
  love.event.quit(1)
  while true do coroutine.yield() end
end

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local seen = {}
  local Runtime = require("src.mods.Runtime")
  Runtime.events:on("map.entered", function(payload)
    local id = type(payload) == "table" and payload.mapId or nil
    local via = type(payload) == "table" and payload.via or nil
    print(("KB_MAP_ENTERED map=%s via=%s"):format(word(id), word(via)))
  end, 0, "kb-opening-driver")
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    if phase == "ready" then
      if status and status.ready then
        status.configure({ port = PORT })
        if DATASET_MEMO > 0 then
          print(("KB_DATASET_MEMO seconds=%d ok=%s"):format(DATASET_MEMO, tostring(memoDatasetChecks(DATASET_MEMO))))
        end
        local top = game.stack:top()
        print(("KB_BOOTED top=%s port=%s"):format(word(top and top.screenId), word(status.port)))
        phase = "serve"
      end
    elseif phase == "serve" then
      local flags = game.save and game.save.flags
      for _, name in ipairs(WATCHED) do
        if not seen[name] and type(flags) == "table" and flags[name] then
          seen[name] = true
          print(("KB_FLAG name=%s"):format(name))
        end
      end
      if status.quitRequested then
        print(("KB_END uiOpened=%s npcMoves=%s warpsArrived=%s"):format(
          word(status.uiOpened), word(status.npcMoves), word(status.warpsArrived)))
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
