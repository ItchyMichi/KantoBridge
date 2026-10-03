-- kb_mirror: the hidden game's live frame for Minecraft's mirror screen
-- (docs/PLAN.md section 4.1 "Mirror", docs/PROTOCOL.md "UI";
-- refs/_research/P2_gen1_ui.md sections 1.7-1.8 and 9).
--
--   mirror.sub{fps}    start (1-30 frames a second); belongs to the connection
--   mirror.unsub{}     stop
--   mirror.frame{w:160, h:144, png, n}
--
-- Capture (P2 1.7, recipe A): main.lua wraps render.output_enabled to
-- answer true only while a client is subscribed (the engine then composes
-- the frame into a full-window canvas, Renderer.lua:942-962), and wraps
-- render.output as a pure observer: it captures, then returns next(ctx)
-- unchanged, so the engine presents the frame exactly as before
-- (Renderer.lua:1383-1419). It never hides a state, never takes the output
-- over and never writes a file (P2 9).
--
-- One capture: the game rectangle (gameX/Y/Width/Height of ctx.canvas,
-- the finished composite with colour, fades and wipes) is drawn scaled into
-- our own 160x144 canvas inside love.graphics.push("all")/pop(); each GB
-- pixel is an SxS block of the integer-scaled frame, so sampling block
-- centres gives exact colours. Then newImageData() (after pop: the canvas
-- is no longer the target), a hash of the pixels to skip identical frames,
-- encode("png") with no filename, base64. At most `fps` captures a second;
-- the newest frame waits for the next logic step, where it is sent unless
-- the client lags (more than MAX_BACKLOG bytes queued: the frame is dropped).
--
-- The cost of every capture is measured (microseconds, for debug.status).

local Mirror = {}

Mirror.W, Mirror.H = 160, 144
Mirror.MAX_BACKLOG = 256 * 1024
Mirror.MAX_PNG_B64 = 1048576 -- mirror.frame png maxLength

local floor = math.floor

