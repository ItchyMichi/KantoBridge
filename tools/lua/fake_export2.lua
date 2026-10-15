-- The parts of Gen1Recomp v0.3.85's Gen 2 engine that the gen2 adapter's
-- export reads (lib/engine/gen2/export.lua; GEN2_BRIDGE_PLAN.md §6, phase
-- G2D), on top of fake_engine2.lua: tilesets (blocks, collision, PalMap,
-- Crystal's tile attributes, an animation program), palettes (BG pools per
-- environment and daytime, roof colours per group, OBJ sets), roof sheets,
-- sprites, landmarks and spawns, encounter tables, constant orders, the
-- engine's pure rules the export asks (Palettes, TileAttrs, Npc.bigFacing,
-- Permissions, World's region / animLayers / animRow,
-- Encounter's slot chances), greyscale sheets behind love.image, and
-- love.data. Everything is synthetic: made-up maps (TEST_*), blocks, tiles,
-- colours and species.
--
--   FakeExport2.attach(E)   adds the modules, E.loveImage / E.loveData and E.exportWorld()
--   E.images[path]          a sheet: { w, h, shade(x, y) -> 0..3, alpha(x, y) -> 0..255 }
--   E.encodes               PNGs encoded; a fake PNG carries its size in its IHDR and its RGBA bytes after "RAW"
--
-- Engine files (refs/gen1recomp): src/world/gen2/Palettes.lua:84-94, :150-249; TileAttrs.lua:15-53;
-- Npc.lua:73-78; Permissions.lua; CollPermissions.lua; World.lua:6411-6438, :9015-9061,
-- :9754-9800; src/battle/gen2/Encounter.lua:15-17; src/render/Assets.lua:38-50.

local FakeExport2 = {}

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

local SHADE_RED = { [0] = 255, 170, 85, 0 }

