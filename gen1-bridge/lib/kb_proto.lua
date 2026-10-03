-- kb_proto: the message envelope, sequence numbers and dispatch
-- (protocol/README.md, docs/PROTOCOL.md).
--
--   {"type": "player.pos", "seq": 42, "re": 7, "data": {...}}
--
-- seq counts from 1 per connection on each side; re is set on replies only;
-- data is always an object ({} when empty). A line that isn't a valid
-- envelope is answered with an `error` message (code bad_envelope), and an
-- unknown type with code unknown_type. Error messages never echo the line.
--
--   local proto = Proto.new({ json = json, write = net.send, log = log })
--   proto.on("ping", function(data, msg) proto.reply(msg, "pong", { t = data.t }) end)
--   net's onLine = proto.receive; on a new connection, proto.reset()

local Proto = {}

Proto.PROTOCOL = 1
Proto.MAX_TYPE = 64
Proto.MAX_SEQ = 9007199254740991 -- 2^53 - 1: the largest exact integer in a double

local byte, find, format, gmatch, match = string.byte, string.find, string.format, string.gmatch, string.match
local concat = table.concat
local floor = math.floor

-- Only the file:line of a Lua error is logged; the message itself could
-- quote a value.
local function where(err)
  return type(err) == "string" and match(err, "[%w_/%.%-]+%.lua:%d+") or "unknown"
end

local ENVELOPE_KEYS = { type = true, seq = true, re = true, data = true }

-- lowercase dotted words: ^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*$
function Proto.validType(t)
  if type(t) ~= "string" or #t == 0 or #t > Proto.MAX_TYPE then return false end
  for word in gmatch(t .. ".", "([^.]*)%.") do
    if not find(word, "^[a-z][a-z0-9_]*$") then return false end
  end
  return true
end

function Proto.validSeq(v)
  return type(v) == "number" and v >= 1 and v <= Proto.MAX_SEQ and v == floor(v)
end

-- Checks a decoded envelope. Returns true, or false plus a reason code.
function Proto.check(json, env)
  if not json.isObject(env) then return false, "not_object" end
  for k in pairs(env) do
    if not ENVELOPE_KEYS[k] then return false, "unknown_field" end
  end
  if not Proto.validType(env.type) then return false, "bad_type" end
  if not Proto.validSeq(env.seq) then return false, "bad_seq" end
  if env.re ~= nil and not Proto.validSeq(env.re) then return false, "bad_re" end
  if not json.isObject(env.data) then return false, "bad_data" end
  return true
end

