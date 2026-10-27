-- kb_region: the region acts (docs/PROTOCOL.md "Regions (MG2)",
-- docs/specs/MULTI_GAME_PLAN.md §4.3, §4.13): how one hidden game is parked
-- while another region is followed, resumed when the player comes back, or
-- started from its title screen the first time its region is entered.
--
--   region.park{region}        -> act.result{kind:"park"}
--                                 the hidden player must be in the game and
--                                 idle; the adapter pushes a hold (input and
--                                 steps refused, scripts and NPC movement
--                                 frozen, the music fading), and the cell is
--                                 written into the save's mod data (`away`,
--                                 shown by snapshot.away). A park while
--                                 parked is ok again.
--                                 Reasons: not_ingame, not_idle, busy.
--   region.resume{region, to}  -> act.result{kind:"resume"}
--                                 the hold is popped (or, after a link drop,
--                                 the marker alone is taken up) and the
--                                 marker cleared; when the hidden player is
--                                 not on `to`, the engine's own `warp` is
--                                 queued (as the Wild Routes exits warp,
--                                 Routes.warpTo) -- only onto one of this
--                                 mod's route maps or the saved away cell
--                                 (story safety) -- and the answer comes when
--                                 it has arrived (kb_warp reports it as kind
--                                 "script").
--                                 Reasons: not_parked, not_ingame, bad_target,
--                                 not_idle (a warp or battle under way), busy,
--                                 failed (the warp never arrived).
--   region.start{region}       -> act.result{kind:"start", detail:"new"|"continue"}
--                                 from the title only: the adapter's
--                                 startFromTitle (CONTINUE when a save loads,
--                                 else NEW GAME, the intro included); answered
--                                 once the overworld is on the stack (the
--                                 intro's screens then run as ordinary UI).
--                                 Reasons: not_title, busy, failed.
--
-- One request at a time, like the Wild Routes acts (act/kb_route.lua): more
-- wait (at most QUEUE; beyond that, busy); a start whose title menu had to be
-- closed first, or a resume whose warp is under way, waits in place. The
-- requests of an old connection are dropped. A link drop (a live session
-- gone) pops the hold so the game can be played alone; the marker stays in
-- the save for the next client's snapshot (snapshot.away). A hot reload or a
-- restart with the hold up keeps it: nothing was dropped.
--
-- The region id is the client's bookkeeping (which region's link this is):
-- checked for shape, echoed nowhere. Logs show kinds, codes and counts only.

local Region = {}

Region.WAIT = 120        -- steps a start waits for its menu to close
Region.ARRIVE = 600      -- steps a resume's warp, or a start's overworld, may take
Region.QUEUE = 8         -- requests waiting at most
Region.STORE_KEY = "region"
Region.KINDS = { park = true, resume = true, start = true }

local floor = math.floor
local REGION_ID = "^[a-z][a-z0-9_]*$"
local MAP_ID = "^[A-Z0-9_]+$"
local DIRS = { up = true, down = true, left = true, right = true }

local function regionId(v)
  return type(v) == "string" and #v >= 1 and #v <= 24 and v:find(REGION_ID) ~= nil
end
local function mapId(v) return type(v) == "string" and #v > 0 and #v <= 64 and v:find(MAP_ID) ~= nil end
local function coord(v) return type(v) == "number" and v == floor(v) and v >= -4096 and v <= 4096 end

