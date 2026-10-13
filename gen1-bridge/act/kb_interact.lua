-- kb_interact: act.interact, "face the cell and press A" (docs/PLAN.md
-- section 4.1 "Interact", docs/PROTOCOL.md "Interaction";
-- refs/_research/P1_gen1_movement.md section 5).
--
--   act.interact{map, x, y}  ->  act.result{ok, kind?, reason?} (re set)
--
-- Only while the classifier says "idle", on the hidden player's map, with
-- the target cell orthogonally adjacent, or two cells away straight ahead
-- across a counter tile (the A press reaches over counters, OC:2655-2660).
-- A request made while the player is still walking (or the step driver is
-- busy) waits up to WAIT steps for the player to stand.
--
-- In one input.step the facing is set (kb_engine.setFacing: no 4-frame
-- turn) and A is tapped; the overworld's handleInput reads both in that
-- same logic step and runs interact(), whose world.interacted event says
-- what was hit. The result:
--   ok true, kind = the interacted kind (npc, sign, door, hidden, script,
--     bookshelf)
--   an NPC that was mid-step gets no talk: retried once, after it stands
--     (or RETRY_WAIT steps), at the cell it stands on if that is still in
--     reach (else ok false, kind npc, reason npc_moved); still moving at the
--     retry: ok false, kind npc, reason npc_moving
--   kind none: ok false, reason nothing
--   no world.interacted within CONFIRM steps: ok false, reason no_effect
--   refusals: not_ingame, wrong_map, off_map (a cell across a map seam:
--     the client names cells in the player's map, so near an outdoor seam x
--     or y can be -1 or one past the edge, and the A press never reaches
--     another map), not_idle, not_adjacent, busy (one interaction at a time)
-- Logs show codes, kinds and counts only.

local Interact = {}

Interact.WAIT = 30         -- steps a request waits for the player to stand
Interact.CONFIRM = 6       -- steps to wait for world.interacted after the tap
Interact.RETRY_WAIT = 64   -- steps to wait for a moving NPC before the retry

local floor, abs = math.floor, math.abs

local function whole(v) return type(v) == "number" and v == floor(v) and v >= -4096 and v <= 4096 end

local function sign(v) return v > 0 and 1 or (v < 0 and -1 or 0) end
local function dirOf(sx, sy)
  if sx == 0 and sy == -1 then return "up" end
  if sx == 0 and sy == 1 then return "down" end
  if sx == -1 and sy == 0 then return "left" end
  if sx == 1 and sy == 0 then return "right" end
  return nil
end

-- The direction to face for the target (and the middle cell when the
-- target is across a counter), or nil plus "not_adjacent".
function Interact.direction(fx, fy, tx, ty, counterAt)
  if type(fx) ~= "number" or type(fy) ~= "number" then return nil, "not_adjacent" end
  local dx, dy = tx - fx, ty - fy
  if abs(dx) + abs(dy) == 1 then return dirOf(dx, dy) end
  if (abs(dx) == 2 and dy == 0) or (dx == 0 and abs(dy) == 2) then
    local sx, sy = sign(dx), sign(dy)
    if counterAt and counterAt(fx + sx, fy + sy) then return dirOf(sx, sy), fx + sx, fy + sy end
  end
  return nil, "not_adjacent"
end

