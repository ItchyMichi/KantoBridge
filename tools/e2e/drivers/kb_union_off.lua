-- Kanto Bridge test driver (ENGINE_UPGRADE_PLAN UP1 item 4, decision U3): switches the engine's Union Room
-- setting off in the test install, so every later test boot runs with vanilla Pokemon Centers. The setting is
-- the key `unionRoom` in options.lua (refs/gen1recomp src/online/union/Setting.lua:3-7: absent means ON), read
-- at data load (src/core/Data.lua:185 -> src/world/gen1/UnionCenters.lua:392-394), so the boot this driver runs
-- in keeps whatever it loaded; the next one starts clean. Written through the engine's own SaveData.loadOptions
-- (src/core/SaveData.lua:1025, the full on-disk table under the defaults) and saveOptions (:896, a whole-file
-- rewrite of that table), the way kb_mods_off.lua uses the loader's own switch.
--
-- Gen1Recomp runs a driver unsandboxed inside its main.lua (POKEPORT_DRIVER): the returned function becomes a
-- coroutine, resumed once per frame with the Game. Data safety: print only markers, booleans and words.

io.stdout:setvbuf("no")

local function finish(code)
  love.event.quit(code)
  while true do coroutine.yield() end
end

return function(game)
  local okS, SaveData = pcall(require, "src.core.SaveData")
  if not (okS and type(SaveData) == "table" and type(SaveData.loadOptions) == "function"
          and type(SaveData.saveOptions) == "function") then
    print("KB_UNION_OFF ok=false reason=no_savedata")
    finish(1)
  end
  local okL, opts = pcall(SaveData.loadOptions)
  if not (okL and type(opts) == "table") then
    print("KB_UNION_OFF ok=false reason=load_failed")
    finish(1)
  end
  local was = opts.unionRoom == false and "off" or "on"
  opts.unionRoom = false
  local okW = pcall(SaveData.saveOptions, opts)
  -- what this boot loaded: the Union Room's second floor is one of the maps its patch adds
  -- (lib/kb_engine.lua engine.unionRoomPatched)
  local maps = game and type(game.data) == "table" and type(game.data.maps) == "table" and game.data.maps or nil
  local patched = maps ~= nil and maps.POKECENTER_2F ~= nil
  print(("KB_UNION_OFF ok=%s was=%s patched_this_boot=%s"):format(tostring(okW == true), was, tostring(patched)))
  finish(okW and 0 or 1)
end
