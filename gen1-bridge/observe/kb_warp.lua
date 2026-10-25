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
--   dig, rope, teleport             the escape spin (M8): kb_engine names it
--                                   (facts.departure): the one act.field or the
--                                   bag's ESCAPE ROPE started, else DIG in a
--                                   dungeon and TELEPORT outside, the only
--                                   places the party menu allows each
--   blackout                        a blackout's warp to the heal point
--   script                          any other warp (cutscenes, mods)
--
-- Two Gen 3 lessons (the user's item 11, 2026-10-14: Emerald's screen went
-- dark for ten seconds after every door): an engine may keep fading in after
-- the map.entered that ended a warp, so after an arrival a transition starts
-- no warp until it has ended once; and a fade with no map change (a
-- cutscene's, a battle's end) is no warp, so a "script" warp that only a
-- transition started, whose transition is over for SETTLE steps without a
-- map.entered, arrives from where the player stands (settled).
--
-- A cave shaft's warp (act/kb_climb.lua, docs/PROTOCOL.md "Ladders and
-- holes"): the act queues the engine's warp command and calls expect(kind,
-- re, gen); the next warp that isn't a warp-table one (a queued script's:
-- started by its fade, or announced as a script warp, which Gen 3's engine
-- does) takes that kind (stairs or hole) and both its messages carry the
-- take's re and its connection's generation (main.lua sends a message with a
-- re as a reply, and only to the connection that asked). An expectation no
-- warp follows within EXPECT steps lapses.
-- Logs show codes, kinds and counts only.

local Warp = {}

Warp.KINDS = {
  door = true, stairs = true, edge = true, hole = true, pad = true, fly = true, teleport = true,
  dig = true, rope = true, blackout = true, script = true, elevator = true,
  -- FA0 (FIELD_MECHANICS_AUDIT_PLAN D2), sent from FA8: Gen 3's ladders, escalators, Dive and emerging
  ladder = true, escalator = true, dive = true,
}
Warp.NOT_WARPS = { connection = true, reload = true, boot = true }
-- logic steps a started warp may take before an arrival is sent from the
-- current position anyway (a warp the engine dropped, e.g. a checkpoint
-- restore, which enters a map without map.entered)
Warp.ARRIVE_TIMEOUT = 1200
-- logic steps a fade-started script warp waits, its transition over, for a
-- map.entered before it counts as no warp at all
Warp.SETTLE = 60
-- logic steps a warp.take's expectation waits for its warp to start
Warp.EXPECT = 120

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
  local current       -- the warp under way: { kind, at, re, gen }
  local hint          -- a departure seen before its fade: "fly", "teleport", "hole"
  local expected      -- a warp.take's warp to come: { kind, re, gen, at }
  local steps = 0
  local tail = false  -- an arrival's transition still under way: it starts no new warp
  local counts = { started = 0, arrived = 0, late = 0, timeouts = 0, heldBack = 0, taken = 0, lapsed = 0, settled = 0,
    tails = 0 }

  -- The next script warp is a cave shaft's (act/kb_climb.lua): kind
  -- "stairs" or "hole", answering the warp.take with seq `re` from the
  -- connection of generation `gen`.
  function tracker.expect(kind, re, gen)
    expected = { kind = Warp.KINDS[kind] and kind or "stairs", re = re, gen = gen, at = steps }
  end

  -- facts: kb_engine.observe() for this step; ev: the events since the last
  -- step (see above). Returns a list of { "warp", data, tag }.
  function tracker.step(f, ev)
    steps = steps + 1
    ev = ev or {}
    local out = {}

    local function start(kind, taken, soft, inMap)
      local re, gen
      if taken and expected then
        kind, re, gen = expected.kind, expected.re, expected.gen
        expected = nil
        counts.taken = counts.taken + 1
        soft = false
      end
      -- soft: settled where the player stands once the field is quiet (a script warp; a warp within its map, which no
      -- map.entered ends)
      current = { kind = kind, at = steps, re = re, gen = gen, soft = (soft and kind == "script") or inMap == true }
      counts.started = counts.started + 1
      out[#out + 1] = { "warp", { phase = "start", kind = kind }, "warp", re = re, gen = gen }
    end

    local function arrive(why)
      local to = place(f)
      if to then
        out[#out + 1] = { "warp", { phase = "arrive", kind = current.kind, to = to }, "warp", re = current.re,
          gen = current.gen }
        counts.arrived = counts.arrived + 1
      else
        counts.heldBack = counts.heldBack + 1
        if log then log.warn("warp.arrive_held_back", { kind = current.kind, why = why }) end
      end
      current = nil
      tail = f.transitioning == true
    end

    if expected and not current and steps - expected.at > Warp.EXPECT then
      expected = nil
      counts.lapsed = counts.lapsed + 1
      if log then log.warn("warp.take_lapsed", { steps = Warp.EXPECT }) end
    end
    if ev.warped and not current then
      -- a script warp the engine announces as it starts (Gen 3) may be a warp.take's; a warp-table warp never is
      local kind = Warp.departureKind(ev.warped)
      -- Gen 3's step warps the engine requests (ladders, arrows) now carry their own kind: a pending warp.take still
      -- claims them (departure.request, game3/move.lua warpDeparture)
      start(kind, kind == "script" or (expected ~= nil and type(ev.warped) == "table" and ev.warped.request == true), nil,
        type(ev.warped) == "table" and ev.warped.inMap == true)
    end
    if ev.blackout and not current then start("blackout") end
    if not f.transitioning then tail = false end
    if not current then
      if f.departure then
        hint = f.departure
      elseif not f.transitioning then
        hint = nil
      end
      if f.transitioning and tail then
        counts.tails = counts.tails + 1
      elseif f.transitioning then
        start(hint or "script", hint == nil, hint == nil)
        hint = nil
      end
    end
    for _, e in ipairs(ev.entered or {}) do
      if type(e) == "table" and not Warp.NOT_WARPS[e.via] then
        if not current then
          -- a warp whose fade started and ended between two of our steps
          counts.late = counts.late + 1
          start(e.via == "fly" and "fly" or hint or "script", e.via ~= "fly" and hint == nil)
          hint = nil
        end
        arrive("entered")
      end
    end
    if current and current.soft then
      -- quiet means back in the field, standing: a battle's fade settles after the battle, where the step that started
      -- it lands (FA8 item 6: settled during the battle it named the cell before the step)
      if f.transitioning or f.battle or f.moving or f.top ~= "overworld" then
        current.quiet = nil
      elseif not current.quiet then
        current.quiet = steps
      elseif steps - current.quiet >= Warp.SETTLE then
        counts.settled = counts.settled + 1
        arrive("settled")
      end
    end
    if current and steps - current.at > Warp.ARRIVE_TIMEOUT and not f.transitioning
        and f.top == "overworld" and not f.battle and not f.moving then
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
