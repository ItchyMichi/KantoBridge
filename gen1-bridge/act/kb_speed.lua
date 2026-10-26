-- kb_speed: STEP SPEED, how much shorter the hidden game's own step is for the
-- steps the bridge drives (docs/specs/STEP_SPEED_PLAN.md §2.1-§2.3, D1-D9).
--
-- Minecraft walks at exactly the hidden game's pace (one cell per 16 frames,
-- 8 while Emerald runs), and a 45° diagonal crosses cell boundaries √2 times
-- as often, so the hidden player falls behind on diagonals and the step
-- driver's short queue fills. The fix is a shorter hidden step:
--
--   pace   = Minecraft's frames a cell for the player's state: 8 while
--            running (Emerald's B held), else 16
--   target = ceil(pace * 100 / pct)              pct: 100, 150, 200 (default), 300
--   frames = max(1, min(frames, target))         never longer than the engine wanted
--
-- Only for a step the driver owns (a move.step in idle, not a push) and only
-- when the adapter's context says it is the player's own walk, run or surf
-- step: ledge hops, spinners, a bike's steps (rides), Gen 3's surf hops and
-- dismounts keep the engine's frames (`pass`), so the client's forced moves and
-- cutscenes still match. 100 (1X) leaves every length as it is (D9).
--
-- Pure: no engine access; the adapters' stepContext / the Gen 3 tap hand it
-- plain facts { isPlayer, pass, run }.

local Speed = {}

Speed.CHOICES = { 100, 150, 200, 300 }
Speed.DEFAULT = 200
Speed.WALK_PACE = 16 -- Rules.WALK_SPEED: one 2-block cell per 16 frames
Speed.RUN_PACE = 8   -- Rules.BIKE_SPEED, also Emerald's running shoes

local VALID = {}
for _, pct in ipairs(Speed.CHOICES) do VALID[pct] = true end

-- True for one of the option's values.
function Speed.valid(pct)
  return type(pct) == "number" and VALID[pct] == true
end

-- The frames a driven step takes at pct, for Minecraft's pace.
function Speed.target(pace, pct)
  return math.max(1, math.ceil(pace * 100 / pct))
end

-- opts.pct (default 200; anything else is 200 and opts.log hears
-- move.speed_option_invalid once), opts.log (kb_log, optional)
function Speed.new(opts)
  opts = opts or {}
  local log = opts.log
  local s = { counts = { fast = 0, passed = 0 } }
  local pct = Speed.DEFAULT

  -- Sets the option; false (and 200) for anything not in CHOICES.
  function s.setPct(v)
    v = tonumber(v)
    if Speed.valid(v) then
      pct = v
      return true
    end
    if log then log.once("move.speed_option_invalid", "warn", "move.speed_option_invalid", {}) end
    pct = Speed.DEFAULT
    return false
  end

  function s.pct() return pct end

  -- frames: the step's length after the engine (and any inner hook link);
  -- c: the adapter's context { isPlayer, pass, run } or nil; owns: the
  -- driver executes this step (driver.ownsStep). Returns the frames to use.
  function s.apply(frames, c, owns)
    local f = tonumber(frames)
    if f == nil or type(c) ~= "table" or not c.isPlayer or not owns then return frames end
    if c.pass then
      s.counts.passed = s.counts.passed + 1
      return frames
    end
    local target = Speed.target(c.run and Speed.RUN_PACE or Speed.WALK_PACE, pct)
    local out = math.max(1, math.floor(math.min(f, target)))
    if out < f then s.counts.fast = s.counts.fast + 1 end
    return out
  end

  function s.stats()
    return { pct = pct, fast = s.counts.fast, passed = s.counts.passed }
  end

  if opts.pct ~= nil then s.setPct(opts.pct) end
  return s
end

return Speed
