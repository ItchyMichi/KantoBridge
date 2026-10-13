-- Kanto Bridge test driver (M3 walking and warps e2e): moves gen1-bridge to
-- the test port, starts a new game without the intro (the player wakes up
-- in REDS_HOUSE_2F), prints KB_OVERWORLD once the overworld is on top, and
-- serves until gen1-bridge reports a debug.quit (exit 0) or the time limit
-- passes (exit 1). The test (tools/e2e/test_walk.py) does the walking over
-- the bridge.
--
-- It also listens to the engine's map.entered event
-- (src/world/OverworldController.lua:685-690) and prints each one as
-- KB_MAP_ENTERED map=<symbol> via=<word>, and at the end prints the step
-- driver's and warp tracker's counters from gen1-bridge's status table.
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- (refs/gen1recomp/main.lua:755-759, :1136-1157). Data safety: prints only
-- markers, map symbols and counts.

io.stdout:setvbuf("no") -- LÖVE block-buffers stdout; this also unbuffers mod.log

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27196
local EXTRA_SECONDS = tonumber(os.getenv("KB_DRIVER_EXTRA") or "") or 0 -- the runner's export allowance
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or (300 + EXTRA_SECONDS)

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
  local entered = 0
  -- the loader's event bus is live before the first resume (Game:load runs
  -- the mod loader, main.lua:750 before :641)
  local Runtime = require("src.mods.Runtime")
  Runtime.events:on("map.entered", function(payload)
    entered = entered + 1
    local id = type(payload) == "table" and payload.mapId or nil
    local via = type(payload) == "table" and payload.via or nil
    print(("KB_MAP_ENTERED map=%s via=%s"):format(word(id), word(via)))
  end, 0, "kb-walk-driver")
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    if phase == "ready" then
      if status and status.ready then
        status.configure({ port = PORT })
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
      if status.quitRequested then
        print(("KB_WALK requests=%s answered=%s dropped=%s repressed=%s warps=%s arrived=%s entered=%s"):format(
          word(status.moveRequests), word(status.moveAnswered), word(status.moveDropped),
          word(status.moveRepressed), word(status.warpsStarted), word(status.warpsArrived), word(entered)))
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
