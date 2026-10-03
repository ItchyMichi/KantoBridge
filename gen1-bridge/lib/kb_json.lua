-- kb_json: gen1-bridge's strict, deterministic JSON codec.
--
-- Written for Kanto Bridge (not derived from the engine's own codec), because
-- the protocol needs things that codec lacks: {} vs [] kept apart, null kept
-- inside arrays, NaN/infinity refused, trailing data refused, surrogate pairs
-- decoded, and sorted keys so equal values always give identical bytes.
--
-- Kinds. Lua has one table type, so JSON's object/array split needs marks:
--   json.object(t) and json.array(t) mark a table (and return it);
--   decoded objects and arrays carry the same marks, so {} and [] round-trip;
--   json.null stands for null (a plain nil would vanish from a table).
-- An unmarked table is an array when its keys are exactly 1..n, an object when
-- every key is a string, and [] when it is empty. Mark a map that may be empty
-- with json.object. Mixed or sparse tables are refused.
--
-- Neither function throws on bad input: both return nil plus a message. The
-- message names a reason and a byte offset or key path, never the input text,
-- because that text may be ROM-derived.

local json = {}

local byte, char, find, format, gsub, sub =
  string.byte, string.char, string.find, string.format, string.gsub, string.sub
local concat, sort = table.concat, table.sort
local floor, huge = math.floor, math.huge

json.MAX_DEPTH = 64
-- integers up to 2^53 are exact in a double; larger integer literals would
-- silently change value, so the decoder refuses them
local MAX_EXACT = 9007199254740992
local MAX_EXACT_DIGITS = "9007199254740992"

-- The marks are metatables; nothing in them changes how the table behaves.
local OBJECT = { kind = "object" }
local ARRAY = { kind = "array" }
json.OBJECT, json.ARRAY = OBJECT, ARRAY

json.null = setmetatable({}, {
  kind = "null",
  __tostring = function() return "null" end,
  __newindex = function() error("kb_json.null is read-only", 2) end,
})
local NULL = json.null

local function mark(t, mt, what)
  if t == nil then return setmetatable({}, mt) end
  if type(t) ~= "table" or t == NULL then
    error(("kb_json.%s expects a table"):format(what), 3)
  end
  local current = getmetatable(t)
  if current ~= nil and current ~= mt then
    error(("kb_json.%s: table already has a metatable"):format(what), 3)
  end
  return setmetatable(t, mt)
end

function json.object(t) return mark(t, OBJECT, "object") end
function json.array(t) return mark(t, ARRAY, "array") end

-- "object", "array", "null", or the Lua type name for anything else.
function json.kind(v)
  if v == NULL then return "null" end
  if type(v) == "table" then
    local mt = getmetatable(v)
    if mt == OBJECT then return "object" end
    if mt == ARRAY then return "array" end
    return "table"
  end
  return type(v)
end

function json.isObject(v) return type(v) == "table" and getmetatable(v) == OBJECT end
function json.isArray(v) return type(v) == "table" and getmetatable(v) == ARRAY end

-- ------------------------------------------------------------ UTF-8

-- Same rules as a strict UTF-8 decoder (Python's): no overlong forms, no
-- surrogates, nothing above U+10FFFF, no truncated sequences.
-- Returns true, or false plus the byte offset of the first bad sequence.
local function utf8Valid(s)
  local i = find(s, "[\128-\255]")
  if not i then return true end
  local n = #s
  while i do
    local c = byte(s, i)
    if c >= 0xC2 and c <= 0xDF then
      local c2 = byte(s, i + 1)
      if not c2 or c2 < 0x80 or c2 > 0xBF then return false, i end
      i = i + 2
    elseif c >= 0xE0 and c <= 0xEF then
      local c2, c3 = byte(s, i + 1, i + 2)
      if not c3 then return false, i end
      local lo, hi = 0x80, 0xBF
      if c == 0xE0 then lo = 0xA0 elseif c == 0xED then hi = 0x9F end
      if c2 < lo or c2 > hi or c3 < 0x80 or c3 > 0xBF then return false, i end
      i = i + 3
    elseif c >= 0xF0 and c <= 0xF4 then
      local c2, c3, c4 = byte(s, i + 1, i + 3)
      if not c4 then return false, i end
      local lo, hi = 0x80, 0xBF
      if c == 0xF0 then lo = 0x90 elseif c == 0xF4 then hi = 0x8F end
      if c2 < lo or c2 > hi or c3 < 0x80 or c3 > 0xBF or c4 < 0x80 or c4 > 0xBF then
        return false, i
      end
      i = i + 4
    else
      return false, i
    end
    if i > n then return true end
    i = find(s, "[\128-\255]", i)
  end
  return true
end
json.utf8Valid = utf8Valid

local function utf8Char(cp)
  if cp < 0x80 then return char(cp) end
  if cp < 0x800 then
    return char(0xC0 + floor(cp / 0x40), 0x80 + cp % 0x40)
  end
  if cp < 0x10000 then
    return char(0xE0 + floor(cp / 0x1000), 0x80 + floor(cp / 0x40) % 0x40,
      0x80 + cp % 0x40)
  end
  return char(0xF0 + floor(cp / 0x40000), 0x80 + floor(cp / 0x1000) % 0x40,
    0x80 + floor(cp / 0x40) % 0x40, 0x80 + cp % 0x40)
end

-- ------------------------------------------------------------ encoding

-- The same escapes as Python's json.dumps(ensure_ascii=False): quote,
-- backslash and control characters only, lowercase hex.
local ESCAPES = {
  ['"'] = '\\"', ["\\"] = "\\\\", ["\b"] = "\\b", ["\f"] = "\\f",
  ["\n"] = "\\n", ["\r"] = "\\r", ["\t"] = "\\t",
}
for code = 0, 31 do
  local c = char(code)
  if not ESCAPES[c] then ESCAPES[c] = format("\\u%04x", code) end
end
local ESCAPE_PATTERN = '[%z\1-\31"\\]'

-- Keys that are safe to show in an error path; anything else is elided.
local function pathKey(k)
  if type(k) == "number" then return tostring(k) end
  if type(k) == "string" and #k <= 64 and find(k, "^[A-Za-z0-9_.:%-]+$") then return k end
  return "<key>"
end

local function encodeError(path, reason)
  local parts = {}
  for i = 1, #path do parts[i] = pathKey(path[i]) end
  error({ kbjson = "kb_json: " .. reason .. " at /" .. concat(parts, "/") }, 0)
end

local function encodeString(s, path)
  local ok = utf8Valid(s)
  if not ok then encodeError(path, "invalid UTF-8 in string") end
  return '"' .. gsub(s, ESCAPE_PATTERN, ESCAPES) .. '"'
end

local function encodeNumber(v, path)
  if v ~= v or v == huge or v == -huge then
    encodeError(path, "cannot encode NaN or infinity")
  end
  if v == floor(v) and v >= -MAX_EXACT and v <= MAX_EXACT then
    return format("%d", v)
  end
  return format("%.17g", v)
end

-- "object", "array" plus its length; refuses mixed, sparse and foreign tables
local function shapeOf(t, path)
  local mt = getmetatable(t)
  if mt ~= nil and mt ~= OBJECT and mt ~= ARRAY then
    encodeError(path, "cannot encode a table with a foreign metatable")
  end
  local count, strings, maxIndex = 0, 0, 0
  for k in pairs(t) do
    count = count + 1
    local kt = type(k)
    if kt == "string" then
      strings = strings + 1
    elseif kt == "number" and k >= 1 and k == floor(k) then
      if k > maxIndex then maxIndex = k end
    else
      encodeError(path, "table key is neither a string nor a positive integer")
    end
  end
  if mt == OBJECT then
    if strings ~= count then encodeError(path, "object has a non-string key") end
    return "object"
  end
  if mt == ARRAY then
    if strings ~= 0 or maxIndex ~= count then
      encodeError(path, "array has holes or string keys")
    end
    return "array", count
  end
  if count == 0 then return "array", 0 end
  if strings == count then return "object" end
  if strings == 0 and maxIndex == count then return "array", count end
  encodeError(path, "mixed or sparse table")
end

local encodeValue

local function encodeTable(t, out, path, active)
  if active[t] then encodeError(path, "cycle") end
  if #path >= json.MAX_DEPTH then encodeError(path, "nesting too deep") end
  active[t] = true
  local shape, n = shapeOf(t, path)
  local depth = #path + 1
  if shape == "array" then
    out[#out + 1] = "["
    for i = 1, n do
      if i > 1 then out[#out + 1] = "," end
      path[depth] = i
      encodeValue(t[i], out, path, active)
    end
    out[#out + 1] = "]"
  else
    local keys, nk = {}, 0
    for k in pairs(t) do nk = nk + 1; keys[nk] = k end
    sort(keys)
    out[#out + 1] = "{"
    for i = 1, nk do
      local k = keys[i]
      if i > 1 then out[#out + 1] = "," end
      path[depth] = k
      out[#out + 1] = encodeString(k, path)
      out[#out + 1] = ":"
      encodeValue(t[k], out, path, active)
    end
    out[#out + 1] = "}"
  end
  path[depth] = nil
  active[t] = nil
end

encodeValue = function(v, out, path, active)
  local vt = type(v)
  if vt == "string" then
    out[#out + 1] = encodeString(v, path)
  elseif vt == "number" then
    out[#out + 1] = encodeNumber(v, path)
  elseif vt == "boolean" then
    out[#out + 1] = v and "true" or "false"
  elseif v == NULL then
    out[#out + 1] = "null"
  elseif vt == "table" then
    encodeTable(v, out, path, active)
  else
    encodeError(path, "cannot encode a " .. vt)
  end
end

-- string, or nil plus a message
function json.encode(value)
  local out = {}
  local ok, err = pcall(encodeValue, value, out, {}, {})
  if ok then return concat(out) end
  if type(err) == "table" and err.kbjson then return nil, err.kbjson end
  return nil, "kb_json: internal error while encoding: " .. tostring(err)
end

-- ------------------------------------------------------------ decoding

local function decodeError(reason, pos)
  error({ kbjson = format("kb_json: %s at byte %d", reason, pos) }, 0)
end

local UNESCAPES = {
  [34] = '"', [92] = "\\", [47] = "/", [98] = "\b", [102] = "\f",
  [110] = "\n", [114] = "\r", [116] = "\t",
}
local STRING_STOP = '[%z\1-\31"\\]'

local function skipSpace(s, i)
  local _, e = find(s, "^[ \t\r\n]*", i)
  return e + 1
end

local function hex4(s, i)
  local h = sub(s, i, i + 3)
  if not find(h, "^[0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F]$") then return nil end
  return tonumber(h, 16)
end

local function parseString(s, i)
  local j = i + 1
  local parts, np = nil, 0
  while true do
    local k = find(s, STRING_STOP, j)
    if not k then decodeError("unterminated string", i) end
    local c = byte(s, k)
    if c == 34 then
      if not parts then return sub(s, j, k - 1), k + 1 end
      if k > j then np = np + 1; parts[np] = sub(s, j, k - 1) end
      return concat(parts, "", 1, np), k + 1
    elseif c == 92 then
      parts = parts or {}
      if k > j then np = np + 1; parts[np] = sub(s, j, k - 1) end
      local e = byte(s, k + 1)
      if e == 117 then
        local cp = hex4(s, k + 2)
        if not cp then decodeError("bad \\u escape", k) end
        local after = k + 6
        if cp >= 0xD800 and cp <= 0xDBFF then
          if sub(s, after, after + 1) ~= "\\u" then decodeError("unpaired surrogate", k) end
          local low = hex4(s, after + 2)
          if not low then decodeError("bad \\u escape", after) end
          if low < 0xDC00 or low > 0xDFFF then decodeError("unpaired surrogate", k) end
          cp = 0x10000 + (cp - 0xD800) * 0x400 + (low - 0xDC00)
          after = after + 6
        elseif cp >= 0xDC00 and cp <= 0xDFFF then
          decodeError("unpaired surrogate", k)
        end
        np = np + 1; parts[np] = utf8Char(cp)
        j = after
      else
        local r = e and UNESCAPES[e]
        if not r then decodeError("bad escape", k) end
        np = np + 1; parts[np] = r
        j = k + 2
      end
    else
      decodeError("control character in string", k)
    end
  end
end

local function parseNumber(s, i)
  local digits = i
  if byte(s, i) == 45 then digits = i + 1 end
  local _, e = find(s, "^[0-9]+", digits)
  if not e then decodeError("bad number", i) end
  if byte(s, digits) == 48 and e > digits then decodeError("leading zero", i) end
  local j = e + 1
  local integral = true
  if byte(s, j) == 46 then
    local _, fe = find(s, "^[0-9]+", j + 1)
    if not fe then decodeError("bad fraction", j) end
    j = fe + 1
    integral = false
  end
  local c = byte(s, j)
  if c == 101 or c == 69 then
    local _, ee = find(s, "^[+-]?[0-9]+", j + 1)
    if not ee then decodeError("bad exponent", j) end
    j = ee + 1
    integral = false
  end
  if integral and e - digits + 1 >= 16 then
    -- compare the digits, not the double: 2^53 + 1 would round to 2^53
    local d = sub(s, digits, e)
    if #d > 16 or d > MAX_EXACT_DIGITS then decodeError("integer out of range", i) end
  end
  local v = tonumber(sub(s, i, j - 1))
  if not v or v ~= v or v == huge or v == -huge then decodeError("number out of range", i) end
  if v == 0 then v = 0 end -- -0 reads as 0
  return v, j
end

local parseValue

local function parseArray(s, i, depth)
  if depth > json.MAX_DEPTH then decodeError("nesting too deep", i) end
  local arr = setmetatable({}, ARRAY)
  local j = skipSpace(s, i + 1)
  if byte(s, j) == 93 then return arr, j + 1 end
  local n = 0
  while true do
    local v
    v, j = parseValue(s, j, depth)
    n = n + 1
    arr[n] = v
    j = skipSpace(s, j)
    local c = byte(s, j)
    if c == 44 then
      j = skipSpace(s, j + 1)
    elseif c == 93 then
      return arr, j + 1
    elseif c == nil then
      decodeError("unexpected end of input", j)
    else
      decodeError("expected ',' or ']'", j)
    end
  end
end

local function parseObject(s, i, depth)
  if depth > json.MAX_DEPTH then decodeError("nesting too deep", i) end
  local obj = setmetatable({}, OBJECT)
  local j = skipSpace(s, i + 1)
  if byte(s, j) == 125 then return obj, j + 1 end
  while true do
    if byte(s, j) ~= 34 then
      if byte(s, j) == nil then decodeError("unexpected end of input", j) end
      decodeError("expected a string key", j)
    end
    local keyAt = j
    local key
    key, j = parseString(s, j)
    if rawget(obj, key) ~= nil then decodeError("duplicate key", keyAt) end
    j = skipSpace(s, j)
    if byte(s, j) ~= 58 then decodeError("expected ':'", j) end
    j = skipSpace(s, j + 1)
    local v
    v, j = parseValue(s, j, depth)
    obj[key] = v
    j = skipSpace(s, j)
    local c = byte(s, j)
    if c == 44 then
      j = skipSpace(s, j + 1)
    elseif c == 125 then
      return obj, j + 1
    elseif c == nil then
      decodeError("unexpected end of input", j)
    else
      decodeError("expected ',' or '}'", j)
    end
  end
end

parseValue = function(s, i, depth)
  local c = byte(s, i)
  if c == 123 then return parseObject(s, i, depth + 1) end
  if c == 91 then return parseArray(s, i, depth + 1) end
  if c == 34 then return parseString(s, i) end
  if c == 45 or (c and c >= 48 and c <= 57) then return parseNumber(s, i) end
  if c == 116 then
    if sub(s, i, i + 3) == "true" then return true, i + 4 end
  elseif c == 102 then
    if sub(s, i, i + 4) == "false" then return false, i + 5 end
  elseif c == 110 then
    if sub(s, i, i + 3) == "null" then return NULL, i + 4 end
  elseif c == nil then
    decodeError("unexpected end of input", i)
  end
  decodeError("unexpected character", i)
end

local function decodeAll(s)
  local v, j = parseValue(s, skipSpace(s, 1), 0)
  j = skipSpace(s, j)
  if j <= #s then decodeError("trailing data", j) end
  return v
end

-- value, or nil plus a message. maxBytes optionally caps the input size.
function json.decode(s, maxBytes)
  if type(s) ~= "string" then return nil, "kb_json: expected a string" end
  if maxBytes and #s > maxBytes then return nil, "kb_json: input too long" end
  local valid, at = utf8Valid(s)
  if not valid then return nil, format("kb_json: invalid UTF-8 at byte %d", at) end
  local ok, result = pcall(decodeAll, s)
  if ok then return result end
  if type(result) == "table" and result.kbjson then return nil, result.kbjson end
  return nil, "kb_json: internal error while decoding: " .. tostring(result)
end

return json
