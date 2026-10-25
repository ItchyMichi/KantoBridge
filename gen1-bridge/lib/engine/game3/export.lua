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
--   g3sprite/<gfx>   the object-event sheets (RGBA strips); SURF_BLOB, the
--                    field effect under a surfing player (item 8)
--   g3anims/all      the animated quarters (item 8): per animated quarter its
--                    frames (quarter ids) and its loop (frame, GBA frames)
--   g3layout/<region> the surface maps' cell origins (connections, BFS from
--                    the root)
--   g3encounters/all, g3names/all
--   g3sites/all      SE2 (docs/specs/STORY_EVENTS_PLAN.md): every cell the
--                    story can change, its own state and the others
--                    (sites.lua); g3map has the every-visit ones baked in;
--                    a state whose metatile the map's pair lacks is left out
--                    (a script other maps share, e.g. Mossdeep's Game Corner
--                    reaching the Pokemon Centers' cable club doors)
--
-- Art: the engine prepares each pair's metatile atlases as RGBA on the CPU
-- (AssetDecode.pair, src/core/game3/asset_decode.lua:24-87: one 16x16 slot per
-- metatile and one atlas per plane: under, middle, over), so the export never
-- touches the GPU or the engine's resident textures. A quarter is flattened
-- the way the GBA draws a still frame: over, else middle, else under (GBA
-- pixels are opaque or clear); identical quarters are shared (MD5 of their
-- bytes). An animated quarter (item 8) is a quarter of its own, never shared
-- with a static one, whose page holds its first frame; its frames are quarters
-- too, and g3anims gives its loop.
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
-- The surf blob's sheet among the sprites (item 8): the field effect the engine draws under a surfing player.
Export3.SURF_BLOB = "SURF_BLOB"

-- Animated tiles (item 8): a quarter's loop is cut to its first counter's length past this many GBA frames.
Export3.MAX_CYCLE = 4096

local function gcd(a, b)
  while b ~= 0 do a, b = b, a % b end
  return a
end

-- The two counters' lengths in GBA frames before they wrap (src/core/game3/tileset_anim.lua:206-252: enterMap
-- sets them from the manifest, stepRse counts them; a secondary of "primary" length runs as long as the primary).
function Export3.counterMaxes(man)
  local c = type(man) == "table" and type(man.counters) == "table" and man.counters or {}
  local p = type(c.primary) == "table" and c.primary or {}
  local sc = type(c.secondary) == "table" and c.secondary or {}
  local pMax = tonumber(p.max) or 0
  local sMax = (sc.max == "primary") and pMax or (tonumber(sc.max) or 0)
  return pMax, sMax
end

