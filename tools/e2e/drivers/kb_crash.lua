-- Kanto Bridge test driver (M10 e2e: the handshake's hardening, crash
-- recovery with save pairs, a reconnect in a text box;
-- tools/e2e/test_crash.py). KB_SCENE picks the part:
--
--   crash1   clears what an earlier failed run may have left (the test
--            world's pairing and slot; the home slot active again), then
--            leaves the game at its boot screens and serves. Once the test
--            has bound world W (its own slot, switched to at the title) the
--            driver starts a New Game without the intro there (KB_NEWGAME),
--            then pushes a text box of its own (made-up words, KB_TEXT). Once
--            the test has read it, the driver saves the game (Game:writeSave,
--            KB_SAVED), waits for gen1-bridge to announce it, and gives a
--            Pokemon through the engine's own give_pokemon command (KB_GAVE:
--            its dex number only). Then it waits: the test kills the process
--            without saving.
--   crash2   the same install after the kill: once the test has bound W again
--            (the game at the title, already on W's slot), the driver
--            continues the saved game the way the title's CONTINUE does
--            (SaveData.load + Game:restoreSave, src/core/Game.lua:224-242,
--            :1475-1537; KB_CONTINUED); at debug.quit it forgets W, deletes W's
--            slot and makes the home slot active again (KB_CLEANED).
--   cleanup  only the clean-up, then quits (after a failed run).
--
-- Markers: KB_PRECLEANED, KB_BOOTED, KB_ACTIVE slot=<n> ingame=<bool>,
-- KB_NEWGAME, KB_TEXT, KB_SAVED, KB_GAVE, KB_CONTINUED, KB_CLEANED, KB_QUIT;
-- KB_FAIL on a timeout. Slot numbers, dex numbers, booleans and counts only:
-- the world id is the test's made-up string and is never printed.
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- (refs/gen1recomp/main.lua:755-759, :1136-1157).

io.stdout:setvbuf("no") -- LÖVE block-buffers stdout; this also unbuffers mod.log

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27202
local SCENE = os.getenv("KB_SCENE") or "crash1"
local EXTRA_SECONDS = tonumber(os.getenv("KB_DRIVER_EXTRA") or "") or 0 -- the runner's export allowance
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or (300 + EXTRA_SECONDS)
local WORLD = os.getenv("KB_WORLD")
local GIFT_DEX = 25

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
  return top ~= nil and top.isOverworld == true and top.map ~= nil and not (top.player and top.player.moving)
end

-- Forgets the test world's pairing, deletes its slot (never the home one) and
-- makes the home slot active again. Returns the count deleted.
local function cleanup(status)
  local version = GameVersion.get()
  local home = status and status.homeSlot and status.homeSlot() or nil
  local deleted = 0
  local id = WORLD and status and status.forgetWorld(WORLD) or nil
  if id and id ~= home then
    if home and active() == id then SaveData.setActiveSlot(version, home) end
    if SaveData.deleteSlot(version, id) then deleted = deleted + 1 end
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

-- The species with this national dex number (the import's own records).
local function speciesOf(game, dex)
  for id, rec in pairs(game.data and game.data.pokemon or {}) do
    if type(rec) == "table" and tonumber(rec.dex) == dex then return id end
  end
  return nil
end

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local step = "wait"
  local box
  local lastActive, lastIngame
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    statusRef = status
    if phase == "ready" then
      if status and status.ready then
        status.configure({ port = PORT })
        if SCENE == "crash1" or SCENE == "cleanup" then
          print(("KB_PRECLEANED deleted=%d"):format((cleanup(status))))
        end
        if SCENE == "cleanup" then
          print(("KB_CLEANED active=%d"):format(num(active())))
          print("KB_QUIT sessions=0 code=0")
          return
        end
        local top = game.stack:top()
        print(("KB_BOOTED scene=%s top=%s port=%s active=%d home=%d paired=%d"):format(
          word(SCENE), word(top and top.screenId), word(status.portConfigured), num(active()),
          num(status.homeSlot()), num(WORLD and status.pairedSlot(WORLD))))
        phase = "serve"
      end
    elseif phase == "serve" then
      local a, ingame = active(), overworld(game) ~= nil
      if a ~= lastActive or ingame ~= lastIngame then
        lastActive, lastIngame = a, ingame
        print(("KB_ACTIVE slot=%d ingame=%s"):format(num(a), word(ingame)))
      end
      local paired = WORLD and status.pairedSlot(WORLD)
      if SCENE == "crash1" then
        if step == "wait" and paired and a == paired and not ingame and (status.sessionBinds or 0) >= 1 then
          -- New Game without Oak's speech (src/core/Game.lua:173-200)
          game:startNewGame({ intro = false })
          print(("KB_NEWGAME slot=%d"):format(num(active())))
          step = "newgame"
        elseif step == "newgame" and onTop(game) then
          -- a text box of the driver's own, with made-up words
          local TextBox = require("src.render.TextBox")
          box = TextBox.new(game, "KANTO BRIDGE\nTEST LINE ONE.\fTEST LINE\nTWO.", function() end)
          game.stack:push(box)
          print("KB_TEXT pages=2")
          step = "text"
        elseif step == "text" and onTop(game) and game.stack:top() ~= box then
          local written = game:writeSave()
          print(("KB_SAVED slot=%d ok=%s"):format(num(active()), word(written == true)))
          step = "saved"
        elseif step == "saved" and (status.saves or 0) >= 1 and onTop(game)
            and not (game.overworld.runner and game.overworld.runner:isRunning()) then
          -- a gift through the engine's own give_pokemon command (no
          -- nickname prompt), as a script would give it
          local species = speciesOf(game, GIFT_DEX)
          if not species then fail("no_species") end
          game.overworld.runner:run({ { "give_pokemon", species, 5, true } })
          print(("KB_GAVE dex=%d party=%d"):format(GIFT_DEX, #(game.save.party or {})))
          step = "gave"
        end
      elseif SCENE == "crash2" then
        if step == "wait" and paired and a == paired and not ingame and (status.sessionBinds or 0) >= 1 then
          -- CONTINUE, as the title's menu runs it (Game:makeTitleState's
          -- onContinue, src/core/Game.lua:231-242)
          local loaded, recovered = SaveData.load()
          if not loaded then fail("no_save") end
          game:restoreSave(loaded, recovered, { freshBoot = true, continued = true })
          print(("KB_CONTINUED slot=%d party=%d"):format(num(active()), #(game.save.party or {})))
          step = "continued"
        end
      end
      if status.quitRequested then
        local deleted, home = cleanup(status)
        print(("KB_CLEANED deleted=%d active=%d home=%d"):format(deleted, num(active()), num(home)))
        print(("KB_QUIT sessions=%s code=%s"):format(word(status.sessions), word(status.quitCode)))
        return
      end
    end
    if love.timer.getTime() - started > SECONDS_LIMIT then
      fail("timeout phase=" .. phase .. " step=" .. step)
    end
    game = coroutine.yield() or game
  end
end
