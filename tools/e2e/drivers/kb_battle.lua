-- Kanto Bridge test driver (M5 starter and rival battle e2e): moves
-- gen1-bridge to the test port, starts a new game without the intro, and sets
-- up the state Oak's escort leaves in his lab -- with the engine's own APIs:
-- the escort's flags (EVENT_FOLLOWED_OAK_INTO_LAB, _2 and
-- EVENT_OAK_ASKED_TO_CHOOSE_MON, written straight into save.flags as the
-- escort itself does, data/scripts/story2.lua:178-179, :159), the lab's Oak
-- swap (Commands.hide_object / show_object, src/script/Commands.lua:641-647,
-- as story2.lua:173-174 does) and a scripted warp into OAKS_LAB below a
-- starter ball, facing it (OverworldState:startWarpTo,
-- src/world/OverworldController.lua:5561). Then it prints KB_LAB_READY and
-- serves until gen1-bridge reports a debug.quit (exit 0) or the time limit
-- passes (exit 1). The test (tools/e2e/test_battle.py) does everything else
-- over the bridge: picks the starter, walks to the rival's trigger, answers
-- the battle.
--
-- It prints KB_MAP_ENTERED map=<symbol> via=<word> for each map.entered,
-- KB_FLAG name=<symbol> when one of the watched story flags turns on,
-- KB_BLACKOUT when the engine blacks out, and KB_BATTLE_PUSHED when a native
-- battle screen appears (there should be none: Cobblemon fights them).
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- (refs/gen1recomp/main.lua:755-759, :1136-1157). Data safety: prints only
-- markers, map and flag symbols, cells and counts.

io.stdout:setvbuf("no") -- LÖVE block-buffers stdout; this also unbuffers mod.log

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27194
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
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or (420 + EXTRA_SECONDS)
-- the cell below the middle starter ball (data/scripts/oaks_lab.lua:19-20:
-- balls at (6,3), (7,3), (8,3))
local LAB_X = tonumber(os.getenv("KB_LAB_X") or "") or 7
local LAB_Y = tonumber(os.getenv("KB_LAB_Y") or "") or 4
local WATCHED = { "EVENT_GOT_STARTER", "EVENT_BATTLED_RIVAL_IN_OAKS_LAB" }

local function word(v)
  local s = tostring(v)
  return (s:gsub("[^%w_.:+%-]", "?"))
end

local function fail(reason)
  print("KB_FAIL " .. reason)
  love.event.quit(1)
  while true do coroutine.yield() end
end

local function overworld(game)
  local states = game.stack and game.stack.states or {}
  for i = #states, 1, -1 do
    local s = states[i]
    if type(s) == "table" and s.isOverworld then return s end
  end
  return nil
end

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local arrived = false
  local seen = {}
  local pushedBattles = 0
  local Runtime = require("src.mods.Runtime")
  Runtime.events:on("map.entered", function(payload)
    local id = type(payload) == "table" and payload.mapId or nil
    local via = type(payload) == "table" and payload.via or nil
    print(("KB_MAP_ENTERED map=%s via=%s"):format(word(id), word(via)))
  end, 0, "kb-battle-driver")
  Runtime.events:on("world.blacked_out", function() print("KB_BLACKOUT") end, 0, "kb-battle-driver")
  Runtime.events:on("screen.pushed", function(payload)
    local state = type(payload) == "table" and payload.state or nil
    if type(state) == "table" and state.isBattleState then
      pushedBattles = pushedBattles + 1
      print(("KB_BATTLE_PUSHED count=%d"):format(pushedBattles))
    end
  end, 0, "kb-battle-driver")
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
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
      local ow = overworld(game)
      if ow and game.stack:top() == ow and ow.map then
        local flags = game.save.flags
        flags.EVENT_FOLLOWED_OAK_INTO_LAB = true
        flags.EVENT_FOLLOWED_OAK_INTO_LAB_2 = true
        flags.EVENT_OAK_ASKED_TO_CHOOSE_MON = true
        local Commands = require("src.script.Commands")
        local ctx = { save = game.save, game = game, overworld = ow }
        Commands.hide_object(ctx, "OAKS_LAB", "OAKSLAB_OAK2")
        Commands.show_object(ctx, "OAKS_LAB", "OAKSLAB_OAK1")
        ow:startWarpTo("OAKS_LAB", LAB_X, LAB_Y, "up", function() arrived = true end)
        phase = "warping"
      end
    elseif phase == "warping" then
      local ow = overworld(game)
      if arrived and ow and game.stack:top() == ow and ow.map and ow.map.id == "OAKS_LAB" then
        local p = ow.player
        print(("KB_LAB_READY map=%s x=%s y=%s facing=%s port=%s"):format(
          word(ow.map.id), word(p.cellX), word(p.cellY), word(p.facing), word(status.port)))
        phase = "serve"
      end
    elseif phase == "serve" then
      for _, name in ipairs(WATCHED) do
        if not seen[name] and game.save and game.save.flags and game.save.flags[name] then
          seen[name] = true
          print(("KB_FLAG name=%s"):format(name))
        end
      end
      if status.quitRequested then
        local ow = overworld(game)
        print(("KB_END map=%s party=%d battles_pushed=%d"):format(
          word(ow and ow.map and ow.map.id), #(game.save.party or {}), pushedBattles))
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
