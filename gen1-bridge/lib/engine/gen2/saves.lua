-- gen2 saves, the playthrough and the Pokémon Center (GEN2_BRIDGE_PLAN.md
-- §5.7). The slot functions and the queue of saves announced to Minecraft are
-- shared with game3 (lib/engine/common/slots.lua, savequeue.lua): Gen 2 keeps
-- its saves in the same slot registry (src/core/gen2/Save.lua:109-152). A save
-- is Game2:writeSave (src/core/Game2.lua:1022-1042: the save.write veto hook,
-- the snapshot into the save, save.writing, the file), which has no state
-- guard of its own: it is called only when Game2:quickSaveAllowed (:1044-1050,
-- the World's own START gate) says the field could save. The nurse heals
-- through `special HealParty` (src/script/gen2/Specials.lua:526-528), seen in
-- the script.command hook with the decoded op as its fifth argument
-- (src/script/gen2/Vm.lua:1824-1851; G2-S4); wrapping World.healParty would
-- also count whiteouts and Sacred Ash (World.lua:5350, :7085, :11102).
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Saves = {}

Saves.HEAL_SPECIAL = "HealParty"

function Saves.install(b)
  local def = b.def

  -- ---- the playthrough and the title

  -- "title" (the boot phase), "fresh" (a NEW GAME never saved) or "saved"
  -- (loaded by CONTINUE -- `loaded` is the set of save objects save.loaded
  -- brought, game.save itself -- or saved since).
  def("playthrough", function(loaded)
    if not b.inField() then
      if type(b.introRunning) == "function" and b.introRunning() then return "fresh" end
      return "title"
    end
    local s = b.saveObject()
    if type(s) ~= "table" then return "title" end
    if type(loaded) == "table" and loaded[s] then return "saved" end
    local st = b.state()
    if st and type(st.savedSessions) == "table" and st.savedSessions[s] then return "saved" end
    return "fresh"
  end)

  -- True while the main menu is up (src/ui/gen2/MainMenu.lua; its rows were
  -- built for the slot active then).
  def("titleMenuOpen", function()
    local top = b.adapter.top()
    return type(top) == "table" and top.screenId == "Gen2MainMenu"
  end)

  local function saveAllowed()
    local g = b.game
    if not (b.inField() and type(g) == "table" and type(g.quickSaveAllowed) == "function") then return false end
    local ok, allowed = pcall(g.quickSaveAllowed, g)
    return ok and allowed == true
  end
  def("switchGateOpen", saveAllowed)

  local function write(reason)
    local g, st = b.game, b.state()
    if not (st and type(g) == "table" and type(g.writeSave) == "function") then return "error" end
    st.saving = reason
    local ok, written = pcall(g.writeSave, g)
    st.saving = nil
    if not ok then
      b.warnAt("engine.write_save_failed", written)
      return "error"
    end
    if written == false then return "refused" end
    return "written"
  end
  def("switchSave", function() return write("switch") end)

  -- Back to the title (unsaved progress is gone; callers save first):
  -- Game2:returnToTitle (Game2.lua:374-381).
  def("toTitle", function()
    local g = b.game
    if type(g) ~= "table" or type(g.returnToTitle) ~= "function" then return false end
    local ok, err = pcall(g.returnToTitle, g)
    if not ok then b.warnAt("engine.return_to_title_failed", err) end
    return ok
  end)

  def("dropPlaythrough", function()
    local st = b.state()
    if type(st) ~= "table" then return 0 end
    local n = (st.saveQueued ~= nil and 1 or 0) + (tonumber(st.heals) or 0)
    st.saveQueued, st.heals, st.healPending, st.centerSave = nil, 0, nil, nil
    n = n + (type(st.gifts) == "table" and #st.gifts or 0) + (type(st.catches) == "table" and #st.catches or 0)
    st.gifts, st.catches, st.renames, st.healHold = {}, {}, {}, nil
    return n
  end)

  -- ---- the Pokémon Center

  -- The script.command hook (main.lua wraps it for every engine): the engine's
  -- own command runs untouched, with its decoded op handed back; a nurse's heal
  -- arms nurseHealed. The special's name comes from the VM (Vm:specialName,
  -- Vm.lua:2372-2376).
  def("scriptCommand", function(nextFn, ctx, name, args, _bridge, cmd)
    if name == "special" and type(cmd) == "table" then
      local vm = type(ctx) == "table" and ctx.vm or nil
      local ok, special = b.method(vm, "specialName", cmd.id)
      if ok and special == Saves.HEAL_SPECIAL then
        local st = b.state()
        local w = b.ow()
        if st then st.healPending = { map = w and type(w.map) == "table" and b.ext(w.map.id) or nil } end
      end
    end
    return nextFn(ctx, name, args, cmd)
  end)

  -- A nurse heal since the last call: { map, outdoor } once, else nil.
  def("nurseHealed", function()
    local st = b.state()
    local heal = st and st.healPending or nil
    if type(heal) ~= "table" then return nil end
    st.healPending = nil
    return { map = heal.map, outdoor = nil }
  end)
  def("armCenterSave", function()
    local st, s = b.state(), b.saveObject()
    if not (st and s and b.inField()) then return false end
    st.centerSave = { session = s }
    return true
  end)
  def("centerSaveArmed", function()
    local st = b.state()
    return type(st) == "table" and type(st.centerSave) == "table"
  end)
  -- One step of the armed center save: nil while it waits (the field busy or
  -- the save gate closed), else "written", "refused", "error" or "dropped"
  -- (the playthrough went away).
  def("centerSaveStep", function()
    local st = b.state()
    local arm = type(st) == "table" and st.centerSave or nil
    if type(arm) ~= "table" then return nil end
    if arm.session ~= b.saveObject() then
      st.centerSave = nil
      return "dropped"
    end
    if not saveAllowed() then return nil end
    st.centerSave = nil
    return write("center")
  end)
end

return Saves
