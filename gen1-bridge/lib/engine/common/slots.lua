-- Save slots, shared by the game3 and gen2 adapters (MG9 moved them from
-- lib/engine/game3/saves.lua; GEN3_BRIDGE_PLAN.md §4.7, GEN2_BRIDGE_PLAN.md
-- §5.7): Gen 2 and Gen 3 keep their saves in the same slot registry as Gen 1
-- (src/core/SaveData.lua:1642-1900: listSlots, createSlot, setActiveSlot,
-- activeSlot, deleteSlot, keyed by version; files saves/<version>/slotN.lua),
-- so these are gen1.lua's slot functions with the game id.
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Slots = {}

function Slots.install(b)
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
end

return Slots
