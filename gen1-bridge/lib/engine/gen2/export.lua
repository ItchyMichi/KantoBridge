-- gen2 export (GEN2_BRIDGE_PLAN.md §6, phase G2D): Crystal's world for the
-- builder, read from the running engine's data and handed to the core's export
-- service as the g2* resources (protocol/export/g2*.schema.json). The core
-- caches, hashes and serves them (export/kb_export.lua); this part reads and
-- shapes, and bakes the art on the CPU.
--
--   g2map/<G2_MAP>     a map in blocks (the map data copied at boot, story.lua),
--                      its border block, the art variant it is drawn with, its
--                      environment, palette, group, area, connections, warps,
--                      coord and bg events, objects
--   g2tiles/<variant>  an art variant (a tileset drawn with one palette set and
--                      roof): per block its 16 baked tile ids and 4 COLL bytes,
--                      and what the engine's predicates say each byte means
--   g2image/g2q.<n>    the baked tiles, 1,024 to a 256x256 RGBA page
--   g2anims/all        the animated tiles: frames and loop
--   g2sprite/<SPRITE>[.p<n>]  the object sheets per (sprite, OBJ palette)
--   g2layout/<area>    the surface maps' block origins, one per surface area
--   g2encounters/all, g2names/all, swaps/all
--
-- Art (research note g2_export_data_model.md §5, §10): Gen 2's sheets are
-- four-shade greyscale (shade from the red channel, as the engine's shader
-- recovers it, src/render/GbcPalette.lua:47-66); a tile draws with one of the
-- map's eight BG palettes, chosen by the tileset's PalMap (Crystal: the tile's
-- attributes, src/world/gen2/TileAttrs.lua), and the eight come from the
-- engine's own Palettes.bgSet (environment, daytime, roof colours of the map's
-- group, Crystal's special sets; src/world/gen2/Palettes.lua:174-212). The
-- roof tilesets take tiles $0a-$12 from the group's roof sheet (World.lua:487-509,
-- :8845-8895). This is World:bakeMapImage's colour (:8911-9020) on the CPU, in
-- the cart's own colours (the COLOR option is the player's display). Baked
-- tiles are shared by their pixels (MD5) and paged as Gen 3's quarters.
-- Daytime (J6): a map that follows the clock is baked at DAY, a pinned map at
-- its own daytime, a dark map as it looks after Flash (NITE) and flagged dark.
--
-- Animated tiles (World:animLayers, :9015-9061; World:animRow, :9790-9800): a
-- tileset's program ticks a 0-7 timer once every `period` frames, and each
-- animated tile shows a row of its frame strip for each timer value (water,
-- flowers, whirlpools, the tower pillar, lava) or its own tile scrolled
-- (waterfalls, currents; World:scrollStrip, :9843-9895). An animated tile is a
-- baked tile of its own whose page holds its frame at timer 0; g2anims gives
-- its frames and its loop in frames.
--
-- Everything read is the game's own data: it goes to the builder only, and the
-- logs carry codes, kinds and counts.
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Export2 = {}

Export2.PAGE_SIDE = 32                           -- baked tiles per page row and column
Export2.PAGE = Export2.PAGE_SIDE * Export2.PAGE_SIDE
-- The surface areas and their roots (Johto from the player's town; Crystal's
-- Kanto is not joined to it by any connection, PROGRESS.md G2-S8).
Export2.AREAS = { { area = "johto", root = "NEW_BARK_TOWN" }, { area = "kanto", root = "PALLET_TOWN" } }
Export2.DIRS = { north = true, south = true, west = true, east = true }
-- The roof tilesets (World.lua:8845-8848) and the sheet tiles a roof replaces (:487-509).
Export2.ROOF_TILESETS = { TILESET_JOHTO = true, TILESET_JOHTO_MODERN = true }
Export2.ROOF_FIRST, Export2.ROOF_LAST = 0x0a, 0x12
-- ScrollTileRightLeft's offsets over the 0-7 timer (World.lua:9827).
Export2.SCROLL_H = { [0] = 0, 1, 2, 3, 2, 1, 0, -1 }
-- The movements that leave the object's cell (constants/map_object_constants.asm,
-- src/world/gen2/Npc.lua:23-46): WANDER, WALK_UP_DOWN, WALK_LEFT_RIGHT, SWIM_WANDER.
Export2.WALKS = { [0x02] = "xy", [0x04] = "y", [0x05] = "x", [0x24] = "xy" }
-- The big objects, two cells by two (Npc.lua:47-53).
Export2.BIG = { [0x15] = true, [0x20] = true, [0x21] = true }
-- The bridge's warp kinds by COLL byte (move.lua's WARP_KINDS), but a ladder (0x72) apart from a staircase (0x7a):
-- the builder's 3D area makes a ladder a real shaft and keeps a staircase's fade (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md
-- D2); the warp messages still call both stairs.
Export2.WARP_KINDS = {
  [0x71] = "door", [0x7b] = "door", [0x7a] = "stairs", [0x72] = "ladder", [0x7c] = "pad",
  [0x60] = "hole", [0x68] = "hole", [0x70] = "edge", [0x76] = "edge", [0x78] = "edge", [0x7e] = "edge",
}
Export2.SIDES = { "up", "down", "left", "right" }

local floor, char, byte = math.floor, string.char, string.byte
local concat, sort = table.concat, table.sort

-- The shade (0-3) of a sheet pixel from its red channel, as the shader recovers it.
function Export2.shade(red)
  local s = floor((1 - red / 255) * 3 + 0.5)
  if s < 0 then return 0 end
  if s > 3 then return 3 end
  return s
