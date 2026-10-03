-- kb_images: images and sprite sheets for the export (docs/PROTOCOL.md
-- "Export resources", protocol/export/image.schema.json and
-- sprite.schema.json).
--
-- The pixels come from kb_engine.imagePng (the engine's own image loading,
-- encoded as PNG); this file turns them into resources: base64 text, the
-- size, and an id derived from the asset path at run time, "<folder>.<name>"
-- from its last two segments (a tileset atlas ".../tilesets/<name>.png" is
-- "tilesets.<name>"). No asset folder is ever written out here: the paths
-- come from the records.

local Images = {}

Images.MAX_SIDE = 1024          -- image.schema.json w/h
Images.MAX_B64 = 1048576        -- image.schema.json png maxLength
Images.ID_PATTERN = "^[A-Za-z0-9_.:-]+$" -- defs.schema.json id
Images.SPRITE_PATTERN = "^[A-Z0-9_]+$"   -- defs.schema.json spriteId

local byte, sub = string.byte, string.sub
local concat = table.concat
local floor = math.floor

local ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
local ONE, PAIR = {}, {}
for i = 0, 63 do ONE[i] = sub(ALPHABET, i + 1, i + 1) end
for i = 0, 4095 do PAIR[i] = ONE[floor(i / 64)] .. ONE[i % 64] end

-- Standard base64 (RFC 4648, with padding), as Python's base64.b64encode.
function Images.base64(s)
  local n = #s
  local out, k = {}, 0
  local full = n - n % 3
  for i = 1, full, 3 do
    local a, b, c = byte(s, i, i + 2)
    local v = a * 65536 + b * 256 + c
    k = k + 1
    out[k] = PAIR[floor(v / 4096)] .. PAIR[v % 4096]
  end
  local rest = n - full
  if rest == 1 then
    local a = byte(s, n)
    k = k + 1
    out[k] = PAIR[a * 16] .. "=="
  elseif rest == 2 then
    local a, b = byte(s, n - 1, n)
    local v = a * 1024 + b * 4
    k = k + 1
    out[k] = PAIR[floor(v / 64)] .. ONE[v % 64] .. "="
  end
  return concat(out, "", 1, k)
end

-- "<folder>.<name>" from an asset path's last two segments, or nil when the
-- path doesn't give a valid id.
function Images.idFor(path)
  if type(path) ~= "string" then return nil end
  local folder, file = path:match("([^/\\]+)[/\\]([^/\\]+)$")
  if not folder then return nil end
  local name = file:gsub("%.[Pp][Nn][Gg]$", "")
  local id = folder .. "." .. name
  if #id > 64 or not id:find(Images.ID_PATTERN) then return nil end
  return id
end

local function sideOk(v)
  return type(v) == "number" and v == floor(v) and v >= 1 and v <= Images.MAX_SIDE
end

-- An image resource, or nil plus a warning code.
function Images.image(id, png, w, h)
  if not (sideOk(w) and sideOk(h)) then return nil, "image_size" end
  local b64 = Images.base64(png)
  if #b64 > Images.MAX_B64 then return nil, "image_too_large" end
  return { id = id, w = w, h = h, png = b64 }
end

-- A sprite sheet resource (a vertical strip of 16x16 frames: 0-2 standing
-- down/up/left, 3-5 walking; RomExtractor's record carries `frames` and
-- `walker`), or nil plus a warning code. The record's frame count wins
-- when the sheet holds at least that many frames (a ROM can ship a
-- shorter sheet than its manifest names).
function Images.sprite(id, record, png, w, h)
  if type(id) ~= "string" or #id > 64 or not id:find(Images.SPRITE_PATTERN) then return nil, "sprite_id" end
  if w ~= 16 or not sideOk(h) or h < 16 then return nil, "sprite_size" end
  local fit = floor(h / 16)
  local frames = type(record) == "table" and record.frames or nil
  if type(frames) ~= "number" or frames ~= floor(frames) or frames < 1 or frames > fit then frames = fit end
  if frames > 64 then frames = 64 end
  -- a walker needs the walking frames 3-5; a record may still opt out
  local walker = frames >= 6 and not (type(record) == "table" and record.walker == false)
  local b64 = Images.base64(png)
  if #b64 > Images.MAX_B64 then return nil, "image_too_large" end
  return { id = id, w = 16, h = h, frames = frames, walker = walker, png = b64 }
end

return Images
