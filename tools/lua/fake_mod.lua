-- A stand-in for the `mod` object Gen1Recomp's loader hands a mod
-- (refs/gen1recomp/src/mods/Loader.lua:1414-1826 in v0.3.85; the object is
-- the previous pin's (v0.3.49), 74 lines down: the loader's fast require path, :247-300,
-- serves engine code with no owning mod and never a mod's own require),
-- with just the parts gen1-bridge uses, plus test controls. Written for
-- Kanto Bridge.
--
--   local mod = FakeMod.new({ read = fn(rel) -> source | nil, err })
--   FakeMod.emit(mod, "game.ready", { game = fakeGame })   -- engine events
--   FakeMod.call(mod, "input.step", vanilla, game, dt)      -- engine hooks
--   mod._log  -- every line mod.log printed, as the engine's Logger prints it

local FakeMod = {}

local GB_BUTTONS = { a = true, b = true, start = true, select = true,
                     up = true, down = true, left = true, right = true }

function FakeMod.new(opts)
  opts = opts or {}
  local id = opts.id or "gen1-bridge"
  local listeners, chains = {}, {}
  local lines = {}
  local mod = {
    id = id,
    version = opts.version or "0.1.0",
    path = "mods/" .. id,
    developer = opts.developer == true,
    generation = 1,
    manifest = opts.manifest or { id = id },
    exports = {},
    _log = lines,
    _listeners = listeners,
    _chains = chains,
    _input = {},
  }

  -- Logger.emit (src/core/Logger.lua:26-32): "[level] " .. format(fmt, ...),
  -- where format (:5-24, since v0.3.85) never throws: when the arguments
  -- don't fit the directives, every directive becomes %s over tostring'd
  -- arguments and the error rides along on the line
  local function format(fmt, ...)
    local n = select("#", ...)
    if n == 0 then return tostring(fmt) end
    local ok, msg = pcall(string.format, fmt, ...)
    if ok then return msg end
    local plain, wanted = tostring(fmt):gsub("%%%%", "\0"):gsub("%%[-+ #0]*%d*%.?%d*%a", "%%s")
    local args = { ... }
    n = math.max(n, wanted)
    for i = 1, n do args[i] = tostring(args[i]) end
    local okPlain, text = pcall(string.format, (plain:gsub("%z", "%%%%")), unpack(args, 1, n))
    if not okPlain then text = tostring(fmt) .. " " .. table.concat(args, " ", 1, n) end
    return string.format("%s (bad log format: %s)", text, tostring(msg))
  end
  local function logger(level)
    return function(_, fmt, ...)
      local msg = format("[%s] " .. fmt, id, ...)
      lines[#lines + 1] = ("[%s] %s"):format(level, msg)
    end
  end
  mod.log = { info = logger("info"), warn = logger("warn"), error = logger("error") }

  function mod:read(rel)
    assert(type(rel) == "string" and not rel:find("%.%."), "mod:read: bad path")
    return opts.read(rel)
  end

  local function subscribe(name, callback, priority, onceOnly)
    assert(type(name) == "string" and name ~= "", "event name is required")
    assert(type(callback) == "function", "event callback must be a function")
    local list = listeners[name] or {}
    listeners[name] = list
    local entry = { callback = callback, priority = priority or 0, once = onceOnly }
    list[#list + 1] = entry
    table.sort(list, function(a, b) return a.priority > b.priority end)
    return function()
      for i, e in ipairs(list) do if e == entry then table.remove(list, i) break end end
    end
  end
  mod.events = {
    on = function(_, name, callback, priority) return subscribe(name, callback, priority, false) end,
    once = function(_, name, callback, priority) return subscribe(name, callback, priority, true) end,
    emit = function(_, name, payload)
      local prefix = "mod." .. id .. "."
      if type(name) ~= "string" or name:sub(1, #prefix) ~= prefix then
        error(("[%s] mods may only emit %s* events"):format(id, prefix), 0)
      end
      return FakeMod.emit(mod, name, payload)
    end,
  }

  mod.hooks = {
    wrap = function(_, name, callback, priority)
      assert(type(name) == "string" and name ~= "", "hook name is required")
      assert(type(callback) == "function", "hook callback must be a function")
      local chain = chains[name] or {}
      chains[name] = chain
      local entry = { callback = callback, priority = priority or 0 }
      chain[#chain + 1] = entry
      table.sort(chain, function(a, b) return a.priority > b.priority end)
      return function()
        for i, e in ipairs(chain) do if e == entry then table.remove(chain, i) break end end
      end
    end,
  }

  -- mod.options (Loader.lua:1671-1689): define stores the schema; get returns
  -- a stored value (opts.options, as the F10 manager would save it), else
  -- the row's default.
  local stored = opts.options or {}
  mod._optionSchema = nil
  mod.options = {
    define = function(_, schema)
      assert(type(schema) == "table", "options schema must be a table of rows")
      for _, row in ipairs(schema) do
        assert(type(row) == "table" and type(row.key) == "string" and row.key ~= "",
          "each options row needs a string key")
      end
      mod._optionSchema = schema
      return schema
    end,
    get = function(_, key)
      if stored[key] ~= nil then return stored[key] end
      for _, row in ipairs(mod._optionSchema or {}) do
        if row.key == key then return row.default end
      end
      return nil
    end,
  }

  -- mod.save (Loader.lua:1615-1630): this mod's bucket of the save's
  -- modData (Game:adoptSave, src/core/Game.lua:1348-1360). Tests reach the
  -- bucket as mod._save (opts.save to start with values).
  local bucket = opts.save or {}
  mod._save = bucket
  mod.save = {
    get = function(_, key, default)
      local value = bucket[key]
      if value == nil then return default end
      return value
    end,
    set = function(_, key, value) bucket[key] = value end,
  }

  -- mod.cache (Loader.lua:1641-1643, src/mods/ImportAccess.lua:72-122):
  -- installation-wide bytes under mod_cache/<id>/, not tied to a playthrough.
  -- opts.cache is the backing table (rel -> bytes), so a test can hand the
  -- same files to a "restarted" mod; mod._cacheControl.failWrites makes
  -- every write fail. Paths follow SafePath (no "..", no leading "/", no "\").
  local cacheFiles = opts.cache or {}
  local cacheControl = { failWrites = false, writes = 0 }
  mod._cache, mod._cacheControl = cacheFiles, cacheControl
  local function safeRel(rel, what)
    if type(rel) ~= "string" or rel == "" or rel:find("..", 1, true) or rel:sub(1, 1) == "/"
        or rel:find("\\", 1, true) then
      error(what .. " must stay inside its root", 0)
    end
  end
  mod.cache = {
    write = function(_, rel, bytes)
      safeRel(rel, "mod.cache:write")
      if type(bytes) ~= "string" then return nil, "mod.cache:write expects a byte string" end
      if cacheControl.failWrites then return false, "could not write" end
      cacheControl.writes = cacheControl.writes + 1
      cacheFiles[rel] = bytes
      return true
    end,
    read = function(_, rel)
      safeRel(rel, "mod.cache:read")
      local v = cacheFiles[rel]
      if v == nil then return nil, "no file: " .. rel end
      return v
    end,
    info = function(_, rel)
      safeRel(rel, "mod.cache:info")
      local v = cacheFiles[rel]
      return v ~= nil and { type = "file", size = #v } or nil
    end,
    exists = function(self, rel) return self:info(rel) ~= nil end,
    delete = function(_, rel)
      safeRel(rel, "mod.cache:delete")
      cacheFiles[rel] = nil
      return true
    end,
  }

  -- mod.ui: the UI module tables (src/ui/ModUI.lua:8-27); tests pass fakes.
  mod.ui = opts.ui or {}

  -- mod.datasets (Loader.lua:1435-1443): open(version) -> a read-only view,
  -- or nil plus a reason. The fake engine provides one; without it the API
  -- is absent, as on an engine that predates dataset views.
  mod.datasets = opts.datasets

  -- mod.content.<registry>:get(id) (Loader.lua:1336-1339), over plain tables
  -- of records the test provides: opts.content = { pokemon = { ID = rec } }.
  mod.content = {}
  for name, records in pairs(opts.content or {}) do
    mod.content[name] = { get = function(_, id) return records[id] end }
  end
  -- Wild Routes (WR2): full registries (fake_registry.lua) and mod.world
  -- (fake_worldapi.lua) when a test provides them
  if opts.registries then mod.content = opts.registries end
  mod.world = opts.world

  -- Records button calls. When the game has an input with sources (the fake
  -- world's), presses go through it as the Loader's do
  -- (src/mods/Loader.lua:1467-1505): every call is its own
  -- "mod:<id>:<n>" source, and release only knows this mod's tokens.
  local input = mod._input
  local ledger, seq = {}, 0
  local function source()
    seq = seq + 1
    return "mod:" .. id .. ":" .. seq
  end
  mod.input = {
    tap = function(_, game, btn)
      assert(game, "mod.input needs the live game (see game.ready)")
      assert(GB_BUTTONS[btn], "unknown GB button: " .. tostring(btn))
      input[#input + 1] = { op = "tap", btn = btn }
      local gi = game.input
      if gi and gi.sourcePress then
        local s = source()
        gi:sourcePress(btn, s)
        gi:sourceRelease(btn, s)
      end
    end,
    press = function(_, game, btn)
      assert(game, "mod.input needs the live game (see game.ready)")
      assert(GB_BUTTONS[btn], "unknown GB button: " .. tostring(btn))
      local token = { btn = btn }
      input[#input + 1] = { op = "press", btn = btn, token = token }
      local gi = game.input
      if gi and gi.sourcePress then
        local s = source()
        gi:sourcePress(btn, s)
        ledger[token] = { input = gi, btn = btn, source = s }
      end
      return token
    end,
    release = function(_, token)
      input[#input + 1] = { op = "release", token = token }
      local rec = ledger[token]
      if rec then
        ledger[token] = nil
        rec.input:sourceRelease(rec.btn, rec.source)
      end
      return true
    end,
  }

  return mod
end

-- Engine event (Events.emit, src/mods/Events.lua:46-61): listeners run in
-- priority order over a snapshot; a throwing listener is logged and skipped.
function FakeMod.emit(mod, name, payload)
  local list = mod._listeners[name]
  if not list then return end
  local snapshot = {}
  for i = 1, #list do snapshot[i] = list[i] end
  for _, entry in ipairs(snapshot) do
    if entry.once then
      for i, e in ipairs(list) do if e == entry then table.remove(list, i) break end end
    end
    local ok, err = pcall(entry.callback, payload)
    if not ok then
      mod._log[#mod._log + 1] = ("[error] [%s] event %s: %s"):format(mod.id, name, tostring(err))
    end
  end
end

-- Engine hook (simplified Hooks.call, src/mods/Hooks.lua:62-104): wrappers
-- run outermost-first by priority; each receives `next`; a wrapper that throws
-- before calling next is logged and skipped.
function FakeMod.call(mod, name, vanilla, ...)
  local chain = mod._chains[name]
  if not chain or #chain == 0 then return vanilla(...) end
  local function run(index, ...)
    if index > #chain then return vanilla(...) end
    local args = { n = select("#", ...), ... }
    local called = false
    local function nextFn(...)
      called = true
      if select("#", ...) == 0 then return run(index + 1, unpack(args, 1, args.n)) end
      return run(index + 1, ...)
    end
    local res = { pcall(chain[index].callback, nextFn, unpack(args, 1, args.n)) }
    if res[1] then return unpack(res, 2, table.maxn(res)) end
    mod._log[#mod._log + 1] = ("[warn] [%s] hook %s failed: %s"):format(mod.id, name, tostring(res[2]))
    if called then return end
    return run(index + 1, unpack(args, 1, args.n))
  end
  return run(1, ...)
end

return FakeMod
