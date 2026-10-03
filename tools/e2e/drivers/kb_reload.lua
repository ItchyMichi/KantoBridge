-- Kanto Bridge test driver (M1, hot reload): like kb_handshake.lua, but once
-- a client session has been up for a second it hot-reloads the mods the way
-- F5 does (refs/gen1recomp/src/dev/HotReload.lua:27-64), points the NEW
-- gen1-bridge instance at the same test port, and serves until that instance
-- reports a debug.quit.
--
-- What it proves: the old instance's sockets are released (main.lua runs one
-- garbage collection on game.ready), the new one binds the same port without
-- SO_REUSEADDR, and a reconnect reaches the new instance.
-- Data safety: prints only markers, symbols and counts.

io.stdout:setvbuf("no")

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27199
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or 150

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
  local phase, since = "ready", nil
  while true do
    local status = game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    local now = love.timer.getTime()
    if phase == "ready" then
      if status and status.ready then
        status.configure({ port = PORT })
        game:startNewGame({ intro = false })
        phase = "world"
      end
    elseif phase == "world" then
      local top = game.stack:top()
      if top and top.isOverworld and top.map then
        print(("KB_OVERWORLD map=%s port=%s"):format(word(top.map.id), word(status.port)))
        phase = "first"
      end
    elseif phase == "first" then
      if (status.sessions or 0) >= 1 then
        since = since or now
        if now - since >= 1.0 then
          local oldSession = status.session
          print("KB_RELOAD")
          require("src.dev.HotReload").run(game)
          local fresh = game.mods.exports["gen1-bridge"]
          if fresh == nil or fresh == status then fail("no_new_instance") end
          fresh.configure({ port = PORT })
          print(("KB_RELOADED new_session=%s"):format(word(fresh.session ~= oldSession)))
          phase = "second"
        end
      end
    elseif phase == "second" then
      if status.quitRequested then
        print(("KB_QUIT sessions=%s"):format(word(status.sessions)))
        return
      end
    end
    if now - started > SECONDS_LIMIT then fail("timeout phase=" .. phase) end
    game = coroutine.yield() or game
  end
end
