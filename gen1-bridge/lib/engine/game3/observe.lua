-- game3 observe (GEN3_BRIDGE_PLAN.md §4.2): the player's facts each step, the
-- NPCs on the current map, whether a game is being played. Everything is read
-- fresh from the engine's modules every call (world note §1: the gates
-- Player.update itself checks, src/core/game3/player.lua:1446-1482).
--
-- The facts follow gen1's shape (observe/kb_player.lua's classify): `top` is
-- "overworld" when the field is up and nothing of the HUD covers it, "screen"
-- (with a topName) for a message box, a choice, a stack layer, a battle or
-- the boot screens, "none" without a game; then transitioning (a warp, a
-- fade, a field callback), playerScripted (a scripted step), forcedPhase (a
-- forced-movement chain), ledge (a jump) and scripted (a running script or a
-- field lock) decide the mode.
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Observe = {}

-- Stack layer ids (src/ui/game3/*: start_menu.lua:192, party_menu.lua:1064,
-- bag_menu.lua:400, …) -> the screen names gen1 uses where one exists.
Observe.LAYER_NAMES = {
  start = "StartMenu", party = "PartyMenu", summary = "SummaryMenu", bag = "BagMenu", save = "SaveMenu",
  option = "OptionMenu", trainer = "TrainerCard", pc_menu = "PCMenu", box_storage = "BoxMenu", shop = "ShopMenu",
  naming = "NamingScreen", script_list_menu = "ListMenu", rse_pokedex = "PokedexMenu", rse_region_map = "TownMap",
}

-- SE6: pokeemerald's weather ids (src/core/game3/weather.lua:13-31) by the protocol's names (defs.schema.json
-- playerState.weather)
Observe.WEATHER = { [0] = "none", [1] = "clouds", [2] = "sunny", [3] = "rain", [4] = "snow", [5] = "thunder", [6] = "fog",
                    [7] = "ash", [8] = "sand", [9] = "fog_diagonal", [10] = "underwater", [11] = "shade",
                    [12] = "drought", [13] = "downpour", [14] = "bubbles" }

local CAMERA_LID = 127   -- camera_object.lua:6
local SEAM_LID = 0xC0    -- objects carried across a seam mid-script (objects.lua:677-729)

function Observe.install(b)
  local def = b.def

  local function battleActive()
    return b.ask(b.g3("battle"), "isActive", false) == true
  end

  -- The name of what covers the field, or nil: a battle, a stack layer, a
  -- choice, the message box (UI note §2). G3F: a battle held for Cobblemon
  -- (battle.lua) covers nothing: the world is busy (f.battle), as on Gen 1.
  local function screenOver()
    if battleActive() then
      if type(b.heldBattle) == "function" and b.heldBattle() then return nil end
      return "BattleState"
    end
    local Stack = b.ui3("stack")
    local top = b.ask(Stack, "top", nil)
    local id = type(top) == "table" and top.id or (type(top) == "string" and top or nil)
    if id ~= nil then
      id = tostring(id)
      return Observe.LAYER_NAMES[id] or id:upper():gsub("[^A-Z0-9_]", "_")
    end
    local Choice = b.ui3("choice")
    if type(Choice) == "table" and Choice.active then return "ChoiceBox" end
    if b.ask(b.ui3("message"), "isOpen", false) == true then return "TextBox" end
    return nil
  end

  local function transitioning()
    if b.ask(b.g3("warp"), "isBusy", false) == true then return true end
    local Fade = b.ui3("fade")
    if type(Fade) == "table" and (Fade.lockInput or b.ask(Fade, "isActive", false) == true) then return true end
    return b.ask(b.g3("field"), "callbackPending", false) == true
  end

  local function scriptRunning()
    if b.ask(b.g3("field"), "isLocked", false) == true then return true end
    local Space = b.g3("scripting.space")
    local vm = type(Space) == "table" and Space.vm or nil
    if type(vm) == "table" and type(vm.isRunning) == "function" then
      local ok, running = pcall(vm.isRunning, vm)
      if ok and running then return true end
    end
    return false
  end

  local function forced()
    local FM = b.g3("forced_movement")
    if type(FM) ~= "table" then return false end
    for _, name in ipairs({ "isForced", "isActive" }) do
      if type(FM[name]) == "function" then
        local ok, v = pcall(FM[name])
        if ok then return v == true end
      end
    end
    return false
  end

  -- FA8 item 11: the forced move's kind by the engine's own table entry for the tile under the player
  -- (forced_movement.lua M.lookup: sForcedMovementTestFuncs' names): a current (and a waterfall's), ice and slides, a
  -- spin, a muddy slope; nil for the rest (walk tiles, mats: the step driver's script)
  local FORCED_KINDS = { Slip = "ice", MuddySlope = "slope" }
  local function forcedKind(P)
    local FM, C = b.g3("forced_movement"), b.g3("collision")
    if type(FM) ~= "table" or type(FM.lookup) ~= "function" or type(C) ~= "table" then return nil end
    local beh = b.ask(C, "behavior", nil, P.cellX, P.cellY)
    if beh == nil then return nil end
    local ok, _, entry = pcall(FM.lookup, beh)
    local name = ok and type(entry) == "table" and entry.name or nil
    if type(name) ~= "string" then return nil end
    if FORCED_KINDS[name] then return FORCED_KINDS[name] end
    if name:find("Current$") then return "current" end
    if name:find("^Slide") then return "ice" end
    if name:find("^Spin") then return "spin" end
    return nil
  end

  -- SE6 (docs/specs/STORY_EVENTS_PLAN.md): the weather the field draws now, by the protocol's names, or nil
  -- without Emerald's weather engine: its state (field_weather_rse.lua:48-49, R.state; fields :406-456) once it
  -- started and was set up and while the weather isn't suspended (weather.lua's _suspended, the battle's freeze),
  -- currWeather by pokeemerald's ids (weather.lua:13-31; the renderer knows 0-14, field_weather_rse.lua:17-32;
  -- the abnormal weather draws drought and downpour in turn, :1742-1786).
  local function weatherName()
    local R = b.g3("field_weather_rse")
    local S = type(R) == "table" and R.state or nil
    if type(S) ~= "table" then return nil end
    if not (S.started and S.initialized) then return "none" end
    local W = b.g3("weather")
    if type(W) == "table" and W._suspended then return "none" end
    return Observe.WEATHER[tonumber(S.currWeather)] or "none"
  end

  def("inGame", function() return b.inField() end)

  def("observe", function()
    local g = b.game
    if not b.inField() then
      if type(g) == "table" and g.phase == "boot" then
        local phase = type(g.boot) == "table" and g.boot.phase or nil
        -- Birch's speech (the boot phase "oak", boot_modules.lua:111-117) is a NEW GAME's intro under way:
        -- `starting` tells the region act the start took (act/kb_region.lua)
        if phase == "oak" then return { inGame = false, starting = true, top = "screen", topName = "BirchSpeech" } end
        return { inGame = false, top = "screen", topName = "TitleState" }
      end
      return { inGame = false, top = "none", topName = "None" }
    end
    local P, Map = b.g3("player"), b.g3("map")
    local s = b.session()
    if type(P) ~= "table" then return { inGame = false, top = "none", topName = "None" } end
    -- chainsHeld: Player.update starts the next step at once while a direction is held (no standing
    -- poll between steps, player.lua:1437-1504), so the mover lets go once a step is under way
    local f = { inGame = true, chainsHeld = true }
    f.map = type(Map) == "table" and Map.current or s.map
    f.x, f.y, f.facing = P.cellX, P.cellY, P.facing
    f.moving = P.moving == true
    if f.moving then f.tx, f.ty = P.targetX, P.targetY end
    f.frames = tonumber(P.stepFrames) or 0
    f.ledge = P.jumping == true
    f.surf = P.surfing == true
    -- WATER_AND_BRIDGES_PLAN D7: the engine's current elevation (on a bridge cell, elevation 15, it names the floor)
    f.elev = tonumber(P.currentElevation)
    -- item 8: a dark map (a cave that needs Flash) and Flash in use (boot.lua); SE7: a map its scripts darken,
    -- lit by the game's flash level there (b.mapLit)
    f.darkMap = type(b.darkMapSet) == "function" and b.darkMapSet()[f.map] == true or false
    f.flashLit = type(b.mapLit) == "function" and b.mapLit(f.map) or false
    -- SE6: the weather the game draws (field_weather_rse.lua)
    f.weather = weatherName()
    f.bike = P.biking == true
    -- K7 (FA8 item 7): runner is a script running, as Gen 1's (kb_story's flag reread, kb_route's stale check);
    -- running is the running shoes' run
    f.running = P.running == true
    f.hidden = P.visible == false
    -- FA8 item 9 (K5): a Strength push under way (Player.boulderPush, player.lua:801, cleared as it lands :1460): its
    -- direction is the player's facing; the step driver answers the step "push" (act/kb_move.lua), as Gen 1's
    f.boulderPush = type(P.boulderPush) == "table" and P.facing or nil
    f.playerScripted = P._scriptedStep ~= nil
    f.forcedPhase = forced() or nil
    if f.forcedPhase then
      local k = forcedKind(P)
      f.current, f.ice, f.spinning = k == "current" or nil, k == "ice" or nil, k == "spin" or nil
      f.muddy = k == "slope" or nil
    end
    f.transitioning = transitioning()
    -- a field move's warp from Minecraft (fieldmoves.lua mark): the departure the tracker names a fade-started warp by,
    -- until a second after the fade, or a minute when nothing warps; an announced warp takes it (move.lua)
    local st = type(b.state) == "function" and b.state() or nil
    local mark = st and st.departure
    if type(mark) == "table" then
      mark.steps = (tonumber(mark.steps) or 0) + 1
      if f.transitioning then
        mark.seen, mark.quiet = true, 0
      elseif mark.seen then
        mark.quiet = (mark.quiet or 0) + 1
      end
      if (mark.seen and mark.quiet > 60) or mark.steps > 3600 then
        st.departure, mark = nil, nil
      end
      f.departure = mark and mark.kind or nil
    end
    f.battle = battleActive()
    f.scripted = scriptRunning()
    f.runner = f.scripted == true
    local over = screenOver()
    if over then
      f.top, f.topName = "screen", over
    else
      f.top, f.topName = "overworld", "Overworld"
    end
    local Objects = b.g3("objects")
    local n = 0
    for _ in pairs(type(Objects) == "table" and type(Objects._byId) == "table" and Objects._byId or {}) do n = n + 1 end
    f.npcs = n
    -- MG8: parked on a Wild Routes zone's trigger cell, the player stands for the route map (routes.lua)
    if type(b.routeFacts) == "function" then f = b.routeFacts(f) end
    return f
  end)

  -- ---- NPCs (world note §4)

  local npcGeneration, lastTable, lastMap = 0, nil, nil
  local function spriteOf(C, gid)
    local name = C and b.ask(C, "name", nil, C, "event_objects", gid, "OBJ_EVENT_GFX_") or nil
    if type(name) == "string" and not name:find("^OBJ_EVENT_GFX_") then name = "OBJ_EVENT_GFX_" .. name end
    if b.symbol(name) then return name end
    return ("OBJ_EVENT_GFX_%d"):format(tonumber(gid) or 0)
  end

  -- The visible objects of the current map as gen1's npc entries (observe/
  -- kb_npcs.lua's shape): id "O<localId>", sprite the OBJ_EVENT_GFX_ name,
  -- kind (trainer: an object with sight; item: an item ball; boulder: a
  -- pushable boulder; person otherwise), cell, facing, the step under way.
  -- Returns (list, mapId, generation) or nil out of the field.
  def("npcs", function()
    if not b.inField() then return nil end
    local Objects, Map = b.g3("objects"), b.g3("map")
    if type(Objects) ~= "table" or type(Objects._byId) ~= "table" then return nil end
    local mapId = type(Map) == "table" and Map.current or nil
    -- MG8: on a Wild Routes zone's route map (routes.lua) nobody stands but the player
    local routeMap = type(b.routeParkedMap) == "function" and b.routeParkedMap() or nil
    if routeMap then
      if lastMap ~= routeMap then
        npcGeneration = npcGeneration + 1
        lastTable, lastMap = nil, routeMap
      end
      return {}, routeMap, npcGeneration
    end
    if Objects._byId ~= lastTable or mapId ~= lastMap then
      npcGeneration = npcGeneration + 1
      lastTable, lastMap = Objects._byId, mapId
    end
    local C = b.constants()
    local order = type(Objects._order) == "table" and Objects._order or {}
    local out = {}
    for _, lid in ipairs(order) do
      local eo = Objects._byId[lid]
      lid = tonumber(lid)
      if type(eo) == "table" and lid and lid ~= CAMERA_LID and lid < SEAM_LID
          and eo.visible ~= false and not eo.hidden and not eo.invisible then
        local sprite = spriteOf(C, eo.graphicsId)
        local kind = "person"
        if type(eo.def) == "table" and eo.def.item ~= nil then
          kind = "item"
        elseif sprite:find("BOULDER") then
          kind = "boulder"
        elseif eo.sight ~= nil and tonumber(eo.sight) and tonumber(eo.sight) > 0 then
          kind = "trainer"
        end
        out[#out + 1] = {
          id = ("O%d"):format(lid), sprite = sprite, kind = kind,
          x = eo.cellX, y = eo.cellY, facing = eo.facing, moving = eo.moving == true,
          tx = eo.targetX, ty = eo.targetY, progress = eo.progress, frames = eo.stepFrames,
          elev = tonumber(eo.currentElevation), -- D7: a two-floor cell's floor
        }
      end
    end
    return out, mapId, npcGeneration
  end)
end

return Observe