end

-- One 8x8 tile of a decoded sheet ({ w, h, raw }: RGBA bytes) as its 64 shades
-- and 64 alphas (strings of bytes), or nil off the sheet. x0, y0 in pixels.
function Export2.pattern(sheet, x0, y0)
  if not sheet or x0 < 0 or y0 < 0 or x0 + 8 > sheet.w or y0 + 8 > sheet.h then return nil end
  local raw, w = sheet.raw, sheet.w
  local shades, alphas = {}, {}
  for r = 0, 7 do
    local at = ((y0 + r) * w + x0) * 4 + 1
    for c = 0, 7 do
      local p = at + c * 4
      shades[#shades + 1] = char(Export2.shade(byte(raw, p)))
      alphas[#alphas + 1] = char(byte(raw, p + 3))
    end
  end
  return { shades = concat(shades), alphas = concat(alphas) }
end

-- A pattern scrolled by (dx, dy) and wrapped, as World:scrollStrip draws a
-- current: the destination pixel (x, y) takes the source's ((x - dx) mod 8, (y - dy) mod 8).
function Export2.scrolled(p, dx, dy)
  local shades, alphas = {}, {}
  for y = 0, 7 do
    for x = 0, 7 do
      local i = ((y - dy) % 8) * 8 + ((x - dx) % 8) + 1
      shades[#shades + 1] = p.shades:sub(i, i)
      alphas[#alphas + 1] = p.alphas:sub(i, i)
    end
  end
  return { shades = concat(shades), alphas = concat(alphas) }
end

-- A pattern through four colours ({ {r,g,b} x4 }) as 256 RGBA bytes: a BG
-- tile is opaque; a sprite (keyed) keeps its sheet's alpha (its shade 0 is cut out).
function Export2.rgba(p, colors, keyed)
  local px = {}
  for i = 1, 64 do
    local s = byte(p.shades, i)
    local c = colors[s + 1] or colors[1] or { 0, 0, 0 }
    local a = keyed and byte(p.alphas, i) or 255
    px[i] = char(floor(c[1] or 0) % 256, floor(c[2] or 0) % 256, floor(c[3] or 0) % 256, a)
  end
  return concat(px)
end

-- What the engine's predicates say a COLL byte means (src/world/gen2/Permissions.lua,
-- src/core/CollPermissions.lua): the permission, grass and encounters, ice,
-- whirlpool, trees, waterfall and currents, counter, ledges, one-way sides (the
-- standing tile and, for each neighbour, the direction it refuses: Gold's and
-- Crystal's rules differ, Permissions.lua:239-252) and warps. Only what holds.
function Export2.meaning(P, ask, coll)
  local m = {}
  local perm = ask(P, "of", nil, coll)
  m.perm = perm == 0 and "land" or (perm == 1 and "water" or "wall")
  local flags = { grass = "isGrass", longGrass = "isSuperTallGrass", encounter = "isEncounterCollision",
                  ice = "isIce", whirlpool = "isWhirlpool", cutTree = "isCutTree", cuttable = "isCuttable",
                  headbutt = "isHeadbuttTree", waterfall = "isWaterfall", counter = "isCounter" }
  for key, fn in pairs(flags) do
    if ask(P, fn, false, coll) == true then m[key] = true end
  end
  local cur = ask(P, "currentDirection", nil, coll)
  if type(cur) == "string" then m.current = cur end
  local function dirs(set)
    if type(set) ~= "table" then return nil end
    local out = {}
    for _, d in ipairs(Export2.SIDES) do
      if set[d] then out[#out + 1] = d end
    end
    return #out > 0 and out or nil
  end
  m.ledge = dirs(ask(P, "ledgeFacings", nil, coll))
  m.sides = dirs(ask(P, "sideBlocks", nil, coll))
  local neighbor
  for _, nd in ipairs(Export2.SIDES) do
    local refused = ask(P, "neighborBlocks", nil, nd, coll)
    if type(refused) == "string" then
      neighbor = neighbor or {}
      neighbor[nd] = refused
    end
  end
  m.neighbor = neighbor
  if ask(P, "isWarpCollision", false, coll) == true then
    local mat = ask(P, "carpetDirection", nil, coll)
    m.warp = Export2.WARP_KINDS[coll] or (mat and "edge" or "door")
    if type(mat) == "string" then m.mat = mat end
    if ask(P, "warpFacesDown", false, coll) == true then m.facesDown = true end
  end
  return m
end

-- The loop of an animated tile: its row per timer value (rowOf(t), t = 0..7)
-- as frames in order of first use and runs { frame index, frames } over one
-- pass of the timer from 0, each value lasting `period` frames, equal
-- neighbours merged.
function Export2.loop(rowOf, period)
  local frames, index, runs = {}, {}, {}
  for t = 0, 7 do
    local row = rowOf(t)
    if row ~= nil then
      local f = index[row]
      if f == nil then
        frames[#frames + 1] = row
        f = #frames - 1
        index[row] = f
      end
      if #runs > 0 and runs[#runs][1] == f then
        runs[#runs][2] = runs[#runs][2] + period
      else
        runs[#runs + 1] = { f, period }
      end
    end
  end
  -- (a last run that looks like the first is kept apart: every loop starts at timer 0, so tiles stay in step)
  return frames, runs
end

function Export2.install(b)
  local def = b.def

  local function sortedKeys(t)
    local out = {}
    for k in pairs(t) do out[#out + 1] = k end
    sort(out, function(x, y) return tostring(x) < tostring(y) end)
    return out
  end

  -- ({ ok } | false, err): every g2* resource through x.put.
  -- x: { put(kind, id, data) -> bool, checkpoint(), warn(code, example), json, base64(s), region }
  def("exportResources", function(x)
    local json, put, checkpoint, warn = x.json, x.put, x.checkpoint, x.warn
    local love = b.love
    local game = b.game
    local data = type(game) == "table" and type(game.data) == "table" and game.data or nil
    local maps = data and data.gen2Maps or nil
    if type(maps) ~= "table" then return false, "no_maps" end
    local tilesets, pals = data.gen2Tilesets, data.gen2Palettes
    if type(tilesets) ~= "table" or type(pals) ~= "table" then return false, "no_art_data" end
    local Palettes, TileAttrs = b.w2("Palettes"), b.w2("TileAttrs")
    local P, World, Assets = b.w2("Permissions"), b.w2("World"), b.mod("src.render.Assets")
    if not (Palettes and TileAttrs and P and World and Assets and love and love.image and love.data) then
      return false, "no_engine"
    end
    local crystal = b.isCrystal()
    local roofs = type(data.gen2Roofs) == "table" and data.gen2Roofs or {}
    local function base64(s)
      local ok, text = pcall(love.data.encode, "string", "base64", s)
      if ok and type(text) == "string" then return text end
      return x.base64(s)
    end
    local function png(w, h, bytes)
      local img = love.image.newImageData(w, h, "rgba8", bytes)
      local file = img:encode("png")
      local s = file:getString()
      if file.release then file:release() end
      if img.release then img:release() end
      return base64(s)
    end

    -- ---- sheets (decoded once; Assets.resolve applies the overrides the game draws with, Assets.lua:38-50)
    local sheets = {}
    local function sheet(path)
      if type(path) ~= "string" then return nil end
      local s = sheets[path]
      if s ~= nil then return s or nil end
      local okR, resolved = pcall(Assets.resolve, path)
      local okD, img = false, nil
      if okR then okD, img = pcall(love.image.newImageData, resolved) end
      if okD and img then
        local w, h = img:getDimensions()
        s = { w = w, h = h, raw = img:getString() }
        if img.release then img:release() end
      else
        warn("g2_sheet_missing", path:match("([%w_]+)%.png$") or "sheet")
        s = false
      end
      sheets[path] = s
      return s or nil
    end

    -- ---- baked tiles and pages
    local known, nextId = {}, 0
    local page, pageNo = {}, 0
    local CLEAR = string.rep("\0", 256)
    local function flush()
      if #page == 0 then return end
      local rows = {}
      for r = 0, Export2.PAGE_SIDE * 8 - 1 do
        local qrow, sr = floor(r / 8), (r % 8) * 32
        local parts = {}
        for c = 0, Export2.PAGE_SIDE - 1 do
          local q = page[qrow * Export2.PAGE_SIDE + c + 1] or CLEAR
          parts[c + 1] = q:sub(sr + 1, sr + 32)
        end
        rows[r + 1] = concat(parts)
      end
      checkpoint()
      local side = Export2.PAGE_SIDE * 8
      local id = ("g2q.%d"):format(pageNo)
      put("g2image", id, { id = id, w = side, h = side, png = png(side, side, concat(rows)) })
      page, pageNo = {}, pageNo + 1
      checkpoint()
    end
    local function newTile(bytes)
      local id = nextId
      nextId = nextId + 1
      page[#page + 1] = bytes
      if #page >= Export2.PAGE then flush() end
      return id
    end
    local function idOf(bytes)
      local h = love.data.hash("md5", bytes)
      local id = known[h]
      if id then return id end
      id = newTile(bytes)
      known[h] = id
      return id
    end

    -- patterns by (sheet, tile), palettes by colours, baked tiles by both
    local patternIds, patterns = {}, {}
    local function patternAt(s, path, x0, y0)
      local key = path .. "@" .. x0 .. "," .. y0
      local id = patternIds[key]
      if id == nil then
        local p = Export2.pattern(s, x0, y0)
        if p then
          patterns[#patterns + 1] = p
          id = #patterns
        else
          id = false
        end
        patternIds[key] = id
      end
      return id or nil
    end
    local paletteIds, paletteN = {}, 0
    local function paletteOf(colors)
      local parts = {}
      for i = 1, 4 do
        local c = type(colors) == "table" and colors[i] or nil
        parts[i] = type(c) == "table" and ((c[1] or 0) .. "," .. (c[2] or 0) .. "," .. (c[3] or 0)) or "0,0,0"
      end
      local key = concat(parts, ";")
      local id = paletteIds[key]
      if not id then
        paletteN = paletteN + 1
        id = paletteN
        paletteIds[key] = id
      end
      return id, key
    end
    local baked = {}
    local function bake(patternId, colors)
      local pid = paletteOf(colors)
      local key = patternId * 65536 + pid
      local id = baked[key]
      if id == nil then
        id = idOf(Export2.rgba(patterns[patternId], colors, false))
        baked[key] = id
      end
      return id
    end
    local blank = nil
    local function blankTile(colors)
      blank = blank or { shades = string.rep("\0", 64), alphas = string.rep("\255", 64) }
      return idOf(Export2.rgba(blank, colors, false))
    end

    -- the World's own rules, asked through stand-ins (their methods read only what is given)
    local proxy = setmetatable({ tilesets = tilesets, landmarks = data.gen2Landmarks }, { __index = World })

    -- ---- variants and maps
    -- (G2H: our own maps -- a Wild Routes end's route map, registered in data.gen2Maps -- are the builder's to place
    -- from the routes resource, not the game's maps)
    local ids = {}
    for id in pairs(maps) do
      if type(id) == "string" and b.ext(id) and not id:find("^KB_") then ids[#ids + 1] = id end
    end
    sort(ids)
    local variants, variantOf, variantN = {}, {}, {}
    local animKnown, anims = {}, {}
    local counts = { variants = 0, animated = 0, outOfSheet = 0, maps = 0, sprites = 0 }

    -- One animated tile of a variant: its frames baked through the tile's palette, a baked tile of its own whose
    -- page holds its frame at timer 0, and its loop; nil when its frames never differ.
    local function animated(v, ts, tile, layer, colors)
      local stripKey, rowPattern
      if layer.kind == "scroll" then
        local sx, sy = (tile % v.perRow) * 8, floor(tile / v.perRow) * 8
        local pid = v.atlasPattern(sx, sy)
        if not pid then return nil end
        local base = patterns[pid]
        local sc = type(layer.scroll) == "table" and layer.scroll or {}
        stripKey = "scroll:" .. pid .. ":" .. tostring(sc.h) .. "," .. tostring(sc.v)
        rowPattern = function(row)
          local t = row - 1
          local dx = ((tonumber(sc.h) or 0) > 0) and (Export2.SCROLL_H[t] % 8) or 0
          local dy = (t * (tonumber(sc.v) or 0)) % 8
          return Export2.scrolled(base, dx, dy)
        end
      else
        local s = sheet(layer.sheet)
        if not s then return nil end
        stripKey = "strip:" .. tostring(layer.sheet)
        rowPattern = function(row)
          local pid = patternAt(s, layer.sheet, 0, (row - 1) * 8)
          return pid and patterns[pid] or nil
        end
      end
      local period = tonumber(ts.anim and ts.anim.period) or 0
      if period < 1 then return nil end
      local frames, runs = Export2.loop(function(t)
        local okR, row = pcall(World.animRow, setmetatable({ animTimer = t }, { __index = proxy }), layer)
        return okR and tonumber(row) or nil
      end, period)
      local ids2, slotOf, remap = {}, {}, {}
      local firstBytes
      for i, row in ipairs(frames) do
        local p = rowPattern(row)
        if not p then return nil end
        local bytes = Export2.rgba(p, colors, false)
        if i == 1 then firstBytes = bytes end
        local qid = idOf(bytes)
        if not slotOf[qid] then
          ids2[#ids2 + 1] = qid
          slotOf[qid] = #ids2 - 1
        end
        remap[i - 1] = slotOf[qid]
      end
      if #ids2 < 2 then return nil end
      local merged = {}
      for _, r in ipairs(runs) do
        local f = remap[r[1]]
        if #merged > 0 and merged[#merged][1] == f then
          merged[#merged][2] = merged[#merged][2] + r[2]
        else
          merged[#merged + 1] = { f, r[2] }
        end
      end
      if #merged < 2 then return nil end
      local parts = {}
      for i, r in ipairs(merged) do parts[i] = r[1] .. ":" .. r[2] end
      local key = stripKey .. "|" .. concat(ids2, ",") .. "|" .. concat(parts, ",")
      local id = animKnown[key]
      if not id then
        id = newTile(firstBytes)
        animKnown[key] = id
        local s2 = {}
        for i, r in ipairs(merged) do s2[i] = json.array({ r[1], r[2] }) end
        anims[#anims + 1] = { q = id, f = json.array(ids2), s = json.array(s2) }
      end
      counts.animated = counts.animated + 1
      return id
    end

    -- A map's art variant: its tileset, roof sheet and eight palettes at the baked daytime. Baked once.
    local function variantFor(mdef, daytime)
      local ts = tilesets[mdef.tileset]
      if type(ts) ~= "table" or type(ts.blocks) ~= "table" then return nil end
      local okS, set = pcall(Palettes.bgSet, pals, mdef, daytime)
      if not (okS and type(set) == "table") then return nil end
      local roofName
      if Export2.ROOF_TILESETS[mdef.tileset] and type(roofs.mapGroupRoofs) == "table" then
        roofName = roofs.mapGroupRoofs[mdef.group]
      end
      local roofSpec = roofName and type(roofs.roofs) == "table" and roofs.roofs[roofName] or nil
      local roofPath = type(roofSpec) == "table" and roofSpec.image or nil
      local setKey = {}
      for slot = 1, 8 do
        local _, k = paletteOf(set[slot])
        setKey[slot] = k
      end
      local vkey = tostring(mdef.tileset) .. "|" .. tostring(roofPath) .. "|" .. concat(setKey, "/")
      local v = variants[vkey]
      if v then return v end
      local n = (variantN[mdef.tileset] or 0) + 1
      variantN[mdef.tileset] = n
      local id = ("%s_%d"):format(tostring(mdef.tileset), n):sub(1, 64)
      local atlas = sheet(ts.image)
      local roof = roofPath and sheet(roofPath) or nil
      v = { id = id, tileset = mdef.tileset, perRow = floor(tonumber(ts.tilesPerRow) or 16), set = set }
      -- a sheet tile through the roof overlay: tiles $0a-$12 come from the roof's 9-tile row
      function v.atlasPattern(sx, sy)
        local tileId = floor(sy / 8) * v.perRow + floor(sx / 8)
        if roof and tileId >= Export2.ROOF_FIRST and tileId <= Export2.ROOF_LAST then
          return patternAt(roof, roofPath, (tileId - Export2.ROOF_FIRST) * 8, 0)
        end
        return atlas and patternAt(atlas, ts.image, sx, sy) or nil
      end
      local okL, layers = pcall(World.animLayers, proxy, ts)
      layers = okL and type(layers) == "table" and layers or {}
      local animIds = {}
      local blocks, colls, used = {}, {}, {}
      for bid = 0, 127 do
        local block = ts.blocks[bid + 1]
        local ids16 = {}
        for i = 1, 16 do
          local tile = type(block) == "table" and floor(tonumber(block[i]) or 0) or 0
          local slot
          local sheetId = tile
          if crystal then
            local attr = TileAttrs.forTile(ts, tile)
            slot = attr and attr.palette or 1
            sheetId = TileAttrs.sheetTileId(tile, attr)
          else
            slot = type(ts.tilePalettes) == "table" and ts.tilePalettes[tile + 1] or 1
          end
          local colors = set[slot] or set[1]
          local layer = layers[tile]
          local qid
          if layer then
            local akey = tile .. "/" .. slot
            if animIds[akey] == nil then animIds[akey] = animated(v, ts, tile, layer, colors) or false end
            qid = animIds[akey] or nil
          end
          if not qid then
            local pid = v.atlasPattern((sheetId % v.perRow) * 8, floor(sheetId / v.perRow) * 8)
            if pid then
              qid = bake(pid, colors)
            else
              counts.outOfSheet = counts.outOfSheet + 1
              qid = blankTile(colors)
            end
          end
          ids16[i] = qid
        end
        blocks[bid + 1] = json.array(ids16)
        local quad = type(ts.collision) == "table" and ts.collision[bid + 1] or nil
        local c4 = {}
        for q = 1, 4 do
          local c = type(quad) == "table" and tonumber(quad[q]) or 0xff
          c4[q] = floor(c) % 256
          used[c4[q]] = true
        end
        colls[bid + 1] = json.array(c4)
        if bid % 16 == 15 then checkpoint() end
      end
      local meanings = json.object()
      for _, c in ipairs(sortedKeys(used)) do
        local m = Export2.meaning(P, b.ask, c)
        local o = json.object()
        for k, val in pairs(m) do
          if type(val) == "table" and val[1] ~= nil then
            o[k] = json.array(val)
          elseif type(val) == "table" then
            local inner = json.object()
            for dk, dv in pairs(val) do inner[dk] = dv end
            o[k] = inner
          else
            o[k] = val
          end
        end
        meanings[tostring(c)] = o
      end
      put("g2tiles", id, { id = id, tileset = mdef.tileset, roof = roofName, blocks = json.array(blocks),
                           coll = json.array(colls), meanings = meanings })
      variants[vkey] = v
      counts.variants = counts.variants + 1
      return v
    end

    -- a map's objects as the schema wants them (RomExtractorGen2.lua:1322-1351)
    local spriteDefs = type(data.gen2Sprites) == "table" and data.gen2Sprites or {}
    local function objectsOf(mdef)
      local out = {}
      for i, o in ipairs(type(mdef.objects) == "table" and mdef.objects or {}) do
        if type(o) == "table" then
          local lid = floor(tonumber(o.index) or i)
          local ox, oy = tonumber(o.x), tonumber(o.y)
          if ox and oy and lid >= 1 and lid <= 255 then
            local e = { lid = lid, x = floor(ox), y = floor(oy) }
            -- a variable sprite (SPRITE_VARS and up, World:resolveSprite, World.lua:2233-2252) is the
            -- hidden game's to name at run time: its raw id
            local raw = tonumber(o.spriteId) or tonumber(o.sprite)
            if b.symbol(o.sprite) then e.sprite = o.sprite end
            if raw and not (e.sprite and type(spriteDefs[o.sprite]) == "table") then e.var = floor(raw) % 256 end
            local mv = floor(tonumber(o.movement) or 0) % 256
            e.movement = mv
            local pal = b.ask(Palettes, "objectPaletteId", nil, o)
            if pal then e.palette = floor(pal) % 8 end
            local kind = "person"
            if tonumber(o.type) == 2 or o.trainer ~= nil then kind = "trainer"
            elseif tonumber(o.type) == 1 or o.itemball ~= nil then kind = "item"
            elseif mv == 0x19 then kind = "boulder"
            elseif mv == 0x18 then kind = "rock" end
            e.kind = kind
            local flag = tonumber(o.eventFlag)
            if flag and flag >= 0 and flag < 0xFFFF then e.flag = floor(flag) end
            local h = type(o.hours) == "table" and o.hours or nil
            local h1, h2 = h and tonumber(h[1]) or -1, h and tonumber(h[2]) or -1
            if not (h1 == -1 and h2 == -1) and not (h1 ~= -1 and h1 == h2) then
              e.hours = json.array({ floor(h1), floor(h2) })
            end
            local walk = Export2.WALKS[mv]
            local r = type(o.radius) == "table" and o.radius or {}
            local rx, ry = tonumber(r.x) or 0, tonumber(r.y) or 0
            e.stays = not (walk and ((walk ~= "y" and rx > 0) or (walk ~= "x" and ry > 0)))
            if Export2.BIG[mv] then
              local okB, facing = b.call(b.w2("Npc"), "bigFacing", mv, e.sprite)
              e.big = (okB and facing == "sym") and "sym" or "asym"
            end
            out[#out + 1] = e
          end
        end
      end
      return out
    end

    for _, id in ipairs(ids) do
      local mdef = maps[id]
      local m = b.pristineMap and b.pristineMap(id) or nil
      local w, h = floor(tonumber(mdef.width) or 0), floor(tonumber(mdef.height) or 0)
      if not m or m.width ~= w or m.height ~= h or w < 1 or h < 1 or w > 255 or h > 255 then
        warn("g2_map_data", b.ext(id))
      else
        local okD, daytime = pcall(Palettes.daytimeFor, mdef, 12, true)
        daytime = okD and daytime or "DAY"
        local v = variantFor(mdef, daytime)
        if not v then
          warn("g2_no_variant", b.ext(id))
        else
          -- MG9 (G2H): a Wild Routes clearing's blocks are baked in, as the hidden game runs them
          local route = type(b.routeBlocks) == "function" and b.routeBlocks(id) or nil
          local bytes = {}
          for i = 1, w * h do
            local blk = route and route[i] or m.blocks[i]
            bytes[i] = char(floor(tonumber(blk) or 0) % 256)
          end
          local conns = {}
          for _, dir in ipairs({ "north", "south", "west", "east" }) do
            local c = type(mdef.connections) == "table" and mdef.connections[dir] or nil
            local to = type(c) == "table" and b.ext(c.mapId or c.map) or nil
            local off = type(c) == "table" and tonumber(c.offset) or nil
            if to and off and maps[c.mapId or c.map] then
              conns[#conns + 1] = { dir = dir, map = to, offset = floor(off) }
            end
          end
          local warps = {}
          for _, wp in ipairs(type(mdef.warps) == "table" and mdef.warps or {}) do
            local wx, wy, dw = tonumber(wp.x), tonumber(wp.y), tonumber(wp.destWarp)
            if wx and wy and wx >= 0 and wy >= 0 and wx <= 511 and wy <= 511 then
              local e = { x = floor(wx), y = floor(wy), warp = 0 }
              if dw == 0xff or dw == -1 then
                e.dynamic = true
              elseif dw and dw >= 1 then
                e.warp = floor(dw - 1) % 256
              end
              local to = maps[wp.destMap] and b.ext(wp.destMap) or nil
              if to then e.to = to elseif not e.dynamic then e.dynamic = true end
              warps[#warps + 1] = e
            end
          end
          local coords, signs = {}, {}
          for _, ev in ipairs(type(mdef.coordEvents) == "table" and mdef.coordEvents or {}) do
            local ex, ey = tonumber(ev.x), tonumber(ev.y)
            if ex and ey and ex >= 0 and ey >= 0 and ex <= 511 and ey <= 511 then
              coords[#coords + 1] = { x = floor(ex), y = floor(ey), scene = floor(tonumber(ev.sceneId) or 0) % 256 }
            end
          end
          for _, ev in ipairs(type(mdef.bgEvents) == "table" and mdef.bgEvents or {}) do
            local ex, ey = tonumber(ev.x), tonumber(ev.y)
            if ex and ey and ex >= 0 and ey >= 0 and ex <= 511 and ey <= 511 then
              signs[#signs + 1] = { x = floor(ex), y = floor(ey), kind = floor(tonumber(ev.kind) or 0) % 256 }
            end
          end
          local okA, area = pcall(World.region, setmetatable({ map = { def = mdef } }, { __index = proxy }))
          local env = type(mdef.environment) == "string" and mdef.environment:find("^[A-Z_]+$") and mdef.environment
            or "TOWN"
          put("g2map", b.ext(id), {
            id = b.ext(id), w = w, h = h, blocks = base64(concat(bytes)),
            border = floor(tonumber(mdef.borderBlock) or 0) % 256, variant = v.id, tileset = mdef.tileset,
            env = env, palette = type(mdef.palette) == "string" and mdef.palette or nil, daytime = daytime,
            group = floor(tonumber(mdef.group) or 0) % 256, num = floor(tonumber(mdef.map) or 0) % 256,
            area = (okA and area == "kanto") and "kanto" or "johto",
            dark = mdef.palette == "PALETTE_DARK" or nil,
            fishGroup = b.symbol(mdef.fishGroup) and mdef.fishGroup or nil,
            connections = json.array(conns), warps = json.array(warps), coords = json.array(coords),
            signs = json.array(signs), objects = json.array(objectsOf(mdef)),
          })
          counts.maps = counts.maps + 1
        end
      end
      checkpoint()
    end
    flush()
    put("g2anims", "all", { id = "all", anims = json.array(anims) })

    -- ---- sprites: every sheet with its own OBJ palette, and each palette an object overrides it with
    -- (Palettes.spritePalette, Palettes.lua:236-249; the DAY set; shade 0 is the sheet's own cutout)
    local sprites = type(data.gen2Sprites) == "table" and data.gen2Sprites or {}
    local pairsUsed = {}
    for _, id in ipairs(ids) do
      for _, o in ipairs(type(maps[id].objects) == "table" and maps[id].objects or {}) do
        local pal = type(o) == "table" and b.ask(Palettes, "objectPaletteId", nil, o) or nil
        if pal and b.symbol(o.sprite) and type(sprites[o.sprite]) == "table" then
          pairsUsed[o.sprite .. ".p" .. floor(pal) % 8] = { sprite = o.sprite, obj = o, pal = floor(pal) % 8 }
        end
      end
    end
    local function sheetSprite(name, spriteDef, obj, pal)
      local s = sheet(spriteDef.image)
      if not s or s.w ~= 16 or s.h < 16 or s.h % 16 ~= 0 then
        warn("g2_sprite_sheet", name)
        return
      end
      local okC, colors = pcall(Palettes.spritePalette, pals, "DAY", spriteDef, obj)
      if not (okC and type(colors) == "table") then
        warn("g2_sprite_palette", name)
        return
      end
      local rows = {}
      for ty = 0, s.h / 8 - 1 do
        local left = Export2.pattern(s, 0, ty * 8)
        local right = Export2.pattern(s, 8, ty * 8)
        local lb, rb = Export2.rgba(left, colors, true), Export2.rgba(right, colors, true)
        for r = 0, 7 do rows[#rows + 1] = lb:sub(r * 32 + 1, r * 32 + 32) .. rb:sub(r * 32 + 1, r * 32 + 32) end
      end
      local frames = floor(s.h / 16)
      put("g2sprite", name, { id = name, sprite = spriteDef.id or name, palette = pal, w = 16, h = s.h,
                              frames = frames, walker = spriteDef.walker == true or nil,
                              type = b.symbol(spriteDef.spriteType) and spriteDef.spriteType or nil,
                              png = png(16, s.h, concat(rows)) })
      counts.sprites = counts.sprites + 1
      checkpoint()
    end
    for _, name in ipairs(sortedKeys(sprites)) do
      local sd = sprites[name]
      if b.symbol(name) and type(sd) == "table" then
        local own = tonumber(sd.paletteId) or 0
        sheetSprite(name, sd, nil, floor(own) % 8)
      end
    end
    for _, key in ipairs(sortedKeys(pairsUsed)) do
      local e = pairsUsed[key]
      local sd = sprites[e.sprite]
      if floor(tonumber(sd.paletteId) or 0) % 8 ~= e.pal then sheetSprite(key, sd, e.obj, e.pal) end
    end

    -- ---- the layout: each surface area from its root, breadth first through the connections with the World's own
    -- neighbour arithmetic (World.computeNeighbors, World.lua:511-558: Gen 1's strip maths, offsets in blocks). The
    -- directions are taken in a fixed order, so where a loop of the game's maps doesn't close on a flat plane the
    -- same edge gives way every time (the engine's walk takes them in table order).
    for _, a in ipairs(Export2.AREAS) do
      local root = maps[a.root]
      if type(root) == "table" then
        local placed = json.object()
        local at = { [a.root] = { x = 0, y = 0 } }
        local queue, head = { a.root }, 1
        while head <= #queue do
          local id = queue[head]
          head = head + 1
          local cur, pos = maps[id], at[id]
          for _, dir in ipairs({ "north", "south", "west", "east" }) do
            local c = type(cur.connections) == "table" and cur.connections[dir] or nil
            local destId = type(c) == "table" and (c.mapId or c.map) or nil
            local d = type(destId) == "string" and maps[destId] or nil
            if type(d) == "table" and at[destId] == nil then
              local off = floor(tonumber(c.offset) or 0)
              local x, y
              if dir == "north" then x, y = pos.x + off, pos.y - floor(d.height)
              elseif dir == "south" then x, y = pos.x + off, pos.y + floor(cur.height)
              elseif dir == "west" then x, y = pos.x - floor(d.width), pos.y + off
              else x, y = pos.x + floor(cur.width), pos.y + off end
              at[destId] = { x = x, y = y }
              queue[#queue + 1] = destId
            end
          end
        end
        for id, pos in pairs(at) do
          local ext, d = b.ext(id), maps[id]
          if ext and math.abs(pos.x) <= 4096 and math.abs(pos.y) <= 4096 then
            placed[ext] = { x = pos.x, y = pos.y, w = floor(d.width), h = floor(d.height) }
          else
            warn("g2_layout_bad", ext)
          end
        end
        local rootId = b.ext(a.root)
        local layout = { root = rootId, unit = "blocks", maps = placed }
        if a.area == "johto" then
          local spawns = type(data.gen2Landmarks) == "table" and data.gen2Landmarks.spawns or nil
          local home = type(spawns) == "table" and spawns.SPAWN_HOME or nil
          if type(home) == "table" and maps[home.map] and tonumber(home.x) and tonumber(home.y) then
            layout.start = { map = b.ext(home.map), x = floor(home.x), y = floor(home.y) }
          end
        end
        put("g2layout", a.area, layout)
      else
        warn("g2_no_root", a.root)
      end
      checkpoint()
    end

    -- ---- encounters (national dex numbers: Gen 2's species order is the dex's, RomExtractorGen2.lua:1-5)
    local consts = type(data.gen2Constants) == "table" and data.gen2Constants or {}
    local dexOf = {}
    for i, name in ipairs(type(consts.speciesOrder) == "table" and consts.speciesOrder or {}) do
      if b.symbol(name) and i <= 251 then dexOf[name] = i end
    end
    local function dex(species)
      local n = dexOf[species] or tonumber(species)
      if n and n >= 1 and n <= 251 then return floor(n) end
      return nil
    end
    local function mon(e, levelKey)
      local d = type(e) == "table" and dex(e.species) or nil
      local lv = type(e) == "table" and tonumber(e[levelKey or "level"]) or nil
      if d and lv and lv >= 1 and lv <= 100 then return { dex = d, level = floor(lv) } end
      return nil
    end
    local enc = type(data.gen2Encounters) == "table" and data.gen2Encounters or {}
    local DAYS = { "MORN", "DAY", "NITE" }
    local function grassOf(t)
      local out = json.object()
      for _, id in ipairs(sortedKeys(type(t) == "table" and t or {})) do
        local e = t[id]
        local ext = maps[id] and b.ext(id) or nil
        if ext and type(e) == "table" and type(e.slots) == "table" then
          local rates, slots, bad = json.object(), json.object(), false
          for _, d in ipairs(DAYS) do
            rates[d] = floor(tonumber(type(e.rates) == "table" and e.rates[d]) or 0) % 256
            local list = {}
            for _, s in ipairs(type(e.slots[d]) == "table" and e.slots[d] or {}) do
              local mm = mon(s)
              if mm then list[#list + 1] = mm else bad = true end
            end
            slots[d] = json.array(list)
          end
          if bad then warn("g2_encounter_slot", ext) end
          out[ext] = { rates = rates, slots = slots }
        end
      end
      return out
    end
    local function waterOf(t)
      local out = json.object()
      for _, id in ipairs(sortedKeys(type(t) == "table" and t or {})) do
        local e = t[id]
        local ext = maps[id] and b.ext(id) or nil
        if ext and type(e) == "table" then
          local list = {}
          for _, s in ipairs(type(e.slots) == "table" and e.slots or {}) do
            local mm = mon(s)
            if mm then list[#list + 1] = mm else warn("g2_encounter_slot", ext) end
          end
          out[ext] = { rate = floor(tonumber(e.rate) or 0) % 256, slots = json.array(list) }
        end
      end
      return out
    end
    local fish = json.object()
    for _, g in ipairs(sortedKeys(type(enc.fishGroups) == "table" and enc.fishGroups or {})) do
      local grp = enc.fishGroups[g]
      if b.symbol(g) and type(grp) == "table" then
        local rods = { chance = floor(tonumber(grp.chance) or 0) % 256 }
        for _, rod in ipairs({ "old", "good", "super" }) do
          local list = {}
          for _, r in ipairs(type(grp[rod]) == "table" and grp[rod] or {}) do
            local row = mon(r)
            if row then
              row.chance = floor(tonumber(r.chance) or 0) % 256
              local nite = type(r.nite) == "table" and mon(r.nite) or nil
              if nite then row.nite = nite end
              list[#list + 1] = row
            end
          end
          rods[rod] = json.array(list)
        end
        fish[g] = rods
      end
    end
    local function setsOf(t)
      local out = json.object()
      for _, id in ipairs(sortedKeys(type(t) == "table" and t or {})) do
        local ext = maps[id] and b.ext(id) or nil
        if ext and b.symbol(t[id]) then out[ext] = t[id] end
      end
      return out
    end
    local treeSets = json.object()
    for _, name in ipairs(sortedKeys(type(enc.treeSets) == "table" and enc.treeSets or {})) do
      local set = enc.treeSets[name]
      if b.symbol(name) and type(set) == "table" then
        local o = json.object()
        for _, part in ipairs({ "common", "rare" }) do
          if type(set[part]) == "table" then
            local list = {}
            for _, r in ipairs(set[part]) do
              local row = mon(r)
              if row then
                row.chance = floor(tonumber(r.chance) or 0) % 256
                list[#list + 1] = row
              end
            end
            o[part] = json.array(list)
          end
        end
        treeSets[name] = o
      end
    end
    local contest = {}
    for _, r in ipairs(type(enc.bugContest) == "table" and enc.bugContest or {}) do
      local d = type(r) == "table" and dex(r.species) or nil
      local lo, hi = tonumber(r.min), tonumber(r.max)
      if d and lo and hi and lo >= 1 and hi <= 100 then
        contest[#contest + 1] = { dex = d, min = floor(lo), max = floor(hi), chance = floor(tonumber(r.chance) or 0) % 256 }
      end
    end
    local E = b.mod("src.battle.gen2.Encounter")
    local function chances(list)
      if type(list) ~= "table" then return nil end
      local out = {}
      for i, v in ipairs(list) do
        if not tonumber(v) then return nil end
        out[i] = floor(tonumber(v))
      end
      return json.array(out)
    end
    put("g2encounters", "all", {
      grass = grassOf(enc.grass), water = waterOf(enc.water),
      swarmGrass = grassOf(enc.swarmGrass), swarmWater = waterOf(enc.swarmWater),
      fish = fish, trees = setsOf(enc.trees), rocks = setsOf(enc.rocks), treeSets = treeSets,
      contest = json.array(contest),
      grassChances = chances(type(E) == "table" and E.GRASS_SLOT_CHANCES or nil),
      waterChances = chances(type(E) == "table" and E.WATER_SLOT_CHANCES or nil),
    })
    checkpoint()

    -- ---- names (the engine's constant orders: a species' place is its dex number)
    local function names(order, maxId)
      local out = json.object()
      for i, name in ipairs(type(order) == "table" and order or {}) do
        if b.symbol(name) and i <= maxId and out[name] == nil then out[name] = i end
      end
      return out
    end
    put("g2names", "all", {
      species = names(consts.speciesOrder, 251), moves = names(consts.moveOrder, 1023),
      items = names(consts.itemOrder, 1023),
    })

    -- ---- the swap sites (story.lua's swapSites: the list the runtime check uses)
    local sites = b.adapter.swapSites()
    put("swaps", "all", { sites = json.array(type(sites) == "table" and sites or {}) })

    if b.log then
      b.log.info("export.g2", { maps = counts.maps, variants = counts.variants, tiles = nextId, pages = pageNo,
                                animated = #anims, sprites = counts.sprites, outOfSheet = counts.outOfSheet,
                                sites = type(sites) == "table" and #sites or 0 })
    end
    return true
  end)
end

return Export2
