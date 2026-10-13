-- Kanto Bridge test driver (M1 handshake e2e): moves gen1-bridge to the test
-- port, starts a new game without the intro, prints KB_OVERWORLD once the
-- overworld is on top, then keeps the game running until gen1-bridge reports
-- a debug.quit (exit 0) or the time limit passes (exit 1).
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- with the Game (refs/gen1recomp/main.lua:755-759, :1136-1157). The first
-- resume comes before the first Game:step, so the port change lands before
-- the listener binds. Data safety: prints only markers, symbols and counts.

io.stdout:setvbuf("no") -- LÖVE block-buffers stdout; this also unbuffers mod.log

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27199
local EXTRA_SECONDS = tonumber(os.getenv("KB_DRIVER_EXTRA") or "") or 0 -- the runner's export allowance
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or (120 + EXTRA_SECONDS)

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
  while true do
    local mods = game and game.mods
    local status = mods and mods.exports and mods.exports["gen1-bridge"]
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
        print(("KB_OVERWORLD map=%s port=%s bound=%s"):format(word(top.map.id), word(status.port),
          word(status.bound)))
        phase = "serve"
      end
    elseif phase == "serve" then
      if status.quitRequested then
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
