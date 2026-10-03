-- kb_export: the world export service for the world builder (docs/PROTOCOL.md
-- "Export (builder role)" and "Export resources"; docs/PLAN.md section 4.1
-- "Export").
--
--   export.index {}           -> export.index {game, engine, bridge, resources[{kind,id,hash,size}]}
--   export.get {kind, id}     -> export.data {kind, id, hash, data}, or error not_found
--   roles builder and test only (error role otherwise)
--
-- Lazy and budgeted. The first export request starts one build of every
-- resource in a coroutine that yields whenever this step's budget (a few ms)
-- is spent, so the game keeps its frame rate; requests wait in a queue until
-- the build is done. Each resource is kept as its canonical JSON (kb_json:
-- sorted keys, no spaces, UTF-8 as is): `hash` is the SHA-256 of that text
-- and `size` its length, so a bundle file written byte for byte has the
-- index's hash. Replies are built around the cached text (no re-encoding)
-- and handed to the non-blocking send queue a byte budget at a time, pausing
-- while the client is slow to read. The cache lives as long as this
-- instance: the data is the game's pristine import and doesn't change.
--
-- Data comes from kb_engine's export sources (mod.datasets first, PLAN
-- section 8's live-Data copy as the fallback); engine questions go through
-- kb_engine only. Whatever can't be exported correctly is left out and
-- counted under a warning code; the codes are logged with their counts when
-- the build ends. Logs carry codes, kinds, symbols, counts and times only.

local Export = {}

Export.KINDS = { map = true, tileset = true, image = true, sprite = true, palettes = true,
                 encounters = true, field = true, names = true, layout = true, swaps = true }
Export.ROLES = { builder = true, test = true }
Export.ROOT = "PALLET_TOWN"              -- layout root (PLAN section 2.2)
Export.BUDGET = 0.006                    -- seconds of build work per logic step
Export.SERVE_BYTES = 256 * 1024          -- reply bytes queued per step
Export.BACKLOG = 1024 * 1024             -- no new replies while this much is unsent
Export.LINE_SLACK = 512                  -- envelope bytes around a page
Export.SLOW_STEP_MS = 33                 -- a build slice longer than two frames counts as slow
Export.STATES = { idle = 0, building = 1, ready = 2, failed = 3 }

-- The field tables the builder and later milestones read, copied as JSON
-- (Lua tables with keys other than 1..n become objects with string keys).
Export.FIELD_TABLES = {
  "badgeGates", "bikeRiding", "bookshelves", "cardKeyDoors", "cutTreeSwaps", "darkMaps",
  "dungeonTransitionMaps", "emotionBubbles", "fishing", "flyOrder", "flyWarps", "forcedMovement",
  "gameCornerPoster", "hiddenItems", "indoorEncounters", "lastMapRewrites", "ledges",
  "mapChangeSound", "outsideTilesets", "overworldFx", "palettes", "playerSprites", "safari",
  "seafoam", "spinners", "superRod", "tilePairs", "warpCarpets", "waterTilesets",
}
-- field tables the build itself reads, beyond FIELD_TABLES
local FIELD_EXTRA = { "hiddenExtras" }

-- registries read through the source, in load order
local REGISTRIES = { "maps", "tilesets", "sprites", "palettes", "encounters", "pokemon", "moves", "items" }

local MAP_ID = "^[A-Z0-9_]+$"
local SYMBOL = "^[A-Za-z0-9_]+$"
local ID = "^[A-Za-z0-9_.:-]+$"
local DIRS = { "north", "south", "west", "east" }

local floor, max = math.floor, math.max
local format = string.format
local sort, concat = table.sort, table.concat

local function sym(v, pattern)
  return type(v) == "string" and #v > 0 and #v <= 64 and v:find(pattern or SYMBOL) ~= nil
end

local function int(v, lo, hi)
  return type(v) == "number" and v == floor(v) and v >= lo and v <= hi
end

local function where(err)
  return type(err) == "string" and err:match("[%w_/%.%-]+%.lua:%d+") or "unknown"
end

