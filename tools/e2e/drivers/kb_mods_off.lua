-- Kanto Bridge test driver (docs/specs/PROFILE_PLAN.md §5.8): switches the Dramaless Shape mod off in the test
-- install, so no test boot runs its renderer, hotkeys or day/night hooks; gen1-bridge reads its table either way.
-- Gen1Recomp saves the switch for the next boot (Loader:setEnabled, refs/gen1recomp/src/mods/Loader.lua:585-596; the
-- Game owns its loader as game.mods, src/core/Game.lua:54).
--
-- Gen1Recomp runs a driver unsandboxed inside its main.lua (POKEPORT_DRIVER): the returned function becomes a
-- coroutine, resumed once per frame with the Game. Data safety: print only markers and booleans.

io.stdout:setvbuf("no")

local ID = "DRAMALESS_SHAPE"

local function finish(code)
  love.event.quit(code)
  while true do coroutine.yield() end
end

return function(game)
  local loader = game and game.mods
  local found = type(loader) == "table" and type(loader.mods) == "table" and loader.mods[ID]
  if not found then
    print("KB_MODS_OFF absent")
    finish(0)
  end
  local wasEnabled = not (type(loader.disabled) == "table" and loader.disabled[ID])
  local ok = loader:setEnabled(ID, false) == true
  print(("KB_MODS_OFF ok=%s was_enabled=%s"):format(tostring(ok), tostring(wasEnabled)))
  finish(ok and 0 or 1)
end
