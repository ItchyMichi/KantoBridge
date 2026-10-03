-- kb_player: the hidden player's mode and position, as player.state and
-- player.pos messages (docs/PROTOCOL.md "Movement", PLAN section 4.1).
--
-- Works on the plain facts kb_engine.observe() gathers each logic step, so it
-- never touches the engine itself and tests can feed it facts directly.
--
-- Modes, checked in this order (refs/_research/P1_gen1_movement.md section 3):
--   screen    something other than the overworld is on top and takes input
--             (text, menus, battles, the title)
--   busy      a transition with no input: a fade on top, a warp in flight,
--             an emote beat, the Cut or boulder-dust animation
--   forced    the game moves the player: a scripted walk, a ledge hop and
--             its landing hold, a spinner, Fly, Dig/Teleport, holes, pads
--   cutscene  the world is held while the player stands: a script running,
--             NPCs walking, a trainer engaging, the Pokemon Center machine
--   idle      waiting for the player's input
--
-- Positions: at each step start the TARGET cell is sent, with the step's
-- length in frames. A continuous walk lands and starts the next step within
-- one logic step, so a new step is recognised by its target changing, not
-- only by `moving` turning on. Anything else that changes the cell, map or
-- facing (turns, warps, script teleports) is sent as kind "sync", frames 0.

local Player = {}

local function same(a, b, keys)
  if a == nil or b == nil then return false end
  for i = 1, #keys do
    if a[keys[i]] ~= b[keys[i]] then return false end
  end
  return true
end

local STATE_KEYS = { "mode", "bike", "surf", "slope", "hidden", "dark", "lit" }
local POS_KEYS = { "map", "x", "y", "facing" }
local DIRS = { up = true, down = true, left = true, right = true }

local floor = math.floor

-- A position the protocol accepts (protocol/defs.schema.json mapId, coord,
-- dir); anything else, e.g. a half-built player during a map switch, is
-- held back rather than sent malformed.
local function sendable(p)
  return type(p.map) == "string" and p.map:find("^[A-Z0-9_]+$") ~= nil and #p.map <= 64
    and type(p.x) == "number" and p.x == floor(p.x) and p.x >= -4096 and p.x <= 4096
    and type(p.y) == "number" and p.y == floor(p.y) and p.y >= -4096 and p.y <= 4096
    and DIRS[p.facing] == true
end

local function frameCount(v)
  if type(v) ~= "number" or v ~= v then return 0 end
  v = floor(v + 0.5)
  if v < 0 then return 0 end
  if v > 3600 then return 3600 end
  return v
end

function Player.classify(f)
  if f.top ~= "overworld" then
    if f.top == "screen" then return "screen" end
    return "busy" -- a fade or flash on top, or an empty stack mid-switch
  end
  if f.transitioning then return "busy" end
  if f.playerScripted or f.ledge or f.spinning or f.forcedPhase or (f.hopLand or 0) > 0 then
    return "forced"
  end
  if f.emote or f.anim then return "busy" end
  if f.scripted then return "cutscene" end
  return "idle"
end

-- The kind of a step that is starting. M1 tells walk, bike, surf, ledge,
-- spin and script apart; Seafoam currents and boulder-pushing are still sent
-- as "script" and the Cycling Road roll as "bike" (M3/M8 refine them).
function Player.moveKind(f)
  if f.ledge then return "ledge" end
  if f.spinning then return "spin" end
  if f.playerScripted or f.forcedPhase then return "script" end
  if f.surf then return "surf" end
  if f.bike then return "bike" end
  return "walk"
end

function Player.state(f)
  local dark = f.darkMap == true
  return {
    mode = Player.classify(f),
    bike = f.bike == true,
    surf = f.surf == true,
    slope = f.slope == true,
    hidden = f.hidden == true,
    dark = dark,
    lit = dark and f.flashLit == true,
  }
end

-- Where the player is, for a snapshot: the target cell while stepping.
function Player.position(f)
  if not f.inGame then return nil end
  if f.moving and f.tx ~= nil then
    return { map = f.map, x = f.tx, y = f.ty, facing = f.facing }
  end
  return { map = f.map, x = f.x, y = f.y, facing = f.facing }
end

local function copy(t)
  local out = {}
  for k, v in pairs(t) do out[k] = v end
  return out
end

function Player.new()
  local observer = {}
  local sentState, sentPos -- what the client has been told
  local last = {}          -- the previous facts, for step detection

  local function remember(f)
    last = { moving = f.moving == true, tx = f.tx, ty = f.ty, map = f.map }
  end

  -- Messages to send for this step's facts: a list of { type, data }.
  function observer.update(f)
    local out = {}
    local state = Player.state(f)
    if not same(state, sentState, STATE_KEYS) then
      sentState = state
      out[#out + 1] = { "player.state", copy(state) }
    end
    if f.inGame and f.map ~= nil then
      local newStep = f.moving and f.tx ~= nil
        and (not last.moving or f.tx ~= last.tx or f.ty ~= last.ty or f.map ~= last.map)
      local target = newStep and { map = f.map, x = f.tx, y = f.ty, facing = f.facing } or nil
      if target and sendable(target) then
        sentPos = target
        local data = copy(sentPos)
        data.kind = Player.moveKind(f)
        data.frames = frameCount(f.frames)
        out[#out + 1] = { "player.pos", data }
      elseif not f.moving then
        local here = { map = f.map, x = f.x, y = f.y, facing = f.facing }
        if sendable(here) and not same(here, sentPos, POS_KEYS) then
          sentPos = here
          local data = copy(here)
          data.kind = "sync"
          data.frames = 0
          out[#out + 1] = { "player.pos", data }
        end
      end
      remember(f)
    else
      last = {}
    end
    return out
  end

  -- After a snapshot told the client everything, deltas start from it.
  function observer.rebase(f)
    sentState = Player.state(f)
    sentPos = Player.position(f)
    if f.inGame then remember(f) else last = {} end
  end

  return observer
end

return Player