-- Sorted unique bytes of a list (tile ids).
local function bytes(list)
  local seen, out = {}, {}
  for _, v in ipairs(type(list) == "table" and list or {}) do
    if int(v, 0, 255) and not seen[v] then
      seen[v] = true
      out[#out + 1] = v
    end
  end
  sort(out)
  return out
end

local function copyList(list)
  local out = {}
  for i = 1, #list do out[i] = list[i] end
  return out
end

-- an object name (defs.schema.json objName)
local function objName(v)
  return type(v) == "string" and #v > 0 and #v <= 96 and v:find(SYMBOL) ~= nil
end

-- a display name: valid UTF-8, at most 200 code points (names.schema.json)
local function displayName(json, v)
  if type(v) ~= "string" or not json.utf8Valid(v) then return false end
  local _, continuation = v:gsub("[\128-\191]", "")
  return #v - continuation <= 200
end

-- opts:
--   json, sha256, log, clock         kb_json, kb_sha256, a kb_log logger, seconds
--   engine                           kb_engine instance (export sources and queries)
--   Cells, Images, SwapSites         the export helper modules
--   catalog                          kb_catalog (exportPageBytes)
--   bridge                           gen1-bridge's version
--   budget, serveBytes, backlog      optional limits (tests)
function Export.new(opts)
  local json, sha256, log, clock = opts.json, opts.sha256, opts.log, opts.clock
  local engine, Cells, Images, SwapSites = opts.engine, opts.Cells, opts.Images, opts.SwapSites
  local pageLimit = (opts.catalog and opts.catalog.exportPageBytes) or 1048576
  local budget = opts.budget or Export.BUDGET
  local serveBytes = opts.serveBytes or Export.SERVE_BYTES
  local backlog = opts.backlog or Export.BACKLOG

  local ex = {}
  local session
  local state = "idle"
  local co, deadline = nil, 0
  local queue, qhead, qtail = {}, 1, 0 -- a FIFO; # is unreliable once its head is cleared
  local resources, index, indexText = {}, {}, nil
  local warnings, warnOrder = {}, {}
  local bootLive          -- the live-Data copy taken at mods.loaded, when datasets failed
  local bootReason        -- why the datasets path failed at boot
  local worldSeen = false -- a map has loaded in this instance (the live Data may have changed)
  local stats = { path = "none", buildMs = 0, cpuMs = 0, maxStepMs = 0, steps = 0, bytes = 0,
                  largest = 0, served = 0, counts = {}, phaseMs = {}, slowSteps = 0 }
  local startedAt = 0
  local phase = "start" -- the build phase the next slice of time is counted under

  -- A new build phase: CPU time is counted under it, and progress is logged
  -- as counts only.
  local function enter(name)
    phase = name
    if log then log.info("export.progress", { phase = name, resources = #index }) end
  end

  local function warn(code, example)
    local w = warnings[code]
    if not w then
      w = { count = 0 }
      warnings[code] = w
      warnOrder[#warnOrder + 1] = code
    end
    w.count = w.count + 1
    if w.first == nil and sym(example) then w.first = example end
  end

  local function checkpoint()
    if clock() >= deadline then coroutine.yield() end
  end

  -- ------------------------------------------------------------ the cache

  local function key(kind, id) return kind .. "\0" .. id end

  local function put(kind, id, data)
    if not sym(id, ID) then warn("bad_id", kind) return false end
    local text, err = json.encode(data)
    if not text then
      warn("encode_failed", id)
      if log then log.debug("export.encode_failed", { kind = kind, id = id, err = err and #err or 0 }) end
      return false
    end
    if #text + Export.LINE_SLACK > pageLimit then warn("too_large", id) return false end
    checkpoint()
    local hash = sha256.hex(text)
    resources[key(kind, id)] = { kind = kind, id = id, text = text, hash = hash, size = #text }
    index[#index + 1] = { kind = kind, id = id, hash = hash, size = #text }
    stats.bytes = stats.bytes + #text
    if #text > stats.largest then stats.largest = #text end
    stats.counts[kind] = (stats.counts[kind] or 0) + 1
    checkpoint()
    return true
  end

  -- ------------------------------------------------------------ JSON shaping

  -- A field table as JSON: arrays stay arrays, other tables become objects
  -- with string keys (integers as decimal text). Values JSON can't carry the
  -- same way in Lua and Python (fractions, NaN, functions, invalid UTF-8)
  -- are left out and counted.
  local function normalize(v, depth)
    local t = type(v)
    if t == "boolean" then return v end
    if t == "number" then
      if v ~= v or v == math.huge or v == -math.huge or v ~= floor(v) or v > 9007199254740991
          or v < -9007199254740991 then
        warn("field_number")
        return nil
      end
      return v
    end
    if t == "string" then
      if json.utf8Valid(v) then return v end
      warn("field_string")
      return nil
    end
    if t ~= "table" or depth > 32 then warn("field_value") return nil end
    local count, n, allInt = 0, 0, true
    for k in pairs(v) do
      count = count + 1
      if type(k) == "number" and k >= 1 and k == floor(k) then
        if k > n then n = k end
      else
        allInt = false
      end
    end
    if count == 0 then return json.array() end
    if allInt and n == count then
      local out = json.array()
      for i = 1, n do
        local item = normalize(v[i], depth + 1)
        out[i] = item == nil and json.null or item
      end
      return out
    end
    local out = json.object()
    for k, item in pairs(v) do
      local name
      if type(k) == "string" then
        name = json.utf8Valid(k) and k or nil
      elseif type(k) == "number" and k == floor(k) and k > -1e15 and k < 1e15 then
        name = format("%d", k)
      end
      if name == nil then
        warn("field_key")
      else
        local value = normalize(item, depth + 1)
        if value ~= nil then out[name] = value end
      end
    end
    return out
  end

  -- ------------------------------------------------------------ the build

  local function pickSource()
    if bootLive then return bootLive end
    local view, reason = engine.openDataset(engine.gameVersion())
    if view then return engine.datasetSource(view) end
    return nil, reason
  end

  local function liveLate(reason)
    -- the live Data is safe to copy only before any map has loaded
    if worldSeen or engine.worldShowing() then
      if log then log.error("export.no_pristine_data", { reason = reason }) end
      return nil
    end
    local src = engine.liveSourceFromGame(checkpoint)
    if src and log then log.info("export.live_copy", { reason = reason, when = "export" }) end
    return src
  end

  -- Loads every registry the export reads; nil plus a reason when the
  -- source can't give the essentials.
  local function load(src)
    local d = { records = {}, field = {} }
    for _, name in ipairs(REGISTRIES) do
      enter("records." .. name)
      local list, why = src.records(name, checkpoint)
      if not list then return nil, name .. ":" .. tostring(why) end
      d.records[name] = list
    end
    if #d.records.maps == 0 or #d.records.tilesets == 0 then return nil, "empty" end
    enter("records.field")
    for _, k in ipairs(Export.FIELD_TABLES) do d.field[k] = src.value("field", k) end
    for _, k in ipairs(FIELD_EXTRA) do d.field[k] = src.value("field", k) end
    d.buckets = src.value("constants", "encounterBuckets")
    checkpoint()
    return d
  end

  -- BFS from the surface over warps, a layer at a time in map-index order
  -- (then id), each map's warps in order: the first warp that leads into an
  -- interior is its parent.
  local function parents(ids, defs, surface)
    local parent, visited, layer = {}, {}, {}
    local function byIndex(a, b)
      local ia = type(defs[a].index) == "number" and defs[a].index or 1e9
      local ib = type(defs[b].index) == "number" and defs[b].index or 1e9
      if ia ~= ib then return ia < ib end
      return a < b
    end
    for _, id in ipairs(ids) do
      if surface[id] then visited[id] = true; layer[#layer + 1] = id end
    end
    sort(layer, byIndex)
    while #layer > 0 do
      local nextLayer = {}
      for _, id in ipairs(layer) do
        for i, w in ipairs(type(defs[id].warps) == "table" and defs[id].warps or {}) do
          local dest = type(w) == "table" and w.destMap or nil
          if type(dest) == "string" and dest ~= "LAST_MAP" and defs[dest] and not visited[dest]
              and int(w.x, -4096, 4096) and int(w.y, -4096, 4096) and int(i, 1, 255) then
            visited[dest] = true
            parent[dest] = { map = id, warp = i, x = w.x, y = w.y }
            nextLayer[#nextLayer + 1] = dest
          end
        end
      end
      sort(nextLayer, byIndex)
      layer = nextLayer
    end
    return parent
  end

  -- What an interior's LAST_MAP memory can hold (the static rule of
  -- refs P3 section 6.2): leaving an outside map by any warp remembers that
  -- map (OverworldState:startWarpTo, src/world/OverworldController.lua:
  -- 5411-5422), interiors carry the memory on, and field.lastMapRewrites
  -- rewrite it on the maps they name (syncLastMapRewrite :4893-4904; rules in
  -- order, first match wins, rewrittenLastMap :4882-4891). A fixpoint over
  -- every warp; one possible map is the interior's lastMap, more than one
  -- means it depends on the position or the way in (null).
  local function memories(ids, defs, outside, rewrites)
    local mem = {}
    local function through(dest, incoming)
      local rw = type(rewrites) == "table" and rewrites[dest] or nil
      if type(rw) ~= "table" then return incoming end
      local out = {}
      for _, rule in ipairs(type(rw.rules) == "table" and rw.rules or {}) do
        if type(rule) == "table" then
          if type(rule.map) == "string" then out[rule.map] = true end
          if rule.below == nil and rule.atLeast == nil then return out end
        end
      end
      for k in pairs(incoming) do out[k] = true end
      return out
    end
    local changed = true
    while changed do
      changed = false
      for _, src in ipairs(ids) do
        local incoming = outside[src] and { [src] = true } or mem[src]
        if incoming then
          for _, w in ipairs(type(defs[src].warps) == "table" and defs[src].warps or {}) do
            local dest = type(w) == "table" and w.destMap or nil
            if type(dest) == "string" and dest ~= "LAST_MAP" and defs[dest] and not outside[dest] then
              local target = mem[dest] or {}
              mem[dest] = target
              for m in pairs(through(dest, incoming)) do
                if not target[m] then target[m] = true; changed = true end
              end
            end
          end
        end
      end
    end
    return mem
  end

  local function single(set)
    local only, n = nil, 0
    for k in pairs(set or {}) do only, n = k, n + 1 end
    if n == 1 then return only end
    return nil
  end

  local function sortedKeys(set)
    local out = {}
    for k in pairs(set or {}) do out[#out + 1] = k end
    sort(out)
    return out
  end

  -- The surface layout: the engine's neighbour BFS from the root.
  local function layout(defs)
    local maps = json.object()
    local surface = {}
    local root = defs[Export.ROOT]
    if not root then
      warn("no_root")
      return { root = Export.ROOT, maps = maps }, surface
    end
    local placed, why = engine.computeNeighbors(defs, Export.ROOT)
    if not placed then warn("layout_failed", why) placed = {} end
    surface[Export.ROOT] = true
    maps[Export.ROOT] = { x = 0, y = 0, w = root.width, h = root.height }
    for _, p in ipairs(placed) do
      local def = defs[p.id]
      if def and int(p.ox, -131072, 131072) and int(p.oy, -131072, 131072) and p.ox % 32 == 0 and p.oy % 32 == 0
          and int(p.ox / 32, -4096, 4096) and int(p.oy / 32, -4096, 4096) then
        surface[p.id] = true
        maps[p.id] = { x = p.ox / 32, y = p.oy / 32, w = def.width, h = def.height }
      else
        warn("layout_bad_origin", p.id)
      end
    end
    -- sanity: every connection between surface maps agrees, nothing overlaps
    -- (the builder asserts the same, PLAN section 4.5)
    local ids = sortedKeys(surface)
    for _, id in ipairs(ids) do
      local a = maps[id]
      for _, dir in ipairs(DIRS) do
        local c = type(defs[id].connections) == "table" and defs[id].connections[dir] or nil
        local b = type(c) == "table" and maps[c.map] or nil
        if b and type(c.offset) == "number" then
          local ex2, ey2
          if dir == "north" then ex2, ey2 = a.x + c.offset, a.y - b.h
          elseif dir == "south" then ex2, ey2 = a.x + c.offset, a.y + a.h
          elseif dir == "west" then ex2, ey2 = a.x - b.w, a.y + c.offset
          else ex2, ey2 = a.x + a.w, a.y + c.offset end
          if ex2 ~= b.x or ey2 ~= b.y then warn("layout_edge_mismatch", id) end
        end
      end
    end
    for i = 1, #ids do
      local a = maps[ids[i]]
      for j = i + 1, #ids do
        local b = maps[ids[j]]
        if a.x < b.x + b.w and b.x < a.x + a.w and a.y < b.y + b.h and b.y < a.y + a.h then
          warn("layout_overlap", ids[i])
        end
      end
    end
    return { root = Export.ROOT, maps = maps }, surface
  end

  local function tileClasses(tid, ts, surfable)
    local probe = engine.tileProbe(tid, ts)
    if not probe then warn("tile_probe_failed", tid) probe = { water = {}, pads = {} } end
    local water, shore = {}, {}
    if surfable then
      local shoreSet = Cells.set(engine.shoreTiles(tid, ts))
      for _, t in ipairs(probe.water) do
        water[#water + 1] = t
        if shoreSet[t] then shore[#shore + 1] = t end
      end
    end
    local pads = {}
    for _, p in ipairs(probe.pads) do
      if #pads < 16 then pads[#pads + 1] = p else warn("tileset_pads", tid) end
    end
    return water, shore, pads
  end

  local function build()
    local t0 = clock()
    enter("records")
    local src, reason = pickSource()
    local d, why
    if src then d, why = load(src) end
    if not d then
      -- PLAN section 8: the dataset view failed on this build
      local failure = reason or why
      src = (not bootLive) and liveLate(failure) or nil
      if src then d, why = load(src) end
      if not d then
        stats.path = "none"
        error("export.no_data:" .. tostring(failure or why), 0)
      end
    end
    stats.path = src.path
    if log then
      log.info("export.source", { path = src.path, maps = #d.records.maps, ms = floor((clock() - t0) * 1000),
                                  reason = src == bootLive and bootReason or nil })
    end

    local field = d.field
    local defs, ids, tilesets = {}, {}, {}
    for _, r in ipairs(d.records.maps) do
      if type(r.value) == "table" then defs[r.id] = r.value; ids[#ids + 1] = r.id end
    end
    for _, r in ipairs(d.records.tilesets) do
      if type(r.value) == "table" then tilesets[r.id] = r.value end
    end
    local dexOf = {}
    for _, r in ipairs(d.records.pokemon) do
      if type(r.value) == "table" and int(r.value.dex, 1, 151) then dexOf[r.id] = r.value.dex end
    end
    local function mon(species, level)
      local dex = dexOf[species]
      if sym(species) and dex and int(level, 1, 100) then return { species = species, dex = dex, level = level } end
      return nil
    end

    -- ---------------------------------------------- whole-world facts
    enter("world")
    local outsideTilesets = field.outsideTilesets
    local outside = {}
    for _, id in ipairs(ids) do
      local o = engine.isOutside(defs[id], outsideTilesets)
      if o == nil then warn("outside_unknown", id) end
      outside[id] = o == true
    end
    local layoutData, surface = layout(defs)
    for id in pairs(surface) do
      if not outside[id] then warn("surface_not_outside", id) end
    end
    local parent = parents(ids, defs, surface)
    local mem = memories(ids, defs, outside, field.lastMapRewrites)
    local dark = {}
    local darkMaps = type(field.darkMaps) == "table" and field.darkMaps.maps or nil
    for _, id in ipairs(type(darkMaps) == "table" and darkMaps or {}) do dark[id] = true end
    local palettes = field.palettes
    local paletteDefault = type(palettes) == "table" and palettes.default or nil
    local function paletteOf(id)
      local def = defs[id]
      local name = engine.paletteLookup(palettes, id, def.tileset, def.palette)
      if name then return name end
      local seen = {}
      for _, m in ipairs(sortedKeys(mem[id])) do
        local lastDef = defs[m]
        seen[#seen + 1] = engine.paletteLookup(palettes, m, lastDef and lastDef.tileset) or paletteDefault
      end
      for i = 2, #seen do
        if seen[i] ~= seen[1] then warn("palette_ambiguous", id) break end
      end
      return seen[1] or paletteDefault
    end
    local surfable = Cells.set(field.waterTilesets)
    local cutBefore, swapAfter = {}, {}
    for _, row in ipairs(type(field.cutTreeSwaps) == "table" and field.cutTreeSwaps or {}) do
      if type(row) == "table" and int(row.before, 0, 255) and int(row.after, 0, 255) and swapAfter[row.before] == nil then
        cutBefore[row.before] = true
        swapAfter[row.before] = row.after
      end
    end
    local spinners = type(field.spinners) == "table" and field.spinners or {}
    checkpoint()

    -- ---------------------------------------------- images
    local imagePaths, imageOrder, imageIds = {}, {}, {}
    local function addImage(path, owner)
      if type(path) ~= "string" then return nil end
      local id = Images.idFor(path)
      if not id then warn("image_bad_path", owner) return nil end
      if imageIds[id] and imageIds[id] ~= path then warn("image_id_clash", owner) return nil end
      if not imagePaths[path] then
        imagePaths[path] = id
        imageIds[id] = path
        imageOrder[#imageOrder + 1] = path
      end
      return id
    end
    local imageDims, imageTried = {}, {}
    local function loadImage(path)
      if imageTried[path] then return end
      imageTried[path] = true
      local id = imagePaths[path]
      local png, w, h = engine.imagePng(path)
      if not png then warn("image_" .. tostring(w), id) return end
      local res, code = Images.image(id, png, w, h)
      if not res then warn(code, id) return end
      if put("image", id, res) then imageDims[path] = { w = w, h = h } end
    end

    -- ---------------------------------------------- tilesets
    enter("tilesets")
    local tilesetIds = {}
    for tid in pairs(tilesets) do tilesetIds[#tilesetIds + 1] = tid end
    sort(tilesetIds)
    for _, tid in ipairs(tilesetIds) do
      local ts = tilesets[tid]
      local imageId = addImage(ts.image, tid)
      for _, path in ipairs(engine.animationImages(ts)) do addImage(path, tid) end
      if imageId then loadImage(ts.image) end
      local dims = imageDims[ts.image] or {}
      local iw = int(ts.imageWidth, 8, 8192) and ts.imageWidth or dims.w
      local ih = int(ts.imageHeight, 8, 8192) and ts.imageHeight or dims.h
      local perRow = ts.tilesPerRow
      if not int(perRow, 1, 64) and iw then perRow = floor(iw / 8) end
      local tiles = (iw and ih) and floor(iw / 8) * floor(ih / 8) or nil
      local blocks, okBlocks = {}, type(ts.blocks) == "table" and #ts.blocks <= 256
      for i, row in ipairs(okBlocks and ts.blocks or {}) do
        if type(row) ~= "table" or #row ~= 16 then okBlocks = false break end
        local copy = {}
        for j = 1, 16 do
          if not int(row[j], 0, 255) then okBlocks = false break end
          copy[j] = row[j]
        end
        if not okBlocks then break end
        blocks[i] = copy
      end
      if not (sym(tid) and imageId and okBlocks and int(perRow, 1, 64) and int(tiles, 1, 1024)) then
        warn("tileset_bad", tid)
      else
        local water, shore, pads = tileClasses(tid, ts, surfable[tid])
        local grass = int(ts.grassTile, 0, 255) and ts.grassTile or json.null
        put("tileset", tid, {
          id = tid, image = imageId, tilesPerRow = perRow, tiles = tiles, blocks = json.array(blocks),
          animation = sym(ts.animation) and ts.animation or "TILEANIM_NONE",
          walkable = json.array(bytes(ts.walkable)), counter = json.array(bytes(ts.counterTiles)),
          grass = grass, door = json.array(bytes(ts.doorTiles)), warp = json.array(bytes(ts.warpTiles)),
          water = json.array(water), shore = json.array(shore),
          cutTree = json.array(bytes(engine.cutTreeTiles(tid))),
          spinner = json.array(bytes(engine.spinnerTiles(tid))), pads = json.array(pads),
        })
      end
      checkpoint()
    end

    -- ---------------------------------------------- maps
    enter("maps")
    local swapRows = {}
    local function isCut(flags) return Cells.has(flags, Cells.CUT) end
    local function point(p) return type(p) == "table" and int(p.x, -4096, 4096) and int(p.y, -4096, 4096) end
    for _, id in ipairs(ids) do
      local def = defs[id]
      local ts = tilesets[def.tileset]
      local w, h = def.width, def.height
      local okSize = int(w, 1, 255) and int(h, 1, 255)
      local blocks = def.blocks
      local okBlocks = okSize and type(blocks) == "table" and #blocks == w * h
      if okBlocks and ts then
        for i = 1, #blocks do
          local b = blocks[i]
          local row = int(b, 0, 255) and type(ts.blocks) == "table" and ts.blocks[b + 1] or nil
          if type(row) ~= "table" or #row ~= 16 then okBlocks = false break end
        end
      end
      if not sym(id, MAP_ID) then
        warn("map_bad_id")
      elseif not int(def.index, 0, 255) then
        warn("map_no_index", id)
      elseif not (ts and sym(def.tileset)) then
        warn("map_no_tileset", id)
      elseif not okBlocks then
        warn("map_bad_blocks", id)
      else
        local q, qwhy = engine.mapQuery(def, ts)
        if not q then
          warn("map_query_" .. tostring(qwhy), id)
        else
          local connections = json.object()
          for _, dir in ipairs(DIRS) do
            local c = type(def.connections) == "table" and def.connections[dir] or nil
            if c ~= nil then
              if type(c) == "table" and sym(c.map, MAP_ID) and int(c.offset, -255, 255) then
                connections[dir] = { map = c.map, offset = c.offset }
              else
                warn("map_bad_connection", id)
              end
            end
          end
          local warps = {}
          for _, wp in ipairs(type(def.warps) == "table" and def.warps or {}) do
            if point(wp) and sym(wp.destMap, MAP_ID) and int(wp.destWarp, 1, 255) and #warps < 64 then
              warps[#warps + 1] = { x = wp.x, y = wp.y, to = wp.destMap, warp = wp.destWarp }
            else
              warn("map_bad_warp", id)
            end
          end
          local signs = {}
          for _, s in ipairs(type(def.signs) == "table" and def.signs or {}) do
            if point(s) and #signs < 64 then signs[#signs + 1] = { x = s.x, y = s.y } else warn("map_bad_sign", id) end
          end
          local objects = {}
          for _, o in ipairs(type(def.objects) == "table" and def.objects or {}) do
            if type(o) == "table" and int(o.index, 1, 255) and objName(o.name)
                and sym(o.sprite, MAP_ID) and point(o) and (o.movement == "WALK" or o.movement == "STAY")
                and sym(o.range) and #objects < 64 then
              local obj = { index = o.index, name = o.name, sprite = o.sprite, x = o.x, y = o.y,
                            movement = o.movement, range = o.range, hidden = o.hidden == true }
              if o.item ~= nil and o.item ~= "0" then
                if sym(o.item, MAP_ID) then obj.item = o.item else warn("object_bad_item", id) end
              end
              if o.trainerClass ~= nil then
                if sym(o.trainerClass) and int(o.trainerParty, 1, 255) then
                  obj.trainer = { cls = o.trainerClass, party = o.trainerParty }
                else
                  warn("object_bad_trainer", id)
                end
              end
              if o.pokemon ~= nil then
                local p = mon(o.pokemon, o.level)
                if p then obj.pokemon = p else warn("object_bad_pokemon", id) end
              end
              objects[#objects + 1] = obj
            else
              warn("object_bad", id)
            end
          end
          local rows = Cells.compute(q, {
            surfable = surfable[def.tileset] == true,
            shore = Cells.set(engine.shoreTiles(def.tileset, ts)),
            cutTiles = Cells.set(engine.cutTreeTiles(def.tileset)),
            cutBlocks = cutBefore,
            warpTiles = Cells.set(ts.warpTiles),
            ledges = Cells.ledgeRows(field.ledges, def.tileset),
            warps = def.warps, signs = def.signs, spinners = spinners[id],
            checkpoint = checkpoint,
            warn = function(code) warn(code, id) end,
          })
          for _, r in ipairs(SwapSites.cutSites(id, rows, q.block, swapAfter, isCut)) do swapRows[#swapRows + 1] = r end
          local palette = paletteOf(id)
          if not sym(palette) then warn("palette_missing", id) palette = "ROUTE" end
          local border = def.borderBlock
          if not int(border, 0, 255) then warn("map_no_border", id) border = 0 end
          local blockList = {}
          for i = 1, #blocks do blockList[i] = blocks[i] end
          local p = parent[id]
          local lastMap = not outside[id] and single(mem[id]) or nil
          if lastMap and not sym(lastMap, MAP_ID) then lastMap = nil end
          put("map", id, {
            id = id, index = def.index, tileset = def.tileset, width = w, height = h,
            blocks = json.array(blockList), border = border,
            surface = surface[id] == true, outside = outside[id] == true,
            connections = connections, warps = json.array(warps), signs = json.array(signs),
            objects = json.array(objects), cells = json.array(rows),
            parent = p and { map = p.map, warp = p.warp, x = p.x, y = p.y } or json.null,
            lastMap = lastMap or json.null,
            dark = dark[id] == true, palette = palette,
          })
        end
      end
      checkpoint()
    end

    -- ---------------------------------------------- effect images, then all images
    enter("images")
    local fx = type(field.overworldFx) == "table" and field.overworldFx or {}
    for _, name in ipairs(sortedKeys(fx)) do
      local entry = fx[name]
      if type(entry) == "table" then addImage(entry.path, name) end
    end
    if type(field.emotionBubbles) == "table" then addImage(field.emotionBubbles.path, "emotionBubbles") end
    for _, path in ipairs(imageOrder) do
      loadImage(path)
      checkpoint()
    end

    -- ---------------------------------------------- sprites
    enter("sprites")
    for _, r in ipairs(d.records.sprites) do
      local rec = r.value
      if type(rec) == "table" and type(rec.image) == "string" then
        local png, w, h = engine.imagePng(rec.image)
        if not png then
          warn("sprite_" .. tostring(w), r.id)
        else
          local res, code = Images.sprite(r.id, rec, png, w, h)
          if res then put("sprite", r.id, res) else warn(code, r.id) end
        end
      else
        warn("sprite_bad", r.id)
      end
      checkpoint()
    end

    -- ---------------------------------------------- palettes
    enter("tables")
    local pals = json.object()
    for _, r in ipairs(d.records.palettes) do
      local colors = type(r.value) == "table" and (r.value.colors or r.value) or nil
      local out = {}
      for i, c in ipairs(type(colors) == "table" and colors or {}) do
        local rgb = type(c) == "table" and { c[1] or c.r, c[2] or c.g, c[3] or c.b } or {}
        if int(rgb[1], 0, 255) and int(rgb[2], 0, 255) and int(rgb[3], 0, 255) then out[i] = rgb end
      end
      if sym(r.id) and #out == 4 then pals[r.id] = out else warn("palette_bad", r.id) end
    end
    put("palettes", "sgb", { mode = "sgb", palettes = pals })

    -- ---------------------------------------------- encounters
    local buckets = d.buckets
    local okBuckets = type(buckets) == "table" and #buckets == 10
    for i = 1, 10 do
      if not (okBuckets and int(buckets[i], 1, 256)) then okBuckets = false break end
    end
    local function slotList(list, maxSlots, code, owner)
      local out = {}
      for _, s in ipairs(type(list) == "table" and list or {}) do
        local m = type(s) == "table" and mon(s.species, s.level) or nil
        if m and #out < maxSlots then out[#out + 1] = m else warn(code, owner) end
      end
      return out
    end
    if not okBuckets then
      warn("encounters_bad_buckets")
    else
      local maps = json.object()
      for _, r in ipairs(d.records.encounters) do
        local entry, any = json.object(), false
        for _, terrain in ipairs({ "grass", "water" }) do
          local t = type(r.value) == "table" and r.value[terrain] or nil
          if type(t) == "table" then
            local slots = slotList(t.slots, 10, "encounter_bad_slot", r.id)
            if int(t.rate, 0, 255) and #slots >= 1 then
              entry[terrain] = { rate = t.rate, slots = json.array(slots) }
              any = true
            end
          end
        end
        if any and sym(r.id, MAP_ID) then maps[r.id] = entry end
      end
      local fishing = type(field.fishing) == "table" and field.fishing or {}
      local function rod(name)
        local def = type(fishing[name]) == "table" and fishing[name] or {}
        if def.pool then return slotList(def.pool, 16, "rod_bad_slot", name) end
        if def.always then return slotList({ def.always }, 16, "rod_bad_slot", name) end
        return {}
      end
      -- field.fishing names the field key of the Super Rod's per-map groups
      -- (fishingPool, src/world/OverworldController.lua:2299-2310)
      local superDef = type(fishing.SUPER_ROD) == "table" and fishing.SUPER_ROD or {}
      local perMapKey = type(superDef.perMap) == "string" and superDef.perMap or "superRod"
      local perMap = field[perMapKey]
      if perMap == nil then perMap = src.value("field", perMapKey) end
      local superRod = json.object()
      for _, mapId in ipairs(sortedKeys(type(perMap) == "table" and perMap or {})) do
        local list = slotList(perMap[mapId], 16, "rod_bad_slot", mapId)
        if sym(mapId, MAP_ID) and #list > 0 then superRod[mapId] = json.array(list) end
      end
      local rods = { old = json.array(rod("OLD_ROD")), good = json.array(rod("GOOD_ROD")), super = superRod }
      put("encounters", "all", { buckets = json.array(copyList(buckets)), maps = maps, rods = rods })
    end
    checkpoint()

    -- ---------------------------------------------- field tables
    local tables = json.object()
    for _, k in ipairs(Export.FIELD_TABLES) do
      if field[k] ~= nil then
        local v = normalize(field[k], 0)
        if v ~= nil then tables[k] = v end
      end
    end
    put("field", "all", { tables = tables })

    -- ---------------------------------------------- names
    local function nameOk(n) return displayName(json, n) end
    local species, moves, items = json.object(), json.object(), json.object()
    for _, r in ipairs(d.records.pokemon) do
      local v = r.value
      if type(v) == "table" and int(v.dex, 1, 151) and nameOk(v.name) and sym(r.id) then
        species[r.id] = { dex = v.dex, name = v.name }
      elseif type(v) == "table" and int(v.dex, 1, 151) then
        warn("name_bad", r.id)
      end
    end
    for _, r in ipairs(d.records.moves) do
      local v = r.value
      if type(v) == "table" and int(v.index, 1, 165) and nameOk(v.name) and sym(r.id) then
        moves[r.id] = { num = v.index, name = v.name }
      else
        warn("move_bad", r.id)
      end
    end
    for _, r in ipairs(d.records.items) do
      local v = r.value
      if type(v) == "table" and nameOk(v.name) and sym(r.id) then
        local item = { name = v.name, key = v.keyItem == true }
        if int(v.index, 0, 255) then item.num = v.index end
        items[r.id] = item
      else
        warn("item_bad", r.id)
      end
    end
    put("names", "all", { species = species, moves = moves, items = items })

    -- ---------------------------------------------- layout and swaps
    put("layout", "kanto", layoutData)
    local function blockAt(mapId, bx, by)
      local def = defs[mapId]
      if not (def and int(bx, 0, 255) and int(by, 0, 255) and int(def.width, 1, 255)) then return nil end
      if bx >= def.width or by >= (def.height or 0) then return nil end
      return type(def.blocks) == "table" and def.blocks[by * def.width + bx + 1] or nil
    end
    for _, r in ipairs(SwapSites.doorSites(field, engine.gameVersion(), blockAt)) do swapRows[#swapRows + 1] = r end
    put("swaps", "all", { sites = json.array(SwapSites.collect(swapRows, function(code) warn(code) end)) })
  end

  local function finish()
    sort(index, function(a, b)
      if a.kind ~= b.kind then return a.kind < b.kind end
      return a.id < b.id
    end)
    local entries = {}
    for i, e in ipairs(index) do entries[i] = { kind = e.kind, id = e.id, hash = e.hash, size = e.size } end
    local game = engine.gameVersion()
    if game ~= "red" and game ~= "blue" then warn("unknown_game") game = "red" end
    local engineVersion = engine.version()
    if type(engineVersion) ~= "string" or not engineVersion:find("^[0-9A-Za-z.+_-]+$") or #engineVersion > 32 then
      engineVersion = "unknown"
    end
    local bridge = type(opts.bridge) == "string" and opts.bridge:find("^[0-9A-Za-z.+_-]+$") and #opts.bridge <= 32
      and opts.bridge or "unknown"
    indexText = json.encode({ game = game, engine = engineVersion, bridge = bridge, resources = json.array(entries) })
    if not indexText then error("export.index_encode", 0) end
    state = "ready"
    stats.buildMs = floor((clock() - startedAt) * 1000 + 0.5)
    if log then
      local total = 0
      for _, code in ipairs(warnOrder) do
        local w = warnings[code]
        total = total + w.count
        log.warn("export.warning", { code = code, count = w.count, first = w.first })
      end
      log.info("export.counts", stats.counts)
      local phases = {}
      for name, ms in pairs(stats.phaseMs) do phases[name] = floor(ms + 0.5) end
      log.info("export.phases", phases)
      log.info("export.ready", {
        path = stats.path, resources = #index, bytes = stats.bytes, largest = stats.largest,
        ms = stats.buildMs, cpuMs = floor(stats.cpuMs + 0.5), maxStepMs = floor(stats.maxStepMs + 0.5),
        steps = stats.steps, slowSteps = stats.slowSteps, warnings = total,
      })
    end
  end

  local function fail(err)
    state = "failed"
    stats.buildMs = floor((clock() - startedAt) * 1000 + 0.5)
    if log then
      local code = type(err) == "string" and err:match("^export%.([%w_]+)") or nil
      log.error("export.failed", { at = code and ("export." .. code) or where(err), path = stats.path })
    end
  end

  local function startBuild()
    state = "building"
    startedAt = clock()
    co = coroutine.create(build)
    if log then log.info("export.build_started", { datasets = bootLive == nil }) end
  end

  local function resume()
    local t0 = clock()
    deadline = t0 + budget
    local ok, err = coroutine.resume(co)
    local spent = (clock() - t0) * 1000
    stats.steps = stats.steps + 1
    stats.cpuMs = stats.cpuMs + spent
    if spent > stats.maxStepMs then stats.maxStepMs = spent end
    if spent > Export.SLOW_STEP_MS then stats.slowSteps = stats.slowSteps + 1 end
    stats.phaseMs[phase] = (stats.phaseMs[phase] or 0) + spent
    if not ok then
      co = nil
      fail(err)
    elseif coroutine.status(co) == "dead" then
      co = nil
      local okF, errF = pcall(finish)
      if not okF then fail(errF) end
    end
  end

  -- ------------------------------------------------------------ serving

  local function serve()
    local sent = false
    local left = serveBytes
    local gen = session.generation()
    while qhead <= qtail do
      if session.queued() > backlog then break end
      local r = queue[qhead]
      queue[qhead] = nil
      qhead = qhead + 1
      if r.gen == gen and session.established() then
        sent = true
        if state == "failed" then
          session.sendError("export_failed", r.seq, "export unavailable")
        elseif r.what == "index" then
          session.replyEncoded(r, "export.index", indexText)
          left = left - #indexText
        else
          local res = resources[key(r.kind, r.id)]
          if not res then
            session.sendError("not_found", r.seq, "no such export resource")
          else
            local page = concat({ '{"data":', res.text, ',"hash":"', res.hash, '","id":', json.encode(res.id),
                                  ',"kind":"', res.kind, '"}' })
            session.replyEncoded(r, "export.data", page)
            stats.served = stats.served + 1
            left = left - #page
          end
        end
      end
      if left <= 0 then break end
    end
    if qhead > qtail then queue, qhead, qtail = {}, 1, 0 end
    return sent
  end

  -- ------------------------------------------------------------ the session side

  local function request(what, data, msg, role)
    if not Export.ROLES[role] then
      session.sendError("role", msg.seq, "export needs role builder or test")
      return
    end
    local r = { what = what, seq = msg.seq, gen = session.generation() }
    if what == "get" then
      if not (Export.KINDS[data.kind] and type(data.id) == "string" and #data.id <= 64 and data.id:find(ID)) then
        session.sendError("bad_message", msg.seq, "bad export.get")
        return
      end
      r.kind, r.id = data.kind, data.id
    end
    qtail = qtail + 1
    queue[qtail] = r
    if state == "idle" then startBuild() end
  end

  -- Registers the export handlers on a kb_session.
  function ex.attach(sess)
    session = sess
    sess.on("export.index", function(data, msg, role) request("index", data, msg, role) end)
    sess.on("export.get", function(data, msg, role) request("get", data, msg, role) end)
  end

  -- mods.loaded (src/mods/Loader.lua:2163): the cheap check that the dataset
  -- view opens; when it doesn't, PLAN section 8's fallback copies the live
  -- Data right now, before any map has loaded.
  function ex.onModsLoaded(payload)
    local version = engine.gameVersion()
    local view, reason = engine.openDataset(version)
    if view then
      if log then log.info("export.datasets_open", { version = version }) end
      return
    end
    bootReason = reason
    local t0 = clock()
    local src, why = engine.liveSource(type(payload) == "table" and payload.data or nil)
    if src then
      bootLive = src
      if log then
        log.info("export.live_copy", { reason = reason, when = "boot", ms = floor((clock() - t0) * 1000 + 0.5),
                                       dropped = src.dropped })
      end
    elseif log then
      log.warn("export.live_copy_failed", { reason = reason, why = why })
    end
  end

  -- map.entered: from here on the live Data may differ from the import.
  function ex.onMapEntered() worldSeen = true end

  -- One logic step: build a slice, then serve within the byte budget.
  -- Returns true when replies were queued (the caller flushes).
  function ex.step()
    if state == "building" and co then resume() end
    if (state == "ready" or state == "failed") and qhead <= qtail then return serve() end
    return false
  end

  function ex.state() return state end

  -- Counts and flags for debug.status (integers and booleans only).
  function ex.status()
    local total = 0
    for _, w in pairs(warnings) do total = total + w.count end
    return {
      exportState = Export.STATES[state],
      exportResources = #index,
      exportBytes = stats.bytes,
      exportLargest = stats.largest,
      exportBuildMs = stats.buildMs,
      exportCpuMs = floor(stats.cpuMs + 0.5),
      exportMaxStepMs = floor(stats.maxStepMs + 0.5),
      exportSteps = stats.steps,
      exportSlowSteps = stats.slowSteps,
      exportWarnings = total,
      exportServed = stats.served,
      exportDatasets = stats.path == "datasets",
      exportLive = stats.path == "live",
      exportBootLive = bootLive ~= nil,
    }
  end

  return ex
end

return Export