-- opts:
--   engine   the engine adapter (holdAway, releaseAway, awayHeld, startFromTitle,
--            playthrough, inGame)
--   routes   the kb_routes runtime (byMap: this mod's route maps), or nil
--   world    mod.world (queueScript)
--   store    { get(key), set(key, value) }: mod.save
--   log      kb_log logger (optional)
function Region.new(opts)
  local engine, routes, world, store, log = opts.engine, opts.routes, opts.world, opts.store, opts.log
  local act = {}
  local queue = {}        -- requests waiting, oldest first
  local current           -- the request at the head: waiting, or running (a warp or a start under way)
  local generation
  local wasLive = false   -- a hold is released on a drop (live -> not live), not at a boot without a client
  local steps = 0
  local counts = { requests = 0, ok = 0, failed = 0, busy = 0, parks = 0, resumes = 0, starts = 0, warps = 0,
                   released = 0, dropReleases = 0 }

  -- ------------------------------------------------------------ the marker

  -- The saved away cell { map, x, y }, or nil.
  local function marker()
    local ok, stored = pcall(store.get, Region.STORE_KEY)
    if not ok or type(stored) ~= "table" or type(stored.away) ~= "table" then return nil end
    local a = stored.away
    if mapId(a.map) and coord(a.x) and coord(a.y) then return { map = a.map, x = a.x, y = a.y } end
    return nil
  end

  local function writeMarker(away)
    pcall(store.set, Region.STORE_KEY, away and { away = { map = away.map, x = away.x, y = away.y } } or nil)
  end

  function act.marker() return marker() end
  function act.held() return engine.awayHeld() end

  -- ------------------------------------------------------------ requests

  -- region.park / region.resume / region.start data from the session (role
  -- already checked). Returns true; false plus "bad_message" when malformed.
  function act.request(op, data, seq, gen)
    if not Region.KINDS[op] or type(data) ~= "table" or not regionId(data.region) then return false, "bad_message" end
    local r = { op = op, seq = seq, gen = gen, since = steps, region = data.region }
    if op == "resume" then
      local to = data.to
      if type(to) ~= "table" or not (mapId(to.map) and coord(to.x) and coord(to.y) and DIRS[to.facing]) then
        return false, "bad_message"
      end
      r.to = { map = to.map, x = to.x, y = to.y, facing = to.facing }
    end
    counts.requests = counts.requests + 1
    if #queue >= Region.QUEUE then
      r.refused = true
      counts.busy = counts.busy + 1
    end
    queue[#queue + 1] = r
    return true
  end

  local function answer(out, r, ok, reason, detail)
    if r.onDone then
      -- WS3: an internal request (act/kb_continue.lua) is answered to its caller, never on the wire
      if ok then counts.ok = counts.ok + 1 else counts.failed = counts.failed + 1 end
      pcall(r.onDone, ok, reason, detail)
      return
    end
    local data = { ok = ok, kind = r.op }
    if reason then data.reason = reason end
    if detail then data.detail = detail end
    out[#out + 1] = { "act.result", data, "answer", re = r.seq, gen = r.gen }
    if ok then counts.ok = counts.ok + 1 else counts.failed = counts.failed + 1 end
    if log then log.debug("region.result", { kind = r.op, ok = ok, reason = reason, detail = detail }) end
  end

  local function finish(out, ok, reason, detail)
    local r = current
    current = nil
    answer(out, r, ok, reason, detail)
  end

  -- The world is free: in the game, standing, nothing scripted, no warp, no
  -- battle (kb_route's rule).
  local function idle(f, mode)
    return f.inGame and mode == "idle" and not f.moving and not f.scripted and not f.transitioning
      and not f.battle and not f.forcedPhase
  end

  -- No warp or battle under way (a held game's classifier says busy, so the
  -- mode alone can't be asked).
  local function settled(f)
    return not f.transitioning and not f.battle and not f.forcedPhase and not f.moving
  end

  local function routeMap(id)
    return routes ~= nil and type(routes.byMap) == "table" and routes.byMap[id] ~= nil
  end

  local function queueWarp(to)
    if not (world and type(world.queueScript) == "function") then return false end
    local ok, queued = pcall(world.queueScript, world, { { "warp", to.map, to.x, to.y, to.facing } })
    return ok and queued == true
  end

  local function startPark(f, mode, out)
    if engine.awayHeld() then
      counts.parks = counts.parks + 1
      return finish(out, true)
    end
    if not f.inGame then return finish(out, false, "not_ingame") end
    if not idle(f, mode) then return finish(out, false, "not_idle") end
    local ok, why = engine.holdAway()
    if not ok then return finish(out, false, why or "failed") end
    writeMarker({ map = f.map, x = f.x, y = f.y })
    counts.parks = counts.parks + 1
    if log then log.info("region.parked", {}) end
    return finish(out, true)
  end

  local function startResume(f, out)
    local r = current
    local held, away = engine.awayHeld(), marker()
    if not held and away == nil then return finish(out, false, "not_parked") end
    if not f.inGame then return finish(out, false, "not_ingame") end
    local to = r.to
    if not (routeMap(to.map) or (away ~= nil and away.map == to.map)) then return finish(out, false, "bad_target") end
    if not settled(f) then return finish(out, false, "not_idle") end
    if held then
      engine.releaseAway()
      counts.released = counts.released + 1
    end
    writeMarker(nil)
    if f.map == to.map and f.x == to.x and f.y == to.y then
      counts.resumes = counts.resumes + 1
      if log then log.info("region.resumed", { warped = false }) end
      return finish(out, true)
    end
    -- MG8 (docs/specs/HOENN_PASS_PLAN.md K2): a route map an adapter stands
    -- for itself (Gen 3): the adapter warps onto the town's exit and parks the
    -- player there, reporting the route map once it arrives
    local zone = routes ~= nil and type(routes.byMap) == "table" and routes.byMap[to.map] or nil
    local enter = rawget(engine, "routeEnter")
    if zone and zone.own and type(enter) == "function" then
      local okE, entered = pcall(enter, zone.key)
      if not (okE and entered == true) then return finish(out, false, "busy") end
    elseif not queueWarp(to) then
      return finish(out, false, "busy")
    end
    counts.warps = counts.warps + 1
    r.running, r.startedAt = true, steps
  end

  local function startStart(f, out)
    local r = current
    if engine.playthrough() ~= "title" then return finish(out, false, "not_title") end
    local detail, why = engine.startFromTitle()
    if detail == nil then
      if why == "menu" then
        -- the open title menu was closed this step; try again next step
        if steps - r.since > Region.WAIT then return finish(out, false, "busy") end
        return
      end
      return finish(out, false, why or "failed")
    end
    r.running, r.detail, r.startedAt = true, detail, steps
  end

  local function settle(f, out)
    local r = current
    if r.op == "resume" then
      if f.inGame and f.map == r.to.map and f.x == r.to.x and f.y == r.to.y and not f.transitioning then
        counts.resumes = counts.resumes + 1
        if log then log.info("region.resumed", { warped = true }) end
        return finish(out, true)
      end
      if steps - r.startedAt > Region.ARRIVE then return finish(out, false, "failed") end
    elseif r.op == "start" then
      -- the overworld is on the stack (the intro's screens may still be over it); MG7: or the adapter
      -- reports a NEW GAME's intro under way before any overworld exists (Gen 3's Birch speech runs in
      -- the boot phase, GEN3_BRIDGE_PLAN.md §4.8)
      if f.inGame or f.starting then
        counts.starts = counts.starts + 1
        if log then log.info("region.started", { detail = r.detail }) end
        return finish(out, true, nil, r.detail)
      end
      if steps - r.startedAt > Region.ARRIVE then return finish(out, false, "failed") end
    end
  end

  -- f:    the adapter's observe() for this step
  -- mode: kb_player.classify(f)
  -- out:  this step's messages (act.result with re and gen)
  -- ctx:  { live, generation }
  function act.step(f, mode, out, ctx)
    steps = steps + 1
    if not (ctx and ctx.live) then
      queue, current, generation = {}, nil, nil
      -- the link dropped: the game can be played alone; the marker stays (a
      -- hot reload or a restart with the hold up keeps it for the next client)
      if wasLive and engine.awayHeld() and engine.releaseAway() then
        counts.dropReleases = counts.dropReleases + 1
        if log then log.info("region.released", { reason = "link" }) end
      end
      wasLive = false
      return
    end
    wasLive = true
    if generation ~= ctx.generation then
      local kept = {}
      for _, r in ipairs(queue) do
        if r.gen == ctx.generation then kept[#kept + 1] = r end
      end
      queue = kept
      if current and current.gen ~= ctx.generation then current = nil end
      generation = ctx.generation
    end
    local kept = {}
    for _, r in ipairs(queue) do
      if r.refused then answer(out, r, false, "busy") else kept[#kept + 1] = r end
    end
    queue = kept
    if current and current.running then settle(f, out) end
    if not current and #queue > 0 then current = table.remove(queue, 1) end
    if current and not current.running then
      if current.op == "park" then startPark(f, mode, out)
      elseif current.op == "resume" then startResume(f, out)
      else startStart(f, out) end
    end
  end

  -- WORLD_SESSIONS_PLAN WS3: a park or a start for act/kb_continue.lua, in the
  -- same queue as the wire's requests (one at a time), answered by
  -- onDone(ok, reason, detail) instead of act.result. It belongs to the
  -- current connection (dropped with it like any request).
  function act.internal(op, onDone)
    if op ~= "park" and op ~= "start" then return false end
    queue[#queue + 1] = { op = op, since = steps, gen = generation, onDone = onDone, region = "kb_continue" }
    counts.requests = counts.requests + 1
    return true
  end

  function act.busy() return current ~= nil end

  function act.stats()
    local out = {}
    for k, v in pairs(counts) do out[k] = v end
    out.held = engine.awayHeld()
    out.marked = marker() ~= nil
    return out
  end

  return act
end

return Region
