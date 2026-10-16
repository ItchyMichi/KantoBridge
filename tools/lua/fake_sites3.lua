-- The parts of Gen1Recomp v0.3.85's Gen 3 engine that the game3 adapter's
-- sites part reads (lib/engine/game3/sites.lua; docs/specs/
-- STORY_EVENTS_PLAN.md SE1, SE2, SE6, SE7), on top of fake_engine3.lua (and
-- fake_export3.lua, when attached first: its cache, roots and behaviours are
-- reused): the script bundle's decoded rows and map scripts, Opcodes.key,
-- the constants' metatile labels and special names, the collision byte a
-- passable metatile gets, the behaviour predicates and the door repair's
-- warp behaviours, alternate layouts in the cache, the engine writers'
-- tables (Mauville and Petalburg Gyms, the Mirage Tower, the lottery
-- laptop), Emerald's weather state and the field's flash level. Everything
-- is synthetic: made-up maps (EM_TEST_*), metatiles, behaviours, flags and
-- vars; names are engine constant names.
--
--   FakeSites3.attach(E)
--   E.script(key, rows)               a decoded script list (disasm.lua:41-125 rows)
--   E.mapEvents(map, ev)              a map's events: { mapScripts, objects, bgEvents, coordEvents }
--   E.siteMap(map, def, cells)        game.data.maps[map]: def plus a layout of cells[y][x] = { mid, coll, elev }
--   E.altLayout(id, w, h, fn)         an alternate layout in the cache (layout_files.lua + layouts/<key>.mid)
--   E.weather(id, opts)               the weather the field draws (field_weather_rse.lua R.state)
--   E.flashLevel(level)               the field's flash level (field_view.lua:46)
--
-- Engine files (refs/gen1recomp src/core/game3/): scripting/opcodes.lua:75-78, scripting/space.lua:115-132,
-- scripting/collision.lua (fromCell), collision.lua:155-158 (isWarpMetatileBehavior), :396-402, :506, :524 (the
-- predicates), mb.lua:73-75 (MB.id), constants.lua:39-47 (id, require), dataset.lua:323-326 (isIndoorMapType),
-- scripting/ops_a.lua:243-298 (layout_files, decodeMidLayout), profile.lua:90, rse/story_specials.lua,
-- mirage_tower.lua:149-155, rse/lottery.lua:126-134, field_weather_rse.lua:48-49, weather.lua (_suspended),
-- field_view.lua:46.

local FakeSites3 = {}

-- Made-up metatile behaviours (MB_* names as mb.lua keeps them).
FakeSites3.MB = { MB_TEST_WALL = 0x10, MB_TEST_WATER = 0x20, MB_ASHGRASS = 0x30, MB_THIN_ICE = 0x31,
                  MB_CRACKED_ICE = 0x32, MB_CRACKED_FLOOR = 0x33, MB_TELEVISION = 0x34, MB_TEST_WARP = 0x60 }
