-- kb_player: the hidden player's mode and position, as player.state and
-- player.pos messages (docs/PROTOCOL.md "Movement", PLAN section 4.1).
--
-- Works on the plain facts kb_engine.observe() gathers each logic step, so it
-- never touches the engine itself and tests can feed it facts directly.
--
-- Modes, checked in this order (refs/_research/P1_gen1_movement.md section 3):
--   screen    something other than the overworld is on top and takes input
--             (text, menus, battles, the title)
--   busy      a battle Cobblemon is fighting holds the world (M5: the
--             hidden game waits for battle.result), or a transition with no
--             input: a fade on top, a warp in flight, an emote beat, the Cut
--             or boulder-dust animation
--   forced    the game moves the player: a scripted walk, a ledge hop and
--             its landing hold, a spinner, Fly, Dig/Teleport, holes, pads
--   cutscene  the world is held while the player stands: a script running,
--             NPCs walking, a trainer engaging, the Pokemon Center machine
--   idle      waiting for the player's input
--
-- SE6 (Gen 3): player.state also carries the weather the game draws
-- (facts.weather), so a weather change is a state change.
--
-- Positions: at each step start the TARGET cell is sent, with the step's
-- length in frames. A continuous walk lands and starts the next step within
-- one logic step, so a new step is recognised by its target changing, not
-- only by `moving` turning on. Anything else that changes the cell, map or
-- facing (turns, warps, script teleports) is sent as kind "sync", frames 0.
--
-- A ledge hop is one message: kind "ledge" at the LANDING cell, two cells
-- away, with the whole hop's frames (both cells); its second cell and the
-- stop between them send nothing. A hop that lands on a connected map
-- crosses the seam on its second cell (OC:2124-2136): that step is sent as
-- kind "ledge" too, with the new map and its own frames.
--
-- Each message carries a third, local element (never sent): a tag the step
-- driver (act/kb_move.lua) reads to answer its requests: "state", "step"
-- (a step start), "hop" (a hop's announcement), "seam", "sync".

local Player = {}

local function same(a, b, keys)
  if a == nil or b == nil then return false end
  for i = 1, #keys do
    if a[keys[i]] ~= b[keys[i]] then return false end
  end
  return true
end

local STATE_KEYS = { "mode", "bike", "surf", "slope", "hidden", "dark", "lit", "weather", "run" }
-- SE6 (docs/specs/STORY_EVENTS_PLAN.md): the weather names player.state may carry (defs.schema.json playerState)
local WEATHERS = { none = true, clouds = true, sunny = true, rain = true, snow = true, thunder = true, fog = true,
                   ash = true, sand = true, fog_diagonal = true, underwater = true, shade = true, drought = true,
                   downpour = true, bubbles = true }
local POS_KEYS = { "map", "x", "y", "facing" }
local DIRS = { up = true, down = true, left = true, right = true }
local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }

local floor = math.floor

-- A position the protocol accepts (protocol/defs.schema.json mapId, coord,
-- dir); anything else, e.g. a half-built player during a map switch, is
-- held back rather than sent malformed.
local function sendable(p)
  return type(p) == "table"
    and type(p.map) == "string" and p.map:find("^[A-Z0-9_]+$") ~= nil and #p.map <= 64
    and type(p.x) == "number" and p.x == floor(p.x) and p.x >= -4096 and p.x <= 4096
    and type(p.y) == "number" and p.y == floor(p.y) and p.y >= -4096 and p.y <= 4096
    and DIRS[p.facing] == true
end
Player.sendable = sendable

local function frameCount(v)
  if type(v) ~= "number" or v ~= v then return 0 end
  v = floor(v + 0.5)
  if v < 0 then return 0 end
  if v > 3600 then return 3600 end
  return v
end
Player.frameCount = frameCount

function Player.classify(f)
  if f.top ~= "overworld" then
    if f.top == "screen" then return "screen" end
    return "busy" -- a fade or flash on top, or an empty stack mid-switch
  end
  if f.battle then return "busy" end -- held for a Cobblemon battle's result
  if f.transitioning then return "busy" end
  if f.playerScripted or f.ledge or f.spinning or f.forcedPhase or (f.hopLand or 0) > 0 then
    return "forced"
  end
  if f.emote or f.anim then return "busy" end
  if f.scripted then return "cutscene" end
  return "idle"
end

