-- game3 saves, slots, pairing and the Pokémon Center (GEN3_BRIDGE_PLAN.md
-- §4.7). Gen 3 keeps its saves in the same slot registry as Gen 1
-- (src/core/SaveData.lua:1642-1900: listSlots, createSlot, setActiveSlot,
-- activeSlot, deleteSlot, keyed by version; files saves/<version>/slotN.lua),
-- so the slot functions are gen1.lua's with the game id. A save is
-- Game3:saveGame (src/core/Game3.lua:1028-1072), written only when
-- Game3:quickSaveAllowed (:1019-1026) says the field could save: saveGame
-- itself has no state guards. The nurse heals through `special
-- HealPlayerParty` (scripting/natives.lua:504-507, :1122-1132), seen in the
-- script.command hook (scripting/ops_a.lua:2352-2357).
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Saves = {}

Saves.HEAL_SPECIAL = "HealPlayerParty"

function Saves.install(b)
  local def = b.def

  local function slotId(v) return type(v) == "string" and v:find("^slot%d+$") ~= nil and #v <= 16 end
  local function version() return b.adapter.gameVersion() end
  local function slotApi()
    local SaveData = b.mod("src.core.SaveData")
    if not SaveData then return nil end
    for _, name in ipairs({ "activeSlot", "setActiveSlot", "createSlot" }) do
      if type(SaveData[name]) ~= "function" then return nil end
    end
    return SaveData
  end

  -- ---- slots (gen1.lua's, the engine's shared registry)

  def("slotState", function()
    local v = version()
    local out = { version = v, api = slotApi() ~= nil and v ~= nil, cart = false }
    local SaveData = b.mod("src.core.SaveData")
    if not (v and SaveData) then return out end
    if type(SaveData.activeSlot) == "function" then
      local ok, id = pcall(SaveData.activeSlot, v)
      out.active = ok and slotId(id) and id or nil
    end
    return out
  end)
  def("resolveActiveSlot", function()
    local SaveData, v = slotApi(), version()
    if not (SaveData and v) then return nil end
    if type(SaveData.refreshSlotResolution) == "function" then pcall(SaveData.refreshSlotResolution, v) end
    local ok, id = pcall(SaveData.activeSlot, v)
    return ok and slotId(id) and id or nil
  end)
  def("slotRegistered", function(id)
    local SaveData, v = b.mod("src.core.SaveData"), version()
    if not (slotId(id) and SaveData and v and type(SaveData.loadOptions) == "function") then return false end
    local ok, opts = pcall(SaveData.loadOptions)
    local roots = ok and type(opts) == "table" and opts.saveSlots or nil
    local reg = type(roots) == "table" and roots[v] or nil
    for _, s in ipairs(type(reg) == "table" and type(reg.list) == "table" and reg.list or {}) do
      if s == id then return true end
    end
    return false
  end)
  def("slotHasSave", function(id)
    local SaveData, v = b.mod("src.core.SaveData"), version()
    if not (slotId(id) and b.symbol(v and v:upper()) and SaveData and type(SaveData.persistenceFs) == "function") then
      return false
    end
    local ok, fs = pcall(SaveData.persistenceFs)
    if not (ok and type(fs) == "table" and type(fs.getInfo) == "function") then return false end
    local main = "saves/" .. v .. "/" .. id .. ".lua"
    for _, path in ipairs({ main, main .. ".tmp", main .. ".bak" }) do
      local okInfo, info = pcall(fs.getInfo, path)
      if okInfo and info then return true end
    end
    return false
  end)
  def("labelSlot", function(id, label)
    local SaveData, v = b.mod("src.core.SaveData"), version()
    if not (slotId(id) and SaveData and v and type(SaveData.renameSlot) == "function") then return false end
    local ok, done = pcall(SaveData.renameSlot, v, id, label)
    return ok and done == true
  end)
  def("createSlot", function(label)
    local SaveData, v = slotApi(), version()
    if not (SaveData and v) then return nil end
    local ok, id = pcall(SaveData.createSlot, v)
    if not ok then
      b.warnAt("engine.create_slot_failed", id)
      return nil
    end
    if not slotId(id) then return nil end
    if label then b.adapter.labelSlot(id, label) end
    return id
  end)
  def("deleteSlot", function(id)
    local SaveData, v = b.mod("src.core.SaveData"), version()
    if not (slotId(id) and SaveData and v and type(SaveData.deleteSlot) == "function") then return false end
    local ok, done = pcall(SaveData.deleteSlot, v, id)
    return ok and done == true
  end)
  def("activateSlot", function(id)
    local SaveData, v = slotApi(), version()
    if not (SaveData and v and slotId(id)) then return false end
    local ok, got = pcall(SaveData.setActiveSlot, v, id)
    if not ok then
      b.warnAt("engine.activate_slot_failed", got)
      return false
    end
    return got == id
  end)
  def("saveSlot", function()
    local SaveData = b.mod("src.core.SaveData")
    if not (SaveData and type(SaveData.activeSlot) == "function") then return 0 end
    local ok, id = pcall(SaveData.activeSlot, version())
    local n = ok and type(id) == "string" and tonumber(id:match("^slot(%d+)$")) or nil
    if not n or n < 0 or n > 255 then return 0 end
    return n
  end)

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

  -- ---- saves announced to Minecraft (gen1.lua's queue)

  def("savingReason", function()
    local st = b.state()
    return type(st) == "table" and st.saving or nil
  end)
  def("queueSave", function(reason, pair)
    local st = b.state()
    if not st then return nil, false end
    st.serial = (st.serial or 0) + 1
    local id = ("v-%s-%d"):format(st.token or "x", st.serial)
    local replaced = st.saveQueued ~= nil
    pair = type(pair) == "table" and pair or {}
    local s = b.session()
    st.saveQueued = { save = id, reason = reason, serial = st.serial, pair = pair.pair, at = pair.at, from = s }
    -- the playthrough is a saved one from now on (playthrough)
    if s then
      st.savedSessions = type(st.savedSessions) == "table" and st.savedSessions or setmetatable({}, { __mode = "k" })
      st.savedSessions[s] = true
    end
    return id, replaced
  end)
  def("takeSave", function()
    local st = b.state()
    local q = type(st) == "table" and st.saveQueued or nil
    if type(q) ~= "table" then return nil end
    st.saveQueued = nil
    return { save = q.save, reason = q.reason, pair = q.pair, at = q.at,
             current = q.from ~= nil and q.from == b.session() }
  end)
  def("queuedSaveSerial", function()
    local st = b.state()
    local q = type(st) == "table" and st.saveQueued or nil
    return type(q) == "table" and q.serial or nil
  end)
end

return Saves
