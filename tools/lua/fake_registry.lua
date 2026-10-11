-- Fake content registries for the Wild Routes tests (WR2): Gen1Recomp's
-- src/mods/Registry.lua and the loader's per-mod accessor
-- (src/mods/Loader.lua:1190-1260) as gen1-bridge uses them, and the
-- map_scripts compose rule (src/script/MapScripts.lua:1-27). Written for
-- Kanto Bridge; every record the tests install is made up.
--
--   FakeRegistry.content(spec)   spec = { records = { maps = {...}, ... },
--                                        reject = { maps = true, ... } }
--     record registries (maps, tilesets, trainers, music, map_songs, field,
--     items, pokemon, moves): get/has fold register/override/patch/remove
--     over the base records; register collides with an existing id; a patch
--     merges tables per key and replaces lists wholesale. `reject[name]`
--     makes every write to that registry throw, as a schema violation does
--     for an api 2 mod. Each registry keeps its writes in order (`writes`).
--     map_scripts is a compose registry: every registration is kept, the
--     latest first; onStep(id, ...) runs the chain until one returns true.
--   FakeRegistry.merge(content, data, names)   the engine's merge, for the
--     fake live data: what was registered or patched in the named registries
--     (default: trainers) lands in data

local FakeRegistry = {}

local function copy(v)
  if type(v) ~= "table" then return v end
  local out = {}
  for k, x in pairs(v) do out[k] = copy(x) end
  return out
end

local function isList(t)
  local n = #t
  if n == 0 then return next(t) == nil end
  local count = 0
  for _ in pairs(t) do count = count + 1 end
  return count == n
end

local function merge(base, patch)
  if type(base) ~= "table" or type(patch) ~= "table" or isList(patch) then return copy(patch) end
  local out = copy(base)
  for k, v in pairs(patch) do out[k] = merge(out[k], v) end
  return out
end

function FakeRegistry.record(name, base, reject)
  base = base or {}
  local reg = { name = name, writes = {} }
  local ops = {}
  local function fold(id)
    local v = copy(base[id])
    for _, o in ipairs(ops[id] or {}) do
      if o[1] == "register" or o[1] == "override" then v = copy(o[2])
      elseif o[1] == "patch" then v = merge(v, o[2])
      elseif o[1] == "remove" then v = nil end
    end
    return v
  end
  local function write(op, id, value)
    if reject then error(("%s: %s %s rejected"):format(name, op, tostring(id)), 0) end
    ops[id] = ops[id] or {}
    table.insert(ops[id], { op, value })
    reg.writes[#reg.writes + 1] = { op = op, id = id, value = value }
  end
  function reg.get(_, id) return fold(id) end
  function reg.has(_, id) return fold(id) ~= nil end
  function reg.register(_, id, value)
    assert(value ~= nil, name .. " value is required")
    if not reject and fold(id) ~= nil then error(("%s already registered: %s"):format(name, tostring(id)), 0) end
    write("register", id, value)
  end
  function reg.override(_, id, value) write("override", id, value) end
  function reg.patch(_, id, partial) write("patch", id, partial) end
  function reg.remove(_, id) write("remove", id, nil) end
  function reg.ids()
    local seen, out = {}, {}
    for id in pairs(base) do seen[id] = true end
    for id in pairs(ops) do seen[id] = true end
    for id in pairs(seen) do if fold(id) ~= nil then out[#out + 1] = id end end
    table.sort(out)
    return out
  end
  return reg
end

function FakeRegistry.compose(name, reject)
  local reg = { name = name, writes = {}, chains = {} }
  function reg.register(_, id, value)
    if reject then error(("%s: register %s rejected"):format(name, tostring(id)), 0) end
    reg.chains[id] = reg.chains[id] or {}
    table.insert(reg.chains[id], 1, value)
    reg.writes[#reg.writes + 1] = { op = "register", id = id, value = value }
  end
  function reg.get(_, id)
    local chain = reg.chains[id]
    return chain and chain[1] or nil
  end
  function reg.onStep(id, ...)
    for _, v in ipairs(reg.chains[id] or {}) do
      if type(v) == "table" and type(v.onStep) == "function" and v.onStep(...) then return true end
    end
    return false
  end
  return reg
end

FakeRegistry.RECORDS = { "maps", "tilesets", "trainers", "music", "map_songs", "field", "items", "pokemon", "moves" }

function FakeRegistry.content(spec)
  spec = spec or {}
  local records, reject = spec.records or {}, spec.reject or {}
  local out = {}
  for _, name in ipairs(FakeRegistry.RECORDS) do
    out[name] = FakeRegistry.record(name, records[name], reject[name])
  end
  out.map_scripts = FakeRegistry.compose("map_scripts", reject.map_scripts)
  return out
end

-- The merge boundary for the fake live data: what gen1-bridge registered or
-- patched in the named registries lands in data (as Loader's merge does).
function FakeRegistry.merge(content, data, names)
  for _, name in ipairs(names or { "trainers" }) do
    data[name] = data[name] or {}
    for _, id in ipairs(content[name].ids()) do data[name][id] = content[name]:get(id) end
  end
end

return FakeRegistry