-- A quarter's loop as segments of unchanged looks, as the engine's TilesetAnim.stepRse runs its banks (src/core/game3/
-- tileset_anim.lua:254-281): each GBA frame both counters count (wrapping at their lengths); a bank whose counter
-- meets its phase (timer % period == phase) works out its frame (frameAt, the engine's TilesetAnim.rseFrame, :114-127)
-- and pastes it when it changed (rse_apply, :190-206), so a layer shows the bank that pasted last. entries: the banks
-- that touch the quarter, in manifest order, { row, max (its counter's length), over (the layer) }. The loop is the
-- counters' least common multiple (at most MAX_CYCLE, else the first bank's), from both counters at 0, run twice so
-- what the wrap carries is in place. Returns { key, under = { entry, frame } or nil, over = ..., len } runs, or nil
-- when nothing changes.
function Export3.segments(entries, frameAt)
  local cycle = 1
  for _, e in ipairs(entries) do
    local m = tonumber(e.max) or 0
    if m > 0 then cycle = cycle / gcd(cycle, m) * m end
  end
  if cycle > Export3.MAX_CYCLE then cycle = math.max(1, tonumber(entries[1].max) or 0) end
  if cycle < 2 then return nil end
  local last, writer = {}, { under = nil, over = nil }
  local segs, prev = {}, nil
  for pass = 1, 2 do
    for t = 0, cycle - 1 do
      for i, e in ipairs(entries) do
        local row = e.row
        local period = tonumber(row.period) or 0
        local m = tonumber(e.max) or 0
        local timer = m > 0 and t % m or 0
        if period > 0 and timer % period == (tonumber(row.phase) or 0) then
          local ok, f = pcall(frameAt, row, timer)
          f = ok and tonumber(f) or nil
          if f and f ~= last[i] then
            last[i] = f
            writer[e.over and "over" or "under"] = { i, math.floor(f) }
          end
        end
      end
      if pass == 2 then
        local u, o = writer.under, writer.over
        local key = (u and (u[1] .. ":" .. u[2]) or "-") .. "|" .. (o and (o[1] .. ":" .. o[2]) or "-")
        if key == prev then
          segs[#segs].len = segs[#segs].len + 1
        else
          segs[#segs + 1] = { key = key, under = u, over = o, len = 1 }
          prev = key
        end
      end
    end
  end
  if #segs < 2 then return nil end
  return segs
end

-- A map that needs Flash: the header's cave flag (FieldView.defaultFlashLevel's mapIsCave,
-- src/core/game3/field_view.lua:1484-1487).
function Export3.dark(mdef)
  return type(mdef) == "table" and mdef.cave ~= nil and (tonumber(mdef.cave) or 0) ~= 0
end

-- The surf blob as three frames facing down, up and left (each fw x fh, a vertical strip of ImageData), or nil:
-- the field effects' manifest entry (src/core/game3/field_effects.lua:279-301, :333-338) and its raw RGBA
-- (:340-350, :352-371); each facing's frame is its animation's first (:1935-1941), else the six-frame order
-- (down 0, up 2, left 4, :1942-1951).
function Export3.surfBlob(cache, cacheRoot, FX, love)
  if type(cache) ~= "table" or type(cacheRoot) ~= "string" or type(love) ~= "table" then return nil end
  local root = cacheRoot .. "/field_effects/"
  local okRead, src = pcall(cache.read, cache, root .. "objects.lua")
  local chunk = okRead and type(src) == "string" and load(src, "@field_effects/objects.lua", "t", {}) or nil
  local okRun, man = pcall(chunk or function() return nil end)
  local o
  if okRun and type(man) == "table" then
    for _, list in ipairs({ man.objects or {}, man.extras or {} }) do
      for _, e in pairs(list) do
        if type(e) == "table" and e.name == "surf_blob" then o = e end
      end
    end
  end
  if type(FX) == "table" and type(FX.manifestObject) == "function" and not o then
    local okO, found = pcall(FX.manifestObject, "surf_blob")
    if okO and type(found) == "table" then o = found end
  end
  local fw, fh, n = tonumber(o and o.fw), tonumber(o and o.fh), tonumber(o and o.frames)
  if not (o and type(o.rgba) == "string" and fw and fh and n and n >= 1 and fw <= 64 and fh <= 64) then return nil end
  local okRgba, rgba = pcall(cache.read, cache, root .. o.rgba)
  if not (okRgba and type(rgba) == "string" and #rgba == fw * fh * n * 4) then return nil end
  local pick = {}
  for d = 1, 3 do
    local seq = type(o.anims) == "table" and o.anims[d] or nil
    local cmd = type(seq) == "table" and seq[1] or nil
    local f = type(cmd) == "table" and tonumber(cmd[2]) or nil
    if f == nil and n >= 6 then f = (d - 1) * 2 end
    pick[d] = (f and f >= 0 and f < n) and math.floor(f) or 0
  end
  local rows = {}
  local frameBytes = fw * fh * 4
  for d = 1, 3 do
    rows[d] = rgba:sub(pick[d] * frameBytes + 1, (pick[d] + 1) * frameBytes)
  end
  local ok, data = pcall(love.image.newImageData, fw, fh * 3, "rgba8", table.concat(rows))
  if not ok or not data then return nil end
  return { w = math.floor(fw), h = math.floor(fh), data = data }
end

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

  -- An object graphics id's name, as the observer names a puppet's sprite (observe.lua spriteOf): the engine's
  -- OBJ_EVENT_GFX_ constant, else OBJ_EVENT_GFX_<id>; the map objects, the sheets and the puppets share it (item 8).
  local function gfxName(C, gid)
    local name = C and b.ask(C, "name", nil, C, "event_objects", gid, "OBJ_EVENT_GFX_") or nil
    if type(name) == "string" and not name:find("^OBJ_EVENT_GFX_") then name = "OBJ_EVENT_GFX_" .. name end
    if b.symbol(name) then return name end
    return ("OBJ_EVENT_GFX_%d"):format(gid)
  end

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
          local gfx = gfxName(C, gid)
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
    -- SE2: each exported map's tileset pair, and each pair's exported metatiles (a site state the pair lacks is left
    -- out: it can't be drawn, and only a script shared with other maps would set it, never this map's own play)
    local mapPair, pairMids = {}, {}
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
            -- MG8: a Wild Routes clearing's cells (routes.lua) are baked in, open, as the hidden game runs them;
            -- SE2: so are the changes every visit's entry scripts make under no condition (sites.lua)
            local opened = type(b.routeCells) == "function" and b.routeCells(map) or {}
            local baked = type(b.bakedCells) == "function" and b.bakedCells(map) or {}
            for cy = 0, h - 1 do
              for cx = 0, w - 1 do
                local c = lay.cells[cy * w + cx + 1]
                local mid = type(c) == "table" and floor(tonumber(c.mid) or 0) or 0
                local coll = type(c) == "table" and floor(tonumber(c.coll) or 0xff) or 0xff
                local elev = type(c) == "table" and floor(tonumber(c.elev) or 0) or 0
                local o = opened[cy * 1024 + cx]
                local k = baked[cy * 1024 + cx]
                if o then
                  mid, coll, elev = floor(o.mid), 0x00, floor(o.elev)
                elseif k then
                  mid, coll, elev = k.mid, k.coll, k.elev
                end
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
              -- the engine keeps a warp's landing 1-based (destWarp = warp_id + 1, src/import/gba/extract_map_events.lua:313;
              -- Collision.resolveWarpDestination reads warps[destWarp]); the export's is 0-based
              local id = tonumber(wp.destWarp)
              id = id and id - 1 or tonumber(wp.warpId) or 0
              if id < 0 then id = 0 end
              if wx and wy and wx >= 0 and wy >= 0 and wx <= 1023 and wy <= 1023 then
                local e = { x = floor(wx), y = floor(wy), warp = floor(id) % 256 }
                if b.symbol(wp.destMap) then e.to = wp.destMap else e.dynamic = true end
                warps[#warps + 1] = e
              end
            end
            local kind = type(mdef.kind) == "string" and mdef.kind:find("^[a-z_]+$") and #mdef.kind <= 32
              and mdef.kind or "town"
            mapPair[map] = pair
            put("g3map", map, {
              id = map, w = w, h = h, trueW = tw, trueH = th, pair = pair, cells = base64(concat(parts)),
              border = { w = bw, h = bh, mids = json.array(border) },
              connections = json.array(conns), warps = json.array(warps), objects = json.array(objectsOf(map, mdef, C)),
              mapType = floor(tonumber(mdef.mapType) or 0) % 256, kind = kind,
              music = floor(tonumber(mdef.music) or 0) % 65536, weather = floor(tonumber(mdef.weather) or 0) % 256,
              -- item 8: a cave that needs Flash (the map header's cave flag, FieldView.defaultFlashLevel's
              -- mapIsCave, src/core/game3/field_view.lua:1484-1487); SE7: a map its entry scripts darken
              dark = (Export3.dark(mdef) or (type(b.scriptDarkMaps) == "function" and b.scriptDarkMaps()[map]))
                or nil,
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
    -- a quarter of its own (an animated quarter's still frame): never shared with a static quarter of the same pixels
    local function newQuarter(bytes)
      local id = nextId
      nextId = nextId + 1
      page[#page + 1] = bytes
      if #page >= Export3.PAGE then flush() end
      return id
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

    -- ---- item 8: a pair's animated metatiles, from the engine's animation manifest (src/core/game3/tileset_anim.lua:
    -- 50-64 read_manifest, :66-78 rse_luts, :80-101 rse_bank): per metatile and quarter the banks that paste into it
    -- (each bank's metatiles, quads masks, under or over layer), with the pair's colour tables. A palette bank (one pair
    -- in Emerald) is left out.
    local Anim, Pal = b.g3("tileset_anim"), b.g3("palette")
    local animKnown, anims, animCounts = {}, {}, { pairs = 0, quarters = 0, palette = 0 }
    local function pairAnimations(pair)
      if type(Anim) ~= "table" or type(Anim.rseFrame) ~= "function" or type(Pal) ~= "table" then return nil end
      local okR, src = pcall(cache.read, cache, nativeRoot .. "/" .. pair .. "/anim_manifest.lua")
      local chunk = okR and type(src) == "string" and load(src, "@anim_manifest.lua", "t", {}) or nil
      local okM, man = pcall(chunk or function() return nil end)
      if not (okM and type(man) == "table" and man.family == "rse" and type(man.banks) == "table") then return nil end
      local okP, pals = pcall(cache.read, cache, nativeRoot .. "/" .. pair .. "/palettes.bin")
      local okL, rgb = false, nil
      if okP and type(pals) == "string" then okL, rgb = pcall(Pal.load, pals) end
      if not (okL and type(rgb) == "table") then return nil end
      local under, over = {}, {}
      for v = 0, 255 do
        local pal = rgb[floor(v / 16) % 16] or rgb[0]
        local c = type(pal) == "table" and pal[v % 16] or nil
        c = type(c) == "table" and c or { 0, 0, 0 }
        under[v] = char(floor(c[1] or 0), floor(c[2] or 0), floor(c[3] or 0), 255)
        over[v] = v == 0 and char(0, 0, 0, 0) or under[v]
      end
      local pMax, sMax = Export3.counterMaxes(man)
      local byMid, blobs = {}, {}
      for _, row in ipairs(man.banks) do
        if row.kind == "palette" then
          animCounts.palette = animCounts.palette + 1
        elseif (tonumber(row.frames) or 0) >= 2 and (tonumber(row.period) or 0) >= 1 then
          local max = row.counter == "secondary" and sMax or pMax
          for layer = 1, 2 do
            local file = layer == 1 and row.file or row.overFile
            local list = layer == 1 and row.mids or row.overMids
            local quads = layer == 1 and row.quads or row.overQuads
            if type(file) == "string" and type(list) == "table" and #list > 0 then
              if blobs[file] == nil then
                local okB, bl = pcall(cache.read, cache, nativeRoot .. "/" .. pair .. "/" .. file)
                blobs[file] = okB and type(bl) == "string" and bl or false
              end
              local data = blobs[file]
              if data then
                for k, mid in ipairs(list) do
                  local mask = type(quads) == "table" and tonumber(quads[k]) or 15
                  for q = 0, 3 do
                    if floor(mask / 2 ^ q) % 2 == 1 then
                      byMid[mid] = byMid[mid] or {}
                      local entries = byMid[mid][q] or {}
                      byMid[mid][q] = entries
                      entries[#entries + 1] = { row = row, max = max, over = layer == 2, k = k, n = #list, blob = data }
                    end
                  end
                end
              end
            end
          end
        end
      end
      return { byMid = byMid, under = under, over = over }
    end

    -- Frame f of a bank's k-th metatile, quarter q, as 256 RGBA bytes through the pair's colours (rse_piece and
    -- rse_paste, src/core/game3/tileset_anim.lua:129-154, :156-187: 16x16 indexed pieces, frame after frame), or nil
    -- past the frames' end.
    local function piece(A, e, f, q)
      local base = (f * e.n + (e.k - 1)) * 256
      if #e.blob < base + 256 then return nil end
      local lut = e.over and A.over or A.under
      local ox, oy = (q % 2) * 8, floor(q / 2) * 8
      local px = {}
      for r = 0, 7 do
        local at = base + (oy + r) * 16 + ox
        for c = 1, 8 do px[#px + 1] = lut[byte(e.blob, at + c)] end
      end
      return concat(px)
    end

    -- An animated quarter: its frames flattened as the still one is, a quarter of its own whose page holds its first
    -- frame, and its loop for g3anims; or nil when its frames never differ. Quarters touched by the same banks share
    -- their loop's runs (segsOf).
    local segsOf = {}
    local function animatedQuarter(A, entries, q, u, m, o)
      local sig = {}
      for i, e in ipairs(entries) do sig[i] = tostring(e.row) .. (e.over and "o" or "u") .. e.max end
      sig = concat(sig, ",")
      local segs = segsOf[sig]
      if segs == nil then
        segs = Export3.segments(entries, Anim.rseFrame) or false
        segsOf[sig] = segs
      end
      if not segs then return nil end
      local frameOf, flats, seq = {}, {}, {}
      for _, sg in ipairs(segs) do
        local fi = frameOf[sg.key]
        if not fi then
          local uu, oo = u, o
          if sg.under then uu = piece(A, entries[sg.under[1]], sg.under[2], q) or u end
          if sg.over then oo = piece(A, entries[sg.over[1]], sg.over[2], q) or o end
          fi = #flats + 1
          flats[fi] = flatten(uu, m, oo)
          frameOf[sg.key] = fi
        end
        seq[#seq + 1] = { fi - 1, sg.len }
      end
      -- each distinct picture once (two looks may draw the same pixels), the runs merged where they now meet; every
      -- loop starts at the counters' 0, so quarters stay in step
      local ids, slotOf, remap = {}, {}, {}
      for i, flat in ipairs(flats) do
        local qid = idOf(flat)
        if not slotOf[qid] then
          ids[#ids + 1] = qid
          slotOf[qid] = #ids - 1
        end
        remap[i - 1] = slotOf[qid]
      end
      if #ids < 2 then return nil end
      local runs = {}
      for _, sq in ipairs(seq) do
        local fi = remap[sq[1]]
        if #runs > 0 and runs[#runs][1] == fi then
          runs[#runs][2] = runs[#runs][2] + sq[2]
        else
          runs[#runs + 1] = { fi, sq[2] }
        end
      end
      local parts = {}
      for i, r in ipairs(runs) do parts[i] = r[1] .. ":" .. r[2] end
      local key = concat(ids, ",") .. "|" .. concat(parts, ",")
      local id = animKnown[key]
      if not id then
        id = newQuarter(flats[1])
        animKnown[key] = id
        local s2 = {}
        for i, r in ipairs(runs) do s2[i] = json.array({ r[1], r[2] }) end
        anims[#anims + 1] = { q = id, f = json.array(ids), s = json.array(s2) }
      end
      animCounts.quarters = animCounts.quarters + 1
      return id
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
          local A = pairAnimations(pair)
          if A then animCounts.pairs = animCounts.pairs + 1 end
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
                local entries = A and A.byMid[mid] and A.byMid[mid][q] or nil
                local animated = entries and animatedQuarter(A, entries, q, u, mOpaque and m or nil, o) or nil
                if animated then
                  qs[q + 1] = animated
                else
                  local flat = (mOpaque or oOpaque) and flatten(u, mOpaque and m or nil, oOpaque and o or nil) or u
                  qs[q + 1] = idOf(flat)
                end
              end
              -- the engine's canonical id (Emerald-only ones from 0x100, mb.lua); the export keeps its low byte, one to one
              -- inside a game, but names it and judges its land rule by the full id (FA8 item 11: by the byte, Emerald's
              -- own behaviours took FireRed's names and long grass, ash and deep sand fell out of the land rule)
              local raw = floor(tonumber(beh[mid]) or 0)
              local bv = raw % 256
              midList[#midList + 1], quarters[#quarters + 1] = mid, qs
              behList[#behList + 1], overList[#overList + 1] = bv, anyOver
              if Rules and not landSeen[bv] then
                landSeen[bv] = true
                if land(raw) then landList[#landList + 1] = bv end
              end
              if names[tostring(bv)] == nil then
                local name = b.ask(MB, "nameOf", nil, raw)
                if b.symbol(name) then names[tostring(bv)] = name end
              end
              if #midList % 32 == 0 then checkpoint() end
            end
          end
          sort(landList)
          local known = {}
          for _, mid in ipairs(midList) do known[mid] = true end
          pairMids[pair] = known
          put("g3tiles", pair, { id = pair, mids = json.array(midList), quarters = json.array(quarters),
                                 beh = json.array(behList), over = json.array(overList), behaviors = names,
                                 land = Rules and json.array(landList) or nil })
        end
      end
      out = nil
      checkpoint()
    end
    flush()
    -- item 8: the animated quarters' frames (quarter ids) and loops (frame, GBA frames)
    put("g3anims", "all", { id = "all", anims = json.array(anims) })
    if b.log then
      b.log.info("export.g3_anims", { pairs = animCounts.pairs, animated = #anims, quarters = animCounts.quarters,
                                      palette = animCounts.palette })
    end

    -- ---- the surf blob (item 8): a field effect's sheet, not an object event's (src/core/game3/field_effects.lua:
    -- 352-385 load_sheet, :1923-1951 the facing's frame), as a sprite of three frames facing down, up and left
    local blob = Export3.surfBlob(cache, cacheRoot, b.g3("field_effects"), love)
    if blob then
      put("g3sprite", Export3.SURF_BLOB, { id = Export3.SURF_BLOB, w = blob.w, h = blob.h, frames = 3, png = png(blob.data) })
      if blob.data.release then blob.data:release() end
    else
      warn("g3_surf_blob_missing")
    end

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
        local name = gfxName(C, gid)
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
    -- MG8: the room a new game starts in (map_ids.lua:33-36, the profile's newGameStart: the moving truck on RSE), so
    -- the builder places it though no warp leads in
    local layout = { root = root, unit = "cells", maps = placed }
    local MapIds = b.g3("map_ids")
    if type(MapIds) == "table" and type(MapIds.newGameStart) == "function" then
      local okStart, st = pcall(MapIds.newGameStart, b.adapter.gameVersion())
      if okStart and type(st) == "table" and type(st.map) == "string" and maps[st.map] and tonumber(st.x) and tonumber(st.y) then
        layout.start = { map = st.map, x = floor(tonumber(st.x)), y = floor(tonumber(st.y)) }
      end
    end
    put("g3layout", x.region or "hoenn", layout)

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

    -- ---- SE2: the cells the story can change (sites.lua), every state with its source
    local okSites, found = pcall(b.adapter.cellSites, checkpoint)
    if okSites and type(found) == "table" then
      local list, foreign = {}, 0
      for _, site in ipairs(found.sites) do
        local known = pairMids[mapPair[site.map] or ""]
        local states = {}
        for _, st in ipairs(site.states) do
          if known and not known[st.metatile % 1024] then
            foreign = foreign + 1
          else
            states[#states + 1] = { metatile = st.metatile % 1024, coll = st.coll % 256, elev = st.elev % 16,
                                    source = st.source }
          end
        end
        if #states > 0 then
          list[#list + 1] = { map = site.map, x = site.x, y = site.y,
                              own = { metatile = site.own.metatile % 1024, coll = site.own.coll % 256,
                                      elev = site.own.elev % 16 },
                              states = json.array(states) }
        end
      end
      put("g3sites", "all", { sites = json.array(list), baked = found.baked })
      if b.log then
        local c = found.counts or {}
        b.log.info("export.g3sites", { sites = #list, states = c.states, baked = c.baked, maps = c.maps,
                                       dropped = c.dropped, offMap = c.offMap, layoutsMissing = c.layoutsMissing,
                                       layoutsMismatched = c.layoutsMismatched, truncated = c.truncated,
                                       foreign = foreign })
      end
      if (found.counts and found.counts.dropped or 0) > 0 then warn("g3_site_states_dropped", "all") end
      if (found.counts and found.counts.truncated or 0) > 0 then warn("g3_site_walk_truncated", "all") end
    else
      warn("g3_sites_failed", "all")
      put("g3sites", "all", { sites = json.array({}), baked = 0 })
    end
    return true
  end)
end

return Export3
