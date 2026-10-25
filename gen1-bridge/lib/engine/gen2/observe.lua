-- gen2 observe (GEN2_BRIDGE_PLAN.md §5.2): the player's facts each step, the
-- NPCs on the current map, what covers the field, whether a game is being
-- played. Everything is read fresh from the World every call (research note
-- g2_world_state.md §1).
--
-- The facts follow gen1's shape (observe/kb_player.lua's classify): `top` is
-- "overworld" when the field is up and nothing on the state stack covers it,
-- "screen" (with a topName) for a text box, a choice or a Gen2<Name> screen,
-- "busy" for a state the bridge doesn't name (the park's hold among them, as
-- on Gen 1), "none" without a game; then transitioning (a map setup chain),
-- playerScripted (a movement stream), forcedPhase (ice, currents, the Cycling
-- Road), ledge (a hop) and scripted (a running script) decide the mode.
--
-- Gen 2 reads directions with isDown only and a held direction chains into
-- the next step at the landing tick (World:pollInput, World.lua:11553-11557;
-- :11452, :11541): the facts say chainsHeld, so the mover lets go once a step
-- is under way, as on Gen 3.
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Observe = {}

-- Logic steps a field move's departure mark waits for its warp's fade (a minute: the used-move text first)
Observe.DEPARTURE_STEPS = 3600

-- The boot screens that belong to a NEW GAME's intro (src/core/Game2.lua:277-310;
-- OakSpeech's steps push them, src/ui/gen2/OakSpeech.lua:219-254).
Observe.INTRO_SCREENS = { Gen2OakSpeech = true, Gen2GenderSelect = true, Gen2NamePick = true,
                          Gen2NamingScreen = true, Gen2InitClock = true }

