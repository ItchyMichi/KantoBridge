-- kb_climb: a cave shaft's warp (docs/PROTOCOL.md "Ladders and holes (H5)",
-- docs/specs/CAVES_3D_PLAN.md §3.6).
--
--   warp.take{from, to, kind}  ->  warp.taken{ok, reason?} (re set)
--
-- The player climbs or falls in Minecraft while the hidden player waits
-- where it was, on or next to the shaft's near end (the client never sends a
-- step onto it: the game would warp at once). At the far end the client
-- asks for the warp. It is taken only while the classifier says "idle"
-- (else mode), on the take's `from` map (else map), with the hidden player
-- on the `from` cell or orthogonally next to it (else far). A request made
-- while the hidden player is still walking waits up to WAIT steps for it to
-- stand.
--
-- The warp is the engine's own `warp` command in a queued script
-- (mod.world:queueScript, src/world/WorldAPI.lua:626-632), as the Wild Routes
-- clearing takes its warps (Routes.warpTo, lib/kb_routes.lua): NPCs go back
-- to their spawns, the map's enter script runs, and the arrival cell can't
-- warp again until the player steps off it. A world already running a script
-- refuses it (busy; the client retries). Taken, the warp observer
-- (observe/kb_warp.lua) reports it with kind stairs (a ladder) or hole and
-- the take's re. One request at a time: another while one waits is answered
-- busy.
-- Logs show codes, kinds and counts only.

local Climb = {}

Climb.WAIT = 30   -- steps a request waits for the hidden player to stand

local floor, abs = math.floor, math.abs
local DIRS = { up = true, down = true, left = true, right = true }
local KINDS = { ladder = "stairs", hole = "hole" }

local function whole(v) return type(v) == "number" and v == floor(v) and v >= -4096 and v <= 4096 end
local function mapId(v) return type(v) == "string" and #v > 0 and #v <= 64 and v:find("^[A-Z0-9_]+$") ~= nil end

-- A take's {from, to, kind}, or nil when malformed.
local function parse(data)
  local from, to = data.from, data.to
  if type(from) ~= "table" or type(to) ~= "table" or KINDS[data.kind] == nil then return nil end
  if not (mapId(from.map) and whole(from.x) and whole(from.y)) then return nil end
  if not (mapId(to.map) and whole(to.x) and whole(to.y) and DIRS[to.facing]) then return nil end
  return {
    from = { map = from.map, x = from.x, y = from.y },
    to = { map = to.map, x = to.x, y = to.y, facing = to.facing },
    kind = data.kind,
  }
end

-- opts:
--   world   mod.world (queueScript)
--   warps   the warp observer (observe/kb_warp.lua: expect)
--   log     kb_log logger (optional)
function Climb.new(opts)
  local world, warps, log = opts.world, opts.warps, opts.log
  local act = {}
  local req              -- the request waiting for its answer
  local refused = {}     -- requests that came while one waited (answered busy)
  local generation
  local steps = 0
  local counts = { requests = 0, taken = 0, busy = 0, mode = 0, map = 0, far = 0 }

  local function answer(out, r, ok, reason)
    local data = { ok = ok }
    if reason then data.reason = reason end
    out[#out + 1] = { "warp.taken", data, "answer", re = r.seq, gen = r.gen }
    counts[ok and "taken" or reason] = counts[ok and "taken" or reason] + 1
    if log then log.debug("climb.taken", { ok = ok, reason = reason, kind = r.kind }) end
  end

  -- warp.take data from the session (role already checked). Returns true;
  -- false plus "bad_message" for malformed data.
  function act.request(data, seq, gen)
    local r = parse(data)
    if not r then return false, "bad_message" end
    counts.requests = counts.requests + 1
    r.seq, r.gen, r.since = seq, gen, steps
    if req then
      refused[#refused + 1] = r
      return true
    end
    req = r
    return true
  end

  -- f:    kb_engine.observe() for this step
  -- mode: kb_player.classify(f)
  -- out:  this step's messages; warp.taken is added with re and gen
  -- ctx:  { live, generation }
  function act.step(f, mode, out, ctx)
    steps = steps + 1
    if not (ctx and ctx.live) then
      req, refused, generation = nil, {}, nil
      return
    end
    if generation ~= ctx.generation then
      if req and req.gen ~= ctx.generation then req = nil end
      local kept = {}
      for _, r in ipairs(refused) do
        if r.gen == ctx.generation then kept[#kept + 1] = r end
      end
      refused = kept
      generation = ctx.generation
    end
    for _, r in ipairs(refused) do answer(out, r, false, "busy") end
    refused = {}
    if not req then return end
    local r = req
    if mode ~= "idle" or f.moving then
      -- still walking (or a screen is up): wait a little, then refuse
      if steps - r.since <= Climb.WAIT then return end
      req = nil
      return answer(out, r, false, "mode")
    end
    req = nil
    if f.map ~= r.from.map then return answer(out, r, false, "map") end
    if not (whole(f.x) and whole(f.y)) or abs(f.x - r.from.x) + abs(f.y - r.from.y) > 1 then
      return answer(out, r, false, "far")
    end
    local queue = world and world.queueScript
    if type(queue) ~= "function" then return answer(out, r, false, "busy") end
    local ok, queued = pcall(queue, world, { { "warp", r.to.map, r.to.x, r.to.y, r.to.facing } })
    if not (ok and queued) then return answer(out, r, false, "busy") end
    if warps then warps.expect(KINDS[r.kind], r.seq, r.gen) end
    return answer(out, r, true)
  end

  function act.busy() return req ~= nil end
  function act.stats()
    local out = {}
    for k, v in pairs(counts) do out[k] = v end
    return out
  end

  return act
end

return Climb
