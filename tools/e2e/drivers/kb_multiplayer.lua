-- Kanto Bridge test driver (M9 e2e: online save pairing and the port
-- fallback; tools/e2e/test_multiplayer.py). KB_SCENE picks the part:
--
--   pairing1  first clears what an earlier failed run may have left (the
--             test worlds' pairings and their slots, the home slot active
--             again), then leaves the game at its boot screens and serves:
--             the test binds world A, A again, world B, then a singleplayer
--             world; gen1-bridge switches the active save slot at the title.
--   pairing2  the same install after a restart: the test binds A (the slot
--             pairing1 created); the driver then starts a New Game in A's
--             slot and saves it (KB_SAVED); the test binds B in game (the
--             game is saved into A's slot first, then the title); the driver
--             starts a New Game in B's slot and leaves it unsaved (KB_FRESH);
--             the test binds A (A's slot has a save: the switch waits); after
--             30 frames of that the driver quits to the title as a player
--             would (KB_QUIT_TO_TITLE) and the switch runs there; the test
--             binds the singleplayer world (home). At debug.quit the driver
--             forgets A and B and deletes their slots (KB_CLEANED).
--   cleanup   only the clean-up, then quits (after a failed run).
--   port      moves gen1-bridge to KB_TEST_PORT, which the test keeps busy
--             (with the next one), and prints KB_LISTENING with the port
--             gen1-bridge took.
--
-- Markers: KB_BOOTED, KB_ACTIVE slot=<n> ingame=<bool> (each change of the
-- active save slot or of being in game), KB_SAVE_WRITING slot=<n> (each
-- save.writing, with the slot it lands in), KB_SAVED, KB_FRESH,
-- KB_QUIT_TO_TITLE, KB_CLEANED, KB_LISTENING, KB_QUIT; KB_FAIL on a timeout
-- (after the same clean-up). Slot numbers, ports, booleans and counts only:
-- the world ids are the test's made-up strings and are never printed.
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- (refs/gen1recomp/main.lua:755-759, :1136-1157). It uses the engine's own
-- slot API (src/core/SaveData.lua:1705-1900) for the clean-up only.

io.stdout:setvbuf("no") -- LÖVE block-buffers stdout; this also unbuffers mod.log

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27169
local SCENE = os.getenv("KB_SCENE") or "pairing1"
local EXTRA_SECONDS = tonumber(os.getenv("KB_DRIVER_EXTRA") or "") or 0 -- the runner's export allowance
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or (300 + EXTRA_SECONDS)
local WORLD_A, WORLD_B = os.getenv("KB_WORLD_A"), os.getenv("KB_WORLD_B")

local SaveData = require("src.core.SaveData")
local GameVersion = require("src.core.GameVersion")

local function word(v)
  local s = tostring(v)
  return (s:gsub("[^%w_.:+%-]", "?"))
end

local function num(id)
  return tonumber(tostring(id):match("^slot(%d+)$")) or 0
end

local function active() return SaveData.activeSlot(GameVersion.get()) end

local function overworld(game)
  local states = game and game.stack and game.stack.states or {}
  for i = #states, 1, -1 do
    local s = states[i]
    if type(s) == "table" and s.isOverworld and s.map and s.player then return s end
  end
  return nil
end

local function onTop(game)
  local top = game and game.stack and game.stack:top()
  return top ~= nil and top.isOverworld == true and top.map ~= nil
end

-- Forgets the test worlds' pairings, deletes their slots (never the home
-- one) and makes the home slot active again. Returns the count deleted.
local function cleanup(status)
  local version = GameVersion.get()
  local home = status and status.homeSlot and status.homeSlot() or nil
  local deleted = 0
  for _, world in ipairs({ WORLD_A, WORLD_B }) do
    local id = world and status and status.forgetWorld(world) or nil
    if id and id ~= home then
      if home and active() == id then SaveData.setActiveSlot(version, home) end
      if SaveData.deleteSlot(version, id) then deleted = deleted + 1 end
    end
  end
  if home and active() ~= home then SaveData.setActiveSlot(version, home) end
  return deleted, home
end

local statusRef
local function fail(reason)
  local ok, deleted = pcall(cleanup, statusRef)
  print(("KB_FAIL %s cleaned=%s"):format(reason, word(ok and deleted or "error")))
  love.event.quit(1)
  while true do coroutine.yield() end
end

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local step2 = "wantA"
  local pendingFrames = 0
  local lastActive, lastIngame
  local Runtime = require("src.mods.Runtime")
  Runtime.events:on("save.writing", function()
    print(("KB_SAVE_WRITING slot=%d"):format(num(active())))
  end, 0, "kb-multiplayer-driver")
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    statusRef = status
    if phase == "ready" then
      if status and status.ready then
        status.configure({ port = PORT })
        if SCENE == "pairing1" or SCENE == "cleanup" then
          local deleted = cleanup(status)
          print(("KB_PRECLEANED deleted=%d"):format(deleted))
        end
        if SCENE == "cleanup" then
          print(("KB_CLEANED active=%d"):format(num(active())))
          print("KB_QUIT sessions=0 code=0")
          return
        end
        local top = game.stack:top()
        print(("KB_BOOTED scene=%s top=%s port=%s active=%d home=%d pairedA=%d pairedB=%d"):format(
          word(SCENE), word(top and top.screenId), word(status.portConfigured), num(active()),
          num(status.homeSlot()), num(WORLD_A and status.pairedSlot(WORLD_A)),
          num(WORLD_B and status.pairedSlot(WORLD_B))))
        phase = SCENE == "port" and "listen" or "serve"
      end
    elseif phase == "listen" then
      if status.bound then
        print(("KB_LISTENING port=%s configured=%s fallback=%s"):format(
          word(status.port), word(status.portConfigured), word(status.portFallback)))
        phase = "serve"
      end
    elseif phase == "serve" then
      local a, ingame = active(), overworld(game) ~= nil
      if a ~= lastActive or ingame ~= lastIngame then
        lastActive, lastIngame = a, ingame
        print(("KB_ACTIVE slot=%d ingame=%s"):format(num(a), word(ingame)))
      end
      if SCENE == "pairing2" then
        local pa = WORLD_A and status.pairedSlot(WORLD_A)
        local pb = WORLD_B and status.pairedSlot(WORLD_B)
        if step2 == "wantA" and pa and a == pa and not ingame then
          -- New Game without Oak's speech (src/core/Game.lua:173-200)
          game:startNewGame({ intro = false })
          step2 = "newA"
        elseif step2 == "newA" and onTop(game) then
          local written = game:writeSave()
          print(("KB_SAVED slot=%d ok=%s"):format(num(active()), word(written == true)))
          step2 = "wantB"
        elseif step2 == "wantB" and pb and a == pb and not ingame then
          game:startNewGame({ intro = false })
          step2 = "newB"
        elseif step2 == "newB" and onTop(game) then
          print(("KB_FRESH slot=%d saved=false"):format(num(active())))
          step2 = "waitPending"
        elseif step2 == "waitPending" and status.pairingPending then
          pendingFrames = pendingFrames + 1
          if pendingFrames >= 30 then
            -- the player quits to the title (START, QUIT: Game:returnToTitle)
            game:returnToTitle()
            print(("KB_QUIT_TO_TITLE pendingFrames=%d"):format(pendingFrames))
            step2 = "done"
          end
        end
      end
      if status.quitRequested then
        if SCENE == "pairing2" then
          local deleted, home = cleanup(status)
          print(("KB_CLEANED deleted=%d active=%d home=%d"):format(deleted, num(active()), num(home)))
        elseif SCENE == "pairing1" then
          -- the next run starts from the home slot whatever happened
          local home = status.homeSlot()
          if home and active() ~= home then
            SaveData.setActiveSlot(GameVersion.get(), home)
            print("KB_HOME_RESTORED")
          end
        end
        print(("KB_QUIT sessions=%s code=%s"):format(word(status.sessions), word(status.quitCode)))
        return
      end
    end
    if love.timer.getTime() - started > SECONDS_LIMIT then
      fail("timeout phase=" .. phase .. " step=" .. step2)
    end
    game = coroutine.yield() or game
  end
end
