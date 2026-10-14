-- game3 export (GEN3_BRIDGE_PLAN.md §5, phase G3D): Emerald's world for the
-- builder, read from the running engine (the dataset view breaks on Gen 3's
-- maps, spike S2) and handed to the core's export service as the g3*
-- resources (protocol/export/g3*.schema.json). The core caches, hashes and
-- serves them (export/kb_export.lua); this part only reads and shapes.
--
--   g3map/<map>      a map in cells: its layout's own cells (metatile u16,
--                    collision u8, elevation u8, as the engine's layout
--                    keeps them, layout_native.lua:15-34, native_pack.lua's
--                    decodeMidLayout), border, connections, warps, objects
--   g3tiles/<pair>   a tileset pair: per metatile its four 8x8 quarters in
--                    the flattened art, its behaviour, whether it draws over
--                    sprites
--   g3image/g3q.<n>  the flattened quarters, 1,024 to a 256x256 RGBA page
--   g3sprite/<gfx>   the object-event sheets (RGBA strips)
--   g3layout/<region> the surface maps' cell origins (connections, BFS from
--                    the root)
--   g3encounters/all, g3names/all
--
-- Art: the engine prepares each pair's metatile atlases as RGBA on the CPU
-- (AssetDecode.pair, src/core/game3/asset_decode.lua:24-87: one 16x16 slot per
-- metatile and one atlas per plane: under, middle, over), so the export never
-- touches the GPU or the engine's resident textures. A quarter is flattened
-- the way the GBA draws a still frame: over, else middle, else under (GBA
-- pixels are opaque or clear); identical quarters are shared (MD5 of their
-- bytes). Animated tiles export their still frame (a later phase animates).
--
-- Everything read is the game's own data: it goes to the builder only, and
-- the logs carry codes, kinds and counts.
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Export3 = {}

Export3.PAGE_SIDE = 32                        -- quarters per page row and column
Export3.PAGE = Export3.PAGE_SIDE * Export3.PAGE_SIDE
Export3.ROOTS = { emerald = "EM_LITTLEROOT_TOWN" }
-- connection directions, as the engine's list keeps them or by the names
-- Connections.each maps (src/core/game3/connections.lua:3-6, :20-40)
Export3.DIRS = { north = "north", south = "south", west = "west", east = "east", up = "north", down = "south",
                 left = "west", right = "east", dive = "dive", emerge = "emerge" }
Export3.AREAS = { land = "land", water = "water", rocks = "rock", fishing = "fish" }
Export3.SLOTS = { land = 12, water = 5, rock = 5, fish = 10 }
-- The engine's movements that leave the object's cell (GfxIds.MOVEMENT,
-- scripting/gfx_ids.lua:144): the rest stand, turn or step in place.
Export3.WALKS = { WALK = true, BACK_FORTH = true, SEQUENCE = true }

local floor, char, byte = math.floor, string.char, string.byte
local concat, sort = table.concat, table.sort

