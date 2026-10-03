-- kb_perf: gen1-bridge's own cost (M10; docs/PLAN.md section 6 M10,
-- "gen1-bridge per-step budget under 1 ms on average").
--
-- A cycle runs from one input.step to the next: our step itself and every
-- hook and event handler of ours the engine runs before the next input.step
-- (the stack update's hooks and events, and whatever the main loop's event
-- handling runs, such as F1's save.writing), timed with the engine's
-- high-resolution clock (love.timer.getTime). The engine's own work inside
-- our wrappers -- the nextFn they call -- is not ours and is left out. A
-- call that spans a cycle boundary (a script coroutine that yields inside a
-- hook) is left out too: its clock ran while the engine did other things.
-- The draw-phase hooks (render.output_enabled, render.output: the mirror's
-- capture) run once per drawn frame, not per logic step, and are counted
-- apart, per frame.
--
--   perf = Perf.new({ clock = love.timer.getTime })
--   perf.beginStep()            at input.step: closes the cycle before
--   perf.add(seconds)           time spent in this cycle
--   perf.timed(fn)              fn, timed into the cycle
--   perf.hook(body)             a hook wrapper body(nextFn, ...) whose
--                               nextFn calls are not counted
--   perf.frame(), addDraw(s)    a drawn frame starts; draw-phase time
--   perf.stats()                integers: steps, avgUs, maxUs, over1ms,
--                               totalUs, lastUs, frames, drawAvgUs, drawMaxUs
--   perf.reset()                starts the counts over (drivers)

local Perf = {}

Perf.BUDGET_US = 1000

local floor = math.floor
local select, unpack = select, unpack

local function pack(...) return { n = select("#", ...), ... } end

-- opts.clock: seconds, high resolution
function Perf.new(opts)
  local clock = assert(opts and opts.clock, "kb_perf needs a clock")
  local p = {}
  local cycle = 0
  local open = false
  local acc = 0                       -- seconds in the cycle under way
  local steps, total, max, over, last = 0, 0, 0, 0, 0
  local frames, drawTotal, drawMax = 0, 0, 0
  local frameOpen, dacc = false, 0

  local function us(seconds) return floor(seconds * 1e6 + 0.5) end

  -- input.step: the cycle before is complete.
  function p.beginStep()
    if open then
      local spent = us(acc)
      steps = steps + 1
      total = total + spent
      last = spent
      if spent > max then max = spent end
      if spent > Perf.BUDGET_US then over = over + 1 end
    end
    open, acc = true, 0
    cycle = cycle + 1
  end

  function p.cycle() return cycle end

  function p.add(seconds)
    if type(seconds) == "number" and seconds > 0 then acc = acc + seconds end
  end

  -- fn timed into the cycle (event handlers, our own calls)
  function p.timed(fn)
    return function(...)
      local c0, t0 = cycle, clock()
      local r = pack(fn(...))
      if cycle == c0 then p.add(clock() - t0) end
      return unpack(r, 1, r.n)
    end
  end

  -- A hook wrapper: body(nextFn, ...) with nextFn's time left out.
  function p.hook(body)
    return function(nextFn, ...)
      local c0, t0, inner = cycle, clock(), 0
      local function timedNext(...)
        local t1 = clock()
        local r = pack(nextFn(...))
        inner = inner + (clock() - t1)
        return unpack(r, 1, r.n)
      end
      local r = pack(body(timedNext, ...))
      if cycle == c0 then p.add(clock() - t0 - inner) end
      return unpack(r, 1, r.n)
    end
  end

  -- render.output_enabled: a frame is being drawn (the one before is done)
  function p.frame()
    if frameOpen then
      local spent = us(dacc)
      drawTotal = drawTotal + spent
      if spent > drawMax then drawMax = spent end
    end
    frames = frames + 1
    frameOpen, dacc = true, 0
  end

  function p.addDraw(seconds)
    if type(seconds) == "number" and seconds > 0 then dacc = dacc + seconds end
  end

  function p.stats()
    local drawn = frameOpen and frames - 1 or frames
    return {
      steps = steps,
      avgUs = steps > 0 and floor(total / steps + 0.5) or 0,
      maxUs = max,
      over1ms = over,
      totalUs = total,
      lastUs = last,
      frames = frames,
      drawAvgUs = drawn > 0 and floor(drawTotal / drawn + 0.5) or 0,
      drawMaxUs = drawMax,
    }
  end

  function p.reset()
    steps, total, max, over, last = 0, 0, 0, 0, 0
    frames, drawTotal, drawMax = 0, 0, 0
    frameOpen, dacc = false, 0
    acc = 0
  end

  return p
end

return Perf
