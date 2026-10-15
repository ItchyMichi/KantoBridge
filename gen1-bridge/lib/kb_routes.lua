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
-- Routes v2 (MULTI_GAME_PLAN §4.8, MG2): a `connecting` route joins two
-- regions. It has two **ends**, `from` and `to`, each of the exit's shape
-- (region, games, map, x, y, facing, depth, and its own classes and music --
-- a null music means none -- else the route's), and each hidden game
-- registers **only the end whose
-- `games` include it**: that end's clearing and a route map of today's
-- [A][x1] form; the far cells live in the other game's process, and the
-- builder joins the two halves with a pass (MG5). The export item names both
-- ends (region and games) and which end this game registered. An end's
-- region defaults to its first game's default region in the catalogue
-- (kb_games).
--
-- MG8 (docs/specs/HOENN_PASS_PLAN.md K2-K4): an engine whose games can't
-- take this end (Gen 3: no new maps, no map scripts, no patched exits, spike
-- S2) installs it itself: when the adapter has routeInstall(zone, content),
-- the end's zone record goes to it instead of the registries (MG9: with the
-- registries, which Gen 2's end registers its route map and town in). The adapter plans the
-- clearing on its own map data (filling zone.plan: cleared blocks, triggers,
-- the way back; ready once planned, zone.active false when the spot doesn't
-- check out), opens it at run time, triggers the way out and stands for the
-- route map; such a zone is marked `own`.
--
-- Nothing ROM-derived is in the route list or in this file: map and class
-- symbols, cell coordinates, counts and our own text. Logs show route ids,
-- codes and counts only.

local Routes = {}

Routes.VERSION = 1                         -- the version written for a free-roam list's export
Routes.VERSIONS = { [1] = true, [2] = true } -- route list versions read (2: connecting routes, MG2)
Routes.VERSION_CONNECTING = 2
Routes.MAX_ROUTES = 32
Routes.MAX_DEPTH = 4
Routes.MAX_CLASSES = 16
Routes.MAP_PREFIX = "KB_ROUTE_"
Routes.CLASS_PREFIX = "KB_"
-- the route map's cells (the plan's §4.2): you arrive on x1, A leads back
Routes.CELL_A = { x = 0, y = 0 }
Routes.CELL_X1 = { x = 1, y = 0 }
Routes.ARRIVE_FACING = "right"
Routes.KINDS = { free_roam = true, connecting = true } -- connecting: two ends, one per region (MG2)
Routes.ENDS = { "from", "to" }
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
-- a chance in whole percent: the export carries it, and canonical JSON (hashed
-- alike in Python, Java and Lua) carries no fractional numbers (protocol/README.md)
local function percent(v) return whole(v, 0, 100) end
local function range(v, lo, hi)
  return type(v) == "table" and whole(v[1], lo, hi) and whole(v[2], lo, hi) and v[1] <= v[2] and v[3] == nil
end
local function isNull(json, v) return v == nil or (json and v == json.null) end

-- The route map's id: KB_ROUTE_<ID>; the `to` end of a connecting route
-- registered by the same game as its `from` end (a one-engine test world)
-- gets KB_ROUTE_<ID>_TO.
function Routes.mapId(id, which)
  return Routes.MAP_PREFIX .. id:upper() .. (which == "to" and "_TO" or "")
end
function Routes.classId(cls) return Routes.CLASS_PREFIX .. cls end

-- ------------------------------------------------------------ the route list