-- opts:
--   love       the love facade (graphics, data); tests pass a fake
--   clock      seconds (love.timer.getTime)
--   base64     fallback encoder (kb_images.base64) when love.data has none
--   log        kb_log logger (optional)
function Mirror.new(opts)
  local love, clock, log = opts.love, opts.clock, opts.log
  local m = {}
  local sub                 -- { fps, gen }
  local nextAt = -math.huge
  local lastHash
  local pending             -- the newest frame not sent yet: { png (base64), bytes }
  local target              -- our 160x144 canvas
  local quad, quadKey
  local frameNo = 0
  local counts = {
    subs = 0, captures = 0, frames = 0, skipped = 0, dropped = 0, failures = 0,
    bytes = 0, pngBytes = 0, totalUs = 0, maxUs = 0, encodeUs = 0,
  }

  function m.subscribe(fps, gen)
    sub = { fps = fps, gen = gen }
    nextAt, lastHash, pending = -math.huge, nil, nil
    counts.subs = counts.subs + 1
    if log then log.info("mirror.subscribed", { fps = fps }) end
  end

  function m.unsubscribe(gen)
    if sub and (gen == nil or sub.gen == gen) then
      sub, pending = nil, nil
      if log then log.info("mirror.unsubscribed", { frames = counts.frames }) end
    end
  end

  -- render.output_enabled: true only while somebody watches
  function m.enabled() return sub ~= nil end

  local function digest(data)
    local d = love.data
    if d and d.hash then
      local ok, h = pcall(d.hash, "md5", data)
      if ok and type(h) == "string" then return h end
    end
    local ok, raw = pcall(data.getString, data)
    return ok and raw or nil
  end

  local function encode64(png)
    local d = love.data
    if d and d.encode then
      local ok, text = pcall(d.encode, "string", "base64", png)
      if ok and type(text) == "string" then return text end
    end
    return opts.base64(png)
  end

  -- The render.output observer's work (ctx: the hook's context). Errors
  -- are the caller's to catch; the graphics state is restored either way.
  function m.capture(ctx)
    if not sub or type(ctx) ~= "table" then return end
    local now = clock()
    if now < nextAt then return end
    local period = 1 / sub.fps
    nextAt = (now - nextAt < period) and (nextAt + period) or (now + period)
    local canvas = ctx.canvas
    local gx, gy, gw, gh = tonumber(ctx.gameX), tonumber(ctx.gameY), tonumber(ctx.gameWidth), tonumber(ctx.gameHeight)
    if canvas == nil or not (gx and gy and gw and gh) or gw <= 0 or gh <= 0 then return end
    local g = love.graphics
    local t0 = clock()
    if target == nil then
      target = g.newCanvas(Mirror.W, Mirror.H, { dpiscale = 1 })
      target:setFilter("nearest", "nearest")
    end
    local cw, ch = canvas:getDimensions()
    local key = gx .. ":" .. gy .. ":" .. gw .. ":" .. gh .. ":" .. cw .. ":" .. ch
    if key ~= quadKey then
      quad, quadKey = g.newQuad(gx, gy, gw, gh, cw, ch), key
    end
    g.push("all")
    local ok, err = pcall(function()
      g.setCanvas(target)
      g.origin()
      g.setScissor()
      g.setShader()
      g.setBlendMode("alpha")
      g.setColor(1, 1, 1, 1)
      g.clear(0, 0, 0, 1)
      g.draw(canvas, quad, 0, 0, 0, Mirror.W / gw, Mirror.H / gh)
    end)
    g.pop()
    if not ok then error(err, 0) end
    counts.captures = counts.captures + 1
    local data = target:newImageData()
    local hash = digest(data)
    if hash ~= nil and hash == lastHash then
      if data.release then data:release() end
      counts.skipped = counts.skipped + 1
      local spent = floor((clock() - t0) * 1e6 + 0.5)
      counts.totalUs = counts.totalUs + spent
      if spent > counts.maxUs then counts.maxUs = spent end
      return
    end
    lastHash = hash
    local t1 = clock()
    local file = data:encode("png")
    local png = file:getString()
    if file.release then file:release() end
    if data.release then data:release() end
    local text = encode64(png)
    local t2 = clock()
    if #text <= Mirror.MAX_PNG_B64 then
      pending = { png = text, bytes = #png }
    end
    local spent = floor((t2 - t0) * 1e6 + 0.5)
    counts.totalUs = counts.totalUs + spent
    counts.encodeUs = counts.encodeUs + floor((t2 - t1) * 1e6 + 0.5)
    if spent > counts.maxUs then counts.maxUs = spent end
  end

  -- One logic step: the newest captured frame goes out to its subscriber.
  -- link: { live, generation, queued (bytes waiting in the socket queue) }
  function m.step(out, link)
    if not sub then return end
    if not (link and link.live) or link.generation ~= sub.gen then
      -- the subscriber's connection is gone
      sub, pending = nil, nil
      return
    end
    if not pending then return end
    local frame = pending
    pending = nil
    if (link.queued or 0) > Mirror.MAX_BACKLOG then
      counts.dropped = counts.dropped + 1
      return
    end
    frameNo = frameNo + 1
    counts.frames = counts.frames + 1
    counts.bytes = counts.bytes + #frame.png
    counts.pngBytes = counts.pngBytes + frame.bytes
    out[#out + 1] = { "mirror.frame", { w = Mirror.W, h = Mirror.H, png = frame.png, n = frameNo }, "mirror" }
  end

  function m.failed()
    counts.failures = counts.failures + 1
    if counts.failures >= 3 and sub then
      -- PLAN section 8: a capture that keeps failing is switched off
      if log then log.warn("mirror.capture_disabled", { failures = counts.failures }) end
      sub, pending = nil, nil
    end
  end

  function m.stats()
    local out = {}
    for k, v in pairs(counts) do out[k] = v end
    out.subscribed = sub ~= nil
    out.avgUs = counts.captures > 0 and floor(counts.totalUs / counts.captures + 0.5) or 0
    local encoded = counts.captures - counts.skipped
    out.encodeAvgUs = encoded > 0 and floor(counts.encodeUs / encoded + 0.5) or 0
    return out
  end

  return m
end

return Mirror
