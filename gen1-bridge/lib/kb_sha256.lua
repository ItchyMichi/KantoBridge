-- kb_sha256: SHA-256 (FIPS 180-4) on LuaJIT's bit library.
--
-- Logs and test output may only show short hashes of game data, never the
-- data itself, and export pages carry content hashes; both need SHA-256.
-- Written from the specification for Kanto Bridge.
--
--   sha256.hex(s)    64 lowercase hex digits
--   sha256.short(s)  the first 8 of them
--   sha256.digest(s) the 32 raw bytes
--   sha256.new()     incremental: h:update(s) ... h:hex() / h:digest()

local bit = bit
local band, bor, bxor, bnot = bit.band, bit.bor, bit.bxor, bit.bnot
local rshift, lshift, ror, tobit, tohex = bit.rshift, bit.lshift, bit.ror, bit.tobit, bit.tohex
local byte, char, sub, rep = string.byte, string.char, string.sub, string.rep
local concat = table.concat
local floor = math.floor

local sha256 = {}

-- first 32 bits of the fractional parts of the cube roots of the first 64 primes
local K = {
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
}
for i = 1, 64 do K[i] = tobit(K[i]) end

-- first 32 bits of the fractional parts of the square roots of the first 8 primes
local H0 = {
  0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
  0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
}
for i = 1, 8 do H0[i] = tobit(H0[i]) end

local W = {}

-- one 64-byte block of s starting at byte `at`
local function compress(H, s, at)
  for t = 0, 15 do
    local a, b, c, d = byte(s, at + t * 4, at + t * 4 + 3)
    W[t] = bor(lshift(a, 24), lshift(b, 16), lshift(c, 8), d)
  end
  for t = 16, 63 do
    local w15, w2 = W[t - 15], W[t - 2]
    local s0 = bxor(ror(w15, 7), ror(w15, 18), rshift(w15, 3))
    local s1 = bxor(ror(w2, 17), ror(w2, 19), rshift(w2, 10))
    W[t] = tobit(W[t - 16] + s0 + W[t - 7] + s1)
  end
  local a, b, c, d, e, f, g, h = H[1], H[2], H[3], H[4], H[5], H[6], H[7], H[8]
  for t = 0, 63 do
    local S1 = bxor(ror(e, 6), ror(e, 11), ror(e, 25))
    local ch = bxor(band(e, f), band(bnot(e), g))
    local t1 = tobit(h + S1 + ch + K[t + 1] + W[t])
    local S0 = bxor(ror(a, 2), ror(a, 13), ror(a, 22))
    local maj = bxor(band(a, b), band(a, c), band(b, c))
    local t2 = tobit(S0 + maj)
    h, g, f, e, d, c, b, a = g, f, e, tobit(d + t1), c, b, a, tobit(t1 + t2)
  end
  H[1], H[2], H[3], H[4] = tobit(H[1] + a), tobit(H[2] + b), tobit(H[3] + c), tobit(H[4] + d)
  H[5], H[6], H[7], H[8] = tobit(H[5] + e), tobit(H[6] + f), tobit(H[7] + g), tobit(H[8] + h)
end

local Hasher = {}
Hasher.__index = Hasher

function sha256.new()
  local H = {}
  for i = 1, 8 do H[i] = H0[i] end
  return setmetatable({ H = H, tail = "", length = 0, done = false }, Hasher)
end

function Hasher:update(s)
  assert(not self.done, "kb_sha256: update after finish")
  assert(type(s) == "string", "kb_sha256: update expects a string")
  self.length = self.length + #s
  local data = s
  if #self.tail > 0 then data = self.tail .. s end
  local n = #data
  local full = n - n % 64
  for at = 1, full, 64 do compress(self.H, data, at) end
  self.tail = sub(data, full + 1)
  return self
end

local function finish(self)
  if not self.done then
    local bits = self.length * 8
    local tail = self.tail
    local pad = 64 - (#tail + 9) % 64
    if pad == 64 then pad = 0 end
    -- the message length in bits as a 64-bit big-endian number
    local hi = floor(bits / 4294967296)
    local lo = bits % 4294967296
    local block = tail .. "\128" .. rep("\0", pad) .. char(
      floor(hi / 16777216) % 256, floor(hi / 65536) % 256, floor(hi / 256) % 256, hi % 256,
      floor(lo / 16777216) % 256, floor(lo / 65536) % 256, floor(lo / 256) % 256, lo % 256)
    for at = 1, #block, 64 do compress(self.H, block, at) end
    self.tail = ""
    self.done = true
  end
  return self.H
end

function Hasher:hex()
  local H = finish(self)
  local out = {}
  for i = 1, 8 do out[i] = tohex(H[i], 8) end
  return concat(out)
end

function Hasher:digest()
  local H = finish(self)
  local out = {}
  for i = 1, 8 do
    local v = H[i]
    out[i] = char(band(rshift(v, 24), 255), band(rshift(v, 16), 255),
      band(rshift(v, 8), 255), band(v, 255))
  end
  return concat(out)
end

function sha256.hex(s) return sha256.new():update(s):hex() end
function sha256.digest(s) return sha256.new():update(s):digest() end
function sha256.short(s) return sub(sha256.hex(s), 1, 8) end

return sha256
