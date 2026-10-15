-- gen2 routes (GEN2_BRIDGE_PLAN.md §5.11, phase G2H; decision J9): a Wild
-- Routes end on Gen 2, and the blackout of a battle lost in Cobblemon.
--
-- Gen 1's end (lib/kb_routes.lua) is a route map of the mod's own, map scripts
-- and a patched town. Gen 2 has two of the three (spike G2-S3): `maps:register`
-- adds a map the World warps into and walks (`maps` routes to data.gen2Maps,
-- the table the World reads, src/mods/Schemas.lua:1928-1977), and
-- `maps:patch(<TOWN>, { blocks })` changes the pristine blocks every load of
-- the town restores (World:restoreBlocks only reverts replaceBlock's edits).
-- Map scripts are dropped on Gen 2, so the core hands such an end to this part
-- (kb_routes.lua's routeInstall, in the entry chunk, with mod.content), which:
--
-- * plans the clearing with Routes.plan's arithmetic on the town's own blocks
--   (Gen 2's blocks are 2x2 cells, the route planner's unit): the exit block
--   plain walkable ground (every cell walkable, no grass, warp, ledge, ice,
--   current or counter), then `depth` blocks of trees inside the map (every
--   cell a wall: no water, no Cut or Headbutt tree), none of their cells under
--   a warp, an object, a bg event (signs, hidden items) or a coord event; each
--   cleared block becomes a copy of the exit block. The far side of the last
--   block triggers the way out; its near side is where the way back lands;
-- * registers the route map KB_ROUTE_<ID>[_TO]: the town's record with one
--   block of the exit's floor, no warps, objects, events, callbacks or
--   connections (A = (0, 0), x1 = (1, 0); the lower row is closed by the core's
--   movement.collision wrapper, Routes.closedMove), then patches the town's
--   blocks (a failure undoes the route map); the export bakes the clearing in
--   (b.routeBlocks) and the story part's pristine copy, taken at mods.loaded,
--   already holds it;
-- * takes the steps (world.stepped, main.lua): a trigger cell queues the
--   engine's own warp to x1 facing right, A the warp to the clearing's back
--   cell facing the town (mod.world:queueScript, src/world/gen2/WorldAPI.lua:
--   636-730; the bridge follows both as script warps, as on Gen 1); a resume
--   onto the route map is the core's own queued warp (kb_region);
-- * runs the zone's acts: an item into the bag (Bag.add) with a found line
--   (Game2:say); a trainer battle through World:startScriptedBattle with the
--   class's first trainer (Trainers.lookup) as the template and the request's
--   party as its roster (Trainers.party builds it: 9/8/8/8 DVs, the given
--   moves or the level's), which battle.lua holds for Cobblemon like any
--   scripted trainer; its onDone carries the outcome.
--
-- The blackout (the server's route.blackout after a wild battle lost in
-- Cobblemon, J/server/WildBlackouts.java): the battle tail's whiteout arm
-- (World:startBattle's closure, src/world/gen2/World.lua:7084-7105): heal the
-- party, HalveMoney and GetWhiteoutSpawn (none during the Bug-Catching
-- Contest), world.blacked_out, then the warp to the spawn point as a
-- MAPSETUP_WARP load.
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Routes2 = {}

Routes2.CELL_A = { x = 0, y = 0 }
Routes2.CELL_X1 = { x = 1, y = 0 }
Routes2.ARRIVE_FACING = "right"
-- MAPSETUP.WARP (src/world/gen2/World.lua:187-199), the whiteout's load
Routes2.MAPSETUP_WARP = 0xF1
-- the fields a slim route map keeps when the registry refuses the town's whole record
Routes2.SLIM = { "tileset", "borderBlock", "palette", "environment", "music", "landmark", "fishGroup" }
local DIRS = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
local OPPOSITE = { up = "down", down = "up", left = "right", right = "left" }

local floor = math.floor
local function whole(v, lo, hi) return type(v) == "number" and v == floor(v) and v >= lo and v <= hi end
local function key(x, y) return y * 1024 + x end

-- What a COLL byte is to a clearing (src/world/gen2/Permissions.lua): "plain"
-- (walkable ground nothing happens on), "land" (walkable, but grass, a warp,
-- a ledge, ice, a current or a counter), "water", "tree" (a wall a clearing
-- may open) or "special" (a Cut or Headbutt tree, a waterfall, a whirlpool:
-- the story's, never cleared).
function Routes2.kind(P, ask, coll)
  if ask(P, "isWater", false, coll) == true then return "water" end
  for _, fn in ipairs({ "isCutTree", "isCuttable", "isHeadbuttTree", "isWaterfall", "isWhirlpool" }) do
    if ask(P, fn, false, coll) == true then return "special" end
  end
  if ask(P, "isWalkable", false, coll) ~= true then return "tree" end
  for _, fn in ipairs({ "isGrass", "isSuperTallGrass", "isEncounterCollision", "isWarpCollision", "isIce", "isCounter" }) do
    if ask(P, fn, false, coll) == true then return "land" end
  end
  if ask(P, "currentDirection", nil, coll) ~= nil then return "land" end
  local ledge = ask(P, "ledgeFacings", nil, coll)
  if type(ledge) == "table" and next(ledge) ~= nil then return "land" end
  return "plain"
end

-- The cells nothing may cover: the map's warps, objects, bg events and coord events.
function Routes2.occupied(def)
  local out = {}
  for _, list in ipairs({ def.warps, def.objects, def.bgEvents, def.coordEvents, def.signs }) do
    for _, o in ipairs(type(list) == "table" and list or {}) do
      local x, y = type(o) == "table" and tonumber(o.x) or nil, type(o) == "table" and tonumber(o.y) or nil
      if x and y and x >= 0 and y >= 0 then out[key(floor(x), floor(y))] = true end
    end
  end
  return out
end

-- The clearing for an end f = { x, y, facing, depth } on a map record def
-- (width, height in blocks; blocks[by * width + bx + 1]) over its tileset ts
-- (collision[block + 1] = the four cells' COLL bytes, top-left, top-right,
-- bottom-left, bottom-right: Map:cellCollision, src/world/gen2/Map.lua:55-63),
-- with kindOf(coll) Routes2.kind and occ the cells nothing may cover; `opened`
-- (a hot reload) is the block an earlier install opened the clearing with,
-- which counts as trees. Returns
-- { blocks (the town's, patched), source, width, cleared = { {bx, by} },
-- triggerList = { {x, y} }, triggers = { [key] = true }, back = { x, y,
-- facing }, exitFacing } or nil plus a code: bad_spot, bad_map, spot_outside,
-- spot_unwalkable, outside_map, not_trees, occupied.
function Routes2.plan(f, def, ts, kindOf, occ, opened)
  occ = occ or {}
  local d = DIRS[f.facing]
  if not (d and whole(f.x, 0, 1023) and whole(f.y, 0, 1023) and whole(f.depth, 1, 4)) then return nil, "bad_spot" end
  local w, h = floor(tonumber(def.width) or 0), floor(tonumber(def.height) or 0)
  if not (w >= 1 and h >= 1 and type(def.blocks) == "table" and #def.blocks == w * h and type(ts) == "table"
      and type(ts.collision) == "table") then
    return nil, "bad_map"
  end
  local function kinds(bx, by)
    local block = floor(tonumber(def.blocks[by * w + bx + 1]) or 0)
    local quad = block ~= 0 and ts.collision[block + 1] or nil
    local out = {}
    for q = 1, 4 do
      local c = type(quad) == "table" and tonumber(quad[q]) or nil
      out[q] = c and kindOf(floor(c)) or "tree" -- block 0 and a missing quad are walls (Map.lua:57-60)
    end
    return out, block
  end
  local function cells(bx, by)
    return { { 2 * bx, 2 * by }, { 2 * bx + 1, 2 * by }, { 2 * bx, 2 * by + 1 }, { 2 * bx + 1, 2 * by + 1 } }
  end
  local bx, by = floor(f.x / 2), floor(f.y / 2)
  if bx >= w or by >= h then return nil, "spot_outside" end
  local spot, source = kinds(bx, by)
  for q = 1, 4 do
    if spot[q] ~= "plain" then return nil, "spot_unwalkable" end
  end
  for _, c in ipairs(cells(bx, by)) do
    if occ[key(c[1], c[2])] then return nil, "occupied" end
  end
  local blocks = {}
  for i, v in ipairs(def.blocks) do blocks[i] = v end
  local cleared = {}
  for i = 1, f.depth do
    local cx, cy = bx + d[1] * i, by + d[2] * i
    if cx < 0 or cy < 0 or cx >= w or cy >= h then return nil, "outside_map" end
    local k, id = kinds(cx, cy)
    for q = 1, 4 do
      if k[q] ~= "tree" and id ~= opened then return nil, "not_trees" end
    end
    for _, c in ipairs(cells(cx, cy)) do
      if occ[key(c[1], c[2])] then return nil, "occupied" end
    end
    blocks[cy * w + cx + 1] = source
    cleared[#cleared + 1] = { cx, cy }
  end
  -- the last block's far side triggers the way out; its near side is where
  -- the way back lands, facing the town (Routes.plan's)
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
  for _, c in ipairs(far) do triggers[key(c[1], c[2])] = true end
  return {
    blocks = blocks, source = source, width = w, cleared = cleared, triggerList = far, triggers = triggers,
    back = { x = near[1], y = near[2], facing = OPPOSITE[f.facing] }, exitFacing = f.facing,
  }
end

-- The route map's record: the town's (its tileset, border, palette, environment,
-- music, landmark) with one block of the clearing's floor and nothing else on
-- it; `slim` keeps only the registry's own fields and Routes2.SLIM. Its map
-- group, number and index stay off, so nothing that looks a map up by them
-- finds it instead of the town; `kbRoute` names the zone it serves.
function Routes2.routeMapDef(town, plan, mapId, slim, key)
  local out = {}
  if slim then
    for _, k in ipairs(Routes2.SLIM) do out[k] = town[k] end
  else
    for k, v in pairs(town) do out[k] = v end
    out.group, out.map, out.index, out.label, out.blockdata = nil, nil, nil, nil, nil
    out.callbacks, out.sceneScripts, out.coordEvents, out.bgEvents = {}, {}, {}, {}
  end
  out.id, out.width, out.height, out.blocks = mapId, 1, 1, { plan.source }
  out.warps, out.objects, out.signs, out.connections = {}, {}, {}, {}
  out.outdoor = true
  out.kbRoute = key -- whose it is: a hot reload finds its own (the registry keeps a record's other fields)
  return out
end

function Routes2.install(b)
  local def = b.def

  -- This instance's zones by key: the core's zone records, handed over in the
  -- entry chunk (a hot reload installs them again; the registries then refuse
  -- a second route map, map_taken).
  local zones = {}
  local counts = { warpsOut = 0, warpsBack = 0, busy = 0, given = 0, battles = 0, blackouts = 0 }

  local function data()
    local g = b.game
    return type(g) == "table" and type(g.data) == "table" and g.data or nil
  end
  local function registry(content, name)
    local r = type(content) == "table" and content[name] or nil
    return type(r) == "table" and r or nil
  end
  local function get(reg, id)
    if not (reg and type(reg.get) == "function") then return nil end
    local ok, v = pcall(reg.get, reg, id)
    return ok and type(v) == "table" and v or nil
  end
  local function call(reg, method, ...)
    if not (reg and type(reg[method]) == "function") then return false end
    local ok, res = pcall(reg[method], reg, ...)
    return ok and res ~= false
  end

  -- ---- the end (kb_routes.lua's routeInstall)

  -- An end the core hands over in the entry chunk, with mod.content: its zone
  -- record (id, key, mapId, town = the reported id, finish = { map, x, y,
  -- facing, depth }, plan, active). True when installed, else false plus a
  -- code (bad_zone, bad_spot, no_map, no_tileset, a plan's code, map_taken,
  -- map_rejected, patch_rejected).
  def("routeInstall", function(zone, content)
    if type(zone) ~= "table" or type(zone.key) ~= "string" or type(zone.finish) ~= "table"
        or not b.symbol(zone.mapId) then
      return false, "bad_zone"
    end
    local f = zone.finish
    if not (b.symbol(f.map) and whole(f.x, 0, 1023) and whole(f.y, 0, 1023) and DIRS[f.facing] and whole(f.depth, 1, 4)) then
      return false, "bad_spot"
    end
    local maps, tilesets = registry(content, "maps"), registry(content, "tilesets")
    local town = b.int(f.map)
    local tdef = get(maps, town)
    if not tdef then return false, "no_map" end
    local ts = get(tilesets, tdef.tileset)
    if not ts then return false, "no_tileset" end
    local P = b.w2("Permissions")
    local function kindOf(c) return Routes2.kind(P, b.ask, c) end
    -- a hot reload: `maps` writes into the live data (it outlives this instance), so the route map and the
    -- clearing are the earlier install's; the plan is made on the town as it stands
    local existing = get(maps, zone.mapId)
    if existing ~= nil then
      local floorBlock = type(existing.blocks) == "table" and tonumber(existing.blocks[1]) or nil
      if existing.kbRoute ~= zone.key or not floorBlock then return false, "map_taken" end
      local plan, code = Routes2.plan(f, tdef, ts, kindOf, Routes2.occupied(tdef), floorBlock)
      if not plan then return false, code end
      for k, v in pairs(plan) do zone.plan[k] = v end
      zone.plan.ready = true
      zone.internalTown = town
      zones[zone.key] = zone
      if b.log then b.log.info("routes.own_reinstalled", { route = zone.id }) end
      return true
    end
    local plan, code = Routes2.plan(f, tdef, ts, kindOf, Routes2.occupied(tdef))
    if not plan then return false, code end
    -- 1. the route map (the town's whole record, else a slim one); 2. the town's blocks.
    local okMap = call(maps, "register", zone.mapId, Routes2.routeMapDef(tdef, plan, zone.mapId, false, zone.key))
      or call(maps, "register", zone.mapId, Routes2.routeMapDef(tdef, plan, zone.mapId, true, zone.key))
    if not okMap then return false, "map_rejected" end
    if not call(maps, "patch", town, { blocks = plan.blocks }) then
      call(maps, "remove", zone.mapId)
      return false, "patch_rejected"
    end
    for k, v in pairs(plan) do zone.plan[k] = v end
    zone.plan.ready = true
    zone.internalTown = town
    zones[zone.key] = zone
    if b.log then b.log.info("routes.own_planned", { route = zone.id, cleared = #plan.cleared }) end
    return true
  end)

  -- The export asks first (kb_export.lua): every zone was planned at install; how many.
  def("routePlan", function()
    local n = 0
    for _, z in pairs(zones) do
      if z.plan.ready then n = n + 1 end
    end
    return n
  end)

  -- The blocks a clearing gives `map` (an engine id; export.lua bakes them), the
  -- town's pristine blocks with every clearing laid over them, or nil.
  function b.routeBlocks(map)
    local out
    for _, z in pairs(zones) do
      if z.internalTown == map and z.active ~= false and z.plan.ready then
        if not out then
          local m = b.pristineMap and b.pristineMap(map) or nil
          local base = type(m) == "table" and type(m.blocks) == "table" and m.blocks or z.plan.blocks
          out = {}
          for i, v in ipairs(base) do out[i] = v end
        end
        for _, c in ipairs(z.plan.cleared) do out[c[2] * z.plan.width + c[1] + 1] = z.plan.source end
      end
    end
    return out
  end

  -- ---- the steps (world.stepped)

  -- The engine's own warp, queued (one script at a time; true when queued).
  local function queueWarp(map, x, y, facing)
    local world = b.api
    if type(world) ~= "table" or type(world.queueScript) ~= "function" then return false end
    local ok, queued = pcall(world.queueScript, world, { { "warp", map, x, y, facing } })
    return ok and queued ~= false and queued ~= nil
  end

  -- world.stepped: a zone's trigger cell is the way out (x1 of its route map,
  -- facing into the zone), A of a route map the way back (the clearing's back
  -- cell, facing the town).
  def("routeStepped", function()
    if not b.inField() then return end
    local w = b.ow()
    local p = w.player
    local map = w.map.id
    local x, y = tonumber(p.cellX), tonumber(p.cellY)
    if not (map and x and y) then return end
    for _, z in pairs(zones) do
      if z.active ~= false and z.plan.ready then
        if map == z.internalTown and z.plan.triggers[key(x, y)] then
          if queueWarp(z.mapId, Routes2.CELL_X1.x, Routes2.CELL_X1.y, Routes2.ARRIVE_FACING) then
            counts.warpsOut = counts.warpsOut + 1
          else
            counts.busy = counts.busy + 1
          end
          return
        elseif map == z.mapId and x == Routes2.CELL_A.x and y == Routes2.CELL_A.y then
          local back = z.plan.back
          if queueWarp(z.internalTown, back.x, back.y, back.facing) then
            counts.warpsBack = counts.warpsBack + 1
          else
            counts.busy = counts.busy + 1
          end
          return
        end
      end
    end
  end)

  def("routeCounts", function()
    local out = {}
    for k, v in pairs(counts) do out[k] = v end
    return out
  end)

  -- ---- the zone's acts (kb_route): give and battle

  -- (true) once started, or (false, reason): busy, unknown_item, unknown_class, bad_party, failed. The request's
  -- names are Gen 2's own (items, trainer classes, species and moves: data.items, data.trainers.classes,
  -- data.pokemon, data.moves).
  def("routeRun", function(op, req)
    if type(req) ~= "table" then return false, "failed" end
    if not b.inField() then return false, "busy" end
    local g, w, d = b.game, b.ow(), data()
    if not d then return false, "busy" end
    local function say(text, after)
      if type(text) == "string" and text ~= "" and type(g.say) == "function" then
        if pcall(g.say, g, text, after) then return end
      end
      after()
    end
    if op == "give" then
      local sym = tostring(req.item)
      local item = type(d.items) == "table" and d.items[sym] or nil
      if type(item) ~= "table" then return false, "unknown_item" end
      local s = b.saveObject()
      local Bag = b.mod("src.inventory.Bag")
      local okAdd, added = b.call(Bag, "add", s, sym, 1, d)
      if not (okAdd and added == true) then
        -- a full pocket: the core compares the counts and answers bag_full
        req.onDone()
        return true
      end
      b.dropView()
      counts.given = counts.given + 1
      local who = type(s) == "table" and type(s.player) == "table" and type(s.player.name) == "string" and s.player.name or nil
      local name = type(item.name) == "string" and item.name or sym
      say(who and ("%s found %s!"):format(who, name) or nil, function() req.onDone() end)
      return true
    elseif op == "battle" then
      local classes = type(d.trainers) == "table" and d.trainers.classes or nil
      local cls = type(classes) == "table" and classes[tostring(req.class)] or nil
      local index = type(cls) == "table" and tonumber(cls.index) or nil
      local rec = index and b.ask(b.w2("Trainers"), "lookup", nil, d.trainers, index, 1) or nil
      if type(rec) ~= "table" then return false, "unknown_class" end
      local roster = {}
      for i, m in ipairs(type(req.party) == "table" and req.party or {}) do
        local species, level = tostring(m.species), tonumber(m.level)
        if type(d.pokemon) ~= "table" or type(d.pokemon[species]) ~= "table" or not whole(level, 1, 100) or i > 6 then
          return false, "bad_party"
        end
        local moves
        for _, mv in ipairs(type(m.moves) == "table" and m.moves or {}) do
          if type(d.moves) == "table" and type(d.moves[tostring(mv)]) == "table" then
            moves = moves or {}
            if #moves < 4 then moves[#moves + 1] = tostring(mv) end
          end
        end
        roster[i] = { species = species, level = level, moves = moves }
      end
      if #roster == 0 then return false, "bad_party" end
      rec.roster = roster
      if type(w.startScriptedBattle) ~= "function" then return false, "failed" end
      local function start()
        local ok, started = pcall(w.startScriptedBattle, w, rec, nil, function(outcome) req.onDone(outcome or "run") end)
        if not ok or started == false then req.onDone("run") end
      end
      counts.battles = counts.battles + 1
      say(req.line, start)
      return true
    end
    return false, "failed"
  end)

  -- Gen 1's routes group: Gen 2's zones run their battles through routeRun.
  def("writeTrainerParty", function() return false end)
  def("scriptBattleResult", function() return nil end)

  -- ---- the blackout

  -- true, or false plus not_ingame | busy | failed (gen1.lua's contract).
  def("blackout", function()
    if not b.inField() then return false, "not_ingame" end
    local w, g = b.ow(), b.game
    local okBusy, isBusy = b.method(w, "busy")
    if okBusy and isBusy then return false, "busy" end
    local okHeal = b.method(w, "healParty")
    if not okHeal then return false, "failed" end
    if b.ask(b.c2("BugContest"), "isActive", false, g.save) ~= true then
      local CallAsm = b.mod("src.script.gen2.CallAsm")
      b.call(CallAsm, "run", w, "HalveMoney")
      b.call(CallAsm, "run", w, "GetWhiteoutSpawn")
    end
    local target = select(2, b.method(w, "healPoint"))
    b.call(b.mod("src.mods.Runtime"), "emit", "world.blacked_out", { save = g.save, healTarget = target })
    local okWarp = b.method(w, "runMapSetup", Routes2.MAPSETUP_WARP, function()
      w:warpToSpawn()
      return true
    end)
    b.dropView()
    if not okWarp then return false, "failed" end
    counts.blackouts = counts.blackouts + 1
    return true
  end)
end

return Routes2
