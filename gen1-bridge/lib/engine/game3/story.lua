-- game3 story (GEN3_BRIDGE_PLAN.md §4.6, phase G3C): object visibility by
-- hide flags, and the current map's changed cells. Flags themselves are the
-- save view's (boot.lua: every set flag by its Emerald name), so story.flags
-- needs nothing here.
--
-- Toggles: every map's object templates (the script bundle's events,
-- Space.bundle.events[map].objects, the source Objects.loadMap prefers,
-- src/core/game3/objects.lua:634-643, else game.data.maps[map].objects)
-- carry a hide flag; an object is visible while its flag is clear, and a
-- flag of 0 or 0xFFFF means always visible (scripting/space.lua:663-671,
-- Space.objectVisible). The client's default is visible, so the toggles are
-- the objects whose flag is set now: { map = { O<localId> = false } }, ids as
-- the NPC observer names them (observe.lua). The camera (localId 127) and
-- objects carried across a seam (0xC0+) are left out.
--
-- Cells: Gen 3's runtime terrain is the current layout's override layer
-- (layout_native.lua:124-140, written by Field.setMetatile, field.lua:2194-
-- 2241, door repairs and layout swaps), cleared and rebuilt by the map's
-- scripts on every load and never saved (world note §5). storyCells() gives
-- the current map's cells whose metatile differs from the layout's own
-- (layout.cells), keyed y*1024+x as the engine keys them; cellBase(x, y)
-- the layout's own metatile, for a cell that went back. Gen 1's block story
-- (storyMap, liveBlocks, ...) stays the stub's: nil.
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Story = {}

local CAMERA_LID = 127   -- camera_object.lua:6
local SEAM_LID = 0xC0    -- objects.lua:677-729
local NO_FLAGS = { [0] = true, [0xFFFF] = true }

function Story.install(b)
  local def = b.def

  -- ---- toggles

  local cache = { bundle = nil, data = nil, list = nil }
  -- every hideable template once per bundle: { map, name, flag }
  local function hideable()
    local Space = b.g3("scripting.space")
    local bundle = type(Space) == "table" and Space.bundle or nil
    local g = b.game
    local data = type(g) == "table" and type(g.data) == "table" and g.data.maps or nil
    if cache.list and cache.bundle == bundle and cache.data == data then return cache.list end
    local list, seen = {}, {}
    local function add(map, defs)
      if not b.symbol(map) or type(defs) ~= "table" then return end
      for _, d in ipairs(defs) do
        if type(d) == "table" then
          local lid = tonumber(d.localId or d.index)
          local flag = tonumber(d.flag or d.flagId)
          if lid and lid > 0 and lid ~= CAMERA_LID and lid < SEAM_LID and flag and not NO_FLAGS[flag] then
            local name = ("O%d"):format(lid)
            local k = map .. "/" .. name
            if not seen[k] then
              seen[k] = true
              -- the store's keys for this flag: the number, its string and hex forms (flags.lua:377-389)
              list[#list + 1] = { map = map, name = name, flag = flag, skey = tostring(flag),
                                  hkey = ("0x%X"):format(flag) }
            end
          end
        end
      end
    end
    local events = type(bundle) == "table" and type(bundle.events) == "table" and bundle.events or {}
    for map, ev in pairs(events) do
      if type(ev) == "table" then add(map, ev.objects or ev.objectEvents) end
    end
    for map, mdef in pairs(type(data) == "table" and data or {}) do
      if type(mdef) == "table" and events[map] == nil then add(map, mdef.objects) end
    end
    table.sort(list, function(x, y) return x.map < y.map or (x.map == y.map and x.name < y.name) end)
    cache.bundle, cache.data, cache.list = bundle, data, list
    return list
  end

  -- ({ map = { O<lid> = false } }, skipped): the objects hidden by their flag.
  -- The flags are read straight from the live store (Flags.getFlag's keys, by
  -- number: Emerald's own ids, never a name through FireRed's tables).
  def("storyToggles", function()
    local out = {}
    local store = b.flagStore()
    local fl = type(store) == "table" and type(store.flags) == "table" and store.flags or nil
    if not fl then return out, 0 end
    for _, o in ipairs(hideable()) do
      if fl[o.flag] == true or fl[o.skey] == true or fl[o.hkey] == true then
        local m = out[o.map]
        if not m then
          m = {}
          out[o.map] = m
        end
        m[o.name] = false
      end
    end
    return out, 0
  end)

  -- ---- cells

  local function layout()
    local Map = b.g3("map")
    local id = type(Map) == "table" and Map.current or nil
    if not b.symbol(id) then return nil end
    local mdef = b.ask(Map, "currentDef", nil)
    local lay = type(mdef) == "table" and mdef.midLayout or nil
    if type(lay) ~= "table" then return nil end
    return id, lay
  end

  local function baseMid(lay, x, y)
    local w = math.floor(tonumber(lay.width) or 0)
    local tw = math.floor(tonumber(lay.trueWidth) or w)
    local th = math.floor(tonumber(lay.trueHeight) or tonumber(lay.height) or 0)
    if x < 0 or y < 0 or x >= tw or y >= th or w < 1 then return nil end
    local cell = type(lay.cells) == "table" and lay.cells[y * w + x + 1] or nil
    return type(cell) == "table" and tonumber(cell.mid) or nil
  end

  -- { map, cells = { [y*1024+x] = metatile } } in the field, else nil.
  def("storyCells", function()
    if not b.inField() then return nil end
    local id, lay = layout()
    if not id then return nil end
    local cells = {}
    for k, ov in pairs(type(lay.overrides) == "table" and lay.overrides or {}) do
      k = tonumber(k)
      local mid = type(ov) == "table" and tonumber(ov.mid) or nil
      if k and mid then
        local x, y = k % 1024, math.floor(k / 1024)
        -- (MG8: a Wild Routes clearing's cells are the map's own: the export bakes them in, routes.lua)
        local base = (type(b.routeBase) == "function" and b.routeBase(id, x, y)) or baseMid(lay, x, y)
        if base ~= nil and base ~= mid then cells[k] = mid end
      end
    end
    return { map = id, cells = cells }
  end)

  -- The current map's own metatile at (x, y), or nil.
  def("cellBase", function(x, y)
    local id, lay = layout()
    if not lay or type(x) ~= "number" or type(y) ~= "number" then return nil end
    x, y = math.floor(x), math.floor(y)
    return (type(b.routeBase) == "function" and b.routeBase(id, x, y)) or baseMid(lay, x, y)
  end)
end

return Story