-- Made-up metatile labels (constants/emerald/metatile_labels.lua's names).
FakeSites3.LABELS = {
  METATILE_Fallarbor_AshGrass = 40, METATILE_Fallarbor_NormalGrass = 41, METATILE_Lavaridge_NormalGrass = 42,
  METATILE_SootopolisGym_Ice_Cracked = 43, METATILE_SootopolisGym_Ice_Broken = 44,
  METATILE_Cave_CrackedFloor = 45, METATILE_Cave_CrackedFloor_Hole = 46,
  METATILE_Pacifidlog_SkyPillar_CrackedFloor_Hole = 47, METATILE_Building_TV_On = 48, METATILE_Building_TV_Off = 49,
  METATILE_Shop_Laptop1_Normal = 50, METATILE_Shop_Laptop1_Flash = 51,
  METATILE_MauvilleGym_PressedSwitch = 52, METATILE_MauvilleGym_RaisedSwitch = 53,
  METATILE_MauvilleGym_Barrier_On = 54, METATILE_MauvilleGym_Barrier_Off = 55,
}

function FakeSites3.attach(E)
  local mods = E.modules
  local MB = FakeSites3.MB
  for name, id in pairs(MB) do E.MB_IDS[name] = id end

  -- ---- the bundle (space.lua:115-132): rows by key, events by map
  E.Space.bundle.scripts = E.Space.bundle.scripts or {}
  function E.script(k, rows) E.Space.bundle.scripts[k] = rows end
  function E.mapEvents(map, ev) E.Space.bundle.events[map] = ev end
  E.Opcodes = {}
  function E.Opcodes.key(addr) -- opcodes.lua:75-78
    if type(addr) == "string" then return addr end
    return ("g3:%08x"):format(tonumber(addr) or 0)
  end
  mods["src.core.game3.scripting.opcodes"] = E.Opcodes

  -- ---- constants: special names and metatile labels (constants.lua:39-47 id/require, :69-71 specialName)
  E.constTables.specials = E.constTables.specials or {}
  local specials = { [20] = "MauvilleGymPressSwitch", [21] = "MauvilleGymSetDefaultBarriers",
                     [22] = "MauvilleGymDeactivatePuzzle", [23] = "PetalburgGymUnlockRoomDoors",
                     [24] = "StartMirageTowerShake", [25] = "DoLotteryCornerComputerEffect",
                     [26] = "IsMirageIslandPresent" }
  for id, name in pairs(specials) do E.constTables.specials[id] = name end
  local Methods = getmetatable(E.Constants.of("emerald")).__index
  function Methods:specialName(id) return E.constTables.specials[tonumber(id)] end
  -- constants.lua:39-41: a name's id in a kind's table (the made-up tables here; metatile labels from LABELS)
  function Methods:id(kind, name)
    if kind == "metatile_labels" then return FakeSites3.LABELS[name] end
    local raw = self:raw(kind)
    return raw and raw.byName[name] or nil
  end
  function Methods:require(kind, name)
    local v = self:id(kind, name)
    if v == nil then error("game3 constants: emerald has no " .. tostring(kind) .. " " .. tostring(name), 2) end
    return v
  end

  -- ---- behaviours (interaction_scripts.lua:50-55) and the collision byte of a passable metatile
  E.InteractionScripts = E.InteractionScripts or { behaviors = {} }
  mods["src.core.game3.scripting.interaction_scripts"] = E.InteractionScripts
  E.ScriptColl = {}
  -- scripting/collision.lua fromCell (made up): a wall or a TV 0x07, water 0x29, a warp behaviour a door's 0x71
  function E.ScriptColl.fromCell(mid, mapColl, beh, kind)
    if beh == MB.MB_TEST_WALL or beh == MB.MB_TELEVISION then return 0x07 end
    if beh == MB.MB_TEST_WATER then return 0x29 end
    if beh == MB.MB_TEST_WARP then return 0x71 end
    return 0x00
  end
  mods["src.core.game3.scripting.collision"] = E.ScriptColl
  local C = E.Collision
  function C.isAshGrass(beh) return beh == MB.MB_ASHGRASS end
  function C.isThinIce(beh) return beh == MB.MB_THIN_ICE end
  function C.isCrackedIce(beh) return beh == MB.MB_CRACKED_ICE end
  function C.isCrackedFloor(beh) return beh == MB.MB_CRACKED_FLOOR end
  local warpBefore = C.isWarpMetatileBehavior
  function C.isWarpMetatileBehavior(beh) return beh == MB.MB_TEST_WARP or (warpBefore and warpBefore(beh)) or false end
  function E.MB.id(name) return E.MB_IDS["MB_" .. tostring(name):gsub("^MB_", "")] end

  -- ---- maps (dataset.lua:122-125 game.data.maps; layout_native.lua:15-34) and indoor map types (dataset.lua:323)
  E.game.data = E.game.data or { maps = {} }
  E.game.data.maps = E.game.data.maps or {}
  function E.siteMap(map, def, cells)
    local h, w = #cells, #cells[1]
    local flat = {}
    for y = 0, h - 1 do
      for x = 0, w - 1 do
        local c = cells[y + 1][x + 1]
        flat[y * w + x + 1] = { mid = c[1], coll = c[2] or 0, elev = c[3] or 3 }
      end
    end
    def.id = map
    def.midLayout = { width = w, height = h, trueWidth = w, trueHeight = h, cells = flat, overrides = {}, pair = def.pair }
    E.game.data.maps[map] = def
    return def
  end
  E.Dataset = E.Dataset or {}
  E.Dataset.isIndoorMapType = function(mapType) return tonumber(mapType) == 8 or tonumber(mapType) == 9 end
  mods["src.core.game3.dataset"] = E.Dataset
  if not E.Dataset.cache then
    E.cacheFiles = E.cacheFiles or {}
    local cache = {}
    function cache:read(rel) return E.cacheFiles[rel] end
    E.Dataset.cache = function() return cache end
  end
  E.Extract = E.Extract or { CACHE_ROOT = "data/generated/gba", NATIVE_ROOT = "data/generated/gba/native" }
  mods["src.import.gba.extract_island1"] = E.Extract

  -- ---- alternate layouts (ops_a.lua:243-298): layout_files.lua names a key per id; a fake .mid blob is the Lua
  -- source of the decoded layout ({ width, height, cells }), which this fake's decodeMidLayout loads
  E.layoutFiles = {}
  local function writeFiles()
    local parts = {}
    for id, k in pairs(E.layoutFiles) do parts[#parts + 1] = ("[%d] = %q"):format(id, k) end
    E.cacheFiles[E.Extract.NATIVE_ROOT .. "/layout_files.lua"] = "return { " .. table.concat(parts, ", ") .. " }"
  end
  function E.altLayout(id, w, h, fn)
    local key = ("TEST_ALT_%d"):format(id)
    E.layoutFiles[id] = key
    writeFiles()
    local rows = {}
    for y = 0, h - 1 do
      for x = 0, w - 1 do
        local mid, coll, elev = fn(x, y)
        rows[#rows + 1] = ("{ mid = %d, coll = %d, elev = %d }"):format(mid, coll or 0, elev or 3)
      end
    end
    E.cacheFiles[E.Extract.NATIVE_ROOT .. "/layouts/" .. key .. ".mid"] =
      ("return { width = %d, height = %d, cells = { %s } }"):format(w, h, table.concat(rows, ", "))
  end
  E.NativePack = { decodeMidLayout = function(blob)
    local chunk = load(blob, "=fake.mid", "t", {})
    return chunk and chunk() or nil
  end }
  mods["src.import.gba.native_pack"] = E.NativePack
  E.Profile = { family = function() return "rse" end }
  mods["src.core.game3.profile"] = E.Profile

  -- ---- the engine writers' tables: Mauville Gym (story_specials.lua's functions with get/set: here one switch at
  -- (1, 1) and the barriers in row 3 toggling between on and off), Petalburg Gym's doors (room 1: (2, 1) and below),
  -- the Mirage Tower's hidden cells, the lottery laptop
  local L = FakeSites3.LABELS
  E.StorySpecials = { MAUVILLE_SWITCHES = { { 1, 1 } }, PETALBURG_DOORS = { [1] = { { 2, 1 } } }, METATILE_ROW_WIDTH = 8 }
  function E.StorySpecials.mauvillePressSwitch(which, get, set, Cn, switches)
    for i, xy in ipairs(switches or E.StorySpecials.MAUVILLE_SWITCHES) do
      set(xy[1], xy[2], (i - 1 == which) and L.METATILE_MauvilleGym_PressedSwitch or L.METATILE_MauvilleGym_RaisedSwitch, false)
    end
  end
  local function sweep(get, set, toOff)
    for x = 0, 3 do
      local m = get(x, 3)
      if m == L.METATILE_MauvilleGym_Barrier_On then set(x, 3, L.METATILE_MauvilleGym_Barrier_Off, false)
      elseif m == L.METATILE_MauvilleGym_Barrier_Off and not toOff then set(x, 3, L.METATILE_MauvilleGym_Barrier_On, true) end
    end
  end
  function E.StorySpecials.mauvilleSetDefaultBarriers(get, set, Cn) sweep(get, set, false) end
  function E.StorySpecials.mauvilleDeactivatePuzzle(get, set, Cn, switches)
    for _, xy in ipairs(switches or E.StorySpecials.MAUVILLE_SWITCHES) do set(xy[1], xy[2], L.METATILE_MauvilleGym_PressedSwitch, false) end
    sweep(get, set, true)
  end
  function E.StorySpecials.slidingDoorMetatile(Cn, frame) return 60 + frame end
  function E.StorySpecials.petalburgSetDoorMetatiles(room, mid, set)
    for _, xy in ipairs(E.StorySpecials.PETALBURG_DOORS[room] or {}) do
      set(xy[1], xy[2], mid, true)
      set(xy[1], xy[2] + 1, mid + 8, true)
    end
  end
  mods["src.core.game3.rse.story_specials"] = E.StorySpecials
  E.mirageTower = { invisibleMetatiles = {} }
  E.FxRse = { fc = function() return { mirageTower = E.mirageTower } end }
  mods["src.core.game3.field_effects_rse"] = E.FxRse
  E.Lottery = { LAPTOP = { { x = 3, y = 0, normal = "METATILE_Shop_Laptop1_Normal", flash = "METATILE_Shop_Laptop1_Flash" } } }
  mods["src.core.game3.rse.lottery"] = E.Lottery

  -- ---- SE6: Emerald's weather state (field_weather_rse.lua:48-49) and the suspension (weather.lua); SE7: the
  -- field's flash level (field_view.lua:46)
  E.FieldWeather = { state = { started = false, initialized = false, currWeather = 0, nextWeather = 0 } }
  mods["src.core.game3.field_weather_rse"] = E.FieldWeather
  E.WeatherMod = { _suspended = false }
  mods["src.core.game3.weather"] = E.WeatherMod
  function E.weather(id, o)
    o = o or {}
    local S = E.FieldWeather.state
    S.started, S.initialized = o.started ~= false, o.initialized ~= false
    S.currWeather, S.nextWeather = id, o.next or id
    E.WeatherMod._suspended = o.suspended == true
  end
  E.FieldView = { flashLevel = 0 }
  mods["src.core.game3.field_view"] = E.FieldView
  function E.flashLevel(level) E.FieldView.flashLevel = level end
end

return FakeSites3
