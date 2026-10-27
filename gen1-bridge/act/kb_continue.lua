-- kb_continue: every saved game in a world continues at join
-- (docs/specs/WORLD_SESSIONS_PLAN.md §2.3, D4; WS3).
--
--   session.bind{..., continue:"play"|"park"|"title"}  M->G
--   session.bound{slot, switched?, save?, started, parked}  G->M (re set)
--
-- Once kb_pairing has the hidden game on the bind's slot, a bind with
-- continue "play" or "park" is handed here instead of being answered at once:
--   play   at the title: the adapter's startFromTitle through kb_region's
--          queue (CONTINUE when the slot holds a save, else NEW GAME with its
--          intro, as region.start runs it): started "continue" | "new";
--          already in game: left as it is, started "none";
--   park   at the title with a save in the slot: the same CONTINUE, then the
--          region hold where the save loaded (kb_region's park: the marker
--          written, snapshot.away), started "continue", parked true; at the
--          title without a save: stays there, started "none"; already in game:
--          held where it stands, started "none", parked true;
-- and session.bound goes out when that is done, so the client's pairing gate
-- holds the player until the game is where it will be. A park waits up to
-- PARK_WAIT steps for an idle world after the CONTINUE (the load's fade);
-- what can't be done is answered as it stands (started as far as it got,
-- parked false). A dropped connection or a newer bind drops the job (the
-- client binds again). continue "title" (or none) answers at once, as
-- before WS3. Logs show kinds and counts only.

local Continue = {}

Continue.PARK_WAIT = 600 -- steps a park after a CONTINUE waits for an idle world

-- opts:
--   engine    the adapter (playthrough, slotHasSave, slotState, awayHeld)
--   region    act/kb_region.lua's acts (internal(op, onDone))
--   log       kb_log logger (optional)
function Continue.new(opts)
  local engine, region, log = opts.engine, opts.region, opts.log
  local cont = {}
  local job
  local steps = 0
  local counts = { jobs = 0, continued = 0, started = 0, parked = 0, untouched = 0, dropped = 0, parkFailed = 0 }

  local function slotHasSave()
    local s = engine.slotState()
    return s and s.active ~= nil and engine.slotHasSave(s.active) == true
  end

  -- The pairing's settled bind r (with r.continue) and its session.bound data
  -- (without started/parked); done(bound) sends it. Replaces a job under way.
  function cont.begin(r, bound, done)
    if job then counts.dropped = counts.dropped + 1 end
    counts.jobs = counts.jobs + 1
    job = { r = r, bound = bound, done = done, phase = "decide", since = steps, started = "none", parked = false }
  end

  local function finish()
    local j = job
    job = nil
    j.bound.started, j.bound.parked = j.started, j.parked
    if j.started == "continue" then counts.continued = counts.continued + 1
    elseif j.started == "new" then counts.started = counts.started + 1
    else counts.untouched = counts.untouched + 1 end
    if j.parked then counts.parked = counts.parked + 1 end
    if log then log.info("session.continued", { continue = j.r.continue, started = j.started, parked = j.parked }) end
    j.done(j.bound)
  end

  local function park(j)
    j.phase = "parking"
    region.internal("park", function(ok, reason)
      if ok then
        j.parked = true
        j.phase = "done"
      elseif reason == "not_idle" and steps - j.since <= Continue.PARK_WAIT then
        j.phase = "park" -- the load's fade: again next step
      else
        counts.parkFailed = counts.parkFailed + 1
        j.phase = "done"
      end
    end)
  end

  -- One logic step, after kb_region's: link = { live, generation }.
  function cont.step(link)
    steps = steps + 1
    local j = job
    if not j then return end
    if not (link and link.live) or j.r.gen ~= link.generation then
      job = nil
      counts.dropped = counts.dropped + 1
      return
    end
    if j.phase == "decide" then
      local atTitle = engine.playthrough() == "title"
      if j.r.continue == "play" then
        if not atTitle then return finish() end
      elseif j.r.continue == "park" then
        if not atTitle then
          if engine.awayHeld() then
            j.parked = true
            return finish()
          end
          j.since = steps
          return park(j)
        end
        if not slotHasSave() then return finish() end
      else
        return finish()
      end
      j.phase = "starting"
      region.internal("start", function(ok, _, detail)
        if ok and (detail == "continue" or detail == "new") then
          j.started = detail
          if j.r.continue == "park" then
            j.since = steps
            j.phase = "park"
          else
            j.phase = "done"
          end
        else
          j.phase = "done"
        end
      end)
    elseif j.phase == "park" then
      park(j)
    elseif j.phase == "done" then
      finish()
    end
  end

  function cont.busy() return job ~= nil end

  function cont.stats()
    local out = {}
    for k, v in pairs(counts) do out[k] = v end
    out.pending = job ~= nil
    return out
  end

  return cont
end

return Continue