-- opts:
--   engine   kb_engine instance (setFacing, counterCell)
--   log      kb_log logger (optional)
function Interact.new(opts)
  local engine, log = opts.engine, opts.log
  local act = {}
  local req              -- the request in progress
  local refused = {}     -- requests that came while one was running (answered busy)
  local generation
  local steps = 0
  local counts = { requests = 0, ok = 0, failed = 0, retries = 0, taps = 0, busy = 0 }

  local function finish(out, ok, kind, reason)
    local data = { ok = ok }
    if kind then data.kind = kind end
    if reason then data.reason = reason end
    out[#out + 1] = { "act.result", data, "answer", re = req.seq, gen = req.gen }
    if ok then counts.ok = counts.ok + 1 else counts.failed = counts.failed + 1 end
    if log then log.debug("interact.result", { ok = ok, kind = kind, reason = reason }) end
    req = nil
  end

  -- act.interact data from the session (role already checked). Returns
  -- true; false plus "bad_message" for malformed data; a second request
  -- while one is running is answered at once (busy) through `out` on the
  -- next step.
  function act.request(data, seq, gen)
    if type(data.map) ~= "string" or not data.map:find("^[A-Z0-9_]+$") or not whole(data.x) or not whole(data.y) then
      return false, "bad_message"
    end
    counts.requests = counts.requests + 1
    local r = { map = data.map, x = data.x, y = data.y, seq = seq, gen = gen, since = steps, attempts = 0 }
    if req then
      counts.busy = counts.busy + 1
      refused[#refused + 1] = r
      return true
    end
    req = r
    req.phase = "wait"
    return true
  end

  -- Taps when everything allows it. Returns true when it tapped.
  local function tryTap(f, mode, ctx)
    if mode ~= "idle" then return false, "not_idle" end
    if f.moving or ctx.moverBusy or not ctx.budget.can() then return false end
    local dir, why = Interact.direction(f.x, f.y, req.x, req.y, engine.counterCell)
    if not dir then return false, why end
    if not engine.setFacing(dir) then return false end
    ctx.budget.tap("a")
    req.attempts = req.attempts + 1
    req.phase, req.since = "sent", steps
    counts.taps = counts.taps + 1
    return true
  end

  -- f:    kb_engine.observe() for this step
  -- mode: kb_player.classify(f)
  -- ev:   events of the logic step before: ev.interacted (world.interacted
  --       facts from kb_engine.interactedFacts, oldest first)
  -- npcs: kb_engine.npcs() list (the retry waits for its NPC to stand), or nil
  -- out:  this step's messages; act.result is added with re and gen
  -- ctx:  { live, generation, budget = { can(), tap(btn) }, moverBusy }
  function act.step(f, mode, ev, npcs, out, ctx)
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
    for _, r in ipairs(refused) do
      out[#out + 1] = { "act.result", { ok = false, reason = "busy" }, "answer", re = r.seq, gen = r.gen }
      counts.failed = counts.failed + 1
    end
    refused = {}
    if not req then return end

    if req.phase == "sent" then
      local hit
      for _, i in ipairs(ev.interacted or {}) do hit = hit or i end
      if hit then
        if hit.kind == "npc" and hit.npcMoving then
          if req.attempts < 2 then
            req.phase, req.since, req.npc = "retry", steps, hit.npcId
            counts.retries = counts.retries + 1
            return
          end
          return finish(out, false, "npc", "npc_moving")
        end
        if hit.kind == "none" then return finish(out, false, "none", "nothing") end
        return finish(out, true, hit.kind)
      end
      if steps - req.since > Interact.CONFIRM then return finish(out, false, nil, "no_effect") end
      return
    end

    -- waiting (first try) or retrying: check, then tap when the player stands
    if not f.inGame then return finish(out, false, nil, "not_ingame") end
    if f.map ~= req.map then return finish(out, false, nil, "wrong_map") end
    -- a cell across a map seam (x or y -1, or one past the edge): the A press
    -- never reaches another map, so nothing is pressed
    if engine.cellInMap and engine.cellInMap(req.x, req.y) == false then
      return finish(out, false, nil, "off_map")
    end
    if mode ~= "idle" and req.phase == "wait" then return finish(out, false, nil, "not_idle") end
    local dir, why = Interact.direction(f.x, f.y, req.x, req.y, engine.counterCell)
    if not dir and not f.moving then return finish(out, false, nil, why) end
    if req.phase == "retry" then
      -- wait for the NPC to stand, then talk to it where it stands, if that
      -- is still within reach
      local found
      for _, n in ipairs(npcs or {}) do
        if n.id == req.npc then found = n end
      end
      if found and found.moving and steps - req.since <= Interact.RETRY_WAIT then return end
      if found and not found.moving and (found.x ~= req.x or found.y ~= req.y) then
        if not Interact.direction(f.x, f.y, found.x, found.y, engine.counterCell) then
          return finish(out, false, "npc", "npc_moved")
        end
        req.x, req.y = found.x, found.y
      end
    end
    local tapped, reason = tryTap(f, mode, ctx)
    if tapped then return end
    if reason == "not_adjacent" then return finish(out, false, nil, reason) end
    if steps - req.since > Interact.WAIT + (req.phase == "retry" and Interact.RETRY_WAIT or 0) then
      return finish(out, false, nil, "not_idle")
    end
  end

  function act.busy() return req ~= nil end

  function act.stats()
    local out = {}
    for k, v in pairs(counts) do out[k] = v end
    return out
  end

  return act
end

return Interact
