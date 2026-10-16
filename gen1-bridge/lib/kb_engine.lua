-- kb_engine: the engine adapter loader (docs/specs/MULTI_GAME_PLAN.md §4.3,
-- Appendix B; MG2, decision D1). Gen1Recomp runs one of three engines per
-- boot -- `gen1` (Red, Blue, Yellow), `gs`/`crystal` (Gold, Silver, Crystal)
-- and `game3` (FireRed, LeafGreen, Ruby, Sapphire, Emerald) -- and every
-- engine's internals differ, so the one file that touches them is an
-- **adapter per engine**, lib/engine/<engine>.lua, and this loader picks the
-- adapter for the running game's engine (GameVersion.engine,
-- src/core/GameVersion.lua:236) and hands the core (main.lua, the observers
-- and acts) its table. The core calls the adapter's functions, grouped as
-- Engine.GROUPS lists them, and never requires an engine module itself.
--
-- An engine without an adapter gets a **stub** (MG9: `gs` and `crystal` have
-- lib/engine/gen2.lua now, docs/specs/GEN2_BRIDGE_PLAN.md):
-- a table that answers everything the core calls without touching the
-- engine (no game, no save, no steps; every hook wrapper passes through), so
-- the mod loads, listens, and answers each hello with hello.reject "game"
-- (kb_session: engine.hasAdapter() is false), which tells a client why.
-- The stub is also the base an adapter can grow from (MG7,
-- docs/specs/GEN3_BRIDGE_PLAN.md §2): Engine.stub hands it out, the adapter
-- overrides group by group, and every name it hasn't written yet keeps
-- answering "no game" with the right type, which matters because the core
-- calls many optional functions unguarded every step.
--
-- The pin lives here: Engine.CHECKED is the Gen1Recomp release the adapters'
-- citations were checked against (tools/pin_check.py re-stamps them on a pin
-- change; tools/tests/test_pins.py keeps every copy of the version in step),
-- and Engine.TESTED the release hello.ok.tested names.

local Engine = {}

-- The engine release whose internals the adapters were checked against, and
-- the one gen1-bridge is tested with (M10: hello.ok.tested; a connection to
-- a different engine gets one engine.untested warning).
Engine.CHECKED = "0.3.85"
Engine.TESTED = Engine.CHECKED

-- Gen1Recomp's engine ids (GameVersion.lua:236: "gen1" | "gs" | "crystal" |
-- "game3") -> the adapter file; an id outside the table has no adapter yet.
Engine.ADAPTERS = {
  gen1 = "lib/engine/gen1.lua",
  -- MG7: FireRed, LeafGreen, Ruby, Sapphire and Emerald's engine (Emerald first, GEN3_BRIDGE_PLAN.md);
  -- the entry loads its parts from lib/engine/game3/
  game3 = "lib/engine/game3.lua",
  -- MG9: Gold, Silver and Crystal's engine, one adapter for both ids (Crystal first, GEN2_BRIDGE_PLAN.md);
  -- the entry loads its parts from lib/engine/gen2/ and the shared ones from lib/engine/common/
  gs = "lib/engine/gen2.lua",
  crystal = "lib/engine/gen2.lua",
}

