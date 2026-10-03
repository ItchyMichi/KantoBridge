-- kb_log: gen1-bridge's redacted logger.
--
-- Data-safety rule (docs/PLAN.md section 0.3): a log line may show message
-- types, engine symbol ids, counts, lengths and short hashes, never game text.
-- So callers pass a code plus structured fields, and only numbers, booleans
-- and id-shaped strings print as they are; any other string prints as
-- <str:LENGTH:SHA8>. Payloads are never logged.
--
--   local log = Log.new({ write = fn(level, line), hash = sha256.short })
--   log.info("net.listen", { port = 27151 })   -> "net.listen port=27151"
--   log.once("bind", "warn", "net.bind_failed", { err = "addr_in_use" })
--
-- `write` receives "info", "warn" or "error" (debug lines go out as info when
-- enabled). setForward(fn) also hands warnings and errors to fn(level, code,
-- line), e.g. to send them to Minecraft as protocol `log` messages.

local Log = {}

local find, format, gsub = string.find, string.format, string.gsub
local concat, sort = table.concat, table.sort
local floor = math.floor

-- symbol ids (PALLET_TOWN), dotted codes, numbers-with-units and file:line spots
local SAFE = "^[A-Za-z0-9_.:/%-]+$"
local MAX_ID = 64

local function isSafe(s)
  return #s > 0 and #s <= MAX_ID and find(s, SAFE) ~= nil
end
Log.isSafe = isSafe

-- length in code points (continuation bytes don't count)
local function codepoints(s)
  local _, cont = gsub(s, "[\128-\191]", "")
  return #s - cont
end

local function number(v)
  if v ~= v then return "nan" end
  if v == floor(v) and v > -1e15 and v < 1e15 then return format("%d", v) end
  return format("%.6g", v)
end

-- The printable form of one value. Exposed for callers that build their own
-- lines (and for tests).
function Log.redact(v, hash)
  local t = type(v)
  if t == "number" then return number(v) end
  if t == "boolean" then return v and "true" or "false" end
  if t == "nil" then return "nil" end
  if t == "string" then
    if isSafe(v) then return v end
    return format("<str:%d:%s>", codepoints(v), hash and hash(v) or "?")
  end
  if t == "table" then
    local n = 0
    for _ in pairs(v) do n = n + 1 end
    return format("<tbl:%d>", n)
  end
  return "<" .. t .. ">"
end

local function render(code, fields, hash)
  local head = (type(code) == "string" and isSafe(code)) and code or "<bad-code>"
  if fields == nil then return head end
  if type(fields) ~= "table" then return head .. " " .. Log.redact(fields, hash) end
  local keys = {}
  for k in pairs(fields) do keys[#keys + 1] = k end
  sort(keys, function(a, b) return tostring(a) < tostring(b) end)
  local parts = { head }
  for i = 1, #keys do
    local k = keys[i]
    local name = (type(k) == "string" and isSafe(k)) and k or "<key>"
    parts[#parts + 1] = name .. "=" .. Log.redact(fields[k], hash)
  end
  return concat(parts, " ")
end
Log.render = render

function Log.new(opts)
  opts = opts or {}
  local write = opts.write or function() end
  local hash = opts.hash
  local debugOn = opts.debug == true
  local forward
  local seen = {}
  local log = {}

  local function emit(level, code, fields)
    local line = render(code, fields, hash)
    if level == "debug" then
      if not debugOn then return line end
      write("info", "debug " .. line)
      return line
    end
    write(level, line)
    if forward and (level == "warn" or level == "error") then
      -- a broken forwarder must never break the caller's path
      pcall(forward, level, (type(code) == "string" and isSafe(code)) and code or "bad_code", line)
    end
    return line
  end

  function log.debug(code, fields) return emit("debug", code, fields) end
  function log.info(code, fields) return emit("info", code, fields) end
  function log.warn(code, fields) return emit("warn", code, fields) end
  function log.error(code, fields) return emit("error", code, fields) end

  -- logs only the first time `key` is seen, until log.rearm(key)
  function log.once(key, level, code, fields)
    if seen[key] then return nil end
    seen[key] = true
    return emit(level, code, fields)
  end
  function log.rearm(key) seen[key] = nil end

  function log.setForward(fn) forward = fn end
  function log.setDebug(on) debugOn = on == true end
  function log.redact(v) return Log.redact(v, hash) end

  return log
end

-- The write function for the engine's mod.log: format strings always go
-- through string.format there, so the line is passed as an argument, never
-- as the format.
function Log.modWriter(modLog)
  return function(level, line)
    local fn = modLog[level] or modLog.info
    fn(modLog, "%s", line)
  end
end

return Log
