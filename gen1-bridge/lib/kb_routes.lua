-- kb_routes: Wild Routes (docs/specs/WILD_ROUTES_PLAN.md §4.1-4.2, WR2): the
-- route list, the exit patched into a ROM map, and the route map that stands
-- for a zone in the hidden game.
--
-- At boot, from the entry chunk (registrations must come before the engine's
-- merge: Gen1Recomp's wiki, "Concepts: Registries"), install() reads
-- data/routes.json and, for each zone of the running game:
--   * computes the exit: `depth` blocks through the tree line in front of
--     the chosen cell become copies of the block that cell stands in (the
--     player's own data, never a constant), after a signature check asked
--     of the engine's own map queries (kb_engine.mapQuery);
--   * registers the route map KB_ROUTE_<ID>: one block of that same floor,
--     2x2 cells, A = (0, 0), x1 = (1, 0) (the plan's §4.2);
--   * registers two step scripts (map_scripts compose; onStep's first truthy
--     return consumes, mods before the engine's own:
--     src/script/MapScripts.lua:1-27, called after every completed step,
--     src/world/OverworldController.lua:4833-4840): the clearing's last
--     cells warp to x1, the route map's A warps back to the clearing's
--     second-to-last cells, facing the town. Both warps are queued scripts
--     (mod.world:queueScript, src/world/WorldAPI.lua:626-632) running the
--     engine's own `warp` command (src/script/Commands.lua:395-402), which
--     the bridge follows as kind "script" (observe/kb_warp.lua);
--   * registers KB_<CLASS> trainer records, copies of the classes the zone's
--     trainers use, whose one party route.battle rewrites (act/kb_route.lua);
--   * registers the route map's song when the music id exists;
--   * patches the exit's map last (mod.content.maps:patch with the whole
--     `blocks` list: lists replace wholesale in a record registry).
-- A schema violation is thrown by the registry call itself for an api 2 mod
-- (src/mods/Loader.lua:1276-1281), so every registration is guarded and a
-- failure switches that zone off; references are only ever to ids that
-- exist (the town's tileset, vanilla trainer fields), since a dangling one
-- would fail the whole mod after the merge (src/mods/Loader.lua:1934-1964).
--
-- Nothing ROM-derived is in the route list or in this file: map and class
-- symbols, cell coordinates, counts and our own text. Logs show route ids,
-- codes and counts only.

local Routes = {}

Routes.VERSION = 1
Routes.MAX_ROUTES = 32
Routes.MAX_DEPTH = 4
Routes.MAX_CLASSES = 16
Routes.MAP_PREFIX = "KB_ROUTE_"
Routes.CLASS_PREFIX = "KB_"
-- the route map's cells (the plan's §4.2): you arrive on x1, A leads back
Routes.CELL_A = { x = 0, y = 0 }
Routes.CELL_X1 = { x = 1, y = 0 }
Routes.ARRIVE_FACING = "right"
Routes.KINDS = { free_roam = true }        -- `connecting` comes later (the plan's §4.10)
Routes.THEMES = { forest = true }          -- caves and the sea come later
Routes.REMATCH = { each_visit = true }

local DIRS = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
local OPPOSITE = { up = "down", down = "up", left = "right", right = "left" }
Routes.OPPOSITE = OPPOSITE

local floor = math.floor

local function sym(v, pattern)
  return type(v) == "string" and #v > 0 and #v <= 64 and v:find(pattern) ~= nil
end
local function whole(v, lo, hi)
  return type(v) == "number" and v == floor(v) and v >= lo and v <= hi
end
local function chance(v) return type(v) == "number" and v >= 0 and v <= 1 end
local function range(v, lo, hi)
  return type(v) == "table" and whole(v[1], lo, hi) and whole(v[2], lo, hi) and v[1] <= v[2] and v[3] == nil
end
local function isNull(json, v) return v == nil or (json and v == json.null) end

function Routes.mapId(id) return Routes.MAP_PREFIX .. id:upper() end
function Routes.classId(cls) return Routes.CLASS_PREFIX .. cls end

-- ------------------------------------------------------------ the route list

-- One route list entry, checked and normalized; nil plus a reason code.
local function entry(json, r)
  if type(r) ~= "table" then return nil, "bad_entry" end
  if not sym(r.id, "^[a-z][a-z0-9_]*$") or #r.id > 32 then return nil, "bad_id" end
  if type(r.name) ~= "string" or #r.name < 1 or #r.name > 24 or not r.name:find("^[%w %-'%.]+$") then
    return nil, "bad_name"
  end
  if r.kind == "connecting" then return nil, "kind_later" end
  if not Routes.KINDS[r.kind] then return nil, "bad_kind" end
  if r.theme ~= nil and not Routes.THEMES[r.theme] then
    if type(r.theme) == "string" then return nil, "theme_later" end
    return nil, "bad_theme"
  end
  local f = r.from
  if type(f) ~= "table" or not sym(f.map, "^[A-Z0-9_]+$") then return nil, "bad_from" end
  local games = {}
  if type(f.games) ~= "table" or #f.games < 1 or #f.games > 9 then return nil, "bad_games" end
  for _, g in ipairs(f.games) do
    if not sym(g, "^[a-z]+$") then return nil, "bad_games" end
    games[g] = true
  end
  local depth = isNull(json, f.depth) and 2 or f.depth
  if not whole(depth, 1, Routes.MAX_DEPTH) then return nil, "bad_depth" end
  local out = {
    id = r.id, name = r.name, kind = r.kind, theme = r.theme or "forest",
    mapId = Routes.mapId(r.id),
    from = { map = f.map, games = games, depth = depth },
    classes = {}, music = nil,
  }
  -- the spot: unset until the player chose it (the plan's §4.2, "Your part")
  if isNull(json, f.x) and isNull(json, f.y) and isNull(json, f.facing) then
    out.unset = true
  elseif whole(f.x, 0, 4095) and whole(f.y, 0, 4095) and DIRS[f.facing] then
    out.from.x, out.from.y, out.from.facing = f.x, f.y, f.facing
  else
    return nil, "bad_spot"
  end
  -- generator settings: checked here, used by the builder (the export's
  -- `routes` resource) and the server
  local a = r.areas
  if a ~= nil then
    if type(a) ~= "table" or not range(a.main, 1, 8) or (a.side ~= nil and not chance(a.side)) then
      return nil, "bad_areas"
    end
  end
  local lv = r.levels
  if lv ~= nil then
    if type(lv) ~= "table" or (lv.from ~= nil and lv.from ~= "neighbours")
        or (lv.perArea ~= nil and not whole(lv.perArea, 0, 5)) or (lv.maxRise ~= nil and not whole(lv.maxRise, 0, 20)) then
      return nil, "bad_levels"
    end
  end
  local t = r.trainers
  if t ~= nil then
    if type(t) ~= "table" or (t.perArea ~= nil and not range(t.perArea, 0, 6))
        or (t.rematch ~= nil and not Routes.REMATCH[t.rematch]) then
      return nil, "bad_trainers"
    end
    if t.classes ~= nil then
      if type(t.classes) ~= "table" or #t.classes > Routes.MAX_CLASSES then return nil, "bad_classes" end
      local seen = {}
      for _, cls in ipairs(t.classes) do
        if not sym(cls, "^[A-Z0-9_]+$") or #cls > 48 then return nil, "bad_classes" end
        if not seen[cls] then
          seen[cls] = true
          out.classes[#out.classes + 1] = cls
        end
      end
    end
  end
  local d = r.dungeon
  if d ~= nil then
    if type(d) ~= "table" or (d.chance ~= nil and not chance(d.chance))
        or (d.floors ~= nil and d.floors ~= "by_level") then
      return nil, "bad_dungeon"
    end
  end
  if not isNull(json, r.music) then
    if not sym(r.music, "^[A-Za-z0-9_]+$") then return nil, "bad_music" end
    out.music = r.music
  end
  out.raw = r
  return out
end

-- data/routes.json's text -> entries, problems. Entries that don't check out
-- are left out, each with a problem { id?, code }; a bad file gives no
-- entries and one problem.
function Routes.parse(json, text)
  if type(text) ~= "string" then return {}, { { code = "no_file" } } end
  local doc = json.decode(text, 262144)
  if type(doc) ~= "table" or doc.version ~= Routes.VERSION or type(doc.routes) ~= "table" then
    return {}, { { code = "bad_file" } }
  end
  local out, problems, seen = {}, {}, {}
  for i, r in ipairs(doc.routes) do
    if i > Routes.MAX_ROUTES then
      problems[#problems + 1] = { code = "too_many" }
      break
    end
    local e, code = entry(json, r)
    local id = type(r) == "table" and sym(r.id, "^[a-z][a-z0-9_]*$") and #r.id <= 32 and r.id or nil
    if e and seen[e.id] then e, code = nil, "duplicate_id" end
    if e then
      seen[e.id] = true
      out[#out + 1] = e
    else
      problems[#problems + 1] = { id = id, code = code }
    end
  end
  return out, problems
end

-- ------------------------------------------------------------ the exit

local function cellKey(x, y) return x .. "," .. y end
Routes.cellKey = cellKey

local function copyList(list)
  local out = {}
  for i = 1, #list do out[i] = list[i] end
  return out
end

-- The four cells of block (bx, by).
local function blockCells(bx, by)
  return { { 2 * bx, 2 * by }, { 2 * bx + 1, 2 * by }, { 2 * bx, 2 * by + 1 }, { 2 * bx + 1, 2 * by + 1 } }
end

-- The exit's plan for one entry on its map's record (def) and the engine's
-- map query over it (q: walkable/water per cell, from kb_engine.mapQuery).
-- `occupied` (optional) is a set of cellKey -> true the clearing must not
-- cover (warps, signs, objects, hidden items). Returns
--   { blocks, source, cleared = { {bx, by} }, triggers = { [key] = true },
--     triggerList = { {x, y} }, back = { x, y, facing }, exitFacing }
-- or nil plus a code: spot_outside, spot_unwalkable, outside_map,
-- not_trees, occupied. (not_walkable_after is check() below, on the
-- patched record.)
function Routes.plan(e, def, q, occupied)
  local f = e.from
  local w, h = def.width, def.height
  if not (whole(w, 1, 1024) and whole(h, 1, 1024) and type(def.blocks) == "table" and #def.blocks == w * h) then
    return nil, "bad_map"
  end
  if f.x >= 2 * w or f.y >= 2 * h then return nil, "spot_outside" end
  local bx, by = floor(f.x / 2), floor(f.y / 2)
  for _, c in ipairs(blockCells(bx, by)) do
    if not q.walkable(c[1], c[2]) then return nil, "spot_unwalkable" end
  end
  local source = def.blocks[by * w + bx + 1]
  local d = DIRS[f.facing]
  local blocks = copyList(def.blocks)
  local cleared = {}
  for i = 1, f.depth do
    local cx, cy = bx + d[1] * i, by + d[2] * i
    if cx < 0 or cy < 0 or cx >= w or cy >= h then return nil, "outside_map" end
    for _, c in ipairs(blockCells(cx, cy)) do
      if q.walkable(c[1], c[2]) or q.water(c[1], c[2]) then return nil, "not_trees" end
      if occupied and occupied[cellKey(c[1], c[2])] then return nil, "occupied" end
    end
    blocks[cy * w + cx + 1] = source
    cleared[#cleared + 1] = { cx, cy }
  end
  -- the last block's far side triggers the way out; its near side is where
  -- the way back lands, facing the town
  local last = cleared[#cleared]
  local lx, ly = last[1], last[2]
  local far, near
  if f.facing == "up" then
    far, near = { { 2 * lx, 2 * ly }, { 2 * lx + 1, 2 * ly } }, { 2 * lx, 2 * ly + 1 }
  elseif f.facing == "down" then
    far, near = { { 2 * lx, 2 * ly + 1 }, { 2 * lx + 1, 2 * ly + 1 } }, { 2 * lx, 2 * ly }
  elseif f.facing == "left" then
    far, near = { { 2 * lx, 2 * ly }, { 2 * lx, 2 * ly + 1 } }, { 2 * lx + 1, 2 * ly }
  else
    far, near = { { 2 * lx + 1, 2 * ly }, { 2 * lx + 1, 2 * ly + 1 } }, { 2 * lx, 2 * ly }
  end
  local triggers = {}
  for _, c in ipairs(far) do triggers[cellKey(c[1], c[2])] = true end
  return {
    blocks = blocks, source = source, width = w, cleared = cleared, triggers = triggers, triggerList = far,
    back = { x = near[1], y = near[2], facing = OPPOSITE[f.facing] }, exitFacing = f.facing,
  }
end

-- After patching: every cleared cell must be walkable on the patched record
-- (q2 over it). Returns true, or nil plus "not_walkable_after".
function Routes.check(p, q2)
  for _, b in ipairs(p.cleared) do
    for _, c in ipairs(blockCells(b[1], b[2])) do
      if not q2.walkable(c[1], c[2]) then return nil, "not_walkable_after" end
    end
  end
  return true
end

-- The cells the clearing must not cover: the map's warps, signs and objects,
-- and its hidden items (field.hiddenItems[map], when given).
function Routes.occupiedCells(def, hidden)
  local out = {}
  for _, list in ipairs({ def.warps, def.signs, def.objects, hidden }) do
    for _, o in ipairs(type(list) == "table" and list or {}) do
      if type(o) == "table" and type(o.x) == "number" and type(o.y) == "number" then
        out[cellKey(o.x, o.y)] = true
      end
    end
  end
  return out
end

-- The route map's record: one block of the clearing's floor, on the town's
-- tileset (so the engine treats it as outdoors) with the town's border.
-- `outdoor` (src/mods/Schemas.lua:1938, since v0.3.85) says so explicitly:
-- Fly and Teleport work from it as on any route, which WILD_ROUTES_PLAN §4
-- allows (flying out ends the visit), and the engine never rolls a wild
-- encounter off its grass (OverworldState.rollsIndoorEncounters, OC:4782-
-- 4788; the zone's wild Pokemon are Minecraft's anyway, WR5).
function Routes.routeMapDef(e, p, def)
  local out = {
    id = e.mapId, width = 1, height = 1, blocks = { p.source }, tileset = def.tileset,
    warps = {}, signs = {}, objects = {}, outdoor = true,
  }
  if whole(def.borderBlock, 0, 255) then out.borderBlock = def.borderBlock end
  return out
end

-- A route map is one block, 2x2 cells, but only its top row is walkable: A
-- and x1. The zone has a hedge under x1 (RouteGenerator), so the hidden game
-- closes the lower row too and the two players can't part there
-- (docs/specs/WILD_ROUTES_PLAN.md §4.2). True for a move the engine allowed
-- onto that row: ctx is the movement.collision hook's (map, mover, dir, toX,
-- toY), currentMap() the overworld's map id, for a ctx without its map.
function Routes.closedMove(rt, ctx, currentMap)
  if type(rt) ~= "table" or type(rt.byMap) ~= "table" or next(rt.byMap) == nil or type(ctx) ~= "table" then
    return false
  end
  local mapId
  if type(ctx.map) == "table" and ctx.map.id ~= nil then mapId = ctx.map.id else mapId = currentMap() end
  if mapId == nil or rt.byMap[mapId] == nil then return false end
  local y = ctx.toY
  if type(y) ~= "number" then
    local m, d = ctx.mover, DIRS[ctx.dir]
    if type(m) ~= "table" or type(m.cellY) ~= "number" or d == nil then return false end
    y = m.cellY + d[2]
  end
  return y >= 1
end

-- A KB_<CLASS> record: the vanilla class's own fields (name, picture, money,
-- AI, theme, palette) with one party, the class's first, rewritten before
-- every zone battle. nil when the class has no usable party.
function Routes.classRecord(cls, vanilla)
  if type(vanilla) ~= "table" or type(vanilla.name) ~= "string" or type(vanilla.parties) ~= "table"
      or type(vanilla.parties[1]) ~= "table" or #vanilla.parties[1] < 1 then
    return nil
  end
  local out = {}
  for k, v in pairs(vanilla) do
    if k ~= "id" and k ~= "index" and k ~= "parties" and k ~= "partyNames" then out[k] = v end
  end
  out.id = Routes.classId(cls)
  if out.basePic == nil and out.pic == nil then out.basePic = cls end
  local party = {}
  for i, m in ipairs(vanilla.parties[1]) do
    local copy = {}
    for k, v in pairs(m) do copy[k] = v end
    party[i] = copy
  end
  out.parties = { party }
  return out
end

-- ------------------------------------------------------------ install

-- opts:
--   json      kb_json
--   text      data/routes.json's text (nil when the file is missing)
--   content   mod.content (registries: maps, tilesets, map_scripts,
--             trainers, map_songs, music, field)
--   world     mod.world (queueScript, warpTo)
--   game      the running game ("red", "blue", ...)
--   mapQuery  kb_engine.mapQuery(def, tileset)
--   log       kb_log logger (optional)
-- Returns the runtime: zones (by route id and by route map id), the entries,
-- the problems and stats. Never throws.
function Routes.install(opts)
  local json, content, world, log = opts.json, opts.content or {}, opts.world, opts.log
  local rt = { zones = {}, byMap = {}, byTown = {}, entries = {}, problems = {}, world = world,
               stats = { listed = 0, installed = 0, skipped = 0, unset = 0, otherGame = 0, classes = 0,
                         warpsOut = 0, warpsBack = 0, warpsBusy = 0, closed = 0 } }
  local entries, problems = Routes.parse(json, opts.text)
  rt.entries = entries
  rt.stats.listed = #entries
  for _, p in ipairs(problems) do rt.problems[#rt.problems + 1] = p end

  local function problem(id, code)
    rt.problems[#rt.problems + 1] = { id = id, code = code }
    rt.stats.skipped = rt.stats.skipped + 1
    if log then log.warn("routes.skipped", { route = id, code = code }) end
  end
  local function call(reg, method, ...)
    local r = content[reg]
    if type(r) ~= "table" or type(r[method]) ~= "function" then return false, "no_registry" end
    local ok, res = pcall(r[method], r, ...)
    if not ok then return false, "rejected" end
    return true, res
  end
  local function get(reg, id)
    local ok, v = call(reg, "get", id)
    return ok and v or nil
  end

  -- one step script per town (map scripts compose; several zones may share a
  -- town), dispatching on the cell
  local function townStep(town)
    return function(_, _, x, y)
      for _, zone in ipairs(rt.byTown[town] or {}) do
        if zone.active and zone.plan.triggers[cellKey(x, y)] then return Routes.warpTo(rt, zone, "out") end
      end
      return false
    end
  end
  local townScripted = {}
  local classDone = {}

  for _, e in ipairs(entries) do
    if not e.from.games[opts.game] then
      rt.stats.otherGame = rt.stats.otherGame + 1
    elseif e.unset then
      rt.stats.unset = rt.stats.unset + 1
      rt.problems[#rt.problems + 1] = { id = e.id, code = "unset" }
    else
      local def = get("maps", e.from.map)
      local ts = type(def) == "table" and get("tilesets", def.tileset) or nil
      local q = def and ts and opts.mapQuery and opts.mapQuery(def, ts) or nil
      if not q then
        problem(e.id, def and "no_query" or "no_map")
      else
        local hidden = get("field", "hiddenItems")
        local p, code = Routes.plan(e, def, q, Routes.occupiedCells(def, type(hidden) == "table" and hidden[e.from.map] or nil))
        local patched
        if p then
          patched = {}
          for k, v in pairs(def) do patched[k] = v end
          patched.blocks = p.blocks
          local q2 = opts.mapQuery(patched, ts)
          if not q2 then p, code = nil, "no_query" else
            local ok
            ok, code = Routes.check(p, q2)
            if not ok then p = nil end
          end
        end
        if not p then
          problem(e.id, code)
        elseif get("maps", e.mapId) ~= nil then
          problem(e.id, "map_taken")
        else
          local zone = { id = e.id, entry = e, mapId = e.mapId, town = e.from.map, plan = p, active = false,
                         classes = {} }
          -- 1. the route map; 2. its step script and the town's; 3. the
          -- town's blocks. A later failure undoes the route map (a tombstone)
          -- and leaves the scripts inert (zone.active stays false).
          local okMap = call("maps", "register", e.mapId, Routes.routeMapDef(e, p, def))
          local okScripts = okMap and call("map_scripts", "register", e.mapId, {
            onStep = function(_, _, x, y)
              if zone.active and x == Routes.CELL_A.x and y == Routes.CELL_A.y then
                return Routes.warpTo(rt, zone, "back")
              end
              return false
            end,
          })
          if okScripts and not townScripted[e.from.map] then
            okScripts = call("map_scripts", "register", e.from.map, { onStep = townStep(e.from.map) })
            townScripted[e.from.map] = okScripts or nil
          end
          local okPatch = okScripts and call("maps", "patch", e.from.map, { blocks = p.blocks })
          if not okPatch then
            if okMap then call("maps", "remove", e.mapId) end
            problem(e.id, not okMap and "map_rejected" or not okScripts and "scripts_rejected" or "patch_rejected")
          else
            zone.active = true
            rt.zones[e.id] = zone
            rt.byMap[e.mapId] = zone
            rt.byTown[e.from.map] = rt.byTown[e.from.map] or {}
            table.insert(rt.byTown[e.from.map], zone)
            rt.stats.installed = rt.stats.installed + 1
            -- the trainer classes (a class that can't be copied only takes
            -- that class's battles away)
            for _, cls in ipairs(e.classes) do
              local id = Routes.classId(cls)
              if classDone[cls] == nil then
                local rec = get("trainers", id) == nil and Routes.classRecord(cls, get("trainers", cls)) or nil
                classDone[cls] = rec ~= nil and call("trainers", "register", id, rec) or false
                if classDone[cls] then rt.stats.classes = rt.stats.classes + 1 end
              end
              if classDone[cls] then zone.classes[cls] = id end
            end
            -- the song, only when the music id exists (a dangling one would
            -- fail the whole mod after the merge)
            if e.music and get("music", e.music) ~= nil then call("map_songs", "register", e.mapId, e.music) end
            if log then
              log.info("routes.installed", { route = e.id, depth = e.from.depth, classes = rt.stats.classes })
            end
          end
        end
      end
    end
  end
  return rt
end

-- The warps of the step scripts, as a queued script of the engine's own
-- `warp` command. "out" goes to x1 of the route map, facing into the zone;
-- "back" to the clearing's second-to-last cell, facing the town. Returns
-- true when queued (the step is consumed), false when the world is busy.
function Routes.warpTo(rt, zone, which)
  local world = rt.world
  if not (world and type(world.queueScript) == "function") then return false end
  local row
  if which == "out" then
    row = { "warp", zone.mapId, Routes.CELL_X1.x, Routes.CELL_X1.y, Routes.ARRIVE_FACING }
  else
    local b = zone.plan.back
    row = { "warp", zone.town, b.x, b.y, b.facing }
  end
  local ok, queued = pcall(world.queueScript, world, { row })
  local key = which == "out" and "warpsOut" or "warpsBack"
  if ok and queued then
    rt.stats[key] = rt.stats[key] + 1
    return true
  end
  rt.stats.warpsBusy = rt.stats.warpsBusy + 1
  return false
end

-- ------------------------------------------------------------ the export

-- The export's view (export/kb_export.lua): the patched blocks of a town
-- map, and the `routes` resource (docs/PROTOCOL.md "Export resources").
function Routes.patchedBlocks(rt, mapId)
  local zones = rt and rt.byTown[mapId]
  if not zones then return nil end
  -- several zones in one town: each clearing is laid over the town's blocks
  local blocks
  for _, zone in ipairs(zones) do
    if zone.active then
      local p = zone.plan
      blocks = blocks or copyList(p.blocks)
      for _, b in ipairs(p.cleared) do blocks[b[2] * p.width + b[1] + 1] = p.source end
    end
  end
  return blocks
end

function Routes.exportData(json, rt)
  local list = {}
  for _, e in ipairs(rt and rt.entries or {}) do
    local zone = rt.zones[e.id]
    if zone and zone.active then
      local p = zone.plan
      local cleared, triggers = {}, {}
      for _, b in ipairs(p.cleared) do cleared[#cleared + 1] = { b[1], b[2] } end
      for _, c in ipairs(p.triggerList) do triggers[#triggers + 1] = { c[1], c[2] } end
      local raw = e.raw
      local item = {
        id = e.id, name = e.name, kind = e.kind, theme = e.theme,
        town = { map = e.from.map, cleared = cleared, triggers = triggers,
                 back = { x = p.back.x, y = p.back.y, facing = p.back.facing } },
        routeMap = { id = e.mapId, width = 1, height = 1,
                     a = { x = Routes.CELL_A.x, y = Routes.CELL_A.y },
                     arrive = { x = Routes.CELL_X1.x, y = Routes.CELL_X1.y, facing = Routes.ARRIVE_FACING } },
      }
      local classes = {}
      for _, cls in ipairs(e.classes) do
        if zone.classes[cls] then classes[#classes + 1] = cls end
      end
      item.trainers = json.object({
        perArea = type(raw.trainers) == "table" and raw.trainers.perArea or { 1, 3 },
        rematch = type(raw.trainers) == "table" and raw.trainers.rematch or "each_visit",
        classes = json.array(classes),
      })
      item.areas = json.object({
        main = type(raw.areas) == "table" and raw.areas.main or { 3, 4 },
        side = type(raw.areas) == "table" and raw.areas.side or 0.5,
      })
      item.levels = json.object({
        from = "neighbours",
        perArea = type(raw.levels) == "table" and raw.levels.perArea or 1,
        maxRise = type(raw.levels) == "table" and raw.levels.maxRise or 3,
      })
      item.dungeon = json.object({
        chance = type(raw.dungeon) == "table" and raw.dungeon.chance or 0.6,
        floors = "by_level",
      })
      list[#list + 1] = item
    end
  end
  return { version = Routes.VERSION, routes = json.array(list) }
end

return Routes
