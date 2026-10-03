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
-- M10 (handshake hardening): hello is checked against its schema field by
-- field -- another protocol number is hello.reject "protocol" (whatever
-- else it carries: another protocol may shape its hello differently), then
-- any malformed field (unknown keys, mod, minecraft, player, world, a role
-- that isn't a string) is "malformed", and a role string that isn't
-- client/builder/test is "role"; every reject is answered, then the
-- connection is closed once it has gone out. A connection with no hello
-- within 10 seconds of connecting gets error no_hello and is closed, and so
-- does one whose first line isn't even an envelope (after its
-- bad_envelope). hello.ok carries `tested`, the engine version gen1-bridge
-- is tested with; when the running engine differs, each connection gets one
-- `log` warning engine.untested (after its snapshot).
--
-- Everything outside the protocol is injected: the snapshot, the debug
-- status and what quitting means. Streams (player.* and later the UI, NPCs
-- and story) go only to established client and test sessions. Other
-- services (the export) add their handlers with session.on.

local Session = {}

Session.ROLES = { client = true, builder = true, test = true }
Session.STREAM_ROLES = { client = true, test = true }
Session.LOG_PER_SECOND = 5
Session.MAX_LOG_MSG = 500
Session.HELLO_SECONDS = 10

local floor = math.floor
local GAMES = { red = true, blue = true }

local function wholeNumber(v, lo)
  return type(v) == "number" and v == floor(v) and v >= (lo or 0) and v <= 9007199254740991
end

local VERSION = "^[0-9A-Za-z.+_-]+$"

local function versionText(v)
  if type(v) ~= "string" or #v == 0 or #v > 32 or not v:find(VERSION) then
    return "unknown"
  end
  return v
end

-- hello.schema.json, field by field
local HELLO_KEYS = { protocol = true, role = true, mod = true, minecraft = true, player = true, world = true }
local PLAYER_KEYS = { uuid = true, name = true }
local WORLD_KEYS = { id = true, multiplayer = true }

local function versionField(v)
  return type(v) == "string" and #v >= 1 and #v <= 32 and v:find(VERSION) ~= nil
end

local function onlyKeys(json, t, allowed)
  if not json.isObject(t) then return false end
  for k in pairs(t) do
    if not allowed[k] then return false end
  end
  return true
end

