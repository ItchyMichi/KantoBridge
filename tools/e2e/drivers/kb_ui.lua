-- Kanto Bridge test driver (M4 dialogue, menus, NPCs and the mirror e2e):
-- moves gen1-bridge to the test port, starts a new game without the intro
-- (the player wakes up in REDS_HOUSE_2F), prints KB_OVERWORLD once the
-- overworld is on top, and serves until gen1-bridge reports a debug.quit
-- (exit 0) or the time limit passes (exit 1). The test (tools/e2e/test_ui.py)
-- does everything else over the bridge.
--
-- It also prints KB_MAP_ENTERED map=<symbol> via=<word> for each map.entered
-- event (src/world/OverworldController.lua:685-690), KB_TITLE once the game
-- is back at the title screen after having been in the overworld (the
-- start menu's QUIT answered YES: Game:returnToTitle, src/core/Game.lua:247-251),
-- and at the end gen1-bridge's UI, NPC and mirror counters from its status
-- table.
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- (refs/gen1recomp/main.lua:755-759, :1136-1157). Data safety: prints only
-- markers, map symbols and counts.

io.stdout:setvbuf("no") -- LÖVE block-buffers stdout; this also unbuffers mod.log

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27195
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or 420

local function word(v)
  local s = tostring(v)
  return (s:gsub("[^%w_.:+%-]", "?"))
end

local function fail(reason)
  print("KB_FAIL " .. reason)
  love.event.quit(1)
  while true do coroutine.yield() end
end

local function overworldOnStack(game)
  for _, s in ipairs(game.stack and game.stack.states or {}) do
    if type(s) == "table" and s.isOverworld then return true end
  end
  return false
end

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local entered = 0
  local Runtime = require("src.mods.Runtime")
  Runtime.events:on("map.entered", function(payload)
    entered = entered + 1
    local id = type(payload) == "table" and payload.mapId or nil
    local via = type(payload) == "table" and payload.via or nil
    print(("KB_MAP_ENTERED map=%s via=%s"):format(word(id), word(via)))
  end, 0, "kb-ui-driver")
  local titled = false
  -- whole frames (update, draw, present) with the mirror's output seam on
  -- and off: with no frame pacing under a driver, a frame's length is its work
  -- (or the vsync wait, when vsync is on)
  local last = love.timer.getTime()
  local frame = { on = { n = 0, sum = 0, max = 0 }, off = { n = 0, sum = 0, max = 0 } }
  while true do
    local now = love.timer.getTime()
    local dt = now - last
    last = now
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
      local bucket = status.mirrorOn and frame.on or frame.off
      bucket.n, bucket.sum = bucket.n + 1, bucket.sum + dt
      if dt > bucket.max then bucket.max = dt end
      if not titled and not overworldOnStack(game) then
        titled = true
        local top = game.stack:top()
        print(("KB_TITLE top=%s"):format(word(top and top.screenId)))
      end
      if status.quitRequested then
        print(("KB_UI opened=%s closed=%s npcsets=%s npcmoves=%s captures=%s frames=%s avg_us=%s max_us=%s encode_us=%s entered=%s"):format(
          word(status.uiOpened), word(status.uiClosed), word(status.npcSets), word(status.npcMoves),
          word(status.mirrorCaptures), word(status.mirrorFrames), word(status.mirrorAvgUs),
          word(status.mirrorMaxUs), word(status.mirrorEncodeAvgUs), word(entered)))
        local function us(v) return math.floor(v * 1e6 + 0.5) end
        print(("KB_FRAMES on=%d on_avg_us=%d on_max_us=%d off=%d off_avg_us=%d off_max_us=%d"):format(
          frame.on.n, frame.on.n > 0 and us(frame.on.sum / frame.on.n) or 0, us(frame.on.max),
          frame.off.n, frame.off.n > 0 and us(frame.off.sum / frame.off.n) or 0, us(frame.off.max)))
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
