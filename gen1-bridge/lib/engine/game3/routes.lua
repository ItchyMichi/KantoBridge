-- game3 routes (GEN3_BRIDGE_PLAN.md §4.10, G3F; MG8: docs/specs/HOENN_PASS_PLAN.md
-- K2-K4).
--
-- The blackout (G3F): the server's route.blackout after a lost habitat
-- battle (J/server/WildBlackouts.java), the engine's own whiteout as its
-- battle writeback runs it on a loss (battle_bridge.lua:257-279): half the
-- money (BattleBridge.applyWhiteoutMoneyLoss, :122-132) and the respawn at
-- the heal point (Field.respawnAtHeal, field.lua:2054-2090).
--
-- A Wild Routes end on Gen 3 (MG8). Gen 1's end (lib/kb_routes.lua) is a
-- route map of the mod's own, map scripts and a patched town; Gen 3 has none
-- of them (spike S2: a registered map is never entered, map scripts never
-- run, a patched warp is inert). So the core hands such an end to this part
-- (kb_routes.lua's routeInstall), which:
--
-- * plans the clearing with Routes.plan's arithmetic on the town's own cells
--   (2x2 blocks of metatiles, so the builder's units stay): the exit block
--   walkable plain ground of one elevation, then `depth` blocks of trees
--   (collision 0x07) inside the map, none of their cells under a warp, an
--   object, a sign or a trigger; each cleared cell copies the exit block's
--   metatile at its place and its elevation. The far side of the last block
--   triggers the way out; its near side is where the way back lands;
-- * opens the clearing on every load of the town (a Gen 3 map load clears
--   every cell override, field.lua:81-88): the elevation first (the layout's
--   override, layout_native.lua:124-131), then Field.setMetatile, which makes
--   the cell passable (field.lua:2194-2241); story.lua counts these cells as
--   the map's own (the export bakes them in, export.lua);
-- * parks the hidden player on the trigger cell when it steps there
--   (world.stepped; a Field lock, "kb_route", as the region park's) and from
--   then on reports the route map KB_ROUTE_<ID> at x1 (1, 0) facing right,
--   so the core, the client's zone following and the zone's acts work as on
--   Gen 1; entering is a script warp the core is told of (takeRouteEvents:
--   no engine warp runs, the town isn't reloaded);
-- * takes the step onto A (left from x1): the way back, the engine's warp
--   to the clearing's back cell facing the town (mod.world:warpTo, a map
--   load as a door's); any other step is a wall (the zone is the client's);
-- * enters the route map for a resume (a pass's arrival): the warp to the
--   trigger cell, parked once the town has loaded;
-- * keeps both warps whole: they are script warps to the core (warpDeparture
--   asks routeOwnWarp), and a warp within the town loads it again without
--   map.entered (map.lua:776-786, a same-map load), so each step checks
--   whether the clearing is still open and whether the warp has landed, and
--   tells the core of the arrival itself;
-- * runs the zone's acts: an item into the bag (Bag.add) with a found line
--   in the field's message box; a trainer battle through BattleBridge.start
--   with the first trainer of the class as the template and the request's
--   party (species and moves by Emerald's constant names), held for
--   Cobblemon by battle.lua (kbRoute: an abort answers too).
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Routes3 = {}

Routes3.LOCK = "kb_route"
Routes3.TREES = 0x07                              -- the cells a clearing opens (G3Cells' BLOCKED)
Routes3.WALK = { [0x00] = true, [0x2B] = true }   -- the exit block: plain ground or cave floor
Routes3.CELL_A = { x = 0, y = 0 }
Routes3.CELL_X1 = { x = 1, y = 0 }
Routes3.ARRIVE_FACING = "right"
Routes3.MAX_TRAINERS = 1023
local DIRS = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
local OPPOSITE = { up = "down", down = "up", left = "right", right = "left" }

local floor = math.floor
local function whole(v, lo, hi) return type(v) == "number" and v == floor(v) and v >= lo and v <= hi end
local function key(x, y) return y * 1024 + x end

-- The clearing for an end f = { x, y, facing, depth } on a layout (lay: the
-- engine's midLayout: width, trueWidth, trueHeight, cells[y*w+x+1] = { mid,
-- coll, elev }) with `occ` (key -> true) the cells nothing may cover.
-- Returns { cleared = { {bx, by} }, cells = { {x, y, mid, elev} },
-- triggerList = { {x, y} }, triggers = { [key] = true }, back = { x, y,
-- facing }, exitFacing, elev } or nil plus a code: bad_spot, spot_outside,
-- spot_unwalkable, spot_elevation, outside_map, not_trees, occupied.
function Routes3.plan(f, lay, occ)
  occ = occ or {}
  local d = DIRS[f.facing]
  if not (d and whole(f.x, 0, 1023) and whole(f.y, 0, 1023) and whole(f.depth, 1, 4)) then return nil, "bad_spot" end
  local w = floor(tonumber(lay.width) or 0)
  local tw = floor(tonumber(lay.trueWidth or lay.width) or 0)
  local th = floor(tonumber(lay.trueHeight or lay.height) or 0)
  local function cell(x, y)
    if x < 0 or y < 0 or x >= tw or y >= th or w < 1 then return nil end
    local c = lay.cells[y * w + x + 1]
    if type(c) ~= "table" then return nil end
    return floor(tonumber(c.mid) or 0), floor(tonumber(c.coll) or 0xff), floor(tonumber(c.elev) or 0)
  end
  local bx, by = floor(f.x / 2), floor(f.y / 2)
  local mids, elev = {}, nil
  for j = 0, 1 do
    for i = 0, 1 do
      local cx, cy = 2 * bx + i, 2 * by + j
      local mid, coll, e = cell(cx, cy)
      if mid == nil then return nil, "spot_outside" end
      if not Routes3.WALK[coll] then return nil, "spot_unwalkable" end
      if elev == nil then elev = e elseif e ~= elev then return nil, "spot_elevation" end
      if occ[key(cx, cy)] then return nil, "occupied" end
      mids[j * 2 + i + 1] = mid
    end
  end
  local cleared, cells = {}, {}
  for k = 1, f.depth do
    local cbx, cby = bx + d[1] * k, by + d[2] * k
    for j = 0, 1 do
      for i = 0, 1 do
        local cx, cy = 2 * cbx + i, 2 * cby + j
        local mid, coll = cell(cx, cy)
        if mid == nil then return nil, "outside_map" end
        if coll ~= Routes3.TREES then return nil, "not_trees" end
        if occ[key(cx, cy)] then return nil, "occupied" end
        cells[#cells + 1] = { x = cx, y = cy, mid = mids[j * 2 + i + 1], elev = elev }
      end
    end
    cleared[#cleared + 1] = { cbx, cby }
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
    cleared = cleared, cells = cells, triggerList = far, triggers = triggers,
    back = { x = near[1], y = near[2], facing = OPPOSITE[f.facing] }, exitFacing = f.facing, elev = elev,
  }
end

function Routes3.install(b)
  local def = b.def

  -- This instance's zones by key: the core's zone records, handed over at
  -- boot before any game is set (a hot reload installs them again).
  local zones = {}
  -- The part's state, kept on the Game3 object across a hot reload (b.state):
  -- the parked zone's key, a pending resume, the warp events for the core.
  local function rs()
    local st = b.state()
    if not st then return nil end
    if type(st.routes) ~= "table" then st.routes = {} end
    return st.routes
  end

  local function currentMap()
    local Map = b.g3("map")
    return type(Map) == "table" and Map.current or nil
  end

  -- The town's layout: the current map's own (the live one) or the game data's.
  local function layoutOf(map)
    if map == currentMap() then
      local mdef = b.ask(b.g3("map"), "currentDef", nil)
      local lay = type(mdef) == "table" and mdef.midLayout or nil
      if type(lay) == "table" and type(lay.cells) == "table" then return lay, mdef end
    end
    local g = b.game
    local data = type(g) == "table" and type(g.data) == "table" and g.data.maps or nil
    local mdef = type(data) == "table" and data[map] or nil
    if type(mdef) ~= "table" then return nil end
    local ok, lay = pcall(function() return mdef.midLayout end) -- lazy (dataset.lua:357-372)
    if not ok or type(lay) ~= "table" or type(lay.cells) ~= "table" then return nil end
    return lay, mdef
  end

  -- The cells nothing may cover: warps, objects, signs and other bg events,
  -- coord triggers (the map's record and the script bundle's events).
  local function occupied(map, mdef)
    local out = {}
    local function add(list)
      for _, o in ipairs(type(list) == "table" and list or {}) do
        local x, y = type(o) == "table" and tonumber(o.x) or nil, type(o) == "table" and tonumber(o.y) or nil
        if x and y and x >= 0 and y >= 0 then out[key(floor(x), floor(y))] = true end
      end
    end
    if type(mdef) == "table" then
      add(mdef.warps) add(mdef.objects) add(mdef.bgEvents) add(mdef.coordEvents)
    end
    local Space = b.g3("scripting.space")
    local bundle = type(Space) == "table" and Space.bundle or nil
    local ev = type(bundle) == "table" and type(bundle.events) == "table" and bundle.events[map] or nil
    if type(ev) == "table" then
      add(ev.objects) add(ev.objectEvents) add(ev.bgEvents) add(ev.coordEvents) add(ev.warps)
    end
    return out
  end

  -- Plans a zone when its town's cells are there: true when planned now or
  -- before; a spot that doesn't check out switches the zone off.
  local function planZone(z)
    if z.plan.ready then return true end
    if z.active == false then return false end
    local lay, mdef = layoutOf(z.town)
    if not lay then return false end
    local p, code = Routes3.plan(z.finish, lay, occupied(z.town, mdef))
    if not p then
      z.active, z.problem = false, code
      if b.log then b.log.warn("routes.own_failed", { route = z.id, code = code }) end
      return false
    end
    for k, v in pairs(p) do z.plan[k] = v end
    z.plan.ready = true
    return true
  end

  -- ---- the clearing

  -- Opens the clearings of `map`'s zones in the loaded layout; the count of cells.
  local function openClearings(map)
    if not b.inField() then return 0 end
    local F = b.g3("field")
    local mdef = b.ask(b.g3("map"), "currentDef", nil)
    local lay = type(mdef) == "table" and mdef.midLayout or nil
    local n = 0
    for _, z in pairs(zones) do
      if z.town == map and z.active ~= false and planZone(z) then
        for _, c in ipairs(z.plan.cells) do
          -- the elevation first (setMetatile keeps the cell's), then the cell, passable
          if type(lay) == "table" and type(lay.applyOverride) == "function" then
            pcall(lay.applyOverride, lay, c.x, c.y, c.mid, 0x00, c.elev)
          end
          if b.call(F, "setMetatile", c.x, c.y, c.mid, false) then n = n + 1 end
        end
      end
    end
    return n
  end

  -- The metatile a clearing gives (x, y) of `map`, or nil (story.lua: the
  -- export bakes these into the map, so they are the map's own).
  function b.routeBase(map, x, y)
    for _, z in pairs(zones) do
      if z.town == map and z.active ~= false and z.plan.ready then
        for _, c in ipairs(z.plan.cells) do
          if c.x == x and c.y == y then return c.mid end
        end
      end
    end
    return nil
  end

  -- The cells a clearing opens on `map` (export.lua bakes them): { [key] = { mid, elev } }.
  function b.routeCells(map)
    local out = {}
    for _, z in pairs(zones) do
      if z.town == map and z.active ~= false and planZone(z) then
        for _, c in ipairs(z.plan.cells) do out[key(c.x, c.y)] = { mid = c.mid, elev = c.elev } end
      end
    end
    return out
  end

  -- ---- the park on the route map

  local function locked()
    local F = b.g3("field")
    return type(F) == "table" and type(F._locks) == "table" and F._locks[Routes3.LOCK] ~= nil
  end

  -- The zone the hidden player stands for (parked on its trigger cell), or nil.
  local function parked()
    local R = rs()
    local z = R and R.park and zones[R.park] or nil
    if R and R.park and not z then R.park = nil end
    return z, R
  end

  local function park(z, announce)
    local R = rs()
    R.park = z.key
    b.call(b.g3("field"), "lock", Routes3.LOCK)
    if announce then
      -- the core's warp messages for it (kb_warp: a script warp onto the route map)
      R.events = { warped = { kind = "script" }, entered = { { map = z.mapId, via = "warp" } } }
    end
    if b.log then b.log.info("routes.parked", { route = z.id }) end
  end

  local function unpark()
    local R = rs()
    if R then R.park = nil end
    b.call(b.g3("field"), "unlock", Routes3.LOCK)
  end

  -- True while one of this part's own warps is under way (warpDeparture: a script warp, as Gen 1's).
  function b.routeOwnWarp()
    local R = rs()
    return R ~= nil and (R.backing ~= nil or R.resume ~= nil)
  end

  -- The clearing of the current map open, again after a same-map load cleared it.
  local function ensureOpen(map)
    local mdef = b.ask(b.g3("map"), "currentDef", nil)
    local lay = type(mdef) == "table" and mdef.midLayout or nil
    local ov = type(lay) == "table" and type(lay.overrides) == "table" and lay.overrides or nil
    if not ov then return end
    for _, z in pairs(zones) do
      if z.town == map and z.active ~= false and z.plan.ready then
        local c = z.plan.cells[1]
        local have = c and ov[c.y * 1024 + c.x] or nil
        if c and (type(have) ~= "table" or tonumber(have.mid) ~= c.mid) then
          openClearings(map)
          return
        end
      end
    end
  end

  -- Each step (observe): the clearing kept open, and an own warp that landed
  -- on its cell told to the core (a warp within the town has no map.entered).
  local function tick()
    if not b.inField() then return end
    local R = rs()
    if not R then return end
    local map = currentMap()
    if b.ask(b.g3("warp"), "isBusy", false) == true then return end
    ensureOpen(map)
    local P = b.g3("player")
    local x, y = type(P) == "table" and tonumber(P.cellX) or nil, type(P) == "table" and tonumber(P.cellY) or nil
    if R.backing then
      local bk = R.backing
      R.backingSteps = (R.backingSteps or 0) + 1
      if map == bk.town and x == bk.x and y == bk.y then
        R.backing, R.backingSteps = nil, nil
        R.events = { entered = { { map = map, via = "warp" } } }
      elseif R.backingSteps > 600 then
        R.backing, R.backingSteps = nil, nil
      end
    end
    if R.resume then
      local z = zones[R.resume]
      local t = z and z.plan.triggerList and z.plan.triggerList[1] or nil
      R.resumeSteps = (R.resumeSteps or 0) + 1
      if z and t and map == z.town and x == t[1] and y == t[2] then
        R.resume, R.resumeSteps = nil, nil
        park(z, false)
        R.events = { entered = { { map = z.mapId, via = "warp" } } }
      elseif R.resumeSteps > 600 then
        R.resume, R.resumeSteps = nil, nil
      end
    end
  end

  -- The facts while parked: the route map at x1, facing into the zone, idle
  -- unless something else (the region park, a script) holds the world.
  function b.routeFacts(f)
    tick()
    local z = parked()
    if not z or type(f) ~= "table" or not f.inGame then return f end
    if b.inField() and not locked() then b.call(b.g3("field"), "lock", Routes3.LOCK) end
    f.map, f.x, f.y, f.facing = z.mapId, Routes3.CELL_X1.x, Routes3.CELL_X1.y, Routes3.ARRIVE_FACING
    f.moving, f.tx, f.ty, f.ledge = false, nil, nil, false
    local st = b.state()
    local regionHeld = type(st) == "table" and type(st.region) == "table" and st.region.state == "held"
    local F = b.g3("field")
    local others = false
    for tag in pairs(type(F) == "table" and type(F._locks) == "table" and F._locks or {}) do
      if tag ~= Routes3.LOCK then others = true end
    end
    local Space = b.g3("scripting.space")
    local vm = type(Space) == "table" and Space.vm or nil
    local running = type(vm) == "table" and type(vm.isRunning) == "function" and select(2, pcall(vm.isRunning, vm)) == true
    f.scripted = regionHeld or others or running
    f.npcs = 0
    return f
  end

  -- The route map's id while parked (observe.lua: no NPCs there), else nil.
  function b.routeParkedMap()
    local z = parked()
    return z and z.mapId or nil
  end

  -- ---- the core's hooks (lib/kb_routes.lua, main.lua, act/kb_move.lua, act/kb_region.lua, act/kb_route.lua)

  -- An end the core hands over: its zone record (id, key, mapId, town,
  -- finish = { map, x, y, facing, depth }, plan, active). True when taken.
  def("routeInstall", function(zone)
    if type(zone) ~= "table" or type(zone.key) ~= "string" or type(zone.finish) ~= "table" then return false, "bad_zone" end
    local f = zone.finish
    if not (b.symbol(f.map) and whole(f.x, 0, 1023) and whole(f.y, 0, 1023) and DIRS[f.facing]
        and whole(f.depth, 1, 4)) then
      return false, "bad_spot"
    end
    zones[zone.key] = zone
    planZone(zone) -- now when the town's cells are loaded, else at its first load or the export
    return true
  end)

  -- Plans every zone not planned yet (the export asks first); how many are planned.
  def("routePlan", function()
    local n = 0
    for _, z in pairs(zones) do
      if planZone(z) then n = n + 1 end
    end
    return n
  end)

  -- map.entered: the clearings again; a pending resume parks here.
  def("routeMapEntered", function(payload)
    local R = rs()
    if not R then return end
    local map = type(payload) == "table" and payload.mapId or currentMap()
    if map ~= currentMap() then map = currentMap() end
    openClearings(map)
    if R.resume then
      local z = zones[R.resume]
      R.resume, R.resumeSteps = nil, nil
      if z and z.town == map and z.active ~= false then park(z, false) end
    end
  end)

  -- world.stepped: a trigger cell of a zone of this town parks the player
  -- (the way out).
  def("routeStepped", function()
    local R = rs()
    if not R or R.park or not b.inField() then return end
    local P = b.g3("player")
    local map = currentMap()
    local x, y = type(P) == "table" and tonumber(P.cellX) or nil, type(P) == "table" and tonumber(P.cellY) or nil
    if not (map and x and y) then return end
    for _, z in pairs(zones) do
      if z.town == map and z.active ~= false and z.plan.ready and z.plan.triggers[key(x, y)] then
        park(z, true)
        return
      end
    end
  end)

  -- The warp events of entering a route map, once (main.lua merges them into the step's).
  def("takeRouteEvents", function()
    local R = rs()
    local ev = R and R.events or nil
    if R then R.events = nil end
    return ev
  end)

  -- A step on the route map (kb_move): left from x1 onto A is the way back
  -- (true); any other step is a wall (false, "tile"); nil off the route map.
  def("routeStep", function(dir)
    local z = parked()
    if not z then return nil end
    if dir ~= "left" then return false, "tile" end
    local back = z.plan.back
    unpark()
    local world = b.world
    local ok, warped = false, nil
    if type(world) == "table" and type(world.warpTo) == "function" then
      ok, warped = pcall(world.warpTo, world, z.town, back.x, back.y, back.facing)
    end
    if not (ok and warped) then
      park(z, false) -- no warp: the player stays on the route map
      return false, "script"
    end
    rs().backing, rs().backingSteps = { town = z.town, x = back.x, y = back.y }, 0
    if b.log then b.log.info("routes.back", { route = z.id }) end
    return true
  end)

  -- A resume onto the route map (kb_region): the warp to the trigger cell,
  -- parked once the town has loaded. True when the warp started.
  def("routeEnter", function(zoneKey)
    local R = rs()
    local z = zones[zoneKey]
    if not R or not z or z.active == false then return false end
    if R.park == zoneKey then return true end
    if not planZone(z) then return false end
    local t = z.plan.triggerList[1]
    local world = b.world
    if type(world) ~= "table" or type(world.warpTo) ~= "function" then return false end
    R.resume, R.resumeSteps = zoneKey, 0
    local ok, warped = pcall(world.warpTo, world, z.town, t[1], t[2], z.plan.exitFacing)
    if not (ok and warped) then
      R.resume = nil
      return false
    end
    return true
  end)

  -- ---- the zone's acts (kb_route): give and battle

  local templates = {} -- trainer class id -> the first trainer id of that class

  local function templateOf(classId)
    if templates[classId] ~= nil then return templates[classId] or nil end
    local Trainers = b.g3("scripting.trainers")
    for id = 1, Routes3.MAX_TRAINERS do
      local t = b.ask(Trainers, "get", nil, id)
      if type(t) == "table" and tonumber(t.class) == classId and type(t.party) == "table" and #t.party > 0 then
        templates[classId] = id
        return id
      end
    end
    templates[classId] = false
    return nil
  end

  -- The foe of a zone battle: the template's, with the request's party.
  local function foeOf(templateId, party)
    local C = b.constants()
    local Trainers = b.g3("scripting.trainers")
    local base = b.ask(Trainers, "foeFromId", nil, templateId)
    if type(base) ~= "table" then return nil, "no_template" end
    local list = {}
    for i, m in ipairs(party) do
      local species = C and b.ask(C, "id", nil, C, "species", "SPECIES_" .. tostring(m.species)) or nil
      if not species then return nil, "bad_party" end
      local moves
      if type(m.moves) == "table" and #m.moves > 0 then
        moves = {}
        for _, mv in ipairs(m.moves) do
          local id = C and b.ask(C, "id", nil, C, "moves", "MOVE_" .. tostring(mv)) or nil
          if id then moves[#moves + 1] = id end
        end
        if #moves == 0 then moves = nil end
      end
      list[i] = { species = species, level = m.level, ivs = { hp = 0, atk = 0, def = 0, spa = 0, spd = 0, spe = 0 },
                  iv = 0, moves = moves, trainerId = templateId }
    end
    local foe = {}
    for k, v in pairs(base) do foe[k] = v end
    foe.party = list
    foe.species, foe.level, foe.moves = list[1].species, list[1].level, list[1].moves
    return foe
  end

  -- (true) once started, or (false, reason): busy, unknown_item, unknown_class, bad_party, failed.
  def("routeRun", function(op, req)
    if type(req) ~= "table" then return false, "failed" end
    if not b.inField() then return false, "busy" end
    local s = b.session()
    local C = b.constants()
    local Message = b.ui3("message")
    local function say(text, after)
      if text ~= nil and type(Message) == "table" and type(Message.show) == "function" then
        if pcall(Message.show, text, { done = after }) then return end
      end
      after()
    end
    if op == "give" then
      local id = C and b.ask(C, "id", nil, C, "items", "ITEM_" .. tostring(req.item)) or nil
      if not id then return false, "unknown_item" end
      local ok, added = b.call(b.g3("bag"), "add", type(s) == "table" and s.bag or nil, id, 1)
      if not (ok and added) then
        -- a full pocket: the core compares the counts and answers bag_full
        req.onDone()
        return true
      end
      b.dropView()
      local name = b.ask(b.g3("items_data"), "displayName", nil, id)
      local who = type(s) == "table" and type(s.name) == "string" and s.name or "You"
      say(name and ("%s found a %s!"):format(who, tostring(name)) or nil, function() req.onDone() end)
      return true
    elseif op == "battle" then
      local classId = C and b.ask(C, "id", nil, C, "trainer_classes", tostring(req.class)) or nil
      local templateId = classId and templateOf(classId) or nil
      if not templateId then return false, "unknown_class" end
      local foe, why = foeOf(templateId, type(req.party) == "table" and req.party or {})
      if not foe then return false, why end
      local Runtime = b.g3("runtime")
      local BB = b.g3("battle_bridge")
      if type(BB) ~= "table" or type(BB.start) ~= "function" then return false, "failed" end
      local function start()
        local ok = pcall(BB.start, type(Runtime) == "table" and Runtime._mod or nil, b.game, foe, {
          wild = false, trainerId = templateId, kbRoute = true,
          done = function(result) req.onDone(result or "win") end,
        })
        if not ok then req.onDone("run") end
      end
      say(req.line, start)
      return true
    end
    return false, "failed"
  end)

  -- ---- the blackout (G3F)

  -- true, or false plus not_ingame | busy | failed (gen1.lua's contract).
  def("blackout", function()
    if not b.inField() then return false, "not_ingame" end
    if b.ask(b.g3("battle"), "isActive", false) == true then return false, "busy" end
    local s = b.session()
    local g = b.game
    b.call(b.g3("battle_bridge"), "applyWhiteoutMoneyLoss", s, type(g) == "table" and g.save or nil)
    local ok = b.call(b.g3("field"), "respawnAtHeal")
    b.dropView()
    if not ok then return false, "failed" end
    return true
  end)
end

return Routes3
