-- game3 sites (docs/specs/STORY_EVENTS_PLAN.md §4: SE1, SE2, SE7): every
-- cell Emerald's story can change, found without running anything, and the
-- cells every visit changes the same way.
--
-- The engine's script bundle is already decoded into rows (the importer's
-- breadth-first walk, src/import/gba/extract_scripts.lua:251-410; a row is
-- { op = "setmetatile", [1] = x, [2] = y, [3] = metatile, [4] = impassable },
-- scripting/disasm.lua:41-125). A map's roots are its entry scripts
-- (onTransition, onLoad, onResume, onReturnToField, onDiveWarp; the onFrame
-- and onWarpIntoMap tables, var-conditioned, extract_map_events.lua:201-272)
-- and its objects', signs' and coord events' scripts. The walk follows
-- goto/call/goto_if/call_if (row.target, opcodes.lua:75-78 Opcodes.key), the
-- standard scripts ("std:<n>") and trainer battles' event scripts, keeping
-- the conditions taken on the way: checkflag and the compares, switch/case
-- (copyvar VAR_0x8000), specialvar results (ops_a.lua:34-44, :364-503).
--
--   b.bakedCells(map)  the changes of the entry scripts every visit runs
--                      (onTransition, onLoad, onResume: space.lua:447-522)
--                      under no condition: a layout swap (setmaplayoutindex,
--                      ops_a.lua:252-298) or a setmetatile. The export bakes
--                      them into g3map, so they are the map's own cells.
--   b.ownCell(map, x, y)  the cell as the export has it: a Wild Routes
--                      clearing's (routes.lua), else the baked change, else
--                      the layout's own; and b.ownPass its collision class
--                      after the engine's door repair on map load.
--   b.scriptDarkMaps() SE7: maps whose entry scripts set a flash level above
--                      0 (setflashlevel/animateflash, ops_a.lua:1203-1228;
--                      Dewford Gym), dark until the game's level there is 0.
--   cellSites(checkpoint)  SE2: the g3sites export: per changeable cell its
--                      own state and every other state a script's
--                      setmetatile, an alternate layout or an engine writer
--                      can give it (protocol/export/g3sites.schema.json).
--
-- A state's collision byte is the engine's own: setmetatile's 0x07 when
-- impassable, else what Field.setMetatile derives from the metatile's
-- behaviour (field.lua:2179-2191 passableColl, :2217-2221), a layout's cell's
-- own; on a warp cell the repair Collision.installWarps makes on every bind
-- (collision.lua:167-205). The pass classes are PROTOCOL.md's (SE1).
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Sites = {}

local floor = math.floor

-- The engine's collision bytes a player walks on (g3map's vocabulary, PROTOCOL.md "Story rules", SE1): plain, tall
-- grass, cave floor, door, a warp keeping the facing, the four ledges; 0x29 is water; anything else is solid.
Sites.GROUND = { [0x00] = true, [0x18] = true, [0x2B] = true, [0x71] = true, [0x72] = true,
                 [0xA0] = true, [0xA1] = true, [0xA2] = true, [0xA3] = true }
Sites.WATER = 0x29
Sites.DOOR = 0x71 -- the door repair's byte (collision.lua:166)