function FakeExport2.attach(E)
  E.encodes = 0
  E.images = {}

  -- ---- love.image and love.data (LÖVE 11)
  local function imageData(w, h, fmt, bytes)
    local d
    if type(w) == "string" then
      -- newImageData(path): a sheet by its resolved path
      local img = E.images[w]
      assert(img, "fake newImageData: no image " .. w)
      local px = {}
      for y = 0, img.h - 1 do
        for x = 0, img.w - 1 do
          local s = img.shade(x, y)
          local r = SHADE_RED[s]
          px[#px + 1] = string.char(r, r, r, img.alpha and img.alpha(x, y) or 255)
        end
      end
      d = { w = img.w, h = img.h, bytes = table.concat(px), format = "rgba8" }
    else
      bytes = bytes or string.rep("\0", w * h * 4)
      assert(#bytes == w * h * 4, "fake newImageData: " .. #bytes .. " bytes for " .. w .. "x" .. h)
      d = { w = w, h = h, bytes = bytes, format = fmt or "rgba8" }
    end
    function d:getString() return self.bytes end
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
      return "md5:" .. s
    end,
    encode = function(container, format, s)
      assert(container == "string" and format == "base64", "fake data.encode")
      return base64(s)
    end,
  }
  E.Assets = { resolve = function(path) return path end }

  -- ---- Palettes (Palettes.lua:84-94 daytimeFor; :174-212 bgSet; :215-249 objectSet, objectPaletteId, spritePalette)
  local FORCED = { PALETTE_DAY = "DAY", PALETTE_NITE = "NITE", PALETTE_MORN = "MORN", PALETTE_DARK = "DARK" }
  local ROOF_ENVS = { TOWN = true, ROUTE = true }
  local OW = { PAL_OW_RED = 1, PAL_OW_BLUE = 2, PAL_OW_GREEN = 3, PAL_OW_BROWN = 4 }
  E.Palettes = {}
  function E.Palettes.clockDaytime(hour)
    hour = math.floor(hour or 12) % 24
    if hour < 4 then return "NITE" end
    if hour < 10 then return "MORN" end
    if hour < 18 then return "DAY" end
    return "NITE"
  end
  function E.Palettes.daytimeFor(mapDef, hour, flashUsed)
    local clock = E.Palettes.clockDaytime(hour)
    local forced = mapDef and FORCED[mapDef.palette]
    if forced == "DARK" then return flashUsed and "NITE" or "DARK" end
    return forced or clock
  end
  function E.Palettes.bgSet(data, mapDef, daytime)
    if not (data and data.bg and data.environments) then return nil end
    local env = mapDef and mapDef.environment
    local row = data.environments[env] or data.environments.TOWN
    local indices = row[daytime] or row.DAY
    local set = {}
    for slot = 1, 8 do
      local pool = data.bg[indices[slot]]
      local colors = {}
      for i = 1, 4 do
        local c = pool and pool[i] or { 0, 0, 0 }
        colors[i] = { c[1], c[2], c[3] }
      end
      set[slot] = colors
    end
    local roof = ROOF_ENVS[env] and data.roofs and data.roofs[mapDef.group]
    if roof then
      local pair = (daytime == "MORN" or daytime == "DAY") and roof.mornDay or roof.nite
      set[7][2] = { pair[1][1], pair[1][2], pair[1][3] }
      set[7][3] = { pair[2][1], pair[2][2], pair[2][3] }
    end
    return set
  end
  function E.Palettes.objectSet(data, daytime)
    return data and data.objects and (data.objects[daytime] or data.objects.DAY) or nil
  end
  function E.Palettes.objectPaletteId(objDef)
    local p = objDef and objDef.palette
    if not p or p == 0 then return nil end
    return p % 8
  end
  function E.Palettes.spritePalette(data, daytime, spriteDef, objDef)
    local set = E.Palettes.objectSet(data, daytime)
    if not set then return nil end
    local id = E.Palettes.objectPaletteId(objDef)
    if not id then
      id = spriteDef and spriteDef.paletteId
      if not id then id = (OW[spriteDef and spriteDef.palette] or 1) - 1 end
    end
    return set[(id or 0) + 1] or set[1]
  end

  -- ---- TileAttrs (TileAttrs.lua:15-53)
  E.TileAttrs = {}
  function E.TileAttrs.forTile(tileset, tileId)
    local attrs = tileset and tileset.tileAttrs
    if attrs then
      local a = attrs[tileId + 1]
      if a then return a end
      if tileId < 0x80 then
        a = attrs[0x80 + tileId + 1]
        if a and a.vramBank == 1 then return a end
      end
    end
    local bankFromId = (tileId >= 0x80 and tileId < 0xe0) and 1 or 0
    local slot = tileset and tileset.tilePalettes and tileset.tilePalettes[tileId + 1] or 1
    return { palette = slot, vramBank = bankFromId }
  end
  function E.TileAttrs.sheetTileId(tileId, attr)
    if tileId >= 0x80 then return tileId end
    if attr and attr.vramBank == 1 then return 0x80 + tileId end
    return tileId
  end

  -- ---- Npc.bigFacing (Npc.lua:73-78)
  E.Npc = {}
  function E.Npc.bigFacing(movement, spriteId)
    if movement == 0x15 then return "sym" end
    if movement == 0x20 then return "asym" end
    if movement ~= 0x21 then return nil end
    return spriteId == "SPRITE_BIG_SNORLAX" and "sym" or "asym"
  end

  -- ---- Encounter (Encounter.lua:15-17)
  E.Encounter = { GRASS_SLOT_CHANCES = { 30, 60, 80, 90, 95, 99, 100 }, WATER_SLOT_CHANCES = { 60, 90, 100 } }

  -- ---- Permissions (Permissions.lua; CollPermissions.lua: a made-up permission rule, wall unless listed)
  local P = E.Permissions
  local LAND = { [0x00] = true, [0x18] = true, [0x14] = true, [0x23] = true, [0x71] = true, [0x70] = true,
                 [0xa3] = true, [0xb2] = true, [0x7a] = true }
  local WATER = { [0x29] = true, [0x24] = true, [0x33] = true, [0x3b] = true }
  function P.of(coll)
    if LAND[coll] then return 0 end
    if WATER[coll] then return 1 end
    return 15
  end
  -- G2H (CollPermissions.lua:37-47): water and the cells a step may land on, by the same rule
  function P.isWater(c) return P.of(c) == 1 end
  function P.isWalkable(c) return P.of(c) == 0 end
  function P.isGrass(c) return c == 0x18 or c == 0x14 end
  function P.isSuperTallGrass(c) return c == 0x14 end
  function P.isEncounterCollision(c) return c == 0x18 or c == 0x14 or c == 0x29 end
  function P.isWhirlpool(c) return c == 0x24 end
  function P.isCutTree(c) return c == 0x12 end
  function P.isCuttable(c) return c == 0x12 or c == 0x18 or c == 0x14 end
  function P.isHeadbuttTree(c) return c == 0x15 end
  function P.isWaterfall(c) return c == 0x33 or c == 0x3b end
  function P.isCounter(c) return c == 0x90 end
  function P.ledgeFacings(c) return c == 0xa3 and { down = true } or nil end
  function P.sideBlocks(c) return c == 0xb2 and { up = true } or nil end
  function P.neighborBlocks(nd, c)
    if c ~= 0xb2 or nd ~= "down" then return nil end
    return "down"
  end
  function P.isWarpCollision(c) return math.floor(c / 16) == 7 or c == 0x60 end
  function P.carpetDirection(c) return c == 0x70 and "down" or nil end
  function P.warpFacesDown(c) return c == 0x71 or c == 0x7a end
  local cur = P.currentDirection
  function P.currentDirection(c)
    if c == 0x33 or c == 0x3b then return "down" end
    return cur and cur(c) or nil
  end

  -- ---- the World's own rules (World.lua:6411-6438 region; :9015-9061 animLayers;
  -- :9754-9800 waterFrameFor, flowerFrameFor, animRow)
  local World = E.World
  function World:landmarkIndex(id, fallback)
    local records = self.landmarks and self.landmarks.landmarks
    local record = records and (records["LANDMARK_" .. id] or records[id])
    return record and tonumber(record.index) or fallback
  end
  function World:region()
    local landmarks = self.landmarks and self.landmarks.landmarks
    local id = self.map and self.map.def and self.map.def.landmark
    local entry = type(id) == "string" and landmarks and landmarks[id] or nil
    local index = (entry and entry.index) or 0
    return index >= self:landmarkIndex("PALLET_TOWN", 0x2e) and "kanto" or "johto"
  end
  function World:animLayers(tileset)
    local anim = tileset and tileset.anim
    if not (anim and anim.frames) then return nil end
    local defs = self.tilesets
    local wanted
    for _, f in ipairs(anim.frames) do
      local layer, tile
      if f.func == "AnimateWaterTile" then
        tile, layer = f.tile, { kind = "water", sheet = defs and defs.waterFrames, frames = 4 }
      elseif f.func == "AnimateFlowerTile" then
        tile, layer = 0x03, { kind = "flower", sheet = defs and defs.flowerFrames, frames = 4 }
      elseif f.func == "WriteTileFromAnimBuffer" and f.scroll then
        tile, layer = f.tile, { kind = "scroll", scroll = f.scroll, frames = 8 }
      end
      if tile and layer and (layer.kind == "scroll" or layer.sheet) then
        wanted = wanted or {}
        wanted[tile] = layer
      end
    end
    return wanted
  end
  function World.waterFrameFor(timer) return math.floor(((timer or 0) % 8) / 2) + 1 end
  function World:flowerFrameFor(timer)
    local rows = self.tilesets and self.tilesets.flowerCgbFrames
    local first, second = 2, 4
    if rows and rows[1] and rows[2] then first, second = rows[1], rows[2] end
    return ((timer or 0) % 4 < 2) and first or second
  end
  function World:animRow(layer)
    local timer = (self.animTimer or 0) % 8
    if layer.kind == "water" then return World.waterFrameFor(timer) end
    if layer.kind == "flower" then return self:flowerFrameFor(timer) end
    return timer + 1
  end

  local mods = E.modules
  mods["src.world.gen2.Palettes"] = E.Palettes
  mods["src.world.gen2.TileAttrs"] = E.TileAttrs
  mods["src.world.gen2.Npc"] = E.Npc
  mods["src.render.Assets"] = E.Assets
  mods["src.battle.gen2.Encounter"] = E.Encounter

  -- ---- the made-up data (Game2's tables, Game2.lua:1112-1145)
  local function grey(w, h, shade, alpha)
    return { w = w, h = h, shade = shade, alpha = alpha }
  end
  -- a tile's shades: tile t, pixel (x, y) -> (t + x + y) % 4; row y of tile 0 all shade 0 so a pattern differs per tile
  E.images["tilesets/test.png"] = grey(128, 16, function(x, y)
    local t = math.floor(y / 8) * 16 + math.floor(x / 8)
    return (t + x % 8 + y % 8) % 4
  end)
  E.images["tilesets/johto.png"] = grey(128, 16, function(x, y) return (math.floor(x / 8) + y) % 4 end)
  -- the roof row: 9 tiles of shade 3
  E.images["tilesets/roofs/test_roof.png"] = grey(72, 8, function() return 3 end)
  -- water frames: four 8x8 rows, each a flat shade
  E.images["tilesets/water_frames.png"] = grey(8, 32, function(_, y) return math.floor(y / 8) % 4 end)
  -- sprites: a 16x32 walker (2 frames) whose shade 0 is cut out; a 16x16 still sprite
  E.images["sprites/test_lass.png"] = grey(16, 32, function(x, y) return (x + y) % 4 end, function(x, y)
    return (x + y) % 4 == 0 and 0 or 255
  end)
  E.images["sprites/test_ball.png"] = grey(16, 16, function() return 2 end)

  local function blocks16(first)
    local b = {}
    for i = 1, 16 do b[i] = (first + i - 1) % 32 end
    return b
  end
  E.data.gen2Tilesets = {
    TILESET_TEST = {
      image = "tilesets/test.png", tilesPerRow = 16,
      blocks = { blocks16(0), blocks16(4), blocks16(8), blocks16(0) },
      collision = { { 0, 0, 0, 0 }, { 0x07, 0x07, 0x18, 0x18 }, { 0x29, 0x29, 0x71, 0x70 }, { 0x12, 0xa3, 0xb2, 0x24 } },
      tilePalettes = { 1, 2, 3, 4, 5, 6, 7, 8 },
      anim = { period = 3, frames = { { func = "AnimateWaterTile", tile = 2 },
                                      { func = "WriteTileFromAnimBuffer", tile = 9, scroll = { h = 1 } } } },
    },
    TILESET_JOHTO = {
      image = "tilesets/johto.png", tilesPerRow = 16,
      blocks = { { 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12, 0, 1, 2, 3, 4, 5, 6 } },
      collision = { { 0, 0, 0, 0 } },
    },
    waterFrames = "tilesets/water_frames.png",
  }
  -- BG pools: pool n's colour i is (n*10 + i, i * 20, 7); environments pick pools 1..8 at DAY, 9..16 at NITE
  local bg = {}
  for n = 1, 16 do
    bg[n] = {}
    for i = 1, 4 do bg[n][i] = { n * 10 + i, i * 20, 7 } end
  end
  local function row(first) return { first, first + 1, first + 2, first + 3, first + 4, first + 5, first + 6, first + 7 } end
  E.data.gen2Palettes = {
    bg = bg,
    environments = { TOWN = { DAY = row(1), NITE = row(9), MORN = row(1) }, INDOOR = { DAY = row(9), NITE = row(9) },
                     CAVE = { DAY = row(1), NITE = row(9) } },
    roofs = { [3] = { mornDay = { { 200, 0, 0 }, { 0, 200, 0 } }, nite = { { 100, 0, 0 }, { 0, 100, 0 } } } },
    objects = { DAY = { { { 255, 255, 255 }, { 1, 1, 1 }, { 2, 2, 2 }, { 3, 3, 3 } },
                        { { 255, 255, 255 }, { 9, 9, 9 }, { 8, 8, 8 }, { 7, 7, 7 } } } },
  }
  E.data.gen2Roofs = { mapGroupRoofs = { [3] = "ROOF_TEST" }, roofs = { ROOF_TEST = { image = "tilesets/roofs/test_roof.png" } } }
  E.data.gen2Sprites = {
    SPRITE_TEST_LASS = { id = "SPRITE_TEST_LASS", image = "sprites/test_lass.png", frames = 2, walker = true,
                         spriteType = "WALKING_SPRITE", palette = "PAL_OW_RED", paletteId = 0 },
    SPRITE_TEST_BALL = { id = "SPRITE_TEST_BALL", image = "sprites/test_ball.png", frames = 1, walker = false,
                         spriteType = "STILL_SPRITE", palette = "PAL_OW_BLUE", paletteId = 1 },
  }
  E.data.gen2Landmarks = {
    landmarks = { LANDMARK_TEST_JOHTO = { index = 3 }, LANDMARK_PALLET_TOWN = { index = 0x2e },
                  LANDMARK_TEST_KANTO = { index = 0x30 } },
    spawns = { SPAWN_HOME = { map = "TEST_HOUSE_2F", x = 3, y = 3 } },
  }
  E.data.gen2Constants = {
    speciesOrder = { "TEST_ONE", "TEST_TWO", "TEST_THREE" },
    moveOrder = { "TEST_TACKLE", "TEST_SPLASH" },
    itemOrder = { "TEST_POTION", "TEST_BALL" },
  }
  E.data.gen2Encounters = {
    grass = { TEST_TOWN = { rates = { MORN = 10, DAY = 20, NITE = 30 }, slots = {
      MORN = { { level = 2, species = "TEST_ONE" } }, DAY = { { level = 3, species = "TEST_TWO" } },
      NITE = { { level = 4, species = "TEST_THREE" }, { level = 4, species = "NO_SUCH_MON" } } } } },
    water = { TEST_TOWN = { rate = 5, slots = { { level = 10, species = "TEST_TWO" } } } },
    fishGroups = { FISHGROUP_TEST = { chance = 128, old = { { chance = 255, species = "TEST_ONE", level = 5 } },
                                      good = {}, super = { { chance = 255, species = "TEST_TWO", level = 20,
                                                             nite = { species = "TEST_THREE", level = 21 } } } } },
    trees = { TEST_TOWN = "TREEMON_SET_TEST" },
    treeSets = { TREEMON_SET_TEST = { common = { { chance = 50, species = "TEST_ONE", level = 7 } }, rare = {} } },
    bugContest = { { chance = 20, species = "TEST_TWO", min = 7, max = 18 } },
  }

  -- E.exportWorld(): the default world on top of the fake's maps. TEST_TOWN (10x10, TILESET_TEST, TOWN, group 3,
  -- Johto) with TEST_ROUTE (4x6) north at offset 1 and NEW_BARK_TOWN (4x4, the Johto root) west at offset 2;
  -- TEST_HOUSE_2F and TEST_HOUSE interiors; TEST_CAVE dark;
  -- PALLET_TOWN (Kanto, 5x5, TILESET_JOHTO with group 3's roof). Blocks: TEST_TOWN's (0,0) is block 3 (a Cut
  -- tree's); objects: a lass, a ball (palette 8: PAL_NPC_RED's override, slot 0), a walker, a big doll; warps: a door to TEST_HOUSE (dest
  -- 2, so 0-based 1) and a remembered one (0xff).
  function E.exportWorld()
    local town = E.maps.TEST_TOWN
    town.group, town.map, town.landmark = 3, 1, "LANDMARK_TEST_JOHTO"
    town.fishGroup = "FISHGROUP_TEST"
    town.blocks[1] = 3
    town.blocks[2] = 2
    town.blocks[5] = 0x40 -- a Cut tree's block (FieldMoves.CUT_BLOCKS, fake_engine2.lua)
    town.connections = { north = { mapId = "TEST_ROUTE", offset = 1 }, west = { mapId = "NEW_BARK_TOWN", offset = 2 } }
    town.warps = { { x = 4, y = 4, destMap = "TEST_HOUSE", destWarp = 2 }, { x = 6, y = 4, destMap = "NONE", destWarp = 0xff } }
    town.coordEvents = { { x = 2, y = 3, sceneId = 1 } }
    town.bgEvents = { { x = 5, y = 1, kind = 0 }, { x = 7, y = 1, kind = 7 } }
    town.objects = {
      { index = 1, sprite = "SPRITE_TEST_LASS", spriteId = 1, x = 3, y = 4, movement = 0x06, hours = { -1, -1 }, type = 0 },
      { index = 2, sprite = "SPRITE_TEST_BALL", spriteId = 2, x = 6, y = 2, movement = 0x01, palette = 8, type = 1,
        eventFlag = 40, hours = { 18, 3 } },
      { index = 3, sprite = "SPRITE_TEST_LASS", spriteId = 1, x = 7, y = 7, movement = 0x02, radius = { x = 1, y = 0 },
        type = 2 },
      { index = 4, sprite = 0xf3, spriteId = 0xf3, x = 1, y = 7, movement = 0x21, type = 0 },
    }
    E.newMap("TEST_ROUTE", 4, 6)
    E.maps.TEST_ROUTE.group, E.maps.TEST_ROUTE.landmark = 3, "LANDMARK_TEST_JOHTO"
    E.maps.TEST_ROUTE.connections = { south = { mapId = "TEST_TOWN", offset = -1 } }
    E.maps.TEST_CAVE.palette = "PALETTE_DARK"
    E.newMap("NEW_BARK_TOWN", 4, 4)
    E.maps.NEW_BARK_TOWN.group, E.maps.NEW_BARK_TOWN.landmark = 3, "LANDMARK_TEST_JOHTO"
    E.maps.NEW_BARK_TOWN.connections = { east = { mapId = "TEST_TOWN", offset = -2 } }
    E.newMap("PALLET_TOWN", 5, 5)
    local pallet = E.maps.PALLET_TOWN
    pallet.tileset, pallet.group, pallet.landmark = "TILESET_JOHTO", 3, "LANDMARK_PALLET_TOWN"
    for i = 1, 25 do pallet.blocks[i] = 0 end
    for _, id in ipairs({ "TEST_HOUSE", "TEST_HOUSE_2F", "TEST_CENTER", "TEST_CAVE" }) do
      E.maps[id].group, E.maps[id].landmark = 1, "LANDMARK_TEST_JOHTO"
    end
  end
end

return FakeExport2
