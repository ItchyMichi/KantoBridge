-- game3 movement, warps and interaction (GEN3_BRIDGE_PLAN.md §4.3, §4.4).
--
-- Gen 3 raises no movement.collision hook (only Gen3Compat's facade does,
-- src/mods/Gen3Compat.lua:1121-1141), so the player's step verdicts come
-- from Player.tryMove itself: Player.update calls it through the module
-- table (src/core/game3/player.lua:1503), so a wrapper sees every attempted
-- step and its result -- "blocked", why with why = bounds | tile | elevation
-- | entity | water (collision.lua:1033-1090) or rotating_gate | acro
-- (player.lua:830-841). The wrapper is installed once per process on the
-- module (a hot reload finds it there and points it at the new instance) and
-- queues verdicts in the protocol's words (player.blocked's reasons: tile,
-- entity, bounds, script); main.lua takes them each step (takeVerdicts).
--
-- STEP_SPEED_PLAN §2.3 (D7): Gen 3 raises no movement.speed hook either, so
-- the same tap shapes the step: when tryMove answers "step" (beginStep, then
-- ordinaryStepFrames: player.lua:502-531, :852-865) or "connection" (the seam,
-- Collision.tryConnection: collision.lua:853-866), Player.stepFrames is final
-- for that step and Player.progress is 0 (the position is progress /
-- stepFrames, player.lua:1362-1372), so a write there is what a hook's answer
-- would be. The core's shaper (stepShaper) decides; a hop, a bike's step and
-- a surf hop or dismount pass (jumping, biking, surfHopping, dismounting).
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Move = {}

local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }

-- The protocol's warp kinds for Emerald's warp behaviours (src/core/game3/constants/emerald/metatile_behaviors.lua),
-- by name, MB_ prefix off (FIELD_MECHANICS_AUDIT_PLAN §13.8 item 4, D2).
local BEHAVIOR_KINDS = {
  LADDER = "ladder", NON_ANIMATED_DOOR = "door", CAVE_DOOR = "door", WARP_DOOR = "door", WATER_DOOR = "door",
  EAST_ARROW_WARP = "edge", WEST_ARROW_WARP = "edge", NORTH_ARROW_WARP = "edge", SOUTH_ARROW_WARP = "edge",
  WATER_SOUTH_ARROW_WARP = "edge", DEEP_SOUTH_WARP = "edge",
  AQUA_HIDEOUT_WARP = "pad", MOSSDEEP_GYM_WARP = "pad", BATTLE_PYRAMID_WARP = "pad", LAVARIDGE_GYM_B1F_WARP = "pad",
  MT_PYRE_HOLE = "hole", CRACKED_FLOOR_HOLE = "hole", LAVARIDGE_GYM_1F_WARP = "hole",
  UP_ESCALATOR = "escalator", DOWN_ESCALATOR = "escalator",
}
-- The engine's announced kinds (warp.lua announce) the protocol names otherwise; "warp" is a request (above).
local ENGINE_KINDS = {
  door = "door", exit_door = "door", escalator = "escalator", stairs = "stairs", teleport = "teleport",
  escape_rope = "rope", fall = "hole", dive = "dive", lavaridge_b1f = "pad", rse_teleport_tile = "pad",
  rse_spin_exit = "pad", rse_mossdeep_gym = "pad",
}
-- Gen 3's blocked reasons -> player.blocked's (protocol/messages/player.blocked.schema.json)
Move.REASONS = { bounds = "bounds", tile = "tile", elevation = "tile", water = "tile", entity = "entity",
                 rotating_gate = "tile", acro = "tile" }
Move.MAX_VERDICTS = 16
local WRAP_SLOT = "kantoBridgeTryMove"
local STEP_RESULTS = { step = true, connection = true }

