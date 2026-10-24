-- engine/gen1: the Gen 1 engine adapter (docs/specs/MULTI_GAME_PLAN.md §4.3,
-- Appendix B; MG2, decision D1): the one gen1-bridge file that touches the
-- `gen1` engine's internals (the manifest's `engine_internals` permission).
-- The engine adapters, lib/engine/*.lua, are the only files that reach into
-- Gen1Recomp; lib/kb_engine.lua loads the one for the running game's engine
-- (GameVersion.engine) and hands the core an adapter table of this shape, or
-- a stub for an engine without one. kb_engine.lua's Engine.GROUPS lists the
-- interface group by group.
--
-- Every function names the engine source it relies on, as `file:line` in the
-- pinned v0.3.85 (refs/gen1recomp at e50c396, the `dev` head of 2026-10-12
-- the release was built from). When the pin changes, re-check each reference
-- below before anything else (CLAUDE.md; tools/pin_check.py lists the
-- citations that moved or changed, and re-stamps the ones that only moved).
-- Elsewhere, gen1-bridge reaches the engine only through this table and the
-- supported mod API (mod.events, mod.hooks, mod.input, mod.world; the
-- export's dataset views come through here too).
--
-- Readers hand back plain values (strings, numbers, booleans, fresh tables),
-- never live engine tables, so nothing outside this file can mutate the game
-- by accident. Shorthand: OC = src/world/OverworldController.lua.

local Gen1 = {}

-- The engine release whose internals this file was checked against is the
-- pin in lib/kb_engine.lua (Engine.CHECKED; the loader hands it over as
-- opts.checked, and hello.ok.tested comes from the same pin).

-- PLAN P21: the hidden game's display options pinned to classic, so mirror
-- frames have the fixed 160x144 geometry, and text at FAST. Keys and
-- defaults: SaveData.defaultOptions (src/core/SaveData.lua:572-713).
Gen1.CLASSIC = {
  tilt = 0,                -- src/render/Tilt.lua:80-91
  zoom = 0,                -- src/render/Zoom.lua:113-115 (0 = FIT)
  battleLayout = "og",     -- src/battle/BattleState.lua:79-81 (not "wide")
  battleFit = "fixed",     -- src/battle/BattleState.lua:94-96 (not "fill")
  uiLayout = "centered",   -- src/core/Game.lua:588-595 (not "dynamic")
  textSpeed = 1,           -- src/render/TextBox.lua:684-686: 1 frame per glyph = FAST
  -- ENGINE_UPGRADE_PLAN U3: the Union Room (src/online/union/Setting.lua:3-7;
  -- an absent key means ON) rewrites Gen 1's Pokemon Centers at data load
  -- (src/core/Data.lua:185 -> src/world/gen1/UnionCenters.lua:297-390,
  -- :392-394), which the export never sees (DatasetViews.lua:427): the
  -- hidden game and the Minecraft world would disagree at every Center.
  -- Pinned off with the display keys; the boot that first finds it on still
  -- runs patched (engine.unionRoomPatched), and writes it off for the next.
  unionRoom = false,
}

-- ENGINE_UPGRADE_PLAN UP1: every engine module this file loads through
-- try() and the functions and fields its code reads from each, by name.
-- engine.checkRequired walks it once at game.ready, so an engine that
-- renamed or dropped one shows up as one engine.missing warning,
-- debug.status.engineMissing and KB_READY missing=<n>, instead of as a
-- silent fallback somewhere below. Modules reached only behind a guard
-- with a fallback (the menu classes, data.scripts.victories,
-- OC.objectVisible) stay out of it; the citations beside each use say what
-- the member has to do. Besides these, the adapter leaves three slots of its
-- own on the Game object (rawset; the engine never reads them):
-- kantoBridgeCloseInstance (engine.takeOver), kantoBridgeState (the
-- process-lifetime record: battles, gifts, holds) and kantoBridgeWindow
-- (running hidden, M10).
Gen1.REQUIRES = {
  ["src.battle.BattleState"] = { "stampOT" },
  -- Game.logicStep is a counter the first logic step creates (nil at game.ready): not a member to require
  ["src.core.Game"] = {},
  ["src.core.GameVersion"] = { "get", "isYellow" },
  ["src.core.Music"] = { "playBattle", "playVictory", "restoreMap" },
  ["src.core.SaveData"] = { "activeSlot", "createSlot", "deleteSlot", "getCart", "loadOptions", "persistenceFs",
                            "refreshSlotResolution", "renameSlot", "saveOptions", "setActiveSlot" },
  ["src.core.Strings"] = {},
  ["src.core.Version"] = { "engine", "modApi" },
  ["src.inventory.Badges"] = { "itemFor", "list" },
  -- MG6: add/capacity/slots for bag.set, isBadge for the key-item predicate
  ["src.inventory.Bag"] = { "add", "capacity", "isBadge", "remove", "slots" },
  ["src.inventory.ItemEffects"] = { "isStone" },
  ["src.mods.Runtime"] = { "call", "wantsHook" },
  ["src.pokemon.Growth"] = { "expForLevel" },
  ["src.pokemon.Pokemon"] = { "movesAtLevel", "new" },
  ["src.pokemon.Stats"] = { "calc" },
  ["src.render.Assets"] = { "imageData" },
  ["src.render.Pipelines"] = { "applyOptions" },
  ["src.render.TextBox"] = { "new", "soundOpts" },
  ["src.render.TileRenderer"] = { "SPINNER_ARROW_TILES", "VOID_FILLS", "borderBlockFor", "defaultAnimatedTiles",
                                  "voidFill" },
  ["src.render.Tilt"] = { "applyOptions" },
  ["src.render.Transition"] = { "whiteFlash" },
  ["src.render.Zoom"] = { "applyOptions" },
  ["src.script.Commands"] = { "show_object" },
  ["src.script.Flags"] = { "set" },
  ["src.ui.Screens"] = { "push" },
  ["src.world.FieldDefaults"] = { "constant", "field", "fieldValue", "world" },
  ["src.world.Map"] = { "ghostBattles", "inRegion", "isFlyTown", "isOutside", "new" },
  ["src.world.OverworldController"] = { "computeNeighbors" },
}

local SYMBOL = "^[A-Za-z0-9_]+$"

local function symbol(v)
  return type(v) == "string" and #v > 0 and #v <= 64 and v:find(SYMBOL) ~= nil
end

-- ------------------------------------------------------------ battle constants (M5)
--
-- Engine facts a trainer team needs that BattleState keeps as file locals,
-- so there is nothing to call without building a battle (newTrainer marks
-- the Pokedex and loads pics, so it is never called just to read a team;
-- refs/_research/P3_gen1_data_export.md 7.5). Copied, cited and re-checked
-- with everything else on a pin change.

-- Fixed trainer DVs, unless constants.trainerDvs overrides them
-- (src/battle/BattleState.lua:543-545, :959-968).
Gen1.TRAINER_DVS = { attack = 9, defense = 8, speed = 8, special = 8 }

-- The boss moves written into a team's third move slot
-- (src/battle/BattleState.lua:871-923, from pokered's
-- data/trainers/special_moves.asm): a gym leader's n-th Pokemon (Giovanni
-- only in party 3), the fifth Pokemon of each Elite Four member, and the
-- Champion's first Pokemon plus his starter's final form.
Gen1.LONE_MOVES = {
  OPP_BROCK = { 2, "BIDE" }, OPP_MISTY = { 2, "BUBBLEBEAM" }, OPP_LT_SURGE = { 3, "THUNDERBOLT" },
  OPP_ERIKA = { 3, "MEGA_DRAIN" }, OPP_KOGA = { 4, "TOXIC" }, OPP_SABRINA = { 4, "PSYWAVE" },
  OPP_BLAINE = { 4, "FIRE_BLAST" }, OPP_GIOVANNI = { 5, "FISSURE", onlyParty = 3 },
}
Gen1.TEAM_MOVES = { OPP_LORELEI = "BLIZZARD", OPP_BRUNO = "FISSURE", OPP_AGATHA = "TOXIC", OPP_LANCE = "BARRIER" }
Gen1.RIVAL_STARTER_MOVES = { VENUSAUR = "MEGA_DRAIN", CHARIZARD = "FIRE_BLAST", BLASTOISE = "BLIZZARD" }
-- GetTrainerName_: the rival classes are named after the rival
-- (src/battle/BattleState.lua:936-943)
Gen1.RIVAL_CLASSES = { OPP_RIVAL1 = true, OPP_RIVAL2 = true, OPP_RIVAL3 = true }

-- ------------------------------------------------------------ M7 constants

-- A blackout's party.heal waits for its warp's arrival, at most this many
-- live steps (engine.queueHeal).
Gen1.HEAL_HOLD_STEPS = 600

-- The PC menu's box row, by the English source label it carries while the
-- ui.pc.items hook sees it: "BILL'S PC", or "SOMEONE'S PC" before meeting
-- Bill (OC:3704-3705; Strings.source returns its argument,
-- src/core/Strings.lua:211-213; rows are translated only after the hook,
-- OC:3784-3801).
Gen1.BOX_PC_LABELS = { ["BILL'S PC"] = true, ["SOMEONE'S PC"] = true }

-- One move list (ids) after setThirdMove (src/battle/BattleState.lua:898-903):
-- the move lands in slot min(3, #moves + 1).
local function setThirdMove(moves, moveId)
  if type(moves) ~= "table" then return end
  moves[math.min(3, #moves + 1)] = moveId
end

-- applySpecialMoves (src/battle/BattleState.lua:905-923) over plain
-- { species, moves = {ids} } entries.
function Gen1.applySpecialMoves(oppClass, partyIndex, party)
  local lone = Gen1.LONE_MOVES[oppClass]
  if lone and (not lone.onlyParty or lone.onlyParty == partyIndex) then
    local mon = party[lone[1]]
    if mon then setThirdMove(mon.moves, lone[2]) end
    return
  end
  local team = Gen1.TEAM_MOVES[oppClass]
  if team then
    if party[5] then setThirdMove(party[5].moves, team) end
    return
  end
  if oppClass == "OPP_RIVAL3" then
    if party[1] then setThirdMove(party[1].moves, "SKY_ATTACK") end
    local starter = party[6]
    if starter and Gen1.RIVAL_STARTER_MOVES[starter.species] then
      setThirdMove(starter.moves, Gen1.RIVAL_STARTER_MOVES[starter.species])
    end
  end
end

-- ------------------------------------------------------------ export constants
--
-- Engine facts the world export needs that the engine keeps as inline
-- literals or locals, so there is nothing to call. Copied here, cited, and
-- re-checked with everything else on a pin change.

-- Cuttable tree tiles per tileset: OVERWORLD $3d and GYM $50
-- (OverworldState:tryCut, src/world/OverworldController.lua:3416-3419, and
-- the same gate in useCutFieldMove :3536-3539). OVERWORLD $52 is cuttable
-- tall grass, not a tree, so it is left out.
Gen1.CUT_TREE_TILES = { OVERWORLD = { 0x3d }, GYM = { 0x50 } }

-- Shore tiles when a tileset record carries no shoreTiles: $32 and $48,
-- except on SHIP_PORT (src/world/Map.lua:20-22, :128-131). Map.new folds
-- water and shore into one set, so the shore half is the one thing that
-- can't be asked of the engine.
Gen1.SHORE_FALLBACK = { 0x32, 0x48 }
Gen1.NO_SHORE_TILESETS = { SHIP_PORT = true }

-- Where each registry the export reads lives in the live Data table: the
-- registry targets (src/mods/Schemas.lua: pokemon :1726, moves :1856,
-- items :1893, maps :1922, tilesets :1980, encounters :2046, sprites :2253,
-- palettes :2825, constants :3101, field :3130).
Gen1.EXPORT_TARGETS = {
  maps = { "maps" }, tilesets = { "tilesets" }, sprites = { "sprites" },
  palettes = { "palettes", "palettes" }, encounters = { "encounters" },
  pokemon = { "pokemon" }, moves = { "moves" }, items = { "items" },
  field = { "field" }, constants = { "constants" },
}

-- A deep copy of plain data: functions, userdata, cycles and metatables are
-- dropped, like DatasetViews' copyData refuses them
-- (src/mods/DatasetViews.lua:117-142). Returns the copy and how many values
-- were dropped.
local function copyPlain(value, seen, dropped)
  local t = type(value)
  if t == "string" or t == "number" or t == "boolean" then return value, dropped end
  if t ~= "table" or seen[value] then return nil, dropped + 1 end
  seen[value] = true
  local out = {}
  for k, v in pairs(value) do
    local kt = type(k)
    if kt == "string" or kt == "number" then
      local copy
      copy, dropped = copyPlain(v, seen, dropped)
      if copy ~= nil then out[k] = copy end
    else
      dropped = dropped + 1
    end
  end
  seen[value] = nil
  return out, dropped
end

-- opts.require:  the mod's (sandboxed) require; tests pass a fake
-- opts.log:      kb_log logger (optional)
-- opts.ui:       mod.ui, the UI module tables (src/ui/ModUI.lua:8-27)
-- opts.datasets: mod.datasets, the dataset views (src/mods/Loader.lua:1435-1443)
-- opts.love:     the sandbox's love facade (M10: love.window and love.timer
--                for running hidden; optional)
-- opts (lib/kb_engine.lua hands them over): require, log, ui, datasets, love,
-- and `checked`, the pin (Engine.CHECKED) this adapter's citations were
-- checked against.
function Gen1.new(opts)
  opts = opts or {}
  local requireFn = opts.require or function() return nil end
  local log = opts.log
  local ui = opts.ui
  local datasets = opts.datasets
  local CHECKED = assert(type(opts.checked) == "string" and opts.checked, "engine/gen1: opts.checked (the pin) is required")
  -- MG6 (docs/specs/MULTI_GAME_PLAN.md §4.9): the canonical item catalogue
  -- (lib/kb_items.lua, generated from protocol/items.json), whose gen1
  -- symbols are this engine's import item ids; nil in tests that build the
  -- adapter alone (then the item table is empty)
  local itemsCatalogue = type(opts.items) == "table" and opts.items or nil
  local engine = {}
  local game

  -- MG2: the adapter's identity (MULTI_GAME_PLAN Appendix B "boot and identity")
  function engine.engineId() return "gen1" end
  function engine.family() return "gen1" end
  function engine.hasAdapter() return true end

  local function try(name)
    local ok, result = pcall(requireFn, name)
    if ok and type(result) == "table" then return result end
    return nil
  end

  -- The Game object from the game.ready payload { game = self }
  -- (src/core/Game.lua:121, emitted once services are up, before the title).
  -- input.step also passes it (src/core/Game.lua:322).
  function engine.setGame(g) if g ~= nil then game = g end end
  function engine.game() return game end

  -- The process-lifetime slot (PLAN section 4.1, "Hot-reload safety").
  -- Nothing unloads a mod: a hot reload (src/dev/HotReload.lua:27-64) or a
  -- return to the launcher drops the old loader, but the old instance's raw
  -- sockets stay open until a garbage collection finds them unreachable,
  -- which can take a long time. The Game object outlives both, so each
  -- instance leaves a function on it that closes its sockets; the next
  -- instance calls it before binding. Returns true when it closed a
  -- previous instance.
  local SLOT = "kantoBridgeCloseInstance"
  function engine.takeOver(closeMine)
    if type(game) ~= "table" then return false end
    local previous = rawget(game, SLOT)
    rawset(game, SLOT, closeMine)
    if type(previous) == "function" and previous ~= closeMine then
      local ok = pcall(previous)
      if not ok and log then log.warn("engine.takeover_failed", {}) end
      return true
    end
    return false
  end

  -- Engine release and mod API: src/core/Version.lua:6-23 (`engine`, stamped
  -- "0.3.85" in the release build; "0.0.0-dev[+commit]" in a source checkout,
  -- :70-88) and `modApi` (:19).
  function engine.version()
    local Version = try("src.core.Version")
    if not Version then return nil end
    return tostring(Version.engine), Version.modApi
  end

  -- True when the running engine is the one these internals were checked
  -- against, or a source checkout (which reports 0.0.0-dev). Logs once when
  -- it differs, since every reference in this file may then be stale.
  function engine.checkPin()
    local v = engine.version()
    if v == nil then return false end
    local ok = v == CHECKED or v:find("^0%.0%.0%-dev") ~= nil
    if not ok and log then
      log.once("engine.pin", "warn", "engine.unchecked_version",
        { engine = v, checked = CHECKED })
    end
    return ok
  end

  -- UP1: the modules and members of Gen1.REQUIRES this engine lacks, as
  -- sorted "module" / "module.member" strings; empty when all are there.
  -- main.lua logs one engine.missing at game.ready and keeps the list in
  -- its status (debug.status.engineMissing, KB_READY missing=).
  function engine.checkRequired()
    local missing = {}
    for name, members in pairs(Gen1.REQUIRES) do
      local m = try(name)
      if m == nil then
        missing[#missing + 1] = name
      else
        for i = 1, #members do
          if m[members[i]] == nil then missing[#missing + 1] = name .. "." .. members[i] end
        end
      end
    end
    table.sort(missing)
    return missing
  end

  -- U3: true when this boot's data carries the Union Room's second floor
  -- (UnionCenters.FLOOR_2F, src/world/gen1/UnionCenters.lua:7, one of the
  -- maps UnionCenters.apply adds, :297-390, when Setting.patchesOn,
  -- :392-394): the live Centers then differ from the import the export
  -- reads, until the setting is off and the engine restarted.
  function engine.unionRoomPatched()
    local data = game and game.data
    local maps = type(data) == "table" and type(data.maps) == "table" and data.maps or nil
    return maps ~= nil and maps.POKECENTER_2F ~= nil
  end

  -- U3: writes the Union Room setting off for the next boot:
  -- SaveData.loadOptions (src/core/SaveData.lua:1025, the full on-disk
  -- table under the defaults) then saveOptions (:896; a whole-file rewrite
  -- that keeps every key the table holds, :904-930). The in-memory pin
  -- (Gen1.CLASSIC) covers this boot's options table; this covers the
  -- file the setting is read from at data load. True when written or
  -- already off.
  function engine.unionRoomOff()
    local SaveData = try("src.core.SaveData")
    if not (SaveData and type(SaveData.loadOptions) == "function" and type(SaveData.saveOptions) == "function") then
      return false
    end
    local ok, opts = pcall(SaveData.loadOptions)
    if not ok or type(opts) ~= "table" then return false end
    if opts.unionRoom == false then return true end
    opts.unionRoom = false
    return pcall(SaveData.saveOptions, opts) == true
  end

  -- "red" or "blue": GameVersion.get (src/core/GameVersion.lua:209-211).
  function engine.gameVersion()
    local GameVersion = try("src.core.GameVersion")
    local v = GameVersion and GameVersion.get and GameVersion.get()
    return type(v) == "string" and v or nil
  end

  -- Game.logicStep counts fixed logic steps (src/core/Game.lua:313-317).
  function engine.logicStep()
    local Game = try("src.core.Game")
    return Game and tonumber(Game.logicStep) or 0
  end

  -- The live save table (src/core/Game.lua:97, :1559 on load). Callers read
  -- it; only pinDisplay writes to it.
  function engine.save()
    return game and type(game.save) == "table" and game.save or nil
  end
  -- MG7 (GEN3_BRIDGE_PLAN.md §3): the readers' save facts; Gen 1's save is already that shape
  engine.saveView = engine.save

  -- The top state of the screen stack: src/core/StateStack.lua:47
  -- (game.stack is StateStack, src/core/Game.lua:95).
  function engine.top()
    local stack = game and game.stack
    return stack and stack.top and stack:top() or nil
  end

  -- The overworld when it is on the stack (it stays under menus and
  -- battles). StateStack.states is the stack, bottom to top
  -- (src/core/StateStack.lua:9-11); the overworld module marks itself with
  -- isOverworld (OC:37). Same authority WorldAPI:overworld uses
  -- (src/world/WorldAPI.lua:96-109), minus its fallback to a stale module
  -- table after a return to the title.
  function engine.overworld()
    local stack = game and game.stack
    local states = stack and stack.states
    if type(states) ~= "table" then return nil end
    for i = #states, 1, -1 do
      local state = states[i]
      if type(state) == "table" and state.isOverworld then
        if state.map and state.player then return state end
        return nil
      end
    end
    return nil
  end

  local UI_CLASSES = { "TextBox", "Menu", "ListMenu", "ChoiceBox", "QuantityBox", "NamingScreen", "PicBox" }

  -- Classifies a state: "overworld", "screen" (it takes input: text, menus,
  -- battles, full screens) or "busy" (a fade, a flash, a slide or a panel
  -- with no input), plus a symbol naming it.
  --   isOverworld OC:37; screenId stamped by Screens.build
  --   (src/ui/Screens.lua:174); isTextBox (src/render/TextBox.lua:22);
  --   isBattleState (src/battle/BattleState.lua:49); the mod.ui module
  --   tables are the instances' metatables (src/ui/ModUI.lua:8-27); isMenu
  --   (e.g. src/ui/BagMenu.lua:11). Anything else is transient
  --   (src/render/Transition.lua and friends).
  function engine.classifyState(state)
    if state == nil then return "none", "None" end
    if type(state) ~= "table" then return "busy", "Busy" end
    if state.isOverworld then return "overworld", "Overworld" end
    if symbol(state.screenId) then return "screen", state.screenId end
    if state.isTextBox then return "screen", "TextBox" end
    if state.isBattleState then return "screen", "BattleState" end
    local mt = getmetatable(state)
    if ui and mt ~= nil then
      for i = 1, #UI_CLASSES do
        local name = UI_CLASSES[i]
        if ui[name] ~= nil and mt == ui[name] then return "screen", name end
      end
    end
    if state.isMenu then return "screen", "Menu" end
    return "busy", "Busy"
  end

  -- Rock Tunnel and friends: field.darkMaps (OC:221-227). Built once per data
  -- table, since observe() asks every step.
  local darkFor, darkSet = nil, {}
  local function darkMapSet()
    local field = game and game.data and game.data.field
    local def = field and field.darkMaps
    if def ~= darkFor then
      darkFor, darkSet = def, {}
      local list = def and def.maps
      for _, id in ipairs(type(list) == "table" and list or {}) do darkSet[id] = true end
    end
    return darkSet
  end

  function engine.darkMaps()
    local out = {}
    for id in pairs(darkMapSet()) do
      if symbol(id) then out[#out + 1] = id end
    end
    table.sort(out)
    return out
  end

  -- Badge inventory keys in gym order: Badges.list / itemFor
  -- (src/inventory/Badges.lua:16-26).
  function engine.badgeItems()
    local Badges = try("src.inventory.Badges")
    local out = {}
    if Badges and Badges.list then
      for _, entry in ipairs(Badges.list(game and game.data) or {}) do
        local item = Badges.itemFor and Badges.itemFor(entry) or entry.item or entry.id
        if symbol(item) then out[#out + 1] = item end
      end
    end
    return out
  end

  local function playerScripted(ow, p)
    -- OC:5814-5819: { entity, dir, remaining, onDone, collide }
    for _, mv in ipairs(type(ow.scriptMoves) == "table" and ow.scriptMoves or {}) do
      if mv.entity == p then return true end
    end
    return false
  end

  -- A Strength boulder on the move: a scriptMoves entry (OC:5814-5819) whose
  -- entity is a pushable object, as checkBoulderPush queues it (OC:1977).
  -- Pushable is Map.isPushable's rule (src/world/Map.lua:188-191): the
  -- object's own `pushable`, else sprite SPRITE_BOULDER. Returns its
  -- direction, or nil.
  local function boulderMoving(ow, p)
    for _, mv in ipairs(type(ow.scriptMoves) == "table" and ow.scriptMoves or {}) do
      local e = mv.entity
      local def = type(e) == "table" and e ~= p and e.def or nil
      if type(def) == "table" and not mv.inPlace then
        local pushable = def.pushable
        if pushable == nil then pushable = def.sprite == "SPRITE_BOULDER" end
        if pushable then return mv.dir end
      end
    end
    return nil
  end

  -- The live player while an overworld is on the stack, refreshed by
  -- observe() every step, so the movement.collision hook can tell the
  -- player's verdicts from the NPCs' without walking the stack each call.
  local livePlayer

  -- M8: a Seafoam current is a chain of the player's scripted steps that
  -- checkSeafoamCurrent starts on one of the map's current cells while
  -- surfing (OC:5176-5226: runSpinnerMoves with noSpin, :4980-5005, which
  -- sets neither p.spinning nor spinnerSliding; the B4F stair push-back,
  -- :5191-5202, is one too). Nothing marks those steps, so a chain is
  -- recognised by where it began: when the player's scripted moves start
  -- (OC:5814-5819) on such a cell while surfing. The surf mount's own
  -- scripted step (stepForwardOrCrossEdge, OC:2275-2283) starts on land.
  local chain = { active = false, current = false }

  local function currentStart(ow, p)
    local data = game and game.data
    local field = data and data.field
    local sf = type(field) == "table" and type(field.seafoam) == "table" and field.seafoam[ow.map.id] or nil
    if type(sf) ~= "table" or not p.surfing then return false end
    local x, y = p.cellX, p.cellY
    local function at(c) return type(c) == "table" and c.x == x and c.y == y end
    for _, c in ipairs(type(sf.currents) == "table" and sf.currents or {}) do
      if at(c) then return true end
    end
    if at(sf.entryCurrent) then return true end
    local exit = type(sf.forcedExit) == "table" and sf.forcedExit or nil
    for _, c in ipairs(exit and type(exit.coords) == "table" and exit.coords or {}) do
      if at(c) then return true end
    end
    return false
  end

  -- M8: which escape a teleportOut spin is (OC:2583-2612, shared by DIG,
  -- TELEPORT and the ESCAPE ROPE): the one gen1-bridge started (act.field)
  -- or saw the bag start (the item.use wrapper below) is marked with its
  -- teleportOut table; otherwise the party menu's rules say it: TELEPORT
  -- works only outside, DIG only in the dungeon tilesets
  -- (src/ui/PartyMenu.lua:740-767), so the map tells them apart. A warp
  -- pad's spin is a warp-table warp (player.warped, OC:5457-5493), which
  -- kb_warp names first.
  local DIG_TILESETS = { FOREST = true, CEMETERY = true, CAVERN = true, FACILITY = true, INTERIOR = true }

  local function outsideMap(ow)
    local Map, data = try("src.world.Map"), game and game.data
    if not (Map and type(Map.isOutside) == "function" and type(ow.map) == "table" and type(ow.map.def) == "table") then
      return false
    end
    local FieldDefaults = try("src.world.FieldDefaults")
    local tilesets = FieldDefaults and type(FieldDefaults.field) == "function"
      and FieldDefaults.field(data, "outsideTilesets") or (data and data.field and data.field.outsideTilesets)
    local ok, out = pcall(Map.isOutside, ow.map.def, tilesets)
    return ok and out == true
  end

  local function teleportKind(ow)
    local st = type(game) == "table" and rawget(game, "kantoBridgeState") or nil
    local mark = type(st) == "table" and st.departure or nil
    if type(mark) == "table" and mark.out == ow.teleportOut then return mark.kind end
    return outsideMap(ow) and "teleport" or "dig"
  end

  -- Everything the player observer needs, as plain values. The input.step
  -- hook runs before the stack updates (src/core/Game.lua:322 vs :356), so
  -- this is the world as the previous step left it.
  function engine.observe()
    local facts = { inGame = false }
    facts.top, facts.topName = engine.classifyState(engine.top())
    local save = engine.save()
    local ow = engine.overworld()
    livePlayer = ow and ow.player or nil
    if not ow then return facts end
    local p = ow.player
    facts.inGame = true
    facts.map = ow.map.id                       -- WorldAPI.lua:116-118 reads the same
    facts.x, facts.y = p.cellX, p.cellY         -- Player.lua:247 (snapped on landing)
    facts.facing = p.facing                     -- "up"/"down"/"left"/"right" (OC:69)
    facts.moving = p.moving == true             -- Player.lua:150, :178-184
    if facts.moving then
      facts.tx, facts.ty = p.targetX, p.targetY -- set at step start (Player.lua:179, OC:5870)
      facts.frames = p.stepFramesCur            -- Player.lua:183, OC:5873
      facts.progress = tonumber(p.progress)     -- frames done, Player.lua:182, :237
    end
    facts.playerScripted = playerScripted(ow, p)
    -- M8: a Seafoam current's chain (see currentStart above)
    if facts.playerScripted then
      if not chain.active or chain.ow ~= ow then
        chain.active, chain.ow, chain.current = true, ow, currentStart(ow, p)
      end
    else
      chain.active, chain.ow, chain.current = false, nil, false
    end
    facts.current = (chain.current and p.surfing) and true or false
    facts.ledge = p.ledgeHop and true or false  -- OC:2098-2150, Player.lua:124
    -- a hop's whole length, both cells: stepLength() * 2 (OC:2130-2131, :2142-2143)
    facts.hopFrames = facts.ledge and tonumber(p.hopTotal) or nil
    facts.boulderPush = boulderMoving(ow, p)
    facts.spinning = (p.spinning or ow.spinnerSliding) and true or false -- OC:4966-5012
    -- departures and arrivals that move or hide the player (OC:1485-1617,
    -- :1665-1671) and the locks set with them (Player.lua:97, :152; OC:2569,
    -- :2599, :5479, :5504, :5681, :5687)
    facts.forcedPhase = (ow.teleportOut or ow.flyFade or ow.flyAnim or ow.flyArrive
      or ow.holeFall or ow.holeArrive or ow.spinArrive or p.inputLocked) and true or false
    -- which departure is under way before a warp's fade starts (P1 6.5):
    -- Fly's fade and bird (flyTo, OC:2554-2571; OC:1485-1527), the spin of
    -- Dig/Teleport/Escape Rope and of a warp pad (teleportOut, OC:2583-2612,
    -- :5489-5492, :1555-1582), a hole's sink (fallThroughHole, OC:5503-5509,
    -- :1585-1596)
    if ow.flyFade or ow.flyAnim then
      facts.departure = "fly"
    elseif ow.teleportOut then
      facts.departure = teleportKind(ow) -- M8: dig, rope or teleport
    elseif ow.holeFall then
      facts.departure = "hole"
    end
    facts.hopLand = tonumber(ow.hopLand) or 0     -- OC:1667, :1691
    facts.transitioning = ow.transitioning and true or false -- OC:5573, :5709-5711
    facts.emote = ow.emote ~= nil and ow.emote ~= false      -- OC:1629-1646
    facts.anim = (ow.cutAnim or (ow.dustAnim and ow.dustAnim.boulder)) and true or false
    local runner = ow.runner
    local running = runner and runner.isRunning and runner:isRunning() or false
    -- the rest of OC:1665-1671's `scripted`, plus the early-return holds
    -- healAnim and pikaHop (OC:1463-1482)
    facts.scripted = (running or #(ow.scriptMoves or {}) > 0 or ow.engaging
      or ow.healAnim or ow.pikaHop) and true or false
    -- M8: the main script runner alone (a script's end is a story-diff
    -- moment; ScriptRunner:isRunning, src/script/ScriptRunner.lua:120-139)
    facts.runner = running and true or false
    facts.dark = ow.dark and true or false                          -- OC:514-522, :1182
    facts.surf = p.surfing and true or false                         -- Player.lua:139, :331
    facts.bike = (save and save.onBike) and true or false            -- OC:1420-1421
    facts.slope = (facts.bike and p.slopeMap) and true or false      -- Player.lua:128
    facts.hidden = ow.playerHidden and true or false                 -- OC:6725-6728
    facts.darkMap = darkMapSet()[facts.map] == true                  -- OC:517-522
    facts.flashLit = (save and save.flashLit) and true or false      -- OC:517-520, :1182
    facts.npcs = type(ow.npcs) == "table" and #ow.npcs or 0
    -- a battle Cobblemon is fighting holds this world (M5, engine.battle*)
    facts.battle = engine.battleHolds(ow)
    return facts
  end

  -- ow.npcs is rebuilt as a new table by every setMap (OC:558-565: warps,
  -- seam crossings, same-map warps), so its identity tells a map (re)load
  -- from NPCs coming and going on the same map. The generation counts the
  -- tables seen.
  local npcTable, npcGeneration = nil, 0

  -- The live NPCs of the current map, as PLAN section 4.1 "NPCs" describes
  -- them: ow.npcs holds the visible ones (NPC.lua:39-71, OC:558-565); sprite
  -- is appearance or def.sprite (NPC.lua:75-86); item balls have def.item
  -- (except the ITEM_NONE sentinels "0" and 0, OC:3563-3568); boulders are
  -- SPRITE_BOULDER or pushable (Map.lua:188-191); statics have def.pokemon;
  -- trainers trainerClass. Per NPC also its step: moving, targetX/Y, the
  -- step length (stepFrames or 32, NPC.lua:9, :116), marching (the
  -- walk-in-place cycle, NPC.lua:123-131), progress, and `via` when a
  -- scripted move drives it: scriptMoves entries (OC:5814-5830) are "march"
  -- for an inPlace beat, "push" for a pushable object (a Strength boulder,
  -- OC:1977), else "script".
  -- Returns the list, the map id and the npc-table generation, or nil
  -- outside the overworld.
  function engine.npcs()
    local ow = engine.overworld()
    if not ow then return nil end
    local list = type(ow.npcs) == "table" and ow.npcs or {}
    if list ~= npcTable then
      npcTable, npcGeneration = list, npcGeneration + 1
    end
    local via = {}
    for _, mv in ipairs(type(ow.scriptMoves) == "table" and ow.scriptMoves or {}) do
      local e = mv.entity
      if type(e) == "table" and e ~= ow.player then
        local def = type(e.def) == "table" and e.def or {}
        local pushable = def.pushable
        if pushable == nil then pushable = def.sprite == "SPRITE_BOULDER" end
        via[e] = mv.inPlace and "march" or (pushable and "push") or "script"
      end
    end
    local out = {}
    for _, npc in ipairs(list) do
      if type(npc) == "table" then
        local def = type(npc.def) == "table" and npc.def or {}
        local sprite = npc.appearance or def.sprite
        local kind = "person"
        if def.item ~= nil and def.item ~= "0" and def.item ~= 0 then
          kind = "item"
        elseif sprite == "SPRITE_BOULDER" or def.pushable then
          kind = "boulder"
        elseif def.pokemon ~= nil then
          kind = "static"
        elseif def.trainerClass ~= nil then
          kind = "trainer"
        end
        out[#out + 1] = {
          id = npc.id, sprite = sprite, kind = kind,
          x = npc.cellX, y = npc.cellY, facing = npc.facing, moving = npc.moving == true,
          tx = npc.targetX, ty = npc.targetY,
          marching = npc.marching == true,
          progress = tonumber(npc.progress) or 0,
          frames = tonumber(npc.stepFrames) or 32,
          via = via[npc],
        }
      end
    end
    return out, ow.map.id, npcGeneration
  end

  -- The global emotion-bubble hold, ow.emote = { npc, frames, bubble,
  -- onDone } (OC:1629-1646; Commands.emote, src/script/Commands.lua:1191-1207):
  -- bubble nil or 1 is "!", 2 "?", 3 happy, false a silent hold (OC:6279-6299).
  -- Each new hold gets a new serial. target is "player" (ow.player), an NPC
  -- id, or nil (a silent cutscene beat with no entity).
  local emoteSerials = setmetatable({}, { __mode = "k" })
  local emoteSerial = 0
  function engine.emote()
    local ow = engine.overworld()
    local em = ow and ow.emote
    if type(em) ~= "table" then return nil end
    local serial = emoteSerials[em]
    if serial == nil then
      emoteSerial = emoteSerial + 1
      serial = emoteSerial
      emoteSerials[em] = serial
    end
    local target
    if em.npc ~= nil and em.npc == ow.player then
      target = "player"
    elseif type(em.npc) == "table" and symbol(em.npc.id) then
      target = em.npc.id
    end
    local bubble = em.bubble
    if bubble ~= false and type(bubble) ~= "number" then bubble = nil end
    return { serial = serial, target = target, bubble = bubble, frames = tonumber(em.frames) or 0 }
  end

  -- ------------------------------------------------------------ interaction (M4)

  -- world.interacted { mapId, x, y, kind, target } fires after the A press
  -- was handled, in the same logic step (OC:2645-2648, :2650-2727). kind is
  -- npc, sign, door, hidden, script, bookshelf or none; for npc the target
  -- is the NPC, which was talked to only if it stood still (a moving NPC
  -- gets no world.talk, OC:2663-2680). Plain values only.
  function engine.interactedFacts(payload)
    if type(payload) ~= "table" then return nil end
    local target = payload.target
    local out = {
      map = symbol(payload.mapId) and payload.mapId or nil,
      x = tonumber(payload.x), y = tonumber(payload.y),
      kind = symbol(payload.kind) and payload.kind or "none",
    }
    if out.kind == "npc" and type(target) == "table" then
      out.npcId = symbol(target.id) and target.id or nil
      out.npcMoving = target.moving == true
    end
    return out
  end

  -- Whether (x, y) is a cell of the current map (Map:inBounds,
  -- src/world/Map.lua:213-215), or nil outside the overworld. The A press
  -- looks only at the current map (OC:2650-2727): a cell across a seam holds
  -- nothing it can reach, the neighbour map's NPCs being drawn ghosts that
  -- never talk (OC:904-922).
  function engine.cellInMap(x, y)
    local ow = engine.overworld()
    local m = ow and ow.map
    if not (m and type(m.inBounds) == "function") then return nil end
    local ok, inside = pcall(m.inBounds, m, x, y)
    if not ok then return nil end
    return inside and true or false
  end

  -- True when (x, y) of the current map is a counter tile: the A press
  -- then reaches one cell further (OC:2655-2660; Map:isCounterCell,
  -- src/world/Map.lua:272).
  function engine.counterCell(x, y)
    local ow = engine.overworld()
    local m = ow and ow.map
    if not (m and type(m.isCounterCell) == "function") then return false end
    local ok, counter = pcall(m.isCounterCell, m, x, y)
    return ok and counter == true
  end

  -- ------------------------------------------------------------ UI (M4)
  --
  -- The UI observer (observe/kb_ui.lua) follows the screen stack
  -- (game.stack.states, src/core/StateStack.lua:9-11) without holding its
  -- states: each state gets a small integer key here (weak keys, so a
  -- popped state can be collected; weak values for the way back). Readers
  -- return plain copies; the few writes (a cursor, a quantity, a name) are
  -- the ones P2 sections 4-6 describe, made right before the bridge taps
  -- the button that confirms them.

  local stateKeys = setmetatable({}, { __mode = "k" })
  local keyStates = setmetatable({}, { __mode = "v" })
  local lastKey = 0
  local function keyOf(state)
    local k = stateKeys[state]
    if k == nil then
      lastKey = lastKey + 1
      k = lastKey
      stateKeys[state] = k
      keyStates[k] = state
    end
    return k
  end

  -- The engine's string catalog, an identity function without a
  -- translation mod (src/core/Strings.lua:46-90); labels pass through it
  -- when drawn (e.g. ChoiceBox.lua:90-91, ListMenu.lua:374).
  local function S(text)
    if type(text) ~= "string" then return nil end
    local Strings = try("src.core.Strings")
    if Strings then
      local ok, out = pcall(Strings, text)
      if ok and type(out) == "string" then return out end
    end
    return text
  end

  -- The widget classes are the instances' metatables (mod.ui, ModUI.lua:8-27).
  local UI_CLASS_NAMES = { "ChoiceBox", "Menu", "ListMenu", "QuantityBox", "NamingScreen", "PicBox" }
  -- Screens built with .new outside Screens.push carry no screenId: the
  -- daycare's and Name Rater's PartyMenu (data/scripts/story2.lua:1171,
  -- story4.lua:767) and the prize counter (data/scripts/story3.lua:900).
  -- Their modules are loaded by then, so require hands back the class.
  local MENU_CLASSES = {
    { "PartyMenu", "src.ui.PartyMenu" }, { "PrizeCounter", "src.ui.PrizeCounter" },
    { "PokedexMenu", "src.ui.PokedexMenu" },
  }
  local SCREEN_CLASSES = { PartyMenu = true, PokedexMenu = true, PrizeCounter = true, TownMap = true }

  -- A state's class for the UI observer, plus its screen id (a symbol, or
  -- nil). Classes: Overworld; Manager (the mod manager, ManagerState:
  -- everything from it up is the player's own business, P2 2.3); TextBox
  -- (isTextBox, TextBox.lua:22); the mod.ui widgets ChoiceBox, Menu,
  -- ListMenu, QuantityBox, NamingScreen, PicBox; BattleState (isBattleState,
  -- BattleState.lua:49); the adapted screens PartyMenu, PokedexMenu,
  -- PrizeCounter, TownMap (by the screenId Screens.build stamps,
  -- src/ui/Screens.lua:174-176, or by class); Screen (any other screen that
  -- takes input: a screenId or isMenu); Busy (no input: fades, flashes,
  -- slides, the SAVE panel; P2 2.2's last row).
  function engine.uiClass(state)
    if type(state) ~= "table" then return "Busy" end
    if state.isOverworld then return "Overworld" end
    local sid = symbol(state.screenId) and state.screenId or nil
    if sid == "ManagerState" then return "Manager", sid end
    if state.isTextBox then return "TextBox", sid end
    local mt = getmetatable(state)
    if ui and mt ~= nil then
      if ui.TextBox ~= nil and mt == ui.TextBox then return "TextBox", sid end
      for i = 1, #UI_CLASS_NAMES do
        local m = ui[UI_CLASS_NAMES[i]]
        if m ~= nil and mt == m then return UI_CLASS_NAMES[i], sid end
      end
    end
    if state.isBattleState then return "BattleState", sid or "BattleState" end
    if sid and SCREEN_CLASSES[sid] then return sid, sid end
    if sid == nil and state.isMenu and mt ~= nil then
      for i = 1, #MENU_CLASSES do
        local m = try(MENU_CLASSES[i][2])
        if m and mt == m then return MENU_CLASSES[i][1], MENU_CLASSES[i][1] end
      end
    end
    if sid then return "Screen", sid end
    if state.isMenu then return "Screen", nil end
    return "Busy"
  end

  -- The stack as plain entries, bottom to top: { key, cls, screen }.
  function engine.uiStack()
    local stack = game and game.stack
    local states = stack and stack.states
    local out = {}
    if type(states) ~= "table" then return out end
    for i = 1, #states do
      local s = states[i]
      local cls, screen = engine.uiClass(s)
      out[i] = { key = type(s) == "table" and keyOf(s) or 0, cls = cls, screen = screen }
    end
    return out
  end

  local function stateFor(key, cls)
    local s = keyStates[key]
    if type(s) ~= "table" then return nil end
    if cls and engine.uiClass(s) ~= cls then return nil end
    return s
  end

  local function count(t) return type(t) == "table" and #t or 0 end
  local function int(v, default)
    v = tonumber(v)
    if v == nil or v ~= v then return default end
    return math.floor(v)
  end

  -- TextBox (src/render/TextBox.lua): the rows currently in the box.
  -- TextBox:visibleText (:404-412) maps `shownSource` (one entry per shown
  -- row, kept by beginLine :386-401: the page line it came from, or false
  -- for the blank continuation row Gen 1's fixed two-row box inserts) to
  -- the page's lines, "" for a blank row. A box without shownSource (an
  -- older engine, a fake) keeps the v0.3.49 rule: the page's lines up to
  -- lineIndex, as many as `shown` holds (at most two).
  local function visibleLines(s)
    local pages = type(s.pages) == "table" and s.pages or {}
    local page = pages[int(s.pageIndex, 1)]
    if type(page) ~= "table" then return {} end
    local out = {}
    if type(s.shownSource) == "table" then
      for i = 1, #s.shownSource do
        local source = s.shownSource[i]
        local text = source and page[source] or nil
        out[#out + 1] = type(text) == "string" and text or ""
      end
      return out
    end
    local shown = count(s.shown)
    local line = int(s.lineIndex, 1)
    for i = math.max(1, line - shown + 1), line do
      if type(page[i]) == "string" then out[#out + 1] = page[i] end
    end
    return out
  end

  -- The box takes an A/B tap this logic step (P2 3.3; TextBox:update
  -- :478-725, in its order): nothing while a PAUSE or sound waits (preSound
  -- :490-504, pauseFrames :510-528, pauseSrc :529-534), a scroll or page
  -- clear holds (holdFrames, :505-509) or a sound effect holds (sfxWait,
  -- cleared by sfxHeld :415-420 once the effect ends; checked before every
  -- press below). Finished: a stay.prompt or stay.press box takes one press
  -- (:535-556), an auto.promptFirst box one press (:557-565; auto.wait
  -- hands the box to the plain path, :572-578); other stay and auto boxes
  -- and a choice box take none (:566-635); A/B closes a plain box
  -- (:636-643, waitButton only mutes the press sound). Mid-text: waiting at
  -- a page break or scroll once the 3-frame preWait has run out (:647-660).
  local function textReady(s)
    if int(s.holdFrames, 0) > 0 then return false end
    if s.preSound or s.pauseFrames or s.pauseSrc or s.sfxWait then return false end
    if s.done then
      if s.stay then
        return type(s.stay) == "table" and (s.stay.prompt or s.stay.press) and not s.stayShown and true or false
      end
      if s.auto then
        return type(s.auto) == "table" and s.auto.promptFirst and not s.autoPrompted and true or false
      end
      return not s.choice
    end
    return s.waiting == true and int(s.preWait, 0) == 0
  end

  -- `waiting` for the client and the tests: a press does something now.
  -- Deliberately NOT TextBox:arrowVisible (:428-440) since v0.3.85: a text
  -- ending in {DONE} (TextBox.ending :72-78 sets waitButton, TextBox.new
  -- :152-157) hides the engine's arrow while its update still closes the
  -- box on A/B (:636-643), and a stay.press box takes its press without one
  -- (:541-548). The Minecraft dialogue and the e2e tests press only while
  -- `waiting` is up (TextBoxModel; story.py), so the drawn arrow's rule left
  -- every {DONE} box unpressable (G3, 2026-10-13). So `waiting` keeps its
  -- v0.3.49 meaning, "takes a press": a sound effect hides it, a page break
  -- or scroll shows it, a finished box shows it unless a choice box is up
  -- or its auto/stay prompt was already taken.
  local function textArrow(s)
    if s.sfxWait then return false end
    if s.waiting then return true end
    if not s.done or s.choice then return false end
    if s.auto then
      return type(s.auto) == "table" and s.auto.promptFirst and not s.autoPrompted and true or false
    end
    if s.stay then
      return type(s.stay) == "table" and (s.stay.prompt or s.stay.press) and not s.stayShown and true or false
    end
    return true
  end

  -- Menu rows are drawn as their labels (Menu.lua:180-184); callers built
  -- them through Strings already (e.g. StartMenu.lua:32-165).
  local function menuItems(items)
    local out = {}
    for i = 1, count(items) do
      local it = items[i]
      out[i] = { label = type(it) == "table" and type(it.label) == "string" and it.label or "" }
    end
    return out
  end

  -- ListMenu rows (src/ui/ListMenu.lua:1-3, drawn :322-344, :378-402): the
  -- right-hand column is a level (`sub`), a price, a count (×N) or `right`.
  local function listItems(items)
    local out = {}
    for i = 1, count(items) do
      local it = items[i]
      local row = { label = "" }
      if type(it) == "table" then
        row.label = type(it.label) == "string" and it.label or ""
        if type(it.sub) == "string" then
          row.right = it.sub
        elseif type(it.price) == "string" then
          row.right = it.price
        elseif it.count ~= nil then
          row.right = "\195\151" .. tostring(it.count) -- "×N", :337-341
        elseif type(it.right) == "string" then
          row.right = it.right
        end
      end
      out[i] = row
    end
    return out
  end

  -- PrizeCounter rows: prizes {name, cost} (src/ui/PrizeCounter.lua:15-23)
  -- and the fixed fourth row, NO THANKS, which cancels (:33-37, drawn :62).
  local function prizeItems(s)
    local out = {}
    for i, p in ipairs(type(s.prizes) == "table" and s.prizes or {}) do
      out[i] = { label = type(p) == "table" and type(p.name) == "string" and p.name or "",
                 right = type(p) == "table" and p.cost ~= nil and tostring(p.cost) or nil }
    end
    out[#out + 1] = { label = S("NO THANKS") }
    return out
  end

  -- TownMap's fly list (src/ui/TownMap.lua:259-276): the visited fly towns
  -- (locs, :265) by the names the banner shows (:174-177).
  local function flyItems(s)
    local out = {}
    for i, loc in ipairs(type(s.locs) == "table" and s.locs or {}) do
      out[i] = { label = type(loc) == "table" and S(loc.name) or "" }
    end
    return out
  end

  local function copyStrings(list)
    local out = {}
    for i = 1, count(list) do
      if type(list[i]) == "string" then out[#out + 1] = list[i] end
    end
    return out
  end

  -- The NamingScreen's letter pages, as NamingScreen:grid gives them
  -- (src/ui/NamingScreen.lua:186-197, the ui.naming.grid hook included),
  -- for both cases: `lower` is flipped only around the call and restored.
  -- The ED cell and the case-switch row are controls, not glyphs (findMeta's
  -- rule, :72-84), so they are left out.
  local CASE_ROWS = { ["lower case"] = true, ["UPPER CASE"] = true, lower = true, UPPER = true }
  local function namingGrid(s)
    if type(s.grid) ~= "function" then return {} end
    local saved = s.lower
    local pages = {}
    for _, lower in ipairs({ false, true }) do
      s.lower = lower
      local ok, grid = pcall(s.grid, s)
      local rows = {}
      if ok and type(grid) == "table" then
        for r = 1, #grid do
          local row = grid[r]
          if type(row) == "table" and not (#row == 1 and CASE_ROWS[row[1]]) then
            local cells = {}
            for c = 1, #row do
              local cell = row[c]
              if type(cell) == "string" and cell ~= "ED" and #cell > 0 then cells[#cells + 1] = cell end
            end
            if #cells > 0 then rows[#rows + 1] = cells end
          end
        end
      end
      pages[#pages + 1] = rows
    end
    s.lower = saved
    return pages
  end

  local function stackIndex(state)
    local states = game and game.stack and game.stack.states
    if type(states) ~= "table" then return nil end
    for i = #states, 1, -1 do
      if states[i] == state then return i, states end
    end
    return nil
  end

  -- The money a menu-like UI shows (M7): the Mart's BUY/SELL/QUIT menu
  -- draws save.money in its MONEY box (ShopMenu's drawClerk,
  -- src/ui/ShopMenu.lua:280-290); its BUY and SELL lists are dialogue lists
  -- with a money() reader (ShopMenu.lua:78-84, :188-194; ListMenu.lua
  -- :135, :140, :414-421). nil for anything else.
  local function shopMoney(s, screen)
    if screen == "ShopMenu" then
      local save = engine.save()
      return save and int(save.money, nil) or nil
    end
    if s.dialogue and type(s.money) == "function" then
      local ok, money = pcall(s.money)
      if ok then return int(money, nil) end
    end
    return nil
  end

  -- Everything a UI needs when it opens, by class, as plain values (raw
  -- engine strings; the observer translates them to the protocol markup).
  function engine.uiOpenFacts(key)
    local s = keyStates[key]
    if type(s) ~= "table" then return nil end
    local cls, screen = engine.uiClass(s)
    local f = { cls = cls, screen = screen }
    if cls == "TextBox" then
      -- pages + pages.contBefore (TextBox.paginate, :298-375; built at :181);
      -- the opts flags (:133, :143-144, :161)
      local pages = type(s.pages) == "table" and s.pages or {}
      local before = type(pages.contBefore) == "table" and pages.contBefore or {}
      f.pages, f.cont = {}, {}
      for p = 1, #pages do
        local lines = type(pages[p]) == "table" and pages[p] or {}
        local conts = type(before[p]) == "table" and before[p] or {}
        local pl, pc = {}, {}
        for i = 1, #lines do
          pl[i] = type(lines[i]) == "string" and lines[i] or ""
          pc[i] = conts[i] == true
        end
        f.pages[p], f.cont[p] = pl, pc
      end
      -- auto/stay as the protocol means them: the box's end takes no press.
      -- auto.promptFirst waits for one press first (:529-534) and auto.wait
      -- turns into a plain A/B box once its sound is over (:569-572);
      -- stay.prompt takes one press before it stays up (:511-526).
      local auto, stay = s.auto, s.stay
      f.auto = auto ~= nil and auto ~= false
        and not (type(auto) == "table" and (auto.promptFirst or auto.wait))
      -- a stay.prompt or stay.press box takes one press at its end (TextBox.lua:535-556), so it is
      -- not a "stays up" box to the client; the engine's own screens never pass stay = true
      f.stay = stay ~= nil and stay ~= false and not (type(stay) == "table" and (stay.prompt or stay.press))
      f.instant = s.instant == true
      f.choice = s.choice ~= nil
    elseif cls == "ChoiceBox" then
      -- labels default to YES/NO (ChoiceBox.lua:33), drawn through Strings
      -- (:90-91); the question is the text box right under it (P2 4)
      f.labels = {}
      for i, label in ipairs(type(s.labels) == "table" and s.labels or {}) do f.labels[i] = S(label) or "" end
      local i, states = stackIndex(s)
      local below = i and states[i - 1]
      if type(below) == "table" and engine.uiClass(below) == "TextBox" then f.question = visibleLines(below) end
    elseif cls == "Menu" then
      -- items {label} (Menu.lua:1-2, :17), title (:40, may carry {DONE}/
      -- {PROMPT} and line breaks, :137-143), maxVisible (:46); the Mart
      -- menu keeps the clerk's line in `footer` (src/ui/ShopMenu.lua:31-37)
      f.items = menuItems(s.items)
      f.title = type(s.title) == "string" and s.title or nil
      f.rows = int(s.maxVisible, nil)
      f.footer = type(s.footer) == "string" and s.footer or nil
      f.wrap = not s.noWrap -- up/down wrap unless noWrap (Menu.lua:38, :90-95)
      f.money = shopMoney(s, screen)
    elseif cls == "ListMenu" then
      -- title (:110-111, drawn through Strings :370), items (:113), rows
      -- (:158), footer (:120), the Mart's money box (dialogue + money(),
      -- :135, :140, :414-421), script mode (the old man's demo, :131)
      f.items = listItems(s.items)
      f.title = type(s.title) == "string" and S(s.title) or nil
      f.rows = int(s.rows, nil)
      f.footer = type(s.footer) == "string" and s.footer or nil
      f.wrap = s.wrap == true -- clamps unless opts.wrap (ListMenu.lua:123, :164-174)
      f.money = shopMoney(s, screen)
      f.script = s.script ~= nil
    elseif cls == "QuantityBox" then
      f.qty, f.max, f.unit = int(s.qty, 1), int(s.max, 1), int(s.unitPrice, nil) -- QuantityBox.lua:14-16
    elseif cls == "NamingScreen" then
      -- title (:92), presets (:93), maxLen (:95), default (:96), mon (:102)
      f.title = type(s.title) == "string" and s.title or ""
      f.presets = copyStrings(s.presets)
      f.max = int(s.maxLen, 7)
      f.value = type(s.default) == "string" and s.default or ""
      f.species = type(s.mon) == "table" and symbol(s.mon.species) and s.mon.species or nil
      f.grid = namingGrid(s)
    elseif cls == "PokedexMenu" then
      -- items {num, name, label} (src/ui/PokedexMenu.lua:84-98), rows() :113,
      -- the SEEN/OWN footer (:103)
      f.items = menuItems(s.items)
      local ok, rows = pcall(function() return s:rows() end)
      f.rows = ok and int(rows, nil) or nil
      f.footer = type(s.footer) == "string" and s.footer or nil
      f.wrap = false -- the cursor stops at both ends (PokedexMenu:syncScroll, :118-127)
    elseif cls == "PrizeCounter" then
      f.items = prizeItems(s)
      f.rows = #f.items
      f.wrap = false -- stops at rows 1 and 4 (PrizeCounter.lua:28-33)
    elseif cls == "TownMap" then
      -- only fly mode is a list (src/ui/TownMap.lua:259-276); the viewer and
      -- the dex AREA map are art
      f.fly = s.fly == true
      if f.fly then
        f.items = flyItems(s)
        f.rows = #f.items
        f.wrap = true -- TownMap:moveList goes round (:322-327)
      end
    end
    return f
  end

  -- The fields that move while a UI is open, by class.
  function engine.uiCursor(key)
    local s = keyStates[key]
    if type(s) ~= "table" then return nil end
    local cls = engine.uiClass(s)
    if cls == "TextBox" then
      -- pageIndex/lineIndex (:189-190), done (:195)
      return { page = int(s.pageIndex, 1), line = int(s.lineIndex, 1), done = s.done == true,
               waiting = textArrow(s), ready = textReady(s) }
    elseif cls == "ChoiceBox" then
      -- index 1 YES / 2 NO (:17); pending during the 15-frame answer hold (:43-52)
      return { index = int(s.index, 1), busy = s.pending ~= nil }
    elseif cls == "Menu" then
      return { index = int(s.index, 1), scroll = int(s.scroll, 0), count = count(s.items),
               busy = s.frozen == true } -- frozen ignores input (Menu.lua:89)
    elseif cls == "ListMenu" then
      -- script: the old man's demo list reads no input (ListMenu.lua:129-132, :220-223)
      return { index = int(s.index, 1), scroll = int(s.scroll, 0), count = count(s.items),
               script = s.script ~= nil }
    elseif cls == "QuantityBox" then
      return { qty = int(s.qty, 1), max = int(s.max, 1) }
    elseif cls == "NamingScreen" then
      return { choosing = s.choosing == true, typed = count(s.glyphs) } -- :98, :117
    elseif cls == "PokedexMenu" then
      return { index = int(s.index, 1), scroll = int(s.scroll, 0), count = count(s.items) }
    elseif cls == "PrizeCounter" then
      return { index = int(s.index, 1), scroll = 0, count = count(s.prizes) + 1 }
    elseif cls == "TownMap" then
      return { index = int(s.sel, 1), scroll = 0, count = count(s.locs), fly = s.fly == true } -- :303
    end
    return {}
  end

  -- A menu-like UI's rows again (they change while it is open: tosses,
  -- sales, swaps; P2 5.2), or nil for other classes.
  function engine.uiItems(key)
    local s = keyStates[key]
    if type(s) ~= "table" then return nil end
    local cls = engine.uiClass(s)
    if cls == "Menu" or cls == "PokedexMenu" then return menuItems(s.items) end
    if cls == "ListMenu" then return listItems(s.items) end
    if cls == "PrizeCounter" then return prizeItems(s) end
    if cls == "TownMap" and s.fly then return flyItems(s) end
    return nil
  end

  -- A menu-like UI's footer (raw) and money, which change while it is open
  -- (M7): the Mart's clerk lines (ShopMenu's say, src/ui/ShopMenu.lua:32-40,
  -- and list.footer, :107, :119, :137), its money box after a purchase or a
  -- sale (:130, :255), the PC lists' prompts (src/ui/PlayerPC.lua). The
  -- same rules as uiOpenFacts; nil for other classes.
  function engine.uiMoneyFooter(key)
    local s = keyStates[key]
    if type(s) ~= "table" then return nil end
    local cls, screen = engine.uiClass(s)
    if cls ~= "Menu" and cls ~= "ListMenu" and cls ~= "PokedexMenu" then return nil end
    return { footer = type(s.footer) == "string" and s.footer or nil, money = shopMoney(s, screen) }
  end

  -- Moves a cursor to `index` (1-based), keeping the scroll window around
  -- it as the widget itself would on its next update: Menu:clampScroll
  -- (Menu.lua:74-84), ListMenu's syncScroll (ListMenu.lua:176-182),
  -- PokedexMenu:syncScroll (PokedexMenu.lua:118-127). ChoiceBox index
  -- 1/2 (:17), PrizeCounter rows 1-4 (PrizeCounter.lua:13, :20), TownMap's
  -- fly cursor `sel` (TownMap.lua:303). Returns true when it moved.
  function engine.uiSetIndex(key, index)
    local s = keyStates[key]
    if type(s) ~= "table" or type(index) ~= "number" or index ~= math.floor(index) then return false end
    local cls = engine.uiClass(s)
    if cls == "ChoiceBox" then
      if index ~= 1 and index ~= 2 then return false end
      s.index = index
    elseif cls == "Menu" then
      local n = count(s.items)
      if index < 1 or index > n then return false end
      s.index = index
      local maxVisible = int(s.maxVisible, nil)
      if not (maxVisible and n > maxVisible) then
        s.scroll = 0
      elseif index - int(s.scroll, 0) > maxVisible then
        s.scroll = index - maxVisible
      elseif index - int(s.scroll, 0) < 1 then
        s.scroll = index - 1
      end
    elseif cls == "ListMenu" then
      if index < 1 or index > count(s.items) then return false end
      s.index = index
      local maxRow = int(s.cursorRows, nil) or int(s.rows, 7)
      if index - int(s.scroll, 0) > maxRow then s.scroll = index - maxRow end
      if index - int(s.scroll, 0) < 1 then s.scroll = index - 1 end
    elseif cls == "PokedexMenu" then
      if index < 1 or index > count(s.items) then return false end
      s.index = index
      if type(s.syncScroll) == "function" then pcall(s.syncScroll, s) end
    elseif cls == "PrizeCounter" then
      if index < 1 or index > count(s.prizes) + 1 then return false end
      s.index = index
    elseif cls == "TownMap" then
      if not s.fly or index < 1 or index > count(s.locs) then return false end
      s.sel = index
    else
      return false
    end
    return true
  end

  -- QuantityBox: qty in 1..max (QuantityBox.lua:14-15, :29-33).
  function engine.uiSetQty(key, qty)
    local s = stateFor(key, "QuantityBox")
    if not s or type(qty) ~= "number" or qty ~= math.floor(qty) then return false end
    if qty < 1 or qty > int(s.max, 1) then return false end
    s.qty = qty
    return true
  end

  -- The mirror's frame (MG7, G3B): the Game Boy's screen.
  function engine.screenSize() return 160, 144 end

  -- NamingScreen: the typed glyphs, one grid cell per entry, as A would
  -- have inserted them (NamingScreen.lua:98, :242-245); confirm() joins them
  -- (:153-174) when START is pressed on the next update (:201-203). None
  -- (item 9): confirm's own empty name, the screen's default or "" (no
  -- nickname: the species' name stays, and evolution renames it, :156-180).
  function engine.uiSetGlyphs(key, glyphs)
    local s = stateFor(key, "NamingScreen")
    if not s or type(glyphs) ~= "table" then return false end
    local copy = {}
    for i = 1, #glyphs do
      if type(glyphs[i]) ~= "string" then return false end
      copy[i] = glyphs[i]
    end
    s.glyphs = copy
    return true
  end

  -- ------------------------------------------------------------ movement (M3)

  local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }

  -- Turns the standing player in place by writing ow.player.facing, as the
  -- engine's own cutscenes do (Commands.face_player_dir,
  -- src/script/Commands.lua:547-549) and as handleInput does before an edge,
  -- ledge or boulder check (OC:1842-1845, which also ends a wall-bonk cycle
  -- by clearing bumpFrames). turnArmed and turnTimer are left alone, so a
  -- held direction then steps at once with no 4-frame turn
  -- (Player:tryMove, src/world/Player.lua:151-165), and nothing fires: map
  -- onStep scripts run only from onStepComplete (OC:4833-4839). Refused
  -- mid-step, where the pixel interpolation follows the facing
  -- (Player.lua:242-245). Returns true when the player now faces dir.
  function engine.setFacing(dir)
    local ow = engine.overworld()
    local p = ow and ow.player
    if not p or p.moving or DELTA[dir] == nil then return false end
    if p.facing ~= dir then
      p.facing = dir
      p.bumpFrames = nil
    end
    return true
  end

  -- True while any source holds a Game Boy button: Input:isDown
  -- (src/core/Input.lua:761-765) on game.input, the one mod.input presses
  -- into (src/mods/Loader.lua:1467-1505). Input:reset on a focus or
  -- visibility change wipes every source, mods' holds included
  -- (Input.lua:193-216; src/core/Game.lua:1151, :1164).
  function engine.buttonDown(btn)
    local input = game and game.input
    if type(input) ~= "table" or type(input.isDown) ~= "function" then return false end
    local ok, down = pcall(input.isDown, input, btn)
    return ok and down == true
  end

  -- One movement.collision verdict as plain values when its mover is the
  -- player, else nil (NPC moves pass through the same hook). The hook's ctx
  -- is { map, mover, dir, fromX, fromY, toX, toY, reason }
  -- (src/world/Collision.lua:84-96); the engine's reasons are bounds, tile
  -- and entity (Collision.lua:56-73), and a wrapper may rewrite them.
  -- Player:tryMove asks it for every attempted step (Player.lua:166-177);
  -- the door walk-out probe (OC:5701) and collide-moves (OC:5858-5862) do
  -- too, which callers tell apart by timing.
  function engine.playerVerdict(ctx, allowed)
    if type(ctx) ~= "table" or livePlayer == nil or ctx.mover ~= livePlayer then return nil end
    local reason = ctx.reason
    return {
      allowed = allowed and true or false,
      dir = DELTA[ctx.dir] and ctx.dir or nil,
      reason = type(reason) == "string" and symbol(reason) and reason or nil,
    }
  end

  -- Where a warp the player is taking leaves from, for the protocol's warp
  -- kinds. Called from the player.warped event, which takeWarp emits before
  -- anything moves (OC:5456-5459), so the player still stands on the
  -- departure cell; Warp.lua's three triggers (src/world/Warp.lua:1-10)
  -- then read:
  --   pad, hole  Map:warpPadOrHoleAt (src/world/Map.lua:265-269), the test
  --              takeWarp branches on (OC:5470-5497)
  --   door       a door tile underfoot (Map:isDoorTileCell, Map.lua:253-255)
  --   stairs     another warp-activating tile (Map:isWarpTileCell,
  --              Map.lua:258-261): stairs, ladders, cave mouths
  --   edge       neither, with the facing pointing off the map: an exit mat,
  --              taken by walking off the edge (checkEdgeExit, OC:2160-2174;
  --              Warp.onEdge, Warp.lua:77-86) or by arriving on it facing the
  --              edge with the d-pad held (Warp.extraCheck's facing-edge
  --              test, Warp.lua:38-45; onStepComplete, OC:4892-4899)
  --   door       neither, facing into the map: the carpet maps' collision
  --              warps (route-gate doorways, the dock: Warp.extraCheck's
  --              warp-tile-in-front test, Warp.lua:46-61; OC:1856-1862)
  -- Returns { kind, map, x, y, facing } or nil outside the overworld.
  function engine.warpDeparture()
    local ow = engine.overworld()
    local p, m = ow and ow.player, ow and ow.map
    if not (p and m) then return nil end
    local x, y, facing = p.cellX, p.cellY, p.facing
    local out = { map = symbol(m.id) and m.id or nil, x = x, y = y, facing = facing }
    local function ask(method, ...)
      local fn = m[method]
      if type(fn) ~= "function" then return nil end
      local ok, v = pcall(fn, m, ...)
      if not ok then return nil end
      return v
    end
    local pad = ask("warpPadOrHoleAt", x, y)
    if pad == "pad" or pad == "hole" then
      out.kind = pad
    elseif ask("isDoorTileCell", x, y) then
      out.kind = "door"
    elseif ask("isWarpTileCell", x, y) then
      out.kind = "stairs"
    else
      local d = DELTA[facing]
      local inside = d and ask("inBounds", x + d[1], y + d[2])
      out.kind = (d and inside == false) and "edge" or "door"
    end
    return out
  end

  -- True when options already hold the classic pin (debug.status reports it).
  function engine.displayPinned(options)
    if type(options) ~= "table" then return false end
    for key, value in pairs(Gen1.CLASSIC) do
      if options[key] ~= value then return false end
    end
    for _, level in pairs(type(options.pipelines) == "table" and options.pipelines or {}) do
      if level ~= 0 then return false end
    end
    return true
  end

  -- Pins options (a save.options table) to Gen1.CLASSIC and turns every
  -- render pipeline (Dramaless voxel and friends) to level 0, keeping the
  -- keys. Returns how many values changed. With reapply, the modules that
  -- cache these values are told at once: Tilt.applyOptions
  -- (src/render/Tilt.lua:80-91), Pipelines.applyOptions
  -- (src/render/Pipelines.lua:230-252) and Zoom.applyOptions
  -- (src/render/Zoom.lua:113-115), in Game:applyOptions' order
  -- (src/core/Game.lua:1493-1498). battleLayout, battleFit, uiLayout and
  -- textSpeed are read live. Nothing is written to disk here; the engine
  -- persists options only when its own OPTIONS screen saves them
  -- (Game:writeOptions, src/core/Game.lua:1452-1455).
  function engine.pinDisplay(options, reapply)
    if type(options) ~= "table" then return 0 end
    local changed = 0
    for key, value in pairs(Gen1.CLASSIC) do
      if options[key] ~= value then
        options[key] = value
        changed = changed + 1
      end
    end
    if type(options.pipelines) == "table" then
      for id, level in pairs(options.pipelines) do
        if level ~= 0 then
          options.pipelines[id] = 0
          changed = changed + 1
        end
      end
    end
    if changed > 0 and reapply then
      for _, name in ipairs({ "src.render.Tilt", "src.render.Pipelines", "src.render.Zoom" }) do
        local cached = try(name)
        if cached and cached.applyOptions then
          local ok = pcall(cached.applyOptions, options)
          if not ok and log then log.once("pin:" .. name, "warn", "engine.pin_apply_failed", { module = name }) end
        end
      end
    end
    return changed
  end

  -- ------------------------------------------------------------ battles, gifts, the shadow party (M5)
  --
  -- Battles the hidden game would fight are fought in Cobblemon instead
  -- (PLAN section 4.1 "Battles"), through two seams:
  --   * script.command (src/script/ScriptRunner.lua:168-172, inside the
  --     script's coroutine): the rows start_battle, rival_battle and
  --     static_battle (src/script/Commands.lua:339-393, :1054-1083,
  --     :991-1000) wait on runner.waitingCheck, exactly as push_screen
  --     waits for its screen (Commands.lua:1351-1371; ScriptRunner:update,
  --     ScriptRunner.lua:290-308). The result then goes through
  --     start_battle's own resume path (ctx.resumeBattle, Commands.lua:
  --     342-358): lastBattleResult, lastCheck and afterBattle (OC:5334-5375),
  --     whose lose branch keeps the Oak's-lab rival loss in the lab
  --     (OC:5345-5351). static_battle's beat flag and object toggle follow
  --     on their own (Commands.lua:1000-1006).
  --   * trainer.before_battle (OC:4172-4193, :4281-4301; docs/modding.md
  --     "trainer.before_battle"): map trainers are deferred; a result applies
  --     engageTrainer's own win side effects (OC:4261-4278) and ends the
  --     encounter with continue({ cancel = true }) (OC:4182-4183), which runs
  --     the encounter's completion callback and suppresses the sight at the
  --     player's cell (OC:4291-4300). M8 wave 3: a win instead runs the
  --     encounter's own start with the battle stood in for, and its onFinish
  --     (startTrainerBattle below), so a loss line the caller handed over
  --     is shown too.
  -- What a result shows first, as text boxes the battle screen would have
  -- shown (BattleState.lua): the trainer's own loss line with its tag
  -- (:5006-5037) and the prize (:4979-4980, :5039); on a loss Rival1WinText
  -- (:5117-5130) and the blackout lines (:1894-1897), except for the
  -- Oak's-lab rival (:5096-5101, :5133).
  --
  -- The pending battle, the captured gifts and the queued heals live in a
  -- process-lifetime slot on the Game object (like kantoBridgeCloseInstance),
  -- so a hot reload keeps them: a script coroutine waiting on a battle holds
  -- the previous instance's wrapper, which only ever reads the shared record.
  -- Battles are taken over only while a Minecraft client (or a test) is
  -- connected; otherwise the hidden game fights them itself.

  local STATE_SLOT = "kantoBridgeState"
  local SCRIPT_BATTLES = { start_battle = true, rival_battle = true, static_battle = true }

  local function gameData() return type(game) == "table" and type(game.data) == "table" and game.data or nil end

  local function bridgeState()
    if type(game) ~= "table" then return nil end
    local st = rawget(game, STATE_SLOT)
    if type(st) ~= "table" then
      -- giving: the give_pokemon calls under way, { ow, save } each
      st = { serial = 0, gifts = {}, heals = 0, giving = {} }
      rawset(game, STATE_SLOT, st)
    end
    return st
  end

  local function warnAt(code, err)
    if log then
      log.warn(code, { at = type(err) == "string" and err:match("[%w_/%.%-]+%.lua:%d+") or "unknown" })
    end
  end

  -- A per-process token for battle and gift ids (the first instance's
  -- survives hot reloads), and the counter behind them.
  function engine.bridgeToken(token)
    local st = bridgeState()
    if not st then return token or "x" end
    if st.token == nil and type(token) == "string" then st.token = token end
    return st.token or token or "x"
  end
  function engine.nextSerial()
    local st = bridgeState()
    if not st then return 0 end
    st.serial = st.serial + 1
    return st.serial
  end

  -- ---- data lookups (the live Data registries: pokemon[id].dex,
  -- RomExtractor.lua:1215; moves[id].index = the Gen 1 move number, the
  -- moveOrder position, RomExtractor.lua:608-624)

  function engine.dexOf(species)
    local data = gameData()
    local def = data and type(data.pokemon) == "table" and data.pokemon[species] or nil
    return type(def) == "table" and tonumber(def.dex) or nil
  end

  local dexFor, dexIndex = nil, {}
  function engine.speciesOfDex(dex)
    local data = gameData()
    local reg = data and data.pokemon
    if type(reg) ~= "table" then return nil end
    if reg ~= dexFor then
      dexFor, dexIndex = reg, {}
      for id, def in pairs(reg) do
        local n = type(def) == "table" and tonumber(def.dex) or nil
        if n and type(id) == "string" and (dexIndex[n] == nil or id < dexIndex[n]) then dexIndex[n] = id end
      end
    end
    return dexIndex[dex]
  end

  function engine.moveNumOf(id)
    local data = gameData()
    local def = data and type(data.moves) == "table" and data.moves[id] or nil
    return type(def) == "table" and tonumber(def.index) or nil
  end

  local moveFor, moveIndex = nil, {}
  function engine.moveIdOfNum(num)
    local data = gameData()
    local reg = data and data.moves
    if type(reg) ~= "table" then return nil end
    if reg ~= moveFor then
      moveFor, moveIndex = reg, {}
      for id, def in pairs(reg) do
        local n = type(def) == "table" and tonumber(def.index) or nil
        if n and type(id) == "string" and (moveIndex[n] == nil or id < moveIndex[n]) then moveIndex[n] = id end
      end
    end
    return moveIndex[num]
  end

  -- ---- trainer teams

  -- The trainer's name as the battle shows it (BattleState.lua:902-916): the
  -- class name, the rival's name for the rival classes, plus a personal
  -- name. Raw engine text (ROM-derived), never logged.
  local function trainerName(cls, partyIndex)
    local data = gameData()
    local tr = data and type(data.trainers) == "table" and data.trainers[cls] or nil
    if type(tr) ~= "table" then return nil end
    local name = tr.name
    if Gen1.RIVAL_CLASSES[cls] then
      local save = engine.save()
      name = (save and type(save.player) == "table" and save.player.rival) or "BLUE"
    end
    local personal = type(tr.partyNames) == "table" and tr.partyNames[partyIndex] or nil
    if type(name) == "string" and type(personal) == "string" and personal ~= "" then name = name .. " " .. personal end
    return type(name) == "string" and name or nil
  end

  -- A trainer's party as BattleState.newTrainer builds it, without building
  -- a battle: the party (through the trainer.party hook, :918-924), each
  -- slot's moves from Pokemon.movesAtLevel (src/pokemon/Pokemon.lua:10-30,
  -- as Pokemon.new, :60-88, gives them), the boss moves (:937), a slot's own
  -- moves list winning (:938-948), the fixed DVs (:925-934). Returns a plain
  -- list of { species, dex, level, moves = {Gen 1 numbers}, dvs }, the
  -- trainer's name (raw text) and its base money (:4979), or nil + reason.
  function engine.trainerTeam(cls, partyIndex)
    local data = gameData()
    local tr = data and type(data.trainers) == "table" and data.trainers[cls] or nil
    if type(tr) ~= "table" or type(tr.parties) ~= "table" then return nil, "no_trainer" end
    partyIndex = tonumber(partyIndex) or 1
    local partyDef = tr.parties[partyIndex]
    if type(partyDef) ~= "table" then return nil, "no_party" end
    local Runtime = try("src.mods.Runtime")
    if Runtime and type(Runtime.wantsHook) == "function" and Runtime.wantsHook("trainer.party") then
      local ok, hooked = pcall(Runtime.call, "trainer.party", function(_, _, party) return party end,
        cls, partyIndex, partyDef)
      if ok and type(hooked) == "table" then partyDef = hooked end
    end
    local Pokemon = try("src.pokemon.Pokemon")
    if not (Pokemon and type(Pokemon.movesAtLevel) == "function") then return nil, "no_pokemon_module" end
    local mons = {}
    for i, slot in ipairs(partyDef) do
      local def = type(slot) == "table" and type(data.pokemon) == "table" and data.pokemon[slot.species] or nil
      if type(def) ~= "table" or type(slot.level) ~= "number" then return nil, "bad_slot" end
      local ok, ids = pcall(Pokemon.movesAtLevel, def, slot.level)
      if not ok or type(ids) ~= "table" then return nil, "moves_failed" end
      local moves = {}
      for k = 1, #ids do moves[k] = ids[k] end
      mons[i] = { species = slot.species, level = slot.level, moves = moves }
    end
    Gen1.applySpecialMoves(cls, partyIndex, mons)
    for i, slot in ipairs(partyDef) do
      if mons[i] and type(slot.moves) == "table" then
        local own = {}
        for k = 1, #slot.moves do own[k] = slot.moves[k] end
        mons[i].moves = own
      end
    end
    local dvs = (type(data.constants) == "table" and type(data.constants.trainerDvs) == "table")
      and data.constants.trainerDvs or Gen1.TRAINER_DVS
    local out = {}
    for i, m in ipairs(mons) do
      local nums = {}
      for _, id in ipairs(m.moves) do nums[#nums + 1] = engine.moveNumOf(id) or 0 end
      out[i] = {
        species = m.species, dex = engine.dexOf(m.species), level = m.level, moves = nums,
        dvs = { atk = tonumber(dvs.attack), def = tonumber(dvs.defense), spd = tonumber(dvs.speed),
                spc = tonumber(dvs.special) },
      }
    end
    return out, trainerName(cls, partyIndex), tonumber(tr.baseMoney) or 0
  end

  -- ---- the pending battle

  local function liveBattle()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    local rec = type(st) == "table" and st.battle or nil
    if type(rec) ~= "table" then return nil end
    return rec, st
  end

  -- True while a battle Cobblemon fights holds this overworld (the
  -- classifier reports busy).
  function engine.battleHolds(ow)
    local rec = liveBattle()
    return rec ~= nil and rec.ow == ow and rec.state ~= "consumed"
  end

  -- The pending battle as plain values (data is kb_battle's own battle.start
  -- payload), or nil.
  function engine.battlePending()
    local rec = liveBattle()
    if not rec then return nil end
    return { id = rec.id, kind = rec.kind, state = rec.state, path = rec.path, data = rec.data,
             sentGen = rec.sentGen, cls = rec.cls, wild = rec.wild == true }
  end

  function engine.battleSent(id, generation)
    local rec = liveBattle()
    if rec and rec.id == id then rec.sentGen = generation end
  end

  -- Drops a pending battle whose world is gone (the title, a new game,
  -- CONTINUE): its script coroutine or deferred encounter went with it.
  -- Returns true when one was dropped.
  function engine.battleStale()
    local rec, st = liveBattle()
    if not rec then return false end
    if rec.state == "consumed" or rec.ow ~= engine.overworld() or rec.save ~= engine.save() then
      st.battle = nil
      return true
    end
    return false
  end

  local function lastLevel(data)
    local team = type(data) == "table" and data.team or nil
    local last = type(team) == "table" and team[#team] or nil
    return type(last) == "table" and tonumber(last.level) or 0
  end

  local function newRecord(plan, facts, path)
    local data = gameData()
    local tr = facts.cls and data and type(data.trainers) == "table" and data.trainers[facts.cls] or nil
    return {
      id = plan.id, kind = plan.kind, data = plan.data, path = path, state = "waiting",
      cls = facts.cls, party = facts.party, wild = facts.wild == true, map = facts.map, npcId = facts.npc,
      -- engine side only
      ow = engine.overworld(), save = engine.save(),
      prize = (type(tr) == "table" and tonumber(tr.baseMoney) or 0) * lastLevel(plan.data),
      fake = { kind = facts.wild and "wild" or "trainer", oppClass = facts.cls, partyIndex = facts.party },
    }
  end

  -- ---- battle music (M8, PLAN P22)
  --
  -- While a battle Cobblemon fights holds the hidden game, the hidden game
  -- plays the Gen 1 battle theme that battle would have had: Music.playBattle
  -- (src/core/Music.lua:461-468, kind "wild" | "trainer" | "gym" | "final")
  -- with BattleState:computeMusicKind's choice (src/battle/BattleState.lua
  -- :1791-1818): "final" for OPP_RIVAL3, "gym" for a badge fight (a
  -- data/scripts/victories.lua reward with a badge for class#party, the
  -- eight gym battles, :1801-1803) or OPP_LANCE, "trainer" for every other
  -- trainer, "wild" for wild Pokemon. A direct battle (below) plays its own
  -- BattleState:playBattleTheme (:1838-1841), as OverworldState:pushBattle
  -- does at the wipe (OC:1273-1277). A win with texts to show plays the
  -- victory theme under them (Music.playVictory, Music.lua:474-483; the
  -- final battle shares the gym leaders' theme, BattleState.lua:5803-5810),
  -- and every result brings the map's song back (restoreMapMusic, M7).
  local musicCounts = { battle = 0, victory = 0, failed = 0 }

  -- a badge fight: victories.lua's reward for class#party carries a badge
  -- (BattleState.lua:1801-1803); without the module, the gym leaders'
  -- boss-move table names the same eight fights
  local function badgeFight(cls, partyIndex)
    local victories = try("data.scripts.victories")
    if victories then
      local reward = victories[tostring(cls) .. "#" .. tostring(partyIndex or 1)]
      return type(reward) == "table" and reward.badge ~= nil
    end
    local lone = Gen1.LONE_MOVES[cls]
    return lone ~= nil and (not lone.onlyParty or lone.onlyParty == partyIndex)
  end

  local function musicKindFor(rec)
    if rec.wild then return "wild" end
    if rec.cls == "OPP_RIVAL3" then return "final" end
    if rec.cls == "OPP_LANCE" or badgeFight(rec.cls, rec.party) then return "gym" end
    return "trainer"
  end

  -- The held battle's theme (battle: the engine's own BattleState of a
  -- direct battle, or nil). Records the kind on the record for the
  -- victory theme and the map song's return.
  local function playBattleMusic(rec, battle)
    local data = gameData()
    rec.music = musicKindFor(rec)
    local ok, err = false, nil
    if type(battle) == "table" and type(battle.playBattleTheme) == "function" then
      ok, err = pcall(battle.playBattleTheme, battle)
    else
      local Music = try("src.core.Music")
      if not (Music and type(Music.playBattle) == "function" and data) then return end
      ok, err = pcall(Music.playBattle, data, rec.music, rec.cls)
    end
    if ok then
      musicCounts.battle = musicCounts.battle + 1
    else
      musicCounts.failed = musicCounts.failed + 1
      warnAt("engine.battle_music_failed", err)
    end
  end

  -- The victory jingle's kind, BattleState:playVictoryMusic's rule
  -- (src/battle/BattleState.lua:5897-5911): the gym leaders' theme for the
  -- final battle and for a badge fight (the engine's isGymLeader), the
  -- trainer jingle for a gym-kind battle that isn't a leader's (Lance), else
  -- the battle's own kind; Music.playVictory (src/core/Music.lua:474).
  local function playVictoryMusic(rec)
    local Music, data = try("src.core.Music"), gameData()
    if not (Music and type(Music.playVictory) == "function" and data and rec.music) then return end
    local kind = rec.music
    if kind == "final" or badgeFight(rec.cls, rec.party) then
      kind = "gym"
    elseif kind == "gym" then
      kind = "trainer"
    end
    local ok, err = pcall(Music.playVictory, data, kind)
    if ok then
      musicCounts.victory = musicCounts.victory + 1
    else
      musicCounts.failed = musicCounts.failed + 1
      warnAt("engine.battle_music_failed", err)
    end
  end

  -- Commands.rival_battle's party offset for Red/Blue (Commands.lua:1070-1082).
  local function rivalOffset(save, offsets)
    local flags = type(save) == "table" and type(save.flags) == "table" and save.flags or {}
    local data = gameData()
    offsets = offsets or (data and type(data.field) == "table" and data.field.starterCounterpicks or nil)
    if type(offsets) == "table" then
      for flag, mapped in pairs(offsets) do
        if flags[flag] == true then return tonumber(mapped) or 0 end
      end
      return 0
    end
    if flags.EVENT_CHOSE_SQUIRTLE == true then return 1 end
    if flags.EVENT_CHOSE_BULBASAUR == true then return 2 end
    return 0
  end

  local function isYellow()
    local GameVersion = try("src.core.GameVersion")
    if not (GameVersion and type(GameVersion.isYellow) == "function") then return false end
    local ok, yes = pcall(GameVersion.isYellow)
    return ok and yes == true
  end

  -- Plain facts about a held battle row, or nil to let the engine run it.
  local function scriptBattleFacts(ctx, name, args)
    local data = gameData()
    if not data or type(args) ~= "table" then return nil end
    local f = { path = "script", command = name }
    if name == "static_battle" or (name == "start_battle" and args[1] == "wild") then
      local species, level = args[1], args[2]
      if name == "start_battle" then species, level = args[2], args[3] end
      if not (symbol(species) and type(data.pokemon) == "table" and type(data.pokemon[species]) == "table"
          and type(level) == "number") then
        return nil
      end
      f.wild, f.species, f.level, f.dex = true, species, level, engine.dexOf(species)
    elseif name == "start_battle" then
      f.cls, f.party = args[2], tonumber(args[3]) or 1
    else
      -- Yellow keys its rival parties off save.rivalStarter (Commands.lua:1042-1069)
      if isYellow() or type(args[2]) ~= "number" then return nil end
      f.cls, f.party, f.rival = args[1], args[2] + rivalOffset(ctx.save, args[3]), true
    end
    if f.cls ~= nil and not (symbol(f.cls) and type(data.trainers) == "table" and type(data.trainers[f.cls]) == "table") then
      return nil
    end
    local ow = ctx.overworld
    f.map = type(ow) == "table" and type(ow.map) == "table" and symbol(ow.map.id) and ow.map.id or nil
    local npc = ctx.npc
    if type(npc) == "table" then
      if symbol(npc.id) then f.npc = npc.id end
      f.x, f.y = tonumber(npc.cellX), tonumber(npc.cellY)
    end
    return f
  end

  -- An aborted static_battle (M8): the object its script hid before the
  -- fight (hide_object writes objectToggles[map][name] = false,
  -- Commands.lua:603-647) comes back through Commands.show_object, unless
  -- its beat flag (static_battle's third argument, Commands.lua:998-1007)
  -- is set. Nothing else of the script is undone.
  local function reshowStatic(ctx, args)
    local npc, ow, save = ctx.npc, ctx.overworld, ctx.save
    if not (type(npc) == "table" and type(npc.def) == "table" and symbol(npc.def.name) and type(ow) == "table"
            and type(ow.map) == "table" and symbol(ow.map.id) and type(save) == "table") then
      return false
    end
    local beat = type(args) == "table" and args[3] or nil
    if beat ~= nil and type(save.flags) == "table" and save.flags[beat] then return false end
    local toggles = type(save.objectToggles) == "table" and save.objectToggles[ow.map.id] or nil
    if not (type(toggles) == "table" and toggles[npc.def.name] == false) then return false end
    local Commands = try("src.script.Commands")
    if not (Commands and type(Commands.show_object) == "function") then return false end
    Commands.show_object({ game = game, save = save, overworld = ow }, ow.map.id, npc.def.name)
    return true
  end

  local function scriptBattle(nextFn, ctx, name, args, bridge)
    local runner = ctx.runner
    if ctx.resumeBattle ~= nil or type(runner) ~= "table" or type(runner.yield) ~= "function"
        or runner.parallel then
      return nextFn(ctx, name, args)
    end
    local okLive, live = pcall(bridge.live)
    if not (okLive and live) or liveBattle() then return nextFn(ctx, name, args) end
    local okFacts, facts = pcall(scriptBattleFacts, ctx, name, args)
    if not (okFacts and facts) then return nextFn(ctx, name, args) end
    local okPlan, plan = pcall(bridge.battle, facts)
    if not (okPlan and type(plan) == "table" and type(plan.id) == "string") then return nextFn(ctx, name, args) end
    local st = bridgeState()
    local record = newRecord(plan, facts, "script")
    -- save_end_battle_text's armed line (Commands.lua:310-318), already
    -- substituted; shown after a win as the battle screen would
    record.endText = type(ctx.endBattleText) == "string" and ctx.endBattleText or nil
    st.battle = record
    playBattleMusic(record) -- M8 (P22)
    runner.waitingCheck = function() return record.state == "done" end
    runner:yield()
    -- Resumed by ScriptRunner:update once the result's texts are done
    -- (possibly applied by a newer instance after a hot reload: only the
    -- record is read here).
    local result = record.result
    record.state = "consumed"
    if st.battle == record then st.battle = nil end
    ctx.endBattleText = nil -- one armed line arms one battle (Commands.lua:366-368)
    -- aborted: the script stops here, so whatever triggers it can run it again
    if result == nil or result == "abort" then
      -- M8: a static_battle whose script hid its object before the fight
      -- (Snorlax's wake-up, data/scripts/story.lua:652-660) shows it again,
      -- so the Poke Flute can wake it once more
      if name == "static_battle" then pcall(reshowStatic, ctx, args) end
      return "end"
    end
    ctx.resumeBattle = { result = result, battle = record.fake }
    return nextFn(ctx, name, args)
  end

  -- trainer.before_battle's wrapper body: defers a map trainer's battle.
  -- context = { trainerClass, partyIndex, mapId, npcId } (OC:4286-4291).
  -- While it waits, the world is held as a trainer sight holds it
  -- (ow.engaging, part of OC:1665-1671's `scripted`), also for the talk path.
  function engine.trainerBeforeBattle(nextFn, g, context, continueBattle, bridge)
    if type(context) ~= "table" or type(continueBattle) ~= "function" then return nextFn(g, context, continueBattle) end
    local okLive, live = pcall(bridge.live)
    local ow = engine.overworld()
    local data = gameData()
    if not (okLive and live) or not ow or not data or liveBattle()
        or not (symbol(context.trainerClass) and type(data.trainers) == "table"
                and type(data.trainers[context.trainerClass]) == "table") then
      return nextFn(g, context, continueBattle)
    end
    local npc
    for _, n in ipairs(type(ow.npcs) == "table" and ow.npcs or {}) do
      if type(n) == "table" and n.id == context.npcId then npc = n break end
    end
    local facts = {
      path = "trainer", cls = context.trainerClass, party = tonumber(context.partyIndex) or 1,
      map = symbol(context.mapId) and context.mapId or (symbol(ow.map.id) and ow.map.id or nil),
      npc = symbol(context.npcId) and context.npcId or nil,
    }
    if npc then facts.x, facts.y = tonumber(npc.cellX), tonumber(npc.cellY) end
    local okPlan, plan = pcall(bridge.battle, facts)
    if not (okPlan and type(plan) == "table" and type(plan.id) == "string") then
      return nextFn(g, context, continueBattle)
    end
    local record = newRecord(plan, facts, "trainer")
    -- the encounter's continuation: cancels an abort or a loss, and (M8
    -- wave 3) runs the encounter's own start on a win (startTrainerBattle)
    record.continueBattle = continueBattle
    -- the trainer header's event flag and loss line (Data:trainerHeader,
    -- src/core/Data.lua:426-429; OC:4207-4221, :4264-4266): what a win
    -- shows and applies when that start can't run (M5's own path)
    if npc and type(npc.def) == "table" and type(ow.map) == "table" and type(ow.map.def) == "table"
        and type(data.trainerHeader) == "function" then
      local ok, header = pcall(data.trainerHeader, data, ow.map.def.label, npc.def.index)
      if ok and type(header) == "table" then
        record.event = symbol(header.event) and header.event or nil
        local won = header.won and type(data.text) == "table" and data.text[header.won] or nil
        record.endText = type(won) == "string" and won or nil
      end
    end
    if not ow.engaging then
      ow.engaging = true
      record.heldEngaging = true
    end
    bridgeState().battle = record
    playBattleMusic(record) -- M8 (P22)
    return true
  end

  -- The line the game prints for a label, with the engine's wording as the
  -- fallback: src/core/RomText.lua (the module is a function).
  local function romText(label, fallback, ...)
    local okReq, RomText = pcall(requireFn, "src.core.RomText")
    local data = gameData()
    if okReq and type(RomText) == "function" then
      local ok, text = pcall(RomText, data, label, fallback, ...)
      if ok and type(text) == "string" then return text end
    end
    local text = data and type(data.text) == "table" and data.text[label] or nil
    if type(text) == "string" then return text end
    local okFmt, formatted = pcall(string.format, fallback, ...)
    return okFmt and formatted or nil
  end

  -- M8 wave 3: wins applied through engageTrainer's own start
  -- (startTrainerBattle below), starts that came back empty, and the loss
  -- lines shown after a win (with a sound: a gym leader's badge jingle).
  local trainerCounts = { started = 0, failed = 0, lines = 0, sounds = 0 }

  -- A win's loss line as the battle screen shows it (BattleState.lua
  -- :5006-5037): its \f pages, empty ones skipped, the trainer's tag on the
  -- first; and (M8 wave 3) the sound the line carries (engageTrainer's
  -- endBattleSound, OC:4255-4257: a gym leader's badge jingle) played once
  -- the page endBattleSoundPage (default 1) has typed: the pages up to that
  -- one make a box that plays it (TextBox.soundOpts, TextBox.lua:225-243,
  -- the reward chain's way, OC:4335-4382), the rest a second box. A sound
  -- page past the line's last page plays nothing, as on the battle screen.
  local function lossLineBoxes(rec, texts)
    local pages = {}
    for page in (rec.endText .. "\f"):gmatch("(.-)\f") do
      if page ~= "" then pages[#pages + 1] = page end
    end
    if #pages == 0 then return end
    local tag = trainerName(rec.cls, rec.party)
    if tag then pages[1] = tag .. ": " .. pages[1] end
    trainerCounts.lines = trainerCounts.lines + 1
    local sound = type(rec.endSound) == "string" and rec.endSound ~= "" and rec.endSound or nil
    local at = sound and math.max(1, math.floor(tonumber(rec.endSoundPage) or 1)) or nil
    if at and at <= #pages then
      trainerCounts.sounds = trainerCounts.sounds + 1
      texts[#texts + 1] = { text = table.concat(pages, "\f", 1, at), sound = sound }
      if at < #pages then texts[#texts + 1] = table.concat(pages, "\f", at + 1) end
    else
      texts[#texts + 1] = table.concat(pages, "\f")
    end
  end

  -- The text boxes a result shows (raw engine text, substituted by
  -- TextBox.new itself, TextBox.lua:172), and the prize. A box is its
  -- text, or { text, sound } for one that plays a sound (lossLineBoxes).
  local function resultTexts(rec, result)
    local save = engine.save()
    local player = save and type(save.player) == "table" and save.player.name or nil
    player = type(player) == "string" and player or "RED"
    local texts, prize = {}, 0
    if result == "win" and rec.cls then
      if type(rec.endText) == "string" then lossLineBoxes(rec, texts) end
      prize = tonumber(rec.prize) or 0
      if prize > 0 then
        texts[#texts + 1] = romText("_MoneyForWinningText", "%s got \194\165%d\nfor winning!", player, prize)
      end
    elseif result == "lose" then
      local data = gameData()
      if rec.cls == "OPP_RIVAL1" then
        local raw = data and type(data.text) == "table" and data.text._Rival1WinText or nil
        texts[#texts + 1] = type(raw) == "string" and raw or "{RIVAL}: Yeah! Am\nI great or what?"
      end
      if not (rec.cls == "OPP_RIVAL1" and rec.map == "OAKS_LAB") then
        texts[#texts + 1] = romText("_PlayerBlackedOutText2", "%s is out of\nuseable POK\195\169MON!\f%s blacked\nout!",
          player, player)
      end
    end
    return texts, prize
  end

  -- Pushes text boxes one after the other (TextBox.new, TextBox.lua:129;
  -- A/B pops each and calls its onDone), then runs onDone. An entry
  -- { text, sound } plays its sound once its last page has typed and holds
  -- until it is over (TextBox.soundOpts, TextBox.lua:225-243).
  local function pushTexts(list, onDone)
    local TextBox = try("src.render.TextBox")
    local i = 0
    local function nextBox()
      i = i + 1
      local entry = list[i]
      if entry == nil or not (TextBox and type(TextBox.new) == "function" and game and game.stack) then
        return onDone()
      end
      local text, opts = entry, nil
      if type(entry) == "table" then
        text = entry.text
        if entry.sound ~= nil and type(TextBox.soundOpts) == "function" then
          local okOpts, o = pcall(TextBox.soundOpts, game, entry.sound)
          if okOpts and type(o) == "table" then opts = o end
        end
      end
      local ok, box = pcall(TextBox.new, game, text, nextBox, opts)
      if not ok or type(box) ~= "table" then return nextBox() end
      game.stack:push(box)
    end
    nextBox()
  end

  -- M8 wave 3: engageTrainer's own start of a deferred map trainer's
  -- battle, run when Cobblemon reports a win. An engageTrainer caller may
  -- hand the battle its own loss line and what goes with it
  -- (endBattleText, endBattleSound, endBattleIsReward, endBattleSoundPage;
  -- OC:4201-4221, :4253-4260): Silph Co Giovanni
  -- (data/scripts/story.lua:885-901), the Game Corner rocket
  -- (story3.lua:597-643), the Karate Master (story4.lua:233-258), the Nugget
  -- Bridge recruiter (story4.lua:668-679) and every gym leader's badge lines
  -- (data/scripts/gyms.lua:94-131). Those live only in the start's closure:
  -- the trainer.before_battle context carries none of them (OC:4286-4291)
  -- and the mod sandbox has no debug library. So the continuation captured
  -- when the battle was deferred runs the start (continueBattle(),
  -- OC:4179-4188 -> startBattle, OC:4232-4280), with two engine functions
  -- shadowed for that one synchronous call and put back at once, error or
  -- not: BattleState.newTrainer (looked up on the module table at the call,
  -- OC:4223, :4235) hands back a stand-in instead of building a battle
  -- (src/battle/BattleState.lua:925-1003: nothing is marked in the Pokedex,
  -- no pic or cry loads), and OverworldState.pushBattle (OC:4279) keeps what
  -- the start filled in instead of pushing it (OC:1261-1295; the overworld
  -- on the stack is the module table itself, src/core/Game.lua:129, :192).
  -- The stand-in then carries what the battle screen would use -- the
  -- substituted endBattleText (the caller's line, else the header's won
  -- line), endBattleSound, endBattleSoundPage, rewardDialogueShown -- and
  -- the encounter's own onFinish (OC:4261-4278: defeatedTrainers, the
  -- header's event, checkVictoryRewards with rewardDialogueShown,
  -- afterBattle, the caller's onDone), which applies the win
  -- (finishTrainer). The start's other writes are harmless here: the
  -- sighting suppression is cleared (OC:4233) and the meet sting stays
  -- silent while the world is held engaging (OC:4227-4231). A loss and an
  -- abort never run it: they keep M5's path, continue({cancel = true}).
  -- Returns the stand-in (or whatever the start pushed), or nil.
  local function startTrainerBattle(rec)
    local continueBattle = rec.continueBattle
    local ow = rec.ow
    if type(continueBattle) ~= "function" or type(ow) ~= "table" or ow ~= engine.overworld() then return nil end
    local BattleState = try("src.battle.BattleState")
    if not (BattleState and type(ow.pushBattle) == "function") then return nil end
    local standIn = { kind = "trainer", oppClass = rec.cls, partyIndex = rec.party or 1 }
    local kept
    local function newShadow(_, oppClass, partyIndex)
      standIn.oppClass, standIn.partyIndex = oppClass, partyIndex or 1
      return standIn
    end
    local function pushShadow(_, battle) kept = battle end
    local ownNew, ownPush = rawget(BattleState, "newTrainer"), rawget(ow, "pushBattle")
    rawset(BattleState, "newTrainer", newShadow)
    rawset(ow, "pushBattle", pushShadow)
    rec.continueBattle = nil -- spent either way (OC:4180-4181)
    local ok, err = pcall(continueBattle)
    if rawget(ow, "pushBattle") == pushShadow then rawset(ow, "pushBattle", ownPush) end
    if rawget(BattleState, "newTrainer") == newShadow then rawset(BattleState, "newTrainer", ownNew) end
    if ok and type(kept) == "table" and type(kept.onFinish) == "function" then
      trainerCounts.started = trainerCounts.started + 1
      return kept
    end
    trainerCounts.failed = trainerCounts.failed + 1
    warnAt("engine.trainer_start_failed", not ok and err or nil)
    return nil
  end

  local function finishTrainer(rec, result)
    local ow, save = rec.ow, engine.save()
    if type(ow) == "table" and ow == engine.overworld() and type(save) == "table" then
      if result == "win" and type(rec.built) == "table" then
        -- M8 wave 3: the encounter's own win (OC:4261-4278), the world let
        -- go first as the battle screen's exit would
        if rec.heldEngaging then ow.engaging = false end
        local ok, err = pcall(rec.built.onFinish, "win")
        if not ok then warnAt("engine.trainer_finish_failed", err) end
        return
      end
      if result == "win" then
        if rec.npcId and type(save.defeatedTrainers) == "table" then save.defeatedTrainers[rec.npcId] = true end
        if rec.event then
          local Flags = try("src.script.Flags")
          if Flags and type(Flags.set) == "function" then pcall(Flags.set, save, rec.event)
          elseif type(save.flags) == "table" then save.flags[rec.event] = true end
        end
        if type(ow.checkVictoryRewards) == "function" then
          local ok, err = pcall(ow.checkVictoryRewards, ow, rec.cls, rec.party, false)
          if not ok then warnAt("engine.victory_rewards_failed", err) end
        end
        if type(ow.afterBattle) == "function" then
          local ok, err = pcall(ow.afterBattle, ow, "win", rec.fake)
          if not ok then warnAt("engine.after_battle_failed", err) end
        end
      elseif result == "lose" and type(ow.afterBattle) == "function" then
        local ok, err = pcall(ow.afterBattle, ow, "lose", rec.fake)
        if not ok then warnAt("engine.after_battle_failed", err) end
      end
      if rec.heldEngaging then ow.engaging = false end
    end
    if type(rec.continueBattle) == "function" then
      local ok, err = pcall(rec.continueBattle, { cancel = true })
      if not ok then warnAt("engine.continue_failed", err) end
    end
  end

  -- M6: a surfing or fishing encounter held for Cobblemon (engine.encounter*
  -- below). The world lets go, then the engine's own wild-battle ending
  -- runs as the native battle's onFinish would (OC:4961, :2542):
  -- OverworldState:afterBattle (OC:5334-5375) gives a wild battle its
  -- encounter grace steps (:5338-5340), and "lose" blacks out (:5346-5364).
  -- "caught" needs nothing more here: the Pokemon is Cobblemon's and comes
  -- back through party.sync, its Pokedex entry through dex.sync. "abort"
  -- applies nothing at all.
  local function finishWild(rec, result)
    local ow = rec.ow
    if type(ow) ~= "table" or ow ~= engine.overworld() then return end
    if rec.heldEngaging then ow.engaging = false end
    if result ~= "abort" and type(ow.afterBattle) == "function" then
      local ok, err = pcall(ow.afterBattle, ow, result, rec.fake)
      if not ok then warnAt("engine.after_battle_failed", err) end
    end
  end

  -- Leaving a battle screen brings the map's song back: BattleState's exit
  -- calls Music.restoreMap (src/battle/BattleState.lua:5963-5970, unless a
  -- final battle was won; and :1878 for a battle that ends before it
  -- starts; src/core/Music.lua:545, whose `reason` argument is optional). A
  -- battle Cobblemon fought never had that exit, so the trainer's sighting
  -- theme (OC:4429-4430, :4096) would play on (M7).
  local function restoreMapMusic()
    local Music, data = try("src.core.Music"), gameData()
    if Music and type(Music.restoreMap) == "function" and data then
      local ok, err = pcall(Music.restoreMap, data)
      if not ok then warnAt("engine.music_restore_failed", err) end
    end
  end

  -- M8: a direct battle (engine.worldTalk / directPushed below) ends the
  -- way BattleState:finish ends it (src/battle/BattleState.lua:5912-5993):
  -- the battle's own onFinish continuation with the result -- the
  -- talk-path static's removal and afterBattle (OC:3609-3619), the ghost
  -- Marowak's departure or its step off the trigger
  -- (data/scripts/story3.lua:156-190), Giovanni's hideout aftermath
  -- (story3.lua:484-507). "abort" ends it as a flight ("run"): nothing is
  -- won, the object or trigger stays, so it can be fought again.
  local function finishDirect(rec, result)
    local ow = rec.ow
    if type(ow) ~= "table" or ow ~= engine.overworld() or rec.save ~= engine.save() then return end
    if rec.heldEngaging then ow.engaging = false end
    local battle = rec.battle
    local onFinish = type(battle) == "table" and battle.onFinish or nil
    if type(onFinish) ~= "function" then return end
    local word = result == "abort" and "run" or result
    battle.result = word
    local ok, err = pcall(onFinish, word)
    if not ok then warnAt("engine.direct_finish_failed", err) end
  end

  -- Applies a Cobblemon result to the pending battle. result is the
  -- engine's word: "win", "lose", "run", "caught", or "abort" (nothing
  -- happened: the script stops / the encounter is cancelled, so it can be
  -- fought again). Returns true, or false when id isn't the battle waiting.
  function engine.battleFinish(id, result)
    local rec, st = liveBattle()
    if not rec or rec.id ~= id or rec.state ~= "waiting" then return false end
    rec.state, rec.result = "texts", result
    -- M8 wave 3: a map trainer's win runs engageTrainer's own start first,
    -- for the loss line (and its sound, its reward flag) the caller handed
    -- the battle; without it the header's line captured at deferral stays
    if result == "win" and rec.path == "trainer" then
      local built = startTrainerBattle(rec)
      if built then
        rec.built = built
        rec.endText = type(built.endBattleText) == "string" and built.endBattleText or nil
        rec.endSound = type(built.endBattleSound) == "string" and built.endBattleSound or nil
        rec.endSoundPage = tonumber(built.endBattleSoundPage)
      end
    end
    local texts, prize = resultTexts(rec, result)
    local save = engine.save()
    if prize > 0 and type(save) == "table" then save.money = (tonumber(save.money) or 0) + prize end
    -- M8 (P22): the victory theme under a win's texts
    if result == "win" and #texts > 0 and rec.music then playVictoryMusic(rec) end
    pushTexts(texts, function()
      -- as the battle screen's exit would, before its onFinish (M7); M8:
      -- also after an abort, when the battle's theme was playing; never
      -- after a won final battle (BattleState:finish, :5963-5970: the Hall
      -- of Fame sequence owns the music from there)
      local wonFinal = result == "win" and rec.music == "final"
      if not wonFinal and (result ~= "abort" or rec.music) then restoreMapMusic() end
      if rec.path == "script" then
        rec.state = "done" -- the script's waitingCheck resumes it
      else
        rec.state = "consumed"
        if st.battle == rec then st.battle = nil end
        if rec.path == "wild" then
          finishWild(rec, result)
        elseif rec.path == "direct" then
          finishDirect(rec, result)
        else
          finishTrainer(rec, result)
        end
      end
    end)
    return true
  end

  -- ---- gifts and heals

  local function giftFacts(mon)
    local moves = {}
    for _, mv in ipairs(type(mon.moves) == "table" and mon.moves or {}) do
      moves[#moves + 1] = type(mv) == "table" and engine.moveNumOf(mv.id) or 0
    end
    local dvs = type(mon.dvs) == "table" and mon.dvs or {}
    return {
      species = mon.species, dex = engine.dexOf(mon.species), level = tonumber(mon.level), moves = moves,
      dvs = { atk = tonumber(dvs.attack), def = tonumber(dvs.defense), spd = tonumber(dvs.speed),
              spc = tonumber(dvs.special) },
      nick = type(mon.nickname) == "string" and mon.nickname or nil,
      ot = type(mon.ot) == "string" and mon.ot or nil,
    }
  end

  -- give_pokemon (Commands.lua:808-870) runs as usual -- Party.add or the
  -- box, the Pokedex, the native nickname prompt (askNickname, :762-795) --
  -- and the gift is captured once it returns: the party member that wasn't
  -- there before (Party.add appends, src/pokemon/Party.lua:7-13), or the box
  -- deposit's last entry (Boxes.deposit, src/pokemon/Boxes.lua:33-43; taken
  -- out of the Gen 1 box: Cobblemon's PC keeps it). ctx.lastCheck is
  -- give_pokemon's carry (:829-838, kept by askNickname :764, :789). A
  -- party.sync waits while a give is under way (engine.partySafe).
  local function scriptGive(nextFn, ctx, name, args)
    local save, st = ctx.save, bridgeState()
    if type(save) ~= "table" or type(save.party) ~= "table" or not st then return nextFn(ctx, name, args) end
    local before = {}
    for _, mon in ipairs(save.party) do before[mon] = true end
    local flags = type(save.flags) == "table" and save.flags or {}
    local ow = ctx.overworld
    local mapId = type(ow) == "table" and type(ow.map) == "table" and ow.map.id or nil
    local source = "gift"
    if flags.EVENT_GOT_STARTER ~= true and #save.party == 0 then
      source = "starter"
    elseif mapId == "GAME_CORNER_PRIZE_ROOM" then
      source = "prize"
    end
    local giving = { ow = ow, save = save }
    st.giving[giving] = true
    local ok, jump = pcall(nextFn, ctx, name, args)
    st.giving[giving] = nil
    if not ok then error(jump, 0) end
    if ctx.lastCheck == true then
      local mon, box
      if type(ctx.boxNum) == "number" then
        local list = type(save.boxes) == "table" and save.boxes[ctx.boxNum] or nil
        if type(list) == "table" and type(list[#list]) == "table" then
          mon, box = table.remove(list), ctx.boxNum
        end
      else
        for _, m in ipairs(save.party) do
          if not before[m] then mon = m end
        end
      end
      if type(mon) == "table" then
        st.serial = st.serial + 1
        st.gifts[#st.gifts + 1] = {
          give = ("g-%s-%d"):format(st.token or "x", st.serial), serial = st.serial, state = "new", syncs = 0,
          box = box ~= nil, source = source, save = save, facts = giftFacts(mon),
          mon = (box == nil) and mon or nil,
        }
      end
    end
    return jump
  end

  -- The script.command wrapper's body (ScriptRunner.lua:168-172: next is
  -- the command itself; it may yield the script's coroutine).
  -- ---- party operations for Minecraft (M8): trades, the daycare
  --
  -- Changes the hidden game makes to the party that Cobblemon must follow,
  -- queued in the process-lifetime slot until a client takes them:
  --   take      party.take{uid, reason}: an in-game trade sent this member
  --             away (Commands.trade, src/script/Commands.lua:1093-1184)
  --   deposit   daycare.deposit{uid}: the daycare took it (the gentleman's
  --             talk, data/scripts/story2.lua:1155-1180)
  --   withdraw  daycare.withdraw{uid, steps}: it came back (:1132-1150)
  -- Each goes out once, and again after every snapshot until a party.sync
  -- confirms it: a take or a deposit by the uid missing from Cobblemon's
  -- party, a withdrawal by the uid in it. Until then a take's uid is left
  -- out of every sync (a sync from before the take would bring the traded
  -- member back), so is the uid the daycare holds, and a withdrawn member
  -- is kept in the hidden party like a gift (engine.applyParty).

  local opCounts = { trades = 0, tradeNoUid = 0, deposits = 0, withdraws = 0, depositNoUid = 0 }

  local function queueOp(op)
    local st = bridgeState()
    if not st then return nil end
    st.ops = type(st.ops) == "table" and st.ops or {}
    st.serial = st.serial + 1
    op.id, op.state, op.syncs = st.serial, "new", 0
    st.ops[#st.ops + 1] = op
    return op.id
  end

  local function opsOf(st, save)
    local list = type(st) == "table" and type(st.ops) == "table" and st.ops or {}
    for i = #list, 1, -1 do
      if list[i].save ~= save then table.remove(list, i) end -- a new game or CONTINUE
    end
    return list
  end

  -- The pending operations of this save, as plain values, oldest first.
  function engine.ops()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    local out = {}
    for _, op in ipairs(opsOf(st, engine.save())) do
      out[#out + 1] = { id = op.id, serial = op.id, kind = op.kind, uid = op.uid, reason = op.reason,
                        steps = op.steps, state = op.state, sentGen = op.sentGen, syncs = op.syncs }
    end
    return out
  end

  local function opNamed(id)
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    for i, op in ipairs(type(st) == "table" and type(st.ops) == "table" and st.ops or {}) do
      if op.id == id then return op, i, st end
    end
    return nil
  end
  function engine.opSent(id, generation)
    local op = opNamed(id)
    if op then op.state, op.sentGen = "sent", generation end
  end
  function engine.opSynced(id)
    local op = opNamed(id)
    if op then op.syncs = op.syncs + 1 end
  end
  function engine.opDone(id)
    local op, i, st = opNamed(id)
    if op then table.remove(st.ops, i) end
  end

  -- The uids no party.sync may put back into the hidden party right now:
  -- the pending takes and the member the daycare holds.
  function engine.excludedUids()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    local save = engine.save()
    local out = {}
    for _, op in ipairs(opsOf(st, save)) do
      if op.kind == "take" and op.uid then out[op.uid] = true end
    end
    local dc = type(st) == "table" and st.daycare or nil
    if type(dc) == "table" and dc.save == save and dc.uid then out[dc.uid] = true end
    return out
  end

  -- Commands.trade (src/script/Commands.lua:1093-1184) runs natively -- the
  -- YES/NO, the party menu, the TradeAnim, the texts -- and the trade is
  -- read from the party afterwards: the member that left (table.remove,
  -- :1161) and the one that arrived at the end (Pokemon.new with the
  -- trade's nickname, traded, OT "TRAINER", :1149-1155). The member sent
  -- goes as a take (by its Cobblemon uid), the one received as a gift with
  -- source "trade" (its nickname and OT). A party.sync waits while the
  -- trade is under way (engine.partySafe), as it does for give_pokemon.
  local function scriptTrade(nextFn, ctx, name, args)
    local save, st = ctx.save, bridgeState()
    if type(save) ~= "table" or type(save.party) ~= "table" or not st then return nextFn(ctx, name, args) end
    local before = {}
    for _, mon in ipairs(save.party) do before[mon] = true end
    local giving = { ow = ctx.overworld, save = save }
    st.giving[giving] = true
    local ok, jump = pcall(nextFn, ctx, name, args)
    st.giving[giving] = nil
    if not ok then error(jump, 0) end
    local now = {}
    for _, mon in ipairs(save.party) do now[mon] = true end
    local sent, received
    for mon in pairs(before) do
      if not now[mon] then sent = mon end
    end
    for _, mon in ipairs(save.party) do
      if not before[mon] then received = mon end
    end
    if type(sent) ~= "table" or type(received) ~= "table" then return jump end
    opCounts.trades = opCounts.trades + 1
    local uid = engine.uidOf(sent)
    if uid then
      queueOp({ kind = "take", uid = uid, reason = "trade", save = save })
    else
      -- a member Cobblemon never named: a gift not yet sent is simply
      -- forgotten; anything else is counted
      for i, g in ipairs(st.gifts) do
        if g.mon == sent and g.state == "new" then table.remove(st.gifts, i) break end
      end
      opCounts.tradeNoUid = opCounts.tradeNoUid + 1
      if log then log.warn("engine.trade_no_uid", { count = opCounts.tradeNoUid }) end
    end
    st.serial = st.serial + 1
    st.gifts[#st.gifts + 1] = {
      give = ("g-%s-%d"):format(st.token or "x", st.serial), serial = st.serial, state = "new", syncs = 0,
      box = false, source = "trade", save = save, facts = giftFacts(received), mon = received,
    }
    return jump
  end

  function engine.scriptCommand(nextFn, ctx, name, args, bridge)
    if type(ctx) ~= "table" then return nextFn(ctx, name, args) end
    if SCRIPT_BATTLES[name] then return scriptBattle(nextFn, ctx, name, args, bridge) end
    if name == "give_pokemon" then return scriptGive(nextFn, ctx, name, args) end
    if name == "trade" then return scriptTrade(nextFn, ctx, name, args) end
    if name == "heal_party" then
      -- HealParty (Commands.lua:751-756): Cobblemon's party heals too
      local jump = nextFn(ctx, name, args)
      local st = bridgeState()
      if st then st.heals = st.heals + 1 end
      return jump
    end
    if name == "record_hall_of_fame" then
      -- M8: the Hall of Fame heals the hidden party once THE END is up
      -- (Commands.record_hall_of_fame, Commands.lua:917-940); Cobblemon's
      -- party heals too, and the save it writes there is a menu save
      -- (save.writing, M7)
      local st = bridgeState()
      if st then st.heals = st.heals + 1 end
      return nextFn(ctx, name, args)
    end
    return nextFn(ctx, name, args)
  end

  -- Captured gifts of this save, as plain values.
  function engine.gifts()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    local out = {}
    if type(st) ~= "table" then return out end
    local save = engine.save()
    for i = #st.gifts, 1, -1 do
      if st.gifts[i].save ~= save then table.remove(st.gifts, i) end -- a new game or CONTINUE
    end
    for _, g in ipairs(st.gifts) do
      local f = g.facts
      out[#out + 1] = {
        give = g.give, serial = g.serial, state = g.state, sentGen = g.sentGen, syncs = g.syncs, box = g.box,
        source = g.source, dex = f.dex, level = f.level, moves = f.moves, dvs = f.dvs, nick = f.nick, ot = f.ot,
      }
    end
    return out
  end

  local function giftNamed(give)
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    for i, g in ipairs(type(st) == "table" and st.gifts or {}) do
      if g.give == give then return g, i, st end
    end
    return nil
  end
  function engine.giftSent(give, generation)
    local g = giftNamed(give)
    if g then g.state, g.sentGen = "sent", generation end
  end
  function engine.giftSynced(give)
    local g = giftNamed(give)
    if g then g.syncs = g.syncs + 1 end
  end
  -- Forgets a gift (confirmed by a party.sync, or given up on).
  function engine.giftDone(give)
    local g, i, st = giftNamed(give)
    if g then table.remove(st.gifts, i) end
  end

  -- Heals for Cobblemon's party (party.heal). kind "blackout" (M7): the
  -- blackout heals the hidden party before its warp to the heal point
  -- (OC:5352-5364), but Minecraft gets the heal after the warp's arrival:
  -- the heal waits until releaseHeals() (main.lua calls it when the
  -- blackout's warp arrive goes out) or HEAL_HOLD_STEPS live steps.
  function engine.queueHeal(kind)
    local st = bridgeState()
    if not st then return end
    st.heals = st.heals + 1
    if kind == "blackout" then st.healHold = 0 end
  end
  function engine.releaseHeals()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    if type(st) == "table" then st.healHold = nil end
  end
  function engine.takeHeals()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    if type(st) ~= "table" then return 0 end
    if st.healHold ~= nil then
      st.healHold = st.healHold + 1
      if st.healHold <= Gen1.HEAL_HOLD_STEPS then return 0 end
      st.healHold = nil
    end
    local n = st.heals
    st.heals = 0
    return n
  end

  -- ---- the visitor stand-in (docs/specs/FOREIGN_POKEMON_PLAN.md §3, FP2)
  --
  -- One original species, KB_VISITOR ("VISITOR"), registered from the mod's
  -- entry chunk through the public registry, before the registries freeze
  -- (src/mods/Loader.lua:2223-2227; the record's schema, src/mods/Schemas.lua
  -- :1732-1768). Its dex 999 is above constants.dexSize, so the Pokedex never
  -- lists it (src/ui/PokedexMenu.lua:55-99); no index, no evolutions, no
  -- learnset, catch rate 0 (FP0 ran it on Blue: PROGRESS.md "Foreign
  -- Pokemon: spike FP0"). A party member Blue can't show as itself is a
  -- record of this species carrying the member's name, level, HP, stats and
  -- field moves; nothing ever sets a Pokedex flag for it.

  local VISITOR = "KB_VISITOR"
  local visitorAsked, visitorAccepted = false, false
  -- The field moves Blue's party menu offers (GetMonFieldMoves, src/ui/PartyMenu.lua:867-886)
  local FIELD_MOVES = { FLY = true, FLASH = true, CUT = true, SURF = true, STRENGTH = true, SOFTBOILED = true,
                        TELEPORT = true, DIG = true }

  -- Registers the stand-in with its art under `dir` (the mod's assets/visitor);
  -- true when the registry took it. Called once from the entry chunk.
  function engine.registerVisitor(content, dir)
    visitorAsked = true
    local reg = type(content) == "table" and content.pokemon or nil
    if type(reg) ~= "table" or type(reg.register) ~= "function" then return false end
    local art = type(dir) == "string" and dir or ""
    local icon = { image = art .. "/gen1_icon.png", frames = 2 }
    local ok, res = pcall(reg.register, reg, VISITOR, {
      id = VISITOR, name = "VISITOR", dex = 999, types = { "NORMAL" },
      baseStats = { hp = 50, attack = 50, defense = 50, speed = 50, special = 50 },
      catchRate = 0, baseExp = 0, level1Moves = {}, growthRate = "MEDIUM_FAST", learnset = {}, evolutions = {},
      spriteFront = art .. "/gen1_front.png", spriteBack = art .. "/gen1_back.png", frontSize = 5, icon = icon,
    })
    visitorAccepted = ok and res ~= nil and res ~= false
    if visitorAccepted and type(content.icons) == "table" and type(content.icons.register) == "function" then
      pcall(content.icons.register, content.icons, VISITOR, icon)
    end
    if not visitorAccepted then warnAt("party.visitor_refused", ok and "nil" or res) end
    if visitorAccepted then pcall(engine.wrapExport) end
    return visitorAccepted
  end

  -- The stand-in's species id once the live data holds it, else nil (then a
  -- member Blue lacks is left out, Q13).
  function engine.visitorSpecies()
    local data = gameData()
    if not visitorAccepted or not (data and type(data.pokemon) == "table") then return nil end
    return type(data.pokemon[VISITOR]) == "table" and VISITOR or nil
  end

  -- R2: Gen 1 has no forms, so any form Cobblemon names is one Blue lacks.
  function engine.formKnown(species, form) return form == nil end

  -- §4.2: the move ids a visitor keeps (the field moves Blue offers).
  function engine.visitorMoves() return FIELD_MOVES end

  local function isVisitor(mon) return type(mon) == "table" and mon.species == VISITOR end

  -- FP6 (§4.9): the Pokedex never counts the stand-in (save.pokedex.seen/owned by species id; Oak's aides count the
  -- keys, src/script/Commands.lua:213-220)
  function engine.dexSweep()
    local save = engine.save()
    local dex = save and type(save.pokedex) == "table" and save.pokedex or nil
    if not dex then return 0 end
    local n = 0
    for _, k in ipairs({ "seen", "owned" }) do
      if type(dex[k]) == "table" and dex[k][VISITOR] then
        dex[k][VISITOR] = nil
        n = n + 1
      end
    end
    return n
  end

  -- §4.14 (FP2): a box never keeps a visitor (Cobblemon holds the real
  -- Pokemon; SaveData.validate's reclaim, src/core/SaveData.lua:3072-3076,
  -- may put one in a box when the party was full). Returns how many went.
  function engine.visitorSweep()
    local save = engine.save()
    local n = 0
    for _, box in ipairs(save and type(save.boxes) == "table" and save.boxes or {}) do
      if type(box) == "table" then
        for i = #box, 1, -1 do
          if isVisitor(box[i]) then table.remove(box, i) n = n + 1 end
        end
      end
    end
    return n
  end

  -- §4.14 (FP2): the Hall of Fame's visitor members (Commands.record_hall_of_fame,
  -- src/script/Commands.lua:920-927: species, level, nickname per member),
  -- as { entry, slot, level, nickname } rows for mod.save. A save opened
  -- without the mod blanks them for good (SaveData.validate, :3112-3123).
  function engine.hofCopy()
    local save = engine.save()
    local out = {}
    for e, entry in ipairs(save and type(save.hallOfFame) == "table" and save.hallOfFame or {}) do
      for s, mon in ipairs(type(entry) == "table" and entry or {}) do
        if isVisitor(mon) then
          out[#out + 1] = { entry = e, slot = s, level = mon.level, nickname = mon.nickname }
        end
      end
    end
    return out
  end

  -- Puts the copy's visitors back where the entry still holds a blanked
  -- member (species nil) at that place. Returns how many came back.
  function engine.hofRestore(copy)
    local save = engine.save()
    local fame = save and type(save.hallOfFame) == "table" and save.hallOfFame or nil
    if not (fame and type(copy) == "table" and engine.visitorSpecies()) then return 0 end
    local n = 0
    for _, row in ipairs(copy) do
      local entry = type(row) == "table" and fame[row.entry] or nil
      local mon = type(entry) == "table" and entry[row.slot] or nil
      if type(mon) == "table" and mon.species == nil then
        mon.species = VISITOR
        if mon.nickname == nil then mon.nickname = row.nickname end
        n = n + 1
      end
    end
    return n
  end

  -- §4.14 (FP2): a cartridge export never carries a visitor: GenSave.encode
  -- (src/save_convert/GenSave.lua:1410, called by SaveConvert.lua:699)
  -- refuses a species with no Gen 1 index (:664-669), so the export gets a
  -- copy of the save without them (party, boxes, daycare, Hall of Fame). One
  -- wrapper per process (a hot reload keeps the first).
  local function withoutVisitors(save)
    if type(save) ~= "table" then return save end
    local copy = {}
    for k, v in pairs(save) do copy[k] = v end
    local function list(mons)
      local out = {}
      for _, mon in ipairs(type(mons) == "table" and mons or {}) do
        if not isVisitor(mon) then out[#out + 1] = mon end
      end
      return out
    end
    copy.party = list(save.party)
    if type(save.boxes) == "table" then
      copy.boxes = {}
      for i, box in ipairs(save.boxes) do copy.boxes[i] = list(box) end
    end
    if type(save.daycare) == "table" and isVisitor(save.daycare.mon) then
      copy.daycare = {}
      for k, v in pairs(save.daycare) do copy.daycare[k] = v end
      copy.daycare.mon = nil
    end
    if type(save.hallOfFame) == "table" then
      copy.hallOfFame = {}
      for i, entry in ipairs(save.hallOfFame) do copy.hallOfFame[i] = list(entry) end
    end
    return copy
  end
  function engine.wrapExport()
    local GenSave = try("src.save_convert.GenSave")
    if not (GenSave and type(GenSave.encode) == "function") or rawget(GenSave, "kbVisitorEncode") then return false end
    local encode = GenSave.encode
    GenSave.kbVisitorEncode = encode
    GenSave.encode = function(save, ...) return encode(withoutVisitors(save), ...) end
    return true
  end

  -- ---- the shadow party (party.sync, Cobblemon authoritative)

  -- Cobblemon uids of party members, by member (weak keys); rebuilt from
  -- mod.save after a reload (engine.adoptUids).
  local monUid = setmetatable({}, { __mode = "k" })
  -- FP2: the stones each synced member evolves with in Cobblemon, as Gen 1
  -- item symbols (party.sync `stones`, §4.4), by member; nil when the sync
  -- didn't say (then the species' own evolutions answer)
  local monStones = setmetatable({}, { __mode = "k" })
  local monCanLearn = setmetatable({}, { __mode = "k" }) -- FP4: the sync's canLearn as a set of move ids

  function engine.uidOf(mon) return type(mon) == "table" and monUid[mon] or nil end

  -- The uids of save.party in order (false where unknown) and their set.
  function engine.partyUids()
    local save = engine.save()
    local list, set = {}, {}
    for i, mon in ipairs(save and type(save.party) == "table" and save.party or {}) do
      local uid = monUid[mon]
      list[i] = uid or false
      if uid then set[uid] = true end
    end
    return list, set
  end

  -- After a reload or CONTINUE: uids by party position (the list saved with
  -- the last sync). Returns how many were adopted.
  function engine.adoptUids(list)
    local save = engine.save()
    local n = 0
    if type(list) ~= "table" or not (save and type(save.party) == "table") then return 0 end
    for i, mon in ipairs(save.party) do
      local uid = list[i]
      if type(uid) == "string" and monUid[mon] == nil then
        monUid[mon] = uid
        n = n + 1
      end
    end
    return n
  end

  -- A sync may be applied now: an overworld is up, no give_pokemon is under
  -- way (it holds its new party member while the nickname prompt runs), no
  -- TM/HM is being taught (M6: the watch below compares the member's moves)
  -- and no native battle (BattleState, isBattleState :49) holds party
  -- members.
  function engine.partySafe()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    local ow, save = engine.overworld(), engine.save()
    if not ow then return false end
    for giving in pairs(type(st) == "table" and st.giving or {}) do
      -- one whose world is gone (the title, New Game, CONTINUE) never ends
      if giving.ow ~= ow or giving.save ~= save then st.giving[giving] = nil else return false end
    end
    local watch = type(st) == "table" and st.tmWatch or nil
    if type(watch) == "table" and watch.save == save and watch.ow == ow then return false end
    for _, s in ipairs(game.stack.states) do
      if type(s) == "table" and s.isBattleState then return false end
    end
    return true
  end

  -- Brings one party member in line with a sync entry: species and level
  -- (stats from Stats.calc with its own DVs, src/pokemon/Stats.lua:35-48;
  -- exp from Growth.expForLevel, src/pokemon/Growth.lua:27), moves (PP kept
  -- for known moves, full for new ones, as Pokemon.new fills them, :71-74),
  -- HP scaled to the Gen 1 maximum, status, nickname.
  local function updateMon(mon, e, data)
    local def = data.pokemon[e.species]
    local Stats, Growth = try("src.pokemon.Stats"), try("src.pokemon.Growth")
    local levelChanged = mon.level ~= e.level
    if mon.species ~= e.species or levelChanged or type(mon.stats) ~= "table" then
      mon.species, mon.level = e.species, e.level
      if Stats and type(Stats.calc) == "function" then
        local ok, stats = pcall(Stats.calc, def, e.level, type(mon.dvs) == "table" and mon.dvs or {}, mon.statExp)
        if ok and type(stats) == "table" then mon.stats = stats end
      end
      if Growth and type(Growth.expForLevel) == "function" then
        local ok, base = pcall(Growth.expForLevel, def.growthRate, e.level)
        local okNext, nextBase = pcall(Growth.expForLevel, def.growthRate, e.level + 1)
        local exp = tonumber(mon.exp)
        if ok and type(base) == "number" and (levelChanged or exp == nil or exp < base
            or (okNext and type(nextBase) == "number" and e.level < 100 and exp >= nextBase)) then
          mon.exp = base
        end
      end
    end
    if #e.moves > 0 then
      local old = {}
      for _, mv in ipairs(type(mon.moves) == "table" and mon.moves or {}) do
        if type(mv) == "table" and mv.id ~= nil then old[mv.id] = mv end
      end
      local moves = {}
      for i, id in ipairs(e.moves) do
        local md = type(data.moves) == "table" and data.moves[id] or nil
        moves[i] = old[id] or { id = id, pp = type(md) == "table" and tonumber(md.pp) or 0 }
      end
      mon.moves = moves
    end
    local maxhp = type(mon.stats) == "table" and tonumber(mon.stats.hp) or 1
    if e.hp <= 0 then
      mon.hp = 0
    else
      mon.hp = math.max(1, math.min(maxhp, math.floor(e.hp * maxhp / e.maxhp + 0.5)))
    end
    mon.status = e.status
    if mon.status ~= "SLP" then mon.sleepTurns = nil end
    mon.nickname = e.nick
    -- FP2 (Q20): Cobblemon's six stats pinned on every record that has them;
    -- Gen 1's special is Cobblemon's special attack. Stats.ensure keeps a
    -- complete block (src/pokemon/Stats.lua:58-74) and nothing recomputes a
    -- synced member (no hidden battle, level-up or candy runs on it)
    if type(e.stats) == "table" then
      local p = e.stats
      mon.stats = { hp = p.hp, attack = p.atk, defense = p.def, speed = p.spe, special = p.spa }
      mon.hp = e.hp <= 0 and 0 or math.max(1, math.min(p.hp, e.hp))
    end
    if type(e.stones) == "table" then
      local set = {}
      for _, id in ipairs(e.stones) do
        local item = itemsCatalogue and type(itemsCatalogue.items) == "table" and itemsCatalogue.items[id] or nil
        local sym = type(item) == "table" and type(item.symbols) == "table" and item.symbols.gen1 or nil
        if type(sym) == "string" then set[sym] = true end
      end
      monStones[mon] = set
    else
      monStones[mon] = nil
    end
    -- FP4 (§4.4): the machine moves Cobblemon's learnset allows, by Gen 1 move id
    if type(e.canLearn) == "table" then
      local set = {}
      for _, n in ipairs(e.canLearn) do
        local id = engine.moveIdOfNum(n)
        if id then set[id] = true end
      end
      monCanLearn[mon] = set
    else
      monCanLearn[mon] = nil
    end
  end

  -- Rebuilds save.party from a party.sync (Cobblemon is authoritative):
  -- entries = { { uid, species, level, moves = {ids}, hp, maxhp, status?,
  -- nick?, give? } } in Cobblemon's order. A known uid keeps its member (in
  -- place); `give` names a captured gift whose member this entry is; others
  -- are created with Pokemon.new (Pokemon.lua:60-88) and stamped with the
  -- player as OT (BattleState.stampOT, :698-705). Gifts Cobblemon hasn't
  -- confirmed yet stay after them, so scripts still find them. The party
  -- table is refilled in place (callers hold save.party itself). Returns
  -- counts.
  function engine.applyParty(entries)
    local save, data, st = engine.save(), gameData(), bridgeState()
    if not (save and type(save.party) == "table" and data and st) then return nil end
    local Pokemon = try("src.pokemon.Pokemon")
    local BattleState = try("src.battle.BattleState")
    local byUid = {}
    for _, mon in ipairs(save.party) do
      local uid = monUid[mon]
      if uid then byUid[uid] = mon end
    end
    local res = { created = 0, updated = 0, matched = 0, kept = 0, failed = 0 }
    local out, seen = {}, {}
    for _, e in ipairs(entries) do
      local mon, how = byUid[e.uid], "updated"
      if e.give ~= nil then
        -- the gift this entry is: its member, unless the uid is known already
        local g = giftNamed(e.give)
        if mon == nil and g and type(g.mon) == "table" and g.save == save then mon, how = g.mon, "matched" end
        engine.giftDone(e.give)
      end
      if mon == nil or seen[mon] then
        local ok, fresh = false, nil
        if Pokemon and type(Pokemon.new) == "function" then ok, fresh = pcall(Pokemon.new, data, e.species, e.level) end
        if ok and type(fresh) == "table" then
          mon, how = fresh, "created"
          if BattleState and type(BattleState.stampOT) == "function" then pcall(BattleState.stampOT, save, mon) end
        else
          mon, how = nil, "failed"
        end
      end
      res[how] = res[how] + 1
      if mon then
        local ok = pcall(updateMon, mon, e, data)
        if not ok then res.failed = res.failed + 1 end
        monUid[mon] = e.uid
        seen[mon] = true
        out[#out + 1] = mon
      end
    end
    for _, g in ipairs(st.gifts) do
      if type(g.mon) == "table" and g.save == save and not seen[g.mon] and #out < 6 then
        out[#out + 1] = g.mon
        seen[g.mon] = true
        res.kept = res.kept + 1
      end
    end
    -- M8: a member back from the daycare that Cobblemon has not returned
    -- yet stays too (its withdrawal is still pending)
    for _, op in ipairs(type(st.ops) == "table" and st.ops or {}) do
      if op.kind == "withdraw" and op.save == save and type(op.mon) == "table" and not seen[op.mon] and #out < 6 then
        out[#out + 1] = op.mon
        seen[op.mon] = true
        monUid[op.mon] = op.uid
        res.kept = res.kept + 1
      end
    end
    local party = save.party
    for i = #party, 1, -1 do party[i] = nil end
    for i, mon in ipairs(out) do party[i] = mon end
    res.size = #party
    return res
  end

  -- Plain counts for debug.status.
  function engine.bridgeCounts()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    local save = engine.save()
    local known = 0
    for _, mon in ipairs(save and type(save.party) == "table" and save.party or {}) do
      if monUid[mon] then known = known + 1 end
    end
    local seen, owned = engine.dexCounts()
    return {
      partySize = save and type(save.party) == "table" and #save.party or 0,
      partyKnown = known,
      giftsPending = type(st) == "table" and #st.gifts or 0,
      healsPending = type(st) == "table" and st.heals or 0,
      battleHeld = liveBattle() ~= nil,
      dexSeen = seen,
      dexOwned = owned,
      itemUsesQueued = type(st) == "table" and type(st.itemUses) == "table" and #st.itemUses or 0,
      tmWatching = type(st) == "table" and type(st.tmWatch) == "table",
      -- M7
      healHeld = type(st) == "table" and st.healHold ~= nil,
      centerArmed = type(st) == "table" and type(st.centerSave) == "table",
      saveQueued = type(st) == "table" and type(st.saveQueued) == "table",
      pcHeld = type(st) == "table" and type(st.pc) == "table",
    }
  end

  -- ------------------------------------------------------------ wild encounters, Pokedex, bag, items (M6)
  --
  -- Wild encounters (PLAN section 4.1 "Battles", P8, P9). Every random
  -- encounter the overworld rolls goes through OverworldState:rollEncounter
  -- (OC:4769-4780), whose encounter.roll chain may return nil to suppress it
  -- (OC:4775-4779; docs/modding.md "Effective wild-encounter distribution").
  -- ctx.terrain is "grass" (a grass cell), "water" (surfing on water: the
  -- map's water table) or "indoor" (every step of an indoor map), in
  -- onStepComplete's order (OC:4790-4932: the roll :4926-4931; rollsIndoorEncounters :4782-4788 says whether an indoor map rolls at all). While a client or test session is
  -- connected:
  --   * a roll the engine would turn into a Safari Zone battle (a Safari game
  --     on a SAFARI-region map, OC:4953-4955) or an unidentifiable ghost
  --     battle (Map.ghostBattles without its item, OC:4946-4951) runs
  --     natively, shown on the mirror (P9);
  --   * grass and indoor rolls return nil without rolling: Cobblemon's
  --     habitat pools spawn those Pokemon in Minecraft (P8);
  --   * a water roll is the engine's own (the vanilla link, then
  --     encounter.species as OC:4776-4778 runs it), filtered by REPEL as
  --     OC:4934-4940 does, and then held for Cobblemon instead of the native
  --     battle (OC:4941-4963): the world waits (ow.engaging, as a trainer's
  --     encounter holds it) until battle.result.
  -- Fishing: OverworldState:goFishing (OC:2488-2520) rolls through
  -- encounter.fishing (OC:2495-2501). No bite ("Not even a nibble!", "nothing
  -- here") stays native, and so does a Safari Zone bite (OC:2539-2541). A
  -- bite plays its cast animation natively in the TextBox goFishing pushes
  -- right after the roll (OC:2507-2519); that one box's continuation
  -- (self.onDone, called when it pops: src/render/TextBox.lua:616-618,
  -- fishVerdict OC:2510-2512) is replaced with fishVerdict's bite text and
  -- its field resets (OC:2534-2536) and then the hold, so the native battle
  -- (:2424-2430) never starts.
  -- Without a client the hidden game fights everything itself (M5's rule).

  local DIR_DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
  -- BattleState:battleKind (src/battle/BattleState.lua:1909-1914): the
  -- special battles that stay native (P9), in the protocol's words
  local NATIVE_KINDS = { safari = "safari", ghost = "ghost", oldman = "old_man" }
  local encounterCounts = {
    grass = 0, indoor = 0, water = 0, fish = 0, safari = 0, ghost = 0, repel = 0, native = 0,
    declined = 0, noBite = 0, other = 0,
  }

  -- The process-lifetime slot also points at the current instance's bridge
  -- (live, battle): a fishing line cast before a hot reload is answered by
  -- the instance that is current when it bites.
  function engine.attachBridge(bridge)
    local st = bridgeState()
    if st then st.bridge = bridge end
  end

  local function currentBridge()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    return type(st) == "table" and st.bridge or nil
  end

  local function isLive(bridge)
    if type(bridge) ~= "table" or type(bridge.live) ~= "function" then return false end
    local ok, live = pcall(bridge.live)
    return ok and live == true
  end

  -- The engine would make this a Safari Zone battle: a Safari game under way
  -- (save.safari) on a SAFARI-region map (Map.inRegion,
  -- src/world/Map.lua:173-176; tested so at OC:4953 and :2539).
  local function safariHere(ow, save)
    local def = type(ow) == "table" and type(ow.map) == "table" and ow.map.def or nil
    if not (type(save) == "table" and save.safari and type(def) == "table") then return false end
    local Map = try("src.world.Map")
    if not (Map and type(Map.inRegion) == "function") then return false end
    local ok, yes = pcall(Map.inRegion, def, "SAFARI", "SAFARI_ZONE")
    return ok and yes == true
  end

  -- ... or an unidentifiable ghost battle: the map's ghostBattles
  -- (src/world/Map.lua:179-185: the Pokemon Tower floors) without its
  -- unlessItem in the bag (OC:4948-4951).
  local function ghostHere(ow, save)
    local def = type(ow) == "table" and type(ow.map) == "table" and ow.map.def or nil
    if type(def) ~= "table" then return false end
    local Map = try("src.world.Map")
    if not (Map and type(Map.ghostBattles) == "function") then return false end
    local ok, ghost = pcall(Map.ghostBattles, def)
    if not (ok and ghost) then return false end
    local inv = type(save) == "table" and type(save.inventory) == "table" and save.inventory or {}
    return not (type(ghost) == "table" and ghost.unlessItem and inv[ghost.unlessItem])
  end

  -- Plain facts about a wild Pokemon the engine rolled, for kb_battle: kind
  -- "surf" or "fish", the species as dex, its level, and the cell in front
  -- of the player (the water it surfaces from or bites in), on the
  -- player's map.
  local function wildFacts(ow, enc, kind)
    local data = gameData()
    local species, level = enc.species, tonumber(enc.level)
    if not (symbol(species) and level and data and type(data.pokemon) == "table"
            and type(data.pokemon[species]) == "table") then
      return nil
    end
    local f = { path = "wild", kind = kind, wild = true, species = species, level = level,
                dex = engine.dexOf(species) }
    local p, m = ow.player, ow.map
    f.map = type(m) == "table" and symbol(m.id) and m.id or nil
    local d = type(p) == "table" and DIR_DELTA[p.facing] or nil
    if d and tonumber(p.cellX) and tonumber(p.cellY) then f.x, f.y = p.cellX + d[1], p.cellY + d[2] end
    return f
  end

  -- Holds a wild battle for Cobblemon on this overworld (the record kb_battle
  -- sends and battleFinish/finishWild end).
  local function holdWild(ow, plan, facts)
    local record = newRecord(plan, facts, "wild")
    if not ow.engaging then
      ow.engaging = true
      record.heldEngaging = true
    end
    bridgeState().battle = record
    playBattleMusic(record) -- M8 (P22)
    return record
  end

  local function planFor(bridge, facts)
    if type(bridge) ~= "table" or type(bridge.battle) ~= "function" then return nil end
    local ok, plan = pcall(bridge.battle, facts)
    if ok and type(plan) == "table" and type(plan.id) == "string" then return plan end
    return nil
  end

  -- encounter.roll's wrapper body (OC:4775; next is the vanilla roll,
  -- OC:4762).
  function engine.encounterRoll(nextFn, encDef, ctx, bridge)
    local terrain = type(ctx) == "table" and ctx.terrain or nil
    local ow, save = engine.overworld(), engine.save()
    if not ow or not isLive(bridge) then
      encounterCounts.native = encounterCounts.native + 1
      return nextFn(encDef, ctx)
    end
    if safariHere(ow, save) then
      encounterCounts.safari = encounterCounts.safari + 1
      return nextFn(encDef, ctx)
    end
    if ghostHere(ow, save) then
      encounterCounts.ghost = encounterCounts.ghost + 1
      return nextFn(encDef, ctx)
    end
    if terrain == "grass" or terrain == "indoor" then
      encounterCounts[terrain] = encounterCounts[terrain] + 1
      return nil
    end
    if terrain ~= "water" then
      encounterCounts.other = encounterCounts.other + 1
      return nextFn(encDef, ctx)
    end
    if liveBattle() then
      encounterCounts.declined = encounterCounts.declined + 1
      return nil
    end
    local raw = nextFn(encDef, ctx)
    if type(raw) ~= "table" then return raw end
    local enc = raw
    local Runtime = try("src.mods.Runtime")
    if Runtime and type(Runtime.wantsHook) == "function" and Runtime.wantsHook("encounter.species") then
      local ok, out = pcall(Runtime.call, "encounter.species", function(e) return e end, raw, ctx)
      if ok then enc = out end
      if type(enc) ~= "table" then return nil end
    end
    -- REPEL blocks wild Pokemon weaker than the lead (OC:4934-4940)
    local lead = type(save) == "table" and type(save.party) == "table" and save.party[1] or nil
    local repel = type(save) == "table" and tonumber(save.repelSteps) or 0
    if repel > 0 and type(lead) == "table" and tonumber(lead.level) and tonumber(enc.level)
        and enc.level < lead.level then
      encounterCounts.repel = encounterCounts.repel + 1
      return nil
    end
    local facts = wildFacts(ow, enc, "surf")
    local plan = facts and planFor(bridge, facts)
    if not plan then
      -- kb_battle can't express it: the hidden game fights it (the engine
      -- runs encounter.species on this raw roll itself)
      encounterCounts.declined = encounterCounts.declined + 1
      return raw
    end
    holdWild(ow, plan, facts)
    encounterCounts.water = encounterCounts.water + 1
    return nil
  end

  -- encounter.fishing's wrapper body (OC:2497-2500: next rolls the
  -- candidates, or the Old Rod's `always`).
  function engine.encounterFishing(nextFn, rod, mapId, pool, bridge)
    local ow, save = engine.overworld(), engine.save()
    if not ow or not isLive(bridge) then
      encounterCounts.native = encounterCounts.native + 1
      return nextFn(rod, mapId, pool)
    end
    if safariHere(ow, save) then
      encounterCounts.safari = encounterCounts.safari + 1
      return nextFn(rod, mapId, pool)
    end
    local enc = nextFn(rod, mapId, pool)
    if type(enc) ~= "table" then
      encounterCounts.noBite = encounterCounts.noBite + 1
      return enc
    end
    local facts = wildFacts(ow, enc, "fish")
    local st = bridgeState()
    if facts and st then
      st.fishArm = { facts = facts, ow = ow, save = save, step = engine.logicStep() }
    else
      encounterCounts.declined = encounterCounts.declined + 1
    end
    return enc
  end

  -- screen.pushed (src/core/StateStack.lua:28-35, fired right after the
  -- push): the cast box goFishing pushes right after a bite's roll, in the
  -- same logic step, gets the bite's continuation. Returns true when it
  -- took one.
  function engine.fishPushed(state)
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    local arm = type(st) == "table" and st.fishArm or nil
    if type(arm) ~= "table" then return false end
    st.fishArm = nil
    if not (type(state) == "table" and state.isTextBox and type(state.onDone) == "function")
        or arm.step ~= engine.logicStep() or arm.ow ~= engine.overworld() then
      return false
    end
    local nativeDone = state.onDone
    state.onDone = function()
      local ow = engine.overworld()
      local bridge = currentBridge()
      local plan
      if ow == arm.ow and engine.save() == arm.save and not liveBattle() and isLive(bridge) then
        plan = planFor(bridge, arm.facts)
      end
      if not plan then
        if ow == arm.ow then encounterCounts.declined = encounterCounts.declined + 1 end
        return nativeDone() -- the engine's own verdict: the bite and a native battle
      end
      encounterCounts.fish = encounterCounts.fish + 1
      pushTexts({ romText("_ItsABiteText", "Oh!\nIt's a bite!") }, function()
        ow.fishing = nil
        if type(ow.player) == "table" then ow.player.fishing = nil end
        if engine.overworld() == ow and engine.save() == arm.save and not liveBattle() then
          holdWild(ow, plan, arm.facts)
        end
      end)
    end
    return true
  end

  -- The special battle running natively on the stack, if any: "safari",
  -- "ghost" or "old_man" (BattleState:battleKind, src/battle/BattleState.lua
  -- :1857-1862, from makeSafari :1077, makeGhost :986, makeOldManDemo :1052).
  function engine.nativeBattle()
    local states = type(game) == "table" and game.stack and game.stack.states or nil
    if type(states) ~= "table" then return nil end
    for i = #states, 1, -1 do
      local s = states[i]
      if type(s) == "table" and s.isBattleState then
        local kind
        if type(s.battleKind) == "function" then
          local ok, k = pcall(s.battleKind, s)
          if ok then kind = k end
        end
        if kind == nil then kind = (s.ghost and "ghost") or (s.safari and "safari") or (s.demo and "oldman") or nil end
        return NATIVE_KINDS[kind]
      end
    end
    return nil
  end

  function engine.encounterCounts()
    local out = {}
    for k, v in pairs(encounterCounts) do out[k] = v end
    return out
  end

  -- ---- the Pokedex
  --
  -- save.pokedex.seen / owned: species -> true (SaveData.newGame's skeleton,
  -- src/core/SaveData.lua:3264); the engine marks both on a gift, a trade or
  -- an evolution (src/pokemon/Evolution.lua:105-108,
  -- src/link/Protocol.lua:1227-1228) and counts entries with pairs (Oak's
  -- rating, OC:3870-3873; the start menu, src/ui/StartMenu.lua:68).

  function engine.inGame() return engine.overworld() ~= nil end

  -- Adds Cobblemon's Pokedex (dex numbers) to the hidden game's: an owned
  -- entry is seen as well, and nothing already set is ever cleared. Returns
  -- { seen, owned } newly set and `unknown` dex numbers with no Gen 1
  -- species, or nil without a save.
  function engine.applyDex(seen, owned)
    local save = engine.save()
    if type(save) ~= "table" then return nil end
    if type(save.pokedex) ~= "table" then save.pokedex = {} end
    local dex = save.pokedex
    if type(dex.seen) ~= "table" then dex.seen = {} end
    if type(dex.owned) ~= "table" then dex.owned = {} end
    local res = { seen = 0, owned = 0, unknown = 0 }
    local function mark(set, species, field)
      if not set[species] then
        set[species] = true
        res[field] = res[field] + 1
      end
    end
    for _, n in ipairs(type(owned) == "table" and owned or {}) do
      local species = engine.speciesOfDex(n)
      if species then
        mark(dex.owned, species, "owned")
        mark(dex.seen, species, "seen")
      else
        res.unknown = res.unknown + 1
      end
    end
    for _, n in ipairs(type(seen) == "table" and seen or {}) do
      local species = engine.speciesOfDex(n)
      if species then mark(dex.seen, species, "seen") else res.unknown = res.unknown + 1 end
    end
    return res
  end

  -- How many species the hidden game has seen and owned.
  function engine.dexCounts()
    local save = engine.save()
    local dex = type(save) == "table" and type(save.pokedex) == "table" and save.pokedex or {}
    local seen, owned = 0, 0
    for _, v in pairs(type(dex.seen) == "table" and dex.seen or {}) do if v then seen = seen + 1 end end
    for _, v in pairs(type(dex.owned) == "table" and dex.owned or {}) do if v then owned = owned + 1 end end
    return seen, owned
  end

  -- ---- the bag
  --
  -- save.inventory: item -> quantity, badges included (as true; Bag.isBadge,
  -- src/inventory/Bag.lua:23-26); key items carry keyItem in their record
  -- (RomExtractor.lua:884-887). The engine changes it through Bag.add /
  -- Bag.remove (Bag.lua:443-469).

  -- MG6: an item that never leaves this game (the plan's "per game, never in
  -- the ledger"): a badge (Bag.isBadge: the id contains "BADGE",
  -- src/inventory/Bag.lua:24-27, exported :58), a key item (`keyItem` set
  -- from the ROM's KeyItemFlags at import, src/import/RomExtractor.lua:
  -- 883-887) or an HM (its record's machine.kind "HM", RomExtractor.lua:
  -- 889-897; TMs cross by move, HMs stay).
  function engine.isKeyItem(id)
    if type(id) ~= "string" then return false end
    local Bag = try("src.inventory.Bag")
    if Bag and type(Bag.isBadge) == "function" then
      if Bag.isBadge(id) then return true end
    elseif id:find("BADGE", 1, true) then
      return true
    end
    local data = gameData()
    local def = data and type(data.items) == "table" and data.items[id] or nil
    if type(def) ~= "table" then return false end
    if def.keyItem then return true end
    return type(def.machine) == "table" and def.machine.kind == "HM"
  end

  -- The quantities of the listed items the bag holds (1 or more), never a
  -- key item (MG6: nor a badge or an HM, engine.isKeyItem).
  function engine.bagCounts(items)
    local save = engine.save()
    local inv = type(save) == "table" and type(save.inventory) == "table" and save.inventory or nil
    local out = {}
    if not inv then return out end
    for _, id in ipairs(type(items) == "table" and items or {}) do
      local qty = inv[id]
      if type(qty) == "number" and qty >= 1 and not engine.isKeyItem(id) then
        out[id] = math.floor(qty)
      end
    end
    return out
  end

  -- MG6: the game's item table from the catalogue: this family's symbol ->
  -- the canonical id, for every item the catalogue knows a gen1 symbol of.
  -- Empty without a catalogue.
  function engine.itemTable()
    local out = {}
    local cat = itemsCatalogue and type(itemsCatalogue.items) == "table" and itemsCatalogue.items or {}
    local family = engine.family()
    for cid, entry in pairs(cat) do
      local sym = type(entry) == "table" and type(entry.symbols) == "table" and entry.symbols[family] or nil
      if type(sym) == "string" then out[sym] = cid end
    end
    return out
  end

  -- MG6: the catalogue's class of one of this game's symbols (ball,
  -- medicine, battle, field, stone, held, valuable, tm, key), or nil.
  function engine.itemClass(symbol)
    local cat = itemsCatalogue and type(itemsCatalogue.items) == "table" and itemsCatalogue.items or {}
    local family = engine.family()
    for _, entry in pairs(cat) do
      if type(entry) == "table" and type(entry.symbols) == "table" and entry.symbols[family] == symbol then
        return entry.class
      end
    end
    return nil
  end

  -- MG6: the move a TM or HM teaches, as its Gen 1 move number: the item
  -- record's machine.move (RomExtractor.lua:889-910, the move symbol) through
  -- engine.moveNumOf; nil for anything else.
  function engine.tmMove(id)
    local data = gameData()
    local def = data and type(data.items) == "table" and data.items[id] or nil
    local m = type(def) == "table" and def.machine or nil
    if type(m) ~= "table" or type(m.move) ~= "string" then return nil end
    return engine.moveNumOf(m.move)
  end

  -- MG6: the bag's limits: Bag.capacity(data) (the ITEM pocket, every Gen 1
  -- item's: data.constants.bagSize or 20, src/inventory/Bag.lua:43-55 with
  -- pocketOf :31-36), 99 a stack (MAX_STACK, :59) and the slots in use
  -- (Bag.slots(save, data), :344-349, one per stack).
  function engine.bagLimits()
    local Bag = try("src.inventory.Bag")
    local save, data = engine.save(), gameData()
    local out = { slots = 20, stack = 99, used = 0 }
    if Bag and type(Bag.capacity) == "function" then
      local ok, cap = pcall(Bag.capacity, data)
      if ok and type(cap) == "number" and cap >= 1 then out.slots = math.floor(cap) end
    end
    if Bag and type(Bag.slots) == "function" and type(save) == "table" then
      local ok, used = pcall(Bag.slots, save, data)
      if ok and type(used) == "number" then out.used = math.floor(used) end
    end
    return out
  end

  -- MG6: bag.set's projection (docs/PROTOCOL.md "One economy"): every item of
  -- the game's item table that isn't a key item is taken out of the bag with
  -- Bag.remove (Bag.lua:464-469; an id the catalogue doesn't know -- a story
  -- item -- is never touched), then the projection goes in with Bag.add
  -- (Bag.lua:443-461) in class order (balls, medicine, battle items, field
  -- items, stones, valuables, TMs; ids sorted within a class), one add per
  -- 99: addTo refuses an add whole when it would pass a stack's 99 (:231)
  -- or needs a slot the bag hasn't got (:243, :248), so a chunk that fails
  -- leaves the rest of that item in `left`. save.money is written clamped to
  -- the snapshot's range (0..999999). Returns ok (the Bag module and a live
  -- save were there) and left { id = qty } (empty when everything fit).
  Gen1.CLASS_ORDER = { ball = 1, medicine = 2, battle = 3, field = 4, stone = 5, valuable = 6, held = 7, tm = 8 }
  function engine.bagSet(items, money)
    local Bag = try("src.inventory.Bag")
    local save, data = engine.save(), gameData()
    if not (Bag and type(Bag.add) == "function" and type(Bag.remove) == "function" and type(save) == "table") then
      return false, {}
    end
    local tbl = engine.itemTable()
    if type(save.inventory) ~= "table" then save.inventory = {} end
    local inv = save.inventory
    local ids = {}
    for id in pairs(tbl) do ids[#ids + 1] = id end
    table.sort(ids)
    for _, id in ipairs(ids) do
      local held = type(inv[id]) == "number" and math.floor(inv[id]) or 0
      if held > 0 and not engine.isKeyItem(id) then pcall(Bag.remove, save, id, held, data) end
    end
    local list = {}
    for id, qty in pairs(type(items) == "table" and items or {}) do
      if type(id) == "string" and tbl[id] and type(qty) == "number" and qty >= 1 and not engine.isKeyItem(id) then
        list[#list + 1] = { id = id, qty = math.floor(qty), rank = Gen1.CLASS_ORDER[engine.itemClass(id) or ""] or 9 }
      end
    end
    table.sort(list, function(a, b)
      if a.rank ~= b.rank then return a.rank < b.rank end
      return a.id < b.id
    end)
    local left = {}
    for _, e in ipairs(list) do
      local remaining = e.qty
      while remaining > 0 do
        local n = math.min(remaining, 99)
        local ok, added = pcall(Bag.add, save, e.id, n, data)
        if not (ok and added == true) then break end
        remaining = remaining - n
      end
      if remaining > 0 then left[e.id] = remaining end
    end
    local m = tonumber(money)
    if m then save.money = math.max(0, math.min(999999, math.floor(m))) end
    return true, left
  end

  -- Takes up to qty of an item out of the bag with Bag.remove(save, id, qty,
  -- data, slot) (Bag.lua:464-469: the slot and its bagOrder entry go at
  -- zero; since v0.3.85 the game data decides whether the item is kept in
  -- stacks of 99, save.bagStacks :333-348, so it is passed; no slot: the
  -- total is what the bridge mirrors); never below zero. Returns how many
  -- were taken and how many there were.
  function engine.bagRemove(id, qty)
    local save = engine.save()
    local inv = type(save) == "table" and type(save.inventory) == "table" and save.inventory or nil
    if not inv then return 0, 0 end
    local held = type(inv[id]) == "number" and math.max(0, math.floor(inv[id])) or 0
    local n = math.min(qty, held)
    if n <= 0 then return 0, held end
    local Bag = try("src.inventory.Bag")
    if not (Bag and type(Bag.remove) == "function") then return 0, held end
    local ok = pcall(Bag.remove, save, id, n, gameData())
    if not ok then return 0, held end
    return n, held
  end

  -- ---- items used on a party member
  --
  -- BagMenu runs every use-flow through the item.use hook
  -- (src/ui/BagMenu.lua:525-528; the wrapped dispatch is vanillaUseOn, :95),
  -- so the wrapper sees (game, battle, id, target, list, moveIndex, picker)
  -- before anything happens. Outside battles, while a client or test
  -- session is connected, on a member with a Cobblemon uid (PLAN P14, DESIGN
  -- section 5 "Items and moves crossing over"):
  --   * an evolution stone the member's species evolves with (ItemEffects.use's
  --     own test, src/inventory/ItemEffects.lua:518-539; Yellow's starter
  --     Pikachu refusal left native) and RARE_CANDY below level 100 (:490-492):
  --     the engine's effect is skipped -- Cobblemon applies it (party.item),
  --     and the next party.sync brings it back. The bag side stays BagMenu's:
  --     the item is consumed and its row refreshed (consume and removeUsed,
  --     BagMenu.lua:42-52, :368-373), and the party menu closes behind the
  --     bag's white flash (closePicker, :94-101; BAG_RETURN_WHITE, :17);
  --   * a TM or HM runs natively -- its forget prompt (MoveLearnMenu) is the
  --     slot picker, HMs stay in the bag and TMs are used up (BagMenu.lua
  --     :240-277) -- and the move it teaches is watched for: appended
  --     (:247-248) or written over slot i (src/ui/MoveLearnMenu.lua:111-112).
  --     That goes to Cobblemon as party.item{move, slot}; the next party.sync
  --     replaces the shadow party's copy either way.
  -- Anything else, and anything the engine refuses, runs natively.

  local STONES = { FIRE_STONE = true, WATER_STONE = true, THUNDER_STONE = true, LEAF_STONE = true,
                   MOON_STONE = true } -- ItemEffects.lua:57-60, when isStone isn't there
  local itemCounts = { stone = 0, candy = 0, machine = 0, taught = 0, declined = 0, native = 0, medicine = 0 }

  local function itemState()
    local st = bridgeState()
    if st and type(st.itemUses) ~= "table" then st.itemUses = {} end
    return st
  end

  local function queueItemUse(use)
    local st = itemState()
    if st then st.itemUses[#st.itemUses + 1] = use end
  end

  local function isStone(id)
    local ItemEffects = try("src.inventory.ItemEffects")
    if ItemEffects and type(ItemEffects.isStone) == "function" then
      local ok, yes = pcall(ItemEffects.isStone, id)
      if ok then return yes and true or false end
    end
    return STONES[id] == true
  end

  -- BagMenu's bag side of a used-up item, without the item's effect.
  local function consumeAndClose(g, id, list, picker)
    local save = g.save
    local Bag = try("src.inventory.Bag")
    if not (type(save) == "table" and Bag and type(Bag.remove) == "function") then return false end
    if not pcall(Bag.remove, save, id, 1, gameData()) then return false end
    local inv = type(save.inventory) == "table" and save.inventory or {}
    if not inv[id] then
      g.bagSavedMenuItem, g.bagListScrollOffset = 0, 0
      if type(list) == "table" then list.index, list.scroll = 1, 0 end
    end
    if type(list) == "table" and type(list.items) == "table" then
      for i, it in ipairs(list.items) do
        if type(it) == "table" and it.value == id then
          local left = inv[id]
          if left then it.count = left else table.remove(list.items, i) end
          break
        end
      end
      list.index = math.min(tonumber(list.index) or 1, math.max(1, #list.items))
    end
    if type(picker) == "table" and type(picker.close) == "function" then
      pcall(picker.close, picker)
      local Transition = try("src.render.Transition")
      if Transition and type(Transition.whiteFlash) == "function" and g.stack then
        local ok, flash = pcall(Transition.whiteFlash, g, 19)
        if ok and type(flash) == "table" then g.stack:push(flash) end
      end
    end
    return true
  end

  -- The watched TM/HM: taught (the move is in the member's list now: slot
  -- i when it took an old move's place), declined (the party menu the flow
  -- runs from is gone without it) or dropped (the world went away).
  -- Returns that word, or nil while it is still under way.
  function engine.tmWatchStep()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    local w = type(st) == "table" and st.tmWatch or nil
    if type(w) ~= "table" then return nil end
    if w.save ~= engine.save() or w.ow ~= engine.overworld() then
      st.tmWatch = nil
      return "dropped"
    end
    for i, mv in ipairs(type(w.mon.moves) == "table" and w.mon.moves or {}) do
      if type(mv) == "table" and mv.id == w.move then
        st.tmWatch = nil
        local use = { item = w.item, uid = w.uid, move = engine.moveNumOf(w.move), save = w.save }
        if i <= w.known then
          use.slot = i - 1
          -- FP4: the move it replaced, by number (the hidden and Cobblemon slots need not match)
          local old = type(w.before) == "table" and w.before[i] or nil
          if old then use.forget = engine.moveNumOf(old) end
        end
        queueItemUse(use)
        itemCounts.taught = itemCounts.taught + 1
        return "taught"
      end
    end
    if not (w.picker ~= nil and stackIndex(w.picker)) then
      st.tmWatch = nil
      itemCounts.declined = itemCounts.declined + 1
      return "declined"
    end
    return nil
  end

  -- item.use's wrapper body (BagMenu.lua:525-528).
  function engine.itemUse(nextFn, g, battle, id, target, list, moveIndex, picker, bridge)
    local function native()
      return nextFn(g, battle, id, target, list, moveIndex, picker)
    end
    -- M8: the bag's ESCAPE ROPE (BagMenu's escape_rope branch runs
    -- beginTeleportOut inside this dispatch, src/ui/BagMenu.lua:329-353):
    -- its spin is marked so the warp goes out as kind "rope"
    if id == "ESCAPE_ROPE" and battle == nil and target == nil then
      local ow = engine.overworld()
      local before = ow and ow.teleportOut
      local results = { native() }
      if ow and ow.teleportOut ~= nil and ow.teleportOut ~= before then engine.markDeparture("rope") end
      return unpack(results, 1, table.maxn(results))
    end
    if battle ~= nil or type(g) ~= "table" or type(target) ~= "table" or type(id) ~= "string" then
      return native()
    end
    local data, save, ow = gameData(), engine.save(), engine.overworld()
    local uid = monUid[target]
    local def = data and type(data.items) == "table" and data.items[id] or nil
    local sdef = data and type(data.pokemon) == "table" and data.pokemon[target.species] or nil
    if not (uid and save and ow and type(def) == "table" and type(sdef) == "table" and isLive(bridge)) then
      itemCounts.native = itemCounts.native + 1
      return native()
    end
    if type(def.machine) == "table" then
      -- ItemUseTMHM's tests (ItemEffects.lua:586-612): the species learns
      -- it and the member doesn't know it yet; otherwise the engine refuses
      local move = def.machine.move
      local learns = false
      local synced = monCanLearn[target]
      if synced then
        -- FP4 (§4.4): Cobblemon's learnset answers, natives and visitors alike
        learns = synced[move] == true
      else
        for _, m in ipairs(type(sdef.tmhm) == "table" and sdef.tmhm or {}) do
          if m == move then learns = true break end
        end
      end
      local known, before = 0, {}
      for _, mv in ipairs(type(target.moves) == "table" and target.moves or {}) do
        known = known + 1
        before[known] = type(mv) == "table" and mv.id or nil
        if type(mv) == "table" and mv.id == move then learns = false end
      end
      if not learns then
        -- the engine's own check would let it through on the species' list:
        -- its refusal line is ItemEffects' (the species list, swapped for
        -- the member's while it runs)
        if not synced then return native() end
        local tmhm0 = sdef.tmhm
        sdef.tmhm = {}
        local results = { pcall(native) }
        sdef.tmhm = tmhm0
        if not results[1] then error(results[2], 0) end
        return unpack(results, 2, table.maxn(results))
      end
      local st = itemState()
      if st then
        st.tmWatch = { mon = target, uid = uid, item = id, move = move, known = known, before = before, picker = picker,
                       save = save, ow = ow }
      end
      if synced then
        -- the engine's own check reads the species' list: it gets the move
        -- while the flow starts (a visitor's list is empty)
        local tmhm0 = sdef.tmhm
        local list = {}
        for _, m in ipairs(type(tmhm0) == "table" and tmhm0 or {}) do list[#list + 1] = m end
        list[#list + 1] = move
        sdef.tmhm = list
        itemCounts.machine = itemCounts.machine + 1
        local results = { pcall(native) }
        sdef.tmhm = tmhm0
        if not results[1] then error(results[2], 0) end
        engine.tmWatchStep()
        return unpack(results, 2, table.maxn(results))
      end
      itemCounts.machine = itemCounts.machine + 1
      local results = { native() }
      engine.tmWatchStep() -- fewer than four moves: taught already
      return unpack(results, 1, table.maxn(results))
    end
    -- FP4 (§4.4): medicine, PP items and vitamins run natively on the
    -- record (the next sync rewrites it) and go to Cobblemon when the
    -- engine spent the item (ItemEffects.use's "consumed",
    -- src/inventory/ItemEffects.lua:212): Cobblemon applies its own item
    -- (party.item kind medicine; kb_party names pp and vitamin); a PP item
    -- names the move it was used on
    if id ~= "RARE_CANDY" and engine.itemClass(id) == "medicine" then
      local IE = try("src.inventory.ItemEffects")
      local use0 = IE and rawget(IE, "use")
      local outcome
      if type(use0) == "function" then
        IE.use = function(...)
          local r = { use0(...) }
          outcome = r[1]
          return unpack(r, 1, table.maxn(r))
        end
      end
      local results = { pcall(native) }
      if type(use0) == "function" then IE.use = use0 end
      if not results[1] then error(results[2], 0) end
      if outcome == "consumed" then
        local use = { item = id, uid = uid, save = save, kind = "medicine" }
        local mv = moveIndex and type(target.moves) == "table" and target.moves[moveIndex] or nil
        if type(mv) == "table" then use.move = engine.moveNumOf(mv.id) end
        queueItemUse(use)
        itemCounts.medicine = itemCounts.medicine + 1
      end
      return unpack(results, 2, table.maxn(results))
    end
    local kind
    if isStone(id) then
      if isYellow() then return native() end
      local synced = monStones[target]
      if synced then
        -- FP2/FP4 (§4.4): Cobblemon's own answer, from the sync's stones,
        -- for natives and visitors alike (a visitor's species has none)
        if synced[id] then kind = "stone" end
      else
        for _, evo in ipairs(type(sdef.evolutions) == "table" and sdef.evolutions or {}) do
          if type(evo) == "table" and evo.method == "ITEM" and evo.item == id then kind = "stone" break end
        end
      end
    elseif id == "RARE_CANDY" and tonumber(target.level) and target.level < 100 then
      kind = "candy"
    end
    if not kind or not consumeAndClose(g, id, list, picker) then return native() end
    queueItemUse({ item = id, uid = uid, save = save, kind = kind })
    itemCounts[kind] = itemCounts[kind] + 1
    return nil
  end

  -- The item uses waiting for Minecraft (party.item), as plain values; the
  -- queue is emptied. Uses from another save (New Game, CONTINUE) are gone.
  function engine.takeItemUses()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    local list = type(st) == "table" and st.itemUses or nil
    local out = {}
    if type(list) ~= "table" then return out end
    local save = engine.save()
    for _, u in ipairs(list) do
      if u.save == save then
        out[#out + 1] = { item = u.item, uid = u.uid, slot = u.slot, move = u.move, forget = u.forget,
                          kind = u.kind or (u.move and "machine") }
      end
    end
    st.itemUses = {}
    return out
  end

  function engine.itemCounts()
    local out = {}
    for k, v in pairs(itemCounts) do out[k] = v end
    return out
  end

  -- ------------------------------------------------------------ the Pokemon Center, saving, the PC, sessions (M7)
  --
  -- The nurse (OverworldState:nurseHeal, OC:3943-4049) has no event or
  -- hook. Her machine() heals the hidden party, writes save.lastHeal -- the
  -- blackout return point, SetLastBlackoutMap (OC:3987-3994) -- and starts
  -- ow.healAnim (OC:3995-4001), the one place that field is set; the
  -- machine runs for dozens of frames (OC:1467-1479, stepHealAnim), so a
  -- new healAnim table, polled every step, is a nurse heal. Cobblemon's
  -- party heals too (party.heal), and while a client or test session is
  -- connected the hidden game saves itself once her lines are done and the
  -- player stands: Game:writeSave (src/core/Game.lua:1362-1385: the save.write
  -- veto, captureSave, the meta stamp, save.writing, SaveData.save), gated
  -- exactly as the engine gates its own F1 quick save (Game:quickSaveAllowed,
  -- Game.lua:1387-1403; :861-863). That save's save.writing has the reason
  -- "center"; every other one -- the start menu's SAVE
  -- (src/ui/StartMenu.lua:117-125), F1, the Hall of Fame
  -- (src/script/Commands.lua:950) -- is "menu". gen1-bridge never writes
  -- lastHeal itself.
  --
  -- Bill's PC: OverworldState:openPC (OC:3680-3812) passes the PC menu's
  -- rows through the ui.pc.items hook (OC:3776) with their English source
  -- labels (Gen1.BOX_PC_LABELS). The box row keeps the menu open
  -- (keepOpen, src/ui/Menu.lua:104-105) and its onSelect plays the sound, pushes
  -- the "Accessed ... PC" text box whose onDone opens BoxMenu through
  -- openSub (OC:3693-3720), then calls done(). While a client or test
  -- session is connected the wrapper runs that onSelect as it is and then
  -- gives the text box another continuation (TextBox's onDone, read when it
  -- pops: src/render/TextBox.lua:132, :642-643): a hold state on top of the
  -- still-open PC menu -- a plain state with no input and nothing drawn
  -- (src/core/StateStack.lua:1-3), busy to the classifier -- until
  -- pc.closed; it then pops itself and the PC menu takes input again. The
  -- player's item PC (PlayerPC) and the other rows stay native. The hold
  -- record lives in the process-lifetime slot, so the wrapper an older
  -- instance put on an open menu, and the hold an older instance pushed,
  -- answer to the current instance after a hot reload.

  -- A nurse heal since the last call: { map, outdoor } (lastHeal's map and
  -- its town, symbols or nil) once per new ow.healAnim, else nil.
  function engine.nurseHealed()
    local ow, st = engine.overworld(), bridgeState()
    if not (ow and st) then return nil end
    local anim = ow.healAnim
    if type(anim) ~= "table" or anim == st.healAnimSeen then return nil end
    st.healAnimSeen = anim
    local save = engine.save()
    local heal = type(save) == "table" and type(save.lastHeal) == "table" and save.lastHeal or {}
    local outdoor = type(heal.outdoor) == "table" and heal.outdoor.id or nil
    return { map = symbol(heal.map) and heal.map or nil, outdoor = symbol(outdoor) and outdoor or nil }
  end

  -- Arms the center save for this overworld and save.
  function engine.armCenterSave()
    local st, ow, save = bridgeState(), engine.overworld(), engine.save()
    if not (st and ow and save) then return false end
    st.centerSave = { ow = ow, save = save }
    return true
  end

  function engine.centerSaveArmed()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    return type(st) == "table" and type(st.centerSave) == "table"
  end

  -- One step of the armed center save. nil while there is none or it waits
  -- (the machine still running, something on top of the overworld, a
  -- Cobblemon battle held, or the quick-save gate closed: a walk, a script,
  -- an emote, a warp); otherwise what happened: "written", "refused" (the
  -- save.write veto or a failed write), "error" (writeSave threw or is
  -- missing) or "dropped" (the world went away: the title, New Game,
  -- CONTINUE).
  function engine.centerSaveStep()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    local arm = type(st) == "table" and st.centerSave or nil
    if type(arm) ~= "table" then return nil end
    local ow, save = engine.overworld(), engine.save()
    if arm.ow ~= ow or arm.save ~= save then
      st.centerSave = nil
      return "dropped"
    end
    if ow.healAnim or engine.top() ~= ow or liveBattle() then return nil end
    if type(game.quickSaveAllowed) == "function" then
      local ok, allowed = pcall(game.quickSaveAllowed, game)
      if not (ok and allowed) then return nil end
    end
    st.centerSave = nil
    if type(game.writeSave) ~= "function" then return "error" end
    st.saving = "center"
    local ok, written = pcall(game.writeSave, game)
    st.saving = nil
    if not ok then
      warnAt("engine.write_save_failed", written)
      return "error"
    end
    if written == false or written == nil then return "refused" end
    return "written"
  end

  -- The reason of the save being written right now (inside writeSave's
  -- save.writing): "center" for the center save, "switch" for the save
  -- before a slot switch (M9, engine.switchSave), else nil.
  function engine.savingReason()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    return type(st) == "table" and st.saving or nil
  end

  -- A hidden-game save to announce (save.begin): the newest one waits, one
  -- Minecraft save covers any before it. Returns its id (unique per
  -- process: the first instance's token and the shared serial) and whether
  -- it replaced one still waiting. M10: `pair` = { pair, at } (the save
  -- pair written into this save's mod data, act/kb_save.lua) goes with it,
  -- and so does the save table it was written from and its serial, the
  -- shared counter's value: a gift or an operation with a higher serial
  -- came after this save and waits until its save.begin is out
  -- (engine.queuedSaveSerial).
  function engine.queueSave(reason, pair)
    local st = bridgeState()
    if not st then return nil, false end
    st.serial = st.serial + 1
    local id = ("v-%s-%d"):format(st.token or "x", st.serial)
    local replaced = st.saveQueued ~= nil
    pair = type(pair) == "table" and pair or {}
    st.saveQueued = { save = id, reason = reason, serial = st.serial, pair = pair.pair, at = pair.at,
                      from = engine.save() }
    return id, replaced
  end

  -- The save waiting to be announced ({ save, reason, pair, at, current }),
  -- taken; or nil. current: it was written from the live save table (not a
  -- playthrough left since, through the title).
  function engine.takeSave()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    local q = type(st) == "table" and st.saveQueued or nil
    if type(q) ~= "table" then return nil end
    st.saveQueued = nil
    return { save = q.save, reason = q.reason, pair = q.pair, at = q.at,
             current = q.from ~= nil and q.from == engine.save() }
  end

  -- M10: the serial of the save waiting to be announced, or nil.
  function engine.queuedSaveSerial()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    local q = type(st) == "table" and st.saveQueued or nil
    return type(q) == "table" and q.serial or nil
  end

  -- The hidden game's active save slot as a number: SaveData.activeSlot
  -- (src/core/SaveData.lua:1765-1775) names it "slot<N>" (valid_slot_id,
  -- :1288-1290), resolved once per process (the title's CONTINUE gate
  -- already did, saveNames :1486-1497); nil there means the flat legacy
  -- save file, 0 here.
  function engine.saveSlot()
    local SaveData = try("src.core.SaveData")
    if not (SaveData and type(SaveData.activeSlot) == "function") then return 0 end
    local save = engine.save()
    local version = type(save) == "table" and type(save.version) == "string" and save.version
      or engine.gameVersion()
    local ok, id = pcall(SaveData.activeSlot, version)
    local n = ok and type(id) == "string" and tonumber(id:match("^slot(%d+)$")) or nil
    if not n or n < 0 or n > 255 then return 0 end
    return n
  end

  -- ---- Bill's PC

  local pcCounts = { taken = 0, native = 0 }

  local function pcRecord()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    local rec = type(st) == "table" and st.pc or nil
    return type(rec) == "table" and rec or nil, st
  end

  -- The hold: a plain state over the PC menu that pops itself once its
  -- record is no longer waiting (only the top state updates,
  -- src/core/StateStack.lua:60-63).
  local function holdPc(ow, save)
    local st = bridgeState()
    if not (st and type(game) == "table" and game.stack) then return end
    local rec = { state = "waiting", ow = ow, save = save }
    local stack = game.stack
    local hold = { kantoBridgePcHold = true }
    function hold.update()
      if rec.state ~= "waiting" and stack:top() == hold then stack:pop() end
    end
    rec.hold = hold
    st.pc = rec
    stack:push(hold)
  end

  local function boxPcSelected(nativeSelect, ...)
    local ow = engine.overworld()
    if not (ow and isLive(currentBridge())) or pcRecord() or not (game and game.stack) then
      pcCounts.native = pcCounts.native + 1
      return nativeSelect(...)
    end
    local stack = game.stack
    local before = stack:top()
    local results = { nativeSelect(...) }
    local box = stack:top()
    if box ~= before and type(box) == "table" and box.isTextBox and type(box.onDone) == "function" then
      local save = engine.save()
      box.onDone = function() holdPc(ow, save) end
      pcCounts.taken = pcCounts.taken + 1
    else
      pcCounts.native = pcCounts.native + 1
      if log then log.warn("engine.pc_native", { count = pcCounts.native }) end
    end
    return unpack(results, 1, table.maxn(results))
  end

  -- ui.pc.items's wrapper body (OC:3776; the (next, game, items)
  -- decorate-after-next convention, docs/modding.md "ui.title_menu.items"):
  -- the box row's onSelect is wrapped, the rest is left as it is.
  function engine.pcItems(nextFn, g, items)
    local out = nextFn(g, items)
    if type(out) ~= "table" then return out end
    for _, row in ipairs(out) do
      if type(row) == "table" and Gen1.BOX_PC_LABELS[row.label] and type(row.onSelect) == "function" then
        local nativeSelect = row.onSelect
        row.onSelect = function(...) return boxPcSelected(nativeSelect, ...) end
      end
    end
    return out
  end

  -- The PC hold as plain values ({ state, sentGen, id }) or nil; a hold
  -- whose world went away (the title, New Game, CONTINUE) is dropped: nil,
  -- "dropped".
  function engine.pcPending()
    local rec, st = pcRecord()
    if not rec then return nil end
    if rec.state ~= "waiting" or rec.ow ~= engine.overworld() or rec.save ~= engine.save() then
      if rec.state == "waiting" then rec.state = "dropped" end
      st.pc = nil
      return nil, "dropped"
    end
    return { state = rec.state, sentGen = rec.sentGen, id = rec.id }
  end

  -- pc.open went out for the hold, with its id (M10: in the process-lifetime
  -- record, so the pc.closed a newer instance gets after a hot reload is
  -- matched against it too).
  function engine.pcSent(generation, id)
    local rec = pcRecord()
    if rec then rec.sentGen, rec.id = generation, id end
  end

  -- Ends the hold (pc.closed, or a release when the link drops): the hold
  -- state pops itself on its next update. Returns true when one was held.
  -- M10: with an id, only the hold that pc.open announced with that id
  -- ends; false plus "stale" for another one (the hold stays).
  function engine.pcClose(why, id)
    local rec, st = pcRecord()
    if not rec or rec.state ~= "waiting" then return false end
    if id ~= nil and rec.id ~= id then return false, "stale" end
    rec.state = why or "closed"
    st.pc = nil
    return true
  end

  function engine.pcHeld() return pcRecord() ~= nil end

  function engine.pcCounts()
    return { taken = pcCounts.taken, native = pcCounts.native }
  end

  -- ---- regions (MG2): the park hold, the title start, the story markers
  -- (docs/specs/MULTI_GAME_PLAN.md §4.3, §4.7; act/kb_region.lua,
  -- observe/kb_progress.lua)

  local function regionRecord()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    local rec = type(st) == "table" and st.region or nil
    return type(rec) == "table" and rec or nil, st
  end

  -- The park hold: a plain state over the overworld, the Bill's PC hold's
  -- pattern (holdPc above). Only the top state updates
  -- (src/core/StateStack.lua:62-65), so the overworld's scripts, NPC movement
  -- and steps freeze underneath and the classifier reports `busy` (a state
  -- without a screen id: classifyState); the hold pops itself on its next
  -- update once released. The record lives in the process-lifetime slot, so
  -- a hot reload keeps the hold. The map song fades out (Music.fadeOut,
  -- src/core/Music.lua:363-392; MAP_FADE :437; guarded, not in REQUIRES) and
  -- comes back on release (Music.restoreMap, :545-551). True when held
  -- (again), or false plus not_ingame | failed.
  function engine.holdAway()
    local ow = engine.overworld()
    if not ow then return false, "not_ingame" end
    local st = bridgeState()
    if not (st and type(game) == "table" and type(game.stack) == "table") then return false, "failed" end
    if type(st.region) == "table" then return true end
    local rec = { state = "held", ow = ow }
    local stack = game.stack
    local hold = { kantoBridgeRegionHold = true }
    function hold.update()
      if rec.state ~= "held" and stack:top() == hold then stack:pop() end
    end
    rec.hold = hold
    st.region = rec
    local ok, err = pcall(stack.push, stack, hold)
    if not ok then
      st.region = nil
      warnAt("engine.hold_failed", err)
      return false, "failed"
    end
    local Music = try("src.core.Music")
    if Music and type(Music.fadeOut) == "function" then pcall(Music.fadeOut, tonumber(Music.MAP_FADE) or 10) end
    return true
  end

  -- Ends the hold: popped at once when it is on top (else on its next
  -- update); the map's song comes back. True when one was held.
  function engine.releaseAway()
    local rec, st = regionRecord()
    if not rec then return false end
    rec.state = "released"
    st.region = nil
    local stack = type(game) == "table" and game.stack or nil
    if type(stack) == "table" and stack:top() == rec.hold then pcall(stack.pop, stack) end
    local Music = try("src.core.Music")
    if Music and type(Music.restoreMap) == "function" and engine.overworld() then
      pcall(Music.restoreMap, game.data, "region")
    end
    return true
  end

  function engine.awayHeld() return regionRecord() ~= nil end

  -- Starts the game from its title screen the way the title menu's rows do
  -- (src/ui/TitleState.lua:541-562 call the callbacks Game:makeTitleState
  -- wires, src/core/Game.lua:226-240): CONTINUE when a save loads --
  -- SaveData.load() (src/core/SaveData.lua:2845), then Game:restoreSave(loaded,
  -- recovered, { freshBoot = true, continued = true }) (Game.lua:230-235,
  -- :1537-1610) -- else NEW GAME, Game:startNewGame() (Game.lua:181-200),
  -- its intro included: nothing skips it (MULTI_GAME_PLAN §4.13). An open
  -- title menu (its rows were built for the title) is closed first with
  -- Game:returnToTitle (:247-251), and the caller tries again next step.
  -- Returns "new" | "continue", or nil plus not_title | menu | failed.
  function engine.startFromTitle()
    if engine.overworld() then return nil, "not_title" end
    if type(game) ~= "table" then return nil, "failed" end
    if engine.titleMenuOpen() then
      if engine.toTitle() then return nil, "menu" end
      return nil, "failed"
    end
    local SaveData = try("src.core.SaveData")
    local okLoad, loaded, recovered = false, nil, nil
    if SaveData and type(SaveData.load) == "function" then
      okLoad, loaded, recovered = pcall(SaveData.load)
    end
    if okLoad and type(loaded) == "table" then
      if type(game.restoreSave) ~= "function" then return nil, "failed" end
      local ok, err = pcall(game.restoreSave, game, loaded, recovered, { freshBoot = true, continued = true })
      if not ok then
        warnAt("engine.continue_failed", err)
        return nil, "failed"
      end
      return "continue"
    end
    if type(game.startNewGame) ~= "function" then return nil, "failed" end
    local ok, err = pcall(game.startNewGame, game)
    if not ok then
      warnAt("engine.new_game_failed", err)
      return nil, "failed"
    end
    return "new"
  end

  -- The game profile's story markers (protocol/games.json `markers`: a marker
  -- name -> the EVENT_* flag that means it), resolved in the Gen 1 flag
  -- namespace: save.flags[flag] == true (the save's flags table, Game.lua:97;
  -- src/script/Flags.lua). Every named marker is answered, false without a
  -- save.
  function engine.progressMarkers(names)
    local save = engine.save()
    local flags = type(save) == "table" and type(save.flags) == "table" and save.flags or {}
    local out = {}
    for name, flag in pairs(type(names) == "table" and names or {}) do
      if type(name) == "string" and type(flag) == "string" then out[name] = flags[flag] == true end
    end
    return out
  end

  -- ------------------------------------------------------------ direct battles, native catches, the daycare, renames (M8)
  --
  -- Direct battles. Three Red/Blue battles never pass the M5 seams: their
  -- code builds the BattleState itself and hands it to
  -- OverworldState:pushBattle (OC:1261-1295) from a text box's continuation:
  --   * a static Pokemon object talked to (talkTo, OC:3600-3622: the
  --     legendary birds, Mewtwo, the Power Plant's Voltorb and Electrode),
  --     an A press through the world.talk hook (OC:2674-2679);
  --   * the ghost Marowak (data/scripts/story3.lua:134-194), a step trigger
  --     (the map's onStep, OC:4834-4839, right after world.stepped,
  --     :4817-4820); with the SILPH_SCOPE it is an ordinary wild battle
  --     (makeUnveiledGhost, src/battle/BattleState.lua:1033-1036); without it
  --     the unidentified ghost (makeGhost, :986-989: battleKind "ghost")
  --     stays native (P9);
  --   * Giovanni in the Rocket Hideout (story3.lua:458-513), a talk script.
  -- The seam: a text box pushed while a talk (the world.talk wrapper) or a
  -- completed step (world.stepped, same logic step) is under way gets a new
  -- continuation (TextBox's onDone, read when it pops: TextBox.lua:634-644,
  -- as M6's fishing and M7's PC do), which runs the native one with
  -- OverworldState.pushBattle shadowed for that one synchronous call. (The
  -- overworld on the stack is the OverworldState module table itself,
  -- src/core/Game.lua:129, :192, so the method is its own field; it is put
  -- back as soon as the call returns, error or not.) A battle pushed then
  -- that is not native (battleKind, :1857-1862), not an engageTrainer or
  -- script-runner battle (checkpointOrigin.kind "trainer_encounter",
  -- OC:4242-4249, or "script_battle", src/script/ScriptRunner.lua:210-245:
  -- those were offered through trainer.before_battle / script.command
  -- already) and that kb_battle
  -- can express is held for Cobblemon as kind "static" (wild: dex, level,
  -- the object's cell or the cell in front of the player; catch unless
  -- battle.noCatch, story3.lua:147-150) or "scripted" (a trainer); the
  -- result ends it through the battle's own onFinish (finishDirect).
  -- Anything else is pushed as usual.
  --
  -- Native catches (P9). pokemon.caught (BattleState:storeCaughtMon,
  -- :5555-5620) reports a Pokemon caught in a battle the hidden game fought
  -- itself (the Safari Zone; any wild battle without a client); it joined
  -- the party (Party.add) or a box (Boxes.deposit) before the event, and its
  -- nickname prompt follows inside the battle (offerNickname, :5487-5505).
  -- Once that battle has left the stack, the catch becomes a gift with
  -- source "native_catch" (facts read then, nickname included): a box catch
  -- is taken out of the Gen 1 box (Cobblemon's PC keeps it), a party catch
  -- stays until a party.sync names it (as for gifts). The old man's demo
  -- never reaches storeCaughtMon (:2755-2776).
  --
  -- The daycare (P15): data/scripts/story2.lua:1020-1210 keeps the boarded
  -- member in save.daycare = { mon, steps, depositLevel } (removed from
  -- save.party, :1168-1173), adds a step's exp per step (OC:4854-4858),
  -- folds the steps into mon.exp on every talk (:1046-1047) and puts the
  -- member back at the end of the party on a paid withdrawal (:1132-1145).
  -- A member with a Cobblemon uid going in is a deposit; coming back, a
  -- withdrawal with the steps walked since: the exp it gained in the
  -- daycare over FieldDefaults' daycareExpPerStep (src/world/FieldDefaults
  -- .lua; 1 by default). The cost, the texts and the Gen 1 state stay
  -- native. kb_party keeps the boarded uid in mod.save for CONTINUE.
  --
  -- Renames (P16): only the Name Rater changes a nickname in Gen 1
  -- (data/scripts/story4.lua:744-818; its NamingScreen's onDone writes
  -- mon.nickname, :800-806). kb_party watches the nicknames of the members
  -- with a uid (engine.partyNicks) and sends what changed outside a sync.

  local directCounts = { armed = 0, taken = 0, native = 0, declined = 0 }
  local catchCounts = { caught = 0, released = 0, skipped = 0, lost = 0 }

  -- What a battle the engine built would be for kb_battle, or nil.
  local function directFacts(ow, battle, npc)
    local f = { path = "direct" }
    f.map = type(ow.map) == "table" and symbol(ow.map.id) and ow.map.id or nil
    if type(npc) == "table" then
      if symbol(npc.id) then f.npc = npc.id end
      f.x, f.y = tonumber(npc.cellX), tonumber(npc.cellY)
    end
    if battle.kind == "wild" then
      local mon = type(battle.enemy) == "table" and battle.enemy.mon or nil
      local species = type(mon) == "table" and mon.species or nil
      local level = type(mon) == "table" and tonumber(mon.level) or nil
      local data = gameData()
      if not (symbol(species) and level and data and type(data.pokemon) == "table"
              and type(data.pokemon[species]) == "table") then
        return nil
      end
      f.wild, f.species, f.level, f.dex = true, species, level, engine.dexOf(species)
      f.catch = not battle.noCatch
      if f.x == nil then
        -- no object (a step trigger): the cell in front of the player
        local p = ow.player
        local d = type(p) == "table" and DIR_DELTA[p.facing] or nil
        if d and tonumber(p.cellX) and tonumber(p.cellY) then f.x, f.y = p.cellX + d[1], p.cellY + d[2] end
      end
      return f
    end
    if battle.kind == "trainer" and symbol(battle.oppClass) then
      f.cls, f.party = battle.oppClass, tonumber(battle.partyIndex) or 1
      return f
    end
    return nil
  end

  -- The shadowed pushBattle's decision: true when the battle is now held
  -- for Cobblemon (and never pushed), false to push it as usual.
  local function divert(ow, battle, npc)
    if type(battle) ~= "table" or type(battle.onFinish) ~= "function" or battle.dead then return false end
    local bridge = currentBridge()
    if liveBattle() or not isLive(bridge) then return false end
    local kind = battle.kind
    if type(battle.battleKind) == "function" then
      local ok, k = pcall(battle.battleKind, battle)
      if ok then kind = k end
    end
    -- ghost, safari, oldman (P9) and link battles stay native
    if kind ~= "wild" and kind ~= "trainer" then return false end
    -- engageTrainer's and the script runner's battles (their checkpoint
    -- origins, OC:4242-4249, src/script/ScriptRunner.lua:210-245) were
    -- offered through trainer.before_battle / script.command already
    local origin = battle.checkpointOrigin
    if type(origin) == "table" and (origin.kind == "trainer_encounter" or origin.kind == "script_battle") then
      return false
    end
    local facts = directFacts(ow, battle, npc)
    local plan = facts and planFor(bridge, facts)
    if not plan then
      directCounts.declined = directCounts.declined + 1
      return false
    end
    local record = newRecord(plan, facts, "direct")
    record.battle = battle
    -- the loss line the script armed (already substituted, e.g.
    -- story3.lua:482-484), shown with the trainer's tag after a win
    if facts.cls and type(battle.endBattleText) == "string" then record.endText = battle.endBattleText end
    if not ow.engaging then
      ow.engaging = true
      record.heldEngaging = true
    end
    bridgeState().battle = record
    playBattleMusic(record, battle)
    directCounts.taken = directCounts.taken + 1
    return true
  end

  -- Runs a text box's native continuation with pushBattle shadowed.
  local function runShadowed(arm, nativeDone, ...)
    local ow = arm.ow
    if type(ow) ~= "table" or ow ~= engine.overworld() then return nativeDone(...) end
    local push = ow.pushBattle
    if type(push) ~= "function" then return nativeDone(...) end
    local own = rawget(ow, "pushBattle")
    local shadow
    shadow = function(self, battle, trainerNpc, ...)
      local ok, took = pcall(divert, self, battle, trainerNpc or arm.npc)
      if not ok then
        warnAt("engine.direct_failed", took)
        took = false
      end
      if took then return nil end
      directCounts.native = directCounts.native + 1
      return push(self, battle, trainerNpc, ...)
    end
    rawset(ow, "pushBattle", shadow)
    local results = { pcall(nativeDone, ...) }
    if rawget(ow, "pushBattle") == shadow then rawset(ow, "pushBattle", own) end
    if not results[1] then error(results[2], 0) end
    return unpack(results, 2, table.maxn(results))
  end

  -- world.talk's wrapper body (OC:2674-2679; docs/modding.md "world.talk"):
  -- text boxes the talk pushes are armed (engine.directPushed).
  function engine.worldTalk(nextFn, ow, target)
    local st = bridgeState()
    if not st then return nextFn(ow, target) end
    local previous = st.directArm
    st.directArm = { ow = ow, npc = target }
    local results = { pcall(nextFn, ow, target) }
    st.directArm = previous
    if not results[1] then error(results[2], 0) end
    return unpack(results, 2, table.maxn(results))
  end

  -- world.stepped (OC:4817-4820, before the map's onStep, :4834-4839): text
  -- boxes pushed in this same logic step are armed.
  function engine.directStepped()
    local st, ow = bridgeState(), engine.overworld()
    if st and ow then st.directArm = { ow = ow, step = engine.logicStep() } end
  end

  -- screen.pushed: an armed text box gets the shadowed continuation.
  -- Returns true when it took one.
  function engine.directPushed(state)
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    local arm = type(st) == "table" and st.directArm or nil
    if type(arm) ~= "table" then return false end
    if arm.step ~= nil and arm.step ~= engine.logicStep() then
      st.directArm = nil
      return false
    end
    if arm.ow ~= engine.overworld() or type(state) ~= "table" or not state.isTextBox
        or type(state.onDone) ~= "function" or state.kantoBridgeDirect then
      return false
    end
    local nativeDone = state.onDone
    state.kantoBridgeDirect = true
    state.onDone = function(...) return runShadowed(arm, nativeDone, ...) end
    directCounts.armed = directCounts.armed + 1
    return true
  end

  -- pokemon.caught's payload { battle, mon, species, isNew, ball,
  -- destination, game } (BattleState.lua:5615-5618). Returns true when the
  -- catch was taken.
  function engine.nativeCaught(payload)
    if type(payload) ~= "table" then return false end
    local st, save = bridgeState(), engine.save()
    local mon, battle = payload.mon, payload.battle
    if not (st and type(save) == "table" and type(mon) == "table") then return false end
    if (payload.destination ~= "party" and payload.destination ~= "box")
        or (type(battle) == "table" and battle.demo) then
      catchCounts.skipped = catchCounts.skipped + 1
      return false
    end
    st.catches = type(st.catches) == "table" and st.catches or {}
    st.catches[#st.catches + 1] = { mon = mon, battle = battle, save = save }
    catchCounts.caught = catchCounts.caught + 1
    return true
  end

  -- Where a member is now: "party", a box number, or nil.
  local function whereIs(save, mon)
    for _, m in ipairs(type(save.party) == "table" and save.party or {}) do
      if m == mon then return "party" end
    end
    for n, box in pairs(type(save.boxes) == "table" and save.boxes or {}) do
      if type(box) == "table" then
        for i, m in ipairs(box) do
          if m == mon then return n, i end
        end
      end
    end
    return nil
  end

  -- Catches whose battle has left the stack become gifts (source
  -- "native_catch"). Called before gifts are sent and syncs applied.
  -- Returns how many were released.
  function engine.releaseCatches()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    local list = type(st) == "table" and st.catches or nil
    if type(list) ~= "table" or #list == 0 then return 0 end
    local save = engine.save()
    local n, i = 0, 1
    while i <= #list do
      local c = list[i]
      if c.save ~= save then
        table.remove(list, i)
      elseif type(c.battle) == "table" and stackIndex(c.battle) then
        i = i + 1 -- its battle (the nickname prompt) is still under way
      else
        table.remove(list, i)
        local where, slot = whereIs(save, c.mon)
        if where == nil then
          catchCounts.lost = catchCounts.lost + 1
        else
          local box = where ~= "party"
          if box then table.remove(save.boxes[where], slot) end
          st.serial = st.serial + 1
          st.gifts[#st.gifts + 1] = {
            give = ("g-%s-%d"):format(st.token or "x", st.serial), serial = st.serial, state = "new", syncs = 0,
            box = box, source = "native_catch", save = save, facts = giftFacts(c.mon),
            mon = (not box) and c.mon or nil,
          }
          catchCounts.released = catchCounts.released + 1
          n = n + 1
        end
      end
    end
    return n
  end

  -- One step of the daycare watch. stored: the boarded member kb_party
  -- kept in mod.save ({ uid, exp0 }) or nil. Returns what happened, or nil:
  --   { kind = "deposit", uid, exp0 }  a member with a uid went in (a
  --                                    daycare.deposit is queued)
  --   { kind = "withdraw", uid, steps } it came back (daycare.withdraw queued)
  --   { kind = "adopted" }             the boarded member of a loaded save
  --                                    got its uid back from `stored`
  --   { kind = "unknown" }             a member without a uid went in
  --   { kind = "cleared" | "lost" }    the daycare emptied with nothing to tell
  function engine.daycareStep(stored)
    local save, st = engine.save(), bridgeState()
    if not (st and type(save) == "table") then return nil end
    local dc = save.daycare
    local mon = type(dc) == "table" and type(dc.mon) == "table" and dc.mon or nil
    local rec = st.daycare
    if type(rec) == "table" and rec.save ~= save then rec, st.daycare = nil, nil end
    if mon then
      if rec and rec.mon == mon then return nil end
      local uid = monUid[mon]
      if uid then
        st.daycare = { uid = uid, mon = mon, exp0 = tonumber(mon.exp) or 0, save = save }
        queueOp({ kind = "deposit", uid = uid, save = save })
        opCounts.deposits = opCounts.deposits + 1
        return { kind = "deposit", uid = uid, exp0 = st.daycare.exp0 }
      end
      if type(stored) == "table" and type(stored.uid) == "string" then
        monUid[mon] = stored.uid
        st.daycare = { uid = stored.uid, mon = mon, exp0 = tonumber(stored.exp0) or 0, save = save }
        return { kind = "adopted" }
      end
      st.daycare = { mon = mon, save = save }
      opCounts.depositNoUid = opCounts.depositNoUid + 1
      return { kind = "unknown" }
    end
    if not rec then return nil end
    st.daycare = nil
    if not rec.uid then return { kind = "cleared" } end
    for _, m in ipairs(type(save.party) == "table" and save.party or {}) do
      if m == rec.mon then
        monUid[m] = rec.uid
        local FD, data = try("src.world.FieldDefaults"), gameData()
        local perStep = 1
        if FD and type(FD.world) == "function" then
          local ok, v = pcall(FD.world, data, "daycareExpPerStep")
          if ok and tonumber(v) and v > 0 then perStep = tonumber(v) end
        end
        local steps = math.floor(math.max(0, (tonumber(m.exp) or 0) - (rec.exp0 or 0)) / perStep)
        queueOp({ kind = "withdraw", uid = rec.uid, steps = steps, mon = m, save = save })
        opCounts.withdraws = opCounts.withdraws + 1
        return { kind = "withdraw", uid = rec.uid, steps = steps }
      end
    end
    return { kind = "lost", uid = rec.uid }
  end

  -- The boarded member's uid (for the snapshot's sake: never in the party).
  function engine.daycareUid()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    local dc = type(st) == "table" and st.daycare or nil
    return type(dc) == "table" and dc.save == engine.save() and dc.uid or nil
  end

  -- The nicknames of the party members with a uid: { { uid, nick } } (nick
  -- false when there is none), and the save they belong to.
  function engine.partyNicks()
    local save = engine.save()
    local out = {}
    for _, mon in ipairs(save and type(save.party) == "table" and save.party or {}) do
      local uid = monUid[mon]
      if uid then
        out[#out + 1] = { uid = uid, nick = type(mon.nickname) == "string" and mon.nickname or false }
      end
    end
    return out, save
  end


  -- ---- hidden-only state (FOREIGN_POKEMON_PLAN §3.5, R3; FP6)
  --
  -- The fields the hidden game writes on a record that Cobblemon has no home for, per uid: kb_party takes them
  -- before every sync apply (partyStates), keeps them in mod.save ("uidState") and puts them back after it
  -- (restoreStates), so a rebuilt record (a member back from Cobblemon's PC or daycare, a swap between visitor and
  -- native) gets them again. A kept record gets its own values back.
  local function stateCopy(v, depth)
    if type(v) ~= "table" then return v end
    if (depth or 0) > 4 then return nil end
    local out = {}
    for k, x in pairs(v) do
      if type(k) == "string" or type(k) == "number" then out[k] = stateCopy(x, (depth or 0) + 1) end
    end
    return out
  end
  -- Blue's: stat exp (src/pokemon/Pokemon.lua:75).
  local STATE_KEYS = { "statExp" }
  function engine.partyStates()
    local save = engine.save()
    local out = {}
    for _, mon in ipairs(save and type(save.party) == "table" and save.party or {}) do
      local uid = monUid[mon]
      if uid then
        local st = {}
        for _, k in ipairs(STATE_KEYS) do st[k] = stateCopy(mon[k]) end
        out[uid] = st
      end
    end
    return out
  end
  function engine.restoreStates(states)
    local save = engine.save()
    if type(states) ~= "table" then return 0 end
    local n = 0
    for _, mon in ipairs(save and type(save.party) == "table" and save.party or {}) do
      local st = monUid[mon] and states[monUid[mon]] or nil
      if type(st) == "table" then
        for _, k in ipairs(STATE_KEYS) do
          if st[k] ~= nil then mon[k] = stateCopy(st[k]) end
        end
        n = n + 1
      end
    end
    return n
  end

  -- FP4 (§4.3, §4.5): what kb_party watches for party.change, party.learn
  -- and party.forget on the members with a uid: { { uid, hp, status
  -- (protocol: slp psn brn frz par, or "none"), moves (numbers) } } (Gen 1
  -- has no friendship), and the save they belong to.
  local FACT_STATUS = { SLP = "slp", PSN = "psn", BRN = "brn", FRZ = "frz", PAR = "par", TOX = "psn" }
  function engine.partyFacts()
    local save = engine.save()
    local out = {}
    for _, mon in ipairs(save and type(save.party) == "table" and save.party or {}) do
      local uid = monUid[mon]
      if uid then
        local moves = {}
        for _, mv in ipairs(type(mon.moves) == "table" and mon.moves or {}) do
          local n = type(mv) == "table" and engine.moveNumOf(mv.id) or nil
          if n then moves[#moves + 1] = n end
        end
        out[#out + 1] = { uid = uid, hp = tonumber(mon.hp), status = FACT_STATUS[mon.status] or "none", moves = moves }
      end
    end
    return out, save
  end

  -- Renames waiting for Minecraft (party.rename), in the process-lifetime
  -- slot so a hot reload keeps them; taken once each.
  function engine.queueRename(uid, nick)
    local st = bridgeState()
    if not st then return end
    st.renames = type(st.renames) == "table" and st.renames or {}
    st.renames[#st.renames + 1] = { uid = uid, nick = nick, save = engine.save() }
  end
  function engine.takeRenames()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    local list = type(st) == "table" and st.renames or nil
    local out = {}
    if type(list) ~= "table" then return out end
    local save = engine.save()
    for _, r in ipairs(list) do
      if r.save == save then out[#out + 1] = { uid = r.uid, nick = r.nick } end
    end
    st.renames = {}
    return out
  end

  -- Plain counts for debug.status (M8 wave 2).
  function engine.contentCounts()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    return {
      directArmed = directCounts.armed, directTaken = directCounts.taken,
      directNative = directCounts.native, directDeclined = directCounts.declined,
      catchesCaught = catchCounts.caught, catchesReleased = catchCounts.released,
      catchesSkipped = catchCounts.skipped, catchesLost = catchCounts.lost,
      catchesPending = type(st) == "table" and type(st.catches) == "table" and #st.catches or 0,
      trades = opCounts.trades, tradeNoUid = opCounts.tradeNoUid,
      deposits = opCounts.deposits, withdraws = opCounts.withdraws, depositNoUid = opCounts.depositNoUid,
      opsPending = type(st) == "table" and type(st.ops) == "table" and #st.ops or 0,
      daycareHeld = engine.daycareUid() ~= nil,
      musicBattle = musicCounts.battle, musicVictory = musicCounts.victory, musicFailed = musicCounts.failed,
      -- M8 wave 3: wins through engageTrainer's own start, loss lines shown
      trainerStarts = trainerCounts.started, trainerStartsFailed = trainerCounts.failed,
      lossLines = trainerCounts.lines, lossLineSounds = trainerCounts.sounds,
    }
  end

  -- ------------------------------------------------------------ the story, departures, field moves (M8)
  --
  -- Story diffs (docs/PROTOCOL.md "Story rules (M8)", PLAN section 4.1
  -- "Story diffs") compare the live game with the map data the world was
  -- built from:
  --   * blocks: Map:setBlock writes into the map record's own blocks
  --     (src/world/Map.lua:244-249), which is the shared Data.maps record
  --     (MapLoader's build hands Map.new data.maps[mapId] itself,
  --     src/world/MapLoader.lua:33-46; Map.new keeps it as `def`,
  --     Map.lua:112-114), so the live block array is ow.map.def.blocks and
  --     the pristine one must be copied before any map loads: at mods.loaded
  --     (src/mods/Loader.lua:2237), which a boot runs before anything is on
  --     the stack (src/core/Game.lua:47-121) and a hot reload runs right
  --     after Data:reloadGenerated put the generated records back
  --     (src/dev/HotReload.lua:31-41; src/core/Data.lua:373-379). A map the
  --     copy lacks is read from the dataset view (the export's own source).
  --   * objects: an object's effective visibility is the engine's spawn
  --     filter, OverworldState.objectVisible (OC:232-248, exposed :244):
  --     the record's `hidden` default, then save.objectToggles by name,
  --     items taken and beaten statics by "<MAP>_obj_<index>".
  --   * a map (re)load is a new ow.npcs table: setMap is its only writer
  --     (OC:558), for warps, seam crossings, same-map warps and reloads; Cut
  --     regrowth and the door stamps happen there too (OC:486-495).

  do
    local pristine = { maps = {}, captured = false, source = nil }
    local objects -- byName[map][name], byKey["<MAP>_obj_<i>"] = { map, obj }, of the pristine records
    local dsView, dsTried

    local function copyMap(def)
      if type(def) ~= "table" or type(def.blocks) ~= "table" or type(def.width) ~= "number" then return nil end
      local blocks = {}
      for i = 1, #def.blocks do blocks[i] = def.blocks[i] end
      local objs = {}
      for _, o in ipairs(type(def.objects) == "table" and def.objects or {}) do
        -- runtime objects a mod adds (OC:5750-5764) are never map data
        if type(o) == "table" and not o.runtime then
          objs[#objs + 1] = { index = o.index, name = o.name, hidden = o.hidden, item = o.item, pokemon = o.pokemon }
        end
      end
      return { width = def.width, height = tonumber(def.height) or 0, blocks = blocks, objects = objs }
    end

    -- mods.loaded's payload { loader, data } (Loader.lua:2163). Returns how
    -- many maps were copied.
    function engine.capturePristine(data)
      local maps = type(data) == "table" and data.maps or nil
      if type(maps) ~= "table" then return 0 end
      local out, n = {}, 0
      for id, def in pairs(maps) do
        local copy = type(id) == "string" and copyMap(def) or nil
        if copy then
          out[id] = copy
          n = n + 1
        end
      end
      pristine = { maps = out, captured = true, source = "boot" }
      objects = nil
      return n
    end

    -- One map's pristine record (a copy kb_engine owns: callers only read
    -- it), from the boot copy or else the dataset view (DatasetViews' get
    -- hands out detached copies, src/mods/DatasetViews.lua:368-378).
    local function pristineMap(mapId)
      local m = pristine.maps[mapId]
      if m then return m end
      if type(mapId) ~= "string" then return nil end
      if not dsTried then
        dsTried = true
        dsView = engine.openDataset(engine.gameVersion())
      end
      local reg = dsView and dsView.content and dsView.content.maps or nil
      if not (reg and type(reg.get) == "function") then return nil end
      local ok, def = pcall(reg.get, reg, mapId)
      m = ok and copyMap(def) or nil
      if m then pristine.maps[mapId] = m end
      return m
    end

    function engine.pristineBlocks(mapId)
      local m = pristineMap(mapId)
      return m and m.blocks or nil, m and m.width or nil
    end

    function engine.pristineCaptured() return pristine.captured end

    -- The pristine block at (bx, by) of a map, or nil.
    function engine.pristineBlockAt(mapId, bx, by)
      local m = pristineMap(mapId)
      if not (m and type(bx) == "number" and type(by) == "number") then return nil end
      if bx < 0 or by < 0 or bx >= m.width or by >= m.height then return nil end
      return m.blocks[by * m.width + bx + 1]
    end

    -- The current map for the story: its id, size in blocks and a load
    -- serial that changes on every setMap (a new ow.npcs table, OC:558).
    local loadTable, loadSerial = nil, 0
    function engine.storyMap()
      local ow = engine.overworld()
      local m = ow and ow.map
      local def = type(m) == "table" and m.def or nil
      if type(def) ~= "table" or type(def.blocks) ~= "table" or not symbol(m.id) then return nil end
      if ow.npcs ~= loadTable then loadTable, loadSerial = ow.npcs, loadSerial + 1 end
      return { map = m.id, width = tonumber(def.width) or 0, height = tonumber(def.height) or 0, load = loadSerial }
    end

    -- A copy of the current map's live blocks (Map:blockAt reads the same
    -- array, Map.lua:193-198).
    function engine.liveBlocks()
      local ow = engine.overworld()
      local blocks = ow and ow.map and type(ow.map.def) == "table" and ow.map.def.blocks or nil
      if type(blocks) ~= "table" then return nil end
      local out = {}
      for i = 1, #blocks do out[i] = blocks[i] end
      return out
    end

    -- The live blocks that differ from `known` (the caller's plain array),
    -- as { { index, block }, ... }, or nil when none do. Allocates nothing
    -- when nothing changed (it runs every step).
    function engine.liveBlockChanges(known)
      local ow = engine.overworld()
      local blocks = ow and ow.map and type(ow.map.def) == "table" and ow.map.def.blocks or nil
      if type(blocks) ~= "table" or type(known) ~= "table" then return nil end
      local out
      for i = 1, #blocks do
        local b = blocks[i]
        if b ~= known[i] then
          out = out or {}
          out[#out + 1] = { i, b }
        end
      end
      return out
    end

    -- The engine's own record of this visit's cuts on a map, { bx, by,
    -- block = the block before the cut } (tryCut, OC:3430-3434; cleared by
    -- the regrowth on the map's next load, OC:488-494), copied.
    function engine.cutRecords(mapId)
      local ow = engine.overworld()
      local list = ow and type(ow.cutBlocks) == "table" and ow.cutBlocks[mapId] or nil
      local out = {}
      for _, c in ipairs(type(list) == "table" and list or {}) do
        if type(c) == "table" then out[#out + 1] = { bx = c.bx, by = c.by, block = c.block } end
      end
      return out
    end

    -- field.cutTreeSwaps as before -> after (tryCut takes the first row
    -- whose before matches, OC:3422-3425).
    function engine.cutSwaps()
      local data = gameData()
      local rows = data and type(data.field) == "table" and data.field.cutTreeSwaps or nil
      local out = {}
      for _, r in ipairs(type(rows) == "table" and rows or {}) do
        if type(r) == "table" and r.before ~= nil and out[r.before] == nil then out[r.before] = r.after end
      end
      return out
    end

    -- The field tables the swap sites come from, as the engine reads them:
    -- FieldDefaults.fieldValue falls back to the engine's own seeds
    -- (src/world/FieldDefaults.lua:247-252: the Rocket Hideout lift gates,
    -- the trash-can door), copied.
    function engine.swapField()
      local data = gameData()
      local field = data and type(data.field) == "table" and data.field or {}
      local FD = try("src.world.FieldDefaults")
      local function value(...)
        if FD and type(FD.fieldValue) == "function" then
          local ok, v = pcall(FD.fieldValue, data, ...)
          if ok then return v end
        end
        local node = field
        for _, k in ipairs({ ... }) do node = type(node) == "table" and node[k] or nil end
        return node
      end
      local ck = type(field.cardKeyDoors) == "table" and field.cardKeyDoors or {}
      local out = {
        cardKeyDoors = {
          maps = ck.maps, openBlock = ck.openBlock, silphCo11F = ck.silphCo11F,
          closedDoors = value("cardKeyDoors", "closedDoors"), skipMaps = value("cardKeyDoors", "skipMaps"),
        },
        gameCornerPoster = field.gameCornerPoster,
        hiddenExtras = { trashCans = value("hiddenExtras", "trashCans") },
      }
      return (copyPlain(out, {}, 0))
    end

    -- objects of every map, from the pristine copy (or the live records
    -- when there is none: map objects never change in play, OC:5750-5764
    -- adds only runtime ones, which are skipped)
    local function objectIndex()
      if objects then return objects end
      local byName, byKey = {}, {}
      local source = pristine.maps
      if not pristine.captured then
        source = {}
        local data = gameData()
        for id, def in pairs(data and type(data.maps) == "table" and data.maps or {}) do
          if type(id) == "string" then source[id] = copyMap(def) end
        end
      end
      for id, m in pairs(source) do
        local names = {}
        byName[id] = names
        for _, o in ipairs(m.objects) do
          if type(o.name) == "string" then names[o.name] = o end
          if type(o.index) == "number" then byKey[id .. "_obj_" .. o.index] = { map = id, obj = o } end
        end
      end
      objects = { byName = byName, byKey = byKey }
      return objects
    end

    -- objectVisible's rule (OC:232-248), for an engine that doesn't expose it
    local function ruleVisible(save, mapId, obj)
      local toggles = type(save.objectToggles) == "table" and save.objectToggles[mapId] or nil
      local visible = not obj.hidden
      if obj.name and type(toggles) == "table" and toggles[obj.name] ~= nil then visible = toggles[obj.name] end
      local key = mapId .. "_obj_" .. tostring(obj.index)
      if obj.item and type(save.itemsTaken) == "table" and save.itemsTaken[key] then visible = false end
      if obj.pokemon and type(save.defeatedTrainers) == "table" and save.defeatedTrainers[key] then visible = false end
      return visible and true or false
    end

    -- Effective visibility for the story: every object whose effective
    -- visibility differs from its record's `hidden` default, as
    -- { [map] = { [name] = visible } } (names that aren't symbols are left
    -- out and counted). Only objects a toggle, an item taken or a beaten
    -- static can touch are looked at.
    function engine.storyToggles()
      local save = engine.save()
      local out, skipped = {}, 0
      if type(save) ~= "table" then return out, skipped end
      local idx = objectIndex()
      local OC = try("src.world.OverworldController")
      local visibleFn = OC and type(OC.objectVisible) == "function" and OC.objectVisible or nil
      local candidates = {}
      for map, names in pairs(type(save.objectToggles) == "table" and save.objectToggles or {}) do
        local byName = idx.byName[map]
        if byName and type(names) == "table" then
          for name in pairs(names) do
            local o = byName[name]
            if o then candidates[o] = map end
          end
        end
      end
      for key in pairs(type(save.itemsTaken) == "table" and save.itemsTaken or {}) do
        local e = idx.byKey[key]
        if e and e.obj.item then candidates[e.obj] = e.map end
      end
      for key in pairs(type(save.defeatedTrainers) == "table" and save.defeatedTrainers or {}) do
        local e = idx.byKey[key]
        if e and e.obj.pokemon then candidates[e.obj] = e.map end
      end
      for obj, map in pairs(candidates) do
        local visible
        if visibleFn then
          local ok, v = pcall(visibleFn, save, map, obj)
          if ok then visible = v and true or false end
        end
        if visible == nil then visible = ruleVisible(save, map, obj) end
        if visible ~= (not obj.hidden) then
          if type(obj.name) == "string" and #obj.name <= 96 and obj.name:find(SYMBOL) then
            out[map] = out[map] or {}
            out[map][obj.name] = visible
          else
            skipped = skipped + 1
          end
        end
      end
      return out, skipped
    end

    -- Marks the teleportOut spin just started as `kind` (dig, rope,
    -- teleport); observe() reports it as the departure (teleportKind).
    function engine.markDeparture(kind)
      local ow, st = engine.overworld(), bridgeState()
      if ow and st and ow.teleportOut ~= nil then st.departure = { kind = kind, out = ow.teleportOut } end
    end
  end

  -- ---- field moves from Minecraft (act.field)
  --
  -- The party menu's and the bag's own entry points, the ones the supported
  -- mod.world:useFieldAction / flyTo facade calls (src/world/WorldAPI.lua
  -- :190-324), after the checks the engine makes, so a refusal names its
  -- reason:
  --   the world takes input   WorldAPI's acceptsMenuInput (:26-35, a local)
  --   the move                a shadow-party member knows it, and the badge
  --                           of constants.hmBadges (src/world/FieldDefaults
  --                           .lua:220-224) is in the bag: partyKnowsVanilla
  --                           (OC:2327-2341); then OverworldState:partyKnows
  --                           itself (OC:2343-2350), which a mod's
  --                           fieldmove.eligibility hook may answer
  --   cut                     useCutFieldMove (OC:3527-3550), tryCut on the
  --                           cell in front (OC:3404-3459)
  --   surf                    useSurfFieldMove (OC:3464-3516), trySurf
  --                           (OC:3349-3383) or stopSurfing (OC:3385-3392)
  --   strength                useStrengthFieldMove (OC:1193-1214), not when
  --                           already active (the facade's own rule)
  --   flash                   useFlashFieldMove (OC:1176-1191), only while
  --                           the map is dark (ow.dark, the facade's rule)
  --   dig / teleport          beginTeleportOut (OC:2583-2612) where the party
  --                           menu allows it (PartyMenu.lua:708-735) and a
  --                           heal point exists
  --   rope                    action dig with item ESCAPE_ROPE: the bag's
  --                           branch (BagMenu.lua:329-353): the item used up
  --                           with Bag.remove (src/inventory/Bag.lua:464-469)
  --   bicycle                 useBicycle (OC:1154-1171) after the bag's checks
  --                           (BagMenu.lua:173-218; ItemEffects.lua:636-647)
  --   fish                    useFishingRod (OC:2544-2550) with the rod
  --   fly                     flyTo (OC:2554-2571) to a destination the Town
  --                           Map would list (WorldAPI.lua:69-80), or, with no
  --                           destination, the native fly list: Screens.push
  --                           TownMap { fly, onFly } as the party menu does
  --                           (PartyMenu.lua:582-585; src/ui/Screens.lua)
  do
    local RODS = { "SUPER_ROD", "GOOD_ROD", "OLD_ROD" } -- best first (WorldAPI.lua:24)
    local ROD = { SUPER_ROD = true, GOOD_ROD = true, OLD_ROD = true }
    local DIG_PLACES = { FOREST = true, CEMETERY = true, CAVERN = true, FACILITY = true, INTERIOR = true }

    local function takesInput(ow)
      local stack = game and game.stack
      local runner = ow.runner
      return stack and stack.top and stack:top() == ow
        and not ow.transitioning and not ow.flyAnim and not ow.teleportOut
        and not ow.engaging and not ow.emote and not ow.pikaHop and not ow.healAnim
        and not (ow.player and (ow.player.moving or ow.player.inputLocked))
        and not (runner and runner.isRunning and runner:isRunning())
        and #(ow.scriptMoves or {}) == 0
    end

    local function knows(save, move)
      for _, mon in ipairs(type(save.party) == "table" and save.party or {}) do
        for _, mv in ipairs(type(mon) == "table" and type(mon.moves) == "table" and mon.moves or {}) do
          if type(mv) == "table" and mv.id == move then return true end
        end
      end
      return false
    end

    local function badgeOf(move)
      local FD = try("src.world.FieldDefaults")
      local gates = FD and type(FD.constant) == "function" and FD.constant(gameData(), "hmBadges") or nil
      local gate = type(gates) == "table" and gates[move] or nil
      return type(gate) == "table" and gate.badge or nil
    end

    -- nil when the party may use the move (the engine's own partyKnows
    -- decides, a mod's eligibility hook included), else why not: the move
    -- missing from the shadow party, else its badge
    local function eligible(ow, save, move)
      local ok, mon = pcall(ow.partyKnows, ow, move)
      if ok and mon then return nil end
      if not knows(save, move) then return "no_move" end
      local badge = badgeOf(move)
      local inv = type(save.inventory) == "table" and save.inventory or {}
      if badge and not inv[badge] then return "no_badge" end
      return "no_move"
    end

    local function call(ow, method, ...)
      local fn = ow[method]
      if type(fn) ~= "function" then return false, nil end
      return pcall(fn, ow, ...)
    end

    local function hasHeal(save) return type(save.lastHeal) == "table" end

    local function tilesetOf(ow) return type(ow.map.def) == "table" and ow.map.def.tileset or nil end

    local function flyable(save, mapId)
      local data = gameData()
      local field = data and type(data.field) == "table" and data.field or {}
      local Map = try("src.world.Map")
      for _, id in ipairs(type(field.flyOrder) == "table" and field.flyOrder or {}) do
        if id == mapId then
          local def = data.maps and data.maps[id]
          local town = Map and type(Map.isFlyTown) == "function" and def and Map.isFlyTown(def)
          return type(save.visited) == "table" and save.visited[id] and type(field.flyWarps) == "table"
            and field.flyWarps[id] and town and true or false
        end
      end
      return false
    end

    local function anyFlyable(save)
      local data = gameData()
      local field = data and type(data.field) == "table" and data.field or {}
      for _, id in ipairs(type(field.flyOrder) == "table" and field.flyOrder or {}) do
        if flyable(save, id) then return true end
      end
      return false
    end

    local function teleportOut(ow, kind)
      local ok = call(ow, "beginTeleportOut")
      if not ok or ow.teleportOut == nil then return false, "failed" end
      engine.markDeparture(kind)
      return true, kind
    end

    local actions = {}

    function actions.cut(ow, save, p)
      local why = eligible(ow, save, "CUT")
      if why then return false, why end
      local ok, verdict = call(ow, "useCutFieldMove")
      if not ok then return false, "failed" end
      if verdict == "no_badge" then return false, "no_badge" end
      if verdict ~= "ok" then return false, "no_target" end
      local fx, fy = p:facingCell()
      local okCut, did = call(ow, "tryCut", fx, fy)
      if okCut and did then return true, "cut" end
      return false, "failed"
    end

    function actions.surf(ow, save, p)
      local why = eligible(ow, save, "SURF")
      if why then return false, why end
      local ok, verdict = call(ow, "useSurfFieldMove")
      if not ok then return false, "failed" end
      if verdict == "no_badge" then return false, "no_badge" end
      if verdict == "forced_bike" or verdict == "current" then return false, "not_here" end
      if verdict == "dismount" then
        if call(ow, "stopSurfing") then return true, "dismount" end
        return false, "failed"
      end
      if verdict ~= "ok" then return false, "no_target" end
      local fx, fy = p:facingCell()
      if call(ow, "trySurf", fx, fy) then return true, "surf" end
      return false, "failed"
    end

    function actions.strength(ow, save)
      local why = eligible(ow, save, "STRENGTH")
      if why then return false, why end
      if ow.strengthActive then return false, "already" end
      local ok, did = call(ow, "useStrengthFieldMove")
      if ok and did then return true, "strength" end
      return false, "failed"
    end

    function actions.flash(ow, save)
      local why = eligible(ow, save, "FLASH")
      if why then return false, why end
      if not ow.dark then return false, "not_here" end
      if call(ow, "useFlashFieldMove") then return true, "flash" end
      return false, "failed"
    end

    function actions.dig(ow, save, _, opts)
      local rope = opts.item == "ESCAPE_ROPE"
      if opts.item ~= nil and not rope then return false, "no_item" end
      if rope then
        local inv = type(save.inventory) == "table" and save.inventory or {}
        if (tonumber(inv.ESCAPE_ROPE) or 0) < 1 then return false, "no_item" end
      else
        local why = eligible(ow, save, "DIG")
        if why then return false, why end
      end
      if not (DIG_PLACES[tilesetOf(ow)] and ow.map.id ~= "AGATHAS_ROOM") or not hasHeal(save) then
        return false, "not_here"
      end
      if rope then
        local Bag = try("src.inventory.Bag")
        if not (Bag and type(Bag.remove) == "function" and pcall(Bag.remove, save, "ESCAPE_ROPE", 1, gameData())) then
          return false, "failed"
        end
        return teleportOut(ow, "rope")
      end
      return teleportOut(ow, "dig")
    end

    function actions.teleport(ow, save)
      local why = eligible(ow, save, "TELEPORT")
      if why then return false, why end
      if not outsideMap(ow) or not hasHeal(save) then return false, "not_here" end
      return teleportOut(ow, "teleport")
    end

    function actions.bicycle(ow, save, p)
      local inv = type(save.inventory) == "table" and save.inventory or {}
      if (tonumber(inv.BICYCLE) or 0) < 1 then return false, "no_item" end
      if p.surfing then return false, "not_here" end
      if save.onBike and save.forcedBike then return false, "not_here" end
      if not save.onBike then
        local ok, allowed = call(ow, "bikeAllowed", ow.map.id)
        if not (ok and allowed) then return false, "not_here" end
      end
      local ok, did = call(ow, "useBicycle")
      if not (ok and did) then return false, "failed" end
      return true, save.onBike and "bike_on" or "bike_off"
    end

    function actions.fish(ow, save, p, opts)
      local inv = type(save.inventory) == "table" and save.inventory or {}
      local rod = opts.item
      if rod ~= nil then
        if not ROD[rod] or (tonumber(inv[rod]) or 0) < 1 then return false, "no_item" end
      else
        for _, id in ipairs(RODS) do
          if (tonumber(inv[id]) or 0) >= 1 then rod = id break end
        end
        if not rod then return false, "no_item" end
      end
      if p.surfing then return false, "not_here" end
      local okW, water = call(ow, "facingIsShoreOrWater")
      if not (okW and water) then return false, "no_target" end
      local ok, did = call(ow, "useFishingRod", rod)
      if ok and did then return true, "fish" end
      return false, "failed"
    end

    function actions.fly(ow, save, _, opts)
      local why = eligible(ow, save, "FLY")
      if why then return false, why end
      if not outsideMap(ow) then return false, "not_here" end
      if opts.dest ~= nil then
        if not flyable(save, opts.dest) then return false, "no_target" end
        if call(ow, "flyTo", opts.dest) and (ow.flyFade or ow.flyAnim) then return true, "fly" end
        return false, "failed"
      end
      if not anyFlyable(save) then return false, "no_target" end
      local Screens = try("src.ui.Screens")
      if not (Screens and type(Screens.push) == "function") then return false, "failed" end
      local okPush = pcall(Screens.push, game, "TownMap", { fly = true, onFly = function(mapId)
        if engine.overworld() == ow then ow:flyTo(mapId) end
      end })
      if okPush then return true, "fly_list" end
      return false, "failed"
    end

    -- Performs act.field's action now: true plus the kind, or false plus
    -- the reason (not_ingame, not_idle, no_move, no_badge, no_target,
    -- not_here, no_item, already, failed). The caller has already set the
    -- facing for a target cell.
    function engine.fieldAction(action, opts)
      opts = type(opts) == "table" and opts or {}
      local ow, save = engine.overworld(), engine.save()
      if not (ow and type(save) == "table" and type(ow.player) == "table") then return false, "not_ingame" end
      local fn = actions[action]
      if not fn then return false, "bad_action" end
      if not takesInput(ow) then return false, "not_idle" end
      local ok, done, what = pcall(fn, ow, save, ow.player, opts)
      if not ok then
        warnAt("engine.field_failed", done)
        return false, "failed"
      end
      return done == true, what
    end

  end

  -- ------------------------------------------------------------ save slots, online pairing (M9)
  --
  -- DESIGN section 8 "Saving": each multiplayer world gets its own paired
  -- online story save, kept apart from the offline one (act/kb_pairing.lua).
  -- Gen1Recomp keeps a version's playthroughs in numbered slots,
  -- saves/<version>/slot<N>.lua (src/core/SaveData.lua:1254-1296),
  -- registered in options.lua as saveSlots[version] = { list, active, names }
  -- (:1327-1345, read through SaveData.loadOptions :1025-1064). The ACTIVE
  -- slot is where SaveData.save (:2785-2838) and SaveData.load (:2845-2887)
  -- land, through saveNames (:1484-1497), which resolves it once per process
  -- (ensureSlots :1451-1482: an existing registry wins; else a flat legacy
  -- save is migrated into slot1, :1370-1405; else orphaned slot files are
  -- adopted, :1419-1449; else the flat legacy path, "slot 0" here).
  --
  -- Switching at runtime goes through the engine's own slot API:
  --   * SaveData.setActiveSlot(version, id) (:1705-1730) registers id if it
  --     is new and makes it active in options.lua AND in the process cache:
  --     the very next CONTINUE or save lands there;
  --   * SaveData.createSlot(version) (:1732-1759) registers a new empty slot
  --     one past the highest number (never a lingering file's id) without
  --     activating it; renameSlot (:1670-1703) labels it for the launcher;
  --     deleteSlot (:1844-1900) takes one back;
  --   * refreshSlotResolution (:2058-2072) drops the once-per-process
  --     resolution, so a flat save written in this process is migrated into
  --     slot1 before any slot is registered beside it (a registry hides a
  --     flat save for good, :1463-1465);
  --   * a progress save rewrites options.lua from the live options snapshot,
  --     but rememberPlaythroughId copies the slot registry from disk first
  --     (:2364-2378), so a switch is never undone by a later save;
  --   * at the title, CONTINUE reads the active slot when it is chosen
  --     (Game:makeTitleState, src/core/Game.lua:224-242), and the title menu
  --     builds its CONTINUE row from the active slot's file when it opens
  --     (src/ui/TitleState.lua:469-477, :541-559), so a menu already open is
  --     rebuilt with Game:returnToTitle (Game.lua:244-251, the START menu
  --     QUIT's own path);
  --   * in game, the live playthrough belongs to the active slot: it is saved
  --     there first (Game:writeSave, Game.lua:1362-1385, behind the F1
  --     quick-save gate :1353-1371, as the center save), then the slot
  --     switches and the game goes back to the title. A New Game never saved
  --     has no meta.savedAt (SaveData.newGame :3026-3036; buildMeta stamps it
  --     on every writeSave, :2331-2362, Game.lua:1373-1375).
  -- A cart (SaveData.getCart :1895-1897) has its own slot scope
  -- (activeScopeKey :1290-1293), which the version's registry doesn't reach.
  -- The --slot launch option is the same setActiveSlot, by id ("slot2") or
  -- by 1-based list index (src/core/LaunchOptions.lua:349-374).

  local function slotId(v)
    return type(v) == "string" and v:find("^slot%d+$") ~= nil and #v <= 16
  end

  local function slotApi()
    local SaveData = try("src.core.SaveData")
    if not SaveData then return nil end
    for _, name in ipairs({ "activeSlot", "setActiveSlot", "createSlot" }) do
      if type(SaveData[name]) ~= "function" then return nil end
    end
    return SaveData
  end

  -- What pairing needs to know: { version, api (the switching API is
  -- there), cart (a cart is active), active (the active slot's id, or nil
  -- for the flat legacy path) }.
  function engine.slotState()
    local version = engine.gameVersion()
    local out = { version = version, api = slotApi() ~= nil and version ~= nil, cart = false }
    local SaveData = try("src.core.SaveData")
    if not (version and SaveData) then return out end
    if type(SaveData.getCart) == "function" then
      local ok, cart = pcall(SaveData.getCart)
      out.cart = ok and cart ~= nil
    end
    if type(SaveData.activeSlot) == "function" then
      local ok, id = pcall(SaveData.activeSlot, version)
      out.active = ok and slotId(id) and id or nil
    end
    return out
  end

  -- The active slot after resolving again (a flat save written in this
  -- process becomes slot1 first), or nil.
  function engine.resolveActiveSlot()
    local SaveData, version = slotApi(), engine.gameVersion()
    if not (SaveData and version) then return nil end
    if type(SaveData.refreshSlotResolution) == "function" then pcall(SaveData.refreshSlotResolution, version) end
    local ok, id = pcall(SaveData.activeSlot, version)
    return ok and slotId(id) and id or nil
  end

  -- True when the slot is in the version's registry.
  function engine.slotRegistered(id)
    local SaveData, version = try("src.core.SaveData"), engine.gameVersion()
    if not (slotId(id) and SaveData and version and type(SaveData.loadOptions) == "function") then return false end
    local ok, opts = pcall(SaveData.loadOptions)
    local roots = ok and type(opts) == "table" and opts.saveSlots or nil
    local reg = type(roots) == "table" and roots[version] or nil
    for _, v in ipairs(type(reg) == "table" and type(reg.list) == "table" and reg.list or {}) do
      if v == id then return true end
    end
    return false
  end

  -- True when the slot holds a save: its file or one of its recovery copies
  -- (decodeSlot's order, :1324-1336) on the persistence root (:555-567).
  function engine.slotHasSave(id)
    local SaveData, version = try("src.core.SaveData"), engine.gameVersion()
    if not (slotId(id) and symbol(version) and SaveData and type(SaveData.persistenceFs) == "function") then
      return false
    end
    local ok, fs = pcall(SaveData.persistenceFs)
    if not (ok and type(fs) == "table" and type(fs.getInfo) == "function") then return false end
    local main = "saves/" .. version .. "/" .. id .. ".lua"
    for _, path in ipairs({ main, main .. ".tmp", main .. ".bak" }) do
      local okInfo, info = pcall(fs.getInfo, path)
      if okInfo and info then return true end
    end
    return false
  end

  -- Labels a slot in Gen1Recomp's launcher. True when it took.
  function engine.labelSlot(id, label)
    local SaveData, version = try("src.core.SaveData"), engine.gameVersion()
    if not (slotId(id) and SaveData and version and type(SaveData.renameSlot) == "function") then return false end
    local ok, done = pcall(SaveData.renameSlot, version, id, label)
    return ok and done == true
  end

  -- Registers a new empty slot (labelled when a label is given): its id,
  -- or nil.
  function engine.createSlot(label)
    local SaveData, version = slotApi(), engine.gameVersion()
    if not (SaveData and version) then return nil end
    local ok, id = pcall(SaveData.createSlot, version)
    if not ok then
      warnAt("engine.create_slot_failed", id)
      return nil
    end
    if not slotId(id) then return nil end
    if label then engine.labelSlot(id, label) end
    return id
  end

  -- Takes back a slot pairing created (its files and its registry row).
  function engine.deleteSlot(id)
    local SaveData, version = try("src.core.SaveData"), engine.gameVersion()
    if not (slotId(id) and SaveData and version and type(SaveData.deleteSlot) == "function") then return false end
    local ok, done = pcall(SaveData.deleteSlot, version, id)
    return ok and done == true
  end

  -- Makes the slot the active one (registering it again if the launcher
  -- deleted it): the next CONTINUE and every save land there.
  function engine.activateSlot(id)
    local SaveData, version = slotApi(), engine.gameVersion()
    if not (SaveData and version and slotId(id)) then return false end
    local ok, got = pcall(SaveData.setActiveSlot, version, id)
    if not ok then
      warnAt("engine.activate_slot_failed", got)
      return false
    end
    return got == id
  end

  -- The live playthrough: "title" (no overworld: the boot screens, the
  -- title, its menus), "fresh" (a New Game never saved) or "saved" (loaded
  -- by CONTINUE -- `loaded`, a set of save tables save.loaded brought -- or
  -- saved since).
  function engine.playthrough(loaded)
    if not engine.overworld() then return "title" end
    local save = engine.save()
    if type(save) ~= "table" then return "title" end
    if type(loaded) == "table" and loaded[save] then return "saved" end
    local meta = save.meta
    if type(meta) == "table" and meta.savedAt ~= nil then return "saved" end
    return "fresh"
  end

  -- True while something is open over the title (its menu, CONTINUE's info
  -- box, OPTION): its rows were built for the slot that was active then.
  -- Game:makeTitleState stamps screenId "TitleState" (Game.lua:240).
  function engine.titleMenuOpen()
    local states = game and game.stack and game.stack.states
    if type(states) ~= "table" then return false end
    for i = 1, #states do
      local s = states[i]
      if type(s) == "table" and s.screenId == "TitleState" then return i < #states end
    end
    return false
  end

  -- The save before a switch may run now: the overworld on top and
  -- settled, no battle held, the engine's own F1 gate open (the center
  -- save's gate, engine.centerSaveStep).
  function engine.switchGateOpen()
    local ow = engine.overworld()
    if not ow or ow.healAnim or engine.top() ~= ow or liveBattle() then return false end
    if type(game.writeSave) ~= "function" then return false end
    if type(game.quickSaveAllowed) == "function" then
      local ok, allowed = pcall(game.quickSaveAllowed, game)
      if not (ok and allowed) then return false end
    end
    return true
  end

  -- Saves the live playthrough into its own (active) slot before a switch:
  -- "written", "refused" (the save.write veto or a failed write) or
  -- "error". Its save.writing carries the reason "switch" (kb_save doesn't
  -- announce it: it belongs to the pairing being left).
  function engine.switchSave()
    local st = bridgeState()
    if not (st and type(game.writeSave) == "function") then return "error" end
    st.saving = "switch"
    local ok, written = pcall(game.writeSave, game)
    st.saving = nil
    if not ok then
      warnAt("engine.write_save_failed", written)
      return "error"
    end
    if written == false or written == nil then return "refused" end
    return "written"
  end

  -- Back to the title, as the START menu's QUIT (Game:returnToTitle):
  -- unsaved progress is gone, so callers save first.
  function engine.toTitle()
    if type(game) ~= "table" or type(game.returnToTitle) ~= "function" then return false end
    local ok, err = pcall(game.returnToTitle, game)
    if not ok then warnAt("engine.return_to_title_failed", err) end
    return ok
  end

  -- Forgets what the previous playthrough still had for Minecraft -- gifts,
  -- party operations, heals, renames, item uses, native catches, the
  -- daycare record, a save waiting to be announced -- which after a switch
  -- would reach the new world. Returns how many there were.
  function engine.dropPlaythrough()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    if type(st) ~= "table" then return 0 end
    local function count(list) return type(list) == "table" and #list or 0 end
    local n = count(st.gifts) + count(st.ops) + count(st.itemUses) + count(st.renames) + count(st.catches)
      + (tonumber(st.heals) or 0) + (st.saveQueued ~= nil and 1 or 0) + (st.daycare ~= nil and 1 or 0)
    st.gifts, st.ops, st.itemUses, st.renames, st.catches = {}, {}, {}, {}, {}
    st.heals, st.healHold, st.saveQueued, st.daycare = 0, nil, nil, nil
    return n
  end

  -- ------------------------------------------------------------ reliability (M10)
  --
  -- Save pairs (crash recovery; act/kb_save.lua, docs/PROTOCOL.md "Saving").
  -- Game:writeSave stamps the save's meta first (SaveData.buildMeta: savedAt
  -- = os.time(), sessionStart, the playthrough id carried over;
  -- src/core/Game.lua:1371-1375, src/core/SaveData.lua:2531-2562) and then
  -- emits save.writing, the moment "mods snapshot runtime state into their
  -- namespace" (Game.lua:1371-1378). mod.save IS the save table's own modData
  -- (Game:adoptSave, Game.lua:1348-1358; src/mods/Loader.lua:1617-1630), and
  -- SaveData.save serializes the whole save table -- modData and meta
  -- included -- into the active slot's file in one write (a .tmp witness and
  -- a .bak of the previous file, SaveData.lua:2785-2838). So what kb_save
  -- writes into mod.save during save.writing is part of that very save, and
  -- comes back with it on every CONTINUE: Game:restoreSave adopts the loaded
  -- save's modData before save.loaded (Game.lua:1537-1608), and F2 takes
  -- the same path (:864-873). Not used, because they live apart from the
  -- save: mod.storage writes its own files at once (src/mods/Storage.lua:
  -- 183-229, "independent of the vanilla progress checkpoint"), and the
  -- playthrough id names a playthrough, not one of its saves, and is filled
  -- in from options.lua on load when the file lacks it (SaveData.lua:
  -- 2675-2681). The Hall of Fame writes its save twice, the second time with
  -- SaveData.save and no event (src/script/Commands.lua:950-957): the same
  -- save table, modData and meta, so the same pair.

  -- A save table's meta as plain values: savedAt and sessionStart (Unix
  -- seconds, integers) and the engine version that wrote it.
  function engine.metaOf(save)
    if save == nil then save = engine.save() end
    local meta = type(save) == "table" and type(save.meta) == "table" and save.meta or nil
    if not meta then return {} end
    local function int(v)
      v = tonumber(v)
      if v and v == v and v >= 0 and v < 9007199254740992 then return math.floor(v) end
      return nil
    end
    return { savedAt = int(meta.savedAt), sessionStart = int(meta.sessionStart),
             engine = type(meta.engine) == "string" and #meta.engine <= 32 and meta.engine or nil }
  end

  -- Our own state that must outlive a hot reload (the process-lifetime
  -- slot): a table by name, created on first use; nil before game.ready.
  function engine.persistent(name)
    local st = bridgeState()
    if not st then return nil end
    st.keep = type(st.keep) == "table" and st.keep or {}
    local t = st.keep[name]
    if type(t) ~= "table" then
      t = {}
      st.keep[name] = t
    end
    return t
  end

  -- Running hidden (DESIGN section 9). love.run picks each frame's cap from
  -- love.window.isVisible() (main.lua:1907-1924): a window that isn't
  -- visible is capped at 10 frames a second, while Game:update catches up at
  -- most about 2 logic steps a frame (FixedStep.catchupLimit,
  -- src/core/FixedStep.lua:75-86, Game.lua:430-437): logic at a third of
  -- its speed. It also isn't drawn (main.lua:1930-1937), so the mirror gets
  -- no frames. Measured on Windows (tools/e2e/test_hidden.py): LÖVE 11.5's
  -- isVisible reads only SDL_WINDOW_SHOWN, which a MINIMISED window keeps,
  -- so minimising never slowed anything (60 logic steps a second); a HIDDEN
  -- window (ShowWindow SW_HIDE) drops to 20. While a client or test
  -- session is connected, isVisible answers true for a window that isn't
  -- shown, so the loop keeps its normal cap and draws, as LÖVE's own default
  -- love.run always does. Reached through the sandbox's love facade, which
  -- hands the real love.window through (src/mods/Sandbox.lua:112-152).
  -- Nothing else in the engine reads isVisible (PadHints.windowMinimized
  -- asks isMinimized first, src/core/PadHints.lua:19-31; the game3
  -- isVisible functions are other modules). A frame faked visible is paced
  -- to at most 60 a second here, since present() needn't wait on a window
  -- that isn't shown and the loop trusts it when vsync paces the cap
  -- (main.lua:1941-1960, PresentSync.hardwarePacesCap). The same holds for a
  -- MINIMISED window, which the loop runs at its normal cap: its present()
  -- returns at once, so the loop spun at 170-350 drawn frames a second
  -- (measured), taking CPU and GPU time from Minecraft; while a client or
  -- test session is connected its frames are paced to 60 a second as well
  -- (love.window.isMinimized, SDL_WINDOW_MINIMIZED). Logic stays at 60
  -- steps a second either way: FixedStep runs on real time. Without a
  -- client nothing changes: that is the engine's own behaviour. The wrapper
  -- is installed once into a process-lifetime slot on the Game object with
  -- the original function, answers only while its keep-alive (refreshed by
  -- every live step) is fresh, and is taken out again when the link drops
  -- and on a hot reload or a return to the launcher (the next instance's
  -- takeover, main.lua).
  local WINDOW_SLOT = "kantoBridgeWindow"
  Gen1.HIDDEN_KEEP_SECONDS = 1
  Gen1.HIDDEN_FRAME = 1 / 60
  local windowCounts = { installs = 0, removals = 0 }

  -- love.window and love.timer through the facade, looked up once (a
  -- module never changes; a failed lookup is tried again)
  local function index(t, k) return t[k] end
  local loveModules = {}
  local function loveModule(name)
    local m = loveModules[name]
    if m then return m end
    local lv = opts.love
    if lv == nil then return nil end
    local ok, value = pcall(index, lv, name)
    if ok and type(value) == "table" then
      loveModules[name] = value
      return value
    end
    return nil
  end
  local function loveWindow() return loveModule("window") end
  local function loveTimer() return loveModule("timer") end

  -- One frame, at most HIDDEN_FRAME after the one before: a carried
  -- deadline, anchored again after a stall (or the first frame).
  local function paceFrame(slot, timer, now)
    local deadline = slot.nextFrame
    if deadline == nil or now - deadline > Gen1.HIDDEN_FRAME then deadline = now end
    if deadline > now and type(timer.sleep) == "function" then
      timer.sleep(math.min(deadline - now, Gen1.HIDDEN_FRAME))
      slot.paced = (slot.paced or 0) + 1
    end
    slot.nextFrame = deadline + Gen1.HIDDEN_FRAME
  end

  -- love.window.isVisible, as the loop asks it once a frame (main.lua:1907):
  -- the window's own answer, except while the keep-alive is fresh: a window
  -- that isn't shown answers true (the normal cap, drawn), and its frames
  -- and a minimised window's are paced. Never throws.
  local function hiddenWrapper(slot)
    return function(...)
      local original = slot.original
      if type(original) ~= "function" then return true end
      local real = original(...)
      local ok, answer = pcall(function()
        local timer = slot.timer
        local now = timer and timer.getTime and timer.getTime() or nil
        if not (slot.active and now and now <= (slot.keepUntil or -math.huge)) then return real end
        if real then
          -- shown: the engine's own pacing, unless the window is minimised
          local isMinimized = slot.isMinimized
          if type(isMinimized) ~= "function" or isMinimized() ~= true then return real end
          paceFrame(slot, timer, now)
          slot.minimizedFrames = (slot.minimizedFrames or 0) + 1
          return real
        end
        paceFrame(slot, timer, now)
        slot.faked = (slot.faked or 0) + 1
        return true
      end)
      if ok then return answer end
      return real
    end
  end

  -- Each logic step: want = a client or test session is connected; now =
  -- love.timer.getTime(). Returns true while the wrapper is in place.
  function engine.runHidden(want, now)
    if type(game) ~= "table" then return false end
    local win = loveWindow()
    local slot = rawget(game, WINDOW_SLOT)
    if not want then
      if type(slot) == "table" and slot.wrapper ~= nil then
        slot.active = false
        if win and win.isVisible == slot.wrapper then
          win.isVisible = slot.original
          slot.wrapper = nil
          windowCounts.removals = windowCounts.removals + 1
        end
      end
      return false
    end
    if not (win and type(win.isVisible) == "function") then return false end
    if type(slot) ~= "table" then
      slot = { faked = 0, paced = 0 }
      rawset(game, WINDOW_SLOT, slot)
    end
    slot.timer = loveTimer()
    slot.isMinimized = type(win.isMinimized) == "function" and win.isMinimized or nil
    if slot.wrapper == nil then
      slot.original = win.isVisible
      slot.wrapper = hiddenWrapper(slot)
      win.isVisible = slot.wrapper
      windowCounts.installs = windowCounts.installs + 1
    end
    -- a wrapper someone else put over ours keeps calling ours: left alone
    slot.active = true
    slot.keepUntil = (tonumber(now) or 0) + Gen1.HIDDEN_KEEP_SECONDS
    return win.isVisible == slot.wrapper
  end

  -- Plain counts for debug.status: the wrapper in place, frames it answered
  -- visible for a window that wasn't, a minimised window's frames it saw,
  -- the frames it paced (both kinds), installs and removals, and the
  -- window's own answers (isVisible through the original, isMinimized).
  function engine.hiddenStats()
    local slot = type(game) == "table" and rawget(game, WINDOW_SLOT) or nil
    local win = loveWindow()
    local out = { installs = windowCounts.installs, removals = windowCounts.removals, faked = 0, paced = 0,
                  minimizedFrames = 0, active = false, visible = true, minimized = false }
    if type(slot) == "table" then
      out.faked, out.paced, out.minimizedFrames = slot.faked or 0, slot.paced or 0, slot.minimizedFrames or 0
      out.active = slot.active == true and slot.wrapper ~= nil and win ~= nil and win.isVisible == slot.wrapper
    end
    if win then
      local isVisible = (type(slot) == "table" and slot.wrapper ~= nil and win.isVisible == slot.wrapper)
        and slot.original or win.isVisible
      if type(isVisible) == "function" then
        local ok, v = pcall(isVisible)
        if ok then out.visible = v == true end
      end
      if type(win.isMinimized) == "function" then
        local ok, v = pcall(win.isMinimized)
        if ok then out.minimized = v == true end
      end
    end
    return out
  end

  -- ------------------------------------------------------------ export (M2)
  --
  -- The world export reads the active game's PRISTINE data. The live Data
  -- tables change during play: Map:setBlock writes into the map record's
  -- blocks (src/world/Map.lua:244-249) for Cut, card-key doors and script
  -- swaps, and elevator panels rewrite warps. So the export reads one of two
  -- sources, both of which hand out detached plain tables:
  --   * "datasets": mod.datasets:open(version) (src/mods/Loader.lua:1435-1443,
  --     src/mods/DatasetViews.lua:427-479) decodes the version's cache again
  --     and copies every record it hands out (get/each, :368-405);
  --   * "live": PLAN section 8's fallback, a deep copy of the live Data taken
  --     before any map has loaded (the mods.loaded payload's `data`,
  --     src/mods/Loader.lua:2237; Game:load runs the loader before anything
  --     is on the stack, src/core/Game.lua:47-121).
  --
  -- A source has: path ("datasets" or "live"); records(name, checkpoint),
  -- the sorted { {id, value}, ... } of a record registry (nil plus a reason
  -- when it can't be read); value(name, key), one key of a deep registry
  -- (field, constants). checkpoint() may yield the caller's coroutine; it is
  -- never called inside a pcall.

  -- The dataset view of a game version, or nil plus the reason open gave
  -- ("unknown_version", "not_imported", "invalid_cache", ...).
  function engine.openDataset(version)
    if not (datasets and datasets.open) then return nil, "no_datasets_api" end
    local ok, view, reason = pcall(datasets.open, datasets, version)
    if not ok then return nil, "open_failed" end
    if type(view) ~= "table" or type(view.content) ~= "table" then
      return nil, type(reason) == "string" and reason or "no_view"
    end
    return view
  end

  function engine.datasetSource(view)
    local src = { path = "datasets" }
    function src.records(name, checkpoint)
      local reg = view.content[name]
      if type(reg) ~= "table" or type(reg.each) ~= "function" then return nil, "no_registry" end
      local ok, iter = pcall(reg.each, reg)
      if not ok or type(iter) ~= "function" then return nil, "each_failed" end
      local out = {}
      while true do
        local okNext, id, value = pcall(iter)
        if not okNext then return nil, "read_failed" end
        if id == nil then break end
        if type(id) == "string" and value ~= nil then out[#out + 1] = { id = id, value = value } end
        if checkpoint then checkpoint() end
      end
      return out
    end
    function src.value(name, key)
      local reg = view.content[name]
      if type(reg) ~= "table" or type(reg.get) ~= "function" then return nil end
      local ok, value = pcall(reg.get, reg, key)
      return ok and value or nil
    end
    return src
  end

  -- Deep-copies the export's registries out of a live Data table now.
  -- checkpoint (optional) runs between records.
  function engine.liveSource(data, checkpoint)
    if type(data) ~= "table" then return nil, "no_data" end
    local snapshot, dropped = {}, 0
    for name, path in pairs(Gen1.EXPORT_TARGETS) do
      local node = data
      for i = 1, #path do node = type(node) == "table" and node[path[i]] or nil end
      local copy = {}
      if type(node) == "table" then
        for key, value in pairs(node) do
          if type(key) == "string" then
            local c, d = copyPlain(value, {}, 0)
            dropped = dropped + d
            if c ~= nil then copy[key] = c end
            if checkpoint then checkpoint() end
          end
        end
      end
      snapshot[name] = copy
    end
    local src = { path = "live", dropped = dropped }
    function src.records(name)
      local reg = snapshot[name]
      if not reg then return nil, "no_registry" end
      local ids = {}
      for id, value in pairs(reg) do
        if type(value) == "table" then ids[#ids + 1] = id end
      end
      table.sort(ids)
      local out = {}
      for i = 1, #ids do out[i] = { id = ids[i], value = reg[ids[i]] } end
      return out
    end
    function src.value(name, key)
      local reg = snapshot[name]
      return reg and reg[key] or nil
    end
    return src
  end

  -- True while an overworld is on the stack: a loaded map is what starts
  -- changing the live Data, so the late fallback refuses to copy it then.
  function engine.worldShowing()
    return engine.overworld() ~= nil
  end

  -- liveSource over the Game's own Data table (Game:load sets self.data =
  -- Data, src/core/Game.lua:46), so the live table never leaves this file.
  function engine.liveSourceFromGame(checkpoint)
    return engine.liveSource(game and type(game.data) == "table" and game.data or nil, checkpoint)
  end

  local function mapModule()
    local Map = try("src.world.Map")
    if Map and type(Map.new) == "function" then return Map end
    return nil
  end

  -- Per-cell questions, answered by the engine's own runtime map built from
  -- the records (Map.new, src/world/Map.lua:112-142). A cell's behaviour
  -- comes from its bottom-left 8x8 tile (cellTile :208-211); the queries are
  -- isWalkableCell :217, isGrassCell :221 (never off-map), isWaterCell :239
  -- (water + shore; the caller checks field.waterTilesets), isDoorTileCell
  -- :253, warpPadOrHoleAt :265, isCounterCell :272, and blockAt :193. (A warp
  -- fires on arrival on a door or warp tile, isWarpTileCell :258; the warp
  -- tiles are the tileset record's warpTiles, which the caller reads.)
  -- Coordinates are cells; the map is width*2 x height*2 of them.
  function engine.mapQuery(def, tileset)
    local Map = mapModule()
    if not Map then return nil, "no_map_module" end
    local ok, m = pcall(Map.new, def, tileset)
    if not ok or type(m) ~= "table" then return nil, "map_new_failed" end
    local q = { w = def.width * 2, h = def.height * 2 }
    function q.tile(cx, cy) return m:cellTile(cx, cy) end
    function q.walkable(cx, cy) return m:isWalkableCell(cx, cy) and true or false end
    function q.grass(cx, cy) return m:isGrassCell(cx, cy) and true or false end
    function q.water(cx, cy) return m:isWaterCell(cx, cy) and true or false end
    function q.door(cx, cy) return m:isDoorTileCell(cx, cy) and true or false end
    function q.counter(cx, cy) return m:isCounterCell(cx, cy) and true or false end
    function q.padOrHole(cx, cy) return m:warpPadOrHoleAt(cx, cy) end
    function q.block(bx, by) return m:blockAt(bx, by) end
    return q
  end

  -- Map.isOutside (src/world/Map.lua:146-158): OVERWORLD maps, plus the
  -- outsideTilesets the overworld passes in (field.outsideTilesets, as at
  -- src/world/OverworldController.lua:5569). This is what sets the LAST_MAP
  -- memory.
  function engine.isOutside(def, outsideTilesets)
    local Map = mapModule()
    if not (Map and Map.isOutside) then return nil end
    local ok, outside = pcall(Map.isOutside, def, outsideTilesets)
    if not ok then return nil end
    return outside and true or false
  end

  -- The tile classes of one tileset that come from engine constants (water,
  -- shore, warp pads and holes; src/world/Map.lua:20-41, :128-131, :265-269),
  -- asked of the engine itself: for every tile id, a 1x1 probe map whose
  -- cells are all that tile. Returns { water = {ids}, pads = {{tile, kind}} }
  -- (water includes shore; the caller applies field.waterTilesets).
  function engine.tileProbe(tilesetId, tileset)
    local Map = mapModule()
    if not Map or type(tileset) ~= "table" then return nil, "no_map_module" end
    local row = {}
    local probeTs = {}
    for k, v in pairs(tileset) do probeTs[k] = v end
    probeTs.blocks = { row }
    local def = { id = "KB_PROBE", tileset = tilesetId, width = 1, height = 1,
                  blocks = { 0 }, borderBlock = 0, warps = {}, signs = {}, objects = {} }
    local water, pads = {}, {}
    for t = 0, 255 do
      for i = 1, 16 do row[i] = t end
      local ok, m = pcall(Map.new, def, probeTs)
      if not ok or type(m) ~= "table" then return nil, "map_new_failed" end
      local okW, isWater = pcall(m.isWaterCell, m, 0, 0)
      if okW and isWater then water[#water + 1] = t end
      local okP, kind = pcall(m.warpPadOrHoleAt, m, 0, 0)
      if okP and (kind == "pad" or kind == "hole") then pads[#pads + 1] = { tile = t, kind = kind } end
    end
    return { water = water, pads = pads }
  end

  -- Shore tiles of a tileset (Gen1.SHORE_FALLBACK above).
  function engine.shoreTiles(tilesetId, tileset)
    local shore = type(tileset) == "table" and tileset.shoreTiles or nil
    if shore == nil and not Gen1.NO_SHORE_TILESETS[tilesetId] then shore = Gen1.SHORE_FALLBACK end
    local out = {}
    for _, t in ipairs(type(shore) == "table" and shore or {}) do out[#out + 1] = t end
    return out
  end

  function engine.cutTreeTiles(tilesetId)
    local out = {}
    for _, t in ipairs(Gen1.CUT_TREE_TILES[tilesetId] or {}) do out[#out + 1] = t end
    return out
  end

  -- Spinner-arrow tiles of a tileset, the tiles the spinner toggle patches:
  -- TileRenderer.SPINNER_ARROW_TILES (src/render/TileRenderer.lua:139-142).
  function engine.spinnerTiles(tilesetId)
    local TileRenderer = try("src.render.TileRenderer")
    local list = TileRenderer and type(TileRenderer.SPINNER_ARROW_TILES) == "table"
      and TileRenderer.SPINNER_ARROW_TILES[tilesetId] or nil
    local out = {}
    for _, t in ipairs(type(list) == "table" and list or {}) do
      if type(t) == "number" then out[#out + 1] = t end
    end
    return out
  end

  -- A tileset's animated tiles as the engine describes them: the record's own
  -- animatedTiles, else TileRenderer.defaultAnimatedTiles
  -- (src/render/TileRenderer.lua:311-332, precedence :538-539). Each entry has
  -- `tile` or `tiles`, a `kind` (hshift, frames, toggle), a `period`, and
  -- `offsets`, or `images` + `sequence`, or `image` + `stripOffsets` and a
  -- `gate`. Image fields are asset paths. The voxel plan's V2 exports them as
  -- data (kb_export's tileset `animated`). Engine internals: re-check on every
  -- pin change.
  function engine.animatedTiles(tileset)
    local TileRenderer = try("src.render.TileRenderer")
    local specs = type(tileset) == "table" and tileset.animatedTiles or nil
    if specs == nil and TileRenderer and TileRenderer.defaultAnimatedTiles then
      local ok, list = pcall(TileRenderer.defaultAnimatedTiles, tileset)
      specs = ok and list or nil
    end
    return type(specs) == "table" and specs or {}
  end

  -- The block the engine really draws around a map: TileRenderer.borderBlockFor
  -- (src/render/TileRenderer.lua:25-34), which reads the map's tileset and
  -- border block and, for OVERWORLD maps, the player's voidFill option. A
  -- block id, or false when it draws none; the map's own border block when the
  -- renderer can't be asked. Engine internals: re-check on every pin change.
  function engine.borderBlockFor(def)
    local TileRenderer = try("src.render.TileRenderer")
    if type(def) ~= "table" then return nil end
    if not (TileRenderer and type(TileRenderer.borderBlockFor) == "function") then return def.borderBlock end
    local ok, block = pcall(TileRenderer.borderBlockFor, { def = def })
    if not ok then return def.borderBlock end
    return block
  end

  -- The player's void-fill option and whether it is the engine's default: the
  -- renderer's TileRenderer.voidFill against the first of its
  -- TileRenderer.VOID_FILLS (src/render/TileRenderer.lua:21-23; setVoidFill
  -- falls back to it, :36-42). Decided here, so no engine constant is copied
  -- into the builder. nil when the renderer can't be asked.
  function engine.voidFill()
    local TileRenderer = try("src.render.TileRenderer")
    if not TileRenderer or type(TileRenderer.voidFill) ~= "string" then return nil, nil end
    local modes = TileRenderer.VOID_FILLS
    local default = type(modes) == "table" and modes[1] or nil
    return TileRenderer.voidFill, default ~= nil and TileRenderer.voidFill == default
  end

  -- Another installed mod's data file, as text (the profile plan,
  -- docs/specs/PROFILE_PLAN.md): mods.loaded's payload carries the loader
  -- (src/mods/Loader.lua:2237), whose `mods` lists every mod found under mods/,
  -- enabled or not, as { manifest, path } (:619-640; a disabled one is marked
  -- in `disabled`, :469-478), and whose `fs` is love.filesystem unless a test
  -- injects one (:393-419). The sandbox keeps mod:read inside a mod's own
  -- folder (:1786-1790), so this reads through the loader. The text is only
  -- returned, never run. Returns text, version, enabled; or nil and a reason
  -- code (no_loader, absent, no_file, too_big, unreadable). Engine internals:
  -- re-check on every pin change.
  function engine.modDataFile(loader, modId, rel, maxBytes)
    if type(loader) ~= "table" or type(loader.mods) ~= "table" then return nil, "no_loader" end
    local m = loader.mods[modId]
    if type(m) ~= "table" or type(m.path) ~= "string" then return nil, "absent" end
    local fs = loader.fs
    if type(fs) ~= "table" or type(fs.read) ~= "function" then return nil, "no_loader" end
    local path = m.path .. "/" .. rel
    if type(fs.getInfo) == "function" then
      local ok, info = pcall(fs.getInfo, path)
      if not ok or type(info) ~= "table" or info.type ~= "file" then return nil, "no_file" end
      if type(info.size) == "number" and info.size > maxBytes then return nil, "too_big" end
    end
    local ok, text = pcall(fs.read, path)
    if not ok or type(text) ~= "string" then return nil, "unreadable" end
    if #text == 0 then return nil, "no_file" end
    if #text > maxBytes then return nil, "too_big" end
    local manifest = type(m.manifest) == "table" and m.manifest or {}
    local version = type(manifest.version) == "string" and manifest.version or nil
    local enabled = not (type(loader.disabled) == "table" and loader.disabled[modId]) and m.enabled ~= false
    return text, version, enabled
  end

  -- The asset paths a tileset's tile animations draw from (engine.animatedTiles):
  -- each entry carries `images` (frames) or `image` (a strip).
  function engine.animationImages(tileset)
    local specs = engine.animatedTiles(tileset)
    local out = {}
    for _, spec in ipairs(type(specs) == "table" and specs or {}) do
      if type(spec) == "table" then
        for _, path in ipairs(type(spec.images) == "table" and spec.images or {}) do
          if type(path) == "string" then out[#out + 1] = path end
        end
        if type(spec.image) == "string" then out[#out + 1] = spec.image end
      end
    end
    return out
  end

  -- The engine's own neighbour placement: OverworldState.computeNeighbors
  -- (src/world/OverworldController.lua:301-344; the module returns
  -- OverworldState, :6914), a BFS over the connections that composes the
  -- strip offsets. With an unbounded hop count it places every connected
  -- map. Returns { {id, ox, oy}, ... } in world pixels (32 per block),
  -- without the root.
  function engine.computeNeighbors(maps, rootId)
    local OC = try("src.world.OverworldController")
    if not (OC and type(OC.computeNeighbors) == "function") then return nil, "no_overworld_module" end
    local ok, list = pcall(OC.computeNeighbors, maps, rootId, 1000000)
    if not ok or type(list) ~= "table" then return nil, "neighbors_failed" end
    local out = {}
    for _, entry in ipairs(list) do
      if type(entry) == "table" and type(entry.id) == "string" then
        out[#out + 1] = { id = entry.id, ox = entry.ox, oy = entry.oy }
      end
    end
    return out
  end

  -- The SGB palette cascade of OverworldState:paletteNameFor
  -- (src/world/OverworldController.lua:1016-1038): a map's own `palette`, then
  -- field.palettes byMap, byTileset and the byPrefix rows (its local
  -- paletteLookup, :973-985, restated here because it is a local); nil when
  -- nothing matches, which is when an interior takes its remembered outdoor
  -- map's palette, and then `default`.
  function engine.paletteLookup(palettes, mapId, tilesetId, ownPalette)
    if type(ownPalette) == "string" then return ownPalette end
    if type(palettes) ~= "table" or type(mapId) ~= "string" then return nil end
    local byMap = palettes.byMap
    if type(byMap) == "table" and byMap[mapId] then return byMap[mapId] end
    local byTileset = palettes.byTileset
    if type(byTileset) == "table" and tilesetId and byTileset[tilesetId] then return byTileset[tilesetId] end
    for _, row in ipairs(type(palettes.byPrefix) == "table" and palettes.byPrefix or {}) do
      if type(row) == "table" and type(row.prefix) == "string"
          and mapId:find(row.prefix, 1, true) == 1 then
        return row.palette
      end
    end
    return nil
  end

  -- PNG bytes of one asset, loaded the way the engine loads it:
  -- Assets.imageData (src/render/Assets.lua:141-148; mod overrides first, then
  -- the active version's mounted cache), encoded like the importer writes
  -- PNGs, ImageData:encode("png") with no filename
  -- (src/import/ImageWriter.lua:167-179). Returns png, width, height, or nil
  -- plus a reason.
  function engine.imagePng(path)
    local Assets = try("src.render.Assets")
    if not (Assets and type(Assets.imageData) == "function") then return nil, "no_assets_module" end
    local ok, data = pcall(Assets.imageData, path)
    if not ok or data == nil then return nil, "load_failed" end
    local okDims, w, h = pcall(function() return data:getWidth(), data:getHeight() end)
    local okEnc, file = pcall(data.encode, data, "png")
    local png
    if okEnc and file ~= nil then
      local okStr, bytes = pcall(file.getString, file)
      if okStr and type(bytes) == "string" then png = bytes end
      if file.release then pcall(file.release, file) end
    end
    if data.release then pcall(data.release, data) end
    if not okDims or type(w) ~= "number" or type(h) ~= "number" then return nil, "no_dimensions" end
    if not png then return nil, "encode_failed" end
    return png, w, h
  end

  -- ---- Wild Routes (WR2: act/kb_route.lua, docs/specs/WILD_ROUTES_PLAN.md §4.2)

  -- How many of an item the bag holds (save.inventory[ITEM] = qty,
  -- src/inventory/Bag.lua); 0 for none.
  function engine.inventoryCount(item)
    local save = engine.save()
    local inv = save and type(save.inventory) == "table" and save.inventory or nil
    local n = inv and tonumber(inv[item]) or 0
    return math.floor(n)
  end

  -- A zone trainer's party, written into its KB_<CLASS> record's first party
  -- (Data.trainers: the table BattleState.newTrainer and engine.trainerTeam
  -- read; parties are lists of { species, level, moves? }). False when the
  -- record is missing.
  function engine.writeTrainerParty(cls, party)
    local data = gameData()
    local tr = data and type(data.trainers) == "table" and data.trainers[cls] or nil
    if type(tr) ~= "table" or type(party) ~= "table" or #party == 0 then return false end
    local copy = {}
    for i, m in ipairs(party) do
      local slot = { species = m.species, level = m.level }
      if type(m.moves) == "table" then
        slot.moves = {}
        for k, mv in ipairs(m.moves) do slot.moves[k] = mv end
      end
      copy[i] = slot
    end
    if type(tr.parties) ~= "table" then tr.parties = {} end
    tr.parties[1] = copy
    return true
  end

  -- The outcome a script's start_battle left in its context, "win" or
  -- "lose" (Commands.start_battle sets ctx.lastBattleResult when the battle
  -- resumes, src/script/Commands.lua:339-393; the runner keeps the context
  -- as runner.ctx, src/script/ScriptRunner.lua:120-123); nil before that.
  function engine.scriptBattleResult()
    local ow = engine.overworld()
    local runner = ow and ow.runner
    local ctx = runner and type(runner.ctx) == "table" and runner.ctx or nil
    local r = ctx and ctx.lastBattleResult
    if r == "win" or r == "lose" then return r end
    return nil
  end

  -- The engine's own blackout for a wild battle lost in Cobblemon:
  -- OverworldState:afterBattle("lose") heals the party, takes half the money,
  -- emits world.blacked_out and warps to the last heal point
  -- (src/world/OverworldController.lua:5334-5375). Returns true, or false
  -- plus a reason.
  function engine.blackout()
    local ow = engine.overworld()
    if not ow then return false, "not_ingame" end
    if ow.transitioning then return false, "busy" end
    if type(ow.afterBattle) ~= "function" then return false, "failed" end
    local ok = pcall(ow.afterBattle, ow, "lose", nil)
    if not ok then return false, "failed" end
    return true
  end

  return engine
end

return Gen1
