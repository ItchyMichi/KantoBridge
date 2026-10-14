-- Kanto Bridge test driver (MG8's story video, tools/e2e/story_video.py): moves
-- gen1-bridge to KB_TEST_PORT and leaves the game at its boot screens, any
-- game (Gen 1 or Gen 3), printing KB_BOOTED; the real dev client plays from
-- there (the title, NEW GAME, the story). The driver serves until gen1-bridge
-- reports a debug.quit (exit 0) or the time limit passes (exit 1).
--
-- KB_PACE=<n> (default 60) paces the serving to n frames a second: a driver
-- turns the engine's own pacing off (main.lua:1776-1781), and a real client
-- playing against an unpaced game would see it run several times too fast.
-- KB_DATASET_MEMO=<seconds> memoises the engine's import cache check as the
-- other drivers do (the pod's network disk; test-only).
--
-- It prints KB_MAP_ENTERED map=<symbol> via=<word> for each map.entered
-- event. Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per
-- frame. Data safety: prints only markers, map symbols and counts.

io.stdout:setvbuf("no")

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27192
local PACE = tonumber(os.getenv("KB_PACE") or "") or 60
local DATASET_MEMO = tonumber(os.getenv("KB_DATASET_MEMO") or "") or 0
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or 7200

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

local function word(v)
  local s = tostring(v)
  return (s:gsub("[^%w_.:+%-]", "?"))
end

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local nextFrame
  local ok, Runtime = pcall(require, "src.mods.Runtime")
  if ok and type(Runtime) == "table" and Runtime.events then
    Runtime.events:on("map.entered", function(payload)
      local id = type(payload) == "table" and payload.mapId or nil
      local via = type(payload) == "table" and payload.via or nil
      print(("KB_MAP_ENTERED map=%s via=%s"):format(word(id), word(via)))
    end, 0, "kb-serve-driver")
  end
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    if phase == "ready" then
      if status and status.ready then
        status.configure({ port = PORT })
        if DATASET_MEMO > 0 then
          print(("KB_DATASET_MEMO seconds=%d ok=%s"):format(DATASET_MEMO, tostring(memoDatasetChecks(DATASET_MEMO))))
        end
        print(("KB_BOOTED port=%s pace=%d"):format(word(status.port), PACE))
        phase = "serve"
      end
    elseif status and status.quitRequested then
      print(("KB_QUIT sessions=%s code=%s"):format(word(status.sessions), word(status.quitCode)))
      return
    end
    if PACE > 0 then
      local now = love.timer.getTime()
      nextFrame = math.max((nextFrame or now) + 1 / PACE, now - 0.1)
      if nextFrame > now then love.timer.sleep(nextFrame - now) end
    end
    if love.timer.getTime() - started > SECONDS_LIMIT then
      print("KB_FAIL timeout phase=" .. phase)
      love.event.quit(1)
      while true do coroutine.yield() end
    end
    game = coroutine.yield() or game
  end
end
