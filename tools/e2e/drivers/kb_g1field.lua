-- Kanto Bridge test driver: Blue's field mechanics read from the real engine
-- (the field mechanics audit, docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md §3-§4).
-- Driven by tools/e2e/g1_field_check.py. Every scene starts a new game without
-- the intro (src/core/Game.lua startNewGame, as kb_g1story.lua does; a new game
-- writes no save until the player saves, so no slot is touched) and moves
-- gen1-bridge to KB_TEST_PORT. KB_SCENE picks the part:
--
--   survey   FA1 + FA2 in one walk over every map (KB_MAPS: only these). Per
--            map, statically (Map.new(def, tileset), no warp; the map's blocks
--            back to the boot copy first): every cell classified by the
--            engine's own predicates (src/world/Map.lua:209-286): walkable,
--            water, shore, grass, door tile, warp tile, warp entry, sign,
--            counter, warp pad / hole under a warp entry (and the same tiles
--            with no warp, floor art), cut tree (OverworldState:tryCut's rule,
--            OC:3404-3427), ledge cells with their hop direction
--            (field.ledges, checkLedgeHop's row match, OC:2098-2152), tile-pair
--            edges (field.tilePairs, Collision.lua:40-54: two-way walls,
--            counted per map), spinner cells (field.spinners), forced
--            bike/surf tiles (field.forcedMovement.tiles), the slope map
--            (forcedMovement.slopeMaps), the Seafoam currents, forced exit,
--            boulder holes and surf-blocked cells (field.seafoam), badge gate
--            cells (field.badgeGates), boulders (Map.isPushable), and what
--            the map's onStep hook does on each cell: a probe calls
--            MapScripts.get(id).onStep (OC:4834) on every cell with a
--            stand-in overworld that records fallThroughHole / startWarpTo /
--            takeWarp / scriptMove / queueScript / ... with their arguments
--            (pcall; the save is a copy; the real flags, the hook's own
--            upvalues and TextBox.new are restored after) -- that is where
--            the 12 scripted hole cells come from. The map header (tileset,
--            surfable, dark, bike allowed, slope, connections, hooks) and the
--            warp table with each warp's kind by gen1-bridge's warpDeparture
--            rule (lib/engine/gen1.lua:1407-1433: pad/hole, door, stairs,
--            edge), read-only. The engine's classes are compared with
--            gen1-bridge's own per-cell flags (export/kb_cells.lua) where the
--            repo is found: mismatch counts per bit.
--            Then, on every map the audit covers (the link rooms and
--            KantoBridge's own Wild Routes maps left out, D8), a warp in
--            (WorldAPI:warpTo, WorldAPI.lua:358-371), the field settled (a box
--            answered B, a scene still running after KB_SETTLE_FRAMES halted,
--            as kb_g1story.lua does), and per cell and direction the engine's
--            own terrain verdict asked without moving the player, in
--            handleInput's order (OC:1791-1872):
--              off the map  checkEdgeExit (OC:2160-2184): a warp entry on the
--                           cell keeps the engine off the connection ('b',
--                           the warp itself listed apart), else the
--                           connection's landing (OC:connectionLanding,
--                           :2186-2211) with Map.defPassable: 'E' / 'e'; no
--                           connection 'b';
--              ledge        checkLedgeHop's row match, landing two cells on,
--                           in bounds and walkable (or across a connection,
--                           defPassable): 'J';
--              else         Collision.canMove(map, {}, {cellX, cellY,
--                           surfing}, dir) (Collision.lua:84-97): '.' or the
--                           refusal ('w' when a walker is refused water).
--            Objects are left out (an empty entity list): this is terrain.
--            The bike is not in Gen 1's verdict (Player:tryMove and
--            Collision.canMove never read it), so there is no bike string.
--            The cells where a step off the edge, or a blocked step, would
--            take a warp (Warp.onEdge / Warp.onCollision under the
--            standingOnWarp rule, OC:1781-1789) are listed per map. The visit's
--            live blocks that differ from the boot copy (what the bridge sends
--            as story.blocks) are recorded with the verdicts. Written to
--            KB_REPORT_DIR/g1_field_inventory.json and g1_field_parity.json
--            (ROM-derived: they stay in local/).
--
--   scripts  FA1's script part, static: per map MapScripts.get(id)'s hooks
--            (onStep, onEnter, onBoulderMoved, onInteract, onVictory; talk and
--            named script counts); a text scan of data/scripts/**.lua through
--            love.filesystem (as kb_g1story.scriptFlags) counting per file and
--            per map block: coordinate checks, {"warp", ...} rows,
--            {"move_player", ...} rows, scriptMove( calls, fallThroughHole(,
--            takeWarp(, startWarpTo(, replace_block / replaceBlock; the onStep
--            probe above; and the engine's coordinate tables (badgeGates,
--            forcedMovement, seafoam, spinners, the Safari step maps).
--            Written to KB_REPORT_DIR/g1_field_scripts.json.
--
--   sweep    FA3 + FA4, the live sweep: the rows in KB_ROWS (a Lua file
--            g1_field_check.py writes from the inventory, the parity dump
--            and the scripts file), one step each, the stand-in Minecraft
--            client of field_common.py stepping (kb_g3field.lua's design).
--            Per row the hidden player is put on the row's start cell: a
--            warp in (WorldAPI:warpTo, the new game's flags, toggles and
--            every map's boot blocks restored first, the map's trainers
--            beaten as kb_field.lua does) when the map differs, the row is
--            fresh or the row before changed the world; else placed on the
--            bound map by the player's own fields (setMap's placement,
--            OC:575-580; refreshStandingOnWarp, OC:1781-1789; the arrival
--            cell cleared). The vehicle is the engine's own state:
--            player.surfing, save.onBike (a slope row mounts only after the
--            client's go: the Cycling Road rolls at once, OC:1883-1905);
--            REPEL and the encounter grace (ow.wildEncounterGraceSteps,
--            OC:4791-4806) keep wild battles out; a boulder row sets
--            ow.strengthActive (OC:1198) and asks for two steps (dirs=d+d:
--            checkBoulderPush's first try only arms, the second pushes,
--            OC:1934-1993). Then KB_ROW_READY n= dir= push= [dirs=]; the client
--            steps (move.step) and writes KB_CTL; the driver waits until
--            the field is quiet (fieldReady and none of update's in-flight
--            states, OC:1678-1684) for the row's idle frames or its budget
--            (halted), records the engine's end state (map, cell, facing,
--            surfing, on the bike), the row's live block changes on the
--            same map ({bx, by, block, was}: story.blocks), whether the
--            story state changed, then KB_ROW_DONE and 0.8 s by the clock.
--            trigger="warp" rows (a map whose entry sends the player on)
--            print READY first and warp after the go (driven). Written to
--            KB_REPORT_DIR/<KB_SWEEP_FILE> (g1_field_sweep.json).
--
-- Verdict codes (one character per cell and direction, directions up, down,
-- left, right; cells row by row), the same alphabet as kb_g3field.lua:
--   .  open (a step to the neighbour)       J  a ledge hop (lands two cells on)
--   x  refused: tile                         w  refused: water (on foot)
--   b  refused: off the map                  E  open across a connection
--   e  refused across a connection
--
-- Markers: KB_BOOTED scene= pristine=, KB_FIELD map=, KB_SURVEY_MAP n= map=
-- cells= warped= halted= classes=, KB_SURVEY maps= surveyed= cells= failed=
-- file=, KB_SCRIPTS maps= hooks= onstep= files= coord= warps= moves=
-- scriptmoves= holes= probecells= file=, KB_SWEEP_READY rows=, KB_ROW_READY
-- n= dir= push=, KB_ROW_DONE n= map= cls= end= moved= changed= frames= halted=
-- err=, KB_SWEEP_DONE rows= file=, KB_QUIT code=; KB_FAIL <reason>.
-- Map symbols, ids, class names, counts and booleans only; no script text.
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- with the Game (refs/gen1recomp/main.lua:755-759, :1131-1150). OC =
-- refs/gen1recomp/src/world/OverworldController.lua (Gen1Recomp v0.3.85).

io.stdout:setvbuf("no")

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27230
local SCENE = os.getenv("KB_SCENE") or "survey"
local REPORT_DIR = os.getenv("KB_REPORT_DIR")
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or 5400
local EXTRA_SECONDS = tonumber(os.getenv("KB_DRIVER_EXTRA") or "") or 0 -- the runner's export allowance
local DATASET_MEMO = tonumber(os.getenv("KB_DATASET_MEMO") or "") or 0
local SETTLE_FRAMES = tonumber(os.getenv("KB_SETTLE_FRAMES") or "") or 600 -- then a scene still running is halted
local IDLE_FRAMES = tonumber(os.getenv("KB_IDLE_FRAMES") or "") or 15
local ANSWER_FRAMES = 4 -- a box is answered this many frames after it comes up
local GAME = "blue"

local floor = math.floor
local function word(v) return (tostring(v):gsub("[^%w_.:+%-]", "?")) end

local function fail(reason)
  print(("KB_FAIL %s"):format(reason))
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

-- the repository this driver sits in (for gen1-bridge's own kb_cells.lua)
local function repoRoot()
  local me = os.getenv("POKEPORT_DRIVER") or ""
  return me:match("^(.*)[/\\]tools[/\\]e2e[/\\]drivers[/\\][^/\\]+$")
end

local function loadKbCells()
  local repo = repoRoot()
  if not repo then return nil end
  local chunk = loadfile(repo .. "/gen1-bridge/export/kb_cells.lua")
  if not chunk then return nil end
  local ok, mod = pcall(chunk)
  return ok and type(mod) == "table" and mod or nil
end

local function overworld(game)
  local states = game and game.stack and game.stack.states or {}
  for i = #states, 1, -1 do
    local s = states[i]
    if type(s) == "table" and s.isOverworld then
      if s.map and s.player then return s end
      return nil
    end
  end
  return nil
end

local function topState(game)
  local stack = game and game.stack
  return stack and stack.top and stack:top() or nil
end

-- The field is up and settled: the overworld on top, no warp, no script running or queued, no scripted move, the
-- player standing still (kb_g1story.lua's test).
local function fieldReady(game)
  local ow = overworld(game)
  if not ow or topState(game) ~= ow or ow.transitioning then return false end
  local p = ow.player
  if p.moving or p.inputLocked then return false end
  if ow.runner and ow.runner.isRunning and ow.runner:isRunning() then return false end
  if #(ow.scriptMoves or {}) > 0 or #(ow.pendingScripts or {}) > 0 or ow.emote then return false end
  return true
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

local function copy(t, depth)
  depth = depth or 0
  local out = {}
  if depth > 12 then return out end
  for k, v in pairs(type(t) == "table" and t or {}) do
    out[k] = type(v) == "table" and copy(v, depth + 1) or v
  end
  return out
end

-- a symbol-like string (map ids, directions, flag names) or nil: what may go into a report
local function sym(v)
  return type(v) == "string" and #v <= 64 and v:match("^[%w_%.:%-]+$") ~= nil
end

-- ---- the boot copy of every map's blocks (taken before any map loads; kb_g1story.lua)

local pristine = {}
local function capture(game)
  local n = 0
  for id, def in pairs(game.data and game.data.maps or {}) do
    if type(id) == "string" and type(def) == "table" and type(def.blocks) == "table" then
      local b = {}
      for i = 1, #def.blocks do b[i] = def.blocks[i] end
      pristine[id] = b
      n = n + 1
    end
  end
  return n
end

local function resetMapBlocks(game, id)
  local b = pristine[id]
  local def = game.data.maps[id]
  local live = def and def.blocks
  if b and type(live) == "table" then
    for i = 1, #b do if live[i] ~= b[i] then live[i] = b[i] end end
  end
end

local function resetBlocks(game)
  for id in pairs(pristine) do resetMapBlocks(game, id) end
end

local function snapshot(game)
  local save = game.save
  return { flags = copy(save.flags), toggles = copy(save.objectToggles), flashLit = save.flashLit,
           safari = save.safari and copy(save.safari) or nil, onBike = save.onBike, forcedBike = save.forcedBike }
end

local function restore(game, base)
  local save = game.save
  if type(save.flags) == "table" then
    for k in pairs(save.flags) do save.flags[k] = nil end
    for k, v in pairs(base.flags) do save.flags[k] = v end
  end
  save.objectToggles = copy(base.toggles)
  save.flashLit = base.flashLit
  save.safari = base.safari and copy(base.safari) or nil
  save.onBike = base.onBike
  save.forcedBike = base.forcedBike
  resetBlocks(game)
end

-- every live block of the current map that differs from the boot copy (kb_g1story.lua)
local function blockDiffs(ow)
  local m = ow.map
  local def = m.def
  local live = type(def) == "table" and def.blocks or {}
  local b = pristine[m.id] or {}
  local w = tonumber(def and def.width) or 1
  local out = array()
  for i = 1, #live do
    if b[i] ~= nil and live[i] ~= b[i] then
      out[#out + 1] = { bx = (i - 1) % w, by = floor((i - 1) / w), block = live[i], was = b[i] }
    end
  end
  return out
end

-- ---- what the audit leaves out (D8; g1_story_check.SKIP_PREFIX + the Wild Routes maps, kb_routes.MAP_PREFIX)

local SKIP_PREFIX = { "TRADE_CENTER", "COLOSSEUM", "UNION_ROOM", "KB_ROUTE_" }
local function skipped(id)
  for _, p in ipairs(SKIP_PREFIX) do
    if id:sub(1, #p) == p then return true end
  end
  return false
end

local DIRS = { "up", "down", "left", "right" }
local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
local COMPASS = { up = "north", down = "south", left = "west", right = "east" }

-- engine constants this driver needs and the engine keeps local:
-- the shore tiles (Map.lua:17-19: $32 and $48, none on SHIP_PORT, a tileset's shoreTiles wins)
local SHORE_FALLBACK = { 0x32, 0x48 }
local NO_SHORE_TILESETS = { SHIP_PORT = true }
-- tryCut's tile gate (OC:3414-3420): OVERWORLD tree $3d, GYM plant $50 (grass $52 is cut too, counted as grass)
local CUT_TILES = { OVERWORLD = { [0x3d] = true }, GYM = { [0x50] = true } }
-- warpPadOrHoleAt's vanilla rows (Map.lua:31-35), to find pad/hole tiles with no warp entry
local PAD_TILES = { FACILITY = { [0x20] = "pad", [0x11] = "hole" }, CAVERN = { [0x22] = "hole" },
                    INTERIOR = { [0x55] = "pad" } }

local function inList(list, v)
  for _, x in ipairs(type(list) == "table" and list or {}) do if x == v then return true end end
  return false
end

local function fieldDefaults()
  local ok, FD = pcall(require, "src.world.FieldDefaults")
  return ok and type(FD) == "table" and FD or nil
end

local function fieldOf(game, key)
  local FD = fieldDefaults()
  if FD and type(FD.field) == "function" then
    local ok, v = pcall(FD.field, game.data, key)
    if ok then return v end
  end
  return type(game.data.field) == "table" and game.data.field[key] or nil
end

local function fieldValue(game, key, ...)
  local FD = fieldDefaults()
  if FD and type(FD.fieldValue) == "function" then
    local ok, v = pcall(FD.fieldValue, game.data, key, ...)
    if ok then return v end
  end
  return nil
end

-- the map's hooks, MapScripts.get (src/script/MapScripts.lua:196-210)
local function hooksOf(id)
  local ok, MS = pcall(require, "src.script.MapScripts")
  if not ok or type(MS) ~= "table" or type(MS.get) ~= "function" then return nil end
  local okG, hooks = pcall(MS.get, id)
  return okG and type(hooks) == "table" and hooks or nil
end

local function hookFlags(hooks)
  if type(hooks) ~= "table" then return nil end
  local out = {}
  for _, k in ipairs({ "onStep", "onEnter", "onBoulderMoved", "onInteract", "onVictory" }) do
    out[k] = type(hooks[k]) == "function" or (type(hooks[k]) == "table" and getmetatable(hooks[k]) ~= nil)
  end
  local nt, ns = 0, 0
  for _ in pairs(type(hooks.talk) == "table" and hooks.talk or {}) do nt = nt + 1 end
  for _ in pairs(type(hooks.scripts) == "table" and hooks.scripts or {}) do ns = ns + 1 end
  out.talk, out.scripts = nt, ns
  return out
end

-- ---- the onStep probe: a stand-in overworld that records what the hook asks for

-- a call's argument as report data: symbols, numbers, booleans; the player; a row list summarized; never text
local function argOf(v, player)
  local t = type(v)
  if t == "number" or t == "boolean" then return v end
  if t == "string" then return sym(v) and v or "str" end
  if t == "function" then return "fn" end
  if t == "table" then
    if v == player then return "player" end
    if type(v[1]) == "table" and type(v[1][1]) == "string" then
      local rows = array()
      for _, r in ipairs(v) do
        if type(r) == "table" and type(r[1]) == "string" then
          local op = r[1]
          if op == "warp" then
            rows[#rows + 1] = { op = op, map = sym(r[2]) and r[2] or nil, x = tonumber(r[3]), y = tonumber(r[4]),
                                facing = sym(r[5]) and r[5] or nil }
          elseif op == "move_player" then
            rows[#rows + 1] = { op = op, dir = sym(r[2]) and r[2] or nil, n = tonumber(r[3]) or 1 }
          elseif op == "replace_block" then
            rows[#rows + 1] = { op = op, bx = tonumber(r[2]), by = tonumber(r[3]), block = tonumber(r[4]) }
          elseif sym(op) then
            rows[#rows + 1] = { op = op }
          end
        end
      end
      return { rows = rows }
    end
    if v.def and type(v.def) == "table" and sym(v.def.destMap) then
      return { warp = v.def.destMap, destWarp = tonumber(v.def.destWarp) }
    end
    if sym(v.destMap) then return { warp = v.destMap, destWarp = tonumber(v.destWarp) } end
    return "table"
  end
  return t
end

-- the scalar upvalues of a hook (and of the functions it closes over, two levels), to put back after the probe
local function snapUpvalues(fn, depth, out, seen)
  if type(debug) ~= "table" or type(debug.getupvalue) ~= "function" or depth > 2 or seen[fn] then return out end
  seen[fn] = true
  local i = 1
  while true do
    local okU, name, val = pcall(debug.getupvalue, fn, i)
    if not okU or name == nil then break end
    if type(val) == "function" then
      snapUpvalues(val, depth + 1, out, seen)
    elseif type(val) ~= "table" and type(val) ~= "userdata" and type(val) ~= "thread" then
      out[#out + 1] = { fn = fn, i = i, v = val }
    end
    i = i + 1
  end
  return out
end

local function putUpvalues(list)
  if type(debug) ~= "table" or type(debug.setupvalue) ~= "function" then return end
  for _, u in ipairs(list) do pcall(debug.setupvalue, u.fn, u.i, u.v) end
end

local CALLBACK_DEPTH = 3

-- Calls hooks.onStep(game, ow, x, y) on every cell of the map, as onStepComplete does after a step (OC:4832-4838),
-- with a stand-in game (its save a copy, its stack recording what is pushed) and a stand-in overworld whose methods
-- (every function OverworldState has) record their call. A box's onDone and a scriptMove's onDone are called too
-- (CALLBACK_DEPTH deep), so a text-then-push-back gate shows its push. Returns { cells, sigs, err } or nil.
local function probeOnStep(game, id, m)
  local hooks = hooksOf(id)
  if not hooks or type(hooks.onStep) ~= "function" then return nil end
  local okOC, OC = pcall(require, "src.world.OverworldController")
  local okTB, TB = pcall(require, "src.render.TextBox")
  if not okOC or type(OC) ~= "table" then return { err = "no_oc" } end
  local realNew = okTB and type(TB) == "table" and TB.new or nil
  local realSave = game.save
  local base = snapshot(game)
  local ups = snapUpvalues(hooks.onStep, 0, {}, {})
  local saveCopy = copy(realSave)
  local calls, depth, player
  local function record(name, ...)
    local args = array()
    local n = select("#", ...)
    for i = 1, math.min(n, 6) do args[#args + 1] = argOf((select(i, ...)), player) end
    calls[#calls + 1] = { fn = name, a = args }
  end
  local function callBack(cb, ...)
    if type(cb) == "function" and depth < CALLBACK_DEPTH then
      depth = depth + 1
      local ok = pcall(cb, ...)
      if not ok then calls[#calls + 1] = { fn = "callback_error" } end
      depth = depth - 1
    end
  end
  local stack = { states = {} }
  function stack.push(_, st)
    if type(st) == "table" and rawget(st, "kbProbeBox") then
      calls[#calls + 1] = { fn = "text", choice = rawget(st, "choice") and true or nil }
      callBack(rawget(st, "onDone"))
    else
      calls[#calls + 1] = { fn = "push_state" }
    end
  end
  function stack.pop() calls[#calls + 1] = { fn = "pop_state" } end
  function stack.top() return nil end
  local stubGame = setmetatable({ save = saveCopy, data = game.data, stack = stack, input = game.input,
                                  mods = game.mods }, {
    __index = function(_, k)
      local v = game[k]
      if type(v) == "function" then
        return function(_, ...) record("game." .. tostring(k), ...) end
      end
      return v
    end,
  })
  local runner = setmetatable({}, {
    __index = function(_, k)
      return function(_, ...) record("runner." .. tostring(k), ...) return nil end
    end,
  })
  local special = {
    scriptMove = function(_, entity, dir, tiles, onDone, opts)
      calls[#calls + 1] = { fn = "scriptMove", who = entity == player and "player" or "npc",
                            dir = sym(dir) and dir or nil, n = tonumber(tiles) or 1,
                            collide = type(opts) == "table" and opts.collide and true or nil }
      callBack(onDone)
    end,
    marchInPlace = function(_, entity, onDone)
      calls[#calls + 1] = { fn = "marchInPlace", who = entity == player and "player" or "npc" }
      callBack(onDone)
    end,
  }
  local ow
  local function newOw(x, y)
    player = { cellX = x, cellY = y, facing = "down", surfing = false, px = x * 16, py = y * 16 }
    ow = setmetatable({ player = player, map = m, npcs = {}, entities = {}, scriptMoves = {}, pendingScripts = {},
                        runner = runner, cutBlocks = {}, isOverworld = true }, {
      __index = function(_, k)
        if special[k] then return special[k] end
        if type(OC[k]) == "function" then
          return function(_, ...) record(tostring(k), ...) return nil end
        end
        return nil
      end,
    })
    return ow
  end
  if realNew then
    TB.new = function(_, _, onDone, opts)
      return setmetatable({ kbProbeBox = true, onDone = onDone, choice = type(opts) == "table" and opts.choice },
        { __index = function() return function() end end })
    end
  end
  local sigs, order, cellsDone, errors = {}, array(), 0, 0
  local dirty = false
  local okAll, errAll = pcall(function()
    for y = 0, m.heightCells - 1 do
      for x = 0, m.widthCells - 1 do
        if dirty then
          saveCopy = copy(realSave)
          rawset(stubGame, "save", saveCopy)
          dirty = false
        end
        calls, depth = array(), 0
        newOw(x, y)
        local ok, ret = pcall(hooks.onStep, stubGame, ow, x, y)
        cellsDone = cellsDone + 1
        if not ok then
          errors = errors + 1
          calls[#calls + 1] = { fn = "error" }
        end
        if (ok and ret) or #calls > 0 then
          dirty = true
          local out = {}
          encode(calls, out)
          local sig = (ok and ret and "T|" or "F|") .. table.concat(out)
          local s = sigs[sig]
          if not s then
            s = { ret = ok and ret and true or false, calls = calls, cells = array() }
            sigs[sig] = s
            order[#order + 1] = s
          end
          s.cells[#s.cells + 1] = { x, y }
        end
      end
    end
  end)
  if realNew then TB.new = realNew end
  putUpvalues(ups)
  -- the real save's story state back, in case a hook reached it another way; this map's blocks back
  restore(game, base)
  local n = 0
  for _, s in ipairs(order) do n = n + #s.cells end
  return { probed = cellsDone, cells = n, errors = errors, sigs = order,
           err = (not okAll) and word(errAll):sub(1, 80) or nil }
end

-- the probe's signatures as classes: scripted holes, step warps, moves, scripts, text, block writes
local function probeClasses(probe)
  local out = {}
  local function add(name, cell, extra)
    out[name] = out[name] or array()
    local rec = { cell[1], cell[2] }
    if extra then rec[3] = extra end
    out[name][#out[name] + 1] = rec
  end
  for _, s in ipairs(type(probe) == "table" and probe.sigs or {}) do
    local has = {}
    local hole
    for _, c in ipairs(s.calls) do
      local fn = c.fn
      if fn == "fallThroughHole" then
        has.scripted_hole = true
        hole = hole or { map = c.a and c.a[1], x = c.a and c.a[2], y = c.a and c.a[3] }
      elseif fn == "startWarpTo" or fn == "takeWarp" or fn == "warpToHealPoint" or fn == "flyTo" then
        has.step_warp = true
      elseif fn == "scriptMove" and c.who == "player" then
        has.step_move = true
      elseif fn == "text" then
        has.step_text = true
      elseif fn == "replaceBlock" then
        has.step_block = true
      elseif fn == "queueScript" or fn == "startParallel" or fn:sub(1, 7) == "runner." then
        has.step_script = true
      end
      for _, a in ipairs(type(c.a) == "table" and c.a or {}) do
        if type(a) == "table" and type(a.rows) == "table" then
          for _, r in ipairs(a.rows) do
            if r.op == "warp" then has.step_warp = true end
            if r.op == "move_player" then has.step_move = true end
            if r.op == "replace_block" then has.step_block = true end
          end
        end
      end
    end
    if s.ret and not next(has) then has.step_consumed = true end
    for name in pairs(has) do
      for _, cell in ipairs(s.cells) do add(name, cell, name == "scripted_hole" and hole or nil) end
    end
  end
  return out
end

-- ---- the static part of a map (no warp)

-- classes counted but not listed per cell (grass and counter cells are listed: the sweep samples them, 10 a map)
local COMMON = { walk = true, water = true, shore = true }

local function staticMap(game, id, def, kbCells)
  local data = game.data
  local ts = data.tilesets and data.tilesets[def.tileset]
  if type(ts) ~= "table" then return nil, "no_tileset" end
  local Map = require("src.world.Map")
  local okN, m = pcall(Map.new, def, ts)
  if not okN or type(m) ~= "table" then return nil, "map_new " .. word(m):sub(1, 40) end
  local field = type(data.field) == "table" and data.field or {}
  local w, h = m.widthCells, m.heightCells
  local out = { w = w, h = h, tileset = def.tileset, counts = {}, cells = {}, warps = array(), boulders = array(),
                pairEdges = { land = array(), water = array() } }
  local function add(name, x, y, extra)
    out.counts[name] = (out.counts[name] or 0) + 1
    if not COMMON[name] then
      out.cells[name] = out.cells[name] or array()
      local rec = { x, y }
      if extra ~= nil then rec[3] = extra end
      out.cells[name][#out.cells[name] + 1] = rec
    end
  end
  local function ask(method, ...)
    local fn = m[method]
    if type(fn) ~= "function" then return nil end
    local ok, v = pcall(fn, m, ...)
    if not ok then return nil end
    return v
  end

  -- the header
  out.surfable = inList(field.waterTilesets, def.tileset)
  local dark = fieldOf(game, "darkMaps")
  out.dark = type(dark) == "table" and inList(dark.maps, id) or false
  local okOC, OC = pcall(require, "src.world.OverworldController")
  if okOC and type(OC) == "table" and type(OC.bikeAllowed) == "function" then
    local okB, b = pcall(OC.bikeAllowed, { map = m }, id)
    out.bikeAllowed = okB and b and true or false
  end
  local fm = field.forcedMovement
  out.slope = type(fm) == "table" and inList(fm.slopeMaps, id) or false
  out.clearsForcedBike = inList(fieldValue(game, "forcedMovement", "clearMaps"), id)
  out.safariSteps = inList(fieldValue(game, "safari", "stepMaps"), id)
  out.elevator = id:find("ELEVATOR", 1, true) ~= nil
  local okO, outdoor = pcall(Map.isOutdoor, def)
  out.outdoor = okO and outdoor and true or false
  local okS, outside = pcall(Map.isOutside, def, fieldOf(game, "outsideTilesets"))
  out.outside = okS and outside and true or false
  out.connections = {}
  for dir, c in pairs(type(def.connections) == "table" and def.connections or {}) do
    if type(c) == "table" and sym(c.map) then out.connections[tostring(dir)] = { map = c.map, offset = tonumber(c.offset) } end
  end
  out.hooks = hookFlags(hooksOf(id))

  -- per cell: the Map predicates
  local shore = {}
  for _, t in ipairs(ts.shoreTiles or (not NO_SHORE_TILESETS[def.tileset] and SHORE_FALLBACK) or {}) do shore[t] = true end
  local warpTiles = {}
  for _, t in ipairs(ts.warpTiles or {}) do warpTiles[t] = true end
  local cutTiles = CUT_TILES[def.tileset] or {}
  local cutBlocks = {}
  for _, sw in ipairs(type(field.cutTreeSwaps) == "table" and field.cutTreeSwaps or {}) do
    if type(sw) == "table" and sw.before ~= nil then cutBlocks[sw.before] = true end
  end
  local padTiles = ts.warpPadTiles or PAD_TILES[def.tileset] or {}
  local ledges = {}
  for _, row in ipairs(type(field.ledges) == "table" and field.ledges or {}) do
    if type(row) == "table" and (row.tileset or "OVERWORLD") == def.tileset and row.facing == row.input
        and DELTA[row.facing] then
      ledges[#ledges + 1] = row
    end
  end
  local mine = {} -- per cell, the bits kb_cells would set, by the engine's own answers (for the comparison)
  for y = 0, h - 1 do
    for x = 0, w - 1 do
      local tile = ask("cellTile", x, y)
      local walk = ask("isWalkableCell", x, y) and true or false
      local water = ask("isWaterCell", x, y) and true or false
      local f = {}
      if walk then add("walk", x, y) f.walk = true end
      if water then
        if shore[tile] then add("shore", x, y) else add("water", x, y) end
        if out.surfable then f.water = true end
      end
      if ask("isGrassCell", x, y) then add("grass", x, y) f.grass = true end
      local door = ask("isDoorTileCell", x, y) and true or false
      if door then add("door", x, y) f.door = true end
      if warpTiles[tile] then f.warptile = true end
      if ask("isWarpTileCell", x, y) and not door then add("warp_tile", x, y) end
      if ask("isCounterCell", x, y) then add("counter", x, y) f.counter = true end
      local warp = ask("warpAtCell", x, y)
      if warp then add("warp", x, y, type(warp) == "table" and warp.index or nil) f.warp = true end
      if ask("signAtCell", x, y) then add("sign", x, y) f.sign = true end
      local pad = ask("warpPadOrHoleAt", x, y)
      if warp and (pad == "pad" or pad == "hole") then
        add(pad, x, y)
        f[pad] = true
      elseif tile ~= nil and padTiles[tile] then
        add(padTiles[tile] .. "_tile_no_warp", x, y)
      end
      if tile ~= nil and cutTiles[tile] and not walk and cutBlocks[ask("blockAt", floor(x / 2), floor(y / 2))] then
        add("cut", x, y)
        f.cut = true
      end
      -- a ledge cell: its tile is a row's ledgeTile and the cell behind it (against the hop) the row's standingTile
      for _, row in ipairs(ledges) do
        local d = DELTA[row.facing]
        local sx, sy = x - d[1], y - d[2]
        if tile == row.ledgeTile and sx >= 0 and sy >= 0 and sx < w and sy < h
            and ask("cellTile", sx, sy) == row.standingTile then
          if not f.ledge then add("ledge", x, y, row.facing) end
          f.ledge = f.ledge or row.facing
        end
      end
      mine[y * w + x] = f
      -- tile-pair edges with the right and lower neighbours (Collision.lua:40-54's rule: either order)
      for _, nb in ipairs({ { 1, 0, "right" }, { 0, 1, "down" } }) do
        local nx, ny = x + nb[1], y + nb[2]
        if nx < w and ny < h then
          local b = ask("cellTile", nx, ny)
          for _, kind in ipairs({ "land", "water" }) do
            local list = type(field.tilePairs) == "table" and field.tilePairs[kind] or nil
            for _, p in ipairs(type(list) == "table" and list or {}) do
              if type(p) == "table" and p.tileset == def.tileset
                  and ((p.a == tile and p.b == b) or (p.a == b and p.b == tile)) then
                local edges = out.pairEdges[kind]
                edges[#edges + 1] = { x, y, nb[3] }
                out.counts["pair_" .. kind] = (out.counts["pair_" .. kind] or 0) + 1
                break
              end
            end
          end
        end
      end
    end
  end

  -- the field tables' cells on this map
  for _, sp in ipairs(type(field.spinners) == "table" and type(field.spinners[id]) == "table" and field.spinners[id] or {}) do
    if type(sp) == "table" and tonumber(sp.x) then
      add("spinner", sp.x, sp.y, #(type(sp.moves) == "table" and sp.moves or {}))
      local f = mine[sp.y * w + sp.x]
      if f then f.spinner = true end
    end
  end
  for _, t in ipairs(type(fm) == "table" and type(fm.tiles) == "table" and type(fm.tiles[id]) == "table" and fm.tiles[id] or {}) do
    if type(t) == "table" and tonumber(t.x) then add("forced_" .. word(t.mode), t.x, t.y) end
  end
  local sf = type(field.seafoam) == "table" and field.seafoam[id] or nil
  if type(sf) == "table" then
    for _, c in ipairs(type(sf.currents) == "table" and sf.currents or {}) do
      if type(c) == "table" and tonumber(c.x) then add("seafoam_current", c.x, c.y) end
    end
    if type(sf.entryCurrent) == "table" and tonumber(sf.entryCurrent.x) then
      add("seafoam_entry_current", sf.entryCurrent.x, sf.entryCurrent.y)
    end
    local fe = type(sf.forcedExit) == "table" and sf.forcedExit.coords or nil
    for _, c in ipairs(type(fe) == "table" and fe or {}) do
      if type(c) == "table" and tonumber(c.x) then add("seafoam_forced_exit", c.x, c.y) end
    end
    for _, c in ipairs(type(sf.holes) == "table" and sf.holes or {}) do
      if type(c) == "table" and tonumber(c.x) then add("seafoam_boulder_hole", c.x, c.y) end
    end
    out.seafoam = { holeDestination = sym(sf.holeDestination) and sf.holeDestination or nil,
                    setsForcedWarp = fieldValue(game, "seafoam", id, "setsForcedWarp") and true or false,
                    pluggedBy = type(sf.pluggedByHolesOn) == "table" and sym(sf.pluggedByHolesOn.map)
                      and sf.pluggedByHolesOn.map or nil }
  end
  local surfBlocked = fieldValue(game, "seafoam", id, "surfBlocked")
  for _, c in ipairs(type(surfBlocked) == "table" and surfBlocked or {}) do
    if type(c) == "table" and tonumber(c.x) then add("surf_blocked", c.x, c.y) end
  end
  local gate = type(field.badgeGates) == "table" and field.badgeGates[id] or nil
  if type(gate) == "table" then
    for _, c in ipairs(type(gate.coords) == "table" and gate.coords or {}) do
      if type(c) == "table" and tonumber(c.x) then add("badge_gate", c.x, c.y, sym(gate.badge) and gate.badge or nil) end
    end
    for _, g in ipairs(type(gate.guards) == "table" and gate.guards or {}) do
      local gy = type(g) == "table" and tonumber(g.y)
      if gy and gy >= 0 and gy < h then
        for gx = 0, math.min(w - 1, tonumber(g.maxX) or (w - 1)) do
          add("badge_gate", gx, gy, sym(g.badge) and g.badge or nil)
        end
      end
    end
  end
  for i, o in ipairs(type(def.objects) == "table" and def.objects or {}) do
    if type(o) == "table" and not o.runtime then
      local okP, push = pcall(Map.isPushable, o)
      if okP and push then
        out.boulders[#out.boulders + 1] = { x = tonumber(o.x), y = tonumber(o.y), index = tonumber(o.index) or i,
                                            name = sym(o.name) and o.name or nil, hidden = o.hidden == true }
        out.counts.boulder = (out.counts.boulder or 0) + 1
      end
    end
  end
  if out.slope then out.counts.slope_map = 1 end
  if out.dark then out.counts.dark_map = 1 end

  -- the onStep probe
  local probe = probeOnStep(game, id, m)
  if probe then
    out.onStep = probe
    for name, list in pairs(probeClasses(probe)) do
      out.counts[name] = #list
      out.cells[name] = list
    end
  end

  -- the warp table, each warp's kind by gen1-bridge's warpDeparture rule (read-only)
  local carpets = type(field.warpCarpets) == "table" and field.warpCarpets or nil
  for i, wp in ipairs(type(def.warps) == "table" and def.warps or {}) do
    local x, y = tonumber(wp.x), tonumber(wp.y)
    local rec = { n = i, x = x, y = y, to = sym(wp.destMap) and wp.destMap or nil, destWarp = tonumber(wp.destWarp) }
    if x and y then
      local pad = ask("warpPadOrHoleAt", x, y)
      local door = ask("isDoorTileCell", x, y) and true or false
      local wt = ask("isWarpTileCell", x, y) and true or false
      local off = array()
      for _, dir in ipairs(DIRS) do
        local d = DELTA[dir]
        if not ask("inBounds", x + d[1], y + d[2]) then off[#off + 1] = dir end
      end
      if pad == "pad" or pad == "hole" then
        rec.kind = pad
      elseif door then
        rec.kind = "door"
      elseif wt then
        rec.kind = "stairs"
      else
        -- facing off the map: edge; facing into it: door (the carpet maps); statically, edge when an edge is there
        rec.kind = #off > 0 and "edge" or "door"
      end
      rec.offEdge = off
      rec.standingOnWarp = not (wt and not door)
      rec.fires = wt and "arrive" or (#off > 0 and "edge" or "collision")
      rec.inBounds = ask("inBounds", x, y) and true or false
      rec.inside = x >= 0 and y >= 0 and x < w and y < h
    end
    out.warps[#out.warps + 1] = rec
  end
  out.carpets = carpets ~= nil

  -- gen1-bridge's own per-cell flags against the engine's answers (counts of disagreeing cells per bit)
  if kbCells and type(kbCells.compute) == "function" then
    local okC, rows = pcall(function()
      local q = { w = w, h = h }
      function q.tile(cx, cy) return ask("cellTile", cx, cy) end
      function q.walkable(cx, cy) return ask("isWalkableCell", cx, cy) and true or false end
      function q.grass(cx, cy) return ask("isGrassCell", cx, cy) and true or false end
      function q.water(cx, cy) return ask("isWaterCell", cx, cy) and true or false end
      function q.door(cx, cy) return ask("isDoorTileCell", cx, cy) and true or false end
      function q.counter(cx, cy) return ask("isCounterCell", cx, cy) and true or false end
      function q.padOrHole(cx, cy) return ask("warpPadOrHoleAt", cx, cy) end
      function q.block(bx, by) return ask("blockAt", bx, by) end
      local spin = {}
      for _, sp in ipairs(type(field.spinners) == "table" and type(field.spinners[id]) == "table" and field.spinners[id] or {}) do
        spin[#spin + 1] = sp
      end
      local cutSet = {}
      for t in pairs(cutTiles) do cutSet[t] = true end
      return kbCells.compute(q, {
        surfable = out.surfable, shore = shore, cutTiles = cutSet, cutBlocks = cutBlocks, warpTiles = warpTiles,
        ledges = kbCells.ledgeRows(field.ledges, def.tileset), warps = def.warps, signs = def.signs, spinners = spin,
      })
    end)
    if okC and type(rows) == "table" then
      local bits = { walk = kbCells.WALK, water = kbCells.WATER, grass = kbCells.GRASS, door = kbCells.DOOR,
                     warptile = kbCells.WARP_TILE, counter = kbCells.COUNTER, cut = kbCells.CUT, pad = kbCells.PAD,
                     hole = kbCells.HOLE, spinner = kbCells.SPINNER, warp = kbCells.WARP, sign = kbCells.SIGN,
                     ledge = kbCells.LEDGE }
      local diff = {}
      local dirIndex = kbCells.LEDGE_DIRS or {}
      for y = 0, h - 1 do
        for x = 0, w - 1 do
          local flags = rows[y + 1] and rows[y + 1][x + 1] or 0
          local f = mine[y * w + x] or {}
          for name, bit in pairs(bits) do
            if type(bit) == "number" and kbCells.has(flags, bit) ~= (f[name] and true or false) then
              diff[name] = (diff[name] or 0) + 1
            end
          end
          if f.ledge and kbCells.has(flags, kbCells.LEDGE) and kbCells.LEDGE_UNIT then
            local d = floor(flags / kbCells.LEDGE_UNIT) % 4
            if dirIndex[f.ledge] ~= d then diff.ledge_dir = (diff.ledge_dir or 0) + 1 end
          end
        end
      end
      out.kbCells = { checked = true, diff = diff }
    else
      out.kbCells = { checked = false, err = word(rows):sub(1, 60) }
    end
  end
  return out
end

-- ---- the live part: verdicts on the bound map

-- The engine's verdict for a step from (x, y) toward dir, in handleInput's order (OC:1814-1828), without moving
-- anyone: checkEdgeExit, checkLedgeHop's row match, Player:tryMove's Collision.canMove (Player.lua:149-178).
local function liveVerdicts(game, ow)
  local Collision = require("src.world.Collision")
  local Map = require("src.world.Map")
  local Warp = require("src.world.Warp")
  local OC = require("src.world.OverworldController")
  local m = ow.map
  local def = m.def
  local data = game.data
  local w, h = m.widthCells, m.heightCells
  local ledges = {}
  for _, row in ipairs(type(data.field.ledges) == "table" and data.field.ledges or {}) do
    if type(row) == "table" and (row.tileset or "OVERWORLD") == def.tileset and row.facing == row.input then
      ledges[#ledges + 1] = row
    end
  end
  local carpets = data.field.warpCarpets
  local walk, surf, stand = {}, {}, {}
  -- surfing is possible only on the tilesets the field table names (OC:tilesetHasWater, :3318)
  local surfable = inList(data.field.waterTilesets, def.tileset)
  local edgeWarps = array()
  local landing = {} -- per off-map step: OC.connectionLanding with a stand-in self (the real function, read-only)

  local function connectionLanding(x, y, dir)
    local key = ("%d:%d:%s"):format(x, y, dir)
    local hit = landing[key]
    if hit == nil then
      local ok, dest, ts, lx, ly = pcall(OC.connectionLanding, { map = m, player = { cellX = x, cellY = y } }, dir)
      hit = (ok and dest) and { dest, ts, lx, ly } or false
      landing[key] = hit
    end
    if not hit then return nil end
    return hit[1], hit[2], hit[3], hit[4]
  end

  local function standingOnWarp(x, y)
    -- refreshStandingOnWarp (OC:1781-1789)
    return m:warpAtCell(x, y) ~= nil and not (m:isWarpTileCell(x, y) and not m:isDoorTileCell(x, y))
  end

  local function verdict(x, y, dir, surfing)
    local d = DELTA[dir]
    local tx, ty = x + d[1], y + d[2]
    if not m:inBounds(tx, ty) then
      -- checkEdgeExit (OC:2160-2184): a warp entry here keeps the engine off the connection either way
      if Warp.onEdge(m, x, y, dir) then return "b" end
      if m:connection(COMPASS[dir]) then
        local dest, ts, lx, ly = connectionLanding(x, y, dir)
        if dest and Map.defPassable(dest, ts, lx, ly, surfing) then return "E" end
        return "e"
      end
      return "b"
    end
    -- checkLedgeHop (OC:2098-2152): the row match; entities left out
    local standing, front = m:cellTile(x, y), m:cellTile(tx, ty)
    for _, row in ipairs(ledges) do
      if row.facing == dir and row.standingTile == standing and row.ledgeTile == front then
        local lx, ly = tx + d[1], ty + d[2]
        if not m:inBounds(lx, ly) then
          -- the landing on the connected map: OC.connectionLanding from the ledge cell, then defPassable
          -- (from the player's cell, as the engine asks it: only the coordinate across the edge is used)
          local dest, ts, cx, cy = connectionLanding(x, y, dir)
          if dest and Map.defPassable(dest, ts, cx, cy, surfing) then return "J" end
        elseif m:isWalkableCell(lx, ly) then
          return "J"
        end
      end
    end
    local ok, why = Collision.canMove(m, {}, { cellX = x, cellY = y, surfing = surfing }, dir)
    if ok then return "." end
    if why == "bounds" then return "b" end
    if why == "tile" and not surfing and m:isWaterCell(tx, ty) then return "w" end
    return "x"
  end

  local counts = { cells = 0 }
  local ok, err = pcall(function()
    for y = 0, h - 1 do
      for x = 0, w - 1 do
        counts.cells = counts.cells + 1
        -- where a player can stand: . walkable, ~ water (surfable), # neither
        stand[#stand + 1] = (m:isWalkableCell(x, y) and ".") or (surfable and m:isWaterCell(x, y) and "~") or "#"
        local onWarp = m:warpAtCell(x, y) ~= nil and standingOnWarp(x, y)
        for _, dir in ipairs(DIRS) do
          local a, s = verdict(x, y, dir, false), verdict(x, y, dir, true)
          walk[#walk + 1], surf[#surf + 1] = a, s
          if onWarp then
            local d = DELTA[dir]
            if not m:inBounds(x + d[1], y + d[2]) then
              if Warp.onEdge(m, x, y, dir) then edgeWarps[#edgeWarps + 1] = { x, y, dir, "edge" } end
            elseif a ~= "." and a ~= "J" then
              -- a blocked step on a warp square: Warp.onCollision's extra check (OC:1835-1846)
              local okC, wc = pcall(Warp.onCollision, m, carpets, x, y, dir)
              if okC and wc then edgeWarps[#edgeWarps + 1] = { x, y, dir, "collision" } end
            end
          end
        end
      end
    end
  end)
  if not ok then return nil, word(err):sub(1, 80) end
  return { w = w, h = h, walk = table.concat(walk), surf = table.concat(surf), stand = table.concat(stand), edgeWarps = edgeWarps,
           bikeNote = "gen1 verdict ignores the bike" }
end

-- Where the warp lands: the map's first warp's cell, else the first walkable dry cell (kb_g1story.lua).
local function arrivalCell(game, mapId)
  local def = game.data.maps[mapId]
  local warps = type(def.warps) == "table" and def.warps or {}
  if type(warps[1]) == "table" and tonumber(warps[1].x) then return tonumber(warps[1].x), tonumber(warps[1].y) end
  local ts = game.data.tilesets and game.data.tilesets[def.tileset]
  local ok, m = pcall(require("src.world.Map").new, def, ts)
  if ok and m then
    for y = 0, (tonumber(def.height) or 1) * 2 - 1 do
      for x = 0, (tonumber(def.width) or 1) * 2 - 1 do
        local okW, walkable = pcall(m.isWalkableCell, m, x, y)
        local okD, wet = pcall(m.isWaterCell, m, x, y)
        if okW and walkable and not (okD and wet) then return x, y end
      end
    end
  end
  return floor((tonumber(def.width) or 2)), floor((tonumber(def.height) or 2))
end

-- A box on top of the field is answered B a few frames after it comes up; a battle is ended (test-only: the states
-- above the overworld popped). kb_g1story.lua's.
local function answer(game, st)
  local top = topState(game)
  local ow = overworld(game)
  if top == nil or top == ow then
    st.boxFrames = 0
    return
  end
  if top.isBattleState then
    st.battles = (st.battles or 0) + 1
    local n = 0
    while topState(game) ~= ow and topState(game) ~= nil and n < 8 do
      pcall(game.stack.pop, game.stack)
      n = n + 1
    end
    return
  end
  st.boxFrames = (st.boxFrames or 0) + 1
  if st.boxFrames >= ANSWER_FRAMES then
    st.boxFrames = 0
    st.answered = (st.answered or 0) + 1
    game.input:sourcePress("b", "kb-g1field")
    game.input:sourceRelease("b", "kb-g1field")
  end
end

-- Stops a scene that still holds the field (test-only), as kb_g1story.lua's halt.
local function halt(game)
  local ow = overworld(game)
  local n = 0
  while ow and topState(game) ~= ow and topState(game) ~= nil and n < 8 do
    pcall(game.stack.pop, game.stack)
    n = n + 1
  end
  if not ow then return n > 0 end
  local halted = n > 0
  if #(ow.scriptMoves or {}) > 0 then ow.scriptMoves = {} halted = true end
  if #(ow.pendingScripts or {}) > 0 then ow.pendingScripts = {} halted = true end
  if ow.runner and ow.runner.isRunning and ow.runner:isRunning() then
    local okR, ScriptRunner = pcall(require, "src.script.ScriptRunner")
    if okR and ScriptRunner and ScriptRunner.new then ow.runner = ScriptRunner.new(game, ow) end
    halted = true
  end
  if ow.emote then ow.emote = nil halted = true end
  if ow.player then ow.player.inputLocked = false end
  return halted
end

-- ---- the static script part (FA1 §3.2)

-- per line patterns: name -> function(line) returning a list of found records (coordinates and map symbols only)
local function scanLine(line)
  local found = {}
  local function push(rec) found[#found + 1] = rec end
  for x, y in line:gmatch("[%w_%.]*[xX]%s*==%s*(%d+)%s+and%s+[%w_%.]*[yY]%s*==%s*(%d+)") do
    push({ op = "coord", x = tonumber(x), y = tonumber(y) })
  end
  for y, x in line:gmatch("[%w_%.]*[yY]%s*==%s*(%d+)%s+and%s+[%w_%.]*[xX]%s*==%s*(%d+)") do
    push({ op = "coord", x = tonumber(x), y = tonumber(y) })
  end
  for x, y in line:gmatch("[%w_%.]*[xX]%s*~=%s*(%d+)%s+or%s+[%w_%.]*[yY]%s*~=%s*(%d+)") do
    push({ op = "coord_not", x = tonumber(x), y = tonumber(y) })
  end
  for map, x, y in line:gmatch('{%s*"warp"%s*,%s*"([%w_]+)"%s*,%s*([%w_%.]+)%s*,%s*([%w_%.]+)') do
    push({ op = "warp_row", map = map, x = tonumber(x), y = tonumber(y) })
  end
  for dir, n in line:gmatch('{%s*"move_player"%s*,%s*"(%a+)"%s*,?%s*(%d*)') do
    push({ op = "move_player", dir = dir, n = tonumber(n) or 1 })
  end
  for who in line:gmatch("scriptMove%(%s*([%w_%.]*)") do
    push({ op = "scriptMove", player = who:find("player", 1, true) ~= nil or who == "p" })
  end
  for rest in line:gmatch("fallThroughHole%(([^)]*)") do
    local map, x, y = rest:match('^%s*"([%w_]+)"%s*,%s*(%d+)%s*,%s*(%d+)')
    push({ op = "fallThroughHole", map = map, x = tonumber(x), y = tonumber(y) })
  end
  for _ in line:gmatch("takeWarp%(") do push({ op = "takeWarp" }) end
  for rest in line:gmatch("startWarpTo%(([^)]*)") do
    local map, x, y = rest:match('^%s*"([%w_]+)"%s*,%s*(%d+)%s*,%s*(%d+)')
    push({ op = "startWarpTo", map = map, x = tonumber(x), y = tonumber(y) })
  end
  for _ in line:gmatch('"replace_block"') do push({ op = "replace_block" }) end
  for _ in line:gmatch("replaceBlock%(") do push({ op = "replaceBlock" }) end
  return found
end

local function textScan(game, report, totals)
  local fs = love.filesystem
  local maps = game.data.maps
  local files = {}
  local function scanFile(path)
    local okR, text = pcall(fs.read, path)
    if not okR or type(text) ~= "string" then return end
    local rec = { counts = {}, blocks = {} }
    local current = "(file)"
    local lineNo = 0
    for line in (text .. "\n"):gmatch("([^\n]*)\n") do
      lineNo = lineNo + 1
      -- a map's block: M.MAP_ID..., M["MAP_ID"], or MAP_ID = { (a map id the game has); a top-level local or
      -- function leaves it
      local name = line:match("^M%.([A-Z][A-Z0-9_]*)") or line:match('^M%[%s*"([A-Z0-9_]+)"%s*%]')
      if not name then
        local n2 = line:match("^%s*([A-Z][A-Z0-9_]+)%s*=%s*[{%w]")
        if n2 and maps[n2] ~= nil then name = n2 end
      end
      if name and maps[name] ~= nil then
        current = name
      elseif line:match("^local%s") or line:match("^function%s") or line:match("^return%s") then
        current = "(file)"
      end
      local code = line:gsub("%-%-.*$", "")
      if code:match("%S") then
        for _, f in ipairs(scanLine(code)) do
          rec.counts[f.op] = (rec.counts[f.op] or 0) + 1
          local b = rec.blocks[current]
          if not b then
            b = { counts = {}, found = array() }
            rec.blocks[current] = b
          end
          b.counts[f.op] = (b.counts[f.op] or 0) + 1
          f.line = lineNo
          b.found[#b.found + 1] = f
          totals[f.op] = (totals[f.op] or 0) + 1
          if current ~= "(file)" then
            local m = report.maps[current]
            if m then
              m.text = m.text or {}
              m.text[f.op] = (m.text[f.op] or 0) + 1
            end
          end
        end
      end
    end
    rec.lines = lineNo
    files[path] = rec
  end
  local function walkDir(dir)
    local okI, items = pcall(fs.getDirectoryItems, dir)
    for _, item in ipairs(okI and items or {}) do
      local path = dir .. "/" .. item
      local info = fs.getInfo(path)
      if info and info.type == "directory" then
        walkDir(path)
      elseif item:match("%.lua$") then
        scanFile(path)
      end
    end
  end
  walkDir("data/scripts")
  return files
end

local function engineTables(game)
  local field = type(game.data.field) == "table" and game.data.field or {}
  local out = {}
  -- badge gates (OC:5050-5095): coords or guard rows; badge and event names are symbols
  local gates = {}
  for id, g in pairs(type(field.badgeGates) == "table" and field.badgeGates or {}) do
    if type(g) == "table" then
      local rec = { badge = sym(g.badge) and g.badge or nil, coords = array(), guards = array() }
      for _, c in ipairs(type(g.coords) == "table" and g.coords or {}) do
        if type(c) == "table" then rec.coords[#rec.coords + 1] = { x = tonumber(c.x), y = tonumber(c.y) } end
      end
      for _, c in ipairs(type(g.guards) == "table" and g.guards or {}) do
        if type(c) == "table" then
          rec.guards[#rec.guards + 1] = { y = tonumber(c.y), maxX = tonumber(c.maxX),
                                          badge = sym(c.badge) and c.badge or nil, event = sym(c.event) and c.event or nil }
        end
      end
      gates[tostring(id)] = rec
    end
  end
  out.badgeGates = gates
  -- forced bike/surf tiles, slope and clear maps (OC:5125-5170, :1915-1930)
  local fm = type(field.forcedMovement) == "table" and field.forcedMovement or {}
  local tiles = {}
  for id, list in pairs(type(fm.tiles) == "table" and fm.tiles or {}) do
    local l = array()
    for _, t in ipairs(type(list) == "table" and list or {}) do
      if type(t) == "table" then l[#l + 1] = { x = tonumber(t.x), y = tonumber(t.y), mode = sym(t.mode) and t.mode or nil } end
    end
    tiles[tostring(id)] = l
  end
  local function list(v)
    local l = array()
    for _, x in ipairs(type(v) == "table" and v or {}) do if sym(x) then l[#l + 1] = x end end
    return l
  end
  out.forcedMovement = { tiles = tiles, slopeMaps = list(fm.slopeMaps),
                         clearMaps = list(fieldValue(game, "forcedMovement", "clearMaps")) }
  -- the Seafoam table (OC:5176-5245)
  local sea = {}
  local function cells(list)
    local l = array()
    for _, c in ipairs(type(list) == "table" and list or {}) do
      if type(c) == "table" then
        l[#l + 1] = { x = tonumber(c.x), y = tonumber(c.y), moves = type(c.moves) == "table" and #c.moves or nil }
      end
    end
    return l
  end
  for id, sf in pairs(type(field.seafoam) == "table" and field.seafoam or {}) do
    if type(sf) == "table" then
      sea[tostring(id)] = {
        currents = cells(sf.currents), entryCurrent = type(sf.entryCurrent) == "table" and cells({ sf.entryCurrent })[1] or nil,
        forcedExit = type(sf.forcedExit) == "table" and cells(sf.forcedExit.coords) or nil,
        holes = cells(sf.holes), holeDestination = sym(sf.holeDestination) and sf.holeDestination or nil,
        pluggedBy = type(sf.pluggedByHolesOn) == "table" and sym(sf.pluggedByHolesOn.map) and sf.pluggedByHolesOn.map or nil,
        setsForcedWarp = fieldValue(game, "seafoam", id, "setsForcedWarp") and true or false,
        surfBlocked = cells(fieldValue(game, "seafoam", id, "surfBlocked")),
      }
    end
  end
  out.seafoam = sea
  -- spinners (OC:4966-5021)
  local spin = {}
  for id, list in pairs(type(field.spinners) == "table" and field.spinners or {}) do
    spin[tostring(id)] = cells(list)
  end
  out.spinners = spin
  -- the Safari game (OC:5299-5313; data/scripts/safari.lua's walk-in charges 2 steps)
  out.safari = { stepMaps = list(fieldValue(game, "safari", "stepMaps")),
                 exitWarp = (function()
                   local e = fieldValue(game, "safari", "exitWarp")
                   return type(e) == "table" and { map = sym(e.map) and e.map or nil, x = tonumber(e.x), y = tonumber(e.y) } or nil
                 end)() }
  local rewrites = array()
  local lmr = fieldOf(game, "lastMapRewrites")
  for id in pairs(type(lmr) == "table" and lmr or {}) do rewrites[#rewrites + 1] = tostring(id) end
  table.sort(rewrites)
  out.lastMapRewrites = rewrites
  return out
end

local function scripts(game)
  local ids = {}
  for id, def in pairs(game.data.maps or {}) do
    if type(id) == "string" and type(def) == "table" then ids[#ids + 1] = id end
  end
  table.sort(ids)
  local report = { game = GAME, maps = {} }
  local totals = { maps = #ids, hooks = 0, onStep = 0, onEnter = 0, onBoulderMoved = 0, onInteract = 0,
                   probeCells = 0, holes = 0 }
  for _, id in ipairs(ids) do
    local def = game.data.maps[id]
    local h = hookFlags(hooksOf(id))
    local m = { hooks = h }
    if h then
      totals.hooks = totals.hooks + 1
      for _, k in ipairs({ "onStep", "onEnter", "onBoulderMoved", "onInteract" }) do
        if h[k] then totals[k] = totals[k] + 1 end
      end
      if h.onStep then
        local ts = game.data.tilesets and game.data.tilesets[def.tileset]
        local okN, map = pcall(require("src.world.Map").new, def, ts)
        if okN and type(map) == "table" then
          local okP, probe = pcall(probeOnStep, game, id, map)
          if okP and probe then
            m.probe = probe
            m.probeClasses = probeClasses(probe)
            totals.probeCells = totals.probeCells + (probe.cells or 0)
            totals.holes = totals.holes + #(m.probeClasses.scripted_hole or {})
          else
            m.probeErr = word(probe):sub(1, 60)
          end
        end
      end
    end
    report.maps[id] = m
  end
  local textTotals = {}
  report.files = textScan(game, report, textTotals)
  report.textTotals = textTotals
  report.tables = engineTables(game)
  report.totals = totals
  local nFiles = 0
  for _ in pairs(report.files) do nFiles = nFiles + 1 end
  local path = writeJson("g1_field_scripts.json", report)
  print(("KB_SCRIPTS maps=%d hooks=%d onstep=%d onenter=%d boulder=%d interact=%d files=%d coord=%d warps=%d moves=%d scriptmoves=%d holes=%d textholes=%d takewarps=%d startwarps=%d blocks=%d probecells=%d file=%s")
    :format(totals.maps, totals.hooks, totals.onStep, totals.onEnter, totals.onBoulderMoved, totals.onInteract, nFiles,
      (textTotals.coord or 0) + (textTotals.coord_not or 0), textTotals.warp_row or 0, textTotals.move_player or 0,
      textTotals.scriptMove or 0, totals.holes, textTotals.fallThroughHole or 0, textTotals.takeWarp or 0,
      textTotals.startWarpTo or 0, (textTotals.replace_block or 0) + (textTotals.replaceBlock or 0), totals.probeCells,
      word(path ~= nil)))
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

local function nClasses(st)
  local n = 0
  for _ in pairs(type(st) == "table" and st.counts or {}) do n = n + 1 end
  return n
end

-- ---- the live sweep (FA3-FA4): rows from KB_ROWS, one step each, a stand-in client stepping

local CTL = os.getenv("KB_CTL")
local ROW_BUDGET = tonumber(os.getenv("KB_ROW_FRAMES") or "") or 900 -- frames a row may take before it is halted
local ROW_IDLE = 24                                                  -- the field quiet this long ends a row
local ROW_SETTLE_MIN = 30
local STEP_WAIT_S = 6.0      -- the client's answer wait (ANSWER_S 4 s) and some; by the clock (headless runs fast)
local BOX_FALLBACK = 90      -- frames a box may sit before a warp-in's settle answers it B (the client answers first)
local REPEL_STEPS = 9999     -- save.repelSteps: never worn off during a row (OC:4906-4913)
local GRACE_STEPS = 9999     -- ow.wildEncounterGraceSteps: no wild roll (OC:4791-4806); a new game has no lead,
                             -- so the REPEL filter alone (OC:4934-4940) would let every encounter through

local function ctlValue()
  if not CTL then return nil end
  local f = io.open(CTL, "rb")
  if not f then return nil end
  local v = f:read("*a")
  f:close()
  return tonumber(v)
end

-- the field quiet: fieldReady and none of the in-flight states OverworldState:update holds input for (OC:1678-1684)
local function quiet(game)
  if not fieldReady(game) then return false end
  local ow = overworld(game)
  local p = ow.player
  if (ow.hopLand or 0) > 0 or ow.engaging or ow.teleportOut or ow.flyAnim or ow.flyArrive or ow.spinArrive
      or ow.holeFall or ow.holeArrive or ow.cutAnim or ow.dustAnim then
    return false
  end
  return not (p.spinning or p.ledgeHop or (tonumber(p.hopFrames) or 0) > 0)
end

-- a box on top for BOX_FALLBACK frames is answered B (the client's auto_ui answers first; a battle is left to it)
local function answerLate(game, st)
  local top = topState(game)
  local ow = overworld(game)
  if top == nil or top == ow or (type(top) == "table" and top.isBattleState) then
    st.boxFrames = 0
    return
  end
  st.boxFrames = (st.boxFrames or 0) + 1
  if st.boxFrames >= BOX_FALLBACK then
    st.boxFrames = 0
    st.answered = (st.answered or 0) + 1
    pcall(game.input.sourcePress, game.input, "b", "kb-g1field")
    pcall(game.input.sourceRelease, game.input, "b", "kb-g1field")
  end
end

-- every map trainer of the row's map beaten (kb_field.lua's beatTrainers): nobody sights the hidden player
local function beatTrainers(game, mapId)
  local def = game.data.maps[mapId]
  local save = game.save
  save.defeatedTrainers = save.defeatedTrainers or {}
  local n = 0
  for _, o in ipairs(type(def) == "table" and type(def.objects) == "table" and def.objects or {}) do
    if type(o) == "table" and o.trainerClass then
      save.defeatedTrainers[mapId .. "_obj_" .. tostring(o.index)] = true
      local okH, header = pcall(game.data.trainerHeader, game.data, def.label, o.index)
      if okH and type(header) == "table" and header.event then save.flags[header.event] = true end
      n = n + 1
    end
  end
  return n
end

-- the current map's live blocks (a copy) and what changed between two copies, as story.blocks would carry it
local function liveBlocks(ow)
  local live = ow and ow.map and type(ow.map.def) == "table" and ow.map.def.blocks or {}
  local out = {}
  for i = 1, #live do out[i] = live[i] end
  return out
end

local function blocksChanged(ow, before, after)
  local w = tonumber(ow.map.def.width) or 1
  local out = array()
  for i = 1, math.max(#before, #after) do
    if before[i] ~= after[i] and after[i] ~= nil then
      out[#out + 1] = { bx = (i - 1) % w, by = floor((i - 1) / w), block = after[i], was = before[i] }
    end
  end
  return out
end

-- the story state a row may change (flags, object toggles, the forced bike): a signature to compare
local function storySig(game)
  local save = game.save
  local parts = {}
  for k, v in pairs(type(save.flags) == "table" and save.flags or {}) do
    if v then parts[#parts + 1] = "f" .. tostring(k) end
  end
  for m, list in pairs(type(save.objectToggles) == "table" and save.objectToggles or {}) do
    for o, v in pairs(type(list) == "table" and list or {}) do
      parts[#parts + 1] = "t" .. tostring(m) .. "." .. tostring(o) .. "=" .. tostring(v)
    end
  end
  parts[#parts + 1] = "b" .. tostring(save.forcedBike)
  table.sort(parts)
  return table.concat(parts, "|")
end

-- the player's vehicle for the row (the engine's own fields): walk, surf (player.surfing, Player.lua; as the
-- forced-surf tile sets it, OC:5160-5167), bike (save.onBike, as useBicycle sets it, OC:1154-1171)
local function setVehicle(game, state, base)
  local ow = overworld(game)
  local p = ow and ow.player
  if not p then return end
  local surf = state == "surf"
  if (p.surfing == true) ~= surf then
    p.surfing = surf
    if type(ow.syncSurfingPikachu) == "function" then pcall(ow.syncSurfingPikachu, ow) end
  end
  if state == "bike" then
    game.save.onBike = true
  else
    game.save.onBike = base.onBike
  end
end

-- the hidden player on (x, y) of the bound map without a warp: the fields setMap sets for a placement (OC:575-580),
-- the in-flight states a halted row may leave cleared, the warp under the feet re-derived as a completed step does
-- (refreshStandingOnWarp, OC:1781-1789) and the arrival cell cleared (OC:1709-1712)
local function placeAt(game, row)
  local ow = overworld(game)
  local p = ow.player
  p.cellX, p.cellY = row.x, row.y
  p.px, p.py = row.x * 16, row.y * 16
  p.facing = row.face or row.dir or p.facing or "down"
  p.moving = false
  p.targetX, p.targetY = nil, nil
  p.progress = 0
  p.turnTimer = 0
  p.bumpFrames = nil
  if p.spinning then p.spinning = false end
  if p.ledgeHop then p.ledgeHop = nil end
  if ow.spinnerSliding then ow.spinnerSliding, ow.spinnerLeft, ow.spinnerFirstTile = nil, nil, nil end
  if ow.forcedWarp then ow.forcedWarp = false end
  ow.boulderTried = nil
  ow.warpEntryCell = nil
  if type(ow.refreshStandingOnWarp) == "function" then pcall(ow.refreshStandingOnWarp, ow) end
  if ow.camera and type(ow.camera.follow) == "function" then pcall(ow.camera.follow, ow.camera, p.px, p.py) end
end

-- REPEL and the encounter grace, every row (setMap and a battle reset the grace, OC:374, :5339)
local function noEncounters(game)
  game.save.repelSteps = REPEL_STEPS
  local ow = overworld(game)
  if ow then ow.wildEncounterGraceSteps = GRACE_STEPS end
end

local function rowsList()
  local ok, list = pcall(dofile, os.getenv("KB_ROWS") or "")
  if not (ok and type(list) == "table") then fail("no_rows") end
  return list
end

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local sv, world, base, frames, idle, arrived
  local sw, row, rec
  local st = {}
  local function finishMap(rec)
    local cur = sv.cur
    sv.par.maps[cur.id] = rec
    print(("KB_SURVEY_MAP n=%d map=%s cells=%d warped=%s halted=%s classes=%d")
      :format(sv.i, word(cur.id), cur.st and (cur.st.w or 0) * (cur.st.h or 0) or 0, tostring(rec.warped == true),
        tostring(rec.halted == true), nClasses(cur.st)))
    phase = "map_next"
  end
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    if phase == "ready" then
      if status and status.ready and game.data and game.data.maps and not overworld(game) then
        if type(status.configure) == "function" then status.configure({ port = PORT }) end
        if DATASET_MEMO > 0 then
          print(("KB_DATASET_MEMO seconds=%d ok=%s"):format(DATASET_MEMO, tostring(memoDatasetChecks(DATASET_MEMO))))
        end
        local n = capture(game)
        print(("KB_BOOTED scene=%s pristine=%d"):format(word(SCENE), n))
        game:startNewGame({ intro = false })
        phase = "field"
      end
    elseif phase == "field" then
      if fieldReady(game) then
        local ow = overworld(game)
        print(("KB_FIELD map=%s"):format(word(ow.map.id)))
        base = snapshot(game)
        if SCENE == "scripts" then
          local ok, err = pcall(scripts, game)
          restore(game, base)
          if not ok then fail("scripts " .. word(err):sub(1, 80)) end
          phase = "done"
        elseif SCENE == "survey" then
          sv = { ids = surveyList(game), i = 0, inv = { game = GAME, maps = {} }, par = { game = GAME, maps = {} },
                 totals = { maps = 0, surveyed = 0, cells = 0, failed = 0 }, kbCells = loadKbCells() }
          sv.inv.kbCells = sv.kbCells ~= nil
          world = require("src.world.WorldAPI").new(game, "kb-g1field-driver")
          phase = "map_next"
        elseif SCENE == "sweep" then
          sw = { rows = rowsList(), i = 0, results = array(), dirty = true, base = base, st = {},
                 grace = ow.wildEncounterGraceSteps }
          world = require("src.world.WorldAPI").new(game, "kb-g1field-driver")
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
      local ow = overworld(game)
      if not row then
        halt(game)
        setVehicle(game, "walk", base)
        restore(game, base)
        if ow then ow.wildEncounterGraceSteps = sw.grace or 0 end
        local path = writeJson(os.getenv("KB_SWEEP_FILE") or "g1_field_sweep.json", { game = GAME, rows = sw.results })
        print(("KB_SWEEP_DONE rows=%d file=%s"):format(#sw.results, word(path ~= nil)))
        phase = "done"
      else
        rec = { n = row.n, map = row.map, cls = row.cls, x = row.x, y = row.y, dir = row.dir, state = row.state }
        halt(game)
        setVehicle(game, "walk", base)
        if ow and sw.strength then ow.strengthActive = false end
        sw.strength = nil
        if row.trigger == "warp" then
          -- an entry script's row: the warp itself is the trigger, made after READY
          rec.start = {}
          rec.readyAt = love.timer.getTime()
          print(("KB_ROW_READY n=%d dir=none push=0"):format(row.n))
          frames = 0
          phase = "row_wait_step"
        elseif sw.dirty or row.fresh or not ow or ow.map.id ~= row.map then
          -- the new game's story state and every map's boot blocks, the map's trainers beaten, then a warp in
          restore(game, base)
          beatTrainers(game, row.map)
          arrived = false
          local okW, res, err = pcall(world.warpTo, world, row.map, row.x, row.y, row.face or row.dir or "down",
            { onDone = function() arrived = true end })
          if not okW or not res then
            rec.err = "warp " .. word(okW and err or res):sub(1, 50)
            phase = "row_report"
          else
            rec.warped = true
            frames, sw.st = 0, {}
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
      answerLate(game, sw.st)
      if frames == SETTLE_FRAMES and not rec.entryHalted then rec.entryHalted = halt(game) end
      if (arrived and frames >= ROW_SETTLE_MIN and quiet(game)) or frames >= SETTLE_FRAMES + 120 then
        local ow = overworld(game)
        if not ow or ow.map.id ~= row.map or not quiet(game) then
          rec.err = (ow and ow.map.id ~= row.map) and "left_on_entry" or "not_settled"
          phase = "row_report"
        else
          placeAt(game, row)
          frames = 0
          phase = "row_place"
        end
      end
    elseif phase == "row_place" then
      frames = frames + 1
      local ow = overworld(game)
      if frames == 1 and ow then
        if not row.late then setVehicle(game, row.state, base) end
        if row.bicycle then
          sw.bag = { had = true, v = game.save.inventory.BICYCLE }
          game.save.inventory.BICYCLE = 1
        end
        noEncounters(game)
        if row.strength then
          -- BIT_STRENGTH_ACTIVE as the party menu's STRENGTH sets it (OC:1193-1198); cleared on every map load (:486)
          ow.strengthActive = true
          sw.strength = true
        end
      end
      if frames >= 8 and quiet(game) then
        local p = ow.player
        local Collision = require("src.world.Collision")
        if p.cellX ~= row.x or p.cellY ~= row.y then
          rec.err = "not_placed"
          phase = "row_report"
        else
          local okO, busy = pcall(Collision.occupied, ow.entities or {}, p.cellX, p.cellY, p)
          if okO and busy then
            rec.err = "occupied"
            phase = "row_report"
          else
            local d = row.dir and DELTA[row.dir]
            if d then
              local okT, tbusy = pcall(Collision.occupied, ow.entities or {}, p.cellX + d[1], p.cellY + d[2], p)
              rec.targetBusy = (okT and tbusy and true) or nil
            end
            rec.start = { x = p.cellX, y = p.cellY, facing = p.facing, surfing = p.surfing == true,
                          biking = game.save.onBike == true }
            rec.before = liveBlocks(ow)
            rec.story = storySig(game)
            rec.todSteps = tonumber(ow.todSteps)
            rec.readyAt = love.timer.getTime()
            -- dirs: several steps in one row (field_common sends them one after another, the first answered with n);
            -- hold: a button the client holds over the row's steps (move.hold)
            local extra = ""
            if row.dirs then extra = extra .. " dirs=" .. word((row.dirs:gsub(",", "+"))) end
            if row.hold then extra = extra .. " hold=" .. word(row.hold) end
            print(("KB_ROW_READY n=%d dir=%s push=%s%s"):format(row.n, word(row.dir or "none"), row.push and "1" or "0",
              extra))
            frames = 0
            phase = "row_wait_step"
          end
        end
      elseif frames >= 200 then
        halt(game)
        rec.err = "not_quiet"
        phase = "row_report"
      end
    elseif phase == "row_wait_step" then
      frames = frames + 1
      if ctlValue() == row.n or love.timer.getTime() - (rec.readyAt or 0) > STEP_WAIT_S then
        rec.stepWait = frames
        rec.ctl = ctlValue() == row.n
        frames = 0
        rec.idle = 0
        if row.late then setVehicle(game, row.state, base) end
        if row.trigger == "warp" then
          restore(game, base)
          beatTrainers(game, row.map)
          local x, y = row.x, row.y
          if not x or x < 0 then x, y = arrivalCell(game, row.map) end
          local okW, res, err = pcall(world.warpTo, world, row.map, x, y, row.face or "down")
          if not okW or not res then rec.err = "warp " .. word(okW and err or res):sub(1, 50) end
          rec.driven = true
        end
        phase = rec.err and "row_report" or "row_run"
      end
    elseif phase == "row_run" then
      frames = frames + 1
      local ow = overworld(game)
      if quiet(game) then rec.idle = rec.idle + 1 else rec.idle = 0 end
      local now = ow and ow.map.id or nil
      if now ~= rec.lastMap then
        rec.maps = rec.maps or array()
        rec.maps[#rec.maps + 1] = now
        rec.lastMap = now
      end
      if row.roll and frames == row.roll then
        -- the Cycling Road's roll has been seen: off the bike, so the roll ends after the step under way
        game.save.onBike = base.onBike
        rec.rollStopped = true
      end
      if (frames >= 30 and rec.idle >= (row.idle or ROW_IDLE)) or frames >= (row.wait or ROW_BUDGET) then
        if frames >= (row.wait or ROW_BUDGET) then rec.halted = halt(game) or true end
        ow = overworld(game)
        local p = ow and ow.player or {}
        rec.frames = frames
        rec["end"] = { map = ow and ow.map.id or nil, x = p.cellX, y = p.cellY, facing = p.facing,
                       surfing = p.surfing == true, biking = game.save.onBike == true }
        if ow and ow.map.id == row.map and rec.before then
          rec.changed = blocksChanged(ow, rec.before, liveBlocks(ow))
        else
          rec.changed = array()
        end
        rec.storyChanged = rec.story ~= nil and storySig(game) ~= rec.story or nil
        rec.steps = (ow and rec.todSteps and tonumber(ow.todSteps)) and (tonumber(ow.todSteps) - rec.todSteps) or nil
        rec.before, rec.story, rec.lastMap, rec.todSteps = nil, nil, nil, nil
        phase = "row_report"
      end
    elseif phase == "row_report" then
      local e = rec["end"] or {}
      if row.bicycle and sw.bag then
        game.save.inventory.BICYCLE = sw.bag.v
        sw.bag = nil
      end
      sw.dirty = rec.err ~= nil or (e.map ~= nil and e.map ~= row.map) or (rec.changed ~= nil and #rec.changed > 0)
        or rec.halted == true or rec.storyChanged == true or row.strength == true or row.trigger == "warp"
      print(("KB_ROW_DONE n=%d map=%s cls=%s end=%s moved=%s changed=%d frames=%s halted=%s err=%s")
        :format(row.n, word(row.map), word(row.cls), word(e.map or "none"),
          word(e.x ~= nil and (e.x ~= row.x or e.y ~= row.y or e.map ~= row.map)),
          rec.changed and #rec.changed or 0, word(rec.frames or 0), word(rec.halted or false), word(rec.err or "none")))
      rec.readyAt = nil
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
        restore(game, base)
        local p1 = writeJson("g1_field_inventory.json", sv.inv)
        local p2 = writeJson("g1_field_parity.json", sv.par)
        print(("KB_SURVEY maps=%d surveyed=%d cells=%d failed=%d file=%s"):format(sv.totals.maps, sv.totals.surveyed,
          sv.totals.cells, sv.totals.failed, word(p1 ~= nil and p2 ~= nil)))
        phase = "done"
      else
        local def = game.data.maps[id]
        sv.totals.maps = sv.totals.maps + 1
        -- the new game's story state and every map's boot blocks before the static read and the visit
        restore(game, base)
        local ok, stv, why = pcall(staticMap, game, id, def, sv.kbCells)
        sv.cur = { id = id, def = def, st = ok and stv or nil }
        if not ok or not stv then
          sv.inv.maps[id] = { err = word(ok and why or stv):sub(1, 60) }
          sv.totals.failed = sv.totals.failed + 1
          finishMap({ warped = false, err = "static" })
        else
          sv.inv.maps[id] = stv
          if skipped(id) then
            stv.skipped = true
            finishMap({ warped = false, skipped = true })
          else
            local x, y = arrivalCell(game, id)
            arrived = false
            local okW, res, err = pcall(world.warpTo, world, id, x, y, "down", { onDone = function() arrived = true end })
            if not okW or not res then
              stv.warpErr = word(okW and err or res):sub(1, 60)
              sv.totals.failed = sv.totals.failed + 1
              finishMap({ warped = false, err = "warp" })
            else
              frames, idle, st = 0, 0, {}
              sv.cur.halted = false
              phase = "map_arrive"
            end
          end
        end
      end
    elseif phase == "map_arrive" then
      frames = frames + 1
      answer(game, st)
      local ow = overworld(game)
      if arrived and ow and ow.map.id == sv.cur.id and not ow.transitioning then
        frames, idle = 0, 0
        phase = "map_settle"
      elseif frames >= SETTLE_FRAMES then
        sv.cur.halted = halt(game)
        local now = overworld(game)
        sv.totals.failed = sv.totals.failed + 1
        finishMap({ warped = false, left = true, at = now and now.map.id or nil, halted = sv.cur.halted,
                    err = "no_arrival" })
      end
    elseif phase == "map_settle" then
      frames = frames + 1
      answer(game, st)
      idle = fieldReady(game) and idle + 1 or 0
      if frames == SETTLE_FRAMES then sv.cur.halted = halt(game) or sv.cur.halted end
      if idle >= IDLE_FRAMES or frames >= SETTLE_FRAMES + 60 then
        local ow = overworld(game)
        local rec = { at = ow and ow.map.id or nil, halted = sv.cur.halted or nil, answered = st.answered,
                      battles = st.battles, frames = frames }
        if not ow or ow.map.id ~= sv.cur.id then
          -- arrived, then sent on by the map (an entry script, a warp underfoot): no verdicts
          rec.left = true
          rec.warped = false
        else
          rec.warped = true
          local v, err = liveVerdicts(game, ow)
          if v then
            for k, val in pairs(v) do rec[k] = val end
            local okB, blocks = pcall(blockDiffs, ow)
            rec.blocks = okB and blocks or array()
            sv.totals.surveyed = sv.totals.surveyed + 1
            sv.totals.cells = sv.totals.cells + v.w * v.h
          else
            rec.err = err
            sv.totals.failed = sv.totals.failed + 1
          end
        end
        finishMap(rec)
      end
    elseif phase == "done" then
      print("KB_QUIT code=0")
      love.event.quit(0)
      while true do coroutine.yield() end
    end
    if love.timer.getTime() - started > SECONDS_LIMIT + EXTRA_SECONDS then
      fail("timeout phase=" .. phase)
    end
    game = coroutine.yield() or game
  end
end