-- The reject reason for a hello's data, or nil when it is acceptable.
function Session.helloProblem(json, data, protocol)
  if not json.isObject(data) then return "malformed" end
  local p = data.protocol
  if not wholeNumber(p, 1) or p > 1000 then return "malformed" end
  if p ~= protocol then return "protocol" end
  if not onlyKeys(json, data, HELLO_KEYS) then return "malformed" end
  if not versionField(data.mod) then return "malformed" end
  if data.minecraft ~= nil and not versionField(data.minecraft) then return "malformed" end
  local player = data.player
  if player ~= nil then
    if not onlyKeys(json, player, PLAYER_KEYS) then return "malformed" end
    local uuid, name = player.uuid, player.name
    if not (type(uuid) == "string" and #uuid == 36 and uuid:find("^[0-9a-f-]+$")) then return "malformed" end
    if not (type(name) == "string" and #name >= 1 and #name <= 16 and name:find("^[A-Za-z0-9_]+$")) then
      return "malformed"
    end
  end
  local world = data.world
  if world ~= nil then
    if not onlyKeys(json, world, WORLD_KEYS) then return "malformed" end
    local id = world.id
    if not (type(id) == "string" and #id >= 1 and #id <= 64 and id:find("^[A-Za-z0-9_.:-]+$")) then
      return "malformed"
    end
    if type(world.multiplayer) ~= "boolean" then return "malformed" end
  end
  if type(data.role) ~= "string" then return "malformed" end
  if not Session.ROLES[data.role] then return "role" end
  return nil
end

-- opts:
--   json, Net, Proto, catalog   modules (kb_json, kb_net, kb_proto, kb_catalog)
--   socket       LuaSocket, or a function returning it (resolved lazily)
--   port         listening port (the configured one)
--   portSpan, fallbackAfter   M9: kb_net's fallback when the port is busy
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
--   tested       M10: the engine version gen1-bridge is tested with
--                (hello.ok.tested; engine.untested when the running one differs)
--   helloSeconds M10: how long a connection may go without hello (default 10)
function Session.new(opts)
  local json, catalog, log, clock = opts.json, opts.catalog, opts.log, opts.clock
  local timeout = opts.timeout or (catalog.timeoutMs / 1000)
  local helloSeconds = opts.helloSeconds or Session.HELLO_SECONDS
  local session = {}
  local role, established = nil, false
  local lastRx = 0
  local helloBy = math.huge
  local counts = {
    sessions = 0, rejected = 0, timeouts = 0, logsForwarded = 0, logsDropped = 0,
    -- M10
    rejectedProtocol = 0, rejectedRole = 0, rejectedMalformed = 0, helloTimeouts = 0, untested = 0,
    badFirstLines = 0,
  }
  local REJECT_FIELD = { protocol = "rejectedProtocol", role = "rejectedRole", malformed = "rejectedMalformed" }
  local tokens, refilledAt = Session.LOG_PER_SECOND, 0
  local forwarding = false
  local generation = 0 -- counts connections, so a reply never reaches a newer one
  local net, proto

  local function reset()
    role, established = nil, false
  end

  net = opts.Net.new({
    socket = opts.socket,
    port = opts.port,
    portSpan = opts.portSpan,
    fallbackAfter = opts.fallbackAfter,
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
      generation = generation + 1
      lastRx = clock()
      helloBy = lastRx + helloSeconds
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
    -- M10: a first line that isn't even an envelope gets its bad_envelope,
    -- then the connection is closed like any other line before hello
    onRejected = function()
      if established then return end
      counts.badFirstLines = counts.badFirstLines + 1
      net.finish("no_hello")
    end,
  })

  local function reject(msg, reason)
    counts.rejected = counts.rejected + 1
    local field = REJECT_FIELD[reason]
    if field then counts[field] = counts[field] + 1 end
    proto.reply(msg, "hello.reject", { reason = reason, protocol = catalog.protocol })
    net.finish("rejected")
    if log then log.info("session.rejected", { reason = reason }) end
  end

  proto.on("hello", function(data, msg)
    if established then
      proto.sendError("already_hello", msg.seq, "already_hello")
      return
    end
    local problem = Session.helloProblem(json, data, catalog.protocol)
    if problem then return reject(msg, problem) end
    role, established = data.role, true
    counts.sessions = counts.sessions + 1
    local game = opts.game()
    local running = versionText(opts.engine())
    local ok = {
      protocol = catalog.protocol,
      bridge = versionText(opts.bridge),
      engine = running,
      game = GAMES[game] and game or "red",
      session = opts.sessionId,
    }
    local tested = opts.tested and versionText(opts.tested) or nil
    if tested ~= nil and tested ~= "unknown" then ok.tested = tested end
    proto.reply(msg, "hello.ok", ok)
    proto.send("snapshot", opts.snapshot())
    if log then log.info("session.established", { role = role, count = counts.sessions }) end
    -- M10: one warning per connection when the engine isn't the tested one
    -- (after the snapshot: nothing lands between hello.ok and it)
    if ok.tested ~= nil and running ~= ok.tested then
      counts.untested = counts.untested + 1
      if log then log.warn("engine.untested", { engine = running, tested = ok.tested }) end
    end
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
  -- client (M10: and one that hasn't said hello in time).
  function session.step()
    net.pump()
    if net.connected() and not established and clock() > helloBy then
      counts.helloTimeouts = counts.helloTimeouts + 1
      if log then log.info("session.hello_timeout", { seconds = helloSeconds }) end
      helloBy = math.huge
      proto.sendError("no_hello", nil, "hello_timeout")
      net.finish("hello_timeout")
    elseif net.connected() and clock() - lastRx > timeout then
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

  -- Services beyond the session's own messages (the export) register here:
  -- handler(data, msg, role) runs for an established session.
  function session.on(msgType, handler)
    proto.on(msgType, function(data, msg) return handler(data, msg, role) end)
  end
  function session.reply(msg, msgType, data) return proto.reply(msg, msgType, data) end
  function session.replyEncoded(msg, msgType, text) return proto.replyEncoded(msg, msgType, text) end
  function session.sendError(code, re, msg) return proto.sendError(code, re, msg) end
  -- bytes queued but not yet sent (backpressure for large replies)
  function session.queued() return net.queued() end
  -- the current connection's number; it changes on every new connection
  function session.generation() return generation end

  function session.setPort(port) net.setPort(port) end
  function session.port() return net.port() end
  function session.configuredPort() return net.configuredPort() end
  function session.portFallback() return net.fallback() end
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
