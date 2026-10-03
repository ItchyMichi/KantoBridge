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

-- ------------------------------------------------------------ battle constants (M5)
--
-- Engine facts a trainer team needs that BattleState keeps as file locals,
-- so there is nothing to call without building a battle (newTrainer marks
-- the Pokedex and loads pics, so it is never called just to read a team;
-- refs/_research/P3_gen1_data_export.md 7.5). Copied, cited and re-checked
-- with everything else on a pin change.

-- Fixed trainer DVs, unless constants.trainerDvs overrides them
-- (src/battle/BattleState.lua:543-545, :925-934).
Engine.TRAINER_DVS = { attack = 9, defense = 8, speed = 8, special = 8 }

-- The boss moves written into a team's third move slot
-- (src/battle/BattleState.lua:837-889, from pokered's
-- data/trainers/special_moves.asm): a gym leader's n-th Pokemon (Giovanni
-- only in party 3), the fifth Pokemon of each Elite Four member, and the
-- Champion's first Pokemon plus his starter's final form.
Engine.LONE_MOVES = {
  OPP_BROCK = { 2, "BIDE" }, OPP_MISTY = { 2, "BUBBLEBEAM" }, OPP_LT_SURGE = { 3, "THUNDERBOLT" },
  OPP_ERIKA = { 3, "MEGA_DRAIN" }, OPP_KOGA = { 4, "TOXIC" }, OPP_SABRINA = { 4, "PSYWAVE" },
  OPP_BLAINE = { 4, "FIRE_BLAST" }, OPP_GIOVANNI = { 5, "FISSURE", onlyParty = 3 },
}
Engine.TEAM_MOVES = { OPP_LORELEI = "BLIZZARD", OPP_BRUNO = "FISSURE", OPP_AGATHA = "TOXIC", OPP_LANCE = "BARRIER" }
Engine.RIVAL_STARTER_MOVES = { VENUSAUR = "MEGA_DRAIN", CHARIZARD = "FIRE_BLAST", BLASTOISE = "BLIZZARD" }
-- GetTrainerName_: the rival classes are named after the rival
-- (src/battle/BattleState.lua:902-909)
Engine.RIVAL_CLASSES = { OPP_RIVAL1 = true, OPP_RIVAL2 = true, OPP_RIVAL3 = true }

