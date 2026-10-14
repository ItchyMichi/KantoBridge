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
    f.bike = P.biking == true
    f.runner = P.running == true
    f.hidden = P.visible == false
    f.playerScripted = P._scriptedStep ~= nil
    f.forcedPhase = forced() or nil
    f.transitioning = transitioning()
    f.battle = battleActive()
    f.scripted = scriptRunning()
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
        }
      end
    end
    return out, mapId, npcGeneration
  end)
end

return Observe