-- The entry scripts every visit runs (space.lua:447-522: onTransition, the
-- secret base hook, onLoad, onResume); a change in them under no condition
-- is the map's own.
Sites.EVERY_VISIT = { onTransition = true, onLoad = true, onResume = true }
-- SE7: the roots whose flash level darkens a map (the directive's onTransition/onLoad)
Sites.DARKENING = { onTransition = true, onLoad = true }
Sites.ROOTS = { "onTransition", "onLoad", "onResume", "onReturnToField", "onDiveWarp" }
Sites.TABLES = { "onFrame", "onWarpIntoMap" }

-- Places left out (the plan's S7): the Battle Frontier's generated floors, secret bases, the link rooms.
Sites.LEFT_OUT = { "EM_BATTLE_FRONTIER_", "EM_BATTLE_PYRAMID_", "EM_TRAINER_HILL_", "EM_SECRET_BASE_",
                   "EM_UNION_ROOM", "EM_TRADE_CENTER", "EM_RECORD_CORNER", "EM_BATTLE_COLOSSEUM_",
                   "EM_LINK_CONTEST_" }

-- VAR_ABNORMAL_WEATHER_LOCATION's values and their routes (natives_field_rse.lua:384-402,
-- GetAbnormalWeatherMapNameAndType): a script that places another route's cave entrance never runs here.
Sites.ABNORMAL = { EM_ROUTE114 = { [1] = true, [2] = true }, EM_ROUTE115 = { [3] = true, [4] = true },
                   EM_ROUTE116 = { [5] = true, [6] = true }, EM_ROUTE118 = { [7] = true, [8] = true },
                   EM_ROUTE105 = { [9] = true, [10] = true }, EM_ROUTE125 = { [11] = true, [12] = true },
                   EM_ROUTE127 = { [13] = true, [14] = true }, EM_ROUTE129 = { [15] = true, [16] = true } }
Sites.ABNORMAL_VAR = "VAR_ABNORMAL_WEATHER_LOCATION"
Sites.ABNORMAL_VAR_ID = 0x4037 -- constants/emerald/vars.lua, when the tables lack it

Sites.MAX_STATES = 16
Sites.WALK_ROWS = 200000   -- rows one map's walk visits at most (all its roots)
Sites.PATHS_PER_KEY = 6    -- condition paths followed into one script list
Sites.MAX_DEPTH = 48
Sites.MAX_CONDS = 12       -- conditions kept on a path (the nearest win)
Sites.CHECKPOINT_ROWS = 2000

local VAR_RESULT, VAR_0x8000 = 0x800D, 0x8000

-- Rows that end a list (ops_a.lua:364-440).
local STOPS = { ["end"] = true, ["return"] = true, ["goto"] = true, gotostd = true, endram = true, returnram = true,
                gotobeatenscript = true, gotopostbattlescript = true, gotonative = true, vgoto = true }
-- Rows whose result a following goto_if/call_if tests, besides checkflag and the compares.
local CHECKS = { checktrainerflag = "trainer", checkitem = "item", checkitemspace = "item", checkitemtype = "item",
                 checkpcitem = "item", checkdecor = "decor", checkdecorspace = "decor", checkpartymove = "move",
                 checkplayergender = "gender", checkmoney = "money", checkcoins = "coins", yesnobox = "answer",
                 multichoice = "answer", multichoicedefault = "answer", multichoicegrid = "answer", random = "random",
                 getpartysize = "party", checkmonmodernfatefulencounter = "fateful" }
local COND_OPS = { [0] = "<", [1] = "==", [2] = ">", [3] = "<=", [4] = ">=", [5] = "!=" }
local NEGATE = { [0] = 4, [1] = 5, [2] = 3, [3] = 2, [4] = 0, [5] = 1 }

-- The class of a collision byte: "ground", "water" or "solid" (PROTOCOL.md, SE1).
function Sites.passOf(coll)
  coll = tonumber(coll)
  if coll == nil then return "solid" end
  if Sites.GROUND[coll] then return "ground" end
  if coll == Sites.WATER then return "water" end
  return "solid"
end

-- A condition from the check a goto_if/call_if tests and its condition code (checkflag's result is 1 when set,
-- ops_a.lua:34-44), or nil for one that always holds; { k = "never" } for one that never does.
local function condOf(check, code, negate)
  code = tonumber(code)
  if code == nil or COND_OPS[code] == nil then return { k = "?" } end
  if negate then code = NEGATE[code] end
  if not check then return { k = "result", op = COND_OPS[code] } end
  if check.k == "flag" then
    local set = ({ [0] = false, [1] = true, [3] = "any", [4] = true, [5] = false })[code]
    if set == "any" then return nil end
    if set == nil then return { k = "never" } end
    return { k = "flag", id = check.id, set = set }
  end
  return { k = check.k, id = check.id, name = check.name, op = COND_OPS[code], value = check.value }
end

local function condSig(conds)
  local parts = {}
  for i, c in ipairs(conds) do
    parts[i] = table.concat({ c.k or "", tostring(c.id or c.name or ""), tostring(c.op or ""), tostring(c.value or ""),
      tostring(c.set) }, ",")
  end
  return table.concat(parts, ";")
end

local function withCond(conds, c)
  if c == nil then return conds end
  local out = {}
  for i = math.max(1, #conds - Sites.MAX_CONDS + 2), #conds do out[#out + 1] = conds[i] end
  out[#out + 1] = c
  return out
end

-- A map's roots: { kind, key, info }.
function Sites.roots(ev, key)
  local out = {}
  local ms = type(ev.mapScripts) == "table" and ev.mapScripts or {}
  local function add(kind, k, info)
    if type(k) == "string" or type(k) == "number" then out[#out + 1] = { kind = kind, key = key(k), info = info } end
  end
  for _, kind in ipairs(Sites.ROOTS) do add(kind, ms[kind]) end
  for _, kind in ipairs(Sites.TABLES) do
    for _, r in ipairs(type(ms[kind]) == "table" and ms[kind] or {}) do
      if type(r) == "table" then add(kind, r.script, { var = tonumber(r.var), value = tonumber(r.value) }) end
    end
  end
  for _, o in ipairs(type(ev.objects) == "table" and ev.objects or {}) do
    if type(o) == "table" then add("object", o.scriptKey) end
  end
  for _, s in ipairs(type(ev.bgEvents) == "table" and ev.bgEvents or {}) do
    if type(s) == "table" then add("bg", s.scriptKey) end
  end
  for _, c in ipairs(type(ev.coordEvents) == "table" and ev.coordEvents or {}) do
    if type(c) == "table" and c.scriptKey ~= nil then
      add("coord", c.scriptKey, { var = tonumber(c.var), value = tonumber(c.value) })
    end
  end
  return out
end

-- Walks the rows `roots` reach: visit(row, root, conds) for every row (the conditions on its path; a coord or table
-- root's var condition first). scripts: the bundle's scripts; specialName(id); opts: { rows, checkpoint }.
-- Returns the rows visited and whether the walk stopped at its limit.
function Sites.walk(scripts, roots, key, specialName, visit, opts)
  opts = opts or {}
  local limit = opts.rows or Sites.WALK_ROWS
  local checkpoint = opts.checkpoint
  local visits, truncated = 0, false
  local function list(k)
    local ok, l = pcall(function() return scripts[k] end)
    return ok and type(l) == "table" and l or nil
  end
  for _, r in ipairs(roots) do
    local paths = {}
    local start = {}
    if r.info and r.info.var then
      start = { { k = "var", id = r.info.var, op = "==", value = r.info.value } }
    end
    local function walk(k, conds, depth)
      if depth > Sites.MAX_DEPTH or truncated then return end
      local l = list(k)
      if not l then return end
      local sig = condSig(conds)
      local p = paths[k]
      if not p then
        p = { n = 0 }
        paths[k] = p
      end
      if p[sig] or p.n >= Sites.PATHS_PER_KEY then return end
      p[sig], p.n = true, p.n + 1
      local cur, check, alias8000, resultFrom = conds, nil, nil, nil
      for _, row in ipairs(l) do
        visits = visits + 1
        if visits > limit then
          truncated = true
          return
        end
        if checkpoint and visits % Sites.CHECKPOINT_ROWS == 0 then checkpoint() end
        local op = type(row) == "table" and row.op or nil
        if op == nil then break end
        if op == "checkflag" then
          check = { k = "flag", id = tonumber(row.flag or row[1]) }
        elseif op == "compare_var_to_value" then
          local id, value = tonumber(row.var or row[1]), tonumber(row.value or row[2])
          if id == VAR_0x8000 and alias8000 then id = alias8000 end
          if id == VAR_RESULT and resultFrom then
            check = { k = "special", name = resultFrom, value = value }
          else
            check = { k = "var", id = id, value = value }
          end
        elseif op == "compare_var_to_var" then
          check = { k = "varvar", id = tonumber(row[1]), value = tonumber(row[2]) }
        elseif op == "copyvar" then
          if tonumber(row[1]) == VAR_0x8000 then alias8000 = tonumber(row[2]) end
        elseif op == "specialvar" then
          if tonumber(row[1]) == VAR_RESULT then resultFrom = specialName(row[2]) end
        elseif CHECKS[op] then
          check = { k = CHECKS[op], id = tonumber(row[1]) }
        end
        visit(row, r, cur)
        if op == "goto_if" or op == "call_if" or op == "vgoto_if" or op == "vcall_if" then
          local c = condOf(check, row.cond or row[1])
          if row.target ~= nil and (c == nil or c.k ~= "never") then walk(key(row.target), withCond(cur, c), depth + 1) end
          if op == "goto_if" or op == "vgoto_if" then cur = withCond(cur, condOf(check, row.cond or row[1], true)) end
        elseif op == "goto" or op == "call" or op == "vgoto" or op == "vcall" then
          if row.target ~= nil then walk(key(row.target), cur, depth + 1) end
        elseif op == "callstd" or op == "gotostd" then
          walk("std:" .. tostring(row.std or row[1]), cur, depth + 1)
        elseif op == "callstd_if" or op == "gotostd_if" then
          walk("std:" .. tostring(row[2]), withCond(cur, condOf(check, row[1])), depth + 1)
          if op == "gotostd_if" then cur = withCond(cur, condOf(check, row[1], true)) end
        elseif op == "trainerbattle" and row.eventScript ~= nil then
          walk(key(row.eventScript), cur, depth + 1)
        end
        if STOPS[op] then break end
      end
    end
    walk(r.key, start, 0)
  end
  return visits, truncated
end

function Sites.leftOut(map)
  for _, p in ipairs(Sites.LEFT_OUT) do
    if map:sub(1, #p) == p then return true end
  end
  return false
end

function Sites.install(b)
  local def = b.def
  b.passOf = Sites.passOf

  local function space()
    local Space = b.g3("scripting.space")
    local bundle = type(Space) == "table" and Space.bundle or nil
    if type(bundle) ~= "table" or type(bundle.events) ~= "table" then return nil end
    return bundle
  end
  local function maps()
    local g = b.game
    return type(g) == "table" and type(g.data) == "table" and type(g.data.maps) == "table" and g.data.maps or nil
  end
  local function keyFn()
    local Opcodes = b.g3("scripting.opcodes")
    if type(Opcodes) == "table" and type(Opcodes.key) == "function" then return Opcodes.key end
    return function(addr)
      if type(addr) == "string" then return addr end
      return ("g3:%08x"):format(tonumber(addr) or 0)
    end
  end
  local function specialNameFn()
    local C = b.constants()
    return function(id)
      local ok, name = pcall(function() return C:specialName(tonumber(id)) end)
      return ok and name or nil
    end
  end
  local function abnormalVar()
    local t = b.flagTables()
    local id = t and type(t.VAR_IDS) == "table" and t.VAR_IDS[Sites.ABNORMAL_VAR] or nil
    return tonumber(id) or Sites.ABNORMAL_VAR_ID
  end
  -- false when a path asks VAR_ABNORMAL_WEATHER_LOCATION for a value that names another route
  local function abnormalOk(map, conds, varId)
    for _, c in ipairs(conds) do
      if c.k == "var" and c.id == varId and c.op == "==" then
        local own = Sites.ABNORMAL[map]
        if not (own and own[tonumber(c.value)]) then return false end
      end
    end
    return true
  end

  -- A map's definition: the game data's (dataset.lua:122-125), or the current map's (map.lua:23-25) where the data
  -- has none (the same table on the engine).
  local function defOf(map)
    local data = maps()
    local mdef = data and data[map] or nil
    if type(mdef) ~= "table" then
      local Map = b.g3("map")
      if type(Map) == "table" and Map.current == map then mdef = b.ask(Map, "currentDef", nil) end
    end
    return type(mdef) == "table" and mdef or nil
  end

  local function layoutOf(map, mdef)
    local ok, lay = pcall(function() return mdef.midLayout end) -- lazy (dataset.lua:357-372)
    if ok and type(lay) == "table" and type(lay.cells) == "table" then return lay end
    return nil
  end
  local function pairOf(mdef, lay) return mdef.pair or (lay and lay.pair) end
  local function baseCell(lay, x, y)
    local w = floor(tonumber(lay.width) or 0)
    local tw = floor(tonumber(lay.trueWidth) or w)
    local th = floor(tonumber(lay.trueHeight) or tonumber(lay.height) or 0)
    if x < 0 or y < 0 or x >= tw or y >= th or w < 1 then return nil end
    local c = lay.cells[y * w + x + 1]
    if type(c) ~= "table" then return nil end
    return floor(tonumber(c.mid) or 0), floor(tonumber(c.coll) or 0xff), floor(tonumber(c.elev) or 0)
  end

  -- A metatile's behaviour in a pair (collision.lua:247-253's table).
  local function behaviorOf(pair, mid)
    local IS = b.g3("scripting.interaction_scripts")
    local behaviors = type(IS) == "table" and type(IS.behaviors) == "table" and IS.behaviors[pair] or nil
    return type(behaviors) == "table" and behaviors[mid] or nil
  end
  -- The collision byte Field.setMetatile gives a passable metatile (field.lua:2179-2191 passableColl).
  local function passableColl(mdef, pair, mid)
    local beh = behaviorOf(pair, mid)
    if beh ~= nil then
      local ok, coll = b.call(b.g3("scripting.collision"), "fromCell", mid, 0, beh, mdef.kind)
      if ok and tonumber(coll) then return floor(tonumber(coll)) end
    end
    local Register = b.mod("src.import.gba.register")
    local idx = type(Register) == "table" and type(Register._midIndex) == "table" and Register._midIndex[pair] or nil
    local row = type(idx) == "table" and idx[mid] or nil
    return type(row) == "table" and tonumber(row.coll) or 0x00
  end
  local function warpCell(mdef, x, y)
    for _, w in ipairs(type(mdef.warps) == "table" and mdef.warps or {}) do
      if tonumber(w.x) == x and tonumber(w.y) == y then return true end
    end
    return false
  end
  -- The door repair every bind makes on a warp cell (collision.lua:167-205): a solid, void or missing collision
  -- under an unknown or warp behaviour becomes a door's.
  local function repaired(mdef, pair, x, y, mid, coll)
    if not warpCell(mdef, x, y) then return coll end
    if coll ~= nil and coll ~= 0x07 and coll ~= 0xff then return coll end
    local beh = behaviorOf(pair, mid)
    local C = b.g3("collision")
    local warp = beh == nil or (type(C) == "table" and type(C.isWarpMetatileBehavior) == "function"
      and C.isWarpMetatileBehavior(beh) == true)
    return warp and Sites.DOOR or coll
  end

  -- An alternate layout as setmaplayoutindex reads it (ops_a.lua:243-298): the cells, or nil and why.
  local layoutFiles
  local function altLayout(layoutId)
    local Dataset, Extract = b.g3("dataset"), b.mod("src.import.gba.extract_island1")
    local NativePack = b.mod("src.import.gba.native_pack")
    if not (Dataset and Extract and NativePack and type(NativePack.decodeMidLayout) == "function") then return nil, "no_engine" end
    local okC, cache = b.call(Dataset, "cache")
    if not (okC and type(cache) == "table") then return nil, "no_cache" end
    -- the engine's own roots (extract_island1.lua:21-27, set by Dataset.mountExtractRoots)
    local root = Extract.NATIVE_ROOT or (type(Extract.CACHE_ROOT) == "string" and Extract.CACHE_ROOT .. "/native") or nil
    if type(root) ~= "string" then return nil, "no_cache_root" end
    local fileKey
    local family = b.ask(b.g3("profile"), "family", "rse", b.session())
    if family ~= "rse" then
      fileKey = "alt_" .. tostring(layoutId)
    else
      if layoutFiles == nil then
        local okR, src = pcall(cache.read, cache, root .. "/layout_files.lua")
        local chunk = okR and type(src) == "string" and load(src, "@layout_files.lua", "t", {}) or nil
        local okF, files = false, nil
        if chunk then okF, files = pcall(chunk) end
        layoutFiles = okF and type(files) == "table" and files or false
      end
      fileKey = layoutFiles and layoutFiles[tonumber(layoutId)] or nil
    end
    if not fileKey then return nil, "no_layout" end
    local okB, blob = pcall(cache.read, cache, root .. "/layouts/" .. fileKey .. ".mid")
    if not (okB and type(blob) == "string") then return nil, "no_layout" end
    local okD, decoded = pcall(NativePack.decodeMidLayout, blob)
    if not (okD and type(decoded) == "table" and type(decoded.cells) == "table") then return nil, "undecodable" end
    return decoded
  end

  -- One map's world rows: { metatiles = { { x, y, mid, imp, root, conds } }, layouts = { { id, root, conds } },
  -- flash = { { level, root, conds } }, specials = { name = true }, truncated }. entryOnly: the every-visit roots.
  local scans = { bundle = nil, maps = nil, entry = {}, full = {} }
  local function scanMap(map, entryOnly, checkpoint)
    local bundle, data = space(), maps()
    if not bundle then return nil end
    if scans.bundle ~= bundle or scans.maps ~= data then
      scans.bundle, scans.maps, scans.entry, scans.full = bundle, data, {}, {}
    end
    local store = entryOnly and scans.entry or scans.full
    if store[map] then return store[map] end
    local out = { metatiles = {}, layouts = {}, flash = {}, specials = {}, truncated = false }
    local ev = bundle.events[map]
    if type(ev) == "table" then
      local key = keyFn()
      local roots = Sites.roots(ev, key)
      if entryOnly then
        local kept = {}
        for _, r in ipairs(roots) do
          if Sites.EVERY_VISIT[r.kind] then kept[#kept + 1] = r end
        end
        roots = kept
      end
      local specialName = specialNameFn()
      local abVar = abnormalVar()
      local seen = {}
      local function once(kind, sig)
        if seen[kind .. "|" .. sig] then return false end
        seen[kind .. "|" .. sig] = true
        return true
      end
      local scripts = type(bundle.scripts) == "table" and bundle.scripts or {}
      local _, truncated = Sites.walk(scripts, roots, key, specialName, function(row, root, conds)
        local op = row.op
        if op == "setmetatile" then
          local x, y, mid, imp = tonumber(row[1]), tonumber(row[2]), tonumber(row[3]), tonumber(row[4])
          -- an operand that is a var (0x4000 and up, ops_a.lua:72-81) is decided at run time: left out
          if x and y and mid and x < 0x4000 and y < 0x4000 and mid < 0x4000 and abnormalOk(map, conds, abVar)
              and once("m", table.concat({ root.kind, x, y, mid, imp or 0, condSig(conds) }, ",")) then
            out.metatiles[#out.metatiles + 1] = { x = floor(x), y = floor(y), mid = floor(mid), imp = (imp or 0) ~= 0,
                                                  root = root.kind, conds = conds }
          end
        elseif op == "setmaplayoutindex" then
          local id = tonumber(row[1])
          if id and id < 0x4000 and abnormalOk(map, conds, abVar)
              and once("l", table.concat({ root.kind, id, condSig(conds) }, ",")) then
            out.layouts[#out.layouts + 1] = { id = floor(id), root = root.kind, conds = conds }
          end
        elseif op == "setflashlevel" or op == "animateflash" then
          local level = tonumber(row[1])
          if level and level < 0x4000 then
            out.flash[#out.flash + 1] = { level = floor(level), root = root.kind, conds = conds }
          end
        elseif op == "special" or op == "specialvar" then
          local name = specialName(op == "special" and (row.id or row[1]) or row[2])
          if type(name) == "string" then out.specials[name] = true end
        end
      end, { checkpoint = checkpoint })
      out.truncated = truncated
    end
    store[map] = out
    return out
  end

  -- ---- the cells every visit changes the same way (SE2's baked cells)

  local bakedCache = { bundle = nil, maps = nil, byMap = {} }
  function b.bakedCells(map)
    local bundle, data = space(), maps()
    if not (bundle and data and b.symbol(map)) then return {} end
    if bakedCache.bundle ~= bundle or bakedCache.maps ~= data then
      bakedCache.bundle, bakedCache.maps, bakedCache.byMap = bundle, data, {}
    end
    local hit = bakedCache.byMap[map]
    if hit then return hit end
    local out = {}
    local mdef = defOf(map)
    local lay = mdef and layoutOf(map, mdef) or nil
    if lay and not Sites.leftOut(map) then
      local scan = scanMap(map, true)
      local pair = pairOf(mdef, lay)
      local function current(x, y)
        local o = out[y * 1024 + x]
        if o then return o.mid, o.coll, o.elev end
        return baseCell(lay, x, y)
      end
      -- in the order a visit runs them: the transition's, then the load's, then the resume's (space.lua:483-504)
      for _, kind in ipairs({ "onTransition", "onLoad", "onResume" }) do
        for _, l in ipairs(scan and scan.layouts or {}) do
          if l.root == kind and #l.conds == 0 then
            local alt = altLayout(l.id)
            if alt and alt.width == lay.width and alt.height == lay.height then
              for i = 1, alt.width * alt.height do
                local cell = alt.cells[i]
                local x, y = (i - 1) % alt.width, floor((i - 1) / alt.width)
                local bm, bc, be = current(x, y)
                if type(cell) == "table" and bm ~= nil then
                  local m, c, e = floor(tonumber(cell.mid) or 0), floor(tonumber(cell.coll) or 0xff), floor(tonumber(cell.elev) or 0)
                  if m ~= bm or c ~= bc or e ~= be then out[y * 1024 + x] = { mid = m, coll = c, elev = e } end
                end
              end
            end
          end
        end
        for _, m in ipairs(scan and scan.metatiles or {}) do
          if m.root == kind and #m.conds == 0 then
            local bm, _, be = current(m.x, m.y)
            if bm ~= nil then
              local coll = m.imp and 0x07 or passableColl(mdef, pair, m.mid)
              out[m.y * 1024 + m.x] = { mid = m.mid, coll = coll, elev = be }
            end
          end
        end
      end
    end
    bakedCache.byMap[map] = out
    return out
  end

  -- A map's cells as the export has them: own(x, y) -> (mid, coll, elev), or nil off its true size. A Wild Routes
  -- clearing's cells first (MG8: export.lua bakes them in open, collision 0x00), then the baked changes, then the
  -- layout's own. Built once per use (the clearings and the baked cells are read once).
  local function ownFn(map)
    local mdef = defOf(map)
    local lay = mdef and layoutOf(map, mdef) or nil
    if not lay then return function() return nil end end
    local rc = type(b.routeCells) == "function" and b.routeCells(map) or nil
    if type(rc) ~= "table" then rc = {} end
    local baked = b.bakedCells(map)
    return function(x, y)
      local bm, bc, be = baseCell(lay, x, y)
      if bm == nil then return nil end
      local k = y * 1024 + x
      local o = rc[k]
      if o then return floor(o.mid), 0x00, floor(tonumber(o.elev) or be) end
      local c = baked[k]
      if c then return c.mid, c.coll, c.elev end
      return bm, bc, be
    end
  end

  -- The cell as the export has it: (mid, coll, elev), or nil off the map's true size.
  function b.ownCell(map, x, y) return ownFn(map)(x, y) end

  -- The own cell's metatile and pass class as a visit shows it (the door repair applied).
  function b.ownPass(map, x, y)
    local mid, coll = b.ownCell(map, x, y)
    if mid == nil then return nil end
    local mdef = defOf(map)
    local lay = layoutOf(map, mdef)
    return mid, Sites.passOf(repaired(mdef, pairOf(mdef, lay), x, y, mid, coll))
  end

  -- ---- SE7: maps their scripts darken

  local darkCache = { bundle = nil, maps = nil, set = nil }
  function b.scriptDarkMaps()
    local bundle, data = space(), maps()
    if not (bundle and data) then return {} end
    if darkCache.bundle == bundle and darkCache.maps == data and darkCache.set then return darkCache.set end
    local set = {}
    for map, ev in pairs(bundle.events) do
      local ms = type(ev) == "table" and type(ev.mapScripts) == "table" and ev.mapScripts or nil
      if b.symbol(map) and ms and (ms.onTransition ~= nil or ms.onLoad ~= nil) and data[map] ~= nil then
        local scan = scanMap(map, true)
        for _, f in ipairs(scan and scan.flash or {}) do
          if Sites.DARKENING[f.root] and f.level > 0 then set[map] = true end
        end
      end
    end
    darkCache.bundle, darkCache.maps, darkCache.set = bundle, data, set
    return set
  end

  -- ---- SE2: every changeable cell

  -- The engine writers' states on one map (the plan's S1 "engine writers with fixed tables"), into add(x, y, mid,
  -- coll, elev). Each reads the engine's own tables and functions with a dry-run get and set (nothing of the live
  -- game is touched); a writer whose table the engine doesn't expose is left out.
  local function nativeStates(map, mdef, lay, scan, add, counts, own)
    local pair = pairOf(mdef, lay)
    local C = b.constants()
    local function label(name)
      local ok, id = pcall(function() return C:id("metatile_labels", "METATILE_" .. name) end)
      return ok and tonumber(id) or nil
    end
    -- Mauville Gym's barriers and switches (rse/story_specials.lua:16-105): the switch presses, the default
    -- barriers and the deactivated puzzle, run on the gym's own cells and on what each run leaves
    local specials = scan and scan.specials or {}
    local Story = b.g3("rse.story_specials")
    if type(Story) == "table" and C and (specials.MauvilleGymPressSwitch or specials.MauvilleGymSetDefaultBarriers
        or specials.MauvilleGymDeactivatePuzzle) then
      local grids = { {} }
      local function run(grid, fn)
        local next_ = {}
        for k, v in pairs(grid) do next_[k] = v end
        local function get(x, y)
          local v = next_[y * 1024 + x]
          if v then return v.mid end
          return (own(x, y))
        end
        local function set(x, y, mid, imp)
          mid = tonumber(mid)
          if mid then next_[y * 1024 + x] = { mid = floor(mid), imp = imp == true } end
        end
        local ok = pcall(fn, get, set)
        if ok then grids[#grids + 1] = next_ end
        return next_
      end
      local first = run(grids[1], function(g, s) Story.mauvilleSetDefaultBarriers(g, s, C) end)
      run(first, function(g, s) Story.mauvilleSetDefaultBarriers(g, s, C) end)
      run(grids[1], function(g, s) Story.mauvilleDeactivatePuzzle(g, s, C) end)
      run(first, function(g, s) Story.mauvilleDeactivatePuzzle(g, s, C) end)
      for i = 0, #(Story.MAUVILLE_SWITCHES or {}) - 1 do
        run(grids[1], function(g, s) Story.mauvillePressSwitch(i, g, s, C) end)
        run(first, function(g, s) Story.mauvillePressSwitch(i, g, s, C) end)
      end
      for _, grid in ipairs(grids) do
        for k, v in pairs(grid) do
          local x, y = k % 1024, floor(k / 1024)
          local _, _, e = own(x, y)
          if e then add(x, y, v.mid, v.imp and 0x07 or passableColl(mdef, pair, v.mid), e, "native") end
        end
      end
      counts.mauville = counts.mauville + 1
    end
    -- Petalburg Gym's sliding doors (story_specials.lua:113-150): every room's door cells in each frame, impassable as
    -- the engine sets them (PetalburgGymSlideOpenRoomDoors, PetalburgGymUnlockRoomDoors)
    if type(Story) == "table" and C and type(Story.PETALBURG_DOORS) == "table"
        and type(Story.petalburgSetDoorMetatiles) == "function" and type(Story.slidingDoorMetatile) == "function"
        and (specials.PetalburgGymSlideOpenRoomDoors or specials.PetalburgGymUnlockRoomDoors) then
      for room in pairs(Story.PETALBURG_DOORS) do
        for frame = 0, 4 do
          local okM, mid = pcall(Story.slidingDoorMetatile, C, frame)
          if okM and tonumber(mid) then
            pcall(Story.petalburgSetDoorMetatiles, room, mid, function(x, y, m, imp)
              local _, _, e = own(x, y)
              m = tonumber(m)
              if e and m then add(x, y, floor(m), imp and 0x07 or passableColl(mdef, pair, floor(m)), e, "native") end
            end)
          end
        end
      end
      counts.petalburg = counts.petalburg + 1
    end
    -- The Mirage Tower's cells hidden as it shakes (mirage_tower.lua:149-155, the field-effect manifest's list)
    if specials.StartMirageTowerShake then
      local FxRse = b.g3("field_effects_rse")
      local okF, fc = b.call(FxRse, "fc")
      local list = okF and type(fc) == "table" and type(fc.mirageTower) == "table" and fc.mirageTower.invisibleMetatiles or nil
      for _, t in ipairs(type(list) == "table" and list or {}) do
        local x, y, m = tonumber(t.x), tonumber(t.y), tonumber(t.metatile)
        if x and y and m then
          local _, _, e = own(floor(x), floor(y))
          if e then add(floor(x), floor(y), floor(m), passableColl(mdef, pair, floor(m)), e, "native") end
        end
      end
      counts.mirage = counts.mirage + (type(list) == "table" and 1 or 0)
    end
    -- The Lilycove lottery's laptop (rse/lottery.lua:126-134: normal and flashing, impassable)
    if specials.DoLotteryCornerComputerEffect or specials.EndLotteryCornerComputerEffect then
      local Lottery = b.g3("rse.lottery")
      for _, t in ipairs(type(Lottery) == "table" and type(Lottery.LAPTOP) == "table" and Lottery.LAPTOP or {}) do
        for _, name in ipairs({ t.normal, t.flash }) do
          local okI, m = pcall(function() return C:id("metatile_labels", name) end)
          local _, _, e = own(tonumber(t.x) or -1, tonumber(t.y) or -1)
          if okI and tonumber(m) and e then add(floor(t.x), floor(t.y), floor(m), 0x07, e, "native") end
        end
      end
    end
    -- Cells by their behaviour (step_callbacks_rse.lua): ash grass to grass (:71-108), Sootopolis Gym's thin ice to
    -- cracked and broken (:300-330), cracked floors to holes (:333-369); TVs on indoor maps, on and off, impassable
    -- (natives_tv.lua:50-70, map.lua:825-828)
    local Coll, MB = b.g3("collision"), b.g3("mb")
    local function is(fname, beh)
      return beh ~= nil and type(Coll) == "table" and type(Coll[fname]) == "function" and Coll[fname](beh) == true
    end
    local tvBeh = type(MB) == "table" and type(MB.id) == "function" and MB.id("TELEVISION") or nil
    local indoor = b.ask(b.g3("dataset"), "isIndoorMapType", false, mdef.mapType) == true
    local fallAsh, fallGrass, lavaGrass = label("Fallarbor_AshGrass"), label("Fallarbor_NormalGrass"), label("Lavaridge_NormalGrass")
    local iceCracked, iceBroken = label("SootopolisGym_Ice_Cracked"), label("SootopolisGym_Ice_Broken")
    local caveCracked = label("Cave_CrackedFloor") or 0x22F
    local caveHole = label("Cave_CrackedFloor_Hole") or 0x206
    local pillarHole = label("Pacifidlog_SkyPillar_CrackedFloor_Hole") or 0x237
    local tvOn, tvOff = label("Building_TV_On"), label("Building_TV_Off")
    local tw = floor(tonumber(lay.trueWidth) or tonumber(lay.width) or 0)
    local th = floor(tonumber(lay.trueHeight) or tonumber(lay.height) or 0)
    for y = 0, th - 1 do
      for x = 0, tw - 1 do
        local m, _, e = own(x, y)
        local beh = m and behaviorOf(pair, m) or nil
        if beh ~= nil then
          if is("isAshGrass", beh) then
            local to = (m == fallAsh) and fallGrass or lavaGrass
            if to then add(x, y, to, passableColl(mdef, pair, to), e, "native") counts.ash = counts.ash + 1 end
          elseif is("isThinIce", beh) then
            if iceCracked then add(x, y, iceCracked, passableColl(mdef, pair, iceCracked), e, "native") end
            if iceBroken then add(x, y, iceBroken, passableColl(mdef, pair, iceBroken), e, "native") end
            counts.ice = counts.ice + 1
          elseif is("isCrackedIce", beh) then
            if iceBroken then add(x, y, iceBroken, passableColl(mdef, pair, iceBroken), e, "native") end
            counts.ice = counts.ice + 1
          elseif is("isCrackedFloor", beh) then
            local to = (m == caveCracked) and caveHole or pillarHole
            add(x, y, to, passableColl(mdef, pair, to), e, "native")
            counts.cracked = counts.cracked + 1
          elseif indoor and tvBeh ~= nil and beh == tvBeh then
            if tvOn then add(x, y, tvOn, 0x07, e, "native") end
            if tvOff then add(x, y, tvOff, 0x07, e, "native") end
            counts.tv = counts.tv + 1
          end
        end
      end
    end
  end

  -- { sites = { { map, x, y, own = { metatile, coll, elev }, states = { { metatile, coll, elev, source } } } },
  -- baked, counts }, or nil out of a game. checkpoint: the export's (yields while the game keeps its frames).
  def("cellSites", function(checkpoint)
    local bundle, data = space(), maps()
    if not (bundle and data) then return nil end
    local ids = {}
    for id in pairs(data) do
      if b.symbol(id) and not Sites.leftOut(id) then ids[#ids + 1] = id end
    end
    table.sort(ids)
    local counts = { maps = 0, sites = 0, states = 0, dropped = 0, offMap = 0, layoutsMissing = 0, layoutsMismatched = 0,
                     truncated = 0, baked = 0, mauville = 0, petalburg = 0, mirage = 0, ash = 0, ice = 0, cracked = 0,
                     tv = 0 }
    local sites = {}
    for _, map in ipairs(ids) do
      local mdef = data[map]
      local lay = type(mdef) == "table" and layoutOf(map, mdef) or nil
      if lay then
        local pair = pairOf(mdef, lay)
        local scan = scanMap(map, false, checkpoint) or { metatiles = {}, layouts = {}, flash = {}, specials = {} }
        if scan.truncated then counts.truncated = counts.truncated + 1 end
        local baked = b.bakedCells(map)
        for _ in pairs(baked) do counts.baked = counts.baked + 1 end
        local own = ownFn(map)
        local cells = {}
        local function add(x, y, mid, coll, elev, source)
          local om, oc = own(x, y)
          if om == nil then
            counts.offMap = counts.offMap + 1
            return
          end
          coll = repaired(mdef, pair, x, y, mid, coll)
          if mid == om and coll == repaired(mdef, pair, x, y, om, oc) then return end
          local k = y * 1024 + x
          local cell = cells[k]
          if not cell then
            cell = { x = x, y = y, states = {}, seen = {} }
            cells[k] = cell
          end
          local sk = mid * 256 + coll
          if not cell.seen[sk] then
            cell.seen[sk] = true
            cell.states[#cell.states + 1] = { metatile = mid, coll = coll, elev = elev, source = source }
          end
        end
        -- scripts' setmetatile rows: every one but those baked in (an every-visit change under no condition)
        for _, m in ipairs(scan.metatiles) do
          if not (Sites.EVERY_VISIT[m.root] and #m.conds == 0) then
            local _, _, e = own(m.x, m.y)
            if e then
              add(m.x, m.y, m.mid, m.imp and 0x07 or passableColl(mdef, pair, m.mid), e, "script")
            else
              counts.offMap = counts.offMap + 1
            end
          end
        end
        -- alternate layouts: their cells that differ from the map's own
        for _, l in ipairs(scan.layouts) do
          if not (Sites.EVERY_VISIT[l.root] and #l.conds == 0) then
            local alt = altLayout(l.id)
            if not alt then
              counts.layoutsMissing = counts.layoutsMissing + 1
            elseif alt.width ~= lay.width or alt.height ~= lay.height then
              counts.layoutsMismatched = counts.layoutsMismatched + 1 -- the engine leaves such a swap undone (ops_a.lua:274-277)
            else
              for i = 1, alt.width * alt.height do
                local cell = alt.cells[i]
                if type(cell) == "table" then
                  local x, y = (i - 1) % alt.width, floor((i - 1) / alt.width)
                  if own(x, y) ~= nil then
                    add(x, y, floor(tonumber(cell.mid) or 0), floor(tonumber(cell.coll) or 0xff), floor(tonumber(cell.elev) or 0), "layout")
                  end
                end
              end
            end
          end
        end
        nativeStates(map, mdef, lay, scan, add, counts, own)
        local keys = {}
        for k in pairs(cells) do keys[#keys + 1] = k end
        table.sort(keys)
        for _, k in ipairs(keys) do
          local cell = cells[k]
          table.sort(cell.states, function(p, q)
            if p.metatile ~= q.metatile then return p.metatile < q.metatile end
            return p.coll < q.coll
          end)
          local states = {}
          for i, s in ipairs(cell.states) do
            if i <= Sites.MAX_STATES then states[i] = s else counts.dropped = counts.dropped + 1 end
          end
          local om, oc, oe = own(cell.x, cell.y)
          sites[#sites + 1] = { map = map, x = cell.x, y = cell.y, own = { metatile = om, coll = oc, elev = oe }, states = states }
          counts.states = counts.states + #states
        end
        if #keys > 0 then counts.maps = counts.maps + 1 end
        if checkpoint then checkpoint() end
      end
    end
    counts.sites = #sites
    return { sites = sites, baked = counts.baked, counts = counts }
  end)
end

return Sites
