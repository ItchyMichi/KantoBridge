-- A stand-in for the `mod` object Gen1Recomp's loader hands a mod
-- (refs/gen1recomp/src/mods/Loader.lua:1340-1752 in v0.3.49), with just the
-- parts gen1-bridge uses, plus test controls. Written for Kanto Bridge.
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

  -- Logger.emit (src/core/Logger.lua:5-13): "[level] " .. string.format(fmt, ...)
  local function logger(level)
    return function(_, fmt, ...)
      local msg = string.format("[%s] " .. fmt, id, ...)
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

  -- Records button calls; the real ones go through game.input sources.
  local input = mod._input
  mod.input = {
    tap = function(_, game, btn)
      assert(game, "mod.input needs the live game (see game.ready)")
      assert(GB_BUTTONS[btn], "unknown GB button: " .. tostring(btn))
      input[#input + 1] = { op = "tap", btn = btn }
    end,
    press = function(_, game, btn)
      assert(game, "mod.input needs the live game (see game.ready)")
      assert(GB_BUTTONS[btn], "unknown GB button: " .. tostring(btn))
      local token = { btn = btn }
      input[#input + 1] = { op = "press", btn = btn, token = token }
      return token
    end,
    release = function(_, token)
      input[#input + 1] = { op = "release", token = token }
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