-- The adapter interface (MULTI_GAME_PLAN Appendix B), group by group: the
-- function names the core calls. An adapter implements a group whole or
-- reports it absent; the core treats an absent optional group as "not
-- supported". LINK_GROUPS are the ones a game needs to be followed at all.
Engine.GROUPS = {
  boot = { "setGame", "game", "takeOver", "version", "checkPin", "checkRequired", "gameVersion", "engineId",
           "family", "hasAdapter", "logicStep", "save", "top", "overworld", "classifyState", "darkMaps",
           "badgeItems", "observe", "npcs", "emote", "unionRoomPatched", "unionRoomOff",
           -- MG7: the save facts the readers want, Gen 1-shaped (gen1: the save itself); save() stays the
           -- object whose identity kb_save and kb_pairing compare with the save events' payloads
           "saveView" },
  interaction = { "interactedFacts", "cellInMap", "counterCell" },
  ui = { "uiClass", "uiStack", "uiOpenFacts", "uiCursor", "uiItems", "uiMoneyFooter", "uiSetIndex", "uiSetQty",
         "uiSetGlyphs",
         -- MG7 (G3B): the mirror's frame size, the engine's screen (Gen 1 160x144, Gen 3 240x160)
         "screenSize" },
  movement = { "setFacing", "buttonDown", "playerVerdict", "warpDeparture", "displayPinned", "pinDisplay",
               -- MG7: an engine without the movement.collision hook (Gen 3) hands its verdicts over per step
               "takeVerdicts" },
  party = { "bridgeToken", "nextSerial", "dexOf", "speciesOfDex", "moveNumOf", "moveIdOfNum", "trainerTeam",
            "battlePending", "battleSent", "battleStale", "trainerBeforeBattle", "battleFinish", "ops", "opSent",
            "opSynced", "opDone", "excludedUids", "scriptCommand", "gifts", "giftSent", "giftSynced", "giftDone",
            "queueHeal", "releaseHeals", "takeHeals", "uidOf", "partyUids", "adoptUids", "partySafe",
            "applyParty", "bridgeCounts" },
  items = { "attachBridge", "encounterRoll", "encounterFishing", "fishPushed", "nativeBattle", "encounterCounts",
            "inGame", "applyDex", "bagCounts", "bagRemove", "tmWatchStep", "itemUse", "takeItemUses", "itemCounts",
            -- MG6 (one trainer economy): the key-item predicate, the catalogue's item table, bag.set
            "isKeyItem", "itemTable", "itemClass", "tmMove", "bagLimits", "bagSet" },
  saving = { "nurseHealed", "armCenterSave", "centerSaveArmed", "centerSaveStep", "savingReason", "queueSave",
             "takeSave", "queuedSaveSerial", "saveSlot", "pcItems", "pcPending", "pcSent", "pcClose", "pcHeld",
             "pcCounts" },
  battles = { "worldTalk", "directStepped", "directPushed", "nativeCaught", "releaseCatches", "daycareStep",
              "daycareUid", "partyNicks", "queueRename", "takeRenames", "contentCounts" },
  story = { "capturePristine", "pristineBlocks", "pristineCaptured", "pristineBlockAt", "storyMap", "liveBlocks",
            "liveBlockChanges", "cutRecords", "cutSwaps", "swapField", "storyToggles", "fieldAction",
            -- MG7 (G3C): Gen 3's runtime terrain, the current map's changed cells (gen1: none)
            "storyCells", "cellBase",
            -- MG9 (G2C): an adapter's own swap sites (Gen 2's); gen1's come from swapField
            "swapSites" },
  sessions = { "slotState", "resolveActiveSlot", "slotRegistered", "slotHasSave", "labelSlot", "createSlot",
               "deleteSlot", "activateSlot", "playthrough", "titleMenuOpen", "switchGateOpen", "switchSave",
               "toTitle", "dropPlaythrough" },
  reliability = { "metaOf", "persistent", "runHidden", "hiddenStats" },
  export = { "openDataset", "datasetSource", "liveSource", "worldShowing", "liveSourceFromGame", "mapQuery",
             -- MG7 (G3D): an adapter that builds its family's own resources (Gen 3's g3* kinds)
             "exportResources",
             -- SE2 (STORY_EVENTS_PLAN.md): Gen 3's changeable cells, the g3sites resource
             "cellSites",
             "isOutside", "tileProbe", "shoreTiles", "cutTreeTiles", "spinnerTiles", "animatedTiles",
             "borderBlockFor", "voidFill", "modDataFile", "animationImages", "computeNeighbors", "paletteLookup",
             "imagePng" },
  routes = { "inventoryCount", "writeTrainerParty", "scriptBattleResult", "blackout" },
  region = { "holdAway", "releaseAway", "awayHeld", "awayMarker", "startFromTitle", "progressMarkers" },
}
-- the groups a game needs to be followed (link); the rest are optional
Engine.LINK_GROUPS = { "boot", "interaction", "ui", "movement", "story", "saving", "sessions", "reliability" }

