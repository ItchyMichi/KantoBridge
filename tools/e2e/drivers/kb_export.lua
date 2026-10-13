-- Kanto Bridge test driver (M2 world export e2e): moves gen1-bridge to the
-- test port and leaves the game at the title screen (no new game: the export
-- reads the imported data, not a save). Prints KB_TITLE once the listener is
-- bound, then serves until gen1-bridge reports a debug.quit (exit 0) or the
-- time limit passes (exit 1).
--
-- While gen1-bridge builds the export it also measures the longest gap
-- between two frames, which shows whether the build ever stalled the game.
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- (refs/gen1recomp/main.lua:755-759, :1136-1157). Data safety: prints only
-- markers, states and numbers.

io.stdout:setvbuf("no") -- LÖVE block-buffers stdout; this also unbuffers mod.log

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27197
local EXTRA_SECONDS = tonumber(os.getenv("KB_DRIVER_EXTRA") or "") or 0 -- the runner's export allowance
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or (300 + EXTRA_SECONDS)
-- KB_DATASET_MEMO=<seconds> (as kb_wild.lua): the engine re-checks a dataset view's readiness on every
-- record read, which on a network disk (the pod's volume) stretches gen1-bridge's export from seconds to
-- many minutes; memoising the check for that many seconds brings it back (the data never changes during
-- an export). Test-only: the driver patches DatasetViews._ready (src/mods/DatasetViews.lua) in place.
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
  local last = love.timer.getTime()
  local buildFrames, maxGap, wasBuilding = 0, 0, false
  while true do
    local now = love.timer.getTime()
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    if status and status.exportState == "building" then
      wasBuilding = true
      buildFrames = buildFrames + 1
      if now - last > maxGap then maxGap = now - last end
    end
    last = now
    if phase == "ready" then
      if status and status.ready then
        status.configure({ port = PORT })
        if DATASET_MEMO > 0 then
          print(("KB_DATASET_MEMO seconds=%d ok=%s"):format(DATASET_MEMO, tostring(memoDatasetChecks(DATASET_MEMO))))
        end
        phase = "bind"
      end
    elseif phase == "bind" then
      if status.bound then
        local top = game.stack:top()
        print(("KB_TITLE port=%s bound=%s overworld=%s"):format(word(status.port), word(status.bound),
          word(top ~= nil and top.isOverworld == true)))
        phase = "serve"
      end
    elseif phase == "serve" then
      if status.quitRequested then
        print(("KB_EXPORT_FRAMES built=%s state=%s frames=%d max_gap_ms=%d"):format(word(wasBuilding),
          word(status.exportState), buildFrames, math.floor(maxGap * 1000 + 0.5)))
        print(("KB_QUIT sessions=%s code=%s"):format(word(status.sessions), word(status.quitCode)))
        return
      end
    end
    if now - started > SECONDS_LIMIT then
      fail("timeout phase=" .. phase)
    end
    game = coroutine.yield() or game
  end
end
