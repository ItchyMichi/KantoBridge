-- Kanto Bridge test driver: Crystal's story events and the world changes
-- they make, read from the real engine (the story check, docs/specs/
-- STORY_EVENTS_PLAN.md §5). Driven by tools/e2e/g2_story_check.py. Every
-- scene first registers a fresh empty save slot labelled KB_G2STORY_TEST and
-- makes it active (the engine's slot API, src/core/SaveData.lua), uses driver
-- mode's own start (Gen 2 boots straight into the field on a new game,
-- src/core/Game2.lua:1263-1274) and deletes the test slots again at the end.
-- KB_SCENE picks the part:
--
--   scan     a static walk over every map's scripts (data.gen2Scripts: rows
--            the importer decoded, keyed bank:address, src/import/
--            RomExtractorGen2.lua:3850-4122): from each map's callbacks (by
--            type), scene scripts (by scene id), coord events, bg events,
--            objects and trainers, every reachable row, following iftrue,
--            iffalse, ifequal, ifnotequal, ifgreater and ifless (a taken
--            branch is a tail call: the fall-through carries the negated
--            condition, src/script/gen2/Vm.lua:365-405), scall, farscall and
--            callstd (they return), sjump, farsjump, jumpstd and stopandsjump
--            (they don't), sdefer and writecmdqueue's stone-table scripts,
--            with the conditions taken on the way: what the script result
--            register holds (checkevent, checkflag, checkscene,
--            checkmapscene, checktime, readvar, readmem, setval; any other
--            writer by name). Recorded per map: every command that can change
--            the world (changeblock, changemapblocks, appear, disappear,
--            moveobject, variablesprite, setevent/clearevent,
--            setflag/clearflag, setscene/setmapscene, warps, specials by
--            name, writecmdqueue), the map's group and number, its palette,
--            its objects' flags and hours. Written to
--            KB_REPORT_DIR/g2_story_scan.json (ROM-derived: it stays in
--            local/); stdout gets counts only.
--
--   sweep    the live check: the cases in KB_CASES (a Lua file the test
--            writes from the scan: per case a map and the event flags, engine
--            flags, map scenes, script memory and clock that make its
--            callbacks and scene script change the world; KB_MAPS, a
--            comma-separated list, keeps only those maps). Once a client
--            watches (the bridge diffs only then), for each case: the World's
--            story state back to the new game's (event flags, engine flags,
--            badges, map scenes, variable sprites, the script memory), the
--            case's state set and the clock (hour 12 and Monday unless the
--            case says), then a warp into the map (World:warpToMapId: the
--            load runs NEWMAP, TILES and OBJECTS at once, World.lua:10134-
--            10366; onto its first warp's cell, else its middle; a case on
--            the map the player stands on hops to another map first). Text,
--            yes/no, movements and battles hold no script during the sweep
--            (the VM's hooks: no text box, every yes/no answered no,
--            movements skipped, battles won, Vm.lua:2729-2767), so a scene
--            script runs to its end (a script that raises an error ends
--            there and is counted for the case). Recorded right after the load and again
--            once the field has stood idle for KB_IDLE_FRAMES (up to
--            KB_SETTLE_FRAMES; a script still running then is halted): every
--            live block that differs from the map data (the World's own
--            record of this visit's edits, World.blockEdits), the objects
--            shown and hidden (the live masks, else flag and hour), the live
--            people, the palette and Flash, the scene, the map the player is
--            on. Written to KB_REPORT_DIR/g2_story_sweep.json.
--
--   objects  the same loop over the maps whose objects have event flags
--            (KB_SKIP: comma-separated map id prefixes left out): per map
--            every such flag set, then every one clear; recorded as above.
--            Written to KB_REPORT_DIR/g2_story_objects.json.
--
-- Markers: KB_BOOTED scene= slot= home= precleaned=, KB_FIELD map=,
-- KB_SCAN maps= roots= rows= world= changeblock= mapblocks= truncated= file=,
-- KB_SWEEP_READY cases=, KB_CASE n= map= ok= blocks= shown= hidden= people=
-- current= halted= errors= err=, KB_SWEEP_DONE cases= ok= file=, KB_CLEANED deleted=
-- active=, KB_QUIT sessions= code=; KB_FAIL <reason> on a timeout. Slot
-- numbers, map symbols, flag numbers and counts only.
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- with the Game (refs/gen1recomp/main.lua:755-759, :1131-1150).

io.stdout:setvbuf("no")

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27226
local SCENE = os.getenv("KB_SCENE") or "scan"
local REPORT_DIR = os.getenv("KB_REPORT_DIR")
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or 900
local DATASET_MEMO = tonumber(os.getenv("KB_DATASET_MEMO") or "") or 0
local SETTLE_FRAMES = tonumber(os.getenv("KB_SETTLE_FRAMES") or "") or 600 -- then a script still running is halted
local IDLE_FRAMES = tonumber(os.getenv("KB_IDLE_FRAMES") or "") or 15
local LABEL = "KB_G2STORY_TEST"
local HOP, HOP_ALT = "NEW_BARK_TOWN", "ROUTE_29"
local DEFAULT_HOUR, DEFAULT_DAY = 12, 1
-- the walk's bounds: how deep calls and branches nest, how many rows one root may visit and under how many
-- different conditions one root may walk the same script (scripts that branch on many results multiply paths)
local MAX_DEPTH, ROW_BUDGET, KEY_PATHS = 48, 40000, 48

local SaveData = require("src.core.SaveData")
local GameVersion = require("src.core.GameVersion")
local okOp, Opcodes = pcall(require, "src.script.gen2.Opcodes")

local floor = math.floor
local function word(v) return (tostring(v):gsub("[^%w_.:+%-]", "?")) end
local function num(id) return tonumber(tostring(id):match("^slot(%d+)$")) or 0 end
local function version() return GameVersion.get() end
local function active() return SaveData.activeSlot(version()) end

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

-- The field is up and settled (kb_crystal.lua's test, and no people rebuild pending): the play phase with a map and a
-- player, nothing on the stack, the World's own START gate open (World:acceptsMenuInput: no script, no map setup, no
-- text box, no field move), no step, turn or setup.
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

-- ---- the static scan

local function arg(c, i)
  local a = type(c.args) == "table" and c.args or nil
  return a and tonumber(a[i]) or nil
end
local function word16(c, i) return (arg(c, i) or 0) + (arg(c, i + 1) or 0) * 256 end

-- the special a row calls, by name (constants.specialOrder, Vm.lua:2370-2383)
local specialOrder = {}
local function specialName(id)
  id = tonumber(id)
  return id and specialOrder[id + 1] or ("special_" .. tostring(id))
end

-- Commands that change the world, and what each one records.
local WORLD = {
  changeblock = function(c) return { x = arg(c, 1), y = arg(c, 2), block = arg(c, 3) } end,
  changemapblocks = function(c) return { bank = arg(c, 1), address = word16(c, 2) } end,
  appear = function(c) return { object = tonumber(c.object) or arg(c, 1) } end,
  disappear = function(c) return { object = tonumber(c.object) or arg(c, 1) } end,
  moveobject = function(c) return { object = tonumber(c.object) or arg(c, 1), x = arg(c, 2), y = arg(c, 3) } end,
  variablesprite = function(c) return { slot = arg(c, 1), sprite = arg(c, 2) } end,
  setevent = function(c) return { event = tonumber(c.event) or arg(c, 1) } end,
  clearevent = function(c) return { event = tonumber(c.event) or arg(c, 1) } end,
  setflag = function(c) return { flag = tonumber(c.flag) or arg(c, 1) } end,
  clearflag = function(c) return { flag = tonumber(c.flag) or arg(c, 1) } end,
  setscene = function(c) return { scene = tonumber(c.scene) or arg(c, 1) } end,
  setmapscene = function(c) return { group = tonumber(c.group), map = tonumber(c.map), scene = tonumber(c.scene) } end,
  warp = function(c) return { group = arg(c, 1), map = arg(c, 2), x = arg(c, 3), y = arg(c, 4) } end,
  warpfacing = function(c) return { facing = arg(c, 1), group = arg(c, 2), map = arg(c, 3), x = arg(c, 4), y = arg(c, 5) } end,
  warpmod = function(c) return { warp = arg(c, 1), group = arg(c, 2), map = arg(c, 3) } end,
  warpcheck = function() return {} end,
  newloadmap = function(c) return { setup = arg(c, 1) } end,
  special = function(c) return { name = specialName(c.id or arg(c, 1)) } end,
  writecmdqueue = function(c)
    local q = type(c.queue) == "table" and c.queue or {}
    return { rows = #(type(q.rows) == "table" and q.rows or q) }
  end,
}

-- What a row leaves in the script result register (wScriptVar), or nil when it leaves it alone.
local OTHER_WRITERS = {
  checkitem = true, checkpoke = true, checkcellnum = true, addval = true, random = true, checkver = true,
  checksave = true, checkjustbattled = true, checkphonecall = true, yesorno = true, startbattle = true,
  checkmoney = true, checkcoins = true, giveitem = true, verbosegiveitem = true, takeitem = true, giveegg = true,
  askforphonenumber = true, elevator = true, verticalmenu = true, _2dmenu = true, checkpokemail = true,
  trainerflagaction = true, delcmdqueue = true, fruittree = true, givepoke = true, checkcode = true,
}
local function writer(c)
  local op = c.op
  if op == "checkevent" then return { k = "event", id = tonumber(c.event) or arg(c, 1) } end
  if op == "checkflag" then return { k = "flag", id = tonumber(c.flag) or arg(c, 1) } end
  if op == "checkscene" then return { k = "scene" } end
  if op == "checkmapscene" then return { k = "mapscene", group = arg(c, 1), map = arg(c, 2) } end
  if op == "checktime" then return { k = "time", mask = arg(c, 1) } end
  if op == "readvar" then return { k = "var", id = tonumber(c.var) or arg(c, 1) } end
  if op == "readmem" then return { k = "mem", addr = word16(c, 1) } end
  if op == "setval" then return { k = "const", value = arg(c, 1) } end
  if op == "special" then return { k = "special", name = specialName(c.id or arg(c, 1)) } end
  if OTHER_WRITERS[op] then return { k = "op", op = op } end
  return nil
end

local BRANCH = { iftrue = "~=0", iffalse = "==0", ifequal = "==", ifnotequal = "~=", ifgreater = ">", ifless = "<" }
local NEGATE = { ["~=0"] = "==0", ["==0"] = "~=0", ["=="] = "~=", ["~="] = "==", [">"] = "<=", ["<"] = ">=" }
local RETURNS = { scall = true, farscall = true, callstd = true }
local JUMPS = { sjump = true, farsjump = true, jumpstd = true, stopandsjump = true }
local TERMINATORS = okOp and type(Opcodes) == "table" and type(Opcodes.TERMINATORS) == "table" and Opcodes.TERMINATORS
  or { sjump = true, farsjump = true, memjump = true, jumpstd = true, jumptext = true, farjumptext = true,
       jumptextfaceplayer = true, stopandsjump = true, ["end"] = true, endall = true, endcallback = true,
       reloadend = true, fruittree = true, describedecoration = true, halloffame = true, credits = true }

local function condKey(c)
  local w = c.w
  return table.concat({ w.k, tostring(w.id or w.mask or w.addr or w.name or w.op or ""), tostring(w.group or ""),
    tostring(w.map or ""), w.k == "const" and tostring(w.value) or "", c.op, tostring(c.value or "") }, ":")
end
local function condsKey(conds)
  local parts = {}
  for i, c in ipairs(conds) do parts[i] = condKey(c) end
  return table.concat(parts, ";")
end
local function plus(conds, c)
  local out = array()
  for i, v in ipairs(conds) do out[i] = v end
  out[#out + 1] = c
  return out
end
local function argsKey(t)
  local keys = {}
  for k in pairs(t) do keys[#keys + 1] = k end
  table.sort(keys)
  local parts = {}
  for i, k in ipairs(keys) do parts[i] = k .. "=" .. tostring(t[k]) end
  return table.concat(parts, ",")
end

local function queueRows(c)
  local q = type(c.queue) == "table" and c.queue or {}
  local rows = type(q.rows) == "table" and q.rows or q
  return type(rows) == "table" and rows or {}
end

-- A map's entry points: its callbacks (by type), scene scripts (by scene id), coord events (by scene id), bg events
-- (by kind; 5 and 6 only while their event is set or clear), objects and trainers.
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
  for _, e in ipairs(type(def.coordEvents) == "table" and def.coordEvents or {}) do
    if type(e) == "table" then add("coord", e.scriptKey, { scene = tonumber(e.sceneId), x = e.x, y = e.y }) end
  end
  for _, e in ipairs(type(def.bgEvents) == "table" and def.bgEvents or {}) do
    if type(e) == "table" then add("bg", e.scriptKey, { kind = tonumber(e.kind), event = tonumber(e.event) }) end
  end
  for i, o in ipairs(type(def.objects) == "table" and def.objects or {}) do
    if type(o) == "table" then
      local info = { object = tonumber(o.index) or i, flag = tonumber(o.eventFlag) }
      add("object", o.scriptKey, info)
      if type(o.trainer) == "table" then add("trainer", o.trainer.scriptKey, info) end
    end
  end
  return out
end

local function objectsOf(def)
  local out = array()
  for i, o in ipairs(type(def.objects) == "table" and def.objects or {}) do
    if type(o) == "table" then
      local hours = type(o.hours) == "table" and array({ tonumber(o.hours[1]) or -1, tonumber(o.hours[2]) or -1 }) or nil
      out[#out + 1] = { name = ("O%d"):format(tonumber(o.index) or i), index = tonumber(o.index) or i,
                        flag = tonumber(o.eventFlag), hours = hours, type = tonumber(o.type),
                        trainer = type(o.trainer) == "table" and tonumber(o.trainer.event) or nil,
                        x = tonumber(o.x), y = tonumber(o.y) }
    end
  end
  return out
end

local function scan(game)
  local w = game.world
  local data = game.data or {}
  local maps = data.gen2Maps or w.maps or {}
  local scripts = data.gen2Scripts or w.scripts or {}
  local constants = data.gen2Constants or w.constants or {}
  specialOrder = type(constants.specialOrder) == "table" and constants.specialOrder or {}
  local edits = type(w.blockEdits) == "table" and w.blockEdits[w.map.id] or {}
  local ids = {}
  for id in pairs(maps) do if type(id) == "string" then ids[#ids + 1] = id end end
  table.sort(ids)
  local totals = { maps = 0, roots = 0, rows = 0, world = 0, changeblock = 0, mapblocks = 0, truncated = 0 }
  local out = {}
  for _, id in ipairs(ids) do
    local def = maps[id]
    if type(def) == "table" then
      totals.maps = totals.maps + 1
      local width = tonumber(def.width) or 0
      local blocks = type(def.blocks) == "table" and def.blocks or {}
      local function pristine(bx, by)
        if not (bx and by) or bx < 0 or by < 0 or bx >= width then return nil end
        local i = by * width + bx + 1
        if id == w.map.id and edits and edits[i] ~= nil then return edits[i] end
        return blocks[i]
      end
      local rec = { group = tonumber(def.group), map = tonumber(def.map), width = width, height = tonumber(def.height),
                    palette = def.palette, dark = def.palette == "PALETTE_DARK", tileset = def.tileset,
                    environment = def.environment, objects = objectsOf(def), world = array(), callbacks = array(),
                    scenes = array(), roots = 0, warps = #(type(def.warps) == "table" and def.warps or {}) }
      local seenWorld = {}
      for _, root in ipairs(rootsOf(def)) do
        rec.roots = rec.roots + 1
        totals.roots = totals.roots + 1
        if root.kind:match("^MAPCALLBACK_") then rec.callbacks[#rec.callbacks + 1] = root.kind end
        if root.kind == "scene" then rec.scenes[#rec.scenes + 1] = root.info.scene end
        local budget, seen, paths = 0, {}, {}
        local function visit(key, i, c, conds)
          local f = WORLD[c.op]
          if not f then return end
          local ok, a = pcall(f, c)
          if not ok or type(a) ~= "table" then a = {} end
          if c.op == "changeblock" and a.x and a.y then a.was = pristine(floor(a.x / 2), floor(a.y / 2)) end
          local k = table.concat({ c.op, argsKey(a), root.kind, argsKey(root.info), condsKey(conds) }, "|")
          if seenWorld[k] then return end
          seenWorld[k] = true
          rec.world[#rec.world + 1] = { op = c.op, args = a, root = root.kind, rootInfo = root.info, conds = conds,
                                        key = tostring(key), row = i }
          totals.world = totals.world + 1
          if c.op == "changeblock" then totals.changeblock = totals.changeblock + 1 end
          if c.op == "changemapblocks" then totals.mapblocks = totals.mapblocks + 1 end
        end
        local function cut()
          totals.truncated = totals.truncated + 1
          rec.truncated = (rec.truncated or 0) + 1
          rec.truncatedRoots = rec.truncatedRoots or {}
          rec.truncatedRoots[root.kind] = (rec.truncatedRoots[root.kind] or 0) + 1
        end
        local function walk(key, conds, depth)
          if depth > MAX_DEPTH then cut() return nil end
          local list = scripts[key]
          if type(list) ~= "table" then return nil end
          local s = tostring(key) .. "|" .. condsKey(conds)
          if seen[s] then return nil end
          seen[s] = true
          paths[key] = (paths[key] or 0) + 1
          if paths[key] > KEY_PATHS then cut() return nil end
          local var
          for i, c in ipairs(list) do
            if type(c) == "table" then
              budget = budget + 1
              totals.rows = totals.rows + 1
              if budget > ROW_BUDGET then cut() return var end
              visit(key, i, c, conds)
              local wv = writer(c)
              if wv then var = wv end
              local br = BRANCH[c.op]
              if br then
                local cond = { w = var or { k = "const", value = 0 }, op = br, value = tonumber(c.value) }
                walk(c.script, plus(conds, cond), depth + 1)
                conds = plus(conds, { w = cond.w, op = NEGATE[br], value = cond.value })
              elseif RETURNS[c.op] then
                local back = walk(c.script, conds, depth + 1)
                if back then var = back end
              elseif JUMPS[c.op] or c.op == "sdefer" then
                walk(c.script, conds, depth + 1)
              elseif c.op == "writecmdqueue" then
                for _, r in ipairs(queueRows(c)) do
                  if type(r) == "table" then walk(r.scriptKey, conds, depth + 1) end
                end
              end
              if TERMINATORS[c.op] or c.op == "unknown" or c.op == "truncated" then break end
            end
          end
          return var
        end
        walk(root.key, array(), 0)
      end
      out[id] = rec
    end
  end
  local names = { engineFlags = type(constants.engineFlagOrder) == "table" and constants.engineFlagOrder or nil }
  local report = { totals = totals, maps = out, names = names }
  local path = writeJson("g2_story_scan.json", report)
  print(("KB_SCAN maps=%d roots=%d rows=%d world=%d changeblock=%d mapblocks=%d truncated=%d file=%s"):format(
    totals.maps, totals.roots, totals.rows, totals.world, totals.changeblock, totals.mapblocks, totals.truncated,
    word(path and "written" or "none")))
end

-- ---- the live sweep

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

-- The World's story state as the new game left it, and back.
local function snapshot(game)
  local w, save = game.world, game.save or {}
  local player = save.player or {}
  return { events = w.events:serialize(), scenes = copy(w.mapScenes), engine = copy(save.engineFlags),
           badges = copy(player.badges), kanto = copy(player.kantoBadges), sprites = copy(w.variableSprites),
           mem = w.vm and w.vm.serializeMem and w.vm:serializeMem() or copy(w.vm and w.vm.mem) }
end
local function restore(game, base)
  local w, save = game.world, game.save or {}
  local player = save.player or {}
  w.events:restore(copy(base.events))
  if type(w.mapScenes) == "table" then refill(w.mapScenes, base.scenes) end
  if type(save.engineFlags) == "table" then refill(save.engineFlags, base.engine) end
  if type(player.badges) == "table" then refill(player.badges, base.badges) end
  if type(player.kantoBadges) == "table" then refill(player.kantoBadges, base.kanto) end
  if type(w.variableSprites) == "table" then refill(w.variableSprites, base.sprites) end
  if w.vm and w.vm.restoreMem then w.vm:restoreMem(copy(base.mem)) end
end

-- What holds a script on the field: none of it during the sweep (the VM's own hooks, Vm.lua:2729-2767). A yes/no is
-- answered no on the next frame (answerPending), as a player's answer comes; a script that raises an error ends there
-- and is counted for its case (Vm:resume wrapped on the instance: the VM resumes itself through self:resume) instead
-- of taking the game down.
local yesno, scriptErrors = nil, 0
local function quietHooks(vm)
  local saved = { showTextFn = vm.showTextFn, yesornoFn = vm.yesornoFn, startBattleFn = vm.startBattleFn,
                  applyMovementFn = vm.applyMovementFn, waitButtonFn = vm.waitButtonFn, resume = rawget(vm, "resume") }
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
local function answerPending()
  if yesno then
    local done = yesno
    yesno = nil
    done(false)
  end
end

-- What holds the field now (World:busy's markers, World.lua:1531-1556, and the field's own gates), as a list.
local BUSY = { "mapSetup", "textbox", "moveState", "choicebox", "fishing", "headbutt", "fieldMove", "flyAnim", "skyfall",
               "battleActive", "waitButton", "pendingSceneScript", "pendingWarp", "peopleDirty" }
local function busyFlags(game)
  local w = game.world
  local out = {}
  if w and w.vm and w.vm.busy then out[#out + 1] = "vm" end
  for _, k in ipairs(BUSY) do if w and w[k] ~= nil and w[k] ~= false then out[#out + 1] = k end end
  if topState(game) ~= nil then out[#out + 1] = "stack" end
  return #out > 0 and table.concat(out, "+") or "none"
end

-- Stops a script that still holds the field (test-only): the VM's run state cleared, boxes taken off the stack and
-- every marker World:busy reads cleared, so the player walks again.
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
      if w[k] ~= nil then
        w[k] = nil
        halted = true
      end
    end
  end
  return halted
end

-- Every live block of the current map that differs from the map data, from the World's own record of this visit's
-- edits (index -> the original, World:replaceBlock).
local function blockDiffs(w)
  local m = w.map
  local live = type(m.def) == "table" and m.def.blocks or {}
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

-- The current map's objects: shown and hidden (the live mask, else flag and hour, as World:loadObjectMasks derives),
-- and the people standing (the live NPCs' objects), by the bridge's names O<index>.
local function objectsState(w)
  local m = w.map
  local objs = type(m.def) == "table" and type(m.def.objects) == "table" and m.def.objects or {}
  local shown, hidden, people = array(), array(), array()
  local nameOf = {}
  for i, o in ipairs(objs) do
    if type(o) == "table" then
      local name = ("O%d"):format(tonumber(o.index) or i)
      nameOf[o] = name
      local ok, key = pcall(w.objectMaskKey, w, o, i)
      local masked = ok and type(w.objectMasks) == "table" and w.objectMasks[key] or nil
      if masked == nil then
        local okF, byFlag = pcall(w.events.objectVisible, w.events, o.eventFlag)
        local okT, byTime = pcall(w.objectTimeVisible, w, o)
        masked = (okF and byFlag == false) or (okT and byTime == false)
      end
      if masked then hidden[#hidden + 1] = name else shown[#shown + 1] = name end
    end
  end
  for _, n in ipairs(type(w.npcs) == "table" and w.npcs or {}) do
    if type(n) == "table" and n.def and nameOf[n.def] then people[#people + 1] = nameOf[n.def] end
  end
  table.sort(people)
  return shown, hidden, people
end

local function record(w)
  local okS, scene = pcall(w.scene, w)
  local shown, hidden, people = objectsState(w)
  return { map = w.map.id, x = w.player.cellX, y = w.player.cellY, blocks = blockDiffs(w), shown = shown,
           hidden = hidden, people = people, daytime = w.daytime, flash = w.flashUsed == true,
           dark = type(w.map.def) == "table" and w.map.def.palette == "PALETTE_DARK" or false,
           scene = okS and scene or nil, hour = (pcall(w.hour, w)) and w:hour() or nil }
end

-- Where the warp lands: the map's first warp's cell, else its middle.
local function arrivalCell(def)
  local warps = type(def.warps) == "table" and def.warps or {}
  local first = warps[1]
  if type(first) == "table" and tonumber(first.x) and tonumber(first.y) then return tonumber(first.x), tonumber(first.y) end
  return floor((tonumber(def.width) or 2) * 2 / 2), floor((tonumber(def.height) or 2) * 2 / 2)
end

local function applyCase(game, case)
  local w = game.world
  for id, on in pairs(case.events or {}) do w.events:set(id, on) end
  for id, on in pairs(case.flags or {}) do pcall(w.setEngineFlag, w, id, on) end
  for map, v in pairs(case.scenes or {}) do w.mapScenes[map] = v end
  for _, ms in ipairs(case.mapscenes or {}) do
    local ok, id = pcall(w.mapIdByGroupMap, w, ms.group, ms.map)
    if ok and id then w.mapScenes[id] = ms.value end
  end
  for addr, v in pairs(case.mem or {}) do if w.vm then w.vm.mem[addr] = v end end
  w.clockHour = case.hour or DEFAULT_HOUR
  w.clockDay = case.day or DEFAULT_DAY
end

local function loadCases()
  local cases = {}
  if SCENE == "sweep" then
    local ok, list = pcall(dofile, os.getenv("KB_CASES") or "")
    if not ok or type(list) ~= "table" then fail("no_cases") end
    cases = list
  end
  local only = {}
  for m in (os.getenv("KB_MAPS") or ""):gmatch("[^,]+") do only[m] = true end
  if next(only) then
    local kept = {}
    for _, c in ipairs(cases) do if only[c.map] then kept[#kept + 1] = c end end
    cases = kept
  end
  return cases, only
end

-- The objects run's cases: per map whose objects have event flags, all set, then all clear.
local function objectCases(game, only)
  local maps = (game.data or {}).gen2Maps or game.world.maps or {}
  local skip = {}
  for p in (os.getenv("KB_SKIP") or ""):gmatch("[^,]+") do skip[#skip + 1] = p end
  local ids = {}
  for id, def in pairs(maps) do
    if type(id) == "string" and type(def) == "table" and (not next(only) or only[id]) then
      local skipped = false
      for _, p in ipairs(skip) do if id:sub(1, #p) == p then skipped = true end end
      local flags = {}
      for _, o in ipairs(type(def.objects) == "table" and def.objects or {}) do
        local f = type(o) == "table" and tonumber(o.eventFlag) or nil
        if f and f ~= 0xFFFF and f >= 8 then flags[f] = true end
      end
      if not skipped and next(flags) then ids[#ids + 1] = { id = id, flags = flags } end
    end
  end
  table.sort(ids, function(a, b) return a.id < b.id end)
  local cases = {}
  for _, e in ipairs(ids) do
    for _, mode in ipairs({ "set", "clear" }) do
      local events = {}
      for f in pairs(e.flags) do events[f] = mode == "set" end
      cases[#cases + 1] = { n = #cases + 1, map = e.id, label = "hide flags " .. mode, events = events, mode = mode }
    end
  end
  return cases
end

-- ---------------------------------------------------------------- the scene

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local slot
  local cases, only, results, base, saved
  local ci, case, rec, frames, idle, hopTo
  local okCount = 0
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
        local precleaned = cleanup(homeRef)
        local v = version()
        slot = SaveData.createSlot(v)
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
        if SCENE == "scan" then
          scan(game)
          phase = "serve"
        elseif SCENE == "sweep" or SCENE == "objects" then
          cases, only = loadCases()
          if SCENE == "objects" then cases = objectCases(game, only) end
          results = array()
          base = snapshot(game)
          print(("KB_SWEEP_READY cases=%d"):format(#cases))
          phase = "wait_client"
        else
          fail("unknown_scene")
        end
      end
    elseif phase == "wait_client" then
      if (tonumber(status.sessions) or 0) >= 1 and fieldReady(game) then
        saved = quietHooks(game.world.vm)
        ci = 0
        phase = "next"
      end
    elseif phase == "next" then
      ci = ci + 1
      case = cases[ci]
      if not case then
        local w = game.world
        for _, k in ipairs({ "showTextFn", "yesornoFn", "startBattleFn", "applyMovementFn", "waitButtonFn", "resume" }) do
          w.vm[k] = (saved or {})[k]
        end
        local path = writeJson(SCENE == "objects" and "g2_story_objects.json" or "g2_story_sweep.json",
          { cases = results, scene = SCENE })
        print(("KB_SWEEP_DONE cases=%d ok=%d file=%s"):format(#results, okCount, word(path and "written" or "none")))
        phase = "serve"
      else
        rec = { n = case.n or ci, map = case.map, label = case.label, ok = false }
        scriptErrors = 0
        restore(game, base)
        local w = game.world
        local def = (game.data.gen2Maps or w.maps or {})[case.map]
        if type(def) ~= "table" then
          rec.err = "no_map"
          results[#results + 1] = rec
        elseif w.map.id == case.map then
          hopTo = case.map == HOP and HOP_ALT or HOP
          local hdef = (game.data.gen2Maps or w.maps)[hopTo]
          local hx, hy = arrivalCell(hdef)
          local ok = pcall(w.warpToMapId, w, hopTo, hx, hy, "down")
          frames, idle = 0, 0
          phase = ok and "hop" or "go"
        else
          phase = "go"
        end
      end
    elseif phase == "hop" then
      frames = frames + 1
      idle = fieldReady(game) and idle + 1 or 0
      if idle >= IDLE_FRAMES or frames >= SETTLE_FRAMES then
        if frames >= SETTLE_FRAMES then haltScripts(game) end
        restore(game, base)
        phase = "go"
      end
    elseif phase == "go" then
      local w = game.world
      local def = (game.data.gen2Maps or w.maps or {})[case.map]
      applyCase(game, case)
      local x, y = arrivalCell(def)
      local ok, res = pcall(w.warpToMapId, w, case.map, x, y, "down")
      if not ok or res == false then
        rec.err = ok and "warp_refused" or ("warp_error " .. word(res):sub(1, 60))
        results[#results + 1] = rec
        print(("KB_CASE n=%d map=%s ok=false err=%s"):format(rec.n, word(case.map), word(rec.err)))
        phase = "next"
      else
        rec.arrived = record(w)
        frames, idle = 0, 0
        phase = "settle"
      end
    elseif phase == "settle" then
      frames = frames + 1
      idle = fieldReady(game) and idle + 1 or 0
      if idle >= IDLE_FRAMES or frames >= SETTLE_FRAMES + 30 then
        local w = game.world
        rec.settled = record(w)
        rec.frames = frames
        if scriptErrors > 0 then rec.scriptErrors = scriptErrors end
        rec.ok = rec.settled.map == case.map
        if rec.ok then okCount = okCount + 1 end
        results[#results + 1] = rec
        local s = rec.settled
        print(("KB_CASE n=%d map=%s ok=%s blocks=%d shown=%d hidden=%d people=%d current=%s halted=%s errors=%d err=%s")
          :format(rec.n, word(case.map), tostring(rec.ok), #s.blocks, #s.shown, #s.hidden, #s.people, word(s.map),
          tostring(rec.halted or false), scriptErrors, word(rec.err or "none")))
        phase = "next"
      elseif frames == SETTLE_FRAMES then
        rec.halted = haltScripts(game)
        rec.haltedAt = frames
      end
    elseif phase == "serve" then
      if status.quitRequested then
        local deleted, now = cleanup(homeRef)
        print(("KB_CLEANED deleted=%d active=%d"):format(deleted, num(now)))
        print(("KB_QUIT sessions=%s code=%s"):format(word(status.sessions), word(status.quitCode)))
        return
      end
      if SCENE == "scan" then
        local deleted, now = cleanup(homeRef)
        print(("KB_CLEANED deleted=%d active=%d"):format(deleted, num(now)))
        print("KB_QUIT sessions=0 code=0")
        love.event.quit(0)
        while true do coroutine.yield() end
      end
    end
    if love.timer.getTime() - started > SECONDS_LIMIT then
      fail("timeout phase=" .. phase)
    end
    game = coroutine.yield() or game
  end
end