-- One move list (ids) after setThirdMove (src/battle/BattleState.lua:864-869):
-- the move lands in slot min(3, #moves + 1).
local function setThirdMove(moves, moveId)
  if type(moves) ~= "table" then return end
  moves[math.min(3, #moves + 1)] = moveId
end

-- applySpecialMoves (src/battle/BattleState.lua:871-889) over plain
-- { species, moves = {ids} } entries.
function Engine.applySpecialMoves(oppClass, partyIndex, party)
  local lone = Engine.LONE_MOVES[oppClass]
  if lone and (not lone.onlyParty or lone.onlyParty == partyIndex) then
    local mon = party[lone[1]]
    if mon then setThirdMove(mon.moves, lone[2]) end
    return
  end
  local team = Engine.TEAM_MOVES[oppClass]
  if team then
    if party[5] then setThirdMove(party[5].moves, team) end
    return
  end
  if oppClass == "OPP_RIVAL3" then
    if party[1] then setThirdMove(party[1].moves, "SKY_ATTACK") end
    local starter = party[6]
    if starter and Engine.RIVAL_STARTER_MOVES[starter.species] then
      setThirdMove(starter.moves, Engine.RIVAL_STARTER_MOVES[starter.species])
    end
  end
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
    -- a battle Cobblemon is fighting holds this world (M5, engine.battle*)
    facts.battle = engine.battleHolds(ow)
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

  -- ------------------------------------------------------------ battles, gifts, the shadow party (M5)
  --
  -- Battles the hidden game would fight are fought in Cobblemon instead
  -- (PLAN section 4.1 "Battles"), through two seams:
  --   * script.command (src/script/ScriptRunner.lua:168-172, inside the
  --     script's coroutine): the rows start_battle, rival_battle and
  --     static_battle (src/script/Commands.lua:339-393, :1047-1076,
  --     :991-1000) wait on runner.waitingCheck, exactly as push_screen
  --     waits for its screen (Commands.lua:1344-1364; ScriptRunner:update,
  --     ScriptRunner.lua:290-308). The result then goes through
  --     start_battle's own resume path (ctx.resumeBattle, Commands.lua:
  --     342-358): lastBattleResult, lastCheck and afterBattle (OC:5184-5225),
  --     whose lose branch keeps the Oak's-lab rival loss in the lab
  --     (OC:5195-5201). static_battle's beat flag and object toggle follow
  --     on their own (Commands.lua:993-999).
  --   * trainer.before_battle (OC:4038-4059, :4147-4167; docs/modding.md
  --     "trainer.before_battle"): map trainers are deferred; a result applies
  --     engageTrainer's own win side effects (OC:4127-4144) and ends the
  --     encounter with continue({ cancel = true }) (OC:4048-4049), which runs
  --     the encounter's completion callback and suppresses the sight at the
  --     player's cell (OC:4157-4166).
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
  -- RomExtractor.lua:1214; moves[id].index = the Gen 1 move number, the
  -- moveOrder position, RomExtractor.lua:607-623)

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
    if Engine.RIVAL_CLASSES[cls] then
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
    Engine.applySpecialMoves(cls, partyIndex, mons)
    for i, slot in ipairs(partyDef) do
      if mons[i] and type(slot.moves) == "table" then
        local own = {}
        for k = 1, #slot.moves do own[k] = slot.moves[k] end
        mons[i].moves = own
      end
    end
    local dvs = (type(data.constants) == "table" and type(data.constants.trainerDvs) == "table")
      and data.constants.trainerDvs or Engine.TRAINER_DVS
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

  -- Commands.rival_battle's party offset for Red/Blue (Commands.lua:1063-1075).
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
      -- Yellow keys its rival parties off save.rivalStarter (Commands.lua:1035-1062)
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
    if result == nil or result == "abort" then return "end" end
    ctx.resumeBattle = { result = result, battle = record.fake }
    return nextFn(ctx, name, args)
  end

  -- trainer.before_battle's wrapper body: defers a map trainer's battle.
  -- context = { trainerClass, partyIndex, mapId, npcId } (OC:4152-4157).
  -- While it waits, the world is held as a trainer sight holds it
  -- (ow.engaging, part of OC:1546-1552's `scripted`), also for the talk path.
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
    record.continueBattle = continueBattle
    -- the trainer header's event flag and loss line (Data:trainerHeader,
    -- src/core/Data.lua:425-428; OC:4073-4087, :4130-4132)
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

  -- The text boxes a result shows (raw engine text, substituted by
  -- TextBox.new itself, TextBox.lua:170), and the prize.
  local function resultTexts(rec, result)
    local save = engine.save()
    local player = save and type(save.player) == "table" and save.player.name or nil
    player = type(player) == "string" and player or "RED"
    local texts, prize = {}, 0
    if result == "win" and rec.cls then
      if rec.endText then
        local tag = trainerName(rec.cls, rec.party)
        texts[#texts + 1] = (tag and (tag .. ": ") or "") .. rec.endText
      end
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
  -- A/B pops each and calls its onDone), then runs onDone.
  local function pushTexts(list, onDone)
    local TextBox = try("src.render.TextBox")
    local i = 0
    local function nextBox()
      i = i + 1
      local text = list[i]
      if text == nil or not (TextBox and type(TextBox.new) == "function" and game and game.stack) then
        return onDone()
      end
      local ok, box = pcall(TextBox.new, game, text, nextBox)
      if not ok or type(box) ~= "table" then return nextBox() end
      game.stack:push(box)
    end
    nextBox()
  end

  local function finishTrainer(rec, result)
    local ow, save = rec.ow, engine.save()
    if type(ow) == "table" and ow == engine.overworld() and type(save) == "table" then
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
  -- runs as the native battle's onFinish would (OC:4820, :2429):
  -- OverworldState:afterBattle (OC:5184-5225) gives a wild battle its
  -- encounter grace steps (:5188-5190), and "lose" blacks out (:5196-5214).
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

  -- Applies a Cobblemon result to the pending battle. result is the
  -- engine's word: "win", "lose", "run", "caught", or "abort" (nothing
  -- happened: the script stops / the encounter is cancelled, so it can be
  -- fought again). Returns true, or false when id isn't the battle waiting.
  function engine.battleFinish(id, result)
    local rec, st = liveBattle()
    if not rec or rec.id ~= id or rec.state ~= "waiting" then return false end
    rec.state, rec.result = "texts", result
    local texts, prize = resultTexts(rec, result)
    local save = engine.save()
    if prize > 0 and type(save) == "table" then save.money = (tonumber(save.money) or 0) + prize end
    pushTexts(texts, function()
      if rec.path == "script" then
        rec.state = "done" -- the script's waitingCheck resumes it
      else
        rec.state = "consumed"
        if st.battle == rec then st.battle = nil end
        if rec.path == "wild" then finishWild(rec, result) else finishTrainer(rec, result) end
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

  -- give_pokemon (Commands.lua:807-869) runs as usual -- Party.add or the
  -- box, the Pokedex, the native nickname prompt (askNickname, :761-794) --
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
          give = ("g-%s-%d"):format(st.token or "x", st.serial), state = "new", syncs = 0,
          box = box ~= nil, source = source, save = save, facts = giftFacts(mon),
          mon = (box == nil) and mon or nil,
        }
      end
    end
    return jump
  end

  -- The script.command wrapper's body (ScriptRunner.lua:168-172: next is
  -- the command itself; it may yield the script's coroutine).
  function engine.scriptCommand(nextFn, ctx, name, args, bridge)
    if type(ctx) ~= "table" then return nextFn(ctx, name, args) end
    if SCRIPT_BATTLES[name] then return scriptBattle(nextFn, ctx, name, args, bridge) end
    if name == "give_pokemon" then return scriptGive(nextFn, ctx, name, args) end
    if name == "heal_party" then
      -- HealParty (Commands.lua:750-755): Cobblemon's party heals too
      local jump = nextFn(ctx, name, args)
      local st = bridgeState()
      if st then st.heals = st.heals + 1 end
      return jump
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
        give = g.give, state = g.state, sentGen = g.sentGen, syncs = g.syncs, box = g.box, source = g.source,
        dex = f.dex, level = f.level, moves = f.moves, dvs = f.dvs, nick = f.nick, ot = f.ot,
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

  function engine.queueHeal()
    local st = bridgeState()
    if st then st.heals = st.heals + 1 end
  end
  function engine.takeHeals()
    local st = type(game) == "table" and rawget(game, STATE_SLOT) or nil
    if type(st) ~= "table" then return 0 end
    local n = st.heals
    st.heals = 0
    return n
  end

  -- ---- the shadow party (party.sync, Cobblemon authoritative)

  -- Cobblemon uids of party members, by member (weak keys); rebuilt from
  -- mod.save after a reload (engine.adoptUids).
  local monUid = setmetatable({}, { __mode = "k" })

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
    }
  end

  -- ------------------------------------------------------------ wild encounters, Pokedex, bag, items (M6)
  --
  -- Wild encounters (PLAN section 4.1 "Battles", P8, P9). Every random
  -- encounter the overworld rolls goes through OverworldState:rollEncounter
  -- (OC:4635-4646), whose encounter.roll chain may return nil to suppress it
  -- (OC:4641-4645; docs/modding.md "Effective wild-encounter distribution").
  -- ctx.terrain is "grass" (a grass cell), "water" (surfing on water: the
  -- map's water table) or "indoor" (every step of an indoor map), in
  -- onStepComplete's order (OC:4781-4790). While a client or test session is
  -- connected:
  --   * a roll the engine would turn into a Safari Zone battle (a Safari game
  --     on a SAFARI-region map, OC:4812-4814) or an unidentifiable ghost
  --     battle (Map.ghostBattles without its item, OC:4805-4810) runs
  --     natively, shown on the mirror (P9);
  --   * grass and indoor rolls return nil without rolling: Cobblemon's
  --     habitat pools spawn those Pokemon in Minecraft (P8);
  --   * a water roll is the engine's own (the vanilla link, then
  --     encounter.species as OC:4642-4644 runs it), filtered by REPEL as
  --     OC:4793-4799 does, and then held for Cobblemon instead of the native
  --     battle (OC:4800-4822): the world waits (ow.engaging, as a trainer's
  --     encounter holds it) until battle.result.
  -- Fishing: OverworldState:goFishing (OC:2375-2407) rolls through
  -- encounter.fishing (OC:2382-2388). No bite ("Not even a nibble!", "nothing
  -- here") stays native, and so does a Safari Zone bite (OC:2426-2428). A
  -- bite plays its cast animation natively in the TextBox goFishing pushes
  -- right after the roll (OC:2394-2406); that one box's continuation
  -- (self.onDone, called when it pops: src/render/TextBox.lua:586-588,
  -- fishVerdict OC:2397-2399) is replaced with fishVerdict's bite text and
  -- its field resets (OC:2421-2423) and then the hold, so the native battle
  -- (:2424-2430) never starts.
  -- Without a client the hidden game fights everything itself (M5's rule).

  local DIR_DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
  -- BattleState:battleKind (src/battle/BattleState.lua:1857-1862): the
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
  -- src/world/Map.lua:173-176; tested so at OC:4812 and :2426).
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
  -- unlessItem in the bag (OC:4807-4810).
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
    return record
  end

  local function planFor(bridge, facts)
    if type(bridge) ~= "table" or type(bridge.battle) ~= "function" then return nil end
    local ok, plan = pcall(bridge.battle, facts)
    if ok and type(plan) == "table" and type(plan.id) == "string" then return plan end
    return nil
  end

  -- encounter.roll's wrapper body (OC:4641; next is the vanilla roll,
  -- OC:4628).
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
    -- REPEL blocks wild Pokemon weaker than the lead (OC:4793-4799)
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

  -- encounter.fishing's wrapper body (OC:2384-2387: next rolls the
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
  -- src/core/SaveData.lua:3061); the engine marks both on a gift, a trade or
  -- an evolution (src/pokemon/Evolution.lua:105-108,
  -- src/link/Protocol.lua:1199-1200) and counts entries with pairs (Oak's
  -- rating, OC:3740-3743; the start menu, src/ui/StartMenu.lua:68).

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
  -- (RomExtractor.lua:883-886). The engine changes it through Bag.add /
  -- Bag.remove (Bag.lua:139-181).

  -- The quantities of the listed items the bag holds (1 or more), never a
  -- key item.
  function engine.bagCounts(items)
    local save, data = engine.save(), gameData()
    local inv = type(save) == "table" and type(save.inventory) == "table" and save.inventory or nil
    local out = {}
    if not inv then return out end
    for _, id in ipairs(type(items) == "table" and items or {}) do
      local qty = inv[id]
      local def = data and type(data.items) == "table" and data.items[id] or nil
      if type(qty) == "number" and qty >= 1 and not (type(def) == "table" and def.keyItem) then
        out[id] = math.floor(qty)
      end
    end
    return out
  end

  -- Takes up to qty of an item out of the bag with Bag.remove (the slot and
  -- its bagOrder entry go at zero, Bag.lua:166-181); never below zero.
  -- Returns how many were taken and how many there were.
  function engine.bagRemove(id, qty)
    local save = engine.save()
    local inv = type(save) == "table" and type(save.inventory) == "table" and save.inventory or nil
    if not inv then return 0, 0 end
    local held = type(inv[id]) == "number" and math.max(0, math.floor(inv[id])) or 0
    local n = math.min(qty, held)
    if n <= 0 then return 0, held end
    local Bag = try("src.inventory.Bag")
    if not (Bag and type(Bag.remove) == "function") then return 0, held end
    local ok = pcall(Bag.remove, save, id, n)
    if not ok then return 0, held end
    return n, held
  end

  -- ---- items used on a party member
  --
  -- BagMenu runs every use-flow through the item.use hook
  -- (src/ui/BagMenu.lua:513-516; the wrapped dispatch is vanillaUseOn, :89),
  -- so the wrapper sees (game, battle, id, target, list, moveIndex, picker)
  -- before anything happens. Outside battles, while a client or test
  -- session is connected, on a member with a Cobblemon uid (PLAN P14, DESIGN
  -- section 5 "Items and moves crossing over"):
  --   * an evolution stone the member's species evolves with (ItemEffects.use's
  --     own test, src/inventory/ItemEffects.lua:510-531; Yellow's starter
  --     Pikachu refusal left native) and RARE_CANDY below level 100 (:490-492):
  --     the engine's effect is skipped -- Cobblemon applies it (party.item),
  --     and the next party.sync brings it back. The bag side stays BagMenu's:
  --     the item is consumed and its row refreshed (consume and removeUsed,
  --     BagMenu.lua:39-46, :362-374), and the party menu closes behind the
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
  local itemCounts = { stone = 0, candy = 0, machine = 0, taught = 0, declined = 0, native = 0 }

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
    if not pcall(Bag.remove, save, id, 1) then return false end
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
        if i <= w.known then use.slot = i - 1 end
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

  -- item.use's wrapper body (BagMenu.lua:513-516).
  function engine.itemUse(nextFn, g, battle, id, target, list, moveIndex, picker, bridge)
    local function native()
      return nextFn(g, battle, id, target, list, moveIndex, picker)
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
      for _, m in ipairs(type(sdef.tmhm) == "table" and sdef.tmhm or {}) do
        if m == move then learns = true break end
      end
      local known = 0
      for _, mv in ipairs(type(target.moves) == "table" and target.moves or {}) do
        known = known + 1
        if type(mv) == "table" and mv.id == move then learns = false end
      end
      if not learns then return native() end
      local st = itemState()
      if st then
        st.tmWatch = { mon = target, uid = uid, item = id, move = move, known = known, picker = picker,
                       save = save, ow = ow }
      end
      itemCounts.machine = itemCounts.machine + 1
      local results = { native() }
      engine.tmWatchStep() -- fewer than four moves: taught already
      return unpack(results, 1, table.maxn(results))
    end
    local kind
    if isStone(id) then
      if isYellow() then return native() end
      for _, evo in ipairs(type(sdef.evolutions) == "table" and sdef.evolutions or {}) do
        if type(evo) == "table" and evo.method == "ITEM" and evo.item == id then kind = "stone" break end
      end
    elseif id == "RARE_CANDY" and tonumber(target.level) and target.level < 100 then
      kind = "candy"
    end
    if not kind or not consumeAndClose(g, id, list, picker) then return native() end
    queueItemUse({ item = id, uid = uid, save = save })
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
      if u.save == save then out[#out + 1] = { item = u.item, uid = u.uid, slot = u.slot, move = u.move } end
    end
    st.itemUses = {}
    return out
  end

  function engine.itemCounts()
    local out = {}
    for k, v in pairs(itemCounts) do out[k] = v end
    return out
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
