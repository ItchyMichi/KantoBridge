-- Kanto Bridge test driver: Emerald's story events and the world changes
-- they make, read from the real engine (the story check, docs/specs/
-- STORY_EVENTS_PLAN.md §2). Driven by tools/e2e/g3_story_check.py. Every
-- scene first registers a fresh empty save slot labelled KB_G3STORY_TEST and
-- makes it active (the engine's slot API, src/core/SaveData.lua), starts a
-- new game straight into the field (Game3:_handleBootAction, as
-- kb_emerald.lua does) and deletes the test slots again at the end.
-- KB_SCENE picks the part:
--
--   scan     a static walk over every map's scripts in the engine's script
--            bundle (Space.bundle: the importer already decoded every
--            script into rows, src/import/gba/extract_scripts.lua): from each
--            map's entry scripts (onTransition, onLoad, onResume,
--            onReturnToField, onDiveWarp, the onFrame and onWarpIntoMap
--            tables), its objects', signs' and coord events' scripts, every
--            reachable row, following goto/call/goto_if/call_if, the
--            standard scripts and trainer battles' event scripts, with the
--            conditions taken on the way (checkflag, compare_var_*,
--            switch/case through VAR_0x8000, specialvar results). Recorded
--            per map: every command that can change the world (setmetatile,
--            setmaplayoutindex with the alternate layout's differing cells,
--            setweather/doweather/resetweather, setflashlevel/animateflash,
--            setstepcallback, specials by name, object visibility and
--            placement, warps, door states), the map header's weather and
--            cave flag, coord weather events. Written to
--            KB_REPORT_DIR/g3_story_scan.json (ROM-derived: it stays in
--            local/); stdout gets counts only.
--
--   sweep    the live check: the cases in KB_CASES (a Lua file the test
--            writes from the scan: per case a map and the story flags and
--            vars that make its entry scripts change the world; KB_MAPS, a
--            comma-separated list, keeps only those maps). Once a client
--            watches (the bridge diffs only then), for each case: the flag
--            store back to the new game's, the case's flags and vars set
--            (Flags.setFlag/setVar on Space.store, persisted by the warp's
--            own deactivate), the tide or Mirage Island forced where the case
--            says, then a warp into the map (Warp.request, no fade: Map.load
--            runs the entry scripts at once; onto its first warp's cell, else
--            the walkable or water cell nearest its middle). Recorded right
--            after the warp and again once the field is idle (onFrame scripts
--            given up to KB_SETTLE_FRAMES; a script still holding the field
--            then is halted): every layout override against the map's own
--            cell (metatile, collision byte and its class, elevation; an
--            override equal to the map's cell marked `same`), the saved and
--            the drawn weather, the flash level, the map the player is on. A
--            case on the map the player is already on hops to another map
--            first. Written to KB_REPORT_DIR/g3_story_sweep.json.
--
--   objects  the same loop over the maps whose objects have hide flags (the
--            Battle Frontier, secret bases and link rooms left out): per map
--            every hide flag set, then every one clear; recorded once idle:
--            the visible live objects (objects.lua's _byId, as the bridge's
--            NPC observer reads them) and each hideable template's flag as it
--            is after the load (temp flags are cleared on every load, entry
--            scripts set or clear some). Written to
--            KB_REPORT_DIR/g3_story_objects.json.
--
-- Markers: KB_BOOTED scene= slot= home= precleaned=, KB_FIELD map=,
-- KB_SCAN maps= roots= rows= world= setmetatile= layouts= weather= flash=
-- specials= truncated= file=, KB_SWEEP_READY cases=, KB_CASE n= map= ok=
-- cells= mids= classed= weather= drawn= flash= current= halted= err=,
-- KB_SWEEP_DONE cases= file=, KB_CLEANED deleted= active=, KB_QUIT code=;
-- KB_FAIL <reason>. Map symbols, ids, counts and booleans only.
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- with the Game (refs/gen1recomp/main.lua:755-759, :1136-1157). Paths cited
-- are refs/gen1recomp's (Gen1Recomp v0.3.85).

io.stdout:setvbuf("no")

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27224
local SCENE = os.getenv("KB_SCENE") or "scan"
local REPORT_DIR = os.getenv("KB_REPORT_DIR")
local EXTRA_SECONDS = tonumber(os.getenv("KB_DRIVER_EXTRA") or "") or 0
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or (600 + EXTRA_SECONDS)
local LABEL = "KB_G3STORY_TEST"
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

-- The field is up and settled: no lock, no warp, no fade, no step.
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
    if getmetatable(v) == ARRAY or (v[1] ~= nil) or next(v) == nil and getmetatable(v) == ARRAY then
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

-- Commands that change the world, and what each one records (operand names).
local WORLD = {
  setmetatile = { "x", "y", "metatile", "impassable" },
  setmaplayoutindex = { "layout" },
  setweather = { "weather" },
  doweather = {},
  resetweather = {},
  setflashlevel = { "level" },
  animateflash = { "level" },
  setstepcallback = { "callback" },
  setdooropen = { "x", "y" },
  setdoorclosed = { "x", "y" },
  setobjectxyperm = { "localId", "x", "y" },
  copyobjectxytoperm = { "localId" },
  setobjectmovementtype = { "localId", "movement" },
  removeobject = { "localId" },
  addobject = { "localId" },
  showobjectat = { "localId", "group", "num" },
  hideobjectat = { "localId", "group", "num" },
  removeobjectat = { "localId", "group", "num" },
  addobjectat = { "localId", "group", "num" },
  setberrytree = { "tree", "berry", "stage" },
  createvobject = { "gfx", "id", "x", "y", "elev", "dir" },
  setwarp = { "group", "num", "warp", "x", "y" },
  setdynamicwarp = { "group", "num", "warp", "x", "y" },
  setdivewarp = { "group", "num", "warp", "x", "y" },
  setholewarp = { "group", "num", "warp", "x", "y" },
  setescapewarp = { "group", "num", "warp", "x", "y" },
  setrespawn = { "heal" },
  moverotatingtileobjects = {},
  turnrotatingtileobjects = {},
  initrotatingtilepuzzle = {},
}
-- Flag and var writes are recorded per map as id lists (what the story sets).
local WRITES = { setflag = "flag", clearflag = "flag", setvar = "var", addvar = "var", subvar = "var" }
-- Specials (by Emerald name) known to change terrain, weather, light or what stands where
-- (natives_field_rse.lua and friends; the research note's table).
local WORLD_SPECIALS = {
  MauvilleGymPressSwitch = "terrain", MauvilleGymSetDefaultBarriers = "terrain", MauvilleGymDeactivatePuzzle = "terrain",
  PetalburgGymSlideOpenRoomDoors = "terrain", PetalburgGymUnlockRoomDoors = "terrain", DrawWholeMapView = "redraw",
  SetSootopolisGymCrackedIceMetatiles = "terrain", TurnOnTVScreen = "terrain", TurnOffTVScreen = "terrain",
  DoPCTurnOnEffect = "terrain", DoPCTurnOffEffect = "terrain", DoLotteryCornerComputerEffect = "terrain",
  EndLotteryCornerComputerEffect = "terrain", MoveElevator = "terrain", EnterSecretBase = "terrain",
  InitSecretBaseDecorationSprites = "objects", SetSecretBaseOwnerGfxId = "objects", DoSecretBasePCTurnOffEffect = "terrain",
  SetDecoration = "terrain", CallBattlePyramidFunction = "terrain", CallTrainerHillFunction = "terrain",
  SetMirageTowerVisibility = "terrain", StartMirageTowerDisintegration = "terrain", StartMirageTowerShake = "screen",
  StartMirageTowerFossilFallAndSink = "screen", UpdateShoalTideFlag = "layout", IsMirageIslandPresent = "layout",
  SetRoute119Weather = "weather", SetRoute123Weather = "weather", Unused_SetWeatherSunny = "weather", WaitWeather = "weather",
  CreateAbnormalWeatherEvent = "weather", GetAbnormalWeatherMapNameAndType = "weather", StartDroughtWeatherBlend = "weather",
  DoOrbEffect = "screen", FadeOutOrbEffect = "screen", SetDeoxysRockPalette = "palette", DoDeoxysRockInteraction = "objects",
  RotatingGate_InitPuzzle = "objects", RotatingGate_InitPuzzleAndGraphics = "objects", SetMewAboveGrass = "objects",
  DestroyMewEmergingGrassSprite = "objects", ShakeCamera = "screen", SpawnCameraObject = "camera", RemoveCameraObject = "camera",
  DoSealedChamberShakingEffect_Long = "screen", DoSealedChamberShakingEffect_Short = "screen",
  TryUpdateRusturfTunnelState = "flags", LoadWallyZigzagoon = "objects", SetSSTidalFlag = "flags", ResetSSTidalFlag = "flags",
  Script_FadeOutMapMusic = nil, DoRayquazaScene = "screen", SetMauvilleOldManObjEventGfx = "objects",
  SetLilycoveLadyGfx = "objects", SetContestTrainerGfxIds = "objects", TryHideBattleTowerReporter = "objects",
  ShouldHideFanClubInterviewer = "objects", FoundBlackGlasses = "flags", SetTrickHouseNuggetFlag = "flags",
  ResetTrickHouseNuggetFlag = "flags",
}
-- Branches that end a script list.
local STOPS = { ["end"] = true, ["return"] = true, ["goto"] = true, gotostd = true, endram = true, returnram = true,
                gotobeatenscript = true, gotopostbattlescript = true, gotonative = true, vgoto = true }
-- Rows whose result a following goto_if/call_if tests (besides checkflag and the compares).
local CHECKS = { checktrainerflag = "trainer", checkitem = "item", checkitemspace = "item", checkitemtype = "item",
                 checkpcitem = "item", checkdecor = "decor", checkdecorspace = "decor", checkpartymove = "move",
                 checkplayergender = "gender", checkmoney = "money", checkcoins = "coins", yesnobox = "answer",
                 multichoice = "answer", multichoicedefault = "answer", multichoicegrid = "answer", random = "random",
                 getpartysize = "party", checkmonmodernfatefulencounter = "fateful" }
local WALK_ROWS = 400000 -- rows visited per map at most (all its roots together)
local PATHS_PER_KEY = 6   -- distinct condition paths followed into one script list
local MAX_DEPTH = 48
local MAX_CONDS = 12      -- conditions kept on one path (the nearest to the command win)

local VAR_RESULT, VAR_0x8000 = 0x800D, 0x8000

-- "flag 0x86E set", "var 0x4050 >= 3", "special IsMirageIslandPresent == 1", ... as compact tables
local COND_OPS = { [0] = "<", [1] = "==", [2] = ">", [3] = "<=", [4] = ">=", [5] = "!=" }
local NEGATE = { [0] = 4, [1] = 5, [2] = 3, [3] = 2, [4] = 0, [5] = 1 }

local function condOf(check, code, negate)
  code = tonumber(code)
  if code == nil or COND_OPS[code] == nil then return { k = "?" } end
  if negate then code = NEGATE[code] end
  if not check then return { k = "result", op = COND_OPS[code] } end
  if check.k == "flag" then
    -- checkflag's result is 1 when set, 0 when not (ops_a.lua's cond table: LT0 EQ1 GT2 LE3 GE4 NE5)
    local set = ({ [0] = false, [1] = true, [2] = nil, [3] = "any", [4] = true, [5] = false })[code]
    if set == "any" then return nil end
    if set == nil then return { k = "never" } end
    return { k = "flag", id = check.id, set = set }
  elseif check.k == "var" then
    return { k = "var", id = check.id, op = COND_OPS[code], value = check.value }
  elseif check.k == "special" then
    return { k = "special", name = check.name, op = COND_OPS[code], value = check.value }
  end
  return { k = check.k, op = COND_OPS[code], value = check.value, id = check.id }
end

local function condSig(conds)
  local parts = {}
  for _, c in ipairs(conds) do
    parts[#parts + 1] = table.concat({ c.k or "", tostring(c.id or c.name or ""), tostring(c.op or ""),
      tostring(c.value or ""), tostring(c.set) }, ",")
  end
  return table.concat(parts, ";")
