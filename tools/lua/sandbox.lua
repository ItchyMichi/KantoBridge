-- Test copy of the environment Gen1Recomp gives a mod's code, so gen1-bridge
-- runs under lupa with the same globals it gets in game.
--
-- Mirrors refs/gen1recomp/src/mods/Sandbox.lua in the pinned v0.3.85:
--   baseGlobals        :172-185   (the global list, per-mod copies, safe os)
--   require deny lists :25-89, :239-254
--   load / loadstring  :203-232   (string or reader, no bytecode, mod env)
--   envFor             :256-276   (love facade, _G = env)
-- (unchanged since the previous pin, v0.3.49; the tag added only Sandbox.evalData, :296-304,
-- which the loader uses for data files and no mod environment sees).
-- Re-check it whenever the Gen1Recomp pin changes.
--
-- Deliberately stricter in one way: the engine also adds LegacyCompat shims
-- (io, package, loadfile, dofile, os.getenv/remove/rename stand-ins) for old
-- mods. gen1-bridge must never use them, so they are absent here and any use
-- fails the tests.

local Sandbox = {}

local DENIED = {
  io = "the filesystem", os = "the filesystem", debug = "the debug library",
  package = "the module loader", ffi = "arbitrary C calls",
}
local DENIED_PREFIX = { love = true, ffi = true, jit = true }
local NETWORK = { socket = true, enet = true, http = true, https = true,
                  ssl = true, mime = true, ltn12 = true }

local function head(name) return name:match("^([^%.]+)") or name end

-- nil when allowed, else the denial message (same wording as the engine)
function Sandbox.denial(name, permissions)
  if type(name) ~= "string" then return nil end
  local root = head(name)
  if DENIED[root] then
    return ("%s is not available to mods (it grants %s); use mod.storage, "
      .. "mod:read, mod:list and the engine API instead"):format(name, DENIED[root])
  end
  if DENIED_PREFIX[root] and name ~= root then
    return ("%s is not available to mods; use mod.storage, mod:read, mod:list "
      .. "and the engine API instead"):format(name)
  end
  if NETWORK[root] and not (permissions or {}).network then
    return ('%s needs the "network" permission in manifest.json'):format(name)
  end
  return nil
end

local function copy(source)
  local out = {}
  for k, v in pairs(source) do out[k] = v end
  return out
end

local BLOCKED_LOVE = { filesystem = true, thread = true, system = true, event = true }

local function loveFacade(love, permissions)
  if love == nil then return nil end
  return setmetatable({}, {
    __index = function(_, key)
      if key == "thread" and (permissions or {}).compute then return love.thread end
      if BLOCKED_LOVE[key] then
        error(("love.%s is not available to mods"):format(key), 2)
      end
      return love[key]
    end,
    __newindex = function(_, key)
      error(("mods cannot assign love.%s"):format(tostring(key)), 2)
    end,
  })
end

local function compile(source, chunkname, env)
  if type(source) == "string" and source:sub(1, 1) == "\27" then
    return nil, (chunkname or "chunk") .. ": mods must ship Lua source, not bytecode"
  end
  local chunk, err = loadstring(source, chunkname)
  if not chunk then return nil, err end
  return setfenv(chunk, env)
end
Sandbox.compile = compile

-- opts.modId        for require error messages (default "gen1-bridge")
-- opts.permissions  set of manifest permissions, e.g. { network = true }
-- opts.modules      what the real require would hand back, by name: a value,
--                   or a function(name) returning one (fakes for socket, src.*)
-- opts.love         a fake love table for the facade (optional)
-- opts.jitOs        what jit.os reads in the sandbox (default: this host's), so a test
--                   pins the platform main.lua sees (its SO_REUSEADDR rule)
-- opts.strict       fail on reads of unknown globals and on new globals
function Sandbox.env(opts)
  opts = opts or {}
  local modId = opts.modId or "gen1-bridge"
  local permissions = opts.permissions or {}
  local modules = opts.modules or {}
  local env = {
    assert = assert, error = error, ipairs = ipairs, next = next,
    pairs = pairs, pcall = pcall, xpcall = xpcall, select = select,
    tonumber = tonumber, tostring = tostring, type = type, unpack = unpack,
    rawequal = rawequal, rawget = rawget, rawset = rawset, rawlen = rawlen,
    setmetatable = setmetatable, getmetatable = getmetatable, print = print,
    collectgarbage = collectgarbage, _VERSION = _VERSION,
    coroutine = copy(coroutine), math = copy(math), string = copy(string),
    table = copy(table), bit = copy(bit),
    jit = opts.jitOs and setmetatable({ os = opts.jitOs }, { __index = jit }) or jit,
    os = { time = os.time, date = os.date, clock = os.clock, difftime = os.difftime },
  }
  env.love = loveFacade(opts.love, permissions)
  env.require = function(name)
    local denial = Sandbox.denial(name, permissions)
    if denial then error(("[%s] %s"):format(modId, denial), 2) end
    local found = modules[name]
    if found == nil then
      error(("module '%s' not found (test harness: not provided)"):format(tostring(name)), 2)
    end
    if type(found) == "function" then found = found(name) end
    return found
  end
  local function sandboxedLoad(chunk, chunkname)
    if type(chunk) == "function" then
      local parts = {}
      while true do
        local piece = chunk()
        if piece == nil or piece == "" then break end
        parts[#parts + 1] = piece
      end
      chunk = table.concat(parts)
    end
    if type(chunk) ~= "string" then return nil, "load expects a string or reader" end
    return compile(chunk, chunkname or "=(load)", env)
  end
  env.load = sandboxedLoad
  env.loadstring = sandboxedLoad
  env._G = env
  if opts.strict then
    -- Test-only: the real env returns nil for an unknown global, which hides
    -- typos; here reading or creating one fails the test.
    setmetatable(env, {
      __index = function(_, k) error("undefined global " .. tostring(k), 2) end,
      __newindex = function(_, k) error("assignment to undeclared global " .. tostring(k), 2) end,
    })
  end
  return env
end

-- Loads one gen1-bridge file the way main.lua does (load(mod:read(rel),
-- "@" .. rel)()) and returns what it returns.
function Sandbox.import(env, source, rel)
  local chunk, err = env.load(source, "@" .. rel)
  if not chunk then error(err, 0) end
  return chunk()
end

-- Runs an entry chunk the way the loader does (src/mods/Loader.lua:1867-1877):
-- chunk(api), and if that returns a function, call it with api too.
function Sandbox.runEntry(env, source, rel, api)
  local chunk, err = compile(source, "@" .. rel, env)
  if not chunk then error(err, 0) end
  local result = chunk(api)
  if type(result) == "function" then result(api) end
end

return Sandbox
