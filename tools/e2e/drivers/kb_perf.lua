-- Kanto Bridge test driver (M10 e2e: gen1-bridge's own cost per logic step
-- over a busy scenario, and the mirror's frame rate; tools/e2e/test_perf.py).
-- Moves gen1-bridge to the test port, starts a new game without the intro,
-- prints KB_OVERWORLD once the overworld is on top, and starts gen1-bridge's
-- cost counts over (status.perfReset) the moment the test subscribes to the
-- mirror (KB_PERF_RESET): the scenario's own numbers, without the boot, the
-- export or the handshake. It also times whole frames while the scenario
-- runs (KB_FRAMES). At debug.quit: KB_PERF with gen1-bridge's counts, then
-- exit 0.
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- (refs/gen1recomp/main.lua:755-759, :1136-1157). Prints only markers and
-- numbers.

io.stdout:setvbuf("no") -- LÖVE block-buffers stdout; this also unbuffers mod.log

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27204
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
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or (400 + EXTRA_SECONDS)

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
  local frames, frameTotal, frameMax, last = 0, 0, 0, nil
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    local now = love.timer.getTime()
    if phase == "ready" then
      if status and status.ready then
        status.configure({ port = PORT })
        if DATASET_MEMO > 0 then
          print(("KB_DATASET_MEMO seconds=%d ok=%s"):format(DATASET_MEMO, tostring(memoDatasetChecks(DATASET_MEMO))))
        end
        -- New Game without Oak's speech (src/core/Game.lua:173-200)
        game:startNewGame({ intro = false })
        phase = "world"
      end
    elseif phase == "world" then
      local top = game.stack:top()
      if top and top.isOverworld and top.map then
        print(("KB_OVERWORLD map=%s port=%s"):format(word(top.map.id), word(status.port)))
        phase = "serve"
      end
    elseif phase == "serve" then
      if status.mirrorOn then
        status.perfReset()
        print("KB_PERF_RESET")
        phase = "scenario"
      end
    elseif phase == "scenario" then
      -- the whole frame, from one resume to the next (the engine's update,
      -- draw and present, gen1-bridge included)
      if last then
        local ms = (now - last) * 1000
        frames, frameTotal = frames + 1, frameTotal + ms
        if ms > frameMax then frameMax = ms end
      end
      last = now
    end
    if status and status.quitRequested then
      print(("KB_FRAMES n=%d avgMs=%.2f maxMs=%.2f"):format(frames, frames > 0 and frameTotal / frames or 0, frameMax))
      print(("KB_PERF steps=%s avgUs=%s maxUs=%s over1ms=%s"):format(word(status.perfSteps),
        word(status.perfAvgUs), word(status.perfMaxUs), word(status.perfOver1ms)))
      print(("KB_QUIT sessions=%s code=%s"):format(word(status.sessions), word(status.quitCode)))
      return
    end
    if now - started > SECONDS_LIMIT then
      fail("timeout phase=" .. phase)
    end
    game = coroutine.yield() or game
  end
end