-- The kind of a step that is starting: a spinner's slide is "spin" (48
-- frames a cell), a Seafoam current's drag "current" (M8: kb_engine's
-- facts.current), any other move the game makes on its own "script"; the
-- step driver turns a Cycling Road roll into "slope" and sends Strength
-- pushes as "push".
function Player.moveKind(f)
  if f.ledge then return "ledge" end
  if f.spinning then return "spin" end
  if f.current then return "current" end
  -- FA8 item 11: ice and slides (Gen 2's and Gen 3's latched slide), a muddy slope pushing back
  if f.ice then return "ice" end
  if f.muddy then return "slope" end
  if f.playerScripted or f.forcedPhase then return "script" end
  if f.surf then return "surf" end
  if f.bike then return "bike" end
  return "walk"
end

function Player.state(f)
  local dark = f.darkMap == true
  local run = nil
  if f.running ~= nil then run = f.running == true end
  return {
    mode = Player.classify(f),
    bike = f.bike == true,
    surf = f.surf == true,
    slope = f.slope == true,
    hidden = f.hidden == true,
    dark = dark,
    lit = dark and f.flashLit == true,
    -- SE6: the weather the game draws (Gen 3); absent where the game has none
    weather = WEATHERS[f.weather] and f.weather or nil,
    -- FA8 item 7 (D4): the hidden player is running (Emerald's running shoes); absent where the game has no running
    run = run,
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
  local hop                -- the ledge hop already announced: { map }
  local syncWanted = false -- the next standing update sends a sync even if unchanged

  local function remember(f)
    last = { moving = f.moving == true, tx = f.tx, ty = f.ty, map = f.map }
  end

  local function posMessage(map, x, y, facing, kind, frames, tag)
    local pos = { map = map, x = x, y = y, facing = facing }
    if not sendable(pos) then return nil end
    sentPos = pos
    local data = copy(pos)
    data.kind = kind
    data.frames = frameCount(frames)
    return { "player.pos", data, tag }
  end

  -- The message for a step that starts now, or nil (held back, or a hop's
  -- second cell).
  local function stepStart(f)
    if f.ledge then
      if hop ~= nil then return nil end -- the second cell: the landing is out already
      local d = DELTA[f.facing]
      if not d then return nil end
      hop = { map = f.map }
      local frames = f.hopFrames or (tonumber(f.frames) or 16) * 2
      return posMessage(f.map, f.tx + d[1], f.ty + d[2], f.facing, "ledge", frames, "hop")
    end
    if hop ~= nil and f.map ~= hop.map then
      hop = nil
      return posMessage(f.map, f.tx, f.ty, f.facing, "ledge", f.frames, "seam")
    end
    hop = nil
    return posMessage(f.map, f.tx, f.ty, f.facing, Player.moveKind(f), f.frames, "step")
  end

  -- Messages to send for this step's facts: a list of { type, data, tag }.
  function observer.update(f)
    local out = {}
    local state = Player.state(f)
    if not same(state, sentState, STATE_KEYS) then
      sentState = state
      out[#out + 1] = { "player.state", copy(state), "state" }
    end
    if f.inGame and f.map ~= nil then
      local newStep = f.moving and f.tx ~= nil
        and (not last.moving or f.tx ~= last.tx or f.ty ~= last.ty or f.map ~= last.map)
      if newStep then
        local msg = stepStart(f)
        if msg then out[#out + 1] = msg end
      elseif not f.moving then
        if hop ~= nil and not f.ledge then hop = nil end -- landed: the hop is over
        if hop == nil then -- between a hop's two cells nothing is sent
          local here = { map = f.map, x = f.x, y = f.y, facing = f.facing }
          if sendable(here) and (syncWanted or not same(here, sentPos, POS_KEYS)) then
            syncWanted = false
            local msg = posMessage(f.map, f.x, f.y, f.facing, "sync", 0, "sync")
            out[#out + 1] = msg
          end
        end
      end
      remember(f)
    else
      last = {}
      hop = nil
    end
    return out
  end

  -- The next update where the player stands sends a sync even when nothing
  -- changed (the answer to move.face).
  function observer.forceSync() syncWanted = true end

  -- After a snapshot told the client everything, deltas start from it.
  function observer.rebase(f)
    sentState = Player.state(f)
    sentPos = Player.position(f)
    hop = (f.inGame and f.ledge) and { map = f.map } or nil
    if f.inGame then remember(f) else last = {} end
  end

  return observer
end

return Player
