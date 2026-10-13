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
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or 300

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