function Observe.install(b)
  local def = b.def

  -- What a state on the stack is, gen1's classifyState for Gen 2: the shared
  -- TextBox and ChoiceBox (src/render/TextBox.lua, src/ui/ChoiceBox.lua; Gen 2's
  -- field text and yes/no are those, World.lua:4319-4364, :7992-8046), a
  -- screen stamped by the Screens registry (screenId "Gen2<Name>",
  -- src/ui/Screens.lua:174), else busy.
  local function classify(state)
    if state == nil then return "none", "None" end
    if type(state) ~= "table" then return "busy", "Busy" end
    if b.symbol(state.screenId) or (type(state.screenId) == "string" and state.screenId:find("^[%w_]+$")) then
      return "screen", state.screenId
    end
    local mt = getmetatable(state)
    local TextBox, ChoiceBox = b.mod("src.render.TextBox"), b.mod("src.ui.ChoiceBox")
    if state.isTextBox or (TextBox and mt == TextBox) then return "screen", "TextBox" end
    if ChoiceBox and mt == ChoiceBox then return "screen", "ChoiceBox" end
    return "busy", "Busy"
  end
  def("classifyState", classify)

  local function stackStates()
    local g = b.game
    local stack = type(g) == "table" and g.stack or nil
    return type(stack) == "table" and type(stack.states) == "table" and stack.states or {}
  end

  -- A NEW GAME's intro is under way: the boot phase with one of its screens on the stack.
  function b.introRunning()
    local g = b.game
    if type(g) ~= "table" or g.phase ~= "boot" then return false end
    for _, state in ipairs(stackStates()) do
      if type(state) == "table" and Observe.INTRO_SCREENS[state.screenId] then return true end
    end
    return false
  end

  -- The forced-movement chains a held direction doesn't decide, by the
  -- engine's own tests (World:step, World.lua:11512-11535): a current or a door
  -- push under the player (Permissions.currentDirection, doorForcedDirection,
  -- Permissions.lua:135-160), a latched direction on ice (turningDirection is
  -- also set by every ordinary held step, so it counts only on ice, as
  -- acceptsMenuInput asks, :1567-1578). The Cycling Road's roll is no forced move (FA8 item 13, K4): any held
  -- direction wins over it (Bike.forcedDirection, Bike.lua:139-143), so steps go on; f.slope names it, as Gen 1's.
  local function forced(w)
    local P = b.w2("Permissions")
    local okC, coll = b.method(w, "playerCollision")
    if P and okC then
      if b.ask(P, "currentDirection", nil, coll) ~= nil then return true end
      if b.ask(P, "doorForcedDirection", nil, coll) ~= nil then return true end
      if w.turningDirection ~= nil and b.ask(P, "isIce", false, coll) == true then return true end
    end
    return false
  end

  -- A Strength push under way (FA8 item 15, K5): World:tryPushBoulder (World.lua:6190-6214) steps a strength boulder
  -- beside the player the way the player walked, and the player bumps. Its direction, or nil: the step driver then
  -- answers the step "push" (act/kb_move.lua), as Gen 1's.
  local STEP = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
  local function boulderPush(w, p)
    if not w.strengthActive then return nil end
    local World = b.w2("World")
    for _, npc in ipairs(type(w.npcs) == "table" and w.npcs or {}) do
      local d = type(npc) == "table" and npc.moving and STEP[npc.stepDir] or nil
      if d and b.ask(World, "isStrengthBoulder", false, npc) == true then
        local nx, ny = tonumber(npc.cellX), tonumber(npc.cellY)
        if (nx == p.cellX + d[1] and ny == p.cellY + d[2]) or (nx == p.cellX + 2 * d[1] and ny == p.cellY + 2 * d[2]) then
          return npc.stepDir
        end
      end
    end
    return nil
  end

  -- A script, a field animation or a pending scene holds the field
  -- (World:busy's parts, World.lua:1531-1565, without the map setup and the
  -- movement stream, which transitioning and playerScripted say).
  -- (the World starts some of these as false, not nil: pendingSceneScript = false, World.lua:600-612)
  local function on(v) return v ~= nil and v ~= false end
  local function scripted(w)
    local ok, running = b.method(w, "scriptRunning")
    if ok and running then return true end
    return on(w.waitButton) or on(w.pendingSceneScript) or on(w.fishing) or on(w.headbutt)
      or on(w.fieldMove) or on(w.flyAnim) or on(w.skyfall) or on(w.healAnim)
  end

  def("inGame", function() return b.inField() end)

  def("observe", function()
    local g = b.game
    if not b.inField() then
      if type(g) == "table" and g.phase == "boot" then
        -- Oak's speech after NEW GAME is the intro under way: `starting` tells the region act the start took
        if b.introRunning() then return { inGame = false, starting = true, top = "screen", topName = "OakSpeech" } end
        return { inGame = false, top = "screen", topName = "TitleState" }
      end
      return { inGame = false, top = "none", topName = "None" }
    end
    local w = b.ow()
    local p = w.player
    local f = { inGame = true, chainsHeld = true }
    f.map = b.ext(w.map.id)
    f.x, f.y, f.facing = p.cellX, p.cellY, p.facing
    f.moving = p.moving == true
    if f.moving then f.tx, f.ty = p.targetX, p.targetY end
    f.frames = tonumber(p.stepFrames) or 0
    f.ledge = p.jumping == true
    f.surf = w.playerState == "surf" or w.playerState == "surf_pika"
    f.bike = w.playerState == "bike"
    -- on the Cycling Road's downhill (ENGINE_DOWNHILL, World:downhill): the step driver names its roll "slope"
    local okD, down = b.method(w, "downhill")
    f.slope = (f.bike and okD and down == true) and true or false
    local darkSet = type(b.darkMapSet) == "function" and b.darkMapSet() or {}
    f.darkMap = darkSet[f.map] == true
    f.flashLit = type(b.flashLit) == "function" and b.flashLit() or false
    f.playerScripted = on(w.moveState)
    f.forcedPhase = forced(w) or nil
    f.boulderPush = boulderPush(w, p)
    f.transitioning = on(w.mapSetup) or on(w.pendingWarp)
    -- a field move's warp from Minecraft (fieldmoves.lua mark): its kind until its fade starts, then forgotten; a
    -- move that warps nowhere is forgotten after DEPARTURE_STEPS
    local st = b.state()
    local mark = st and st.departure
    if type(mark) == "table" then
      mark.steps = (tonumber(mark.steps) or 0) + 1
      if f.transitioning then
        mark.seen = true
      elseif mark.seen or mark.steps > Observe.DEPARTURE_STEPS then
        st.departure, mark = nil, nil
      end
      f.departure = mark and mark.kind or nil
    end
    f.battle = w.battleActive == true
    f.scripted = scripted(w)
    local top, name = classify(b.adapter.top())
    if top == "none" then
      f.top, f.topName = "overworld", "Overworld"
    else
      f.top, f.topName = top, name
    end
    f.npcs = type(w.npcs) == "table" and #w.npcs or 0
    return f
  end)

  -- ---- NPCs (research note g2_world_state.md §1 "NPCs")

  local npcGeneration, lastList, lastMap = 0, nil, nil

  -- The visible people of the current map as gen1's npc entries (observe/
  -- kb_npcs.lua's shape): id "O<index>" (the object's index in its map,
  -- RomExtractorGen2.lua:1321-1350), sprite the SPRITE_ name, kind (trainer,
  -- item, boulder, person), cell, facing, the step under way, big for the 2x2
  -- objects (Npc.lua:63-80). The optional party follower is left out
  -- (Follower.lua:14-30). Returns (list, mapId, generation) or nil out of the field.
  def("npcs", function()
    if not b.inField() then return nil end
    local w = b.ow()
    local list = type(w.npcs) == "table" and w.npcs or {}
    local mapId = b.ext(w.map.id)
    if list ~= lastList or mapId ~= lastMap then
      npcGeneration = npcGeneration + 1
      lastList, lastMap = list, mapId
    end
    local out = {}
    for _, n in ipairs(list) do
      local d = type(n) == "table" and type(n.def) == "table" and n.def or nil
      local index = d and tonumber(d.index) or nil
      if d and index and not n.follower and not n.hidden and n.visible ~= false then
        local sprite = type(d.sprite) == "string" and d.sprite or nil
        if type(n.spriteDef) == "table" and b.symbol(n.spriteDef.id) then sprite = n.spriteDef.id end
        if not b.symbol(sprite) then sprite = ("SPRITE_%d"):format(tonumber(d.spriteId) or 0) end
        local kind = "person"
        if d.trainer ~= nil or d.type == 2 then
          kind = "trainer"
        elseif d.itemball ~= nil or d.type == 1 then
          kind = "item"
        elseif tonumber(d.movement) == 0x19 then
          kind = "boulder"
        end
        out[#out + 1] = {
          id = ("O%d"):format(index), sprite = sprite, kind = kind,
          x = n.cellX, y = n.cellY, facing = n.facing, moving = n.moving == true,
          tx = n.targetX, ty = n.targetY, progress = n.progress, frames = n.stepFrames,
        }
      end
    end
    return out, mapId, npcGeneration
  end)
end

return Observe
