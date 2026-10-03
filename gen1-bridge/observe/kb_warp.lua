-- kb_warp: the warp messages (docs/PROTOCOL.md "Movement"):
--
--   warp{phase:"start", kind}          the hidden game starts a warp
--   warp{phase:"arrive", kind, to}     the destination map is entered; `to`
--                                      is the player's cell and facing there
--
-- The observer (kb_player) follows the arrival with a player.pos kind "sync"
-- for the new cell; main.lua sends the warp messages before it. A connection
-- crossing (walking off a map edge into the connected map) is not a warp:
-- that step's own player.pos carries the new map.
--
-- Works on kb_engine facts plus the events of the logic step before
-- (refs/_research/P1_gen1_movement.md section 6):
--   ev.warped    the player.warped event, with kb_engine.warpDeparture():
--                fired by every warp-table warp (doors, stairs, exit mats,
--                gate doorways, pads, holes) before anything moves
--   ev.blackout  world.blacked_out, right before the heal-point warp
--   ev.entered   map.entered events { map, via }; via "connection",
--                "reload" and "boot" are not warps
-- and facts.transitioning / facts.departure for warps that emit no
-- player.warped: Fly, Dig/Teleport/Escape Rope, script and mod warps.
--
-- Kinds:
--   door, stairs, edge, pad, hole   from the departure cell (kb_engine)
--   elevator                        any warp-table warp out of an elevator car
--   fly                             Fly (its bird departure, or via "fly")
--   teleport                        the Dig/Teleport/Escape Rope spin; the three
--                                   look alike from here, so dig and rope wait
--                                   for M8's field moves to say which
--   blackout                        a blackout's warp to the heal point
--   script                          any other warp (cutscenes, mods)
-- Logs show codes, kinds and counts only.

local Warp = {}

Warp.KINDS = {
  door = true, stairs = true, edge = true, hole = true, pad = true, fly = true, teleport = true,
  dig = true, rope = true, blackout = true, script = true, elevator = true,
}
Warp.NOT_WARPS = { connection = true, reload = true, boot = true }
-- logic steps a started warp may take before an arrival is sent from the
-- current position anyway (a warp the engine dropped, e.g. a checkpoint
-- restore, which enters a map without map.entered)
Warp.ARRIVE_TIMEOUT = 1200

local floor = math.floor
local DIRS = { up = true, down = true, left = true, right = true }

local function place(f)
  local to = { map = f.map, x = f.x, y = f.y, facing = f.facing }
  local ok = type(to.map) == "string" and to.map:find("^[A-Z0-9_]+$") ~= nil and #to.map <= 64
    and type(to.x) == "number" and to.x == floor(to.x) and to.x >= -4096 and to.x <= 4096
    and type(to.y) == "number" and to.y == floor(to.y) and to.y >= -4096 and to.y <= 4096
    and DIRS[to.facing] == true
  return ok and to or nil
end

-- The kind of a warp-table warp, from kb_engine.warpDeparture().
function Warp.departureKind(d)
  if type(d) ~= "table" then return "door" end
  if type(d.map) == "string" and d.map:find("_ELEVATOR$") then return "elevator" end
  if Warp.KINDS[d.kind] then return d.kind end
  return "door"
end

-- opts.log: kb_log logger (optional)
function Warp.new(opts)
  opts = opts or {}
  local log = opts.log
  local tracker = {}
  local current       -- the warp under way: { kind, at }
  local hint          -- a departure seen before its fade: "fly", "teleport", "hole"
  local steps = 0
  local counts = { started = 0, arrived = 0, late = 0, timeouts = 0, heldBack = 0 }

  -- facts: kb_engine.observe() for this step; ev: the events since the last
  -- step (see above). Returns a list of { "warp", data, tag }.
  function tracker.step(f, ev)
    steps = steps + 1
    ev = ev or {}
    local out = {}

    local function start(kind)
      current = { kind = kind, at = steps }
      counts.started = counts.started + 1
      out[#out + 1] = { "warp", { phase = "start", kind = kind }, "warp" }
    end

    local function arrive(why)
      local to = place(f)
      if to then
        out[#out + 1] = { "warp", { phase = "arrive", kind = current.kind, to = to }, "warp" }
        counts.arrived = counts.arrived + 1
      else
        counts.heldBack = counts.heldBack + 1
        if log then log.warn("warp.arrive_held_back", { kind = current.kind, why = why }) end
      end
      current = nil
    end

    if ev.warped and not current then start(Warp.departureKind(ev.warped)) end
    if ev.blackout and not current then start("blackout") end
    if not current then
      if f.departure then
        hint = f.departure
      elseif not f.transitioning then
        hint = nil
      end
      if f.transitioning then
        start(hint or "script")
        hint = nil
      end
    end
    for _, e in ipairs(ev.entered or {}) do
      if type(e) == "table" and not Warp.NOT_WARPS[e.via] then
        if not current then
          -- a warp whose fade started and ended between two of our steps
          counts.late = counts.late + 1
          start(e.via == "fly" and "fly" or hint or "script")
          hint = nil
        end
        arrive("entered")
      end
    end
    if current and steps - current.at > Warp.ARRIVE_TIMEOUT and not f.transitioning
        and f.top == "overworld" then
      counts.timeouts = counts.timeouts + 1
      if log then log.warn("warp.no_arrival", { kind = current.kind, steps = steps - current.at }) end
      arrive("timeout")
    end
    return out
  end

  function tracker.active() return current ~= nil end
  function tracker.stats()
    local out = {}
    for k, v in pairs(counts) do out[k] = v end
    return out
  end

  return tracker
end

return Warp
