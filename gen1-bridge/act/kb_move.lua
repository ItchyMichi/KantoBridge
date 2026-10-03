-- kb_move: the step driver (docs/PLAN.md section 4.1 "Step driver",
-- docs/PROTOCOL.md "Movement").
--
--   move.step{dir, n, push?}   one step; answered exactly once with n, by
--     player.pos     the step's start: its target cell and frames; a ledge
--                    hop's landing cell and the whole hop's frames; kind
--                    "push" when it shoved a Strength boulder (the player
--                    stays); kind "sync" when it set off a warp or the player
--                    ended up elsewhere at once
--     player.blocked tile / entity / bounds when the hidden game refused it
--                    (the hidden player only turns); script when the hidden
--                    game isn't taking steps (not idle), the step was dropped
--                    from a full queue, or nothing happened in time
--   move.face{dir}             turn in place while idle; the observer then
--                              sends player.pos kind "sync", frames 0
--   move.hold{button:"b", on}  hold B, the Cycling Road brake
--
-- How a step is driven (refs/_research/P1_gen1_movement.md sections 1.3-3):
--   * only while the classifier says "idle" and the player stands: set the
--     facing first (kb_engine.setFacing, so there is no 4-frame turn), then
--     mod.input:press the direction. The overworld polls held directions
--     with isDown in that same logic step, so the step starts at once and
--     lands 16 frames later (8 on the bicycle);
--   * the press is released on the landing poll -- the first input.step that
--     finds the player standing again -- unless the next queued step is
--     ready: that one then starts in the same logic step, with no idle frame
--     (a new direction is first set as the facing);
--   * at most MAX_QUEUE steps wait behind the one in flight; beyond that the
--     oldest waiting step is dropped (answered blocked/script, logged with a
--     count);
--   * outcomes come from polling (moving, target, the ledge flag, a boulder's
--     scripted move) and from the movement.collision verdicts for the player
--     (main.lua records them), plus the player.warped event;
--   * the held buttons are a "desired" set: a focus change's Input:reset drops
--     every mod hold, so a hold the engine no longer sees is pressed again;
--   * every hold is released the moment the state leaves "idle" (a held
--     direction would walk on after a text box closes, a held B would close
--     it), and the waiting steps are answered blocked/script;
--   * a push step (move.step push:true, the Minecraft player pushing against
--     an exit mat, a gate or an edge) is held until the engine has tried it,
--     then released: exit mats and edge warps fire on that blocked try.
-- Requests belong to the connection that sent them: a new connection, or
-- none, drops them unanswered and lets go of every hold.
--
-- Messages are added to the observer's list for the step (kb_player tags:
-- "step", "hop", "seam", "sync"); an answered message is tagged "answer".
-- Logs show codes, directions and counts only.

local Move = {}

Move.MAX_QUEUE = 2       -- steps waiting behind the one in flight (PLAN section 8: lower to 1)
Move.PRESS_TIMEOUT = 12  -- logic steps a pressed step may wait for its outcome
Move.HOP_TIMEOUT = 4     -- logic steps between a hop's start and its first cell

local DIR_LIST = { "up", "down", "left", "right" }
local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
local ENGINE_REASONS = { tile = true, entity = true, bounds = true }
local MAX_N = 9007199254740991

local floor = math.floor

local function count(n) return n + 1 end

