-- Kanto Bridge test driver: Emerald's field mechanics read from the real
-- engine (the field mechanics audit, docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md
-- §4). Driven by tools/e2e/g3_field_check.py. Every scene first registers a
-- fresh empty save slot labelled KB_G3FIELD_TEST and makes it active (the
-- engine's slot API, src/core/SaveData.lua), starts a new game straight into
-- the field (Game3:_handleBootAction, as kb_g3story.lua does) and deletes the
-- test slots again at the end. KB_SCENE picks the part:
--
--   survey   FA1 + FA2 in one walk over every map (KB_MAPS: only these). Per
--            map, statically (no warp): every cell's metatile, collision byte,
--            elevation and metatile behaviour (the engine's canonical id,
--            src/core/game3/mb.lua), classified by the engine's own
--            predicates (collision.lua, forced_movement.lua, dive.lua,
--            bike/rse.lua): the §3.1 classes; the warp table with each warp
--            cell's behaviour name; the map header (type, running and biking
--            allowed, dive and emerge links). Then, on every map the audit
--            covers (the Battle Frontier, secret bases and link rooms left
--            out, D8), a warp in (no fade: Map.load runs the entry scripts),
--            the field settled (a script still running after
--            KB_SETTLE_FRAMES is halted), and per cell and direction the
--            engine's own step verdict, asked without moving anyone:
--              walk  Collision.ledgeLanding, then Collision.canEnter
--                    (surfing false) with the cell's elevation, then the
--                    rotating gate and the Acro check (player.lua:730-857's
--                    order; the warp triggers asked apart, read-only);
--              surf  the same with surfing true;
--              bike  BikeRse.collision (bike/rse.lua:877, GetBikeCollisionAt),
--                    the player's cell set to the cell for the call;
--            a step off the map: Connections.incoming + connectionLanding +
--            the landing's collision, as Collision.tryConnection checks it
--            (collision.lua:780-830) without loading anything. Objects are
--            left out (Objects.blocks stubbed false during the walk): this is
--            terrain. A cell whose elevation is 15 keeps the walker's: its
--            verdicts are asked with each neighbour's elevation and marked
--            "?" where they differ. The live layout overrides of the visit
--            (what the bridge would send as story.cells) are recorded with the
--            verdicts, and per cell where a player can stand (`stand`: . walkable, ~
--            water, L a ledge cell, # neither). Written to
--            KB_REPORT_DIR/g3_field_inventory.json and
--            g3_field_parity.json (ROM-derived: they stay in local/).
--
--   scripts  FA1's script part, static: every map's coord events, onFrame
--            and entry tables, objects' and signs' scripts, walked row by row
--            (goto/call/branches followed, no conditions), recording the
--            commands that move or warp the player or change the world while
--            the player is there: warp ops with their destination, hole /
--            dive / escape / dynamic warp setters, applymovement on the
--            player (LOCALID_PLAYER 0xFF), setstepcallback, setmetatile,
--            specials by name. Written to KB_REPORT_DIR/g3_field_scripts.json.
--
-- Verdict codes (one character per cell and direction, directions up, down,
-- left, right; cells row by row):
--   .  open (a step to the neighbour)       J  a ledge hop (lands two cells on)
--   x  refused: tile                         w  refused: water (on foot)
--   v  refused: elevation                    g  refused: rotating gate
--   a  refused: Acro bike rail/slope         b  refused: off the map
--   E  open across a connection              e  refused across a connection
--   ?  depends on the walker's elevation (a cell of elevation 15)
-- Bike codes: the GetBikeCollisionAt number as one hex digit (0 none, 1
-- outside range, 2 impassable, 3 elevation, 5 stop surfing, 6 ledge jump, 8
-- gate, 9 wheelie hop, a-d rails), E/e/b/? as above.
--
-- Markers: KB_BOOTED, KB_FIELD map=, KB_SURVEY_MAP n= map= cells= warped=
-- halted= classes=, KB_SURVEY maps= surveyed= cells= file=, KB_SCRIPTS maps=
-- roots= rows= warps= moves= file=, KB_CLEANED deleted= active=, KB_QUIT
-- code=; KB_FAIL <reason>. Map symbols, ids, counts and booleans only.
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- with the Game. Paths cited are refs/gen1recomp's (Gen1Recomp v0.3.85).

io.stdout:setvbuf("no")

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27228
local SCENE = os.getenv("KB_SCENE") or "survey"
local REPORT_DIR = os.getenv("KB_REPORT_DIR")
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or 3600
local SETTLE_MIN = tonumber(os.getenv("KB_SETTLE_MIN") or "") or 30
local SETTLE_FRAMES = tonumber(os.getenv("KB_SETTLE_FRAMES") or "") or 240
local LABEL = "KB_G3FIELD_TEST"
local GAME = "emerald"

local SaveData = require("src.core.SaveData")
local GameVersion = require("src.core.GameVersion")

local function word(v) return (tostring(v):gsub("[^%w_.:+%-]", "?")) end
local function num(id) return tonumber(tostring(id):match("^slot(%d+)$")) or 0 end
local function version() return GameVersion.get() end
local function active() return SaveData.activeSlot(version()) end
local function mod3(name) return package.loaded["src.core.game3." .. name] end

local function slots()
  local ok, list = pcall(SaveData.listSlots, version())
  return ok and type(list) == "table" and list or {}
end

local function cleanup(home)
  local deleted, v = 0, version()
  for _, s in ipairs(slots()) do
    if type(s) == "table" and s.label == LABEL then
      if active() == s.id then
        local back = home
        if not back then
          for _, o in ipairs(slots()) do
            if type(o) == "table" and o.label ~= LABEL then back = o.id break end
          end
        end
        if back and back ~= s.id then SaveData.setActiveSlot(v, back) end
      end
      if SaveData.deleteSlot(v, s.id) then deleted = deleted + 1 end
    end
  end
  if home and active() ~= home then SaveData.setActiveSlot(v, home) end
  return deleted, active()
end

local homeRef
local function fail(reason)
  local ok, deleted = pcall(cleanup, homeRef)
  print(("KB_FAIL %s cleaned=%s"):format(reason, word(ok and deleted or "error")))
  love.event.quit(1)
  while true do coroutine.yield() end
end

local function fieldReady(game)
  local F, W, P = mod3("field"), mod3("warp"), mod3("player")
  local Fade = package.loaded["src.ui.game3.fade"]
  if not (game and game.phase == "field" and F and F.running) then return false end
  if F.isLocked and F.isLocked() then return false end
  if W and W.isBusy and W.isBusy() then return false end
  if Fade and Fade.lockInput then return false end
  return not (P and P.moving)
end

-- ---- a small JSON writer (sorted keys; arrays are tables with [1] or marked)

local ARRAY = {}
local function array(t) return setmetatable(t or {}, ARRAY) end

local function encode(v, out)
  local t = type(v)
  if t == "nil" then
    out[#out + 1] = "null"
  elseif t == "boolean" then
    out[#out + 1] = v and "true" or "false"
  elseif t == "number" then
    if v ~= v or v == math.huge or v == -math.huge then
      out[#out + 1] = "null"
    elseif v == math.floor(v) and math.abs(v) < 2 ^ 53 then
      out[#out + 1] = ("%d"):format(v)
    else
      out[#out + 1] = ("%.6g"):format(v)
    end
  elseif t == "string" then
    out[#out + 1] = '"' .. v:gsub('[%c"\\]', function(c)
      return ({ ['"'] = '\\"', ["\\"] = "\\\\", ["\n"] = "\\n", ["\t"] = "\\t" })[c] or ("\\u%04x"):format(c:byte())
    end) .. '"'
  elseif t == "table" then
    if getmetatable(v) == ARRAY or (v[1] ~= nil) then
      out[#out + 1] = "["
      for i = 1, #v do
        if i > 1 then out[#out + 1] = "," end
        encode(v[i], out)
      end
      out[#out + 1] = "]"
    else
      local keys = {}
      for k in pairs(v) do keys[#keys + 1] = tostring(k) end
      table.sort(keys)
      out[#out + 1] = "{"
      for i, k in ipairs(keys) do
        if i > 1 then out[#out + 1] = "," end
        encode(k, out)
        out[#out + 1] = ":"
        local val = v[k]
        if val == nil then val = v[tonumber(k)] end
        encode(val, out)
      end
      out[#out + 1] = "}"
    end
  else
    out[#out + 1] = "null"
  end
end

local function writeJson(name, value)
  if not REPORT_DIR then return nil end
  local out = {}
  encode(value, out)
  local path = REPORT_DIR .. "/" .. name
  local f = io.open(path, "wb")
  if not f then return nil end
  f:write(table.concat(out))
  f:close()
  return path
end

-- ---- what the audit leaves out (D8, as STORY_EVENTS_PLAN S7)

local SKIP_PREFIX = { "EM_BATTLE_FRONTIER_", "EM_BATTLE_PYRAMID_", "EM_TRAINER_HILL_", "EM_SECRET_BASE_",
                      "EM_UNION_ROOM", "EM_RECORD_CORNER", "EM_TRADE_CENTER", "EM_BATTLE_COLOSSEUM",
                      "EM_CONTEST_HALL", "EM_LINK_CONTEST", "EM_SS_TIDAL", "EM_INSIDE_OF_TRUCK" }
local function skipped(id)
  for _, p in ipairs(SKIP_PREFIX) do
    if id:sub(1, #p) == p then return true end
  end
  return false
end

local DIRS = { "up", "down", "left", "right" }
local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
local CARD = { up = "north", down = "south", left = "west", right = "east" }

-- ---- classification: the engine's own predicates, by class name (§3.1)

local function classifier()
  local C = require("src.core.game3.collision")
  local MB = require("src.core.game3.mb")
  local FM = require("src.core.game3.forced_movement")
  local okD, Dive = pcall(require, "src.core.game3.dive")
  local Rse = require("src.core.game3.bike.rse")
  local function ids(...)
    local set = {}
    for _, n in ipairs({ ... }) do
      local id = MB.id(n)
      if id then set[id] = true end
    end
    return function(b) return set[b] == true end
  end
  local function pred(name)
    return function(b) return type(C[name]) == "function" and C[name](b) == true end
  end
  -- order matters only for the name of a cell's first class; a cell keeps every class it has
  return {
    { "ledge_jump", ids("JUMP_EAST", "JUMP_WEST", "JUMP_NORTH", "JUMP_SOUTH") },
    { "ledge_diagonal", ids("JUMP_NORTHEAST", "JUMP_NORTHWEST", "JUMP_SOUTHEAST", "JUMP_SOUTHWEST") },
    { "impassable_side", function(b)
      return C.isEastBlocked(b) or C.isWestBlocked(b) or C.isNorthBlocked(b) or C.isSouthBlocked(b)
    end },
    { "impassable_pair", ids("IMPASSABLE_SOUTH_AND_NORTH", "IMPASSABLE_WEST_AND_EAST") },
    { "walk_tile", function(b) return C.isWalkEast(b) or C.isWalkWest(b) or C.isWalkNorth(b) or C.isWalkSouth(b) end },
    { "slide_tile", function(b) return C.isSlideEast(b) or C.isSlideWest(b) or C.isSlideNorth(b) or C.isSlideSouth(b) end },
    { "slippery_floor", pred("isTrickHouseSlipperyFloor") },
    { "ice", pred("isIce") },
    { "thin_ice", pred("isThinIce") },
    { "cracked_ice", pred("isCrackedIce") },
    { "current", function(b)
      return C.isEastwardCurrent(b) or C.isWestwardCurrent(b) or C.isNorthwardCurrent(b) or C.isSouthwardCurrent(b)
    end },
    { "waterfall", pred("isWaterfall") },
    { "spin_tile", pred("isSpinTile") },
    { "dive_spot", function(b) return okD and type(Dive.isDiveable) == "function" and Dive.isDiveable(b) == true end },
    { "no_surfacing", function(b) return okD and type(Dive.isUnableToEmerge) == "function" and Dive.isUnableToEmerge(b) == true end },
    { "cracked_floor", pred("isCrackedFloor") },
    { "cracked_floor_hole", pred("isCrackedFloorHole") },
    { "fall_warp", pred("isFallWarp") },
    { "mt_pyre_hole", pred("isMtPyreHole") },
    { "warp_pad", pred("isWarpPad") },
    { "lavaridge_warp", function(b) return C.isLavaridge1FWarp(b) or C.isLavaridgeB1FWarp(b) end },
    { "mossdeep_gym_warp", pred("isMossdeepGymWarp") },
    { "bridge_over_ocean", pred("isUnionRoomWarp") },
    { "ladder", pred("isLadder") },
    { "escalator", pred("isEscalator") },
    { "arrow_warp", pred("isArrowWarpBehavior") },
    { "warp_door", pred("isWarpDoor") },
    { "non_anim_door", pred("isNonAnimDoor") },
    { "deep_south_warp", pred("isDeepSouthWarp") },
    { "warp_metatile", pred("isWarpMetatileBehavior") },
    { "muddy_slope", pred("isMuddySlope") },
    { "bumpy_slope", pred("isBumpySlope") },
    { "rail", function(b)
      return C.isIsolatedVerticalRail(b) or C.isIsolatedHorizontalRail(b) or C.isVerticalRail(b) or C.isHorizontalRail(b)
    end },
    { "bike_bridge", ids("BIKE_BRIDGE_OVER_BARRIER") },
    { "no_running", ids("NO_RUNNING") },
    { "run_bike_blocked", function(b) return Rse.runningDisallowedByMetatile(b) == true end },
    { "long_grass", ids("LONG_GRASS") },
    { "hot_springs", pred("isHotSprings") },
    { "ash_grass", pred("isAshGrass") },
    { "fortree_bridge", pred("isFortreeBridge") },
    { "pacifidlog_log", pred("isPacifidlogLog") },
    { "pond_bridge", ids("BRIDGE_OVER_POND_LOW", "BRIDGE_OVER_POND_MED", "BRIDGE_OVER_POND_HIGH",
      "BRIDGE_OVER_POND_MED_EDGE_1", "BRIDGE_OVER_POND_MED_EDGE_2", "BRIDGE_OVER_POND_HIGH_EDGE_1",
      "BRIDGE_OVER_POND_HIGH_EDGE_2", "UNUSED_BRIDGE", "BIKE_BRIDGE_OVER_BARRIER") },
    { "tall_grass", ids("TALL_GRASS") },
    { "sand", ids("SAND", "DEEP_SAND") },
    { "puddle", ids("PUDDLE") },
    { "shallow_water", ids("SHALLOW_WATER") },
    { "counter", ids("COUNTER") },
    { "strength_button", pred("isStrengthButton") },
    { "secret_base_mat", function(b) return C.isSecretBaseJumpMat(b) or C.isSecretBaseSpinMat(b) end },
    { "forced_tile", function(b) return FM.isForcedMovementTile and FM.isForcedMovementTile(b) == true end },
    { "surfable", pred("isSurfable") },
  }
end

-- the classes a cell has, as one string "a|b|c" (or "")
local function classesOf(list, b)
  if b == nil then return "" end
  local out = {}
  for _, c in ipairs(list) do
    local ok, yes = pcall(c[2], b)
    if ok and yes then out[#out + 1] = c[1] end
  end
  return table.concat(out, "|")
end

-- Common classes are counted only; every other class's cells are listed (map-relative, in local/).
local COMMON = { tall_grass = true, sand = true, counter = true, surfable = true, puddle = true,
                 warp_metatile = true, non_anim_door = true, shallow_water = true }

-- ---- the static part of a map (no warp)

local function staticMap(game, id, def, classes)
  local Map, C, MB = mod3("map"), require("src.core.game3.collision"), require("src.core.game3.mb")
  local lay = Map.ensureMidLayout and Map.ensureMidLayout(game, id, def) or def.midLayout
  if type(lay) ~= "table" then return nil, "no_layout" end
  local w = math.floor(tonumber(lay.width) or 0)
  local tw = math.floor(tonumber(lay.trueWidth) or w)
  local th = math.floor(tonumber(lay.trueHeight) or tonumber(lay.height) or 0)
  local out = { w = tw, h = th, gridW = w, gridH = math.floor(tonumber(lay.height) or th), type = tonumber(def.mapType),
                allowRunning = def.allowRunning, bikingAllowed = def.bikingAllowed, cave = def.cave or nil,
                dive = def.dive and true or false, emerge = def.emerge and true or false,
                counts = {}, cells = {}, warps = array(), behaviors = {} }
  for y = 0, th - 1 do
    for x = 0, tw - 1 do
      local b = C.behaviorOn(def, x, y)
      out.behaviors[b or -1] = (out.behaviors[b or -1] or 0) + 1
      local cl = classesOf(classes, b)
      if cl ~= "" then
        for name in cl:gmatch("[^|]+") do
          out.counts[name] = (out.counts[name] or 0) + 1
          out.cells[name] = out.cells[name] or array()
          local list = out.cells[name]
          -- every cell of a rare class; the first 30 of a common one (the sweep samples 10 a map, D9)
          if not COMMON[name] or #list < 30 then list[#list + 1] = { x, y } end
        end
      end
    end
  end
  -- behaviour ids as names (counts only)
  local beh = {}
  for b, n in pairs(out.behaviors) do
    local name = b >= 0 and MB.nameOf and MB.nameOf(b) or "none"
    beh[("%s:%d"):format(tostring(name), b)] = n
  end
  out.behaviors = beh
  for i, wp in ipairs(type(def.warps) == "table" and def.warps or {}) do
    local x, y = tonumber(wp.x), tonumber(wp.y)
    local b = x and y and C.behaviorOn(def, x, y) or nil
    out.warps[#out.warps + 1] = { n = i, x = x, y = y, to = wp.destMap, destWarp = tonumber(wp.destWarp),
                                  beh = b, behName = b and MB.nameOf and MB.nameOf(b) or nil,
                                  coll = x and y and lay.collAt and lay:collAt(x, y) or nil,
                                  classes = classesOf(classes, b) }
  end
  return out
end

-- ---- the live part: verdicts on the bound map

local function verdictChar(ok, why)
  if ok then return "." end
  return ({ tile = "x", water = "w", elevation = "v", bounds = "b", entity = "x" })[why] or "x"
end

local HEX = "0123456789abcdef"

-- The read-only half of Collision.tryConnection (collision.lua:780-830): E open, e refused, b no connection.
local function edgeVerdict(game, def, x, y, dir, surfing, elevation)
  local C = require("src.core.game3.collision")
  local Conn = require("src.core.game3.connections")
  local Map = mod3("map")
  local P = require("src.world.gen2.Permissions")
  local data = game.data and game.data.maps
  if type(def.connections) ~= "table" or not data then return "b" end
  local conn, dest = Conn.incoming(def, CARD[dir], x, y, function(mid)
    local d = data[mid]
    if d and Map.ensureMidLayout then Map.ensureMidLayout(game, mid, d) end
    return d
  end)
  if not conn then return "b" end
  local lx, ly = C.connectionLanding(dest, conn, dir, x, y)
  if not lx then return "b" end
  local L = dest.midLayout
  local water = C.isWaterOn(dest, lx, ly)
  if L and L.collAt then
    local coll = L:collAt(lx, ly)
    local walk = not P.isLedge(coll) and P.isWalkable(coll)
    if not walk and not P.isWater(coll) then return "e" end
    if water and not surfing then return "e" end
  end
  local leave = ({ down = C.isSouthBlocked, up = C.isNorthBlocked, left = C.isWestBlocked, right = C.isEastBlocked })[dir]
  local enter = ({ down = C.isNorthBlocked, up = C.isSouthBlocked, left = C.isEastBlocked, right = C.isWestBlocked })[dir]
  if leave(C.behaviorOn(def, x, y)) or enter(C.behaviorOn(dest, lx, ly)) then return "e" end
  if surfing and L and L.elevAt then
    local e = L:elevAt(lx, ly)
    if not water then
      if e ~= 3 then return "e" end
    elseif e ~= 0 and e ~= 15 and elevation ~= 0 and e ~= elevation then
      return "e"
    end
  end
  return "E"
end

local function liveVerdicts(game, id, def)
  local C = require("src.core.game3.collision")
  local Rse = require("src.core.game3.bike.rse")
  local Objects = require("src.core.game3.objects")
  local P = require("src.core.game3.player")
  local RG = package.loaded["src.core.game3.rotating_gate"]
  local lay = def.midLayout
  local tw = math.floor(tonumber(lay.trueWidth) or tonumber(lay.width) or 0)
  local th = math.floor(tonumber(lay.trueHeight) or tonumber(lay.height) or 0)
  -- terrain only: no object blocks (entityBlocks looks the function up at call time, collision.lua:1000-1004)
  local realBlocks = Objects.blocks
  Objects.blocks = function() return false end
  local saved = { P.cellX, P.cellY, P.surfing, P.currentElevation, P.elevation, P.underwater }
  local challenge = Rse.state and Rse.state.cyclingChallenge
  if Rse.state then Rse.state.cyclingChallenge = false end
  local walk, surf, bike, stand = {}, {}, {}, {}
  local Perm = require("src.world.gen2.Permissions")
  local counts = { cells = 0, varies = 0 }
  local function elevOf(x, y) return C.elevationOn(def, x, y) end

  local function footVerdict(x, y, dir, surfing, elev)
    local d = DELTA[dir]
    local tx, ty = x + d[1], y + d[2]
    if C.ledgeLanding(game, x, y, dir) then return "J" end
    if not C.inBounds(tx, ty) then return edgeVerdict(game, def, x, y, dir, surfing, elev) end
    local ok, why = C.canEnter(game, tx, ty, { fromX = x, fromY = y, dir = dir, surfing = surfing, elevation = elev })
    if not ok then return verdictChar(false, why) end
    if RG and RG.active and RG.active() and RG.checkCollision(dir, tx, ty) then return "g" end
    if Rse.acroCollision(C.behavior(tx, ty)) ~= 0 then return "a" end
    return "."
  end

  local function bikeVerdict(x, y, dir, elev)
    local d = DELTA[dir]
    local tx, ty = x + d[1], y + d[2]
    P.cellX, P.cellY, P.surfing, P.currentElevation, P.elevation = x, y, false, elev, elev
    if not C.inBounds(tx, ty) and not C.ledgeLanding(game, x, y, dir) then
      return edgeVerdict(game, def, x, y, dir, false, elev)
    end
    local ok, col = pcall(Rse.collision, game, dir)
    if not ok then return "x" end
    col = tonumber(col) or 2
    return HEX:sub(col + 1, col + 1)
  end

  -- the elevations a cell of elevation 15 may be walked with: its neighbours'
  local function walkerElevations(x, y)
    local e = elevOf(x, y)
    if e ~= 15 then return { e or 0 } end
    local set, list = {}, {}
    for _, dir in ipairs(DIRS) do
      local d = DELTA[dir]
      local ne = elevOf(x + d[1], y + d[2])
      if ne and ne ~= 15 and not set[ne] then
        set[ne] = true
        list[#list + 1] = ne
      end
    end
    if #list == 0 then list[1] = 0 end
    return list
  end

  local ok, err = pcall(function()
    for y = 0, th - 1 do
      for x = 0, tw - 1 do
        counts.cells = counts.cells + 1
        -- where a player can stand: . walkable, ~ water, L a ledge cell, # neither
        local coll = C.cell(x, y)
        stand[#stand + 1] = (C.isWater(x, y) and "~") or (Perm.isLedge(coll) and "L") or (C.isWalkable(x, y) and ".") or "#"
        local elevs = walkerElevations(x, y)
        for _, dir in ipairs(DIRS) do
          local vw, vs, vb
          for i, e in ipairs(elevs) do
            local a, s, k = footVerdict(x, y, dir, false, e), footVerdict(x, y, dir, true, e), bikeVerdict(x, y, dir, e)
            if i == 1 then
              vw, vs, vb = a, s, k
            else
              if a ~= vw then vw = "?" end
              if s ~= vs then vs = "?" end
              if k ~= vb then vb = "?" end
            end
          end
          if vw == "?" or vs == "?" or vb == "?" then counts.varies = counts.varies + 1 end
          walk[#walk + 1], surf[#surf + 1], bike[#bike + 1] = vw, vs, vb
        end
      end
    end
  end)
  Objects.blocks = realBlocks
  P.cellX, P.cellY, P.surfing, P.currentElevation, P.elevation, P.underwater =
    saved[1], saved[2], saved[3], saved[4], saved[5], saved[6]
  if Rse.state then Rse.state.cyclingChallenge = challenge end
  if not ok then return nil, word(err):sub(1, 80) end
  return { w = tw, h = th, walk = table.concat(walk), surf = table.concat(surf), bike = table.concat(bike),
           stand = table.concat(stand), varies = counts.varies }
end

-- The current map's layout overrides that differ from its own cells (what the bridge sends as story.cells).
local function overrides()
  local Map = mod3("map")
  local def = Map and Map.currentDef and Map.currentDef() or nil
  local lay = def and def.midLayout
  local out = array()
  if type(lay) ~= "table" or type(lay.overrides) ~= "table" then return out end
  local w = math.floor(tonumber(lay.width) or 0)
  local keys = {}
  for k in pairs(lay.overrides) do
    if tonumber(k) then keys[#keys + 1] = tonumber(k) end
  end
  table.sort(keys)
  for _, k in ipairs(keys) do
    local ov = lay.overrides[k]
    local x, y = k % 1024, math.floor(k / 1024)
    local base = lay.cells[y * w + x + 1]
    if type(ov) == "table" and type(base) == "table" then
      local same = ov.mid == base.mid and ov.coll == base.coll and ov.elev == base.elev
      if not same then
        out[#out + 1] = { x = x, y = y, b = { base.mid, base.coll, base.elev }, l = { ov.mid, ov.coll, ov.elev } }
      end
    end
  end
  return out
end

local function arrivalCell(game, map)
  local def = game.data and game.data.maps and game.data.maps[map]
  local w = def and def.warps and def.warps[1]
  if w and tonumber(w.x) and tonumber(w.y) then return tonumber(w.x), tonumber(w.y) end
  local Map = mod3("map")
  local lay = def and Map and Map.ensureMidLayout and Map.ensureMidLayout(game, map, def) or nil
  if type(lay) ~= "table" then return nil end
  local P = require("src.world.gen2.Permissions")
  local tw = math.floor(tonumber(lay.trueWidth) or tonumber(lay.width) or 0)
  local th = math.floor(tonumber(lay.trueHeight) or tonumber(lay.height) or 0)
  local cx, cy = math.floor(tw / 2), math.floor(th / 2)
  local best = {}
  for y = 0, th - 1 do
    for x = 0, tw - 1 do
      local cell = lay.cells[y * lay.width + x + 1]
      local coll = type(cell) == "table" and cell.coll or 0xff
      local class = (P.isWater(coll) and "water") or (P.isWalkable(coll) and not P.isLedge(coll) and "walk") or nil
      if class then
        local d = math.abs(x - cx) + math.abs(y - cy)
        local b = best[class]
        if not b or d < b.d then best[class] = { x = x, y = y, d = d } end
      end
    end
  end
  local pick = best.walk or best.water
  if pick then return pick.x, pick.y end
  return nil
end

local function haltScripts()
  local Space = mod3("scripting.space")
  local vm = Space and Space.vm
  local halted = false
  if vm and vm.isRunning and vm:isRunning() then
    pcall(vm.halt, vm, true)
    halted = true
  end
  local imm = Space and Space._immediateVm
  if imm and imm.isRunning and imm:isRunning() then pcall(imm.halt, imm, true) halted = true end
  local Message = package.loaded["src.ui.game3.message"]
  if Message and Message.reset then pcall(Message.reset) end
  local Choice = package.loaded["src.ui.game3.choice"]
  if type(Choice) == "table" then Choice.active = nil end
  local F = mod3("field")
  if F then F._locks = {} end
  return halted
end

-- ---- the static script walk (FA1 §3.2)

local WARP_OPS = { warp = true, warpsilent = true, warpdoor = true, warphole = true, warpteleport = true,
                   warpspinenter = true, warpmossdeepgym = true, warpwhitefade = true }
local SETTER_OPS = { setwarp = true, setdynamicwarp = true, setdivewarp = true, setholewarp = true, setescapewarp = true }
local STOPS = { ["end"] = true, ["return"] = true, ["goto"] = true, gotostd = true, endram = true, returnram = true,
                gotonative = true, vgoto = true }
local LOCALID_PLAYER = 0xFF

local function scripts(game)
  local Space = mod3("scripting.space")
  local B = Space and (Space.bundle or (Space.ensureBundle and Space.ensureBundle(nil)))
  if not (type(B) == "table" and type(B.events) == "table" and type(B.scripts) == "table") then fail("no_bundle") end
  local Opcodes = require("src.core.game3.scripting.opcodes")
  local Cn = require("src.core.game3.constants").of(GAME)
  local function specialName(id)
    local ok, name = pcall(function() return Cn:specialName(tonumber(id)) end)
    return ok and name or ("special_" .. tostring(id))
  end
  local function list(k)
    local ok, l = pcall(function() return B.scripts[k] end)
    return ok and type(l) == "table" and l or nil
  end
  local mapIds = {}
  for id in pairs(B.events) do
    if type(id) == "string" then mapIds[#mapIds + 1] = id end
  end
  table.sort(mapIds)
  local report = { game = GAME, maps = {} }
  local totals = { maps = 0, roots = 0, rows = 0, warps = 0, moves = 0, setters = 0, stepcb = 0, coord = 0 }
  for _, mapId in ipairs(mapIds) do
    local ev = B.events[mapId]
    local ms = type(ev.mapScripts) == "table" and ev.mapScripts or {}
    local roots = {}
    local function root(kind, k, info)
      if type(k) == "string" or type(k) == "number" then roots[#roots + 1] = { kind = kind, key = Opcodes.key(k), info = info } end
    end
    for _, kind in ipairs({ "onTransition", "onLoad", "onResume", "onReturnToField", "onDiveWarp" }) do root(kind, ms[kind]) end
    for _, kind in ipairs({ "onFrame", "onWarpIntoMap" }) do
      for _, r in ipairs(type(ms[kind]) == "table" and ms[kind] or {}) do
        if type(r) == "table" then root(kind, r.script, { var = tonumber(r.var), value = tonumber(r.value) }) end
      end
    end
    for _, o in ipairs(type(ev.objects) == "table" and ev.objects or {}) do
      if type(o) == "table" then root("object", o.scriptKey, { localId = tonumber(o.localId or o.index) }) end
    end
    for i, s in ipairs(type(ev.bgEvents) == "table" and ev.bgEvents or {}) do
      if type(s) == "table" then root("bg", s.scriptKey, { n = i, type = s.type }) end
    end
    local coords = array()
    for i, c in ipairs(type(ev.coordEvents) == "table" and ev.coordEvents or {}) do
      if type(c) == "table" then
        coords[#coords + 1] = { n = i, x = tonumber(c.x), y = tonumber(c.y), elevation = tonumber(c.elevation),
                                var = tonumber(c.var), value = tonumber(c.value), script = c.scriptKey ~= nil }
        if c.scriptKey ~= nil then root("coord", c.scriptKey, { n = i }) end
      end
    end
    local m = { roots = #roots, rows = 0, coord = coords, found = array(), stepCallbacks = array() }
    totals.coord = totals.coord + #coords
    for _, r in ipairs(roots) do
      local seen = {}
      local function walk(k, depth)
        if depth > 40 or seen[k] then return end
        seen[k] = true
        local l = list(k)
        if not l then return end
        for i, row in ipairs(l) do
          m.rows = m.rows + 1
          local op = type(row) == "table" and row.op or nil
          if op == nil then break end
          local rec
          if WARP_OPS[op] then
            rec = { op = op, group = tonumber(row[1]), num = tonumber(row[2]), warp = tonumber(row[3]),
                    x = tonumber(row[4]), y = tonumber(row[5]) }
            totals.warps = totals.warps + 1
          elseif SETTER_OPS[op] then
            rec = { op = op, group = tonumber(row[1]), num = tonumber(row[2]), warp = tonumber(row[3]),
                    x = tonumber(row[4]), y = tonumber(row[5]) }
            totals.setters = totals.setters + 1
          elseif op == "applymovement" or op == "applymovementat" then
            local lid = tonumber(row.localId or row[1])
            if lid == LOCALID_PLAYER or (lid and lid >= 0x4000) then
              rec = { op = op, player = lid == LOCALID_PLAYER, var = lid ~= LOCALID_PLAYER or nil }
              totals.moves = totals.moves + 1
            end
          elseif op == "setstepcallback" then
            rec = { op = op, callback = tonumber(row[1]) }
            m.stepCallbacks[#m.stepCallbacks + 1] = tonumber(row[1])
            totals.stepcb = totals.stepcb + 1
          elseif op == "setmetatile" then
            rec = { op = op, x = tonumber(row[1]), y = tonumber(row[2]), metatile = tonumber(row[3]),
                    impassable = tonumber(row[4]) }
          elseif op == "special" or op == "specialvar" then
            local name = specialName(op == "special" and (row.id or row[1]) or row[2])
            if name and (name:find("Elevator") or name:find("Warp") or name:find("Cable") or name:find("Ferry")
                or name:find("Boat") or name:find("Truck") or name:find("Fall") or name:find("Hole")
                or name:find("Dive") or name:find("Bike") or name:find("Step")) then
              rec = { op = "special", name = name }
            end
          end
          if rec then
            rec.root, rec.rootInfo, rec.row, rec.rootKey = r.kind, r.info, i, tostring(r.key)
            m.found[#m.found + 1] = rec
          end
          if op == "goto_if" or op == "call_if" or op == "vgoto_if" or op == "vcall_if" or op == "goto" or op == "call"
              or op == "vgoto" or op == "vcall" then
            if row.target ~= nil then walk(Opcodes.key(row.target), depth + 1) end
          elseif op == "callstd" or op == "gotostd" then
            walk("std:" .. tostring(row.std or row[1]), depth + 1)
          elseif op == "callstd_if" or op == "gotostd_if" then
            walk("std:" .. tostring(row[2]), depth + 1)
          elseif op == "trainerbattle" and row.eventScript ~= nil then
            walk(Opcodes.key(row.eventScript), depth + 1)
          end
          if STOPS[op] then break end
        end
      end
      walk(r.key, 0)
    end
    totals.maps = totals.maps + 1
    totals.roots = totals.roots + m.roots
    totals.rows = totals.rows + m.rows
    report.maps[mapId] = m
  end
  report.totals = totals
  local path = writeJson("g3_field_scripts.json", report)
  print(("KB_SCRIPTS maps=%d roots=%d rows=%d warps=%d setters=%d moves=%d stepcb=%d coord=%d file=%s")
    :format(totals.maps, totals.roots, totals.rows, totals.warps, totals.setters, totals.moves, totals.stepcb,
      totals.coord, word(path ~= nil)))
end

-- ---- the survey loop

local function surveyList(game)
  local ids = {}
  for id, def in pairs(game.data and game.data.maps or {}) do
    if type(id) == "string" and type(def) == "table" then ids[#ids + 1] = id end
  end
  table.sort(ids)
  local only = os.getenv("KB_MAPS")
  if only and only ~= "" then
    local want, keep = {}, {}
    for m in only:gmatch("[^,]+") do want[m] = true end
    for _, id in ipairs(ids) do
      if want[id] then keep[#keep + 1] = id end
    end
    ids = keep
  end
  return ids
end


-- ---- the live sweep (FA3-FA5): rows from KB_ROWS, one step each, a stand-in client stepping

local CTL = os.getenv("KB_CTL")
local ROW_BUDGET = tonumber(os.getenv("KB_ROW_FRAMES") or "") or 900   -- frames a row may take before it is halted
local IDLE_FRAMES = 24                                                 -- the field idle this long ends a row

local function ctlValue()
  if not CTL then return nil end
  local f = io.open(CTL, "rb")
  if not f then return nil end
  local v = f:read("*a")
  f:close()
  return tonumber(v)
end

local function forcedNow()
  local FM = mod3("forced_movement")
  if type(FM) ~= "table" then return false end
  for _, name in ipairs({ "isForced", "isActive" }) do
    if type(FM[name]) == "function" then
      local ok, v = pcall(FM[name])
      if ok then return v == true end
    end
  end
  return false
end

local function quiet(game)
  local Space = mod3("scripting.space")
  local running = Space and Space.vm and Space.vm:isRunning()
  local P = mod3("player")
  return fieldReady(game) and not running and not forcedNow() and not (P and (P.moving or P.jumping))
end

-- the layout's live cells as a map "x,y" -> { mid, coll, elev } (overrides only)
local function liveCells()
  local Map = mod3("map")
  local def = Map and Map.currentDef and Map.currentDef() or nil
  local lay = def and def.midLayout
  local out = {}
  if type(lay) ~= "table" or type(lay.overrides) ~= "table" then return out end
  for k, ov in pairs(lay.overrides) do
    k = tonumber(k)
    if k and type(ov) == "table" then out[(k % 1024) .. "," .. math.floor(k / 1024)] = { ov.mid, ov.coll, ov.elev } end
  end
  return out
end

local function cellsDiff(before, after)
  local out = array()
  for k, v in pairs(after) do
    local b = before[k]
    if not b or b[1] ~= v[1] or b[2] ~= v[2] or b[3] ~= v[3] then
      local x, y = k:match("^(%d+),(%d+)$")
      out[#out + 1] = { x = tonumber(x), y = tonumber(y), l = { v[1], v[2], v[3] } }
    end
  end
  return out
end

local function setRepel()
  local Runtime = mod3("runtime")
  local session = Runtime and Runtime.getSession and Runtime.getSession()
  local Sem = package.loaded["src.core.game3.field_semantics"]
  if session and Sem and Sem.setVar then pcall(Sem.setVar, session, "repelSteps", 250) end
end

local function restoreStore(baseline)
  local Space = mod3("scripting.space")
  if not (Space and Space.store) then return end
  for k in pairs(Space.store.flags or {}) do Space.store.flags[k] = nil end
  for k, v in pairs(baseline.flags) do Space.store.flags[k] = v end
  for k in pairs(Space.store.vars or {}) do Space.store.vars[k] = nil end
  for k, v in pairs(baseline.vars) do Space.store.vars[k] = v end
end

local varIds
local function varId(name)
  if not varIds then
    varIds = {}
    local ok, E = pcall(function() return require("src.core.game3.scripting.flags").forVersion(GAME) end)
    for id, n in pairs(ok and type(E) == "table" and type(E.VAR_NAMES) == "table" and E.VAR_NAMES or {}) do varIds[n] = id end
  end
  return varIds[name]
end

local function applyRowState(row)
  local Space = mod3("scripting.space")
  local Flags = require("src.core.game3.scripting.flags")
  for id, on in pairs(row.flags or {}) do Flags.setFlag(Space.store, nil, tonumber(id), on == true) end
  local ctx = Space.vm and Space.vm.ctx
  for id, value in pairs(row.vars or {}) do Flags.setVar(Space.store, ctx, tonumber(id), tonumber(value)) end
  -- a map's story state by its variables' names (e.g. VAR_SKY_PILLAR_STATE)
  for name, value in pairs(row.varn or {}) do
    local id = varId(name)
    if id then Flags.setVar(Space.store, ctx, id, tonumber(value)) end
  end
end

local flagIds
local function flagId(name)
  if not flagIds then
    flagIds = {}
    local ok, E = pcall(function() return require("src.core.game3.scripting.flags").forVersion(GAME) end)
    for id, n in pairs(ok and type(E) == "table" and type(E.NAMES) == "table" and E.NAMES or {}) do flagIds[n] = id end
  end
  return flagIds[name]
end

-- FA5: the badges every field move asks for, and named flags (the running shoes: FLAG_SYS_B_DASH)
local function applyFeatureState(row)
  local Space = mod3("scripting.space")
  local Flags = require("src.core.game3.scripting.flags")
  if row.badges then
    local FM = require("src.core.game3.field_moves")
    for _, k in ipairs({ "CUT", "FLASH", "ROCK_SMASH", "STRENGTH", "SURF", "FLY", "DIVE", "WATERFALL" }) do
      local okB, flag = pcall(FM.badgeFlag, k)
      if okB and flag then pcall(Flags.setFlag, Space.store, nil, flag, true) end
    end
  end
  for name, on in pairs(row.flagn or {}) do
    local id = flagId(name)
    if id then pcall(Flags.setFlag, Space.store, nil, id, on == true) end
  end
  if row.strength then
    local FM = require("src.core.game3.field_moves")
    local flag = FM.SYS_FLAGS and FM.SYS_FLAGS.USE_STRENGTH
    if flag then pcall(Flags.setFlag, Space.store, nil, flag, true) end
  end
end

-- the step counters the engine keeps (§3.4): totals, friendship, poison, repel, daycare, safari
local function counters()
  local out = {}
  local Steps = mod3("step_events")
  out.total = Steps and tonumber(Steps._totalSteps) or nil
  local Runtime = mod3("runtime")
  local session = Runtime and Runtime.getSession and Runtime.getSession()
  local Sem = package.loaded["src.core.game3.field_semantics"]
  if session and Sem and Sem.getVar then
    for _, k in ipairs({ "repelSteps", "happinessSteps", "poisonSteps" }) do
      local ok, v = pcall(Sem.getVar, session, k)
      out[k] = ok and tonumber(v) or nil
    end
  end
  local okD, Daycare = pcall(require, "src.core.game3.daycare")
  if okD and type(Daycare) == "table" and Daycare.stateOf and session then
    local ok, st = pcall(Daycare.stateOf, session)
    if ok and type(st) == "table" then out.daycare = tonumber(st.stepCounter) end
  end
  if session and type(session.safari) == "table" then out.safari = tonumber(session.safari.steps) end
  local P = mod3("player")
  out.serial = P and tonumber(P.stepSerial) or nil
  return out
end

-- FA5: an object row finds its object on the map: a boulder, a cut tree or a breakable rock, with an open cell to
-- stand on facing it (and, for a boulder, an open cell behind it); sets the row's start, dir and target
local function findObject(row)
  local Objects, C = mod3("objects"), require("src.core.game3.collision")
  local FM = require("src.core.game3.field_moves")
  local want = ({ boulder = "PUSHABLE_BOULDER", cut_tree = "CUT_TREE", rock = "ROCK_SMASH_ROCK" })[row.find]
  local gfx = want and FM.GFX_IDS[want]
  if not (Objects and type(Objects._byId) == "table" and gfx) then return false end
  local lids = {}
  for lid in pairs(Objects._byId) do lids[#lids + 1] = lid end
  table.sort(lids, function(a, b) return tostring(a) < tostring(b) end)
  for _, lid in ipairs(lids) do
    local o = Objects._byId[lid]
    local g = type(o) == "table" and (o.graphicsId or (o.def and (o.def.graphicsId or o.def.gfx))) or nil
    if g == gfx and o.visible ~= false and tonumber(o.cellX) then
      for _, dir in ipairs(DIRS) do
        local d = DELTA[dir]
        local sx, sy = o.cellX - d[1], o.cellY - d[2]
        local bx, by = o.cellX + d[1], o.cellY + d[2]
        local okS = C.inBounds(sx, sy) and C.isWalkable(sx, sy) and not C.isWater(sx, sy)
        local okB = row.find ~= "boulder" or (C.inBounds(bx, by) and C.isWalkable(bx, by))
        if okS and okB then
          row.x, row.y, row.dir, row.face = sx, sy, dir, dir
          row.tx, row.ty = o.cellX, o.cellY
          row.objLid = tonumber(lid)
          return true
        end
      end
    end
  end
  return false
end

local function stepCallbackNow()
  local okC, Ctx = pcall(require, "src.core.game3.scripting.ctx")
  local Map = mod3("map")
  if not (okC and Ctx and Ctx.stepCallback and Map) then return nil end
  local ok, cb = pcall(Ctx.stepCallback, Map.current)
  return ok and cb or nil
end

-- the player's vehicle for the row: walk, surf, mach, acro (fields set directly, as the engine's own state)
local function setVehicle(row)
  local P = mod3("player")
  P.surfing = row.state == "surf"
  P.underwater = row.state == "underwater"
  P.biking = row.state == "mach" or row.state == "acro"
  P.bikeType = P.biking and row.state or nil
  local Runtime = mod3("runtime")
  local session = Runtime and Runtime.getSession and Runtime.getSession()
  if session then
    session.biking = P.biking
    session.bikeType = P.bikeType
  end
end

local function placeAt(game, row)
  local P = mod3("player")
  P.reset(row.x, row.y, row.face or row.dir)
  if P.syncSavePosition then pcall(P.syncSavePosition, game) end
  local C = require("src.core.game3.collision")
  local e = C.elevationAt(row.x, row.y)
  if e and e ~= 0 and e ~= 15 then P.currentElevation, P.elevation = e, e end
end

local function rowsList()
  local ok, list = pcall(dofile, os.getenv("KB_ROWS") or "")
  if not (ok and type(list) == "table") then fail("no_rows") end
  return list
end

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local sv, frames, sw, row, rec
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    if phase == "ready" then
      if status and status.ready and game.phase == "boot" then
        status.configure({ port = PORT })
        homeRef = active()
        if SCENE == "cleanup" then
          local deleted, now = cleanup(homeRef)
          print(("KB_CLEANED deleted=%d active=%d"):format(deleted, num(now)))
          print("KB_QUIT code=0")
          return
        end
        local precleaned = cleanup(homeRef)
        local v = version()
        local slot = SaveData.createSlot(v)
        if not slot then fail("no_slot") end
        SaveData.renameSlot(v, slot, LABEL)
        if SaveData.setActiveSlot(v, slot) ~= slot then fail("activate_failed") end
        print(("KB_BOOTED scene=%s slot=%d home=%d precleaned=%d"):format(word(SCENE), num(slot), num(homeRef), precleaned))
        local ok, err = pcall(game._handleBootAction, game, { action = "new_game", name = "TEST", gender = 0 })
        if not ok then fail("new_game " .. word(err):sub(1, 40)) end
        phase = "newgame"
      end
    elseif phase == "newgame" then
      if fieldReady(game) then
        local Map = mod3("map")
        print(("KB_FIELD map=%s"):format(word(Map and Map.current)))
        if SCENE == "scripts" then
          local ok, err = pcall(scripts, game)
          if not ok then fail("scripts " .. word(err):sub(1, 80)) end
          phase = "done"
        elseif SCENE == "survey" then
          sv = { ids = surveyList(game), i = 0, inv = { game = GAME, maps = {} }, par = { game = GAME, maps = {} },
                 classes = classifier(), totals = { maps = 0, surveyed = 0, cells = 0, failed = 0 } }
          sv.baseline = (function()
            local Space = mod3("scripting.space")
            local s = { flags = {}, vars = {} }
            for k, v in pairs(Space.store and Space.store.flags or {}) do s.flags[k] = v end
            for k, v in pairs(Space.store and Space.store.vars or {}) do s.vars[k] = v end
            return s
          end)()
          phase = "map_next"
        elseif SCENE == "sweep" then
          sw = { rows = rowsList(), i = 0, results = array(), dirty = true }
          sw.baseline = (function()
            local Space = mod3("scripting.space")
            local s0 = { flags = {}, vars = {} }
            for k, v in pairs(Space.store and Space.store.flags or {}) do s0.flags[k] = v end
            for k, v in pairs(Space.store and Space.store.vars or {}) do s0.vars[k] = v end
            return s0
          end)()
          frames = 0
          phase = "sweep_client"
        else
          fail("unknown_scene")
        end
      end
    elseif phase == "sweep_client" then
      if (tonumber(status.sessions) or 0) >= 1 and fieldReady(game) then
        frames = frames + 1
        if frames >= 120 then
          print(("KB_SWEEP_READY rows=%d"):format(#sw.rows))
          phase = "row_next"
        end
      end
    elseif phase == "row_next" then
      sw.i = sw.i + 1
      row = sw.rows[sw.i]
      if not row then
        local path = writeJson(os.getenv("KB_SWEEP_FILE") or "g3_field_sweep.json", { game = GAME, rows = sw.results })
        print(("KB_SWEEP_DONE rows=%d file=%s"):format(#sw.results, word(path ~= nil)))
        phase = "done"
      else
        rec = { n = row.n, map = row.map, cls = row.cls, x = row.x, y = row.y, dir = row.dir, state = row.state }
        haltScripts()
        setVehicle({ state = "walk" })
        local Map = mod3("map")
        if row.trigger == "warp" then
          -- an entry script's row: the warp itself is the trigger, made after READY
          rec.start = {}
          rec.before = {}
          print(("KB_ROW_READY n=%d dir=none push=0"):format(row.n))
          frames = 0
          phase = "row_wait_step"
        elseif sw.dirty or row.fresh or Map.current ~= row.map or row.flags or row.vars or row.varn then
          restoreStore(sw.baseline)
          applyRowState(row)
          local wx, wy = row.x, row.y
          if row.find then wx, wy = arrivalCell(game, row.map) end
          local okW, res, err = pcall(mod3("warp").request, nil, game, row.map, wx or row.x, wy or row.y,
            row.face or row.dir or "down", { fade = false })
          if not okW or (res == nil and err ~= nil) then
            rec.err = "warp " .. word(okW and err or res):sub(1, 50)
            phase = "row_report"
          else
            rec.warped = true
            frames = 0
            phase = "row_settle"
          end
        else
          placeAt(game, row)
          frames = 0
          phase = "row_place"
        end
      end
    elseif phase == "row_settle" then
      frames = frames + 1
      if frames >= SETTLE_FRAMES and not rec.entryHalted then rec.entryHalted = haltScripts() end
      if (frames >= SETTLE_MIN and quiet(game)) or frames >= SETTLE_FRAMES + 120 then
        local Map = mod3("map")
        if Map.current ~= row.map then
          rec.err = "left_on_entry"
          phase = "row_report"
        else
          placeAt(game, row)
          frames = 0
          phase = "row_place"
        end
      end
    elseif phase == "row_place" then
      frames = frames + 1
      if frames == 1 then
        applyFeatureState(row)
        if row.find and not row.found then
          row.found = findObject(row)
          if row.found then
            placeAt(game, row)
            rec.x, rec.y, rec.dir, rec.tx, rec.ty = row.x, row.y, row.dir, row.tx, row.ty
          else
            rec.err = "no_object"
          end
        end
        setVehicle(row)
        if not row.norepel then setRepel() end
      end
      if rec.err then
        phase = "row_report"
      elseif frames >= 8 and quiet(game) then
        local P = mod3("player")
        rec.start = { x = P.cellX, y = P.cellY, facing = P.facing, surfing = P.surfing == true }
        rec.before = liveCells()
        rec.stepSerial = tonumber(P.stepSerial)
        rec.stepcb = stepCallbackNow()
        local Steps = mod3("step_events")
        rec.stepsBefore = Steps and tonumber(Steps._totalSteps) or nil
        rec.countersBefore = counters()
        local extra = ""
        if row.dirs then extra = extra .. " dirs=" .. word((row.dirs:gsub(",", "+"))) end
        if row.hold then extra = extra .. " hold=" .. word(row.hold) end
        if row.act then extra = extra .. " act=" .. word(row.act) end
        if row.ax then extra = extra .. (" ax=%d ay=%d"):format(row.ax, row.ay) end
        if row.item then extra = extra .. " item=" .. word(row.item) end
        if row.dest then extra = extra .. " dest=" .. word(row.dest) end
        print(("KB_ROW_READY n=%d dir=%s push=%s%s"):format(row.n, word(row.dir or "none"), row.push and "1" or "0", extra))
        rec.stepsSeen = array()
        rec.lastMoving = false
        frames = 0
        phase = "row_wait_step"
      elseif frames >= 200 then
        haltScripts()
        rec.err = "not_quiet"
        phase = "row_report"
      end
    elseif phase == "row_wait_step" then
      frames = frames + 1
      if ctlValue() == row.n or frames >= 400 then
        rec.stepWait = frames
        frames = 0
        rec.idle = 0
        if row.trigger == "warp" then
          restoreStore(sw.baseline)
          applyRowState(row)
          local okW, res, err = pcall(mod3("warp").request, nil, game, row.map, row.x, row.y, row.face or "down", { fade = false })
          if not okW or (res == nil and err ~= nil) then rec.err = "warp " .. word(okW and err or res):sub(1, 50) end
        elseif row.trigger == "script" then
          -- a script reached through an interaction the sweep can't make (an object or sign script with a warp):
          -- run as the engine runs it, marked driven
          local Space = mod3("scripting.space")
          local okS, started = pcall(Space.startScript, row.script, row.lid)
          rec.driven = true
          if not okS or not started then rec.err = "script_not_started" end
        end
        phase = rec.err and "row_report" or "row_run"
      end
    elseif phase == "row_run" then
      frames = frames + 1
      if quiet(game) then rec.idle = rec.idle + 1 else rec.idle = 0 end
      do
        -- every step the hidden player starts: its frames and whether it ran or rode (§3.3 speeds)
        local P = mod3("player")
        local moving = P and P.moving == true
        if moving and not rec.lastMoving and rec.stepsSeen and #rec.stepsSeen < 64 then
          rec.stepsSeen[#rec.stepsSeen + 1] = { f = tonumber(P.stepFrames), run = P.running == true, bike = P.biking == true,
                                                bt = P.bikeType, x = P.targetX, y = P.targetY }
        end
        rec.lastMoving = moving
      end
      local Map = mod3("map")
      if Map.current ~= rec.lastMap then
        rec.maps = rec.maps or array()
        rec.maps[#rec.maps + 1] = Map.current
        rec.lastMap = Map.current
      end
      if (frames >= 30 and rec.idle >= (row.idle or IDLE_FRAMES)) or frames >= (row.wait or ROW_BUDGET) then
        if frames >= (row.wait or ROW_BUDGET) then rec.halted = haltScripts() or true end
        local P = mod3("player")
        rec.frames = frames
        rec["end"] = { map = Map.current, x = P.cellX, y = P.cellY, facing = P.facing, surfing = P.surfing == true,
                       underwater = P.underwater == true, biking = P.biking == true }
        if Map.current == row.map then
          rec.changed = cellsDiff(rec.before, liveCells())
        else
          rec.changed = array()
        end
        rec.before = nil
        rec.lastMap = nil
        rec.stepSerialEnd = tonumber(P.stepSerial)
        rec.countersAfter = counters()
        rec.lastMoving = nil
        local Steps = mod3("step_events")
        rec.stepsAfter = Steps and tonumber(Steps._totalSteps) or nil
        phase = "row_report"
      end
    elseif phase == "row_report" then
      local e = rec["end"] or {}
      sw.dirty = rec.err ~= nil or (e.map ~= nil and e.map ~= row.map) or (rec.changed ~= nil and #rec.changed > 0)
        or rec.halted == true
      print(("KB_ROW_DONE n=%d map=%s cls=%s end=%s moved=%s changed=%d frames=%s halted=%s err=%s")
        :format(row.n, word(row.map), word(row.cls), word(e.map or "none"),
          word(e.x ~= nil and (e.x ~= row.x or e.y ~= row.y or e.map ~= row.map)),
          rec.changed and #rec.changed or 0, word(rec.frames or 0), word(rec.halted or false), word(rec.err or "none")))
      sw.results[#sw.results + 1] = rec
      sw.gapUntil = love.timer.getTime() + 0.8
      phase = "row_gap"
    elseif phase == "row_gap" then
      -- the bridge's last messages for this row reach the client before the next row moves the player (by the
      -- clock: the engine runs faster than 60 frames a second headless)
      if love.timer.getTime() >= sw.gapUntil then phase = "row_next" end
    elseif phase == "map_next" then
      sv.i = sv.i + 1
      local id = sv.ids[sv.i]
      if not id then
        local p1 = writeJson("g3_field_inventory.json", sv.inv)
        local p2 = writeJson("g3_field_parity.json", sv.par)
        print(("KB_SURVEY maps=%d surveyed=%d cells=%d failed=%d file=%s"):format(sv.totals.maps, sv.totals.surveyed,
          sv.totals.cells, sv.totals.failed, word(p1 ~= nil and p2 ~= nil)))
        phase = "done"
      else
        local def = game.data.maps[id]
        sv.totals.maps = sv.totals.maps + 1
        local ok, st, why = pcall(staticMap, game, id, def, sv.classes)
        if not ok or not st then
          sv.inv.maps[id] = { err = word(ok and why or st):sub(1, 60) }
          phase = "map_next"
        else
          sv.inv.maps[id] = st
          sv.cur = { id = id, def = def, st = st }
          if skipped(id) then
            st.skipped = true
            phase = "map_next"
          else
            local x, y = arrivalCell(game, id)
            if not x then
              st.noArrival = true
              phase = "map_next"
            else
              -- the new game's story state, then a warp in (no fade)
              local Space = mod3("scripting.space")
              if Space and Space.store then
                for k in pairs(Space.store.flags or {}) do Space.store.flags[k] = nil end
                for k, v in pairs(sv.baseline.flags) do Space.store.flags[k] = v end
                for k in pairs(Space.store.vars or {}) do Space.store.vars[k] = nil end
                for k, v in pairs(sv.baseline.vars) do Space.store.vars[k] = v end
              end
              local okW, res, err = pcall(mod3("warp").request, nil, game, id, x, y, "down", { fade = false })
              if not okW or (res == nil and err ~= nil) then
                st.warpErr = word(okW and err or res):sub(1, 60)
                phase = "map_next"
              else
                frames = 0
                sv.cur.halted = false
                phase = "map_settle"
              end
            end
          end
        end
      end
    elseif phase == "map_settle" then
      frames = frames + 1
      local Space = mod3("scripting.space")
      local running = Space and Space.vm and Space.vm:isRunning()
      if frames >= SETTLE_FRAMES and not sv.cur.halted then sv.cur.halted = haltScripts() end
      if (frames >= SETTLE_MIN and fieldReady(game) and not running) or frames >= SETTLE_FRAMES + 240 then
        local Map = mod3("map")
        local cur = sv.cur
        local rec = { at = Map.current, halted = cur.halted or nil }
        if Map.current ~= cur.id then
          rec.left = true
        else
          local def = Map.currentDef and Map.currentDef() or cur.def
          local v, err = liveVerdicts(game, cur.id, def)
          if v then
            for k, val in pairs(v) do rec[k] = val end
            rec.overrides = overrides()
            sv.totals.surveyed = sv.totals.surveyed + 1
            sv.totals.cells = sv.totals.cells + v.w * v.h
          else
            rec.err = err
            sv.totals.failed = sv.totals.failed + 1
          end
        end
        sv.par.maps[cur.id] = rec
        local n = 0
        for _ in pairs(cur.st.counts) do n = n + 1 end
        print(("KB_SURVEY_MAP n=%d map=%s cells=%d warped=%s halted=%s classes=%d")
          :format(sv.i, word(cur.id), (cur.st.w or 0) * (cur.st.h or 0), word(not rec.left), word(cur.halted), n))
        phase = "map_next"
      end
    elseif phase == "done" then
      local deleted, now = cleanup(homeRef)
      print(("KB_CLEANED deleted=%d active=%d"):format(deleted, num(now)))
      print("KB_QUIT code=0")
      love.event.quit(0)
      return
    end
    if love.timer.getTime() - started > SECONDS_LIMIT then
      fail("timeout phase=" .. phase)
    end
    game = coroutine.yield() or game
  end
end
