-- Kanto Bridge test driver: Crystal's field mechanics read from the real
-- engine (the field mechanics audit, docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md
-- §4). Driven by tools/e2e/g2_field_check.py. Every scene first registers a
-- fresh empty save slot labelled KB_G2FIELD_TEST and makes it active (the
-- engine's slot API, src/core/SaveData.lua), uses driver mode's own start
-- (Gen 2 boots straight into the field on a new game, as kb_g2story.lua does)
-- and deletes the test slots again at the end. KB_SCENE picks the part:
--
--   survey   FA1 + FA2 in one walk over every map (KB_MAPS: only these). Per
--            map, statically (no warp): every cell's COLL byte (Map.defCellTile
--            through the tileset's collision quads, src/world/gen2/Map.lua:
--            167-182; the loaded map's own block edits undone first), classified
--            by the engine's own predicates (src/world/gen2/Permissions.lua,
--            src/core/CollPermissions.lua): the §3.1 classes; the warp table
--            with each warp cell's COLL byte and meaning and whether it lands
--            on the map itself (in-map ladders); the map header (environment,
--            palette, dark, tileset, connections). Then, on every map the audit
--            covers (the Battle Tower rooms, link rooms and mobile rooms left
--            out, D8, the story check's list), a warp in (World:warpToMapId:
--            the load runs the map's callbacks; onto its first warp's cell,
--            else the walkable or water cell nearest its middle), the field
--            settled (text, yes/no, movements and battles hold no script, as
--            kb_g2story.lua's sweep; a script still running after
--            KB_SETTLE_FRAMES is halted), and per cell and direction the
--            engine's own terrain verdict, asked without moving anyone and
--            without Player:tryMove (its movement.collision hook would report
--            to the bridge), composed in World:step's order (World.lua:
--            10716-10798, 11538-11550, Player.lua:79-135):
--              1. the target off the map: the edge, whatever the permission
--                 veto said (refusingMap keeps inBounds honest, so tryMove
--                 answers "edge"), then the read-only half of
--                 World:tryConnection (World.lua:10598-10619: the neighbour
--                 arm's veto on the landing, landable = surfable when surfing,
--                 else walkable); no ledge hop off an edge;
--              2. GetMovementPermissions: Permissions.stepPermitted with
--                 World:cellCollisionAcross (side walls and buoys, seams);
--              3. walk: the target walkable (LAND); surf: Permissions.surfable
--                 (LAND or WATER, World:surfMap);
--              4. walking and refused for any reason: World:tryLedgeJump's rule
--                 (the standing cell's ledgeFacings[dir], the landing two cells
--                 on inside the map and walkable).
--            People are left out (terrain only: no entity scan, no Snorlax
--            big-object veto). The bike is not dumped: in Gen 2 the bike runs
--            the walking arm (.Normal) with a shorter step, so bike == walk;
--            the Cycling Road's roll is a direction choice (Bike.forcedDirection),
--            not a collision. The cells where the engine moves the player on its
--            own (current, door push, whirlpool, ice) are the static part's
--            classes. The visit's live block edits (World.blockEdits against the
--            live blocks, what the bridge sends as story.blocks) are recorded
--            with the verdicts. Written to KB_REPORT_DIR/g2_field_inventory.json
--            and g2_field_parity.json (ROM-derived: they stay in local/).
--
--   scripts  FA1's script part, static: every map's callbacks, scene scripts,
--            coord events, bg events, objects and trainers, walked row by row
--            (iftrue/iffalse/ifequal/... targets, scall/farscall/callstd,
--            sjump/farsjump/jumpstd/stopandsjump, sdefer and writecmdqueue's
--            scripts followed, as kb_g2story.lua's scan; no conditions kept),
--            recording the rows that move or warp the player or change the
--            world while the player is there: warp, warpfacing, warpcheck,
--            warpmod, newloadmap, warpsound, blackoutmod; applymovement on
--            object 0 (the player) and applymovementlasttalked; changeblock,
--            changemapblocks; elevator; earthquake; reloadmap(part); specials
--            whose names say movement, warp, train, ferry, bike, fall or hole;
--            setflag/clearflag of ENGINE_DOWNHILL and ENGINE_ALWAYS_ON_BIKE
--            (World:engineFlagId's ids); and each map's coord events with their
--            scene id. Written to KB_REPORT_DIR/g2_field_scripts.json.
--
-- Verdict codes (one character per cell and direction, directions up, down,
-- left, right; cells row by row), the same alphabet as kb_g3field.lua's (Gen 2
-- has no elevation, so no "v" or "?"; no gates or Acro rails, so no "g"/"a"):
--   .  open (a step to the neighbour)       J  a ledge hop (lands two cells on)
--   x  refused: tile (a wall, a side wall)  w  refused: water (on foot)
--   b  refused: off the map, no connection
--   E  open across a connection              e  refused across a connection
--
-- Markers: KB_BOOTED, KB_FIELD map=, KB_SURVEY_MAP n= map= cells= warped=
-- halted= classes=, KB_SURVEY maps= surveyed= cells= failed= file=, KB_SCRIPTS
-- maps= roots= rows= warps= moves= blocks= elevators= specials= bikeflags=
-- coord= truncated= file=, KB_CLEANED deleted= active=, KB_QUIT code=;
-- KB_FAIL <reason>. Map symbols, ids, class names, counts and booleans only.
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- with the Game. Paths cited are refs/gen1recomp's (Gen1Recomp v0.3.85).

io.stdout:setvbuf("no")

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27229
local SCENE = os.getenv("KB_SCENE") or "survey"
local REPORT_DIR = os.getenv("KB_REPORT_DIR")
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or 3600
local DATASET_MEMO = tonumber(os.getenv("KB_DATASET_MEMO") or "") or 0
local SETTLE_MIN = tonumber(os.getenv("KB_SETTLE_MIN") or "") or 30
local SETTLE_FRAMES = tonumber(os.getenv("KB_SETTLE_FRAMES") or "") or 240 -- then a script still running is halted
local IDLE_FRAMES = tonumber(os.getenv("KB_IDLE_FRAMES") or "") or 15
local LABEL = "KB_G2FIELD_TEST"
local GAME = "crystal"
local HOP, HOP_ALT = "NEW_BARK_TOWN", "ROUTE_29"
local DEFAULT_HOUR, DEFAULT_DAY = 12, 1
local MAX_DEPTH, ROW_BUDGET = 48, 40000

local SaveData = require("src.core.SaveData")
local GameVersion = require("src.core.GameVersion")
local okOp, Opcodes = pcall(require, "src.script.gen2.Opcodes")

local floor = math.floor
local function word(v) return (tostring(v):gsub("[^%w_.:+%-]", "?")) end
local function num(id) return tonumber(tostring(id):match("^slot(%d+)$")) or 0 end
local function version() return GameVersion.get() end
local function active() return SaveData.activeSlot(version()) end
local function hex(b) return type(b) == "number" and ("0x%02x"):format(b) or "none" end

local function slots()
  local ok, list = pcall(SaveData.listSlots, version())
  return ok and type(list) == "table" and list or {}
end

-- Deletes every test slot (never another), making `home` active first.
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

local function memoDatasetChecks(seconds)
  local ok, DatasetViews = pcall(require, "src.mods.DatasetViews")
  if not ok or type(DatasetViews) ~= "table" or type(DatasetViews._ready) ~= "function" then return false end
  local ready = DatasetViews._ready
  DatasetViews._ready = function(self, view)
    local now = love.timer.getTime()
    if type(view) == "table" and view.kbReadyAt and now - view.kbReadyAt < seconds and not view.invalid
        and not view.unavailable then
      return true
    end
    local passed = ready(self, view)
    if passed and type(view) == "table" then view.kbReadyAt = now end
    return passed
  end
  return true
end

local function topState(game)
  local stack = game and game.stack
  return stack and stack.top and stack:top() or nil
end

-- The field is up and settled (kb_g2story.lua's test).
local function fieldReady(game)
  local w = game and game.world
  if not (game and game.phase == "play" and w and w.map and w.player) then return false end
  if topState(game) ~= nil then return false end
  local ok, accepts = pcall(w.acceptsMenuInput, w)
  if not (ok and accepts) then return false end
  local p = w.player
  if p.moving or (tonumber(p.turnTimer) or 0) > 0 or p.jumping then return false end
  return not (w.mapSetup or w.pendingSceneScript or w.pendingWarp or w.waitButton or w.peopleDirty)
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
    elseif v == floor(v) and math.abs(v) < 2 ^ 53 then
      out[#out + 1] = ("%d"):format(v)
    else
      out[#out + 1] = ("%.6g"):format(v)
    end
  elseif t == "string" then
    out[#out + 1] = '"' .. v:gsub('[%c"\\]', function(c)
      return ({ ['"'] = '\\"', ["\\"] = "\\\\", ["\n"] = "\\n", ["\t"] = "\\t" })[c] or ("\\u%04x"):format(c:byte())
    end) .. '"'
  elseif t == "table" then
    if getmetatable(v) == ARRAY or v[1] ~= nil then
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

-- ---- what the audit leaves out (D8: g2_story_check.py's SKIP_PREFIX)

local SKIP_PREFIX = { "BATTLE_TOWER_BATTLE_ROOM", "BATTLE_TOWER_ELEVATOR", "BATTLE_TOWER_HALLWAY", "TRADE_CENTER",
                      "COLOSSEUM", "TIME_CAPSULE", "MOBILE_TRADE_ROOM", "MOBILE_BATTLE_ROOM" }
local function skipped(id)
  for _, p in ipairs(SKIP_PREFIX) do
    if id:sub(1, #p) == p then return true end
  end
  return false
end

local DIRS = { "up", "down", "left", "right" }
local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
local DIR_CONN = { up = "north", down = "south", left = "west", right = "east" } -- World.lua:105

local function dirsText(set)
  if type(set) ~= "table" then return "" end
  local out = {}
  for _, d in ipairs(DIRS) do if set[d] then out[#out + 1] = d end end
  return table.concat(out, "+")
end

-- ---- classification: the engine's own predicates, by class name (§3.1)

local function perms()
  return require("src.world.gen2.Permissions")
end

-- The warp tiles by meaning (HI_NYBBLE_WARPS $7x; the names are pokecrystal's COLL_* constants, the
-- engine's own meaning of each is Permissions.isWarpCollision / isImmediateWarp / carpetDirection /
-- doorForcedDirection / warpFacesDown).
local WARP_MEANING = { [0x71] = "door", [0x72] = "ladder", [0x7a] = "staircase", [0x7b] = "cave", [0x7c] = "warp_panel" }

-- What a warp-collision byte is: its meaning, or nil when the byte is no warp tile.
local function warpMeaning(P, coll)
  if type(coll) ~= "number" or not P.isWarpCollision(coll) then return nil end
  if coll == 0x60 or coll == 0x68 then return "pit" end
  if P.carpetDirection(coll) then return "carpet" end
  return WARP_MEANING[coll] or "warp_unused"
end

-- Per class: the predicate over the COLL byte and, for the classes whose cells are listed, the extra
-- fact each listed cell carries (a direction set or a direction).
local function classifier()
  local P = perms()
  local function hi(c) return floor((c % 256) / 16) end
  local list = {
    { "ledge", function(c) return P.ledgeFacings(c) ~= nil end,
      function(c) return dirsText(P.ledgeFacings(c)) end },
    { "ledge_two_facings", function(c)
      local f = P.ledgeFacings(c)
      local n = 0
      for _ in pairs(type(f) == "table" and f or {}) do n = n + 1 end
      return n >= 2
    end, function(c) return dirsText(P.ledgeFacings(c)) end },
    { "side_wall", function(c) return P.isSideWall(c) and hi(c) == 0xb end,
      function(c) return dirsText(P.sideBlocks(c)) end },
    { "buoy", function(c) return P.isSideWall(c) and hi(c) == 0xc end,
      function(c) return dirsText(P.sideBlocks(c)) end },
    { "ice", function(c) return P.isIce(c) end },
    { "current", function(c) return P.currentDirection(c) ~= nil end,
      function(c) return P.currentDirection(c) end },
    { "waterfall", function(c) return P.isWaterfall(c) end },
    { "whirlpool", function(c) return P.isWhirlpool(c) end },
    { "pit", function(c) return warpMeaning(P, c) == "pit" end },
    { "door", function(c) return warpMeaning(P, c) == "door" end },
    { "ladder", function(c) return warpMeaning(P, c) == "ladder" end },
    { "staircase", function(c) return warpMeaning(P, c) == "staircase" end },
    { "cave", function(c) return warpMeaning(P, c) == "cave" end },
    { "warp_panel", function(c) return warpMeaning(P, c) == "warp_panel" end },
    { "carpet", function(c) return P.carpetDirection(c) ~= nil end,
      function(c) return P.carpetDirection(c) end },
    { "warp_unused", function(c) return warpMeaning(P, c) == "warp_unused" end, function(c) return hex(c) end },
    { "door_forced", function(c) return P.doorForcedDirection(c) ~= nil end,
      function(c) return P.doorForcedDirection(c) end },
    { "counter", function(c) return P.isCounter(c) end },
    { "grass", function(c) return P.isGrass(c) end },
    { "super_tall_grass", function(c) return P.isSuperTallGrass(c) end },
    { "cut_tree", function(c) return P.isCutTree(c) end },
    { "headbutt_tree", function(c) return P.isHeadbuttTree(c) end },
    -- HI_NYBBLE_WALK $4x / WALK_ALT $5x: pokecrystal's forced-walk tiles; the port's movement code has
    -- no arm for them (DoPlayerMovement's .CheckTile walk arms), so any cell here is a port gap
    { "unported_walk", function(c) return c >= 0x40 and c <= 0x5f end, function(c) return hex(c) end },
    -- the base permission (CollPermissions.of), counted only
    { "water", function(c) return P.isWater(c) end },
    { "wall", function(c) return P.isWall(c) and c ~= 0xff end },
    { "void", function(c) return c == 0xff end },
    { "land", function(c) return P.isLand(c) end },
  }
  return list
end

-- Common classes are counted only; every other class's cells are listed (map-relative, in local/).
local COMMON = { grass = true, super_tall_grass = true, counter = true, water = true, wall = true, void = true,
                 land = true }
-- The classes that say nothing beyond the base permission: a byte with only these is "unclassed".
local BASE = { water = true, wall = true, void = true, land = true }
-- The bytes whose meaning is the base permission itself (COLL_FLOOR, COLL_WALL, COLL_WATER, the $ff sentinel).
local PLAIN = { [0x00] = true, [0x07] = true, [0x29] = true, [0xff] = true }

-- The classes a cell has, as names in the classifier's order (pcall'd: a predicate that throws is skipped).
local function classesOf(list, c)
  local out = {}
  if type(c) ~= "number" then return out end
  for _, cl in ipairs(list) do
    local ok, yes = pcall(cl[2], c)
    if ok and yes then out[#out + 1] = cl end
  end
  return out
end

local function classNames(list, c)
  local names = {}
  for _, cl in ipairs(classesOf(list, c)) do names[#names + 1] = cl[1] end
  return table.concat(names, "|")
end

local function mapsOf(game)
  local w = game.world
  return (game.data and game.data.gen2Maps) or (w and w.maps) or {}
end

local function tilesetOf(game, def)
  local w = game.world
  local sets = (w and w.tilesets) or (game.data and game.data.gen2Tilesets) or {}
  return type(def) == "table" and sets[def.tileset] or nil
end

-- A view of a map def with its blocks as the ROM has them: the World edits the loaded map's def.blocks in
-- place (World:replaceBlock, World.lua:5842-5860) and keeps the originals in blockEdits[id] until the next
-- load (World:restoreBlocks, :5944-5958); every other map's blocks are its ROM blocks already.
local function pristineDef(game, id, def)
  local w = game.world
  local edits = w and type(w.blockEdits) == "table" and w.blockEdits[id] or nil
  if type(edits) ~= "table" or next(edits) == nil then return def end
  local blocks = {}
  for i, b in pairs(type(def.blocks) == "table" and def.blocks or {}) do blocks[i] = b end
  for i, b in pairs(edits) do
    if type(i) == "number" then blocks[i] = b end
  end
  return { width = def.width, height = def.height, borderBlock = def.borderBlock, blocks = blocks }
end

-- ---- the static part of a map (no warp)

local function staticMap(game, id, def, classes)
  local Map = require("src.world.gen2.Map")
  local P = perms()
  if type(def) ~= "table" or type(def.blocks) ~= "table" then return nil, "no_blocks" end
  local ts = tilesetOf(game, def)
  if type(ts) ~= "table" or type(ts.collision) ~= "table" then return nil, "no_tileset" end
  local src = pristineDef(game, id, def)
  local bw, bh = floor(tonumber(def.width) or 0), floor(tonumber(def.height) or 0)
  local tw, th = bw * 2, bh * 2
  local conns = array()
  for _, k in ipairs({ "north", "south", "west", "east" }) do
    local c = type(def.connections) == "table" and def.connections[k] or nil
    if type(c) == "table" and (c.mapId or c.map) then conns[#conns + 1] = { dir = k, to = c.mapId or c.map } end
  end
  local cbs = array()
  for _, cb in ipairs(type(def.callbacks) == "table" and def.callbacks or {}) do
    if type(cb) == "table" then cbs[#cbs + 1] = tostring(cb.callback or cb.type or "callback") end
  end
  local out = { w = tw, h = th, blocksW = bw, blocksH = bh, group = tonumber(def.group), map = tonumber(def.map),
                environment = def.environment, palette = def.palette, dark = def.palette == "PALETTE_DARK",
                tileset = def.tileset, connections = conns, callbacks = cbs,
                coordEvents = #(type(def.coordEvents) == "table" and def.coordEvents or {}),
                edited = src ~= def or nil,
                counts = {}, cells = {}, warps = array(), colls = {}, unclassed = {}, inMapLadders = 0 }
  local unknown = 0
  for y = 0, th - 1 do
    for x = 0, tw - 1 do
      local okC, c = pcall(Map.defCellTile, src, ts, x, y)
      c = okC and tonumber(c) or nil
      if c == nil or c < 0 or c > 0xff then
        unknown = unknown + 1
        out.counts.unknown = (out.counts.unknown or 0) + 1
      else
        local key = hex(c)
        out.colls[key] = (out.colls[key] or 0) + 1
        local cls = classesOf(classes, c)
        local extra = false
        for _, cl in ipairs(cls) do
          local name = cl[1]
          out.counts[name] = (out.counts[name] or 0) + 1
          if not BASE[name] then extra = true end
          if not COMMON[name] then
            out.cells[name] = out.cells[name] or array()
            local l = out.cells[name]
            local fact
            if cl[3] then
              local okF, f = pcall(cl[3], c)
              fact = okF and f or nil
            end
            l[#l + 1] = fact and fact ~= "" and array({ x, y, fact }) or array({ x, y })
          end
        end
        if not extra and not PLAIN[c] then out.unclassed[key] = (out.unclassed[key] or 0) + 1 end
      end
    end
  end
  for i, wp in ipairs(type(def.warps) == "table" and def.warps or {}) do
    if type(wp) == "table" then
      local x, y = tonumber(wp.x), tonumber(wp.y)
      local c
      if x and y then
        local okC, v = pcall(Map.defCellTile, src, ts, x, y)
        c = okC and tonumber(v) or nil
      end
      local meaning = c and warpMeaning(P, c) or nil
      local inMap = wp.destMap ~= nil and wp.destMap == id
      if inMap then out.inMapLadders = out.inMapLadders + 1 end
      out.warps[#out.warps + 1] = { n = i, x = x, y = y, to = wp.destMap, destWarp = tonumber(wp.destWarp),
                                    coll = c, collHex = c and hex(c) or nil, meaning = meaning or "none",
                                    inMap = inMap, classes = c and classNames(classes, c) or "" }
    end
  end
  return out
end

-- ---- the live part: verdicts on the bound map

-- The read-only half of World:tryConnection (World.lua:10598-10619): E open, e refused, b no connection.
local function edgeVerdict(w, map, x, y, dir, surfing)
  local P, Map = perms(), require("src.world.gen2.Map")
  local okC, conn = pcall(map.connection, map, DIR_CONN[dir])
  if not okC or type(conn) ~= "table" or not conn.mapId then return "b" end
  local dest = type(w.maps) == "table" and w.maps[conn.mapId] or nil
  if type(dest) ~= "table" then return "e" end
  local okL, lx, ly = pcall(Map.connectionLanding, dest, conn, dir, x, y)
  if not okL or not lx then return "e" end
  local okM, destMap = pcall(w.connectionMap, w, conn.mapId)
  if not okM or type(destMap) ~= "table" then return "e" end
  local dcoll = destMap:cellCollision(lx, ly)
  if P.neighborBlocks(dir, dcoll) == dir then return "e" end
  local landable
  if surfing then
    landable = P.surfable(dcoll) ~= nil
  else
    landable = destMap:isWalkable(lx, ly)
  end
  return landable and "E" or "e"
end

local function liveVerdicts(w)
  local P = perms()
  local map = w.map
  local tw = floor(tonumber(map.widthCells) or 0)
  local th = floor(tonumber(map.heightCells) or 0)
  local function collOf(x, y) return w:cellCollisionAcross(map, x, y) end
  local walk, surf, stand = {}, {}, {}

  local function verdicts(x, y, dir)
    local d = DELTA[dir]
    local tx, ty = x + d[1], y + d[2]
    if not map:inBounds(tx, ty) then
      return edgeVerdict(w, map, x, y, dir, false), edgeVerdict(w, map, x, y, dir, true)
    end
    local permitted = P.stepPermitted(collOf, x, y, dir)
    local tcoll = map:cellCollision(tx, ty)
    -- surfing: .CheckSurfPerms (LAND or WATER), no ledges
    local vs = (permitted and P.surfable(tcoll) ~= nil) and "." or "x"
    -- walking: .CheckLandPerms, then .TryJump on any refusal
    local vw
    if permitted and P.isWalkable(tcoll) then
      vw = "."
    else
      local facings = P.ledgeFacings(map:cellCollision(x, y))
      local lx, ly = x + d[1] * 2, y + d[2] * 2
      if type(facings) == "table" and facings[dir] and map:inBounds(lx, ly) and map:isWalkable(lx, ly) then
        vw = "J"
      elseif not permitted then
        vw = "x"
      elseif P.isWater(tcoll) then
        vw = "w"
      else
        vw = "x"
      end
    end
    return vw, vs
  end

  local ok, err = pcall(function()
    for y = 0, th - 1 do
      for x = 0, tw - 1 do
        -- where a player can stand: . land (a Gen 2 ledge cell is land), ~ water, # neither
        local c = map:cellCollision(x, y)
        stand[#stand + 1] = (P.isWater(c) and "~") or (P.isWalkable(c) and ".") or "#"
        for _, dir in ipairs(DIRS) do
          local vw, vs = verdicts(x, y, dir)
          walk[#walk + 1], surf[#surf + 1] = vw, vs
        end
      end
    end
  end)
  if not ok then return nil, word(err):sub(1, 80) end
  return { w = tw, h = th, walk = table.concat(walk), surf = table.concat(surf), stand = table.concat(stand) }
end

-- Every live block of the current map that differs from the map data (kb_g2story.lua's blockDiffs: the
-- World's own record of this visit's edits, World:replaceBlock), what the bridge sends as story.blocks.
local function blockDiffs(w)
  local m = w.map
  local live = type(m.def) == "table" and type(m.def.blocks) == "table" and m.def.blocks or {}
  local edits = type(w.blockEdits) == "table" and w.blockEdits[m.id] or {}
  local width = tonumber(m.width) or tonumber(m.def and m.def.width) or 1
  local out = array()
  for i, before in pairs(type(edits) == "table" and edits or {}) do
    if type(i) == "number" and live[i] ~= before then
      out[#out + 1] = { bx = (i - 1) % width, by = floor((i - 1) / width), block = live[i], was = before }
    end
  end
  table.sort(out, function(a, b) return a.by * 4096 + a.bx < b.by * 4096 + b.bx end)
  return out
end

-- Where the warp lands: the map's first warp's cell, else the walkable cell nearest its middle, else the
-- nearest water cell (from the static part's bytes).
local function arrivalCell(game, id, def)
  local warps = type(def.warps) == "table" and def.warps or {}
  local first = warps[1]
  if type(first) == "table" and tonumber(first.x) and tonumber(first.y) then return tonumber(first.x), tonumber(first.y) end
  local Map, P = require("src.world.gen2.Map"), perms()
  local ts = tilesetOf(game, def)
  if type(ts) ~= "table" then return nil end
  local src = pristineDef(game, id, def)
  local tw, th = floor(tonumber(def.width) or 0) * 2, floor(tonumber(def.height) or 0) * 2
  local cx, cy = floor(tw / 2), floor(th / 2)
  local best = {}
  for y = 0, th - 1 do
    for x = 0, tw - 1 do
      local ok, c = pcall(Map.defCellTile, src, ts, x, y)
      c = ok and tonumber(c) or nil
      local class = c and ((P.isWalkable(c) and not P.isLedge(c) and "walk") or (P.isWater(c) and "water")) or nil
      if class then
        local dist = math.abs(x - cx) + math.abs(y - cy)
        local b = best[class]
        if not b or dist < b.d then best[class] = { x = x, y = y, d = dist } end
      end
    end
  end
  local pick = best.walk or best.water
  if pick then return pick.x, pick.y end
  return nil
end

-- ---- the story state back to the new game's before each visit (kb_g2story.lua's snapshot / restore)

local function copy(t)
  local out = {}
  for k, v in pairs(type(t) == "table" and t or {}) do out[k] = type(v) == "table" and copy(v) or v end
  return out
end
local function refill(dst, src)
  if type(dst) ~= "table" then return end
  for k in pairs(dst) do dst[k] = nil end
  for k, v in pairs(src) do dst[k] = type(v) == "table" and copy(v) or v end
end

local function snapshot(game)
  local w, save = game.world, game.save or {}
  local player = save.player or {}
  local okE, events = pcall(function() return w.events:serialize() end)
  return { events = okE and events or nil, scenes = copy(w.mapScenes), engine = copy(save.engineFlags),
           badges = copy(player.badges), kanto = copy(player.kantoBadges), sprites = copy(w.variableSprites),
           mem = w.vm and w.vm.serializeMem and w.vm:serializeMem() or copy(w.vm and w.vm.mem),
           playerState = w.playerState, clockHour = w.clockHour, clockDay = w.clockDay }
end
local function restore(game, base)
  local w, save = game.world, game.save or {}
  local player = save.player or {}
  if base.events ~= nil then pcall(function() w.events:restore(copy(base.events)) end) end
  if type(w.mapScenes) == "table" then refill(w.mapScenes, base.scenes) end
  if type(save.engineFlags) == "table" then refill(save.engineFlags, base.engine) end
  if type(player.badges) == "table" then refill(player.badges, base.badges) end
  if type(player.kantoBadges) == "table" then refill(player.kantoBadges, base.kanto) end
  if type(w.variableSprites) == "table" then refill(w.variableSprites, base.sprites) end
  if w.vm and w.vm.restoreMem then pcall(w.vm.restoreMem, w.vm, copy(base.mem)) end
  if w.playerState ~= base.playerState and type(w.applyPlayerState) == "function" then
    pcall(w.applyPlayerState, w, base.playerState)
  end
end

-- What holds a script on the field: none of it during the survey (kb_g2story.lua's quietHooks, the VM's own
-- hooks, Vm.lua:2729-2767). A yes/no is answered no on the next frame; a script that raises an error ends there.
local yesno, scriptErrors = nil, 0
local HOOKS = { "showTextFn", "yesornoFn", "startBattleFn", "applyMovementFn", "waitButtonFn" }
local function quietHooks(vm)
  if type(vm) ~= "table" then return nil end
  local saved = { resume = rawget(vm, "resume") }
  for _, k in ipairs(HOOKS) do saved[k] = vm[k] end
  vm.showTextFn = nil
  vm.yesornoFn = function(done) yesno = done end
  vm.startBattleFn = nil
  vm.applyMovementFn = nil
  vm.waitButtonFn = nil
  local resume = vm.resume
  vm.resume = function(self, value)
    local ok = pcall(resume, self, value)
    if not ok then
      scriptErrors = scriptErrors + 1
      self.busy, self.co, self.pending = false, nil, nil
    end
  end
  return saved
end
local function loudHooks(vm, saved)
  if type(vm) ~= "table" or type(saved) ~= "table" then return end
  for _, k in ipairs(HOOKS) do vm[k] = saved[k] end
  vm.resume = saved.resume -- nil puts the class's method back
end
local function answerPending()
  if yesno then
    local done = yesno
    yesno = nil
    pcall(done, false)
  end
end

-- Stops a script that still holds the field (kb_g2story.lua's haltScripts, test-only).
local function haltScripts(game)
  local w = game.world
  local vm = w and w.vm
  local halted = false
  if vm and vm.busy then
    vm.busy, vm.co, vm.pending, vm.waitLeft, vm.waitSfx, vm.waitSfxLeft, vm.deferred = false, nil, nil, nil, nil, nil, nil
    halted = true
  end
  local n = 0
  while topState(game) ~= nil and n < 8 do
    pcall(game.stack.pop, game.stack)
    n = n + 1
    halted = true
  end
  if w then
    for _, k in ipairs({ "textbox", "moveState", "choicebox", "fishing", "headbutt", "fieldMove", "flyAnim", "skyfall",
                         "waitButton", "pendingSceneScript" }) do
      if w[k] ~= nil and w[k] ~= false then
        w[k] = nil
        halted = true
      end
    end
  end
  return halted
end

-- ---- the static script walk (FA1 §3.2)

local function arg(c, i)
  local a = type(c.args) == "table" and c.args or nil
  return a and tonumber(a[i]) or nil
end
local function word16(c, i) return (arg(c, i) or 0) + (arg(c, i + 1) or 0) * 256 end

local BRANCH = { iftrue = true, iffalse = true, ifequal = true, ifnotequal = true, ifgreater = true, ifless = true }
local RETURNS = { scall = true, farscall = true, callstd = true }
local JUMPS = { sjump = true, farsjump = true, jumpstd = true, stopandsjump = true }
local TERMINATORS = okOp and type(Opcodes) == "table" and type(Opcodes.TERMINATORS) == "table" and Opcodes.TERMINATORS
  or { sjump = true, farsjump = true, memjump = true, jumpstd = true, jumptext = true, farjumptext = true,
       jumptextfaceplayer = true, stopandsjump = true, ["end"] = true, endall = true, endcallback = true,
       reloadend = true, fruittree = true, describedecoration = true, halloffame = true, credits = true }
local PLAYER = 0 -- applymovement's object byte for the player (Vm.lua:442-451, World:beginMovement)
local SPECIAL_WORDS = { "movement", "move", "warp", "train", "ferry", "boat", "bike", "fall", "hole", "elevator" }

local function queueRows(c)
  local q = type(c.queue) == "table" and c.queue or {}
  local rows = type(q.rows) == "table" and q.rows or q
  return type(rows) == "table" and rows or {}
end

-- A map's entry points (kb_g2story.lua's rootsOf).
local function rootsOf(def)
  local out = {}
  local function add(kind, key, info)
    if type(key) == "string" or type(key) == "number" then out[#out + 1] = { kind = kind, key = key, info = info or {} } end
  end
  for _, cb in ipairs(type(def.callbacks) == "table" and def.callbacks or {}) do
    if type(cb) == "table" then add(tostring(cb.callback or cb.type or "callback"), cb.scriptKey) end
  end
  for s, sc in pairs(type(def.sceneScripts) == "table" and def.sceneScripts or {}) do
    if type(sc) == "table" then add("scene", sc.scriptKey, { scene = tonumber(sc.sceneId) or tonumber(s) }) end
  end
  for i, e in ipairs(type(def.coordEvents) == "table" and def.coordEvents or {}) do
    if type(e) == "table" then add("coord", e.scriptKey, { n = i, scene = tonumber(e.sceneId), x = tonumber(e.x), y = tonumber(e.y) }) end
  end
  for i, e in ipairs(type(def.bgEvents) == "table" and def.bgEvents or {}) do
    if type(e) == "table" then add("bg", e.scriptKey, { n = i, kind = tonumber(e.kind) }) end
  end
  for i, o in ipairs(type(def.objects) == "table" and def.objects or {}) do
    if type(o) == "table" then
      local info = { object = tonumber(o.index) or i }
      add("object", o.scriptKey, info)
      if type(o.trainer) == "table" then add("trainer", o.trainer.scriptKey, info) end
    end
  end
  return out
end

local function scripts(game)
  local w = game.world
  local data = game.data or {}
  local maps = mapsOf(game)
  local rows = data.gen2Scripts or (w and w.scripts) or {}
  local constants = data.gen2Constants or (w and w.constants) or {}
  local specialOrder = type(constants.specialOrder) == "table" and constants.specialOrder or {}
  local flagOrder = type(constants.engineFlagOrder) == "table" and constants.engineFlagOrder or {}
  local movements = w and w.vm and type(w.vm.movements) == "table" and w.vm.movements or {}
  local function specialName(id)
    id = tonumber(id)
    return id and specialOrder[id + 1] or ("special_" .. tostring(id))
  end
  local function flagId(name, goldId)
    local ok, id = pcall(w.engineFlagId, w, name, goldId)
    return ok and tonumber(id) or goldId
  end
  local Bike = package.loaded["src.world.gen2.Bike"] or select(2, pcall(require, "src.world.gen2.Bike"))
  local DOWNHILL = flagId("ENGINE_DOWNHILL", type(Bike) == "table" and Bike.ENGINE_DOWNHILL or 25)
  local ON_BIKE = flagId("ENGINE_ALWAYS_ON_BIKE", type(Bike) == "table" and Bike.ENGINE_ALWAYS_ON_BIKE or 24)
  local BIKE_FLAGS = { [DOWNHILL] = "ENGINE_DOWNHILL", [ON_BIKE] = "ENGINE_ALWAYS_ON_BIKE" }
  local function mapIdOf(group, mapNum)
    if not (group and mapNum) then return nil end
    local ok, id = pcall(w.mapIdByGroupMap, w, group, mapNum)
    return ok and id or nil
  end

  -- What one row records, or nil (and which total it counts in).
  local function recordOf(c)
    local op = c.op
    if op == "warp" or op == "warpfacing" then
      local base = op == "warpfacing" and 1 or 0
      local group = tonumber(c.group) or arg(c, base + 1)
      local mapNum = tonumber(c.map) or arg(c, base + 2)
      return { op = op, group = group, map = mapNum, to = mapIdOf(group, mapNum),
               x = tonumber(c.x) or arg(c, base + 3), y = tonumber(c.y) or arg(c, base + 4),
               facing = op == "warpfacing" and (tonumber(c.facing) or arg(c, 1)) or nil }, "warps"
    elseif op == "warpcheck" or op == "warpsound" then
      return { op = op }, "warps"
    elseif op == "warpmod" then
      local group, mapNum = tonumber(c.group) or arg(c, 2), tonumber(c.map) or arg(c, 3)
      return { op = op, warp = tonumber(c.warp) or arg(c, 1), group = group, map = mapNum, to = mapIdOf(group, mapNum) },
        "warps"
    elseif op == "blackoutmod" then
      local group, mapNum = tonumber(c.group) or arg(c, 1), tonumber(c.map) or arg(c, 2)
      return { op = op, group = group, map = mapNum, to = mapIdOf(group, mapNum) }, "warps"
    elseif op == "newloadmap" then
      return { op = op, setup = arg(c, 1) }, "warps"
    elseif op == "applymovement" or op == "applymovementlasttalked" then
      local object = op == "applymovement" and (tonumber(c.object) or arg(c, 1) or 0) or nil
      if op == "applymovementlasttalked" or object == PLAYER then
        local bytes = c.movement ~= nil and movements[c.movement] or nil
        return { op = op, player = object == PLAYER, lastTalked = op == "applymovementlasttalked" or nil,
                 movement = c.movement ~= nil and tostring(c.movement) or nil,
                 steps = type(bytes) == "table" and #bytes or nil }, "moves"
      end
    elseif op == "changeblock" then
      return { op = op, x = tonumber(c.x) or arg(c, 1), y = tonumber(c.y) or arg(c, 2),
               block = tonumber(c.block) or arg(c, 3) }, "blocks"
    elseif op == "changemapblocks" then
      return { op = op, bank = tonumber(c.bank) or arg(c, 1), address = tonumber(c.address) or word16(c, 2) }, "blocks"
    elseif op == "elevator" then
      return { op = op, floors = type(c.floors) == "table" and #c.floors or 0 }, "elevators"
    elseif op == "earthquake" then
      return { op = op, param = tonumber(c.param) or arg(c, 1) }, "other"
    elseif op == "reloadmap" or op == "reloadmappart" then
      return { op = op }, "other"
    elseif op == "special" then
      local name = specialName(c.id or word16(c, 1))
      local low = tostring(name):lower()
      for _, k in ipairs(SPECIAL_WORDS) do
        if low:find(k, 1, true) then return { op = op, name = name }, "specials" end
      end
    elseif op == "setflag" or op == "clearflag" then
      local flag = tonumber(c.flag) or word16(c, 1)
      if BIKE_FLAGS[flag] then
        return { op = op, flag = flag, name = BIKE_FLAGS[flag], orderName = flagOrder[flag + 1] }, "bikeflags"
      end
    end
    return nil
  end

  local ids = {}
  for id in pairs(maps) do if type(id) == "string" then ids[#ids + 1] = id end end
  table.sort(ids)
  local totals = { maps = 0, roots = 0, rows = 0, warps = 0, moves = 0, blocks = 0, elevators = 0, specials = 0,
                   bikeflags = 0, other = 0, coord = 0, truncated = 0 }
  local report = { game = GAME, maps = {}, names = { downhill = DOWNHILL, alwaysOnBike = ON_BIKE } }
  for _, id in ipairs(ids) do
    local def = maps[id]
    if type(def) == "table" then
      totals.maps = totals.maps + 1
      local coords = array()
      for i, e in ipairs(type(def.coordEvents) == "table" and def.coordEvents or {}) do
        if type(e) == "table" then
          coords[#coords + 1] = { n = i, x = tonumber(e.x), y = tonumber(e.y), sceneId = tonumber(e.sceneId),
                                  script = e.scriptKey ~= nil }
        end
      end
      totals.coord = totals.coord + #coords
      local m = { roots = 0, rows = 0, coord = coords, found = array(), skipped = skipped(id) or nil,
                  setsDownhill = nil, setsAlwaysOnBike = nil }
      for _, root in ipairs(rootsOf(def)) do
        m.roots = m.roots + 1
        local seen, budget, cut = {}, 0, false
        local seenRec = {}
        local function walk(key, depth)
          if depth > MAX_DEPTH then cut = true return end
          if key == nil or seen[key] then return end
          seen[key] = true
          local list = rows[key]
          if type(list) ~= "table" then return end
          for i, c in ipairs(list) do
            if type(c) == "table" then
              budget = budget + 1
              m.rows = m.rows + 1
              if budget > ROW_BUDGET then cut = true return end
              local okR, rec, bucket = pcall(recordOf, c)
              if okR and rec then
                local k = tostring(key) .. "#" .. i
                if not seenRec[k] then
                  seenRec[k] = true
                  rec.root, rec.rootInfo, rec.key, rec.row = root.kind, root.info, tostring(key), i
                  m.found[#m.found + 1] = rec
                  totals[bucket] = (totals[bucket] or 0) + 1
                  if rec.op == "setflag" and rec.name == "ENGINE_DOWNHILL" then m.setsDownhill = true end
                  if rec.op == "setflag" and rec.name == "ENGINE_ALWAYS_ON_BIKE" then m.setsAlwaysOnBike = true end
                end
              end
              local op = c.op
              if BRANCH[op] or RETURNS[op] or JUMPS[op] or op == "sdefer" then
                walk(c.script, depth + 1)
              elseif op == "writecmdqueue" then
                for _, r in ipairs(queueRows(c)) do
                  if type(r) == "table" then walk(r.scriptKey, depth + 1) end
                end
              end
              if TERMINATORS[op] or op == "unknown" or op == "truncated" then break end
            end
          end
        end
        walk(root.key, 0)
        if cut then
          totals.truncated = totals.truncated + 1
          m.truncated = (m.truncated or 0) + 1
        end
      end
      totals.roots = totals.roots + m.roots
      totals.rows = totals.rows + m.rows
      report.maps[id] = m
    end
  end
  report.totals = totals
  local path = writeJson("g2_field_scripts.json", report)
  print(("KB_SCRIPTS maps=%d roots=%d rows=%d warps=%d moves=%d blocks=%d elevators=%d specials=%d bikeflags=%d "
    .. "other=%d coord=%d truncated=%d file=%s"):format(totals.maps, totals.roots, totals.rows, totals.warps,
    totals.moves, totals.blocks, totals.elevators, totals.specials, totals.bikeflags, totals.other, totals.coord,
    totals.truncated, word(path ~= nil)))
end

-- ---- the survey loop

local function surveyList(game)
  local ids = {}
  for id, def in pairs(mapsOf(game)) do
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

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local sv, frames, idle, saved
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    answerPending()
    if phase == "ready" then
      local w = game and game.world
      if status and status.ready and game.phase == "play" and w and w.map then
        status.configure({ port = PORT })
        if DATASET_MEMO > 0 then
          print(("KB_DATASET_MEMO seconds=%d ok=%s"):format(DATASET_MEMO, tostring(memoDatasetChecks(DATASET_MEMO))))
        end
        homeRef = active()
        if SCENE == "cleanup" then
          local deleted, now = cleanup(homeRef)
          print(("KB_CLEANED deleted=%d active=%d"):format(deleted, num(now)))
          print("KB_QUIT code=0")
          love.event.quit(0)
          while true do coroutine.yield() end
        end
        local precleaned = cleanup(homeRef)
        local v = version()
        local slot = SaveData.createSlot(v)
        if not slot then fail("no_slot") end
        SaveData.renameSlot(v, slot, LABEL)
        if SaveData.setActiveSlot(v, slot) ~= slot then fail("activate_failed") end
        print(("KB_BOOTED scene=%s slot=%d home=%d precleaned=%d"):format(word(SCENE), num(slot), num(homeRef), precleaned))
        phase = "field"
      end
    elseif phase == "field" then
      if fieldReady(game) then
        local w = game.world
        print(("KB_FIELD map=%s"):format(word(w.map.id)))
        if SCENE == "scripts" then
          local ok, err = pcall(scripts, game)
          if not ok then fail("scripts " .. word(err):sub(1, 80)) end
          phase = "done"
        elseif SCENE == "survey" then
          local okC, classes = pcall(classifier)
          if not okC then fail("classifier " .. word(classes):sub(1, 60)) end
          sv = { ids = surveyList(game), i = 0, inv = { game = GAME, maps = {} }, par = { game = GAME, maps = {} },
                 classes = classes, totals = { maps = 0, surveyed = 0, cells = 0, failed = 0 } }
          sv.base = snapshot(game)
          saved = quietHooks(w.vm)
          w.clockHour, w.clockDay = DEFAULT_HOUR, DEFAULT_DAY
          phase = "map_next"
        else
          fail("unknown_scene")
        end
      end
    elseif phase == "map_next" then
      sv.i = sv.i + 1
      local id = sv.ids[sv.i]
      local w = game.world
      if not id then
        local p1 = writeJson("g2_field_inventory.json", sv.inv)
        local p2 = writeJson("g2_field_parity.json", sv.par)
        print(("KB_SURVEY maps=%d surveyed=%d cells=%d failed=%d file=%s"):format(sv.totals.maps, sv.totals.surveyed,
          sv.totals.cells, sv.totals.failed, word(p1 ~= nil and p2 ~= nil)))
        loudHooks(w.vm, saved)
        w.clockHour, w.clockDay = sv.base.clockHour, sv.base.clockDay
        phase = "done"
      else
        local def = mapsOf(game)[id]
        sv.totals.maps = sv.totals.maps + 1
        local ok, st, why = pcall(staticMap, game, id, def, sv.classes)
        if not ok or not st then
          sv.inv.maps[id] = { err = word(ok and why or st):sub(1, 60) }
        else
          sv.inv.maps[id] = st
          sv.cur = { id = id, def = def, st = st, halted = false }
          if skipped(id) then
            st.skipped = true
          else
            local okA, x, y = pcall(arrivalCell, game, id, def)
            if not (okA and x) then
              st.noArrival = true
            else
              sv.cur.x, sv.cur.y = x, y
              restore(game, sv.base)
              if w.map and w.map.id == id then
                -- a warp onto the map underfoot first hops to another (kb_g2story.lua's sweep)
                local hop = id == HOP and HOP_ALT or HOP
                local hdef = mapsOf(game)[hop]
                local okH, hx, hy = false, nil, nil
                if type(hdef) == "table" then okH, hx, hy = pcall(arrivalCell, game, hop, hdef) end
                local okW, res = false, nil
                if okH and hx then okW, res = pcall(w.warpToMapId, w, hop, hx, hy, "down") end
                frames, idle = 0, 0
                phase = (okW and res ~= false) and "map_hop" or "map_go"
              else
                phase = "map_go"
              end
            end
          end
        end
      end
    elseif phase == "map_hop" then
      frames = frames + 1
      idle = fieldReady(game) and idle + 1 or 0
      if idle >= IDLE_FRAMES or frames >= SETTLE_FRAMES then
        if frames >= SETTLE_FRAMES then haltScripts(game) end
        restore(game, sv.base)
        phase = "map_go"
      end
    elseif phase == "map_go" then
      local w = game.world
      local cur = sv.cur
      scriptErrors = 0
      local okW, res = pcall(w.warpToMapId, w, cur.id, cur.x, cur.y, "down")
      if not okW or res == false then
        cur.st.warpErr = okW and "warp_refused" or word(res):sub(1, 60)
        sv.par.maps[cur.id] = { left = true, err = cur.st.warpErr }
        sv.totals.failed = sv.totals.failed + 1
        local n = 0
        for _ in pairs(cur.st.counts) do n = n + 1 end
        print(("KB_SURVEY_MAP n=%d map=%s cells=%d warped=false halted=false classes=%d")
          :format(sv.i, word(cur.id), (cur.st.w or 0) * (cur.st.h or 0), n))
        phase = "map_next"
      else
        frames, idle = 0, 0
        phase = "map_settle"
      end
    elseif phase == "map_settle" then
      frames = frames + 1
      local w = game.world
      local ready = fieldReady(game)
      idle = ready and idle + 1 or 0
      if frames == SETTLE_FRAMES and not sv.cur.halted then sv.cur.halted = haltScripts(game) end
      if (frames >= SETTLE_MIN and idle >= IDLE_FRAMES) or frames >= SETTLE_FRAMES + 240 then
        local cur = sv.cur
        local at = w.map and w.map.id or nil
        local rec = { at = at, halted = cur.halted or nil, frames = frames,
                      scriptErrors = scriptErrors > 0 and scriptErrors or nil }
        if at ~= cur.id then
          rec.left = true
        else
          local okV, v, err = pcall(liveVerdicts, w)
          if okV and v then
            for k, val in pairs(v) do rec[k] = val end
            local okB, blocks = pcall(blockDiffs, w)
            rec.blocks = okB and blocks or array()
            local okD, down = pcall(w.downhill, w)
            local okO, bike = pcall(w.alwaysOnBike, w)
            rec.downhill = okD and down == true or nil
            rec.alwaysOnBike = okO and bike == true or nil
            rec.playerState = type(w.playerState) == "string" and w.playerState or nil
            sv.totals.surveyed = sv.totals.surveyed + 1
            sv.totals.cells = sv.totals.cells + v.w * v.h
          else
            rec.err = word(okV and err or v):sub(1, 80)
            sv.totals.failed = sv.totals.failed + 1
          end
        end
        sv.par.maps[cur.id] = rec
        local n = 0
        for _ in pairs(cur.st.counts) do n = n + 1 end
        print(("KB_SURVEY_MAP n=%d map=%s cells=%d warped=%s halted=%s classes=%d")
          :format(sv.i, word(cur.id), (cur.st.w or 0) * (cur.st.h or 0), tostring(not rec.left),
            tostring(cur.halted == true), n))
        phase = "map_next"
      end
    elseif phase == "done" then
      local deleted, now = cleanup(homeRef)
      print(("KB_CLEANED deleted=%d active=%d"):format(deleted, num(now)))
      print("KB_QUIT code=0")
      love.event.quit(0)
      while true do coroutine.yield() end
    end
    if love.timer.getTime() - started > SECONDS_LIMIT then
      fail("timeout phase=" .. phase)
    end
    game = coroutine.yield() or game
  end
end
