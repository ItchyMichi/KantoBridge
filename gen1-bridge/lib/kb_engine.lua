-- kb_engine: the only gen1-bridge file that touches Gen1Recomp engine
-- internals (the manifest's `engine_internals` permission).
--
-- Every function names the engine source it relies on, as `file:line` in the
-- pinned v0.3.49 (identical to refs/gen1recomp at cb02aeb). When the pin
-- changes, re-check each reference below before anything else (CLAUDE.md).
-- Elsewhere, gen1-bridge reaches the engine only through this table and the
-- supported mod API (mod.events, mod.hooks, mod.input, mod.world; the
-- export's dataset views come through here too).
--
-- Readers hand back plain values (strings, numbers, booleans, fresh tables),
-- never live engine tables, so nothing outside this file can mutate the game
-- by accident. Shorthand: OC = src/world/OverworldController.lua.

local Engine = {}

-- The engine release whose internals this file was checked against.
Engine.CHECKED = "0.3.49"

-- PLAN P21: the hidden game's display options pinned to classic, so mirror
-- frames have the fixed 160x144 geometry, and text at FAST. Keys and
-- defaults: SaveData.defaultOptions (src/core/SaveData.lua:569-654).
Engine.CLASSIC = {
  tilt = 0,                -- src/render/Tilt.lua:80-91
  zoom = 0,                -- src/render/Zoom.lua:113-115 (0 = FIT)
  battleLayout = "og",     -- src/battle/BattleState.lua:79-81 (not "wide")
  battleFit = "fixed",     -- src/battle/BattleState.lua:94-96 (not "fill")
  uiLayout = "centered",   -- src/core/Game.lua:573-580 (not "dynamic")
  textSpeed = 1,           -- src/render/TextBox.lua:652-654: 1 frame per glyph = FAST
}

local SYMBOL = "^[A-Za-z0-9_]+$"

local function symbol(v)
  return type(v) == "string" and #v > 0 and #v <= 64 and v:find(SYMBOL) ~= nil
end

-- ------------------------------------------------------------ export constants
--
-- Engine facts the world export needs that the engine keeps as inline
-- literals or locals, so there is nothing to call. Copied here, cited, and
-- re-checked with everything else on a pin change.

-- Cuttable tree tiles per tileset: OVERWORLD $3d and GYM $50
-- (OverworldState:tryCut, src/world/OverworldController.lua:3304-3307, and
-- the same gate in useCutFieldMove :3424-3427). OVERWORLD $52 is cuttable
-- tall grass, not a tree, so it is left out.
Engine.CUT_TREE_TILES = { OVERWORLD = { 0x3d }, GYM = { 0x50 } }

-- Shore tiles when a tileset record carries no shoreTiles: $32 and $48,
-- except on SHIP_PORT (src/world/Map.lua:20-22, :128-131). Map.new folds
-- water and shore into one set, so the shore half is the one thing that
-- can't be asked of the engine.
Engine.SHORE_FALLBACK = { 0x32, 0x48 }
Engine.NO_SHORE_TILESETS = { SHIP_PORT = true }

-- Where each registry the export reads lives in the live Data table: the
-- registry targets (src/mods/Schemas.lua: pokemon :1726, moves :1856,
-- items :1893, maps :1922, tilesets :1980, encounters :2046, sprites :2253,
-- palettes :2825, constants :3101, field :3130).
Engine.EXPORT_TARGETS = {
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
-- opts.datasets: mod.datasets, the dataset views (src/mods/Loader.lua:1361-1369)
function Engine.new(opts)
  opts = opts or {}
  local requireFn = opts.require or function() return nil end
  local log = opts.log
  local ui = opts.ui
  local datasets = opts.datasets
  local engine = {}
  local game

  local function try(name)
    local ok, result = pcall(requireFn, name)
    if ok and type(result) == "table" then return result end
    return nil
  end

  -- The Game object from the game.ready payload { game = self }
  -- (src/core/Game.lua:119, emitted once services are up, before the title).
  -- input.step also passes it (src/core/Game.lua:320).
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
  -- "0.3.49" in the release build; "0.0.0-dev[+commit]" in a source checkout,
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
    local ok = v == Engine.CHECKED or v:find("^0%.0%.0%-dev") ~= nil
    if not ok and log then
      log.once("engine.pin", "warn", "engine.unchecked_version",
        { engine = v, checked = Engine.CHECKED })
    end
    return ok
  end

  -- "red" or "blue": GameVersion.get (src/core/GameVersion.lua:178-180).
  function engine.gameVersion()
    local GameVersion = try("src.core.GameVersion")
    local v = GameVersion and GameVersion.get and GameVersion.get()
    return type(v) == "string" and v or nil
  end

  -- Game.logicStep counts fixed logic steps (src/core/Game.lua:311-315).
  function engine.logicStep()
    local Game = try("src.core.Game")
    return Game and tonumber(Game.logicStep) or 0
  end

  -- The live save table (src/core/Game.lua:95, :1496 on load). Callers read
  -- it; only pinDisplay writes to it.
  function engine.save()
    return game and type(game.save) == "table" and game.save or nil
  end

  -- The top state of the screen stack: src/core/StateStack.lua:47
  -- (game.stack is StateStack, src/core/Game.lua:93).
  function engine.top()
    local stack = game and game.stack
    return stack and stack.top and stack:top() or nil
  end

  -- The overworld when it is on the stack (it stays under menus and
  -- battles). StateStack.states is the stack, bottom to top
  -- (src/core/StateStack.lua:9-11); the overworld module marks itself with
  -- isOverworld (OC:35). Same authority WorldAPI:overworld uses
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
  --   isOverworld OC:35; screenId stamped by Screens.build
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

  -- Rock Tunnel and friends: field.darkMaps (OC:216-222). Built once per data
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
    -- OC:5655-5660: { entity, dir, remaining, onDone, collide }
    for _, mv in ipairs(type(ow.scriptMoves) == "table" and ow.scriptMoves or {}) do
      if mv.entity == p then return true end
    end
    return false
  end

  -- A Strength boulder on the move: a scriptMoves entry (OC:5655-5660) whose
  -- entity is a pushable object, as checkBoulderPush queues it (OC:1864).
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

  -- Everything the player observer needs, as plain values. The input.step
  -- hook runs before the stack updates (src/core/Game.lua:320 vs :356), so
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
    facts.facing = p.facing                     -- "up"/"down"/"left"/"right" (OC:67)
    facts.moving = p.moving == true             -- Player.lua:150, :178-184
    if facts.moving then
      facts.tx, facts.ty = p.targetX, p.targetY -- set at step start (Player.lua:179, OC:5711)
      facts.frames = p.stepFramesCur            -- Player.lua:183, OC:5714
      facts.progress = tonumber(p.progress)     -- frames done, Player.lua:182, :237
    end
    facts.playerScripted = playerScripted(ow, p)
    facts.ledge = p.ledgeHop and true or false  -- OC:1985-2037, Player.lua:124
    -- a hop's whole length, both cells: stepLength() * 2 (OC:2017-2018, :2029-2030)
    facts.hopFrames = facts.ledge and tonumber(p.hopTotal) or nil
    facts.boulderPush = boulderMoving(ow, p)
    facts.spinning = (p.spinning or ow.spinnerSliding) and true or false -- OC:4825-4871
    -- departures and arrivals that move or hide the player (OC:1366-1498,
    -- :1546-1552) and the locks set with them (Player.lua:97, :150; OC:2456,
    -- :2486, :5329, :5354, :5522, :5528)
    facts.forcedPhase = (ow.teleportOut or ow.flyFade or ow.flyAnim or ow.flyArrive
      or ow.holeFall or ow.holeArrive or ow.spinArrive or p.inputLocked) and true or false
    -- which departure is under way before a warp's fade starts (P1 6.5):
    -- Fly's fade and bird (flyTo, OC:2441-2458; OC:1366-1408), the spin of
    -- Dig/Teleport/Escape Rope and of a warp pad (teleportOut, OC:2470-2499,
    -- :5339-5342, :1436-1463), a hole's sink (fallThroughHole, OC:5353-5359,
    -- :1466-1477)
    if ow.flyFade or ow.flyAnim then
      facts.departure = "fly"
    elseif ow.teleportOut then
      facts.departure = "teleport"
    elseif ow.holeFall then
      facts.departure = "hole"
    end
    facts.hopLand = tonumber(ow.hopLand) or 0     -- OC:1548, :1572
    facts.transitioning = ow.transitioning and true or false -- OC:5423, :5550-5552
    facts.emote = ow.emote ~= nil and ow.emote ~= false      -- OC:1510-1527
    facts.anim = (ow.cutAnim or (ow.dustAnim and ow.dustAnim.boulder)) and true or false
    local runner = ow.runner
    local running = runner and runner.isRunning and runner:isRunning() or false
    -- the rest of OC:1546-1552's `scripted`, plus the early-return holds
    -- healAnim and pikaHop (OC:1344-1363)
    facts.scripted = (running or #(ow.scriptMoves or {}) > 0 or ow.engaging
      or ow.healAnim or ow.pikaHop) and true or false
    facts.surf = p.surfing and true or false                         -- Player.lua:139, :331
    facts.bike = (save and save.onBike) and true or false            -- OC:1301-1302
    facts.slope = (facts.bike and p.slopeMap) and true or false      -- Player.lua:128
    facts.hidden = ow.playerHidden and true or false                 -- OC:6491-6494
    facts.darkMap = darkMapSet()[facts.map] == true                  -- OC:510-515
    facts.flashLit = (save and save.flashLit) and true or false      -- OC:510-513, :1063
    facts.npcs = type(ow.npcs) == "table" and #ow.npcs or 0
    return facts
  end

  -- ow.npcs is rebuilt as a new table by every setMap (OC:551-558: warps,
  -- seam crossings, same-map warps), so its identity tells a map (re)load
  -- from NPCs coming and going on the same map. The generation counts the
  -- tables seen.
  local npcTable, npcGeneration = nil, 0

  -- The live NPCs of the current map, as PLAN section 4.1 "NPCs" describes
  -- them: ow.npcs holds the visible ones (NPC.lua:32-64, OC:551-558); sprite
  -- is appearance or def.sprite (NPC.lua:68-79); item balls have def.item
  -- (except the ITEM_NONE sentinels "0" and 0, OC:3451-3456); boulders are
  -- SPRITE_BOULDER or pushable (Map.lua:188-191); statics have def.pokemon;
  -- trainers trainerClass. Per NPC also its step: moving, targetX/Y, the
  -- step length (stepFrames or 32, NPC.lua:9, :109), marching (the
  -- walk-in-place cycle, NPC.lua:116-124), progress, and `via` when a
  -- scripted move drives it: scriptMoves entries (OC:5655-5671) are "march"
  -- for an inPlace beat, "push" for a pushable object (a Strength boulder,
  -- OC:1864), else "script".
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
  -- onDone } (OC:1510-1527; Commands.emote, src/script/Commands.lua:1184-1200):
  -- bubble nil or 1 is "!", 2 "?", 3 happy, false a silent hold (OC:6055-6075).
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
  -- was handled, in the same logic step (OC:2532-2535, :2537-2614). kind is
  -- npc, sign, door, hidden, script, bookshelf or none; for npc the target
  -- is the NPC, which was talked to only if it stood still (a moving NPC
  -- gets no world.talk, OC:2550-2567). Plain values only.
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
  -- looks only at the current map (OC:2537-2614): a cell across a seam holds
  -- nothing it can reach, the neighbour map's NPCs being drawn ghosts that
  -- never talk (OC:785-803).
  function engine.cellInMap(x, y)
    local ow = engine.overworld()
    local m = ow and ow.map
    if not (m and type(m.inBounds) == "function") then return nil end
    local ok, inside = pcall(m.inBounds, m, x, y)
    if not ok then return nil end
    return inside and true or false
  end

  -- True when (x, y) of the current map is a counter tile: the A press
  -- then reaches one cell further (OC:2542-2547; Map:isCounterCell,
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
  -- when drawn (e.g. ChoiceBox.lua:90-91, ListMenu.lua:370).
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

  -- TextBox (src/render/TextBox.lua): the lines currently in the box,
  -- TextBox:visibleText's rule (:391-399): the page's lines up to lineIndex,
  -- as many as `shown` holds (at most two).
  local function visibleLines(s)
    local pages = type(s.pages) == "table" and s.pages or {}
    local page = pages[int(s.pageIndex, 1)]
    if type(page) ~= "table" then return {} end
    local shown = count(s.shown)
    local line = int(s.lineIndex, 1)
    local out = {}
    for i = math.max(1, line - shown + 1), line do
      if type(page[i]) == "string" then out[#out + 1] = page[i] end
    end
    return out
  end

  -- The box takes an A/B tap this logic step (P2 3.3; TextBox.lua:457-698,
  -- in update order): nothing while a scroll or page clear holds
  -- (holdFrames, :476-479), a PAUSE or sound waits (preSound :461-472,
  -- pauseFrames :481-499, pauseSrc :500-506) or a sound effect holds
  -- (sfxWait, :402-407). Finished: A/B closes a plain box (:607-614); a
  -- stay.prompt box takes one press (:511-526) and an auto.promptFirst box
  -- one press (:529-534); other stay, auto and choice boxes take none
  -- (:527-605). Mid-text: waiting at a page break or scroll once the
  -- 3-frame preWait has run out (:617-627).
  local function textReady(s)
    if int(s.holdFrames, 0) > 0 then return false end
    if s.preSound or s.pauseFrames or s.pauseSrc or s.sfxWait then return false end
    if s.done then
      if s.stay then
        return type(s.stay) == "table" and s.stay.prompt and not s.stayShown and true or false
      end
      if s.auto then
        return type(s.auto) == "table" and s.auto.promptFirst and not s.autoPrompted and true or false
      end
      return not s.choice
    end
    return s.waiting == true and int(s.preWait, 0) == 0
  end

  -- The blinking arrow is up: TextBox:arrowVisible's rule (:415-426) for a
  -- Gen 1 save.
  local function textArrow(s)
    if s.sfxWait then return false end
    if s.waiting then return true end
    if not s.done or s.choice then return false end
    if s.auto and not (type(s.auto) == "table" and s.auto.promptFirst and not s.autoPrompted) then
      return false
    end
    if s.stay and not (type(s.stay) == "table" and s.stay.prompt and not s.stayShown) then
      return false
    end
    return true
  end

  -- Menu rows are drawn as their labels (Menu.lua:176-180); callers built
  -- them through Strings already (e.g. StartMenu.lua:32-165).
  local function menuItems(items)
    local out = {}
    for i = 1, count(items) do
      local it = items[i]
      out[i] = { label = type(it) == "table" and type(it.label) == "string" and it.label or "" }
    end
    return out
  end

  -- ListMenu rows (src/ui/ListMenu.lua:1-3, drawn :322-344, :374-398): the
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
  -- (src/ui/NamingScreen.lua:176-187, the ui.naming.grid hook included),
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
      f.stay = stay ~= nil and stay ~= false and not (type(stay) == "table" and stay.prompt)
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
    elseif cls == "ListMenu" then
      -- title (:110-111, drawn through Strings :370), items (:113), rows
      -- (:158), footer (:120), the Mart's money box (dialogue + money(),
      -- :135, :140, :414-421), script mode (the old man's demo, :131)
      f.items = listItems(s.items)
      f.title = type(s.title) == "string" and S(s.title) or nil
      f.rows = int(s.rows, nil)
      f.footer = type(s.footer) == "string" and s.footer or nil
      f.wrap = s.wrap == true -- clamps unless opts.wrap (ListMenu.lua:122, :163-173)
      if s.dialogue and type(s.money) == "function" then
        local ok, money = pcall(s.money)
        if ok then f.money = int(money, nil) end
      end
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
      -- script: the old man's demo list reads no input (ListMenu.lua:128-131, :219-222)
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

  -- Moves a cursor to `index` (1-based), keeping the scroll window around
  -- it as the widget itself would on its next update: Menu:clampScroll
  -- (Menu.lua:74-84), ListMenu's syncScroll (ListMenu.lua:175-181),
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

  -- NamingScreen: the typed glyphs, one grid cell per entry, as A would
  -- have inserted them (NamingScreen.lua:98, :242-245); confirm() joins them
  -- (:153-174) when START is pressed on the next update (:201-203).
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
  -- src/script/Commands.lua:546-548) and as handleInput does before an edge,
  -- ledge or boulder check (OC:1729-1732, which also ends a wall-bonk cycle
  -- by clearing bumpFrames). turnArmed and turnTimer are left alone, so a
  -- held direction then steps at once with no 4-frame turn
  -- (Player:tryMove, src/world/Player.lua:151-165), and nothing fires: map
  -- onStep scripts run only from onStepComplete (OC:4691-4697). Refused
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
  -- (src/core/Input.lua:705-709) on game.input, the one mod.input presses
  -- into (src/mods/Loader.lua:1393-1431). Input:reset on a focus or
  -- visibility change wipes every source, mods' holds included
  -- (Input.lua:182-203; src/core/Game.lua:1120-1139).
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
  -- the door walk-out probe (OC:5542) and collide-moves (OC:5699-5703) do
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
  -- anything moves (OC:5306-5309), so the player still stands on the
  -- departure cell; Warp.lua's three triggers (src/world/Warp.lua:1-10)
  -- then read:
  --   pad, hole  Map:warpPadOrHoleAt (src/world/Map.lua:265-269), the test
  --              takeWarp branches on (OC:5320-5347)
  --   door       a door tile underfoot (Map:isDoorTileCell, Map.lua:253-255)
  --   stairs     another warp-activating tile (Map:isWarpTileCell,
  --              Map.lua:258-261): stairs, ladders, cave mouths
  --   edge       neither, with the facing pointing off the map: an exit mat,
  --              taken by walking off the edge (checkEdgeExit, OC:2047-2061;
  --              Warp.onEdge, Warp.lua:77-86) or by arriving on it facing the
  --              edge with the d-pad held (Warp.extraCheck's facing-edge
  --              test, Warp.lua:38-45; onStepComplete, OC:4750-4757)
  --   door       neither, facing into the map: the carpet maps' collision
  --              warps (route-gate doorways, the dock: Warp.extraCheck's
  --              warp-tile-in-front test, Warp.lua:46-61; OC:1743-1749)
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
    for key, value in pairs(Engine.CLASSIC) do
      if options[key] ~= value then return false end
    end
    for _, level in pairs(type(options.pipelines) == "table" and options.pipelines or {}) do
      if level ~= 0 then return false end
    end
    return true
  end

  -- Pins options (a save.options table) to Engine.CLASSIC and turns every
  -- render pipeline (Dramaless voxel and friends) to level 0, keeping the
  -- keys. Returns how many values changed. With reapply, the modules that
  -- cache these values are told at once: Tilt.applyOptions
  -- (src/render/Tilt.lua:80-91), Pipelines.applyOptions
  -- (src/render/Pipelines.lua:230-252) and Zoom.applyOptions
  -- (src/render/Zoom.lua:113-115), in Game:applyOptions' order
  -- (src/core/Game.lua:1431-1436). battleLayout, battleFit, uiLayout and
  -- textSpeed are read live. Nothing is written to disk here; the engine
  -- persists options only when its own OPTIONS screen saves them
  -- (Game:writeOptions, src/core/Game.lua:1418-1421).
  function engine.pinDisplay(options, reapply)
    if type(options) ~= "table" then return 0 end
    local changed = 0
    for key, value in pairs(Engine.CLASSIC) do
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

  -- ------------------------------------------------------------ export (M2)
  --
  -- The world export reads the active game's PRISTINE data. The live Data
  -- tables change during play: Map:setBlock writes into the map record's
  -- blocks (src/world/Map.lua:244-249) for Cut, card-key doors and script
  -- swaps, and elevator panels rewrite warps. So the export reads one of two
  -- sources, both of which hand out detached plain tables:
  --   * "datasets": mod.datasets:open(version) (src/mods/Loader.lua:1361-1369,
  --     src/mods/DatasetViews.lua:427-479) decodes the version's cache again
  --     and copies every record it hands out (get/each, :368-405);
  --   * "live": PLAN section 8's fallback, a deep copy of the live Data taken
  --     before any map has loaded (the mods.loaded payload's `data`,
  --     src/mods/Loader.lua:2163; Game:load runs the loader before anything
  --     is on the stack, src/core/Game.lua:47-119).
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
    for name, path in pairs(Engine.EXPORT_TARGETS) do
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
  -- Data, src/core/Game.lua:44), so the live table never leaves this file.
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
  -- src/world/OverworldController.lua:5419). This is what sets the LAST_MAP
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

  -- Shore tiles of a tileset (Engine.SHORE_FALLBACK above).
  function engine.shoreTiles(tilesetId, tileset)
    local shore = type(tileset) == "table" and tileset.shoreTiles or nil
    if shore == nil and not Engine.NO_SHORE_TILESETS[tilesetId] then shore = Engine.SHORE_FALLBACK end
    local out = {}
    for _, t in ipairs(type(shore) == "table" and shore or {}) do out[#out + 1] = t end
    return out
  end

  function engine.cutTreeTiles(tilesetId)
    local out = {}
    for _, t in ipairs(Engine.CUT_TREE_TILES[tilesetId] or {}) do out[#out + 1] = t end
    return out
  end

  -- Spinner-arrow tiles of a tileset, the tiles the spinner toggle patches:
  -- TileRenderer.SPINNER_ARROW_TILES (src/render/TileRenderer.lua:134-137).
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

  -- The asset paths a tileset's tile animations draw from: the record's own
  -- animatedTiles, else TileRenderer.defaultAnimatedTiles (the flower frames
  -- and the spinner strip; src/render/TileRenderer.lua:313-334, precedence
  -- :538-539). Each entry carries `images` (frames) or `image` (a strip).
  function engine.animationImages(tileset)
    local TileRenderer = try("src.render.TileRenderer")
    local specs = type(tileset) == "table" and tileset.animatedTiles or nil
    if specs == nil and TileRenderer and TileRenderer.defaultAnimatedTiles then
      local ok, list = pcall(TileRenderer.defaultAnimatedTiles, tileset)
      specs = ok and list or nil
    end
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
  -- (src/world/OverworldController.lua:296-345; the module returns
  -- OverworldState, :6683), a BFS over the connections that composes the
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
  -- (src/world/OverworldController.lua:897-919): a map's own `palette`, then
  -- field.palettes byMap, byTileset and the byPrefix rows (its local
  -- paletteLookup, :854-866, restated here because it is a local); nil when
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
  -- Assets.imageData (src/render/Assets.lua:67-71; mod overrides first, then
  -- the active version's mounted cache), encoded like the importer writes
  -- PNGs, ImageData:encode("png") with no filename
  -- (src/import/ImageWriter.lua:170-182). Returns png, width, height, or nil
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

  return engine
end

return Engine
