-- game3 saves, the playthrough and the Pokémon Center (GEN3_BRIDGE_PLAN.md
-- §4.7). The slot functions and the queue of saves announced to Minecraft are
-- shared with the gen2 adapter (lib/engine/common/slots.lua, savequeue.lua;
-- MG9). A save is Game3:saveGame (src/core/Game3.lua:1028-1072), written only
-- when Game3:quickSaveAllowed (:1019-1026) says the field could save: saveGame
-- itself has no state guards. The nurse heals through `special
-- HealPlayerParty` (scripting/natives.lua:504-507, :1122-1132), seen in the
-- script.command hook (scripting/ops_a.lua:2352-2357).
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Saves = {}

-- FP5: the party choices of the Frontier's and the Tents' desks, and VAR_RESULT (pokeemerald include/constants/vars.h)
Saves.DESK_SPECIALS = { ChooseHalfPartyForBattle = true, ChoosePartyForBattleFrontier = true }
Saves.VAR_RESULT = 0x800D

Saves.HEAL_SPECIAL = "HealPlayerParty"

function Saves.install(b)
  local def = b.def

  -- ---- the playthrough and the title

  -- "title" (the boot phase), "fresh" (a NEW GAME never saved) or "saved"
  -- (loaded by CONTINUE -- `loaded` is the set of save objects save.loaded
  -- brought, Gen 3's session itself, Game3.lua:540 -- or saved since).
  def("playthrough", function(loaded)
    if not b.inField() then
      -- Birch's speech after NEW GAME (boot phase "oak") is a new game under way, never saved
      local g = b.game
      if type(g) == "table" and g.phase == "boot" and type(g.boot) == "table" and g.boot.phase == "oak" then
        return "fresh"
      end
      return "title"
    end
    local s = b.session()
    if type(s) ~= "table" then return "title" end
    if type(loaded) == "table" and loaded[s] then return "saved" end
    local st = b.state()
    if st and type(st.savedSessions) == "table" and st.savedSessions[s] then return "saved" end
    local meta = s.meta
    if type(meta) == "table" and meta.savedAt ~= nil then return "saved" end
    return "fresh"
  end)

  -- True while the main menu is up (its rows were built for the slot active
  -- then): the custom boot's phase "menu" (src/ui/game3/boot_modules.lua:107).
  def("titleMenuOpen", function()
    local g = b.game
    return type(g) == "table" and g.phase == "boot" and type(g.boot) == "table" and g.boot.phase == "menu"
  end)

  -- The save before a switch may run now: the field could save as the
  -- engine's own quick save would (quickSaveAllowed: the field phase, no HUD
  -- busy, START allowed, no pending ON_FRAME, saveOffered).
  local function saveAllowed()
    local g = b.game
    if not (b.inField() and type(g) == "table" and type(g.quickSaveAllowed) == "function") then return false end
    local ok, allowed = pcall(g.quickSaveAllowed, g)
    return ok and allowed == true
  end
  def("switchGateOpen", saveAllowed)

  local function write(reason)
    local g, st = b.game, b.state()
    if not (st and type(g) == "table" and type(g.saveGame) == "function") then return "error" end
    st.saving = reason
    local ok, written = pcall(g.saveGame, g)
    st.saving = nil
    if not ok then
      b.warnAt("engine.write_save_failed", written)
      return "error"
    end
    if written == false or written == nil then return "refused" end
    return "written"
  end
  def("switchSave", function() return write("switch") end)

  -- Back to the title (unsaved progress is gone; callers save first):
  -- Game3:returnToTitle with the intro skipped (Game3.lua:1445-1479).
  def("toTitle", function()
    local g = b.game
    if type(g) ~= "table" or type(g.returnToTitle) ~= "function" then return false end
    local ok, err = pcall(g.returnToTitle, g, { skipIntro = true })
    if not ok then b.warnAt("engine.return_to_title_failed", err) end
    return ok
  end)

  def("dropPlaythrough", function()
    local st = b.state()
    if type(st) ~= "table" then return 0 end
    local n = (st.saveQueued ~= nil and 1 or 0) + (tonumber(st.heals) or 0)
    st.saveQueued, st.heals, st.healPending, st.centerSave = nil, 0, nil, nil
    -- G3F: what the party part keeps for the dropped playthrough (gen1.lua:5062-5071)
    n = n + (type(st.gifts) == "table" and #st.gifts or 0) + (type(st.catches) == "table" and #st.catches or 0)
    st.gifts, st.catches, st.renames, st.healHold = {}, {}, {}, nil
    return n
  end)

  -- ---- the Pokémon Center

  local function specialName(row)
    if type(row) ~= "table" then return nil end
    local id = tonumber(row.id) or tonumber(row.special) or tonumber(row[1])
    if not id then return nil end
    local Natives = b.g3("scripting.natives")
    local name = Natives and b.ask(Natives, "specialName", nil, id) or nil
    if type(name) ~= "string" then
      local C = b.constants()
      name = C and b.ask(C, "name", nil, C, "specials", id) or nil
    end
    return type(name) == "string" and name or nil
  end

  -- The script.command hook (main.lua wraps it for every engine): the
  -- engine's own command runs untouched; a nurse's heal arms nurseHealed.
  def("scriptCommand", function(nextFn, ctx, op, row)
    if op == "special" then
      local name = specialName(row)
      -- FP5 (FOREIGN_POKEMON_PLAN §4.12): the Battle Frontier's and the Battle Tents' desks choose a party here
      -- (script_pokemon_util.c's ChooseHalfPartyForBattle / ChoosePartyForBattleFrontier); while a client follows
      -- the choice is a cancel (VAR_RESULT 0), so the desk takes its own way out, and the HUD says why (FP9)
      local Map0 = b.g3("map")
      local here = type(Map0) == "table" and type(Map0.current) == "string" and Map0.current or ""
      -- (only at the facilities: Steven's partner battle at the Space Center chooses its half the same way)
      local desk = here:find("BATTLE_FRONTIER", 1, true) or here:find("BATTLE_TENT", 1, true)
      if name and Saves.DESK_SPECIALS[name] and desk then
        local st = b.state()
        local bridge = st and st.bridge
        if type(bridge) == "table" and type(bridge.live) == "function" and bridge.live() == true then
          local vm = type(ctx) == "table" and (ctx.vm or ctx.runner) or nil
          local Rse = b.g3("rse.init")
          if type(vm) == "table" and type(Rse) == "table" and type(Rse.setSpecialVar) == "function" then
            pcall(Rse.setSpecialVar, vm.ctx, Saves.VAR_RESULT, 0)
            b.refuse(here:find("BATTLE_TENT", 1, true) and "battle_tent" or "battle_frontier")
            return nil
          end
        end
      end
      if name and name:find(Saves.HEAL_SPECIAL, 1, true) then
        local st = b.state()
        if st then st.healPending = { map = b.symbol(b.g3("map") and b.g3("map").current) and b.g3("map").current or nil } end
      end
    end
    return nextFn(ctx, op, row)
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
    local st, s = b.state(), b.session()
    if not (st and s and b.inField()) then return false end
    st.centerSave = { session = s }
    return true
  end)
  def("centerSaveArmed", function()
    local st = b.state()
    return type(st) == "table" and type(st.centerSave) == "table"
  end)
  -- One step of the armed center save: nil while it waits (the field busy
  -- or the save gate closed), else "written", "refused", "error" or
  -- "dropped" (the playthrough went away).
  def("centerSaveStep", function()
    local st = b.state()
    local arm = type(st) == "table" and st.centerSave or nil
    if type(arm) ~= "table" then return nil end
    if arm.session ~= b.session() then
      st.centerSave = nil
      return "dropped"
    end
    if not saveAllowed() then return nil end
    st.centerSave = nil
    return write("center")
  end)

end

return Saves