-- opts:
--   json     kb_json
--   write    function(line): sends one line (kb_net's net.send)
--   log      kb_log logger (optional)
--   maxLine  decode limit in bytes (default 4 MiB)
--   catalog  kb_catalog (optional) and leg, the hop we receive on ("m2g"):
--            a type with no handler is then answered by what the catalog
--            says: unknown_type when it isn't in the protocol, wrong_leg
--            when it never travels this way, and nothing at all when it is
--            ours to receive but not handled yet (logged once, at debug)
--   gate     function(env) -> false to stop a valid envelope before dispatch
--            (the session's hello-first rule); runs before reply matching
function Proto.new(opts)
  local json = assert(opts and opts.json, "kb_proto needs kb_json")
  local write = assert(opts.write, "kb_proto needs a write function")
  local log = opts.log
  local maxLine = opts.maxLine or 4 * 1024 * 1024
  local catalog = opts.catalog
  local leg = opts.leg
  local gate = opts.gate

  local proto = {}
  local handlers = {}
  local pending = {}
  local seq = 0
  local stats = { received = 0, sent = 0, rejected = 0, unknown = 0, unhandled = 0 }

  local function warn(code, fields) if log then log.warn(code, fields) end end

  -- New connection: numbering starts again and old requests are forgotten.
  function proto.reset()
    seq = 0
    pending = {}
  end

  function proto.on(msgType, handler)
    assert(Proto.validType(msgType), "kb_proto.on: bad message type")
    handlers[msgType] = handler
  end

  -- Sends a message; returns its seq, or nil plus an error.
  function proto.send(msgType, data, re)
    if not Proto.validType(msgType) then return nil, "bad_type" end
    seq = seq + 1
    local env = json.object({ type = msgType, seq = seq, data = json.object(data or {}) })
    if re ~= nil then env.re = re end
    local line, err = json.encode(env)
    if not line then
      warn("proto.encode_failed", { type = msgType })
      return nil, err
    end
    write(line)
    stats.sent = stats.sent + 1
    return seq
  end

  -- Answers msg (a received envelope) with re = msg.seq.
  function proto.reply(msg, msgType, data)
    return proto.send(msgType, data, msg.seq)
  end

  -- Answers msg with data that is already encoded JSON object text, e.g. a
  -- cached export page, so a large payload is never decoded and encoded
  -- again. The envelope keeps kb_json's sorted key order (data, re, seq,
  -- type), so the line is the same canonical JSON a full encode would give.
  function proto.replyEncoded(msg, msgType, dataText)
    if not Proto.validType(msgType) then return nil, "bad_type" end
    if type(dataText) ~= "string" or byte(dataText, 1) ~= 123 or not Proto.validSeq(msg.seq) then
      return nil, "bad_data"
    end
    seq = seq + 1
    write(concat({ '{"data":', dataText, ',"re":', format("%d", msg.seq), ',"seq":', format("%d", seq),
                   ',"type":"', msgType, '"}' }))
    stats.sent = stats.sent + 1
    return seq
  end

  -- Sends a request; onReply(data, msg) runs when a message with re = its seq
  -- arrives (instead of that type's handler).
  function proto.request(msgType, data, onReply)
    local s, err = proto.send(msgType, data)
    if not s then return nil, err end
    if onReply then pending[s] = onReply end
    return s
  end

  function proto.sendError(code, re, msg)
    return proto.send("error", { code = code, msg = msg or code }, re)
  end

  -- Handles one received line. Returns true when it was dispatched.
  function proto.receive(line)
    local env = json.decode(line, maxLine)
    local ok, why
    if env == nil then
      ok, why = false, "json"
    else
      ok, why = Proto.check(json, env)
    end
    if not ok then
      stats.rejected = stats.rejected + 1
      warn("proto.bad_envelope", { reason = why, bytes = #line })
      -- the error itself is never answered, so two peers can't loop
      if not (type(env) == "table" and env.type == "error") then
        local re = type(env) == "table" and Proto.validSeq(env.seq) and env.seq or nil
        proto.sendError("bad_envelope", re, why)
      end
      return false
    end
    stats.received = stats.received + 1
    if gate then
      local gok, pass = pcall(gate, env)
      if not gok then
        warn("proto.gate_failed", { type = env.type, at = where(pass) })
        return false
      end
      if pass == false then return false end
    end
    if env.re ~= nil and pending[env.re] then
      local callback = pending[env.re]
      pending[env.re] = nil
      local cok, cerr = pcall(callback, env.data, env)
      if not cok then warn("proto.reply_failed", { type = env.type, at = where(cerr) }) end
      return true
    end
    local handler = handlers[env.type]
    if not handler then
      local known = catalog and catalog.types[env.type]
      if known and leg and known[leg] then
        -- ours to receive, just not handled (yet): no error, and one log line
        stats.unhandled = stats.unhandled + 1
        if log then log.once("unhandled:" .. env.type, "debug", "proto.unhandled", { type = env.type }) end
        return false
      end
      if known and leg then
        stats.rejected = stats.rejected + 1
        warn("proto.wrong_leg", { type = env.type })
        if env.type ~= "error" then proto.sendError("wrong_leg", env.seq, "wrong_leg") end
        return false
      end
      stats.unknown = stats.unknown + 1
      warn("proto.unknown_type", { type = env.type })
      if env.type ~= "error" then proto.sendError("unknown_type", env.seq, "unknown_type") end
      return false
    end
    local hok, herr = pcall(handler, env.data, env)
    if not hok then
      warn("proto.handler_failed", { type = env.type, at = where(herr) })
      if env.type ~= "error" then proto.sendError("handler_failed", env.seq, "handler_failed") end
      return false
    end
    return true
  end

  function proto.stats()
    local out = { seq = seq }
    for k, v in pairs(stats) do out[k] = v end
    return out
  end

  return proto
end

return Proto
