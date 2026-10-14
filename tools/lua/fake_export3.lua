-- The parts of Gen1Recomp v0.3.85's Gen 3 engine that the game3 adapter's
-- export reads (lib/engine/game3/export.lua; GEN3_BRIDGE_PLAN.md §5, phase
-- G3D), on top of fake_engine3.lua: a made-up world in game.data.maps with
-- lazy layouts, tileset pairs whose metatile atlases AssetDecode.pair
-- prepares as RGBA per plane, the object-event sprite manifest and sheets,
-- encounter tables, behaviour names, the constants' raw tables, national dex
-- numbers, and love.image / love.data for the RGBA pages. Everything is
-- synthetic: made-up maps (EM_TEST_*), pairs, metatile ids, colours and
-- species.
--
--   FakeExport3.attach(E)        adds the modules and E.loveImage/E.loveData
--   E.exportWorld()              the default world (three maps, two pairs)
--   E.pairArt[pair] = { mids, cols, pixel(plane, mid, x, y) -> r, g, b, a }
--   E.encodes                    PNGs encoded; a fake PNG carries its size in
--                                its IHDR and its RGBA bytes after "RAW"
--
-- Engine files (refs/gen1recomp): src/core/game3/dataset.lua:99-447 (cache,
-- lazy midLayout), asset_decode.lua:24-128 (pair, sprite),
-- scripting/interaction_scripts.lua:50-55 (behaviors), ow_sprites.lua:43-48,
-- encounters.lua:11, 86-126, pokemon.lua:293-301, constants.lua:39-62.

local FakeExport3 = {}