function Move.install(b)
  local def = b.def
  local verdicts = {}
  local shaper -- STEP_SPEED_PLAN: function(dir, frames, { isPlayer, run, pass }) -> frames or nil

  -- ---- the tryMove tap

  local function record(dir, result, why)
    if result ~= "blocked" or DELTA[dir] == nil then return end
    if #verdicts >= Move.MAX_VERDICTS then table.remove(verdicts, 1) end
    verdicts[#verdicts + 1] = { allowed = false, dir = dir, reason = Move.REASONS[why] or "tile" }
  end

  -- A step just begun, shaped by the core's STEP SPEED (see the header).
  local function shape(dir, result)
    if type(shaper) ~= "function" or not STEP_RESULTS[result] or DELTA[dir] == nil then return end
    local P = b.g3("player")
    if type(P) ~= "table" or not P.moving or P.progress ~= 0 then return end
    local frames = tonumber(P.stepFrames)
    if frames == nil then return end
    local c = { isPlayer = true, run = P.running == true,
                pass = (P.jumping or P.biking or P.surfHopping or P.dismounting) and true or false }
    local out = shaper(dir, frames, c)
    out = tonumber(out)
    if out and out >= 1 and out == math.floor(out) and out ~= frames then P.stepFrames = out end
  end

  local function sink(dir, result, why)
    record(dir, result, why)
    shape(dir, result)
  end

  -- The wrapper's process-lifetime record lives on the Player module (it
  -- outlives a hot reload); `sink` points at the current instance's record.
  local function installTap()
    local P = b.g3("player")
    if type(P) ~= "table" then return false end
    local tap = rawget(P, WRAP_SLOT)
    if type(tap) ~= "table" then
      if type(P.tryMove) ~= "function" then return false end
      tap = { original = P.tryMove }
      -- the results pass through untouched (no packing: a vararg relay)
      local function relay(dir, ...)
        local sink = tap.sink
        if type(sink) == "function" then pcall(sink, dir, (...), (select(2, ...))) end
        return ...
      end
      tap.wrapper = function(dir, ...)
        return relay(dir, tap.original(dir, ...))
      end
      rawset(P, WRAP_SLOT, tap)
    end
    tap.sink = sink
    if P.tryMove ~= tap.wrapper then P.tryMove = tap.wrapper end
    return true
  end

  -- Taken by main.lua each step (GEN3_BRIDGE_PLAN.md §3); installs the tap
  -- the first time the player module is there.
  local tapped = false
  def("takeVerdicts", function()
    if not tapped then tapped = installTap() end
    if #verdicts == 0 then return {} end
    local out = verdicts
    verdicts = {}
    return out
  end)
  def("playerVerdict", function() return nil end) -- no movement.collision hook on Gen 3

  -- STEP_SPEED_PLAN §2.3: Gen 3 has no movement.speed call, so no context for the hook; the core hands its policy
  -- here instead, and the tap applies it at each step's start (installed now if the player module is there).
  def("stepContext", function() return nil end)
  def("stepShaper", function(fn)
    shaper = type(fn) == "function" and fn or nil
    if not tapped then tapped = installTap() end
    return tapped
  end)

  -- ---- facing and buttons

  -- Turns the standing player as the engine's own scripts do
  -- (Player.scriptFace, player.lua:1066-1069, else the facing field);
  -- refused mid-step. True when the player now faces dir.
  def("setFacing", function(dir)
    if not b.inField() or DELTA[dir] == nil then return false end
    local P = b.g3("player")
    if type(P) ~= "table" or P.moving then return false end
    if P.facing ~= dir then
      local ok = b.call(P, "scriptFace", dir)
      if not ok then P.facing = dir end
    end
    return P.facing == dir
  end)

  -- True while any source holds the button (Input:isDown on game.input,
  -- the input mod.input presses into: src/mods/Loader.lua:1467-1505).
  def("buttonDown", function(btn)
    local g = b.game
    local input = type(g) == "table" and g.input or nil
    if type(input) ~= "table" or type(input.isDown) ~= "function" then return false end
    local ok, down = pcall(input.isDown, input, btn)
    return ok and down == true
  end)

  -- ---- warps


  -- Where a warp the player is taking leaves from, as the protocol's warp
  -- kinds, read from the metatile behaviours (collision.lua:586-662) under
  -- the player and in front: player.warped fires as the warp starts
  -- (warp.lua:66-69), the player still on the departure cell (a door is the
  -- cell in front, walked into after the hook).
  def("warpDeparture", function(payload)
    if not b.inField() then return nil end
    local P, Map, C = b.g3("player"), b.g3("map"), b.g3("collision")
    if type(P) ~= "table" or type(C) ~= "table" then return nil end
    local x, y, facing = P.cellX, P.cellY, P.facing
    local out = { map = type(Map) == "table" and b.symbol(Map.current) and Map.current or nil, x = x, y = y,
                  facing = facing }
    local MB = b.g3("mb")
    local function kindAt(cx, cy)
      local beh = b.ask(C, "behavior", nil, cx, cy)
      if beh == nil then return nil end
      -- Emerald's own warp behaviours by the engine's names (mb.lua nameOf; FA8 item 4, K1): the FRLG predicates
      -- below never match them
      local name = type(MB) == "table" and b.ask(MB, "nameOf", nil, beh) or nil
      local byName = type(name) == "string" and BEHAVIOR_KINDS[(name:gsub("^MB_", ""))] or nil
      if byName then return byName end
      if b.ask(C, "isFallWarp", false, beh) then return "hole" end
      if b.ask(C, "isWarpPad", false, beh) then return "pad" end
      if b.ask(C, "isStairWarpBehavior", false, beh) then return "stairs" end
      if b.ask(C, "isArrowWarpBehavior", false, beh) then return "edge" end
      if b.ask(C, "isWarpDoor", false, beh) or b.ask(C, "isWarpMetatileBehavior", false, beh) then return "door" end
      return nil
    end
    local under = (type(x) == "number" and type(y) == "number") and kindAt(x, y) or nil
    local kind = under
    local d = DELTA[facing]
    if not kind and d and type(x) == "number" then kind = kindAt(x + d[1], y + d[2]) end
    -- The engine's own kind (announce, src/core/game3/warp.lua:55): door, escalator, fall, dive, ... A warp a
    -- script or a mod asked for, and the step warps the engine hands to Warp.request (ladders, cave doors, arrows:
    -- collision.lua:1553), announce "warp": the behaviour's kind then, else a script warp. Either may be a cave
    -- shaft's take (act/kb_climb.lua, docs/specs/HEIGHTS_ALL_GAMES_PLAN.md W3): out.request lets the warp tracker
    -- match it.
    local engineKind = type(payload) == "table" and type(payload.warp) == "table" and payload.warp.kind or nil
    -- a warp to a cell of the map it leaves (the Aqua Hideout's pads, Mossdeep Gym's tiles): no map.entered follows,
    -- so the tracker settles it once the field is quiet (observe/kb_warp.lua; FA8 item 5)
    local dest = type(payload) == "table" and (payload.toMap or (type(payload.warp) == "table" and payload.warp.map)) or nil
    if out.map ~= nil and dest == out.map then out.inMap = true end
    if engineKind == "warp" then
      -- a step warp's tile is under the player or, announced before the step lands, in front of it (a ladder, a cave
      -- door, an escalator; the real engine's sweep, FA8); the kind only names the fade
      out.request = true
      kind = kind or "script"
    elseif not kind and ENGINE_KINDS[engineKind] then
      -- the behaviour first (a pad's tile says pad, whatever path the engine took); the engine's name where there is none
      kind = ENGINE_KINDS[engineKind]
    end
    -- a field move from Minecraft named the warp it set off (fieldmoves.lua mark): Dig, Teleport, Fly, the rope
    local st = type(b.state) == "function" and b.state() or nil
    if st and type(st.departure) == "table" then
      kind = st.departure.kind
      st.departure = nil
    end
    -- MG8: a Wild Routes end's own warps (the way back, a resume onto the route map) are script warps, as Gen 1's
    if type(b.routeOwnWarp) == "function" and b.routeOwnWarp() then kind = "script" end
    out.kind = kind or "door"
    return out
  end)

  -- Gen 3 pins no display options (gen1's classic pin is Gen 1's renderer).
  def("displayPinned", function() return true end)
  def("pinDisplay", function() return 0 end)

  -- ---- interaction (world.interacted: { mapId, x, y, kind, target },
  -- src/core/game3/field.lua:776-782)

  def("interactedFacts", function(payload)
    if type(payload) ~= "table" then return nil end
    local kind = type(payload.kind) == "string" and payload.kind:find("^[a-z_]+$") and #payload.kind <= 32
      and payload.kind or "none"
    local out = { map = b.symbol(payload.mapId) and payload.mapId or nil,
                  x = tonumber(payload.x), y = tonumber(payload.y), kind = kind }
    local target = payload.target
    if kind == "npc" and type(target) == "table" then
      local lid = tonumber(target.localId)
      out.npcId = lid and ("O%d"):format(lid) or nil
      out.npcMoving = target.moving == true
    end
    return out
  end)

  -- Whether (x, y) is a cell of the current map (its layout's size in cells).
  def("cellInMap", function(x, y)
    if not b.inField() then return nil end
    local Map = b.g3("map")
    local d = Map and b.ask(Map, "currentDef", nil) or nil
    local layout = type(d) == "table" and d.midLayout or nil
    local w = type(layout) == "table" and tonumber(layout.width) or (type(d) == "table" and tonumber(d.width)) or nil
    local h = type(layout) == "table" and tonumber(layout.height) or (type(d) == "table" and tonumber(d.height)) or nil
    if not (w and h and type(x) == "number" and type(y) == "number") then return nil end
    return x >= 0 and y >= 0 and x < w and y < h
  end)

  -- True when (x, y) is a counter cell: the A press then reaches one cell
  -- further (pokeemerald's MB_COUNTER through the engine's behaviour names).
  def("counterCell", function(x, y)
    if not b.inField() then return false end
    local C = b.g3("collision")
    local beh = b.ask(C, "behavior", nil, x, y)
    if beh == nil then return false end
    local MB = b.g3("mb")
    local name = MB and b.ask(MB, "nameOf", nil, beh) or nil
    return type(name) == "string" and name:find("COUNTER") ~= nil
  end)
end

return Move
