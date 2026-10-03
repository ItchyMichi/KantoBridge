-- A stand-in for the parts of LÖVE 11.5's love.graphics and love.data the
-- mirror capture uses (observe/kb_mirror.lua), with checks for the rules
-- refs/_research/P2_gen1_ui.md sections 1.6 and 9 set: newImageData only on
-- a canvas that isn't the render target, push/pop balanced, the previous
-- target restored, encode never given a filename. Written for Kanto Bridge.
--
--   local L = FakeLove.new()
--   love.graphics = L.graphics; love.data = L.data
--   L.source(content)        a "composite" canvas whose picture is `content`
--   L.pngPad = n             extra bytes in every encoded PNG (size tests)
--   L.failDraw = true        draw throws (a broken capture)
-- A canvas's picture is a string; drawing copies it, so a frame's PNG
-- carries the content of the composite it came from.

local FakeLove = {}

local ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

local function base64(s)
  local out = {}
  for i = 1, #s, 3 do
    local a, b, c = s:byte(i, i + 2)
    local n = a * 65536 + (b or 0) * 256 + (c or 0)
    local c1 = math.floor(n / 262144) % 64
    local c2 = math.floor(n / 4096) % 64
    local c3 = math.floor(n / 64) % 64
    local c4 = n % 64
    out[#out + 1] = ALPHABET:sub(c1 + 1, c1 + 1) .. ALPHABET:sub(c2 + 1, c2 + 1)
      .. (b and ALPHABET:sub(c3 + 1, c3 + 1) or "=") .. (c and ALPHABET:sub(c4 + 1, c4 + 1) or "=")
  end
  return table.concat(out)
end
FakeLove.base64 = base64

local function u32(n)
  return string.char(math.floor(n / 16777216) % 256, math.floor(n / 65536) % 256, math.floor(n / 256) % 256, n % 256)
end

-- PNG signature + an IHDR chunk with the size (CRC left zero) + the picture
local function png(w, h, content, pad)
  return "\137PNG\r\n\26\n" .. u32(13) .. "IHDR" .. u32(w) .. u32(h) .. "\8\6\0\0\0" .. u32(0)
    .. content .. string.rep("\0", pad or 0)
end

function FakeLove.new()
  local L = {
    depth = 0, maxDepth = 0, readbacks = 0, encodes = 0, draws = {}, canvases = 0,
    pngPad = 0, failDraw = false, problems = {},
  }
  local saved = {}
  local active -- the current render target (nil = the screen)

  local function problem(what) L.problems[#L.problems + 1] = what end

  local Canvas = {}
  Canvas.__index = Canvas
  function Canvas:getDimensions() return self.w, self.h end
  function Canvas:getWidth() return self.w end
  function Canvas:getHeight() return self.h end
  function Canvas:setFilter(min, mag) self.filter = min .. "/" .. mag end
  function Canvas:newImageData()
    if active == self then problem("newImageData on the active canvas") end
    L.readbacks = L.readbacks + 1
    local w, h, content = self.w, self.h, self.content
    local data = { released = false }
    function data.getWidth() return w end
    function data.getHeight() return h end
    function data.getString() return content .. string.rep("\0", 16) end
    function data.encode(_, fmt, filename)
      if fmt ~= "png" then problem("encode " .. tostring(fmt)) end
      if filename ~= nil then problem("encode wrote a file") end
      L.encodes = L.encodes + 1
      local bytes = png(w, h, content, L.pngPad)
      return { getString = function() return bytes end, release = function() end }
    end
    function data.release() data.released = true end
    data.content = content
    L.lastImage = data
    return data
  end

  local function newCanvas(w, h, opts, content)
    L.canvases = L.canvases + 1
    return setmetatable({ w = w, h = h, content = content or "", dpiscale = opts and opts.dpiscale }, Canvas)
  end

  function L.source(content, w, h)
    local c = newCanvas(w or 800, h or 720, nil, content)
    L.canvases = L.canvases - 1 -- the engine's, not ours
    return c
  end

  local g = {}
  function g.newCanvas(w, h, opts) return newCanvas(w, h, opts) end
  function g.newQuad(x, y, w, h, sw, sh) return { x = x, y = y, w = w, h = h, sw = sw, sh = sh } end
  function g.push(kind)
    if kind ~= "all" then problem("push " .. tostring(kind)) end
    saved[#saved + 1] = active
    L.depth = L.depth + 1
    if L.depth > L.maxDepth then L.maxDepth = L.depth end
  end
  function g.pop()
    if L.depth == 0 then problem("pop without push") return end
    active = saved[#saved]
    saved[#saved] = nil
    L.depth = L.depth - 1
  end
  function g.setCanvas(c) active = c end
  function g.getCanvas() return active end
  function g.origin() end
  function g.setScissor() end
  function g.setShader() end
  function g.setBlendMode(mode) L.blend = mode end
  function g.setColor() end
  function g.clear() if active then active.content = "" end end
  function g.draw(src, quad, x, y, r, sx, sy)
    if L.failDraw then error("fake draw failure", 0) end
    L.draws[#L.draws + 1] = { quad = quad, sx = sx, sy = sy, to = active }
    if active then active.content = src.content end
  end
  L.graphics = g

  local d = {}
  function d.hash(fn, data)
    if fn ~= "md5" then problem("hash " .. tostring(fn)) end
    return "md5:" .. data.content
  end
  function d.encode(container, format, s)
    if container ~= "string" or format ~= "base64" then problem("data.encode") end
    return base64(s)
  end
  L.data = d

  function L.active() return active end
  function L.setActive(c) active = c end
  return L
end

return FakeLove