-- opts:
--   engine            kb_engine instance: setFacing(dir), buttonDown(btn)
--   press(btn)        mod.input:press with the live game; returns a token
--   release(token)    mod.input:release
--   Player            the kb_player module (position, sendable, frameCount)
--   forceSync()       the observer's forceSync (answers move.face)
--   log               kb_log logger (optional)
--   maxQueue          default Move.MAX_QUEUE
function Move.new(opts)
  local engine, Player, log = opts.engine, opts.Player, opts.log
  local maxQueue = opts.maxQueue or Move.MAX_QUEUE
  local driver = {}
  local queue = {}         -- waiting requests, oldest first
  local active             -- the request being executed
  local holds = {}         -- button -> token, the presses we own
  local brake              -- move.hold b: { on, gen }
  local faceReq            -- a move.face waiting for the player to stand idle
  local generation         -- the connection the requests belong to
  local steps = 0
  local lastPos            -- the last valid settled position
  local counts = {
    requests = 0, answered = 0, started = 0, blocked = 0, script = 0, dropped = 0,
    pushes = 0, warps = 0, hops = 0, timeouts = 0, repressed = 0, faces = 0, rolls = 0,
    chained = 0, -- steps begun on the landing poll of the one before (no idle frame)
  }

  local function press(btn)
    local ok, token = pcall(opts.press, btn)
    if ok and token ~= nil then return token end
    if log then log.once("move.press_failed:" .. btn, "warn", "move.press_failed", { button = btn }) end
    return nil
  end

  local function release(token)
    if token ~= nil then pcall(opts.release, token) end
  end

  local function hold(btn)
    if holds[btn] == nil then holds[btn] = press(btn) end
  end

  local function unhold(btn)
    local token = holds[btn]
    if token ~= nil then
      holds[btn] = nil
      release(token)
    end
  end

  local function releaseDirections()
    for i = 1, #DIR_LIST do unhold(DIR_LIST[i]) end
  end

  local function releaseAll()
    for btn in pairs(holds) do unhold(btn) end
  end

  -- A hold the engine no longer sees (Input:reset on a focus change) is
  -- pressed again; the stale token's release is harmless.
  local function repress()
    for btn, token in pairs(holds) do
      if not engine.buttonDown(btn) then
        release(token)
        holds[btn] = press(btn)
        counts.repressed = count(counts.repressed)
        if log then log.info("move.repressed", { button = btn, count = counts.repressed }) end
      end
    end
  end

  -- Where the hidden player is or is about to be (the target mid-step).
  local function settled(f)
    local pos = f and f.inGame and Player.position(f) or nil
    if pos and Player.sendable(pos) then lastPos = pos end
    return lastPos or { map = "NONE", x = 0, y = 0, facing = "down" }
  end

  local function find(out, tag)
    for i = 1, #out do
      local m = out[i]
      if m[1] == "player.pos" and m[3] == tag then return m end
    end
    return nil
  end

  local function answered(req)
    counts.answered = count(counts.answered)
    req.done = true
  end

  local function attach(msg, req)
    msg[2].n = req.n
    msg[3] = "answer"
    answered(req)
  end

  local function blocked(out, req, reason, f)
    local pos = settled(f)
    out[#out + 1] = { "player.blocked", {
      dir = req.dir, reason = reason, map = pos.map, x = pos.x, y = pos.y, n = req.n,
    }, "answer" }
    if reason == "script" then
      counts.script = count(counts.script)
    else
      counts.blocked = count(counts.blocked)
    end
    answered(req)
  end

  -- The answer when the player didn't take a step of its own: the
  -- observer's sync for this step (a turn shows as one) if there is one,
  -- else a fresh one for the settled cell.
  local function answerPos(out, req, f, kind)
    local msg = find(out, "sync") or find(out, "step")
    if msg then
      if kind ~= "sync" then msg[2].kind, msg[2].frames = kind, 0 end
      attach(msg, req)
      return
    end
    local pos = settled(f)
    out[#out + 1] = { "player.pos", {
      map = pos.map, x = pos.x, y = pos.y, facing = pos.facing, kind = kind, frames = 0, n = req.n,
    }, "answer" }
    answered(req)
  end

  local function lastVerdict(ev, dir)
    local found
    for _, v in ipairs(ev.verdicts or {}) do
      if v.dir == dir then found = v end
    end
    return found
  end

  -- What a pressed step did, judged from this step's facts and the events
  -- of the logic step before: "warp", "step", "hop", "moved", "push",
  -- "blocked" (plus the reason), or nil (nothing yet).
  local function outcome(a, f, ev)
    if ev.warped then return "warp" end
    local d = DELTA[a.dir]
    if f.moving then
      if f.facing == a.dir and (f.ledge or f.map ~= a.from.map
          or (f.tx == a.from.x + d[1] and f.ty == a.from.y + d[2])) then
        return "step"
      end
      return nil -- somebody else's move (a cutscene); judged by the mode below
    end
    if f.ledge then return "hop" end -- queued; its first cell starts next step
    if f.map ~= a.from.map or f.x ~= a.from.x or f.y ~= a.from.y then return "moved" end
    if f.boulderPush == a.dir then return "push" end
    local v = lastVerdict(ev, a.dir)
    if v and not v.allowed then
      return "blocked", ENGINE_REASONS[v.reason] and v.reason or "tile"
    end
    return nil
  end

  local function resolve(f, mode, ev, out)
    local a = active
    if a.phase == "moving" then
      -- answered at its start; done once the player stands again (the
      -- landing poll), or when the hop's first cell lands
      if not f.moving then active = nil end
      return
    end
    if a.phase == "hop" then
      local msg = find(out, "hop") or find(out, "seam")
      if msg then
        attach(msg, a)
        a.phase = "moving"
      elseif not f.ledge or steps - a.since > Move.HOP_TIMEOUT then
        answerPos(out, a, f, "sync")
        active = nil
      end
      return
    end
    local what, reason = outcome(a, f, ev)
    if what == "step" then
      local msg = find(out, "step") or find(out, "hop") or find(out, "seam")
      if msg then
        attach(msg, a)
        counts.started = count(counts.started)
        a.phase = "moving"
      else
        blocked(out, a, "script", f) -- the observer held the step back (malformed)
        active = nil
      end
    elseif what == "hop" then
      counts.hops = count(counts.hops)
      a.phase, a.since = "hop", steps
    elseif what == "warp" then
      counts.warps = count(counts.warps)
      answerPos(out, a, f, "sync")
      active = nil
    elseif what == "moved" then
      answerPos(out, a, f, "sync")
      active = nil
    elseif what == "push" then
      counts.pushes = count(counts.pushes)
      answerPos(out, a, f, "push")
      active = nil
    elseif what == "blocked" then
      blocked(out, a, reason, f)
      active = nil
    elseif mode ~= "idle" then
      blocked(out, a, "script", f)
      active = nil
    elseif steps - a.since >= Move.PRESS_TIMEOUT then
      counts.timeouts = count(counts.timeouts)
      if log then log.warn("move.no_outcome", { dir = a.dir, count = counts.timeouts }) end
      blocked(out, a, "script", f)
      active = nil
    end
  end

  -- Starts the next request: the facing first, then only its direction held.
  local function begin(req, f)
    if f.facing ~= req.dir then engine.setFacing(req.dir) end
    for i = 1, #DIR_LIST do
      if DIR_LIST[i] ~= req.dir then unhold(DIR_LIST[i]) end
    end
    hold(req.dir)
    req.phase, req.since = "pressed", steps
    req.from = { map = f.map, x = f.x, y = f.y }
    active = req
  end

  local function dropAll()
    releaseAll()
    queue, active, faceReq, brake = {}, nil, nil, nil
  end

  -- ------------------------------------------------------------ requests

  -- move.step data from the session (role already checked). Returns true,
  -- or false plus "bad_message".
  function driver.request(data, gen)
    local n = data.n
    if DELTA[data.dir] == nil or type(n) ~= "number" or n ~= floor(n) or n < 1 or n > MAX_N
        or (data.push ~= nil and type(data.push) ~= "boolean") then
      return false, "bad_message"
    end
    counts.requests = count(counts.requests)
    queue[#queue + 1] = { dir = data.dir, n = n, push = data.push == true, gen = gen }
    return true
  end

  function driver.face(data, gen)
    if DELTA[data.dir] == nil then return false, "bad_message" end
    faceReq = { dir = data.dir, gen = gen }
    return true
  end

  function driver.hold(data, gen)
    if data.button ~= "b" or type(data.on) ~= "boolean" then return false, "bad_message" end
    brake = { on = data.on, gen = gen }
    return true
  end

  -- ------------------------------------------------------------ each step

  -- f:     kb_engine.observe() for this step (the world as the previous
  --        logic step left it)
  -- mode:  kb_player.classify(f)
  -- ev:    events of the logic step before: ev.warped (player.warped),
  --        ev.verdicts (the player's movement.collision verdicts)
  -- out:   this step's messages so far (observer, warps); answers are added
  -- link:  { live = a client/test session is established, generation }
  function driver.step(f, mode, ev, out, link)
    steps = steps + 1
    ev = ev or {}
    if f.inGame then settled(f) end
    if not (link and link.live) then
      if active or #queue > 0 or next(holds) ~= nil then dropAll() end
      generation = nil
      return
    end
    if generation ~= link.generation then
      -- a new connection: what the old one asked for dies unanswered
      releaseAll()
      active = nil
      local kept = {}
      for _, r in ipairs(queue) do
        if r.gen == link.generation then kept[#kept + 1] = r end
      end
      queue = kept
      if faceReq and faceReq.gen ~= link.generation then faceReq = nil end
      if brake and brake.gen ~= link.generation then brake = nil end
      generation = link.generation
    end

    local landed = active ~= nil and active.phase == "moving"
    if active then resolve(f, mode, ev, out) end
    landed = landed and active == nil

    if mode ~= "idle" then
      -- let go of everything; what waits can't be walked now
      releaseAll()
      for i = 1, #queue do blocked(out, queue[i], "script", f) end
      queue, faceReq = {}, nil
    else
      repress()
      if not f.moving then
        if active == nil and #queue > 0 then
          if landed then counts.chained = count(counts.chained) end
          begin(table.remove(queue, 1), f)
        end
        if active == nil then
          releaseDirections() -- the landing release
          if faceReq then
            if engine.setFacing(faceReq.dir) then
              counts.faces = count(counts.faces)
              if opts.forceSync then opts.forceSync() end
            end
            faceReq = nil
          end
        end
      end
      if brake and brake.on then hold("b") else unhold("b") end
    end

    -- the waiting line stays short: the oldest waiting steps go first
    while #queue > maxQueue do
      local r = table.remove(queue, 1)
      counts.dropped = count(counts.dropped)
      if log then log.warn("move.dropped", { dir = r.dir, count = counts.dropped }) end
      blocked(out, r, "script", f)
    end

    -- a Cycling Road roll: a bike step down that nobody asked for
    -- (handleInput's pull with no d-pad and no A/B held, OC:1770-1792)
    local msg = find(out, "step")
    if msg and f.slope and msg[2].kind == "bike" and msg[2].facing == "down" then
      msg[2].kind = "slope"
      counts.rolls = count(counts.rolls)
    end
  end

  function driver.releaseAll() releaseAll() end
  function driver.busy() return active ~= nil or #queue > 0 end

  function driver.stats()
    local out = {}
    for k, v in pairs(counts) do out[k] = v end
    out.queued = #queue
    local held = 0
    for _ in pairs(holds) do held = held + 1 end
    out.held = held
    return out
  end

  return driver
end

return Move
