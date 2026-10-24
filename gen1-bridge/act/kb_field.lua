-- kb_field: act.field, field moves from Minecraft (docs/PROTOCOL.md
-- "Interaction", docs/PLAN.md M8 item 4; refs/_research/P1_gen1_movement.md
-- section 8).
--
--   act.field{action, x?, y?, dest?, item?}  ->  act.result{ok, kind?, reason?}
--                                               (re set)
--
-- The hidden game performs the move as if it were chosen from the party menu
-- or the bag (kb_engine.fieldAction: the engine's own entry points after the
-- engine's own checks):
--   cut       the tree in front, or at x/y (an adjacent cell: the player
--             turns to it first)
--   surf      onto the water in front (or at x/y), or off it onto land
--             (kind "dismount")
--   strength  switch Strength on; walking into a boulder then pushes it
--             (move.step, the engine's own push)
--   flash     light a dark map
--   dig       Dig out of a dungeon; with item ESCAPE_ROPE the bag's Escape
--             Rope instead (kind "rope"; the rope is used up)
--   teleport  Teleport out from an outdoor map
--   bicycle   get on or off (kind "bike_on" / "bike_off")
--   fish      cast the rod `item` (or the best rod in the bag) at the water
--             in front (or at x/y)
--   fly       to `dest` (a map the Town Map's fly list offers), or, without
--             one, open that native fly list (kind "fly_list": it arrives
--             as a list UI, answered with ui.pick / ui.back)
--   MG9 (G2F), Gen 2's: headbutt (the tree in front), rock_smash (the rock
--             in front), whirlpool (the whirlpool in front), waterfall (up
--             the waterfall in front), sweet_scent (a wild Pokemon here)
-- Kinds on success: cut, surf, dismount, strength, flash, dig, rope, (MG9) headbutt,
-- rock_smash, whirlpool, waterfall, sweet_scent, (FP7) dive,
-- teleport, bike_on, bike_off, fish, fly, fly_list. Refusals: not_ingame,
-- not_idle, not_adjacent, no_move (no party member knows it), no_badge,
-- no_target (nothing to cut, no water, no such destination), not_here (the
-- wrong place: indoors, outdoors, not dark, no riding, the Cycling Road, a
-- current), no_item, already (Strength is on), busy (one at a time),
-- failed.
-- A request made while the player is still walking (or the step driver or
-- an interaction is busy) waits up to WAIT steps for the player to stand.
-- Logs show codes, kinds and counts only.

local Field = {}

Field.WAIT = 30 -- steps a request waits for the player to stand
Field.ACTIONS = { cut = true, surf = true, strength = true, flash = true, dig = true, teleport = true,
                  bicycle = true, fish = true, fly = true,
                  -- MG9 (G2F): Gen 2's field moves
                  headbutt = true, rock_smash = true, whirlpool = true, waterfall = true, sweet_scent = true,
                  -- FP7: Emerald's Dive
                  dive = true }
-- the actions that use the cell in front; x/y name it
Field.TARGETED = { cut = true, surf = true, fish = true, headbutt = true, rock_smash = true, whirlpool = true,
                   waterfall = true, dive = true }

local floor, abs = math.floor, math.abs

local function whole(v) return type(v) == "number" and v == floor(v) and v >= -4096 and v <= 4096 end
local function sym(v, pattern)
  return type(v) == "string" and #v > 0 and #v <= 64 and v:find(pattern) ~= nil
end

-- The direction from (fx, fy) to an orthogonally adjacent (tx, ty), or nil.
function Field.direction(fx, fy, tx, ty)
  if type(fx) ~= "number" or type(fy) ~= "number" then return nil end
  local dx, dy = tx - fx, ty - fy
  if abs(dx) + abs(dy) ~= 1 then return nil end
  if dx == 1 then return "right" elseif dx == -1 then return "left" end
  if dy == 1 then return "down" end
  return "up"
end

-- opts:
--   engine   kb_engine instance (setFacing, fieldAction)
--   log      kb_log logger (optional)
function Field.new(opts)
  local engine, log = opts.engine, opts.log
  local act = {}
  local req              -- the request in progress
  local refused = {}     -- requests that came while one was waiting (answered busy)
  local generation
  local steps = 0
  local counts = { requests = 0, ok = 0, failed = 0, busy = 0 }
  local byKind = {}

  local function finish(out, ok, kind, reason)
    local data = { ok = ok }
    if kind then data.kind = kind end
    if reason then data.reason = reason end
    out[#out + 1] = { "act.result", data, "answer", re = req.seq, gen = req.gen }
    if ok then
      counts.ok = counts.ok + 1
      byKind[kind] = (byKind[kind] or 0) + 1
    else
      counts.failed = counts.failed + 1
    end
    if log then log.debug("field.result", { action = req.action, ok = ok, kind = kind, reason = reason }) end
    req = nil
  end

  -- act.field data from the session (role already checked). Returns true;
  -- false plus "bad_message" for malformed data.
  function act.request(data, seq, gen)
    if not Field.ACTIONS[data.action] then return false, "bad_message" end
    if (data.x == nil) ~= (data.y == nil) or (data.x ~= nil and not (whole(data.x) and whole(data.y))) then
      return false, "bad_message"
    end
    if data.dest ~= nil and not sym(data.dest, "^[A-Z0-9_]+$") then return false, "bad_message" end
    if data.item ~= nil and not sym(data.item, "^[A-Z0-9_]+$") then return false, "bad_message" end
    counts.requests = counts.requests + 1
    local r = { action = data.action, x = data.x, y = data.y, dest = data.dest, item = data.item,
                seq = seq, gen = gen, since = steps }
    if req then
      counts.busy = counts.busy + 1
      refused[#refused + 1] = r
      return true
    end
    req = r
    return true
  end

  -- f:    kb_engine.observe() for this step
  -- mode: kb_player.classify(f)
  -- out:  this step's messages; act.result is added with re and gen
  -- ctx:  { live, generation, moverBusy, interactBusy }
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
    for _, r in ipairs(refused) do
      out[#out + 1] = { "act.result", { ok = false, reason = "busy" }, "answer", re = r.seq, gen = r.gen }
      counts.failed = counts.failed + 1
    end
    refused = {}
    if not req then return end

    if not f.inGame then return finish(out, false, nil, "not_ingame") end
    local waiting = f.moving or ctx.moverBusy or ctx.interactBusy
    if waiting or mode ~= "idle" then
      -- a step in flight lands within a few steps; anything else is not idle
      if not waiting and mode ~= "idle" then return finish(out, false, nil, "not_idle") end
      if steps - req.since > Field.WAIT then return finish(out, false, nil, "not_idle") end
      return
    end
    if Field.TARGETED[req.action] and req.x ~= nil then
      local dir = Field.direction(f.x, f.y, req.x, req.y)
      if not dir then return finish(out, false, nil, "not_adjacent") end
      if not engine.setFacing(dir) then return finish(out, false, nil, "not_idle") end
    end
    local ok, what = engine.fieldAction(req.action, { dest = req.dest, item = req.item })
    if ok then return finish(out, true, what) end
    return finish(out, false, nil, what or "failed")
  end

  function act.busy() return req ~= nil end

  function act.stats()
    local out = {}
    for k, v in pairs(counts) do out[k] = v end
    for k, v in pairs(byKind) do
      if type(k) == "string" then out["kind_" .. k] = v end
    end
    return out
  end

  return act
end

return Field