-- A list of trainer class symbols (deduplicated), or nil plus a code.
local function classList(list)
  if type(list) ~= "table" or #list > Routes.MAX_CLASSES then return nil, "bad_classes" end
  local out, seen = {}, {}
  for _, cls in ipairs(list) do
    if not sym(cls, "^[A-Z0-9_]+$") or #cls > 48 then return nil, "bad_classes" end
    if not seen[cls] then
      seen[cls] = true
      out[#out + 1] = cls
    end
  end
  return out
end

-- One end of a route (the exit's shape), checked and normalized: { map,
-- games, gameList, depth, x, y, facing, region?, classes?, music?, unset }.
-- nil plus a code: bad_from / bad_to, bad_games, bad_depth, bad_spot,
-- bad_region, bad_classes, bad_music.
local function routeEnd(json, f, which)
  local badEnd = which == "to" and "bad_to" or "bad_from"
  if type(f) ~= "table" or not sym(f.map, "^[A-Z0-9_]+$") then return nil, badEnd end
  local games, gameList = {}, {}
  if type(f.games) ~= "table" or #f.games < 1 or #f.games > 9 then return nil, "bad_games" end
  for _, g in ipairs(f.games) do
    if not sym(g, "^[a-z][a-z0-9_]*$") or #g > 16 then return nil, "bad_games" end
    if not games[g] then
      games[g] = true
      gameList[#gameList + 1] = g
    end
  end
  local depth = isNull(json, f.depth) and 2 or f.depth
  if not whole(depth, 1, Routes.MAX_DEPTH) then return nil, "bad_depth" end
  local out = { map = f.map, games = games, gameList = gameList, depth = depth }
  -- the spot: unset until the player chose it (the plan's §4.2, "Your part")
  if isNull(json, f.x) and isNull(json, f.y) and isNull(json, f.facing) then
    out.unset = true
  elseif whole(f.x, 0, 4095) and whole(f.y, 0, 4095) and DIRS[f.facing] then
    out.x, out.y, out.facing = f.x, f.y, f.facing
  else
    return nil, "bad_spot"
  end
  -- MG2: the end's region (absent: the games' default region, filled in by
  -- install from the catalogue), its own classes and music
  if not isNull(json, f.region) then
    if not sym(f.region, "^[a-z][a-z0-9_]*$") or #f.region > 24 then return nil, "bad_region" end
    out.region = f.region
  end
  if f.classes ~= nil then
    local classes, code = classList(f.classes)
    if not classes then return nil, code end
    out.classes = classes
  end
  if f.music ~= nil then
    if isNull(json, f.music) then
      out.music = false -- this end has no song (absent: the route's)
    else
      if not sym(f.music, "^[A-Za-z0-9_]+$") then return nil, "bad_music" end
      out.music = f.music
    end
  end
  return out
end

-- One route list entry, checked and normalized; nil plus a reason code.
local function entry(json, r)
  if type(r) ~= "table" then return nil, "bad_entry" end
  if not sym(r.id, "^[a-z][a-z0-9_]*$") or #r.id > 32 then return nil, "bad_id" end
  if type(r.name) ~= "string" or #r.name < 1 or #r.name > 24 or not r.name:find("^[%w %-'%.]+$") then
    return nil, "bad_name"
  end
  if not Routes.KINDS[r.kind] then return nil, "bad_kind" end
  if r.theme ~= nil and not Routes.THEMES[r.theme] then
    if type(r.theme) == "string" then return nil, "theme_later" end
    return nil, "bad_theme"
  end
  local from, code = routeEnd(json, r.from, "from")
  if not from then return nil, code end
  local out = {
    id = r.id, name = r.name, kind = r.kind, theme = r.theme or "forest",
    mapId = Routes.mapId(r.id),
    from = from,
    classes = {}, music = nil,
  }
  if r.kind == "connecting" then
    -- MG2: the far end, in the other region
    if r.to == nil then return nil, "bad_to" end
    local to
    to, code = routeEnd(json, r.to, "to")
    if not to then return nil, code end
    out.to = to
  elseif r.to ~= nil then
    return nil, "bad_to"
  end
  out.unset = from.unset == true or (out.to ~= nil and out.to.unset == true) or nil
  -- generator settings: checked here, used by the builder (the export's
  -- `routes` resource) and the server
  local a = r.areas
  if a ~= nil then
    if type(a) ~= "table" or not range(a.main, 1, 8) or (a.side ~= nil and not percent(a.side)) then
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
      local classes
      classes, code = classList(t.classes)
      if not classes then return nil, code end
      out.classes = classes
    end
  end
  local d = r.dungeon
  if d ~= nil then
    if type(d) ~= "table" or (d.chance ~= nil and not percent(d.chance))
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
  if type(doc) ~= "table" or not Routes.VERSIONS[doc.version] or type(doc.routes) ~= "table" then
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

-- The exit's plan for one end of an entry on its map's record (def) and the
-- engine's map query over it (q: walkable/water per cell, from
-- kb_engine.mapQuery): the entry's `from` end, its `to` end when `which` is
-- "to" (MG2), or `e` itself when it is a bare end. `occupied` (optional) is
-- a set of cellKey -> true the clearing must not cover (warps, signs,
-- objects, hidden items). Returns
--   { blocks, source, cleared = { {bx, by} }, triggers = { [key] = true },
--     triggerList = { {x, y} }, back = { x, y, facing }, exitFacing }
-- or nil plus a code: spot_outside, spot_unwalkable, outside_map,
-- not_trees, occupied. (not_walkable_after is check() below, on the
-- patched record.)
function Routes.plan(e, def, q, occupied, which)
  local f = (which == "to" and e.to) or e.from or e
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
-- 4788; the zone's wild Pokemon are Minecraft's anyway, WR5). `mapId` (MG2)
-- is the route map's id when it isn't the entry's own (a `to` end's).
function Routes.routeMapDef(e, p, def, mapId)
  local out = {
    id = mapId or e.mapId, width = 1, height = 1, blocks = { p.source }, tileset = def.tileset,
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

-- The region an end's half is built in: its own `region`, else the default
-- region of its first game in the catalogue (lib/kb_games.lua), else nil.
function Routes.endRegion(e, games)
  if e.region then return e.region end
  local g = e.gameList and e.gameList[1]
  local entry = g and type(games) == "table" and type(games.games) == "table" and games.games[g] or nil
  return type(entry) == "table" and type(entry.region) == "string" and entry.region or nil
end

-- opts:
--   json      kb_json
--   text      data/routes.json's text (nil when the file is missing)
--   content   mod.content (registries: maps, tilesets, map_scripts,
--             trainers, map_songs, music, field)
--   world     mod.world (queueScript, warpTo)
--   game      the running game ("red", "blue", ...)
--   games     lib/kb_games.lua (MG2: an end's default region), optional
--   mapQuery  kb_engine.mapQuery(def, tileset)
--   engine    kb_engine instance (MG8: routeInstall, when the adapter has its own route ends), optional
--   log       kb_log logger (optional)
-- Returns the runtime: zones (by route id -- a connecting route whose two
-- ends this game both registers keeps the `from` end's zone there and the
-- `to` end's under "<id>:to" -- and by route map id), the entries, the
-- problems and stats. Never throws.
function Routes.install(opts)
  local json, content, world, log = opts.json, opts.content or {}, opts.world, opts.log
  local rt = { zones = {}, byMap = {}, byTown = {}, entries = {}, problems = {}, world = world, games = opts.games,
               stats = { listed = 0, installed = 0, skipped = 0, unset = 0, otherGame = 0, classes = 0,
                         warpsOut = 0, warpsBack = 0, warpsBusy = 0, closed = 0, connecting = 0 } }
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

  -- Installs one end of an entry as a zone: `which` is "from" or "to"; the
  -- `to` end's zone is keyed "<id>:to" and its route map KB_ROUTE_<ID>_TO
  -- (both ends in one game: a test world).
  local function installEnd(e, which)
    local f = which == "to" and e.to or e.from
    local zoneKey = which == "to" and (e.id .. ":to") or e.id
    local mapId = Routes.mapId(e.id, which == "to" and "to" or nil)
    local def = get("maps", f.map)
    local ts = type(def) == "table" and get("tilesets", def.tileset) or nil
    local q = def and ts and opts.mapQuery and opts.mapQuery(def, ts) or nil
    if not q then
      problem(e.id, def and "no_query" or "no_map")
      return
    end
    local hidden = get("field", "hiddenItems")
    local p, code = Routes.plan(e, def, q, Routes.occupiedCells(def, type(hidden) == "table" and hidden[f.map] or nil), which)
    if p then
      local patched = {}
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
      return
    end
    if get("maps", mapId) ~= nil then
      problem(e.id, "map_taken")
      return
    end
    local zone = { id = e.id, key = zoneKey, which = which, entry = e, finish = f, mapId = mapId, town = f.map,
                   plan = p, active = false, classes = {} }
    -- 1. the route map; 2. its step script and the town's; 3. the
    -- town's blocks. A later failure undoes the route map (a tombstone)
    -- and leaves the scripts inert (zone.active stays false).
    local okMap = call("maps", "register", mapId, Routes.routeMapDef(e, p, def, mapId))
    local okScripts = okMap and call("map_scripts", "register", mapId, {
      onStep = function(_, _, x, y)
        if zone.active and x == Routes.CELL_A.x and y == Routes.CELL_A.y then
          return Routes.warpTo(rt, zone, "back")
        end
        return false
      end,
    })
    if okScripts and not townScripted[f.map] then
      okScripts = call("map_scripts", "register", f.map, { onStep = townStep(f.map) })
      townScripted[f.map] = okScripts or nil
    end
    local okPatch = okScripts and call("maps", "patch", f.map, { blocks = p.blocks })
    if not okPatch then
      if okMap then call("maps", "remove", mapId) end
      problem(e.id, not okMap and "map_rejected" or not okScripts and "scripts_rejected" or "patch_rejected")
      return
    end
    zone.active = true
    rt.zones[zoneKey] = zone
    rt.byMap[mapId] = zone
    rt.byTown[f.map] = rt.byTown[f.map] or {}
    table.insert(rt.byTown[f.map], zone)
    rt.stats.installed = rt.stats.installed + 1
    if e.kind == "connecting" then rt.stats.connecting = rt.stats.connecting + 1 end
    -- the trainer classes: the end's own, else the route's (a class that
    -- can't be copied only takes that class's battles away)
    zone.classList = f.classes or e.classes
    for _, cls in ipairs(zone.classList) do
      local id = Routes.classId(cls)
      if classDone[cls] == nil then
        local rec = get("trainers", id) == nil and Routes.classRecord(cls, get("trainers", cls)) or nil
        classDone[cls] = rec ~= nil and call("trainers", "register", id, rec) or false
        if classDone[cls] then rt.stats.classes = rt.stats.classes + 1 end
      end
      if classDone[cls] then zone.classes[cls] = id end
    end
    -- the song (the end's own -- null means none -- else the route's), only
    -- when the music id exists (a dangling one would fail the whole mod
    -- after the merge)
    local music = f.music
    if music == nil then music = e.music end
    if music and get("music", music) ~= nil then call("map_songs", "register", mapId, music) end
    if log then
      log.info("routes.installed", { route = e.id, ["end"] = which, depth = f.depth, classes = rt.stats.classes })
    end
  end

  -- MG8: one end installed by the adapter (K2-K4): the zone record goes to
  -- it, nothing to the registries; its classes are kept by symbol (the
  -- adapter finds a trainer of the class at battle time)
  local function installOwn(e, which, own)
    local f = which == "to" and e.to or e.from
    local zoneKey = which == "to" and (e.id .. ":to") or e.id
    local mapId = Routes.mapId(e.id, which == "to" and "to" or nil)
    local zone = { id = e.id, key = zoneKey, which = which, entry = e, finish = f, mapId = mapId, town = f.map,
                   plan = { cleared = {}, triggers = {}, triggerList = {}, ready = false }, active = true,
                   classes = {}, own = true }
    local ok, accepted, code = pcall(own, zone, content)
    if not ok or accepted ~= true then
      problem(e.id, ok and (type(code) == "string" and code or "rejected") or "adapter_failed")
      return
    end
    rt.zones[zoneKey] = zone
    rt.byMap[mapId] = zone
    rt.byTown[f.map] = rt.byTown[f.map] or {}
    table.insert(rt.byTown[f.map], zone)
    rt.stats.installed = rt.stats.installed + 1
    rt.stats.own = (rt.stats.own or 0) + 1
    if e.kind == "connecting" then rt.stats.connecting = rt.stats.connecting + 1 end
    zone.classList = f.classes or e.classes
    for _, cls in ipairs(zone.classList) do zone.classes[cls] = cls end
    if log then log.info("routes.installed", { route = e.id, ["end"] = which, depth = f.depth, own = true }) end
  end

  local own = opts.engine ~= nil and rawget(opts.engine, "routeInstall") or nil
  if type(own) ~= "function" then own = nil end
  for _, e in ipairs(entries) do
    -- the ends this game registers: a free-roam zone's exit, or the end(s)
    -- of a connecting route whose `games` name it (MG2)
    local ends = {}
    if e.from.games[opts.game] then ends[#ends + 1] = "from" end
    if e.to and e.to.games[opts.game] then ends[#ends + 1] = "to" end
    if #ends == 0 then
      rt.stats.otherGame = rt.stats.otherGame + 1
    elseif e.unset then
      rt.stats.unset = rt.stats.unset + 1
      rt.problems[#rt.problems + 1] = { id = e.id, code = "unset" }
    else
      for _, which in ipairs(ends) do
        if own then installOwn(e, which, own) else installEnd(e, which) end
      end
    end
  end
  return rt
end

-- The route map ids this boot installed (act/kb_region.lua: a resume may warp
-- onto one of them).
function Routes.routeMapIds(rt)
  local out = {}
  for id in pairs(rt and rt.byMap or {}) do out[id] = true end
  return out
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
    if zone.active and not zone.own then -- (an adapter's own end bakes its clearing into its own export, MG8)
      local p = zone.plan
      blocks = blocks or copyList(p.blocks)
      for _, b in ipairs(p.cleared) do blocks[b[2] * p.width + b[1] + 1] = p.source end
    end
  end
  return blocks
end

-- One end of a connecting route as the export names it: its region (the
-- catalogue's default when the list gives none) and its games, in list order.
local function exportEnd(json, rt, f)
  return json.object({ region = Routes.endRegion(f, rt.games) or "kanto", games = json.array(f.gameList) })
end

function Routes.exportData(json, rt)
  local list = {}
  local connecting = false
  for _, e in ipairs(rt and rt.entries or {}) do
    -- one item per end this game registered (a connecting route both of
    -- whose ends it holds, a test world, lists two items with its id: the
    -- export schema puts no uniqueness rule on `id`, and `end` tells them
    -- apart)
    for _, which in ipairs(Routes.ENDS) do
    local zone = rt.zones[which == "to" and (e.id .. ":to") or e.id]
    -- (MG8: an adapter's own end goes out once it is planned)
    if zone and zone.active and zone.which == which and zone.plan.ready ~= false then
      local p, f = zone.plan, zone.finish or e.from
      local cleared, triggers = {}, {}
      for _, b in ipairs(p.cleared) do cleared[#cleared + 1] = { b[1], b[2] } end
      for _, c in ipairs(p.triggerList) do triggers[#triggers + 1] = { c[1], c[2] } end
      local raw = e.raw
      local item = {
        id = e.id, name = e.name, kind = e.kind, theme = e.theme,
        town = { map = f.map, cleared = cleared, triggers = triggers,
                 back = { x = p.back.x, y = p.back.y, facing = p.back.facing } },
        routeMap = { id = zone.mapId, width = 1, height = 1,
                     a = { x = Routes.CELL_A.x, y = Routes.CELL_A.y },
                     arrive = { x = Routes.CELL_X1.x, y = Routes.CELL_X1.y, facing = Routes.ARRIVE_FACING } },
      }
      if e.kind == "connecting" then
        connecting = true
        item.ends = json.object({ from = exportEnd(json, rt, e.from), to = exportEnd(json, rt, e.to) })
        item["end"] = which
      end
      local classes = {}
      for _, cls in ipairs(zone.classList or e.classes) do
        if zone.classes[cls] then classes[#classes + 1] = cls end
      end
      item.trainers = json.object({
        perArea = type(raw.trainers) == "table" and raw.trainers.perArea or { 1, 3 },
        rematch = type(raw.trainers) == "table" and raw.trainers.rematch or "each_visit",
        classes = json.array(classes),
      })
      item.areas = json.object({
        main = type(raw.areas) == "table" and raw.areas.main or { 3, 4 },
        side = type(raw.areas) == "table" and raw.areas.side or 50,
      })
      item.levels = json.object({
        from = "neighbours",
        perArea = type(raw.levels) == "table" and raw.levels.perArea or 1,
        maxRise = type(raw.levels) == "table" and raw.levels.maxRise or 3,
      })
      item.dungeon = json.object({
        chance = type(raw.dungeon) == "table" and raw.dungeon.chance or 60,
        floors = "by_level",
      })
      list[#list + 1] = item
    end
    end
  end
  -- a free-roam list keeps version 1 (and its bytes); a connecting route makes it 2
  return { version = connecting and Routes.VERSION_CONNECTING or Routes.VERSION, routes = json.array(list) }
end

return Routes