-- The engine id of the running game: GameVersion.engine(id) is
-- info(id).engine or "gen1" (GameVersion.lua:236-238, info :263-265 with the
-- current game for a nil id); an engine without the function is the one
-- engine there was.
function Engine.engineId(requireFn)
  if type(requireFn) ~= "function" then return "gen1" end
  local ok, GameVersion = pcall(requireFn, "src.core.GameVersion")
  if ok and type(GameVersion) == "table" and type(GameVersion.engine) == "function" then
    local okId, id = pcall(GameVersion.engine)
    if okId and type(id) == "string" and id:find("^[a-z][a-z0-9_]*$") then return id end
  end
  return "gen1"
end

-- The groups of Engine.GROUPS an adapter table lacks entirely (none of the
-- group's functions present as its own fields), sorted; the stub lacks every
-- one (its functions only stand in for an engine that isn't there), and so
-- does an adapter grown from the stub for every group whose functions are all
-- still the stub's (its `_stubbed` set names them).
function Engine.missingGroups(adapter)
  local out = {}
  local isStub = type(adapter) ~= "table" or (type(rawget(adapter, "hasAdapter")) == "function" and adapter.hasAdapter() == false)
  local stubbed = type(adapter) == "table" and rawget(adapter, "_stubbed") or nil
  if type(stubbed) ~= "table" then stubbed = {} end
  for name, fns in pairs(Engine.GROUPS) do
    local any = false
    if not isStub then
      for _, fn in ipairs(fns) do
        if type(rawget(adapter, fn)) == "function" and not stubbed[fn] then any = true break end
      end
    end
    if not any then out[#out + 1] = name end
  end
  table.sort(out)
  return out
end

-- The stub for an engine without an adapter: every function the core calls
-- answers as an engine with no game would, and every hook wrapper calls
-- next. Anything not named here is a function returning nothing (the
-- metatable), so a new call site never throws on a stub.
local function stub(opts, id, requireFn)
  local game
  local function facts() return { inGame = false, top = "none", topName = "None" } end
  local function empty() return {} end
  local function nothing() return nil end
  local s = {
    hasAdapter = function() return false end,
    engineId = function() return id end,
    family = nothing,
    setGame = function(g) if g ~= nil then game = g end end,
    game = function() return game end,
    takeOver = function() return false end,
    version = function()
      local ok, Version = pcall(requireFn, "src.core.Version")
      if not (ok and type(Version) == "table") then return nil end
      return tostring(Version.engine), Version.modApi
    end,
    checkPin = function() return true end,
    checkRequired = empty,
    gameVersion = function()
      local ok, GameVersion = pcall(requireFn, "src.core.GameVersion")
      local v = ok and type(GameVersion) == "table" and type(GameVersion.get) == "function" and GameVersion.get()
      return type(v) == "string" and v or nil
    end,
    unionRoomPatched = function() return false end,
    unionRoomOff = function() return false end,
    logicStep = function() return 0 end,
    save = nothing, saveView = nothing, top = nothing, overworld = nothing, npcs = nothing, emote = nothing,
    takeVerdicts = empty,
    classifyState = function() return "none", "None" end,
    observe = facts, darkMaps = empty, badgeItems = empty,
    displayPinned = function() return true end,
    pinDisplay = function() return 0 end,
    inGame = function() return false end,
    playthrough = function() return "title" end,
    slotState = function() return { api = false, cart = false } end,
    saveSlot = function() return 0 end,
    pcHeld = function() return false end,
    pcPending = nothing,
    uiStack = empty, uiOpenFacts = nothing,
    screenSize = function() return 160, 144 end,
    storyCells = nothing, cellBase = nothing, swapSites = nothing, cellSites = nothing,
    encounterCounts = empty, itemCounts = empty, bridgeCounts = empty, contentCounts = empty, hiddenStats = empty,
    pcCounts = empty, bagCounts = empty, dexCounts = empty,
    -- lists and counts the acts read every step
    gifts = empty, ops = empty, takeItemUses = empty, takeRenames = empty, partyUids = empty, excludedUids = empty,
    daycareUids = empty,
    partyNicks = empty, animatedTiles = empty, animationImages = empty, liveBlocks = empty, liveBlockChanges = empty,
    storyToggles = empty, cutRecords = empty, cutSwaps = empty, pristineBlocks = empty,
    releaseCatches = function() return 0 end,
    takeHeals = function() return 0 end,
    nextSerial = function() return 0 end,
    bridgeToken = function(token) return token or "x" end,
    inventoryCount = function() return 0 end,
    pristineCaptured = function() return false end,
    capturePristine = function() return 0 end,
    runHidden = nothing,
    -- hook wrappers: the engine's own behaviour, untouched (MG9: Gen 2 hands script.command a fifth
    -- argument, the decoded op, and encounter.fishing a context after the pool; both go on as they came)
    scriptCommand = function(nextFn, ctx, name, args, _bridge, cmd) return nextFn(ctx, name, args, cmd) end,
    trainerBeforeBattle = function(nextFn, g, context, continueBattle) return nextFn(g, context, continueBattle) end,
    encounterRoll = function(nextFn, encDef, ctx) return nextFn(encDef, ctx) end,
    encounterFishing = function(nextFn, rod, mapId, pool, _bridge, ctx) return nextFn(rod, mapId, pool, ctx) end,
    itemUse = function(nextFn, g, battle, itemId, target, list, moveIndex, picker)
      return nextFn(g, battle, itemId, target, list, moveIndex, picker)
    end,
    pcItems = function(nextFn, g, items) return nextFn(g, items) end,
    worldTalk = function(nextFn, ow, target) return nextFn(ow, target) end,
    playerVerdict = nothing,
    holdAway = function() return false, "no_adapter" end,
    releaseAway = function() return false end,
    awayHeld = function() return false end,
    awayMarker = nothing,
    startFromTitle = function() return false, "failed" end,
    progressMarkers = empty,
  }
  return setmetatable(s, { __index = function() return nothing end })
end

-- The stub, for an adapter to grow from (GEN3_BRIDGE_PLAN.md §2): a fresh
-- table each call; the adapter records the names it took over in its
-- `_stubbed` set (missingGroups reads it).
function Engine.stub(opts, id, requireFn)
  return stub(opts or {}, id or "none", requireFn or (opts and opts.require) or function() return nil end)
end

-- opts: main.lua's (require, log, ui, datasets, love) plus
--   import(rel)   loads a gen1-bridge file the way main.lua does (the
--                 adapter is loaded with it, so one mod can carry several
--                 adapters and load one)
--   adapter       an adapter module table already loaded (tests), used in
--                 place of import
-- Returns the adapter table for the running game's engine, or the stub.
function Engine.new(opts)
  opts = opts or {}
  local requireFn = opts.require or function() return nil end
  local log = opts.log
  local id = Engine.engineId(requireFn)
  local rel = Engine.ADAPTERS[id]
  local module
  if rel then
    if type(opts.adapter) == "table" and type(opts.adapter.new) == "function" then
      module = opts.adapter
    elseif type(opts.import) == "function" then
      local ok, m = pcall(opts.import, rel)
      if ok and type(m) == "table" and type(m.new) == "function" then
        module = m
      elseif log then
        log.error("engine.adapter_failed", { engine = id })
      end
    elseif log then
      log.error("engine.adapter_failed", { engine = id, reason = "no_import" })
    end
  end
  if module then
    local aopts = {}
    for k, v in pairs(opts) do aopts[k] = v end
    aopts.checked, aopts.tested = Engine.CHECKED, Engine.TESTED
    -- an adapter may grow from the stub (MG7: game3.lua does)
    aopts.stub = function() return stub(opts, id, requireFn) end
    local adapter = module.new(aopts)
    if type(adapter.engineId) ~= "function" then adapter.engineId = function() return id end end
    if type(adapter.hasAdapter) ~= "function" then adapter.hasAdapter = function() return true end end
    return adapter
  end
  if log then log.warn("engine.no_adapter", { engine = id }) end
  return stub(opts, id, requireFn)
end

return Engine