local B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
local function base64(s)
  local out = {}
  for i = 1, #s, 3 do
    local a, b, c = s:byte(i, i + 2)
    local n = a * 65536 + (b or 0) * 256 + (c or 0)
    local c1, c2 = math.floor(n / 262144) % 64, math.floor(n / 4096) % 64
    local c3, c4 = math.floor(n / 64) % 64, n % 64
    out[#out + 1] = B64:sub(c1 + 1, c1 + 1) .. B64:sub(c2 + 1, c2 + 1)
      .. (b and B64:sub(c3 + 1, c3 + 1) or "=") .. (c and B64:sub(c4 + 1, c4 + 1) or "=")
  end
  return table.concat(out)
end

local function be32(n)
  return string.char(math.floor(n / 16777216) % 256, math.floor(n / 65536) % 256, math.floor(n / 256) % 256, n % 256)
end

function FakeExport3.attach(E)
  E.encodes = 0

  -- ---- love.image and love.data (LÖVE 11: newImageData(w, h, format, bytes), ImageData:getString,
  -- :encode("png") -> FileData; data.hash, data.encode)
  local function imageData(w, h, fmt, bytes)
    bytes = bytes or string.rep("\0", w * h * 4)
    assert(#bytes == w * h * 4, "fake newImageData: " .. #bytes .. " bytes for " .. w .. "x" .. h)
    local d = { w = w, h = h, bytes = bytes, format = fmt or "rgba8" }
    function d:getString() return self.bytes end
    function d:getWidth() return self.w end
    function d:getHeight() return self.h end
    function d:getDimensions() return self.w, self.h end
    function d:encode(format, filename)
      assert(format == "png" and filename == nil, "fake encode: png into memory only")
      E.encodes = E.encodes + 1
      local png = "\137PNG\r\n\26\n" .. "\0\0\0\13IHDR" .. be32(self.w) .. be32(self.h) .. "\8\6\0\0\0RAW" .. self.bytes
      return { getString = function() return png end, release = function() end }
    end
    function d:release() end
    return d
  end
  E.loveImage = { newImageData = imageData }
  E.loveData = {
    hash = function(fn, s)
      assert(fn == "md5", "fake hash: md5 only")
      return "md5:" .. s -- the bytes themselves: as good as a digest for telling quarters apart
    end,
    encode = function(container, format, s)
      assert(container == "string" and format == "base64", "fake data.encode")
      return base64(s)
    end,
  }

  -- ---- the cache and roots (dataset.lua:99, extract_island1.lua:21-27)
  E.cacheFiles = {}
  local cache = { reads = 0 }
  function cache:read(rel)
    self.reads = self.reads + 1
    return E.cacheFiles[rel]
  end
  E.Dataset = { cache = function() return cache end }
  E.Extract = { CACHE_ROOT = "data/generated/gba", NATIVE_ROOT = "data/generated/gba/native" }

  -- ---- tileset pairs: the metatile atlases per plane (asset_decode.lua:24-87)
  E.pairArt = {}
  local PLANES = { { "image", "imageData", "under" }, { "midImage", "midImageData", "middle" },
                   { "overImage", "overImageData", "over" } }
  E.pairDecodes = 0
  local function decodePair(_cache, root, pair)
    local art = E.pairArt[pair]
    if not art then return nil, "missing native blobs for " .. tostring(pair) end
    E.pairDecodes = E.pairDecodes + 1
    local cols = art.cols or 4
    local rows = math.ceil(#art.mids / cols)
    local out = { pair = pair, cols = cols, rows = rows, midCount = #art.mids, midToSlot = {}, layers = {} }
    for i, mid in ipairs(art.mids) do out.midToSlot[mid] = i - 1 end
    for _, p in ipairs(PLANES) do
      local plane = p[3]
      if plane == "under" or art.layered ~= false then
        local w, h = cols * 16, rows * 16
        local px = {}
        for y = 0, h - 1 do
          for x = 0, w - 1 do
            local slot = math.floor(y / 16) * cols + math.floor(x / 16)
            local mid = art.mids[slot + 1]
            local r, g, b, a = 0, 0, 0, 0
            if mid then r, g, b, a = art.pixel(plane, mid, x % 16, y % 16) end
            px[#px + 1] = string.char(r, g, b, a)
          end
        end
        out.layers[#out.layers + 1] = { imageKey = p[1], dataKey = p[2], data = imageData(w, h, "rgba8", table.concat(px)) }
      end
    end
    return out
  end

  -- ---- object-event sheets (ow_extract.lua:619-667 manifest; asset_decode.lua:88-104)
  E.owManifest = { count = 2, total = 2, sprites = {
    [1] = { width = 16, height = 32, frameCount = 9 },
    [4] = { width = 32, height = 32, frameCount = 3 },
  } }
  E.OwSprites = { _manifest = E.owManifest }
  local function decodeSprite(_cache, _root, gid)
    local m = E.owManifest.sprites[tonumber(gid)]
    if not m then return nil, "missing OW sheet " .. tostring(gid) end
    local w, h, n = m.width, m.height, m.frameCount
    local bytes = string.rep(string.char(gid % 256, 0, 0, 255), w * h * n)
    return { width = w, height = h, frameCount = n, imageData = imageData(w, h * n, "rgba8", bytes) }
  end
  E.AssetDecode = { pair = decodePair, sprite = decodeSprite }

  -- ---- behaviours (interaction_scripts.lua:50-55: behaviors[pair][mid])
  E.InteractionScripts = { behaviors = {} }

  -- ---- encounters (encounters.lua:11: mapId -> { land, water, rocks, fishing }, slots { species, minLevel,
  -- maxLevel }; ensureLoaded :116-125)
  E.Encounters = { _tables = {}, _h = { LAND_WEIGHTS = { 20, 20, 10, 10, 10, 10, 5, 5, 4, 4, 1, 1 } } }
  function E.Encounters.ensureLoaded() E.encounterLoads = (E.encounterLoads or 0) + 1 return true end
  -- the bound rules (encounters.lua:383-393) and their classify (encounter_rules/rse.lua:338-352): "land" for the
  -- behaviours in E.landBehaviors
  E.landBehaviors = {}
  E.EncounterRules = { classify = function(beh) return E.landBehaviors[tonumber(beh) or -1] and "land" or nil end }
  function E.Encounters.rules() return E.EncounterRules end

  -- ---- object movement (objects.lua:131-139 canonMovementType; scripting/gfx_ids.lua:136-151 hostMovement):
  -- made-up numbers, 1 LOOK, 2 WALK, 3 BACK_FORTH, 8 STAY
  E.MOVEMENTS = { [1] = "LOOK", [2] = "WALK", [3] = "BACK_FORTH", [8] = "STAY" }
  E.Objects.canonMovementType = function(mt) return tonumber(mt) end
  E.GfxIds = { hostMovement = function(mt)
    return { movement = E.MOVEMENTS[tonumber(mt) or -1] or "STAY", face = "down" }
  end }

  -- ---- national dex (pokemon.lua:293-301): made up, species id + 250
  E.Pokemon = {}
  function E.Pokemon.national(species)
    species = tonumber(species)
    if not species or species < 1 or species > 136 then return nil end
    return species + 250
  end
  function E.Pokemon.speciesFromName(name) return name == "SPECIES_TEST_NAMED" and 3 or nil end

  -- ---- the map catalogue (map_catalog.lua:147: group, num -> map id)
  E.MapCatalog = { byGroup = {} }
  function E.MapCatalog.mapIdFor(group, num) return E.MapCatalog.byGroup[group .. ":" .. num] end

  -- ---- constants: names for species, moves, items (constants.lua:39-62; raw(kind).byName)
  E.constTables.species = { SPECIES_ = { [1] = "SPECIES_TEST_ONE", [2] = "SPECIES_TEST_TWO", [3] = "SPECIES_TEST_NAMED",
                                         [400] = "SPECIES_TEST_EGG" } }
  E.constTables.moves = { MOVE_ = { [0] = "MOVE_NONE", [1] = "MOVE_TEST_TACKLE", [2] = "MOVE_TEST_SPLASH" } }

  local mods = E.modules
  mods["src.core.game3.dataset"] = E.Dataset
  mods["src.import.gba.extract_island1"] = E.Extract
  mods["src.core.game3.asset_decode"] = E.AssetDecode
  mods["src.core.game3.scripting.interaction_scripts"] = E.InteractionScripts
  mods["src.core.game3.ow_sprites"] = E.OwSprites
  mods["src.core.game3.encounters"] = E.Encounters
  mods["src.core.game3.scripting.gfx_ids"] = E.GfxIds
  mods["src.core.game3.pokemon"] = E.Pokemon
  mods["src.import.gba.map_catalog"] = E.MapCatalog

  -- ---- a world: E.exportWorld() puts it into game.data.maps (lazy midLayout, dataset.lua:357-372)
  local function layout(w, h, stride, mid, border)
    local cells = {}
    for cy = 0, h - 1 do
      for cx = 0, stride - 1 do
        cells[cy * stride + cx + 1] = { mid = mid(cx, cy), coll = (cx + cy) % 2 == 0 and 0 or 1, elev = 3 }
      end
    end
    return { width = stride, height = h, trueWidth = w, trueHeight = h, cells = cells, overrides = {},
             borderWidth = 2, borderHeight = 1, borderMids = border or { 5, 6 } }
  end
  function E.lazyMap(def, lay)
    E.layoutLoads = E.layoutLoads or {}
    return setmetatable(def, { __index = function(t, k)
      if k ~= "midLayout" then return nil end
      E.layoutLoads[t.id] = (E.layoutLoads[t.id] or 0) + 1
      rawset(t, "midLayout", lay)
      return lay
    end })
  end

  -- The default world: EM_TEST_TOWN (6x4, stride 8, pair TEST_A) with EM_TEST_ROUTE (4x5) north of it at
  -- offset 1 and EM_TEST_SEA (3x3) east at offset -2; EM_TEST_HOUSE (pair TEST_B) an interior. Pair TEST_A:
  -- metatiles 0..7; art repeats every three metatiles (0, 3 and 6 look alike); metatile 1's top-left quarter has
  -- a middle-plane pixel, metatile 2's bottom-right an over-plane pixel. TEST_B: metatiles 512, 513, one plane.
  function E.exportWorld()
    E.pairArt.TEST_A = { mids = { 0, 1, 2, 3, 4, 5, 6, 7 }, cols = 4, pixel = function(plane, mid, x, y)
      local q = (y >= 8 and 2 or 0) + (x >= 8 and 1 or 0)
      if plane == "under" then return 10 + mid % 3, q, (x + y) % 2, 255 end
      if plane == "middle" and mid == 1 and q == 0 and x == 1 and y == 1 then return 200, 1, 1, 255 end
      if plane == "over" and mid == 2 and q == 3 and x == 9 and y == 9 then return 1, 200, 1, 255 end
      return 0, 0, 0, 0
    end }
    E.pairArt.TEST_B = { mids = { 512, 513 }, cols = 2, layered = false, pixel = function(_, mid, x, y)
      return 50, mid % 256, x, 255
    end }
    E.InteractionScripts.behaviors.TEST_A = { [0] = 0, [1] = 0x40, [2] = 0x40, [3] = 0x20, [4] = 0x20, [5] = 0, [6] = 0,
                                              [7] = 0x10 }
    E.InteractionScripts.behaviors.TEST_B = { [512] = 0, [513] = 0x10 }
    E.MB_IDS.MB_TEST_GRASS, E.MB_IDS.MB_TEST_WATER, E.MB_IDS.MB_TEST_WALL = 0x40, 0x20, 0x10
    E.landBehaviors[0x40] = true
    local mb = E.MB_IDS
    local byNum = {}
    for name, id in pairs(mb) do byNum[id] = name end
    E.MB.nameOf = function(id) return byNum[tonumber(id) or -1] end
    local maps = {}
    maps.EM_TEST_TOWN = E.lazyMap({ id = "EM_TEST_TOWN", pair = "TEST_A", kind = "town", mapType = 1, music = 300,
      weather = 2,
      -- the import's list form (connections.lua: { dir, map, offset }, the engine's up/right names)
      connections = { { dir = "up", map = "EM_TEST_ROUTE", offset = 1 }, { dir = "right", map = "EM_TEST_SEA", offset = -2 },
                      { dir = "dive", map = "EM_TEST_SEA", offset = 0 } },
      warps = { { x = 2, y = 1, destMap = "EM_TEST_HOUSE", destWarp = 1 }, { x = 4, y = 1, destMap = nil, destWarp = 3,
                mapGroup = 0, mapNum = 127 } } },
      layout(6, 4, 8, function(cx, cy) return (cx + cy * 6) % 8 end))
    maps.EM_TEST_ROUTE = E.lazyMap({ id = "EM_TEST_ROUTE", pair = "TEST_A", kind = "route", mapType = 3, music = 301,
      weather = 0, connections = { south = { map = "EM_TEST_TOWN", offset = -1 } }, warps = {} },
      layout(4, 5, 4, function() return 7 end))
    maps.EM_TEST_SEA = E.lazyMap({ id = "EM_TEST_SEA", pair = "TEST_A", kind = "route", mapType = 6, music = 302,
      weather = 0, connections = { west = { map = "EM_TEST_TOWN", offset = 2 } }, warps = {} },
      layout(3, 3, 3, function() return 3 end))
    maps.EM_TEST_HOUSE = E.lazyMap({ id = "EM_TEST_HOUSE", pair = "TEST_B", kind = "indoor", mapType = 8, music = 303,
      weather = 0, connections = {}, warps = { { x = 1, y = 2, destMap = "EM_TEST_TOWN", destWarp = 1 } } },
      layout(2, 3, 2, function(cx) return 512 + cx end, { 512, 513 }))
    E.game.data = { maps = maps }
    E.Space.bundle.events.EM_TEST_TOWN = { objects = {
      { localId = 1, graphicsId = 1, x = 3, y = 2, elevation = 3, flag = 0x101, movementType = 1 },  -- looks about
      { localId = 2, graphicsId = 3, x = 5, y = 1, elevation = 3, flag = 0x102, movementType = 8 },  -- an item ball
      { localId = 3, graphicsId = 4, x = 0, y = 3, elevation = 3, trainerType = 1, movementType = 2 },  -- a walking trainer
      { localId = 127, graphicsId = 1, x = 1, y = 1 },                                  -- the camera: left out
    } }
    E.Encounters._tables = {
      EM_TEST_ROUTE = {
        land = { rate = 20, mons = { { species = 1, minLevel = 2, maxLevel = 3 }, { species = 2, minLevel = 4, maxLevel = 4 },
                                     { species = 999, minLevel = 2, maxLevel = 3 } } },
        fishing = { rate = 30, mons = { { species = "SPECIES_TEST_NAMED", minLevel = 5, maxLevel = 10 } } },
      },
      ["0:3"] = { mapGroup = 0, mapNum = 3, water = { rate = 4, slots = { { species = 2, minLevel = 20, maxLevel = 30 } } } },
    }
    E.MapCatalog.byGroup["0:3"] = "EM_TEST_SEA"
    return maps
  end
end

return FakeExport3