function Export3.install(b)
  local def = b.def

  local function sortedKeys(t)
    local out = {}
    for k in pairs(t) do out[#out + 1] = k end
    sort(out, function(x, y) return tostring(x) < tostring(y) end)
    return out
  end

  local function u16(v) return char(v % 256, floor(v / 256) % 256) end

  -- A layer's 8x8 quarter q (0-3) of slot s as 256 RGBA bytes, and whether
  -- any pixel of it is opaque.
  local function quarterOf(raw, atlasW, cols, s, q)
    local sx, sy = (s % cols) * 16 + (q % 2) * 8, floor(s / cols) * 16 + floor(q / 2) * 8
    local rows = {}
    for r = 0, 7 do
      local at = ((sy + r) * atlasW + sx) * 4 + 1
      rows[r + 1] = raw:sub(at, at + 31)
    end
    local bytes = concat(rows)
    for i = 4, 256, 4 do
      if byte(bytes, i) ~= 0 then return bytes, true end
    end
    return bytes, false
  end

  -- over, else middle, else under, pixel by pixel
  local function flatten(under, middle, over)
    local px = {}
    for i = 1, 256, 4 do
      local src = under
      if over and byte(over, i + 3) ~= 0 then
        src = over
      elseif middle and byte(middle, i + 3) ~= 0 then
        src = middle
      end
      px[#px + 1] = src:sub(i, i + 3)
    end
    return concat(px)
  end

  -- A map's connections as { dir, map, offset } (cells): the engine's list
  -- form ({ dir, map, offset }, the import's connections.lua) and the keyed
  -- form, as Connections.each reads them; a direction may have several.
  local function connectionsOf(mdef)
    local out = {}
    local conns = type(mdef) == "table" and type(mdef.connections) == "table" and mdef.connections or {}
    local function add(dir, c)
      local d = Export3.DIRS[dir]
      local map = type(c) == "table" and (c.map or c.mapId) or nil
      local off = type(c) == "table" and tonumber(c.offset) or nil
      if d and b.symbol(map) and off and off >= -1024 and off <= 1024 then
        out[#out + 1] = { dir = d, map = map, offset = floor(off) }
      end
    end
    for i = 1, #conns do
      local c = conns[i]
      if type(c) == "table" then add(c.dir or c.direction, c) end
    end
    local keyed = {}
    for k in pairs(conns) do
      if type(k) == "string" then keyed[#keyed + 1] = k end
    end
    sort(keyed)
    for _, k in ipairs(keyed) do add(k, conns[k]) end
    return out
  end

  -- The objects of a map's templates (the script bundle's, as Objects.loadMap
  -- prefers them, objects.lua:634-643), as the schema wants them.
  -- Whether an object keeps its cell: the engine's own movement for its
  -- template (Objects.canonMovementType, objects.lua:131-139; GfxIds.hostMovement,
  -- scripting/gfx_ids.lua:136-151); walkers (WALK, BACK_FORTH, SEQUENCE) leave
  -- it. Nil when the template has no movement type.
  local function staysOf(d)
    local mt = tonumber(d.movementType)
    if not mt then return nil end
    local canon = tonumber(b.ask(b.g3("objects"), "canonMovementType", mt, mt)) or mt
    local spec = b.ask(b.g3("scripting.gfx_ids"), "hostMovement", nil, canon, d.rangeX, d.rangeY)
    if type(spec) ~= "table" or type(spec.movement) ~= "string" then return nil end
    return not Export3.WALKS[spec.movement]
  end

  local function objectsOf(map, mdef, C)
    local Space = b.g3("scripting.space")
    local ev = type(Space) == "table" and type(Space.bundle) == "table" and type(Space.bundle.events) == "table"
      and Space.bundle.events[map] or nil
    local defs = type(ev) == "table" and (ev.objects or ev.objectEvents) or (type(mdef) == "table" and mdef.objects)
    local out = {}
    for _, d in ipairs(type(defs) == "table" and defs or {}) do
      if type(d) == "table" then
        local lid = tonumber(d.localId or d.index)
        local gid = tonumber(d.graphicsId or d.gfx)
        local x, y = tonumber(d.x), tonumber(d.y)
        if lid and lid >= 1 and lid <= 254 and lid ~= 127 and gid and x and y then
          local gfx = C and b.ask(C, "name", nil, C, "event_objects", gid, "OBJ_EVENT_GFX_") or nil
          if not b.symbol(gfx) then gfx = ("OBJ_EVENT_GFX_%d"):format(gid) end
          local kind = "person"
          if gfx:find("ITEM_BALL", 1, true) then kind = "item"
          elseif gfx:find("BOULDER", 1, true) then kind = "boulder"
          elseif gfx:find("BERRY_TREE", 1, true) then kind = "berry"
          elseif (tonumber(d.trainerType) or 0) ~= 0 then kind = "trainer" end
          local o = { lid = lid, gfx = gfx, x = floor(x), y = floor(y), kind = kind }
          local elev = tonumber(d.elevation)
          if elev and elev >= 0 and elev <= 15 then o.elev = floor(elev) end
          local flag = tonumber(d.flag or d.flagId)
          if flag and flag >= 1 and flag <= 65534 then o.flag = floor(flag) end
          o.stays = staysOf(d)
          out[#out + 1] = o
        end
      end
    end
    return out
  end

  -- ({ ok }, err): every g3* resource through x.put.
  -- x: { put(kind, id, data) -> bool, checkpoint(), warn(code, example), json, base64(s), region }
  def("exportResources", function(x)
    local json, put, checkpoint, warn = x.json, x.put, x.checkpoint, x.warn
    local love = b.love
    local game = b.game
    local maps = type(game) == "table" and type(game.data) == "table" and game.data.maps or nil
    if type(maps) ~= "table" then return false, "no_maps" end
    local Dataset, Decode = b.g3("dataset"), b.g3("asset_decode")
    local Extract = b.mod("src.import.gba.extract_island1")
    if not (Dataset and Decode and Extract and love and love.image and love.data) then return false, "no_engine" end
    local okCache, cache = b.call(Dataset, "cache")
    if not okCache or type(cache) ~= "table" then return false, "no_cache" end
    -- the engine's own roots (extract_island1.lua:21-27, set by Dataset.mountExtractRoots)
    local cacheRoot, nativeRoot = Extract.CACHE_ROOT, Extract.NATIVE_ROOT
    if type(cacheRoot) ~= "string" or type(nativeRoot) ~= "string" then return false, "no_cache_root" end
    local C = b.constants()
    local IScripts = b.g3("scripting.interaction_scripts")
    local behaviors = type(IScripts) == "table" and type(IScripts.behaviors) == "table" and IScripts.behaviors or {}
    local MB = b.g3("mb")
    local function base64(s)
      local ok, text = pcall(love.data.encode, "string", "base64", s)
      if ok and type(text) == "string" then return text end
      return x.base64(s)
    end
    local function png(data)
      local file = data:encode("png")
      local s = file:getString()
      if file.release then file:release() end
      return base64(s)
    end

    -- ---- maps
    local byPair, ids = {}, {}
    for _, map in ipairs(sortedKeys(maps)) do
      local mdef = maps[map]
      if b.symbol(map) and type(mdef) == "table" then
        local ok, lay = pcall(function() return mdef.midLayout end) -- lazy (dataset.lua:357-372)
        local pair = mdef.pair or (ok and type(lay) == "table" and lay.pair) or nil
        if ok and type(lay) == "table" and type(lay.cells) == "table" and type(pair) == "string"
            and pair:find("^[A-Za-z0-9_]+$") and #pair <= 64 then
          ids[#ids + 1] = map
          byPair[pair] = byPair[pair] or {}
          table.insert(byPair[pair], map)
          -- the layout's whole grid (width x height, as the engine places it among its neighbours,
          -- Connections.sizeOf, connections.lua:41-45) and its true size (a small map is padded,
          -- native_pack.lua's padded_from; cells past the true size show the border, layout_native.lua:53-66)
          local w = floor(tonumber(lay.width) or 0)
          local h = floor(tonumber(lay.height) or 0)
          local tw = floor(tonumber(lay.trueWidth or lay.width) or 0)
          local th = floor(tonumber(lay.trueHeight or lay.height) or 0)
          if w < 1 or h < 1 or w > 1023 or h > 1023 or tw < 1 or th < 1 or tw > w or th > h then
            warn("g3_map_size", map)
          else
            local parts = {}
            -- MG8: a Wild Routes clearing's cells (routes.lua) are baked in, open, as the hidden game runs them
            local opened = type(b.routeCells) == "function" and b.routeCells(map) or {}
            for cy = 0, h - 1 do
              for cx = 0, w - 1 do
                local c = lay.cells[cy * w + cx + 1]
                local mid = type(c) == "table" and floor(tonumber(c.mid) or 0) or 0
                local coll = type(c) == "table" and floor(tonumber(c.coll) or 0xff) or 0xff
                local elev = type(c) == "table" and floor(tonumber(c.elev) or 0) or 0
                local o = opened[cy * 1024 + cx]
                if o then mid, coll, elev = floor(o.mid), 0x00, floor(o.elev) end
                parts[#parts + 1] = u16(mid % 1024) .. char(coll % 256, elev % 16)
              end
              if cy % 16 == 15 then checkpoint() end
            end
            local bw = floor(tonumber(lay.borderWidth) or 1)
            local bh = floor(tonumber(lay.borderHeight) or 1)
            local border = {}
            for i = 1, bw * bh do border[i] = floor(tonumber(lay.borderMids and lay.borderMids[i]) or 0) % 1024 end
            local conns = connectionsOf(mdef)
            local warps = {}
            for _, wp in ipairs(type(mdef.warps) == "table" and mdef.warps or {}) do
              local wx, wy = tonumber(wp.x), tonumber(wp.y)
              local id = tonumber(wp.destWarp or wp.warpId) or 0
              if wx and wy and wx >= 0 and wy >= 0 and wx <= 1023 and wy <= 1023 then
                local e = { x = floor(wx), y = floor(wy), warp = floor(id) % 256 }
                if b.symbol(wp.destMap) then e.to = wp.destMap else e.dynamic = true end
                warps[#warps + 1] = e
              end
            end
            local kind = type(mdef.kind) == "string" and mdef.kind:find("^[a-z_]+$") and #mdef.kind <= 32
              and mdef.kind or "town"
            put("g3map", map, {
              id = map, w = w, h = h, trueW = tw, trueH = th, pair = pair, cells = base64(concat(parts)),
              border = { w = bw, h = bh, mids = json.array(border) },
              connections = json.array(conns), warps = json.array(warps), objects = json.array(objectsOf(map, mdef, C)),
              mapType = floor(tonumber(mdef.mapType) or 0) % 256, kind = kind,
              music = floor(tonumber(mdef.music) or 0) % 65536, weather = floor(tonumber(mdef.weather) or 0) % 256,
            })
          end
        else
          warn("g3_no_layout", map)
        end
      end
      checkpoint()
    end

    -- ---- pairs: the flattened quarters, paged
    local known, nextId = {}, 0
    local page, pageNo = {}, 0
    local CLEAR = string.rep("\0", 256)
    local function flush()
      if #page == 0 then return end
      local rows = {}
      for r = 0, Export3.PAGE_SIDE * 8 - 1 do
        local qrow, sr = floor(r / 8), (r % 8) * 32
        local parts = {}
        for c = 0, Export3.PAGE_SIDE - 1 do
          local q = page[qrow * Export3.PAGE_SIDE + c + 1] or CLEAR
          parts[c + 1] = q:sub(sr + 1, sr + 32)
        end
        rows[r + 1] = concat(parts)
      end
      checkpoint()
      local side = Export3.PAGE_SIDE * 8
      local data = love.image.newImageData(side, side, "rgba8", concat(rows))
      put("g3image", ("g3q.%d"):format(pageNo), { id = ("g3q.%d"):format(pageNo), w = side, h = side, png = png(data) })
      if data.release then data:release() end
      page, pageNo = {}, pageNo + 1
      checkpoint()
    end
    local function idOf(bytes)
      local h = love.data.hash("md5", bytes)
      local id = known[h]
      if id then return id end
      id = nextId
      known[h], nextId = id, nextId + 1
      page[#page + 1] = bytes
      if #page >= Export3.PAGE then flush() end
      return id
    end

    -- The behaviours whose cells roll land encounters: the engine's own rule
    -- (encounter_rules/rse.lua:338-352, R.classify: the tile bits' encounter flag
    -- without the surfable one), through Encounters.rules (encounters.lua:383-393).
    local Rules = b.ask(b.g3("encounters"), "rules", nil)
    local landOf = {}
    local function land(bv)
      if landOf[bv] == nil then landOf[bv] = b.ask(Rules, "classify", nil, bv) == "land" end
      return landOf[bv]
    end

    for _, pair in ipairs(sortedKeys(byPair)) do
      local ok, out = pcall(Decode.pair, cache, nativeRoot, pair)
      if not ok or type(out) ~= "table" or type(out.midToSlot) ~= "table" then
        warn("g3_pair_failed", pair)
      else
        local raws = {}
        for _, layer in ipairs(out.layers or {}) do
          if layer.dataKey == "imageData" then raws.under = layer.data:getString()
          elseif layer.dataKey == "midImageData" then raws.middle = layer.data:getString()
          elseif layer.dataKey == "overImageData" then raws.over = layer.data:getString() end
        end
        if not raws.under then
          warn("g3_pair_no_art", pair)
        else
          local cols = floor(out.cols or 1)
          local atlasW = cols * 16
          local beh = type(behaviors[pair]) == "table" and behaviors[pair] or {}
          local mids = sortedKeys(out.midToSlot)
          local midList, quarters, behList, overList, names = {}, {}, {}, {}, json.object()
          local landSeen, landList = {}, {}
          for _, mid in ipairs(mids) do
            local s = out.midToSlot[mid]
            if type(mid) == "number" and mid >= 0 and mid <= 1023 and type(s) == "number" and #midList < 1024 then
              local qs, anyOver = {}, false
              for q = 0, 3 do
                local u = quarterOf(raws.under, atlasW, cols, s, q)
                local m, mOpaque, o, oOpaque
                if raws.middle then m, mOpaque = quarterOf(raws.middle, atlasW, cols, s, q) end
                if raws.over then o, oOpaque = quarterOf(raws.over, atlasW, cols, s, q) end
                if oOpaque then anyOver = true end
                local flat = (mOpaque or oOpaque) and flatten(u, mOpaque and m or nil, oOpaque and o or nil) or u
                qs[q + 1] = idOf(flat)
              end
              local bv = floor(tonumber(beh[mid]) or 0) % 256
              midList[#midList + 1], quarters[#quarters + 1] = mid, qs
              behList[#behList + 1], overList[#overList + 1] = bv, anyOver
              if Rules and not landSeen[bv] then
                landSeen[bv] = true
                if land(bv) then landList[#landList + 1] = bv end
              end
              if names[tostring(bv)] == nil then
                local name = b.ask(MB, "nameOf", nil, bv)
                if b.symbol(name) then names[tostring(bv)] = name end
              end
              if #midList % 32 == 0 then checkpoint() end
            end
          end
          sort(landList)
          put("g3tiles", pair, { id = pair, mids = json.array(midList), quarters = json.array(quarters),
                                 beh = json.array(behList), over = json.array(overList), behaviors = names,
                                 land = Rules and json.array(landList) or nil })
        end
      end
      out = nil
      checkpoint()
    end
    flush()

    -- ---- sprites: the object-event sheets (ow_extract's manifest, AssetDecode.sprite)
    local owRoot = cacheRoot .. "/ow"
    local Ow = b.g3("ow_sprites")
    local manifest = type(Ow) == "table" and type(Ow._manifest) == "table" and Ow._manifest or nil
    if not manifest then
      local okRead, src = pcall(cache.read, cache, owRoot .. "/manifest.lua")
      local chunk = okRead and type(src) == "string" and load(src, "@ow/manifest.lua", "t", {}) or nil
      local okRun, m = pcall(chunk or function() return nil end)
      manifest = okRun and type(m) == "table" and m or {}
    end
    local spriteIds = {}
    for _, gid in ipairs(sortedKeys(type(manifest.sprites) == "table" and manifest.sprites or {})) do
      local okS, meta = pcall(Decode.sprite, cache, owRoot, gid)
      if okS and type(meta) == "table" and meta.imageData then
        local name = C and b.ask(C, "name", nil, C, "event_objects", gid, "OBJ_EVENT_GFX_") or nil
        if not b.symbol(name) then name = ("OBJ_EVENT_GFX_%d"):format(gid) end
        if spriteIds[name] then name = ("%s_%d"):format(name, gid):sub(1, 64) end
        spriteIds[name] = true
        put("g3sprite", name, { id = name, gid = floor(gid), w = meta.width, h = meta.height,
                                frames = meta.frameCount, png = png(meta.imageData) })
      else
        warn("g3_sprite_failed")
      end
      checkpoint()
    end

    -- ---- the layout: the surface maps by connections from the root (cells), as
    -- the engine places neighbours (Map.computeWorld, map.lua:73-135: breadth
    -- first, the first placement wins, the layout's width x height)
    local root = Export3.ROOTS[b.adapter.gameVersion() or ""] or "EM_LITTLEROOT_TOWN"
    local function size(map)
      local mdef = maps[map]
      local ok, lay = pcall(function() return mdef.midLayout end)
      if not ok or type(lay) ~= "table" then return nil end
      return floor(tonumber(lay.width) or 0), floor(tonumber(lay.height) or 0)
    end
    local placed = json.object()
    local rw, rh
    if maps[root] then rw, rh = size(root) end
    local MapMod = b.g3("map")
    local okWorld, world = false, nil
    if rw and type(MapMod) == "table" and type(MapMod.computeWorld) == "function" then
      okWorld, world = pcall(MapMod.computeWorld, maps, root, 1000000, nil, nil, nil)
    end
    if rw and okWorld and type(world) == "table" then
      placed[root] = { x = 0, y = 0, w = rw, h = rh }
      for _, p in ipairs(world) do
        local w, h = size(p.id)
        local ox, oy = tonumber(p.ox), tonumber(p.oy)
        if b.symbol(p.id) and w and w > 0 and h > 0 and ox and oy and ox >= -16384 and ox <= 16384 and oy >= -16384
            and oy <= 16384 then
          placed[p.id] = { x = floor(ox), y = floor(oy), w = w, h = h }
        else
          warn("g3_layout_bad", p.id)
        end
      end
    elseif rw and rw > 0 and rh > 0 then
      placed[root] = { x = 0, y = 0, w = rw, h = rh }
      local queue, head = { root }, 1
      while head <= #queue do
        local id = queue[head]
        head = head + 1
        local a = placed[id]
        for _, c in ipairs(connectionsOf(maps[id])) do
          local dir, off = c.dir, c.offset
          if dir ~= "dive" and dir ~= "emerge" and maps[c.map] and placed[c.map] == nil then
            local w, h = size(c.map)
            if w and w > 0 and h > 0 then
              local bx, by
              if dir == "north" then bx, by = a.x + off, a.y - h
              elseif dir == "south" then bx, by = a.x + off, a.y + a.h
              elseif dir == "west" then bx, by = a.x - w, a.y + off
              else bx, by = a.x + a.w, a.y + off end
              placed[c.map] = { x = floor(bx), y = floor(by), w = w, h = h }
              queue[#queue + 1] = c.map
            end
          end
        end
        checkpoint()
      end
    else
      warn("g3_no_root", root)
    end
    put("g3layout", x.region or "hoenn", { root = root, unit = "cells", maps = placed })

    -- ---- encounters (encounters.lua's tables; national dex numbers)
    local Enc, Pokemon = b.g3("encounters"), b.g3("pokemon")
    b.call(Enc, "ensureLoaded")
    local MapCatalog = b.mod("src.import.gba.map_catalog")
    local encMaps = json.object()
    for _, k in ipairs(sortedKeys(type(Enc) == "table" and type(Enc._tables) == "table" and Enc._tables or {})) do
      local t = Enc._tables[k]
      local map = (type(k) == "string" and maps[k]) and k or nil
      if not map and type(t) == "table" and t.mapGroup ~= nil then
        local okM, id = b.call(MapCatalog, "mapIdFor", t.mapGroup, t.mapNum)
        if okM and maps[id] then map = id end
      end
      if map and type(t) == "table" then
        if type(t.variants) == "table" then warn("g3_encounter_variants", map) end
        local entry = json.object()
        for src, kind in pairs(Export3.AREAS) do
          local area = t[src]
          local slots = type(area) == "table" and (area.slots or area.mons or (#area > 0 and area)) or nil
          if type(slots) == "table" and #slots > 0 then
            local list = {}
            for i = 1, math.min(#slots, Export3.SLOTS[kind]) do
              local e = slots[i]
              local id = type(e) == "table" and tonumber(e.species or e[1]) or nil
              if not id and type(e) == "table" and type(e.species) == "string" then
                id = b.ask(Pokemon, "speciesFromName", nil, e.species)
              end
              local dex = id and b.ask(Pokemon, "national", nil, id) or nil
              local lo = type(e) == "table" and tonumber(e.minLevel or e.level or e[2]) or nil
              local hi = type(e) == "table" and tonumber(e.maxLevel or e.level or e[2]) or lo
              if dex and dex >= 1 and dex <= 2000 and lo and hi and lo >= 1 and hi <= 100 then
                if hi < lo then lo, hi = hi, lo end
                list[#list + 1] = { dex = floor(dex), min = floor(lo), max = floor(hi) }
              else
                warn("g3_encounter_slot", map)
              end
            end
            if #list > 0 then
              entry[kind] = { rate = floor(tonumber(area.rate) or 0) % 256, slots = json.array(list) }
            end
          end
        end
        if next(entry) ~= nil then encMaps[map] = entry end
      end
      checkpoint()
    end
    -- The land slots' chances, the table the engine's roll uses
    -- (encounters.lua:24 and :364, H.LAND_WEIGHTS; encounter_rules/rse.lua:116-118).
    local H = type(Enc) == "table" and type(Enc._h) == "table" and Enc._h or nil
    local weights = H and H.LAND_WEIGHTS
    local landWeights
    if type(weights) == "table" and #weights == Export3.SLOTS.land then
      landWeights = {}
      for i = 1, #weights do
        local v = tonumber(weights[i])
        if not v or v < 0 or v > 255 then landWeights = nil; break end
        landWeights[i] = floor(v)
      end
    end
    if not landWeights then warn("g3_land_weights_missing") end
    put("g3encounters", "all", { maps = encMaps, landWeights = landWeights and json.array(landWeights) or nil })

    -- ---- names (the engine's constants; species by national dex)
    local function names(kind, prefix, value)
      local out = json.object()
      local raw = C and b.ask(C, "raw", nil, C, kind) or nil
      local byName = type(raw) == "table" and raw.byName or nil
      for _, name in ipairs(sortedKeys(type(byName) == "table" and byName or {})) do
        local id = tonumber(byName[name])
        if b.symbol(name) and name:sub(1, #prefix) == prefix and id then
          local v = value(id)
          if v and v >= 1 and v <= 2000 then out[name] = floor(v) end
        end
      end
      return out
    end
    put("g3names", "all", {
      species = names("species", "SPECIES_", function(id) return b.ask(Pokemon, "national", nil, id) end),
      moves = names("moves", "MOVE_", function(id) return id end),
      items = names("items", "ITEM_", function(id) return id end),
    })
    return true
  end)
end

return Export3