end

local function withCond(conds, c)
  if c == nil then return conds end
  local out = {}
  local start = math.max(1, #conds - MAX_CONDS + 2)
  for i = start, #conds do out[#out + 1] = conds[i] end
  out[#out + 1] = c
  return out
end

local function scan(game)
  local Space = mod3("scripting.space")
  local B = Space and (Space.bundle or (Space.ensureBundle and Space.ensureBundle(nil)))
  if not (type(B) == "table" and type(B.events) == "table" and type(B.scripts) == "table") then fail("no_bundle") end
  local Opcodes = require("src.core.game3.scripting.opcodes")
  local C = require("src.core.game3.constants").of(GAME)
  local Map = mod3("map")
  local scripts = B.scripts

  local function specialName(id)
    local ok, name = pcall(function() return C:specialName(tonumber(id)) end)
    return ok and name or ("special_" .. tostring(id))
  end
  local function key(target) return Opcodes.key(target) end
  local function list(k)
    local ok, l = pcall(function() return scripts[k] end)
    return ok and type(l) == "table" and l or nil
  end

  -- the alternate layouts setmaplayoutindex can apply (ops_a.lua:243-298's own lookup)
  local layoutFiles
  local function layoutCells(layoutId)
    local Extract = require("src.import.gba.extract_island1")
    local Dataset = require("src.core.game3.dataset")
    local root = Extract.NATIVE_ROOT or ((Extract.CACHE_ROOT or "data/generated/gba") .. "/native")
    if layoutFiles == nil then
      local src = Dataset.cache():read(root .. "/layout_files.lua")
      local chunk = src and load(src, "@layout_files.lua", "t", {})
      local ok, files = false, nil
      if chunk then ok, files = pcall(chunk) end
      layoutFiles = ok and type(files) == "table" and files or false
    end
    local fileKey = layoutFiles and layoutFiles[tonumber(layoutId)]
    local blob = fileKey and Dataset.cache():read(root .. "/layouts/" .. fileKey .. ".mid")
    if not blob then return nil, "no_layout" end
    local ok, decoded = pcall(require("src.import.gba.native_pack").decodeMidLayout, blob)
    if not (ok and decoded) then return nil, "undecodable" end
    return decoded
  end

  local mapIds = {}
  for id in pairs(B.events) do
    if type(id) == "string" then mapIds[#mapIds + 1] = id end
  end
  table.sort(mapIds)

  local report = { game = GAME, maps = {} }
  local totals = { maps = 0, roots = 0, rows = 0, world = 0, setmetatile = 0, layouts = 0, weather = 0, flash = 0,
                   specials = 0, truncated = 0 }

  for _, mapId in ipairs(mapIds) do
    local ev = B.events[mapId]
    local def = game.data and game.data.maps and game.data.maps[mapId]
    local ms = type(ev.mapScripts) == "table" and ev.mapScripts or {}
    local roots = {}
    local function root(kind, k, info)
      if type(k) == "string" or type(k) == "number" then roots[#roots + 1] = { kind = kind, key = key(k), info = info } end
    end
    for _, kind in ipairs({ "onTransition", "onLoad", "onResume", "onReturnToField", "onDiveWarp" }) do
      root(kind, ms[kind])
    end
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
    local coordWeather = {}
    for i, c in ipairs(type(ev.coordEvents) == "table" and ev.coordEvents or {}) do
      if type(c) == "table" then
        if c.scriptKey == nil then
          coordWeather[#coordWeather + 1] = { n = i, trigger = tonumber(c.var) }
        else
          root("coord", c.scriptKey, { n = i, var = tonumber(c.var), value = tonumber(c.value) })
        end
      end
    end

    local m = { roots = #roots, rows = 0, world = array(), writes = { flag = {}, var = {} }, specials = {},
                ops = {}, layouts = {}, header = { weather = def and tonumber(def.weather), cave = def and def.cave or nil },
                coordWeather = array(coordWeather), truncated = false }
    local seenWorld = {}
    local visits = 0

    for _, r in ipairs(roots) do
      local paths = {}
      local function walk(k, conds, depth)
        if depth > MAX_DEPTH then return end
        local l = list(k)
        if not l then return end
        local sig = condSig(conds)
        local p = paths[k]
        if not p then
          p = {}
          paths[k] = p
        end
        if p[sig] then return end
        local n = 0
        for _ in pairs(p) do n = n + 1 end
        if n >= PATHS_PER_KEY then return end
        p[sig] = true
        local cur = conds
        local check -- what the next goto_if/call_if tests
        local alias8000 -- switch: VAR_0x8000 holds a copy of this var
        local resultFrom -- VAR_RESULT was set by this special
        for i, row in ipairs(l) do
          visits = visits + 1
          if visits > WALK_ROWS then
            m.truncated = true
            return
          end
          m.rows = m.rows + 1
          local op = type(row) == "table" and row.op or nil
          if op == nil then break end
          m.ops[op] = (m.ops[op] or 0) + 1
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
            local name = specialName(row[2])
            m.specials[name] = (m.specials[name] or 0) + 1
            if WORLD_SPECIALS[name] then
              local wk = table.concat({ r.kind, k, i, "special", name }, "|")
              if not seenWorld[wk] then
                seenWorld[wk] = true
                m.world[#m.world + 1] = { root = r.kind, rootInfo = r.info, key = k, row = i, op = "special",
                                          name = name, class = WORLD_SPECIALS[name], conds = array(cur) }
              end
            end
          elseif CHECKS[op] then
            check = { k = CHECKS[op], id = tonumber(row[1]) }
          elseif op == "goto_if" or op == "call_if" or op == "vgoto_if" or op == "vcall_if" then
            local c = condOf(check, row.cond or row[1])
            if row.target ~= nil and (c == nil or c.k ~= "never") then walk(key(row.target), withCond(cur, c), depth + 1) end
            if op == "goto_if" or op == "vgoto_if" then cur = withCond(cur, condOf(check, row.cond or row[1], true)) end
          elseif op == "goto" or op == "call" or op == "vgoto" or op == "vcall" then
            if row.target ~= nil then walk(key(row.target), cur, depth + 1) end
          elseif op == "callstd" or op == "gotostd" then
            walk("std:" .. tostring(row.std or row[1]), cur, depth + 1)
          elseif op == "callstd_if" or op == "gotostd_if" then
            local c = condOf(check, row[1])
            walk("std:" .. tostring(row[2]), withCond(cur, c), depth + 1)
            if op == "gotostd_if" then cur = withCond(cur, condOf(check, row[1], true)) end
          elseif op == "trainerbattle" then
            if row.eventScript ~= nil then walk(key(row.eventScript), cur, depth + 1) end
          elseif op == "special" then
            local name = specialName(row.id or row[1])
            m.specials[name] = (m.specials[name] or 0) + 1
            if WORLD_SPECIALS[name] then
              local wk = table.concat({ r.kind, k, i, "special", name }, "|")
              if not seenWorld[wk] then
                seenWorld[wk] = true
                m.world[#m.world + 1] = { root = r.kind, rootInfo = r.info, key = k, row = i, op = "special",
                                          name = name, class = WORLD_SPECIALS[name], conds = array(cur) }
              end
            end
          elseif WRITES[op] then
            local id = tonumber(row[1])
            if id then
              local w = m.writes[WRITES[op]]
              w[id] = (w[id] or 0) + 1
            end
          end
          if WORLD[op] then
            local args = {}
            for j, name in ipairs(WORLD[op]) do args[name] = tonumber(row[j]) end
            local wk = table.concat({ r.kind, k, i, op, condSig(cur) }, "|")
            if not seenWorld[wk] then
              seenWorld[wk] = true
              m.world[#m.world + 1] = { root = r.kind, rootInfo = r.info, key = k, row = i, op = op, args = args,
                                        conds = array(cur) }
              if op == "setmaplayoutindex" and args.layout then
                local lid = args.layout
                if lid >= 0x4000 then
                  m.layouts[tostring(lid)] = { var = true }
                elseif not m.layouts[tostring(lid)] then
                  local base = Map and Map.ensureMidLayout and Map.ensureMidLayout(game, mapId, def) or nil
                  local alt, why = layoutCells(lid)
                  local entry = { found = alt ~= nil, why = why }
                  if alt and base then
                    entry.sizeOk = (alt.width == base.width and alt.height == base.height)
                    local diff = array()
                    if entry.sizeOk then
                      for ci = 1, alt.width * alt.height do
                        local a, bcell = alt.cells[ci], base.cells[ci]
                        if a and bcell and (a.mid ~= bcell.mid or a.coll ~= bcell.coll or a.elev ~= bcell.elev) then
                          diff[#diff + 1] = { x = (ci - 1) % alt.width, y = math.floor((ci - 1) / alt.width),
                                              base = { bcell.mid, bcell.coll, bcell.elev }, alt = { a.mid, a.coll, a.elev } }
                        end
                      end
                    end
                    entry.cells = diff
                    entry.changed = #diff
                  end
                  m.layouts[tostring(lid)] = entry
                end
              end
            end
          end
          if STOPS[op] then break end
        end
      end
      walk(r.key, {}, 0)
    end

    -- the map's counts
    local counts = {}
    for _, w in ipairs(m.world) do
      counts[w.op == "special" and ("special:" .. w.class) or w.op] = (counts[w.op == "special" and ("special:" .. w.class) or w.op] or 0) + 1
      if w.op == "setmetatile" then totals.setmetatile = totals.setmetatile + 1 end
      if w.op == "setmaplayoutindex" then totals.layouts = totals.layouts + 1 end
      if w.op == "setweather" or w.op == "doweather" or w.op == "resetweather" or (w.op == "special" and w.class == "weather") then
        totals.weather = totals.weather + 1
      end
      if w.op == "setflashlevel" or w.op == "animateflash" then totals.flash = totals.flash + 1 end
      if w.op == "special" then totals.specials = totals.specials + 1 end
    end
    m.counts = counts
    -- id lists as arrays of { id, n }
    for _, kind in ipairs({ "flag", "var" }) do
      local out = array()
      for id, n in pairs(m.writes[kind]) do out[#out + 1] = { id = id, n = n } end
      table.sort(out, function(a, b) return a.id < b.id end)
      m.writes[kind] = out
    end
    totals.maps = totals.maps + 1
    totals.roots = totals.roots + m.roots
    totals.rows = totals.rows + m.rows
    totals.world = totals.world + #m.world
    if m.truncated then totals.truncated = totals.truncated + 1 end
    report.maps[mapId] = m
  end
  report.totals = totals
  -- Emerald's own names for the flags and vars the conditions and writes name (Flags.forVersion, flags.lua:150-213)
  local okF, E = pcall(function() return require("src.core.game3.scripting.flags").forVersion(GAME) end)
  local names = { flags = {}, vars = {} }
  local function nameIt(kind, id)
    id = tonumber(id)
    if not (okF and type(E) == "table" and id) then return end
    local t = kind == "flags" and E.NAMES or E.VAR_NAMES
    local n = type(t) == "table" and t[id] or nil
    if type(n) == "string" then names[kind][tostring(id)] = n end
  end
  for _, m in pairs(report.maps) do
    for _, w in ipairs(m.world) do
      for _, c in ipairs(w.conds or {}) do
        if c.k == "flag" then nameIt("flags", c.id) elseif c.k == "var" then nameIt("vars", c.id) end
      end
      if w.rootInfo and w.rootInfo.var then nameIt("vars", w.rootInfo.var) end
    end
    for _, f in ipairs(m.writes.flag) do nameIt("flags", f.id) end
    for _, v in ipairs(m.writes.var) do nameIt("vars", v.id) end
  end
  report.names = names
  local path = writeJson("g3_story_scan.json", report)
  print(("KB_SCAN maps=%d roots=%d rows=%d world=%d setmetatile=%d layouts=%d weather=%d flash=%d specials=%d truncated=%d file=%s")
    :format(totals.maps, totals.roots, totals.rows, totals.world, totals.setmetatile, totals.layouts, totals.weather,
      totals.flash, totals.specials, totals.truncated, word(path ~= nil)))
end

-- ---- the live sweep

local NEUTRAL = "EM_LITTLEROOT_TOWN"
local SETTLE_MIN = tonumber(os.getenv("KB_SETTLE_MIN") or "") or 45     -- frames after a warp before "settled"
local SETTLE_FRAMES = tonumber(os.getenv("KB_SETTLE_FRAMES") or "") or 360 -- then a script still running is halted
local TAIL_FRAMES = 40 -- frames for the bridge to send what changed, before the next case

local function classOf(coll)
  local P = package.loaded["src.world.gen2.Permissions"]
  if not P then
    local ok, mod = pcall(require, "src.world.gen2.Permissions")
    P = ok and mod or nil
  end
  coll = tonumber(coll) or 0xff
  if coll == 0xff then return "void" end
  if P and P.isWater and P.isWater(coll) then return "water" end
  if P and P.isLedge and P.isLedge(coll) then return "ledge" end
  if P and P.isWalkable then return P.isWalkable(coll) and "walk" or "solid" end
  return (coll ~= 0x07 and coll ~= 0x29) and "walk" or "solid"
end

-- The current map's layout overrides that differ from its own cells.
local function layoutState(game)
  local Map = mod3("map")
  local id = Map and Map.current
  local def = Map and Map.currentDef and Map.currentDef() or nil
  local lay = def and def.midLayout
  local out = { map = id, cells = array(), mids = 0, collOnly = 0, classChanged = 0, composed = false }
  if type(lay) ~= "table" then return out end
  out.composed = def._pyramidBase ~= nil or def._hillBase ~= nil
  local w = math.floor(tonumber(lay.width) or 0)
  local tw = math.floor(tonumber(lay.trueWidth) or w)
  local th = math.floor(tonumber(lay.trueHeight) or tonumber(lay.height) or 0)
  local keys = {}
  for k in pairs(type(lay.overrides) == "table" and lay.overrides or {}) do
    if tonumber(k) then keys[#keys + 1] = tonumber(k) end
  end
  table.sort(keys)
  for _, k in ipairs(keys) do
    local ov = lay.overrides[k]
    local x, y = k % 1024, math.floor(k / 1024)
    local base = (x < tw and y < th and w > 0) and lay.cells[y * w + x + 1] or nil
    if type(ov) == "table" and type(base) == "table" then
      local bm, bc, be = tonumber(base.mid), tonumber(base.coll), tonumber(base.elev)
      local lm, lc, le = tonumber(ov.mid), tonumber(ov.coll), tonumber(ov.elev)
      local bcl, lcl = classOf(bc), classOf(lc)
      local differs = bm ~= lm or bc ~= lc or be ~= le
      -- every override, also one equal to the map's own cell (a cell put back), so the test knows its own metatile
      out.cells[#out.cells + 1] = { x = x, y = y, b = { bm, bc, be }, l = { lm, lc, le }, bc = bcl, lc = lcl,
                                    same = not differs }
      if differs then
        if bm ~= lm then out.mids = out.mids + 1 else out.collOnly = out.collOnly + 1 end
        if bcl ~= lcl then out.classChanged = out.classChanged + 1 end
      end
    end
  end
  return out
end

local function weatherState()
  local Runtime = mod3("runtime")
  local sess = Runtime and Runtime.getSession and Runtime.getSession()
  local R = mod3("field_weather_rse")
  local S = R and R.state
  local FV = mod3("field_view")
  return {
    saved = sess and tonumber(sess.savedWeather) or nil,
    stage = sess and tonumber(sess.weatherCycleStage) or nil,
    drawn = S and tonumber(S.currWeather) or nil,
    next = S and tonumber(S.nextWeather) or nil,
    abnormal = S and S.abnormal and true or false,
    flash = FV and tonumber(FV.flashLevel) or nil,
    radius = FV and FV.flashRadius and FV.flashRadius() or nil,
  }
end

local function storeCopy(store)
  local s = { flags = {}, vars = {} }
  for k, v in pairs(type(store) == "table" and store.flags or {}) do s.flags[k] = v end
  for k, v in pairs(type(store) == "table" and store.vars or {}) do s.vars[k] = v end
  return s
end

local function storeRestore(store, s)
  if type(store) ~= "table" then return end
  store.flags = store.flags or {}
  store.vars = store.vars or {}
  for k in pairs(store.flags) do store.flags[k] = nil end
  for k, v in pairs(s.flags) do store.flags[k] = v end
  for k in pairs(store.vars) do store.vars[k] = nil end
  for k, v in pairs(s.vars) do store.vars[k] = v end
end

-- Where a warp into `map` puts the player: its first warp's cell, else (a sea route has none) the walkable cell of
-- its own layout nearest the middle, else the nearest water cell.
local function arrivalCell(game, map)
  local def = game.data and game.data.maps and game.data.maps[map]
  local w = def and def.warps and def.warps[1]
  if w and tonumber(w.x) and tonumber(w.y) then return tonumber(w.x), tonumber(w.y) end
  local Map = mod3("map")
  local lay = def and Map and Map.ensureMidLayout and Map.ensureMidLayout(game, map, def) or nil
  if type(lay) ~= "table" then return nil end
  local tw = math.floor(tonumber(lay.trueWidth) or tonumber(lay.width) or 0)
  local th = math.floor(tonumber(lay.trueHeight) or tonumber(lay.height) or 0)
  local cx, cy = math.floor(tw / 2), math.floor(th / 2)
  local best = {}
  for y = 0, th - 1 do
    for x = 0, tw - 1 do
      local cell = lay.cells[y * lay.width + x + 1]
      local class = type(cell) == "table" and classOf(cell.coll) or "void"
      if class == "walk" or class == "water" then
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

local OBJ_SKIP = { "EM_BATTLE_FRONTIER_", "EM_BATTLE_PYRAMID_", "EM_TRAINER_HILL_", "EM_SECRET_BASE_", "EM_UNION_ROOM",
                   "EM_RECORD_CORNER", "EM_TRADE_CENTER", "EM_BATTLE_COLOSSEUM", "EM_CONTEST_HALL", "EM_LINK_CONTEST",
                   "EM_SS_TIDAL", "EM_INSIDE_OF_TRUCK" }

-- A map's object templates whose hide flag decides their visibility ({ lid, flag }), as the bridge's story part reads
-- them (Space.bundle.events[map].objects; flag 0 or 0xFFFF: always visible; the camera and seam objects left out).
local function hideable(map)
  local Space = mod3("scripting.space")
  local ev = Space and Space.bundle and Space.bundle.events and Space.bundle.events[map]
  local out = array()
  for _, d in ipairs(ev and (ev.objects or ev.objectEvents) or {}) do
    local lid, flag = tonumber(d.localId or d.index), tonumber(d.flag or d.flagId)
    if lid and lid > 0 and lid < 127 and flag and flag ~= 0 and flag ~= 0xFFFF then
      out[#out + 1] = { lid = lid, flag = flag }
    end
  end
  return out
end

-- Two cases per map with hideable objects: every hide flag set, then every one clear.
local function objectCases()
  local Space = mod3("scripting.space")
  local ids = {}
  for id in pairs(Space and Space.bundle and Space.bundle.events or {}) do
    local skip = type(id) ~= "string"
    for _, p in ipairs(OBJ_SKIP) do
      if not skip and id:sub(1, #p) == p then skip = true end
    end
    if not skip and #hideable(id) > 0 then ids[#ids + 1] = id end
  end
  table.sort(ids)
  local cases = {}
  for _, id in ipairs(ids) do
    for _, mode in ipairs({ "set", "clear" }) do
      cases[#cases + 1] = { n = #cases + 1, map = id, label = "hide flags " .. mode, objects = mode }
    end
  end
  return cases
end

local function newSweep(game)
  local cases
  if SCENE == "objects" then
    cases = objectCases()
  else
    local ok, list = pcall(dofile, os.getenv("KB_CASES") or "")
    if not (ok and type(list) == "table") then fail("no_cases") end
    cases = list
  end
  local only = os.getenv("KB_MAPS")
  if only and only ~= "" then
    local keep, want = {}, {}
    for m in only:gmatch("[^,]+") do want[m] = true end
    for _, c in ipairs(cases) do
      if want[c.map] then keep[#keep + 1] = c end
    end
    cases = keep
  end
  return { cases = cases, i = 0, results = array(), baseline = nil, patches = {} }
end

-- The hideable templates with their flags' values now (after the map's load and scripts: temp flags are cleared on
-- every load, and entry scripts set or clear some).
local function hideableNow(map)
  local Space = mod3("scripting.space")
  local Flags = require("src.core.game3.scripting.flags")
  local out = array()
  for _, o in ipairs(hideable(map)) do
    out[#out + 1] = { lid = o.lid, flag = o.flag, set = Flags.getFlag(Space.store, nil, o.flag) == true }
  end
  return out
end

-- The current map's visible live objects (objects.lua's _byId, as the bridge's NPC observer reads them).
local function liveObjects()
  local Objects = mod3("objects")
  local out = array()
  for lid, eo in pairs(type(Objects) == "table" and type(Objects._byId) == "table" and Objects._byId or {}) do
    lid = tonumber(lid)
    if type(eo) == "table" and lid and lid ~= 127 and lid < 0xC0 and eo.visible ~= false and not eo.hidden
        and not eo.invisible then
      out[#out + 1] = lid
    end
  end
  table.sort(out)
  return out
end

local function applyCase(sw, c)
  local Space = mod3("scripting.space")
  local Flags = require("src.core.game3.scripting.flags")
  storeRestore(Space.store, sw.baseline)
  for id, on in pairs(c.flags or {}) do Flags.setFlag(Space.store, nil, tonumber(id), on == true) end
  local ctx = Space.vm and Space.vm.ctx
  for id, value in pairs(c.vars or {}) do Flags.setVar(Space.store, ctx, tonumber(id), tonumber(value)) end
  if c.objects then
    for _, o in ipairs(hideable(c.map)) do Flags.setFlag(Space.store, nil, o.flag, c.objects == "set") end
  end
  local FR = require("src.core.game3.scripting.natives_field_rse")
  if c.tide ~= nil then
    sw.patches.tide = sw.patches.tide or FR.updateShoalTideFlag
    FR.updateShoalTideFlag = function() return true end
  end
  if c.mirage ~= nil then
    sw.patches.mirage = sw.patches.mirage or FR.isMirageIslandPresent
    FR.isMirageIslandPresent = function() return c.mirage == true end
  end
end

local function unpatch(sw)
  local FR = package.loaded["src.core.game3.scripting.natives_field_rse"]
  if FR and sw.patches.tide then FR.updateShoalTideFlag = sw.patches.tide end
  if FR and sw.patches.mirage then FR.isMirageIslandPresent = sw.patches.mirage end
  sw.patches = {}
end

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local sw, c, frames, rec
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
        print(("KB_BOOTED scene=%s slot=%d home=%d precleaned=%d"):format(word(SCENE), num(slot), num(homeRef),
          precleaned))
        local ok, err = pcall(game._handleBootAction, game, { action = "new_game", name = "TEST", gender = 0 })
        if not ok then fail("new_game " .. word(err):sub(1, 40)) end
        phase = "newgame"
      end
    elseif phase == "newgame" then
      if fieldReady(game) then
        local Map = mod3("map")
        print(("KB_FIELD map=%s"):format(word(Map and Map.current)))
        if SCENE == "scan" then
          local ok, err = pcall(scan, game)
          if not ok then fail("scan " .. word(err):sub(1, 80)) end
        elseif SCENE == "sweep" or SCENE == "objects" then
          sw = newSweep(game)
          phase = "sweep_client"
        else
          fail("unknown_scene")
        end
        if phase ~= "sweep_client" then
          local deleted, now = cleanup(homeRef)
          print(("KB_CLEANED deleted=%d active=%d"):format(deleted, num(now)))
          print("KB_QUIT code=0")
          return
        end
      end
    elseif phase == "sweep_client" then
      -- the bridge diffs only while a client watches
      if (tonumber(status.sessions) or 0) >= 1 and fieldReady(game) then
        frames = (frames or 0) + 1
        if frames >= 120 then
          sw.baseline = storeCopy(mod3("scripting.space").store)
          print(("KB_SWEEP_READY cases=%d"):format(#sw.cases))
          phase = "case_start"
        end
      end
    elseif phase == "case_start" then
      sw.i = sw.i + 1
      c = sw.cases[sw.i]
      if not c then
        local path = writeJson(SCENE == "objects" and "g3_story_objects.json" or "g3_story_sweep.json",
          { game = GAME, cases = sw.results })
        print(("KB_SWEEP_DONE cases=%d file=%s"):format(#sw.results, word(path ~= nil)))
        phase = "sweep_done"
      else
        local Map = mod3("map")
        frames = 0
        if Map and Map.current == c.map then
          -- a hop to another map first (Route 101 when the case is the neutral town itself)
          local hop = c.map == NEUTRAL and "EM_ROUTE101" or NEUTRAL
          local x, y = arrivalCell(game, hop)
          storeRestore(mod3("scripting.space").store, sw.baseline)
          local ok = x and pcall(mod3("warp").request, nil, game, hop, x, y + (hop == NEUTRAL and 1 or 0), "down",
            { fade = false })
          if not ok then fail("neutral_warp") end
          phase = "case_hop"
        else
          phase = "case_go"
        end
      end
    elseif phase == "case_hop" then
      frames = frames + 1
      if frames >= 300 then haltScripts() end
      if fieldReady(game) and frames >= 30 then
        frames = 0
        phase = "case_go"
      end
    elseif phase == "case_go" then
      rec = { n = c.n, map = c.map, label = c.label, ok = false }
      local x, y = arrivalCell(game, c.map)
      if not x then
        rec.err = "no_warp"
        phase = "case_report"
      else
        applyCase(sw, c)
        local ok, res, err = pcall(mod3("warp").request, nil, game, c.map, x, y, "down", { fade = false })
        if not ok or res == nil and err ~= nil then
          rec.err = word(ok and err or res):sub(1, 60)
          unpatch(sw)
          phase = "case_report"
        else
          rec.ok = true
          rec.entry = layoutState(game)
          rec.entryWeather = weatherState()
          frames = 0
          phase = "case_settle"
        end
      end
    elseif phase == "case_settle" then
      frames = frames + 1
      local Space = mod3("scripting.space")
      local running = Space and Space.vm and Space.vm:isRunning()
      if frames >= SETTLE_FRAMES and not rec.halted then
        rec.halted = haltScripts()
        rec.haltedAt = frames
      end
      if frames >= SETTLE_MIN and fieldReady(game) and not running then
        rec.settled = layoutState(game)
        rec.weather = weatherState()
        if c.objects then rec.objects = { mode = c.objects, live = liveObjects(), hideable = hideableNow(c.map) } end
        rec.frames = frames
        unpatch(sw)
        frames = 0
        phase = "case_tail"
      elseif frames >= SETTLE_FRAMES + 240 then
        rec.settled = layoutState(game)
        rec.weather = weatherState()
        if c.objects then rec.objects = { mode = c.objects, live = liveObjects(), hideable = hideableNow(c.map) } end
        rec.frames = frames
        rec.stuck = true
        haltScripts()
        unpatch(sw)
        frames = 0
        phase = "case_tail"
      end
    elseif phase == "case_tail" then
      frames = frames + 1
      if frames >= TAIL_FRAMES then phase = "case_report" end
    elseif phase == "case_report" then
      local s = rec.settled or {}
      local wth = rec.weather or {}
      local changed = 0
      for _, cell in ipairs(s.cells or {}) do
        if not cell.same then changed = changed + 1 end
      end
      print(("KB_CASE n=%d map=%s ok=%s cells=%d mids=%d classed=%d weather=%s drawn=%s flash=%s current=%s halted=%s err=%s")
        :format(c.n or sw.i, word(c.map), word(rec.ok), changed, s.mids or 0, s.classChanged or 0, word(wth.saved),
          word(wth.drawn), word(wth.flash), word(s.map), word(rec.halted or false), word(rec.err or "none")))
      sw.results[#sw.results + 1] = rec
      phase = "case_start"
    elseif phase == "sweep_done" then
      if status.quitRequested or love.timer.getTime() - started > SECONDS_LIMIT - 5 then
        local deleted, now = cleanup(homeRef)
        print(("KB_CLEANED deleted=%d active=%d"):format(deleted, num(now)))
        print(("KB_QUIT code=%s"):format(word(status.quitCode or 0)))
        return
      end
    end
    if love.timer.getTime() - started > SECONDS_LIMIT then
      fail("timeout phase=" .. phase)
    end
    game = coroutine.yield() or game
  end
end
