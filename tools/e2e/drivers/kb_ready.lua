-- Kanto Bridge test driver (spike S5): waits for gen1-bridge to report ready
-- after game.ready, prints KB_READY with safe facts, and ends, which makes the
-- engine quit with exit code 0.
--
-- Gen1Recomp runs a driver unsandboxed inside its main.lua (POKEPORT_DRIVER,
-- refs/gen1recomp/main.lua:755-759): the returned function becomes a
-- coroutine, resumed once per frame with the Game (main.lua:1136-1157).
-- Data safety: print only markers, ids, versions and counts.

io.stdout:setvbuf("no") -- LÖVE block-buffers stdout; this also unbuffers mod.log

local FRAME_LIMIT = tonumber(os.getenv("KB_DRIVER_FRAMES") or "") or 3600
local EXTRA_SECONDS = tonumber(os.getenv("KB_DRIVER_EXTRA") or "") or 0 -- the runner's export allowance
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or (60 + EXTRA_SECONDS)

local function fail(reason)
  print("KB_FAIL " .. reason)
  love.event.quit(1)
  while true do coroutine.yield() end
end

local function word(v)
  local s = tostring(v)
  return (s:gsub("[^%w_.:+%-]", "?"))
end

return function(game)
  local started = love.timer.getTime()
  local frames = 0
  while true do
    frames = frames + 1
    local mods = game and game.mods
    local record = mods and mods.mods and mods.mods["gen1-bridge"]
    if record and record.state ~= nil and record.state ~= "loaded" and record.state ~= "pending" then
      fail("mod_state=" .. word(record.state))
    end
    local status = mods and mods.exports and mods.exports["gen1-bridge"]
    if status and status.ready and record and record.state == "loaded" then
      local okV, Version = pcall(require, "src.core.Version")
      local errors = 0
      if game.modStatus and type(game.modStatus.errors) == "table" then
        for _ in pairs(game.modStatus.errors) do errors = errors + 1 end
      end
      -- ENGINE_UPGRADE_PLAN UP1: the engine members gen1-bridge found missing (a count) and whether this
      -- boot's Pokemon Centers carry the Union Room's patch ("on" fails the smoke gate: it must be off)
      local missing = type(status.engineMissing) == "table" and #status.engineMissing or -1
      local names = {}
      for i = 1, math.min(missing, 8) do names[i] = word(status.engineMissing[i]) end
      print(("KB_READY state=%s engine=%s status_engine=%s bridge=%s protocol=%s modules=%s mod_errors=%d frames=%d missing=%d missing_names=%s union_room=%s")
        :format(word(record.state), word(okV and Version.engine or "?"), word(status.engine),
          word(status.bridge), word(status.protocol), word(status.modules), errors, frames, missing,
          #names > 0 and table.concat(names, "+") or "-", word(status.unionRoom or "?")))
      return
    end
    if frames >= FRAME_LIMIT or love.timer.getTime() - started > SECONDS_LIMIT then
      fail(("timeout frames=%d have_record=%s have_status=%s"):format(frames, tostring(record ~= nil),
        tostring(status ~= nil)))
    end
    game = coroutine.yield() or game
  end
end
