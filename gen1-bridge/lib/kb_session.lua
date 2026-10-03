-- kb_session: one Minecraft connection's protocol session (docs/PROTOCOL.md
-- "Transport", "Handshake"): the listener, the hello-first rule, the
-- handshake, the heartbeat timeout, resync, and the test-only debug messages.
--
--   hello  -> hello.ok (re) then snapshot; or hello.reject (re) and close
--   other first message -> error no_hello, close
--   ping -> pong{t} (re); resync -> snapshot (re)
--   10 s without a received message -> drop
--   debug.status / debug.quit: role "test" only (else error test_only)
--
-- Everything outside the protocol is injected: the snapshot, the debug
-- status and what quitting means. Streams (player.* and later the UI, NPCs
-- and story) go only to established client and test sessions.

local Session = {}

Session.ROLES = { client = true, builder = true, test = true }
Session.STREAM_ROLES = { client = true, test = true }
Session.LOG_PER_SECOND = 5
Session.MAX_LOG_MSG = 500

local floor = math.floor
local GAMES = { red = true, blue = true }

local function wholeNumber(v, lo)
  return type(v) == "number" and v == floor(v) and v >= (lo or 0) and v <= 9007199254740991
end

local function versionText(v)
  if type(v) ~= "string" or #v == 0 or #v > 32 or not v:find("^[0-9A-Za-z.+_-]+$") then
    return "unknown"
  end
  return v
end

-- opts:
--   json, Net, Proto, catalog   modules (kb_json, kb_net, kb_proto, kb_catalog)
--   socket       LuaSocket, or a function returning it (resolved lazily)
--   port         listening port
--   clock        seconds (love.timer.getTime in game)
--   log          kb_log logger
--   bridge       gen1-bridge's version string
--   engine()     the engine version string
--   game()       "red" or "blue"
--   sessionId    random per gen1-bridge start
--   snapshot()   the snapshot data (and rebases the observers)
--   status()     the debug.status reply data
--   onQuit(code) debug.quit
--   timeout      seconds of silence before a drop (default: the catalog's)
function Session.new(opts)
  local json, catalog, log, clock = opts.json, opts.catalog, opts.log, opts.clock
  local timeout = opts.timeout or (catalog.timeoutMs / 1000)
  local session = {}
  local role, established = nil, false
  local lastRx = 0
  local counts = { sessions = 0, rejected = 0, timeouts = 0, logsForwarded = 0, logsDropped = 0 }
  local tokens, refilledAt = Session.LOG_PER_SECOND, 0
  local forwarding = false
  local net, proto

  local function reset()
    role, established = nil, false
  end

  net = opts.Net.new({
    socket = opts.socket,
    port = opts.port,
    clock = clock,
    log = log,
    maxLine = catalog.maxLineBytes,
    onLine = function(line)
      lastRx = clock()
      proto.receive(line)
    end,
    onConnect = function()
      reset()
      proto.reset()
      lastRx = clock()
    end,
    onDisconnect = function() reset() end,
    onOversize = function() proto.sendError("line_too_long", nil, "line_too_long") end,
  })

  proto = opts.Proto.new({
    json = json,
    write = net.send,
    log = log,
    maxLine = catalog.maxLineBytes,
    catalog = catalog,
    leg = "m2g",
    gate = function(env)
      if established or env.type == "hello" then return true end
      if env.type ~= "error" then proto.sendError("no_hello", env.seq, "no_hello") end
      net.finish("no_hello")
      return false
    end,
  })

  local function reject(msg, reason)
    counts.rejected = counts.rejected + 1
    proto.reply(msg, "hello.reject", { reason = reason, protocol = catalog.protocol })
    net.finish("rejected")
    if log then log.info("session.rejected", { reason = reason }) end
  end

  proto.on("hello", function(data, msg)
    if established then
      proto.sendError("already_hello", msg.seq, "already_hello")
      return
    end
    if not wholeNumber(data.protocol, 1) or type(data.mod) ~= "string" then
      return reject(msg, "malformed")
    end
    if data.protocol ~= catalog.protocol then return reject(msg, "protocol") end
    -- an unknown role fails the hello schema, so it is malformed (as in fake_gen1)
    if not Session.ROLES[data.role] then return reject(msg, "malformed") end
    role, established = data.role, true
    counts.sessions = counts.sessions + 1
    local game = opts.game()
    proto.reply(msg, "hello.ok", {
      protocol = catalog.protocol,
      bridge = versionText(opts.bridge),
      engine = versionText(opts.engine()),
      game = GAMES[game] and game or "red",
      session = opts.sessionId,
    })
    proto.send("snapshot", opts.snapshot())
    if log then log.info("session.established", { role = role, count = counts.sessions }) end
  end)

  proto.on("ping", function(data, msg)
    if not wholeNumber(data.t) then
      proto.sendError("bad_message", msg.seq, "bad_message")
      return
    end
    proto.reply(msg, "pong", { t = data.t })
  end)

  proto.on("resync", function(_, msg)
    proto.reply(msg, "snapshot", opts.snapshot())
  end)

  local function testOnly(msg)
    if role == "test" then return true end
    proto.sendError("test_only", msg.seq, "test_only")
    return false
  end

  proto.on("debug.status", function(_, msg)
    if testOnly(msg) then proto.reply(msg, "debug.status", opts.status()) end
  end)

  proto.on("debug.quit", function(data, msg)
    if not testOnly(msg) then return end
    if data.code ~= nil and not (wholeNumber(data.code) and data.code <= 255) then
      proto.sendError("bad_message", msg.seq, "bad_message")
      return
    end
    opts.onQuit(data.code or 0)
  end)

  -- One logic step: keep the listener up, move bytes, and drop a silent
  -- client.
  function session.step()
    net.pump()
    if net.connected() and clock() - lastRx > timeout then
      counts.timeouts = counts.timeouts + 1
      if log then log.info("session.timeout", { seconds = timeout }) end
      net.disconnect("timeout")
    end
  end

  -- Sends what this step queued after its pump (live updates).
  function session.flush() net.flush() end

  -- Sends a live update to an established client or test session.
  function session.stream(msgType, data)
    if established and Session.STREAM_ROLES[role] then return proto.send(msgType, data) end
    return nil
  end

  -- kb_log's forward hook: warnings and errors go to the client as `log`
  -- messages, at most LOG_PER_SECOND a second. The line is already redacted.
  function session.forwardLog(level, code, line)
    if forwarding or not established then return end
    local now = clock()
    tokens = math.min(Session.LOG_PER_SECOND, tokens + (now - refilledAt) * Session.LOG_PER_SECOND)
    refilledAt = now
    if tokens < 1 then
      counts.logsDropped = counts.logsDropped + 1
      return
    end
    tokens = tokens - 1
    forwarding = true
    local ok = pcall(proto.send, "log", {
      level = level,
      code = code,
      msg = #line > Session.MAX_LOG_MSG and line:sub(1, Session.MAX_LOG_MSG) or line,
    })
    forwarding = false
    if ok then counts.logsForwarded = counts.logsForwarded + 1 end
  end

  function session.setPort(port) net.setPort(port) end
  function session.port() return net.port() end
  function session.listening() return net.listening() end
  function session.connected() return net.connected() end
  function session.established() return established end
  function session.sessions() return counts.sessions end
  function session.role() return role end
  function session.close() net.close() end

  function session.stats()
    local out = net.stats()
    for k, v in pairs(counts) do out[k] = v end
    local p = proto.stats()
    out.received, out.sent, out.unknown, out.unhandled = p.received, p.sent, p.unknown, p.unhandled
    return out
  end

  return session
end

return Session
