-- Kanto Bridge test driver: Blue's story events and the world changes they
-- make, read from the real engine (the story check, docs/specs/
-- STORY_EVENTS_PLAN.md §6). Driven by tools/e2e/g1_story_check.py. Every
-- scene starts a new game without the intro (src/core/Game.lua:173-200; a new
-- game writes no save until the player saves, so no slot is touched) and moves
-- gen1-bridge to KB_TEST_PORT. Gen 1's story scripts are the engine's own Lua
-- (data/scripts/**, attached by src/script/MapScripts.lua), so there is no
-- script walk; KB_SCENE picks the part:
--
--   inventory  every map (size, objects with their names and `hidden`
--              defaults, whether a map-enter hook runs, dark), the event
--              flags the engine's scripts and field tables name (the
--              literal EVENT_* names in data/scripts/**.lua and the field
--              tables' door events) and the swap sites as gen1-bridge's
--              export lists them (export/kb_swapsites.lua: doorSites over
--              the field tables FieldDefaults.fieldValue reads, and
--              scriptSites; the map data's blocks from the boot copy taken
--              before any map loaded). Written to KB_REPORT_DIR/
--              g1_story_inventory.json (ROM-derived: it stays in local/);
--              stdout gets counts only.
--
--   sweep      the live check: the cases in KB_CASES (a Lua file the test
--              writes from the inventory: per case a map and the flags to
--              set or clear, or `every`: every inventory flag set; KB_MAPS,
--              comma-separated, keeps only those maps). Once a client
--              watches (the bridge diffs only then), for each case: the
--              story state back to the new game's (save.flags,
--              save.objectToggles, the Flash light) and every map's blocks
--              back to the boot copy (Gen 1's map scripts write into the
--              shared map record, Map.lua:244-249, so a later case would
--              see an earlier one's doors), the case's flags set, then a
--              warp into the map (WorldAPI:warpTo, src/world/
--              WorldAPI.lua:358-371: setMap stamps the doors and runs the
--              map-enter hooks, OC:385-428, :690-705) onto its first warp's
--              cell, else a walkable cell. Every case hops to another map
--              first and the reset happens there, so each case is a visit
--              of its own and no visit sees a reset. A text box or yes/no that comes up is answered B
--              (no) a few frames later, as a player would; a battle, or a
--              scene still holding the field after KB_SETTLE_FRAMES, is
--              halted (test-only). Recorded right after the arrival and
--              again once the field has stood idle for KB_IDLE_FRAMES:
--              every live block of the map that differs from the boot copy,
--              its objects (name, `hidden` default, visible by the engine's
--              own spawn filter OC.objectVisible, OC:232-248), the live
--              people (NPC ids, OC's "<MAP>_obj_<index>"), dark and lit,
--              the map the player is on. Written to KB_REPORT_DIR/
--              g1_story_sweep.json.
--
--   objects    the same loop over the maps whose objects have names
--              (KB_SKIP: comma-separated map id prefixes left out): per map
--              every named object toggled hidden (save.objectToggles, the
--              store WorldAPI:toggleObject writes), then every one shown;
--              recorded as above. Written to KB_REPORT_DIR/
--              g1_story_objects.json.
--
-- Markers: KB_BOOTED scene= pristine=, KB_FIELD map=, KB_INVENTORY maps=
-- flags= sites= dark= file=, KB_SWEEP_READY cases=, KB_CASE n= map= ok=
-- blocks= shown= hidden= people= current= halted= answered= err=,
-- KB_SWEEP_DONE cases= ok= file=, KB_QUIT sessions= code=; KB_FAIL <reason>
-- on a timeout. Map symbols, flag names and counts only.
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- with the Game (refs/gen1recomp/main.lua:755-759, :1131-1150).

io.stdout:setvbuf("no")

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27228
local SCENE = os.getenv("KB_SCENE") or "inventory"
local REPORT_DIR = os.getenv("KB_REPORT_DIR")
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or 900
local DATASET_MEMO = tonumber(os.getenv("KB_DATASET_MEMO") or "") or 0
local SETTLE_FRAMES = tonumber(os.getenv("KB_SETTLE_FRAMES") or "") or 600 -- then a scene still running is halted
local IDLE_FRAMES = tonumber(os.getenv("KB_IDLE_FRAMES") or "") or 15
local ANSWER_FRAMES = 4 -- a box is answered this many frames after it comes up
local HOPS = { "PALLET_TOWN", "ROUTE_1", "VIRIDIAN_CITY" } -- the first that is neither the case's map nor the current one

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

-- gen1-bridge's own swap sites, from the repository this driver sits in
local function loadSwapSites()
  local me = os.getenv("POKEPORT_DRIVER") or ""
  local repo = me:match("^(.*)[/\\]tools[/\\]e2e[/\\]drivers[/\\][^/\\]+$")
  if not repo then return nil end
  local chunk = loadfile(repo .. "/gen1-bridge/export/kb_swapsites.lua")
  return chunk and chunk() or nil
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
-- player standing still.
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

-- ---- the boot copy of every map's blocks (taken before any map loads)

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

local function pristineAt(id, def, bx, by)
  local b = pristine[id]
  local w = tonumber(def and def.width) or 0
  if not b or bx < 0 or by < 0 or bx >= w or by >= (tonumber(def.height) or 0) then return nil end
  return b[by * w + bx + 1]
end

-- every map's blocks back to the boot copy
local function resetBlocks(game)
  for id, b in pairs(pristine) do
    local def = game.data.maps[id]
    local live = def and def.blocks
    if type(live) == "table" then
      for i = 1, #b do if live[i] ~= b[i] then live[i] = b[i] end end
    end
  end
end

-- ---- the inventory

local function scriptFlags(out)
  local fs = love.filesystem
  local function walk(dir)
    for _, item in ipairs(fs.getDirectoryItems(dir) or {}) do
      local path = dir .. "/" .. item
      local info = fs.getInfo(path)
      if info and info.type == "directory" then
        walk(path)
      elseif item:match("%.lua$") then
        local text = fs.read(path)
        if type(text) == "string" then
          for name in text:gmatch("EVENT_[A-Z0-9_]+") do out[name] = true end
        end
      end
    end
  end
  walk("data/scripts")
end

local function fieldFor(game)
  local data = game.data
  local field = type(data.field) == "table" and data.field or {}
  local okF, FD = pcall(require, "src.world.FieldDefaults")
  local function value(...)
    if okF and type(FD) == "table" and type(FD.fieldValue) == "function" then
      local ok, v = pcall(FD.fieldValue, data, ...)
      if ok then return v end
    end
    local node = field
    for _, k in ipairs({ ... }) do node = type(node) == "table" and node[k] or nil end
    return node
  end
  local ck = type(field.cardKeyDoors) == "table" and field.cardKeyDoors or {}
  return {
    cardKeyDoors = { maps = ck.maps, openBlock = ck.openBlock, silphCo11F = ck.silphCo11F,
                     closedDoors = value("cardKeyDoors", "closedDoors"), skipMaps = value("cardKeyDoors", "skipMaps") },
    gameCornerPoster = field.gameCornerPoster,
    hiddenExtras = { trashCans = value("hiddenExtras", "trashCans") },
    darkMaps = field.darkMaps,
  }
end

local function inventory(game)
  local data = game.data
  local okM, MapScripts = pcall(require, "src.script.MapScripts")
  local okV, GameVersion = pcall(require, "src.core.GameVersion")
  local field = fieldFor(game)
  local dark = {}
  -- field.darkMaps.maps, the list OC's isDarkMap reads (OC:221-227)
  local darkDef = type(field.darkMaps) == "table" and field.darkMaps or {}
  for _, id in ipairs(type(darkDef.maps) == "table" and darkDef.maps or {}) do
    if type(id) == "string" then dark[id] = true end
  end
  local maps = {}
  for id, def in pairs(data.maps or {}) do
    if type(id) == "string" and type(def) == "table" then
      local objs = array()
      for _, o in ipairs(type(def.objects) == "table" and def.objects or {}) do
        if type(o) == "table" and not o.runtime then
          objs[#objs + 1] = { name = o.name, index = o.index, hidden = o.hidden == true,
                              item = o.item ~= nil and o.item ~= 0 and o.item ~= "0", pokemon = o.pokemon ~= nil }
        end
      end
      local hooks = okM and type(MapScripts.get) == "function" and select(2, pcall(MapScripts.get, id)) or nil
      maps[id] = { width = tonumber(def.width), height = tonumber(def.height), objects = objs,
                   warps = #(type(def.warps) == "table" and def.warps or {}),
                   enter = type(hooks) == "table" and hooks.onEnter ~= nil, dark = dark[id] == true }
    end
  end
  local names = {}
  scriptFlags(names)
  local sites = array()
  local SwapSites = loadSwapSites()
  if SwapSites then
    local function blockAt(mapId, bx, by)
      local def = data.maps[mapId]
      if not (def and type(bx) == "number" and type(by) == "number") then return nil end
      return pristineAt(mapId, def, bx, by)
    end
    local version = okV and GameVersion.get() or "blue"
    local rows = SwapSites.doorSites(field, version, blockAt)
    for _, r in ipairs(SwapSites.scriptSites(blockAt)) do
      if blockAt(r[1], r[2], r[3]) ~= nil then rows[#rows + 1] = r end
    end
    for _, s in ipairs(SwapSites.collect(rows)) do
      sites[#sites + 1] = s
      if s.source then names[s.source] = true end
    end
    for _, door in pairs(type(field.cardKeyDoors.closedDoors) == "table" and field.cardKeyDoors.closedDoors or {}) do
      for _, d in ipairs(type(door) == "table" and door or {}) do
        for _, ev in ipairs(type(d) == "table" and type(d.events) == "table" and d.events or {}) do names[ev] = true end
      end
    end
  end
  local flags = array()
  for name in pairs(names) do if name:find("^EVENT_[A-Z0-9_]+$") then flags[#flags + 1] = name end end
  table.sort(flags)
  local darkMaps = array()
  for id in pairs(dark) do darkMaps[#darkMaps + 1] = id end
  table.sort(darkMaps)
  local n = 0
  for _ in pairs(maps) do n = n + 1 end
  local path = writeJson("g1_story_inventory.json", { maps = maps, flags = flags, sites = sites, darkMaps = darkMaps,
                                                     swapSites = SwapSites ~= nil })
  print(("KB_INVENTORY maps=%d flags=%d sites=%d dark=%d swapsites=%s file=%s"):format(n, #flags, #sites, #darkMaps,
    tostring(SwapSites ~= nil), word(path and "written" or "none")))
end

-- ---- the live sweep

local function copy(t)
  local out = {}
  for k, v in pairs(type(t) == "table" and t or {}) do out[k] = type(v) == "table" and copy(v) or v end
  return out
end

local function snapshot(game)
  local save = game.save
  return { flags = copy(save.flags), toggles = copy(save.objectToggles), flashLit = save.flashLit }
end

local function restore(game, base)
  local save = game.save
  for k in pairs(save.flags) do save.flags[k] = nil end
  for k, v in pairs(base.flags) do save.flags[k] = v end
  save.objectToggles = copy(base.toggles)
  save.flashLit = base.flashLit
  resetBlocks(game)
end

-- every live block of the current map that differs from the boot copy
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

local function record(game)
  local ow = overworld(game)
  if not ow then return { map = nil } end
  local OC = require("src.world.OverworldController")
  local visibleFn = type(OC.objectVisible) == "function" and OC.objectVisible or nil
  local m = ow.map
  local objects, shown, hidden, people = array(), array(), array(), array()
  for _, o in ipairs(type(m.def.objects) == "table" and m.def.objects or {}) do
    if type(o) == "table" and not o.runtime then
      local ok, v = false, nil
      if visibleFn then ok, v = pcall(visibleFn, game.save, m.id, o) end
      local visible = ok and v and true or false
      objects[#objects + 1] = { name = o.name, index = o.index, hidden = o.hidden == true, visible = visible }
      local name = m.id .. "_obj_" .. tostring(o.index)
      if visible then shown[#shown + 1] = name else hidden[#hidden + 1] = name end
    end
  end
  for _, n in ipairs(type(ow.npcs) == "table" and ow.npcs or {}) do
    if type(n) == "table" and n.id then people[#people + 1] = n.id end
  end
  table.sort(people)
  return { map = m.id, x = ow.player.cellX, y = ow.player.cellY, blocks = blockDiffs(ow), objects = objects,
           shown = shown, hidden = hidden, people = people, dark = ow.dark == true,
           lit = game.save.flashLit == true }
end

-- Where the warp lands: the map's first warp's cell, else the first walkable dry cell (the engine's own map queries).
local function arrivalCell(game, mapId)
  local def = game.data.maps[mapId]
  local warps = type(def.warps) == "table" and def.warps or {}
  if type(warps[1]) == "table" and tonumber(warps[1].x) then return tonumber(warps[1].x), tonumber(warps[1].y) end
  local ts = game.data.tilesets and game.data.tilesets[def.tileset]
  local ok, m = pcall(require("src.world.Map").new, def, ts)
  if ok and m then
    for y = 0, (tonumber(def.height) or 1) * 2 - 1 do
      for x = 0, (tonumber(def.width) or 1) * 2 - 1 do
        local okW, walk = pcall(m.isWalkableCell, m, x, y)
        local okD, wet = pcall(m.isWaterCell, m, x, y)
        if okW and walk and not (okD and wet) then return x, y end
      end
    end
  end
  return floor((tonumber(def.width) or 2)), floor((tonumber(def.height) or 2))
end

-- A box on top of the field (text, yes/no, a menu) is answered B a few frames after it comes up; a battle is ended
-- (test-only: the states above the overworld popped).
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
    game.input:sourcePress("b", "kb-g1story")
    game.input:sourceRelease("b", "kb-g1story")
  end
end

-- Stops a scene that still holds the field (test-only): the states above the overworld popped, the scripted moves and
-- queued scripts dropped, the runner stopped, the player's input unlocked.
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
  if ow.runner and ow.runner:isRunning() then
    local ScriptRunner = require("src.script.ScriptRunner")
    ow.runner = ScriptRunner.new(game, ow)
    halted = true
  end
  if ow.emote then ow.emote = nil halted = true end
  if ow.player then ow.player.inputLocked = false end
  return halted
end

local function loadCases(inv)
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

-- The objects run's cases: per map with named objects, every one hidden, then every one shown.
local function objectCases(game, only)
  local skip = {}
  for p in (os.getenv("KB_SKIP") or ""):gmatch("[^,]+") do skip[#skip + 1] = p end
  local ids = {}
  for id, def in pairs(game.data.maps or {}) do
    if type(id) == "string" and type(def) == "table" and (not next(only) or only[id]) then
      local skipped = false
      for _, p in ipairs(skip) do if id:sub(1, #p) == p then skipped = true end end
      local names = {}
      for _, o in ipairs(type(def.objects) == "table" and def.objects or {}) do
        if type(o) == "table" and not o.runtime and type(o.name) == "string" then names[#names + 1] = o.name end
      end
      if not skipped and #names > 0 then ids[#ids + 1] = { id = id, names = names } end
    end
  end
  table.sort(ids, function(a, b) return a.id < b.id end)
  local cases = {}
  for _, e in ipairs(ids) do
    for _, mode in ipairs({ "hidden", "shown" }) do
      local toggles = {}
      for _, name in ipairs(e.names) do toggles[name] = mode == "shown" end
      cases[#cases + 1] = { n = #cases + 1, map = e.id, label = "objects " .. mode, toggles = toggles }
    end
  end
  return cases
end

local everyFlags = {}
local function applyCase(game, case)
  local save = game.save
  local function set(name, on)
    if on then save.flags[name] = true else save.flags[name] = nil end
  end
  if case.every then for _, name in ipairs(everyFlags) do set(name, true) end end
  for name, on in pairs(case.flags or {}) do set(name, on) end
  if case.toggles then
    save.objectToggles = save.objectToggles or {}
    save.objectToggles[case.map] = save.objectToggles[case.map] or {}
    for name, v in pairs(case.toggles) do save.objectToggles[case.map][name] = v end
  end
end

-- ---------------------------------------------------------------- the scene

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local cases, results, base, world
  local ci, case, rec, frames, idle, arrived
  local st = {}
  local okCount = 0
  local function warp(mapId)
    local x, y = arrivalCell(game, mapId)
    arrived = false
    return pcall(world.warpTo, world, mapId, x, y, "down", { onDone = function() arrived = true end })
  end
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    if phase == "ready" then
      if status and status.ready and game.data and game.data.maps and not overworld(game) then
        status.configure({ port = PORT })
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
        if SCENE == "inventory" then
          inventory(game)
          print("KB_QUIT sessions=0 code=0")
          love.event.quit(0)
          while true do coroutine.yield() end
        elseif SCENE == "sweep" or SCENE == "objects" then
          local only
          cases, only = loadCases()
          if SCENE == "objects" then cases = objectCases(game, only) end
          -- `every`: the flags the inventory named (its file beside the cases)
          local okI, invText = pcall(function()
            local f = io.open((REPORT_DIR or ".") .. "/g1_story_inventory.json", "rb")
            if not f then return nil end
            local s = f:read("*a")
            f:close()
            return s
          end)
          if okI and type(invText) == "string" then
            local list = invText:match('"flags":%[(.-)%]')
            for name in (list or ""):gmatch('"(EVENT_[A-Z0-9_]+)"') do
              if name ~= "EVENT_IN_SAFARI_ZONE" then everyFlags[#everyFlags + 1] = name end
            end
          end
          results = array()
          base = snapshot(game)
          world = require("src.world.WorldAPI").new(game, "kb-g1story-driver")
          print(("KB_SWEEP_READY cases=%d every=%d"):format(#cases, #everyFlags))
          phase = "wait_client"
        else
          fail("unknown_scene")
        end
      end
    elseif phase == "wait_client" then
      if (tonumber(status.sessions) or 0) >= 1 and fieldReady(game) then
        ci = 0
        phase = "next"
      end
    elseif phase == "next" then
      ci = ci + 1
      case = cases[ci]
      if not case then
        local path = writeJson(SCENE == "objects" and "g1_story_objects.json" or "g1_story_sweep.json",
          { cases = results, scene = SCENE })
        print(("KB_SWEEP_DONE cases=%d ok=%d file=%s"):format(#results, okCount, word(path and "written" or "none")))
        phase = "serve"
      else
        rec = { n = case.n or ci, map = case.map, label = case.label, ok = false }
        st = {}
        if type(game.data.maps[case.map]) ~= "table" then
          rec.err = "no_map"
          results[#results + 1] = rec
        else
          -- off the last case's map first: the story state goes back only once the player has left it, so the client
          -- never sees the reset as part of that visit
          local ow = overworld(game)
          local hop
          for _, id in ipairs(HOPS) do
            if not hop and id ~= case.map and not (ow and ow.map.id == id) then hop = id end
          end
          local ok = warp(hop)
          frames, idle = 0, 0
          phase = ok and "hop" or "go"
        end
      end
    elseif phase == "hop" then
      frames = frames + 1
      answer(game, st)
      idle = (arrived and fieldReady(game)) and idle + 1 or 0
      if idle >= IDLE_FRAMES or frames >= SETTLE_FRAMES then
        if frames >= SETTLE_FRAMES then halt(game) end
        phase = "go"
      end
    elseif phase == "go" then
      restore(game, base)
      applyCase(game, case)
      st = {}
      local ok, res, err = warp(case.map)
      if not ok or not res then
        rec.err = ok and ("warp_refused " .. word(err)) or ("warp_error " .. word(res):sub(1, 60))
        results[#results + 1] = rec
        print(("KB_CASE n=%d map=%s ok=false err=%s"):format(rec.n, word(case.map), word(rec.err)))
        phase = "next"
      else
        frames, idle = 0, 0
        phase = "arrive"
      end
    elseif phase == "arrive" then
      frames = frames + 1
      local ow = overworld(game)
      if arrived and ow and ow.map.id == case.map and not ow.transitioning then
        rec.arrived = record(game)
        frames, idle = 0, 0
        phase = "settle"
      elseif frames >= SETTLE_FRAMES then
        rec.err = "no_arrival"
        rec.halted = halt(game)
        rec.settled = record(game)
        results[#results + 1] = rec
        print(("KB_CASE n=%d map=%s ok=false err=no_arrival current=%s"):format(rec.n, word(case.map),
          word(rec.settled.map)))
        phase = "next"
      end
    elseif phase == "settle" then
      frames = frames + 1
      answer(game, st)
      idle = fieldReady(game) and idle + 1 or 0
      if idle >= IDLE_FRAMES or frames >= SETTLE_FRAMES + 30 then
        rec.settled = record(game)
        rec.frames = frames
        rec.answered = st.answered
        rec.battles = st.battles
        rec.ok = rec.settled.map == case.map
        if rec.ok then okCount = okCount + 1 end
        results[#results + 1] = rec
        local s = rec.settled
        print(("KB_CASE n=%d map=%s ok=%s blocks=%d shown=%d hidden=%d people=%d current=%s halted=%s answered=%d err=%s")
          :format(rec.n, word(case.map), tostring(rec.ok), #(s.blocks or {}), #(s.shown or {}), #(s.hidden or {}),
          #(s.people or {}), word(s.map), tostring(rec.halted or false), st.answered or 0, word(rec.err or "none")))
        phase = "next"
      elseif frames == SETTLE_FRAMES then
        rec.halted = halt(game)
        rec.haltedAt = frames
      end
    elseif phase == "serve" then
      if status.quitRequested then
        print(("KB_QUIT sessions=%s code=%s"):format(word(status.sessions), word(status.quitCode)))
        return
      end
    end
    if love.timer.getTime() - started > SECONDS_LIMIT then
      fail("timeout phase=" .. phase)
    end
    game = coroutine.yield() or game
  end
end
